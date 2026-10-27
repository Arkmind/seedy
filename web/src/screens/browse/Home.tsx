import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import {
  getItems, getLatest, getNextUp, getResume, getSimilar, getUpcomingEpisodes, CARD_FIELDS
} from '../../api/jellyfin';
import {
  getFeatured, getHome, getTop10, seerrDiscover, seerrUpcoming, type FeaturedEntry, type HomeRow
} from '../../api/seedy';
import { backdropUrl, tmdbImage } from '../../api/images';
import type { BaseItem } from '../../api/types';
import { meStore, userStore, viewsStore } from '../../lib/app-state';
import { plural, progressFraction, runtime, score, whenLabel, yearRange, ratingLabel, dayOf, fitTitle } from '../../lib/format';
import { noteOrigin } from '../../lib/origin';
import { navigate } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { ItemCard, LibraryTile, PosterCard, Top10Card, UpcomingCard, detailsPath, playPath, seerrPath } from '../../ui/cards';
import { Header } from '../../ui/navbar/Header';
import { Icon } from '../../ui/icons';
import { MenuButton, MenuItem, toast } from '../../ui/overlay';
import { Row } from '../../ui/Row';
import {
  Lazy, NextUpCard, ResumeCard, RowSkeleton, groupUpcoming, join, resolvePlayable, seasonsLabel, tileView, toggleFavorite,
  togglePlayed, typeLabel, useRequests, yearOf
} from './parts';
import { TrailerLayer, useHeroTrailer } from './trailer';
import { ArrivedNotice } from '../me/arrivals';
import { CUSTOM, getNextUpForMe, savePrefsPatch as queuePrefs, readCustom, userConfig } from '../me/prefs';
import './home.css';
import { t } from '../../lib/i18n';

const BROWSABLE = new Set(['movies', 'tvshows', 'music', 'boxsets', 'homevideos', 'musicvideos', 'mixed', 'playlists', undefined, '']);
const LATEST_TYPES = new Set(['movies', 'tvshows', 'music', 'homevideos', 'musicvideos', 'mixed', undefined, '']);
const EAGER_ROWS = 3;

/* When the plugin can't build the home screen, lay out the basics from Jellyfin alone. */
function defaultRows(views: BaseItem[]): HomeRow[] {
  const rows: HomeRow[] = [
    { id: 'featured', kind: 'featured' },
    { id: 'continue', kind: 'continue' },
    { id: 'nextup', kind: 'nextup' },
    { id: 'mymedia', kind: 'mymedia' }
  ];
  for (const v of views) {
    if (!LATEST_TYPES.has(v.CollectionType)) continue;
    rows.push({ id: 'latest:' + v.Id, kind: 'latest', libraryId: v.Id, libraryName: v.Name, collectionType: v.CollectionType });
  }
  return rows;
}

