using System;
using System.Collections.Concurrent;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Seedy.Data;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Movies;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Session;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>
/// Records every play on the server (from any client) into the Seedy play log, with the home row it
/// started from when the client told us. Feeds the Top 10, viewing history, row order and genre rows.
/// </summary>
public sealed class PlaybackRecorder : IHostedService
{
    private static readonly TimeSpan FlushEvery = TimeSpan.FromSeconds(20);
    private static readonly TimeSpan OriginWindow = TimeSpan.FromMinutes(10);
    private static readonly TimeSpan RestartWindow = TimeSpan.FromMinutes(3);

    private readonly ISessionManager _sessions;
    private readonly SeedyDatabase _db;
    private readonly ILogger<PlaybackRecorder> _logger;
    private readonly ConcurrentDictionary<string, Active> _active = new(StringComparer.Ordinal);
    private readonly ConcurrentDictionary<string, (string Row, DateTime At)> _origins = new(StringComparer.Ordinal);

    // Jellyfin raises PlaybackStart on a worker thread and PlaybackProgress on the request thread, so the first
    // progress report can race the start. Starting a play is serialised so it's recorded once.
    private readonly object _beginGate = new();

    /// <summary>
    /// Initializes a new instance of the <see cref="PlaybackRecorder"/> class.
    /// </summary>
    /// <param name="sessions">Session manager.</param>
    /// <param name="db">Database.</param>
    /// <param name="logger">Logger.</param>
    public PlaybackRecorder(ISessionManager sessions, SeedyDatabase db, ILogger<PlaybackRecorder> logger)
    {
        _sessions = sessions;
        _db = db;
        _logger = logger;
    }

    private sealed class Active
    {
        public required PlayRecord Play { get; init; }

        public DateTime LastFlush { get; set; }

        public string? SessionId { get; init; }
    }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _sessions.PlaybackStart += OnStart;
        _sessions.PlaybackProgress += OnProgress;
        _sessions.PlaybackStopped += OnStopped;
        _sessions.SessionEnded += OnSessionEnded;
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
    {
        _sessions.PlaybackStart -= OnStart;
        _sessions.PlaybackProgress -= OnProgress;
        _sessions.PlaybackStopped -= OnStopped;
        _sessions.SessionEnded -= OnSessionEnded;
        foreach (var a in _active.Values)
        {
            Safe(() => _db.UpdatePlay(a.Play));
        }

        return Task.CompletedTask;
    }

    /// <summary>Notes the home row a play is about to start from.</summary>
    /// <param name="userId">User id ("N").</param>
    /// <param name="itemId">Item id ("N").</param>
    /// <param name="row">Row kind.</param>
    public void SetOrigin(string userId, string itemId, string row)
    {
        // The play may already have started (the client sends this alongside playback start).
        var now = DateTime.UtcNow;
        var running = _active.Values.FirstOrDefault(a => a.Play.UserId == userId && a.Play.ItemId == itemId && a.Play.Origin is null && now - a.Play.StartedAt < OriginWindow);
        if (running is not null)
        {
            running.Play.Origin = row;
            Safe(() => _db.UpdatePlay(running.Play));
            return;
        }

        _origins[userId + ":" + itemId] = (row, now);
        foreach (var stale in _origins.Where(o => now - o.Value.At > OriginWindow).Select(o => o.Key).ToList())
        {
            _origins.TryRemove(stale, out _);
        }
    }

    // Movies, episodes and other videos; not trailers or extras.
    private static bool Recordable(BaseItem? item) => item is Video and not Trailer && item.ExtraType is null;

    private static string Key(string userId, PlaybackProgressEventArgs e)
        => userId + ":" + (e.PlaySessionId ?? (e.Session?.Id + ":" + e.Item?.Id.ToString("N")));

    private void OnStart(object? sender, PlaybackProgressEventArgs e)
    {
        if (!(Plugin.Instance?.Configuration.PlayLogEnabled ?? true) || !Recordable(e.Item) || e.Users is null)
        {
            return;
        }

        foreach (var user in e.Users)
        {
            Safe(() => Begin(user.Id.ToString("N"), e));
        }
    }

    private Active Begin(string userId, PlaybackProgressEventArgs e)
    {
        lock (_beginGate)
        {
            return BeginLocked(userId, e);
        }
    }

