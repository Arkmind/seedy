import { useEffect, useMemo } from 'preact/hooks';
import { get, getSession } from '../../api/client';
import { getItem, getItems } from '../../api/jellyfin';
import type { BaseItem, ItemsResult } from '../../api/types';
import { t, tn } from '../../lib/i18n';
import { Link, setQuery, useLocation } from '../../lib/router';
import { useAsync } from '../../lib/store';
import { MenuButton, MenuItem } from '../../ui/overlay';
import { Icon } from '../../ui/icons';
import { EmptyState, ErrorState, Sentinel, usePaged, useInView } from '../browse/parts';
import { TRACK_FIELDS, genreMix, type Track } from '../../audio/api';
import { playContainer, playLoaded } from '../../audio/engine';
import { AIcon } from '../../audio/icons';
import { Cover } from '../../audio/cover';
import {
  AlbumCard, ArtistCard, GenreTile, MUSIC_FIELDS, NewPlaylistTile, PlaylistCard, TrackTable, TracksSkeleton, albumArtist, dot, songsLabel, yearOf
} from './parts';
import { CardSkel, artistAlbums, audioPlaylists, playlistPreview, type PlaylistPreview } from './MusicLibrary';

/* The music library's tabs (built in the MusicHome board's language): albums, artists and songs with sorting and a
   favourites filter, playlists, genres (each opening its albums) and the library's folders. */

const uid = () => getSession()?.userId ?? '';

interface SortOpt { id: string; label: string; by: string[]; order: 'Ascending' | 'Descending' }

function useSort(opts: SortOpt[], fallback: string): SortOpt {
  const loc = useLocation();
  const id = loc.query.get('sort') ?? fallback;
  return opts.find((o) => o.id === id) ?? opts.find((o) => o.id === fallback) ?? opts[0];
}

function Toolbar({ count, sorts, sort, favs }: { count?: string; sorts?: SortOpt[]; sort?: SortOpt; favs?: boolean }) {
  const loc = useLocation();
  const favOn = loc.query.get('fav') === '1';
  return (
    <div class="mu-toolbar">
      <span class="mu-toolbar-count">{count}</span>
      <div class="mu-toolbar-tools">
        {favs && (
          <button type="button" class={'btn btn-secondary mu-fav-filter' + (favOn ? ' is-on' : '')} aria-pressed={favOn ? 'true' : 'false'} onClick={() => setQuery({ fav: favOn ? undefined : '1' })}>
            <AIcon name="heart" size={17} filled={favOn} />{t('Favorites')}
          </button>
        )}
        {sorts && sort && (
          <MenuButton
            width={240}
            button={({ ref, open, toggle }) => (
              <button ref={ref} type="button" class="btn btn-secondary lib-sort" aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={toggle}>
                <Icon name="sort" size={18} />
                <span class="lib-sort-k">{t('Sort')}</span> {t(sort.label)}
                <Icon name="chevronDown" size={16} stroke={2.2} />
              </button>
            )}
          >
            {(close) => (
              <>
                <span class="menu-label">{t('Sort by')}</span>
                {sorts.map((o) => <MenuItem key={o.id} label={t(o.label)} pressed={o.id === sort.id} onSelect={() => { close(); setQuery({ sort: o.id }); }} />)}
              </>
            )}
          </MenuButton>
        )}
      </div>
    </div>
  );
}

function More({ paged }: { paged: ReturnType<typeof usePaged> }) {
  return (
    <>
      {!paged.done && <Sentinel onVisible={paged.loadMore} token={paged.items.length} disabled={paged.loading || !!paged.error} />}
      {paged.loading && paged.items.length > 0 && <div class="lib-loading"><span class="spinner" aria-label={t('Loading more')} /></div>}
      {paged.error && paged.items.length > 0 && (
        <div class="grid-more"><button type="button" class="btn btn-secondary" onClick={paged.loadMore}><Icon name="refresh" size={18} />{t('Load more')}</button></div>
      )}
    </>
  );
}

