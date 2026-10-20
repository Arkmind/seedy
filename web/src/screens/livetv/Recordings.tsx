import { useEffect, useMemo, useState } from 'preact/hooks';
import { cardArt, imageUrl } from '../../api/images';
import {
  cancelSeriesTimer, cancelTimer, deleteRecording, getRecordings, updateSeriesTimer, type Program, type SeriesTimer, type Timer
} from '../../api/livetv';
import { meStore } from '../../lib/app-state';
import { clock, fmtDayMonth, startOfDay } from '../../lib/format';
import { t, tn } from '../../lib/i18n';
import { navigate } from '../../lib/router';
import { safeLocal, saveLocal, useAsync, useStore } from '../../lib/store';
import { Switch } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { confirm, toast } from '../../ui/overlay';
import {
  categoryOf, channelColor, cleanName, endMs, fraction, loadTimers, programTitle, recordingNow, startMs, timeRange, tunersStore,
  loadTuners, useChannels, useNow, useTimers, weekdayShort, type LiveChannel
} from './data';
import { LiveLayout } from './LiveLayout';
import { Chip, ChannelLogo, LivePill, LtIcon, ProgramArt, StopIcon, hasProgramArt, watch } from './parts';

/* Live TV · Recordings (board "Live TV · Recordings"): a warning when more recordings overlap than the tuner can
   play at once, what's recording now, scheduled recordings by day, series rules (editable), and what's been
   recorded, with type filters. */

type Rec = Program & { Status?: string };
const DAY_CAP = 6;
const REC_CHANNELS = 'seedy.device.recordingChannels';

