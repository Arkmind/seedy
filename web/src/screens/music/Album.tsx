import { useEffect, useState } from 'preact/hooks';
import { SERVER_URL, del, getSession } from '../../api/client';
import { getItem, getItems } from '../../api/jellyfin';
import { imageUrl } from '../../api/images';
import type { BaseItem } from '../../api/types';
import { userStore, viewsStore } from '../../lib/app-state';
import { dayOf } from '../../lib/format';
import { t } from '../../lib/i18n';
import { Link, navigate } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { Header } from '../../ui/navbar/Header';
import { MenuItem, toast } from '../../ui/overlay';
import { EmptyState } from '../browse/parts';
import { toggleFavorite } from '../browse/parts';
import { MoreActions, useView } from '../browse/details-parts';
import { manageChanged } from '../browse/manage/host';
import { albumTracks, audioStreamOf, instantMix, playlistTracks, qualityLabel, type Track } from '../../audio/api';
import { Cover, initialsOf, paletteOf, useCoverTint } from '../../audio/cover';
import { addToQueue, audioStore, playLoaded, playedStore, togglePlay } from '../../audio/engine';
import { AIcon } from '../../audio/icons';
import { sizeLabel } from '../../audio/NowPlaying';
import {
  AlbumCard, SectionHead, TrackTable, TracksSkeleton, dot, lengthLabel, longDate, playlistCovers, releaseLabel, releaseOf, songsLabel, yearOf
} from './parts';
import './music.css';

/* An album (MusicAlbum board), and a music playlist in the same layout: the cover glowing behind the title, the
   artist, year, songs, length and quality; Play, Shuffle, Instant mix, favourite, download and ⋯; the songs; when it
   came out and was added; More by the artist. */


/* Reloads [deps] after the player finished one of these songs, so play counts move. */
function usePlaysVersion(ids: string[] | undefined): number {
  const played = useStore(playedStore);
  const [v, setV] = useState(0);
  useEffect(() => { if (played.n && ids?.includes(played.id)) setV((x) => x + 1); }, [played.n]);
  return v;
}

/* Every song of the album, one file after the other (the browser asks once). */
function downloadAll(tracks: Track[]) {
  const token = getSession()?.token ?? '';
  tracks.forEach((tk, i) => {
    window.setTimeout(() => {
      const a = document.createElement('a');
      a.href = `${SERVER_URL}/Items/${tk.Id}/Download?ApiKey=${encodeURIComponent(token)}`;
      a.download = '';
      a.rel = 'noreferrer';
      document.body.appendChild(a);
      a.click();
      a.remove();
    }, i * 400);
  });
  toast(t('Downloading {n} songs', { n: tracks.length }));
}

const titleSize = (name: string) => (name.length > 34 ? ' is-xlong' : name.length > 18 ? ' is-long' : '');

