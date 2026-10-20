import { clock } from '../../lib/format';
import { t } from '../../lib/i18n';
import type { DayOfWeek, TaskInfo, TaskTrigger } from './api';
import { dayAt, onDay, realDate, runLength, weekdayName, WEEKDAYS } from './format';

/* How the Scheduled tasks board names and groups Jellyfin's tasks. Jellyfin localises task names and
   categories on the server, so known tasks are matched by key; anything else keeps the server's wording.
   The copy below is English; it is translated where it is shown (taskName, taskDesc, t(group)). */

const COPY: Record<string, { name: string; desc: string }> = {
  RefreshLibrary: { name: 'Scan media library', desc: 'Finds new, changed and removed files' },
  RefreshTrickplayImages: { name: 'Generate trickplay images', desc: 'Seek previews for new videos' },
  TaskExtractMediaSegments: { name: 'Media segment scan', desc: 'Intro, recap and credits markers' },
  RefreshChapterImages: { name: 'Extract chapter images', desc: 'Thumbnails for chapter lists' },
  AudioNormalization: { name: 'Audio normalization', desc: 'Measures loudness so tracks play at an even volume' },
  DownloadLyrics: { name: 'Download missing lyrics', desc: 'Finds lyrics for songs that have none' },
  DownloadSubtitles: { name: 'Download missing subtitles', desc: 'Searches for subtitles in each library’s languages' },
  KeyframeExtraction: { name: 'Extract keyframes', desc: 'Makes seeking in transcoded streams more precise' },
  RefreshPeople: { name: 'Refresh people', desc: 'Updates cast and crew details' },
  TmdbRefreshUpcomingEpisodes: { name: 'Find upcoming episodes', desc: 'Adds announced episodes from TheMovieDb' },
  DeleteTranscodeFiles: { name: 'Clean transcode folder', desc: 'Deletes leftover transcoding files' },
  DeleteCacheFiles: { name: 'Clean cache folder', desc: 'Deletes cache files older than a month' },
  CleanLogFiles: { name: 'Clean log folder', desc: 'Deletes logs past their retention' },
  OptimizeDatabaseTask: { name: 'Optimize database', desc: 'Compacts the library database' },
  CleanActivityLog: { name: 'Clean activity log', desc: 'Deletes activity entries past their retention' },
  MoveTrickplayImages: { name: 'Move trickplay images', desc: 'Applies each library’s trickplay location' },
  CleanupUserDataTask: { name: 'Clean up user data', desc: 'Removes watch data for media that’s gone' },
  PluginUpdates: { name: 'Check for plugin updates', desc: 'Looks for new versions in your repositories' },
  RefreshGuide: { name: 'Refresh guide', desc: 'Downloads programme data for Live TV' },
  RefreshInternetChannels: { name: 'Refresh channels', desc: 'Updates what channel plugins offer' }
};

const LIBRARY = ['RefreshLibrary', 'RefreshTrickplayImages', 'TaskExtractMediaSegments', 'RefreshChapterImages', 'AudioNormalization', 'DownloadLyrics', 'DownloadSubtitles', 'KeyframeExtraction', 'RefreshPeople', 'TmdbRefreshUpcomingEpisodes'];
const MAINTENANCE = ['DeleteTranscodeFiles', 'DeleteCacheFiles', 'CleanLogFiles', 'OptimizeDatabaseTask', 'CleanActivityLog', 'MoveTrickplayImages', 'CleanupUserDataTask'];
const ORDER = [...LIBRARY.slice(0, 4), ...MAINTENANCE.slice(0, 4), 'PluginUpdates'];

export const taskName = (task: TaskInfo) => (COPY[task.Key] ? t(COPY[task.Key].name) : task.Name);
export const taskDesc = (task: TaskInfo) => (COPY[task.Key] ? t(COPY[task.Key].desc) : task.Description.replace(/\.$/, ''));

/* Group labels, in English: show them with t(label). */
export const GROUPS = ['Library', 'Maintenance', 'Live TV', 'Channels', 'Plugins'] as const;
export type Group = (typeof GROUPS)[number];

