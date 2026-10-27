using System;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Plugin.Seedy.Services;
using Jellyfin.Plugin.Seedy.Services.Media;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>
/// Music and book requests for the signed-in person: Seedy's own queue (limits, approvals by admins), the Music and
/// Books discover rows, searches and what the request windows show. Keys for Lidarr, Shelfarr, Librarr and the
/// Readarr-API app stay on the server.
/// </summary>
[ApiController]
[Route("Seedy/MediaRequests")]
[Authorize]
[Produces("application/json")]
public class MediaRequestsController : ControllerBase
{
    private readonly IUserManager _users;
    private readonly MediaRequestService _requests;
    private readonly MediaDiscoverService _discover;

    /// <summary>
    /// Initializes a new instance of the <see cref="MediaRequestsController"/> class.
    /// </summary>
    /// <param name="users">User manager.</param>
    /// <param name="requests">Requests.</param>
    /// <param name="discover">Discover rows.</param>
    public MediaRequestsController(IUserManager users, MediaRequestService requests, MediaDiscoverService discover)
    {
        _users = users;
        _requests = requests;
        _discover = discover;
    }

    private async Task<ActionResult<T>> Run<T>(Func<User, Task<T>> body, bool admin = false, string? needs = null)
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        if (admin && !user.IsAdmin())
        {
            return StatusCode(StatusCodes.Status403Forbidden, new { message = "Only admins can do this." });
        }

        if (needs == "music" && !MediaRequestService.MusicEnabled)
        {
            return StatusCode(StatusCodes.Status503ServiceUnavailable, new { message = "Music requests aren't set up on this server." });
        }

        if (needs == "book" && !MediaRequestService.BooksEnabled)
        {
            return StatusCode(StatusCodes.Status503ServiceUnavailable, new { message = "Book requests aren't set up on this server." });
        }