export function MusicAlbumPage({ item: initial, playlist }: { item: BaseItem; playlist?: boolean }) {
  const [item, setItem] = useState(initial);
  useEffect(() => setItem(initial), [initial]);
  const found = useView(item);
  const views = useStore(viewsStore);
  // A music playlist belongs with the music library (its pill and search box).
  const view = playlist ? views.find((v) => v.CollectionType === 'music') ?? found : found;
  const s = useStore(audioStore);
  const user = useStore(userStore);
  const managed = useStore(manageChanged);
  const [ids, setIds] = useState<string[]>();
  const ver = usePlaysVersion(ids);
  const tracks = useAsync(() => (playlist ? playlistTracks(item.Id) : albumTracks(item.Id)), [item.Id, ver, managed.n]);
  const list = tracks.data ?? [];
  useEffect(() => { if (tracks.data) setIds(tracks.data.map((x) => x.Id)); }, [tracks.data]);
  const tint = useCoverTint(playlist ? list[0] : item);
  const artist = (item as Track).AlbumArtists?.[0] ?? item.ArtistItems?.[0];
  const artistName = artist?.Name ?? item.AlbumArtist;
  const total = list.reduce((sum, x) => sum + (x.RunTimeTicks ?? 0), 0) / 10_000_000;
  const quality = qualityLabel(audioStreamOf(list.find((x) => audioStreamOf(x))));
  const bytes = list.reduce((sum, x) => sum + (x.MediaSources?.[0]?.Size ?? 0), 0);
  const source = { kind: playlist ? 'playlist' as const : 'album' as const, id: item.Id, name: item.Name };
  const here = !!s.current && (s.source?.id === item.Id || (!playlist && s.current.track.AlbumId === item.Id && s.source?.kind === 'album'));
  const playing = here && s.playing;
  const canDownload = !!(user?.Policy as { EnableContentDownloading?: boolean } | undefined)?.EnableContentDownloading;
  const fav = !!item.UserData?.IsFavorite;

  const play = () => {
    if (here) { togglePlay(); return; }
    if (list.length) void playLoaded(list, 0, { source });
  };
  const mix = async () => {
    try {
      const mixed = await instantMix(item.Id, 150);
      await playLoaded(mixed.length ? mixed : list, 0, { source: { kind: 'mix', id: item.Id, name: t('{name} mix', { name: item.Name }) } });
    } catch {
      toast(t('Couldn’t start the mix'), 'error');
    }
  };
  const remove = async (tk: Track & { PlaylistItemId?: string }) => {
    if (!tk.PlaylistItemId) return;
    try {
      await del<void>(`/Playlists/${item.Id}/Items`, { entryIds: tk.PlaylistItemId });
      toast(t('Removed {name} from {list}', { name: tk.Name, list: item.Name }));
      tracks.reload();
    } catch {
      toast(t('Couldn’t update {name}', { name: item.Name }), 'error');
    }
  };

  const released = item.PremiereDate ? dayOf(item.PremiereDate) : undefined;
  const added = item.DateCreated ? new Date(item.DateCreated) : undefined;
  const label = item.Studios?.[0]?.Name;
  const covers = playlist ? playlistCovers(list) : [];

  return (
    <div class="page mu-page">
      <Header active={view ? { viewId: view.Id } : null} />
      <div class="mu-hero-wrap">
        <div aria-hidden="true" class="mu-hero-glow" style={{ background: `radial-gradient(900px 420px at 18% 0%, color-mix(in srgb, ${tint[0]} 75%, transparent) 0%, rgba(14, 14, 14, 0) 70%)` }} />
        <section aria-labelledby="mu-al-h" class="mu-al-hero">
          {playlist && covers.length >= 2 && !item.ImageTags?.Primary ? (
            <span class="mu-al-cover mu-pl-grid">{[0, 1, 2, 3].map((i) => <Cover key={i} item={covers[i % covers.length]} width={300} class="mu-pl-cell" />)}</span>
          ) : <Cover item={playlist && !item.ImageTags?.Primary ? covers[0] ?? item : item} width={600} title={item.Name} class="mu-al-cover" />}
          <div class="mu-al-text">
            <span class="mu-over">{playlist ? t('Playlist') : releaseLabel(releaseOf({ ...item, ChildCount: list.length || item.ChildCount, RunTimeTicks: total ? total * 10_000_000 : item.RunTimeTicks }))}</span>
            <h1 id="mu-al-h" class={'mu-al-title' + titleSize(item.Name)}>{item.Name}</h1>
            <span class="mu-al-meta">
              {!playlist && artist && (
                <Link to={`/details/${artist.Id}`} class="mu-al-artist">
                  <ArtistAvatar id={artist.Id} name={artist.Name} />
                  {artist.Name}
                </Link>
              )}
              {!playlist && !artist && artistName && <span class="mu-al-artist">{artistName}</span>}
              <span>{(!playlist && artistName ? '· ' : '') + dot(playlist ? '' : yearOf(item), list.length ? songsLabel(list.length) : '', total ? lengthLabel(total) : '')}</span>
              {quality && !playlist && <span class="mu-quality">{quality}</span>}
            </span>
            <div class="mu-al-actions">
              <button type="button" class="mu-play-big" disabled={!list.length && !here} aria-label={playing ? t('Pause {name}', { name: item.Name }) : t('Play {name}', { name: item.Name })} onClick={play}>
                <AIcon name={playing ? 'pause' : 'play'} size={24} />
              </button>
              <button type="button" class="mu-btn" disabled={!list.length} onClick={() => void playLoaded(list, 0, { shuffle: true, source })}>
                <AIcon name="shuffle" size={18} />{t('Shuffle')}
              </button>
              <button type="button" class="mu-btn" disabled={!list.length} onClick={() => void mix()}>
                <AIcon name="mix" size={18} />{t('Instant mix')}
              </button>
              <button type="button" class={'mu-btn is-icon' + (fav ? ' is-on' : '')} aria-pressed={fav ? 'true' : 'false'}
                aria-label={fav ? t('Remove from favorites') : t('Add to favorites')} onClick={() => void toggleFavorite(item, setItem)}>
                <AIcon name="heart" size={20} filled={fav} />
              </button>
              {canDownload && !playlist && (
                <button type="button" class="mu-btn is-icon" aria-label={t('Download album')} disabled={!list.length} onClick={() => downloadAll(list)}>
                  <AIcon name="download" size={20} />
                </button>
              )}
              <MoreActions item={item}>
                {(close) => (
                  <>
                    <MenuItem icon="playlistAdd" label={t('Play next')} onSelect={() => { close(); addToQueue(list, 'next'); toast(t('{name} plays next', { name: item.Name })); }} />
                    <MenuItem icon="list" label={t('Add to queue')} onSelect={() => { close(); addToQueue(list); toast(t('Added {name} to the queue', { name: item.Name })); }} />
                    {artist && !playlist && <MenuItem icon="person" label={t('Go to artist')} onSelect={() => { close(); navigate(`/details/${artist.Id}`); }} />}
                  </>
                )}
              </MoreActions>
            </div>
          </div>
        </section>
      </div>

      <main class="mu-page-main">
        <div>
          {tracks.loading && !tracks.data ? <TracksSkeleton rows={10} />
            : !list.length ? <EmptyState icon="music" title={playlist ? t('This playlist is empty') : t('No songs')} text={playlist ? t('Add songs from their ⋯ menu, or drag them onto the playlist in the library.') : t('This album has no playable songs on the server.')} />
            : (
              <TrackTable tracks={list} cols={playlist ? 'list' : 'album'} source={source} discs={!playlist} numbers={playlist ? 'position' : 'index'} pageId={item.Id}
                extra={playlist ? (tk) => (close) => (
                  <button type="button" role="menuitem" class="menu-item" onClick={() => { close(); void remove(tk); }}>
                    <span class="lead"><AIcon name="trash" size={18} /><span>{t('Remove from this playlist')}</span></span>
                  </button>
                ) : undefined} />
            )}
          {!playlist && list.length > 0 && (released || added || label) && (
            <p class="mu-foot">
              {(released || label) && <>{dot(released ? t('Released {date}', { date: longDate(released) }) : '', label)}<br /></>}
              {dot(added ? t('Added to the server {date}', { date: longDate(added) }) : '', bytes ? sizeLabel(bytes) : '')}
            </p>
          )}
        </div>
        {!playlist && artist && <MoreBy album={item} artist={artist} />}
      </main>
    </div>
  );
}

