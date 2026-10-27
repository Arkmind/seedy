import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import {
  adminMediaInfo, adminMediaKey, adminMediaProfile, adminMediaProfiles, adminMediaTest,
  type AdminMedia, type AdminMediaInfo, type AdminMediaProfile, type MediaService, type MediaTestResult
} from '../../api/media';
import type { AdminConfig } from '../../api/seedy';
import { refreshMe } from '../../lib/app-state';
import { t } from '../../lib/i18n';
import { createStore, useStore } from '../../lib/store';
import { SavedNote, SelectField, SettingRow, Switch } from '../../ui/controls';
import { Icon, type IconName } from '../../ui/icons';
import { MenuButton, MenuItem, Sheet, toast } from '../../ui/overlay';
import { AdminLayout, LoadError, PanelSkeletons } from './AdminLayout';
import { editConfig, flushConfig, mergeServerConfig, useAdminConfig } from './config';
import { ProfileAvatar } from './Requests';
import '../requests/media.css';

/* Music and book requests (AdminMediaRequests board): Seedy talks to Lidarr, Shelfarr, Librarr and a Readarr-API app
   itself, so people request music and books from the Requests page. Keys stay on the server; each app has a
   connection test; formats are routed to apps; limits and auto-approval are per profile. Autosaves. */

type Media = AdminMedia;
type BookApp = 'shelfarr' | 'librarr' | 'readarr';

export const DEF_MEDIA: Media = {
  lidarr: { url: '', qualityProfileId: 0, metadataProfileId: 0, rootFolder: '', wholeArtist: 'all', searchOnApprove: true },
  shelfarr: { enabled: false, url: '' },
  librarr: { enabled: false, url: '' },
  readarr: { enabled: false, url: '' },
  routes: { ebooks: 'shelfarr', audiobooks: 'shelfarr', comics: 'librarr' },
  scanOnReady: true,
  notifyOnReady: true,
  showRequesters: false
};

const media = (c: AdminConfig): Media => ({ ...DEF_MEDIA, ...c.media, lidarr: { ...DEF_MEDIA.lidarr, ...c.media?.lidarr } } as Media);
const setMedia = (fn: (m: Media) => Media, delay?: number) => editConfig((c) => ({ ...c, media: fn(media(c)) }), delay);

const APP_NAMES: Record<MediaService, string> = { lidarr: 'Lidarr', shelfarr: 'Shelfarr', librarr: 'Librarr', readarr: 'Readarr' };

/* ── Tests and Lidarr's lists, kept for the session ── */

interface TestState { state: 'idle' | 'testing' | 'done'; result?: MediaTestResult; error?: string; at: number }
const testsStore = createStore<Partial<Record<MediaService, TestState>>>({});
const seq: Partial<Record<MediaService, number>> = {};
function test(service: MediaService) {
  const n = (seq[service] = (seq[service] ?? 0) + 1);
  testsStore.set((s) => ({ ...s, [service]: { ...(s[service] ?? { at: 0 }), state: 'testing' } }));
  return adminMediaTest(service).then(
    (result) => { if (seq[service] === n) testsStore.set((s) => ({ ...s, [service]: { state: 'done', result, at: Date.now() } })); refreshMe(); },
    (e: Error) => { if (seq[service] === n) testsStore.set((s) => ({ ...s, [service]: { state: 'done', error: e.message, at: Date.now() } })); }
  );
}

const infoStore = createStore<{ data?: AdminMediaInfo; loading: boolean }>({ loading: false });
function loadInfo() {
  infoStore.set((s) => ({ ...s, loading: true }));
  adminMediaInfo().then((data) => infoStore.set({ data, loading: false }), () => infoStore.set((s) => ({ ...s, loading: false })));
}

const profilesStore = createStore<{ data?: AdminMediaProfile[]; error?: Error }>({});

