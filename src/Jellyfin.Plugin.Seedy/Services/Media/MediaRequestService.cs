using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Plugin.Seedy.Api;
using Jellyfin.Plugin.Seedy.Data;
using MediaBrowser.Controller.Library;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services.Media;

/// <summary>What Seedy keeps about a request besides its columns.</summary>
public sealed class MediaRequestData
{
    /// <summary>Gets or sets the albums asked for (albums mode).</summary>
    public List<MediaAlbumRefDto> Albums { get; set; } = [];

    /// <summary>Gets or sets a value indicating whether Lidarr should keep getting the artist's new albums.</summary>
    public bool KeepNew { get; set; }

    /// <summary>Gets or sets the book formats asked for.</summary>
    public List<string> Formats { get; set; } = [];

    /// <summary>Gets or sets book or comic.</summary>
    public string? Content { get; set; }

    /// <summary>Gets or sets the series.</summary>
    public string? Series { get; set; }

    /// <summary>Gets or sets the place in the series.</summary>
    public double? SeriesIndex { get; set; }

    /// <summary>Gets or sets the year.</summary>
    public int? Year { get; set; }

    /// <summary>Gets or sets the language asked for.</summary>
    public string? Language { get; set; }

    /// <summary>Gets or sets Lidarr's artist id once added.</summary>
    public int? LidarrArtistId { get; set; }

    /// <summary>Gets or sets Lidarr's album ids by release group id.</summary>
    public Dictionary<string, int> LidarrAlbums { get; set; } = new(StringComparer.OrdinalIgnoreCase);

    /// <summary>Gets or sets the quality profile accepted instead of the admin's, if any.</summary>
    public int? AcceptedQualityId { get; set; }

    /// <summary>Gets or sets when the app said it was done, before the item showed up on the server.</summary>
    public DateTime? FinishedAt { get; set; }

    /// <summary>Gets or sets a value indicating whether the library was scanned for it.</summary>
    public bool Scanned { get; set; }

    /// <summary>Gets or sets a value indicating whether Seedy leaves it as it is (demo data on the dev stack).</summary>
    public bool Frozen { get; set; }
}

/// <summary>Where a request is in one app (one per book format; one for Lidarr).</summary>
public sealed class MediaRequestPart
{
    /// <summary>Gets or sets ebook, audiobook, comic or music.</summary>
    public string Format { get; set; } = "music";

    /// <summary>Gets or sets the app.</summary>
    public string Service { get; set; } = string.Empty;

    /// <summary>Gets or sets the app's id for it.</summary>
    public string? ExternalId { get; set; }

    /// <summary>Gets or sets approved, searching, downloading, ready, failed or cancelled.</summary>
    public string Status { get; set; } = "approved";

    /// <summary>Gets or sets the download progress.</summary>
    public double? Progress { get; set; }

    /// <summary>Gets or sets a value indicating whether the app flags it.</summary>
    public bool Attention { get; set; }

    /// <summary>Gets or sets the app's own words about it.</summary>
    public string? Message { get; set; }

    /// <summary>Gets or sets the Jellyfin item it became.</summary>
    public string? ItemId { get; set; }

    /// <summary>Gets or sets releases the app found.</summary>
    public int? Found { get; set; }
}

/// <summary>
/// Seedy's own music and book requests: limits and auto-approval per profile, approvals by admins, sending approved
/// requests to Lidarr, Shelfarr, Librarr or a Readarr-API app, and following them there until the files are on the
/// server (scanning the matching library when the admin wants it).
/// </summary>
public sealed class MediaRequestService
{
    private static readonly JsonSerializerOptions Json = new() { PropertyNameCaseInsensitive = true };
    private static readonly string[] Open = ["pending", "approved", "searching", "downloading", "failed"];
    private static readonly TimeSpan SearchAgain = TimeSpan.FromHours(6);
    private static readonly TimeSpan NothingFound = TimeSpan.FromDays(2);

    private readonly SeedyDatabase _db;
    private readonly IUserManager _users;
    private readonly LidarrClient _lidarr;
    private readonly ShelfarrClient _shelfarr;
    private readonly LibrarrClient _librarr;
    private readonly ReadarrClient _readarr;
    private readonly MediaLibrary _library;
    private readonly ILogger<MediaRequestService> _logger;
    private readonly SemaphoreSlim _syncGate = new(1, 1);

    /// <summary>
    /// Initializes a new instance of the <see cref="MediaRequestService"/> class.
    /// </summary>
    /// <param name="db">Database.</param>
    /// <param name="users">User manager.</param>
    /// <param name="lidarr">Lidarr.</param>
    /// <param name="shelfarr">Shelfarr.</param>
    /// <param name="librarr">Librarr.</param>
    /// <param name="readarr">Readarr-API app.</param>
    /// <param name="library">What's on the server.</param>
    /// <param name="logger">Logger.</param>
    public MediaRequestService(SeedyDatabase db, IUserManager users, LidarrClient lidarr, ShelfarrClient shelfarr, LibrarrClient librarr, ReadarrClient readarr, MediaLibrary library, ILogger<MediaRequestService> logger)
    {
        _db = db;
        _users = users;
        _lidarr = lidarr;
        _shelfarr = shelfarr;
        _librarr = librarr;
        _readarr = readarr;
        _library = library;
        _logger = logger;
    }

    /// <summary>Gets whether music requests work.</summary>
    public static bool MusicEnabled => LidarrClient.IsConfigured;

    /// <summary>Gets whether book requests work.</summary>
    public static bool BooksEnabled => ConfigMapper.BookFormats(ConfigMapper.Current).Count > 0;

    /// <summary>The app's name as people know it.</summary>
    /// <param name="service">lidarr, shelfarr, librarr or readarr.</param>
    /// <returns>Name.</returns>
    public static string ServiceName(string service) => service switch
    {
        "lidarr" => "Lidarr",
        "shelfarr" => "Shelfarr",
        "librarr" => "Librarr",
        "readarr" => "Readarr",
        _ => service
    };

    private static MediaRequestData DataOf(MediaRequestRecord q)
    {
        try
        {
            return JsonSerializer.Deserialize<MediaRequestData>(q.Data, Json) ?? new MediaRequestData();
        }
        catch (JsonException)
        {
            return new MediaRequestData();
        }
    }

    private static List<MediaRequestPart> PartsOf(MediaRequestRecord q)
    {
        try
        {
            return JsonSerializer.Deserialize<List<MediaRequestPart>>(q.Parts, Json) ?? [];
        }
        catch (JsonException)
        {
            return [];
        }
    }

