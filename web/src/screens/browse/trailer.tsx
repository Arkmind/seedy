import type { RefObject } from 'preact';
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { SERVER_URL, getSession } from '../../api/client';
import { getTrailer, type TrailerInfo } from '../../api/seedy';
import { createStore, useStore } from '../../lib/store';
import { saverOpen } from '../me/effects';
import { CUSTOM, readCustom } from '../me/prefs';
import { t } from '../../lib/i18n';

/* Trailers behind the Home carousel: muted, faded in over the backdrop once they really play, paused whenever
   nobody can see them. A local trailer file plays in a <video>; YouTube trailers in a youtube-nocookie iframe driven
   through the IFrame API's postMessage protocol (no extra script). */

/* The trailer loads, hidden, once the slide has been up this long… */
const PRELOAD_MS = 600;
/* …and never shows before the slide has been on screen for this long. */
const DWELL_MS = 1800;
/* YouTube shows its play/pause glyph in the middle of the picture for the first seconds of every run (start, resume),
   even without controls. The trailer stays hidden until that has gone. */
const YT_CHROME_S = 4.3;
const FADE_MS = 700;
/* Scrolled away from the banner: the trailer goes quiet but keeps playing, so it's still there on the way back
   (pausing would cost YouTube's hidden seconds again). Only after this long away does it pause. */
const AWAY_PAUSE_MS = 60_000;
const START_TIMEOUT_MS = 12000;
/* Leave this long before the end, so YouTube's end screen never shows. */
const END_EARLY_S = 1.2;
const YT_ORIGIN = 'https://www.youtube-nocookie.com';
const YT_ORIGINS = /^https:\/\/www\.youtube(-nocookie)?\.com$/;

/* ── Whether trailers may play by themselves ── */

type NetInfo = EventTarget & { saveData?: boolean };
const connection = (): NetInfo | undefined => (navigator as Navigator & { connection?: NetInfo }).connection;

