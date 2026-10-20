import { authHeader, get, getSession, post, request, url } from '../../api/client';
import { CARD_FIELDS, getMe, getUserViews } from '../../api/jellyfin';
import { getMe as getSeedyMe, savePrefs, type PlaybackPrefs, type UserPrefs } from '../../api/seedy';
import type { ItemsResult, User, UserConfiguration } from '../../api/types';
import { applyBranding, DEFAULT_PLAYBACK, meStore, setPrefsBusyCheck, userStore, viewsStore } from '../../lib/app-state';
import { createStore, safeLocal, saveLocal, useStore } from '../../lib/store';

/* Personal settings (Settings · Profile, Home screen, Display, Controls).

   Three homes, as the boards mark them:
   - Seedy's profile prefs (`/Seedy/Me/Prefs`), including the free `custom` bag — follows the profile everywhere.
   - Jellyfin's UserConfiguration — Jellyfin's own settings, so they apply in Jellyfin's other apps too.
   - This browser (localStorage `seedy.device.*`) — rows tagged "This device". */

/* ── Keys in prefs.custom (owned by these pages; documented in the round report) ── */
export const CUSTOM = {
  accent: 'display.accent',
  language: 'display.language',
  dateLocale: 'display.dateLocale',
  backdrops: 'display.backdrops',
  themeMusic: 'display.themeMusic',
  themeVideos: 'display.themeVideos',
  opensOn: 'home.opensOn',
  nextUpDays: 'home.nextUpDays',
  rewatching: 'home.rewatching',
  episodeImages: 'home.episodeImages',
  dismissedArrivals: 'home.dismissedArrivals'
} as const;

export const CUSTOM_DEFAULTS = {
  [CUSTOM.accent]: null as string | null,
  [CUSTOM.language]: 'auto',
  [CUSTOM.dateLocale]: 'auto',
  [CUSTOM.backdrops]: true,
  [CUSTOM.themeMusic]: false,
  [CUSTOM.themeVideos]: false,
  [CUSTOM.opensOn]: {} as Record<string, string>,
  [CUSTOM.nextUpDays]: 365,
  [CUSTOM.rewatching]: false,
  [CUSTOM.episodeImages]: true,
  [CUSTOM.dismissedArrivals]: [] as number[]
};

type CustomKey = keyof typeof CUSTOM_DEFAULTS;
type CustomValue<K extends CustomKey> = (typeof CUSTOM_DEFAULTS)[K];

function sameType(a: unknown, b: unknown) {
  if (b === null) return a === null || typeof a === 'string';
  if (Array.isArray(b)) return Array.isArray(a);
  return typeof a === typeof b && a !== null;
}

/* A custom value with its default when unset or of the wrong shape. */
export function readCustom<K extends CustomKey>(key: K, prefs: UserPrefs = meStore.get().prefs): CustomValue<K> {
  const v = prefs?.custom?.[key];
  return (v !== undefined && sameType(v, CUSTOM_DEFAULTS[key]) ? v : CUSTOM_DEFAULTS[key]) as CustomValue<K>;
}

export function useCustom<K extends CustomKey>(key: K): CustomValue<K> {
  const me = useStore(meStore);
  return readCustom(key, me.prefs);
}

/* ── Saving: changes save as they're made; the note at the foot of each page shows the state ── */

export type SaveState = 'saved' | 'saving' | 'error';
export const saveState = createStore<SaveState>('saved');

/* Every prefs write in Seedy goes through this one queue (Settings pages, Home's row menus, the player): one request
   at a time, so the server applies changes in the order they were made, and changes made meanwhile ride along in the
   next request instead of racing it. */
let pending: Partial<UserPrefs> = {};
let waiters: ((ok: boolean) => void)[] = [];
let timer: number | undefined;
let inFlight = false;
let jfInFlight = 0;
/* Bumped on every local change, so a slower read of the profile never overwrites a newer change. */
let localVersion = 0;
const settle = () => { if (!inFlight && !jfInFlight && !Object.keys(pending).length && saveState.get() !== 'error') saveState.set('saved'); };

