import { useEffect, useRef } from 'preact/hooks';
import { cardArt } from '../api/images';
import type { BaseItem } from '../api/types';
import { duration, episodeTag, runtime } from '../lib/format';
import { Art } from '../ui/cards';
import { Icon } from '../ui/icons';
import { t } from '../lib/i18n';

/* "Are you still watching?" (Settings · Playback → Between episodes). Auto-play stops here after a run of episodes
   or a long stretch with nobody touching anything; built from the Paused screen and Up next card. */
export function StillWatching({ next, episodes, minutes, byTime, onContinue, onLeave }: {
  next: BaseItem;
  /* Episodes played in a row, and minutes since anyone touched the player. */
  episodes: number;
  minutes: number;
  byTime: boolean;
  onContinue: () => void;
  onLeave: () => void;
}) {
  const playRef = useRef<HTMLButtonElement>(null);
  useEffect(() => { playRef.current?.focus({ preventScroll: true }); }, []);
  const why = byTime
    ? t('You’ve been watching for {time} without touching anything.', { time: duration(minutes * 60) })
    : episodes === 1 ? t('You’ve watched a whole episode without touching anything.') : t('You’ve watched {n} episodes in a row.', { n: episodes });
  const meta = [next.Type === 'Episode' ? episodeTag(next) : next.ProductionYear, runtime(next)].filter(Boolean).join(' · ');
  return (
    <div class="pl-ask" role="dialog" aria-modal="true" aria-labelledby="pl-ask-title" aria-describedby="pl-ask-text">
      <div aria-hidden="true" class="pl-ask-dim" />
      <section class="pl-ask-sheet">
        <div class="pl-ask-head">
          <span class="overline">{next.Type === 'Episode' ? next.SeriesName ?? t('Up next') : t('Up next')}</span>
          <h2 id="pl-ask-title" class="pl-ask-title">{t('Are you still watching?')}</h2>
          <p id="pl-ask-text" class="pl-ask-text">{why} {t('We paused before the next one so you don’t miss it.')}</p>
        </div>
        <div class="pl-ask-next">
          <span class="pl-ask-art"><Art art={cardArt(next, 320)} title={next.Type === 'Episode' ? next.SeriesName ?? next.Name : next.Name} /></span>
          <span class="pl-ask-next-text">
            <span class="pl-ask-next-kicker">{t('Next')}</span>
            <span class="pl-ask-next-title">{next.Name}</span>
            {meta && <span class="pl-ask-next-meta">{meta}</span>}
          </span>
        </div>
        <div class="pl-ask-actions">
          <button ref={playRef} type="button" class="btn btn-primary is-md pl-ask-play" onClick={onContinue}>
            <Icon name="play" size={18} />
            {t('Keep watching')}
          </button>
          <button type="button" class="btn btn-secondary is-tall" onClick={onLeave}>{t('Back to browse')}</button>
        </div>
      </section>
    </div>
  );
}
