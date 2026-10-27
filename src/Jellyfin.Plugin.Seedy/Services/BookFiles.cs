using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Xml.Linq;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>What Seedy learns by opening a book file: its length in pages and, for EPUBs, its chapters.</summary>
public sealed class BookFileInfo
{
    /// <summary>Gets or sets the format: epub, pdf, cbz, cbr or other.</summary>
    public string Format { get; set; } = "other";

    /// <summary>Gets or sets the length in pages. EPUB pages are 1600 characters (what the reader counts as a page).</summary>
    public int Pages { get; set; }

    /// <summary>Gets or sets the number of words (EPUB only).</summary>
    public int Words { get; set; }

    /// <summary>Gets or sets the reading time in minutes at 250 words a minute (EPUB only).</summary>
    public int Minutes { get; set; }

    /// <summary>Gets or sets the table of contents (EPUB only).</summary>
    public List<BookChapter> Chapters { get; set; } = [];
}

/// <summary>One entry of an EPUB's table of contents.</summary>
public sealed class BookChapter
{
    /// <summary>Gets or sets the title.</summary>
    public string Title { get; set; } = string.Empty;

    /// <summary>Gets or sets the target, relative to the EPUB's package document, with any #fragment.</summary>
    public string Href { get; set; } = string.Empty;

    /// <summary>Gets or sets the first page of the chapter (from 1), counted like <see cref="BookFileInfo.Pages"/>.</summary>
    public int Page { get; set; }
}

/// <summary>
/// Reads book files for the reader: EPUB length and chapters, comic book pages (CBZ), PDF page counts.
/// Results are cached per file and modification time.
/// </summary>
public sealed partial class BookFiles
{
    /// <summary>Characters per page; the web reader generates its locations with the same size.</summary>
    public const int CharsPerPage = 1600;

    private static readonly string[] ImageExtensions = [".jpg", ".jpeg", ".png", ".webp", ".gif", ".avif"];
    private readonly ConcurrentDictionary<string, (DateTime Modified, object Value)> _cache = new(StringComparer.Ordinal);
    private readonly ILogger<BookFiles> _logger;

    /// <summary>
    /// Initializes a new instance of the <see cref="BookFiles"/> class.
    /// </summary>
    /// <param name="logger">Logger.</param>
    public BookFiles(ILogger<BookFiles> logger)
    {
        _logger = logger;
    }

    /// <summary>Gets the format of a book file from its extension.</summary>
    /// <param name="path">File path.</param>
    /// <returns>epub, pdf, cbz, cbr or other.</returns>
    public static string FormatOf(string path) => Path.GetExtension(path).ToLowerInvariant() switch
    {
        ".epub" => "epub",
        ".pdf" => "pdf",
        ".cbz" => "cbz",
        ".cbr" => "cbr",
        _ => "other"
    };

    /// <summary>Gets the MIME type to serve a book file with.</summary>
    /// <param name="path">File path.</param>
    /// <returns>MIME type.</returns>
    public static string MimeOf(string path) => FormatOf(path) switch
    {
        "epub" => "application/epub+zip",
        "pdf" => "application/pdf",
        "cbz" => "application/vnd.comicbook+zip",
        "cbr" => "application/vnd.comicbook-rar",
        _ => "application/octet-stream"
    };

    /// <summary>Reads a book file's length and chapters.</summary>
    /// <param name="path">File path.</param>
    /// <returns>The info; Pages is 0 when the file can't be read.</returns>
    public BookFileInfo Info(string path) => Cached(path, "info", () =>
    {
        var info = new BookFileInfo { Format = FormatOf(path) };
        try
        {
            switch (info.Format)
            {
                case "epub": ReadEpub(path, info); break;
                case "cbz": info.Pages = ComicPages(path).Count; break;
                case "pdf": info.Pages = PdfPages(path); break;
            }
        }
        catch (Exception ex) when (ex is IOException or InvalidDataException or System.Xml.XmlException or UnauthorizedAccessException)
        {
            _logger.LogWarning(ex, "Seedy couldn't read the book {Path}", path);
        }

        return info;
    });

    /// <summary>Lists a CBZ's page images in reading order.</summary>
    /// <param name="path">File path.</param>
    /// <returns>Entry names.</returns>
    public List<string> ComicPages(string path) => Cached(path, "pages", () =>
    {
        using var zip = ZipFile.OpenRead(path);
        return zip.Entries
            .Where(e => e.Length > 0 && ImageExtensions.Contains(Path.GetExtension(e.FullName).ToLowerInvariant())
                && !e.FullName.StartsWith("__MACOSX", StringComparison.Ordinal))
            .Select(e => e.FullName)
            .OrderBy(n => n, NaturalComparer.Instance)
            .ToList();
    });

