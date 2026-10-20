import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { getItems, getLatest, getResume, getSimilar, getUpcomingEpisodes } from '../../api/jellyfin';
import { tmdbImage } from '../../api/images';
import { seerrDiscover, seerrUpcoming, type SeerrItem } from '../../api/seedy';
import type { BaseItem } from '../../api/types';
import { meStore, viewsStore } from '../../lib/app-state';
import { plural, whenLabel, dayOf } from '../../lib/format';
import { Link, useLocation } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { PosterCard, UpcomingCard, seerrPath } from '../../ui/cards';
import { Header, type HeaderActive } from '../../ui/navbar/Header';
import { Icon } from '../../ui/icons';
import {
  CardGrid, EmptyState, ErrorState, GridSkeleton, ItemGrid, NextUpCard, PageHead, ResumeCard, Sentinel, groupUpcoming,
  useItemList, usePaged, useRequests
} from './parts';
import './lists.css';
import { getNextUpForMe } from '../me/prefs';
import { t } from '../../lib/i18n';

const FIELDS = ['PrimaryImageAspectRatio', 'ProductionYear', 'Genres', 'ChildCount', 'DateCreated', 'SortName', 'Overview'];

export function ListScreen({ kind }: { kind: string }) {
  switch (kind) {
    case 'continue': return <ContinueList />;
    case 'nextup': return <NextUpList />;
    case 'similar': return <SimilarList />;
    case 'genre': return <GenreList />;
    case 'discover': return <DiscoverList />;
    case 'upcoming-movies': return <UpcomingMoviesList />;
    case 'upcoming-shows': return <UpcomingShowsList />;
    case 'latest': return <LatestList />;
    default:
      return (
        <Frame title={t('List not found')}>
          <EmptyState icon="searchX" title={t('This list isn\'t here')} text={t('The link may be old.')} action={<Link to="/" class="btn btn-primary is-sm">{t('Back to home')}</Link>} />
        </Frame>
      );
  }
}

function Frame({ title, count, lede, active = 'home', children }: { title: ComponentChildren; count?: string; lede?: ComponentChildren; active?: HeaderActive; children: ComponentChildren }) {
  return (
    <div class="page list-page">
      <Header active={active} />
      <main class="page-main">
        <PageHead title={title} count={count} lede={lede} />
        {children}
      </main>
    </div>
  );
}

/* Loading / error / empty around a simple (non-paged) list. */
function Body<T>({ state, empty, poster, children }: {
  state: { data?: T[]; loading: boolean; error?: Error; reload: () => void };
  empty: ComponentChildren;
  poster?: boolean;
  children: (items: T[]) => ComponentChildren;
}) {
  if (state.loading && !state.data) return <GridSkeleton count={poster ? 14 : 12} poster={poster} />;
  if (state.error && !state.data) return <ErrorState error={state.error} retry={state.reload} />;
  if (!state.data?.length) return <>{empty}</>;
  return <>{children(state.data)}</>;
}

const countOf = (data?: unknown[]) => (data ? plural(data.length, 'title') : undefined);

/* ───────── Continue watching / Next up ───────── */

function ContinueList() {
  const state = useAsync(() => getResume(100).then((r) => r.Items), []);
  return (
    <Frame title={t('Continue watching')} count={countOf(state.data)}>
      <Body state={state} empty={<EmptyState icon="clock" title={t('Nothing in progress')} text={t('Titles you start will wait for you here.')} />}>
        {(items) => <ItemGrid items={items} render={(it) => <ResumeCard item={it} origin="continue" />} />}
      </Body>
    </Frame>
  );
}

function NextUpList() {
  const state = useAsync(() => getNextUpForMe(100).then((r) => r.Items), []);
  return (
    <Frame title={t('Next up')} count={state.data ? plural(state.data.length, 'show') : undefined}>
      <Body state={state} empty={<EmptyState icon="tv" title={t('You\'re all caught up')} text={t('When a show you watch has a next episode, it shows up here.')} />}>
        {(items) => <ItemGrid items={items} render={(it) => <NextUpCard item={it} origin="nextup" />} />}
      </Body>
    </Frame>
  );
}

/* ───────── Because you watched ───────── */

