using System;
using System.Collections.Generic;
using System.Linq;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Plugin.Seedy.Api;
using Jellyfin.Plugin.Seedy.Configuration;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>Music and book request settings.</summary>
public static partial class ConfigMapper
{
    /// <summary>The apps Seedy can send book requests to.</summary>
    public static readonly string[] BookServices = ["shelfarr", "librarr", "readarr"];

    private static string Hint(string key) => string.IsNullOrEmpty(key) ? null! : key[^Math.Min(4, key.Length)..];

    private static string CleanUrl(string? url)
    {
        var v = (url ?? string.Empty).Trim().TrimEnd('/');
        return v;
    }

    private static string Route(string? value, string fallback) => value is "shelfarr" or "librarr" or "readarr" or "off" ? value : fallback;

    /// <summary>Builds the admin view of music and book requests.</summary>
    /// <param name="c">Configuration.</param>
    /// <returns>Settings.</returns>
    public static AdminMediaDto ToAdminMedia(PluginConfiguration c) => new()
    {
        Lidarr = new AdminLidarrDto
        {
            Url = c.LidarrUrl,
            ApiKeyHint = string.IsNullOrEmpty(c.LidarrApiKey) ? null : Hint(c.LidarrApiKey),
            QualityProfileId = c.LidarrQualityProfileId,
            MetadataProfileId = c.LidarrMetadataProfileId,
            RootFolder = c.LidarrRootFolder,
            WholeArtist = c.LidarrWholeArtist == "future" ? "future" : "all",
            SearchOnApprove = c.LidarrSearchOnApprove
        },
        Shelfarr = new AdminBookAppDto { Enabled = c.ShelfarrEnabled, Url = c.ShelfarrUrl, ApiKeyHint = string.IsNullOrEmpty(c.ShelfarrToken) ? null : Hint(c.ShelfarrToken) },
        Librarr = new AdminBookAppDto { Enabled = c.LibrarrEnabled, Url = c.LibrarrUrl, ApiKeyHint = string.IsNullOrEmpty(c.LibrarrApiKey) ? null : Hint(c.LibrarrApiKey) },
        Readarr = new AdminBookAppDto { Enabled = c.ReadarrEnabled, Url = c.ReadarrUrl, ApiKeyHint = string.IsNullOrEmpty(c.ReadarrApiKey) ? null : Hint(c.ReadarrApiKey) },
        Routes = new AdminBookRoutesDto
        {
            Ebooks = Route(c.RouteEbooks, "shelfarr"),
            Audiobooks = Route(c.RouteAudiobooks, "shelfarr"),
            Comics = Route(c.RouteComics, "librarr")
        },
        ScanOnReady = c.MediaScanOnReady,
        NotifyOnReady = c.MediaNotifyOnReady,
        ShowRequesters = c.MediaShowRequesters
    };

    /// <summary>Applies the admin's music and book request settings. Keys are saved separately.</summary>
    /// <param name="c">Configuration.</param>
    /// <param name="m">Incoming settings.</param>
    public static void ApplyMedia(PluginConfiguration c, AdminMediaDto m)
    {
        c.LidarrUrl = CleanUrl(m.Lidarr.Url);
        c.LidarrQualityProfileId = Math.Max(0, m.Lidarr.QualityProfileId);
        c.LidarrMetadataProfileId = Math.Max(0, m.Lidarr.MetadataProfileId);
        c.LidarrRootFolder = (m.Lidarr.RootFolder ?? string.Empty).Trim();
        c.LidarrWholeArtist = m.Lidarr.WholeArtist == "future" ? "future" : "all";
        c.LidarrSearchOnApprove = m.Lidarr.SearchOnApprove;
        c.ShelfarrEnabled = m.Shelfarr.Enabled;
        c.ShelfarrUrl = CleanUrl(m.Shelfarr.Url);
        c.LibrarrEnabled = m.Librarr.Enabled;
        c.LibrarrUrl = CleanUrl(m.Librarr.Url);
        c.ReadarrEnabled = m.Readarr.Enabled;
        c.ReadarrUrl = CleanUrl(m.Readarr.Url);
        c.RouteEbooks = Route(m.Routes.Ebooks, "shelfarr");
        c.RouteAudiobooks = Route(m.Routes.Audiobooks, "shelfarr");
        c.RouteComics = Route(m.Routes.Comics, "librarr");
        c.MediaScanOnReady = m.ScanOnReady;
        c.MediaNotifyOnReady = m.NotifyOnReady;
        c.MediaShowRequesters = m.ShowRequesters;
    }