    /// <summary>Copies one page of a CBZ.</summary>
    /// <param name="path">File path.</param>
    /// <param name="index">Page, from 0.</param>
    /// <returns>The image bytes and MIME type, or null when there is no such page.</returns>
    public (byte[] Bytes, string Mime)? ComicPage(string path, int index)
    {
        var pages = ComicPages(path);
        if (index < 0 || index >= pages.Count)
        {
            return null;
        }

        using var zip = ZipFile.OpenRead(path);
        var entry = zip.GetEntry(pages[index]);
        if (entry is null)
        {
            return null;
        }

        using var s = entry.Open();
        using var ms = new MemoryStream();
        s.CopyTo(ms);
        var mime = Path.GetExtension(entry.FullName).ToLowerInvariant() switch
        {
            ".png" => "image/png",
            ".webp" => "image/webp",
            ".gif" => "image/gif",
            ".avif" => "image/avif",
            _ => "image/jpeg"
        };
        return (ms.ToArray(), mime);
    }

    private T Cached<T>(string path, string kind, Func<T> read)
        where T : class
    {
        var modified = File.GetLastWriteTimeUtc(path);
        var key = kind + "|" + path;
        if (_cache.TryGetValue(key, out var hit) && hit.Modified == modified && hit.Value is T value)
        {
            return value;
        }

        var fresh = read();
        _cache[key] = (modified, fresh);
        return fresh;
    }

    private static void ReadEpub(string path, BookFileInfo info)
    {
        using var zip = ZipFile.OpenRead(path);
        var container = LoadXml(zip, "META-INF/container.xml");
        var opfPath = container?.Descendants().FirstOrDefault(e => e.Name.LocalName == "rootfile")?.Attribute("full-path")?.Value;
        if (opfPath is null || LoadXml(zip, opfPath) is not { } opf)
        {
            return;
        }

        var opfDir = opfPath.Contains('/', StringComparison.Ordinal) ? opfPath[..(opfPath.LastIndexOf('/') + 1)] : string.Empty;
        var manifest = opf.Descendants().Where(e => e.Name.LocalName == "item")
            .Select(e => (Id: e.Attribute("id")?.Value, Href: e.Attribute("href")?.Value, Props: e.Attribute("properties")?.Value ?? string.Empty, Type: e.Attribute("media-type")?.Value))
            .Where(i => i.Id is not null && i.Href is not null)
            .GroupBy(i => i.Id!)
            .ToDictionary(g => g.Key, g => g.First(), StringComparer.Ordinal);

        // Pages per spine document, counted the way the reader counts them (a page every 1600 characters of text).
        var firstPage = new Dictionary<string, int>(StringComparer.Ordinal);
        var pages = 0;
        var words = 0;
        foreach (var idref in opf.Descendants().Where(e => e.Name.LocalName == "itemref").Select(e => e.Attribute("idref")?.Value))
        {
            if (idref is null || !manifest.TryGetValue(idref, out var item))
            {
                continue;
            }

            var href = Uri.UnescapeDataString(item.Href!);
            var text = PlainText(ReadEntry(zip, opfDir + href) ?? string.Empty);
            firstPage[href] = pages + 1;
            pages += Math.Max(1, (int)Math.Ceiling(text.Length / (double)CharsPerPage));
            words += WordRegex().Matches(text).Count;
        }

        info.Pages = pages;
        info.Words = words;
        info.Minutes = (int)Math.Round(words / 250.0);

        // EPUB 3 navigation document first, then the EPUB 2 NCX.
        var nav = manifest.Values.FirstOrDefault(i => i.Props.Split(' ').Contains("nav"));
        if (nav.Href is not null && LoadXml(zip, opfDir + Uri.UnescapeDataString(nav.Href)) is { } navDoc)
        {
            var navDir = DirOf(nav.Href);
            var toc = navDoc.Descendants().FirstOrDefault(e => e.Name.LocalName == "nav"
                && e.Attributes().Any(a => a.Name.LocalName == "type" && a.Value == "toc"))
                ?? navDoc.Descendants().FirstOrDefault(e => e.Name.LocalName == "nav");
            foreach (var a in toc?.Descendants().Where(e => e.Name.LocalName == "a") ?? [])
            {
                AddChapter(info, firstPage, Join(navDir, a.Attribute("href")?.Value), a.Value);
            }
        }

        if (info.Chapters.Count == 0)
        {
            var ncx = manifest.Values.FirstOrDefault(i => i.Type == "application/x-dtbncx+xml");
            if (ncx.Href is not null && LoadXml(zip, opfDir + Uri.UnescapeDataString(ncx.Href)) is { } ncxDoc)
            {
                var ncxDir = DirOf(ncx.Href);
                foreach (var point in ncxDoc.Descendants().Where(e => e.Name.LocalName == "navPoint"))
                {
                    var label = point.Elements().FirstOrDefault(e => e.Name.LocalName == "navLabel")?.Value;
                    var src = point.Elements().FirstOrDefault(e => e.Name.LocalName == "content")?.Attribute("src")?.Value;
                    AddChapter(info, firstPage, Join(ncxDir, src), label);
                }
            }
        }
    }

