import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { cardArt } from '../api/images';
import type { BaseItem } from '../api/types';
import { progressFraction, runtime } from '../lib/format';
import { Art, Progress } from '../ui/cards';
import { Segmented } from '../ui/controls';
import { Icon } from '../ui/icons';
import { t } from '../lib/i18n';

/* Episodes of the playing season, with a season switcher. */
export function EpisodesPanel({ current, episodes, onPlay }: { current: BaseItem; episodes: BaseItem[] | null; onPlay: (ep: BaseItem) => void }) {
  const seasons = useMemo(() => {
    const map = new Map<string, { id: string; name: string; num?: number; eps: BaseItem[] }>();
    for (const ep of episodes ?? []) {
      const id = ep.SeasonId ?? String(ep.ParentIndexNumber ?? 0);
      let s = map.get(id);
      if (!s) {
        const num = ep.ParentIndexNumber;
        s = { id, num, name: ep.SeasonName || (num === 0 ? t('Specials') : t('Season {n}', { n: num ?? '' })), eps: [] };
        map.set(id, s);
      }
      s.eps.push(ep);
    }
    return [...map.values()];
  }, [episodes]);

  const [season, setSeason] = useState<string>(current.SeasonId ?? String(current.ParentIndexNumber ?? 0));
  const shown = seasons.find((s) => s.id === season) ?? seasons[0];
  const listRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    listRef.current?.querySelector<HTMLElement>('[aria-current="true"]')?.scrollIntoView({ block: 'nearest' });
  }, [episodes]);

  return (
    <section class="pl-panel" aria-label={t('Episodes')}>
      {seasons.length > 1 && (
        <div class="pl-seasons hscroll">
          <Segmented<string>
            label={t('Season')}
            value={shown?.id ?? ''}
            onChange={setSeason}
            options={seasons.map((s) => ({ value: s.id, label: s.num === 0 ? t('Specials') : s.num !== undefined ? t('Season {n}', { n: s.num }) : s.name }))}
          />
        </div>
      )}
      <div ref={listRef} class="pl-group">
        <span class="pl-label">{current.SeriesName ? `${current.SeriesName} · ${shown?.name ?? ''}` : shown?.name}</span>
        {episodes === null && [0, 1, 2, 3].map((i) => <span key={i} class="pl-row-skel skel" />)}
        {shown?.eps.map((ep) => {
          const on = ep.Id === current.Id;
          const prog = progressFraction(ep);
          const watched = !!ep.UserData?.Played;
          return (
            <button
              key={ep.Id}
              type="button"
              class="menu-item pl-row-item is-episode"
              aria-pressed={on ? 'true' : 'false'}
              aria-current={on ? 'true' : undefined}
              onClick={() => { if (!on) onPlay(ep); }}
            >
              <span class="lead">
                <span class="pl-row-thumb is-episode">
                  <Art art={cardArt(ep, 320)} title={ep.Name} titleClass="pl-row-art-title" />
                  {watched && !on && <span class="pl-row-watched" aria-label={t('Watched')}><Icon name="check" size={11} stroke={3} /></span>}
                  {!watched && prog > 0 && !on && <Progress fraction={prog} size={4} class="pl-row-bar" />}
                </span>
                <span class="two">
                  <span>{ep.IndexNumber !== undefined ? `${ep.IndexNumber}. ` : ''}{ep.Name}</span>
                  <span class="sub">{on ? t('Playing now') : [runtime(ep), watched ? t('Watched') : ''].filter(Boolean).join(' · ')}</span>
                </span>
              </span>
              {on && <Icon name="check" size={20} stroke={2.6} class="tick" />}
            </button>
          );
        })}
      </div>
    </section>
  );
}