export function AdminMediaRequests() {
  const { config, error, save, reload } = useAdminConfig();
  const m = config ? media(config) : null;
  useEffect(() => { loadInfo(); adminMediaProfiles().then((data) => profilesStore.set({ data }), (e: Error) => profilesStore.set({ error: e })); }, []);
  // Test what's set up when the page opens, unless it was tested a minute ago.
  useEffect(() => {
    if (!m) return;
    const tests = testsStore.get();
    for (const s of ['lidarr', 'shelfarr', 'librarr', 'readarr'] as MediaService[]) {
      const app = s === 'lidarr' ? { enabled: true, ...m.lidarr } : m[s];
      if (app.enabled && app.url && app.apiKeyHint && tests[s]?.state !== 'testing' && Date.now() - (tests[s]?.at ?? 0) > 60_000) void test(s);
    }
  }, [!!m]);

  return (
    <AdminLayout
      page="mediaRequests"
      title={t('Music and book requests')}
      lede={t('Seedy talks to these apps directly, so people can request music and books from the Requests page without another sign-in. Movie and show requests stay in Seerr.')}
    >
      {!config && error ? <LoadError error={error} onRetry={reload} />
        : !m ? <PanelSkeletons count={4} />
        : (
          <>
            <LidarrPanel m={m} />
            <BooksPanel m={m} />
            {(['shelfarr', 'librarr', 'readarr'] as BookApp[]).filter((s) => m[s].enabled).map((s) => <BookAppPanel key={s} service={s} m={m} />)}
            <LimitsPanel />
            <AfterPanel m={m} />
            <SavedNote state={save} />
          </>
        )}
    </AdminLayout>
  );
}

/* ── Pieces ── */

function Head({ icon, title, sub, id }: { icon: IconName; title: string; sub: string; id: string }) {
  return (
    <div class="amr-head">
      <span class="amr-icon" aria-hidden="true"><Icon name={icon} size={20} stroke={2.2} /></span>
      <span class="amr-head-text">
        <h2 id={id} class="panel-title">{title}</h2>
        <span class="amr-head-sub">{sub}</span>
      </span>
    </div>
  );
}

