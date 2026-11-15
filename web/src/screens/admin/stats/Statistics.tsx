import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { cardArt } from '../../../api/images';
import type { BaseItem } from '../../../api/types';
import { formatNumber, t, tn } from '../../../lib/i18n';
import { Link, navigate, setQuery, useLocation } from '../../../lib/router';
import { toast } from '../../../ui/overlay';
import { AdminLayout, LoadError } from '../AdminLayout';
import { exportStats, getStats, type Range, type Stats, type StatsFilters } from './api';
import {
  Avatar, Change, Kpi, KIND_COLOUR, KINDS, METHOD_COLOUR, Panel, Trend, codecName, dayKey, durationText, hoursText, hoursValue, kindLabel, longDay, methodLabel, parseDay, pctText, shortMoment, songs
} from './parts';
import { capitalize, fmtIntl, listText } from '../../../lib/format';
import './stats.css';

const RANGES: { value: Range; label: string }[] = [
  { value: '7d', label: '7 days' },
  { value: '30d', label: '30 days' },
  { value: '12m', label: '12 months' },
  { value: 'all', label: 'All time' }
];

function readFilters(q: URLSearchParams): StatsFilters {
  const r = q.get('range');
  return {
    range: RANGES.some((x) => x.value === r) ? (r as Range) : '30d',
    userId: q.get('user') || undefined,
    libraryId: q.get('library') || undefined,
    device: q.get('device') || undefined
  };
}

/* A link back to this page with one filter changed. */
function statsLink(f: StatsFilters, patch: Partial<StatsFilters>): string {
  const next = { ...f, ...patch };
  const q = new URLSearchParams();
  if (next.range !== '30d') q.set('range', next.range);
  if (next.userId) q.set('user', next.userId);
  if (next.libraryId) q.set('library', next.libraryId);
  if (next.device) q.set('device', next.device);
  const s = q.toString();
  return '/admin/statistics' + (s ? '?' + s : '');
}

export function AdminStatistics() {
  const loc = useLocation();
  const filters = readFilters(loc.query);
  const key = statsLink(filters, {});
  const [data, setData] = useState<Stats | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [loading, setLoading] = useState(true);
  const [attempt, setAttempt] = useState(0);
  const [exporting, setExporting] = useState(false);

  useEffect(() => {
    const ctl = new AbortController();
    setLoading(true);
    getStats(filters, ctl.signal).then(
      (d) => { setData(d); setError(null); setLoading(false); },
      (e: Error) => { if (!ctl.signal.aborted) { setError(e); setLoading(false); } }
    );
    return () => ctl.abort();
  }, [key, attempt]);

  const doExport = async () => {
    setExporting(true);
    try {
      await exportStats(filters);
    } catch (e) {
      toast(`${t('Couldn’t export the statistics.')} ${(e as Error).message || ''}`.trim(), 'error');
    } finally {
      setExporting(false);
    }
  };

  const kept = data ? keptFor(data.retentionDays) : t('kept for 12 months');
  return (
    <AdminLayout
      page="statistics"
      wide
      title={t('Statistics')}
      lede={t('Who watches what, when, and how the server copes. Built from Seedy’s play log, {kept}.', { kept })}
      actions={
        <button type="button" class="st-btn" onClick={doExport} disabled={exporting || !data}>
          <svg viewBox="0 0 24 24" width="17" height="17" aria-hidden="true"><path d="M12 3v12" /><path d="M7 10l5 5 5-5" /><path d="M5 20h14" /></svg>
          {exporting ? t('Exporting…') : t('Export CSV')}
        </button>
      }
    >
      <Filters filters={filters} data={data} />
      {!data && error ? (
        <LoadError error={error} onRetry={() => setAttempt((n) => n + 1)} what="the statistics" />
      ) : !data ? (
        <Skeleton />
      ) : (
        <div class={'st-body' + (loading ? ' is-loading' : '')} aria-busy={loading}>
          <Kpis data={data} filters={filters} />
          <WatchTime data={data} />
          <div class="st-grid">
            <div class="st-col">
              <HowItPlayed data={data} />
              <WhenPeopleWatch data={data} />
              <DevicesPanel data={data} />
            </div>
            <div class="st-col">
              <WhatPeoplePlay data={data} />
              <Viewers data={data} filters={filters} />
              <MostWatched data={data} />
            </div>
          </div>
          <p class="st-foot">{t('Statistics count a play once someone watches or listens for 2 minutes.')}</p>
        </div>
      )}
    </AdminLayout>
  );
}

function keptFor(days: number): string {
  if (days <= 0) return t('kept forever');
  if (days === 365) return t('kept for 12 months');
  if (days === 730) return t('kept for 2 years');
  return tn(days, 'kept for {n} day', 'kept for {n} days');
}

