using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.Linq;
using System.Net;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Plugin.Seedy.Api;
using Jellyfin.Plugin.Seedy.Data;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services.Media;

/// <summary>What the Music and Books request pages offer: rows built from the server's artists and the person's
/// reading, trending lists, searches and the details the request windows show, each marked with whether it's on the
/// server, requested or downloading.</summary>
public sealed class MediaDiscoverService
{
    private static readonly string[] SkippedSecondary = ["compilation", "dj-mix", "mixtape/street", "demo", "interview", "audiobook", "audio drama", "spokenword", "remix", "field recording"];
    private readonly MusicBrainzClient _mb;
    private readonly LidarrClient _lidarr;
    private readonly ShelfarrClient _shelfarr;
    private readonly OpenLibraryClient _openLibrary;
    private readonly MediaLibrary _library;
    private readonly MediaRequestService _requests;
    private readonly SeedyDatabase _db;
    private readonly ILibraryManager _items;
    private readonly ILogger<MediaDiscoverService> _logger;

    /// <summary>
    /// Initializes a new instance of the <see cref="MediaDiscoverService"/> class.
    /// </summary>
    /// <param name="mb">MusicBrainz.</param>
    /// <param name="lidarr">Lidarr.</param>
    /// <param name="shelfarr">Shelfarr.</param>
    /// <param name="openLibrary">Open Library.</param>
    /// <param name="library">What's on the server.</param>
    /// <param name="requests">Requests.</param>
    /// <param name="db">Database.</param>
    /// <param name="items">Library manager.</param>
    /// <param name="logger">Logger.</param>
    public MediaDiscoverService(MusicBrainzClient mb, LidarrClient lidarr, ShelfarrClient shelfarr, OpenLibraryClient openLibrary, MediaLibrary library, MediaRequestService requests, SeedyDatabase db, ILibraryManager items, ILogger<MediaDiscoverService> logger)
    {
        _mb = mb;
        _lidarr = lidarr;
        _shelfarr = shelfarr;
        _openLibrary = openLibrary;
        _library = library;
        _requests = requests;
        _db = db;
        _items = items;
        _logger = logger;
    }

    private static bool Kept(ReleaseGroup g) => !g.Secondary.Any(s => SkippedSecondary.Contains(s));

    /* Rows leave out live recordings too: for many artists they're mostly concert bootlegs. */
    private static bool Studio(ReleaseGroup g) => Kept(g) && !g.Secondary.Contains("live");

    private static DateTime? Day(string? date)
    {
        if (date is null)
        {
            return null;
        }

        var formats = new[] { "yyyy-MM-dd", "yyyy-MM", "yyyy" };
        return DateTime.TryParseExact(date, formats, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out var d) ? d : null;
    }

    // ── Music ──

    /* The artists a row is built from: the ones the person plays, then the rest of the server's. */
    private List<ServerArtist> ArtistsFor(User user, int limit)
    {
        var played = _library.PlayedArtists(user, limit);
        if (played.Count >= limit)
        {
            return played;
        }

        var (albums, artists) = _library.Music();
        var counts = albums.Where(a => a.ArtistMbid is not null).GroupBy(a => a.ArtistMbid!, StringComparer.OrdinalIgnoreCase).ToDictionary(g => g.Key, g => g.Count(), StringComparer.OrdinalIgnoreCase);
        return played.Concat(artists.Where(a => !played.Contains(a)).OrderByDescending(a => counts.GetValueOrDefault(a.Mbid)).ThenBy(a => a.Name, StringComparer.OrdinalIgnoreCase))
            .Take(limit).ToList();
    }

    /* Release groups for artists, fetching what isn't cached while there's time left (MusicBrainz allows one call a
       second); the rest come from the background warm-up. */
    private async Task<List<(ServerArtist Artist, List<ReleaseGroup> Groups)>> GroupsFor(IEnumerable<ServerArtist> artists, TimeSpan budget, CancellationToken ct)
    {
        var watch = Stopwatch.StartNew();
        var list = new List<(ServerArtist, List<ReleaseGroup>)>();
        foreach (var a in artists)
        {
            var groups = _mb.CachedReleaseGroups(a.Mbid);
            if (groups is null && watch.Elapsed < budget)
            {
                try
                {
                    groups = await _mb.ReleaseGroupsAsync(a.Mbid, ct).ConfigureAwait(false);
                }
                catch (MediaServiceException ex)
                {
                    _logger.LogDebug("MusicBrainz didn't list {Artist}: {Message}", a.Name, ex.Message);
                }
            }

            if (groups is not null)
            {
                list.Add((a, groups));
            }
        }

        return list;
    }

