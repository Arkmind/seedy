import { useEffect, useMemo, useState } from 'preact/hooks';
import { JELLYFIN_WEB, post } from '../../api/client';
import type { BaseItem } from '../../api/types';
import { isAdmin, userStore } from '../../lib/app-state';
import { t } from '../../lib/i18n';
import { Link, setQuery, useLocation } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { BackBar, type Crumb } from '../../ui/BackBar';
import { Header } from '../../ui/navbar/Header';
import { MenuButton, MenuItem, toast } from '../../ui/overlay';
import { EmptyState, ErrorState, Sentinel, usePaged } from '../browse/parts';
import { useSelection } from '../browse/library-select';
import { manageChanged } from '../browse/manage/host';
import {
  MEDIA_TYPES, PHOTO_FIELDS, ancestorsOf, dateRange, folderInfo, getMedia, groupByDay, isVideo, subfolders, thumbUrl, type FolderInfo, type PhotoItem
} from './api';
import { PIcon } from './icons';
import { FolderCard, FolderGrid, JustifiedRows, rowFor, TabRow, Timeline, countsLine, openPhoto, useTileSize } from './parts';
import { PhotoSelectionBar } from './PhotosLibrary';
import { PhotoViewer } from './Viewer';
import { downloadZip } from './zip';
import './photos.css';

/* A folder of photos (Photos · Folder board): back button and breadcrumbs, three photos beside the folder's name,
   counts, dates and path, Slideshow, Shuffle, Download and ⋯, the folders inside it, then All · Photos · Videos ·
   Favorites with the sort, and its photos by day. */

type Show = 'all' | 'photos' | 'videos' | 'favorites';
const SORTS = [
  { id: 'oldest', label: 'Date taken, oldest first', by: ['DateCreated', 'SortName'], order: 'Ascending' as const },
  { id: 'newest', label: 'Date taken, newest first', by: ['DateCreated', 'SortName'], order: 'Descending' as const },
  { id: 'name', label: 'Name', by: ['SortName'], order: 'Ascending' as const }
];

