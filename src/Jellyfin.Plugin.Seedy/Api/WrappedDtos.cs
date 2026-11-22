using System.Collections.Generic;
using System.Text.Json.Serialization;
using MediaBrowser.Model.Dto;

#pragma warning disable CS1591 // DTO members are self-describing.

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>Which Wrapped years a profile can open, and the one to offer on Home in December.</summary>
public sealed class WrappedStatusDto
{
    /// <summary>Gets or sets the years with a Wrapped, newest first.</summary>
    [JsonPropertyName("years")] public List<int> Years { get; set; } = [];

    /// <summary>Gets or sets this year's Wrapped, offered on Home while it's December; null otherwise.</summary>
    [JsonPropertyName("current")] public WrappedTeaserDto? Current { get; set; }
}

/// <summary>What the Home banner says about this year's Wrapped.</summary>
public sealed class WrappedTeaserDto
{
    [JsonPropertyName("year")] public int Year { get; set; }

    /// <summary>Gets or sets the video watched in the year, in milliseconds.</summary>
    [JsonPropertyName("watchMs")] public long WatchMs { get; set; }

    /// <summary>Gets or sets the number of stories.</summary>
    [JsonPropertyName("stories")] public int Stories { get; set; }
}

/// <summary>
/// A profile's Seedy Wrapped for one year: built once in December from the play log (1 January to 30 November in the
/// profile's time zone) and kept, so it outlives the log's retention. Clients write every sentence from these numbers.
/// </summary>
public sealed class WrappedDto
{
    /// <summary>The snapshot's format. Wrapped are kept for years, so later versions of Seedy read older ones by it.</summary>
    [JsonPropertyName("v")] public int Version { get; set; } = 1;

    [JsonPropertyName("year")] public int Year { get; set; }

    /// <summary>Gets or sets when it was built (UTC, ISO 8601).</summary>
    [JsonPropertyName("builtAt")] public string BuiltAt { get; set; } = string.Empty;

    /// <summary>Gets or sets the time zone its days and hours are in.</summary>
    [JsonPropertyName("zone")] public string Zone { get; set; } = string.Empty;

    /// <summary>Gets or sets the stories to show, in order: cover, hours, lover, genres, shows, movie, binge, clock,
    /// personality, summary. Stories without enough to say are left out; clients skip names they don't know.</summary>
    [JsonPropertyName("stories")] public List<string> Stories { get; set; } = [];

    /// <summary>Gets or sets the video watched (shows, movies, Live TV, other videos), in milliseconds.</summary>
    [JsonPropertyName("watchMs")] public long WatchMs { get; set; }

    /// <summary>Gets or sets the plays counted.</summary>
    [JsonPropertyName("plays")] public int Plays { get; set; }

    /// <summary>Gets or sets the video watched the year before, when Seedy knows it.</summary>
    [JsonPropertyName("prevWatchMs")] public long? PrevWatchMs { get; set; }

    /// <summary>Gets or sets the profile's place among everyone on the server by watch time (1 = most), when two or more watched.</summary>
    [JsonPropertyName("rank")] public int? Rank { get; set; }

    /// <summary>Gets or sets how many profiles watched anything in the year.</summary>
    [JsonPropertyName("viewers")] public int Viewers { get; set; }

    [JsonPropertyName("genres")] public List<WrappedGenreDto> Genres { get; set; } = [];

    /// <summary>Gets or sets the percentage left for every other genre.</summary>
    [JsonPropertyName("otherPct")] public int OtherPct { get; set; }

    [JsonPropertyName("shows")] public List<WrappedTitleDto> Shows { get; set; } = [];

    /// <summary>Gets or sets the seasons whose last episode was watched to the end in the year.</summary>
    [JsonPropertyName("seasonsFinished")] public int SeasonsFinished { get; set; }

    /// <summary>Gets or sets the shows started from their first episode in the year.</summary>
    [JsonPropertyName("showsStarted")] public int ShowsStarted { get; set; }

    [JsonPropertyName("movie")] public WrappedMovieDto? Movie { get; set; }

    /// <summary>Gets or sets the different movies watched.</summary>
    [JsonPropertyName("movies")] public int Movies { get; set; }

    /// <summary>Gets or sets the different movies played (watched to the end or not).</summary>
    [JsonPropertyName("moviesPlayed")] public int MoviesPlayed { get; set; }

    /// <summary>Gets or sets the movies watched on two days or more.</summary>
    [JsonPropertyName("moviesRewatched")] public int MoviesRewatched { get; set; }

    /// <summary>Gets or sets the time spent on movies, in milliseconds.</summary>
    [JsonPropertyName("movieMs")] public long MovieMs { get; set; }

    /// <summary>Gets or sets the different shows watched (at least one episode).</summary>
    [JsonPropertyName("showsWatched")] public int ShowsWatched { get; set; }

