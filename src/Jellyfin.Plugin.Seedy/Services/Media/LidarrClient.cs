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

/// <summary>Lidarr's v1 API with the server-side key: profiles and folders for the admin, adding and monitoring
/// artists and albums, searches, the download queue and the release calendar.</summary>
public sealed class LidarrClient
{
    /// <summary>The app's name as people know it.</summary>
    public const string Name = "Lidarr";

    private static readonly TimeSpan Short = TimeSpan.FromSeconds(20);
    private static readonly TimeSpan Medium = TimeSpan.FromMinutes(5);
    private static readonly TimeSpan Long = TimeSpan.FromMinutes(30);

    private readonly ServiceHttp _http;

    /// <summary>
    /// Initializes a new instance of the <see cref="LidarrClient"/> class.
    /// </summary>
    /// <param name="http">HTTP helper.</param>
    public LidarrClient(ServiceHttp http)
    {
        _http = http;
    }

    /// <summary>Gets whether Lidarr has an address and key.</summary>
    public static bool IsConfigured => ConfigMapper.LidarrReady(ConfigMapper.Current);

    private static string Base => ConfigMapper.Current.LidarrUrl.TrimEnd('/') + "/api/v1";

    private Task<JsonNode?> Api(HttpMethod method, string path, object? body, CancellationToken ct, TimeSpan? cache = null)
    {
        if (!IsConfigured)
        {
            throw new MediaServiceException(Name, "Lidarr isn't connected.", HttpStatusCode.ServiceUnavailable);
        }

        var key = ConfigMapper.Current.LidarrApiKey;
        return _http.SendAsync(Name, method, Base + path, r => r.Headers.Add("X-Api-Key", key), body, ct, cache);
    }

    /// <summary>Forgets cached answers.</summary>
    public void Reset() => _http.Reset(Name);

    private void ForgetLibrary()
    {
        _http.Forget(Name, Base + "/artist");
        _http.Forget(Name, Base + "/album");
        _http.Forget(Name, Base + "/queue");
    }

    /// <summary>Checks an address and key (the saved ones when not given).</summary>
    /// <param name="url">Address.</param>
    /// <param name="key">Key.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Result.</returns>
    public async Task<MediaTestResultDto> TestAsync(string? url, string? key, CancellationToken ct)
    {
        var c = ConfigMapper.Current;
        var address = (string.IsNullOrWhiteSpace(url) ? c.LidarrUrl : url!).Trim().TrimEnd('/');
        var apiKey = string.IsNullOrWhiteSpace(key) ? c.LidarrApiKey : key!.Trim();
        var result = new MediaTestResultDto { Service = "lidarr" };
        if (!Uri.TryCreate(address, UriKind.Absolute, out var uri) || (uri.Scheme != "http" && uri.Scheme != "https"))
        {
            result.Message = "Add Lidarr's address, such as http://lidarr:8686.";
            return result;
        }

        if (string.IsNullOrEmpty(apiKey))
        {
            result.Message = "Add Lidarr's API key. It's under Settings › General in Lidarr.";
            return result;
        }

        void Auth(HttpRequestMessage r) => r.Headers.Add("X-Api-Key", apiKey);
        try
        {
            var status = await _http.SendAsync(Name, HttpMethod.Get, address + "/api/v1/system/status", Auth, null, ct, failFast: false).ConfigureAwait(false);
            var artists = await _http.SendAsync(Name, HttpMethod.Get, address + "/api/v1/artist", Auth, null, ct, failFast: false).ConfigureAwait(false);
            var queue = await _http.SendAsync(Name, HttpMethod.Get, address + "/api/v1/queue/status", Auth, null, ct, failFast: false).ConfigureAwait(false);
            var version = J.Str(status?["version"]);
            result.Ok = J.Str(status?["appName"]) is null or "Lidarr" && version is not null;
            if (!result.Ok)
            {
                result.Message = "That address answered, but not like Lidarr.";
                return result;
            }

            result.Version = ShortVersion(version);
            result.Counts["artists"] = J.Arr(artists).Count(a => J.Bool(a?["monitored"]));
            result.Counts["downloads"] = J.Int(queue?["totalCount"]) ?? 0;
            result.Message = "Connected.";
        }
        catch (MediaServiceException ex)
        {
            result.Message = ex.Status == HttpStatusCode.Forbidden ? "Lidarr refused the API key." : ex.Unreachable ? $"Couldn't reach Lidarr at {address}." : ex.Message;
        }

        return result;
    }