function UrlRow({ service, value, onCommit, placeholder }: { service: MediaService; value: string; onCommit: (v: string) => void; placeholder: string }) {
  const [url, setUrl] = useState(value);
  useEffect(() => setUrl(value), [value]);
  const commit = async () => {
    let v = url.trim().replace(/\/+$/, '');
    if (v && !/^https?:\/\//i.test(v)) v = 'http://' + v;
    if (v !== url) setUrl(v);
    if (v === value) return;
    onCommit(v);
    try { await flushConfig(); } catch { return; }
    if (v) { void test(service); if (service === 'lidarr') loadInfo(); }
  };
  return (
    <SettingRow name={t('Server address')} help={t('As this Jellyfin server reaches it')} htmlFor={service + '-url'}>
      <input
        id={service + '-url'}
        class="field is-340"
        type="url"
        inputMode="url"
        value={url}
        placeholder={placeholder}
        autoComplete="off"
        spellcheck={false}
        aria-label={t('{app} server address', { app: APP_NAMES[service] })}
        onInput={(e) => setUrl((e.currentTarget as HTMLInputElement).value)}
        onBlur={() => void commit()}
        onKeyDown={(e) => {
          if (e.key === 'Enter') { e.preventDefault(); void commit(); }
          if (e.key === 'Escape') setUrl(value);
        }}
      />
    </SettingRow>
  );
}

function KeyRow({ service, hint, name, help }: { service: MediaService; hint?: string; name: string; help: string }) {
  const [open, setOpen] = useState(false);
  return (
    <SettingRow name={name} help={help}>
      <div class="setting-controls is-10">
        <span class="key-saved">
          <Icon name="lock" size={16} />
          {hint ? t('Saved · ends in {hint}', { hint }) : t('No key saved')}
        </span>
        <button type="button" class="btn btn-secondary" onClick={() => setOpen(true)}>{hint ? t('Replace key') : t('Add key')}</button>
      </div>
      <KeySheet service={service} open={open} onClose={() => setOpen(false)} replacing={!!hint} />
    </SettingRow>
  );
}

const KEY_HELP: Record<MediaService, string> = {
  lidarr: 'Copy the key from Settings › General in Lidarr. Seedy keeps it on the server and only shows its last four characters.',
  shelfarr: 'Make a token under Profile › API tokens in Shelfarr, with the search and requests scopes. Seedy keeps it on the server and only shows its last four characters.',
  librarr: 'Copy the key from Settings in Librarr. Seedy sends it as X-Api-Key, keeps it on the server and only shows its last four characters.',
  readarr: 'Copy the key from Settings › General in the app. Seedy keeps it on the server and only shows its last four characters.'
};

function KeySheet({ service, open, onClose, replacing }: { service: MediaService; open: boolean; onClose: () => void; replacing: boolean }) {
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (open) setKey(''); }, [open]);
  const submit = async (e: Event) => {
    e.preventDefault();
    const v = key.trim();
    if (!v) return;
    setBusy(true);
    try {
      await flushConfig().catch(() => { /* the key is saved on its own */ });
      const res = await adminMediaKey(service, v);
      mergeServerConfig(res, (server, local) => ({ ...local, media: { ...media(local), [service]: { ...media(local)[service], apiKeyHint: server.media?.[service]?.apiKeyHint } } }));
      toast(t('API key saved'));
      onClose();
      void test(service);
      if (service === 'lidarr') loadInfo();
    } catch (err) {
      toast(`${t('Couldn’t save the key.')} ${(err as Error).message || ''}`.trim(), 'error');
    } finally {
      setBusy(false);
    }
  };
  const title = replacing ? t('Replace {app} key', { app: APP_NAMES[service] }) : t('Add {app} key', { app: APP_NAMES[service] });
  return (
    <Sheet open={open} onClose={onClose} label={title}>
      <form style={{ display: 'contents' }} onSubmit={submit}>
        <div class="sheet-head"><h2 class="sheet-title">{title}</h2></div>
        <p class="sheet-text">{t(KEY_HELP[service])}</p>
        <input class="field" type="password" value={key} aria-label={t('{app} API key', { app: APP_NAMES[service] })} placeholder={t('{app} API key', { app: APP_NAMES[service] })} autoComplete="off" spellcheck={false} onInput={(e) => setKey((e.currentTarget as HTMLInputElement).value)} />
        <div class="sheet-actions">
          <button type="button" class="btn btn-ghost is-light" onClick={onClose}>{t('Cancel')}</button>
          <button type="submit" class="btn btn-primary is-sm" disabled={!key.trim() || busy}>{busy ? t('Saving…') : t('Save key')}</button>
        </div>
      </form>
    </Sheet>
  );
}

/* "Connected. Lidarr 3.1 · 41 artists monitored · 2 downloads running", worded from the test's numbers. */
function connectedLine(r: MediaTestResult): string {
  const c = r.counts ?? {};
  switch (r.service) {
    case 'lidarr':
      return [`Lidarr ${r.version ?? ''}`.trim(), t('{n} artists monitored', { n: c.artists ?? 0 }), c.downloads === 1 ? t('1 download running') : t('{n} downloads running', { n: c.downloads ?? 0 })].join(' · ');
    case 'shelfarr':
      return t('The token can search and make requests · {n} still on their way', { n: c.open ?? 0 });
    case 'librarr':
      return [r.version ? `Librarr ${r.version}` : 'Librarr', c.sources === 1 ? t('1 source on') : t('{n} sources on', { n: c.sources ?? 0 }), t('{n} items in its library', { n: c.items ?? 0 })].join(' · ');
    default: {
      const app = Object.keys(c).find((k) => k.startsWith('app:'))?.slice(4) ?? 'Readarr';
      return `${app} ${r.version ?? ''}`.trim();
    }
  }
}

