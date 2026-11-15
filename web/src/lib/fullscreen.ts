import { useEffect, useState } from 'preact/hooks';

/* Full screen follows the document; Esc leaves it on its own. Without the Fullscreen API (iPhone Safari) only a
   <video> can go full screen, so a player passes its video. */
export function toggleFullscreen(video?: HTMLVideoElement | null) {
  if (document.fullscreenElement) { void document.exitFullscreen().catch(() => undefined); return; }
  const root = document.documentElement;
  if (root.requestFullscreen) void root.requestFullscreen().catch(() => undefined);
  else (video as (HTMLVideoElement & { webkitEnterFullscreen?: () => void }) | null | undefined)?.webkitEnterFullscreen?.();
}

/* Whether the page is full screen now, and a toggle. */
export function useFullscreen(): [boolean, () => void] {
  const [on, setOn] = useState(!!document.fullscreenElement);
  useEffect(() => {
    const fn = () => setOn(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', fn);
    return () => document.removeEventListener('fullscreenchange', fn);
  }, []);
  return [on, () => toggleFullscreen()];
}
