using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data;
using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Enums;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Movies;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Entities;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>
/// What the item dialogs need beyond Jellyfin's own API: what deleting a title removes from disk (and from Seerr),
/// and the people a playlist can be shared with.
/// </summary>
[ApiController]
[Route("Seedy/Items")]
[Authorize]
[Produces("application/json")]
public class ItemsController : ControllerBase
{
    private readonly IUserManager _users;
    private readonly ILibraryManager _library;
    private readonly SeerrService _seerr;
    private readonly ILogger<ItemsController> _logger;

    /// <summary>
    /// Initializes a new instance of the <see cref="ItemsController"/> class.
    /// </summary>
    /// <param name="users">User manager.</param>
    /// <param name="library">Library manager.</param>
    /// <param name="seerr">Seerr service.</param>
    /// <param name="logger">Logger.</param>
    public ItemsController(IUserManager users, ILibraryManager library, SeerrService seerr, ILogger<ItemsController> logger)
    {
        _users = users;
        _library = library;
        _seerr = seerr;
        _logger = logger;
    }

    /// <summary>What deleting these items removes: the files and folders Jellyfin deletes, their size by season (for a
    /// series) or as video files, plus artwork, subtitles and NFO files; and, for request managers, the title in Seerr
    /// with whether Sonarr or Radarr still monitors it.</summary>
    /// <param name="ids">Comma-separated item ids.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The preview.</returns>
    [HttpGet("DeletePreview")]
    public async Task<ActionResult<DeletePreviewDto>> DeletePreview([FromQuery] string ids, CancellationToken ct)
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        var manager = await SeerrManagerAsync(user, ct).ConfigureAwait(false);
        var result = new DeletePreviewDto();
        foreach (var raw in (ids ?? string.Empty).Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).Distinct().Take(500))
        {
            if (!Guid.TryParse(raw, out var id) || _library.GetItemById(id) is not { } item || !item.IsVisible(user))
            {
                continue;
            }

            var dto = Preview(item, user);
            if (manager && dto.CanDelete)
            {
                dto.Seerr = await SeerrStateAsync(item, ct).ConfigureAwait(false);
            }

            result.Items.Add(dto);
            result.Bytes += dto.Bytes;
        }

        return result;
    }

    /// <summary>The other people on the server, to share a playlist with.</summary>
    /// <returns>People, by name.</returns>
    [HttpGet("ShareWith")]
    public ActionResult<List<SharePersonDto>> ShareWith()
    {
        var me = User.GetUser(_users);
        if (me is null)
        {
            return Unauthorized();
        }

        return _users.GetUsers()
            .Where(u => !u.Id.Equals(me.Id) && !u.HasPermission(PermissionKind.IsDisabled))
            .OrderBy(u => u.Username, StringComparer.CurrentCultureIgnoreCase)
            .Select(u => new SharePersonDto { Id = u.Id.ToString("N"), Name = u.Username, HasImage = u.ProfileImage is not null })
            .ToList();
    }

    private DeletePreviewItemDto Preview(BaseItem item, Jellyfin.Database.Implementations.Entities.User user)
    {
        var dto = new DeletePreviewItemDto
        {
            Id = item.Id.ToString("N"),
            Name = item.Name ?? string.Empty,
            Type = item.GetBaseItemKind().ToString(),
            CanDelete = item.CanDelete(user)
        };

        // Every file under what Jellyfin deletes, once.
        var files = new Dictionary<string, long>(StringComparer.Ordinal);
        foreach (var entry in item.GetDeletePaths())
        {
            dto.Paths.Add(entry.FullName);
            try
            {
                if (entry.IsDirectory && Directory.Exists(entry.FullName))
                {
                    foreach (var f in new DirectoryInfo(entry.FullName).EnumerateFiles("*", new EnumerationOptions { RecurseSubdirectories = true, IgnoreInaccessible = true }))
                    {
                        files[f.FullName] = f.Length;
                    }
                }
                else if (System.IO.File.Exists(entry.FullName))
                {
                    files[entry.FullName] = new FileInfo(entry.FullName).Length;
                }
            }
            catch (Exception ex) when (ex is IOException or UnauthorizedAccessException)
            {
                _logger.LogDebug(ex, "Couldn't read {Path}", entry.FullName);
            }
        }

        // The videos among them, by season for a series.
        var videos = item switch
        {
            Series or Season => _library.GetItemList(new InternalItemsQuery(user)
            {
                AncestorIds = [item.Id],
                IncludeItemTypes = [BaseItemKind.Episode],
                Recursive = true,
                IsVirtualItem = false
            }).OfType<Video>().ToList(),
            Video v => [v],
            _ => []
        };

        var groups = new Dictionary<string, DeleteGroupDto>(StringComparer.Ordinal);
        var counted = new HashSet<string>(StringComparer.Ordinal);
        foreach (var video in videos.OrderBy(v => (v as Episode)?.ParentIndexNumber ?? 0).ThenBy(v => v.IndexNumber ?? 0))
        {
            var season = item is Series ? (video as Episode)?.Season : null;
            var key = season?.Id.ToString("N") ?? "media";
            if (!groups.TryGetValue(key, out var g))
            {
                var stream = video.GetDefaultVideoStream();
                g = new DeleteGroupDto
                {
                    Kind = season is null ? "media" : "season",
                    Id = season?.Id.ToString("N"),
                    Name = season?.Name,
                    Index = season?.IndexNumber,
                    Height = stream?.Height,
                    Codec = stream?.Codec
                };
                groups[key] = g;
            }

            var any = false;
            foreach (var source in video.GetMediaSources(false))
            {
                if (source.Path is { } path && files.TryGetValue(path, out var size) && counted.Add(path))
                {
                    g.Files++;
                    g.Bytes += size;
                    any = true;
                }
            }

            if (any)
            {
                g.Episodes++;
            }
        }

        dto.Groups.AddRange(groups.Values.Where(g => g.Files > 0));
        var extras = files.Where(f => !counted.Contains(f.Key)).ToList();
        if (extras.Count > 0)
        {
            dto.Groups.Add(new DeleteGroupDto { Kind = "extras", Files = extras.Count, Bytes = extras.Sum(f => f.Value) });
        }

        dto.Bytes = files.Values.Sum();
        return dto;
    }

    /* Request managers can clear a deleted title in Seerr; only they see it. */
    private async Task<bool> SeerrManagerAsync(Jellyfin.Database.Implementations.Entities.User user, CancellationToken ct)
    {
        if (!SeerrService.IsConfigured)
        {
            return false;
        }

        try
        {
            var (account, _) = await _seerr.AccountFor(user, ct).ConfigureAwait(false);
            return account is not null && await _seerr.CanManageRequestsAsync(account.Id, ct).ConfigureAwait(false);
        }
        catch (SeerrException)
        {
            return false;
        }
    }

    private async Task<SeerrMediaStateDto?> SeerrStateAsync(BaseItem item, CancellationToken ct)
    {
        var mediaType = item switch { Movie => "movie", Series => "tv", _ => null };
        if (mediaType is null || !int.TryParse(item.GetProviderId(MediaBrowser.Model.Entities.MetadataProvider.Tmdb), System.Globalization.NumberStyles.Integer, System.Globalization.CultureInfo.InvariantCulture, out var tmdbId))
        {
            return null;
        }

        try
        {
            return await _seerr.MediaStateAsync(mediaType, tmdbId, ct).ConfigureAwait(false);
        }
        catch (SeerrException)
        {
            return null;
        }
    }
}
