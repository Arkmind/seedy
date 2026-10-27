import type { ComponentChildren, JSX, RefObject } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { get } from '../../api/client';
import { getItems } from '../../api/jellyfin';
import type { BaseItem, ItemsResult } from '../../api/types';
import { locale, t, tn } from '../../lib/i18n';
import { Link, navigate } from '../../lib/router';
import { useStore } from '../../lib/store';
import { Popover, toast } from '../../ui/overlay';
import { TRACK_FIELDS, instantMix, type Track } from '../../audio/api';
import { Cover, initialsOf, useNearestPalette } from '../../audio/cover';
import {
  addToQueue, audioStore, favoriteStore, playItem, playLoaded, toggleTrackFavorite, togglePlay, type PlayOptions
} from '../../audio/engine';
import { AIcon, EqIcon } from '../../audio/icons';
import { artistsOf, clock } from '../../audio/parts';
import { PlaylistPicker } from '../browse/manage/menu';
import { addToPlaylist } from '../browse/manage/actions';
import { openManage } from '../browse/manage/host';
import { getSession } from '../../api/client';

/* Pieces shared by the music library, album and artist pages (Music boards). */

const uid = () => getSession()?.userId ?? '';

export const MUSIC_FIELDS = ['PrimaryImageAspectRatio', 'ProductionYear', 'PremiereDate', 'Genres', 'ChildCount', 'DateCreated', 'SortName', 'AlbumArtist'];

/* ───────── Words ───────── */

export const playsLabel = (n?: number) => (n ? tn(n, '{n} play', '{n} plays') : '');
export const songsLabel = (n: number) => tn(n, '{n} song', '{n} songs');
export const albumsLabel = (n: number) => tn(n, '{n} album', '{n} albums');

/* Jellyfin doesn't say whether a release is an album, an EP or a single: a short one is a single, a mid-length one an EP. */
export type Release = 'album' | 'ep' | 'single';
export function releaseOf(album: Pick<BaseItem, 'ChildCount' | 'RunTimeTicks' | 'Tags'>): Release {
  const tags = (album.Tags ?? []).map((x) => x.toLowerCase());
  if (tags.includes('single')) return 'single';
  if (tags.includes('ep')) return 'ep';
  const n = album.ChildCount ?? 0;
  const min = (album.RunTimeTicks ?? 0) / 600_000_000;
  if (n > 0 && n <= 3 && (!min || min < 20)) return 'single';
  if (n > 0 && n <= 6 && (!min || min < 32)) return 'ep';
  return 'album';
}
export const releaseLabel = (r: Release | 'appears') =>
  r === 'single' ? t('Single') : r === 'ep' ? t('EP') : r === 'appears' ? t('Appears on') : t('Album');

export const albumArtist = (a: Pick<Track, 'AlbumArtist' | 'AlbumArtists' | 'Artists'>) => a.AlbumArtist || a.AlbumArtists?.[0]?.Name || a.Artists?.[0] || '';
export const yearOf = (a: Pick<BaseItem, 'ProductionYear' | 'PremiereDate'>) => (a.ProductionYear ? String(a.ProductionYear) : a.PremiereDate ? a.PremiereDate.slice(0, 4) : '');
/* "12 March 2024", "12 mars 2024". */
export function longDate(d: Date): string {
  try { return new Intl.DateTimeFormat(locale(), { day: 'numeric', month: 'long', year: 'numeric' }).format(d); } catch { return d.toDateString(); }
}

export const dot = (...parts: (string | number | undefined | null | false)[]) => parts.filter((p) => p !== undefined && p !== null && p !== false && p !== '').join(' · ');

/* A word that means something else elsewhere ("Time" of day): its dictionary key says which, English shows [en]. */
export function inContext(key: string, en: string): string {
  const v = t(key);
  return v === key ? en : v;
}

/* ───────── Section heads ───────── */

export function SectionHead({ id, title, link, sub }: { id: string; title: ComponentChildren; link?: { to: string; label: string } | { onClick: () => void; label: string }; sub?: ComponentChildren }) {
  return (
    <div class="mu-sec-head">
      <h2 id={id} class="mu-sec-title">{title}</h2>
      {sub}
      {link && ('to' in link
        ? <Link to={link.to} class="mu-sec-link">{link.label}</Link>
        : <button type="button" class="mu-sec-link" onClick={link.onClick}>{link.label}</button>)}
    </div>
  );
}

