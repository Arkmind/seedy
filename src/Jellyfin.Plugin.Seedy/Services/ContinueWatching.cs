using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Data;
using Jellyfin.Data.Enums;
using Jellyfin.Database.Implementations.Entities;
using Jellyfin.Database.Implementations.Enums;
using MediaBrowser.Controller.Dto;
using MediaBrowser.Controller.Entities;
using MediaBrowser.Controller.Entities.TV;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.MediaSegments;
using MediaBrowser.Controller.Session;
using MediaBrowser.Model.Dto;
using MediaBrowser.Model.Entities;
using MediaBrowser.Model.Querying;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>
/// Continue watching, tidier than Jellyfin's resume list. A show keeps only the episode the person is on, not the
/// ones they moved past, and stopping in the credits counts as finishing, from any app.
/// </summary>
public sealed class ContinueWatching : IHostedService
{
    // Up next opens right where the credits start, so a stop a moment before them still counts.
    private static readonly long Slack = TimeSpan.FromSeconds(2).Ticks;

    // In-progress titles looked at for one list, before the rules drop some.
    private const int Candidates = 300;

    // Episodes of a show looked at to tell whether the person moved past one.
    private const int ShowHistory = 50;

    private readonly ILibraryManager _library;
    private readonly IUserDataManager _userData;
    private readonly IDtoService _dto;
    private readonly IMediaSegmentManager _segments;
    private readonly ISessionManager _sessions;
    private readonly ILogger<ContinueWatching> _logger;

    /// <summary>
    /// Initializes a new instance of the <see cref="ContinueWatching"/> class.
    /// </summary>
    /// <param name="library">Library manager.</param>
    /// <param name="userData">User data manager.</param>
    /// <param name="dto">DTO service.</param>
    /// <param name="segments">Media segment manager.</param>
    /// <param name="sessions">Session manager.</param>
    /// <param name="logger">Logger.</param>
    public ContinueWatching(ILibraryManager library, IUserDataManager userData, IDtoService dto, IMediaSegmentManager segments, ISessionManager sessions, ILogger<ContinueWatching> logger)
    {
        _library = library;
        _userData = userData;
        _dto = dto;
        _segments = segments;
        _sessions = sessions;
        _logger = logger;
    }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _sessions.PlaybackStopped += OnStopped;
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
    {
        _sessions.PlaybackStopped -= OnStopped;
        return Task.CompletedTask;
    }

    /// <summary>Gets what the person is in the middle of, most recent first.</summary>
    /// <param name="user">The person.</param>
    /// <param name="parentId">Only titles under this library, show or season (empty: everywhere).</param>
    /// <param name="limit">How many to return.</param>
    /// <param name="options">Fields for the items.</param>
    /// <returns>The titles.</returns>
    public async Task<QueryResult<BaseItemDto>> ListAsync(User user, Guid parentId, int limit, DtoOptions options)
    {
        var candidates = _library.GetItemList(new InternalItemsQuery(user)
        {
            OrderBy = [(ItemSortBy.DatePlayed, SortOrder.Descending)],
            IsResumable = true,
            ParentId = parentId,
            AncestorIds = parentId == Guid.Empty ? ShownLibraries(user) : [],
            Recursive = true,
            MediaTypes = [MediaType.Video],
            IsVirtualItem = false,
            CollapseBoxSetItems = false,
            Limit = Candidates,
            DtoOptions = new DtoOptions(false)
        });
        var data = _userData.GetUserDataBatch(candidates, user);
        var hidden = MovedPast(user, candidates, data);

        var picked = new List<BaseItem>();
        foreach (var item in candidates)
        {
            if (picked.Count >= limit)
            {
                break;
            }

            if (hidden.Contains(item.Id))
            {
                continue;
            }

            // Left in the credits before this rule existed, or before the credits were found: finished.
            if (data.TryGetValue(item.Id, out var d) && await InCreditsAsync(item, d.PlaybackPositionTicks).ConfigureAwait(false))
            {
                Finish(user, item);
                continue;
            }

            picked.Add(item);
        }

        return new QueryResult<BaseItemDto>(_dto.GetBaseItemDtos(picked, options, user));
    }

    // The libraries the person shows in Continue watching: Jellyfin's resume list leaves out the ones they hid from
    // "Latest" (Settings · Home screen), and so does this one.
    private Guid[] ShownLibraries(User user)
    {
        var excluded = user.GetPreferenceValues<Guid>(PreferenceKind.LatestItemExcludes);
        if (excluded.Length == 0)
        {
            return [];
        }

        return _library.GetUserRootFolder().GetChildren(user, true)
            .Where(f => f is Folder && !excluded.Contains(f.Id))
            .Select(f => f.Id)
            .ToArray();
    }

