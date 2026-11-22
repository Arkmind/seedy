using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Database.Implementations.Enums;
using Jellyfin.Plugin.Seedy.Api;
using MediaBrowser.Controller.Dto;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Movies;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.Querying;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>The Featured hero: the admin's picks and each person's recommendations.</summary>
public sealed partial class HomeService
{
    /// <summary>The most titles the hero rotates through.</summary>
    public const int MaxFeatured = 10;

    /// <summary>
    /// Gets the hero rotation: the admin's picks (in "picks" and "both" modes), then titles recommended for the person
    /// (in "auto" and "both", or when none of the picks is available to them).
    /// </summary>
    /// <param name="user">The person.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>As many entries as the admin asked for, fewer when not enough titles have a backdrop.</returns>
    public async Task<List<FeaturedEntryDto>> FeaturedAsync(User user, CancellationToken ct)
    {
        var c = ConfigMapper.Current;
        var count = Math.Clamp(c.FeaturedCount, 1, MaxFeatured);
        var picked = new List<HeroPick>();
        if (c.FeaturedMode != "auto")
        {
            foreach (var id in c.FeaturedPicks ?? [])
            {
                if (picked.Count < count
                    && Guid.TryParse(id, out var g)
                    && _library.GetItemById<BaseItem>(g, user) is { } item
                    && HasBackdrop(item)
                    && picked.All(p => p.Item.Id != item.Id))
                {
                    picked.Add(new HeroPick(item, "Picked for you", null));
                }
            }
        }

        // "Your picks" alone still needs something to show when no pick is available to this person.
        if (c.FeaturedMode != "picks" || picked.Count == 0)
        {
            var taken = picked.Select(p => p.Item.Id).ToHashSet();
            picked.AddRange(await RecommendedAsync(user, count - picked.Count, taken, ct).ConfigureAwait(false));
        }

        var options = CardOptions;
        var dtos = _dto.GetBaseItemDtos(picked.Select(p => p.Item).ToList(), options, user);
        var list = new List<FeaturedEntryDto>();
        for (var i = 0; i < picked.Count; i++)
        {
            var play = await PlayTargetAsync(user, picked[i].Item).ConfigureAwait(false);
            list.Add(new FeaturedEntryDto
            {
                Item = dtos[i],
                Reason = picked[i].Reason,
                Because = picked[i].Because,
                Play = play is null ? null : (play.Id == picked[i].Item.Id ? dtos[i] : _dto.GetBaseItemDto(play, options, user))
            });
        }

        return list;
    }

