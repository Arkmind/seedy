using System;
using System.Collections.Generic;
using System.Linq;
using System.Net.Http;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;

namespace Jellyfin.Plugin.Seedy.Services.Media;

/// <summary>One release group (album, EP or single) as MusicBrainz lists it.</summary>
/// <param name="Mbid">Release group id.</param>
/// <param name="Title">Title.</param>
/// <param name="Type">album, ep or single.</param>
/// <param name="Secondary">Secondary types (live, compilation…), lowercase.</param>
/// <param name="Date">First release date as MusicBrainz gives it.</param>
public sealed record ReleaseGroup(string Mbid, string Title, string Type, IReadOnlyList<string> Secondary, string? Date);

/// <summary>Track count and length of a release group's main release.</summary>
/// <param name="Tracks">Tracks.</param>
/// <param name="LengthMs">Length, when MusicBrainz knows every track's.</param>
public sealed record ReleaseLength(int Tracks, long? LengthMs);

/// <summary>MusicBrainz (artists, their releases), ListenBrainz (what's trending) and the Cover Art Archive. MusicBrainz
/// asks for a real User-Agent and at most one request a second, so calls queue behind each other and answers are kept
/// for a day.</summary>
public sealed class MusicBrainzClient
{
    private const string Name = "MusicBrainz";
    private static readonly TimeSpan Day = TimeSpan.FromHours(24);
    private readonly ServiceHttp _http;
    private readonly SemaphoreSlim _gate = new(1, 1);
    private DateTime _last = DateTime.MinValue;

    /// <summary>
    /// Initializes a new instance of the <see cref="MusicBrainzClient"/> class.
    /// </summary>
    /// <param name="http">HTTP helper.</param>
    public MusicBrainzClient(ServiceHttp http)
    {
        _http = http;
    }

    /// <summary>A release group's front cover from the Cover Art Archive (a redirect to the image).</summary>
    /// <param name="rgid">Release group id.</param>
    /// <returns>Address.</returns>
    public static string Cover(string rgid) => "https://coverartarchive.org/release-group/" + rgid + "/front-250";

    private async Task<JsonNode?> Mb(string path, CancellationToken ct)
    {
        var url = "https://musicbrainz.org/ws/2" + path + (path.Contains('?', StringComparison.Ordinal) ? "&" : "?") + "fmt=json";
        // Cached answers skip the queue.
        if (_http.TryCached(Name, url, Day, out var hit))
        {
            return hit;
        }

        await _gate.WaitAsync(ct).ConfigureAwait(false);
        try
        {
            if (_http.TryCached(Name, url, Day, out hit))
            {
                return hit;
            }

            var wait = _last.AddMilliseconds(1100) - DateTime.UtcNow;
            if (wait > TimeSpan.Zero)
            {
                await Task.Delay(wait, ct).ConfigureAwait(false);
            }

            _last = DateTime.UtcNow;
            return await _http.SendAsync(Name, HttpMethod.Get, url, null, null, ct, Day, TimeSpan.FromSeconds(20)).ConfigureAwait(false);
        }
        finally
        {
            _last = DateTime.UtcNow;
            _gate.Release();
        }
    }

    /// <summary>Gets whether an artist's release groups are already cached, so a page can skip slow lookups.</summary>
    /// <param name="artistMbid">Artist id.</param>
    /// <returns>The release groups, or null when not cached.</returns>
    public List<ReleaseGroup>? CachedReleaseGroups(string artistMbid)
        => _http.TryCached(Name, "https://musicbrainz.org/ws/2/release-group?artist=" + artistMbid + "&limit=100&fmt=json", Day, out var node) ? Groups(node) : null;

    /// <summary>An artist's albums, EPs and singles.</summary>
    /// <param name="artistMbid">Artist id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Release groups.</returns>
    public async Task<List<ReleaseGroup>> ReleaseGroupsAsync(string artistMbid, CancellationToken ct)
        => Groups(await Mb("/release-group?artist=" + artistMbid + "&limit=100", ct).ConfigureAwait(false));

    private static List<ReleaseGroup> Groups(JsonNode? node)
        => J.Arr(node?["release-groups"]).Select(Group).OfType<ReleaseGroup>().ToList();

    private static ReleaseGroup? Group(JsonNode? g)
    {
        var id = J.Str(g?["id"]);
        var title = J.Str(g?["title"]);
        var type = (J.Str(g?["primary-type"]) ?? string.Empty).ToLowerInvariant();
        if (id is null || title is null || type is not ("album" or "ep" or "single"))
        {
            return null;
        }

        var secondary = J.Arr(g?["secondary-types"]).Select(J.Str).OfType<string>().Select(s => s.ToLowerInvariant()).ToList();
        return new ReleaseGroup(id, title, type, secondary, J.Str(g?["first-release-date"]));
    }