/* ───────── Albums ───────── */

const ALBUM_SORTS: SortOpt[] = [
  { id: 'DateCreated', label: 'Date added', by: ['DateCreated', 'SortName'], order: 'Descending' },
  { id: 'SortName', label: 'Title', by: ['SortName'], order: 'Ascending' },
  { id: 'Artist', label: 'Artist', by: ['AlbumArtist', 'ProductionYear', 'SortName'], order: 'Ascending' },
  { id: 'Year', label: 'Release date', by: ['ProductionYear', 'PremiereDate', 'SortName'], order: 'Descending' },
  { id: 'DatePlayed', label: 'Recently played', by: ['DatePlayed', 'SortName'], order: 'Descending' },
  { id: 'Random', label: 'Random', by: ['Random'], order: 'Ascending' }
];

export function AlbumsTab({ view, genre }: { view: BaseItem; genre?: BaseItem }) {
  const loc = useLocation();
  const sort = useSort(ALBUM_SORTS, 'DateCreated');
  const fav = loc.query.get('fav') === '1';
  const paged = usePaged((start, limit, signal) => getItems({
    parentId: view.Id, includeItemTypes: ['MusicAlbum'], sortBy: sort.by, sortOrder: sort.order, startIndex: start, limit,
    fields: MUSIC_FIELDS, isFavorite: fav || undefined, genreIds: genre ? [genre.Id] : undefined
  }, signal), [view.Id, sort.id, fav, genre?.Id], 70);
  return (
    <section class="mu-sec" aria-label={t('Albums')}>
      <Toolbar count={paged.items.length || !paged.loading ? tn(paged.total, '{n} album', '{n} albums') : undefined} sorts={ALBUM_SORTS} sort={sort} favs />
      {paged.error && !paged.items.length ? <ErrorState error={paged.error} retry={paged.reload} />
        : paged.loading && !paged.items.length ? <div class="mu-grid is-fill">{Array.from({ length: 14 }, (_, i) => <CardSkel key={i} />)}</div>
        : !paged.items.length ? <EmptyState icon="music" title={fav ? t('No favourite albums yet') : t('No albums yet')} text={fav ? t('Tap the heart on an album and it shows up here.') : t('Albums show up here once the server finds them.')} />
        : <div class="mu-grid is-fill">{(paged.items as Track[]).map((a) => <AlbumCard key={a.Id} album={a} sub={dot(albumArtist(a), yearOf(a))} />)}</div>}
      <More paged={paged} />
    </section>
  );
}

/* ───────── Artists ───────── */

const ARTIST_SORTS: SortOpt[] = [
  { id: 'SortName', label: 'Name', by: ['SortName'], order: 'Ascending' },
  { id: 'DateCreated', label: 'Date added', by: ['DateCreated', 'SortName'], order: 'Descending' },
  { id: 'Random', label: 'Random', by: ['Random'], order: 'Ascending' }
];

export function ArtistsTab({ view }: { view: BaseItem }) {
  const loc = useLocation();
  const sort = useSort(ARTIST_SORTS, 'SortName');
  const fav = loc.query.get('fav') === '1';
  const paged = usePaged((start, limit, signal) => get<ItemsResult>('/Artists/AlbumArtists', {
    userId: uid(), parentId: view.Id, startIndex: start, limit, sortBy: sort.by.join(','), sortOrder: sort.order, isFavorite: fav || undefined,
    fields: 'ItemCounts,ChildCount,PrimaryImageAspectRatio', enableImageTypes: 'Primary,Backdrop', imageTypeLimit: 1, enableTotalRecordCount: true
  }, signal), [view.Id, sort.id, fav], 70);
  return (
    <section class="mu-sec" aria-label={t('Artists')}>
      <Toolbar count={paged.items.length || !paged.loading ? tn(paged.total, '{n} artist', '{n} artists') : undefined} sorts={ARTIST_SORTS} sort={sort} favs />
      {paged.error && !paged.items.length ? <ErrorState error={paged.error} retry={paged.reload} />
        : paged.loading && !paged.items.length ? <div class="mu-grid is-fill">{Array.from({ length: 14 }, (_, i) => <CardSkel key={i} round />)}</div>
        : !paged.items.length ? <EmptyState icon="music" title={fav ? t('No favourite artists yet') : t('No artists yet')} text={t('Artists show up here once the server finds their albums.')} />
        : <div class="mu-grid is-fill is-artists">{paged.items.map((a) => <ArtistCard key={a.Id} artist={a} sub={artistAlbums(a)} />)}</div>}
      <More paged={paged} />
    </section>
  );
}

