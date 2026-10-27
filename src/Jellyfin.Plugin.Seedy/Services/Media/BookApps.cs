using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Seedy.Api;

namespace Jellyfin.Plugin.Seedy.Services.Media;

/// <summary>What a book app says about a request it holds.</summary>
/// <param name="Status">Its own status word.</param>
/// <param name="Attention">Whether it flags the request for a look.</param>
/// <param name="Message">Its own explanation, if any.</param>
/// <param name="Progress">Download progress, 0 to 1, if known.</param>
public sealed record BookAppState(string Status, bool Attention, string? Message, double? Progress);

/// <summary>A book to send to an app.</summary>
/// <param name="WorkId">Work id ("openlibrary:OL893415W").</param>
/// <param name="Title">Title.</param>
/// <param name="Author">Author.</param>
/// <param name="Format">ebook, audiobook or comic.</param>
/// <param name="CoverUrl">Cover.</param>
/// <param name="Year">Year.</param>
/// <param name="Series">Series.</param>
/// <param name="SeriesIndex">Place in the series.</param>
/// <param name="Language">Language code.</param>
/// <param name="Requester">Who asked, for the app's notes.</param>
public sealed record BookOrder(string WorkId, string Title, string? Author, string Format, string? CoverUrl, int? Year, string? Series, double? SeriesIndex, string? Language, string Requester);

/// <summary>Shelfarr's v1 API with an API token: metadata search, requests and their status.</summary>
public sealed class ShelfarrClient
{
    /// <summary>The app's name as people know it.</summary>
    public const string Name = "Shelfarr";

    private readonly ServiceHttp _http;

    /// <summary>
    /// Initializes a new instance of the <see cref="ShelfarrClient"/> class.
    /// </summary>
    /// <param name="http">HTTP helper.</param>
    public ShelfarrClient(ServiceHttp http)
    {
        _http = http;
    }

    /// <summary>Gets whether Shelfarr has an address and token (switched on or not).</summary>
    public static bool HasCredentials
    {
        get
        {
            var c = ConfigMapper.Current;
            return !string.IsNullOrWhiteSpace(c.ShelfarrUrl) && !string.IsNullOrWhiteSpace(c.ShelfarrToken);
        }
    }

