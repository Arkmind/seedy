import type { RefObject } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import type { Query } from '../../api/client';
import { tmdbImage } from '../../api/images';
import { seerrBrowse, seerrBrowseOptions, seerrKeywords, seerrStudios, type SeerrGenre, type SeerrNamed, type SeerrTitle } from '../../api/seedy';
import { formatNumber, getLang, t, tc } from '../../lib/i18n';
import { plural } from '../../lib/format';
import { Link, navigate, useLocation } from '../../lib/router';
import { safeLocal, saveLocal, useAsync } from '../../lib/store';
import { inLibrary, seerrTarget, TypePill } from '../../ui/cards';
import { Icon } from '../../ui/icons';
import { EmptyState, ErrorState, GridSkeleton, seerrKey, useRequests } from '../browse/parts';
import {
  FilterChips, FilterPanel, FiltersButton, PanelCheck, PanelDrill, PanelPick, PanelSearch, PanelSection, PanelSegment, RangeSlider, SortButton,
  type FilterChip, type SortOption, type SortOrder
} from '../browse/sort-filter';
import { TitleCard } from './parts';

/* Requests · Discover's Sort and Filters (Requests and RequestsResults boards). Picking a sort or a filter turns
   Discover's rows into one list of titles to request: Everything, Trending, Upcoming or Top rated, sorted and
   filtered like a library (Library · Filters open board), with its state in the URL. Seerr can filter by type,
   genre, year, rating, runtime, original language, studio or network and keywords; whether a title is on the server
   is checked by Seedy as it walks Seerr's pages, so with that filter the list has no total. */

export type BrowseView = 'all' | 'trending' | 'upcoming' | 'top';
type SortId = 'popularity' | 'rating' | 'release' | 'title';
type StatusId = 'missing' | 'partial' | 'requested' | 'available';
type MediaType = 'all' | 'movie' | 'tv';

export interface Filters {
  type: MediaType;
  status: StatusId[];
  genres: number[];
  from?: number;
  to?: number;
  rating?: number;
  rtMin?: number;
  rtMax?: number;
  langs: string[];
  studios: SeerrNamed[];
  networks: number[];
  keywords: SeerrNamed[];
}

const NONE: Filters = { type: 'all', status: [], genres: [], langs: [], studios: [], networks: [], keywords: [] };

const VIEWS: { id: BrowseView; label: string }[] = [
  { id: 'all', label: 'Everything' },
  { id: 'trending', label: 'Trending' },
  { id: 'upcoming', label: 'Upcoming' },
  { id: 'top', label: 'Top rated' }
];

const SORTS: SortOption<SortId>[] = [
  { id: 'popularity', label: 'Popularity', order: 'desc', asc: 'Least popular first', desc: 'Most popular first' },
  { id: 'rating', label: 'TMDB rating', order: 'desc', asc: 'Lowest first', desc: 'Highest first' },
  { id: 'release', label: 'Release date', order: 'desc', asc: 'Oldest first', desc: 'Newest first' },
  { id: 'title', label: 'Title', order: 'asc', asc: 'A to Z', desc: 'Z to A' }
];

const TYPES: { id: MediaType; label: string }[] = [{ id: 'all', label: 'All' }, { id: 'movie', label: 'Movies' }, { id: 'tv', label: 'Shows' }];

/* The status dots match the posters' and request rows' colors. */
const STATUSES: { id: StatusId; label: string; dot: string }[] = [
  { id: 'missing', label: 'Not on the server', dot: '#5a5a5a' },
  { id: 'partial', label: 'Partly there', dot: '#9fd3ff' },
  { id: 'requested', label: 'Requested', dot: 'var(--seedy-pending)' },
  { id: 'available', label: 'On the server', dot: 'var(--seedy-ok)' }
];

/* The genres shown before More: the board's, which most people look for. */
const FIRST_GENRES = [53, 878, 18, 9648, 35, 80, 27, 99];
/* Original languages listed before any search. */
const FIRST_LANGS = ['en', 'fr', 'es', 'de', 'it', 'ja', 'ko', 'zh', 'hi', 'pt', 'sv', 'da'];

const YEAR_MIN = 1950;
const yearMax = () => new Date().getFullYear() + 1;
const RUNTIME_MAX = 240;
const PAGE = 24;

