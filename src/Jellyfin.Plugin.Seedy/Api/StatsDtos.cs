using System;
using System.Collections.Generic;
using System.Text.Json.Serialization;
using MediaBrowser.Model.Dto;

namespace Jellyfin.Plugin.Seedy.Api;

// Wire types for /Seedy/Admin/Stats, matching web/src/screens/admin/stats/api.ts.
// Times are UTC; dates ("yyyy-MM-dd") are in the time zone the page asked for.

#pragma warning disable SA1402, SA1649, CA1002, CA2227, SA1600, CA1819

public sealed class StatsDto
{
    [JsonPropertyName("range")] public string Range { get; set; } = "30d";
    [JsonPropertyName("bucket")] public string Bucket { get; set; } = "day";
    [JsonPropertyName("from")] public string From { get; set; } = string.Empty;
    [JsonPropertyName("to")] public string To { get; set; } = string.Empty;
    [JsonPropertyName("today")] public string Today { get; set; } = string.Empty;
    [JsonPropertyName("retentionDays")] public int RetentionDays { get; set; }
    [JsonPropertyName("kpis")] public StatsKpisDto Kpis { get; set; } = new();
    [JsonPropertyName("buckets")] public List<StatsBucketDto> Buckets { get; set; } = [];
    [JsonPropertyName("methods")] public Dictionary<string, int> Methods { get; set; } = [];
    [JsonPropertyName("reasons")] public List<StatsReasonDto> Reasons { get; set; } = [];
    [JsonPropertyName("kinds")] public Dictionary<string, long> Kinds { get; set; } = [];
    [JsonPropertyName("heat")] public double[][] Heat { get; set; } = [];
    [JsonPropertyName("heatViewers")] public int[][] HeatViewers { get; set; } = [];
    [JsonPropertyName("viewers")] public List<StatsViewerDto> Viewers { get; set; } = [];
    [JsonPropertyName("devices")] public List<StatsFamilyDto> Devices { get; set; } = [];
    [JsonPropertyName("top")] public List<StatsTitleDto> Top { get; set; } = [];
    [JsonPropertyName("filters")] public StatsFiltersDto Filters { get; set; } = new();
}

public sealed class StatsKpisDto
{
    [JsonPropertyName("watchMs")] public long WatchMs { get; set; }
    [JsonPropertyName("prevWatchMs")] public long? PrevWatchMs { get; set; }
    [JsonPropertyName("plays")] public int Plays { get; set; }
    [JsonPropertyName("prevPlays")] public int? PrevPlays { get; set; }
    [JsonPropertyName("viewers")] public int Viewers { get; set; }
    [JsonPropertyName("totalUsers")] public int TotalUsers { get; set; }
    [JsonPropertyName("missing")] public List<string> Missing { get; set; } = [];
    [JsonPropertyName("peak")] public StatsPeakDto? Peak { get; set; }
    [JsonPropertyName("transcodePct")] public double? TranscodePct { get; set; }
    [JsonPropertyName("prevTranscodePct")] public double? PrevTranscodePct { get; set; }
}

public sealed class StatsPeakDto
{
    [JsonPropertyName("streams")] public int Streams { get; set; }
    [JsonPropertyName("at")] public DateTime At { get; set; }
    [JsonPropertyName("bitrate")] public long? Bitrate { get; set; }
}

public sealed class StatsBucketDto
{
    [JsonPropertyName("start")] public string Start { get; set; } = string.Empty;
    [JsonPropertyName("watchMs")] public long WatchMs { get; set; }
    [JsonPropertyName("plays")] public int Plays { get; set; }
    [JsonPropertyName("kinds")] public Dictionary<string, long> Kinds { get; set; } = [];
}

public sealed class StatsReasonDto
{
    [JsonPropertyName("key")] public string Key { get; set; } = string.Empty;
    [JsonPropertyName("plays")] public int Plays { get; set; }
    [JsonPropertyName("codec")] public string? Codec { get; set; }
    [JsonPropertyName("devices")] public List<string> Devices { get; set; } = [];
    [JsonPropertyName("capMbps")] public double? CapMbps { get; set; }
}

