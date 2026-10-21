import { useEffect, useRef, useState } from 'preact/hooks';
import type { BaseItem } from '../api/types';
import { t } from '../lib/i18n';
import { Icon } from '../ui/icons';
import type { MediaLike } from './media';
import { PlaybackSession, type ActiveStream, type StreamOptions } from './playback';

/* "Every video (for testing)": the experimental player on the left and, on the right, the same title played the usual
   way (the <video> element, converted by the server when this browser can't play it), muted and kept in step with
   the left. The bar between them moves. */

export function Compare({ item, master, opts, describe }: {
  item: BaseItem;
  /* The experimental player, as the player drives it. */
  master: MediaLike;
  /* Its stream options: the right side plays the same source, audio track and quality. */
  opts: StreamOptions;
  /* The status line for the right side's stream. */
  describe: (stream: ActiveStream, vw: number, vh: number) => string;
}) {
  const rootRef = useRef<HTMLDivElement>(null);
  const videoRef = useRef<HTMLVideoElement>(null);
  const dragging = useRef(false);
  const [split, setSplit] = useState(50);
  const [usual, setUsual] = useState<ActiveStream | null>(null);
  const [size, setSize] = useState({ w: 0, h: 0 });
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const v = videoRef.current!;
    v.muted = true;
    const sess = new PlaybackSession(item, v, {
      onStream: setUsual,
      onFatal: () => {
        sess.stop({ failed: true });
        setFailed(true);
      }
    }, { ...opts, soft: 'off', subtitleIndex: -1, burnSubtitle: false, startSeconds: master.currentTime || opts.startSeconds });
    sess.silent = true;
    let lastSeek = 0;

    /* Same play state, and the same moment: a small gap closes by running a little faster or slower, a large one by
       seeking (not while the right side is still loading, and not over and over). Paused, both show the same frame. */
    const sync = (exact = false) => {
      if (!sess.stream || sess.stopped) return;
      const m = sess.media;
      const target = master.currentTime;
      const drift = sess.position() - target;
      const now = performance.now();
      const base = master.playbackRate || 1;
      if (master.paused) {
        if (!m.paused) m.pause();
        if (Math.abs(drift) > 0.05 && now - lastSeek > 300) {
          lastSeek = now;
          sess.seek(target);
        }
        return;
      }
      if (m.paused) sess.play();
      const loading = v.readyState < 3;
      if ((exact && Math.abs(drift) > 0.3) || (Math.abs(drift) > 2.5 && !loading && now - lastSeek > 4000)) {
        lastSeek = now;
        m.playbackRate = base;
        sess.seek(target);
        return;
      }
      m.playbackRate = Math.abs(drift) < 0.03 ? base : base * Math.max(0.5, Math.min(1.5, 1 - drift));
    };
    const onMaster = () => sync(true);
    const onSize = () => setSize({ w: v.videoWidth, h: v.videoHeight });
    const events = ['play', 'pause', 'seeked', 'ratechange'];
    events.forEach((e) => master.addEventListener(e, onMaster));
    v.addEventListener('resize', onSize);
    sess.open().then(() => sync(true), () => setFailed(true));
    const timer = window.setInterval(() => sync(), 200);
    return () => {
      window.clearInterval(timer);
      events.forEach((e) => master.removeEventListener(e, onMaster));
      v.removeEventListener('resize', onSize);
      sess.release();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const moveTo = (clientX: number) => {
    const r = rootRef.current?.getBoundingClientRect();
    if (r && r.width) setSplit(Math.max(0, Math.min(100, ((clientX - r.left) / r.width) * 100)));
  };
  const onKey = (e: KeyboardEvent) => {
    const step = e.shiftKey ? 1 : 5;
    const next = e.key === 'ArrowLeft' ? split - step : e.key === 'ArrowRight' ? split + step : e.key === 'Home' ? 0 : e.key === 'End' ? 100 : null;
    if (next === null) return;
    // The arrows move the bar here, not the playback position.
    e.preventDefault();
    e.stopPropagation();
    setSplit(Math.max(0, Math.min(100, next)));
  };

  const right = failed
    ? t('Couldn’t play it the usual way')
    : [t('Usual player'), usual ? describe(usual, size.w, size.h) : ''].filter(Boolean).join(' · ');

  return (
    <div ref={rootRef} class="pl-cmp">
      <video ref={videoRef} class="pl-video pl-cmp-video" style={{ clipPath: `inset(0 0 0 ${split}%)` }} playsInline muted preload="auto" aria-hidden="true" />
      <div
        class="pl-cmp-bar"
        style={{ left: split + '%' }}
        role="slider"
        tabIndex={0}
        aria-label={t('Experimental player on the left, usual player on the right')}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(split)}
        aria-valuetext={`${Math.round(split)}%`}
        onPointerDown={(e) => {
          e.stopPropagation();
          (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
          dragging.current = true;
          moveTo(e.clientX);
        }}
        onPointerMove={(e) => { if (dragging.current) moveTo(e.clientX); }}
        onPointerUp={() => { dragging.current = false; }}
        onPointerCancel={() => { dragging.current = false; }}
        onClick={(e) => e.stopPropagation()}
        onDblClick={(e) => e.stopPropagation()}
        onKeyDown={onKey}
      >
        <span class="pl-cmp-line" aria-hidden="true" />
        <span class="pl-cmp-handle pl-fade" aria-hidden="true">
          <Icon name="chevronLeft" size={16} />
          <Icon name="chevronRight" size={16} />
        </span>
        <span class="pl-cmp-label is-left pl-fade">{t('Experimental player')}</span>
        <span class="pl-cmp-label is-right pl-fade">{right}</span>
      </div>
    </div>
  );
}
