using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data.Queries;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Plugin.Seedy.Api;
using Jellyfin.Plugin.Seedy.Data;
using MediaBrowser.Controller.Devices;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Audio;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Activity;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.Globalization;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>
/// Fills the play log with the plays from before Seedy was installed, rebuilt from Jellyfin's activity log ("Robin is
/// playing Dune on Chrome"). The log has no positions, pauses or play methods, so an imported play's watching time is its
/// length, and whether it was finished is worked out from how long it lasted (see <see cref="HistoryPairing"/>). Only plays
/// older than Seedy's own log and inside the "Keep plays for" setting are imported; importing again replaces them.
/// Items deleted since, and profiles deleted since, are left out.
/// </summary>
public sealed partial class HistoryBackfill
{
    /// <summary>The play_session_id prefix of imported plays.</summary>
    public const string SessionPrefix = "backfill-";

    private const int Page = 1000;

    private static readonly string[] StartTypes = ["VideoPlayback", "AudioPlayback"];
    private static readonly string[] StopTypes = ["VideoPlaybackStopped", "AudioPlaybackStopped"];

    // Jellyfin writes the lines in the server's language: its own format first, English for lines older than a switch.
    private const string StartKey = "UserStartedPlayingItemWithValues";
    private const string StopKey = "UserStoppedPlayingItemWithValues";
    private const string EnglishStart = "{0} is playing {1} on {2}";
    private const string EnglishStop = "{0} has finished playing {1} on {2}";

    private readonly IActivityManager _activity;
    private readonly IUserManager _users;
    private readonly ILibraryManager _library;
    private readonly IMediaSourceManager _mediaSources;
    private readonly IDeviceManager _devices;
    private readonly ILocalizationManager _localization;
    private readonly PlaybackRecorder _recorder;
    private readonly SeedyDatabase _db;
    private readonly ILogger<HistoryBackfill> _logger;
    private readonly SemaphoreSlim _gate = new(1, 1);

    /// <summary>
    /// Initializes a new instance of the <see cref="HistoryBackfill"/> class.
    /// </summary>
    /// <param name="activity">Jellyfin's activity log.</param>
    /// <param name="users">User manager.</param>
    /// <param name="library">Library manager.</param>
    /// <param name="mediaSources">Media source manager.</param>
    /// <param name="devices">Device manager.</param>
    /// <param name="localization">Localization manager, for the log's line formats.</param>
    /// <param name="recorder">Play recorder, for titles and libraries.</param>
    /// <param name="db">Database.</param>
    /// <param name="logger">Logger.</param>
    public HistoryBackfill(IActivityManager activity, IUserManager users, ILibraryManager library, IMediaSourceManager mediaSources, IDeviceManager devices, ILocalizationManager localization, PlaybackRecorder recorder, SeedyDatabase db, ILogger<HistoryBackfill> logger)
    {
        _activity = activity;
        _users = users;
        _library = library;
        _mediaSources = mediaSources;
        _devices = devices;
        _localization = localization;
        _recorder = recorder;
        _db = db;
        _logger = logger;
    }

