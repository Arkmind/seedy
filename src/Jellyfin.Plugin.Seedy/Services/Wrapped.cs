using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Text.Json;
using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Database.Implementations.Enums;
using Jellyfin.Plugin.Seedy.Api;
using Jellyfin.Plugin.Seedy.Data;
using MediaBrowser.Controller.Dto;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>
/// Seedy Wrapped: each profile's year on Seedy, told in a few stories. It's built once in December from the play log
/// (1 January to 30 November, in the time zone the profile's apps run in) and stored, so it can be opened again from
/// Settings long after the log has been trimmed.
/// </summary>
public sealed class Wrapped
{
    /// <summary>The kinds of moment players report.</summary>
    public static readonly HashSet<string> MomentKinds = new(StringComparer.Ordinal) { "introSkipped", "stillAsked", "stillYes", "stillStop" };

    private static readonly JsonSerializerOptions Json = new() { PropertyNameCaseInsensitive = true };

    // Episodes of one show this close together make one sitting.
    private static readonly TimeSpan SittingGap = TimeSpan.FromMinutes(30);

    // Below this, there isn't a year to tell.
    private static readonly long MinWatchMs = (long)TimeSpan.FromHours(1).TotalMilliseconds;

    private static readonly string[] VideoKinds = ["show", "movie", "livetv", "other"];

    // Genre names as TMDB writes them in English and French, folded to one key the apps write their sentences for.
    private static readonly Dictionary<string, string> GenreKeys = new(StringComparer.Ordinal)
    {
        ["thriller"] = "thriller",
        ["drama"] = "drama",
        ["drame"] = "drama",
        ["comedy"] = "comedy",
        ["comedie"] = "comedy",
        ["science fiction"] = "scifi",
        ["science-fiction"] = "scifi",
        ["sci-fi"] = "scifi",
        ["sci-fi & fantasy"] = "scifi",
        ["science-fiction & fantastique"] = "scifi",
        ["documentary"] = "documentary",
        ["documentaire"] = "documentary",
        ["animation"] = "animation",
        ["action"] = "action",
        ["action & adventure"] = "action",
        ["action & aventure"] = "action",
        ["adventure"] = "adventure",
        ["aventure"] = "adventure",
        ["horror"] = "horror",
        ["horreur"] = "horror",
        ["crime"] = "crime",
        ["romance"] = "romance",
        ["fantasy"] = "fantasy",
        ["fantastique"] = "fantasy",
        ["mystery"] = "mystery",
        ["mystere"] = "mystery",
        ["family"] = "family",
        ["familial"] = "family",
        ["history"] = "history",
        ["histoire"] = "history",
        ["war"] = "war",
        ["guerre"] = "war",
        ["war & politics"] = "war",
        ["guerre & politique"] = "war",
        ["western"] = "western",
        ["music"] = "music",
        ["musique"] = "music",
        ["kids"] = "kids",
        ["enfants"] = "kids",
        ["reality"] = "reality",
        ["telerealite"] = "reality",
        ["tele-realite"] = "reality"
    };

    private readonly SeedyDatabase _db;
    private readonly IUserManager _users;
    private readonly ILibraryManager _library;
    private readonly HomeService _home;
    private readonly ILogger<Wrapped> _logger;
    private readonly ConcurrentDictionary<string, string> _zones = new(StringComparer.OrdinalIgnoreCase);
    private readonly object _buildLock = new();

    /// <summary>
    /// Initializes a new instance of the <see cref="Wrapped"/> class.
    /// </summary>
    /// <param name="db">Database.</param>
    /// <param name="users">User manager.</param>
    /// <param name="library">Library manager.</param>
    /// <param name="home">Home service, for item DTOs.</param>
    /// <param name="logger">Logger.</param>
    public Wrapped(SeedyDatabase db, IUserManager users, ILibraryManager library, HomeService home, ILogger<Wrapped> logger)
    {
        _db = db;
        _users = users;
        _library = library;
        _home = home;
        _logger = logger;
        foreach (var (user, zone) in db.Zones())
        {
            _zones[user] = zone;
        }
    }

