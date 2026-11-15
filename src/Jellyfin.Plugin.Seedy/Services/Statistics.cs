using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Database.Implementations.Enums;
using Jellyfin.Plugin.Seedy.Api;
using Jellyfin.Plugin.Seedy.Data;
using MediaBrowser.Controller.Configuration;
using MediaBrowser.Controller.Devices;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Audio;
using MediaBrowser.Controller.Library;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>The filters of the Statistics page.</summary>
/// <param name="Range">7d, 30d, 12m or all.</param>
/// <param name="Zone">The viewer's time zone.</param>
/// <param name="UserId">One profile, or null for everyone.</param>
/// <param name="LibraryId">One library ("livetv" for Live TV), or null for all.</param>
/// <param name="Device">One device name, or null for all.</param>
public sealed record StatsQuery(string Range, TimeZoneInfo Zone, string? UserId, string? LibraryId, string? Device);

/// <summary>
/// Builds the admin Statistics pages from the play log. A play counts once it played for two minutes; its watch time is
/// the time it actually played (pauses left out), estimated from its start and stop for plays logged before Seedy counted it.
/// </summary>
public sealed partial class Statistics
{
    /// <summary>Plays shorter than this are left out.</summary>
    public static readonly TimeSpan MinPlay = TimeSpan.FromMinutes(2);

    // How long a play may have been paused on top of what it played, at least.
    private static readonly TimeSpan PauseRoom = TimeSpan.FromMinutes(15);

    // Songs played back to back on one device make one listening session on the day page.
    private static readonly TimeSpan SongGap = TimeSpan.FromMinutes(3);

    private static readonly string[] Kinds = ["show", "movie", "music", "livetv", "book", "other"];

    private static readonly HashSet<string> VideoReasons = new(StringComparer.Ordinal)
    {
        "VideoCodecNotSupported", "VideoProfileNotSupported", "VideoLevelNotSupported", "VideoResolutionNotSupported",
        "VideoBitDepthNotSupported", "VideoFramerateNotSupported", "RefFramesNotSupported", "AnamorphicVideoNotSupported",
        "InterlacedVideoNotSupported", "VideoRangeTypeNotSupported", "VideoCodecTagNotSupported", "UnknownVideoStreamInfo"
    };

    private static readonly HashSet<string> BitrateReasons = new(StringComparer.Ordinal)
    {
        "ContainerBitrateExceedsLimit", "VideoBitrateNotSupported", "AudioBitrateNotSupported"
    };

    private static readonly HashSet<string> AudioReasons = new(StringComparer.Ordinal)
    {
        "AudioCodecNotSupported", "AudioChannelsNotSupported", "AudioProfileNotSupported", "AudioSampleRateNotSupported",
        "AudioBitDepthNotSupported", "AudioIsExternal", "SecondaryAudioNotSupported", "UnknownAudioStreamInfo"
    };

    private readonly SeedyDatabase _db;
    private readonly IUserManager _users;
    private readonly ILibraryManager _library;
    private readonly IServerConfigurationManager _config;
    private readonly HomeService _home;
    private readonly IDeviceManager _devices;

    /// <summary>
    /// Initializes a new instance of the <see cref="Statistics"/> class.
    /// </summary>
    /// <param name="db">Database.</param>
    /// <param name="users">User manager.</param>
    /// <param name="library">Library manager.</param>
    /// <param name="config">Server configuration.</param>
    /// <param name="home">Home service, for item DTOs.</param>
    /// <param name="devices">Jellyfin's devices, for the app each one runs now.</param>
    public Statistics(SeedyDatabase db, IUserManager users, ILibraryManager library, IServerConfigurationManager config, HomeService home, IDeviceManager devices)
    {
        _devices = devices;
        _db = db;
        _users = users;
        _library = library;
        _config = config;
        _home = home;
    }

    /// <summary>One play as Statistics sees it.</summary>
    private sealed record Row(PlayRecord Play, string Kind, long Watched, DateTime Start, DateTime End);

    /// <summary>Reads a time zone id, falling back to the server's own.</summary>
    /// <param name="id">IANA or Windows id.</param>
    /// <returns>The zone.</returns>
    public static TimeZoneInfo Zone(string? id)
    {
        if (!string.IsNullOrWhiteSpace(id) && TimeZoneInfo.TryFindSystemTimeZoneById(id, out var zone))
        {
            return zone;
        }

        return TimeZoneInfo.Local;
    }

