import { useEffect, useRef } from 'preact/hooks';
import { saveReading, saveReadingOnExit, type ReadingPatch, type ReadingSettings } from '../api';

/* Keeps this profile's place in a book on the server: a save a moment after the page stops moving, settings with
   it when they changed, and a last save when the reader closes or the tab goes away. */
export function useReadingSaver(id: string) {
  const pending = useRef<ReadingPatch | null>(null);
  const settings = useRef<ReadingSettings | null>(null);
  const timer = useRef(0);
  const last = useRef('');

  const body = (): ReadingPatch | null => {
    if (!pending.current) return null;
    return settings.current ? { ...pending.current, Settings: settings.current } : pending.current;
  };
  const flush = (exit = false) => {
    window.clearTimeout(timer.current);
    const b = body();
    if (!b) return;
    const key = JSON.stringify(b);
    if (key === last.current) return;
    last.current = key;
    settings.current = null;
    if (exit) saveReadingOnExit(id, b);
    else saveReading(id, b).catch(() => { last.current = ''; });
  };

  useEffect(() => {
    const hide = () => { if (document.visibilityState === 'hidden') flush(true); };
    const leave = () => flush(true);
    document.addEventListener('visibilitychange', hide);
    window.addEventListener('pagehide', leave);
    return () => {
      document.removeEventListener('visibilitychange', hide);
      window.removeEventListener('pagehide', leave);
      flush(true);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  return {
    /* The place moved. */
    place(p: ReadingPatch, delay = 1500) {
      pending.current = { ...p, Settings: undefined };
      window.clearTimeout(timer.current);
      timer.current = window.setTimeout(() => flush(), delay);
    },
    /* The settings changed (saved with the next place). */
    settings(s: ReadingSettings) {
      settings.current = s;
      window.clearTimeout(timer.current);
      timer.current = window.setTimeout(() => flush(), 800);
    },
    flush: () => flush()
  };
}
