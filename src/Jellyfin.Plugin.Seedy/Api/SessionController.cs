using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Jellyfin.Data.Queries;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Common.Api;
using MediaBrowser.Controller.Devices;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Session;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>
/// The web app's Jellyfin session: a sign-in of its own, and whether the browser decodes the video it plays.
/// </summary>
[ApiController]
[Route("Seedy")]
[Authorize]
[Produces("application/json")]
public class SessionController : ControllerBase
{
    private readonly IUserManager _users;
    private readonly ISessionManager _sessions;
    private readonly IDeviceManager _devices;
    private readonly ClientDecoding _decoding;

    /// <summary>
    /// Initializes a new instance of the <see cref="SessionController"/> class.
    /// </summary>
    /// <param name="users">User manager.</param>
    /// <param name="sessions">Session manager.</param>
    /// <param name="devices">Device manager.</param>
    /// <param name="decoding">Client decoding registry.</param>
    public SessionController(IUserManager users, ISessionManager sessions, IDeviceManager devices, ClientDecoding decoding)
    {
        _users = users;
        _sessions = sessions;
        _devices = devices;
        _decoding = decoding;
    }

    /// <summary>
    /// Trades a sign-in the web app borrowed from Jellyfin's own web app for one made for Seedy on this device.
    /// Jellyfin ties the websocket to the app and device a token was made for, so on a borrowed token the socket and
    /// the player land in two different sessions: the admin's Stop is unavailable and messages never arrive.
    /// </summary>
    /// <returns>The token to use from now on (the same one when it already belongs to this app and device).</returns>
    [HttpPost("Me/Token")]
    public async Task<ActionResult<TokenDto>> OwnToken()
    {
        var user = User.GetUser(_users);
        var token = Claim("Jellyfin-Token");
        var app = User.GetClientName();
        var deviceId = Claim("Jellyfin-DeviceId");
        var deviceName = User.GetDeviceName();
        var version = Claim("Jellyfin-Version");
        if (user is null || token is null)
        {
            return Unauthorized();
        }

        if (string.IsNullOrWhiteSpace(app) || string.IsNullOrWhiteSpace(deviceId) || string.IsNullOrWhiteSpace(deviceName) || string.IsNullOrWhiteSpace(version))
        {
            return BadRequest();
        }

        var current = _devices.GetDevices(new DeviceQuery { AccessToken = token }).Items.FirstOrDefault();
        if (current is not null && string.Equals(current.AppName, app, StringComparison.Ordinal) && string.Equals(current.DeviceId, deviceId, StringComparison.Ordinal))
        {
            return new TokenDto { AccessToken = token };
        }

        var result = await _sessions.AuthenticateDirect(new AuthenticationRequest
        {
            UserId = user.Id,
            Username = user.Username,
            App = app,
            AppVersion = version,
            DeviceId = deviceId,
            DeviceName = deviceName,
            RemoteEndPoint = HttpContext.Connection.RemoteIpAddress?.ToString()
        }).ConfigureAwait(false);
        return new TokenDto { AccessToken = result.AccessToken };
    }

    /// <summary>Notes, as a play starts, whether the browser decodes the video itself.</summary>
    /// <param name="body">Item and decoder.</param>
    /// <returns>No content.</returns>
    [HttpPost("Plays/Decoder")]
    public ActionResult PostDecoder([FromBody] DecoderBody body)
    {
        var deviceId = Claim("Jellyfin-DeviceId");
        if (User.GetUser(_users) is null || string.IsNullOrWhiteSpace(deviceId))
        {
            return Unauthorized();
        }

        if (Guid.TryParse(body.ItemId, out var itemId))
        {
            _decoding.Note(deviceId, itemId.ToString("N"), body.OnClient);
        }

        return NoContent();
    }

    /// <summary>Lists the devices that decode their video themselves, for Playing now.</summary>
    /// <returns>Device and item ids.</returns>
    [HttpGet("Admin/Decoders")]
    [Authorize(Policy = Policies.RequiresElevation)]
    public ActionResult<List<DecoderDto>> GetDecoders()
        => _decoding.List().Select(d => new DecoderDto { DeviceId = d.DeviceId, ItemId = d.ItemId }).ToList();

    private string? Claim(string type) => User.Claims.FirstOrDefault(c => c.Type == type)?.Value;
}
