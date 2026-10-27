import { Fragment, type ComponentChildren, type RefObject } from 'preact';
import { useCallback, useEffect, useRef, useState } from 'preact/hooks';
import { get, getSession, type Query } from '../../api/client';
import {
  CARD_FIELDS, getEpisodes, getItems, getNextUp, getResume, setFavorite, setPlayed, type ItemsQuery
} from '../../api/jellyfin';
import { seriesPosterUrl } from '../../api/images';
import { seerrRequest, type SeerrItem, type SeerrStatus } from '../../api/seedy';
import { meStore } from '../../lib/app-state';
import { useStore } from '../../lib/store';
import type { BaseItem, ItemsResult, UserItemData } from '../../api/types';
import {
  daysFromToday, episodeCode, episodeTag, fmtDate, fmtDayMonth, plural, progressFraction, remainingSeconds,
  runtime, timeLeft, whenLabel, yearRange, dayOf } from '../../lib/format';
import { Link, navigate } from '../../lib/router';
import { ItemCard, LibraryCard, SkeletonCards, WatchedCheck, Progress, Art, detailsPath, playPath } from '../../ui/cards';
import { cardArt, primaryUrl } from '../../api/images';
import { Icon, type IconName } from '../../ui/icons';
import { toast } from '../../ui/overlay';
import { withEpisodeArt } from '../me/prefs';
import './browse.css';
import { t } from '../../lib/i18n';
import { RequestSeasonsDialog } from './request-seasons';

/* ───────── Item helpers ───────── */

export const uid = () => getSession()?.userId ?? '';

export function patchUserData(item: BaseItem, patch: Partial<UserItemData>): BaseItem {
  return { ...item, UserData: { ...item.UserData, ...patch } };
}

export const yearOf = (item: BaseItem): string =>
  item.ProductionYear ? String(item.ProductionYear) : item.PremiereDate ? String(dayOf(item.PremiereDate)!.getFullYear()) : '';

/* "3 seasons" — ChildCount on a series is its season count. */
export function seasonsLabel(item: BaseItem): string {
  const n = item.ChildCount ?? 0;
  return n > 0 ? plural(n, 'season') : '';
}

export const typeLabel = (item: BaseItem) =>
  item.Type === 'Series' ? 'Series' : item.Type === 'Episode' ? 'Episode' : item.Type === 'BoxSet' ? 'Collection' : item.Type === 'MusicAlbum' ? t('Album') : t('Movie');

export const join = (...parts: (string | undefined | null | false)[]) => parts.filter(Boolean).join(' · ');

/* "S3 E2 · The Pass · 34 min left" / "Movie · 1 h 12 min left". */
export function resumeMeta(item: BaseItem): string {
  const left = timeLeft(remainingSeconds(item));
  if (item.Type === 'Episode') return join(episodeTag(item), item.Name, left);
  return join('Movie', left);
}

/* Library grid meta: year for films, year range or seasons for shows. */
export function libraryMeta(item: BaseItem): string {
  switch (item.Type) {
    case 'Series': return yearRange(item);
    case 'Episode': return join(item.SeriesName, episodeTag(item));
    case 'BoxSet': return item.ChildCount ? plural(item.ChildCount, 'title') : '';
    case 'MusicAlbum': return join(item.AlbumArtist, yearOf(item));
    case 'MusicArtist': return item.ChildCount ? plural(item.ChildCount, 'album') : '';
    default: return yearOf(item);
  }
}

export function runtimeLabel(item: BaseItem): string | undefined {
  if (item.Type === 'Series') return seasonsLabel(item) || undefined;
  return runtime(item) || undefined;
}

/* "New": an unwatched episode added to the server in the last 7 days. */
export function isNewEpisode(ep: BaseItem): boolean {
  if (ep.UserData?.Played || !ep.DateCreated) return false;
  return -daysFromToday(new Date(ep.DateCreated)) <= 7;
}

/* ───────── Watched / favourite toggles (optimistic) ───────── */

