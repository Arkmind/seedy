using System;
using System.Collections.Generic;
using System.Linq;
using System.Text.RegularExpressions;
using Jellyfin.Plugin.Seedy.Api;
using Jellyfin.Plugin.Seedy.Configuration;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>Converts between the stored configuration and the admin API's shape, validating as it goes.</summary>
public static partial class ConfigMapper
{
    private static readonly HashSet<string> RowKinds = new(StringComparer.Ordinal)
    {
        "featured", "continue", "nextup", "top10", "because", "genres", "mymedia", "latest", "discover", "upcomingMovies", "upcomingShows", "collections"
    };

    private static readonly HashSet<string> SkipModes = new(StringComparer.Ordinal) { "button", "auto", "off" };

    private static readonly HashSet<string> Qualities = new(StringComparer.Ordinal) { "auto", "4k", "1080p", "720p" };

    [GeneratedRegex("^#[0-9a-fA-F]{6}$")]
    private static partial Regex HexColor();

    /// <summary>Gets the stored configuration (defaults when the plugin isn't loaded).</summary>
    public static PluginConfiguration Current => Plugin.Instance?.Configuration ?? new PluginConfiguration();

    /// <summary>Branding for the client.</summary>
    /// <param name="c">Configuration.</param>
    /// <param name="includeFileName">Whether to include the logo file name (admin only).</param>
    /// <returns>Branding.</returns>
    public static BrandingDto Branding(PluginConfiguration c, bool includeFileName = false) => new()
    {
        Name = string.IsNullOrWhiteSpace(c.BrandName) ? "Seedy" : c.BrandName,
        Accent = HexColor().IsMatch(c.AccentColor) ? c.AccentColor.ToLowerInvariant() : "#ff9fff",
        HasLogo = !string.IsNullOrEmpty(c.LogoFileName),
        LogoVersion = c.LogoVersion,
        SignInBackground = c.SignInBackground == "plain" ? "plain" : "backdrops",
        LogoFileName = includeFileName && !string.IsNullOrEmpty(c.LogoFileName) ? c.LogoFileName : null
    };

    /// <summary>Converts playback settings to the wire shape.</summary>
    /// <param name="p">Settings.</param>
    /// <returns>Wire shape.</returns>
    public static PlaybackPrefsDto ToDto(PlaybackSettings p) => new()
    {
        SkipIntro = p.SkipIntro,
        SkipRecap = p.SkipRecap,
        SkipCredits = p.SkipCredits,
        AutoPlayNext = p.AutoPlayNext,
        UpNextSeconds = p.UpNextSeconds,
        MoviePicksSeconds = p.MoviePicksSeconds,
        SeekBackSeconds = p.SeekBackSeconds,
        SeekForwardSeconds = p.SeekForwardSeconds,
        PauseScreenSeconds = p.PauseScreenSeconds,
        MaxQuality = p.MaxQuality
    };

    /// <summary>Validates playback preferences, clamping numbers and falling back on unknown values.</summary>
    /// <param name="p">Incoming preferences.</param>
    /// <returns>Clean preferences.</returns>
    public static PlaybackPrefsDto Clean(PlaybackPrefsDto p) => new()
    {
        SkipIntro = SkipModes.Contains(p.SkipIntro) ? p.SkipIntro : "button",
        SkipRecap = SkipModes.Contains(p.SkipRecap) ? p.SkipRecap : "button",
        SkipCredits = SkipModes.Contains(p.SkipCredits) ? p.SkipCredits : "button",
        AutoPlayNext = p.AutoPlayNext,
        UpNextSeconds = Math.Clamp(p.UpNextSeconds, 5, 60),
        MoviePicksSeconds = Math.Clamp(p.MoviePicksSeconds, 5, 60),
        SeekBackSeconds = Math.Clamp(p.SeekBackSeconds, 5, 60),
        SeekForwardSeconds = Math.Clamp(p.SeekForwardSeconds, 5, 120),
        PauseScreenSeconds = Math.Clamp(p.PauseScreenSeconds, 0, 600),
        MaxQuality = Qualities.Contains(p.MaxQuality) ? p.MaxQuality : "auto"
    };

    private static PlaybackSettings FromDto(PlaybackPrefsDto p)
    {
        var c = Clean(p);
        return new PlaybackSettings
        {
            SkipIntro = c.SkipIntro,
            SkipRecap = c.SkipRecap,
            SkipCredits = c.SkipCredits,
            AutoPlayNext = c.AutoPlayNext,
            UpNextSeconds = c.UpNextSeconds,
            MoviePicksSeconds = c.MoviePicksSeconds,
            SeekBackSeconds = c.SeekBackSeconds,
            SeekForwardSeconds = c.SeekForwardSeconds,
            PauseScreenSeconds = c.PauseScreenSeconds,
            MaxQuality = c.MaxQuality
        };
    }

