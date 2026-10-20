using System;
using System.Collections.Generic;
using System.Text.Json.Serialization;
using MediaBrowser.Model.Dto;

namespace Jellyfin.Plugin.Seedy.Api;

// Wire types for /Seedy/*. Names are camelCase to match web/src/api/seedy.ts; Jellyfin's own
// BaseItemDto stays PascalCase inside them. Jellyfin binds JSON case-sensitively, hence the attributes.

#pragma warning disable SA1402, SA1649, CA1002, CA2227, SA1600

public sealed class BrandingDto
{
    [JsonPropertyName("name")] public string Name { get; set; } = "Seedy";
    [JsonPropertyName("accent")] public string Accent { get; set; } = "#ff9fff";
    [JsonPropertyName("hasLogo")] public bool HasLogo { get; set; }
    [JsonPropertyName("logoVersion")] public string LogoVersion { get; set; } = "0";
    [JsonPropertyName("signInBackground")] public string SignInBackground { get; set; } = "backdrops";
    [JsonPropertyName("logoFileName")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? LogoFileName { get; set; }
}

public sealed class PlaybackPrefsDto
{
    [JsonPropertyName("skipIntro")] public string SkipIntro { get; set; } = "button";
    [JsonPropertyName("skipRecap")] public string SkipRecap { get; set; } = "button";
    [JsonPropertyName("skipCredits")] public string SkipCredits { get; set; } = "button";
    [JsonPropertyName("autoPlayNext")] public bool AutoPlayNext { get; set; } = true;
    [JsonPropertyName("upNextSeconds")] public int UpNextSeconds { get; set; } = 15;
    [JsonPropertyName("moviePicksSeconds")] public int MoviePicksSeconds { get; set; } = 15;
    [JsonPropertyName("seekBackSeconds")] public int SeekBackSeconds { get; set; } = 10;
    [JsonPropertyName("seekForwardSeconds")] public int SeekForwardSeconds { get; set; } = 30;
    [JsonPropertyName("pauseScreenSeconds")] public int PauseScreenSeconds { get; set; } = 10;
    [JsonPropertyName("maxQuality")] public string MaxQuality { get; set; } = "auto";
}

public sealed class SubtitlePrefsDto
{
    [JsonPropertyName("size")] public string Size { get; set; } = "M";
    [JsonPropertyName("lift")] public bool Lift { get; set; } = true;
}

public sealed class HomeRowPrefDto
{
    [JsonPropertyName("kind")] public string Kind { get; set; } = string.Empty;
    [JsonPropertyName("enabled")] public bool Enabled { get; set; } = true;
    [JsonPropertyName("count")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public int? Count { get; set; }
    [JsonPropertyName("pinned")] public bool Pinned { get; set; }
}

public sealed class PrivacyPrefsDto
{
    [JsonPropertyName("personalRecs")] public bool PersonalRecs { get; set; } = true;
    [JsonPropertyName("countInTop10")] public bool CountInTop10 { get; set; } = true;
}

public sealed class UserPrefsDto
{
    [JsonPropertyName("playback")] public PlaybackPrefsDto Playback { get; set; } = new();
    [JsonPropertyName("subtitles")] public SubtitlePrefsDto Subtitles { get; set; } = new();
    [JsonPropertyName("adaptRowOrder")] public bool AdaptRowOrder { get; set; } = true;
    [JsonPropertyName("hiddenRows")] public List<string> HiddenRows { get; set; } = [];
    [JsonPropertyName("hiddenSeeds")] public List<string> HiddenSeeds { get; set; } = [];
    [JsonPropertyName("volume")] public double? Volume { get; set; }
    [JsonPropertyName("rowOrder")] public string RowOrder { get; set; } = "auto";
    [JsonPropertyName("rows")] public List<HomeRowPrefDto> Rows { get; set; } = [];
    [JsonPropertyName("privacy")] public PrivacyPrefsDto Privacy { get; set; } = new();
    [JsonPropertyName("notifyRequests")] public bool NotifyRequests { get; set; } = true;
    [JsonPropertyName("custom")] public Dictionary<string, System.Text.Json.JsonElement> Custom { get; set; } = [];
}

/// <summary>A partial preferences update: only fields present are applied.</summary>
public sealed class UserPrefsPatch
{
    [JsonPropertyName("playback")] public PlaybackPrefsDto? Playback { get; set; }
    [JsonPropertyName("subtitles")] public SubtitlePrefsDto? Subtitles { get; set; }
    [JsonPropertyName("adaptRowOrder")] public bool? AdaptRowOrder { get; set; }
    [JsonPropertyName("hiddenRows")] public List<string>? HiddenRows { get; set; }
    [JsonPropertyName("hiddenSeeds")] public List<string>? HiddenSeeds { get; set; }
    [JsonPropertyName("volume")] public double? Volume { get; set; }
    [JsonPropertyName("rowOrder")] public string? RowOrder { get; set; }
    [JsonPropertyName("rows")] public List<HomeRowPrefDto>? Rows { get; set; }
    [JsonPropertyName("privacy")] public PrivacyPrefsDto? Privacy { get; set; }
    [JsonPropertyName("notifyRequests")] public bool? NotifyRequests { get; set; }
    /// <summary>Gets or sets keys to merge into the custom bag; a JSON null removes a key.</summary>
    [JsonPropertyName("custom")] public Dictionary<string, System.Text.Json.JsonElement>? Custom { get; set; }
}

public sealed class RequestsFeaturesDto
{
    [JsonPropertyName("connected")] public bool Connected { get; set; }
    [JsonPropertyName("discover")] public bool Discover { get; set; }
    [JsonPropertyName("upcomingMovies")] public bool UpcomingMovies { get; set; }
    [JsonPropertyName("requestButtons")] public bool RequestButtons { get; set; }
    [JsonPropertyName("requestInSearch")] public bool RequestInSearch { get; set; }
    [JsonPropertyName("canRequest")] public bool CanRequest { get; set; }
    [JsonPropertyName("allow4k")] public bool Allow4k { get; set; }
    [JsonPropertyName("seerrUrl")] public string? SeerrUrl { get; set; }
    [JsonPropertyName("account")] public SeerrAccountRefDto? Account { get; set; }
}

/// <summary>The person's own Seerr account, as their settings show it.</summary>
public sealed class SeerrAccountRefDto
{
    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;

