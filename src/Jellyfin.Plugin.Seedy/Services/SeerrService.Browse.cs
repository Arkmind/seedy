using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Seedy.Api;
using Jellyfin.Plugin.Seedy.Services.Media;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>What the Requests page's Sort and Filters ask Seerr for.</summary>
public sealed class SeerrBrowseQuery
{
    /// <summary>Gets or sets all, trending, upcoming or top.</summary>
    public string View { get; set; } = "all";

    /// <summary>Gets or sets all, movie or tv.</summary>
    public string Type { get; set; } = "all";

    /// <summary>Gets or sets popularity, rating, release or title.</summary>
    public string Sort { get; set; } = "popularity";

    /// <summary>Gets or sets a value indicating whether the sort goes up.</summary>
    public bool Ascending { get; set; }

    /// <summary>Gets the statuses to keep: missing, partial, requested, available (none = all).</summary>
    public HashSet<string> Status { get; } = new(StringComparer.Ordinal);

    /// <summary>Gets the genre keys (see <see cref="SeerrGenreDto"/>); a title in any of them matches.</summary>
    public List<int> Genres { get; } = [];

    /// <summary>Gets or sets the first release year.</summary>
    public int? From { get; set; }

    /// <summary>Gets or sets the last release year.</summary>
    public int? To { get; set; }

    /// <summary>Gets or sets the lowest TMDB rating.</summary>
    public double? Rating { get; set; }

    /// <summary>Gets or sets the shortest runtime, in minutes.</summary>
    public int? RuntimeMin { get; set; }

    /// <summary>Gets or sets the longest runtime, in minutes.</summary>
    public int? RuntimeMax { get; set; }

    /// <summary>Gets the original languages (ISO 639-1).</summary>
    public List<string> Languages { get; } = [];

    /// <summary>Gets the studios (TMDB companies): only movies have them here.</summary>
    public List<int> Studios { get; } = [];

    /// <summary>Gets the networks (TMDB networks): only shows have them.</summary>
    public List<int> Networks { get; } = [];

    /// <summary>Gets the keywords; a title needs all of them.</summary>
    public List<int> Keywords { get; } = [];
}

/* The Requests page's Sort and Filters (RequestsResults board): Seerr's discover lists for movies and shows, merged
   into one list in the picked order. Seerr can't filter by what's on the server, nor Trending by anything, so those
   filters are applied here while walking Seerr's pages; the list then has no known total. */
public sealed partial class SeerrService
{
    /* Seerr's discover pages are 20 titles, and TMDB stops at page 500. */
    private const int MaxSeerrPages = 500;

    /* Pages fetched from Seerr for one answer, while filters here leave most titles out. */
    private const int MaxBrowseFetches = 10;

    /* TMDB's show genres that cover two movie genres each. */
    private static readonly Dictionary<int, int> ShowGenreFor = new()
    {
        [28] = 10759, // Action → Action & Adventure
        [12] = 10759, // Adventure
        [878] = 10765, // Science Fiction → Sci-Fi & Fantasy
        [14] = 10765, // Fantasy
        [10752] = 10768 // War → War & Politics
    };

    /* Networks to pick from: TMDB has no network search. */
    private static readonly (int Id, string Name)[] Networks =
    [
        (213, "Netflix"), (2739, "Disney+"), (1024, "Prime Video"), (2552, "Apple TV"), (49, "HBO"), (3186, "HBO Max"),
        (453, "Hulu"), (4330, "Paramount+"), (3353, "Peacock"), (2, "ABC"), (6, "NBC"), (16, "CBS"), (19, "FOX"),
        (71, "The CW"), (174, "AMC"), (88, "FX"), (67, "Showtime"), (318, "STARZ"), (4, "BBC One"), (332, "BBC Two"),
        (26, "Channel 4"), (9, "ITV1"), (285, "Canal+"), (1628, "ARTE"), (361, "France 2"), (290, "TF1"), (712, "M6"),
        (1112, "Crunchyroll"), (80, "Adult Swim"), (56, "Cartoon Network"), (13, "Nickelodeon"), (47, "Comedy Central"), (77, "Syfy")
    ];

