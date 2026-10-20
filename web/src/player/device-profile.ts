/* A compact browser device profile, modelled on jellyfin-web's browserDeviceProfile.
   It tells the server what this browser can play as-is, and what to transcode to when it can't. */

export interface ProfileCondition { Condition: string; Property: string; Value: string; IsRequired: boolean }

export interface DeviceProfile {
  MaxStreamingBitrate: number;
  MaxStaticBitrate: number;
  MusicStreamingTranscodingBitrate: number;
  DirectPlayProfiles: { Container: string; Type: 'Video' | 'Audio'; VideoCodec?: string; AudioCodec?: string }[];
  TranscodingProfiles: Record<string, unknown>[];
  ContainerProfiles: unknown[];
  CodecProfiles: { Type: string; Codec?: string; Conditions: ProfileCondition[] }[];
  SubtitleProfiles: { Format: string; Method: 'External' | 'Encode' | 'Embed' | 'Hls' }[];
  ResponseProfiles: { Type: string; Container: string; MimeType: string }[];
}

export interface BrowserSupport {
  h264: boolean; hevc: boolean; av1: boolean; vp9: boolean; vp8: boolean;
  aac: boolean; mp3: boolean; ac3: boolean; eac3: boolean; opus: boolean; flac: boolean; vorbis: boolean;
  mkv: boolean; hls: boolean; nativeHls: boolean; hdr: boolean;
}

let cached: BrowserSupport | null = null;

export function browserSupport(): BrowserSupport {
  if (cached) return cached;
  const v = document.createElement('video');
  const ms = (window as unknown as { MediaSource?: { isTypeSupported(t: string): boolean } }).MediaSource
    ?? (window as unknown as { ManagedMediaSource?: { isTypeSupported(t: string): boolean } }).ManagedMediaSource;
  const can = (type: string) => {
    try {
      if (v.canPlayType(type).replace(/no/, '')) return true;
      return !!ms?.isTypeSupported(type);
    } catch {
      return false;
    }
  };
  const ua = navigator.userAgent;
  const chromium = /Chrome\/|Chromium\/|CriOS\//.test(ua) || /Edg\//.test(ua);
  const nativeHls = !!v.canPlayType('application/vnd.apple.mpegurl').replace(/no/, '');
  let hdr = false;
  try { hdr = window.matchMedia('(dynamic-range: high)').matches || window.matchMedia('(video-dynamic-range: high)').matches; } catch { /* old browsers */ }
  cached = {
    h264: can('video/mp4; codecs="avc1.42E01E, mp4a.40.2"') || can('video/mp4; codecs="avc1.640029"'),
    hevc: can('video/mp4; codecs="hvc1.1.6.L120.90"') || can('video/mp4; codecs="hev1.1.6.L120.90"') || can('video/mp4; codecs="hvc1.2.4.L153.B0"'),
    av1: can('video/mp4; codecs="av01.0.15M.10"') || can('video/webm; codecs="av01.0.15M.10"'),
    vp9: can('video/webm; codecs="vp9"') || can('video/mp4; codecs="vp09.00.10.08"'),
    vp8: can('video/webm; codecs="vp8"'),
    aac: can('audio/mp4; codecs="mp4a.40.2"') || can('video/mp4; codecs="avc1.42E01E, mp4a.40.2"'),
    mp3: can('audio/mpeg') || can('audio/mp4; codecs="mp4a.69"'),
    ac3: can('audio/mp4; codecs="ac-3"'),
    eac3: can('audio/mp4; codecs="ec-3"'),
    opus: can('audio/ogg; codecs="opus"') || can('audio/webm; codecs="opus"') || can('audio/mp4; codecs="opus"'),
    flac: can('audio/flac') || can('audio/mp4; codecs="flac"'),
    vorbis: can('audio/ogg; codecs="vorbis"') || can('audio/webm; codecs="vorbis"'),
    /* Chromium plays Matroska with the codecs it knows; it just won't say so through canPlayType. */
    mkv: chromium || can('video/x-matroska') || can('video/mkv'),
    hls: !!ms?.isTypeSupported('video/mp4; codecs="avc1.42E01E,mp4a.40.2"') || nativeHls,
    nativeHls,
    hdr
  };
  return cached;
}

const join = (list: (string | false)[]) => list.filter(Boolean).join(',');

/* What this device's settings (Settings · Playback and Subtitles) change in the profile. */
export interface ProfileOptions {
  /* 0 = what the browser can do. */
  maxAudioChannels: number;
  cbrAudio: boolean;
  /* Largest picture when converting; with `limitResolution` also the largest played as-is. */
  maxWidth: number | null;
  limitResolution: boolean;
  preferFmp4: boolean;
  hevc: boolean;
  av1: boolean;
  hdr: boolean;
  dts: boolean;
  truehd: boolean;
  mkv: boolean;
  /* Which subtitles the server burns into the picture, and which Seedy draws itself. */
  burnIn: 'auto' | 'picture' | 'styled' | 'all';
  /* Seedy draws PGS itself. */
  pictureSubtitles: boolean;
  /* ASS/SSA arrive as they are (Seedy draws them with their own styling) instead of as WebVTT. */
  styledSubtitles: boolean;
}

