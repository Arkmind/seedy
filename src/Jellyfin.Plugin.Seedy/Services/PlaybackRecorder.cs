using System;
using System.Collections.Concurrent;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Plugin.Seedy.Data;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Audio;
using MediaBrowser.Controller.Entities.Movies;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.LiveTv;
using MediaBrowser.Controller.Session;
using MediaBrowser.Model.Dto;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.Session;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>
/// Records every play on the server (from any client) into the Seedy play log, with the home row it
/// started from when the client told us. Feeds the Top 10, viewing history, row order, genre rows and Statistics.
/// Music, audiobooks, books and Live TV are logged for Statistics only; the log's other uses read videos.
/// </summary>
public sealed class PlaybackRecorder : IHostedService
{
    private static readonly TimeSpan FlushEvery = TimeSpan.FromSeconds(20);
    private static readonly TimeSpan OriginWindow = TimeSpan.FromMinutes(10);
    private static readonly TimeSpan RestartWindow = TimeSpan.FromMinutes(3);

    // A progress report credits at most this much playing time, so a client that went quiet isn't counted as watching.
    private static readonly TimeSpan MaxCredit = TimeSpan.FromMinutes(10);

    // A reader saves its place as pages turn; a gap longer than this starts a new reading session.
    private static readonly TimeSpan ReadingGap = TimeSpan.FromMinutes(10);
    private static readonly TimeSpan MaxPageCredit = TimeSpan.FromMinutes(3);

    private readonly ISessionManager _sessions;
    private readonly ILibraryManager _library;
    private readonly IMediaSourceManager _mediaSources;
    private readonly Audiobooks _audiobooks;
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
    /// <param name="library">Library manager.</param>
    /// <param name="mediaSources">Media source manager.</param>
    /// <param name="audiobooks">Audiobooks in several files.</param>
    /// <param name="db">Database.</param>
    /// <param name="logger">Logger.</param>
    public PlaybackRecorder(ISessionManager sessions, ILibraryManager library, IMediaSourceManager mediaSources, Audiobooks audiobooks, SeedyDatabase db, ILogger<PlaybackRecorder> logger)
    {
        _sessions = sessions;
        _library = library;
        _mediaSources = mediaSources;
        _audiobooks = audiobooks;
        _db = db;
        _logger = logger;
    }

    private sealed class Active
    {
        public required PlayRecord Play { get; init; }

        public DateTime LastFlush { get; set; }

        public string? SessionId { get; init; }

        /// <summary>Gets or sets when playing time was last credited.</summary>
        public DateTime LastTick { get; set; }

        /// <summary>Gets or sets the position at the last credit.</summary>
        public long LastPosition { get; set; }

        public bool Paused { get; set; }

        public bool Live { get; init; }

        public bool AudioOnly { get; init; }

        /// <summary>Gets or sets the source being played, for its bitrate and streams.</summary>
        public MediaSourceInfo? Source { get; set; }

        /// <summary>Gets or sets a value indicating whether the session said Transcode before Jellyfin described the transcode.</summary>
        public bool TranscodePending { get; set; }

        /// <summary>Gets or sets a value indicating whether the bitrate logged is the transcode's.</summary>
        public bool SendsTranscode { get; set; }
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

