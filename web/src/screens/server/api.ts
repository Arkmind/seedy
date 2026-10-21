import { ApiError, authHeader, del, deviceId, get, getSession, JELLYFIN_WEB, post, request, url } from '../../api/client';
import type { BaseItem, ItemsResult, User, UserConfiguration } from '../../api/types';
import { t } from '../../lib/i18n';
import { createStore } from '../../lib/store';
import { toast } from '../../ui/overlay';
import type { SystemStorage } from './storage';

/* Jellyfin 12 calls behind the server dashboard, in the order of its menu: Server (overview, settings, users,
   libraries, playback, networking, Live TV), Activity (devices, activity log) and Advanced (plugins, scheduled tasks,
   backups, logs, API keys). Routes and DTOs follow Jellyfin.Api/Controllers and MediaBrowser.Model: JSON is
   PascalCase, except the package catalog (/Packages), whose fields are camelCase. Configuration objects go back whole
   (the server replaces them), so their types keep an index signature for the fields Seedy doesn't edit. */

type Rest = { [key: string]: unknown };

/* Jellyfin writes ids with and without dashes. */
export const normId = (id?: string | null) => (id ?? '').replace(/-/g, '').toLowerCase();
export const sameId = (a?: string | null, b?: string | null) => !!a && !!b && normId(a) === normId(b);


/* ── System ── */

export interface SystemInfo {
  ServerName: string;
  Version: string;
  Id: string;
  OperatingSystemDisplayName?: string;
  OperatingSystem?: string;
  SystemArchitecture?: string;
  HasPendingRestart?: boolean;
  IsShuttingDown?: boolean;
  CanSelfRestart?: boolean;
  ProgramDataPath?: string;
  ItemsByNamePath?: string;
  CachePath?: string;
  LogPath?: string;
  InternalMetadataPath?: string;
  TranscodingTempPath?: string;
  CastReceiverApplications?: CastReceiver[];
}

export const getSystemInfo = (signal?: AbortSignal) => get<SystemInfo>('/System/Info', undefined, signal);
export const getStorage = (signal?: AbortSignal) => get<SystemStorage>('/System/Info/Storage', undefined, signal);
export const scanAllLibraries = () => post<void>('/Library/Refresh');
export const shutdownServer = () => post<void>('/System/Shutdown');


export interface CastReceiver { Id: string; Name: string }
export interface PathSubstitution { From: string; To: string }

export interface TrickplayOptions extends Rest {
  EnableHwAcceleration: boolean;
  EnableHwEncoding: boolean;
  EnableKeyFrameOnlyExtraction: boolean;
  ScanBehavior: 'Blocking' | 'NonBlocking';
  ProcessPriority: 'Idle' | 'BelowNormal' | 'Normal' | 'AboveNormal' | 'High' | 'RealTime';
  Interval: number;
  WidthResolutions: number[];
  TileWidth: number;
  TileHeight: number;
  Qscale: number;
  JpegQuality: number;
  ProcessThreads: number;
}

/* The whole ServerConfiguration travels back on save, so unknown fields are kept as they came. */
export interface ServerConfig extends Rest {
  ServerName: string;
  UICulture: string;
  CachePath?: string | null;
  MetadataPath: string;
  QuickConnectAvailable: boolean;
  LibraryScanFanoutConcurrency: number;
  ParallelImageEncodingLimit: number;
  ActivityLogRetentionDays?: number | null;
  LogFileRetentionDays: number;
  InactiveSessionThreshold: number;
  AllowClientLogUpload: boolean;
  EnableSlowResponseWarning: boolean;
  SlowResponseThresholdMs: number;
  EnableMetrics: boolean;
  CorsHosts: string[];
  PathSubstitutions: PathSubstitution[];
  LibraryMonitorDelay: number;
  SortRemoveWords: string[];
  LibraryMetadataRefreshConcurrency: number;
  CastReceiverApplications: CastReceiver[];
  EnableLegacyAuthorization: boolean;
  PreferredMetadataLanguage: string;
  MetadataCountryCode: string;
  EnableFolderView: boolean;
  DisplaySpecialsWithinSeasons: boolean;
  EnableGroupingMoviesIntoCollections: boolean;
  EnableGroupingShowsIntoCollections: boolean;
  EnableExternalContentInSuggestions: boolean;
  DummyChapterDuration: number;
  ChapterImageResolution: string;
  SaveMetadataHidden: boolean;
  ImageSavingConvention: string;
  MinResumePct: number;
  MaxResumePct: number;
  MinResumeDurationSeconds: number;
  MinAudiobookResume: number;
  MaxAudiobookResume: number;
  RemoteClientBitrateLimit: number;
  TrickplayOptions: TrickplayOptions;
}

export const getServerConfig = (signal?: AbortSignal) => get<ServerConfig>('/System/Configuration', undefined, signal);
export const saveServerConfig = (cfg: ServerConfig) => post<void>('/System/Configuration', cfg);

/* Read-modify-write saves run one at a time: two overlapping ones would each read the config before the other
   wrote it, and the later write would undo the earlier change. */
let configQueue: Promise<unknown> = Promise.resolve();
function queued<T>(fn: () => Promise<T>): Promise<T> {
  const run = configQueue.then(fn, fn);
  configQueue = run.catch(() => undefined);
  return run;
}

/* Apply only what changed onto the server's current config, so edits made elsewhere meanwhile survive. */
export function patchServerConfig(patch: Partial<ServerConfig>): Promise<ServerConfig> {
  return queued(async () => {
    const fresh = await getServerConfig();
    const next = { ...fresh, ...patch } as ServerConfig;
    await saveServerConfig(next);
    return next;
  });
}