    /// <summary>What the Filters panel offers: genres of movies and shows as one list, languages and networks.</summary>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Options.</returns>
    public async Task<SeerrBrowseOptionsDto> BrowseOptionsAsync(CancellationToken ct)
    {
        var (movie, tv) = await GenreListsAsync(ct).ConfigureAwait(false);
        var genres = new List<SeerrGenreDto>();
        foreach (var (id, name) in movie)
        {
            genres.Add(new SeerrGenreDto { Id = id, Name = name, Movie = true, Tv = tv.ContainsKey(id) || ShowGenreFor.ContainsKey(id) });
        }

        // Shows' own genres, without the ones that pair two movie genres.
        foreach (var (id, name) in tv)
        {
            if (!movie.ContainsKey(id) && !ShowGenreFor.ContainsValue(id))
            {
                genres.Add(new SeerrGenreDto { Id = id, Name = name, Tv = true });
            }
        }

        var languages = new List<SeerrLanguageDto>();
        try
        {
            var node = await Cached("/languages", DetailsTtl, ct).ConfigureAwait(false);
            foreach (var l in node?.AsArray() ?? [])
            {
                var code = Str(l?["iso_639_1"]);
                var name = NullIfEmpty(Str(l?["english_name"]));
                if (code is { Length: 2 } && name is not null && code != "xx")
                {
                    languages.Add(new SeerrLanguageDto { Code = code, Name = name });
                }
            }
        }
        catch (SeerrException)
        {
            // The rest of the filters still work.
        }

        return new SeerrBrowseOptionsDto
        {
            Genres = genres.OrderBy(g => g.Name, StringComparer.CurrentCultureIgnoreCase).ToList(),
            Languages = languages.OrderBy(l => l.Name, StringComparer.OrdinalIgnoreCase).ToList(),
            Networks = Networks.Select(n => new SeerrNamedDto { Id = n.Id, Name = n.Name }).ToList()
        };
    }

    /// <summary>Studios (TMDB companies) whose name matches.</summary>
    /// <param name="query">Text.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Up to 20 studios.</returns>
    public async Task<List<SeerrNamedDto>> SearchStudiosAsync(string query, CancellationToken ct)
    {
        var node = await Cached("/search/company?page=1&query=" + Uri.EscapeDataString(query.Trim()), ListTtl, ct).ConfigureAwait(false);
        return (node?["results"]?.AsArray() ?? [])
            .Select(r => (Id: J.Int(r?["id"]), Name: NullIfEmpty(Str(r?["name"])), Country: NullIfEmpty(Str(r?["origin_country"]))))
            .Where(r => r.Id is not null && r.Name is not null)
            .Take(20)
            .Select(r => new SeerrNamedDto { Id = r.Id!.Value, Name = r.Name!, Sub = r.Country })
            .ToList();
    }

    /// <summary>TMDB keywords that match.</summary>
    /// <param name="query">Text.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Up to 20 keywords.</returns>
    public async Task<List<SeerrNamedDto>> SearchKeywordsAsync(string query, CancellationToken ct)
    {
        var node = await Cached("/search/keyword?page=1&query=" + Uri.EscapeDataString(query.Trim()), ListTtl, ct).ConfigureAwait(false);
        return (node?["results"]?.AsArray() ?? [])
            .Select(r => (Id: J.Int(r?["id"]), Name: NullIfEmpty(Str(r?["name"]))))
            .Where(r => r.Id is not null && r.Name is not null)
            .Take(20)
            .Select(r => new SeerrNamedDto { Id = r.Id!.Value, Name = r.Name! })
            .ToList();
    }

