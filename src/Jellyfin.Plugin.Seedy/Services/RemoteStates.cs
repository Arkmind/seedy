using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>
/// What a screen tells the phones that control it, beyond Jellyfin's play state: that it's asking for text (the TV's
/// Search) and what's typed there, the title it has focused, and whether the web player is full screen or in its mini
/// player. Kept in memory per device; a screen sends it again whenever it changes.
/// </summary>
public sealed class RemoteStates
{
    private static readonly TimeSpan KeepFor = TimeSpan.FromHours(6);
    private readonly ConcurrentDictionary<string, RemoteState> _devices = new(StringComparer.Ordinal);

    /// <summary>Saves what a device says about itself.</summary>
    /// <param name="state">The device's state, with its device and user ids.</param>
    public void Set(RemoteState state)
    {
        _devices[state.DeviceId] = state;
        var now = DateTime.UtcNow;
        foreach (var stale in _devices.Where(d => now - d.Value.At > KeepFor).Select(d => d.Key).ToList())
        {
            _devices.TryRemove(stale, out _);
        }
    }

    /// <summary>Lists the states saved for some users' devices.</summary>
    /// <param name="userIds">The users, or null for everyone.</param>
    /// <returns>States, newest first.</returns>
    public IReadOnlyList<RemoteState> For(ICollection<Guid>? userIds)
        => _devices.Values.Where(s => userIds is null || userIds.Contains(s.UserId)).OrderByDescending(s => s.At).ToList();
}

/// <summary>One device's remote state.</summary>
/// <param name="DeviceId">Device id.</param>
/// <param name="UserId">The person signed in there.</param>
/// <param name="Asking">What it asks text for ("search"), or null.</param>
/// <param name="Text">What's typed there.</param>
/// <param name="ItemId">The title it shows or has focused ("N"), or null.</param>
/// <param name="Fullscreen">The player fills the screen.</param>
/// <param name="MiniPlayer">The player is in its mini player (picture in picture).</param>
/// <param name="At">When it said so.</param>
public sealed record RemoteState(string DeviceId, Guid UserId, string? Asking, string? Text, string? ItemId, bool Fullscreen, bool MiniPlayer, DateTime At);
