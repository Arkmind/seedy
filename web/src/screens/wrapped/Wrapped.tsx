import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { back, Link, navigate } from '../../lib/router';
import { t } from '../../lib/i18n';
import { useTitle } from '../../lib/title';
import { BrandMark } from '../../ui/navbar/Header';
import { MeduState } from '../../ui/medu';
import { toast } from '../../ui/overlay';
import { useStore } from '../../lib/store';
import { brandingStore } from '../../lib/app-state';
import { getWrapped, getWrappedStatus, storiesOf, type StoryKey, type Wrapped as WrappedData } from './api';
import { Story, SummaryCard } from './stories';
import { picture, savePicture, sharePicture } from './share';
import './wrapped.css';

/* Seedy Wrapped at /wrapped/:year (board Web · Wrapped viewer): the stories one after another, 8 s each. On a wide
   screen the current story sits between its neighbours with arrows and key hints; on a phone it fills the screen.
   Tap the left third to go back, anywhere else to go on; hold to pause. */

const STORY_H = 844;
const HOLD_MS = 250;

function useViewport() {
  const read = () => ({ w: window.innerWidth, h: window.innerHeight });
  const [size, setSize] = useState(read);
  useEffect(() => {
    const on = () => setSize(read());
    window.addEventListener('resize', on);
    return () => window.removeEventListener('resize', on);
  }, []);
  return size;
}

export function WrappedScreen({ year }: { year?: string }) {
  const [data, setData] = useState<WrappedData | null>(null);
  const [state, setState] = useState<'loading' | 'ready' | 'none'>('loading');
  const wanted = Number(year);

  useEffect(() => {
    let live = true;
    setState('loading');
    (async () => {
      try {
        if (!wanted) {
          const s = await getWrappedStatus();
          const latest = s.current?.year ?? s.years[0];
          if (!live) return;
          if (latest) navigate(`/wrapped/${latest}`, { replace: true });
          else setState('none');
          return;
        }
        const w = await getWrapped(wanted);
        if (!live) return;
        setData(w);
        setState(storiesOf(w).length ? 'ready' : 'none');
      } catch {
        if (live) setState('none');
      }
    })();
    return () => { live = false; };
  }, [wanted]);

  useTitle(t('Seedy Wrapped {year}', { year: wanted || '' }).trim());

  if (state === 'loading') return <div class="wr-page"><div class="boot"><div class="spinner" /></div></div>;
  if (state === 'none' || !data) {
    return (
      <div class="wr-page">
        <div class="wr-empty">
          <MeduState
            mood="sleepy"
            title={t('Your Wrapped isn’t ready')}
            text={t('Seedy Wrapped arrives every December, once you’ve watched a few things.')}
            actions={<Link to="/" class="btn btn-primary">{t('Back to Seedy')}</Link>}
          />
        </div>
      </div>
    );
  }
  return <Viewer w={data} />;
}

