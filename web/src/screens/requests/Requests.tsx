import { useEffect, useRef, useState } from 'preact/hooks';
import { requestList, seerrSearch, seerrTitles, type MyRequest, type RequestFilter, type SeerrItem, type SeerrTitle, type TitlesKind } from '../../api/seedy';
import { meStore } from '../../lib/app-state';
import { t } from '../../lib/i18n';
import { Link, setQuery, useLocation } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { Row } from '../../ui/Row';
import { Icon } from '../../ui/icons';
import { MenuButton, MenuItem } from '../../ui/overlay';
import { Header } from '../../ui/navbar/Header';
import { EmptyState, ErrorState, GridSkeleton, Lazy, PageHead, RowSkeleton, Sentinel, useRequests } from '../browse/parts';
import {
  ApprovalRow, ListRow, MineRow, RequestsFrame, RowSkeletons, TitleCard, titleKey, useRequestActions, useSummary
} from './parts';
import '../browse/lists.css';

/* Requests (Requests board): Seerr inside Seedy. Discover shows the person's latest requests, what waits for their
   approval when they manage requests, and rows of titles to request; My requests and All requests list requests
   with Seerr's filters; a search looks Seerr up for anything else. */

const ROWS: { kind: TitlesKind; title: string }[] = [
  { kind: 'trending', title: 'Trending' },
  { kind: 'upcoming-movies', title: 'Upcoming movies' },
  { kind: 'upcoming-shows', title: 'Upcoming shows' },
  { kind: 'popular-movies', title: 'Popular movies' },
  { kind: 'popular-shows', title: 'Popular shows' }
];
const isUpcoming = (k: TitlesKind) => k === 'upcoming-movies' || k === 'upcoming-shows';

function NotSetUp() {
  return (
    <div class="page reqs-page">
      <Header active="requests" />
      <main class="reqs-main">
        <div class="reqs-head"><div class="reqs-title"><h1>{t('Requests')}</h1></div></div>
        <div class="reqs-pad">
          <EmptyState icon="inbox" title={t('Requests aren’t set up')} text={t('Your server isn’t connected to Seerr, or your profile can’t use it. Ask your admin.')} />
        </div>
      </main>
    </div>
  );
}

/* ───────── Discover ───────── */

export function RequestsDiscover() {
  const me = useStore(meStore);
  if (!me.requests.connected) return <NotSetUp />;
  return (
    <RequestsFrame tab="discover" search={(q) => <SearchResults query={q} />}>
      <TopLists />
      {ROWS.map((r, i) => (
        <Lazy key={r.kind} eager={i < 2} placeholder={<RowSkeleton title={t(r.title)} width={180} />}>
          {() => <TitlesRow kind={r.kind} title={t(r.title)} />}
        </Lazy>
      ))}
    </RequestsFrame>
  );
}

/* Your requests, and Waiting for your approval beside it for people who manage requests. */
function TopLists() {
  const me = useStore(meStore);
  const summary = useSummary();
  const canManage = !!summary?.canManage;
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
    <div class="reqs-columns">
      <section aria-labelledby="reqs-mine-h" class="reqs-col is-mine">
        <div class="reqs-col-head">
          <h2 id="reqs-mine-h">{t('Your requests')}</h2>
          {!!mine.data?.total && mine.data.total > 3 && <Link to="/requests/my" class="row-link">{t('See all')}</Link>}
        </div>
        <div class="reqs-list">
          {mine.loading && !mine.data ? <RowSkeletons count={3} />
            : mine.error ? <Note icon="alert" text={t('Seerr didn’t answer. It may be restarting.')} action={<button type="button" class="btn btn-secondary is-xs" onClick={mine.reload}>{t('Try again')}</button>} />
            : !hasAccount ? <Note icon="user" text={t('Your profile isn’t linked to a Seerr account yet. Ask your admin to link it.')} />
            : !mine.data?.results.length ? <Note icon="inbox" text={t('Nothing requested yet. Pick something below, or search for it above.')} />
            : mine.data.results.map((r) => <MineRow key={r.requestId} r={r} />)}
        </div>
      </section>

      {canManage && (
        <section aria-labelledby="reqs-appr-h" class="reqs-col is-approvals">
          <div class="reqs-col-head">
            <h2 id="reqs-appr-h">{t('Waiting for your approval')}</h2>
            <span class="reqs-col-sub">{t('Admins only')}</span>
            {waitingTotal > waiting.length && <Link to="/requests/all?filter=pending" class="row-link reqs-col-link">{t('See all {n}', { n: waitingTotal })}</Link>}
          </div>
          <div class="reqs-list">
            {pending.loading && !pending.data ? <RowSkeletons count={2} approval />
              : pending.error ? <Note icon="alert" text={t('Couldn’t load the requests waiting for approval.')} action={<button type="button" class="btn btn-secondary is-xs" onClick={pending.reload}>{t('Try again')}</button>} />
              : !waiting.length ? <Note icon="check" text={t('All caught up. New requests that need you show up here.')} />
              : waiting.map((r) => <ApprovalRow key={r.requestId} r={r} actions={actions} />)}
          </div>
        </section>
      )}
    </div>
  );
}