        try
        {
            return await body(user).ConfigureAwait(false);
        }
        catch (MediaServiceException ex)
        {
            return StatusCode((int)ex.Status, new { message = ex.Message, service = ex.Service });
        }
    }

    /// <summary>Counts for the Requests page's tabs.</summary>
    /// <returns>Summary.</returns>
    [HttpGet("Summary")]
    public Task<ActionResult<MediaSummaryDto>> Summary() => Run(u => Task.FromResult(_requests.Summary(u)));

    /// <summary>Music and book requests: the person's own, or everyone's for admins.</summary>
    /// <param name="scope">mine or all.</param>
    /// <param name="kind">music or book; both when empty.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Requests.</returns>
    [HttpGet]
    public Task<ActionResult<MediaRequestListDto>> List([FromQuery] string? scope, [FromQuery] string? kind, CancellationToken ct)
        => Run(u => _requests.ListAsync(u, scope == "all", kind, ct), admin: scope == "all");

    /// <summary>Asks for music or a book as the person.</summary>
    /// <param name="body">What to request.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The request.</returns>
    [HttpPost]
    public Task<ActionResult<MediaRequestDto>> Create([FromBody] MediaCreateBody body, CancellationToken ct)
        => Run(u => _requests.CreateAsync(u, body, ct), needs: body.Kind == "book" ? "book" : "music");

    /// <summary>What the request window offers the person: limit, approval, quality, formats and where they land.</summary>
    /// <param name="kind">music or book.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Options.</returns>
    [HttpGet("Options/{kind}")]
    public Task<ActionResult<MediaOptionsDto>> Options([FromRoute] string kind, CancellationToken ct)
        => Run(u => _requests.OptionsAsync(u, kind == "book" ? "book" : "music", ct));

    /// <summary>Approves a request and sends it to its app (admins).</summary>
    /// <param name="id">Request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The request.</returns>
    [HttpPost("{id}/Approve")]
    public Task<ActionResult<MediaRequestDto>> Approve([FromRoute] string id, CancellationToken ct) => Run(u => _requests.ApproveAsync(u, id, ct), admin: true);

    /// <summary>Declines a request (admins).</summary>
    /// <param name="id">Request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The request.</returns>
    [HttpPost("{id}/Decline")]
    public Task<ActionResult<MediaRequestDto>> Decline([FromRoute] string id, CancellationToken ct) => Run(u => _requests.DeclineAsync(u, id, ct), admin: true);

    /// <summary>Searches again now (the requester or an admin).</summary>
    /// <param name="id">Request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The request.</returns>
    [HttpPost("{id}/Search")]
    public Task<ActionResult<MediaRequestDto>> Search([FromRoute] string id, CancellationToken ct) => Run(u => _requests.SearchNowAsync(u, id, ct));

    /// <summary>Accepts a lesser quality in Lidarr and searches again (the requester or an admin).</summary>
    /// <param name="id">Request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The request.</returns>
    [HttpPost("{id}/AcceptLower")]
    public Task<ActionResult<MediaRequestDto>> AcceptLower([FromRoute] string id, CancellationToken ct) => Run(u => _requests.AcceptLowerAsync(u, id, ct));

    /// <summary>Withdraws the person's own request, or removes any for admins (cancelling it in its app).</summary>
    /// <param name="id">Request id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Nothing.</returns>
    [HttpDelete("{id}")]
    public async Task<ActionResult> Remove([FromRoute] string id, CancellationToken ct)
    {
        var result = await Run(async u =>
        {
            await _requests.CancelAsync(u, id, ct).ConfigureAwait(false);
            return true;
        }).ConfigureAwait(false);
        return result.Result ?? NoContent();
    }

    // ── Music ──

    /// <summary>A row on the Music page: new, missing, trending or soon.</summary>
    /// <param name="row">Row.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Releases.</returns>
    [HttpGet("Music/Rows/{row}")]
    public Task<ActionResult<MusicRowDto>> MusicRow([FromRoute] string row, CancellationToken ct) => Run(u => _discover.MusicRowAsync(u, row, ct), needs: "music");

    /// <summary>Searches artists and albums.</summary>
    /// <param name="query">Text.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Artists and albums.</returns>
    [HttpGet("Music/Search")]
    public Task<ActionResult<MusicSearchDto>> MusicSearch([FromQuery] string? query, CancellationToken ct)
        => Run(u => string.IsNullOrWhiteSpace(query) ? Task.FromResult(new MusicSearchDto()) : _discover.MusicSearchAsync(u, query.Trim(), ct), needs: "music");

    /// <summary>An artist for the request window.</summary>
    /// <param name="mbid">MusicBrainz artist id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The artist and their releases.</returns>
    [HttpGet("Music/Artists/{mbid}")]
    public Task<ActionResult<MusicArtistDto>> MusicArtist([FromRoute] string mbid, CancellationToken ct)
        => Run(u => Guid.TryParse(mbid, out _) ? _discover.MusicArtistAsync(u, mbid, ct) : throw new MediaServiceException("Seedy", "Unknown artist.", System.Net.HttpStatusCode.NotFound), needs: "music");

    // ── Books ──

    /// <summary>A row on the Books page: next, audiobooks or trending.</summary>
    /// <param name="row">Row.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Books.</returns>
    [HttpGet("Books/Rows/{row}")]
    public Task<ActionResult<BookRowDto>> BookRow([FromRoute] string row, CancellationToken ct) => Run(u => _discover.BookRowAsync(u, row, ct), needs: "book");

    /// <summary>Searches books, authors and series.</summary>
    /// <param name="query">Text.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Books.</returns>
    [HttpGet("Books/Search")]
    public Task<ActionResult<BookRowDto>> BookSearch([FromQuery] string? query, CancellationToken ct)
        => Run(u => string.IsNullOrWhiteSpace(query) ? Task.FromResult(new BookRowDto()) : _discover.BookSearchAsync(u, query.Trim(), ct), needs: "book");

    /// <summary>A book for the request window: the card from the page, with its description and series progress.</summary>
    /// <param name="card">The book as the page had it.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The book.</returns>
    [HttpPost("Books/Details")]
    public Task<ActionResult<BookCardDto>> BookDetails([FromBody] BookCardDto card, CancellationToken ct) => Run(u => _discover.BookDetailsAsync(u, card, ct), needs: "book");
}