/* Filters Trending can't use: Seerr's trending list has no runtime, studio or keywords. */
const NOT_TRENDING = ['rtmin', 'rtmax', 'studios', 'networks', 'keywords'];
const PARAMS = ['view', 'sort', 'order', 'type', 'status', 'genres', 'from', 'to', 'rating', 'rtmin', 'rtmax', 'lang', 'studios', 'networks', 'keywords'];

/* Any of these in the URL makes Discover the list of results. */
export const isBrowsing = (q: URLSearchParams) => PARAMS.some((p) => q.has(p));

const split = (s: string | null, sep: string) => (s ? s.split(sep).map((x) => x.trim()).filter(Boolean) : []);
const ids = (s: string | null) => split(s, ',').map(Number).filter((n) => Number.isInteger(n) && n > 0);
const num = (s: string | null) => (s !== null && s.trim() !== '' && Number.isFinite(Number(s)) ? Number(s) : undefined);
/* Studios and keywords keep their names in the URL ("41077:A24|…") for their chips. */
const named = (s: string | null): SeerrNamed[] => split(s, '|')
  .map((p) => ({ id: Number(p.slice(0, p.indexOf(':'))), name: p.slice(p.indexOf(':') + 1) }))
  .filter((x) => Number.isInteger(x.id) && x.id > 0 && !!x.name);
const namedParam = (list: SeerrNamed[]) => list.map((x) => `${x.id}:${x.name.replace(/\|/g, ' ')}`).join('|') || undefined;

export function readFilters(q: URLSearchParams): Filters {
  const type = q.get('type');
  return {
    type: type === 'movie' || type === 'tv' ? type : 'all',
    status: split(q.get('status'), ',').filter((s): s is StatusId => STATUSES.some((x) => x.id === s)),
    genres: ids(q.get('genres')),
    from: num(q.get('from')),
    to: num(q.get('to')),
    rating: num(q.get('rating')),
    rtMin: num(q.get('rtmin')),
    rtMax: num(q.get('rtmax')),
    langs: split(q.get('lang'), ',').filter((l) => /^[a-z]{2}$/.test(l)),
    studios: named(q.get('studios')),
    networks: ids(q.get('networks')),
    keywords: named(q.get('keywords'))
  };
}

function filterParams(f: Filters): Record<string, string | undefined> {
  const s = (n?: number) => (n === undefined ? undefined : String(n));
  return {
    type: f.type === 'all' ? undefined : f.type,
    status: f.status.join(',') || undefined,
    genres: f.genres.join(',') || undefined,
    from: s(f.from),
    to: s(f.to),
    rating: s(f.rating),
    rtmin: s(f.rtMin),
    rtmax: s(f.rtMax),
    lang: f.langs.join(',') || undefined,
    studios: namedParam(f.studios),
    networks: f.networks.join(',') || undefined,
    keywords: namedParam(f.keywords)
  };
}

const filterCount = (f: Filters) =>
  (f.type !== 'all' ? 1 : 0) + f.status.length + f.genres.length + (f.from || f.to ? 1 : 0) + (f.rating ? 1 : 0) + (f.rtMin || f.rtMax ? 1 : 0)
  + f.langs.length + f.studios.length + f.networks.length + f.keywords.length;

interface BrowseState { view: BrowseView; sort: SortId; order: SortOrder; filters: Filters }

function readState(q: URLSearchParams): BrowseState {
  const view = VIEWS.find((v) => v.id === q.get('view'))?.id ?? 'all';
  const sort = SORTS.find((s) => s.id === q.get('sort'))?.id ?? (view === 'top' ? 'rating' : 'popularity');
  const o = q.get('order');
  const order: SortOrder = o === 'asc' || o === 'desc' ? o : SORTS.find((s) => s.id === sort)!.order;
  return { view, sort, order, filters: readFilters(q) };
}

/* The Discover link for a state; the view is always there, so clearing every filter keeps the list. */
function hrefFor(s: BrowseState, keepSort = true): string {
  const p = new URLSearchParams();
  const all: Record<string, string | undefined> = {
    view: s.view,
    ...(keepSort && s.view !== 'trending' ? { sort: s.sort, order: s.order } : {}),
    ...filterParams(s.filters)
  };
  for (const [k, v] of Object.entries(all)) if (v !== undefined && !(s.view === 'trending' && NOT_TRENDING.includes(k))) p.set(k, v);
  return '/requests?' + p.toString();
}