    /// <summary>Gets the home rows in order, repairing missing or unknown kinds.</summary>
    /// <param name="c">Configuration.</param>
    /// <returns>Rows; Featured and Continue watching always first.</returns>
    public static List<HomeRowSetting> HomeRows(PluginConfiguration c)
    {
        var rows = (c.HomeRows ?? []).Where(r => RowKinds.Contains(r.Kind)).GroupBy(r => r.Kind).Select(g => g.First()).ToList();
        foreach (var d in HomeRowSetting.Defaults())
        {
            if (rows.All(r => r.Kind != d.Kind))
            {
                rows.Add(d);
            }
        }

        // Featured and Continue watching are fixed in place.
        rows.RemoveAll(r => r.Kind is "featured" or "continue");
        rows.Insert(0, new HomeRowSetting { Kind = "continue", Enabled = true });
        rows.Insert(0, new HomeRowSetting { Kind = "featured", Enabled = true });
        foreach (var r in rows)
        {
            r.Count = r.Kind switch
            {
                "because" => Math.Clamp(r.Count == 0 ? 6 : r.Count, 1, 10),
                "genres" => Math.Clamp(r.Count == 0 ? 4 : r.Count, 1, 8),
                _ => 0
            };
        }

        return rows;
    }

    /// <summary>Builds the admin API view of the configuration.</summary>
    /// <param name="c">Configuration.</param>
    /// <returns>Admin configuration.</returns>
    public static AdminConfigDto ToAdmin(PluginConfiguration c) => new()
    {
        OpensAt = c.OpensAt == "replace" ? "replace" : "alongside",
        Branding = Branding(c, includeFileName: true),
        PlaybackDefaults = ToDto(c.Playback ?? new PlaybackSettings()),
        Home = new AdminHomeDto
        {
            Rows = HomeRows(c).Select(r => new HomeRowSettingDto { Kind = r.Kind, Enabled = r.Enabled, Count = r.Count > 0 ? r.Count : null }).ToList(),
            AdaptOrder = c.AdaptRowOrder,
            Because = new BecauseDto { WatchedWhen = c.BecauseWatchedWhen == "half" ? "half" : "finished", LookBackDays = c.BecauseLookBackDays, MinTitles = c.BecauseMinTitles },
            Top10 = new Top10SettingsDto { PeriodDays = c.Top10PeriodDays == 30 ? 30 : 7, MinProfiles = c.Top10MinProfiles },
            Genres = new GenreSettingsDto { WindowDays = c.GenreWindowDays, Starting = (c.StartingGenres ?? []).ToList() },
            Featured = new FeaturedSettingsDto { Mode = c.FeaturedMode, Picks = (c.FeaturedPicks ?? []).ToList() }
        },
        Requests = new AdminRequestsDto
        {
            Url = c.SeerrUrl,
            ApiKeyHint = string.IsNullOrEmpty(c.SeerrApiKey) ? null : c.SeerrApiKey[^Math.Min(4, c.SeerrApiKey.Length)..],
            ShowDiscover = c.ShowDiscover,
            ShowUpcomingMovies = c.ShowUpcomingMovies,
            ShowRequestButtons = c.ShowRequestButtons,
            ShowRequestInSearch = c.ShowRequestInSearch,
            UpcomingSource = c.UpcomingSource == "requestsAndRadarr" ? "requestsAndRadarr" : "requests",
            Who = c.RequestWho == "selected" ? "selected" : "everyone",
            Allow4k = c.Allow4k
        },
        PlayLog = new AdminPlayLogDto
        {
            Enabled = c.PlayLogEnabled,
            RetentionDays = c.PlayLogRetentionDays,
            UseTop10 = c.UseForTop10,
            UseHistory = c.UseForHistory,
            UseRowOrder = c.UseForRowOrder,
            UseGenres = c.UseForGenres
        }
    };