    /// <summary>One counted play.</summary>
    private sealed record Row(PlayRecord Play, string Kind, long Watched, DateTime Start, DateTime End, DateTime LocalStart, DateTime LocalEnd);

    /// <summary>Remembers the time zone a profile's apps run in.</summary>
    /// <param name="userId">User id ("N").</param>
    /// <param name="tz">IANA time zone id.</param>
    public void SeenIn(string userId, string? tz)
    {
        if (string.IsNullOrWhiteSpace(tz) || tz.Length > 64 || !TimeZoneInfo.TryFindSystemTimeZoneById(tz, out _))
        {
            return;
        }

        if (_zones.TryGetValue(userId, out var known) && known == tz)
        {
            return;
        }

        _zones[userId] = tz;
        _db.SetZone(userId, tz);
    }

    private TimeZoneInfo ZoneOf(string userId)
        => _zones.TryGetValue(userId, out var id) ? Statistics.Zone(id) : TimeZoneInfo.Local;

    /// <summary>Gets whether this year's Wrapped is out for a profile: December has started where they are.</summary>
    /// <param name="userId">User id ("N").</param>
    /// <returns>The year that's out, or null.</returns>
    public int? OpenYear(string userId)
    {
        var now = TimeZoneInfo.ConvertTimeFromUtc(DateTime.UtcNow, ZoneOf(userId));
        return now.Month == 12 ? now.Year : null;
    }

    /// <summary>The years a profile can open, and this year's banner while it's December.</summary>
    /// <param name="user">The profile.</param>
    /// <returns>Status.</returns>
    public WrappedStatusDto Status(User user)
    {
        var uid = user.Id.ToString("N");
        var dto = new WrappedStatusDto();
        var open = OpenYear(uid);
        if (open is { } year && Get(user, year, false) is { } current)
        {
            dto.Current = new WrappedTeaserDto { Year = year, WatchMs = current.WatchMs, Stories = current.Stories.Count };
        }

        dto.Years = _db.WrappedYears(uid);
        return dto;
    }

    /// <summary>Gets a profile's Wrapped for a year, building this year's the first time it's asked for in December.</summary>
    /// <param name="user">The profile.</param>
    /// <param name="year">Year.</param>
    /// <param name="withItems">Whether to add the titles' items, for their pictures.</param>
    /// <returns>The Wrapped, or null when there's none.</returns>
    public WrappedDto? Get(User user, int year, bool withItems = true)
    {
        var uid = user.Id.ToString("N");
        WrappedDto? dto = null;
        if (_db.ReadWrapped(uid, year) is { } stored)
        {
            dto = JsonSerializer.Deserialize<WrappedDto>(stored.Json, Json);
        }
        else if (OpenYear(uid) == year)
        {
            // Wrapped is out but the December task hasn't reached this profile yet.
            BuildYear(year, [user], false);
            dto = _db.ReadWrapped(uid, year) is { } built ? JsonSerializer.Deserialize<WrappedDto>(built.Json, Json) : null;
        }

        if (dto is not null && withItems)
        {
            AddItems(dto, user);
        }

        return dto;
    }