export async function toggleFavorite(item: BaseItem, apply: (next: BaseItem) => void) {
  const on = !item.UserData?.IsFavorite;
  apply(patchUserData(item, { IsFavorite: on }));
  try {
    await setFavorite(item.Id, on);
    toast(on ? `Added ${item.Name} to favorites` : `Removed ${item.Name} from favorites`);
  } catch {
    apply(item);
    toast(`Couldn't update ${item.Name}`, 'error');
  }
}

export async function togglePlayed(item: BaseItem, apply: (next: BaseItem) => void) {
  const on = !item.UserData?.Played;
  // Unwatching a whole show makes every episode unwatched again.
  const unplayedAfter = on ? 0 : item.Type === 'Series' || item.Type === 'Season' ? item.RecursiveItemCount ?? item.UserData?.UnplayedItemCount : item.UserData?.UnplayedItemCount;
  apply(patchUserData(item, {
    Played: on, PlaybackPositionTicks: 0, PlayedPercentage: 0,
    UnplayedItemCount: unplayedAfter
  }));
  try {
    await setPlayed(item.Id, on);
    toast(on ? `Marked ${item.Name} as watched` : `Marked ${item.Name} as unwatched`);
  } catch {
    apply(item);
    toast(`Couldn't update ${item.Name}`, 'error');
  }
}

/* A list whose items can be replaced in place after a toggle. */
export function useItemList(initial: BaseItem[] = []) {
  const [items, setItems] = useState<BaseItem[]>(initial);
  const replace = useCallback((next: BaseItem) => setItems((list) => list.map((x) => (x.Id === next.Id ? next : x))), []);
  return { items, setItems, replace };
}

/* ───────── What "Play" means for a title ───────── */

export async function resolvePlayable(item: BaseItem): Promise<BaseItem | undefined> {
  if (item.Type === 'Series' || item.Type === 'Season') {
    const seriesId = item.Type === 'Series' ? item.Id : item.SeriesId ?? item.Id;
    const resume = await getResume(1, item.Id).catch(() => null);
    if (resume?.Items[0]) return resume.Items[0];
    if (item.Type === 'Series') {
      const next = await getNextUp(1, seriesId).catch(() => null);
      if (next?.Items[0]) return next.Items[0];
    }
    const eps = await getEpisodes(seriesId, item.Type === 'Season' ? item.Id : undefined, { limit: 1, isMissing: false }).catch(() => null);
    return eps?.Items[0];
  }
  if (item.Type === 'BoxSet' || item.Type === 'Playlist' || item.Type === 'MusicAlbum' || item.Type === 'Folder') {
    // Albums play from disc 1 track 1; playlists in their own order; collections oldest first.
    const sortBy = item.Type === 'MusicAlbum' ? ['ParentIndexNumber', 'IndexNumber', 'SortName']
      : item.Type === 'Playlist' ? undefined
      : item.Type === 'BoxSet' ? ['PremiereDate', 'SortName'] : ['SortName'];
    const r = await getItems({ parentId: item.Id, limit: 1, sortBy, excludeItemTypes: ['Folder'], recursive: item.Type !== 'Playlist' }).catch(() => null);
    return r?.Items[0];
  }
  if (item.Type === 'MusicArtist') {
    const r = await getItems({ artistIds: [item.Id], includeItemTypes: ['Audio'], limit: 1, sortBy: ['Random'] }).catch(() => null);
    return r?.Items[0];
  }
  return item;
}

export async function playRandom(q: ItemsQuery): Promise<BaseItem | undefined> {
  const r = await getItems({ ...q, sortBy: ['Random'], limit: 1, enableTotalRecordCount: false });
  return r.Items[0];
}

/* The user view an item lives in (CollectionFolder ids are the view ids). */
export async function findView(itemId: string, views: BaseItem[]): Promise<BaseItem | undefined> {
  try {
    const anc = await get<BaseItem[]>(`/Items/${itemId}/Ancestors`, { userId: uid() });
    for (const a of anc) {
      const v = views.find((x) => x.Id === a.Id);
      if (v) return v;
    }
  } catch { /* fall back on the collection type */ }
  return undefined;
}

