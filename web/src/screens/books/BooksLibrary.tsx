import type { ComponentChildren } from 'preact';
import { useEffect, useMemo, useState } from 'preact/hooks';
import { JELLYFIN_WEB, get, post } from '../../api/client';
import type { BaseItem, SessionInfo } from '../../api/types';
import { deviceId } from '../../api/client';
import { meStore } from '../../lib/app-state';
import { plural, ticksToSeconds, timeLeft } from '../../lib/format';
import { t, tn } from '../../lib/i18n';
import { Link, navigate, setQuery, useLocation } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { Header } from '../../ui/navbar/Header';
import { Icon } from '../../ui/icons';
import { MenuButton, MenuItem, toast } from '../../ui/overlay';
import { EmptyState, ErrorState } from '../browse/parts';
import { authorLine, authorsOf, getBookInfo, isAudioBook, isComic, listReading, loadLibraryItems, normalize, type Reading } from './api';
import {
  AudioCard, BookCard, ContinueCard, SectionHead, SeriesCard, bookSub, comicSub, roughTime, useColumns,
  type ContinueEntry, type SeriesGroup
} from './parts';
import './books.css';

/* The books library (Books library board): title and counts, Sort and ⋯, then tabs. Overview shows Continue,
   Recently added, Series, Audiobooks and Comics; the other tabs are full grids (Books, Audiobooks, Comics) or grouped
   views (Authors, Series). Everything in the library loads once and the tabs sort and group it here. */

type TabId = 'overview' | 'books' | 'audiobooks' | 'comics' | 'authors' | 'series';
const TABS: { id: TabId; label: string }[] = [
  { id: 'overview', label: 'Overview' }, { id: 'books', label: 'Books' }, { id: 'audiobooks', label: 'Audiobooks' },
  { id: 'comics', label: 'Comics' }, { id: 'authors', label: 'Authors' }, { id: 'series', label: 'Series' }
];

const SORTS = [
  { id: 'added', label: 'Recently added' },
  { id: 'title', label: 'Title' },
  { id: 'author', label: 'Author' },
  { id: 'released', label: 'Release date' },
  { id: 'read', label: 'Recently read' }
] as const;
type SortId = (typeof SORTS)[number]['id'];

const time = (s?: string) => (s ? new Date(s).getTime() || 0 : 0);
const sortKey = (it: BaseItem) => (it.SortName ?? it.Name ?? '').toLowerCase();

function sortItems(items: BaseItem[], sort: SortId, reading: Map<string, Reading>): BaseItem[] {
  const list = items.slice();
  const byTitle = (a: BaseItem, b: BaseItem) => sortKey(a).localeCompare(sortKey(b));
  switch (sort) {
    case 'title': return list.sort(byTitle);
    case 'author': return list.sort((a, b) => authorSort(a).localeCompare(authorSort(b)) || (a.SeriesName ?? '').localeCompare(b.SeriesName ?? '') || (a.IndexNumber ?? 0) - (b.IndexNumber ?? 0) || byTitle(a, b));
    case 'released': return list.sort((a, b) => (released(b) - released(a)) || byTitle(a, b));
    case 'read': return list.sort((a, b) => (lastRead(b, reading) - lastRead(a, reading)) || byTitle(a, b));
    default: return list.sort((a, b) => (time(b.DateCreated) - time(a.DateCreated)) || byTitle(a, b));
  }
}

const released = (it: BaseItem) => (it.PremiereDate ? time(it.PremiereDate) : it.ProductionYear ? new Date(it.ProductionYear, 0, 1).getTime() : 0);
const lastRead = (it: BaseItem, reading: Map<string, Reading>) => Math.max(time(reading.get(it.Id)?.UpdatedAt), time(it.UserData?.LastPlayedDate));
/* "Mara Linde" sorts under L. */
const authorSort = (it: BaseItem) => {
  const name = authorsOf(it)[0]?.Name ?? '';
  const parts = name.trim().split(/\s+/);
  return (parts.length > 1 ? parts[parts.length - 1] + ' ' + parts.slice(0, -1).join(' ') : name).toLowerCase() || '￿';
};

