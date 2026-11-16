import { useEffect, useId, useLayoutEffect, useRef, useState } from 'preact/hooks';
import type { ComponentChildren } from 'preact';
import { BRIDGES, type MeduFrame, type MeduTrack } from './medu-bridges';
import './medu.css';

// Medu, Seedy's jellyfish (Brand · Mascot board). One drawing in a 200 × 264 box (viewBox 0 -24 200 264), nine moods
// on top of an idle pose, and a small variant under 80 px that drops the face details and keeps three tentacles.
// Party (sunglasses and a party hat) is the Konami code's dancer (Medu Dance Revolution board).
// Motion is CSS (medu.css) and stops under prefers-reduced-motion.
//
// Medu never cuts from one mood to another (board "Moving between moods"): when `mood` changes, it acts its way
// across. The drawing is made of named parts (eyes, mouths, props…), each in its own <g data-p>, and a bridge moves
// them with the Web Animations API: the board's own choreography for the pairs it draws (medu-bridges.ts), a blink,
// a hop and props popping in and out for every other pair. One bridge at a time; a mood asked for meanwhile follows.

export type MeduMood = 'idle' | 'wave' | 'search' | 'sleepy' | 'yay' | 'think' | 'oops' | 'love' | 'popcorn' | 'sad' | 'party';

const INK = '#1e1e1e';
const ARM = '#efdff3';
const ACCENT = 'var(--seedy-accent)';
const BELL = 'M30 108 C30 52 66 22 100 22 C134 22 170 52 170 108 Q156 124 142 110 Q128 124 114 110 Q100 124 86 110 Q72 124 58 110 Q44 124 30 108 Z';
const FRILL = 'M30 108 Q44 124 58 110 Q72 124 86 110 Q100 124 114 110 Q128 124 142 110 Q156 124 170 108';
const SPARK = 'M0 -10 C1.5 -2 2 -1.5 10 0 C2 1.5 1.5 2 0 10 C-1.5 2 -2 1.5 -10 0 C-2 -1.5 -1.5 -2 0 -10 Z';
const HEART = 'M0 8 C-12 0 -8 -10 0 -4 C8 -10 12 0 0 8 Z';
const TENTACLES = {
  d1: 'M48 112 C40 136 56 152 46 174 C38 192 50 206 44 220',
  d3: 'M70 117 C64 142 78 160 68 184 C62 200 72 214 66 228',
  d2: 'M130 117 C136 142 122 160 132 184 C138 200 128 214 134 228',
  d4: 'M152 112 C160 136 144 152 154 174 C162 192 150 206 156 220',
};
const BLUSH: Record<MeduMood, number> = {
  idle: 0.55, wave: 0.55, search: 0.4, sleepy: 0.5, yay: 0.7, think: 0.45, oops: 0.4, love: 0.75, popcorn: 0.5, sad: 0.35, party: 0.75
};
/* m-slow / m-fast: Sleepy and Sad float at half speed, Yay and Party at double. */
const SPEED: Partial<Record<MeduMood, 'm-slow' | 'm-fast'>> = { sleepy: 'm-slow', sad: 'm-slow', yay: 'm-fast', party: 'm-fast' };
/* The idle loops' durations (ms) at each speed, to keep their phase when the speed changes. */
const LOOPS: Record<string, Record<string, number>> = {
  '': { 'medu-bob': 3200, 'medu-pulse': 3200, 'medu-wiggle': 2400 },
  'm-slow': { 'medu-bob': 5500, 'medu-pulse': 5500, 'medu-wiggle': 4000 },
  'm-fast': { 'medu-bob': 1600, 'medu-pulse': 1600, 'medu-wiggle': 1200 },
};

// ───────── Parts ─────────

type Spark = 'spark0' | 'spark1' | 'spark2' | 'spark3';
type Part =
  | 'eyes' | 'eyesLook' | 'eyesUp' | 'eyesClosed' | 'eyesHappy' | 'eyesHeart'
  | 'browsLost' | 'browsOops' | 'browsSad'
  | 'mSmile' | 'mWobble' | 'mO' | 'mFlat' | 'mGrin' | 'mFrown' | 'mSad' | 'mLove' | 'mChew' | 'party'
  | 'tear' | 'popcorn' | 'd4' | 'plugT' | 'plug' | 'arm' | 'torch' | 'q' | 'sweat' | 'zs' | 'dots' | 'hearts' | Spark;

