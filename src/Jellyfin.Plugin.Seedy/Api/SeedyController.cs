using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data.Enums;
using Jellyfin.Data.Queries;
using Jellyfin.Database.Implementations.Enums;
using Jellyfin.Plugin.Seedy.Data;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Controller.Drawing;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Activity;
using MediaBrowser.Model.Dto;
using MediaBrowser.Model.Entities;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>
/// The signed-in person's Seedy: branding, preferences, home rows, Top 10, and their viewing history.
/// API paths never equal an app page path (routes are case-insensitive), which is why history is /Seedy/Plays.
/// </summary>
[ApiController]
[Route("Seedy")]
[Authorize]
[Produces("application/json")]
public class SeedyController : ControllerBase
{
    private readonly IUserManager _users;
    private readonly ILibraryManager _library;
    private readonly SeedyDatabase _db;
    private readonly PrefsService _prefs;
    private readonly HomeService _home;
    private readonly SeerrService _seerr;
    private readonly PlaybackRecorder _recorder;
    private readonly IImageProcessor _images;
    private readonly IActivityManager _activity;

    /// <summary>
    /// Initializes a new instance of the <see cref="SeedyController"/> class.
    /// </summary>
    /// <param name="users">User manager.</param>
    /// <param name="library">Library manager.</param>
    /// <param name="db">Database.</param>
    /// <param name="prefs">Preferences.</param>
    /// <param name="home">Home service.</param>
    /// <param name="seerr">Seerr service.</param>
    /// <param name="recorder">Play recorder.</param>
    /// <param name="images">Image processor.</param>
    public SeedyController(IUserManager users, ILibraryManager library, SeedyDatabase db, PrefsService prefs, HomeService home, SeerrService seerr, PlaybackRecorder recorder, IImageProcessor images, IActivityManager activity)
    {
        _activity = activity;
        _images = images;
        _users = users;
        _library = library;
        _db = db;
        _prefs = prefs;
        _home = home;
        _seerr = seerr;
        _recorder = recorder;
    }

    // ── Public: what the sign-in page needs ──

    /// <summary>Branding for the sign-in page and header.</summary>
    /// <returns>Branding.</returns>
    [HttpGet("Client")]
    [AllowAnonymous]
    public ActionResult<BrandingDto> GetClient() => ConfigMapper.Branding(ConfigMapper.Current);

    /// <summary>The uploaded logo.</summary>
    /// <returns>The image.</returns>
    [HttpGet("Client/Logo")]
    [AllowAnonymous]
    public ActionResult GetLogo()
    {
        var path = LogoPath();
        if (path is null)
        {
            return NotFound();
        }

        // An admin-uploaded SVG must not run script on this origin.
        Response.Headers.ContentSecurityPolicy = "default-src 'none'; style-src 'unsafe-inline'; sandbox";
        Response.Headers.XContentTypeOptions = "nosniff";
        Response.Headers.CacheControl = "public, max-age=86400";
        var type = Path.GetExtension(path).ToLowerInvariant() switch
        {
            ".svg" => "image/svg+xml",
            ".png" => "image/png",
            ".webp" => "image/webp",
            _ => "image/jpeg"
        };
        return PhysicalFile(path, type);
    }

    /// <summary>
    /// The browser tab icon: the uploaded logo, or else the brand mark the app shows without one (the name's first
    /// letter on the light rounded square).
    /// </summary>
    /// <returns>The image.</returns>
    [HttpGet("Client/Favicon")]
    [AllowAnonymous]
    public ActionResult GetFavicon()
    {
        if (LogoPath() is not null)
        {
            return GetLogo();
        }

        var name = ConfigMapper.Branding(ConfigMapper.Current).Name;
        var initial = string.IsNullOrWhiteSpace(name) ? "S" : name.Trim()[..1].ToUpperInvariant();
        var svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 64 64\">"
            + "<rect width=\"64\" height=\"64\" rx=\"18\" fill=\"#f0f0f0\"/>"
            + "<text x=\"32\" y=\"45\" text-anchor=\"middle\" font-family=\"'Noto Sans', system-ui, sans-serif\" font-size=\"36\" font-weight=\"900\" fill=\"#1e1e1e\">"
            + System.Security.SecurityElement.Escape(initial) + "</text></svg>";
        Response.Headers.CacheControl = "public, max-age=86400";
        Response.Headers.XContentTypeOptions = "nosniff";
        return Content(svg, "image/svg+xml");
    }

