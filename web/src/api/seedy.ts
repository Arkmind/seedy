import { SERVER_URL, get, post, del, request, url, type Query } from './client';
import type { BaseItem } from './types';

/* Endpoints served by the Seedy plugin (Jellyfin.Plugin.Seedy, routes under /Seedy).
   API paths must never equal an app page path: routes are case-insensitive, so GET /Seedy/History
   would shadow the /seedy/history page. History therefore lives under /Seedy/Plays. */

export type SkipMode = 'button' | 'auto' | 'off';
export type MaxQuality = 'auto' | '4k' | '1080p' | '720p';

export interface PlaybackPrefs {
  skipIntro: SkipMode;
  skipRecap: SkipMode;
  skipCredits: SkipMode;
  autoPlayNext: boolean;
  upNextSeconds: number;
  moviePicksSeconds: number;
  seekBackSeconds: number;
  seekForwardSeconds: number;
  /* 0 = never show the pause screen. */
  pauseScreenSeconds: number;
  maxQuality: MaxQuality;
}

export interface SubtitlePrefs { size: 'S' | 'M' | 'L'; lift: boolean }

/* A row on the person's own home screen (Settings · Home screen). Rows the admin switched off stay off. */
export interface HomeRowPref { kind: RowKind; enabled: boolean; count?: number; pinned?: boolean }

export interface PrivacyPrefs {
  /* Because you watched, genre rows and row order learn from this person's plays. */
  personalRecs: boolean;
  /* This person's plays count toward the server's Top 10. */
  countInTop10: boolean;
}

export interface UserPrefs {
  playback: PlaybackPrefs;
  subtitles: SubtitlePrefs;
  adaptRowOrder: boolean;
  hiddenRows: string[];
  hiddenSeeds: string[];
  volume?: number;
  /* 'auto' learns the order from plays (pinned rows stay put); 'mine' uses `rows` as given. */
  rowOrder: 'auto' | 'mine';
  /* The person's rows; empty until they change something, meaning "as the admin set it". */
  rows: HomeRowPref[];
  privacy: PrivacyPrefs;
  /* A notice on Home when something this person requested arrives. */
  notifyRequests: boolean;
  /* Other settings that follow the profile (display, playback extras…). Keys are owned by the page that writes them;
     a patch merges keys, and a null value removes one. */
  custom: Record<string, unknown>;
}

export interface RequestsFeatures {
  connected: boolean;
  discover: boolean;
  upcomingMovies: boolean;
  requestButtons: boolean;
  requestInSearch: boolean;
  canRequest: boolean;
  allow4k: boolean;
  seerrUrl?: string;
  /** The person's own Seerr account; linkedBy "jellyfin" = Seerr's Jellyfin sign-in, "manual" = an admin linked it. */
  account?: { name: string; linkedBy: 'jellyfin' | 'manual' } | null;
}

export interface Branding { name: string; accent: string; hasLogo: boolean; logoVersion: string; signInBackground: 'backdrops' | 'plain' }

export interface Me {
  isAdmin: boolean;
  branding: Branding;
  prefs: UserPrefs;
  defaults: PlaybackPrefs;
  requests: RequestsFeatures;
  historyEnabled: boolean;
  /** The admin's Home rows as this person can have them (rows they can't get are switched off). */
  homeRows?: HomeRowSetting[];
  pluginVersion: string;
}

export type RowKind =
  | 'featured' | 'continue' | 'nextup' | 'top10' | 'because' | 'genres' | 'mymedia' | 'latest'
  | 'discover' | 'upcomingMovies' | 'upcomingShows' | 'collections';

export interface HomeRow {
  id: string;
  kind: RowKind;
  title?: string;
  seedId?: string;
  seedName?: string;
  genre?: string;
  libraryId?: string;
  libraryName?: string;
  collectionType?: string;
  collectionId?: string;
}

export interface FeaturedEntry { item: BaseItem; reason: string; play?: BaseItem }
export interface Top10Entry { item: BaseItem; profiles: number; plays: number }

/* The trailer behind a title in the Home carousel (GET /Seedy/Items/{id}/Trailer; 204 when there's none).
   Local trailer files come first, then YouTube: Jellyfin's remote trailers (from TMDB), then TMDB's list through Seerr.
   `alternates` are further YouTube trailers to try when one can't play (embedding off, removed). */
export interface TrailerAlternate { key: string; name?: string }
export type TrailerInfo =
  | { source: 'local'; itemId: string; name?: string; from?: string; alternates: TrailerAlternate[] }
  | { source: 'youtube'; key: string; name?: string; from?: 'jellyfin' | 'tmdb'; alternates: TrailerAlternate[] };