    private Task<JsonNode?> Api(HttpMethod method, string path, object? body, CancellationToken ct, TimeSpan? cache = null)
    {
        if (!HasCredentials)
        {
            throw new MediaServiceException(Name, "Shelfarr isn't connected.", HttpStatusCode.ServiceUnavailable);
        }

        var c = ConfigMapper.Current;
        var token = c.ShelfarrToken;
        return _http.SendAsync(Name, method, c.ShelfarrUrl.TrimEnd('/') + "/api/v1" + path, r => r.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", token), body, ct, cache);
    }

    /// <summary>Checks an address and token (the saved ones when not given).</summary>
    /// <param name="url">Address.</param>
    /// <param name="token">Token.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Result.</returns>
    public async Task<MediaTestResultDto> TestAsync(string? url, string? token, CancellationToken ct)
    {
        var c = ConfigMapper.Current;
        var address = (string.IsNullOrWhiteSpace(url) ? c.ShelfarrUrl : url!).Trim().TrimEnd('/');
        var key = string.IsNullOrWhiteSpace(token) ? c.ShelfarrToken : token!.Trim();
        var result = new MediaTestResultDto { Service = "shelfarr" };
        if (!Uri.TryCreate(address, UriKind.Absolute, out var uri) || (uri.Scheme != "http" && uri.Scheme != "https"))
        {
            result.Message = "Add Shelfarr's address, such as http://shelfarr:5056.";
            return result;
        }

        if (string.IsNullOrEmpty(key))
        {
            result.Message = "Add a Shelfarr API token. Make one under Profile › API tokens in Shelfarr.";
            return result;
        }

        void Auth(HttpRequestMessage r) => r.Headers.Authorization = new System.Net.Http.Headers.AuthenticationHeaderValue("Bearer", key);
        try
        {
            var list = await _http.SendAsync(Name, HttpMethod.Get, address + "/api/v1/requests?limit=100", Auth, null, ct, failFast: false).ConfigureAwait(false);
            if (list?["requests"] is not JsonArray requests)
            {
                result.Message = "That address answered, but not like Shelfarr.";
                return result;
            }

            result.Ok = true;
            result.Counts["requests"] = requests.Count;
            result.Counts["open"] = requests.Count(r => J.Str(r?["status"]) is "pending" or "searching" or "downloading" or "processing");
            result.Message = "Connected.";
        }
        catch (MediaServiceException ex)
        {
            result.Message = ex.Status == HttpStatusCode.Forbidden
                ? (ex.Message.Contains("scope", StringComparison.OrdinalIgnoreCase) ? "The token needs the requests scope." : "Shelfarr refused the token.")
                : ex.Unreachable ? $"Couldn't reach Shelfarr at {address}." : ex.Message;
        }

        return result;
    }

    /// <summary>Searches Shelfarr's metadata providers.</summary>
    /// <param name="query">Text.</param>
    /// <param name="ct">Cancellation.</param>
    /// <param name="limit">Most results (up to 20).</param>
    /// <returns>Results.</returns>
    public async Task<JsonArray> SearchAsync(string query, CancellationToken ct, int limit = 20)
        => J.Arr((await Api(HttpMethod.Get, "/search?limit=" + Math.Clamp(limit, 1, 20).ToString(CultureInfo.InvariantCulture) + "&q=" + Uri.EscapeDataString(query), null, ct, TimeSpan.FromHours(6)).ConfigureAwait(false))?["results"]);

    /// <summary>Asks Shelfarr for a book.</summary>
    /// <param name="o">The book.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Shelfarr's request id.</returns>
    public async Task<string> CreateAsync(BookOrder o, CancellationToken ct)
    {
        var body = new JsonObject
        {
            ["work_id"] = o.WorkId,
            ["book_type"] = o.Format == "comic" ? "comicbook" : o.Format,
            ["title"] = o.Title,
            ["author"] = o.Author,
            ["cover_url"] = o.CoverUrl,
            ["year"] = o.Year,
            ["series"] = o.Series,
            ["series_position"] = o.SeriesIndex?.ToString(CultureInfo.InvariantCulture),
            ["language"] = o.Language,
            ["content_kind"] = o.Format == "comic" ? "graphic" : "book",
            ["notes"] = "Requested by " + o.Requester + " in Seedy",
            ["external_source"] = "seedy"
        };
        var res = await Api(HttpMethod.Post, "/requests", body, ct).ConfigureAwait(false);
        var id = J.Str(J.Arr(res?["requests"]).FirstOrDefault()?["id"]);
        if (id is null)
        {
            var error = J.Arr(res?["errors"]).Select(J.Str).FirstOrDefault(e => e is not null);
            throw new MediaServiceException(Name, error ?? "Shelfarr didn't take the request.", HttpStatusCode.BadRequest);
        }

        return id;
    }

    /// <summary>Where a request is in Shelfarr.</summary>
    /// <param name="id">Shelfarr request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Its state.</returns>
    public async Task<BookAppState> StateAsync(string id, CancellationToken ct)
    {
        var r = await Api(HttpMethod.Get, "/requests/" + Uri.EscapeDataString(id), null, ct).ConfigureAwait(false);
        return new BookAppState(J.Str(r?["status"]) ?? "pending", J.Bool(r?["attention_needed"]), J.Str(r?["issue_description"]), null);
    }

    /// <summary>How many downloadable releases Shelfarr found for a request.</summary>
    /// <param name="id">Shelfarr request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Count.</returns>
    public async Task<int> FoundAsync(string id, CancellationToken ct)
    {
        var r = await Api(HttpMethod.Get, "/requests/" + Uri.EscapeDataString(id) + "/search_results", null, ct, TimeSpan.FromMinutes(2)).ConfigureAwait(false);
        return J.Arr(r?["search_results"]).Count(x => J.Bool(x?["downloadable"]) && !J.Bool(x?["blocklisted"]));
    }

    /// <summary>Cancels a request.</summary>
    /// <param name="id">Shelfarr request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>A task.</returns>
    public Task CancelAsync(string id, CancellationToken ct) => Api(HttpMethod.Delete, "/requests/" + Uri.EscapeDataString(id), null, ct);

    /// <summary>Searches again (needs the requests:admin scope).</summary>
    /// <param name="id">Shelfarr request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>A task.</returns>
    public Task RetryAsync(string id, CancellationToken ct) => Api(HttpMethod.Post, "/requests/" + Uri.EscapeDataString(id) + "/retry", null, ct);
}

/// <summary>Librarr's API with an API key (which acts as an admin): requests, approved right away since Seedy already
/// approved them, and their status.</summary>
public sealed class LibrarrClient
{
    /// <summary>The app's name as people know it.</summary>
    public const string Name = "Librarr";