    private static MediaNoteDto? NoteOf(MediaRequestRecord q)
    {
        if (string.IsNullOrEmpty(q.Note))
        {
            return null;
        }

        try
        {
            return JsonSerializer.Deserialize<MediaNoteDto>(q.Note, Json);
        }
        catch (JsonException)
        {
            return null;
        }
    }

    private static void Store(MediaRequestRecord q, MediaRequestData data, List<MediaRequestPart> parts, MediaNoteDto? note)
    {
        q.Data = JsonSerializer.Serialize(data);
        q.Parts = JsonSerializer.Serialize(parts);
        q.Note = note is null ? null : JsonSerializer.Serialize(note);
    }

    // ── Limits ──

    private static (int Limit, string Period) LimitOf(MediaRequestProfileView p, string kind)
        => kind == "music" ? (p.MusicPerWeek, "week") : (p.BooksPerMonth, "month");

    private sealed record MediaRequestProfileView(int MusicPerWeek, int BooksPerMonth, bool AutoApprove);

    private static MediaRequestProfileView ProfileOf(User user)
    {
        var p = ConfigMapper.MediaProfile(ConfigMapper.Current, user);
        return new MediaRequestProfileView(p.MusicPerWeek, p.BooksPerMonth, p.AutoApprove && user.MaxParentalRatingScore is null || p.AutoApprove && user.IsAdmin());
    }

    private int Used(string userId, string kind)
    {
        var since = DateTime.UtcNow.AddDays(kind == "music" ? -7 : -30);
        return _db.ListMediaRequests(userId, kind, null, 1000)
            .Where(q => q.CreatedAt >= since && q.Status is not ("declined" or "cancelled"))
            .Sum(q => q.Units);
    }

    /// <summary>What the request window offers the person.</summary>
    /// <param name="user">The person.</param>
    /// <param name="kind">music or book.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Options.</returns>
    public async Task<MediaOptionsDto> OptionsAsync(User user, string kind, CancellationToken ct)
    {
        var c = ConfigMapper.Current;
        var p = ProfileOf(user);
        var (limit, period) = LimitOf(p, kind);
        var dto = new MediaOptionsDto
        {
            Kind = kind,
            Limit = limit,
            Period = period,
            Used = Used(user.Id.ToString("N"), kind),
            AutoApprove = p.AutoApprove,
            ScanOnReady = c.MediaScanOnReady,
            CanRequest = limit != 0 && (kind == "music" ? MusicEnabled : BooksEnabled)
        };
        if (kind == "music")
        {
            try
            {
                dto.Quality = (await _lidarr.QualityAsync(ct).ConfigureAwait(false))?.Name;
            }
            catch (MediaServiceException)
            {
                dto.Quality = null;
            }

            dto.Formats.Add(new MediaFormatOptionDto { Format = "music", Service = "lidarr", Library = MusicLibrary()?.Name });
        }
        else
        {
            foreach (var f in ConfigMapper.BookFormats(c))
            {
                dto.Formats.Add(new MediaFormatOptionDto { Format = f, Service = ConfigMapper.BookRoute(c, f)!, Library = _library.LibraryForFormat(f)?.Name });
            }
        }

        return dto;
    }

    private (string Id, string Name, string Location)? MusicLibrary()
        => _library.LibraryForPath(ConfigMapper.Current.LidarrRootFolder) ?? _library.LibraryForFormat("music");

    // ── Creating and deciding ──

    /// <summary>Asks for music or a book as the person. Within their limit it's stored, and sent at once when they
    /// don't need approval.</summary>
    /// <param name="user">The person.</param>
    /// <param name="body">What to request.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The request.</returns>
    public async Task<MediaRequestDto> CreateAsync(User user, MediaCreateBody body, CancellationToken ct)
    {
        var c = ConfigMapper.Current;
        var kind = body.Kind == "book" ? "book" : "music";
        if (kind == "music" && !MusicEnabled)
        {
            throw new MediaServiceException("Seedy", "Music requests aren't set up on this server.", HttpStatusCode.ServiceUnavailable);
        }

        if (kind == "book" && !BooksEnabled)
        {
            throw new MediaServiceException("Seedy", "Book requests aren't set up on this server.", HttpStatusCode.ServiceUnavailable);
        }

        var title = (body.Title ?? string.Empty).Trim();
        var foreign = (body.ForeignId ?? string.Empty).Trim();
        if (title.Length == 0 || foreign.Length == 0)
        {
            throw new MediaServiceException("Seedy", "Unknown title.", HttpStatusCode.BadRequest);
        }

        var data = new MediaRequestData();
        var parts = new List<MediaRequestPart>();
        string mode;
        int units;
        string service;
        if (kind == "music")
        {
            mode = body.Mode is "artist" or "future" ? body.Mode : "albums";
            data.KeepNew = mode != "albums" || body.KeepNew;
            data.Albums = (body.Albums ?? []).Where(a => !string.IsNullOrWhiteSpace(a.Mbid)).GroupBy(a => a.Mbid, StringComparer.OrdinalIgnoreCase).Select(g => g.First()).Take(100).ToList();
            if (mode == "albums" && data.Albums.Count == 0)
            {
                throw new MediaServiceException("Seedy", "Pick at least one album.", HttpStatusCode.BadRequest);
            }

            units = mode switch { "albums" => data.Albums.Count, "artist" => Math.Clamp(body.AlbumCount ?? 1, 1, 200), _ => 0 };
            service = "lidarr";
            parts.Add(new MediaRequestPart { Format = "music", Service = "lidarr", Status = "pending" });
            mode = mode == "albums" && data.Albums.Count > 0 ? "albums" : mode;
        }
        else
        {
            mode = "book";
            data.Content = body.Content == "comic" ? "comic" : "book";
            var wanted = (body.Formats ?? []).Select(f => f == "comic" || data.Content == "comic" ? "comic" : f).Where(f => f is "ebook" or "audiobook" or "comic").Distinct().ToList();
            if (wanted.Count == 0)
            {
                throw new MediaServiceException("Seedy", "Pick a format.", HttpStatusCode.BadRequest);
            }

            foreach (var f in wanted)
            {
                var route = ConfigMapper.BookRoute(c, f) ?? throw new MediaServiceException("Seedy", "That format can't be requested on this server.", HttpStatusCode.BadRequest);
                parts.Add(new MediaRequestPart { Format = f, Service = route, Status = "pending" });
            }

            data.Formats = wanted;
            data.Series = body.Series;
            data.SeriesIndex = body.SeriesIndex;
            data.Year = body.Year;
            data.Language = string.IsNullOrWhiteSpace(body.Language) ? null : body.Language.Trim();
            units = wanted.Count;
            service = parts.Select(p => p.Service).Distinct().Count() == 1 ? parts[0].Service : parts[0].Service;
        }

        // Already asked for and still on its way: don't ask twice.
        var uid = user.Id.ToString("N");
        var dupe = _db.ListMediaRequests(null, kind, Open, 1000).FirstOrDefault(q => string.Equals(q.ForeignId, foreign, StringComparison.OrdinalIgnoreCase) && Same(q, mode, data));
        if (dupe is not null)
        {
            throw new MediaServiceException("Seedy", dupe.UserId == uid ? "You already asked for this." : "Someone already asked for this.", HttpStatusCode.Conflict);
        }

        var profile = ProfileOf(user);
        var (limit, _) = LimitOf(profile, kind);
        if (limit == 0)
        {
            throw new MediaServiceException("Seedy", kind == "music" ? "Music requests are switched off for this profile." : "Book requests are switched off for this profile.", HttpStatusCode.Forbidden);
        }

        if (limit > 0 && Used(uid, kind) + units > limit)
        {
            throw new MediaServiceException("Seedy", kind == "music" ? "That's more albums than you have left this week." : "That's more books than you have left this month.", HttpStatusCode.Forbidden);
        }

        var now = DateTime.UtcNow;
        var q = new MediaRequestRecord
        {
            Id = Guid.NewGuid().ToString("N"),
            UserId = uid,
            UserName = user.Username,
            Kind = kind,
            Service = service,
            Mode = mode,
            ForeignId = foreign,
            Title = title,
            Subtitle = string.IsNullOrWhiteSpace(body.Subtitle) ? null : body.Subtitle.Trim(),
            CoverUrl = body.CoverUrl is { } cover && cover.StartsWith("http", StringComparison.OrdinalIgnoreCase) ? cover : null,
            Units = units,
            Status = "pending",
            CreatedAt = now,
            UpdatedAt = now
        };
        Store(q, data, parts, null);
        _db.SaveMediaRequest(q);
        if (profile.AutoApprove)
        {
            q.DecidedAt = now;
            q.DecidedBy = user.Username;
            await SendAsync(q, ct).ConfigureAwait(false);
        }

        return await DtoAsync(q, user, ct).ConfigureAwait(false);
    }