function apiQuery(s: BrowseState): Query {
  const f = s.filters;
  const trending = s.view === 'trending';
  return {
    view: s.view,
    sort: s.sort,
    order: s.order,
    type: f.type === 'all' ? undefined : f.type,
    status: f.status.join(',') || undefined,
    genres: f.genres.join(',') || undefined,
    from: f.from,
    to: f.to,
    rating: f.rating,
    lang: f.langs.join(',') || undefined,
    ...(trending ? {} : {
      runtimeMin: f.rtMin,
      runtimeMax: f.rtMax,
      studios: f.studios.map((x) => x.id).join(',') || undefined,
      networks: f.networks.join(',') || undefined,
      keywords: f.keywords.map((x) => x.id).join(',') || undefined
    })
  };
}

/* ───────── Words for what's picked ───────── */

function yearsText(from?: number, to?: number): string | undefined {
  if (from && to) return from === to ? String(from) : `${from} – ${to}`;
  if (from) return t('{year} and later', { year: from });
  if (to) return t('Up to {year}', { year: to });
  return undefined;
}

const ratingText = (r?: number) => (r ? t('{rating} and up', { rating: formatNumber(r, { maximumFractionDigits: 1 }) }) : undefined);

/* "1 h 30", "45 min". */
function minutes(m: number): string {
  const h = Math.floor(m / 60);
  const r = m % 60;
  return h === 0 ? `${r} min` : r ? `${h} h ${String(r).padStart(2, '0')}` : `${h} h`;
}

function runtimeText(min?: number, max?: number): string | undefined {
  if (min && max) return `${minutes(min)} – ${minutes(max)}`;
  if (min) return t('{time} or more', { time: minutes(min) });
  if (max) return t('Up to {time}', { time: minutes(max) });
  return undefined;
}

/* Languages by name in the app's language ("Korean", "coréen"). */
let languageNames: Intl.DisplayNames | null | undefined;
function languageName(code: string, fallback?: string): string {
  if (languageNames === undefined) {
    try { languageNames = new Intl.DisplayNames([getLang()], { type: 'language' }); } catch { languageNames = null; }
  }
  const name = languageNames?.of(code);
  return name && name !== code ? name.charAt(0).toLocaleUpperCase() + name.slice(1) : fallback ?? code.toUpperCase();
}

type Options = { genres: SeerrGenre[]; languages: { code: string; name: string }[]; networks: SeerrNamed[] };

/* The genres, languages and networks the panel offers; Seerr's lists barely change, so once per visit. */
let optionsCache: Promise<Options> | null = null;
function useOptions() {
  return useAsync(() => {
    optionsCache ??= seerrBrowseOptions().catch((e) => { optionsCache = null; throw e; });
    return optionsCache;
  }, []);
}

function chipsFor(f: Filters, options: Options | undefined, set: (f: Filters) => void): FilterChip[] {
  const chips: FilterChip[] = [];
  const add = (key: string, label: string | undefined, next: Partial<Filters>) => { if (label) chips.push({ key, label, remove: () => set({ ...f, ...next }) }); };
  if (f.type !== 'all') add('type', f.type === 'movie' ? t('Movies') : t('Shows'), { type: 'all' });
  for (const s of f.status) add('s' + s, t(STATUSES.find((x) => x.id === s)!.label), { status: f.status.filter((x) => x !== s) });
  for (const g of f.genres) add('g' + g, options?.genres.find((x) => x.id === g)?.name, { genres: f.genres.filter((x) => x !== g) });
  add('years', yearsText(f.from, f.to), { from: undefined, to: undefined });
  add('rating', f.rating ? '★ ' + ratingText(f.rating) : undefined, { rating: undefined });
  add('rt', runtimeText(f.rtMin, f.rtMax), { rtMin: undefined, rtMax: undefined });
  for (const l of f.langs) add('l' + l, languageName(l), { langs: f.langs.filter((x) => x !== l) });
  for (const st of f.studios) add('st' + st.id, st.name, { studios: f.studios.filter((x) => x.id !== st.id) });
  for (const n of f.networks) add('n' + n, options?.networks.find((x) => x.id === n)?.name, { networks: f.networks.filter((x) => x !== n) });
  for (const k of f.keywords) add('k' + k.id, k.name, { keywords: f.keywords.filter((x) => x.id !== k.id) });
  return chips;
}

/* ───────── Discover: Sort and Filters beside the tabs ───────── */