function mergePatch(base: Partial<UserPrefs>, patch: Partial<UserPrefs>): Partial<UserPrefs> {
  const custom = patch.custom ? { ...(base.custom ?? {}), ...patch.custom } : base.custom;
  return { ...base, ...patch, ...(custom ? { custom } : {}) };
}

function mergeCustom(base: Record<string, unknown>, patch?: Record<string, unknown>) {
  if (!patch) return base;
  const next = { ...base };
  for (const [k, v] of Object.entries(patch)) {
    if (v === null || v === undefined) delete next[k];
    else next[k] = v;
  }
  return next;
}

function applyLocal(patch: Partial<UserPrefs>) {
  meStore.set((m) => ({
    ...m,
    prefs: { ...m.prefs, ...patch, custom: mergeCustom(m.prefs.custom ?? {}, patch.custom as Record<string, unknown> | undefined) }
  }));
}

async function flush() {
  window.clearTimeout(timer);
  timer = undefined;
  // One at a time: the save on its way sends what's queued when it's back.
  if (inFlight) return;
  const body = pending;
  const done = waiters;
  pending = {};
  waiters = [];
  if (!Object.keys(body).length) {
    done.forEach((f) => f(true));
    return settle();
  }
  inFlight = true;
  try {
    const saved = await savePrefs(body);
    inFlight = false;
    if (Object.keys(pending).length) {
      // Changed while this was on the way: send that now (a number field still being typed in waits for its timer).
      if (timer === undefined) void flush();
    } else if (saved) {
      meStore.set((m) => ({ ...m, prefs: saved }));
      void refreshProfile();
    }
    if (saveState.get() === 'error') saveState.set('saving');
    settle();
    done.forEach((f) => f(true));
  } catch {
    inFlight = false;
    // Put the change back in front of anything newer so "Try again" (or the next change) resends it.
    pending = mergePatch(body, pending);
    saveState.set('error');
    done.forEach((f) => f(false));
    // Changes made meanwhile now wait for "Try again" too, unless a delayed save is about to send them anyway.
    if (timer === undefined) {
      const later = waiters;
      waiters = [];
      later.forEach((f) => f(false));
    }
  }
}

/* The rest of the profile (feature flags, branding) after a save; skipped if something changed since it was asked for. */
async function refreshProfile() {
  const v = localVersion;
  try {
    const me = await getSeedyMe();
    if (v !== localVersion || inFlight || Object.keys(pending).length) return;
    meStore.set(me);
    applyBranding(me.branding);
  } catch { /* keep the last known profile */ }
}

/* Saves part of the profile's prefs; resolves false if the server refused it (it stays queued for "Try again").
   `delay` batches typing in number fields. */
setPrefsBusyCheck(() => inFlight || Object.keys(pending).length > 0);

export function savePrefsPatch(patch: Partial<UserPrefs>, delay = 0): Promise<boolean> {
  localVersion++;
  applyLocal(patch);
  pending = mergePatch(pending, patch);
  saveState.set('saving');
  window.clearTimeout(timer);
  timer = window.setTimeout(flush, delay);
  return new Promise<boolean>((resolve) => waiters.push(resolve));
}

export const saveCustom = (patch: Record<string, unknown>, delay = 0) => savePrefsPatch({ custom: patch }, delay);

/* The plugin replaces playback prefs as a whole, so send every field. */
export const savePlayback = (patch: Partial<PlaybackPrefs>) =>
  savePrefsPatch({ playback: { ...DEFAULT_PLAYBACK, ...(meStore.get().prefs?.playback ?? {}), ...patch } });