export interface BooksData {
  all: BaseItem[];
  books: BaseItem[];
  comics: BaseItem[];
  audio: BaseItem[];
  reading: Map<string, Reading>;
  series: SeriesGroup[];
}

function groupSeries(items: BaseItem[]): SeriesGroup[] {
  const map = new Map<string, BaseItem[]>();
  for (const it of items) {
    if (!it.SeriesName || isAudioBook(it)) continue;
    const key = normalize(it.SeriesName);
    const list = map.get(key) ?? [];
    list.push(it);
    map.set(key, list);
  }
  return Array.from(map, ([key, vols]) => {
    // One cover per volume: the EPUB and the PDF of one book count once.
    const seen = new Set<string>();
    const volumes = vols
      .sort((a, b) => (a.IndexNumber ?? 9999) - (b.IndexNumber ?? 9999) || sortKey(a).localeCompare(sortKey(b)))
      .filter((v) => { const k = `${v.IndexNumber ?? ''}|${normalize(v.Name)}`; if (seen.has(k)) return false; seen.add(k); return true; });
    const authors = Array.from(new Set(volumes.map(authorLine).filter(Boolean)));
    return {
      name: volumes[0].SeriesName!,
      key,
      author: authors.length === 1 ? authors[0] : authors.length > 1 ? t('Various authors') : '',
      volumes,
      read: volumes.filter((v) => v.UserData?.Played).length,
      latest: Math.max(...volumes.map((v) => Math.max(time(v.DateCreated), time(v.UserData?.LastPlayedDate))))
    };
  });
}

export function useBooksData(viewId: string) {
  const [fresh, setFresh] = useState(0);
  const items = useAsync((signal) => (viewId ? libraryItems(viewId, fresh > 0, signal) : Promise.resolve(undefined)), [viewId, fresh]);
  const reading = useAsync(() => (viewId ? listReading(true, 200).catch(() => [] as Reading[]) : Promise.resolve([] as Reading[])), [viewId, fresh]);
  const data = useMemo<BooksData | undefined>(() => {
    if (!items.data) return undefined;
    const all = items.data;
    const map = new Map((reading.data ?? []).map((r) => [r.ItemId, r]));
    return {
      all,
      books: all.filter((i) => !isAudioBook(i) && !isComic(i)),
      comics: all.filter((i) => !isAudioBook(i) && isComic(i)),
      audio: all.filter(isAudioBook),
      reading: map,
      series: groupSeries(all)
    };
  }, [items.data, reading.data]);
  return { data, error: items.error, loading: items.loading, reload: () => setFresh((n) => n + 1) };
}

/* The library's items stay for a minute, so going from the library to a book and back doesn't load them again. */
const itemsCache = new Map<string, { at: number; items: Promise<BaseItem[]> }>();
function libraryItems(viewId: string, force: boolean, signal: AbortSignal): Promise<BaseItem[]> {
  const hit = itemsCache.get(viewId);
  if (hit && !force && Date.now() - hit.at < 60_000) return hit.items;
  const items = loadLibraryItems(viewId);
  itemsCache.set(viewId, { at: Date.now(), items });
  items.catch(() => itemsCache.delete(viewId));
  void signal;
  return items;
}

/* ───────── Screen ───────── */

