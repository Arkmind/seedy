using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Database.Implementations.Enums;
using Jellyfin.Plugin.Seedy.Api;
using Jellyfin.Plugin.Seedy.Configuration;
using Jellyfin.Plugin.Seedy.Data;
using MediaBrowser.Controller.Dto;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Movies;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.TV;
using MediaBrowser.Model.Dto;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.Library;
using MediaBrowser.Model.Querying;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>
/// Builds each person's home screen: which rows, in what order, and the server-side rows
/// (Featured, Top 10). The browser fetches the contents of the other rows from Jellyfin directly.
/// </summary>
public sealed class HomeService
{
    private static readonly string[] Fixed = ["featured", "continue"];
    private static readonly string[] MultiKinds = ["because", "genres", "latest"];

    private readonly ILibraryManager _library;
    private readonly IUserDataManager _userData;
    private readonly IDtoService _dto;
    private readonly ITVSeriesManager _tv;
    private readonly ISimilarItemsManager _similar;
    private readonly IUserViewManager _views;
    private readonly SeedyDatabase _db;
    private readonly PrefsService _prefs;
    private readonly IUserManager _users;
    private readonly ILogger<HomeService> _logger;

    /// <summary>
    /// Initializes a new instance of the <see cref="HomeService"/> class.
    /// </summary>
    /// <param name="library">Library manager.</param>
    /// <param name="userData">User data manager.</param>
    /// <param name="dto">DTO service.</param>
    /// <param name="tv">TV series manager.</param>
    /// <param name="similar">Similar items manager.</param>
    /// <param name="views">User view manager.</param>
    /// <param name="db">Database.</param>
    /// <param name="prefs">Preferences.</param>
    /// <param name="logger">Logger.</param>
    public HomeService(ILibraryManager library, IUserDataManager userData, IDtoService dto, ITVSeriesManager tv, ISimilarItemsManager similar, IUserViewManager views, SeedyDatabase db, PrefsService prefs, IUserManager users, ILogger<HomeService> logger)
    {
        _users = users;
        _library = library;
        _userData = userData;
        _dto = dto;
        _tv = tv;
        _similar = similar;
        _views = views;
        _db = db;
        _prefs = prefs;
        _logger = logger;
    }

    /// <summary>Gets the fields every card needs.</summary>
    public static DtoOptions CardOptions => new()
    {
        Fields = [ItemFields.PrimaryImageAspectRatio, ItemFields.Overview, ItemFields.Genres, ItemFields.ChildCount, ItemFields.RecursiveItemCount, ItemFields.DateCreated],
        ImageTypes = [ImageType.Primary, ImageType.Backdrop, ImageType.Thumb, ImageType.Logo],
        ImageTypeLimit = 1,
        EnableImages = true,
        EnableUserData = true
    };

    // ── Rows ──

