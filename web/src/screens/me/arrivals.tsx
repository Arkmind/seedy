import { tmdbImage } from '../../api/images';
import { myRequests, type MyRequest } from '../../api/seedy';
import { meStore } from '../../lib/app-state';
import { fmtDayMonth } from '../../lib/format';
import { t, tx } from '../../lib/i18n';
import { Link } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { Icon } from '../../ui/icons';
import { CUSTOM, readCustom, saveCustom } from './prefs';

/* Home's "Arrived" notice (Settings · Profile › Tell me when a request is ready): the person's requests that
   became available in the last 7 days, until they dismiss them. */

const WEEK = 7 * 86_400_000;

export function arrivals(list: MyRequest[], dismissed: number[], now = Date.now()) {
  return list.filter((r) => {
    if (!(r.status === 'available' || r.status === 'partial') || !r.jellyfinItemId) return false;
    const at = r.availableAt ? new Date(r.availableAt).getTime() : NaN;
    return Number.isFinite(at) && now - at <= WEEK && !dismissed.includes(r.requestId);
  });
}

export function ArrivedNotice() {
  const me = useStore(meStore);
  const on = me.prefs.notifyRequests !== false && me.requests.connected;
  const { data } = useAsync(() => (on ? myRequests().catch(() => null) : Promise.resolve(null)), [on]);
  const dismissed = readCustom(CUSTOM.dismissedArrivals, me.prefs);
  const items = arrivals(data?.results ?? [], dismissed).slice(0, 3);
  if (!on || !items.length) return null;

  const dismiss = (ids: number[]) => {
    // Keep the list short: only ids that could still show matter.
    const keep = (data?.results ?? []).filter((r) => r.availableAt && Date.now() - new Date(r.availableAt).getTime() <= WEEK).map((r) => r.requestId);
    saveCustom({ [CUSTOM.dismissedArrivals]: [...new Set([...dismissed, ...ids])].filter((id) => keep.includes(id)) });
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
    </section>
  );
}
