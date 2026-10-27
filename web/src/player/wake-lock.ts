import { useEffect } from 'preact/hooks';

/* Keeps the screen, and so the computer, awake while `on`. Browsers hold a <video>'s own lock only in some cases and
   never for the experimental player's canvas. They drop the lock whenever the page hides: take it again on return.
   Needs a secure context (HTTPS or localhost); elsewhere it does nothing. */
export function useWakeLock(on: boolean) {
  useEffect(() => {
    if (!on || !('wakeLock' in navigator)) return;
    let lock: WakeLockSentinel | null = null;
    let asking = false;
    let done = false;
    const take = () => {
      if (done || asking || document.visibilityState !== 'visible' || (lock && !lock.released)) return;
      asking = true;
      navigator.wakeLock.request('screen').then((l) => {
        asking = false;
        if (done) void l.release();
        else lock = l;
      }, () => { asking = false; });
    };
    take();
    document.addEventListener('visibilitychange', take);
    return () => {
      done = true;
      document.removeEventListener('visibilitychange', take);
      void lock?.release();
    };
  }, [on]);
}