    /// <summary>Gets the person's rows in order.</summary>
    /// <param name="user">The person.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>Rows.</returns>
    public async Task<List<HomeRowDto>> RowsAsync(User user, CancellationToken ct)
    {
        var c = ConfigMapper.Current;
        var uid = user.Id.ToString("N");
        var prefs = _prefs.Get(uid);
        var seerr = SeerrService.IsConfigured;
        var logOn = c.PlayLogEnabled;

        // A copy: the person's counts must not leak into the stored configuration.
        var settings = ConfigMapper.HomeRows(c).Where(r => r.Enabled)
            .Select(r => new HomeRowSetting { Kind = r.Kind, Enabled = true, Count = r.Count })
            .ToList();
        var noSeerr = user.MaxParentalRatingScore is not null;
        settings.RemoveAll(r =>
            (r.Kind == "discover" && !(seerr && c.ShowDiscover && !noSeerr))
            || (r.Kind == "upcomingMovies" && !(seerr && c.ShowUpcomingMovies && !noSeerr))
            || (r.Kind == "top10" && !(logOn && c.UseForTop10)));

        // The person's own rows (Settings · Home screen): switches and counts within what the admin allows.
        var personal = (prefs.Rows ?? []).Where(p => settings.Any(s => s.Kind == p.Kind)).ToList();
        foreach (var p in personal)
        {
            var s = settings.First(x => x.Kind == p.Kind);
            if (!p.Enabled && s.Kind is not ("featured" or "continue"))
            {
                settings.Remove(s);
            }
            else if (p.Count is > 0 && s.Count > 0)
            {
                s.Count = Math.Clamp(p.Count.Value, 1, s.Kind == "because" ? 10 : 8);
            }
        }

        var personalRecs = prefs.Privacy?.PersonalRecs ?? true;
        if (!personalRecs)
        {
            settings.RemoveAll(r => r.Kind == "because");
        }

        var kinds = OrderFor(settings.Select(r => r.Kind).ToList(), personal, prefs.RowOrder);
        if (prefs.RowOrder != "mine")
        {
            // Featured and Continue watching never move, and the person's list doesn't include them.
            var pinned = personal.Where(p => p.Pinned && !Fixed.Contains(p.Kind)).Select(p => p.Kind).ToHashSet(StringComparer.Ordinal);
            var rest = kinds.Where(k => !pinned.Contains(k)).ToList();
            var adapted = personalRecs && c.AdaptRowOrder && prefs.AdaptRowOrder && c.UseForRowOrder && logOn
                ? AdaptedOrder(uid, rest)
                : rest;
            // Pinned rows keep the place the person gave them (their position in the person's own list, which the
            // Home screen settings save in the order shown, below the fixed rows); the rest move with what they play.
            var head = adapted.Count(k => Fixed.Contains(k));
            var theirs = personal.Select(p => p.Kind).Where(k => kinds.Contains(k) && !Fixed.Contains(k)).ToList();
            var places = kinds.Where(pinned.Contains)
                .Select(k => (Kind: k, Index: head + theirs.IndexOf(k)))
                .OrderBy(x => x.Index);
            foreach (var (kind, index) in places)
            {
                adapted.Insert(Math.Min(index, adapted.Count), kind);
            }

            kinds = adapted;
        }

        var hiddenSeeds = new HashSet<string>(prefs.HiddenSeeds, StringComparer.OrdinalIgnoreCase);
        var rows = new List<HomeRowDto>();
        var later = new List<Queue<HomeRowDto>>();
        var insertAt = -1;
        foreach (var kind in kinds)
        {
            var count = settings.First(s => s.Kind == kind).Count;
            List<HomeRowDto> instances = kind switch
            {
                "because" => await BecauseRowsAsync(user, count, hiddenSeeds, ct).ConfigureAwait(false),
                "genres" => GenreRows(user, count, personalRecs),
                "latest" => LatestRows(user),
                "collections" => CollectionRows(user),
                _ => [new HomeRowDto { Id = kind, Kind = kind }]
            };
            if (instances.Count == 0)
            {
                continue;
            }

            if (MultiKinds.Contains(kind))
            {
                // First of each multi-row kind sits at its slot; the rest interleave after the last such slot.
                rows.Add(instances[0]);
                later.Add(new Queue<HomeRowDto>(instances.Skip(1)));
                insertAt = rows.Count;
            }
            else
            {
                rows.AddRange(instances);
            }
        }

        if (insertAt >= 0)
        {
            var interleaved = new List<HomeRowDto>();
            while (later.Any(q => q.Count > 0))
            {
                foreach (var q in later.Where(q => q.Count > 0))
                {
                    interleaved.Add(q.Dequeue());
                }
            }

            rows.InsertRange(insertAt, interleaved);
        }

        var hidden = new HashSet<string>(prefs.HiddenRows, StringComparer.Ordinal);
        rows.RemoveAll(r => hidden.Contains(r.Id));
        return rows;
    }

    /// <summary>
    /// Moves the rows a person plays from up: each play started from a row in the last 30 days lifts it one
    /// place, up to six. Featured and Continue watching never move. Recalculated once a day.
    /// </summary>
    private List<string> AdaptedOrder(string uid, List<string> kinds)
    {
        // The cache key includes the admin's order, so reordering rows in the admin takes effect at once.
        var day = DateTime.UtcNow.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) + "|" + string.Join(",", kinds);
        var stored = _db.ReadJson("row_order", uid);
        if (stored is { Day: var d } && d == day)
        {
            try
            {
                var cached = JsonSerializer.Deserialize<List<string>>(stored.Value.Json) ?? [];
                if (cached.Count == kinds.Count && !cached.Except(kinds).Any())
                {
                    return cached;
                }
            }
            catch (JsonException)
            {
                // Recompute below.
            }
        }

