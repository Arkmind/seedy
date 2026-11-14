import { useId } from 'preact/hooks';
import type { ComponentChildren } from 'preact';
import './medu.css';

// Medu, Seedy's jellyfish (Brand · Mascot board). One drawing in a 200 × 264 box (viewBox 0 -24 200 264), six moods
// on top of an idle pose, and a small variant under 80 px that drops the face details and keeps three tentacles.
// Motion is CSS (medu.css) and stops under prefers-reduced-motion.

export type MeduMood = 'idle' | 'wave' | 'search' | 'sleepy' | 'yay' | 'think' | 'oops';

const INK = '#1e1e1e';
const ARM = '#efdff3';
const ACCENT = 'var(--seedy-accent)';
const BELL = 'M30 108 C30 52 66 22 100 22 C134 22 170 52 170 108 Q156 124 142 110 Q128 124 114 110 Q100 124 86 110 Q72 124 58 110 Q44 124 30 108 Z';
const FRILL = 'M30 108 Q44 124 58 110 Q72 124 86 110 Q100 124 114 110 Q128 124 142 110 Q156 124 170 108';
const SPARK = 'M0 -10 C1.5 -2 2 -1.5 10 0 C2 1.5 1.5 2 0 10 C-1.5 2 -2 1.5 -10 0 C-2 -1.5 -1.5 -2 0 -10 Z';
const TENTACLES = {
  d1: 'M48 112 C40 136 56 152 46 174 C38 192 50 206 44 220',
  d3: 'M70 117 C64 142 78 160 68 184 C62 200 72 214 66 228',
  d2: 'M130 117 C136 142 122 160 132 184 C138 200 128 214 134 228',
  d4: 'M152 112 C160 136 144 152 154 174 C162 192 150 206 156 220',
};
const BLUSH: Record<MeduMood, number> = { idle: 0.55, wave: 0.55, search: 0.4, sleepy: 0.5, yay: 0.7, think: 0.45, oops: 0.4 };

const fill = (color: string) => ({ fill: color });
const stroke = (color: string) => ({ stroke: color });

function Eyes({ dy = 0, look }: { dy?: number; look?: boolean }) {
  const eyes = [80, 120].map((x) => (
    <g key={x}>
      <ellipse cx={x} cy={83 + dy} rx="8.5" ry="10.5" fill={INK} />
      <circle cx={x + 2.8} cy={78 + dy * (5 / 3)} r="3.4" fill="#ffffff" />
      <circle cx={x - 2.4} cy={88 + dy} r="1.6" fill="#ffffff" />
    </g>
  ));
  return <g class="m-eyes">{look ? <g class="m-look">{eyes}</g> : eyes}</g>;
}

const line = (d: string, width = 3, extra?: Record<string, string>) =>
  <path d={d} fill="none" stroke={INK} stroke-width={width} stroke-linecap="round" {...extra} />;

function Face({ mood }: { mood: MeduMood }) {
  switch (mood) {
    case 'search':
      return <>
        <Eyes look />
        {line('M71 69 Q78 63 86 66')}
        {line('M114 64 Q121 61 129 66')}
        {line('M93 99 q3.5 -4 7 0 t7 0', 3, { 'stroke-linejoin': 'round' })}
      </>;
    case 'sleepy':
      return <>
        {line('M73 86 Q80 92 87 86', 3.2)}
        {line('M113 86 Q120 92 127 86', 3.2)}
        <ellipse cx="100" cy="99" rx="3.4" ry="2.8" fill={INK} />
      </>;
    case 'yay':
      return <>
        {line('M73 88 Q80 79 87 88', 3.6)}
        {line('M113 88 Q120 79 127 88', 3.6)}
        <path d="M89 94 Q100 112 111 94 Z" fill={INK} stroke={INK} stroke-width="2.4" stroke-linejoin="round" />
        <path d="M95 101 Q100 106 105 101 Q100 98 95 101 Z" style={fill(ACCENT)} />
      </>;
    case 'think':
      return <>
        <Eyes dy={-3} />
        {line('M94 98 L106 97')}
      </>;
    case 'oops':
      return <>
        <Eyes />
        {line('M70 72 L86 67')}
        {line('M130 72 L114 67')}
        {line('M92 101 Q100 94 108 101')}
      </>;
    default:
      return <>
        <Eyes />
        {line('M93 95 Q100 102 107 95', 3.2)}
      </>;
  }
}

/** The sparkles of the Yay mood, drawn outside the float so they twinkle in place. */
const Sparks = () => <>
  {([[28, 30, 1.1, ACCENT, 0], [176, 14, 1.4, '#ffffff', -0.5], [186, 96, 0.9, ACCENT, -1], [18, 110, 0.8, '#ffffff', -0.8]] as const)
    .map(([x, y, s, color, delay]) => (
      <g key={x} transform={`translate(${x} ${y}) scale(${s})`}>
        <path class="m-spark" d={SPARK} style={{ fill: color, animationDelay: `${delay}s` }} />
      </g>
    ))}