function motionReduced(): boolean {
  try { return window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch { return false; }
}

/* Off with reduced motion, Data Saver, or Settings · Display › Backdrops off. Follows changes while the page is open. */
export function useTrailersAllowed(): boolean {
  const [env, setEnv] = useState(() => !motionReduced() && !connection()?.saveData);
  useEffect(() => {
    const update = () => setEnv(!motionReduced() && !connection()?.saveData);
    let mq: MediaQueryList | undefined;
    try { mq = window.matchMedia('(prefers-reduced-motion: reduce)'); mq.addEventListener('change', update); } catch { /* old browser */ }
    const conn = connection();
    conn?.addEventListener?.('change', update);
    return () => { mq?.removeEventListener('change', update); conn?.removeEventListener?.('change', update); };
  }, []);
  return env && !!readCustom(CUSTOM.backdrops);
}

/* ── Lookups, cached for the session ── */

const lookups = new Map<string, Promise<TrailerInfo | null>>();
/* Titles whose trailers couldn't play here (embedding off, file unplayable): not tried again this session. */
const broken = new Set<string>();

export function trailerFor(itemId: string): Promise<TrailerInfo | null> {
  let p = lookups.get(itemId);
  if (!p) {
    p = getTrailer(itemId).then((r) => r ?? null, () => null);
    lookups.set(itemId, p);
  }
  return p;
}

/* ── Sound: off until the person turns it on; the choice lasts for the session ── */

const SOUND_KEY = 'seedy.trailerSound';
function readSound(): boolean {
  try { return window.sessionStorage.getItem(SOUND_KEY) === '1'; } catch { return false; }
}
export const trailerSound = createStore<boolean>(readSound());
trailerSound.subscribe((on) => { try { window.sessionStorage.setItem(SOUND_KEY, on ? '1' : '0'); } catch { /* private mode */ } });

/* ── Visibility helpers ── */

function usePageVisible(): boolean {
  const [v, setV] = useState(() => document.visibilityState !== 'hidden');
  useEffect(() => {
    const on = () => setV(document.visibilityState !== 'hidden');
    document.addEventListener('visibilitychange', on);
    return () => document.removeEventListener('visibilitychange', on);
  }, []);
  return v;
}

/* Runs `attach` for the element behind `ref` whenever that element changes (it can mount after the hook's first run). */
function useAttach(ref: RefObject<Element>, attach: (el: Element) => () => void) {
  const current = useRef<{ el: Element | null; detach?: () => void }>({ el: null });
  useEffect(() => {
    const el = ref.current;
    if (el === current.current.el) return;
    current.current.detach?.();
    current.current = { el, detach: el ? attach(el) : undefined };
  });
  useEffect(() => () => current.current.detach?.(), []);
}

/* True while at least `ratio` of the element is on screen. */
function useOnScreen(ref: RefObject<Element>, ratio: number): boolean {
  const [on, setOn] = useState(true);
  useAttach(ref, (el) => {
    if (typeof IntersectionObserver === 'undefined') return () => undefined;
    const io = new IntersectionObserver((entries) => {
      const e = entries[entries.length - 1];
      setOn(e.isIntersecting && e.intersectionRatio >= ratio);
    }, { threshold: [0, ratio, 1] });
    io.observe(el);
    return () => io.disconnect();
  });
  return on;
}

/* ── The carousel's trailer, one slide at a time ── */

/* started: it plays (still hidden). visible / hidden: show it or not. */
export type TrailerEvent = 'started' | 'visible' | 'hidden' | 'ended' | 'failed' | 'soundBlocked';

export interface TrailerStage {
  id: string;
  info: TrailerInfo;
  /* Playing (maybe still hidden): the carousel waits for it. */
  started: boolean;
  /* On screen. */
  live: boolean;
  /* Fading out before it goes. */
  leaving: boolean;
}

export interface HeroTrailer {
  /* Trailers may play here at all (the sound button keeps its place while they may). */
  allowed: boolean;
  /* A trailer is on screen. */
  live: boolean;
  /* A trailer is playing or about to show: hold the carousel. */
  holding: boolean;
  stage: TrailerStage | null;
  /* The person's sound choice (the button)… */
  sound: boolean;
  /* …and whether the trailer is heard right now: only while the banner is on screen. */
  audible: boolean;
  paused: boolean;
  toggleSound: () => void;
  /* Something else is about to play: fade the trailer out and don't start another this visit. */
  stop: () => void;
  onEvent: (id: string, e: TrailerEvent) => void;
}

export function useHeroTrailer({ itemId, heroRef, onEnded }: { itemId?: string; heroRef: RefObject<HTMLElement>; onEnded: () => void }): HeroTrailer {
  const allowed = useTrailersAllowed();
  const onScreen = useOnScreen(heroRef, 0.4);
  const pageVisible = usePageVisible();
  const saver = useStore(saverOpen);
  const sound = useStore(trailerSound);
  const [stage, setStage] = useState<TrailerStage | null>(null);
  const [stopped, setStopped] = useState(false);
  const stageRef = useRef(stage);
  stageRef.current = stage;
  /* Played to the end this visit: the slide keeps its backdrop when it comes round again. */
  const done = useRef(new Set<string>());
  const slideAt = useRef({ id: itemId, at: Date.now() });
  if (slideAt.current.id !== itemId) slideAt.current = { id: itemId, at: Date.now() };
  const showTimer = useRef(0);
  const endedRef = useRef(onEnded);
  endedRef.current = onEnded;

  const enabled = allowed && !stopped;
  const watchable = onScreen && pageVisible && !saver;
  const [awayLong, setAwayLong] = useState(false);
  useEffect(() => {
    if (onScreen) { setAwayLong(false); return; }
    const t = window.setTimeout(() => setAwayLong(true), AWAY_PAUSE_MS);
    return () => window.clearTimeout(t);
  }, [onScreen]);

  const leave = () => {
    window.clearTimeout(showTimer.current);
    setStage((s) => (s && !s.leaving ? { ...s, live: false, leaving: true } : s));
  };

  // A new slide: the old trailer fades out.
  useEffect(() => {
    if (stageRef.current && stageRef.current.id !== itemId) leave();
  }, [itemId]);

  // Trailers switched off while one plays (reduced motion, Data Saver, Backdrops off).
  useEffect(() => { if (!enabled) leave(); }, [enabled]);

  // Gone once faded.
  useEffect(() => {
    if (!stage?.leaving) return;
    const t = window.setTimeout(() => setStage((s) => (s && s.leaving ? null : s)), FADE_MS);
    return () => window.clearTimeout(t);
  }, [stage?.leaving, stage?.id]);

  useEffect(() => () => window.clearTimeout(showTimer.current), []);

  // Once the slide has been up for a moment, look up its trailer and start it, hidden until it really plays.
  useEffect(() => {
    if (!enabled || !watchable || !itemId || done.current.has(itemId) || broken.has(itemId)) return;
    const cur = stageRef.current;
    if (cur && cur.id === itemId && !cur.leaving) return;
    let alive = true;
    const t = window.setTimeout(() => {
      trailerFor(itemId).then((info) => {
        if (!alive) return;
        if (!info) { done.current.add(itemId); return; }
        setStage((s) => (s && s.id === itemId && !s.leaving ? s : { id: itemId, info, started: false, live: false, leaving: false }));
      });
    }, PRELOAD_MS);
    return () => { alive = false; window.clearTimeout(t); };
  }, [itemId, enabled, watchable]);

  const stop = () => { setStopped(true); leave(); };

  // Anything else that starts playing on the page takes over.
  useEffect(() => {
    const onPlay = (e: Event) => {
      const t = e.target;
      if (t instanceof HTMLMediaElement && !t.closest('.hero-trailer')) stop();
    };
    document.addEventListener('play', onPlay, true);
    return () => document.removeEventListener('play', onPlay, true);
  }, []);

  const patch = (id: string, p: Partial<TrailerStage>) =>
    setStage((s) => (s && s.id === id && !s.leaving ? { ...s, ...p } : s));

  const onEvent = (id: string, e: TrailerEvent) => {
    if (e === 'soundBlocked') { trailerSound.set(false); return; }
    const cur = stageRef.current;
    if (!cur || cur.id !== id || cur.leaving) return;
    switch (e) {
      case 'started': patch(id, { started: true }); return;
      case 'visible': {
        // Not before the slide has had its moment.
        window.clearTimeout(showTimer.current);
        const wait = slideAt.current.id === id ? DWELL_MS - (Date.now() - slideAt.current.at) : 0;
        if (wait > 0) showTimer.current = window.setTimeout(() => patch(id, { live: true }), wait);
        else patch(id, { live: true });
        return;
      }
      case 'hidden': window.clearTimeout(showTimer.current); patch(id, { live: false }); return;
      case 'failed': broken.add(id); break;
    }
    done.current.add(id);
    leave();
    if (e === 'ended') endedRef.current();
  };

  return {
    allowed: enabled,
    live: !!stage?.live,
    holding: !!stage && stage.started && !stage.leaving,
    stage,
    sound,
    audible: sound && watchable,
    paused: !pageVisible || saver || awayLong,
    toggleSound: () => trailerSound.set((on) => !on),
    stop,
    onEvent
  };
}

/* The layer between the backdrop and the hero's scrims. Inert and hidden from assistive tech: it's decoration. */
export function TrailerLayer({ trailer }: { trailer: HeroTrailer }) {
  const ref = useRef<HTMLDivElement>(null);
  const box = useCoverBox(ref);
  const s = trailer.stage;
  if (!s) return null;
  return (
    <div ref={ref} class={'hero-trailer' + (s.live ? ' is-on' : '')} aria-hidden="true" inert>
      {box && (
        <TrailerPlayer
          key={s.id}
          info={s.info}
          box={box}
          // A trailer on its way out goes quiet at once and plays out its fade.
          sound={trailer.audible && !s.leaving}
          paused={trailer.paused && !s.leaving}
          onEvent={(e) => trailer.onEvent(s.id, e)}
        />
      )}
    </div>
  );
}

/* The 16:9 frame, sized to cover the hero whatever the trailer's shape:
   - letterboxed 2.39:1 trailers still fill its height (width ≥ 2.39 × height);
   - pillarboxed 4:3 trailers fill it to the right edge: the frame shifts right so their left bar falls under the
     hero's dark left-hand scrim (at most a fifth of the width);
   - and 3 % extra, so YouTube's edges never show. */
type CoverBox = { w: number; h: number; shift: number };
function useCoverBox(ref: RefObject<HTMLElement>): CoverBox | null {
  const [box, setBox] = useState<CoverBox | null>(null);
  useAttach(ref, (el) => {
    const measure = () => {
      const r = el.getBoundingClientRect();
      if (!r.width || !r.height) return;
      const w = Math.ceil(Math.max(r.width * 1.0667, r.height * 2.39) * 1.03);
      const shift = Math.max(0, Math.round(r.width / 2 - w * 0.375));
      setBox((b) => (b && b.w === w && b.shift === shift ? b : { w, h: Math.ceil((w * 9) / 16), shift }));
    };
    measure();
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', measure);
      return () => window.removeEventListener('resize', measure);
    }
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  });
  return box;
}