const SPARKS: Spark[] = ['spark0', 'spark1', 'spark2', 'spark3'];
/* The face of each mood (inside the bell), and what comes with it. */
const FACE: Record<MeduMood, Part[]> = {
  idle: ['eyes', 'mSmile'], wave: ['eyes', 'mSmile'], search: ['eyesLook', 'browsLost', 'mWobble'], sleepy: ['eyesClosed', 'mO'],
  yay: ['eyesHappy', 'mGrin'], think: ['eyesUp', 'mFlat'], oops: ['eyes', 'browsOops', 'mFrown'], love: ['eyesHeart', 'mLove'],
  popcorn: ['eyesUp', 'mChew'], sad: ['eyes', 'browsSad', 'mSad'], party: ['party']
};
const PROPS: Record<MeduMood, Part[]> = {
  idle: [], wave: ['arm'], search: ['q', 'sweat'], sleepy: ['zs'], yay: SPARKS, think: ['dots'], oops: ['plugT', 'plug'],
  love: ['hearts'], popcorn: ['popcorn'], sad: ['tear'], party: []
};
const FACE_PARTS = new Set<Part>(Object.values(FACE).flat());
/* Parts that stand in for the fourth tentacle: they grow and shrink rather than pop. */
const LIMBS = new Set<Part>(['d4', 'plugT', 'torch']);

function partsOf(mood: MeduMood, torch: boolean): Set<Part> {
  const parts = new Set<Part>([...FACE[mood], ...PROPS[mood]]);
  if (mood === 'search' && torch) parts.add('torch');
  if (!parts.has('torch') && !parts.has('plugT')) parts.add('d4');
  return parts;
}

// ───────── Bridges ─────────

/* Where each part turns, shrinks or pops from in a bridge the board doesn't draw (drawing units). */
const AT: Record<Part, [number, number] | undefined> = {
  eyes: [100, 83], eyesLook: [100, 83], eyesUp: [100, 80], eyesClosed: [100, 86], eyesHappy: [100, 86], eyesHeart: [100, 85],
  browsLost: [100, 66], browsOops: [100, 70], browsSad: [100, 68],
  mSmile: [100, 98], mWobble: [100, 99], mO: [100, 99], mFlat: [100, 98], mGrin: [100, 100], mFrown: [100, 98], mSad: [100, 100],
  mLove: [100, 103], mChew: [100, 99], party: [100, 70],
  tear: [73, 100], popcorn: [100, 176], d4: [152, 112], plugT: [152, 112], plug: [170, 200], arm: [163, 96], torch: [152, 112],
  q: [164, 22], sweat: [150, 72], zs: [170, 30], dots: [100, 0], hearts: [130, 60],
  spark0: undefined, spark1: undefined, spark2: undefined, spark3: undefined
};

type Span = [number, number];
/* When, within a bridge, the old props leave, the new ones arrive, the face swaps and the blush changes. */
interface Windows { exit: Span; enter: Span; swap: number; blush: Span }
const GENERIC: Windows = { exit: [0, 0.35], enter: [0.45, 0.8], swap: 0.42, blush: [0.35, 0.6] };
/* The board's bridges move everything they draw; these place what they don't (the torch, the plugged tentacle). */
const DRAWN: Record<string, Partial<Windows>> = {
  'search>sleepy': { exit: [0, 0.3], enter: [0.2, 0.5], blush: [0.3, 0.62] },
  'sleepy>search': { exit: [0.1, 0.3], enter: [0.35, 0.65], blush: [0.1, 0.4] },
  'oops>wave': { exit: [0.2, 0.5], enter: [0.35, 0.75] },
  'wave>oops': { exit: [0.5, 0.75], enter: [0.6, 0.95] },
};

const hold = (a: number, b: number, from: MeduFrame, to: MeduFrame): MeduFrame[] =>
  [[0, from[1], from[2]], [a, from[1], from[2], from[3]], [b, to[1], to[2]], [1, to[1], to[2]]];