export interface BrandingOptions { LoginDisclaimer?: string | null; CustomCss?: string | null; SplashscreenEnabled: boolean }
export const getBranding = (signal?: AbortSignal) => get<BrandingOptions>('/System/Configuration/branding', undefined, signal);
export async function patchBranding(patch: Partial<BrandingOptions>): Promise<void> {
  const fresh = await getBranding();
  await post<void>('/System/Configuration/Branding', {
    LoginDisclaimer: fresh.LoginDisclaimer ?? null,
    CustomCss: fresh.CustomCss ?? null,
    SplashscreenEnabled: !!fresh.SplashscreenEnabled,
    ...patch
  });
}

export interface MetadataConfig { UseFileCreationTimeForDateAdded: boolean; [key: string]: unknown }
export interface NfoConfig {
  UserId?: string | null;
  ReleaseDateFormat: string;
  SaveImagePathsInNfo: boolean;
  EnablePathSubstitution: boolean;
  EnableExtraThumbsDuplication: boolean;
  [key: string]: unknown;
}
export const getNamedConfig = <T>(key: string, signal?: AbortSignal) => get<T>(`/System/Configuration/${key}`, undefined, signal);
export function patchNamedConfig<T extends object>(key: string, patch: Partial<T>): Promise<T> {
  return queued(async () => {
    const fresh = await getNamedConfig<T>(key);
    const next = { ...fresh, ...patch } as T;
    await post<void>(`/System/Configuration/${key}`, next);
    return next;
  });
}

/* Images go up as base64 with the image's own content type (Jellyfin's AcceptsImageFile endpoints). */
async function postImage(path: string, file: File, query?: Record<string, string>): Promise<void> {
  const data = await new Promise<string>((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result).replace(/^data:[^,]*,/, ''));
    r.onerror = () => reject(r.error ?? new Error(t('The file could not be read')));
    r.readAsDataURL(file);
  });
  const res = await fetch(url(path, query), {
    method: 'POST',
    headers: { Authorization: authHeader(), 'Content-Type': file.type || 'image/png' },
    body: data
  });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new ApiError(res.status, text.trim().slice(0, 300) || res.statusText || t('Upload failed'));
  }
}

export const uploadSplashscreen = (file: File) => postImage('/Branding/Splashscreen', file);
export const uploadUserImage = (userId: string, file: File) => postImage('/UserImage', file, { userId });
export const deleteUserImage = (userId: string) => del<void>('/UserImage', { userId });


/* ── Restart ── */

export const restartingStore = createStore<boolean>(false);

/* Ask Jellyfin to restart, then wait until it answers again. */
export async function restartServer(): Promise<void> {
  restartingStore.set(true);
  toast(t('Restarting the server…'), 'pending', 6000);
  try {
    await post<void>('/System/Restart');
  } catch (e) {
    restartingStore.set(false);
    throw e;
  }
  const started = Date.now();
  let wentDown = false;
  await new Promise((r) => window.setTimeout(r, 2500));
  while (Date.now() - started < 180_000) {
    try {
      const res = await fetch(url('/System/Info/Public'), { headers: { Accept: 'application/json' }, cache: 'no-store' });
      if (res.ok && (wentDown || Date.now() - started > 12_000)) break;
      if (!res.ok) wentDown = true;
    } catch {
      wentDown = true;
    }
    await new Promise((r) => window.setTimeout(r, 2000));
  }
  restartingStore.set(false);
  toast(t('The server is back'));
}

/* ── Server file system (folder picker) ── */

export interface FsEntry { Name: string; Path: string; Type: 'Directory' | 'File' | 'NetworkComputer' | 'NetworkShare' }
export const getDrives = () => get<FsEntry[]>('/Environment/Drives');
export const getDirectory = (path: string, includeFiles = false) =>
  get<FsEntry[]>('/Environment/DirectoryContents', { path, includeDirectories: true, includeFiles });
export const getParentPath = (path: string) => get<string | null>('/Environment/ParentPath', { path });

/* ── Localisation ── */

export interface LocalizationOption { Name: string; Value: string }
export interface Culture { Name: string; DisplayName: string; TwoLetterISOLanguageName: string; ThreeLetterISOLanguageName: string; ThreeLetterISOLanguageNames?: string[] }
export interface Country { Name: string; DisplayName: string; TwoLetterISORegionName: string }
export interface ParentalRating { Name: string; Value?: number | null; RatingScore?: { score: number; subScore?: number | null } | null }

export const getLocalizationOptions = (signal?: AbortSignal) => get<LocalizationOption[]>('/Localization/Options', undefined, signal);
export const getCultures = (signal?: AbortSignal) => get<Culture[]>('/Localization/Cultures', undefined, signal);
export const getCountries = (signal?: AbortSignal) => get<Country[]>('/Localization/Countries', undefined, signal);
export const getParentalRatings = (signal?: AbortSignal) => get<ParentalRating[]>('/Localization/ParentalRatings', undefined, signal);

/* ── Users ── */

export type SyncPlayAccess = 'CreateAndJoinGroups' | 'JoinGroups' | 'None';
export type UnratedItem = 'Movie' | 'Trailer' | 'Series' | 'Music' | 'Book' | 'LiveTvChannel' | 'LiveTvProgram' | 'ChannelContent' | 'Other';
export type AccessDay = 'Sunday' | 'Monday' | 'Tuesday' | 'Wednesday' | 'Thursday' | 'Friday' | 'Saturday' | 'Everyday' | 'Weekday' | 'Weekend';
export interface AccessSchedule { Id?: number; UserId?: string; DayOfWeek: AccessDay; StartHour: number; EndHour: number }

