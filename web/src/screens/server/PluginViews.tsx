import { useEffect, useMemo, useState } from 'preact/hooks';
import { plural } from '../../lib/format';
import { t } from '../../lib/i18n';
import { createStore, useStore } from '../../lib/store';
import { Switch } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { Sheet, confirm, toast } from '../../ui/overlay';
import { getRepositories, installPackage, normId, type PackageInfo, reasonText, type RepositoryInfo, setRepositories } from './api';
import { cmpVersion, majorMinor, shortVersion } from './format';
import { catalogStore, categoryLabel, initials, installedStore, isJellyfinRepo, latestCompatible, loadCatalog, loadInstalled, setRepos, statusOf } from './plugins';
import { ListEmpty, ListError, ListSkeleton } from './advanced-parts';

/* ── Catalog: every plugin the enabled repositories offer, grouped by category. ── */

export function PluginCatalog({ server }: { server: string }) {
  const cat = useStore(catalogStore);
  const inst = useStore(installedStore);
  const [term, setTerm] = useState('');
  const [busy, setBusy] = useState<Record<string, boolean>>({});

  useEffect(() => { void loadCatalog(); }, []);

  const groups = useMemo(() => {
    const q = term.trim().toLowerCase();
    const list = (cat.packages ?? []).filter((p) => !q || [p.name, p.overview, p.description, p.owner, categoryLabel(p.category)].some((x) => x?.toLowerCase().includes(q)));
    const map = new Map<string, PackageInfo[]>();
    for (const p of list) {
      const k = categoryLabel(p.category);
      map.set(k, [...(map.get(k) ?? []), p]);
    }
    const other = categoryLabel();
    return [...map.entries()]
      .sort(([a], [b]) => (a === other ? 1 : b === other ? -1 : a.localeCompare(b)))
      .map(([label, items]) => ({ label, items: items.sort((a, b) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })) }));
  }, [cat.packages, term]);

  const install = async (p: PackageInfo) => {
    const v = latestCompatible(p, server);
    if (!v) return;
    setBusy((b) => ({ ...b, [p.guid]: true }));
    try {
      await installPackage(p.name, p.guid, v.version, v.repositoryUrl);
      toast(t('{plugin} installed. Restart the server to load it.', { plugin: p.name }));
      await loadInstalled();
    } catch (e) {
      toast(t('Couldn’t install {plugin}. {error}', { plugin: p.name, error: reasonText(e) }), 'error', 6000);
    } finally {
      setBusy((b) => { const n = { ...b }; delete n[p.guid]; return n; });
    }
  };

  const repoCount = new Set((cat.packages ?? []).flatMap((p) => p.versions.map((v) => v.repositoryUrl))).size;

  return (
    <>
      <div class="adv-catalog-tools">
        <label class="search-field">
          <Icon name="search" size={16} />
          <input type="search" value={term} placeholder={t('Search plugins')} aria-label={t('Search the catalog')} onInput={(e) => setTerm((e.currentTarget as HTMLInputElement).value)} />
        </label>
        {cat.packages && (
          <span class="adv-catalog-count">
            {term.trim()
              ? plural(groups.reduce((n, g) => n + g.items.length, 0), 'match', 'matches')
              : t('{plugins} from {repositories}', { plugins: plural(cat.packages.length, 'plugin'), repositories: plural(repoCount, 'repository', 'repositories') })}
          </span>
        )}
      </div>

      {!cat.packages && cat.error ? (
        <ListError message={t('Couldn’t load the catalog.')} error={cat.error} onRetry={() => void loadCatalog(true)} />
      ) : !cat.packages ? (
        <section class="adv-group">
          <span class="adv-group-label"><span class="skel skel-line" style={{ display: 'block', width: '120px', height: '13px' }} /></span>
          <ListSkeleton count={5} />
        </section>
      ) : !groups.length ? (
        <ListEmpty title={term.trim() ? t('Nothing matches “{term}”', { term: term.trim() }) : t('The catalog is empty')}>
          {term.trim() ? t('Try a shorter search, or add the repository that offers the plugin.') : t('Turn on a repository under Repositories to see its plugins here.')}
        </ListEmpty>
      ) : (
        groups.map((g) => (
          <section key={g.label} class="adv-group" aria-label={g.label}>
            <h2 class="adv-group-label">{g.label}</h2>
            <div class="adv-list">
              {g.items.map((p) => {
                const installed = inst.plugins?.find((x) => normId(x.Id) === normId(p.guid));
                const v = latestCompatible(p, server);
                const newest = [...p.versions].sort((a, b) => cmpVersion(b.version, a.version))[0];
                const repo = v ?? newest;
                const third = repo && !isJellyfinRepo(repo.repositoryUrl) ? repo.repositoryName : '';
                const st = installed ? statusOf(installed.Status) : null;
                return (
                  <div key={p.guid} class="adv-prow">
                    <span aria-hidden="true" class="adv-tile">{initials(p.name)}</span>
                    <div class="adv-pbody">
                      <span class="adv-pname-line">
                        <span class="adv-pname">{p.name}</span>
                        <span class="adv-pver">{[v ? shortVersion(v.version) : newest ? t('Built for {version}', { version: majorMinor(newest.targetAbi) }) : '', third].filter(Boolean).join(' · ')}</span>
                      </span>
                      {(p.overview || p.description) && <span class="adv-pdesc">{(p.overview || p.description || '').trim()}</span>}
                      {!v && newest && <span class="adv-pnote">{t('No version for Jellyfin {version} yet, so it can’t be installed.', { version: majorMinor(server) })}</span>}
                    </div>
                    {installed && st ? (
                      <span class="adv-status"><span class={'dot ' + st.dot} />{installed.Status === 'Active' ? t('Installed') : st.label}</span>
                    ) : v ? (
                      <div class="adv-pactions">
                        <button type="button" class="btn btn-primary adv-btn40" disabled={!!busy[p.guid]} aria-label={t('Install {plugin} {version}', { plugin: p.name, version: shortVersion(v.version) })} onClick={() => void install(p)}>
                          {busy[p.guid] ? t('Installing…') : t('Install')}
                        </button>
                      </div>
                    ) : null}
                  </div>
                );
              })}
            </div>
          </section>
        ))
      )}
      <p class="adv-foot">{t('Plugins run inside the server with full access to it. Install from repositories you trust; a restart loads new plugins.')}</p>
    </>
  );
}

