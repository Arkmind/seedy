using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Dto;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Audio;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Providers;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.IO;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services.Media;

/// <summary>An album on the server.</summary>
/// <param name="Id">Item id ("N").</param>
/// <param name="Name">Title.</param>
/// <param name="Artist">Album artist.</param>
/// <param name="ArtistMbid">MusicBrainz album artist id.</param>
/// <param name="ReleaseGroup">MusicBrainz release group id.</param>
public sealed record ServerAlbum(string Id, string Name, string? Artist, string? ArtistMbid, string? ReleaseGroup);

/// <summary>An artist on the server.</summary>
/// <param name="Id">Item id ("N").</param>
/// <param name="Name">Name.</param>
/// <param name="Mbid">MusicBrainz id.</param>
public sealed record ServerArtist(string Id, string Name, string Mbid);

/// <summary>A book, audiobook or comic on the server.</summary>
/// <param name="Id">Item id ("N").</param>
/// <param name="Name">Title.</param>
/// <param name="Format">ebook, audiobook or comic.</param>
/// <param name="Series">Series name.</param>
/// <param name="Index">Place in the series.</param>
public sealed record ServerBook(string Id, string Name, string Format, string? Series, int? Index);

/// <summary>What's on the server, for marking discover cards and finding what a finished request became, and the
/// libraries the request apps fill (to show where things land and to scan them when downloads finish).</summary>
public sealed class MediaLibrary
{
    private static readonly TimeSpan Ttl = TimeSpan.FromMinutes(2);
    private readonly ILibraryManager _library;
    private readonly IFileSystem _fs;
    private readonly ILogger<MediaLibrary> _logger;
    private readonly object _lock = new();
    private (DateTime At, List<ServerAlbum> Albums, List<ServerArtist> Artists)? _music;
    private (DateTime At, List<ServerBook> Books)? _books;

    /// <summary>
    /// Initializes a new instance of the <see cref="MediaLibrary"/> class.
    /// </summary>
    /// <param name="library">Library manager.</param>
    /// <param name="fs">File system.</param>
    /// <param name="logger">Logger.</param>
    public MediaLibrary(ILibraryManager library, IFileSystem fs, ILogger<MediaLibrary> logger)
    {
        _library = library;
        _fs = fs;
        _logger = logger;
    }

    /// <summary>Folds a title for matching: lowercase, no accents, punctuation or edition notes.</summary>
    /// <param name="s">Text.</param>
    /// <returns>Folded text.</returns>
    public static string Fold(string? s)
    {
        if (string.IsNullOrWhiteSpace(s))
        {
            return string.Empty;
        }

        var cut = s.IndexOfAny(['(', '[']);
        if (cut > 2)
        {
            s = s[..cut];
        }

        var sb = new StringBuilder();
        foreach (var ch in s.Normalize(NormalizationForm.FormD))
        {
            if (CharUnicodeInfo.GetUnicodeCategory(ch) == UnicodeCategory.NonSpacingMark)
            {
                continue;
            }

            if (char.IsLetterOrDigit(ch))
            {
                sb.Append(char.ToLowerInvariant(ch));
            }
            else if (ch == '&')
            {
                sb.Append("and");
            }
        }

        var folded = sb.ToString();
        return folded.StartsWith("the", StringComparison.Ordinal) && folded.Length > 6 ? folded[3..] : folded;
    }

    /// <summary>Forgets what was indexed, after a scan.</summary>
    public void Invalidate()
    {
        lock (_lock)
        {
            _music = null;
            _books = null;
        }
    }

    /// <summary>The server's albums and artists with MusicBrainz ids.</summary>
    /// <returns>Albums and artists.</returns>
    public (List<ServerAlbum> Albums, List<ServerArtist> Artists) Music()
    {
        lock (_lock)
        {
            if (_music is { } m && DateTime.UtcNow - m.At < Ttl)
            {
                return (m.Albums, m.Artists);
            }
        }

        var albums = _library.GetItemList(new InternalItemsQuery { IncludeItemTypes = [BaseItemKind.MusicAlbum], Recursive = true, DtoOptions = new DtoOptions(false) })
            .OfType<MusicAlbum>()
            .Select(a => new ServerAlbum(
                a.Id.ToString("N"),
                a.Name,
                a.AlbumArtists?.FirstOrDefault() ?? a.Artists?.FirstOrDefault(),
                Provider(a, "MusicBrainzAlbumArtist")?.Split('/')[0],
                Provider(a, "MusicBrainzReleaseGroup")))
            .ToList();
        var artists = _library.GetItemList(new InternalItemsQuery { IncludeItemTypes = [BaseItemKind.MusicArtist], Recursive = true, DtoOptions = new DtoOptions(false) })
            .Select(a => (a, mbid: Provider(a, "MusicBrainzArtist")))
            .Where(x => x.mbid is not null)
            .Select(x => new ServerArtist(x.a.Id.ToString("N"), x.a.Name, x.mbid!))
            .GroupBy(a => a.Mbid, StringComparer.OrdinalIgnoreCase).Select(g => g.First())
            .ToList();
        lock (_lock)
        {
            _music = (DateTime.UtcNow, albums, artists);
        }

        return (albums, artists);
    }