export function LiveRecordings() {
  const me = useStore(meStore);
  const { list } = useChannels();
  const timers = useTimers();
  const tuners = useStore(tunersStore);
  const now = useNow(15_000);
  useEffect(() => { loadTuners(); }, [me.isAdmin]);
  const recorded = useAsync(() => getRecordings().then((r) => (r.Items ?? []) as Rec[]), [timers.at]);
  const [kind, setKind] = useState<'all' | 'shows' | 'movies' | 'sports' | 'news' | 'kids'>('all');
  const [open, setOpen] = useState<string | null>(null);
  const [expanded, setExpanded] = useState<Set<number>>(new Set());

  const chanById = (id?: string) => (id ? list?.find((c) => c.Id === id) : undefined);
  const chanByName = (name?: string) => (name ? list?.find((c) => c.Name === name || c.label === cleanName(name)) : undefined);

  const all = timers.timers ?? [];
  const live = recordingNow(all);
  const scheduled = all.filter((x) => (x.Status === 'New' || x.Status === 'ConflictedOk' || x.Status === 'ConflictedNotOk' || !x.Status) && endMs(x) > now)
    .sort((a, b) => startMs(a) - startMs(b));

  /* Overlaps: more recordings at once than the tuner plays (admins can read the limit). */
  const limit = useMemo(() => {
    if (!tuners?.length) return 0;
    if (tuners.some((x) => !x.TunerCount)) return 0;
    return tuners.reduce((n, x) => n + x.TunerCount, 0);
  }, [tuners]);
  const conflicts = useMemo(() => findConflicts([...live, ...scheduled], limit), [all, limit]);
  const overlapIds = new Set(conflicts.flatMap((c) => c.ids));
  const tunerKind = tuners?.length === 1 ? tuners[0].Type : '';

  /* Group by day: "Tonight", "Tomorrow, Tue 20 Oct", "Wed 21 Oct". */
  const groups: { day: number; items: Timer[] }[] = [];
  for (const x of scheduled) {
    const d = startOfDay(new Date(startMs(x))).getTime();
    const g = groups.find((y) => y.day === d);
    if (g) g.items.push(x); else groups.push({ day: d, items: [x] });
  }
  const first = conflicts[0];

  /* A finished recording loses its channel; remember it from while it recorded (this device). */
  const recChannels = useMemo(() => {
    const known = safeLocal<Record<string, string>>(REC_CHANNELS, {});
    let changed = false;
    for (const r of recorded.data ?? []) {
      if (r.ChannelName && known[r.Id] !== r.ChannelName) { known[r.Id] = r.ChannelName; changed = true; }
    }
    if (changed) saveLocal(REC_CHANNELS, Object.fromEntries(Object.entries(known).slice(-300)));
    return known;
  }, [recorded.data]);
  const recItems = (recorded.data ?? []).filter((r) => r.Status !== 'InProgress');
  /* A finished recording becomes a library item (an Episode, or a "Movie" for a one-off): its genres tell the kind. */
  const genre = (r: Rec, ...names: string[]) => (r.Genres ?? []).some((g) => names.includes(g.toLowerCase()));
  const kinds: { id: typeof kind; label: string; test: (r: Rec) => boolean }[] = [
    { id: 'all', label: 'All', test: () => true },
    { id: 'shows', label: 'Shows', test: (r) => !!r.IsSeries || r.Type === 'Episode' },
    { id: 'movies', label: 'Movies', test: (r) => !!r.IsMovie || (r.Type === 'Movie' && !genre(r, 'news', 'sports', 'sport', 'kids', 'children') && genre(r, 'movie', 'film')) },
    { id: 'sports', label: 'Sports', test: (r) => !!r.IsSports || genre(r, 'sports', 'sport', 'football', 'basketball', 'baseball') },
    { id: 'news', label: 'News', test: (r) => (!!r.IsNews && !r.IsSeries) || (r.Type !== 'Episode' && genre(r, 'news')) },
    { id: 'kids', label: 'Kids', test: (r) => !!r.IsKids || genre(r, 'kids', 'children') }
  ];
  const shownKinds = kinds.filter((k) => ['all', 'shows', 'movies', 'sports'].includes(k.id) || recItems.some(k.test));
  const recShown = recItems.filter(kinds.find((k) => k.id === kind)!.test)
    .sort((a, b) => Date.parse(b.StartDate ?? b.DateCreated ?? '') - Date.parse(a.StartDate ?? a.DateCreated ?? ''));

  return (
    <LiveLayout tab="recordings">
      {first && (
        <div role="status" class="lt-warn">
          <span aria-hidden="true" class="lt-warn-icon"><LtIcon name="warn" size={20} /></span>
          <p class="lt-warn-text">
            <b>{overlapSentence(first, now)}</b>{' '}
            {tunerSentence(limit, first.count - limit, tunerKind)}
          </p>
          <a href={'#lt-day-' + startOfDay(new Date(first.start)).getTime()} class="lt-btn is-primary" onClick={(e) => {
            e.preventDefault();
            document.getElementById('lt-day-' + startOfDay(new Date(first.start)).getTime())?.scrollIntoView({ behavior: 'smooth', block: 'start' });
          }}>{t('Review')}</a>
        </div>
      )}

      <div class="lt-rec-cols">
        <div class="lt-rec-main">
          {live.length > 0 && (
            <section class="lt-panel" aria-labelledby="lt-now-h">
              <h2 id="lt-now-h" class="lt-panel-title is-rec"><span aria-hidden="true" class="lt-recnow-dot" />{t('Recording now')}</h2>
              {live.map((x) => <RecordingNow key={x.Id} timer={x} channel={chanById(x.ChannelId)} now={now} />)}
            </section>
          )}

          <section class="lt-panel is-sched" aria-labelledby="lt-sched-h">
            <div class="lt-panel-head">
              <h2 id="lt-sched-h" class="lt-panel-title">{t('Scheduled')}</h2>
              {timers.timers && <span class="lt-panel-count">{tn(scheduled.length, '{n} upcoming', '{n} upcoming')}</span>}
            </div>
            {!timers.timers && [0, 1, 2].map((i) => <div key={i} class="lt-sched-row skel" style={{ height: '60px' }} aria-hidden="true" />)}
            {timers.timers && !scheduled.length && (
              <p class="lt-panel-empty">{t('Nothing is set to record. Use Record on a programme in the guide, or Record series to follow a show.')}</p>
            )}
            {groups.map((g) => {
              const dayConflicts = conflicts.filter((c) => startOfDay(new Date(c.start)).getTime() === g.day);
              const worst = dayConflicts.reduce((m, c) => Math.max(m, c.count), 0);
              return (
                <div key={g.day} class="lt-sched-day">
                  <h3 id={'lt-day-' + g.day} class={'lt-day-h' + (worst ? ' is-warn' : '')}>
                    {dayHeading(g.day, now)}
                    {worst > 0 && ' · ' + t('{streams} streams, {count} recordings', { streams: limit, count: worst })}
                  </h3>
                  {(expanded.has(g.day) ? g.items : g.items.slice(0, DAY_CAP)).map((x) => (
                    <ScheduledRow key={x.Id} timer={x} channel={chanById(x.ChannelId)} overlap={overlapIds.has(x.Id)} />
                  ))}
                  {g.items.length > DAY_CAP && !expanded.has(g.day) && (
                    <button type="button" class="link-btn lt-sched-more" onClick={() => setExpanded((e) => new Set([...e, g.day]))}>
                      {tn(g.items.length - DAY_CAP, 'Show {n} more recording', 'Show {n} more recordings')}
                    </button>
                  )}
                </div>
              );
            })}
          </section>
        </div>

        <aside class="lt-panel lt-rules" aria-labelledby="lt-rules-h">
          <div class="lt-panel-head">
            <h2 id="lt-rules-h" class="lt-panel-title">{t('Series rules')}</h2>
            {timers.series && <span class="lt-panel-count">{timers.series.length}</span>}
          </div>
          {!timers.series && [0, 1].map((i) => <div key={i} class="lt-rule skel" style={{ height: '60px' }} aria-hidden="true" />)}
          {timers.series && !timers.series.length && (
            <p class="lt-panel-empty">{t('No series rules yet. Use Record series on a show in the guide and every new episode records.')}</p>
          )}
          {(timers.series ?? []).map((s) => (
            <SeriesRule key={s.Id} rule={s} channel={chanById(s.ChannelId)} open={open === s.Id} onToggle={() => setOpen(open === s.Id ? null : s.Id)} />
          ))}
        </aside>
      </div>

      <section class="lt-recorded" aria-labelledby="lt-rec-h">
        <div class="lt-recorded-head">
          <div class="lt-panel-head">
            <h2 id="lt-rec-h" class="lt-row-title">{t('Recorded')}</h2>
            {recorded.data && <span class="lt-panel-count">{tn(recItems.length, '{n} recording', '{n} recordings')}</span>}
          </div>
          <div role="group" aria-label={t('Show recordings')} class="lt-chips">
            {shownKinds.map((k) => <Chip key={k.id} label={t(k.label)} pressed={kind === k.id} onClick={() => setKind(k.id)} />)}
          </div>
        </div>
        {recorded.loading && !recorded.data ? (
          <div class="lt-recgrid" aria-hidden="true">{[0, 1, 2, 3, 4].map((i) => <div key={i} class="lt-card is-grid"><div class="lt-card-media skel" /></div>)}</div>
        ) : recorded.error ? (
          <div class="empty"><span class="empty-icon"><LtIcon name="warn" size={26} /></span><div class="empty-body"><span class="empty-title">{t("Couldn't load your recordings")}</span><span class="empty-text">{recorded.error.message}</span></div><button type="button" class="btn btn-secondary" onClick={recorded.reload}>{t('Try again')}</button></div>
        ) : recShown.length ? (
          <div class="lt-recgrid">
            {recShown.map((r) => <RecordedCard key={r.Id} item={r} channel={chanByName(r.ChannelName ?? recChannels[r.Id])} onDeleted={recorded.reload} />)}
          </div>
        ) : (
          <p class="lt-panel-empty">{kind === 'all' ? t('Nothing recorded yet. Finished recordings appear here.') : t('No recordings of this kind.')}</p>
        )}
      </section>
    </LiveLayout>
  );
}