    /// <summary>The kind a play counts as: show, movie, music, livetv, book or other.</summary>
    /// <param name="itemType">Item type.</param>
    /// <returns>Kind.</returns>
    public static string KindOf(string itemType) => itemType switch
    {
        "Episode" => "show",
        "Movie" => "movie",
        "Audio" => "music",
        "AudioBook" or "Book" => "book",
        "TvChannel" or "LiveTvChannel" or "LiveTvProgram" => "livetv",
        _ => "other"
    };

    private static long WatchedOf(PlayRecord p)
    {
        if (p.WatchedMs is long w)
        {
            return w;
        }

        // Logged before Seedy counted playing time: the time between start and stop, at most the runtime.
        if (p.StoppedAt is not { } stop)
        {
            return 0;
        }

        var span = (long)(stop - p.StartedAt).TotalMilliseconds;
        return p.RuntimeTicks > 0 ? Math.Min(span, p.RuntimeTicks / TimeSpan.TicksPerMillisecond) : span;
    }

    // A play the client never stopped is only closed when its session times out, hours later. Its stream is taken to
    // end once it had played what it played, plus room for pauses, so it doesn't stay open all night.
    private static Row ToRow(PlayRecord p)
    {
        var watched = Math.Max(0, WatchedOf(p));
        var span = p.StoppedAt is { } stop ? Math.Max(0, (stop - p.StartedAt).TotalMilliseconds) : watched;
        var open = Math.Min(span, watched + Math.Max(PauseRoom.TotalMilliseconds, watched / 2.0));
        return new Row(p, KindOf(p.ItemType), watched, p.StartedAt, p.StartedAt.AddMilliseconds(open));
    }

    private List<Row> Load(DateTime from, DateTime to, StatsQuery? q)
    {
        var min = (long)MinPlay.TotalMilliseconds;
        return _db.PlaysBetween(from, to)
            .Select(ToRow)
            .Where(r => r.Watched >= min)
            .Where(r => q is null || Matches(r, q))
            .ToList();
    }

    private static bool Matches(Row r, StatsQuery q)
        => (q.UserId is null || string.Equals(r.Play.UserId, q.UserId, StringComparison.OrdinalIgnoreCase))
            && (q.Device is null || string.Equals(r.Play.DeviceName, q.Device, StringComparison.Ordinal))
            && (q.LibraryId is null || (q.LibraryId == "livetv" ? r.Kind == "livetv" : string.Equals(r.Play.LibraryId, q.LibraryId, StringComparison.OrdinalIgnoreCase)));

    // Profiles in the order Seedy's admin lists them (administrators first), which also picks their avatar colour.
    private List<User> Profiles()
        => _users.InAdminOrder().ToList();

    private static bool Enabled(User u) => !u.HasPermission(PermissionKind.IsDisabled);

    private static DateTime UtcOf(DateTime localDate, TimeZoneInfo zone)
    {
        var local = DateTime.SpecifyKind(localDate.Date, DateTimeKind.Unspecified);
        // Midnight can fall in a DST gap; step forward until it exists.
        while (zone.IsInvalidTime(local))
        {
            local = local.AddMinutes(30);
        }

        return TimeZoneInfo.ConvertTimeToUtc(local, zone);
    }

    private static DateTime LocalOf(DateTime utc, TimeZoneInfo zone) => TimeZoneInfo.ConvertTimeFromUtc(DateTime.SpecifyKind(utc, DateTimeKind.Utc), zone);

