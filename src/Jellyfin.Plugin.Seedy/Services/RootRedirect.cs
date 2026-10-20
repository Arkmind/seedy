using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using MediaBrowser.Common.Net;
using MediaBrowser.Controller.Configuration;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.DependencyInjection;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>
/// Where things open. "Replace Jellyfin's web app" (the default) serves Seedy at the server address itself
/// ({base}/, {base}/admin/…) and moves Jellyfin's own app to {base}/old. Jellyfin's /web keeps working too, because
/// Jellyfin's mobile and TV apps embed it. Runs ahead of Jellyfin's pipeline, so paths still carry the base URL.
/// </summary>
public sealed class RootRedirectStartupFilter : IStartupFilter
{
    /// <summary>The HttpContext item set when a root path was handed to Seedy's web controller.</summary>
    public const string AtRootItem = "Seedy.AtRoot";

    /// <summary>
    /// First path segments of Seedy's pages (web/src/app.tsx). Seedy links them in lowercase; Jellyfin's API is
    /// capitalised and only shares /Library and /Search, so only browser page loads of these go to Seedy.
    /// </summary>
    private static readonly HashSet<string> Pages = new(StringComparer.Ordinal)
    {
        "admin", "details", "favorites", "history", "library", "list", "livetv", "play", "request", "requests", "search", "settings"
    };

    /// <inheritdoc />
    public Action<IApplicationBuilder> Configure(Action<IApplicationBuilder> next)
    {
        return app =>
        {
            app.Use(Route);
            next(app);
        };
    }

    /// <summary>Gets a value indicating whether Seedy is served at the server address.</summary>
    public static bool AtRoot => Plugin.Instance?.Configuration.OpensAt != "alongside";

    private static Task Route(HttpContext context, Func<Task> next)
    {
        var request = context.Request;
        if (!HttpMethods.IsGet(request.Method) && !HttpMethods.IsHead(request.Method))
        {
            return next();
        }

        var baseUrl = context.RequestServices.GetService<IServerConfigurationManager>()?.GetNetworkConfiguration().BaseUrl ?? string.Empty;
        var path = request.Path.Value ?? string.Empty;
        if (!path.StartsWith(baseUrl, StringComparison.OrdinalIgnoreCase))
        {
            // Behind a base URL, the bare domain would otherwise go to Jellyfin's app at {base}/web/.
            if (AtRoot && HttpMethods.IsGet(request.Method) && (path.Length == 0 || path == "/"))
            {
                context.Response.Redirect(baseUrl + "/");
                return Task.CompletedTask;
            }

            return next();
        }

        var local = path[baseUrl.Length..];

        // Jellyfin's own web app at /old, served from its /web files.
        if (string.Equals(local, "/old", StringComparison.OrdinalIgnoreCase))
        {
            context.Response.Redirect(baseUrl + "/old/" + request.QueryString);
            return Task.CompletedTask;
        }

        if (local.StartsWith("/old/", StringComparison.OrdinalIgnoreCase))
        {
            // Jellyfin's app finds its server by looking for "/web" in the page address, and falls back to the site
            // root. Behind a base URL that fallback is wrong, so there it opens at {base}/web instead.
            if (baseUrl.Length > 0)
            {
                context.Response.Redirect(baseUrl + "/web/" + local[5..] + request.QueryString);
                return Task.CompletedTask;
            }

            request.Path = "/web/" + local[5..];
            return next();
        }

        // Seedy's pages answer GET only (Jellyfin's web controller has no HEAD routes either).
        if (!AtRoot || !HttpMethods.IsGet(request.Method))
        {
            return next();
        }

        if (local.Length == 0 || local == "/")
        {
            return ToSeedy(context, baseUrl, "/", next);
        }

        // Jellyfin's web app moved to /old: people opening its old address in a browser land on Seedy. Its files stay
        // served at /web (the app at /old loads them), and so does /web itself for Jellyfin's own apps that embed it.
        if (IsOldWebEntry(local) && IsPageLoad(request) && !IsEmbeddedApp(request))
        {
            context.Response.Redirect(baseUrl + "/");
            return Task.CompletedTask;
        }

        var end = local.IndexOf('/', 1);
        var first = end < 0 ? local[1..] : local[1..end];
        if (Pages.Contains(first) && IsPageLoad(request))
        {
            return ToSeedy(context, baseUrl, local, next);
        }

        return next();
    }

    private static Task ToSeedy(HttpContext context, string baseUrl, string local, Func<Task> next)
    {
        context.Items[AtRootItem] = true;
        context.Request.Path = baseUrl + "/seedy" + local;
        return next();
    }

    private static bool IsOldWebEntry(string local) =>
        local.Equals("/web", StringComparison.OrdinalIgnoreCase)
        || local.Equals("/web/", StringComparison.OrdinalIgnoreCase)
        || local.Equals("/web/index.html", StringComparison.OrdinalIgnoreCase);

    // Jellyfin's Android and Android TV apps (WebView), Jellyfin Media Player (Qt WebEngine) and the LG webOS app load
    // the server's /web themselves; sending them to Seedy would break them.
    private static bool IsEmbeddedApp(HttpRequest request)
    {
        var agent = request.Headers.UserAgent.ToString();
        return agent.Contains("; wv)", StringComparison.Ordinal)
            || agent.Contains("Jellyfin", StringComparison.OrdinalIgnoreCase)
            || agent.Contains("QtWebEngine", StringComparison.Ordinal)
            || agent.Contains("Web0S", StringComparison.OrdinalIgnoreCase);
    }

    // Browsers mark page loads; API calls (Seedy's included) ask for JSON, images and media for other types.
    private static bool IsPageLoad(HttpRequest request) =>
        string.Equals(request.Headers["Sec-Fetch-Mode"], "navigate", StringComparison.Ordinal)
        || request.Headers.Accept.Any(a => a?.Contains("text/html", StringComparison.OrdinalIgnoreCase) == true);
}
