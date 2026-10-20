import { useEffect, useState } from 'preact/hooks';
import {
  cancelSeriesTimer, cancelTimer, getChannels, getLiveTvConfig, getSeriesTimers, getTimers, recordProgram, recordSeries,
  type Channel, type Program, type SeriesTimer, type Timer, type TunerHost
} from '../../api/livetv';
import { setFavorite } from '../../api/jellyfin';
import { meStore } from '../../lib/app-state';
import { clock, duration, episodeTag, startOfDay } from '../../lib/format';
import { locale, t } from '../../lib/i18n';
import { createStore, safeLocal, saveLocal, useStore } from '../../lib/store';
import { confirm, toast } from '../../ui/overlay';

/* Live TV data shared by the four tabs and the live player: the channel list (with what's on now), the recording
   timers, favourites, and the copy formats the boards use ("20:45 – 22:45", "55 min left", "In 10 min"). */

/* ── Channels ── */

export interface LiveChannel extends Channel {
  /* The number Seedy shows: Jellyfin's, or for playlist channels without one, the next free number in name order. */
  num: string;
  /* The name without the playlist's "(1080p)" and "[Geo-blocked]" notes. */
  label: string;
  /* "HD" / "4K" when the playlist name says so. */
  hd?: string;
}

interface ChannelsState { list: LiveChannel[] | null; error?: Error; at: number }
export const channelsStore = createStore<ChannelsState>({ list: null, at: 0 });

export function cleanName(name: string): string {
  const n = name.replace(/\s*\[[^\]]*\]\s*/g, ' ').replace(/\s*\((?:\d{3,4}[pi]|SD|HD|FHD|UHD|4K)\)\s*/gi, ' ').replace(/\s+/g, ' ').trim();
  return n || name;
}

function hdOf(name: string): string | undefined {
  const m = /\((\d{3,4})[pi]\)/i.exec(name);
  if (m) return Number(m[1]) >= 2000 ? '4K' : Number(m[1]) >= 720 ? 'HD' : undefined;
  return /\b(UHD|4K)\b/.test(name) ? '4K' : /\b(HD|FHD)\b/.test(name) ? 'HD' : undefined;
}

const numVal = (n: string) => {
  const v = parseFloat(n);
  return Number.isFinite(v) ? v : Number.MAX_SAFE_INTEGER;
};

function decorate(items: Channel[]): LiveChannel[] {
  const used = new Set(items.map((c) => c.ChannelNumber ?? c.Number).filter((n): n is string => !!n));
  const byName = [...items].sort((a, b) => cleanName(a.Name).localeCompare(cleanName(b.Name), locale(), { sensitivity: 'base' }));
  const nums = new Map<string, string>();
  let next = 1;
  for (const c of byName) {
    const own = c.ChannelNumber ?? c.Number;
    if (own) { nums.set(c.Id, own); continue; }
    while (used.has(String(next))) next++;
    nums.set(c.Id, String(next));
    used.add(String(next));
  }
  return items
    .map((c) => ({ ...c, num: nums.get(c.Id) ?? '', label: cleanName(c.Name), hd: hdOf(c.Name) }))
    .sort((a, b) => numVal(a.num) - numVal(b.num) || a.label.localeCompare(b.label, locale()));
}

let loading: Promise<void> | null = null;
export function loadChannels(force = false): Promise<void> {
  const s = channelsStore.get();
  if (!force && s.list && Date.now() - s.at < 60_000) return Promise.resolve();
  if (loading) return loading;
  loading = getChannels({ sortBy: 'SortName' })
    .then((r) => channelsStore.set({ list: decorate(r.Items ?? []), at: Date.now() }))
    .catch((error: Error) => channelsStore.set((p) => ({ list: p.list, error, at: p.at })))
    .finally(() => { loading = null; });
  return loading;
}

/* The channel list, loaded on first use and refreshed every minute while a Live TV page is open (what's on now moves on). */
export function useChannels(): ChannelsState & { reload: () => void } {
  const s = useStore(channelsStore);
  useEffect(() => {
    void loadChannels();
    const id = window.setInterval(() => { if (document.visibilityState === 'visible') void loadChannels(true); }, 60_000);
    return () => window.clearInterval(id);
  }, []);
  return { ...s, reload: () => void loadChannels(true) };
}

