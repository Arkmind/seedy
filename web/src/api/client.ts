import type { AuthResult, User } from './types';

export const APP_VERSION = '0.1.0';
export const CLIENT_NAME = 'Seedy';

/* Where Seedy lives. The plugin tells the page in two meta tags: the server's base url ("" or e.g. "/jellyfin"), and
   where Seedy's pages are: the server address itself ("" — Seedy replaces Jellyfin's app) or "{base}/seedy" (alongside
   it). Without them (the Vite dev server) Seedy is at /seedy/ and everything before "/seedy" is the base url. */
function detectBase(): { server: string; app: string } {
  const meta = (name: string) => document.querySelector<HTMLMetaElement>(`meta[name="${name}"]`)?.content;
  const server = meta('seedy-server');
  const app = meta('seedy-app');
  if (server !== undefined && app !== undefined) return { server: window.location.origin + server, app };
  const path = window.location.pathname;
  const i = path.toLowerCase().indexOf('/seedy');
  const prefix = i >= 0 ? path.slice(0, i) : '';
  return { server: window.location.origin + prefix, app: prefix + '/seedy' };
}

const base = detectBase();
export const SERVER_URL = base.server;
/** Path prefix of Seedy's pages: "" at the server address, "/seedy" alongside Jellyfin's app (plus any base url). */
export const APP_BASE = base.app;
/** Jellyfin's own web app: moved to /old when Seedy replaces it, otherwise still at /web. Ends with "/". */
export const JELLYFIN_WEB = SERVER_URL + (APP_BASE === new URL(SERVER_URL).pathname.replace(/\/$/, '') ? '/old/' : '/web/');

const SESSION_KEY = 'seedy.session';
const DEVICE_KEY = 'seedy.deviceId';
/* After an explicit sign-out, Jellyfin's web-app token on this origin must not sign the person straight back in. */
const NO_ADOPT_KEY = 'seedy.noAdopt';

export interface Session { userId: string; token: string; serverId: string; userName: string }

function readStore<T>(key: string): T | null {
  try {
    const raw = window.localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : null;
  } catch {
    return null;
  }
}

function writeStore(key: string, value: unknown) {
  try {
    if (value === null) window.localStorage.removeItem(key);
    else window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    /* Private windows can refuse storage; the session then lasts for this tab only. */
  }
}

let memoryDeviceId: string | null = null;
export function deviceId(): string {
  let id: string | null = null;
  try { id = window.localStorage.getItem(DEVICE_KEY); } catch { id = memoryDeviceId; }
  if (!id) {
    id = 'seedy-' + (crypto.randomUUID ? crypto.randomUUID() : Math.random().toString(36).slice(2) + Date.now().toString(36));
    memoryDeviceId = id;
    try { window.localStorage.setItem(DEVICE_KEY, id); } catch { /* see readStore */ }
  }
  return id;
}

export function deviceName(): string {
  const ua = navigator.userAgent;
  const browser = /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : 'Browser';
  const os = /Windows/.test(ua) ? 'Windows' : /Mac OS X/.test(ua) ? (/iPhone|iPad/.test(ua) ? 'iOS' : 'macOS') : /Android/.test(ua) ? 'Android' : /Linux/.test(ua) ? 'Linux' : '';
  return os ? `${browser} on ${os}` : browser;
}

let session: Session | null = readStore<Session>(SESSION_KEY);

/* When Jellyfin's own web app is signed in on this server, reuse its token instead of asking again. */
function adoptJellyfinWebSession(): Session | null {
  const creds = readStore<{ Servers?: { Id: string; AccessToken?: string; UserId?: string; ManualAddress?: string; LocalAddress?: string }[] }>('jellyfin_credentials');
  const servers = creds?.Servers ?? [];
  const match = servers.find((s) => s.AccessToken && s.UserId && [s.ManualAddress, s.LocalAddress].some((a) => a && a.replace(/\/$/, '') === SERVER_URL))
    ?? servers.find((s) => s.AccessToken && s.UserId);
  if (!match || !match.AccessToken || !match.UserId) return null;
  return { userId: match.UserId, token: match.AccessToken, serverId: match.Id, userName: '' };
}

export function getSession(): Session | null {
  return session;
}

