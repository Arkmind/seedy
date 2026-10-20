import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { getSession } from '../../api/client';
import { t, tn } from '../../lib/i18n';
import { Segmented } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { confirm, Sheet, toast } from '../../ui/overlay';
import { AdminLayout } from '../admin/AdminLayout';
import { type ChannelMappingOptions, deleteListingProvider, deleteTuner, discoverTuners, errText, getChannelCount, getChannelMappingOptions, getLineups, getLiveTv, getSdCountries, getTasks, type ListingsProvider, type LiveTvOptions, type NameIdPair, saveListingProvider, saveLiveTv, saveTuner, setChannelMapping, startTask, type TaskInfo, type TunerHostInfo } from './api';
import { getGuideInfo, type GuideInfo } from '../../api/livetv';
import { LoadFailed, NumBox, PathField, Select, SettingRow, SwitchCtl, UnsavedBar, useDraft, useLoad, useUnsavedGuard } from './parts';
import { Glyph } from './glyphs';
;

/* ── Settings saved with the unsaved bar (everything except tuners and guide sources, which save on their own) ── */

type RecKeys = 'GuideDays' | 'RecordingPath' | 'MovieRecordingPath' | 'SeriesRecordingPath' | 'PrePaddingSeconds' | 'PostPaddingSeconds'
  | 'RecordingPostProcessor' | 'RecordingPostProcessorArguments' | 'SaveRecordingNFO' | 'SaveRecordingImages';
type RecSettings = Pick<LiveTvOptions, RecKeys>;
const REC_KEYS: RecKeys[] = ['GuideDays', 'RecordingPath', 'MovieRecordingPath', 'SeriesRecordingPath', 'PrePaddingSeconds', 'PostPaddingSeconds',
  'RecordingPostProcessor', 'RecordingPostProcessorArguments', 'SaveRecordingNFO', 'SaveRecordingImages'];
function pickRec(o: LiveTvOptions): RecSettings {
  const out = {} as Record<string, unknown>;
  for (const k of REC_KEYS) out[k] = o[k] ?? (k === 'GuideDays' ? null : typeof o[k] === 'number' ? 0 : o[k] ?? '');
  return out as RecSettings;
}

const GUIDE_DAYS = [0, 1, 3, 7, 14];
const daysLabel = (n: number) => (n ? tn(n, '{n} day', '{n} days') : t('Auto'));

/* ── Tuners ── */