    private readonly ServiceHttp _http;

    /// <summary>
    /// Initializes a new instance of the <see cref="LibrarrClient"/> class.
    /// </summary>
    /// <param name="http">HTTP helper.</param>
    public LibrarrClient(ServiceHttp http)
    {
        _http = http;
    }

    private static bool HasCredentials
    {
        get
        {
            var c = ConfigMapper.Current;
            return !string.IsNullOrWhiteSpace(c.LibrarrUrl) && !string.IsNullOrWhiteSpace(c.LibrarrApiKey);
        }
    }

    private Task<JsonNode?> Api(HttpMethod method, string path, object? body, CancellationToken ct)
    {
        if (!HasCredentials)
        {
            throw new MediaServiceException(Name, "Librarr isn't connected.", HttpStatusCode.ServiceUnavailable);
        }

        var c = ConfigMapper.Current;
        var key = c.LibrarrApiKey;
        return _http.SendAsync(Name, method, c.LibrarrUrl.TrimEnd('/') + "/api" + path, r => r.Headers.Add("X-Api-Key", key), body, ct);
    }

    /// <summary>Checks an address and key (the saved ones when not given).</summary>
    /// <param name="url">Address.</param>
    /// <param name="apiKey">Key.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Result.</returns>
    public async Task<MediaTestResultDto> TestAsync(string? url, string? apiKey, CancellationToken ct)
    {
        var c = ConfigMapper.Current;
        var address = (string.IsNullOrWhiteSpace(url) ? c.LibrarrUrl : url!).Trim().TrimEnd('/');
        var key = string.IsNullOrWhiteSpace(apiKey) ? c.LibrarrApiKey : apiKey!.Trim();
        var result = new MediaTestResultDto { Service = "librarr" };
        if (!Uri.TryCreate(address, UriKind.Absolute, out var uri) || (uri.Scheme != "http" && uri.Scheme != "https"))
        {
            result.Message = "Add Librarr's address, such as http://librarr:5050.";
            return result;
        }

        if (string.IsNullOrEmpty(key))
        {
            result.Message = "Add Librarr's API key. It's under Settings in Librarr.";
            return result;
        }

        void Auth(HttpRequestMessage r) => r.Headers.Add("X-Api-Key", key);
        try
        {
            var health = await _http.SendAsync(Name, HttpMethod.Get, address + "/api/health", Auth, null, ct, failFast: false).ConfigureAwait(false);
            if (J.Str(health?["status"]) != "ok")
            {
                result.Message = "That address answered, but not like Librarr.";
                return result;
            }

            // Health answers without a key; the request list checks it.
            var list = await _http.SendAsync(Name, HttpMethod.Get, address + "/api/requests?limit=1", Auth, null, ct, failFast: false).ConfigureAwait(false);
            result.Ok = list?["requests"] is JsonArray;
            result.Version = J.Str(health?["version"]);
            result.Counts["sources"] = J.Int(health?["sources_enabled"]) ?? 0;
            result.Counts["items"] = J.Int(health?["library_items"]) ?? 0;
            result.Message = result.Ok ? "Connected." : "Librarr didn't accept the API key.";
        }
        catch (MediaServiceException ex)
        {
            result.Message = ex.Status == HttpStatusCode.Forbidden ? "Librarr refused the API key." : ex.Unreachable ? $"Couldn't reach Librarr at {address}." : ex.Message;
        }

        return result;
    }

    /// <summary>Asks Librarr for a book, then approves it at once: Seedy already did.</summary>
    /// <param name="o">The book.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Librarr's request id.</returns>
    public async Task<string> CreateAsync(BookOrder o, CancellationToken ct)
    {
        var body = new JsonObject
        {
            ["title"] = o.Title,
            ["author"] = o.Author ?? string.Empty,
            ["book_type"] = o.Format == "comic" ? "manga" : o.Format,
            ["cover_url"] = o.CoverUrl ?? string.Empty,
            ["description"] = "Requested by " + o.Requester + " in Seedy",
            ["year"] = o.Year?.ToString(CultureInfo.InvariantCulture) ?? string.Empty,
            ["series_name"] = o.Series ?? string.Empty,
            ["series_position"] = o.SeriesIndex?.ToString(CultureInfo.InvariantCulture) ?? string.Empty
        };
        var res = await Api(HttpMethod.Post, "/requests", body, ct).ConfigureAwait(false);
        var id = J.Str(res?["request"]?["id"]) ?? throw new MediaServiceException(Name, J.Str(res?["error"]) ?? "Librarr didn't take the request.", HttpStatusCode.BadRequest);
        await Api(HttpMethod.Put, "/requests/" + Uri.EscapeDataString(id) + "/approve", null, ct).ConfigureAwait(false);
        return id;
    }