/* ── Repositories ── */

const addOpenStore = createStore(false);

export function AddRepositoryButton() {
  return (
    <button type="button" class="btn btn-primary is-sm" onClick={() => addOpenStore.set(true)}>
      <Icon name="plus" size={15} stroke={2.6} />
      {t('Add repository')}
    </button>
  );
}

const JELLYFIN_STABLE: RepositoryInfo = { Name: 'Jellyfin Stable', Url: 'https://repo.jellyfin.org/files/plugin/manifest.json', Enabled: true };

export function PluginRepositories() {
  const inst = useStore(installedStore);
  const addOpen = useStore(addOpenStore);
  const [error, setError] = useState<Error | null>(null);
  const [saving, setSaving] = useState(false);
  const repos = inst.repos;

  const reload = () => {
    setError(null);
    getRepositories().then(setRepos, (e: Error) => setError(e));
  };
  useEffect(() => { if (!repos) reload(); }, []);

  const save = async (next: RepositoryInfo[], done: string) => {
    const prev = repos ?? [];
    setRepos(next);
    setSaving(true);
    try {
      await setRepositories(next);
      toast(done);
      void loadCatalog(true);
      return true;
    } catch (e) {
      setRepos(prev);
      toast(t('Couldn’t save the repositories. {error}', { error: reasonText(e) }), 'error', 6000);
      return false;
    } finally {
      setSaving(false);
    }
  };

  const remove = async (r: RepositoryInfo) => {
    const ok = await confirm({
      title: t('Remove {repository}?', { repository: r.Name }),
      text: t('Its plugins leave the catalog and stop getting updates. Plugins you already installed from it stay installed.'),
      action: t('Remove'),
      danger: true
    });
    if (ok) await save((repos ?? []).filter((x) => x !== r), t('{repository} removed', { repository: r.Name }));
  };

  return (
    <>
      {!repos && error ? (
        <ListError message={t('Couldn’t load the repositories.')} error={error} onRetry={reload} />
      ) : !repos ? (
        <ListSkeleton count={2} />
      ) : !repos.length ? (
        <div class="adv-list">
          <div class="adv-prow">
            <div class="adv-pbody">
              <span class="adv-empty-title">{t('No repositories')}</span>
              <span class="adv-empty-text">{t('The catalog stays empty and plugins don’t get updates until you add one.')}</span>
            </div>
            <button type="button" class="btn btn-secondary is-sm" disabled={saving} onClick={() => void save([JELLYFIN_STABLE], t('Jellyfin’s repository added'))}>
              {t('Add Jellyfin’s repository')}
            </button>
          </div>
        </div>
      ) : (
        <div class="adv-list">
          {repos.map((r, i) => (
            <div key={r.Url + i} class="adv-prow">
              <span aria-hidden="true" class="adv-tile">{initials(r.Name || 'R')}</span>
              <div class="adv-pbody">
                <span class="adv-pname-line">
                  <span class="adv-pname">{r.Name}</span>
                  <span class="adv-pver">{isJellyfinRepo(r.Url) ? 'Jellyfin' : t('Third party')}</span>
                </span>
                <span class="adv-repo-url">{r.Url}</span>
              </div>
              <div class="adv-repo-actions">
                <Switch
                  label={t('Use {repository}', { repository: r.Name })}
                  checked={r.Enabled}
                  disabled={saving}
                  onChange={(on) => void save(repos.map((x) => (x === r ? { ...x, Enabled: on } : x)), t(on ? '{repository} turned on' : '{repository} turned off', { repository: r.Name }))}
                />
                <button type="button" class="btn icon-btn is-bare is-40" aria-label={t('Remove {repository}', { repository: r.Name })} disabled={saving} onClick={() => void remove(r)}>
                  <Icon name="trash" size={18} />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
      <p class="adv-foot">{t('Jellyfin checks each repository for new versions once a day. Plugins from other repositories aren’t reviewed by the Jellyfin team.')}</p>
      <AddRepositorySheet open={addOpen} onClose={() => addOpenStore.set(false)} onAdd={(r) => save([...(repos ?? []), r], t('{repository} added', { repository: r.Name }))} existing={repos ?? []} />
    </>
  );
}

function AddRepositorySheet({ open, onClose, onAdd, existing }: {
  open: boolean; onClose: () => void; onAdd: (r: RepositoryInfo) => Promise<boolean>; existing: RepositoryInfo[];
}) {
  const [name, setName] = useState('');
  const [link, setLink] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (open) { setName(''); setLink(''); setErr(''); } }, [open]);

  const submit = async (e: Event) => {
    e.preventDefault();
    const n = name.trim();
    const u = link.trim();
    if (!n) { setErr(t('Give the repository a name.')); return; }
    if (!/^https?:\/\/\S+$/i.test(u)) { setErr(t('Enter the manifest address, starting with https://')); return; }
    if (existing.some((r) => r.Url.trim().toLowerCase() === u.toLowerCase())) { setErr(t('That repository is already on the list.')); return; }
    setBusy(true);
    const ok = await onAdd({ Name: n, Url: u, Enabled: true });
    setBusy(false);
    if (ok) onClose();
  };

  return (
    <Sheet open={open} onClose={onClose} label={t('Add repository')}>
      <form class="adv-sheet-fields" onSubmit={(e) => void submit(e)}>
        <div class="sheet-head"><h2 class="sheet-title">{t('Add repository')}</h2></div>
        <p class="sheet-text">{t('Paste the address of the repository’s manifest.json. Its author lists it with their install instructions.')}</p>
        <label class="adv-sheet-field">
          <span>{t('Name')}</span>
          <input class="field" type="text" value={name} maxLength={80} placeholder="Intro Skipper" onInput={(e) => setName((e.currentTarget as HTMLInputElement).value)} />
        </label>
        <label class="adv-sheet-field">
          <span>{t('Manifest address')}</span>
          <input class="field" type="url" value={link} placeholder="https://example.com/manifest.json" spellcheck={false} onInput={(e) => setLink((e.currentTarget as HTMLInputElement).value)} />
        </label>
        {err && <p class="form-error" role="alert"><Icon name="alert" size={16} />{err}</p>}
        <div class="sheet-actions">
          <button type="button" class="btn btn-ghost is-light" onClick={onClose}>{t('Cancel')}</button>
          <button type="submit" class="btn btn-primary is-sm" disabled={busy}>{busy ? t('Adding…') : t('Add repository')}</button>
        </div>
      </form>
    </Sheet>
  );
}
