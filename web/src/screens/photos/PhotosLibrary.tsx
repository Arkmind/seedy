import { Fragment } from 'preact';
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';

import { getItems } from '../../api/jellyfin';
import type { BaseItem } from '../../api/types';
import { meStore } from '../../lib/app-state';
import { CUSTOM, readCustom } from '../me/prefs';
import { t } from '../../lib/i18n';
import { fmtShortMonth } from '../../lib/format';
import { navigate, useLocation } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { Header } from '../../ui/navbar/Header';
import { MenuButton, MenuItem, toast } from '../../ui/overlay';
import { headerHeight } from '../../ui/sticky';
import { EmptyState, ErrorState, Sentinel, usePaged } from '../browse/parts';
import { type Selection, useSelection } from '../browse/library-select';
import { manageChanged } from '../browse/manage/host';
import { Icon } from '../../ui/icons';
import {
  allMedia, cameraName, countsLabel, getMedia, groupByDay, isVideo, mediaQuery, memoriesOf, monthsOf, yearsOf, type Memory, type PhotoItem
} from './api';
import { PIcon } from './icons';
import {
  FolderCard, FolderGrid, MemoryCard, PhotoSelectionBar, rowFor, SizeSwitch, TabRow, Timeline, openPhoto, usePhotoEdits, useTileSize
} from './parts';
import { PhotoViewer } from './Viewer';
import { LibraryMenuItems } from '../../ui/library-menu';
import './photos.css';

/* A photos library (Photos · Timeline board): its name and counts, Slideshow, Select and ⋯, then Timeline, Folders,
   Videos, Favorites and Cameras with the tile size. The timeline opens with "On this day" (photos from this date in
   earlier years) and lists months and days in justified rows, with a rail to jump to a year or month. */

type TabId = 'timeline' | 'folders' | 'videos' | 'favorites' | 'cameras';