export const isFavorite = (c?: Channel) => !!c?.UserData?.IsFavorite;

export async function toggleFavorite(c: LiveChannel) {
  const on = !isFavorite(c);
  const patch = (v: boolean) => channelsStore.set((s) => ({
    ...s,
    list: s.list?.map((x) => (x.Id === c.Id ? { ...x, UserData: { ...(x.UserData ?? {}), IsFavorite: v } } : x)) ?? null
  }));
  patch(on);
  try {
    await setFavorite(c.Id, on);
    toast(on ? t('{name} added to favorite channels', { name: c.label }) : t('{name} removed from favorite channels', { name: c.label }));
  } catch {
    patch(!on);
    toast(t("Couldn't update your favorite channels"), 'error');
  }
}

/* ── Last watched (this device) ── */

const LAST = 'seedy.device.liveLast';
const PREV = 'seedy.device.livePrev';
export const lastChannelId = () => safeLocal<string | null>(LAST, null);
export const previousChannelId = () => safeLocal<string | null>(PREV, null);
export function noteWatched(id: string) {
  const last = lastChannelId();
  if (last === id) return;
  if (last) saveLocal(PREV, last);
  saveLocal(LAST, id);
}

/* ── Timers (recordings) ── */

interface TimersState { timers: Timer[] | null; series: SeriesTimer[] | null; error?: Error; at: number }
export const timersStore = createStore<TimersState>({ timers: null, series: null, at: 0 });

let timersLoading: Promise<void> | null = null;
export function loadTimers(force = false): Promise<void> {
  const s = timersStore.get();
  if (!force && s.timers && Date.now() - s.at < 30_000) return Promise.resolve();
  if (timersLoading) return timersLoading;
  timersLoading = Promise.all([getTimers(), getSeriesTimers()])
    .then(([a, b]) => timersStore.set({ timers: a.Items ?? [], series: b.Items ?? [], at: Date.now() }))
    .catch((error: Error) => timersStore.set((p) => ({ ...p, error })))
    .finally(() => { timersLoading = null; });
  return timersLoading;
}

export function useTimers(): TimersState & { reload: () => void } {
  const s = useStore(timersStore);
  useEffect(() => {
    void loadTimers();
    const id = window.setInterval(() => { if (document.visibilityState === 'visible') void loadTimers(true); }, 30_000);
    return () => window.clearInterval(id);
  }, []);
  return { ...s, reload: () => void loadTimers(true) };
}

export const recordingNow = (timers: Timer[] | null) => (timers ?? []).filter((x) => x.Status === 'InProgress');

/* A muted preview holds a tuner like any stream. While something records, or a recording starts within two minutes,
   the heroes show the artwork instead, so a preview never takes the tuner a recording needs. */
export function previewAllowed(timers: Timer[] | null, now: number): boolean {
  const tuners = tunersStore.get();
  if (tuners?.length && tuners.every((x) => !x.TunerCount)) return true;
  if (!timers) return false;
  return !timers.some((x) => x.Status === 'InProgress'
    || ((x.Status === 'New' || !x.Status) && startMs(x) - (x.PrePaddingSeconds ?? 0) * 1000 - now < 2 * 60_000 && endMs(x) > now));
}

/* Whether a programme is being recorded now, or is set to record. */
export function recState(p: Program | undefined, timers: Timer[] | null): 'now' | 'set' | null {
  if (!p) return null;
  const tm = (timers ?? []).find((x) => (p.TimerId && x.Id === p.TimerId) || (x.ProgramId && x.ProgramId === p.Id));
  if (tm?.Status === 'InProgress') return 'now';
  if (tm || p.TimerId) return tm?.Status === 'Cancelled' ? null : 'set';
  return null;
}

const timerFor = (p: Program) => (timersStore.get().timers ?? []).find((x) => (p.TimerId && x.Id === p.TimerId) || x.ProgramId === p.Id);