/* "Try again" after a failed save. */
export function retrySave() {
  saveState.set('saving');
  const prefs = Object.keys(pending).length > 0;
  if (prefs) void flush();
  if (lastJfPatch) void saveUserConfig(lastJfPatch);
  else if (!prefs) settle();
}

/* ── Jellyfin's UserConfiguration ── */

export type FullUserConfig = UserConfiguration & {
  DisplayMissingEpisodes?: boolean;
  GroupedFolders?: string[];
  OrderedViews?: string[];
  LatestItemsExcludes?: string[];
  MyMediaExcludes?: string[];
  HidePlayedInLatest?: boolean;
  DisplayCollectionsView?: boolean;
  EnableLocalPassword?: boolean;
  CastReceiverId?: string | null;
};

export const userConfig = (u: User | null = userStore.get()): FullUserConfig => (u?.Configuration ?? {}) as FullUserConfig;

let lastJfPatch: Partial<FullUserConfig> | null = null;
let jfQueue: Promise<unknown> = Promise.resolve();
/* Changes shown already but not saved yet: a save that comes back must not hide the ones queued behind it. */
const jfOutstanding: Partial<FullUserConfig>[] = [];

/* Merge into a fresh copy of the full object (another page or app may have saved since) and post it. Saves run one
   at a time; the m2 pages (Playback, Subtitles) use this same queue. */
export function saveUserConfig(patch: Partial<FullUserConfig>, opts: { refreshViews?: boolean } = {}): Promise<boolean> {
  const before = userStore.get();
  if (!before) return Promise.resolve(false);
  userStore.set({ ...before, Configuration: { ...userConfig(before), ...patch } as UserConfiguration });
  saveState.set('saving');
  jfInFlight++;
  jfOutstanding.push(patch);
  const drop = () => { const i = jfOutstanding.indexOf(patch); if (i >= 0) jfOutstanding.splice(i, 1); };
  const run = async () => {
    try {
      const fresh = await getMe();
      const config = { ...userConfig(fresh), ...patch };
      await post<void>('/Users/Configuration', config, { userId: before.Id });
      drop();
      userStore.set((u) => (u ? { ...u, Configuration: Object.assign({}, config, ...jfOutstanding) as UserConfiguration } : u));
      if (lastJfPatch) {
        // A save after a failed one: it carried the failed fields only if they were the same ones.
        const left = Object.fromEntries(Object.entries(lastJfPatch).filter(([k]) => !(k in patch)));
        lastJfPatch = Object.keys(left).length ? left : null;
      }
      jfInFlight--;
      if (opts.refreshViews) await reloadViews();
      if (!lastJfPatch && saveState.get() === 'error' && !Object.keys(pending).length) saveState.set('saving');
      settle();
      return true;
    } catch {
      drop();
      jfInFlight--;
      lastJfPatch = { ...(lastJfPatch ?? {}), ...patch };
      // Put back only what this change touched (later changes stay on screen until their own save answers).
      const old = userConfig(before) as Record<string, unknown>;
      userStore.set((u) => {
        if (!u) return u;
        const cfg = { ...userConfig(u) } as Record<string, unknown>;
        for (const k of Object.keys(patch)) if (!jfOutstanding.some((p) => k in p)) cfg[k] = old[k];
        return { ...u, Configuration: cfg as UserConfiguration };
      });
      saveState.set('error');
      return false;
    }
  };
  const p = jfQueue.then(run, run);
  jfQueue = p;
  return p;
}

/* The header tabs and My media follow Jellyfin's view order and exclusions. */
export async function reloadViews() {
  try { viewsStore.set((await getUserViews()).Items); } catch { /* keep the old list */ }
}

/* ── This device ── */

export interface DevicePrefs {
  softPlaceholders: boolean;
  fastAnimations: boolean;
  screensaver: 'off' | 'backdrops' | 'logo';
  screensaverAfter: number;
  backdropEvery: number;
  slideshowEvery: number;
  layout: 'auto' | 'desktop' | 'tv';
  customCss: string;
  ignoreServerCss: boolean;
  gamepad: boolean;
  smoothScrolling: boolean;
}