/* ───────── Dragging songs onto playlists ───────── */

const DRAG_TYPE = 'application/x-seedy-items';

export function dragItems(e: DragEvent, items: Pick<BaseItem, 'Id' | 'Name'>[]) {
  if (!e.dataTransfer || !items.length) return;
  e.dataTransfer.effectAllowed = 'copy';
  e.dataTransfer.setData(DRAG_TYPE, JSON.stringify(items.map((i) => i.Id)));
  e.dataTransfer.setData('text/plain', items.map((i) => i.Name).join('\n'));
}

/* A playlist card that takes songs or albums dropped on it. */
function useDrop(playlist: Pick<BaseItem, 'Id' | 'Name'>): [boolean, JSX.HTMLAttributes<HTMLDivElement>] {
  const [over, setOver] = useState(false);
  const depth = useRef(0);
  const ours = (e: DragEvent) => !!e.dataTransfer && Array.from(e.dataTransfer.types).includes(DRAG_TYPE);
  return [over, {
    onDragEnter: (e) => { if (!ours(e as DragEvent)) return; e.preventDefault(); depth.current++; setOver(true); },
    onDragOver: (e) => { if (!ours(e as DragEvent)) return; e.preventDefault(); (e as DragEvent).dataTransfer!.dropEffect = 'copy'; },
    onDragLeave: () => { depth.current = Math.max(0, depth.current - 1); if (!depth.current) setOver(false); },
    onDrop: (e) => {
      const d = (e as DragEvent).dataTransfer;
      if (!d || !ours(e as DragEvent)) return;
      e.preventDefault();
      depth.current = 0;
      setOver(false);
      let ids: string[] = [];
      try { ids = JSON.parse(d.getData(DRAG_TYPE)) as string[]; } catch { return; }
      if (!ids.length) return;
      addToPlaylist(playlist.Id, ids).then(
        () => toast(t('Added to {name}', { name: playlist.Name })),
        () => toast(t('Couldn’t add to {name}', { name: playlist.Name }), 'error')
      );
    }
  }];
}

/* ───────── Cards ───────── */

/* Plays (or pauses) an album, playlist or artist from its card. */
function CardPlay({ item, label }: { item: Pick<Track, 'Id' | 'Type' | 'Name'>; label?: string }) {
  const s = useStore(audioStore);
  const here = s.source?.id === item.Id && !!s.current;
  const playing = here && s.playing;
  return (
    <button type="button" class={'mu-card-play' + (here ? ' is-here' : '')} aria-label={playing ? t('Pause {name}', { name: item.Name }) : label ?? t('Play {name}', { name: item.Name })}
      onClick={(e) => { e.preventDefault(); e.stopPropagation(); if (here) togglePlay(); else void playItem(item as Track); }}>
      <AIcon name={playing ? 'pause' : 'play'} size={20} />
    </button>
  );
}

export function AlbumCard({ album, sub, draggable = true }: { album: Track; sub?: string; draggable?: boolean }) {
  const to = `/details/${album.Id}`;
  return (
    <div class="mu-card" draggable={draggable} onDragStart={(e) => dragItems(e as DragEvent, [album])}>
      <span class="mu-card-art">
        <Link to={to} class="mu-card-link" aria-label={album.Name} draggable={false}>
          <Cover item={album} width={360} title={album.Name} class="mu-card-cover" />
        </Link>
        <CardPlay item={album} />
      </span>
      <Link to={to} class="mu-card-text" tabIndex={-1} draggable={false}>
        <span class="mu-card-title">{album.Name}</span>
        {sub !== undefined ? sub && <span class="mu-card-sub">{sub}</span> : <span class="mu-card-sub">{dot(albumArtist(album), yearOf(album))}</span>}
      </Link>
    </div>
  );
}