/* ───────── Songs ───────── */

const SONG_SORTS: SortOpt[] = [
  { id: 'SortName', label: 'Title', by: ['SortName'], order: 'Ascending' },
  { id: 'Artist', label: 'Artist', by: ['AlbumArtist', 'Album', 'ParentIndexNumber', 'IndexNumber'], order: 'Ascending' },
  { id: 'Album', label: 'Album', by: ['Album', 'ParentIndexNumber', 'IndexNumber'], order: 'Ascending' },
  { id: 'DateCreated', label: 'Date added', by: ['DateCreated', 'SortName'], order: 'Descending' },
  { id: 'PlayCount', label: 'Most played', by: ['PlayCount', 'SortName'], order: 'Descending' },
  { id: 'DatePlayed', label: 'Recently played', by: ['DatePlayed', 'SortName'], order: 'Descending' },
  { id: 'Random', label: 'Random', by: ['Random'], order: 'Ascending' }
];

export function SongsTab({ view }: { view: BaseItem }) {
  const loc = useLocation();
  const sort = useSort(SONG_SORTS, 'SortName');
  const fav = loc.query.get('fav') === '1';
  const paged = usePaged((start, limit, signal) => getItems({
    parentId: view.Id, includeItemTypes: ['Audio'], sortBy: sort.by, sortOrder: sort.order, startIndex: start, limit, fields: TRACK_FIELDS,
    isFavorite: fav || undefined
  }, signal), [view.Id, sort.id, fav], 100);
  return (
    <section class="mu-sec" aria-label={t('Songs')}>
      <Toolbar count={paged.items.length || !paged.loading ? songsLabel(paged.total) : undefined} sorts={SONG_SORTS} sort={sort} favs />
      {paged.error && !paged.items.length ? <ErrorState error={paged.error} retry={paged.reload} />
        : paged.loading && !paged.items.length ? <TracksSkeleton rows={12} />
        : !paged.items.length ? <EmptyState icon="music" title={fav ? t('No favourite songs yet') : t('No songs yet')} text={fav ? t('Tap the heart on a song and it shows up here.') : t('Songs show up here once the server finds them.')} />
        : <TrackTable tracks={paged.items as Track[]} cols="list" source={{ kind: 'library', id: view.Id, name: view.Name }} />}
      <More paged={paged} />
    </section>
  );
}

/* ───────── Playlists ───────── */

export function PlaylistsTab() {
  const data = useAsync(async () => {
    const r = await audioPlaylists();
    return Promise.all(r.Items.map(playlistPreview));
  }, []);
  return (
    <section class="mu-sec" aria-label={t('Playlists')}>
      <Toolbar count={data.data ? tn(data.data.length, '{n} playlist', '{n} playlists') : undefined} />
      {data.error && !data.data ? <ErrorState error={data.error} retry={data.reload} /> : (
        <div class="mu-grid is-6">
          <NewPlaylistTile />
          {!data.data ? Array.from({ length: 5 }, (_, i) => <CardSkel key={i} />) : data.data.map((p: PlaylistPreview) => <PlaylistCard key={p.playlist.Id} playlist={p.playlist} covers={p.covers} sub={p.sub} />)}
        </div>
      )}
    </section>
  );
}

