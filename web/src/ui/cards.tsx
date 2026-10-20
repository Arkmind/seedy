import type { ComponentChildren } from 'preact';
import { useState } from 'preact/hooks';
import { cardArt, personImageUrl, tmdbImage, type CardArt } from '../api/images';
import type { BaseItem } from '../api/types';
import type { SeerrItem } from '../api/seedy';
import { Link, href, navigate } from '../lib/router';
import { pct, progressFraction } from '../lib/format';
import { useStore } from '../lib/store';
import { tvActive } from '../screens/me/effects';
import { Icon } from './icons';
import { t } from '../lib/i18n';

/* ── Artwork ── */

function Img({ src, class: cls, alt = '' }: { src: string; class?: string; alt?: string }) {
  const [loaded, setLoaded] = useState(false);
  return (
    <img
      src={src}
      alt={alt}
      class={cls}
      loading="lazy"
      decoding="async"
      style={{ opacity: loaded ? 1 : 0, transition: 'opacity 240ms ease' }}
      onLoad={() => setLoaded(true)}
    />
  );
}

export function Art({ art, title, titleClass }: { art: CardArt; title: string; titleClass?: string }) {
  return (
    <>
      {art.src && art.contain && <Img src={art.src} class="art-blur" />}
      {art.src && <Img src={art.src} class={art.contain ? 'art-contain' : 'art'} />}
      {art.logo && <><div class="art-shade" /><Img src={art.logo} class="art-logo" /></>}
      {art.titleOnArt && <span aria-hidden="true" class={'art-title' + (titleClass ? ' ' + titleClass : '')}>{title}</span>}
    </>
  );
}

export function Progress({ fraction, size = 8, class: cls }: { fraction: number; size?: 8 | 6 | 5 | 4; class?: string }) {
  return (
    <div class={'bar' + (size !== 8 ? ' is-' + size : '') + (cls ? ' ' + cls : '')} role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(fraction * 100)}>
      <i style={{ width: pct(fraction) }} />
    </div>
  );
}

export function WatchedCheck({ size = 28 }: { size?: number }) {
  return (
    <span aria-label={t('Watched')} class="watched-dot" style={size !== 28 ? { width: size + 'px', height: size + 'px' } : undefined}>
      <Icon name="check" size={size === 28 ? 15 : 13} stroke={3} />
    </span>
  );
}

export const detailsPath = (item: Pick<BaseItem, 'Id' | 'Type'>) => `/details/${item.Id}`;
export const playPath = (item: Pick<BaseItem, 'Id'>, opts: { from?: string; restart?: boolean; startTicks?: number } = {}) => {
  const q = new URLSearchParams();
  if (opts.from) q.set('from', opts.from);
  if (opts.restart) q.set('restart', '1');
  if (opts.startTicks && opts.startTicks > 0) q.set('t', String(Math.round(opts.startTicks)));
  const s = q.toString();
  return `/play/${item.Id}${s ? '?' + s : ''}`;
};

/* ── Play disc ──
   Every card of something playable gets the Continue watching card's play disc (Main board: 56 px, light, centred),
   shown on hover and keyboard focus. The disc plays; the rest of the card opens the details page. It is a sibling of
   the card's link, never inside it. Shows and seasons play their resume point or next episode (the player resolves them). */

const PLAYABLE = new Set(['Movie', 'Series', 'Season', 'Episode', 'Video', 'MusicVideo', 'Audio', 'AudioBook', 'MusicAlbum', 'Playlist']);
export const canPlayCard = (item: Pick<BaseItem, 'Type' | 'LocationType'>) => PLAYABLE.has(item.Type) && item.LocationType !== 'Virtual';

export function CardPlay({ item, label, origin, onPlay }: { item: BaseItem; label: string; origin?: string; onPlay?: () => void }) {
  // TV remotes move from card to card and OK opens the card, so the disc stays out of the arrow keys' path there.
  const tv = useStore(tvActive);
  return (
    <button
      type="button"
      class="card-play"
      aria-label={label}
      tabIndex={tv ? -1 : undefined}
      onClick={() => { onPlay?.(); navigate(playPath(item, { from: origin })); }}
    >
      <Icon name="playRound" size={22} />
    </button>
  );
}

const playLabel = (item: BaseItem, name: string) =>
  ((item.UserData?.PlaybackPositionTicks ?? 0) > 0 && !item.UserData?.Played ? t('Resume {name}', { name }) : t('Play {name}', { name }));

/* ── 16:9 item card ── */