function ArtistAvatar({ id, name }: { id: string; name: string }) {
  const artist = useAsync(() => getItem(id, []).catch(() => null), [id]);
  const tag = artist.data?.ImageTags?.Primary;
  const pal = paletteOf(id);
  return (
    <span aria-hidden="true" class="mu-al-avatar" style={{ background: pal[1], color: pal[3] }}>
      {tag ? <img src={imageUrl(id, 'Primary', { tag, maxWidth: 56 })} alt="" /> : initialsOf(name)}
    </span>
  );
}

/* More by the album's artist: their other releases, then albums they appear on. */
function MoreBy({ album, artist }: { album: BaseItem; artist: { Id: string; Name: string } }) {
  const data = useAsync(async () => {
    const fields = ['ProductionYear', 'PremiereDate', 'ChildCount', 'SortName', 'Tags', 'AlbumArtist'];
    const [own, all] = await Promise.all([
      getItems({ albumArtistIds: [artist.Id], includeItemTypes: ['MusicAlbum'], sortBy: ['ProductionYear', 'PremiereDate', 'SortName'], sortOrder: 'Descending', limit: 30, fields }),
      getItems({ artistIds: [artist.Id], includeItemTypes: ['MusicAlbum'], sortBy: ['ProductionYear', 'PremiereDate', 'SortName'], sortOrder: 'Descending', limit: 30, fields })
    ]);
    const mine = new Set(own.Items.map((x) => x.Id));
    const releases = own.Items.filter((x) => x.Id !== album.Id).map((a) => ({ a: a as Track, sub: dot(releaseLabel(releaseOf(a)), yearOf(a)) }));
    const appears = all.Items.filter((x) => !mine.has(x.Id) && x.Id !== album.Id).map((a) => ({ a: a as Track, sub: dot(releaseLabel('appears'), yearOf(a)) }));
    return [...releases, ...appears].slice(0, 6);
  }, [album.Id, artist.Id]);
  if (!data.data?.length) return null;
  return (
    <section aria-labelledby="mu-more-h" class="mu-sec">
      <SectionHead id="mu-more-h" title={t('More by {name}', { name: artist.Name })} link={{ to: `/details/${artist.Id}#discography`, label: t('Discography') }} />
      <div class="mu-grid is-6">{data.data.map((x) => <AlbumCard key={x.a.Id} album={x.a} sub={x.sub} />)}</div>
    </section>
  );
}

