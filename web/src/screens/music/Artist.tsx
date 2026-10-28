import { useEffect, useState } from 'preact/hooks';
import { get, getSession } from '../../api/client';
import { getItems } from '../../api/jellyfin';
import { backdropUrl, imageUrl } from '../../api/images';
import type { BaseItem, ItemsResult } from '../../api/types';
import { t, tn } from '../../lib/i18n';
import { useAsync, useStore } from '../../lib/store';
import { BackBar } from '../../ui/BackBar';
import { Header } from '../../ui/navbar/Header';
import { ItemCard } from '../../ui/cards';
import { toast } from '../../ui/overlay';
import { toggleFavorite } from '../browse/parts';
import { MoreActions, useView } from '../browse/details-parts';
import { TRACK_FIELDS, artistTracks, instantMix, type Track } from '../../audio/api';
import { Cover, useNearestPalette } from '../../audio/cover';
import { audioStore, playLoaded, playedStore, togglePlay } from '../../audio/engine';
import { AIcon, EqIcon } from '../../audio/icons';
import { clock } from '../../audio/parts';
import { AlbumCard, ArtistCard, SectionHead, dot, playsLabel, releaseOf, songsLabel, yearOf, type Release } from './parts';
import { CardSkel, artistAlbums } from './MusicLibrary';
import './music.css';

/* An artist (MusicArtist board): the name over their art; Play, Shuffle, Artist radio, favourite and ⋯; Most played
   here and About; the discography in albums, singles and EPs, albums they appear on and music videos; similar
   artists on the server. */

const uid = () => getSession()?.userId ?? '';
const nameSize = (name: string) => (name.length > 22 ? ' is-xlong' : name.length > 13 ? ' is-long' : '');

type DiscTab = 'albums' | 'singles' | 'appears' | 'videos';

