import { addNavigationGuard, navigate } from '../../lib/router';
import { useEffect } from 'preact/hooks';
import * as seedy from '../../api/seedy';
import type { AdminConfig, AdminStatus, HomeRowSetting, PlayLogProfile, RowKind, SeerrAccount, SeerrTestResult } from '../../api/seedy';
import { applyBranding, DEFAULT_PLAYBACK, refreshMe } from '../../lib/app-state';
import { createStore, useStore } from '../../lib/store';
import { confirm } from '../../ui/overlay';
import { t } from '../../lib/i18n';

/* Admin state shared by the four admin pages: the whole AdminConfig is loaded once and kept here, so moving
   between pages doesn't refetch. Autosave pages edit it in place and a debounced save sends the whole
   object back; the Home screen page keeps a draft and commits its section explicitly. */

export type SaveState = 'saved' | 'saving' | 'error';

export const configStore = createStore<AdminConfig | null>(null);
const loadErrorStore = createStore<Error | null>(null);
export const saveStateStore = createStore<SaveState>('saved');

/* ── Defaults and normalising ── */

export const LOCKED_ROWS = new Set<RowKind>(['featured', 'continue']);
export const ROW_KINDS: RowKind[] = [
  'featured', 'continue', 'nextup', 'top10', 'because', 'genres', 'mymedia', 'latest',
  'discover', 'upcomingMovies', 'upcomingShows', 'collections'
];
const DEFAULT_COUNT: Partial<Record<RowKind, number>> = { because: 6, genres: 4 };

function normaliseRows(rows: HomeRowSetting[] | undefined): HomeRowSetting[] {
  const list = (rows ?? []).filter((r) => r && ROW_KINDS.includes(r.kind));
  const byKind = new Map(list.map((r) => [r.kind, r]));
  const out: HomeRowSetting[] = [];
  const seen = new Set<RowKind>();
  const push = (r: HomeRowSetting) => {
    if (seen.has(r.kind)) return;
    seen.add(r.kind);
    const count = DEFAULT_COUNT[r.kind];
    out.push(count !== undefined ? { ...r, count: r.count ?? count } : r);
  };
  /* Featured and Continue watching are always first and second, and always on. */
  for (const k of ['featured', 'continue'] as RowKind[]) push({ ...(byKind.get(k) ?? { kind: k }), kind: k, enabled: true });
  for (const r of list) if (!LOCKED_ROWS.has(r.kind)) push({ ...r, enabled: r.enabled !== false });
  for (const k of ROW_KINDS) if (!seen.has(k)) push({ kind: k, enabled: k !== 'collections' });
  return out;
}

const DEF_BRANDING: AdminConfig['branding'] = { name: 'Seedy', accent: '#ff9fff', hasLogo: false, logoVersion: '0', signInBackground: 'backdrops' };
const DEF_BECAUSE: AdminConfig['home']['because'] = { watchedWhen: 'finished', lookBackDays: 60, minTitles: 4 };
const DEF_TOP10: AdminConfig['home']['top10'] = { periodDays: 7, minProfiles: 3 };
const DEF_REQUESTS: AdminConfig['requests'] = {
  url: '', showDiscover: true, showUpcomingMovies: true, showRequestButtons: true, showRequestInSearch: true,
  upcomingSource: 'requests', who: 'everyone', allow4k: false
};
const DEF_PLAYLOG: AdminConfig['playLog'] = { enabled: true, retentionDays: 365, useTop10: true, useHistory: true, useRowOrder: true, useGenres: true };

export function normaliseConfig(c: AdminConfig): AdminConfig {
  const h: Partial<AdminConfig['home']> = c.home ?? {};
  return {
    ...c,
    opensAt: c.opensAt ?? 'alongside',
    branding: { ...DEF_BRANDING, ...c.branding },
    playbackDefaults: { ...DEFAULT_PLAYBACK, ...c.playbackDefaults },
    home: {
      rows: normaliseRows(h.rows),
      adaptOrder: h.adaptOrder ?? true,
      because: { ...DEF_BECAUSE, ...h.because },
      top10: { ...DEF_TOP10, ...h.top10 },
      genres: { windowDays: h.genres?.windowDays ?? 90, starting: h.genres?.starting ?? [] },
      featured: { mode: h.featured?.mode ?? 'both', picks: h.featured?.picks ?? [] }
    },
    requests: { ...DEF_REQUESTS, ...c.requests },
    playLog: { ...DEF_PLAYLOG, ...c.playLog }
  };
}

/* ── Loading ── */

let loading: Promise<void> | null = null;

export function loadConfig(force = false): Promise<void> {
  if (loading) return loading;
  if (configStore.get() && !force) return Promise.resolve();
  loadErrorStore.set(null);
  loading = seedy.adminGetConfig()
    .then((c) => { configStore.set(normaliseConfig(c)); }, (e: Error) => { loadErrorStore.set(e); })
    .finally(() => { loading = null; });
  return loading;
}

export function useAdminConfig() {
  const config = useStore(configStore);
  const error = useStore(loadErrorStore);
  const save = useStore(saveStateStore);
  useEffect(() => { void loadConfig(); }, []);
  return { config, error, save, reload: () => { void loadConfig(true); } };
}

/* ── Saving ── */

let version = 0;
let timer = 0;
let saving: Promise<void> | null = null;