    /// <summary>
    /// Titles for the person's hero from what Seedy already knows about them: titles like the ones they watched lately
    /// (the Because you watched seeds), the genres they watch most, what's popular on the server, what's new, and new
    /// episodes of shows they follow. Only titles they haven't started; one day's order stays put, so the hero doesn't
    /// reshuffle on every visit, and changes the next day. With personal recommendations off, nothing personal is used.
    /// </summary>
    private async Task<List<HeroPick>> RecommendedAsync(User user, int need, HashSet<Guid> taken, CancellationToken ct)
    {
        if (need <= 0)
        {
            return [];
        }

        var c = ConfigMapper.Current;
        var uid = user.Id.ToString("N");
        var prefs = _prefs.Get(uid);
        var personal = prefs.Privacy?.PersonalRecs ?? true;
        var hidden = _db.HiddenTitles(uid);
        var now = DateTime.UtcNow;
        var pool = new Dictionary<Guid, HeroCandidate>();
        // Titles the play log or Jellyfin counts as watched: movies are done, shows are ones the person follows.
        var seen = new HashSet<Guid>();
        HeroCandidate? Take(BaseItem? item)
        {
            if (item is not (Movie or Series)
                || taken.Contains(item.Id)
                || hidden.Contains(item.Id.ToString("N"))
                || (item is Movie && seen.Contains(item.Id))
                || !HasBackdrop(item))
            {
                return null;
            }

            if (!pool.TryGetValue(item.Id, out var found))
            {
                found = new HeroCandidate(item);
                pool[item.Id] = found;
            }

            return found;
        }

        // What they watched lately: the seeds of Because you watched (the latest counting most), and the titles
        // their favourite genres come from (as genre rows pick them).
        var watched = new List<BaseItem>();
        var tally = new Dictionary<string, double>(StringComparer.OrdinalIgnoreCase);
        if (personal)
        {
            DateTime? lookBack = c.BecauseLookBackDays > 0 ? now.AddDays(-c.BecauseLookBackDays) : null;
            watched = RecentlyWatchedTitles(user, lookBack, c.BecauseWatchedWhen == "half", usePlayLog: true);
            seen.UnionWith(watched.Select(w => w.Id));
            foreach (var t in RecentlyWatchedTitles(user, now.AddDays(-Math.Max(7, c.GenreWindowDays)), halfCounts: false, usePlayLog: c.UseForGenres))
            {
                seen.Add(t.Id);
                foreach (var g in t.Genres ?? [])
                {
                    tally[g] = tally.GetValueOrDefault(g) + 1;
                }
            }
        }

        // Titles like those seeds.
        var hiddenSeeds = new HashSet<string>(prefs.HiddenSeeds, StringComparer.OrdinalIgnoreCase);
        var seeds = watched.Where(s => !hiddenSeeds.Contains(s.Id.ToString("N"))).Take(6).ToList();
        for (var i = 0; i < seeds.Count; i++)
        {
            IReadOnlyList<BaseItem> similar;
            try
            {
                similar = await _similar.GetSimilarItemsAsync(seeds[i], [], user, new DtoOptions(false), 16, _library.GetLibraryOptions(seeds[i]), ct).ConfigureAwait(false);
            }
#pragma warning disable CA1031 // One bad seed must not break the home screen.
            catch (Exception ex)
#pragma warning restore CA1031
            {
                _logger.LogDebug(ex, "Similar items failed for {Seed}", seeds[i].Name);
                continue;
            }

            var weight = 1 / (1 + (0.4 * i));
            for (var j = 0; j < similar.Count; j++)
            {
                var score = weight * (1 - (j / 20.0));
                if (Take(similar[j]) is { } cand && score > cand.Similar)
                {
                    cand.Similar = score;
                    cand.Seed = seeds[i].Name;
                }
            }
        }

        // Well-rated titles in their favourite genres; new profiles start from the admin's starting genres.
        if (tally.Values.Sum() < 3)
        {
            foreach (var g in c.StartingGenres ?? [])
            {
                tally[g] = tally.GetValueOrDefault(g) + 1;
            }
        }

        var top = tally.Count == 0 ? 1 : tally.Values.Max();
        var shares = tally.ToDictionary(kv => kv.Key, kv => kv.Value / top, StringComparer.OrdinalIgnoreCase);
        foreach (var g in tally.OrderByDescending(kv => kv.Value).Take(3).Select(kv => kv.Key))
        {
            foreach (var item in _library.GetItemList(new InternalItemsQuery(user)
            {
                IncludeItemTypes = [BaseItemKind.Movie, BaseItemKind.Series],
                Genres = [g],
                Recursive = true,
                IsPlayed = false,
                ImageTypes = [ImageType.Backdrop],
                OrderBy = [(ItemSortBy.CommunityRating, SortOrder.Descending)],
                Limit = 16,
                DtoOptions = new DtoOptions(false)
            }))
            {
                Take(item);
            }
        }

        // Popular on this server lately, one vote per profile (the Top 10's count over a month).
        if (c.PlayLogEnabled && c.UseForTop10)
        {
            var popular = _db.TopTitles(now.AddDays(-30), Top10Excluded(c), 30);
            var most = Math.Max(1, popular.Select(p => p.Profiles).DefaultIfEmpty(1).Max());
            foreach (var t in popular)
            {
                if (Guid.TryParse(t.TitleId, out var g) && Take(_library.GetItemById<BaseItem>(g, user)) is { } cand)
                {
                    cand.Popular = (double)t.Profiles / most;
                }
            }
        }

        // New: titles added in the last two months, newest counting most.
        foreach (var item in _library.GetItemList(new InternalItemsQuery(user)
        {
            IncludeItemTypes = [BaseItemKind.Movie, BaseItemKind.Series],
            Recursive = true,
            MinDateCreated = now.AddDays(-60),
            ImageTypes = [ImageType.Backdrop],
            OrderBy = [(ItemSortBy.DateCreated, SortOrder.Descending)],
            Limit = 30,
            DtoOptions = new DtoOptions(false)
        }))
        {
            if (Take(item) is { } cand)
            {
                cand.Fresh = Math.Max(cand.Fresh, Freshness(now - item.DateCreated));
            }
        }

        // Shows with episodes added in the last three weeks.
        var newEpisodes = _library.GetItemList(new InternalItemsQuery(user)
        {
            IncludeItemTypes = [BaseItemKind.Episode],
            Recursive = true,
            IsVirtualItem = false,
            MinDateCreated = now.AddDays(-21),
            OrderBy = [(ItemSortBy.DateCreated, SortOrder.Descending)],
            Limit = 80,
            DtoOptions = new DtoOptions(false)
        }).OfType<Episode>();
        foreach (var group in newEpisodes.GroupBy(e => e.SeriesId).Take(6))
        {
            if (Take(_library.GetItemById<Series>(group.Key, user)) is { } cand)
            {
                cand.NewEpisodes = true;
                cand.NewSeason = group.Any(e => e.IndexNumber == 1 && (e.ParentIndexNumber ?? 0) > 1);
                cand.Fresh = Math.Max(cand.Fresh, Freshness(now - group.Max(e => e.DateCreated)));
            }
        }

        // What the person has started decides the rest: shows they follow come back for new episodes, anything else
        // they started is left to Continue watching and Next up.
        bool Watchable(HeroCandidate cand)
        {
            if (cand.Started is null)
            {
                if (cand.Item is Series)
                {
                    // Watched episodes the play log knows of count too, when Jellyfin missed them.
                    var data = _userData.GetUserDataDto(cand.Item, user);
                    cand.Finished = data is { Played: true };
                    cand.Started = cand.Finished || data?.PlayedPercentage is > 0 || seen.Contains(cand.Item.Id);
                }
                else
                {
                    var data = _userData.GetUserData(user, cand.Item);
                    cand.Started = data is not null && (data.Played || data.PlaybackPositionTicks > 0);
                    cand.Finished = cand.Started.Value;
                }
            }

            return cand.Started != true || (cand.NewEpisodes && !cand.Finished);
        }

        // Shows the person follows that have new episodes rank high, whatever else they score.
        foreach (var cand in pool.Values.Where(p => p.NewEpisodes && Watchable(p) && p.Started == true))
        {
            cand.FollowUp = true;
        }

        var day = now.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        foreach (var cand in pool.Values)
        {
            cand.Genre = GenreMatch(cand.Item, shares);
            cand.Rating = cand.Item.CommunityRating is > 0 ? Math.Min(1, cand.Item.CommunityRating.Value / 10) : 0.6;
            cand.Score = (personal
                    ? (0.45 * cand.Similar) + (0.2 * cand.Genre) + (0.15 * cand.Popular) + (0.12 * cand.Fresh) + (0.08 * cand.Rating)
                    : (0.1 * cand.Genre) + (0.4 * cand.Popular) + (0.3 * cand.Fresh) + (0.2 * cand.Rating))
                + (cand.FollowUp ? 0.5 : 0)
                + (0.15 * Jitter(uid, day, cand.Item.Id));
        }

        // Best first, with some variety: at most two titles like the same seed, two shows back with new episodes, and
        // a mix of movies and shows when both are there. A second pass drops those limits rather than show fewer titles.
        var ranked = pool.Values.OrderByDescending(p => p.Score).ToList();
        var result = new List<HeroPick>();
        var perSeed = new Dictionary<string, int>(StringComparer.Ordinal);
        var typeCap = Math.Max(1, (int)Math.Ceiling(need * 0.7));
        foreach (var strict in new[] { true, false })
        {
            foreach (var cand in ranked)
            {
                if (result.Count >= need)
                {
                    break;
                }

                if (result.Any(r => r.Item.Id == cand.Item.Id) || !Watchable(cand))
                {
                    continue;
                }

                var pick = Explain(cand, personal);
                if (strict
                    && ((pick.Because is { } seed && perSeed.GetValueOrDefault(seed) >= 2)
                        || (cand.FollowUp && result.Count(r => pool[r.Item.Id].FollowUp) >= 2)
                        || result.Count(r => r.Item.GetType() == cand.Item.GetType()) >= typeCap))
                {
                    continue;
                }

                if (pick.Because is { } s)
                {
                    perSeed[s] = perSeed.GetValueOrDefault(s) + 1;
                }

                result.Add(pick);
            }
        }

        // A small or brand-new library: anything with a backdrop the person hasn't hidden, unwatched titles first.
        if (result.Count < need)
        {
            var rest = _library.GetItemList(new InternalItemsQuery(user)
            {
                IncludeItemTypes = [BaseItemKind.Movie, BaseItemKind.Series],
                Recursive = true,
                ImageTypes = [ImageType.Backdrop],
                OrderBy = [(ItemSortBy.Random, SortOrder.Ascending)],
                Limit = 30,
                DtoOptions = new DtoOptions(false)
            }).Where(i => !taken.Contains(i.Id)
                && !hidden.Contains(i.Id.ToString("N"))
                && HasBackdrop(i)
                && result.All(r => r.Item.Id != i.Id))
                .OrderBy(i => seen.Contains(i.Id) || _userData.GetUserData(user, i) is { Played: true } ? 1 : 0);
            result.AddRange(rest.Take(need - result.Count).Select(i => new HeroPick(i, "From your library", null)));
        }

        return result;
    }

