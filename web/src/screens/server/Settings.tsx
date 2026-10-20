import { useEffect, useMemo, useState } from 'preact/hooks';
import { t, tn } from '../../lib/i18n';
import { safeLocal, saveLocal, useAsync } from '../../lib/store';
import { NumField, Switch } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { confirm, toast } from '../../ui/overlay';
import { AdminLayout, LoadError, PanelSkeletons } from '../admin/AdminLayout';
import { type BrandingOptions, type CastReceiver, errText, getBranding, getLocalizationOptions, getServerConfig, getSystemInfo, type LocalizationOption, patchBranding, patchServerConfig, type PathSubstitution, restartServer, type ServerConfig, type SystemInfo, uploadSplashscreen } from './api';
import { FolderPicker, PairsSheet, UnsavedBar, useFilePicker, useUnsavedGuard } from './parts';

/* Server · Settings: Jellyfin's General and Branding pages on one page, saved explicitly. */

interface Draft {
  serverName: string;
  uiCulture: string;
  cachePath: string;
  metadataPath: string;
  quickConnect: boolean;
  splash: boolean;
  disclaimer: string;
  scanFanout: number;
  imageEncoding: number;
  activityDays: number;
  logDays: number;
  idleMinutes: number;
  clientLogs: boolean;
  slowWarn: boolean;
  slowMs: number;
  metrics: boolean;
  cors: string;
  pathSubs: PathSubstitution[];
  monitorDelay: number;
  sortWords: string;
  metadataRefreshes: number;
  castApps: CastReceiver[];
  legacyAuth: boolean;
  customCss: string;
}

const RESTART_KEYS: (keyof Draft)[] = ['cachePath', 'metadataPath'];
const BRANDING_KEYS: (keyof Draft)[] = ['splash', 'disclaimer', 'customCss'];

const splitList = (s: string) => s.split(',').map((x) => x.trim()).filter(Boolean);

function toDraft(cfg: ServerConfig, branding: BrandingOptions, info: SystemInfo): Draft {
  return {
    serverName: cfg.ServerName || info.ServerName || '',
    uiCulture: cfg.UICulture || 'en-US',
    cachePath: cfg.CachePath || info.CachePath || '',
    metadataPath: cfg.MetadataPath || info.InternalMetadataPath || '',
    quickConnect: !!cfg.QuickConnectAvailable,
    splash: !!branding.SplashscreenEnabled,
    disclaimer: branding.LoginDisclaimer ?? '',
    scanFanout: cfg.LibraryScanFanoutConcurrency ?? 0,
    imageEncoding: cfg.ParallelImageEncodingLimit ?? 0,
    activityDays: cfg.ActivityLogRetentionDays ?? 0,
    logDays: cfg.LogFileRetentionDays ?? 0,
    idleMinutes: cfg.InactiveSessionThreshold ?? 0,
    clientLogs: !!cfg.AllowClientLogUpload,
    slowWarn: !!cfg.EnableSlowResponseWarning,
    slowMs: cfg.SlowResponseThresholdMs ?? 500,
    metrics: !!cfg.EnableMetrics,
    cors: (cfg.CorsHosts ?? []).join(', '),
    pathSubs: (cfg.PathSubstitutions ?? []).map((p) => ({ From: p.From, To: p.To })),
    monitorDelay: cfg.LibraryMonitorDelay ?? 60,
    sortWords: (cfg.SortRemoveWords ?? []).join(', '),
    metadataRefreshes: cfg.LibraryMetadataRefreshConcurrency ?? 0,
    castApps: (cfg.CastReceiverApplications ?? []).map((c) => ({ Id: c.Id, Name: c.Name })),
    legacyAuth: !!cfg.EnableLegacyAuthorization,
    customCss: branding.CustomCss ?? ''
  };
}

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

function changedKeys(d: Draft, base: Draft): (keyof Draft)[] {
  return (Object.keys(d) as (keyof Draft)[]).filter((k) => {
    if (k === 'cors' || k === 'sortWords') return !same(splitList(d[k]), splitList(base[k]));
    return !same(d[k], base[k]);
  });
}