export const getPersonsList = (q: Query) => get<ItemsResult>('/Persons', { userId: uid(), enableImageTypes: 'Primary', imageTypeLimit: 1, ...q });

/* Library tiles use a backdrop from inside the library (Jellyfin's generated view art already carries the name).
   Music and books rarely have backdrops, so their tiles fan out a few covers instead. */
export type TileView = BaseItem & { Covers?: string[] };
const tileArt = new Map<string, Promise<TileView>>();
export function tileView(view: BaseItem): Promise<TileView> {
  let p = tileArt.get(view.Id);
  if (!p) {
    const ct = view.CollectionType;
    const types = ct === 'tvshows' ? ['Series'] : ct === 'movies' ? ['Movie'] : ct === 'music' ? ['MusicAlbum', 'MusicArtist'] : ct === 'books' ? ['Book', 'AudioBook'] : undefined;
    const covers = ct === 'music' || ct === 'books';
    p = getItems({ parentId: view.Id, includeItemTypes: types, recursive: true, sortBy: ['Random'], limit: 12, enableTotalRecordCount: false, enableImageTypes: covers ? ['Backdrop', 'Primary'] : ['Backdrop'], fields: ['PrimaryImageAspectRatio'] })
      .then((r): TileView => {
        const pick = r.Items.find((i) => i.BackdropImageTags?.length);
        if (pick) return { ...view, ImageTags: {}, BackdropImageTags: [], ParentBackdropItemId: pick.Id, ParentBackdropImageTags: pick.BackdropImageTags };
        const art = covers ? r.Items.filter((i) => i.Type !== 'MusicArtist').map((i) => primaryUrl(i, 240)).filter((u): u is string => !!u).slice(0, 3) : [];
        return art.length ? { ...view, ImageTags: {}, Covers: art } : view;
      })
      .catch(() => view);
    tileArt.set(view.Id, p);
  }
  return p;
}

/* ───────── Upcoming episodes, grouped per show as on the Upcoming shows row ───────── */

export interface UpcomingGroup { key: string; seriesId?: string; title: string; poster?: string; line: string; when: string; isToday: boolean; date: number }

export function groupUpcoming(episodes: BaseItem[]): UpcomingGroup[] {
  const bySeries = new Map<string, BaseItem[]>();
  for (const ep of episodes) {
    if (!ep.PremiereDate) continue;
    const k = ep.SeriesId ?? ep.Id;
    const list = bySeries.get(k) ?? [];
    list.push(ep);
    bySeries.set(k, list);
  }
  const groups: UpcomingGroup[] = [];
  for (const [key, list] of bySeries) {
    list.sort((a, b) => Date.parse(a.PremiereDate!) - Date.parse(b.PremiereDate!) || (a.ParentIndexNumber ?? 0) - (b.ParentIndexNumber ?? 0) || (a.IndexNumber ?? 0) - (b.IndexNumber ?? 0));
    const first = list[0];
    const date = dayOf(first.PremiereDate)!;
    /* Episodes dropping together (same day, same season, consecutive numbers) share one card. */
    let run = 1;
    while (
      run < list.length &&
      daysFromToday(dayOf(list[run].PremiereDate)!) === daysFromToday(date) &&
      list[run].ParentIndexNumber === first.ParentIndexNumber &&
      list[run].IndexNumber === (list[run - 1].IndexNumber ?? -2) + 1
    ) run++;
    let line: string;
    let when: string;
    let isToday = daysFromToday(date) === 0;
    if (run > 1) {
      const last = list[run - 1];
      line = `${episodeCode(first)}–E${String(last.IndexNumberEnd ?? last.IndexNumber ?? 0).padStart(2, '0')} · ${run} episodes`;
      when = t('From {date}', { date: fmtDayMonth(date) });
    } else {
      line = join(episodeCode(first), first.Name);
      const w = whenLabel(date);
      when = w.text;
      isToday = w.isToday;
    }
    groups.push({ key, seriesId: first.SeriesId, title: first.SeriesName ?? first.Name, poster: seriesPosterUrl(first, 360), line, when, isToday, date: date.getTime() });
  }
  return groups.sort((a, b) => a.date - b.date);
}

