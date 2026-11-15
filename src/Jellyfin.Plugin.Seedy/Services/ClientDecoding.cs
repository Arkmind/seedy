using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Linq;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>
/// Which browsers decode their video themselves (the web app's experimental player). Jellyfin only sees a direct play,
/// so the web app says so as playback starts. The admin's Playing now matches it by device and title, and the play log
/// records those plays as ClientDecode.
/// </summary>
public sealed class ClientDecoding
{
    private static readonly TimeSpan KeepFor = TimeSpan.FromHours(12);
    private readonly ConcurrentDictionary<string, (string ItemId, DateTime At)> _devices = new(StringComparer.Ordinal);

    /// <summary>Notes what a device decodes as a play starts.</summary>
    /// <param name="deviceId">Device id, from the request.</param>
    /// <param name="itemId">Item id ("N").</param>
    /// <param name="onClient">Whether the browser decodes it; false clears the device (the player went back to the server).</param>
    public void Note(string deviceId, string itemId, bool onClient)
    {
        var now = DateTime.UtcNow;
        if (onClient)
        {
            _devices[deviceId] = (itemId, now);
        }
        else
        {
            _devices.TryRemove(deviceId, out _);
        }

        foreach (var stale in _devices.Where(d => now - d.Value.At > KeepFor).Select(d => d.Key).ToList())
        {
            _devices.TryRemove(stale, out _);
        }
    }

    /// <summary>Gets whether a device decodes a title itself.</summary>
    /// <param name="deviceId">Device id.</param>
    /// <param name="itemId">Item id ("N").</param>
    /// <returns>True while the browser decodes it.</returns>
    public bool Decodes(string deviceId, string itemId)
        => _devices.TryGetValue(deviceId, out var d) && string.Equals(d.ItemId, itemId, StringComparison.OrdinalIgnoreCase);

    /// <summary>Lists the devices decoding on their own and what they decode.</summary>
    /// <returns>Device id and item id pairs.</returns>
    public IReadOnlyList<(string DeviceId, string ItemId)> List()
        => _devices.Select(d => (d.Key, d.Value.ItemId)).ToList();
}