function configPatch(d: Draft, keys: (keyof Draft)[]): Partial<ServerConfig> {
  const p: Partial<ServerConfig> = {};
  for (const k of keys) {
    switch (k) {
      case 'serverName': p.ServerName = d.serverName.trim(); break;
      case 'uiCulture': p.UICulture = d.uiCulture; break;
      case 'cachePath': p.CachePath = d.cachePath.trim() || null; break;
      case 'metadataPath': p.MetadataPath = d.metadataPath.trim(); break;
      case 'quickConnect': p.QuickConnectAvailable = d.quickConnect; break;
      case 'scanFanout': p.LibraryScanFanoutConcurrency = d.scanFanout; break;
      case 'imageEncoding': p.ParallelImageEncodingLimit = d.imageEncoding; break;
      case 'activityDays': p.ActivityLogRetentionDays = d.activityDays; break;
      case 'logDays': p.LogFileRetentionDays = d.logDays; break;
      case 'idleMinutes': p.InactiveSessionThreshold = d.idleMinutes; break;
      case 'clientLogs': p.AllowClientLogUpload = d.clientLogs; break;
      case 'slowWarn': p.EnableSlowResponseWarning = d.slowWarn; break;
      case 'slowMs': p.SlowResponseThresholdMs = d.slowMs; break;
      case 'metrics': p.EnableMetrics = d.metrics; break;
      case 'cors': p.CorsHosts = splitList(d.cors); break;
      case 'pathSubs': p.PathSubstitutions = d.pathSubs; break;
      case 'monitorDelay': p.LibraryMonitorDelay = d.monitorDelay; break;
      case 'sortWords': p.SortRemoveWords = splitList(d.sortWords); break;
      case 'metadataRefreshes': p.LibraryMetadataRefreshConcurrency = d.metadataRefreshes; break;
      case 'castApps': p.CastReceiverApplications = d.castApps; break;
      case 'legacyAuth': p.EnableLegacyAuthorization = d.legacyAuth; break;
      default: break;
    }
  }
  return p;
}

const EXPERT_KEY = 'seedy.device.adminExpertOpen';

export function ServerSettings() {
  const loaded = useAsync(async (signal) => {
    const [cfg, branding, info, langs] = await Promise.all([
      getServerConfig(signal), getBranding(signal), getSystemInfo(signal), getLocalizationOptions(signal).catch(() => [] as LocalizationOption[])
    ]);
    return { cfg, branding, info, langs };
  }, []);
  const base = useMemo(() => (loaded.data ? toDraft(loaded.data.cfg, loaded.data.branding, loaded.data.info) : null), [loaded.data]);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [saving, setSaving] = useState(false);
  const d = draft ?? base;
  const changes = d && base ? changedKeys(d, base) : [];
  const needsRestart = changes.some((k) => RESTART_KEYS.includes(k));
  useUnsavedGuard(changes.length > 0, t('Your changes to the server settings haven’t been saved yet. If you leave now, they’re lost.'));

  const set = <K extends keyof Draft>(k: K, v: Draft[K]) => setDraft((cur) => ({ ...(cur ?? base!), [k]: v }));

  const save = async () => {
    if (!d || !base || !changes.length) return;
    if (needsRestart) {
      const ok = await confirm({
        title: t('Save and restart the server?'),
        text: t('Everyone watching is interrupted for a minute while Jellyfin restarts. Seedy reconnects on its own when the server is back.'),
        action: t('Save and restart')
      });
      if (!ok) return;
    }
    setSaving(true);
    try {
      const cfgKeys = changes.filter((k) => !BRANDING_KEYS.includes(k));
      const brandKeys = changes.filter((k) => BRANDING_KEYS.includes(k));
      if (cfgKeys.length) await patchServerConfig(configPatch(d, cfgKeys));
      if (brandKeys.length) {
        const patch: Partial<BrandingOptions> = {};
        if (brandKeys.includes('splash')) patch.SplashscreenEnabled = d.splash;
        if (brandKeys.includes('disclaimer')) patch.LoginDisclaimer = d.disclaimer.trim() || null;
        if (brandKeys.includes('customCss')) patch.CustomCss = d.customCss || null;
        await patchBranding(patch);
      }
      setDraft(null);
      loaded.reload();
      if (needsRestart) {
        toast(t('Settings saved'));
        await restartServer();
        loaded.reload();
      } else {
        toast(t('Settings saved'));
      }
    } catch (e) {
      toast(t('Couldn’t save. {error}', { error: errText(e) }), 'error');
    } finally {
      setSaving(false);
    }
  };

  return (
    <AdminLayout
      page="settings"
      title={t('Settings')}
      lede={t("Server name, paths, sign-in, performance and logging. Jellyfin's General and Branding pages, on one page.")}
      wide
    >
      {!loaded.data && loaded.error ? (
        <div class="srv-set-root"><LoadError error={loaded.error} onRetry={loaded.reload} what={t('the server settings')} /></div>
      ) : !d || !loaded.data ? (
        <div class="srv-set-root srv-stack"><PanelSkeletons count={4} rows={2} /></div>
      ) : (
        <SettingsForm d={d} set={set} data={loaded.data} />
      )}
      {d && (
        <UnsavedBar
          changes={changes.length}
          note={needsRestart ? t('needs a restart') : undefined}
          saveLabel={needsRestart ? t('Save and restart') : undefined}
          saving={saving}
          onDiscard={() => setDraft(null)}
          onSave={() => void save()}
        />
      )}
    </AdminLayout>
  );
}

