import type { ComponentChildren } from 'preact';
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { get, post, JELLYFIN_WEB } from '../../api/client';
import { CARD_FIELDS, getGenres, getItem, getItems, getSimilar, type ItemsQuery } from '../../api/jellyfin';
import { backdropUrl, cardArt } from '../../api/images';
import type { BaseItem, ItemsResult } from '../../api/types';
import { meStore, viewsStore } from '../../lib/app-state';
import { pct, plural } from '../../lib/format';
import { Link, navigate, setQuery, useLocation } from '../../lib/router';
import { safeLocal, saveLocal, useAsync, useStore } from '../../lib/store';
import { ItemCard, playPath } from '../../ui/cards';
import { Header } from '../../ui/navbar/Header';
import { Icon } from '../../ui/icons';
import { MenuButton, MenuItem, Sheet, toast } from '../../ui/overlay';
import { Row } from '../../ui/Row';
import {
  CardGrid, EmptyState, ErrorState, GridSkeleton, ListRows, PageHead, RowSkeleton, Sentinel, join, playRandom, uid,
  useInView, usePaged, yearOf, type GridSelect
} from './parts';
import { CUSTOM, readCustom } from '../me/prefs';
import './library.css';
import { headerHeight } from '../../ui/sticky';
import { t } from '../../lib/i18n';
import { SelectionBar, useSelection } from './library-select';
import { manageChanged } from './manage/host';
import { MusicLibrary } from '../music/MusicLibrary';

/* ───────── Tabs, sorts and filters ───────── */

export type TabId = 'all' | 'suggestions' | 'favorites' | 'collections' | 'genres' | 'albums' | 'artists' | 'overview' | 'songs' | 'playlists' | 'folders';
interface Tab { id: TabId; label: string }

export function tabsFor(ct?: string): Tab[] {
  switch (ct) {
    case 'movies': return [{ id: 'all', label: 'All movies' }, { id: 'suggestions', label: 'Suggestions' }, { id: 'favorites', label: 'Favorites' }, { id: 'collections', label: 'Collections' }, { id: 'genres', label: 'Genres' }];
    case 'tvshows': return [{ id: 'all', label: 'All shows' }, { id: 'suggestions', label: 'Suggestions' }, { id: 'favorites', label: 'Favorites' }, { id: 'genres', label: 'Genres' }];
    // The music library's own tabs (MusicHome board; screens/music).
    case 'music': return [
      { id: 'overview', label: 'Overview' }, { id: 'albums', label: 'Albums' }, { id: 'artists', label: 'Artists' }, { id: 'songs', label: 'Songs' },
      { id: 'playlists', label: 'Playlists' }, { id: 'genres', label: 'Genres' }, { id: 'folders', label: 'Folders' }
    ];
    default: return [{ id: 'all', label: 'All' }];
  }
}

export function typesFor(ct?: string): string[] | undefined {
  switch (ct) {
    case 'movies': return ['Movie'];
    case 'tvshows': return ['Series'];
    case 'music': return ['MusicAlbum'];
    case 'boxsets': return ['BoxSet'];
    case 'musicvideos': return ['MusicVideo'];
    case 'homevideos': return ['Video', 'Photo'];
    case 'playlists': return ['Playlist'];
    case 'books': return ['Book'];
    default: return ['Movie', 'Series', 'Video', 'MusicVideo', 'BoxSet'];
  }
}

/* What Shuffle plays: episodes for shows, tracks for music. */
function playableTypesFor(ct?: string): string[] | undefined {
  if (ct === 'tvshows') return ['Episode'];
  if (ct === 'music') return ['Audio'];
  if (ct === 'boxsets') return ['Movie', 'Episode', 'Video'];
  return typesFor(ct);
}

const SORTS = [
  { id: 'DateCreated', label: 'Date added', fields: ['DateCreated', 'SortName'], order: 'desc' },
  { id: 'SortName', label: 'Title', fields: ['SortName'], order: 'asc' },
  { id: 'PremiereDate', label: 'Release date', fields: ['PremiereDate', 'ProductionYear', 'SortName'], order: 'desc' },
  { id: 'CommunityRating', label: 'Rating', fields: ['CommunityRating', 'SortName'], order: 'desc' },
  { id: 'Runtime', label: 'Runtime', fields: ['Runtime', 'SortName'], order: 'asc' },
  { id: 'DatePlayed', label: 'Recently played', fields: ['DatePlayed', 'SortName'], order: 'desc' },
  { id: 'Random', label: 'Random', fields: ['Random'], order: 'asc' }
] as const;
type SortId = (typeof SORTS)[number]['id'];

const FLAGS = [
  { id: 'unwatched', label: 'Unwatched' },
  { id: 'watched', label: 'Watched' },
  { id: 'inprogress', label: 'In progress' },
  { id: 'favorites', label: 'Favorites' },
  { id: '4k', label: '4K' },
  { id: 'hd', label: 'HD' },
  { id: 'subs', label: 'Has subtitles' }
] as const;
type FlagId = (typeof FLAGS)[number]['id'];

