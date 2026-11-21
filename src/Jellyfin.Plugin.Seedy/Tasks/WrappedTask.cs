using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Tasks;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Tasks;

/// <summary>Builds everyone's Seedy Wrapped once December starts, so it stays after the play log is trimmed.</summary>
public sealed class WrappedTask : IScheduledTask
{
    private readonly Wrapped _wrapped;
    private readonly IUserManager _users;
    private readonly ILogger<WrappedTask> _logger;

    /// <summary>
    /// Initializes a new instance of the <see cref="WrappedTask"/> class.
    /// </summary>
    /// <param name="wrapped">Wrapped builder.</param>
    /// <param name="users">User manager.</param>
    /// <param name="logger">Logger.</param>
    public WrappedTask(Wrapped wrapped, IUserManager users, ILogger<WrappedTask> logger)
    {
        _wrapped = wrapped;
        _users = users;
        _logger = logger;
    }

    /// <inheritdoc />
    public string Name => "Seedy: build Wrapped";

    /// <inheritdoc />
    public string Key => "SeedyWrapped";

    /// <inheritdoc />
    public string Description => "In December, builds each profile's Seedy Wrapped for the year";

    /// <inheritdoc />
    public string Category => "Seedy";

    /// <inheritdoc />
    public Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        // Each profile's December starts in its own time zone.
        var due = _users.GetUsers()
            .Select(u => (User: u, Year: _wrapped.OpenYear(u.Id.ToString("N"))))
            .Where(x => x.Year is not null)
            .GroupBy(x => x.Year!.Value);
        foreach (var year in due)
        {
            var built = _wrapped.BuildYear(year.Key, year.Select(x => x.User).ToList(), false);
            if (built > 0)
            {
                _logger.LogInformation("Seedy built {Count} Wrapped for {Year}", built, year.Key);
            }
        }

        progress.Report(100);
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public IEnumerable<TaskTriggerInfo> GetDefaultTriggers()
    {
        yield return new TaskTriggerInfo { Type = TaskTriggerInfoType.DailyTrigger, TimeOfDayTicks = new TimeSpan(5, 0, 0).Ticks };
    }
}