/* ── Overlaps ── */

interface Conflict { start: number; end: number; count: number; ids: string[] }

function findConflicts(list: Timer[], limit: number): Conflict[] {
  if (!limit) return [];
  const span = (x: Timer) => [startMs(x) - (x.PrePaddingSeconds ?? 0) * 1000, endMs(x) + (x.PostPaddingSeconds ?? 0) * 1000] as const;
  const points = [...new Set(list.flatMap((x) => span(x)))].sort((a, b) => a - b);
  const out: Conflict[] = [];
  for (let i = 0; i < points.length - 1; i++) {
    const a = points[i];
    const b = points[i + 1];
    const on = list.filter((x) => { const [s, e] = span(x); return s < b && e > a; });
    if (on.length <= limit) continue;
    const last = out[out.length - 1];
    const ids = on.map((x) => x.Id);
    if (last && last.end === a && last.count === on.length && ids.every((id) => last.ids.includes(id))) last.end = b;
    else out.push({ start: a, end: b, count: on.length, ids });
  }
  return out;
}

const NUM_WORDS = ['', 'One', 'Two', 'Three', 'Four', 'Five', 'Six', 'Seven', 'Eight', 'Nine', 'Ten'];

/* "Three recordings overlap tomorrow from 21:40 to 22:00." */
function overlapSentence(c: Conflict, now: number): string {
  const count = c.count < NUM_WORDS.length ? t(NUM_WORDS[c.count]) : String(c.count);
  const days = Math.round((startOfDay(new Date(c.start)).getTime() - startOfDay(new Date(now)).getTime()) / 86_400_000);
  const vars = { count, start: clock(new Date(c.start)), end: clock(new Date(c.end)), day: `${weekdayShort(new Date(c.start))} ${fmtDayMonth(new Date(c.start))}` };
  if (days <= 0) return t('{count} recordings overlap tonight from {start} to {end}.', vars);
  if (days === 1) return t('{count} recordings overlap tomorrow from {start} to {end}.', vars);
  return t('{count} recordings overlap on {day} from {start} to {end}.', vars);
}