/* ── Filters ── */

function FilterSelect({ label, value, options, onChange }: { label: string; value: string; options: { value: string; label: string }[]; onChange: (v: string) => void }) {
  return (
    <span class="st-select">
      <select aria-label={label} value={value} onChange={(e) => onChange((e.currentTarget as HTMLSelectElement).value)}>
        {options.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
      </select>
      <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><path d="M6 9l6 6 6-6" /></svg>
    </span>
  );
}

function Filters({ filters, data }: { filters: StatsFilters; data: Stats | null }) {
  const f = data?.filters;
  const users = [{ value: '', label: t('Everyone') }, ...(f?.users ?? []).map((u) => ({ value: u.id, label: u.name }))];
  const libraries = [{ value: '', label: t('All libraries') }, ...(f?.libraries ?? []).map((l) => ({ value: l.id, label: l.id === 'livetv' ? t('Live TV') : l.name }))];
  const devices = [{ value: '', label: t('All devices') }, ...(f?.devices ?? []).map((d) => ({ value: d, label: d }))];
  // A filter picked from a link keeps its choice listed even before the data arrives.
  if (filters.userId && !users.some((o) => o.value === filters.userId)) users.push({ value: filters.userId, label: '…' });
  if (filters.libraryId && !libraries.some((o) => o.value === filters.libraryId)) libraries.push({ value: filters.libraryId, label: '…' });
  if (filters.device && !devices.some((o) => o.value === filters.device)) devices.push({ value: filters.device, label: filters.device });
  return (
    <div role="group" aria-label={t('Filters')} class="st-filters">
      <div class="st-filter-selects">
        <FilterSelect label={t('Viewer')} value={filters.userId ?? ''} options={users} onChange={(v) => setQuery({ user: v })} />
        <FilterSelect label={t('Library')} value={filters.libraryId ?? ''} options={libraries} onChange={(v) => setQuery({ library: v })} />
        <FilterSelect label={t('Device')} value={filters.device ?? ''} options={devices} onChange={(v) => setQuery({ device: v })} />
      </div>
      <div role="radiogroup" aria-label={t('Time range')} class="st-range">
        {RANGES.map((r) => (
          <button key={r.value} type="button" role="radio" aria-checked={filters.range === r.value} onClick={() => setQuery({ range: r.value === '30d' ? '' : r.value })}>
            {t(r.label)}
          </button>
        ))}
      </div>
    </div>
  );
}

/* ── Headline numbers ── */

function Kpis({ data, filters }: { data: Stats; filters: StatsFilters }) {
  const k = data.kpis;
  const watchUnder1h = k.watchMs > 0 && k.watchMs < 3_600_000;
  const viewersNote = filters.userId
    ? <Trend>{t('One profile picked')}</Trend>
    : k.missing.length === 0
      ? <Trend dir="up">{k.totalUsers > 0 ? t('Everyone watched') : t('No profiles')}</Trend>
      : <Trend dir="down">{k.missing.length <= 2
        ? tn(k.missing.length, '{names} hasn’t watched', '{names} haven’t watched', { names: listText(k.missing) })
        : tn(k.missing.length, '{n} person hasn’t watched', '{n} people haven’t watched')}</Trend>;
  // No change to show when the log doesn't reach back over the period before: say since when it counts instead.
  const start = data.firstPlay && data.firstPlay > data.from ? data.firstPlay : data.from;
  const since = (data.range === 'all' || (data.firstPlay && data.firstPlay > data.from))
    ? <Trend>{t('Since {date}', { date: fmtIntl(parseDay(start), { day: 'numeric', month: 'short', year: 'numeric' }) })}</Trend>
    : k.prevWatchMs === null || k.prevWatchMs === undefined ? <Trend>{t('Not enough history to compare')}</Trend> : null;
  return (
    <div class="st-kpis">
      <Kpi
        label={t('Watch time')}
        value={watchUnder1h ? formatNumber(Math.round(k.watchMs / 60000)) : hoursValue(k.watchMs)}
        unit={watchUnder1h ? t('min') : t('h')}
        note={since || <Change now={k.watchMs} before={k.prevWatchMs} more="{n}% more than before" less="{n}% less than before" same={t('Same as before')} />}
      />
      <Kpi
        label={t('Plays')}
        value={formatNumber(k.plays)}
        note={since || <Change now={k.plays} before={k.prevPlays} more="{n}% more than before" less="{n}% less than before" same={t('Same as before')} />}
      />
      <Kpi label={t('Viewers')} value={formatNumber(k.viewers)} unit={t('of {n}', { n: formatNumber(k.totalUsers) })} note={viewersNote} />
      <Kpi
        label={t('Most at once')}
        value={formatNumber(k.peak?.streams ?? 0)}
        unit={tn(k.peak?.streams ?? 0, 'stream', 'streams')}
        note={k.peak ? <Trend dir="up">{shortMoment(new Date(k.peak.at))}</Trend> : <Trend>{t('Nothing played')}</Trend>}
      />
      <Kpi
        label={t('Transcoded')}
        value={k.transcodePct === null || k.transcodePct === undefined ? '–' : formatNumber(Math.round(k.transcodePct))}
        unit={k.transcodePct === null || k.transcodePct === undefined ? '' : '%'}
        note={k.transcodePct === null || k.transcodePct === undefined ? <Trend>{t('Not recorded yet')}</Trend> : <TranscodeChange now={k.transcodePct} before={k.prevTranscodePct} />}
      />
    </div>
  );
}

function TranscodeChange({ now, before }: { now: number; before?: number | null }) {
  if (before === null || before === undefined) return null;
  const a = Math.round(now);
  const b = Math.round(before);
  if (a === b) return <Trend>{t('Same as before')}</Trend>;
  return <Trend dir={a < b ? 'down' : 'up'}>{t(a < b ? 'Down from {n}%' : 'Up from {n}%', { n: formatNumber(b) })}</Trend>;
}

/* ── Watch time per day ── */

function useWidth<T extends HTMLElement>(): [preact.RefObject<T>, number] {
  const ref = useRef<T>(null);
  const [w, setW] = useState(0);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    setW(el.clientWidth);
    const ro = new ResizeObserver(() => setW(el.clientWidth));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, w];
}

