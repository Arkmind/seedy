using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Threading;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Data;

/// <summary>One recorded play.</summary>
public sealed class PlayRecord
{
    /// <summary>Gets or sets the play id.</summary>
    public string Id { get; set; } = string.Empty;

    /// <summary>Gets or sets the user id ("N").</summary>
    public string UserId { get; set; } = string.Empty;

    /// <summary>Gets or sets the played item id ("N").</summary>
    public string ItemId { get; set; } = string.Empty;

    /// <summary>Gets or sets the title id: the series for episodes, the item otherwise ("N").</summary>
    public string TitleId { get; set; } = string.Empty;

    /// <summary>Gets or sets the item type (Movie, Episode, Video…).</summary>
    public string ItemType { get; set; } = string.Empty;

    /// <summary>Gets or sets the title name (series or movie).</summary>
    public string TitleName { get; set; } = string.Empty;

    /// <summary>Gets or sets the item name (episode name, or the movie again).</summary>
    public string ItemName { get; set; } = string.Empty;

    /// <summary>Gets or sets when playback started (UTC).</summary>
    public DateTime StartedAt { get; set; }

    /// <summary>Gets or sets when playback stopped (UTC); null while playing.</summary>
    public DateTime? StoppedAt { get; set; }

    /// <summary>Gets or sets the last known position.</summary>
    public long PositionTicks { get; set; }

    /// <summary>Gets or sets the runtime.</summary>
    public long RuntimeTicks { get; set; }

    /// <summary>Gets or sets a value indicating whether the play reached the end.</summary>
    public bool Finished { get; set; }

    /// <summary>Gets or sets the device name.</summary>
    public string? DeviceName { get; set; }

    /// <summary>Gets or sets the client name.</summary>
    public string? Client { get; set; }

    /// <summary>Gets or sets Jellyfin's play session id.</summary>
    public string? PlaySessionId { get; set; }

    /// <summary>Gets or sets the home row the play started from.</summary>
    public string? Origin { get; set; }
}

/// <summary>Plays per title, for the Top 10.</summary>
/// <param name="TitleId">Title id.</param>
/// <param name="Profiles">Distinct profiles.</param>
/// <param name="Plays">Plays.</param>
public sealed record TitleCount(string TitleId, int Profiles, int Plays);

/// <summary>
/// The play log, hidden titles, per-profile preferences and learned row order, in one SQLite file.
/// Jellyfin itself only keeps the last play of each title, so Seedy keeps its own log.
/// </summary>
public sealed class SeedyDatabase : IDisposable
{
    private readonly string _connectionString;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly ILogger<SeedyDatabase> _logger;

    /// <summary>
    /// Initializes a new instance of the <see cref="SeedyDatabase"/> class.
    /// </summary>
    /// <param name="logger">Logger.</param>
    public SeedyDatabase(ILogger<SeedyDatabase> logger)
    {
        _logger = logger;
        var folder = Plugin.Instance?.SeedyDataPath ?? Path.Combine(Path.GetTempPath(), "seedy");
        Directory.CreateDirectory(folder);
        _connectionString = new SqliteConnectionStringBuilder
        {
            DataSource = Path.Combine(folder, "seedy.db"),
            Mode = SqliteOpenMode.ReadWriteCreate,
            Cache = SqliteCacheMode.Private,
            Pooling = true
        }.ToString();
        Migrate();
    }

    private SqliteConnection Open()
    {
        var c = new SqliteConnection(_connectionString);
        c.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "PRAGMA busy_timeout = 5000;";
        cmd.ExecuteNonQuery();
        return c;
    }

