import { get, post, del, request, getSession, type Query } from './client';
import type {
  AuthResult, BaseItem, ItemsResult, MediaSegment, PlaybackInfoResponse, RemoteSubtitle, SessionInfo, SyncPlayGroup, User
} from './types';

/* Fields every card needs; details pages ask for more. */
export const CARD_FIELDS = [
  'PrimaryImageAspectRatio', 'ProductionYear', 'Genres', 'ChildCount', 'RecursiveItemCount', 'Overview', 'DateCreated'
];
export const DETAIL_FIELDS = [
  'Overview', 'Genres', 'People', 'Studios', 'Taglines', 'MediaSources', 'MediaStreams', 'Chapters', 'Trickplay',
  'RemoteTrailers', 'ExternalUrls', 'ProviderIds', 'AirTime', 'ChildCount', 'RecursiveItemCount', 'DateCreated',
  'OriginalTitle', 'LocalTrailerCount', 'SpecialFeatureCount', 'Tags', 'ProductionLocations', 'CanDelete'
];
export const PLAYER_FIELDS = ['Overview', 'Genres', 'People', 'MediaSources', 'MediaStreams', 'Chapters', 'Trickplay', 'ParentId'];

const uid = () => getSession()?.userId ?? '';

export const authenticateByName = (Username: string, Pw: string) =>
  request<AuthResult>('POST', '/Users/AuthenticateByName', { body: { Username, Pw }, token: null });
export const getMe = (token?: string) => request<User>('GET', '/Users/Me', token ? { token } : {});
export const logout = () => post<void>('/Sessions/Logout');
export const quickConnectEnabled = () => request<boolean>('GET', '/QuickConnect/Enabled', { token: null });
export const quickConnectInitiate = () => request<{ Secret: string; Code: string; Authenticated: boolean }>('POST', '/QuickConnect/Initiate', { token: null });
export const quickConnectState = (secret: string) =>
  request<{ Secret: string; Code: string; Authenticated: boolean }>('GET', '/QuickConnect/Connect', { query: { secret }, token: null });
export const authenticateWithQuickConnect = (Secret: string) =>
  request<AuthResult>('POST', '/Users/AuthenticateWithQuickConnect', { body: { Secret }, token: null });
export const getSystemInfo = () => get<{ Version: string; ServerName: string; Id: string; OperatingSystemDisplayName?: string }>('/System/Info');

/* ── Library ── */
/* With external content: Jellyfin only lists Live TV (and plugin channels) as a library when asked for it. */
export const getUserViews = () => get<ItemsResult>('/UserViews', { userId: uid(), includeExternalContent: true });

export const getItem = (id: string, fields: string[] = DETAIL_FIELDS) =>
  get<BaseItem>(`/Items/${id}`, { userId: uid(), fields: fields.join(',') } as Query);

export interface ItemsQuery {
  parentId?: string;
  includeItemTypes?: string[];
  excludeItemTypes?: string[];
  recursive?: boolean;
  sortBy?: string[];
  sortOrder?: 'Ascending' | 'Descending';
  startIndex?: number;
  limit?: number;
  fields?: string[];
  filters?: string[];
  genres?: string[];
  genreIds?: string[];
  personIds?: string[];
  studioIds?: string[];
  years?: number[];
  officialRatings?: string[];
  isFavorite?: boolean;
  isPlayed?: boolean;
  isHd?: boolean;
  is4K?: boolean;
  hasSubtitles?: boolean;
  videoTypes?: string[];
  nameStartsWith?: string;
  nameLessThan?: string;
  nameStartsWithOrGreater?: string;
  searchTerm?: string;
  ids?: string[];
  isMissing?: boolean;
  enableTotalRecordCount?: boolean;
  enableImageTypes?: string[];
  imageTypeLimit?: number;
  collapseBoxSetItems?: boolean;
  mediaTypes?: string[];
  minPremiereDate?: string;
  maxPremiereDate?: string;
  hasThemeSong?: boolean;
  albumArtistIds?: string[];
  artistIds?: string[];
  seriesStatus?: string[];
}

