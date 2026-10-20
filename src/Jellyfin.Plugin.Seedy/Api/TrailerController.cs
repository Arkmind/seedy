using System;
using System.Collections.Generic;
using System.Text.Json.Serialization;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>The trailer Seedy plays behind a title in the Home carousel.</summary>
public sealed class TrailerDto
{
    /// <summary>Gets or sets "local" (a trailer file in the library) or "youtube".</summary>
    [JsonPropertyName("source")] public string Source { get; set; } = "youtube";

    /// <summary>Gets or sets the local trailer's item id.</summary>
    [JsonPropertyName("itemId")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? ItemId { get; set; }

    /// <summary>Gets or sets the YouTube video id.</summary>
    [JsonPropertyName("key")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? Key { get; set; }

    /// <summary>Gets or sets the trailer's title.</summary>
    [JsonPropertyName("name")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? Name { get; set; }

    /// <summary>Gets or sets where it was found: "jellyfin" or "tmdb" (through Seerr).</summary>
    [JsonPropertyName("from")] [JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)] public string? From { get; set; }

    /// <summary>Gets or sets further YouTube trailers, tried in order when this one can't play (embedding off, removed).</summary>
    [JsonPropertyName("alternates")] public List<TrailerAltDto> Alternates { get; set; } = [];
}

/// <summary>Another YouTube trailer for the same title.</summary>
public sealed class TrailerAltDto
{
    /// <summary>Gets or sets the YouTube video id.</summary>
    [JsonPropertyName("key")] public string Key { get; set; } = string.Empty;

    /// <summary>Gets or sets its title.</summary>
    [JsonPropertyName("name")] public string? Name { get; set; }
}

/// <summary>
/// Trailers for titles in the library. Lives under /Seedy/Items, which no app page uses (API paths must never
/// equal a page path: routes are case-insensitive, and pages sit under /seedy/ in "alongside" mode).
/// </summary>
[ApiController]
[Route("Seedy/Items")]
[Authorize]
[Produces("application/json")]
public class TrailerController : ControllerBase
{
    private readonly IUserManager _users;
    private readonly ILibraryManager _library;
    private readonly TrailerService _trailers;

    /// <summary>
    /// Initializes a new instance of the <see cref="TrailerController"/> class.
    /// </summary>
    /// <param name="users">User manager.</param>
    /// <param name="library">Library manager.</param>
    /// <param name="trailers">Trailer lookup.</param>
    public TrailerController(IUserManager users, ILibraryManager library, TrailerService trailers)
    {
        _users = users;
        _library = library;
        _trailers = trailers;
    }

    /// <summary>The trailer for a title: a local trailer, else YouTube (Jellyfin's metadata, then TMDB through Seerr).</summary>
    /// <param name="itemId">Movie or show (an episode or season stands for its show).</param>
    /// <param name="ct">Cancellation.</param>
    /// <response code="200">The trailer.</response>
    /// <response code="204">The title has no trailer.</response>
    /// <response code="404">No such title, or the person can't see it (parental controls, library access).</response>
    /// <returns>The trailer.</returns>
    [HttpGet("{itemId}/Trailer")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<TrailerDto>> GetTrailer([FromRoute] Guid itemId, CancellationToken ct)
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        // Only titles this person may see: GetItemById with the user applies library access and parental limits.
        var item = itemId == Guid.Empty ? null : _library.GetItemById<BaseItem>(itemId, user);
        if (item is null)
        {
            return NotFound();
        }

        var trailer = await _trailers.ResolveAsync(item, user, ct).ConfigureAwait(false);
        Response.Headers.CacheControl = "private, max-age=3600";
        return trailer is null ? NoContent() : trailer;
    }
}