/* Record this airing, or (when one is set) offer to cancel it. Returns whether anything changed. */
export async function toggleRecord(p: Program): Promise<boolean> {
  const existing = timerFor(p) ?? (p.TimerId ? ({ Id: p.TimerId, Status: 'New' } as Timer) : undefined);
  const title = p.Name ?? '';
  if (existing) {
    const now = existing.Status === 'InProgress';
    const ok = await confirm({
      title: now ? t('Stop recording {title}?', { title }) : t('Cancel the recording of {title}?', { title }),
      text: now ? t('What has been recorded so far is kept.') : t('It will no longer record.'),
      action: now ? t('Stop recording') : t('Cancel recording'),
      danger: true
    });
    if (!ok) return false;
    try {
      await cancelTimer(existing.Id);
      toast(now ? t('Recording stopped') : t('Recording cancelled'));
    } catch {
      toast(t("Couldn't cancel the recording"), 'error');
      return false;
    }
  } else {
    try {
      await recordProgram(p.Id);
      toast(t('{title} will record', { title }));
    } catch {
      toast(t("Couldn't set the recording"), 'error');
      return false;
    }
  }
  await Promise.all([loadTimers(true), loadChannels(true)]);
  programsChanged.set((n) => n + 1);
  return true;
}

export async function toggleSeries(p: Program): Promise<boolean> {
  const title = p.Name ?? '';
  if (p.SeriesTimerId) {
    const ok = await confirm({
      title: t('Stop recording {title}?', { title }),
      text: t('New episodes will no longer record. Recordings you already have are kept.'),
      action: t('Delete rule'),
      danger: true
    });
    if (!ok) return false;
    try {
      await cancelSeriesTimer(p.SeriesTimerId);
      toast(t('Series rule deleted'));
    } catch {
      toast(t("Couldn't delete the series rule"), 'error');
      return false;
    }
  } else {
    try {
      await recordSeries(p.Id);
      toast(t('Every episode of {title} will record', { title }));
    } catch {
      toast(t("Couldn't set the recording"), 'error');
      return false;
    }
  }
  await Promise.all([loadTimers(true), loadChannels(true)]);
  programsChanged.set((n) => n + 1);
  return true;
}

/* Bumped when timers change, so guide programmes (which carry TimerId) are fetched again. */
export const programsChanged = createStore(0);

/* ── Tuners (admins can read how many streams play at once) ── */

export const tunersStore = createStore<TunerHost[] | null>(null);
let tunersAsked = false;
export function loadTuners() {
  if (tunersAsked || !meStore.get().isAdmin) return;
  tunersAsked = true;
  getLiveTvConfig().then((c) => tunersStore.set(c.TunerHosts ?? []), () => tunersStore.set([]));
}

/* ── Time ── */

/* The current time, ticking every 15 s (progress bars, "10 min left", the guide's now line). */
export function useNow(every = 15_000): number {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = window.setInterval(() => setNow(Date.now()), every);
    return () => window.clearInterval(id);
  }, [every]);
  return now;
}

export const startMs = (p?: { StartDate?: string }) => (p?.StartDate ? Date.parse(p.StartDate) : NaN);
export const endMs = (p?: { EndDate?: string }) => (p?.EndDate ? Date.parse(p.EndDate) : NaN);
export const isAiring = (p: Program | undefined, now: number) => !!p && startMs(p) <= now && now < endMs(p);
export function fraction(p: Program | undefined, now: number): number {
  if (!p) return 0;
  const s = startMs(p);
  const e = endMs(p);
  return e > s ? Math.max(0, Math.min(1, (now - s) / (e - s))) : 0;
}

/* "20:45 – 22:45" */
export const timeRange = (p: { StartDate?: string; EndDate?: string }) =>
  `${clock(new Date(startMs(p)))} – ${clock(new Date(endMs(p)))}`;

/* "55 min left", "1 h 10 min left" */
export const leftLabel = (p: Program, now: number) => t('{time} left', { time: duration(Math.max(60, (endMs(p) - now) / 1000)) });

/* "Now", "In 10 min", "23:00", "Tomorrow" */
export function soonLabel(p: Program, now: number): string {
  const s = startMs(p);
  const mins = Math.round((s - now) / 60_000);
  if (mins <= 0) return t('Now');
  if (mins < 60) return t('In {n} min', { n: mins });
  if (startOfDay(new Date(s)).getTime() !== startOfDay(new Date(now)).getTime() && new Date(s).getHours() >= 6) return t('Tomorrow');
  return clock(new Date(s));
}

