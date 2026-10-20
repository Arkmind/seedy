using System;
using System.Linq;
using System.Security.Claims;
using Jellyfin.Data;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Database.Implementations.Enums;
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

    /// <summary>Formats an id the way Jellyfin's JSON does.</summary>
    /// <param name="id">Id.</param>
    /// <returns>32 hex digits.</returns>
    public static string N(this Guid id) => id.ToString("N");
}
