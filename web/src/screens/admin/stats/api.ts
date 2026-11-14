import { get, request } from '../../../api/client';
import type { BaseItem } from '../../../api/types';

/* /Seedy/Admin/Stats: the play log added up. Times are UTC ISO strings; dates ("2026-11-08") are local to the
   time zone the page sends. */

export type Range = '7d' | '30d' | '12m' | 'all';
export type Kind = 'show' | 'movie' | 'music' | 'livetv' | 'book' | 'other';
export type Method = 'DirectPlay' | 'DirectStream' | 'Transcode';

export interface StatsPeak { streams: number; at: string; bitrate?: number | null }

export interface Stats {
  range: Range;
  bucket: 'day' | 'week' | 'month';
  from: string;
  to: string;
  today: string;
  retentionDays: number;
  /* The day of the oldest play in the log. */
  firstPlay?: string | null;
  kpis: {
    watchMs: number; prevWatchMs?: number | null;
    plays: number; prevPlays?: number | null;
    viewers: number; totalUsers: number; missing: string[];
    peak?: StatsPeak | null;
    transcodePct?: number | null; prevTranscodePct?: number | null;
  };
  buckets: { start: string; watchMs: number; plays: number; kinds: Record<Kind, number> }[];
  methods: { directPlay: number; directStream: number; transcode: number };
  reasons: { key: 'video' | 'bitrate' | 'subtitles' | 'audio' | 'container' | 'other'; plays: number; codec?: string | null; devices: string[]; capMbps?: number | null }[];
  kinds: Record<Kind, number>;
  heat: number[][];
  heatViewers: number[][];
  viewers: StatsViewer[];
  devices: { key: string; name: string; apps: string[]; watchMs: number }[];
  top: { titleId: string; kind: Kind | 'audiobook'; name: string; watchMs: number; plays: number; viewers: number; items: number; item?: BaseItem | null }[];
  filters: { users: { id: string; name: string }[]; libraries: { id: string; name: string }[]; devices: string[] };
}

export interface StatsViewer {
  userId: string; name: string; imageTag?: string | null; index: number;
  watchMs: number; plays: number; device?: string | null; deviceCount: number; enabled: boolean;
}

export interface StatsSession {
  id: string; userId: string; kind: Kind; itemType: string; itemId: string; titleId: string;
  title: string; itemName: string; season?: number | null; episode?: number | null; year?: number | null; artist?: string | null;
  start: string; end: string; watchMs: number; percent?: number | null; finished: boolean;
  device?: string | null; method?: Method | null; bitrate?: number | null; count: number;
}

export interface StatsDay {
  date: string; today: string; start: string; end: string;
  avgDayMs: number; rank: number; daysRanked: number; totalUsers: number;
  users: StatsViewer[];
  sessions: StatsSession[];
}

export interface StatsFilters { range: Range; userId?: string; libraryId?: string; device?: string }

export const timeZone = () => {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone || undefined; } catch { return undefined; }
};

const query = (f: StatsFilters) => ({ range: f.range, tz: timeZone(), userId: f.userId, libraryId: f.libraryId, device: f.device });

export const getStats = (f: StatsFilters, signal?: AbortSignal) => get<Stats>('/Seedy/Admin/Stats', query(f), signal);

export const getStatsDay = (date: string, signal?: AbortSignal) => get<StatsDay>('/Seedy/Admin/Stats/Day', { date, tz: timeZone() }, signal);

export async function exportStats(f: StatsFilters) {
  const res = await request<Response>('GET', '/Seedy/Admin/Stats/Export', { query: query(f), raw: true });
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `seedy-statistics-${f.range}.csv`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