    /// <summary>Builds a year's Wrapped for profiles; with <paramref name="replace"/>, again for those who have one.</summary>
    /// <param name="year">Year.</param>
    /// <param name="users">Profiles, or null for everyone.</param>
    /// <param name="replace">Whether existing ones are built again.</param>
    /// <returns>How many were built.</returns>
    public int BuildYear(int year, IReadOnlyList<User>? users, bool replace)
    {
        lock (_buildLock)
        {
            var everyone = _users.GetUsers().ToList();
            var targets = (users ?? everyone)
                .Where(u => replace || _db.ReadWrapped(u.Id.ToString("N"), year) is null)
                .ToList();
            if (targets.Count == 0)
            {
                return 0;
            }

            // Everyone's plays, with a day to spare on each side for time zones; each profile keeps its own year.
            var from = new DateTime(year, 1, 1, 0, 0, 0, DateTimeKind.Utc).AddDays(-1);
            var to = new DateTime(year, 12, 2, 0, 0, 0, DateTimeKind.Utc);
            var plays = _db.PlaysBetween(from, to).GroupBy(p => p.UserId, StringComparer.OrdinalIgnoreCase).ToDictionary(g => g.Key, g => g.ToList(), StringComparer.OrdinalIgnoreCase);

            // Video watched by everyone, for the "#2 on your server".
            var totals = everyone
                .Select(u => u.Id.ToString("N"))
                .ToDictionary(id => id, id => Rows(plays.GetValueOrDefault(id) ?? [], ZoneOf(id), year).Where(r => VideoKinds.Contains(r.Kind)).Sum(r => r.Watched), StringComparer.OrdinalIgnoreCase);

            var built = 0;
            var cache = new Cache();
            foreach (var user in targets)
            {
                var uid = user.Id.ToString("N");
                cache.NextProfile();
                try
                {
                    var dto = Build(user, year, plays.GetValueOrDefault(uid) ?? [], totals, cache);
                    if (dto is null)
                    {
                        continue;
                    }

                    _db.SaveWrapped(uid, year, JsonSerializer.Serialize(dto), DateTime.UtcNow);
                    built++;
                }
                catch (Exception ex)
                {
                    _logger.LogError(ex, "Seedy couldn't build {User}'s Wrapped {Year}", user.Username, year);
                }
            }

            return built;
        }
    }

    // The year's counted plays: 1 January to 30 November, local time, at least two minutes each.
    private static List<Row> Rows(List<PlayRecord> plays, TimeZoneInfo zone, int year)
    {
        var start = new DateTime(year, 1, 1);
        var end = new DateTime(year, 12, 1);
        var min = (long)Statistics.MinPlay.TotalMilliseconds;
        var rows = new List<Row>();
        foreach (var p in plays)
        {
            var (watched, stop) = Statistics.Span(p);
            if (watched < min)
            {
                continue;
            }

            var localStart = Local(p.StartedAt, zone);
            if (localStart < start || localStart >= end)
            {
                continue;
            }

            rows.Add(new Row(p, Statistics.KindOf(p.ItemType), watched, p.StartedAt, stop, localStart, Local(stop, zone)));
        }

        rows.Sort((a, b) => a.Start.CompareTo(b.Start));
        return rows;
    }

    private static DateTime Local(DateTime utc, TimeZoneInfo zone) => TimeZoneInfo.ConvertTimeFromUtc(DateTime.SpecifyKind(utc, DateTimeKind.Utc), zone);

