using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text.Json;
using System.Threading;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Plugin.Seedy.Data;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Entities;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>
/// What the book reader needs beyond Jellyfin's API: the book file itself (reading isn't downloading, so it doesn't
/// need the download permission), its length and chapters, comic pages one at a time, and each profile's place in
/// each book with the reader settings saved for it.
/// </summary>
[ApiController]
[Route("Seedy")]
[Authorize]
[Produces("application/json")]
public class BooksController : ControllerBase
{
    private readonly IUserManager _users;
    private readonly ILibraryManager _library;
    private readonly IUserDataManager _userData;
    private readonly SeedyDatabase _db;
    private readonly BookFiles _files;

    /// <summary>
    /// Initializes a new instance of the <see cref="BooksController"/> class.
    /// </summary>
    /// <param name="users">User manager.</param>
    /// <param name="library">Library manager.</param>
    /// <param name="userData">User data manager.</param>
    /// <param name="db">Seedy database.</param>
    /// <param name="files">Book file reader.</param>
    public BooksController(IUserManager users, ILibraryManager library, IUserDataManager userData, SeedyDatabase db, BookFiles files)
    {
        _users = users;
        _library = library;
        _userData = userData;
        _db = db;
        _files = files;
    }

    /// <summary>The book file (EPUB, PDF, CBZ…), for the reader.</summary>
    /// <param name="id">Book id.</param>
    /// <returns>The file.</returns>
    [HttpGet("Books/{id}/File")]
    [Produces("application/octet-stream")]
    public ActionResult GetFile([FromRoute] Guid id)
    {
        if (Book(id) is not { } item)
        {
            return NotFound();
        }

        return PhysicalFile(item.Path, BookFiles.MimeOf(item.Path), Path.GetFileName(item.Path), enableRangeProcessing: true);
    }

    /// <summary>The book's format, length in pages, reading time and chapters.</summary>
    /// <param name="id">Book id.</param>
    /// <returns>The info.</returns>
    [HttpGet("Books/{id}/Info")]
    public ActionResult<BookFileInfo> GetInfo([FromRoute] Guid id)
    {
        if (Book(id) is not { } item)
        {
            return NotFound();
        }

        return _files.Info(item.Path);
    }

    /// <summary>One page of a comic book (CBZ), from 0.</summary>
    /// <param name="id">Book id.</param>
    /// <param name="index">Page index.</param>
    /// <returns>The image.</returns>
    [HttpGet("Books/{id}/Pages/{index:int}")]
    [Produces("image/jpeg", "image/png", "image/webp")]
    public ActionResult GetPage([FromRoute] Guid id, [FromRoute] int index)
    {
        if (Book(id) is not { } item || BookFiles.FormatOf(item.Path) != "cbz" || _files.ComicPage(item.Path, index) is not { } page)
        {
            return NotFound();
        }

        Response.Headers.CacheControl = "private, max-age=86400";
        return File(page.Bytes, page.Mime);
    }

    /// <summary>The books this profile is reading, most recent first.</summary>
    /// <param name="finished">Whether finished books are included.</param>
    /// <param name="limit">Most results.</param>
    /// <returns>The records.</returns>
    [HttpGet("Reading")]
    public ActionResult<List<ReadingDto>> ListReading([FromQuery] bool finished = false, [FromQuery] int limit = 20)
    {
        if (User.GetUser(_users) is not { } user)
        {
            return Unauthorized();
        }

        return _db.ListReading(user.Id.N(), finished, Math.Clamp(limit, 1, 200))
            .Where(r => Guid.TryParse(r.ItemId, out var gid) && _library.GetItemById(gid) is { } item && item.IsVisible(user))
            .Select(ReadingDto.From)
            .ToList();
    }

    /// <summary>This profile's place in a book.</summary>
    /// <param name="id">Book id.</param>
    /// <returns>The record; 204 when the book was never opened.</returns>
    [HttpGet("Reading/{id}")]
    public ActionResult<ReadingDto> GetReading([FromRoute] Guid id)
    {
        if (User.GetUser(_users) is not { } user)
        {
            return Unauthorized();
        }

        return _db.GetReading(user.Id.N(), id.N()) is { } rec ? ReadingDto.From(rec) : NoContent();
    }

