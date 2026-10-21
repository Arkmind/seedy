using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Plugin.Seedy.Api;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>A Seerr call failed in a way worth showing to the person.</summary>
public sealed class SeerrException : Exception
{
    /// <summary>
    /// Initializes a new instance of the <see cref="SeerrException"/> class.
    /// </summary>
    /// <param name="message">Message.</param>
    /// <param name="status">HTTP status to return to the browser.</param>
    public SeerrException(string message, HttpStatusCode status = HttpStatusCode.BadGateway)
        : base(message)
    {
        Status = status;
    }

    /// <summary>Gets the HTTP status to return.</summary>
    public HttpStatusCode Status { get; }
}

/// <summary>
/// Talks to Seerr (Jellyseerr/Overseerr API) with the server-side API key. Requests are made as the
/// signed-in person through Seerr's X-Api-User header, so their limits and auto-approval apply.
/// </summary>
public sealed class SeerrService
{
    private static readonly TimeSpan UsersTtl = TimeSpan.FromMinutes(10);
    private static readonly TimeSpan DetailsTtl = TimeSpan.FromHours(6);
    private static readonly TimeSpan ListTtl = TimeSpan.FromMinutes(5);

    /* The latest requests from everyone, newest first (Upcoming movies and shows look through them). */
    private const string RecentRequests = "/request?take=100&skip=0&filter=all&sort=added";

    /* The Requests rows: Seerr's discover list for each, and the media type of its results (trending mixes both). */
    private static readonly Dictionary<string, (string Path, string? Type)> TitleRows = new(StringComparer.Ordinal)
    {
        ["trending"] = ("/discover/trending", null),
        ["popular-movies"] = ("/discover/movies", "movie"),
        ["popular-shows"] = ("/discover/tv", "tv"),
        ["upcoming-movies"] = ("/discover/movies/upcoming", "movie"),
        ["upcoming-shows"] = ("/discover/tv/upcoming", "tv")
    };

    private readonly IHttpClientFactory _http;
    private readonly ILogger<SeerrService> _logger;
    private readonly ConcurrentDictionary<string, (DateTime At, JsonNode? Node)> _cache = new(StringComparer.Ordinal);
    private (DateTime At, List<SeerrUserDto> Users)? _users;
    private DateTime _unreachableUntil = DateTime.MinValue;

    /// <summary>
    /// Initializes a new instance of the <see cref="SeerrService"/> class.
    /// </summary>
    /// <param name="http">HTTP client factory.</param>
    /// <param name="logger">Logger.</param>
    public SeerrService(IHttpClientFactory http, ILogger<SeerrService> logger)
    {
        _http = http;
        _logger = logger;
    }

    /// <summary>Gets the last connection check.</summary>
    public SeerrStateDto State { get; private set; } = new();

    /// <summary>Gets whether an address and key are set.</summary>
    public static bool IsConfigured
    {
        get
        {
            var c = ConfigMapper.Current;
            return !string.IsNullOrWhiteSpace(c.SeerrUrl) && !string.IsNullOrWhiteSpace(c.SeerrApiKey);
        }
    }

    /// <summary>Forgets cached Seerr data after the address or key changes.</summary>
    public void Reset()
    {
        _cache.Clear();
        _users = null;
        _unreachableUntil = DateTime.MinValue;
        State = new SeerrStateDto { State = IsConfigured ? "error" : "off", Message = IsConfigured ? "Not checked yet" : null };
    }

    private HttpClient Client()
    {
        var c = _http.CreateClient("Seedy");
        c.Timeout = TimeSpan.FromSeconds(15);
        return c;
    }

    private async Task<JsonNode?> Send(HttpMethod method, string url, string? apiKey, string? body, int? asUser, CancellationToken ct, bool failFast = true)
    {
        using var req = new HttpRequestMessage(method, url);
        req.Headers.Accept.ParseAdd("application/json");
        if (!string.IsNullOrEmpty(apiKey))
        {
            req.Headers.Add("X-Api-Key", apiKey);
        }

        if (asUser is not null)
        {
            req.Headers.Add("X-Api-User", asUser.Value.ToString(CultureInfo.InvariantCulture));
        }

        if (body is not null)
        {
            req.Content = new StringContent(body, Encoding.UTF8, "application/json");
        }

        // After a connection failure, fail fast for a little while instead of making every page wait.
        if (failFast && DateTime.UtcNow < _unreachableUntil && apiKey is not null)
        {
            throw new SeerrException("Couldn't reach Seerr. Check the address in Seedy's admin.");
        }

        HttpResponseMessage res;
        try
        {
            res = await Client().SendAsync(req, ct).ConfigureAwait(false);
        }
        catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException && !ct.IsCancellationRequested)
        {
            if (failFast)
            {
                _unreachableUntil = DateTime.UtcNow.AddSeconds(30);
            }

            throw new SeerrException("Couldn't reach Seerr. Check the address in Seedy's admin.");
        }

