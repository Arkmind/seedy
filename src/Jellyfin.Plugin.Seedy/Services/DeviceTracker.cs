using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Net;
using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Seedy.Data;
using MediaBrowser.Controller.Session;
using Microsoft.Data.Sqlite;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>
/// Remembers where each profile's devices connect from. Jellyfin forgets a device's address once its session ends,
/// and Settings › Devices tells home from away by it.
/// </summary>
public sealed class DeviceTracker : IHostedService
{
    // A device seen again from the same address is written at most this often.
    private static readonly TimeSpan RewriteEvery = TimeSpan.FromMinutes(15);

    private readonly ISessionManager _sessions;
    private readonly SeedyDatabase _db;
    private readonly ILogger<DeviceTracker> _logger;
    private readonly ConcurrentDictionary<string, (string? Ip, DateTime At)> _written = new(StringComparer.Ordinal);

    /// <summary>
    /// Initializes a new instance of the <see cref="DeviceTracker"/> class.
    /// </summary>
    /// <param name="sessions">Session manager.</param>
    /// <param name="db">Database.</param>
    /// <param name="logger">Logger.</param>
    public DeviceTracker(ISessionManager sessions, SeedyDatabase db, ILogger<DeviceTracker> logger)
    {
        _sessions = sessions;
        _db = db;
        _logger = logger;
    }

    /// <inheritdoc />
    public Task StartAsync(CancellationToken cancellationToken)
    {
        _sessions.SessionStarted += OnSession;
        _sessions.SessionActivity += OnSession;
        return Task.CompletedTask;
    }

    /// <inheritdoc />
    public Task StopAsync(CancellationToken cancellationToken)
    {
        _sessions.SessionStarted -= OnSession;
        _sessions.SessionActivity -= OnSession;
        return Task.CompletedTask;
    }

    /// <summary>"::ffff:192.168.1.31" → "192.168.1.31"; null for anything that isn't an address.</summary>
    /// <param name="value">Address as Jellyfin keeps it.</param>
    /// <returns>The address, or null.</returns>
    public static string? CleanIp(string? value)
    {
        if (string.IsNullOrWhiteSpace(value) || !IPAddress.TryParse(value, out var ip))
        {
            return null;
        }

        return (ip.IsIPv4MappedToIPv6 ? ip.MapToIPv4() : ip).ToString();
    }

    /// <summary>Notes a device of a profile, now.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="deviceId">Device id.</param>
    /// <param name="ip">Address it connects from.</param>
    public void Saw(Guid userId, string? deviceId, string? ip)
    {
        if (userId == Guid.Empty || string.IsNullOrEmpty(deviceId))
        {
            return;
        }

        var user = userId.ToString("N");
        var clean = CleanIp(ip);
        var key = user + "|" + deviceId;
        var now = DateTime.UtcNow;
        if (_written.TryGetValue(key, out var last) && (clean is null || clean == last.Ip) && now - last.At < RewriteEvery)
        {
            return;
        }

        _written[key] = (clean ?? last.Ip, now);
        try
        {
            _db.SawDevice(user, deviceId, clean, now);
        }
        catch (SqliteException ex)
        {
            _logger.LogDebug(ex, "Couldn't note device {DeviceId}", deviceId);
        }
    }

    /// <summary>Gets what Seedy remembers of a profile's devices.</summary>
    /// <param name="userId">User id.</param>
    /// <returns>Devices by id.</returns>
    public Dictionary<string, DeviceSeen> Seen(Guid userId) => _db.DevicesSeen(userId.ToString("N"));

    /// <summary>Forgets a device that was signed out.</summary>
    /// <param name="userId">User id.</param>
    /// <param name="deviceId">Device id.</param>
    public void Forget(Guid userId, string deviceId)
    {
        var user = userId.ToString("N");
        _written.TryRemove(user + "|" + deviceId, out _);
        _db.ForgetDevice(user, deviceId);
    }

    private void OnSession(object? sender, SessionEventArgs e)
    {
        var s = e.SessionInfo;
        if (s is not null)
        {
            Saw(s.UserId, s.DeviceId, s.RemoteEndPoint);
        }
    }
}