const propOut = ([a, b]: Span) => hold(a, b, [0, 'translateY(0px) scale(1)', 1, 'ease-in'], [0, 'translateY(10px) scale(.4)', 0]);
const propIn = ([a, b]: Span): MeduFrame[] => [
  [0, 'translateY(0px) scale(0)', 0], [a, 'translateY(0px) scale(0)', 0, 'cubic-bezier(.2,.8,.3,1.4)'],
  [a + (b - a) * 0.65, 'translateY(0px) scale(1.15)', 1, 'ease-in-out'], [b, 'translateY(0px) scale(1)', 1], [1, 'translateY(0px) scale(1)', 1]
];
const limbOut = ([a, b]: Span) => hold(a, b, [0, 'scaleY(1)', 1, 'ease-in'], [0, 'scaleY(.2)', 0]);
const limbIn = ([a, b]: Span) => hold(a, b, [0, 'scaleY(.2)', 0, 'cubic-bezier(.2,.8,.3,1.3)'], [0, 'scaleY(1)', 1]);
/* Face parts swap while squashed shut, like a blink (rule 3). */
const faceOut = (s: number): MeduFrame[] => [[0, 'scaleY(1)', 1], [s - 0.1, 'scaleY(1)', 1, 'ease-in'], [s, 'scaleY(.1)', 1], [s + 0.005, 'scaleY(.1)', 0], [1, 'scaleY(.1)', 0]];
const faceIn = (s: number): MeduFrame[] => [
  [0, 'scaleY(.1)', 0], [s, 'scaleY(.1)', 0], [s + 0.005, 'scaleY(.1)', 1, 'cubic-bezier(.2,.8,.3,1)'],
  [s + 0.12, 'scaleY(1.12)', 1, 'ease-in-out'], [s + 0.2, 'scaleY(1)', 1], [1, 'scaleY(1)', 1]
];
const blink = (s: number): MeduFrame[] => [[0, 'scaleY(1)'], [s - 0.1, 'scaleY(1)', null, 'ease-in'], [s, 'scaleY(.1)', null, 'ease-out'], [s + 0.12, 'scaleY(1)'], [1, 'scaleY(1)']];
const ty = (y: number) => `translateY(${y}px)`;

/* A bridge the board doesn't draw: wind up, hop, swap the face at the top of the hop, land with a squash (rule 4).
   Towards Sad or Sleepy Medu sighs instead: a breath in, then it sinks and settles. */
function genericBody(fromY: number, to: MeduMood): Record<string, MeduTrack> {
  if (to === 'sad' || to === 'sleepy') return {
    b: { at: [100, 130], frames: [[0, ty(fromY), null, 'ease-in-out'], [0.22, ty(fromY - 3), null, 'ease-in-out'], [0.55, ty(8), null, 'ease-in-out'], [1, ty(0)]] },
    bell: { at: [100, 124], frames: [[0, 'scale(1,1)', null, 'ease-in-out'], [0.22, 'scale(.97,1.04)', null, 'ease-in-out'], [0.55, 'scale(1.05,.94)', null, 'ease-in-out'], [1, 'scale(1,1)']] },
    tg: { at: [100, 116], frames: [[0, 'scaleY(1)', null, 'ease-in-out'], [0.22, 'scaleY(1.04)', null, 'ease-in-out'], [0.55, 'scaleY(.94)', null, 'ease-in-out'], [1, 'scaleY(1)']] },
  };
  const top = to === 'yay' || to === 'love' || to === 'party' || to === 'popcorn' || to === 'wave' ? -12 : -6;
  return {
    b: { at: [100, 130], frames: [[0, ty(fromY), null, 'ease-in-out'], [0.16, ty(fromY + 4), null, 'cubic-bezier(.2,.7,.3,1)'], [0.42, ty(top), null, 'cubic-bezier(.5,0,.5,1)'], [0.7, ty(3), null, 'ease-out'], [1, ty(0)]] },
    bell: { at: [100, 124], frames: [[0, 'scale(1,1)', null, 'ease-out'], [0.16, 'scale(1.07,.93)', null, 'cubic-bezier(.2,.7,.3,1)'], [0.42, 'scale(.94,1.08)', null, 'ease-in-out'], [0.7, 'scale(1.05,.95)', null, 'ease-out'], [1, 'scale(1,1)']] },
    tg: { at: [100, 116], frames: [[0, 'scaleY(1)', null, 'ease-out'], [0.16, 'scaleY(.92)', null, 'ease-out'], [0.42, 'scaleY(1.1)', null, 'ease-in-out'], [0.7, 'scaleY(.93)', null, 'ease-in-out'], [1, 'scaleY(1)']] },
  };
}

