import { SERVER_URL, get, getSession, post, del, request, url, type Query } from './client';
import type { BaseItem } from './types';
import type { AdminMedia, MediaFeatures } from './media';

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
  /* Music and book requests (Lidarr, Shelfarr, Librarr); missing from older plugins. */
  media?: MediaFeatures;
  historyEnabled: boolean;
  /** The admin's Home rows as this person can have them (rows they can't get are switched off). */
  homeRows?: HomeRowSetting[];
  pluginVersion: string;
  /** The sign-in belongs to another app or device (Jellyfin's web app): trade it with ownToken(). Older plugins leave it out. */
  borrowedToken?: boolean;
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

/* `because`: the recent watch a recommendation is like (the hero then says "Because you watched …"). */
export interface FeaturedEntry { item: BaseItem; reason: string; because?: string; play?: BaseItem }
export interface Top10Entry { item: BaseItem; profiles: number; plays: number }

/* The trailer behind a title in the Home carousel (GET /Seedy/Items/{id}/Trailer; 204 when there's none).
   Local trailer files come first, then YouTube: Jellyfin's remote trailers (from TMDB), then TMDB's list through Seerr.
   `alternates` are further YouTube trailers to try when one can't play (embedding off, removed). */
export interface TrailerAlternate { key: string; name?: string }
export type TrailerInfo =
  | { source: 'local'; itemId: string; name?: string; from?: string; alternates: TrailerAlternate[] }
  | { source: 'youtube'; key: string; name?: string; from?: 'jellyfin' | 'tmdb'; alternates: TrailerAlternate[] };

/* A movie or episode in the person's history, as its latest play left it (every play stays in the play log). */
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

/* A Seerr request: the title, who asked and when, and where it is now. `status` is the title's availability, as on posters,
   except for a series: the requested seasons' (available once those are in, even when later seasons aren't). */
export interface SeerrRequest extends SeerrItem {
  requestId: number;
  requestedAt: string;
  requestStatus: RequestStatus;
  seasons?: number[];
  is4k?: boolean;
  /* When the title became available, if it has. */
  availableAt?: string;
  /* When the request last changed (approved, declined…). */
  updatedAt?: string;
  /* What Sonarr/Radarr are downloading for it right now; `season` when it's all one season. */
  download?: { fraction: number; season?: number };
  /* Requested seasons that haven't started airing yet. */
  unaired?: number[];
  /* A movie's home release (yyyy-MM-dd) while it's still ahead. */
  releaseDate?: string;
}

/* The Requests page for the signed-in person: the All requests tab and admin actions follow their Seerr permissions. */
export interface RequestSummary {
  canViewAll: boolean;
  canManage: boolean;
  /* Their own requests still waiting or on the way. */
  open: number;
  /* Everyone's requests waiting for approval (with canViewAll). */
  pending?: number;
}

export type RequestFilter = 'all' | 'pending' | 'processing' | 'available' | 'failed';
export type TitlesKind = 'trending' | 'popular-movies' | 'popular-shows' | 'upcoming-movies' | 'upcoming-shows';

/* A title on a Requests row. Upcoming ones carry their date; a requested series' next season carries its number. */
export interface SeerrTitle extends SeerrItem { releaseDate?: string; season?: number }

export interface SeerrSeason {
  seasonNumber: number;
  name: string;
  episodeCount: number;
  airDate?: string;
  status: SeerrStatus;
  status4k: SeerrStatus;
  posterPath?: string;
  /* Set while the season is airing: how many of its episodes are out. */
  airedEpisodes?: number;
  requestedBy?: string;
  requestedByMe?: boolean;
  requestedBy4k?: string;
  requestedByMe4k?: boolean;
}

/* What the request dialog offers the signed-in person. `advanced` is there when Seerr lets them pick the server or request for others. */
export interface RequestOptions {
  quota?: { limit: number; days: number; used: number; remaining: number };
  autoApprove: boolean;
  autoApprove4k: boolean;
  can4k: boolean;
  advanced?: {
    servers: { id: number; name: string; is4k: boolean; isDefault: boolean; profileId?: number; rootFolder?: string; profiles: { id: number; name: string }[]; rootFolders: { path: string; freeSpace?: number }[] }[];
    users: SeerrUser[];
    me: number;
  };
}