        var counts = _db.OriginCounts(uid, DateTime.UtcNow.AddDays(-30));
        var head = kinds.Where(k => Fixed.Contains(k)).ToList();
        var movable = kinds.Where(k => !Fixed.Contains(k))
            .Select((k, i) => (Kind: k, Index: i, Score: i - Math.Min(counts.GetValueOrDefault(k), 6)))
            .OrderBy(x => x.Score)
            .ThenBy(x => x.Index)
            .Select(x => x.Kind);
        var order = head.Concat(movable).ToList();
        _db.WriteRowOrder(uid, day, JsonSerializer.Serialize(order));
        return order;
    }

    /// <summary>Titles the person watched recently, newest first: from the play log and Jellyfin's own watched state.</summary>
    private List<BaseItem> RecentlyWatchedTitles(User user, DateTime? since, bool halfCounts, bool usePlayLog)
    {
        var uid = user.Id.ToString("N");
        var hidden = _db.HiddenTitles(uid);
        var candidates = new Dictionary<Guid, DateTime>();

        if (usePlayLog && ConfigMapper.Current.PlayLogEnabled)
        {
            foreach (var (titleId, last) in _db.WatchedTitles(uid, since, halfCounts))
            {
                if (Guid.TryParse(titleId, out var g))
                {
                    candidates[g] = last;
                }
            }
        }

        var played = _library.GetItemList(new InternalItemsQuery(user)
        {
            IncludeItemTypes = [BaseItemKind.Movie, BaseItemKind.Episode],
            IsPlayed = halfCounts ? null : true,
            IsResumable = null,
            Recursive = true,
            IsVirtualItem = false,
            OrderBy = [(ItemSortBy.DatePlayed, SortOrder.Descending)],
            Limit = 120,
            DtoOptions = new DtoOptions(false)
        });
        foreach (var item in played)
        {
            var data = _userData.GetUserData(user, item);
            if (data?.LastPlayedDate is not { } last || (since is not null && last < since))
            {
                continue;
            }

            var watched = data.Played
                || (halfCounts && item.RunTimeTicks is > 0 && data.PlaybackPositionTicks * 2 >= item.RunTimeTicks);
            if (!watched)
            {
                continue;
            }

            var title = item is Episode ep && ep.SeriesId != Guid.Empty ? ep.SeriesId : item.Id;
            if (!candidates.TryGetValue(title, out var known) || known < last)
            {
                candidates[title] = last;
            }
        }

        var result = new List<BaseItem>();
        foreach (var (id, _) in candidates.OrderByDescending(kv => kv.Value))
        {
            if (hidden.Contains(id.ToString("N")))
            {
                continue;
            }

            var item = _library.GetItemById<BaseItem>(id, user);
            if (item is Movie or Series)
            {
                result.Add(item);
            }
        }

        return result;
    }

    private async Task<List<HomeRowDto>> BecauseRowsAsync(User user, int count, HashSet<string> hiddenSeeds, CancellationToken ct)
    {
        var c = ConfigMapper.Current;
        DateTime? since = c.BecauseLookBackDays > 0 ? DateTime.UtcNow.AddDays(-c.BecauseLookBackDays) : null;
        var seeds = RecentlyWatchedTitles(user, since, c.BecauseWatchedWhen == "half", usePlayLog: true);
        var rows = new List<HomeRowDto>();
        foreach (var seed in seeds.Take(count * 3))
        {
            if (rows.Count >= count || hiddenSeeds.Contains(seed.Id.ToString("N")))
            {
                continue;
            }

            try
            {
                var similar = await _similar.GetSimilarItemsAsync(seed, [], user, new DtoOptions(false), 12, _library.GetLibraryOptions(seed), ct).ConfigureAwait(false);
                if (similar.Count < c.BecauseMinTitles)
                {
                    continue;
                }
            }
#pragma warning disable CA1031 // One bad seed must not break the home screen.
            catch (Exception ex)
#pragma warning restore CA1031
            {
                _logger.LogDebug(ex, "Similar items failed for {Seed}", seed.Name);
                continue;
            }

            var id = seed.Id.ToString("N");
            rows.Add(new HomeRowDto { Id = "because:" + id, Kind = "because", SeedId = id, SeedName = seed.Name });
        }

        return rows;
    }

    /// <summary>
    /// The row order before learning: the person's own order when they chose "My order" (rows the admin added
    /// later go at the end), otherwise the admin's. Featured and Continue watching always lead.
    /// </summary>
    private static List<string> OrderFor(List<string> kinds, List<HomeRowPrefDto> personal, string? mode)
    {
        if (mode != "mine" || personal.Count == 0)
        {
            return kinds;
        }

        var mine = personal.Select(p => p.Kind).Where(kinds.Contains).ToList();
        var order = Fixed.Where(kinds.Contains).ToList();
        order.AddRange(mine.Where(k => !Fixed.Contains(k)));
        order.AddRange(kinds.Where(k => !order.Contains(k)));
        return order;
    }

    private List<HomeRowDto> GenreRows(User user, int count, bool personalRecs = true)
    {
        var c = ConfigMapper.Current;
        var since = DateTime.UtcNow.AddDays(-Math.Max(7, c.GenreWindowDays));
        // Without personal recommendations, genre rows come from the admin's starting genres only.
        var titles = personalRecs ? RecentlyWatchedTitles(user, since, halfCounts: false, usePlayLog: c.UseForGenres) : [];
        var tally = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        foreach (var t in titles)
        {
            foreach (var g in t.Genres ?? [])
            {
                tally[g] = tally.GetValueOrDefault(g) + 1;
            }
        }

        var genres = tally.OrderByDescending(kv => kv.Value).Select(kv => kv.Key).ToList();
        foreach (var g in c.StartingGenres ?? [])
        {
            if (!genres.Contains(g, StringComparer.OrdinalIgnoreCase))
            {
                genres.Add(g);
            }
        }

        var rows = new List<HomeRowDto>();
        foreach (var g in genres)
        {
            if (rows.Count >= count)
            {
                break;
            }

            var sample = _library.GetItemList(new InternalItemsQuery(user)
            {
                IncludeItemTypes = [BaseItemKind.Movie, BaseItemKind.Series],
                Genres = [g],
                Recursive = true,
                Limit = 4,
                DtoOptions = new DtoOptions(false)
            });
            if (sample.Count >= 4)
            {
                rows.Add(new HomeRowDto { Id = "genre:" + g, Kind = "genres", Genre = g, Title = GenreTitle(g) });
            }
        }

        return rows;
    }

    /// <summary>"Thrillers", "Comedies": genre names as row titles.</summary>
    /// <param name="genre">Genre.</param>
    /// <returns>Row title.</returns>
    public static string GenreTitle(string genre)
    {
        var known = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
        {
            ["Thriller"] = "Thrillers",
            ["Comedy"] = "Comedies",
            ["Drama"] = "Dramas",
            ["Documentary"] = "Documentaries",
            ["Mystery"] = "Mysteries",
            ["Western"] = "Westerns",
            ["Musical"] = "Musicals",
            ["Romance"] = "Romance",
            ["Horror"] = "Horror",
            ["Science Fiction"] = "Science fiction",
            ["Sci-Fi & Fantasy"] = "Sci-fi & fantasy",
            ["Action & Adventure"] = "Action & adventure",
            ["War & Politics"] = "War & politics",
            ["Animation"] = "Animation",
            ["Family"] = "Family",
            ["Fantasy"] = "Fantasy",
            ["Crime"] = "Crime",
            ["History"] = "History",
            ["Action"] = "Action",
            ["Adventure"] = "Adventure",
            ["War"] = "War",
            ["Music"] = "Music",
            ["Kids"] = "Kids",
            ["Reality"] = "Reality"
        };
        return known.TryGetValue(genre, out var t) ? t : genre;
    }

    private List<HomeRowDto> LatestRows(User user)
    {
        var rows = new List<HomeRowDto>();
        foreach (var view in _views.GetUserViews(new UserViewQuery { User = user }))
        {
            var type = (view as IHasCollectionType)?.CollectionType;
            if (type is CollectionType.movies or CollectionType.tvshows or CollectionType.homevideos or CollectionType.musicvideos or CollectionType.music)
            {
                var id = view.Id.ToString("N");
                rows.Add(new HomeRowDto { Id = "latest:" + id, Kind = "latest", LibraryId = id, LibraryName = view.Name, CollectionType = type.ToString() });
            }
        }

        return rows;
    }

    private List<HomeRowDto> CollectionRows(User user)
    {
        return _library.GetItemList(new InternalItemsQuery(user)
        {
            IncludeItemTypes = [BaseItemKind.BoxSet],
            Recursive = true,
            OrderBy = [(ItemSortBy.SortName, SortOrder.Ascending)],
            Limit = 12,
            DtoOptions = new DtoOptions(false)
        }).Select(b => new HomeRowDto { Id = "collection:" + b.Id.ToString("N"), Kind = "collections", CollectionId = b.Id.ToString("N"), Title = b.Name }).ToList();
    }

    // ── Featured ──

    /// <summary>Gets the hero rotation: the admin's picks, then new seasons, things in progress and recent additions.</summary>
    /// <param name="user">The person.</param>
    /// <returns>Up to five entries.</returns>
    public List<FeaturedEntryDto> Featured(User user)
    {
        var c = ConfigMapper.Current;
        var picked = new List<(BaseItem Item, string Reason)>();
        void Add(BaseItem? item, string reason)
        {
            if (item is null || picked.Count >= 5 || picked.Any(p => p.Item.Id == item.Id) || !HasBackdrop(item))
            {
                return;
            }

            picked.Add((item, reason));
        }

        if (c.FeaturedMode != "auto")
        {
            foreach (var id in c.FeaturedPicks ?? [])
            {
                if (Guid.TryParse(id, out var g))
                {
                    Add(_library.GetItemById<BaseItem>(g, user), "Picked for you");
                }
            }
        }

        // "Your picks" alone still needs something to show when no pick is available to this person.
        if (c.FeaturedMode != "picks" || picked.Count == 0)
        {
            var hidden = _db.HiddenTitles(user.Id.ToString("N"));
            var since = DateTime.UtcNow.AddDays(-21);

            // Shows with episodes added in the last three weeks.
            var newEpisodes = _library.GetItemList(new InternalItemsQuery(user)
            {
                IncludeItemTypes = [BaseItemKind.Episode],
                Recursive = true,
                IsVirtualItem = false,
                MinDateCreated = since,
                OrderBy = [(ItemSortBy.DateCreated, SortOrder.Descending)],
                Limit = 80,
                DtoOptions = new DtoOptions(false)
            }).OfType<Episode>();
            foreach (var group in newEpisodes.GroupBy(e => e.SeriesId).Take(4))
            {
                var series = _library.GetItemById<Series>(group.Key, user);
                if (series is null || hidden.Contains(series.Id.ToString("N")))
                {
                    continue;
                }

                var firstOfSeason = group.Any(e => e.IndexNumber == 1 && (e.ParentIndexNumber ?? 0) > 1);
                Add(series, firstOfSeason ? "New season" : "New episodes");
                if (picked.Count >= 2)
                {
                    break;
                }
            }

            // Something already in progress.
            var resumable = _library.GetItemList(new InternalItemsQuery(user)
            {
                IncludeItemTypes = [BaseItemKind.Movie, BaseItemKind.Episode],
                IsResumable = true,
                Recursive = true,
                OrderBy = [(ItemSortBy.DatePlayed, SortOrder.Descending)],
                Limit = 6,
                DtoOptions = new DtoOptions(false)
            });
            foreach (var r in resumable.Take(2))
            {
                Add(r is Episode e ? _library.GetItemById<Series>(e.SeriesId, user) : r, "Continue watching");
            }

            // Recently added titles.
            var recent = _library.GetItemList(new InternalItemsQuery(user)
            {
                IncludeItemTypes = [BaseItemKind.Movie, BaseItemKind.Series],
                Recursive = true,
                ImageTypes = [ImageType.Backdrop],
                OrderBy = [(ItemSortBy.DateCreated, SortOrder.Descending)],
                Limit = 12,
                DtoOptions = new DtoOptions(false)
            });
            foreach (var r in recent)
            {
                if (!hidden.Contains(r.Id.ToString("N")))
                {
                    Add(r, DateTime.UtcNow - r.DateCreated < TimeSpan.FromDays(14) ? "Just added" : "From your library");
                }
            }

            // Fill with well-rated titles the person hasn't finished.
            if (picked.Count < 5)
            {
                foreach (var r in _library.GetItemList(new InternalItemsQuery(user)
                {
                    IncludeItemTypes = [BaseItemKind.Movie, BaseItemKind.Series],
                    Recursive = true,
                    IsPlayed = false,
                    ImageTypes = [ImageType.Backdrop],
                    OrderBy = [(ItemSortBy.Random, SortOrder.Ascending)],
                    Limit = 10,
                    DtoOptions = new DtoOptions(false)
                }))
                {
                    Add(r, "From your library");
                }
            }
        }

        var options = CardOptions;
        var dtos = _dto.GetBaseItemDtos(picked.Select(p => p.Item).ToList(), options, user);
        var list = new List<FeaturedEntryDto>();
        for (var i = 0; i < picked.Count; i++)
        {
            var play = PlayTarget(user, picked[i].Item);
            list.Add(new FeaturedEntryDto
            {
                Item = dtos[i],
                Reason = picked[i].Reason,
                Play = play is null ? null : (play.Id == picked[i].Item.Id ? dtos[i] : _dto.GetBaseItemDto(play, options, user))
            });
        }

        return list;
    }

    private static bool HasBackdrop(BaseItem item) => item.GetImageInfo(ImageType.Backdrop, 0) is not null;

    /// <summary>What the hero's Play button plays: the next episode for a series, the title itself otherwise.</summary>
    private BaseItem? PlayTarget(User user, BaseItem item)
    {
        if (item is not Series series)
        {
            return item;
        }

        var next = _tv.GetNextUp(
            new NextUpQuery
            {
                User = user,
                SeriesId = series.Id,
                Limit = 1,
                EnableResumable = true,
                EnableTotalRecordCount = false,
                NextUpDateCutoff = DateTime.MinValue
            },
            new DtoOptions(false)).Items.FirstOrDefault();
        if (next is not null)
        {
            return next;
        }

        return _library.GetItemList(new InternalItemsQuery(user)
        {
            AncestorIds = [series.Id],
            IncludeItemTypes = [BaseItemKind.Episode],
            IsVirtualItem = false,
            Recursive = true,
            OrderBy = [(ItemSortBy.ParentIndexNumber, SortOrder.Ascending), (ItemSortBy.IndexNumber, SortOrder.Ascending)],
            Limit = 1,
            DtoOptions = new DtoOptions(false)
        }).FirstOrDefault();
    }

    // ── Top 10 ──

    /// <summary>Gets the most watched titles on the server, counting each profile once per title.</summary>
    /// <param name="user">The viewer (only titles they can see are listed).</param>
    /// <returns>Top 10 and whether the period was too quiet to show it.</returns>
    public Top10Dto Top10(User user)
    {
        var c = ConfigMapper.Current;
        if (!c.PlayLogEnabled || !c.UseForTop10)
        {
            return new Top10Dto { Quiet = true };
        }

        var since = DateTime.UtcNow.AddDays(-(c.Top10PeriodDays == 30 ? 30 : 7));
        // Left out: profiles the admin switched off, and people who turned "Count my plays in the Top 10" off.
        var excluded = (c.PlayLogProfiles ?? []).Where(p => !p.Top10).Select(p => p.UserId)
            .Concat(_users.GetUsers().Select(u => u.Id.ToString("N")).Where(id => !(_prefs.Get(id).Privacy?.CountInTop10 ?? true)))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .ToList();
        var quiet = _db.ActiveProfiles(since, excluded) < Math.Max(1, c.Top10MinProfiles);
        var entries = new List<(BaseItem Item, TitleCount Count)>();
        foreach (var t in _db.TopTitles(since, excluded, 30))
        {
            if (entries.Count >= 10)
            {
                break;
            }

            if (Guid.TryParse(t.TitleId, out var id) && _library.GetItemById<BaseItem>(id, user) is { } item)
            {
                entries.Add((item, t));
            }
        }

        var dtos = _dto.GetBaseItemDtos(entries.Select(e => e.Item).ToList(), CardOptions, user);
        return new Top10Dto
        {
            Quiet = quiet,
            Items = entries.Select((e, i) => new Top10EntryDto { Item = dtos[i], Profiles = e.Count.Profiles, Plays = e.Count.Plays }).ToList()
        };
    }

    /// <summary>Converts items to DTOs for the person.</summary>
    /// <param name="items">Items.</param>
    /// <param name="user">The person.</param>
    /// <returns>DTOs.</returns>
    public IReadOnlyList<BaseItemDto> ToDtos(IReadOnlyList<BaseItem> items, User user) => _dto.GetBaseItemDtos(items, CardOptions, user);
}