public sealed class StatsViewerDto
{
    [JsonPropertyName("userId")] public string UserId { get; set; } = string.Empty;
    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;
    [JsonPropertyName("imageTag")] public string? ImageTag { get; set; }
    [JsonPropertyName("index")] public int Index { get; set; }
    [JsonPropertyName("watchMs")] public long WatchMs { get; set; }
    [JsonPropertyName("plays")] public int Plays { get; set; }
    [JsonPropertyName("device")] public string? Device { get; set; }
    [JsonPropertyName("deviceCount")] public int DeviceCount { get; set; }
    [JsonPropertyName("enabled")] public bool Enabled { get; set; }
}

public sealed class StatsFamilyDto
{
    [JsonPropertyName("key")] public string Key { get; set; } = string.Empty;
    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;
    [JsonPropertyName("apps")] public List<string> Apps { get; set; } = [];
    [JsonPropertyName("watchMs")] public long WatchMs { get; set; }
}

public sealed class StatsTitleDto
{
    [JsonPropertyName("titleId")] public string TitleId { get; set; } = string.Empty;
    [JsonPropertyName("kind")] public string Kind { get; set; } = string.Empty;
    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;
    [JsonPropertyName("watchMs")] public long WatchMs { get; set; }
    [JsonPropertyName("plays")] public int Plays { get; set; }
    [JsonPropertyName("viewers")] public int Viewers { get; set; }
    [JsonPropertyName("items")] public int Items { get; set; }
    [JsonPropertyName("item")] public BaseItemDto? Item { get; set; }
}

public sealed class StatsFiltersDto
{
    [JsonPropertyName("users")] public List<StatsOptionDto> Users { get; set; } = [];
    [JsonPropertyName("libraries")] public List<StatsOptionDto> Libraries { get; set; } = [];
    [JsonPropertyName("devices")] public List<string> Devices { get; set; } = [];
}

public sealed class StatsOptionDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = string.Empty;
    [JsonPropertyName("name")] public string Name { get; set; } = string.Empty;
}

public sealed class StatsDayDto
{
    [JsonPropertyName("date")] public string Date { get; set; } = string.Empty;
    [JsonPropertyName("today")] public string Today { get; set; } = string.Empty;
    [JsonPropertyName("start")] public DateTime Start { get; set; }
    [JsonPropertyName("end")] public DateTime End { get; set; }
    [JsonPropertyName("avgDayMs")] public long AvgDayMs { get; set; }
    [JsonPropertyName("rank")] public int Rank { get; set; }
    [JsonPropertyName("daysRanked")] public int DaysRanked { get; set; }
    [JsonPropertyName("totalUsers")] public int TotalUsers { get; set; }
    [JsonPropertyName("users")] public List<StatsViewerDto> Users { get; set; } = [];
    [JsonPropertyName("sessions")] public List<StatsSessionDto> Sessions { get; set; } = [];
}

public sealed class StatsSessionDto
{
    [JsonPropertyName("id")] public string Id { get; set; } = string.Empty;
    [JsonPropertyName("userId")] public string UserId { get; set; } = string.Empty;
    [JsonPropertyName("kind")] public string Kind { get; set; } = string.Empty;
    [JsonPropertyName("itemType")] public string ItemType { get; set; } = string.Empty;
    [JsonPropertyName("itemId")] public string ItemId { get; set; } = string.Empty;
    [JsonPropertyName("titleId")] public string TitleId { get; set; } = string.Empty;
    [JsonPropertyName("title")] public string Title { get; set; } = string.Empty;
    [JsonPropertyName("itemName")] public string ItemName { get; set; } = string.Empty;
    [JsonPropertyName("season")] public int? Season { get; set; }
    [JsonPropertyName("episode")] public int? Episode { get; set; }
    [JsonPropertyName("year")] public int? Year { get; set; }
    [JsonPropertyName("artist")] public string? Artist { get; set; }
    [JsonPropertyName("start")] public DateTime Start { get; set; }
    [JsonPropertyName("end")] public DateTime End { get; set; }
    [JsonPropertyName("watchMs")] public long WatchMs { get; set; }
    [JsonPropertyName("percent")] public double? Percent { get; set; }
    [JsonPropertyName("finished")] public bool Finished { get; set; }
    [JsonPropertyName("device")] public string? Device { get; set; }
    [JsonPropertyName("method")] public string? Method { get; set; }
    [JsonPropertyName("bitrate")] public long? Bitrate { get; set; }
    [JsonPropertyName("count")] public int Count { get; set; } = 1;
}

#pragma warning restore SA1402, SA1649, CA1002, CA2227, SA1600, CA1819
