using System;
using System.Collections.Generic;
using System.Text.Json.Serialization;

namespace Jellyfin.Plugin.Seedy.Api;

// Wire types for music and book requests (/Seedy/MediaRequests/*, /Seedy/Admin/MediaRequests/*). camelCase to match
// web/src/api/media.ts.

#pragma warning disable SA1402, SA1649, CA1002, CA2227, SA1600

// ── Admin ──

public sealed class AdminLidarrDto
{
    [JsonPropertyName("url")] public string Url { get; set; } = string.Empty;
    [JsonPropertyName("apiKeyHint")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? ApiKeyHint { get; set; }
    [JsonPropertyName("qualityProfileId")] public int QualityProfileId { get; set; }
    [JsonPropertyName("metadataProfileId")] public int MetadataProfileId { get; set; }
    [JsonPropertyName("rootFolder")] public string RootFolder { get; set; } = string.Empty;

    /// <summary>Gets or sets what a whole-artist request monitors: "all" or "future".</summary>
    [JsonPropertyName("wholeArtist")] public string WholeArtist { get; set; } = "all";
    [JsonPropertyName("searchOnApprove")] public bool SearchOnApprove { get; set; } = true;
}

public sealed class AdminBookAppDto
{
    [JsonPropertyName("enabled")] public bool Enabled { get; set; }
    [JsonPropertyName("url")] public string Url { get; set; } = string.Empty;
    [JsonPropertyName("apiKeyHint")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? ApiKeyHint { get; set; }
}

public sealed class AdminBookRoutesDto
{
    [JsonPropertyName("ebooks")] public string Ebooks { get; set; } = "shelfarr";
    [JsonPropertyName("audiobooks")] public string Audiobooks { get; set; } = "shelfarr";
    [JsonPropertyName("comics")] public string Comics { get; set; } = "librarr";
}

public sealed class AdminMediaDto
{
    [JsonPropertyName("lidarr")] public AdminLidarrDto Lidarr { get; set; } = new();
    [JsonPropertyName("shelfarr")] public AdminBookAppDto Shelfarr { get; set; } = new();
    [JsonPropertyName("librarr")] public AdminBookAppDto Librarr { get; set; } = new();
    [JsonPropertyName("readarr")] public AdminBookAppDto Readarr { get; set; } = new();
    [JsonPropertyName("routes")] public AdminBookRoutesDto Routes { get; set; } = new();
    [JsonPropertyName("scanOnReady")] public bool ScanOnReady { get; set; } = true;
    [JsonPropertyName("notifyOnReady")] public bool NotifyOnReady { get; set; } = true;
    [JsonPropertyName("showRequesters")] public bool ShowRequesters { get; set; }
}

public sealed class MediaKeyBody
{
    /// <summary>Gets or sets lidarr, shelfarr, librarr or readarr.</summary>
    [JsonPropertyName("service")] public string Service { get; set; } = string.Empty;
    [JsonPropertyName("apiKey")] public string? ApiKey { get; set; }
}

public sealed class MediaTestResultDto
{
    [JsonPropertyName("service")] public string Service { get; set; } = string.Empty;
    [JsonPropertyName("ok")] public bool Ok { get; set; }

    /// <summary>Gets or sets a short sentence after the bold lead ("Lidarr 3.1 · 41 artists monitored · 2 downloads running").</summary>
    [JsonPropertyName("message")] public string Message { get; set; } = string.Empty;
    [JsonPropertyName("version")] public string? Version { get; set; }

    /// <summary>Gets or sets numbers the client words itself (artists, downloads, sources).</summary>
    [JsonPropertyName("counts")] public Dictionary<string, int> Counts { get; set; } = [];
}

public sealed class IdNameDto
{
    [JsonPropertyName("id")] public int Id { get; set; }
    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;
}

public sealed class RootFolderDto
{
    [JsonPropertyName("path")] public string Path { get; set; } = string.Empty;

