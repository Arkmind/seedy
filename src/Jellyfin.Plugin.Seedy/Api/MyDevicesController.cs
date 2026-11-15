using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data.Enums;
using Jellyfin.Data.Queries;
using Jellyfin.Database.Implementations.Entities.Security;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Common.Net;
using MediaBrowser.Controller.Devices;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Session;
using MediaBrowser.Model.Session;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>
/// Settings › Devices: where the person is signed in, and signing those devices out. Jellyfin lets only admins list
/// and remove devices, so Seedy does it for the person's own.
/// </summary>
[ApiController]
[Route("Seedy/Me/Devices")]
[Authorize]
[Produces("application/json")]
public class MyDevicesController : ControllerBase
{
    // Jellyfin.Api's InternalClaimTypes; that assembly isn't a plugin package.
    private const string TokenClaim = "Jellyfin-Token";

    // A device first seen this recently is marked New.
    private static readonly TimeSpan NewFor = TimeSpan.FromDays(7);

    // Used this recently counts as in use right now.
    private static readonly TimeSpan ActiveFor = TimeSpan.FromMinutes(5);

    private readonly IUserManager _users;
    private readonly IDeviceManager _devices;
    private readonly ISessionManager _sessions;
    private readonly INetworkManager _network;
    private readonly DeviceTracker _tracker;
    private readonly ILogger<MyDevicesController> _logger;

    /// <summary>
    /// Initializes a new instance of the <see cref="MyDevicesController"/> class.
    /// </summary>
    /// <param name="users">User manager.</param>
    /// <param name="devices">Device manager.</param>
    /// <param name="sessions">Session manager.</param>
    /// <param name="network">Network manager.</param>
    /// <param name="tracker">Device addresses.</param>
    /// <param name="logger">Logger.</param>
    public MyDevicesController(IUserManager users, IDeviceManager devices, ISessionManager sessions, INetworkManager network, DeviceTracker tracker, ILogger<MyDevicesController> logger)
    {
        _users = users;
        _devices = devices;
        _sessions = sessions;
        _network = network;
        _tracker = tracker;
        _logger = logger;
    }

    /// <summary>Every device signed in to the person's profile, this one included.</summary>
    /// <returns>Devices.</returns>
    [HttpGet]
    public ActionResult<MyDevicesDto> Get()
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        var here = CleanRemoteIp();
        var mine = Mine(user.Id).ToList();
        _tracker.Saw(user.Id, mine.FirstOrDefault(IsCurrent)?.DeviceId, here);
        var seen = _tracker.Seen(user.Id);
        var now = DateTime.UtcNow;
        var list = new List<MyDeviceDto>();
        foreach (var d in mine)
        {
            var current = IsCurrent(d);
            var sessions = _sessions.Sessions.Where(s => s.UserId == user.Id && string.Equals(s.DeviceId, d.DeviceId, StringComparison.Ordinal)).ToList();
            var playing = sessions.FirstOrDefault(s => s.NowPlayingItem is not null);
            var sessionLast = sessions.Count > 0 ? sessions.Max(s => s.LastActivityDate) : DateTime.MinValue;
            var last = Utc(d.DateLastActivity) > Utc(sessionLast) ? Utc(d.DateLastActivity) : Utc(sessionLast);
            seen.TryGetValue(d.DeviceId, out var known);
            var ip = current ? here ?? known?.Ip : DeviceTracker.CleanIp(sessions.FirstOrDefault()?.RemoteEndPoint) ?? known?.Ip;
            var signedIn = Utc(d.DateCreated);
            var firstSeen = known is not null && Utc(known.FirstSeen) < signedIn ? Utc(known.FirstSeen) : signedIn;
            var options = _devices.GetDeviceOptions(d.DeviceId);
            list.Add(new MyDeviceDto
            {
                Id = d.DeviceId,
                Name = string.IsNullOrWhiteSpace(options?.CustomName) ? d.DeviceName : options.CustomName,
                ReportedName = d.DeviceName,
                AppName = d.AppName,
                AppVersion = d.AppVersion,
                SignedInAt = signedIn,
                FirstSeenAt = firstSeen,
                LastActiveAt = last.Year < 2000 ? null : last,
                Ip = ip,
                Network = ip is null ? null : _network.IsInLocalNetwork(ip) ? "home" : "away",
                Current = current,
                Active = current || playing is not null || now - last < ActiveFor,
                IsNew = !current && now - firstSeen < NewFor,
                Playing = playing?.NowPlayingItem is { } item ? new MyDevicePlayingDto
                {
                    ItemId = item.Id.ToString("N"),
                    Title = item.Type == BaseItemKind.Episode && !string.IsNullOrEmpty(item.SeriesName) ? item.SeriesName : item.Name ?? string.Empty,
                    Type = item.Type.ToString(),
                    Season = item.Type == BaseItemKind.Episode ? item.ParentIndexNumber : null,
                    Episode = item.Type == BaseItemKind.Episode ? item.IndexNumber : null,
                    Paused = playing.PlayState?.IsPaused == true
                } : null
            });
        }

