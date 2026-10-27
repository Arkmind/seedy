import type { ComponentChildren } from 'preact';
import { useState } from 'preact/hooks';
import { musicRow, musicSearch, type MusicArtistRef, type MusicRelease, type MusicRow } from '../../api/media';
import { dayOf, daysFromToday, fmtDayMonth, fmtMonthYear, fmtWeekdayDate } from '../../lib/format';
import { t } from '../../lib/i18n';
import { Link } from '../../lib/router';
import { useAsync } from '../../lib/store';
import { Row } from '../../ui/Row';
import { Icon } from '../../ui/icons';
import { ListFrame } from '../browse/Lists';
import { EmptyState, ErrorState, Lazy } from '../browse/parts';
import { Frame, NotSetUp, useServices } from './frame';
import { AlbumArt, cardStatus, initials, MediaTopLists } from './media-parts';
import { RequestMusicArtist } from './music-dialog';

/* Requests · Music (RequestsMusic board): Lidarr inside Seedy. Your music requests and approvals, then new releases
   from the artists the person plays, albums missing from artists on the server, what's trending on ListenBrainz and
   Lidarr's calendar. Every album opens the artist's request window. */

const ROWS: { row: MusicRow; title: string; src: string }[] = [
  { row: 'new', title: 'New from artists you play', src: 'MusicBrainz releases for artists on the server' },
  { row: 'missing', title: 'Missing from artists you have', src: 'Albums the server doesn’t have yet' },
  { row: 'trending', title: 'Trending this week', src: 'From ListenBrainz' },
  { row: 'soon', title: 'Coming soon', src: 'Lidarr calendar for monitored artists' }
];

const TYPE_LABEL: Record<string, string> = { album: 'Album', ep: 'EP', single: 'Single' };

/* "Fri 16 Oct" within a month either way, "4 Dec" this year, "Jan 2027", else the year. */
function releaseWhen(date?: string): { text: string; soon: boolean } {
  const d = dayOf(date && date.length === 4 ? undefined : date);
  if (!d) return { text: date?.slice(0, 4) ?? '', soon: false };
  const days = daysFromToday(d);
  if (Math.abs(days) <= 31) return { text: fmtWeekdayDate(d), soon: days >= 0 && days <= 14 };
  if (days > 0 && d.getFullYear() === new Date().getFullYear()) return { text: fmtDayMonth(d), soon: false };
  if (days > 0 && date!.length >= 7) return { text: fmtMonthYear(d), soon: false };
  return { text: String(d.getFullYear()), soon: false };
}

export function releaseMeta(r: MusicRelease, row?: MusicRow): { text: string; accent: boolean } {
  const artist = r.artist ?? '';
  if (row === 'missing' && r.of) return { text: t('{artist} · you have {n} of {total}', { artist, n: r.have ?? 0, total: r.of }), accent: false };
  if (row === 'trending') return { text: [artist, r.date?.slice(0, 4)].filter(Boolean).join(' · '), accent: false };
  const when = releaseWhen(r.date);
  return { text: [artist, when.text].filter(Boolean).join(' · '), accent: r.upcoming && when.soon };
}

export interface MusicTarget { mbid: string; name?: string; preselect?: string; imageUrl?: string }

export function useMusicDialog() {
  const [target, setTarget] = useState<MusicTarget | null>(null);
  return {
    open: (t: MusicTarget) => setTarget(t),
    dialog: target ? <RequestMusicArtist key={target.mbid} target={target} onClose={() => setTarget(null)} /> : null
  };
}