const offsetY = (transform?: string | null) => Number(/translateY\((-?[\d.]+)px\)/.exec(transform ?? '')?.[1] ?? 0);

interface Run { to: MeduMood; ms: number; tracks: Record<string, MeduTrack>; restY: number }

/* How Medu gets from one mood to the next. `restY` is where the body sits now (Sleepy sinks 10 units when it dozes off). */
function plan(from: MeduMood, to: MeduMood, torch: boolean, restY: number): Run {
  const key = `${from}>${to}`;
  const drawn = BRIDGES[key];
  const win = { ...GENERIC, ...DRAWN[key] };
  const tracks: Record<string, MeduTrack> = drawn ? { ...drawn.tracks } : genericBody(restY, to);
  const was = partsOf(from, torch);
  const will = partsOf(to, torch);
  for (const part of new Set([...was, ...will])) {
    if (tracks[part]) continue;
    const face = FACE_PARTS.has(part);
    if (was.has(part) && will.has(part)) {
      if (face && !drawn) tracks[part] = { at: AT[part], frames: blink(win.swap) };
      continue;
    }
    const leaving = was.has(part);
    const frames = face ? (leaving ? faceOut(win.swap) : faceIn(win.swap))
      : LIMBS.has(part) ? (leaving ? limbOut(win.exit) : limbIn(win.enter))
        : leaving ? propOut(win.exit) : propIn(win.enter);
    tracks[part] = { at: AT[part], frames };
  }
  if (!tracks.blush && BLUSH[from] !== BLUSH[to]) {
    tracks.blush = { at: [100, 97], frames: hold(win.blush[0], win.blush[1], [0, null, BLUSH[from], 'ease-in-out'], [0, null, BLUSH[to]]) };
  }
  // The body starts from where it rests now: swap in that height while the board's track still holds still.
  const body = tracks.b;
  if (body && offsetY(body.frames[0][1]) !== restY) {
    const start = body.frames[0][1];
    let holding = true;
    tracks.b = {
      ...body,
      frames: body.frames.map((f): MeduFrame => {
        holding = holding && f[1] === start;
        return holding ? [f[0], (f[1] ?? ty(0)).replace(/translateY\(-?[\d.]+px\)/, ty(restY)), f[2], f[3]] : f;
      }),
    };
  }
  const end = tracks.b?.frames[tracks.b.frames.length - 1][1];
  return { to, ms: drawn?.ms ?? 1000, tracks, restY: tracks.b ? offsetY(end) : restY };
}

function play(svg: SVGSVGElement, run: Run): Animation[] {
  const anims: Animation[] = [];
  for (const [part, track] of Object.entries(run.tracks)) {
    const el = svg.querySelector<SVGGElement>(`[data-p="${part}"]`);
    if (!el) continue;
    el.style.transformBox = track.at ? 'view-box' : 'fill-box';
    el.style.transformOrigin = track.at ? `${track.at[0]}px ${track.at[1]}px` : '50% 50%';
    const frames = track.frames.map(([offset, transform, opacity, easing]) => ({
      offset,
      ...(transform != null && { transform }),
      ...(opacity != null && { opacity }),
      ...(easing && { easing }),
    }));
    anims.push(el.animate(frames, { duration: run.ms, fill: 'both' }));
  }
  return anims;
}

/* The idle float keeps its place in its loop when Medu changes speed (no jump as Sleepy wakes up). */
function keepPhase(svg: SVGSVGElement, from: string, to: string) {
  try {
    for (const anim of svg.getAnimations({ subtree: true })) {
      const name = (anim as CSSAnimation).animationName;
      const before = LOOPS[from][name];
      const after = LOOPS[to][name];
      if (!before || !after || anim.currentTime == null) continue;
      const delay = anim.effect?.getTiming().delay ?? 0;
      const phase = ((((Number(anim.currentTime) - delay) / before) % 1) + 1) % 1;
      let time = delay + phase * after;
      while (time < 0) time += after;
      anim.currentTime = time;
    }
  } catch { /* no getAnimations: the loop just restarts its phase */ }
}

