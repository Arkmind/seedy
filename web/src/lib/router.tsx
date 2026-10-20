import type { ComponentChildren, JSX } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { APP_BASE } from '../api/client';

/* History routing under {base}/seedy. The plugin serves index.html for any path below it. */

export interface Location { path: string; query: URLSearchParams; key: string }

function current(): Location {
  let path = window.location.pathname;
  if (path.toLowerCase().startsWith(APP_BASE.toLowerCase())) path = path.slice(APP_BASE.length);
  if (!path.startsWith('/')) path = '/' + path;
  if (path.length > 1 && path.endsWith('/')) path = path.slice(0, -1);
  const key = (window.history.state as { key?: string } | null)?.key ?? 'root';
  return { path, query: new URLSearchParams(window.location.search), key };
}

let loc = current();
const subs = new Set<(l: Location) => void>();
const scrollPositions = new Map<string, number>();

function emit() {
  loc = current();
  subs.forEach((fn) => fn(loc));
}

if (!(window.history.state as { key?: string } | null)?.key) {
  window.history.replaceState({ key: 'root' }, '');
}
if ('scrollRestoration' in window.history) window.history.scrollRestoration = 'manual';

window.addEventListener('popstate', () => {
  // Back/forward can't be cancelled, so step back to where we were when a guard says stay.
  const attempted = current();
  if (guards.size && !allowed(attempted.path + (attempted.query.toString() ? '?' + attempted.query.toString() : ''))) {
    window.history.pushState({ key: loc.key }, '', window.location.href.replace(window.location.pathname + window.location.search, APP_BASE + loc.path + (loc.query.toString() ? '?' + loc.query.toString() : '')));
    return;
  }
  emit();
  const y = scrollPositions.get(loc.key) ?? 0;
  requestAnimationFrame(() => requestAnimationFrame(() => window.scrollTo(0, y)));
});

export function href(path: string): string {
  return APP_BASE + (path.startsWith('/') ? path : '/' + path);
}

/* A page with unsaved edits can hold navigation; return false to stay. */
type Guard = (to: string) => boolean;
const guards = new Set<Guard>();
export function addNavigationGuard(fn: Guard): () => void {
  guards.add(fn);
  return () => guards.delete(fn);
}
const allowed = (to: string) => [...guards].every((g) => g(to));

export function navigate(path: string, opts: { replace?: boolean; keepScroll?: boolean; force?: boolean } = {}) {
  if (!opts.force && !allowed(path)) return;
  scrollPositions.set(loc.key, window.scrollY);
  const key = Math.random().toString(36).slice(2, 10);
  const target = href(path);
  if (opts.replace) window.history.replaceState({ key: loc.key }, '', target);
  else window.history.pushState({ key }, '', target);
  emit();
  if (!opts.keepScroll) window.scrollTo(0, 0);
}

export function back(fallback = '/') {
  if (loc.key !== 'root' && window.history.length > 1) window.history.back();
  else navigate(fallback, { replace: true });
}

export function useLocation(): Location {
  const [l, setL] = useState(loc);
  useEffect(() => {
    setL(loc);
    subs.add(setL);
    return () => subs.delete(setL);
  }, []);
  return l;
}

export function getLocation(): Location {
  return loc;
}

/* Match "/details/:id" style patterns. */
export function match(pattern: string, path: string): Record<string, string> | null {
  const a = pattern.split('/').filter(Boolean);
  const b = path.split('/').filter(Boolean);
  if (a.length !== b.length) return null;
  const params: Record<string, string> = {};
  for (let i = 0; i < a.length; i++) {
    if (a[i].startsWith(':')) {
      try { params[a[i].slice(1)] = decodeURIComponent(b[i]); } catch { return null; }
    }
    else if (a[i].toLowerCase() !== b[i].toLowerCase()) return null;
  }
  return params;
}

type LinkProps = Omit<JSX.HTMLAttributes<HTMLAnchorElement>, 'href'> & { to: string; replace?: boolean; children?: ComponentChildren };

export function Link({ to, replace, onClick, children, ...rest }: LinkProps) {
  return (
    <a
      {...rest}
      href={href(to)}
      onClick={(e) => {
        (onClick as ((e: MouseEvent) => void) | undefined)?.(e as unknown as MouseEvent);
        if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
        e.preventDefault();
        navigate(to, { replace });
      }}
    >
      {children}
    </a>
  );
}

export function setQuery(params: Record<string, string | undefined | null>, replace = true) {
  const q = new URLSearchParams(window.location.search);
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null || v === '') q.delete(k);
    else q.set(k, v);
  }
  const s = q.toString();
  navigate(loc.path + (s ? '?' + s : ''), { replace, keepScroll: true });
}