    /// <summary>Applies an admin API update onto the stored configuration. Keys, logos and profile tables are untouched.</summary>
    /// <param name="c">Configuration to update.</param>
    /// <param name="a">Incoming admin configuration.</param>
    public static void Apply(PluginConfiguration c, AdminConfigDto a)
    {
        c.OpensAt = a.OpensAt == "replace" ? "replace" : "alongside";

        var name = (a.Branding.Name ?? string.Empty).Trim();
        c.BrandName = name.Length == 0 ? "Seedy" : name[..Math.Min(40, name.Length)];
        if (HexColor().IsMatch(a.Branding.Accent ?? string.Empty))
        {
            c.AccentColor = a.Branding.Accent!.ToLowerInvariant();
        }

        c.SignInBackground = a.Branding.SignInBackground == "plain" ? "plain" : "backdrops";
        c.Playback = FromDto(a.PlaybackDefaults ?? new PlaybackPrefsDto());

        var rows = new List<HomeRowSetting>();
        foreach (var r in a.Home.Rows ?? [])
        {
            if (RowKinds.Contains(r.Kind) && rows.All(x => x.Kind != r.Kind))
            {
                rows.Add(new HomeRowSetting { Kind = r.Kind, Enabled = r.Enabled, Count = r.Count ?? 0 });
            }
        }

        c.HomeRows = rows.ToArray();
        c.HomeRows = HomeRows(c).ToArray();
        c.AdaptRowOrder = a.Home.AdaptOrder;
        c.BecauseWatchedWhen = a.Home.Because.WatchedWhen == "half" ? "half" : "finished";
        c.BecauseLookBackDays = Math.Clamp(a.Home.Because.LookBackDays, 0, 3650);
        c.BecauseMinTitles = Math.Clamp(a.Home.Because.MinTitles, 1, 12);
        c.Top10PeriodDays = a.Home.Top10.PeriodDays == 30 ? 30 : 7;
        c.Top10MinProfiles = Math.Clamp(a.Home.Top10.MinProfiles, 1, 50);
        c.GenreWindowDays = Math.Clamp(a.Home.Genres.WindowDays, 7, 3650);
        c.StartingGenres = (a.Home.Genres.Starting ?? []).Select(g => g.Trim()).Where(g => g.Length > 0).Distinct(StringComparer.OrdinalIgnoreCase).Take(30).ToArray();
        c.FeaturedMode = a.Home.Featured.Mode is "auto" or "picks" ? a.Home.Featured.Mode : "both";
        c.FeaturedPicks = (a.Home.Featured.Picks ?? []).Where(p => Guid.TryParse(p, out _)).Distinct().Take(30).ToArray();

        c.SeerrUrl = (a.Requests.Url ?? string.Empty).Trim().TrimEnd('/');
        c.ShowDiscover = a.Requests.ShowDiscover;
        c.ShowUpcomingMovies = a.Requests.ShowUpcomingMovies;
        c.ShowRequestButtons = a.Requests.ShowRequestButtons;
        c.ShowRequestInSearch = a.Requests.ShowRequestInSearch;
        c.UpcomingSource = a.Requests.UpcomingSource == "requestsAndRadarr" ? "requestsAndRadarr" : "requests";
        c.RequestWho = a.Requests.Who == "selected" ? "selected" : "everyone";
        c.Allow4k = a.Requests.Allow4k;

        c.PlayLogEnabled = a.PlayLog.Enabled;
        c.PlayLogRetentionDays = Math.Clamp(a.PlayLog.RetentionDays, 0, 3650);
        c.UseForTop10 = a.PlayLog.UseTop10;
        c.UseForHistory = a.PlayLog.UseHistory;
        c.UseForRowOrder = a.PlayLog.UseRowOrder;
        c.UseForGenres = a.PlayLog.UseGenres;
    }

    /// <summary>Gets (or creates) a profile's play log switches.</summary>
    /// <param name="c">Configuration.</param>
    /// <param name="userId">User id ("N").</param>
    /// <returns>Switches.</returns>
    public static PlayLogProfile PlayLogProfile(PluginConfiguration c, string userId)
        => (c.PlayLogProfiles ?? []).FirstOrDefault(p => string.Equals(p.UserId, userId, StringComparison.OrdinalIgnoreCase)) ?? new PlayLogProfile { UserId = userId };

    /// <summary>Gets (or creates) a profile's Seerr link.</summary>
    /// <param name="c">Configuration.</param>
    /// <param name="userId">User id ("N").</param>
    /// <returns>Link.</returns>
    public static SeerrProfile SeerrProfile(PluginConfiguration c, string userId)
        => (c.SeerrProfiles ?? []).FirstOrDefault(p => string.Equals(p.UserId, userId, StringComparison.OrdinalIgnoreCase)) ?? new SeerrProfile { UserId = userId };

    /// <summary>Stores a profile's play log switches.</summary>
    /// <param name="c">Configuration.</param>
    /// <param name="profile">Switches.</param>
    public static void Upsert(PluginConfiguration c, PlayLogProfile profile)
    {
        var list = (c.PlayLogProfiles ?? []).Where(p => !string.Equals(p.UserId, profile.UserId, StringComparison.OrdinalIgnoreCase)).ToList();
        list.Add(profile);
        c.PlayLogProfiles = list.ToArray();
    }

    /// <summary>Stores a profile's Seerr link.</summary>
    /// <param name="c">Configuration.</param>
    /// <param name="profile">Link.</param>
    public static void Upsert(PluginConfiguration c, SeerrProfile profile)
    {
        var list = (c.SeerrProfiles ?? []).Where(p => !string.Equals(p.UserId, profile.UserId, StringComparison.OrdinalIgnoreCase)).ToList();
        list.Add(profile);
        c.SeerrProfiles = list.ToArray();
    }
}
