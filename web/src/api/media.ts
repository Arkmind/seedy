import { get, post, del } from './client';
import type { AdminConfig } from './seedy';

/* Music and book requests (Jellyfin.Plugin.Seedy · MediaRequestsController, AdminMediaController). Seedy keeps its own
   queue for these: people ask, admins approve, and Seedy sends approved requests to Lidarr (music), Shelfarr, Librarr
   or a Readarr-API app (books), then follows them there. Paths live under /Seedy/MediaRequests so none equals a page. */

export type MediaKind = 'music' | 'book';
export type MediaService = 'lidarr' | 'shelfarr' | 'librarr' | 'readarr';
export type BookFormat = 'ebook' | 'audiobook' | 'comic';
export type MediaStatus = 'pending' | 'approved' | 'searching' | 'downloading' | 'ready' | 'failed' | 'declined' | 'cancelled';
/* What a card shows: on the server, asked for, waiting for an admin, or downloading. */
export type CardStatus = 'none' | 'server' | 'requested' | 'waiting' | 'downloading';

export interface MediaFeatures {
  music: boolean;
  books: boolean;
  formats: BookFormat[];
  /* Which app each book format goes to. */
  bookRoutes?: Partial<Record<BookFormat, MediaService>>;
  /* "Open in …" addresses, for admins. */
  lidarrUrl?: string;
  shelfarrUrl?: string;
  librarrUrl?: string;
  readarrUrl?: string;
}

export interface AlbumRef { mbid: string; title: string; type?: string; year?: number }

/* The line under a request, worded by the page. */
export interface MediaNote {
  code: 'albums' | 'noCopy' | 'found' | 'notFound' | 'purchase' | 'service' | 'unreachable' | 'future' | 'scanning' | 'gone';
  n?: number;
  total?: number;
  days?: number;
  name?: string;
  fraction?: number;
  text?: string;
}

export interface MediaRequest {
  id: string;
  kind: MediaKind;
  service: MediaService;
  /* artist = whole discography, albums = picked albums, future = new releases only, book. */
  mode: 'artist' | 'albums' | 'future' | 'book';
  foreignId: string;
  title: string;
  subtitle?: string;
  coverUrl?: string;
  albums: AlbumRef[];
  formats: BookFormat[];
  content?: 'book' | 'comic';
  series?: string;
  seriesIndex?: number;
  language?: string;
  keepNew: boolean;
  units: number;
  requestedBy: string;
  requestedByMe: boolean;
  requestedAt: string;
  updatedAt: string;
  decidedAt?: string;
  readyAt?: string;
  status: MediaStatus;
  attention: boolean;
  note?: MediaNote | null;
  progress?: number | null;
  parts: { format: string; service: MediaService; status: MediaStatus; progress?: number; itemId?: string }[];
  itemId?: string | null;
  lowerQuality?: string | null;
  canSearch: boolean;
  canCancel: boolean;
  canDecide: boolean;
}

export interface MediaSummary {
  music: { enabled: boolean; open: number; pending: number };
  books: { enabled: boolean; open: number; pending: number };
  canManage: boolean;
}

export interface MediaOptions {
  kind: MediaKind;
  canRequest: boolean;
  /* -1 = no limit, 0 = can't request. */
  limit: number;
  used: number;
  period: 'week' | 'month';
  autoApprove: boolean;
  quality?: string | null;
  formats: { format: BookFormat | 'music'; service: MediaService; library?: string | null }[];
  scanOnReady: boolean;
}

export interface MusicRelease {
  mbid: string;
  artistMbid?: string;
  title: string;
  artist?: string;
  type: 'album' | 'ep' | 'single';
  date?: string;
  coverUrl?: string;
  upcoming: boolean;
  status: CardStatus;
  requestedBy?: string | null;
  itemId?: string | null;
  have?: number;
  of?: number;
  tracks?: number;
  lengthMs?: number;
}

export interface MusicArtistRef { mbid: string; name: string; disambiguation?: string; type?: string; area?: string; imageUrl?: string; itemId?: string }
export interface MusicArtist { artist: MusicArtistRef; releases: MusicRelease[]; albumsOnServer: number; watching: boolean }

export interface BookCard {
  workId: string;
  title: string;
  author?: string;
  year?: number;
  releaseDate?: string;
  coverUrl?: string;
  series?: string;
  seriesIndex?: number;
  content: 'book' | 'comic';
  formats: BookFormat[];
  upcoming: boolean;
  rating?: number;
  pages?: number;
  description?: string;
  languages: string[];
  status: CardStatus;
  requestedBy?: string | null;
  itemId?: string | null;
  onServer: BookFormat[];
  readFrom?: number;
  readTo?: number;
}