/* "The IPTV playlist plays 2 streams at once, so one of them won't record. Pick one to skip." */
function tunerSentence(limit: number, excess: number, type: string): string {
  const who = type === 'm3u' ? t('The IPTV playlist') : type === 'hdhomerun' ? t('Your HDHomeRun') : t('Your tuners');
  const plays = tn(limit, '{who} plays {n} stream at once', '{who} plays {n} streams at once', { who });
  return excess <= 1
    ? t("{plays}, so one of them won't record. Pick one to skip.", { plays })
    : t("{plays}, so {n} of them won't record. Pick {n} to skip.", { plays, n: excess });
}

function dayHeading(day: number, now: number): string {
  const d = new Date(day);
  const diff = Math.round((day - startOfDay(new Date(now)).getTime()) / 86_400_000);
  const date = `${weekdayShort(d)} ${fmtDayMonth(d)}`;
  if (diff <= 0) return t('Tonight');
  if (diff === 1) return t('Tomorrow, {date}', { date });
  return date;
}

/* ── Recording now ── */

function RecordingNow({ timer: x, channel, now }: { timer: Timer; channel?: LiveChannel; now: number }) {
  const p = x.ProgramInfo;
  const title = p ? programTitle(p) : x.Name ?? '';
  const total = Math.max(1, Math.round((endMs(x) - startMs(x)) / 60_000));
  const done = Math.max(0, Math.min(total, Math.round((now - startMs(x)) / 60_000)));
  const sub = [p?.EpisodeTitle && p.EpisodeTitle !== p.Name ? p.EpisodeTitle : '', channel?.label ?? x.ChannelName, timeRange(x), x.SeriesTimerId ? t('Series rule') : ''].filter(Boolean).join(' · ');
  const stop = async () => {
    const ok = await confirm({ title: t('Stop recording {title}?', { title: x.Name ?? '' }), text: t('What has been recorded so far is kept.'), action: t('Stop recording'), danger: true });
    if (!ok) return;
    try {
      await cancelTimer(x.Id);
      toast(t('Recording stopped'));
    } catch {
      toast(t("Couldn't stop the recording"), 'error');
    }
    void loadTimers(true);
  };
  return (
    <div class="lt-recnow">
      <div class="lt-recnow-art">
        <ProgramArt program={p} channel={channel} width={320} />
        <span class="lt-recnow-pill"><LivePill small label="REC" /></span>
        {hasProgramArt(p) && <ChannelLogo channel={channel} size={30} radius={8} class="lt-recnow-logo" />}
      </div>
      <div class="lt-recnow-info">
        <span class="lt-recnow-title">{title}</span>
        <span class="lt-recnow-sub">{sub}</span>
        <div class="lt-recnow-progress">
          <div class="lt-bar is-5 is-rec"><i style={{ width: (fraction(x as unknown as Program, now) * 100).toFixed(2) + '%' }} /></div>
          {t('{done} of {total} min', { done, total })}
        </div>
        <div class="lt-recnow-actions">
          <button type="button" class="lt-btn is-primary" onClick={() => watch(x.ChannelId)}><Icon name="playRound" size={18} />{t('Watch')}</button>
          <button type="button" class="lt-btn is-stop" onClick={() => void stop()}><StopIcon />{t('Stop recording')}</button>
        </div>
      </div>
    </div>
  );
}

