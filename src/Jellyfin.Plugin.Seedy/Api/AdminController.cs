using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Plugin.Seedy.Data;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Common.Api;
using MediaBrowser.Common.Updates;
using MediaBrowser.Controller;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Globalization;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>Seedy's admin: general settings, home screen, requests and the play log. Administrators only.</summary>
[ApiController]
[Route("Seedy/Admin")]
[Authorize(Policy = Policies.RequiresElevation)]
[Produces("application/json")]
public class AdminController : ControllerBase
{
    private static readonly string[] LogoExtensions = [".svg", ".png", ".webp", ".jpg", ".jpeg"];
    private static (DateTime At, string? Version)? _latest;

    private readonly IUserManager _users;
    private readonly IServerApplicationHost _host;
    private readonly IInstallationManager _installs;
    private readonly ILocalizationManager _localization;
    private readonly SeedyDatabase _db;
    private readonly SeerrService _seerr;
    private readonly Services.Media.ServiceHttp _media;
    private readonly ILogger<AdminController> _logger;

    /// <summary>
    /// Initializes a new instance of the <see cref="AdminController"/> class.
    /// </summary>
    /// <param name="users">User manager.</param>
    /// <param name="host">Server host.</param>
    /// <param name="installs">Installation manager.</param>
    /// <param name="localization">Localization manager.</param>
    /// <param name="db">Database.</param>
    /// <param name="seerr">Seerr service.</param>
    /// <param name="media">HTTP helper of the music and book apps.</param>
    /// <param name="logger">Logger.</param>
    public AdminController(IUserManager users, IServerApplicationHost host, IInstallationManager installs, ILocalizationManager localization, SeedyDatabase db, SeerrService seerr, Services.Media.ServiceHttp media, ILogger<AdminController> logger)
    {
        _media = media;
        _users = users;
        _host = host;
        _installs = installs;
        _localization = localization;
        _db = db;
        _seerr = seerr;
        _logger = logger;
    }

    private static Plugin PluginInstance => Plugin.Instance ?? throw new InvalidOperationException("Seedy isn't loaded.");

    // ── Configuration ──

    /// <summary>Gets the admin settings.</summary>
    /// <returns>Settings.</returns>
    [HttpGet("Config")]
    public ActionResult<AdminConfigDto> GetConfig() => ConfigMapper.ToAdmin(PluginInstance.Configuration);

    /// <summary>Saves the admin settings.</summary>
    /// <param name="body">Settings.</param>
    /// <returns>Saved settings.</returns>
    [HttpPost("Config")]
    public ActionResult<AdminConfigDto> SaveConfig([FromBody] AdminConfigDto body)
    {
        var c = PluginInstance.Configuration;
        var oldUrl = c.SeerrUrl;
        var oldApps = (c.LidarrUrl, c.ShelfarrUrl, c.LibrarrUrl, c.ReadarrUrl);
        ConfigMapper.Apply(c, body);
        PluginInstance.Save(c);
        if (!string.Equals(oldUrl, c.SeerrUrl, StringComparison.Ordinal))
        {
            _seerr.Reset();
        }

        foreach (var (name, was, now) in new[] { ("Lidarr", oldApps.LidarrUrl, c.LidarrUrl), ("Shelfarr", oldApps.ShelfarrUrl, c.ShelfarrUrl), ("Librarr", oldApps.LibrarrUrl, c.LibrarrUrl), ("Readarr", oldApps.ReadarrUrl, c.ReadarrUrl) })
        {
            if (!string.Equals(was, now, StringComparison.Ordinal))
            {
                _media.Reset(name);
            }
        }

        return ConfigMapper.ToAdmin(c);
    }

