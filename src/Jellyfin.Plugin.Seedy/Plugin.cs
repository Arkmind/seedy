using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using Jellyfin.Plugin.Seedy.Configuration;
using MediaBrowser.Common.Configuration;
using MediaBrowser.Common.Plugins;
using MediaBrowser.Model.Plugins;
using MediaBrowser.Model.Serialization;

namespace Jellyfin.Plugin.Seedy;

/// <summary>
/// Seedy: a Jellyfin web client with Seerr requests, served at the server address (or at /seedy alongside Jellyfin's app).
/// </summary>
public class Plugin : BasePlugin<PluginConfiguration>, IHasWebPages
{
    /// <summary>The plugin id.</summary>
    public static readonly Guid PluginId = Guid.Parse("b547483f-57e9-439e-8a2a-561828a524a6");

    /// <summary>
    /// Initializes a new instance of the <see cref="Plugin"/> class.
    /// </summary>
    /// <param name="applicationPaths">Application paths.</param>
    /// <param name="xmlSerializer">XML serializer.</param>
    public Plugin(IApplicationPaths applicationPaths, IXmlSerializer xmlSerializer)
        : base(applicationPaths, xmlSerializer)
    {
        Instance = this;
        SeedyDataPath = Path.Combine(applicationPaths.DataPath, "seedy");
        Directory.CreateDirectory(SeedyDataPath);
    }

    /// <inheritdoc />
    public override string Name => "Seedy";

    /// <inheritdoc />
    public override Guid Id => PluginId;

    /// <inheritdoc />
    public override string Description => "A Jellyfin web client with Seerr requests, a home screen that learns, and a play log.";

    /// <summary>Gets the running plugin instance.</summary>
    public static Plugin? Instance { get; private set; }

    /// <summary>Gets the folder for the play log database and the uploaded logo.</summary>
    public string SeedyDataPath { get; }

    /// <summary>Gets the plugin version as "0.1.0".</summary>
    public string VersionString => Version.ToString(3);

    /// <summary>Saves configuration changes made by the admin API.</summary>
    /// <param name="config">The new configuration.</param>
    public void Save(PluginConfiguration config)
    {
        UpdateConfiguration(config);
    }

    /// <inheritdoc />
    public IEnumerable<PluginPageInfo> GetPages()
    {
        yield return new PluginPageInfo
        {
            Name = "Seedy",
            DisplayName = "Seedy",
            EnableInMainMenu = true,
            MenuIcon = "spa",
            EmbeddedResourcePath = string.Format(CultureInfo.InvariantCulture, "{0}.Configuration.configPage.html", GetType().Namespace)
        };
    }
}
