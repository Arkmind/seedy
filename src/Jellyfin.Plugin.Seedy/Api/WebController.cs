using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Net;
using System.Reflection;
using System.Text;
using Jellyfin.Plugin.Seedy.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.StaticFiles;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>
/// Serves the Seedy web app from the plugin's embedded files at {baseUrl}/seedy/. Any path that isn't a
/// file gets index.html, so the app's own routes survive a reload. When Seedy replaces Jellyfin's app, its pages
/// are handed over from the server address by <see cref="RootRedirectStartupFilter"/> and /seedy/… pages redirect there.
/// </summary>
[ApiController]
[AllowAnonymous]
[ApiExplorerSettings(IgnoreApi = true)]
public class WebController : ControllerBase
{
    private const string Prefix = "Seedy.Web/";
    private static readonly Assembly Assembly = typeof(WebController).Assembly;
    private static readonly FileExtensionContentTypeProvider Types = new();
    private static readonly Lazy<Dictionary<string, string>> Files = new(() =>
        Assembly.GetManifestResourceNames()
            .Where(n => n.StartsWith(Prefix, StringComparison.Ordinal))
            .ToDictionary(n => n[Prefix.Length..].Replace('\\', '/'), n => n, StringComparer.Ordinal));

    /// <summary>Serves the app's start page.</summary>
    /// <returns>index.html.</returns>
    [HttpGet("seedy")]
    public ActionResult Root()
    {
        if (MovedToRoot)
        {
            return Redirect(Request.PathBase + "/" + Request.QueryString);
        }

        // Relative asset URLs need the trailing slash.
        if (!Request.Path.Value!.EndsWith('/'))
        {
            return Redirect(Request.PathBase + Request.Path + "/" + Request.QueryString);
        }

        return Index();
    }

    /// <summary>Serves an app file, or the start page for app routes.</summary>
    /// <param name="path">Path below /seedy/.</param>
    /// <returns>The file.</returns>
    [HttpGet("seedy/{**path}")]
    public ActionResult Asset([FromRoute] string? path)
    {
        // App pages live at lowercase /seedy/…; a capitalised /Seedy/… path is an API call that matched nothing.
        if (Request.Path.Value?.StartsWith("/Seedy/", StringComparison.Ordinal) == true)
        {
            return NotFound();
        }

        if (string.IsNullOrEmpty(path) || path.Contains("..", StringComparison.Ordinal))
        {
            return MovedToRoot ? Redirect(Request.PathBase + "/" + Request.QueryString) : Index();
        }

        if (Files.Value.TryGetValue(path, out var resource) && path != "index.html")
        {
            var stream = Assembly.GetManifestResourceStream(resource);
            if (stream is null)
            {
                return NotFound();
            }

            Response.Headers.CacheControl = path.StartsWith("assets/", StringComparison.Ordinal)
                ? "public, max-age=31536000, immutable"
                : "public, max-age=3600";
            Response.Headers.XContentTypeOptions = "nosniff";
            return File(stream, ContentType(path));
        }

        // Unknown files under assets/ are a stale build reference, not an app route.
        if (path.StartsWith("assets/", StringComparison.Ordinal))
        {
            return NotFound();
        }

        // Old /seedy/… links (bookmarks, emails) go to the same page at the server address.
        return MovedToRoot ? Redirect(Request.PathBase + "/" + path + Request.QueryString) : Index();
    }

    /// <summary>
    /// Gets a value indicating whether this is a /seedy/… page load while Seedy lives at the server address.
    /// Requests the root routing handed over carry a marker and are served as usual.
    /// </summary>
    private bool MovedToRoot => RootRedirectStartupFilter.AtRoot && !HttpContext.Items.ContainsKey(RootRedirectStartupFilter.AtRootItem);

    private ActionResult Index()
    {
        if (!Files.Value.TryGetValue("index.html", out var resource) || Assembly.GetManifestResourceStream(resource) is not { } stream)
        {
            return Content("Seedy's web files are missing from this build of the plugin.", "text/plain", Encoding.UTF8);
        }

        string html;
        using (var reader = new StreamReader(stream, Encoding.UTF8))
        {
            html = reader.ReadToEnd();
        }

        // Asset URLs are relative so Seedy works under any Jellyfin base URL; the base tag keeps them pointing at
        // /seedy/ when the page is opened at a deep link such as /play/<id>. The app's own pages live at the server
        // address, or under /seedy when Seedy runs alongside Jellyfin's app; the meta tags tell the app which.
        var pathBase = Request.PathBase.Value ?? string.Empty;
        var baseHref = WebUtility.HtmlEncode(pathBase + "/seedy/");
        var appBase = HttpContext.Items.ContainsKey(RootRedirectStartupFilter.AtRootItem) ? pathBase : pathBase + "/seedy";
        var where = $"<meta name=\"seedy-server\" content=\"{WebUtility.HtmlEncode(pathBase)}\">"
            + $"<meta name=\"seedy-app\" content=\"{WebUtility.HtmlEncode(appBase)}\">";

        // Paint the admin's accent and name before the app boots, so nothing flashes.
        var branding = ConfigMapper.Branding(ConfigMapper.Current);
        var head = $"<style>:root{{--seedy-accent:{branding.Accent}}}</style>";
        // The tab icon is the uploaded logo, or the brand mark; the version makes browsers fetch a new one.
        var icon = WebUtility.HtmlEncode($"{pathBase}/Seedy/Client/Favicon?v={branding.LogoVersion}-{Uri.EscapeDataString(branding.Name)}");
        html = html.Replace("<link rel=\"icon\" href=\"data:,\">", $"<link rel=\"icon\" href=\"{icon}\">", StringComparison.Ordinal);
        html = html.Replace("<head>", $"<head><base href=\"{baseHref}\">{where}", StringComparison.Ordinal)
            .Replace("<title>Seedy</title>", $"<title>{WebUtility.HtmlEncode(branding.Name)}</title>", StringComparison.Ordinal)
            .Replace("</head>", head + "</head>", StringComparison.Ordinal);

        Response.Headers.CacheControl = "no-cache";
        Response.Headers.XContentTypeOptions = "nosniff";

        // The experimental player reloads its page with ?isolate=1 to decode on several threads, which takes a
        // cross-origin isolated page. "credentialless" still lets cross-origin images (TMDB posters) load.
        if (Request.Query["isolate"] == "1")
        {
            Response.Headers["Cross-Origin-Opener-Policy"] = "same-origin";
            Response.Headers["Cross-Origin-Embedder-Policy"] = "credentialless";
        }

        return Content(html, "text/html; charset=utf-8", Encoding.UTF8);
    }

    private static string ContentType(string path)
    {
        if (path.EndsWith(".woff2", StringComparison.OrdinalIgnoreCase))
        {
            return "font/woff2";
        }

        return Types.TryGetContentType(path, out var type) ? type : "application/octet-stream";
    }
}
