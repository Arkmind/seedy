/* The subset of Jellyfin's DTOs that Seedy reads. Field names match the server's JSON. */

export type ItemKind =
  | 'Movie' | 'Series' | 'Season' | 'Episode' | 'BoxSet' | 'MusicAlbum' | 'MusicArtist' | 'Audio'
  | 'Person' | 'CollectionFolder' | 'Folder' | 'Video' | 'Trailer' | 'Playlist' | 'UserView' | 'MusicVideo'
  | 'Genre' | 'Studio' | 'TvChannel' | 'Program' | 'Book' | 'Photo' | 'PhotoAlbum';

export interface UserItemData {
  PlaybackPositionTicks?: number;
  PlayCount?: number;
  IsFavorite?: boolean;
  Played?: boolean;
  PlayedPercentage?: number;
  UnplayedItemCount?: number;
  LastPlayedDate?: string;
  Key?: string;
}

export interface NameId { Name: string; Id: string }

export interface Person {
  Name: string;
  Id: string;
  Role?: string;
  Type?: 'Actor' | 'Director' | 'Writer' | 'Producer' | 'GuestStar' | 'Composer' | 'Creator' | string;
  PrimaryImageTag?: string;
}

export interface MediaStream {
  Codec?: string;
  Language?: string;
  DisplayTitle?: string;
  Title?: string;
  Type: 'Video' | 'Audio' | 'Subtitle' | 'EmbeddedImage' | 'Data' | 'Lyric';
  Index: number;
  IsDefault?: boolean;
  IsForced?: boolean;
  IsHearingImpaired?: boolean;
  IsExternal?: boolean;
  IsTextSubtitleStream?: boolean;
  SupportsExternalStream?: boolean;
  DeliveryMethod?: 'Encode' | 'Embed' | 'External' | 'Hls' | 'Drop';
  DeliveryUrl?: string;
  Height?: number;
  Width?: number;
  VideoRange?: string;
  VideoRangeType?: string;
  RealFrameRate?: number;
  AverageFrameRate?: number;
  AudioSpatialFormat?: string;
  Channels?: number;
  ChannelLayout?: string;
  Profile?: string;
  BitRate?: number;
  Path?: string;
}

export interface MediaSource {
  Id: string;
  Path?: string;
  Name?: string;
  Container?: string;
  Size?: number;
  Bitrate?: number;
  Protocol?: string;
  RunTimeTicks?: number;
  SupportsDirectPlay?: boolean;
  SupportsDirectStream?: boolean;
  SupportsTranscoding?: boolean;
  TranscodingUrl?: string;
  TranscodingSubProtocol?: string;
  TranscodingContainer?: string;
  DefaultAudioStreamIndex?: number;
  DefaultSubtitleStreamIndex?: number;
  MediaStreams?: MediaStream[];
  ETag?: string;
  IsRemote?: boolean;
  /* Set on a live TV stream opened by PlaybackInfo; closed when playback stops. */
  LiveStreamId?: string;
  IsInfiniteStream?: boolean;
}

export interface Chapter { StartPositionTicks: number; Name?: string; ImageTag?: string; ImageDateModified?: string }

export interface TrickplayInfo {
  Width: number;
  Height: number;
  TileWidth: number;
  TileHeight: number;
  ThumbnailCount: number;
  Interval: number;
  Bandwidth?: number;
}

