import type { JSX } from 'preact';

/* The music and audiobook boards' own icons, path for path (24 px grid, round caps and joins). */

const p = (d: string) => <path d={d} />;
const f = (d: string) => <path d={d} fill="currentColor" stroke="none" />;

const ICONS = {
  shuffle: <>{p('M16 3h5v5')}{p('M4 20L21 3')}{p('M21 16v5h-5')}{p('M15 15l6 6')}{p('M4 4l5 5')}</>,
  previous: <>{f('M18 5v14L8 12z')}{p('M6 5v14')}</>,
  next: <>{f('M6 5v14l10-7z')}{p('M18 5v14')}</>,
  play: f('M8 5v14l11-7z'),
  pause: <><rect x="6" y="4.5" width="4" height="15" rx="1.2" fill="currentColor" stroke="none" /><rect x="14" y="4.5" width="4" height="15" rx="1.2" fill="currentColor" stroke="none" /></>,
  repeat: <>{p('M17 2l3 3-3 3')}{p('M4 11V9a4 4 0 0 1 4-4h12')}{p('M7 22l-3-3 3-3')}{p('M20 13v2a4 4 0 0 1-4 4H4')}</>,
  lyrics: <>{p('M4 6h10')}{p('M4 11h7')}{p('M4 16h5')}<circle cx="17" cy="16" r="3" />{p('M20 16V6l-3 1')}</>,
  queue: <>{p('M4 6h12')}{p('M4 11h12')}{p('M4 16h7')}<path d="M16 15v6l5-3z" fill="currentColor" /></>,
  cast: <>{p('M3 8V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6')}{p('M3 12a8 8 0 0 1 8 8')}{p('M3 16a4 4 0 0 1 4 4')}{p('M3 20h.01')}</>,
  volume: <>{p('M4 9v6h4l5 4V5L8 9z')}{p('M16.5 8.5a5 5 0 0 1 0 7')}{p('M19 6a8.5 8.5 0 0 1 0 12')}</>,
  volumeLow: <>{p('M4 9v6h4l5 4V5L8 9z')}{p('M16.5 8.5a5 5 0 0 1 0 7')}</>,
  mute: <>{p('M4 9v6h4l5 4V5L8 9z')}{p('M16.5 9.5l5 5')}{p('M21.5 9.5l-5 5')}</>,
  expand: <>{p('M15 3h6v6')}{p('M9 21H3v-6')}{p('M21 3l-7 7')}{p('M3 21l7-7')}</>,
  mix: p('M3 12h3l3-7 4 14 3-7h5'),
  heart: p('M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z'),
  download: <>{p('M12 3v12')}{p('M7 10l5 5 5-5')}{p('M5 20h14')}</>,
  more: <><circle cx="5.5" cy="12" r="1.7" fill="currentColor" stroke="none" /><circle cx="12" cy="12" r="1.7" fill="currentColor" stroke="none" /><circle cx="18.5" cy="12" r="1.7" fill="currentColor" stroke="none" /></>,
  chevronDown: p('M6 9l6 6 6-6'),
  moon: p('M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5z'),
  book: <>{p('M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2z')}{p('M4 21a2 2 0 0 1 2-2h13v2')}</>,
  chapters: <>{p('M9 6h11')}{p('M9 12h11')}{p('M9 18h11')}{p('M4 6h.01')}{p('M4 12h.01')}{p('M4 18h.01')}</>,
  handle: <>{p('M5 9h14')}{p('M5 15h14')}</>,
  check: p('M5 12.5l4.5 4.5L19 7.5'),
  close: <>{p('M6 6l12 12')}{p('M18 6L6 18')}</>,
  plus: <>{p('M12 5v14')}{p('M5 12h14')}</>,
  folder: p('M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z'),
  music: <>{p('M9 18V5l11-2v13')}<circle cx="6.5" cy="18" r="2.5" /><circle cx="17.5" cy="16" r="2.5" /></>,
  playlistAdd: <>{p('M4 6h12')}{p('M4 11h12')}{p('M4 16h7')}{p('M18 14v7')}{p('M14.5 17.5h7')}</>,
  queueNext: <>{p('M4 6h12')}{p('M4 11h12')}{p('M4 16h7')}{p('M18 13v8')}{p('M14 17l4 4 4-4')}</>,
  album: <><circle cx="12" cy="12" r="9" /><circle cx="12" cy="12" r="2.5" /></>,
  artist: <><circle cx="12" cy="8" r="4" />{p('M4 21a8 8 0 0 1 16 0')}</>,
  info: <><circle cx="12" cy="12" r="9" />{p('M12 11v5')}{p('M12 8h.01')}</>,
  trash: <>{p('M4 7h16')}{p('M10 11v6')}{p('M14 11v6')}{p('M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12')}{p('M9 7V4h6v3')}</>,
  arrowUp: <>{p('M12 19V5')}{p('M6 11l6-6 6 6')}</>
} satisfies Record<string, JSX.Element>;

export type AIconName = keyof typeof ICONS;

export function AIcon({ name, size = 18, stroke = 2.2, class: cls, filled, style }: {
  name: AIconName; size?: number; stroke?: number; class?: string; filled?: boolean; style?: JSX.CSSProperties;
}) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" class={'ic' + (cls ? ' ' + cls : '')} style={style}
      fill={filled ? 'currentColor' : 'none'} stroke="currentColor" stroke-width={stroke} stroke-linecap="round" stroke-linejoin="round">
      {ICONS[name]}
    </svg>
  );
}

/* ±30 s: the seconds inside the arrow, as on the Audiobook board. */
export function Skip30({ dir, size = 28 }: { dir: 'back' | 'forward'; size?: number }) {
  const back = dir === 'back';
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" class="ic" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
      {back ? <path d="M4.5 12a7.5 7.5 0 1 0 2.3-5.4" /> : <path d="M19.5 12a7.5 7.5 0 1 1-2.3-5.4" />}
      {back ? <path d="M4.5 4v3.5H8" /> : <path d="M19.5 4v3.5H16" />}
      <text x="12" y="15.2" fill="currentColor" style="stroke: none; font-size: 7.5px; font-weight: 800; text-anchor: middle; font-family: var(--font)">30</text>
    </svg>
  );
}

/* The "Playing" bars in the accent; they move while the song plays. */
export function EqIcon({ playing, size = 16, label }: { playing?: boolean; size?: number; label?: string }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} role="img" aria-label={label} class={'ap-eq' + (playing ? ' is-playing' : '')}>
      <rect x="4" y="9" width="3" height="11" rx="1" />
      <rect x="10.5" y="4" width="3" height="16" rx="1" />
      <rect x="17" y="12" width="3" height="8" rx="1" />
    </svg>
  );
}