    private static bool Same(MediaRequestRecord q, string mode, MediaRequestData data)
    {
        if (q.Kind == "book")
        {
            return DataOf(q).Formats.Intersect(data.Formats).Any();
        }

        if (q.Mode != mode)
        {
            return q.Mode == "artist" && mode == "albums";
        }

        return mode != "albums" || DataOf(q).Albums.Select(a => a.Mbid).Intersect(data.Albums.Select(a => a.Mbid), StringComparer.OrdinalIgnoreCase).Any();
    }

    /// <summary>Approves a request (admins) and sends it to its app.</summary>
    /// <param name="admin">The admin.</param>
    /// <param name="id">Request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The request.</returns>
    public async Task<MediaRequestDto> ApproveAsync(User admin, string id, CancellationToken ct)
    {
        var q = Get(id);
        if (q.Status != "pending")
        {
            throw new MediaServiceException("Seedy", "This request isn't waiting for approval.", HttpStatusCode.Conflict);
        }

        q.DecidedAt = DateTime.UtcNow;
        q.DecidedBy = admin.Username;
        await SendAsync(q, ct).ConfigureAwait(false);
        return await DtoAsync(q, admin, ct).ConfigureAwait(false);
    }

    /// <summary>Declines a request (admins).</summary>
    /// <param name="admin">The admin.</param>
    /// <param name="id">Request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The request.</returns>
    public async Task<MediaRequestDto> DeclineAsync(User admin, string id, CancellationToken ct)
    {
        var q = Get(id);
        if (q.Status != "pending")
        {
            throw new MediaServiceException("Seedy", "This request isn't waiting for approval.", HttpStatusCode.Conflict);
        }

        q.Status = "declined";
        q.DecidedAt = DateTime.UtcNow;
        q.DecidedBy = admin.Username;
        q.UpdatedAt = DateTime.UtcNow;
        _db.SaveMediaRequest(q);
        return await DtoAsync(q, admin, ct).ConfigureAwait(false);
    }

    /// <summary>Withdraws the person's own request, or removes any request for admins; requests already sent are
    /// cancelled in their app where it can.</summary>
    /// <param name="user">The person.</param>
    /// <param name="id">Request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>A task.</returns>
    public async Task CancelAsync(User user, string id, CancellationToken ct)
    {
        var q = Get(id);
        var mine = q.UserId == user.Id.ToString("N");
        if (!user.IsAdmin() && !(mine && q.Status is "pending" or "approved" or "searching" or "failed" or "declined"))
        {
            throw new MediaServiceException("Seedy", "Only admins can remove this request.", HttpStatusCode.Forbidden);
        }

        if (q.Status is "approved" or "searching" or "downloading" or "failed")
        {
            foreach (var p in PartsOf(q).Where(p => p.ExternalId is not null && p.Status is not ("ready" or "cancelled")))
            {
                try
                {
                    switch (p.Service)
                    {
                        case "shelfarr":
                            await _shelfarr.CancelAsync(p.ExternalId!, ct).ConfigureAwait(false);
                            break;
                        case "librarr":
                            await _librarr.CancelAsync(p.ExternalId!, ct).ConfigureAwait(false);
                            break;
                    }
                }
                catch (MediaServiceException ex)
                {
                    _logger.LogInformation("Seedy couldn't cancel {Title} in {Service}: {Message}", q.Title, p.Service, ex.Message);
                }
            }

            if (q.Kind == "music" && q.Mode == "albums")
            {
                // Stop watching the albums nobody has yet, so Lidarr doesn't keep looking for them.
                try
                {
                    var data = DataOf(q);
                    var keep = new HashSet<string>(OtherAlbumRequests(q), StringComparer.OrdinalIgnoreCase);
                    await _lidarr.MonitorAlbumsAsync(data.LidarrAlbums.Where(a => !keep.Contains(a.Key)).Select(a => a.Value).ToList(), false, ct).ConfigureAwait(false);
                }
                catch (MediaServiceException ex)
                {
                    _logger.LogInformation("Seedy couldn't unmonitor {Title} in Lidarr: {Message}", q.Title, ex.Message);
                }
            }
        }

        _db.DeleteMediaRequest(q.Id);
    }