    /// <summary>Gets or sets the Jellyfin library whose folder matches, if any.</summary>
    [JsonPropertyName("library")] public string? Library { get; set; }
    [JsonPropertyName("freeSpace")] public long? FreeSpace { get; set; }
}

public sealed class LandingDto
{
    /// <summary>Gets or sets ebook, audiobook, comic or music.</summary>
    [JsonPropertyName("format")] public string Format { get; set; } = string.Empty;
    [JsonPropertyName("library")] public string? Library { get; set; }
    [JsonPropertyName("path")] public string? Path { get; set; }
}

public sealed class AdminMediaInfoDto
{
    [JsonPropertyName("qualityProfiles")] public List<IdNameDto> QualityProfiles { get; set; } = [];
    [JsonPropertyName("metadataProfiles")] public List<IdNameDto> MetadataProfiles { get; set; } = [];
    [JsonPropertyName("rootFolders")] public List<RootFolderDto> RootFolders { get; set; } = [];
    [JsonPropertyName("lidarrError")] public string? LidarrError { get; set; }
    [JsonPropertyName("landing")] public List<LandingDto> Landing { get; set; } = [];
}

public sealed class AdminMediaProfileDto
{
    [JsonPropertyName("userId")] public string UserId { get; set; } = string.Empty;
    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;
    [JsonPropertyName("isAdmin")] public bool IsAdmin { get; set; }
    [JsonPropertyName("maxParentalRating")] public string? MaxParentalRating { get; set; }
    [JsonPropertyName("imageTag")] public string? ImageTag { get; set; }

    /// <summary>Gets or sets albums a week: -1 no limit, 0 can't request.</summary>
    [JsonPropertyName("musicPerWeek")] public int MusicPerWeek { get; set; }

    /// <summary>Gets or sets books a month: -1 no limit, 0 can't request.</summary>
    [JsonPropertyName("booksPerMonth")] public int BooksPerMonth { get; set; }
    [JsonPropertyName("autoApprove")] public bool AutoApprove { get; set; }
}

public sealed class MediaProfileBody
{
    [JsonPropertyName("musicPerWeek")] public int? MusicPerWeek { get; set; }
    [JsonPropertyName("booksPerMonth")] public int? BooksPerMonth { get; set; }
    [JsonPropertyName("autoApprove")] public bool? AutoApprove { get; set; }
}

// ── What the person can use (in /Seedy/Me) ──

public sealed class MediaFeaturesDto
{
    [JsonPropertyName("music")] public bool Music { get; set; }
    [JsonPropertyName("books")] public bool Books { get; set; }

    /// <summary>Gets or sets the book formats that can be requested: ebook, audiobook, comic.</summary>
    [JsonPropertyName("formats")] public List<string> Formats { get; set; } = [];