/* ───────── Genres ───────── */

export function GenresTab({ view }: { view: BaseItem }) {
  const loc = useLocation();
  const genreId = loc.query.get('genre');
  if (genreId) return <GenrePage view={view} id={genreId} />;
  return <GenreGrid view={view} />;
}

function GenreGrid({ view }: { view: BaseItem }) {
  const data = useAsync(() =>
    get<ItemsResult>('/MusicGenres', { userId: uid(), parentId: view.Id, fields: 'ItemCounts', enableImages: false, sortBy: 'SortName' }).then((r) => r.Items),
  [view.Id]);
  return (
    <section class="mu-sec" aria-label={t('Genres')}>
      <Toolbar count={data.data ? tn(data.data.length, '{n} genre', '{n} genres') : undefined} />
      {data.error && !data.data ? <ErrorState error={data.error} retry={data.reload} />
        : !data.data ? <div class="mu-genre-grid">{Array.from({ length: 12 }, (_, i) => <span key={i} class="mu-genre skel" />)}</div>
        : !data.data.length ? <EmptyState icon="music" title={t('No genres yet')} text={t('Genres come from each album’s tags.')} />
        : <div class="mu-genre-grid">{data.data.map((g, i) => <LazyGenre key={g.Id} view={view} genre={g} index={i} />)}</div>}
    </section>
  );
}

function LazyGenre({ view, genre, index }: { view: BaseItem; genre: BaseItem; index: number }) {
  const [ref, seen] = useInView<HTMLDivElement>('400px');
  const sample = useAsync(() => (seen
    ? getItems({ parentId: view.Id, includeItemTypes: ['MusicAlbum'], genreIds: [genre.Id], sortBy: ['DateCreated'], sortOrder: 'Descending', limit: 1, fields: [], enableTotalRecordCount: false }).then((r) => (r.Items[0] as Track) ?? null)
    : Promise.resolve(undefined)), [seen, genre.Id]);
  return <div ref={ref} class="mu-genre-slot"><GenreTile genre={genre} index={index} sample={sample.data} to={`/library/${view.Id}?tab=genres&genre=${genre.Id}`} /></div>;
}

function GenrePage({ view, id }: { view: BaseItem; id: string }) {
  const genre = useAsync(() => getItem(id, ['ItemCounts']), [id]);
  const g = genre.data;
  return (
    <>
      <div class="mu-genre-head">
        <Link to={`/library/${view.Id}?tab=genres`} class="mu-back"><Icon name="chevronLeft" size={16} stroke={2.4} />{t('All genres')}</Link>
        <div class="mu-genre-title-row">
          <h2 class="mu-genre-title">{g?.Name ?? ' '}</h2>
          <div class="mu-actions">
            <button type="button" class="mu-btn is-light" disabled={!g} onClick={() => g && void playContainer(g.Id, { source: { kind: 'genre', id: g.Id, name: g.Name } })}>
              <AIcon name="play" size={18} />{t('Play')}
            </button>
            <button type="button" class="mu-btn" disabled={!g} onClick={() => g && genreMix(g.Id, 150).then((list) => playLoaded(list, 0, { source: { kind: 'mix', id: g.Id, name: t('{genre} mix', { genre: g.Name }) } }))}>
              <AIcon name="mix" size={18} />{t('Instant mix')}
            </button>
          </div>
        </div>
      </div>
      {g && <AlbumsTab view={view} genre={g} />}
    </>
  );
}

/* ───────── Folders ───────── */