    /// <summary>Fetches release groups for the server's artists ahead of time, so the Music rows open quickly.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>A task.</returns>
    public async Task WarmMusicAsync(CancellationToken ct)
    {
        var (_, artists) = _library.Music();
        foreach (var a in artists.Take(80))
        {
            if (ct.IsCancellationRequested)
            {
                return;
            }

            if (_mb.CachedReleaseGroups(a.Mbid) is null)
            {
                try
                {
                    await _mb.ReleaseGroupsAsync(a.Mbid, ct).ConfigureAwait(false);
                }
                catch (MediaServiceException)
                {
                    return;
                }
            }
        }
    }

    /// <summary>A row on the Music requests page.</summary>
    /// <param name="user">The person.</param>
    /// <param name="row">new, missing, trending or soon.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Releases.</returns>
    public async Task<MusicRowDto> MusicRowAsync(User user, string row, CancellationToken ct)
    {
        var today = DateTime.UtcNow.Date;
        var list = new List<MusicReleaseDto>();
        string? source = null;
        switch (row)
        {
            case "new":
            {
                foreach (var (artist, groups) in await GroupsFor(ArtistsFor(user, 12), TimeSpan.FromSeconds(8), ct).ConfigureAwait(false))
                {
                    foreach (var g in groups.Where(Studio))
                    {
                        if (Day(g.Date) is not { } d || d > today || d < today.AddDays(-400))
                        {
                            continue;
                        }

                        list.Add(Release(g, artist.Name, artist.Mbid));
                    }
                }

                list = list.OrderByDescending(r => r.Date, StringComparer.Ordinal).Take(24).ToList();
                break;
            }

            case "missing":
            {
                foreach (var (artist, groups) in await GroupsFor(ArtistsFor(user, 16), TimeSpan.FromSeconds(8), ct).ConfigureAwait(false))
                {
                    var albums = groups.Where(g => Studio(g) && g.Type is "album" or "ep" && Day(g.Date) is { } d && d <= today).ToList();
                    var mine = _library.AlbumsBy(artist.Mbid, artist.Name);
                    var have = albums.Count(g => _library.FindAlbum(g.Mbid, g.Title, artist.Name) is not null);
                    if (have == 0 && mine.Count == 0)
                    {
                        continue;
                    }

                    foreach (var g in albums.OrderByDescending(g => g.Date, StringComparer.Ordinal).Where(g => _library.FindAlbum(g.Mbid, g.Title, artist.Name) is null).Take(3))
                    {
                        var r = Release(g, artist.Name, artist.Mbid);
                        r.Have = Math.Max(have, Math.Min(mine.Count, albums.Count - 1));
                        r.Of = albums.Count;
                        list.Add(r);
                    }
                }

                // One per artist first, so the row isn't one artist's back catalogue.
                list = list.Select((r, i) => (r, i)).GroupBy(x => x.r.ArtistMbid).SelectMany(g => g.Select((x, n) => (x.r, rank: n, x.i)))
                    .OrderBy(x => x.rank).ThenBy(x => x.i).Select(x => x.r).Take(24).ToList();
                break;
            }

            case "trending":
                source = "ListenBrainz";
                foreach (var t in await _mb.TrendingAsync(ct).ConfigureAwait(false))
                {
                    list.Add(new MusicReleaseDto { Mbid = t.Mbid, Title = t.Title, Artist = t.Artist, ArtistMbid = t.ArtistMbid, CoverUrl = t.Cover, Type = "album" });
                }

                break;

            case "soon":
                source = "Lidarr";
                if (LidarrClient.IsConfigured)
                {
                    foreach (var a in await _lidarr.CalendarAsync(ct).ConfigureAwait(false))
                    {
                        var id = J.Str(a?["foreignAlbumId"]);
                        var date = J.Date(a?["releaseDate"]);
                        if (id is null || date is null || date.Value.Date < today)
                        {
                            continue;
                        }

                        list.Add(new MusicReleaseDto
                        {
                            Mbid = id,
                            Title = J.Str(a?["title"]) ?? "?",
                            Artist = J.Str(a?["artist"]?["artistName"]),
                            ArtistMbid = J.Str(a?["artist"]?["foreignArtistId"]),
                            Type = TypeOf(J.Str(a?["albumType"])),
                            Date = date.Value.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                            CoverUrl = LidarrClient.Image(a, "cover") ?? MusicBrainzClient.Cover(id),
                            Upcoming = true
                        });
                    }
                }

                list = list.OrderBy(r => r.Date, StringComparer.Ordinal).Take(30).ToList();
                break;
            default:
                throw new MediaServiceException("Seedy", "Unknown row.", HttpStatusCode.NotFound);
        }

        list = list.GroupBy(r => r.Mbid, StringComparer.OrdinalIgnoreCase).Select(g => g.First()).ToList();
        Mark(list, user);
        return new MusicRowDto { Results = list, Source = source };
    }

