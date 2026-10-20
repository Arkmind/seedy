import type { BaseItem, MediaSource, MediaStream } from '../../api/types';

/* Technical labels for the Details hero and info panel: "4K · HDR10 · Atmos · CC",
   "2160p · HEVC · HDR10", "English · EAC3 5.1 Atmos", "English, French, Spanish +3". */

export function defaultSource(item?: BaseItem | null): MediaSource | undefined {
  return item?.MediaSources?.[0];
}

export function streamsOf(item?: BaseItem | null): MediaStream[] {
  return defaultSource(item)?.MediaStreams ?? item?.MediaStreams ?? [];
}

const langNames = (() => {
  try { return new Intl.DisplayNames(['en'], { type: 'language' }); } catch { return null; }
})();

export function languageName(s: MediaStream): string {
  const code = s.Language && s.Language !== 'und' ? s.Language : '';
  if (code && langNames) {
    try {
      const n = langNames.of(code);
      if (n && n.toLowerCase() !== code.toLowerCase()) return n;
    } catch { /* not a code the browser knows */ }
  }
  if (s.Title && !/^(stereo|mono|default|forced|sdh|\d(\.\d)? ?ch)/i.test(s.Title)) return s.Title;
  return code ? code.toUpperCase() : '';
}

export function videoStream(ms: MediaStream[]) {
  return ms.find((s) => s.Type === 'Video');
}

export function audioStream(ms: MediaStream[], source?: MediaSource) {
  const audio = ms.filter((s) => s.Type === 'Audio');
  return audio.find((s) => s.Index === source?.DefaultAudioStreamIndex) ?? audio.find((s) => s.IsDefault) ?? audio[0];
}

export function subtitleStreams(ms: MediaStream[]) {
  return ms.filter((s) => s.Type === 'Subtitle');
}

function is4k(v: MediaStream) { return (v.Width ?? 0) >= 3800 || (v.Height ?? 0) >= 2000; }

export function resolutionBadge(v?: MediaStream): string | undefined {
  if (!v || (!v.Width && !v.Height)) return undefined;
  const w = v.Width ?? 0;
  const h = v.Height ?? 0;
  if (is4k(v)) return '4K';
  if (w >= 1900 || h >= 1000) return '1080p';
  if (w >= 1260 || h >= 700) return '720p';
  return 'SD';
}

function resolutionLine(v: MediaStream): string | undefined {
  const w = v.Width ?? 0;
  const h = v.Height ?? 0;
  if (!w && !h) return undefined;
  if (is4k(v)) return '2160p';
  if (w >= 1900 || h >= 1000) return '1080p';
  if (w >= 1260 || h >= 700) return '720p';
  return `${h}p`;
}

export function rangeLabel(v?: MediaStream): string | undefined {
  const t = v?.VideoRangeType ?? '';
  if (t.startsWith('DOVI')) return 'Dolby Vision';
  if (t === 'HDR10Plus') return 'HDR10+';
  if (t === 'HDR10') return 'HDR10';
  if (t === 'HLG') return 'HLG';
  if (!t && v?.VideoRange === 'HDR') return 'HDR';
  return undefined;
}

const VIDEO_CODECS: Record<string, string> = { hevc: 'HEVC', h265: 'HEVC', h264: 'H.264', avc: 'H.264', av1: 'AV1', vp9: 'VP9', vp8: 'VP8', mpeg2video: 'MPEG-2', mpeg4: 'MPEG-4', vc1: 'VC-1' };
const AUDIO_CODECS: Record<string, string> = {
  eac3: 'EAC3', ac3: 'AC3', aac: 'AAC', dts: 'DTS', truehd: 'TrueHD', flac: 'FLAC', opus: 'Opus', mp3: 'MP3', vorbis: 'Vorbis',
  pcm_s16le: 'PCM', pcm_s24le: 'PCM', alac: 'ALAC', mp2: 'MP2'
};

export function videoLine(v?: MediaStream): string | undefined {
  if (!v) return undefined;
  const parts = [resolutionLine(v), v.Codec ? VIDEO_CODECS[v.Codec.toLowerCase()] ?? v.Codec.toUpperCase() : undefined, rangeLabel(v)];
  const s = parts.filter(Boolean).join(' · ');
  return s || undefined;
}

export function isAtmos(a?: MediaStream): boolean {
  if (!a) return false;
  if (a.AudioSpatialFormat === 'DolbyAtmos') return true;
  return /atmos/i.test([a.Profile, a.Title, a.DisplayTitle].filter(Boolean).join(' '));
}

function channels(a: MediaStream): string | undefined {
  const n = a.Channels ?? 0;
  if (!n) return undefined;
  if (n === 1) return 'Mono';
  if (n === 2) return 'Stereo';
  if (n === 6) return '5.1';
  if (n === 8) return '7.1';
  return `${n} ch`;
}

export function audioLine(a?: MediaStream): string | undefined {
  if (!a) return undefined;
  const codec = a.Codec ? AUDIO_CODECS[a.Codec.toLowerCase()] ?? a.Codec.toUpperCase() : '';
  const tail = [codec, channels(a), isAtmos(a) ? 'Atmos' : ''].filter(Boolean).join(' ');
  return [languageName(a), tail].filter(Boolean).join(' · ') || undefined;
}

export function subtitleLine(ms: MediaStream[]): string | undefined {
  const names: string[] = [];
  for (const s of subtitleStreams(ms)) {
    const n = languageName(s) || 'Unknown';
    if (!names.includes(n)) names.push(n);
  }
  if (!names.length) return undefined;
  return names.length > 3 ? `${names.slice(0, 3).join(', ')} +${names.length - 3}` : names.join(', ');
}

/* "4K", "HDR10", "Atmos", "CC" — in board order. */
export function techBadges(item?: BaseItem | null): string[] {
  const ms = streamsOf(item);
  if (!ms.length) return [];
  const v = videoStream(ms);
  const out: string[] = [];
  const res = resolutionBadge(v);
  if (res) out.push(res);
  const range = rangeLabel(v);
  if (range) out.push(range);
  if (ms.some((s) => s.Type === 'Audio' && isAtmos(s))) out.push('Atmos');
  if (subtitleStreams(ms).length) out.push('CC');
  return out;
}

export function techLines(item?: BaseItem | null): { video?: string; audio?: string; subtitles?: string } {
  const ms = streamsOf(item);
  if (!ms.length) return {};
  return { video: videoLine(videoStream(ms)), audio: audioLine(audioStream(ms, defaultSource(item))), subtitles: subtitleLine(ms) };
}