function SimilarList() {
  const loc = useLocation();
  const seed = loc.query.get('seed') ?? '';
  const name = loc.query.get('name') ?? '';
  const { items, setItems, replace } = useItemList();
  const state = useAsync(() => (seed ? getSimilar(seed, 48).then((r) => r.Items) : Promise.resolve([])), [seed]);
  useEffect(() => { if (state.data) setItems(state.data); }, [state.data]);
  return (
    <Frame
      title={<><span class="lead">{t('Because you watched')}</span> {name || 'this'}</>}
      count={countOf(state.data)}
      lede={seed ? <Link to={`/details/${seed}`} class="list-seed"><Icon name="chevronLeft" size={14} stroke={2.4} />Back to {name || 'the title'}</Link> : undefined}
    >
      <Body state={{ ...state, data: state.data ? items : undefined }} empty={<EmptyState icon="film" title={t('No suggestions yet')} text={t('Jellyfin hasn\'t found anything similar on this server.')} />}>
        {(list) => <CardGrid items={list} onReplace={replace} origin="because" />}
      </Body>
    </Frame>
  );
}

/* ───────── Genre (paged) ───────── */

function GenreList() {
  const loc = useLocation();
  const genre = loc.query.get('genre') ?? '';
  const parentId = loc.query.get('parentId') ?? undefined;
  const views = useStore(viewsStore);
  const view = parentId ? views.find((v) => v.Id === parentId) : undefined;
  const paged = usePaged(
    (start, limit, signal) => getItems({ genres: [genre], parentId, includeItemTypes: ['Movie', 'Series'], sortBy: ['SortName'], startIndex: start, limit, fields: FIELDS }, signal),
    [genre, parentId],
    60
  );
  return (
    <Frame title={genre || t('Genre')} count={paged.items.length || !paged.loading ? plural(paged.total, 'title') : undefined} lede={view ? t('In {library}', { library: view.Name }) : undefined} active={view ? { viewId: view.Id } : 'home'}>
      <PagedGrid paged={paged} empty={<EmptyState icon="film" title={`No ${genre.toLowerCase()} titles`} text={t('Nothing on this server is tagged with this genre yet.')} />} />
    </Frame>
  );
}

function PagedGrid({ paged, empty }: { paged: ReturnType<typeof usePaged>; empty: ComponentChildren }) {
  if (paged.loading && !paged.items.length) return <GridSkeleton count={15} />;
  if (paged.error && !paged.items.length) return <ErrorState error={paged.error} retry={paged.reload} />;
  if (!paged.items.length) return <>{empty}</>;
  return (
    <>
      <CardGrid items={paged.items} onReplace={paged.replace} />
      {!paged.done && <Sentinel onVisible={paged.loadMore} token={paged.items.length} disabled={paged.loading || !!paged.error} />}
      {paged.loading && <div class="list-loading"><span class="spinner" aria-label={t('Loading more')} /></div>}
      <ListFoot shown={paged.items.length} total={paged.total} />
    </>
  );
}

function ListFoot({ shown, total }: { shown: number; total: number }) {
  return (
    <div class="lib-foot list-foot" aria-live="polite">
      <div class="lib-foot-bar" aria-hidden="true"><i style={{ width: `${total ? Math.min(100, (shown / total) * 100) : 100}%` }} /></div>
      Showing {shown} of {total}
    </div>
  );
}

/* ───────── Latest in a library ───────── */

function LatestList() {
  const loc = useLocation();
  const parentId = loc.query.get('parentId') ?? '';
  const views = useStore(viewsStore);
  const view = views.find((v) => v.Id === parentId);
  const { items, setItems, replace } = useItemList();
  const state = useAsync(() => (parentId ? getLatest(parentId, 60) : Promise.resolve([] as BaseItem[])), [parentId]);
  useEffect(() => { if (state.data) setItems(state.data); }, [state.data]);
  return (
    <Frame title={view ? t('Latest {library}', { library: view.Name.toLowerCase() }) : t('Recently added')} count={countOf(state.data)} active={view ? { viewId: view.Id } : 'home'}>
      <Body state={{ ...state, data: state.data ? items : undefined }} empty={<EmptyState icon="film" title={t('Nothing new')} text={t('Titles added to this library show up here.')} />}>
        {(list) => <CardGrid items={list} onReplace={replace} origin="latest" />}
      </Body>
    </Frame>
  );
}