export function BooksLibrary({ view }: { view: BaseItem }) {
  const loc = useLocation();
  const me = useStore(meStore);
  const tabParam = loc.query.get('tab') as TabId | null;
  const tab: TabId = TABS.some((x) => x.id === tabParam) ? tabParam! : 'overview';
  const sortParam = loc.query.get('sort') as SortId | null;
  const sort: SortId = SORTS.some((s) => s.id === sortParam) ? sortParam! : 'added';
  const seriesParam = loc.query.get('series');
  const authorParam = loc.query.get('author');
  const lib = useBooksData(view.Id);
  const data = lib.data;
  const base = `/library/${view.Id}`;

  const tabTo = (id: TabId, extra: Record<string, string> = {}) => {
    const q = new URLSearchParams({ ...(id === 'overview' ? {} : { tab: id }), ...(sortParam && id !== 'overview' ? { sort: sortParam } : {}), ...extra });
    const s = q.toString();
    return base + (s ? '?' + s : '');
  };

  const counts = data && [
    plural(data.books.length, 'book'),
    plural(data.audio.length, 'audiobook'),
    plural(data.comics.length, 'comic')
  ].join(' · ');

  return (
    <div class="page bk-page">
      <Header active={{ viewId: view.Id }} />
      <main class="bk-main">
        <div class="bk-top">
          <div class="bk-head">
            <div class="bk-title">
              <h1>{view.Name}</h1>
              {counts && <span class="bk-count">{counts}</span>}
            </div>
            <div class="bk-head-actions">
              <SortMenu sort={sort} onChange={(s) => {
                // On the overview a sort opens the full Books grid in that order.
                if (tab === 'overview') navigate(`${base}?tab=books&sort=${s}`, { replace: true });
                else setQuery({ sort: s === 'added' ? undefined : s });
              }} />
              <MenuButton
                width={260}
                button={({ ref, open, toggle }) => (
                  <button ref={ref} type="button" class="bk-icon-btn" aria-label={t('Library options')} aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={toggle}>
                    <MoreIcon />
                  </button>
                )}
              >
                {(close) => (
                  <>
                    <MenuItem icon="refresh" label={t('Refresh')} onSelect={() => { close(); lib.reload(); }} />
                    <MenuItem icon="link" label={t('Copy link')} onSelect={() => {
                      close();
                      navigator.clipboard.writeText(window.location.href).then(() => toast(t('Link copied')), () => toast(t("Couldn't copy the link"), 'error'));
                    }} />
                    <MenuItem icon="external" label={t('Open in Jellyfin')} href={`${JELLYFIN_WEB}#/list?parentId=${view.Id}`} />
                    {me.isAdmin && (
                      <>
                        <div class="menu-sep" />
                        <MenuItem icon="refresh" label={t('Scan library files')} sub={t('Look for new and changed media')} onSelect={() => {
                          close();
                          post<void>(`/Items/${view.Id}/Refresh`, undefined, { Recursive: true, MetadataRefreshMode: 'Default', ImageRefreshMode: 'Default', ReplaceAllMetadata: false, ReplaceAllImages: false })
                            .then(() => toast(t('Scanning {name}', { name: view.Name }), 'pending'), () => toast(t("Couldn't start the scan"), 'error'));
                        }} />
                      </>
                    )}
                  </>
                )}
              </MenuButton>
            </div>
          </div>
          <nav aria-label={t('Book views')} class="bk-tabs hscroll">
            {TABS.map((tb) => (
              <Link key={tb.id} to={tabTo(tb.id)} replace class="bk-tab" aria-current={tb.id === tab ? 'page' : undefined}>{t(tb.label)}</Link>
            ))}
          </nav>
        </div>

        {lib.error && !data ? <ErrorState error={lib.error} retry={lib.reload} title={t('Couldn\'t open this library')} />
          : !data ? <BooksSkeleton />
          : !data.all.length ? <EmptyState icon="folder" title={t('{name} is empty', { name: view.Name })} text={t('Once the server finds books in this library they will show up here.')} />
          : tab === 'overview' ? <Overview view={view} data={data} tabTo={tabTo} />
          : tab === 'books' ? <Grid items={sortItems(data.books, sort, data.reading)} empty={t('No books yet')} render={(it) => <BookCard item={it} sub={bookSub(it)} />} />
          : tab === 'comics' ? <Grid items={sortItems(data.comics, sort, data.reading)} empty={t('No comics yet')} render={(it) => <BookCard item={it} sub={comicSub(it, data.reading.get(it.Id))} />} />
          : tab === 'audiobooks' ? <Grid audio items={sortItems(data.audio, sort, data.reading)} empty={t('No audiobooks yet')} render={(it) => <AudioCard item={it} />} />
          : tab === 'authors' ? <Authors data={data} sort={sort} focus={authorParam} tabTo={tabTo} />
          : <SeriesView data={data} sort={sort} open={seriesParam} tabTo={tabTo} />}
      </main>
    </div>
  );
}

function MoreIcon() {
  return (
    <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true" fill="currentColor">
      <circle cx="5.5" cy="12" r="1.7" /><circle cx="12" cy="12" r="1.7" /><circle cx="18.5" cy="12" r="1.7" />
    </svg>
  );
}