    private static string? ShortVersion(string? v)
    {
        if (v is null)
        {
            return null;
        }

        var parts = v.Split('.');
        return parts.Length >= 2 ? parts[0] + "." + parts[1] : v;
    }

    /// <summary>Lidarr's quality profiles.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Profiles.</returns>
    public async Task<List<IdNameDto>> QualityProfilesAsync(CancellationToken ct)
        => J.Arr(await Api(HttpMethod.Get, "/qualityprofile", null, ct, Medium).ConfigureAwait(false))
            .Select(p => new IdNameDto { Id = J.Int(p?["id"]) ?? 0, Name = J.Str(p?["name"]) ?? "?" }).ToList();

    /// <summary>Lidarr's metadata profiles (which release types it watches).</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Profiles.</returns>
    public async Task<List<IdNameDto>> MetadataProfilesAsync(CancellationToken ct)
        => J.Arr(await Api(HttpMethod.Get, "/metadataprofile", null, ct, Medium).ConfigureAwait(false))
            .Select(p => new IdNameDto { Id = J.Int(p?["id"]) ?? 0, Name = J.Str(p?["name"]) ?? "?" })
            .Where(p => p.Name != "None").ToList();

    /// <summary>Lidarr's root folders.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Path and free space.</returns>
    public async Task<List<(string Path, long? Free)>> RootFoldersAsync(CancellationToken ct)
        => J.Arr(await Api(HttpMethod.Get, "/rootfolder", null, ct, Medium).ConfigureAwait(false))
            .Select(f => (J.Str(f?["path"]) ?? string.Empty, J.Long(f?["freeSpace"]))).Where(f => f.Item1.Length > 0).ToList();

    /// <summary>The quality profile requests use: the admin's pick, else Lidarr's first.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The profile, or null.</returns>
    public async Task<IdNameDto?> QualityAsync(CancellationToken ct)
    {
        var all = await QualityProfilesAsync(ct).ConfigureAwait(false);
        return all.FirstOrDefault(p => p.Id == ConfigMapper.Current.LidarrQualityProfileId) ?? all.FirstOrDefault();
    }

    /// <summary>A lesser quality to fall back on when the chosen one can't be found: a profile named for MP3 or
    /// "standard" quality other than the current one.</summary>
    /// <param name="currentId">The artist's current profile.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The profile, or null when there's none.</returns>
    public async Task<IdNameDto?> LowerQualityAsync(int currentId, CancellationToken ct)
    {
        var all = await QualityProfilesAsync(ct).ConfigureAwait(false);
        string[] words = ["320", "mp3", "standard", "lossy", "any"];
        return all.Where(p => p.Id != currentId)
            .Select(p => (p, rank: Array.FindIndex(words, w => p.Name.Contains(w, StringComparison.OrdinalIgnoreCase))))
            .Where(x => x.rank >= 0)
            .OrderBy(x => x.rank)
            .Select(x => x.p)
            .FirstOrDefault();
    }

    /// <summary>Every artist Lidarr has.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Artists.</returns>
    public async Task<JsonArray> ArtistsAsync(CancellationToken ct) => J.Arr(await Api(HttpMethod.Get, "/artist", null, ct, Short).ConfigureAwait(false));

    /// <summary>An artist Lidarr has, by MusicBrainz id.</summary>
    /// <param name="mbid">MusicBrainz artist id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The artist, or null.</returns>
    public async Task<JsonNode?> ArtistAsync(string mbid, CancellationToken ct)
        => (await ArtistsAsync(ct).ConfigureAwait(false)).FirstOrDefault(a => string.Equals(J.Str(a?["foreignArtistId"]), mbid, StringComparison.OrdinalIgnoreCase));

    /// <summary>An artist as Lidarr's metadata knows it (for adding).</summary>
    /// <param name="mbid">MusicBrainz artist id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The artist, or null.</returns>
    public async Task<JsonNode?> LookupArtistAsync(string mbid, CancellationToken ct)
        => J.Arr(await Api(HttpMethod.Get, "/artist/lookup?term=" + Uri.EscapeDataString("lidarr:" + mbid), null, ct, Long).ConfigureAwait(false)).FirstOrDefault();