/* A 180 px album: cover, type, state, and Request · More info on hover. */
export function AlbumCard({ r, meta, onOpen, width }: { r: MusicRelease; meta: { text: string; accent: boolean }; onOpen: (r: MusicRelease, request: boolean) => void; width?: number }) {
  const status = cardStatus(r.status, r.requestedBy);
  const onServer = r.status === 'server' && r.itemId;
  const canAsk = r.status === 'none' && !!r.artistMbid;
  const type = r.upcoming ? t('Upcoming') : t(TYPE_LABEL[r.type] ?? 'Album');
  return (
    <div class="card reqs-album-card" style={width ? { flex: `0 0 ${width}px`, width: width + 'px' } : undefined}>
      <div class="reqs-cover" tabIndex={-1}>
        <AlbumArt seed={r.mbid} src={r.coverUrl} text={r.title} />
        <span class={'reqs-type is-' + (r.upcoming ? 'upcoming' : r.type)}>{type}</span>
        {status && <span class="reqs-cover-status"><span class="reqs-dot" style={{ background: status.dot }} />{status.text}</span>}
        <div class="reqs-cover-hover">
          {canAsk && (
            <button type="button" class="btn btn-primary is-sm is-block" aria-haspopup="dialog" onClick={() => onOpen(r, true)}>
              <Icon name="plus" size={18} stroke={2.6} />
              {t('Request')}
            </button>
          )}
          {onServer
            ? <Link to={`/details/${r.itemId}`} class="btn poster-more">{t('Open')}</Link>
            : <button type="button" class="btn poster-more" disabled={!r.artistMbid} onClick={() => onOpen(r, false)}>{t('More info')}</button>}
        </div>
      </div>
      {onServer
        ? <Link to={`/details/${r.itemId}`} class="card-text reqs-card-text" tabIndex={-1}><Text r={r} meta={meta} /></Link>
        : <button type="button" class="card-text reqs-card-text" tabIndex={-1} onClick={() => r.artistMbid && onOpen(r, false)}><Text r={r} meta={meta} /></button>}
    </div>
  );
}

function Text({ r, meta }: { r: MusicRelease; meta: { text: string; accent: boolean } }) {
  return (
    <>
      <span class="card-title is-14">{r.title}</span>
      <span class="card-meta" style={{ fontWeight: 600, color: meta.accent ? 'var(--seedy-accent)' : undefined }}>{meta.text}</span>
    </>
  );
}

function AlbumSkeletons({ count = 7, grid }: { count?: number; grid?: boolean }) {
  return (
    <>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} class="card reqs-album-card" style={grid ? undefined : { flex: '0 0 180px', width: '180px' }} aria-hidden="true">
          <span class="reqs-cover skel" />
          <span class="card-text"><span class="skel skel-line" style={{ width: '70%' }} /><span class="skel skel-line" style={{ width: '50%' }} /></span>
        </div>
      ))}
    </>
  );
}

function RowHead({ title, src }: { title: string; src: string }) {
  return <Row title={title} sub={src}><AlbumSkeletons /></Row>;
}

function MusicRowView({ row, title, src, open }: { row: MusicRow; title: string; src: string; open: (r: MusicRelease, request: boolean) => void }) {
  const state = useAsync(() => musicRow(row), [row]);
  if (state.loading && !state.data) return <RowHead title={title} src={src} />;
  const items = state.data?.results ?? [];
  if (!items.length) return null;
  return (
    <Row title={title} sub={src} seeAll={`/requests/music/discover/${row}`} label={title}>
      {items.slice(0, 20).map((r) => <AlbumCard key={r.mbid} r={r} meta={releaseMeta(r, row)} onOpen={open} width={180} />)}
    </Row>
  );
}

const openAlbum = (dialog: ReturnType<typeof useMusicDialog>) => (r: MusicRelease, request: boolean) => {
  if (r.artistMbid) dialog.open({ mbid: r.artistMbid, name: r.artist, preselect: request || r.status === 'none' ? r.mbid : undefined });
};

function MusicFrame({ children }: { children: ComponentChildren }) {
  return (
    <Frame
      service="music"
      tab="discover"
      placeholder={t('Find any artist or album to request')}
      label={t('Search MusicBrainz for artists and albums')}
      results={(query) => <MusicResults query={query} />}
    >
      {children}
    </Frame>
  );
}

export function MusicDiscover() {
  const dialog = useMusicDialog();
  const open = openAlbum(dialog);
  return (
    <MusicFrame>
      <MediaTopLists kind="music" />
      {ROWS.map((r, i) => (
        <Lazy key={r.row} eager={i < 2} placeholder={<RowHead title={t(r.title)} src={t(r.src)} />}>
          {() => <MusicRowView row={r.row} title={t(r.title)} src={t(r.src)} open={open} />}
        </Lazy>
      ))}
      {dialog.dialog}
    </MusicFrame>
  );
}

