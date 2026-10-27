import { useState } from 'preact/hooks';
import { JELLYFIN_WEB, get, post, getSession } from '../../api/client';
import { getItems } from '../../api/jellyfin';
import type { BaseItem, ItemsResult } from '../../api/types';
import { meStore } from '../../lib/app-state';
import { t, tn } from '../../lib/i18n';
import { Link, useLocation } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { Header } from '../../ui/navbar/Header';
import { MenuButton, MenuItem, toast } from '../../ui/overlay';
import { useInView } from '../browse/parts';
import { manageChanged } from '../browse/manage/host';
import { genreMix, instantMix, orderByIds, playlistTracks, type Track } from '../../audio/api';
import { audioStore, playContainer, playLoaded, playedStore, recentSources } from '../../audio/engine';
import { AIcon } from '../../audio/icons';
import {
  AlbumCard, ArtistCard, GenreTile, JumpCard, MUSIC_FIELDS, MixCard, NewPlaylistTile, PlaylistCard, SectionHead, albumArtist, albumArtistsCount,
  albumsLabel, countOf, dot, playlistCovers, playlistSub, playsLabel, songsOf, songsLabel, yearOf, type Mix
} from './parts';
import { AlbumsTab, ArtistsTab, FoldersTab, GenresTab, PlaylistsTab, SongsTab } from './tabs';
import './music.css';

/* A music library (MusicHome board): the title with its counts, Shuffle all, Instant mix and ⋯; the Overview (Jump
   back in, Instant mixes for you, Recently added albums, Your playlists, Artists you play most, Genres) and a tab for
   albums, artists, songs, playlists, genres and folders. */

export type MusicTab = 'overview' | 'albums' | 'artists' | 'songs' | 'playlists' | 'genres' | 'folders';
const TABS: { id: MusicTab; label: string }[] = [
  { id: 'overview', label: 'Overview' }, { id: 'albums', label: 'Albums' }, { id: 'artists', label: 'Artists' }, { id: 'songs', label: 'Songs' },
  { id: 'playlists', label: 'Playlists' }, { id: 'genres', label: 'Genres' }, { id: 'folders', label: 'Folders' }
];

const uid = () => getSession()?.userId ?? '';