    private WrappedDto? Build(User user, int year, List<PlayRecord> plays, Dictionary<string, long> totals, Cache cache)
    {
        var uid = user.Id.ToString("N");
        var zone = ZoneOf(uid);
        var all = Rows(plays, zone, year);
        var video = all.Where(r => VideoKinds.Contains(r.Kind)).ToList();
        var watchMs = video.Sum(r => r.Watched);
        if (watchMs < MinWatchMs || video.Count < 3)
        {
            return null;
        }

        var dto = new WrappedDto
        {
            Year = year,
            BuiltAt = DateTime.UtcNow.ToString("o", CultureInfo.InvariantCulture),
            Zone = _zones.GetValueOrDefault(uid) ?? zone.Id,
            WatchMs = watchMs,
            Plays = video.Count,
            MusicMs = all.Where(r => r.Kind == "music").Sum(r => r.Watched),
            Books = all.Where(r => r.Kind == "book").Select(r => r.Play.ItemId).Distinct(StringComparer.OrdinalIgnoreCase).Count()
        };

        // ── How it compares ──
        dto.PrevWatchMs = PreviousYear(uid, year, zone);
        var watchers = totals.Where(t => t.Value > 0).ToList();
        dto.Viewers = watchers.Count;
        dto.Rank = watchers.Count >= 2 ? 1 + watchers.Count(t => t.Value > watchMs) : null;

        // ── Genres ──
        var genreTime = new Dictionary<string, (string Name, double Ms, Dictionary<string, double> Names)>(StringComparer.Ordinal);
        foreach (var r in video)
        {
            var genres = cache.Genres(_library, r.Play.TitleId);
            if (genres.Count == 0)
            {
                continue;
            }

            var share = (double)r.Watched / genres.Count;
            foreach (var g in genres)
            {
                var genreKey = KeyOf(g) ?? "name:" + g.ToLowerInvariant();
                var entry = genreTime.GetValueOrDefault(genreKey, (g, 0, new Dictionary<string, double>(StringComparer.Ordinal)));
                entry.Names[g] = entry.Names.GetValueOrDefault(g) + share;
                genreTime[genreKey] = (entry.Name, entry.Ms + share, entry.Names);
            }
        }

        var genreTotal = genreTime.Values.Sum(v => v.Ms);
        if (genreTotal > 0)
        {
            dto.Genres = genreTime
                .OrderByDescending(kv => kv.Value.Ms)
                .Take(5)
                .Select(kv => new WrappedGenreDto
                {
                    Name = kv.Value.Names.OrderByDescending(n => n.Value).First().Key,
                    Key = kv.Key.StartsWith("name:", StringComparison.Ordinal) ? null : kv.Key,
                    Pct = (int)Math.Round(kv.Value.Ms * 100 / genreTotal)
                })
                .Where(g => g.Pct > 0)
                .ToList();
            dto.OtherPct = Math.Max(0, 100 - dto.Genres.Sum(g => g.Pct));
        }

        // ── Shows ──
        var episodes = video.Where(r => r.Kind == "show").ToList();
        dto.Shows = episodes
            .GroupBy(r => r.Play.TitleId, StringComparer.OrdinalIgnoreCase)
            .Select(g => new WrappedTitleDto
            {
                Id = g.Key,
                Name = g.Last().Play.TitleName,
                Episodes = g.Select(r => r.Play.ItemId).Distinct(StringComparer.OrdinalIgnoreCase).Count(),
                WatchMs = g.Sum(r => r.Watched)
            })
            .OrderByDescending(s => s.WatchMs)
            .Take(5)
            .ToList();

        var seasonDays = new List<double>();
        foreach (var finish in episodes.Where(Completed))
        {
            if (cache.Episode(_library, finish.Play.ItemId) is not { } ep || ep.ParentIndexNumber is not int season || season == 0 || ep.IndexNumber is not int number)
            {
                continue;
            }

            if (cache.LastEpisode(_library, finish.Play.TitleId, season) != number || !cache.CountSeason(finish.Play.TitleId, season))
            {
                continue;
            }

            dto.SeasonsFinished++;
            // From the season's first episode played (this year) to its last one watched to the end.
            var first = episodes
                .Where(r => r.Start <= finish.Start && string.Equals(r.Play.TitleId, finish.Play.TitleId, StringComparison.OrdinalIgnoreCase))
                .Where(r => cache.Episode(_library, r.Play.ItemId)?.ParentIndexNumber == season)
                .Select(r => r.Start)
                .DefaultIfEmpty(finish.Start)
                .Min();
            seasonDays.Add(Math.Max(0, ((finish.Play.StoppedAt ?? finish.End) - first).TotalDays));
        }

        dto.ShowsStarted = episodes
            .Where(r => cache.Episode(_library, r.Play.ItemId) is { ParentIndexNumber: 1, IndexNumber: 1 })
            .Select(r => r.Play.TitleId)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Count();

        // ── The movie ──
        var movieRows = video.Where(r => r.Kind == "movie").ToList();
        var movieViews = movieRows
            .GroupBy(r => r.Play.ItemId, StringComparer.OrdinalIgnoreCase)
            .Select(g => new
            {
                Id = g.Key,
                Name = g.Last().Play.ItemName is { Length: > 0 } n ? n : g.Last().Play.TitleName,
                Days = g.Where(Completed).Select(r => r.LocalStart.Date).Distinct().OrderBy(d => d).ToList(),
                Ms = g.Sum(r => r.Watched)
            })
            .ToList();
        dto.Movies = movieViews.Count(m => m.Days.Count > 0);
        var top = movieViews.Where(m => m.Days.Count > 0).OrderByDescending(m => m.Days.Count).ThenByDescending(m => m.Ms).FirstOrDefault();
        if (top is not null)
        {
            dto.Movie = new WrappedMovieDto
            {
                Id = top.Id,
                Name = top.Name,
                Views = top.Days.Count,
                WatchMs = top.Ms,
                Dates = top.Days.Select(d => d.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture)).ToList()
            };
        }

