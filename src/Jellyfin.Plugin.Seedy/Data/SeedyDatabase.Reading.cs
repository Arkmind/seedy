using System;
using System.Collections.Generic;
using Microsoft.Data.Sqlite;

namespace Jellyfin.Plugin.Seedy.Data;

/// <summary>Where a profile is in a book or comic, and the reader settings saved for it.</summary>
public sealed class ReadingRecord
{
    /// <summary>Gets or sets the book's item id ("N").</summary>
    public string ItemId { get; set; } = string.Empty;

    /// <summary>Gets or sets the reader's own position: an EPUB CFI, or a page index for comics and PDFs.</summary>
    public string Locator { get; set; } = string.Empty;

    /// <summary>Gets or sets how far through the book, 0 to 100.</summary>
    public double Percent { get; set; }

    /// <summary>Gets or sets the current chapter's title.</summary>
    public string? Chapter { get; set; }

    /// <summary>Gets or sets the current chapter, from 1.</summary>
    public int? ChapterIndex { get; set; }

    /// <summary>Gets or sets the number of chapters.</summary>
    public int? ChapterCount { get; set; }

    /// <summary>Gets or sets a value indicating whether the book was read to the end.</summary>
    public bool Finished { get; set; }

    /// <summary>Gets or sets the reader settings for this book (JSON object), or null.</summary>
    public string? Settings { get; set; }

    /// <summary>Gets or sets when this was last saved (UTC).</summary>
    public DateTime UpdatedAt { get; set; }
}

/// <summary>Reading positions. Jellyfin has no position for books, so Seedy keeps one per profile and book.</summary>
public sealed partial class SeedyDatabase
{
    private const string ReadingColumns = "item_id, locator, percent, chapter, chapter_index, chapter_count, finished, settings, updated_at";

    private static void MigrateReading(SqliteConnection c)
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = """
            CREATE TABLE IF NOT EXISTS reading (
                user_id TEXT NOT NULL,
                item_id TEXT NOT NULL,
                locator TEXT NOT NULL DEFAULT '',
                percent REAL NOT NULL DEFAULT 0,
                chapter TEXT,
                chapter_index INTEGER,
                chapter_count INTEGER,
                finished INTEGER NOT NULL DEFAULT 0,
                settings TEXT,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY (user_id, item_id)
            );
            CREATE INDEX IF NOT EXISTS ix_reading_user_updated ON reading(user_id, updated_at DESC);
            """;
        cmd.ExecuteNonQuery();
    }

    private static ReadingRecord ReadReading(SqliteDataReader r) => new()
    {
        ItemId = r.GetString(0),
        Locator = r.GetString(1),
        Percent = r.GetDouble(2),
        Chapter = r.IsDBNull(3) ? null : r.GetString(3),
        ChapterIndex = r.IsDBNull(4) ? null : r.GetInt32(4),
        ChapterCount = r.IsDBNull(5) ? null : r.GetInt32(5),
        Finished = r.GetInt64(6) != 0,
        Settings = r.IsDBNull(7) ? null : r.GetString(7),
        UpdatedAt = FromMs(r.GetInt64(8))
    };

    /// <summary>Gets a profile's place in a book.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="itemId">Book id.</param>
    /// <returns>The record, or null when the book was never opened.</returns>
    public ReadingRecord? GetReading(string userId, string itemId)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = $"SELECT {ReadingColumns} FROM reading WHERE user_id = $user AND item_id = $item";
        Bind(cmd, "$user", userId);
        Bind(cmd, "$item", itemId);
        using var r = cmd.ExecuteReader();
        return r.Read() ? ReadReading(r) : null;
    }

    /// <summary>Lists a profile's books, most recently read first.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="includeFinished">Whether finished books are included.</param>
    /// <param name="limit">Most records returned.</param>
    /// <returns>The records.</returns>
    public List<ReadingRecord> ListReading(string userId, bool includeFinished, int limit)
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = $"SELECT {ReadingColumns} FROM reading WHERE user_id = $user"
            + (includeFinished ? string.Empty : " AND finished = 0")
            + " ORDER BY updated_at DESC LIMIT $limit";
        Bind(cmd, "$user", userId);
        Bind(cmd, "$limit", limit);
        var list = new List<ReadingRecord>();
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            list.Add(ReadReading(r));
        }

        return list;
    }

    /// <summary>Saves a profile's place in a book.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="rec">The record; its UpdatedAt is ignored.</param>
    public void SaveReading(string userId, ReadingRecord rec) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = $"INSERT OR REPLACE INTO reading (user_id, {ReadingColumns}) VALUES ($user, $item, $locator, $percent, $chapter, $index, $count, $finished, $settings, $at)";
        Bind(cmd, "$user", userId);
        Bind(cmd, "$item", rec.ItemId);
        Bind(cmd, "$locator", rec.Locator);
        Bind(cmd, "$percent", Math.Clamp(rec.Percent, 0, 100));
        Bind(cmd, "$chapter", rec.Chapter);
        Bind(cmd, "$index", rec.ChapterIndex);
        Bind(cmd, "$count", rec.ChapterCount);
        Bind(cmd, "$finished", rec.Finished ? 1 : 0);
        Bind(cmd, "$settings", rec.Settings);
        Bind(cmd, "$at", Ms(DateTime.UtcNow));
        cmd.ExecuteNonQuery();
    });

    /// <summary>Forgets a profile's place in a book (mark as unread).</summary>
    /// <param name="userId">User id.</param>
    /// <param name="itemId">Book id.</param>
    public void DeleteReading(string userId, string itemId) => Write(c =>
    {
        using var cmd = c.CreateCommand();
        cmd.CommandText = "DELETE FROM reading WHERE user_id = $user AND item_id = $item";
        Bind(cmd, "$user", userId);
        Bind(cmd, "$item", itemId);
        cmd.ExecuteNonQuery();
    });
}