export function ArtistCard({ artist, sub, initialsSize }: { artist: BaseItem; sub?: string; initialsSize?: number }) {
  const to = `/details/${artist.Id}`;
  return (
    <div class="mu-artist">
      <span class="mu-card-art is-round">
        <Link to={to} class="mu-card-link" aria-label={artist.Name}>
          <Cover item={artist} width={360} initials={initialsOf(artist.Name)} class="mu-artist-cover" style={initialsSize ? { ['--mu-initials' as string]: initialsSize + 'px' } : undefined} />
        </Link>
        <CardPlay item={artist} label={t('Play {name}', { name: artist.Name })} />
      </span>
      <Link to={to} class="mu-artist-text" tabIndex={-1}>
        <span class="mu-card-title">{artist.Name}</span>
        {sub && <span class="mu-card-sub">{sub}</span>}
      </Link>
    </div>
  );
}

/* Four covers from the playlist's songs (fewer covers repeat). */
export function playlistCovers(tracks: Track[]): Track[] {
  const seen = new Set<string>();
  const out: Track[] = [];
  for (const tk of tracks) {
    const k = tk.AlbumId ?? tk.Id;
    if (seen.has(k)) continue;
    seen.add(k);
    out.push(tk);
    if (out.length === 4) break;
  }
  return out;
}

export function PlaylistCard({ playlist, covers, sub }: { playlist: BaseItem; covers: Track[]; sub?: string }) {
  const [over, drop] = useDrop(playlist);
  const to = `/details/${playlist.Id}`;
  const four = covers.length >= 4 ? covers.slice(0, 4) : covers.length ? [0, 1, 2, 3].map((i) => covers[i % covers.length]) : [];
  return (
    <div class={'mu-card mu-pl' + (over ? ' is-drop' : '')} {...drop}>
      <span class="mu-card-art">
        <Link to={to} class="mu-card-link" aria-label={playlist.Name}>
          {four.length && !playlist.ImageTags?.Primary ? (
            <span class="mu-pl-grid">{four.map((c, i) => <Cover key={i} item={c} width={180} class="mu-pl-cell" />)}</span>
          ) : <Cover item={playlist} width={360} title={playlist.Name} class="mu-card-cover" />}
          {over && <span class="mu-pl-drop"><AIcon name="plus" size={22} stroke={2.6} />{t('Add to {name}', { name: playlist.Name })}</span>}
        </Link>
        <CardPlay item={playlist} />
      </span>
      <Link to={to} class="mu-card-text" tabIndex={-1}>
        <span class="mu-card-title">{playlist.Name}</span>
        {sub && <span class="mu-card-sub">{sub}</span>}
      </Link>
    </div>
  );
}

export function NewPlaylistTile({ hint = true }: { hint?: boolean }) {
  return (
    <div class="mu-card">
      <button type="button" class="mu-new-pl" onClick={() => openManage('playlist', [])}>
        <span aria-hidden="true" class="mu-new-pl-plus">+</span>
        {t('New playlist')}
      </button>
      {hint && <span class="mu-new-pl-hint">{t('Or drag songs onto a playlist')}</span>}
    </div>
  );
}

/* "48 songs · 3 h 12 min" */
export function playlistSub(pl: BaseItem & { CumulativeRunTimeTicks?: number }, count?: number): string {
  const n = count ?? pl.ChildCount ?? 0;
  const secs = (pl.CumulativeRunTimeTicks ?? pl.RunTimeTicks ?? 0) / 10_000_000;
  return dot(songsLabel(n), secs ? lengthLabel(secs) : '');
}

/* "3 h 12 min", "14 h", "50 min". */
export function lengthLabel(seconds: number): string {
  const min = Math.max(1, Math.round(seconds / 60));
  const h = Math.floor(min / 60);
  const m = min % 60;
  if (!h) return t('{m} min', { m });
  if (!m) return t('{h} h', { h });
  return t('{h} h {m} min', { h, m });
}

export const GENRE_ORDER = [0, 8, 2, 6, 4, 3, 1, 7, 5];

export function GenreTile({ genre, index, sample, to }: { genre: BaseItem; index: number; sample?: Track | null; to: string }) {
  const bg = paletteOfIndex(GENRE_ORDER[index % GENRE_ORDER.length])[1];
  const count = (genre as BaseItem & { AlbumCount?: number }).AlbumCount ?? genre.ChildCount;
  return (
    <Link to={to} class="mu-genre" style={{ background: bg }}>
      <span class="mu-genre-name">{genre.Name}</span>
      {count ? <span class="mu-genre-count">{albumsLabel(count)}</span> : null}
      <span aria-hidden="true" class="mu-genre-art">{sample !== undefined && <Cover item={sample} width={180} seedId={sample?.Id ?? genre.Id} class="mu-genre-cover" />}</span>
    </Link>
  );
}