        // ── The biggest binge, and the time between episodes ──
        var gaps = new List<double>();
        List<Row>? best = null;
        var sitting = new List<Row>();
        void Close()
        {
            if (sitting.Count == 0)
            {
                return;
            }

            var count = sitting.Select(r => r.Play.ItemId).Distinct(StringComparer.OrdinalIgnoreCase).Count();
            var bestCount = best?.Select(r => r.Play.ItemId).Distinct(StringComparer.OrdinalIgnoreCase).Count() ?? 0;
            if (count > bestCount || (count == bestCount && best is not null && sitting[^1].End - sitting[0].Start > best[^1].End - best[0].Start))
            {
                best = [.. sitting];
            }

            sitting.Clear();
        }

        foreach (var r in episodes)
        {
            if (sitting.Count > 0)
            {
                var prev = sitting[^1];
                var gap = r.Start - (prev.Play.StoppedAt ?? prev.End);
                if (!string.Equals(prev.Play.TitleId, r.Play.TitleId, StringComparison.OrdinalIgnoreCase) || gap > SittingGap)
                {
                    Close();
                }
                else if (prev.Play.StoppedAt is not null && gap >= TimeSpan.Zero && !string.Equals(prev.Play.ItemId, r.Play.ItemId, StringComparison.OrdinalIgnoreCase))
                {
                    gaps.Add(gap.TotalSeconds);
                }
            }

            sitting.Add(r);
        }

        Close();
        if (best is { } b && b.Select(r => r.Play.ItemId).Distinct(StringComparer.OrdinalIgnoreCase).Count() >= 3)
        {
            var seasons = b.Select(r => cache.Episode(_library, r.Play.ItemId)?.ParentIndexNumber).Where(s => s is not null).Select(s => s!.Value).ToList();
            var last = cache.Episode(_library, b[^1].Play.ItemId);
            dto.Binge = new WrappedBingeDto
            {
                Id = b[0].Play.TitleId,
                Name = b[0].Play.TitleName,
                Episodes = b.Select(r => r.Play.ItemId).Distinct(StringComparer.OrdinalIgnoreCase).Count(),
                Minutes = b.GroupBy(r => r.Play.ItemId, StringComparer.OrdinalIgnoreCase).Select(g => (int)Math.Max(1, Math.Round(g.Sum(r => r.Watched) / 60_000.0))).ToList(),
                Season = seasons.Count > 0 ? seasons.Min() : null,
                SeasonTo = seasons.Count > 0 && seasons.Max() != seasons.Min() ? seasons.Max() : null,
                LastSeason = last?.ParentIndexNumber,
                LastEpisode = last?.IndexNumber,
                Start = b[0].LocalStart.ToString("yyyy-MM-dd'T'HH:mm", CultureInfo.InvariantCulture),
                End = Local(b[^1].Play.StoppedAt ?? b[^1].End, zone).ToString("yyyy-MM-dd'T'HH:mm", CultureInfo.InvariantCulture)
            };
        }

        // ── Moments: intros skipped, "Still watching?" ──
        var moments = _db.MomentsBetween(uid, TimeZoneInfo.ConvertTimeToUtc(new DateTime(year, 1, 1), zone), TimeZoneInfo.ConvertTimeToUtc(new DateTime(year, 12, 1), zone));
        var intros = moments.Where(m => m.Kind == "introSkipped").ToList();
        dto.Still = new WrappedStillDto { Asked = moments.Count(m => m.Kind == "stillAsked"), Yes = moments.Count(m => m.Kind == "stillYes") };