export interface RequestBody {
  mediaType: 'movie' | 'tv';
  tmdbId: number;
  seasons?: number[];
  is4k?: boolean;
  serverId?: number;
  profileId?: number;
  rootFolder?: string;
  userId?: number;
}

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
    featured: { mode: 'auto' | 'picks' | 'both'; picks: string[]; count: number };
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
  /* Music and book requests; absent from older plugins. */
  media?: AdminMedia;
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

/* Settings › Devices: one place the person is signed in. */
export interface MyDevice {
  id: string;
  /** The admin's custom name, else the one the app reported. */
  name: string;
  reportedName: string;
  appName: string;
  appVersion: string;
  signedInAt: string;
  firstSeenAt: string;
  lastActiveAt?: string | null;
  ip?: string | null;
  network?: 'home' | 'away' | null;
  current: boolean;
  active: boolean;
  isNew: boolean;
  playing?: { itemId: string; title: string; type: string; season?: number | null; episode?: number | null; paused: boolean } | null;
}

/* ── Calls ── */

export const getClientBranding = () => request<Branding>('GET', '/Seedy/Client', { token: null });
export const signInBackdrops = () => request<string[]>('GET', '/Seedy/Client/Backdrops', { token: null });
export const brandLogoUrl = (version: string) => url('/Seedy/Client/Logo', { v: version });
/** The tab icon: the uploaded logo, or the brand mark (the name's first letter). */
export const faviconUrl = (b: Pick<Branding, 'logoVersion' | 'name'>) =>
  `${SERVER_URL}/Seedy/Client/Favicon?v=${encodeURIComponent(`${b.logoVersion}-${b.name}`)}`; // as the plugin writes it in index.html

export const getMe = () => get<Me>('/Seedy/Me');
/** When the person's password last changed (from the activity log); null when unknown. */
export const passwordState = () => get<{ changedAt: string | null }>('/Seedy/Me/Password');
export const myDevices = () => get<{ devices: MyDevice[] }>('/Seedy/Me/Devices');
export const signOutDevice = (id: string) => del<void>('/Seedy/Me/Devices', { id });
export const signOutOtherDevices = () => post<{ signedOut: number }>('/Seedy/Me/Devices/SignOutOthers');
export const savePrefs = (prefs: Partial<UserPrefs>) => post<UserPrefs>('/Seedy/Me/Prefs', prefs);

export const getHome = () => get<{ rows: HomeRow[] }>('/Seedy/Home');
export const getFeatured = () => get<FeaturedEntry[]>('/Seedy/Home/Featured');
export const getTop10 = () => get<{ items: Top10Entry[]; quiet: boolean }>('/Seedy/Home/Top10');
export const recordOrigin = (itemId: string, row: string) => post<void>('/Seedy/Plays/Origin', { itemId, row });
/* Whether the browser decodes the video (the experimental player), for the admin's Playing now. */
export const recordDecoder = (itemId: string, onClient: boolean) => post<void>('/Seedy/Plays/Decoder', { itemId, onClient });
/* Swaps a sign-in borrowed from Jellyfin's web app for one made for Seedy, so its websocket joins the playing session. */
export const ownToken = () => post<{ accessToken: string }>('/Seedy/Me/Token');
export const getTrailer = (itemId: string) => get<TrailerInfo | undefined>(`/Seedy/Items/${itemId}/Trailer`);

/* Viewing history: each movie or episode once, as its latest play left it. `from`/`to` (ISO) and `devices` pick the plays
   first; `state` and hidden filter that latest play. Sorted by when it was watched (newest first) or by title (A to Z). */
export interface HistoryQuery {
  startIndex?: number;
  limit?: number;
  type?: 'all' | 'movies' | 'shows';
  search?: string;
  includeHidden?: boolean;
  state?: 'finished' | 'inprogress';
  from?: string;
  to?: string;
  /* Device names separated by |. */
  devices?: string;
  sort?: 'date' | 'title';
  order?: 'asc' | 'desc';
}
export const getHistory = (q: HistoryQuery) => get<{ items: HistoryEntry[]; total: number }>('/Seedy/Plays', q as Query);
/* What the history's filters offer: years with plays (newest first) and devices (last used first). */
export const getHistoryOptions = (type: 'all' | 'movies' | 'shows') => get<{ years: number[]; devices: string[] }>('/Seedy/Plays/Filters', { type });
export const hideHistoryTitle = (titleId: string, hidden: boolean) => post<void>(`/Seedy/Plays/Titles/${titleId}/Hidden`, { hidden });
export const removeHistoryEntry = (id: string) => del<void>(`/Seedy/Plays/${id}`);
export const clearHistory = () => del<void>('/Seedy/Plays');