    /// <summary>
    /// Counts reading time: Seedy's reader saves the place as pages turn, and each save extends the profile's reading
    /// session of that book (or starts one after a pause), crediting the time since the last save, up to a few minutes.
    /// </summary>
    /// <param name="user">Profile.</param>
    /// <param name="book">The book (EPUB, PDF, comic).</param>
    /// <param name="deviceName">Device, from the request.</param>
    /// <param name="client">App, from the request.</param>
    /// <param name="percent">How far through the book, 0 to 100.</param>
    /// <param name="finished">Whether it was read to the end.</param>
    public void NoteReading(User user, BaseItem book, string? deviceName, string? client, double percent, bool finished)
    {
        if (!(Plugin.Instance?.Configuration.PlayLogEnabled ?? true))
        {
            return;
        }

        Safe(() =>
        {
            var now = DateTime.UtcNow;
            var userId = user.Id.ToString("N");
            var itemId = book.Id.ToString("N");
            var position = (long)(Math.Clamp(percent, 0, 100) * 100_000_000);
            var open = _db.OpenReading(userId, itemId, now - ReadingGap);
            if (open is not null && string.Equals(open.DeviceName, deviceName, StringComparison.Ordinal))
            {
                var since = now - (open.StoppedAt ?? open.StartedAt);
                open.WatchedMs = (open.WatchedMs ?? 0) + (long)Math.Clamp(since.TotalMilliseconds, 0, MaxPageCredit.TotalMilliseconds);
                open.StoppedAt = now;
                open.PositionTicks = position;
                open.Finished = finished;
                _db.UpdatePlay(open);
                return;
            }

            _db.InsertPlay(new PlayRecord
            {
                Id = Guid.NewGuid().ToString("N"),
                UserId = userId,
                ItemId = itemId,
                TitleId = itemId,
                ItemType = "Book",
                TitleName = book.Name ?? string.Empty,
                ItemName = book.Name ?? string.Empty,
                StartedAt = now,
                StoppedAt = now,
                PositionTicks = position,
                // Books have no length: the position is a percentage of this.
                RuntimeTicks = 10_000_000_000,
                Finished = finished,
                DeviceName = deviceName,
                Client = client,
                WatchedMs = 0,
                PlayMethod = "DirectPlay",
                LibraryId = LibraryOf(book)
            });
        });
    }