export interface ItemCardProps {
  item: BaseItem;
  width?: number;
  title?: string;
  meta?: ComponentChildren;
  tag?: string;
  isNew?: boolean;
  progress?: number;
  barSize?: 8 | 6;
  watched?: boolean;
  unwatched?: number;
  /* Show the play disc; by default it shows for anything playable. */
  play?: boolean;
  origin?: string;
  titleSize?: 15 | 14;
  titleWeight?: 700 | 600;
  hoverTools?: ComponentChildren;
  grid?: boolean;
  artWidth?: number;
  onOpen?: () => void;
}

export function ItemCard(p: ItemCardProps) {
  const item = p.item;
  const title = p.title ?? (item.Type === 'Episode' ? item.SeriesName ?? item.Name : item.Name);
  const art = cardArt(item, p.artWidth ?? 640);
  const target = detailsPath(item);
  const canPlay = p.play ?? canPlayCard(item);
  const name = title + (item.Type === 'Episode' && item.Name && item.Name !== title ? ', ' + item.Name : '');
  const style = p.width ? { flex: `0 0 ${p.width}px`, width: p.width + 'px' } : undefined;
  const titleCls = 'card-title' + (p.titleSize === 14 ? ' is-14' : '') + (p.titleWeight === 600 ? ' is-600' : '');
  const open = (e: MouseEvent) => {
    if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey) return;
    e.preventDefault();
    p.onOpen?.();
    navigate(target);
  };

  return (
    <div class={'card' + (canPlay ? ' has-play' : '')} style={style}>
      <div class="card-media">
        <a href={href(target)} class="card-link" onClick={open} aria-label={name}>
          <Art art={art} title={title} />
          {p.tag && <span class="tag">{p.tag}</span>}
          {p.isNew && <span class="tag is-new">{t('New')}</span>}
          {p.unwatched ? <span class="count-pill">{t('Unwatched')} <b>{p.unwatched}</b></span> : null}
          {p.watched && <WatchedCheck />}
        </a>
        {canPlay && <span class="card-hover" aria-hidden="true" />}
        {canPlay && <CardPlay item={item} label={playLabel(item, name)} origin={p.origin} onPlay={p.onOpen} />}
        {p.progress !== undefined && p.progress > 0 && <Progress fraction={p.progress} size={p.barSize ?? 8} class="card-bar" />}
      </div>
      {p.hoverTools}
      <Link to={target} class="card-text" tabIndex={-1} onClick={() => p.onOpen?.()}>
        <span class={titleCls}>{title}</span>
        {p.meta && <span class="card-meta">{p.meta}</span>}
      </Link>
    </div>
  );
}

/* Library grid card: hover shows mark watched, favourite, play and runtime (Library board). */
export function LibraryCard({ item, meta, onToggleWatched, onToggleFavorite, runtimeLabel, origin }: {
  item: BaseItem;
  meta?: ComponentChildren;
  onToggleWatched: () => void;
  onToggleFavorite: () => void;
  runtimeLabel?: string;
  origin?: string;
}) {
  const art = cardArt(item, 520);
  const played = !!item.UserData?.Played;
  const fav = !!item.UserData?.IsFavorite;
  const progress = progressFraction(item);
  const canPlay = canPlayCard(item);
  const count = item.Type === 'Series' ? item.UserData?.UnplayedItemCount ?? 0 : 0;
  return (
    <div class={'card lib-card' + (canPlay ? ' has-play' : '')}>
      <div class="card-media">
        <Link to={detailsPath(item)} class="lib-card-link" aria-label={item.Name}>
          <Art art={art} title={item.Name} titleClass="is-17" />
        </Link>
        {played && <WatchedCheck />}
        {count > 0 && !played && <span class="count-pill">{t('Unwatched')} <b>{count}</b></span>}
        {!played && progress > 0 && <Progress fraction={progress} size={6} class="card-bar" />}
        <div class="lib-hover">
          <div class="lib-hover-top">
            <button type="button" class="lib-tool" aria-label={played ? t('Mark as unwatched') : t('Mark as watched')} aria-pressed={played ? 'true' : 'false'} onClick={onToggleWatched}>
              <Icon name="check" size={16} stroke={2.6} />
            </button>
            <button type="button" class="lib-tool" aria-label={fav ? t('Remove from favorites') : t('Add to favorites')} aria-pressed={fav ? 'true' : 'false'} onClick={onToggleFavorite}>
              <Icon name="heart" size={16} stroke={2.2} filled={fav} />
            </button>
          </div>
          {runtimeLabel ? <span class="tag lib-runtime">{runtimeLabel}</span> : <span />}
        </div>
        {canPlay && <CardPlay item={item} label={playLabel(item, item.Name)} origin={origin} />}
      </div>
      <Link to={detailsPath(item)} class="card-text" tabIndex={-1}>
        <span class="card-title is-14 is-600">{item.Name}</span>
        {meta && <span class="card-meta">{meta}</span>}
      </Link>
    </div>
  );
}