/* Gridlines every step, three steps up: 10/20/30 h on the board. */
function niceScale(maxMs: number): { step: number; top: number } {
  const h = Math.max(maxMs / 3_600_000, 0.25);
  const raw = h / 3;
  const mag = Math.pow(10, Math.floor(Math.log10(raw)));
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s * 3 >= h) ?? 10 * mag;
  return { step: step * 3_600_000, top: step * 3 * 3_600_000 };
}

function bucketLabel(data: Stats, start: string, long: boolean): string {
  const d = parseDay(start);
  if (data.bucket === 'month') return capitalize(fmtIntl(d, long ? { month: 'long', year: 'numeric' } : { month: 'short' }));
  if (data.bucket === 'week') return long ? t('Week of {date}', { date: fmtIntl(d, { day: 'numeric', month: 'long' }) }) : fmtIntl(d, { day: 'numeric', month: 'short' });
  return long ? longDay(d) : fmtIntl(d, { day: 'numeric', month: 'short' });
}

function WatchTime({ data }: { data: Stats }) {
  const [asTable, setAsTable] = useState(false);
  const buckets = data.buckets;
  // The average leaves out the days before the log began.
  let first = 0;
  buckets.forEach((b, i) => { if (data.firstPlay && b.start <= data.firstPlay) first = i; });
  const counted = buckets.slice(first);
  const avg = counted.length ? counted.reduce((s, b) => s + b.watchMs, 0) / counted.length : 0;
  const per = data.bucket === 'month' ? t('Watch time per month') : data.bucket === 'week' ? t('Watch time per week') : t('Watch time per day');
  const avgText = data.bucket === 'month' ? t('Average {h} a month', { h: hoursText(avg, 1) })
    : data.bucket === 'week' ? t('Average {h} a week', { h: hoursText(avg, 1) }) : t('Average {h} a day', { h: hoursText(avg, 1) });
  return (
    <section aria-labelledby="st-wt" class="st-panel">
      <div class="st-panel-head">
        <h2 id="st-wt">{per}</h2>
        <span class="st-wt-aside">
          <span class="st-avg-key">
            <svg viewBox="0 0 24 4" width="24" height="4" aria-hidden="true"><line x1="0" x2="24" y1="2" y2="2" /></svg>
            {avgText}
          </span>
          <button type="button" class="st-mini-btn" aria-pressed={asTable} onClick={() => setAsTable((v) => !v)}>{asTable ? t('Show as chart') : t('Show as table')}</button>
        </span>
      </div>
      {asTable ? <WatchTable data={data} /> : <WatchChart data={data} avg={avg} />}
    </section>
  );
}

