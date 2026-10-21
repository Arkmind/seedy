import { del, get, getSession, post, request } from '../../../api/client';
import type { BaseItem, ItemKind } from '../../../api/types';
import { locale, t } from '../../../lib/i18n';

/* Jellyfin calls behind the item dialogs (Edit metadata, Identify, Edit images). All of them need an administrator. */

const uid = () => getSession()?.userId ?? '';

/* The whole item as the metadata editor sees it. Saving sends this object back, edited: whatever is left out is
   cleared on the server. */
export type ItemDto = BaseItem & Record<string, unknown> & {
  ForcedSortName?: string;
  LockedFields?: string[];
  LockData?: boolean;
  ProductionLocations?: string[];
  CustomRating?: string;
  PreferredMetadataLanguage?: string;
  PreferredMetadataCountryCode?: string;
  DisplayOrder?: string;
  DateLastRefreshed?: string;
  Path?: string;
};

export const getFullItem = (id: string, signal?: AbortSignal) => get<ItemDto>(`/Users/${uid()}/Items/${id}`, undefined, signal);
export const updateItem = (dto: ItemDto) => post<void>(`/Items/${dto.Id}`, dto);

export interface ExternalIdInfo { Name: string; Key: string; Type?: string | null; UrlFormatString?: string | null }
export interface MetadataEditorInfo {
  ParentalRatingOptions: { Name: string; Value?: number | null }[];
  Countries: { Name: string; DisplayName: string; TwoLetterISORegionName: string }[];
  Cultures: { Name: string; DisplayName: string; TwoLetterISOLanguageName: string; ThreeLetterISOLanguageName: string }[];
  ExternalIdInfos: ExternalIdInfo[];
}
export const getMetadataEditor = (id: string, signal?: AbortSignal) => get<MetadataEditorInfo>(`/Items/${id}/MetadataEditor`, undefined, signal);
export const getExternalIdInfos = (id: string, signal?: AbortSignal) => get<ExternalIdInfo[]>(`/Items/${id}/ExternalIdInfos`, undefined, signal);

/* ── Identify ── */

export interface RemoteSearchResult {
  Name?: string;
  ProviderIds?: Record<string, string>;
  ProductionYear?: number;
  IndexNumber?: number;
  IndexNumberEnd?: number;
  ParentIndexNumber?: number;
  PremiereDate?: string;
  ImageUrl?: string;
  SearchProviderName?: string;
  Overview?: string;
  AlbumArtist?: RemoteSearchResult;
  Artists?: RemoteSearchResult[];
}

/* The item types Jellyfin can look up, by the path of their search. */
const SEARCHABLE: Partial<Record<ItemKind, string>> = {
  Movie: 'Movie', Series: 'Series', BoxSet: 'BoxSet', MusicAlbum: 'MusicAlbum', MusicArtist: 'MusicArtist',
  Person: 'Person', Book: 'Book', MusicVideo: 'MusicVideo', Trailer: 'Trailer'
};
export const searchKind = (type: ItemKind) => SEARCHABLE[type];

export const remoteSearch = (item: Pick<BaseItem, 'Id' | 'Type'>, info: { Name?: string; Year?: number; ProviderIds: Record<string, string> }) =>
  post<RemoteSearchResult[]>(`/Items/RemoteSearch/${SEARCHABLE[item.Type] ?? item.Type}`, {
    SearchInfo: { Name: info.Name, Year: info.Year, ProviderIds: info.ProviderIds },
    ItemId: item.Id,
    IncludeDisabledProviders: true
  });

export const applyRemoteSearch = (id: string, result: RemoteSearchResult, replaceAllImages: boolean) =>
  post<void>(`/Items/RemoteSearch/Apply/${id}`, result, { replaceAllImages });

/* ── Images ── */

export type ImageKind = 'Primary' | 'Backdrop' | 'Logo' | 'Thumb' | 'Banner' | 'Art' | 'Disc' | 'Box' | 'Screenshot' | 'Menu' | 'BoxRear';

export interface ImageInfo { ImageType: ImageKind; ImageIndex?: number; ImageTag?: string; Path?: string; Width?: number; Height?: number; Size?: number }
export interface RemoteImageInfo {
  ProviderName?: string; Url?: string; ThumbnailUrl?: string; Width?: number; Height?: number;
  CommunityRating?: number; VoteCount?: number; Language?: string | null; Type?: ImageKind;
}
export interface RemoteImageResult { Images: RemoteImageInfo[]; TotalRecordCount: number; Providers: string[] }
export interface ImageProviderInfo { Name: string; SupportedImages: ImageKind[] }

export const getItemImages = (id: string, signal?: AbortSignal) => get<ImageInfo[]>(`/Items/${id}/Images`, undefined, signal);
export const getRemoteImages = (id: string, type: ImageKind, signal?: AbortSignal) =>
  get<RemoteImageResult>(`/Items/${id}/RemoteImages`, { type, includeAllLanguages: true }, signal);
export const getRemoteImageProviders = (id: string, signal?: AbortSignal) => get<ImageProviderInfo[]>(`/Items/${id}/RemoteImages/Providers`, undefined, signal);
export const downloadRemoteImage = (id: string, type: ImageKind, imageUrl: string) =>
  post<void>(`/Items/${id}/RemoteImages/Download`, undefined, { type, imageUrl });
export const deleteImage = (id: string, type: ImageKind, index?: number) =>
  del<void>(`/Items/${id}/Images/${type}${index !== undefined ? '/' + index : ''}`);

