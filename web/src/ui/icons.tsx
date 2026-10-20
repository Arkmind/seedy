import type { JSX } from 'preact';

/* Every outline icon below is copied path-for-path from the design boards (24 px grid, round caps).
   The few the boards don't draw (mute, exit full screen, sign out, …) follow the same construction. */

type IconDef = { w: number; body: JSX.Element; fill?: boolean };

const p = (d: string) => <path d={d} />;

const ICONS = {
  search: { w: 2, body: <><circle cx="11" cy="11" r="7" />{p('M20 20l-4-4')}</> },
  searchX: { w: 2, body: <><circle cx="11" cy="11" r="7" />{p('M20 20l-4-4')}{p('M8.5 8.5l5 5')}{p('M13.5 8.5l-5 5')}</> },
  cast: { w: 2, body: <>{p('M3 8V6a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-6')}{p('M3 12a8 8 0 0 1 8 8')}{p('M3 16a4 4 0 0 1 4 4')}{p('M3 20h.01')}</> },
  play: { w: 0, fill: true, body: p('M7 4.5v15l12-7.5z') },
  playRound: { w: 0, fill: true, body: p('M8 5v14l11-7z') },
  info: { w: 2, body: <><circle cx="12" cy="12" r="9" />{p('M12 11v5')}{p('M12 8h.01')}</> },
  heart: { w: 2, body: p('M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z') },
  check: { w: 2.2, body: p('M5 12.5l4.5 4.5L19 7.5') },
  chevronLeft: { w: 2, body: p('M15 5l-7 7 7 7') },
  chevronRight: { w: 2, body: p('M9 5l7 7-7 7') },
  chevronDown: { w: 2.2, body: p('M6 9l6 6 6-6') },
  more: { w: 0, fill: true, body: <><circle cx="5" cy="12" r="1.8" /><circle cx="12" cy="12" r="1.8" /><circle cx="19" cy="12" r="1.8" /></> },
  trailer: { w: 2, body: <><rect x="3" y="5" width="18" height="14" rx="2" />{p('M10 9.5v5l4-2.5z')}</> },
  shuffle: { w: 2, body: <>{p('M16 3h5v5')}{p('M4 20L21 3')}{p('M21 16v5h-5')}{p('M15 15l6 6')}{p('M4 4l5 5')}</> },
  sortEpisodes: { w: 2, body: <>{p('M7 4v16')}{p('M3 16l4 4 4-4')}{p('M17 20V4')}{p('M13 8l4-4 4 4')}</> },
  sort: { w: 2, body: <>{p('M7 4v16')}{p('M3 16l4 4 4-4')}{p('M12 6h9')}{p('M12 11h6')}{p('M12 16h3')}</> },
  filters: { w: 2, body: <>{p('M4 6h16')}{p('M7 12h10')}{p('M10 18h4')}</> },
  grid: { w: 2, body: <><rect x="4" y="4" width="6.5" height="6.5" rx="1.5" /><rect x="13.5" y="4" width="6.5" height="6.5" rx="1.5" /><rect x="4" y="13.5" width="6.5" height="6.5" rx="1.5" /><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.5" /></> },
  list: { w: 2, body: <>{p('M9 6h11')}{p('M9 12h11')}{p('M9 18h11')}{p('M4 6h.01')}{p('M4 12h.01')}{p('M4 18h.01')}</> },
  close: { w: 2.6, body: <>{p('M6 6l12 12')}{p('M18 6L6 18')}</> },
  plus: { w: 2.6, body: <>{p('M12 5v14')}{p('M5 12h14')}</> },
  minus: { w: 2.6, body: p('M6 12h12') },
  eyeOff: { w: 2, body: <>{p('M3 3l18 18')}{p('M10.6 5.1A10 10 0 0 1 12 5c6 0 9.5 7 9.5 7a17 17 0 0 1-3 3.9')}{p('M6.6 6.6C3.9 8.4 2.5 12 2.5 12s3.5 7 9.5 7a9.6 9.6 0 0 0 4.4-1')}{p('M9.9 9.9a3 3 0 0 0 4.2 4.2')}</> },
  eye: { w: 2, body: <>{p('M2.5 12S6 5 12 5s9.5 7 9.5 7-3.5 7-9.5 7-9.5-7-9.5-7z')}<circle cx="12" cy="12" r="3" /></> },
  arrowLeft: { w: 2.2, body: <>{p('M19 12H5')}{p('M11 6l-6 6 6 6')}</> },
  watchTogether: { w: 2, body: <><circle cx="9" cy="8" r="3.2" />{p('M3 20a6 6 0 0 1 12 0')}{p('M16 5a3 3 0 0 1 0 6')}{p('M21 20a6 6 0 0 0-3.5-5.4')}</> },
  skip: { w: 2.4, body: <>{p('M5 6l6 6-6 6')}{p('M12 6l6 6-6 6')}</> },
  pause: { w: 0, fill: true, body: <><rect x="6" y="4.5" width="4" height="15" rx="1.2" /><rect x="14" y="4.5" width="4" height="15" rx="1.2" /></> },
  nextEpisode: { w: 2.2, body: <><path d="M6 5v14l10-7z" fill="currentColor" />{p('M18.5 5v14')}</> },
  previous: { w: 2.2, body: <><path d="M18 5v14L8 12z" fill="currentColor" />{p('M5.5 5v14')}</> },
  volume: { w: 2, body: <>{p('M4 9v6h4l5 4V5L8 9z')}{p('M16.5 8.5a5 5 0 0 1 0 7')}{p('M19 6a8.5 8.5 0 0 1 0 12')}</> },
  volumeLow: { w: 2, body: <>{p('M4 9v6h4l5 4V5L8 9z')}{p('M16.5 8.5a5 5 0 0 1 0 7')}</> },
  mute: { w: 2, body: <>{p('M4 9v6h4l5 4V5L8 9z')}{p('M16.5 9.5l5 5')}{p('M21.5 9.5l-5 5')}</> },
  subtitles: { w: 2, body: <><rect x="3" y="5" width="18" height="14" rx="3" />{p('M7 11h3')}{p('M13 11h4')}{p('M7 15h6')}{p('M15.5 15H17')}</> },
  audio: { w: 2, body: <>{p('M4 10v4')}{p('M8 7v10')}{p('M12 4v16')}{p('M16 8v8')}{p('M20 11v2')}</> },
  chapters: { w: 2, body: <>{p('M9 6h11')}{p('M9 12h11')}{p('M9 18h11')}{p('M4 6h.01')}{p('M4 12h.01')}{p('M4 18h.01')}</> },
  episodes: { w: 2, body: <><rect x="3" y="8" width="18" height="12" rx="2" />{p('M6 5h12')}{p('M9 2h6')}</> },
  sliders: { w: 2, body: <>{p('M4 7h9')}{p('M17 7h3')}{p('M4 17h3')}{p('M11 17h9')}<circle cx="15" cy="7" r="2" /><circle cx="9" cy="17" r="2" /></> },
  pip: { w: 2, body: <><rect x="3" y="5" width="18" height="14" rx="2" /><rect x="12" y="11" width="6" height="5" rx="1" /></> },
  fullscreen: { w: 2, body: <>{p('M4 9V4h5')}{p('M20 9V4h-5')}{p('M4 15v5h5')}{p('M20 15v5h-5')}</> },
  exitFullscreen: { w: 2, body: <>{p('M9 4v5H4')}{p('M15 4v5h5')}{p('M9 20v-5H4')}{p('M15 20v-5h5')}</> },
  external: { w: 2, body: <>{p('M14 4h6v6')}{p('M20 4l-9 9')}{p('M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5')}</> },
  home: { w: 2, body: p('M4 11l8-7 8 7v9a1 1 0 0 1-1 1h-5v-6h-4v6H5a1 1 0 0 1-1-1z') },
  inbox: { w: 2, body: <>{p('M4 13h4l2 3h4l2-3h4')}{p('M5.5 5h13L20 13v6H4v-6z')}</> },
  clock: { w: 2, body: <><circle cx="12" cy="12" r="9" />{p('M12 7v5l3 2')}</> },
  lock: { w: 2, body: <><rect x="5" y="11" width="14" height="9" rx="2" />{p('M8 11V8a4 4 0 0 1 8 0v3')}</> },
  drag: { w: 0, fill: true, body: <><circle cx="9" cy="6" r="1.6" /><circle cx="15" cy="6" r="1.6" /><circle cx="9" cy="12" r="1.6" /><circle cx="15" cy="12" r="1.6" /><circle cx="9" cy="18" r="1.6" /><circle cx="15" cy="18" r="1.6" /></> },
  download: { w: 2.2, body: <>{p('M12 4v11')}{p('M7 10l5 5 5-5')}{p('M5 20h14')}</> },
  upload: { w: 2.2, body: <>{p('M12 20V9')}{p('M7 14l5-5 5 5')}{p('M5 4h14')}</> },
  star: { w: 0, fill: true, body: p('M12 3.5l2.6 5.3 5.8.8-4.2 4.1 1 5.8L12 16.8l-5.2 2.7 1-5.8-4.2-4.1 5.8-.8z') },
  signOut: { w: 2, body: <>{p('M14 4h4a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-4')}{p('M10 8l-4 4 4 4')}{p('M6 12h10')}</> },
  users: { w: 2, body: <><circle cx="9" cy="8" r="3.2" />{p('M3 20a6 6 0 0 1 12 0')}{p('M16 5a3 3 0 0 1 0 6')}{p('M21 20a6 6 0 0 0-3.5-5.4')}</> },
  user: { w: 2, body: <><circle cx="12" cy="8" r="3.6" />{p('M5 20a7 7 0 0 1 14 0')}</> },
  shield: { w: 2, body: p('M12 3l7 3v6c0 4.4-3 7.6-7 9-4-1.4-7-4.6-7-9V6z') },
  history: { w: 2, body: <>{p('M4.5 12a7.5 7.5 0 1 0 2.3-5.4')}{p('M4.5 4v3.5H8')}{p('M12 8v4.5l3 1.8')}</> },
  music: { w: 2, body: <>{p('M9 18V5l11-2v13')}<circle cx="6.5" cy="18" r="2.5" /><circle cx="17.5" cy="16" r="2.5" /></> },
  film: { w: 2, body: <><rect x="3" y="4" width="18" height="16" rx="2" />{p('M7 4v16')}{p('M17 4v16')}{p('M3 9h4')}{p('M3 15h4')}{p('M17 9h4')}{p('M17 15h4')}</> },
  tv: { w: 2, body: <><rect x="3" y="6" width="18" height="12" rx="2" />{p('M8 21h8')}{p('M9 3l3 3 3-3')}</> },
  folder: { w: 2, body: p('M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z') },
  refresh: { w: 2, body: <>{p('M19.5 12a7.5 7.5 0 1 1-2.3-5.4')}{p('M19.5 4v3.5H16')}</> },
  trash: { w: 2, body: <>{p('M4 7h16')}{p('M10 11v6')}{p('M14 11v6')}{p('M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12')}{p('M9 7V4h6v3')}</> },
  link: { w: 2, body: <>{p('M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1')}{p('M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1')}</> },
  key: { w: 2, body: <><circle cx="8" cy="15" r="4" />{p('M11 12l9-9')}{p('M17 6l3 3')}</> },
  alert: { w: 2, body: <><circle cx="12" cy="12" r="9" />{p('M12 7.5v5.5')}{p('M12 16.5h.01')}</> },
  speed: { w: 2, body: <>{p('M4.5 17a8.5 8.5 0 1 1 15 0')}{p('M12 13l4-4')}</> },
  quality: { w: 2, body: <><rect x="3" y="5" width="18" height="14" rx="3" />{p('M7 10v4')}{p('M7 12h3')}{p('M10 10v4')}{p('M13.5 10h2a2 2 0 0 1 0 4h-2z')}</> },
  grip: { w: 2, body: <>{p('M8 9h8')}{p('M8 15h8')}</> }
} satisfies Record<string, IconDef>;

export type IconName = keyof typeof ICONS;

type IconProps = {
  name: IconName;
  size?: number;
  stroke?: number;
  class?: string;
  style?: JSX.CSSProperties | string;
  filled?: boolean;
  color?: string;
};

export function Icon({ name, size = 20, stroke, class: cls, style, filled, color }: IconProps) {
  const def = ICONS[name] as IconDef;
  const isFill = def.fill === true;
  const sw = stroke ?? def.w;
  return (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      aria-hidden="true"
      class={(isFill ? 'ic-fill' : 'ic') + (cls ? ' ' + cls : '')}
      style={style}
      stroke-width={isFill ? undefined : sw}
      fill={isFill ? color ?? 'currentColor' : filled ? 'currentColor' : 'none'}
      stroke={isFill ? undefined : color ?? 'currentColor'}
    >
      {def.body}
    </svg>
  );
}

/* Seek glyphs carry their seconds inside the arrow, as on the Player board. */
export function SeekIcon({ dir, seconds, size = 26 }: { dir: 'back' | 'forward'; seconds: number; size?: number }) {
  const back = dir === 'back';
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" class="ic" fill="none" stroke="currentColor" stroke-width="1.8">
      {back ? <path d="M4.5 12a7.5 7.5 0 1 0 2.3-5.4" /> : <path d="M19.5 12a7.5 7.5 0 1 1-2.3-5.4" />}
      {back ? <path d="M4.5 4v3.5H8" /> : <path d="M19.5 4v3.5H16" />}
      <text
        x={back ? 12.4 : 11.6}
        y="15.2"
        fill="currentColor"
        style="stroke: none; font-size: 7.5px; font-weight: 800; text-anchor: middle; font-family: var(--font)"
      >
        {seconds}
      </text>
    </svg>
  );
}

/* Countdown ring used by Up next and the end-of-movie pick (r = 11, circumference 69.12). */
export function CountdownRing({ fraction, size = 22 }: { fraction: number; size?: number }) {
  const c = 69.12;
  const f = Math.max(0, Math.min(1, fraction));
  return (
    <svg viewBox="0 0 26 26" width={size} height={size} style="transform: rotate(-90deg)" aria-hidden="true">
      <circle cx="13" cy="13" r="11" style="fill: none; stroke: rgba(255, 255, 255, 0.18); stroke-width: 3" />
      <circle
        cx="13"
        cy="13"
        r="11"
        style={`fill: none; stroke: #ffffff; stroke-width: 3; stroke-linecap: round; stroke-dasharray: ${c}; stroke-dashoffset: ${(c * (1 - f)).toFixed(2)}; transition: stroke-dashoffset 1s linear`}
      />
    </svg>
  );
}