/* ───────── Visibility ───────── */

export function useInView<T extends Element>(rootMargin = '600px', once = true): [RefObject<T>, boolean] {
  const ref = useRef<T>(null);
  const [seen, setSeen] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el || (once && seen)) return;
    if (typeof IntersectionObserver === 'undefined') { setSeen(true); return; }
    const io = new IntersectionObserver((entries) => {
      const hit = entries.some((e) => e.isIntersecting);
      if (once) { if (hit) { setSeen(true); io.disconnect(); } } else setSeen(hit);
    }, { rootMargin });
    io.observe(el);
    return () => io.disconnect();
  }, [rootMargin, once, seen]);
  return [ref, seen];
}

/* Renders a placeholder until it scrolls near the viewport, then mounts the real content. */
export function Lazy({ eager, placeholder, children }: { eager?: boolean; placeholder: ComponentChildren; children: () => ComponentChildren }) {
  const [ref, seen] = useInView<HTMLDivElement>('700px');
  if (eager || seen) return <>{children()}</>;
  return <div ref={ref}>{placeholder}</div>;
}

/* Calls onVisible whenever the sentinel is on screen; re-armed by `token` (e.g. the item count). */
export function Sentinel({ onVisible, token, disabled }: { onVisible: () => void; token: unknown; disabled?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  const cb = useRef(onVisible);
  cb.current = onVisible;
  useEffect(() => {
    const el = ref.current;
    if (!el || disabled || typeof IntersectionObserver === 'undefined') return;
    const io = new IntersectionObserver((entries) => { if (entries.some((e) => e.isIntersecting)) cb.current(); }, { rootMargin: '900px' });
    io.observe(el);
    return () => io.disconnect();
  }, [token, disabled]);
  return <div ref={ref} aria-hidden="true" class="sentinel" />;
}

/* ───────── Paged item lists (library grids, genre pages) ───────── */

export interface Paged {
  items: BaseItem[];
  total: number;
  loading: boolean;
  error?: Error;
  done: boolean;
  loadMore: () => void;
  ensure: (index: number) => Promise<void>;
  replace: (next: BaseItem) => void;
  reload: () => void;
}

export function usePaged(fetchPage: (start: number, limit: number, signal: AbortSignal) => Promise<ItemsResult>, deps: unknown[], pageSize = 60): Paged {
  const [state, setState] = useState<{ items: BaseItem[]; total: number; loading: boolean; error?: Error; done: boolean }>({ items: [], total: 0, loading: true, done: false });
  const gen = useRef(0);
  const busy = useRef(false);
  const ctrl = useRef<AbortController | null>(null);
  const stateRef = useRef(state);
  stateRef.current = state;
  const fetchRef = useRef(fetchPage);
  fetchRef.current = fetchPage;
  const [tick, setTick] = useState(0);

  const load = useCallback(async (limit: number) => {
    const g = gen.current;
    if (busy.current) return;
    const s = stateRef.current;
    if (s.done && s.items.length) return;
    busy.current = true;
    setState((p) => ({ ...p, loading: true, error: undefined }));
    try {
      const c = new AbortController();
      ctrl.current = c;
      const r = await fetchRef.current(s.items.length, limit, c.signal);
      if (g !== gen.current) return;
      // Append to the *current* list, so a favourite or watched toggle made while this page loaded survives.
      const cur = stateRef.current;
      const seen = new Set(cur.items.map((x) => x.Id));
      const fresh = r.Items.filter((x) => !seen.has(x.Id));
      const items = [...cur.items, ...fresh];
      const total = r.TotalRecordCount ?? items.length;
      const next = { items, total, loading: false, done: items.length >= total || r.Items.length < limit || fresh.length === 0 };
      stateRef.current = next;
      setState(next);
    } catch (e) {
      if (g !== gen.current || (e as Error)?.name === 'AbortError') return;
      setState((p) => ({ ...p, loading: false, error: e as Error }));
    } finally {
      if (g === gen.current) busy.current = false;
    }
  }, []);

  useEffect(() => {
    gen.current++;
    busy.current = false;
    ctrl.current?.abort();
    const fresh = { items: [], total: 0, loading: true, done: false };
    stateRef.current = fresh;
    setState(fresh);
    load(pageSize);
    return () => ctrl.current?.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);

  return {
    ...state,
    // After a failed page, "load more" (or the retry button) tries that page again.
    loadMore: () => { if (!stateRef.current.done) load(pageSize); },
    ensure: async (index: number) => {
      const s = stateRef.current;
      if (index < s.items.length || s.done) return;
      while (busy.current) await new Promise((r) => setTimeout(r, 50));
      await load(index - stateRef.current.items.length + pageSize);
    },
    replace: (next: BaseItem) => setState((p) => {
      const n = { ...p, items: p.items.map((x) => (x.Id === next.Id ? next : x)) };
      stateRef.current = n;
      return n;
    }),
    reload: () => setTick((t) => t + 1)
  };
}

/* ───────── Shared pieces of page chrome ───────── */

export function PageHead({ title, count, actions, lede }: { title: ComponentChildren; count?: ComponentChildren; actions?: ComponentChildren; lede?: ComponentChildren }) {
  return (
    <div class="page-title-row">
      <div class="browse-head-text">
        <div class="page-title">
          <h1>{title}</h1>
          {count !== undefined && count !== null && count !== '' && <span class="page-count">{count}</span>}
        </div>
        {lede && <p class="page-lede">{lede}</p>}
      </div>
      {actions && <div class="browse-head-actions">{actions}</div>}
    </div>
  );
}

export function EmptyState({ icon = 'film', title, text, action }: { icon?: IconName; title: string; text?: ComponentChildren; action?: ComponentChildren }) {
  return (
    <div class="empty">
      <div class="empty-icon"><Icon name={icon} size={26} /></div>
      <div class="empty-body">
        <h2 class="empty-title">{title}</h2>
        {text && <p class="empty-text">{text}</p>}
      </div>
      {action}
    </div>
  );
}

export function ErrorState({ error, retry, title }: { error?: Error; retry?: () => void; title?: string }) {
  return (
    <div class="error-box" role="alert">
      <h2>{title ?? t("Couldn't load this page")}</h2>
      <p>{error?.message ? t('The server said: {message}', { message: error.message }) : t('The server didn’t answer. Check your connection and try again.')}</p>
      {retry && <button type="button" class="btn btn-secondary" onClick={retry}><Icon name="refresh" size={18} />{t('Try again')}</button>}
    </div>
  );
}

/* ───────── Grids ───────── */

export function GridSkeleton({ count = 12, poster }: { count?: number; poster?: boolean }) {
  return (
    <div class={poster ? 'poster-grid' : 'lib-grid'} aria-busy="true" aria-label={t('Loading')}>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} class="card" aria-hidden="true">
          <div class={'card-media skel' + (poster ? ' is-poster' : '')} />
          <div class="card-text" style={{ gap: '8px' }}>
            <span class="skel skel-line" style={{ width: '70%' }} />
            <span class="skel skel-line" style={{ width: '40%' }} />
          </div>
        </div>
      ))}
    </div>
  );
}