const boxStyle = (b: CoverBox) => ({ width: b.w + 'px', height: b.h + 'px', marginLeft: b.shift + 'px' });

/* ── Players ── */

type Candidate = { kind: 'local'; itemId: string } | { kind: 'youtube'; key: string };
type PlayerEvent = Exclude<TrailerEvent, 'failed'> | 'error';

interface PlayerProps { sound: boolean; paused: boolean; box: CoverBox }

/* Tries the trailer, then its YouTube alternates, until one plays. */
function TrailerPlayer({ info, onEvent, ...p }: PlayerProps & { info: TrailerInfo; onEvent: (e: TrailerEvent) => void }) {
  const candidates = useMemo<Candidate[]>(() => {
    const list: Candidate[] = info.source === 'local' ? [{ kind: 'local', itemId: info.itemId }] : [{ kind: 'youtube', key: info.key }];
    for (const a of info.alternates ?? []) if (a.key && !list.some((c) => c.kind === 'youtube' && c.key === a.key)) list.push({ kind: 'youtube', key: a.key });
    return list.slice(0, 4);
  }, [info]);
  const [at, setAt] = useState(0);
  const shown = useRef(false);
  const cand = candidates[at];
  const relay = (e: PlayerEvent) => {
    if (e === 'visible') shown.current = true;
    if (e !== 'error') { onEvent(e); return; }
    // Before anything showed, try the next trailer; once one was on screen, just bow out.
    if (!shown.current && at + 1 < candidates.length) setAt(at + 1);
    else onEvent('failed');
  };
  if (!cand) return null;
  return cand.kind === 'local'
    ? <LocalTrailer key={'l' + cand.itemId} itemId={cand.itemId} {...p} onEvent={relay} />
    : <YouTubeTrailer key={'y' + cand.key} videoKey={cand.key} {...p} onEvent={relay} />;
}

