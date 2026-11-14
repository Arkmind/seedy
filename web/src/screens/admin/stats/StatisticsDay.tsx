import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { clock, episodeTag } from '../../../lib/format';
import { formatNumber, t, tn } from '../../../lib/i18n';
import { Link, navigate, setQuery, useLocation } from '../../../lib/router';
import { BackBar } from '../../../ui/BackBar';
import { AdminLayout, LoadError } from '../AdminLayout';
import { getStatsDay, type Kind, type StatsDay, type StatsSession } from './api';
import {
  Avatar, Kpi, KIND_COLOUR, KINDS, MethodBadge, Trend, dayKey, fmtDate, hoursValue, kindLabel, listText, longDay,
  mbps, parseDay, shortDuration, songs
} from './parts';
import './stats.css';

const MIN = 60_000;
const SLOT = 10; // minutes per "streams at once" bar

/* "21:00" → 1260; "24:00" → 1440. */
function parseTime(s: string | null): number | null {
  const m = s?.match(/^(\d{1,2}):(\d{2})$/);
  if (!m) return null;
  const v = Number(m[1]) * 60 + Number(m[2]);
  return v >= 0 && v <= 1440 ? v : null;
}

const hhmm = (min: number) => `${String(Math.floor(min / 60) % 24).padStart(2, '0')}:${String(Math.round(min) % 60).padStart(2, '0')}`;

interface Span { s: StatsSession; a: number; b: number } // minutes from the day's midnight

/* Streams open at a minute, and the highest count reached inside a window. */
function peakIn(spans: Span[], from: number, to: number): { streams: number; at: number; bitrate: number } | null {
  const events: [number, number, Span][] = [];
  for (const x of spans) {
    const a = Math.max(x.a, from);
    const b = Math.min(x.b, to);
    if (b <= a) continue;
    events.push([a, 1, x], [b, -1, x]);
  }
  events.sort((p, q) => p[0] - q[0] || p[1] - q[1]);
  let open = 0;
  let best = 0;
  let at = from;
  for (const [m, d] of events) {
    open += d;
    if (open > best) { best = open; at = m; }
  }
  if (best === 0) return null;
  const bitrate = spans.filter((x) => x.a <= at && x.b > at).reduce((sum, x) => sum + (x.s.bitrate ?? 0), 0);
  return { streams: best, at, bitrate };
}

function sessionTitle(s: StatsSession): string {
  if (s.kind === 'show') {
    const tag = episodeTag({ ParentIndexNumber: s.season ?? undefined, IndexNumber: s.episode ?? undefined });
    return tag ? `${s.title} ${tag}` : s.title;
  }
  if (s.kind === 'music' && s.count === 1) return s.itemName;
  return s.title || s.itemName;
}

function sessionNote(s: StatsSession): string {
  switch (s.kind) {
    case 'show': return s.itemName && s.itemName !== s.title ? t('Show · {episode}', { episode: s.itemName }) : t('Show');
    case 'movie': return s.year ? t('Movie · {year}', { year: s.year }) : t('Movie');
    case 'music': return s.count > 1 ? t('Music · {songs}', { songs: songs(s.count) }) : s.artist ? t('Song · {artist}', { artist: s.artist }) : t('Song');
    case 'book': return s.itemType === 'AudioBook' ? t('Audiobook') : t('Book');
    case 'livetv': return t('Live TV');
    default: return t('Video');
  }
}

/* Where the title links to: the episode or movie, the album of a listening session, the book. */
function sessionLink(s: StatsSession): string | undefined {
  if (s.kind === 'livetv') return undefined;
  if (s.kind === 'music' || s.itemType === 'AudioBook') return `/details/${s.titleId}`;
  return `/details/${s.itemId}`;
}