/* ── Search ── */

function ArtistCard({ a, onOpen }: { a: MusicArtistRef; onOpen: () => void }) {
  return (
    <div class="card reqs-artist-card">
      <button type="button" class="reqs-artist-art" aria-haspopup="dialog" aria-label={t('Request {name}', { name: a.name })} onClick={onOpen}>
        <AlbumArt seed={a.mbid} src={a.imageUrl} text={initials(a.name)} round />
      </button>
      <button type="button" class="card-text reqs-card-text" tabIndex={-1} onClick={onOpen}>
        <span class="card-title is-14">{a.name}</span>
        <span class="card-meta">{[a.type === 'Group' ? t('Band') : t('Artist'), a.disambiguation || a.area].filter(Boolean).join(' · ')}</span>
      </button>
    </div>
  );
}

function MusicResults({ query }: { query: string }) {
  const dialog = useMusicDialog();
  const state = useAsync(() => musicSearch(query), [query]);
  const open = openAlbum(dialog);
  let body: ComponentChildren;
  if (state.loading && !state.data) body = <div class="reqs-grid is-albums"><AlbumSkeletons count={12} grid /></div>;
  else if (state.error) body = <ErrorState error={state.error} retry={state.reload} title={t('Couldn’t search for music')} />;
  else if (!state.data?.artists.length && !state.data?.albums.length) body = <EmptyState icon="searchX" title={t('Nothing found for “{query}”', { query })} text={t('Try another name, or check the spelling.')} />;
  else {
    body = (
      <>
        {!!state.data.artists.length && (
          <section class="reqs-results-part" aria-label={t('Artists')}>
            <h3 class="reqs-results-h">{t('Artists')}</h3>
            <div class="reqs-grid is-albums">
              {state.data.artists.map((a) => <ArtistCard key={a.mbid} a={a} onOpen={() => dialog.open({ mbid: a.mbid, name: a.name, imageUrl: a.imageUrl })} />)}
            </div>
          </section>
        )}
        {!!state.data.albums.length && (
          <section class="reqs-results-part" aria-label={t('Albums')}>
            <h3 class="reqs-results-h">{t('Albums')}</h3>
            <div class="reqs-grid is-albums">
              {state.data.albums.map((r) => <AlbumCard key={r.mbid} r={r} meta={releaseMeta(r)} onOpen={open} />)}
            </div>
          </section>
        )}
      </>
    );
  }
  return (
    <section class="reqs-pad reqs-results" aria-labelledby="reqs-results-h">
      <div class="reqs-col-head">
        <h2 id="reqs-results-h" class="row-title">{t('Results for “{query}”', { query })}</h2>
      </div>
      {body}
      {dialog.dialog}
    </section>
  );
}

/* ── See all ── */

export function MusicBrowse({ row }: { row: string }) {
  const services = useServices();
  const def = ROWS.find((r) => r.row === row);
  const dialog = useMusicDialog();
  const state = useAsync(() => (def && services.includes('music') ? musicRow(def.row) : Promise.resolve(null)), [row]);
  const open = openAlbum(dialog);
  let body: ComponentChildren;
  if (!def) body = <EmptyState icon="searchX" title={t('This list isn\'t here')} text={t('The link may be old.')} />;
  else if (!services.includes('music')) body = <NotSetUp />;
  else if (state.loading && !state.data) body = <div class="reqs-grid is-albums"><AlbumSkeletons count={14} grid /></div>;
  else if (state.error) body = <ErrorState error={state.error} retry={state.reload} />;
  else if (!state.data?.results.length) body = <EmptyState icon="clock" title={t('Nothing here right now')} text={t('Check back later for new albums.')} />;
  else body = <div class="reqs-grid is-albums">{state.data.results.map((r) => <AlbumCard key={r.mbid} r={r} meta={releaseMeta(r, def.row)} onOpen={open} />)}</div>;
  return (
    <ListFrame
      active="requests"
      title={def ? t(def.title) : t('List not found')}
      lede={<Link to="/requests/music" class="list-seed"><Icon name="chevronLeft" size={14} stroke={2.4} />{t('Back to music requests')}</Link>}
    >
      {body}
      {dialog.dialog}
    </ListFrame>
  );
}
