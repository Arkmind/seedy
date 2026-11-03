import type { JSX } from 'preact';

/* The Photos boards' icons, path for path (24 px grid, round caps and joins). */

const p = (d: string) => <path d={d} />;
const f = (d: string) => <path d={d} fill="currentColor" stroke="none" />;

const ICONS = {
  slideshow: <><rect x="3" y="5" width="18" height="14" rx="2" /><path d="M10 9.5v5l4-2.5z" fill="currentColor" /></>,
  pause: <><rect x="6" y="4.5" width="4" height="15" rx="1.2" fill="currentColor" stroke="none" /><rect x="14" y="4.5" width="4" height="15" rx="1.2" fill="currentColor" stroke="none" /></>,
  check: p('M5 12.5l4.5 4.5L19 7.5'),
  more: <><circle cx="5.5" cy="12" r="1.7" fill="currentColor" stroke="none" /><circle cx="12" cy="12" r="1.7" fill="currentColor" stroke="none" /><circle cx="18.5" cy="12" r="1.7" fill="currentColor" stroke="none" /></>,
  heart: p('M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z'),
  folder: p('M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z'),
  shuffle: <>{p('M16 3h5v5')}{p('M4 20L21 3')}{p('M21 16v5h-5')}{p('M15 15l6 6')}{p('M4 4l5 5')}</>,
  download: <>{p('M12 3v12')}{p('M7 10l5 5 5-5')}{p('M5 20h14')}</>,
  chevronDown: p('M6 9l6 6 6-6'),
  chevronRight: p('M9 6l6 6-6 6'),
  play: f('M8 5v14l11-7z'),
  close: <>{p('M6 6l12 12')}{p('M18 6L6 18')}</>,
  rotate: <>{p('M20 11a8 8 0 1 0-2.3 5.7')}{p('M20 4v7h-7')}</>,
  info: <><circle cx="12" cy="12" r="8.5" />{p('M12 11v5')}{p('M12 8h.01')}</>,
  prev: p('M15 6l-6 6 6 6'),
  next: p('M9 6l6 6-6 6'),
  calendar: <>{p('M5 5h14a1 1 0 0 1 1 1v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1z')}{p('M4 10h16M9 3v4M15 3v4')}</>,
  camera: <>{p('M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z')}{p('M12 10a3.5 3.5 0 1 1 0 7a3.5 3.5 0 1 1 0-7z')}</>,
  file: <>{p('M6 3h8l4 4v14H6z')}{p('M14 3v4h4')}</>,
  video: <><rect x="3" y="6" width="13" height="12" rx="2" />{p('M16 10.5l5-3v9l-5-3z')}</>,
  zoomIn: <><circle cx="11" cy="11" r="7" />{p('M20 20l-4-4')}{p('M11 8v6M8 11h6')}</>,
  zoomOut: <><circle cx="11" cy="11" r="7" />{p('M20 20l-4-4')}{p('M8 11h6')}</>,
  external: <>{p('M14 4h6v6')}{p('M20 4l-9 9')}{p('M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5')}</>,
  pin: <>{p('M12 21s-6.5-5.6-6.5-11a6.5 6.5 0 0 1 13 0c0 5.4-6.5 11-6.5 11z')}<circle cx="12" cy="10" r="2.4" /></>
};

export type PIconName = keyof typeof ICONS;

export function PIcon({ name, size = 18, stroke = 2.2, filled, class: cls }: { name: PIconName; size?: number; stroke?: number; filled?: boolean; class?: string }) {
  const style: JSX.CSSProperties = { fill: filled ? 'currentColor' : 'none', stroke: 'currentColor', strokeWidth: stroke, strokeLinecap: 'round', strokeLinejoin: 'round', flex: 'none' };
  return <svg viewBox="0 0 24 24" width={size} height={size} style={style} class={cls} aria-hidden="true">{ICONS[name]}</svg>;
}