    /// <summary>Gets or sets the shows whose last episode in the library was watched to the end in the year.</summary>
    [JsonPropertyName("showsFinished")] public int ShowsFinished { get; set; }

    /// <summary>Gets or sets the different episodes watched.</summary>
    [JsonPropertyName("episodes")] public int Episodes { get; set; }

    /// <summary>Gets or sets the time spent on shows, in milliseconds.</summary>
    [JsonPropertyName("showMs")] public long ShowMs { get; set; }

    [JsonPropertyName("binge")] public WrappedBingeDto? Binge { get; set; }

    /// <summary>Gets or sets how often "Still watching?" was asked, and answered with yes.</summary>
    [JsonPropertyName("still")] public WrappedStillDto Still { get; set; } = new();

    [JsonPropertyName("clock")] public WrappedClockDto? Clock { get; set; }

    [JsonPropertyName("personality")] public WrappedPersonalityDto Personality { get; set; } = new();

    /// <summary>Gets or sets the music listened to, in milliseconds.</summary>
    [JsonPropertyName("musicMs")] public long MusicMs { get; set; }

    /// <summary>Gets or sets the different books read or listened to.</summary>
    [JsonPropertyName("books")] public int Books { get; set; }

    /// <summary>The titles asked for through Seerr this year (absent without Seerr, or from Wrapped built before it was).</summary>
    [JsonPropertyName("requests")] public WrappedRequestsDto? Requests { get; set; }

    /// <summary>The oddest of those: barely rated, badly rated or very old.</summary>
    [JsonPropertyName("oddest")] public WrappedOddestDto? Oddest { get; set; }
}

public sealed class WrappedRequestsDto
{
    [JsonPropertyName("count")] public int Count { get; set; }

    /// <summary>On the server now, at least the seasons asked for.</summary>
    [JsonPropertyName("here")] public int Here { get; set; }

    /// <summary>Waiting for approval, approved or downloading.</summary>
    [JsonPropertyName("coming")] public int Coming { get; set; }

    [JsonPropertyName("declined")] public int Declined { get; set; }

    /// <summary>The request that landed soonest after it was made.</summary>
    [JsonPropertyName("fastest")] public WrappedArrivalDto? Fastest { get; set; }

    /// <summary>The genre asked for most, when more than one request had it.</summary>
    [JsonPropertyName("genre")] public WrappedGenreDto? Genre { get; set; }
}

public sealed class WrappedArrivalDto
{
    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;

    [JsonPropertyName("season")] public int? Season { get; set; }

    [JsonPropertyName("seasonTo")] public int? SeasonTo { get; set; }

    [JsonPropertyName("ms")] public long Ms { get; set; }
}

public sealed class WrappedOddestDto
{
    [JsonPropertyName("tmdbId")] public int TmdbId { get; set; }

    [JsonPropertyName("mediaType")] public string MediaType { get; set; } = "movie";

    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;

    [JsonPropertyName("year")] public int? Year { get; set; }

    /// <summary>TMDB's average, out of 10.</summary>
    [JsonPropertyName("rating")] public double Rating { get; set; }

    [JsonPropertyName("votes")] public int Votes { get; set; }

    /// <summary>People who asked Seerr for it, this profile included.</summary>
    [JsonPropertyName("requesters")] public int Requesters { get; set; }

    /// <summary>How odd, 0–100.</summary>
    [JsonPropertyName("score")] public int Score { get; set; }

    [JsonPropertyName("posterPath")] public string? PosterPath { get; set; }

    /// <summary>The title in the library, when it's there.</summary>
    [JsonPropertyName("itemId")] public string? ItemId { get; set; }

    [JsonPropertyName("item")] public BaseItemDto? Item { get; set; }
}

public sealed class WrappedGenreDto
{
    /// <summary>Gets or sets the genre as the library names it.</summary>
    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;

    /// <summary>Gets or sets a language-independent key for known genres (thriller, drama, scifi…), else null.</summary>
    [JsonPropertyName("key")] public string? Key { get; set; }

    /// <summary>Gets or sets its share of the video watched, 0-100.</summary>
    [JsonPropertyName("pct")] public int Pct { get; set; }
}

public sealed class WrappedTitleDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = string.Empty;

    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;

    /// <summary>Gets or sets the different episodes watched.</summary>
    [JsonPropertyName("episodes")] public int Episodes { get; set; }

    [JsonPropertyName("watchMs")] public long WatchMs { get; set; }

    /// <summary>Gets or sets the item, for its pictures; null once it left the library.</summary>
    [JsonPropertyName("item")] public BaseItemDto? Item { get; set; }
}