    /// <summary>An artist's name, type and area.</summary>
    /// <param name="mbid">Artist id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Name, area and type, or null.</returns>
    public async Task<(string Name, string? Area, string? Type, string? Disambiguation)?> ArtistAsync(string mbid, CancellationToken ct)
    {
        var a = await Mb("/artist/" + mbid, ct).ConfigureAwait(false);
        var name = J.Str(a?["name"]);
        return name is null ? null : (name, J.Str(a?["begin-area"]?["name"]) ?? J.Str(a?["area"]?["name"]), J.Str(a?["type"]), J.Str(a?["disambiguation"]));
    }

    /// <summary>Track counts and lengths for an artist's release groups, from their official releases.</summary>
    /// <param name="artistMbid">Artist id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>By release group id.</returns>
    public async Task<Dictionary<string, ReleaseLength>> LengthsAsync(string artistMbid, CancellationToken ct)
    {
        var node = await Mb("/release?artist=" + artistMbid + "&status=official&inc=release-groups+media+recordings&limit=100", ct).ConfigureAwait(false);
        var map = new Dictionary<string, ReleaseLength>(StringComparer.OrdinalIgnoreCase);
        foreach (var r in J.Arr(node?["releases"]))
        {
            var rg = J.Str(r?["release-group"]?["id"]);
            if (rg is null)
            {
                continue;
            }

            var media = J.Arr(r?["media"]);
            var tracks = media.Sum(m => J.Int(m?["track-count"]) ?? 0);
            var lengths = media.SelectMany(m => J.Arr(m?["tracks"])).Select(t => J.Long(t?["length"])).ToList();
            long? length = lengths.Count > 0 && lengths.All(l => l is > 0) ? lengths.Sum(l => l!.Value) : null;

            // The release with the fewest tracks is usually the original, not a deluxe edition.
            if (tracks > 0 && (!map.TryGetValue(rg, out var have) || tracks < have.Tracks || (have.LengthMs is null && length is not null && tracks == have.Tracks)))
            {
                map[rg] = new ReleaseLength(tracks, length);
            }
        }

        return map;
    }

    /// <summary>Searches MusicBrainz for artists.</summary>
    /// <param name="query">Text.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Artists: id, name, disambiguation, type, area.</returns>
    public async Task<List<(string Mbid, string Name, string? Disambiguation, string? Type, string? Area)>> SearchArtistsAsync(string query, CancellationToken ct)
    {
        var node = await Mb("/artist?limit=8&query=" + Uri.EscapeDataString(query), ct).ConfigureAwait(false);
        return J.Arr(node?["artists"])
            .Select(a => (J.Str(a?["id"]) ?? string.Empty, J.Str(a?["name"]) ?? string.Empty, J.Str(a?["disambiguation"]), J.Str(a?["type"]), J.Str(a?["area"]?["name"])))
            .Where(a => a.Item1.Length > 0 && a.Item2.Length > 0).ToList();
    }

    /// <summary>Searches MusicBrainz for albums, EPs and singles.</summary>
    /// <param name="query">Text.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Release groups with their artist.</returns>
    public async Task<List<(ReleaseGroup Group, string? Artist, string? ArtistMbid)>> SearchReleaseGroupsAsync(string query, CancellationToken ct)
    {
        var node = await Mb("/release-group?limit=16&query=" + Uri.EscapeDataString(query), ct).ConfigureAwait(false);
        return J.Arr(node?["release-groups"])
            .Select(g => (Group(g), J.Str(J.Arr(g?["artist-credit"]).FirstOrDefault()?["name"]), J.Str(J.Arr(g?["artist-credit"]).FirstOrDefault()?["artist"]?["id"])))
            .Where(x => x.Item1 is not null)
            .Select(x => (x.Item1!, x.Item2, x.Item3)).ToList();
    }

    /// <summary>The release groups most listened to on ListenBrainz this week.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Release group id, title, artist, artist id and a cover.</returns>
    public async Task<List<(string Mbid, string Title, string Artist, string? ArtistMbid, string? Cover)>> TrendingAsync(CancellationToken ct)
    {
        var node = await _http.SendAsync("ListenBrainz", HttpMethod.Get, "https://api.listenbrainz.org/1/stats/sitewide/release-groups?range=week&count=40", null, null, ct, TimeSpan.FromHours(6), TimeSpan.FromSeconds(20)).ConfigureAwait(false);
        var list = new List<(string, string, string, string?, string?)>();
        foreach (var g in J.Arr(node?["payload"]?["release_groups"]))
        {
            var id = J.Str(g?["release_group_mbid"]);
            var title = J.Str(g?["release_group_name"]);
            var artist = J.Str(g?["artist_name"]);
            if (id is null || title is null || artist is null)
            {
                continue;
            }

            var caa = J.Str(g?["caa_id"]);
            var release = J.Str(g?["caa_release_mbid"]);
            var cover = caa is not null && release is not null ? $"https://coverartarchive.org/release/{release}/{caa}-250.jpg" : Cover(id);
            list.Add((id, title, artist, J.Arr(g?["artist_mbids"]).Select(J.Str).FirstOrDefault(), cover));
        }

        return list;
    }
}