    private static string TypeOf(string? lidarrType) => (lidarrType ?? "album").ToLowerInvariant() switch
    {
        "ep" => "ep",
        "single" => "single",
        _ => "album"
    };

    private static MusicReleaseDto Release(ReleaseGroup g, string? artist, string? artistMbid) => new()
    {
        Mbid = g.Mbid,
        Title = g.Title,
        Artist = artist,
        ArtistMbid = artistMbid,
        Type = g.Type,
        Date = g.Date,
        CoverUrl = MusicBrainzClient.Cover(g.Mbid),
        Upcoming = Day(g.Date) is { } d && d > DateTime.UtcNow.Date,
        Secondary = g.Secondary.ToList()
    };

    /* On the server, asked for, waiting for approval or downloading. */
    private void Mark(IEnumerable<MusicReleaseDto> releases, User viewer)
    {
        var active = _requests.Active("music");
        var showNames = ConfigMapper.Current.MediaShowRequesters || viewer.IsAdmin();
        var uid = viewer.Id.ToString("N");
        foreach (var r in releases)
        {
            if (_library.FindAlbum(r.Mbid, r.Title, r.Artist) is { } onServer)
            {
                r.Status = "server";
                r.ItemId = onServer.Id;
                continue;
            }

            var hit = active.FirstOrDefault(x => x.Data.Albums.Any(a => string.Equals(a.Mbid, r.Mbid, StringComparison.OrdinalIgnoreCase)))
                is { Request: not null } byAlbum ? byAlbum
                : active.FirstOrDefault(x => x.Request.Mode == "artist" && r.ArtistMbid is not null && string.Equals(x.Request.ForeignId, r.ArtistMbid, StringComparison.OrdinalIgnoreCase) && !r.Upcoming);
            if (hit.Request is { } q)
            {
                r.Status = q.Status switch { "pending" => "waiting", "downloading" => "downloading", _ => "requested" };
                r.RequestedBy = q.UserId == uid ? null : showNames ? q.UserName : null;
            }
        }
    }

    /// <summary>Searches for artists and albums: Lidarr's metadata when it's connected, else MusicBrainz.</summary>
    /// <param name="user">The person.</param>
    /// <param name="query">Text.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Artists and albums.</returns>
    public async Task<MusicSearchDto> MusicSearchAsync(User user, string query, CancellationToken ct)
    {
        var dto = new MusicSearchDto();
        if (LidarrClient.IsConfigured)
        {
            try
            {
                var artists = _lidarr.SearchArtistsAsync(query, ct);
                var albums = _lidarr.SearchAlbumsAsync(query, ct);
                foreach (var a in (await artists.ConfigureAwait(false)).Take(8))
                {
                    var mbid = J.Str(a?["foreignArtistId"]);
                    if (mbid is null)
                    {
                        continue;
                    }

                    dto.Artists.Add(new MusicArtistRefDto
                    {
                        Mbid = mbid,
                        Name = J.Str(a?["artistName"]) ?? "?",
                        Disambiguation = J.Str(a?["disambiguation"]),
                        Type = J.Str(a?["artistType"]),
                        ImageUrl = LidarrClient.Image(a, "poster") ?? LidarrClient.Image(a, "fanart"),
                        ItemId = _library.FindArtist(mbid, null)?.Id
                    });
                }

                foreach (var a in (await albums.ConfigureAwait(false)).Take(16))
                {
                    var id = J.Str(a?["foreignAlbumId"]);
                    if (id is null)
                    {
                        continue;
                    }

                    dto.Albums.Add(new MusicReleaseDto
                    {
                        Mbid = id,
                        Title = J.Str(a?["title"]) ?? "?",
                        Artist = J.Str(a?["artist"]?["artistName"]),
                        ArtistMbid = J.Str(a?["artist"]?["foreignArtistId"]) ?? J.Str(a?["artistId"]),
                        Type = TypeOf(J.Str(a?["albumType"])),
                        Date = J.Date(a?["releaseDate"])?.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture),
                        CoverUrl = LidarrClient.Image(a, "cover") ?? MusicBrainzClient.Cover(id),
                        Upcoming = J.Date(a?["releaseDate"]) is { } d && d > DateTime.UtcNow
                    });
                }

                Mark(dto.Albums, user);
                return dto;
            }
            catch (MediaServiceException ex)
            {
                _logger.LogDebug("Lidarr search failed, using MusicBrainz: {Message}", ex.Message);
                dto = new MusicSearchDto();
            }
        }