/* ── Seerr poster (2:3). Coloured type pills only appear on these. ── */

export function SeerrStatusPill({ status }: { status: SeerrItem['status'] }) {
  if (status === 'available' || status === 'partial')
    return (
      <span class="pill is-scrim status-corner">
        <Icon name="check" size={12} stroke={3.2} color="var(--seedy-ok)" />
        {status === 'partial' ? t('Partly available') : t('Available')}
      </span>
    );
  if (status === 'pending' || status === 'processing')
    return (
      <span class="pill is-scrim status-corner">
        <span class="pip" style={{ background: 'var(--seedy-pending)' }} />
        {t('Requested')}
      </span>
    );
  return null;
}

export function TypePill({ type }: { type: 'movie' | 'tv' | 'upcoming' }) {
  const cls = type === 'movie' ? 'is-movie' : type === 'tv' ? 'is-series' : 'is-upcoming';
  return <span class={'type-pill ' + cls}>{type === 'movie' ? 'Movie' : type === 'tv' ? t('Series') : t('Upcoming')}</span>;
}

export const seerrPath = (s: Pick<SeerrItem, 'mediaType' | 'tmdbId'>) => `/request/${s.mediaType}/${s.tmdbId}`;

export function PosterCard({ item, width, canRequest, onRequest, requesting, showRequestButton, requestedLabel }: {
  item: SeerrItem;
  width?: number;
  canRequest: boolean;
  onRequest: (item: SeerrItem) => void;
  requesting?: boolean;
  /* Search shows the Request button under the poster rather than on hover. */
  showRequestButton?: boolean;
  requestedLabel?: string;
}) {
  const poster = tmdbImage(item.posterPath, 'w342');
  const inLibrary = !!item.jellyfinItemId && (item.status === 'available' || item.status === 'partial');
  const target = inLibrary ? `/details/${item.jellyfinItemId}` : seerrPath(item);
  const requestable = canRequest && (item.status === 'none' || item.status === 'declined');
  const style = width ? { flex: `0 0 ${width}px`, width: width + 'px' } : undefined;
  return (
    <div class="card poster-card" style={style}>
      <div class="card-media is-poster" tabIndex={-1}>
        <Link to={target} class="poster-link" aria-label={`${item.title}, ${item.mediaType === 'movie' ? 'movie' : 'series'}${item.year ? ', ' + item.year : ''}`}>
          {poster ? <Img src={poster} class="art" /> : <span aria-hidden="true" class="art-title">{item.title}</span>}
        </Link>
        <TypePill type={item.mediaType} />
        <SeerrStatusPill status={item.status} />
        {!showRequestButton && (
          <div class="poster-hover">
            {requestable && (
              <button type="button" class="btn btn-primary is-sm is-block" disabled={requesting} onClick={() => onRequest(item)}>
                <Icon name="plus" size={18} stroke={2.6} />
                {requesting ? t('Requesting…') : t('Request')}
              </button>
            )}
            <Link to={target} class="btn poster-more">{inLibrary ? t('Open') : t('More info')}</Link>
          </div>
        )}
      </div>
      <Link to={target} class="card-text" tabIndex={-1}>
        <span class="card-title is-14">{item.title}</span>
        <span class="card-meta is-score">
          {item.rating ? <><Icon name="star" size={13} color="var(--seedy-accent)" />{item.rating.toFixed(1)}</> : null}
          {item.rating && item.year ? ' · ' : ''}
          {item.year}
        </span>
      </Link>
      {showRequestButton && requestable && (
        <button type="button" class="btn btn-primary is-sm is-block" aria-label={`Request ${item.title} (${item.mediaType === 'movie' ? 'movie' : 'series'}${item.year ? ', ' + item.year : ''})`} disabled={requesting} onClick={() => onRequest(item)}>
          <Icon name="plus" size={18} stroke={2.6} />
          {requesting ? t('Requesting…') : t('Request')}
        </button>
      )}
      {showRequestButton && !requestable && (item.status === 'pending' || item.status === 'processing') && (
        <span class="requested-by">{requestedLabel ?? (item.requestedBy ? t('Requested by {name}', { name: item.requestedBy }) : t('Requested'))}</span>
      )}
    </div>
  );
}