    /// <summary>Status tiles: versions, Seerr and the play log.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Status.</returns>
    [HttpGet("Status")]
    public async Task<ActionResult<AdminStatusDto>> GetStatus(CancellationToken ct)
    {
        var c = PluginInstance.Configuration;
        var mine = PluginInstance.Version;
        var latest = await LatestVersionAsync(ct).ConfigureAwait(false);
        return new AdminStatusDto
        {
            PluginVersion = PluginInstance.VersionString,
            LatestVersion = latest,
            UpToDate = latest is null ? null : !(Version.TryParse(latest, out var l) && l > mine),
            ServerVersion = _host.ApplicationVersionString,
            ServerSupported = _host.ApplicationVersion.Major >= 12,
            ServerStartedAt = ServerStartedAt,
            Os = OsName(),
            UtcOffsetMinutes = (int)TimeZoneInfo.Local.GetUtcOffset(DateTime.UtcNow).TotalMinutes,
            Seerr = await _seerr.StateAsync(ct).ConfigureAwait(false),
            PlayLog = new PlayLogStateDto { Enabled = c.PlayLogEnabled, RetentionDays = c.PlayLogRetentionDays, Plays = _db.TotalPlays() }
        };
    }

    // Jellyfin 12 reports neither uptime nor the OS, so the Overview's server line takes them from here.
    private static readonly DateTime ServerStartedAt = Process.GetCurrentProcess().StartTime.ToUniversalTime();

    private static string OsName() =>
        OperatingSystem.IsWindows() ? "Windows"
        : OperatingSystem.IsMacOS() ? "macOS"
        : OperatingSystem.IsFreeBSD() ? "FreeBSD"
        : OperatingSystem.IsLinux() ? "Linux"
        : RuntimeInformation.OSDescription;

    private async Task<string?> LatestVersionAsync(CancellationToken ct)
    {
        if (_latest is { } cached && DateTime.UtcNow - cached.At < TimeSpan.FromHours(1))
        {
            return cached.Version;
        }

        string? version = null;
        try
        {
            var packages = await _installs.GetAvailablePackages(ct).ConfigureAwait(false);
            var seedy = packages.FirstOrDefault(p => p.Id == Plugin.PluginId);
            version = seedy?.Versions.Select(v => v.Version).OrderByDescending(v => Version.TryParse(v, out var x) ? x : new Version(0, 0)).FirstOrDefault();
            if (version is not null && Version.TryParse(version, out var parsed))
            {
                version = parsed.ToString(3);
            }
        }
#pragma warning disable CA1031 // Repositories can be down; the tile just shows the installed version.
        catch (Exception ex)
#pragma warning restore CA1031
        {
            _logger.LogDebug(ex, "Couldn't check for Seedy updates");
        }

        _latest = (DateTime.UtcNow, version);
        return version;
    }

    // ── Logo ──

    /// <summary>Uploads a logo (SVG, PNG, WebP or JPEG, up to 2 MB).</summary>
    /// <param name="file">The file.</param>
    /// <returns>Saved settings.</returns>
    [HttpPost("Logo")]
    [RequestSizeLimit(2_200_000)]
    public async Task<ActionResult<AdminConfigDto>> UploadLogo(IFormFile file)
    {
        if (file is null || file.Length == 0)
        {
            return BadRequest(new { message = "Choose an image." });
        }

        var ext = Path.GetExtension(file.FileName).ToLowerInvariant();
        if (!LogoExtensions.Contains(ext) || file.Length > 2_000_000)
        {
            return BadRequest(new { message = "Use an SVG, PNG, WebP or JPEG up to 2 MB." });
        }

        var folder = PluginInstance.SeedyDataPath;
        foreach (var old in Directory.EnumerateFiles(folder, "logo.*"))
        {
            System.IO.File.Delete(old);
        }

        var target = Path.Combine(folder, "logo" + (ext == ".jpeg" ? ".jpg" : ext));
        await using (var stream = System.IO.File.Create(target))
        {
            await file.CopyToAsync(stream).ConfigureAwait(false);
        }

        var c = PluginInstance.Configuration;
        c.LogoFileName = Path.GetFileName(file.FileName);
        c.LogoVersion = DateTime.UtcNow.Ticks.ToString(CultureInfo.InvariantCulture);
        PluginInstance.Save(c);
        return ConfigMapper.ToAdmin(c);
    }