async function runSave(): Promise<void> {
  for (;;) {
    const v = version;
    const cfg = configStore.get();
    if (!cfg) return;
    try {
      const res = await seedy.adminSaveConfig(cfg);
      if (v !== version) {
        if (timer) return; /* a newer edit is waiting for its own debounce */
        continue;
      }
      const next = normaliseConfig(res && typeof res === 'object' ? res : cfg);
      configStore.set(next);
      saveStateStore.set('saved');
      applyBranding(next.branding);
      void refreshMe();
      return;
    } catch (e) {
      if (v !== version) {
        if (timer) return;
        continue;
      }
      saveStateStore.set('error');
      throw e;
    }
  }
}

export function flushConfig(): Promise<void> {
  window.clearTimeout(timer);
  timer = 0;
  if (!saving) saving = runSave().finally(() => { saving = null; });
  return saving;
}

/* Change the config now and save it after a short pause (autosave pages). */
export function editConfig(fn: (c: AdminConfig) => AdminConfig, delay = 600) {
  const cur = configStore.get();
  if (!cur) return;
  configStore.set(fn(cur));
  version++;
  saveStateStore.set('saving');
  window.clearTimeout(timer);
  timer = window.setTimeout(() => { flushConfig().catch(() => { /* shown by SavedNote */ }); }, delay);
}

/* Replace one section and save it right away; on failure the section goes back to what was saved. */
export async function saveSection<K extends keyof AdminConfig>(key: K, value: AdminConfig[K]): Promise<void> {
  await flushConfig().catch(() => { /* earlier autosave error; this save retries everything */ });
  const before = configStore.get();
  if (!before) throw new Error(t('Settings are not loaded'));
  configStore.set({ ...before, [key]: value });
  version++;
  saveStateStore.set('saving');
  try {
    await flushConfig();
  } catch (e) {
    configStore.set((c) => (c ? { ...c, [key]: before[key] } : c));
    throw e;
  }
}

/* Take a server response (logo upload, API key) without losing edits that are still waiting to be saved. */
export function mergeServerConfig(res: AdminConfig, pick: (server: AdminConfig, local: AdminConfig) => AdminConfig) {
  const local = configStore.get();
  const server = normaliseConfig(res);
  configStore.set(local ? pick(server, local) : server);
}

if (typeof window !== 'undefined') {
  window.addEventListener('pagehide', () => { if (timer) flushConfig().catch(() => { /* leaving */ }); });
}

/* ── Status, Seerr and play log data (cached for the session) ── */

export const statusStore = createStore<{ data?: AdminStatus; error?: Error; at: number }>({ at: 0 });

export interface SeerrTestState { state: 'idle' | 'testing' | 'done'; result?: SeerrTestResult; error?: string; at: number }
export const seerrTestStore = createStore<SeerrTestState>({ state: 'idle', at: 0 });

// A slow test started earlier must not overwrite the result of a newer one.
let testSeq = 0;

export function testSeerr(body: { url?: string; apiKey?: string } = {}) {
  const seq = ++testSeq;
  seerrTestStore.set((p) => ({ ...p, state: 'testing' }));
  return seedy.adminSeerrTest(body).then(
    (result) => {
      if (seq !== testSeq) return;
      seerrTestStore.set({ state: 'done', result, at: Date.now() });
      // Connecting or fixing Seerr changes what the app itself shows (Discover, Request buttons).
      refreshMe();
      statusStore.set((p) => (p.data
        ? { ...p, data: { ...p.data, seerr: { state: result.ok ? 'connected' : 'error', message: result.message, checkedAt: new Date().toISOString() } } }
        : p));
    },
    (e: Error) => { if (seq === testSeq) seerrTestStore.set({ state: 'done', error: e.message || t('Seerr did not answer.'), at: Date.now() }); }
  );
}

export const accountsStore = createStore<{ data?: SeerrAccount[]; error?: Error; loading: boolean }>({ loading: false });

export function loadAccounts(force = false) {
  const s = accountsStore.get();
  if ((s.data && !force) || s.loading) return;
  accountsStore.set((p) => ({ ...p, loading: true }));
  seedy.adminSeerrAccounts().then(
    (data) => accountsStore.set({ data, loading: false }),
    (error: Error) => accountsStore.set((p) => ({ ...p, error, loading: false }))
  );
}

export const profilesStore = createStore<{ data?: PlayLogProfile[]; error?: Error; loading: boolean }>({ loading: false });

export function loadProfiles(force = false) {
  const s = profilesStore.get();
  if ((s.data && !force) || s.loading) return;
  profilesStore.set((p) => ({ ...p, loading: true }));
  seedy.adminPlayLogProfiles().then(
    (data) => profilesStore.set({ data, loading: false }),
    (error: Error) => profilesStore.set((p) => ({ ...p, error, loading: false }))
  );
}

/* ── Leaving a page with unsaved changes ── */

let leaveGuard: (() => boolean) | null = null;

let removeNavGuard: (() => void) | null = null;

/* Also holds the account menu, header links and the browser's Back button, not just links on the page. */
export function setLeaveGuard(fn: (() => boolean) | null) {
  leaveGuard = fn;
  removeNavGuard?.();
  removeNavGuard = null;
  if (!fn) return;
  removeNavGuard = addNavigationGuard((to) => {
    if (!hasUnsavedChanges()) return true;
    canLeave().then((ok) => { if (ok) navigate(to, { force: true }); });
    return false;
  });
}

export const hasUnsavedChanges = () => !!leaveGuard?.();

export async function canLeave(): Promise<boolean> {
  if (!hasUnsavedChanges()) return true;
  return confirm({
    title: t('Leave without saving?'),
    text: t('Your changes to the home screen haven’t been saved yet. If you leave now, they’re lost.'),
    action: t('Leave without saving'),
    danger: true
  });
}