function ConnRow({ service, ready, children }: { service: MediaService; ready: boolean; children?: ComponentChildren }) {
  const s = useStore(testsStore)[service];
  let dot = 'is-muted';
  let lead: string;
  let rest = '';
  if (!ready) { lead = t('Not connected.'); rest = t('Add the address and key above.'); }
  else if (s?.state === 'testing' && !s.result && !s.error) { dot = 'is-pending'; lead = t('Testing…'); rest = t('Asking {app} for its status.', { app: APP_NAMES[service] }); }
  else if (s?.error) { dot = 'is-danger'; lead = t('Couldn’t reach {app}.', { app: APP_NAMES[service] }); rest = s.error; }
  else if (s?.result?.ok) { dot = ''; lead = t('Connected.'); rest = connectedLine(s.result); }
  else if (s?.result) { dot = 'is-danger'; lead = t('Not connected.'); rest = t(s.result.message); }
  else { dot = 'is-pending'; lead = t('Not tested yet.'); rest = t('Test the connection to check the address and key.'); }
  const testing = s?.state === 'testing';
  return (
    <div class="setting is-last">
      <span class="conn-state" role="status" aria-live="polite">
        <span class={'dot ' + dot} />
        <span><b>{lead}</b>{rest ? ' ' + rest : ''}</span>
      </span>
      <div class="setting-controls">
        <button type="button" class="btn btn-primary is-sm" disabled={!ready || testing} aria-busy={testing ? 'true' : undefined} onClick={() => void test(service)}>
          {testing ? t('Testing…') : t('Test connection')}
        </button>
        {children}
      </div>
    </div>
  );
}

/* ── Music · Lidarr ── */

function LidarrPanel({ m }: { m: Media }) {
  const info = useStore(infoStore).data;
  const l = m.lidarr;
  const set = (patch: Partial<Media['lidarr']>, delay?: number) => setMedia((x) => ({ ...x, lidarr: { ...x.lidarr, ...patch } }), delay);
  const quality = info?.qualityProfiles ?? [];
  const meta = info?.metadataProfiles ?? [];
  const roots = info?.rootFolders ?? [];
  const root = roots.find((f) => f.path === l.rootFolder) ?? roots[0];
  const ready = !!l.url && !!l.apiKeyHint;
  const noLists = !quality.length;
  const disabledSelect = (label: string) => <SelectField value="" options={[{ value: '', label }]} onChange={() => { /* nothing to pick yet */ }} minWidth={280} />;
  return (
    <section aria-labelledby="lid-h" class="panel">
      <Head id="lid-h" icon="notes" title={t('Music · Lidarr')} sub={t('Artists and albums from MusicBrainz')} />
      <UrlRow service="lidarr" value={l.url} onCommit={(v) => set({ url: v }, 0)} placeholder="http://lidarr.local:8686" />
      <KeyRow service="lidarr" hint={l.apiKeyHint} name={t('API key')} help={t('Lidarr › Settings › General · stored on the server, never sent to browsers')} />
      <SettingRow name={t('Quality profile')} help={t('Used for every request')}>
        {noLists ? disabledSelect(ready ? t('Test the connection first') : t('Connect Lidarr first'))
          : <SelectField label={t('Quality profile')} minWidth={280} value={quality.find((q) => q.id === l.qualityProfileId)?.id ?? quality[0]!.id} options={quality.map((q) => ({ value: q.id, label: q.name }))} onChange={(v) => set({ qualityProfileId: v })} />}
      </SettingRow>
      <SettingRow name={t('Release types')} help={t('Lidarr metadata profile')}>
        {!meta.length ? disabledSelect(ready ? t('Test the connection first') : t('Connect Lidarr first'))
          : <SelectField label={t('Release types')} minWidth={280} value={meta.find((q) => q.id === l.metadataProfileId)?.id ?? meta[0]!.id} options={meta.map((q) => ({ value: q.id, label: q.name }))} onChange={(v) => set({ metadataProfileId: v })} />}
      </SettingRow>
      <SettingRow name={t('Root folder')} help={root?.library ? t('Matches the Jellyfin library {library}', { library: root.library }) : roots.length ? t('No Jellyfin library uses this folder') : t('Where Lidarr puts new artists')}>
        {!roots.length ? disabledSelect(ready ? t('Lidarr has no root folder') : t('Connect Lidarr first'))
          : <SelectField label={t('Root folder')} minWidth={280} value={root!.path} options={roots.map((f) => ({ value: f.path, label: f.library ? `${f.path} → ${f.library}` : f.path }))} onChange={(v) => set({ rootFolder: v })} />}
      </SettingRow>
      <SettingRow name={t('When someone asks for a whole artist')} help={t('They can still pick albums in the request window')}>
        <SelectField label={t('Whole artist requests')} minWidth={280} value={l.wholeArtist} options={[{ value: 'all', label: t('Monitor existing and future albums') }, { value: 'future', label: t('Monitor future albums only') }]} onChange={(v) => set({ wholeArtist: v })} />
      </SettingRow>
      <SettingRow name={t('Search as soon as it’s approved')} help={t('Otherwise Lidarr waits for its next RSS sync')}>
        <Switch label={t('Search as soon as it’s approved')} checked={l.searchOnApprove} onChange={(v) => set({ searchOnApprove: v })} />
      </SettingRow>
      <ConnRow service="lidarr" ready={ready}>
        {l.url
          ? <a class="btn btn-secondary" href={l.url} target="_blank" rel="noreferrer">{t('Open Lidarr')}<Icon name="external" size={16} /></a>
          : <button type="button" class="btn btn-secondary" disabled>{t('Open Lidarr')}<Icon name="external" size={16} /></button>}
      </ConnRow>
    </section>
  );
}