    /// <summary>Gets whether Lidarr has an address and key.</summary>
    /// <param name="c">Configuration.</param>
    /// <returns>True when music requests can work.</returns>
    public static bool LidarrReady(PluginConfiguration c) => !string.IsNullOrWhiteSpace(c.LidarrUrl) && !string.IsNullOrWhiteSpace(c.LidarrApiKey);

    /// <summary>Gets whether a book app is switched on and has an address and key.</summary>
    /// <param name="c">Configuration.</param>
    /// <param name="service">shelfarr, librarr or readarr.</param>
    /// <returns>True when it can take requests.</returns>
    public static bool BookAppReady(PluginConfiguration c, string service) => service switch
    {
        "shelfarr" => c.ShelfarrEnabled && !string.IsNullOrWhiteSpace(c.ShelfarrUrl) && !string.IsNullOrWhiteSpace(c.ShelfarrToken),
        "librarr" => c.LibrarrEnabled && !string.IsNullOrWhiteSpace(c.LibrarrUrl) && !string.IsNullOrWhiteSpace(c.LibrarrApiKey),
        "readarr" => c.ReadarrEnabled && !string.IsNullOrWhiteSpace(c.ReadarrUrl) && !string.IsNullOrWhiteSpace(c.ReadarrApiKey),
        _ => false
    };

    /// <summary>Where a book format goes, when that app is ready.</summary>
    /// <param name="c">Configuration.</param>
    /// <param name="format">ebook, audiobook or comic.</param>
    /// <returns>The app, or null when the format can't be requested.</returns>
    public static string? BookRoute(PluginConfiguration c, string format)
    {
        var route = format switch
        {
            "ebook" => Route(c.RouteEbooks, "shelfarr"),
            "audiobook" => Route(c.RouteAudiobooks, "shelfarr"),
            "comic" => Route(c.RouteComics, "librarr"),
            _ => "off"
        };
        return route != "off" && BookAppReady(c, route) ? route : null;
    }

    /// <summary>The book formats people can request now.</summary>
    /// <param name="c">Configuration.</param>
    /// <returns>Formats.</returns>
    public static List<string> BookFormats(PluginConfiguration c) => new[] { "ebook", "audiobook", "comic" }.Where(f => BookRoute(c, f) is not null).ToList();

    /// <summary>Gets a profile's music and book limits, with defaults by role when the admin never set them.</summary>
    /// <param name="c">Configuration.</param>
    /// <param name="user">The profile.</param>
    /// <returns>Limits.</returns>
    public static MediaRequestProfile MediaProfile(PluginConfiguration c, User user)
    {
        var uid = user.Id.ToString("N");
        var stored = (c.MediaRequestProfiles ?? []).FirstOrDefault(p => string.Equals(p.UserId, uid, StringComparison.OrdinalIgnoreCase));
        if (stored is not null)
        {
            return stored;
        }

        if (user.IsAdmin())
        {
            return new MediaRequestProfile { UserId = uid, MusicPerWeek = -1, BooksPerMonth = -1, AutoApprove = true };
        }

        // Profiles with a maturity limit start without music (album art and lyrics carry no ratings) and fewer books.
        return user.MaxParentalRatingScore is not null
            ? new MediaRequestProfile { UserId = uid, MusicPerWeek = 0, BooksPerMonth = 4, AutoApprove = false }
            : new MediaRequestProfile { UserId = uid, MusicPerWeek = 5, BooksPerMonth = 8, AutoApprove = false };
    }

    /// <summary>Stores a profile's music and book limits.</summary>
    /// <param name="c">Configuration.</param>
    /// <param name="profile">Limits.</param>
    public static void Upsert(PluginConfiguration c, MediaRequestProfile profile)
    {
        var list = (c.MediaRequestProfiles ?? []).Where(p => !string.Equals(p.UserId, profile.UserId, StringComparison.OrdinalIgnoreCase)).ToList();
        list.Add(profile);
        c.MediaRequestProfiles = list.ToArray();
    }
}