/* Jellyfin reads an uploaded image as base64 text, sent with the image's own content type. */
export async function uploadImage(id: string, type: ImageKind, file: File) {
  const dataUrl = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(reader.error ?? new Error('Unreadable file'));
    reader.readAsDataURL(file);
  });
  const base64 = dataUrl.slice(dataUrl.indexOf(',') + 1);
  return request<void>('POST', `/Items/${id}/Images/${type}`, { data: { type: file.type || 'image/jpeg', body: base64 } });
}

/* ── Names ── */

/* Providers by the short names the boards use. */
const PROVIDER_SHORT: Record<string, string> = {
  TheMovieDb: 'TMDb', 'The Movie Database': 'TMDb', TheTVDB: 'TVDb', TvdbPlugin: 'TVDb',
  'The Open Movie Database': 'OMDb', FanArt: 'Fanart', 'Fanart.tv': 'Fanart', TheAudioDB: 'AudioDB', 'Screen Grabber': 'Screen grabber'
};
export const providerName = (name?: string) => (name ? PROVIDER_SHORT[name] ?? name : '');

/* Provider id keys ("Tmdb", "Imdb") the way people write them. */
const ID_SHORT: Record<string, string> = {
  Tmdb: 'TMDb', Tvdb: 'TVDb', Imdb: 'IMDb', TmdbCollection: 'TMDb collection', Zap2It: 'Zap2It', Tvcom: 'TV.com',
  MusicBrainzAlbum: 'MusicBrainz album', MusicBrainzAlbumArtist: 'MusicBrainz album artist', MusicBrainzArtist: 'MusicBrainz artist',
  MusicBrainzReleaseGroup: 'MusicBrainz release group', MusicBrainzTrack: 'MusicBrainz track', AudioDbAlbum: 'AudioDB album', AudioDbArtist: 'AudioDB artist'
};
export const idName = (key: string) => ID_SHORT[key] ?? key;

/* The order Seedy lists ids in: TMDb, TVDb, IMDb, then the rest as the server gives them. */
const ID_ORDER = ['Tmdb', 'Tvdb', 'Imdb'];
const idRank = (key: string) => { const i = ID_ORDER.indexOf(key); return i < 0 ? ID_ORDER.length : i; };

export const sortIds = (keys: string[]) => keys.map((k, at) => ({ k, at })).sort((a, b) => idRank(a.k) - idRank(b.k) || a.at - b.at).map((x) => x.k);

/* ExternalIdInfo.Type → the item types it is for (no type: every type). */
const ID_TYPE: Record<string, ItemKind[]> = {
  Movie: ['Movie', 'Trailer', 'MusicVideo'], Series: ['Series'], Season: ['Season'], Episode: ['Episode'], BoxSet: ['BoxSet'],
  Person: ['Person'], Book: ['Book'], Album: ['MusicAlbum'], AlbumArtist: ['MusicAlbum', 'MusicArtist'], Artist: ['MusicArtist', 'Audio'],
  OtherArtist: ['MusicArtist'], ReleaseGroup: ['MusicAlbum'], Track: ['Audio'], Recording: ['Audio']
};

/* The ids that apply to this item, in Seedy's order, once each. */
export function idsFor(infos: ExternalIdInfo[], type: ItemKind): ExternalIdInfo[] {
  const seen = new Set<string>();
  return infos
    .filter((i) => !i.Type || (ID_TYPE[i.Type] ?? []).includes(type))
    .filter((i) => (seen.has(i.Key) ? false : (seen.add(i.Key), true)))
    .map((info, at) => ({ info, at }))
    .sort((a, b) => idRank(a.info.Key) - idRank(b.info.Key) || a.at - b.at)
    .map((x) => x.info);
}

/* "TMDb 219408 · TVDb 402117". */
export const idsLine = (ids?: Record<string, string>) =>
  Object.entries(ids ?? {})
    .filter(([, v]) => v)
    .sort((a, b) => idRank(a[0]) - idRank(b[0]))
    .map(([k, v]) => `${idName(k)} ${v}`)
    .join(' · ');

export function languageName(code?: string | null): string {
  if (!code) return t('no text');
  try {
    const name = new Intl.DisplayNames([locale()], { type: 'language' }).of(code);
    if (name && name !== code) return name.charAt(0).toLocaleUpperCase(locale()) + name.slice(1);
  } catch { /* not a code Intl knows */ }
  return code;
}

/* "Series", "Movie"… for the dialogs' sub lines. */
export function typeName(type: ItemKind): string {
  switch (type) {
    case 'Movie': return t('Movie');
    case 'Series': return t('Series');
    case 'Season': return t('Season');
    case 'Episode': return t('Episode');
    case 'BoxSet': return t('Collection');
    case 'MusicAlbum': return t('Album');
    case 'MusicArtist': return t('Artist');
    case 'Audio': return t('Song');
    case 'MusicVideo': return t('Music video');
    case 'Person': return t('Person');
    case 'Book': return t('Book');
    case 'Trailer': return t('Trailer');
    case 'Playlist': return t('Playlist');
    default: return type;
  }
}

/* Smaller art for the remote previews: TMDb serves any size from the same path. */
export function previewUrl(src?: string, width: 'w342' | 'w780' = 'w342'): string | undefined {
  if (!src) return undefined;
  return src.replace(/(image\.tmdb\.org\/t\/p\/)original\//, `$1${width}/`);
}
