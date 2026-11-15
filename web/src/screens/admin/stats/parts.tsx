import type { ComponentChildren } from 'preact';
import { userImageUrl } from '../../../api/images';
import { capitalize, clock, fmtIntl } from '../../../lib/format';
import { formatNumber, t, tn } from '../../../lib/i18n';
import { AVATAR_COLOURS } from '../../../ui/avatar';
import type { Kind, Method } from './api';

/* Pieces both Statistics pages share. Colours are the boards'. */

export const KINDS: Kind[] = ['show', 'movie', 'music', 'livetv', 'book', 'other'];

export const KIND_COLOUR: Record<Kind, string> = {
  show: '#3987e5', movie: '#d95926', music: '#199e70', livetv: '#c98500', book: '#d55181', other: '#8c8c8c'
};

export const kindLabel = (k: Kind) => ({
  show: t('Shows'), movie: t('Movies'), music: t('Music'), livetv: t('Live TV'), book: t('Books'), other: t('Other videos')
})[k];

export const METHOD_COLOUR: Record<Method, string> = { DirectPlay: '#9fe0b0', DirectStream: '#9fd3ff', Transcode: '#ffc98a' };

export const methodLabel = (m: Method) => ({ DirectPlay: t('Direct play'), DirectStream: t('Direct stream'), Transcode: t('Transcoded') })[m];

const HOUR = 3_600_000;

/* "412", "14.1", "0.6": whole hours from 10 up, one decimal below. */
export function hoursValue(ms: number, decimals?: number): string {
  if (ms <= 0) return formatNumber(0);
  const h = ms / HOUR;
  const d = decimals ?? (h >= 10 ? 0 : 1);
  return formatNumber(h, { minimumFractionDigits: d, maximumFractionDigits: d });
}

/* "189 h", "4.5 h", "35 min". */
export function hoursText(ms: number, decimals?: number): string {
  if (ms <= 0) return t('{n} h', { n: formatNumber(0) });
  if (decimals === undefined && ms > 0 && ms < HOUR / 10) return t('{n} min', { n: formatNumber(Math.max(1, Math.round(ms / 60000))) });
  return t('{n} h', { n: hoursValue(ms, decimals) });
}

/* "9 h 50 min", "45 min". */
export function durationText(ms: number): string {
  const total = Math.round(ms / 60000);
  const h = Math.floor(total / 60);
  const m = total % 60;
  if (h === 0) return t('{n} min', { n: m });
  return m === 0 ? t('{n} h', { n: formatNumber(h) }) : t('{h} h {m} min', { h: formatNumber(h), m });
}

/* "45 min", "1 h 09": the day table's short form. */
export function shortDuration(ms: number): string {
  const total = Math.round(ms / 60000);
  const h = Math.floor(total / 60);
  const m = total % 60;
  return h === 0 ? t('{n} min', { n: m }) : t('{h} h {m}', { h, m: String(m).padStart(2, '0') });
}

export const mbps = (bps: number) => formatNumber(Math.round(bps / 1_000_000));

/* "2026-11-08" → a local Date at midnight. */
export function parseDay(day: string): Date {
  const [y, m, d] = day.split('-').map(Number);
  return new Date(y, (m || 1) - 1, d || 1);
}

export const dayKey = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

/* "Saturday 8 November" (French: "samedi 8 novembre"); the comma en-GB puts after the weekday is dropped. */
export const longDay = (d: Date, year = false) =>
  capitalize(fmtIntl(d, { weekday: 'long', day: 'numeric', month: 'long', ...(year ? { year: 'numeric' } : {}) }).replace(',', ''));

/* "Sat 8 Nov at 21:10". */
export const shortMoment = (d: Date) =>
  t('{date} at {time}', { date: capitalize(fmtIntl(d, { weekday: 'short', day: 'numeric', month: 'short' }).replace(',', '')), time: clock(d) });