/* ── Scheduled ── */

function ScheduledRow({ timer: x, channel, overlap }: { timer: Timer; channel?: LiveChannel; overlap: boolean }) {
  const p = x.ProgramInfo;
  const title = p ? programTitle(p) : x.Name ?? '';
  const extra = p ? (p.IsMovie ? t('Film') : p.IsLive ? t('Live') : p.IsSeries && !p.IsRepeat ? t('New') : categoryOf(p)) : '';
  const cancel = async () => {
    const ok = await confirm({
      title: t('Cancel the recording of {title}?', { title }),
      text: x.SeriesTimerId ? t('Only this episode is skipped; the series rule stays.') : t('It will no longer record.'),
      action: t('Cancel recording'),
      danger: true
    });
    if (!ok) return;
    try {
      await cancelTimer(x.Id);
      toast(t('Recording cancelled'));
    } catch {
      toast(t("Couldn't cancel the recording"), 'error');
    }
    void loadTimers(true);
  };
  return (
    <div class={'lt-sched-row' + (overlap ? ' is-overlap' : '')}>
      <span class="lt-sched-time"><b>{clock(new Date(startMs(x)))}</b><span>{t('to {time}', { time: clock(new Date(endMs(x))) })}</span></span>
      <ChannelLogo channel={channel ?? ({ Id: x.ChannelId, Name: x.ChannelName ?? '' } as LiveChannel)} size={36} radius={10} />
      <span class="lt-two lt-sched-text">
        <span class="lt-sched-title">{title}</span>
        <span class="lt-sched-sub">{[channel?.label ?? (x.ChannelName ? cleanName(x.ChannelName) : ''), extra].filter(Boolean).join(' · ')}</span>
      </span>
      {overlap && <span class="lt-overlap">{t('Overlaps')}</span>}
      <span class={'lt-kind' + (x.SeriesTimerId ? ' is-series' : '')}>{x.SeriesTimerId ? t('Series') : t('Once')}</span>
      <button type="button" class="lt-x" aria-label={t('Cancel recording of {title}', { title })} onClick={() => void cancel()}>
        <LtIcon name="close" size={17} stroke={2.4} />
      </button>
    </div>
  );
}

/* ── Series rules ── */

const KEEP = [0, 1, 2, 3, 5, 10, 20];

function ruleSummary(r: SeriesTimer, channel?: LiveChannel): string {
  return [
    r.RecordAnyChannel ? t('Any channel') : channel?.label ?? (r.ChannelName ? cleanName(r.ChannelName) : ''),
    r.RecordAnyTime ? (r.RecordNewOnly ? t('New episodes') : t('Every episode')) : t('Around {time}', { time: clock(new Date(startMs(r))) }),
    r.KeepUpTo > 0 ? t('Keep the last {n}', { n: r.KeepUpTo }) : t('Keep all')
  ].filter(Boolean).join(' · ');
}

