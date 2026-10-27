using System;
using System.Collections.Generic;
using System.Globalization;
using Microsoft.Data.Sqlite;

namespace Jellyfin.Plugin.Seedy.Data;

/// <summary>A music or book request in Seedy's own queue. Seedy approves them, then sends approved ones to Lidarr,
/// Shelfarr, Librarr or a Readarr-API app and follows them there.</summary>
public sealed class MediaRequestRecord
{
    /// <summary>Gets or sets the request id (32 hex digits).</summary>
    public string Id { get; set; } = string.Empty;

    /// <summary>Gets or sets the profile that asked ("N").</summary>
    public string UserId { get; set; } = string.Empty;

    /// <summary>Gets or sets the profile's name when it asked.</summary>
    public string UserName { get; set; } = string.Empty;

    /// <summary>Gets or sets "music" or "book".</summary>
    public string Kind { get; set; } = "music";

    /// <summary>Gets or sets the app it goes to (lidarr, shelfarr, librarr, readarr); books may use several, see <see cref="Parts"/>.</summary>
    public string Service { get; set; } = string.Empty;

    /// <summary>Gets or sets what was asked for: artist (whole discography), albums, future (new releases only) or book.</summary>
    public string Mode { get; set; } = "albums";

    /// <summary>Gets or sets the MusicBrainz artist id, or the book's work id.</summary>
    public string ForeignId { get; set; } = string.Empty;

    /// <summary>Gets or sets the artist's or book's name.</summary>
    public string Title { get; set; } = string.Empty;

    /// <summary>Gets or sets the album's artist or the book's author.</summary>
    public string? Subtitle { get; set; }

    /// <summary>Gets or sets the cover image address.</summary>
    public string? CoverUrl { get; set; }

    /// <summary>Gets or sets how much of the person's limit it uses (albums, or books with an ebook and audiobook as two).</summary>
    public int Units { get; set; }

    /// <summary>Gets or sets the request's details (JSON): albums, formats, series, language.</summary>
    public string Data { get; set; } = "{}";

    /// <summary>Gets or sets where it is in each app (JSON array, one per format or one for Lidarr).</summary>
    public string Parts { get; set; } = "[]";

    /// <summary>Gets or sets pending, approved, searching, downloading, ready, failed, declined or cancelled.</summary>
    public string Status { get; set; } = "pending";

    /// <summary>Gets or sets the detail line under it (JSON: a code and its values), or null.</summary>
    public string? Note { get; set; }

    /// <summary>Gets or sets the download progress, 0 to 1, while downloading.</summary>
    public double? Progress { get; set; }

    /// <summary>Gets or sets a value indicating whether it needs a look (nothing found for days, an app reported a problem).</summary>
    public bool Attention { get; set; }

    /// <summary>Gets or sets the Jellyfin item it became ("N"), once it's on the server.</summary>
    public string? ItemId { get; set; }

    /// <summary>Gets or sets when it was asked for (UTC).</summary>
    public DateTime CreatedAt { get; set; }

    /// <summary>Gets or sets when it last changed (UTC).</summary>
    public DateTime UpdatedAt { get; set; }

    /// <summary>Gets or sets when it was approved or declined (UTC).</summary>
    public DateTime? DecidedAt { get; set; }

    /// <summary>Gets or sets who approved or declined it.</summary>
    public string? DecidedBy { get; set; }

    /// <summary>Gets or sets when it was sent to the app (UTC).</summary>
    public DateTime? SentAt { get; set; }

    /// <summary>Gets or sets when a search last started (UTC).</summary>
    public DateTime? SearchedAt { get; set; }

    /// <summary>Gets or sets when it was ready (UTC).</summary>
    public DateTime? ReadyAt { get; set; }
}

/// <summary>Seedy's music and book request queue.</summary>
public sealed partial class SeedyDatabase
{
    private const string RequestColumns = "id, user_id, user_name, kind, service, mode, foreign_id, title, subtitle, cover_url, units, data, parts, status, note, progress, attention, item_id, created_at, updated_at, decided_at, decided_by, sent_at, searched_at, ready_at";