        // ── The clock ──
        var hours = new double[24];
        var slots = new double[144];
        var weekdays = new double[7];
        var days = new Dictionary<DateTime, double>();
        foreach (var r in video)
        {
            var span = Math.Max(1, (r.LocalEnd - r.LocalStart).TotalMilliseconds);
            var cursor = r.LocalStart;
            var guard = 0;
            do
            {
                var slotEnd = cursor.Date.AddMinutes(((cursor.Hour * 60) + cursor.Minute) / 10 * 10 + 10);
                var sliceEnd = r.LocalEnd < slotEnd ? r.LocalEnd : slotEnd;
                var share = r.LocalEnd > r.LocalStart ? r.Watched * (sliceEnd - cursor).TotalMilliseconds / span : r.Watched;
                hours[cursor.Hour] += share;
                slots[((cursor.Hour * 60) + cursor.Minute) / 10] += share;
                weekdays[((int)cursor.DayOfWeek + 6) % 7] += share;
                days[cursor.Date] = days.GetValueOrDefault(cursor.Date) + share;
                cursor = slotEnd;
            }
            while (cursor < r.LocalEnd && ++guard < 400);
        }

        var peak = Array.IndexOf(slots, slots.Max());
        var peakHour = peak / 6;
        var kind = peakHour >= 22 || peakHour < 4 ? "night" : peakHour < 12 ? "morning" : peakHour < 18 ? "afternoon" : "evening";
        var window = kind switch
        {
            "night" => new[] { 22, 23, 0, 1, 2, 3 },
            "morning" => Enumerable.Range(4, 8).ToArray(),
            "afternoon" => Enumerable.Range(12, 6).ToArray(),
            _ => Enumerable.Range(18, 4).ToArray()
        };
        var hoursTotal = Math.Max(1, hours.Sum());
        dto.Clock = new WrappedClockDto
        {
            Hours = hours.Select(h => (long)Math.Round(h)).ToList(),
            Peak = $"{peak / 6:00}:{peak % 6 * 10:00}",
            Kind = kind,
            Pct = (int)Math.Round(window.Sum(h => hours[h]) * 100 / hoursTotal),
            Day = Array.IndexOf(weekdays, weekdays.Max()) + 1
        };

        // ── The personality ──
        var stats = new Dictionary<string, WrappedStatDto>(StringComparer.Ordinal);
        void Stat(string key, double value, double? extra = null)
        {
            if (value > 0)
            {
                stats[key] = new WrappedStatDto { Key = key, Value = value, Extra = extra };
            }
        }

        var avgSeason = seasonDays.Count > 0 ? Math.Round(seasonDays.Average(), 1) : 0;
        Stat("seasonDays", Math.Max(avgSeason, seasonDays.Count > 0 ? 0.1 : 0));
        Stat("introsSkipped", intros.Count, intros.Sum(m => m.Value));
        Stat("gapSeconds", gaps.Count > 0 ? Math.Max(1, Math.Round(gaps.Average())) : 0);
        Stat("seasonsFinished", dto.SeasonsFinished);
        Stat("movies", dto.Movies);
        var titles = video.Where(r => r.Kind is "show" or "movie").Select(r => r.Play.TitleId).Distinct(StringComparer.OrdinalIgnoreCase).Count();
        Stat("titles", titles);
        Stat("genres", genreTime.Count);
        var rewatches = movieViews.Sum(m => Math.Max(0, m.Days.Count - 1))
            + episodes.Where(Completed).GroupBy(r => r.Play.ItemId, StringComparer.OrdinalIgnoreCase).Sum(g => Math.Max(0, g.Select(r => r.LocalStart.Date).Distinct().Count() - 1));
        Stat("rewatches", rewatches);
        Stat("streak", Streak(days.Keys));
        Stat("activeDays", days.Count);
        Stat("bigDay", days.Count > 0 ? Math.Round(days.Values.Max()) : 0);
        var weekendPct = Math.Round((weekdays[5] + weekdays[6]) * 100 / Math.Max(1, weekdays.Sum()));
        Stat("weekendPct", weekendPct);
        Stat("showsStarted", dto.ShowsStarted);

