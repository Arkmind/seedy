using System;
using Jellyfin.Plugin.Seedy.Data;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Common.Api;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>Seedy Wrapped, and the moments players report for it.</summary>
[ApiController]
// Under Seedy/Me: the page /wrapped is served as /seedy/wrapped, and routes ignore case.
[Route("Seedy")]
[Authorize]
[Produces("application/json")]
public class WrappedController : ControllerBase
{
    private readonly Wrapped _wrapped;
    private readonly SeedyDatabase _db;
    private readonly IUserManager _users;

    /// <summary>
    /// Initializes a new instance of the <see cref="WrappedController"/> class.
    /// </summary>
    /// <param name="wrapped">Wrapped builder.</param>
    /// <param name="db">Database.</param>
    /// <param name="users">User manager.</param>
    public WrappedController(Wrapped wrapped, SeedyDatabase db, IUserManager users)
    {
        _wrapped = wrapped;
        _db = db;
        _users = users;
    }

    /// <summary>The years the person can open, and this year's Wrapped while it's December.</summary>
    /// <param name="tz">The app's time zone (IANA id); Wrapped is built in it.</param>
    /// <returns>Status.</returns>
    [HttpGet("Me/Wrapped")]
    public ActionResult<WrappedStatusDto> Status([FromQuery] string? tz)
    {
        if (User.GetUser(_users) is not { } user)
        {
            return Unauthorized();
        }

        _wrapped.SeenIn(user.Id.ToString("N"), tz);
        return _wrapped.Status(user);
    }

    /// <summary>The person's Wrapped for a year.</summary>
    /// <param name="year">Year.</param>
    /// <param name="tz">The app's time zone (IANA id).</param>
    /// <returns>The Wrapped.</returns>
    [HttpGet("Me/Wrapped/{year:int}")]
    public ActionResult<WrappedDto> Get([FromRoute] int year, [FromQuery] string? tz)
    {
        if (User.GetUser(_users) is not { } user)
        {
            return Unauthorized();
        }

        _wrapped.SeenIn(user.Id.ToString("N"), tz);
        return _wrapped.Get(user, year) is { } dto ? dto : NotFound();
    }

    /// <summary>Records a moment from a player: an intro skipped, a "Still watching?" asked or answered.</summary>
    /// <param name="moment">The moment.</param>
    /// <returns>No content.</returns>
    [HttpPost("Me/Moments")]
    public ActionResult Moment([FromBody] MomentDto moment)
    {
        if (User.GetUser(_users) is not { } user)
        {
            return Unauthorized();
        }

        if (!Wrapped.MomentKinds.Contains(moment.Kind))
        {
            return BadRequest(new { message = "Unknown moment." });
        }

        if (!ConfigMapper.Current.PlayLogEnabled)
        {
            return NoContent();
        }

        var item = Guid.TryParse(moment.ItemId, out var id) ? id.ToString("N") : null;
        _db.InsertMoment(new MomentRecord(user.Id.ToString("N"), moment.Kind, item, Math.Clamp(moment.Ms ?? 0, 0, 3_600_000), DateTime.UtcNow));
        return NoContent();
    }

    /// <summary>Builds a year's Wrapped again for everyone. Administrators only.</summary>
    /// <param name="year">Year.</param>
    /// <returns>How many profiles got one.</returns>
    [HttpPost("Admin/Wrapped/{year:int}")]
    [Authorize(Policy = Policies.RequiresElevation)]
    public ActionResult Build([FromRoute] int year)
    {
        if (year < 2000 || year > DateTime.UtcNow.Year)
        {
            return BadRequest(new { message = "No Wrapped for that year." });
        }

        if (!_wrapped.CanRebuild(year))
        {
            return BadRequest(new { message = "The play log no longer holds all of that year, so its Wrapped is kept as it is." });
        }

        return Ok(new { built = _wrapped.BuildYear(year, null, true) });
    }
}