export function MusicLibrary({ view }: { view: BaseItem }) {
  const loc = useLocation();
  const me = useStore(meStore);
  const asked = loc.query.get('tab') as MusicTab | null;
  const tab: MusicTab = TABS.some((x) => x.id === asked) ? asked! : 'overview';
  const [reload, setReload] = useState(0);
  const counts = useAsync(
    () => Promise.all([countOf(view.Id, 'MusicAlbum'), albumArtistsCount(view.Id), countOf(view.Id, 'Audio')]),
    [view.Id, reload]
  );
  const [busy, setBusy] = useState(false);
  const mix = async () => {
    if (busy) return;
    setBusy(true);
    try {
      // From a favourite song, else the most played one, else anything.
      const seed = (await songsOf({ parentId: view.Id, isFavorite: true, sortBy: ['Random'], limit: 1 }))[0]
        ?? (await songsOf({ parentId: view.Id, filters: ['IsPlayed'], sortBy: ['PlayCount'], sortOrder: 'Descending', limit: 1 }))[0]
        ?? (await songsOf({ parentId: view.Id, sortBy: ['Random'], limit: 1 }))[0];
      if (!seed) { toast(t('Nothing to play here yet'), 'error'); return; }
      const list = await instantMix(seed.Id, 150);
      await playLoaded(list.length ? list : [seed], 0, { source: { kind: 'mix', id: seed.Id, name: t('Instant mix') } });
    } catch {
      toast(t('Couldn’t start the mix'), 'error');
    } finally {
      setBusy(false);
    }
  };
  const c = counts.data;
  return (
    <div class="page mu-page">
      <Header active={{ viewId: view.Id }} />
      <main class="mu-main">
        <div class="mu-head">
          <div class="mu-head-row">
            <div class="mu-title">
              <h1>{view.Name}</h1>
              {c && <span class="mu-count">{dot(albumsLabel(c[0]), tn(c[1], '{n} artist', '{n} artists'), songsLabel(c[2]))}</span>}
            </div>
            <div class="mu-actions">
              <button type="button" class="mu-btn is-light" onClick={() => void playContainer(view.Id, { shuffle: true, source: { kind: 'library', id: view.Id, name: view.Name } })}>
                <AIcon name="shuffle" size={18} />{t('Shuffle all')}
              </button>
              <button type="button" class="mu-btn" disabled={busy} onClick={() => void mix()}>
                <AIcon name="mix" size={18} />{t('Instant mix')}
              </button>
              <MenuButton
                width={260}
                button={({ ref, open, toggle }) => (
                  <button ref={ref} type="button" class="mu-btn is-icon" aria-label={t('Library options')} aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={toggle}>
                    <AIcon name="more" size={20} />
                  </button>
                )}
              >
                {(close) => (
                  <>
                    <MenuItem icon="refresh" label={t('Refresh')} onSelect={() => { close(); setReload((n) => n + 1); }} />
                    <MenuItem icon="link" label={t('Copy link')} onSelect={() => {
                      close();
                      navigator.clipboard.writeText(window.location.href).then(() => toast(t('Link copied')), () => toast(t('Couldn’t copy the link'), 'error'));
                    }} />
                    <MenuItem icon="external" label={t('Open in Jellyfin')} href={`${JELLYFIN_WEB}#/music.html?topParentId=${view.Id}&collectionType=music`} />
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
          <nav aria-label={t('Music views')} class="mu-tabs hscroll">
            {TABS.map((x) => (
              <Link key={x.id} to={`/library/${view.Id}${x.id === 'overview' ? '' : '?tab=' + x.id}`} replace class="mu-tab" aria-current={x.id === tab ? 'page' : undefined}>
                {t(x.label)}
              </Link>
            ))}
          </nav>
        </div>
        {tab === 'overview' ? <Overview key={reload} view={view} />
          : tab === 'albums' ? <AlbumsTab view={view} />
          : tab === 'artists' ? <ArtistsTab view={view} />
          : tab === 'songs' ? <SongsTab view={view} />
          : tab === 'playlists' ? <PlaylistsTab />
          : tab === 'genres' ? <GenresTab view={view} />
          : <FoldersTab view={view} />}
      </main>
    </div>
  );
}

/* ───────── Overview ───────── */

interface TopArtist { artist: BaseItem; plays: number }

/* Artists by how often this profile played their songs. */
async function topArtists(viewId: string): Promise<TopArtist[]> {
  const r = await getItems({
    parentId: viewId, includeItemTypes: ['Audio'], filters: ['IsPlayed'], sortBy: ['PlayCount'], sortOrder: 'Descending', limit: 800,
    fields: ['ParentId'], enableImageTypes: [], enableTotalRecordCount: false
  });
  const plays = new Map<string, number>();
  for (const tk of r.Items as Track[]) {
    const a = tk.AlbumArtists?.[0] ?? tk.ArtistItems?.[0];
    if (!a?.Id) continue;
    plays.set(a.Id, (plays.get(a.Id) ?? 0) + (tk.UserData?.PlayCount ?? 1));
  }
  const ids = [...plays.entries()].sort((a, b) => b[1] - a[1]).slice(0, 7).map(([id]) => id);
  if (!ids.length) return [];
  const artists = await getItems({ ids, fields: MUSIC_FIELDS, enableImageTypes: ['Primary', 'Backdrop'] });
  return orderByIds(artists.Items, ids).map((artist) => ({ artist, plays: plays.get(artist.Id) ?? 0 }));
}

function Overview({ view }: { view: BaseItem }) {
  const top = useAsync(() => topArtists(view.Id).catch(() => [] as TopArtist[]), [view.Id]);
  return (
    <>
      <JumpBackIn view={view} />
      <Mixes view={view} top={top.data} />
      <RecentAlbums view={view} />
      <YourPlaylists view={view} />
      <TopArtists view={view} top={top.data} loading={top.loading} />
      <Genres view={view} />
    </>
  );
}

function JumpBackIn({ view }: { view: BaseItem }) {
  const played = useStore(playedStore);
  const s = useStore(audioStore);
  const data = useAsync(async () => {
    const r = await getItems({
      parentId: view.Id, includeItemTypes: ['Audio'], sortBy: ['DatePlayed'], sortOrder: 'Descending', filters: ['IsPlayed'], limit: 80,
      fields: ['ParentId'], enableImageTypes: [], enableTotalRecordCount: false
    });
    const seen: { id: string; at: number }[] = [];
    for (const tk of r.Items) if (tk.AlbumId) seen.push({ id: tk.AlbumId, at: Date.parse(tk.UserData?.LastPlayedDate ?? '') || 0 });
    for (const x of recentSources()) if (x.kind === 'album' || x.kind === 'playlist' || x.kind === 'artist') seen.push({ id: x.id, at: x.at });
    seen.sort((a, b) => b.at - a.at);
    const ids = [...new Set(seen.map((x) => x.id))].slice(0, 8);
    if (!ids.length) return [];
    const items = await getItems({ ids, fields: [...MUSIC_FIELDS, 'ChildCount'], recursive: true });
    return orderByIds(items.Items, ids);
  }, [view.Id, Math.floor(played.n / 3)]);
  const list = data.data ?? [];
  if (!data.loading && !list.length) return null;
  const sub = (it: BaseItem) =>
    it.Type === 'MusicAlbum' ? dot(t('Album'), albumArtist(it as Track))
      : it.Type === 'Playlist' ? dot(t('Playlist'), songsLabel(it.ChildCount ?? 0))
      : it.Type === 'MusicArtist' ? t('Artist') : '';
  const curId = s.current ? s.source?.id ?? s.current.track.AlbumId : undefined;
  // What's playing now comes first.
  const at = list.findIndex((x) => x.Id === curId);
  const shown = at > 0 ? [list[at], ...list.slice(0, at), ...list.slice(at + 1)] : list;
  return (
    <section aria-labelledby="mu-jump-h" class="mu-sec">
      <SectionHead id="mu-jump-h" title={t('Jump back in')} />
      <div class="mu-jump-grid">
        {data.loading && !data.data
          ? Array.from({ length: 8 }, (_, i) => <span key={i} class="mu-jump skel" />)
          : shown.map((it) => <JumpCard key={it.Id} item={it} sub={sub(it)} current={curId === it.Id ? { playing: s.playing } : undefined} />)}
      </div>
    </section>
  );
}

const distinctCovers = (tracks: Track[], n = 2) => playlistCovers(tracks).slice(0, n);

/* Every mix card shows two covers: a mix from a single album borrows another album's. */
async function padCovers(mixes: Mix[], viewId: string): Promise<Mix[]> {
  if (mixes.every((m) => m.covers.length >= 2)) return mixes;
  const spare = (await getItems({ parentId: viewId, includeItemTypes: ['MusicAlbum'], sortBy: ['Random'], limit: 8, fields: [] }).catch(() => null))?.Items as Track[] ?? [];
  return mixes.map((m, i) => {
    if (m.covers.length >= 2) return m;
    const have = new Set(m.covers.map((c) => c.AlbumId ?? c.Id));
    const extra = spare.filter((a) => !have.has(a.Id)).slice(i % 3);
    return { ...m, covers: [...m.covers, ...extra].slice(0, 2) };
  });
}

async function artistMix(a: BaseItem): Promise<Mix> {
  const tracks = await instantMix(a.Id, 120).catch(() => [] as Track[]);
  const others = new Set(tracks.flatMap((x) => (x.ArtistItems ?? x.AlbumArtists ?? []).map((y) => y.Id)).filter((id) => id !== a.Id)).size;
  return {
    key: 'artist-' + a.Id, seed: a.Id, title: t('{name} radio', { name: a.Name }),
    sub: others ? tn(others, '{name} and {n} artist like them', '{name} and {n} artists like them', { name: a.Name }) : t('{name} and similar artists', { name: a.Name }),
    covers: distinctCovers(tracks), tracks: async () => tracks
  };
}

async function genreMixOf(g: BaseItem): Promise<Mix> {
  const tracks = await genreMix(g.Id, 120).catch(() => [] as Track[]);
  return {
    key: 'genre-' + g.Id, seed: g.Id, title: t('{genre} mix', { genre: g.Name }), sub: t('{genre} from across the server', { genre: g.Name }),
    covers: distinctCovers(tracks), tracks: async () => tracks
  };
}

const getGenres = (viewId: string) =>
  get<ItemsResult>('/MusicGenres', { userId: uid(), parentId: viewId, fields: 'ItemCounts', enableImages: false, sortBy: 'SortName' })
    .then((r) => r.Items.slice().sort((a, b) => ((b as { AlbumCount?: number }).AlbumCount ?? b.ChildCount ?? 0) - ((a as { AlbumCount?: number }).AlbumCount ?? a.ChildCount ?? 0)));

function Mixes({ view, top }: { view: BaseItem; top?: TopArtist[] }) {
  const [more, setMore] = useState(false);
  const mixes = useAsync(async () => {
    if (!top) return null;
    const out: Mix[] = [];
    const lead = top[0]?.artist ?? (await get<ItemsResult>('/Artists/AlbumArtists', { userId: uid(), parentId: view.Id, sortBy: 'Random', limit: 1 })).Items[0];
    if (lead) out.push(await artistMix(lead));
    const favs = await songsOf({ parentId: view.Id, isFavorite: true, sortBy: ['Random'], limit: 40 });
    if (favs.length) {
      out.push({
        key: 'favs', seed: 'favs-' + view.Id, title: t('Your favourites mix'), sub: t('Songs you love, and more like them'), covers: distinctCovers(favs),
        tracks: async () => {
          const like = await instantMix(favs[0].Id, 60).catch(() => [] as Track[]);
          const ids = new Set(favs.map((x) => x.Id));
          const mixed: Track[] = [];
          const rest = like.filter((x) => !ids.has(x.Id));
          favs.forEach((f, i) => { mixed.push(f); if (rest[i]) mixed.push(rest[i]); });
          return mixed;
        }
      });
    } else {
      const g = (await getGenres(view.Id).catch(() => []))[0];
      if (g) out.push(await genreMixOf(g));
    }
    const fresh = await songsOf({ parentId: view.Id, filters: ['IsUnplayed'], sortBy: ['Random'], limit: 120 });
    if (fresh.length) out.push({ key: 'new', seed: 'new-' + view.Id, title: t('Something new'), sub: t('Albums on the server you’ve never played'), covers: distinctCovers(fresh), tracks: async () => fresh });
    return padCovers(out, view.Id);
  }, [view.Id, !!top]);
  const extra = useAsync(async () => {
    if (!more || !top) return [] as Mix[];
    const list: Mix[] = [];
    for (const x of top.slice(1, 3)) list.push(await artistMix(x.artist));
    const genres = (await getGenres(view.Id).catch(() => [])).slice(0, 6 - list.length);
    for (const g of genres) {
      if (list.length >= 6) break;
      list.push(await genreMixOf(g));
    }
    return padCovers(list, view.Id);
  }, [more, view.Id, !!top]);
  const list = [...(mixes.data ?? []), ...(more ? extra.data ?? [] : [])];
  if (mixes.data && !mixes.data.length) return null;
  return (
    <section aria-labelledby="mu-mix-h" class="mu-sec">
      <SectionHead id="mu-mix-h" title={t('Instant mixes for you')} link={more ? { onClick: () => setMore(false), label: t('Fewer mixes') } : { onClick: () => setMore(true), label: t('More mixes') }} />
      <div class="mu-mix-grid">
        {!mixes.data ? [0, 1, 2].map((i) => <span key={i} class="mu-mix skel" />) : list.map((m) => <MixCard key={m.key} mix={m} />)}
        {more && extra.loading && [0, 1, 2].map((i) => <span key={'x' + i} class="mu-mix skel" />)}
      </div>
    </section>
  );
}

function RecentAlbums({ view }: { view: BaseItem }) {
  const data = useAsync(() => getItems({ parentId: view.Id, includeItemTypes: ['MusicAlbum'], sortBy: ['DateCreated', 'SortName'], sortOrder: 'Descending', limit: 7, fields: MUSIC_FIELDS }).then((r) => r.Items as Track[]), [view.Id]);
  if (data.data && !data.data.length) return null;
  return (
    <section aria-labelledby="mu-new-h" class="mu-sec">
      <SectionHead id="mu-new-h" title={t('Recently added albums')} link={{ to: `/library/${view.Id}?tab=albums&sort=DateCreated`, label: t('See all') }} />
      <div class="mu-grid is-7">
        {!data.data ? Array.from({ length: 7 }, (_, i) => <CardSkel key={i} />) : data.data.map((a) => <AlbumCard key={a.Id} album={a} sub={dot(albumArtist(a), yearOf(a))} />)}
      </div>
    </section>
  );
}

export function CardSkel({ round }: { round?: boolean }) {
  return (
    <div class="mu-card" aria-hidden="true">
      <span class={'skel mu-skel-art' + (round ? ' is-round' : '')} />
      <span class="skel skel-line" style={{ width: '70%' }} />
    </div>
  );
}

export interface PlaylistPreview { playlist: BaseItem; covers: Track[]; sub: string }

export async function playlistPreview(pl: BaseItem): Promise<PlaylistPreview> {
  const tracks = await playlistTracks(pl.Id).catch(() => [] as Track[]);
  const secs = tracks.reduce((s, x) => s + (x.RunTimeTicks ?? 0), 0);
  return { playlist: pl, covers: playlistCovers(tracks), sub: playlistSub({ ...pl, CumulativeRunTimeTicks: secs || undefined } as BaseItem, tracks.length || pl.ChildCount) };
}

export const audioPlaylists = (limit?: number) =>
  getItems({ includeItemTypes: ['Playlist'], recursive: true, mediaTypes: ['Audio'], sortBy: ['DateCreated', 'SortName'], sortOrder: 'Descending', limit, fields: ['ChildCount', 'DateCreated'] });

function YourPlaylists({ view }: { view: BaseItem }) {
  const [ref, seen] = useInView<HTMLElement>('600px');
  const managed = useStore(manageChanged);
  const data = useAsync(async () => {
    if (!seen) return null;
    const r = await audioPlaylists(5);
    return Promise.all(r.Items.map(playlistPreview));
  }, [seen, managed.n]);
  return (
    <section ref={ref} aria-labelledby="mu-pl-h" class="mu-sec">
      <SectionHead id="mu-pl-h" title={t('Your playlists')} link={{ to: `/library/${view.Id}?tab=playlists`, label: t('All playlists') }} />
      <div class="mu-grid is-6">
        <NewPlaylistTile />
        {!data.data ? Array.from({ length: 5 }, (_, i) => <CardSkel key={i} />) : data.data.map((p) => <PlaylistCard key={p.playlist.Id} playlist={p.playlist} covers={p.covers} sub={p.sub} />)}
      </div>
    </section>
  );
}

function TopArtists({ view, top, loading }: { view: BaseItem; top?: TopArtist[]; loading: boolean }) {
  const fallback = useAsync(
    () => (top && !top.length
      ? get<ItemsResult>('/Artists/AlbumArtists', { userId: uid(), parentId: view.Id, sortBy: 'SortName', limit: 7, fields: 'ItemCounts,ChildCount', enableImageTypes: 'Primary', imageTypeLimit: 1 }).then((r) => r.Items)
      : Promise.resolve(null)),
    [view.Id, !!top && !top.length]
  );
  const played = !!top?.length;
  const list = played ? top!.map((x) => ({ artist: x.artist, sub: playsLabel(x.plays) })) : (fallback.data ?? []).map((a) => ({ artist: a, sub: artistAlbums(a) }));
  if (!loading && top && !top.length && fallback.data && !fallback.data.length) return null;
  return (
    <section aria-labelledby="mu-ar-h" class="mu-sec">
      <SectionHead id="mu-ar-h" title={played || loading ? t('Artists you play most') : t('Artists')} link={{ to: `/library/${view.Id}?tab=artists`, label: t('See all') }} />
      <div class="mu-grid is-7">
        {loading && !top ? Array.from({ length: 7 }, (_, i) => <CardSkel key={i} round />) : list.map((x) => <ArtistCard key={x.artist.Id} artist={x.artist} sub={x.sub} />)}
      </div>
    </section>
  );
}

export const artistAlbums = (a: BaseItem) => {
  const n = (a as BaseItem & { AlbumCount?: number }).AlbumCount ?? a.ChildCount;
  return n ? tn(n, '{n} album', '{n} albums') : '';
};

function Genres({ view }: { view: BaseItem }) {
  const [ref, seen] = useInView<HTMLElement>('600px');
  const data = useAsync(async () => {
    if (!seen) return null;
    const genres = (await getGenres(view.Id)).slice(0, 6);
    const used = new Set<string>();
    const samples = await Promise.all(genres.map((g) =>
      getItems({ parentId: view.Id, includeItemTypes: ['MusicAlbum'], genreIds: [g.Id], sortBy: ['DateCreated'], sortOrder: 'Descending', limit: 6, fields: [], enableTotalRecordCount: false })
        .then((r) => r.Items as Track[], () => [] as Track[])
    ));
    return genres.map((g, i) => {
      const pick = samples[i].find((x) => !used.has(x.Id)) ?? samples[i][0] ?? null;
      if (pick) used.add(pick.Id);
      return { genre: g, sample: pick };
    });
  }, [seen, view.Id]);
  if (data.data && !data.data.length) return null;
  return (
    <section ref={ref} aria-labelledby="mu-ge-h" class="mu-sec">
      <SectionHead id="mu-ge-h" title={t('Genres')} link={{ to: `/library/${view.Id}?tab=genres`, label: t('All genres') }} />
      <div class="mu-genre-grid">
        {!data.data ? Array.from({ length: 6 }, (_, i) => <span key={i} class="mu-genre skel" />)
          : data.data.map((x, i) => <GenreTile key={x.genre.Id} genre={x.genre} index={i} sample={x.sample} to={`/library/${view.Id}?tab=genres&genre=${x.genre.Id}`} />)}
      </div>
    </section>
  );
}

