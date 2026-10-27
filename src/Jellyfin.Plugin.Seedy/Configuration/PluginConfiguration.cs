using System;
using MediaBrowser.Model.Plugins;

namespace Jellyfin.Plugin.Seedy.Configuration;

/// <summary>
/// Everything the Seedy admin pages edit. Stored by Jellyfin as XML, so collections are arrays:
/// XmlSerializer appends to pre-filled lists but replaces arrays.
/// </summary>
public class PluginConfiguration : BasePluginConfiguration
{
    /// <summary>Gets or sets where Seedy opens: "replace" (the server address; Jellyfin's app moves to /old) or "alongside" (/seedy).</summary>
    public string OpensAt { get; set; } = "replace";

    // Branding

    /// <summary>Gets or sets the name shown next to the logo.</summary>
    public string BrandName { get; set; } = "Seedy";

    /// <summary>Gets or sets the accent colour as #rrggbb.</summary>
    public string AccentColor { get; set; } = "#ff9fff";

    /// <summary>Gets or sets the uploaded logo's original file name; empty when there is none.</summary>
    public string LogoFileName { get; set; } = string.Empty;

    /// <summary>Gets or sets a token that changes whenever the logo does, for cache busting.</summary>
    public string LogoVersion { get; set; } = "0";

    /// <summary>Gets or sets the sign-in background: "backdrops" or "plain".</summary>
    public string SignInBackground { get; set; } = "backdrops";

    // Playback defaults for new profiles

    /// <summary>Gets or sets the defaults each profile starts with.</summary>
    public PlaybackSettings Playback { get; set; } = new();

    // Home screen

    /// <summary>Gets or sets the starting row order and switches.</summary>
    public HomeRowSetting[] HomeRows { get; set; } = HomeRowSetting.Defaults();

    /// <summary>Gets or sets a value indicating whether row order adapts to each person.</summary>
    public bool AdaptRowOrder { get; set; } = true;

    /// <summary>Gets or sets when a title counts as watched for Because you watched: "finished" or "half".</summary>
    public string BecauseWatchedWhen { get; set; } = "finished";

    /// <summary>Gets or sets how far back plays can start a Because you watched row (0 = no limit).</summary>
    public int BecauseLookBackDays { get; set; } = 60;

    /// <summary>Gets or sets the smallest Because you watched row; smaller rows are dropped.</summary>
    public int BecauseMinTitles { get; set; } = 4;

    /// <summary>Gets or sets the Top 10 period in days (7 or 30).</summary>
    public int Top10PeriodDays { get; set; } = 7;

    /// <summary>Gets or sets the number of active profiles below which the Top 10 hides.</summary>
    public int Top10MinProfiles { get; set; } = 3;

    /// <summary>Gets or sets the period genres are picked from.</summary>
    public int GenreWindowDays { get; set; } = 90;

    /// <summary>Gets or sets the genres new profiles start with.</summary>
    public string[] StartingGenres { get; set; } = ["Thriller", "Comedy", "Drama", "Documentary"];

    /// <summary>Gets or sets what the hero shows: "auto", "picks" or "both".</summary>
    public string FeaturedMode { get; set; } = "both";

    /// <summary>Gets or sets the admin's featured item ids.</summary>
    public string[] FeaturedPicks { get; set; } = [];

    // Requests (Seerr)

    /// <summary>Gets or sets the Seerr address as this server reaches it.</summary>
    public string SeerrUrl { get; set; } = string.Empty;

    /// <summary>Gets or sets the Seerr API key. Never sent to browsers.</summary>
    public string SeerrApiKey { get; set; } = string.Empty;