    /// <summary>A page of titles for the Requests page's Sort and Filters.</summary>
    /// <param name="q">Sort and filters.</param>
    /// <param name="cursor">Where the previous page stopped (null for the first).</param>
    /// <param name="take">Titles wanted.</param>
    /// <param name="me">The viewer's Seerr id.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Titles, the total when Seerr knows it, and where to go on from.</returns>
    public async Task<SeerrBrowseDto> BrowseAsync(SeerrBrowseQuery q, string? cursor, int take, int? me, CancellationToken ct)
    {
        var sources = await BrowseSourcesAsync(q, ct).ConfigureAwait(false);
        var resumed = cursor?.Split('-') ?? [];
        for (var i = 0; i < sources.Count; i++)
        {
            var at = i < resumed.Length ? resumed[i] : null;
            if (at == "x")
            {
                sources[i].Done = true;
            }
            else if (at?.Split('.') is [var p, var x]
                && int.TryParse(p, NumberStyles.Integer, CultureInfo.InvariantCulture, out var page)
                && int.TryParse(x, NumberStyles.Integer, CultureInfo.InvariantCulture, out var index))
            {
                sources[i].Page = Math.Clamp(page, 1, MaxSeerrPages);
                sources[i].Index = Math.Max(0, index);
            }
        }

        var trending = q.View == "trending";
        // Filters Seerr can't apply (any on Trending), which leave the total unknown.
        var filtersHere = q.Status.Count > 0 || (trending && (q.Type != "all" || q.Genres.Count > 0 || q.From is not null || q.To is not null || q.Rating is not null || q.Languages.Count > 0));
        var genreIds = trending ? await TrendingGenreIdsAsync(q, ct).ConfigureAwait(false) : (new List<int>(), new List<int>());
        var result = new SeerrBrowseDto();
        var fetches = 0;
        var seen = new HashSet<string>(StringComparer.Ordinal);
        var stopped = false;
        while (result.Results.Count < take && !stopped)
        {
            foreach (var s in sources.Where(s => !s.Done))
            {
                if (s.Loaded && s.Index >= s.Items.Count)
                {
                    s.Page++;
                    s.Index = 0;
                    s.Loaded = false;
                    if (s.Page > s.TotalPages)
                    {
                        s.Done = true;
                        continue;
                    }
                }

                if (s.Loaded)
                {
                    continue;
                }

                if (fetches >= MaxBrowseFetches)
                {
                    stopped = true;
                    break;
                }

                var node = await Cached(s.Path + "&page=" + s.Page.ToString(CultureInfo.InvariantCulture), ListTtl, ct).ConfigureAwait(false);
                fetches++;
                s.Items = node?["results"]?.AsArray().ToList() ?? [];
                s.TotalPages = Math.Min(J.Int(node?["totalPages"]) ?? 1, MaxSeerrPages);
                s.TotalResults = J.Int(node?["totalResults"]) ?? s.Items.Count;
                s.Loaded = true;
                if (s.Items.Count == 0)
                {
                    s.Done = true;
                }
            }

            var live = sources.Where(s => !s.Done && s.Loaded && s.Index < s.Items.Count).ToList();
            if (stopped || live.Count == 0)
            {
                if (live.Count == 0 && sources.Any(s => !s.Done))
                {
                    // A source just moved on to a page it couldn't fetch yet.
                    continue;
                }

                break;
            }

            var next = live.Aggregate((a, b) => Before(b, a, q) ? b : a);
            var r = next.Items[next.Index];
            next.Index++;
            var item = MapResult(r, me, next.Type);
            if (item is null || item.Status == "blocklisted" || !seen.Add(item.MediaType + ":" + item.TmdbId.ToString(CultureInfo.InvariantCulture)))
            {
                continue;
            }

            if (trending && !TrendingMatches(r, item, q, genreIds))
            {
                continue;
            }

            if (q.Status.Count > 0 && !q.Status.Contains(StatusGroup(item.Status)))
            {
                continue;
            }

            result.Results.Add(Title(item, null));
        }

        // Where to go on from: each source's page and place in it, "x" once it's used up.
        if (sources.Any(s => !s.Done && (!s.Loaded || s.Index < s.Items.Count || s.Page < s.TotalPages)))
        {
            result.Next = string.Join('-', sources.Select(s => s.Done ? "x" : string.Create(CultureInfo.InvariantCulture, $"{s.Page}.{s.Index}")));
        }

        if (cursor is null && !filtersHere && sources.All(s => s.Loaded || s.Done))
        {
            result.Total = sources.Sum(s => s.TotalResults);
        }

        await Enrich(result.Results, me, ct).ConfigureAwait(false);
        return result;
    }

    /* none and declined titles aren't on the server and nobody's waiting for them. */
    private static string StatusGroup(string status) => status switch
    {
        "available" => "available",
        "partial" => "partial",
        "pending" or "processing" => "requested",
        _ => "missing"
    };

    private sealed class BrowseSource
    {
        public BrowseSource(string? type, string path)
        {
            Type = type;
            Path = path;
        }

        /* movie or tv; null for Trending, which mixes both. */
        public string? Type { get; }

        /* The discover path with its filters, without the page. */
        public string Path { get; }

        public int Page { get; set; } = 1;

        public int Index { get; set; }

        public bool Loaded { get; set; }

        public bool Done { get; set; }

        public List<JsonNode?> Items { get; set; } = [];

        public int TotalPages { get; set; } = 1;

        public int TotalResults { get; set; }
    }

    private async Task<(Dictionary<int, string> Movie, Dictionary<int, string> Tv)> GenreListsAsync(CancellationToken ct)
    {
        static Dictionary<int, string> Read(JsonNode? node)
        {
            var map = new Dictionary<int, string>();
            foreach (var g in node?.AsArray() ?? [])
            {
                if (J.Int(g?["id"]) is { } id && NullIfEmpty(Str(g?["name"])) is { } name)
                {
                    map[id] = name;
                }
            }

            return map;
        }

        var movie = await Cached("/genres/movie", DetailsTtl, ct).ConfigureAwait(false);
        var tv = await Cached("/genres/tv", DetailsTtl, ct).ConfigureAwait(false);
        return (Read(movie), Read(tv));
    }

