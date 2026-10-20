using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Seedy.Data;
using MediaBrowser.Model.Tasks;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Tasks;

/// <summary>Deletes plays older than the admin's "Keep plays for" setting, every night.</summary>
public sealed class PlayLogCleanupTask : IScheduledTask
{
    private readonly SeedyDatabase _db;
    private readonly ILogger<PlayLogCleanupTask> _logger;

    /// <summary>
    /// Initializes a new instance of the <see cref="PlayLogCleanupTask"/> class.
    /// </summary>
    /// <param name="db">Database.</param>
    /// <param name="logger">Logger.</param>
    public PlayLogCleanupTask(SeedyDatabase db, ILogger<PlayLogCleanupTask> logger)
    {
        _db = db;
        _logger = logger;
    }

    /// <inheritdoc />
    public string Name => "Seedy: trim play log";

    /// <inheritdoc />
    public string Key => "SeedyPlayLogCleanup";

    /// <inheritdoc />
    public string Description => "Deletes plays older than the retention setting";

    /// <inheritdoc />
    public string Category => "Seedy";

    /// <inheritdoc />
    public Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        var days = Plugin.Instance?.Configuration.PlayLogRetentionDays ?? 365;
        if (days > 0)
        {
            var removed = _db.DeletePlaysBefore(DateTime.UtcNow.AddDays(-days));
            if (removed > 0)
            {
                _logger.LogInformation("Seedy removed {Count} plays older than {Days} days", removed, days);
            }
        }

        progress.Report(100);
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public IEnumerable<TaskTriggerInfo> GetDefaultTriggers()
    {
        yield return new TaskTriggerInfo { Type = TaskTriggerInfoType.DailyTrigger, TimeOfDayTicks = new TimeSpan(4, 30, 0).Ticks };
    }
}