const still = () => typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches;

// ───────── Drawing ─────────

const fill = (color: string) => ({ fill: color });
const stroke = (color: string) => ({ stroke: color });
const line = (d: string, width = 3, extra?: Record<string, string>) =>
  <path d={d} fill="none" stroke={INK} stroke-width={width} stroke-linecap="round" {...extra} />;

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

/* One face part. */
function facePart(part: Part) {
  switch (part) {
    case 'eyes': return <Eyes />;
    case 'eyesLook': return <Eyes look />;
    case 'eyesUp': return <Eyes dy={-3} />;
    case 'eyesClosed': return <>{line('M73 86 Q80 92 87 86', 3.2)}{line('M113 86 Q120 92 127 86', 3.2)}</>;
    case 'eyesHappy': return <>{line('M73 88 Q80 79 87 88', 3.6)}{line('M113 88 Q120 79 127 88', 3.6)}</>;
    case 'eyesHeart': return [80, 120].map((x) => (
      <g key={x}>
        <path d={`M${x} 96 C${x - 17} 86 ${x - 11} 70 ${x} 77 C${x + 11} 70 ${x + 17} 86 ${x} 96 Z`} style={fill(ACCENT)} />
        <circle cx={x - 6} cy="78" r="2.4" fill="#ffffff" opacity="0.9" />
      </g>
    ));
    case 'browsLost': return <>{line('M71 69 Q78 63 86 66')}{line('M114 64 Q121 61 129 66')}</>;
    case 'browsOops': return <>{line('M70 72 L86 67')}{line('M130 72 L114 67')}</>;
    case 'browsSad': return <>{line('M70 71 Q78 64 86 68')}{line('M114 68 Q122 64 130 71')}</>;
    case 'mSmile': return line('M93 95 Q100 102 107 95', 3.2);
    case 'mWobble': return line('M93 99 q3.5 -4 7 0 t7 0', 3, { 'stroke-linejoin': 'round' });
    case 'mO': return <ellipse cx="100" cy="99" rx="3.4" ry="2.8" fill={INK} />;
    case 'mFlat': return line('M94 98 L106 97');
    case 'mGrin': return <>
      <path d="M89 94 Q100 112 111 94 Z" fill={INK} stroke={INK} stroke-width="2.4" stroke-linejoin="round" />
      <path d="M95 101 Q100 106 105 101 Q100 98 95 101 Z" style={fill(ACCENT)} />
    </>;
    case 'mFrown': return line('M92 101 Q100 94 108 101');
    case 'mSad': return line('M92 103 Q100 96 108 103');
    case 'mLove': return <path d="M89 98 Q100 113 111 98 Z" fill={INK} stroke={INK} stroke-width="2.4" stroke-linejoin="round" />;
    case 'mChew': return <ellipse class="m-chew" cx="100" cy="99" rx="4.5" ry="3.4" fill={INK} />;
    case 'party': return <>
      <path d="M78 31 Q100 40 122 31 L106 -18 Z" style={fill(ACCENT)} />
      <path d="M86 14 Q100 20 114 14 M93 -2 Q101 2 109 -2" fill="none" stroke="#ffffff" stroke-width="4" stroke-linecap="round" opacity="0.9" />
      <circle cx="106" cy="-19" r="8" fill="#ffffff" />
      <rect x="64" y="72" width="31" height="22" rx="9" fill={INK} />
      <rect x="105" y="72" width="31" height="22" rx="9" fill={INK} />
      {line('M95 79 Q100 75 105 79 M64 78 L48 72 M136 78 L152 72', 3.5)}
      <path d="M71 77 L79 86 M112 77 L120 86" stroke="#ffffff" stroke-width="3" stroke-linecap="round" opacity="0.85" />
      <path d="M88 98 Q100 116 112 98 Z" fill={INK} stroke={INK} stroke-width="2.4" stroke-linejoin="round" />
      <path d="M94 105 Q100 111 106 105 Q100 101 94 105 Z" style={fill(ACCENT)} />
    </>;
    default: return null;
  }
}
const FACE_ORDER: Part[] = [
  'eyes', 'eyesLook', 'eyesUp', 'eyesClosed', 'eyesHappy', 'eyesHeart', 'browsLost', 'browsOops', 'browsSad',
  'mSmile', 'mWobble', 'mO', 'mFlat', 'mGrin', 'mFrown', 'mSad', 'mLove', 'mChew', 'party'
];

