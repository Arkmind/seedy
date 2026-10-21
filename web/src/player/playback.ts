import Hls from 'hls.js';
import { SERVER_URL, authHeader, deviceId, getSession } from '../api/client';
import {
  getPlaybackInfo, pingPlayback, reportPlaybackProgress, reportPlaybackStart, type PlaybackInfoRequest, type PlaybackReport
} from '../api/jellyfin';
import type { BaseItem, MediaSource, MediaStream } from '../api/types';
import { secondsToTicks, ticksToSeconds } from '../lib/format';
import { t } from '../lib/i18n';
import { PROFILE_DEFAULTS, browserSupport, buildDeviceProfile, type ProfileOptions } from './device-profile';
import { liveDeviceProfile, trackClose } from './live-profile';
import { MediaBridge } from './media';
import type { SoftMedia } from './soft-media';
import {
  softAvailable, softDecodes, softDeviceProfile, softExt, softFits, softFixes, type SoftMode
} from './soft-profile';

/* PlaybackInfo → stream URL → <video>/<audio> (+ hls.js), and the Sessions/Playing reports around it. */

export type PlayMethod = 'DirectPlay' | 'DirectStream' | 'Transcode';

export const QUALITY_OPTIONS = [
  { id: 'auto', label: 'Auto', bitrate: 120_000_000 },
  { id: '4k', label: '4K · 80 Mbps', bitrate: 80_000_000 },
  { id: '1080p', label: '1080p · 20 Mbps', bitrate: 20_000_000 },
  { id: '720p', label: '720p · 8 Mbps', bitrate: 8_000_000 },
  { id: '480p', label: '480p · 3 Mbps', bitrate: 3_000_000 }
] as const;
export type QualityId = (typeof QUALITY_OPTIONS)[number]['id'];
export const qualityBitrate = (id: QualityId) => QUALITY_OPTIONS.find((q) => q.id === id)?.bitrate ?? QUALITY_OPTIONS[0].bitrate;

export const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 1.75, 2];

export interface StreamOptions {
  mediaSourceId?: string;
  audioIndex?: number;
  /* -1 = off. */
  subtitleIndex: number;
  maxBitrate: number;
  startSeconds: number;
  forceTranscode?: boolean;
  /* This device's settings that shape the device profile. */
  profile?: ProfileOptions;
  /* Have the server burn the chosen subtitle into the picture (styled subtitles under "Styled and picture"). */
  burnSubtitle?: boolean;
  /* "Always burn in while converting". */
  burnWhenConverting?: boolean;
  /* When the experimental player may decode here instead of the server converting. */
  soft?: SoftMode;
}

export interface ActiveStream {
  playSessionId: string;
  source: MediaSource;
  method: PlayMethod;
  /* What the status pill says: a remux through HLS is still "Direct stream". */
  kind: 'Direct play' | 'Direct stream' | 'Transcoding';
  url: string;
  isHls: boolean;
  /* Progressive transcodes start their timeline at the requested position. */
  timeOffset: number;
  audioIndex?: number;
  subtitleIndex: number;
  burnIn: boolean;
  maxBitrate: number;
  /* The <video> element, or the experimental player (soft-media.ts). */
  engine: 'native' | 'soft';
  /* The container as the experimental player names it. */
  ext?: string;
}

export class PlaybackError extends Error {
  constructor(public code: string, message?: string) {
    super(message ?? code);
  }
}

export function errorCopy(code: string): { title: string; text: string } {
  switch (code) {
    case 'NotAllowed':
      return { title: t('Not allowed on this profile'), text: t("Your profile isn't allowed to play this title. Ask the server's admin if that's unexpected.") };
    case 'NoCompatibleStream':
      return { title: t("Can't play this here"), text: t("This browser can't play the file and the server couldn't convert it.") };
    case 'RateLimitExceeded':
      return { title: t('Server is busy'), text: t('Too many streams are playing on this server right now. Try again in a moment.') };
    case 'network':
      return { title: t("Can't reach the server"), text: t('The stream stopped loading. Check your connection and try again.') };
    case 'media':
      return { title: t('Playback stopped'), text: t("The video couldn't be decoded in this browser.") };
    default:
      return { title: t("Couldn't start playback"), text: code };
  }
}