export function FoldersTab({ view }: { view: BaseItem }) {
  const loc = useLocation();
  const folderId = loc.query.get('folder') ?? view.Id;
  const folder = useAsync(() => (folderId === view.Id ? Promise.resolve(view) : getItem(folderId, ['ChildCount'])), [folderId]);
  const trail = useAsync(async () => {
    // The folders above this one, up to the library.
    const list: BaseItem[] = [];
    let cur = folder.data;
    for (let i = 0; cur && cur.Id !== view.Id && cur.ParentId && i < 12; i++) {
      list.unshift(cur);
      if (cur.ParentId === view.Id) break;
      cur = await getItem(cur.ParentId, []).catch(() => undefined);
      if (cur && (cur.Type === 'CollectionFolder' || cur.Type === 'UserView')) break;
    }
    return list;
  }, [folder.data?.Id]);
  const kids = useAsync(() => getItems({
    parentId: folderId, recursive: false, sortBy: ['IsFolder', 'SortName'], sortOrder: 'Ascending', limit: 2000, fields: [...TRACK_FIELDS, 'ChildCount']
  }), [folderId]);
  const items = kids.data?.Items ?? [];
  const folders = items.filter((x) => x.IsFolder);
  const songs = useMemo(() => items.filter((x) => !x.IsFolder && x.MediaType === 'Audio') as Track[], [kids.data]);
  useEffect(() => { window.scrollTo(0, 0); }, [folderId]);
  return (
    <section class="mu-sec" aria-label={t('Folders')}>
      <nav aria-label={t('Folder path')} class="mu-crumbs">
        <Link to={`/library/${view.Id}?tab=folders`} class="mu-crumb">{view.Name}</Link>
        {(trail.data ?? []).map((f) => (
          <span key={f.Id} class="mu-crumb-wrap"><Icon name="chevronRight" size={14} stroke={2.4} /><Link to={`/library/${view.Id}?tab=folders&folder=${f.Id}`} class="mu-crumb">{f.Name}</Link></span>
        ))}
      </nav>
      {kids.error && !kids.data ? <ErrorState error={kids.error} retry={kids.reload} />
        : kids.loading && !kids.data ? <TracksSkeleton rows={8} />
        : !items.length ? <EmptyState icon="folder" title={t('This folder is empty')} text={t('Nothing the server can play is in here.')} />
        : (
          <>
            {folders.length > 0 && (
              <div class="mu-folders">
                {folders.map((f) => {
                  const album = f.Type === 'MusicAlbum';
                  return (
                    <Link key={f.Id} to={`/library/${view.Id}?tab=folders&folder=${f.Id}`} class="mu-folder">
                      {album || f.ImageTags?.Primary ? <Cover item={f} width={96} class="mu-folder-cover" /> : <span class="mu-folder-icon"><AIcon name="folder" size={22} /></span>}
                      <span class="mu-folder-text">
                        <span class="mu-folder-name">{f.Name}</span>
                        <span class="mu-folder-sub">{dot(album ? t('Album') : t('Folder'), f.ChildCount ? tn(f.ChildCount, '{n} item', '{n} items') : '')}</span>
                      </span>
                      <Icon name="chevronRight" size={18} stroke={2.2} />
                    </Link>
                  );
                })}
              </div>
            )}
            {songs.length > 0 && (
              <>
                <div class="mu-folder-play">
                  <button type="button" class="mu-btn is-light" onClick={() => void playLoaded(songs, 0, { source: { kind: 'folder', id: folderId, name: folder.data?.Name ?? view.Name } })}>
                    <AIcon name="play" size={18} />{t('Play')}
                  </button>
                  <button type="button" class="mu-btn" onClick={() => void playLoaded(songs, 0, { shuffle: true, source: { kind: 'folder', id: folderId, name: folder.data?.Name ?? view.Name } })}>
                    <AIcon name="shuffle" size={18} />{t('Shuffle')}
                  </button>
                </div>
                <TrackTable tracks={songs} cols="list" source={{ kind: 'folder', id: folderId, name: folder.data?.Name ?? view.Name }} />
              </>
            )}
          </>
        )}
    </section>
  );
}