/* Popcorn: the bucket held in two tentacles, a kernel hopping out. */
const Popcorn = () => <>
  {([[80, 124, 7], [91, 119, 8], [103, 117, 8], [115, 119, 8], [125, 124, 6], [97, 127, 6], [110, 127, 6]] as const).map(([cx, cy, r]) => (
    <circle key={`${cx}-${cy}`} cx={cx} cy={cy} r={r} fill="#fff3cf" stroke="#ecd59c" stroke-width="1.5" />
  ))}
  <path d="M70 126 L130 126 L123 176 L77 176 Z" fill="#fdf6fe" />
  <path d="M84 126 L96 126 L95 176 L86 176 Z M106 126 L118 126 L113 176 L104 176 Z" style={fill(ACCENT)} />
  <rect x="67" y="122" width="66" height="8" rx="3" fill="#f4eef6" />
  <circle class="m-pop" cx="108" cy="114" r="5" fill="#fff3cf" stroke="#ecd59c" stroke-width="1.5" />
  <path d="M56 112 C52 132 62 146 76 146 M144 112 C148 132 138 146 124 146" fill="none" stroke={ARM} stroke-width="6.5" stroke-linecap="round" />
</>;

const SPARK_AT = [[28, 30, 1.1, ACCENT, 0], [176, 14, 1.4, '#ffffff', -0.5], [186, 96, 0.9, ACCENT, -1], [18, 110, 0.8, '#ffffff', -0.8]] as const;

export interface MeduProps {
  mood?: MeduMood;
  /** Width in CSS pixels; the height follows (264 / 200). Under 80 the small variant draws. */
  size: number;
  /** Lost Medu holds a torch whose beam sweeps the page (404, finding servers). Without it, the fourth tentacle stays. */
  torch?: boolean;
  /** The soft accent halo behind the bell. */
  glow?: boolean;
  class?: string;
  /** Medu has reached a new `mood` (after its bridge, or at once with reduced motion). */
  onSettled?: (mood: MeduMood) => void;
}

