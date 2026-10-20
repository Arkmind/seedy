using System;
using System.Linq;
using System.Text.Json;
using Jellyfin.Plugin.Seedy.Api;
using Jellyfin.Plugin.Seedy.Data;
using Microsoft.Extensions.Logging;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>Per-profile preferences. A profile starts from the admin's playback defaults until it saves its own.</summary>
public sealed class PrefsService
{
    private static readonly JsonSerializerOptions Json = new() { PropertyNameCaseInsensitive = true };

    private readonly SeedyDatabase _db;
    private readonly ILogger<PrefsService> _logger;
    private readonly object _saveGate = new();

    /// <summary>
    /// Initializes a new instance of the <see cref="PrefsService"/> class.
    /// </summary>
    /// <param name="db">Database.</param>
    /// <param name="logger">Logger.</param>
    public PrefsService(SeedyDatabase db, ILogger<PrefsService> logger)
    {
        _db = db;
        _logger = logger;
    }

    /// <summary>Gets a profile's preferences.</summary>
    /// <param name="userId">User id ("N").</param>
    /// <returns>Preferences.</returns>
    public UserPrefsDto Get(string userId)
    {
        var stored = _db.ReadJson("user_prefs", userId);
        if (stored is not null)
        {
            try
            {
                var prefs = JsonSerializer.Deserialize<UserPrefsDto>(stored.Value.Json, Json);
                if (prefs is not null)
                {
                    prefs.Playback = ConfigMapper.Clean(prefs.Playback ?? ConfigMapper.ToDto(ConfigMapper.Current.Playback));
                    prefs.Subtitles ??= new SubtitlePrefsDto();
                    prefs.HiddenRows ??= [];
                    prefs.HiddenSeeds ??= [];
                    prefs.Rows ??= [];
                    prefs.Privacy ??= new PrivacyPrefsDto();
                    prefs.Custom ??= [];
                    prefs.RowOrder = prefs.RowOrder == "mine" ? "mine" : "auto";
                    return prefs;
                }
            }
            catch (JsonException ex)
            {
                _logger.LogWarning(ex, "Ignoring unreadable Seedy preferences for {UserId}", userId);
            }
        }

        return new UserPrefsDto { Playback = ConfigMapper.ToDto(ConfigMapper.Current.Playback) };
    }

    /// <summary>Applies a partial update and stores the result.</summary>
    /// <param name="userId">User id ("N").</param>
    /// <param name="patch">Fields to change.</param>
    /// <returns>The stored preferences.</returns>
    public UserPrefsDto Save(string userId, UserPrefsPatch patch)
    {
        // Read-modify-write: two saves at once (Settings and the player) must not drop each other's change.
        lock (_saveGate)
        {
            return SaveLocked(userId, patch);
        }
    }

    private UserPrefsDto SaveLocked(string userId, UserPrefsPatch patch)
    {
        var prefs = Get(userId);
        if (patch.Playback is not null)
        {
            prefs.Playback = ConfigMapper.Clean(patch.Playback);
        }

        if (patch.Subtitles is not null)
        {
            prefs.Subtitles = new SubtitlePrefsDto
            {
                Size = patch.Subtitles.Size is "S" or "M" or "L" ? patch.Subtitles.Size : "M",
                Lift = patch.Subtitles.Lift
            };
        }

        if (patch.AdaptRowOrder is not null)
        {
            prefs.AdaptRowOrder = patch.AdaptRowOrder.Value;
        }

        if (patch.HiddenRows is not null)
        {
            // Newest last (the app appends): at the limit the oldest go, so a new hide always sticks.
            prefs.HiddenRows = patch.HiddenRows.Where(r => !string.IsNullOrWhiteSpace(r)).Distinct(StringComparer.Ordinal).TakeLast(200).ToList();
        }

        if (patch.HiddenSeeds is not null)
        {
            prefs.HiddenSeeds = patch.HiddenSeeds.Where(r => !string.IsNullOrWhiteSpace(r)).Distinct(StringComparer.OrdinalIgnoreCase).TakeLast(500).ToList();
        }

        if (patch.Volume is not null)
        {
            prefs.Volume = Math.Clamp(patch.Volume.Value, 0, 1);
        }

        if (patch.RowOrder is not null)
        {
            prefs.RowOrder = patch.RowOrder == "mine" ? "mine" : "auto";
        }

        if (patch.Rows is not null)
        {
            prefs.Rows = patch.Rows
                .Where(r => r is not null && !string.IsNullOrWhiteSpace(r.Kind))
                .GroupBy(r => r.Kind, StringComparer.Ordinal)
                .Select(g => g.First())
                .Take(40)
                .ToList();
        }

        if (patch.Privacy is not null)
        {
            prefs.Privacy = patch.Privacy;
        }

        if (patch.NotifyRequests is not null)
        {
            prefs.NotifyRequests = patch.NotifyRequests.Value;
        }

        if (patch.Custom is not null)
        {
            foreach (var (key, value) in patch.Custom)
            {
                if (key.Length is 0 or > 80)
                {
                    continue;
                }

                if (value.ValueKind is JsonValueKind.Null or JsonValueKind.Undefined)
                {
                    prefs.Custom.Remove(key);
                }
                else if (value.GetRawText().Length <= 20_000)
                {
                    prefs.Custom[key] = value.Clone();
                }
            }

            if (prefs.Custom.Count > 200)
            {
                prefs.Custom = prefs.Custom.Take(200).ToDictionary(kv => kv.Key, kv => kv.Value);
            }
        }

        _db.WritePrefs(userId, JsonSerializer.Serialize(prefs, Json));
        return prefs;
    }
}