    // Episodes the person moved past: one with a later episode of the same show watched or in progress since, or, for
    // a show with several in progress, all but the one played last. Their resume points stay; they just don't show.
    // Specials don't count as moving on, and are moved past by anything played after them. A later episode only
    // opened for a moment (no resume point, not watched) doesn't count: the show would drop out of Continue watching
    // and Next up both.
    private HashSet<Guid> MovedPast(User user, IReadOnlyList<BaseItem> candidates, Dictionary<Guid, UserItemData> data)
    {
        var hidden = new HashSet<Guid>();
        foreach (var show in candidates.OfType<Episode>().Where(e => e.SeriesId != Guid.Empty).GroupBy(e => e.SeriesId))
        {
            var inProgress = show.ToList();
            var watched = _library.GetItemList(new InternalItemsQuery(user)
            {
                AncestorIds = [show.Key],
                IncludeItemTypes = [BaseItemKind.Episode],
                IsPlayed = true,
                IsVirtualItem = false,
                Recursive = true,
                OrderBy = [(ItemSortBy.DatePlayed, SortOrder.Descending)],
                Limit = ShowHistory,
                DtoOptions = new DtoOptions(false)
            }).OfType<Episode>().Where(w => inProgress.All(e => e.Id != w.Id)).ToList();
            var watchedData = _userData.GetUserDataBatch(watched, user);
            var history = inProgress.Select(e => (Ep: e, At: LastPlayed(data, e)))
                .Concat(watched.Select(e => (Ep: e, At: LastPlayed(watchedData, e))))
                .ToList();

            foreach (var ep in inProgress)
            {
                var at = LastPlayed(data, ep);
                if (history.Any(h => h.Ep.Id != ep.Id && h.At > at && !IsSpecial(h.Ep) && (IsSpecial(ep) || Order(h.Ep).CompareTo(Order(ep)) > 0)))
                {
                    hidden.Add(ep.Id);
                }
            }

            foreach (var ep in inProgress.Where(e => !hidden.Contains(e.Id)).OrderByDescending(e => LastPlayed(data, e)).Skip(1))
            {
                hidden.Add(ep.Id);
            }
        }

        return hidden;
    }

    private static DateTime LastPlayed(Dictionary<Guid, UserItemData> data, BaseItem item)
        => data.TryGetValue(item.Id, out var d) && d.LastPlayedDate is { } at ? at : DateTime.MinValue;

    private static bool IsSpecial(Episode ep) => ep.ParentIndexNumber == 0;

    private static (int Season, int Episode) Order(Episode ep) => (ep.ParentIndexNumber ?? 0, ep.IndexNumber ?? 0);

    /// <summary>
    /// Takes a title out of Continue watching without marking it watched: its resume point goes. For an episode, the
    /// show's other episodes in progress go too: they were hidden behind it, and would otherwise take its place.
    /// </summary>
    /// <param name="user">The person.</param>
    /// <param name="item">The title.</param>
    public void Remove(User user, BaseItem item)
    {
        var titles = new List<BaseItem> { item };
        if (item is Episode { SeriesId: var seriesId } && seriesId != Guid.Empty)
        {
            titles.AddRange(_library.GetItemList(new InternalItemsQuery(user)
            {
                AncestorIds = [seriesId],
                IncludeItemTypes = [BaseItemKind.Episode],
                IsResumable = true,
                Recursive = true,
                DtoOptions = new DtoOptions(false)
            }).Where(e => e.Id != item.Id));
        }

        foreach (var title in titles)
        {
            // Through the server's cached copy, as in Finish.
            var target = _library.GetItemById(title.Id) ?? title;
            var data = _userData.GetUserData(user, target);
            if (data is null || data.PlaybackPositionTicks <= 0)
            {
                continue;
            }

            data.PlaybackPositionTicks = 0;
            _userData.SaveUserData(user, target, data, UserDataSaveReason.UpdateUserData, CancellationToken.None);
        }
    }

    // ── Credits ──

    private void OnStopped(object? sender, PlaybackStopEventArgs e)
    {
        if (e.PlayedToCompletion || e.Item is not Video item || item is Trailer || item.ExtraType is not null
            || e.Users is not { Count: > 0 } || e.PlaybackPositionTicks is not { } position)
        {
            return;
        }

        var users = e.Users.ToList();
        _ = Task.Run(async () =>
        {
            try
            {
                if (await InCreditsAsync(item, position).ConfigureAwait(false))
                {
                    foreach (var user in users)
                    {
                        Finish(user, item);
                    }
                }
            }
            catch (Exception ex)
            {
                _logger.LogWarning(ex, "Seedy couldn't check whether {Item} stopped in its credits", item.Name);
            }
        });
    }

    // Whether a position is in the credits (or after them): past the start of an Outro segment in the second half.
    // An "outro" in the first half is a bad detection.
    private async Task<bool> InCreditsAsync(BaseItem item, long position)
    {
        var runtime = item.RunTimeTicks ?? 0;
        if (position <= 0 || runtime <= 0 || !_segments.HasSegments(item.Id))
        {
            return false;
        }

        var outros = await _segments.GetSegmentsAsync(item, [MediaSegmentType.Outro], _library.GetLibraryOptions(item), true).ConfigureAwait(false);
        return outros.Any(s => s.EndTicks > s.StartTicks && s.StartTicks >= runtime / 2 && position >= s.StartTicks - Slack);
    }

    // Marks a title watched and drops its resume point, as Jellyfin does when playback ends near the end.
    // Saved through the server's cached copy of the item: saving refreshes the user data held on the instance it's
    // given, and a query's fresh instance would leave the cached one (what /Items/{id} reads) out of date.
    private void Finish(User user, BaseItem item)
    {
        item = _library.GetItemById(item.Id) ?? item;
        var data = _userData.GetUserData(user, item);
        if (data is null || data.Played || data.PlaybackPositionTicks <= 0)
        {
            return;
        }

        data.Played = true;
        data.PlaybackPositionTicks = 0;
        _userData.SaveUserData(user, item, data, UserDataSaveReason.PlaybackFinished, CancellationToken.None);
        _logger.LogInformation("Seedy marked {Item} watched for {User}: it stopped in the credits", item.Name, user.Username);
    }
}
