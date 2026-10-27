import type { ComponentChildren } from 'preact';
import { useState } from 'preact/hooks';
import { requestList, seerrSearch, seerrTitles, type TitlesKind } from '../../api/seedy';
import { meStore } from '../../lib/app-state';
import { t } from '../../lib/i18n';
import { Link } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { Row } from '../../ui/Row';
import { Icon } from '../../ui/icons';
import { ListFrame } from '../browse/Lists';
import { EmptyState, Lazy, RowSkeleton, SeerrGrid, seerrKey, useRequests, useSeerrPages } from '../browse/parts';
import { Frame, NotSetUp, type Tab } from './frame';
import { Note } from './media-parts';
import { ApprovalRow, MineRow, RowSkeletons, TitleCard, useRequestActions, useSummary } from './parts';

/* Requests · Movies and shows (Requests board): Seerr inside Seedy. Discover shows the person's latest requests, what
   waits for their approval when they manage requests, and rows of titles to request; a search looks Seerr up for
   anything else. My requests and All requests are shared with music and books (mine.tsx). */

const ROWS: { kind: TitlesKind; title: string }[] = [
  { kind: 'trending', title: 'Trending' },
  { kind: 'upcoming-movies', title: 'Upcoming movies' },
  { kind: 'upcoming-shows', title: 'Upcoming shows' },
  { kind: 'popular-movies', title: 'Popular movies' },
  { kind: 'popular-shows', title: 'Popular shows' }
];
const isUpcoming = (k: TitlesKind) => k === 'upcoming-movies' || k === 'upcoming-shows';

/* The movies and shows search: Seerr. */
function MoviesFrame({ tab, children }: { tab: Tab; children: ComponentChildren }) {
  return (
    <Frame
      service="movies"
      tab={tab}
      placeholder={t('Find any movie or show to request')}
      label={t('Search Seerr for movies and shows')}
      results={(query) => <SearchResults query={query} />}
    >
      {children}
    </Frame>
  );
}

function SearchResults({ query }: { query: string }) {
  const req = useRequests();
  const pages = useSeerrPages((page) => seerrSearch(query, page).then((r) => ({ ...r, pages: Math.ceil(r.total / 20) })), [query]);
  return (
    <section class="reqs-pad reqs-results" aria-labelledby="reqs-results-h">
      <div class="reqs-col-head">
        <h2 id="reqs-results-h" class="row-title">{t('Results for “{query}”', { query })}</h2>
        {pages.total !== undefined && <span class="row-sub">{pages.total === 1 ? t('1 title') : t('{n} titles', { n: pages.total })}</span>}
      </div>
      <SeerrGrid
        pages={pages}
        empty={<EmptyState icon="searchX" title={t('Nothing found for “{query}”', { query })} text={t('Try another title, or check the spelling.')} />}
        render={(s) => <TitleCard item={s} upcoming={false} req={req} />}
      />
      {req.dialog}
    </section>
  );
}

/* ───────── Discover ───────── */

export function RequestsDiscover() {
  return (
    <MoviesFrame tab="discover">
      <TopLists />
      {ROWS.map((r, i) => (
        <Lazy key={r.kind} eager={i < 2} placeholder={<RowSkeleton title={t(r.title)} width={180} />}>
          {() => <TitlesRow kind={r.kind} title={t(r.title)} />}
        </Lazy>
      ))}
    </MoviesFrame>
  );
}