export const PROFILE_DEFAULTS: ProfileOptions = {
  maxAudioChannels: 0, cbrAudio: false, maxWidth: null, limitResolution: false, preferFmp4: false,
  hevc: true, av1: true, hdr: true, dts: false, truehd: false, mkv: true,
  burnIn: 'auto', pictureSubtitles: true, styledSubtitles: true
};

export function buildDeviceProfile(maxBitrate: number, o: ProfileOptions = PROFILE_DEFAULTS): DeviceProfile {
  const b = browserSupport();
  const s = { ...b, hevc: b.hevc && o.hevc, av1: b.av1 && o.av1, mkv: b.mkv && o.mkv, hdr: b.hdr && o.hdr };
  /* DTS and TrueHD only when this device (or a receiver behind it) says it can take them. */
  const passthrough = [o.dts && 'dts', o.dts && 'dca', o.truehd && 'truehd'] as (string | false)[];
  const videoCodecs = join([s.h264 && 'h264', s.hevc && 'hevc', s.av1 && 'av1', s.vp9 && 'vp9']);
  const videoAudio = join([s.aac && 'aac', s.mp3 && 'mp3', s.ac3 && 'ac3', s.eac3 && 'eac3', s.opus && 'opus', s.flac && 'flac', ...passthrough]);
  const ranges = s.hdr ? 'SDR|HDR10|HDR10Plus|HLG' : 'SDR';

  const direct: DeviceProfile['DirectPlayProfiles'] = [
    { Container: 'mp4,m4v', Type: 'Video', VideoCodec: videoCodecs, AudioCodec: videoAudio },
    { Container: 'webm', Type: 'Video', VideoCodec: join([s.vp8 && 'vp8', s.vp9 && 'vp9', s.av1 && 'av1']), AudioCodec: join([s.vorbis && 'vorbis', s.opus && 'opus']) }
  ];
  if (s.mkv) direct.push({ Container: 'mkv', Type: 'Video', VideoCodec: videoCodecs, AudioCodec: videoAudio });
  if (s.mp3) direct.push({ Container: 'mp3', Type: 'Audio', AudioCodec: 'mp3' });
  if (s.aac) {
    direct.push({ Container: 'aac', Type: 'Audio', AudioCodec: 'aac' });
    direct.push({ Container: 'm4a,m4b,mp4', Type: 'Audio', AudioCodec: join(['aac', s.flac && 'flac', s.opus && 'opus']) });
  }
  if (s.flac) direct.push({ Container: 'flac', Type: 'Audio', AudioCodec: 'flac' });
  if (s.opus || s.vorbis) {
    direct.push({ Container: 'webm', Type: 'Audio', AudioCodec: join([s.opus && 'opus', s.vorbis && 'vorbis']) });
    direct.push({ Container: 'ogg,oga,opus', Type: 'Audio', AudioCodec: join([s.opus && 'opus', s.vorbis && 'vorbis']) });
  }
  direct.push({ Container: 'wav', Type: 'Audio', AudioCodec: 'pcm_s16le,pcm_s24le' });

  /* HEVC and AV1 only travel in fMP4 segments; plain H.264 keeps MPEG-TS unless fMP4 is preferred. */
  const fmp4 = o.preferFmp4 || s.hevc || s.av1;
  const hlsVideo = join(['h264', fmp4 && s.hevc && 'hevc', fmp4 && s.av1 && 'av1']);
  const hlsAudio = join(['aac', s.mp3 && 'mp3', fmp4 && s.ac3 && 'ac3', fmp4 && s.eac3 && 'eac3', fmp4 && s.opus && 'opus', fmp4 && s.flac && 'flac']);

  const channels = o.maxAudioChannels > 0 ? o.maxAudioChannels : 6;
  const musicChannels = String(Math.min(2, channels));
  const widthLimit: ProfileCondition[] = o.maxWidth ? [{ Condition: 'LessThanEqual', Property: 'Width', Value: String(o.maxWidth), IsRequired: false }] : [];
  const transcoding: Record<string, unknown>[] = [
    {
      Container: fmp4 ? 'mp4' : 'ts', Type: 'Video', VideoCodec: hlsVideo, AudioCodec: hlsAudio, Context: 'Streaming', Protocol: 'hls',
      MaxAudioChannels: String(channels), MinSegments: '1', BreakOnNonKeyFrames: true, EnableAudioVbrEncoding: !o.cbrAudio,
      Conditions: widthLimit
    },
    { Container: 'mp3', Type: 'Audio', AudioCodec: 'mp3', Context: 'Streaming', Protocol: 'http', MaxAudioChannels: musicChannels, EnableAudioVbrEncoding: !o.cbrAudio },
    { Container: 'aac', Type: 'Audio', AudioCodec: 'aac', Context: 'Static', Protocol: 'http', MaxAudioChannels: musicChannels, EnableAudioVbrEncoding: !o.cbrAudio }
  ];

  const notAnamorphic: ProfileCondition = { Condition: 'NotEquals', Property: 'IsAnamorphic', Value: 'true', IsRequired: false };
  const notInterlaced: ProfileCondition = { Condition: 'NotEquals', Property: 'IsInterlaced', Value: 'true', IsRequired: false };
  const codecs: DeviceProfile['CodecProfiles'] = [
    {
      Type: 'Video', Codec: 'h264', Conditions: [
        notAnamorphic, notInterlaced,
        { Condition: 'EqualsAny', Property: 'VideoProfile', Value: 'high|main|baseline|constrained baseline', IsRequired: false },
        { Condition: 'EqualsAny', Property: 'VideoRangeType', Value: 'SDR', IsRequired: false },
        { Condition: 'LessThanEqual', Property: 'VideoLevel', Value: '52', IsRequired: false }
      ]
    },
    /* Browsers can't switch audio tracks in a file: picking another track makes the server remux. */
    {
      Type: 'VideoAudio', Conditions: [
        { Condition: 'Equals', Property: 'IsSecondaryAudio', Value: 'false', IsRequired: false },
        ...(o.maxAudioChannels > 0 ? [{ Condition: 'LessThanEqual', Property: 'AudioChannels', Value: String(o.maxAudioChannels), IsRequired: false }] : [])
      ]
    }
  ];
  /* "Treat that as this screen's limit": never play a bigger picture as-is either. */
  if (o.limitResolution && o.maxWidth) codecs.push({ Type: 'Video', Conditions: widthLimit });
  if (o.maxAudioChannels > 0) codecs.push({ Type: 'Audio', Conditions: [{ Condition: 'LessThanEqual', Property: 'AudioChannels', Value: String(o.maxAudioChannels), IsRequired: false }] });
  if (s.hevc) codecs.push({
    Type: 'Video', Codec: 'hevc', Conditions: [
      notAnamorphic, notInterlaced,
      { Condition: 'EqualsAny', Property: 'VideoProfile', Value: 'main|main 10', IsRequired: false },
      { Condition: 'EqualsAny', Property: 'VideoRangeType', Value: ranges, IsRequired: false },
      { Condition: 'LessThanEqual', Property: 'VideoLevel', Value: '183', IsRequired: false }
    ]
  });
  if (s.av1) codecs.push({
    Type: 'Video', Codec: 'av1', Conditions: [
      notAnamorphic, notInterlaced,
      { Condition: 'EqualsAny', Property: 'VideoProfile', Value: 'main', IsRequired: false },
      { Condition: 'EqualsAny', Property: 'VideoRangeType', Value: ranges, IsRequired: false },
      { Condition: 'LessThanEqual', Property: 'VideoLevel', Value: '19', IsRequired: false }
    ]
  });
  if (s.vp9) codecs.push({
    Type: 'Video', Codec: 'vp9', Conditions: [{ Condition: 'EqualsAny', Property: 'VideoRangeType', Value: ranges, IsRequired: false }]
  });

  /* Text subtitles come over as WebVTT and are drawn by Seedy (styled ASS/SSA as they are), PGS too when Seedy
     draws picture subtitles; whatever the burn-in setting claims, and VobSub/DVB, is burnt in by the server. */
  const subtitles: DeviceProfile['SubtitleProfiles'] = [];
  if (o.burnIn !== 'all') {
    if (o.styledSubtitles && o.burnIn !== 'styled') subtitles.push({ Format: 'ass', Method: 'External' }, { Format: 'ssa', Method: 'External' });
    if (o.pictureSubtitles && o.burnIn === 'auto') subtitles.push({ Format: 'pgssub', Method: 'External' });
    subtitles.push({ Format: 'vtt', Method: 'External' }, { Format: 'webvtt', Method: 'External' });
  }
  /* (The server burns in anything no External profile takes; styled ASS/SSA and "always burn in while converting"
     are asked for per stream, see playback.ts.) */
  subtitles.push(...['pgs', 'pgssub', 'dvdsub', 'dvbsub', 'vobsub', 'sub', 'idx']
    .filter((Format) => !subtitles.some((p) => p.Format === Format))
    .map((Format) => ({ Format, Method: 'Encode' as const })));

  return {
    MaxStreamingBitrate: maxBitrate,
    MaxStaticBitrate: 100_000_000,
    MusicStreamingTranscodingBitrate: 384_000,
    DirectPlayProfiles: direct,
    TranscodingProfiles: transcoding,
    ContainerProfiles: [],
    CodecProfiles: codecs,
    SubtitleProfiles: subtitles,
    ResponseProfiles: [{ Type: 'Video', Container: 'm4v', MimeType: 'video/mp4' }]
  };
}

/* Text subtitle formats that Seedy can render (as WebVTT); everything else is a picture format. */
export const TEXT_SUB_CODECS = new Set(['srt', 'subrip', 'vtt', 'webvtt', 'ass', 'ssa', 'mov_text', 'tx3g', 'text', 'smi', 'sami', 'ttml', 'microdvd', 'mpl2', 'subviewer', 'jacosub']);
