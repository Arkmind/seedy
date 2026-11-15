import type { ComponentChildren } from 'preact';
import { useEffect, useMemo, useState } from 'preact/hooks';
import { deviceId, get } from '../../api/client';
import type { BaseItem, SessionInfo } from '../../api/types';
import { dot, plural, ticksToSeconds, timeLeft } from '../../lib/format';
import { t, tc, tn } from '../../lib/i18n';
import { Link, navigate, setQuery, useLocation } from '../../lib/router';
import { useAsync } from '../../lib/store';
import { BackBar } from '../../ui/BackBar';
import { Header } from '../../ui/navbar/Header';
import { Icon } from '../../ui/icons';
import { MenuButton, MenuItem } from '../../ui/overlay';
import { EmptyState, ErrorState } from '../browse/parts';
import { AudioCover, BookCover } from './covers';
import { isBookOrPart } from '../../audio/api';
import { authorsOf, getBookInfo, isAudioBook, isComic, normalize, seriesOf, workKey, type Reading } from './api';
import {
  AudioCard, BookCard, ContinueCard, SectionHead, SeriesCard, VolumeCard, bookSub, comicSub, roughTime, useColumns,
  type ContinueEntry, type SeriesGroup
} from './parts';
import { LibraryMenuItems } from '../../ui/library-menu';
import { sortKey, time, useBooksData, type BooksData } from './library-data';
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

/* Whether a book belongs to a series with more than one volume here. */
const running = (data: BooksData, it: BaseItem) => { const s = seriesOf(it); return !!s && data.series.some((g) => g.key === normalize(s)); };

/* ───────── Screen ───────── */

export function BooksLibrary({ view }: { view: BaseItem }) {
  const loc = useLocation();
  const tabParam = loc.query.get('tab') as TabId | null;
  const tab: TabId = TABS.some((x) => x.id === tabParam) ? tabParam! : 'overview';
  const sortParam = loc.query.get('sort') as SortId | null;
  // Authors list by name unless asked otherwise; everything else newest first.
  const sort: SortId = SORTS.some((s) => s.id === sortParam) ? sortParam! : tab === 'authors' ? 'author' : 'added';
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
          <BackBar crumbs={[{ label: t('Libraries'), to: '/' }, { label: view.Name }]} />
          <div class="bk-head">
            <div class="bk-title">
              <h1>{view.Name}</h1>
              {counts && <span class="bk-count">{counts}</span>}
            </div>
            <div class="bk-head-actions">
              <SortMenu sort={sort} onChange={(s) => {
                // On the overview a sort opens the full Books grid in that order.
                if (tab === 'overview') navigate(`${base}?tab=books&sort=${s}`, { replace: true });
                else setQuery({ sort: s === (tab === 'authors' ? 'author' : 'added') ? undefined : s });
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
                    <LibraryMenuItems item={view} close={close} />
                  </>
                )}
              </MenuButton>
            </div>
          </div>
          <nav aria-label={t('Book views')} class="bk-tabs hscroll">
            {TABS.map((tb) => (
              <Link key={tb.id} to={tabTo(tb.id)} replace class="bk-tab" aria-current={tb.id === tab ? 'page' : undefined}>{tc(tb.label, 'tab')}</Link>
            ))}
          </nav>
        </div>

        {lib.error && !data ? <ErrorState error={lib.error} retry={lib.reload} title={t('Couldn\'t open this library')} />
          : !data ? <BooksSkeleton />
          : !data.all.length ? <EmptyState icon="folder" title={t('{name} is empty', { name: view.Name })} text={t('Once the server finds books in this library they will show up here.')} />
          : tab === 'overview' ? <Overview view={view} data={data} tabTo={tabTo} />
          : tab === 'books' ? <Grid items={sortItems(data.books, sort, data.reading)} empty={t('No books yet')} render={(it) => <BookCard item={it} sub={bookSub(it)} />} />
          : tab === 'comics' ? <Grid items={sortItems(data.comics, sort, data.reading)} empty={t('No comics yet')} render={(it) => <BookCard item={it} sub={comicSub(it, data.reading.get(it.Id), running(data, it))} />} />
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
  // Series being read first, then the newest.
  const series = useMemo(() => data.series.slice().sort((a, b) => (b.read_at - a.read_at) || (b.latest - a.latest)), [data]);
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
          {(n) => comics.slice(0, n).map((it) => <BookCard key={it.Id} item={it} sub={comicSub(it, data.reading.get(it.Id), running(data, it))} />)}
        </OverviewRow>
      )}
    </div>
  );
}

