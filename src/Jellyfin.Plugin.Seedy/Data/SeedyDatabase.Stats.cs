using System;
using System.Collections.Generic;
using Microsoft.Data.Sqlite;

namespace Jellyfin.Plugin.Seedy.Data;

/// <summary>What the Statistics pages read from the play log.</summary>
public sealed partial class SeedyDatabase
{
    private static readonly (string Name, string Type)[] StatsColumns =
    [
        ("watched_ms", "INTEGER"),
        ("play_method", "TEXT"),
        ("transcode_reasons", "TEXT"),
        ("bitrate", "INTEGER"),
        ("library_id", "TEXT"),
        ("video_codec", "TEXT"),
        ("subtitle_codec", "TEXT")
    ];

    private static void MigrateStats(SqliteConnection c)
    {
        var have = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        using (var info = c.CreateCommand())
        {
            info.CommandText = "PRAGMA table_info(plays)";
            using var r = info.ExecuteReader();
            while (r.Read())
            {
                have.Add(r.GetString(1));
            }
        }

        foreach (var (name, type) in StatsColumns)
        {
            if (have.Contains(name))
            {
                continue;
            }

            using var alter = c.CreateCommand();
            alter.CommandText = $"ALTER TABLE plays ADD COLUMN {name} {type}";
            alter.ExecuteNonQuery();
        }
    }

    /// <summary>Every play that started in a period, oldest first.</summary>
    /// <param name="from">Start (UTC, inclusive).</param>
    /// <param name="to">End (UTC, exclusive).</param>
    /// <returns>Plays.</returns>
    public List<PlayRecord> PlaysBetween(DateTime from, DateTime to)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = $"SELECT {PlayColumns} FROM plays WHERE started_at >= $from AND started_at < $to ORDER BY started_at";
        Bind(cmd, "$from", Ms(from));
        Bind(cmd, "$to", Ms(to));
        var list = new List<PlayRecord>();
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            list.Add(ReadPlay(r));
        }

        return list;
    }

    /// <summary>The app each device played with last, by device name.</summary>
    /// <returns>Device name and app, oldest first, so later entries win.</returns>
    public List<(string Device, string Client)> DeviceApps()
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT device_name, client, MAX(started_at) AS last FROM plays WHERE device_name IS NOT NULL AND client IS NOT NULL GROUP BY device_name, client ORDER BY last";
        var list = new List<(string, string)>();
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            list.Add((r.GetString(0), r.GetString(1)));
        }

        return list;
    }

    /// <summary>When the oldest play in the log started.</summary>
    /// <returns>Start time (UTC), or null when the log is empty.</returns>
    public DateTime? FirstPlayAt()
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT MIN(started_at) FROM plays";
        return cmd.ExecuteScalar() is long ms ? FromMs(ms) : null;
    }

    /// <summary>A profile's latest reading session of a book, for extending it.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="itemId">Book id.</param>
    /// <param name="since">Only sessions last saved after this (UTC).</param>
    /// <returns>The play, if any.</returns>
    public PlayRecord? OpenReading(string userId, string itemId, DateTime since)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = $"SELECT {PlayColumns} FROM plays WHERE user_id = $user AND item_id = $item AND item_type = 'Book' AND COALESCE(stopped_at, started_at) >= $since ORDER BY started_at DESC LIMIT 1";
        Bind(cmd, "$user", userId);
        Bind(cmd, "$item", itemId);
        Bind(cmd, "$since", Ms(since));
        using var r = cmd.ExecuteReader();
        return r.Read() ? ReadPlay(r) : null;
    }
}