function SortMenu({ sort, onChange }: { sort: SortId; onChange: (s: SortId) => void }) {
  const cur = SORTS.find((s) => s.id === sort) ?? SORTS[0];
  return (
    <MenuButton
      width={240}
      button={({ ref, open, toggle }) => (
        <button ref={ref} type="button" class="bk-sort" aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} aria-label={t('Sort by {name}', { name: t(cur.label) })} onClick={toggle}>
          <span class="bk-sort-k">{t('Sort')}</span> {t(cur.label)}
          <Icon name="chevronDown" size={16} stroke={2.2} />
        </button>
      )}
    >
      {(close) => (
        <>
          <span class="menu-label">{t('Sort by')}</span>
          {SORTS.map((s) => <MenuItem key={s.id} label={t(s.label)} pressed={s.id === sort} onSelect={() => { close(); onChange(s.id); }} />)}
        </>
      )}
    </MenuButton>
  );
}

function BooksSkeleton() {
  return (
    <div class="bk-sections" aria-busy="true" aria-label={t('Loading')}>
      <section class="bk-sec">
        <div class="bk-sec-head"><span class="skel skel-line" style={{ width: '140px', height: '24px' }} /></div>
        <div class="bk-cont-grid">{[0, 1, 2].map((i) => <div key={i} class="bk-cont skel" style={{ height: '196px' }} />)}</div>
      </section>
      <section class="bk-sec">
        <div class="bk-sec-head"><span class="skel skel-line" style={{ width: '180px', height: '24px' }} /></div>
        <div class="bk-grid">{Array.from({ length: 8 }, (_, i) => <div key={i} class="bk-card"><span class="bk-cover skel" /><span class="skel skel-line" style={{ width: '70%' }} /></div>)}</div>
      </section>
    </div>
  );
}

/* ───────── Overview ───────── */

function Overview({ view, data, tabTo }: { view: BaseItem; data: BooksData; tabTo: (id: TabId, extra?: Record<string, string>) => string }) {
  const recent = useMemo(() => sortItems(data.books, 'added', data.reading), [data]);
  const series = useMemo(() => data.series.slice().sort((a, b) => b.latest - a.latest), [data]);
  const audio = useMemo(() => sortItems(data.audio, 'added', data.reading), [data]);
  const comics = useMemo(() => sortItems(data.comics, 'added', data.reading), [data]);
  return (
    <div class="bk-sections">
      <ContinueSection view={view} data={data} />
      {recent.length > 0 && (
        <OverviewRow id="bk-rec" title={t('Recently added')} link={tabTo('books', { sort: 'added' })} linkLabel={t('See all')} min={140} gap={22}>
          {(n) => recent.slice(0, n).map((it) => <BookCard key={it.Id} item={it} sub={bookSub(it)} />)}
        </OverviewRow>
      )}
      {series.length > 0 && (
        <section class="bk-sec" aria-labelledby="bk-ser">
          <SectionHead id="bk-ser" title={t('Series')} link={tabTo('series')} linkLabel={t('All series')} />
          <SeriesRow groups={series} tabTo={tabTo} />
        </section>
      )}
      {audio.length > 0 && (
        <OverviewRow id="bk-au" title={t('Audiobooks')} link={tabTo('audiobooks')} linkLabel={t('All audiobooks')} min={160} gap={20} audio>
          {(n) => audio.slice(0, n).map((it) => <AudioCard key={it.Id} item={it} />)}
        </OverviewRow>
      )}
      {comics.length > 0 && (
        <OverviewRow id="bk-co" title={t('Comics')} link={tabTo('comics')} linkLabel={t('All comics')} min={140} gap={22}>
          {(n) => comics.slice(0, n).map((it) => <BookCard key={it.Id} item={it} sub={comicSub(it, data.reading.get(it.Id))} />)}
        </OverviewRow>
      )}
    </div>
  );
}

/* One row of cards, as many as fit. */
function OverviewRow({ id, title, link, linkLabel, min, gap, audio, children }: {
  id: string; title: string; link: string; linkLabel: string; min: number; gap: number; audio?: boolean; children: (n: number) => ComponentChildren;
}) {
  const [ref, cols] = useColumns<HTMLDivElement>(min, gap);
  return (
    <section class="bk-sec" aria-labelledby={id}>
      <SectionHead id={id} title={title} link={link} linkLabel={linkLabel} />
      <div ref={ref} class={'bk-row' + (audio ? ' is-audio' : '')} style={{ gridTemplateColumns: `repeat(${cols || 1}, minmax(0, 1fr))` }}>
        {cols > 0 && children(cols)}
      </div>
    </section>
  );
}

