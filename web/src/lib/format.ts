import type { BaseItem } from '../api/types';
import { formatNumber, getLang, locale, t, tn } from './i18n';

/* Copy formats follow the boards: "1 h 54 min", "2 h 03 min", "34 min left", "Ends at 22:41",
   "S3 E2", "Fri 23 Oct", "In 4 weeks · 17 Nov". */

export const TICKS_PER_SECOND = 10_000_000;
export const ticksToSeconds = (t?: number | null) => (t ?? 0) / TICKS_PER_SECOND;
export const secondsToTicks = (s: number) => Math.round(s * TICKS_PER_SECOND);

export function duration(seconds: number): string {
  // Anything that runs at all is at least "1 min"; "0 min" reads as broken.
  const totalMin = seconds > 0 ? Math.max(1, Math.round(seconds / 60)) : 0;
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  if (h === 0) return `${m} min`;
  return `${h} h ${String(m).padStart(2, '0')} min`;
}

export const runtime = (item: Pick<BaseItem, 'RunTimeTicks'>) => (item.RunTimeTicks ? duration(ticksToSeconds(item.RunTimeTicks)) : '');

export function remainingSeconds(item: BaseItem): number {
  const total = ticksToSeconds(item.RunTimeTicks);
  const pos = ticksToSeconds(item.UserData?.PlaybackPositionTicks);
  return Math.max(0, total - pos);
}

export const timeLeft = (seconds: number) => t('{time} left', { time: duration(seconds) });

export function progressFraction(item: BaseItem): number {
  if (item.UserData?.PlayedPercentage) return Math.min(1, item.UserData.PlayedPercentage / 100);
  const total = item.RunTimeTicks ?? 0;
  const pos = item.UserData?.PlaybackPositionTicks ?? 0;
  return total > 0 ? Math.min(1, pos / total) : 0;
}

export const pct = (f: number) => `${(Math.max(0, Math.min(1, f)) * 100).toFixed(2)}%`;
/* [n] of [of] as a CSS width, unrounded (quota bars). */
export const pctOf = (n: number, of: number) => `${of > 0 ? Math.max(0, Math.min(100, (n / of) * 100)) : 0}%`;

/* Settings · Display › Dates and times: a 12- or 24-hour clock, month or day first, or ISO dates.
   Month and day names follow Seedy's language; the default is the boards' style ("19 Oct", "22:41"). */
export interface DateStyle { hour12: boolean; monthFirst: boolean; iso: boolean }
let dateStyle: DateStyle = { hour12: false, monthFirst: false, iso: false };
export function setDateStyle(next: DateStyle) { dateStyle = next; }