/* One row of cards, as many as fit. */
function OverviewRow({ id, title, link, linkLabel, min, gap, audio, children }: {
  id: string; title: string; link: string; linkLabel: string; min: number; gap: number; audio?: boolean; children: (n: number) => ComponentChildren;
}) {
  // Phones fit three covers a row.
  const phone = typeof window !== 'undefined' && window.matchMedia?.('(max-width: 720px)').matches;
  const [ref, cols] = useColumns<HTMLDivElement>(phone ? Math.round(min * 0.68) : min, phone ? 14 : gap);
  return (
    <section class="bk-sec" aria-labelledby={id}>
      <SectionHead id={id} title={title} link={link} linkLabel={linkLabel} />
      <div ref={ref} class={'bk-row' + (audio ? ' is-audio' : '')} style={{ gridTemplateColumns: `repeat(${cols || 1}, minmax(0, 1fr))`, gap: phone ? '14px' : undefined }}>
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
    const opened = reading.map((r) => ({ r, item: byId.get(r.ItemId) })).filter((x): x is { r: Reading; item: BaseItem } => !!x.item && !isAudioBook(x.item));
    // One card per work: the format read furthest (the EPUB at 46% over a PDF only opened), then the latest.
    const best = new Map<string, { r: Reading; item: BaseItem }>();
    for (const x of opened) {
      const k = workKey(x.item);
      const cur = best.get(k);
      if (!cur || x.r.Percent > cur.r.Percent || (x.r.Percent === cur.r.Percent && time(x.r.UpdatedAt) > time(cur.r.UpdatedAt))) best.set(k, x);
    }
    const books = Array.from(best.values());
    const listening = data.audio.filter((a) => !a.UserData?.Played && (a.UserData?.PlaybackPositionTicks ?? 0) > 0);
    const playing = await nowPlayingHere();
    type Cand = { at: number; make: () => Promise<ContinueEntry> };
    const cands: Cand[] = [
      ...books.map(({ r, item }) => ({ at: time(r.UpdatedAt), make: () => readingEntry(item, r) })),
      ...listening.map((item) => {
        // Jellyfin's session names the file playing, which for a book in several files is one of its parts.
        const live = isBookOrPart(item, playing);
        return { at: live ? Date.now() : time(item.UserData?.LastPlayedDate), make: async () => audioEntry(item, live) };
      })
    ];
    cands.sort((a, b) => b.at - a.at);
    return Promise.all(cands.slice(0, 6).map((c) => c.make()));
  }, [data, view.Id]);
  const [ref, cols] = useColumns<HTMLDivElement>(380, 20);
  const list = entries.data ?? [];
  if (!list.length) return null;
  return (
    <section class="bk-sec" aria-labelledby="bk-cont">
      <SectionHead id="bk-cont" title={t('Continue')} />
      <div ref={ref} class="bk-cont-grid" style={{ gridTemplateColumns: `repeat(${cols || 1}, minmax(0, 1fr))` }}>
        {cols > 0 && list.slice(0, cols).map((e) => <ContinueCard key={e.item.Id} entry={e} />)}
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
      item, kind: t('Comic · {format}', { format: (/\.(\w+)$/.exec(item.Path ?? '')?.[1] ?? 'CBZ').toUpperCase() }),
      where: pages ? t('Page {n} of {total}', { n: page, total: pages }) : undefined,
      fraction, done: t('{n}% read', { n: Math.round(r.Percent) }),
      left: pages ? tn(Math.max(0, pages - page), '{n} page left', '{n} pages left') : undefined
    };
  }
  const format = (/\.(\w+)$/.exec(item.Path ?? '')?.[1] ?? 'EPUB').toUpperCase();
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

/* ───────── Authors: a card per author, or one author's books ───────── */

interface AuthorGroup { key: string; name: string; books: BaseItem[]; audio: BaseItem[]; added: number; readAt: number; released: number }