type InnerProps = PlayerProps & { onEvent: (e: PlayerEvent) => void };

/* Gives up when nothing has played after a while on screen (blocked, stalled). */
function useStartTimeout(started: boolean, paused: boolean, onEvent: (e: 'error') => void) {
  const cb = useRef(onEvent);
  cb.current = onEvent;
  useEffect(() => {
    if (started || paused) return;
    const t = window.setTimeout(() => cb.current('error'), START_TIMEOUT_MS);
    return () => window.clearTimeout(t);
  }, [started, paused]);
}

let playerSeq = 0;

function YouTubeTrailer({ videoKey, sound, paused, box, onEvent }: InnerProps & { videoKey: string }) {
  const ref = useRef<HTMLIFrameElement>(null);
  const id = useMemo(() => ++playerSeq, []);
  const [started, setStarted] = useState(false);
  const live = useRef({ sound, paused, onEvent });
  live.current = { sound, paused, onEvent };
  const st = useRef({
    ready: false, started: false, visible: false, finished: false, muted: true, pausing: false, unmutedAt: 0,
    playing: false, runFrom: -1, duration: 0
  });

  const src = useMemo(() => {
    const q = new URLSearchParams({
      autoplay: '1', mute: '1', controls: '0', playsinline: '1', rel: '0', iv_load_policy: '3', disablekb: '1',
      modestbranding: '1', fs: '0', cc_load_policy: '0', enablejsapi: '1', origin: window.location.origin
    });
    return `${YT_ORIGIN}/embed/${encodeURIComponent(videoKey)}?${q.toString()}`;
  }, [videoKey]);

  const send = (func: string, args: unknown[] = []) => {
    ref.current?.contentWindow?.postMessage(JSON.stringify({ event: 'command', func, args, id, channel: 'widget' }), YT_ORIGIN);
  };

  const applySound = () => {
    const s = st.current;
    if (!s.ready) return;
    if (live.current.sound && s.muted) { send('unMute'); send('setVolume', [100]); s.muted = false; s.unmutedAt = Date.now(); }
    else if (!live.current.sound && !s.muted) { send('mute'); s.muted = true; }
  };
  const setVisible = (on: boolean) => {
    const s = st.current;
    if (s.visible === on) return;
    s.visible = on;
    live.current.onEvent(on ? 'visible' : 'hidden');
  };
  const applyPause = () => {
    const s = st.current;
    if (!s.ready || s.finished) return;
    if (live.current.paused) { s.pausing = true; setVisible(false); send('pauseVideo'); }
    else { s.pausing = false; send('playVideo'); }
  };

  useEffect(() => {
    const frame = ref.current;
    if (!frame) return;
    let handshake = 0;
    const s = st.current;
    const finish = (e: 'ended' | 'error') => {
      if (s.finished) return;
      s.finished = true;
      live.current.onEvent(e);
    };
    const listen = () => frame.contentWindow?.postMessage(JSON.stringify({ event: 'listening', id, channel: 'widget' }), YT_ORIGIN);
    const onLoad = () => {
      listen();
      window.clearInterval(handshake);
      handshake = window.setInterval(() => (s.ready ? window.clearInterval(handshake) : listen()), 250);
    };
    const onState = (state: number) => {
      if (state === 1) {
        if (live.current.paused) { applyPause(); return; }
        if (!s.playing) { s.playing = true; s.runFrom = -1; }
        if (!s.started) { s.started = true; setStarted(true); live.current.onEvent('started'); }
        applySound();
      } else if (state === 2 || state === 0) {
        s.playing = false;
        if (state === 0) { finish('ended'); return; }
        if (!s.pausing && !live.current.paused && !s.finished) {
          // Paused by the browser, not by us: unmuting without a gesture can do that. Play on, muted.
          if (!s.muted || Date.now() - s.unmutedAt < 2000) {
            send('mute'); s.muted = true;
            live.current.onEvent('soundBlocked');
          }
          setVisible(false);
          send('playVideo');
        }
      }
    };
    const onTime = (t: number) => {
      if (!s.playing || !(t >= 0)) return;
      // Every run (start, resume) begins with YouTube's glyph in the middle: show once it has gone.
      if (s.runFrom < 0) s.runFrom = t;
      if (!s.visible && t - s.runFrom >= YT_CHROME_S) setVisible(true);
      if (s.visible && s.duration > 0 && s.duration - t < END_EARLY_S) finish('ended');
    };
    const onMessage = (e: MessageEvent) => {
      if (e.source !== frame.contentWindow || !YT_ORIGINS.test(e.origin)) return;
      let data: { event?: string; info?: unknown };
      try { data = typeof e.data === 'string' ? JSON.parse(e.data) : e.data; } catch { return; }
      if (!data || typeof data !== 'object') return;
      const info = data.info as Record<string, unknown> | number | null | undefined;
      switch (data.event) {
        case 'onReady':
        case 'initialDelivery':
          if (!s.ready) {
            s.ready = true;
            window.clearInterval(handshake);
            send('addEventListener', ['onStateChange']);
            send('addEventListener', ['onError']);
            send('mute');
            applyPause();
          }
          if (info && typeof info === 'object' && typeof info.playerState === 'number') onState(info.playerState);
          break;
        case 'onStateChange':
          if (typeof info === 'number') onState(info);
          break;
        case 'infoDelivery':
          if (info && typeof info === 'object') {
            if (typeof info.playerState === 'number') onState(info.playerState);
            if (typeof info.muted === 'boolean') s.muted = info.muted;
            if (Number(info.duration) > 0) s.duration = Number(info.duration);
            if (info.currentTime !== undefined) onTime(Number(info.currentTime));
          }
          break;
        case 'onError':
          finish('error');
          break;
      }
    };
    frame.addEventListener('load', onLoad);
    window.addEventListener('message', onMessage);
    return () => {
      frame.removeEventListener('load', onLoad);
      window.removeEventListener('message', onMessage);
      window.clearInterval(handshake);
    };
  }, []);

  useEffect(applySound, [sound]);
  useEffect(applyPause, [paused]);
  useStartTimeout(started, paused, onEvent);

  return (
    <iframe
      ref={ref}
      class="hero-trailer-media"
      src={src}
      title={t('Trailer')}
      tabIndex={-1}
      allow="autoplay; encrypted-media; picture-in-picture"
      referrerpolicy="strict-origin-when-cross-origin"
      style={boxStyle(box)}
    />
  );
}