export const DEVICE_DEFAULTS: DevicePrefs = {
  softPlaceholders: true,
  fastAnimations: false,
  screensaver: 'off',
  screensaverAfter: 180,
  backdropEvery: 8,
  slideshowEvery: 5,
  layout: 'auto',
  customCss: '',
  ignoreServerCss: false,
  gamepad: false,
  smoothScrolling: true
};

const deviceKey = (k: keyof DevicePrefs) => `seedy.device.${k}`;

function readDevice(): DevicePrefs {
  const out = { ...DEVICE_DEFAULTS };
  for (const k of Object.keys(DEVICE_DEFAULTS) as (keyof DevicePrefs)[]) {
    const v = safeLocal<unknown>(deviceKey(k), undefined);
    if (v !== undefined && typeof v === typeof DEVICE_DEFAULTS[k]) (out as Record<string, unknown>)[k] = v;
  }
  return out;
}

export const deviceStore = createStore<DevicePrefs>(readDevice());

export function setDevice<K extends keyof DevicePrefs>(key: K, value: DevicePrefs[K]) {
  deviceStore.set((d) => ({ ...d, [key]: value }));
  saveLocal(deviceKey(key), value);
}

/* Another tab changed a device setting. */
if (typeof window !== 'undefined') {
  window.addEventListener('storage', (e) => { if (e.key?.startsWith('seedy.device.')) deviceStore.set(readDevice()); });
}

/* ── Next up, as the person set it (Settings · Home screen) ── */

export function getNextUpForMe(limit = 16, opts: { seriesId?: string; parentId?: string } = {}) {
  const days = readCustom(CUSTOM.nextUpDays);
  const cutoff = days > 0 ? new Date(Date.now() - days * 86_400_000).toISOString() : undefined;
  return get<ItemsResult>('/Shows/NextUp', {
    userId: getSession()?.userId ?? '', limit, seriesId: opts.seriesId, parentId: opts.parentId, enableUserData: true, imageTypeLimit: 1,
    enableResumable: false, enableRewatching: readCustom(CUSTOM.rewatching), disableFirstEpisode: false, nextUpDateCutoff: cutoff,
    enableImageTypes: 'Primary,Backdrop,Thumb,Logo', fields: [...CARD_FIELDS, 'DateLastMediaAdded'].join(','), enableTotalRecordCount: false
  });
}

/* With "Use episode pictures" off, episodes in Next up and Continue watching show their show's artwork instead. */
export function withEpisodeArt<T extends { Type?: string; ImageTags?: Record<string, string> }>(item: T): T {
  if (item.Type !== 'Episode' || readCustom(CUSTOM.episodeImages)) return item;
  const tags = { ...(item.ImageTags ?? {}) };
  delete tags.Primary;
  return { ...item, ImageTags: tags };
}

/* ── Profile picture ── */

export async function uploadUserImage(userId: string, file: File) {
  const data = await new Promise<string>((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result).replace(/^data:[^,]*,/, ''));
    r.onerror = () => reject(r.error);
    r.readAsDataURL(file);
  });
  // The route Jellyfin's own apps use (also proxied by the dev server).
  const res = await fetch(url(`/Users/${userId}/Images/Primary`), {
    method: 'POST', headers: { Authorization: authHeader(), 'Content-Type': file.type || 'image/jpeg' }, body: data
  });
  if (!res.ok) throw new Error((await res.text().catch(() => '')) || res.statusText);
}

export const removeUserImage = (userId: string) => request<void>('DELETE', `/Users/${userId}/Images/Primary`);

/* Refresh the signed-in user (picture tag, configuration) after a change. */
export async function reloadUser() {
  try { userStore.set(await getMe()); } catch { /* keep the last known */ }
}
