import { useEffect, useMemo, useState } from 'preact/hooks';
import { href, navigate, setQuery, useLocation } from '../../lib/router';
import { useStore } from '../../lib/store';
import { t, tn } from '../../lib/i18n';
import { Icon } from '../../ui/icons';
import { MenuButton, MenuItem, confirm, toast } from '../../ui/overlay';
import { AdminLayout } from '../admin/AdminLayout';
import { configPageUrl, disablePlugin, enablePlugin, getPluginManifest, installPackage, reasonText, uninstallPlugin } from './api';
import { majorMinor } from './format';
import { catalogStore, describe, initials, installedStore, loadCatalog, loadInstalled, needsRestart, type PluginView, sourceLabel, statusOf, versionLabel } from './plugins';
import { ListEmpty, ListError, ListSkeleton } from './advanced-parts';
import { AddRepositoryButton, PluginCatalog, PluginRepositories } from './PluginViews';
import { plural } from '../../lib/format';
import './advanced.css';

type View = 'installed' | 'catalog' | 'repositories';
/* Tab labels in English, translated where they are shown. */
const VIEWS: { id: View; label: string }[] = [
  { id: 'installed', label: 'Installed' },
  { id: 'catalog', label: 'Catalog' },
  { id: 'repositories', label: 'Repositories' }
];

/* Plugins: what's installed, the repositories' catalog, and the repositories themselves. */
export function ServerPlugins() {
  const loc = useLocation();
  const raw = loc.query.get('view');
  const view: View = raw === 'catalog' || raw === 'repositories' ? raw : 'installed';
  const inst = useStore(installedStore);
  const cat = useStore(catalogStore);
  const [busy, setBusy] = useState<Record<string, string>>({});
  const [updatingAll, setUpdatingAll] = useState(false);

  useEffect(() => {
    void loadInstalled();
    void loadCatalog();
  }, []);

  const server = inst.serverVersion ?? '';
  const rows = useMemo(
    () => {
      /* As on the board: Seedy, then plugins with an update, the rest, and the ones that won't load last. */
      const rank = (v: PluginView) => (v.self ? 0 : v.update ? 1 : v.wontLoad ? 3 : 2);
      return (inst.plugins ?? []).map((p) => describe(p, cat.packages, inst.pages, server)).sort((a, b) => rank(a) - rank(b));
    },
    [inst.plugins, inst.pages, cat.packages, server]
  );
  const updates = rows.filter((r) => r.update);
  const wontLoad = rows.filter((r) => r.wontLoad).length;
  const restart = rows.filter((r) => needsRestart(r.plugin)).length;

  const setRowBusy = (id: string, label?: string) => setBusy((b) => {
    const next = { ...b };
    if (label) next[id] = label; else delete next[id];
    return next;
  });

  const update = async (v: PluginView, quiet = false) => {
    if (!v.update || !v.pkg) return false;
    setRowBusy(v.plugin.Id, t('Updating…'));
    try {
      await installPackage(v.pkg.name, v.pkg.guid, v.update.version, v.update.repositoryUrl);
      if (!quiet) toast(t('{plugin} updated. Restart the server to finish.', { plugin: v.plugin.Name }));
      return true;
    } catch (e) {
      toast(t('Couldn’t update {plugin}. {error}', { plugin: v.plugin.Name, error: reasonText(e) }), 'error', 6000);
      return false;
    } finally {
      setRowBusy(v.plugin.Id);
    }
  };

  const updateAll = async () => {
    setUpdatingAll(true);
    let done = 0;
    for (const v of updates) if (await update(v, true)) done++;
    setUpdatingAll(false);
    if (done) toast(tn(done, '{n} plugin updated. Restart the server to finish.', '{n} plugins updated. Restart the server to finish.'));
    await loadInstalled();
  };

  const lede = !inst.plugins
    ? (inst.error ? t('Couldn’t read the installed plugins.') : t('Reading the installed plugins…'))
    : [
      t('{n} installed', { n: inst.plugins.length }),
      cat.packages ? (updates.length ? plural(updates.length, 'update') : t('all up to date')) : null,
      wontLoad ? t("{n} won't load on Jellyfin {version}", { n: wontLoad, version: majorMinor(server) }) : null
    ].filter(Boolean).join(' · ');

  const actions = view === 'installed'
    ? (updates.length > 0 && (
      <button type="button" class={'btn btn-primary is-sm' + (updatingAll ? ' is-busy' : '')} disabled={updatingAll} onClick={() => void updateAll()}>
        {updatingAll ? t('Updating…') : t('Update all')}
      </button>
    ))
    : view === 'repositories' ? <AddRepositoryButton /> : null;

  return (
    <AdminLayout page="plugins" title={t('Plugins')} lede={<span class="adv-tight">{lede}</span>} wide actions={actions || undefined}>
      <nav aria-label={t('Plugin views')} class="tab-row adv-tabs">
        {VIEWS.map((tab) => (
          <a
            key={tab.id}
            href={href('/admin/plugins' + (tab.id === 'installed' ? '' : '?view=' + tab.id))}
            class="tab"
            aria-current={view === tab.id ? 'page' : undefined}
            onClick={(e) => {
              if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
              e.preventDefault();
              setQuery({ view: tab.id === 'installed' ? null : tab.id });
            }}
          >
            {t(tab.label)}{tab.id === 'repositories' && inst.repos ? ` · ${inst.repos.length}` : ''}
          </a>
        ))}
      </nav>

      {restart > 0 && (
        <a class="adv-notice" href={href('/admin/server')} onClick={(e) => { if (e.button === 0 && !e.metaKey && !e.ctrlKey) { e.preventDefault(); navigate('/admin/server'); } }}>
          <span class="dot is-pending" />
          <span class="adv-notice-text"><b>{t('Restart the server to finish.')}</b> {tn(restart, 'One plugin change takes effect after a restart.', '{n} plugin changes take effect after a restart.')}</span>
          <span class="adv-notice-link">{t('Open Overview')}</span>
        </a>
      )}

      {view === 'installed' && (
        <>
          <InstalledList rows={rows} server={server} busy={busy} onUpdate={(v) => void update(v).then((ok) => { if (ok) void loadInstalled(); })} setBusy={setRowBusy} />
          <p class="adv-foot">{t("A plugin's own settings page comes from the plugin itself and opens in Jellyfin's web app.")}</p>
        </>
      )}
      {view === 'catalog' && <PluginCatalog server={server} />}
      {view === 'repositories' && <PluginRepositories />}
    </AdminLayout>
  );
}