export function RowSkeleton({ title, width = 300, count = 6 }: { title: ComponentChildren; width?: number; count?: number }) {
  return (
    <section class="row" aria-busy="true">
      <div class="row-head"><div class="row-head-main"><h2 class="row-title">{title}</h2></div></div>
      <div class="row-shell"><div class="row-scroller hscroll"><SkeletonCards count={count} width={width} /></div></div>
    </section>
  );
}

/* Library cards in the board's grid (minmax(240px, 1fr), gap 28 × 20). */
/* What a grid needs to let people pick titles: whether picking is on, what's picked, and a toggle. */
export interface GridSelect { active: boolean; has: (id: string) => boolean; toggle: (item: BaseItem, index: number, shift: boolean) => void }

export function CardGrid({ items, onReplace, origin, gridRef, select }: {
  items: BaseItem[]; onReplace: (next: BaseItem) => void; origin?: string; gridRef?: RefObject<HTMLDivElement>; select?: GridSelect;
}) {
  return (
    <div class="lib-grid" ref={gridRef}>
      {items.map((it, i) => (
        <div key={it.Id} class="grid-slot" data-sort={(it.SortName ?? it.Name ?? '').charAt(0)}>
          <LibraryCard
            item={it}
            meta={libraryMeta(it)}
            runtimeLabel={runtimeLabel(it)}
            origin={origin}
            onToggleWatched={() => togglePlayed(it, onReplace)}
            onToggleFavorite={() => toggleFavorite(it, onReplace)}
            select={select && { active: select.active, selected: select.has(it.Id), toggle: (shift) => select.toggle(it, i, shift) }}
          />
        </div>
      ))}
    </div>
  );
}