function SettingsForm({ d, set, data }: {
  d: Draft;
  set: <K extends keyof Draft>(k: K, v: Draft[K]) => void;
  data: { cfg: ServerConfig; info: SystemInfo; langs: LocalizationOption[] };
}) {
  const [browse, setBrowse] = useState<'cachePath' | 'metadataPath' | null>(null);
  const [subsOpen, setSubsOpen] = useState(false);
  const [castOpen, setCastOpen] = useState(false);
  const [expert, setExpert] = useState(() => safeLocal<boolean>(EXPERT_KEY, true));
  const [uploading, setUploading] = useState(false);
  useEffect(() => saveLocal(EXPERT_KEY, expert), [expert]);

  const splashFile = useFilePicker(async (file) => {
    setUploading(true);
    try {
      await uploadSplashscreen(file);
      toast(t('Splash screen image uploaded'));
    } catch (e) {
      toast(t('Couldn’t upload it. {error}', { error: errText(e) }), 'error');
    } finally {
      setUploading(false);
    }
  });

  const langs = data.langs.length ? data.langs : [{ Name: d.uiCulture, Value: d.uiCulture }];
  const langOptions = langs.some((l) => l.Value === d.uiCulture) ? langs : [{ Name: d.uiCulture, Value: d.uiCulture }, ...langs];

  const subsLabel = d.pathSubs.length === 0 ? t('Add a rule') : tn(d.pathSubs.length, '{n} rule · Edit', '{n} rules · Edit');
  const castLabel = d.castApps.length === 0 ? t('Add an app') : t('{names} · Edit', { names: d.castApps.map((c) => c.Name || c.Id).join(', ') });

  return (
    <>
      <section aria-labelledby="set-srv" class="panel srv-set-root">
        <h2 id="set-srv" class="panel-title srv-panel-h">{t('Server')}</h2>
        <div class="setting">
          <label for="server-name" class="setting-label"><span class="setting-name">{t('Server name')}</span><span class="setting-help">{t('Shown to apps when they find this server')}</span></label>
          <input id="server-name" class="field srv-w300" value={d.serverName} placeholder={data.info.ServerName} onInput={(e) => set('serverName', (e.currentTarget as HTMLInputElement).value)} />
        </div>
        <div class="setting is-last">
          <label for="server-lang" class="setting-label"><span class="setting-name">{t('Server language')}</span><span class="setting-help">{t('For notifications, logs and generated names')}</span></label>
          <span class="select-wrap">
            <select id="server-lang" class="select srv-w300" value={d.uiCulture} onChange={(e) => set('uiCulture', (e.currentTarget as HTMLSelectElement).value)}>
              {langOptions.map((l) => <option key={l.Value} value={l.Value}>{l.Name}</option>)}
            </select>
            <Icon name="chevronDown" size={16} />
          </span>
        </div>
      </section>

      <section aria-labelledby="set-paths" class="panel">
        <div class="srv-panel-headrow">
          <h2 id="set-paths" class="panel-title">{t('Paths')}</h2>
          <span class="srv-restart-note"><span class="pip" />{t('Changes need a restart')}</span>
        </div>
        {([
          ['cachePath', 'Cache', 'Images and other files Jellyfin can rebuild'],
          ['metadataPath', 'Metadata', 'Artwork and info downloaded for your library']
        ] as const).map(([key, name, help]) => (
          <div key={key} class="setting">
            <span class="setting-label"><span class="setting-name">{t(name)}</span><span class="setting-help">{t(help)}</span></span>
            <div class="srv-path-field">
              <input class="field srv-mono" aria-label={t(name)} value={d[key]} onInput={(e) => set(key, (e.currentTarget as HTMLInputElement).value)} />
              <button type="button" class="btn btn-secondary srv-browse" onClick={() => setBrowse(key)}>{t('Browse')}</button>
            </div>
          </div>
        ))}
      </section>

      <section aria-labelledby="set-sign" class="panel">
        <h2 id="set-sign" class="panel-title srv-panel-h">{t('Sign-in')}</h2>
        <div class="setting">
          <span class="setting-label"><span class="setting-name">{t('Quick Connect')}</span><span class="setting-help">{t('Sign in on a TV with a code from a signed-in device')}</span></span>
          <Switch checked={d.quickConnect} onChange={(v) => set('quickConnect', v)} label={t('Quick Connect')} />
        </div>
        <div class="setting">
          <span class="setting-label"><span class="setting-name">{t('Splash screen')}</span><span class="setting-help">{t('Image shown while apps load')}</span></span>
          <div class="setting-controls is-12">
            {splashFile.input}
            <button type="button" class="btn btn-secondary is-sm" disabled={uploading} onClick={splashFile.open}>{uploading ? t('Uploading…') : t('Upload image')}</button>
            <Switch checked={d.splash} onChange={(v) => set('splash', v)} label={t('Splash screen')} />
          </div>
        </div>
        <div class="setting is-stack is-last srv-gap10">
          <label for="disclaimer" class="setting-label"><span class="setting-name">{t('Sign-in message')}</span><span class="setting-help">{t('Shown under the sign-in form')}</span></label>
          <textarea id="disclaimer" rows={2} class="srv-textarea" value={d.disclaimer} onInput={(e) => set('disclaimer', (e.currentTarget as HTMLTextAreaElement).value)} />
        </div>
      </section>

      <section aria-labelledby="set-perf" class="panel">
        <h2 id="set-perf" class="panel-title srv-panel-h">{t('Performance')}</h2>
        <NumRow name={t('Parallel library scans')} help={t('How many items a scan works on at once. 0 picks automatically')} value={d.scanFanout} unit={t('items')} max={64} onChange={(v) => set('scanFanout', v)} />
        <NumRow name={t('Parallel image encoding')} help={t('Image jobs at once. 0 picks automatically')} value={d.imageEncoding} unit={t('jobs')} max={64} onChange={(v) => set('imageEncoding', v)} />
      </section>

      <section aria-labelledby="set-log" class="panel">
        <h2 id="set-log" class="panel-title srv-panel-h">{t('Logging and sessions')}</h2>
        {/* Jellyfin's clean-up tasks read 0 days as "delete everything", not "keep forever". */}
        <NumRow name={t('Keep activity log for')} help={t('Sign-ins, playback and changes')} value={d.activityDays} unit={t('days')} min={1} max={3650} onChange={(v) => set('activityDays', v)} />
        <NumRow name={t('Keep log files for')} help={t('Server log files on disk')} value={d.logDays} unit={t('days')} min={1} max={3650} onChange={(v) => set('logDays', v)} />
        <NumRow name={t('Close idle sessions after')} help={t('0 never closes them')} value={d.idleMinutes} unit="min" max={10080} onChange={(v) => set('idleMinutes', v)} />
        <div class="setting">
          <span class="setting-label"><span class="setting-name">{t('Let apps upload their logs')}</span><span class="setting-help">{t('Helps when debugging a device')}</span></span>
          <Switch checked={d.clientLogs} onChange={(v) => set('clientLogs', v)} label={t('Let apps upload their logs')} />
        </div>
        <div class="setting">
          <span class="setting-label"><span class="setting-name">{t('Warn about slow responses')}</span><span class="setting-help">{t('Logs requests slower than {ms} ms', { ms: d.slowMs })}</span></span>
          <div class="setting-controls is-12">
            {d.slowWarn && <span class="srv-num"><NumField value={d.slowMs} min={50} max={60000} suffix="ms" ariaLabel={t('Slow response threshold, milliseconds')} width={72} onChange={(v) => set('slowMs', v)} /></span>}
            <Switch checked={d.slowWarn} onChange={(v) => set('slowWarn', v)} label={t('Warn about slow responses')} />
          </div>
        </div>
        <div class="setting">
          <span class="setting-label"><span class="setting-name">{t('Metrics endpoint')}</span><span class="setting-help">{t('Prometheus metrics for monitoring tools')}</span></span>
          <Switch checked={d.metrics} onChange={(v) => set('metrics', v)} label={t('Metrics endpoint')} />
        </div>
      </section>

      <section aria-labelledby="set-exp" class="srv-expert">
        <button type="button" class="srv-expert-head" aria-expanded={expert ? 'true' : 'false'} aria-controls="set-exp-body" onClick={() => setExpert((o) => !o)}>
          <span class="srv-expert-text">
            <span id="set-exp" class="srv-expert-title">{t('Expert')}</span>
            <span class="srv-expert-sub">{t('Settings Jellyfin only exposes in its config file. Change them with care.')}</span>
          </span>
          {/* 'Show' alone is the noun (a TV show) in the dictionary; {verb} keeps this key apart and fills in nothing. */}
          <span class="srv-expert-toggle">{expert ? t('Hide') : t('Show{verb}', { verb: '' })}</span>
        </button>
        {expert && (
          <div id="set-exp-body" class="srv-expert-body">
            <div class="setting">
              <label for="cors" class="setting-label"><span class="setting-name">{t('Allowed web origins')}</span><span class="setting-help">{t('Sites allowed to call this server from a browser. * allows all')}</span></label>
              <input id="cors" class="field srv-w300 srv-mono" value={d.cors} onInput={(e) => set('cors', (e.currentTarget as HTMLInputElement).value)} />
            </div>
            <div class="setting">
              <span class="setting-label"><span class="setting-name">{t('Path substitution')}</span><span class="setting-help">{t('Rewrite server paths for apps that read files directly, like Kodi')}</span></span>
              <button type="button" class="btn btn-secondary is-sm" onClick={() => setSubsOpen(true)}>{subsLabel}</button>
            </div>
            <NumRow name={t('Wait after a file changes')} help={t('Before folder watching scans it')} value={d.monitorDelay} unit="s" max={3600} onChange={(v) => set('monitorDelay', v)} ariaLabel={t('Wait after a file changes, seconds')} />
            <div class="setting">
              <label for="sortwords" class="setting-label"><span class="setting-name">{t('Ignore when sorting')}</span><span class="setting-help">{t('Leading words skipped in A–Z order')}</span></label>
              <input id="sortwords" class="field srv-w300 srv-plain" value={d.sortWords} onInput={(e) => set('sortWords', (e.currentTarget as HTMLInputElement).value)} />
            </div>
            <NumRow name={t('Parallel metadata refreshes')} help={t('0 picks automatically')} value={d.metadataRefreshes} unit={t('items')} max={64} onChange={(v) => set('metadataRefreshes', v)} />
            <div class="setting">
              <span class="setting-label"><span class="setting-name">{t('Cast receiver apps')}</span><span class="setting-help">{t('Chromecast apps people can pick when casting')}</span></span>
              <button type="button" class="btn btn-secondary is-sm" onClick={() => setCastOpen(true)}>{castLabel}</button>
            </div>
            <div class="setting">
              <span class="setting-label"><span class="setting-name">{t('Legacy authorization')}</span><span class="setting-help">{t('Accept the old sign-in header some older apps still send')}</span></span>
              <Switch checked={d.legacyAuth} onChange={(v) => set('legacyAuth', v)} label={t('Legacy authorization')} />
            </div>
          </div>
        )}
      </section>

      <section aria-labelledby="set-css" class="panel srv-css">
        <div class="panel-head srv-css-head">
          <h2 id="set-css" class="panel-title">{t("Custom CSS for Jellyfin's app")}</h2>
          <p class="panel-text">{t("Applies to Jellyfin's own web app at /web. Seedy has its own theme under Branding & playback.")}</p>
        </div>
        <textarea
          aria-label={t('Custom CSS')}
          rows={7}
          class="srv-code"
          spellcheck={false}
          placeholder={t('/* No custom CSS */')}
          value={d.customCss}
          onInput={(e) => set('customCss', (e.currentTarget as HTMLTextAreaElement).value)}
        />
      </section>

      <FolderPicker
        open={browse !== null}
        start={browse ? d[browse] : ''}
        title={browse === 'cachePath' ? t('Cache folder') : t('Metadata folder')}
        onClose={() => setBrowse(null)}
        onPick={(p) => { if (browse) set(browse, p); setBrowse(null); }}
      />
      <PairsSheet
        open={subsOpen}
        title={t('Path substitution')}
        text={t('When an app reads files directly, it sees the path on the right instead of the server’s path on the left.')}
        rows={d.pathSubs as unknown as Record<string, string>[]}
        keys={['From', 'To']}
        labels={[t('Server path'), t('Path apps see')]}
        placeholders={['/media/movies', '\\\\nas\\movies']}
        mono={[true, true]}
        addLabel={t('Add a rule')}
        onClose={() => setSubsOpen(false)}
        onDone={(rows) => { set('pathSubs', rows.filter((r) => r.From && r.To).map((r) => ({ From: r.From, To: r.To }))); setSubsOpen(false); }}
      />
      <PairsSheet
        open={castOpen}
        title={t('Cast receiver apps')}
        text={t('The Chromecast apps people can choose from when they cast. Each needs its Google Cast application ID.')}
        rows={d.castApps as unknown as Record<string, string>[]}
        keys={['Name', 'Id']}
        labels={[t('Name'), t('Application ID')]}
        placeholders={['Stable', 'F007D354']}
        mono={[false, true]}
        addLabel={t('Add an app')}
        onClose={() => setCastOpen(false)}
        onDone={(rows) => { set('castApps', rows.filter((r) => r.Id).map((r) => ({ Id: r.Id, Name: r.Name || r.Id }))); setCastOpen(false); }}
      />
    </>
  );
}

function NumRow({ name, help, value, unit, min = 0, max, onChange, ariaLabel }: {
  name: string; help: string; value: number; unit: string; min?: number; max: number; onChange: (v: number) => void; ariaLabel?: string;
}) {
  return (
    <div class="setting">
      <span class="setting-label"><span class="setting-name">{name}</span><span class="setting-help">{help}</span></span>
      <span class="srv-num"><NumField value={value} min={min} max={max} suffix={unit} ariaLabel={ariaLabel ?? name} width={56} onChange={onChange} /></span>
    </div>
  );
}