    private static string? Provider(BaseItem item, string key)
        => item.ProviderIds is not null && item.ProviderIds.TryGetValue(key, out var v) && !string.IsNullOrWhiteSpace(v) ? v : null;

    /// <summary>Finds an album on the server.</summary>
    /// <param name="rgid">MusicBrainz release group id.</param>
    /// <param name="title">Title.</param>
    /// <param name="artist">Artist name.</param>
    /// <returns>The album, or null.</returns>
    public ServerAlbum? FindAlbum(string? rgid, string? title, string? artist)
    {
        var (albums, _) = Music();
        if (rgid is not null && albums.FirstOrDefault(a => string.Equals(a.ReleaseGroup, rgid, StringComparison.OrdinalIgnoreCase)) is { } byId)
        {
            return byId;
        }

        var t = Fold(title);
        var ar = Fold(artist);
        return t.Length == 0 ? null : albums.FirstOrDefault(a => Fold(a.Name) == t && (ar.Length == 0 || Fold(a.Artist) == ar));
    }

    /// <summary>Finds an artist on the server by MusicBrainz id, else by name.</summary>
    /// <param name="mbid">MusicBrainz id.</param>
    /// <param name="name">Name.</param>
    /// <returns>The artist, or null.</returns>
    public ServerArtist? FindArtist(string? mbid, string? name)
    {
        var (_, artists) = Music();
        if (mbid is not null && artists.FirstOrDefault(a => string.Equals(a.Mbid, mbid, StringComparison.OrdinalIgnoreCase)) is { } byId)
        {
            return byId;
        }

        var n = Fold(name);
        return n.Length == 0 ? null : artists.FirstOrDefault(a => Fold(a.Name) == n);
    }

    /// <summary>Albums on the server by an artist.</summary>
    /// <param name="mbid">MusicBrainz artist id.</param>
    /// <param name="name">Artist name.</param>
    /// <returns>Albums.</returns>
    public List<ServerAlbum> AlbumsBy(string mbid, string? name)
    {
        var (albums, _) = Music();
        var n = Fold(name);
        return albums.Where(a => string.Equals(a.ArtistMbid, mbid, StringComparison.OrdinalIgnoreCase) || (a.ArtistMbid is null && n.Length > 0 && Fold(a.Artist) == n)).ToList();
    }

    /// <summary>Artists a person played lately, most recent first, with their MusicBrainz ids.</summary>
    /// <param name="user">The person.</param>
    /// <param name="limit">Most artists.</param>
    /// <returns>Artists.</returns>
    public List<ServerArtist> PlayedArtists(User user, int limit)
    {
        var (_, artists) = Music();
        var byName = artists.GroupBy(a => Fold(a.Name)).ToDictionary(g => g.Key, g => g.First());
        var played = _library.GetItemList(new InternalItemsQuery(user)
        {
            IncludeItemTypes = [BaseItemKind.Audio],
            IsPlayed = true,
            Recursive = true,
            OrderBy = [(ItemSortBy.DatePlayed, SortOrder.Descending)],
            Limit = 400,
            DtoOptions = new DtoOptions(false)
        }).OfType<Audio>();
        var list = new List<ServerArtist>();
        foreach (var track in played)
        {
            var name = track.AlbumArtists?.FirstOrDefault() ?? track.Artists?.FirstOrDefault();
            if (name is not null && byName.TryGetValue(Fold(name), out var artist) && !list.Contains(artist))
            {
                list.Add(artist);
                if (list.Count >= limit)
                {
                    break;
                }
            }
        }

        return list;
    }

    /// <summary>The server's books, audiobooks and comics.</summary>
    /// <returns>Books.</returns>
    public List<ServerBook> Books()
    {
        lock (_lock)
        {
            if (_books is { } b && DateTime.UtcNow - b.At < Ttl)
            {
                return b.Books;
            }
        }

        var list = _library.GetItemList(new InternalItemsQuery { IncludeItemTypes = [BaseItemKind.Book, BaseItemKind.AudioBook], Recursive = true, DtoOptions = new DtoOptions(false) })
            .Select(i => new ServerBook(i.Id.ToString("N"), i.Name, FormatOf(i), i is Book book ? book.SeriesName : null, i.IndexNumber))
            .ToList();
        lock (_lock)
        {
            _books = (DateTime.UtcNow, list);
        }

        return list;
    }

    /// <summary>ebook, audiobook or comic, from the item's type and file.</summary>
    /// <param name="item">Item.</param>
    /// <returns>Format.</returns>
    public static string FormatOf(BaseItem item)
    {
        if (item is MediaBrowser.Controller.Entities.AudioBook || item.MediaType == MediaType.Audio)
        {
            return "audiobook";
        }

        var ext = Path.GetExtension(item.Path ?? string.Empty).ToLowerInvariant();
        return ext is ".cbz" or ".cbr" or ".cb7" or ".cbt" ? "comic" : "ebook";
    }