export function DiscoverTools() {
  const ref = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const start: BrowseState = { view: 'all', sort: 'popularity', order: 'desc', filters: NONE };
  return (
    <div class="reqs-tools">
      <SortButton sorts={SORTS} sort={start.sort} order={start.order} onChange={(sort, order) => navigate(hrefFor({ ...start, sort, order }))} />
      <FiltersButton buttonRef={ref} open={open} count={0} onClick={() => setOpen((o) => !o)} />
      {open && <BrowseFilters anchor={ref} state={start} onClose={() => setOpen(false)} onApply={(filters) => { setOpen(false); navigate(hrefFor({ ...start, filters })); }} />}
    </div>
  );
}

/* ───────── The list of results ───────── */

interface Results { items: SeerrTitle[]; total?: number; next?: string; busy: boolean; error?: Error }

function useResults(state: BrowseState): Results & { more: () => void; retry: () => void } {
  const key = JSON.stringify(apiQuery(state));
  const [r, setR] = useState<Results>({ items: [], busy: true });
  const gen = useRef(0);
  const seen = useRef(new Set<string>());
  const load = async (cursor: string | undefined, g: number, tries = 0): Promise<void> => {
    setR((p) => ({ ...p, busy: true, error: undefined }));
    try {
      const page = await seerrBrowse({ ...(JSON.parse(key) as Query), cursor, take: PAGE });
      if (g !== gen.current) return;
      const fresh = page.results.filter((s) => !seen.current.has(seerrKey(s)));
      fresh.forEach((s) => seen.current.add(seerrKey(s)));
      setR((p) => ({ items: [...p.items, ...fresh], total: cursor ? p.total : page.total, next: page.next, busy: false }));
      // On the server and Trending's filters are checked here, a few Seerr pages at a time: a page can come back
      // empty while later ones match, so look a little further before saying so.
      if (!fresh.length && page.next && tries < 2) await load(page.next, g, tries + 1);
    } catch (e) {
      if (g === gen.current) setR((p) => ({ ...p, busy: false, error: e as Error }));
    }
  };
  useEffect(() => {
    const g = ++gen.current;
    seen.current = new Set();
    setR({ items: [], busy: true });
    void load(undefined, g);
  }, [key]);
  return {
    ...r,
    more: () => { if (r.next && !r.busy) void load(r.next, gen.current); },
    retry: () => void load(r.items.length ? r.next : undefined, gen.current)
  };
}