/* ── Books, audiobooks and comics ── */

const APPS: { id: BookApp; name: string; help: string }[] = [
  { id: 'shelfarr', name: 'Shelfarr', help: 'Ebooks and audiobooks. Searches your indexers, then direct sources, and picks the best format.' },
  { id: 'librarr', name: 'Librarr', help: 'Ebooks, audiobooks and manga. Monitors authors and finishes series on its own.' },
  { id: 'readarr', name: 'Readarr API', help: 'For Bookshelf, Chaptarr and other Readarr forks. Readarr itself was retired by the Servarr team in 2025.' }
];

const ROUTES: { key: keyof Media['routes']; format: 'ebook' | 'audiobook' | 'comic'; name: string; help: string; plural: string }[] = [
  { key: 'ebooks', format: 'ebook', name: 'Ebooks', help: 'EPUB, AZW3, PDF', plural: 'ebooks' },
  { key: 'audiobooks', format: 'audiobook', name: 'Audiobooks', help: 'M4B, MP3 folders', plural: 'audiobooks' },
  { key: 'comics', format: 'comic', name: 'Comics and manga', help: 'CBZ, CBR', plural: 'comics and manga' }
];

function usedFor(m: Media, app: BookApp): string {
  const formats = ROUTES.filter((r) => m.routes[r.key] === app).map((r) => t(r.plural));
  if (!formats.length) return '';
  const list = formats.length > 1 ? t('{a} and {b}', { a: formats.slice(0, -1).join(', '), b: formats[formats.length - 1]! }) : formats[0]!;
  return t('Used for {formats}', { formats: list });
}