    /// <summary>Finds a book on the server by title (and series place when given).</summary>
    /// <param name="title">Title.</param>
    /// <param name="series">Series.</param>
    /// <param name="index">Place in the series.</param>
    /// <returns>Matching items, any format.</returns>
    public List<ServerBook> FindBooks(string? title, string? series, double? index)
    {
        var t = Fold(title);
        var s = Fold(series);
        return Books().Where(b =>
            (t.Length > 0 && Fold(b.Name) == t)
            || (s.Length > 0 && index is not null && b.Index is not null && Fold(b.Series) == s && Math.Abs(b.Index.Value - index.Value) < 0.01)).ToList();
    }

    // ── Libraries and folders ──

    /// <summary>The server's libraries: name, folders and kind.</summary>
    /// <returns>Libraries.</returns>
    public List<(string Id, string Name, string[] Locations, string? Type)> Libraries()
        => _library.GetVirtualFolders()
            .Select(v => (v.ItemId ?? string.Empty, v.Name ?? string.Empty, v.Locations ?? [], v.CollectionType?.ToString()?.ToLowerInvariant()))
            .Where(v => v.Item1.Length > 0)
            .ToList();

    /// <summary>The library whose folder matches an app's folder: the same path, a folder inside it, or one with the same
    /// last name (apps and Jellyfin often see one folder at different paths).</summary>
    /// <param name="path">The app's folder.</param>
    /// <returns>Library id, name and its matching folder, or null.</returns>
    public (string Id, string Name, string Location)? LibraryForPath(string? path)
    {
        if (string.IsNullOrWhiteSpace(path))
        {
            return null;
        }

        var p = path.TrimEnd('/', '\\');
        var libs = Libraries();
        foreach (var l in libs)
        {
            foreach (var loc in l.Locations)
            {
                var lo = loc.TrimEnd('/', '\\');
                if (string.Equals(lo, p, StringComparison.OrdinalIgnoreCase) || p.StartsWith(lo + "/", StringComparison.OrdinalIgnoreCase))
                {
                    return (l.Id, l.Name, loc);
                }
            }
        }

        var last = Path.GetFileName(p);
        foreach (var l in libs)
        {
            var loc = l.Locations.FirstOrDefault(lo => string.Equals(Path.GetFileName(lo.TrimEnd('/', '\\')), last, StringComparison.OrdinalIgnoreCase));
            if (loc is not null)
            {
                return (l.Id, l.Name, loc);
            }
        }

        return null;
    }

    /// <summary>The library a format most likely lands in: the music library for music; for books the books library
    /// named for audiobooks, comics or plain books.</summary>
    /// <param name="format">music, ebook, audiobook or comic.</param>
    /// <returns>Library id, name and folder, or null.</returns>
    public (string Id, string Name, string Location)? LibraryForFormat(string format)
    {
        var libs = Libraries();
        static bool Named(string name, string loc, params string[] words)
            => words.Any(w => name.Contains(w, StringComparison.OrdinalIgnoreCase) || Path.GetFileName(loc.TrimEnd('/', '\\')).Contains(w, StringComparison.OrdinalIgnoreCase));

        // Every folder of every library of a kind: one Books library often holds the books, audiobooks and comics folders.
        List<(string Id, string Name, string Location)> Folders(string type) => libs.Where(l => l.Type == type).SelectMany(l => l.Locations.Select(loc => (l.Id, l.Name, loc))).ToList();
        if (format == "music")
        {
            var music = Folders("music").Where(m => !Named(m.Name, m.Location, "audiobook")).ToList();
            return music.Count > 0 ? music[0] : null;
        }

        var books = Folders("books");
        var pick = format switch
        {
            "audiobook" => books.FirstOrDefault(b => Named(b.Name, b.Location, "audio")),
            "comic" => books.FirstOrDefault(b => Named(b.Name, b.Location, "comic", "manga")),
            _ => books.FirstOrDefault(b => !Named(b.Name, b.Location, "audio", "comic", "manga"))
        };
        if (pick.Id is null && format == "audiobook")
        {
            pick = Folders("music").FirstOrDefault(m => Named(m.Name, m.Location, "audio"));
        }

        if (pick.Id is null && books.Count > 0)
        {
            pick = books[0];
        }

        return pick.Id is null ? null : pick;
    }

    /// <summary>Scans one library for new files.</summary>
    /// <param name="libraryId">Library (collection folder) id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>A task.</returns>
    public async Task ScanAsync(string libraryId, CancellationToken ct)
    {
        if (!Guid.TryParse(libraryId, out var id) || _library.GetItemById(id) is not Folder folder)
        {
            return;
        }

        _logger.LogInformation("Seedy is scanning {Library} for a finished request", folder.Name);
        await folder.ValidateChildren(new Progress<double>(), new MetadataRefreshOptions(new DirectoryService(_fs)), true, false, ct).ConfigureAwait(false);
        Invalidate();
    }
}
