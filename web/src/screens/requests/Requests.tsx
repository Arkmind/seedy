import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { requestList, seerrSearch, seerrTitles, type RequestFilter, type SeerrRequest, type TitlesKind } from '../../api/seedy';
import { meStore } from '../../lib/app-state';
import { t } from '../../lib/i18n';
import { Link, setQuery, useLocation } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { Row } from '../../ui/Row';
import { Icon, type IconName } from '../../ui/icons';
import { MenuButton, MenuItem } from '../../ui/overlay';
import { Header } from '../../ui/navbar/Header';
import { ListFrame } from '../browse/Lists';
import { EmptyState, ErrorState, Lazy, RowSkeleton, SeerrGrid, Sentinel, seerrKey, useRequests, useSeerrPages } from '../browse/parts';
import { ApprovalRow, ListRow, MineRow, RowSkeletons, TitleCard, useRequestActions, useSummary } from './parts';

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

type Tab = 'discover' | 'my' | 'all';
const TABS: { id: Tab; path: string; label: string }[] = [
  { id: 'discover', path: '/requests', label: 'Discover' },
  { id: 'my', path: '/requests/my', label: 'My requests' },
  { id: 'all', path: '/requests/all', label: 'All requests' }
];

/* ───────── Page frame ───────── */

/* The header and the page's title, with Open in Seerr. */
function Shell({ children }: { children: ComponentChildren }) {
  const seerr = useStore(meStore).requests.seerrUrl?.trim();
  return (
    <div class="page">
      <Header active="requests" />
      <main class="reqs-main">
        <div class="page-title-row reqs-pad">
          <div class="page-title">
            <h1>{t('Requests')}</h1>
            <span class="page-count">{t('From Seerr · ask for anything the server doesn’t have')}</span>
          </div>
          {seerr && (
            <a href={seerr} target="_blank" rel="noreferrer" class="btn btn-secondary reqs-open">
              {t('Open in Seerr')}
              <Icon name="external" size={16} stroke={2.2} />
            </a>
          )}
        </div>
        {children}
      </main>
    </div>
  );
}

const NotSetUp = () => <EmptyState icon="inbox" title={t('Requests aren\'t set up')} text={t('Your server isn’t connected to Seerr, or your profile can’t use it. Ask your admin.')} />;

/* The Seerr search and the Discover · My requests · All requests tabs. While there's a search, its results take the
   place of the tab. */
