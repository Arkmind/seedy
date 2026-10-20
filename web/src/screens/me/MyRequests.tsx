import { useState } from 'preact/hooks';
import { tmdbImage } from '../../api/images';
import { myRequests, type MyRequest } from '../../api/seedy';
import { meStore } from '../../lib/app-state';
import { fmtDate, fmtDayMonth } from '../../lib/format';
import { Link } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { seerrPath } from '../../ui/cards';
import { Icon } from '../../ui/icons';
import { MeLayout } from './MeLayout';
import { t } from '../../lib/i18n';
import './parts.css';

/* My requests: the person's Seerr requests, grouped by where they are (no board; History's list language). */

type Group = 'waiting' | 'downloading' | 'available' | 'declined';

const GROUPS: { id: Group; label: string }[] = [
  { id: 'waiting', label: 'Waiting for approval' },
  { id: 'downloading', label: 'Downloading' },
  { id: 'available', label: 'Available' },
  { id: 'declined', label: 'Declined' }
];

export function groupOf(r: MyRequest): Group {
  if (r.status === 'available' || r.requestStatus === 'completed') return 'available';
  if (r.requestStatus === 'declined' || r.requestStatus === 'failed') return 'declined';
  if (r.requestStatus === 'pending') return 'waiting';
  return 'downloading';
}

const dayLabel = (s?: string) => {
  if (!s) return '';
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return '';
  return d.getFullYear() === new Date().getFullYear() ? fmtDayMonth(d) : fmtDate(d);
};

function seasonsText(r: MyRequest) {
  const s = (r.seasons ?? []).slice().sort((a, b) => a - b);
  if (!s.length) return r.mediaType === 'tv' ? t('All seasons') : '';
  const contiguous = s.every((n, i) => i === 0 || n === s[i - 1] + 1);
  if (s.length === 1) return t('Season {n}', { n: s[0] });
  return contiguous ? t('Seasons {from}–{to}', { from: s[0], to: s[s.length - 1] }) : t('Seasons {list}', { list: s.join(', ') });
}

function stateOf(r: MyRequest, g: Group): { dot: string; text: string; when: string } {
  const asked = t('Requested {date}', { date: dayLabel(r.requestedAt) });
  switch (g) {
    case 'waiting': return { dot: 'is-pending', text: t('Waiting for approval'), when: asked };
    case 'downloading': return { dot: 'is-pending', text: r.status === 'partial' ? t('Partly in the library') : t('Approved · downloading'), when: asked };
    case 'available': return { dot: '', text: r.status === 'partial' ? t('Some episodes available') : t('In your library'), when: r.availableAt ? t('Arrived {date}', { date: dayLabel(r.availableAt) }) : asked };
    default: return { dot: 'is-danger', text: r.requestStatus === 'failed' ? t('Couldn’t be downloaded') : t('Declined by your admin'), when: asked };
  }
}

export function MyRequests() {
  const me = useStore(meStore);
  const [tick, setTick] = useState(0);
  const connected = me.requests.connected;
  const { data, error, loading } = useAsync(() => (connected ? myRequests() : Promise.resolve(null)), [connected, tick]);
  const list = data?.results ?? [];

  const lede = t('What you asked for in Seerr, and where each one is now.');

  return (
    <MeLayout
      page="requests"
      title={t('My requests')}
      lede={lede}
      actions={me.requests.discover ? <Link to="/list/discover" class="btn btn-secondary"><Icon name="search" size={18} />{t('Find something to request')}</Link> : undefined}
    >
      {!connected ? (
        <div class="empty">
          <span class="empty-icon"><Icon name="inbox" size={26} /></span>
          <div class="empty-body">
            <span class="empty-title">{t('Requests aren’t set up')}</span>
            <span class="empty-text">{t('Your server isn’t connected to Seerr, or your profile can’t browse it. Ask your admin.')}</span>
          </div>
        </div>
      ) : error ? (
        <div class="empty">
          <span class="empty-icon"><Icon name="alert" size={26} /></span>
          <div class="empty-body">
            <span class="empty-title">{t('Couldn’t load your requests')}</span>
            <span class="empty-text">{t('Seerr didn’t answer. It may be restarting.')}</span>
          </div>
          <button type="button" class="btn btn-secondary" onClick={() => setTick((k) => k + 1)}><Icon name="refresh" size={18} />{t('Try again')}</button>
        </div>
      ) : loading && !data ? (
        <div class="me-req-group" aria-busy="true">
          <span class="skel skel-line" style={{ width: '180px', margin: '2px 4px' }} />
          <div class="me-req-list">{[0, 1, 2].map((i) => <div key={i} class="me-req-row"><span class="skel me-req-skel" style={{ flex: '0 0 60px', height: '90px', borderRadius: '8px' }} /><span class="skel skel-line" style={{ width: '40%' }} /></div>)}</div>
        </div>
      ) : !list.length ? (
        <div class="empty">
          <span class="empty-icon"><Icon name="inbox" size={26} /></span>
          <div class="empty-body">
            <span class="empty-title">{t('No requests yet')}</span>
            <span class="empty-text">{t('Titles you request from Discover or Search show up here, with where they are on the way to your library.')}</span>
          </div>
        </div>
      ) : (
        GROUPS.map((g) => {
          const items = list.filter((r) => groupOf(r) === g.id);
          if (!items.length) return null;
          return (
            <section key={g.id} class="me-req-group" aria-labelledby={'req-' + g.id}>
              <h2 id={'req-' + g.id} class="me-req-label">{t(g.label)}<b>{items.length}</b></h2>
              <div class="me-req-list">
                {items.map((r) => <RequestRow key={r.requestId || r.mediaType + r.tmdbId} r={r} group={g.id} />)}
              </div>
            </section>
          );
        })
      )}
    </MeLayout>
  );
}

function RequestRow({ r, group }: { r: MyRequest; group: Group }) {
  const poster = tmdbImage(r.posterPath, 'w185');
  const st = stateOf(r, group);
  const sub = [r.year, seasonsText(r), r.is4k ? '4K' : ''].filter(Boolean).join(' · ');
  const inLibrary = !!r.jellyfinItemId && (r.status === 'available' || r.status === 'partial');
  return (
    <div class="me-req-row">
      <Link to={seerrPath(r)} class="me-req-poster" aria-label={t('{title} details', { title: r.title })}>
        {poster ? <img src={poster} alt="" loading="lazy" /> : <span class="art-title" aria-hidden="true">{r.title}</span>}
      </Link>
      <div class="me-req-text">
        <span class="me-req-name">
          <Link to={seerrPath(r)} class="me-req-title">{r.title}</Link>
          <span class={'type-pill ' + (r.mediaType === 'tv' ? 'is-series' : 'is-movie')}>{r.mediaType === 'tv' ? t('Series') : t('Movie')}</span>
        </span>
        {sub && <span class="me-req-sub">{sub}</span>}
      </div>
      <div class="me-req-state">
        <span><span class={'dot ' + st.dot} aria-hidden="true" />{st.text}</span>
        <span>{st.when}</span>
      </div>
      <div class="me-req-actions">
        {inLibrary && <Link to={`/details/${r.jellyfinItemId}`} class="btn btn-secondary is-sm">{t('Open in library')}</Link>}
        <Link to={seerrPath(r)} class="btn icon-btn is-bare" aria-label={t('More about {title}', { title: r.title })}><Icon name="chevronRight" size={20} /></Link>
      </div>
    </div>
  );
}