export interface FullPolicy {
  IsAdministrator: boolean;
  IsHidden: boolean;
  IsDisabled: boolean;
  EnableRemoteAccess: boolean;
  LoginAttemptsBeforeLockout: number;
  InvalidLoginAttemptCount?: number;
  MaxActiveSessions: number;
  EnableMediaPlayback: boolean;
  EnableVideoPlaybackTranscoding: boolean;
  EnableAudioPlaybackTranscoding: boolean;
  EnablePlaybackRemuxing: boolean;
  ForceRemoteSourceTranscoding: boolean;
  RemoteClientBitrateLimit: number;
  SyncPlayAccess: SyncPlayAccess;
  EnableContentDownloading: boolean;
  EnableContentDeletion: boolean;
  EnableCollectionManagement: boolean;
  EnableSubtitleManagement: boolean;
  EnableLiveTvAccess: boolean;
  EnableLiveTvManagement: boolean;
  EnableRemoteControlOfOtherUsers: boolean;
  EnableSharedDeviceControl: boolean;
  EnableAllFolders: boolean;
  EnabledFolders: string[];
  EnableAllDevices: boolean;
  EnabledDevices: string[];
  MaxParentalRating?: number | null;
  MaxParentalSubRating?: number | null;
  BlockUnratedItems: UnratedItem[];
  AllowedTags: string[];
  BlockedTags: string[];
  AccessSchedules: AccessSchedule[];
  [key: string]: unknown;
}

export interface FullUser extends Omit<User, 'Policy' | 'Configuration'> {
  Policy: FullPolicy;
  Configuration: UserConfiguration & Record<string, unknown>;
  LastLoginDate?: string;
  LastActivityDate?: string;
}

export const getUsers = (signal?: AbortSignal) => get<FullUser[]>('/Users', undefined, signal);
export const getUser = (id: string, signal?: AbortSignal) => get<FullUser>(`/Users/${id}`, undefined, signal);
export const createUser = (Name: string, Password?: string) => post<FullUser>('/Users/New', { Name, Password: Password || undefined });
export const deleteUser = (id: string) => del<void>(`/Users/${id}`);
export const saveUserPolicy = (id: string, policy: FullPolicy) => post<void>(`/Users/${id}/Policy`, policy);
/* Jellyfin replaces the whole policy, so changes go onto the policy as the server has it now, not as a page loaded it. */
export async function patchUserPolicy(id: string, patch: Partial<FullPolicy>): Promise<FullPolicy> {
  const fresh = await getUser(id);
  const next = { ...fresh.Policy, ...patch } as FullPolicy;
  await saveUserPolicy(id, next);
  return next;
}
export const saveUserConfiguration = (id: string, cfg: FullUser['Configuration']) => post<void>('/Users/Configuration', cfg, { userId: id });
/* Renaming goes through the full user object, and Jellyfin also saves the configuration it carries; take it fresh so
   settings the person changed since this page loaded aren't put back. */
export async function renameUser(user: FullUser, name: string): Promise<void> {
  const fresh = await getUser(user.Id);
  await post<void>('/Users', { ...fresh, Name: name }, { userId: user.Id });
}
export const setUserPassword = (id: string, NewPw: string, CurrentPw?: string) =>
  post<void>('/Users/Password', { NewPw, CurrentPw: CurrentPw ?? '', ResetPassword: false }, { userId: id });
export const resetUserPassword = (id: string) => post<void>('/Users/Password', { ResetPassword: true }, { userId: id });


/* ── Libraries ── */

export interface MediaPathInfo { Path: string; NetworkPath?: string }
export interface TypeOptions {
  Type: string;
  MetadataFetchers: string[];
  MetadataFetcherOrder: string[];
  ImageFetchers: string[];
  ImageFetcherOrder: string[];
  ImageOptions?: unknown[];
  SimilarItemProviders?: string[];
  SimilarItemProviderOrder?: string[];
}
export interface LibraryOptions {
  Enabled?: boolean;
  EnableRealtimeMonitor: boolean;
  EnableChapterImageExtraction: boolean;
  ExtractChapterImagesDuringLibraryScan: boolean;
  EnableTrickplayImageExtraction: boolean;
  ExtractTrickplayImagesDuringLibraryScan: boolean;
  PathInfos: MediaPathInfo[];
  SaveLocalMetadata: boolean;
  AutomaticRefreshIntervalDays: number;
  PreferredMetadataLanguage?: string | null;
  MetadataCountryCode?: string | null;
  DisabledSubtitleFetchers: string[];
  SubtitleFetcherOrder: string[];
  DisabledMediaSegmentProviders: string[];
  MediaSegmentProviderOrder: string[];
  SkipSubtitlesIfEmbeddedSubtitlesPresent: boolean;
  SkipSubtitlesIfAudioTrackMatches: boolean;
  SubtitleDownloadLanguages?: string[] | null;
  RequirePerfectSubtitleMatch: boolean;
  SaveSubtitlesWithMedia: boolean;
  SaveTrickplayWithMedia: boolean;
  AutomaticallyAddToCollection: boolean;
  TypeOptions: TypeOptions[];
  [key: string]: unknown;
}
export interface VirtualFolder {
  Name: string;
  Locations: string[];
  CollectionType?: string;
  LibraryOptions: LibraryOptions;
  ItemId: string;
  PrimaryImageItemId?: string;
  RefreshProgress?: number | null;
  RefreshStatus?: string;
}