export function BrowseResults() {
  const loc = useLocation();
  const state = readState(loc.query);
  const { view, sort, order, filters } = state;
  const res = useResults(state);
  const req = useRequests();
  const options = useOptions();
  const ref = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const [layout, setLayoutState] = useState<'grid' | 'list'>(() => safeLocal('seedy.requestsLayout', 'grid'));
  const setLayout = (l: 'grid' | 'list') => { setLayoutState(l); saveLocal('seedy.requestsLayout', l); };
  const go = (next: Partial<BrowseState>) => navigate(hrefFor({ ...state, ...next }), { replace: true, keepScroll: true });
  const chips = chipsFor(filters, options.data, (f) => go({ filters: f }));
  const filtered = filterCount(filters) > 0;

  return (
    <div class="reqs-browse">
      <div class="lib-toolbar reqs-pad">
        <nav aria-label={t('Discover views')} class="tab-row hscroll">
          {VIEWS.map((v) => (
            <Link
              key={v.id}
              to={hrefFor({ ...state, view: v.id, ...(v.id !== view ? { sort: v.id === 'top' ? 'rating' : 'popularity', order: 'desc' } : {}) })}
              replace
              class="tab"
              aria-current={v.id === view ? 'page' : undefined}
            >
              {t(v.label)}
            </Link>
          ))}
        </nav>
        <div class="lib-tools">
          {res.total !== undefined && <span class="reqs-total">{plural(res.total, 'title')}</span>}
          {view !== 'trending' && <SortButton sorts={SORTS} sort={sort} order={order} onChange={(s, o) => go({ sort: s, order: o })} />}
          <FiltersButton buttonRef={ref} open={open} count={filterCount(filters)} onClick={() => setOpen((o) => !o)} />
          <div role="group" aria-label={t('Layout')} class="seg is-icons">
            <button type="button" class="seg-btn" aria-label={t('Poster grid')} aria-pressed={layout === 'grid' ? 'true' : 'false'} onClick={() => setLayout('grid')}>
              <Icon name="grid" size={18} />
            </button>
            <button type="button" class="seg-btn" aria-label={t('List')} aria-pressed={layout === 'list' ? 'true' : 'false'} onClick={() => setLayout('list')}>
              <Icon name="list" size={18} />
            </button>
          </div>
        </div>
      </div>

      {chips.length > 0 && <div class="reqs-pad"><FilterChips chips={chips} onClear={() => go({ filters: NONE })} /></div>}

      <section class="reqs-pad reqs-found" aria-label={t('Titles')}>
        {res.error && !res.items.length ? <ErrorState error={res.error} retry={res.retry} title={t('Couldn\'t reach Seerr')} />
          : res.busy && !res.items.length ? <GridSkeleton count={12} poster />
          : !res.items.length ? (
            res.next
              ? <EmptyState icon="search" title={t('Nothing found yet')} text={t('Seerr has more titles to look through.')} action={<button type="button" class="btn btn-secondary is-sm" onClick={res.more}>{t('Look further')}</button>} />
              : <EmptyState
                  icon="filters"
                  title={t('Nothing matches these filters')}
                  text={t('Try removing a filter or two.')}
                  action={filtered ? <button type="button" class="btn btn-primary is-sm" onClick={() => go({ filters: NONE })}>{t('Clear all')}</button> : undefined}
                />
          ) : (
            <>
              {layout === 'list'
                ? <div class="list-rows">{res.items.map((s) => <TitleRow key={seerrKey(s)} item={s} req={req} />)}</div>
                : <div class="poster-grid">{res.items.map((s) => <TitleCard key={seerrKey(s)} item={s} upcoming={false} req={req} />)}</div>}
              <div class="reqs-foot" aria-live="polite">
                <span>
                  {res.total !== undefined
                    ? t('Showing {shown} of {total}', { shown: formatNumber(res.items.length), total: formatNumber(res.total) })
                    : t('Showing {shown}', { shown: formatNumber(res.items.length) })}
                </span>
                {res.error ? (
                  <button type="button" class="btn btn-secondary" onClick={res.retry}><Icon name="refresh" size={18} />{t('Try again')}</button>
                ) : res.next && (
                  <button type="button" class="btn btn-secondary reqs-more" disabled={res.busy} onClick={res.more}>
                    {res.busy ? <span class="spinner" aria-label={t('Loading more')} /> : t('Load more')}
                  </button>
                )}
              </div>
            </>
          )}
      </section>

      {open && (
        <BrowseFilters
          anchor={ref}
          state={state}
          onClose={() => setOpen(false)}
          onApply={(f) => { setOpen(false); go({ filters: f }); }}
        />
      )}
      {req.dialog}
    </div>
  );
}

/* A title in the list layout: art, name and kind, where it stands, then Request or Open. */
function TitleRow({ item, req }: { item: SeerrTitle; req: ReturnType<typeof useRequests> }) {
  const s = req.apply(item);
  const to = seerrTarget(s);
  const art = tmdbImage(s.backdropPath, 'w300') ?? tmdbImage(s.posterPath, 'w342');
  const requestable = req.canRequest && (s.status === 'none' || s.status === 'declined');
  const state = s.status === 'available' ? t('On the server')
    : s.status === 'partial' ? t('Partly there')
    : s.status === 'pending' || s.status === 'processing' ? t('Requested')
    : t('Not on the server');
  const who = (s.status === 'pending' || s.status === 'processing') && s.requestedBy
    ? (s.requestedByMe ? t('By you') : t('By {name}', { name: s.requestedBy })) : undefined;
  return (
    <div class="list-row reqs-list-row">
      <Link to={to} class="list-thumb" aria-label={s.title}>
        {art ? <img src={art} alt="" class="art" loading="lazy" /> : <span aria-hidden="true" class="art-title is-list">{s.title}</span>}
      </Link>
      <div class="list-main">
        <span class="reqs-list-name">
          <Link to={to} class="list-title">{s.title}</Link>
          <TypePill type={s.mediaType} />
        </span>
        <span class="list-sub reqs-list-sub">
          {s.rating ? <><Icon name="star" size={13} color="var(--seedy-accent)" />{formatNumber(s.rating, { minimumFractionDigits: 1, maximumFractionDigits: 1 })}</> : null}
          {s.rating && s.year ? ' · ' : ''}
          {s.year}
        </span>
      </div>
      <div class="list-state">
        <span class="list-state-1">{state}</span>
        {who && <span class="list-state-2">{who}</span>}
      </div>
      <div class="list-tools">
        {requestable ? (
          <button type="button" class="btn btn-primary is-sm" aria-haspopup={s.mediaType === 'tv' ? 'dialog' : undefined} disabled={req.busy(item)} onClick={() => req.request(item)}>
            <Icon name="plus" size={18} stroke={2.6} />
            {req.busy(item) ? t('Requesting…') : t('Request')}
          </button>
        ) : (
          <Link to={to} class="btn btn-secondary is-sm">{inLibrary(s) ? t('Open') : t('More info')}</Link>
        )}
      </div>
    </div>
  );
}