    private static void AddChapter(BookFileInfo info, Dictionary<string, int> firstPage, string? href, string? title)
    {
        if (string.IsNullOrWhiteSpace(href) || string.IsNullOrWhiteSpace(title))
        {
            return;
        }

        var file = Uri.UnescapeDataString(href.Split('#')[0]);
        info.Chapters.Add(new BookChapter
        {
            Title = WhitespaceRegex().Replace(title, " ").Trim(),
            Href = href,
            Page = firstPage.TryGetValue(file, out var page) ? page : 0
        });
    }

    private static string DirOf(string href) => href.Contains('/', StringComparison.Ordinal) ? href[..(href.LastIndexOf('/') + 1)] : string.Empty;

    // Resolves a link inside a navigation file against that file's folder, giving a path relative to the package.
    private static string? Join(string dir, string? href)
    {
        if (string.IsNullOrEmpty(href))
        {
            return null;
        }

        var parts = new List<string>();
        foreach (var part in (dir + href).Split('/'))
        {
            if (part == "..")
            {
                if (parts.Count > 0)
                {
                    parts.RemoveAt(parts.Count - 1);
                }
            }
            else if (part.Length > 0 && part != ".")
            {
                parts.Add(part);
            }
        }

        return string.Join('/', parts);
    }

    private static XDocument? LoadXml(ZipArchive zip, string name)
    {
        var text = ReadEntry(zip, name);
        if (text is null)
        {
            return null;
        }

        try
        {
            return XDocument.Parse(text);
        }
        catch (System.Xml.XmlException)
        {
            return null;
        }
    }

    private static string? ReadEntry(ZipArchive zip, string name)
    {
        var entry = zip.GetEntry(name) ?? zip.Entries.FirstOrDefault(e => string.Equals(e.FullName, name, StringComparison.OrdinalIgnoreCase));
        if (entry is null)
        {
            return null;
        }

        using var reader = new StreamReader(entry.Open(), Encoding.UTF8);
        return reader.ReadToEnd();
    }

    private static string PlainText(string html)
    {
        var body = BodyRegex().Match(html);
        var text = TagRegex().Replace(body.Success ? body.Groups[1].Value : html, " ");
        text = System.Net.WebUtility.HtmlDecode(text);
        return WhitespaceRegex().Replace(text, " ").Trim();
    }

    // Counts "/Type /Page" objects; good enough for the page count the details page shows.
    private static int PdfPages(string path)
    {
        var text = Encoding.Latin1.GetString(File.ReadAllBytes(path));
        return PdfPageRegex().Matches(text).Count;
    }

    [GeneratedRegex(@"<body[^>]*>(.*)</body>", RegexOptions.Singleline | RegexOptions.IgnoreCase)]
    private static partial Regex BodyRegex();

    [GeneratedRegex(@"<[^>]+>")]
    private static partial Regex TagRegex();

    [GeneratedRegex(@"\s+")]
    private static partial Regex WhitespaceRegex();

    [GeneratedRegex(@"\w+")]
    private static partial Regex WordRegex();

    [GeneratedRegex(@"/Type\s*/Page(?![a-zA-Z])")]
    private static partial Regex PdfPageRegex();

    // Orders "page2.jpg" before "page10.jpg".
    private sealed partial class NaturalComparer : IComparer<string>
    {
        public static readonly NaturalComparer Instance = new();

        public int Compare(string? x, string? y)
        {
            if (x is null || y is null)
            {
                return string.CompareOrdinal(x, y);
            }

            var a = DigitsRegex().Split(x);
            var b = DigitsRegex().Split(y);
            for (var i = 0; i < Math.Min(a.Length, b.Length); i++)
            {
                if (a[i] == b[i])
                {
                    continue;
                }

                if (long.TryParse(a[i], NumberStyles.None, CultureInfo.InvariantCulture, out var na)
                    && long.TryParse(b[i], NumberStyles.None, CultureInfo.InvariantCulture, out var nb))
                {
                    return na.CompareTo(nb);
                }

                return string.Compare(a[i], b[i], StringComparison.OrdinalIgnoreCase);
            }

            return a.Length.CompareTo(b.Length);
        }

        [GeneratedRegex(@"(\d+)")]
        private static partial Regex DigitsRegex();
    }
}