export interface OptionInfo { Name: string; DefaultEnabled: boolean }
export interface LibraryTypeOptionsInfo {
  Type: string;
  MetadataFetchers: OptionInfo[];
  ImageFetchers: OptionInfo[];
  SimilarItemProviders?: OptionInfo[];
  SupportedImageTypes?: string[];
  DefaultImageOptions?: unknown[];
}
export interface AvailableOptions {
  MetadataSavers: OptionInfo[];
  MetadataReaders: OptionInfo[];
  SubtitleFetchers: OptionInfo[];
  LyricFetchers: OptionInfo[];
  MediaSegmentProviders: OptionInfo[];
  TypeOptions: LibraryTypeOptionsInfo[];
}

export const getVirtualFolders = (signal?: AbortSignal) => get<VirtualFolder[]>('/Library/VirtualFolders', undefined, signal);
export const getAvailableOptions = (collectionType: string | undefined, isNewLibrary: boolean, signal?: AbortSignal) =>
  get<AvailableOptions>('/Libraries/AvailableOptions', { libraryContentType: collectionType || undefined, isNewLibrary }, signal);
export const saveLibraryOptions = (Id: string, LibraryOptions: LibraryOptions) => post<void>('/Library/VirtualFolders/LibraryOptions', { Id, LibraryOptions });
/* The folders go in LibraryOptions.PathInfos: the `paths` query parameter is split on commas, which breaks folders
   with a comma in their name. */
export const addLibrary = (name: string, collectionType: string | undefined, paths: string[], LibraryOptions: Partial<LibraryOptions>) =>
  request<void>('POST', '/Library/VirtualFolders', {
    query: { name, collectionType: collectionType || undefined, refreshLibrary: true },
    body: { LibraryOptions: { ...LibraryOptions, PathInfos: paths.map((Path) => ({ Path })) } }
  });
export const removeLibrary = (name: string) => del<void>('/Library/VirtualFolders', { name, refreshLibrary: true });
export const addLibraryPath = (Name: string, Path: string) =>
  post<void>('/Library/VirtualFolders/Paths', { Name, PathInfo: { Path } }, { refreshLibrary: false });
export const removeLibraryPath = (name: string, path: string) =>
  del<void>('/Library/VirtualFolders/Paths', { name, path, refreshLibrary: false });
export const refreshItem = (id: string, mode: 'scan' | 'metadata') =>
  post<void>(`/Items/${id}/Refresh`, undefined, mode === 'scan'
    ? { Recursive: true, ImageRefreshMode: 'Default', MetadataRefreshMode: 'Default', ReplaceAllImages: false, ReplaceAllMetadata: false }
    : { Recursive: true, ImageRefreshMode: 'FullRefresh', MetadataRefreshMode: 'FullRefresh', ReplaceAllImages: false, ReplaceAllMetadata: false });

export const countItems = (parentId: string, includeItemTypes?: string[], signal?: AbortSignal) =>
  get<{ TotalRecordCount: number }>('/Items', {
    parentId, recursive: true, limit: 0, includeItemTypes, enableTotalRecordCount: true, enableImages: false, enableUserData: false,
    excludeItemTypes: includeItemTypes ? undefined : ['Folder', 'CollectionFolder', 'Season']
  }, signal);

export interface ItemCounts { MovieCount: number; SeriesCount: number; EpisodeCount: number; AlbumCount: number; SongCount: number; MusicVideoCount: number; BookCount: number; BoxSetCount: number; [k: string]: number }
export const getItemCounts = (signal?: AbortSignal) => get<ItemCounts>('/Items/Counts', undefined, signal);

/* ── Playback: EncodingOptions (/System/Configuration/encoding) ── */

export type HwAccel = 'none' | 'amf' | 'qsv' | 'nvenc' | 'v4l2m2m' | 'vaapi' | 'videotoolbox' | 'rkmpp';
export type TonemapAlgorithm = 'none' | 'clip' | 'linear' | 'gamma' | 'reinhard' | 'hable' | 'mobius' | 'bt2390';
export type EncoderPreset = 'auto' | 'placebo' | 'veryslow' | 'slower' | 'slow' | 'medium' | 'fast' | 'faster' | 'veryfast' | 'superfast' | 'ultrafast';

export interface EncodingOptions extends Rest {
  EncodingThreadCount: number;
  TranscodingTempPath?: string | null;
  FallbackFontPath?: string | null;
  EnableFallbackFont: boolean;
  EnableAudioVbr: boolean;
  DownMixAudioBoost: number;
  DownMixStereoAlgorithm: string;
  MaxMuxingQueueSize: number;
  EnableThrottling: boolean;
  ThrottleDelaySeconds: number;
  EnableSegmentDeletion: boolean;
  SegmentKeepSeconds: number;
  HardwareAccelerationType: HwAccel;
  EncoderAppPath?: string | null;
  EncoderAppPathDisplay?: string | null;
  VaapiDevice: string;
  QsvDevice: string;
  EnableTonemapping: boolean;
  EnableVppTonemapping: boolean;
  EnableVideoToolboxTonemapping: boolean;
  TonemappingAlgorithm: TonemapAlgorithm;
  H264Crf: number;
  H265Crf: number;
  EncoderPreset: EncoderPreset;
  DeinterlaceDoubleRate: boolean;
  DeinterlaceMethod: string;
  EnableDecodingColorDepth10Hevc: boolean;
  EnableDecodingColorDepth10Vp9: boolean;
  EnableDecodingColorDepth10HevcRext: boolean;
  EnableDecodingColorDepth12HevcRext: boolean;
  EnableEnhancedNvdecDecoder: boolean;
  PreferSystemNativeHwDecoder: boolean;
  EnableIntelLowPowerH264HwEncoder: boolean;
  EnableIntelLowPowerHevcHwEncoder: boolean;
  EnableHardwareEncoding: boolean;
  AllowHevcEncoding: boolean;
  AllowAv1Encoding: boolean;
  EnableSubtitleExtraction: boolean;
  HardwareDecodingCodecs: string[];
}

