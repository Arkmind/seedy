using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Plugin.Seedy.Data;
using MediaBrowser.Controller.Dto;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Library;
using MediaBrowser.Model.Dto;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.Querying;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>An audiobook that comes as a folder of files.</summary>
/// <param name="Folder">The book's folder (above any CD 1, CD 2… folders).</param>
/// <param name="Parts">The files, in listening order.</param>
/// <param name="Name">The album tag the files share, else the folder's name.</param>
public sealed record AudiobookGroup(Folder Folder, IReadOnlyList<BaseItem> Parts, string Name);

/// <summary>
/// Audiobooks in several files (an MP3 per chapter or per CD). Jellyfin makes each file an audiobook of its own; Seedy
/// shows the folder as one book, its files played one after the other, with one chapter per file (or the files' own
/// chapters). Files belong together when they sit in the same folder, below a library's own folders (CD folders count
/// as their parent), and share their album tag. Untagged M4B files stay books of their own: one M4B is one book.
/// </summary>
public sealed partial class Audiobooks
{
    private readonly ILibraryManager _library;
    private readonly IDtoService _dto;
    private readonly SeedyDatabase _db;

    /// <summary>Initializes a new instance of the <see cref="Audiobooks"/> class.</summary>
    /// <param name="library">Library manager.</param>
    /// <param name="dto">DTO service.</param>
    /// <param name="db">Seedy database (the places the player saves).</param>
    public Audiobooks(ILibraryManager library, IDtoService dto, SeedyDatabase db)
    {
        _library = library;
        _dto = dto;
        _db = db;
    }

    /// <summary>The books in several files among these audiobook files.</summary>
    /// <param name="files">Audiobook items.</param>
    /// <returns>One group per book; files left out are books of their own.</returns>
    public List<AudiobookGroup> Group(IEnumerable<BaseItem> files)
    {
        var roots = _library.GetVirtualFolders().SelectMany(v => v.Locations ?? []).Select(Trim).ToHashSet(StringComparer.OrdinalIgnoreCase);
        var list = new List<AudiobookGroup>();
        foreach (var byFolder in files.Where(f => f is AudioBook && !string.IsNullOrEmpty(f.Path)).GroupBy(f => BookFolder(f.Path), StringComparer.OrdinalIgnoreCase))
        {
            var parts = byFolder.ToList();
            if (parts.Count < 2 || roots.Contains(byFolder.Key))
            {
                continue;
            }

            var albums = parts.Select(p => AlbumKey(Album(p))).Distinct().ToList();
            if (albums.Count > 1 || (albums[0].Length == 0 && parts.All(p => string.Equals(Path.GetExtension(p.Path), ".m4b", StringComparison.OrdinalIgnoreCase))))
            {
                continue;
            }

            if (_library.FindByPath(byFolder.Key, true) is not Folder folder)
            {
                continue;
            }

            var album = parts.Select(Album).FirstOrDefault(a => a.Length > 0);
            var name = album is null ? folder.Name : DiscSuffix().Replace(album, string.Empty).Trim();
            list.Add(new AudiobookGroup(folder, Order(parts), name.Length > 0 ? name : folder.Name));
        }

        return list;
    }

    /// <summary>The book in several files that this folder is, or that this file is part of.</summary>
    /// <param name="item">The book's folder, or one of its files.</param>
    /// <param name="user">Who's asking (only what they can see counts).</param>
    /// <returns>The book, or null for a book in one file (or anything else).</returns>
    public AudiobookGroup? Find(BaseItem item, User user)
    {
        var path = item switch
        {
            AudioBook when !string.IsNullOrEmpty(item.Path) => BookFolder(item.Path),
            Folder when !string.IsNullOrEmpty(item.Path) => IsDiscFolder(item.Path) ? Trim(Path.GetDirectoryName(Trim(item.Path))) : Trim(item.Path),
            _ => null
        };
        if (path is null || _library.FindByPath(path, true) is not Folder folder)
        {
            return null;
        }

        var files = _library.GetItemList(new InternalItemsQuery(user)
        {
            ParentId = folder.Id,
            Recursive = true,
            IncludeItemTypes = [BaseItemKind.AudioBook],
            DtoOptions = new DtoOptions(false)
        });
        return Group(files).FirstOrDefault(g => g.Folder.Id == folder.Id);
    }

