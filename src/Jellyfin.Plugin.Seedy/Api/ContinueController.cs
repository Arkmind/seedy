using System;
using System.Linq;
using System.Threading.Tasks;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Dto;
using MediaBrowser.Model.Querying;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>Continue watching: Jellyfin's resume list without the episodes a person moved past.</summary>
[ApiController]
[Route("Seedy")]
[Authorize]
[Produces("application/json")]
public class ContinueController : ControllerBase
{
    private readonly IUserManager _users;
    private readonly ContinueWatching _continue;

    /// <summary>
    /// Initializes a new instance of the <see cref="ContinueController"/> class.
    /// </summary>
    /// <param name="users">User manager.</param>
    /// <param name="continueWatching">Continue watching.</param>
    public ContinueController(IUserManager users, ContinueWatching continueWatching)
    {
        _users = users;
        _continue = continueWatching;
    }

    /// <summary>What the person is in the middle of, most recent first; same shape as /UserItems/Resume.</summary>
    /// <param name="limit">How many (1 to 200).</param>
    /// <param name="parentId">Only under this library, show or season.</param>
    /// <param name="fields">Extra item fields, comma-separated, on top of what cards need.</param>
    /// <returns>The titles.</returns>
    [HttpGet("Home/Continue")]
    public async Task<ActionResult<QueryResult<BaseItemDto>>> GetContinue([FromQuery] int limit = 16, [FromQuery] Guid? parentId = null, [FromQuery] string? fields = null)
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        var options = HomeService.CardOptions;
        var extra = (fields ?? string.Empty).Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(f => Enum.TryParse<ItemFields>(f, true, out var v) ? v : (ItemFields?)null)
            .OfType<ItemFields>();
        options.Fields = [.. options.Fields.Union(extra)];
        return await _continue.ListAsync(user, parentId ?? Guid.Empty, Math.Clamp(limit, 1, 200), options).ConfigureAwait(false);
    }
}