export function AdminStatisticsDay({ date }: { date: string }) {
  const loc = useLocation();
  const [data, setData] = useState<StatsDay | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [attempt, setAttempt] = useState(0);
  const valid = /^\d{4}-\d{2}-\d{2}$/.test(date);
  const day = parseDay(date);
  const step = loc.query.get('step') === '7' ? 7 : 1;
  const from = parseTime(loc.query.get('from'));
  const to = parseTime(loc.query.get('to'));
  const window = from !== null && to !== null && to > from ? { from, to } : null;

  useEffect(() => {
    if (!valid) return;
    const ctl = new AbortController();
    getStatsDay(date, ctl.signal).then(
      (d) => { setData(d); setError(null); },
      (e: Error) => { if (!ctl.signal.aborted) setError(e); }
    );
    return () => ctl.abort();
  }, [date, attempt]);

  const go = (offset: number) => {
    const d = parseDay(date);
    d.setDate(d.getDate() + offset);
    const q = new URLSearchParams(loc.query);
    navigate(`/admin/statistics/${dayKey(d)}${q.toString() ? '?' + q.toString() : ''}`, { keepScroll: true });
  };
  const prev = new Date(day); prev.setDate(day.getDate() - step);
  const next = new Date(day); next.setDate(day.getDate() + step);
  const nextDisabled = !data || dayKey(next) > data.today;
  const shortName = (d: Date) => fmtDate(d, { weekday: 'long', day: 'numeric' }).replace(/^./, (c) => c.toUpperCase());
  const current = data && data.date === date ? data : null;

  return (
    <AdminLayout
      page="statistics"
      wide
      overline={<BackBar class="st-crumbs" crumbs={[{ label: t('Statistics'), to: '/admin/statistics' }, { label: longDay(day, true) }]} />}
      title={longDay(day)}
      lede={current ? <DaySummary data={current} /> : undefined}
      actions={
        <div class="st-daynav">
          <button type="button" class="st-btn is-40" aria-label={t('Previous day, {date}', { date: longDay(prev) })} onClick={() => go(-step)}>
            <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><path d="M15 5l-7 7 7 7" /></svg>{shortName(prev)}
          </button>
          <button type="button" class="st-btn is-40" aria-label={t('Next day, {date}', { date: longDay(next) })} onClick={() => go(step)} disabled={nextDisabled}>
            {shortName(next)}<svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true"><path d="M9 6l6 6-6 6" /></svg>
          </button>
        </div>
      }
    >
      {!valid ? (
        <LoadError error={new Error(t('This isn’t a date.'))} onRetry={() => navigate('/admin/statistics')} what="this day" />
      ) : !current && error ? (
        <LoadError error={error} onRetry={() => setAttempt((n) => n + 1)} what="this day" />
      ) : !current ? (
        <div class="st-body" aria-hidden="true">
          <div class="st-kpis is-4">{[0, 1, 2, 3].map((i) => <div key={i} class="st-kpi"><span class="skel skel-line" style={{ width: '60%' }} /><span class="skel skel-line" style={{ width: '40%', height: '28px' }} /><span class="skel skel-line" style={{ width: '80%' }} /></div>)}</div>
          <section class="st-panel"><span class="skel" style={{ height: '260px', borderRadius: '12px' }} /></section>
        </div>
      ) : (
        <DayBody data={current} window={window} />
      )}
    </AdminLayout>
  );
}

function useSpans(data: StatsDay): Span[] {
  return useMemo(() => {
    const start = new Date(data.start).getTime();
    return data.sessions.map((s) => ({ s, a: (new Date(s.start).getTime() - start) / MIN, b: Math.max((new Date(s.end).getTime() - start) / MIN, (new Date(s.start).getTime() - start) / MIN + 1) }));
  }, [data]);
}

function DaySummary({ data }: { data: StatsDay }) {
  const watch = data.sessions.reduce((sum, s) => sum + s.watchMs, 0);
  const people = new Set(data.sessions.map((s) => s.userId));
  const enabled = data.users.filter((u) => u.enabled);
  const missing = enabled.filter((u) => !people.has(u.userId)).map((u) => u.name);
  const rankText = watch === 0 ? t('Nothing was played.')
    : data.rank === 1 ? t('The busiest day of the last {n}.', { n: data.daysRanked })
      : data.rank <= 3 ? t(data.rank === 2 ? 'The second busiest day of the last {n}.' : 'The third busiest day of the last {n}.', { n: data.daysRanked })
        : watch >= data.avgDayMs ? t('Busier than an average day.') : t('Quieter than an average day.');
  const whoText = watch === 0 ? ''
    : missing.length === 0 ? t('Everyone watched something.')
      : people.size === 1 ? t('Only {name} watched.', { name: data.users.find((u) => people.has(u.userId))?.name ?? '' })
        : missing.length <= 2 ? t('Everyone but {names} watched something.', { names: listText(missing) })
          : t('{n} of {total} people watched.', { n: people.size, total: enabled.length });
  return <>{rankText} {whoText}</>;
}

