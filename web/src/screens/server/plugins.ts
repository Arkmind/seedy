import { t } from '../../lib/i18n';
import { createStore } from '../../lib/store';
import { type ConfigPage, getConfigPages, getPackages, getPlugins, getRepositories, getSystemInfo, normId, type PackageInfo, type PackageVersion, type PluginInfo, type PluginStatus, type RepositoryInfo } from './api';
import { cmpVersion, majorMinor, shortVersion } from './format';
import { pluginUpdatesStore } from './status';

/* Installed plugins, the repository catalog and the repositories, cached for the session and shared by the
   three Plugins views (and the nav badge). */

export const SEEDY_PLUGIN_ID = 'b547483f57e9439e8a2a561828a524a6';

interface Installed { plugins?: PluginInfo[]; pages?: ConfigPage[]; repos?: RepositoryInfo[]; serverVersion?: string; error?: Error; loading: boolean }
export const installedStore = createStore<Installed>({ loading: false });

interface Catalog { packages?: PackageInfo[]; error?: Error; loading: boolean; at: number }
export const catalogStore = createStore<Catalog>({ loading: false, at: 0 });

let serverVersion: Promise<string> | null = null;
const getServerVersion = () => (serverVersion ??= getSystemInfo().then((i) => i.Version).catch(() => { serverVersion = null; return ''; }));

/* GET /Plugins can list an old and a new version of the same plugin after an update; keep the newest and
   remember that the change needs a restart. */
function dedupe(list: PluginInfo[]): PluginInfo[] {
  const byId = new Map<string, PluginInfo[]>();
  for (const p of list) {
    const k = normId(p.Id);
    byId.set(k, [...(byId.get(k) ?? []), p]);
  }
  const out: PluginInfo[] = [];
  for (const group of byId.values()) {
    const sorted = [...group].sort((a, b) => cmpVersion(b.Version, a.Version));
    const top = { ...sorted[0] };
    if (group.length > 1 && top.Status !== 'Disabled') top.Status = 'Restart';
    out.push(top);
  }
  return out.sort((a, b) => {
    // Seedy first, then by name, as on the board.
    if (normId(a.Id) === SEEDY_PLUGIN_ID) return -1;
    if (normId(b.Id) === SEEDY_PLUGIN_ID) return 1;
    return a.Name.localeCompare(b.Name, undefined, { sensitivity: 'base' });
  });
}

export async function loadInstalled() {
  installedStore.set((s) => ({ ...s, loading: true, error: undefined }));
  try {
    const [plugins, pages, repos, version] = await Promise.all([
      getPlugins(),
      getConfigPages().catch(() => [] as ConfigPage[]),
      getRepositories().catch(() => undefined),
      getServerVersion()
    ]);
    installedStore.set((s) => ({ plugins: dedupe(plugins), pages, repos: repos ?? s.repos, serverVersion: version, loading: false }));
    publishUpdates();
  } catch (error) {
    installedStore.set((s) => ({ ...s, error: error as Error, loading: false }));
  }
}

let catalogReq = 0;
export async function loadCatalog(force = false) {
  const c = catalogStore.get();
  if (!force && (c.loading || (c.packages && Date.now() - c.at < 10 * 60_000))) return;
  const seq = ++catalogReq;
  catalogStore.set((s) => ({ ...s, loading: true, error: undefined }));
  try {
    const packages = await getPackages();
    if (seq !== catalogReq) return;
    catalogStore.set({ packages, loading: false, at: Date.now() });
    publishUpdates();
  } catch (error) {
    if (seq !== catalogReq) return;
    catalogStore.set((s) => ({ ...s, error: error as Error, loading: false }));
  }
}

export function setRepos(repos: RepositoryInfo[]) {
  installedStore.set((s) => ({ ...s, repos }));
}

/* ── What each installed plugin's row says ── */

/* Plugins built for an older major version (10.11) don't load on 12. */
export function isCompatible(v: PackageVersion, server: string): boolean {
  if (!v.targetAbi) return true;
  const major = parseInt(v.targetAbi, 10);
  const serverMajor = parseInt(server, 10);
  if (!serverMajor) return true;
  return major >= serverMajor && cmpVersion(v.targetAbi, server) <= 0;
}

export function latestCompatible(pkg: PackageInfo, server: string): PackageVersion | undefined {
  return [...pkg.versions].filter((v) => isCompatible(v, server)).sort((a, b) => cmpVersion(b.version, a.version))[0];
}

export function isJellyfinRepo(url?: string): boolean {
  if (!url) return false;
  try {
    const host = new URL(url).hostname.toLowerCase();
    return host === 'jellyfin.org' || host.endsWith('.jellyfin.org');
  } catch {
    return false;
  }
}

export interface PluginView {
  plugin: PluginInfo;
  self: boolean;
  pkg?: PackageInfo;
  update?: PackageVersion;
  wontLoad: boolean;
  builtIn: boolean;
  page?: ConfigPage;
}

export function describe(p: PluginInfo, packages: PackageInfo[] | undefined, pages: ConfigPage[] | undefined, server: string): PluginView {
  const id = normId(p.Id);
  const pkg = packages?.find((x) => normId(x.guid) === id);
  const latest = pkg ? latestCompatible(pkg, server) : undefined;
  const update = latest && cmpVersion(latest.version, p.Version) > 0 && p.Status !== 'Deleted' ? latest : undefined;
  const own = (pages ?? []).filter((pg) => normId(pg.PluginId) === id);
  const page = own.find((pg) => pg.Name === p.Name) ?? own.find((pg) => pg.EnableInMainMenu) ?? own[0];
  return {
    plugin: p,
    self: id === SEEDY_PLUGIN_ID,
    pkg,
    update,
    wontLoad: p.Status === 'NotSupported' || p.Status === 'Malfunctioned',
    builtIn: !p.CanUninstall && id !== SEEDY_PLUGIN_ID,
    page
  };
}