function InstalledList({ rows, server, busy, onUpdate, setBusy }: {
  rows: PluginView[]; server: string; busy: Record<string, string>; onUpdate: (v: PluginView) => void; setBusy: (id: string, label?: string) => void;
}) {
  const inst = useStore(installedStore);
  if (!inst.plugins && inst.error) return <ListError message={t('Couldn’t load the plugins.')} error={inst.error} onRetry={() => void loadInstalled()} />;
  if (!inst.plugins) return <ListSkeleton count={6} />;
  if (!rows.length) return <ListEmpty title={t('No plugins installed')}>{t('Add metadata providers, subtitle sources and more from the Catalog.')}</ListEmpty>;
  return (
    <div class="adv-list">
      {rows.map((v) => <PluginRow key={v.plugin.Id} v={v} server={server} busy={busy[v.plugin.Id]} onUpdate={onUpdate} setBusy={setBusy} />)}
    </div>
  );
}

const abiCache = new Map<string, string>();

function PluginRow({ v, server, busy, onUpdate, setBusy }: {
  v: PluginView; server: string; busy?: string; onUpdate: (v: PluginView) => void; setBusy: (id: string, label?: string) => void;
}) {
  const p = v.plugin;
  const [abi, setAbi] = useState<string | undefined>(abiCache.get(p.Id));
  /* "Built for 10.11": a plugin that won't load says which Jellyfin it targets in its manifest. */
  useEffect(() => {
    if (!v.wontLoad || abi) return;
    const fromCatalog = v.pkg?.versions.find((x) => x.version === p.Version)?.targetAbi;
    if (fromCatalog) { abiCache.set(p.Id, fromCatalog); setAbi(fromCatalog); return; }
    getPluginManifest(p.Id).then((m) => { if (m?.TargetAbi) { abiCache.set(p.Id, m.TargetAbi); setAbi(m.TargetAbi); } }, () => { /* keep the version */ });
  }, [v.wontLoad, p.Id]);

  const st = statusOf(p.Status);
  const mm = majorMinor(server);
  const note = v.wontLoad
    ? (abi && majorMinor(abi) !== mm
      ? t('Plugins built for {abi} don’t load on {version}. Update it once its author ships a {version} build, or uninstall it.', { abi: majorMinor(abi), version: mm })
      : t('This plugin failed to load on Jellyfin {version}. Check the logs, update it, or uninstall it.', { version: mm }))
    : '';

  const toggle = async (on: boolean) => {
    setBusy(p.Id, on ? t('Enabling…') : t('Disabling…'));
    try {
      await (on ? enablePlugin(p) : disablePlugin(p));
      toast(on ? t('{plugin} enabled. Restart the server to load it.', { plugin: p.Name }) : t('{plugin} disabled. Restart the server to unload it.', { plugin: p.Name }));
      await loadInstalled();
    } catch (e) {
      toast(t(on ? 'Couldn’t enable {plugin}. {error}' : 'Couldn’t disable {plugin}. {error}', { plugin: p.Name, error: reasonText(e) }), 'error', 6000);
    } finally {
      setBusy(p.Id);
    }
  };

  const uninstall = async () => {
    const ok = await confirm({
      title: t('Uninstall {plugin}?', { plugin: p.Name }),
      text: t('Jellyfin removes {plugin} the next time the server restarts. Its saved settings stay on the server in case you install it again.', { plugin: p.Name }),
      action: t('Uninstall'),
      danger: true
    });
    if (!ok) return;
    setBusy(p.Id, t('Uninstalling…'));
    try {
      await uninstallPlugin(p);
      toast(t('{plugin} will be removed when the server restarts.', { plugin: p.Name }));
      await loadInstalled();
    } catch (e) {
      toast(t('Couldn’t uninstall {plugin}. {error}', { plugin: p.Name, error: reasonText(e) }), 'error', 6000);
    } finally {
      setBusy(p.Id);
    }
  };

  const canToggle = !v.self && p.Status !== 'Deleted';
  const isOff = p.Status === 'Disabled';
  const canUninstall = !v.self && p.CanUninstall && p.Status !== 'Deleted';

  return (
    <div class="adv-prow">
      <span aria-hidden="true" class={'adv-tile' + (v.self ? ' is-self' : '')}>{v.self ? 'S' : initials(p.Name)}</span>
      <div class="adv-pbody">
        <span class="adv-pname-line">
          <span class="adv-pname" title={p.Description || undefined}>{p.Name}</span>
          <span class="adv-pver" title={t('Version {version}', { version: p.Version })}>{versionLabel(v, server, abi)}</span>
        </span>
        <span class="adv-psource">{sourceLabel(v)}</span>
        {note && <span class="adv-pnote">{note}</span>}
      </div>
      <span class="adv-status"><span class={'dot ' + st.dot} />{busy ?? st.label}</span>
      <div class="adv-pactions">
        {v.update && (
          <button type="button" class="btn btn-primary adv-btn40" disabled={!!busy} aria-label={t('Update {plugin} to {version}', { plugin: p.Name, version: v.update.version })} onClick={() => onUpdate(v)}>
            {t('Update')}
          </button>
        )}
        {v.page && !v.self && (
          <a class="btn btn-secondary is-sm" href={configPageUrl(v.page.Name)} target="_blank" rel="noreferrer" aria-label={t('{plugin} settings (opens Jellyfin’s web app)', { plugin: p.Name })}>
            {t('Settings')}
          </a>
        )}
        <MenuButton
          width={240}
          button={({ ref, open, toggle: flip }) => (
            <button
              ref={ref}
              type="button"
              class="btn icon-btn is-bare is-40"
              aria-label={t('More actions for {plugin}', { plugin: p.Name })}
              aria-haspopup="menu"
              aria-expanded={open ? 'true' : 'false'}
              disabled={!!busy}
              onClick={flip}
            >
              <Icon name="more" size={20} />
            </button>
          )}
        >
          {(close) => (
            <>
              {v.self && <MenuItem label={t('Seedy settings')} sub={t('Branding, home screen, requests')} onSelect={() => { close(); navigate('/admin'); }} />}
              {v.self && <MenuItem label={t('Can’t be disabled here')} sub={t('Seedy is the app you’re using')} disabled />}
              {canToggle && (isOff
                ? <MenuItem label={t('Enable')} sub={t('Loads after a restart')} onSelect={() => { close(); void toggle(true); }} />
                : <MenuItem label={t('Disable')} sub={t('Unloads after a restart')} onSelect={() => { close(); void toggle(false); }} />)}
              {v.page && !v.self && <MenuItem label={t('Open settings')} trailing={<Icon name="external" size={16} />} href={configPageUrl(v.page.Name)} />}
              {canUninstall && <div class="menu-sep" />}
              {canUninstall && <MenuItem label={t('Uninstall')} danger onSelect={() => { close(); void uninstall(); }} />}
              {!canUninstall && !v.self && v.builtIn && <MenuItem label={t('Comes with Jellyfin')} sub={t('Built-in plugins can’t be uninstalled')} disabled />}
            </>
          )}
        </MenuButton>
      </div>
    </div>
  );
}