export function MusicArtistPage({ item: initial }: { item: BaseItem }) {
  const [item, setItem] = useState(initial);
  useEffect(() => setItem(initial), [initial]);
  const view = useView(item);
  const s = useStore(audioStore);
  const played = useStore(playedStore);
  const [tab, setTab] = useState<DiscTab>('albums');

  const songs = useAsync(() => getItems({
    artistIds: [item.Id], includeItemTypes: ['Audio'], recursive: true, sortBy: ['PlayCount', 'SortName'], sortOrder: 'Descending', limit: 400, fields: TRACK_FIELDS
  }).then((r) => r.Items as Track[]), [item.Id, Math.floor(played.n / 2)]);
  const disc = useAsync(async () => {
    const fields = ['ProductionYear', 'PremiereDate', 'ChildCount', 'SortName', 'Tags', 'AlbumArtist', 'RunTimeTicks'];
    const [own, all, videos] = await Promise.all([
      getItems({ albumArtistIds: [item.Id], includeItemTypes: ['MusicAlbum'], sortBy: ['ProductionYear', 'PremiereDate', 'SortName'], sortOrder: 'Descending', limit: 200, fields }),
      getItems({ artistIds: [item.Id], includeItemTypes: ['MusicAlbum'], sortBy: ['ProductionYear', 'PremiereDate', 'SortName'], sortOrder: 'Descending', limit: 200, fields }),
      getItems({ artistIds: [item.Id], includeItemTypes: ['MusicVideo'], recursive: true, sortBy: ['ProductionYear', 'SortName'], sortOrder: 'Descending', limit: 100 }).catch(() => ({ Items: [] as BaseItem[], TotalRecordCount: 0 }))
    ]);
    const mine = new Set(own.Items.map((x) => x.Id));
    const by = (r: Release) => own.Items.filter((a) => releaseOf(a) === r) as Track[];
    return {
      albums: by('album'), singles: [...by('ep'), ...by('single')].sort((a, b) => (b.ProductionYear ?? 0) - (a.ProductionYear ?? 0)),
      appears: all.Items.filter((x) => !mine.has(x.Id)) as Track[], videos: videos.Items, owned: own.Items.length
    };
  }, [item.Id]);

  const list = songs.data ?? [];
  const playsHere = list.reduce((sum, x) => sum + (x.UserData?.PlayCount ?? 0), 0);
  const top = (list.some((x) => x.UserData?.PlayCount) ? list.filter((x) => x.UserData?.PlayCount) : list).slice(0, 5);
  const place = (item as BaseItem & { ProductionLocations?: string[] }).ProductionLocations?.[0];
  const source = { kind: 'artist' as const, id: item.Id, name: item.Name };
  const here = !!s.current && s.source?.id === item.Id;
  const fav = !!item.UserData?.IsFavorite;

  const playAll = async (shuffle: boolean) => {
    try {
      const all = await artistTracks(item.Id);
      await playLoaded(all, 0, { shuffle, source });
    } catch {
      toast(t('Couldn’t start playback'), 'error');
    }
  };
  const radio = async () => {
    try {
      const mixed = await instantMix(item.Id, 150);
      await playLoaded(mixed, 0, { source: { kind: 'mix', id: item.Id, name: t('{name} radio', { name: item.Name }) } });
    } catch {
      toast(t('Couldn’t start the mix'), 'error');
    }
  };

  const d = disc.data;
  const tabs: { id: DiscTab; label: string; n: number }[] = d ? [
    { id: 'albums', label: 'Albums', n: d.albums.length },
    { id: 'singles', label: 'Singles and EPs', n: d.singles.length },
    { id: 'appears', label: 'Appears on', n: d.appears.length },
    { id: 'videos', label: 'Music videos', n: d.videos.length }
  ].filter((x) => x.n > 0 || x.id === 'albums') as { id: DiscTab; label: string; n: number }[] : [];
  const shown = d && tabs.some((x) => x.id === tab) ? tab : 'albums';
  useEffect(() => {
    if (window.location.hash === '#discography') document.getElementById('mu-disc-h')?.scrollIntoView({ block: 'start' });
  }, [!!d]);

  return (
    <div class="page mu-page">
      <Header active={view ? { viewId: view.Id } : null} />
      <section aria-labelledby="mu-ar-h" class="mu-ar-hero">
        <ArtistArt artist={item} latest={d ? d.albums[0] ?? d.singles[0] : undefined} />
        <div aria-hidden="true" class="mu-ar-shade" />
        <BackBar over class="mu-ar-back" crumbs={view ? [
          { label: view.Name, to: `/library/${view.Id}` },
          { label: t('Artists'), to: `/library/${view.Id}?tab=artists` },
          { label: item.Name }
        ] : [{ label: item.Name }]} />
        <div class="mu-ar-text">
          <span class="mu-over">{t('Artist')}</span>
          <h1 id="mu-ar-h" class={'mu-ar-name' + nameSize(item.Name)}>{item.Name}</h1>
          <span class="mu-ar-meta">
            {dot(
              d ? tn(d.owned, '{n} album or EP on the server', '{n} albums and EPs on the server') : '',
              playsHere ? tn(playsHere, '{n} play here', '{n} plays here') : '',
              place
            )}
          </span>
          <div class="mu-ar-actions">
            <button type="button" class="mu-btn is-light" onClick={() => (here ? togglePlay() : void playAll(false))}>
              <AIcon name={here && s.playing ? 'pause' : 'play'} size={18} />{here && s.playing ? t('Pause') : t('Play')}
            </button>
            <button type="button" class="mu-btn" onClick={() => void playAll(true)}><AIcon name="shuffle" size={18} />{t('Shuffle')}</button>
            <button type="button" class="mu-btn" onClick={() => void radio()}><AIcon name="mix" size={18} />{t('Artist radio')}</button>
            <button type="button" class={'mu-btn is-icon' + (fav ? ' is-on' : '')} aria-pressed={fav ? 'true' : 'false'}
              aria-label={fav ? t('Remove {name} from favorites', { name: item.Name }) : t('Add {name} to favorites', { name: item.Name })}
              onClick={() => void toggleFavorite(item, setItem)}>
              <AIcon name="heart" size={20} filled={fav} />
            </button>
            <MoreActions item={item} />
          </div>
        </div>
      </section>

      <main class="mu-ar-main">
        <div class="mu-ar-top">
          <section aria-labelledby="mu-pop-h" class="mu-pop">
            <h2 id="mu-pop-h" class="mu-pop-title">{t('Most played here')}</h2>
            {songs.loading && !songs.data ? Array.from({ length: 5 }, (_, i) => <span key={i} class="skel" style={{ height: '64px', borderRadius: '12px' }} />)
              : !top.length ? <p class="mu-about-text">{t('No songs by {name} on the server yet.', { name: item.Name })}</p>
              : top.map((tk, i) => {
                const current = s.current?.track.Id === tk.Id;
                return (
                  <div key={tk.Id} role="button" tabIndex={0} class={'mu-pop-row' + (current ? ' is-current' : '')}
                    aria-label={t('Play {name}', { name: tk.Name })}
                    onClick={() => (current ? togglePlay() : void playLoaded(top, i, { source }))}
                    onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); if (current) togglePlay(); else void playLoaded(top, i, { source }); } }}>
                    <span class="mu-pop-n">{current ? <EqIcon playing={s.playing} label={t('Playing')} /> : i + 1}</span>
                    <Cover item={tk} width={104} class="mu-pop-cover" />
                    <span class="mu-pop-titles">
                      <span class="mu-tr-title">{tk.Name}</span>
                      <span class="mu-tr-artists">{tk.Album}</span>
                    </span>
                    <span class="mu-pop-plays">{playsLabel(tk.UserData?.PlayCount)}</span>
                    <span class="mu-pop-time">{tk.RunTimeTicks ? clock(tk.RunTimeTicks / 10_000_000) : ''}</span>
                  </div>
                );
              })}
          </section>
          <About artist={item} albums={d ? [...d.albums, ...d.singles] : []} />
        </div>

        <section aria-labelledby="mu-disc-h" class="mu-sec" id="discography">
          <div class="mu-disc-head">
            <h2 id="mu-disc-h" class="mu-sec-title">{t('Discography')}</h2>
            {tabs.length > 0 && (
              <div role="tablist" aria-label={t('Discography')} class="mu-disc-tabs">
                {tabs.map((x) => (
                  <button key={x.id} type="button" role="tab" class="mu-tab" aria-selected={shown === x.id ? 'true' : 'false'} onClick={() => setTab(x.id)}>
                    {`${t(x.label)} · ${x.n}`}
                  </button>
                ))}
              </div>
            )}
          </div>
          {!d ? <div class="mu-grid is-6">{Array.from({ length: 4 }, (_, i) => <CardSkel key={i} />)}</div>
            : shown === 'videos' ? <div class="mu-video-grid">{d.videos.map((v) => <ItemCard key={v.Id} item={v} meta={yearOf(v)} />)}</div>
            : (
              <div class="mu-grid is-6">
                {(shown === 'albums' ? d.albums : shown === 'singles' ? d.singles : d.appears).map((a) => (
                  <AlbumCard key={a.Id} album={a} sub={shown === 'appears' ? dot(a.AlbumArtist, yearOf(a)) : dot(yearOf(a), a.ChildCount ? songsLabel(a.ChildCount) : '')} />
                ))}
                {shown === 'albums' && !d.albums.length && <p class="mu-about-text">{t('No albums by {name} on the server yet.', { name: item.Name })}</p>}
              </div>
            )}
        </section>

        <Similar artist={item} />
      </main>
    </div>
  );
}