    /// <summary>Gets or sets how it was linked: "jellyfin" (Seerr's Jellyfin sign-in) or "manual" (by an admin).</summary>
    [JsonPropertyName("linkedBy")] public string LinkedBy { get; set; } = "jellyfin";
}

public sealed class PasswordStateDto
{
    [JsonPropertyName("changedAt")] public DateTime? ChangedAt { get; set; }
}

public sealed class MeDto
{
    [JsonPropertyName("isAdmin")] public bool IsAdmin { get; set; }
    [JsonPropertyName("branding")] public BrandingDto Branding { get; set; } = new();
    [JsonPropertyName("prefs")] public UserPrefsDto Prefs { get; set; } = new();
    [JsonPropertyName("defaults")] public PlaybackPrefsDto Defaults { get; set; } = new();
    [JsonPropertyName("requests")] public RequestsFeaturesDto Requests { get; set; } = new();
    [JsonPropertyName("historyEnabled")] public bool HistoryEnabled { get; set; }

    /// <summary>Gets or sets the admin's Home rows as this person can have them (unavailable ones switched off).</summary>
    [JsonPropertyName("homeRows")] public List<HomeRowSettingDto> HomeRows { get; set; } = [];
    [JsonPropertyName("pluginVersion")] public string PluginVersion { get; set; } = "0.1.0";
}

public sealed class HomeRowDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = string.Empty;
    [JsonPropertyName("kind")] public string Kind { get; set; } = string.Empty;
    [JsonPropertyName("title")] public string? Title { get; set; }
    [JsonPropertyName("seedId")] public string? SeedId { get; set; }
    [JsonPropertyName("seedName")] public string? SeedName { get; set; }
    [JsonPropertyName("genre")] public string? Genre { get; set; }
    [JsonPropertyName("libraryId")] public string? LibraryId { get; set; }
    [JsonPropertyName("libraryName")] public string? LibraryName { get; set; }
    [JsonPropertyName("collectionType")] public string? CollectionType { get; set; }
    [JsonPropertyName("collectionId")] public string? CollectionId { get; set; }
}

public sealed class HomeDto
{
    [JsonPropertyName("rows")] public List<HomeRowDto> Rows { get; set; } = [];
}

public sealed class FeaturedEntryDto
{
    [JsonPropertyName("item")] public BaseItemDto Item { get; set; } = null!;
    [JsonPropertyName("reason")] public string Reason { get; set; } = string.Empty;
    [JsonPropertyName("play")] public BaseItemDto? Play { get; set; }
}

public sealed class Top10EntryDto
{
    [JsonPropertyName("item")] public BaseItemDto Item { get; set; } = null!;
    [JsonPropertyName("profiles")] public int Profiles { get; set; }
    [JsonPropertyName("plays")] public int Plays { get; set; }
}

public sealed class Top10Dto
{
    [JsonPropertyName("items")] public List<Top10EntryDto> Items { get; set; } = [];
    [JsonPropertyName("quiet")] public bool Quiet { get; set; }
}

public sealed class OriginBody
{
    [JsonPropertyName("itemId")] public string ItemId { get; set; } = string.Empty;
    [JsonPropertyName("row")] public string Row { get; set; } = string.Empty;
}

public sealed class HistoryEntryDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = string.Empty;
    [JsonPropertyName("itemId")] public string ItemId { get; set; } = string.Empty;
    [JsonPropertyName("titleId")] public string TitleId { get; set; } = string.Empty;
    [JsonPropertyName("titleName")] public string TitleName { get; set; } = string.Empty;
    [JsonPropertyName("itemName")] public string ItemName { get; set; } = string.Empty;
    [JsonPropertyName("itemType")] public string ItemType { get; set; } = string.Empty;
    [JsonPropertyName("item")] public BaseItemDto? Item { get; set; }
    [JsonPropertyName("startedAt")] public DateTime StartedAt { get; set; }
    [JsonPropertyName("stoppedAt")] public DateTime? StoppedAt { get; set; }
    [JsonPropertyName("positionTicks")] public long PositionTicks { get; set; }
    [JsonPropertyName("runtimeTicks")] public long RuntimeTicks { get; set; }
    [JsonPropertyName("finished")] public bool Finished { get; set; }
    [JsonPropertyName("deviceName")] public string? DeviceName { get; set; }
    [JsonPropertyName("client")] public string? Client { get; set; }
    [JsonPropertyName("hidden")] public bool Hidden { get; set; }
}

