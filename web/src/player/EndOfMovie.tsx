import { useEffect, useLayoutEffect, useRef } from 'preact/hooks';
import { backdropUrl, cardArt } from '../api/images';
import type { BaseItem } from '../api/types';
import { duration, runtime, score } from '../lib/format';
import { Art } from '../ui/cards';
import { CountdownRing, Icon } from '../ui/icons';
import { usingKeyboard } from './keys';
import { t } from '../lib/i18n';

export interface TileRect { left: number; top: number; width: number; height: number }

const pickMeta = (it: BaseItem) => [it.ProductionYear, it.Genres?.[0], runtime(it)].filter(Boolean) as (string | number)[];

/* End of a movie (PlayerEndMovie board): the playing video shrinks into the left tile while a pick
   from "similar" counts down on the right. The player moves its own <video> onto the tile. */
export function EndOfMovie({ item, picks, creditsLeft, fraction, remaining, total, onWatchCredits, onBack, onPlay, onTileRect }: {
  item: BaseItem;
  picks: BaseItem[];
  creditsLeft: number;
  fraction: number;
  remaining: number | null;
  total: number;
  onWatchCredits: () => void;
  onBack: () => void;
  onPlay: (it: BaseItem) => void;
  onTileRect: (r: TileRect | null) => void;
}) {
  const tile = useRef<HTMLDivElement>(null);
  const playRef = useRef<HTMLButtonElement>(null);
  const [pick, ...rest] = picks;

  useLayoutEffect(() => {
    const el = tile.current;
    if (!el) return;
    const measure = () => {
      const r = el.getBoundingClientRect();
      onTileRect({ left: r.left, top: r.top, width: r.width, height: r.height });
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    const scroller = el.closest('.pl-end');
    scroller?.addEventListener('scroll', measure, { passive: true });
    window.addEventListener('resize', measure);
    return () => {
      ro.disconnect();
      scroller?.removeEventListener('scroll', measure);
      window.removeEventListener('resize', measure);
      onTileRect(null);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => { if (usingKeyboard()) playRef.current?.focus({ preventScroll: true }); }, []);

  const bd = pick ? backdropUrl(pick, 1280) ?? cardArt(pick, 1280).src : undefined;

  return (
    <div class="pl-end">
      <div class="pl-end-top">
        <button type="button" class="btn player-btn is-soft" aria-label={t('Back to browse')} onClick={onBack}>
          <Icon name="arrowLeft" size={22} />
        </button>
        <span class="pl-end-name">{item.Name}</span>
      </div>

      <div class={'pl-end-body' + (pick ? '' : ' is-solo')}>
        <div class="pl-end-now">
          <div ref={tile} class="pl-end-tile">
            <div class="pl-end-bar"><div style={{ width: `${(Math.min(1, fraction) * 100).toFixed(2)}%` }} /></div>
          </div>
          <div class="pl-end-text">
            <span class="pl-end-title">{item.Name}</span>
            <span class="pl-end-meta">{t('Credits · {time} left', { time: duration(Math.ceil(creditsLeft / 60) * 60) })}</span>
          </div>
          <div class="pl-end-actions">
            <button type="button" class="btn btn-secondary pl-end-btn" onClick={onWatchCredits}>{t('Watch credits')}</button>
            <button type="button" class="btn btn-ghost pl-end-btn" onClick={onBack}>{t('Back to browse')}</button>
          </div>
        </div>

        {pick && (
          <section class="pl-end-picks" aria-label={t('Because you watched {title}', { title: item.Name ?? '' })}>
            <span class="overline">{t('Because you watched {title}', { title: item.Name ?? '' })}</span>
            <div class="pl-pick">
              {bd && <img class="pl-pick-art" src={bd} alt="" />}
              <div aria-hidden="true" class="pl-pick-shade" />
              <div class="pl-pick-body">
                <span class="pl-pick-title">{pick.Name}</span>
                <div class="pl-pick-meta">
                  {pickMeta(pick).map((m, i) => <span key={i}>{m}</span>)}
                  {pick.CommunityRating ? (
                    <span class="pl-pick-score"><Icon name="star" size={14} color="var(--seedy-accent)" />{score(pick.CommunityRating)}</span>
                  ) : null}
                </div>
                <div class="pl-pick-actions">
                  <button ref={playRef} type="button" class="btn btn-primary is-md" onClick={() => onPlay(pick)}>
                    <Icon name="play" size={18} />
                    {t('Play')}
                  </button>
                  {remaining !== null && (
                    <span class="pl-pick-count">
                      <CountdownRing fraction={total > 0 ? remaining / total : 0} />
                      {t('Plays in {n} s', { n: Math.max(0, Math.ceil(remaining)) })}
                    </span>
                  )}
                </div>
              </div>
            </div>
            {rest.length > 0 && (
              <>
                <span class="pl-end-or">{t('Or try')}</span>
                <div class="pl-end-more">
                  {rest.slice(0, 2).map((it) => (
                    <button key={it.Id} type="button" class="pl-more" onClick={() => onPlay(it)} aria-label={t('Play {title}', { title: it.Name ?? '' })}>
                      <span class="pl-more-art"><Art art={cardArt(it, 640)} title={it.Name} /></span>
                      <span class="pl-more-text">
                        <span class="pl-more-title">{it.Name}</span>
                        <span class="pl-more-meta">{pickMeta(it).join(' · ')}</span>
                      </span>
                    </button>
                  ))}
                </div>
              </>
            )}
          </section>
        )}
      </div>
    </div>
  );
}
