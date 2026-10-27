import { tmdbImage } from '../../api/images';
import { mediaList, type MediaRequest } from '../../api/media';
import { myRequests, type SeerrRequest } from '../../api/seedy';
import { meStore } from '../../lib/app-state';
import { fmtDayMonth } from '../../lib/format';
import { t, tx } from '../../lib/i18n';
import { Link } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { inLibrary } from '../../ui/cards';
import { Icon } from '../../ui/icons';
import { CUSTOM, readCustom, saveCustom } from './prefs';
import { AlbumArt, BookArt, mediaName, readyAction } from '../requests/media-parts';

/* Home's "Arrived" notice (Settings · Profile › Tell me when a request is ready): the person's requests that
   became available in the last 7 days, until they dismiss them. */

const WEEK = 7 * 86_400_000;

export function arrivals(list: SeerrRequest[], dismissed: number[], now = Date.now()) {
  return list.filter((r) => {
    if (!inLibrary(r)) return false;
    const at = r.availableAt ? new Date(r.availableAt).getTime() : NaN;
    return Number.isFinite(at) && now - at <= WEEK && !dismissed.includes(r.requestId);
  });
}

/* Music and books that arrived in the last 7 days, from Seedy's own requests. */
export function mediaArrivals(list: MediaRequest[], dismissed: string[], now = Date.now()) {
  return list.filter((r) => {
    if (r.status !== 'ready' || !r.itemId || !r.requestedByMe) return false;
    const at = r.readyAt ? new Date(r.readyAt).getTime() : NaN;
    return Number.isFinite(at) && now - at <= WEEK && !dismissed.includes(r.id);
  });
}

export function ArrivedNotice() {
  const me = useStore(meStore);
  const notify = me.prefs.notifyRequests !== false;
  const on = notify && me.requests.connected;
  const mediaOn = notify && !!me.media?.notifyOnReady && (!!me.media?.music || !!me.media?.books);
  const { data } = useAsync(() => (on ? myRequests().catch(() => null) : Promise.resolve(null)), [on]);
  const media = useAsync(() => (mediaOn ? mediaList('mine').catch(() => null) : Promise.resolve(null)), [mediaOn]);
  const dismissed = readCustom(CUSTOM.dismissedArrivals, me.prefs);
  const dismissedMedia = readCustom(CUSTOM.dismissedMediaArrivals, me.prefs);
  const items = on ? arrivals(data?.results ?? [], dismissed).slice(0, 3) : [];
  const mediaItems = mediaOn ? mediaArrivals(media.data?.results ?? [], dismissedMedia).slice(0, Math.max(0, 3 - items.length)) : [];
  if (!items.length && !mediaItems.length) return null;

  const dismiss = (ids: number[]) => {
    // Keep the list short: only ids that could still show matter.
    const keep = (data?.results ?? []).filter((r) => r.availableAt && Date.now() - new Date(r.availableAt).getTime() <= WEEK).map((r) => r.requestId);
    saveCustom({ [CUSTOM.dismissedArrivals]: [...new Set([...dismissed, ...ids])].filter((id) => keep.includes(id)) });
  };
  const dismissMedia = (id: string) => {
    const keep = (media.data?.results ?? []).filter((r) => r.readyAt && Date.now() - new Date(r.readyAt).getTime() <= WEEK).map((r) => r.id);
    saveCustom({ [CUSTOM.dismissedMediaArrivals]: [...new Set([...dismissedMedia, id])].filter((x) => keep.includes(x)) });
  };

  return (
    <section class="me-arrivals" aria-label={t('Requests that arrived')}>
      {items.map((r) => (
        <div key={r.requestId} class="me-arrival">
          <Link to={`/details/${r.jellyfinItemId}`} class="me-arrival-poster" tabIndex={-1} aria-hidden="true">
            {r.posterPath && <img src={tmdbImage(r.posterPath, 'w185')} alt="" loading="lazy" />}
          </Link>
          <div class="me-arrival-text">
            <span class="me-arrival-line"><span class="dot" aria-hidden="true" /><span>{tx('{arrived} {title}', { arrived: <em>{t('Arrived:')}</em>, title: r.title })}</span></span>
            <span class="me-arrival-sub">
              {[t('You asked for it {date}', { date: fmtDayMonth(new Date(r.requestedAt)) }), t(r.status === 'partial' ? 'Some episodes so far' : r.mediaType === 'tv' ? 'Series' : 'Movie')].join(' · ')}
            </span>
          </div>
          <div class="me-arrival-actions">
            <Link to={`/details/${r.jellyfinItemId}`} class="btn btn-secondary is-sm">{t('Open')}</Link>
            <button type="button" class="btn icon-btn is-bare is-40" aria-label={t('Dismiss {title}', { title: r.title })} onClick={() => dismiss([r.requestId])}><Icon name="close" size={16} /></button>
          </div>
        </div>
      ))}
      {mediaItems.map((r) => {
        const go = readyAction(r);
        const name = mediaName(r);
        return (
          <div key={r.id} class="me-arrival">
            <Link to={`/details/${r.itemId}`} class={'me-arrival-poster' + (r.kind === 'music' ? ' is-square' : '')} tabIndex={-1} aria-hidden="true">
              {r.kind === 'music'
                ? <AlbumArt seed={r.albums[0]?.mbid ?? r.foreignId} src={r.coverUrl} />
                : <BookArt seed={r.foreignId} src={r.coverUrl} title={r.title} author={r.subtitle} size="thumb" />}
            </Link>
            <div class="me-arrival-text">
              <span class="me-arrival-line"><span class="dot" aria-hidden="true" /><span>{tx('{arrived} {title}', { arrived: <em>{t('Arrived:')}</em>, title: name })}</span></span>
              <span class="me-arrival-sub">
                {[t('You asked for it {date}', { date: fmtDayMonth(new Date(r.requestedAt)) }), r.kind === 'music' ? t('Music') : t('Book')].join(' · ')}
              </span>
            </div>
            <div class="me-arrival-actions">
              {go && <Link to={go.to} class="btn btn-secondary is-sm">{go.label}</Link>}
              <button type="button" class="btn icon-btn is-bare is-40" aria-label={t('Dismiss {title}', { title: name })} onClick={() => dismissMedia(r.id)}><Icon name="close" size={16} /></button>
            </div>
          </div>
        );
      })}
    </section>
  );
}