function SeriesRow({ groups, tabTo }: { groups: SeriesGroup[]; tabTo: (id: TabId, extra?: Record<string, string>) => string }) {
  const [ref, cols] = useColumns<HTMLDivElement>(300, 20);
  return (
    <div ref={ref} class="bk-series-grid" style={{ gridTemplateColumns: `repeat(${cols || 1}, minmax(0, 1fr))` }}>
      {cols > 0 && groups.slice(0, cols).map((g) => <SeriesCard key={g.key} group={g} to={tabTo('series', { series: g.name })} />)}
    </div>
  );
}

/* Continue: books and comics open in the reader (from /Seedy/Reading), audiobooks from Jellyfin's resume points. */
function ContinueSection({ view, data }: { view: BaseItem; data: BooksData }) {
  const entries = useAsync(async () => {
    const reading = Array.from(data.reading.values()).filter((r) => !r.Finished);
    const byId = new Map(data.all.map((i) => [i.Id, i]));
    const books = reading.map((r) => ({ r, item: byId.get(r.ItemId) })).filter((x): x is { r: Reading; item: BaseItem } => !!x.item && !isAudioBook(x.item));
    const listening = data.audio.filter((a) => !a.UserData?.Played && (a.UserData?.PlaybackPositionTicks ?? 0) > 0);
    const playing = await nowPlayingHere();
    type Cand = { at: number; make: () => Promise<ContinueEntry> };
    const cands: Cand[] = [
      ...books.map(({ r, item }) => ({ at: time(r.UpdatedAt), make: () => readingEntry(item, r) })),
      ...listening.map((item) => ({ at: item.Id === playing ? Date.now() : time(item.UserData?.LastPlayedDate), make: async () => audioEntry(item, item.Id === playing) }))
    ];
    cands.sort((a, b) => b.at - a.at);
    return Promise.all(cands.slice(0, 3).map((c) => c.make()));
  }, [data, view.Id]);
  if (!entries.data?.length) return null;
  return (
    <section class="bk-sec" aria-labelledby="bk-cont">
      <SectionHead id="bk-cont" title={t('Continue')} />
      <div class="bk-cont-grid">
        {entries.data.map((e) => <ContinueCard key={e.item.Id} entry={e} />)}
      </div>
    </section>
  );
}

/* The audiobook this browser is playing right now, if any (Jellyfin's session for this device). */
async function nowPlayingHere(): Promise<string | undefined> {
  try {
    const list = await get<SessionInfo[]>('/Sessions', { deviceId: deviceId(), activeWithinSeconds: 600 });
    return list.find((s) => s.NowPlayingItem)?.NowPlayingItem?.Id;
  } catch {
    return undefined;
  }
}

async function readingEntry(item: BaseItem, r: Reading): Promise<ContinueEntry> {
  const info = await getBookInfo(item.Id).catch(() => undefined);
  const fraction = Math.max(0, Math.min(1, r.Percent / 100));
  if (isComic(item)) {
    const pages = info?.Pages ?? 0;
    const page = Math.min(pages, (Number(r.Locator) || 0) + 1);
    return {
      item, kind: t('Comic · {format}', { format: (/\.(\w+)$/.exec(String((item as { Path?: string }).Path ?? ''))?.[1] ?? 'CBZ').toUpperCase() }),
      where: pages ? t('Page {n} of {total}', { n: page, total: pages }) : undefined,
      fraction, done: t('{n}% read', { n: Math.round(r.Percent) }),
      left: pages ? tn(Math.max(0, pages - page), '{n} page left', '{n} pages left') : undefined
    };
  }
  const format = (/\.(\w+)$/.exec(String((item as { Path?: string }).Path ?? ''))?.[1] ?? 'EPUB').toUpperCase();
  const chapter = r.ChapterIndex && r.Chapter ? t('Chapter {n} · {title}', { n: r.ChapterIndex, title: r.Chapter }) : r.Chapter;
  const minutes = info?.Minutes ? info.Minutes * (1 - fraction) : 0;
  return {
    item, kind: t('Book · {format}', { format }), where: chapter || undefined, fraction,
    done: t('{n}% read', { n: Math.round(r.Percent) }),
    left: minutes ? t('About {time} left', { time: roughTime(minutes) }) : undefined
  };
}