export interface HistoryEntry {
  id: string;
  itemId: string;
  titleId: string;
  item?: BaseItem;
  startedAt: string;
  stoppedAt?: string;
  positionTicks: number;
  runtimeTicks: number;
  finished: boolean;
  deviceName?: string;
  client?: string;
  hidden: boolean;
}

export type SeerrStatus = 'none' | 'pending' | 'processing' | 'partial' | 'available' | 'declined' | 'blocklisted';

export interface SeerrItem {
  tmdbId: number;
  mediaType: 'movie' | 'tv';
  title: string;
  year?: string;
  rating?: number;
  posterPath?: string;
  backdropPath?: string;
  overview?: string;
  status: SeerrStatus;
  requestedBy?: string;
  requestedByMe?: boolean;
  jellyfinItemId?: string;
}

export interface SeerrUpcoming extends SeerrItem { releaseDate: string; releaseKind?: 'digital' | 'physical' | 'theatrical' }

export type RequestStatus = 'pending' | 'approved' | 'declined' | 'failed' | 'completed';

/* One of the signed-in person's own Seerr requests. `status` is the title's availability, as on posters. */
export interface MyRequest extends SeerrItem {
  requestId: number;
  requestedAt: string;
  requestStatus: RequestStatus;
  seasons?: number[];
  is4k?: boolean;
  /* When the title became available, if it has. */
  availableAt?: string;
}

export interface SeerrSeason { seasonNumber: number; name: string; episodeCount: number; airDate?: string; status: SeerrStatus }

export interface SeerrDetails extends SeerrItem {
  tagline?: string;
  genres: string[];
  runtime?: number;
  releaseDate?: string;
  certification?: string;
  cast: { id: number; name: string; character?: string; profilePath?: string }[];
  crew: { name: string; job: string }[];
  seasons?: SeerrSeason[];
  networks?: string[];
  numberOfSeasons?: number;
  trailerUrl?: string;
  status4k?: SeerrStatus;
}

/* ── Admin ── */

export interface HomeRowSetting { kind: RowKind; enabled: boolean; count?: number }

export interface AdminConfig {
  opensAt: 'alongside' | 'replace';
  branding: Branding & { logoFileName?: string };
  playbackDefaults: PlaybackPrefs;
  home: {
    rows: HomeRowSetting[];
    adaptOrder: boolean;
    because: { watchedWhen: 'finished' | 'half'; lookBackDays: number; minTitles: number };
    top10: { periodDays: number; minProfiles: number };
    genres: { windowDays: number; starting: string[] };
    featured: { mode: 'auto' | 'picks' | 'both'; picks: string[] };
  };
  requests: {
    url: string;
    apiKeyHint?: string;
    showDiscover: boolean;
    showUpcomingMovies: boolean;
    showRequestButtons: boolean;
    showRequestInSearch: boolean;
    upcomingSource: 'requests' | 'requestsAndRadarr';
    who: 'everyone' | 'selected';
    allow4k: boolean;
  };
  playLog: {
    enabled: boolean;
    retentionDays: number;
    useTop10: boolean;
    useHistory: boolean;
    useRowOrder: boolean;
    useGenres: boolean;
  };
}

export interface AdminStatus {
  pluginVersion: string;
  latestVersion?: string;
  upToDate?: boolean;
  serverVersion: string;
  serverSupported: boolean;
  /** When the Jellyfin process started (ISO). */
  serverStartedAt?: string;
  /** "Linux", "Windows", "macOS"… */
  os?: string;
  /** The server clock's UTC offset in minutes (east positive); scheduled tasks run on it. */
  utcOffsetMinutes?: number;
  seerr: { state: 'connected' | 'error' | 'off'; message?: string; checkedAt?: string };
  playLog: { enabled: boolean; retentionDays: number; plays: number };
}

export interface SeerrTestResult { ok: boolean; message: string; version?: string; radarr: boolean; sonarr: boolean }

export interface SeerrAccount {
  userId: string;
  name: string;
  isAdmin: boolean;
  maxParentalRating?: string;
  hasImage: boolean;
  imageTag?: string;
  seerrUserId?: number;
  seerrName?: string;
  matchedBy?: 'jellyfin' | 'manual';
  canRequest: boolean;
}

export interface SeerrUser { id: number; displayName: string; email?: string; jellyfinUserId?: string; avatar?: string }

export interface PlayLogProfile { userId: string; name: string; imageTag?: string; plays: number; top10: boolean; history: boolean }

/* ── Calls ── */

export const getClientBranding = () => request<Branding>('GET', '/Seedy/Client', { token: null });
export const signInBackdrops = () => request<string[]>('GET', '/Seedy/Client/Backdrops', { token: null });
export const logoUrl = (version: string) => url('/Seedy/Client/Logo', { v: version });
/** The tab icon: the uploaded logo, or the brand mark (the name's first letter). */
export const faviconUrl = (b: Pick<Branding, 'logoVersion' | 'name'>) =>
  `${SERVER_URL}/Seedy/Client/Favicon?v=${encodeURIComponent(`${b.logoVersion}-${b.name}`)}`; // as the plugin writes it in index.html

