using System;
using System.Collections.Generic;
using Microsoft.Data.Sqlite;

namespace Jellyfin.Plugin.Seedy.Data;

/// <summary>One moment the players report: an intro skipped, a "Still watching?" asked or answered.</summary>
/// <param name="UserId">User id ("N").</param>
/// <param name="Kind">introSkipped, stillAsked, stillYes or stillStop.</param>
/// <param name="ItemId">The item playing, if known ("N").</param>
/// <param name="Value">A number that goes with it (the milliseconds an intro skip saved).</param>
/// <param name="At">When it happened (UTC).</param>
public sealed record MomentRecord(string UserId, string Kind, string? ItemId, long Value, DateTime At);

/// <summary>Seedy Wrapped: the yearly snapshots, the moments they count, and each profile's last known time zone.</summary>
public sealed partial class SeedyDatabase
{
    private static void MigrateWrapped(SqliteConnection c)
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            CREATE TABLE IF NOT EXISTS wrapped (
                user_id TEXT NOT NULL,
                year INTEGER NOT NULL,
                json TEXT NOT NULL,
                built_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, year)
            );
            CREATE TABLE IF NOT EXISTS moments (
                user_id TEXT NOT NULL,
                kind TEXT NOT NULL,
                item_id TEXT,
                value INTEGER NOT NULL DEFAULT 0,
                at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS ix_moments_user_at ON moments(user_id, at);
            CREATE TABLE IF NOT EXISTS user_zones (
                user_id TEXT PRIMARY KEY,
                zone TEXT NOT NULL,
                seen_at INTEGER NOT NULL
            );
            """;
        cmd.ExecuteNonQuery();
    }

    /// <summary>Stores a profile's Wrapped for a year, replacing an older one.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="year">Year.</param>
    /// <param name="json">The Wrapped, as JSON.</param>
    /// <param name="builtAt">When it was built (UTC).</param>
    public void SaveWrapped(string userId, int year, string json, DateTime builtAt) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = "INSERT OR REPLACE INTO wrapped (user_id, year, json, built_at) VALUES ($user, $year, $json, $at)";
        Bind(cmd, "$user", userId);
        Bind(cmd, "$year", year);
        Bind(cmd, "$json", json);
        Bind(cmd, "$at", Ms(builtAt));
        cmd.ExecuteNonQuery();
    });

    /// <summary>Deletes every profile's Wrapped for a year, before building it again.</summary>
    /// <param name="year">Year.</param>
    public void DeleteWrapped(int year) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = "DELETE FROM wrapped WHERE year = $year";
        Bind(cmd, "$year", year);
        cmd.ExecuteNonQuery();
    });

    /// <summary>Reads a profile's Wrapped for a year.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="year">Year.</param>
    /// <returns>JSON and when it was built, or null.</returns>
    public (string Json, DateTime BuiltAt)? ReadWrapped(string userId, int year)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT json, built_at FROM wrapped WHERE user_id = $user AND year = $year";
        Bind(cmd, "$user", userId);
        Bind(cmd, "$year", year);
        using var r = cmd.ExecuteReader();
        return r.Read() ? (r.GetString(0), FromMs(r.GetInt64(1))) : null;
    }

    /// <summary>The years a profile has a Wrapped for, newest first.</summary>
    /// <param name="userId">User id.</param>
    /// <returns>Years.</returns>
    public List<int> WrappedYears(string userId)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT year FROM wrapped WHERE user_id = $user ORDER BY year DESC";
        Bind(cmd, "$user", userId);
        return ReadAll(cmd, r => r.GetInt32(0));
    }

    /// <summary>When a year's Wrapped was built for anyone, oldest first, or null when nobody has one.</summary>
    /// <param name="year">Year.</param>
    /// <returns>Build time (UTC).</returns>
    public DateTime? WrappedBuiltAt(int year)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT MIN(built_at) FROM wrapped WHERE year = $year";
        Bind(cmd, "$year", year);
        return cmd.ExecuteScalar() is long ms ? FromMs(ms) : null;
    }

    /// <summary>Records a moment.</summary>
    /// <param name="m">The moment.</param>
    public void InsertMoment(MomentRecord m) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = "INSERT INTO moments (user_id, kind, item_id, value, at) VALUES ($user, $kind, $item, $value, $at)";
        Bind(cmd, "$user", m.UserId);
        Bind(cmd, "$kind", m.Kind);
        Bind(cmd, "$item", m.ItemId);
        Bind(cmd, "$value", m.Value);
        Bind(cmd, "$at", Ms(m.At));
        cmd.ExecuteNonQuery();
    });

    /// <summary>A profile's moments in a period.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="from">Start (UTC, inclusive).</param>
    /// <param name="to">End (UTC, exclusive).</param>
    /// <returns>Moments, oldest first.</returns>
    public List<MomentRecord> MomentsBetween(string userId, DateTime from, DateTime to)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT user_id, kind, item_id, value, at FROM moments WHERE user_id = $user AND at >= $from AND at < $to ORDER BY at";
        Bind(cmd, "$user", userId);
        Bind(cmd, "$from", Ms(from));
        Bind(cmd, "$to", Ms(to));
        return ReadAll(cmd, r => new MomentRecord(r.GetString(0), r.GetString(1), StrOrNull(r, 2), r.GetInt64(3), FromMs(r.GetInt64(4))));
    }

    /// <summary>Remembers the time zone a profile's apps run in, for building its Wrapped.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="zone">IANA time zone id.</param>
    public void SetZone(string userId, string zone) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = "INSERT OR REPLACE INTO user_zones (user_id, zone, seen_at) VALUES ($user, $zone, $at)";
        Bind(cmd, "$user", userId);
        Bind(cmd, "$zone", zone);
        Bind(cmd, "$at", Ms(DateTime.UtcNow));
        cmd.ExecuteNonQuery();
    });

    /// <summary>The time zone each profile's apps last ran in.</summary>
    /// <returns>User id → IANA time zone id.</returns>
    public Dictionary<string, string> Zones()
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT user_id, zone FROM user_zones";
        var map = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            map[r.GetString(0)] = r.GetString(1);
        }

        return map;
    }
}