export function Home() {
  const me = useStore(meStore);
  const [rows, setRows] = useState<HomeRow[] | null>(null);
  const [heroState, setHeroState] = useState<'loading' | 'shown' | 'none'>('loading');

  useEffect(() => {
    let alive = true;
    getHome()
      .then((r) => (r?.rows?.length ? r.rows : null))
      .catch(() => null)
      .then((list) => { if (alive) setRows(list ?? defaultRows(viewsStore.get())); });
    return () => { alive = false; };
  }, []);

  const prefs = me.prefs;
  // Libraries the person keeps out of Home rows (Settings · Home screen › Libraries › Home rows).
  const latestExcludes = userConfig(useStore(userStore)).LatestItemsExcludes ?? [];
  const visible = (rows ?? []).filter((r) => {
    if (r.kind === 'latest' && r.libraryId && latestExcludes.includes(r.libraryId)) return false;
    if (prefs.hiddenRows?.includes(r.id)) return false;
    if (r.kind === 'because' && r.seedId && prefs.hiddenSeeds?.includes(r.seedId)) return false;
    if (r.kind === 'discover' && !me.requests.discover) return false;
    if (r.kind === 'upcomingMovies' && !me.requests.upcomingMovies) return false;
    return true;
  });
  const featured = visible.find((r) => r.kind === 'featured');
  const rest = visible.filter((r) => r.kind !== 'featured');
  const overlay = rows === null || (!!featured && heroState !== 'none');

  // Through the shared prefs queue, so two quick hides reach the server in order (and the second keeps the first).
  const savePrefsPatch = async (patch: { hiddenRows?: string[]; hiddenSeeds?: string[] }, message: string) => {
    if (await queuePrefs(patch)) toast(message);
    else toast("Couldn't save that change", 'error');
  };
  const hideRow = (row: HomeRow) =>
    savePrefsPatch({ hiddenRows: [...(meStore.get().prefs.hiddenRows ?? []), row.id] }, t('Row hidden'));
  const hideSeed = (row: HomeRow) =>
    row.seedId && savePrefsPatch({ hiddenSeeds: [...(meStore.get().prefs.hiddenSeeds ?? []), row.seedId] }, row.seedName ? t("{title} won't be used for suggestions", { title: row.seedName }) : t("That title won't be used for suggestions"));

  return (
    <div class="home">
      <Header variant={overlay ? 'overlay' : 'plain'} active="home" />
      {rows === null ? <HeroSkeleton /> : featured && <Featured onState={setHeroState} />}
      <main class={'home-main' + (overlay ? '' : ' is-plain')}>
        {rows !== null && <ArrivedNotice />}
        {rows === null && (
          <>
            <RowSkeleton title={t('Continue watching')} width={340} />
            <RowSkeleton title={t('Next up')} width={340} />
          </>
        )}
        {rest.map((row, i) => (
          <Lazy key={row.id} eager={i < EAGER_ROWS} placeholder={<RowSkeleton title={placeholderTitle(row)} width={row.kind === 'discover' || row.kind.startsWith('upcoming') ? 180 : 300} />}>
            {() => <HomeRowView row={row} onHideRow={hideRow} onHideSeed={hideSeed} />}
          </Lazy>
        ))}
      </main>
    </div>
  );
}

function placeholderTitle(row: HomeRow): ComponentChildren {
  switch (row.kind) {
    case 'because': return <><span class="lead">{t('Because you watched')}</span> {row.seedName}</>;
    case 'latest': return row.title ?? t('Latest {library}', { library: (row.libraryName ?? '').toLowerCase(), name: row.libraryName ?? '' });
    case 'genres': return row.title ?? row.genre;
    default: return row.title ?? t(DEFAULT_TITLES[row.kind] ?? '');
  }
}

const DEFAULT_TITLES: Partial<Record<HomeRow['kind'], string>> = {
  continue: 'Continue watching',
  nextup: 'Next up',
  top10: 'Top 10 this week',
  mymedia: 'My media',
  discover: 'Discover',
  upcomingMovies: 'Upcoming movies',
  upcomingShows: 'Upcoming shows',
  collections: 'Collection'
};

function HomeRowView({ row, onHideRow, onHideSeed }: { row: HomeRow; onHideRow: (r: HomeRow) => void; onHideSeed: (r: HomeRow) => void }) {
  switch (row.kind) {
    case 'continue': return <ContinueRow row={row} />;
    case 'nextup': return <NextUpRow row={row} />;
    case 'top10': return <Top10Row row={row} />;
    case 'because': return <BecauseRow row={row} onHideRow={onHideRow} onHideSeed={onHideSeed} />;
    case 'genres': return <GenreRow row={row} />;
    case 'mymedia': return <MyMediaRow row={row} />;
    case 'latest': return <LatestRow row={row} />;
    case 'discover': return <DiscoverRow row={row} />;
    case 'upcomingMovies': return <UpcomingMoviesRow row={row} />;
    case 'upcomingShows': return <UpcomingShowsRow row={row} />;
    case 'collections': return <CollectionRow row={row} />;
    default: return null;
  }
}

/* Cards credit the row they were opened from, so the play that follows can move the row up. */
function Slot({ id, kind, children }: { id: string; kind: string; children: ComponentChildren }) {
  return <div class="home-slot" onClickCapture={() => noteOrigin(id, kind)}>{children}</div>;
}

