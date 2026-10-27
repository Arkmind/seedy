using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net.Http;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Seedy.Api;

namespace Jellyfin.Plugin.Seedy.Services.Media;

/// <summary>Open Library's public API: what's trending this week, book search and a work's description. Used for
/// Trending, and for search when Shelfarr isn't connected.</summary>
public sealed class OpenLibraryClient
{
    private const string Name = "Open Library";
    private const string Fields = "key,title,author_name,first_publish_year,cover_i,number_of_pages_median,language,ratings_average";
    private readonly ServiceHttp _http;

    /// <summary>
    /// Initializes a new instance of the <see cref="OpenLibraryClient"/> class.
    /// </summary>
    /// <param name="http">HTTP helper.</param>
    public OpenLibraryClient(ServiceHttp http)
    {
        _http = http;
    }

    private Task<JsonNode?> Get(string path, TimeSpan cache, CancellationToken ct)
        => _http.SendAsync(Name, HttpMethod.Get, "https://openlibrary.org" + path, null, null, ct, cache, TimeSpan.FromSeconds(20));

    /// <summary>The books people open most on Open Library this week.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Books.</returns>
    public async Task<List<BookCardDto>> TrendingAsync(CancellationToken ct)
        => J.Arr((await Get("/trending/weekly.json?limit=40", TimeSpan.FromHours(6), ct).ConfigureAwait(false))?["works"]).Select(Card).OfType<BookCardDto>().ToList();

    /// <summary>Searches Open Library.</summary>
    /// <param name="query">Text.</param>
    /// <param name="ct">Cancellation.</param>
    /// <param name="limit">Most results.</param>
    /// <returns>Books.</returns>
    public async Task<List<BookCardDto>> SearchAsync(string query, CancellationToken ct, int limit = 24)
        => J.Arr((await Get("/search.json?limit=" + limit.ToString(CultureInfo.InvariantCulture) + "&fields=" + Fields + "&q=" + Uri.EscapeDataString(query), TimeSpan.FromHours(6), ct).ConfigureAwait(false))?["docs"])
            .Select(Card).OfType<BookCardDto>().ToList();

    /// <summary>A work's description.</summary>
    /// <param name="workId">"openlibrary:OL…W".</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Description, or null.</returns>
    public async Task<string?> DescriptionAsync(string workId, CancellationToken ct)
    {
        var olid = workId.StartsWith("openlibrary:", StringComparison.Ordinal) ? workId["openlibrary:".Length..] : null;
        if (olid is null || !olid.EndsWith('W'))
        {
            return null;
        }

        var w = await Get("/works/" + olid + ".json", TimeSpan.FromDays(3), ct).ConfigureAwait(false);
        var d = w?["description"];
        var text = d is JsonObject o ? J.Str(o["value"]) : J.Str(d);
        if (text is null)
        {
            return null;
        }

        // Descriptions often end with source notes and links; keep the first paragraph.
        var cut = text.IndexOf("\n", StringComparison.Ordinal);
        text = (cut > 40 ? text[..cut] : text).Trim();
        return text.Length > 420 ? text[..text.LastIndexOf(' ', 417)] + "…" : text;
    }

    private static BookCardDto? Card(JsonNode? w)
    {
        var key = J.Str(w?["key"]);
        var title = J.Str(w?["title"]);
        if (key is null || title is null)
        {
            return null;
        }

        var cover = J.Long(w?["cover_i"]);
        return new BookCardDto
        {
            WorkId = "openlibrary:" + key.Replace("/works/", string.Empty, StringComparison.Ordinal),
            Title = title,
            Author = J.Arr(w?["author_name"]).Select(J.Str).FirstOrDefault(a => a is not null),
            Year = J.Int(w?["first_publish_year"]),
            CoverUrl = cover is > 0 ? "https://covers.openlibrary.org/b/id/" + cover.Value.ToString(CultureInfo.InvariantCulture) + "-M.jpg" : null,
            Pages = J.Int(w?["number_of_pages_median"]),
            Rating = J.Double(w?["ratings_average"]) is { } r ? Math.Round(r, 1) : null,
            Languages = J.Arr(w?["language"]).Select(J.Str).OfType<string>().Take(12).ToList()
        };
    }
}
