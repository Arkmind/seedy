import { useEffect, useState } from 'preact/hooks';

/* A tiny observable value. Screens subscribe with useStore; anything can set it. */
export interface Store<T> {
  get(): T;
  set(next: T | ((prev: T) => T)): void;
  subscribe(fn: (v: T) => void): () => void;
}

export function createStore<T>(initial: T): Store<T> {
  let value = initial;
  const subs = new Set<(v: T) => void>();
  return {
    get: () => value,
    set(next) {
      const v = typeof next === 'function' ? (next as (p: T) => T)(value) : next;
      if (Object.is(v, value)) return;
      value = v;
      subs.forEach((fn) => fn(value));
    },
    subscribe(fn) {
      subs.add(fn);
      return () => subs.delete(fn);
    }
  };
}

export function useStore<T>(store: Store<T>): T {
  const [v, setV] = useState(store.get());
  useEffect(() => {
    setV(store.get());
    return store.subscribe(setV);
  }, [store]);
  return v;
}

/* Fetch-on-mount with cancellation; re-runs when deps change. */
export function useAsync<T>(fn: (signal: AbortSignal) => Promise<T>, deps: unknown[]): { data?: T; error?: Error; loading: boolean; reload: () => void } {
  const [state, setState] = useState<{ data?: T; error?: Error; loading: boolean }>({ loading: true });
  const [tick, setTick] = useState(0);
  useEffect(() => {
    const ctrl = new AbortController();
    setState((s) => ({ data: s.data, loading: true }));
    fn(ctrl.signal).then(
      (data) => { if (!ctrl.signal.aborted) setState({ data, loading: false }); },
      (error: Error) => { if (!ctrl.signal.aborted && error?.name !== 'AbortError') setState({ error, loading: false }); }
    );
    return () => ctrl.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);
  return { ...state, reload: () => setTick((t) => t + 1) };
}

export function safeLocal<T>(key: string, fallback: T): T {
  try {
    const raw = window.localStorage.getItem(key);
    return raw === null ? fallback : (JSON.parse(raw) as T);
  } catch {
    return fallback;
  }
}

export function saveLocal(key: string, value: unknown) {
  try { window.localStorage.setItem(key, JSON.stringify(value)); } catch { /* storage unavailable */ }
}

export function removeLocal(key: string) {
  try { window.localStorage.removeItem(key); } catch { /* storage unavailable */ }
}

/* Summaries belong to the signed-in profile: forgetSummaries() drops them when it changes, so the next profile never
   sees the last one's counts or Waiting for your approval. */
const summaries = new Set<() => void>();
export const forgetSummaries = () => summaries.forEach((forget) => forget());

/* A summary the pieces of a page share (the request tabs' counts): one call when a page opens, at most every
   [throttleMs], and the last answer kept when a call fails. While [enabled] is false it reads as null. */
export function createSummary<T>(fetch: () => Promise<T>, throttleMs = 3000) {
  const store = createStore<T | null>(null);
  let at = 0;
  let profile = 0;
  summaries.add(() => {
    profile++;
    at = 0;
    store.set(null);
  });
  const refresh = (): Promise<void> => {
    at = Date.now();
    const asked = profile;
    return fetch().then((s) => { if (asked === profile) store.set(s); }, () => { /* the last answer stays */ });
  };
  function useSummary(enabled = true): T | null {
    const s = useStore(store);
    useEffect(() => { if (enabled && Date.now() - at > throttleMs) void refresh(); }, [enabled]);
    return enabled ? s : null;
  }
  return { refresh, use: useSummary };
}