/* Your requests, and Waiting for your approval beside it for people who manage requests. */
function TopLists() {
  const me = useStore(meStore);
  const canManage = !!useSummary()?.canManage;
  const hasAccount = !!me.requests.account || me.requests.canRequest;
  const mine = useAsync(() => (hasAccount ? requestList({ scope: 'mine', take: 3 }) : Promise.resolve(null)), [hasAccount]);
  const pending = useAsync(() => (canManage ? requestList({ scope: 'all', filter: 'pending', take: 4 }) : Promise.resolve(null)), [canManage]);
  const [gone, setGone] = useState<Set<number>>(new Set());
  const actions = useRequestActions((r) => {
    setGone((g) => new Set(g).add(r.requestId));
    mine.reload();
  });
  const waiting = (pending.data?.results ?? []).filter((r) => !gone.has(r.requestId));
  const waitingTotal = Math.max(0, (pending.data?.total ?? 0) - gone.size);
  const retry = (reload: () => void) => <button type="button" class="btn btn-secondary is-xs" onClick={reload}>{t('Try again')}</button>;

  return (
    <div class="reqs-columns">
      <section aria-labelledby="reqs-mine-h" class="reqs-col is-mine">
        <div class="reqs-col-head">
          <h2 id="reqs-mine-h" class="row-title">{t('Your requests')}</h2>
          {(mine.data?.total ?? 0) > 3 && <Link to="/requests/my" class="row-link">{t('See all')}</Link>}
        </div>
        <div class="reqs-list">
          {mine.loading && !mine.data ? <RowSkeletons count={3} />
            : mine.error ? <Note icon="alert" text={t('Seerr didn’t answer. It may be restarting.')} action={retry(mine.reload)} />
            : !hasAccount ? <Note icon="user" text={t('Your profile isn’t linked to a Seerr account yet. Ask your admin to link it.')} />
            : !mine.data?.results.length ? <Note icon="inbox" text={t('Nothing requested yet. Pick something below, or search for it above.')} />
            : mine.data.results.map((r) => <MineRow key={r.requestId} r={r} />)}
        </div>
      </section>

      {canManage && (
        <section aria-labelledby="reqs-appr-h" class="reqs-col is-approvals">
          <div class="reqs-col-head">
            <h2 id="reqs-appr-h" class="row-title">{t('Waiting for your approval')}</h2>
            <span class="row-sub">{t('Admins only')}</span>
            {waitingTotal > waiting.length && <Link to="/requests/all?filter=pending" class="row-link">{t('See all {n}', { n: waitingTotal })}</Link>}
          </div>
          <div class="reqs-list">
            {pending.loading && !pending.data ? <RowSkeletons count={2} approval />
              : pending.error ? <Note icon="alert" text={t('Couldn’t load the requests waiting for approval.')} action={retry(pending.reload)} />
              : !waiting.length ? <Note icon="check" text={t('All caught up. New requests that need you show up here.')} />
              : waiting.map((r) => <ApprovalRow key={r.requestId} r={r} actions={actions} />)}
          </div>
        </section>
      )}
    </div>
  );
}

function TitlesRow({ kind, title }: { kind: TitlesKind; title: string }) {
  const req = useRequests();
  const state = useAsync(() => seerrTitles(kind, 1), [kind]);
  if (state.loading && !state.data) return <RowSkeleton title={title} width={180} />;
  const items = state.data?.results ?? [];
  if (!items.length) return null;
  return (
    <>
      <Row title={title} seeAll={`/requests/discover/${kind}`} label={title}>
        {items.slice(0, 20).map((s) => <TitleCard key={seerrKey(s)} item={s} upcoming={isUpcoming(kind)} req={req} width={180} />)}
      </Row>
      {req.dialog}
    </>
  );
}

/* ───────── See all: one row as a grid ───────── */

export function RequestsBrowse({ kind }: { kind: string }) {
  const me = useStore(meStore);
  const row = ROWS.find((r) => r.kind === kind);
  const req = useRequests();
  const pages = useSeerrPages((page) => seerrTitles(row!.kind, page), [kind], !!row && me.requests.connected);
  return (
    <ListFrame
      active="requests"
      title={row ? t(row.title) : t('List not found')}
      lede={<Link to="/requests" class="list-seed"><Icon name="chevronLeft" size={14} stroke={2.4} />{t('Back to requests')}</Link>}
    >
      {!row ? <EmptyState icon="searchX" title={t('This list isn\'t here')} text={t('The link may be old.')} />
        : !me.requests.connected ? <NotSetUp />
        : (
          <SeerrGrid
            pages={pages}
            empty={<EmptyState icon="clock" title={t('Nothing here right now')} text={t('Check back later for new titles.')} />}
            render={(s) => <TitleCard item={s} upcoming={isUpcoming(row.kind)} req={req} />}
          />
        )}
      {req.dialog}
    </ListFrame>
  );
}