function Frame({ tab, children }: { tab: Tab; children: ComponentChildren }) {
  const me = useStore(meStore);
  const summary = useSummary();
  const query = (useLocation().query.get('q') ?? '').trim();
  const [text, setText] = useState(query);
  useEffect(() => {
    const id = window.setTimeout(() => { if (text.trim() !== query) setQuery({ q: text.trim() || undefined }); }, 350);
    return () => window.clearTimeout(id);
  }, [text]);
  useEffect(() => { if (!query) setText((v) => (v.trim() ? '' : v)); }, [query]);

  if (!me.requests.connected) {
    return (
      <Shell>
        <div class="reqs-pad">
          <NotSetUp />
        </div>
      </Shell>
    );
  }
  return (
    <Shell>
      <div class="reqs-bar">
        <label class="reqs-search">
          <Icon name="search" size={20} stroke={2.2} />
          <input
            type="search"
            aria-label={t('Search Seerr for movies and shows')}
            placeholder={t('Find any movie or show to request')}
            value={text}
            onInput={(e) => setText((e.currentTarget as HTMLInputElement).value)}
            onKeyDown={(e) => {
              if (e.key === 'Escape' && text) { e.preventDefault(); setText(''); setQuery({ q: undefined }); }
              if (e.key === 'Enter') setQuery({ q: text.trim() || undefined });
            }}
          />
        </label>
        <nav aria-label={t('Requests views')} class="tab-row hscroll">
          {TABS.filter((x) => x.id !== 'all' || summary?.canViewAll).map((x) => {
            const count = x.id === 'my' ? summary?.open : x.id === 'all' ? summary?.pending : undefined;
            const current = tab === x.id && !query;
            return (
              <Link key={x.id} to={x.path} class="tab reqs-tab" aria-current={current ? 'page' : undefined}>
                {t(x.label)}
                {!!count && (
                  <span class={'reqs-count' + (x.id === 'all' ? ' is-pending' : '')} aria-label={x.id === 'all' ? t('{n} waiting for approval', { n: count }) : t('{n} open', { n: count })}>
                    {count}
                  </span>
                )}
              </Link>
            );
          })}
        </nav>
      </div>
      {query ? <SearchResults query={query} /> : children}
    </Shell>
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
    <Frame tab="discover">
      <TopLists />
      {ROWS.map((r, i) => (
        <Lazy key={r.kind} eager={i < 2} placeholder={<RowSkeleton title={t(r.title)} width={180} />}>
          {() => <TitlesRow kind={r.kind} title={t(r.title)} />}
        </Lazy>
      ))}
    </Frame>
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

function Note({ icon, text, action }: { icon: IconName; text: string; action?: ComponentChildren }) {
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

/* Whether a request still belongs under a filter (after an action changed it). */
function matches(r: SeerrRequest, filter: RequestFilter): boolean {
  switch (filter) {
    case 'pending': return r.requestStatus === 'pending';
    case 'processing': return r.requestStatus === 'approved' && r.status !== 'available';
    case 'available': return r.status === 'available';
    case 'failed': return r.requestStatus === 'failed';
    default: return true;
  }
}

export function RequestsList({ scope }: { scope: 'mine' | 'all' }) {
  const me = useStore(meStore);
  const summary = useSummary();
  const loc = useLocation();
  const filter = FILTERS.find((f) => f.value === loc.query.get('filter'))?.value ?? 'all';
  const sort = loc.query.get('sort') === 'modified' ? 'modified' : 'added';
  const everyone = scope === 'all';

  const [items, setItems] = useState<SeerrRequest[]>([]);
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
    if (next && matches(next, filter)) setItems((list) => list.map((i) => (i.requestId === r.requestId ? next : i)));
    else {
      setItems((list) => list.filter((i) => i.requestId !== r.requestId));
      setTotal((n) => Math.max(0, n - 1));
    }
  });

  const label = FILTERS.find((f) => f.value === filter)!.label;
  let body: ComponentChildren;
  if (everyone && summary && !summary.canViewAll) {
    body = <EmptyState icon="lock" title={t('Only request managers see everyone’s requests')} text={t('Your Seerr account can see your own requests under My requests.')} action={<Link to="/requests/my" class="btn btn-secondary">{t('My requests')}</Link>} />;
  } else if (status === 'loading') {
    body = <div class="reqs-list"><RowSkeletons count={6} /></div>;
  } else if (status === 'error') {
    body = <ErrorState title={t('Couldn’t load requests')} retry={() => setTick((n) => n + 1)} />;
  } else if (!items.length) {
    body = filter !== 'all'
      ? <EmptyState icon="inbox" title={t('No requests here')} text={t('Nothing matches “{filter}”. Try All.', { filter: t(label) })} action={<button type="button" class="btn btn-secondary" onClick={() => setQuery({ filter: undefined })}>{t('Show all')}</button>} />
      : (
        <EmptyState
          icon="inbox"
          title={everyone ? t('No requests yet') : t('You haven’t requested anything yet')}
          text={everyone ? t('Requests from everyone on the server show up here.') : t('Titles you request from Discover or Search show up here, with where they are on the way to your library.')}
          action={<Link to="/requests" class="btn btn-secondary">{t('Find something to request')}</Link>}
        />
      );
  } else {
    body = (
      <>
        <div class="reqs-list">
          {items.map((r) => <ListRow key={r.requestId} r={r} actions={actions} everyone={everyone} canManage={!!summary?.canManage} />)}
        </div>
        {items.length < total && <Sentinel onVisible={loadMore} token={items.length} disabled={status !== 'ready'} />}
        {status === 'more' && <div class="list-loading"><span class="spinner" aria-label={t('Loading more')} /></div>}
      </>
    );
  }

  return (
    <Frame tab={everyone ? 'all' : 'my'}>
      <div class="reqs-pad reqs-listing">
        <div class="reqs-filters">
          <div role="tablist" aria-label={t('Filter requests')} class="tab-row hscroll">
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
            {(status === 'ready' || status === 'more') && <span class="reqs-total" aria-live="polite">{total === 1 ? t('1 request') : t('{n} requests', { n: total })}</span>}
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
        {body}
      </div>
    </Frame>
  );
}