/* "22:41" whatever the clock setting (timelines, exports, server pages). */
export const hhmm24 = (date: Date) => `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;

export function clock(date: Date): string {
  if (dateStyle.hour12) return `${date.getHours() % 12 || 12}:${String(date.getMinutes()).padStart(2, '0')} ${date.getHours() < 12 ? 'AM' : 'PM'}`;
  return hhmm24(date);
}

export const endsAt = (secondsFromNow: number, rate = 1) => t('Ends at {time}', { time: clock(new Date(Date.now() + (secondsFromNow / (rate || 1)) * 1000)) });

export function timecode(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const mm = String(m).padStart(2, '0');
  const ss = String(sec).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

export function episodeTag(item: Pick<BaseItem, 'ParentIndexNumber' | 'IndexNumber' | 'IndexNumberEnd'>): string {
  const s = item.ParentIndexNumber;
  const e = item.IndexNumber;
  if (s === undefined || s === null || e === undefined || e === null) return e !== undefined && e !== null ? `E${e}` : '';
  const end = item.IndexNumberEnd ? `–${item.IndexNumberEnd}` : '';
  return s === 0 ? t('Special {n}', { n: `${e}${end}` }) : t('S{s} E{e}', { s, e: `${e}${end}` });
}

/* "S01E09" as on the Upcoming shows row. */
export function episodeCode(item: Pick<BaseItem, 'ParentIndexNumber' | 'IndexNumber' | 'IndexNumberEnd'>): string {
  const p = (n?: number) => String(n ?? 0).padStart(2, '0');
  const end = item.IndexNumberEnd ? `–E${p(item.IndexNumberEnd)}` : '';
  return `S${p(item.ParentIndexNumber)}E${p(item.IndexNumber)}${end}`;
}

export const seasonEpisodeLong = (item: BaseItem) =>
  item.ParentIndexNumber === 0
    ? t('Special · Episode {e}', { e: item.IndexNumber ?? '' })
    : t('Season {s} · Episode {e}', { s: item.ParentIndexNumber ?? '', e: item.IndexNumber ?? '' });

/* Fixed short month and day names: three letters in English as on the boards ("19 Oct", "25 Sep 2026"; newer ICU
   data says "Sept"), and the usual French abbreviations ("19 oct.", "ven. 23 oct."). */
const MONTHS_EN = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const DAYS_EN = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const MONTHS_FR = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];
const DAYS_FR = ['dim.', 'lun.', 'mar.', 'mer.', 'jeu.', 'ven.', 'sam.'];
const MONTHS = new Proxy([] as string[], { get: (_, k) => (getLang() === 'fr' ? MONTHS_FR : MONTHS_EN)[k as unknown as number] });
const DAYS = new Proxy([] as string[], { get: (_, k) => (getLang() === 'fr' ? DAYS_FR : DAYS_EN)[k as unknown as number] });

/* "Oct", "oct."; "Mon", "lun.". */
export const fmtMonthShort = (d: Date) => MONTHS[d.getMonth()];
export const fmtWeekdayShort = (d: Date) => DAYS[d.getDay()];

export const fmtDayMonth = (d: Date) => (dateStyle.monthFirst ? `${MONTHS[d.getMonth()]} ${d.getDate()}` : `${d.getDate()} ${MONTHS[d.getMonth()]}`);
/* "2026-11-08", in local time. */
export const dayKey = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
export const fmtDate = (d: Date) =>
  dateStyle.iso ? dayKey(d)
    : dateStyle.monthFirst ? `${MONTHS[d.getMonth()]} ${d.getDate()}, ${d.getFullYear()}`
    : `${d.getDate()} ${MONTHS[d.getMonth()]} ${d.getFullYear()}`;
export const fmtWeekdayDate = (d: Date) => `${DAYS[d.getDay()]} ${fmtDayMonth(d)}`;
/* "Nov 2026", "nov. 2026". */
export const fmtMonthYear = (d: Date) => `${MONTHS[d.getMonth()]} ${d.getFullYear()}`;

/* Any Intl date format in Seedy's language (the browser's own date text if Intl refuses the options). */
export function fmtIntl(d: Date, opts: Intl.DateTimeFormatOptions): string {
  try { return new Intl.DateTimeFormat(locale(), opts).format(d); } catch { return d.toDateString(); }
}

/* "12 March 2024", "12 mars 2024". */
export const longDate = (d: Date) => fmtIntl(d, { day: 'numeric', month: 'long', year: 'numeric' });

export function startOfDay(d: Date): Date {
  const x = new Date(d);
  x.setHours(0, 0, 0, 0);
  return x;
}

/* Whole calendar days from [ref] to [d]: 0 the same day, 1 the day after, -1 the day before. */
export const daysBetween = (d: Date, ref: Date = new Date()) => Math.round((startOfDay(d).getTime() - startOfDay(ref).getTime()) / 86_400_000);

export const daysFromToday = (d: Date) => daysBetween(d);

/* "Today · 19 Oct", "In 8 days · 27 Oct", "In 4 weeks · 17 Nov", "In 2 months · 24 Dec". */
export function whenLabel(d: Date): { text: string; isToday: boolean } {
  const days = daysFromToday(d);
  const date = fmtDayMonth(d);
  if (days < 0) return { text: t('Out {date}', { date }), isToday: false };
  if (days === 0) return { text: t('Today · {date}', { date }), isToday: true };
  if (days === 1) return { text: t('Tomorrow · {date}', { date }), isToday: false };
  if (days < 21) return { text: t('In {n} days · {date}', { n: days, date }), isToday: false };
  if (days < 56) return { text: t('In {n} weeks · {date}', { n: Math.round(days / 7), date }), isToday: false };
  const months = Math.max(2, Math.round(days / 30.4));
  return { text: t('In {n} months · {date}', { n: months, date }), isToday: false };
}

/* History group labels: "Today · Mon 19 Oct", "Yesterday · Sun 18 Oct", "Last week", "Earlier in October". */
export function historyGroup(d: Date): { key: string; label: string } {
  const days = -daysFromToday(d);
  if (days <= 0) return { key: 'today', label: t('Today · {date}', { date: fmtWeekdayDate(d) }) };
  if (days === 1) return { key: 'yesterday', label: t('Yesterday · {date}', { date: fmtWeekdayDate(d) }) };
  if (days < 7) return { key: 'week', label: t('Last week') };
  if (days < 14) return { key: 'lastweek', label: t('Two weeks ago') };
  const now = new Date();
  if (d.getFullYear() === now.getFullYear() && d.getMonth() === now.getMonth()) return { key: 'month', label: t('Earlier this month') };
  const month = new Intl.DateTimeFormat(locale(), { month: 'long', year: d.getFullYear() === now.getFullYear() ? undefined : 'numeric' }).format(d);
  return { key: `m-${d.getFullYear()}-${d.getMonth()}`, label: capitalize(month) };
}

/* An item's year: its production year, else the year of its premiere day. */
export const itemYear = (item: Pick<BaseItem, 'ProductionYear' | 'PremiereDate'>) => item.ProductionYear ?? dayOf(item.PremiereDate)?.getFullYear();

export function yearRange(item: BaseItem): string {
  const start = itemYear(item);
  if (!start) return '';
  if (item.Type !== 'Series') return String(start);
  const end = dayOf(item.EndDate)?.getFullYear();
  if (item.Status === 'Continuing') return `${start}–${new Date().getFullYear() > start ? new Date().getFullYear() : ''}`.replace(/–$/, '');
  if (end && end !== start) return `${start}–${end}`;
  return String(start);
}

/* "248 movies": the English forms are the translation keys ("{n} movie" / "{n} movies"), so every count Seedy shows
   through plural() translates once the dictionary has them. */
/* "Album · 2019 · 12 songs": the parts that have a value, joined with middle dots. */
export const dot = (...parts: (string | number | undefined | null | false)[]) => parts.filter((p) => p !== undefined && p !== null && p !== false && p !== '').join(' · ');

/* "Movies, Shows and Music". */
export function joinNames(names: string[]): string {
  if (names.length <= 1) return names[0] ?? '';
  return t('{list} and {last}', { list: names.slice(0, -1).join(', '), last: names[names.length - 1] });
}

export const plural = (n: number, one: string, many = one + 's') => tn(n, `{n} ${one}`, `{n} ${many}`);

/* "7.4" ("7,4"): a rating with one decimal, rounded as toFixed does. */
export const score = (n?: number) => (n ? formatNumber(Number(n.toFixed(1)), { minimumFractionDigits: 1, maximumFractionDigits: 1 }) : '');

/* "Bob Dylan · 1965 · 6 min": the parts that are there, with the boards' middle dot. */
export const join = (...parts: (string | undefined | null | false)[]) => parts.filter(Boolean).join(' · ');

/* "Chrome, Firefox and Safari" ("Chrome, Firefox et Safari"). */
export function listText(items: string[]): string {
  try { return new Intl.ListFormat(locale(), { style: 'long', type: 'conjunction' }).format(items); } catch { return items.join(', '); }
}

/* French month and language names are lower case ("octobre", "anglais"); a label starts with a capital. */
export const capitalize = (s: string) => (s ? s.charAt(0).toLocaleUpperCase(locale()) + s.slice(1) : s);

/* "JD" for "Jane Doe": the first letters of the first two words. */
export const initials = (name: string) => name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase();

/* A language's name from its code ("fr" → "French"; "français" in French), or undefined when Intl doesn't know it.
   Callers decide how to treat a name that only repeats the code, and their fallback. */
const languageNames = new Map<string, Intl.DisplayNames | null>();
export function displayLanguage(code: string, loc = getLang() === 'en' ? 'en' : locale()): string | undefined {
  let names = languageNames.get(loc);
  if (names === undefined) {
    try { names = new Intl.DisplayNames([loc], { type: 'language' }); } catch { names = null; }
    languageNames.set(loc, names);
  }
  try { return names?.of(code) || undefined; } catch { return undefined; }
}

/* Calendar dates ("2026-10-23", or Jellyfin's "2026-10-23T00:00:00.0000000Z" premiere dates) are days, not moments:
   read them as that day wherever the viewer is, instead of shifting west of UTC to the day before. */
export function dayOf(s?: string | null): Date | undefined {
  if (!s) return undefined;
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:T00:00:00(?:\.0+)?(?:Z|\+00:00)?)?$/.exec(s.trim());
  if (m) return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  const d = new Date(s);
  return Number.isNaN(d.getTime()) ? undefined : d;
}

/* "GB-15" → "15", "DE-16" → "16"; US ratings ("TV-MA", "PG-13") stay as they are. */
const RATING_COUNTRY = /^(GB|UK|IE|DE|AT|CH|FR|BE|NL|LU|ES|PT|IT|SE|NO|DK|FI|IS|PL|CZ|HU|RO|BG|GR|TR|RU|UA|AU|NZ|BR|MX|AR|CL|CO|JP|KR|CN|HK|TW|IN|SG|ZA|CA|US)-/;
export function ratingLabel(rating?: string | null): string {
  if (!rating) return '';
  return rating.trim().replace(RATING_COUNTRY, '');
}

/* Style for a .fit-title heading: its longest word, so the title can shrink to keep that word on one line. */
export function fitTitle(text?: string | null): Record<string, string> {
  const longest = Math.max(4, ...(text ?? '').split(/\s+/).map((w) => w.length));
  return { '--fit-chars': String(longest) };
}