    /* A genre key's TMDB ids for movies and for shows (a show genre can stand for two movie genres). */
    private static (List<int> Movie, List<int> Tv) GenreIds(IEnumerable<int> keys, Dictionary<int, string> movie, Dictionary<int, string> tv)
    {
        var m = new List<int>();
        var s = new List<int>();
        foreach (var k in keys)
        {
            if (movie.ContainsKey(k))
            {
                m.Add(k);
            }

            if (tv.ContainsKey(k))
            {
                s.Add(k);
            }
            else if (ShowGenreFor.TryGetValue(k, out var show))
            {
                s.Add(show);
            }
        }

        return (m.Distinct().ToList(), s.Distinct().ToList());
    }

    private async Task<(List<int> Movie, List<int> Tv)> TrendingGenreIdsAsync(SeerrBrowseQuery q, CancellationToken ct)
    {
        if (q.Genres.Count == 0)
        {
            return ([], []);
        }

        var (movie, tv) = await GenreListsAsync(ct).ConfigureAwait(false);
        return GenreIds(q.Genres, movie, tv);
    }

    /* Seerr's discover lists for what's asked: one per media type, or Trending. */
    private async Task<List<BrowseSource>> BrowseSourcesAsync(SeerrBrowseQuery q, CancellationToken ct)
    {
        if (q.View == "trending")
        {
            return [new BrowseSource(null, "/discover/trending?language=en")];
        }

        var (movieGenres, tvGenres) = q.Genres.Count > 0 ? await GenreListsAsync(ct).ConfigureAwait(false) : (new Dictionary<int, string>(), new Dictionary<int, string>());
        var (movieIds, tvIds) = GenreIds(q.Genres, movieGenres, tvGenres);
        var sources = new List<BrowseSource>();
        foreach (var type in new[] { "movie", "tv" })
        {
            var tv = type == "tv";
            if (q.Type != "all" && q.Type != type)
            {
                continue;
            }

            // Genres only movies have (Thriller), studios (movies) or networks (shows): the other type has none.
            if ((q.Genres.Count > 0 && (tv ? tvIds : movieIds).Count == 0)
                || (tv && q.Studios.Count > 0 && q.Networks.Count == 0)
                || (!tv && q.Networks.Count > 0 && q.Studios.Count == 0))
            {
                continue;
            }

            sources.Add(new BrowseSource(type, DiscoverPath(q, tv, tv ? tvIds : movieIds)));
        }

        return sources;
    }