interface Filters { flags: FlagId[]; genres: string[]; decades: number[]; ratings: string[] }

const split = (s: string | null, sep: string) => (s ? s.split(sep).map((x) => x.trim()).filter(Boolean) : []);

function readFilters(q: URLSearchParams): Filters {
  return {
    flags: split(q.get('f'), ',').filter((f): f is FlagId => FLAGS.some((x) => x.id === f)),
    genres: split(q.get('genres'), '|'),
    decades: split(q.get('decades'), ',').map(Number).filter((n) => Number.isFinite(n)),
    ratings: split(q.get('ratings'), '|')
  };
}

function filterParams(f: Filters): Record<string, string | undefined> {
  return { f: f.flags.join(',') || undefined, genres: f.genres.join('|') || undefined, decades: f.decades.join(',') || undefined, ratings: f.ratings.join('|') || undefined };
}

const filterCount = (f: Filters) => f.flags.length + f.genres.length + f.decades.length + f.ratings.length;

function applyFilters(q: ItemsQuery, f: Filters): ItemsQuery {
  const filters: string[] = [];
  if (f.flags.includes('unwatched')) filters.push('IsUnplayed');
  if (f.flags.includes('watched')) filters.push('IsPlayed');
  if (f.flags.includes('inprogress')) filters.push('IsResumable');
  if (f.flags.includes('favorites')) filters.push('IsFavorite');
  const years = f.decades.flatMap((d) => Array.from({ length: 10 }, (_, i) => d + i));
  return {
    ...q,
    filters: filters.length ? [...(q.filters ?? []), ...filters] : q.filters,
    is4K: f.flags.includes('4k') || undefined,
    isHd: f.flags.includes('hd') || undefined,
    hasSubtitles: f.flags.includes('subs') || undefined,
    genres: f.genres.length ? f.genres : q.genres,
    years: years.length ? years : undefined,
    officialRatings: f.ratings.length ? f.ratings : undefined
  };
}

const LIST_FIELDS = ['PrimaryImageAspectRatio', 'ProductionYear', 'Genres', 'ChildCount', 'DateCreated', 'SortName', 'PremiereDate'];

function baseQuery(view: BaseItem, tab: TabId): ItemsQuery {
  const ct = view.CollectionType;
  switch (tab) {
    case 'collections': return { includeItemTypes: ['BoxSet'], recursive: true, fields: LIST_FIELDS };
    case 'albums': return { parentId: view.Id, includeItemTypes: ['MusicAlbum'], fields: LIST_FIELDS };
    case 'favorites': return { parentId: view.Id, includeItemTypes: ct === 'music' ? ['MusicAlbum', 'MusicArtist', 'Audio'] : typesFor(ct), isFavorite: true, fields: LIST_FIELDS };
    default: return { parentId: view.Id, includeItemTypes: typesFor(ct), fields: LIST_FIELDS };
  }
}

/* Album artists aren't children of the music library, so they have their own endpoint. */
function fetchArtists(view: BaseItem, q: ItemsQuery, signal?: AbortSignal) {
  return get<ItemsResult>('/Artists/AlbumArtists', {
    userId: uid(), parentId: view.Id, startIndex: q.startIndex, limit: q.limit, sortBy: q.sortBy?.join(','), sortOrder: q.sortOrder,
    fields: LIST_FIELDS.join(','), enableImageTypes: 'Primary,Backdrop,Thumb', imageTypeLimit: 1, enableTotalRecordCount: true,
    isFavorite: q.isFavorite, genres: q.genres?.join('|'), filters: q.filters?.join(','), nameLessThan: q.nameLessThan
  }, signal);
}

const LETTERS = '#ABCDEFGHIJKLMNOPQRSTUVWXYZ'.split('');

/* ───────── Screen ───────── */

export function Library({ id }: { id: string }) {
  const views = useStore(viewsStore);
  const known = views.find((v) => v.Id === id);
  const fetched = useAsync(() => (known ? Promise.resolve(known) : getItem(id, ['ChildCount'])), [id, !!known]);
  const view = known ?? fetched.data;

  if (!view) {
    return (
      <div class="page">
        <Header active={{ viewId: id }} />
        <main class="page-main">
          {fetched.error ? <ErrorState error={fetched.error} retry={fetched.reload} title={t('Couldn\'t open this library')} /> : (
            <>
              <div class="page-title"><span class="skel" style={{ width: '220px', height: '48px', borderRadius: '10px' }} /></div>
              <GridSkeleton count={12} />
            </>
          )}
        </main>
      </div>
    );
  }
  if (view.Type !== 'CollectionFolder' && view.Type !== 'UserView' && view.Type !== 'Folder' && !known) return <Redirect to={`/details/${view.Id}`} />;
  if (view.CollectionType === 'music') return <MusicLibrary view={view} />;
  return <LibraryView view={view} />;
}

/* /library/<id> for something that isn't a library: show it on its details page instead. */
function Redirect({ to }: { to: string }) {
  useEffect(() => { navigate(to, { replace: true }); }, [to]);
  return null;
}

