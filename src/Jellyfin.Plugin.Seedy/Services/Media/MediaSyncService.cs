using System;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services.Media;

/// <summary>Follows music and book requests in their apps every minute, and keeps MusicBrainz data for the server's
/// artists warm so the Music rows open quickly.</summary>
public sealed class MediaSyncService : IHostedService, IDisposable
{
    private static readonly TimeSpan Every = TimeSpan.FromMinutes(1);
    private static readonly TimeSpan WarmEvery = TimeSpan.FromHours(6);
    private readonly MediaRequestService _requests;
    private readonly MediaDiscoverService _discover;
    private readonly ILogger<MediaSyncService> _logger;
    private CancellationTokenSource? _stop;
    private Task? _loop;

    /// <summary>
    /// Initializes a new instance of the <see cref="MediaSyncService"/> class.
    /// </summary>
    /// <param name="requests">Requests.</param>
    /// <param name="discover">Discover rows.</param>
    /// <param name="logger">Logger.</param>
    public MediaSyncService(MediaRequestService requests, MediaDiscoverService discover, ILogger<MediaSyncService> logger)
    {
        _requests = requests;
        _discover = discover;
        _logger = logger;
    }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _stop = new CancellationTokenSource();
        _loop = Task.Run(() => Loop(_stop.Token), CancellationToken.None);
        return Task.CompletedTask;
    }

    private async Task Loop(CancellationToken ct)
    {
        var warmAt = DateTime.UtcNow.AddMinutes(2);
        try
        {
            await Task.Delay(TimeSpan.FromSeconds(20), ct).ConfigureAwait(false);
            while (!ct.IsCancellationRequested)
            {
                try
                {
                    await _requests.SyncAsync(ct).ConfigureAwait(false);
                    if (DateTime.UtcNow >= warmAt && MediaRequestService.MusicEnabled)
                    {
                        warmAt = DateTime.UtcNow + WarmEvery;
                        await _discover.WarmMusicAsync(ct).ConfigureAwait(false);
                    }
                }
#pragma warning disable CA1031 // The loop keeps running whatever one round hits.
                catch (Exception ex) when (ex is not OperationCanceledException)
#pragma warning restore CA1031
                {
                    _logger.LogWarning(ex, "Seedy's request sync failed");
                }

                await Task.Delay(Every, ct).ConfigureAwait(false);
            }
        }
        catch (OperationCanceledException)
        {
            // Stopping.
        }
    }

    /// <inheritdoc />
    public async Task StopAsync(CancellationToken cancellationToken)
    {
        _stop?.Cancel();
        if (_loop is not null)
        {
            await Task.WhenAny(_loop, Task.Delay(TimeSpan.FromSeconds(5), cancellationToken)).ConfigureAwait(false);
        }
    }

    /// <inheritdoc />
    public void Dispose()
    {
        _stop?.Dispose();
    }
}