function audioEntry(item: BaseItem, live: boolean): ContinueEntry {
  const pos = item.UserData?.PlaybackPositionTicks ?? 0;
  const total = item.RunTimeTicks ?? 0;
  const fraction = total ? pos / total : 0;
  const chapters = item.Chapters ?? [];
  let ci = -1;
  chapters.forEach((c, i) => { if (c.StartPositionTicks <= pos) ci = i; });
  const ch = ci >= 0 ? chapters[ci] : undefined;
  return {
    item,
    kind: live ? t('Audiobook · listening now') : t('Audiobook'),
    live,
    where: ch ? (ch.Name && !/^chapter\s*\d+$/i.test(ch.Name) ? t('Chapter {n} · {title}', { n: ci + 1, title: ch.Name }) : t('Chapter {n}', { n: ci + 1 })) : undefined,
    fraction,
    done: t('{n}% listened', { n: Math.round(fraction * 100) }),
    left: total ? timeLeft(ticksToSeconds(total - pos)) : undefined
  };
}

/* ───────── Grids ───────── */

function Grid({ items, render, empty, audio }: { items: BaseItem[]; render: (it: BaseItem) => ComponentChildren; empty: string; audio?: boolean }) {
  const [limit, setLimit] = useState(120);
  useEffect(() => setLimit(120), [items]);
  if (!items.length) return <EmptyState icon="folder" title={empty} />;
  return (
    <>
      <div class={'bk-grid' + (audio ? ' is-audio' : '')}>
        {items.slice(0, limit).map((it) => <div key={it.Id} class="bk-slot">{render(it)}</div>)}
      </div>
      {items.length > limit && <More onVisible={() => setLimit((l) => l + 120)} />}
    </>
  );
}

function More({ onVisible }: { onVisible: () => void }) {
  const [el, setEl] = useState<HTMLDivElement | null>(null);
  useEffect(() => {
    if (!el) return;
    const io = new IntersectionObserver((e) => { if (e.some((x) => x.isIntersecting)) onVisible(); }, { rootMargin: '800px' });
    io.observe(el);
    return () => io.disconnect();
  }, [el, onVisible]);
  return <div ref={setEl} class="bk-more"><span class="spinner" aria-label={t('Loading more')} /></div>;
}

/* ───────── Authors: each author with their books ───────── */

function Authors({ data, sort, focus, tabTo }: { data: BooksData; sort: SortId; focus: string | null; tabTo: (id: TabId, extra?: Record<string, string>) => string }) {
  const groups = useMemo(() => {
    const map = new Map<string, { name: string; items: BaseItem[] }>();
    for (const it of data.all) {
      const authors = authorsOf(it);
      for (const a of authors.length ? authors : [{ Name: '' }]) {
        const key = normalize(a.Name) || '~';
        const g = map.get(key) ?? { name: a.Name, items: [] };
        g.items.push(it);
        map.set(key, g);
      }
    }
    const surname = (n: string) => { const p = n.trim().split(/\s+/); return (p.length > 1 ? p[p.length - 1] + ' ' + p.slice(0, -1).join(' ') : n).toLowerCase(); };
    return Array.from(map.values()).sort((a, b) => (!a.name ? 1 : !b.name ? -1 : surname(a.name).localeCompare(surname(b.name))));
  }, [data]);
  useEffect(() => {
    if (!focus) return;
    requestAnimationFrame(() => document.getElementById('bk-a-' + normalize(focus))?.scrollIntoView({ block: 'start' }));
  }, [focus]);
  return (
    <div class="bk-sections">
      {groups.map((g) => {
        const books = g.items.filter((i) => !isAudioBook(i));
        const audio = g.items.filter(isAudioBook);
        const id = 'bk-a-' + (normalize(g.name) || 'none');
        const counts = [books.length ? plural(books.length, 'book') : '', audio.length ? plural(audio.length, 'audiobook') : ''].filter(Boolean).join(' · ');
        return (
          <section key={id} class="bk-sec bk-author" aria-labelledby={id}>
            <div class="bk-sec-head">
              <h2 id={id} class="bk-sec-title">{g.name || t('Unknown author')}<span class="bk-sec-count">{counts}</span></h2>
            </div>
            <div class="bk-grid">
              {sortItems(books, sort === 'added' ? 'released' : sort, data.reading).map((it) => (
                <div key={it.Id} class="bk-slot"><BookCard item={it} sub={isComic(it) ? comicSub(it, data.reading.get(it.Id)) : bookSub(it)} /></div>
              ))}
              {audio.map((it) => <div key={it.Id} class="bk-slot is-audio-slot"><AudioCard item={it} /></div>)}
            </div>
          </section>
        );
      })}
      {!groups.length && <EmptyState icon="person" title={t('No authors yet')} text={t('Authors come from each book\'s metadata.')} />}
      {focus && <Link to={tabTo('authors')} replace class="bk-sec-link">{t('All authors')}</Link>}
    </div>
  );
}