export function PhotoAlbumPage({ item }: { item: BaseItem }) {
  const loc = useLocation();
  const showParam = loc.query.get('show') as Show | null;
  const show: Show = showParam && ['photos', 'videos', 'favorites'].includes(showParam) ? showParam : 'all';
  const sort = SORTS.find((s) => s.id === loc.query.get('sort')) ?? SORTS[0];
  const [size] = useTileSize();
  const row = rowFor(size);
  const managed = useStore(manageChanged);
  const user = useStore(userStore);
  const canDownload = !!(user?.Policy as { EnableContentDownloading?: boolean } | undefined)?.EnableContentDownloading;
  const base = `/details/${item.Id}`;

  const ancestors = useAsync(() => ancestorsOf(item.Id).catch(() => [] as BaseItem[]), [item.Id]);
  const library = ancestors.data?.[0];
  // [library, its folder on disk, …folders above this one]
  const between = (ancestors.data ?? []).slice(2).filter((a) => a.Type === 'PhotoAlbum' || a.Type === 'Folder');
  const crumbs: Crumb[] = library
    ? [{ label: library.Name, to: `/library/${library.Id}` }, { label: t('Folders'), to: `/library/${library.Id}?tab=folders` }, ...between.map((a) => ({ label: a.Name, to: `/details/${a.Id}` })), { label: item.Name }]
    : [{ label: t('Home'), to: '/' }, { label: item.Name }];

  const info = useAsync(() => folderInfo(item, false), [item.Id, managed.n]);
  const subs = useAsync((signal) => subfolders(item.Id, signal), [item.Id, managed.n]);
  const paged = usePaged((start, limit, signal) => getMedia({
    parentId: item.Id, recursive: false, includeItemTypes: show === 'photos' ? ['Photo'] : show === 'videos' ? ['Video'] : MEDIA_TYPES,
    isFavorite: show === 'favorites' ? true : undefined, sortBy: sort.by, sortOrder: sort.order, startIndex: start, limit,
    fields: PHOTO_FIELDS, enableImageTypes: ['Primary'], imageTypeLimit: 1
  }, signal), [item.Id, show, sort.id, managed.n], 200);
  const [local, setLocal] = useState<Record<string, PhotoItem>>({});
  const list = (paged.items as PhotoItem[]).map((x) => local[x.Id] ?? x);
  const months = useMemo(() => (sort.id === 'name' ? [] : groupByDay(list)), [list, sort.id]);
  const sel = useSelection(list);
  const [viewerList, setViewerList] = useState<PhotoItem[] | null>(null);
  useEffect(() => { if (!loc.query.get('photo')) setViewerList(null); }, [loc.query.get('photo')]);

  const all = async (q: { random?: boolean; photos?: boolean } = {}) => {
    const r = await getMedia({
      parentId: item.Id, recursive: false, includeItemTypes: q.photos ? ['Photo'] : MEDIA_TYPES, sortBy: q.random ? ['Random'] : sort.by, sortOrder: sort.order,
      limit: q.random ? 500 : undefined, fields: PHOTO_FIELDS, enableImageTypes: ['Primary'], imageTypeLimit: 1
    });
    return r.Items;
  };
  const slideshow = async (shuffle: boolean) => {
    try {
      if (shuffle) {
        const photos = await all({ random: true, photos: true });
        if (!photos.length) { toast(t('No photos here yet'), 'error'); return; }
        setViewerList(photos);
        openPhoto(photos[0].Id, true);
        return;
      }
      const first = list.find((x) => !isVideo(x)) ?? (await all({ photos: true }))[0];
      if (first) openPhoto(first.Id, true);
      else toast(t('No photos here yet'), 'error');
    } catch {
      toast(t('Couldn’t load the photos'), 'error');
    }
  };
  const [zipping, setZipping] = useState(false);
  const download = async () => {
    setZipping(true);
    try { await downloadZip(item.Name, await all()); } catch { toast(t('Couldn’t download these'), 'error'); }
    setZipping(false);
  };

  const fi = info.data;
  const counts = fi ? countsLine(fi.photos, fi.videos) : '';
  const range = fi ? dateRange(fi.from, fi.to) : '';
  const path = (item as PhotoItem).Path;
  const covers = fi?.covers ?? [];
  const folders = subs.data?.Items ?? [];
  const empty = !paged.loading && !paged.items.length;

  return (
    <div class="page ph-page">
      <Header active={library ? { viewId: library.Id } : null} />
      <main class="ph-main is-album">
        <BackBar crumbs={crumbs} />

        <section class="ph-hero" aria-labelledby="ph-alb-h">
          <div class={'ph-mosaic' + (covers.length < 3 ? ' is-one' : '')} aria-hidden="true">
            {(covers.length >= 3 ? covers.slice(0, 3) : covers.slice(0, 1)).map((c, i) => (
              <span key={c.Id}><img src={thumbUrl(c, i ? 220 : 440)} alt="" draggable={false} /></span>
            ))}
            {!covers.length && <span class="ph-mosaic-empty"><PIcon name="folder" size={40} stroke={1.8} /></span>}
          </div>
          <div class="ph-hero-info">
            <span class="ph-eyebrow"><PIcon name="folder" size={16} />{t('Folder')}</span>
            <h1 id="ph-alb-h">{item.Name}</h1>
            <span class="ph-hero-counts">{[counts, range].filter(Boolean).join(' · ') || ' '}</span>
            {path && <span class="ph-hero-path">{path}</span>}
            <div class="ph-hero-actions">
              <button type="button" class="ph-btn is-primary" onClick={() => void slideshow(false)}><PIcon name="slideshow" size={18} />{t('Slideshow')}</button>
              <button type="button" class="ph-btn" onClick={() => void slideshow(true)}><PIcon name="shuffle" size={18} />{t('Shuffle')}</button>
              {canDownload && <button type="button" class="ph-btn" disabled={zipping || !fi?.count} onClick={() => void download()}><PIcon name="download" size={18} />{zipping ? t('Preparing…') : t('Download')}</button>}
              <AlbumMenu item={item} onSelect={() => sel.start()} onRefresh={() => { paged.reload(); info.reload(); subs.reload(); }} />
            </div>
            {folders.length > 0 && (
              <div class="ph-chips">
                {folders.map((f) => <SubfolderChip key={f.Id} item={f} />)}
              </div>
            )}
          </div>
        </section>

        {(fi?.count ?? 1) > 0 && (
          <div class="ph-bar">
            <TabRow
              label={t('Show')}
              tabs={[{ id: 'all' as Show, label: t('All') }, { id: 'photos' as Show, label: t('Photos') }, { id: 'videos' as Show, label: t('Videos') }, { id: 'favorites' as Show, label: t('Favorites') }]}
              current={show}
              to={(id) => { const q = new URLSearchParams(loc.query); q.delete('photo'); q.delete('play'); if (id === 'all') q.delete('show'); else q.set('show', id); const s = q.toString(); return base + (s ? '?' + s : ''); }}
            />
            <SortMenu sort={sort.id} />
          </div>
        )}

        {sel.active && (
          <PhotoSelectionBar sel={sel} total={paged.total} name={item.Name}
            onChanged={(changed) => setLocal((m) => ({ ...m, ...Object.fromEntries(changed.map((x) => [x.Id, x])) }))}
            onSelectAll={async () => sel.set(await all())}
            onShow={(photos) => { setViewerList(photos); openPhoto(photos[0].Id, true); }} />
        )}

        {paged.error && !paged.items.length ? <ErrorState error={paged.error} retry={paged.reload} title={t('Couldn’t load the photos')} />
          : empty ? (
            folders.length > 0 && show === 'all'
              ? <FolderGrid>{folders.map((f) => <FolderCard key={f.Id} item={f} to={`/details/${f.Id}`} />)}</FolderGrid>
              : <EmptyState icon={show === 'favorites' ? 'heart' : 'film'} title={show === 'favorites' ? t('No favorite photos yet') : show === 'videos' ? t('No videos here') : t('No photos here yet')} />
          ) : (
            <div class="ph-days">
              {sort.id === 'name'
                ? <JustifiedRows items={list} row={row} select={sel.grid} onOpen={(it) => openPhoto(it.Id)} />
                : <Timeline months={months} row={row} select={sel.grid} own={item.Name} headings={months.length > 1} onOpen={(it) => openPhoto(it.Id)} />}
              {!paged.done && <Sentinel onVisible={paged.loadMore} token={paged.items.length} disabled={paged.loading || !!paged.error} />}
              {paged.loading && <div class="ph-loading"><span class="spinner" aria-label={t('Loading more')} /></div>}
            </div>
          )}
      </main>

      {loc.query.get('photo') && (
        <PhotoViewer
          list={viewerList ?? list}
          total={viewerList ? viewerList.length : paged.total}
          more={viewerList ? undefined : paged.loadMore}
          done={viewerList ? true : paged.done}
          onItem={(it) => { setLocal((m) => ({ ...m, [it.Id]: it })); if (viewerList) setViewerList((l) => l && l.map((x) => (x.Id === it.Id ? it : x))); }}
        />
      )}
    </div>
  );
}