    /// <summary>Saves this profile's place in a book, and marks it played in Jellyfin when finished.</summary>
    /// <param name="id">Book id.</param>
    /// <param name="body">The place.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The saved record.</returns>
    [HttpPost("Reading/{id}")]
    public ActionResult<ReadingDto> SaveReading([FromRoute] Guid id, [FromBody] ReadingDto body, CancellationToken ct)
    {
        if (User.GetUser(_users) is not { } user)
        {
            return Unauthorized();
        }

        if (_library.GetItemById(id) is not { } item || !item.IsVisible(user))
        {
            return NotFound();
        }

        var rec = new ReadingRecord
        {
            ItemId = id.N(),
            Locator = body.Locator ?? string.Empty,
            Percent = body.Percent,
            Chapter = body.Chapter,
            ChapterIndex = body.ChapterIndex,
            ChapterCount = body.ChapterCount,
            Finished = body.Finished,
            Settings = body.Settings is { ValueKind: JsonValueKind.Object } s ? s.GetRawText() : _db.GetReading(user.Id.N(), id.N())?.Settings
        };
        _db.SaveReading(user.Id.N(), rec);
        MarkPlayed(user, item, rec.Finished, ct);
        return ReadingDto.From(_db.GetReading(user.Id.N(), id.N()) ?? rec);
    }

    /// <summary>Forgets this profile's place in a book and marks it unplayed.</summary>
    /// <param name="id">Book id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>No content.</returns>
    [HttpDelete("Reading/{id}")]
    public ActionResult DeleteReading([FromRoute] Guid id, CancellationToken ct)
    {
        if (User.GetUser(_users) is not { } user)
        {
            return Unauthorized();
        }

        _db.DeleteReading(user.Id.N(), id.N());
        if (_library.GetItemById(id) is { } item && item.IsVisible(user))
        {
            MarkPlayed(user, item, false, ct);
        }

        return NoContent();
    }

    // Jellyfin's own "played" and "last played" follow the reader, so its filters and sorts work for books too.
    private void MarkPlayed(User user, BaseItem item, bool finished, CancellationToken ct)
    {
        if (_userData.GetUserData(user, item) is not { } data)
        {
            return;
        }

        data.Played = finished;
        data.LastPlayedDate = DateTime.UtcNow;
        _userData.SaveUserData(user, item, data, UserDataSaveReason.PlaybackProgress, ct);
    }

    private BaseItem? Book(Guid id)
    {
        if (User.GetUser(_users) is not { } user || _library.GetItemById(id) is not { } item || !item.IsVisible(user))
        {
            return null;
        }

        return item is Book && !string.IsNullOrEmpty(item.Path) && System.IO.File.Exists(item.Path) ? item : null;
    }
}

/// <summary>A profile's place in a book.</summary>
public sealed class ReadingDto
{
    /// <summary>Gets or sets the book id.</summary>
    public string ItemId { get; set; } = string.Empty;

    /// <summary>Gets or sets the reader's position: an EPUB CFI, or a page index for comics and PDFs.</summary>
    public string? Locator { get; set; }

    /// <summary>Gets or sets how far through, 0 to 100.</summary>
    public double Percent { get; set; }

    /// <summary>Gets or sets the chapter title.</summary>
    public string? Chapter { get; set; }

    /// <summary>Gets or sets the chapter, from 1.</summary>
    public int? ChapterIndex { get; set; }

    /// <summary>Gets or sets the number of chapters.</summary>
    public int? ChapterCount { get; set; }

    /// <summary>Gets or sets a value indicating whether the book was finished.</summary>
    public bool Finished { get; set; }

    /// <summary>Gets or sets the reader settings saved for this book (any JSON object); omit to keep them.</summary>
    public JsonElement? Settings { get; set; }

    /// <summary>Gets or sets when it was saved (UTC).</summary>
    public DateTime? UpdatedAt { get; set; }

    /// <summary>Converts a stored record.</summary>
    /// <param name="r">Record.</param>
    /// <returns>The DTO.</returns>
    public static ReadingDto From(ReadingRecord r) => new()
    {
        ItemId = r.ItemId,
        Locator = r.Locator,
        Percent = r.Percent,
        Chapter = r.Chapter,
        ChapterIndex = r.ChapterIndex,
        ChapterCount = r.ChapterCount,
        Finished = r.Finished,
        Settings = r.Settings is null ? null : JsonDocument.Parse(r.Settings).RootElement.Clone(),
        UpdatedAt = r.UpdatedAt
    };
}