    /// <summary>Finds the stored logo file.</summary>
    /// <returns>Its path, or null.</returns>
    public static string? LogoPath()
    {
        var folder = Plugin.Instance?.SeedyDataPath;
        if (folder is null || string.IsNullOrEmpty(ConfigMapper.Current.LogoFileName))
        {
            return null;
        }

        return Directory.EnumerateFiles(folder, "logo.*").FirstOrDefault();
    }

    /// <summary>A few library backdrops for the sign-in page, when the admin chose that background.</summary>
    /// <returns>Image URLs.</returns>
    [HttpGet("Client/Backdrops")]
    [AllowAnonymous]
    public ActionResult<List<string>> GetBackdrops()
    {
        if (ConfigMapper.Current.SignInBackground == "plain")
        {
            return new List<string>();
        }

        // Anyone can open the sign-in page, so only use titles without a restrictive rating.
        var items = _library.GetItemList(new InternalItemsQuery
        {
            IncludeItemTypes = [BaseItemKind.Movie, BaseItemKind.Series],
            ImageTypes = [ImageType.Backdrop],
            Recursive = true,
            OrderBy = [(ItemSortBy.Random, SortOrder.Ascending)],
            Limit = 24,
            DtoOptions = new MediaBrowser.Controller.Dto.DtoOptions(false)
        });
        var urls = new List<string>();
        foreach (var item in items)
        {
            var score = item.GetParentalRatingScore()?.Score;
            if (score is > 12)
            {
                continue;
            }

            var image = item.GetImageInfo(ImageType.Backdrop, 0);
            if (image is null)
            {
                continue;
            }

            urls.Add($"/Items/{item.Id:N}/Images/Backdrop/0?maxWidth=1920&quality=80&tag={_images.GetImageCacheTag(item, image)}");
            if (urls.Count >= 8)
            {
                break;
            }
        }

        return urls;
    }

    // ── The signed-in person ──

    /// <summary>Everything the app needs to know about the signed-in person.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Profile.</returns>
    [HttpGet("Me")]
    public async Task<ActionResult<MeDto>> GetMe(CancellationToken ct)
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        var c = ConfigMapper.Current;
        var uid = user.Id.ToString("N");

        // Seerr must never hold up opening the app: give it a moment, then carry on without it.
        Api.SeerrUserDto? account = null;
        string? matchedBy = null;
        string? publicUrl = null;
        var connected = SeerrService.IsConfigured;
        if (connected)
        {
            try
            {
                var lookup = Task.WhenAll(_seerr.AccountFor(user, ct), _seerr.PublicUrlAsync(ct));
                if (await Task.WhenAny(lookup, Task.Delay(TimeSpan.FromSeconds(2), ct)).ConfigureAwait(false) == lookup)
                {
                    (account, matchedBy) = await _seerr.AccountFor(user, ct).ConfigureAwait(false);
                    publicUrl = await _seerr.PublicUrlAsync(ct).ConfigureAwait(false);
                }
            }
#pragma warning disable CA1031 // Seerr trouble shows up as "not connected", never as a broken app.
            catch (Exception)
#pragma warning restore CA1031
            {
                account = null;
            }
        }