public sealed class HistoryDto
{
    [JsonPropertyName("items")] public List<HistoryEntryDto> Items { get; set; } = [];
    [JsonPropertyName("total")] public int Total { get; set; }
}

public sealed class HiddenBody
{
    [JsonPropertyName("hidden")] public bool Hidden { get; set; }
}

public class SeerrItemDto
{
    [JsonPropertyName("tmdbId")] public int TmdbId { get; set; }
    [JsonPropertyName("mediaType")] public string MediaType { get; set; } = "movie";
    [JsonPropertyName("title")] public string Title { get; set; } = string.Empty;
    [JsonPropertyName("year")] public string? Year { get; set; }
    [JsonPropertyName("rating")] public double? Rating { get; set; }
    [JsonPropertyName("posterPath")] public string? PosterPath { get; set; }
    [JsonPropertyName("backdropPath")] public string? BackdropPath { get; set; }
    [JsonPropertyName("overview")] public string? Overview { get; set; }
    [JsonPropertyName("status")] public string Status { get; set; } = "none";
    [JsonPropertyName("requestedBy")] public string? RequestedBy { get; set; }
    [JsonPropertyName("requestedByMe")] public bool RequestedByMe { get; set; }
    [JsonPropertyName("jellyfinItemId")] public string? JellyfinItemId { get; set; }
}

public sealed class SeerrUpcomingDto : SeerrItemDto
{
    /// <summary>Gets or sets the release day as yyyy-MM-dd (a calendar date, not a moment).</summary>
    [JsonPropertyName("releaseDate")] public string ReleaseDate { get; set; } = string.Empty;
    [JsonPropertyName("releaseKind")] public string? ReleaseKind { get; set; }
}