/* ───────── Series: every series, or one series' volumes in order ───────── */

function SeriesView({ data, sort, open, tabTo }: { data: BooksData; sort: SortId; open: string | null; tabTo: (id: TabId, extra?: Record<string, string>) => string }) {
  const groups = useMemo(() => {
    const list = data.series.slice();
    if (sort === 'title' || sort === 'author') return list.sort((a, b) => (sort === 'author' ? a.author.localeCompare(b.author) : 0) || a.name.localeCompare(b.name));
    return list.sort((a, b) => b.latest - a.latest);
  }, [data, sort]);
  const current = open ? groups.find((g) => g.key === normalize(open)) : undefined;
  if (current) {
    return (
      <div class="bk-sections">
        <section class="bk-sec" aria-labelledby="bk-one">
          <div class="bk-sec-head">
            <h2 id="bk-one" class="bk-sec-title">{current.name}<span class="bk-sec-count">{[current.author, t('{read} of {total} read', { read: current.read, total: current.volumes.length })].filter(Boolean).join(' · ')}</span></h2>
            <Link to={tabTo('series')} replace class="bk-sec-link">{t('All series')}</Link>
          </div>
          <div class="bk-grid">
            {current.volumes.map((v) => (
              <div key={v.Id} class="bk-slot"><VolumeCard item={v} reading={data.reading.get(v.Id)} /></div>
            ))}
          </div>
        </section>
      </div>
    );
  }
  if (!groups.length) return <EmptyState icon="collection" title={t('No series yet')} text={t('Books with a series in their metadata are grouped here.')} />;
  return (
    <div class="bk-series-grid is-all">
      {groups.map((g) => <SeriesCard key={g.key} group={g} to={tabTo('series', { series: g.name })} />)}
    </div>
  );
}

/* A volume in a series: "Book 2 · read" with a tick, "Book 3 · 46%" in the accent, "Book 4 · new". */
export function VolumeCard({ item, reading, current }: { item: BaseItem; reading?: Reading; current?: boolean }) {
  const n = item.IndexNumber;
  const read = !!item.UserData?.Played || !!reading?.Finished;
  const inProgress = !read && !!reading && reading.Percent > 0;
  const fresh = !read && !reading && !!item.DateCreated && Date.now() - time(item.DateCreated) < 30 * 86_400_000;
  const label = n !== undefined && n !== null ? (isComic(item) ? t('No. {n}', { n }) : t('Book {n}', { n })) : '';
  const state = read ? t('read') : inProgress ? `${Math.round(reading!.Percent)}%` : fresh ? t('new') : '';
  const sub = [label, state].filter(Boolean).join(' · ');
  return (
    <BookCard
      item={item}
      sub={sub}
      subClass={read ? 'is-dimmer' : inProgress ? 'is-accent' : undefined}
      titleClass={read ? 'is-dim' : current ? 'is-current' : undefined}
      badge={read ? <span class="bk-read-badge" aria-label={t('Read')}><svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg></span> : undefined}
    />
  );
}

export { sortItems, groupSeries };
