using System;
using System.Collections.Concurrent;
using System.Globalization;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services.Media;

/// <summary>A call to Lidarr, Shelfarr, Librarr, a Readarr-API app or a public metadata service failed in a way worth
/// showing to the person.</summary>
public sealed class MediaServiceException : Exception
{
    /// <summary>
    /// Initializes a new instance of the <see cref="MediaServiceException"/> class.
    /// </summary>
    /// <param name="service">The app's name as people know it ("Lidarr").</param>
    /// <param name="message">Message.</param>
    /// <param name="status">HTTP status to return to the browser.</param>
    /// <param name="unreachable">Whether the app didn't answer at all.</param>
    public MediaServiceException(string service, string message, HttpStatusCode status = HttpStatusCode.BadGateway, bool unreachable = false)
        : base(message)
    {
        Service = service;
        Status = status;
        Unreachable = unreachable;
    }

    /// <summary>Gets the app's name.</summary>
    public string Service { get; }

    /// <summary>Gets the HTTP status to return.</summary>
    public HttpStatusCode Status { get; }

    /// <summary>Gets a value indicating whether the app didn't answer.</summary>
    public bool Unreachable { get; }
}

/// <summary>JSON over HTTP for the request apps and metadata services: timeouts, a short pause after an app stops
/// answering (so pages don't keep waiting on it), small response caches, and errors people can read.</summary>
public sealed class ServiceHttp
{
    /// <summary>The User-Agent MusicBrainz, ListenBrainz and Open Library ask clients to send.</summary>
    public const string UserAgent = "Seedy/0.2 ( https://github.com/Arkmind/seedy )";

    private readonly IHttpClientFactory _http;
    private readonly ILogger<ServiceHttp> _logger;
    private readonly ConcurrentDictionary<string, (DateTime At, JsonNode? Node)> _cache = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, DateTime> _down = new(StringComparer.Ordinal);

    /// <summary>
    /// Initializes a new instance of the <see cref="ServiceHttp"/> class.
    /// </summary>
    /// <param name="http">HTTP client factory.</param>
    /// <param name="logger">Logger.</param>
    public ServiceHttp(IHttpClientFactory http, ILogger<ServiceHttp> logger)
    {
        _http = http;
        _logger = logger;
    }

    /// <summary>Forgets cached answers and pauses for one app (after its address or key changes).</summary>
    /// <param name="service">The app's name.</param>
    public void Reset(string service)
    {
        _down.TryRemove(service, out _);
        foreach (var key in _cache.Keys.Where(k => k.StartsWith(service + "|", StringComparison.Ordinal)).ToList())
        {
            _cache.TryRemove(key, out _);
        }
    }

    /// <summary>Forgets one cached answer, or every answer whose address starts with the given text.</summary>
    /// <param name="service">The app's name.</param>
    /// <param name="urlPrefix">Address or its start.</param>
    public void Forget(string service, string urlPrefix)
    {
        foreach (var key in _cache.Keys.Where(k => k.StartsWith(service + "|" + urlPrefix, StringComparison.Ordinal)).ToList())
        {
            _cache.TryRemove(key, out _);
        }
    }

    /// <summary>Gets a cached GET answer without calling the app.</summary>
    /// <param name="service">The app's name.</param>
    /// <param name="url">Full address.</param>
    /// <param name="ttl">How old the answer may be.</param>
    /// <param name="node">The answer.</param>
    /// <returns>True when there was one.</returns>
    public bool TryCached(string service, string url, TimeSpan ttl, out JsonNode? node)
    {
        if (_cache.TryGetValue(service + "|" + url, out var hit) && DateTime.UtcNow - hit.At < ttl)
        {
            node = hit.Node;
            return true;
        }

        node = null;
        return false;
    }