    private static void MigrateRequests(SqliteConnection c)
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            CREATE TABLE IF NOT EXISTS media_requests (
                id TEXT PRIMARY KEY,
                user_id TEXT NOT NULL,
                user_name TEXT NOT NULL DEFAULT '',
                kind TEXT NOT NULL,
                service TEXT NOT NULL DEFAULT '',
                mode TEXT NOT NULL,
                foreign_id TEXT NOT NULL DEFAULT '',
                title TEXT NOT NULL,
                subtitle TEXT,
                cover_url TEXT,
                units INTEGER NOT NULL DEFAULT 0,
                data TEXT NOT NULL DEFAULT '{}',
                parts TEXT NOT NULL DEFAULT '[]',
                status TEXT NOT NULL,
                note TEXT,
                progress REAL,
                attention INTEGER NOT NULL DEFAULT 0,
                item_id TEXT,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                decided_at INTEGER,
                decided_by TEXT,
                sent_at INTEGER,
                searched_at INTEGER,
                ready_at INTEGER
            );
            CREATE INDEX IF NOT EXISTS ix_media_requests_user ON media_requests(user_id, created_at DESC);
            CREATE INDEX IF NOT EXISTS ix_media_requests_status ON media_requests(status);
            """;
        cmd.ExecuteNonQuery();
    }

    private static MediaRequestRecord ReadRequest(SqliteDataReader r) => new()
    {
        Id = r.GetString(0),
        UserId = r.GetString(1),
        UserName = r.GetString(2),
        Kind = r.GetString(3),
        Service = r.GetString(4),
        Mode = r.GetString(5),
        ForeignId = r.GetString(6),
        Title = r.GetString(7),
        Subtitle = r.IsDBNull(8) ? null : r.GetString(8),
        CoverUrl = r.IsDBNull(9) ? null : r.GetString(9),
        Units = r.GetInt32(10),
        Data = r.GetString(11),
        Parts = r.GetString(12),
        Status = r.GetString(13),
        Note = r.IsDBNull(14) ? null : r.GetString(14),
        Progress = r.IsDBNull(15) ? null : r.GetDouble(15),
        Attention = r.GetInt64(16) != 0,
        ItemId = r.IsDBNull(17) ? null : r.GetString(17),
        CreatedAt = FromMs(r.GetInt64(18)),
        UpdatedAt = FromMs(r.GetInt64(19)),
        DecidedAt = r.IsDBNull(20) ? null : FromMs(r.GetInt64(20)),
        DecidedBy = r.IsDBNull(21) ? null : r.GetString(21),
        SentAt = r.IsDBNull(22) ? null : FromMs(r.GetInt64(22)),
        SearchedAt = r.IsDBNull(23) ? null : FromMs(r.GetInt64(23)),
        ReadyAt = r.IsDBNull(24) ? null : FromMs(r.GetInt64(24))
    };

    private static long? MsOrNull(DateTime? d) => d is null ? null : Ms(d.Value);

    /// <summary>Saves a request, adding it or replacing the stored one.</summary>
    /// <param name="q">The request.</param>
    public void SaveMediaRequest(MediaRequestRecord q) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = $"INSERT OR REPLACE INTO media_requests ({RequestColumns}) VALUES ($id, $user, $uname, $kind, $service, $mode, $foreign, $title, $sub, $cover, $units, $data, $parts, $status, $note, $progress, $attention, $item, $created, $updated, $decided, $decidedBy, $sent, $searched, $ready)";
        Bind(cmd, "$id", q.Id);
        Bind(cmd, "$user", q.UserId);
        Bind(cmd, "$uname", q.UserName);
        Bind(cmd, "$kind", q.Kind);
        Bind(cmd, "$service", q.Service);
        Bind(cmd, "$mode", q.Mode);
        Bind(cmd, "$foreign", q.ForeignId);
        Bind(cmd, "$title", q.Title);
        Bind(cmd, "$sub", q.Subtitle);
        Bind(cmd, "$cover", q.CoverUrl);
        Bind(cmd, "$units", q.Units);
        Bind(cmd, "$data", q.Data);
        Bind(cmd, "$parts", q.Parts);
        Bind(cmd, "$status", q.Status);
        Bind(cmd, "$note", q.Note);
        Bind(cmd, "$progress", q.Progress);
        Bind(cmd, "$attention", q.Attention ? 1 : 0);
        Bind(cmd, "$item", q.ItemId);
        Bind(cmd, "$created", Ms(q.CreatedAt));
        Bind(cmd, "$updated", Ms(q.UpdatedAt));
        Bind(cmd, "$decided", MsOrNull(q.DecidedAt));
        Bind(cmd, "$decidedBy", q.DecidedBy);
        Bind(cmd, "$sent", MsOrNull(q.SentAt));
        Bind(cmd, "$searched", MsOrNull(q.SearchedAt));
        Bind(cmd, "$ready", MsOrNull(q.ReadyAt));
        cmd.ExecuteNonQuery();
    });

    /// <summary>Gets a request.</summary>
    /// <param name="id">Request id.</param>
    /// <returns>The request, or null.</returns>
    public MediaRequestRecord? GetMediaRequest(string id)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = $"SELECT {RequestColumns} FROM media_requests WHERE id = $id";
        Bind(cmd, "$id", id);
        using var r = cmd.ExecuteReader();
        return r.Read() ? ReadRequest(r) : null;
    }

    /// <summary>Lists requests, newest first.</summary>
    /// <param name="userId">Only this profile's, or everyone's when null.</param>
    /// <param name="kind">Only music or book requests, or both when null.</param>
    /// <param name="statuses">Only these statuses, or all when null.</param>
    /// <param name="limit">Most results.</param>
    /// <returns>Requests.</returns>
    public List<MediaRequestRecord> ListMediaRequests(string? userId, string? kind, IReadOnlyCollection<string>? statuses, int limit = 500)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        var where = new List<string>();
        if (userId is not null)
        {
            where.Add("user_id = $user");
            Bind(cmd, "$user", userId);
        }

        if (kind is not null)
        {
            where.Add("kind = $kind");
            Bind(cmd, "$kind", kind);
        }

        if (statuses is { Count: > 0 })
        {
            where.Add("status IN (" + BuildIn(cmd, "$st", statuses) + ")");
        }

        cmd.CommandText = $"SELECT {RequestColumns} FROM media_requests"
            + (where.Count > 0 ? " WHERE " + string.Join(" AND ", where) : string.Empty)
            + " ORDER BY created_at DESC LIMIT " + Math.Clamp(limit, 1, 5000).ToString(CultureInfo.InvariantCulture);
        var list = new List<MediaRequestRecord>();
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            list.Add(ReadRequest(r));
        }

        return list;
    }

    /// <summary>Removes a request.</summary>
    /// <param name="id">Request id.</param>
    public void DeleteMediaRequest(string id) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = "DELETE FROM media_requests WHERE id = $id";
        Bind(cmd, "$id", id);
        cmd.ExecuteNonQuery();
    });
}
