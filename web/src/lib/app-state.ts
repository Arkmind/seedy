import {
  ApiError, allowAdoption, blockAdoption, candidateSessions, getSession, sessionFromAuth, setSession, setUnauthorizedHandler, storedSession, type Session
} from '../api/client';
import { castTarget } from './cast';
import * as jf from '../api/jellyfin';
import * as seedy from '../api/seedy';
import type { AuthResult, BaseItem, User } from '../api/types';
import { createStore } from './store';

export const DEFAULT_PLAYBACK: seedy.PlaybackPrefs = {
  skipIntro: 'button',
  skipRecap: 'button',
  skipCredits: 'button',
  autoPlayNext: true,
  upNextSeconds: 15,
  moviePicksSeconds: 15,
  seekBackSeconds: 10,
  seekForwardSeconds: 30,
  pauseScreenSeconds: 10,
  maxQuality: 'auto'
};

export const DEFAULT_BRANDING: seedy.Branding = { name: 'Seedy', accent: '#ff9fff', hasLogo: false, logoVersion: '0', signInBackground: 'backdrops' };

const fallbackMe = (): seedy.Me => ({
  isAdmin: false,
  branding: DEFAULT_BRANDING,
  prefs: {
    playback: DEFAULT_PLAYBACK, subtitles: { size: 'M', lift: true }, adaptRowOrder: true, hiddenRows: [], hiddenSeeds: [],
    rowOrder: 'auto', rows: [], privacy: { personalRecs: true, countInTop10: true }, notifyRequests: true, custom: {}
  },
  defaults: DEFAULT_PLAYBACK,
  requests: { connected: false, discover: false, upcomingMovies: false, requestButtons: false, requestInSearch: false, canRequest: false, allow4k: false },
  historyEnabled: true,
  pluginVersion: '0.1.0'
});

export const userStore = createStore<User | null>(null);
export const meStore = createStore<seedy.Me>(fallbackMe());
export const viewsStore = createStore<BaseItem[]>([]);
export const brandingStore = createStore<seedy.Branding>(DEFAULT_BRANDING);
/* 'offline': the server didn't answer (restarting, network down) — keep the session and offer a retry. */
export const bootState = createStore<'booting' | 'signedOut' | 'ready' | 'offline'>('booting');

/* A personal accent (Settings · Display) goes over the server's; Seedy's personal settings install it at start-up. */
let accentFilter: (serverAccent: string) => string = (a) => a;
export function setAccentFilter(fn: (serverAccent: string) => string) {
  accentFilter = fn;
  applyBranding(brandingStore.get());
}

export function applyBranding(b: seedy.Branding) {
  brandingStore.set(b);
  const accent = /^#[0-9a-f]{6}$/i.test(b.accent) ? b.accent : DEFAULT_BRANDING.accent;
  document.documentElement.style.setProperty('--seedy-accent', accentFilter(accent));
  document.title = b.name || 'Seedy';
  setFavicon(seedy.faviconUrl(b));
}

/* The tab icon follows the branding: a new logo (or name, without one) shows without a reload. */
function setFavicon(href: string) {
  let link = document.querySelector<HTMLLinkElement>('link[rel~="icon"]');
  if (!link) {
    link = document.createElement('link');
    link.rel = 'icon';
    document.head.appendChild(link);
  }
  if (link.href !== new URL(href, window.location.href).href) link.href = href;
}

export async function loadBranding() {
  try {
    applyBranding(await seedy.getClientBranding());
  } catch {
    applyBranding(DEFAULT_BRANDING);
  }
}

async function loadSignedIn(user: User) {
  userStore.set(user);
  const [me, views] = await Promise.all([
    // Without the plugin's profile, admins must still reach the admin (it's where Seerr gets fixed).
    seedy.getMe().catch(() => ({ ...fallbackMe(), isAdmin: !!user.Policy?.IsAdministrator })),
    jf.getUserViews().then((r) => r.Items).catch(() => [] as BaseItem[])
  ]);
  meStore.set(me);
  applyBranding(me.branding);
  viewsStore.set(views);
  bootState.set('ready');
}

/* Set by the personal settings' save queue: while it still has prefs on the way, a profile refresh keeps the prefs
   already on screen instead of briefly showing the older ones the server returned. */
let prefsBusy = () => false;
export function setPrefsBusyCheck(fn: () => boolean) { prefsBusy = fn; }

export async function refreshMe() {
  try {
    const me = await seedy.getMe();
    meStore.set(prefsBusy() ? { ...me, prefs: meStore.get().prefs } : me);
    applyBranding(me.branding);
  } catch { /* keep the last known profile */ }
}

export async function boot() {
  setUnauthorizedHandler((token) => {
    if (bootState.get() !== 'ready') return;
    // Another tab may have signed in as someone else since; follow it rather than wiping its session.
    const stored = storedSession();
    if (stored && stored.token !== token) {
      window.location.reload();
      return;
    }
    signOutLocal();
  });
  bootState.set('booting');
  await loadBranding();
  for (const s of candidateSessions()) {
    try {
      const user = await jf.getMe(s.token);
      const session: Session = { ...s, userId: user.Id, userName: user.Name };
      setSession(session);
      await loadSignedIn(user);
      return;
    } catch (e) {
      // Only a rejected token means signed out; anything else is the server being away for a moment.
      if (!(e instanceof ApiError) || (e.status !== 401 && e.status !== 403)) {
        bootState.set('offline');
        return;
      }
    }
  }
  setSession(null);
  bootState.set('signedOut');
}

export async function completeSignIn(auth: AuthResult) {
  allowAdoption();
  setSession(sessionFromAuth(auth));
  await loadSignedIn(auth.User);
}

function signOutLocal() {
  setSession(null);
  userStore.set(null);
  meStore.set(fallbackMe());
  viewsStore.set([]);
  castTarget.set(null);
  bootState.set('signedOut');
}

export async function signOut() {
  try { await jf.logout(); } catch { /* token may already be gone */ }
  blockAdoption();
  signOutLocal();
}

export const isAdmin = () => !!userStore.get()?.Policy?.IsAdministrator;
export const currentUserId = () => getSession()?.userId ?? '';
