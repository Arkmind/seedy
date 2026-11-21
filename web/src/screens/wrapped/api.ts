import { get, post } from '../../api/client';
import type { BaseItem } from '../../api/types';

/* /Seedy/Me/Wrapped: each profile's year on Seedy, built once in December (1 January to 30 November, in the time zone
   the apps report) and kept. Clients write every sentence themselves from these numbers (copy.ts). */

export type StoryKey = 'cover' | 'hours' | 'genres' | 'shows' | 'movie' | 'binge' | 'clock' | 'personality' | 'summary';
export const STORY_KEYS: StoryKey[] = ['cover', 'hours', 'genres', 'shows', 'movie', 'binge', 'clock', 'personality', 'summary'];

export interface WrappedTitle { id: string; name: string; episodes: number; watchMs: number; item?: BaseItem | null }
export interface WrappedGenre { name: string; key?: string | null; pct: number }
export interface WrappedStat { key: string; value: number; extra?: number | null }

export interface Wrapped {
  year: number;
  builtAt: string;
  zone: string;
  stories: string[];
  /** Video watched (shows, movies, Live TV, other videos), ms. */
  watchMs: number;
  plays: number;
  prevWatchMs?: number | null;
  rank?: number | null;
  viewers: number;
  genres: WrappedGenre[];
  otherPct: number;
  shows: WrappedTitle[];
  seasonsFinished: number;
  showsStarted: number;
  movie?: { id: string; name: string; views: number; watchMs: number; dates: string[]; item?: BaseItem | null } | null;
  movies: number;
  binge?: {
    id: string; name: string; episodes: number; season?: number | null; seasonTo?: number | null;
    lastSeason?: number | null; lastEpisode?: number | null; start: string; end: string; item?: BaseItem | null;
    /** Minutes watched of each episode, in order. */
    minutes?: number[];
  } | null;
  still: { asked: number; yes: number };
  clock?: { hours: number[]; peak: string; kind: 'night' | 'morning' | 'afternoon' | 'evening'; pct: number; day: number } | null;
  personality: { key: string; stats: WrappedStat[] };
  musicMs: number;
  books: number;
}

export interface WrappedStatus { years: number[]; current?: { year: number; watchMs: number; stories: number } | null }

const timeZone = () => {
  try { return Intl.DateTimeFormat().resolvedOptions().timeZone; } catch { return undefined; }
};

export const getWrappedStatus = () => get<WrappedStatus>('/Seedy/Me/Wrapped', { tz: timeZone() });
export const getWrapped = (year: number) => get<Wrapped>(`/Seedy/Me/Wrapped/${year}`, { tz: timeZone() });

/* The stories this app knows, in the server's order. */
export const storiesOf = (w: Wrapped): StoryKey[] => w.stories.filter((s): s is StoryKey => (STORY_KEYS as string[]).includes(s));

/* A player moment Wrapped counts: an intro skipped, "Still watching?" asked or answered. Fire and forget. */
export function recordMoment(kind: 'introSkipped' | 'stillAsked' | 'stillYes' | 'stillStop', itemId?: string, ms?: number) {
  post<void>('/Seedy/Me/Moments', { kind, itemId, ms: ms ? Math.round(ms) : undefined }).catch(() => { /* not worth bothering anyone */ });
}
