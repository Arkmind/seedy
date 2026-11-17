import { useEffect, useId, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { audioStore, holdSession, pause as pauseMusic, play as playMusic, releaseSession } from '../audio/engine';
import { bootState } from '../lib/app-state';
import { t, tn } from '../lib/i18n';
import { safeLocal, saveLocal } from '../lib/store';
import { CUSTOM, readCustom, saveCustom } from '../screens/me/prefs';
import { Icon, volumeIcon } from '../ui/icons';
import { Medu, type MeduMood } from '../ui/medu';
import { BEAT, BPM, LANES, LEVELS, LIVES, Run, formatScore, stepCount, type Judgment, type Level } from './game';
import song from './smack-attack.mp3';
import './party.css';

/* Medu Dance Revolution (Konami code board): a disco, five dancing Medus and a four-arrow rhythm game on
   "Smack Attack (Medu Mix)". The board is 1440 × 900; the stage keeps that size scaled to fit the window and widens
   (or deepens) to fill what's left, so the side panels stay at the edges. The song's clock drives the notes. */

const BOARD_W = 1440;
const BOARD_H = 900;

/* The arrow lane: receptors 66 px apart at the top, notes rising from the bottom at 150 px a beat (Drift's drift slower,
   Abyssal's rise faster so its sixteenths stay apart). */
const LANE_H = 470;
const HIT_Y = 16;
const LANE_X = (lane: number) => 14 + lane * 66;
const SCROLL: Record<Level, number> = { Drift: 120, Light: 150, Swell: 150, Tentacular: 150, Abyssal: 190 };
const LEVEL_COLOURS: Record<Level, string> = { Drift: '#9fd3ff', Light: '#9fe0b0', Swell: '#fff59f', Tentacular: '#ffc98a', Abyssal: '#ff9fff' };
const COLOURS = ['#ff9fff', '#9fd3ff', '#9fe0b0', '#ffc98a'];
const TURN = [-90, 180, 0, 90];
const KEYS: Record<string, number> = { ArrowLeft: 0, ArrowDown: 1, ArrowUp: 2, ArrowRight: 3 };
const LEAVE = ['Escape', 'Backspace', 'BrowserBack', 'GoBack'];
const LEVEL_KEY = 'seedy.party.level';
const VOLUME_KEY = 'seedy.party.volume';
/* The song is mastered loud (about −10 LUFS, hotter than most music): the party starts at 40 %. */
const VOLUME = 0.4;

type Phase = 'loading' | 'blocked' | 'playing' | 'clear' | 'over';
interface Hud { score: number; combo: number; lives: number; judge: Judgment | null; n: number }
interface LaneCtl { press: (lane: number, hit: boolean, synthetic: boolean) => void; release: (lane: number) => void }

/* ── The backdrop (positions from the board) ── */

const BEAMS: [number, string, number, number][] = [[200, '#ff9fff', 0, 7], [560, '#9fd3ff', -2.5, 8.5], [900, '#9fe0b0', -5, 7.5], [1250, '#ffc98a', -1.2, 9]];
const SPECKS = [[1113, 886, 3, 0.55], [1247, 342, 3, 0.41], [1201, 146, 5, 0.37], [965, 631, 4, 0.49], [102, 571, 4, 0.44], [352, 416, 4, 0.34], [1332, 619, 3, 0.33], [466, 691, 3, 0.27], [1183, 724, 6, 0.39], [95, 821, 3, 0.45], [1033, 191, 3, 0.42], [1273, 578, 3, 0.3], [201, 670, 4, 0.44], [1075, 385, 4, 0.45], [521, 897, 3, 0.3], [710, 680, 4, 0.55], [220, 143, 6, 0.49], [858, 346, 4, 0.46], [673, 226, 6, 0.44], [1357, 612, 5, 0.29], [1274, 675, 3, 0.52], [489, 264, 4, 0.31], [4, 649, 6, 0.5], [1399, 685, 5, 0.43], [153, 562, 5, 0.54], [731, 179, 4, 0.38], [232, 858, 4, 0.57], [1322, 539, 5, 0.42], [161, 326, 3, 0.59], [1161, 215, 3, 0.33], [816, 70, 6, 0.51], [1175, 880, 3, 0.44], [181, 593, 5, 0.58], [237, 474, 5, 0.46], [1388, 836, 5, 0.51], [993, 641, 3, 0.39], [967, 336, 6, 0.56], [650, 223, 3, 0.27], [30, 498, 6, 0.46], [10, 637, 3, 0.27], [97, 28, 5, 0.37], [740, 250, 5, 0.42], [776, 651, 4, 0.56], [829, 218, 3, 0.42], [586, 84, 3, 0.48], [510, 369, 4, 0.55]];
const CONFETTI: [number, number, number, number, string, number, number][] = [[1060, 8, 12, 2, '#9fe0b0', 6.3, -4.9], [592, 12, 18, 2, '#c9a8ff', 10, -8.6], [1167, 8, 16, 2, '#9fe0b0', 7, -1.1], [1009, 12, 10, 6, '#ffc98a', 6.7, -5.8], [1236, 8, 18, 2, '#9fd3ff', 8.3, -1.7], [1322, 8, 10, 2, '#9fe0b0', 10, -3.1], [1145, 12, 12, 6, '#9fd3ff', 6.3, -1.4], [950, 12, 12, 6, '#ffc98a', 10.8, -3.6], [1244, 8, 16, 2, '#9fe0b0', 8, -2.8], [733, 10, 16, 2, '#c9a8ff', 10.3, -10.1], [772, 8, 16, 2, '#ff9fff', 7.9, -0.9], [1397, 10, 18, 2, '#9fd3ff', 10.6, -6.2], [1416, 10, 18, 2, '#ff9fff', 8.1, -0.9], [625, 12, 10, 6, '#c9a8ff', 6.8, -5.3], [628, 10, 16, 2, '#fff59f', 10.8, -1.3], [1295, 12, 12, 6, '#9fd3ff', 9.7, -2.5], [1020, 8, 12, 2, '#ff9fff', 8.9, -4.6], [1161, 12, 16, 2, '#c9a8ff', 6.3, -4.5], [1068, 12, 12, 6, '#c9a8ff', 10.3, -2], [621, 8, 12, 2, '#c9a8ff', 9.4, -8.6], [897, 8, 10, 2, '#c9a8ff', 6.6, -5.6], [1036, 12, 16, 2, '#9fe0b0', 8.9, -4.5], [960, 8, 12, 2, '#9fe0b0', 8.1, -3.3], [425, 12, 18, 2, '#9fd3ff', 10.7, -2], [957, 8, 18, 2, '#ffc98a', 7.2, -4.9], [1300, 10, 16, 2, '#fff59f', 9.4, -5.3], [584, 10, 10, 6, '#ff9fff', 7.6, -2.3], [302, 8, 12, 2, '#fff59f', 8.3, -5], [464, 12, 12, 6, '#ffc98a', 6.4, -2.8], [307, 8, 18, 2, '#ffc98a', 6.7, -5.3], [1382, 10, 12, 2, '#c9a8ff', 10.4, -1.8], [122, 10, 18, 2, '#fff59f', 6.8, -4.5], [891, 12, 18, 2, '#ff9fff', 9.1, -7.4], [1081, 12, 12, 6, '#c9a8ff', 9.3, -2.9], [1161, 12, 18, 2, '#9fd3ff', 10.7, -0.3], [352, 8, 16, 2, '#ffc98a', 7.3, -4.5], [1106, 8, 16, 2, '#ffc98a', 7.4, -3.1], [906, 12, 16, 2, '#9fd3ff', 9.6, -6.9], [552, 8, 18, 2, '#ffc98a', 8.5, -2.1], [944, 10, 12, 2, '#ff9fff', 6.5, -5.8], [1239, 10, 18, 2, '#ff9fff', 6.2, -4.8], [315, 10, 16, 2, '#c9a8ff', 9.2, -4.8], [72, 10, 16, 2, '#9fd3ff', 9.7, -8.9], [174, 12, 10, 6, '#9fd3ff', 8.8, -7.1], [961, 8, 12, 2, '#fff59f', 10.7, -6.4], [1368, 12, 16, 2, '#ffc98a', 8.7, -5.3], [1035, 10, 16, 2, '#fff59f', 8.1, -3.5], [1305, 8, 18, 2, '#ffc98a', 7.8, -7], [1070, 8, 18, 2, '#ffc98a', 7.1, -3.2], [1082, 8, 12, 2, '#c9a8ff', 7.8, -2.2], [1119, 12, 10, 6, '#ffc98a', 10.9, -6.8], [395, 12, 16, 2, '#ff9fff', 10.6, -7.6], [337, 8, 16, 2, '#9fe0b0', 10.9, -3.5], [777, 8, 10, 2, '#9fd3ff', 6.1, -0.9]];
/* The dance floor: 14 × 7 tiles, each a quarter of the colour loop behind its left neighbour. */
const TILES = Array.from({ length: 98 }, (_, i) => -((((Math.floor(i / 14) - (i % 14)) % 4) + 4) % 4) * 0.9);
const TILE_COLOURS = ['rgba(255, 159, 255, 0.34)', 'rgba(159, 211, 255, 0.3)', 'rgba(159, 224, 176, 0.28)', 'rgba(255, 201, 138, 0.3)'];
/* The dancers, from the stage's centre line: [x offset, top, width, mood, hue, half a bar late]. */
const DANCERS: [number, number, number, MeduMood, number, boolean][] = [
  [-328, 440, 128, 'yay', 150, true],
  [-220, 500, 104, 'party', 80, false],
  [116, 500, 104, 'party', 230, false],
  [204, 440, 128, 'yay', 300, true]
];
const pct = (x: number) => (x / BOARD_W) * 100 + '%';

function Backdrop() {
  return <>
    {BEAMS.map(([x, c, delay, dur]) => (
      <span key={x} aria-hidden="true" class="party-beam" style={{ left: pct(x), background: `linear-gradient(180deg, ${c}, rgba(0, 0, 0, 0) 85%)`, animationDelay: `${delay}s`, animationDuration: `${dur}s` }} />
    ))}
    <div aria-hidden="true" class="party-specks">
      {SPECKS.map(([x, y, size, o], i) => <span key={i} style={{ left: pct(x), top: y + 'px', width: size + 'px', height: size + 'px', opacity: o }} />)}
    </div>
    <div aria-hidden="true" class="party-floor">
      <div class="party-tiles">
        {/* The colour each tile shows when motion is reduced is where its loop would be. */}
        {TILES.map((d, i) => <span key={i} class="party-tile" style={{ animationDelay: `${d}s`, background: TILE_COLOURS[Math.round(-d / 0.9) % 4] }} />)}
      </div>
      <div class="party-floor-fade" />
    </div>
    {CONFETTI.map(([x, w, h, r, c, dur, delay], i) => (
      <span key={i} aria-hidden="true" class="party-confetti" style={{ left: pct(x), width: w + 'px', height: h + 'px', borderRadius: r + 'px', background: c, animationDuration: `${dur}s`, animationDelay: `${delay}s` }} />
    ))}
    <div aria-hidden="true" class="party-ball-wire"><span /><span class="party-ball" /></div>
  </>;
}

function Arrow({ lane, fill, stroke, glow }: { lane: number; fill: string; stroke: string; glow?: boolean }) {
  return (
    <svg viewBox="0 0 48 48" width="52" height="52" aria-hidden="true" class="party-arrow" style={{ transform: `rotate(${TURN[lane]}deg)`, filter: glow ? `drop-shadow(0 0 10px ${stroke})` : undefined }}>
      <path d="M24 5 L43 24 H32 V43 H16 V24 H5 Z" fill={fill} stroke={stroke} stroke-width="4" stroke-linejoin="round" />
    </svg>
  );
}

/* The song's position, smoothed between the media element's updates so the notes glide. */
function makeClock(audio: () => HTMLAudioElement | null) {
  let media = -1;
  let at = 0;
  return () => {
    const a = audio();
    if (!a) return 0;
    const m = a.currentTime;
    if (a.paused) return m;
    const now = performance.now();
    if (m !== media) { media = m; at = now; return m; }
    return m + Math.min(0.2, ((now - at) / 1000) * a.playbackRate);
  };
}

/* ── The arrow lane: notes move every frame, so only this part redraws per frame ── */

function LanePanel({ run, clock, ctl, onMiss, foot, onFoot }: {
  run: { current: Run };
  clock: () => number;
  ctl: { current: LaneCtl };
  onMiss: { current: () => void };
  foot: string;
  onFoot?: () => void;
}) {
  const [now, setNow] = useState(0);
  const [down, setDown] = useState([false, false, false, false]);
  const [glow, setGlow] = useState([0, 0, 0, 0]);

  useEffect(() => {
    let raf = 0;
    const frame = () => {
      const tm = clock();
      if (run.current.sweep(tm)) onMiss.current();
      setNow(tm);
      raf = requestAnimationFrame(frame);
    };
    raf = requestAnimationFrame(frame);
    const timers = new Set<number>();
    const set = (lane: number, on: boolean) => setDown((d) => (d[lane] === on ? d : d.map((v, i) => (i === lane ? on : v))));
    ctl.current = {
      press: (lane, hit, synthetic) => {
        set(lane, true);
        if (hit) setGlow((g) => g.map((v, i) => (i === lane ? v + 1 : v)));
        // A gamepad's d-pad sends key presses without releases.
        if (synthetic) { const id = window.setTimeout(() => { timers.delete(id); set(lane, false); }, 120); timers.add(id); }
      },
      release: (lane) => set(lane, false)
    };
    return () => { cancelAnimationFrame(raf); timers.forEach((id) => window.clearTimeout(id)); };
  }, []);

  const notes = run.current.notes;
  const speed = SCROLL[run.current.level] / BEAT;
  const lead = (LANE_H - HIT_Y) / speed + 0.1;
  // The first note still on screen (missed ones keep rising past the receptors for a moment).
  let lo = 0;
  let hi = notes.length;
  while (lo < hi) { const mid = (lo + hi) >> 1; if (notes[mid].t < now - 0.5) lo = mid + 1; else hi = mid; }
  const shown = [];
  for (let i = lo; i < notes.length && notes[i].t < now + lead; i++) {
    const n = notes[i];
    if (n.result && n.result !== 'miss') continue;
    shown.push(
      <span key={i} class={'party-note' + (n.result ? ' is-missed' : '')} style={{ left: LANE_X(n.lane) + 'px', transform: `translateY(${(HIT_Y + (n.t - now) * speed).toFixed(1)}px)` }}>
        <Arrow lane={n.lane} fill={COLOURS[n.lane]} stroke={COLOURS[n.lane]} glow />
      </span>
    );
  }

  return (
    <section aria-label={t('Arrows')} class="party-lane">
      <div aria-hidden="true" class="party-notes">{shown}</div>
      {LANES.map((_, i) => (
        <span key={i} aria-hidden="true" class={'party-target' + (down[i] ? ' is-down' : '')} style={{ left: LANE_X(i) + 'px', '--c': COLOURS[i] }}>
          <Arrow lane={i} fill="rgba(255, 255, 255, 0.04)" stroke={down[i] ? COLOURS[i] : '#9a9aa6'} />
        </span>
      ))}
      {glow.map((g, i) => g > 0 && <span key={i + '-' + g} aria-hidden="true" class="party-glow" style={{ left: LANE_X(i) + 'px', '--c': COLOURS[i] }} />)}
      {onFoot
        ? <button type="button" class="party-lane-foot is-action" onClick={onFoot}>{foot}</button>
        : <div class="party-lane-foot">{foot}</div>}
    </section>
  );
}

/* ── The party ── */

export function Party({ onClose }: { onClose: () => void }) {
  const rootRef = useRef<HTMLDivElement>(null);
  const stageRef = useRef<HTMLDivElement>(null);
  const audioRef = useRef<HTMLAudioElement>(null);
  const [fit, setFit] = useState(() => measure());
  const [level, setLevel] = useState<Level>(() => { const l = safeLocal<string>(LEVEL_KEY, 'Light'); return LEVELS.includes(l as Level) ? l as Level : 'Light'; });
  const run = useRef(new Run(level));
  const clock = useRef(makeClock(() => audioRef.current)).current;
  const ctl = useRef<LaneCtl>({ press: () => {}, release: () => {} });
  const [phase, setPhase] = useState<Phase>('loading');
  const phaseRef = useRef(phase);
  phaseRef.current = phase;
  const [paused, setPaused] = useState(true);
  const [muted, setMuted] = useState(false);
  const [volume, setVolume] = useState(() => { const v = safeLocal<unknown>(VOLUME_KEY, VOLUME); return typeof v === 'number' && v >= 0 && v <= 1 ? v : VOLUME; });
  const [picking, setPicking] = useState(false);
  const resumeAfterPick = useRef(false);
  const levelRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const [hud, setHud] = useState<Hud>({ score: 0, combo: 0, lives: LIVES, judge: null, n: 0 });
  const [offer, setOffer] = useState(() => bootState.get() === 'ready' && readCustom(CUSTOM.disco) === null);
  const cardId = useId();

  const show = (judge: Judgment) => {
    const r = run.current;
    setHud((h) => ({ score: r.score, combo: r.combo, lives: r.lives, judge, n: h.n + 1 }));
  };

  const onMiss = useRef(() => {});
  onMiss.current = () => {
    show('miss');
    if (run.current.over) { audioRef.current?.pause(); setPhase('over'); }
  };

  const start = () => {
    const a = audioRef.current;
    if (!a) return;
    a.play().catch((e: unknown) => { if ((e as Error)?.name === 'NotAllowedError') setPhase('blocked'); });
  };

  const restart = (next: Level = run.current.level) => {
    run.current = new Run(next);
    setLevel(next);
    saveLocal(LEVEL_KEY, next);
    setHud({ score: 0, combo: 0, lives: LIVES, judge: null, n: 0 });
    const a = audioRef.current;
    if (!a) return;
    a.currentTime = 0;
    if (a.paused) { setPhase('loading'); start(); } else setPhase('playing');
  };

  const changeVolume = (v: number) => {
    const next = Math.round(Math.max(0, Math.min(1, v)) * 100) / 100;
    setVolume(next);
    setMuted(false);
    saveLocal(VOLUME_KEY, next);
  };
  // At 0 the speaker brings the sound back rather than muting silence.
  const toggleMute = () => (volume === 0 ? changeVolume(VOLUME) : setMuted((m) => !m));

  /* The difficulty menu holds the song while it's open; a new chart starts it again from the top. */
  const openPicker = () => {
    const a = audioRef.current;
    resumeAfterPick.current = !!a && !a.paused;
    a?.pause();
    setPicking(true);
  };
  const closePicker = (choice: Level | null, keyboard: boolean) => {
    setPicking(false);
    (keyboard ? levelRef.current : rootRef.current)?.focus({ preventScroll: true });
    const resume = resumeAfterPick.current;
    resumeAfterPick.current = false;
    if (choice && choice !== run.current.level) restart(choice);
    else if (resume) void audioRef.current?.play().catch(() => {});
  };

  /* What Enter (or the lane's foot) does: start the music the browser held back, or dance again. */
  const action = phase === 'blocked' ? start : phase === 'clear' || phase === 'over' ? () => restart() : undefined;

  const live = useRef({ action, onClose, press: (_lane: number, _synthetic: boolean) => {}, mute: () => {}, louder: (_up: boolean) => {}, picking, closePicker });
  live.current = {
    action,
    onClose,
    picking,
    closePicker,
    press: (lane, synthetic) => {
      const r = run.current;
      const j = phaseRef.current === 'playing' && !r.over ? r.hit(lane, clock()) : null;
      ctl.current.press(lane, !!j, synthetic);
      if (j) show(j);
    },
    mute: toggleMute,
    louder: (up) => changeVolume(up ? (muted ? 0 : volume) + 0.1 : volume - 0.1)
  };

  useLayoutEffect(() => {
    const onResize = () => setFit(measure());
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, []);

  useEffect(() => {
    const a = audioRef.current;
    if (!a) return;
    a.volume = volume;
    a.muted = muted;
  }, [volume, muted]);

  /* The song: play on open, follow its state, keep the dancers on the beat. */
  useEffect(() => {
    const a = audioRef.current!;
    // The dancers bounce twice a bar: line their CSS animations up with the song's position.
    const sync = () => {
      const ms = a.currentTime * 1000;
      stageRef.current?.querySelectorAll('.party-dance').forEach((el) => el.getAnimations().forEach((an) => { an.currentTime = ms; }));
    };
    const onPlaying = () => {
      setPaused(false);
      sync();
      if (phaseRef.current === 'loading' || phaseRef.current === 'blocked') setPhase('playing');
    };
    const onPause = () => setPaused(true);
    const onEnded = () => { if (!run.current.over) setPhase('clear'); };
    a.addEventListener('playing', onPlaying);
    a.addEventListener('seeked', sync);
    a.addEventListener('pause', onPause);
    a.addEventListener('ended', onEnded);
    start();

    // A hidden tab stops the music (and with it the notes) until it's back.
    let resume = false;
    const onVisible = () => {
      if (document.hidden) { resume = !a.paused; a.pause(); }
      else if (resume) { resume = false; void a.play().catch(() => {}); }
    };
    document.addEventListener('visibilitychange', onVisible);

    // Seedy's music steps aside and comes back after; the OS controls show the party's song meanwhile.
    const musicWas = audioStore.get().playing;
    if (musicWas) pauseMusic();
    holdSession();
    try { navigator.mediaSession.metadata = new MediaMetadata({ title: 'Smack Attack (Medu Mix)', artist: 'Medu', album: 'Medu Dance Revolution' }); } catch { /* old browsers */ }

    return () => {
      a.pause();
      a.removeEventListener('playing', onPlaying);
      a.removeEventListener('seeked', sync);
      a.removeEventListener('pause', onPause);
      a.removeEventListener('ended', onEnded);
      document.removeEventListener('visibilitychange', onVisible);
      releaseSession();
      if (musicWas) playMusic();
    };
  }, []);

  useEffect(() => {
    if (picking) menuRef.current?.querySelector<HTMLElement>('[aria-checked="true"]')?.focus({ preventScroll: true });
  }, [picking]);

  /* Keys: the arrows play, Esc (or a remote's Back) leaves, M mutes, − and + set the volume, Enter starts again.
     Nothing reaches the page. While the difficulty menu is open, the arrows move in it and Esc closes it. */
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null;
    rootRef.current?.focus({ preventScroll: true });
    const html = document.documentElement;
    const overflow = html.style.overflow;
    html.style.overflow = 'hidden';
    const onDown = (e: KeyboardEvent) => {
      e.stopPropagation();
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if (live.current.picking) {
        const opts = Array.from(rootRef.current?.querySelectorAll<HTMLElement>('.party-level-option') ?? []);
        const at = opts.indexOf(document.activeElement as HTMLElement);
        const go = (i: number) => { e.preventDefault(); opts[(i + opts.length) % opts.length]?.focus(); };
        if (e.key === 'ArrowDown') return go(at + 1);
        if (e.key === 'ArrowUp') return go(at - 1);
        if (e.key === 'Home') return go(0);
        if (e.key === 'End') return go(opts.length - 1);
        if (LEAVE.includes(e.key) || e.key === 'Tab') { e.preventDefault(); live.current.closePicker(null, true); return; }
        if (KEYS[e.key] !== undefined) { e.preventDefault(); return; }
      }
      const lane = KEYS[e.key];
      if (lane !== undefined) { e.preventDefault(); if (!e.repeat) live.current.press(lane, !e.isTrusted); return; }
      if (LEAVE.includes(e.key)) { e.preventDefault(); live.current.onClose(); return; }
      if (e.key === 'm' || e.key === 'M') { live.current.mute(); return; }
      if (e.key === '-' || e.key === '_' || e.key === '+' || e.key === '=') { e.preventDefault(); live.current.louder(e.key === '+' || e.key === '='); return; }
      const onButton = !!(e.target as Element | null)?.closest?.('button');
      if ((e.key === 'Enter' || e.key === ' ') && !onButton) { e.preventDefault(); live.current.action?.(); return; }
      if (e.key === 'Tab') {
        // Focus stays in the party.
        const els = Array.from(rootRef.current?.querySelectorAll<HTMLElement>('button:not([disabled]), input') ?? []);
        if (!els.length) return;
        e.preventDefault();
        const i = els.indexOf(document.activeElement as HTMLElement);
        els[e.shiftKey ? (i <= 0 ? els.length - 1 : i - 1) : (i + 1) % els.length].focus();
      }
    };
    const onUp = (e: KeyboardEvent) => {
      e.stopPropagation();
      const lane = KEYS[e.key];
      if (lane !== undefined) ctl.current.release(lane);
    };
    window.addEventListener('keydown', onDown, true);
    window.addEventListener('keyup', onUp, true);
    return () => {
      window.removeEventListener('keydown', onDown, true);
      window.removeEventListener('keyup', onUp, true);
      html.style.overflow = overflow;
      if (prev && prev !== document.body && prev.isConnected) prev.focus({ preventScroll: true });
    };
  }, []);

  const answer = (on: boolean) => {
    void saveCustom({ [CUSTOM.disco]: on ? 'on' : 'off' });
    setOffer(false);
    rootRef.current?.focus({ preventScroll: true });
  };

  const r = run.current;
  const ended = phase === 'clear' || phase === 'over';
  const judge = phase === 'over' ? { text: t('GAME OVER'), cls: 'is-miss is-final' }
    : phase === 'clear' ? { text: r.misses ? t('CLEARED!') : t('FULL COMBO!!'), cls: 'party-rainbow is-final' }
    : hud.judge === 'perfect' ? { text: t('PERFECT!!'), cls: 'party-rainbow' }
    : hud.judge === 'great' ? { text: t('GREAT!'), cls: 'is-great' }
    : hud.judge === 'good' ? { text: t('GOOD'), cls: 'is-good' }
    : hud.judge === 'miss' ? { text: t('MISS'), cls: 'is-miss' }
    : { text: t('READY?'), cls: 'is-ready' };
  const foot = phase === 'blocked' ? t('Press Enter to start the music')
    : ended ? t('Press Enter to dance again')
    : t('Use ← ↓ ↑ → on the keyboard, or the remote’s arrows');
  const heard = muted ? 0 : volume;
  const lifeIcons = Math.min(hud.lives, 5);

  return (
    <div
      ref={rootRef}
      class={'party' + (paused ? ' is-paused' : '')}
      role="dialog"
      aria-modal="true"
      aria-label="Medu Dance Revolution"
      tabIndex={-1}
      onPointerDown={(e) => { if (picking && !(e.target as Element).closest('.party-level-wrap')) closePicker(null, false); }}
    >
      <audio ref={audioRef} src={song} preload="auto" />
      <div ref={stageRef} class="party-stage" style={{ width: fit.w + 'px', height: fit.h + 'px', transform: `scale(${fit.s})`, '--party-beat': `${BEAT}s` }}>
        <Backdrop />

        <div class="party-top">
          <div role="status" class="party-code">
            <div aria-hidden="true" class="party-keys">
              {['↑', '↑', '↓', '↓', '←', '→', '←', '→', 'B', 'A'].map((k, i) => <kbd key={i}>{k}</kbd>)}
            </div>
            <span>{t('Code accepted')}</span>
          </div>
          <div class="party-actions">
            <div class="party-song">
              <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true"><path d="M9 18V5l11-2v13" /><path d="M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0z" /><path d="M20 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0z" /></svg>
              <span>Smack Attack <span class="party-bpm">· {BPM} BPM</span></span>
              <button type="button" class={'party-mute' + (heard === 0 ? ' is-muted' : '')} aria-label={heard === 0 ? t('Unmute') : t('Mute')} title={(heard === 0 ? t('Unmute') : t('Mute')) + ' (M)'} onClick={toggleMute}>
                <Icon name={volumeIcon(volume, muted)} size={18} />
              </button>
              <span class="party-volume" title={t('Volume') + ' (− +)'}>
                <span aria-hidden="true" class="party-volume-track">
                  <span class="party-volume-fill" style={{ width: heard * 100 + '%' }} />
                  <span class="party-volume-thumb" style={{ left: heard * 100 + '%' }} />
                </span>
                <input type="range" min={0} max={100} step={5} value={Math.round(heard * 100)} aria-label={t('Volume')} aria-valuetext={t('Volume {n}%', { n: Math.round(heard * 100) })}
                  onInput={(e) => changeVolume(Number((e.target as HTMLInputElement).value) / 100)} />
              </span>
            </div>
            <button type="button" class="party-leave" onClick={onClose}>{t('Leave the party')}<kbd>Esc</kbd></button>
          </div>
        </div>

        <div class="party-title">
          <h1>
            <span aria-hidden="true" class="party-title-shadow">MEDU DANCE REVOLUTION</span>
            <span class="party-rainbow">MEDU DANCE REVOLUTION</span>
          </h1>
          <p>{t('A group of jellyfish is called a smack. This one’s yours.')}</p>
        </div>

        <div class="party-dancer" style={{ left: 'calc(50% - 132px)', top: '318px', width: '264px' }}>
          <span aria-hidden="true" class="party-shadow" style={{ height: '33px' }} />
          <div class="party-dance"><Medu mood={phase === 'over' ? 'oops' : 'party'} size={264} glow={false} /></div>
        </div>
        {DANCERS.map(([x, top, size, mood, hue, late]) => (
          <div key={x} class="party-dancer" style={{ left: `calc(50% + ${x}px)`, top: top + 'px', width: size + 'px' }}>
            <span aria-hidden="true" class="party-shadow" style={{ height: Math.round(size / 8) + 'px' }} />
            <div class={'party-dance' + (late ? ' is-late' : '')} style={{ filter: `hue-rotate(${hue}deg) saturate(1.4)` }}>
              <Medu mood={mood} size={size} glow={false} />
            </div>
          </div>
        ))}

        <LanePanel run={run} clock={clock} ctl={ctl} onMiss={onMiss} foot={foot} onFoot={action} />

        <section aria-label={t('Score')} class="party-score">
          <div class="party-card party-points">
            <span class="party-label">{t('SCORE')}</span>
            <span class="party-number">{formatScore(hud.score)}</span>
          </div>
          <div class="party-card party-judge-card" aria-live="polite">
            <span key={hud.n + phase} class={'party-judge ' + judge.cls}>{judge.text}</span>
            <span class="party-combo">
              {ended ? r.maxCombo : hud.combo} <span>{ended ? t('MAX COMBO') : t('COMBO')}</span>
            </span>
          </div>
          <div class="party-card party-lives">
            <div class="party-lives-head">
              <span class="party-label">{t('LIVES')}</span>
              <span class="party-tradition">{t('+30, as tradition demands')}</span>
            </div>
            <div class="party-lives-row">
              {Array.from({ length: lifeIcons }, (_, i) => <Medu key={i} size={30} />)}
              <span class="party-lives-count">×{hud.lives}</span>
            </div>
          </div>
          <div class="party-level-wrap">
            <button ref={levelRef} type="button" class="party-level" aria-haspopup="menu" aria-expanded={picking} title={t('Change the difficulty (the song starts again)')}
              onClick={(e) => (picking ? closePicker(null, e.detail === 0) : openPicker())}>
              <span>{t('Difficulty')}</span>
              {/* The charts' own names, like a rhythm game's levels. */}
              <span class="party-level-name">{level}<Icon name="chevronDown" size={14} /></span>
            </button>
            {picking && (
              <div ref={menuRef} role="menu" aria-label={t('Difficulty')} class="party-levels">
                {LEVELS.map((l, i) => (
                  <button key={l} type="button" role="menuitemradio" aria-checked={l === level} class="party-level-option" style={{ '--c': LEVEL_COLOURS[l] }}
                    onClick={(e) => closePicker(l, e.detail === 0)}>
                    <span aria-hidden="true" class="party-pips">{LEVELS.map((_, k) => <span key={k} class={k <= i ? 'is-on' : undefined} />)}</span>
                    <span class="party-level-option-name">{l}</span>
                    <span class="party-level-steps">{tn(stepCount(l), '{n} step', '{n} steps')}</span>
                  </button>
                ))}
              </div>
            )}
          </div>
        </section>

        {offer && (
          <section aria-labelledby={cardId} class="party-unlock">
            <span aria-hidden="true" class="party-disco" />
            <div class="party-unlock-text">
              <span class="party-unlock-kicker">{t('Unlocked')}</span>
              <h2 id={cardId}>{t('Disco accent')}</h2>
              <span>{t('Your accent slowly cycles through every colour. Switch it off any time in Settings.')}</span>
            </div>
            <div class="party-unlock-actions">
              <button type="button" class="party-keep" onClick={() => answer(true)}>{t('Keep it')}</button>
              <button type="button" class="party-nope" onClick={() => answer(false)}>{t('No thanks')}</button>
            </div>
          </section>
        )}
      </div>
    </div>
  );
}

/* The board's 1440 × 900, scaled to fit; the stage then covers the window in board pixels. */
function measure() {
  const s = Math.min(window.innerWidth / BOARD_W, window.innerHeight / BOARD_H);
  return { s, w: window.innerWidth / s, h: window.innerHeight / s };
}