/* The artist's backdrop when the server has one; otherwise the board's sun over a hill, with their photo in it. */
function ArtistArt({ artist, latest }: { artist: BaseItem; latest?: BaseItem }) {
  const bd = backdropUrl(artist, 1920);
  const [failed, setFailed] = useState(false);
  const pal = useNearestPalette(latest, artist.Id);
  if (bd && !failed) return <img class="mu-ar-backdrop" src={bd} alt="" onError={() => setFailed(true)} />;
  const photo = artist.ImageTags?.Primary ? imageUrl(artist.Id, 'Primary', { tag: artist.ImageTags.Primary, maxWidth: 520 }) : undefined;
  const clip = 'mu-ar-clip-' + artist.Id;
  return (
    <svg viewBox="0 0 1440 420" preserveAspectRatio="xMidYMid slice" class="mu-ar-art" aria-hidden="true">
      <defs><clipPath id={clip}><circle cx="1060" cy="150" r="186" /></clipPath></defs>
      <rect width="1440" height="420" fill={pal[0]} />
      <circle cx="1060" cy="150" r="260" fill={pal[1]} opacity="0.6" />
      {photo
        ? <image href={photo} x="874" y="-36" width="372" height="372" preserveAspectRatio="xMidYMid slice" clip-path={`url(#${clip})`} />
        : <circle cx="1060" cy="150" r="120" fill={pal[2]} opacity="0.9" />}
      <circle cx="1060" cy="150" r="190" fill="none" stroke={pal[3]} stroke-width="3" opacity="0.35" />
      <path d="M0 300 Q360 240 720 300 T1440 290 V420 H0Z" style={{ fill: `color-mix(in srgb, ${pal[0]} 72%, #000)` }} />
    </svg>
  );
}