export function Medu({ mood = 'idle', size, torch = false, glow, class: cls, onSettled }: MeduProps) {
  const uid = useId().replace(/[^\w-]/g, '');
  const small = size < 80;
  const halo = glow ?? !small;
  const svgRef = useRef<SVGSVGElement>(null);
  const [shown, setShown] = useState(mood);
  const [run, setRun] = useState<Run | null>(null);
  const [restY, setRestY] = useState(0);
  const settled = useRef(onSettled);
  settled.current = onSettled;
  const moving = useRef(false);

  // A new mood: bridge to it, unless a bridge is still running (the next one starts when it ends).
  useEffect(() => {
    if (run || mood === shown) return;
    moving.current = true;
    if (small || still()) { setShown(mood); setRestY(0); return; }
    setRun(plan(shown, mood, torch && !small, restY));
  }, [mood, shown, run]);

  // Start the bridge before the first frame paints; hand over to the plain drawing when it ends.
  useLayoutEffect(() => {
    const svg = svgRef.current;
    if (!run || !svg) return;
    const anims = play(svg, run);
    const timer = window.setTimeout(() => { setRestY(run.restY); setShown(run.to); setRun(null); }, run.ms);
    return () => { window.clearTimeout(timer); anims.forEach((a) => a.cancel()); };
  }, [run]);

  useEffect(() => {
    if (run || shown !== mood || !moving.current) return;
    moving.current = false;
    settled.current?.(shown);
  }, [run, shown, mood]);

  const speed = small ? '' : SPEED[shown] ?? '';
  const lastSpeed = useRef(speed);
  useLayoutEffect(() => {
    if (lastSpeed.current !== speed && svgRef.current) keepPhase(svgRef.current, lastSpeed.current, speed);
    lastSpeed.current = speed;
  }, [speed]);

  const parts = partsOf(shown, torch && !small);
  if (run) for (const part of partsOf(run.to, torch && !small)) parts.add(part);
  const has = (part: Part) => parts.has(part);
  const P = (part: Part, children: ComponentChildren) => has(part) && <g key={part} data-p={part}>{children}</g>;

  return (
    <svg ref={svgRef} class={`medu${speed ? ` ${speed}` : ''}${cls ? ` ${cls}` : ''}`} viewBox="0 -24 200 264" width={size} height={Math.round(size * 1.32)} aria-hidden="true">
      <defs>
        <linearGradient id={`mb-${uid}`} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stop-color="#fdf6fe" />
          <stop offset="1" stop-color="#ebd3f0" />
        </linearGradient>
        <radialGradient id={`mg-${uid}`}>
          <stop offset="0" style={{ stopColor: ACCENT, stopOpacity: 0.32 }} />
          <stop offset="1" style={{ stopColor: ACCENT, stopOpacity: 0 }} />
        </radialGradient>
        {has('torch') && <>
          <linearGradient id={`bm-${uid}`} gradientUnits="userSpaceOnUse" x1="30" y1="0" x2="640" y2="0">
            <stop offset="0" stop-color="#fff4d6" stop-opacity="0.55" />
            <stop offset="0.35" stop-color="#fff4d6" stop-opacity="0.2" />
            <stop offset="1" stop-color="#fff4d6" stop-opacity="0" />
          </linearGradient>
          <filter id={`bf-${uid}`} x="-10%" y="-30%" width="120%" height="160%"><feGaussianBlur stdDeviation="5" /></filter>
        </>}
      </defs>
      {halo && <circle cx="100" cy="96" r="108" fill={`url(#mg-${uid})`} />}
      <g data-p="b" style={{ transform: restY ? ty(restY) : '' }}>
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
            <g data-p="tg">
              {(['d1', 'd3', 'd2'] as const).map((k) => (
                <g key={k} class={`m-t m-${k}`}><path d={TENTACLES[k]} fill="none" stroke={ARM} stroke-width="6" stroke-linecap="round" /></g>
              ))}
              {P('d4', <g class="m-t m-d4"><path d={TENTACLES.d4} fill="none" stroke={ARM} stroke-width="6" stroke-linecap="round" /></g>)}
              {[['d5', 'M90 116 C82 132 98 144 88 160 C80 174 94 182 88 196'], ['d2', 'M110 116 C118 132 102 144 112 160 C120 174 106 182 112 196']].map(([delay, d]) => (
                <g key={d} class={`m-t m-${delay}`}>
                  <path d={d} fill="none" style={stroke(ACCENT)} stroke-opacity="0.75" stroke-width="8" stroke-linecap="round" />
                </g>
              ))}
              {(has('plugT') || has('plug')) && (
                <g class="m-plug">
                  {P('plugT', <path d="M152 112 C160 136 146 150 156 170 C162 182 168 190 170 200" fill="none" stroke={ARM} stroke-width="6" stroke-linecap="round" />)}
                  {P('plug', <>
                    <path d="M170 200 C172 212 160 218 166 230" fill="none" stroke="#8c8c8c" stroke-width="3.5" stroke-linecap="round" />
                    <rect x="157" y="226" width="18" height="13" rx="3.5" fill="#a1a1a1" />
                    <path d="M161 239v6M171 239v6" stroke="#a1a1a1" stroke-width="3" stroke-linecap="round" />
                  </>)}
                </g>
              )}
            </g>
            {P('arm', <g class="m-wave">
              <path d="M163 96 C180 92 188 76 180 60" fill="none" stroke={ARM} stroke-width="8" stroke-linecap="round" />
              <circle cx="180" cy="58" r="6.5" fill={ARM} />
            </g>)}
            {P('torch', <>
              <g transform="translate(186 128) rotate(16)">
                <g class="m-beam">
                  <polygon points="30,-6 640,-160 640,160 30,6" fill={`url(#bm-${uid})`} filter={`url(#bf-${uid})`} />
                </g>
                <rect x="0" y="-6" width="22" height="12" rx="3" fill="#a1a1a1" />
                <rect x="20" y="-9" width="11" height="18" rx="3" fill="#d4d4d4" />
                <path d="M31 -7 V7" stroke="#fff4d6" stroke-width="3" stroke-linecap="round" />
              </g>
              <path d="M152 112 C156 126 166 133 192 131" fill="none" stroke={ARM} stroke-width="6.5" stroke-linecap="round" />
            </>)}
            <g class="m-bell">
              <g data-p="bell">
                <path d={BELL} fill={`url(#mb-${uid})`} />
                <path d={FRILL} fill="none" stroke="#d6b3de" stroke-width="2.5" stroke-linejoin="round" opacity="0.85" />
                {([[76, 44, 6.5], [100, 35, 4.2], [124, 45, 5.5]] as const).map(([cx, cy, r]) => (
                  <circle key={cx} cx={cx} cy={cy} r={r} style={fill(ACCENT)} opacity="0.32" />
                ))}
                <ellipse cx="54" cy="70" rx="7" ry="15" transform="rotate(24 54 70)" fill="#ffffff" opacity="0.9" />
                {FACE_ORDER.map((part) => P(part, facePart(part)))}
                {P('tear', <path class="m-tear" d="M73 95 C73 95 68 102 68 105 a5 5 0 0 0 10 0 C78 102 73 95 73 95 Z" fill="#9fd3ff" />)}
                <g data-p="blush" opacity={BLUSH[shown]}>
                  {[64, 136].map((cx) => <ellipse key={cx} cx={cx} cy="97" rx="8.5" ry="4.6" style={fill(ACCENT)} />)}
                </g>
                {P('popcorn', <Popcorn />)}
              </g>
            </g>
          </>}
        </g>
        {!small && <>
          {P('q', <g class="m-q">
            <text x="164" y="22" text-anchor="middle" font-family="Noto Sans Variable, Noto Sans, sans-serif" font-size="46" font-weight="900" style={fill(ACCENT)}>?</text>
          </g>)}
          {P('sweat', <path d="M150 62 C150 62 143 72 143 77 a7 7 0 0 0 14 0 C157 72 150 62 150 62 Z" fill="#9fd3ff" opacity="0.9" />)}
          {P('zs', ([[148, 40, 20, 0], [166, 18, 28, -1.2], [186, -6, 22, -2.4]] as const).map(([x, y, s, delay]) => (
            <g key={x} class="m-z" style={{ animationDelay: `${delay}s` }}>
              <text x={x} y={y} font-family="Noto Sans Variable, Noto Sans, sans-serif" font-size={s} font-weight="900" fill="#e0e0e0">z</text>
            </g>
          )))}
          {P('dots', ([[82, '#e0e0e0', 0], [100, ACCENT, 0.18], [118, '#e0e0e0', 0.36]] as const).map(([cx, color, delay]) => (
            <circle key={cx} class="m-dot" cx={cx} cy="0" r="6" style={{ fill: color, animationDelay: `${delay}s` }} />
          )))}
          {P('hearts', ([[160, 34, 1.2, 0], [34, 58, 0.9, -0.9], [172, 92, 0.8, -1.7]] as const).map(([x, y, s, delay]) => (
            <g key={x} transform={`translate(${x} ${y}) scale(${s})`}>
              <path class="m-heart" d={HEART} style={{ fill: ACCENT, animationDelay: `${delay}s` }} />
            </g>
          )))}
        </>}
        {SPARKS.map((part, i) => {
          const [x, y, s, color, delay] = SPARK_AT[i];
          return has(part) && (
            <g key={part} transform={`translate(${x} ${y}) scale(${s})`}>
              <g data-p={part}><path class="m-spark" d={SPARK} style={{ fill: color, animationDelay: `${delay}s` }} /></g>
            </g>
          );
        })}
      </g>
    </svg>
  );
}

/** Medu above a title, a plain sentence and a way out: the empty pages (Empty pages and My requests boards). */
export function MeduState({ mood = 'sleepy', size = 150, title, text, actions, children, large }: {
  mood?: MeduMood;
  size?: number;
  title: ComponentChildren;
  text?: ComponentChildren;
  actions?: ComponentChildren;
  /** Extra content under the actions (the scan progress of a new library). */
  children?: ComponentChildren;
  /** The bigger My requests variant (30 px title). */
  large?: boolean;
}) {
  return (
    <div class={'medu-state' + (large ? ' is-large' : '')}>
      <div class="medu-state-body">
        <Medu mood={mood} size={size} />
        <h2 class="medu-state-title">{title}</h2>
        {text && <p class="medu-state-text">{text}</p>}
        {actions && <div class="medu-state-actions">{actions}</div>}
        {children}
      </div>
    </div>
  );
}