export const getMe = () => get<Me>('/Seedy/Me');
/** When the person's password last changed (from the activity log); null when unknown. */
export const passwordState = () => get<{ changedAt: string | null }>('/Seedy/Me/Password');
export const savePrefs = (prefs: Partial<UserPrefs>) => post<UserPrefs>('/Seedy/Me/Prefs', prefs);

export const getHome = () => get<{ rows: HomeRow[] }>('/Seedy/Home');
export const getFeatured = () => get<FeaturedEntry[]>('/Seedy/Home/Featured');
export const getTop10 = () => get<{ items: Top10Entry[]; quiet: boolean }>('/Seedy/Home/Top10');
export const recordOrigin = (itemId: string, row: string) => post<void>('/Seedy/Plays/Origin', { itemId, row });
export const getTrailer = (itemId: string) => get<TrailerInfo | undefined>(`/Seedy/Items/${itemId}/Trailer`);

export const getHistory = (q: { startIndex?: number; limit?: number; type?: 'all' | 'movies' | 'shows'; search?: string; includeHidden?: boolean }) =>
  get<{ items: HistoryEntry[]; total: number }>('/Seedy/Plays', q as Query);
export const hideHistoryTitle = (titleId: string, hidden: boolean) => post<void>(`/Seedy/Plays/Titles/${titleId}/Hidden`, { hidden });
export const removeHistoryEntry = (id: string) => del<void>(`/Seedy/Plays/${id}`);
export const clearHistory = () => del<void>('/Seedy/Plays');

export const seerrDiscover = (page = 1) => get<{ results: SeerrItem[] }>('/Seedy/Requests/Discover', { page });
export const seerrSearch = (query: string, page = 1) => get<{ results: SeerrItem[]; total: number }>('/Seedy/Requests/Search', { query, page });
export const seerrUpcoming = () => get<{ results: SeerrUpcoming[] }>('/Seedy/Requests/Upcoming');
export const seerrDetails = (mediaType: 'movie' | 'tv', tmdbId: number) => get<SeerrDetails>(`/Seedy/Requests/Media/${mediaType}/${tmdbId}`);
/* The person's requests, newest first; `open` counts those still waiting or downloading. */
export const myRequests = () => get<{ results: MyRequest[]; open: number }>('/Seedy/Requests/Mine');
export const seerrRequest = (body: { mediaType: 'movie' | 'tv'; tmdbId: number; seasons?: number[]; is4k?: boolean }) =>
  post<{ status: SeerrStatus }>('/Seedy/Requests', body);

export const adminGetConfig = () => get<AdminConfig>('/Seedy/Admin/Config');
export const adminSaveConfig = (cfg: AdminConfig) => post<AdminConfig>('/Seedy/Admin/Config', cfg);
export const adminStatus = () => get<AdminStatus>('/Seedy/Admin/Status');
export const adminUploadLogo = (file: File) => {
  const form = new FormData();
  form.append('file', file);
  return request<AdminConfig>('POST', '/Seedy/Admin/Logo', { form });
};
export const adminRemoveLogo = () => del<AdminConfig>('/Seedy/Admin/Logo');
export const adminSeerrTest = (body: { url?: string; apiKey?: string }) => post<SeerrTestResult>('/Seedy/Admin/Seerr/Test', body);
export const adminSeerrSetKey = (apiKey: string) => post<AdminConfig>('/Seedy/Admin/Seerr/Key', { apiKey });
export const adminSeerrAccounts = () => get<SeerrAccount[]>('/Seedy/Admin/Seerr/Accounts');
export const adminSeerrMatch = () => post<SeerrAccount[]>('/Seedy/Admin/Seerr/Match');
export const adminSeerrUsers = () => get<SeerrUser[]>('/Seedy/Admin/Seerr/Users');
export const adminSeerrLink = (userId: string, seerrUserId: number | null) => post<SeerrAccount[]>('/Seedy/Admin/Seerr/Link', { userId, seerrUserId });
export const adminSetCanRequest = (userId: string, canRequest: boolean) => post<SeerrAccount[]>('/Seedy/Admin/Seerr/CanRequest', { userId, canRequest });
export const adminPlayLogProfiles = () => get<PlayLogProfile[]>('/Seedy/Admin/PlayLog/Profiles');
export const adminPlayLogProfile = (userId: string, body: { top10?: boolean; history?: boolean }) =>
  post<PlayLogProfile[]>(`/Seedy/Admin/PlayLog/Profiles/${userId}`, body);
export const adminClearPlayLog = (userId?: string) => del<void>('/Seedy/Admin/PlayLog', userId ? { userId } : undefined);