/* Upcoming movies and shows: series or movie poster, green Upcoming pill, date in accent when today. */
export function UpcomingCard({ title, posterSrc, line, when, isToday, to, width = 180 }: {
  title: string; posterSrc?: string; line?: string; when: string; isToday: boolean; to: string; width?: number;
}) {
  return (
    <Link to={to} class="card poster-card" style={{ flex: `0 0 ${width}px`, width: width + 'px', gap: '10px' }}>
      <div class="card-media is-poster">
        {posterSrc ? <Img src={posterSrc} class="art" /> : <span aria-hidden="true" class="art-title">{title}</span>}
        <TypePill type="upcoming" />
      </div>
      <span class="card-text" style={{ gap: '2px' }}>
        <span class="card-title is-14">{title}</span>
        {line && <span class="card-meta" style={{ color: 'var(--seedy-text-2)' }}>{line}</span>}
        <span class="card-meta" style={{ fontWeight: 600, color: isToday ? 'var(--seedy-accent)' : 'var(--seedy-text-3)' }}>{when}</span>
      </span>
    </Link>
  );
}

/* Top 10: outlined rank tucked under the card. The link covers rank and card; the play disc sits above it. */
export function Top10Card({ rank, item, meta, onOpen }: { rank: number; item: BaseItem; meta: string; onOpen?: () => void }) {
  const art = cardArt(item, 600);
  const canPlay = canPlayCard(item);
  return (
    <div class={'top10' + (canPlay ? ' has-play' : '')}>
      <Link to={detailsPath(item)} class="top10-link" aria-label={t('Number {rank}: {name}', { rank, name: item.Name })} onClick={onOpen} />
      <span aria-hidden="true" class="top10-rank" style={{ width: rank === 10 ? '172px' : '100px' }}>{rank}</span>
      <div class="card top10-card">
        <div class="card-media">
          <Art art={art} title={item.Name} />
          {canPlay && <span class="card-hover" aria-hidden="true" />}
          {canPlay && <CardPlay item={item} label={playLabel(item, item.Name)} onPlay={onOpen} />}
        </div>
        <span class="card-text">
          <span class="card-title">{item.Name}</span>
          <span class="card-meta">{meta}</span>
        </span>
      </div>
    </div>
  );
}

/* My media: one tile per library. */
export function LibraryTile({ view, width = 300, artSrc }: { view: BaseItem; width?: number; artSrc?: string }) {
  const art = artSrc ? { src: artSrc, titleOnArt: false } : cardArt(view, 640);
  return (
    <Link to={`/library/${view.Id}`} class="lib-tile" style={{ flex: `0 0 ${width}px`, width: width + 'px' }}>
      {art.src && <Img src={art.src} class="art" />}
      <div aria-hidden="true" class="lib-tile-shade" />
      <span class="lib-tile-name">{view.Name}</span>
    </Link>
  );
}

const CAST_BG = ['#3d2424', '#26362b', '#3f3322', '#2f2840', '#1f3a3c', '#3d2436', '#2e2e2e', '#24313d'];
const CAST_INK = ['#f0b9a0', '#d9e3b5', '#f2cf86', '#e3c4ff', '#a8e6df', '#ffb3e6', '#e0e0e0', '#c9d6e3'];

export function CastCard({ person, index, role }: { person: { Id: string; Name: string; PrimaryImageTag?: string }; index: number; role?: string }) {
  const img = personImageUrl(person, 280);
  const initials = person.Name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase();
  return (
    <Link to={`/details/${person.Id}`} class="cast-card">
      <span aria-hidden="true" class="cast-face" style={{ background: CAST_BG[index % CAST_BG.length], color: CAST_INK[index % CAST_INK.length] }}>
        {img ? <Img src={img} class="art" /> : initials}
      </span>
      <span class="cast-text">
        <span class="cast-name">{person.Name}</span>
        {role && <span class="cast-role">{role}</span>}
      </span>
    </Link>
  );
}

/* Skeleton placeholders sized like the real cards. */
export function SkeletonCards({ count, width, poster }: { count: number; width: number; poster?: boolean }) {
  return (
    <>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} class="card" style={{ flex: `0 0 ${width}px`, width: width + 'px' }} aria-hidden="true">
          <div class={'card-media skel' + (poster ? ' is-poster' : '')} />
          <div class="card-text" style={{ gap: '8px' }}>
            <span class="skel skel-line" style={{ width: '70%' }} />
            <span class="skel skel-line" style={{ width: '45%' }} />
          </div>
        </div>
      ))}
    </>
  );
}