    /// <summary>Gets or sets app addresses for the "Open in" buttons (admins only).</summary>
    [JsonPropertyName("lidarrUrl")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? LidarrUrl { get; set; }
    [JsonPropertyName("shelfarrUrl")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? ShelfarrUrl { get; set; }
    [JsonPropertyName("librarrUrl")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? LibrarrUrl { get; set; }
    [JsonPropertyName("readarrUrl")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? ReadarrUrl { get; set; }
}

// ── Requests ──

public sealed class MediaAlbumRefDto
{
    [JsonPropertyName("mbid")] public string Mbid { get; set; } = string.Empty;
    [JsonPropertyName("title")] public string Title { get; set; } = string.Empty;
    [JsonPropertyName("type")] public string? Type { get; set; }
    [JsonPropertyName("year")] public int? Year { get; set; }
}

public sealed class MediaPartDto
{
    /// <summary>Gets or sets ebook, audiobook, comic, or music.</summary>
    [JsonPropertyName("format")] public string Format { get; set; } = string.Empty;
    [JsonPropertyName("service")] public string Service { get; set; } = string.Empty;
    [JsonPropertyName("status")] public string Status { get; set; } = "pending";
    [JsonPropertyName("progress")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public double? Progress { get; set; }
    [JsonPropertyName("itemId")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? ItemId { get; set; }
}

/// <summary>The line under a request: a code the client words, with its values.</summary>
public sealed class MediaNoteDto
{
    /// <summary>Gets or sets albums, noCopy, searching, found, notFound, service, unreachable, future, purchase or scanning.</summary>
    [JsonPropertyName("code")] public string Code { get; set; } = string.Empty;
    [JsonPropertyName("n")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public int? N { get; set; }
    [JsonPropertyName("total")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public int? Total { get; set; }
    [JsonPropertyName("days")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public int? Days { get; set; }
    [JsonPropertyName("name")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? Name { get; set; }
    [JsonPropertyName("fraction")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public double? Fraction { get; set; }

    /// <summary>Gets or sets the app's own words, shown as they are.</summary>
    [JsonPropertyName("text")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? Text { get; set; }
}

public sealed class MediaRequestDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = string.Empty;
    [JsonPropertyName("kind")] public string Kind { get; set; } = "music";
    [JsonPropertyName("service")] public string Service { get; set; } = string.Empty;

    /// <summary>Gets or sets artist, albums, future or book.</summary>
    [JsonPropertyName("mode")] public string Mode { get; set; } = "albums";
    [JsonPropertyName("foreignId")] public string ForeignId { get; set; } = string.Empty;
    [JsonPropertyName("title")] public string Title { get; set; } = string.Empty;
    [JsonPropertyName("subtitle")] public string? Subtitle { get; set; }
    [JsonPropertyName("coverUrl")] public string? CoverUrl { get; set; }
    [JsonPropertyName("albums")] public List<MediaAlbumRefDto> Albums { get; set; } = [];

    /// <summary>Gets or sets the book formats asked for.</summary>
    [JsonPropertyName("formats")] public List<string> Formats { get; set; } = [];

    /// <summary>Gets or sets book or comic.</summary>
    [JsonPropertyName("content")] public string? Content { get; set; }
    [JsonPropertyName("series")] public string? Series { get; set; }
    [JsonPropertyName("seriesIndex")] public double? SeriesIndex { get; set; }
    [JsonPropertyName("language")] public string? Language { get; set; }
    [JsonPropertyName("keepNew")] public bool KeepNew { get; set; }
    [JsonPropertyName("units")] public int Units { get; set; }
    [JsonPropertyName("requestedBy")] public string RequestedBy { get; set; } = string.Empty;
    [JsonPropertyName("requestedByMe")] public bool RequestedByMe { get; set; }
    [JsonPropertyName("requestedAt")] public DateTime RequestedAt { get; set; }
    [JsonPropertyName("updatedAt")] public DateTime UpdatedAt { get; set; }
    [JsonPropertyName("decidedAt")] public DateTime? DecidedAt { get; set; }
    [JsonPropertyName("readyAt")] public DateTime? ReadyAt { get; set; }

    /// <summary>Gets or sets pending, approved, searching, downloading, ready, failed, declined or cancelled.</summary>
    [JsonPropertyName("status")] public string Status { get; set; } = "pending";
    [JsonPropertyName("attention")] public bool Attention { get; set; }
    [JsonPropertyName("note")] public MediaNoteDto? Note { get; set; }
    [JsonPropertyName("progress")] public double? Progress { get; set; }
    [JsonPropertyName("parts")] public List<MediaPartDto> Parts { get; set; } = [];

    /// <summary>Gets or sets the Jellyfin item to open once it's on the server ("N").</summary>
    [JsonPropertyName("itemId")] public string? ItemId { get; set; }

    /// <summary>Gets or sets a lesser quality the person may accept instead ("MP3 320"), when the app allows it.</summary>
    [JsonPropertyName("lowerQuality")] public string? LowerQuality { get; set; }
    [JsonPropertyName("canSearch")] public bool CanSearch { get; set; }
    [JsonPropertyName("canCancel")] public bool CanCancel { get; set; }
    [JsonPropertyName("canDecide")] public bool CanDecide { get; set; }
}

public sealed class MediaRequestListDto
{
    [JsonPropertyName("results")] public List<MediaRequestDto> Results { get; set; } = [];
    [JsonPropertyName("total")] public int Total { get; set; }
}

public sealed class MediaKindSummaryDto
{
    [JsonPropertyName("enabled")] public bool Enabled { get; set; }

    /// <summary>Gets or sets the person's own requests still on their way.</summary>
    [JsonPropertyName("open")] public int Open { get; set; }

    /// <summary>Gets or sets every request waiting for approval (admins only).</summary>
    [JsonPropertyName("pending")] public int Pending { get; set; }
}

public sealed class MediaSummaryDto
{
    [JsonPropertyName("music")] public MediaKindSummaryDto Music { get; set; } = new();
    [JsonPropertyName("books")] public MediaKindSummaryDto Books { get; set; } = new();
    [JsonPropertyName("canManage")] public bool CanManage { get; set; }
}

public sealed class MediaFormatOptionDto
{
    [JsonPropertyName("format")] public string Format { get; set; } = string.Empty;
    [JsonPropertyName("service")] public string Service { get; set; } = string.Empty;

    /// <summary>Gets or sets the Jellyfin library it lands in.</summary>
    [JsonPropertyName("library")] public string? Library { get; set; }
}

public sealed class MediaOptionsDto
{
    [JsonPropertyName("kind")] public string Kind { get; set; } = "music";
    [JsonPropertyName("canRequest")] public bool CanRequest { get; set; }

    /// <summary>Gets or sets the limit per period: -1 = no limit, 0 = can't request.</summary>
    [JsonPropertyName("limit")] public int Limit { get; set; } = -1;
    [JsonPropertyName("used")] public int Used { get; set; }

    /// <summary>Gets or sets week or month.</summary>
    [JsonPropertyName("period")] public string Period { get; set; } = "week";
    [JsonPropertyName("autoApprove")] public bool AutoApprove { get; set; }

    /// <summary>Gets or sets Lidarr's quality profile name, for "set by your admin".</summary>
    [JsonPropertyName("quality")] public string? Quality { get; set; }
    [JsonPropertyName("formats")] public List<MediaFormatOptionDto> Formats { get; set; } = [];
    [JsonPropertyName("scanOnReady")] public bool ScanOnReady { get; set; }
}

public sealed class MediaCreateBody
{
    /// <summary>Gets or sets music or book.</summary>
    [JsonPropertyName("kind")] public string Kind { get; set; } = "music";

    /// <summary>Gets or sets artist, albums or future (music); book.</summary>
    [JsonPropertyName("mode")] public string Mode { get; set; } = "albums";
    [JsonPropertyName("foreignId")] public string ForeignId { get; set; } = string.Empty;
    [JsonPropertyName("title")] public string Title { get; set; } = string.Empty;
    [JsonPropertyName("subtitle")] public string? Subtitle { get; set; }
    [JsonPropertyName("coverUrl")] public string? CoverUrl { get; set; }
    [JsonPropertyName("albums")] public List<MediaAlbumRefDto>? Albums { get; set; }
    [JsonPropertyName("keepNew")] public bool KeepNew { get; set; }

    /// <summary>Gets or sets the number of albums a whole-artist request brings (for the person's limit).</summary>
    [JsonPropertyName("albumCount")] public int? AlbumCount { get; set; }
    [JsonPropertyName("formats")] public List<string>? Formats { get; set; }
    [JsonPropertyName("content")] public string? Content { get; set; }
    [JsonPropertyName("series")] public string? Series { get; set; }
    [JsonPropertyName("seriesIndex")] public double? SeriesIndex { get; set; }
    [JsonPropertyName("year")] public int? Year { get; set; }
    [JsonPropertyName("language")] public string? Language { get; set; }
}

// ── Discover ──

public sealed class MusicReleaseDto
{
    /// <summary>Gets or sets the MusicBrainz release group id.</summary>
    [JsonPropertyName("mbid")] public string Mbid { get; set; } = string.Empty;
    [JsonPropertyName("artistMbid")] public string? ArtistMbid { get; set; }
    [JsonPropertyName("title")] public string Title { get; set; } = string.Empty;
    [JsonPropertyName("artist")] public string? Artist { get; set; }

    /// <summary>Gets or sets album, ep or single.</summary>
    [JsonPropertyName("type")] public string Type { get; set; } = "album";

    /// <summary>Gets or sets the first release date: yyyy, yyyy-MM or yyyy-MM-dd.</summary>
    [JsonPropertyName("date")] public string? Date { get; set; }
    [JsonPropertyName("coverUrl")] public string? CoverUrl { get; set; }
    [JsonPropertyName("upcoming")] public bool Upcoming { get; set; }

    /// <summary>Gets or sets none, server, requested, waiting or downloading.</summary>
    [JsonPropertyName("status")] public string Status { get; set; } = "none";
    [JsonPropertyName("requestedBy")] public string? RequestedBy { get; set; }
    [JsonPropertyName("itemId")] public string? ItemId { get; set; }

    /// <summary>Gets or sets albums of this artist on the server, for "you have 7 of 8".</summary>
    [JsonPropertyName("have")] public int? Have { get; set; }
    [JsonPropertyName("of")] public int? Of { get; set; }
    [JsonPropertyName("tracks")] public int? Tracks { get; set; }
    [JsonPropertyName("lengthMs")] public long? LengthMs { get; set; }
}

public sealed class MusicRowDto
{
    [JsonPropertyName("results")] public List<MusicReleaseDto> Results { get; set; } = [];

    /// <summary>Gets or sets where the row came from, as the row's subtitle says it.</summary>
    [JsonPropertyName("source")] public string? Source { get; set; }
}

public sealed class MusicArtistRefDto
{
    [JsonPropertyName("mbid")] public string Mbid { get; set; } = string.Empty;
    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;
    [JsonPropertyName("disambiguation")] public string? Disambiguation { get; set; }
    [JsonPropertyName("type")] public string? Type { get; set; }
    [JsonPropertyName("area")] public string? Area { get; set; }
    [JsonPropertyName("imageUrl")] public string? ImageUrl { get; set; }
    [JsonPropertyName("itemId")] public string? ItemId { get; set; }
}

public sealed class MusicSearchDto
{
    [JsonPropertyName("artists")] public List<MusicArtistRefDto> Artists { get; set; } = [];
    [JsonPropertyName("albums")] public List<MusicReleaseDto> Albums { get; set; } = [];
}

public sealed class MusicArtistDto
{
    [JsonPropertyName("artist")] public MusicArtistRefDto Artist { get; set; } = new();
    [JsonPropertyName("releases")] public List<MusicReleaseDto> Releases { get; set; } = [];
    [JsonPropertyName("albumsOnServer")] public int AlbumsOnServer { get; set; }

    /// <summary>Gets or sets whether Lidarr already watches this artist for new albums.</summary>
    [JsonPropertyName("watching")] public bool Watching { get; set; }
}

public sealed class BookCardDto
{
    [JsonPropertyName("workId")] public string WorkId { get; set; } = string.Empty;
    [JsonPropertyName("title")] public string Title { get; set; } = string.Empty;
    [JsonPropertyName("author")] public string? Author { get; set; }
    [JsonPropertyName("year")] public int? Year { get; set; }
    [JsonPropertyName("releaseDate")] public string? ReleaseDate { get; set; }
    [JsonPropertyName("coverUrl")] public string? CoverUrl { get; set; }
    [JsonPropertyName("series")] public string? Series { get; set; }
    [JsonPropertyName("seriesIndex")] public double? SeriesIndex { get; set; }

    /// <summary>Gets or sets book or comic.</summary>
    [JsonPropertyName("content")] public string Content { get; set; } = "book";

    /// <summary>Gets or sets the formats that can be requested for it (ebook, audiobook, comic).</summary>
    [JsonPropertyName("formats")] public List<string> Formats { get; set; } = [];
    [JsonPropertyName("upcoming")] public bool Upcoming { get; set; }
    [JsonPropertyName("rating")] public double? Rating { get; set; }
    [JsonPropertyName("pages")] public int? Pages { get; set; }
    [JsonPropertyName("description")] public string? Description { get; set; }
    [JsonPropertyName("languages")] public List<string> Languages { get; set; } = [];

    /// <summary>Gets or sets none, server, requested, waiting or downloading.</summary>
    [JsonPropertyName("status")] public string Status { get; set; } = "none";
    [JsonPropertyName("requestedBy")] public string? RequestedBy { get; set; }
    [JsonPropertyName("itemId")] public string? ItemId { get; set; }

    /// <summary>Gets or sets the formats already on the server.</summary>
    [JsonPropertyName("onServer")] public List<string> OnServer { get; set; } = [];

    /// <summary>Gets or sets the series volumes the person read on Seedy, for "You've read books 1 to 6".</summary>
    [JsonPropertyName("readFrom")] public double? ReadFrom { get; set; }
    [JsonPropertyName("readTo")] public double? ReadTo { get; set; }
}

public sealed class BookRowDto
{
    [JsonPropertyName("results")] public List<BookCardDto> Results { get; set; } = [];
    [JsonPropertyName("source")] public string? Source { get; set; }
}