function SeriesRule({ rule, channel, open, onToggle }: { rule: SeriesTimer; channel?: LiveChannel; open: boolean; onToggle: () => void }) {
  const [draft, setDraft] = useState<SeriesTimer>(rule);
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (!open) setDraft(rule); }, [open, rule]);
  const set = (patch: Partial<SeriesTimer>) => setDraft((d) => ({ ...d, ...patch }));
  const dirty = JSON.stringify(draft) !== JSON.stringify(rule);
  const name = rule.Name ?? '';
  const chName = channel?.label ?? (rule.ChannelName ? cleanName(rule.ChannelName) : t('This channel'));
  const thumbTag = rule.ParentThumbImageTag ?? rule.ParentPrimaryImageTag;
  const thumbId = rule.ParentThumbImageTag ? rule.ParentThumbItemId : rule.ParentPrimaryImageItemId;
  const thumb = thumbTag && thumbId ? imageUrl(thumbId, rule.ParentThumbImageTag ? 'Thumb' : 'Primary', { tag: thumbTag, maxWidth: 144 }) : undefined;
  const col = channelColor(channel?.label ?? rule.ChannelName ?? name);

  const save = async () => {
    setBusy(true);
    try {
      await updateSeriesTimer(draft);
      toast(t('Series rule saved'));
      await loadTimers(true);
      onToggle();
    } catch {
      toast(t("Couldn't save the series rule"), 'error');
    } finally {
      setBusy(false);
    }
  };
  const remove = async () => {
    const ok = await confirm({
      title: t('Delete the rule for {title}?', { title: name }),
      text: t('New episodes will no longer record. Recordings you already have are kept.'),
      action: t('Delete rule'),
      danger: true
    });
    if (!ok) return;
    try {
      await cancelSeriesTimer(rule.Id);
      toast(t('Series rule deleted'));
    } catch {
      toast(t("Couldn't delete the series rule"), 'error');
    }
    void loadTimers(true);
  };

  const head = (
    <button type="button" class="lt-rule-head" aria-expanded={open ? 'true' : 'false'} aria-controls={'lt-rule-' + rule.Id} onClick={onToggle}>
      <span aria-hidden="true" class="lt-rule-thumb" style={thumb ? { backgroundImage: `url("${thumb}")` } : { background: `linear-gradient(135deg, ${col.bg}, #121212)` }} />
      <span class="lt-two lt-rule-text">
        <span class="lt-rule-name">{name}</span>
        <span class="lt-rule-sum">{ruleSummary(open ? draft : rule, channel)}</span>
      </span>
      <LtIcon name={open ? 'chevronUp' : 'chevronDown'} size={18} stroke={2.4} class="lt-rule-chev" />
    </button>
  );
  if (!open) return <div class="lt-rule">{head}</div>;
  const at = clock(new Date(startMs(rule)));
  return (
    <div class="lt-rule is-open">
      {head}
      <div id={'lt-rule-' + rule.Id} class="lt-rule-body">
        <div class="lt-rule-row">
          <span id={'lt-r-ch-' + rule.Id} class="lt-rule-label">{t('Channel')}</span>
          <div role="radiogroup" aria-labelledby={'lt-r-ch-' + rule.Id} class="lt-seg">
            <button type="button" role="radio" aria-checked={!draft.RecordAnyChannel ? 'true' : 'false'} onClick={() => set({ RecordAnyChannel: false })}>{t('{channel} only', { channel: chName })}</button>
            <button type="button" role="radio" aria-checked={draft.RecordAnyChannel ? 'true' : 'false'} onClick={() => set({ RecordAnyChannel: true })}>{t('Any channel')}</button>
          </div>
        </div>
        <div class="lt-rule-row">
          <span id={'lt-r-time-' + rule.Id} class="lt-rule-label">{t('Time')}</span>
          <div role="radiogroup" aria-labelledby={'lt-r-time-' + rule.Id} class="lt-seg">
            <button type="button" role="radio" aria-checked={!draft.RecordAnyTime ? 'true' : 'false'} onClick={() => set({ RecordAnyTime: false })}>{t('Around {time}', { time: at })}</button>
            <button type="button" role="radio" aria-checked={draft.RecordAnyTime ? 'true' : 'false'} onClick={() => set({ RecordAnyTime: true })}>{t('Any time')}</button>
          </div>
        </div>
        <div class="lt-rule-row is-switch">
          <span class="lt-two"><span class="lt-rule-label">{t('New episodes only')}</span><span class="lt-rule-help">{t('Skips repeats')}</span></span>
          <Switch checked={draft.RecordNewOnly} onChange={(v) => set({ RecordNewOnly: v })} label={t('New episodes only')} />
        </div>
        <div class="lt-rule-row is-switch">
          <span class="lt-two"><span class="lt-rule-label">{t('Skip episodes I already have')}</span><span class="lt-rule-help">{t('Checks your Shows library first')}</span></span>
          <Switch checked={draft.SkipEpisodesInLibrary} onChange={(v) => set({ SkipEpisodesInLibrary: v })} label={t('Skip episodes I already have')} />
        </div>
        <div class="lt-rule-row">
          <label for={'lt-r-keep-' + rule.Id} class="lt-rule-label">{t('Keep')}</label>
          <span class="lt-select">
            <select id={'lt-r-keep-' + rule.Id} value={String(draft.KeepUpTo)} onChange={(e) => set({ KeepUpTo: Number((e.currentTarget as HTMLSelectElement).value) })}>
              {[...new Set([...KEEP, draft.KeepUpTo])].sort((a, b) => a - b).map((n) => (
                <option key={n} value={String(n)}>{n === 0 ? t('All episodes') : tn(n, 'The last episode', 'The last {n} episodes')}</option>
              ))}
            </select>
            <Icon name="chevronDown" size={15} stroke={2.2} />
          </span>
        </div>
        <div class="lt-rule-row">
          <span class="lt-rule-label">{t('Padding')}</span>
          <div class="lt-pads">
            <label class="lt-padbox">{t('Start')}<input type="number" min={0} max={120} value={Math.round(draft.PrePaddingSeconds / 60)} aria-label={t('Start minutes early')}
              onInput={(e) => set({ PrePaddingSeconds: Math.max(0, Number((e.currentTarget as HTMLInputElement).value) || 0) * 60 })} />{t('min early')}</label>
            <label class="lt-padbox">{t('End')}<input type="number" min={0} max={240} value={Math.round(draft.PostPaddingSeconds / 60)} aria-label={t('End minutes late')}
              onInput={(e) => set({ PostPaddingSeconds: Math.max(0, Number((e.currentTarget as HTMLInputElement).value) || 0) * 60 })} />{t('min late')}</label>
          </div>
        </div>
        <div class="lt-rule-foot">
          <button type="button" class="lt-del" onClick={() => void remove()}>{t('Delete rule')}</button>
          <button type="button" class="lt-btn is-primary lt-save" disabled={!dirty || busy} onClick={() => void save()}>{busy ? t('Saving…') : t('Save')}</button>
        </div>
      </div>
    </div>
  );
}