    // Movies, episodes and other videos (not trailers or extras), songs, audiobooks and Live TV channels.
    private static bool Recordable(BaseItem? item)
        => (item is Video and not Trailer && item.ExtraType is null) || item is Audio || item is LiveTvChannel;

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
            Safe(() => Begin(user, e));
        }
    }

    private Active Begin(User user, PlaybackProgressEventArgs e)
    {
        lock (_beginGate)
        {
            return BeginLocked(user, e);
        }
    }

    private Active BeginLocked(User user, PlaybackProgressEventArgs e)
    {
        var userId = user.Id.ToString("N");
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
            var carried = Track(moved.Play, e, item, now);
            carried.LastFlush = moved.LastFlush;
            carried.LastTick = moved.LastTick;
            carried.Source ??= moved.Source;
            carried.TranscodePending = moved.TranscodePending;
            carried.SendsTranscode = moved.SendsTranscode;
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
            var resumed = Track(last, e, item, now);
            Capture(resumed, e.Session);
            _db.UpdatePlay(last);
            _active[key] = resumed;
            return resumed;
        }

        var (titleId, titleName) = Title(item, user);
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
            Origin = origin,
            WatchedMs = 0,
            LibraryId = LibraryOf(item)
        };
        var a = Track(play, e, item, now);
        Capture(a, e.Session);
        _db.InsertPlay(play);
        _active[key] = a;
        return a;
    }

    private Active Track(PlayRecord play, PlaybackProgressEventArgs e, BaseItem item, DateTime now)
    {
        var a = new Active
        {
            Play = play,
            LastFlush = now,
            SessionId = e.Session?.Id,
            LastTick = now,
            LastPosition = e.PlaybackPositionTicks ?? play.PositionTicks,
            Paused = e.IsPaused,
            Live = item is LiveTvChannel,
            AudioOnly = item is Audio
        };
        if (!a.Live)
        {
            try
            {
                var sources = _mediaSources.GetStaticMediaSources(item, false);
                a.Source = sources.FirstOrDefault(s => string.Equals(s.Id, e.MediaSourceId, StringComparison.OrdinalIgnoreCase)) ?? sources.FirstOrDefault();
            }
#pragma warning disable CA1031 // Missing stream details only leave Statistics blanks.
            catch (Exception ex)
#pragma warning restore CA1031
            {
                _logger.LogDebug(ex, "Seedy couldn't read the media sources of {Item}", item.Id);
            }

            play.VideoCodec ??= a.Source?.MediaStreams?.FirstOrDefault(s => s.Type == MediaStreamType.Video)?.Codec;
        }

        return a;
    }

    // Episodes count towards their series, songs towards their album, audiobook files towards the book.
    private (string Id, string? Name) Title(BaseItem item, User user)
    {
        switch (item)
        {
            case Episode ep when ep.SeriesId != Guid.Empty:
                return (ep.SeriesId.ToString("N"), ep.SeriesName ?? item.Name);
            case AudioBook:
                var book = _audiobooks.Find(item, user);
                return book is null ? (item.Id.ToString("N"), item.Name) : (book.Folder.Id.ToString("N"), book.Name);
            case Audio song when song.AlbumEntity is { } album:
                return (album.Id.ToString("N"), album.Name);
            default:
                return (item.Id.ToString("N"), item.Name);
        }
    }

    private string? LibraryOf(BaseItem item)
    {
        if (item is LiveTvChannel)
        {
            return null;
        }

        try
        {
            return _library.GetCollectionFolders(item).FirstOrDefault()?.Id.ToString("N");
        }
#pragma warning disable CA1031 // The library only feeds a Statistics filter.
        catch (Exception)
#pragma warning restore CA1031
        {
            return null;
        }
    }

    private static int Weight(string? method) => method switch
    {
        "Transcode" => 3,
        "DirectStream" => 2,
        "DirectPlay" => 1,
        _ => 0
    };

    // How it plays right now, from the session: the heaviest method seen during the play is kept.
    private static void Capture(Active a, SessionInfo? session)
    {
        if (session is null)
        {
            return;
        }

        var p = a.Play;
        var ti = session.TranscodingInfo;
        var reported = session.PlayState?.PlayMethod;
        string? method = reported switch
        {
            PlayMethod.DirectPlay => "DirectPlay",
            PlayMethod.DirectStream => "DirectStream",
            // Jellyfin reports remuxes (video and audio copied into a new container) as Transcode.
            PlayMethod.Transcode when ti is null => null,
            PlayMethod.Transcode when ti.IsAudioDirect && (a.AudioOnly || ti.IsVideoDirect) => "DirectStream",
            PlayMethod.Transcode => "Transcode",
            _ => null
        };
        if (reported == PlayMethod.Transcode && ti is null)
        {
            a.TranscodePending = true;
        }

        if (method is not null && Weight(method) >= Weight(p.PlayMethod))
        {
            p.PlayMethod = method;
        }

        if (ti is not null && ti.TranscodeReasons != 0 && method is "Transcode" or "DirectStream")
        {
            p.TranscodeReasons = ti.TranscodeReasons.ToString();
        }

        // What goes out to the device: the transcode's bitrate once there is one (the highest it reached), else the file's.
        if (method is "Transcode" or "DirectStream" && ti?.Bitrate is > 0 and var sent)
        {
            p.Bitrate = a.SendsTranscode ? Math.Max(p.Bitrate ?? 0, sent) : sent;
            a.SendsTranscode = true;
        }
        else if (!a.SendsTranscode && a.Source?.Bitrate is > 0 and var file)
        {
            p.Bitrate = file;
        }

        var subtitle = session.PlayState?.SubtitleStreamIndex;
        if (subtitle is >= 0 && a.Source?.MediaStreams?.FirstOrDefault(s => s.Index == subtitle && s.Type == MediaStreamType.Subtitle) is { } stream)
        {
            p.SubtitleCodec = stream.Codec;
        }
    }

    // Credits the time since the last report as playing time, unless it was paused; capped by how far the position moved.
    private static void Tick(Active a, long? position, bool? paused)
    {
        var now = DateTime.UtcNow;
        if (!a.Paused)
        {
            var credit = (now - a.LastTick).TotalMilliseconds;
            if (!a.Live && position is { } pos && pos >= a.LastPosition && a.Play.RuntimeTicks > 0)
            {
                credit = Math.Min(credit, ((pos - a.LastPosition) / TimeSpan.TicksPerMillisecond) + 15_000);
            }

            a.Play.WatchedMs = (a.Play.WatchedMs ?? 0) + (long)Math.Clamp(credit, 0, MaxCredit.TotalMilliseconds);
        }

        a.LastTick = now;
        a.LastPosition = position ?? a.LastPosition;
        a.Paused = paused ?? a.Paused;
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
                    a = Begin(user, e);
                }

                Tick(a, e.PlaybackPositionTicks, e.IsPaused);
                Capture(a, e.Session);
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
                    Tick(a, e.PlaybackPositionTicks, null);
                    Capture(a, e.Session);
                    if (play.PlayMethod is null && a.TranscodePending)
                    {
                        play.PlayMethod = "Transcode";
                    }
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
                    if (a.Play.PlayMethod is null && a.TranscodePending)
                    {
                        a.Play.PlayMethod = "Transcode";
                    }

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
