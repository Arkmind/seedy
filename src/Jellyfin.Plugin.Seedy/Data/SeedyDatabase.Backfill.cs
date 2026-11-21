using System;
using System.Collections.Generic;
using Jellyfin.Plugin.Seedy.Services;

namespace Jellyfin.Plugin.Seedy.Data;

/// <summary>Plays imported from Jellyfin's activity log (play_session_id "backfill-…").</summary>
public sealed partial class SeedyDatabase
{
    private const string NotImported = "(play_session_id IS NULL OR play_session_id NOT LIKE '" + HistoryBackfill.SessionPrefix + "%')";

    /// <summary>When the oldest play Seedy recorded itself started: imports stop there.</summary>
    /// <returns>Start time (UTC), or null when Seedy hasn't recorded any.</returns>
    public DateTime? FirstOwnPlayAt()
    {
        using var c = Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = $"SELECT MIN(started_at) FROM plays WHERE {NotImported}";
        return cmd.ExecuteScalar() is long ms ? FromMs(ms) : null;
    }

    /// <summary>Replaces every imported play with these, in one go.</summary>
    /// <param name="plays">The plays to import.</param>
    /// <returns>How many imported plays were there before.</returns>
    public int ReplaceImported(IReadOnlyList<PlayRecord> plays)
    {
        var removed = 0;
        Write(c =>
        {
            using var tx = c.BeginTransaction();
            using (var del = c.CreateCommand())
            {
                del.Transaction = tx;
                del.CommandText = $"DELETE FROM plays WHERE play_session_id LIKE '{HistoryBackfill.SessionPrefix}%'";
                removed = del.ExecuteNonQuery();
            }

            foreach (var p in plays)
            {
                Insert(c, p, tx);
            }

            tx.Commit();
        });
        return removed;
    }
}
