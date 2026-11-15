using System;
using System.Collections.Generic;
using System.Linq;
using System.Security.Claims;
using Jellyfin.Data;
using Jellyfin.Data.Queries;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Devices;
using MediaBrowser.Controller.Library;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>Resolves the signed-in Jellyfin user behind a request.</summary>
public static class UserContext
{
    // Jellyfin.Api's InternalClaimTypes.UserId; that assembly isn't a plugin package, so the value is repeated here.
    private const string UserIdClaim = "Jellyfin-UserId";

    /// <summary>Gets the user id from the request's claims.</summary>
    /// <param name="principal">The request user.</param>
    /// <returns>User id, or empty.</returns>
    public static Guid GetUserId(this ClaimsPrincipal principal)
    {
        var value = principal.Claims.FirstOrDefault(c => c.Type == UserIdClaim)?.Value;
        return Guid.TryParse(value, out var id) ? id : Guid.Empty;
    }

    /// <summary>Gets the device name the request's client signed in with.</summary>
    /// <param name="principal">The request user.</param>
    /// <returns>Device name, or null.</returns>
    public static string? GetDeviceName(this ClaimsPrincipal principal)
        => principal.Claims.FirstOrDefault(c => c.Type == "Jellyfin-Device")?.Value;

    /// <summary>Gets the app the request comes from.</summary>
    /// <param name="principal">The request user.</param>
    /// <returns>App name, or null.</returns>
    public static string? GetClientName(this ClaimsPrincipal principal)
        => principal.Claims.FirstOrDefault(c => c.Type == "Jellyfin-Client")?.Value;

    /// <summary>Gets a claim Jellyfin puts on authenticated requests ("Jellyfin-Token", "Jellyfin-DeviceId"…).</summary>
    /// <param name="principal">The request user.</param>
    /// <param name="type">Claim type.</param>
    /// <returns>The value, or null.</returns>
    public static string? GetClaim(this ClaimsPrincipal principal, string type)
        => principal.Claims.FirstOrDefault(c => c.Type == type)?.Value;

    /// <summary>
    /// Gets whether the request's token was made for another app or device than the one using it: the web app picks up
    /// the sign-in of Jellyfin's own web app. Jellyfin ties the websocket to the token's own app and device, so on such a
    /// token the socket and the playing session are two sessions, and the admin can't stop or message the player.
    /// </summary>
    /// <param name="principal">The request user.</param>
    /// <param name="devices">Device manager.</param>
    /// <returns>True when the token belongs to another app or device; false for its own token and for API keys.</returns>
    public static bool UsesBorrowedToken(this ClaimsPrincipal principal, IDeviceManager devices)
    {
        var token = principal.GetClaim("Jellyfin-Token");
        if (string.IsNullOrEmpty(token))
        {
            return false;
        }

        var device = devices.GetDevices(new DeviceQuery { AccessToken = token }).Items.FirstOrDefault();
        return device is not null
            && !(string.Equals(device.AppName, principal.GetClientName(), StringComparison.Ordinal)
                && string.Equals(device.DeviceId, principal.GetClaim("Jellyfin-DeviceId"), StringComparison.Ordinal));
    }

    /// <summary>Gets the signed-in user.</summary>
    /// <param name="principal">The request user.</param>
    /// <param name="users">User manager.</param>
    /// <returns>The user, or null for API keys and anonymous requests.</returns>
    public static User? GetUser(this ClaimsPrincipal principal, IUserManager users)
    {
        var id = principal.GetUserId();
        return id == Guid.Empty ? null : users.GetUserById(id);
    }

    /// <summary>Gets whether a user administers the server.</summary>
    /// <param name="user">User.</param>
    /// <returns>True for administrators.</returns>
    public static bool IsAdmin(this User user) => user.HasPermission(PermissionKind.IsAdministrator);

    /// <summary>Lists the profiles admins first, then by name: the order the admin pages show them in, which also
    /// picks their avatar colours in the statistics.</summary>
    /// <param name="users">User manager.</param>
    /// <returns>Profiles.</returns>
    public static IEnumerable<User> InAdminOrder(this IUserManager users)
        => users.GetUsers().OrderBy(u => !u.IsAdmin()).ThenBy(u => u.Username, StringComparer.OrdinalIgnoreCase);
}
