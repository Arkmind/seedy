using System;
using System.Collections.Generic;
using System.Linq;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>
/// The phone as a remote: screens say what Jellyfin's sessions can't (asking for text, the focused title, full screen,
/// mini player) and the phones controlling them read it back.
/// </summary>
[ApiController]
[Route("Seedy/Remote")]
[Authorize]
[Produces("application/json")]
public class RemoteController : ControllerBase
{
    private readonly IUserManager _users;
    private readonly RemoteStates _states;

    /// <summary>
    /// Initializes a new instance of the <see cref="RemoteController"/> class.
    /// </summary>
    /// <param name="users">User manager.</param>
    /// <param name="states">Remote states.</param>
    public RemoteController(IUserManager users, RemoteStates states)
    {
        _users = users;
        _states = states;
    }

    /// <summary>Saves what this device tells the phones that control it.</summary>
    /// <param name="body">Its state.</param>
    /// <returns>No content.</returns>
    [HttpPost("State")]
    public ActionResult PostState([FromBody] RemoteStateBody body)
    {
        var user = User.GetUser(_users);
        var deviceId = User.GetClaim("Jellyfin-DeviceId");
        if (user is null || string.IsNullOrWhiteSpace(deviceId))
        {
            return Unauthorized();
        }

        var itemId = Guid.TryParse(body.ItemId, out var id) ? id.ToString("N") : null;
        var asking = string.IsNullOrWhiteSpace(body.Asking) ? null : body.Asking.Trim();
        var text = body.Text is { Length: > 200 } ? body.Text[..200] : body.Text;
        _states.Set(new RemoteState(deviceId, user.Id, asking, asking is null ? null : text, itemId, body.Fullscreen, body.MiniPlayer, DateTime.UtcNow));
        return NoContent();
    }

    /// <summary>The states of the screens this person may control: their own (see <see cref="RemoteControlGuard"/>).</summary>
    /// <returns>States.</returns>
    [HttpGet("States")]
    public ActionResult<List<RemoteStateDto>> GetStates()
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        return _states.For(new[] { user.Id })
            .Select(s => new RemoteStateDto
            {
                DeviceId = s.DeviceId,
                Asking = s.Asking,
                Text = s.Text,
                ItemId = s.ItemId,
                Fullscreen = s.Fullscreen,
                MiniPlayer = s.MiniPlayer,
                At = s.At
            })
            .ToList();
    }
}