const typeLabel = (type: string) => (type.toLowerCase() === 'm3u' ? t('M3U tuner') : type.toLowerCase() === 'hdhomerun' ? 'HDHomeRun' : type);
const tunerName = (x: TunerHostInfo) => x.FriendlyName?.trim() || (x.Type === 'm3u' ? t('IPTV playlist') : typeLabel(x.Type));
const hostOf = (u?: string | null) => {
  if (!u) return '';
  try { return /^[a-z]+:\/\//i.test(u) ? new URL(u).host : u; } catch { return u; }
};

function newTuner(type: string): TunerHostInfo {
  return {
    Type: type, Url: '', FriendlyName: '', ImportFavoritesOnly: false, AllowHWTranscoding: true, AllowFmp4TranscodingContainer: false,
    AllowStreamSharing: true, FallbackMaxStreamingBitrate: 30_000_000, EnableStreamLooping: false, TunerCount: 0, UserAgent: '',
    IgnoreDts: true, ReadAtNativeFramerate: false
  };
}

const TUNER_SWITCHES: { key: keyof TunerHostInfo; name: string; help: string; types: string[] }[] = [
  { key: 'ImportFavoritesOnly', name: 'Only favourite channels', help: 'Import only channels marked as favourite on the tuner', types: ['hdhomerun'] },
  { key: 'AllowHWTranscoding', name: 'Hardware transcoding on the tuner', help: 'Let the tuner convert streams itself, so the server does less', types: ['hdhomerun'] },
  { key: 'AllowFmp4TranscodingContainer', name: 'Allow fMP4 container', help: 'Needed for HEVC and HDR channels. Turn off if playback fails', types: ['m3u'] },
  { key: 'AllowStreamSharing', name: 'Share streams', help: 'Several people watching one channel use one tuner slot', types: ['m3u'] },
  { key: 'EnableStreamLooping', name: 'Auto-loop live streams', help: 'For sources that send only a few seconds at a time', types: ['m3u'] },
  { key: 'IgnoreDts', name: 'Ignore decoding timestamps', help: 'Can fix missing audio on some channels', types: ['m3u'] },
  { key: 'ReadAtNativeFramerate', name: 'Read at native frame rate', help: 'Can fix streams that stop too early', types: ['m3u'] }
];

/* ── Page ── */

export function ServerLiveTV() {
  const cfg = useLoad(getLiveTv);
  const saved = useMemo(() => (cfg.data ? pickRec(cfg.data) : undefined), [cfg.data]);
  const rec = useDraft<RecSettings>(saved);
  const [saving, setSaving] = useState(false);
  const [editing, setEditing] = useState<{ key: string; draft: TunerHostInfo } | null>(null);
  const [found, setFound] = useState<TunerHostInfo[] | null>(null);
  const [channels, setChannels] = useState<number | null>(null);
  const [task, setTask] = useState<TaskInfo | null>(null);
  const [guide, setGuide] = useState<GuideInfo | null>(null);
  const [findOpen, setFindOpen] = useState(false);
  const [sourceSheet, setSourceSheet] = useState<{ provider: ListingsProvider | null } | null>(null);
  const [mapFor, setMapFor] = useState<ListingsProvider | null>(null);
  const [unmapped, setUnmapped] = useState<Record<string, number>>({});
  useUnsavedGuard(rec.changes > 0 || !!editing, t('Your changes to Live TV haven’t been saved yet. If you leave now, they’re lost.'));

  const tuners = cfg.data?.TunerHosts ?? [];
  const providers = cfg.data?.ListingProviders ?? [];

  /* Statuses: discovery marks HDHomeRuns online, the guide task says when listings were last fetched. */
  const refreshStatus = () => {
    const uid = getSession()?.userId;
    if (uid) getChannelCount(uid).then((r) => setChannels(r.TotalRecordCount), () => setChannels(null));
    getTasks().then((list) => setTask(list.find((t) => t.Key === 'RefreshGuide') ?? null), () => undefined);
    getGuideInfo().then(setGuide, () => undefined);
  };
  useEffect(() => { refreshStatus(); }, []);
  useEffect(() => {
    if (!tuners.some((t) => t.Type === 'hdhomerun')) return;
    let alive = true;
    discoverTuners().then((list) => { if (alive) setFound(list); }, () => { if (alive) setFound([]); });
    return () => { alive = false; };
  }, [tuners.map((t) => t.Id).join()]);
  useEffect(() => {
    let alive = true;
    for (const p of providers) {
      if (!p.Id) continue;
      getChannelMappingOptions(p.Id).then(
        (m) => { if (alive) setUnmapped((u) => ({ ...u, [p.Id!]: m.TunerChannels.filter((c) => !c.ProviderChannelId).length })); },
        () => undefined
      );
    }
    return () => { alive = false; };
  }, [providers.map((p) => `${p.Id}:${p.ChannelMappings?.length ?? 0}`).join()]);

  /* "Refresh guide data" runs the Refresh Guide task and follows it until it ends. */
  const pollRef = useRef(0);
  /* A poll still in flight when the page closes must not schedule the next one. */
  const mounted = useRef(true);
  useEffect(() => () => { mounted.current = false; window.clearTimeout(pollRef.current); }, []);
  /* Follows the task while it runs (also when the server started it itself, after a tuner or guide source changed);
     only a refresh asked for here ends with a toast. */
  const following = useRef<{ id: string; announce: boolean } | null>(null);
  const follow = (id: string, announce: boolean) => {
    window.clearTimeout(pollRef.current);
    if (!mounted.current) return;
    following.current = { id, announce: announce || (following.current?.id === id && following.current.announce) };
    pollRef.current = window.setTimeout(() => {
      getTasks().then((list) => {
        if (!mounted.current) return;
        const job = list.find((x) => x.Id === id) ?? null;
        setTask(job);
        if (job && job.State !== 'Idle') { follow(id, false); return; }
        const said = following.current?.announce;
        following.current = null;
        if (said) {
          const ok = job?.LastExecutionResult?.Status === 'Completed';
          toast(ok ? t('Guide data refreshed') : t('Refreshing the guide didn’t finish. Check the log.'), ok ? 'ok' : 'error');
        }
        refreshStatus();
      }, () => follow(id, false));
    }, 1500);
  };
  useEffect(() => {
    if (task && task.State !== 'Idle' && following.current?.id !== task.Id) follow(task.Id, false);
  }, [task?.Id, task?.State]);
  const refreshGuide = async () => {
    try {
      const job = task ?? (await getTasks()).find((x) => x.Key === 'RefreshGuide');
      if (!job) throw new Error(t('This server has no Refresh Guide task.'));
      await startTask(job.Id);
      following.current = { id: job.Id, announce: true };
      setTask({ ...job, State: 'Running', CurrentProgressPercentage: 0 });
      follow(job.Id, true);
    } catch (e) {
      toast(errText(e, t('Couldn’t start refreshing the guide.')), 'error');
    }
  };
  const refreshing = task?.State === 'Running' || task?.State === 'Cancelling';

  const saveRec = async () => {
    if (!rec.value) return;
    setSaving(true);
    try {
      const fresh = await getLiveTv();
      const v = rec.value;
      const orNull = (x?: string | null) => (x ?? '').trim() || null;
      const next: LiveTvOptions = {
        ...fresh, ...v, GuideDays: v.GuideDays || null, RecordingPath: orNull(v.RecordingPath), MovieRecordingPath: orNull(v.MovieRecordingPath),
        SeriesRecordingPath: orNull(v.SeriesRecordingPath), RecordingPostProcessor: orNull(v.RecordingPostProcessor)
      };
      await saveLiveTv(next);
      cfg.setData(next);
      rec.reset();
      toast(t('Live TV settings saved'));
    } catch (e) {
      toast(errText(e, t('Couldn’t save the Live TV settings.')), 'error');
    } finally {
      setSaving(false);
    }
  };

  const removeTuner = async (tuner: TunerHostInfo) => {
    const ok = await confirm({
      title: t('Remove {name}?', { name: tunerName(tuner) }),
      text: t('Its channels and their listings leave Live TV. Recordings already made are kept.'),
      action: t('Remove tuner'),
      danger: true
    });
    if (!ok || !tuner.Id) return;
    try {
      await deleteTuner(tuner.Id);
      toast(t('{name} removed', { name: tunerName(tuner) }));
      cfg.reload(true);
      refreshStatus();
    } catch (e) {
      toast(errText(e, t('Couldn’t remove the tuner.')), 'error');
    }
  };

  const removeProvider = async (p: ListingsProvider) => {
    const ok = await confirm({
      title: t('Remove {name}?', { name: providerName(p) }),
      text: t('Channels it filled in lose their listings until another guide source covers them.'),
      action: t('Remove guide source'),
      danger: true
    });
    if (!ok || !p.Id) return;
    try {
      await deleteListingProvider(p.Id);
      toast(t('Guide source removed'));
      cfg.reload(true);
    } catch (e) {
      toast(errText(e, t('Couldn’t remove the guide source.')), 'error');
    }
  };

  const onTunerSaved = (tuner: TunerHostInfo, isNew: boolean) => {
    setEditing(null);
    toast(isNew ? t('{name} added. Fetching its channels…', { name: tunerName(tuner) }) : t('{name} saved', { name: tunerName(tuner) }));
    cfg.reload(true);
    window.setTimeout(refreshStatus, 4000);
  };

  const r = rec.value;
  const setR = rec.patch;
  const lastRun = task?.LastExecutionResult;
  const listingDays = guide ? Math.max(0, Math.round((new Date(guide.EndDate).getTime() - Date.now()) / 86_400_000)) : 0;

  return (
    <AdminLayout
      page="livetv"
      wide
      title={t('Live TV')}
      lede={t("Tuners, guide data and recordings. Watching Live TV still happens in Jellyfin's app for now.")}
      actions={(
        <button type="button" class="btn btn-secondary" disabled={refreshing || !cfg.data} onClick={() => void refreshGuide()}>
          <Glyph name="refresh" size={17} />
          {refreshing ? (task?.CurrentProgressPercentage ? t('Refreshing guide · {pct}%', { pct: Math.round(task.CurrentProgressPercentage) }) : t('Refreshing guide…')) : t('Refresh guide data')}
        </button>
      )}
    >
      <span class="srv-w880 srv-gap28 srv-title16" hidden />
      {cfg.error && !cfg.data ? (
        <LoadFailed error={cfg.error} title={t('Couldn’t load the Live TV settings')} onRetry={() => cfg.reload()} />
      ) : !cfg.data || !r ? (
        <>
          <div class="srv-section"><span class="skel skel-line" style={{ width: '120px', height: '22px' }} /><span class="skel srv-skel-card" /></div>
          <div class="srv-section"><span class="skel skel-line" style={{ width: '140px', height: '22px' }} /><span class="skel srv-skel-card" /></div>
        </>
      ) : (
        <>
          <section aria-labelledby="tuners-h" class="srv-section">
            <div class="srv-section-head">
              <h2 id="tuners-h" class="srv-section-title">{t('Tuners')}</h2>
              <div class="srv-section-actions">
                <button type="button" class="btn btn-secondary is-sm" onClick={() => setFindOpen(true)}>{t('Find tuners on my network')}</button>
                <button type="button" class="btn btn-primary srv-btn-40" disabled={editing?.key === 'new'}
                  onClick={() => setEditing({ key: 'new', draft: newTuner('m3u') })}>
                  <Icon name="plus" size={15} />{t('Add tuner')}
                </button>
              </div>
            </div>

            {tuners.length === 0 && editing?.key !== 'new' && (
              <div class="srv-empty-card">
                <span class="srv-card-icon" aria-hidden="true"><Glyph name="tuner" /></span>
                <div class="srv-card-text">
                  <span class="srv-card-name">{t('No tuners yet')}</span>
                  <span class="srv-card-sub">{t('Add an HDHomeRun or an M3U playlist, or let the server look for tuners on your network.')}</span>
                </div>
              </div>
            )}

            {tuners.map((tuner) => (editing && editing.key === tuner.Id ? (
              <TunerEditor key={tuner.Id} draft={editing.draft} isNew={false} channels={tuners.length === 1 ? channels : null}
                onChange={(d) => setEditing({ key: editing.key, draft: d })} onCancel={() => setEditing(null)} onSaved={(x) => onTunerSaved(x, false)} />
            ) : (
              <TunerCard key={tuner.Id} t={tuner} channels={tuners.length === 1 ? channels : null}
                online={tuner.Type !== 'hdhomerun' || found === null ? null : found.some((f) => sameTuner(f, tuner))}
                onEdit={() => setEditing({ key: tuner.Id ?? '', draft: { ...tuner } })} onRemove={() => void removeTuner(tuner)} />
            )))}

            {editing?.key === 'new' && (
              <TunerEditor draft={editing.draft} isNew channels={null}
                onChange={(d) => setEditing({ key: 'new', draft: d })} onCancel={() => setEditing(null)} onSaved={(x) => onTunerSaved(x, true)} />
            )}
          </section>

          <section aria-labelledby="guide-h" class="srv-section">
            <div class="srv-section-head">
              <h2 id="guide-h" class="srv-section-title">{t('Guide data')}</h2>
              <button type="button" class="btn btn-secondary srv-btn-40b" onClick={() => setSourceSheet({ provider: null })}>
                <Icon name="plus" size={15} />{t('Add guide source')}
              </button>
            </div>
            {providers.length === 0 && (
              <div class="srv-empty-card">
                <span class="srv-card-icon" aria-hidden="true"><Icon name="list" size={22} stroke={1.8} /></span>
                <div class="srv-card-text">
                  <span class="srv-card-name">{t('No guide data yet')}</span>
                  <span class="srv-card-sub">{t('Add an XMLTV file or a Schedules Direct account so channels show what’s on.')}</span>
                </div>
              </div>
            )}
            {providers.map((p) => {
              const miss = p.Id ? unmapped[p.Id] : undefined;
              const failed = lastRun?.Status === 'Failed';
              const status = miss ? { dot: 'is-pending', text: tn(miss, '{n} channel not mapped', '{n} channels not mapped') }
                : failed ? { dot: 'is-danger', text: t('Last update failed {ago}', { ago: updatedAgo(lastRun?.EndTimeUtc ?? new Date().toISOString()) }) }
                : lastRun?.EndTimeUtc ? {
                  dot: '',
                  text: listingDays
                    ? t('Updated {ago} · {days} of listings', { ago: updatedAgo(lastRun.EndTimeUtc), days: tn(listingDays, '{n} day', '{n} days') })
                    : t('Updated {ago}', { ago: updatedAgo(lastRun.EndTimeUtc) })
                }
                : { dot: 'is-muted', text: t('Not updated yet') };
              return (
                <article key={p.Id} class="srv-card" aria-label={providerName(p)}>
                  <div class="srv-card-text is-280">
                    <span class="srv-card-name">{providerName(p)} <span class="srv-pill is-kind">{p.Type === 'xmltv' ? 'XMLTV' : t('Lineup')}</span></span>
                    <span class="srv-card-sub">{providerDetail(p, tuners)}</span>
                    <span class="srv-card-status"><span class={'dot ' + status.dot} />{status.text}</span>
                  </div>
                  <div class="srv-card-actions">
                    <button type="button" class="btn btn-secondary is-xs" onClick={() => setMapFor(p)}>{t('Map channels')}</button>
                    <button type="button" class="btn btn-secondary is-xs" onClick={() => setSourceSheet({ provider: p })}>{t('Edit')}</button>
                    <button type="button" class="btn btn-ghost is-36" aria-label={t('Remove {name}', { name: providerName(p) })} onClick={() => void removeProvider(p)}>{t('Remove')}</button>
                  </div>
                </article>
              );
            })}
            <div class="srv-days">
              <label for="guide-days" class="srv-label is-free">
                <span class="srv-name">{t('Days of guide to download')}</span>
                <span class="srv-help">{t('More days lets people schedule further ahead, but updates take longer')}</span>
              </label>
              <Select id="guide-days" minWidth={160} value={r.GuideDays ?? 0}
                options={(GUIDE_DAYS.includes(r.GuideDays ?? 0) ? GUIDE_DAYS : [...GUIDE_DAYS, r.GuideDays ?? 0].sort((a, b) => a - b)).map((n) => ({ value: n, label: daysLabel(n) }))}
                onChange={(v) => setR({ GuideDays: v || null })} />
            </div>
          </section>

          <section aria-labelledby="rec-h" class="panel">
            <h2 id="rec-h" class="panel-title srv-panel-h">{t('Recordings')}</h2>
            <SettingRow name={t('Recordings folder')} help={t('Empty uses the server data folder')} cls="is-16" labelCls="is-free is-normal">
              <PathField label={t('Recordings folder')} value={r.RecordingPath ?? ''} placeholder={t('Server data folder')} onChange={(v) => setR({ RecordingPath: v })} />
            </SettingRow>
            <SettingRow name={t('Movie recordings')} help={t('Optional, overrides the folder above')} cls="is-16" labelCls="is-free is-normal">
              <PathField label={t('Movie recordings')} value={r.MovieRecordingPath ?? ''} placeholder={t('Same as recordings folder')} onChange={(v) => setR({ MovieRecordingPath: v })} />
            </SettingRow>
            <SettingRow name={t('Series recordings')} help={t('Optional, overrides the folder above')} cls="is-16" labelCls="is-free is-normal">
              <PathField label={t('Series recordings')} value={r.SeriesRecordingPath ?? ''} placeholder={t('Same as recordings folder')} onChange={(v) => setR({ SeriesRecordingPath: v })} />
            </SettingRow>
            <SettingRow name={t('Padding')} help={t('Start early and stop late when the tuner allows')} cls="is-16" labelCls="is-free is-normal">
              <div class="srv-chiprow">
                <NumBox label={t('Minutes before')} unit={t('min before')} width={36} value={Math.round(r.PrePaddingSeconds / 60)} min={0} max={120} onChange={(v) => setR({ PrePaddingSeconds: v * 60 })} />
                <NumBox label={t('Minutes after')} unit={t('min after')} width={36} value={Math.round(r.PostPaddingSeconds / 60)} min={0} max={240} onChange={(v) => setR({ PostPaddingSeconds: v * 60 })} />
              </div>
            </SettingRow>
            <SettingRow name={t('After recording, run')} help={t('Optional. Use {path} for the recording file')} htmlFor="post-app" cls="is-16" labelCls="is-free is-normal">
              <div class="srv-field-stack">
                <input id="post-app" type="text" class="field srv-mono-plain" placeholder={t('Program, e.g. /usr/bin/comskip')} spellcheck={false} value={r.RecordingPostProcessor ?? ''}
                  onChange={(e) => setR({ RecordingPostProcessor: (e.currentTarget as HTMLInputElement).value.trim() })} />
                <input type="text" class="field srv-mono-plain" aria-label={t('Arguments')} spellcheck={false} value={r.RecordingPostProcessorArguments ?? ''}
                  onChange={(e) => setR({ RecordingPostProcessorArguments: (e.currentTarget as HTMLInputElement).value })} />
              </div>
            </SettingRow>
            <SettingRow name={t('Save guide info as NFO')} help={t('Store listing details next to each recording')} cls="is-rec-switch" labelCls="is-free is-normal">
              <SwitchCtl on={r.SaveRecordingNFO} set={(v) => setR({ SaveRecordingNFO: v })} label={t('Save guide info as NFO')} />
            </SettingRow>
            <SettingRow name={t('Save guide images')} help={t('Store listing artwork next to each recording')} cls="is-rec-switch" labelCls="is-free is-normal">
              <SwitchCtl on={r.SaveRecordingImages} set={(v) => setR({ SaveRecordingImages: v })} label={t('Save guide images')} />
            </SettingRow>
          </section>
        </>
      )}

      <UnsavedBar changes={rec.changes} saving={saving} onDiscard={rec.reset} onSave={() => void saveRec()} />

      <FindTuners open={findOpen} configured={tuners} onClose={() => setFindOpen(false)}
        onAdd={(t) => { setFindOpen(false); setEditing({ key: 'new', draft: { ...newTuner('hdhomerun'), ...t, Id: null } }); }} />
      <GuideSourceSheet open={!!sourceSheet} provider={sourceSheet?.provider ?? null} tuners={tuners}
        onClose={() => { setSourceSheet(null); /* "Find lineups" may already have saved the source */ cfg.reload(true); }} onSaved={(isNew) => { setSourceSheet(null); toast(isNew ? 'Guide source added' : 'Guide source saved'); cfg.reload(true); refreshStatus(); }} />
      <MapChannels provider={mapFor} onClose={() => setMapFor(null)}
        onChanged={(id, n) => setUnmapped((u) => ({ ...u, [id]: n }))} />
    </AdminLayout>
  );
}

const sameTuner = (a: TunerHostInfo, b: TunerHostInfo) =>
  (!!a.DeviceId && a.DeviceId === b.DeviceId) || (!!a.Url && hostOf(a.Url) === hostOf(b.Url));

/* "just now", "12 min ago", "2 hours ago", "3 days ago". */
function updatedAgo(iso: string): string {
  const min = Math.round((Date.now() - new Date(iso).getTime()) / 60_000);
  if (min < 1) return t('just now');
  if (min < 60) return t('{n} min ago', { n: min });
  const h = Math.round(min / 60);
  if (h < 24) return tn(h, '{n} hour ago', '{n} hours ago');
  return tn(Math.round(h / 24), '{n} day ago', '{n} days ago');
}

/* ── Tuner card and inline editor ── */

function TunerCard({ t: tuner, channels, online, onEdit, onRemove }: {
  t: TunerHostInfo; channels: number | null; online: boolean | null; onEdit: () => void; onRemove: () => void;
}) {
  const name = tunerName(tuner);
  const parts = [typeLabel(tuner.Type), tuner.Type === 'hdhomerun' ? hostOf(tuner.Url) : tuner.Url || ''];
  if (tuner.TunerCount) parts.push(tn(tuner.TunerCount, '{n} stream', '{n} streams'));
  if (channels !== null) parts.push(tn(channels, '{n} channel', '{n} channels'));
  return (
    <article class="srv-card" aria-label={t('{name} tuner', { name })}>
      <span class="srv-card-icon" aria-hidden="true"><Glyph name={tuner.Type === 'm3u' ? 'playlist' : 'tuner'} /></span>
      <div class="srv-card-text">
        <span class="srv-card-name">
          {name}
          {online !== null && <span class="srv-pill"><span class={'dot' + (online ? '' : ' is-danger')} />{online ? t('Online') : t('Not found')}</span>}
        </span>
        <span class="srv-card-sub">{parts.filter(Boolean).join(' · ')}</span>
      </div>
      <div class="srv-card-actions">
        <button type="button" class="btn btn-secondary is-xs" onClick={onEdit}>{t('Edit')}</button>
        <button type="button" class="btn btn-ghost is-36" aria-label={t('Remove {name}', { name })} onClick={onRemove}>{t('Remove')}</button>
      </div>
    </article>
  );
}

function TunerEditor({ draft, isNew, channels, onChange, onCancel, onSaved }: {
  draft: TunerHostInfo; isNew: boolean; channels: number | null; onChange: (d: TunerHostInfo) => void; onCancel: () => void; onSaved: (t: TunerHostInfo) => void;
}) {
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const ref = useRef<HTMLElement>(null);
  useEffect(() => {
    ref.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    ref.current?.querySelector<HTMLElement>('#tuner-type, #tuner-url')?.focus({ preventScroll: true });
  }, []);
  const set = (p: Partial<TunerHostInfo>) => { setErr(null); onChange({ ...draft, ...p }); };
  const type = draft.Type;
  const m3u = type === 'm3u';
  const name = isNew ? t('New tuner') : tunerName(draft);
  const sub = [typeLabel(type), channels !== null ? tn(channels, '{n} channel', '{n} channels') : '', isNew ? t('adding') : t('editing')].filter(Boolean).join(' · ');

  const save = async () => {
    const url = (draft.Url ?? '').trim();
    if (!url) { setErr(m3u ? t('Add the playlist’s file or URL.') : t('Add the tuner’s IP address.')); return; }
    setBusy(true);
    setErr(null);
    try {
      const body: TunerHostInfo = { ...draft, Url: url, UserAgent: (draft.UserAgent ?? '').trim() || null, FriendlyName: (draft.FriendlyName ?? '').trim() || null };
      if (import.meta.env.DEV) console.info('[seedy] POST /LiveTv/TunerHosts', JSON.stringify(body));
      const res = await saveTuner(body);
      onSaved(res ?? body);
    } catch (e) {
      setErr(errText(e, m3u ? t('The server couldn’t open that playlist. Check the address and try again.') : t('The server couldn’t reach that tuner.')));
    } finally {
      setBusy(false);
    }
  };

  return (
    <article ref={ref} class="srv-editor" aria-label={isNew ? t('{name}, adding', { name }) : t('{name}, editing', { name })}>
      <div class="srv-editor-head">
        <span class="srv-card-icon" aria-hidden="true"><Glyph name={m3u ? 'playlist' : 'tuner'} /></span>
        <div class="srv-card-text">
          <input class={'srv-name-input' + (isNew ? ' is-new' : '')} value={draft.FriendlyName ?? ''} placeholder={isNew ? t('Name this tuner') : name} aria-label={t('Tuner name')} title={t('Rename')}
            onInput={(e) => set({ FriendlyName: (e.currentTarget as HTMLInputElement).value })} />
          <span class="srv-card-sub">{sub}</span>
        </div>
      </div>
      <form class="srv-editor-body" onSubmit={(e) => { e.preventDefault(); void save(); }}>
        {isNew && (
          <SettingRow name={t('Type')} help={t('What kind of tuner this is')} htmlFor="tuner-type" cls="is-16" labelCls="is-free is-normal">
            <Select id="tuner-type" minWidth={360} value={type}
              options={[{ value: 'm3u', label: t('M3U playlist (IPTV)') }, { value: 'hdhomerun', label: 'HDHomeRun' }]}
              onChange={(v) => onChange({ ...newTuner(v), FriendlyName: draft.FriendlyName, Url: draft.Url })} />
          </SettingRow>
        )}
        <SettingRow name={m3u ? t('File or URL') : t('IP address')} help={m3u ? t('M3U playlist') : t('The tuner’s address on your network')} htmlFor="tuner-url" cls="is-16" labelCls="is-free is-normal">
          <input id="tuner-url" type="text" class="field srv-mono-plain srv-text is-360" spellcheck={false} value={draft.Url ?? ''}
            placeholder={m3u ? 'http://iptv.local/playlist.m3u' : '192.168.1.40'} aria-invalid={err && !(draft.Url ?? '').trim() ? 'true' : undefined}
            onInput={(e) => set({ Url: (e.currentTarget as HTMLInputElement).value })} />
        </SettingRow>
        {m3u && (
          <SettingRow name={t('User agent')} help={t('Optional, sent with every request')} htmlFor="tuner-ua" cls="is-16" labelCls="is-free is-normal">
            <input id="tuner-ua" type="text" class="field srv-text is-360" placeholder={t('Default')} value={draft.UserAgent ?? ''}
              onInput={(e) => set({ UserAgent: (e.currentTarget as HTMLInputElement).value })} />
          </SettingRow>
        )}
        <SettingRow name={t('Limits')} help={m3u ? t('0 streams means no limit. Bitrate is used when it can’t be detected') : t('Bitrate is used when it can’t be detected')} cls="is-16" labelCls="is-free is-normal">
          <div class="srv-chiprow">
            {m3u && <NumBox label={t('Simultaneous streams')} unit={t('streams')} width={40} value={draft.TunerCount} min={0} max={99} onChange={(v) => set({ TunerCount: v })} />}
            <NumBox label={t('Fallback bitrate in Mbps')} unit="Mbps" width={40} value={Math.round(draft.FallbackMaxStreamingBitrate / 1_000_000)} min={1} max={400}
              onChange={(v) => set({ FallbackMaxStreamingBitrate: v * 1_000_000 })} />
          </div>
        </SettingRow>
        {TUNER_SWITCHES.filter((s) => s.types.includes(type)).map((s) => (
          <SettingRow key={s.key} name={t(s.name)} help={t(s.help)} labelCls="is-560">
            <SwitchCtl on={!!draft[s.key]} set={(v) => set({ [s.key]: v } as Partial<TunerHostInfo>)} label={t(s.name)} />
          </SettingRow>
        ))}
        <div class="srv-editor-foot">
          {err ? <span class="form-error" role="alert"><Icon name="alert" size={16} />{err}</span> : <span class="srv-note">{t('Channels with DRM are never imported.')}</span>}
          <div class="srv-unsaved-actions">
            <button type="button" class="btn btn-ghost is-light" disabled={busy} onClick={onCancel}>{t('Cancel')}</button>
            <button type="submit" class="btn btn-primary is-sm is-save" disabled={busy}>{busy ? t('Checking…') : t('Save tuner')}</button>
          </div>
        </div>
      </form>
    </article>
  );
}

/* ── Find tuners ── */

function FindTuners({ open, configured, onClose, onAdd }: { open: boolean; configured: TunerHostInfo[]; onClose: () => void; onAdd: (t: TunerHostInfo) => void }) {
  const [list, setList] = useState<TunerHostInfo[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const run = () => {
    setList(null);
    setErr(null);
    discoverTuners().then(setList, (e) => { setList([]); setErr(errText(e, t('The server couldn’t search the network.'))); });
  };
  useEffect(() => { if (open) run(); }, [open]);
  return (
    <Sheet open={open} onClose={onClose} label={t('Find tuners on my network')}>
      <div class="sheet-head">
        <div class="srv-sheet-titles">
          <h2 class="sheet-title">{t('Tuners on your network')}</h2>
          <p class="sheet-text">{t('HDHomeRun tuners on the same network as the server show up here.')}</p>
        </div>
        <button type="button" class="btn icon-btn is-40" aria-label={t('Close')} onClick={onClose}><Icon name="close" size={18} /></button>
      </div>
      <div class="link-list" aria-busy={list === null ? 'true' : undefined}>
        {list === null && <span class="menu-item is-muted"><span class="spinner" style={{ width: '18px', height: '18px', borderWidth: '2px' }} />{t('Looking for tuners…')}</span>}
        {list && list.length === 0 && <p class="sheet-text">{err ?? t('No tuners answered. Check that the tuner is on and on the same network, or add it by its address.')}</p>}
        {list?.map((tuner) => {
          const known = configured.some((c) => sameTuner(c, tuner));
          return (
            <div key={(tuner.DeviceId ?? '') + tuner.Url} class="menu-item" style={{ cursor: 'default' }}>
              <span class="lead">
                <Glyph name="tuner" size={20} />
                <span class="two"><span>{tuner.FriendlyName || typeLabel(tuner.Type)}</span><span class="sub">{[hostOf(tuner.Url), tuner.TunerCount ? tn(tuner.TunerCount, '{n} stream', '{n} streams') : ''].filter(Boolean).join(' · ')}</span></span>
              </span>
              {known ? <span class="srv-note">{t('Added')}</span> : <button type="button" class="btn btn-secondary is-xs" onClick={() => onAdd(tuner)}>{t('Add')}</button>}
            </div>
          );
        })}
      </div>
      <div class="sheet-actions">
        <button type="button" class="btn btn-ghost is-light" onClick={run} disabled={list === null}>{t('Search again')}</button>
        <button type="button" class="btn btn-primary is-sm" onClick={onClose}>{t('Done')}</button>
      </div>
    </Sheet>
  );
}

/* ── Guide sources ── */

function providerName(p: ListingsProvider): string {
  if (p.Type === 'xmltv') {
    const file = (p.Path ?? '').split(/[\\/]/).filter(Boolean).pop();
    return file ? `XMLTV · ${file}` : 'XMLTV';
  }
  return 'Schedules Direct';
}

function providerDetail(p: ListingsProvider, tuners: TunerHostInfo[]): string {
  const forTuners = p.EnableAllTuners || !p.EnabledTuners?.length
    ? t('All tuners')
    : t('{tuners} only', { tuners: tuners.filter((x) => x.Id && p.EnabledTuners.includes(x.Id)).map(tunerName).join(', ') });
  if (p.Type === 'xmltv') {
    const cat = (label: string, list?: string[]) => (list?.length ? t('{label}: {list}', { label: t(label), list: list.map(cap).join('|') }) : '');
    return [cat('Movies', p.MovieCategories), cat('Kids', p.KidsCategories), cat('News', p.NewsCategories), cat('Sports', p.SportsCategories), forTuners].filter(Boolean).join(' · ');
  }
  return [p.Username, p.Country, p.ZipCode, forTuners].filter(Boolean).join(' · ');
}

const cap = (s: string) => s.charAt(0).toUpperCase() + s.slice(1);
const CAT_LABEL = { movies: 'Movies categories', kids: 'Kids categories', news: 'News categories', sports: 'Sports categories' } as const;
const splitList = (s: string) => s.split(/[|,]/).map((x) => x.trim()).filter(Boolean);

function blankProvider(type: string): ListingsProvider {
  return {
    Type: type, Path: '', Username: '', ListingsId: '', ZipCode: '', Country: '', EnabledTuners: [], EnableAllTuners: true,
    NewsCategories: ['news', 'journalism', 'documentary', 'current affairs'], SportsCategories: ['sports', 'basketball', 'baseball', 'football'],
    KidsCategories: ['kids', 'family', 'children', 'childrens', 'disney'], MovieCategories: ['movie'], ChannelMappings: [], MoviePrefix: '', UserAgent: ''
  };
}

function GuideSourceSheet({ open, provider, tuners, onClose, onSaved }: {
  open: boolean; provider: ListingsProvider | null; tuners: TunerHostInfo[]; onClose: () => void; onSaved: (isNew: boolean) => void;
}) {
  const isNew = !provider;
  const [p, setP] = useState<ListingsProvider>(blankProvider('xmltv'));
  const [cats, setCats] = useState({ movies: '', kids: '', news: '', sports: '' });
  const [pw, setPw] = useState('');
  const [countries, setCountries] = useState<{ value: string; label: string }[] | null>(null);
  const [lineups, setLineups] = useState<NameIdPair[] | null>(null);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    const base = provider ? { ...blankProvider(provider.Type), ...provider } : blankProvider('xmltv');
    setP(base);
    setCats({ movies: base.MovieCategories.join(', '), kids: base.KidsCategories.join(', '), news: base.NewsCategories.join(', '), sports: base.SportsCategories.join(', ') });
    setPw('');
    setErr(null);
    setLineups(null);
  }, [open, provider]);

  useEffect(() => {
    if (!open || p.Type !== 'schedulesdirect' || countries) return;
    getSdCountries().then(
      (data) => setCountries(Object.values(data).flat().map((c) => ({ value: c.shortName, label: c.fullName })).sort((a, b) => a.label.localeCompare(b.label))),
      () => setCountries([])
    );
  }, [open, p.Type]);

  const set = (x: Partial<ListingsProvider>) => { setErr(null); setP((o) => ({ ...o, ...x })); };
  const sd = p.Type === 'schedulesdirect';

  const findLineups = async () => {
    setBusy(true);
    setErr(null);
    try {
      /* Lineups need a saved, signed-in account: save it first, then ask. */
      const saved = await saveListingProvider({ ...p }, { pw: pw || undefined, validateLogin: true });
      setP((o) => ({ ...o, Id: saved.Id, Password: saved.Password }));
      setPw('');
      const list = await getLineups({ id: saved.Id, type: 'schedulesdirect', location: p.ZipCode, country: p.Country });
      setLineups(list);
      if (list.length && !p.ListingsId) set({ ListingsId: list[0].Id });
    } catch (e) {
      setErr(errText(e, t('Schedules Direct didn’t accept that account.')));
    } finally {
      setBusy(false);
    }
  };

  const save = async () => {
    if (!sd && !(p.Path ?? '').trim()) { setErr(t('Add the XMLTV file or URL.')); return; }
    if (sd && (!p.Username || (!p.Password && !pw))) { setErr(t('Add the Schedules Direct username and password.')); return; }
    if (sd && !p.ListingsId) { setErr(t('Find and pick a lineup first.')); return; }
    setBusy(true);
    setErr(null);
    try {
      const body: ListingsProvider = sd ? p : {
        ...p, Path: (p.Path ?? '').trim(), MovieCategories: splitList(cats.movies), KidsCategories: splitList(cats.kids),
        NewsCategories: splitList(cats.news), SportsCategories: splitList(cats.sports)
      };
      await saveListingProvider(body, { pw: pw || undefined, validateListings: true, validateLogin: sd });
      onSaved(isNew);
    } catch (e) {
      setErr(errText(e, sd ? t('Schedules Direct didn’t accept that.') : t('The server couldn’t read that XMLTV file.')));
    } finally {
      setBusy(false);
    }
  };

  const toggleTuner = (id: string, on: boolean) => {
    const list = on ? [...p.EnabledTuners.filter((x) => x !== id), id] : p.EnabledTuners.filter((x) => x !== id);
    set({ EnabledTuners: list, EnableAllTuners: false });
  };

  return (
    <Sheet open={open} onClose={onClose} label={isNew ? t('Add guide source') : t('Edit guide source')} wide>
      <div class="sheet-head">
        <div class="srv-sheet-titles">
          <h2 class="sheet-title">{isNew ? t('Add guide source') : providerName(p)}</h2>
          <p class="sheet-text">{t('Where channel listings come from.')}</p>
        </div>
        <button type="button" class="btn icon-btn is-40" aria-label={t('Close')} onClick={onClose}><Icon name="close" size={18} /></button>
      </div>
      <form class="srv-sheet-form" onSubmit={(e) => { e.preventDefault(); void save(); }}>
        {isNew && (
          <Segmented label={t('Guide source type')} value={p.Type} variant="fill"
            options={[{ value: 'xmltv', label: t('XMLTV file') }, { value: 'schedulesdirect', label: 'Schedules Direct' }]}
            onChange={(v) => { setP(blankProvider(v)); setErr(null); setLineups(null); }} />
        )}
        {!sd ? (
          <>
            <label class="srv-sheet-field">{t('File or URL')}
              <PathField files label={t('XMLTV file')} title={t('Choose the XMLTV file')} value={p.Path ?? ''} placeholder={t('/config/xmltv/tv-guide.xml or https://…')} onChange={(v) => set({ Path: v })} />
            </label>
            <div class="srv-sheet-grid is-2">
              {(['movies', 'kids', 'news', 'sports'] as const).map((k) => (
                <label key={k} class="srv-sheet-field">{t(CAT_LABEL[k])}
                  <input class="field" value={cats[k]} onInput={(e) => setCats((c) => ({ ...c, [k]: (e.currentTarget as HTMLInputElement).value }))} />
                </label>
              ))}
            </div>
            <span class="srv-sheet-hint">{t('Listing categories, separated by commas, that put programmes in Movies, Kids, News and Sports.')}</span>
            <div class="srv-sheet-grid">
              <label class="srv-sheet-field">{t('Movie prefix')}
                <input class="field" value={p.MoviePrefix ?? ''} placeholder={t('None')} onInput={(e) => set({ MoviePrefix: (e.currentTarget as HTMLInputElement).value })} />
              </label>
              <label class="srv-sheet-field">{t('User agent')}
                <input class="field" value={p.UserAgent ?? ''} placeholder={t('Default')} onInput={(e) => set({ UserAgent: (e.currentTarget as HTMLInputElement).value })} />
              </label>
            </div>
          </>
        ) : (
          <>
            <div class="srv-sheet-grid">
              <label class="srv-sheet-field">{t('Username')}
                <input class="field" value={p.Username ?? ''} autocomplete="off" onInput={(e) => set({ Username: (e.currentTarget as HTMLInputElement).value })} />
              </label>
              <label class="srv-sheet-field">{t('Password')}
                <input class="field" type="password" value={pw} placeholder={p.Password ? t('Saved') : ''} autocomplete="new-password" onInput={(e) => { setPw((e.currentTarget as HTMLInputElement).value); setErr(null); }} />
              </label>
              <label class="srv-sheet-field">{t('Country')}
                {countries && countries.length > 0
                  ? <Select label={t('Country')} value={p.Country ?? ''} options={[{ value: '', label: t('Choose…') }, ...countries]} onChange={(v) => set({ Country: v })} />
                  : <input class="field" value={p.Country ?? ''} placeholder="USA" onInput={(e) => set({ Country: (e.currentTarget as HTMLInputElement).value })} />}
              </label>
              <label class="srv-sheet-field">{t('Postal code')}
                <input class="field" value={p.ZipCode ?? ''} onInput={(e) => set({ ZipCode: (e.currentTarget as HTMLInputElement).value })} />
              </label>
            </div>
            <div class="srv-sheet-field">{t('Lineup')}
              <div class="srv-browse-path">
                {lineups && lineups.length > 0
                  ? <Select label={t('Lineup')} value={p.ListingsId ?? ''} options={lineups.map((l) => ({ value: l.Id, label: l.Name }))} onChange={(v) => set({ ListingsId: v })} />
                  : <input class="field" readOnly value={p.ListingsId ?? ''} placeholder={t('Find lineups for your area')} aria-label={t('Lineup')} />}
                <button type="button" class="btn btn-secondary" disabled={busy || !p.Username || !p.Country || !p.ZipCode} onClick={() => void findLineups()}>{t('Find lineups')}</button>
              </div>
            </div>
          </>
        )}
        {tuners.length > 0 && (
          <div class="srv-sheet-field" role="group" aria-label={t('Use for')}>
            {t('Use for')}
            <div class="srv-checks">
              <label class="srv-check-sm"><input type="checkbox" checked={p.EnableAllTuners} onChange={(e) => set({ EnableAllTuners: (e.currentTarget as HTMLInputElement).checked })} />{t('All tuners')}</label>
              {!p.EnableAllTuners && tuners.map((x) => x.Id && (
                <label key={x.Id} class="srv-check-sm"><input type="checkbox" checked={p.EnabledTuners.includes(x.Id)} onChange={(e) => toggleTuner(x.Id!, (e.currentTarget as HTMLInputElement).checked)} />{tunerName(x)}</label>
              ))}
            </div>
          </div>
        )}
        {err && <span class="form-error" role="alert"><Icon name="alert" size={16} />{err}</span>}
        <div class="sheet-actions">
          <button type="button" class="btn btn-ghost is-light" onClick={onClose}>{t('Cancel')}</button>
          <button type="submit" class="btn btn-primary is-sm" disabled={busy}>{busy ? t('Checking…') : isNew ? t('Add guide source') : t('Save')}</button>
        </div>
      </form>
    </Sheet>
  );
}