/* Jellyfin writes transcoding paths in lower case; the dev proxy (and nothing else) cares. */
const fixPath = (p: string) => p.replace(/^\/videos\//, '/Videos/').replace(/^\/audio\//, '/Audio/');

export const token = () => getSession()?.token ?? '';

export function withApiKey(path: string): string {
  const u = fixPath(path);
  if (/[?&](ApiKey|api_key)=/i.test(u)) return SERVER_URL + u;
  return SERVER_URL + u + (u.includes('?') ? '&' : '?') + 'ApiKey=' + encodeURIComponent(token());
}

export const audioStreams = (s?: MediaSource) => (s?.MediaStreams ?? []).filter((m) => m.Type === 'Audio');
export const subtitleStreams = (s?: MediaSource) => (s?.MediaStreams ?? []).filter((m) => m.Type === 'Subtitle');
export const videoStream = (s?: MediaSource) => (s?.MediaStreams ?? []).find((m) => m.Type === 'Video');

/* The track a browser plays from a file on its own. */
export function fileDefaultAudio(s?: MediaSource): number | undefined {
  const list = audioStreams(s);
  return (list.find((a) => a.IsDefault) ?? list[0])?.Index;
}

const VIDEO_REASONS = /Video|ContainerBitrateExceedsLimit|SubtitleCodecNotSupported|RefFrames|Anamorphic|Interlaced|Unknown/;

function transcodeKind(url: string): ActiveStream['kind'] {
  const reasons = new URL(url, 'http://x').searchParams.get('TranscodeReasons') ?? '';
  if (!reasons) return 'Transcoding';
  return VIDEO_REASONS.test(reasons) ? 'Transcoding' : 'Direct stream';
}

export async function requestStream(item: BaseItem, opts: StreamOptions): Promise<ActiveStream> {
  /* A live TV channel: the server picks its source and opens the live stream; it always goes through the server. */
  const live = item.Type === 'TvChannel';
  const msId = live ? undefined : opts.mediaSourceId ?? item.MediaSources?.[0]?.Id ?? item.Id;
  const known = item.MediaSources?.find((s) => s.Id === msId);
  const secondaryAudio = opts.audioIndex !== undefined && known !== undefined && opts.audioIndex !== fileDefaultAudio(known);
  /* Burning a subtitle in means converting, so it rules out playing the file as-is. */
  const forceBurn = !!opts.burnSubtitle && opts.subtitleIndex >= 0;
  const body: PlaybackInfoRequest & { MaxAudioChannels?: number } = {
    DeviceProfile: live ? liveDeviceProfile(opts.maxBitrate, opts.profile) : buildDeviceProfile(opts.maxBitrate, opts.profile),
    MaxStreamingBitrate: opts.maxBitrate,
    StartTimeTicks: secondsToTicks(opts.startSeconds),
    AudioStreamIndex: opts.audioIndex,
    SubtitleStreamIndex: opts.subtitleIndex,
    MediaSourceId: msId,
    EnableDirectPlay: !live && !opts.forceTranscode && !secondaryAudio && !forceBurn,
    EnableDirectStream: true,
    EnableTranscoding: true,
    AllowVideoStreamCopy: true,
    AllowAudioStreamCopy: true,
    AutoOpenLiveStream: true,
    AlwaysBurnInSubtitleWhenTranscoding: forceBurn || !!opts.burnWhenConverting
  };
  if (opts.profile?.maxAudioChannels) body.MaxAudioChannels = opts.profile.maxAudioChannels;
  const res = await getPlaybackInfo(item.Id, body);
  if (res.ErrorCode) throw new PlaybackError(res.ErrorCode);
  const source = (live ? undefined : res.MediaSources?.find((s) => s.Id === msId)) ?? res.MediaSources?.[0];
  if (!source) throw new PlaybackError('NoCompatibleStream');

  const isAudio = item.MediaType === 'Audio';
  const sub = (source.MediaStreams ?? []).find((m) => m.Type === 'Subtitle' && m.Index === opts.subtitleIndex);
  const base = {
    playSessionId: res.PlaySessionId,
    source,
    audioIndex: opts.audioIndex ?? source.DefaultAudioStreamIndex,
    subtitleIndex: opts.subtitleIndex,
    maxBitrate: opts.maxBitrate,
    burnIn: false,
    engine: 'native' as const
  };

  let native: ActiveStream | null = null;
  if (source.SupportsDirectPlay && !live && !opts.forceTranscode && !secondaryAudio && !forceBurn) {
    native = { ...base, method: 'DirectPlay', kind: 'Direct play', url: staticUrl(item, source, res.PlaySessionId), isHls: false, timeOffset: 0 };
  } else if (source.TranscodingUrl) {
    const url = withApiKey(source.TranscodingUrl);
    const isHls = source.TranscodingSubProtocol === 'hls' || /\.m3u8/i.test(url);
    const startTicks = Number(new URL(url).searchParams.get('StartTimeTicks') ?? 0);
    const kind = transcodeKind(url);
    native = {
      ...base,
      method: kind === 'Direct stream' ? 'DirectStream' : 'Transcode',
      kind,
      url,
      isHls,
      timeOffset: isHls ? 0 : ticksToSeconds(startTicks),
      /* The server only puts the subtitle in the conversion URL when it draws it into the picture. */
      burnIn: !!sub && (sub.DeliveryMethod === 'Encode' || Number(new URL(url).searchParams.get('SubtitleStreamIndex') ?? -1) === sub.Index)
    };
  }

  const mode = !live && !isAudio && opts.soft && opts.soft !== 'off' && softAvailable() ? opts.soft : 'off';
  if (mode !== 'off') {
    const soft = await softStream(item, opts, body, source, native, mode).catch((e) => {
      console.warn('[seedy] experimental player: no stream', e);
      return null;
    });
    if (soft) return soft;
  }
  if (native) return native;
  throw new PlaybackError('NoCompatibleStream');
}

/* The file itself, as the server sends it for direct play. */
function staticUrl(item: BaseItem, source: MediaSource, playSessionId: string): string {
  const isAudio = item.MediaType === 'Audio';
  const container = (source.Container ?? (isAudio ? 'mp3' : 'mp4')).split(',')[0];
  const q = new URLSearchParams({
    static: 'true', mediaSourceId: source.Id, deviceId: deviceId(), PlaySessionId: playSessionId, ApiKey: token()
  });
  if (source.ETag) q.set('Tag', source.ETag);
  return `${SERVER_URL}/${isAudio ? 'Audio' : 'Videos'}/${item.Id}/stream.${container}?${q.toString()}`;
}

/* The experimental player's stream, when it spares the server converting the video: asked for with a profile that
   takes what libmedia decodes. The file as it is, or the server copying the video with converted audio (TrueHD). */
async function softStream(
  item: BaseItem, opts: StreamOptions, body: PlaybackInfoRequest, known: MediaSource, native: ActiveStream | null, mode: SoftMode
): Promise<ActiveStream | null> {
  const v = videoStream(known);
  if (!softDecodes(v) || !softFits(v, mode)) return null;
  if (mode !== 'always' && native && !(native.method === 'Transcode' && softFixes(native.url))) return null;
  const forceBurn = !!opts.burnSubtitle && opts.subtitleIndex >= 0;
  const res = await getPlaybackInfo(item.Id, {
    ...body,
    DeviceProfile: softDeviceProfile(opts.maxBitrate, opts.profile ?? PROFILE_DEFAULTS),
    // Libmedia picks the audio track itself, so any track plays as it is.
    EnableDirectPlay: !opts.forceTranscode && !forceBurn
  });
  if (res.ErrorCode) return null;
  const source = res.MediaSources?.find((s) => s.Id === known.Id) ?? res.MediaSources?.[0];
  if (!source) return null;
  const audioIndex = opts.audioIndex ?? source.DefaultAudioStreamIndex;
  const base = {
    playSessionId: res.PlaySessionId, source, audioIndex, subtitleIndex: opts.subtitleIndex, maxBitrate: opts.maxBitrate, burnIn: false, engine: 'soft' as const
  };
  const audio = audioStreams(source).find((a) => a.Index === audioIndex);
  const ext = softExt(source.Container);
  if (source.SupportsDirectPlay && ext && !audio?.IsExternal) {
    return { ...base, method: 'DirectPlay', kind: 'Direct play', url: staticUrl(item, source, res.PlaySessionId), isHls: false, timeOffset: 0, ext };
  }
  if (source.TranscodingUrl) {
    const url = withApiKey(source.TranscodingUrl);
    const isHls = source.TranscodingSubProtocol === 'hls' || /\.m3u8/i.test(url);
    if (isHls && transcodeKind(url) === 'Direct stream') {
      return { ...base, method: 'DirectStream', kind: 'Direct stream', url, isHls: true, timeOffset: 0, ext: 'm3u8' };
    }
  }
  return null;
}

/* How Seedy gets a subtitle it draws itself: WebVTT text, styled ASS/SSA, or PGS pictures. */
export type SubtitleFormat = 'vtt' | 'ass' | 'pgs';

export function subtitleFormat(s: MediaStream): SubtitleFormat {
  const fromUrl = /\/Stream\.([a-z0-9]+)(\?|$)/i.exec(s.DeliveryUrl ?? '')?.[1]?.toLowerCase();
  const f = fromUrl ?? (s.Codec ?? '').toLowerCase();
  if (f === 'ass' || f === 'ssa') return 'ass';
  if (f === 'pgssub' || f === 'pgs' || f === 'sup') return 'pgs';
  return 'vtt';
}

/* Address of a subtitle stream in the format the server delivers it in (WebVTT unless ASS or PGS). */
export function subtitleUrl(item: BaseItem, source: MediaSource, s: MediaStream, format: SubtitleFormat = subtitleFormat(s)): string {
  const ext = format === 'ass' ? ((s.Codec ?? '').toLowerCase() === 'ssa' ? 'ssa' : 'ass') : format === 'pgs' ? 'pgssub' : 'vtt';
  if (s.DeliveryUrl && !/^https?:/i.test(s.DeliveryUrl)) {
    return withApiKey(s.DeliveryUrl.replace(/\/Stream\.[a-z0-9]+(\?|$)/i, `/Stream.${ext}$1`));
  }
  if (s.DeliveryUrl) return s.DeliveryUrl;
  return withApiKey(`/Videos/${item.Id}/${source.Id}/Subtitles/${s.Index}/0/Stream.${ext}`);
}

const isSafari = () => /Safari\//.test(navigator.userAgent) && !/Chrome\/|Chromium\/|Edg\//.test(navigator.userAgent);

/* Sessions/Playing/Stopped and the encoder cleanup must survive the tab closing. */
function keepalive(method: 'POST' | 'DELETE', path: string, body?: unknown) {
  try {
    return fetch(SERVER_URL + path, {
      method,
      keepalive: true,
      headers: { Authorization: authHeader(), 'Content-Type': 'application/json', Accept: 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body)
    }).catch(() => undefined);
  } catch {
    return Promise.resolve(undefined);
  }
}

export interface SessionEvents {
  onStream(stream: ActiveStream): void;
  onFatal(code: string, stream: ActiveStream): void;
  /* Where the experimental player draws. */
  softHost?(): HTMLElement | null;
  /* The experimental player decodes on several threads only in a cross-origin isolated page: reload into one at this
     position. True when the page is reloading. */
  isolate?(seconds: number): boolean;
}

/* One playback of one item. Owns the stream on the media element (or the experimental player) and every report
   about it. */
export class PlaybackSession {
  stream: ActiveStream | null = null;
  opts: StreamOptions;
  /* The subtitle the viewer picked (text tracks are drawn by Seedy, so the stream may not know). */
  subtitleIndex = -1;
  started = false;
  stopped = false;
  /* A copy played alongside (the comparison in Player): no start, progress or stop reports. */
  silent = false;
  /* What plays: the <video> element, or the experimental player standing in for it. */
  readonly media: MediaBridge;
  private soft: SoftMedia | null = null;
  private hls: Hls | null = null;
  private seq = 0;
  private progressTimer = 0;
  private pingTimer = 0;
  private netRetries = 0;
  private mediaRecoveries = 0;
  /* The start-position seek waiting for the current stream's metadata. */
  private pendingSeek: (() => void) | null = null;
  private pendingAt = 0;

  constructor(public item: BaseItem, media: MediaBridge | HTMLVideoElement, private events: SessionEvents, opts: StreamOptions) {
    this.media = media instanceof MediaBridge ? media : new MediaBridge(media);
    this.opts = opts;
    this.subtitleIndex = opts.subtitleIndex;
    this.onPlaying = this.onPlaying.bind(this);
    this.onMediaError = this.onMediaError.bind(this);
    this.media.addEventListener('playing', this.onPlaying);
    this.el.addEventListener('error', this.onMediaError);
  }

  /* The page's <video> element. */
  private get el(): HTMLVideoElement {
    return this.media.video;
  }

  get runtime(): number {
    const t = this.stream?.source.RunTimeTicks ?? this.item.RunTimeTicks;
    if (t) return ticksToSeconds(t);
    return Number.isFinite(this.media.duration) ? this.media.duration : 0;
  }

  position(): number {
    // Until the start seek has happened, the element's 0 isn't where playback is.
    const t = this.pendingSeek ? this.pendingAt : this.media.currentTime || 0;
    return t + (this.stream?.timeOffset ?? 0);
  }

  async open(opts: Partial<StreamOptions> = {}): Promise<ActiveStream | null> {
    const my = ++this.seq;
    const next = { ...this.opts, ...opts };
    const stream = await requestStream(this.item, next);
    const drop = () => {
      if (stream.method !== 'DirectPlay') this.stopEncoding(stream.playSessionId);
      if (stream.source.LiveStreamId) trackClose(keepalive('POST', `/LiveStreams/Close?liveStreamId=${encodeURIComponent(stream.source.LiveStreamId)}`));
    };
    if (my !== this.seq || this.stopped) {
      drop();
      return null;
    }
    if (stream.engine === 'soft' && !window.crossOriginIsolated && this.events.isolate?.(next.startSeconds)) {
      drop();
      return null;
    }
    const old = this.stream;
    this.opts = next;
    this.stream = stream;
    if (stream.burnIn) this.subtitleIndex = stream.subtitleIndex;
    await this.attach(stream, next.startSeconds);
    if (my !== this.seq || this.stopped) return null;
    if (old && old.method !== 'DirectPlay' && old.playSessionId !== stream.playSessionId) this.stopEncoding(old.playSessionId);
    this.events.onStream(stream);
    this.schedule();
    return stream;
  }

  /* Restart the stream at the current position with new options (audio track, quality, burn-in). */
  async change(opts: Partial<StreamOptions>, event?: string): Promise<ActiveStream | null> {
    const wasPaused = this.media.paused;
    const startSeconds = opts.startSeconds ?? this.position();
    const stream = await this.open({ ...opts, startSeconds });
    if (!stream) return null;
    if (!wasPaused) this.play();
    if (this.started) this.report('progress', event);
    return stream;
  }

  private async attach(stream: ActiveStream, startSeconds: number) {
    this.destroyHls();
    this.clearPendingSeek();
    this.netRetries = 0;
    this.mediaRecoveries = 0;
    const startAt = Math.max(0, startSeconds - stream.timeOffset);
    if (stream.engine === 'soft') {
      await this.attachSoft(stream, startAt);
      return;
    }
    this.dropSoft();
    const el = this.el;
    if (stream.isHls && Hls.isSupported() && !(isSafari() && browserSupport().nativeHls)) {
      const hls = new Hls({
        startPosition: startAt,
        manifestLoadingTimeOut: 20000,
        maxBufferLength: 30,
        maxMaxBufferLength: 90,
        backBufferLength: 60,
        enableWorker: true
      });
      this.hls = hls;
      hls.on(Hls.Events.ERROR, (_e, data) => {
        if (!data.fatal || this.hls !== hls) return;
        if (data.type === Hls.ErrorTypes.NETWORK_ERROR && this.netRetries < 3) {
          this.netRetries++;
          window.setTimeout(() => this.hls === hls && hls.startLoad(), 1000 * this.netRetries);
          return;
        }
        if (data.type === Hls.ErrorTypes.MEDIA_ERROR && this.mediaRecoveries < 2) {
          this.mediaRecoveries++;
          hls.recoverMediaError();
          return;
        }
        this.events.onFatal(data.type === Hls.ErrorTypes.NETWORK_ERROR ? 'network' : 'media', stream);
      });
      hls.loadSource(stream.url);
      hls.attachMedia(el);
      return;
    }
    el.src = stream.url;
    if (startAt > 0) {
      // Only for this stream: a switch before the metadata arrives must not seek the next one to this position.
      const seek = () => {
        const at = this.pendingAt;
        this.clearPendingSeek();
        try { el.currentTime = at; } catch { /* not seekable yet */ }
      };
      this.pendingSeek = seek;
      this.pendingAt = startAt;
      el.addEventListener('loadedmetadata', seek);
    }
    el.load();
  }

  /* A new experimental player for this stream (a track or quality change gets a fresh one too). */
  private async attachSoft(stream: ActiveStream, startAt: number) {
    const el = this.el;
    el.pause();
    if (el.getAttribute('src')) {
      el.removeAttribute('src');
      el.load();
    }
    const host = this.events.softHost?.();
    const { SoftMedia } = await import('./soft-media');
    if (this.stopped || this.stream !== stream) return;
    if (!host) throw new PlaybackError('media');
    this.dropSoft();
    const soft = new SoftMedia(host);
    this.soft = soft;
    soft.addEventListener('error', () => {
      if (this.soft === soft && !this.stopped) this.events.onFatal(soft.error?.code === 'slow' ? 'soft-slow' : 'soft', stream);
    });
    this.media.use(soft);
    const v = videoStream(stream.source);
    const ownAudio = audioStreams(stream.source).filter((a) => !a.IsExternal).sort((a, b) => a.Index - b.Index);
    const track = ownAudio.findIndex((a) => a.Index === stream.audioIndex);
    void soft.open(stream.url, startAt, {
      // A direct-play file carries every track; the server's copy only the one asked for.
      audioTrack: stream.method === 'DirectPlay' && track >= 0 ? track : undefined,
      fps: v?.RealFrameRate || v?.AverageFrameRate,
      ext: stream.ext
    });
  }

  private dropSoft() {
    const soft = this.soft;
    if (!soft) return;
    this.soft = null;
    this.media.use(this.el);
    void soft.destroy();
  }

  private clearPendingSeek() {
    if (this.pendingSeek) this.el.removeEventListener('loadedmetadata', this.pendingSeek);
    this.pendingSeek = null;
  }

  play() {
    const p = this.media.play();
    if (p) p.catch(() => undefined);
  }

  seek(seconds: number) {
    const s = this.stream;
    const target = Math.max(0, Math.min(seconds, (this.runtime || seconds) - 0.25));
    if (s && !s.isHls && s.method !== 'DirectPlay') {
      void this.change({ startSeconds: target });
      return;
    }
    // Before the metadata is in, move where the start seek will land instead.
    if (this.pendingSeek) { this.pendingAt = Math.max(0, target - (s?.timeOffset ?? 0)); return; }
    try { this.media.currentTime = target; } catch { /* not seekable */ }
  }

  private onPlaying() {
    if (this.started || this.stopped || !this.stream || this.silent) return;
    this.started = true;
    reportPlaybackStart(this.body()).catch(() => undefined);
  }

  private onMediaError() {
    if (this.stopped || !this.stream || this.hls) return;
    if (!this.el.getAttribute('src')) return;
    this.events.onFatal('media', this.stream);
  }

  private body(event?: string): PlaybackReport {
    const s = this.stream!;
    const m = this.media;
    return {
      ItemId: this.item.Id,
      MediaSourceId: s.source.Id,
      PlaySessionId: s.playSessionId,
      PositionTicks: secondsToTicks(this.position()),
      IsPaused: m.paused,
      IsMuted: m.muted,
      VolumeLevel: Math.round(m.volume * 100),
      PlayMethod: s.method,
      AudioStreamIndex: s.audioIndex,
      SubtitleStreamIndex: this.subtitleIndex,
      CanSeek: true,
      PlaybackRate: m.playbackRate,
      EventName: event,
      ...(s.source.LiveStreamId ? { LiveStreamId: s.source.LiveStreamId, CanSeek: false } : {})
    };
  }

  report(kind: 'progress', event?: string) {
    if (!this.stream || this.stopped || !this.started || kind !== 'progress') return;
    reportPlaybackProgress(this.body(event)).catch(() => undefined);
  }

  private schedule() {
    window.clearInterval(this.progressTimer);
    window.clearInterval(this.pingTimer);
    this.progressTimer = window.setInterval(() => this.report('progress', 'timeupdate'), 10_000);
    if (this.stream && this.stream.method !== 'DirectPlay') {
      this.pingTimer = window.setInterval(() => {
        if (this.stream && !this.stopped) pingPlayback(this.stream.playSessionId).catch(() => undefined);
      }, 30_000);
    }
  }

  private stopEncoding(playSessionId: string) {
    void keepalive('DELETE', `/Videos/ActiveEncodings?deviceId=${encodeURIComponent(deviceId())}&playSessionId=${encodeURIComponent(playSessionId)}`);
  }

  private destroyHls() {
    if (this.hls) {
      try { this.hls.destroy(); } catch { /* already gone */ }
      this.hls = null;
    }
  }

  /* Report the stop, end any transcode and release the element. Safe to call more than once. */
  stop(opts: { ended?: boolean; failed?: boolean } = {}) {
    if (this.stopped) return;
    const s = this.stream;
    const body = s ? this.body() : null;
    this.stopped = true;
    this.seq++;
    window.clearInterval(this.progressTimer);
    window.clearInterval(this.pingTimer);
    this.media.removeEventListener('playing', this.onPlaying);
    this.el.removeEventListener('error', this.onMediaError);
    this.clearPendingSeek();
    if (s && body) {
      if (opts.ended && this.runtime) body.PositionTicks = secondsToTicks(this.runtime);
      if (opts.failed) body.Failed = true;
      const reported = this.started ? keepalive('POST', '/Sessions/Playing/Stopped', body) : undefined;
      if (s.method !== 'DirectPlay') this.stopEncoding(s.playSessionId);
      /* A live stream closes with the stop report once playback has started, else on its own. */
      if (s.source.LiveStreamId) trackClose(reported ?? keepalive('POST', `/LiveStreams/Close?liveStreamId=${encodeURIComponent(s.source.LiveStreamId)}`));
    }
    this.destroyHls();
    // The experimental player stays on its last frame (the end-of-movie tile shows it) until released.
    this.soft?.pause();
  }

  /* Detach from the element once nothing else will use it. */
  release() {
    this.stop();
    this.dropSoft();
    try {
      this.el.pause();
      this.el.removeAttribute('src');
      this.el.load();
    } catch { /* element already gone */ }
  }
}