    /// <summary>Where a request is in Librarr.</summary>
    /// <param name="id">Librarr request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Its state.</returns>
    public async Task<BookAppState> StateAsync(string id, CancellationToken ct)
    {
        var r = (await Api(HttpMethod.Get, "/requests/" + Uri.EscapeDataString(id), null, ct).ConfigureAwait(false))?["request"];
        var note = J.Str(r?["attention_note"]);
        return new BookAppState(J.Str(r?["status"]) ?? "pending", note is not null, note, null);
    }

    /// <summary>Cancels a request.</summary>
    /// <param name="id">Librarr request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>A task.</returns>
    public Task CancelAsync(string id, CancellationToken ct) => Api(HttpMethod.Put, "/requests/" + Uri.EscapeDataString(id) + "/cancel", null, ct);

    /// <summary>Searches again after a failure.</summary>
    /// <param name="id">Librarr request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>A task.</returns>
    public Task RetryAsync(string id, CancellationToken ct) => Api(HttpMethod.Put, "/requests/" + Uri.EscapeDataString(id) + "/retry", null, ct);
}

/// <summary>The Readarr v1 API, as Bookshelf, Chaptarr and other Readarr forks still serve it: a connection test, adding
/// a book with a search, and its status.</summary>
public sealed class ReadarrClient
{
    /// <summary>The app's name as people know it.</summary>
    public const string Name = "Readarr";

    private readonly ServiceHttp _http;

    /// <summary>
    /// Initializes a new instance of the <see cref="ReadarrClient"/> class.
    /// </summary>
    /// <param name="http">HTTP helper.</param>
    public ReadarrClient(ServiceHttp http)
    {
        _http = http;
    }

    private Task<JsonNode?> Api(HttpMethod method, string path, object? body, CancellationToken ct, TimeSpan? cache = null)
    {
        var c = ConfigMapper.Current;
        if (string.IsNullOrWhiteSpace(c.ReadarrUrl) || string.IsNullOrWhiteSpace(c.ReadarrApiKey))
        {
            throw new MediaServiceException(Name, "The Readarr-API app isn't connected.", HttpStatusCode.ServiceUnavailable);
        }

        var key = c.ReadarrApiKey;
        return _http.SendAsync(Name, method, c.ReadarrUrl.TrimEnd('/') + "/api/v1" + path, r => r.Headers.Add("X-Api-Key", key), body, ct, cache);
    }

    /// <summary>Checks an address and key (the saved ones when not given).</summary>
    /// <param name="url">Address.</param>
    /// <param name="apiKey">Key.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Result.</returns>
    public async Task<MediaTestResultDto> TestAsync(string? url, string? apiKey, CancellationToken ct)
    {
        var c = ConfigMapper.Current;
        var address = (string.IsNullOrWhiteSpace(url) ? c.ReadarrUrl : url!).Trim().TrimEnd('/');
        var key = string.IsNullOrWhiteSpace(apiKey) ? c.ReadarrApiKey : apiKey!.Trim();
        var result = new MediaTestResultDto { Service = "readarr" };
        if (!Uri.TryCreate(address, UriKind.Absolute, out var uri) || (uri.Scheme != "http" && uri.Scheme != "https"))
        {
            result.Message = "Add the app's address, such as http://bookshelf:8787.";
            return result;
        }

        if (string.IsNullOrEmpty(key))
        {
            result.Message = "Add the app's API key. It's under Settings › General.";
            return result;
        }

        try
        {
            var status = await _http.SendAsync(Name, HttpMethod.Get, address + "/api/v1/system/status", r => r.Headers.Add("X-Api-Key", key), null, ct, failFast: false).ConfigureAwait(false);
            var version = J.Str(status?["version"]);
            result.Ok = version is not null;
            result.Version = version;
            result.Message = result.Ok ? "Connected." : "That address answered, but not like a Readarr-API app.";
            if (J.Str(status?["appName"]) is { } app)
            {
                result.Counts["app:" + app] = 1;
            }
        }
        catch (MediaServiceException ex)
        {
            result.Message = ex.Status == HttpStatusCode.Forbidden ? "The app refused the API key." : ex.Unreachable ? $"Couldn't reach the app at {address}." : ex.Message;
        }

        return result;
    }

