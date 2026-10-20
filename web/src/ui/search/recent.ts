import { bootState, meStore } from '../../lib/app-state';
import { useEffect } from 'preact/hooks';
import { useStore } from '../../lib/store';
import { saveCustom } from '../../screens/me/prefs';

/* Recent searches follow the profile (prefs.custom "search.recent", newest first, at most 8). */

export const RECENT_KEY = 'search.recent';
const RECENT_MAX = 8;
/* Where earlier versions kept them, on this device only; moved into the profile the first time they're read. */
const LEGACY_KEY = 'seedy.recentSearches';

function valid(v: unknown): string[] {
  return Array.isArray(v) ? v.filter((s): s is string => typeof s === 'string' && s.trim().length > 0).slice(0, RECENT_MAX) : [];
}

export function readRecent(): string[] {
  return valid(meStore.get().prefs?.custom?.[RECENT_KEY]);
}

let migrated = false;
function migrate() {
  if (migrated) return;
  migrated = true;
  let legacy: string[] = [];
  try {
    const raw = window.localStorage.getItem(LEGACY_KEY);
    if (raw) legacy = valid(JSON.parse(raw));
    window.localStorage.removeItem(LEGACY_KEY);
  } catch { /* storage unavailable */ }
  if (!legacy.length) return;
  const mine = readRecent();
  const merged = [...mine, ...legacy.filter((s) => !mine.some((m) => m.toLowerCase() === s.toLowerCase()))].slice(0, RECENT_MAX);
  if (merged.length !== mine.length) void saveCustom({ [RECENT_KEY]: merged });
}

export function useRecent(): string[] {
  const me = useStore(meStore);
  useEffect(() => { if (bootState.get() === 'ready') migrate(); }, []);
  return valid(me.prefs?.custom?.[RECENT_KEY]);
}

export function addRecent(q: string) {
  const t = q.trim().replace(/\s+/g, ' ');
  // Only while signed in: a page closing on sign-out must not queue a write for whoever signs in next.
  if (t.length < 2 || bootState.get() !== 'ready') return;
  const list = readRecent();
  if (list[0]?.toLowerCase() === t.toLowerCase()) return;
  const next = [t, ...list.filter((s) => s.toLowerCase() !== t.toLowerCase())].slice(0, RECENT_MAX);
  void saveCustom({ [RECENT_KEY]: next });
}

export function clearRecent() {
  void saveCustom({ [RECENT_KEY]: [] });
}
