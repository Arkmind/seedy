using System;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>
/// Seerr for the signed-in person: Discover, search, upcoming movies, title details and requests.
/// The Seerr API key stays on the server; requests are made as the person's own Seerr account.
/// </summary>
[ApiController]
[Route("Seedy/Requests")]
[Authorize]
[Produces("application/json")]
public class RequestsController : ControllerBase
{
    private readonly IUserManager _users;
    private readonly SeerrService _seerr;

    /// <summary>
    /// Initializes a new instance of the <see cref="RequestsController"/> class.
    /// </summary>
    /// <param name="users">User manager.</param>
    /// <param name="seerr">Seerr service.</param>
    public RequestsController(IUserManager users, SeerrService seerr)
    {
        _users = users;
        _seerr = seerr;
    }

    private async Task<ActionResult<T>> Run<T>(Func<Jellyfin.Database.Implementations.Entities.User, int?, Task<T>> body, CancellationToken ct)
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        if (!SeerrService.IsConfigured)
        {
            return StatusCode(StatusCodes.Status503ServiceUnavailable, new { message = "Requests aren't set up on this server." });
        }

        if (user.MaxParentalRatingScore is not null)
        {
            return StatusCode(StatusCodes.Status403Forbidden, new { message = "Requests aren't available on profiles with a maturity limit." });
        }

        try
        {
            var (account, _) = await _seerr.AccountFor(user, ct).ConfigureAwait(false);
            return await body(user, account?.Id).ConfigureAwait(false);
        }
        catch (SeerrException ex)
        {
            return StatusCode((int)ex.Status, new { message = ex.Message });
        }
    }

    /// <summary>Popular titles that aren't in the library.</summary>
    /// <param name="page">Page.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Titles.</returns>
    [HttpGet("Discover")]
    public Task<ActionResult<SeerrResultsDto<SeerrItemDto>>> Discover([FromQuery] int page, CancellationToken ct)
        => Run(async (_, me) =>
        {
            var list = await _seerr.DiscoverAsync(me, page <= 0 ? 1 : page, ct).ConfigureAwait(false);
            return new SeerrResultsDto<SeerrItemDto> { Results = list, Total = list.Count };
        }, ct);

    /// <summary>Searches Seerr.</summary>
    /// <param name="query">Text.</param>
    /// <param name="page">Page.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Titles.</returns>
    [HttpGet("Search")]
    public Task<ActionResult<SeerrResultsDto<SeerrItemDto>>> Search([FromQuery] string query, [FromQuery] int page, CancellationToken ct)
        => Run(async (_, me) =>
        {
            if (string.IsNullOrWhiteSpace(query))
            {
                return new SeerrResultsDto<SeerrItemDto>();
            }

            var (items, total) = await _seerr.SearchAsync(query, me, page <= 0 ? 1 : page, ct).ConfigureAwait(false);
            return new SeerrResultsDto<SeerrItemDto> { Results = items, Total = total };
        }, ct);

    /// <summary>The signed-in person's own requests.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Requests.</returns>
    [HttpGet("Mine")]
    public Task<ActionResult<MyRequestsDto>> Mine(CancellationToken ct)
        => Run<MyRequestsDto>(async (_, me) => me is null ? new MyRequestsDto() : await _seerr.MyRequestsAsync(me.Value, ct).ConfigureAwait(false), ct);

    /// <summary>Requested movies that aren't out yet.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Movies with release dates.</returns>
    [HttpGet("Upcoming")]
    public Task<ActionResult<SeerrResultsDto<SeerrUpcomingDto>>> Upcoming(CancellationToken ct)
        => Run(async (_, me) =>
        {
            var list = await _seerr.UpcomingAsync(me, ct).ConfigureAwait(false);
            return new SeerrResultsDto<SeerrUpcomingDto> { Results = list, Total = list.Count };
        }, ct);

    /// <summary>Details for a movie or series.</summary>
    /// <param name="mediaType">movie or tv.</param>
    /// <param name="tmdbId">TMDB id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Details.</returns>
    [HttpGet("Media/{mediaType}/{tmdbId:int}")]
    public Task<ActionResult<SeerrDetailsDto>> Details([FromRoute] string mediaType, [FromRoute] int tmdbId, CancellationToken ct)
        => Run((_, me) => _seerr.DetailsAsync(mediaType == "tv" ? "tv" : "movie", tmdbId, me, ct), ct);

    /// <summary>What the request dialog offers the signed-in person: quota, approval, 4K and, for admins, advanced choices.</summary>
    /// <param name="mediaType">movie or tv.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Options.</returns>
    [HttpGet("Options/{mediaType}")]
    public Task<ActionResult<RequestOptionsDto>> Options([FromRoute] string mediaType, CancellationToken ct)
        => Run<RequestOptionsDto>(async (user, me) => me is null
            ? new RequestOptionsDto()
            : await _seerr.RequestOptionsAsync(mediaType == "tv" ? "tv" : "movie", me.Value, user.IsAdmin(), ct).ConfigureAwait(false), ct);

    /// <summary>Requests a title as the signed-in person.</summary>
    /// <param name="body">What to request.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The title's new status.</returns>
    [HttpPost]
    public async Task<ActionResult<RequestResultDto>> CreateRequest([FromBody] RequestBody body, CancellationToken ct)
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        if (!SeerrService.IsConfigured)
        {
            return StatusCode(StatusCodes.Status503ServiceUnavailable, new { message = "Requests aren't set up on this server." });
        }

        if (user.MaxParentalRatingScore is not null)
        {
            return StatusCode(StatusCodes.Status403Forbidden, new { message = "Requests aren't available on profiles with a maturity limit." });
        }

        if (body.TmdbId <= 0 || body.MediaType is not ("movie" or "tv"))
        {
            return BadRequest(new { message = "Unknown title." });
        }

        try
        {
            var (account, _) = await _seerr.AccountFor(user, ct).ConfigureAwait(false);
            if (account is null)
            {
                return StatusCode(StatusCodes.Status403Forbidden, new { message = "This profile has no Seerr account. Ask an admin to link one." });
            }

            if (!SeerrService.CanRequest(user, true))
            {
                return StatusCode(StatusCodes.Status403Forbidden, new { message = "Requests are switched off for this profile." });
            }

            if (body.Is4k && !ConfigMapper.Current.Allow4k)
            {
                return StatusCode(StatusCodes.Status403Forbidden, new { message = "4K requests are switched off." });
            }

            // Server, profile, folder and "request for" are admin choices; everyone else gets Seerr's defaults.
            if (!user.IsAdmin())
            {
                body.ServerId = null;
                body.ProfileId = null;
                body.RootFolder = null;
                body.UserId = null;
            }

            body.Seasons = body.Seasons?.Where(s => s > 0).Distinct().ToList();
            var status = await _seerr.RequestAsync(body, account.Id, ct).ConfigureAwait(false);
            return new RequestResultDto { Status = status };
        }
        catch (SeerrException ex)
        {
            return StatusCode((int)ex.Status, new { message = ex.Message });
        }
    }
}