    private IEnumerable<string> OtherAlbumRequests(MediaRequestRecord q)
        => _db.ListMediaRequests(null, "music", Open, 1000).Where(o => o.Id != q.Id).SelectMany(o => DataOf(o).Albums.Select(a => a.Mbid));

    /// <summary>Starts a search again now.</summary>
    /// <param name="user">The person (the requester or an admin).</param>
    /// <param name="id">Request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The request.</returns>
    public async Task<MediaRequestDto> SearchNowAsync(User user, string id, CancellationToken ct)
    {
        var q = Get(id);
        if (!user.IsAdmin() && q.UserId != user.Id.ToString("N"))
        {
            throw new MediaServiceException("Seedy", "This isn't your request.", HttpStatusCode.Forbidden);
        }

        if (q.SentAt is null)
        {
            if (q.Status == "pending")
            {
                throw new MediaServiceException("Seedy", "This request is waiting for approval.", HttpStatusCode.Conflict);
            }

            await SendAsync(q, ct).ConfigureAwait(false);
            return await DtoAsync(q, user, ct).ConfigureAwait(false);
        }

        if (q.Kind == "music")
        {
            await SearchMusicAsync(q, DataOf(q), ct).ConfigureAwait(false);
        }
        else
        {
            var parts = PartsOf(q);
            foreach (var p in parts.Where(p => p.ExternalId is not null && p.Status is "searching" or "failed"))
            {
                switch (p.Service)
                {
                    case "shelfarr":
                        await _shelfarr.RetryAsync(p.ExternalId!, ct).ConfigureAwait(false);
                        break;
                    case "librarr":
                        await _librarr.RetryAsync(p.ExternalId!, ct).ConfigureAwait(false);
                        break;
                    case "readarr":
                        await _readarr.RetryAsync(p.ExternalId!, ct).ConfigureAwait(false);
                        break;
                }

                p.Status = "searching";
                p.Attention = false;
                p.Message = null;
            }

            Store(q, DataOf(q), parts, null);
        }

        q.SearchedAt = DateTime.UtcNow;
        q.Status = "searching";
        q.Attention = false;
        q.UpdatedAt = DateTime.UtcNow;
        _db.SaveMediaRequest(q);
        return await DtoAsync(q, user, ct).ConfigureAwait(false);
    }

    /// <summary>Accepts Lidarr's lesser quality profile for the artist and searches again.</summary>
    /// <param name="user">The person (the requester or an admin).</param>
    /// <param name="id">Request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The request.</returns>
    public async Task<MediaRequestDto> AcceptLowerAsync(User user, string id, CancellationToken ct)
    {
        var q = Get(id);
        if (!user.IsAdmin() && q.UserId != user.Id.ToString("N"))
        {
            throw new MediaServiceException("Seedy", "This isn't your request.", HttpStatusCode.Forbidden);
        }

        var data = DataOf(q);
        var artist = q.Kind == "music" ? await _lidarr.ArtistAsync(q.ForeignId, ct).ConfigureAwait(false) : null;
        if (artist is null)
        {
            throw new MediaServiceException("Seedy", "Only music requests in Lidarr can change quality.", HttpStatusCode.BadRequest);
        }

        var lower = await _lidarr.LowerQualityAsync(J.Int(artist["qualityProfileId"]) ?? 0, ct).ConfigureAwait(false)
            ?? throw new MediaServiceException("Lidarr", "Lidarr has no lesser quality profile to fall back on.", HttpStatusCode.BadRequest);
        artist["qualityProfileId"] = lower.Id;
        await _lidarr.UpdateArtistAsync(artist, ct).ConfigureAwait(false);
        data.AcceptedQualityId = lower.Id;
        Store(q, data, PartsOf(q), null);
        await SearchMusicAsync(q, data, ct).ConfigureAwait(false);
        q.Status = "searching";
        q.Attention = false;
        q.SearchedAt = DateTime.UtcNow;
        q.UpdatedAt = DateTime.UtcNow;
        _db.SaveMediaRequest(q);
        return await DtoAsync(q, user, ct).ConfigureAwait(false);
    }

    private MediaRequestRecord Get(string id) => _db.GetMediaRequest(id) ?? throw new MediaServiceException("Seedy", "This request is gone.", HttpStatusCode.NotFound);

    // ── Sending to the apps ──

    /// <summary>Sends an approved request to its app. When the app can't be reached the request stays approved and
    /// the sync tries again.</summary>
    private async Task SendAsync(MediaRequestRecord q, CancellationToken ct)
    {
        var data = DataOf(q);
        var parts = PartsOf(q);
        q.Status = "approved";
        q.UpdatedAt = DateTime.UtcNow;
        MediaNoteDto? note = null;
        try
        {
            if (q.Kind == "music")
            {
                await SendMusicAsync(q, data, ct).ConfigureAwait(false);
                foreach (var p in parts)
                {
                    p.Status = q.Status;
                }
            }
            else
            {
                await SendBooksAsync(q, data, parts, ct).ConfigureAwait(false);
            }

            q.SentAt ??= DateTime.UtcNow;
            q.Attention = false;
        }
        catch (MediaServiceException ex)
        {
            _logger.LogWarning("Seedy couldn't send {Title} to {Service}: {Message}", q.Title, ex.Service, ex.Message);
            note = ex.Unreachable ? new MediaNoteDto { Code = "unreachable", Name = ex.Service } : new MediaNoteDto { Code = "service", Name = ex.Service, Text = ex.Message };
            q.Attention = !ex.Unreachable;
        }

        if (q.Kind == "book" && parts.Any(p => p.ExternalId is not null))
        {
            q.SentAt ??= DateTime.UtcNow;
        }

        if (note is null && q.Kind == "music" && q.Mode == "future")
        {
            note = new MediaNoteDto { Code = "future" };
        }

        Store(q, data, parts, note);
        _db.SaveMediaRequest(q);
    }

