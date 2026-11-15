namespace Jellyfin.Plugin.Seedy.Configuration;

/// <summary>An entry of one of the per-profile lists in the configuration.</summary>
internal interface IUserProfile
{
    /// <summary>Gets the Jellyfin user id ("N" format).</summary>
    string UserId { get; }
}