    /// <summary>
    /// The book as one audiobook: the first file's details under the folder's id and name, the files' lengths added up,
    /// their chapters one after the other, the folder's cover (else the first file's), and the profile's place across
    /// the files: the one Seedy's player saved, else the file played last and how far into it.
    /// </summary>
    /// <param name="g">The book.</param>
    /// <param name="user">Profile.</param>
    /// <returns>The book and its files.</returns>
    public AudiobookDto ToDto(AudiobookGroup g, User user)
    {
        // Every field for the first file (the book's details come from it), lengths, chapters and places for the rest.
        var first = _dto.GetBaseItemDto(g.Parts[0], new DtoOptions(true) { EnableUserData = true, ImageTypeLimit = 1 }, user);
        var light = new DtoOptions(false) { Fields = [ItemFields.Chapters], EnableImages = true, ImageTypes = [ImageType.Primary], ImageTypeLimit = 1, EnableUserData = true };
        var parts = new List<BaseItemDto> { first };
        parts.AddRange(_dto.GetBaseItemDtos(g.Parts.Skip(1).ToList(), light, user));
        var folder = _dto.GetBaseItemDto(g.Folder, new DtoOptions(false) { EnableImages = true, ImageTypes = [ImageType.Primary, ImageType.Backdrop], ImageTypeLimit = 1, EnableUserData = true }, user);

        long at = 0, position = 0;
        DateTime? last = null;
        var all = true;
        var chapters = new List<ChapterInfo>();
        var slim = new List<AudiobookPartDto>();
        for (var i = 0; i < parts.Count; i++)
        {
            var p = parts[i];
            var length = p.RunTimeTicks ?? 0;
            var own = p.Chapters ?? [];
            if (own.Count > 1)
            {
                chapters.AddRange(own.Select(c => new ChapterInfo { Name = c.Name, StartPositionTicks = at + c.StartPositionTicks }));
            }
            else
            {
                chapters.Add(new ChapterInfo { Name = p.Name, StartPositionTicks = at });
            }

            slim.Add(new AudiobookPartDto { Id = p.Id, Name = p.Name ?? string.Empty, Start = at, RunTimeTicks = length, Size = g.Parts[i].Size });

            // The place is in the file played last, unless that file was only reset (marked unplayed).
            var ud = p.UserData;
            if (ud?.LastPlayedDate is { } played && (ud.Played || ud.PlaybackPositionTicks > 0) && (last is null || played >= last))
            {
                last = played;
                position = at + (ud.Played ? length : ud.PlaybackPositionTicks);
            }

            all &= ud?.Played == true;
            at += length;
        }

        var book = first;
        book.Id = g.Folder.Id;
        book.Name = g.Name;
        book.SortName = g.Folder.SortName;
        book.ParentId = g.Folder.ParentId;
        book.Path = g.Folder.Path;
        book.RunTimeTicks = at;
        book.DateCreated = g.Parts.Max(p => p.DateCreated);
        book.IndexNumber = null;
        book.ParentIndexNumber = null;
        book.Chapters = chapters;
        book.MediaSources = null;
        book.Container = Path.GetExtension(g.Parts[0].Path).TrimStart('.').ToLowerInvariant();
        book.CanDownload = false;
        book.Etag = null;

        // The folder's cover.jpg or folder.jpg, else the first file's own picture.
        book.ImageTags = folder.ImageTags;
        book.BackdropImageTags = folder.BackdropImageTags;
        book.ParentPrimaryImageItemId = null;
        book.ParentPrimaryImageTag = null;
        if (folder.ImageTags?.ContainsKey(ImageType.Primary) == true)
        {
            book.PrimaryImageAspectRatio = folder.PrimaryImageAspectRatio;
        }
        else if (parts.FirstOrDefault(p => p.ImageTags?.ContainsKey(ImageType.Primary) == true) is { } pic)
        {
            book.ParentPrimaryImageItemId = pic.Id;
            book.ParentPrimaryImageTag = pic.ImageTags![ImageType.Primary];
            book.PrimaryImageAspectRatio = pic.PrimaryImageAspectRatio;
        }

        var done = all || (at > 0 && position >= at);

        // Seedy's player saves its own place in the book (under the folder's id), as Jellyfin keeps no place in a file's
        // first or last five minutes: in a book of short files, that's most of it. It wins unless another app played the
        // book more than a minute after it was saved (Seedy's own reports to Jellyfin land around the same time).
        if (_db.GetReading(user.Id.N(), g.Folder.Id.N()) is { } place && (last is null || place.UpdatedAt >= last.Value.AddMinutes(-1))
            && double.TryParse(place.Locator, NumberStyles.Float, CultureInfo.InvariantCulture, out var seconds))
        {
            last = place.UpdatedAt;
            position = Math.Clamp((long)(seconds * TimeSpan.TicksPerSecond), 0, at);
            done = place.Finished;
        }
        book.UserData = new UserItemDataDto
        {
            Key = g.Folder.Id.ToString("N"),
            ItemId = g.Folder.Id,
            Played = done,
            PlaybackPositionTicks = done ? 0 : position,
            PlayedPercentage = done || position == 0 ? null : position * 100.0 / at,
            LastPlayedDate = last,
            PlayCount = done ? Math.Max(1, parts.Min(p => p.UserData?.PlayCount ?? 0)) : 0,
            IsFavorite = folder.UserData?.IsFavorite ?? false
        };

        return new AudiobookDto { Item = book, Parts = slim };
    }

