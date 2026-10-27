using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Seedy.Services;
using Jellyfin.Plugin.Seedy.Services.Media;
using MediaBrowser.Common.Api;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Globalization;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>The admin's Music and book requests page: app keys, connection tests, Lidarr's profiles and folders, where
/// each format lands, and limits per profile. Administrators only.</summary>
[ApiController]
[Route("Seedy/Admin/MediaRequests")]
[Authorize(Policy = Policies.RequiresElevation)]
[Produces("application/json")]
public class AdminMediaController : ControllerBase
{
    private readonly IUserManager _users;
    private readonly ILocalizationManager _localization;
    private readonly ServiceHttp _http;
    private readonly LidarrClient _lidarr;
    private readonly ShelfarrClient _shelfarr;
    private readonly LibrarrClient _librarr;
    private readonly ReadarrClient _readarr;
    private readonly MediaLibrary _library;

    /// <summary>
    /// Initializes a new instance of the <see cref="AdminMediaController"/> class.
    /// </summary>
    /// <param name="users">User manager.</param>
    /// <param name="localization">Localization manager.</param>
    /// <param name="http">HTTP helper.</param>
    /// <param name="lidarr">Lidarr.</param>
    /// <param name="shelfarr">Shelfarr.</param>
    /// <param name="librarr">Librarr.</param>
    /// <param name="readarr">Readarr-API app.</param>
    /// <param name="library">What's on the server.</param>
    public AdminMediaController(IUserManager users, ILocalizationManager localization, ServiceHttp http, LidarrClient lidarr, ShelfarrClient shelfarr, LibrarrClient librarr, ReadarrClient readarr, MediaLibrary library)
    {
        _users = users;
        _localization = localization;
        _http = http;
        _lidarr = lidarr;
        _shelfarr = shelfarr;
        _librarr = librarr;
        _readarr = readarr;
        _library = library;
    }

    private static Plugin PluginInstance => Plugin.Instance ?? throw new InvalidOperationException("Seedy isn't loaded.");

    /// <summary>Lidarr's profiles and folders (with the library each folder matches) and where each book format lands.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Info.</returns>
    [HttpGet("Info")]
    public async Task<ActionResult<AdminMediaInfoDto>> Info(CancellationToken ct)
    {
        var dto = new AdminMediaInfoDto();
        if (LidarrClient.IsConfigured)
        {
            try
            {
                dto.QualityProfiles = await _lidarr.QualityProfilesAsync(ct).ConfigureAwait(false);
                dto.MetadataProfiles = await _lidarr.MetadataProfilesAsync(ct).ConfigureAwait(false);
                dto.RootFolders = (await _lidarr.RootFoldersAsync(ct).ConfigureAwait(false))
                    .Select(f => new RootFolderDto { Path = f.Path, FreeSpace = f.Free, Library = _library.LibraryForPath(f.Path)?.Name })
                    .ToList();
            }
            catch (MediaServiceException ex)
            {
                dto.LidarrError = ex.Message;
            }
        }

        foreach (var f in new[] { "ebook", "audiobook", "comic" })
        {
            var lib = _library.LibraryForFormat(f);
            dto.Landing.Add(new LandingDto { Format = f, Library = lib?.Name, Path = lib?.Location });
        }

        return dto;
    }

    /// <summary>Tests an app's address and key; with neither, the saved ones.</summary>
    /// <param name="service">lidarr, shelfarr, librarr or readarr.</param>
    /// <param name="body">Address and key.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Result.</returns>
    [HttpPost("Test/{service}")]
    public async Task<ActionResult<MediaTestResultDto>> Test([FromRoute] string service, [FromBody] SeerrTestBody body, CancellationToken ct) => service switch
    {
        "lidarr" => await _lidarr.TestAsync(body.Url, body.ApiKey, ct).ConfigureAwait(false),
        "shelfarr" => await _shelfarr.TestAsync(body.Url, body.ApiKey, ct).ConfigureAwait(false),
        "librarr" => await _librarr.TestAsync(body.Url, body.ApiKey, ct).ConfigureAwait(false),
        "readarr" => await _readarr.TestAsync(body.Url, body.ApiKey, ct).ConfigureAwait(false),
        _ => NotFound()
    };

    /// <summary>Saves an app's key. It is never sent back to browsers.</summary>
    /// <param name="body">App and key.</param>
    /// <returns>Saved settings.</returns>
    [HttpPost("Key")]
    public ActionResult<AdminConfigDto> SetKey([FromBody] MediaKeyBody body)
    {
        var c = PluginInstance.Configuration;
        var key = (body.ApiKey ?? string.Empty).Trim();
        switch (body.Service)
        {
            case "lidarr":
                c.LidarrApiKey = key;
                break;
            case "shelfarr":
                c.ShelfarrToken = key;
                break;
            case "librarr":
                c.LibrarrApiKey = key;
                break;
            case "readarr":
                c.ReadarrApiKey = key;
                break;
            default:
                return NotFound();
        }

        PluginInstance.Save(c);
        _http.Reset(MediaRequestService.ServiceName(body.Service));
        return ConfigMapper.ToAdmin(c);
    }

    /// <summary>Each profile's music and book limits and auto-approval.</summary>
    /// <returns>Profiles.</returns>
    [HttpGet("Profiles")]
    public ActionResult<List<AdminMediaProfileDto>> Profiles() => List();

    /// <summary>Changes a profile's limits or auto-approval.</summary>
    /// <param name="userId">Profile.</param>
    /// <param name="body">Changes.</param>
    /// <returns>Profiles.</returns>
    [HttpPost("Profiles/{userId}")]
    public ActionResult<List<AdminMediaProfileDto>> SetProfile([FromRoute] string userId, [FromBody] MediaProfileBody body)
    {
        if (!Guid.TryParse(userId, out var id) || _users.GetUserById(id) is not { } user)
        {
            return NotFound();
        }

        var c = PluginInstance.Configuration;
        var current = ConfigMapper.MediaProfile(c, user);
        var profile = new Configuration.MediaRequestProfile
        {
            UserId = id.ToString("N"),
            MusicPerWeek = Math.Clamp(body.MusicPerWeek ?? current.MusicPerWeek, -1, 100),
            BooksPerMonth = Math.Clamp(body.BooksPerMonth ?? current.BooksPerMonth, -1, 100),
            AutoApprove = body.AutoApprove ?? current.AutoApprove
        };
        ConfigMapper.Upsert(c, profile);
        PluginInstance.Save(c);
        return List();
    }

    private List<AdminMediaProfileDto> List()
    {
        var c = PluginInstance.Configuration;
        var ratings = _localization.GetParentalRatings();
        return _users.GetUsers()
            .OrderBy(u => !u.IsAdmin())
            .ThenBy(u => u.Username, StringComparer.OrdinalIgnoreCase)
            .Select(u =>
            {
                var p = ConfigMapper.MediaProfile(c, u);
                return new AdminMediaProfileDto
                {
                    UserId = u.Id.ToString("N"),
                    Name = u.Username,
                    IsAdmin = u.IsAdmin(),
                    MaxParentalRating = AdminController.RatingName(u, ratings),
                    ImageTag = _users.GetUserDto(u).PrimaryImageTag,
                    MusicPerWeek = p.MusicPerWeek,
                    BooksPerMonth = p.BooksPerMonth,
                    AutoApprove = p.AutoApprove
                };
            })
            .ToList();
    }
}