    private void Migrate()
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            PRAGMA journal_mode = WAL;
            CREATE TABLE IF NOT EXISTS plays (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                title_id TEXT NOT NULL,
                item_type TEXT NOT NULL,
                title_name TEXT NOT NULL DEFAULT '',
                item_name TEXT NOT NULL DEFAULT '',
                started_at INTEGER NOT NULL,
                stopped_at INTEGER,
                position_ticks INTEGER NOT NULL DEFAULT 0,
                runtime_ticks INTEGER NOT NULL DEFAULT 0,
                finished INTEGER NOT NULL DEFAULT 0,
                device_name TEXT,
                client TEXT,
                play_session_id TEXT,
                origin TEXT
            );
            CREATE INDEX IF NOT EXISTS ix_plays_user_started ON plays(user_id, started_at DESC);
            CREATE INDEX IF NOT EXISTS ix_plays_started ON plays(started_at);
            CREATE INDEX IF NOT EXISTS ix_plays_session ON plays(play_session_id);
            CREATE TABLE IF NOT EXISTS hidden_titles (
                user_id TEXT NOT NULL,
                title_id TEXT NOT NULL,
                hidden_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, title_id)
            );
            CREATE TABLE IF NOT EXISTS user_prefs (
                user_id TEXT PRIMARY KEY,
                json TEXT NOT NULL,
                updated_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS row_order (
                user_id TEXT PRIMARY KEY,
                day TEXT NOT NULL,
                json TEXT NOT NULL
            );
            """;
        cmd.ExecuteNonQuery();
    }

    private static long Ms(DateTime d) => new DateTimeOffset(DateTime.SpecifyKind(d, DateTimeKind.Utc)).ToUnixTimeMilliseconds();

    private static DateTime FromMs(long ms) => DateTimeOffset.FromUnixTimeMilliseconds(ms).UtcDateTime;

    private void Write(Action<SqliteConnection> action)
    {
        _writeLock.Wait();
        try
        {
            using var c = Open();
            action(c);
        }
        catch (SqliteException ex)
        {
            _logger.LogError(ex, "Seedy database write failed");
            throw;
        }
        finally
        {
            _writeLock.Release();
        }
    }

    private static void Bind(SqliteCommand cmd, string name, object? value) => cmd.Parameters.AddWithValue(name, value ?? DBNull.Value);

    private static PlayRecord ReadPlay(SqliteDataReader r) => new()
    {
        Id = r.GetString(0),
        UserId = r.GetString(1),
        ItemId = r.GetString(2),
        TitleId = r.GetString(3),
        ItemType = r.GetString(4),
        TitleName = r.GetString(5),
        ItemName = r.GetString(6),
        StartedAt = FromMs(r.GetInt64(7)),
        StoppedAt = r.IsDBNull(8) ? null : FromMs(r.GetInt64(8)),
        PositionTicks = r.GetInt64(9),
        RuntimeTicks = r.GetInt64(10),
        Finished = r.GetInt64(11) != 0,
        DeviceName = r.IsDBNull(12) ? null : r.GetString(12),
        Client = r.IsDBNull(13) ? null : r.GetString(13),
        PlaySessionId = r.IsDBNull(14) ? null : r.GetString(14),
        Origin = r.IsDBNull(15) ? null : r.GetString(15)
    };

    private const string PlayColumns = "id, user_id, item_id, title_id, item_type, title_name, item_name, started_at, stopped_at, position_ticks, runtime_ticks, finished, device_name, client, play_session_id, origin";

    /// <summary>Inserts a play.</summary>
    /// <param name="p">The play.</param>
    public void InsertPlay(PlayRecord p) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = $"INSERT INTO plays ({PlayColumns}) VALUES ($id, $user, $item, $title, $type, $tname, $iname, $started, $stopped, $pos, $run, $fin, $dev, $client, $session, $origin)";
        Bind(cmd, "$id", p.Id);
        Bind(cmd, "$user", p.UserId);
        Bind(cmd, "$item", p.ItemId);
        Bind(cmd, "$title", p.TitleId);
        Bind(cmd, "$type", p.ItemType);
        Bind(cmd, "$tname", p.TitleName);
        Bind(cmd, "$iname", p.ItemName);
        Bind(cmd, "$started", Ms(p.StartedAt));
        Bind(cmd, "$stopped", p.StoppedAt is null ? null : Ms(p.StoppedAt.Value));
        Bind(cmd, "$pos", p.PositionTicks);
        Bind(cmd, "$run", p.RuntimeTicks);
        Bind(cmd, "$fin", p.Finished ? 1 : 0);
        Bind(cmd, "$dev", p.DeviceName);
        Bind(cmd, "$client", p.Client);
        Bind(cmd, "$session", p.PlaySessionId);
        Bind(cmd, "$origin", p.Origin);
        cmd.ExecuteNonQuery();
    });

    /// <summary>Updates a play's position, stop time and completion.</summary>
    /// <param name="p">The play.</param>
    public void UpdatePlay(PlayRecord p) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = "UPDATE plays SET stopped_at = $stopped, position_ticks = $pos, runtime_ticks = $run, finished = $fin, origin = COALESCE(origin, $origin), play_session_id = $session WHERE id = $id";
        Bind(cmd, "$id", p.Id);
        Bind(cmd, "$stopped", p.StoppedAt is null ? null : Ms(p.StoppedAt.Value));
        Bind(cmd, "$pos", p.PositionTicks);
        Bind(cmd, "$run", p.RuntimeTicks);
        Bind(cmd, "$fin", p.Finished ? 1 : 0);
        Bind(cmd, "$origin", p.Origin);
        Bind(cmd, "$session", p.PlaySessionId);
        cmd.ExecuteNonQuery();
    });

    /// <summary>Gets a user's most recent play of an item.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="itemId">Item id.</param>
    /// <returns>The play, if any.</returns>
    public PlayRecord? LatestPlay(string userId, string itemId)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = $"SELECT {PlayColumns} FROM plays WHERE user_id = $user AND item_id = $item ORDER BY started_at DESC LIMIT 1";
        Bind(cmd, "$user", userId);
        Bind(cmd, "$item", itemId);
        using var r = cmd.ExecuteReader();
        return r.Read() ? ReadPlay(r) : null;
    }

    /// <summary>Gets a play by id.</summary>
    /// <param name="id">Play id.</param>
    /// <returns>The play, if any.</returns>
    public PlayRecord? GetPlay(string id)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = $"SELECT {PlayColumns} FROM plays WHERE id = $id";
        Bind(cmd, "$id", id);
        using var r = cmd.ExecuteReader();
        return r.Read() ? ReadPlay(r) : null;
    }

    /// <summary>Lists a user's plays, newest first.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="types">Item types to include (empty = all).</param>
    /// <param name="search">Text to match in title or item name.</param>
    /// <param name="includeHidden">Whether hidden titles are included.</param>
    /// <param name="skip">Rows to skip.</param>
    /// <param name="take">Rows to take.</param>
    /// <param name="total">Total matching rows.</param>
    /// <returns>Plays plus whether each title is hidden.</returns>
    public List<(PlayRecord Play, bool Hidden)> History(string userId, IReadOnlyList<string> types, string? search, bool includeHidden, int skip, int take, out int total)
    {
        using var c = Open();
        var where = "p.user_id = $user";
        if (types.Count > 0)
        {
            var names = new List<string>();
            for (var i = 0; i < types.Count; i++)
            {
                names.Add("$t" + i.ToString(CultureInfo.InvariantCulture));
            }

            where += $" AND p.item_type IN ({string.Join(", ", names)})";
        }

        if (!string.IsNullOrWhiteSpace(search))
        {
            where += " AND (p.title_name LIKE $q ESCAPE '\\' OR p.item_name LIKE $q ESCAPE '\\')";
        }

        if (!includeHidden)
        {
            where += " AND h.title_id IS NULL";
        }

        void BindAll(SqliteCommand cmd)
        {
            Bind(cmd, "$user", userId);
            for (var i = 0; i < types.Count; i++)
            {
                Bind(cmd, "$t" + i.ToString(CultureInfo.InvariantCulture), types[i]);
            }

            if (!string.IsNullOrWhiteSpace(search))
            {
                var escaped = search.Trim().Replace("\\", "\\\\", StringComparison.Ordinal).Replace("%", "\\%", StringComparison.Ordinal).Replace("_", "\\_", StringComparison.Ordinal);
                Bind(cmd, "$q", "%" + escaped + "%");
            }
        }

        const string Join = "FROM plays p LEFT JOIN hidden_titles h ON h.user_id = p.user_id AND h.title_id = p.title_id";
        using (var count = c.CreateCommand())
        {
            count.CommandText = $"SELECT COUNT(*) {Join} WHERE {where}";
            BindAll(count);
            total = Convert.ToInt32(count.ExecuteScalar(), CultureInfo.InvariantCulture);
        }

        using var cmd = c.CreateCommand();
        cmd.CommandText = $"SELECT p.id, p.user_id, p.item_id, p.title_id, p.item_type, p.title_name, p.item_name, p.started_at, p.stopped_at, p.position_ticks, p.runtime_ticks, p.finished, p.device_name, p.client, p.play_session_id, p.origin, h.title_id IS NOT NULL {Join} WHERE {where} ORDER BY p.started_at DESC LIMIT $take OFFSET $skip";
        BindAll(cmd);
        Bind(cmd, "$take", take);
        Bind(cmd, "$skip", skip);
        var list = new List<(PlayRecord, bool)>();
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            list.Add((ReadPlay(r), r.GetInt64(16) != 0));
        }

        return list;
    }

    /// <summary>Counts plays per title since a date, one vote per profile.</summary>
    /// <param name="since">Start of the period (UTC).</param>
    /// <param name="excludedUsers">Profiles left out of the Top 10.</param>
    /// <param name="limit">Titles to return.</param>
    /// <returns>Titles, most profiles first.</returns>
    public List<TitleCount> TopTitles(DateTime since, IReadOnlyCollection<string> excludedUsers, int limit)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        var exclude = BuildIn(cmd, "$x", excludedUsers);
        cmd.CommandText = $"""
            SELECT p.title_id, COUNT(DISTINCT p.user_id) AS profiles, COUNT(*) AS plays, MAX(p.started_at) AS last
            FROM plays p
            WHERE p.started_at >= $since
              AND p.item_type IN ('Movie', 'Episode')
              {(exclude.Length > 0 ? $"AND p.user_id NOT IN ({exclude})" : string.Empty)}
              AND NOT EXISTS (SELECT 1 FROM hidden_titles h WHERE h.user_id = p.user_id AND h.title_id = p.title_id)
            GROUP BY p.title_id
            ORDER BY profiles DESC, plays DESC, last DESC
            LIMIT $limit
            """;
        Bind(cmd, "$since", Ms(since));
        Bind(cmd, "$limit", limit);
        var list = new List<TitleCount>();
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            list.Add(new TitleCount(r.GetString(0), r.GetInt32(1), r.GetInt32(2)));
        }

        return list;
    }

    /// <summary>Counts profiles that watched anything since a date.</summary>
    /// <param name="since">Start of the period (UTC).</param>
    /// <param name="excludedUsers">Profiles left out of the Top 10.</param>
    /// <returns>Active profiles.</returns>
    public int ActiveProfiles(DateTime since, IReadOnlyCollection<string> excludedUsers)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        var exclude = BuildIn(cmd, "$x", excludedUsers);
        cmd.CommandText = $"SELECT COUNT(DISTINCT user_id) FROM plays WHERE started_at >= $since {(exclude.Length > 0 ? $"AND user_id NOT IN ({exclude})" : string.Empty)}";
        Bind(cmd, "$since", Ms(since));
        return Convert.ToInt32(cmd.ExecuteScalar(), CultureInfo.InvariantCulture);
    }

    /// <summary>Counts a user's plays by the home row they started from.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="since">Start of the period (UTC).</param>
    /// <returns>Row kind → plays.</returns>
    public Dictionary<string, int> OriginCounts(string userId, DateTime since)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT origin, COUNT(*) FROM plays WHERE user_id = $user AND started_at >= $since AND origin IS NOT NULL GROUP BY origin";
        Bind(cmd, "$user", userId);
        Bind(cmd, "$since", Ms(since));
        var d = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            d[r.GetString(0)] = r.GetInt32(1);
        }

        return d;
    }

    /// <summary>Titles a user watched since a date, most recent first.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="since">Start of the period (UTC), or null for no limit.</param>
    /// <param name="halfCounts">Whether half-watched plays count, not just finished ones.</param>
    /// <returns>Title ids with their latest play time.</returns>
    public List<(string TitleId, DateTime Last)> WatchedTitles(string userId, DateTime? since, bool halfCounts)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        var watched = halfCounts ? "(finished = 1 OR (runtime_ticks > 0 AND position_ticks * 2 >= runtime_ticks))" : "finished = 1";
        cmd.CommandText = $"""
            SELECT title_id, MAX(started_at) AS last FROM plays
            WHERE user_id = $user AND {watched} {(since is null ? string.Empty : "AND started_at >= $since")}
              AND title_id NOT IN (SELECT title_id FROM hidden_titles WHERE user_id = $user)
            GROUP BY title_id ORDER BY last DESC LIMIT 100
            """;
        Bind(cmd, "$user", userId);
        if (since is not null)
        {
            Bind(cmd, "$since", Ms(since.Value));
        }

        var list = new List<(string, DateTime)>();
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            list.Add((r.GetString(0), FromMs(r.GetInt64(1))));
        }

        return list;
    }

    /// <summary>Counts plays for each user.</summary>
    /// <returns>User id → plays.</returns>
    public Dictionary<string, int> PlaysPerUser()
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT user_id, COUNT(*) FROM plays GROUP BY user_id";
        var d = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            d[r.GetString(0)] = r.GetInt32(1);
        }

        return d;
    }

    /// <summary>Counts every play.</summary>
    /// <returns>Plays.</returns>
    public int TotalPlays()
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT COUNT(*) FROM plays";
        return Convert.ToInt32(cmd.ExecuteScalar(), CultureInfo.InvariantCulture);
    }

    /// <summary>All of a user's plays, oldest first, for export.</summary>
    /// <param name="userId">User id.</param>
    /// <returns>Plays.</returns>
    public List<PlayRecord> AllPlays(string userId)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = $"SELECT {PlayColumns} FROM plays WHERE user_id = $user ORDER BY started_at";
        Bind(cmd, "$user", userId);
        var list = new List<PlayRecord>();
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            list.Add(ReadPlay(r));
        }

        return list;
    }

    /// <summary>Deletes one of a user's plays.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="id">Play id.</param>
    /// <returns>Whether a row was deleted.</returns>
    public bool DeletePlay(string userId, string id)
    {
        var n = 0;
        Write(c =>
        {
            using var cmd = c.CreateCommand();
            cmd.CommandText = "DELETE FROM plays WHERE id = $id AND user_id = $user";
            Bind(cmd, "$id", id);
            Bind(cmd, "$user", userId);
            n = cmd.ExecuteNonQuery();
        });
        return n > 0;
    }

    /// <summary>Deletes a user's plays, hidden titles and learned row order; or everyone's.</summary>
    /// <param name="userId">User id, or null for everyone.</param>
    public void ClearPlays(string? userId) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        if (userId is null)
        {
            cmd.CommandText = "DELETE FROM plays; DELETE FROM hidden_titles; DELETE FROM row_order;";
        }
        else
        {
            cmd.CommandText = "DELETE FROM plays WHERE user_id = $user; DELETE FROM hidden_titles WHERE user_id = $user; DELETE FROM row_order WHERE user_id = $user;";
            Bind(cmd, "$user", userId);
        }

        cmd.ExecuteNonQuery();
    });

    /// <summary>Deletes plays older than a date.</summary>
    /// <param name="before">Cut-off (UTC).</param>
    /// <returns>Rows deleted.</returns>
    public int DeletePlaysBefore(DateTime before)
    {
        var n = 0;
        Write(c =>
        {
            using var cmd = c.CreateCommand();
            cmd.CommandText = "DELETE FROM plays WHERE started_at < $before";
            Bind(cmd, "$before", Ms(before));
            n = cmd.ExecuteNonQuery();
        });
        return n;
    }

    /// <summary>Hides or unhides a title for a user.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="titleId">Title id.</param>
    /// <param name="hidden">Whether it is hidden.</param>
    public void SetHidden(string userId, string titleId, bool hidden) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = hidden
            ? "INSERT OR REPLACE INTO hidden_titles (user_id, title_id, hidden_at) VALUES ($user, $title, $at)"
            : "DELETE FROM hidden_titles WHERE user_id = $user AND title_id = $title";
        Bind(cmd, "$user", userId);
        Bind(cmd, "$title", titleId);
        Bind(cmd, "$at", Ms(DateTime.UtcNow));
        cmd.ExecuteNonQuery();
    });

    /// <summary>Gets the titles a user hid.</summary>
    /// <param name="userId">User id.</param>
    /// <returns>Title ids.</returns>
    public HashSet<string> HiddenTitles(string userId)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = "SELECT title_id FROM hidden_titles WHERE user_id = $user";
        Bind(cmd, "$user", userId);
        var set = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            set.Add(r.GetString(0));
        }

        return set;
    }

    /// <summary>Reads a stored JSON value from a keyed table.</summary>
    /// <param name="table">user_prefs or row_order.</param>
    /// <param name="userId">User id.</param>
    /// <returns>JSON and, for row_order, the day it was computed.</returns>
    public (string Json, string? Day)? ReadJson(string table, string userId)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = table == "row_order" ? "SELECT json, day FROM row_order WHERE user_id = $user" : "SELECT json, NULL FROM user_prefs WHERE user_id = $user";
        Bind(cmd, "$user", userId);
        using var r = cmd.ExecuteReader();
        if (!r.Read())
        {
            return null;
        }

        return (r.GetString(0), r.IsDBNull(1) ? null : r.GetString(1));
    }

    /// <summary>Stores a profile's preferences.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="json">Preferences JSON.</param>
    public void WritePrefs(string userId, string json) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = "INSERT OR REPLACE INTO user_prefs (user_id, json, updated_at) VALUES ($user, $json, $at)";
        Bind(cmd, "$user", userId);
        Bind(cmd, "$json", json);
        Bind(cmd, "$at", Ms(DateTime.UtcNow));
        cmd.ExecuteNonQuery();
    });

    /// <summary>Stores a profile's learned row order for a day.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="day">Day key (yyyy-MM-dd).</param>
    /// <param name="json">Row kinds JSON.</param>
    public void WriteRowOrder(string userId, string day, string json) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = "INSERT OR REPLACE INTO row_order (user_id, day, json) VALUES ($user, $day, $json)";
        Bind(cmd, "$user", userId);
        Bind(cmd, "$day", day);
        Bind(cmd, "$json", json);
        cmd.ExecuteNonQuery();
    });

    /// <summary>Forgets a profile's learned row order and the row each play started from.</summary>
    /// <param name="userId">User id.</param>
    public void ResetRowOrder(string userId) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = "DELETE FROM row_order WHERE user_id = $user; UPDATE plays SET origin = NULL WHERE user_id = $user;";
        Bind(cmd, "$user", userId);
        cmd.ExecuteNonQuery();
    });

    private static string BuildIn(SqliteCommand cmd, string prefix, IReadOnlyCollection<string> values)
    {
        var names = new List<string>();
        var i = 0;
        foreach (var v in values)
        {
            var name = prefix + i.ToString(CultureInfo.InvariantCulture);
            names.Add(name);
            Bind(cmd, name, v);
            i++;
        }

        return string.Join(", ", names);
    }

    /// <inheritdoc />
    public void Dispose()
    {
        _writeLock.Dispose();
    }
}