export function groupOf(task: TaskInfo): Group {
  if (LIBRARY.includes(task.Key)) return 'Library';
  if (MAINTENANCE.includes(task.Key)) return 'Maintenance';
  if (task.Key === 'RefreshGuide') return 'Live TV';
  if (task.Key === 'RefreshInternetChannels') return 'Channels';
  if (task.Key === 'PluginUpdates') return 'Plugins';
  // Plugins' own tasks ("Seedy", "Intro Skipper", …) and anything else a plugin adds.
  if (task.Category === 'Library' || task.Category === 'Maintenance' || task.Category === 'Live TV') return task.Category;
  return 'Plugins';
}

export function sortTasks(a: TaskInfo, b: TaskInfo): number {
  const ia = ORDER.indexOf(a.Key);
  const ib = ORDER.indexOf(b.Key);
  if (ia !== -1 || ib !== -1) return (ia === -1 ? 99 : ia) - (ib === -1 ? 99 : ib);
  return taskName(a).localeCompare(taskName(b), undefined, { sensitivity: 'base' });
}

/* ── Triggers ── */

const TICKS_PER_MINUTE = 600_000_000;
export const minutesOf = (ticks?: number | null) => Math.round((ticks ?? 0) / TICKS_PER_MINUTE);
export const ticksOfMinutes = (m: number) => m * TICKS_PER_MINUTE;

export function timeOfDay(ticks?: number | null): string {
  const m = minutesOf(ticks);
  return `${String(Math.floor(m / 60) % 24).padStart(2, '0')}:${String(m % 60).padStart(2, '0')}`;
}

export function intervalText(ticks?: number | null): string {
  const m = minutesOf(ticks);
  if (m <= 0) return t('Every so often');
  if (m < 60) return t('Every {n} minutes', { n: m });
  if (m % 60 === 0) {
    const h = m / 60;
    if (h === 1) return t('Every hour');
    if (h % 24 === 0 && h > 24 && h / 24 === 7) return t('Every week');
    if (h % 24 === 0 && h > 24) return t('Every {n} days', { n: h / 24 });
    return t('Every {n} hours', { n: h });
  }
  return t('Every {h} h {m} min', { h: Math.floor(m / 60), m: m % 60 });
}

export function triggerText(tr: TaskTrigger): string {
  switch (tr.Type) {
    case 'DailyTrigger': return t('Daily at {time}', { time: timeOfDay(tr.TimeOfDayTicks) });
    case 'WeeklyTrigger': return t('Weekly, {day} at {time}', { day: weekdayName(tr.DayOfWeek ?? 'Sunday'), time: timeOfDay(tr.TimeOfDayTicks) });
    case 'IntervalTrigger': return intervalText(tr.IntervalTicks);
    case 'StartupTrigger': return t('On startup');
    default: return t('Custom');
  }
}

/* "Every 12 hours", "Daily at 03:00", "Every 24 hours and on startup", "Not scheduled". */
export function scheduleText(triggers: TaskTrigger[]): string {
  if (!triggers.length) return t('Not scheduled');
  const timed = triggers.filter((tr) => tr.Type !== 'StartupTrigger').map(triggerText);
  const startup = triggers.some((tr) => tr.Type === 'StartupTrigger');
  if (!timed.length) return t('On startup');
  const first = timed.length > 1 ? t('{schedule} · {n} more', { schedule: timed[0], n: timed.length - 1 }) : timed[0];
  return startup ? t('{schedule} and on startup', { schedule: first }) : first;
}

/* When a trigger fires next, where that can be known from here. Daily and weekly triggers run on the server's clock:
   `serverOffset` is its UTC offset in minutes (unknown = assume the browser's). */