function LibraryView({ view }: { view: BaseItem }) {
  const loc = useLocation();
  const me = useStore(meStore);
  const ct = view.CollectionType;
  const tabs = tabsFor(ct);
  const tabParam = loc.query.get('tab') as TabId | null;
  // The tab a library opens on is the person's choice (Settings · Home screen › Libraries › Opens on).
  const opensOn = readCustom(CUSTOM.opensOn, me.prefs)[view.Id] as TabId | undefined;
  const firstTab: TabId = tabs.some((t) => t.id === opensOn) ? opensOn! : tabs[0].id;
  const tab: TabId = tabs.some((t) => t.id === tabParam) ? tabParam! : firstTab;
  const isGrid = tab !== 'suggestions' && tab !== 'genres';
  const sortParam = loc.query.get('sort') as SortId | null;
  const sort = SORTS.find((s) => s.id === sortParam) ?? (tab === 'collections' || tab === 'artists' ? SORTS[1] : SORTS[0]);
  const order = (loc.query.get('order') === 'asc' || loc.query.get('order') === 'desc' ? loc.query.get('order') : sort.order) as 'asc' | 'desc';
  const filters = readFilters(loc.query);
  const filterKey = JSON.stringify(filters);
  const layoutKey = `seedy.libraryLayout.${view.Id}`;
  const [layout, setLayoutState] = useState<'grid' | 'list'>(() => safeLocal(layoutKey, 'grid'));
  const setLayout = (l: 'grid' | 'list') => { setLayoutState(l); saveLocal(layoutKey, l); };
  const [sheet, setSheet] = useState(false);

  const query = useMemo(() => {
    const q = applyFilters(baseQuery(view, tab), filters);
    return { ...q, sortBy: [...sort.fields], sortOrder: order === 'asc' ? t('Ascending') : t('Descending') } as ItemsQuery;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [view.Id, tab, sort.id, order, filterKey]);

  const fetchPage = (start: number, limit: number, signal: AbortSignal): Promise<ItemsResult> =>
    !isGrid ? Promise.resolve({ Items: [], TotalRecordCount: 0 })
      // Jellyfin's random order isn't stable between pages, so Random is one shuffled page of up to 120.
      : sort.id === 'Random' ? (start > 0
        ? Promise.resolve({ Items: [], TotalRecordCount: start })
        : getItems({ ...query, startIndex: 0, limit: 120 }, signal).then((r) => ({ ...r, TotalRecordCount: r.Items.length })))
      : tab === 'artists' ? fetchArtists(view, { ...query, startIndex: start, limit }, signal)
      : getItems({ ...query, startIndex: start, limit }, signal);
  const paged = usePaged(fetchPage, [view.Id, tab, sort.id, order, filterKey, isGrid], 60);
  const sel = useSelection(paged.items);
  useEffect(() => { sel.stop(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [view.Id, tab]);
  /* After the bar or a dialog changed titles: fresh cards for the picked ones, a fresh page after a delete. */
  const managed = useStore(manageChanged);
  useEffect(() => {
    if (!managed.n) return;
    if (managed.deleted.length) {
      sel.set(sel.items.filter((i) => !managed.deleted.includes(i.Id)));
      paged.reload();
      libTotal.reload();
    } else refreshPicked();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [managed.n]);
  const refreshPicked = () => {
    const ids = sel.items.map((i) => i.Id);
    if (!ids.length) return;
    getItems({ ids, fields: CARD_FIELDS }).then((r) => r.Items.forEach(paged.replace), () => paged.reload());
  };
  const selectAll = async () => {
    try {
      const r = await getItems({ ...query, startIndex: 0, limit: paged.total, enableImageTypes: [], fields: [] });
      sel.set(r.Items);
    } catch {
      toast(t('Couldn’t select everything'), 'error');
    }
  };

  /* The title count is the whole library, not the filtered view. */
  const libTotal = useAsync(
    () => getItems({ parentId: view.Id, includeItemTypes: typesFor(ct), limit: 0, enableImageTypes: [], fields: [] }).then((r) => r.TotalRecordCount),
    [view.Id]
  );

  // Shuffle picks from what this tab shows (filters included); the player turns a show into its next episode.
  const shuffle = async () => {
    const q: ItemsQuery = tab !== 'suggestions' && tab !== 'genres' && tab !== 'artists'
      ? { ...query, startIndex: undefined }
      : applyFilters({ parentId: view.Id, includeItemTypes: playableTypesFor(ct), isMissing: false }, filters);
    const pick = await playRandom(q).catch(() => undefined);
    if (pick) navigate(playPath(pick));
    else toast(t('Nothing to play here yet'), 'error');
  };

  const setFilters = (f: Filters) => setQuery({ ...filterParams(f), tab: tab === 'genres' || tab === 'suggestions' ? undefined : loc.query.get('tab') });
  const removeFlag = (kind: keyof Filters, value: string | number) => {
    const next: Filters = { ...filters, [kind]: (filters[kind] as (string | number)[]).filter((x) => x !== value) } as Filters;
    setFilters(next);
  };
  const chips: { key: string; label: string; remove: () => void }[] = [
    ...filters.flags.map((f) => ({ key: 'f' + f, label: t(FLAGS.find((x) => x.id === f)!.label), remove: () => removeFlag('flags', f) })),
    ...filters.genres.map((g) => ({ key: 'g' + g, label: g, remove: () => removeFlag('genres', g) })),
    ...filters.decades.map((d) => ({ key: 'd' + d, label: `${d}s`, remove: () => removeFlag('decades', d) })),
    ...filters.ratings.map((r) => ({ key: 'r' + r, label: r, remove: () => removeFlag('ratings', r) }))
  ];

  const count = libTotal.data;
  return (
    <div class="page library">
      <Header active={{ viewId: view.Id }} />
      <main class="page-main">
        <PageHead
          title={view.Name}
          count={count !== undefined ? plural(count, ct === 'music' ? 'album' : 'title') : undefined}
          actions={sel.active && isGrid ? undefined :
            <>
              <button type="button" class="btn btn-primary is-md lib-shuffle" onClick={shuffle}>
                <Icon name="shuffle" size={18} stroke={2.2} />
                {t('Shuffle')}
              </button>
              <MenuButton
                width={260}
                button={({ ref, open, toggle }) => (
                  <button ref={ref} type="button" class="btn icon-btn is-48" aria-label={t('Library options')} aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={toggle}>
                    <Icon name="more" size={20} />
                  </button>
                )}
              >
                {(close) => (
                  <>
                    {isGrid && <MenuItem icon="check" label={t('Select titles')} sub={t('Act on several at once')} onSelect={() => { close(); sel.start(); }} />}
                    <MenuItem icon="refresh" label={t('Refresh')} onSelect={() => { close(); paged.reload(); libTotal.reload(); }} />
                    <MenuItem icon="link" label={t('Copy link')} onSelect={() => {
                      close();
                      navigator.clipboard.writeText(window.location.href).then(() => toast(t('Link copied')), () => toast("Couldn't copy the link", 'error'));
                    }} />
                    <MenuItem icon="external" label={t('Open in Jellyfin')} href={jellyfinLibraryUrl(view)} />
                    {me.isAdmin && (
                      <>
                        <div class="menu-sep" />
                        <MenuItem icon="refresh" label={t('Scan library files')} sub={t('Look for new and changed media')} onSelect={() => {
                          close();
                          post<void>(`/Items/${view.Id}/Refresh`, undefined, { Recursive: true, MetadataRefreshMode: 'Default', ImageRefreshMode: 'Default', ReplaceAllMetadata: false, ReplaceAllImages: false })
                            .then(() => toast(`Scanning ${view.Name}`, 'pending'), () => toast("Couldn't start the scan", 'error'));
                        }} />
                      </>
                    )}
                  </>
                )}
              </MenuButton>
            </>
          }
        />

        {sel.active && isGrid && (
          <SelectionBar
            sel={sel}
            total={paged.total}
            kindLabel={(n) => t('{n} selected', { n })}
            onSelectAll={selectAll}
            onChanged={refreshPicked}
          />
        )}

        <div class="lib-toolbar">
          <nav aria-label={t('Library views')} class="tab-row hscroll">
            {tabs.map((tb) => (
              <Link
                key={tb.id}
                to={`/library/${view.Id}${tb.id === firstTab ? '' : '?tab=' + tb.id}`}
                replace
                class="tab"
                aria-current={tb.id === tab ? 'page' : undefined}
              >
                {t(tb.label)}
              </Link>
            ))}
          </nav>
          {isGrid && (
            <div class="lib-tools">
              <SortButton sort={sort.id} order={order} onChange={(s, o) => setQuery({ sort: s, order: o })} />
              {tab !== 'artists' && (
                <button type="button" class="btn btn-secondary lib-filters" aria-haspopup="dialog" onClick={() => setSheet(true)}>
                  <Icon name="filters" size={18} />
                  {t('Filters')}
                  {filterCount(filters) > 0 && <span class="accent-badge" aria-label={`${filterCount(filters)} active`}>{filterCount(filters)}</span>}
                </button>
              )}
              <div role="group" aria-label={t('Layout')} class="seg is-icons">
                <button type="button" class="seg-btn" aria-label={t('Poster grid')} aria-pressed={layout === 'grid' ? 'true' : 'false'} onClick={() => setLayout('grid')}>
                  <Icon name="grid" size={18} />
                </button>
                <button type="button" class="seg-btn" aria-label={t('List')} aria-pressed={layout === 'list' ? 'true' : 'false'} onClick={() => setLayout('list')}>
                  <Icon name="list" size={18} />
                </button>
              </div>
            </div>
          )}
        </div>

        {chips.length > 0 && isGrid && (
          <div class="lib-chips" aria-label={t('Active filters')}>
            {chips.map((c) => (
              <span key={c.key} class="fchip">
                {c.label}
                <button type="button" aria-label={t('Remove {name} filter', { name: c.label })} onClick={c.remove}><Icon name="close" size={14} stroke={2.6} /></button>
              </span>
            ))}
            <button type="button" class="btn btn-ghost is-sm" onClick={() => setFilters({ flags: [], genres: [], decades: [], ratings: [] })}>{t('Clear all')}</button>
          </div>
        )}

        {tab === 'suggestions' ? <Suggestions view={view} />
          : tab === 'genres' ? <GenreTiles view={view} />
          : <GridBody view={view} tab={tab} paged={paged} layout={layout} query={query} sort={sort.id} order={order} hasFilters={chips.length > 0} onClear={() => setFilters({ flags: [], genres: [], decades: [], ratings: [] })} select={tab === 'artists' ? undefined : sel.grid} />}
      </main>

      {sheet && (
        <FilterSheet
          view={view}
          base={{ ...baseQuery(view, tab), sortBy: undefined }}
          initial={filters}
          onClose={() => setSheet(false)}
          onApply={(f) => { setSheet(false); setFilters(f); }}
        />
      )}
    </div>
  );
}

function jellyfinLibraryUrl(view: BaseItem) {
  const ct = view.CollectionType;
  if (ct === 'movies') return `${JELLYFIN_WEB}#/movies.html?topParentId=${view.Id}&collectionType=movies`;
  if (ct === 'tvshows') return `${JELLYFIN_WEB}#/tv.html?topParentId=${view.Id}&collectionType=tvshows`;
  if (ct === 'music') return `${JELLYFIN_WEB}#/music.html?topParentId=${view.Id}&collectionType=music`;
  return `${JELLYFIN_WEB}#/list.html?parentId=${view.Id}`;
}

/* ───────── Sort ───────── */

function SortButton({ sort, order, onChange }: { sort: SortId; order: 'asc' | 'desc'; onChange: (s: SortId, o: 'asc' | 'desc') => void }) {
  const cur = SORTS.find((s) => s.id === sort) ?? SORTS[0];
  return (
    <MenuButton
      width={240}
      button={({ ref, open, toggle }) => (
        <button ref={ref} type="button" class="btn btn-secondary lib-sort" aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} aria-label={order === 'asc' ? t('Sort by {name}, ascending', { name: t(cur.label) }) : t('Sort by {name}, descending', { name: t(cur.label) })} onClick={toggle}>
          <Icon name="sort" size={18} />
          <span class="lib-sort-k">{t('Sort')}</span> {t(cur.label)}
          <Icon name="chevronDown" size={16} stroke={2.2} />
        </button>
      )}
    >
      {(close) => (
        <>
          <span class="menu-label">{t('Sort by')}</span>
          {SORTS.map((s) => (
            <MenuItem key={s.id} label={t(s.label)} pressed={s.id === sort} onSelect={() => { close(); onChange(s.id, s.id === sort ? order : s.order); }} />
          ))}
          {sort !== 'Random' && (
            <>
              <div class="menu-sep" />
              <MenuItem icon="sortEpisodes" label={t('Ascending')} sub={sort === 'SortName' ? 'A to Z' : sort === 'Runtime' ? t('Shortest first') : t('Oldest or lowest first')} pressed={order === 'asc'} onSelect={() => { close(); onChange(sort, 'asc'); }} />
              <MenuItem icon="sortEpisodes" label={t('Descending')} sub={sort === 'SortName' ? 'Z to A' : sort === 'Runtime' ? t('Longest first') : t('Newest or highest first')} pressed={order === 'desc'} onSelect={() => { close(); onChange(sort, 'desc'); }} />
            </>
          )}
        </>
      )}
    </MenuButton>
  );
}

/* ───────── Grid / list with A–Z rail and infinite scroll ───────── */

function GridBody({ view, tab, paged, layout, query, sort, order, hasFilters, onClear, select }: {
  view: BaseItem; tab: TabId; paged: ReturnType<typeof usePaged>; layout: 'grid' | 'list'; query: ItemsQuery; sort: SortId; order: 'asc' | 'desc';
  hasFilters: boolean; onClear: () => void; select?: GridSelect;
}) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [letter, setLetter] = useState<string | null>(null);
  const pending = useRef<string | null>(null);
  const lockUntil = useRef(0);

  /* Highlight the letter of the first card on screen. */
  useEffect(() => {
    let raf = 0;
    const update = () => {
      raf = 0;
      if (Date.now() < lockUntil.current) return;
      const el = containerRef.current;
      if (!el) return;
      const cards = el.querySelectorAll<HTMLElement>('[data-sort]');
      let found: HTMLElement | null = null;
      for (const c of Array.from(cards)) {
        const r = c.getBoundingClientRect();
        if (r.bottom > headerHeight() + 16) { found = c; break; }
      }
      const ch = (found?.dataset.sort ?? '').toUpperCase();
      setLetter(found ? (/[A-Z]/.test(ch) ? ch : '#') : null);
    };
    const onScroll = () => { if (!raf) raf = requestAnimationFrame(update); };
    update();
    window.addEventListener('scroll', onScroll, { passive: true });
    window.addEventListener('resize', onScroll);
    return () => { window.removeEventListener('scroll', onScroll); window.removeEventListener('resize', onScroll); if (raf) cancelAnimationFrame(raf); };
  }, [paged.items.length, layout]);

  const doJump = async (l: string) => {
    let index = 0;
    if (l !== '#') {
      const q = { ...query, nameLessThan: l, limit: 0, startIndex: 0, enableImageTypes: [], fields: [] as string[] };
      const r = tab === 'artists' ? await fetchArtists(view, q).catch(() => null) : await getItems(q).catch(() => null);
      index = r?.TotalRecordCount ?? 0;
    }
    await paged.ensure(index);
    requestAnimationFrame(() => requestAnimationFrame(() => {
      const cards = containerRef.current?.querySelectorAll<HTMLElement>('[data-sort]');
      const target = cards?.[Math.min(index, (cards?.length ?? 1) - 1)];
      if (target) {
        setLetter(l);
        lockUntil.current = Date.now() + 900;
        const y = target.getBoundingClientRect().top + window.scrollY - headerHeight() - 24;
        window.scrollTo({ top: y, behavior: 'smooth' });
        target.querySelector<HTMLElement>('a')?.focus({ preventScroll: true });
      }
    }));
  };

  const jump = (l: string) => {
    if (sort !== 'SortName' || order !== 'asc') {
      pending.current = l;
      setQuery({ sort: 'SortName', order: 'asc' });
      return;
    }
    doJump(l);
  };

  useEffect(() => {
    if (pending.current && !paged.loading && sort === 'SortName' && order === 'asc') {
      const l = pending.current;
      pending.current = null;
      doJump(l);
    }
  }, [paged.loading, sort, order]);

  if (paged.error && !paged.items.length) return <ErrorState error={paged.error} retry={paged.reload} />;
  if (paged.loading && !paged.items.length) return <GridSkeleton count={20} />;
  if (!paged.items.length) {
    if (hasFilters) return <EmptyState icon="filters" title={t('Nothing matches these filters')} text={t('Try removing a filter or two.')} action={<button type="button" class="btn btn-primary is-sm" onClick={onClear}>{t('Clear all')}</button>} />;
    if (tab === 'favorites') return <EmptyState icon="heart" title={`No favorites in ${view.Name} yet`} text={t('Tap the heart on any title and it will show up here.')} />;
    if (tab === 'collections') return <EmptyState icon="folder" title={t('No collections yet')} text={t('Collections group related titles, like a film series. Create them in Jellyfin and they\'ll show up here.')} />;
    return <EmptyState icon="film" title={`${view.Name} is empty`} text={t('Once the server finds media in this library it will show up here.')} />;
  }

  const showRail = paged.total > 1;
  return (
    <>
      <div class="lib-body">
        <div class="lib-main" ref={containerRef}>
          {layout === 'list' ? <ListRows items={paged.items} onReplace={paged.replace} /> : <CardGrid items={paged.items} onReplace={paged.replace} select={select} />}
          {!paged.done && <Sentinel onVisible={paged.loadMore} token={paged.items.length} disabled={paged.loading || !!paged.error} />}
          {paged.loading && paged.items.length > 0 && <div class="lib-loading"><span class="spinner" aria-label={t('Loading more')} /></div>}
          {paged.error && paged.items.length > 0 && (
            <div class="grid-more"><button type="button" class="btn btn-secondary" onClick={paged.loadMore}><Icon name="refresh" size={18} />{t('Load more')}</button></div>
          )}
        </div>
        {showRail && (
          <nav aria-label={t('Jump to letter')} class="az-rail">
            {LETTERS.map((l) => (
              <button key={l} type="button" class="az-letter" aria-label={l === '#' ? t('Jump to numbers and symbols') : t('Jump to {letter}', { letter: l })} aria-current={letter === l ? 'true' : undefined} onClick={() => jump(l)}>
                {l}
              </button>
            ))}
          </nav>
        )}
      </div>
      <div class="lib-foot" aria-live="polite">
        <div class="lib-foot-bar" aria-hidden="true"><i style={{ width: pct(paged.total ? paged.items.length / paged.total : 1) }} /></div>
        Showing {paged.items.length} of {paged.total}
      </div>
    </>
  );
}

/* ───────── Filters sheet ───────── */

interface FilterOptions { Genres?: string[]; OfficialRatings?: string[]; Years?: number[] }

function FilterSheet({ view, base, initial, onClose, onApply }: { view: BaseItem; base: ItemsQuery; initial: Filters; onClose: () => void; onApply: (f: Filters) => void }) {
  const [draft, setDraft] = useState<Filters>(initial);
  const opts = useAsync(
    () => get<FilterOptions>('/Items/Filters', { userId: uid(), parentId: base.parentId ?? view.Id, includeItemTypes: base.includeItemTypes?.join(',') }),
    [view.Id]
  );
  const [count, setCount] = useState<number | null>(null);
  useEffect(() => {
    setCount(null);
    let alive = true;
    const t = window.setTimeout(() => {
      getItems({ ...applyFilters(base, draft), limit: 0, enableImageTypes: [], fields: [] })
        .then((r) => alive && setCount(r.TotalRecordCount), () => alive && setCount(null));
    }, 220);
    // An older count must not land after a newer one.
    return () => { alive = false; window.clearTimeout(t); };
  }, [JSON.stringify(draft)]);

  const toggle = (kind: keyof Filters, value: string | number) => {
    setDraft((d) => {
      const list = d[kind] as (string | number)[];
      let next = list.includes(value) ? list.filter((x) => x !== value) : [...list, value];
      /* Watched and Unwatched exclude each other. */
      if (kind === 'flags' && value === 'watched' && next.includes('watched')) next = next.filter((x) => x !== 'unwatched');
      if (kind === 'flags' && value === 'unwatched' && next.includes('unwatched')) next = next.filter((x) => x !== 'watched');
      return { ...d, [kind]: next } as Filters;
    });
  };
  const decades = Array.from(new Set((opts.data?.Years ?? []).map((y) => Math.floor(y / 10) * 10))).sort((a, b) => b - a);
  const genres = opts.data?.Genres ?? [];
  const ratings = opts.data?.OfficialRatings ?? [];
  const on = (kind: keyof Filters, v: string | number) => (draft[kind] as (string | number)[]).includes(v);

  const group = (label: string, children: ComponentChildren) => (
    <fieldset class="fgroup">
      <legend class="menu-label">{label}</legend>
      <div class="fopts">{children}</div>
    </fieldset>
  );
  const opt = (kind: keyof Filters, value: string | number, label: string) => (
    <button key={String(value)} type="button" class="fopt" aria-pressed={on(kind, value) ? 'true' : 'false'} onClick={() => toggle(kind, value)}>
      {on(kind, value) && <Icon name="check" size={14} stroke={2.8} />}
      {label}
    </button>
  );

  return (
    <Sheet open onClose={onClose} label={t('Filters')} wide>
      <div class="sheet-head">
        <h2 class="sheet-title">{t('Filters')}</h2>
        <button type="button" class="btn icon-btn is-bare is-40" aria-label={t('Close filters')} onClick={onClose}><Icon name="close" size={18} /></button>
      </div>
      {group(t('Show (verb)'), FLAGS.slice(0, 4).map((f) => opt('flags', f.id, t(f.label))))}
      {group(t('Quality'), FLAGS.slice(4).map((f) => opt('flags', f.id, t(f.label))))}
      {opts.loading && !opts.data ? (
        <div class="fopts" aria-busy="true">{Array.from({ length: 8 }, (_, i) => <span key={i} class="skel" style={{ width: '90px', height: '36px', borderRadius: '999px' }} />)}</div>
      ) : (
        <>
          {genres.length > 0 && group('Genres', genres.map((g) => opt('genres', g, g)))}
          {decades.length > 0 && group('Decades', decades.map((d) => opt('decades', d, `${d}s`)))}
          {ratings.length > 0 && group('Parental rating', ratings.map((r) => opt('ratings', r, r)))}
        </>
      )}
      <div class="sheet-actions fsheet-actions">
        <button type="button" class="btn btn-ghost is-light" disabled={!filterCount(draft)} onClick={() => setDraft({ flags: [], genres: [], decades: [], ratings: [] })}>Clear all</button>
        <button type="button" class="btn btn-primary is-sm" onClick={() => onApply(draft)}>
          {count === null ? t('Show titles') : count === 0 ? t('No titles match') : t('Show {count}', { count: plural(count, 'title') })}
        </button>
      </div>
    </Sheet>
  );
}

/* ───────── Suggestions ───────── */

function Suggestions({ view }: { view: BaseItem }) {
  const ct = view.CollectionType;
  const types = typesFor(ct);
  const seeds = useAsync(async () => {
    if (ct === 'tvshows') {
      const r = await getItems({ parentId: view.Id, includeItemTypes: ['Episode'], sortBy: ['DatePlayed'], sortOrder: 'Descending', filters: ['IsPlayed'], limit: 30, enableTotalRecordCount: false });
      const seen = new Map<string, string>();
      for (const e of r.Items) if (e.SeriesId && !seen.has(e.SeriesId)) seen.set(e.SeriesId, e.SeriesName ?? '');
      return Array.from(seen, ([id, name]) => ({ id, name })).slice(0, 2);
    }
    const r = await getItems({ parentId: view.Id, includeItemTypes: types, sortBy: ['DatePlayed'], sortOrder: 'Descending', filters: ['IsPlayed'], limit: 2, enableTotalRecordCount: false });
    return r.Items.map((i) => ({ id: i.Id, name: i.Name }));
  }, [view.Id]);
  return (
    <div class="bleed lib-rows">
      {seeds.data?.map((s) => <BecauseRow key={s.id} seed={s} />)}
      <SuggestRow title={t('Top rated')} sub={t('Not watched yet')} q={{ parentId: view.Id, includeItemTypes: types, filters: ['IsUnplayed'], sortBy: ['CommunityRating', 'SortName'], sortOrder: 'Descending', limit: 16 }} seeAll={`/library/${view.Id}?sort=CommunityRating&f=unwatched`} />
      <SuggestRow title={t('Recently added')} sub={t('Not watched yet')} q={{ parentId: view.Id, includeItemTypes: types, filters: ['IsUnplayed'], sortBy: ['DateCreated', 'SortName'], sortOrder: 'Descending', limit: 16 }} seeAll={`/library/${view.Id}?sort=DateCreated&f=unwatched`} />
      <SuggestRow title={t('Something different')} sub={t('Picked at random')} q={{ parentId: view.Id, includeItemTypes: types, sortBy: ['Random'], limit: 16 }} />
    </div>
  );
}

function BecauseRow({ seed }: { seed: { id: string; name: string } }) {
  const { data, loading } = useAsync(() => getSimilar(seed.id, 16).then((r) => r.Items), [seed.id]);
  const title = <><span class="lead">{t('Because you watched')}</span> {seed.name}</>;
  if (loading && !data) return <RowSkeleton title={title} />;
  if (!data?.length) return null;
  return (
    <Row title={title} tight seeAll={`/list/similar?${new URLSearchParams({ seed: seed.id, name: seed.name })}`} label={t('Because you watched {title}', { title: seed.name })}>
      {data.map((it) => <ItemCard key={it.Id} item={it} width={300} meta={join(yearOf(it), it.Genres?.[0])} />)}
    </Row>
  );
}

function SuggestRow({ title, sub, q, seeAll }: { title: string; sub?: string; q: ItemsQuery; seeAll?: string }) {
  const [ref, seen] = useInView<HTMLDivElement>('600px');
  const { data, loading } = useAsync(() => (seen ? getItems({ ...q, enableTotalRecordCount: false }).then((r) => r.Items) : Promise.resolve(null)), [seen, JSON.stringify(q)]);
  if (!seen || (loading && !data)) return <div ref={ref}><RowSkeleton title={title} /></div>;
  if (!data?.length) return null;
  return (
    <Row title={title} sub={sub} seeAll={seeAll} label={title}>
      {data.map((it) => <ItemCard key={it.Id} item={it} width={300} meta={join(yearOf(it), it.Genres?.[0])} watched={!!it.UserData?.Played} />)}
    </Row>
  );
}

/* ───────── Genres ───────── */

function GenreTiles({ view }: { view: BaseItem }) {
  const types = typesFor(view.CollectionType);
  const used = useRef(new Set<string>());
  const { data, loading, error, reload } = useAsync(() => getGenres(view.Id, types).then((r) => r.Items), [view.Id]);
  if (error && !data) return <ErrorState error={error} retry={reload} />;
  if (loading && !data) {
    return <div class="genre-tiles">{Array.from({ length: 8 }, (_, i) => <div key={i} class="lib-tile genre-tile skel" />)}</div>;
  }
  if (!data?.length) return <EmptyState icon="film" title={t('No genres yet')} text={t('Genres come from each title\'s metadata.')} />;
  return (
    <div class="genre-tiles">
      {data.map((g) => <GenreTile key={g.Id} view={view} genre={g} types={types} used={used.current} />)}
    </div>
  );
}

/* Art comes from a title in the genre; tiles avoid repeating a picture another tile already uses. */
function GenreTile({ view, genre, types, used }: { view: BaseItem; genre: BaseItem; types?: string[]; used: Set<string> }) {
  const [ref, seen] = useInView<HTMLDivElement>('400px');
  const [loaded, setLoaded] = useState(false);
  const [src, setSrc] = useState<string | undefined>();
  useEffect(() => {
    if (!seen) return;
    let alive = true;
    getItems({ parentId: view.Id, genres: [genre.Name], includeItemTypes: types, sortBy: ['CommunityRating', 'SortName'], sortOrder: 'Descending', limit: 12, enableTotalRecordCount: false })
      .then((r) => {
        if (!alive) return;
        const withArt = r.Items.filter((i) => i.BackdropImageTags?.length || i.ImageTags?.Thumb);
        const pick = withArt.find((i) => !used.has(i.Id)) ?? withArt[0] ?? r.Items[0];
        if (!pick) return;
        used.add(pick.Id);
        setSrc(backdropUrl(pick, 640) ?? cardArt(pick, 640).src);
      })
      .catch(() => undefined);
    return () => { alive = false; };
  }, [seen, genre.Name]);
  return (
    <div ref={ref} class="genre-slot">
      <Link to={`/library/${view.Id}?${new URLSearchParams({ genres: genre.Name })}`} class="lib-tile genre-tile">
        {src && <img src={src} alt="" class="art" loading="lazy" style={{ opacity: loaded ? 1 : 0, transition: 'opacity 240ms ease' }} onLoad={() => setLoaded(true)} />}
        <div aria-hidden="true" class="lib-tile-shade" />
        <span class="lib-tile-name">{genre.Name}</span>
      </Link>
    </div>
  );
}