export const seerrDiscover = (page = 1) => get<{ results: SeerrItem[] }>('/Seedy/Requests/Discover', { page });
export const seerrSearch = (query: string, page = 1) => get<{ results: SeerrItem[]; total: number }>('/Seedy/Requests/Search', { query, page });
export const seerrUpcoming = () => get<{ results: SeerrUpcoming[] }>('/Seedy/Requests/Upcoming');
export const seerrDetails = (mediaType: 'movie' | 'tv', tmdbId: number) => get<SeerrDetails>(`/Seedy/Requests/Media/${mediaType}/${tmdbId}`);
/* The person's requests, newest first; `open` counts those still waiting or downloading. */
export const myRequests = () => get<{ results: SeerrRequest[]; open: number }>('/Seedy/Requests/Mine');
export const requestSummary = () => get<RequestSummary>('/Seedy/Requests/Summary');
export const requestList = (q: { scope: 'mine' | 'all'; filter?: RequestFilter; sort?: 'added' | 'modified'; skip?: number; take?: number }) =>
  get<{ results: SeerrRequest[]; total: number }>('/Seedy/Requests/List', q as Query);
export const seerrTitles = (kind: TitlesKind, page = 1) => get<{ results: SeerrTitle[]; pages: number }>(`/Seedy/Requests/Titles/${kind}`, { page });
export const approveRequest = (id: number) => post<SeerrRequest | undefined>(`/Seedy/Requests/${id}/Approve`);
export const declineRequest = (id: number) => post<SeerrRequest | undefined>(`/Seedy/Requests/${id}/Decline`);
export const retryRequest = (id: number) => post<SeerrRequest | undefined>(`/Seedy/Requests/${id}/Retry`);
export const removeRequest = (id: number) => del<void>(`/Seedy/Requests/${id}`);
export const seerrRequestOptions = (mediaType: 'movie' | 'tv') => get<RequestOptions>(`/Seedy/Requests/Options/${mediaType}`);
export const seerrRequest = (body: RequestBody) =>
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

/* ── Reading: the place in each book (Seedy keeps it under the book's id; audiobooks use it too) ── */

/* Reader settings saved with the place in each book (any JSON the reader likes; see reader/settings.ts). */
export type ReadingSettings = Record<string, unknown>;

export interface Reading {
  ItemId: string;
  /* An EPUB CFI, or a page index (from 0) for comics. */
  Locator?: string;
  Percent: number;
  Chapter?: string;
  ChapterIndex?: number;
  ChapterCount?: number;
  Finished: boolean;
  Settings?: ReadingSettings | null;
  UpdatedAt?: string;
}

export type ReadingPatch = Omit<Reading, 'ItemId' | 'UpdatedAt'>;

export const listReading = (finished = false, limit = 40) => get<Reading[]>('/Seedy/Reading', { finished, limit });

/* 204 (never opened) comes back as undefined. */
export const getReading = (id: string) => get<Reading | undefined>(`/Seedy/Reading/${id}`).then((r) => (r && typeof r === 'object' ? r : undefined));

export const saveReading = (id: string, patch: ReadingPatch) => post<Reading>(`/Seedy/Reading/${id}`, patch);

/* Fire-and-forget save that survives the page closing. */
export function saveReadingOnExit(id: string, patch: ReadingPatch) {
  const session = getSession();
  if (!session) return;
  try {
    void fetch(`${SERVER_URL}/Seedy/Reading/${id}?ApiKey=${encodeURIComponent(session.token)}`, {
      method: 'POST',
      keepalive: true,
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(patch)
    });
  } catch { /* the debounced save already ran */ }
}

export const deleteReading = (id: string) => del<void>(`/Seedy/Reading/${id}`);