function SubfolderChip({ item }: { item: BaseItem }) {
  const [info, setInfo] = useState<FolderInfo>();
  useEffect(() => { folderInfo(item).then(setInfo, () => undefined); }, [item.Id]);
  return (
    <Link to={`/details/${item.Id}`} class="ph-chip">
      <PIcon name="folder" size={15} />{item.Name} {info && <span>{info.count}</span>}
    </Link>
  );
}

function SortMenu({ sort }: { sort: string }) {
  const cur = SORTS.find((s) => s.id === sort) ?? SORTS[0];
  return (
    <MenuButton
      width={260}
      button={({ ref, open, toggle }) => (
        <button ref={ref} type="button" class="ph-sort" aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={toggle}>
          <span class="ph-sort-k">{t('Sort')}</span> {t(cur.label)}<PIcon name="chevronDown" size={16} />
        </button>
      )}
    >
      {(close) => (
        <>
          <span class="menu-label">{t('Sort by')}</span>
          {SORTS.map((o) => <MenuItem key={o.id} label={t(o.label)} pressed={o.id === cur.id} onSelect={() => { close(); setQuery({ sort: o.id === 'oldest' ? undefined : o.id }); }} />)}
        </>
      )}
    </MenuButton>
  );
}

function AlbumMenu({ item, onSelect, onRefresh }: { item: BaseItem; onSelect: () => void; onRefresh: () => void }) {
  return (
    <MenuButton
      width={260}
      button={({ ref, open, toggle }) => (
        <button ref={ref} type="button" class="ph-icon-btn" aria-label={t('More options')} aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={toggle}>
          <PIcon name="more" size={20} />
        </button>
      )}
    >
      {(close) => (
        <>
          <MenuItem icon="check" label={t('Select photos')} sub={t('Act on several at once')} onSelect={() => { close(); onSelect(); }} />
          <MenuItem icon="refresh" label={t('Refresh')} onSelect={() => { close(); onRefresh(); }} />
          <MenuItem icon="link" label={t('Copy link')} onSelect={() => {
            close();
            navigator.clipboard.writeText(window.location.href).then(() => toast(t('Link copied')), () => toast(t('Couldn’t copy the link'), 'error'));
          }} />
          <MenuItem icon="external" label={t('Open in Jellyfin')} href={`${JELLYFIN_WEB}#/list.html?parentId=${item.Id}`} />
          {isAdmin() && (
            <>
              <div class="menu-sep" />
              <MenuItem icon="refresh" label={t('Scan this folder')} sub={t('Look for new and changed photos')} onSelect={() => {
                close();
                post<void>(`/Items/${item.Id}/Refresh`, undefined, { Recursive: true, MetadataRefreshMode: 'Default', ImageRefreshMode: 'Default', ReplaceAllMetadata: false, ReplaceAllImages: false })
                  .then(() => toast(t('Scanning {name}', { name: item.Name }), 'pending'), () => toast(t('Couldn’t start the scan'), 'error'));
              }} />
            </>
          )}
        </>
      )}
    </MenuButton>
  );
}