export function nextRun(task: TaskInfo, now = new Date(), serverOffset?: number | null): Date | undefined {
  let best: Date | undefined;
  const consider = (d?: Date) => { if (d && d > now && (!best || d < best)) best = d; };
  /* Work in the server's wall-clock time held in this browser's date fields, then shift back to a real instant. */
  const shift = serverOffset === undefined || serverOffset === null ? 0 : (serverOffset + now.getTimezoneOffset()) * 60_000;
  const serverNow = new Date(now.getTime() + shift);
  for (const tr of task.Triggers) {
    if (tr.Type === 'DailyTrigger' || tr.Type === 'WeeklyTrigger') {
      const m = minutesOf(tr.TimeOfDayTicks);
      const d = new Date(serverNow);
      d.setHours(Math.floor(m / 60), m % 60, 0, 0);
      if (tr.Type === 'DailyTrigger') {
        if (d <= serverNow) d.setDate(d.getDate() + 1);
      } else {
        const want = WEEKDAYS.indexOf((tr.DayOfWeek ?? 'Sunday') as DayOfWeek);
        let add = (want - d.getDay() + 7) % 7;
        if (add === 0 && d <= serverNow) add = 7;
        d.setDate(d.getDate() + add);
      }
      consider(new Date(d.getTime() - shift));
    } else if (tr.Type === 'IntervalTrigger') {
      const end = realDate(task.LastExecutionResult?.EndTimeUtc);
      if (end) consider(new Date(end.getTime() + minutesOf(tr.IntervalTicks) * 60_000));
    }
  }
  return best;
}

/* "at 03:00" within a day, else "Sunday at 04:00". */
export function nextText(d: Date): string {
  if (d.getTime() - Date.now() < 24 * 3600_000) return t('at {time}', { time: clock(d) });
  return dayAt(d);
}

/* ── Last run ── */

export interface LastRun { text: string; failed?: boolean; logDay?: Date; title?: string }

export function lastRunText(task: TaskInfo, startedAt?: number): LastRun {
  if (task.State === 'Cancelling') return { text: t('Stopping…') };
  if (task.State === 'Running') return { text: startedAt ? t('Running since {time}', { time: clock(new Date(startedAt)) }) : t('Running now') };
  const r = task.LastExecutionResult;
  const start = realDate(r?.StartTimeUtc);
  const end = realDate(r?.EndTimeUtc) ?? start;
  if (!r || !start || !end) return { text: t('Never run') };
  const took = runLength(end.getTime() - start.getTime());
  switch (r.Status) {
    case 'Failed': {
      const days = Math.round((Date.now() - end.getTime()) / 86_400_000);
      const when = days < 2 ? dayAt(end) : onDay(end);
      return { text: t('Failed {when}', { when }), failed: true, logDay: end, title: r.ErrorMessage ?? undefined };
    }
    case 'Cancelled': return { text: t('Stopped {when} · after {time}', { when: dayAt(start), time: took }) };
    case 'Aborted': return { text: t('Interrupted {when}', { when: dayAt(start) }), title: t('The server stopped while it was running') };
    default: return { text: t('Last run {when} · {time}', { when: dayAt(start), time: took }) };
  }
}

/* Jellyfin's daily log for a date: log_20261019.log. */
export function logNameFor(d: Date): string {
  return `log_${d.getUTCFullYear()}${String(d.getUTCMonth() + 1).padStart(2, '0')}${String(d.getUTCDate()).padStart(2, '0')}.log`;
}

/* "Scan Media Library" → "Scan media library"; names like TheMovieDb keep their capitals. */
export function sentenceCase(name: string): string {
  return name.split(' ').map((w, i) => (i > 0 && /^[A-Z][a-z]+$/.test(w) ? w.toLowerCase() : w)).join(' ');
}

/* When the server started: the two startup tasks run together at start (within milliseconds). After their
   24 h interval runs they drift apart, and then the start time can't be known from the API. */
export function serverStartFromTasks(tasks: TaskInfo[]): Date | undefined {
  const starts = tasks
    .filter((t) => t.Triggers?.some((tr) => tr.Type === 'StartupTrigger') && t.LastExecutionResult?.StartTimeUtc)
    .map((t) => new Date(t.LastExecutionResult!.StartTimeUtc!).getTime());
  if (starts.length < 2) return undefined;
  const min = Math.min(...starts);
  const max = Math.max(...starts);
  return max - min < 25 ? new Date(min) : undefined;
}