export const getEncoding = () => get<EncodingOptions>('/System/Configuration/encoding');
export const saveEncoding = (o: EncodingOptions) => post<void>('/System/Configuration/encoding', o);

/* ── FFmpeg and GPUs (Playback) ── */

/* FFmpeg's version isn't part of any DTO. Every FFmpeg log in the server's log folder starts with ffmpeg's banner
   ("ffmpeg version 7.1.1-Jellyfin …"), and the server log says "Found ffmpeg version 7.1.1" at start-up, so read
   the newest of those, only as far as the version line. */

async function scanLog(name: string, pattern: RegExp, maxBytes: number): Promise<string | null> {
  const ctrl = new AbortController();
  const res = await fetch(url('/System/Logs/Log', { name }), { headers: { Authorization: authHeader(), Accept: 'text/plain' }, signal: ctrl.signal });
  if (!res.ok || !res.body) return null;
  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let text = '';
  try {
    while (text.length < maxBytes) {
      const { value, done } = await reader.read();
      if (done) break;
      text += dec.decode(value, { stream: true });
      const m = pattern.exec(text);
      if (m) return m[1];
    }
    return pattern.exec(text)?.[1] ?? null;
  } finally {
    ctrl.abort();
  }
}

export async function ffmpegVersion(): Promise<string | null> {
  const logs = await get<LogFile[]>('/System/Logs');
  const byDate = [...logs].sort((a, b) => b.DateModified.localeCompare(a.DateModified));
  for (const f of byDate.filter((l) => /^FFmpeg\./i.test(l.Name)).slice(0, 2)) {
    const v = await scanLog(f.Name, /ffmpeg version n?(\d+(?:\.\d+)*)/i, 64_000).catch(() => null);
    if (v) return v;
  }
  for (const f of byDate.filter((l) => /^log_/i.test(l.Name)).slice(0, 3)) {
    const v = await scanLog(f.Name, /Found ffmpeg version (\d+(?:\.\d+)*)/i, 400_000).catch(() => null);
    if (v) return v;
  }
  return null;
}

/* What the server can see of its GPUs: render nodes in /dev/dri, NVIDIA and Rockchip device nodes in /dev, and
   the kernel drivers bound on the PCI bus. Only Linux servers answer; anything else reports nothing found. */
export interface GpuScan { renderNodes: string[]; intel: boolean; amd: boolean; nvidia: boolean; rockchip: boolean; mac: boolean }

export async function scanGpus(programDataPath?: string): Promise<GpuScan> {
  const list = (p: string, files = true) => getDirectory(p, files).catch(() => [] as FsEntry[]);
  const mac = !!programDataPath && /\/Library\/Application Support\//.test(programDataPath);
  const dev = programDataPath?.startsWith('/') && !mac ? await list('/dev') : [];
  const names = new Set(dev.map((d) => d.Name));
  const dri = names.has('dri') ? await list('/dev/dri') : [];
  const renderNodes = dri.filter((d) => /^renderD\d+$/.test(d.Name)).map((d) => d.Path);
  const drv = new Set(renderNodes.length ? (await list('/sys/bus/pci/drivers', false)).map((d) => d.Name) : []);
  return {
    renderNodes,
    intel: renderNodes.length > 0 && (drv.has('i915') || drv.has('xe')),
    amd: renderNodes.length > 0 && (drv.has('amdgpu') || drv.has('radeon')),
    nvidia: names.has('nvidia0') || names.has('nvidiactl'),
    rockchip: names.has('mpp_service') || names.has('rga'),
    mac
  };
}

/* ── Networking: NetworkConfiguration (/System/Configuration/network) ── */

export interface NetworkConfig extends Rest {
  BaseUrl: string;
  EnableHttps: boolean;
  RequireHttps: boolean;
  CertificatePath: string;
  CertificatePassword: string;
  InternalHttpPort: number;
  InternalHttpsPort: number;
  PublicHttpPort: number;
  PublicHttpsPort: number;
  AutoDiscovery: boolean;
  EnableIPv6: boolean;
  EnableRemoteAccess: boolean;
  LocalNetworkSubnets: string[];
  LocalNetworkAddresses: string[];
  KnownProxies: string[];
  IgnoreVirtualInterfaces: boolean;
  PublishedServerUriBySubnet: string[];
  RemoteIPFilter: string[];
  IsRemoteIPFilterBlacklist: boolean;
}

export const getNetwork = () => get<NetworkConfig>('/System/Configuration/network');
export const saveNetwork = (c: NetworkConfig) => post<void>('/System/Configuration/network', c);

/* ── Live TV (/System/Configuration/livetv and /LiveTv/*) ── */

export interface TunerHostInfo extends Rest {
  Id?: string | null;
  Url?: string | null;
  Type: string;
  DeviceId?: string | null;
  FriendlyName?: string | null;
  ImportFavoritesOnly: boolean;
  AllowHWTranscoding: boolean;
  AllowFmp4TranscodingContainer: boolean;
  AllowStreamSharing: boolean;
  FallbackMaxStreamingBitrate: number;
  EnableStreamLooping: boolean;
  Source?: string | null;
  TunerCount: number;
  UserAgent?: string | null;
  IgnoreDts: boolean;
  ReadAtNativeFramerate: boolean;
}

export interface NameValue { Name: string; Value: string }