export type MusicRow = 'new' | 'missing' | 'trending' | 'soon';
export type BookRow = 'next' | 'audiobooks' | 'trending';

export interface MediaCreate {
  kind: MediaKind;
  mode: MediaRequest['mode'];
  foreignId: string;
  title: string;
  subtitle?: string;
  coverUrl?: string;
  albums?: AlbumRef[];
  keepNew?: boolean;
  albumCount?: number;
  formats?: BookFormat[];
  content?: 'book' | 'comic';
  series?: string;
  seriesIndex?: number;
  year?: number;
  language?: string;
}

const M = '/Seedy/MediaRequests';
export const mediaSummary = () => get<MediaSummary>(`${M}/Summary`);
export const mediaList = (scope: 'mine' | 'all', kind?: MediaKind) => get<{ results: MediaRequest[]; total: number }>(M, { scope, kind });
export const mediaOptions = (kind: MediaKind) => get<MediaOptions>(`${M}/Options/${kind}`);
export const mediaCreate = (body: MediaCreate) => post<MediaRequest>(M, body);
export const mediaApprove = (id: string) => post<MediaRequest>(`${M}/${id}/Approve`);
export const mediaDecline = (id: string) => post<MediaRequest>(`${M}/${id}/Decline`);
export const mediaSearchNow = (id: string) => post<MediaRequest>(`${M}/${id}/Search`);
export const mediaAcceptLower = (id: string) => post<MediaRequest>(`${M}/${id}/AcceptLower`);
export const mediaRemove = (id: string) => del<void>(`${M}/${id}`);
export const musicRow = (row: MusicRow) => get<{ results: MusicRelease[]; source?: string }>(`${M}/Music/Rows/${row}`);
export const musicSearch = (query: string) => get<{ artists: MusicArtistRef[]; albums: MusicRelease[] }>(`${M}/Music/Search`, { query });
export const musicArtist = (mbid: string) => get<MusicArtist>(`${M}/Music/Artists/${mbid}`);
export const bookRow = (row: BookRow) => get<{ results: BookCard[]; source?: string }>(`${M}/Books/Rows/${row}`);
export const bookSearch = (query: string) => get<{ results: BookCard[] }>(`${M}/Books/Search`, { query });
export const bookDetails = (card: BookCard) => post<BookCard>(`${M}/Books/Details`, card);

/* ── Admin ── */

export interface AdminBookApp { enabled: boolean; url: string; apiKeyHint?: string }
export interface AdminMedia {
  lidarr: { url: string; apiKeyHint?: string; qualityProfileId: number; metadataProfileId: number; rootFolder: string; wholeArtist: 'all' | 'future'; searchOnApprove: boolean };
  shelfarr: AdminBookApp;
  librarr: AdminBookApp;
  readarr: AdminBookApp;
  routes: { ebooks: MediaService | 'off'; audiobooks: MediaService | 'off'; comics: MediaService | 'off' };
  scanOnReady: boolean;
  notifyOnReady: boolean;
  showRequesters: boolean;
}
export interface MediaTestResult { service: MediaService; ok: boolean; message: string; version?: string; counts: Record<string, number> }
export interface AdminMediaInfo {
  qualityProfiles: { id: number; name: string }[];
  metadataProfiles: { id: number; name: string }[];
  rootFolders: { path: string; library?: string; freeSpace?: number }[];
  lidarrError?: string;
  landing: { format: BookFormat; library?: string; path?: string }[];
}
export interface AdminMediaProfile {
  userId: string;
  name: string;
  isAdmin: boolean;
  maxParentalRating?: string;
  imageTag?: string;
  musicPerWeek: number;
  booksPerMonth: number;
  autoApprove: boolean;
}

const A = '/Seedy/Admin/MediaRequests';
export const adminMediaInfo = () => get<AdminMediaInfo>(`${A}/Info`);
export const adminMediaTest = (service: MediaService, body: { url?: string; apiKey?: string } = {}) => post<MediaTestResult>(`${A}/Test/${service}`, body);
export const adminMediaKey = (service: MediaService, apiKey: string) => post<AdminConfig>(`${A}/Key`, { service, apiKey });
export const adminMediaProfiles = () => get<AdminMediaProfile[]>(`${A}/Profiles`);
export const adminMediaProfile = (userId: string, body: { musicPerWeek?: number; booksPerMonth?: number; autoApprove?: boolean }) =>
  post<AdminMediaProfile[]>(`${A}/Profiles/${userId}`, body);