function DayBody({ data, window }: { data: StatsDay; window: { from: number; to: number } | null }) {
  const spans = useSpans(data);
  const watch = data.sessions.reduce((sum, s) => sum + s.watchMs, 0);
  const plays = data.sessions.reduce((sum, s) => sum + s.count, 0);
  const people = data.users.filter((u) => data.sessions.some((s) => s.userId === u.userId));
  const enabled = data.users.filter((u) => u.enabled).length;
  const peak = peakIn(spans, 0, 1440 * 2);
  const startMs = new Date(data.start).getTime();

  // What most of the plays were.
  const byKind = KINDS.map((k) => ({ k, n: data.sessions.filter((s) => s.kind === k).reduce((sum, s) => sum + s.count, 0) }));
  const songsPlayed = byKind.find((x) => x.k === 'music')!.n;
  const lead = songsPlayed > 0 ? byKind.find((x) => x.k === 'music')! : byKind.reduce((a, b) => (b.n > a.n ? b : a));
  const ofThem: Record<Kind, [string, string]> = {
    music: ['{n} of them a song', '{n} of them songs'],
    show: ['{n} of them an episode', '{n} of them episodes'],
    movie: ['{n} of them a movie', '{n} of them movies'],
    book: ['{n} of them a book', '{n} of them books'],
    livetv: ['{n} of them on Live TV', '{n} of them on Live TV'],
    other: ['{n} of them a video', '{n} of them videos']
  };
  const allOfThem: Record<Kind, string> = {
    music: 'All of them songs', show: 'All of them episodes', movie: 'All of them movies', book: 'All of them books',
    livetv: 'All of them on Live TV', other: 'All of them videos'
  };

  const avgNote = data.avgDayMs > 0 && watch > 0 ? (() => {
    const pct = Math.round(((watch - data.avgDayMs) / data.avgDayMs) * 100);
    if (pct === 0) return <Trend>{t('Like an average day')}</Trend>;
    return <Trend dir={pct > 0 ? 'up' : 'down'}>{t(pct > 0 ? '{n}% more than an average day' : '{n}% less than an average day', { n: Math.abs(pct) })}</Trend>;
  })() : <Trend>{watch > 0 ? t('Nothing the days before') : t('Nothing played')}</Trend>;

  const names = people.map((u) => u.name);
  const peopleNote = names.length === 0 ? t('Nobody watched')
    : names.length <= 4 ? listText(names) : t('{names} and {n} more', { names: names.slice(0, 2).join(', '), n: names.length - 2 });

  return (
    <div class="st-body">
      <div class="st-kpis is-4">
        <Kpi label={t('Watch time')} value={hoursValue(watch, 1)} unit={t('h')} note={avgNote} />
        <Kpi label={t('Plays')} value={formatNumber(plays)} note={plays > 0 && lead.n > 0 ? <Trend dir="up">{lead.n === plays && plays > 1 ? t(allOfThem[lead.k]) : tn(lead.n, ofThem[lead.k][0], ofThem[lead.k][1])}</Trend> : <Trend>{t('Nothing played')}</Trend>} />
        <Kpi label={t('Viewers')} value={formatNumber(people.length)} unit={t('of {n}', { n: formatNumber(enabled) })} note={<Trend dir={people.length > 0 ? 'up' : undefined}>{peopleNote}</Trend>} />
        <Kpi
          label={t('Most at once')}
          value={formatNumber(peak?.streams ?? 0)}
          unit={tn(peak?.streams ?? 0, 'stream', 'streams')}
          note={peak ? <Trend dir="up">{peak.bitrate > 0 ? t('{time} · {n} Mbps out', { time: clock(new Date(startMs + peak.at * MIN)), n: mbps(peak.bitrate) }) : clock(new Date(startMs + peak.at * MIN))}</Trend> : <Trend>{t('Nothing played')}</Trend>}
        />
      </div>
      <Timeline data={data} spans={spans} window={window} peak={peak} />
      {data.sessions.length > 0 && <PlaysTable data={data} spans={spans} window={window} />}
    </div>
  );
}

/* ── Who watched when ── */