export function getItems(q: ItemsQuery, signal?: AbortSignal) {
  return get<ItemsResult>('/Items', {
    userId: uid(),
    parentId: q.parentId,
    includeItemTypes: q.includeItemTypes,
    excludeItemTypes: q.excludeItemTypes,
    recursive: q.recursive ?? true,
    sortBy: q.sortBy,
    sortOrder: q.sortOrder,
    startIndex: q.startIndex,
    limit: q.limit,
    fields: q.fields ?? CARD_FIELDS,
    filters: q.filters,
    genres: q.genres?.join('|'),
    genreIds: q.genreIds?.join('|'),
    personIds: q.personIds,
    studioIds: q.studioIds,
    years: q.years?.map(String),
    officialRatings: q.officialRatings?.join('|'),
    isFavorite: q.isFavorite,
    isPlayed: q.isPlayed,
    isHd: q.isHd,
    is4K: q.is4K,
    hasSubtitles: q.hasSubtitles,
    videoTypes: q.videoTypes,
    nameStartsWith: q.nameStartsWith,
    nameLessThan: q.nameLessThan,
    nameStartsWithOrGreater: q.nameStartsWithOrGreater,
    searchTerm: q.searchTerm,
    ids: q.ids,
    isMissing: q.isMissing,
    enableTotalRecordCount: q.enableTotalRecordCount ?? true,
    enableImageTypes: q.enableImageTypes ?? ['Primary', 'Backdrop', 'Thumb', 'Logo'],
    imageTypeLimit: q.imageTypeLimit ?? 1,
    collapseBoxSetItems: q.collapseBoxSetItems,
    mediaTypes: q.mediaTypes,
    minPremiereDate: q.minPremiereDate,
    maxPremiereDate: q.maxPremiereDate,
    albumArtistIds: q.albumArtistIds,
    artistIds: q.artistIds,
    seriesStatus: q.seriesStatus,
    enableUserData: true
  } as Query, signal);
}

export const getResume = (limit = 16, parentId?: string) =>
  get<ItemsResult>('/UserItems/Resume', {
    userId: uid(), limit, parentId, mediaTypes: 'Video', enableUserData: true, imageTypeLimit: 1,
    enableImageTypes: 'Primary,Backdrop,Thumb,Logo', fields: CARD_FIELDS.join(','), enableTotalRecordCount: false
  });

export const getNextUp = (limit = 16, seriesId?: string, parentId?: string) =>
  get<ItemsResult>('/Shows/NextUp', {
    userId: uid(), limit, seriesId, parentId, enableUserData: true, imageTypeLimit: 1, enableResumable: false,
    enableRewatching: false, disableFirstEpisode: false, enableImageTypes: 'Primary,Backdrop,Thumb,Logo',
    fields: [...CARD_FIELDS, 'DateLastMediaAdded'].join(','), enableTotalRecordCount: false
  });

export const getLatest = (parentId: string, limit = 16, includeItemTypes?: string[]) =>
  get<BaseItem[]>('/Items/Latest', {
    userId: uid(), parentId, limit, includeItemTypes, enableUserData: true, imageTypeLimit: 1, groupItems: true,
    enableImageTypes: 'Primary,Backdrop,Thumb,Logo', fields: CARD_FIELDS.join(',')
  } as Query);

export const getSimilar = (id: string, limit = 12) =>
  get<ItemsResult>(`/Items/${id}/Similar`, { userId: uid(), limit, fields: CARD_FIELDS.join(',') });

export const getUpcomingEpisodes = (limit = 40, parentId?: string) =>
  get<ItemsResult>('/Shows/Upcoming', {
    userId: uid(), limit, parentId, enableUserData: true, imageTypeLimit: 1,
    enableImageTypes: 'Primary,Backdrop,Thumb', fields: ['PrimaryImageAspectRatio', 'Overview'].join(',')
  });

export const getSeasons = (seriesId: string) =>
  get<ItemsResult>(`/Shows/${seriesId}/Seasons`, { userId: uid(), enableUserData: true, fields: 'ChildCount,RecursiveItemCount,Overview' });

export const getEpisodes = (seriesId: string, seasonId?: string, extra: Query = {}) =>
  get<ItemsResult>(`/Shows/${seriesId}/Episodes`, {
    userId: uid(), seasonId, enableUserData: true, fields: 'Overview,PrimaryImageAspectRatio,MediaStreams,Trickplay', isMissing: undefined, ...extra
  });

export const getGenres = (parentId?: string, includeItemTypes?: string[]) =>
  get<ItemsResult>('/Genres', { userId: uid(), parentId, includeItemTypes, sortBy: 'SortName', enableImageTypes: 'Primary,Backdrop,Thumb', imageTypeLimit: 1 } as Query);

export const getLocalTrailers = (id: string) => get<BaseItem[]>(`/Items/${id}/LocalTrailers`, { userId: uid() });

export const setFavorite = (id: string, on: boolean) =>
  on
    ? post<{ IsFavorite: boolean }>(`/UserFavoriteItems/${id}`, undefined, { userId: uid() })
    : del<{ IsFavorite: boolean }>(`/UserFavoriteItems/${id}`, { userId: uid() });

export const setPlayed = (id: string, on: boolean) =>
  on
    ? post<{ Played: boolean }>(`/UserPlayedItems/${id}`, undefined, { userId: uid() })
    : del<{ Played: boolean }>(`/UserPlayedItems/${id}`, { userId: uid() });