export function PhotosLibrary({ view }: { view: BaseItem }) {
  const loc = useLocation();
  const tabs: { id: TabId; label: string }[] = [
    { id: 'timeline', label: t('Timeline') }, { id: 'folders', label: t('Folders') }, { id: 'videos', label: t('Videos') },
    { id: 'favorites', label: t('Favorites') }, { id: 'cameras', label: t('Cameras') }
  ];
  const tabParam = loc.query.get('tab') as TabId | null;
  // The tab it opens on is the person's choice (Settings · Home screen › Libraries › Opens on).
  const me = useStore(meStore);
  const opensOn = readCustom(CUSTOM.opensOn, me.prefs)[view.Id] as TabId | undefined;
  const first: TabId = tabs.some((x) => x.id === opensOn) ? opensOn! : 'timeline';
  const tab: TabId = tabs.some((x) => x.id === tabParam) ? tabParam! : first;
  const camera = loc.query.get('camera');
  const base = `/library/${view.Id}`;
  const [size, setSize] = useTileSize();
  const row = rowFor(size);
  const managed = useStore(manageChanged);

  const counts = useAsync(() => Promise.all([
    getItems({ parentId: view.Id, includeItemTypes: ['Photo'], limit: 0, fields: [], enableImageTypes: [] }),
    getItems({ parentId: view.Id, includeItemTypes: ['Video'], limit: 0, fields: [], enableImageTypes: [] })
  ]).then(([p, v]) => ({ photos: p.TotalRecordCount, videos: v.TotalRecordCount })), [view.Id, managed.n]);

  // The grid tabs page through the library, newest first.
  const grid = tab === 'timeline' || tab === 'videos' || tab === 'favorites';
  const tabQuery = { includeItemTypes: tab === 'videos' ? ['Video'] : undefined, isFavorite: tab === 'favorites' ? true : undefined };
  const paged = usePaged((start, limit, signal) => (grid
    ? getMedia(mediaQuery(view.Id, { startIndex: start, limit, ...tabQuery }), signal)
    : Promise.resolve({ Items: [], TotalRecordCount: 0 })), [view.Id, tab, managed.n], 150);

  // Cameras: every photo, grouped by the camera that took it.
  const everything = useAsync(() => (tab === 'cameras' ? allMedia(view.Id) : Promise.resolve([] as PhotoItem[])), [view.Id, tab, managed.n]);
  const cameras = useMemo(() => {
    const map = new Map<string, PhotoItem[]>();
    for (const it of everything.data ?? []) {
      if (isVideo(it)) continue;
      const k = cameraName(it) || '';
      map.set(k, [...(map.get(k) ?? []), it]);
    }
    return [...map].sort((a, b) => (a[0] ? 0 : 1) - (b[0] ? 0 : 1) || b[1].length - a[1].length);
  }, [everything.data]);
  const currentCamera = camera ?? cameras[0]?.[0];
  const cameraItems = cameras.find(([k]) => k === currentCamera)?.[1] ?? [];

  const edits = usePhotoEdits();
  const { viewerList, setViewerList } = edits;
  const list: PhotoItem[] = edits.patch(grid ? (paged.items as PhotoItem[]) : tab === 'cameras' ? cameraItems : []);

  const sel = useSelection(list);
  useEffect(() => { sel.stop(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [view.Id, tab, camera]);

  const slideshow = async () => {
    let from = list.find((x) => !isVideo(x));
    if (!from) {
      const r = await getMedia(mediaQuery(view.Id, { includeItemTypes: ['Photo'], limit: 200 })).catch(() => undefined);
      if (r?.Items.length) { setViewerList(r.Items); from = r.Items[0]; }
    }
    if (from) openPhoto(from.Id, true);
    else toast(t('No photos here yet'), 'error');
  };

  const countText = counts.data ? countsLabel(counts.data.photos, counts.data.videos, ' · ') : '';
  const shownList = viewerList ?? list;

  return (
    <div class="page ph-page">
      <Header active={{ viewId: view.Id }} />
      <main class="ph-main">
        <div class="ph-top">
          <div class="ph-head">
            <div class="ph-title"><h1>{view.Name}</h1>{countText && <span class="ph-count">{countText}</span>}</div>
            <div class="ph-head-actions">
              <button type="button" class="ph-btn is-primary" onClick={() => void slideshow()}><PIcon name="slideshow" size={18} />{t('Slideshow')}</button>
              <button type="button" class={'ph-btn' + (sel.active ? ' is-on' : '')} aria-pressed={sel.active ? 'true' : 'false'} disabled={tab === 'folders'} onClick={() => (sel.active ? sel.stop() : sel.start())}>
                <PIcon name="check" size={18} />{t('Select')}
              </button>
              <LibraryMenu view={view} onRefresh={() => { paged.reload(); counts.reload(); everything.reload(); }} />
            </div>
          </div>
          <div class="ph-bar">
            <TabRow label={t('Photo views')} tabs={tabs} current={tab} to={(id) => (id === first ? base : `${base}?tab=${id}`)} />
            {tab !== 'folders' && <SizeSwitch size={size} onChange={setSize} />}
          </div>
          {sel.active && <PhotoSelectionBar sel={sel} total={tab === 'cameras' ? list.length : paged.total} name={view.Name} onChanged={edits.onChanged} onSelectAll={async () => {
            if (tab === 'cameras') { sel.set(list); return; }
            const r = await getMedia(mediaQuery(view.Id, { ...tabQuery, limit: paged.total }));
            sel.set(r.Items);
          }} onShow={edits.onShow} />}
        </div>

        {tab === 'timeline' && !sel.active && <Memories view={view} onOpen={(m) => { setViewerList(m.items); openPhoto(m.items[0].Id); }} />}

        {grid && (
          paged.error && !paged.items.length ? <ErrorState error={paged.error} retry={paged.reload} title={t('Couldn’t load the photos')} />
            : !paged.loading && !paged.items.length ? (
              tab === 'favorites'
                ? <EmptyState icon="heart" title={t('No favorite photos yet')} text={t('Tap the heart on a photo to keep it here.')} />
                : tab === 'videos' ? <EmptyState icon="film" title={t('No videos here')} />
                  : <EmptyState icon="film" title={t('No photos here yet')} text={t('Photos added to this library’s folders show up here, newest first.')} />
            ) : (
              <TimelineWithRail view={view} list={list} paged={paged} row={row} select={sel} rail={tab === 'timeline'} onOpen={(it) => openPhoto(it.Id)} />
            )
        )}

        {tab === 'folders' && <Folders view={view} />}

        {tab === 'cameras' && (
          everything.error ? <ErrorState error={everything.error} retry={everything.reload} />
            : !everything.data ? <div class="ph-loading"><span class="spinner" /></div>
            : !cameras.length ? <EmptyState icon="film" title={t('No photos here yet')} />
            : (
              <>
                <div class="ph-chips" role="list" aria-label={t('Cameras')}>
                  {cameras.map(([k, items]) => (
                    <a key={k || 'none'} role="listitem" href={`${base}?tab=cameras&camera=${encodeURIComponent(k)}`} class="ph-chip" aria-current={k === currentCamera ? 'true' : undefined}
                      onClick={(e) => { e.preventDefault(); navigate(`${base}?tab=cameras&camera=${encodeURIComponent(k)}`, { replace: true, keepScroll: true }); }}>
                      <PIcon name="camera" size={15} />{k || t('No camera details')} <span>{items.length}</span>
                    </a>
                  ))}
                </div>
                <div class="ph-timeline"><div class="ph-days"><Timeline months={groupByDay(list)} row={row} select={sel.grid} onOpen={(it) => openPhoto(it.Id)} /></div></div>
              </>
            )
        )}
      </main>

      {loc.query.get('photo') && (
        <PhotoViewer
          list={shownList}
          total={viewerList ? viewerList.length : tab === 'cameras' ? list.length : paged.total}
          more={viewerList || !grid ? undefined : paged.loadMore}
          done={viewerList || !grid ? true : paged.done}
          onItem={edits.onItem}
        />
      )}
    </div>
  );
}

/* ───────── Timeline with the date rail ───────── */

function TimelineWithRail({ view, list, paged, row, select, rail, onOpen }: {
  view: BaseItem; list: PhotoItem[]; paged: ReturnType<typeof usePaged>; row: number; select: Selection; rail: boolean; onOpen: (it: PhotoItem) => void;
}) {
  const months = useMemo(() => groupByDay(list), [list]);
  const years = useAsync(() => (rail ? yearsOf(view.Id) : Promise.resolve([] as number[])), [view.Id, rail]);
  const [current, setCurrent] = useState<{ y: number; m: number } | null>(null);
  const cur = current ?? (months[0] ? { y: months[0].date.getFullYear(), m: months[0].date.getMonth() } : null);
  const curYear = cur?.y;
  const yearMonths = useAsync(() => (rail && curYear ? monthsOf(view.Id, curYear) : Promise.resolve([] as number[])), [view.Id, curYear, rail]);
  const wrap = useRef<HTMLDivElement>(null);

  // The month at the top of the screen is the rail's current one.
  useEffect(() => {
    if (!rail) return;
    let raf = 0;
    const onScroll = () => {
      cancelAnimationFrame(raf);
      raf = requestAnimationFrame(() => {
        const top = headerHeight() + 90;
        let found: string | null = null;
        for (const el of wrap.current?.querySelectorAll<HTMLElement>('.ph-month') ?? []) {
          if (el.getBoundingClientRect().top <= top) found = el.dataset.month ?? null;
          else break;
        }
        const k = found ?? wrap.current?.querySelector<HTMLElement>('.ph-month')?.dataset.month;
        if (k) {
          const [y, m] = k.split('-').map(Number);
          setCurrent((c) => (c && c.y === y && c.m === m ? c : { y, m }));
        }
      });
    };
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => { cancelAnimationFrame(raf); window.removeEventListener('scroll', onScroll); };
  }, [rail, months.length]);

  // Jump: load until that month is on the page, then scroll to it.
  const jump = async (y: number, m?: number) => {
    const month = m ?? (y === curYear ? undefined : 11);
    const end = month === undefined ? new Date(Date.UTC(y + 1, 0, 1)) : new Date(Date.UTC(y, month + 1, 1));
    try {
      const newer = await getItems({ parentId: view.Id, includeItemTypes: ['Photo', 'Video'], limit: 0, fields: [], enableImageTypes: [], minPremiereDate: end.toISOString() });
      await paged.ensure(newer.TotalRecordCount + 1);
    } catch { /* scroll to what's there */ }
    window.setTimeout(() => {
      const all = [...(wrap.current?.querySelectorAll<HTMLElement>('.ph-month') ?? [])];
      const target = all.find((el) => {
        const [yy, mm] = (el.dataset.month ?? '').split('-').map(Number);
        return yy < y || (yy === y && (month === undefined || mm <= month));
      });
      if (target) window.scrollTo({ top: target.getBoundingClientRect().top + window.scrollY - headerHeight() - 24, behavior: 'smooth' });
    }, 60);
  };

  // "Oct", "Sep" (the app's short month names, as on the board).
    return (
    <div class="ph-timeline" ref={wrap}>
      <div class="ph-days">
        <Timeline months={months} row={row} select={select.grid} onOpen={onOpen} />
        {!paged.done && <Sentinel onVisible={paged.loadMore} token={paged.items.length} disabled={paged.loading || !!paged.error} />}
        {paged.loading && <div class="ph-loading"><span class="spinner" aria-label={t('Loading more')} /></div>}
        {paged.error && paged.items.length > 0 && <div class="ph-loading"><button type="button" class="btn btn-secondary" onClick={paged.loadMore}><Icon name="refresh" size={18} />{t('Load more')}</button></div>}
      </div>
      {rail && (years.data?.length ?? 0) > 0 && (
        <nav class="ph-rail" aria-label={t('Jump to a date')}>
          {years.data!.map((y) => (
            <Fragment key={y}>
              <a href="#" class="ph-rail-y" aria-current={y === curYear && !(yearMonths.data?.length) ? 'true' : undefined} onClick={(e) => { e.preventDefault(); void jump(y); }}>{y}</a>
              {y === curYear && yearMonths.data?.map((m) => (
                <a key={`${y}-${m}`} href="#" class="ph-rail-m" aria-current={cur?.m === m ? 'true' : undefined} onClick={(e) => { e.preventDefault(); void jump(y, m); }}>{fmtShortMonth(m)}</a>
              ))}
            </Fragment>
          ))}
        </nav>
      )}
    </div>
  );
}

/* ───────── On this day ───────── */

function Memories({ view, onOpen }: { view: BaseItem; onOpen: (m: Memory) => void }) {
  const [all, setAll] = useState(false);
  // "All memories": this time of year (two weeks either side), not just these days.
  const memories = useAsync(() => memoriesOf(view.Id, new Date(), all ? 15 : 3), [view.Id, all]);
  const [shown, setShown] = useState<Memory[]>([]);
  useEffect(() => { if (memories.data) setShown(memories.data); }, [memories.data]);
  if (!shown.length) return null;
  return (
    <section class="ph-memories" aria-labelledby="ph-mem-h">
      <div class="ph-sec-head">
        <h2 id="ph-mem-h">{t('On this day')}</h2>
        <button type="button" class="ph-sec-link" aria-expanded={all ? 'true' : 'false'} onClick={() => setAll((v) => !v)}>{all ? t('Fewer memories') : t('All memories')}</button>
      </div>
      <div class="ph-memory-grid">
        {(all ? shown : shown.slice(0, 4)).map((m) => <MemoryCard key={m.key} memory={m} onOpen={() => onOpen(m)} />)}
      </div>
    </section>
  );
}

/* ───────── Folders ───────── */

function Folders({ view }: { view: BaseItem }) {
  const folders = useAsync(() => getItems({
    parentId: view.Id, recursive: false, includeItemTypes: ['PhotoAlbum', 'Folder'], sortBy: ['SortName'], sortOrder: 'Ascending',
    fields: ['DateCreated', 'Path'], enableImageTypes: ['Primary']
  }), [view.Id]);
  if (folders.error) return <ErrorState error={folders.error} retry={folders.reload} />;
  if (!folders.data) return <div class="ph-loading"><span class="spinner" /></div>;
  if (!folders.data.Items.length) return <EmptyState icon="film" title={t('No folders here')} text={t('Photos sitting in the library’s own folder show on the timeline.')} />;
  return (
    <FolderGrid>
      {folders.data.Items.map((f) => <FolderCard key={f.Id} item={f} to={`/details/${f.Id}`} />)}
    </FolderGrid>
  );
}

/* ───────── ⋯ ───────── */

function LibraryMenu({ view, onRefresh }: { view: BaseItem; onRefresh: () => void }) {
  return (
    <MenuButton
      width={260}
      button={({ ref, open, toggle }) => (
        <button ref={ref} type="button" class="ph-icon-btn" aria-label={t('Library options')} aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={toggle}>
          <PIcon name="more" size={20} />
        </button>
      )}
    >
      {(close) => (
        <>
          <MenuItem icon="refresh" label={t('Refresh')} onSelect={() => { close(); onRefresh(); }} />
          <LibraryMenuItems item={view} close={close} />
        </>
      )}
    </MenuButton>
  );
}