const PALETTE_HEX = [
  ['#1c2a3a', '#2f4a66'], ['#3a1d2e', '#6b2f4f'], ['#13302b', '#1f5a4d'], ['#2b2118', '#5c3b22'], ['#1d1d2e', '#3b3b6b'],
  ['#262626', '#444444'], ['#36200f', '#a3471f'], ['#0f2233', '#1b6f8f'], ['#2a1035', '#7a3fd1']
];
const paletteOfIndex = (i: number) => PALETTE_HEX[i % PALETTE_HEX.length];

/* Jump back in: an album, playlist or artist played lately. */
export function JumpCard({ item, sub, current }: { item: BaseItem; sub: string; current?: { playing: boolean } }) {
  const round = item.Type === 'MusicArtist';
  return (
    <Link to={`/details/${item.Id}`} class={'mu-jump' + (current ? ' is-current' : '')} draggable={item.Type !== 'MusicArtist'}
      onDragStart={(e) => dragItems(e as DragEvent, [item])}>
      <Cover item={item} width={144} class={'mu-jump-cover' + (round ? ' is-round' : '')} />
      <span class="mu-jump-text">
        <span class="mu-jump-title">{item.Name}</span>
        <span class="mu-jump-sub">{sub}</span>
      </span>
      {current && <EqIcon playing={current.playing} label={t('Playing')} />}
    </Link>
  );
}

/* ───────── Instant mix cards ───────── */

export interface Mix { key: string; title: string; sub: string; covers: Track[]; tracks: () => Promise<Track[]>; seed?: string }