    /// <summary>Adds a book (and its author, unwatched) and searches for it.</summary>
    /// <param name="o">The book.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The app's book id.</returns>
    public async Task<string> CreateAsync(BookOrder o, CancellationToken ct)
    {
        var term = string.IsNullOrWhiteSpace(o.Author) ? o.Title : o.Title + " " + o.Author;
        var found = J.Arr(await Api(HttpMethod.Get, "/book/lookup?term=" + Uri.EscapeDataString(term), null, ct).ConfigureAwait(false));
        var pick = found.FirstOrDefault(b => string.Equals(J.Str(b?["title"]), o.Title, StringComparison.OrdinalIgnoreCase)) ?? found.FirstOrDefault()
            ?? throw new MediaServiceException(Name, "The app couldn't find this book.", HttpStatusCode.NotFound);
        if (J.Int(pick["id"]) is > 0 and var existing)
        {
            await Api(HttpMethod.Post, "/command", new JsonObject { ["name"] = "BookSearch", ["bookIds"] = new JsonArray(existing) }, ct).ConfigureAwait(false);
            return existing.ToString(CultureInfo.InvariantCulture);
        }

        var quality = J.Int(J.Arr(await Api(HttpMethod.Get, "/qualityprofile", null, ct, TimeSpan.FromMinutes(5)).ConfigureAwait(false)).FirstOrDefault()?["id"]) ?? 1;
        var metadata = J.Int(J.Arr(await Api(HttpMethod.Get, "/metadataprofile", null, ct, TimeSpan.FromMinutes(5)).ConfigureAwait(false)).FirstOrDefault()?["id"]) ?? 1;
        var root = J.Str(J.Arr(await Api(HttpMethod.Get, "/rootfolder", null, ct, TimeSpan.FromMinutes(5)).ConfigureAwait(false)).FirstOrDefault()?["path"])
            ?? throw new MediaServiceException(Name, "The app has no root folder yet.", HttpStatusCode.BadRequest);
        var body = pick.DeepClone();
        body["monitored"] = true;
        body["addOptions"] = new JsonObject { ["searchForNewBook"] = true };
        if (body["author"] is JsonObject author)
        {
            author["qualityProfileId"] = quality;
            author["metadataProfileId"] = metadata;
            author["rootFolderPath"] = root;
            author["monitored"] = true;
            author["monitorNewItems"] = "none";
            author["addOptions"] = new JsonObject { ["monitor"] = "none", ["searchForMissingBooks"] = false };
        }

        var added = await Api(HttpMethod.Post, "/book", body, ct).ConfigureAwait(false);
        return J.Str(added?["id"]) ?? throw new MediaServiceException(Name, "The app didn't add the book.", HttpStatusCode.BadGateway);
    }

    /// <summary>Where a book is: downloaded, downloading, or still wanted.</summary>
    /// <param name="id">The app's book id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Its state.</returns>
    public async Task<BookAppState> StateAsync(string id, CancellationToken ct)
    {
        var book = await Api(HttpMethod.Get, "/book/" + Uri.EscapeDataString(id), null, ct).ConfigureAwait(false);
        if ((J.Int(book?["statistics"]?["bookFileCount"]) ?? 0) > 0)
        {
            return new BookAppState("completed", false, null, 1);
        }

        var queue = J.Arr((await Api(HttpMethod.Get, "/queue?page=1&pageSize=200", null, ct, TimeSpan.FromSeconds(20)).ConfigureAwait(false))?["records"]);
        var q = queue.FirstOrDefault(r => J.Str(r?["bookId"]) == id);
        if (q is not null)
        {
            var size = J.Double(q["size"]) ?? 0;
            var left = J.Double(q["sizeleft"]) ?? 0;
            return new BookAppState("downloading", J.Str(q["trackedDownloadStatus"]) is "warning" or "error", J.Arr(q["statusMessages"]).SelectMany(m => J.Arr(m?["messages"])).Select(J.Str).FirstOrDefault(m => m is not null), size > 0 ? Math.Clamp((size - left) / size, 0, 1) : null);
        }

        return new BookAppState("searching", false, null, null);
    }

    /// <summary>Searches again.</summary>
    /// <param name="id">The app's book id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>A task.</returns>
    public Task RetryAsync(string id, CancellationToken ct)
        => Api(HttpMethod.Post, "/command", new JsonObject { ["name"] = "BookSearch", ["bookIds"] = new JsonArray(int.Parse(id, CultureInfo.InvariantCulture)) }, ct);
}