/* ───────── Seerr: Discover and Upcoming movies ───────── */

function DiscoverList() {
  const me = useStore(meStore);
  const req = useRequests();
  const [items, setItems] = useState<SeerrItem[]>([]);
  const [page, setPage] = useState(1);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | undefined>();
  const seen = useRef(new Set<string>());

  const load = async (p: number) => {
    if (busy || done) return;
    setBusy(true);
    setError(undefined);
    try {
      const r = await seerrDiscover(p);
      const fresh = (r.results ?? []).filter((s) => !seen.current.has(s.mediaType + s.tmdbId));
      fresh.forEach((s) => seen.current.add(s.mediaType + s.tmdbId));
      setItems((list) => [...list, ...fresh]);
      setPage(p + 1);
      if (!fresh.length) setDone(true);
    } catch (e) {
      setError(e as Error);
    } finally {
      setBusy(false);
    }
  };
  useEffect(() => { load(1); }, []);

  if (!me.requests.discover && !me.requests.connected) {
    return (
      <Frame title={t('Discover')}>
        <EmptyState icon="inbox" title={t('Requests aren\'t set up')} text={t('An administrator can connect Seerr in Admin › Requests to discover and request new titles.')} />
      </Frame>
    );
  }
  return (
    <Frame title={t('Discover')} lede={t('Not in your library yet · request with one click')}>
      {busy && !items.length ? <GridSkeleton count={14} poster />
        : error && !items.length ? <ErrorState error={error} retry={() => load(page)} title={t('Couldn\'t reach Seerr')} />
        : !items.length ? <EmptyState icon="searchX" title={t('Nothing to discover right now')} text={t('Check back later for new titles.')} />
        : (
          <>
            <div class="poster-grid">
              {items.map((s) => (
                <PosterCard key={s.mediaType + s.tmdbId} item={req.apply(s)} canRequest={req.canRequest} onRequest={req.request} requesting={req.busy(s)} />
              ))}
            </div>
            {!done && !error && <Sentinel onVisible={() => load(page)} token={items.length} disabled={busy} />}
            {busy && <div class="list-loading"><span class="spinner" aria-label={t('Loading more')} /></div>}
            {error && <div class="grid-more"><button type="button" class="btn btn-secondary" onClick={() => load(page)}><Icon name="refresh" size={18} />{t('Load more')}</button></div>}
          </>
        )}
    </Frame>
  );
}

function UpcomingMoviesList() {
  const state = useAsync(() => seerrUpcoming().then((r) => r.results ?? []), []);
  return (
    <Frame title={t('Upcoming movies')} count={countOf(state.data)}>
      <Body poster state={state} empty={<EmptyState icon="clock" title={t('Nothing coming up')} text={t('Requested movies with a release date show up here.')} />}>
        {(items) => (
          <div class="poster-grid">
            {items.map((u) => {
              const w = whenLabel(dayOf(u.releaseDate)!);
              return <UpcomingCard key={u.tmdbId} title={u.title} posterSrc={tmdbImage(u.posterPath, 'w342')} when={w.text} isToday={w.isToday} to={u.jellyfinItemId ? `/details/${u.jellyfinItemId}` : seerrPath(u)} />;
            })}
          </div>
        )}
      </Body>
    </Frame>
  );
}

function UpcomingShowsList() {
  const state = useAsync(() => getUpcomingEpisodes(300).then((r) => groupUpcoming(r.Items)), []);
  return (
    <Frame title={t('Upcoming shows')} count={state.data ? plural(state.data.length, 'show') : undefined}>
      <Body poster state={state} empty={<EmptyState icon="tv" title={t('No new episodes scheduled')} text={t('Upcoming episodes of shows in your library show up here once they\'re announced.')} />}>
        {(groups) => (
          <div class="poster-grid">
            {groups.map((g) => <UpcomingCard key={g.key} title={g.title} posterSrc={g.poster} line={g.line} when={g.when} isToday={g.isToday} to={g.seriesId ? `/details/${g.seriesId}` : '/'} />)}
          </div>
        )}
      </Body>
    </Frame>
  );
}
