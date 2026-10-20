import { fmtWeekdayDate, daysFromToday } from '../../lib/format';
import { t } from '../../lib/i18n';
import type { LogFile } from './api';

/* Jellyfin's Serilog lines: "[2026-10-19 13:10:09.983 +00:00] [INF] [4] Emby.Server.Implementations.Foo: message".
   The board shows them as "20:01:58  INF  Foo: message" in the viewer's time zone. */

export type Level = 'VRB' | 'DBG' | 'INF' | 'WRN' | 'ERR' | 'FTL' | '';

export interface Entry { i: number; time: string; level: Level; text: string; raw: string }

const LINE = /^\[(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})(?:\.\d+)? ?([+-]\d{2}:?\d{2}|Z)?\] \[(VRB|DBG|INF|WRN|ERR|FTL)\] (?:\[\d+\] )?(.*)$/;
const SOURCE = /^((?:[A-Za-z_][\w`]*\.)+)([A-Za-z_][\w`]*(?:\[[^\]]*\])?): /;

const pad = (n: number) => String(n).padStart(2, '0');

function localTime(date: string, time: string, offset?: string): string {
  if (!offset) return time;
  const iso = `${date}T${time}${offset === 'Z' ? 'Z' : offset.length === 5 ? offset.slice(0, 3) + ':' + offset.slice(3) : offset}`;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return time;
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

export interface Parsed { entries: Entry[]; structured: boolean; text: string; lastStart: number }

/* Parses the text. When the new text only grows the old one (a log being written), the entries before
   the last one are kept as they are and only the tail is read again. */
export function parseLog(text: string, prev?: Parsed): Parsed {
  let entries: Entry[] = [];
  let offset = 0;
  let structured = false;
  if (prev && prev.structured && prev.entries.length > 1 && text.length >= prev.text.length && text.startsWith(prev.text)) {
    entries = prev.entries.slice(0, -1);
    offset = prev.lastStart;
    structured = true;
  }
  const lines = text.slice(offset).split('\n');
  if (lines.length && lines[lines.length - 1] === '') lines.pop();
  if (!structured) {
    for (let k = 0; k < Math.min(lines.length, 40); k++) if (LINE.test(lines[k].replace(/\r$/, ''))) { structured = true; break; }
  }
  let lastStart = offset;
  for (const rawLine of lines) {
    const line = rawLine.replace(/\r$/, '');
    const m = structured ? LINE.exec(line) : null;
    if (m) {
      lastStart = offset;
      entries.push({ i: entries.length, time: localTime(m[1], m[2], m[3]), level: m[4] as Level, text: m[5].replace(SOURCE, '$2: '), raw: line });
    } else if (structured && entries.length) {
      const e = entries[entries.length - 1];
      entries[entries.length - 1] = { ...e, text: e.text + '\n' + line, raw: e.raw + '\n' + line };
    } else {
      lastStart = offset;
      entries.push({ i: entries.length, time: '', level: '', text: line, raw: line });
    }
    offset += rawLine.length + 1;
  }
  return { entries, structured, text, lastStart };
}

export const isWarn = (l: Level) => l === 'WRN' || l === 'ERR' || l === 'FTL';
export const isError = (l: Level) => l === 'ERR' || l === 'FTL';

/* ── File list labels ── */

const DAILY = /^log_(\d{4})(\d{2})(\d{2})\.(log|txt)$/i;
const FFMPEG = /^FFmpeg\.([A-Za-z]+)-(\d{4})-(\d{2})-(\d{2})_(\d{2})-(\d{2})/;

export const isDaily = (name: string) => DAILY.test(name);

/* FFmpeg log kinds Jellyfin writes, and how a titled one reads ("Sintel transcode"). Others keep the file's word. */
const KINDS: Record<string, { label: string; titled: string }> = {
  Transcode: { label: 'Transcode', titled: '{title} transcode' },
  Remux: { label: 'Remux', titled: '{title} remux' },
  DirectStream: { label: 'Direct Stream', titled: '{title} direct stream' }
};

/* "Today", "Yesterday", "Sat 17 Oct"; "Sintel transcode" once its title is known, else "Transcode · 14:09". */
export function fileLabel(f: LogFile, title?: string): string {
  const d = DAILY.exec(f.Name);
  if (d) {
    const date = new Date(Number(d[1]), Number(d[2]) - 1, Number(d[3]));
    const days = daysFromToday(date);
    return days === 0 ? t('Today') : days === -1 ? t('Yesterday') : fmtWeekdayDate(date);
  }
  const m = FFMPEG.exec(f.Name);
  if (m) {
    const known = KINDS[m[1]];
    const kind = known ? t(known.label) : m[1].replace(/([a-z])([A-Z])/g, '$1 $2');
    if (title) return known ? t(known.titled, { title }) : `${title} ${kind.toLowerCase()}`;
    return `${kind} · ${m[5]}:${m[6]}`;
  }
  return f.Name;
}

/* FFmpeg logs for playback start with the media source as JSON; its Name is the title. */
export const wantsTitle = (name: string) => /^FFmpeg\.(Transcode|Remux|DirectStream)-/i.test(name);

export function titleFromLog(text: string): string | undefined {
  const first = text.slice(0, text.indexOf('\n') > 0 ? text.indexOf('\n') : 4000);
  const m = /"Name":"((?:[^"\\]|\\.)*)"/.exec(first);
  if (!m) return undefined;
  try {
    return (JSON.parse(`"${m[1]}"`) as string).replace(/\s*\(\d{4}\)\s*$/, '').trim() || undefined;
  } catch {
    return undefined;
  }
}

/* "FFmpeg.Transcode-2026-10-19_14-09-30_fb0a…_07a39e63.log" → "FFmpeg.Transcode-2026-10-19_14-09-30_….log":
   the ids after the time only matter to the server. */
export function shortFileName(name: string): string {
  return name.replace(/_[0-9a-f]{16,}(?:_[0-9a-f]{6,})?(\.\w+)$/i, '_…$1');
}