export interface ListingsProvider extends Rest {
  Id?: string | null;
  Type: string;
  Username?: string | null;
  Password?: string | null;
  ListingsId?: string | null;
  ZipCode?: string | null;
  Country?: string | null;
  Path?: string | null;
  EnabledTuners: string[];
  EnableAllTuners: boolean;
  NewsCategories: string[];
  SportsCategories: string[];
  KidsCategories: string[];
  MovieCategories: string[];
  ChannelMappings: NameValue[];
  MoviePrefix?: string | null;
  PreferredLanguage?: string | null;
  UserAgent?: string | null;
}

export interface LiveTvOptions extends Rest {
  GuideDays?: number | null;
  RecordingPath?: string | null;
  MovieRecordingPath?: string | null;
  SeriesRecordingPath?: string | null;
  TunerHosts: TunerHostInfo[];
  ListingProviders: ListingsProvider[];
  PrePaddingSeconds: number;
  PostPaddingSeconds: number;
  RecordingPostProcessor?: string | null;
  RecordingPostProcessorArguments?: string | null;
  SaveRecordingNFO: boolean;
  SaveRecordingImages: boolean;
}

export interface NameIdPair { Name: string; Id: string }
export interface TunerChannelMapping { Name: string; ProviderChannelName?: string | null; ProviderChannelId?: string | null; Id: string }
export interface ChannelMappingOptions { TunerChannels: TunerChannelMapping[]; ProviderChannels: NameIdPair[]; Mappings: NameValue[]; ProviderName?: string | null }

export const getLiveTv = () => get<LiveTvOptions>('/System/Configuration/livetv');
export const saveLiveTv = (o: LiveTvOptions) => post<void>('/System/Configuration/livetv', o);
export const saveTuner = (t: TunerHostInfo) => post<TunerHostInfo>('/LiveTv/TunerHosts', t);
export const deleteTuner = (id: string) => del<void>('/LiveTv/TunerHosts', { id });
export const discoverTuners = () => get<TunerHostInfo[]>('/LiveTv/Tuners/Discover', { newDevicesOnly: false });
export const saveListingProvider = (p: ListingsProvider, opts: { pw?: string; validateListings?: boolean; validateLogin?: boolean } = {}) =>
  request<ListingsProvider>('POST', '/LiveTv/ListingProviders', { body: p, query: { pw: opts.pw, validateListings: opts.validateListings, validateLogin: opts.validateLogin } });
export const deleteListingProvider = (id: string) => del<void>('/LiveTv/ListingProviders', { id });
export const getLineups = (p: { id?: string | null; type: string; location?: string | null; country?: string | null }) =>
  get<NameIdPair[]>('/LiveTv/ListingProviders/Lineups', { id: p.id ?? undefined, type: p.type, location: p.location ?? undefined, country: p.country ?? undefined });
export const getSdCountries = () => get<Record<string, { fullName: string; shortName: string; postalCode?: string; postalCodeExample?: string }[]>>('/LiveTv/ListingProviders/SchedulesDirect/Countries');
export const getChannelMappingOptions = (providerId: string) => get<ChannelMappingOptions>('/LiveTv/ChannelMappingOptions', { providerId });
export const setChannelMapping = (ProviderId: string, TunerChannelId: string, ProviderChannelId: string) =>
  post<TunerChannelMapping>('/LiveTv/ChannelMappings', { ProviderId, TunerChannelId, ProviderChannelId });
export const getChannelCount = (userId: string) => get<ItemsResult>('/LiveTv/Channels', { userId, limit: 0, enableImages: false, enableUserData: false });

/* ── Sessions (Overview, Devices) ── */

export interface TranscodingInfo {
  AudioCodec?: string;
  VideoCodec?: string;
  Container?: string;
  IsVideoDirect?: boolean;
  IsAudioDirect?: boolean;
  Bitrate?: number;
  Width?: number;
  Height?: number;
  AudioChannels?: number;
  HardwareAccelerationType?: string;
  TranscodeReasons?: string[] | string;
}

export interface Session {
  Id: string;
  UserId?: string;
  UserName?: string;
  UserPrimaryImageTag?: string;
  Client?: string;
  DeviceName?: string;
  DeviceId?: string;
  ApplicationVersion?: string;
  RemoteEndPoint?: string;
  LastActivityDate?: string;
  IsActive?: boolean;
  SupportsMediaControl?: boolean;
  SupportsRemoteControl?: boolean;
  SupportedCommands?: string[];
  NowPlayingItem?: BaseItem;
  PlayState?: { PositionTicks?: number; IsPaused?: boolean; PlayMethod?: 'DirectPlay' | 'DirectStream' | 'Transcode'; MediaSourceId?: string; AudioStreamIndex?: number };
  TranscodingInfo?: TranscodingInfo;
}

export const getSessions = (signal?: AbortSignal) => get<Session[]>('/Sessions', { activeWithinSeconds: 960 }, signal);
export const sendSessionMessage = (id: string, Header: string, Text: string) =>
  post<void>(`/Sessions/${id}/Message`, { Header, Text, TimeoutMs: 15000 });
export const stopSession = (id: string) => post<void>(`/Sessions/${id}/Playing/Stop`);

/* ── Devices ── */

export interface DeviceInfo {
  Name?: string | null;
  CustomName?: string | null;
  Id: string;
  LastUserName?: string | null;
  AppName?: string | null;
  AppVersion?: string | null;
  LastUserId?: string | null;
  DateLastActivity?: string | null;
  IconUrl?: string | null;
}

export const getDevices = (userId?: string, signal?: AbortSignal) =>
  get<ItemsResult<DeviceInfo>>('/Devices', userId ? { userId } : undefined, signal);