/* ───────── The Filters panel ───────── */

type Drill = 'runtime' | 'language' | 'studio' | 'keywords' | null;

function BrowseFilters({ anchor, state, onClose, onApply }: {
  anchor: RefObject<HTMLButtonElement>; state: BrowseState; onClose: () => void; onApply: (f: Filters) => void;
}) {
  const [draft, setDraft] = useState<Filters>(state.filters);
  const [drill, setDrill] = useState<Drill>(null);
  const [allGenres, setAllGenres] = useState(false);
  const options = useOptions();
  const trending = state.view === 'trending';
  const set = (p: Partial<Filters>) => setDraft((d) => ({ ...d, ...p }));
  const toggle = <T,>(list: T[], v: T, same: (a: T, b: T) => boolean = (a, b) => a === b) => (list.some((x) => same(x, v)) ? list.filter((x) => !same(x, v)) : [...list, v]);

  // How many titles the draft shows, once it settles; unknown with filters Seedy checks itself.
  const [count, setCount] = useState<number | null | undefined>(undefined);
  const draftKey = JSON.stringify(apiQuery({ ...state, filters: draft }));
  useEffect(() => {
    setCount(undefined);
    let alive = true;
    const id = window.setTimeout(() => {
      seerrBrowse({ ...(JSON.parse(draftKey) as Query), take: 1 })
        .then((r) => alive && setCount(r.total ?? (r.results.length || r.next ? null : 0)), () => alive && setCount(null));
    }, 300);
    return () => { alive = false; window.clearTimeout(id); };
  }, [draftKey]);
  const apply = count === undefined || count === null ? t('Show titles') : count === 0 ? t('No titles match') : t('Show {count}', { count: plural(count, 'title') });

  const genres = (options.data?.genres ?? []).filter((g) => draft.type === 'all' || (draft.type === 'movie' ? g.movie : g.tv));
  const first = FIRST_GENRES.map((id) => genres.find((g) => g.id === id)).filter((g): g is SeerrGenre => !!g);
  const shownGenres = allGenres ? genres : [...first, ...genres.filter((g) => draft.genres.includes(g.id) && !first.includes(g))];
  const yMax = yearMax();

  return (
    <FilterPanel
      anchor={anchor as RefObject<HTMLElement>}
      open
      onClose={onClose}
      canReset={filterCount(draft) > 0}
      onReset={() => setDraft(NONE)}
      apply={apply}
      onApply={() => onApply(draft)}
    >
      <PanelSection title={t('Type')}>
        <PanelSegment label={t('Type')} options={TYPES.map((x) => ({ id: x.id, label: t(x.label) }))} value={draft.type} onChange={(type) => set({ type })} />
      </PanelSection>

      <PanelSection title={t('On the server')}>
        <div class="flist">
          {STATUSES.map((s) => (
            <PanelCheck key={s.id} on={draft.status.includes(s.id)} label={t(s.label)} dot={s.dot} onChange={() => set({ status: toggle(draft.status, s.id) })} />
          ))}
        </div>
      </PanelSection>

      <PanelSection title={t('Genres')} value={draft.genres.length === 1 ? t('1 picked') : draft.genres.length ? t('{n} picked', { n: draft.genres.length }) : undefined}>
        {options.loading && !options.data ? (
          <div class="fpicks" aria-busy="true">{Array.from({ length: 8 }, (_, i) => <span key={i} class="skel" style={{ width: '84px', height: '34px', borderRadius: '10px' }} />)}</div>
        ) : options.error ? (
          <p class="fdrill-note">{t('Couldn’t load the genres.')}</p>
        ) : (
          <div class="fpicks">
            {shownGenres.map((g) => <PanelPick key={g.id} on={draft.genres.includes(g.id)} label={g.name} onClick={() => set({ genres: toggle(draft.genres, g.id) })} />)}
            {genres.length > shownGenres.length && (
              <button type="button" class="fpick is-more" onClick={() => setAllGenres(true)}>{t('{n} more', { n: genres.length - shownGenres.length })}</button>
            )}
          </div>
        )}
      </PanelSection>

      <PanelSection title={t('Release year')} value={yearsText(draft.from, draft.to) ?? t('Any year')}>
        <RangeSlider
          min={YEAR_MIN}
          max={yMax}
          step={1}
          value={[draft.from ?? YEAR_MIN, draft.to ?? yMax]}
          labels={[t('From'), t('To')]}
          onChange={(v) => {
            const [lo, hi] = v as [number, number];
            set({ from: lo > YEAR_MIN ? lo : undefined, to: hi < yMax ? hi : undefined });
          }}
        />
      </PanelSection>

      <PanelSection title={t('TMDB rating')} value={ratingText(draft.rating) ?? t('Any rating')}>
        <RangeSlider min={0} max={10} step={0.5} value={draft.rating ?? 0} labels={[t('Minimum rating')]} onChange={(v) => set({ rating: (v as number) > 0 ? (v as number) : undefined })} />
      </PanelSection>

      {!trending && (
        <PanelDrill
          title={t('Runtime')}
          sub={runtimeText(draft.rtMin, draft.rtMax) ?? t('Any length')}
          set={!!(draft.rtMin || draft.rtMax)}
          open={drill === 'runtime'}
          onToggle={() => setDrill((d) => (d === 'runtime' ? null : 'runtime'))}
        >
          <RangeSlider
            min={0}
            max={RUNTIME_MAX}
            step={15}
            value={[draft.rtMin ?? 0, draft.rtMax ?? RUNTIME_MAX]}
            labels={[t('Shortest'), t('Longest')]}
            onChange={(v) => {
              const [lo, hi] = v as [number, number];
              set({ rtMin: lo > 0 ? lo : undefined, rtMax: hi < RUNTIME_MAX ? hi : undefined });
            }}
          />
          <p class="fdrill-note">{t('For shows, the length of an episode.')}</p>
        </PanelDrill>
      )}

      <PanelDrill
        title={t('Original language')}
        sub={draft.langs.map((l) => languageName(l)).join(', ') || t('Any')}
        set={draft.langs.length > 0}
        open={drill === 'language'}
        onToggle={() => setDrill((d) => (d === 'language' ? null : 'language'))}
      >
        <LanguagePicker options={options.data?.languages ?? []} picked={draft.langs} onToggle={(code) => set({ langs: toggle(draft.langs, code) })} />
      </PanelDrill>

      {!trending && (
        <PanelDrill
          title={t('Studio or network')}
          sub={[...draft.studios.map((s) => s.name), ...draft.networks.map((n) => options.data?.networks.find((x) => x.id === n)?.name ?? '')].filter(Boolean).join(', ') || tc('Any', 'studio')}
          set={draft.studios.length + draft.networks.length > 0}
          open={drill === 'studio'}
          onToggle={() => setDrill((d) => (d === 'studio' ? null : 'studio'))}
        >
          <StudioPicker
            networks={options.data?.networks ?? []}
            type={draft.type}
            studios={draft.studios}
            pickedNetworks={draft.networks}
            onStudio={(s) => set({ studios: toggle(draft.studios, s, (a, b) => a.id === b.id) })}
            onNetwork={(id) => set({ networks: toggle(draft.networks, id) })}
          />
        </PanelDrill>
      )}

      {!trending && (
        <PanelDrill
          title={t('Keywords')}
          sub={draft.keywords.map((k) => k.name).join(', ') || t('None')}
          set={draft.keywords.length > 0}
          open={drill === 'keywords'}
          onToggle={() => setDrill((d) => (d === 'keywords' ? null : 'keywords'))}
        >
          <KeywordPicker picked={draft.keywords} onToggle={(k) => set({ keywords: toggle(draft.keywords, k, (a, b) => a.id === b.id) })} />
        </PanelDrill>
      )}
    </FilterPanel>
  );
}

