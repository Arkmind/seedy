using System;
using System.Collections.Generic;
using Microsoft.Data.Sqlite;

namespace Jellyfin.Plugin.Seedy.Data;

/// <summary>Where a profile's device was last seen from, and since when Seedy has known it.</summary>
/// <param name="DeviceId">Jellyfin device id.</param>
/// <param name="Ip">Last address it connected from.</param>
/// <param name="FirstSeen">When Seedy first saw it (UTC).</param>
/// <param name="LastSeen">When Seedy last saw it (UTC).</param>
public sealed record DeviceSeen(string DeviceId, string? Ip, DateTime FirstSeen, DateTime LastSeen);

/// <summary>Device addresses. Jellyfin keeps a device's address only while its session lives, so Seedy remembers it.</summary>
public sealed partial class SeedyDatabase
{
    private static void MigrateDevices(SqliteConnection c)
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            CREATE TABLE IF NOT EXISTS device_seen (
                user_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                ip TEXT,
                first_seen INTEGER NOT NULL,
                last_seen INTEGER NOT NULL,
                PRIMARY KEY (user_id, device_id)
            );
            """;
        cmd.ExecuteNonQuery();
    }

    /// <summary>Notes that a profile's device connected from an address.</summary>
    /// <param name="userId">User id ("N").</param>
    /// <param name="deviceId">Device id.</param>
    /// <param name="ip">Address, or null to keep the last one.</param>
    /// <param name="at">When (UTC).</param>
    public void SawDevice(string userId, string deviceId, string? ip, DateTime at) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            INSERT INTO device_seen (user_id, device_id, ip, first_seen, last_seen) VALUES ($u, $d, $ip, $at, $at)
            ON CONFLICT (user_id, device_id) DO UPDATE SET ip = COALESCE(excluded.ip, ip), last_seen = excluded.last_seen;
            """;
        Bind(cmd, "$u", userId);
        Bind(cmd, "$d", deviceId);
        Bind(cmd, "$ip", ip);
        Bind(cmd, "$at", Ms(at));
        cmd.ExecuteNonQuery();
    });

    /// <summary>Gets what Seedy remembers of a profile's devices.</summary>
    /// <param name="userId">User id ("N").</param>
    /// <returns>Devices by id.</returns>
    public Dictionary<string, DeviceSeen> DevicesSeen(string userId)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT device_id, ip, first_seen, last_seen FROM device_seen WHERE user_id = $u";
        Bind(cmd, "$u", userId);
        var map = new Dictionary<string, DeviceSeen>(StringComparer.Ordinal);
        foreach (var d in ReadAll(cmd, r => new DeviceSeen(r.GetString(0), StrOrNull(r, 1), FromMs(r.GetInt64(2)), FromMs(r.GetInt64(3)))))
        {
            map[d.DeviceId] = d;
        }

        return map;
    }

    /// <summary>Forgets a signed-out device, so signing in on it again counts as a new device.</summary>
    /// <param name="userId">User id ("N").</param>
    /// <param name="deviceId">Device id.</param>
    public void ForgetDevice(string userId, string deviceId) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = "DELETE FROM device_seen WHERE user_id = $u AND device_id = $d";
        Bind(cmd, "$u", userId);
        Bind(cmd, "$d", deviceId);
        cmd.ExecuteNonQuery();
    });
}