    /// <summary>An album as Lidarr's metadata knows it (for adding).</summary>
    /// <param name="rgid">MusicBrainz release group id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The album, or null.</returns>
    public async Task<JsonNode?> LookupAlbumAsync(string rgid, CancellationToken ct)
        => J.Arr(await Api(HttpMethod.Get, "/album/lookup?term=" + Uri.EscapeDataString("lidarr:" + rgid), null, ct, Long).ConfigureAwait(false)).FirstOrDefault();

    /// <summary>Searches Lidarr's metadata for artists.</summary>
    /// <param name="term">Text.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Artists.</returns>
    public async Task<JsonArray> SearchArtistsAsync(string term, CancellationToken ct)
        => J.Arr(await Api(HttpMethod.Get, "/artist/lookup?term=" + Uri.EscapeDataString(term), null, ct, Medium).ConfigureAwait(false));

    /// <summary>Searches Lidarr's metadata for albums.</summary>
    /// <param name="term">Text.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Albums.</returns>
    public async Task<JsonArray> SearchAlbumsAsync(string term, CancellationToken ct)
        => J.Arr(await Api(HttpMethod.Get, "/album/lookup?term=" + Uri.EscapeDataString(term), null, ct, Medium).ConfigureAwait(false));

    /// <summary>An artist's albums in Lidarr.</summary>
    /// <param name="artistId">Lidarr artist id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <param name="fresh">Skip the short cache.</param>
    /// <returns>Albums.</returns>
    public async Task<JsonArray> AlbumsAsync(int artistId, CancellationToken ct, bool fresh = false)
        => J.Arr(await Api(HttpMethod.Get, "/album?artistId=" + artistId.ToString(CultureInfo.InvariantCulture), null, ct, fresh ? null : Short).ConfigureAwait(false));

    /// <summary>Adds an artist.</summary>
    /// <param name="mbid">MusicBrainz artist id.</param>
    /// <param name="monitor">all, future, none…</param>
    /// <param name="monitorNew">all, new or none.</param>
    /// <param name="search">Search for missing albums right away.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The artist as Lidarr stored it.</returns>
    public async Task<JsonNode?> AddArtistAsync(string mbid, string monitor, string monitorNew, bool search, CancellationToken ct)
    {
        var resource = await LookupArtistAsync(mbid, ct).ConfigureAwait(false)
            ?? throw new MediaServiceException(Name, "Lidarr doesn't know this artist yet.", HttpStatusCode.NotFound);
        var body = resource.DeepClone();
        await Fill(body, ct).ConfigureAwait(false);
        body["monitored"] = true;
        body["monitorNewItems"] = monitorNew;
        body["addOptions"] = new JsonObject { ["monitor"] = monitor, ["searchForMissingAlbums"] = search };
        var added = await Api(HttpMethod.Post, "/artist", body, ct).ConfigureAwait(false);
        ForgetLibrary();
        return added;
    }

    /* The admin's profile and folder, or Lidarr's first ones. */
    private async Task Fill(JsonNode artist, CancellationToken ct)
    {
        var c = ConfigMapper.Current;
        var quality = c.LidarrQualityProfileId;
        if (quality <= 0)
        {
            quality = (await QualityProfilesAsync(ct).ConfigureAwait(false)).FirstOrDefault()?.Id ?? 1;
        }

        var metadata = c.LidarrMetadataProfileId;
        if (metadata <= 0)
        {
            metadata = (await MetadataProfilesAsync(ct).ConfigureAwait(false)).FirstOrDefault()?.Id ?? 1;
        }

        var root = c.LidarrRootFolder;
        if (string.IsNullOrEmpty(root))
        {
            root = (await RootFoldersAsync(ct).ConfigureAwait(false)).FirstOrDefault().Path
                ?? throw new MediaServiceException(Name, "Lidarr has no root folder yet.", HttpStatusCode.BadRequest);
        }

        artist["qualityProfileId"] = quality;
        artist["metadataProfileId"] = metadata;
        artist["rootFolderPath"] = root;
    }

