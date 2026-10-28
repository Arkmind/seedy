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
        serviceCollection.AddSingleton<Audiobooks>();
        serviceCollection.AddSingleton<HomeService>();
        serviceCollection.AddSingleton<PlaybackRecorder>();
        serviceCollection.AddSingleton<Services.Media.ServiceHttp>();
        serviceCollection.AddSingleton<Services.Media.LidarrClient>();
        serviceCollection.AddSingleton<Services.Media.ShelfarrClient>();
        serviceCollection.AddSingleton<Services.Media.LibrarrClient>();
        serviceCollection.AddSingleton<Services.Media.ReadarrClient>();
        serviceCollection.AddSingleton<Services.Media.MusicBrainzClient>();
        serviceCollection.AddSingleton<Services.Media.OpenLibraryClient>();
        serviceCollection.AddSingleton<Services.Media.MediaLibrary>();
        serviceCollection.AddSingleton<Services.Media.MediaRequestService>();
        serviceCollection.AddSingleton<Services.Media.MediaDiscoverService>();
        serviceCollection.AddHostedService<Services.Media.MediaSyncService>();
        serviceCollection.AddHostedService(sp => sp.GetRequiredService<PlaybackRecorder>());
        serviceCollection.AddTransient<IStartupFilter, RootRedirectStartupFilter>();
    }
}