    private async Task SendMusicAsync(MediaRequestRecord q, MediaRequestData data, CancellationToken ct)
    {
        var c = ConfigMapper.Current;
        var search = c.LidarrSearchOnApprove;
        var artist = await _lidarr.ArtistAsync(q.ForeignId, ct).ConfigureAwait(false);
        switch (q.Mode)
        {
            case "artist":
            {
                var monitor = c.LidarrWholeArtist == "future" ? "future" : "all";
                if (artist is null)
                {
                    artist = await _lidarr.AddArtistAsync(q.ForeignId, monitor, "all", search && monitor == "all", ct).ConfigureAwait(false);
                }
                else
                {
                    artist["monitored"] = true;
                    artist["monitorNewItems"] = "all";
                    await _lidarr.UpdateArtistAsync(artist, ct).ConfigureAwait(false);
                    if (monitor == "all" && J.Int(artist["id"]) is { } aid)
                    {
                        var albums = await _lidarr.AlbumsAsync(aid, ct, fresh: true).ConfigureAwait(false);
                        await _lidarr.MonitorAlbumsAsync(albums.Where(a => J.Date(a?["releaseDate"]) is not { } d || d <= DateTime.UtcNow).Select(a => J.Int(a?["id"]) ?? 0).Where(i => i > 0).ToList(), true, ct).ConfigureAwait(false);
                        if (search)
                        {
                            await _lidarr.SearchArtistNowAsync(aid, ct).ConfigureAwait(false);
                        }
                    }
                }

                data.LidarrArtistId = J.Int(artist?["id"]);
                if (search && monitor == "all")
                {
                    q.SearchedAt = DateTime.UtcNow;
                    q.Status = "searching";
                }

                break;
            }

            case "future":
                if (artist is null)
                {
                    artist = await _lidarr.AddArtistAsync(q.ForeignId, "future", "all", false, ct).ConfigureAwait(false);
                }
                else
                {
                    artist["monitored"] = true;
                    artist["monitorNewItems"] = "all";
                    await _lidarr.UpdateArtistAsync(artist, ct).ConfigureAwait(false);
                }

                data.LidarrArtistId = J.Int(artist?["id"]);
                break;

            default:
                if (artist is null)
                {
                    // Adding the first album adds the artist without watching the rest; the others follow below or on
                    // the next sync, once Lidarr has read the artist's albums.
                    var first = data.Albums[0];
                    var added = await _lidarr.AddAlbumAsync(first.Mbid, data.KeepNew ? "all" : "none", search, ct).ConfigureAwait(false);
                    if (J.Int(added?["id"]) is { } albumId)
                    {
                        data.LidarrAlbums[first.Mbid] = albumId;
                    }

                    data.LidarrArtistId = J.Int(added?["artistId"]) ?? J.Int(added?["artist"]?["id"]);
                }
                else
                {
                    data.LidarrArtistId = J.Int(artist["id"]);
                    var monitorNew = data.KeepNew ? "all" : J.Str(artist["monitorNewItems"]) ?? "none";
                    if (!J.Bool(artist["monitored"]) || J.Str(artist["monitorNewItems"]) != monitorNew)
                    {
                        artist["monitored"] = true;
                        artist["monitorNewItems"] = monitorNew;
                        await _lidarr.UpdateArtistAsync(artist, ct).ConfigureAwait(false);
                    }
                }

                await ResolveAlbumsAsync(q, data, search, ct).ConfigureAwait(false);
                if (search)
                {
                    q.SearchedAt = DateTime.UtcNow;
                    q.Status = "searching";
                }

                break;
        }
    }

    /* Finds the requested albums in Lidarr, watches them and searches. Albums Lidarr hasn't listed (its metadata
       profile leaves the type out, or it's still reading the artist) are added one by one, or wait for the next sync. */
    private async Task<bool> ResolveAlbumsAsync(MediaRequestRecord q, MediaRequestData data, bool search, CancellationToken ct)
    {
        var missing = data.Albums.Where(a => !data.LidarrAlbums.ContainsKey(a.Mbid)).ToList();
        if (missing.Count == 0 || data.LidarrArtistId is not { } artistId)
        {
            return missing.Count == 0;
        }

        var albums = await _lidarr.AlbumsAsync(artistId, ct, fresh: true).ConfigureAwait(false);
        var found = new List<int>();
        foreach (var a in missing)
        {
            var match = albums.FirstOrDefault(x => string.Equals(J.Str(x?["foreignAlbumId"]), a.Mbid, StringComparison.OrdinalIgnoreCase));
            if (J.Int(match?["id"]) is { } id)
            {
                data.LidarrAlbums[a.Mbid] = id;
                found.Add(id);
                continue;
            }

            // Only add albums once Lidarr has read the artist (it lists some albums), so the add doesn't race its refresh.
            if (albums.Count > 0)
            {
                try
                {
                    var added = await _lidarr.AddAlbumAsync(a.Mbid, data.KeepNew ? "all" : "none", search, ct).ConfigureAwait(false);
                    if (J.Int(added?["id"]) is { } addedId)
                    {
                        data.LidarrAlbums[a.Mbid] = addedId;
                    }
                }
                catch (MediaServiceException ex) when (!ex.Unreachable)
                {
                    _logger.LogInformation("Lidarr didn't add {Album}: {Message}", a.Title, ex.Message);
                }
            }
        }

        if (found.Count > 0)
        {
            await _lidarr.MonitorAlbumsAsync(found, true, ct).ConfigureAwait(false);
            if (search)
            {
                await _lidarr.SearchAlbumsNowAsync(found, ct).ConfigureAwait(false);
            }
        }

        return data.Albums.All(a => data.LidarrAlbums.ContainsKey(a.Mbid));
    }

    private async Task SearchMusicAsync(MediaRequestRecord q, MediaRequestData data, CancellationToken ct)
    {
        if (q.Mode == "albums")
        {
            await _lidarr.SearchAlbumsNowAsync(data.LidarrAlbums.Values.ToList(), ct).ConfigureAwait(false);
        }
        else if ((data.LidarrArtistId ?? J.Int((await _lidarr.ArtistAsync(q.ForeignId, ct).ConfigureAwait(false))?["id"])) is { } artistId)
        {
            await _lidarr.SearchArtistNowAsync(artistId, ct).ConfigureAwait(false);
        }
    }

    private async Task SendBooksAsync(MediaRequestRecord q, MediaRequestData data, List<MediaRequestPart> parts, CancellationToken ct)
    {
        MediaServiceException? first = null;
        foreach (var p in parts.Where(p => p.ExternalId is null))
        {
            var order = new BookOrder(q.ForeignId, q.Title, q.Subtitle, p.Format, q.CoverUrl, data.Year, data.Series, data.SeriesIndex, data.Language, q.UserName);
            try
            {
                p.ExternalId = p.Service switch
                {
                    "shelfarr" => await _shelfarr.CreateAsync(order, ct).ConfigureAwait(false),
                    "librarr" => await _librarr.CreateAsync(order, ct).ConfigureAwait(false),
                    "readarr" => await _readarr.CreateAsync(order, ct).ConfigureAwait(false),
                    _ => throw new MediaServiceException("Seedy", "That format can't be requested on this server.", HttpStatusCode.BadRequest)
                };
                p.Status = p.Service == "librarr" ? "approved" : "searching";
            }
            catch (MediaServiceException ex)
            {
                first ??= ex;
                p.Status = "approved";
            }
        }

        q.Status = Aggregate(parts);
        if (q.Status == "searching")
        {
            q.SearchedAt = DateTime.UtcNow;
        }

        if (first is not null)
        {
            throw first;
        }
    }