/* ───────── Featured ───────── */

async function loadFeatured(): Promise<FeaturedEntry[]> {
  const fromPlugin = await getFeatured().catch(() => null);
  if (fromPlugin && fromPlugin.length) return fromPlugin.slice(0, 5);
  /* Fallback: the newest titles that have backdrops. */
  const r = await getItems({
    includeItemTypes: ['Movie', 'Series'], sortBy: ['DateCreated'], sortOrder: 'Descending', limit: 12,
    fields: [...CARD_FIELDS, 'Genres'], enableImageTypes: ['Backdrop', 'Logo', 'Thumb', 'Primary']
  });
  const picks = r.Items.filter((i) => i.BackdropImageTags?.length).slice(0, 5);
  return Promise.all(picks.map(async (item) => {
    let play: BaseItem | undefined;
    if (item.Type === 'Series') play = (await getNextUp(1, item.Id).catch(() => null))?.Items[0];
    return { item, reason: item.Type === 'Series' ? t('New episodes') : t('Just added'), play };
  }));
}

function playLabel(entry: FeaturedEntry): string {
  const p = entry.play ?? entry.item;
  const verb = (p.UserData?.PlaybackPositionTicks ?? 0) > 0 ? t('Resume') : t('Play');
  if (p.Type === 'Episode' && p.IndexNumber !== undefined && p.IndexNumber !== null) {
    return p.ParentIndexNumber ? `${verb} S${p.ParentIndexNumber} · E${p.IndexNumber}` : `${verb} E${p.IndexNumber}`;
  }
  return verb;
}

function HeroSkeleton() {
  return (
    <section class="hero is-loading" aria-busy="true" aria-label={t('Featured')}>
      <div class="hero-body">
        <div class="hero-info">
          <span class="skel skel-line" style={{ width: '180px' }} />
          <span class="skel" style={{ width: 'min(480px, 80vw)', height: '76px', borderRadius: '10px' }} />
          <span class="skel skel-line" style={{ width: '320px' }} />
          <span class="skel skel-line" style={{ width: 'min(560px, 80vw)' }} />
          <span class="skel" style={{ width: '220px', height: '52px', borderRadius: '13px' }} />
        </div>
      </div>
    </section>
  );
}