function Authors({ data, sort, focus, tabTo }: { data: BooksData; sort: SortId; focus: string | null; tabTo: (id: TabId, extra?: Record<string, string>) => string }) {
  const groups = useMemo(() => {
    const map = new Map<string, AuthorGroup>();
    // One card per work for books (the EPUB and PDF of a title count once).
    const shown = new Set([...data.books, ...data.comics].map((i) => i.Id));
    for (const it of data.all) {
      if (!isAudioBook(it) && !shown.has(it.Id)) continue;
      const authors = authorsOf(it);
      for (const a of authors.length ? authors : [{ Name: '' }]) {
        const key = normalize(a.Name) || '~';
        const g = map.get(key) ?? { key, name: a.Name, books: [], audio: [], added: 0, readAt: 0, released: 0 };
        (isAudioBook(it) ? g.audio : g.books).push(it);
        g.added = Math.max(g.added, time(it.DateCreated));
        g.readAt = Math.max(g.readAt, lastRead(it, data.reading));
        g.released = Math.max(g.released, released(it));
        map.set(key, g);
      }
    }
    const surname = (n: string) => { const p = n.trim().split(/\s+/); return (p.length > 1 ? p[p.length - 1] + ' ' + p.slice(0, -1).join(' ') : n).toLowerCase(); };
    const byName = (a: AuthorGroup, b: AuthorGroup) => (!a.name ? 1 : !b.name ? -1 : surname(a.name).localeCompare(surname(b.name)));
    const list = Array.from(map.values());
    if (sort === 'added') return list.sort((a, b) => (b.added - a.added) || byName(a, b));
    if (sort === 'read') return list.sort((a, b) => (b.readAt - a.readAt) || byName(a, b));
    if (sort === 'released') return list.sort((a, b) => (b.released - a.released) || byName(a, b));
    return list.sort(byName);
  }, [data, sort]);
  const counts = (g: AuthorGroup) => dot(g.books.length ? plural(g.books.length, 'book') : '', g.audio.length ? plural(g.audio.length, 'audiobook') : '');
  const one = focus ? groups.find((g) => g.key === (normalize(focus) || '~')) : undefined;
  if (one) {
    return (
      <div class="bk-sections">
        <section class="bk-sec" aria-labelledby="bk-one">
          <div class="bk-sec-head">
            <h2 id="bk-one" class="bk-sec-title">{one.name || t('Unknown author')}<span class="bk-sec-count">{counts(one)}</span></h2>
            <Link to={tabTo('authors')} replace class="bk-sec-link">{t('All authors')}</Link>
          </div>
          {one.books.length > 0 && (
            <div class="bk-grid">
              {sortItems(one.books, sort === 'added' ? 'released' : sort, data.reading).map((it) => (
                <div key={it.Id} class="bk-slot"><BookCard item={it} sub={isComic(it) ? comicSub(it, data.reading.get(it.Id), running(data, it)) : bookSub(it)} /></div>
              ))}
            </div>
          )}
        </section>
        {one.audio.length > 0 && (
          <section class="bk-sec" aria-labelledby="bk-one-au">
            <SectionHead id="bk-one-au" title={t('Audiobooks')} />
            <div class="bk-grid is-audio">
              {one.audio.map((it) => <div key={it.Id} class="bk-slot"><AudioCard item={it} /></div>)}
            </div>
          </section>
        )}
      </div>
    );
  }
  if (!groups.length) return <EmptyState icon="person" title={t('No authors yet')} text={t('Authors come from each book\'s metadata.')} />;
  return (
    <div class="bk-authors">
      {groups.map((g) => {
        const covers = sortItems(g.books, 'released', data.reading).slice(0, 4);
        return (
          <Link key={g.key} to={tabTo('authors', { author: g.name })} class="bk-author" aria-label={g.name || t('Unknown author')}>
            <span class="bk-author-covers" aria-hidden="true">
              {covers.map((c) => <span key={c.Id} class="bk-author-cover"><BookCover item={c} size="xs" width={160} /></span>)}
              {!covers.length && g.audio[0] && <span class="bk-author-cover is-audio"><AudioCover item={g.audio[0]} size="xs" width={200} noTitle /></span>}
            </span>
            <span class="bk-series-text">
              <span class="bk-series-name">{g.name || t('Unknown author')}</span>
              <span class="bk-series-sub">{counts(g)}</span>
            </span>
          </Link>
        );
      })}
    </div>
  );
}

/* ───────── Series: every series, or one series' volumes in order ───────── */

function SeriesView({ data, sort, open, tabTo }: { data: BooksData; sort: SortId; open: string | null; tabTo: (id: TabId, extra?: Record<string, string>) => string }) {
  const groups = useMemo(() => {
    const list = data.series.slice();
    if (sort === 'title' || sort === 'author') return list.sort((a, b) => (sort === 'author' ? a.author.localeCompare(b.author) : 0) || a.name.localeCompare(b.name));
    if (sort === 'read') return list.sort((a, b) => (b.read_at - a.read_at) || (b.latest - a.latest));
    return list.sort((a, b) => b.latest - a.latest);
  }, [data, sort]);
  const current = open ? groups.find((g) => g.key === normalize(open)) : undefined;
  if (current) {
    return (
      <div class="bk-sections">
        <section class="bk-sec" aria-labelledby="bk-one">
          <div class="bk-sec-head">
            <h2 id="bk-one" class="bk-sec-title">{current.name}<span class="bk-sec-count">{dot(current.author, t('{read} of {total} read', { read: current.read, total: current.volumes.length }))}</span></h2>
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
