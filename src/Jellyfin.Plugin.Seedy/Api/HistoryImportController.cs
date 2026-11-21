using System.Threading;
using System.Threading.Tasks;
using Jellyfin.Plugin.Seedy.Services;
using MediaBrowser.Common.Api;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Jellyfin.Plugin.Seedy.Api;

/// <summary>
/// What importing Jellyfin's activity log would add to the play log, without writing anything. The import itself is the
/// "Seedy: import Jellyfin play history" task. Administrators only.
/// </summary>
[ApiController]
[Route("Seedy/Admin/HistoryImport")]
[Authorize(Policy = Policies.RequiresElevation)]
[Produces("application/json")]
public class HistoryImportController : ControllerBase
{
    private readonly HistoryBackfill _backfill;

    /// <summary>
    /// Initializes a new instance of the <see cref="HistoryImportController"/> class.
    /// </summary>
    /// <param name="backfill">The import.</param>
    public HistoryImportController(HistoryBackfill backfill)
    {
        _backfill = backfill;
    }

    /// <summary>Counts what the import would add.</summary>
    /// <param name="cancellationToken">Cancellation.</param>
    /// <returns>The plays it would import.</returns>
    [HttpGet]
    public Task<HistoryImportDto> Preview(CancellationToken cancellationToken) => _backfill.RunAsync(false, cancellationToken);
}