/* Text typed into a picker's search, settled for 300 ms. */
function useSettled(text: string, ms = 300): string {
  const [v, setV] = useState(text);
  useEffect(() => {
    const id = window.setTimeout(() => setV(text), ms);
    return () => window.clearTimeout(id);
  }, [text]);
  return v;
}

const matches = (name: string, q: string) => name.toLocaleLowerCase().includes(q.toLocaleLowerCase());

function LanguagePicker({ options, picked, onToggle }: { options: { code: string; name: string }[]; picked: string[]; onToggle: (code: string) => void }) {
  const [q, setQ] = useState('');
  const query = q.trim();
  const langs = options.map((l) => ({ code: l.code, name: languageName(l.code, l.name), english: l.name }));
  const list = query
    ? langs.filter((l) => matches(l.name, query) || matches(l.english, query)).slice(0, 12)
    : [...picked.map((c) => langs.find((l) => l.code === c) ?? { code: c, name: languageName(c), english: c }),
      ...FIRST_LANGS.filter((c) => !picked.includes(c)).map((c) => langs.find((l) => l.code === c)).filter((l): l is (typeof langs)[number] => !!l)].slice(0, Math.max(8, picked.length));
  return (
    <>
      <PanelSearch value={q} onInput={setQ} placeholder={t('Find a language')} />
      <div class="flist">
        {list.map((l) => <PanelCheck key={l.code} on={picked.includes(l.code)} label={l.name} onChange={() => onToggle(l.code)} />)}
        {query && !list.length && <p class="fdrill-note">{t('No language matches “{query}”.', { query })}</p>}
      </div>
    </>
  );
}