export interface BaseItem {
  Id: string;
  Name: string;
  ServerId?: string;
  Type: ItemKind;
  CollectionType?: string;
  IsFolder?: boolean;
  OriginalTitle?: string;
  Overview?: string;
  Taglines?: string[];
  Genres?: string[];
  GenreItems?: NameId[];
  Studios?: NameId[];
  People?: Person[];
  ProductionYear?: number;
  PremiereDate?: string;
  EndDate?: string;
  DateCreated?: string;
  Status?: string;
  AirDays?: string[];
  AirTime?: string;
  OfficialRating?: string;
  CommunityRating?: number;
  CriticRating?: number;
  RunTimeTicks?: number;
  ChildCount?: number;
  RecursiveItemCount?: number;
  SeriesId?: string;
  SeriesName?: string;
  SeasonId?: string;
  SeasonName?: string;
  IndexNumber?: number;
  IndexNumberEnd?: number;
  ParentIndexNumber?: number;
  ParentId?: string;
  ImageTags?: Partial<Record<'Primary' | 'Thumb' | 'Logo' | 'Banner' | 'Art' | 'Disc' | 'Backdrop', string>>;
  BackdropImageTags?: string[];
  ParentBackdropItemId?: string;
  ParentBackdropImageTags?: string[];
  ParentThumbItemId?: string;
  ParentThumbImageTag?: string;
  ParentLogoItemId?: string;
  ParentLogoImageTag?: string;
  ParentPrimaryImageItemId?: string;
  ParentPrimaryImageTag?: string;
  SeriesPrimaryImageTag?: string;
  SeriesThumbImageTag?: string;
  AlbumId?: string;
  AlbumPrimaryImageTag?: string;
  Album?: string;
  AlbumArtist?: string;
  Artists?: string[];
  ArtistItems?: NameId[];
  PrimaryImageAspectRatio?: number;
  UserData?: UserItemData;
  MediaSources?: MediaSource[];
  MediaStreams?: MediaStream[];
  Chapters?: Chapter[];
  Trickplay?: Record<string, Record<string, TrickplayInfo>>;
  LocationType?: 'FileSystem' | 'Virtual' | 'Remote' | 'Offline';
  MediaType?: 'Video' | 'Audio' | 'Photo' | 'Book' | 'Unknown';
  Width?: number;
  Height?: number;
  RemoteTrailers?: { Url: string; Name?: string }[];
  LocalTrailerCount?: number;
  ProviderIds?: Record<string, string>;
  ExternalUrls?: { Name: string; Url: string }[];
  Tags?: string[];
  CanDelete?: boolean;
  SortName?: string;
  SpecialFeatureCount?: number;
}

export interface ItemsResult<T = BaseItem> { Items: T[]; TotalRecordCount: number; StartIndex?: number }

export interface UserPolicy {
  IsAdministrator: boolean;
  EnableSubtitleManagement?: boolean;
  EnableCollectionManagement?: boolean;
  EnableContentDeletion?: boolean;
  EnableRemoteControlOfOtherUsers?: boolean;
  EnableSharedDeviceControl?: boolean;
  EnableSyncPlayAccess?: boolean;
  SyncPlayAccess?: 'CreateAndJoinGroups' | 'JoinGroups' | 'None';
  MaxParentalRating?: number;
}

export interface UserConfiguration {
  AudioLanguagePreference?: string;
  SubtitleLanguagePreference?: string;
  PlayDefaultAudioTrack?: boolean;
  SubtitleMode?: 'Default' | 'Always' | 'OnlyForced' | 'None' | 'Smart';
  RememberAudioSelections?: boolean;
  RememberSubtitleSelections?: boolean;
  EnableNextEpisodeAutoPlay?: boolean;
}

export interface User {
  Id: string;
  Name: string;
  ServerId?: string;
  PrimaryImageTag?: string;
  HasPassword?: boolean;
  HasConfiguredPassword?: boolean;
  Policy?: UserPolicy;
  Configuration?: UserConfiguration;
  LastActivityDate?: string;
}

export interface AuthResult { User: User; AccessToken: string; ServerId: string }

export interface PlaybackInfoResponse { MediaSources: MediaSource[]; PlaySessionId: string; ErrorCode?: string }

export type SegmentType = 'Unknown' | 'Commercial' | 'Preview' | 'Recap' | 'Outro' | 'Intro';
export interface MediaSegment { Id: string; ItemId: string; Type: SegmentType; StartTicks: number; EndTicks: number }

export interface SessionInfo {
  Id: string;
  UserId?: string;
  UserName?: string;
  Client?: string;
  DeviceName?: string;
  DeviceId?: string;
  SupportsRemoteControl?: boolean;
  SupportsMediaControl?: boolean;
  PlayableMediaTypes?: string[];
  NowPlayingItem?: BaseItem;
  LastActivityDate?: string;
}

export interface RemoteSubtitle {
  Id: string;
  ProviderName?: string;
  Name?: string;
  Format?: string;
  Author?: string;
  Comment?: string;
  CommunityRating?: number;
  DownloadCount?: number;
  IsHashMatch?: boolean;
  ThreeLetterISOLanguageName?: string;
  HearingImpaired?: boolean;
  Forced?: boolean;
}

export interface SyncPlayGroup { GroupId: string; GroupName: string; State: string; Participants: string[]; LastUpdatedAt: string }