        foreach (var a in await _mb.SearchArtistsAsync(query, ct).ConfigureAwait(false))
        {
            dto.Artists.Add(new MusicArtistRefDto { Mbid = a.Mbid, Name = a.Name, Disambiguation = a.Disambiguation, Type = a.Type, Area = a.Area, ItemId = _library.FindArtist(a.Mbid, null)?.Id });
        }

        foreach (var (g, artist, artistMbid) in await _mb.SearchReleaseGroupsAsync(query, ct).ConfigureAwait(false))
        {
            dto.Albums.Add(Release(g, artist, artistMbid));
        }

        Mark(dto.Albums, user);
        return dto;
    }

    /// <summary>An artist for the request window: where they're from, their albums, EPs and singles with track counts
    /// and lengths, and which are on the server or asked for.</summary>
    /// <param name="user">The person.</param>
    /// <param name="mbid">MusicBrainz artist id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The artist.</returns>
    public async Task<MusicArtistDto> MusicArtistAsync(User user, string mbid, CancellationToken ct)
    {
        var info = await _mb.ArtistAsync(mbid, ct).ConfigureAwait(false)
            ?? throw new MediaServiceException("MusicBrainz", "MusicBrainz doesn't know this artist.", HttpStatusCode.NotFound);
        var groups = await _mb.ReleaseGroupsAsync(mbid, ct).ConfigureAwait(false);
        Dictionary<string, ReleaseLength> lengths;
        try
        {
            lengths = await _mb.LengthsAsync(mbid, ct).ConfigureAwait(false);
        }
        catch (MediaServiceException)
        {
            lengths = [];
        }

        var server = _library.FindArtist(mbid, info.Name);
        var dto = new MusicArtistDto
        {
            Artist = new MusicArtistRefDto { Mbid = mbid, Name = info.Name, Area = info.Area, Type = info.Type, Disambiguation = info.Disambiguation, ItemId = server?.Id }
        };
        foreach (var g in groups.Where(Kept).OrderByDescending(g => g.Date ?? string.Empty, StringComparer.Ordinal))
        {
            var r = Release(g, info.Name, mbid);
            if (lengths.TryGetValue(g.Mbid, out var len))
            {
                r.Tracks = len.Tracks;
                r.LengthMs = len.LengthMs;
            }

            dto.Releases.Add(r);
        }

        Mark(dto.Releases, user);
        dto.AlbumsOnServer = dto.Releases.Count(r => r.Status == "server" && r.Type == "album");
        if (LidarrClient.IsConfigured)
        {
            try
            {
                var artist = await _lidarr.ArtistAsync(mbid, ct).ConfigureAwait(false);
                dto.Watching = artist is not null && J.Bool(artist["monitored"]) && J.Str(artist["monitorNewItems"]) == "all";
                dto.Artist.ImageUrl = LidarrClient.Image(artist, "poster");
            }
            catch (MediaServiceException)
            {
                dto.Watching = false;
            }
        }

        return dto;
    }

    // ── Books ──

    private static BookCardDto FromShelfarr(JsonNode? r)
    {
        var kind = J.Str(r?["content_kind"]);
        var position = J.Double(r?["series_position"]);
        var release = J.Str(r?["release_date"]);
        var year = J.Int(r?["year"]);
        return new BookCardDto
        {
            WorkId = J.Str(r?["work_id"]) ?? string.Empty,
            Title = J.Str(r?["title"]) ?? "?",
            Author = J.Str(r?["author"]),
            Year = year,
            ReleaseDate = release,
            CoverUrl = J.Str(r?["cover_url"]),
            Series = J.Str(r?["series_name"]),
            SeriesIndex = position,
            Content = kind is "graphic" or "comic" or "manga" ? "comic" : "book",
            Formats = kind is "graphic" or "comic" or "manga" ? ["comic"]
                : new[] { J.Bool(r?["has_ebook"]) || r?["has_ebook"] is null ? "ebook" : null, J.Bool(r?["has_audiobook"]) ? "audiobook" : null }.OfType<string>().ToList(),
            Upcoming = (Day(release) is { } d && d > DateTime.UtcNow) || year > DateTime.UtcNow.Year
        };
    }

    /// <summary>Searches for books: Shelfarr's metadata providers when it's connected, else Open Library.</summary>
    /// <param name="user">The person.</param>
    /// <param name="query">Text.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Books.</returns>
    public async Task<BookRowDto> BookSearchAsync(User user, string query, CancellationToken ct)
    {
        var list = (await SearchBooks(query, ct).ConfigureAwait(false)).GroupBy(b => b.WorkId, StringComparer.OrdinalIgnoreCase).Select(g => g.First()).ToList();
        MarkBooks(list, user);
        return new BookRowDto { Results = list };
    }

    private async Task<List<BookCardDto>> SearchBooks(string query, CancellationToken ct)
    {
        if (ShelfarrClient.HasCredentials && ConfigMapper.Current.ShelfarrEnabled)
        {
            try
            {
                return (await _shelfarr.SearchAsync(query, ct).ConfigureAwait(false)).Select(FromShelfarr).Where(b => b.WorkId.Length > 0).ToList();
            }
            catch (MediaServiceException ex)
            {
                _logger.LogDebug("Shelfarr search failed, using Open Library: {Message}", ex.Message);
            }
        }

        var books = await _openLibrary.SearchAsync(query, ct).ConfigureAwait(false);
        foreach (var b in books)
        {
            b.Formats = ["ebook", "audiobook"];
        }

        return books;
    }

    /// <summary>A row on the Books requests page.</summary>
    /// <param name="user">The person.</param>
    /// <param name="row">next, audiobooks or trending.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Books.</returns>
    public async Task<BookRowDto> BookRowAsync(User user, string row, CancellationToken ct)
    {
        var list = new List<BookCardDto>();
        string? source = null;
        switch (row)
        {
            case "next":
                list = await NextInSeriesAsync(user, ct).ConfigureAwait(false);
                break;
            case "audiobooks":
                list = await AudiobooksOfReadAsync(user, ct).ConfigureAwait(false);
                break;
            case "trending":
                source = "Open Library";
                list = await _openLibrary.TrendingAsync(ct).ConfigureAwait(false);
                foreach (var b in list)
                {
                    b.Formats = ["ebook", "audiobook"];
                }

                break;
            default:
                throw new MediaServiceException("Seedy", "Unknown row.", HttpStatusCode.NotFound);
        }

        list = list.Where(b => b.WorkId.Length > 0).GroupBy(b => b.WorkId, StringComparer.OrdinalIgnoreCase).Select(g => g.First()).ToList();
        MarkBooks(list, user);
        return new BookRowDto { Results = list, Source = source };
    }

    /* Books the person has opened in Seedy's reader, as library items. */
    private List<(ReadingRecord Reading, BaseItem Item)> Read(User user)
        => _db.ListReading(user.Id.ToString("N"), true, 200)
            .Select(r => (r, item: Guid.TryParse(r.ItemId, out var id) ? _items.GetItemById(id) : null))
            .Where(x => x.item is not null)
            .Select(x => (x.r, x.item!))
            .ToList();

    private async Task<List<BookCardDto>> NextInSeriesAsync(User user, CancellationToken ct)
    {
        var series = Read(user)
            .Where(x => x.Item is Book b && !string.IsNullOrWhiteSpace(b.SeriesName) && x.Item.IndexNumber is not null)
            .GroupBy(x => ((Book)x.Item).SeriesName!, StringComparer.OrdinalIgnoreCase)
            .Select(g => (Series: g.Key, Max: g.Max(x => x.Item.IndexNumber!.Value), Min: g.Min(x => x.Item.IndexNumber!.Value), Comic: g.Any(x => MediaLibrary.FormatOf(x.Item) == "comic"), At: g.Max(x => x.Reading.UpdatedAt), Last: g.OrderByDescending(x => x.Item.IndexNumber).First().Item))
            .OrderByDescending(s => s.At)
            .Take(10)
            .ToList();
        var list = new List<BookCardDto>();
        foreach (var s in series)
        {
            // The next volume the server doesn't have yet.
            var next = s.Max + 1;
            while (_library.FindBooks(null, s.Series, next).Count > 0 && next < s.Max + 20)
            {
                next++;
            }

            List<BookCardDto> found;
            try
            {
                found = await SearchBooks(s.Series, ct).ConfigureAwait(false);
            }
            catch (MediaServiceException)
            {
                continue;
            }

            var pick = found.FirstOrDefault(b => MediaLibrary.Fold(b.Series) == MediaLibrary.Fold(s.Series) && b.SeriesIndex is { } i && Math.Abs(i - next) < 0.01)
                ?? found.FirstOrDefault(b => b.Series is null && MediaLibrary.Fold(b.Title) == MediaLibrary.Fold(s.Series + " " + next.ToString(CultureInfo.InvariantCulture)));

            // Metadata providers often don't know comic issues or small series: name the next volume after the last
            // one ("Kestrel No. 5" → "Kestrel No. 6"). Librarr and Shelfarr search by title and author.
            if (pick is null && s.Last is { } last && (s.Comic || last.Name.TrimEnd().EndsWith(s.Max.ToString(CultureInfo.InvariantCulture), StringComparison.Ordinal)))
            {
                var name = NextName(last.Name, s.Max, next, s.Series);
                pick = new BookCardDto
                {
                    WorkId = "seedy:" + MediaLibrary.Fold(s.Series) + ":" + next.ToString(CultureInfo.InvariantCulture),
                    Title = name,
                    Author = _items.GetPeople(last).FirstOrDefault(p => p.Type == Jellyfin.Data.Enums.PersonKind.Author)?.Name,
                    Formats = s.Comic ? ["comic"] : ["ebook", "audiobook"]
                };
            }

            if (pick is null)
            {
                continue;
            }

            pick.Series ??= s.Series;
            pick.SeriesIndex ??= next;
            pick.ReadFrom = s.Min;
            pick.ReadTo = s.Max;
            if (s.Comic)
            {
                pick.Content = "comic";
                pick.Formats = ["comic"];
            }

            list.Add(pick);
        }

        return list;
    }

    /* "Kestrel No. 5" → "Kestrel No. 6", "Ember Run Vol. 2" → "Ember Run Vol. 3", else "Series 7". */
    private static string NextName(string last, int lastIndex, int next, string series)
    {
        var number = lastIndex.ToString(CultureInfo.InvariantCulture);
        var at = last.LastIndexOf(number, StringComparison.Ordinal);
        if (at >= 0 && at + number.Length == last.TrimEnd().Length)
        {
            return last[..at] + next.ToString(CultureInfo.InvariantCulture);
        }

        return series + " " + next.ToString(CultureInfo.InvariantCulture);
    }

    private async Task<List<BookCardDto>> AudiobooksOfReadAsync(User user, CancellationToken ct)
    {
        var audio = _library.Books().Where(b => b.Format == "audiobook").Select(b => MediaLibrary.Fold(b.Name)).ToHashSet();
        var finished = Read(user)
            .Where(x => x.Reading.Finished && MediaLibrary.FormatOf(x.Item) == "ebook" && !audio.Contains(MediaLibrary.Fold(x.Item.Name)))
            .OrderByDescending(x => x.Reading.UpdatedAt)
            .Take(12)
            .ToList();
        var list = new List<BookCardDto>();
        foreach (var (_, item) in finished)
        {
            var author = _items.GetPeople(item).FirstOrDefault(p => p.Type == Jellyfin.Data.Enums.PersonKind.Author)?.Name;
            BookCardDto? pick = null;
            try
            {
                var found = await SearchBooks(author is null ? item.Name : item.Name + " " + author, ct).ConfigureAwait(false);
                pick = found.FirstOrDefault(b => MediaLibrary.Fold(b.Title) == MediaLibrary.Fold(item.Name));
            }
            catch (MediaServiceException)
            {
                pick = null;
            }

            if (pick is not null && pick.Formats.Count > 0 && !pick.Formats.Contains("audiobook") && ShelfarrClient.HasCredentials)
            {
                // Shelfarr knows there's no audiobook of it.
                continue;
            }

            pick ??= new BookCardDto { WorkId = "seedy:" + item.Id.ToString("N"), Title = item.Name, Author = author, Year = item.ProductionYear };
            pick.Formats = ["audiobook"];
            list.Add(pick);
        }

        return list;
    }

    /// <summary>A book for the request window: the card plus its description and where the person is in its series.</summary>
    /// <param name="user">The person.</param>
    /// <param name="card">The book as the page had it.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The book.</returns>
    public async Task<BookCardDto> BookDetailsAsync(User user, BookCardDto card, CancellationToken ct)
    {
        try
        {
            card.Description ??= await _openLibrary.DescriptionAsync(card.WorkId, ct).ConfigureAwait(false);
            if (card.Description is null || card.Pages is null)
            {
                var match = (await _openLibrary.SearchAsync(card.Title + (card.Author is null ? string.Empty : " " + card.Author), ct, 5).ConfigureAwait(false))
                    .FirstOrDefault(b => MediaLibrary.Fold(b.Title) == MediaLibrary.Fold(card.Title));
                if (match is not null)
                {
                    card.Pages ??= match.Pages;
                    card.Year ??= match.Year;
                    card.Languages = card.Languages.Count > 0 ? card.Languages : match.Languages;
                    card.Description ??= await _openLibrary.DescriptionAsync(match.WorkId, ct).ConfigureAwait(false);
                }
            }
        }
        catch (MediaServiceException ex)
        {
            _logger.LogDebug("Open Library didn't describe {Title}: {Message}", card.Title, ex.Message);
        }

        if (card.Series is not null && card.ReadTo is null)
        {
            var read = Read(user).Where(x => x.Item is Book b && MediaLibrary.Fold(b.SeriesName) == MediaLibrary.Fold(card.Series) && x.Item.IndexNumber is not null).ToList();
            if (read.Count > 0)
            {
                card.ReadFrom = read.Min(x => x.Item.IndexNumber!.Value);
                card.ReadTo = read.Max(x => x.Item.IndexNumber!.Value);
            }
        }

        MarkBooks([card], user);
        return card;
    }

    private void MarkBooks(IEnumerable<BookCardDto> books, User viewer)
    {
        var routable = ConfigMapper.BookFormats(ConfigMapper.Current);
        var active = _requests.Active("book");
        var showNames = ConfigMapper.Current.MediaShowRequesters || viewer.IsAdmin();
        var uid = viewer.Id.ToString("N");
        foreach (var b in books)
        {
            b.Formats = b.Formats.Where(routable.Contains).ToList();
            var onServer = _library.FindBooks(b.Title, b.Series, b.SeriesIndex);
            b.OnServer = onServer.Select(x => x.Format).Distinct().ToList();
            var hit = active.FirstOrDefault(x => string.Equals(x.Request.ForeignId, b.WorkId, StringComparison.OrdinalIgnoreCase)
                || MediaLibrary.Fold(x.Request.Title) == MediaLibrary.Fold(b.Title));
            if (onServer.Count > 0 && b.Formats.All(f => b.OnServer.Contains(f)))
            {
                b.Status = "server";
                b.ItemId = onServer[0].Id;
            }
            else if (hit.Request is { } q)
            {
                b.Status = q.Status switch { "pending" => "waiting", "downloading" => "downloading", _ => "requested" };
                b.RequestedBy = q.UserId == uid ? null : showNames ? q.UserName : null;
            }
            else if (onServer.Count > 0 && b.Formats.Count == 0)
            {
                b.Status = "server";
                b.ItemId = onServer[0].Id;
            }
        }
    }
}