/* "Update available", "Installed with 12.2", "Up to date", "Built for 10.11", or the version. */
export function versionLabel(v: PluginView, server: string, abi?: string): string {
  const ver = shortVersion(v.plugin.Version);
  if (v.self) return ver;
  if (v.wontLoad) return abi ? t('Built for {version}', { version: majorMinor(abi) }) : ver;
  if (v.update) return t('Update available');
  if (v.builtIn) return t('Installed with {version}', { version: majorMinor(server) || ver });
  if (v.pkg) return t('Up to date');
  return ver;
}

/* "This interface · Seedy repository", "Jellyfin", "Third party". */
export function sourceLabel(v: PluginView): string {
  const repo = v.pkg?.versions.find((x) => cmpVersion(x.version, v.plugin.Version) === 0) ?? v.pkg?.versions[0];
  if (v.self) return repo?.repositoryName ? t('This interface · {repo}', { repo: repo.repositoryName }) : t('This interface');
  if (v.builtIn || isJellyfinRepo(repo?.repositoryUrl)) return 'Jellyfin';
  return t('Third party');
}

export function statusOf(s: PluginStatus): { label: string; dot: '' | 'is-pending' | 'is-muted' | 'is-danger' } {
  switch (s) {
    case 'Active': return { label: t('Active'), dot: '' };
    case 'Restart': return { label: t('Restart required'), dot: 'is-pending' };
    case 'Disabled': return { label: t('Disabled'), dot: 'is-muted' };
    case 'NotSupported':
    case 'Malfunctioned': return { label: t('Won’t load'), dot: 'is-pending' };
    case 'Deleted': return { label: t('Removed on restart'), dot: 'is-muted' };
    default: return { label: t('Replaced on restart'), dot: 'is-muted' };
  }
}

export function needsRestart(p: PluginInfo) {
  return p.Status === 'Restart' || p.Status === 'Deleted' || p.Status === 'Superceded' || p.Status === 'Superseded';
}

export function initials(name: string): string {
  const words = name.replace(/[^\p{L}\p{N}\s]/gu, ' ').trim().split(/\s+/).filter(Boolean);
  if (!words.length) return '?';
  if (words.length > 1) return (words[0][0] + words[1][0]).toUpperCase();
  const w = words[0];
  const caps = w.slice(1).match(/\p{Lu}/u);
  if (caps && w[0] === w[0].toUpperCase()) return (w[0] + caps[0]).toUpperCase();
  return w.slice(0, 2).toUpperCase();
}

/* The nav badge: compatible updates for installed plugins. */
export function countUpdates(): number {
  const { plugins, serverVersion: server = '' } = installedStore.get();
  const { packages } = catalogStore.get();
  if (!plugins || !packages) return pluginUpdatesStore.get();
  return plugins.filter((p) => describe(p, packages, undefined, server).update).length;
}

function publishUpdates() {
  const { plugins } = installedStore.get();
  const { packages } = catalogStore.get();
  if (plugins && packages) pluginUpdatesStore.set(countUpdates());
}

/* English names for the catalog's categories; categoryLabel() translates them. Unknown categories keep the
   repository's own word. */
export const CATEGORY_LABELS: Record<string, string> = {
  MoviesAndShows: 'Movies and shows',
  LiveTV: 'Live TV',
  Anime: 'Anime',
  Books: 'Books',
  General: 'General',
  Administration: 'Administration',
  Music: 'Music',
  Subtitles: 'Subtitles',
  Metadata: 'Metadata',
  Authentication: 'Authentication',
  Channels: 'Channels',
  Notifications: 'Notifications'
};

export const categoryLabel = (c?: string) => (!c ? t('Other') : CATEGORY_LABELS[c] ? t(CATEGORY_LABELS[c]) : c.replace(/([a-z])([A-Z])/g, '$1 $2'));

/* Updates for the installed plugins (the Overview notice and the nav badge), by the same rules as the Plugins page. */
export function pluginUpdates(plugins: PluginInfo[], packages: PackageInfo[], server: string): { plugin: PluginInfo; version: string }[] {
  const installed = new Map<string, PluginInfo>();
  for (const pl of plugins) {
    if (pl.Status === 'Deleted') continue;
    const prev = installed.get(normId(pl.Id));
    if (!prev || cmpVersion(pl.Version, prev.Version) > 0) installed.set(normId(pl.Id), pl);
  }
  const out: { plugin: PluginInfo; version: string }[] = [];
  for (const [id, plugin] of installed) {
    const best = packages.filter((pk) => normId(pk.guid) === id).map((pk) => latestCompatible(pk, server)?.version)
      .reduce<string | undefined>((a, v) => (v && (!a || cmpVersion(v, a) > 0) ? v : a), undefined);
    if (best && cmpVersion(best, plugin.Version) > 0) out.push({ plugin, version: best });
  }
  return out;
}

export function countPluginUpdates(plugins: PluginInfo[], packages: PackageInfo[], server: string): number {
  const n = pluginUpdates(plugins, packages, server).length;
  pluginUpdatesStore.set(n);
  return n;
}

/* The Jellyfin version a plugin build targets, from the catalog entry for the installed version. */
export function pluginTargetAbi(plugin: PluginInfo, packages: PackageInfo[]): string | undefined {
  const versions = packages.filter((pk) => normId(pk.guid) === normId(plugin.Id)).flatMap((pk) => pk.versions ?? []);
  return versions.find((v) => cmpVersion(v.version, plugin.Version) === 0)?.targetAbi ?? undefined;
}