function Note({ icon, text, action }: { icon: 'inbox' | 'alert' | 'check' | 'user'; text: string; action?: preact.ComponentChildren }) {
  return (
    <div class="reqs-note">
      <Icon name={icon} size={18} />
      <span>{text}</span>
      {action}
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
        {items.slice(0, 20).map((s) => <TitleCard key={titleKey(s)} item={s} upcoming={isUpcoming(kind)} req={req} width={180} />)}
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
  const paged = useTitlePages((page) => seerrTitles(row!.kind, page), [kind], !!row && me.requests.connected);
  if (!me.requests.connected) return <NotSetUp />;
  return (
    <div class="page list-page reqs-page">
      <Header active="requests" />
      <main class="page-main">
        <PageHead
          title={row ? t(row.title) : t('List not found')}
          lede={<Link to="/requests" class="list-seed"><Icon name="chevronLeft" size={14} stroke={2.4} />{t('Back to requests')}</Link>}
        />
        {!row ? <EmptyState icon="searchX" title={t('This list isn\'t here')} text={t('The link may be old.')} />
          : <TitleGrid paged={paged} upcoming={isUpcoming(row.kind)} req={req} empty={<EmptyState icon="clock" title={t('Nothing here right now')} text={t('Check back later for new titles.')} />} />}
        {req.dialog}
      </main>
    </div>
  );
}

/* Pages of titles from Seerr, without repeats, loaded as the grid scrolls. */
function useTitlePages(load: (page: number) => Promise<{ results: SeerrTitle[]; pages?: number }>, deps: unknown[], enabled = true) {
  const [items, setItems] = useState<SeerrTitle[]>([]);
  const [page, setPage] = useState(1);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | undefined>();
  const seen = useRef(new Set<string>());
  const gen = useRef(0);

  const more = async (p: number, g = gen.current) => {
    setBusy(true);
    setError(undefined);
    try {
      const r = await load(p);
      if (g !== gen.current) return;
      const fresh = (r.results ?? []).filter((s) => !seen.current.has(titleKey(s)));
      fresh.forEach((s) => seen.current.add(titleKey(s)));
      setItems((list) => [...list, ...fresh]);
      setPage(p + 1);
      if (!r.results?.length || (r.pages !== undefined && p >= r.pages)) setDone(true);
    } catch (e) {
      if (g === gen.current) setError(e as Error);
    } finally {
      if (g === gen.current) setBusy(false);
    }
  };
  useEffect(() => {
    gen.current += 1;
    seen.current = new Set();
    setItems([]);
    setPage(1);
    setDone(false);
    if (enabled) void more(1, gen.current);
  }, [...deps, enabled]);
  return { items, busy, done, error, loadMore: () => { if (!busy && !done) void more(page); } };
}

function TitleGrid({ paged, upcoming, req, empty }: {
  paged: ReturnType<typeof useTitlePages>;
  upcoming: boolean;
  req: ReturnType<typeof useRequests>;
  empty: preact.ComponentChildren;
}) {
  if (paged.busy && !paged.items.length) return <GridSkeleton count={14} poster />;
  if (paged.error && !paged.items.length) return <ErrorState error={paged.error} retry={paged.loadMore} title={t('Couldn\'t reach Seerr')} />;
  if (!paged.items.length) return <>{empty}</>;
  return (
    <>
      <div class="poster-grid">
        {paged.items.map((s) => <TitleCard key={titleKey(s)} item={s} upcoming={upcoming} req={req} />)}
      </div>
      {!paged.done && !paged.error && <Sentinel onVisible={paged.loadMore} token={paged.items.length} disabled={paged.busy} />}
      {paged.busy && <div class="list-loading"><span class="spinner" aria-label={t('Loading more')} /></div>}
      {paged.error && <div class="grid-more"><button type="button" class="btn btn-secondary" onClick={paged.loadMore}><Icon name="refresh" size={18} />{t('Load more')}</button></div>}
    </>
  );
}

/* ───────── Search ───────── */

function SearchResults({ query }: { query: string }) {
  const req = useRequests();
  const [total, setTotal] = useState<number | undefined>();
  const paged = useTitlePages(
    (page) => seerrSearch(query, page).then((r) => { setTotal(r.total); return { results: r.results as SeerrItem[], pages: Math.ceil((r.total || 0) / 20) }; }),
    [query]
  );
  return (
    <section class="reqs-pad reqs-results" aria-labelledby="reqs-results-h">
      <div class="reqs-col-head">
        <h2 id="reqs-results-h">{t('Results for “{query}”', { query })}</h2>
        {total !== undefined && <span class="reqs-col-sub">{total === 1 ? t('1 title') : t('{n} titles', { n: total })}</span>}
      </div>
      <TitleGrid
        paged={paged}
        upcoming={false}
        req={req}
        empty={<EmptyState icon="searchX" title={t('Nothing found for “{query}”', { query })} text={t('Try another title, or check the spelling.')} />}
      />
      {req.dialog}
    </section>
  );
}

/* ───────── My requests · All requests ───────── */

const FILTERS: { value: RequestFilter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'pending', label: 'Waiting for approval' },
  { value: 'processing', label: 'On the way' },
  { value: 'available', label: 'Available' },
  { value: 'failed', label: 'Failed' }
];
const SORTS: { value: 'added' | 'modified'; label: string }[] = [
  { value: 'added', label: 'Newest first' },
  { value: 'modified', label: 'Recently changed' }
];
const PAGE = 20;