function Featured({ onState }: { onState: (s: 'loading' | 'shown' | 'none') => void }) {
  const { data, loading } = useAsync(() => loadFeatured(), []);
  const [entries, setEntries] = useState<FeaturedEntry[]>([]);
  const [idx, setIdx] = useState(0);
  const [hover, setHover] = useState(false);
  const [focus, setFocus] = useState(false);
  const ref = useRef<HTMLElement>(null);

  const list = entries.length ? entries : data ?? [];
  const n = list.length;
  const cur = n ? list[Math.min(idx, n - 1)] : undefined;
  // The carousel moves on by itself unless the pointer or focus is in it.
  const autoAdvance = !hover && !focus && n > 1;
  const autoRef = useRef(autoAdvance);
  autoRef.current = autoAdvance;

  /* The slide's trailer fades in over the backdrop after a moment. At its end the carousel moves on, or the
     backdrop comes back while someone is looking at the slide. */
  const trailer = useHeroTrailer({
    itemId: cur?.item.Id,
    heroRef: ref,
    onEnded: () => { if (autoRef.current) setIdx((i) => (i + 1) % Math.max(1, n)); }
  });

  useEffect(() => { if (data) setEntries(data); }, [data]);
  useEffect(() => { onState(loading && !data ? 'loading' : entries.length || (data?.length ?? 0) ? 'shown' : 'none'); }, [loading, data, entries.length]);

  /* Auto-advance every 9 s; hovering or focusing inside the hero holds it, and so does a playing trailer. */
  useEffect(() => {
    if (!autoAdvance || trailer.holding) return;
    const t = window.setTimeout(() => setIdx((i) => (i + 1) % n), 9000);
    return () => window.clearTimeout(t);
  }, [idx, autoAdvance, n, trailer.holding]);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const fin = () => setFocus(true);
    const fout = (e: FocusEvent) => { if (!el.contains(e.relatedTarget as Node)) setFocus(false); };
    el.addEventListener('focusin', fin);
    el.addEventListener('focusout', fout);
    return () => { el.removeEventListener('focusin', fin); el.removeEventListener('focusout', fout); };
  }, [entries.length]);

  if (loading && !data) return <HeroSkeleton />;
  if (!cur) return null;

  const item = cur.item;
  const go = (d: number) => setIdx((i) => (i + d + n) % n);
  const update = (next: BaseItem) => setEntries(list.map((e) => (e.item.Id === next.Id ? { ...e, item: next } : e)));
  const fav = !!item.UserData?.IsFavorite;
  const played = !!item.UserData?.Played;
  const genres = (item.Genres ?? []).slice(0, 2).join(' · ');

  const play = async () => {
    trailer.stop();
    const target = cur.play ?? (await resolvePlayable(item));
    if (!target) { navigate(detailsPath(item)); return; }
    navigate(playPath(target, { from: 'featured' }));
  };
  const more = () => { noteOrigin(item.Id, 'featured'); navigate(detailsPath(item)); };

  return (
    <section
      ref={ref}
      class="hero"
      aria-roledescription="carousel"
      aria-label={t('Featured')}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      {list.map((e, i) => {
        // Settings · Display › Backdrops off: the banner keeps its text on the plain ground.
        const src = readCustom(CUSTOM.backdrops) ? backdropUrl(e.item, 1920) : undefined;
        return (
          <div key={e.item.Id} class={'hero-slide' + (i === idx ? ' is-on' : '')} aria-hidden="true">
            {src && Math.min(Math.abs(i - idx), n - Math.abs(i - idx)) <= 1 && <img src={src} alt="" class="hero-art" decoding="async" onLoad={(ev) => (ev.currentTarget as HTMLImageElement).classList.add('is-loaded')} />}
          </div>
        );
      })}
      <TrailerLayer trailer={trailer} />
      <div aria-hidden="true" class="hero-shade-x" />
      <div aria-hidden="true" class="hero-shade-b" />
      <div aria-hidden="true" class="hero-shade-t" />

      <div class="hero-body">
        <div key={item.Id} class="hero-info" role="group" aria-roledescription="slide" aria-label={`${idx + 1} of ${n}: ${item.Name}`}>
          <div class="overline hero-over"><span class="hero-dot" aria-hidden="true" /><span>{cur.reason ? t('{reason} · Featured', { reason: t(cur.reason) }) : t('Featured')}</span></div>
          <h1 class="hero-title fit-title" style={fitTitle(item.Name)}>{item.Name}</h1>
          <div class="hero-meta">
            {yearRange(item) && <span>{yearRange(item)}</span>}
            {item.OfficialRating && <span class="rating-badge">{ratingLabel(item.OfficialRating)}</span>}
            {item.Type === 'Series' ? seasonsLabel(item) && <span>{seasonsLabel(item)}</span> : runtime(item) && <span>{runtime(item)}</span>}
            {item.CommunityRating ? <span class="score"><Icon name="star" size={15} />{score(item.CommunityRating)}</span> : null}
            {genres && <span>{genres}</span>}
          </div>
          {item.Overview && <p class="hero-overview">{item.Overview}</p>}
          <div class="hero-actions">
            <button type="button" class="btn btn-primary" onClick={play}>
              <Icon name="play" size={20} />
              {playLabel(cur)}
            </button>
            <button type="button" class="btn btn-square" aria-label={t('More information')} onClick={more}>
              <Icon name="info" size={22} />
            </button>
            <button type="button" class="btn btn-square" aria-label={fav ? t('Remove from favorites') : t('Add to favorites')} aria-pressed={fav ? 'true' : 'false'} onClick={() => toggleFavorite(item, update)}>
              <Icon name="heart" size={22} filled={fav} />
            </button>
            <button type="button" class="btn btn-square" aria-label={played ? t('Mark as unwatched') : t('Mark as watched')} aria-pressed={played ? 'true' : 'false'} onClick={() => togglePlayed(item, update)}>
              <Icon name="check" size={22} stroke={played ? 3 : 2.2} />
            </button>
          </div>
        </div>
        {(n > 1 || trailer.allowed) && (
          <div class="hero-nav">
            {trailer.allowed && (
              // Keeps its place while trailers may play, so the pager never shifts; shows while one is running, even in
              // the seconds it's still hidden, so it can always be muted.
              <button
                type="button"
                class={'btn icon-btn is-glass hero-sound' + (trailer.holding ? ' is-on' : '')}
                aria-label={trailer.sound ? t('Mute trailer') : t('Play trailer sound')}
                aria-hidden={trailer.holding ? undefined : 'true'}
                tabIndex={trailer.holding ? 0 : -1}
                onClick={trailer.toggleSound}
              >
                <Icon name={trailer.sound ? 'volume' : 'mute'} size={20} />
              </button>
            )}
            {n > 1 && (
              <>
                <button type="button" class="btn icon-btn is-glass" aria-label={t('Previous featured title')} onClick={() => go(-1)}>
                  <Icon name="chevronLeft" size={20} />
                </button>
                <span class="hero-count" aria-live="polite">{idx + 1} <span>/ {n}</span></span>
                <button type="button" class="btn icon-btn is-glass" aria-label={t('Next featured title')} onClick={() => go(1)}>
                  <Icon name="chevronRight" size={20} />
                </button>
              </>
            )}
          </div>
        )}
      </div>
    </section>
  );
}

