using System;
using System.Linq;
using System.Net;
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

    /// <summary>The Requests page's tabs, actions and counts for the signed-in person.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Summary.</returns>
    [HttpGet("Summary")]
    public Task<ActionResult<RequestSummaryDto>> Summary(CancellationToken ct)
        => Run<RequestSummaryDto>(async (_, me) => me is null ? new RequestSummaryDto() : await _seerr.RequestSummaryAsync(me.Value, ct).ConfigureAwait(false), ct);

    /// <summary>A page of requests: the person's own, or everyone's for people Seerr lets see them.</summary>
    /// <param name="scope">mine or all.</param>
    /// <param name="filter">all, pending, processing, available or failed.</param>
    /// <param name="sort">added or modified.</param>
    /// <param name="skip">Requests to skip.</param>
    /// <param name="take">Page size (up to 50).</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Requests.</returns>
    [HttpGet("List")]
    public Task<ActionResult<RequestListDto>> List([FromQuery] string? scope, [FromQuery] string? filter, [FromQuery] string? sort, [FromQuery] int skip, [FromQuery] int take, CancellationToken ct)
        => Run<RequestListDto>(async (_, me) =>
        {
            if (me is null)
            {
                return new RequestListDto();
            }

            var all = scope == "all";
            if (all && !SeerrService.CanViewAllRequests(await _seerr.PermissionsAsync(me.Value, ct).ConfigureAwait(false)))
            {
                throw new SeerrException("Your Seerr account can't see other people's requests.", HttpStatusCode.Forbidden);
            }

            return await _seerr.RequestListAsync(me.Value, all, filter, sort, skip, take <= 0 ? 20 : take, ct).ConfigureAwait(false);
        }, ct);

    /// <summary>A row of titles to request: trending, popular or upcoming movies and shows.</summary>
    /// <param name="kind">trending, popular-movies, popular-shows, upcoming-movies or upcoming-shows.</param>
    /// <param name="page">Page.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Titles.</returns>
    [HttpGet("Titles/{kind}")]
    public Task<ActionResult<SeerrTitlesDto>> Titles([FromRoute] string kind, [FromQuery] int page, CancellationToken ct)
        => Run((_, me) => _seerr.TitlesAsync(kind, page <= 0 ? 1 : page, me, ct), ct);

    /// <summary>Approves a request (needs Seerr's Manage requests).</summary>
    /// <param name="requestId">Seerr request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The request.</returns>
    [HttpPost("{requestId:int}/Approve")]
    public Task<ActionResult<RequestDto?>> Approve([FromRoute] int requestId, CancellationToken ct) => Update(requestId, "approve", ct);

    /// <summary>Declines a request (needs Seerr's Manage requests).</summary>
    /// <param name="requestId">Seerr request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The request.</returns>
    [HttpPost("{requestId:int}/Decline")]
    public Task<ActionResult<RequestDto?>> Decline([FromRoute] int requestId, CancellationToken ct) => Update(requestId, "decline", ct);

    /// <summary>Sends a failed request to Sonarr/Radarr again (needs Seerr's Manage requests).</summary>
    /// <param name="requestId">Seerr request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The request.</returns>
    [HttpPost("{requestId:int}/Retry")]
    public Task<ActionResult<RequestDto?>> Retry([FromRoute] int requestId, CancellationToken ct) => Update(requestId, "retry", ct);

    /// <summary>Withdraws the person's own waiting request, or removes any request for request managers.</summary>
    /// <param name="requestId">Seerr request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Nothing.</returns>
    [HttpDelete("{requestId:int}")]
    public async Task<ActionResult> Remove([FromRoute] int requestId, CancellationToken ct)
    {
        var result = await Run<bool>(async (_, me) =>
        {
            await _seerr.DeleteRequestAsync(requestId, me ?? throw NoAccount(), ct).ConfigureAwait(false);
            return true;
        }, ct).ConfigureAwait(false);
        return result.Result ?? NoContent();
    }

    private Task<ActionResult<RequestDto?>> Update(int requestId, string action, CancellationToken ct)
        => Run((_, me) => _seerr.UpdateRequestAsync(requestId, action, me ?? throw NoAccount(), ct), ct);

    private static SeerrException NoAccount() => new("This profile has no Seerr account. Ask an admin to link one.", HttpStatusCode.Forbidden);

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

    /// <summary>What the request dialog offers the signed-in person: quota, approval, 4K and the advanced choices Seerr allows them.</summary>
    /// <param name="mediaType">movie or tv.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Options.</returns>
    [HttpGet("Options/{mediaType}")]
    public Task<ActionResult<RequestOptionsDto>> Options([FromRoute] string mediaType, CancellationToken ct)
        => Run<RequestOptionsDto>(async (_, me) => me is null
            ? new RequestOptionsDto()
            : await _seerr.RequestOptionsAsync(mediaType == "tv" ? "tv" : "movie", me.Value, ct).ConfigureAwait(false), ct);

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

            // Server, profile, folder and "request for" follow the person's Seerr permissions, as in Seerr itself;
            // without them the request gets Seerr's defaults.
            if (body.ServerId is not null || body.ProfileId is not null || body.RootFolder is not null || body.UserId is not null)
            {
                var perms = await _seerr.PermissionsAsync(account.Id, ct).ConfigureAwait(false);
                if (!SeerrService.CanPickServer(perms))
                {
                    body.ServerId = null;
                    body.ProfileId = null;
                    body.RootFolder = null;
                }

                if (!SeerrService.CanRequestFor(perms))
                {
                    body.UserId = null;
                }
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
