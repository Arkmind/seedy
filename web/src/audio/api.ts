import { SERVER_URL, authHeader, deviceId, get, getSession, post } from '../api/client';
import { getItem, getItems, getPlaybackInfo, type ItemsQuery } from '../api/jellyfin';
import type { BaseItem, ItemsResult, MediaSource, MediaStream, NameId, PlaybackInfoResponse } from '../api/types';
import { buildDeviceProfile } from '../player/device-profile';

/* Jellyfin calls behind the audio engine and the music screens: tracks of an album, playlist, artist or genre,
   instant mixes, lyrics, stream decisions and playback reports. */

export type Track = BaseItem & {
  AlbumArtists?: NameId[];
  HasLyrics?: boolean;
  /* Book items carry their author here when Jellyfin read it from the file's tags. */
  CumulativeRunTimeTicks?: number;
  MediaSourceCount?: number;
};

export type AudioStream = MediaStream & { BitDepth?: number; SampleRate?: number };

const uid = () => getSession()?.userId ?? '';

/* What a track row, the queue and the players need. */
export const TRACK_FIELDS = ['ParentId', 'Genres', 'DateCreated', 'ProductionYear', 'PrimaryImageAspectRatio', 'MediaSources'];

export const AUDIO_TYPES = new Set(['Audio', 'AudioBook', 'MusicAlbum', 'MusicArtist', 'MusicGenre']);

/* Whether "Play" on this item belongs to the audio player rather than the video one. */
export function isAudioItem(item: Pick<BaseItem, 'Type' | 'MediaType' | 'CollectionType'>): boolean {
  if (AUDIO_TYPES.has(item.Type)) return true;
  if (item.Type === 'Playlist' || item.Type === 'Folder') return item.MediaType === 'Audio';
  if (item.Type === 'CollectionFolder' || item.Type === 'UserView') return item.CollectionType === 'music';
  return item.MediaType === 'Audio';
}

export function orderByIds<T extends { Id: string }>(items: T[], ids: string[]): T[] {
  const map = new Map(items.map((i) => [i.Id, i]));
  return ids.map((id) => map.get(id)).filter((x): x is T => !!x);
}

export async function getTracksByIds(ids: string[]): Promise<Track[]> {
  if (!ids.length) return [];
  const out: Track[] = [];
  for (let i = 0; i < ids.length; i += 150) {
    const chunk = ids.slice(i, i + 150);
    const r = await getItems({ ids: chunk, fields: TRACK_FIELDS, enableImageTypes: ['Primary'], recursive: true });
    out.push(...orderByIds(r.Items as Track[], chunk));
  }
  return out;
}

export const albumTracks = (albumId: string) =>
  getItems({ parentId: albumId, includeItemTypes: ['Audio'], sortBy: ['ParentIndexNumber', 'IndexNumber', 'SortName'], limit: 1000, fields: TRACK_FIELDS })
    .then((r) => r.Items as Track[]);

export const playlistTracks = (playlistId: string) =>
  get<ItemsResult>(`/Playlists/${playlistId}/Items`, { userId: uid(), fields: TRACK_FIELDS.join(','), enableImageTypes: 'Primary', imageTypeLimit: 1, enableUserData: true })
    .then((r) => r.Items as Track[]);

/* An artist's songs: their albums newest first, each in disc and track order; songs where they only feature last. */
export async function artistTracks(artistId: string, limit = 600): Promise<Track[]> {
  const r = await getItems({
    artistIds: [artistId], includeItemTypes: ['Audio'], recursive: true, limit,
    sortBy: ['PremiereDate', 'ProductionYear', 'Album', 'ParentIndexNumber', 'IndexNumber', 'SortName'], sortOrder: 'Descending', fields: TRACK_FIELDS
  });
  // Descending reverses the track order too: put each album back in order, keeping albums newest first.
  const groups = new Map<string, Track[]>();
  for (const tk of r.Items as Track[]) {
    const k = tk.AlbumId ?? tk.Album ?? '';
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k)!.push(tk);
  }
  const byTrack = (a: Track, b: Track) => (a.ParentIndexNumber ?? 1) - (b.ParentIndexNumber ?? 1) || (a.IndexNumber ?? 0) - (b.IndexNumber ?? 0);
  return [...groups.values()].flatMap((g) => g.sort(byTrack));
}