    private static string Aggregate(IReadOnlyCollection<MediaRequestPart> parts)
    {
        if (parts.Count == 0)
        {
            return "approved";
        }

        if (parts.All(p => p.Status is "ready" or "cancelled"))
        {
            return parts.Any(p => p.Status == "ready") ? "ready" : "cancelled";
        }

        if (parts.Any(p => p.Status == "failed"))
        {
            return "failed";
        }

        if (parts.Any(p => p.Status == "downloading"))
        {
            return "downloading";
        }

        return parts.Any(p => p.Status == "searching") ? "searching" : "approved";
    }

    // ── Following requests in the apps ──

    /// <summary>Checks every open request in its app and moves it along: sends approved ones that couldn't be sent,
    /// follows searches and downloads, searches again when nothing turned up, and scans the library when files land.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>A task.</returns>
    public async Task SyncAsync(CancellationToken ct)
    {
        if (!await _syncGate.WaitAsync(0, ct).ConfigureAwait(false))
        {
            return;
        }

        try
        {
            var open = _db.ListMediaRequests(null, null, ["approved", "searching", "downloading", "failed"], 500);
            foreach (var q in open)
            {
                if (ct.IsCancellationRequested)
                {
                    break;
                }

                if (DataOf(q).Frozen)
                {
                    continue;
                }

                try
                {
                    if (q.SentAt is null || (q.Kind == "book" && PartsOf(q).Any(p => p.ExternalId is null && p.Status != "cancelled")))
                    {
                        await SendAsync(q, ct).ConfigureAwait(false);
                        continue;
                    }

                    if (q.Kind == "music")
                    {
                        await SyncMusicAsync(q, ct).ConfigureAwait(false);
                    }
                    else
                    {
                        await SyncBooksAsync(q, ct).ConfigureAwait(false);
                    }
                }
                catch (MediaServiceException ex)
                {
                    _logger.LogDebug("Seedy couldn't check {Title} in {Service}: {Message}", q.Title, ex.Service, ex.Message);
                }
#pragma warning disable CA1031 // One broken request must not stop the others from being followed.
                catch (Exception ex)
#pragma warning restore CA1031
                {
                    _logger.LogWarning(ex, "Seedy couldn't follow the request for {Title}", q.Title);
                }
            }
        }
        finally
        {
            _syncGate.Release();
        }
    }

    private async Task SyncMusicAsync(MediaRequestRecord q, CancellationToken ct)
    {
        var data = DataOf(q);
        var parts = PartsOf(q);
        var artist = await _lidarr.ArtistAsync(q.ForeignId, ct).ConfigureAwait(false);
        if (J.Int(artist?["id"]) is not { } artistId)
        {
            // Lidarr lost the artist (deleted by hand): send it again.
            q.SentAt = null;
            data.LidarrAlbums.Clear();
            Store(q, data, parts, NoteOf(q));
            _db.SaveMediaRequest(q);
            return;
        }

        data.LidarrArtistId = artistId;
        if (q.Mode == "albums" && data.Albums.Any(a => !data.LidarrAlbums.ContainsKey(a.Mbid)))
        {
            await ResolveAlbumsAsync(q, data, q.SearchedAt is not null, ct).ConfigureAwait(false);
        }

        var albums = await _lidarr.AlbumsAsync(artistId, ct).ConfigureAwait(false);
        var now = DateTime.UtcNow;
        var targets = q.Mode switch
        {
            "albums" => albums.Where(a => data.LidarrAlbums.ContainsValue(J.Int(a?["id"]) ?? -1)).ToList(),
            "future" => albums.Where(a => J.Date(a?["releaseDate"]) is { } d && d >= q.CreatedAt.Date && d <= now).ToList(),
            _ => albums.Where(a => J.Bool(a?["monitored"]) && (J.Date(a?["releaseDate"]) is not { } d || d <= now)).ToList()
        };
        var queue = await _lidarr.QueueAsync(ct).ConfigureAwait(false);
        var queued = queue.Where(r => J.Int(r?["artistId"]) == artistId).ToList();
        var done = 0;
        double partial = 0;
        string? current = null;
        double? currentFraction = null;
        foreach (var a in targets)
        {
            var id = J.Int(a?["id"]);
            var item = queued.FirstOrDefault(r => J.Int(r?["albumId"]) == id);
            if (item is not null)
            {
                var size = J.Double(item["size"]) ?? 0;
                var left = J.Double(item["sizeleft"]) ?? 0;
                var f = size > 0 ? Math.Clamp((size - left) / size, 0, 1) : 0;
                partial += f;
                if (current is null)
                {
                    current = J.Str(a?["title"]);
                    currentFraction = f;
                }

                continue;
            }

            if ((J.Int(a?["statistics"]?["trackFileCount"]) ?? 0) > 0)
            {
                done++;
            }
        }

        var total = targets.Count;
        MediaNoteDto? note;
        var was = q.Status;
        if (q.Mode == "future" && total == 0)
        {
            q.Status = "approved";
            note = new MediaNoteDto { Code = "future" };
        }
        else if (total > 0 && done == total)
        {
            note = null;
            await ReadyAsync(q, data, "music", ct).ConfigureAwait(false);
            if (q.Status != "ready")
            {
                note = new MediaNoteDto { Code = "scanning" };
            }
        }
        else if (current is not null)
        {
            q.Status = "downloading";
            q.Attention = false;
            q.Progress = total > 0 ? (done + partial) / total : currentFraction;
            note = new MediaNoteDto { Code = "albums", N = done, Total = total, Name = current, Fraction = currentFraction };
        }
        else
        {
            q.Status = q.SearchedAt is null ? "approved" : "searching";
            q.Progress = null;
            note = done > 0 ? new MediaNoteDto { Code = "albums", N = done, Total = total } : null;
            if (q.Status == "searching")
            {
                var since = q.SentAt ?? q.SearchedAt!.Value;
                if (now - since > NothingFound && done == 0)
                {
                    q.Attention = true;
                    var quality = data.AcceptedQualityId is not null ? null : (await _lidarr.QualityAsync(ct).ConfigureAwait(false))?.Name;
                    note = new MediaNoteDto { Code = "noCopy", Name = QualityWord(quality), Days = (int)(now - since).TotalDays };
                }

                if (now - q.SearchedAt!.Value > SearchAgain)
                {
                    await SearchMusicAsync(q, data, ct).ConfigureAwait(false);
                    q.SearchedAt = now;
                }
            }
        }

        foreach (var p in parts)
        {
            p.Status = q.Status;
            p.Progress = q.Progress;
        }

        Store(q, data, parts, note);
        if (was != q.Status)
        {
            q.UpdatedAt = now;
        }

        _db.SaveMediaRequest(q);
    }

