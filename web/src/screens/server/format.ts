import type { MediaStream } from '../../api/types';
import { clock, daysFromToday, fmtDate, fmtDayMonth } from '../../lib/format';
import { formatNumber, getLang, locale, t, tn } from '../../lib/i18n';
import type { Session, SystemInfo } from './api';

/* How the server dashboard boards write things: sizes, dates and times ("2.4 MB", "today at 03:00", "22 min",
   "Last seen yesterday", "19 Oct 2026, 14:02"), versions, people and what a session is playing. */

export const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'] as const;

/* A weekday as people read it: "Sunday" in English, "dimanche" in French. WEEKDAYS stays the API's English names. */
export function weekdayName(day: number | string): string {
  const i = typeof day === 'number' ? day : (WEEKDAYS as readonly string[]).indexOf(day);
  if (i < 0) return String(day);
  if (getLang() === 'en') return WEEKDAYS[i];
  try {
    return new Intl.DateTimeFormat(locale(), { weekday: 'long', timeZone: 'UTC' }).format(new Date(Date.UTC(2023, 0, 1 + i)));
  } catch {
    return WEEKDAYS[i];
  }
}

/* The same as a label on its own ("Sunday", "Dimanche"). */
export const weekdayLabel = (day: number | string) => {
  const s = weekdayName(day);
  return s.charAt(0).toLocaleUpperCase() + s.slice(1);
};

/* .NET's DateTime.MinValue ("0001-01-01T00:00:00") means "never". */
export function realDate(s?: string | null): Date | undefined {
  if (!s || s.startsWith('0001-')) return undefined;
  const d = new Date(s);
  return Number.isNaN(d.getTime()) || d.getFullYear() < 1971 ? undefined : d;
}

export function fmtSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) return '';
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let v = bytes / 1024;
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  const num = v >= 100 || i === 0 ? String(Math.round(v)) : v.toFixed(1).replace(/\.0$/, '');
  return `${formatNumber(Number(num), { useGrouping: false, maximumFractionDigits: 1 })} ${units[i]}`;
}

/* "today at 03:00", "yesterday at 03:00", "Sunday at 04:00" (this week), "12 Oct at 04:00", "12 Oct 2025". */
export function dayAt(d: Date): string {
  const days = daysFromToday(d);
  const time = clock(d);
  if (days === 0) return t('today at {time}', { time });
  if (days === -1) return t('yesterday at {time}', { time });
  if (days === 1) return t('tomorrow at {time}', { time });
  if (days > -7 && days < 7) return t('{day} at {time}', { day: weekdayName(d.getDay()), time });
  if (d.getFullYear() === new Date().getFullYear()) return t('{date} at {time}', { date: fmtDayMonth(d), time });
  // French needs the article before a bare date ("le 12 oct. 2025"); English shows the date alone.
  return getLang() === 'en' ? fmtDate(d) : t('on {date}', { date: fmtDate(d) });
}

/* "on Sunday", "today", "yesterday", "on 12 Oct". */
export function onDay(d: Date): string {
  const days = daysFromToday(d);
  if (days === 0) return t('today');
  if (days === -1) return t('yesterday');
  if (days > -7 && days < 0) return t('on {day}', { day: weekdayName(d.getDay()) });
  return t('on {date}', { date: d.getFullYear() === new Date().getFullYear() ? fmtDayMonth(d) : fmtDate(d) });
}

