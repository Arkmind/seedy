import { SERVER_URL, getSession } from './client';
import type { BaseItem, User } from './types';

type ImageType = 'Primary' | 'Backdrop' | 'Thumb' | 'Logo' | 'Banner' | 'Art' | 'Chapter';

export function imageUrl(itemId: string, type: ImageType, opts: { tag?: string; maxWidth?: number; index?: number; quality?: number } = {}): string {
  const params = new URLSearchParams();
  if (opts.tag) params.set('tag', opts.tag);
  if (opts.maxWidth) params.set('maxWidth', String(Math.round(opts.maxWidth * (window.devicePixelRatio > 1 ? 1.5 : 1))));
  params.set('quality', String(opts.quality ?? 90));
  const idx = opts.index !== undefined ? `/${opts.index}` : '';
  return `${SERVER_URL}/Items/${itemId}/Images/${type}${idx}?${params.toString()}`;
}

export function backdropUrl(item: BaseItem, maxWidth = 1920): string | undefined {
  if (item.BackdropImageTags?.length) return imageUrl(item.Id, 'Backdrop', { tag: item.BackdropImageTags[0], maxWidth, index: 0 });
  if (item.ParentBackdropItemId && item.ParentBackdropImageTags?.length)
    return imageUrl(item.ParentBackdropItemId, 'Backdrop', { tag: item.ParentBackdropImageTags[0], maxWidth, index: 0 });
  if (item.ImageTags?.Thumb) return imageUrl(item.Id, 'Thumb', { tag: item.ImageTags.Thumb, maxWidth });
  return undefined;
}

export function logoUrl(item: BaseItem, maxWidth = 600): string | undefined {
  if (item.ImageTags?.Logo) return imageUrl(item.Id, 'Logo', { tag: item.ImageTags.Logo, maxWidth, quality: 100 });
  if (item.ParentLogoItemId && item.ParentLogoImageTag) return imageUrl(item.ParentLogoItemId, 'Logo', { tag: item.ParentLogoImageTag, maxWidth, quality: 100 });
  return undefined;
}

export function primaryUrl(item: BaseItem, maxWidth = 400): string | undefined {
  if (item.ImageTags?.Primary) return imageUrl(item.Id, 'Primary', { tag: item.ImageTags.Primary, maxWidth });
  if (item.Type === 'Episode' && item.SeriesId && item.SeriesPrimaryImageTag) return imageUrl(item.SeriesId, 'Primary', { tag: item.SeriesPrimaryImageTag, maxWidth });
  if (item.AlbumId && item.AlbumPrimaryImageTag) return imageUrl(item.AlbumId, 'Primary', { tag: item.AlbumPrimaryImageTag, maxWidth });
  if (item.ParentPrimaryImageItemId && item.ParentPrimaryImageTag) return imageUrl(item.ParentPrimaryImageItemId, 'Primary', { tag: item.ParentPrimaryImageTag, maxWidth });
  return undefined;
}

/* Series poster for an episode (Upcoming shows uses the show's poster, not the episode still). */
export function seriesPosterUrl(item: BaseItem, maxWidth = 360): string | undefined {
  if (item.SeriesId && item.SeriesPrimaryImageTag) return imageUrl(item.SeriesId, 'Primary', { tag: item.SeriesPrimaryImageTag, maxWidth });
  return primaryUrl(item, maxWidth);
}

export interface CardArt {
  src?: string;
  /* Square or portrait art shown whole over a blurred copy of itself. */
  contain?: boolean;
  logo?: string;
  /* Set the title on the artwork when the art carries no title of its own. */
  titleOnArt: boolean;
}

/* 16:9 art. Thumbs already carry the title; backdrops get the logo, else the title in type. */
export function cardArt(item: BaseItem, width = 600): CardArt {
  if (item.Type === 'Episode') {
    if (item.ImageTags?.Primary) return { src: imageUrl(item.Id, 'Primary', { tag: item.ImageTags.Primary, maxWidth: width }), titleOnArt: false };
    if (item.ParentThumbItemId && item.ParentThumbImageTag) return { src: imageUrl(item.ParentThumbItemId, 'Thumb', { tag: item.ParentThumbImageTag, maxWidth: width }), titleOnArt: false };
    const bd = backdropUrl(item, width);
    return { src: bd, titleOnArt: !bd };
  }
  if (item.Type === 'MusicAlbum' || item.Type === 'Audio' || item.Type === 'MusicArtist' || item.Type === 'Playlist') {
    const p = primaryUrl(item, width);
    return { src: p, contain: !!p, titleOnArt: !p };
  }
  if (item.ImageTags?.Thumb) return { src: imageUrl(item.Id, 'Thumb', { tag: item.ImageTags.Thumb, maxWidth: width }), titleOnArt: false };
  if (item.ParentThumbItemId && item.ParentThumbImageTag && item.Type === 'Season')
    return { src: imageUrl(item.ParentThumbItemId, 'Thumb', { tag: item.ParentThumbImageTag, maxWidth: width }), titleOnArt: false };
  const bd = backdropUrl(item, width);
  if (bd) {
    const logo = logoUrl(item, Math.round(width * 0.6));
    return { src: bd, logo, titleOnArt: !logo };
  }
  const p = primaryUrl(item, width);
  if (p) return { src: p, contain: (item.PrimaryImageAspectRatio ?? 0.67) < 1.2, titleOnArt: true };
  return { titleOnArt: true };
}

export function personImageUrl(person: { Id: string; PrimaryImageTag?: string }, maxWidth = 300): string | undefined {
  return person.PrimaryImageTag ? imageUrl(person.Id, 'Primary', { tag: person.PrimaryImageTag, maxWidth }) : undefined;
}

export function userImageUrl(user: Pick<User, 'Id' | 'PrimaryImageTag'>, maxWidth = 120): string | undefined {
  if (!user.PrimaryImageTag) return undefined;
  return `${SERVER_URL}/Users/${user.Id}/Images/Primary?tag=${user.PrimaryImageTag}&maxWidth=${maxWidth}&quality=90`;
}

export function chapterImageUrl(itemId: string, index: number, tag: string, maxWidth = 320): string {
  return imageUrl(itemId, 'Chapter', { tag, index, maxWidth });
}

/* Trickplay tiles need auth in Jellyfin 12, and <img> can't send headers, so the token rides as ApiKey. */
export function trickplayTileUrl(itemId: string, width: number, index: number, mediaSourceId?: string): string {
  const q = new URLSearchParams();
  if (mediaSourceId) q.set('mediaSourceId', mediaSourceId);
  const token = getSession()?.token;
  if (token) q.set('ApiKey', token);
  return `${SERVER_URL}/Videos/${itemId}/Trickplay/${width}/${index}.jpg?${q.toString()}`;
}

/* TMDB artwork for Seerr titles. */
export function tmdbImage(path: string | undefined | null, size: 'w342' | 'w500' | 'w780' | 'w1280' | 'original' | 'w300_and_h450_face' | 'w185' = 'w342'): string | undefined {
  if (!path) return undefined;
  if (/^https?:/.test(path)) return path;
  return `https://image.tmdb.org/t/p/${size}${path}`;
}