    /// <summary>Adds one album (and its artist, unwatched, when Lidarr doesn't have it).</summary>
    /// <param name="rgid">MusicBrainz release group id.</param>
    /// <param name="monitorNew">The artist's new-album setting when it's added: all or none.</param>
    /// <param name="search">Search for it right away.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The album as Lidarr stored it.</returns>
    public async Task<JsonNode?> AddAlbumAsync(string rgid, string monitorNew, bool search, CancellationToken ct)
    {
        var resource = await LookupAlbumAsync(rgid, ct).ConfigureAwait(false)
            ?? throw new MediaServiceException(Name, "Lidarr doesn't know this album yet.", HttpStatusCode.NotFound);
        var body = resource.DeepClone();
        body["monitored"] = true;
        body["anyReleaseOk"] = true;
        body["addOptions"] = new JsonObject { ["searchForNewAlbum"] = search };
        if (body["artist"] is JsonObject artist)
        {
            await Fill(artist, ct).ConfigureAwait(false);
            artist["monitored"] = true;
            artist["monitorNewItems"] = monitorNew;
            artist["addOptions"] = new JsonObject { ["monitor"] = "none", ["searchForMissingAlbums"] = false };
        }

        var added = await Api(HttpMethod.Post, "/album", body, ct).ConfigureAwait(false);
        ForgetLibrary();
        return added;
    }

    /// <summary>Saves changes to an artist.</summary>
    /// <param name="artist">The artist as Lidarr gave it, changed.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>A task.</returns>
    public async Task UpdateArtistAsync(JsonNode artist, CancellationToken ct)
    {
        await Api(HttpMethod.Put, "/artist/" + (J.Int(artist["id"]) ?? 0).ToString(CultureInfo.InvariantCulture), artist, ct).ConfigureAwait(false);
        ForgetLibrary();
    }

    /// <summary>Watches (or stops watching) albums.</summary>
    /// <param name="albumIds">Lidarr album ids.</param>
    /// <param name="monitored">Watch them.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>A task.</returns>
    public async Task MonitorAlbumsAsync(IReadOnlyCollection<int> albumIds, bool monitored, CancellationToken ct)
    {
        if (albumIds.Count == 0)
        {
            return;
        }

        await Api(HttpMethod.Put, "/album/monitor", new JsonObject { ["albumIds"] = new JsonArray(albumIds.Select(i => (JsonNode)i).ToArray()), ["monitored"] = monitored }, ct).ConfigureAwait(false);
        ForgetLibrary();
    }

    /// <summary>Searches for albums now.</summary>
    /// <param name="albumIds">Lidarr album ids.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>A task.</returns>
    public Task SearchAlbumsNowAsync(IReadOnlyCollection<int> albumIds, CancellationToken ct)
        => albumIds.Count == 0 ? Task.CompletedTask
            : Api(HttpMethod.Post, "/command", new JsonObject { ["name"] = "AlbumSearch", ["albumIds"] = new JsonArray(albumIds.Select(i => (JsonNode)i).ToArray()) }, ct);

    /// <summary>Searches for an artist's missing albums now.</summary>
    /// <param name="artistId">Lidarr artist id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>A task.</returns>
    public Task SearchArtistNowAsync(int artistId, CancellationToken ct)
        => Api(HttpMethod.Post, "/command", new JsonObject { ["name"] = "ArtistSearch", ["artistId"] = artistId }, ct);

    /// <summary>What Lidarr is downloading.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Queue records.</returns>
    public async Task<JsonArray> QueueAsync(CancellationToken ct)
        => J.Arr((await Api(HttpMethod.Get, "/queue?page=1&pageSize=500&includeAlbum=true", null, ct, Short).ConfigureAwait(false))?["records"]);

    /// <summary>Albums coming out soon for artists Lidarr watches.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Albums with their artists.</returns>
    public async Task<JsonArray> CalendarAsync(CancellationToken ct)
    {
        var start = DateTime.UtcNow.Date.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        var end = DateTime.UtcNow.Date.AddDays(400).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        return J.Arr(await Api(HttpMethod.Get, $"/calendar?start={start}&end={end}&unmonitored=true&includeArtist=true", null, ct, Long).ConfigureAwait(false));
    }

    /// <summary>A cover from Lidarr's metadata images.</summary>
    /// <param name="node">Album or artist.</param>
    /// <param name="type">cover, poster…</param>
    /// <returns>Address, or null.</returns>
    public static string? Image(JsonNode? node, string type)
    {
        var img = J.Arr(node?["images"]).FirstOrDefault(i => string.Equals(J.Str(i?["coverType"]), type, StringComparison.OrdinalIgnoreCase));
        var url = J.Str(img?["remoteUrl"]) ?? J.Str(img?["url"]);
        return url is not null && url.StartsWith("http", StringComparison.OrdinalIgnoreCase) ? url : null;
    }
}