    /// <summary>Removes the logo.</summary>
    /// <returns>Saved settings.</returns>
    [HttpDelete("Logo")]
    public ActionResult<AdminConfigDto> RemoveLogo()
    {
        foreach (var old in Directory.EnumerateFiles(PluginInstance.SeedyDataPath, "logo.*"))
        {
            System.IO.File.Delete(old);
        }

        var c = PluginInstance.Configuration;
        c.LogoFileName = string.Empty;
        c.LogoVersion = DateTime.UtcNow.Ticks.ToString(CultureInfo.InvariantCulture);
        PluginInstance.Save(c);
        return ConfigMapper.ToAdmin(c);
    }

    // ── Seerr ──

    /// <summary>Tests an address and key; with neither, tests the saved ones.</summary>
    /// <param name="body">Address and key.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Result.</returns>
    [HttpPost("Seerr/Test")]
    public Task<SeerrTestResultDto> TestSeerr([FromBody] SeerrTestBody body, CancellationToken ct) => _seerr.TestAsync(body.Url, body.ApiKey, ct);

    /// <summary>Saves the Seerr API key. It is never sent back to browsers.</summary>
    /// <param name="body">Key.</param>
    /// <returns>Saved settings.</returns>
    [HttpPost("Seerr/Key")]
    public ActionResult<AdminConfigDto> SetSeerrKey([FromBody] KeyBody body)
    {
        var c = PluginInstance.Configuration;
        c.SeerrApiKey = (body.ApiKey ?? string.Empty).Trim();
        PluginInstance.Save(c);
        _seerr.Reset();
        return ConfigMapper.ToAdmin(c);
    }

    /// <summary>Profiles and their Seerr accounts.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Accounts.</returns>
    [HttpGet("Seerr/Accounts")]
    public Task<List<SeerrAccountDto>> GetAccounts(CancellationToken ct) => AccountsAsync(false, ct);

    /// <summary>Re-reads Seerr's users and matches them again.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Accounts.</returns>
    [HttpPost("Seerr/Match")]
    public Task<List<SeerrAccountDto>> MatchAccounts(CancellationToken ct) => AccountsAsync(true, ct);

    /// <summary>Seerr's users, for linking by hand.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Users.</returns>
    [HttpGet("Seerr/Users")]
    public async Task<ActionResult<List<SeerrUserDto>>> GetSeerrUsers(CancellationToken ct)
    {
        try
        {
            return await _seerr.UsersAsync(false, ct).ConfigureAwait(false);
        }
        catch (SeerrException ex)
        {
            return StatusCode((int)ex.Status, new { message = ex.Message });
        }
    }

    /// <summary>Links a profile to a Seerr user, or unlinks it (null).</summary>
    /// <param name="body">Profile and Seerr user.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Accounts.</returns>
    [HttpPost("Seerr/Link")]
    public async Task<ActionResult<List<SeerrAccountDto>>> Link([FromBody] LinkBody body, CancellationToken ct)
    {
        if (!Guid.TryParse(body.UserId, out var id) || _users.GetUserById(id) is null)
        {
            return NotFound();
        }

        var c = PluginInstance.Configuration;
        var isNew = (c.SeerrProfiles ?? []).All(p => !string.Equals(p.UserId, id.ToString("N"), StringComparison.OrdinalIgnoreCase));
        var profile = ConfigMapper.SeerrProfile(c, id.ToString("N"));
        profile.UserId = id.ToString("N");
        if (isNew)
        {
            // Linking an account isn't the same as switching requesting on.
            profile.CanRequest = c.RequestWho != "selected";
        }

        profile.Manual = body.SeerrUserId is > 0;
        profile.SeerrUserId = body.SeerrUserId ?? 0;
        ConfigMapper.Upsert(c, profile);
        PluginInstance.Save(c);
        return await AccountsAsync(false, ct).ConfigureAwait(false);
    }