function WatchChart({ data, avg }: { data: Stats; avg: number }) {
  const [box, w] = useWidth<HTMLDivElement>();
  const [hover, setHover] = useState<number | null>(null);
  const buckets = data.buckets;
  const n = Math.max(1, buckets.length);
  const vw = w >= 700 || w === 0 ? 938 : Math.max(w, 280);
  const base = 204;
  const top = 10;
  const left = 35;
  const pitch = (vw - left) / n;
  const barW = Math.max(1, pitch - (pitch > 6 ? 2 : 1));
  const max = Math.max(...buckets.map((b) => b.watchMs), avg, 1);
  const scale = niceScale(max);
  const y = (ms: number) => base - ((base - top) * ms) / scale.top;
  const every = data.bucket === 'day' ? (n <= 10 ? 1 : 7) : data.bucket === 'week' ? Math.max(1, Math.round(n / 6)) : Math.max(1, Math.ceil(n / 8));
  const grid = [0, 1, 2, 3].map((i) => i * scale.step);
  const stepH = scale.step / 3_600_000;
  const gridLabel = (ms: number) => t('{n} h', { n: formatNumber(ms / 3_600_000, { maximumFractionDigits: stepH < 1 ? 2 : stepH < 10 && stepH % 1 ? 1 : 0 }) });
  const best = buckets.reduce((bi, b, i) => (b.watchMs > (buckets[bi]?.watchMs ?? -1) ? i : bi), 0);
  const label = t('Hours watched per {unit}, {from} to {to}. Average {avg}, highest {max} on {day}.', {
    unit: data.bucket === 'month' ? t('month') : data.bucket === 'week' ? t('week') : t('day'),
    from: bucketLabel(data, buckets[0]?.start ?? data.from, true),
    to: bucketLabel(data, buckets[n - 1]?.start ?? data.to, true),
    avg: hoursText(avg, 1),
    max: hoursText(buckets[best]?.watchMs ?? 0, 1),
    day: bucketLabel(data, buckets[best]?.start ?? data.to, true)
  });
  const open = (i: number) => { if (data.bucket === 'day' && buckets[i]) navigate(`/admin/statistics/${buckets[i].start}`); };
  const hb = hover !== null ? buckets[hover] : null;
  const center = hover !== null ? ((left + pitch * hover + barW / 2) / vw) * 100 : 0;

  return (
    <div ref={box} class="st-chart" onMouseLeave={() => setHover(null)}>
      <svg viewBox={`0 0 ${vw} 230`} width="100%" role="img" aria-label={label}>
        {grid.map((ms, i) => (
          <g key={i}>
            <line x1="34" x2={vw} y1={y(ms)} y2={y(ms)} stroke={i === 0 ? '#3a3a3a' : '#222222'} stroke-width="1" />
            <text x="26" y={y(ms) + 4} class="st-axis" text-anchor="end">{gridLabel(ms)}</text>
          </g>
        ))}
        {avg > 0 && <line x1="34" x2={vw} y1={y(avg)} y2={y(avg)} stroke="#a1a1a1" stroke-width="1.5" stroke-dasharray="4 4" />}
        {buckets.map((b, i) => {
          if (b.watchMs <= 0) return null;
          const x = left + pitch * i;
          const yt = y(b.watchMs);
          const r = Math.min(4, barW / 2, base - yt);
          return (
            <path
              key={b.start}
              d={`M${x} ${base} V${yt + r} Q${x} ${yt} ${x + r} ${yt} H${x + barW - r} Q${x + barW} ${yt} ${x + barW} ${yt + r} V${base} Z`}
              class={'st-bar' + (hover === i ? ' is-on' : '')}
            />
          );
        })}
        {buckets.map((b, i) => (i % every === 0 ? (
          <text key={'l' + b.start} x={left + pitch * i + barW / 2} y="224" class="st-axis" text-anchor="middle">{bucketLabel(data, b.start, false)}</text>
        ) : null))}
      </svg>
      <div class="st-hits" style={{ left: `${(left / vw) * 100}%`, bottom: `${((230 - base) / 230) * 100}%` }}>
        {buckets.map((b, i) => (
          <button
            key={b.start}
            type="button"
            class={'st-hit' + (data.bucket === 'day' ? '' : ' is-static')}
            aria-label={`${bucketLabel(data, b.start, true)}: ${hoursText(b.watchMs, 1)}, ${tn(b.plays, '{n} play', '{n} plays')}`}
            onMouseEnter={() => setHover(i)}
            onFocus={() => setHover(i)}
            onBlur={() => setHover(null)}
            onClick={() => open(i)}
            tabIndex={data.bucket === 'day' ? 0 : -1}
          />
        ))}
      </div>
      {hb && (
        <div role="tooltip" class="st-tip" style={{ left: `${center}%`, transform: center > 50 ? 'translateX(-104%)' : 'translateX(4%)' }}>
          <span class="st-tip-title">{bucketLabel(data, hb.start, true)}</span>
          <span class="st-tip-big">{hoursText(hb.watchMs, 1)} <span>· {tn(hb.plays, '{n} play', '{n} plays')}</span></span>
          {KINDS.filter((k) => hb.kinds[k] > 0).map((k) => (
            <span key={k} class="st-tip-row">
              <span class="st-sq" style={{ background: KIND_COLOUR[k] }} />
              <span class="st-tip-name">{kindLabel(k)}</span>
              <span class="st-tip-val">{hoursText(hb.kinds[k], 1)}</span>
            </span>
          ))}
          {data.bucket === 'day' && hb.plays > 0 && (
            <span class="st-tip-more">{t('Click to see who watched')}<svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true"><path d="M9 6l6 6-6 6" /></svg></span>
          )}
        </div>
      )}
    </div>
  );
}