/* 16:9 item cards in a grid (episodes, continue watching). */
export function ItemGrid({ items, render }: { items: BaseItem[]; render: (item: BaseItem) => ComponentChildren }) {
  return <div class="lib-grid is-wide">{items.map((it) => <div key={it.Id} class="grid-slot">{render(it)}</div>)}</div>;
}

/* ───────── List layout: rows as on the History board ───────── */

export function ListRows({ items, onReplace, listRef }: { items: BaseItem[]; onReplace: (next: BaseItem) => void; listRef?: RefObject<HTMLDivElement> }) {
  return (
    <div class="list-rows" ref={listRef}>
      {items.map((it) => <ListRow key={it.Id} item={it} onReplace={onReplace} />)}
    </div>
  );
}

export function ListRow({ item, onReplace }: { item: BaseItem; onReplace: (next: BaseItem) => void }) {
  const played = !!item.UserData?.Played;
  const fav = !!item.UserData?.IsFavorite;
  const progress = progressFraction(item);
  const art = cardArt(item, 360);
  const canPlay = item.Type !== 'BoxSet' && item.Type !== 'MusicArtist';
  const unplayed = item.Type === 'Series' ? item.UserData?.UnplayedItemCount ?? 0 : 0;
  const state = played ? 'Watched'
    : item.Type === 'Series' ? (unplayed ? t('{count} to watch', { count: plural(unplayed, 'episode') }) : t('Up to date'))
    : item.Type === 'BoxSet' ? (item.ChildCount ? plural(item.ChildCount, 'title') : 'Collection')
    : progress > 0 ? timeLeft(remainingSeconds(item))
    : t('Not started');
  const meta = join(libraryMeta(item), item.Type === 'Series' ? seasonsLabel(item) : runtime(item), (item.Genres ?? []).slice(0, 2).join(', '));
  return (
    <div class="list-row" data-sort={(item.SortName ?? item.Name ?? '').charAt(0)}>
      <Link to={detailsPath(item)} class="list-thumb" aria-label={item.Name}>
        <Art art={art} title={item.Name} titleClass="is-list" />
        {!played && progress > 0 && <Progress fraction={progress} size={5} class="list-bar" />}
        {played && <WatchedCheck size={24} />}
      </Link>
      <div class="list-main">
        <Link to={detailsPath(item)} class="list-title">{item.Name}</Link>
        <span class="list-sub">{meta}</span>
      </div>
      <div class="list-state">
        <span class="list-state-1">{state}</span>
        {item.DateCreated && <span class="list-state-2">Added {fmtDate(new Date(item.DateCreated))}</span>}
      </div>
      <div class="list-tools">
        <button type="button" class="btn icon-btn is-bare" aria-label={played ? t('Mark {name} as unwatched', { name: item.Name }) : t('Mark {name} as watched', { name: item.Name })} aria-pressed={played ? 'true' : 'false'} onClick={() => togglePlayed(item, onReplace)}>
          <Icon name="check" size={20} stroke={2.4} color={played ? 'var(--seedy-accent)' : undefined} />
        </button>
        <button type="button" class="btn icon-btn is-bare" aria-label={fav ? t('Remove {name} from favorites', { name: item.Name }) : t('Add {name} to favorites', { name: item.Name })} aria-pressed={fav ? 'true' : 'false'} onClick={() => toggleFavorite(item, onReplace)}>
          <Icon name="heart" size={20} filled={fav} color={fav ? 'var(--seedy-accent)' : undefined} />
        </button>
        {canPlay && (
          <button
            type="button"
            class="btn icon-btn is-bare"
            aria-label={t('Play {name}', { name: item.Name })}
            onClick={async () => {
              const target = await resolvePlayable(item);
              if (target) navigate(playPath(target));
              else toast(`Nothing to play in ${item.Name} yet`, 'error');
            }}
          >
            <Icon name="playRound" size={20} />
          </button>
        )}
      </div>
    </div>
  );
}