export function pctText(n: number): string {
  return `${formatNumber(Math.round(n))}%`;
}

/* The arrows under the headline numbers. */
export function Trend({ dir, children }: { dir?: 'up' | 'down'; children: ComponentChildren }) {
  return (
    <span class="st-kpi-note">
      {dir && (
        <svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true">
          {dir === 'up' ? <><path d="M12 19V5" /><path d="M6 11l6-6 6 6" /></> : <><path d="M12 5v14" /><path d="M6 13l6 6 6-6" /></>}
        </svg>
      )}
      {children}
    </span>
  );
}

export function Kpi({ label, value, unit, note }: { label: string; value: string; unit?: string; note?: ComponentChildren }) {
  return (
    <div class="st-kpi">
      <span class="st-kpi-label">{label}</span>
      <span class="st-kpi-value"><span class="st-kpi-num">{value}</span><span class="st-kpi-unit">{unit ?? ''}</span></span>
      {note}
    </div>
  );
}

/* "18% more than before", "Same as before". */
export function Change({ now, before, more, less, same }: { now: number; before?: number | null; more: string; less: string; same: string }) {
  if (before === undefined || before === null) return null;
  if (before === 0) return now > 0 ? <Trend dir="up">{t('Nothing before')}</Trend> : <Trend>{same}</Trend>;
  const pct = Math.round(((now - before) / before) * 100);
  if (pct === 0) return <Trend>{same}</Trend>;
  return <Trend dir={pct > 0 ? 'up' : 'down'}>{t(pct > 0 ? more : less, { n: Math.abs(pct) })}</Trend>;
}

export function Avatar({ userId, name, imageTag, index, size, grey }: { userId: string; name: string; imageTag?: string | null; index: number; size: number; grey?: boolean }) {
  const img = imageTag ? userImageUrl({ Id: userId, PrimaryImageTag: imageTag }, size * 2) : undefined;
  return (
    <span class="st-avatar" aria-hidden="true" style={{ width: size + 'px', height: size + 'px', background: grey ? '#8c8c8c' : AVATAR_COLOURS[index % AVATAR_COLOURS.length] }}>
      {img ? <img src={img} alt="" /> : name.trim().charAt(0).toUpperCase()}
    </span>
  );
}

export function MethodBadge({ method }: { method?: Method | null }) {
  if (!method) return <span class="st-badge is-none">{t('Not recorded')}</span>;
  return <span class="st-badge"><span class="st-dot" style={{ background: METHOD_COLOUR[method] }} />{methodLabel(method)}</span>;
}

export function Panel({ id, title, aside, children, class: cls }: { id: string; title: string; aside?: ComponentChildren; children: ComponentChildren; class?: string }) {
  return (
    <section aria-labelledby={id} class={'st-panel' + (cls ? ' ' + cls : '')}>
      <div class="st-panel-head">
        <h2 id={id}>{title}</h2>
        {aside}
      </div>
      {children}
    </section>
  );
}

/* Codec names as people know them. */
export function codecName(codec?: string | null): string | undefined {
  if (!codec) return undefined;
  const c = codec.toLowerCase();
  return ({
    hevc: 'HEVC', h265: 'HEVC', h264: 'H.264', avc: 'H.264', av1: 'AV1', vp9: 'VP9', vp8: 'VP8', mpeg2video: 'MPEG-2', mpeg4: 'MPEG-4',
    vc1: 'VC-1', pgssub: 'PGS', pgs: 'PGS', hdmv_pgs_subtitle: 'PGS', dvdsub: 'DVD', dvd_subtitle: 'DVD', dvbsub: 'DVB', dvb_subtitle: 'DVB',
    ass: 'ASS', ssa: 'SSA', subrip: 'SRT', srt: 'SRT', webvtt: 'WebVTT', mov_text: 'MP4'
  } as Record<string, string>)[c] ?? codec.toUpperCase();
}

export const songs = (n: number) => tn(n, '{n} song', '{n} songs');
