using System;
using System.Collections.Generic;
using System.Linq;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>One "is playing" or "has finished playing" line of Jellyfin's activity log.</summary>
/// <param name="Id">Activity log entry id.</param>
/// <param name="Start">Whether it's a start (else a stop).</param>
/// <param name="At">When (UTC).</param>
/// <param name="UserId">User id ("N").</param>
/// <param name="ItemId">Item id ("N").</param>
/// <param name="Device">Device name, when it could be read from the line.</param>
internal sealed record PlayEvent(long Id, bool Start, DateTime At, string UserId, string ItemId, string? Device);

/// <summary>A play rebuilt from the activity log.</summary>
internal sealed class RebuiltPlay
{
    /// <summary>Gets the id of the activity entry it started with.</summary>
    public required long StartId { get; init; }

    /// <summary>Gets the user id ("N").</summary>
    public required string UserId { get; init; }

    /// <summary>Gets the item id ("N").</summary>
    public required string ItemId { get; init; }

    /// <summary>Gets the device name.</summary>
    public string? Device { get; init; }

    /// <summary>Gets the start (UTC).</summary>
    public required DateTime Start { get; init; }

    /// <summary>Gets or sets the stop (UTC).</summary>
    public required DateTime Stop { get; set; }

    /// <summary>Gets or sets the time actually streamed: the stop minus the start, less the gaps of stream restarts.</summary>
    public TimeSpan Streamed { get; set; }

    /// <summary>Gets or sets where it's taken to have stopped, in ticks.</summary>
    public long PositionTicks { get; set; }

    /// <summary>Gets or sets a value indicating whether it got to the end.</summary>
    public bool Finished { get; set; }
}

/// <summary>
/// Rebuilds plays from Jellyfin's activity log, which only says when someone started and stopped playing something:
/// no position, no pauses. A stop closes that profile's latest start of the item; stream restarts on one device are joined
/// into one play, the way <see cref="PlaybackRecorder"/> does; and since a play that isn't finished resumes where it
/// stopped, each one is taken to carry on from the profile's previous unfinished play of the item.
/// </summary>
internal static class HistoryPairing
{
    /// <summary>A start with no stop within this is dropped: the log lost its end.</summary>
    public static readonly TimeSpan MaxPlay = TimeSpan.FromHours(12);

    /// <summary>A stop and a start on the same device closer than this are a stream restart, like PlaybackRecorder's.</summary>
    public static readonly TimeSpan RestartWindow = TimeSpan.FromMinutes(3);

    /// <summary>Rebuilds the plays.</summary>
    /// <param name="events">Starts and stops, in any order.</param>
    /// <param name="runtimeTicks">Each item's length in ticks, 0 when unknown.</param>
    /// <returns>Plays, oldest first.</returns>
    public static List<RebuiltPlay> Rebuild(IEnumerable<PlayEvent> events, Func<string, long> runtimeTicks)
    {
        // 1. A stop closes the profile's latest open start of the item; older open starts never got a stop.
        var open = new Dictionary<(string, string), PlayEvent>();
        var paired = new List<RebuiltPlay>();
        foreach (var e in events.OrderBy(e => e.At).ThenBy(e => e.Id))
        {
            var key = (e.UserId, e.ItemId);
            if (e.Start)
            {
                open[key] = e;
            }
            else if (open.Remove(key, out var s) && e.At - s.At <= MaxPlay)
            {
                paired.Add(new RebuiltPlay { StartId = s.Id, UserId = s.UserId, ItemId = s.ItemId, Device = s.Device, Start = s.At, Stop = e.At, Streamed = e.At - s.At });
            }
        }

        // 2. Stream restarts (audio track, quality, a seek in a transcode) continue the play.
        var plays = new List<RebuiltPlay>();
        var last = new Dictionary<(string, string, string), RebuiltPlay>();
        foreach (var p in paired.OrderBy(p => p.Start).ThenBy(p => p.StartId))
        {
            var key = (p.UserId, p.ItemId, p.Device ?? string.Empty);
            if (last.TryGetValue(key, out var q) && p.Start >= q.Stop && p.Start - q.Stop < RestartWindow)
            {
                q.Streamed += p.Streamed;
                q.Stop = p.Stop > q.Stop ? p.Stop : q.Stop;
                continue;
            }

            plays.Add(p);
            last[key] = p;
        }

        // 3. Positions: each play adds what it streamed (at most the length) to where the previous unfinished one stopped.
        var position = new Dictionary<(string, string), (long Ticks, bool Finished)>();
        foreach (var p in plays)
        {
            var runtime = runtimeTicks(p.ItemId);
            var key = (p.UserId, p.ItemId);
            var from = position.TryGetValue(key, out var prev) && !prev.Finished ? prev.Ticks : 0;
            var streamed = p.Streamed.Ticks;
            p.PositionTicks = runtime > 0 ? Math.Min(runtime, from + Math.Min(streamed, runtime)) : 0;
            p.Finished = runtime > 0 && p.PositionTicks >= runtime * 0.9;
            position[key] = (p.PositionTicks, p.Finished);
        }

        return plays;
    }

    /// <summary>
    /// Reads the device from a line such as "Robin is playing Dune on Chrome on Windows". The device can have the
    /// separator in its name too, so the line is first matched against the profile and item names, then against the
    /// devices known, and only then cut at the last separator.
    /// </summary>
    /// <param name="line">Activity log line.</param>
    /// <param name="templates">The line's format strings ({0} user, {1} item, {2} device), most likely first.</param>
    /// <param name="userName">Profile name.</param>
    /// <param name="itemName">Item name as Jellyfin writes it in the log, if the item is known.</param>
    /// <param name="devices">Device names known, longest first.</param>
    /// <returns>The device, or null.</returns>
    public static string? DeviceOf(string line, IEnumerable<string> templates, string? userName, string? itemName, IReadOnlyList<string> devices)
    {
        foreach (var template in templates)
        {
            int u = template.IndexOf("{0}", StringComparison.Ordinal), i = template.IndexOf("{1}", StringComparison.Ordinal), d = template.IndexOf("{2}", StringComparison.Ordinal);
            if (u < 0 || i < u || d < i)
            {
                continue;
            }

            var prefix = template[..u];
            var afterUser = template[(u + 3)..i];
            var beforeDevice = template[(i + 3)..d];
            var suffix = template[(d + 3)..];
            if (beforeDevice.Length == 0 || !line.StartsWith(prefix, StringComparison.Ordinal) || !line.EndsWith(suffix, StringComparison.Ordinal) || line.Length < prefix.Length + suffix.Length)
            {
                continue;
            }

            var body = line[prefix.Length..^suffix.Length];
            if (userName is not null && itemName is not null)
            {
                var head = userName + afterUser + itemName + beforeDevice;
                if (body.StartsWith(head, StringComparison.Ordinal) && body.Length > head.Length)
                {
                    return body[head.Length..];
                }
            }

            foreach (var device in devices)
            {
                if (body.EndsWith(beforeDevice + device, StringComparison.Ordinal))
                {
                    return device;
                }
            }

            // Not this template's line unless the user part matches too.
            var cut = body.LastIndexOf(beforeDevice, StringComparison.Ordinal);
            if (cut > 0 && (userName is null || body.StartsWith(userName + afterUser, StringComparison.Ordinal)))
            {
                return body[(cut + beforeDevice.Length)..];
            }
        }

        return null;
    }
}