function Viewer({ w }: { w: WrappedData }) {
  const stories = useMemo(() => storiesOf(w), [w]);
  const total = stories.length;
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const [held, setHeld] = useState(false);
  const [busy, setBusy] = useState(false);
  const [run, setRun] = useState(0);
  const vp = useViewport();
  const brand = useStore(brandingStore).name || 'Seedy';
  const storyEl = useRef<HTMLDivElement>(null);
  const cardEl = useRef<HTMLElement | null>(null);
  const holdTimer = useRef(0);
  const holding = useRef(false);

  const wide = vp.w >= 900 && vp.h >= 560;
  // Wide: the board's 0.88 at 900 px tall, smaller when the window is; neighbours at 0.7 of that board's size.
  const scale = wide
    ? Math.max(0.45, Math.min(0.88, (vp.h - 157) / STORY_H, (vp.w - 280) / 1010))
    : Math.min(vp.w / 390, vp.h / 700);
  const side = scale * (0.7 / 0.88);
  const height = wide ? STORY_H : Math.round(vp.h / scale);

  const go = (to: number) => {
    if (to < 0 || to >= total) return;
    setIndex(to);
    setPaused(false);
    setRun((r) => r + 1);
  };
  const next = () => go(index + 1);
  const prev = () => (index > 0 ? go(index - 1) : setRun((r) => r + 1));
  const close = () => back('/');

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.altKey || e.ctrlKey || e.metaKey) return;
      if (e.key === 'ArrowRight') { e.preventDefault(); next(); }
      else if (e.key === 'ArrowLeft') { e.preventDefault(); prev(); }
      else if (e.key === ' ') { e.preventDefault(); setPaused((p) => !p); }
      else if (e.key === 'Escape') { e.preventDefault(); close(); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  // A hidden tab holds the story where it was.
  useEffect(() => {
    const on = () => { if (document.hidden) setPaused(true); };
    document.addEventListener('visibilitychange', on);
    return () => document.removeEventListener('visibilitychange', on);
  }, []);

  const fileName = (what: string) => `seedy-wrapped-${w.year}-${what}.png`;
  const title = t('Seedy Wrapped {year}', { year: w.year });

  async function shoot(el: HTMLElement | null | undefined, name: string, save: boolean) {
    if (!el || busy) return;
    setBusy(true);
    const was = paused;
    setPaused(true);
    try {
      const blob = await picture(el);
      if (save) {
        savePicture(blob, name);
        toast(t('Picture saved'));
      } else if ((await sharePicture(blob, name, title)) === 'saved') {
        toast(t('Picture saved'));
      }
    } catch {
      toast(t('Couldn’t make the picture'), 'error');
    } finally {
      setBusy(false);
      setPaused(was);
    }
  }

  const shareStory = () => shoot(storyEl.current?.querySelector<HTMLElement>('.wr-story'), fileName(stories[index]), false);
  const shareCard = () => shoot(cardEl.current, fileName('summary'), false);
  const saveCard = () => shoot(cardEl.current, fileName('summary'), true);

  // Tap zones: a short press moves, a long one pauses until it's let go.
  const press = (dir: -1 | 1) => ({
    onPointerDown: (e: PointerEvent) => {
      if (e.button !== 0) return;
      holding.current = false;
      window.clearTimeout(holdTimer.current);
      holdTimer.current = window.setTimeout(() => { holding.current = true; setHeld(true); }, HOLD_MS);
    },
    onPointerUp: () => {
      window.clearTimeout(holdTimer.current);
      if (holding.current) { holding.current = false; setHeld(false); return; }
      if (dir > 0) next(); else prev();
    },
    onPointerCancel: () => { window.clearTimeout(holdTimer.current); holding.current = false; setHeld(false); },
    onPointerLeave: () => { if (holding.current) { holding.current = false; setHeld(false); } window.clearTimeout(holdTimer.current); }
  });

  // Between the story's header and its share button.
  const tapZone = `top: ${Math.round(96 * scale)}px; bottom: ${Math.round(96 * scale)}px`;
  const storyProps = (key: StoryKey, i: number) => ({ w, story: key, index: i, total, height });

  const current = (
    <div class="wr-frame is-current" ref={storyEl} style={`width: ${390 * scale}px; height: ${height * scale}px`}>
      <div class="wr-scale" style={`width: 390px; height: ${height}px; transform: scale(${scale})`}>
        <Story
          key={`${index}-${run}`}
          {...storyProps(stories[index], index)}
          paused={paused || held || busy}
          done={index === total - 1}
          onSegmentEnd={() => { if (index < total - 1) next(); }}
          onPause={() => setPaused((p) => !p)}
          onClose={close}
          onNext={next}
          onShare={shareStory}
          onShareCard={shareCard}
          onSaveCard={saveCard}
          onReplay={() => go(0)}
        />
      </div>
      {stories[index] !== 'summary' && <>
        <button type="button" class="wr-tap is-prev" tabIndex={-1} aria-hidden="true" style={tapZone} {...press(-1)} />
        <button type="button" class="wr-tap is-next" tabIndex={-1} aria-hidden="true" style={tapZone} {...press(1)} />
      </>}
    </div>
  );

  // The share card, kept ready off screen for "Share my Wrapped" from any story.
  const offscreen = (
    <div aria-hidden="true" style="position: fixed; left: -10000px; top: 0; pointer-events: none">
      <SummaryCard w={w} cardRef={(el) => { cardEl.current = el; }} />
    </div>
  );

  if (!wide) {
    return (
      <div class="wr-page is-full">
        <main class="wr-stage" aria-label={title} style={`justify-content: center`}>{current}</main>
        {offscreen}
      </div>
    );
  }

  const neighbour = (i: number) => (
    <div
      class={'wr-frame is-side' + (i < 0 || i >= total ? ' is-blank' : '')}
      style={`width: ${390 * side}px; height: ${STORY_H * side}px`}
      onClick={() => go(i)}
      aria-hidden="true"
    >
      {i >= 0 && i < total && (
        <div class="wr-scale" style={`width: 390px; height: ${STORY_H}px; transform: scale(${side})`}>
          <Story {...storyProps(stories[i], i)} still />
        </div>
      )}
    </div>
  );

  return (
    <div class="wr-page">
      <div aria-hidden="true" class="glow" style="position: absolute; left: calc(50% - 340px); top: -120px; width: 680px; height: 680px; border-radius: 50%; background: radial-gradient(closest-side, rgba(255, 159, 255, 0.32), rgba(255, 159, 255, 0))" />
      <div aria-hidden="true" class="glow" style="position: absolute; left: calc(50% - 100px); top: 420px; width: 620px; height: 620px; border-radius: 50%; background: radial-gradient(closest-side, rgba(205, 180, 255, 0.22), rgba(205, 180, 255, 0)); animation-delay: -2s" />
      <header class="wr-head">
        <Link to="/" class="wr-home"><BrandMark size={32} /><span class="wr-home-name">{brand}</span></Link>
        <span aria-hidden="true" class="wr-head-rule" />
        <span class="wr-head-title">{t('Wrapped')} <span>{w.year}</span></span>
        <span style="flex: 1" />
        <button type="button" class="wr-head-share" onClick={shareCard} disabled={busy}>
          <svg viewBox="0 0 24 24" width="18" height="18" style="fill: none; stroke: currentColor; stroke-width: 2.4; stroke-linecap: round; stroke-linejoin: round" aria-hidden="true"><path d="M12 3v12" /><path d="M7 8l5-5 5 5" /><path d="M5 13v6a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-6" /></svg>
          {t('Share my Wrapped')}
        </button>
        <Link to="/" class="wr-head-back">{t('Back to Seedy')}</Link>
      </header>
      <main class="wr-stage" aria-label={title}>
        {neighbour(index - 1)}
        <button type="button" class="wr-arrow" aria-label={t('Previous story')} onClick={prev} disabled={index === 0}>
          <svg viewBox="0 0 24 24" width="24" height="24" style="fill: none; stroke: currentColor; stroke-width: 2.6; stroke-linecap: round; stroke-linejoin: round" aria-hidden="true"><path d="M15 6l-6 6 6 6" /></svg>
        </button>
        {current}
        <button type="button" class="wr-arrow" aria-label={t('Next story')} onClick={next} disabled={index === total - 1}>
          <svg viewBox="0 0 24 24" width="24" height="24" style="fill: none; stroke: currentColor; stroke-width: 2.6; stroke-linecap: round; stroke-linejoin: round" aria-hidden="true"><path d="M9 6l6 6-6 6" /></svg>
        </button>
        {neighbour(index + 1)}
      </main>
      <div aria-hidden="true" class="wr-keys">
        <span><kbd>← →</kbd>{t('Move')}</span>
        <span><kbd>{t('Space')}</kbd>{t('Pause')}</span>
        <span><kbd>{t('Esc')}</kbd>{t('Close')}</span>
      </div>
      {offscreen}
    </div>
  );
}