    /* "lossless" for FLAC profiles, else the profile's name. */
    private static string? QualityWord(string? profile)
        => profile is null ? null : profile.Contains("flac", StringComparison.OrdinalIgnoreCase) || profile.Contains("lossless", StringComparison.OrdinalIgnoreCase) ? "lossless" : profile;

    private async Task SyncBooksAsync(MediaRequestRecord q, CancellationToken ct)
    {
        var data = DataOf(q);
        var parts = PartsOf(q);
        MediaNoteDto? note = null;
        foreach (var p in parts.Where(p => p.ExternalId is not null && p.Status is not ("ready" or "cancelled")))
        {
            BookAppState state;
            try
            {
                state = p.Service switch
                {
                    "shelfarr" => await _shelfarr.StateAsync(p.ExternalId!, ct).ConfigureAwait(false),
                    "librarr" => await _librarr.StateAsync(p.ExternalId!, ct).ConfigureAwait(false),
                    _ => await _readarr.StateAsync(p.ExternalId!, ct).ConfigureAwait(false)
                };
            }
            catch (MediaServiceException ex) when (ex.Status == HttpStatusCode.NotFound)
            {
                p.Status = "failed";
                p.Attention = true;
                p.Message = null;
                note = new MediaNoteDto { Code = "gone", Name = ServiceName(p.Service) };
                continue;
            }

            p.Attention = state.Attention;
            p.Message = state.Message;
            p.Progress = state.Progress;
            p.Status = state.Status switch
            {
                "completed" => "ready",
                "downloading" or "processing" => "downloading",
                "failed" => "failed",
                "cancelled" => "cancelled",
                "approved" => "approved",
                "pending" => p.Service == "librarr" ? "approved" : "searching",
                _ => "searching"
            };
            if (state.Status is "not_found" or "awaiting_purchase")
            {
                p.Attention = true;
            }

            if (p.Service == "shelfarr" && p.Status == "searching")
            {
                try
                {
                    p.Found = await _shelfarr.FoundAsync(p.ExternalId!, ct).ConfigureAwait(false);
                }
                catch (MediaServiceException)
                {
                    p.Found = null;
                }
            }

            if (state.Status == "not_found")
            {
                note ??= new MediaNoteDto { Code = "notFound", Name = ServiceName(p.Service) };
            }
            else if (state.Status == "awaiting_purchase")
            {
                note ??= new MediaNoteDto { Code = "purchase", Name = ServiceName(p.Service) };
            }
        }

        var was = q.Status;
        var status = Aggregate(parts);
        q.Attention = parts.Any(p => p.Attention) || status == "failed";
        q.Progress = parts.Where(p => p.Status == "downloading").Select(p => p.Progress).FirstOrDefault(x => x is not null);
        var attention = parts.FirstOrDefault(p => p.Attention && p.Message is not null);
        if (note is null && attention is not null)
        {
            note = new MediaNoteDto { Code = "service", Name = ServiceName(attention.Service), Text = attention.Message };
        }
        else if (note is null && status == "searching" && parts.FirstOrDefault(p => p.Found is not null) is { } found)
        {
            note = new MediaNoteDto { Code = "found", N = found.Found, Name = ServiceName(found.Service) };
        }

        if (status == "ready")
        {
            Store(q, data, parts, null);
            await ReadyAsync(q, data, parts[0].Format, ct).ConfigureAwait(false);
            if (q.Status != "ready")
            {
                note = new MediaNoteDto { Code = "scanning" };
            }
            else
            {
                note = null;
            }

            data = DataOf(q);
        }
        else
        {
            q.Status = status;
        }

        if (was != q.Status)
        {
            q.UpdatedAt = DateTime.UtcNow;
        }

        Store(q, data, parts, note);
        _db.SaveMediaRequest(q);
    }

    /* The app has the files: scan the matching library once when the admin wants it, then wait (up to 15 minutes)
       for the item to show up so Play and Read can open it. */
    private async Task ReadyAsync(MediaRequestRecord q, MediaRequestData data, string format, CancellationToken ct)
    {
        var now = DateTime.UtcNow;
        data.FinishedAt ??= now;
        var item = FindItem(q, data);
        if (item is null && !data.Scanned && ConfigMapper.Current.MediaScanOnReady)
        {
            data.Scanned = true;
            Store(q, data, PartsOf(q), NoteOf(q));
            var lib = format == "music" ? MusicLibrary() : _library.LibraryForFormat(format);
            if (lib is { } l)
            {
                try
                {
                    await _library.ScanAsync(l.Id, ct).ConfigureAwait(false);
                }
#pragma warning disable CA1031 // A failed scan leaves the request to Jellyfin's own scheduled scan.
                catch (Exception ex)
#pragma warning restore CA1031
                {
                    _logger.LogWarning(ex, "Seedy couldn't scan {Library}", l.Name);
                }
            }

            item = FindItem(q, data);
        }

        if (item is not null || now - data.FinishedAt.Value > TimeSpan.FromMinutes(15) || !ConfigMapper.Current.MediaScanOnReady)
        {
            q.Status = "ready";
            q.ReadyAt ??= now;
            q.ItemId = item;
            q.Progress = null;
            q.Attention = false;
        }
        else
        {
            q.Status = "downloading";
            q.Progress = 1;
        }

        q.Data = JsonSerializer.Serialize(data);
    }