function BooksPanel({ m }: { m: Media }) {
  const landing = useStore(infoStore).data?.landing ?? [];
  const toggle = (app: BookApp) => setMedia((x) => {
    const on = !x[app].enabled;
    const routes = { ...x.routes };
    // Turning an app off sends its formats elsewhere; turning one on takes formats nothing handles.
    for (const r of ROUTES) {
      if (!on && routes[r.key] === app) routes[r.key] = (APPS.find((a) => a.id !== app && x[a.id].enabled && (r.key !== 'comics' || a.id !== 'shelfarr'))?.id ?? 'off');
      if (on && routes[r.key] === 'off') routes[r.key] = app;
    }
    return { ...x, [app]: { ...x[app], enabled: on }, routes };
  }, 0);
  const enabled = APPS.filter((a) => m[a.id].enabled);
  return (
    <section aria-labelledby="bk-h" class="panel">
      <Head id="bk-h" icon="book" title={t('Books, audiobooks and comics')} sub={t('Turn on the apps you run, then pick one for each format')} />
      <div role="group" aria-label={t('Apps Seedy uses for books')} class="amr-apps">
        {APPS.map((a) => {
          const on = m[a.id].enabled;
          const note = on ? usedFor(m, a.id) || t('Not used yet') : t('Not set up');
          return (
            <button key={a.id} type="button" role="checkbox" aria-checked={on ? 'true' : 'false'} class={'amr-app' + (on ? ' is-on' : '')} onClick={() => toggle(a.id)}>
              <span class="amr-app-top">
                <span aria-hidden="true" class={'reqs-box is-22' + (on ? ' is-on' : '')}><Icon name="check" size={14} stroke={3.2} /></span>
                <span class="amr-app-name">{a.name}</span>
              </span>
              <span class="amr-app-help">{t(a.help)}</span>
              <span class={'amr-app-note' + (on && usedFor(m, a.id) ? ' is-on' : '')}>{note}</span>
            </button>
          );
        })}
      </div>
      <div role="table" aria-label={t('Where each format goes')} class="amr-routes">
        <div role="row" class="amr-route is-head">
          <span role="columnheader">{t('Format')}</span>
          <span role="columnheader">{t('Sent to')}</span>
          <span role="columnheader">{t('Lands in')}</span>
        </div>
        {ROUTES.map((r) => {
          const land = landing.find((l) => l.format === r.format);
          const options = [
            ...enabled.filter((a) => r.format !== 'comic' || a.id !== 'readarr').map((a) => ({ value: a.id as string, label: a.id === 'readarr' ? 'Readarr API' : a.name })),
            { value: 'off', label: t('Don’t allow') }
          ];
          const value = options.some((o) => o.value === m.routes[r.key]) ? m.routes[r.key] : 'off';
          return (
            <div role="row" class="amr-route" key={r.key}>
              <span role="cell" class="amr-route-cell">
                <span class="amr-route-name">{t(r.name)}</span>
                <span class="amr-route-help">{r.help}</span>
              </span>
              <span role="cell" class="amr-route-cell is-select">
                <SelectField label={t('{format} are sent to', { format: t(r.name) })} value={value} options={options} onChange={(v) => setMedia((x) => ({ ...x, routes: { ...x.routes, [r.key]: v as MediaService | 'off' } }))} />
              </span>
              <span role="cell" class="amr-route-cell">
                <span class="amr-route-lib">{value === 'off' ? t('Not requested') : land?.library ?? t('No books library yet')}</span>
                {value !== 'off' && land?.path && <span class="amr-route-help">{land.path}</span>}
              </span>
            </div>
          );
        })}
      </div>
    </section>
  );
}

const APP_PANEL: Record<BookApp, { icon: IconName; sub: string; key: string; keyHelp: string; placeholder: string }> = {
  shelfarr: { icon: 'book', sub: 'Ebooks and audiobooks', key: 'API token', keyHelp: 'Shelfarr › Profile › API tokens · needs the requests scope', placeholder: 'http://shelfarr.local:5056' },
  librarr: { icon: 'grid', sub: 'Comics and manga', key: 'API key', keyHelp: 'Sent as X-Api-Key', placeholder: 'http://librarr.local:5050' },
  readarr: { icon: 'book', sub: 'Bookshelf, Chaptarr and other Readarr forks', key: 'API key', keyHelp: 'Settings › General in the app', placeholder: 'http://bookshelf.local:8787' }
};