export function RequestsList({ scope }: { scope: 'mine' | 'all' }) {
  const me = useStore(meStore);
  const summary = useSummary();
  const loc = useLocation();
  const filter = (FILTERS.find((f) => f.value === loc.query.get('filter'))?.value ?? 'all') as RequestFilter;
  const sort = loc.query.get('sort') === 'modified' ? 'modified' : 'added';
  const everyone = scope === 'all';
  const canManage = !!summary?.canManage;

  const [items, setItems] = useState<MyRequest[]>([]);
  const [total, setTotal] = useState(0);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error' | 'more'>('loading');
  const [tick, setTick] = useState(0);
  const gen = useRef(0);

  useEffect(() => {
    if (!me.requests.connected) return;
    const g = ++gen.current;
    setStatus('loading');
    requestList({ scope, filter, sort, skip: 0, take: PAGE }).then(
      (r) => { if (g !== gen.current) return; setItems(r.results); setTotal(r.total); setStatus('ready'); },
      () => { if (g === gen.current) setStatus('error'); }
    );
  }, [scope, filter, sort, tick, me.requests.connected]);

  const loadMore = () => {
    if (status !== 'ready' || items.length >= total) return;
    const g = gen.current;
    setStatus('more');
    requestList({ scope, filter, sort, skip: items.length, take: PAGE }).then(
      (r) => {
        if (g !== gen.current) return;
        const seen = new Set(items.map((i) => i.requestId));
        setItems((list) => [...list, ...r.results.filter((i) => !seen.has(i.requestId))]);
        setTotal(r.total);
        setStatus('ready');
      },
      () => { if (g === gen.current) setStatus('ready'); }
    );
  };

  const actions = useRequestActions((r, next) => {
    // Leave the row where it is unless it no longer belongs in this filter.
    const keep = next && (filter === 'all' || (filter === 'pending' ? next.requestStatus === 'pending' : filter !== 'failed' || next.requestStatus === 'failed'));
    if (keep) setItems((list) => list.map((i) => (i.requestId === r.requestId ? next : i)));
    else {
      setItems((list) => list.filter((i) => i.requestId !== r.requestId));
      setTotal((n) => Math.max(0, n - 1));
    }
  });

  if (!me.requests.connected) return <NotSetUp />;
  const blocked = everyone && summary && !summary.canViewAll;
  const busy = status === 'loading';
  const label = FILTERS.find((f) => f.value === filter)!.label;

  return (
    <RequestsFrame tab={everyone ? 'all' : 'my'} search={(q) => <SearchResults query={q} />}>
      <div class="reqs-pad reqs-listing">
        <div class="reqs-filters">
          <div role="tablist" aria-label={t('Filter requests')} class="reqs-filter-tabs">
            {FILTERS.map((f) => (
              <button
                key={f.value}
                type="button"
                role="tab"
                class="tab"
                aria-selected={filter === f.value ? 'true' : 'false'}
                tabIndex={filter === f.value ? 0 : -1}
                onClick={() => setQuery({ filter: f.value === 'all' ? undefined : f.value })}
              >
                {t(f.label)}
                {f.value === 'pending' && everyone && !!summary?.pending && <span class="reqs-count is-pending">{summary.pending}</span>}
              </button>
            ))}
          </div>
          <div class="reqs-filters-end">
            {!busy && status !== 'error' && <span class="reqs-total" aria-live="polite">{total === 1 ? t('1 request') : t('{n} requests', { n: total })}</span>}
            <MenuButton
              width={240}
              align="end"
              button={({ ref, open, toggle }) => (
                <button ref={ref} type="button" class="btn btn-secondary" aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={toggle}>
                  <Icon name="sort" size={18} />
                  {t(SORTS.find((s) => s.value === sort)!.label)}
                </button>
              )}
            >
              {(close) => (
                <>
                  <span class="menu-label">{t('Sort by')}</span>
                  {SORTS.map((s) => (
                    <MenuItem key={s.value} label={t(s.label)} pressed={s.value === sort} onSelect={() => { close(); setQuery({ sort: s.value === 'added' ? undefined : s.value }); }} />
                  ))}
                </>
              )}
            </MenuButton>
          </div>
        </div>

        {blocked ? (
          <EmptyState icon="lock" title={t('Only request managers see everyone’s requests')} text={t('Your Seerr account can see your own requests under My requests.')} action={<Link to="/requests/my" class="btn btn-secondary">{t('My requests')}</Link>} />
        ) : busy ? (
          <div class="reqs-list"><RowSkeletons count={6} /></div>
        ) : status === 'error' ? (
          <ErrorState title={t('Couldn’t load requests')} retry={() => setTick((n) => n + 1)} />
        ) : !items.length ? (
          <EmptyState
            icon="inbox"
            title={filter === 'all' ? (everyone ? t('No requests yet') : t('You haven’t requested anything yet')) : t('No requests here')}
            text={filter === 'all'
              ? (everyone ? t('Requests from everyone on the server show up here.') : t('Titles you request from Discover or Search show up here, with where they are on the way to your library.'))
              : t('Nothing matches “{filter}”. Try All.', { filter: t(label) })}
            action={filter === 'all' ? <Link to="/requests" class="btn btn-secondary">{t('Find something to request')}</Link> : <button type="button" class="btn btn-secondary" onClick={() => setQuery({ filter: undefined })}>{t('Show all')}</button>}
          />
        ) : (
          <>
            <div class="reqs-list">
              {items.map((r) => <ListRow key={r.requestId} r={r} actions={actions} everyone={everyone} canManage={canManage} />)}
            </div>
            {items.length < total && <Sentinel onVisible={loadMore} token={items.length} disabled={status !== 'ready'} />}
            {status === 'more' && <div class="list-loading"><span class="spinner" aria-label={t('Loading more')} /></div>}
          </>
        )}
      </div>
    </RequestsFrame>
  );
}