/* ───────── Small shared bits ───────── */

/* Continue-watching card, as on Home (tag, 8 px bar). Its play disc resumes; the card opens the details page.
   Episodes use the show's art when the person turned off "Use episode pictures" (Settings · Home screen). */
export function ResumeCard({ item, width, origin, onOpen }: { item: BaseItem; width?: number; origin?: string; onOpen?: () => void }) {
  return (
    <ItemCard
      item={withEpisodeArt(item)}
      width={width}
      tag={item.Type === 'Episode' ? episodeTag(item) : 'Movie'}
      progress={progressFraction(item)}
      meta={resumeMeta(item)}
      origin={origin}
      onOpen={onOpen}
    />
  );
}

export function NextUpCard({ item, width, origin, onOpen }: { item: BaseItem; width?: number; origin?: string; onOpen?: () => void }) {
  return (
    <ItemCard
      item={withEpisodeArt(item)}
      width={width}
      tag={episodeTag(item)}
      isNew={isNewEpisode(item)}
      meta={join(episodeTag(item), item.Name)}
      origin={origin}
      onOpen={onOpen}
    />
  );
}


/* Seerr requests: optimistic "Requested" with a toast. A series always opens the seasons dialog first;
   render `dialog` once in the page. */
export function useRequests() {
  const me = useStore(meStore);
  const [status, setStatus] = useState<Record<string, SeerrStatus>>({});
  const [busy, setBusy] = useState<Record<string, boolean>>({});
  const [series, setSeries] = useState<SeerrItem | null>(null);
  const key = (s: SeerrItem) => s.mediaType + ':' + s.tmdbId;
  const request = async (s: SeerrItem) => {
    if (s.mediaType === 'tv') { setSeries(s); return; }
    const k = key(s);
    setBusy((b) => ({ ...b, [k]: true }));
    setStatus((m) => ({ ...m, [k]: 'pending' }));
    try {
      const r = await seerrRequest({ mediaType: s.mediaType, tmdbId: s.tmdbId });
      setStatus((m) => ({ ...m, [k]: r?.status ?? 'pending' }));
      toast(`Requested ${s.title}`, 'pending');
    } catch (e) {
      setStatus((m) => { const n = { ...m }; delete n[k]; return n; });
      toast(`Couldn't request ${s.title}${(e as Error)?.message ? ': ' + (e as Error).message : ''}`, 'error');
    } finally {
      setBusy((b) => ({ ...b, [k]: false }));
    }
  };
  const apply = (s: SeerrItem): SeerrItem => (status[key(s)] ? { ...s, status: status[key(s)], requestedByMe: true } : s);
  const dialog = series && (
    <RequestSeasonsDialog
      open
      context="seerr"
      target={{ tmdbId: series.tmdbId, title: series.title, year: series.year, posterPath: series.posterPath, backdropPath: series.backdropPath }}
      onClose={() => setSeries(null)}
      onDone={(r) => setStatus((m) => ({ ...m, [key(series)]: r.status }))}
    />
  );
  return { canRequest: me.requests.canRequest && me.requests.requestButtons !== false, request, apply, busy: (s: SeerrItem) => !!busy[key(s)], dialog };
}