export function candidateSessions(): Session[] {
  const list: Session[] = [];
  if (session) list.push(session);
  const adopted = adoptJellyfinWebSession();
  const blocked = readStore<string>(NO_ADOPT_KEY);
  if (adopted && adopted.token !== session?.token && adopted.token !== blocked) list.push(adopted);
  return list;
}

/* Signing out of Seedy also stops it reusing Jellyfin's web-app session; signing in again lifts that. */
export function blockAdoption() {
  const adopted = adoptJellyfinWebSession();
  if (adopted) writeStore(NO_ADOPT_KEY, adopted.token);
}

export function allowAdoption() {
  writeStore(NO_ADOPT_KEY, null);
}

/* The session another tab may have stored since this tab loaded. */
export function storedSession(): Session | null {
  return readStore<Session>(SESSION_KEY);
}

export function setSession(next: Session | null) {
  session = next;
  writeStore(SESSION_KEY, next);
}

export function sessionFromAuth(auth: AuthResult): Session {
  return { userId: auth.User.Id, token: auth.AccessToken, serverId: auth.ServerId, userName: auth.User.Name };
}

export function authHeader(token?: string | null): string {
  const parts = [
    `Client="${CLIENT_NAME}"`,
    `Device="${encodeURIComponent(deviceName())}"`,
    `DeviceId="${encodeURIComponent(deviceId())}"`,
    `Version="${APP_VERSION}"`
  ];
  const t = token === undefined ? session?.token : token;
  if (t) parts.push(`Token="${t}"`);
  return 'MediaBrowser ' + parts.join(', ');
}

export class ApiError extends Error {
  constructor(public status: number, message: string, public body?: unknown) {
    super(message);
  }
}

type Query = Record<string, string | number | boolean | undefined | null | string[]>;

export function qs(query?: Query): string {
  if (!query) return '';
  const params = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === '') continue;
    if (Array.isArray(v)) {
      if (v.length) params.set(k, v.join(','));
    } else params.set(k, String(v));
  }
  const s = params.toString();
  return s ? '?' + s : '';
}

export function url(path: string, query?: Query): string {
  return SERVER_URL + path + qs(query);
}

let onUnauthorized: ((token: string | null | undefined) => void) | null = null;
export function setUnauthorizedHandler(fn: (token: string | null | undefined) => void) {
  onUnauthorized = fn;
}

export async function request<T>(
  method: string,
  path: string,
  opts: {
    query?: Query; body?: unknown; token?: string | null; raw?: boolean; signal?: AbortSignal; form?: FormData;
    /* A body sent as it is, with its own content type (image uploads). */
    data?: { type: string; body: BodyInit };
  } = {}
): Promise<T> {
  const headers: Record<string, string> = { Authorization: authHeader(opts.token), Accept: 'application/json' };
  let body: BodyInit | undefined;
  if (opts.form) body = opts.form;
  else if (opts.data) {
    headers['Content-Type'] = opts.data.type;
    body = opts.data.body;
  } else if (opts.body !== undefined) {
    headers['Content-Type'] = 'application/json';
    body = JSON.stringify(opts.body);
  }
  const res = await fetch(url(path, opts.query), { method, headers, body, signal: opts.signal });
  if (res.status === 401 && opts.token === undefined) onUnauthorized?.(session?.token);
  if (!res.ok) {
    let detail: unknown;
    let message = res.statusText || 'Request failed';
    try {
      const text = await res.text();
      try { detail = JSON.parse(text); } catch { detail = text; }
      if (typeof detail === 'string' && detail.trim()) message = detail.trim().slice(0, 300);
      else if (detail && typeof detail === 'object' && 'message' in detail) message = String((detail as { message: unknown }).message);
      else if (detail && typeof detail === 'object' && 'title' in detail) message = String((detail as { title: unknown }).title);
    } catch { /* body unreadable */ }
    throw new ApiError(res.status, message, detail);
  }
  if (opts.raw) return res as unknown as T;
  if (res.status === 204) return undefined as T;
  const type = res.headers.get('content-type') ?? '';
  if (!type.includes('json')) return (await res.text()) as unknown as T;
  return (await res.json()) as T;
}

export const get = <T>(path: string, query?: Query, signal?: AbortSignal) => request<T>('GET', path, { query, signal });
export const post = <T>(path: string, body?: unknown, query?: Query) => request<T>('POST', path, { body, query });
export const del = <T>(path: string, query?: Query) => request<T>('DELETE', path, { query });

export type { Query, User };
