import { useEffect, useRef } from 'preact/hooks';
import { cardArt } from '../api/images';
import type { BaseItem } from '../api/types';
import { endsAt, episodeTag, runtime, ticksToSeconds } from '../lib/format';
import { Art } from '../ui/cards';
import { CountdownRing, Icon } from '../ui/icons';
import { usingKeyboard } from './keys';
import { t, tx } from '../lib/i18n';

/* Up next card (PlayerUpNext board): bottom-right, over the credits. */
export function UpNext({ next, remaining, total, onPlay, onDismiss }: {
  next: BaseItem;
  /* Seconds left on the countdown; null when autoplay is off. */
  remaining: number | null;
  total: number;
  onPlay: () => void;
  onDismiss: () => void;
}) {
  const playRef = useRef<HTMLButtonElement>(null);
  useEffect(() => { if (usingKeyboard()) playRef.current?.focus({ preventScroll: true }); }, []);
  const secs = ticksToSeconds(next.RunTimeTicks);
  const meta = [next.Type === 'Episode' ? episodeTag(next) : next.ProductionYear, runtime(next), secs ? endsAt(secs + (remaining ?? 0)) : '']
    .filter(Boolean).join(' · ');
  return (
    <section class="pl-upnext" aria-label={t('Up next')}>
      <div class="pl-upnext-main">
        <div class="pl-upnext-art">
          <Art art={cardArt(next, 400)} title={next.Type === 'Episode' ? next.SeriesName ?? next.Name : next.Name} />
        </div>
        <div class="pl-upnext-text">
          <div class="pl-upnext-count">
            {remaining !== null && <CountdownRing fraction={total > 0 ? remaining / total : 0} />}
            {remaining !== null
              ? <span>{tx('Up next in {time}', { time: <b class="tnum">{Math.max(0, Math.ceil(remaining))} s</b> })}</span>
              : <span>{t('Up next')}</span>}
          </div>
          <span class="pl-upnext-title">{next.Name}</span>
          <span class="pl-upnext-meta">{meta}</span>
        </div>
      </div>
      <div class="pl-upnext-actions">
        <button ref={playRef} type="button" class="btn btn-primary is-md pl-upnext-play" onClick={onPlay}>
          <Icon name="play" size={18} />
          {t('Play now')}
        </button>
        <button type="button" class="btn btn-secondary is-tall" onClick={onDismiss}>{t('Watch credits')}</button>
      </div>
    </section>
  );
}

/* 4 px progress line along the very bottom (Up next, Paused). */
export function EdgeProgress({ fraction }: { fraction: number }) {
  return (
    <div aria-hidden="true" class="pl-edge">
      <div style={{ width: `${(Math.max(0, Math.min(1, fraction)) * 100).toFixed(2)}%` }} />
    </div>
  );
}