    /// <summary>Where a file's book lives: its folder, or the folder above a CD 1, Disc 2… folder.</summary>
    private static string BookFolder(string path)
    {
        var dir = Trim(Path.GetDirectoryName(path));
        return IsDiscFolder(dir) ? Trim(Path.GetDirectoryName(dir)) : dir;
    }

    private static bool IsDiscFolder(string path) => DiscFolder().IsMatch(Path.GetFileName(Trim(path)));

    private static int Disc(BaseItem item)
    {
        var dir = Path.GetFileName(Trim(Path.GetDirectoryName(item.Path)));
        var m = DiscFolder().Match(dir);
        return m.Success ? int.Parse(m.Groups[1].Value, System.Globalization.CultureInfo.InvariantCulture) : item.ParentIndexNumber ?? 0;
    }

    private static List<BaseItem> Order(List<BaseItem> parts)
        => parts.OrderBy(Disc).ThenBy(p => p.IndexNumber ?? 0).ThenBy(p => Path.GetFileName(p.Path), Natural.Instance).ToList();

    private static string Album(BaseItem item) => (item as MediaBrowser.Controller.Entities.Audio.Audio)?.Album?.Trim() ?? string.Empty;

    // "Title (Disc 2)" and "Title" are the same book.
    private static string AlbumKey(string album) => DiscSuffix().Replace(album, string.Empty).Trim().ToLowerInvariant();

    private static string Trim(string? path) => (path ?? string.Empty).TrimEnd('/', '\\');

    [GeneratedRegex(@"^(?:cd|disc|disk)\s*[-_.]?\s*(\d+)$", RegexOptions.IgnoreCase)]
    private static partial Regex DiscFolder();

    [GeneratedRegex(@"\s*[\(\[]?\s*(?:cd|disc|disk)\s*\d+\s*[\)\]]?\s*$", RegexOptions.IgnoreCase)]
    private static partial Regex DiscSuffix();

    /// <summary>File names in the order people number them: "Chapter 2" before "Chapter 10".</summary>
    private sealed class Natural : IComparer<string?>
    {
        public static readonly Natural Instance = new();

        public int Compare(string? x, string? y)
        {
            x ??= string.Empty;
            y ??= string.Empty;
            int i = 0, j = 0;
            while (i < x.Length && j < y.Length)
            {
                if (char.IsDigit(x[i]) && char.IsDigit(y[j]))
                {
                    int si = i, sj = j;
                    while (i < x.Length && char.IsDigit(x[i]))
                    {
                        i++;
                    }

                    while (j < y.Length && char.IsDigit(y[j]))
                    {
                        j++;
                    }

                    var a = x[si..i].TrimStart('0');
                    var b = y[sj..j].TrimStart('0');
                    var c = a.Length != b.Length ? a.Length.CompareTo(b.Length) : string.CompareOrdinal(a, b);
                    if (c != 0)
                    {
                        return c;
                    }
                }
                else
                {
                    var c = char.ToLowerInvariant(x[i]).CompareTo(char.ToLowerInvariant(y[j]));
                    if (c != 0)
                    {
                        return c;
                    }

                    i++;
                    j++;
                }
            }

            return (x.Length - i).CompareTo(y.Length - j);
        }
    }
}

/// <summary>An audiobook in several files: the book as one Jellyfin item, and its files.</summary>
public sealed class AudiobookDto
{
    /// <summary>Gets or sets the book (an AudioBook item with the folder's id).</summary>
    public BaseItemDto Item { get; set; } = new();

    /// <summary>Gets or sets the files, in listening order.</summary>
    public List<AudiobookPartDto> Parts { get; set; } = [];
}

/// <summary>One file of an audiobook in several files.</summary>
public sealed class AudiobookPartDto
{
    /// <summary>Gets or sets the file's item id.</summary>
    public Guid Id { get; set; }

    /// <summary>Gets or sets its name (title tag or file name).</summary>
    public string Name { get; set; } = string.Empty;

    /// <summary>Gets or sets where it starts in the book, in ticks.</summary>
    public long Start { get; set; }

    /// <summary>Gets or sets its length, in ticks.</summary>
    public long RunTimeTicks { get; set; }

    /// <summary>Gets or sets its size in bytes, when known.</summary>
    public long? Size { get; set; }
}