public sealed class WrappedMovieDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = string.Empty;

    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;

    /// <summary>Gets or sets how many times it was watched (plays that reached the end, at most one a day).</summary>
    [JsonPropertyName("views")] public int Views { get; set; }

    /// <summary>Gets or sets the time spent on it, all plays together.</summary>
    [JsonPropertyName("watchMs")] public long WatchMs { get; set; }

    /// <summary>Gets or sets the day of each view (yyyy-MM-dd), oldest first.</summary>
    [JsonPropertyName("dates")] public List<string> Dates { get; set; } = [];

    /// <summary>Its genres' keys (thriller, drama…), for what Medu says about it.</summary>
    [JsonPropertyName("genres")] public List<string>? Genres { get; set; }

    [JsonPropertyName("item")] public BaseItemDto? Item { get; set; }
}

public sealed class WrappedBingeDto
{
    /// <summary>Gets or sets the series id.</summary>
    [JsonPropertyName("id")] public string Id { get; set; } = string.Empty;

    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;

    /// <summary>Gets or sets the different episodes watched in one go.</summary>
    [JsonPropertyName("episodes")] public int Episodes { get; set; }

    /// <summary>Gets or sets the minutes watched of each episode, in the order they played.</summary>
    [JsonPropertyName("minutes")] public List<int> Minutes { get; set; } = [];

    /// <summary>Gets or sets the first season played in it.</summary>
    [JsonPropertyName("season")] public int? Season { get; set; }

    /// <summary>Gets or sets the last season played in it, when it crossed into another.</summary>
    [JsonPropertyName("seasonTo")] public int? SeasonTo { get; set; }

    /// <summary>Gets or sets the last episode's number, as S{season} E{episode}.</summary>
    [JsonPropertyName("lastSeason")] public int? LastSeason { get; set; }

    [JsonPropertyName("lastEpisode")] public int? LastEpisode { get; set; }

    /// <summary>Gets or sets when it started, local time (yyyy-MM-ddTHH:mm).</summary>
    [JsonPropertyName("start")] public string Start { get; set; } = string.Empty;

    /// <summary>Gets or sets when it ended, local time (yyyy-MM-ddTHH:mm).</summary>
    [JsonPropertyName("end")] public string End { get; set; } = string.Empty;

    [JsonPropertyName("item")] public BaseItemDto? Item { get; set; }
}

public sealed class WrappedStillDto
{
    [JsonPropertyName("asked")] public int Asked { get; set; }

    [JsonPropertyName("yes")] public int Yes { get; set; }
}

public sealed class WrappedClockDto
{
    /// <summary>Gets or sets the video watched in each hour of the day (local, 0-23), in milliseconds.</summary>
    [JsonPropertyName("hours")] public List<long> Hours { get; set; } = [];

    /// <summary>Gets or sets the busiest ten minutes of the day (HH:mm).</summary>
    [JsonPropertyName("peak")] public string Peak { get; set; } = string.Empty;

    /// <summary>Gets or sets night (22:00-04:00), morning (04:00-12:00), afternoon (12:00-18:00) or evening (18:00-22:00):
    /// the part of the day the peak falls in.</summary>
    [JsonPropertyName("kind")] public string Kind { get; set; } = "night";

    /// <summary>Gets or sets the share of the watching in that part of the day, 0-100.</summary>
    [JsonPropertyName("pct")] public int Pct { get; set; }

    /// <summary>Gets or sets the busiest day of the week, ISO numbering (1 = Monday … 7 = Sunday).</summary>
    [JsonPropertyName("day")] public int Day { get; set; }
}

public sealed class WrappedPersonalityDto
{
    /// <summary>Gets or sets sprinter, rewatcher, cinephile, explorer, slowburner, regular, weekender or easygoing.</summary>
    [JsonPropertyName("key")] public string Key { get; set; } = "easygoing";

    /// <summary>Gets or sets the three numbers that back it up.</summary>
    [JsonPropertyName("stats")] public List<WrappedStatDto> Stats { get; set; } = [];
}

/// <summary>One number on the personality story. Keys: seasonDays (days), introsSkipped (count, extra = ms saved),
/// gapSeconds (seconds), seasonsFinished, movies, titles, genres, rewatches, streak (days), activeDays, bigDay (ms),
/// weekendPct (0-100), showsStarted.</summary>
public sealed class WrappedStatDto
{
    [JsonPropertyName("key")] public string Key { get; set; } = string.Empty;

    [JsonPropertyName("value")] public double Value { get; set; }

    [JsonPropertyName("extra")] public double? Extra { get; set; }
}

/// <summary>A moment reported by a player.</summary>
public sealed class MomentDto
{
    /// <summary>Gets or sets introSkipped, stillAsked, stillYes or stillStop.</summary>
    [JsonPropertyName("kind")] public string Kind { get; set; } = string.Empty;

    [JsonPropertyName("itemId")] public string? ItemId { get; set; }

    /// <summary>Gets or sets the milliseconds an intro skip saved.</summary>
    [JsonPropertyName("ms")] public long? Ms { get; set; }
}