export const renameDevice = (id: string, CustomName: string) => request<void>('POST', '/Devices/Options', { query: { id }, body: { CustomName } });
/* DELETE /Devices binds `id` as a repeated query parameter (id=a&id=b), not a comma list. Long lists go in batches
   so the request line stays under Kestrel's 8 KB limit. */
export async function deleteDevices(ids: string[]): Promise<void> {
  const remove = (batch: string[]) => request<void>('DELETE', '/Devices?' + batch.map((id) => 'id=' + encodeURIComponent(id)).join('&'));
  for (let i = 0; i < ids.length; i += 40) {
    const batch = ids.slice(i, i + 40);
    try {
      await remove(batch);
    } catch (e) {
      /* Jellyfin refuses the whole batch (400) when one device has gone since the list loaded: retry one by one and
         skip the ones that are already gone. */
      if (!(e instanceof ApiError) || e.status !== 400) throw e;
      for (const id of batch) {
        try { await remove([id]); } catch (err) { if (!(err instanceof ApiError) || err.status !== 400) throw err; }
      }
    }
  }
}

const deleteDevice = (id: string) => del<void>('/Devices', { id });

/* Sign a user out on every device: removing a device ends its session and revokes its token. This device stays. */
export async function signOutEverywhere(userId: string): Promise<number> {
  const { Items } = await getDevices(userId);
  const mine = getSession();
  const own = deviceId();
  const list = Items.filter((d) => normId(d.LastUserId) === normId(userId) && !(mine && normId(mine.userId) === normId(userId) && d.Id === own));
  let n = 0;
  for (const d of list) {
    try { await deleteDevice(d.Id); n++; } catch { /* already gone */ }
  }
  return n;
}

/* ── Activity log ── */

export interface ActivityEntry {
  Id: number;
  Name: string;
  Overview?: string | null;
  ShortOverview?: string | null;
  Type: string;
  ItemId?: string | null;
  Date: string;
  UserId?: string | null;
  UserPrimaryImageTag?: string | null;
  Severity: 'Trace' | 'Debug' | 'Information' | 'Warning' | 'Error' | 'Critical' | 'None';
}

export const getActivity = (q: { startIndex?: number; limit?: number; minDate?: string; maxDate?: string; type?: string; severity?: string; hasUserId?: boolean }, signal?: AbortSignal) =>
  get<ItemsResult<ActivityEntry>>('/System/ActivityLog/Entries', q, signal);

/* ── Plugins, packages, repositories ── */

export type PluginStatus = 'Active' | 'Restart' | 'Disabled' | 'NotSupported' | 'Malfunctioned' | 'Superceded' | 'Superseded' | 'Deleted';

export interface PluginInfo {
  Name: string;
  Version: string;
  ConfigurationFileName?: string | null;
  Description: string;
  Id: string;
  CanUninstall: boolean;
  HasImage: boolean;
  Status: PluginStatus;
}

export interface PackageVersion {
  version: string;
  VersionNumber?: string;
  changelog?: string | null;
  targetAbi?: string | null;
  sourceUrl?: string | null;
  checksum?: string | null;
  timestamp?: string | null;
  repositoryName: string;
  repositoryUrl: string;
}

export interface PackageInfo {
  name: string;
  description?: string;
  overview?: string;
  owner?: string;
  category?: string;
  guid: string;
  versions: PackageVersion[];
  imageUrl?: string | null;
}

export interface RepositoryInfo { Name: string; Url: string; Enabled: boolean }

export interface ConfigPage { Name: string; DisplayName?: string; PluginId?: string; EnableInMainMenu?: boolean }

export const getPlugins = (signal?: AbortSignal) => get<PluginInfo[]>('/Plugins', undefined, signal);
export const getPackages = (signal?: AbortSignal) => get<PackageInfo[]>('/Packages', undefined, signal);
export const getRepositories = () => get<RepositoryInfo[]>('/Repositories');
export const setRepositories = (list: RepositoryInfo[]) => post<void>('/Repositories', list);
export const getConfigPages = () => get<ConfigPage[]>('/web/ConfigurationPages');
export const enablePlugin = (p: PluginInfo) => post<void>(`/Plugins/${p.Id}/${p.Version}/Enable`);
export const disablePlugin = (p: PluginInfo) => post<void>(`/Plugins/${p.Id}/${p.Version}/Disable`);
export const getPluginManifest = (id: string) => post<{ TargetAbi?: string; Version?: string; Owner?: string }>(`/Plugins/${id}/Manifest`);
export const uninstallPlugin = (p: PluginInfo) => del<void>(`/Plugins/${p.Id}/${p.Version}`);
export const installPackage = (name: string, guid: string, version: string, repositoryUrl: string) =>
  post<void>(`/Packages/Installed/${encodeURIComponent(name)}`, undefined, { assemblyGuid: guid, version, repositoryUrl });

/* A plugin's own settings page is Jellyfin web-app HTML, so it opens there. */
export const configPageUrl = (name: string) => JELLYFIN_WEB + '#/configurationpage?name=' + encodeURIComponent(name);

/* ── Scheduled tasks ── */

export type TriggerType = 'DailyTrigger' | 'WeeklyTrigger' | 'IntervalTrigger' | 'StartupTrigger';
export type DayOfWeek = 'Sunday' | 'Monday' | 'Tuesday' | 'Wednesday' | 'Thursday' | 'Friday' | 'Saturday';

export interface TaskTrigger {
  Type: TriggerType;
  TimeOfDayTicks?: number | null;
  IntervalTicks?: number | null;
  DayOfWeek?: DayOfWeek | null;
  MaxRuntimeTicks?: number | null;
}