export const genreTracks = (genreId: string, parentId?: string, limit = 300) =>
  getItems({ genreIds: [genreId], parentId, includeItemTypes: ['Audio'], recursive: true, sortBy: ['Random'], limit, fields: TRACK_FIELDS })
    .then((r) => r.Items as Track[]);

export const randomTracks = (q: ItemsQuery, limit = 300) =>
  getItems({ ...q, includeItemTypes: ['Audio'], recursive: true, sortBy: ['Random'], limit, fields: TRACK_FIELDS, enableTotalRecordCount: false })
    .then((r) => r.Items as Track[]);

export const folderTracks = (folderId: string) =>
  getItems({ parentId: folderId, includeItemTypes: ['Audio'], recursive: true, sortBy: ['Album', 'ParentIndexNumber', 'IndexNumber', 'SortName'], limit: 1000, fields: TRACK_FIELDS })
    .then((r) => r.Items as Track[]);

/* Jellyfin's instant mix for any song, album, artist, playlist or genre. */
export const instantMix = (id: string, limit = 120) =>
  get<ItemsResult>(`/Items/${id}/InstantMix`, { userId: uid(), limit, fields: TRACK_FIELDS.join(','), enableImageTypes: 'Primary', imageTypeLimit: 1, enableUserData: true })
    .then((r) => r.Items as Track[]);

export const genreMix = (genreId: string, limit = 120) =>
  get<ItemsResult>('/MusicGenres/InstantMix', { id: genreId, userId: uid(), limit, fields: TRACK_FIELDS.join(','), enableImageTypes: 'Primary', imageTypeLimit: 1, enableUserData: true })
    .then((r) => r.Items as Track[]);

/* What playing an album, playlist, artist, genre, folder or library means, as a list of songs. */
export async function containerTracks(item: BaseItem): Promise<Track[]> {
  switch (item.Type) {
    case 'MusicAlbum': return albumTracks(item.Id);
    case 'Playlist': return playlistTracks(item.Id);
    case 'MusicArtist': return artistTracks(item.Id);
    case 'MusicGenre': case 'Genre': return genreTracks(item.Id);
    case 'CollectionFolder': case 'UserView': return randomTracks({ parentId: item.Id });
    case 'Audio': return [item as Track];
    default: return folderTracks(item.Id);
  }
}

export const getAudioItem = (id: string) => getItem(id, [...TRACK_FIELDS, 'Overview', 'Chapters', 'People', 'Studios', 'ProviderIds', 'MediaStreams', 'Tags']) as Promise<Track>;

/* ── Lyrics ── */

export interface LyricLine { Text: string; Start?: number }
export interface Lyrics { Metadata?: { Artist?: string; Album?: string; Title?: string; Author?: string; Length?: number; IsSynced?: boolean }; Lyrics: LyricLine[] }

export const getLyrics = (id: string) => get<Lyrics>(`/Audio/${id}/Lyrics`);

/* ── Streams ── */

export interface Stream {
  url: string;
  method: 'DirectPlay' | 'DirectStream' | 'Transcode';
  source: MediaSource;
  playSessionId: string;
  /* A conversion starts at the asked position: the element's time 0 is this many seconds in. */
  offset: number;
}