function Timeline({ data, spans, window, peak }: { data: StatsDay; spans: Span[]; window: { from: number; to: number } | null; peak: ReturnType<typeof peakIn> }) {
  const startMs = new Date(data.start).getTime();
  const first = spans.length ? Math.min(...spans.map((x) => x.a)) : 360;
  const axisFrom = Math.max(0, Math.min(360, Math.floor(first / 180) * 180));
  const axisTo = 1440;
  const span = axisTo - axisFrom;
  const pos = (m: number) => Math.min(1, Math.max(0, (m - axisFrom) / span));
  const lanes = data.users.filter((u) => spans.some((x) => x.s.userId === u.userId));
  const kinds = KINDS.filter((k) => k !== 'other' || spans.some((x) => x.s.kind === 'other'));
  const inWindow = (a: number, b: number) => !window || (b > window.from && a < window.to);

  // Streams at once, one bar per 10 minutes: the most open at any minute inside it.
  const slots = useMemo(() => {
    const out: number[] = [];
    for (let m = axisFrom; m < axisTo; m += SLOT) {
      let best = 0;
      for (let k = m; k < m + SLOT; k++) {
        let open = 0;
        for (const x of spans) if (x.a <= k + 0.5 && x.b > k + 0.5) open++;
        if (open > best) best = open;
      }
      out.push(best);
    }
    return out;
  }, [spans, axisFrom]);
  const top = Math.max(...slots, 1);
  const unit = Math.min(9, 52 / top);
  const peakSlot = peak ? Math.floor((peak.at - axisFrom) / SLOT) : -1;

  // Click a time for its hour, or drag across the timeline for any stretch (in 15-minute steps).
  const area = useRef<HTMLDivElement>(null);
  const [drag, setDrag] = useState<{ a: number; b: number; x: number; moved: boolean } | null>(null);
  // The tracks start after the 110 px column of names.
  const minuteAt = (clientX: number) => {
    const r = area.current!.getBoundingClientRect();
    return axisFrom + Math.min(1, Math.max(0, (clientX - r.left - 110) / (r.width - 110))) * span;
  };
  const snap = (m: number) => Math.round(m / 15) * 15;
  const pick = (a: number, b: number) => setQuery({ from: hhmm(a), to: b >= 1440 ? '24:00' : hhmm(b) });
  const onDown = (e: PointerEvent) => {
    if (e.button !== 0 || !area.current || e.clientX < area.current.getBoundingClientRect().left + 110) return;
    e.preventDefault();
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    const m = minuteAt(e.clientX);
    setDrag({ a: m, b: m, x: e.clientX, moved: false });
  };
  const onMove = (e: PointerEvent) => {
    if (!drag) return;
    setDrag({ ...drag, b: minuteAt(e.clientX), moved: drag.moved || Math.abs(e.clientX - drag.x) > 4 });
  };
  const onUp = () => {
    if (!drag) return;
    if (!drag.moved) {
      const h = Math.floor(drag.a / 60) * 60;
      pick(h, h + 60);
    } else {
      const a = snap(Math.min(drag.a, drag.b));
      const b = Math.max(a + 15, snap(Math.max(drag.a, drag.b)));
      pick(a, Math.min(1440, b));
    }
    setDrag(null);
  };
  const shown = drag?.moved ? { from: snap(Math.min(drag.a, drag.b)), to: Math.max(snap(Math.min(drag.a, drag.b)) + 15, snap(Math.max(drag.a, drag.b))) } : window;
  const ticks: number[] = [];
  for (let m = Math.ceil(axisFrom / 180) * 180; m <= axisTo; m += 180) ticks.push(m);

  return (
    <section aria-labelledby="st-tl" class="st-panel">
      <div class="st-panel-head">
        <h2 id="st-tl">{t('Who watched when')}</h2>
        <span class="st-kind-key">
          {kinds.map((k) => <span key={k}><span class="st-sq is-10" style={{ background: KIND_COLOUR[k] }} />{kindLabel(k)}</span>)}
        </span>
      </div>
      {spans.length === 0 ? <p class="st-empty">{t('Nothing was played on this day.')}</p> : (
        <>
          <div class="st-hours">
            <span class="st-hours-label">{t('Hour')}</span>
            <button type="button" class="st-chip" aria-pressed={!window} onClick={() => setQuery({ from: null, to: null })}>{t('Whole day')}</button>
            {window && (
              <span class="st-chip is-on">
                {hhmm(window.from)} – {window.to >= 1440 ? '00:00' : hhmm(window.to)}
                <button type="button" aria-label={t('Clear the hour')} onClick={() => setQuery({ from: null, to: null })}>
                  <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M6 6l12 12" /><path d="M18 6L6 18" /></svg>
                </button>
              </span>
            )}
            <span class="st-note">{t('Click or drag on the timeline to pick a time. Hover a bar for its details.')}</span>
          </div>
          <div class="st-tl">
            {shown && (
              <div aria-hidden="true" class="st-tl-window" style={{ left: `calc(110px + (100% - 110px) * ${pos(shown.from)})`, width: `calc((100% - 110px) * ${pos(shown.to) - pos(shown.from)})` }} />
            )}
            <div
              ref={area}
              onPointerDown={onDown}
              onPointerMove={onMove}
              onPointerUp={onUp}
              onPointerCancel={() => setDrag(null)}
              role="img"
              aria-label={t('Plays by viewer from {from} to midnight on {date}.', { from: hhmm(axisFrom), date: longDay(parseDay(data.date)) })
                + (peak ? ' ' + tn(peak.streams, '{n} stream at once at {time}.', '{n} streams at once at {time}.', { time: clock(new Date(startMs + peak.at * MIN)) }) : '')}
              class="st-tl-rows"
            >
              {lanes.map((u) => (
                <div key={u.userId} class="st-tl-lane">
                  <span class="st-tl-who"><Avatar userId={u.userId} name={u.name} imageTag={u.imageTag} index={u.index} size={26} />{u.name}</span>
                  <div class="st-tl-track">
                    {spans.filter((x) => x.s.userId === u.userId && x.b > axisFrom).map((x) => {
                      const l = pos(x.a);
                      const w = Math.max(pos(x.b) - l, 0.004);
                      const title = sessionTitle(x.s);
                      return (
                        <span
                          key={x.s.id}
                          title={`${title} · ${clock(new Date(x.s.start))}–${clock(new Date(x.s.end))}`}
                          class={'st-tl-block' + (w < 0.03 ? ' is-tiny' : '')}
                          style={{ left: `${l * 100}%`, width: `calc(${w * 100}% - 2px)`, background: KIND_COLOUR[x.s.kind], opacity: inWindow(x.a, x.b) ? 1 : 0.45 }}
                        >
                          <span>{title}</span>
                        </span>
                      );
                    })}
                  </div>
                </div>
              ))}
              <div class="st-tl-lane is-streams">
                <span class="st-tl-streams-label">{t('Streams')}<br />{t('at once')}</span>
                <div class="st-tl-bars">
                  {slots.map((n, i) => {
                    const m = axisFrom + i * SLOT;
                    const isPeak = i === peakSlot;
                    return (
                      <span
                        key={i}
                        title={`${hhmm(m)} · ${n}`}
                        style={{ height: `${Math.max(2, n * unit)}px`, background: isPeak ? 'var(--seedy-accent)' : '#8c8c8c', opacity: isPeak ? 1 : inWindow(m, m + SLOT) ? 0.7 : 0.35 }}
                      />
                    );
                  })}
                  {peak && peakSlot >= 0 && (
                    <span class="st-tl-peak" style={{ left: `${pos(peak.at) * 100}%` }}>{t('{n} at {time}', { n: peak.streams, time: clock(new Date(startMs + peak.at * MIN)) })}</span>
                  )}
                </div>
              </div>
            </div>
            <div class="st-tl-axis">
              <span />
              <div>{ticks.map((m) => <span key={m} style={{ left: `${pos(m) * 100}%` }}>{hhmm(m)}</span>)}</div>
            </div>
          </div>
        </>
      )}
    </section>
  );
}