    /// <summary>Rebuilds the plays and, with <paramref name="write"/>, replaces the imported ones with them.</summary>
    /// <param name="write">Whether to write them (else it only counts).</param>
    /// <param name="cancellationToken">Cancellation.</param>
    /// <returns>What was (or would be) imported.</returns>
    public async Task<HistoryImportDto> RunAsync(bool write, CancellationToken cancellationToken)
    {
        await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            return await RunLockedAsync(write, cancellationToken).ConfigureAwait(false);
        }
        finally
        {
            _gate.Release();
        }
    }

    private async Task<HistoryImportDto> RunLockedAsync(bool write, CancellationToken cancellationToken)
    {
        var config = ConfigMapper.Current;
        var until = _db.FirstOwnPlayAt() ?? DateTime.UtcNow;
        var from = config.PlayLogRetentionDays > 0 ? DateTime.UtcNow.AddDays(-config.PlayLogRetentionDays) : (DateTime?)null;
        var dto = new HistoryImportDto { DryRun = !write, PlayLogEnabled = config.PlayLogEnabled, Until = until };
        if (!config.PlayLogEnabled)
        {
            return dto;
        }

        // Older lines too, so a play resumed inside the window knows where it was left.
        var entries = await ReadAsync(from?.AddDays(-30), cancellationToken).ConfigureAwait(false);
        dto.Entries = entries.Count;

        var users = new Dictionary<Guid, User?>();
        var items = new Dictionary<string, BaseItem?>(StringComparer.OrdinalIgnoreCase);
        var byId = new Dictionary<string, BaseItem>(StringComparer.Ordinal);
        var devices = DeviceNames();
        var startFormats = Formats(StartKey, EnglishStart);
        var stopFormats = Formats(StopKey, EnglishStop);
        bool InWindow(DateTime at) => at < until && (from is null || at >= from);

        var events = new List<PlayEvent>();
        foreach (var e in entries)
        {
            var at = DateTime.SpecifyKind(e.Date, DateTimeKind.Utc);
            var start = StartTypes.Contains(e.Type, StringComparer.Ordinal);
            if (!users.TryGetValue(e.UserId, out var user))
            {
                user = users[e.UserId] = _users.GetUserById(e.UserId);
            }

            if (user is null)
            {
                dto.MissingUsers += start && InWindow(at) ? 1 : 0;
                continue;
            }

            var itemKey = e.ItemId ?? string.Empty;
            if (!items.TryGetValue(itemKey, out var item))
            {
                item = items[itemKey] = Guid.TryParse(itemKey, out var id) ? _library.GetItemById(id) : null;
            }

            if (item is null)
            {
                dto.MissingItems += start && InWindow(at) ? 1 : 0;
                continue;
            }

            if (!PlaybackRecorder.Recordable(item))
            {
                continue;
            }

            byId[item.Id.ToString("N")] = item;
            var device = HistoryPairing.DeviceOf(e.Name, start ? startFormats : stopFormats, user.Username, LogName(item), devices);
            events.Add(new PlayEvent(e.Id, start, at, user.Id.ToString("N"), item.Id.ToString("N"), device));
        }

        var plays = HistoryPairing.Rebuild(events, id => byId[id].RunTimeTicks ?? 0)
            .Where(p => InWindow(p.Start))
            .ToList();

        var apps = AppsByDevice();
        var codecs = new Dictionary<Guid, string?>();
        var records = new List<PlayRecord>(plays.Count);
        foreach (var p in plays)
        {
            var item = byId[p.ItemId];
            var user = users[Guid.Parse(p.UserId)]!;
            var (titleId, titleName) = _recorder.Title(item, user);
            records.Add(new PlayRecord
            {
                Id = IdOf(p.StartId),
                UserId = p.UserId,
                ItemId = p.ItemId,
                TitleId = titleId,
                ItemType = item.GetBaseItemKind().ToString(),
                TitleName = titleName ?? string.Empty,
                ItemName = item.Name ?? string.Empty,
                StartedAt = p.Start,
                StoppedAt = p.Stop,
                PositionTicks = p.PositionTicks,
                RuntimeTicks = item.RunTimeTicks ?? 0,
                Finished = p.Finished,
                DeviceName = p.Device,
                Client = ClientOf(p.Device, apps),
                PlaySessionId = SessionPrefix + p.StartId.ToString(System.Globalization.CultureInfo.InvariantCulture),
                // No watched time: Statistics then counts the time between start and stop, at most the item's length.
                WatchedMs = null,
                LibraryId = _recorder.LibraryOf(item),
                VideoCodec = VideoCodecOf(item, codecs)
            });
        }

        Summarise(dto, records, users);
        if (write)
        {
            dto.Replaced = _db.ReplaceImported(records);
            _logger.LogInformation(
                "Seedy imported {Plays} plays from Jellyfin's activity log ({From} to {Until}), replacing {Replaced}; left out {Items} for deleted items and {Users} for deleted profiles",
                records.Count,
                dto.From?.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture),
                until.ToString("yyyy-MM-dd HH:mm", System.Globalization.CultureInfo.InvariantCulture),
                dto.Replaced,
                dto.MissingItems,
                dto.MissingUsers);
        }

        return dto;
    }

    // Every start and stop line, oldest first. Jellyfin pages newest first, so a line logged meanwhile can come back twice.
    private async Task<List<ActivityLogEntry>> ReadAsync(DateTime? since, CancellationToken cancellationToken)
    {
        var all = new Dictionary<long, ActivityLogEntry>();
        foreach (var type in StartTypes.Concat(StopTypes))
        {
            for (var skip = 0; ; skip += Page)
            {
                cancellationToken.ThrowIfCancellationRequested();
                var page = await _activity.GetPagedResultAsync(new ActivityLogQuery { Type = type, MinDate = since, Skip = skip, Limit = Page }).ConfigureAwait(false);
                foreach (var e in page.Items)
                {
                    all[e.Id] = e;
                }

                if (page.Items.Count < Page)
                {
                    break;
                }
            }
        }

        return all.Values.OrderBy(e => e.Date).ThenBy(e => e.Id).ToList();
    }

    private void Summarise(HistoryImportDto dto, List<PlayRecord> records, Dictionary<Guid, User?> users)
    {
        var min = (long)Statistics.MinPlay.TotalMilliseconds;
        long Watched(PlayRecord p)
        {
            var span = (long)((p.StoppedAt ?? p.StartedAt) - p.StartedAt).TotalMilliseconds;
            return p.RuntimeTicks > 0 ? Math.Min(span, p.RuntimeTicks / TimeSpan.TicksPerMillisecond) : span;
        }

        var counted = records.Where(p => Watched(p) >= min).ToList();
        dto.Plays = records.Count;
        dto.Counted = counted.Count;
        dto.WatchMs = counted.Sum(Watched);
        dto.Finished = records.Count(p => p.Finished);
        dto.From = records.Count > 0 ? records.Min(p => p.StartedAt) : null;
        dto.Users = counted
            .GroupBy(p => p.UserId, StringComparer.OrdinalIgnoreCase)
            .Select(g => new HistoryImportUserDto
            {
                UserId = g.Key,
                Name = users.GetValueOrDefault(Guid.Parse(g.Key))?.Username ?? string.Empty,
                Plays = g.Count(),
                WatchMs = g.Sum(Watched)
            })
            .OrderByDescending(u => u.WatchMs)
            .ToList();
    }

    private string[] Formats(string key, string english)
    {
        string? local = null;
        try
        {
            local = _localization.GetLocalizedString(key);
        }
#pragma warning disable CA1031 // English still reads most servers' logs.
        catch (Exception)
#pragma warning restore CA1031
        {
        }

        return string.IsNullOrEmpty(local) || local == key || local == english ? [english] : [local, english];
    }

    // The item as Jellyfin names it in the log: "Series - Episode", "Artist - Song".
    private static string LogName(BaseItem item)
    {
        var name = item.Name ?? string.Empty;
        if (item is IHasSeries series && !string.IsNullOrEmpty(series.SeriesName))
        {
            name = series.SeriesName + " - " + name;
        }

        if (item is IHasArtist artist && artist.Artists.Count > 0)
        {
            name = artist.Artists[0] + " - " + name;
        }

        return name;
    }

    // Device names Jellyfin and the play log know, longest first.
    private List<string> DeviceNames()
    {
        var names = new HashSet<string>(StringComparer.Ordinal);
        try
        {
            foreach (var d in _devices.GetDevicesForUser(null).Items)
            {
                if (!string.IsNullOrEmpty(d.Name))
                {
                    names.Add(d.Name);
                }
            }
        }
#pragma warning disable CA1031 // The log lines can still be cut without the device list.
        catch (Exception ex)
#pragma warning restore CA1031
        {
            _logger.LogDebug(ex, "Seedy couldn't read Jellyfin's devices");
        }

        foreach (var (device, _) in _db.DeviceApps())
        {
            names.Add(device);
        }

        return names.OrderByDescending(n => n.Length).ToList();
    }

    // The app each device last ran other than Seedy: imported plays are from before Seedy, so never Seedy itself.
    private Dictionary<string, string> AppsByDevice()
    {
        var apps = new Dictionary<string, string>(StringComparer.Ordinal);
        foreach (var (device, client) in _db.DeviceApps())
        {
            if (!client.StartsWith("Seedy", StringComparison.Ordinal))
            {
                apps[device] = client;
            }
        }

        try
        {
            foreach (var d in _devices.GetDevicesForUser(null).Items.OrderBy(d => d.DateLastActivity))
            {
                if (!string.IsNullOrEmpty(d.Name) && d.AppName is { Length: > 0 } app && !app.StartsWith("Seedy", StringComparison.Ordinal))
                {
                    apps[d.Name] = app;
                }
            }
        }
#pragma warning disable CA1031 // Without the device list, the play log alone decides.
        catch (Exception)
#pragma warning restore CA1031
        {
        }

        return apps;
    }

    private static string? ClientOf(string? device, Dictionary<string, string> apps)
        => device is null ? null
            : apps.TryGetValue(device, out var app) ? app
            : JellyfinWebDevice().IsMatch(device) ? "Jellyfin Web"
            : null;

    private string? VideoCodecOf(BaseItem item, Dictionary<Guid, string?> codecs)
    {
        if (item is not Video)
        {
            return null;
        }

        if (codecs.TryGetValue(item.Id, out var known))
        {
            return known;
        }

        string? codec = null;
        try
        {
            codec = _mediaSources.GetStaticMediaSources(item, false).FirstOrDefault()?.MediaStreams?.FirstOrDefault(s => s.Type == MediaStreamType.Video)?.Codec;
        }
#pragma warning disable CA1031 // A missing codec only leaves a Statistics blank.
        catch (Exception)
#pragma warning restore CA1031
        {
        }

        return codecs[item.Id] = codec;
    }

    // The same id on every import, from the line the play started with.
    private static string IdOf(long startId)
        => new Guid(SHA256.HashData(Encoding.UTF8.GetBytes("seedy-backfill:" + startId.ToString(System.Globalization.CultureInfo.InvariantCulture)))[..16]).ToString("N");

    // The names Jellyfin's web app gives the browser it runs in.
    [GeneratedRegex(@"^(Chrome|Firefox|Safari|Edge|Edge Chromium|Opera|Opera TV|Opera Android|Chrome Android|Firefox Android|Safari iPad|Safari iPhone|Samsung Smart TV|LG Smart TV|Xbox One|Sony PS4|Web Browser)$")]
    private static partial Regex JellyfinWebDevice();
}
