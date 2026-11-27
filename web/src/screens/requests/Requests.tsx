import type { ComponentChildren } from 'preact';
import { useState } from 'preact/hooks';
import { requestList, seerrSearch, seerrTitles, type TitlesKind } from '../../api/seedy';
import { meStore } from '../../lib/app-state';
import { t } from '../../lib/i18n';
import { useAsync, useStore } from '../../lib/store';
import { Row } from '../../ui/Row';
import { EmptyState, Lazy, RowSkeleton, SeerrGrid, seerrKey, useRequests, useSeerrPages } from '../browse/parts';
import { BrowseFrame, Frame, NotSetUp, ResultsSection, type Tab } from './frame';
import { Note, RetryButton, RowSkeletons, TopColumns } from './media-parts';
import { ApprovalRow, MineRow, TitleCard, useRequestActions, useSummary } from './parts';
import { plural } from '../../lib/format';
import { useLocation } from '../../lib/router';
import { BrowseResults, DiscoverTools, isBrowsing } from './browse';

/* Requests · Movies and shows (Requests board): Seerr inside Seedy. Discover shows the person's latest requests, what
   waits for their approval when they manage requests, and rows of titles to request; a search looks Seerr up for
   anything else. My requests and All requests are shared with music and books (RequestsMine.tsx). */

const ROWS: { kind: TitlesKind; title: string }[] = [
  { kind: 'trending', title: 'Trending' },
  { kind: 'upcoming-movies', title: 'Upcoming movies' },
  { kind: 'upcoming-shows', title: 'Upcoming shows' },
  { kind: 'popular-movies', title: 'Popular movies' },
  { kind: 'popular-shows', title: 'Popular shows' }
];
const isUpcoming = (k: TitlesKind) => k === 'upcoming-movies' || k === 'upcoming-shows';

/* The movies and shows search: Seerr. */
function MoviesFrame({ tab, tools, children }: { tab: Tab; tools?: ComponentChildren | false; children: ComponentChildren }) {
  return (
    <Frame
      service="movies"
      tab={tab}
      placeholder={t('Find any movie or show to request')}
      label={t('Search Seerr for movies and shows')}
      results={(query) => <SearchResults query={query} />}
      tools={tools}
    >
      {children}
    </Frame>
  );
}

function SearchResults({ query }: { query: string }) {
  const req = useRequests();
  const pages = useSeerrPages((page) => seerrSearch(query, page).then((r) => ({ ...r, pages: Math.ceil(r.total / 20) })), [query]);
  return (
    <ResultsSection query={query} sub={pages.total !== undefined && <span class="row-sub">{plural(pages.total, 'title')}</span>}>
      <SeerrGrid
        pages={pages}
        empty={<EmptyState icon="searchX" title={t('Nothing found for “{query}”', { query })} text={t('Try another title, or check the spelling.')} />}
        render={(s) => <TitleCard item={s} upcoming={false} req={req} />}
      />
      {req.dialog}
    </ResultsSection>
  );
}

/* ───────── Discover ───────── */

export function RequestsDiscover() {
  // A sort or a filter turns the rows into one list of results (RequestsResults board).
  const browsing = isBrowsing(useLocation().query);
  return (
    <MoviesFrame tab="discover" tools={browsing ? false : <DiscoverTools />}>
      {browsing ? <BrowseResults /> : (
        <>
          <TopLists />
          {ROWS.map((r, i) => (
            <Lazy key={r.kind} eager={i < 2} placeholder={<RowSkeleton title={t(r.title)} width={180} />}>
              {() => <TitlesRow kind={r.kind} title={t(r.title)} />}
            </Lazy>
          ))}
        </>
      )}
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
  return (
    <TopColumns
      mineTitle={t('Your requests')}
      mineSeeAll={(mine.data?.total ?? 0) > 3 ? '/requests/my' : undefined}
      mine={mine.loading && !mine.data ? <RowSkeletons count={3} />
        : mine.error ? <Note icon="alert" text={t('Seerr didn’t answer. It may be restarting.')} action={<RetryButton onClick={mine.reload} />} />
        : !hasAccount ? <Note icon="user" text={t('Your profile isn’t linked to a Seerr account yet. Ask your admin to link it.')} />
        : !mine.data?.results.length ? <Note icon="inbox" text={t('Nothing requested yet. Pick something below, or search for it above.')} />
        : mine.data.results.map((r) => <MineRow key={r.requestId} r={r} />)}
      approvals={canManage ? (pending.loading && !pending.data ? <RowSkeletons count={2} approval />
        : pending.error ? <Note icon="alert" text={t('Couldn’t load the requests waiting for approval.')} action={<RetryButton onClick={pending.reload} />} />
        : !waiting.length ? <Note icon="check" text={t('All caught up. New requests that need you show up here.')} />
        : waiting.map((r) => <ApprovalRow key={r.requestId} r={r} actions={actions} />)) : undefined}
      approvalsSeeAll={waitingTotal > waiting.length ? { to: '/requests/all?filter=pending', n: waitingTotal } : undefined}
    />
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
    <BrowseFrame title={row ? t(row.title) : t('List not found')} back="/requests" backLabel={t('Back to requests')}>
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
    </BrowseFrame>
  );
}