function LocalTrailer({ itemId, sound, paused, box, onEvent }: InnerProps & { itemId: string }) {
  const ref = useRef<HTMLVideoElement>(null);
  const [started, setStarted] = useState(false);
  const live = useRef({ sound, paused, onEvent });
  live.current = { sound, paused, onEvent };
  const st = useRef({ started: false, finished: false, pausing: false });

  const src = useMemo(() => {
    const q = new URLSearchParams({ static: 'true' });
    const token = getSession()?.token;
    if (token) q.set('ApiKey', token);
    return `${SERVER_URL}/Videos/${itemId}/stream?${q.toString()}`;
  }, [itemId]);

  const finish = (e: 'ended' | 'error') => {
    if (st.current.finished) return;
    st.current.finished = true;
    live.current.onEvent(e);
  };

  const play = () => {
    const v = ref.current;
    if (!v || st.current.finished) return;
    st.current.pausing = false;
    v.play().catch((err: Error) => {
      if (err?.name === 'AbortError') return;
      // Sound not allowed without a gesture: play muted instead.
      if (!v.muted) { v.muted = true; live.current.onEvent('soundBlocked'); v.play().catch(() => finish('error')); }
      else finish('error');
    });
  };

  useEffect(() => {
    const v = ref.current;
    if (v) v.muted = !live.current.sound;
  }, [sound]);

  useEffect(() => {
    const v = ref.current;
    if (!v) return;
    if (paused) { st.current.pausing = true; v.pause(); } else if (v.readyState >= 2) play();
  }, [paused]);

  useStartTimeout(started, paused, onEvent);

  return (
    <video
      ref={ref}
      class="hero-trailer-media"
      src={src}
      muted
      playsInline
      preload="auto"
      disablePictureInPicture
      tabIndex={-1}
      style={boxStyle(box)}
      onLoadedData={() => { if (!live.current.paused) play(); }}
      onTimeUpdate={(e) => {
        const v = e.currentTarget;
        // Shown on the first real frame, not on "play" alone, so a stalled start never shows black.
        if (!st.current.started && v.currentTime > 0.15) {
          st.current.started = true;
          setStarted(true);
          live.current.onEvent('started');
          live.current.onEvent('visible');
        }
        if (st.current.started && v.duration > 0 && v.duration - v.currentTime < END_EARLY_S) finish('ended');
      }}
      onPause={(e) => {
        const v = e.currentTarget;
        // Paused by the browser after unmuting: carry on muted.
        if (!st.current.pausing && !live.current.paused && !v.ended && !st.current.finished && !v.muted) {
          v.muted = true;
          live.current.onEvent('soundBlocked');
          play();
        }
      }}
      onEnded={() => finish('ended')}
      onError={() => finish('error')}
    />
  );
}