export interface TaskResult {
  StartTimeUtc: string;
  EndTimeUtc: string;
  Status: 'Completed' | 'Failed' | 'Cancelled' | 'Aborted';
  Name?: string;
  Key?: string;
  Id?: string;
  ErrorMessage?: string | null;
  LongErrorMessage?: string | null;
}

export interface TaskInfo {
  Name: string;
  State: 'Idle' | 'Cancelling' | 'Running';
  CurrentProgressPercentage?: number | null;
  Id: string;
  LastExecutionResult?: TaskResult | null;
  Triggers: TaskTrigger[];
  Description: string;
  Category: string;
  IsHidden: boolean;
  Key: string;
}

export const getTasks = (signal?: AbortSignal) => get<TaskInfo[]>('/ScheduledTasks', { isHidden: false }, signal);
export const startTask = (id: string) => post<void>(`/ScheduledTasks/Running/${encodeURIComponent(id)}`);
export const stopTask = (id: string) => del<void>(`/ScheduledTasks/Running/${id}`);
export const setTaskTriggers = (id: string, triggers: TaskTrigger[]) => post<void>(`/ScheduledTasks/${id}/Triggers`, triggers);

/* ── Backups ── */

export interface BackupOptions { Database: boolean; Metadata: boolean; Subtitles: boolean; Trickplay: boolean }

export interface BackupManifest {
  ServerVersion: string;
  BackupEngineVersion: string;
  DateCreated: string;
  Path: string;
  Options: BackupOptions;
}

export const getBackups = () => get<BackupManifest[]>('/Backup');
export const createBackup = (options: BackupOptions) => post<BackupManifest>('/Backup/Create', options);
export const restoreBackup = (archiveFileName: string) => post<void>('/Backup/Restore', { ArchiveFileName: archiveFileName });

/* Jellyfin 12 can create, list and restore backups, but not size, serve, delete or schedule them. The Seedy
   plugin fills that gap (see the round-2 report); until it does, the page falls back to what Jellyfin offers. */
export interface BackupFile { fileName: string; size: number; kind: 'manual' | 'automatic' | 'upgrade' }
export interface BackupSchedule { enabled: boolean; dayOfWeek: DayOfWeek; timeOfDay: string; keep: number; options: BackupOptions }

/* An older plugin answers unknown /Seedy paths with the app's index.html; treat that as "not there". */
const missing = () => new ApiError(404, 'Not available on this server');
export const seedyBackups = () => get<BackupFile[]>('/Seedy/Admin/BackupFiles').then((r) => {
  if (!Array.isArray(r)) throw missing();
  return r;
});
export const seedyBackupSchedule = () => get<BackupSchedule>('/Seedy/Admin/BackupSchedule').then((r) => {
  if (!r || typeof r !== 'object' || !('options' in r)) throw missing();
  return r;
});
export const saveSeedyBackupSchedule = (s: BackupSchedule) => post<BackupSchedule>('/Seedy/Admin/BackupSchedule', s);
export const deleteSeedyBackup = (fileName: string) => del<void>(`/Seedy/Admin/BackupFiles/${encodeURIComponent(fileName)}`);
export const seedyBackupDownloadUrl = (fileName: string) =>
  url(`/Seedy/Admin/BackupFiles/${encodeURIComponent(fileName)}/File`, { ApiKey: getSession()?.token ?? '' });

/* ── Logs ── */

export interface LogFile { DateCreated: string; DateModified: string; Size: number; Name: string }

export const getLogFiles = (signal?: AbortSignal) => get<LogFile[]>('/System/Logs', undefined, signal);
export const getLogText = (name: string, signal?: AbortSignal) =>
  request<Response>('GET', '/System/Logs/Log', { query: { name }, raw: true, signal }).then((r) => r.text());

/* ── API keys ── */

export interface ApiKeyInfo {
  Id?: number;
  AccessToken: string;
  DeviceId?: string;
  AppName: string;
  AppVersion?: string;
  DeviceName?: string;
  UserId?: string;
  IsActive?: boolean;
  DateCreated: string;
  DateRevoked?: string | null;
  DateLastActivity?: string | null;
}

export const getApiKeys = () => get<{ Items: ApiKeyInfo[]; TotalRecordCount: number }>('/Auth/Keys');
export const createApiKey = (app: string) => post<void>('/Auth/Keys', undefined, { app });
export const revokeApiKey = (key: string) => del<void>(`/Auth/Keys/${encodeURIComponent(key)}`);

/* ── Errors ── */

export const isNotFound = (e: unknown) => e instanceof ApiError && (e.status === 404 || e.status === 405);

/* Fixed English that Seedy or the browser puts in errors; anything else is the server's own wording and stays. */
const KNOWN_ERRORS = new Set(['Not available on this server.', 'Failed to fetch.']);
const GENERIC_ERRORS = /^(Bad Request|Internal Server Error|Not Found|Request failed|Error processing request)\.?$/i;

/* A short sentence for an error: the server's own reason, or `fallback` when it gives none worth showing. */
export function errText(e: unknown, fallback = t('The server didn’t accept the change.')): string {
  const m = (e instanceof Error ? e.message : typeof e === 'string' ? e : '').trim();
  if (!m || GENERIC_ERRORS.test(m)) return fallback;
  const out = /[.!?]$/.test(m) ? m : m + '.';
  return KNOWN_ERRORS.has(out) ? t(out) : out;
}

/* The reason to put after "Couldn’t load the keys.": the server's own words, or that it didn't answer. */
export const reasonText = (e: unknown) => errText(e, t('The server did not answer.'));