function BookAppPanel({ service, m }: { service: BookApp; m: Media }) {
  const p = APP_PANEL[service];
  const app = m[service];
  const name = service === 'readarr' ? 'Readarr API' : APP_NAMES[service];
  const used = ROUTES.filter((r) => m.routes[r.key] === service).map((r) => t(r.plural));
  const usedLine = used.length > 1 ? t('{a} and {b}', { a: used.slice(0, -1).join(', '), b: used[used.length - 1]! }) : used[0];
  return (
    <section aria-labelledby={service + '-h'} class="panel">
      <Head id={service + '-h'} icon={p.icon} title={name} sub={usedLine ? usedLine.charAt(0).toLocaleUpperCase() + usedLine.slice(1) : t(p.sub)} />
      <UrlRow service={service} value={app.url} onCommit={(v) => setMedia((x) => ({ ...x, [service]: { ...x[service], url: v } }), 0)} placeholder={p.placeholder} />
      <KeyRow service={service} hint={app.apiKeyHint} name={t(p.key)} help={t(p.keyHelp)} />
      <ConnRow service={service} ready={!!app.url && !!app.apiKeyHint} />
    </section>
  );
}

/* ── Approvals and limits ── */

const MUSIC_LIMITS = [-1, 1, 2, 3, 5, 10, 20, 0];
const BOOK_LIMITS = [-1, 2, 4, 6, 8, 12, 20, 0];
const musicLimit = (n: number) => (n < 0 ? t('No limit') : n === 0 ? t('Can’t request') : n === 1 ? t('1 album a week') : t('{n} albums a week', { n }));
const bookLimit = (n: number) => (n < 0 ? t('No limit') : n === 0 ? t('Can’t request') : n === 1 ? t('1 book a month') : t('{n} books a month', { n }));

function LimitCell({ value, options, word, label, onChange, suffix }: { value: number; options: number[]; word: (n: number) => string; label: string; onChange: (n: number) => void; suffix?: string }) {
  return (
    <MenuButton
      width={220}
      button={({ ref, open, toggle }) => (
        <button ref={ref} type="button" class={'amr-limit' + (value === 0 ? ' is-off' : '')} aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} aria-label={label + ': ' + word(value)} onClick={toggle}>
          {word(value)}{suffix && value !== 0 ? suffix : ''}
          <Icon name="chevronDown" size={14} stroke={2.4} class="amr-limit-chev" />
        </button>
      )}
    >
      {(close) => options.map((n) => <MenuItem key={n} label={word(n)} pressed={n === value} onSelect={() => { close(); onChange(n); }} />)}
    </MenuButton>
  );
}

