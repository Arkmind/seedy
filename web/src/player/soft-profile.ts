import type { MediaStream } from '../api/types';
import { browserSupport, buildDeviceProfile, type DeviceProfile, type ProfileOptions } from './device-profile';

/* The experimental player (Settings · Playback · Advanced): libmedia decodes in this browser what the browser can't,
   so the server sends the file as it is instead of converting its video. Kept free of libmedia, which only loads when
   a stream actually uses it (soft-media.ts). */

/* Off; when the server would convert the video, up to 1080p or at any size; or every video (for trying it out). */
export type SoftMode = 'off' | 'needed' | 'any' | 'always';
export const SOFT_MODES: SoftMode[] = ['off', 'needed', 'any', 'always'];
export const softMode = (v: unknown): SoftMode => (SOFT_MODES.includes(v as SoftMode) ? (v as SoftMode) : 'off');

let available: boolean | null = null;

/* WebAssembly with SIMD (libmedia's decoders are SIMD builds), workers and an AudioWorklet. */
export function softAvailable(): boolean {
  if (available !== null) return available;
  try {
    // The smallest module using a v128 instruction.
    const simd = new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 96, 0, 1, 123, 3, 2, 1, 0, 10, 10, 1, 8, 0, 65, 0, 253, 15, 253, 98, 11]);
    available = typeof WebAssembly === 'object' && WebAssembly.validate(simd) && typeof Worker === 'function'
      && typeof AudioWorkletNode === 'function' && typeof BigInt === 'function';
  } catch {
    available = false;
  }
  return available;
}

/* Video libmedia decodes itself, plus what it hands to the browser's own decoders (WebCodecs). */
function softVideoCodecs(): string[] {
  const b = browserSupport();
  return ['hevc', 'h264', 'mpeg2video', 'mpeg4', ...(b.av1 ? ['av1'] : []), ...(b.vp9 ? ['vp9'] : []), ...(b.vp8 ? ['vp8'] : [])];
}

const SOFT_AUDIO = ['aac', 'mp3', 'ac3', 'eac3', 'dts', 'dca', 'flac', 'opus', 'vorbis', 'pcm_s16le', 'pcm_s24le', 'pcm_s32le', 'pcm_f32le'];
/* What the server converts audio to when the file's own can't be decoded here (TrueHD): video is still copied. */
const CONVERTED_AUDIO = 'aac,ac3,eac3,flac,opus,mp3';

export const softDecodes = (v?: MediaStream) => !!v?.Codec && softVideoCodecs().includes(v.Codec.toLowerCase());

/* On one thread (the page asked to be isolated and wasn't), 4K brings the tab down: then it's 1080p at most. */
const oneThread = () => !window.crossOriginIsolated && new URLSearchParams(window.location.search).get('isolate') === '1';

/* "Up to 1080p" takes 2K and odd 1080p sizes, not 1440p. */
export const softFits = (v: MediaStream | undefined, mode: SoftMode) =>
  (mode !== 'needed' && !oneThread()) || (v?.Width ?? 0) * (v?.Height ?? 0) <= 2048 * 1152;

/* Why the server would convert, when the experimental player would make that unnecessary: only reasons about what
   this browser decodes or how the file is packed, with at least one about the video. A bitrate limit, a subtitle that
   must be burnt in or an interlaced picture still need the server. */
const FIXABLE = new Set([
  'VideoCodecNotSupported', 'VideoProfileNotSupported', 'VideoLevelNotSupported', 'VideoBitDepthNotSupported',
  'VideoRangeTypeNotSupported', 'VideoCodecTagNotSupported', 'RefFramesNotSupported', 'AnamorphicVideoNotSupported',
  'VideoFramerateNotSupported', 'ContainerNotSupported', 'AudioCodecNotSupported', 'AudioChannelsNotSupported',
  'AudioProfileNotSupported', 'AudioSampleRateNotSupported', 'AudioBitDepthNotSupported', 'SecondaryAudioNotSupported',
  'DirectPlayError'
]);

export function softFixes(transcodingUrl: string): boolean {
  const reasons = (new URL(transcodingUrl, 'http://x').searchParams.get('TranscodeReasons') ?? '').split(',').filter(Boolean);
  return reasons.length > 0 && reasons.every((r) => FIXABLE.has(r)) && reasons.some((r) => /^Video|^RefFrames|^Anamorphic/.test(r));
}

/* Libmedia reads these containers itself; the address's extension tells it which. */
const CONTAINERS: Record<string, string> = { mkv: 'mkv', webm: 'mkv', matroska: 'mkv', mp4: 'mp4', m4v: 'mp4', mov: 'mp4', ts: 'ts', mpegts: 'ts', avi: 'avi' };
export const softExt = (container?: string): string | undefined =>
  (container ?? '').split(',').map((c) => CONTAINERS[c.trim().toLowerCase()]).find(Boolean);

/* Plays any container, video and audio libmedia handles as they are, and any audio track (it picks the track
   itself). Subtitles, channel limits and picture limits are the usual profile's. */
export function softDeviceProfile(maxBitrate: number, o: ProfileOptions): DeviceProfile {
  const base = buildDeviceProfile(maxBitrate, o);
  const video = softVideoCodecs().join(',');
  const hdr = browserSupport().hdr && o.hdr;
  /* Dolby Vision with an HDR10/HLG/SDR base layer plays as that layer; profile 5 has none, so the server converts it. */
  const ranges = hdr
    ? 'SDR|HDR10|HDR10Plus|HLG|DOVIWithHDR10|DOVIWithHDR10Plus|DOVIWithHLG|DOVIWithSDR|DOVIWithEL|DOVIWithELHDR10Plus'
    : 'SDR|DOVIWithSDR';
  const [hls, ...audioOnly] = base.TranscodingProfiles;
  return {
    ...base,
    DirectPlayProfiles: [
      { Container: Object.keys(CONTAINERS).join(','), Type: 'Video', VideoCodec: video, AudioCodec: SOFT_AUDIO.join(',') },
      ...base.DirectPlayProfiles.filter((p) => p.Type === 'Audio')
    ],
    TranscodingProfiles: [{ ...hls, Container: 'mp4', VideoCodec: video, AudioCodec: CONVERTED_AUDIO }, ...audioOnly],
    CodecProfiles: [
      /* Libmedia doesn't deinterlace. */
      { Type: 'Video', Conditions: [
        { Condition: 'NotEquals', Property: 'IsInterlaced', Value: 'true', IsRequired: false },
        { Condition: 'EqualsAny', Property: 'VideoRangeType', Value: ranges, IsRequired: false }
      ] },
      // Picture and channel limits from this device's settings; none about codecs or the audio track.
      ...base.CodecProfiles.filter((p) => !p.Codec && p.Type !== 'VideoAudio'),
      ...(o.maxAudioChannels > 0
        ? [{ Type: 'VideoAudio', Conditions: [{ Condition: 'LessThanEqual', Property: 'AudioChannels', Value: String(o.maxAudioChannels), IsRequired: false }] }]
        : [])
    ]
  };
}