export function MixCard({ mix }: { mix: Mix }) {
  const [a, b] = mix.covers;
  // The card takes the colours of its first cover, as on the board.
  const pal = useNearestPalette(a, mix.seed ?? mix.key);
  const [busy, setBusy] = useState(false);
  const play = async () => {
    if (busy) return;
    setBusy(true);
    try {
      const list = await mix.tracks();
      await playLoaded(list, 0, { source: { kind: 'mix', id: mix.seed, name: mix.title } });
    } catch {
      toast(t('Couldn’t start the mix'), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <button type="button" class="mu-mix" style={{ background: `linear-gradient(135deg, ${pal[1]} 0%, ${pal[0]} 100%)` }}
      aria-label={t('Play {name}', { name: mix.title })} onClick={() => void play()}>
      <span aria-hidden="true" class="mu-mix-covers">
        {a && <Cover item={a} width={240} class="mu-mix-a" />}
        {b && <Cover item={b} width={240} class="mu-mix-b" />}
      </span>
      <span class="mu-mix-over"><AIcon name="mix" size={16} stroke={2.4} />{t('Instant mix')}</span>
      <span class="mu-mix-foot">
        <span class="mu-mix-text"><span class="mu-mix-title">{mix.title}</span><span class="mu-mix-sub">{mix.sub}</span></span>
        <span aria-hidden="true" class={'mu-mix-play' + (busy ? ' is-busy' : '')}><AIcon name="play" size={22} /></span>
      </span>
    </button>
  );
}

/* ───────── Song lists ───────── */

/* What a song's ⋯ menu offers: queueing, playlists, going to its album or artist, its radio. */
export function TrackMenu({ track, anchor, open, onClose, extra, pageId }: {
  track: Track; anchor: RefObject<HTMLElement>; open: boolean; onClose: () => void;
  extra?: (close: () => void) => ComponentChildren;
  /* The page the menu opens on: no link back to it. */
  pageId?: string;
}) {
  const [sub, setSub] = useState(false);
  const close = () => { setSub(false); onClose(); };
  const artist0 = track.ArtistItems?.[0] ?? track.AlbumArtists?.[0];
  const artist = artist0?.Id !== pageId ? artist0 : undefined;
  const album = track.AlbumId !== pageId ? track.AlbumId : undefined;
  const item = (icon: Parameters<typeof AIcon>[0]['name'], label: string, run: () => void) => (
    <button type="button" role="menuitem" class="menu-item" onClick={() => { close(); run(); }}>
      <span class="lead"><AIcon name={icon} size={18} /><span>{label}</span></span>
    </button>
  );
  return (
    <Popover anchor={anchor} open={open} onClose={close} align="end" width={sub ? 320 : 260}>
      {sub ? <PlaylistPicker items={[track]} onDone={close} onBack={() => setSub(false)} /> : (
        <div class="menu" role="menu" aria-label={t('More actions for {name}', { name: track.Name })}>
          {item('queueNext', t('Play next'), () => { addToQueue([track], 'next'); toast(t('{name} plays next', { name: track.Name })); })}
          {item('queue', t('Add to queue'), () => { addToQueue([track]); toast(t('Added {name} to the queue', { name: track.Name })); })}
          {item('mix', t('Song radio'), () => {
            instantMix(track.Id).then((list) => playLoaded(list, 0, { source: { kind: 'mix', id: track.Id, name: t('{name} radio', { name: track.Name }) } }), () => toast(t('Couldn’t start the mix'), 'error'));
          })}
          <button type="button" role="menuitem" class="menu-item" onClick={() => setSub(true)}>
            <span class="lead"><AIcon name="playlistAdd" size={18} /><span>{t('Add to playlist')}</span></span>
            <AIcon name="chevronDown" size={15} style={{ transform: 'rotate(-90deg)' }} />
          </button>
          {extra?.(close)}
          {(album || artist) && <div class="menu-sep" />}
          {album && item('album', t('Go to album'), () => navigate(`/details/${album}`))}
          {artist && item('artist', t('Go to artist'), () => navigate(`/details/${artist.Id}`))}
        </div>
      )}
    </Popover>
  );
}

function MoreButton({ track, extra, pageId }: { track: Track; extra?: (close: () => void) => ComponentChildren; pageId?: string }) {
  const ref = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  return (
    <>
      <button ref={ref} type="button" class="mu-tr-more" aria-label={t('More actions for {name}', { name: track.Name })} aria-haspopup="menu"
        aria-expanded={open ? 'true' : 'false'} onClick={(e) => { e.stopPropagation(); setOpen((o) => !o); }}>
        <AIcon name="more" size={18} />
      </button>
      <TrackMenu track={track} anchor={ref as RefObject<HTMLElement>} open={open} onClose={() => setOpen(false)} extra={extra} pageId={pageId} />
    </>
  );
}

/* Favourite states changed from a player since the list loaded. */
function useFavs(): (tk: Track) => boolean {
  const [map, setMap] = useState<Record<string, boolean>>({});
  const f = useStore(favoriteStore);
  useEffect(() => { if (f.n) setMap((m) => ({ ...m, [f.id]: f.on })); }, [f.n]);
  return (tk) => (tk.Id in map ? map[tk.Id] : !!tk.UserData?.IsFavorite);
}

export type TrackCols = 'album' | 'list';

/* The songs table (MusicAlbum board): number (the playing bars on the current song, a play button on hover), title
   and artists, plays, time, favourite. The list variant (songs, playlists) adds the cover and the album. */
export function TrackTable({ tracks, cols = 'album', source, numbers, extra, label, discs, pageId }: {
  tracks: Track[]; cols?: TrackCols; source: PlayOptions['source']; numbers?: 'index' | 'position'; pageId?: string;
  extra?: (track: Track, index: number) => ((close: () => void) => ComponentChildren) | undefined;
  label?: string; discs?: boolean;
}) {
  const s = useStore(audioStore);
  const isFav = useFavs();
  const curId = s.current?.track.Id;
  const play = (i: number) => {
    const tk = tracks[i];
    if (tk.Id === curId) { togglePlay(); return; }
    void playLoaded(tracks, i, { source });
  };
  const multiDisc = discs && new Set(tracks.map((x) => x.ParentIndexNumber ?? 1)).size > 1;
  const rows: ComponentChildren[] = [];
  let disc: number | undefined;
  tracks.forEach((tk, i) => {
    if (multiDisc && (tk.ParentIndexNumber ?? 1) !== disc) {
      disc = tk.ParentIndexNumber ?? 1;
      rows.push(<div key={'d' + disc} role="row" class="mu-tr-disc"><span role="cell">{t('Disc {n}', { n: disc })}</span></div>);
    }
    const current = tk.Id === curId;
    const fav = isFav(tk);
    const n = numbers === 'position' || cols === 'list' ? i + 1 : tk.IndexNumber ?? i + 1;
    const time = tk.RunTimeTicks ? clock(tk.RunTimeTicks / 10_000_000) : '';
    rows.push(
      <div key={tk.Id + ':' + i} role="row" class={'mu-tr is-' + cols + (current ? ' is-current' : '')} draggable
        onDragStart={(e) => dragItems(e as DragEvent, [tk])}
        onClick={(e) => { if ((e.target as HTMLElement).closest('button, a')) return; play(i); }}>
        <span role="cell" class="mu-tr-n">
          {current && <EqIcon playing={s.playing} label={s.playing ? t('Playing') : t('Paused')} />}
          {!current && <span class="mu-tr-num">{n}</span>}
          <button type="button" class="mu-tr-play" aria-label={current && s.playing ? t('Pause {name}', { name: tk.Name }) : t('Play {name}', { name: tk.Name })} onClick={() => play(i)}>
            <AIcon name={current && s.playing ? 'pause' : 'play'} size={18} />
          </button>
        </span>
        <span role="cell" class="mu-tr-main">
          {cols === 'list' && <Cover item={tk} width={80} class="mu-tr-cover" />}
          <span class="mu-tr-titles">
            <span class="mu-tr-title">{tk.Name}</span>
            <span class="mu-tr-artists">{artistsOf(tk)}</span>
          </span>
        </span>
        {cols === 'list' && (
          <span role="cell" class="mu-tr-album">{tk.AlbumId ? <Link to={`/details/${tk.AlbumId}`}>{tk.Album}</Link> : tk.Album}</span>
        )}
        <span role="cell" class="mu-tr-plays">{playsLabel(tk.UserData?.PlayCount)}</span>
        <span role="cell" class="mu-tr-time"><MoreButton track={tk} extra={extra?.(tk, i)} pageId={pageId} /><span>{time}</span></span>
        <span role="cell" class="mu-tr-favcell">
          <button type="button" class={'mu-tr-fav' + (fav ? ' is-on' : '')} aria-pressed={fav ? 'true' : 'false'}
            aria-label={fav ? t('Remove {name} from favorites', { name: tk.Name }) : t('Add {name} to favorites', { name: tk.Name })}
            onClick={() => void toggleTrackFavorite(tk, !fav)}>
            <AIcon name="heart" size={18} filled={fav} />
          </button>
        </span>
      </div>
    );
  });
  return (
    <div role="table" aria-label={label ?? t('Songs')} class={'mu-tracks is-' + cols}>
      <div role="row" class={'mu-tr-head is-' + cols}>
        <span role="columnheader">#</span>
        <span role="columnheader">{t('Title')}</span>
        {cols === 'list' && <span role="columnheader">{t('Album')}</span>}
        <span role="columnheader">{t('Plays')}</span>
        <span role="columnheader" class="is-right">{inContext('Time (song length)', 'Time')}</span>
        <span role="columnheader"><span class="sr-only">{t('Favorite')}</span></span>
      </div>
      {rows}
    </div>
  );
}

export function TracksSkeleton({ rows = 8 }: { rows?: number }) {
  return (
    <div class="mu-tracks" aria-busy="true">
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} class="mu-tr is-album is-skel"><span class="skel" style={{ width: '18px', height: '14px' }} /><span class="skel skel-line" style={{ width: `${40 + ((i * 17) % 35)}%` }} /></div>
      ))}
    </div>
  );
}

/* ───────── Data ───────── */

export const albumArtistsCount = (parentId: string) =>
  get<ItemsResult>('/Artists/AlbumArtists', { userId: uid(), parentId, limit: 0, enableTotalRecordCount: true, enableImages: false }).then((r) => r.TotalRecordCount);

export async function countOf(parentId: string, type: string): Promise<number> {
  const r = await getItems({ parentId, includeItemTypes: [type], recursive: true, limit: 0, enableImageTypes: [], fields: [] });
  return r.TotalRecordCount;
}

export const songsOf = (q: Parameters<typeof getItems>[0]) => getItems({ ...q, includeItemTypes: ['Audio'], recursive: true, fields: TRACK_FIELDS }).then((r) => r.Items as Track[]);