function LimitsPanel() {
  const { data, error } = useStore(profilesStore);
  const change = async (p: AdminMediaProfile, patch: Partial<AdminMediaProfile>) => {
    profilesStore.set((s) => ({ ...s, data: s.data?.map((x) => (x.userId === p.userId ? { ...x, ...patch } : x)) }));
    try {
      profilesStore.set({ data: await adminMediaProfile(p.userId, patch) });
    } catch (e) {
      profilesStore.set((s) => ({ ...s, data: s.data?.map((x) => (x.userId === p.userId ? p : x)) }));
      toast(`${t('Couldn’t change {name}.', { name: p.name })} ${(e as Error).message || ''}`.trim(), 'error');
    }
  };
  const role = (p: AdminMediaProfile) => (p.isAdmin ? t('Administrator') : p.maxParentalRating ? t('Maturity limit: {rating}', { rating: p.maxParentalRating }) : t('User'));
  return (
    <section aria-labelledby="lim-h" class="panel is-gap" style={{ gap: '14px' }}>
      <div class="panel-head">
        <h2 id="lim-h" class="panel-title">{t('Approvals and limits')}</h2>
        <p class="panel-text">{t('Seedy handles approvals for music and books, then sends only approved requests. Librarr’s own approval queue is skipped.')}</p>
      </div>
      {!data && error ? (
        <div class="admin-inline-error" role="alert"><span class="dot is-danger" />{t('Couldn’t load the profiles.')} {error.message}</div>
      ) : !data ? (
        <div class="table-skel" aria-busy="true">{[0, 1, 2, 3].map((i) => <span key={i} class="skel" />)}</div>
      ) : (
        <div class="table-scroll">
          <div role="table" aria-label={t('Request limits')} class="table amr-limits">
            <div role="row" class="table-row">
              <span role="columnheader" class="table-head">{t('Profile')}</span>
              <span role="columnheader" class="table-head">{t('Music')}</span>
              <span role="columnheader" class="table-head">{t('Books')}</span>
              <span role="columnheader" class="table-head is-right">{t('Auto-approve')}</span>
            </div>
            {data.map((p, i) => {
              const kids = !!p.maxParentalRating && !p.isAdmin;
              return (
                <div role="row" class="table-row" key={p.userId}>
                  <div role="cell" class="table-cell">
                    <span class="who">
                      <ProfileAvatar userId={p.userId} name={p.name} imageTag={p.imageTag} index={i} />
                      <span class="who-text"><span class="who-name">{p.name}</span><span class="who-sub">{role(p)}</span></span>
                    </span>
                  </div>
                  <div role="cell" class="table-cell">
                    <LimitCell value={p.musicPerWeek} options={MUSIC_LIMITS} word={musicLimit} label={t('{name}’s music limit', { name: p.name })} onChange={(n) => void change(p, { musicPerWeek: n })} />
                  </div>
                  <div role="cell" class="table-cell">
                    <LimitCell value={p.booksPerMonth} options={BOOK_LIMITS} word={bookLimit} label={t('{name}’s book limit', { name: p.name })} onChange={(n) => void change(p, { booksPerMonth: n })} suffix={kids ? ' · ' + t('kids’ books only') : undefined} />
                  </div>
                  <div role="cell" class="table-cell is-flex is-right">
                    <span title={kids ? t('An admin checks every request from a profile with a maturity limit') : undefined}>
                      <Switch
                        label={t('Approve {name}’s requests automatically', { name: p.name })}
                        checked={p.autoApprove && !kids}
                        disabled={kids}
                        onChange={(v) => void change(p, { autoApprove: v })}
                      />
                    </span>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </section>
  );
}

/* ── When a download finishes ── */

function AfterPanel({ m }: { m: Media }) {
  const rows: { key: 'scanOnReady' | 'notifyOnReady' | 'showRequesters'; name: string; help: string }[] = [
    { key: 'scanOnReady', name: 'Scan the matching Jellyfin library', help: 'So it shows up in Seedy within a minute' },
    { key: 'notifyOnReady', name: 'Tell the person who asked', help: 'A notice on their Home screen when it’s ready' },
    { key: 'showRequesters', name: 'Show requests to everyone', help: 'Others see “Requested by Sam” on the cover' }
  ];
  return (
    <section aria-labelledby="after-h" class="panel">
      <div class="panel-head is-title-only"><h2 id="after-h" class="panel-title">{t('When a download finishes')}</h2></div>
      {rows.map((r, i) => (
        <SettingRow key={r.key} name={t(r.name)} help={t(r.help)} last={i === rows.length - 1}>
          <Switch label={t(r.name)} checked={m[r.key]} onChange={(v) => setMedia((x) => ({ ...x, [r.key]: v }))} />
        </SettingRow>
      ))}
    </section>
  );
}
