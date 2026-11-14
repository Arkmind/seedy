using System;
using System.Globalization;
using System.Linq;
using System.Text;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Common.Api;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>The admin Statistics pages, built from the play log. Administrators only.</summary>
[ApiController]
// Not Seedy/Admin/Statistics: that is the page /admin/statistics once Seedy serves it under /seedy (routes ignore case).
[Route("Seedy/Admin/Stats")]
[Authorize(Policy = Policies.RequiresElevation)]
[Produces("application/json")]
public class StatisticsController : ControllerBase
{
    private static readonly string[] Ranges = ["7d", "30d", "12m", "all"];

    private readonly Statistics _stats;
    private readonly IUserManager _users;

    /// <summary>
    /// Initializes a new instance of the <see cref="StatisticsController"/> class.
    /// </summary>
    /// <param name="stats">Statistics builder.</param>
    /// <param name="users">User manager.</param>
    public StatisticsController(Statistics stats, IUserManager users)
    {
        _stats = stats;
        _users = users;
    }

    private static StatsQuery Query(string? range, string? tz, string? userId, string? libraryId, string? device)
        => new(
            Ranges.Contains(range) ? range! : "30d",
            Statistics.Zone(tz),
            Guid.TryParse(userId, out var u) ? u.ToString("N") : null,
            libraryId == "livetv" ? "livetv" : Guid.TryParse(libraryId, out var l) ? l.ToString("N") : null,
            string.IsNullOrEmpty(device) ? null : device);

    /// <summary>The Statistics page: headline numbers, watch time over time, how and what people play.</summary>
    /// <param name="range">7d, 30d, 12m or all.</param>
    /// <param name="tz">The viewer's time zone (IANA id).</param>
    /// <param name="userId">One profile.</param>
    /// <param name="libraryId">One library, or "livetv".</param>
    /// <param name="device">One device name.</param>
    /// <returns>The page's numbers.</returns>
    [HttpGet]
    public ActionResult<StatsDto> Get([FromQuery] string? range, [FromQuery] string? tz, [FromQuery] string? userId, [FromQuery] string? libraryId, [FromQuery] string? device)
    {
        if (User.GetUser(_users) is not { } admin)
        {
            return Unauthorized();
        }

        return _stats.Build(Query(range, tz, userId, libraryId, device), admin);
    }

    /// <summary>One day: who played what and when, and how it compares with the 30 days before.</summary>
    /// <param name="date">The day (yyyy-MM-dd) in the viewer's time zone.</param>
    /// <param name="tz">The viewer's time zone (IANA id).</param>
    /// <returns>The day's plays.</returns>
    [HttpGet("Day")]
    public ActionResult<StatsDayDto> GetDay([FromQuery] string date, [FromQuery] string? tz)
    {
        if (!DateTime.TryParseExact(date, "yyyy-MM-dd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var day))
        {
            return BadRequest(new { message = "The date must look like 2026-11-08." });
        }

        return _stats.BuildDay(day, Statistics.Zone(tz));
    }

    /// <summary>The plays behind the page, as CSV.</summary>
    /// <param name="range">7d, 30d, 12m or all.</param>
    /// <param name="tz">The viewer's time zone (IANA id).</param>
    /// <param name="userId">One profile.</param>
    /// <param name="libraryId">One library, or "livetv".</param>
    /// <param name="device">One device name.</param>
    /// <returns>CSV file.</returns>
    [HttpGet("Export")]
    [Produces("text/csv")]
    public ActionResult Export([FromQuery] string? range, [FromQuery] string? tz, [FromQuery] string? userId, [FromQuery] string? libraryId, [FromQuery] string? device)
    {
        var q = Query(range, tz, userId, libraryId, device);
        var csv = _stats.Csv(q);
        return File(Encoding.UTF8.GetPreamble().Concat(Encoding.UTF8.GetBytes(csv)).ToArray(), "text/csv", $"seedy-statistics-{q.Range}.csv");
    }
}