/* ───────── Pages of Seerr titles (Discover, the Requests rows' See all, Requests search) ───────── */

type Titled = SeerrItem & { season?: number };
export const seerrKey = (s: Titled) => `${s.mediaType}:${s.tmdbId}:${s.season ?? ''}`;

export interface SeerrPages<T> { items: T[]; busy: boolean; done: boolean; error?: Error; total?: number; loadMore: () => void }

/* Loads pages of titles as a grid scrolls, without repeats; stops at Seerr's last page, or at a page with nothing new. */
export function useSeerrPages<T extends Titled>(
  load: (page: number) => Promise<{ results: T[]; pages?: number; total?: number }>,
  deps: unknown[],
  enabled = true
): SeerrPages<T> {
  const [items, setItems] = useState<T[]>([]);
  const [page, setPage] = useState(1);
  const [done, setDone] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error | undefined>();
  const [total, setTotal] = useState<number | undefined>();
  const seen = useRef(new Set<string>());
  const gen = useRef(0);

  const more = async (p: number, g: number) => {
    setBusy(true);
    setError(undefined);
    try {
      const r = await load(p);
      if (g !== gen.current) return;
      const fresh = (r.results ?? []).filter((s) => !seen.current.has(seerrKey(s)));
      fresh.forEach((s) => seen.current.add(seerrKey(s)));
      setItems((list) => [...list, ...fresh]);
      setTotal(r.total);
      setPage(p + 1);
      if (!fresh.length || (r.pages !== undefined && p >= r.pages)) setDone(true);
    } catch (e) {
      if (g === gen.current) setError(e as Error);
    } finally {
      if (g === gen.current) setBusy(false);
    }
  };
  useEffect(() => {
    const g = ++gen.current;
    seen.current = new Set();
    setItems([]);
    setTotal(undefined);
    setPage(1);
    setDone(false);
    if (enabled) void more(1, g);
  }, [...deps, enabled]);
  return { items, busy, done, error, total, loadMore: () => { if (!busy && !done) void more(page, gen.current); } };
}

/* A poster grid of those pages: skeleton, error, empty, then more as it scrolls. */
export function SeerrGrid<T extends Titled>({ pages, empty, render }: { pages: SeerrPages<T>; empty: ComponentChildren; render: (item: T) => ComponentChildren }) {
  if (pages.busy && !pages.items.length) return <GridSkeleton count={14} poster />;
  if (pages.error && !pages.items.length) return <ErrorState error={pages.error} retry={pages.loadMore} title={t('Couldn\'t reach Seerr')} />;
  if (!pages.items.length) return <>{empty}</>;
  return (
    <>
      <div class="poster-grid">
        {pages.items.map((s) => <Fragment key={seerrKey(s)}>{render(s)}</Fragment>)}
      </div>
      {!pages.done && !pages.error && <Sentinel onVisible={pages.loadMore} token={pages.items.length} disabled={pages.busy} />}
      {pages.busy && <div class="list-loading"><span class="spinner" aria-label={t('Loading more')} /></div>}
      {pages.error && <div class="grid-more"><button type="button" class="btn btn-secondary" onClick={pages.loadMore}><Icon name="refresh" size={18} />{t('Load more')}</button></div>}
    </>
  );
}

export { CARD_FIELDS };