        // Profiles with a maturity limit don't browse Seerr: its titles carry no ratings Seedy could filter on.
        var restricted = user.MaxParentalRatingScore is not null;
        var seerrForUser = connected && !restricted;
        var profile = ConfigMapper.PlayLogProfile(c, uid);
        return new MeDto
        {
            IsAdmin = user.IsAdmin(),
            Branding = ConfigMapper.Branding(c),
            Prefs = _prefs.Get(uid),
            Defaults = ConfigMapper.ToDto(c.Playback),
            Requests = new RequestsFeaturesDto
            {
                Connected = seerrForUser,
                Discover = seerrForUser && c.ShowDiscover,
                UpcomingMovies = seerrForUser && c.ShowUpcomingMovies,
                RequestButtons = seerrForUser && c.ShowRequestButtons,
                RequestInSearch = seerrForUser && c.ShowRequestInSearch,
                CanRequest = seerrForUser && SeerrService.CanRequest(user, account is not null),
                Allow4k = seerrForUser && c.Allow4k,
                SeerrUrl = seerrForUser ? publicUrl ?? c.SeerrUrl : null,
                Account = seerrForUser && account is not null
                    ? new SeerrAccountRefDto { Name = account.DisplayName, LinkedBy = matchedBy ?? "jellyfin" }
                    : null
            },
            HistoryEnabled = c.PlayLogEnabled && c.UseForHistory && profile.History,
            HomeRows = ConfigMapper.HomeRows(c).Select(r => new HomeRowSettingDto
            {
                Kind = r.Kind,
                Enabled = r.Enabled && r.Kind switch
                {
                    "discover" => seerrForUser && c.ShowDiscover,
                    "upcomingMovies" => seerrForUser && c.ShowUpcomingMovies,
                    "top10" => c.PlayLogEnabled && c.UseForTop10,
                    _ => true
                },
                Count = r.Count > 0 ? r.Count : null
            }).ToList(),
            PluginVersion = Plugin.Instance?.VersionString ?? "0.1.0"
        };
    }

    /// <summary>When the person's password last changed. Only the activity log knows, and it is admin-only in Jellyfin.</summary>
    /// <returns>The date, or null when the log doesn't go back that far.</returns>
    [HttpGet("Me/Password")]
    public async Task<ActionResult<PasswordStateDto>> GetPasswordState()
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        var log = await _activity.GetPagedResultAsync(new ActivityLogQuery { Type = "UserPasswordChanged", Limit = 500 }).ConfigureAwait(false);
        var last = log.Items.Where(e => e.UserId == user.Id).Select(e => (DateTime?)e.Date).Max();
        return new PasswordStateDto { ChangedAt = last is { } d ? DateTime.SpecifyKind(d, DateTimeKind.Utc) : null };
    }

    /// <summary>Saves some of the person's preferences.</summary>
    /// <param name="patch">Fields to change.</param>
    /// <returns>All preferences.</returns>
    [HttpPost("Me/Prefs")]
    public ActionResult<UserPrefsDto> SavePrefs([FromBody] UserPrefsPatch patch)
    {
        var user = User.GetUser(_users);
        return user is null ? Unauthorized() : _prefs.Save(user.Id.ToString("N"), patch);
    }

    /// <summary>Forgets the row order Seedy learned for the person.</summary>
    /// <returns>No content.</returns>
    [HttpDelete("Me/RowOrder")]
    public ActionResult ResetRowOrder()
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        _db.ResetRowOrder(user.Id.ToString("N"));
        return NoContent();
    }

    // ── Home ──

    /// <summary>The person's home rows, in order.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Rows.</returns>
    [HttpGet("Home")]
    public async Task<ActionResult<HomeDto>> GetHome(CancellationToken ct)
    {
        var user = User.GetUser(_users);
        return user is null ? Unauthorized() : new HomeDto { Rows = await _home.RowsAsync(user, ct).ConfigureAwait(false) };
    }

    /// <summary>The hero rotation.</summary>
    /// <returns>Entries.</returns>
    [HttpGet("Home/Featured")]
    public ActionResult<List<FeaturedEntryDto>> GetFeatured()
    {
        var user = User.GetUser(_users);
        return user is null ? Unauthorized() : _home.Featured(user);
    }

    /// <summary>The Top 10 this period.</summary>
    /// <returns>Top 10.</returns>
    [HttpGet("Home/Top10")]
    public ActionResult<Top10Dto> GetTop10()
    {
        var user = User.GetUser(_users);
        return user is null ? Unauthorized() : _home.Top10(user);
    }

    // ── Plays ──

    /// <summary>Notes which home row a play is starting from.</summary>
    /// <param name="body">Item and row.</param>
    /// <returns>No content.</returns>
    [HttpPost("Plays/Origin")]
    public ActionResult PostOrigin([FromBody] OriginBody body)
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        if (Guid.TryParse(body.ItemId, out var itemId) && !string.IsNullOrWhiteSpace(body.Row) && body.Row.Length <= 40)
        {
            _recorder.SetOrigin(user.Id.ToString("N"), itemId.ToString("N"), body.Row);
        }

        return NoContent();
    }

    private bool HistoryAllowed(string uid)
    {
        var c = ConfigMapper.Current;
        return c.PlayLogEnabled && c.UseForHistory && ConfigMapper.PlayLogProfile(c, uid).History;
    }

    /// <summary>The person's viewing history, newest first.</summary>
    /// <param name="startIndex">Rows to skip.</param>
    /// <param name="limit">Rows to return.</param>
    /// <param name="type">all, movies or shows.</param>
    /// <param name="search">Text in the title.</param>
    /// <param name="includeHidden">Include hidden titles.</param>
    /// <returns>History.</returns>
    [HttpGet("Plays")]
    public ActionResult<HistoryDto> GetPlays([FromQuery] int startIndex = 0, [FromQuery] int limit = 30, [FromQuery] string type = "all", [FromQuery] string? search = null, [FromQuery] bool includeHidden = false)
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        var uid = user.Id.ToString("N");
        if (!HistoryAllowed(uid))
        {
            return StatusCode(StatusCodes.Status403Forbidden, new { message = "Viewing history is off for this profile." });
        }

        string[] types = type switch
        {
            "movies" => ["Movie"],
            "shows" => ["Episode"],
            _ => []
        };
        var rows = _db.History(uid, types, search, includeHidden, Math.Max(0, startIndex), Math.Clamp(limit, 1, 200), out var total);

        var items = new Dictionary<string, BaseItem>(StringComparer.Ordinal);
        foreach (var (play, _) in rows)
        {
            if (!items.ContainsKey(play.ItemId) && Guid.TryParse(play.ItemId, out var id) && _library.GetItemById<BaseItem>(id, user) is { } item)
            {
                items[play.ItemId] = item;
            }
        }

        var keys = items.Keys.ToList();
        var dtos = _home.ToDtos(keys.Select(k => items[k]).ToList(), user);
        var byId = new Dictionary<string, BaseItemDto>(StringComparer.Ordinal);
        for (var i = 0; i < keys.Count; i++)
        {
            byId[keys[i]] = dtos[i];
        }

        return new HistoryDto
        {
            Total = total,
            Items = rows.Select(r => new HistoryEntryDto
            {
                Id = r.Play.Id,
                ItemId = r.Play.ItemId,
                TitleId = r.Play.TitleId,
                TitleName = r.Play.TitleName,
                ItemName = r.Play.ItemName,
                ItemType = r.Play.ItemType,
                Item = byId.GetValueOrDefault(r.Play.ItemId),
                StartedAt = DateTime.SpecifyKind(r.Play.StartedAt, DateTimeKind.Utc),
                StoppedAt = r.Play.StoppedAt is null ? null : DateTime.SpecifyKind(r.Play.StoppedAt.Value, DateTimeKind.Utc),
                PositionTicks = r.Play.PositionTicks,
                RuntimeTicks = r.Play.RuntimeTicks,
                Finished = r.Play.Finished,
                DeviceName = r.Play.DeviceName,
                Client = r.Play.Client,
                Hidden = r.Hidden
            }).ToList()
        };
    }

    /// <summary>Hides or unhides a title from history, recommendations and the Top 10.</summary>
    /// <param name="titleId">Movie or series id.</param>
    /// <param name="body">Hidden or not.</param>
    /// <returns>No content.</returns>
    [HttpPost("Plays/Titles/{titleId}/Hidden")]
    public ActionResult SetHidden([FromRoute] string titleId, [FromBody] HiddenBody body)
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        if (!Guid.TryParse(titleId, out var id))
        {
            return BadRequest();
        }

        _db.SetHidden(user.Id.ToString("N"), id.ToString("N"), body.Hidden);
        return NoContent();
    }

    /// <summary>Removes one play from the person's history.</summary>
    /// <param name="id">Play id.</param>
    /// <returns>No content.</returns>
    [HttpDelete("Plays/{id}")]
    public ActionResult DeletePlay([FromRoute] string id)
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        return _db.DeletePlay(user.Id.ToString("N"), id) ? NoContent() : NotFound();
    }

    /// <summary>Clears the person's history.</summary>
    /// <returns>No content.</returns>
    [HttpDelete("Plays")]
    public ActionResult ClearPlays()
    {
        var user = User.GetUser(_users);
        if (user is null)
        {
            return Unauthorized();
        }

        _db.ClearPlays(user.Id.ToString("N"));
        return NoContent();
    }
}
