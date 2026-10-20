import type { BaseItem } from '../api/types';
import { runtime, yearRange, ratingLabel } from '../lib/format';
import { Icon } from '../ui/icons';
import { t } from '../lib/i18n';
import { EdgeProgress } from './UpNext';

/* "You're watching" screen (PlayerPaused board), shown after a while paused with no input. */
export function PausedOverlay({ item, rating, fraction, onWake }: { item: BaseItem; rating?: string; fraction: number; onWake: () => void }) {
  const isEpisode = item.Type === 'Episode';
  const title = isEpisode ? item.SeriesName ?? item.Name : item.Album && item.Type === 'Audio' ? item.Album : item.Name;
  const sub = isEpisode
    ? [item.ParentIndexNumber !== undefined ? t('S{n}', { n: item.ParentIndexNumber }) : '', item.IndexNumber !== undefined ? t('E{n}', { n: item.IndexNumber }) : '', item.Name].filter(Boolean).join(' · ')
    : item.Type === 'Audio' ? [item.Name, item.AlbumArtist ?? item.Artists?.[0]].filter(Boolean).join(' · ') : '';
  const year = isEpisode && item.PremiereDate ? String(new Date(item.PremiereDate).getFullYear()) : yearRange(item);
  const cast = (item.People ?? []).filter((p) => p.Type === 'Actor' || p.Type === 'GuestStar').slice(0, 3).map((p) => p.Name);
  return (
    <div class="pl-paused" onPointerMove={onWake} onPointerDown={onWake} onWheel={onWake}>
      <div aria-hidden="true" class="pl-paused-dim" />
      <div aria-hidden="true" class="pl-paused-shade" />
      <section class="pl-paused-body" aria-label={t("You're watching")}>
        <div class="pl-paused-col">
          <span class="pl-paused-kicker">{t("You're watching")}</span>
          <h1 class="pl-paused-title">{title}</h1>
          {sub && <span class="pl-paused-sub">{sub}</span>}
          <div class="pl-paused-meta">
            {year && <span>{year}</span>}
            {rating && <span class="rating-badge pl-paused-rating">{ratingLabel(rating)}</span>}
            {item.RunTimeTicks ? <span>{runtime(item)}</span> : null}
          </div>
          {item.Overview && <p class="pl-paused-overview">{item.Overview}</p>}
          {cast.length > 0 && <span class="pl-paused-cast">{t('Starring {names}', { names: cast.join(', ') })}</span>}
        </div>
      </section>
      <div class="pl-paused-badge" role="status">
        <Icon name="pause" size={26} />
        <span>{t('Paused')}</span>
      </div>
      <EdgeProgress fraction={fraction} />
    </div>
  );
}