/* ── Channel mapping ── */

function MapChannels({ provider, onClose, onChanged }: { provider: ListingsProvider | null; onClose: () => void; onChanged: (id: string, unmapped: number) => void }) {
  const [opts, setOpts] = useState<ChannelMappingOptions | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [q, setQ] = useState('');
  const [busy, setBusy] = useState<string | null>(null);
  useEffect(() => {
    if (!provider?.Id) return;
    setOpts(null);
    setErr(null);
    setQ('');
    getChannelMappingOptions(provider.Id).then(setOpts, (e) => setErr(errText(e, t('The server couldn’t list the channels.'))));
  }, [provider?.Id]);

  const choices = useMemo(() => [{ value: '', label: t('Not mapped') }, ...(opts?.ProviderChannels ?? []).map((c) => ({ value: c.Id, label: c.Name }))], [opts]);
  const shown = (opts?.TunerChannels ?? []).filter((c) => !q || c.Name.toLowerCase().includes(q.toLowerCase()));

  const map = async (tunerChannelId: string, providerChannelId: string) => {
    if (!provider?.Id || !opts) return;
    setBusy(tunerChannelId);
    try {
      const res = await setChannelMapping(provider.Id, tunerChannelId, providerChannelId);
      const next = { ...opts, TunerChannels: opts.TunerChannels.map((c) => (c.Id === tunerChannelId ? { ...c, ...res, ProviderChannelId: providerChannelId || null } : c)) };
      setOpts(next);
      onChanged(provider.Id, next.TunerChannels.filter((c) => !c.ProviderChannelId).length);
    } catch (e) {
      toast(errText(e, t('Couldn’t map that channel.')), 'error');
    } finally {
      setBusy(null);
    }
  };

  return (
    <Sheet open={!!provider} onClose={onClose} label={t('Map channels')} wide>
      <div class="sheet-head">
        <div class="srv-sheet-titles">
          <h2 class="sheet-title">{t('Map channels')}</h2>
          <p class="sheet-text">{provider
            ? t('Pick the listing each tuner channel should show from {source}. Channels with the same name or number are matched automatically.', { source: providerName(provider) })
            : t('Pick the listing each tuner channel should show. Channels with the same name or number are matched automatically.')}</p>
        </div>
        <button type="button" class="btn icon-btn is-40" aria-label={t('Close')} onClick={onClose}><Icon name="close" size={18} /></button>
      </div>
      {opts && opts.TunerChannels.length > 8 && (
        <label class="search-field">
          <Icon name="search" size={18} />
          <input type="search" placeholder={t('Search channels')} aria-label={t('Search channels')} value={q} onInput={(e) => setQ((e.currentTarget as HTMLInputElement).value)} />
        </label>
      )}
      <div class="srv-map-list">
        {!opts && !err && Array.from({ length: 4 }, (_, i) => <span key={i} class="skel srv-picker-skel" style={{ margin: '6px 0' }} />)}
        {err && <p class="sheet-text">{err}</p>}
        {opts && opts.TunerChannels.length === 0 && <p class="sheet-text">{t('No channels yet. Add a tuner, then refresh the guide data.')}</p>}
        {shown.map((c) => (
          <div key={c.Id} class="srv-map-row">
            <span class="srv-map-name">{c.Name}<span class="srv-help">{c.ProviderChannelName ? t('Shows {name}', { name: c.ProviderChannelName }) : t('No listings')}</span></span>
            <Select label={t('Listing for {name}', { name: c.Name })} value={c.ProviderChannelId ?? ''} options={choices}
              onChange={(v) => { if (busy !== c.Id) void map(c.Id, v); }} />
          </div>
        ))}
      </div>
      <div class="sheet-actions">
        <button type="button" class="btn btn-primary is-sm" onClick={onClose}>{t('Done')}</button>
      </div>
    </Sheet>
  );
}