    private static string DiscoverPath(SeerrBrowseQuery q, bool tv, List<int> genres)
    {
        var p = new List<string>();
        void Add(string key, string? value)
        {
            if (!string.IsNullOrEmpty(value))
            {
                p.Add(key + "=" + Uri.EscapeDataString(value));
            }
        }

        static string Ids(IEnumerable<int> ids, char sep) => string.Join(sep, ids.Select(i => i.ToString(CultureInfo.InvariantCulture)));

        var dir = q.Ascending ? ".asc" : ".desc";
        Add("sortBy", q.Sort switch
        {
            "rating" => "vote_average" + dir,
            "release" => (tv ? "first_air_date" : "primary_release_date") + dir,
            "title" => (tv ? "original_name" : "original_title") + dir,
            _ => "popularity" + dir
        });

        var today = DateTime.UtcNow.Date;
        DateTime? from = q.From is { } f ? new DateTime(f, 1, 1, 0, 0, 0, DateTimeKind.Utc) : null;
        DateTime? to = q.To is { } t ? new DateTime(t, 12, 31, 0, 0, 0, DateTimeKind.Utc) : null;
        if (q.View == "upcoming" && (from is null || from <= today))
        {
            from = today.AddDays(1);
        }

        // Newest first means newest out: without a last year, titles only announced for later stay out.
        if (q.Sort == "release" && !q.Ascending && q.View != "upcoming" && to is null)
        {
            to = today;
        }

        var dateKey = tv ? "firstAirDate" : "primaryReleaseDate";
        Add(dateKey + "Gte", from?.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture));
        Add(dateKey + "Lte", to?.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture));

        // Top rated: well-rated titles enough people rated. Sorting by rating or title also skips titles with a
        // handful of votes, which would otherwise fill the first pages ("!", "!!!", a 10 from one vote).
        var top = q.View == "top";
        var minRating = top ? Math.Max(7.5, q.Rating ?? 0) : q.Rating;
        Add("voteAverageGte", minRating is > 0 ? minRating.Value.ToString("0.#", CultureInfo.InvariantCulture) : null);
        Add("voteCountGte", top ? (tv ? "150" : "300") : q.Sort == "rating" ? (tv ? "50" : "100") : q.Sort == "title" ? "25" : null);

        Add("genre", genres.Count > 0 ? Ids(genres, '|') : null);
        Add("withRuntimeGte", q.RuntimeMin?.ToString(CultureInfo.InvariantCulture));
        Add("withRuntimeLte", q.RuntimeMax?.ToString(CultureInfo.InvariantCulture));
        Add("language", q.Languages.Count > 0 ? string.Join('|', q.Languages) : null);
        Add("keywords", q.Keywords.Count > 0 ? Ids(q.Keywords, ',') : null);
        if (tv)
        {
            Add("network", q.Networks.Count > 0 ? Ids(q.Networks, '|') : null);
        }
        else
        {
            Add("studio", q.Studios.Count > 0 ? Ids(q.Studios, '|') : null);
        }

        return (tv ? "/discover/tv?" : "/discover/movies?") + string.Join('&', p);
    }

    /* Whether source a's next title comes before source b's in the picked order. Shows' popularity runs on a bigger
       scale than movies', so by popularity the lists take turns by rank: each one's 1st, then each one's 2nd… */
    private static bool Before(BrowseSource sa, BrowseSource sb, SeerrBrowseQuery q)
    {
        var (a, aType, b, bType) = (sa.Items[sa.Index], sa.Type, sb.Items[sb.Index], sb.Type);
        int c;
        switch (q.Sort)
        {
            case "popularity" when sa.Type is not null && sb.Type is not null:
                // Lower rank first, whichever way the sort goes; a tie goes to the list listed first (movies).
                return Rank(sa) < Rank(sb);
            case "rating":
                c = (J.Double(a?["voteAverage"]) ?? 0).CompareTo(J.Double(b?["voteAverage"]) ?? 0);
                break;
            case "release":
                c = string.CompareOrdinal(DateOf(a, aType), DateOf(b, bType));
                break;
            case "title":
                c = string.Compare(OriginalTitle(a, aType), OriginalTitle(b, bType), StringComparison.OrdinalIgnoreCase);
                break;
            default:
                c = (J.Double(a?["popularity"]) ?? 0).CompareTo(J.Double(b?["popularity"]) ?? 0);
                break;
        }

        return q.Ascending ? c < 0 : c > 0;
    }

    private static long Rank(BrowseSource s) => ((long)s.Page - 1) * 20 + s.Index;

    private static string TypeOf(JsonNode? r, string? type) => type ?? Str(r?["mediaType"]) ?? "movie";

    private static string DateOf(JsonNode? r, string? type) => Str(r?[TypeOf(r, type) == "tv" ? "firstAirDate" : "releaseDate"]) ?? string.Empty;

    private static string OriginalTitle(JsonNode? r, string? type) => TypeOf(r, type) == "tv"
        ? Str(r?["originalName"]) ?? Str(r?["name"]) ?? string.Empty
        : Str(r?["originalTitle"]) ?? Str(r?["title"]) ?? string.Empty;

    /* Trending can't be filtered by Seerr: type, genre, year, rating and language from what each title says. */
    private static bool TrendingMatches(JsonNode? r, SeerrItemDto item, SeerrBrowseQuery q, (List<int> Movie, List<int> Tv) genres)
    {
        var tv = item.MediaType == "tv";
        if (q.Type != "all" && q.Type != item.MediaType)
        {
            return false;
        }

        if (q.Genres.Count > 0)
        {
            var wanted = tv ? genres.Tv : genres.Movie;
            var has = (r?["genreIds"]?.AsArray() ?? []).Select(g => J.Int(g)).OfType<int>();
            if (!has.Any(wanted.Contains))
            {
                return false;
            }
        }

        var year = int.TryParse(item.Year, NumberStyles.Integer, CultureInfo.InvariantCulture, out var y) ? y : (int?)null;
        if ((q.From is not null && (year is null || year < q.From)) || (q.To is not null && (year is null || year > q.To)))
        {
            return false;
        }

        if (q.Rating is > 0 && (J.Double(r?["voteAverage"]) ?? 0) < q.Rating)
        {
            return false;
        }

        return q.Languages.Count == 0 || q.Languages.Contains(Str(r?["originalLanguage"]) ?? string.Empty);
    }
}