/* ── Plays in that hour ── */

function PlaysTable({ data, spans, window }: { data: StatsDay; spans: Span[]; window: { from: number; to: number } | null }) {
  const startMs = new Date(data.start).getTime();
  const list = window ? spans.filter((x) => x.b > window.from && x.a < window.to) : spans;
  const all = spans.reduce((sum, x) => sum + x.s.count, 0);
  const count = list.reduce((sum, x) => sum + x.s.count, 0);
  const watched = list.reduce((sum, x) => {
    if (!window) return sum + x.s.watchMs;
    const overlap = Math.max(0, Math.min(x.b, window.to) - Math.max(x.a, window.from));
    return sum + x.s.watchMs * (overlap / Math.max(1, x.b - x.a));
  }, 0);
  const peak = window ? peakIn(spans, window.from, window.to) : peakIn(spans, 0, 2880);
  const hourEnd = window && (window.to >= 1440 ? '00:00' : hhmm(window.to));
  const title = window ? tn(count, '{n} play between {from} and {to}', '{n} plays between {from} and {to}', { from: hhmm(window.from), to: hourEnd! }) : tn(count, '{n} play', '{n} plays');
  const peakTime = peak ? clock(new Date(startMs + peak.at * MIN)) : '';
  const peakText = peak && (peak.bitrate > 0
    ? tn(peak.streams, 'peak of {n} stream at {time}, {mbps} Mbps going out', 'peak of {n} streams at {time}, {mbps} Mbps going out', { time: peakTime, mbps: mbps(peak.bitrate) })
    : tn(peak.streams, 'peak of {n} stream at {time}', 'peak of {n} streams at {time}', { time: peakTime }));
  const isHour = window && window.to - window.from === 60 && window.from % 60 === 0;
  const watchedText = t(isHour ? '{time} watched inside that hour' : window ? '{time} watched inside that time' : '{time} watched', { time: shortDuration(watched) });
  const users = new Map(data.users.map((u) => [u.userId, u]));

  return (
    <section aria-labelledby="st-pl" class="st-panel">
      <div class="st-panel-head">
        <h2 id="st-pl">{title}</h2>
        {window && <button type="button" class="st-head-link" onClick={() => setQuery({ from: null, to: null })}>{tn(all, 'Show the one play of the day', 'Show all {n} plays of the day')}</button>}
      </div>
      <span class="st-pl-sub">{watchedText}{peakText ? ` · ${peakText}` : ''}</span>
      <div class="st-scroll">
        <div role="table" aria-label={title} class="st-plays">
          <div role="row" class="st-plays-row is-head">
            <span role="columnheader">{t('Time')}</span>
            <span role="columnheader">{t('Viewer')}</span>
            <span role="columnheader">{t('What')}</span>
            <span role="columnheader">{t('Device')}</span>
            <span role="columnheader">{t('Watched')}</span>
            <span role="columnheader" class="is-right">{t('How')}</span>
          </div>
          {list.length === 0 && <div role="row" class="st-plays-row is-empty"><span role="cell">{t('Nothing played at that time.')}</span></div>}
          {list.map(({ s }) => {
            const u = users.get(s.userId);
            const link = sessionLink(s);
            const name = sessionTitle(s);
            const pct = s.finished ? 100 : s.percent ?? null;
            return (
              <div key={s.id} role="row" class="st-plays-row">
                <span role="cell" class="st-plays-time">{clock(new Date(s.start))}–{clock(new Date(s.end))}</span>
                <span role="cell" class="st-plays-who">{u && <Avatar userId={u.userId} name={u.name} imageTag={u.imageTag} index={u.index} size={24} />}{u?.name ?? '?'}</span>
                <span role="cell" class="st-plays-what">
                  <span aria-hidden="true" class="st-plays-bar" style={{ background: KIND_COLOUR[s.kind] }} />
                  <span class="st-plays-text">
                    {link ? <Link to={link} class="st-plays-title">{name}</Link> : <span class="st-plays-title">{name}</span>}
                    <span class="st-note">{sessionNote(s)}</span>
                  </span>
                </span>
                <span role="cell" class="st-plays-device">{s.device ?? '–'}</span>
                <span role="cell" class="st-plays-watched">
                  <span class="st-plays-watched-head">
                    <b>{shortDuration(s.watchMs)}</b>
                    <span class={s.finished || pct === null ? undefined : 'is-white'}>
                      {s.kind === 'livetv' ? t('Live') : s.count > 1 ? songs(s.count) : s.finished ? t('Finished') : pct !== null ? t('Stopped at {n}%', { n: Math.round(pct) }) : ''}
                    </span>
                  </span>
                  {pct !== null && s.kind !== 'livetv' && s.count === 1 && <span aria-hidden="true" class="st-plays-progress"><span style={{ width: `${pct}%` }} /></span>}
                </span>
                <span role="cell" class="st-plays-how"><MethodBadge method={s.method} /></span>
              </div>
            );
          })}
        </div>
      </div>
    </section>
  );
}