function WatchTable({ data }: { data: Stats }) {
  const kinds = KINDS.filter((k) => data.buckets.some((b) => b.kinds[k] > 0));
  return (
    <div class="st-scroll">
      <table class="st-table">
        <thead>
          <tr>
            <th>{data.bucket === 'month' ? t('Month') : data.bucket === 'week' ? t('Week') : t('Day')}</th>
            <th class="is-num">{t('Watch time')}</th>
            <th class="is-num">{t('Plays')}</th>
            {kinds.map((k) => <th key={k} class="is-num">{kindLabel(k)}</th>)}
          </tr>
        </thead>
        <tbody>
          {[...data.buckets].reverse().map((b) => (
            <tr key={b.start}>
              <td>{data.bucket === 'day' && b.plays > 0 ? <Link to={`/admin/statistics/${b.start}`}>{bucketLabel(data, b.start, true)}</Link> : bucketLabel(data, b.start, true)}</td>
              <td class="is-num">{hoursText(b.watchMs, 1)}</td>
              <td class="is-num">{formatNumber(b.plays)}</td>
              {kinds.map((k) => <td key={k} class="is-num">{b.kinds[k] > 0 ? hoursText(b.kinds[k], 1) : '–'}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/* ── How it played ── */

function Stacked({ parts, label }: { parts: { value: number; colour: string }[]; label: string }) {
  const shown = parts.filter((p) => p.value > 0);
  return (
    <div role="img" aria-label={label} class="st-stack">
      {shown.map((p, i) => (
        <span key={i} style={{ flex: `${p.value} 1 0`, background: p.colour, borderRadius: shown.length === 1 ? '4px' : i === 0 ? '4px 0 0 4px' : i === shown.length - 1 ? '0 4px 4px 0' : '0' }} />
      ))}
    </div>
  );
}

function ShareRow({ colour, name, detail, pct, first }: { colour: string; name: string; detail: string; pct: number; first?: boolean }) {
  return (
    <div class={'st-share' + (first ? ' is-first' : '')}>
      <span class="st-sq is-10" style={{ background: colour }} />
      <span class="st-share-name">{name}</span>
      <span class="st-share-detail">{detail}</span>
      <span class="st-share-pct">{pctText(pct)}</span>
    </div>
  );
}

function reasonText(r: Stats['reasons'][number]): { name: string; note?: string } {
  const devices = r.devices.length ? listText(r.devices) : undefined;
  const codec = codecName(r.codec);
  switch (r.key) {
    case 'video':
      return { name: t('Video codec not supported'), note: codec && devices ? t('Mostly {codec} on {devices}', { codec, devices }) : devices ? t('Mostly on {devices}', { devices }) : undefined };
    case 'bitrate':
      return { name: t('Over the bitrate limit'), note: r.capMbps ? t('Remote streams capped at {n} Mbps', { n: formatNumber(r.capMbps) }) : devices ? t('Mostly on {devices}', { devices }) : undefined };
    case 'subtitles':
      return { name: t('Subtitles burned in'), note: codec ? t('{codec} subtitles on devices that can’t draw them', { codec }) : t('On devices that can’t draw the subtitles') };
    case 'audio':
      return { name: t('Audio codec not supported'), note: devices && t('Mostly on {devices}', { devices }) };
    case 'container':
      return { name: t('Container not supported'), note: devices && t('Mostly on {devices}', { devices }) };
    default:
      return { name: t('Other reasons'), note: devices && t('Mostly on {devices}', { devices }) };
  }
}

function HowItPlayed({ data }: { data: Stats }) {
  const m = data.methods;
  const total = m.directPlay + m.directStream + m.transcode;
  const rows = ([['DirectPlay', m.directPlay], ['DirectStream', m.directStream], ['Transcode', m.transcode]] as const);
  const reasons = data.reasons.filter((r) => r.plays > 0);
  const reasonTotal = reasons.reduce((s, r) => s + r.plays, 0);
  return (
    <Panel id="st-hp" title={t('How it played')} aside={<Link to="/admin/playback" class="st-head-link">{t('Playback settings')}</Link>}>
      {total === 0 ? (
        <p class="st-empty">{t('Not recorded for these plays. Seedy notes how each play streams from now on.')}</p>
      ) : (
        <>
          <Stacked
            label={rows.map(([k, v]) => `${methodLabel(k)} ${pctText((100 * v) / total)}`).join(', ')}
            parts={rows.map(([k, v]) => ({ value: v, colour: METHOD_COLOUR[k] }))}
          />
          <div class="st-shares">
            {rows.map(([k, v], i) => (
              <ShareRow key={k} first={i === 0} colour={METHOD_COLOUR[k]} name={methodLabel(k)} detail={tn(v, '{n} play', '{n} plays')} pct={(100 * v) / total} />
            ))}
          </div>
          {reasonTotal > 0 && (
            <>
              <span class="st-sub-h">{t('Why it transcoded')}</span>
              <div class="st-reasons">
                {reasons.map((r) => {
                  const { name, note } = reasonText(r);
                  const pct = (100 * r.plays) / reasonTotal;
                  return (
                    <div key={r.key} class="st-reason">
                      <span class="st-meter-head"><span>{name}</span><b>{pctText(pct)}</b></span>
                      <span aria-hidden="true" class="st-meter"><span style={{ width: `${pct}%` }} /></span>
                      {note && <span class="st-note">{note}</span>}
                    </div>
                  );
                })}
              </div>
            </>
          )}
        </>
      )}
    </Panel>
  );
}

/* ── What people play ── */

function WhatPeoplePlay({ data }: { data: Stats }) {
  const total = KINDS.reduce((s, k) => s + data.kinds[k], 0);
  const kinds = KINDS.filter((k) => data.kinds[k] > 0).sort((a, b) => data.kinds[b] - data.kinds[a]);
  return (
    <Panel id="st-wp" title={t('What people play')} aside={<span class="st-head-note">{t('Share of watch time')}</span>}>
      {total === 0 ? <p class="st-empty">{t('Nothing played in this period.')}</p> : (
        <>
          <Stacked label={kinds.map((k) => `${kindLabel(k)} ${pctText((100 * data.kinds[k]) / total)}`).join(', ')} parts={kinds.map((k) => ({ value: data.kinds[k], colour: KIND_COLOUR[k] }))} />
          <div class="st-shares">
            {kinds.map((k, i) => <ShareRow key={k} first={i === 0} colour={KIND_COLOUR[k]} name={kindLabel(k)} detail={hoursText(data.kinds[k])} pct={(100 * data.kinds[k]) / total} />)}
          </div>
        </>
      )}
    </Panel>
  );
}

/* ── When people watch ── */

const DAY_NAMES = () => [0, 1, 2, 3, 4, 5, 6].map((i) => new Date(2024, 0, 1 + i)); // 1 January 2024 was a Monday.

function busiest(heat: number[][]): { days: number[]; from: number } | null {
  let best = { sum: 0, day: -1, from: 0 };
  const windowSum = (d: number, h: number) => heat[d][h] + heat[d][h + 1] + heat[d][h + 2];
  for (let d = 0; d < 7; d++) for (let h = 0; h <= 21; h++) {
    const s = windowSum(d, h);
    if (s > best.sum) best = { sum: s, day: d, from: h };
  }
  if (best.day < 0) return null;
  const days = [0, 1, 2, 3, 4, 5, 6].filter((d) => windowSum(d, best.from) >= best.sum * 0.85);
  return { days, from: best.from };
}

function WhenPeopleWatch({ data }: { data: Stats }) {
  const [cell, setCell] = useState<{ d: number; h: number } | null>(null);
  const max = Math.max(...data.heat.flat(), 0);
  const names = DAY_NAMES();
  const short = names.map((d) => capitalize(fmtIntl(d, { weekday: 'short' }).replace('.', '')));
  const hh = (h: number) => `${String(h % 24).padStart(2, '0')}:00`;
  const b = busiest(data.heat);
  const busy = b ? t('Busiest: {days}, {from} to {to}', { days: capitalize(listText(b.days.map((d) => fmtIntl(names[d], { weekday: 'long' })))), from: hh(b.from), to: hh(b.from + 3) }) : t('Nothing played in this period.');

  // The most recent such weekday in the period, opened at that hour.
  const openCell = (d: number, h: number) => {
    const last = parseDay(data.to);
    const back = (((last.getDay() + 6) % 7) - d + 7) % 7;
    last.setDate(last.getDate() - back);
    if (dayKey(last) < data.from) return;
    navigate(`/admin/statistics/${dayKey(last)}?from=${hh(h)}&to=${hh(h + 1) === '00:00' ? '24:00' : hh(h + 1)}&step=7`);
  };
  const tip = cell && (
    <div role="tooltip" class="st-heat-tip" style={{
      ...(cell.h >= 12 ? { right: `${((23 - cell.h) / 24) * 100}%` } : { left: `calc(34px + ${(cell.h / 24) * 100}%)` }),
      ...(cell.d <= 3 ? { top: `${(cell.d + 1) * 26 + 4}px` } : { bottom: `${(7 - cell.d) * 26 + 22}px` })
    }}>
      <b>{t('{day}s, {time}', { day: fmtIntl(names[cell.d], { weekday: 'long' }), time: hh(cell.h) })}</b>
      <span>{t('{h} watched on average', { h: hoursText(data.heat[cell.d][cell.h], 1) })} · {tn(data.heatViewers[cell.d][cell.h], '{n} viewer', '{n} viewers')}</span>
      <span class="st-tip-more">{t('Click to see each {day}', { day: fmtIntl(names[cell.d], { weekday: 'long' }) })}<svg viewBox="0 0 24 24" width="13" height="13" aria-hidden="true"><path d="M9 6l6 6-6 6" /></svg></span>
    </div>
  );
  return (
    <Panel id="st-ww" title={t('When people watch')} aside={<span class="st-head-note">{t('Average hours, local time')}</span>}>
      <div class="st-heat-box" onMouseLeave={() => setCell(null)}>
        <div role="img" aria-label={busy} class="st-heat">
          {data.heat.map((row, d) => [
            <span key={'d' + d} class="st-heat-day">{short[d]}</span>,
            ...row.map((v, h) => (
              <span
                key={d + '-' + h}
                class={'st-heat-cell' + (cell && cell.d === d && cell.h === h ? ' is-on' : '')}
                style={{ background: `color-mix(in srgb, var(--seedy-accent) ${Math.round(Math.max(0.06, max > 0 ? v / max : 0) * 100)}%, transparent)` }}
                onMouseEnter={() => setCell({ d, h })}
                onClick={() => openCell(d, h)}
              />
            ))
          ])}
          <span />
          {Array.from({ length: 24 }, (_, h) => <span key={'h' + h} class="st-heat-hour">{h % 6 === 0 ? String(h).padStart(2, '0') : ''}</span>)}
        </div>
        {tip}
      </div>
      <div class="st-heat-foot">
        <span>{busy}</span>
        <span class="st-legend">{t('Less')}{[0.06, 0.3, 0.55, 0.8, 1].map((a) => <span key={a} style={{ background: `color-mix(in srgb, var(--seedy-accent) ${a * 100}%, transparent)` }} />)}{t('More')}</span>
      </div>
    </Panel>
  );
}

/* ── Viewers ── */

const VIEWERS_SHOWN = 8;

function Viewers({ data, filters }: { data: Stats; filters: StatsFilters }) {
  const [all, setAll] = useState(false);
  const max = Math.max(...data.viewers.map((v) => v.watchMs), 1);
  const list = all ? data.viewers : data.viewers.slice(0, VIEWERS_SHOWN);
  const period = data.range === '7d' ? t('No plays in 7 days') : data.range === '30d' ? t('No plays in 30 days') : data.range === '12m' ? t('No plays in 12 months') : t('No plays yet');
  return (
    <Panel id="st-vw" title={t('Viewers')} aside={<Link to="/admin/users" class="st-head-link">{t('Users')}</Link>}>
      <div class="st-viewers">
        {list.map((v, i) => (
          <Link key={v.userId} to={statsLink(filters, { userId: filters.userId === v.userId ? undefined : v.userId })} class={'st-viewer' + (i === 0 ? ' is-first' : '')}>
            <Avatar userId={v.userId} name={v.name} imageTag={v.imageTag} index={v.index} size={34} grey={v.plays === 0} />
            <span class="st-viewer-body">
              <span class="st-meter-head is-14"><span class="st-viewer-name">{v.name}</span><b>{hoursText(v.watchMs)}</b></span>
              <span aria-hidden="true" class="st-meter is-accent"><span style={{ width: `${(100 * v.watchMs) / max}%` }} /></span>
              <span class="st-note">
                {v.plays === 0 ? period
                  : v.device ? `${tn(v.plays, '{n} play', '{n} plays')} · ${v.deviceCount > 1 ? t('mostly {device}', { device: v.device }) : v.device}`
                    : tn(v.plays, '{n} play', '{n} plays')}
              </span>
            </span>
          </Link>
        ))}
      </div>
      {data.viewers.length > VIEWERS_SHOWN && (
        <button type="button" class="st-more" aria-expanded={all} onClick={() => setAll((v) => !v)}>
          {all ? t('Show fewer') : tn(data.viewers.length, 'Show the {n} profile', 'Show all {n} profiles')}
        </button>
      )}
    </Panel>
  );
}

/* ── Devices and apps ── */

function DevicesPanel({ data }: { data: Stats }) {
  const total = data.devices.reduce((s, d) => s + d.watchMs, 0);
  const max = Math.max(...data.devices.map((d) => d.watchMs), 1);
  return (
    <Panel id="st-dv" title={t('Devices and apps')} aside={<Link to="/admin/devices" class="st-head-link">{t('Devices')}</Link>} class="is-fill">
      {total === 0 ? <p class="st-empty">{t('Nothing played in this period.')}</p> : (
        <>
          <div class="st-devices">
            {data.devices.filter((d) => d.watchMs / total >= 0.005).slice(0, 6).map((d) => (
              <div key={d.key} class="st-device">
                <span class="st-meter-head is-14">
                  <span><b>{d.key.startsWith('app:') ? d.name : t(d.name)}</b>{d.apps.length > 0 && <span class="st-device-apps"> · {listText(d.apps)}</span>}</span>
                  <b>{pctText((100 * d.watchMs) / total)}</b>
                </span>
                <span aria-hidden="true" class="st-meter is-8"><span style={{ width: `${(100 * d.watchMs) / max}%` }} /></span>
              </div>
            ))}
          </div>
          <span class="st-note">{t('Share of watch time. Bars are scaled to the biggest.')}</span>
        </>
      )}
    </Panel>
  );
}

/* ── Most watched ── */

function TopArt({ item, kind }: { item?: BaseItem | null; kind: string }) {
  const art = item ? cardArt(item, 160) : undefined;
  return (
    <span aria-hidden="true" class={'st-top-art is-' + kind}>
      {art?.src && <img src={art.src} alt="" loading="lazy" />}
    </span>
  );
}

function topNote(x: Stats['top'][number]): string {
  switch (x.kind) {
    case 'show': return t('Show · {episodes}', { episodes: tn(x.items, '{n} episode', '{n} episodes') });
    case 'movie': return x.item?.ProductionYear ? t('Movie · {year}', { year: x.item.ProductionYear }) : t('Movie');
    case 'music': return t('Album · {songs}', { songs: songs(x.items) });
    case 'audiobook': return t('Audiobook');
    case 'book': return t('Book');
    case 'livetv': return t('Channel');
    default: return t('Video');
  }
}

function MostWatched({ data }: { data: Stats }) {
  return (
    <Panel id="st-mw" title={t('Most watched')} aside={<Link to="/admin/playlog" class="st-head-link">{t('Play log')}</Link>} class="is-fill">
      {data.top.length === 0 ? <p class="st-empty">{t('Nothing played in this period.')}</p> : (
        <ol class="st-top">
          {data.top.map((x, i) => {
            const timed = x.kind === 'audiobook' || x.kind === 'book' || x.kind === 'livetv';
            const listeners = x.kind === 'music' || x.kind === 'audiobook';
            const name = x.kind === 'music' && x.item?.AlbumArtist ? `${x.name} · ${x.item.AlbumArtist}` : x.name;
            return (
              <li key={x.titleId} class={i === 0 ? 'is-first' : undefined}>
                <span class="st-top-rank">{i + 1}</span>
                <TopArt item={x.item} kind={x.kind} />
                <span class="st-top-text">
                  {x.item && x.kind !== 'livetv' ? <Link to={`/details/${x.titleId}`} class="st-top-name">{name}</Link> : <span class="st-top-name">{name}</span>}
                  <span class="st-note">{topNote(x)}</span>
                </span>
                <span class="st-top-nums">
                  <b>{timed ? durationText(x.watchMs) : tn(x.plays, '{n} play', '{n} plays')}</b>
                  <span class="st-note">{listeners ? tn(x.viewers, '{n} listener', '{n} listeners') : x.kind === 'book' ? tn(x.viewers, '{n} reader', '{n} readers') : tn(x.viewers, '{n} viewer', '{n} viewers')}</span>
                </span>
              </li>
            );
          })}
        </ol>
      )}
    </Panel>
  );
}

/* ── Loading ── */

function Skeleton() {
  return (
    <div class="st-body" aria-hidden="true">
      <div class="st-kpis">{[0, 1, 2, 3, 4].map((i) => <div key={i} class="st-kpi"><span class="skel skel-line" style={{ width: '60%' }} /><span class="skel skel-line" style={{ width: '40%', height: '28px' }} /><span class="skel skel-line" style={{ width: '80%' }} /></div>)}</div>
      <section class="st-panel"><span class="skel skel-line" style={{ width: '200px', height: '18px' }} /><span class="skel" style={{ height: '220px', borderRadius: '12px' }} /></section>
    </div>
  );
}