public sealed class SeerrCastDto
{
    [JsonPropertyName("id")] public int Id { get; set; }
    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;
    [JsonPropertyName("character")] public string? Character { get; set; }
    [JsonPropertyName("profilePath")] public string? ProfilePath { get; set; }
}

public sealed class SeerrCrewDto
{
    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;
    [JsonPropertyName("job")] public string Job { get; set; } = string.Empty;
}

public sealed class MyRequestDto : SeerrItemDto
{
    [JsonPropertyName("requestId")] public int RequestId { get; set; }
    [JsonPropertyName("requestedAt")] public DateTime RequestedAt { get; set; }
    [JsonPropertyName("requestStatus")] public string RequestStatus { get; set; } = "pending";
    [JsonPropertyName("seasons")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public List<int>? Seasons { get; set; }
    [JsonPropertyName("is4k")] public bool Is4k { get; set; }
    [JsonPropertyName("availableAt")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public DateTime? AvailableAt { get; set; }
}

public sealed class MyRequestsDto
{
    [JsonPropertyName("results")] public List<MyRequestDto> Results { get; set; } = [];
    [JsonPropertyName("open")] public int Open { get; set; }
}

public sealed class SeerrSeasonDto
{
    [JsonPropertyName("seasonNumber")] public int SeasonNumber { get; set; }
    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;
    [JsonPropertyName("episodeCount")] public int EpisodeCount { get; set; }
    [JsonPropertyName("airDate")] public string? AirDate { get; set; }
    [JsonPropertyName("status")] public string Status { get; set; } = "none";
}

public sealed class SeerrDetailsDto : SeerrItemDto
{
    [JsonPropertyName("tagline")] public string? Tagline { get; set; }
    [JsonPropertyName("genres")] public List<string> Genres { get; set; } = [];
    [JsonPropertyName("runtime")] public int? Runtime { get; set; }
    [JsonPropertyName("releaseDate")] public string? ReleaseDate { get; set; }
    [JsonPropertyName("certification")] public string? Certification { get; set; }
    [JsonPropertyName("cast")] public List<SeerrCastDto> Cast { get; set; } = [];
    [JsonPropertyName("crew")] public List<SeerrCrewDto> Crew { get; set; } = [];
    [JsonPropertyName("seasons")] public List<SeerrSeasonDto>? Seasons { get; set; }
    [JsonPropertyName("networks")] public List<string>? Networks { get; set; }
    [JsonPropertyName("numberOfSeasons")] public int? NumberOfSeasons { get; set; }
    [JsonPropertyName("trailerUrl")] public string? TrailerUrl { get; set; }
    [JsonPropertyName("status4k")] public string? Status4k { get; set; }
}

public sealed class SeerrResultsDto<T>
{
    [JsonPropertyName("results")] public List<T> Results { get; set; } = [];
    [JsonPropertyName("total")] public int Total { get; set; }
}

public sealed class RequestBody
{
    [JsonPropertyName("mediaType")] public string MediaType { get; set; } = "movie";
    [JsonPropertyName("tmdbId")] public int TmdbId { get; set; }
    [JsonPropertyName("seasons")] public List<int>? Seasons { get; set; }
    [JsonPropertyName("is4k")] public bool Is4k { get; set; }
}

public sealed class RequestResultDto
{
    [JsonPropertyName("status")] public string Status { get; set; } = "pending";
}

// Admin

public sealed class HomeRowSettingDto
{
    [JsonPropertyName("kind")] public string Kind { get; set; } = string.Empty;
    [JsonPropertyName("enabled")] public bool Enabled { get; set; } = true;
    [JsonPropertyName("count")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public int? Count { get; set; }
}

public sealed class AdminHomeDto
{
    [JsonPropertyName("rows")] public List<HomeRowSettingDto> Rows { get; set; } = [];
    [JsonPropertyName("adaptOrder")] public bool AdaptOrder { get; set; } = true;
    [JsonPropertyName("because")] public BecauseDto Because { get; set; } = new();
    [JsonPropertyName("top10")] public Top10SettingsDto Top10 { get; set; } = new();
    [JsonPropertyName("genres")] public GenreSettingsDto Genres { get; set; } = new();
    [JsonPropertyName("featured")] public FeaturedSettingsDto Featured { get; set; } = new();
}

public sealed class BecauseDto
{
    [JsonPropertyName("watchedWhen")] public string WatchedWhen { get; set; } = "finished";
    [JsonPropertyName("lookBackDays")] public int LookBackDays { get; set; } = 60;
    [JsonPropertyName("minTitles")] public int MinTitles { get; set; } = 4;
}

public sealed class Top10SettingsDto
{
    [JsonPropertyName("periodDays")] public int PeriodDays { get; set; } = 7;
    [JsonPropertyName("minProfiles")] public int MinProfiles { get; set; } = 3;
}

public sealed class GenreSettingsDto
{
    [JsonPropertyName("windowDays")] public int WindowDays { get; set; } = 90;
    [JsonPropertyName("starting")] public List<string> Starting { get; set; } = [];
}

public sealed class FeaturedSettingsDto
{
    [JsonPropertyName("mode")] public string Mode { get; set; } = "both";
    [JsonPropertyName("picks")] public List<string> Picks { get; set; } = [];
}

public sealed class AdminRequestsDto
{
    [JsonPropertyName("url")] public string Url { get; set; } = string.Empty;
    [JsonPropertyName("apiKeyHint")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? ApiKeyHint { get; set; }
    [JsonPropertyName("showDiscover")] public bool ShowDiscover { get; set; } = true;
    [JsonPropertyName("showUpcomingMovies")] public bool ShowUpcomingMovies { get; set; } = true;
    [JsonPropertyName("showRequestButtons")] public bool ShowRequestButtons { get; set; } = true;
    [JsonPropertyName("showRequestInSearch")] public bool ShowRequestInSearch { get; set; } = true;
    [JsonPropertyName("upcomingSource")] public string UpcomingSource { get; set; } = "requests";
    [JsonPropertyName("who")] public string Who { get; set; } = "everyone";
    [JsonPropertyName("allow4k")] public bool Allow4k { get; set; }
}

public sealed class AdminPlayLogDto
{
    [JsonPropertyName("enabled")] public bool Enabled { get; set; } = true;
    [JsonPropertyName("retentionDays")] public int RetentionDays { get; set; } = 365;
    [JsonPropertyName("useTop10")] public bool UseTop10 { get; set; } = true;
    [JsonPropertyName("useHistory")] public bool UseHistory { get; set; } = true;
    [JsonPropertyName("useRowOrder")] public bool UseRowOrder { get; set; } = true;
    [JsonPropertyName("useGenres")] public bool UseGenres { get; set; } = true;
}

public sealed class AdminConfigDto
{
    [JsonPropertyName("opensAt")] public string OpensAt { get; set; } = "alongside";
    [JsonPropertyName("branding")] public BrandingDto Branding { get; set; } = new();
    [JsonPropertyName("playbackDefaults")] public PlaybackPrefsDto PlaybackDefaults { get; set; } = new();
    [JsonPropertyName("home")] public AdminHomeDto Home { get; set; } = new();
    [JsonPropertyName("requests")] public AdminRequestsDto Requests { get; set; } = new();
    [JsonPropertyName("playLog")] public AdminPlayLogDto PlayLog { get; set; } = new();
}

public sealed class SeerrStateDto
{
    [JsonPropertyName("state")] public string State { get; set; } = "off";
    [JsonPropertyName("message")] public string? Message { get; set; }
    [JsonPropertyName("checkedAt")] public DateTime? CheckedAt { get; set; }
}

public sealed class PlayLogStateDto
{
    [JsonPropertyName("enabled")] public bool Enabled { get; set; }
    [JsonPropertyName("retentionDays")] public int RetentionDays { get; set; }
    [JsonPropertyName("plays")] public int Plays { get; set; }
}

public sealed class AdminStatusDto
{
    [JsonPropertyName("pluginVersion")] public string PluginVersion { get; set; } = string.Empty;
    [JsonPropertyName("latestVersion")] public string? LatestVersion { get; set; }
    [JsonPropertyName("upToDate")] public bool? UpToDate { get; set; }
    [JsonPropertyName("serverVersion")] public string ServerVersion { get; set; } = string.Empty;
    [JsonPropertyName("serverSupported")] public bool ServerSupported { get; set; }
    [JsonPropertyName("serverStartedAt")] public DateTime ServerStartedAt { get; set; }
    [JsonPropertyName("os")] public string Os { get; set; } = string.Empty;

    /// <summary>Gets or sets the server clock's current UTC offset in minutes: daily and weekly task triggers use it.</summary>
    [JsonPropertyName("utcOffsetMinutes")] public int UtcOffsetMinutes { get; set; }
    [JsonPropertyName("seerr")] public SeerrStateDto Seerr { get; set; } = new();
    [JsonPropertyName("playLog")] public PlayLogStateDto PlayLog { get; set; } = new();
}

public sealed class SeerrTestBody
{
    [JsonPropertyName("url")] public string? Url { get; set; }
    [JsonPropertyName("apiKey")] public string? ApiKey { get; set; }
}

public sealed class SeerrTestResultDto
{
    [JsonPropertyName("ok")] public bool Ok { get; set; }
    [JsonPropertyName("message")] public string Message { get; set; } = string.Empty;
    [JsonPropertyName("version")] public string? Version { get; set; }
    [JsonPropertyName("radarr")] public bool Radarr { get; set; }
    [JsonPropertyName("sonarr")] public bool Sonarr { get; set; }
}

public sealed class KeyBody
{
    [JsonPropertyName("apiKey")] public string ApiKey { get; set; } = string.Empty;
}

public sealed class SeerrAccountDto
{
    [JsonPropertyName("userId")] public string UserId { get; set; } = string.Empty;
    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;
    [JsonPropertyName("isAdmin")] public bool IsAdmin { get; set; }
    [JsonPropertyName("maxParentalRating")] public string? MaxParentalRating { get; set; }
    [JsonPropertyName("hasImage")] public bool HasImage { get; set; }
    [JsonPropertyName("imageTag")] public string? ImageTag { get; set; }
    [JsonPropertyName("seerrUserId")] public int? SeerrUserId { get; set; }
    [JsonPropertyName("seerrName")] public string? SeerrName { get; set; }
    [JsonPropertyName("matchedBy")] public string? MatchedBy { get; set; }
    [JsonPropertyName("canRequest")] public bool CanRequest { get; set; }
}

public sealed class SeerrUserDto
{
    [JsonPropertyName("id")] public int Id { get; set; }
    [JsonPropertyName("displayName")] public string DisplayName { get; set; } = string.Empty;
    [JsonPropertyName("email")] public string? Email { get; set; }
    [JsonPropertyName("jellyfinUserId")] public string? JellyfinUserId { get; set; }
    [JsonPropertyName("avatar")] public string? Avatar { get; set; }
}

public sealed class LinkBody
{
    [JsonPropertyName("userId")] public string UserId { get; set; } = string.Empty;
    [JsonPropertyName("seerrUserId")] public int? SeerrUserId { get; set; }
}

public sealed class CanRequestBody
{
    [JsonPropertyName("userId")] public string UserId { get; set; } = string.Empty;
    [JsonPropertyName("canRequest")] public bool CanRequest { get; set; }
}

public sealed class PlayLogProfileDto
{
    [JsonPropertyName("userId")] public string UserId { get; set; } = string.Empty;
    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;
    [JsonPropertyName("imageTag")] public string? ImageTag { get; set; }
    [JsonPropertyName("plays")] public int Plays { get; set; }
    [JsonPropertyName("top10")] public bool Top10 { get; set; }
    [JsonPropertyName("history")] public bool History { get; set; }
}

public sealed class PlayLogProfileBody
{
    [JsonPropertyName("top10")] public bool? Top10 { get; set; }
    [JsonPropertyName("history")] public bool? History { get; set; }
}

#pragma warning restore SA1402, SA1649, CA1002, CA2227, SA1600
