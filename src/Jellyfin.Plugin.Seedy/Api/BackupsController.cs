using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.Json.Serialization;
using System.Threading.Tasks;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Common.Api;
using MediaBrowser.Common.Configuration;
using Jellyfin.Server.Implementations.SystemBackupService;
using MediaBrowser.Controller.SystemBackupService;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Seedy.Api;

#pragma warning disable SA1402, SA1600

public sealed class BackupFileDto
{
    [JsonPropertyName("fileName")] public string FileName { get; set; } = string.Empty;
    [JsonPropertyName("size")] public long Size { get; set; }
    [JsonPropertyName("kind")] public string Kind { get; set; } = "manual";
}

public sealed class BackupOptionsWire
{
    [JsonPropertyName("Database")] public bool Database { get; set; } = true;
    [JsonPropertyName("Metadata")] public bool Metadata { get; set; } = true;
    [JsonPropertyName("Subtitles")] public bool Subtitles { get; set; } = true;
    [JsonPropertyName("Trickplay")] public bool Trickplay { get; set; }
}

public sealed class BackupScheduleDto
{
    [JsonPropertyName("enabled")] public bool Enabled { get; set; }
    [JsonPropertyName("dayOfWeek")] public string DayOfWeek { get; set; } = "Sunday";
    [JsonPropertyName("timeOfDay")] public string TimeOfDay { get; set; } = "04:00";
    [JsonPropertyName("keep")] public int Keep { get; set; } = 4;
    [JsonPropertyName("options")] public BackupOptionsWire Options { get; set; } = new();
}

#pragma warning restore SA1402, SA1600

/// <summary>
/// What Jellyfin 12's backup API leaves out: file sizes, downloads, deleting, and a weekly schedule.
/// Paths avoid the app's /seedy/admin/backups page (routes are case-insensitive).
/// </summary>
[ApiController]
[Route("Seedy/Admin")]
[Authorize(Policy = Policies.RequiresElevation)]
[Produces("application/json")]
public class BackupsController : ControllerBase
{
    private readonly IApplicationPaths _paths;
    private readonly IBackupService _backups;

    /// <summary>
    /// Initializes a new instance of the <see cref="BackupsController"/> class.
    /// </summary>
    /// <param name="paths">Application paths.</param>
    /// <param name="backups">Backup service.</param>
    public BackupsController(IApplicationPaths paths, IBackupService backups)
    {
        _paths = paths;
        _backups = backups;
    }

    private string Folder => Path.Combine(_paths.DataPath, "backups");

    /// <summary>Resolves a backup file name to its path, refusing anything outside the backups folder.</summary>
    private string? Resolve(string fileName)
    {
        if (string.IsNullOrWhiteSpace(fileName) || fileName != Path.GetFileName(fileName) || !fileName.EndsWith(".zip", StringComparison.OrdinalIgnoreCase))
        {
            return null;
        }

        var full = Path.GetFullPath(Path.Combine(Folder, fileName));
        return full.StartsWith(Path.GetFullPath(Folder) + Path.DirectorySeparatorChar, StringComparison.Ordinal) && System.IO.File.Exists(full) ? full : null;
    }

    /// <summary>Backup archives with their size and whether Seedy made them on schedule.</summary>
    /// <returns>Files, newest first.</returns>
    [HttpGet("BackupFiles")]
    public ActionResult<List<BackupFileDto>> List()
    {
        if (!Directory.Exists(Folder))
        {
            return new List<BackupFileDto>();
        }

        var automatic = new HashSet<string>(ConfigMapper.Current.AutomaticBackups ?? [], StringComparer.OrdinalIgnoreCase);
        return new DirectoryInfo(Folder).EnumerateFiles("*.zip")
            .OrderByDescending(f => f.CreationTimeUtc)
            .Select(f => new BackupFileDto
            {
                FileName = f.Name,
                Size = f.Length,
                Kind = automatic.Contains(f.Name) ? "automatic" : f.Name.Contains("upgrade", StringComparison.OrdinalIgnoreCase) || f.Name.Contains("migration", StringComparison.OrdinalIgnoreCase) ? "upgrade" : "manual"
            })
            .ToList();
    }

    /// <summary>Downloads a backup archive.</summary>
    /// <param name="fileName">Archive file name.</param>
    /// <returns>The zip.</returns>
    [HttpGet("BackupFiles/{fileName}/File")]
    [Produces("application/zip")]
    public ActionResult Download([FromRoute] string fileName)
    {
        var path = Resolve(fileName);
        return path is null ? NotFound() : PhysicalFile(path, "application/zip", fileName, enableRangeProcessing: true);
    }

    /// <summary>Deletes a backup archive.</summary>
    /// <param name="fileName">Archive file name.</param>
    /// <returns>No content.</returns>
    [HttpDelete("BackupFiles/{fileName}")]
    public ActionResult Delete([FromRoute] string fileName)
    {
        var path = Resolve(fileName);
        if (path is null)
        {
            return NotFound();
        }

        System.IO.File.Delete(path);
        var plugin = Plugin.Instance;
        if (plugin is not null)
        {
            var c = plugin.Configuration;
            c.AutomaticBackups = (c.AutomaticBackups ?? []).Where(n => !string.Equals(n, fileName, StringComparison.OrdinalIgnoreCase)).ToArray();
            plugin.Save(c);
        }

        return NoContent();
    }

    /// <summary>The weekly backup schedule.</summary>
    /// <returns>Schedule.</returns>
    [HttpGet("BackupSchedule")]
    public ActionResult<BackupScheduleDto> GetSchedule() => ToDto();

    /// <summary>Saves the weekly backup schedule.</summary>
    /// <param name="body">Schedule.</param>
    /// <returns>Saved schedule.</returns>
    [HttpPost("BackupSchedule")]
    public ActionResult<BackupScheduleDto> SaveSchedule([FromBody] BackupScheduleDto body)
    {
        var plugin = Plugin.Instance;
        if (plugin is null)
        {
            return NotFound();
        }

        var c = plugin.Configuration;
        c.BackupScheduleEnabled = body.Enabled;
        c.BackupDayOfWeek = Enum.TryParse<DayOfWeek>(body.DayOfWeek, true, out var day) ? day.ToString() : "Sunday";
        c.BackupTimeOfDay = TimeSpan.TryParse(body.TimeOfDay, CultureInfo.InvariantCulture, out var t) && t >= TimeSpan.Zero && t < TimeSpan.FromDays(1)
            ? string.Create(CultureInfo.InvariantCulture, $"{t.Hours:00}:{t.Minutes:00}")
            : "04:00";
        c.BackupKeep = Math.Clamp(body.Keep, 1, 52);
        c.BackupDatabase = true; // A backup without the database can't restore a server.
        var options = body.Options ?? new BackupOptionsWire();
        c.BackupMetadata = options.Metadata;
        c.BackupSubtitles = options.Subtitles;
        c.BackupTrickplay = options.Trickplay;
        plugin.Save(c);
        return ToDto();
    }

    private static BackupScheduleDto ToDto()
    {
        var c = ConfigMapper.Current;
        return new BackupScheduleDto
        {
            Enabled = c.BackupScheduleEnabled,
            DayOfWeek = c.BackupDayOfWeek,
            TimeOfDay = c.BackupTimeOfDay,
            Keep = c.BackupKeep,
            Options = new BackupOptionsWire { Database = true, Metadata = c.BackupMetadata, Subtitles = c.BackupSubtitles, Trickplay = c.BackupTrickplay }
        };
    }
}