    /// <summary>Why a title is in the hero: whatever weighed most in its score.</summary>
    private static HeroPick Explain(HeroCandidate cand, bool personal)
    {
        if (cand.FollowUp)
        {
            return new HeroPick(cand.Item, cand.NewSeason ? "New season" : "New episodes", null);
        }

        var parts = personal
            ? new[] { ("similar", 0.45 * cand.Similar), ("genre", 0.2 * cand.Genre), ("popular", 0.15 * cand.Popular), ("fresh", 0.12 * cand.Fresh) }
            : new[] { ("genre", 0.1 * cand.Genre), ("popular", 0.4 * cand.Popular), ("fresh", 0.3 * cand.Fresh) };
        var (why, weight) = parts.MaxBy(p => p.Item2);
        return why switch
        {
            _ when weight <= 0 => new HeroPick(cand.Item, "From your library", null),
            "similar" => new HeroPick(cand.Item, "Recommended for you", cand.Seed),
            "popular" => new HeroPick(cand.Item, "Popular here", null),
            "fresh" when cand.NewEpisodes => new HeroPick(cand.Item, cand.NewSeason ? "New season" : "New episodes", null),
            "fresh" => new HeroPick(cand.Item, "Just added", null),
            _ => new HeroPick(cand.Item, personal ? "Recommended for you" : "From your library", null)
        };
    }