        using (res)
        {
            var text = await res.Content.ReadAsStringAsync(ct).ConfigureAwait(false);
            if (res.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden)
            {
                var detail = TryMessage(text);
                throw new SeerrException(detail ?? "Seerr refused the request.", HttpStatusCode.Forbidden);
            }

            if (!res.IsSuccessStatusCode)
            {
                var detail = TryMessage(text);
                _logger.LogWarning("Seerr {Method} {Url} returned {Status}: {Detail}", method, url, (int)res.StatusCode, detail ?? text);
                throw new SeerrException(detail ?? $"Seerr returned {(int)res.StatusCode}.", res.StatusCode == HttpStatusCode.NotFound ? HttpStatusCode.NotFound : HttpStatusCode.BadGateway);
            }

            if (string.IsNullOrWhiteSpace(text))
            {
                return null;
            }

            try
            {
                return JsonNode.Parse(text);
            }
            catch (JsonException)
            {
                // A login page or a web app in front of Seerr answers 200 with HTML.
                throw new SeerrException("Seerr's address didn't answer like Seerr. Check the address in Seedy's admin.");
            }
        }
    }

    private static string? TryMessage(string text)
    {
        try
        {
            return JsonNode.Parse(text)?["message"]?.GetValue<string>();
        }
        catch (Exception ex) when (ex is JsonException or InvalidOperationException or FormatException)
        {
            return null;
        }
    }

    private Task<JsonNode?> Api(HttpMethod method, string path, object? body = null, int? asUser = null, CancellationToken ct = default)
    {
        var c = ConfigMapper.Current;
        if (!IsConfigured)
        {
            throw new SeerrException("Seerr isn't connected.", HttpStatusCode.ServiceUnavailable);
        }

        var json = body is null ? null : JsonSerializer.Serialize(body);
        return Send(method, c.SeerrUrl.TrimEnd('/') + "/api/v1" + path, c.SeerrApiKey, json, asUser, ct);
    }

    /* A title's details path. Every lookup of a title goes through it, so they all share one cached answer. */
    private static string TitlePath(string mediaType, int tmdbId) =>
        (mediaType == "tv" ? "/tv/" : "/movie/") + tmdbId.ToString(CultureInfo.InvariantCulture) + "?language=en";

    private Task<JsonNode?> CachedTitle(string mediaType, int tmdbId, CancellationToken ct) => Cached(TitlePath(mediaType, tmdbId), DetailsTtl, ct);

    private async Task<JsonNode?> Cached(string path, TimeSpan ttl, CancellationToken ct)
    {
        if (_cache.TryGetValue(path, out var hit) && DateTime.UtcNow - hit.At < ttl)
        {
            return hit.Node;
        }

        var node = await Api(HttpMethod.Get, path, ct: ct).ConfigureAwait(false);
        _cache[path] = (DateTime.UtcNow, node);
        if (_cache.Count > 500)
        {
            foreach (var old in _cache.OrderBy(k => k.Value.At).Take(100).Select(k => k.Key).ToList())
            {
                _cache.TryRemove(old, out _);
            }
        }

        return node;
    }

    // ── Connection ──

    /// <summary>Checks an address and key (the saved ones when not given).</summary>
    /// <param name="url">Address to test.</param>
    /// <param name="apiKey">Key to test.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The result, also kept as <see cref="State"/> when testing the saved settings.</returns>
    public async Task<SeerrTestResultDto> TestAsync(string? url, string? apiKey, CancellationToken ct)
    {
        var c = ConfigMapper.Current;
        var saved = string.IsNullOrWhiteSpace(url) && string.IsNullOrWhiteSpace(apiKey);
        var address = (string.IsNullOrWhiteSpace(url) ? c.SeerrUrl : url!).Trim().TrimEnd('/');
        var key = string.IsNullOrWhiteSpace(apiKey) ? c.SeerrApiKey : apiKey!.Trim();
        var result = new SeerrTestResultDto();
        if (string.IsNullOrEmpty(address) || !Uri.TryCreate(address, UriKind.Absolute, out var uri) || (uri.Scheme != "http" && uri.Scheme != "https"))
        {
            result.Message = "Add Seerr's address, such as http://seerr:5055.";
        }
        else if (string.IsNullOrEmpty(key))
        {
            result.Message = "Add Seerr's API key. It's under Settings → General in Seerr.";
        }
        else
        {
            try
            {
                var status = await Send(HttpMethod.Get, address + "/api/v1/status", null, null, null, ct, failFast: false).ConfigureAwait(false);
                result.Version = Str(status?["version"]);
                var radarr = await Send(HttpMethod.Get, address + "/api/v1/settings/radarr", key, null, null, ct, failFast: false).ConfigureAwait(false);
                var sonarr = await Send(HttpMethod.Get, address + "/api/v1/settings/sonarr", key, null, null, ct, failFast: false).ConfigureAwait(false);
                if (saved)
                {
                    _unreachableUntil = DateTime.MinValue;
                }

                result.Radarr = radarr is JsonArray ra && ra.Count > 0;
                result.Sonarr = sonarr is JsonArray sa && sa.Count > 0;
                result.Ok = true;
                result.Message = (result.Radarr, result.Sonarr) switch
                {
                    (true, true) => "Connected. Seerr can see Radarr and Sonarr.",
                    (true, false) => "Connected. Seerr can see Radarr, but no Sonarr server is set up.",
                    (false, true) => "Connected. Seerr can see Sonarr, but no Radarr server is set up.",
                    _ => "Connected. Seerr has no Radarr or Sonarr server set up yet."
                };
            }
            catch (SeerrException ex)
            {
                result.Message = ex.Status == HttpStatusCode.Forbidden ? "Seerr refused the API key." : $"Couldn't reach Seerr at {address}.";
            }
        }

        if (saved)
        {
            State = new SeerrStateDto { State = result.Ok ? "connected" : (IsConfigured ? "error" : "off"), Message = result.Message, CheckedAt = DateTime.UtcNow };
        }

        return result;
    }

    /// <summary>Gets the connection state, rechecking when it's older than five minutes.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>State.</returns>
    public async Task<SeerrStateDto> StateAsync(CancellationToken ct)
    {
        if (!IsConfigured)
        {
            State = new SeerrStateDto { State = "off" };
            return State;
        }

        if (State.CheckedAt is null || DateTime.UtcNow - State.CheckedAt.Value > TimeSpan.FromMinutes(5))
        {
            await TestAsync(null, null, ct).ConfigureAwait(false);
        }

        return State;
    }

    /// <summary>The address people's browsers should use for Seerr: its "Application URL" when set, else the configured one.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Public address, or null when not connected.</returns>
    public async Task<string?> PublicUrlAsync(CancellationToken ct)
    {
        if (!IsConfigured)
        {
            return null;
        }

        try
        {
            var main = await Cached("/settings/main", DetailsTtl, ct).ConfigureAwait(false);
            var app = Str(main?["applicationUrl"]);
            if (!string.IsNullOrWhiteSpace(app) && Uri.TryCreate(app, UriKind.Absolute, out _))
            {
                return app.TrimEnd('/');
            }
        }
        catch (SeerrException)
        {
            // Fall back to the configured address.
        }

        return ConfigMapper.Current.SeerrUrl;
    }

    // ── Accounts ──

    /// <summary>Lists Seerr users.</summary>
    /// <param name="refresh">Ignore the cache.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Users.</returns>
    public async Task<List<SeerrUserDto>> UsersAsync(bool refresh, CancellationToken ct)
    {
        if (!refresh && _users is not null && DateTime.UtcNow - _users.Value.At < UsersTtl)
        {
            return _users.Value.Users;
        }

        JsonNode? node;
        try
        {
            node = await Api(HttpMethod.Get, "/user?take=1000&skip=0&sort=displayname", ct: ct).ConfigureAwait(false);
        }
        catch (SeerrException)
        {
            // Remember the failure for a minute so profile lookups don't keep waiting on Seerr.
            _users = (DateTime.UtcNow - UsersTtl + TimeSpan.FromMinutes(1), _users?.Users ?? []);
            throw;
        }

        var list = new List<SeerrUserDto>();
        foreach (var u in node?["results"]?.AsArray() ?? [])
        {
            if (u is null)
            {
                continue;
            }

            list.Add(new SeerrUserDto
            {
                Id = Int(u["id"]) ?? 0,
                DisplayName = UserName(u) ?? Str(u["email"]) ?? "Seerr user",
                Email = Str(u["email"]),
                JellyfinUserId = NormalizeId(Str(u["jellyfinUserId"])),
                Avatar = Str(u["avatar"]) is { } av && av.StartsWith("http", StringComparison.OrdinalIgnoreCase) ? av : null
            });
        }

        _users = (DateTime.UtcNow, list);
        return list;
    }

    /// <summary>Finds the Seerr account for a profile: an admin's manual link, else Seerr's Jellyfin sign-in.</summary>
    /// <param name="user">The profile.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Seerr user and how it was matched.</returns>
    public async Task<(SeerrUserDto? User, string? MatchedBy)> AccountFor(User user, CancellationToken ct)
    {
        if (!IsConfigured)
        {
            return (null, null);
        }

        var uid = user.Id.ToString("N");
        var link = ConfigMapper.SeerrProfile(ConfigMapper.Current, uid);
        List<SeerrUserDto> users;
        try
        {
            users = await UsersAsync(false, ct).ConfigureAwait(false);
        }
        catch (SeerrException)
        {
            return (null, null);
        }

        if (link.Manual && link.SeerrUserId > 0)
        {
            var manual = users.FirstOrDefault(u => u.Id == link.SeerrUserId);
            if (manual is not null)
            {
                return (manual, "manual");
            }
        }

        var matched = users.FirstOrDefault(u => u.JellyfinUserId == uid);
        return matched is null ? (null, null) : (matched, "jellyfin");
    }

    /// <summary>Gets whether a profile may request, given its Seerr account.</summary>
    /// <param name="user">The profile.</param>
    /// <param name="hasAccount">Whether it has a Seerr account.</param>
    /// <returns>True when Request buttons should work for it.</returns>
    public static bool CanRequest(User user, bool hasAccount)
    {
        var c = ConfigMapper.Current;
        if (!hasAccount || !c.ShowRequestButtons)
        {
            return false;
        }

        var uid = user.Id.ToString("N");
        var stored = (c.SeerrProfiles ?? []).FirstOrDefault(p => string.Equals(p.UserId, uid, StringComparison.OrdinalIgnoreCase));
        return c.RequestWho == "selected" ? stored?.CanRequest == true : stored?.CanRequest ?? true;
    }

    // ── Browsing ──

    /// <summary>Who asked for each title, from Seerr's request list (list endpoints don't say).</summary>
    private async Task<Dictionary<string, (string Name, int Id)>> RequestersAsync(CancellationToken ct)
    {
        var map = new Dictionary<string, (string, int)>(StringComparer.Ordinal);
        JsonNode? node;
        try
        {
            node = await Cached("/request?take=200&skip=0&filter=all&sort=added", ListTtl, ct).ConfigureAwait(false);
        }
        catch (SeerrException)
        {
            return map;
        }

        // Newest first; keep the most recent request that is still waiting or approved.
        foreach (var r in node?["results"]?.AsArray() ?? [])
        {
            var tmdb = Int(r?["media"]?["tmdbId"]);
            var type = Str(r?["type"]);
            if (tmdb is null || type is null || Int(r?["status"]) is not (1 or 2 or 5))
            {
                continue;
            }

            var key = type + ":" + tmdb.Value.ToString(CultureInfo.InvariantCulture);
            var by = r?["requestedBy"];
            map.TryAdd(key, (UserName(by) ?? "someone", Int(by?["id"]) ?? 0));
        }

        return map;
    }

    private async Task Enrich(IEnumerable<SeerrItemDto> items, int? me, CancellationToken ct)
    {
        var missing = items.Where(i => i.RequestedBy is null && i.Status is "pending" or "processing" or "partial" or "available").ToList();
        if (missing.Count == 0)
        {
            return;
        }

        var requesters = await RequestersAsync(ct).ConfigureAwait(false);
        foreach (var i in missing)
        {
            if (requesters.TryGetValue(i.MediaType + ":" + i.TmdbId.ToString(CultureInfo.InvariantCulture), out var who))
            {
                i.RequestedBy = who.Name;
                i.RequestedByMe = me is not null && who.Id == me;
            }
        }
    }


    /// <summary>Trending titles that aren't in the library yet.</summary>
    /// <param name="me">The viewer's Seerr id, to flag their own requests.</param>
    /// <param name="page">Page (1-based).</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Titles.</returns>
    public async Task<List<SeerrItemDto>> DiscoverAsync(int? me, int page, CancellationToken ct)
    {
        var results = new List<SeerrItemDto>();
        {
            var node = await Cached($"/discover/trending?page={Math.Max(1, page)}&language=en", ListTtl, ct).ConfigureAwait(false);
            foreach (var r in node?["results"]?.AsArray() ?? [])
            {
                var item = MapResult(r, me);
                // "Not in your library yet": keep what's missing, plus things someone requested that just arrived.
                if (item is null || item.Status == "blocklisted")
                {
                    continue;
                }

                if (results.All(x => x.TmdbId != item.TmdbId || x.MediaType != item.MediaType))
                {
                    results.Add(item);
                }
            }
        }

        await Enrich(results, me, ct).ConfigureAwait(false);

        // "Not in your library yet": drop what's available, except titles someone requested (they just arrived).
        results.RemoveAll(i => i.Status == "available" && i.RequestedBy is null);
        return results;
    }

    /// <summary>Searches Seerr for movies and series.</summary>
    /// <param name="query">Text.</param>
    /// <param name="me">The viewer's Seerr id.</param>
    /// <param name="page">Page.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Titles and the total count.</returns>
    public async Task<(List<SeerrItemDto> Items, int Total)> SearchAsync(string query, int? me, int page, CancellationToken ct)
    {
        var node = await Cached($"/search?query={Uri.EscapeDataString(query.Trim())}&page={Math.Max(1, page)}&language=en", ListTtl, ct).ConfigureAwait(false);
        var list = new List<SeerrItemDto>();
        foreach (var r in node?["results"]?.AsArray() ?? [])
        {
            var item = MapResult(r, me);
            if (item is not null)
            {
                list.Add(item);
            }
        }

        await Enrich(list, me, ct).ConfigureAwait(false);
        return (list, Int(node?["totalResults"]) ?? list.Count);
    }

    /// <summary>Movies that were requested but aren't out yet, soonest first.</summary>
    /// <param name="me">The viewer's Seerr id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Upcoming movies.</returns>
    public async Task<List<SeerrUpcomingDto>> UpcomingAsync(int? me, CancellationToken ct)
    {
        var today = DateTime.UtcNow.Date;
        var found = new Dictionary<int, SeerrUpcomingDto>();
        var requests = await Cached(RecentRequests, ListTtl, ct).ConfigureAwait(false);
        var ids = new List<int>();
        foreach (var r in requests?["results"]?.AsArray() ?? [])
        {
            // Available already, or a request that was declined (3) or failed (4): not coming.
            if (Str(r?["type"]) != "movie" || Int(r?["media"]?["status"]) == 5 || Int(r?["status"]) is 3 or 4)
            {
                continue;
            }

            var id = Int(r?["media"]?["tmdbId"]);
            if (id is not null && !ids.Contains(id.Value))
            {
                ids.Add(id.Value);
            }
        }

        foreach (var id in ids.Take(40))
        {
            JsonNode? movie;
            try
            {
                movie = await CachedTitle("movie", id, ct).ConfigureAwait(false);
            }
            catch (SeerrException)
            {
                continue;
            }

            var (date, kind) = ReleaseDate(movie);
            if (date is null || date.Value.Date < today)
            {
                continue;
            }

            var item = MapResult(movie, me, "movie");
            if (item is null)
            {
                continue;
            }

            found[id] = Upcoming(item, date.Value, kind);
        }

        if (ConfigMapper.Current.UpcomingSource == "requestsAndRadarr")
        {
            await AddRadarrCalendar(found, today, ct).ConfigureAwait(false);
        }

        var upcoming = found.Values.OrderBy(u => u.ReleaseDate, StringComparer.Ordinal).Take(20).ToList();
        await Enrich(upcoming, me, ct).ConfigureAwait(false);
        return upcoming;
    }

    private async Task AddRadarrCalendar(Dictionary<int, SeerrUpcomingDto> found, DateTime today, CancellationToken ct)
    {
        JsonNode? servers;
        try
        {
            servers = await Cached("/settings/radarr", DetailsTtl, ct).ConfigureAwait(false);
        }
        catch (SeerrException ex)
        {
            _logger.LogWarning("Seedy couldn't read Radarr servers from Seerr: {Message}", ex.Message);
            return;
        }

        foreach (var s in servers?.AsArray() ?? [])
        {
            if (s is null || Bool(s["is4k"]))
            {
                continue;
            }

            var scheme = Bool(s["useSsl"]) ? "https" : "http";
            var baseUrl = (Str(s["baseUrl"]) ?? string.Empty).TrimEnd('/');
            var url = $"{scheme}://{Str(s["hostname"])}:{Int(s["port"]) ?? 7878}{baseUrl}/api/v3/calendar?start={today:yyyy-MM-dd}&end={today.AddDays(150):yyyy-MM-dd}&unmonitored=false";
            JsonNode? cal;
            if (_cache.TryGetValue("radarr:" + url, out var hit) && DateTime.UtcNow - hit.At < ListTtl)
            {
                cal = hit.Node;
            }
            else
            {
                try
                {
                    // Radarr's address is as Seerr sees it; Jellyfin may not reach it. That must not trip Seerr's breaker.
                    cal = await Send(HttpMethod.Get, url, Str(s["apiKey"]), null, null, ct, failFast: false).ConfigureAwait(false);
                }
                catch (SeerrException)
                {
                    cal = null;
                }

                _cache["radarr:" + url] = (DateTime.UtcNow, cal);
            }

            foreach (var m in cal?.AsArray() ?? [])
            {
                var id = Int(m?["tmdbId"]);
                if (m is null || id is null || found.ContainsKey(id.Value) || Bool(m["hasFile"]))
                {
                    continue;
                }

                var candidates = new (string Field, string Kind)[] { ("digitalRelease", "digital"), ("physicalRelease", "physical"), ("inCinemas", "theatrical") };
                DateTime? date = null;
                string? kind = null;
                foreach (var (field, k) in candidates)
                {
                    var d = Date(m[field]);
                    if (d is not null && d.Value.Date >= today && (date is null || d < date))
                    {
                        date = d;
                        kind = k;
                    }
                }

                if (date is null)
                {
                    continue;
                }

                var poster = m["images"]?.AsArray().FirstOrDefault(i => Str(i?["coverType"]) == "poster");
                found[id.Value] = Upcoming(
                    new SeerrItemDto
                    {
                        TmdbId = id.Value,
                        MediaType = "movie",
                        Title = Str(m["title"]) ?? "Untitled",
                        Year = Int(m["year"])?.ToString(CultureInfo.InvariantCulture),
                        PosterPath = Str(poster?["remoteUrl"]) ?? Str(poster?["url"]),
                        Overview = Str(m["overview"]),
                        Status = "processing"
                    },
                    date.Value,
                    kind);
            }
        }
    }

    private static SeerrUpcomingDto Upcoming(SeerrItemDto i, DateTime date, string? kind)
    {
        var u = As<SeerrUpcomingDto>(i);
        u.ReleaseDate = date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        u.ReleaseKind = kind;
        return u;
    }

    /* A title's common fields on one of the richer DTOs. */
    private static T As<T>(SeerrItemDto i)
        where T : SeerrItemDto, new() => new()
    {
        TmdbId = i.TmdbId,
        MediaType = i.MediaType,
        Title = i.Title,
        Year = i.Year,
        Rating = i.Rating,
        PosterPath = i.PosterPath,
        BackdropPath = i.BackdropPath,
        Overview = i.Overview,
        Status = i.Status,
        RequestedBy = i.RequestedBy,
        RequestedByMe = i.RequestedByMe,
        JellyfinItemId = i.JellyfinItemId
    };

    /// <summary>The person's own requests, newest first, with titles and artwork filled in.</summary>
    /// <param name="me">Their Seerr user id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Requests and how many are still open.</returns>
    public async Task<MyRequestsDto> MyRequestsAsync(int me, CancellationToken ct)
    {
        var mine = (await RequestListAsync(me, false, "all", "added", 0, 60, ct).ConfigureAwait(false)).Results;
        return new MyRequestsDto { Results = mine, Open = mine.Count(x => x.RequestStatus is "pending" or "approved" && x.Status != "available") };
    }

    // ── Requests page ──

    /// <summary>A page of requests: the person's own, or everyone's (Seerr only lists everyone's for people with
    /// Manage or View requests, since the call is made as the person).</summary>
    /// <param name="me">The person's Seerr id.</param>
    /// <param name="all">Everyone's requests rather than the person's.</param>
    /// <param name="filter">all, pending, processing, available or failed (Seerr's request filters).</param>
    /// <param name="sort">added or modified.</param>
    /// <param name="skip">Requests to skip.</param>
    /// <param name="take">Page size.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Requests and the total.</returns>
    public async Task<RequestListDto> RequestListAsync(int me, bool all, string? filter, string? sort, int skip, int take, CancellationToken ct)
    {
        var f = filter is "pending" or "processing" or "available" or "failed" ? filter : "all";
        var s = sort == "modified" ? "modified" : "added";
        var path = string.Create(CultureInfo.InvariantCulture, $"/request?take={Math.Clamp(take, 1, 100)}&skip={Math.Max(0, skip)}&filter={f}&sort={s}");
        var node = await Api(HttpMethod.Get, all ? path : path + "&requestedBy=" + me.ToString(CultureInfo.InvariantCulture), asUser: me, ct: ct).ConfigureAwait(false);
        var list = (node?["results"]?.AsArray() ?? []).Where(r => r is not null && (all || Int(r["requestedBy"]?["id"]) is not { } by || by == me)).ToList();
        return new RequestListDto
        {
            Results = await MapRequestsAsync(list, me, ct).ConfigureAwait(false),
            Total = Int(node?["pageInfo"]?["results"]) ?? list.Count
        };
    }

    /// <summary>The Requests page's tabs and counts for the person.</summary>
    /// <param name="me">The person's Seerr id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Summary.</returns>
    public async Task<RequestSummaryDto> RequestSummaryAsync(int me, CancellationToken ct)
    {
        var perms = await PermissionsAsync(me, ct).ConfigureAwait(false);
        var summary = new RequestSummaryDto
        {
            CanViewAll = CanViewAllRequests(perms),
            CanManage = Has(perms, PermManageRequests)
        };

        // "unavailable": waiting for approval, or approved and not in the library yet.
        var open = await Api(HttpMethod.Get, $"/request?take=1&skip=0&filter=unavailable&requestedBy={me}", asUser: me, ct: ct).ConfigureAwait(false);
        summary.Open = Int(open?["pageInfo"]?["results"]) ?? 0;
        if (summary.CanViewAll)
        {
            // Seerr counts everyone's requests whoever asks, so only people who may see them get the number.
            var count = await Api(HttpMethod.Get, "/request/count", ct: ct).ConfigureAwait(false);
            summary.Pending = Int(count?["pending"]) ?? 0;
        }

        return summary;
    }

    /// <summary>Approves, declines or retries a request as the person (Seerr checks they have Manage requests).</summary>
    /// <param name="requestId">Seerr request id.</param>
    /// <param name="action">approve, decline or retry.</param>
    /// <param name="me">The person's Seerr id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The request as it is now.</returns>
    public async Task<RequestDto?> UpdateRequestAsync(int requestId, string action, int me, CancellationToken ct)
    {
        var node = await Api(HttpMethod.Post, string.Create(CultureInfo.InvariantCulture, $"/request/{requestId}/{action}"), asUser: me, ct: ct).ConfigureAwait(false);
        ForgetRequests(Str(node?["type"]), Int(node?["media"]?["tmdbId"]));
        return node is null ? null : (await MapRequestsAsync([node], me, ct).ConfigureAwait(false)).FirstOrDefault();
    }

    /// <summary>Removes a request as the person: Seerr lets people withdraw their own while it waits, and
    /// request managers remove any.</summary>
    /// <param name="requestId">Seerr request id.</param>
    /// <param name="me">The person's Seerr id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>A task.</returns>
    public async Task DeleteRequestAsync(int requestId, int me, CancellationToken ct)
    {
        await Api(HttpMethod.Delete, "/request/" + requestId.ToString(CultureInfo.InvariantCulture), asUser: me, ct: ct).ConfigureAwait(false);
        ForgetRequests(null, null);
    }

    /// <summary>What deleting a title from the server means in Seerr: whether Seerr tracks it (so it can be cleared and
    /// requested again) and whether Sonarr or Radarr still monitors it (and may download it again).</summary>
    /// <param name="mediaType">movie or tv.</param>
    /// <param name="tmdbId">TMDB id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Null when Seerr doesn't track the title.</returns>
    public async Task<SeerrMediaStateDto?> MediaStateAsync(string mediaType, int tmdbId, CancellationToken ct)
    {
        var node = await Api(HttpMethod.Get, TitlePath(mediaType, tmdbId), ct: ct).ConfigureAwait(false);
        var info = node?["mediaInfo"];
        if (Int(info?["id"]) is not { } mediaId)
        {
            return null;
        }

        var state = new SeerrMediaStateDto { MediaType = mediaType, TmdbId = tmdbId, MediaId = mediaId };
        var kind = mediaType == "tv" ? "sonarr" : "radarr";
        foreach (var (service, external) in new[] { ("serviceId", "externalServiceId"), ("serviceId4k", "externalServiceId4k") })
        {
            if (Int(info?[service]) is not { } serverId || Int(info?[external]) is not { } arrId)
            {
                continue;
            }

            state.Arr = mediaType == "tv" ? "Sonarr" : "Radarr";
            var monitored = await ArrMonitoredAsync(kind, serverId, arrId, ct).ConfigureAwait(false);
            state.Monitored = monitored == true || state.Monitored == true ? true : monitored ?? state.Monitored;
        }

        return state;
    }

    /* Asks Sonarr or Radarr (with the address and key Seerr keeps for it) whether it still monitors a title.
       Null when it can't tell. */
    private async Task<bool?> ArrMonitoredAsync(string kind, int serverId, int arrId, CancellationToken ct)
    {
        try
        {
            var servers = await Cached("/settings/" + kind, ListTtl, ct).ConfigureAwait(false);
            var server = servers?.AsArray().FirstOrDefault(x => Int(x?["id"]) == serverId);
            var host = Str(server?["hostname"]);
            var key = Str(server?["apiKey"]);
            if (server is null || host is null || key is null)
            {
                return null;
            }

            var scheme = server["useSsl"]?.GetValue<bool>() == true ? "https" : "http";
            var baseUrl = (Str(server["baseUrl"]) ?? string.Empty).TrimEnd('/');
            var path = (kind == "sonarr" ? "/api/v3/series/" : "/api/v3/movie/") + arrId.ToString(CultureInfo.InvariantCulture);
            var url = string.Create(CultureInfo.InvariantCulture, $"{scheme}://{host}:{Int(server["port"]) ?? (kind == "sonarr" ? 8989 : 7878)}{baseUrl}{path}");
            var title = await Send(HttpMethod.Get, url, key, null, null, ct, failFast: false).ConfigureAwait(false);
            return title?["monitored"]?.GetValue<bool>();
        }
        catch (SeerrException ex) when (ex.Status == HttpStatusCode.NotFound)
        {
            // Removed from Sonarr or Radarr since.
            return false;
        }
        catch (Exception ex) when (ex is SeerrException or InvalidOperationException or FormatException)
        {
            _logger.LogDebug(ex, "Couldn't ask {Kind} {Server} about {Id}", kind, serverId, arrId);
            return null;
        }
    }

    /// <summary>Clears a title in Seerr as the person (Seerr lets request managers do it): its requests and status
    /// go, so it can be requested again.</summary>
    /// <param name="mediaType">movie or tv.</param>
    /// <param name="tmdbId">TMDB id.</param>
    /// <param name="me">The person's Seerr id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>A task.</returns>
    public async Task ClearMediaAsync(string mediaType, int tmdbId, int me, CancellationToken ct)
    {
        var state = await MediaStateAsync(mediaType, tmdbId, ct).ConfigureAwait(false);
        if (state is not null)
        {
            await Api(HttpMethod.Delete, "/media/" + state.MediaId.ToString(CultureInfo.InvariantCulture), asUser: me, ct: ct).ConfigureAwait(false);
        }

        ForgetRequests(mediaType, tmdbId);
    }

    /// <summary>Whether a Seerr user may manage requests (and so clear titles).</summary>
    /// <param name="me">Seerr user id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>True for request managers and admins.</returns>
    public async Task<bool> CanManageRequestsAsync(int me, CancellationToken ct) => Has(await PermissionsAsync(me, ct).ConfigureAwait(false), PermManageRequests);

    /* After a request is made or changes: lists, searches and (when known) the title's details have a new status. */
    private void ForgetRequests(string? mediaType, int? tmdbId)
    {
        var title = mediaType is null || tmdbId is null ? null : TitlePath(mediaType, tmdbId.Value);
        foreach (var k in _cache.Keys.Where(k => k == title || k.StartsWith("/discover", StringComparison.Ordinal)
            || k.StartsWith("/search", StringComparison.Ordinal) || k.StartsWith("/request", StringComparison.Ordinal)).ToList())
        {
            _cache.TryRemove(k, out _);
        }
    }

    /// <summary>A Requests row: trending, popular movies or shows, upcoming movies or shows. The first page of the
    /// upcoming rows starts with what people requested: movies not out yet, and new seasons of requested series.</summary>
    /// <param name="kind">trending, popular-movies, popular-shows, upcoming-movies or upcoming-shows.</param>
    /// <param name="page">Page (1-based).</param>
    /// <param name="me">The viewer's Seerr id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Titles and Seerr's page count.</returns>
    public async Task<SeerrTitlesDto> TitlesAsync(string kind, int page, int? me, CancellationToken ct)
    {
        if (!TitleRows.TryGetValue(kind, out var row))
        {
            throw new SeerrException("There's no such row.", HttpStatusCode.NotFound);
        }

        var upcoming = kind.StartsWith("upcoming", StringComparison.Ordinal);
        var result = new SeerrTitlesDto();
        if (page <= 1 && kind == "upcoming-movies")
        {
            foreach (var u in await UpcomingAsync(me, ct).ConfigureAwait(false))
            {
                result.Results.Add(Title(u, u.ReleaseDate));
            }
        }
        else if (page <= 1 && kind == "upcoming-shows")
        {
            result.Results.AddRange(await UpcomingSeasonsAsync(me, ct).ConfigureAwait(false));
        }

        var node = await Cached(string.Create(CultureInfo.InvariantCulture, $"{row.Path}?page={Math.Max(1, page)}&language=en"), ListTtl, ct).ConfigureAwait(false);
        result.Pages = Int(node?["totalPages"]) ?? 1;
        foreach (var r in node?["results"]?.AsArray() ?? [])
        {
            var item = MapResult(r, me, row.Type);
            if (item is null || item.Status == "blocklisted" || result.Results.Any(x => x.Season is null && x.TmdbId == item.TmdbId && x.MediaType == item.MediaType))
            {
                continue;
            }

            var date = upcoming ? NullIfEmpty(Str(r?[item.MediaType == "tv" ? "firstAirDate" : "releaseDate"])) : null;
            result.Results.Add(Title(item, date));
        }

        await Enrich(result.Results.Where(x => x.Season is null), me, ct).ConfigureAwait(false);
        return result;
    }

    private static SeerrTitleDto Title(SeerrItemDto i, string? releaseDate)
    {
        var title = As<SeerrTitleDto>(i);
        title.ReleaseDate = releaseDate;
        return title;
    }

    /// <summary>The next season of each requested series that has been announced but hasn't started airing; the
    /// viewer's own requests first, then soonest first.</summary>
    private async Task<List<SeerrTitleDto>> UpcomingSeasonsAsync(int? me, CancellationToken ct)
    {
        var requests = await Cached(RecentRequests, ListTtl, ct).ConfigureAwait(false);
        var series = new Dictionary<int, (string Name, bool Mine)>();
        foreach (var r in requests?["results"]?.AsArray() ?? [])
        {
            // Declined (3) and failed (4) requests aren't coming.
            if (Str(r?["type"]) != "tv" || Int(r?["status"]) is 3 or 4 || Int(r?["media"]?["tmdbId"]) is not { } id)
            {
                continue;
            }

            var by = r?["requestedBy"];
            var mine = me is not null && Int(by?["id"]) == me;
            if (!series.TryGetValue(id, out var known) || (mine && !known.Mine))
            {
                series[id] = (UserName(by) ?? "someone", mine);
            }
        }

        var today = DateTime.UtcNow.Date;
        var found = new List<(SeerrTitleDto Title, bool Mine, DateTime? Date)>();
        foreach (var (id, who) in series.Take(30))
        {
            JsonNode? tv;
            try
            {
                tv = await CachedTitle("tv", id, ct).ConfigureAwait(false);
            }
            catch (SeerrException)
            {
                continue;
            }

            if (tv is null || Str(tv["status"]) is "Ended" or "Canceled" || MapResult(tv, me, "tv") is not { } basic)
            {
                continue;
            }

            var next = (tv["seasons"]?.AsArray() ?? [])
                .Where(s => (Int(s?["seasonNumber"]) ?? 0) > 0 && (Date(s?["airDate"]) is not { } d || d.Date > today))
                .OrderBy(s => Int(s?["seasonNumber"]))
                .FirstOrDefault();
            if (next is null)
            {
                continue;
            }

            var airs = Date(next["airDate"]);
            var title = Title(basic, airs?.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture));
            title.Season = Int(next["seasonNumber"]);
            title.PosterPath = Str(next["posterPath"]) ?? basic.PosterPath;
            title.RequestedBy = who.Name;
            title.RequestedByMe = who.Mine;
            found.Add((title, who.Mine, airs));
        }

        return found.OrderByDescending(f => f.Mine).ThenBy(f => f.Date ?? DateTime.MaxValue).Select(f => f.Title).ToList();
    }

    /* Requests as the page shows them, with each title's name and art (Seerr's details, cached) filled in. */
    private async Task<List<RequestDto>> MapRequestsAsync(IReadOnlyList<JsonNode?> requests, int me, CancellationToken ct)
    {
        using var gate = new SemaphoreSlim(6);
        var tasks = requests.Select(async r =>
        {
            await gate.WaitAsync(ct).ConfigureAwait(false);
            try
            {
                return await MapRequestAsync(r, me, ct).ConfigureAwait(false);
            }
            finally
            {
                gate.Release();
            }
        }).ToList();
        return (await Task.WhenAll(tasks).ConfigureAwait(false)).OfType<RequestDto>().ToList();
    }

    private async Task<RequestDto?> MapRequestAsync(JsonNode? r, int me, CancellationToken ct)
    {
        var type = Str(r?["type"]) == "tv" ? "tv" : "movie";
        var media = r?["media"];
        if (r is null || Int(media?["tmdbId"]) is not { } tmdb)
        {
            return null;
        }

        JsonNode? details = null;
        try
        {
            details = await CachedTitle(type, tmdb, ct).ConfigureAwait(false);
        }
        catch (SeerrException)
        {
            // Keep the request even when Seerr can't describe the title.
        }

        var is4k = Bool(r["is4k"]);
        var basic = MapResult(details, me, type) ?? new SeerrItemDto { TmdbId = tmdb, MediaType = type, Title = "Unknown title" };
        var mediaStatus = MediaStatus(media, is4k);
        var requestStatus = Int(r["status"]) switch { 2 => "approved", 3 => "declined", 4 => "failed", 5 => "completed", _ => "pending" };
        var by = r["requestedBy"];
        var request = As<RequestDto>(basic);
        request.Status = mediaStatus ?? basic.Status;
        request.RequestedBy = UserName(by) ?? "someone";
        request.RequestedByMe = Int(by?["id"]) == me;
        request.JellyfinItemId = NormalizeId(Str(media?[is4k ? "jellyfinMediaId4k" : "jellyfinMediaId"])) ?? NormalizeId(Str(media?["jellyfinMediaId"])) ?? basic.JellyfinItemId;
        request.RequestId = Int(r["id"]) ?? 0;
        request.RequestedAt = Date(r["createdAt"]) ?? DateTime.UtcNow;
        request.UpdatedAt = Date(r["updatedAt"]);
        request.RequestStatus = requestStatus;
        request.Is4k = is4k;
        request.Seasons = type == "tv" ? (r["seasons"]?.AsArray() ?? []).Select(s => Int(s?["seasonNumber"]) ?? 0).Where(n => n > 0).ToList() : null;
        request.AvailableAt = mediaStatus is "available" or "partial" ? Date(media?["mediaAddedAt"]) ?? Date(media?["updatedAt"]) : null;
        request.Download = Download(media?[is4k ? "downloadStatus4k" : "downloadStatus"]);

        // Approved and still to come: which requested seasons haven't aired, or when the movie comes out.
        if (requestStatus == "approved" && request.Status is not "available")
        {
            var today = DateTime.UtcNow.Date;
            if (type == "tv")
            {
                var unaired = (details?["seasons"]?.AsArray() ?? [])
                    .Where(s => Int(s?["seasonNumber"]) is { } n && request.Seasons!.Contains(n) && (Date(s?["airDate"]) is not { } d || d.Date > today))
                    .Select(s => Int(s?["seasonNumber"])!.Value)
                    .ToList();
                request.Unaired = unaired.Count > 0 ? unaired : null;
            }
            else if (ReleaseDate(details).Date is { } release && release.Date > today)
            {
                request.ReleaseDate = release.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
            }
        }

        return request;
    }

    /* Sonarr/Radarr's queue for a title, as Seerr tracks it: overall progress, and the season when it's just one. */
    private static RequestDownloadDto? Download(JsonNode? status)
    {
        var items = (status as JsonArray ?? []).Where(d => d is not null).ToList();
        if (items.Count == 0)
        {
            return null;
        }

        double size = 0, left = 0;
        foreach (var d in items)
        {
            size += Double(d!["size"]) ?? 0;
            left += Double(d["sizeLeft"]) ?? 0;
        }

        var seasons = items.Select(d => Int(d!["episode"]?["seasonNumber"])).Distinct().ToList();
        return new RequestDownloadDto
        {
            Fraction = size > 0 ? Math.Clamp((size - left) / size, 0, 1) : 0,
            Season = seasons.Count == 1 ? seasons[0] : null
        };
    }

    /* A Seerr user's name as people know it (never their email). */
    private static string? UserName(JsonNode? user) => Str(user?["displayName"]) ?? Str(user?["jellyfinUsername"]) ?? Str(user?["username"]);

    /// <summary>
    /// The YouTube videos TMDB lists for a title (Seerr's relatedVideos), best first: trailers, then teasers, then
    /// clips (one named "Trailer" before the rest). Featurettes, behind-the-scenes and opening credits are left out.
    /// </summary>
    /// <param name="mediaType">movie or tv.</param>
    /// <param name="tmdbId">TMDB id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Videos in order of preference; empty when there's nothing worth showing.</returns>
    public async Task<List<TrailerVideo>> TrailerVideosAsync(string mediaType, int tmdbId, CancellationToken ct)
    {
        var node = await CachedTitle(mediaType, tmdbId, ct).ConfigureAwait(false);
        var found = new List<(TrailerVideo Video, int Rank, int Order)>();
        var order = 0;
        foreach (var v in node?["relatedVideos"]?.AsArray() ?? [])
        {
            if (v is null || !string.Equals(Str(v["site"]), "YouTube", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            var key = Str(v["key"]);
            var name = Str(v["name"]) ?? string.Empty;
            var rank = TrailerRank(Str(v["type"]) ?? string.Empty, name);
            if (string.IsNullOrWhiteSpace(key) || rank < 0)
            {
                continue;
            }

            found.Add((new TrailerVideo(key, name, "tmdb"), rank, order++));
        }

        return found.OrderBy(f => f.Rank).ThenBy(f => f.Order).Select(f => f.Video).ToList();
    }

    private static int TrailerRank(string type, string name)
    {
        var calledTrailer = name.Contains("trailer", StringComparison.OrdinalIgnoreCase);
        return type switch
        {
            "Trailer" => name.Contains("official", StringComparison.OrdinalIgnoreCase) ? 0 : 1,
            "Teaser" => 2,
            "Clip" => calledTrailer ? 2 : 3,
            _ => -1
        };
    }

    /// <summary>Full details for a title.</summary>
    /// <param name="mediaType">movie or tv.</param>
    /// <param name="tmdbId">TMDB id.</param>
    /// <param name="me">The viewer's Seerr id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Details.</returns>
    public async Task<SeerrDetailsDto> DetailsAsync(string mediaType, int tmdbId, int? me, CancellationToken ct)
    {
        var tv = mediaType == "tv";
        var node = await Api(HttpMethod.Get, TitlePath(mediaType, tmdbId), ct: ct).ConfigureAwait(false)
            ?? throw new SeerrException("Seerr doesn't know this title.", HttpStatusCode.NotFound);
        var basic = MapResult(node, me, mediaType) ?? new SeerrItemDto { TmdbId = tmdbId, MediaType = mediaType };
        var d = As<SeerrDetailsDto>(basic);
        d.Tagline = NullIfEmpty(Str(node["tagline"]));
        d.Genres = (node["genres"]?.AsArray() ?? []).Select(g => Str(g?["name"])).Where(g => g is not null).Select(g => g!).ToList();
        d.Runtime = tv ? Int(node["episodeRunTime"]?.AsArray().FirstOrDefault()) : Int(node["runtime"]);
        d.ReleaseDate = Str(node[tv ? "firstAirDate" : "releaseDate"]);
        d.Status4k = MediaStatus(node["mediaInfo"], true);
        d.Certification = tv ? TvRating(node) : MovieCertification(node);
        foreach (var c in (node["credits"]?["cast"]?.AsArray() ?? []).Take(16))
        {
            d.Cast.Add(new SeerrCastDto { Id = Int(c?["id"]) ?? 0, Name = Str(c?["name"]) ?? string.Empty, Character = NullIfEmpty(Str(c?["character"])), ProfilePath = Str(c?["profilePath"]) });
        }

        if (tv)
        {
            foreach (var c in node["createdBy"]?.AsArray() ?? [])
            {
                d.Crew.Add(new SeerrCrewDto { Name = Str(c?["name"]) ?? string.Empty, Job = "Creator" });
            }

            d.Networks = (node["networks"]?.AsArray() ?? []).Select(n => Str(n?["name"])).Where(n => n is not null).Select(n => n!).ToList();
            d.NumberOfSeasons = Int(node["numberOfSeasons"]);
            var seasonStatus = new Dictionary<int, (string Std, string FourK)>();
            foreach (var s in node["mediaInfo"]?["seasons"]?.AsArray() ?? [])
            {
                var n = Int(s?["seasonNumber"]);
                if (n is not null)
                {
                    seasonStatus[n.Value] = (StatusName(Int(s?["status"])), StatusName(Int(s?["status4k"])));
                }
            }

            // Who asked for each season: the newest request still waiting (1) or approved (2) that names it.
            var asked = new Dictionary<(int Season, bool FourK), (string Name, int Id, bool Approved)>();
            foreach (var r in node["mediaInfo"]?["requests"]?.AsArray() ?? [])
            {
                var rs = Int(r?["status"]);
                if (rs is not (1 or 2))
                {
                    continue;
                }

                var by = r?["requestedBy"];
                var who = (UserName(by) ?? "someone", Int(by?["id"]) ?? 0, rs == 2);
                foreach (var s in r?["seasons"]?.AsArray() ?? [])
                {
                    if (Int(s?["seasonNumber"]) is { } n)
                    {
                        asked.TryAdd((n, Bool(r?["is4k"])), who);
                    }
                }
            }

            // While a season is airing, Seerr knows the last episode out and the next one due.
            var last = node["lastEpisodeToAir"];
            var next = node["nextEpisodeToAir"];
            var airing = Int(next?["seasonNumber"]) is { } ns && Int(last?["seasonNumber"]) == ns ? ns : (int?)null;

            string Status(int n, bool fourK, string known)
            {
                if (known != "none" || !asked.TryGetValue((n, fourK), out var a))
                {
                    return known;
                }

                return a.Approved ? "processing" : "pending";
            }

            d.Seasons = (node["seasons"]?.AsArray() ?? [])
                .Where(s => (Int(s?["seasonNumber"]) ?? 0) > 0)
                .Select(s =>
                {
                    var n = Int(s?["seasonNumber"]) ?? 0;
                    var known = seasonStatus.TryGetValue(n, out var st) ? st : ("none", "none");
                    var season = new SeerrSeasonDto
                    {
                        SeasonNumber = n,
                        Name = Str(s?["name"]) ?? "Season",
                        EpisodeCount = Int(s?["episodeCount"]) ?? 0,
                        AirDate = NullIfEmpty(Str(s?["airDate"])),
                        PosterPath = Str(s?["posterPath"]),
                        Status = Status(n, false, known.Item1),
                        Status4k = Status(n, true, known.Item2),
                        AiredEpisodes = airing == n ? Int(last?["episodeNumber"]) : null
                    };
                    if (asked.TryGetValue((n, false), out var a))
                    {
                        season.RequestedBy = a.Name;
                        season.RequestedByMe = me is not null && a.Id == me;
                    }

                    if (asked.TryGetValue((n, true), out var a4))
                    {
                        season.RequestedBy4k = a4.Name;
                        season.RequestedByMe4k = me is not null && a4.Id == me;
                    }

                    return season;
                })
                .ToList();
        }
        else
        {
            var jobs = new[] { "Director", "Screenplay", "Writer", "Novel", "Story", "Original Music Composer" };
            foreach (var c in node["credits"]?["crew"]?.AsArray() ?? [])
            {
                var job = Str(c?["job"]);
                if (job is not null && jobs.Contains(job) && d.Crew.Count < 8)
                {
                    d.Crew.Add(new SeerrCrewDto { Name = Str(c?["name"]) ?? string.Empty, Job = job });
                }
            }

            d.Networks = (node["productionCompanies"]?.AsArray() ?? []).Select(n => Str(n?["name"])).Where(n => n is not null).Select(n => n!).Take(3).ToList();
        }

        await Enrich([d], me, ct).ConfigureAwait(false);
        var trailer = (node["relatedVideos"]?.AsArray() ?? []).FirstOrDefault(v => Str(v?["type"]) == "Trailer" && Str(v?["site"]) == "YouTube");
        d.TrailerUrl = Str(trailer?["url"]) ?? (Str(trailer?["key"]) is { } key ? "https://www.youtube.com/watch?v=" + key : null);
        return d;
    }

    /// <summary>Requests a title as the signed-in person.</summary>
    /// <param name="body">What to request.</param>
    /// <param name="asUser">Their Seerr user id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The new status for the card.</returns>
    public async Task<string> RequestAsync(RequestBody body, int asUser, CancellationToken ct)
    {
        var tv = body.MediaType == "tv";
        var payload = new Dictionary<string, object> { ["mediaType"] = body.MediaType, ["mediaId"] = body.TmdbId, ["is4k"] = body.Is4k };
        if (tv)
        {
            payload["seasons"] = body.Seasons is { Count: > 0 } s ? s : "all";
        }

        if (body.ServerId is { } server)
        {
            payload["serverId"] = server;
        }

        if (body.ProfileId is { } profile)
        {
            payload["profileId"] = profile;
        }

        if (!string.IsNullOrWhiteSpace(body.RootFolder))
        {
            payload["rootFolder"] = body.RootFolder;
        }

        if (body.UserId is { } forUser)
        {
            payload["userId"] = forUser;
        }

        var node = await Api(HttpMethod.Post, "/request", payload, asUser, ct).ConfigureAwait(false);
        ForgetRequests(body.MediaType, body.TmdbId);

        // Request status 2 = approved (auto-approval); 1 = waiting for an admin.
        return Int(node?["status"]) == 2 ? "processing" : "pending";
    }

    // Seerr's permission bits (server/lib/permissions.ts). ADMIN has every permission.
    private const int PermAdmin = 2;
    private const int PermManageUsers = 8;
    private const int PermManageRequests = 16;
    private const int PermAutoApprove = 128;
    private const int PermAutoApproveMovie = 256;
    private const int PermAutoApproveTv = 512;
    private const int PermRequest4k = 1024;
    private const int PermRequest4kMovie = 2048;
    private const int PermRequest4kTv = 4096;
    private const int PermRequestAdvanced = 8192;
    private const int PermRequestView = 16384;
    private const int PermAutoApprove4k = 32768;
    private const int PermAutoApprove4kMovie = 65536;
    private const int PermAutoApprove4kTv = 131072;

    private static bool Has(int perms, params int[] any) => (perms & PermAdmin) != 0 || any.Any(p => (perms & p) != 0);

    /// <summary>Gets whether Seerr lets these permissions pick the server, quality profile and folder (Seerr's Advanced).</summary>
    /// <param name="perms">Seerr permission bits.</param>
    /// <returns>True when allowed.</returns>
    public static bool CanPickServer(int perms) => Has(perms, PermRequestAdvanced, PermManageRequests);

    /// <summary>Gets whether Seerr lets these permissions make a request on someone else's behalf: Seerr wants both
    /// Manage users and Manage requests (or Admin) for that.</summary>
    /// <param name="perms">Seerr permission bits.</param>
    /// <returns>True when allowed.</returns>
    public static bool CanRequestFor(int perms) => Has(perms, PermManageUsers) && Has(perms, PermManageRequests);

    /// <summary>Gets whether Seerr lets these permissions see everyone's requests (Manage or View requests).</summary>
    /// <param name="perms">Seerr permission bits.</param>
    /// <returns>True when allowed.</returns>
    public static bool CanViewAllRequests(int perms) => Has(perms, PermManageRequests, PermRequestView);

    /// <summary>A Seerr user's permission bits, read fresh so a change in Seerr applies at once.</summary>
    /// <param name="me">Seerr user id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Permission bits (0 when unknown).</returns>
    public async Task<int> PermissionsAsync(int me, CancellationToken ct)
    {
        var user = await Api(HttpMethod.Get, "/user/" + me.ToString(CultureInfo.InvariantCulture), ct: ct).ConfigureAwait(false);
        return Int(user?["permissions"]) ?? 0;
    }

    /// <summary>What the request dialog needs: the person's quota and approval, 4K, and (for admins) server choices.</summary>
    /// <param name="mediaType">movie or tv.</param>
    /// <param name="me">The person's Seerr id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Options.</returns>
    public async Task<RequestOptionsDto> RequestOptionsAsync(string mediaType, int me, CancellationToken ct)
    {
        var tv = mediaType == "tv";
        var id = me.ToString(CultureInfo.InvariantCulture);
        var perms = await PermissionsAsync(me, ct).ConfigureAwait(false);
        var o = new RequestOptionsDto
        {
            AutoApprove = Has(perms, PermManageRequests, PermAutoApprove, tv ? PermAutoApproveTv : PermAutoApproveMovie),
            AutoApprove4k = Has(perms, PermManageRequests, PermAutoApprove4k, tv ? PermAutoApprove4kTv : PermAutoApprove4kMovie)
        };

        try
        {
            var quota = (await Api(HttpMethod.Get, "/user/" + id + "/quota", ct: ct).ConfigureAwait(false))?[tv ? "tv" : "movie"];
            if (Int(quota?["limit"]) is { } limit and > 0)
            {
                var used = Int(quota?["used"]) ?? 0;
                o.Quota = new RequestQuotaDto { Limit = limit, Days = Int(quota?["days"]) ?? 7, Used = used, Remaining = Int(quota?["remaining"]) ?? Math.Max(0, limit - used) };
            }
        }
        catch (SeerrException ex)
        {
            _logger.LogDebug("Seerr quota for user {Id}: {Message}", me, ex.Message);
        }

        JsonNode? servers;
        try
        {
            servers = await Cached(tv ? "/service/sonarr" : "/service/radarr", ListTtl, ct).ConfigureAwait(false);
        }
        catch (SeerrException)
        {
            return o;
        }

        var list = servers?.AsArray() ?? [];
        o.Can4k = ConfigMapper.Current.Allow4k
            && list.Any(s => Bool(s?["is4k"]))
            && Has(perms, PermRequest4k, tv ? PermRequest4kTv : PermRequest4kMovie);

        if (!CanPickServer(perms))
        {
            return o;
        }

        var adv = new RequestAdvancedDto { Me = me };
        foreach (var s in list)
        {
            var sid = Int(s?["id"]);
            if (sid is null)
            {
                continue;
            }

            var server = new RequestServerDto
            {
                Id = sid.Value,
                Name = Str(s?["name"]) ?? (tv ? "Sonarr" : "Radarr"),
                Is4k = Bool(s?["is4k"]),
                IsDefault = Bool(s?["isDefault"]),
                ProfileId = Int(s?["activeProfileId"]),
                RootFolder = Str(s?["activeDirectory"])
            };
            try
            {
                var detail = await Cached((tv ? "/service/sonarr/" : "/service/radarr/") + sid.Value.ToString(CultureInfo.InvariantCulture), ListTtl, ct).ConfigureAwait(false);
                foreach (var p in detail?["profiles"]?.AsArray() ?? [])
                {
                    if (Int(p?["id"]) is { } pid)
                    {
                        server.Profiles.Add(new RequestProfileDto { Id = pid, Name = Str(p?["name"]) ?? pid.ToString(CultureInfo.InvariantCulture) });
                    }
                }

                foreach (var f in detail?["rootFolders"]?.AsArray() ?? [])
                {
                    if (Str(f?["path"]) is { } path)
                    {
                        server.RootFolders.Add(new RequestFolderDto { Path = path, FreeSpace = f?["freeSpace"] is JsonValue v && v.TryGetValue<long>(out var free) ? free : null });
                    }
                }
            }
            catch (SeerrException ex)
            {
                // Sonarr/Radarr didn't answer: the server can still be picked with its defaults.
                _logger.LogDebug("Seerr service {Id}: {Message}", sid, ex.Message);
            }

            adv.Servers.Add(server);
        }

        if (CanRequestFor(perms))
        {
            try
            {
                adv.Users = await UsersAsync(false, ct).ConfigureAwait(false);
            }
            catch (SeerrException)
            {
                // Without the list, the request goes out as the person.
            }
        }

        o.Advanced = adv.Servers.Count > 0 || adv.Users.Count > 1 ? adv : null;
        return o;
    }

    // ── Mapping ──

    private SeerrItemDto? MapResult(JsonNode? r, int? me, string? forcedType = null)
    {
        if (r is null)
        {
            return null;
        }

        var type = forcedType ?? Str(r["mediaType"]);
        if (type is not ("movie" or "tv"))
        {
            return null;
        }

        var id = Int(r["id"]);
        if (id is null)
        {
            return null;
        }

        var tv = type == "tv";
        var date = Str(r[tv ? "firstAirDate" : "releaseDate"]);
        var info = r["mediaInfo"];
        var requests = info?["requests"]?.AsArray();
        var firstRequest = requests?.FirstOrDefault(q => Int(q?["status"]) is 1 or 2) ?? requests?.FirstOrDefault();
        var requester = firstRequest?["requestedBy"];
        var vote = Double(r["voteAverage"]);
        return new SeerrItemDto
        {
            TmdbId = id.Value,
            MediaType = type,
            Title = Str(r[tv ? "name" : "title"]) ?? Str(r["originalTitle"]) ?? "Untitled",
            Year = date is { Length: >= 4 } ? date[..4] : null,
            Rating = vote is > 0 ? Math.Round(vote.Value, 1) : null,
            PosterPath = Str(r["posterPath"]),
            BackdropPath = Str(r["backdropPath"]),
            Overview = NullIfEmpty(Str(r["overview"])),
            Status = MediaStatus(info, false) ?? "none",
            RequestedBy = UserName(requester),
            RequestedByMe = me is not null && Int(requester?["id"]) == me,
            JellyfinItemId = NormalizeId(Str(info?["jellyfinMediaId"]))
        };
    }

    private static string? MediaStatus(JsonNode? info, bool fourK)
    {
        if (info is null)
        {
            return fourK ? null : "none";
        }

        var status = Int(info[fourK ? "status4k" : "status"]);
        var name = StatusName(status);
        if (name == "none" && !fourK)
        {
            var requests = info["requests"]?.AsArray();
            if (requests?.Any(q => Int(q?["status"]) is 1 or 2) == true)
            {
                return "pending";
            }

            if (requests?.Any(q => Int(q?["status"]) == 3) == true)
            {
                return "declined";
            }
        }

        return name;
    }

    private static string StatusName(int? status) => status switch
    {
        2 => "pending",
        3 => "processing",
        4 => "partial",
        5 => "available",
        6 => "blocklisted",
        _ => "none"
    };

    /// <summary>
    /// When a requested movie can be watched at home: the earliest upcoming digital or physical release;
    /// if every home release is past, the latest one (the caller then treats the movie as out). Theatrical
    /// dates are only used when no home release is known.
    /// </summary>
    private static (DateTime? Date, string? Kind) ReleaseDate(JsonNode? movie)
    {
        var today = DateTime.UtcNow.Date;
        var home = new List<(DateTime Date, string Kind)>();
        var cinema = new List<(DateTime Date, string Kind)>();
        foreach (var country in movie?["releases"]?["results"]?.AsArray() ?? [])
        {
            foreach (var rd in country?["release_dates"]?.AsArray() ?? [])
            {
                var kind = Int(rd?["type"]) switch { 4 => "digital", 5 => "physical", 3 => "theatrical", _ => null };
                if (kind is null || Date(rd?["release_date"]) is not { } d)
                {
                    continue;
                }

                (kind == "theatrical" ? cinema : home).Add((d, kind));
            }
        }

        if (cinema.Count == 0 && Date(movie?["releaseDate"]) is { } primary)
        {
            cinema.Add((primary, "theatrical"));
        }

        foreach (var list in new[] { home, cinema })
        {
            if (list.Count == 0)
            {
                continue;
            }

            var upcoming = list.Where(x => x.Date.Date >= today).OrderBy(x => x.Date).ToList();
            var pick = upcoming.Count > 0 ? upcoming[0] : list.OrderByDescending(x => x.Date).First();
            return (pick.Date, pick.Kind);
        }

        return (null, null);
    }

    private static string? MovieCertification(JsonNode node)
    {
        var countries = node["releases"]?["results"]?.AsArray() ?? [];
        foreach (var code in new[] { "GB", "US" })
        {
            var c = countries.FirstOrDefault(x => Str(x?["iso_3166_1"]) == code);
            var cert = (c?["release_dates"]?.AsArray() ?? []).Select(r => Str(r?["certification"])).FirstOrDefault(s => !string.IsNullOrWhiteSpace(s));
            if (cert is not null)
            {
                return cert;
            }
        }

        return null;
    }

    private static string? TvRating(JsonNode node)
    {
        var ratings = node["contentRatings"]?["results"]?.AsArray() ?? [];
        foreach (var code in new[] { "GB", "US" })
        {
            var r = Str(ratings.FirstOrDefault(x => Str(x?["iso_3166_1"]) == code)?["rating"]);
            if (!string.IsNullOrWhiteSpace(r))
            {
                return r;
            }
        }

        return null;
    }

    private static string? NormalizeId(string? id) => string.IsNullOrWhiteSpace(id) ? null : id.Replace("-", string.Empty, StringComparison.Ordinal).ToLowerInvariant();

    private static string? NullIfEmpty(string? s) => string.IsNullOrWhiteSpace(s) ? null : s;

    private static string? Str(JsonNode? n)
    {
        if (n is not JsonValue v)
        {
            return null;
        }

        if (v.TryGetValue<string>(out var s))
        {
            return s;
        }

        return v.TryGetValue<double>(out var d) ? d.ToString(CultureInfo.InvariantCulture) : null;
    }

    private static int? Int(JsonNode? n)
    {
        if (n is not JsonValue v)
        {
            return null;
        }

        if (v.TryGetValue<int>(out var i))
        {
            return i;
        }

        if (v.TryGetValue<double>(out var d))
        {
            return (int)d;
        }

        return v.TryGetValue<string>(out var s) && int.TryParse(s, NumberStyles.Integer, CultureInfo.InvariantCulture, out var p) ? p : null;
    }

    private static double? Double(JsonNode? n) => n is JsonValue v && v.TryGetValue<double>(out var d) ? d : null;

    private static bool Bool(JsonNode? n) => n is JsonValue v && v.TryGetValue<bool>(out var b) && b;

    private static DateTime? Date(JsonNode? n)
        => Str(n) is { } s && DateTime.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.AdjustToUniversal | DateTimeStyles.AssumeUniversal, out var d) ? d : null;
}
