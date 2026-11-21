using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Model.Tasks;

namespace Jellyfin.Plugin.Seedy.Tasks;

/// <summary>Imports the plays Jellyfin's activity log remembers from before Seedy. Only runs when an admin starts it.</summary>
public sealed class HistoryBackfillTask : IScheduledTask
{
    private readonly HistoryBackfill _backfill;

    /// <summary>
    /// Initializes a new instance of the <see cref="HistoryBackfillTask"/> class.
    /// </summary>
    /// <param name="backfill">The import.</param>
    public HistoryBackfillTask(HistoryBackfill backfill)
    {
        _backfill = backfill;
    }

    /// <inheritdoc />
    public string Name => "Seedy: import Jellyfin play history";

    /// <inheritdoc />
    public string Key => "SeedyHistoryBackfill";

    /// <inheritdoc />
    public string Description => "Adds the plays from before Seedy that Jellyfin's activity log remembers. Running it again redoes the import.";

    /// <inheritdoc />
    public string Category => "Seedy";

    /// <inheritdoc />
    public async Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        await _backfill.RunAsync(true, cancellationToken).ConfigureAwait(false);
        progress.Report(100);
    }

    /// <inheritdoc />
    public IEnumerable<TaskTriggerInfo> GetDefaultTriggers() => [];
}
