using Jellyfin.Plugin.Seedy.Data;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Controller;
using MediaBrowser.Controller.Plugins;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.DependencyInjection;

namespace Jellyfin.Plugin.Seedy;

/// <summary>Registers Seedy's services with the server.</summary>
public class PluginServiceRegistrator : IPluginServiceRegistrator
{
    /// <inheritdoc />
    public void RegisterServices(IServiceCollection serviceCollection, IServerApplicationHost applicationHost)
    {
        serviceCollection.AddSingleton<SeedyDatabase>();
        serviceCollection.AddSingleton<PrefsService>();
        serviceCollection.AddSingleton<SeerrService>();
        serviceCollection.AddSingleton<TrailerService>();
        serviceCollection.AddSingleton<BookFiles>();
        serviceCollection.AddSingleton<HomeService>();
        serviceCollection.AddSingleton<PlaybackRecorder>();
        serviceCollection.AddHostedService(sp => sp.GetRequiredService<PlaybackRecorder>());
        serviceCollection.AddTransient<IStartupFilter, RootRedirectStartupFilter>();
    }
}