        var movieShare = (double)movieRows.Sum(r => r.Watched) / watchMs;
        var key = dto.SeasonsFinished >= 2 && avgSeason <= 7 ? "sprinter"
            : rewatches >= 5 ? "rewatcher"
            : movieShare >= 0.5 && dto.Movies >= 8 ? "cinephile"
            : titles >= 25 && genreTime.Count >= 8 ? "explorer"
            : days.Count >= 120 ? "regular"
            : dto.SeasonsFinished >= 1 && avgSeason > 30 ? "slowburner"
            : weekendPct >= 50 ? "weekender"
            : "easygoing";
        string[] order = key switch
        {
            "sprinter" => ["seasonDays", "introsSkipped", "gapSeconds", "seasonsFinished", "streak", "activeDays"],
            "rewatcher" => ["rewatches", "streak", "titles", "activeDays", "bigDay"],
            "cinephile" => ["movies", "bigDay", "genres", "titles", "activeDays"],
            "explorer" => ["titles", "genres", "showsStarted", "movies", "activeDays"],
            "regular" => ["activeDays", "streak", "bigDay", "titles"],
            "slowburner" => ["seasonDays", "seasonsFinished", "activeDays", "titles", "bigDay"],
            "weekender" => ["weekendPct", "bigDay", "activeDays", "titles"],
            _ => ["activeDays", "titles", "bigDay", "streak", "genres"]
        };
        dto.Personality = new WrappedPersonalityDto
        {
            Key = key,
            Stats = order.Where(stats.ContainsKey).Select(k => stats[k]).Take(3).ToList()
        };

        // ── Stories, in order ──
        dto.Stories.Add("cover");
        dto.Stories.Add("hours");
        if (dto.Genres.Count > 0)
        {
            dto.Stories.Add("genres");
        }

        if (dto.Shows.Count > 0)
        {
            dto.Stories.Add("shows");
        }

        if (dto.Movie is not null)
        {
            dto.Stories.Add("movie");
        }

        if (dto.Binge is not null)
        {
            dto.Stories.Add("binge");
        }