/* ── Recorded ── */

function RecordedCard({ item: r, channel, onDeleted }: { item: Rec; channel?: LiveChannel; onDeleted: () => void }) {
  const art = cardArt(r, 480);
  const pos = r.UserData?.PlaybackPositionTicks ?? 0;
  const total = r.RunTimeTicks ?? 0;
  const when = r.StartDate ?? r.DateCreated;
  const isNew = !r.UserData?.Played && !pos && !!when && Date.now() - Date.parse(when) < 14 * 86_400_000;
  const episode = (r.IsSeries || r.Type === 'Episode') && r.IndexNumber != null;
  const title = episode ? programTitle({ ...r, IsSeries: true, Name: r.SeriesName ?? r.Name } as Program) : r.Name;
  const meta = [when ? t('Recorded {date}', { date: `${weekdayShort(new Date(when))} ${fmtDayMonth(new Date(when))}` }) : '', channel?.label ?? (r.ChannelName ? cleanName(r.ChannelName) : '')].filter(Boolean).join(' · ');
  const remove = async () => {
    const ok = await confirm({ title: t('Delete {title}?', { title: title ?? '' }), text: t('The recording is removed from the server.'), action: t('Delete'), danger: true });
    if (!ok) return;
    try {
      await deleteRecording(r.Id);
      toast(t('Recording deleted'));
      onDeleted();
    } catch {
      toast(t("Couldn't delete the recording"), 'error');
    }
  };
  return (
    <div class="lt-card is-grid lt-reccard">
      <a href="#" class="lt-card-link" aria-label={t('Play {title}', { title: title ?? '' })} onClick={(e) => { e.preventDefault(); navigate(`/play/${r.Id}`); }}>
        <div class="lt-card-media">
          {art.src ? <ProgramArt program={r} channel={channel} width={480} /> : <ProgramArt channel={channel} width={480} />}
          <div class="card-hover"><span class="play-disc is-48"><Icon name="play" size={22} /></span></div>
          {isNew && <span class="lt-card-pills"><span class="lt-tag"><span class="lt-tag-dot" />{t('New')}</span></span>}
          {art.src && channel && <ChannelLogo channel={channel} size={30} radius={8} class="lt-card-logo" />}
          {pos > 0 && total > 0 && <div class="lt-card-bar"><i style={{ width: Math.min(100, (pos / total) * 100).toFixed(2) + '%' }} /></div>}
        </div>
        <span class="lt-card-text is-tight">
          <span class="lt-card-title is-14">{title}</span>
          <span class="lt-card-meta">{meta}</span>
        </span>
      </a>
      {r.CanDelete !== false && (
        <div class="lt-card-over">
          <button type="button" class="lt-card-del" aria-label={t('Delete {title}', { title: title ?? '' })} onClick={() => void remove()}>
            <Icon name="trash" size={16} />
          </button>
        </div>
      )}
    </div>
  );
}
