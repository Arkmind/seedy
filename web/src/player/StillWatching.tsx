import { useEffect, useRef, useState } from 'preact/hooks';
import { backdropUrl, cardArt } from '../api/images';
import type { BaseItem } from '../api/types';
import { duration, endsAt, episodeTag, runtime, ticksToSeconds, timecode } from '../lib/format';
import { Art } from '../ui/cards';
import { Icon } from '../ui/icons';
import { Medu } from '../ui/medu';
import { EdgeProgress } from './UpNext';
import { t, tx } from '../lib/i18n';

/* How long the question waits for an answer before the stream stops to free up the server. */
const ANSWER_MS = 15 * 60_000;

/* "Still watching?" (PlayerStillWatching board; Settings · Playback → Ask "Are you still watching?"). Auto-play stops
   here after a run of episodes or a long stretch with nobody touching anything. */
export function StillWatching({ item, next, count, movies, minutes, byTime, fraction, onContinue, onLeave, onSettings }: {
  /* What just finished, and what would play next. */
  item: BaseItem;
  next: BaseItem;
  /* Episodes (or movies) played in a row, and minutes since anyone touched the player. */
  count: number;
  movies: boolean;
  minutes: number;
  byTime: boolean;
  fraction: number;
  onContinue: () => void;
  /* "I'm done for now", and nobody answering in time. */
  onLeave: () => void;
  onSettings: () => void;
}) {
  const playRef = useRef<HTMLButtonElement>(null);
  const [deadline] = useState(() => Date.now() + ANSWER_MS);
  const [now, setNow] = useState(() => Date.now());
  const leave = useRef(onLeave);
  leave.current = onLeave;
  // Keep watching: Medu wakes up and grabs the popcorn (Sleepy → Popcorn), then the next one plays.
  const [waking, setWaking] = useState(false);
  useEffect(() => { playRef.current?.focus({ preventScroll: true }); }, []);
  useEffect(() => {
    const id = window.setInterval(() => {
      setNow(Date.now());
      if (Date.now() >= deadline) { window.clearInterval(id); leave.current(); }
    }, 1000);
    return () => window.clearInterval(id);
  }, [deadline]);

  const why = byTime
    ? t('You’ve been watching for {time} without touching anything, so Seedy paused before the next one.', { time: duration(minutes * 60) })
    : movies
      ? count === 1
        ? t('You’ve watched a whole movie without touching anything, so Seedy paused before the next one.')
        : t('You’ve watched {n} movies in a row without touching anything, so Seedy paused before the next one.', { n: count })
      : count === 1
        ? t('You’ve watched a whole episode without touching anything, so Seedy paused before the next one.')
        : t('You’ve watched {n} episodes in a row without touching anything, so Seedy paused before the next one.', { n: count });
  const tag = item.Type === 'Episode' ? episodeTag(item) : '';
  const kicker = item.Type === 'Episode'
    ? [item.SeriesName, tag && t('{what} finished', { what: tag })]
    : [t('{what} finished', { what: item.Name })];
  const secs = ticksToSeconds(next.RunTimeTicks);
  const meta = [next.Type === 'Episode' ? episodeTag(next) : next.ProductionYear, runtime(next), secs ? endsAt(secs) : '']
    .filter(Boolean).join(' · ');
  const backdrop = backdropUrl(next) ?? backdropUrl(item);
  const left = Math.ceil(Math.max(0, deadline - now) / 1000);

  return (
    <div class="pl-sw">
      {backdrop && <img aria-hidden="true" class="pl-sw-bg" src={backdrop} alt="" />}
      <div aria-hidden="true" class="pl-sw-shade" />
      <div class="pl-sw-body" role="dialog" aria-modal="true" aria-labelledby="pl-sw-title" aria-describedby="pl-sw-text">
        <span class="pl-sw-kicker">{kicker.filter(Boolean).join(' · ')}</span>
        <div class="pl-sw-head">
          <h1 id="pl-sw-title" class="pl-sw-title">{t('Still watching?')}</h1>
          <span class="pl-sw-medu">
            <Medu mood={waking ? 'popcorn' : 'sleepy'} size={96} onSettled={(mood) => { if (mood === 'popcorn') onContinue(); }} />
          </span>
        </div>
        <p id="pl-sw-text" class="pl-sw-text">{why}</p>
        <div class="pl-sw-next">
          <span class="pl-sw-art"><Art art={cardArt(next, 336)} title={next.Type === 'Episode' ? next.SeriesName ?? next.Name : next.Name} /></span>
          <span class="pl-sw-next-text">
            <span class="pl-sw-next-kicker">{t('Next')}</span>
            <span class="pl-sw-next-title">{next.Name}</span>
            {meta && <span class="pl-sw-next-meta">{meta}</span>}
          </span>
        </div>
        <div class="pl-sw-actions">
          <button ref={playRef} type="button" class="pl-sw-btn is-primary" onClick={() => setWaking(true)}>
            <Icon name="play" size={18} />
            {t('Keep watching')}
          </button>
          <button type="button" class="pl-sw-btn" disabled={waking} onClick={onLeave}>{t('I’m done for now')}</button>
        </div>
        <a class="pl-sw-link" href="settings/playback" onClick={(e) => { e.preventDefault(); onSettings(); }}>{t('Change when Seedy asks')}</a>
      </div>
      <div class="pl-sw-status" role="timer">
        <Icon name="clock" size={18} stroke={2.2} />
        <span>{tx('Nobody here? The stream stops in {time} to free up the server. Your spot is saved.', { time: <b>{timecode(left)}</b> })}</span>
      </div>
      <EdgeProgress fraction={fraction} />
    </div>
  );
}