        dto.Stories.Add("clock");
        dto.Stories.Add("personality");
        dto.Stories.Add("summary");
        return dto;
    }

    // Watched to the end: marked finished, or played to 90 % of its length.
    private static bool Completed(Row r)
        => r.Play.Finished || (r.Play.RuntimeTicks > 0 && (r.Play.PositionTicks >= r.Play.RuntimeTicks * 0.9 || r.Watched >= r.Play.RuntimeTicks / TimeSpan.TicksPerMillisecond * 0.85));

    private static int Streak(IEnumerable<DateTime> days)
    {
        var best = 0;
        var run = 0;
        DateTime? prev = null;
        foreach (var d in days.OrderBy(d => d))
        {
            run = prev is { } p && (d - p).TotalDays == 1 ? run + 1 : 1;
            best = Math.Max(best, run);
            prev = d;
        }

        return best;
    }

    // The year before: from its Wrapped, or from the log when the log still covers it.
    private long? PreviousYear(string uid, int year, TimeZoneInfo zone)
    {
        if (_db.ReadWrapped(uid, year - 1) is { } stored)
        {
            return JsonSerializer.Deserialize<WrappedDto>(stored.Json, Json)?.WatchMs;
        }

        var start = TimeZoneInfo.ConvertTimeToUtc(new DateTime(year - 1, 1, 1), zone);
        if (_db.FirstPlayAt() is not { } first || first > start)
        {
            return null;
        }

        var plays = _db.PlaysBetween(start.AddDays(-1), new DateTime(year - 1, 12, 2, 0, 0, 0, DateTimeKind.Utc))
            .Where(p => string.Equals(p.UserId, uid, StringComparison.OrdinalIgnoreCase))
            .ToList();
        return Rows(plays, zone, year - 1).Where(r => VideoKinds.Contains(r.Kind)).Sum(r => r.Watched);
    }

    /// <summary>Folds a genre name to the key the apps know, or null.</summary>
    /// <param name="genre">Genre name.</param>
    /// <returns>Key.</returns>
    public static string? KeyOf(string genre)
    {
        var folded = new StringBuilder();
        foreach (var ch in genre.Trim().ToLowerInvariant().Normalize(NormalizationForm.FormD))
        {
            if (CharUnicodeInfo.GetUnicodeCategory(ch) != UnicodeCategory.NonSpacingMark)
            {
                folded.Append(ch);
            }
        }

        return GenreKeys.GetValueOrDefault(folded.ToString().Replace(" and ", " & ", StringComparison.Ordinal));
    }

    // The titles' items, for their pictures, as the profile may see them now.
    private void AddItems(WrappedDto dto, User user)
    {
        var ids = dto.Shows.Select(s => s.Id)
            .Concat(dto.Movie is { } m ? [m.Id] : [])
            .Concat(dto.Binge is { } b ? [b.Id] : [])
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .Select(id => Guid.TryParse(id, out var g) ? _library.GetItemById(g) : null)
            .Where(i => i is not null && i.IsVisible(user))
            .Cast<BaseItem>()
            .ToList();
        var items = _home.ToDtos(ids, user).ToDictionary(i => i.Id.ToString("N"), StringComparer.OrdinalIgnoreCase);
        foreach (var s in dto.Shows)
        {
            s.Item = items.GetValueOrDefault(s.Id);
        }

        if (dto.Movie is { } movie)
        {
            movie.Item = items.GetValueOrDefault(movie.Id);
        }

        if (dto.Binge is { } binge)
        {
            binge.Item = items.GetValueOrDefault(binge.Id);
        }
    }

    /// <summary>Library lookups shared by every profile built in one go.</summary>
    private sealed class Cache
    {
        private readonly Dictionary<string, IReadOnlyList<string>> _genres = new(StringComparer.OrdinalIgnoreCase);
        private readonly Dictionary<string, Episode?> _episodes = new(StringComparer.OrdinalIgnoreCase);
        private readonly Dictionary<string, Dictionary<int, int>> _seasonEnds = new(StringComparer.OrdinalIgnoreCase);
        private readonly HashSet<string> _counted = new(StringComparer.OrdinalIgnoreCase);

        public IReadOnlyList<string> Genres(ILibraryManager library, string titleId)
        {
            if (!_genres.TryGetValue(titleId, out var genres))
            {
                genres = Guid.TryParse(titleId, out var id) && library.GetItemById(id) is { } item ? item.Genres ?? [] : [];
                _genres[titleId] = genres;
            }

            return genres;
        }

        public Episode? Episode(ILibraryManager library, string itemId)
        {
            if (!_episodes.TryGetValue(itemId, out var ep))
            {
                ep = Guid.TryParse(itemId, out var id) ? library.GetItemById(id) as Episode : null;
                _episodes[itemId] = ep;
            }

            return ep;
        }

        // The number of each season's last episode in the library.
        public int? LastEpisode(ILibraryManager library, string seriesId, int season)
        {
            if (!_seasonEnds.TryGetValue(seriesId, out var ends))
            {
                ends = [];
                if (Guid.TryParse(seriesId, out var id))
                {
                    var eps = library.GetItemList(new InternalItemsQuery
                    {
                        AncestorIds = [id],
                        IncludeItemTypes = [BaseItemKind.Episode],
                        IsVirtualItem = false,
                        Recursive = true,
                        DtoOptions = new DtoOptions(false)
                    });
                    foreach (var e in eps.OfType<Episode>())
                    {
                        if (e.ParentIndexNumber is int s && e.IndexNumber is int n)
                        {
                            ends[s] = Math.Max(ends.GetValueOrDefault(s), e.IndexNumberEnd ?? n);
                        }
                    }
                }

                _seasonEnds[seriesId] = ends;
            }

            return ends.TryGetValue(season, out var last) ? last : null;
        }

        // Each season finished counts once per profile, however often its finale was played. Reset per profile.
        public bool CountSeason(string seriesId, int season) => _counted.Add(seriesId + ":" + season.ToString(CultureInfo.InvariantCulture));

        public void NextProfile() => _counted.Clear();
    }
}