    /// <summary>Switches requesting on or off for a profile.</summary>
    /// <param name="body">Profile and switch.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Accounts.</returns>
    [HttpPost("Seerr/CanRequest")]
    public async Task<ActionResult<List<SeerrAccountDto>>> SetCanRequest([FromBody] CanRequestBody body, CancellationToken ct)
    {
        if (!Guid.TryParse(body.UserId, out var id) || _users.GetUserById(id) is null)
        {
            return NotFound();
        }

        var c = PluginInstance.Configuration;
        var profile = ConfigMapper.SeerrProfile(c, id.ToString("N"));
        profile.UserId = id.ToString("N");
        profile.CanRequest = body.CanRequest;
        ConfigMapper.Upsert(c, profile);
        PluginInstance.Save(c);
        return await AccountsAsync(false, ct).ConfigureAwait(false);
    }

    private async Task<List<SeerrAccountDto>> AccountsAsync(bool refresh, CancellationToken ct)
    {
        if (refresh && SeerrService.IsConfigured)
        {
            try
            {
                await _seerr.UsersAsync(true, ct).ConfigureAwait(false);
            }
            catch (SeerrException ex)
            {
                _logger.LogWarning("Seedy couldn't read Seerr users: {Message}", ex.Message);
            }
        }

        var ratings = _localization.GetParentalRatings();
        var list = new List<SeerrAccountDto>();
        foreach (var user in _users.GetUsers().OrderBy(u => !u.IsAdmin()).ThenBy(u => u.Username, StringComparer.OrdinalIgnoreCase))
        {
            var (account, matchedBy) = await _seerr.AccountFor(user, ct).ConfigureAwait(false);
            var dto = _users.GetUserDto(user);
            list.Add(new SeerrAccountDto
            {
                UserId = user.Id.ToString("N"),
                Name = user.Username,
                IsAdmin = user.IsAdmin(),
                MaxParentalRating = RatingName(user, ratings),
                HasImage = dto.PrimaryImageTag is not null,
                ImageTag = dto.PrimaryImageTag,
                SeerrUserId = account?.Id,
                SeerrName = account?.DisplayName,
                MatchedBy = matchedBy,
                CanRequest = SeerrService.CanRequest(user, account is not null)
            });
        }

        return list;
    }

    /// <summary>A profile's maturity limit as a rating name ("TV-PG").</summary>
    /// <param name="user">Profile.</param>
    /// <param name="ratings">The server's ratings.</param>
    /// <returns>Name, or null without a limit.</returns>
    internal static string? RatingName(User user, IReadOnlyList<MediaBrowser.Model.Entities.ParentalRating> ratings)
    {
        if (user.MaxParentalRatingScore is not { } score)
        {
            return null;
        }

        var matches = ratings.Where(r => r.RatingScore?.Score == score).Select(r => r.Name).Where(n => !int.TryParse(n, out _)).ToList();
        var name = matches.FirstOrDefault(n => n.StartsWith("TV-", StringComparison.Ordinal)) ?? matches.FirstOrDefault();
        if (name is not null)
        {
            return name;
        }

        // The server's country may have no name for this score; use the US names metadata mostly carries.
        return score switch
        {
            <= 0 => "TV-G",
            <= 7 => "TV-Y7",
            <= 10 => "TV-PG",
            <= 13 => "PG-13",
            <= 14 => "TV-14",
            <= 17 => "R",
            _ => "TV-MA"
        };
    }

    // ── Play log ──

    /// <summary>Plays and switches per profile.</summary>
    /// <returns>Profiles.</returns>
    [HttpGet("PlayLog/Profiles")]
    public ActionResult<List<PlayLogProfileDto>> GetPlayLogProfiles() => PlayLogProfiles();

    /// <summary>Changes a profile's Top 10 and history switches.</summary>
    /// <param name="userId">Profile.</param>
    /// <param name="body">Switches.</param>
    /// <returns>Profiles.</returns>
    [HttpPost("PlayLog/Profiles/{userId}")]
    public ActionResult<List<PlayLogProfileDto>> SetPlayLogProfile([FromRoute] string userId, [FromBody] PlayLogProfileBody body)
    {
        if (!Guid.TryParse(userId, out var id) || _users.GetUserById(id) is null)
        {
            return NotFound();
        }

        var c = PluginInstance.Configuration;
        var profile = ConfigMapper.PlayLogProfile(c, id.ToString("N"));
        profile.UserId = id.ToString("N");
        profile.Top10 = body.Top10 ?? profile.Top10;
        profile.History = body.History ?? profile.History;
        ConfigMapper.Upsert(c, profile);
        PluginInstance.Save(c);
        return PlayLogProfiles();
    }