        return new MyDevicesDto { Devices = list.OrderByDescending(d => d.Current).ThenByDescending(d => d.Playing is not null).ThenByDescending(d => d.LastActiveAt).ToList() };
    }

    /// <summary>Signs one of the person's devices out: playback there stops and its token stops working.</summary>
    /// <param name="id">Device id.</param>
    /// <returns>No content.</returns>
    [HttpDelete]
    public async Task<ActionResult> SignOut([FromQuery] string? id)
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        if (string.IsNullOrEmpty(id))
        {
            return BadRequest();
        }

        var found = Mine(user.Id).Where(d => string.Equals(d.DeviceId, id, StringComparison.Ordinal)).ToList();
        if (found.Count == 0)
        {
            return NotFound();
        }

        // This device signs out from the account menu, which also clears what it stored.
        if (found.Any(IsCurrent))
        {
            return BadRequest();
        }

        foreach (var d in found)
        {
            await End(d).ConfigureAwait(false);
        }

        return NoContent();
    }

    /// <summary>Signs out every device but this one.</summary>
    /// <returns>How many devices were signed out.</returns>
    [HttpPost("SignOutOthers")]
    public async Task<ActionResult<SignedOutDto>> SignOutOthers()
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        var others = Mine(user.Id).Where(d => !IsCurrent(d)).ToList();
        foreach (var d in others)
        {
            await End(d).ConfigureAwait(false);
        }

        return new SignedOutDto { SignedOut = others.Select(d => d.DeviceId).Distinct().Count() };
    }

    private static DateTime Utc(DateTime d) => d.Kind == DateTimeKind.Utc ? d : DateTime.SpecifyKind(d, DateTimeKind.Utc);

    private IEnumerable<Device> Mine(Guid userId) => _devices.GetDevices(new DeviceQuery { UserId = userId }).Items;

    private string? ClaimValue(string type) => User.Claims.FirstOrDefault(c => c.Type == type)?.Value;

    private bool IsCurrent(Device d)
    {
        var token = ClaimValue(TokenClaim);
        return !string.IsNullOrEmpty(token) && string.Equals(d.AccessToken, token, StringComparison.Ordinal);
    }

    private string? CleanRemoteIp() => DeviceTracker.CleanIp(HttpContext.Connection.RemoteIpAddress?.ToString());

    // Stops what it plays first, so the TV doesn't run on until its next progress report fails.
    private async Task End(Device d)
    {
        foreach (var s in _sessions.Sessions.Where(s => s.UserId == d.UserId && string.Equals(s.DeviceId, d.DeviceId, StringComparison.Ordinal) && s.NowPlayingItem is not null).ToList())
        {
            try
            {
                await _sessions.SendPlaystateCommand(null!, s.Id, new PlaystateRequest { Command = PlaystateCommand.Stop }, CancellationToken.None).ConfigureAwait(false);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                _logger.LogDebug(ex, "Couldn't stop playback on {Device}", d.DeviceName);
            }
        }

        await _sessions.Logout(d).ConfigureAwait(false);
        _tracker.Forget(d.UserId, d.DeviceId);
    }
}
