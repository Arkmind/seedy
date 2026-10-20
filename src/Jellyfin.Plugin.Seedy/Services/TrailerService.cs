using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Plugin.Seedy.Api;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.Movies;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Model.Entities;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>A trailer on YouTube.</summary>
/// <param name="Key">YouTube video id.</param>
/// <param name="Name">Its title.</param>
/// <param name="From">Where it was found: "jellyfin" (the item's remote trailers) or "tmdb" (through Seerr).</param>
public sealed record TrailerVideo(string Key, string Name, string From);

/// <summary>
/// Finds the trailer to play behind a title on Home: a local trailer file the browser can play, else the YouTube
/// trailers in Jellyfin's metadata (from TMDB), else TMDB's video list through Seerr, which holds the TMDB key.
/// IMDb has no public trailer API, so it isn't asked.
/// </summary>
public sealed partial class TrailerService
{
    private static readonly TimeSpan FoundTtl = TimeSpan.FromHours(12);
    private static readonly TimeSpan NoneTtl = TimeSpan.FromHours(2);
    private static readonly TimeSpan FailedTtl = TimeSpan.FromMinutes(5);
    private static readonly string[] BrowserFiles = [".mp4", ".m4v", ".mov", ".webm"];
    private static readonly string[] BrowserCodecs = ["h264", "avc", "vp8", "vp9", "av1"];

    private readonly SeerrService _seerr;
    private readonly ILogger<TrailerService> _logger;
    private readonly ConcurrentDictionary<Guid, (DateTime Until, List<TrailerVideo> Videos)> _cache = new();

    /// <summary>
    /// Initializes a new instance of the <see cref="TrailerService"/> class.
    /// </summary>
    /// <param name="seerr">Seerr service.</param>
    /// <param name="logger">Logger.</param>
    public TrailerService(SeerrService seerr, ILogger<TrailerService> logger)
    {
        _seerr = seerr;
        _logger = logger;
    }

    [GeneratedRegex(@"(?:youtube(?:-nocookie)?\.com/(?:watch\?(?:[^#]*&)?v=|embed/|shorts/|v/)|youtu\.be/)([A-Za-z0-9_-]{11})", RegexOptions.IgnoreCase)]
    private static partial Regex YouTubeUrl();

    /// <summary>Picks the trailer for a title the person can see.</summary>
    /// <param name="item">The title (an episode or season stands for its show).</param>
    /// <param name="user">The person, for parental limits on local trailers.</param>
    /// <param name="ct">Cancellation.</param>
    /// <returns>The trailer, or null when there's none.</returns>
    public async Task<TrailerDto?> ResolveAsync(BaseItem item, User user, CancellationToken ct)
    {
        var title = item switch
        {
            Episode e => (BaseItem?)e.Series ?? item,
            Season s => (BaseItem?)s.Series ?? item,
            _ => item
        };

        var videos = await YouTubeAsync(title, ct).ConfigureAwait(false);
        var alternates = videos.Select(v => new TrailerAltDto { Key = v.Key, Name = v.Name }).ToList();

        var local = LocalTrailer(title, user);
        if (local is not null)
        {
            // YouTube stays as the fallback when the file won't play in this browser after all.
            return new TrailerDto { Source = "local", ItemId = local.Id.ToString("N"), Name = local.Name, From = "jellyfin", Alternates = alternates.Take(4).ToList() };
        }

        if (videos.Count == 0)
        {
            return null;
        }

        var first = videos[0];
        return new TrailerDto { Source = "youtube", Key = first.Key, Name = first.Name, From = first.From, Alternates = alternates.Skip(1).Take(4).ToList() };
    }

    /// <summary>A local trailer in a container and codec browsers play without transcoding.</summary>
    private static BaseItem? LocalTrailer(BaseItem title, User user)
    {
        try
        {
            foreach (var extra in title.GetExtras([ExtraType.Trailer], user))
            {
                if (extra is not Video video || string.IsNullOrEmpty(video.Path) || video.IsPlaceHolder)
                {
                    continue;
                }

                var ext = Path.GetExtension(video.Path).ToLowerInvariant();
                if (!BrowserFiles.Contains(ext))
                {
                    continue;
                }

                var codec = video.GetDefaultVideoStream()?.Codec?.ToLowerInvariant();
                if (codec is null || BrowserCodecs.Contains(codec))
                {
                    return video;
                }
            }
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            // A broken extra shouldn't cost the title its YouTube trailer.
        }

        return null;
    }

    /// <summary>YouTube trailers for a title, best first, cached per title.</summary>
    private async Task<List<TrailerVideo>> YouTubeAsync(BaseItem title, CancellationToken ct)
    {
        if (_cache.TryGetValue(title.Id, out var hit) && DateTime.UtcNow < hit.Until)
        {
            return hit.Videos;
        }

        var videos = FromMetadata(title);
        var ttl = FoundTtl;
        if (videos.Count == 0)
        {
            ttl = NoneTtl;
            var tmdb = title.GetProviderId(MetadataProvider.Tmdb);
            var mediaType = title is Series ? "tv" : title is Movie ? "movie" : null;
            if (mediaType is not null && SeerrService.IsConfigured && int.TryParse(tmdb, NumberStyles.Integer, CultureInfo.InvariantCulture, out var tmdbId) && tmdbId > 0)
            {
                try
                {
                    videos = await _seerr.TrailerVideosAsync(mediaType, tmdbId, ct).ConfigureAwait(false);
                    if (videos.Count > 0)
                    {
                        ttl = FoundTtl;
                    }
                }
                catch (SeerrException ex)
                {
                    // Seerr down or the title unknown there: try again in a few minutes.
                    _logger.LogDebug("No TMDB trailer for {Title} through Seerr: {Message}", title.Name, ex.Message);
                    ttl = FailedTtl;
                }
            }
        }

        _cache[title.Id] = (DateTime.UtcNow + ttl, videos);
        if (_cache.Count > 2000)
        {
            foreach (var old in _cache.Where(k => k.Value.Until < DateTime.UtcNow).Select(k => k.Key).ToList())
            {
                _cache.TryRemove(old, out _);
            }
        }

        return videos;
    }

    /// <summary>The item's remote trailers (Jellyfin fills these from TMDB), trailers first.</summary>
    private static List<TrailerVideo> FromMetadata(BaseItem title)
    {
        var list = new List<(TrailerVideo Video, int Rank, int Order)>();
        var order = 0;
        foreach (var t in title.RemoteTrailers ?? [])
        {
            var m = string.IsNullOrEmpty(t?.Url) ? null : YouTubeUrl().Match(t.Url);
            if (m is null || !m.Success)
            {
                continue;
            }

            var key = m.Groups[1].Value;
            if (list.Any(x => x.Video.Key == key))
            {
                continue;
            }

            var name = t!.Name ?? string.Empty;
            // Trailers, then teasers, then whatever else is listed (date announcements, clips).
            var rank = name.Contains("trailer", StringComparison.OrdinalIgnoreCase) ? 0
                : name.Contains("teaser", StringComparison.OrdinalIgnoreCase) ? 1
                : 2;
            list.Add((new TrailerVideo(key, name, "jellyfin"), rank, order++));
        }

        return list.OrderBy(x => x.Rank).ThenBy(x => x.Order).Select(x => x.Video).ToList();
    }
}