const fixPath = (p: string) => p.replace(/^\/audio\//, '/Audio/');
export const withKey = (path: string) => {
  const u = fixPath(path);
  const token = getSession()?.token ?? '';
  if (/[?&](ApiKey|api_key)=/i.test(u)) return SERVER_URL + u;
  return SERVER_URL + u + (u.includes('?') ? '&' : '?') + 'ApiKey=' + encodeURIComponent(token);
};

/* Asks the server how to play [id] here: the file as it is when the browser can, otherwise a conversion. */
export async function openStream(id: string, startSeconds: number, convert = false): Promise<Stream> {
  const maxBitrate = 140_000_000;
  const profile = buildDeviceProfile(maxBitrate);
  const startTicks = Math.round(startSeconds * 10_000_000);
  const res: PlaybackInfoResponse = await getPlaybackInfo(id, {
    DeviceProfile: profile, MaxStreamingBitrate: maxBitrate, StartTimeTicks: startTicks,
    EnableDirectPlay: !convert, EnableDirectStream: !convert, EnableTranscoding: true, AllowAudioStreamCopy: !convert
  });
  const source = res.MediaSources?.[0];
  if (!source) throw new Error(res.ErrorCode || 'No media source');
  if (!convert && source.SupportsDirectPlay) {
    const q = new URLSearchParams({ static: 'true', mediaSourceId: source.Id, deviceId: deviceId(), PlaySessionId: res.PlaySessionId, ApiKey: getSession()?.token ?? '' });
    return { url: `${SERVER_URL}/Audio/${id}/stream?${q}`, method: 'DirectPlay', source, playSessionId: res.PlaySessionId, offset: 0 };
  }
  if (!source.TranscodingUrl) throw new Error(res.ErrorCode || 'Not playable');
  let url = withKey(source.TranscodingUrl);
  if (startTicks > 0 && !/StartTimeTicks=/i.test(url)) url += `&StartTimeTicks=${startTicks}`;
  const copy = /AudioCodec=copy|static=true/i.test(url) || source.SupportsDirectStream;
  return { url, method: copy && !convert ? 'DirectStream' : 'Transcode', source, playSessionId: res.PlaySessionId, offset: startTicks > 0 ? startSeconds : 0 };
}

/* ── Reports ── */

export interface Report {
  ItemId: string;
  MediaSourceId?: string;
  PlaySessionId?: string;
  PositionTicks?: number;
  IsPaused?: boolean;
  IsMuted?: boolean;
  VolumeLevel?: number;
  PlayMethod?: 'DirectPlay' | 'DirectStream' | 'Transcode';
  CanSeek?: boolean;
  PlaybackRate?: number;
  RepeatMode?: 'RepeatNone' | 'RepeatAll' | 'RepeatOne';
  PlaybackOrder?: 'Default' | 'Shuffle';
  EventName?: string;
  NowPlayingQueue?: { Id: string; PlaylistItemId: string }[];
}

export const reportStart = (r: Report) => post<void>('/Sessions/Playing', r);
export const reportProgress = (r: Report) => post<void>('/Sessions/Playing/Progress', r);

/* Stopped must reach the server even while the tab closes. */
export function reportStopped(r: Report) {
  try {
    return fetch(SERVER_URL + '/Sessions/Playing/Stopped', {
      method: 'POST', keepalive: true,
      headers: { Authorization: authHeader(), 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(r)
    }).catch(() => undefined);
  } catch {
    return Promise.resolve(undefined);
  }
}

/* ── Labels from the media streams ── */

const CODECS: Record<string, string> = {
  flac: 'FLAC', alac: 'ALAC', mp3: 'MP3', aac: 'AAC', opus: 'Opus', vorbis: 'Vorbis', wav: 'WAV', ac3: 'AC3', eac3: 'E-AC3',
  dsd_lsbf: 'DSD', dsd_msbf: 'DSD', ape: 'APE', wavpack: 'WavPack', wma: 'WMA', pcm_s16le: 'PCM', pcm_s24le: 'PCM'
};
const LOSSLESS = new Set(['flac', 'alac', 'wav', 'ape', 'wavpack', 'pcm_s16le', 'pcm_s24le', 'pcm_s32le', 'dsd_lsbf', 'dsd_msbf']);

export function audioStreamOf(item?: Pick<BaseItem, 'MediaStreams' | 'MediaSources'> | null): AudioStream | undefined {
  const streams = item?.MediaSources?.[0]?.MediaStreams ?? item?.MediaStreams ?? [];
  return streams.find((s) => s.Type === 'Audio') as AudioStream | undefined;
}

/* "FLAC · 24-bit 96 kHz", "MP3 · 320 kbps"; [sep] joins the codec and the rest (the Now playing badge uses a space). */
export function qualityLabel(s?: AudioStream, sep = ' · '): string {
  if (!s?.Codec) return '';
  const codec = CODECS[s.Codec.toLowerCase()] ?? s.Codec.toUpperCase();
  const khz = s.SampleRate ? `${Math.round(s.SampleRate / 100) / 10} kHz`.replace('.0 kHz', ' kHz') : '';
  if (LOSSLESS.has(s.Codec.toLowerCase())) {
    const rest = [s.BitDepth ? `${s.BitDepth}-bit` : '', khz].filter(Boolean).join(' ');
    return rest ? codec + sep + rest : codec;
  }
  const kbps = s.BitRate ? `${Math.round(s.BitRate / 1000)} kbps` : '';
  return kbps ? codec + sep + kbps : codec;
}