    private string? FindItem(MediaRequestRecord q, MediaRequestData data)
    {
        if (q.Kind == "music")
        {
            if (q.Mode == "albums" && data.Albums.Count == 1)
            {
                return _library.FindAlbum(data.Albums[0].Mbid, data.Albums[0].Title, q.Subtitle)?.Id;
            }

            return _library.FindArtist(q.ForeignId, q.Mode == "albums" ? q.Subtitle : q.Title)?.Id;
        }

        var matches = _library.FindBooks(q.Title, data.Series, data.SeriesIndex);
        return (matches.FirstOrDefault(b => data.Formats.Contains(b.Format)) ?? matches.FirstOrDefault())?.Id;
    }

    // ── Reading ──

    /// <summary>Lists requests: the person's own, or everyone's for admins.</summary>
    /// <param name="user">The person.</param>
    /// <param name="all">Everyone's (admins only).</param>
    /// <param name="kind">music, book, or both when null.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Requests.</returns>
    public async Task<MediaRequestListDto> ListAsync(User user, bool all, string? kind, CancellationToken ct)
    {
        if (all && !user.IsAdmin())
        {
            throw new MediaServiceException("Seedy", "Only admins see everyone's requests.", HttpStatusCode.Forbidden);
        }

        var list = _db.ListMediaRequests(all ? null : user.Id.ToString("N"), kind is "music" or "book" ? kind : null, null, 300)
            .Where(q => q.Status != "cancelled")
            .Where(q => q.Kind == "music" ? MusicEnabled || !all : BooksEnabled || !all)
            .ToList();
        var dtos = new List<MediaRequestDto>();
        foreach (var q in list)
        {
            dtos.Add(await DtoAsync(q, user, ct).ConfigureAwait(false));
        }

        return new MediaRequestListDto { Results = dtos, Total = dtos.Count };
    }

    /// <summary>The Requests page's counts for music and books.</summary>
    /// <param name="user">The person.</param>
    /// <returns>Summary.</returns>
    public MediaSummaryDto Summary(User user)
    {
        var uid = user.Id.ToString("N");
        var mine = _db.ListMediaRequests(uid, null, Open, 1000);
        var admin = user.IsAdmin();
        var pending = admin ? _db.ListMediaRequests(null, null, ["pending"], 1000) : [];
        return new MediaSummaryDto
        {
            CanManage = admin,
            Music = new MediaKindSummaryDto { Enabled = MusicEnabled, Open = mine.Count(q => q.Kind == "music"), Pending = pending.Count(q => q.Kind == "music") },
            Books = new MediaKindSummaryDto { Enabled = BooksEnabled, Open = mine.Count(q => q.Kind == "book"), Pending = pending.Count(q => q.Kind == "book") }
        };
    }

    /// <summary>Open and recent requests by what they're for, so discover cards can show "Requested" and
    /// "Downloading".</summary>
    /// <param name="kind">music or book.</param>
    /// <returns>Requests.</returns>
    public List<(MediaRequestRecord Request, MediaRequestData Data)> Active(string kind)
        => _db.ListMediaRequests(null, kind, Open, 1000).Select(q => (q, DataOf(q))).ToList();

    private async Task<MediaRequestDto> DtoAsync(MediaRequestRecord q, User viewer, CancellationToken ct)
    {
        var data = DataOf(q);
        var parts = PartsOf(q);
        var mine = q.UserId == viewer.Id.ToString("N");
        var admin = viewer.IsAdmin();
        var dto = new MediaRequestDto
        {
            Id = q.Id,
            Kind = q.Kind,
            Service = q.Service,
            Mode = q.Mode,
            ForeignId = q.ForeignId,
            Title = q.Title,
            Subtitle = q.Subtitle,
            CoverUrl = q.CoverUrl,
            Albums = data.Albums,
            Formats = data.Formats,
            Content = data.Content,
            Series = data.Series,
            SeriesIndex = data.SeriesIndex,
            Language = data.Language,
            KeepNew = data.KeepNew,
            Units = q.Units,
            RequestedBy = mine || admin || ConfigMapper.Current.MediaShowRequesters ? q.UserName : string.Empty,
            RequestedByMe = mine,
            RequestedAt = q.CreatedAt,
            UpdatedAt = q.UpdatedAt,
            DecidedAt = q.DecidedAt,
            ReadyAt = q.ReadyAt,
            Status = q.Status,
            Attention = q.Attention,
            Note = NoteOf(q),
            Progress = q.Progress,
            Parts = parts.Select(p => new MediaPartDto { Format = p.Format, Service = p.Service, Status = p.Status, Progress = p.Progress, ItemId = p.ItemId }).ToList(),
            ItemId = q.ItemId,
            CanDecide = admin && q.Status == "pending",
            CanCancel = admin || (mine && q.Status is "pending" or "approved" or "searching" or "failed" or "declined"),
            CanSearch = (admin || mine) && q.SentAt is not null && q.Status is "searching" or "failed"
        };
        if (q.Kind == "music" && q.Attention && q.Status == "searching" && data.AcceptedQualityId is null && (admin || mine))
        {
            try
            {
                var current = await _lidarr.QualityAsync(ct).ConfigureAwait(false);
                var lower = current is null ? null : await _lidarr.LowerQualityAsync(current.Id, ct).ConfigureAwait(false);
                dto.LowerQuality = lower is null ? null : QualityLabel(lower.Name);
            }
            catch (MediaServiceException)
            {
                dto.LowerQuality = null;
            }
        }

        return dto;
    }

    /* "Standard (MP3 320)" → "MP3 320". */
    private static string QualityLabel(string name)
    {
        var open = name.IndexOf('(', StringComparison.Ordinal);
        var close = name.LastIndexOf(')');
        return open >= 0 && close > open + 1 ? name[(open + 1)..close].Trim() : name;
    }

    /// <summary>Seeds a request as it would be at some point of its life (for the dev stack's demo data).</summary>
    /// <param name="q">The request, with its columns filled in.</param>
    /// <param name="data">Its details.</param>
    /// <param name="note">Its note.</param>
    public void Import(MediaRequestRecord q, MediaRequestData data, MediaNoteDto? note)
    {
        var parts = q.Kind == "music"
            ? [new MediaRequestPart { Format = "music", Service = "lidarr", Status = q.Status, Progress = q.Progress }]
            : data.Formats.Select(f => new MediaRequestPart { Format = f, Service = q.Service, Status = q.Status, Progress = q.Progress }).ToList();
        Store(q, data, parts, note);
        _db.SaveMediaRequest(q);
    }

    /// <summary>The person a request belongs to, if they still exist.</summary>
    /// <param name="q">Request.</param>
    /// <returns>User or null.</returns>
    public User? Owner(MediaRequestRecord q) => Guid.TryParse(q.UserId, out var id) ? _users.GetUserById(id) : null;
}
