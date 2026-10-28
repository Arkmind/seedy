using System;
using System.Collections.Generic;
using System.Linq;
using Jellyfin.Data.Enums;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Controller.Dto;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>
/// Audiobooks in several files (an MP3 per chapter or per CD), each as one book: the books pages swap the files for
/// these, and the player plays the files one after the other. See <see cref="Audiobooks"/> for which files belong
/// together.
/// </summary>
[ApiController]
[Route("Seedy/Audiobooks")]
[Authorize]
[Produces("application/json")]
public class AudiobooksController : ControllerBase
{
    private readonly IUserManager _users;
    private readonly ILibraryManager _library;
    private readonly Audiobooks _audiobooks;

    /// <summary>Initializes a new instance of the <see cref="AudiobooksController"/> class.</summary>
    /// <param name="users">User manager.</param>
    /// <param name="library">Library manager.</param>
    /// <param name="audiobooks">Audiobooks in several files.</param>
    public AudiobooksController(IUserManager users, ILibraryManager library, Audiobooks audiobooks)
    {
        _users = users;
        _library = library;
        _audiobooks = audiobooks;
    }

    /// <summary>The audiobooks in several files of a library (or of every library).</summary>
    /// <param name="parentId">Library id.</param>
    /// <param name="searchTerm">Only books whose name, or one of whose files' names, has these words (search).</param>
    /// <returns>The books, each with its files.</returns>
    [HttpGet]
    public ActionResult<List<AudiobookDto>> List([FromQuery] Guid? parentId, [FromQuery] string? searchTerm)
    {
        if (User.GetUser(_users) is not { } user)
        {
            return Unauthorized();
        }

        var files = _library.GetItemList(new InternalItemsQuery(user)
        {
            ParentId = parentId ?? Guid.Empty,
            Recursive = true,
            IncludeItemTypes = [BaseItemKind.AudioBook],
            DtoOptions = new DtoOptions(false)
        });
        IEnumerable<AudiobookGroup> groups = _audiobooks.Group(files);
        if (!string.IsNullOrWhiteSpace(searchTerm))
        {
            var q = searchTerm.Trim();
            groups = groups.Where(g => g.Name.Contains(q, StringComparison.OrdinalIgnoreCase) || g.Parts.Any(p => p.Name?.Contains(q, StringComparison.OrdinalIgnoreCase) == true)).Take(20);
        }

        return groups.Select(g => _audiobooks.ToDto(g, user)).ToList();
    }

    /// <summary>One audiobook in several files, by its folder's id or any of its files' ids.</summary>
    /// <param name="id">Folder or file id.</param>
    /// <returns>The book and its files; 204 when it's a book in one file (or not an audiobook).</returns>
    [HttpGet("{id}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public ActionResult<AudiobookDto> Get([FromRoute] Guid id)
    {
        if (User.GetUser(_users) is not { } user)
        {
            return Unauthorized();
        }

        if (_library.GetItemById(id) is not { } item || !item.IsVisible(user))
        {
            return NotFound();
        }

        return _audiobooks.Find(item, user) is { } g ? _audiobooks.ToDto(g, user) : NoContent();
    }
}