function StudioPicker({ networks, type, studios, pickedNetworks, onStudio, onNetwork }: {
  networks: SeerrNamed[]; type: MediaType; studios: SeerrNamed[]; pickedNetworks: number[];
  onStudio: (s: SeerrNamed) => void; onNetwork: (id: number) => void;
}) {
  const [q, setQ] = useState('');
  const query = useSettled(q.trim());
  const found = useAsync(() => (query && type !== 'tv' ? seerrStudios(query) : Promise.resolve([] as SeerrNamed[])), [query, type]);
  const shownNetworks = type === 'movie' ? networks.filter((n) => pickedNetworks.includes(n.id))
    : q.trim() ? networks.filter((n) => matches(n.name, q.trim()))
    : [...networks.filter((n) => pickedNetworks.includes(n.id)), ...networks.filter((n) => !pickedNetworks.includes(n.id)).slice(0, 8)];
  const shownStudios = [...studios, ...(found.data ?? []).filter((s) => !studios.some((x) => x.id === s.id))];
  return (
    <>
      <PanelSearch value={q} onInput={setQ} placeholder={type === 'tv' ? t('Find a network') : type === 'movie' ? t('Find a studio') : t('Find a studio or network')} />
      {type !== 'tv' && shownStudios.length > 0 && (
        <div class="flist">
          <span class="flist-head">{t('Studios · movies')}</span>
          {shownStudios.map((s) => <PanelCheck key={'s' + s.id} on={studios.some((x) => x.id === s.id)} label={s.name} sub={s.sub} onChange={() => onStudio(s)} />)}
        </div>
      )}
      {shownNetworks.length > 0 && (
        <div class="flist">
          <span class="flist-head">{t('Networks · shows')}</span>
          {shownNetworks.map((n) => <PanelCheck key={'n' + n.id} on={pickedNetworks.includes(n.id)} label={n.name} onChange={() => onNetwork(n.id)} />)}
        </div>
      )}
      {type !== 'tv' && !q.trim() && !studios.length && <p class="fdrill-note">{t('Type a name to find a studio, like A24 or Pixar.')}</p>}
      {q.trim() && query === q.trim() && !found.loading && !shownStudios.length && !shownNetworks.length && (
        <p class="fdrill-note">{t('Nothing matches “{query}”.', { query: q.trim() })}</p>
      )}
    </>
  );
}

function KeywordPicker({ picked, onToggle }: { picked: SeerrNamed[]; onToggle: (k: SeerrNamed) => void }) {
  const [q, setQ] = useState('');
  const query = useSettled(q.trim());
  const found = useAsync(() => (query ? seerrKeywords(query) : Promise.resolve([] as SeerrNamed[])), [query]);
  const list = [...picked, ...(found.data ?? []).filter((k) => !picked.some((x) => x.id === k.id)).slice(0, 10)];
  return (
    <>
      <PanelSearch value={q} onInput={setQ} placeholder={t('Find a keyword, like heist')} />
      {list.length > 0 && (
        <div class="flist">
          {list.map((k) => <PanelCheck key={k.id} on={picked.some((x) => x.id === k.id)} label={k.name} onChange={() => onToggle(k)} />)}
        </div>
      )}
      {picked.length > 1 && <p class="fdrill-note">{t('Titles need every keyword picked.')}</p>}
      {q.trim() && query === q.trim() && !found.loading && !list.length && <p class="fdrill-note">{t('Nothing matches “{query}”.', { query: q.trim() })}</p>}
    </>
  );
}