    private List<PlayLogProfileDto> PlayLogProfiles()
    {
        var c = PluginInstance.Configuration;
        var plays = _db.PlaysPerUser();
        return _users.GetUsers()
            .OrderBy(u => !u.IsAdmin())
            .ThenBy(u => u.Username, StringComparer.OrdinalIgnoreCase)
            .Select(u =>
            {
                var uid = u.Id.ToString("N");
                var p = ConfigMapper.PlayLogProfile(c, uid);
                return new PlayLogProfileDto
                {
                    UserId = uid,
                    Name = u.Username,
                    ImageTag = _users.GetUserDto(u).PrimaryImageTag,
                    Plays = plays.GetValueOrDefault(uid),
                    Top10 = p.Top10,
                    History = p.History
                };
            })
            .ToList();
    }

    /// <summary>Downloads a profile's plays as CSV.</summary>
    /// <param name="userId">Profile.</param>
    /// <returns>CSV file.</returns>
    [HttpGet("PlayLog/Export")]
    [Produces("text/csv")]
    public ActionResult Export([FromQuery] string userId)
    {
        if (!Guid.TryParse(userId, out var id) || _users.GetUserById(id) is not { } user)
        {
            return NotFound();
        }

        var csv = new StringBuilder();
        csv.AppendLine("started_utc,stopped_utc,title,episode_or_movie,type,position,runtime,finished,device,client,started_from_row");
        foreach (var p in _db.AllPlays(id.ToString("N")))
        {
            csv.AppendLine(string.Join(',', new[]
            {
                p.StartedAt.ToString("u", CultureInfo.InvariantCulture),
                p.StoppedAt?.ToString("u", CultureInfo.InvariantCulture) ?? string.Empty,
                Csv(p.TitleName),
                Csv(p.ItemName),
                p.ItemType,
                TimeSpan.FromTicks(p.PositionTicks).ToString(@"hh\:mm\:ss", CultureInfo.InvariantCulture),
                TimeSpan.FromTicks(p.RuntimeTicks).ToString(@"hh\:mm\:ss", CultureInfo.InvariantCulture),
                p.Finished ? "yes" : "no",
                Csv(p.DeviceName),
                Csv(p.Client),
                Csv(p.Origin)
            }));
        }

        var name = string.Concat(user.Username.Where(ch => char.IsLetterOrDigit(ch) || ch is '-' or '_'));
        return File(Encoding.UTF8.GetPreamble().Concat(Encoding.UTF8.GetBytes(csv.ToString())).ToArray(), "text/csv", $"seedy-plays-{(name.Length > 0 ? name : "profile")}.csv");
    }

    private static string Csv(string? value)
    {
        if (string.IsNullOrEmpty(value))
        {
            return string.Empty;
        }

        // Quote, and defuse spreadsheet formulas.
        var v = value[0] is '=' or '+' or '-' or '@' ? "'" + value : value;
        return "\"" + v.Replace("\"", "\"\"", StringComparison.Ordinal) + "\"";
    }

    /// <summary>Clears one profile's plays, or the whole log.</summary>
    /// <param name="userId">Profile, or empty for everyone.</param>
    /// <returns>No content.</returns>
    [HttpDelete("PlayLog")]
    public ActionResult ClearPlayLog([FromQuery] string? userId)
    {
        if (string.IsNullOrEmpty(userId))
        {
            _db.ClearPlays(null);
            return NoContent();
        }

        if (!Guid.TryParse(userId, out var id))
        {
            return NotFound();
        }

        _db.ClearPlays(id.ToString("N"));
        return NoContent();
    }
}