/* ───────── Rows ───────── */

function useRowData<T>(fn: () => Promise<T[]>, deps: unknown[] = []) {
  const { data, loading, error } = useAsync(() => fn(), deps);
  return { items: data ?? [], pending: loading && !data, failed: !!error };
}

function ContinueRow({ row }: { row: HomeRow }) {
  const title = row.title ?? t('Continue watching');
  const { items, pending } = useRowData(() => getResume(16).then((r) => r.Items));
  if (pending) return <RowSkeleton title={title} width={340} />;
  if (!items.length) return null;
  return (
    <Row title={title} seeAll="/list/continue" label={title}>
      {items.map((it) => <Slot key={it.Id} id={it.Id} kind={row.kind}><ResumeCard item={it} width={340} origin={row.kind} /></Slot>)}
    </Row>
  );
}

function NextUpRow({ row }: { row: HomeRow }) {
  const title = row.title ?? t('Next up');
  // Next up follows Settings · Home screen: drop shows after N days, include rewatches.
  const { items, pending } = useRowData(() => getNextUpForMe(16).then((r) => r.Items));
  if (pending) return <RowSkeleton title={title} width={340} />;
  if (!items.length) return null;
  return (
    <Row title={title} seeAll="/list/nextup" label={title}>
      {items.map((it) => <Slot key={it.Id} id={it.SeriesId ?? it.Id} kind={row.kind}><NextUpCard item={it} width={340} origin={row.kind} /></Slot>)}
    </Row>
  );
}

function Top10Row({ row }: { row: HomeRow }) {
  const title = row.title ?? t('Top 10 this week');
  const { data, loading } = useAsync(() => getTop10(), []);
  if (loading && !data) return <RowSkeleton title={title} width={380} count={4} />;
  if (!data || data.quiet || !data.items?.length) return null;
  return (
    <Row title={title} sub={t('Most watched on this server, all profiles')} gap={16} label={title}>
      {data.items.slice(0, 10).map((e, i) => (
        <Top10Card
          key={e.item.Id}
          rank={i + 1}
          item={e.item}
          meta={join(e.item.Type === 'Series' ? t('Series') : t('Movie'), plural(e.profiles, 'profile'))}
          onOpen={() => noteOrigin(e.item.Id, row.kind)}
        />
      ))}
    </Row>
  );
}