    /// <summary>Sends a request and reads JSON back.</summary>
    /// <param name="service">The app's name, for messages and the pause after failures.</param>
    /// <param name="method">Method.</param>
    /// <param name="url">Full address.</param>
    /// <param name="headers">Adds the app's credentials.</param>
    /// <param name="body">Body to send as JSON.</param>
    /// <param name="ct">Cancellation.</param>
    /// <param name="cache">Keep a GET's answer this long.</param>
    /// <param name="timeout">Give up after this long (15 s by default).</param>
    /// <param name="failFast">Skip the call for 30 s after the app stopped answering.</param>
    /// <returns>The JSON, or null for an empty answer.</returns>
    public async Task<JsonNode?> SendAsync(
        string service,
        HttpMethod method,
        string url,
        Action<HttpRequestMessage>? headers,
        object? body,
        CancellationToken ct,
        TimeSpan? cache = null,
        TimeSpan? timeout = null,
        bool failFast = true)
    {
        var key = service + "|" + url;
        if (cache is not null && method == HttpMethod.Get && _cache.TryGetValue(key, out var hit) && DateTime.UtcNow - hit.At < cache.Value)
        {
            return hit.Node;
        }

        if (failFast && _down.TryGetValue(service, out var until) && DateTime.UtcNow < until)
        {
            throw new MediaServiceException(service, $"Couldn't reach {service}.", HttpStatusCode.BadGateway, unreachable: true);
        }

        using var req = new HttpRequestMessage(method, url);
        req.Headers.Accept.ParseAdd("application/json");
        req.Headers.UserAgent.ParseAdd(UserAgent);
        headers?.Invoke(req);
        if (body is not null)
        {
            var json = body is JsonNode node ? node.ToJsonString() : JsonSerializer.Serialize(body);
            req.Content = new StringContent(json, Encoding.UTF8, "application/json");
        }

        var client = _http.CreateClient("Seedy");
        client.Timeout = timeout ?? TimeSpan.FromSeconds(15);
        HttpResponseMessage res;
        try
        {
            res = await client.SendAsync(req, ct).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException && !ct.IsCancellationRequested)
        {
            if (failFast)
            {
                _down[service] = DateTime.UtcNow.AddSeconds(30);
            }

            throw new MediaServiceException(service, $"Couldn't reach {service}.", HttpStatusCode.BadGateway, unreachable: true);
        }

        using (res)
        {
            _down.TryRemove(service, out _);
            var text = await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            if (res.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden)
            {
                throw new MediaServiceException(service, ErrorText(text) ?? $"{service} refused the key.", HttpStatusCode.Forbidden);
            }

            if (!res.IsSuccessStatusCode)
            {
                var detail = ErrorText(text);
                _logger.LogWarning("{Service} {Method} {Url} returned {Status}: {Detail}", service, method, Redact(url), (int)res.StatusCode, detail ?? Short(text));
                throw new MediaServiceException(
                    service,
                    detail ?? string.Create(CultureInfo.InvariantCulture, $"{service} returned {(int)res.StatusCode}."),
                    res.StatusCode == HttpStatusCode.NotFound ? HttpStatusCode.NotFound : res.StatusCode == HttpStatusCode.UnprocessableEntity || res.StatusCode == HttpStatusCode.BadRequest ? HttpStatusCode.BadRequest : HttpStatusCode.BadGateway);
            }

            JsonNode? parsed = null;
            if (!string.IsNullOrWhiteSpace(text))
            {
                try
                {
                    parsed = JsonNode.Parse(text);
                }
                catch (JsonException)
                {
                    throw new MediaServiceException(service, $"{service}'s address didn't answer like {service}. Check the address in Seedy's admin.");
                }
            }

            if (cache is not null && method == HttpMethod.Get)
            {
                _cache[key] = (DateTime.UtcNow, parsed);
                if (_cache.Count > 800)
                {
                    foreach (var old in _cache.OrderBy(k => k.Value.At).Take(200).Select(k => k.Key).ToList())
                    {
                        _cache.TryRemove(old, out _);
                    }
                }
            }

            return parsed;
        }
    }

    private static string Short(string text) => text.Length > 300 ? text[..300] : text;

    private static string Redact(string url)
    {
        var q = url.IndexOf("apikey=", StringComparison.OrdinalIgnoreCase);
        return q < 0 ? url : url[..q] + "apikey=…";
    }

    /* The *arr apps answer errors as [{ errorMessage }] or { message }; Shelfarr as { errors: [] }; Librarr as { error }. */
    private static string? ErrorText(string text)
    {
        try
        {
            var node = JsonNode.Parse(text);
            if (node is JsonArray arr)
            {
                return arr.Select(e => J.Str(e?["errorMessage"])).FirstOrDefault(m => m is not null);
            }

            return J.Str(node?["message"])
                ?? J.Str(node?["error"])
                ?? (node?["errors"] is JsonArray errs ? errs.Select(e => e is JsonValue ? J.Str(e) : J.Str(e?["message"])).FirstOrDefault(m => m is not null) : null);
        }
        catch (Exception ex) when (ex is JsonException or InvalidOperationException or FormatException)
        {
            return null;
        }
    }
}

/// <summary>Small helpers for reading JSON from other apps.</summary>
internal static class J
{
    public static string? Str(JsonNode? n)
    {
        if (n is not JsonValue v)
        {
            return null;
        }

        if (v.TryGetValue<string>(out var s))
        {
            return string.IsNullOrWhiteSpace(s) ? null : s;
        }

        if (v.TryGetValue<long>(out var l))
        {
            return l.ToString(CultureInfo.InvariantCulture);
        }

        return v.TryGetValue<double>(out var d) ? d.ToString(CultureInfo.InvariantCulture) : null;
    }

    public static int? Int(JsonNode? n)
    {
        if (n is not JsonValue v)
        {
            return null;
        }

        if (v.TryGetValue<int>(out var i))
        {
            return i;
        }

        if (v.TryGetValue<long>(out var l))
        {
            return (int)Math.Clamp(l, int.MinValue, int.MaxValue);
        }

        if (v.TryGetValue<double>(out var d))
        {
            return (int)d;
        }

        return v.TryGetValue<string>(out var s) && int.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out var p) ? p : null;
    }

    public static long? Long(JsonNode? n)
    {
        if (n is not JsonValue v)
        {
            return null;
        }

        if (v.TryGetValue<long>(out var l))
        {
            return l;
        }

        return v.TryGetValue<double>(out var d) ? (long)d : null;
    }

    public static double? Double(JsonNode? n)
    {
        if (n is not JsonValue v)
        {
            return null;
        }

        if (v.TryGetValue<double>(out var d))
        {
            return d;
        }

        return v.TryGetValue<string>(out var s) && double.TryParse(s, NumberStyles.Float, CultureInfo.InvariantCulture, out var p) ? p : null;
    }

    public static bool Bool(JsonNode? n) => n is JsonValue v && v.TryGetValue<bool>(out var b) && b;

    public static DateTime? Date(JsonNode? n)
    {
        var s = Str(n);
        return s is not null && DateTime.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal | DateTimeStyles.AssumeUniversal, out var d) ? d : null;
    }

    public static JsonArray Arr(JsonNode? n) => n as JsonArray ?? [];
}