    /// <summary>Gets or sets a value indicating whether the Discover row shows.</summary>
    public bool ShowDiscover { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether the Upcoming movies row shows.</summary>
    public bool ShowUpcomingMovies { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether Request buttons show.</summary>
    public bool ShowRequestButtons { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether search offers Seerr results when the library has none.</summary>
    public bool ShowRequestInSearch { get; set; } = true;

    /// <summary>Gets or sets where Upcoming movies come from: "requests" or "requestsAndRadarr".</summary>
    public string UpcomingSource { get; set; } = "requests";

    /// <summary>Gets or sets who can request: "everyone" (with a Seerr account) or "selected".</summary>
    public string RequestWho { get; set; } = "everyone";

    /// <summary>Gets or sets a value indicating whether 4K requests are offered.</summary>
    public bool Allow4k { get; set; }

    /// <summary>Gets or sets per-profile Seerr links and request switches.</summary>
    public SeerrProfile[] SeerrProfiles { get; set; } = [];

    // Music and book requests (Lidarr, Shelfarr, Librarr, a Readarr-API app)

    /// <summary>Gets or sets the Lidarr address as this server reaches it.</summary>
    public string LidarrUrl { get; set; } = string.Empty;

    /// <summary>Gets or sets the Lidarr API key. Never sent to browsers.</summary>
    public string LidarrApiKey { get; set; } = string.Empty;

    /// <summary>Gets or sets the Lidarr quality profile used for every request (0 = Lidarr's first).</summary>
    public int LidarrQualityProfileId { get; set; }

    /// <summary>Gets or sets the Lidarr metadata profile, which decides the release types (0 = Lidarr's first).</summary>
    public int LidarrMetadataProfileId { get; set; }

    /// <summary>Gets or sets the Lidarr root folder new artists go to (empty = Lidarr's first).</summary>
    public string LidarrRootFolder { get; set; } = string.Empty;

    /// <summary>Gets or sets what a whole-artist request monitors: "all" (existing and future albums) or "future".</summary>
    public string LidarrWholeArtist { get; set; } = "all";

    /// <summary>Gets or sets a value indicating whether Lidarr searches as soon as a request is approved.</summary>
    public bool LidarrSearchOnApprove { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether Shelfarr is used.</summary>
    public bool ShelfarrEnabled { get; set; }

    /// <summary>Gets or sets the Shelfarr address.</summary>
    public string ShelfarrUrl { get; set; } = string.Empty;

    /// <summary>Gets or sets the Shelfarr API token. Never sent to browsers.</summary>
    public string ShelfarrToken { get; set; } = string.Empty;

    /// <summary>Gets or sets a value indicating whether Librarr is used.</summary>
    public bool LibrarrEnabled { get; set; }

    /// <summary>Gets or sets the Librarr address.</summary>
    public string LibrarrUrl { get; set; } = string.Empty;

    /// <summary>Gets or sets the Librarr API key. Never sent to browsers.</summary>
    public string LibrarrApiKey { get; set; } = string.Empty;

    /// <summary>Gets or sets a value indicating whether a Readarr-API app (Bookshelf, Chaptarr…) is used.</summary>
    public bool ReadarrEnabled { get; set; }

    /// <summary>Gets or sets the Readarr-API app's address.</summary>
    public string ReadarrUrl { get; set; } = string.Empty;

    /// <summary>Gets or sets the Readarr-API app's key. Never sent to browsers.</summary>
    public string ReadarrApiKey { get; set; } = string.Empty;

    /// <summary>Gets or sets where ebook requests go: "shelfarr", "librarr", "readarr" or "off".</summary>
    public string RouteEbooks { get; set; } = "shelfarr";

    /// <summary>Gets or sets where audiobook requests go.</summary>
    public string RouteAudiobooks { get; set; } = "shelfarr";

    /// <summary>Gets or sets where comic and manga requests go.</summary>
    public string RouteComics { get; set; } = "librarr";

    /// <summary>Gets or sets per-profile limits and auto-approval for music and book requests.</summary>
    public MediaRequestProfile[] MediaRequestProfiles { get; set; } = [];

    /// <summary>Gets or sets a value indicating whether the matching Jellyfin library is scanned when a download finishes.</summary>
    public bool MediaScanOnReady { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether the person who asked is told when it's ready.</summary>
    public bool MediaNotifyOnReady { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether everyone sees who requested a title.</summary>
    public bool MediaShowRequesters { get; set; }

    // Play log

    /// <summary>Gets or sets a value indicating whether plays are recorded.</summary>
    public bool PlayLogEnabled { get; set; } = true;

    /// <summary>Gets or sets how long plays are kept, in days (0 = forever).</summary>
    public int PlayLogRetentionDays { get; set; } = 365;

    /// <summary>Gets or sets a value indicating whether the log feeds the Top 10.</summary>
    public bool UseForTop10 { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether the log feeds the viewing history page.</summary>
    public bool UseForHistory { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether the log feeds row order.</summary>
    public bool UseForRowOrder { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether the log feeds genre rows.</summary>
    public bool UseForGenres { get; set; } = true;

    /// <summary>Gets or sets per-profile play log switches.</summary>
    public PlayLogProfile[] PlayLogProfiles { get; set; } = [];

    // Automatic backups (Jellyfin 12 can create backups but not schedule them)

    /// <summary>Gets or sets a value indicating whether Seedy makes a weekly backup.</summary>
    public bool BackupScheduleEnabled { get; set; }

    /// <summary>Gets or sets the weekday for the automatic backup ("Sunday"…).</summary>
    public string BackupDayOfWeek { get; set; } = "Sunday";

    /// <summary>Gets or sets the time of day for the automatic backup, "HH:mm" server time.</summary>
    public string BackupTimeOfDay { get; set; } = "04:00";

    /// <summary>Gets or sets how many automatic backups to keep.</summary>
    public int BackupKeep { get; set; } = 4;

    /// <summary>Gets or sets whether automatic backups include the database.</summary>
    public bool BackupDatabase { get; set; } = true;

    /// <summary>Gets or sets whether automatic backups include metadata.</summary>
    public bool BackupMetadata { get; set; } = true;

    /// <summary>Gets or sets whether automatic backups include subtitles.</summary>
    public bool BackupSubtitles { get; set; } = true;

    /// <summary>Gets or sets whether automatic backups include seek previews.</summary>
    public bool BackupTrickplay { get; set; }

    /// <summary>Gets or sets the file names of backups Seedy made automatically, oldest first.</summary>
    public string[] AutomaticBackups { get; set; } = [];
}

/// <summary>Playback defaults; mirrors the frontend's PlaybackPrefs.</summary>
public class PlaybackSettings
{
    /// <summary>Gets or sets intros: "button", "auto" or "off".</summary>
    public string SkipIntro { get; set; } = "button";

    /// <summary>Gets or sets recaps: "button", "auto" or "off".</summary>
    public string SkipRecap { get; set; } = "button";

    /// <summary>Gets or sets credits: "button", "auto" or "off".</summary>
    public string SkipCredits { get; set; } = "button";

    /// <summary>Gets or sets a value indicating whether the next episode auto-plays.</summary>
    public bool AutoPlayNext { get; set; } = true;

    /// <summary>Gets or sets the Up next countdown.</summary>
    public int UpNextSeconds { get; set; } = 15;

    /// <summary>Gets or sets the end-of-movie countdown.</summary>
    public int MoviePicksSeconds { get; set; } = 15;

    /// <summary>Gets or sets the seek back step.</summary>
    public int SeekBackSeconds { get; set; } = 10;

    /// <summary>Gets or sets the seek forward step.</summary>
    public int SeekForwardSeconds { get; set; } = 30;

    /// <summary>Gets or sets the pause screen delay (0 = never).</summary>
    public int PauseScreenSeconds { get; set; } = 10;

    /// <summary>Gets or sets the maximum quality: "auto", "4k", "1080p" or "720p".</summary>
    public string MaxQuality { get; set; } = "auto";

    /// <summary>Copies the settings.</summary>
    /// <returns>A copy.</returns>
    public PlaybackSettings Clone() => (PlaybackSettings)MemberwiseClone();
}

/// <summary>One home row type in the admin's starting order.</summary>
public class HomeRowSetting
{
    /// <summary>Gets or sets the row kind (featured, continue, nextup, top10, because, genres, mymedia, latest, discover, upcomingMovies, upcomingShows, collections).</summary>
    public string Kind { get; set; } = string.Empty;

    /// <summary>Gets or sets a value indicating whether the row shows.</summary>
    public bool Enabled { get; set; } = true;

    /// <summary>Gets or sets the number of rows for multi-row kinds (because, genres); 0 otherwise.</summary>
    public int Count { get; set; }

    /// <summary>The board's starting home screen.</summary>
    /// <returns>Default rows.</returns>
    public static HomeRowSetting[] Defaults() =>
    [
        new() { Kind = "featured" },
        new() { Kind = "continue" },
        new() { Kind = "nextup" },
        new() { Kind = "top10" },
        new() { Kind = "because", Count = 6 },
        new() { Kind = "genres", Count = 4 },
        new() { Kind = "mymedia" },
        new() { Kind = "latest" },
        new() { Kind = "discover" },
        new() { Kind = "upcomingMovies" },
        new() { Kind = "upcomingShows" },
        new() { Kind = "collections", Enabled = false }
    ];
}

/// <summary>A profile's Seerr account link.</summary>
public class SeerrProfile
{
    /// <summary>Gets or sets the Jellyfin user id ("N" format).</summary>
    public string UserId { get; set; } = string.Empty;

    /// <summary>Gets or sets the linked Seerr user id; 0 when not linked by an admin.</summary>
    public int SeerrUserId { get; set; }

    /// <summary>Gets or sets a value indicating whether an admin linked it by hand.</summary>
    public bool Manual { get; set; }

    /// <summary>Gets or sets a value indicating whether the profile may request (used when RequestWho is "selected", and to switch someone off).</summary>
    public bool CanRequest { get; set; } = true;
}

/// <summary>A profile's play log switches.</summary>
public class PlayLogProfile
{
    /// <summary>Gets or sets the Jellyfin user id ("N" format).</summary>
    public string UserId { get; set; } = string.Empty;

    /// <summary>Gets or sets a value indicating whether the profile counts toward the Top 10.</summary>
    public bool Top10 { get; set; } = true;

    /// <summary>Gets or sets a value indicating whether the profile has a history page.</summary>
    public bool History { get; set; } = true;
}

/// <summary>A profile's music and book request limits.</summary>
public class MediaRequestProfile
{
    /// <summary>Gets or sets the Jellyfin user id ("N" format).</summary>
    public string UserId { get; set; } = string.Empty;

    /// <summary>Gets or sets albums per week: -1 = no limit, 0 = can't request.</summary>
    public int MusicPerWeek { get; set; } = 5;

    /// <summary>Gets or sets books per month: -1 = no limit, 0 = can't request. An ebook and its audiobook count as two.</summary>
    public int BooksPerMonth { get; set; } = 8;

    /// <summary>Gets or sets a value indicating whether the profile's requests are approved without an admin.</summary>
    public bool AutoApprove { get; set; }
}
