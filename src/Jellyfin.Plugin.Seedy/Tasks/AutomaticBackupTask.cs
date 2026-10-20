using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Server.Implementations.SystemBackupService;
using MediaBrowser.Controller.SystemBackupService;
using MediaBrowser.Model.Tasks;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Tasks;

/// <summary>
/// Makes the weekly backup set in Admin · Backups and keeps only the newest few. Checks hourly whether one is due,
/// so changing the day or time in the admin needs no trigger editing.
/// </summary>
public sealed class AutomaticBackupTask : IScheduledTask
{
    private readonly IBackupService _backups;
    private readonly ILogger<AutomaticBackupTask> _logger;

    /// <summary>
    /// Initializes a new instance of the <see cref="AutomaticBackupTask"/> class.
    /// </summary>
    /// <param name="backups">Backup service.</param>
    /// <param name="logger">Logger.</param>
    public AutomaticBackupTask(IBackupService backups, ILogger<AutomaticBackupTask> logger)
    {
        _backups = backups;
        _logger = logger;
    }

    /// <inheritdoc />
    public string Name => "Seedy: weekly backup";

    /// <inheritdoc />
    public string Key => "SeedyAutomaticBackup";

    /// <inheritdoc />
    public string Description => "Makes the weekly backup set in Backups and removes old ones";

    /// <inheritdoc />
    public string Category => "Seedy";

    /// <inheritdoc />
    public async Task ExecuteAsync(IProgress<double> progress, CancellationToken cancellationToken)
    {
        var plugin = Plugin.Instance;
        var c = plugin?.Configuration;
        if (plugin is null || c is null || !c.BackupScheduleEnabled || !Due(c))
        {
            progress.Report(100);
            return;
        }

        _logger.LogInformation("Seedy is making the weekly backup");
        var manifest = await _backups.CreateBackupAsync(new BackupOptionsDto
        {
            Database = true,
            Metadata = c.BackupMetadata,
            Subtitles = c.BackupSubtitles,
            Trickplay = c.BackupTrickplay
        }).ConfigureAwait(false);

        // A backup can take minutes, and the configuration may have been saved (or replaced) meanwhile: record the
        // backup on the current one, so that save isn't undone.
        c = plugin.Configuration;
        var made = Path.GetFileName(manifest.Path);
        var list = (c.AutomaticBackups ?? []).Append(made).ToList();
        var folder = Path.GetDirectoryName(manifest.Path) ?? string.Empty;
        while (list.Count > Math.Max(1, c.BackupKeep))
        {
            var oldest = list[0];
            list.RemoveAt(0);

            // Only ever an archive directly in the backups folder, whatever the stored name says.
            var name = Path.GetFileName(oldest);
            if (string.IsNullOrEmpty(name) || !name.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
            {
                continue;
            }

            var path = Path.Combine(folder, name);
            if (File.Exists(path))
            {
                File.Delete(path);
                _logger.LogInformation("Seedy removed the old automatic backup {File}", name);
            }
        }

        c.AutomaticBackups = list.ToArray();
        plugin.Save(c);
        progress.Report(100);
    }

    /// <summary>
    /// Due when the latest weekly slot has passed and no automatic backup was made since it. A slot missed while the
    /// server was off is caught up later, but only for a schedule that was already running the week before, or within
    /// a day of the slot: switching the schedule on (or back on) mid-week waits for the next slot instead of backing
    /// up straight away.
    /// </summary>
    private static bool Due(Configuration.PluginConfiguration c)
    {
        var now = DateTime.Now;
        if (!Enum.TryParse<DayOfWeek>(c.BackupDayOfWeek, true, out var day) || !TimeSpan.TryParse(c.BackupTimeOfDay, CultureInfo.InvariantCulture, out var time))
        {
            return false;
        }

        var daysSince = ((int)now.DayOfWeek - (int)day + 7) % 7;
        var slot = now.Date.AddDays(-daysSince).Add(time);
        if (slot > now)
        {
            slot = slot.AddDays(-7);
        }

        var last = LastMade(c);
        if (last >= slot)
        {
            return false;
        }

        return now - slot < TimeSpan.FromDays(1) || last >= slot.AddDays(-7);
    }

    private static DateTime LastMade(Configuration.PluginConfiguration c)
    {
        // Backup names carry their creation time: jellyfin-backup-yyyyMMddHHmmss.zip.
        var last = (c.AutomaticBackups ?? []).LastOrDefault();
        if (last is null)
        {
            return DateTime.MinValue;
        }

        var digits = new string(last.Where(char.IsDigit).ToArray());
        return digits.Length >= 14 && DateTime.TryParseExact(digits[..14], "yyyyMMddHHmmss", CultureInfo.InvariantCulture, DateTimeStyles.AssumeLocal, out var made)
            ? made
            : DateTime.MinValue;
    }

    /// <inheritdoc />
    public IEnumerable<TaskTriggerInfo> GetDefaultTriggers()
    {
        yield return new TaskTriggerInfo { Type = TaskTriggerInfoType.IntervalTrigger, IntervalTicks = TimeSpan.FromHours(1).Ticks };
    }
}