function About({ artist, albums }: { artist: BaseItem; albums: BaseItem[] }) {
  const [open, setOpen] = useState(false);
  const genres = artist.Genres?.length ? artist.Genres : [...new Set(albums.flatMap((a) => a.Genres ?? []))];
  const ids = artist.ProviderIds ?? {};
  const mb = !!(ids.MusicBrainzArtist || ids.MusicBrainzAlbumArtist);
  const adb = !!(ids.AudioDbArtist || ids.AudioDbAlbumArtist);
  const src = mb && adb ? t('Info from MusicBrainz and TheAudioDB') : mb ? t('Info from MusicBrainz') : adb ? t('Info from TheAudioDB') : '';
  const long = (artist.Overview?.length ?? 0) > 420;
  return (
    <section aria-labelledby="mu-about-h" class="mu-about">
      <h2 id="mu-about-h">{t('About')}</h2>
      {artist.Overview
        ? <p class={'mu-about-text' + (long && !open ? ' is-clamped' : '')}>{artist.Overview}</p>
        : <p class="mu-about-text">{t('No biography for {name} yet.', { name: artist.Name })}</p>}
      {long && <button type="button" class="mu-about-more" aria-expanded={open ? 'true' : 'false'} onClick={() => setOpen((o) => !o)}>{open ? t('Show less') : t('Read more')}</button>}
      {genres.length > 0 && <div class="mu-chips">{genres.slice(0, 6).map((g) => <span key={g} class="mu-chip">{g}</span>)}</div>}
      {src && <span class="mu-about-src">{src}</span>}
    </section>
  );
}

function Similar({ artist }: { artist: BaseItem }) {
  const data = useAsync(() =>
    get<ItemsResult>(`/Artists/${artist.Id}/Similar`, { userId: uid(), limit: 7, fields: 'ItemCounts,ChildCount,PrimaryImageAspectRatio' })
      .then((r) => r.Items, () => [] as BaseItem[]),
  [artist.Id]);
  if (!data.data?.length) return null;
  return (
    <section aria-labelledby="mu-sim-h" class="mu-sec">
      <SectionHead id="mu-sim-h" title={t('Similar artists on the server')} />
      <div class="mu-grid is-7">{data.data.map((a) => <ArtistCard key={a.Id} artist={a} sub={artistAlbums(a)} initialsSize={40} />)}</div>
    </section>
  );
}