    private Active BeginLocked(string userId, PlaybackProgressEventArgs e)
    {
        var item = e.Item!;
        var itemId = item.Id.ToString("N");
        var key = Key(userId, e);
        if (_active.TryGetValue(key, out var existing))
        {
            return existing;
        }

        var now = DateTime.UtcNow;
        string? origin = null;
        if (_origins.TryRemove(userId + ":" + itemId, out var o) && now - o.At < OriginWindow)
        {
            origin = o.Row;
        }

        // Still playing on this device under an older stream: carry the play over to the new stream.
        var running = _active.FirstOrDefault(kv => kv.Value.Play.UserId == userId && kv.Value.Play.ItemId == itemId
            && string.Equals(kv.Value.Play.DeviceName, e.DeviceName, StringComparison.Ordinal));
        if (running.Value is not null && _active.TryRemove(running.Key, out var moved))
        {
            moved.Play.PlaySessionId = e.PlaySessionId ?? moved.Play.PlaySessionId;
            moved.Play.Origin ??= origin;
            var carried = new Active { Play = moved.Play, LastFlush = moved.LastFlush, SessionId = e.Session?.Id };
            _active[key] = carried;
            return carried;
        }

        // A client restarting the stream (audio track, quality, seek in a transcode) continues the same play:
        // same device, and the previous stream stopped (or started) moments ago.
        var last = _db.LatestPlay(userId, itemId);
        if (last is not null && !last.Finished
            && string.Equals(last.DeviceName, e.DeviceName, StringComparison.Ordinal)
            && now - (last.StoppedAt ?? last.StartedAt) < RestartWindow)
        {
            last.StoppedAt = null;
            last.PlaySessionId = e.PlaySessionId ?? last.PlaySessionId;
            last.Origin ??= origin;
            last.PositionTicks = e.PlaybackPositionTicks ?? last.PositionTicks;
            _db.UpdatePlay(last);
            var resumed = new Active { Play = last, LastFlush = now, SessionId = e.Session?.Id };
            _active[key] = resumed;
            return resumed;
        }

        var (titleId, titleName) = item is Episode ep && ep.SeriesId != Guid.Empty
            ? (ep.SeriesId.ToString("N"), ep.SeriesName ?? item.Name)
            : (itemId, item.Name);
        var play = new PlayRecord
        {
            Id = Guid.NewGuid().ToString("N"),
            UserId = userId,
            ItemId = itemId,
            TitleId = titleId,
            ItemType = item.GetBaseItemKind().ToString(),
            TitleName = titleName ?? string.Empty,
            ItemName = item.Name ?? string.Empty,
            StartedAt = now,
            PositionTicks = e.PlaybackPositionTicks ?? 0,
            RuntimeTicks = item.RunTimeTicks ?? 0,
            DeviceName = e.DeviceName,
            Client = e.ClientName,
            PlaySessionId = e.PlaySessionId,
            Origin = origin
        };
        _db.InsertPlay(play);
        var a = new Active { Play = play, LastFlush = now, SessionId = e.Session?.Id };
        _active[key] = a;
        return a;
    }

    private void OnProgress(object? sender, PlaybackProgressEventArgs e)
    {
        if (!(Plugin.Instance?.Configuration.PlayLogEnabled ?? true) || !Recordable(e.Item) || e.Users is null)
        {
            return;
        }

        foreach (var user in e.Users)
        {
            Safe(() =>
            {
                var userId = user.Id.ToString("N");
                if (!_active.TryGetValue(Key(userId, e), out var a))
                {
                    // Seedy started after the play did; pick it up from here.
                    a = Begin(userId, e);
                }

                a.Play.PositionTicks = e.PlaybackPositionTicks ?? a.Play.PositionTicks;
                if (DateTime.UtcNow - a.LastFlush > FlushEvery)
                {
                    a.LastFlush = DateTime.UtcNow;
                    _db.UpdatePlay(a.Play);
                }
            });
        }
    }

    private void OnStopped(object? sender, PlaybackStopEventArgs e)
    {
        if (!Recordable(e.Item) || e.Users is null)
        {
            return;
        }

        foreach (var user in e.Users)
        {
            Safe(() =>
            {
                var userId = user.Id.ToString("N");
                PlayRecord? play = null;
                if (_active.TryRemove(Key(userId, e), out var a))
                {
                    play = a.Play;
                }
                else
                {
                    var last = _db.LatestPlay(userId, e.Item!.Id.ToString("N"));
                    if (last is not null && last.StoppedAt is null)
                    {
                        play = last;
                    }
                }

                if (play is null)
                {
                    return;
                }

                play.PositionTicks = e.PlaybackPositionTicks ?? play.PositionTicks;
                play.RuntimeTicks = e.Item?.RunTimeTicks ?? play.RuntimeTicks;
                play.StoppedAt = DateTime.UtcNow;
                play.Finished = e.PlayedToCompletion
                    || (play.RuntimeTicks > 0 && play.PositionTicks >= play.RuntimeTicks * 0.9);
                _db.UpdatePlay(play);
            });
        }
    }

    // A client that vanished (closed tab, lost network) never reports a stop; close its plays at the last known position.
    private void OnSessionEnded(object? sender, SessionEventArgs e)
    {
        var id = e.SessionInfo?.Id;
        if (id is null)
        {
            return;
        }

        foreach (var (key, a) in _active.Where(kv => kv.Value.SessionId == id).ToList())
        {
            if (_active.TryRemove(key, out _))
            {
                Safe(() =>
                {
                    a.Play.StoppedAt = DateTime.UtcNow;
                    a.Play.Finished = a.Play.RuntimeTicks > 0 && a.Play.PositionTicks >= a.Play.RuntimeTicks * 0.9;
                    _db.UpdatePlay(a.Play);
                });
            }
        }
    }

    private void Safe(Action action)
    {
        try
        {
            action();
        }
#pragma warning disable CA1031 // A logging failure must never break playback.
        catch (Exception ex)
#pragma warning restore CA1031
        {
            _logger.LogError(ex, "Seedy couldn't record a play");
        }
    }
}