/* ── Playback ── */
export interface PlaybackInfoRequest {
  DeviceProfile: unknown;
  MaxStreamingBitrate?: number;
  StartTimeTicks?: number;
  AudioStreamIndex?: number;
  SubtitleStreamIndex?: number;
  MediaSourceId?: string;
  EnableDirectPlay?: boolean;
  EnableDirectStream?: boolean;
  EnableTranscoding?: boolean;
  AllowVideoStreamCopy?: boolean;
  AllowAudioStreamCopy?: boolean;
  AutoOpenLiveStream?: boolean;
  AlwaysBurnInSubtitleWhenTranscoding?: boolean;
}

export const getPlaybackInfo = (id: string, body: PlaybackInfoRequest) =>
  post<PlaybackInfoResponse>(`/Items/${id}/PlaybackInfo`, { UserId: uid(), ...body }, { userId: uid() });

export interface PlaybackReport {
  ItemId: string;
  MediaSourceId?: string;
  PlaySessionId?: string;
  PositionTicks?: number;
  IsPaused?: boolean;
  IsMuted?: boolean;
  VolumeLevel?: number;
  PlayMethod?: 'DirectPlay' | 'DirectStream' | 'Transcode';
  AudioStreamIndex?: number;
  SubtitleStreamIndex?: number;
  CanSeek?: boolean;
  PlaybackRate?: number;
  RepeatMode?: string;
  EventName?: string;
  Failed?: boolean;
  PlaybackStartTimeTicks?: number;
  LiveStreamId?: string;
}

export const reportPlaybackStart = (r: PlaybackReport) => post<void>('/Sessions/Playing', r);
export const reportPlaybackProgress = (r: PlaybackReport) => post<void>('/Sessions/Playing/Progress', r);
export const pingPlayback = (playSessionId: string) => post<void>('/Sessions/Playing/Ping', undefined, { playSessionId });
export const stopEncoding = (deviceId: string, playSessionId: string) => del<void>('/Videos/ActiveEncodings', { deviceId, playSessionId });

export const getMediaSegments = (id: string) => get<{ Items: MediaSegment[] }>(`/MediaSegments/${id}`);

export const searchRemoteSubtitles = (id: string, language: string) =>
  get<RemoteSubtitle[]>(`/Items/${id}/RemoteSearch/Subtitles/${language}`);
export const downloadRemoteSubtitle = (id: string, subtitleId: string) =>
  post<void>(`/Items/${id}/RemoteSearch/Subtitles/${encodeURIComponent(subtitleId)}`);

/* ── Sessions, remote control, SyncPlay ── */
export const reportCapabilities = (body: unknown) => post<void>('/Sessions/Capabilities/Full', body);
export const getControllableSessions = () =>
  get<SessionInfo[]>('/Sessions', { controllableByUserId: uid(), activeWithinSeconds: 960 });
export const sendPlayCommand = (sessionId: string, itemIds: string[], startPositionTicks = 0) =>
  post<void>(`/Sessions/${sessionId}/Playing`, undefined, { playCommand: 'PlayNow', itemIds, startPositionTicks } as Query);

export const syncPlayList = () => get<SyncPlayGroup[]>('/SyncPlay/List');
export const syncPlayNew = (GroupName: string) => post<SyncPlayGroup>('/SyncPlay/New', { GroupName });
export const syncPlayJoin = (GroupId: string) => post<void>('/SyncPlay/Join', { GroupId });
export const syncPlayLeave = () => post<void>('/SyncPlay/Leave');
export const syncPlayPause = () => post<void>('/SyncPlay/Pause');
export const syncPlayUnpause = () => post<void>('/SyncPlay/Unpause');
export const syncPlaySeek = (PositionTicks: number) => post<void>('/SyncPlay/Seek', { PositionTicks });
export const syncPlayReady = (body: { When: string; PositionTicks: number; IsPlaying: boolean; PlaylistItemId: string }) =>
  post<void>('/SyncPlay/Ready', body);
export const syncPlayBuffering = (body: { When: string; PositionTicks: number; IsPlaying: boolean; PlaylistItemId: string }) =>
  post<void>('/SyncPlay/Buffering', body);
export const syncPlaySetQueue = (PlayingQueue: string[], StartPositionTicks = 0) =>
  post<void>('/SyncPlay/SetNewQueue', { PlayingQueue, PlayingItemPosition: 0, StartPositionTicks });
export const getUtcTime = () => get<{ RequestReceptionTime: string; ResponseTransmissionTime: string }>('/GetUtcTime');

export type { BaseItem, ItemsResult };
