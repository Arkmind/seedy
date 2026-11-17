using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using MediaBrowser.Controller.Library;
using MediaBrowser.Controller.Session;
using MediaBrowser.Model.Dto;
using MediaBrowser.Model.Session;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Controllers;
using Microsoft.AspNetCore.Mvc.Filters;

namespace Jellyfin.Plugin.Seedy.Services;

/// <summary>
/// Who may control a screen: the person signed in on it, nobody else. Jellyfin also lets anyone with "remote control of
/// other users" (the first administrator has it) control everyone, and anyone control a screen nobody is signed in on;
/// Seedy doesn't, whatever the person's permissions. Administrators keep the two moderation tools of Playing now (Stop
/// and Message) on everyone's playback. API keys are the server's own integrations and pass as before.
/// </summary>
public sealed class RemoteControlGuard : IAsyncActionFilter, IAsyncResultFilter
{
    private const string JellyfinSessions = "Jellyfin.Api.Controllers.SessionController";
    private const string IsApiKeyClaim = "Jellyfin-IsApiKey";

    /// <summary>Jellyfin's actions that act on another session (its id in the route).</summary>
    private static readonly HashSet<string> Commands = new(StringComparer.Ordinal)
    {
        "DisplayContent",
        "Play",
        "SendPlaystateCommand",
        "SendSystemCommand",
        "SendGeneralCommand",
        "SendFullGeneralCommand",
        "SendMessageCommand",
        "AddUserToSession",
        "RemoveUserFromSession"
    };

    private readonly ISessionManager _sessions;
    private readonly IUserManager _users;

    /// <summary>
    /// Initializes a new instance of the <see cref="RemoteControlGuard"/> class.
    /// </summary>
    /// <param name="sessions">Session manager.</param>
    /// <param name="users">User manager.</param>
    public RemoteControlGuard(ISessionManager sessions, IUserManager users)
    {
        _sessions = sessions;
        _users = users;
    }

    /// <inheritdoc />
    public async Task OnActionExecutionAsync(ActionExecutingContext context, ActionExecutionDelegate next)
    {
        var action = JellyfinSessionsAction(context.ActionDescriptor);
        if (action is null
            || !Commands.Contains(action)
            || context.RouteData.Values["sessionId"] is not string sessionId
            || IsApiKey(context.HttpContext))
        {
            await next().ConfigureAwait(false);
            return;
        }

        // Jellyfin finds the session by its exact id; ignoring case here can only find more, never miss one it acts on.
        var target = _sessions.Sessions.FirstOrDefault(s => string.Equals(s.Id, sessionId, StringComparison.OrdinalIgnoreCase));
        var me = context.HttpContext.User.GetUserId();
        if (target is null || (!me.Equals(Guid.Empty) && target.UserId.Equals(me)))
        {
            await next().ConfigureAwait(false);
            return;
        }

        var user = context.HttpContext.User.GetUser(_users);
        if (user?.IsAdmin() == true && await Moderate(context, action, target.Id).ConfigureAwait(false))
        {
            context.Result = new NoContentResult();
            return;
        }

        context.Result = new ObjectResult("Only the person signed in on this screen can control it.") { StatusCode = StatusCodes.Status403Forbidden };
    }

    /// <inheritdoc />
    public async Task OnResultExecutionAsync(ResultExecutingContext context, ResultExecutionDelegate next)
    {
        // "The sessions this person can control" lists their own only.
        if (JellyfinSessionsAction(context.ActionDescriptor) == "GetSessions"
            && context.Result is ObjectResult { Value: IEnumerable<SessionInfoDto> list } result
            && context.HttpContext.Request.Query.TryGetValue("controllableByUserId", out var asked))
        {
            var who = Guid.TryParse(asked.ToString(), out var id) && !id.Equals(Guid.Empty) ? id : context.HttpContext.User.GetUserId();
            result.Value = list.Where(s => !who.Equals(Guid.Empty) && s.UserId.Equals(who)).ToList();
        }

        await next().ConfigureAwait(false);
    }

    private static string? JellyfinSessionsAction(Microsoft.AspNetCore.Mvc.Abstractions.ActionDescriptor descriptor) =>
        descriptor is ControllerActionDescriptor d && string.Equals(d.ControllerTypeInfo.FullName, JellyfinSessions, StringComparison.Ordinal)
            ? d.ActionName
            : null;

    private static bool IsApiKey(HttpContext context) =>
        bool.TryParse(context.User.FindFirst(IsApiKeyClaim)?.Value, out var apiKey) && apiKey;

    /// <summary>
    /// An administrator's Stop or Message on someone else's playback (Playing now): sent as the server, so it doesn't
    /// hang on Jellyfin's "remote control of other users" permission, which Seedy no longer uses.
    /// </summary>
    private async Task<bool> Moderate(ActionExecutingContext context, string action, string sessionId)
    {
        var args = context.ActionArguments;
        if (action == "SendPlaystateCommand" && args.TryGetValue("command", out var c) && c is PlaystateCommand.Stop)
        {
            await _sessions.SendPlaystateCommand(string.Empty, sessionId, new PlaystateRequest { Command = PlaystateCommand.Stop }, CancellationToken.None).ConfigureAwait(false);
            return true;
        }

        if (action == "SendMessageCommand" && args.TryGetValue("command", out var m) && m is MessageCommand message)
        {
            if (string.IsNullOrWhiteSpace(message.Header))
            {
                message.Header = "Message from Server";
            }

            await _sessions.SendMessageCommand(string.Empty, sessionId, message, CancellationToken.None).ConfigureAwait(false);
            return true;
        }

        return false;
    }
}