function BecauseRow({ row, onHideRow, onHideSeed }: { row: HomeRow; onHideRow: (r: HomeRow) => void; onHideSeed: (r: HomeRow) => void }) {
  const seed = row.seedName ?? 'this';
  const title = <><span class="lead">{t('Because you watched')}</span> {seed}</>;
  const { items, pending } = useRowData(() => (row.seedId ? getSimilar(row.seedId, 16).then((r) => r.Items) : Promise.resolve([])), [row.seedId]);
  if (pending) return <RowSkeleton title={title} />;
  if (!items.length) return null;
  const q = new URLSearchParams({ seed: row.seedId ?? '', name: seed }).toString();
  return (
    <Row
      title={title}
      label={t('Because you watched {title}', { title: seed })}
      tight
      seeAll={`/list/similar?${q}`}
      tools={
        <MenuButton
          width={300}
          button={({ ref, open, toggle }) => (
            <button ref={ref} type="button" class="btn icon-btn is-bare" aria-label={t('Row options for {title}', { title: seed })} aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={toggle}>
              <Icon name="more" size={20} />
            </button>
          )}
        >
          {(close) => (
            <>
              <MenuItem icon="eyeOff" label={t('Hide this row')} onSelect={() => { close(); onHideRow(row); }} />
              <MenuItem icon="close" label={`Don't use ${seed}`} sub={t('For Because you watched suggestions')} onSelect={() => { close(); onHideSeed(row); }} />
            </>
          )}
        </MenuButton>
      }
    >
      {items.map((it) => (
        <Slot key={it.Id} id={it.Id} kind={row.kind}>
          <ItemCard item={it} width={300} meta={join(yearOf(it), it.Genres?.[0])} origin={row.kind} />
        </Slot>
      ))}
    </Row>
  );
}

function GenreRow({ row }: { row: HomeRow }) {
  const genre = row.genre ?? '';
  const title = row.title ?? genre;
  const { items, pending } = useRowData(
    () => (genre ? getItems({ genres: [genre], includeItemTypes: ['Movie', 'Series'], sortBy: ['Random'], limit: 16, enableTotalRecordCount: false }).then((r) => r.Items) : Promise.resolve([])),
    [genre]
  );
  if (pending) return <RowSkeleton title={title} />;
  if (!items.length) return null;
  return (
    <Row title={title} seeAll={`/list/genre?genre=${encodeURIComponent(genre)}`} label={String(title)}>
      {items.map((it) => (
        <Slot key={it.Id} id={it.Id} kind={row.kind}>
          <ItemCard item={it} width={300} meta={join(yearOf(it), typeLabel(it))} origin={row.kind} />
        </Slot>
      ))}
    </Row>
  );
}

function MyMediaRow({ row }: { row: HomeRow }) {
  const views = useStore(viewsStore).filter((v) => BROWSABLE.has(v.CollectionType));
  const { data } = useAsync(() => Promise.all(views.map(tileView)), [views.map((v) => v.Id).join()]);
  const title = row.title ?? t('My media');
  if (!views.length) return null;
  return (
    <Row title={title} label={title}>
      {(data ?? views.map((v) => ({ ...v, ImageTags: {} }))).map((v) => <LibraryTile key={v.Id + (v.ParentBackdropItemId ?? '')} view={v} width={300} />)}
    </Row>
  );
}

function LatestRow({ row }: { row: HomeRow }) {
  const views = useStore(viewsStore);
  const view = views.find((v) => v.Id === row.libraryId);
  const name = row.libraryName ?? view?.Name ?? '';
  const ct = row.collectionType ?? view?.CollectionType;
  const title = row.title ?? t('Latest {library}', { library: name.toLowerCase(), name });
  const { items, pending } = useRowData(() => (row.libraryId ? getLatest(row.libraryId, 16) : Promise.resolve([])), [row.libraryId]);
  if (pending) return <RowSkeleton title={title} />;
  if (!items.length) return null;
  return (
    <Row title={title} seeAll={row.libraryId ? `/library/${row.libraryId}?sort=DateCreated` : undefined} label={title}>
      {items.map((it) => {
        if (it.Type === 'Series' || ct === 'tvshows') {
          const unwatched = it.Type === 'Series' ? it.UserData?.UnplayedItemCount ?? 0 : 0;
          return (
            <Slot key={it.Id} id={it.SeriesId ?? it.Id} kind={row.kind}>
              <ItemCard item={it} width={300} meta={it.Type === 'Series' ? seasonsLabel(it) || yearRange(it) : join(it.SeriesName && it.Name, yearOf(it))} unwatched={unwatched} origin={row.kind} />
            </Slot>
          );
        }
        const fraction = progressFraction(it);
        return (
          <Slot key={it.Id} id={it.Id} kind={row.kind}>
            <ItemCard
              item={it}
              width={300}
              meta={it.Type === 'MusicAlbum' ? join(it.AlbumArtist, yearOf(it)) : join(yearOf(it), runtime(it))}
              watched={!!it.UserData?.Played}
              progress={!it.UserData?.Played && fraction > 0 ? fraction : undefined}
              barSize={6}
              origin={row.kind}
            />
          </Slot>
        );
      })}
    </Row>
  );
}