/* "Tomorrow 13:00" for a programme on another day, else "22:00 – 22:30". */
export function whenLine(p: Program, now: number): string {
  const s = new Date(startMs(p));
  const days = Math.round((startOfDay(s).getTime() - startOfDay(new Date(now)).getTime()) / 86_400_000);
  if (days >= 1 && s.getHours() >= 6) {
    return days === 1 ? t('Tomorrow {time}', { time: clock(s) }) : `${weekdayShort(s)} ${clock(s)}`;
  }
  return timeRange(p);
}

/* "Mon" / "lun." as each language writes it. */
export const weekdayShort = (d: Date) => new Intl.DateTimeFormat(locale(), { weekday: 'short' }).format(d);

/* "The Undercroft · S1 E3" */
export function programTitle(p: Program): string {
  const ep = p.IsSeries && p.IndexNumber != null ? episodeTag(p) : '';
  return ep ? `${p.Name} · ${ep}` : p.Name ?? '';
}

/* The programme's kind as the boards write it: its own genre ("Football", "Drama"), else Film, Sports, News… */
const GENERIC = new Set(['movie', 'movies', 'film', 'sports', 'sport', 'news', 'kids', 'children', 'series', 'show', 'tv show', 'documentary']);
export function categoryOf(p: Program): string {
  const g = (p.Genres ?? []).find((x) => !GENERIC.has(x.toLowerCase()));
  if (g) return g;
  if (p.IsMovie) return t('Film');
  if (p.IsSports) return t('Sports');
  if (p.IsNews) return t('News');
  if (p.IsKids) return t('Kids');
  if ((p.Genres ?? []).some((x) => x.toLowerCase() === 'documentary')) return t('Documentary');
  if (p.IsSeries) return t('Series');
  return '';
}

/* The tag pill a programme earns: a premiere, or a new episode (Jellyfin's own rule: a series airing that isn't a repeat). */
export function newTag(p: Program): 'premiere' | 'new' | null {
  if (p.IsPremiere) return 'premiere';
  if (p.IsSeries && !p.IsRepeat && !p.IsLive && !p.IsNews) return 'new';
  return null;
}

/* Guide categories, in the board's order. */
export type Category = 'news' | 'sports' | 'movies' | 'kids';
export const CATEGORIES: { id: Category; label: string; test: (p: Program) => boolean }[] = [
  { id: 'news', label: 'News', test: (p) => !!p.IsNews },
  { id: 'sports', label: 'Sports', test: (p) => !!p.IsSports },
  { id: 'movies', label: 'Movies', test: (p) => !!p.IsMovie },
  { id: 'kids', label: 'Kids', test: (p) => !!p.IsKids }
];

/* Two letters for a channel without a logo ("Kestrel Sports" → KS, "Meridian" → Me). */
export function initials(name: string): string {
  const words = cleanName(name).replace(/[^\p{L}\p{N} ]/gu, ' ').split(/\s+/).filter(Boolean);
  if (words.length >= 2) return (words[0][0] + words[1][0]).toUpperCase();
  const w = words[0] ?? '?';
  return w.length > 1 ? w[0].toUpperCase() + w[1].toLowerCase() : w.toUpperCase();
}

/* The boards' channel colours, picked by name. */
const LOGO_BG = ['#2f4a7a', '#b33a3a', '#1f7a3a', '#6b3fa0', '#c9852b', '#2a6f73', '#2b3f8f', '#3f8fb3', '#8a6d3b', '#f2c94c', '#d96a9b', '#3a3a3a'];
const LIGHT = new Set(['#c9852b', '#f2c94c', '#d96a9b']);
export function channelColor(name: string): { bg: string; ink: string } {
  let h = 0;
  for (const ch of cleanName(name)) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  const bg = LOGO_BG[h % LOGO_BG.length];
  return { bg, ink: LIGHT.has(bg) ? '#1e1e1e' : '#ffffff' };
}

/* Day boundaries for "tonight": until 5 in the morning. */
export function tonightEnd(now: number): number {
  const d = new Date(now);
  const end = startOfDay(d);
  end.setHours(5);
  if (d.getHours() >= 5) end.setDate(end.getDate() + 1);
  return end.getTime();
}