    private static string Day(DateTime d) => d.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);

    private static DateTime WeekStart(DateTime d) => d.Date.AddDays(-(((int)d.DayOfWeek + 6) % 7));

    /// <summary>Builds the Statistics page.</summary>
    /// <param name="q">Filters.</param>
    /// <param name="admin">The admin asking, for item details.</param>
    /// <returns>The page's numbers.</returns>
    public StatsDto Build(StatsQuery q, User admin)
    {
        var zone = q.Zone;
        var today = LocalOf(DateTime.UtcNow, zone).Date;
        var from = From(q.Range, today, zone);
        DateTime? prevFrom = q.Range switch
        {
            "7d" => today.AddDays(-13),
            "12m" => today.AddMonths(-24).AddDays(1),
            "all" => null,
            _ => today.AddDays(-59)
        };
        if (from > today)
        {
            from = today;
        }

        var spanDays = (today - from).TotalDays + 1;
        var bucket = q.Range switch
        {
            "7d" or "30d" => "day",
            "12m" => "week",
            _ => spanDays <= 62 ? "day" : spanDays <= 550 ? "week" : "month"
        };

        var fromUtc = UtcOf(from, zone);
        var toUtc = UtcOf(today.AddDays(1), zone);
        var all = Load(fromUtc, toUtc, null);
        var rows = all.Where(r => Matches(r, q)).ToList();
        // Compare with the period before only when the log already covered all of it.
        var firstPlay = _db.FirstPlayAt() is { } fp ? LocalOf(fp, zone).Date : (DateTime?)null;
        var prev = prevFrom is { } pf && firstPlay <= pf ? Load(UtcOf(pf, zone), fromUtc, q) : null;

        var profiles = Profiles();
        var index = profiles.Select((u, i) => (u, i)).ToDictionary(x => x.u.Id.ToString("N"), x => x.i, StringComparer.OrdinalIgnoreCase);
        var enabled = profiles.Where(Enabled).ToList();
        var watchers = rows.Select(r => r.Play.UserId).ToHashSet(StringComparer.OrdinalIgnoreCase);

        var dto = new StatsDto
        {
            Range = q.Range,
            Bucket = bucket,
            From = Day(from),
            To = Day(today),
            Today = Day(today),
            RetentionDays = ConfigMapper.Current.PlayLogRetentionDays,
            FirstPlay = firstPlay is { } f ? Day(f) : null
        };

        // ── Headline numbers ──
        var k = dto.Kpis;
        k.WatchMs = rows.Sum(r => r.Watched);
        k.Plays = rows.Count;
        k.PrevWatchMs = prev?.Sum(r => r.Watched);
        k.PrevPlays = prev?.Count;
        if (q.UserId is null)
        {
            k.Viewers = enabled.Count(u => watchers.Contains(u.Id.ToString("N")));
            k.TotalUsers = enabled.Count;
            k.Missing = enabled.Where(u => !watchers.Contains(u.Id.ToString("N"))).Select(u => u.Username).ToList();
        }
        else
        {
            k.Viewers = watchers.Count;
            k.TotalUsers = enabled.Count;
        }

        k.Peak = Peak(rows);
        k.TranscodePct = TranscodeShare(rows);
        k.PrevTranscodePct = prev is null ? null : TranscodeShare(prev);

        // ── Watch time per day, week or month ──
        DateTime BucketOf(DateTime localDay) => bucket switch
        {
            "week" => WeekStart(localDay),
            "month" => new DateTime(localDay.Year, localDay.Month, 1, 0, 0, 0, DateTimeKind.Unspecified),
            _ => localDay.Date
        };

        var buckets = new SortedDictionary<DateTime, StatsBucketDto>();
        for (var d = BucketOf(from); d <= today; d = bucket switch { "week" => d.AddDays(7), "month" => d.AddMonths(1), _ => d.AddDays(1) })
        {
            buckets[d] = new StatsBucketDto { Start = Day(d), Kinds = Kinds.ToDictionary(x => x, _ => 0L) };
        }

        foreach (var r in rows)
        {
            var b = BucketOf(LocalOf(r.Start, zone));
            if (!buckets.TryGetValue(b, out var dtoBucket))
            {
                continue;
            }

            dtoBucket.WatchMs += r.Watched;
            dtoBucket.Plays++;
            dtoBucket.Kinds[r.Kind] += r.Watched;
        }

        dto.Buckets = buckets.Values.ToList();

        // ── How it played ──
        dto.Methods = new Dictionary<string, int>(StringComparer.Ordinal)
        {
            ["directPlay"] = rows.Count(r => r.Play.PlayMethod == "DirectPlay"),
            ["directStream"] = rows.Count(r => r.Play.PlayMethod == "DirectStream"),
            ["clientDecode"] = rows.Count(r => r.Play.PlayMethod == "ClientDecode"),
            ["transcode"] = rows.Count(r => r.Play.PlayMethod == "Transcode")
        };
        dto.Reasons = Reasons(rows.Where(r => r.Play.PlayMethod == "Transcode").ToList());

        // ── What people play ──
        dto.Kinds = Kinds.ToDictionary(x => x, x => rows.Where(r => r.Kind == x).Sum(r => r.Watched), StringComparer.Ordinal);

        // ── When people watch ──
        (dto.Heat, dto.HeatViewers) = Heat(rows, zone, from, today);

        // ── Viewers ──
        var byUser = rows.GroupBy(r => r.Play.UserId, StringComparer.OrdinalIgnoreCase).ToDictionary(g => g.Key, g => g.ToList(), StringComparer.OrdinalIgnoreCase);
        dto.Viewers = profiles
            .Where(u => (q.UserId is null && Enabled(u)) || byUser.ContainsKey(u.Id.ToString("N")))
            .Select(u =>
            {
                var uid = u.Id.ToString("N");
                var mine = byUser.GetValueOrDefault(uid) ?? [];
                var devices = mine.GroupBy(r => r.Play.DeviceName ?? string.Empty).OrderByDescending(g => g.Sum(r => r.Watched)).ToList();
                return new StatsViewerDto
                {
                    UserId = uid,
                    Name = u.Username,
                    ImageTag = _users.GetUserDto(u).PrimaryImageTag,
                    Index = index[uid],
                    WatchMs = mine.Sum(r => r.Watched),
                    Plays = mine.Count,
                    Device = devices.FirstOrDefault()?.Key is { Length: > 0 } dev ? dev : null,
                    DeviceCount = devices.Count,
                    Enabled = Enabled(u)
                };
            })
            .OrderByDescending(v => v.WatchMs)
            .ThenBy(v => v.Index)
            .ToList();

        // ── Devices and apps ──
        var apps = AppsByDevice();
        dto.Devices = rows
            .Select(r => (Row: r, Family: Family(r.Play.Client, r.Play.DeviceName, apps)))
            .GroupBy(x => x.Family.Key, StringComparer.Ordinal)
            .Select(g => new StatsFamilyDto
            {
                Key = g.Key,
                Name = g.First().Family.Name,
                WatchMs = g.Sum(x => x.Row.Watched),
                Apps = g.GroupBy(x => x.Family.App, StringComparer.Ordinal).OrderByDescending(a => a.Sum(x => x.Row.Watched)).Select(a => a.Key).Take(3).ToList()
            })
            .OrderByDescending(f => f.WatchMs)
            .ToList();

        // ── Most watched ──
        dto.Top = Top(rows, admin);

        // ── Filter choices ──
        dto.Filters.Users = profiles.Select(u => new StatsOptionDto { Id = u.Id.ToString("N"), Name = u.Username }).ToList();
        dto.Filters.Libraries = _library.GetVirtualFolders()
            .Where(v => !string.IsNullOrEmpty(v.ItemId))
            .Select(v => new StatsOptionDto { Id = Guid.TryParse(v.ItemId, out var g) ? g.ToString("N") : v.ItemId, Name = v.Name })
            .OrderBy(o => o.Name, StringComparer.CurrentCultureIgnoreCase)
            .ToList();
        if (all.Any(r => r.Kind == "livetv"))
        {
            dto.Filters.Libraries.Add(new StatsOptionDto { Id = "livetv", Name = "Live TV" });
        }

        dto.Filters.Devices = all.Select(r => r.Play.DeviceName).OfType<string>().Where(d => d.Length > 0)
            .Distinct(StringComparer.Ordinal).OrderBy(d => d, StringComparer.CurrentCultureIgnoreCase).ToList();
        return dto;
    }

    private DateTime From(string range, DateTime today, TimeZoneInfo zone) => range switch
    {
        "7d" => today.AddDays(-6),
        "12m" => today.AddMonths(-12).AddDays(1),
        "all" => _db.FirstPlayAt() is { } first ? LocalOf(first, zone).Date : today,
        _ => today.AddDays(-29)
    };

    private static double? TranscodeShare(List<Row> rows)
    {
        var known = rows.Count(r => r.Play.PlayMethod is not null);
        return known == 0 ? null : 100.0 * rows.Count(r => r.Play.PlayMethod == "Transcode") / known;
    }

    // The most streams open at the same moment, when that first happened, and the bitrate going out then.
    private static StatsPeakDto? Peak(IReadOnlyCollection<Row> rows)
    {
        if (rows.Count == 0)
        {
            return null;
        }

        var events = rows.SelectMany(r => new[] { (At: r.Start, Delta: 1, Row: r), (At: r.End, Delta: -1, Row: r) })
            .OrderBy(e => e.At).ThenBy(e => e.Delta)
            .ToList();
        int open = 0, best = 0;
        DateTime at = default;
        foreach (var e in events)
        {
            open += e.Delta;
            if (open > best)
            {
                best = open;
                at = e.At;
            }
        }

        var live = rows.Where(r => r.Start <= at && r.End > at).ToList();
        var bitrate = live.Where(r => r.Play.Bitrate > 0).Sum(r => r.Play.Bitrate ?? 0);
        return new StatsPeakDto { Streams = best, At = DateTime.SpecifyKind(at, DateTimeKind.Utc), Bitrate = bitrate > 0 ? bitrate : null };
    }

    private static string ReasonGroup(string? reasons)
    {
        var tokens = (reasons ?? string.Empty).Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        if (tokens.Any(VideoReasons.Contains))
        {
            return "video";
        }

        if (tokens.Contains("SubtitleCodecNotSupported"))
        {
            return "subtitles";
        }

        if (tokens.Any(BitrateReasons.Contains))
        {
            return "bitrate";
        }

        if (tokens.Any(AudioReasons.Contains))
        {
            return "audio";
        }

        return tokens.Contains("ContainerNotSupported") ? "container" : "other";
    }

    private List<StatsReasonDto> Reasons(List<Row> transcoded)
    {
        var capBps = _config.Configuration.RemoteClientBitrateLimit;
        return transcoded
            .GroupBy(r => ReasonGroup(r.Play.TranscodeReasons), StringComparer.Ordinal)
            .Select(g => new StatsReasonDto
            {
                Key = g.Key,
                Plays = g.Count(),
                Codec = g.Key switch
                {
                    "video" => Most(g.Select(r => r.Play.VideoCodec)),
                    "subtitles" => Most(g.Select(r => r.Play.SubtitleCodec)),
                    _ => null
                },
                Devices = g.Where(r => !string.IsNullOrEmpty(r.Play.DeviceName)).GroupBy(r => r.Play.DeviceName!, StringComparer.Ordinal)
                    .OrderByDescending(d => d.Count()).Select(d => d.Key).Take(2).ToList(),
                CapMbps = g.Key == "bitrate" && capBps > 0 ? Math.Round(capBps / 1_000_000.0, 1) : null
            })
            .OrderByDescending(r => r.Plays)
            .ToList();
    }

    private static string? Most(IEnumerable<string?> values)
        => values.Where(v => !string.IsNullOrEmpty(v)).GroupBy(v => v!, StringComparer.OrdinalIgnoreCase).OrderByDescending(g => g.Count()).FirstOrDefault()?.Key;

    // Average hours played in each hour of each weekday (Monday first), and how many people played in it.
    private static (double[][] Heat, int[][] Viewers) Heat(List<Row> rows, TimeZoneInfo zone, DateTime from, DateTime to)
    {
        var sums = new double[7][];
        var people = new HashSet<string>[7][];
        for (var d = 0; d < 7; d++)
        {
            sums[d] = new double[24];
            people[d] = new HashSet<string>[24];
            for (var h = 0; h < 24; h++)
            {
                people[d][h] = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            }
        }

        foreach (var r in rows)
        {
            // The play's watch time is spread over the hours its stream was open.
            var start = LocalOf(r.Start, zone);
            var end = LocalOf(r.End, zone);
            var span = Math.Max(1, (end - start).TotalMilliseconds);
            var cursor = start;
            var guard = 0;
            do
            {
                var hourEnd = cursor.Date.AddHours(cursor.Hour + 1);
                var sliceEnd = end < hourEnd ? end : hourEnd;
                var share = end > start ? (sliceEnd - cursor).TotalMilliseconds / span : 1;
                var day = ((int)cursor.DayOfWeek + 6) % 7;
                sums[day][cursor.Hour] += r.Watched * share;
                people[day][cursor.Hour].Add(r.Play.UserId);
                cursor = hourEnd;
            }
            while (cursor < end && ++guard < 48);
        }

        var counts = new int[7];
        for (var d = from.Date; d <= to.Date; d = d.AddDays(1))
        {
            counts[((int)d.DayOfWeek + 6) % 7]++;
        }

        var heat = new double[7][];
        var viewers = new int[7][];
        for (var d = 0; d < 7; d++)
        {
            heat[d] = new double[24];
            viewers[d] = new int[24];
            for (var h = 0; h < 24; h++)
            {
                heat[d][h] = counts[d] == 0 ? 0 : Math.Round(sums[d][h] / counts[d]);
                viewers[d][h] = people[d][h].Count;
            }
        }

        return (heat, viewers);
    }

    [GeneratedRegex(@"^(Chrome|Firefox|Safari|Edge|Opera|Brave|Vivaldi|Browser|Chromium)( on .+)?$", RegexOptions.IgnoreCase)]
    private static partial Regex BrowserDevice();

    [GeneratedRegex(@"\b(TV|Shield|AFT\w*|Fire ?TV|BRAVIA|Chromecast|Google TV|MiBOX|Android TV)\b", RegexOptions.IgnoreCase)]
    private static partial Regex TvDevice();

    [GeneratedRegex(@"\b(Smart ?TV|webOS|Tizen|Roku|Hisense|Vizio)\b", RegexOptions.IgnoreCase)]
    private static partial Regex SmartTv();

    // Older Android apps (TV and phone alike) reported themselves as "Seedy", like the web app. A device that has since played
    // with a newer app (in the log), or that Jellyfin lists with a newer app name, tells which one its older plays came from.
    private Dictionary<string, string> AppsByDevice()
    {
        var apps = new Dictionary<string, string>(StringComparer.Ordinal);
        try
        {
            foreach (var d in _devices.GetDevicesForUser(null).Items.OrderBy(d => d.DateLastActivity))
            {
                if (!string.IsNullOrEmpty(d.Name) && d.AppName is { } app && app.StartsWith("Seedy for ", StringComparison.Ordinal))
                {
                    apps[d.Name] = app;
                }
            }
        }
#pragma warning disable CA1031 // Without Jellyfin's device list, the play log alone decides.
        catch (Exception)
#pragma warning restore CA1031
        {
        }

        foreach (var (device, client) in _db.DeviceApps())
        {
            if (client.StartsWith("Seedy for ", StringComparison.Ordinal))
            {
                apps[device] = client;
            }
        }

        return apps;
    }

    /// <summary>The kind of device and app a play came from, as the Devices and apps panel groups them.</summary>
    /// <param name="client">App name.</param>
    /// <param name="device">Device name.</param>
    /// <param name="apps">The Seedy app each device is known to run now, by device name.</param>
    /// <returns>Group key, English name and the app's name.</returns>
    public static (string Key, string Name, string App) Family(string? client, string? device, IReadOnlyDictionary<string, string>? apps = null)
    {
        var c = client ?? string.Empty;
        if (c == "Seedy" && device is not null && apps?.GetValueOrDefault(device) is { } known && !BrowserDevice().IsMatch(device))
        {
            c = known;
        }

        var d = device ?? string.Empty;
        bool Has(string s) => c.Contains(s, StringComparison.OrdinalIgnoreCase);
        var browser = BrowserDevice().Match(d);
        if ((c == "Seedy" || Has("Web")) && browser.Success)
        {
            return ("web", "Web", browser.Groups[1].Value);
        }

        // Jellyfin's LG and Samsung apps wrap its web app and call themselves "Jellyfin Web".
        if (!Has("Android") && (Has("webOS") || Has("Tizen") || Has("Roku") || SmartTv().IsMatch(d)))
        {
            return ("smarttv", "Smart TVs", d.Length > 0 ? d : c);
        }

        if (Has("Web"))
        {
            return ("web", "Web", c);
        }

        if (Has("Android TV") || Has("AndroidTV") || (c == "Seedy" && TvDevice().IsMatch(d)))
        {
            return ("androidtv", "Android TV", c == "Seedy" ? "Seedy for Android TV" : c);
        }

        if (Has("tvOS") || Has("Apple TV"))
        {
            return ("appletv", "Apple TV", c);
        }

        if (Has("iOS") || Has("iPhone") || Has("iPad") || Has("Swiftfin") || Has("Infuse"))
        {
            return ("ios", "iPhone and iPad", c);
        }

        if (c == "Seedy")
        {
            // An older Android app on a device not seen with a newer one since: a phone or a TV.
            return ("android-any", "Android", "Seedy");
        }

        if (Has("Android") || Has("Findroid"))
        {
            return ("android", "Android phones", c);
        }

        return c.Length == 0 ? ("other", "Other", d) : ("app:" + c.ToLowerInvariant(), c, c);
    }

    private List<StatsTitleDto> Top(List<Row> rows, User admin)
    {
        var top = rows
            .GroupBy(r => r.Play.TitleId, StringComparer.OrdinalIgnoreCase)
            .Select(g => (Rows: g.ToList(), Watched: g.Sum(r => r.Watched)))
            .OrderByDescending(x => x.Watched)
            .Take(5)
            .ToList();
        var items = top.Select(x => Guid.TryParse(x.Rows[0].Play.TitleId, out var id) ? _library.GetItemById(id) : null).ToList();
        var found = items.OfType<BaseItem>().ToList();
        var dtos = found.Count > 0 ? _home.ToDtos(found, admin) : [];
        var byId = new Dictionary<Guid, MediaBrowser.Model.Dto.BaseItemDto>();
        for (var i = 0; i < found.Count && i < dtos.Count; i++)
        {
            byId[found[i].Id] = dtos[i];
        }

        return top.Select((x, i) =>
        {
            var first = x.Rows[0];
            var item = items[i];
            return new StatsTitleDto
            {
                TitleId = first.Play.TitleId,
                Kind = first.Play.ItemType == "AudioBook" ? "audiobook" : first.Kind,
                Name = item?.Name ?? first.Play.TitleName,
                WatchMs = x.Watched,
                Plays = x.Rows.Count,
                Viewers = x.Rows.Select(r => r.Play.UserId).Distinct(StringComparer.OrdinalIgnoreCase).Count(),
                Items = x.Rows.Select(r => r.Play.ItemId).Distinct(StringComparer.OrdinalIgnoreCase).Count(),
                Item = item is not null && byId.TryGetValue(item.Id, out var dto) ? dto : null
            };
        }).ToList();
    }

    /// <summary>Builds the page of one day.</summary>
    /// <param name="date">The day, in the viewer's time zone.</param>
    /// <param name="zone">The viewer's time zone.</param>
    /// <returns>The day's plays and how it compares.</returns>
    public StatsDayDto BuildDay(DateTime date, TimeZoneInfo zone)
    {
        var today = LocalOf(DateTime.UtcNow, zone).Date;
        var start = UtcOf(date, zone);
        var end = UtcOf(date.AddDays(1), zone);

        // The 30 days up to this one, to rank it and compare it with an average day.
        var windowStart = UtcOf(date.AddDays(-29), zone);
        var window = Load(windowStart, end, null);
        var perDay = new Dictionary<DateTime, long>();
        for (var d = date.AddDays(-29); d <= date; d = d.AddDays(1))
        {
            perDay[d] = 0;
        }

        foreach (var r in window)
        {
            var d = LocalOf(r.Start, zone).Date;
            if (perDay.ContainsKey(d))
            {
                perDay[d] += r.Watched;
            }
        }

        var mine = perDay[date.Date];
        var others = perDay.Where(p => p.Key != date.Date).Select(p => p.Value).ToList();
        var rows = window.Where(r => r.Start >= start && r.Start < end).ToList();

        var profiles = Profiles();
        var dto = new StatsDayDto
        {
            Date = Day(date),
            Today = Day(today),
            Start = DateTime.SpecifyKind(start, DateTimeKind.Utc),
            End = DateTime.SpecifyKind(end, DateTimeKind.Utc),
            AvgDayMs = others.Count == 0 ? 0 : (long)others.Average(),
            Rank = 1 + perDay.Count(p => p.Key != date.Date && p.Value > mine),
            DaysRanked = perDay.Count,
            TotalUsers = profiles.Count(Enabled),
            Users = profiles.Select((u, i) => new StatsViewerDto
            {
                UserId = u.Id.ToString("N"),
                Name = u.Username,
                ImageTag = _users.GetUserDto(u).PrimaryImageTag,
                Index = i,
                WatchMs = rows.Where(r => string.Equals(r.Play.UserId, u.Id.ToString("N"), StringComparison.OrdinalIgnoreCase)).Sum(r => r.Watched),
                Plays = rows.Count(r => string.Equals(r.Play.UserId, u.Id.ToString("N"), StringComparison.OrdinalIgnoreCase)),
                Enabled = Enabled(u)
            }).ToList()
        };
        dto.Sessions = Sessions(rows);
        return dto;
    }

    // The day's plays, with songs played back to back on one device joined into one listening session.
    private List<StatsSessionDto> Sessions(List<Row> rows)
    {
        var list = new List<StatsSessionDto>();
        var details = new Dictionary<string, BaseItem?>(StringComparer.OrdinalIgnoreCase);
        BaseItem? Item(string id)
        {
            if (!details.TryGetValue(id, out var item))
            {
                item = Guid.TryParse(id, out var g) ? _library.GetItemById(g) : null;
                details[id] = item;
            }

            return item;
        }

        StatsSessionDto? music = null;
        var albums = new Dictionary<string, (string Name, int Count)>(StringComparer.OrdinalIgnoreCase);
        foreach (var r in rows.OrderBy(r => r.Play.UserId, StringComparer.Ordinal).ThenBy(r => r.Start))
        {
            var p = r.Play;
            if (r.Kind == "music" && p.ItemType == "Audio" && music is not null && music.UserId == p.UserId
                && string.Equals(music.Device, p.DeviceName, StringComparison.Ordinal) && r.Start - music.End <= SongGap)
            {
                music.End = r.End > music.End ? r.End : music.End;
                music.WatchMs += r.Watched;
                music.Count++;
                if (p.Bitrate > (music.Bitrate ?? 0))
                {
                    music.Bitrate = p.Bitrate;
                }

                if (PlaybackRecorder.MethodWeight(p.PlayMethod) > PlaybackRecorder.MethodWeight(music.Method))
                {
                    music.Method = p.PlayMethod;
                }

                Count(albums, p);
                continue;
            }

            Close();
            var item = Item(p.ItemId);
            var s = new StatsSessionDto
            {
                Id = p.Id,
                UserId = p.UserId,
                Kind = r.Kind,
                ItemType = p.ItemType,
                ItemId = p.ItemId,
                TitleId = p.TitleId,
                Title = p.TitleName,
                ItemName = p.ItemName,
                Season = item is MediaBrowser.Controller.Entities.TV.Episode ? item.ParentIndexNumber : null,
                Episode = item is MediaBrowser.Controller.Entities.TV.Episode ? item.IndexNumber : null,
                Year = item?.ProductionYear,
                Artist = item is IHasAlbumArtist a ? a.AlbumArtists?.FirstOrDefault() : null,
                Start = DateTime.SpecifyKind(r.Start, DateTimeKind.Utc),
                End = DateTime.SpecifyKind(r.End, DateTimeKind.Utc),
                WatchMs = r.Watched,
                Percent = p.RuntimeTicks > 0 ? Math.Clamp(100.0 * p.PositionTicks / p.RuntimeTicks, 0, 100) : null,
                Finished = p.Finished,
                Device = p.DeviceName,
                Method = p.PlayMethod,
                Bitrate = p.Bitrate
            };
            if (r.Kind == "music" && p.ItemType == "Audio")
            {
                music = s;
                albums.Clear();
                Count(albums, p);
            }

            list.Add(s);
        }

        Close();
        return list.OrderBy(s => s.Start).ToList();

        void Close()
        {
            if (music is not null && music.Count > 1)
            {
                // A listening session is named after the album played most.
                var top = albums.OrderByDescending(a => a.Value.Count).First();
                music.TitleId = top.Key;
                music.Title = top.Value.Name;
                music.ItemId = top.Key;
                music.ItemName = top.Value.Name;
                music.Percent = null;
                music.Finished = false;
            }

            music = null;
        }
    }

    private static void Count(Dictionary<string, (string Name, int Count)> albums, PlayRecord p)
    {
        var count = albums.TryGetValue(p.TitleId, out var x) ? x.Count : 0;
        albums[p.TitleId] = (p.TitleName, count + 1);
    }

    /// <summary>The plays behind the Statistics page, as CSV.</summary>
    /// <param name="q">Filters.</param>
    /// <returns>CSV text.</returns>
    public string Csv(StatsQuery q)
    {
        var zone = q.Zone;
        var today = LocalOf(DateTime.UtcNow, zone).Date;
        var from = From(q.Range, today, zone);
        var rows = Load(UtcOf(from, zone), UtcOf(today.AddDays(1), zone), q);
        var names = _users.GetUsers().ToDictionary(u => u.Id.ToString("N"), u => u.Username, StringComparer.OrdinalIgnoreCase);
        var libraries = _library.GetVirtualFolders().Where(v => Guid.TryParse(v.ItemId, out _))
            .ToDictionary(v => Guid.Parse(v.ItemId).ToString("N"), v => v.Name, StringComparer.OrdinalIgnoreCase);
        var csv = new StringBuilder();
        csv.AppendLine("started,stopped,profile,kind,title,item,library,device,app,watched_minutes,progress_percent,finished,how,transcode_reasons,bitrate_mbps");
        foreach (var r in rows)
        {
            var p = r.Play;
            csv.AppendLine(string.Join(',', new[]
            {
                LocalOf(r.Start, zone).ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture),
                LocalOf(r.End, zone).ToString("yyyy-MM-dd HH:mm", CultureInfo.InvariantCulture),
                Cell(names.GetValueOrDefault(p.UserId) ?? p.UserId),
                r.Kind,
                Cell(p.TitleName),
                Cell(p.ItemName),
                Cell(r.Kind == "livetv" ? "Live TV" : p.LibraryId is null ? null : libraries.GetValueOrDefault(p.LibraryId)),
                Cell(p.DeviceName),
                Cell(p.Client),
                (r.Watched / 60000.0).ToString("0.0", CultureInfo.InvariantCulture),
                p.RuntimeTicks > 0 ? Math.Clamp(100.0 * p.PositionTicks / p.RuntimeTicks, 0, 100).ToString("0", CultureInfo.InvariantCulture) : string.Empty,
                p.Finished ? "yes" : "no",
                p.PlayMethod switch { "DirectPlay" => "direct play", "DirectStream" => "direct stream", "ClientDecode" => "decoded on client", "Transcode" => "transcoded", _ => string.Empty },
                Cell(p.TranscodeReasons),
                p.Bitrate > 0 ? (p.Bitrate.Value / 1_000_000.0).ToString("0.0", CultureInfo.InvariantCulture) : string.Empty
            }));
        }

        return csv.ToString();
    }

    private static string Cell(string? value)
    {
        if (string.IsNullOrEmpty(value))
        {
            return string.Empty;
        }

        // Spreadsheets run cells starting with these as formulas.
        var v = value[0] is '=' or '+' or '-' or '@' ? "'" + value : value;
        return v.IndexOfAny([',', '"', '\n', '\r']) >= 0 ? "\"" + v.Replace("\"", "\"\"", StringComparison.Ordinal) + "\"" : v;
    }
}