function DiscoverRow({ row }: { row: HomeRow }) {
  const title = row.title ?? t('Discover');
  const { items, pending } = useRowData(() => seerrDiscover().then((r) => r.results ?? []));
  const req = useRequests();
  if (pending) return <RowSkeleton title={title} width={180} />;
  if (!items.length) return null;
  return (
    <>
      <Row title={title} sub={t('Not in your library yet · request with one click')} seeAll="/list/discover" label={title}>
        {items.map((s) => (
          <PosterCard key={s.mediaType + s.tmdbId} item={req.apply(s)} width={180} canRequest={req.canRequest} onRequest={req.request} requesting={req.busy(s)} />
        ))}
      </Row>
      {req.dialog}
    </>
  );
}

function UpcomingMoviesRow({ row }: { row: HomeRow }) {
  const title = row.title ?? t('Upcoming movies');
  const { items, pending } = useRowData(() => seerrUpcoming().then((r) => r.results ?? []));
  if (pending) return <RowSkeleton title={title} width={180} />;
  if (!items.length) return null;
  return (
    <Row title={title} seeAll="/list/upcoming-movies" label={title}>
      {items.map((u) => {
        const w = whenLabel(dayOf(u.releaseDate)!);
        return (
          <UpcomingCard
            key={u.tmdbId}
            title={u.title}
            posterSrc={tmdbImage(u.posterPath, 'w342')}
            when={w.text}
            isToday={w.isToday}
            to={u.jellyfinItemId ? `/details/${u.jellyfinItemId}` : seerrPath(u)}
          />
        );
      })}
    </Row>
  );
}

function UpcomingShowsRow({ row }: { row: HomeRow }) {
  const title = row.title ?? t('Upcoming shows');
  const { items, pending } = useRowData(() => getUpcomingEpisodes(60).then((r) => groupUpcoming(r.Items)));
  if (pending) return <RowSkeleton title={title} width={180} />;
  if (!items.length) return null;
  return (
    <Row title={title} seeAll="/list/upcoming-shows" label={title}>
      {items.map((g) => (
        <UpcomingCard key={g.key} title={g.title} posterSrc={g.poster} line={g.line} when={g.when} isToday={g.isToday} to={g.seriesId ? `/details/${g.seriesId}` : '/'} />
      ))}
    </Row>
  );
}

function CollectionRow({ row }: { row: HomeRow }) {
  const title = row.title ?? 'Collection';
  const { items, pending } = useRowData(
    () => (row.collectionId ? getItems({ parentId: row.collectionId, limit: 20, sortBy: ['PremiereDate', 'SortName'] }).then((r) => r.Items) : Promise.resolve([])),
    [row.collectionId]
  );
  if (pending) return <RowSkeleton title={title} />;
  if (!items.length) return null;
  return (
    <Row title={title} seeAll={row.collectionId ? `/details/${row.collectionId}` : undefined} label={title}>
      {items.map((it) => (
        <Slot key={it.Id} id={it.Id} kind={row.kind}>
          <ItemCard item={it} width={300} meta={join(yearOf(it), typeLabel(it))} origin={row.kind} />
        </Slot>
      ))}
    </Row>
  );
}