/* How long a run took: "1 s", "12 s", "4 min", "1 h 5 min". */
export function runLength(ms: number): string {
  const s = Math.max(0, Math.round(ms / 1000));
  if (s < 60) return `${Math.max(1, s)} s`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m} min`;
  const h = Math.floor(m / 60);
  const rest = m % 60;
  return rest ? `${h} h ${rest} min` : `${h} h`;
}

/* "just now", "3 min ago", "1 hour ago", "5 hours ago", "yesterday", "4 days ago", "3 weeks ago", "6 months ago", "2 years ago". */
export function ago(d: Date): string {
  const s = Math.round((Date.now() - d.getTime()) / 1000);
  if (s < 45) return t('just now');
  const m = Math.round(s / 60);
  if (m < 60) return t('{n} min ago', { n: m });
  const h = Math.round(m / 60);
  if (h < 24) return h === 1 ? t('1 hour ago') : t('{n} hours ago', { n: h });
  const days = Math.round(h / 24);
  if (days === 1) return t('yesterday');
  if (days < 14) return t('{n} days ago', { n: days });
  if (days < 60) return t('{n} weeks ago', { n: Math.round(days / 7) });
  const months = Math.round(days / 30.4);
  if (months < 12) return t('{n} months ago', { n: months });
  const years = Math.round(days / 365);
  return years <= 1 ? t('1 year ago') : t('{n} years ago', { n: years });
}

/* "Today", "2 Mar 2026". */
export const createdDay = (d: Date) => (daysFromToday(d) === 0 ? t('Today') : daysFromToday(d) === -1 ? t('Yesterday') : fmtDate(d));

/* "19 Oct 2026, 14:02". */
export const dateTime = (d: Date) => t('{date}, {time}', { date: fmtDate(d), time: clock(d) });

/* "12.2.0" → "12.2"; "0.1.0.0" → "0.1.0". */
export function shortVersion(v?: string | null, parts = 3): string {
  if (!v) return '';
  const bits = v.split('.');
  while (bits.length > parts && bits[bits.length - 1] === '0') bits.pop();
  return bits.join('.');
}

export const majorMinor = (v?: string | null) => (v ? v.split('.').slice(0, 2).join('.') : '');

/* Compare dotted versions numerically. */
export function cmpVersion(a?: string | null, b?: string | null): number {
  const pa = (a ?? '').split('.').map((x) => parseInt(x, 10) || 0);
  const pb = (b ?? '').split('.').map((x) => parseInt(x, 10) || 0);
  for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0);
    if (d) return d < 0 ? -1 : 1;
  }
  return 0;
}

/* Copies text; falls back to a hidden textarea where the async clipboard isn't allowed (plain http). */
export async function copyText(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text);
      return true;
    }
  } catch { /* fall through */ }
  try {
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    ta.remove();
    return ok;
  } catch {
    return false;
  }
}

export function downloadText(name: string, text: string, type = 'text/plain;charset=utf-8') {
  const blob = new Blob([text], { type });
  const href = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = href;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(href), 1000);
}

/* ── Dates and people (Overview, Users, Devices, Activity) ── */

export const AVATAR_COLOURS = ['#f0f0f0', '#9fd3ff', '#b8f59f', '#ffc98a', '#e3c4ff', '#ffb3e6', '#a8e6df', '#f0b9a0'];
export const avatarColour = (index: number) => AVATAR_COLOURS[Math.max(0, index) % AVATAR_COLOURS.length];

const pad = (n: number) => String(n).padStart(2, '0');
export const hhmm = (d: Date) => `${pad(d.getHours())}:${pad(d.getMinutes())}`;

/* Whole days between that day and today (0 today, 1 yesterday). */
export function dayDiff(d: Date): number {
  const a = new Date(d); a.setHours(0, 0, 0, 0);
  const b = new Date(); b.setHours(0, 0, 0, 0);
  return Math.round((b.getTime() - a.getTime()) / 86_400_000);
}

/* "17 Oct", or "17 Oct 2025" outside this year. */
export const shortDate = (d: Date) => (d.getFullYear() === new Date().getFullYear() ? fmtDayMonth(d) : fmtDate(d));

/* "today at 18:30", "yesterday at 03:00", "on 12 Oct at 03:00": the {when} of a sentence. */
export function atLabel(d: Date): string {
  const days = dayDiff(d);
  if (days === 0) return t('today at {time}', { time: hhmm(d) });
  if (days === 1) return t('yesterday at {time}', { time: hhmm(d) });
  return t('on {date} at {time}', { date: fmtDayMonth(d), time: hhmm(d) });
}

/* Activity times: "20:02" today, "Yesterday", then "17 Oct". */
export function activityWhen(d: Date): string {
  const days = dayDiff(d);
  if (days === 0) return hhmm(d);
  if (days === 1) return t('Yesterday');
  return shortDate(d);
}

/* "Last seen yesterday", "Last seen 3 months ago", "Last seen 3 days ago on iPhone". */
export function lastSeen(d?: Date, device?: string): string {
  if (!d || Number.isNaN(d.getTime()) || d.getFullYear() < 2000) return t('Never signed in');
  const seen = lastSeenText(d);
  return device ? t('{seen} on {device}', { seen, device }) : seen;
}

function lastSeenText(d: Date): string {
  const mins = Math.round((Date.now() - d.getTime()) / 60_000);
  const days = dayDiff(d);
  if (mins < 60) return t('Last seen {n} min ago', { n: Math.max(1, mins) });
  if (days === 0) return t('Last seen today at {time}', { time: hhmm(d) });
  if (days === 1) return t('Last seen yesterday');
  if (days < 7) return t('Last seen {n} days ago', { n: days });
  if (days < 30) return tn(Math.round(days / 7), 'Last seen {n} week ago', 'Last seen {n} weeks ago');
  if (days < 365) return tn(Math.max(1, Math.round(days / 30.4)), 'Last seen {n} month ago', 'Last seen {n} months ago');
  return tn(Math.round(days / 365), 'Last seen a year ago', 'Last seen {n} years ago');
}

export function uptime(since: Date): string {
  const mins = Math.max(0, Math.round((Date.now() - since.getTime()) / 60_000));
  if (mins < 60) return t('up {n} min', { n: Math.max(1, mins) });
  const hours = Math.round(mins / 60);
  if (hours < 24) return tn(hours, 'up {n} hour', 'up {n} hours');
  return tn(Math.floor(hours / 24), 'up {n} day', 'up {n} days');
}

/* "Linux x64": Jellyfin 12 no longer reports the OS, so read it from how the server's paths look. */
export function platformLabel(info: SystemInfo, knownOs = ''): string {
  const arch = (info.SystemArchitecture ?? '').toLowerCase().replace('x64', 'x64').replace('arm64', 'arm64');
  const p = info.ProgramDataPath ?? info.CachePath ?? '';
  let os = knownOs || info.OperatingSystemDisplayName || info.OperatingSystem || '';
  if (!os) {
    if (/^[a-z]:[\\/]/i.test(p) || p.startsWith('\\\\')) os = 'Windows';
    else if (/^\/Users\/|Library\/Application Support/.test(p)) os = 'macOS';
    else if (p.startsWith('/')) os = 'Linux';
  }
  return [os, arch].filter(Boolean).join(' ');
}

export const mbps = (bps?: number) => {
  if (!bps) return '';
  const m = bps / 1_000_000;
  return `${formatNumber(m >= 10 ? Math.round(m) : Math.round(m * 10) / 10)} Mbps`;
};

/* Short month and day names as the boards write them ("19 Oct", "Mon"); French uses its usual abbreviations. */
const MONTHS_EN = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const MONTHS_FR = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];
const DAYS_EN = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const DAYS_FR = ['dim.', 'lun.', 'mar.', 'mer.', 'jeu.', 'ven.', 'sam.'];
export const monthShort = (d: Date) => (getLang() === 'fr' ? MONTHS_FR : MONTHS_EN)[d.getMonth()];
export const dayShort = (d: Date) => (getLang() === 'fr' ? DAYS_FR : DAYS_EN)[d.getDay()];

/* "Now", "2 min ago", "Yesterday, 19:40", "14 Oct", "2 Aug 2025". */
export function lastActive(iso?: string | null, now = Date.now()): string {
  if (!iso) return t('Never');
  const d = new Date(iso);
  const s = Math.round((now - d.getTime()) / 1000);
  if (s < 60) return t('Now');
  const m = Math.round(s / 60);
  if (m < 60) return t('{n} min ago', { n: m });
  const today = new Date(now);
  today.setHours(0, 0, 0, 0);
  const hm = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  if (d >= today) return t('Today, {time}', { time: hm });
  if (d.getTime() >= today.getTime() - 86_400_000) return t('Yesterday, {time}', { time: hm });
  const dm = `${d.getDate()} ${monthShort(d)}`;
  return d.getFullYear() === today.getFullYear() ? dm : `${dm} ${d.getFullYear()}`;
}

/* ── Playback descriptions for "Playing now" ── */

const CODEC: Record<string, string> = {
  hevc: 'HEVC', h265: 'HEVC', h264: 'H.264', avc: 'H.264', av1: 'AV1', vp9: 'VP9', vp8: 'VP8', mpeg2video: 'MPEG-2', mpeg4: 'MPEG-4', vc1: 'VC-1',
  aac: 'AAC', ac3: 'AC3', eac3: 'EAC3', dts: 'DTS', truehd: 'TrueHD', flac: 'FLAC', opus: 'Opus', mp3: 'MP3', vorbis: 'Vorbis', pcm_s16le: 'PCM', alac: 'ALAC'
};
export const codecName = (c?: string) => (c ? CODEC[c.toLowerCase()] ?? c.toUpperCase() : '');

const HWA: Record<string, string> = {
  qsv: 'Intel QSV', nvenc: 'NVIDIA NVENC', amf: 'AMD AMF', vaapi: 'VA-API', videotoolbox: 'VideoToolbox', rkmpp: 'Rockchip MPP', v4l2m2m: 'V4L2'
};

function channels(n?: number): string {
  if (!n) return '';
  if (n === 1) return t('mono');
  if (n === 2) return t('stereo');
  if (n === 6) return '5.1';
  if (n === 8) return '7.1';
  return t('{n} ch', { n });
}

function resolution(w?: number, h?: number): string {
  if (!w && !h) return '';
  if ((w ?? 0) >= 3200 || (h ?? 0) >= 2000) return '4K';
  if ((w ?? 0) >= 1800 || (h ?? 0) >= 1000) return '1080p';
  if ((w ?? 0) >= 1200 || (h ?? 0) >= 700) return '720p';
  return 'SD';
}

function rangeLabel(v?: MediaStream): string {
  const t = v?.VideoRangeType ?? '';
  if (!t || t === 'SDR' || t === 'Unknown') return '';
  if (t.startsWith('DOVI')) return 'Dolby Vision';
  if (t === 'HDR10Plus') return 'HDR10+';
  return t;
}

const REASONS: Record<string, (src: { video: string; audio: string }) => string> = {
  VideoCodecNotSupported: (s) => (s.video ? t('the device can’t play {codec}', { codec: s.video }) : t('the device can’t play this video')),
  AudioCodecNotSupported: (s) => (s.audio ? t('the device can’t play {codec}', { codec: s.audio }) : t('the device can’t play this audio')),
  ContainerNotSupported: () => t('the device can’t open this file type'),
  ContainerBitrateExceedsLimit: () => t('the file is above the streaming limit'),
  VideoBitrateNotSupported: () => t('the video is above the streaming limit'),
  AudioBitrateNotSupported: () => t('the audio is above the streaming limit'),
  SubtitleCodecNotSupported: () => t('subtitles are burned in'),
  VideoResolutionNotSupported: () => t('the resolution is too high for the device'),
  VideoRangeTypeNotSupported: () => t('the device can’t show HDR'),
  VideoLevelNotSupported: () => t('the video profile level is too high'),
  VideoProfileNotSupported: () => t('the device can’t play this video profile'),
  VideoBitDepthNotSupported: () => t('the device can’t play this bit depth'),
  AudioChannelsNotSupported: () => t('the device can’t play this many audio channels'),
  InterlacedVideoNotSupported: () => t('the video is interlaced'),
  DirectPlayError: () => t('direct play failed')
};

export type PlayMethodKind = 'direct' | 'stream' | 'transcode';

export function playMethod(s: Session): PlayMethodKind {
  const m = s.PlayState?.PlayMethod;
  if (m === 'Transcode') return s.TranscodingInfo?.IsVideoDirect ? 'stream' : 'transcode';
  if (m === 'DirectStream') return 'stream';
  return 'direct';
}

export function playDetail(s: Session): string {
  const item = s.NowPlayingItem;
  const streams = item?.MediaStreams ?? item?.MediaSources?.[0]?.MediaStreams ?? [];
  const video = streams.find((x) => x.Type === 'Video');
  const audio = (s.PlayState?.AudioStreamIndex !== undefined ? streams.find((x) => x.Type === 'Audio' && x.Index === s.PlayState?.AudioStreamIndex) : undefined)
    ?? streams.find((x) => x.Type === 'Audio' && x.IsDefault) ?? streams.find((x) => x.Type === 'Audio');
  const ti = s.TranscodingInfo;
  const method = playMethod(s);
  const srcVideo = codecName(video?.Codec);
  const srcAudio = codecName(audio?.Codec);
  if (method === 'direct') {
    const v = [resolution(video?.Width, video?.Height), srcVideo, rangeLabel(video)].filter(Boolean).join(' ');
    const a = [srcAudio, audio?.ChannelLayout?.replace(/\(.*\)/, '') || channels(audio?.Channels)].filter(Boolean).join(' ');
    const rate = mbps(item?.MediaSources?.[0]?.Bitrate ?? video?.BitRate);
    return [t('Direct play'), v, a, rate].filter(Boolean).join(' · ');
  }
  if (method === 'stream') {
    const parts = [t('Direct stream')];
    if (ti && !ti.IsAudioDirect && ti.AudioCodec) {
      const to = `${codecName(ti.AudioCodec)}${ti.AudioChannels ? ' ' + channels(ti.AudioChannels) : ''}`;
      parts.push(srcAudio ? t('audio {from} to {to}', { from: srcAudio, to }) : t('audio track to {to}', { to }));
    } else if (ti?.Container) parts.push(t('into {container}', { container: ti.Container.toUpperCase() }));
    parts.push(ti && !ti.IsAudioDirect ? t('video copied') : t('video and audio copied'));
    return parts.join(' · ');
  }
  const hw = ti?.HardwareAccelerationType && ti.HardwareAccelerationType !== 'none' ? HWA[ti.HardwareAccelerationType] ?? ti.HardwareAccelerationType : '';
  const to = codecName(ti?.VideoCodec);
  const v = { from: srcVideo, to, hw };
  let line = srcVideo && to
    ? (hw ? t('Transcoding {from} to {to} with {hw}', v) : t('Transcoding {from} to {to}', v))
    : to ? (hw ? t('Transcoding to {to} with {hw}', v) : t('Transcoding to {to}', v))
    : hw ? t('Transcoding video with {hw}', v) : t('Transcoding video');
  const rate = mbps(ti?.Bitrate);
  if (rate) line += `, ${rate}`;
  const reasons = Array.isArray(ti?.TranscodeReasons) ? ti!.TranscodeReasons : String(ti?.TranscodeReasons ?? '').split(',').map((x) => x.trim()).filter(Boolean);
  const first = reasons.map((r) => REASONS[r]).find(Boolean);
  return first ? t('{line}. Reason: {reason}.', { line, reason: first({ video: srcVideo, audio: srcAudio }) }) : `${line}.`;
}