    /// <summary>1 for two weeks after a title arrives, then down to 0 at two months.</summary>
    private static double Freshness(TimeSpan age) => Math.Clamp(1 - ((age.TotalDays - 14) / 46), 0, 1);

    /// <summary>How well a title's genres match the person's: their favourite counts fully, a second one half.</summary>
    private static double GenreMatch(BaseItem item, Dictionary<string, double> shares)
    {
        var mine = (item.Genres ?? []).Select(g => shares.GetValueOrDefault(g)).OrderDescending().Take(2).ToList();
        return mine.Count == 0 ? 0 : Math.Min(1, mine[0] + (mine.Count > 1 ? mine[1] / 2 : 0));
    }

    /// <summary>A number in [0, 1) that's the same all day for a person and a title, and different the next day.</summary>
    private static double Jitter(string uid, string day, Guid item)
    {
        var hash = SHA256.HashData(Encoding.UTF8.GetBytes(uid + "|" + day + "|" + item.ToString("N")));
        return BitConverter.ToUInt32(hash, 0) / (uint.MaxValue + 1.0);
    }

    private static bool HasBackdrop(BaseItem item) => item.GetImageInfo(ImageType.Backdrop, 0) is not null;

    /// <summary>
    /// What the hero's Play button plays: for a series, the episode in progress (as its details page resumes it), else
    /// the next one; the title itself otherwise.
    /// </summary>
    private async Task<BaseItem?> PlayTargetAsync(User user, BaseItem item)
    {
        if (item is not Series series)
        {
            return item;
        }

        var resume = (await _continue.ItemsAsync(user, series.Id, 1).ConfigureAwait(false)).FirstOrDefault();
        if (resume is not null)
        {
            return resume;
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

    private sealed record HeroPick(BaseItem Item, string Reason, string? Because);

    /// <summary>A title the hero might show, and what speaks for it (each in [0, 1]).</summary>
    private sealed class HeroCandidate(BaseItem item)
    {
        public BaseItem Item { get; } = item;

        /// <summary>Gets or sets how alike it is to a recent watch (the latest watch and the closest match count most).</summary>
        public double Similar { get; set; }

        /// <summary>Gets or sets the recent watch it's most like.</summary>
        public string? Seed { get; set; }

        public double Genre { get; set; }

        public double Popular { get; set; }

        public double Fresh { get; set; }

        public double Rating { get; set; }

        public bool NewEpisodes { get; set; }

        public bool NewSeason { get; set; }

        /// <summary>Gets or sets a value indicating whether the person follows this show and it has new episodes.</summary>
        public bool FollowUp { get; set; }

        /// <summary>Gets or sets whether the person started it; null until checked.</summary>
        public bool? Started { get; set; }

        public bool Finished { get; set; }

        public double Score { get; set; }
    }
}
