import { del, get, getSession, post, request, type Query } from './client';
import type { BaseItem, ItemsResult } from './types';

/* Jellyfin's Live TV API (Jellyfin.Api/Controllers/LiveTvController.cs): channels, guide programmes, timers,
   series timers and recordings. JSON is PascalCase. */

const uid = () => getSession()?.userId ?? '';

/* A guide programme, as /LiveTv/Programs and a channel's CurrentProgram give it. */
export interface Program extends BaseItem {
  StartDate?: string;
  EndDate?: string;
  ChannelId?: string;
  ChannelName?: string;
  ChannelNumber?: string;
  ChannelPrimaryImageTag?: string;
  EpisodeTitle?: string;
  IsMovie?: boolean;
  IsSeries?: boolean;
  IsSports?: boolean;
  IsNews?: boolean;
  IsKids?: boolean;
  IsLive?: boolean;
  IsPremiere?: boolean;
  IsRepeat?: boolean;
  IsNew?: boolean;
  /* Set when a recording is scheduled for this airing / its series. */
  TimerId?: string;
  SeriesTimerId?: string;
}

export interface Channel extends BaseItem {
  Number?: string;
  ChannelNumber?: string;
  ChannelType?: 'TV' | 'Radio';
  CurrentProgram?: Program;
}

export type RecordingStatus = 'New' | 'InProgress' | 'Completed' | 'Cancelled' | 'ConflictedOk' | 'ConflictedNotOk' | 'Error';
export type KeepUntil = 'UntilDeleted' | 'UntilSpaceNeeded' | 'UntilWatched' | 'UntilDate';

interface BaseTimer {
  Id: string;
  Type?: string;
  ServerId?: string;
  ChannelId: string;
  ChannelName?: string;
  ChannelPrimaryImageTag?: string;
  ProgramId?: string;
  Name?: string;
  Overview?: string;
  StartDate: string;
  EndDate: string;
  ServiceName?: string;
  Priority?: number;
  PrePaddingSeconds: number;
  PostPaddingSeconds: number;
  IsPrePaddingRequired?: boolean;
  IsPostPaddingRequired?: boolean;
  ParentBackdropItemId?: string;
  ParentBackdropImageTags?: string[];
  KeepUntil?: KeepUntil;
}

export interface Timer extends BaseTimer {
  Status?: RecordingStatus;
  SeriesTimerId?: string;
  RunTimeTicks?: number;
  ProgramInfo?: Program;
}

export interface SeriesTimer extends BaseTimer {
  RecordAnyTime: boolean;
  SkipEpisodesInLibrary: boolean;
  RecordAnyChannel: boolean;
  KeepUpTo: number;
  RecordNewOnly: boolean;
  Days?: string[];
  DayPattern?: 'Daily' | 'Weekdays' | 'Weekends';
  ImageTags?: Record<string, string>;
  ParentThumbItemId?: string;
  ParentThumbImageTag?: string;
  ParentPrimaryImageItemId?: string;
  ParentPrimaryImageTag?: string;
}

export interface GuideInfo { StartDate: string; EndDate: string }

const CHANNEL_FIELDS = ['PrimaryImageAspectRatio', 'ChannelInfo', 'Overview'];

export const getChannels = (q: Query = {}, signal?: AbortSignal) =>
  get<ItemsResult<Channel>>('/LiveTv/Channels', {
    userId: uid(), addCurrentProgram: true, enableUserData: true, enableImages: true, imageTypeLimit: 1,
    enableImageTypes: ['Primary', 'Thumb', 'Backdrop'], fields: CHANNEL_FIELDS, ...q
  }, signal);

export const getGuideInfo = () => get<GuideInfo>('/LiveTv/GuideInfo');

export interface ProgramsQuery {
  ChannelIds?: string[];
  MinStartDate?: string;
  MaxStartDate?: string;
  MinEndDate?: string;
  MaxEndDate?: string;
  IsAiring?: boolean;
  HasAired?: boolean;
  IsMovie?: boolean;
  IsSeries?: boolean;
  IsSports?: boolean;
  IsNews?: boolean;
  IsKids?: boolean;
  Limit?: number;
  SortBy?: string[];
  SortOrder?: ('Ascending' | 'Descending')[];
  SeriesTimerId?: string;
}

/* POST keeps long channel lists out of the address. */
export const getPrograms = (q: ProgramsQuery, signal?: AbortSignal) =>
  request<ItemsResult<Program>>('POST', '/LiveTv/Programs', {
    signal,
    body: {
      UserId: uid(), EnableImages: true, ImageTypeLimit: 1, EnableImageTypes: ['Primary', 'Thumb', 'Backdrop'],
      EnableUserData: false, Fields: ['Overview', 'Genres', 'ChannelInfo', 'PrimaryImageAspectRatio'], EnableTotalRecordCount: false,
      SortBy: ['StartDate'], ...q
    }
  });

/* ── Recording ── */

export const getTimers = (q: { channelId?: string; seriesTimerId?: string; isActive?: boolean; isScheduled?: boolean } = {}) =>
  get<ItemsResult<Timer>>('/LiveTv/Timers', q);
export const getTimerDefaults = (programId?: string) => get<SeriesTimer>('/LiveTv/Timers/Defaults', { programId });
export const createTimer = (t: Partial<Timer>) => post<void>('/LiveTv/Timers', t);
export const cancelTimer = (id: string) => del<void>(`/LiveTv/Timers/${id}`);

export const getSeriesTimers = () => get<ItemsResult<SeriesTimer>>('/LiveTv/SeriesTimers', { sortBy: 'SortName', sortOrder: 'Ascending' });
export const createSeriesTimer = (t: Partial<SeriesTimer>) => post<void>('/LiveTv/SeriesTimers', t);
export const updateSeriesTimer = (t: SeriesTimer) => post<void>(`/LiveTv/SeriesTimers/${t.Id}`, t);
export const cancelSeriesTimer = (id: string) => del<void>(`/LiveTv/SeriesTimers/${id}`);

export const getRecordings = (q: Query = {}) =>
  get<ItemsResult<Program>>('/LiveTv/Recordings', {
    userId: uid(), enableImages: true, imageTypeLimit: 1, enableImageTypes: ['Primary', 'Thumb', 'Backdrop'], enableUserData: true,
    fields: ['Overview', 'Genres', 'ChannelInfo', 'DateCreated', 'PrimaryImageAspectRatio', 'CanDelete'], ...q
  });
/* Through the library: /LiveTv/Recordings/{id} only forgets the item and leaves the file (which the next scan brings back). */
export const deleteRecording = (id: string) => del<void>(`/Items/${id}`);

/* Record one airing with the server's defaults (padding, keep). */
export async function recordProgram(programId: string): Promise<void> {
  const d = await getTimerDefaults(programId);
  await createTimer({ ...d, ProgramId: programId } as Partial<Timer>);
}

/* Record every airing of the programme's series (the defaults for that programme describe the rule). */
export async function recordSeries(programId: string): Promise<void> {
  const d = await getTimerDefaults(programId);
  await createSeriesTimer({ ...d, ProgramId: programId });
}

/* Admin only: the tuners and how many streams each plays at once (M3U "simultaneous stream limit"). */
export interface TunerHost { Id: string; Type: string; Url?: string; FriendlyName?: string; TunerCount: number }
export const getLiveTvConfig = () => get<{ TunerHosts?: TunerHost[] }>('/System/Configuration/livetv');