</>;

export interface MeduProps {
  mood?: MeduMood;
  /** Width in CSS pixels; the height follows (264 / 200). Under 80 the small variant draws. */
  size: number;
  /** Lost Medu holds a torch whose beam sweeps the page (404, finding servers). Without it, the fourth tentacle stays. */
  torch?: boolean;
  /** The soft accent halo behind the bell. */
  glow?: boolean;
  /** Bubbles drifting up around Medu (the idle hero). */
  bubbles?: boolean;
  /** Spoken name; Medu is decoration (aria-hidden) unless this is given. */
  label?: string;
  class?: string;
}

export function Medu({ mood = 'idle', size, torch = false, glow, bubbles = false, label, class: cls }: MeduProps) {
  const uid = useId().replace(/[^\w-]/g, '');
  const small = size < 80;
  const halo = glow ?? !small;
  const speed = mood === 'sleepy' ? ' m-slow' : mood === 'yay' ? ' m-fast' : '';
  const a11y = label ? { role: 'img' as const, 'aria-label': label } : { 'aria-hidden': true };
  const torchArm = mood === 'search' && torch && !small;
  return (
    <svg class={`medu${speed}${cls ? ` ${cls}` : ''}`} viewBox="0 -24 200 264" width={size} height={Math.round(size * 1.32)} {...a11y}>
      <defs>
        <linearGradient id={`mb-${uid}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stop-color="#fdf6fe" />
          <stop offset="1" stop-color="#ebd3f0" />
        </linearGradient>
        <radialGradient id={`mg-${uid}`}>
          <stop offset="0" style={{ stopColor: ACCENT, stopOpacity: 0.32 }} />
          <stop offset="1" style={{ stopColor: ACCENT, stopOpacity: 0 }} />
        </radialGradient>
        {torchArm && <>
          <linearGradient id={`bm-${uid}`} gradientUnits="userSpaceOnUse" x1="30" y1="0" x2="640" y2="0">
            <stop offset="0" stop-color="#fff4d6" stop-opacity="0.55" />
            <stop offset="0.35" stop-color="#fff4d6" stop-opacity="0.2" />
            <stop offset="1" stop-color="#fff4d6" stop-opacity="0" />
          </linearGradient>
          <filter id={`bf-${uid}`} x="-10%" y="-30%" width="120%" height="160%"><feGaussianBlur stdDeviation="5" /></filter>
        </>}
      </defs>
      {halo && <circle cx="100" cy="96" r="108" fill={`url(#mg-${uid})`} />}
      {bubbles && ([[26, 196, 5, -0.6], [176, 170, 7, -2.1], [160, 226, 4, -3.3], [40, 140, 3.5, -1.4]] as const).map(([cx, cy, r, delay]) => (
        <circle key={cx} class="m-bub" cx={cx} cy={cy} r={r} fill="none" stroke="#ffffff" stroke-opacity="0.6" stroke-width="2" style={{ animationDelay: `${delay}s` }} />
      ))}
      <g class="m-float">
        {small ? <>
          {[['d1', TENTACLES.d1], ['d2', 'M100 118 C94 144 108 162 98 190 C92 206 102 218 98 230'], ['d3', TENTACLES.d4]].map(([delay, d]) => (
            <g key={delay} class={`m-t m-${delay}`}><path d={d} fill="none" stroke={ARM} stroke-width="12" stroke-linecap="round" /></g>
          ))}
          <g>
            <path d={BELL} fill={`url(#mb-${uid})`} />
            <path d={FRILL} fill="none" stroke="#d6b3de" stroke-width="2.5" stroke-linejoin="round" opacity="0.85" />
            {[78, 122].map((x) => (
              <g key={x}>
                <ellipse cx={x} cy="84" rx="10" ry="12.5" fill={INK} />
                <circle cx={x + 3} cy="79" r="3.6" fill="#ffffff" />
              </g>
            ))}
          </g>
        </> : <>
          {(['d1', 'd3', 'd2', 'd4'] as const)
            .filter((k) => k !== 'd4' || !(torchArm || mood === 'oops'))
            .map((k) => (
              <g key={k} class={`m-t m-${k === 'd4' ? 'd4' : k}`}>
                <path d={TENTACLES[k]} fill="none" stroke={ARM} stroke-width="6" stroke-linecap="round" />
              </g>
            ))}
          {[['d5', 'M90 116 C82 132 98 144 88 160 C80 174 94 182 88 196'], ['d2', 'M110 116 C118 132 102 144 112 160 C120 174 106 182 112 196']].map(([delay, d]) => (
            <g key={d} class={`m-t m-${delay}`}>
              <path d={d} fill="none" style={stroke(ACCENT)} stroke-opacity="0.75" stroke-width="8" stroke-linecap="round" />
            </g>
          ))}
          {mood === 'wave' && (
            <g class="m-wave">
              <path d="M163 96 C180 92 188 76 180 60" fill="none" stroke={ARM} stroke-width="8" stroke-linecap="round" />
              <circle cx="180" cy="58" r="6.5" fill={ARM} />
            </g>
          )}
          {torchArm && <>
            <g transform="translate(186 128) rotate(16)">
              <g class="m-beam">
                <polygon points="30,-6 640,-160 640,160 30,6" fill={`url(#bm-${uid})`} filter={`url(#bf-${uid})`} />
              </g>
              <rect x="0" y="-6" width="22" height="12" rx="3" fill="#a1a1a1" />
              <rect x="20" y="-9" width="11" height="18" rx="3" fill="#d4d4d4" />
              <path d="M31 -7 V7" stroke="#fff4d6" stroke-width="3" stroke-linecap="round" />
            </g>
            <path d="M152 112 C156 126 166 133 192 131" fill="none" stroke={ARM} stroke-width="6.5" stroke-linecap="round" />
          </>}
          {mood === 'oops' && (
            <g class="m-plug">
              <path d="M152 112 C160 136 146 150 156 170 C162 182 168 190 170 200" fill="none" stroke={ARM} stroke-width="6" stroke-linecap="round" />
              <path d="M170 200 C172 212 160 218 166 230" fill="none" stroke="#8c8c8c" stroke-width="3.5" stroke-linecap="round" />
              <rect x="157" y="226" width="18" height="13" rx="3.5" fill="#a1a1a1" />
              <path d="M161 239v6M171 239v6" stroke="#a1a1a1" stroke-width="3" stroke-linecap="round" />
            </g>
          )}
          <g class="m-bell">
            <path d={BELL} fill={`url(#mb-${uid})`} />
            <path d={FRILL} fill="none" stroke="#d6b3de" stroke-width="2.5" stroke-linejoin="round" opacity="0.85" />
            {([[76, 44, 6.5], [100, 35, 4.2], [124, 45, 5.5]] as const).map(([cx, cy, r]) => (
              <circle key={cx} cx={cx} cy={cy} r={r} style={fill(ACCENT)} opacity="0.32" />
            ))}
            <ellipse cx="54" cy="70" rx="7" ry="15" transform="rotate(24 54 70)" fill="#ffffff" opacity="0.9" />
            <Face mood={mood} />
            {[64, 136].map((cx) => <ellipse key={cx} cx={cx} cy="97" rx="8.5" ry="4.6" style={fill(ACCENT)} opacity={BLUSH[mood]} />)}
          </g>
        </>}
      </g>
      {mood === 'search' && !small && <>
        <g class="m-q">
          <text x="164" y="22" text-anchor="middle" font-family="Noto Sans Variable, Noto Sans, sans-serif" font-size="46" font-weight="900" style={fill(ACCENT)}>?</text>
        </g>
        <path d="M150 62 C150 62 143 72 143 77 a7 7 0 0 0 14 0 C157 72 150 62 150 62 Z" fill="#9fd3ff" opacity="0.9" />
      </>}
      {mood === 'sleepy' && !small && ([[148, 40, 20, 0], [166, 18, 28, -1.2], [186, -6, 22, -2.4]] as const).map(([x, y, s, delay]) => (
        <g key={x} class="m-z" style={{ animationDelay: `${delay}s` }}>
          <text x={x} y={y} font-family="Noto Sans Variable, Noto Sans, sans-serif" font-size={s} font-weight="900" fill="#e0e0e0">z</text>
        </g>
      ))}
      {mood === 'yay' && <Sparks />}
      {mood === 'think' && !small && ([[82, '#e0e0e0', 0], [100, ACCENT, 0.18], [118, '#e0e0e0', 0.36]] as const).map(([cx, color, delay]) => (
        <circle key={cx} class="m-dot" cx={cx} cy="0" r="6" style={{ fill: color, animationDelay: `${delay}s` }} />
      ))}
    </svg>
  );
}

/** Medu above a title, a plain sentence and a way out: the empty pages (Empty pages and My requests boards). */
export function MeduState({ mood = 'sleepy', size = 150, title, text, actions, children, large, bare, as: H = 'h2', torch }: {
  mood?: MeduMood;
  size?: number;
  title: ComponentChildren;
  text?: ComponentChildren;
  actions?: ComponentChildren;
  /** Extra content under the actions (the scan progress of a new library). */
  children?: ComponentChildren;
  /** The bigger My requests variant (30 px title). */
  large?: boolean;
  /** Without the panel behind it, for places that already sit in one. */
  bare?: boolean;
  as?: 'h1' | 'h2' | 'h3';
  torch?: boolean;
}) {
  return (
    <div class={'medu-state' + (large ? ' is-large' : '') + (bare ? ' is-bare' : '')}>
      <div class="medu-state-body">
        <Medu mood={mood} size={size} torch={torch} />
        <H class="medu-state-title">{title}</H>
        {text && <p class="medu-state-text">{text}</p>}
        {actions && <div class="medu-state-actions">{actions}</div>}
        {children}
      </div>
    </div>
  );
}
