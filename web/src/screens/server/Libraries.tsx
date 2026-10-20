import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { imageUrl } from '../../api/images';
import { fmtDayMonth, plural } from '../../lib/format';
import { formatNumber, t, tn } from '../../lib/i18n';
import { Link, navigate, setQuery, useLocation } from '../../lib/router';
import { useAsync } from '../../lib/store';
import { NumField, SavedNote, SelectField, Switch } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { MenuButton, Sheet, confirm, toast } from '../../ui/overlay';
import { AdminLayout, LoadError, PanelSkeletons } from '../admin/AdminLayout';
import { addLibrary, addLibraryPath, type AvailableOptions, countItems, type Country, type Culture, errText, type FullUser, getAvailableOptions, getCountries, getCultures, getNamedConfig, getServerConfig, getTasks, getUsers, getVirtualFolders, type LibraryOptions, type MetadataConfig, type NfoConfig, patchNamedConfig, patchServerConfig, refreshItem, removeLibrary, removeLibraryPath, saveLibraryOptions, scanAllLibraries, type ServerConfig, type TaskInfo, type TypeOptions, type VirtualFolder } from './api';
import { FolderPicker, InlineError, usePoll } from './parts';
import { atLabel, dayDiff, hhmm } from './format';

/* Server · Libraries: each library's folders and options (autosaved), plus the server-wide library settings. */

type Tab = 'libraries' | 'display' | 'metadata' | 'nfo';
const TABS: { id: Tab; label: string }[] = [
  { id: 'libraries', label: 'Libraries' },
  { id: 'display', label: 'Display' },
  { id: 'metadata', label: 'Metadata' },
  { id: 'nfo', label: 'NFO files' }
];

const TYPE_LABEL: Record<string, string> = {
  movies: 'Movies', tvshows: 'Shows', music: 'Music', musicvideos: 'Music videos', homevideos: 'Home videos', boxsets: 'Collections',
  books: 'Books', photos: 'Photos', playlists: 'Playlists', livetv: 'Live TV', mixed: 'Mixed'
};
const typeLabel = (type?: string) => t(TYPE_LABEL[type ?? 'mixed'] ?? 'Mixed');

/* What a library counts on its card: "248 items", "61 series". The forms are translation keys; "1 series" has its
   own {count} key so French can say "1 série". */
const COUNT_AS: Record<string, { types?: string[]; one: string; many: string }> = {
  movies: { types: ['Movie'], one: '{n} item', many: '{n} items' },
  tvshows: { types: ['Series'], one: '{count} series', many: '{n} series' },
  music: { types: ['MusicAlbum'], one: '{n} album', many: '{n} albums' },
  books: { types: ['Book'], one: '{n} book', many: '{n} books' },
  musicvideos: { types: ['MusicVideo'], one: '{n} item', many: '{n} items' },
  boxsets: { types: ['BoxSet'], one: '{n} collection', many: '{n} collections' }
};

/* "Scanned today at 18:30", "Scanned yesterday", "Scanned on 12 Oct". */
function scannedLabel(end?: string): string {
  if (!end) return t('Not scanned yet');
  const d = new Date(end);
  const days = dayDiff(d);
  if (days === 0) return t('Scanned today at {time}', { time: hhmm(d) });
  if (days === 1) return t('Scanned yesterday');
  return t('Scanned on {date}', { date: fmtDayMonth(d) });
}

const percent = (p: number) => formatNumber(Math.floor(p) / 100, { style: 'percent' });

export function ServerLibraries() {
  const loc = useLocation();
  const tab = (TABS.find((x) => x.id === loc.query.get('tab'))?.id ?? 'libraries') as Tab;
  const [folders, setFolders] = useState<VirtualFolder[] | null>(null);
  const [foldersError, setFoldersError] = useState<Error | null>(null);
  const [tasks, setTasks] = useState<TaskInfo[] | null>(null);
  const [adding, setAdding] = useState(false);

  const loadFolders = () => getVirtualFolders().then((f) => { setFolders(f); setFoldersError(null); }, (e: Error) => setFoldersError(e));
  const loadTasks = () => getTasks().then(setTasks, () => undefined);
  useEffect(() => { void loadFolders(); void loadTasks(); }, []);
  usePoll(() => { void loadFolders(); void loadTasks(); }, 5000);

  const scanTask = tasks?.find((x) => x.Key === 'RefreshLibrary');
  const scanning = scanTask?.State === 'Running';
  const lastScan = scanTask?.LastExecutionResult?.EndTimeUtc;

  const scanAll = async () => {
    try {
      await scanAllLibraries();
      toast(t('Scanning all libraries'));
      window.setTimeout(() => void loadTasks(), 600);
    } catch (e) {
      toast(t('Couldn’t start the scan. {error}', { error: errText(e) }), 'error');
    }
  };

  const n = folders?.length ?? 0;
  const lede = folders
    ? `${plural(n, 'library', 'libraries')} · ${scanning ? `${t('scanning now')}${scanTask?.CurrentProgressPercentage != null ? ` · ${percent(scanTask.CurrentProgressPercentage)}` : ''}` : lastScan ? t('last full scan {when}', { when: atLabel(new Date(lastScan)) }) : t('not scanned yet')}`
    : foldersError ? t('Libraries unavailable') : <span class="skel skel-line srv-lede-skel" style={{ width: '260px' }} />;

  return (
    <AdminLayout
      page="libraries"
      title={t('Libraries')}
      lede={<span class="srv-lede">{lede}</span>}
      wide
      actions={
        <>
          <button type="button" class="btn btn-secondary" disabled={scanning} onClick={() => void scanAll()}>{scanning ? t('Scanning…') : t('Scan all')}</button>
          <button type="button" class="btn btn-primary is-sm" onClick={() => setAdding(true)}><Icon name="plus" size={17} />{t('Add library')}</button>
        </>
      }
    >
      <div role="tablist" aria-label={t('Library settings')} class="srv-tabs srv-lib-tabs">
        {TABS.map((x) => (
          <button key={x.id} type="button" role="tab" class="tab" aria-selected={tab === x.id ? 'true' : 'false'} onClick={() => setQuery({ tab: x.id === 'libraries' ? null : x.id })}>{t(x.label)}</button>
        ))}
      </div>

      {tab === 'libraries' && (
        !folders && foldersError ? <LoadError error={foldersError} onRetry={() => void loadFolders()} what={t('the libraries')} />
          : !folders ? <LibrariesSkeleton />
            : <LibrariesTab folders={folders} tasks={tasks} selectedId={loc.query.get('lib')} onReload={loadFolders} onAdd={() => setAdding(true)} />
      )}
      {tab === 'display' && <DisplayTab />}
      {tab === 'metadata' && <MetadataTab />}
      {tab === 'nfo' && <NfoTab />}

      <AddLibrarySheet
        open={adding}
        existing={folders ?? []}
        onClose={() => setAdding(false)}
        onAdded={async (name) => {
          setAdding(false);
          const list = await getVirtualFolders().catch(() => null);
          if (list) setFolders(list);
          const created = list?.find((f) => f.Name === name);
          navigate(`/admin/libraries${created ? `?lib=${created.ItemId}` : ''}`, { replace: true, keepScroll: true });
        }}
      />
    </AdminLayout>
  );
}

function LibrariesSkeleton() {
  return (
    <div class="srv-lib-layout" aria-busy="true">
      <div class="srv-lib-list">{[0, 1, 2].map((i) => <span key={i} class="skel srv-lib-card-skel" />)}</div>
      <div class="srv-lib-editor"><PanelSkeletons count={2} rows={2} /></div>
    </div>
  );
}

/* ── Libraries tab ── */

function LibrariesTab({ folders, tasks, selectedId, onReload, onAdd }: {
  folders: VirtualFolder[]; tasks: TaskInfo[] | null; selectedId: string | null; onReload: () => Promise<void>; onAdd: () => void;
}) {
  const selected = folders.find((f) => f.ItemId === selectedId) ?? folders[0];
  const counts = useAsync(async (signal) => {
    const out: Record<string, number> = {};
    await Promise.all(folders.map(async (f) => {
      try { out[f.ItemId] = (await countItems(f.ItemId, COUNT_AS[f.CollectionType ?? '']?.types, signal)).TotalRecordCount; } catch { /* leave it out */ }
    }));
    return out;
  }, [folders.map((f) => f.ItemId).join()]);

  if (!folders.length) {
    return (
      <div class="empty">
        <span class="empty-icon"><Icon name="folder" size={26} /></span>
        <div class="empty-body">
          <span class="empty-title">{t('No libraries yet')}</span>
          <span class="empty-text">{t('Add a library and point it at the folders with your movies, shows or music. Jellyfin scans them and fetches the artwork.')}</span>
        </div>
        <button type="button" class="btn btn-primary is-sm" onClick={onAdd}><Icon name="plus" size={17} />{t('Add library')}</button>
      </div>
    );
  }

  const scanTask = tasks?.find((x) => x.Key === 'RefreshLibrary');
  return (
    <div class="srv-lib-layout">
      <nav class="srv-lib-list" aria-label={t('Libraries')}>
        {folders.map((f) => {
          const progress = f.RefreshProgress ?? (f.RefreshStatus === 'Active' ? 0 : scanTask?.State === 'Running' ? scanTask.CurrentProgressPercentage ?? 0 : null);
          const scanning = progress !== null && progress !== undefined;
          const count = counts.data?.[f.ItemId];
          const unit = COUNT_AS[f.CollectionType ?? ''] ?? { one: '{n} item', many: '{n} items' };
          return (
            <Link
              key={f.ItemId}
              to={`/admin/libraries?lib=${f.ItemId}`}
              replace
              class="srv-lib-card"
              aria-current={f === selected ? 'page' : undefined}
            >
              <div class="srv-lib-card-top">
                <span class="srv-lib-thumb" aria-hidden="true">
                  {f.PrimaryImageItemId && <img src={imageUrl(f.PrimaryImageItemId, 'Primary', { maxWidth: 200 })} alt="" onError={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; }} />}
                </span>
                <span class="srv-lib-card-text">
                  <span class="srv-lib-name">{f.Name}</span>
                  <span class="srv-lib-meta">{typeLabel(f.CollectionType)}{count !== undefined ? ` · ${tn(count, unit.one, unit.many, { count: formatNumber(count) })}` : ''}</span>
                </span>
              </div>
              <div class="srv-lib-state">
                <span>{scanning ? t('Scanning · {pct}', { pct: percent(progress!) }) : scannedLabel(scanTask?.LastExecutionResult?.EndTimeUtc)}</span>
                <span class={'srv-lib-bar' + (scanning ? ' is-active' : '')}><i style={{ width: scanning ? `${Math.max(0, Math.min(100, progress!))}%` : '100%' }} /></span>
              </div>
            </Link>
          );
        })}
      </nav>
      {selected && <LibraryEditor key={selected.ItemId} folder={selected} onReload={onReload} />}
    </div>
  );
}

/* ── One library ── */

/* English labels; translated where shown. */
const IMAGE_ONLY_NAMES: Record<string, { label: string; kind: string }> = {
  'Embedded Image Extractor': { label: 'Embedded image extractor', kind: 'Artwork from the file' },
  'Screen Grabber': { label: 'Screen grabber', kind: 'Frame from the video' }
};
const SIMILAR_LABEL: Record<string, string> = { 'Local Genre/Tag': 'Jellyfin, built in' };
const SIMILAR_SHORT: Record<string, string> = { 'Local Genre/Tag': 'Jellyfin', TheMovieDb: 'TMDb' };
const similarLabel = (n: string) => (SIMILAR_LABEL[n] ? t(SIMILAR_LABEL[n]) : n);
const similarShort = (n: string) => SIMILAR_SHORT[n] ?? n;

interface Source { name: string; meta: boolean; image: boolean; enabled: boolean }

/* Insert the items of \`extra\` missing from \`base\` just before the next item they precede, so both orders hold. */
function mergeOrder(base: string[], extra: string[]): string[] {
  const out = [...base];
  extra.forEach((n, i) => {
    if (out.includes(n)) return;
    const next = extra.slice(i + 1).find((x) => out.includes(x));
    if (next) out.splice(out.indexOf(next), 0, n);
    else out.push(n);
  });
  return out;
}

/* One list for details and artwork providers: Jellyfin keeps an order per role, Seedy shows them merged. */
function buildSources(opts: LibraryOptions, avail: AvailableOptions): Source[] {
  let order: string[] = [];
  const meta = new Set<string>();
  const image = new Set<string>();
  const enabled = new Set<string>();
  for (const t of avail.TypeOptions) {
    const own = opts.TypeOptions?.find((x) => x.Type === t.Type);
    const metaNames = t.MetadataFetchers.map((x) => x.Name);
    const imgNames = t.ImageFetchers.map((x) => x.Name);
    metaNames.forEach((n) => meta.add(n));
    imgNames.forEach((n) => image.add(n));
    const metaOrder = own ? orderByList(metaNames, own.MetadataFetcherOrder) : metaNames;
    const imgOrder = own ? orderByList(imgNames, own.ImageFetcherOrder) : imgNames;
    order = mergeOrder(order, mergeOrder(imgOrder, metaOrder));
    if (own) {
      (own.MetadataFetchers ?? []).forEach((n) => enabled.add(n));
      (own.ImageFetchers ?? []).forEach((n) => enabled.add(n));
    } else {
      t.MetadataFetchers.filter((x) => x.DefaultEnabled).forEach((x) => enabled.add(x.Name));
      t.ImageFetchers.filter((x) => x.DefaultEnabled).forEach((x) => enabled.add(x.Name));
    }
  }
  return order.map((name) => ({ name, meta: meta.has(name), image: image.has(name), enabled: enabled.has(name) }));
}

function similarState(opts: LibraryOptions, avail: AvailableOptions): { all: string[]; chosen: string[] } {
  const first = avail.TypeOptions.find((t) => (t.SimilarItemProviders ?? []).length);
  if (!first) return { all: [], chosen: [] };
  const all = (first.SimilarItemProviders ?? []).map((x) => x.Name);
  const own = opts.TypeOptions?.find((x) => x.Type === first.Type);
  if (own?.SimilarItemProviders) {
    const order = [...(own.SimilarItemProviderOrder ?? []), ...all].filter((n, i, a) => a.indexOf(n) === i && all.includes(n));
    return { all, chosen: order.filter((n) => own.SimilarItemProviders!.includes(n)) };
  }
  return { all, chosen: (first.SimilarItemProviders ?? []).filter((x) => x.DefaultEnabled).map((x) => x.Name) };
}

function applyTypeOptions(opts: LibraryOptions, avail: AvailableOptions, sources: Source[], similar: string[] | null): LibraryOptions {
  const order = sources.map((s) => s.name);
  const on = new Set(sources.filter((s) => s.enabled).map((s) => s.name));
  const TypeOptions: TypeOptions[] = avail.TypeOptions.map((t) => {
    const own = opts.TypeOptions?.find((x) => x.Type === t.Type);
    const metaNames = t.MetadataFetchers.map((x) => x.Name);
    const imgNames = t.ImageFetchers.map((x) => x.Name);
    const simNames = (t.SimilarItemProviders ?? []).map((x) => x.Name);
    const sim = similar ? similar.filter((n) => simNames.includes(n)) : own?.SimilarItemProviders ?? (t.SimilarItemProviders ?? []).filter((x) => x.DefaultEnabled).map((x) => x.Name);
    return {
      ...(own ?? {}),
      Type: t.Type,
      MetadataFetchers: order.filter((n) => metaNames.includes(n) && on.has(n)),
      MetadataFetcherOrder: order.filter((n) => metaNames.includes(n)),
      ImageFetchers: order.filter((n) => imgNames.includes(n) && on.has(n)),
      ImageFetcherOrder: order.filter((n) => imgNames.includes(n)),
      ImageOptions: own?.ImageOptions ?? t.DefaultImageOptions ?? [],
      SimilarItemProviders: sim,
      SimilarItemProviderOrder: [...sim, ...simNames.filter((n) => !sim.includes(n))]
    };
  });
  const others = (opts.TypeOptions ?? []).filter((x) => !avail.TypeOptions.some((t) => t.Type === x.Type));
  return { ...opts, TypeOptions: [...TypeOptions, ...others] };
}

const refreshLabel = (days: number) => (days === 0 ? t('Never') : days === 365 ? t('Year') : tn(days, '{n} day', '{n} days'));
const REFRESH_DAYS = [0, 30, 60, 90, 180, 365];

const availCache = new Map<string, Promise<AvailableOptions>>();
const availableFor = (type?: string) => {
  const key = type ?? '';
  if (!availCache.has(key)) availCache.set(key, getAvailableOptions(type, false).catch((e) => { availCache.delete(key); throw e; }));
  return availCache.get(key)!;
};
let localesPromise: Promise<{ cultures: Culture[]; countries: Country[] }> | null = null;
const locales = () => (localesPromise ??= Promise.all([getCultures(), getCountries()]).then(([cultures, countries]) => ({ cultures, countries })).catch((e) => { localesPromise = null; throw e; }));

function LibraryEditor({ folder, onReload }: { folder: VirtualFolder; onReload: () => Promise<void> }) {
  const [opts, setOpts] = useState<LibraryOptions>(folder.LibraryOptions);
  const [save, setSave] = useState<'saved' | 'saving' | 'error'>('saved');
  const [picker, setPicker] = useState(false);
  const avail = useAsync(() => availableFor(folder.CollectionType), [folder.CollectionType]);
  const loc = useAsync(() => locales(), []);
  const timer = useRef(0);
  const latest = useRef({ opts, folder });
  latest.current = { opts, folder };
  const version = useRef(0);
  /* Options changed here and not saved yet. Jellyfin replaces the whole LibraryOptions, so a save puts only these on
     top of what the server has now; saves run one after another so an older one can't land last. */
  const dirty = useRef(new Set<string>());
  const chain = useRef<Promise<void>>(Promise.resolve());

  const flush = (): Promise<void> => {
    window.clearTimeout(timer.current);
    timer.current = 0;
    const run = async () => {
      const v = version.current;
      const keys = [...dirty.current];
      if (!keys.length) return;
      dirty.current = new Set();
      const { opts: o, folder: f } = latest.current;
      try {
        const fresh = (await getVirtualFolders()).find((x) => x.ItemId === f.ItemId)?.LibraryOptions ?? f.LibraryOptions;
        const next: LibraryOptions = { ...fresh };
        for (const k of keys) next[k] = o[k];
        await saveLibraryOptions(f.ItemId, next);
        if (v === version.current) setSave('saved');
      } catch {
        keys.forEach((k) => dirty.current.add(k));
        if (v === version.current) setSave('error');
      }
    };
    const p = chain.current.then(run, run);
    chain.current = p.catch(() => undefined);
    return p;
  };
  useEffect(() => () => { if (timer.current) void flush(); }, []);

  const edit = (fn: (o: LibraryOptions) => LibraryOptions) => {
    setOpts((o) => {
      const n = fn(o);
      for (const k of Object.keys(n)) if (n[k] !== o[k] && k !== 'PathInfos') dirty.current.add(k);
      latest.current = { ...latest.current, opts: n };
      return n;
    });
    version.current++;
    setSave('saving');
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => void flush(), 700);
  };
  const setOpt = <K extends keyof LibraryOptions>(k: K, v: LibraryOptions[K]) => edit((o) => ({ ...o, [k]: v }));

  const sources = avail.data ? buildSources(opts, avail.data) : [];
  const similar = avail.data ? similarState(opts, avail.data) : { all: [], chosen: [] };
  const setSources = (next: Source[]) => { if (avail.data) edit((o) => applyTypeOptions(o, avail.data!, next, null)); };

  const scan = async (mode: 'scan' | 'metadata') => {
    if (mode === 'metadata') {
      const ok = await confirm({
        title: t('Refresh metadata for {name}?', { name: folder.Name }),
        text: t('Jellyfin looks up details and artwork for every title in this library again. Anything you edited by hand stays. On a big library this takes a while.'),
        action: t('Refresh metadata')
      });
      if (!ok) return;
    }
    try {
      await refreshItem(folder.ItemId, mode);
      toast(mode === 'scan' ? t('Scanning {name}', { name: folder.Name }) : t('Refreshing metadata for {name}', { name: folder.Name }));
      window.setTimeout(() => void onReload(), 800);
    } catch (e) {
      toast(t('Couldn’t start it. {error}', { error: errText(e) }), 'error');
    }
  };

  const removePath = async (path: string) => {
    const ok = await confirm({
      title: t('Remove {path}?', { path }),
      text: t('{name} stops showing what’s in this folder after the next scan. The files themselves stay where they are.', { name: folder.Name }),
      action: t('Remove folder'),
      danger: true
    });
    if (!ok) return;
    try {
      await flush();
      await removeLibraryPath(folder.Name, path);
      toast(t('Folder removed'));
      await onReload();
    } catch (e) {
      toast(t('Couldn’t remove it. {error}', { error: errText(e) }), 'error');
    }
  };

  const addPath = async (path: string) => {
    setPicker(false);
    try {
      await flush();
      await addLibraryPath(folder.Name, path);
      toast(t('Added {path}', { path }));
      await onReload();
    } catch (e) {
      toast(t('Couldn’t add the folder. {error}', { error: errText(e) }), 'error');
    }
  };

  const remove = async () => {
    const ok = await confirm({
      title: t('Remove {name}?', { name: folder.Name }),
      text: t('The library and everyone’s watch progress in it are removed from Jellyfin. The media files on disk aren’t touched, and you can add the folders again later.'),
      action: t('Remove library'),
      danger: true
    });
    if (!ok) return;
    try {
      window.clearTimeout(timer.current);
      timer.current = 0;
      await removeLibrary(folder.Name);
      toast(t('{name} was removed', { name: folder.Name }));
      navigate('/admin/libraries', { replace: true, keepScroll: true });
      await onReload();
    } catch (e) {
      toast(t('Couldn’t remove it. {error}', { error: errText(e) }), 'error');
    }
  };

  const cultures = loc.data?.cultures ?? [];
  const countries = loc.data?.countries ?? [];
  const langOptions = [{ value: '', label: t('Server default') }, ...cultures.filter((c) => c.TwoLetterISOLanguageName).map((c) => ({ value: c.TwoLetterISOLanguageName, label: c.DisplayName }))];
  const countryOptions = [{ value: '', label: t('Server default') }, ...countries.map((c) => ({ value: c.TwoLetterISORegionName, label: c.DisplayName }))];
  const lang = opts.PreferredMetadataLanguage ?? '';
  const country = opts.MetadataCountryCode ?? '';
  const isMovies = !folder.CollectionType || folder.CollectionType === 'movies' || folder.CollectionType === 'mixed';
  const segProviders = avail.data?.MediaSegmentProviders ?? [];
  const subFetchers = avail.data?.SubtitleFetchers ?? [];

  return (
    <div class="srv-lib-editor">
      <section aria-labelledby="lib-ed-h" class="srv-lib-sec">
        <div class="srv-lib-ed-head">
          <h2 id="lib-ed-h" class="srv-lib-ed-title">{folder.Name}</h2>
          <div class="srv-lib-ed-actions">
            <button type="button" class="btn btn-secondary is-sm" onClick={() => void scan('scan')}>{t('Scan')}</button>
            <button type="button" class="btn btn-secondary is-sm" onClick={() => void scan('metadata')}>{t('Refresh metadata')}</button>
          </div>
        </div>
        <span class="srv-lib-overline">{t('Folders')}</span>
        {folder.Locations.map((p) => (
          <div key={p} class="srv-lib-folder">
            <span class="srv-lib-path">{p}</span>
            <button type="button" class="srv-x" aria-label={t('Remove {path}', { path: p })} disabled={folder.Locations.length === 1} title={folder.Locations.length === 1 ? t('A library needs at least one folder') : undefined} onClick={() => void removePath(p)}><Icon name="close" size={14} /></button>
          </div>
        ))}
        <button type="button" class="srv-dashed" onClick={() => setPicker(true)}><Icon name="plus" size={13} />{t('Add folder')}</button>
      </section>

      <section aria-labelledby="lib-meta-h" class="srv-lib-sec">
        <h3 id="lib-meta-h" class="srv-lib-sec-title">{t('Metadata')}</h3>
        <div class="srv-lib-selects">
          <label class="srv-lib-select">{t('Language')}
            <SelectField value={lang} options={langOptions.some((o) => o.value === lang) ? langOptions : [...langOptions, { value: lang, label: lang }]} onChange={(v) => setOpt('PreferredMetadataLanguage', v || null)} />
          </label>
          <label class="srv-lib-select">{t('Country')}
            <SelectField value={country} options={countryOptions.some((o) => o.value === country) ? countryOptions : [...countryOptions, { value: country, label: country }]} onChange={(v) => setOpt('MetadataCountryCode', v || null)} />
          </label>
          <label class="srv-lib-select">{t('Refresh every')}
            <SelectField value={opts.AutomaticRefreshIntervalDays ?? 0} options={(REFRESH_DAYS.includes(opts.AutomaticRefreshIntervalDays) ? REFRESH_DAYS : [...REFRESH_DAYS, opts.AutomaticRefreshIntervalDays]).map((v) => ({ value: v, label: refreshLabel(v) }))} onChange={(v) => setOpt('AutomaticRefreshIntervalDays', v)} />
          </label>
        </div>
        {avail.error && !avail.data ? (
          <InlineError text={t('Couldn’t load the metadata sources.')} onRetry={avail.reload} />
        ) : (
          <>
            <span class="srv-lib-label">{t('Sources, in order · drag to reorder')}</span>
            {!avail.data ? (
              <div class="srv-sources">{[0, 1, 2].map((i) => <span key={i} class="skel srv-source-skel" />)}</div>
            ) : sources.length ? (
              <SourceList sources={sources} onChange={setSources} />
            ) : (
              <p class="srv-lib-none">{t('This kind of library has no metadata sources.')}</p>
            )}
          </>
        )}
        {similar.all.length > 0 && (
          <div class="srv-lib-rec">
            <label for="sim-src" class="setting-label"><span class="setting-name">{t('Recommendations from')}</span><span class="setting-help">{t('Feeds More like this and Because you watched')}</span></label>
            <SimilarSelect all={similar.all} chosen={similar.chosen} onChange={(c) => { if (avail.data) edit((o) => applyTypeOptions(o, avail.data!, buildSources(o, avail.data!), c)); }} />
          </div>
        )}
      </section>

      <section aria-labelledby="lib-sub-h" class="srv-lib-sec">
        <h3 id="lib-sub-h" class="srv-lib-sec-title">{t('Subtitles')}</h3>
        <LanguageChips codes={opts.SubtitleDownloadLanguages ?? []} cultures={cultures} onChange={(c) => setOpt('SubtitleDownloadLanguages', c)} />
        {subFetchers.length > 0 && (
          <div class="srv-lib-checks">
            <span class="srv-lib-label">{t('Download from')}</span>
            {subFetchers.map((f) => (
              <label key={f.Name} class="srv-check">
                <input type="checkbox" checked={!(opts.DisabledSubtitleFetchers ?? []).includes(f.Name)} onChange={(e) => {
                  const on = (e.currentTarget as HTMLInputElement).checked;
                  setOpt('DisabledSubtitleFetchers', on ? opts.DisabledSubtitleFetchers.filter((x) => x !== f.Name) : [...(opts.DisabledSubtitleFetchers ?? []), f.Name]);
                }} />
                {f.Name}
              </label>
            ))}
          </div>
        )}
        <div class="srv-grid2">
          <GridSwitch name={t('Skip if the file has subtitles')} checked={opts.SkipSubtitlesIfEmbeddedSubtitlesPresent} onChange={(v) => setOpt('SkipSubtitlesIfEmbeddedSubtitlesPresent', v)} />
          <GridSwitch name={t('Skip if the audio matches')} checked={opts.SkipSubtitlesIfAudioTrackMatches} onChange={(v) => setOpt('SkipSubtitlesIfAudioTrackMatches', v)} />
          <GridSwitch name={t('Only perfect matches')} checked={opts.RequirePerfectSubtitleMatch} onChange={(v) => setOpt('RequirePerfectSubtitleMatch', v)} />
          <GridSwitch name={t('Save next to the media')} checked={opts.SaveSubtitlesWithMedia} onChange={(v) => setOpt('SaveSubtitlesWithMedia', v)} />
        </div>
      </section>

      <section aria-labelledby="lib-scan-h" class="srv-lib-sec">
        <h3 id="lib-scan-h" class="srv-lib-sec-title">{t('Scanning and previews')}</h3>
        <div class="srv-grid2">
          <GridSwitch name={t('Watch folders for changes')} checked={opts.EnableRealtimeMonitor} onChange={(v) => setOpt('EnableRealtimeMonitor', v)} />
          <GridSwitch name={t('Seek previews (trickplay)')} checked={opts.EnableTrickplayImageExtraction} onChange={(v) => edit((o) => ({ ...o, EnableTrickplayImageExtraction: v, ExtractTrickplayImagesDuringLibraryScan: v }))} />
          <GridSwitch name={t('Chapter images')} checked={opts.EnableChapterImageExtraction} onChange={(v) => edit((o) => ({ ...o, EnableChapterImageExtraction: v, ExtractChapterImagesDuringLibraryScan: v }))} />
          <GridSwitch name={t('Save previews next to the media')} checked={opts.SaveTrickplayWithMedia} onChange={(v) => setOpt('SaveTrickplayWithMedia', v)} />
          {isMovies && <GridSwitch name={t('Add to collections automatically')} checked={opts.AutomaticallyAddToCollection} onChange={(v) => setOpt('AutomaticallyAddToCollection', v)} />}
          <GridSwitch name={t('Save artwork next to the media')} checked={opts.SaveLocalMetadata} onChange={(v) => setOpt('SaveLocalMetadata', v)} />
        </div>
        <div class="srv-lib-rec">
          <span class="setting-label"><span class="setting-name">{t('Skip markers from')}</span><span class="setting-help">{t('Media segment providers, in order')}</span></span>
          {segProviders.length ? (
            <div class="srv-lib-checks">
              {orderByList(segProviders.map((p) => p.Name), opts.MediaSegmentProviderOrder).map((name) => (
                <label key={name} class="srv-check">
                  <input type="checkbox" checked={!(opts.DisabledMediaSegmentProviders ?? []).includes(name)} onChange={(e) => {
                    const on = (e.currentTarget as HTMLInputElement).checked;
                    edit((o) => ({
                      ...o,
                      DisabledMediaSegmentProviders: on ? (o.DisabledMediaSegmentProviders ?? []).filter((x) => x !== name) : [...(o.DisabledMediaSegmentProviders ?? []), name],
                      MediaSegmentProviderOrder: orderByList(segProviders.map((p) => p.Name), o.MediaSegmentProviderOrder)
                    }));
                  }} />
                  {name}
                </label>
              ))}
            </div>
          ) : (
            <span class="srv-lib-none">{avail.data ? t('None installed') : '…'}</span>
          )}
        </div>
      </section>

      <div class="srv-lib-foot">
        <button type="button" class="btn srv-btn-danger" onClick={() => void remove()}>{t('Remove library')}</button>
        <SavedNote state={save} />
      </div>

      <FolderPicker open={picker} start={folder.Locations[0] ? parentOf(folder.Locations[0]) : ''} title={t('Add a folder to {name}', { name: folder.Name })} action={t('Add folder')} onClose={() => setPicker(false)} onPick={(p) => void addPath(p)} />
    </div>
  );
}

const parentOf = (p: string) => p.replace(/[\\/][^\\/]+[\\/]?$/, '') || '/';
const orderByList = (names: string[], order?: string[]) => [...(order ?? []).filter((n) => names.includes(n)), ...names.filter((n) => !(order ?? []).includes(n))];

function GridSwitch({ name, checked, onChange }: { name: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <div class="srv-sw">
      <span class="srv-sw-name is-plain">{name}</span>
      <Switch checked={!!checked} onChange={onChange} label={name} />
    </div>
  );
}

function GripIcon() {
  return (
    <svg viewBox="0 0 24 24" width="16" height="16" aria-hidden="true">
      {[7, 12, 17].map((y) => [9, 15].map((x) => <circle key={`${x}-${y}`} cx={x} cy={y} r="1.5" fill="currentColor" />))}
    </svg>
  );
}

/* Sources with a grip: drag with a pointer, or focus the grip and use ↑ / ↓. */
function SourceList({ sources, onChange }: { sources: Source[]; onChange: (s: Source[]) => void }) {
  const list = useRef<HTMLDivElement>(null);
  const [drag, setDrag] = useState<{ from: number; to: number } | null>(null);
  const move = (from: number, to: number) => {
    if (from === to) return;
    const next = [...sources];
    const [it] = next.splice(from, 1);
    next.splice(to, 0, it);
    onChange(next);
  };
  const targetAt = (y: number) => {
    const rows = Array.from(list.current?.querySelectorAll<HTMLElement>('.srv-source') ?? []);
    let to = rows.length - 1;
    for (let i = 0; i < rows.length; i++) {
      const r = rows[i].getBoundingClientRect();
      if (y < r.top + r.height / 2) { to = i; break; }
    }
    return to;
  };
  return (
    <div class="srv-sources" ref={list}>
      {sources.map((s, i) => {
        const special = IMAGE_ONLY_NAMES[s.name];
        const kind = t(s.meta && s.image ? 'Details and artwork' : s.meta ? 'Details' : special?.kind ?? 'Artwork');
        const label = special ? t(special.label) : s.name;
        const over = drag && drag.from !== drag.to && drag.to === i;
        return (
          <div key={s.name} class={'srv-source' + (drag?.from === i ? ' is-dragging' : '') + (over ? (drag!.to > drag!.from ? ' is-over-after' : ' is-over') : '')}>
            <button
              type="button"
              class="srv-grip"
              aria-label={t('Move {name}. Position {i} of {n}', { name: label, i: i + 1, n: sources.length })}
              onPointerDown={(e) => {
                (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
                setDrag({ from: i, to: i });
                document.body.classList.add('is-row-dragging');
              }}
              onPointerMove={(e) => { if (drag) setDrag({ from: drag.from, to: targetAt(e.clientY) }); }}
              onPointerUp={() => { document.body.classList.remove('is-row-dragging'); if (drag) move(drag.from, drag.to); setDrag(null); }}
              onPointerCancel={() => { document.body.classList.remove('is-row-dragging'); setDrag(null); }}
              onKeyDown={(e) => {
                if (e.key === 'ArrowUp' && i > 0) { e.preventDefault(); move(i, i - 1); requestAnimationFrame(() => list.current?.querySelectorAll<HTMLElement>('.srv-grip')[i - 1]?.focus()); }
                if (e.key === 'ArrowDown' && i < sources.length - 1) { e.preventDefault(); move(i, i + 1); requestAnimationFrame(() => list.current?.querySelectorAll<HTMLElement>('.srv-grip')[i + 1]?.focus()); }
              }}
            >
              <GripIcon />
            </button>
            <span class="srv-source-text"><span class="srv-source-name">{label}</span><span class="srv-source-kind">{kind}</span></span>
            <input type="checkbox" class="srv-checkbox" checked={s.enabled} aria-label={t('Use {name}', { name: label })} onChange={(e) => onChange(sources.map((x, j) => (j === i ? { ...x, enabled: (e.currentTarget as HTMLInputElement).checked } : x)))} />
          </div>
        );
      })}
    </div>
  );
}

function SimilarSelect({ all, chosen, onChange }: { all: string[]; chosen: string[]; onChange: (c: string[]) => void }) {
  const options: { value: string; label: string }[] = all.map((n) => ({ value: JSON.stringify([n]), label: similarLabel(n) }));
  /* Short labels keep the select at the board's 220 px. */
  if (all.length === 2) {
    options.push({ value: JSON.stringify([all[0], all[1]]), label: t('Both, {name} first', { name: similarShort(all[0]) }) });
    options.push({ value: JSON.stringify([all[1], all[0]]), label: t('Both, {name} first', { name: similarShort(all[1]) }) });
  } else if (all.length > 2) {
    for (const n of all) options.push({ value: JSON.stringify([n, ...all.filter((x) => x !== n)]), label: t('All, {name} first', { name: similarShort(n) }) });
  }
  options.push({ value: '[]', label: t('Nothing') });
  const value = JSON.stringify(chosen);
  const opts = options.some((o) => o.value === value) ? options : [{ value, label: chosen.map(similarShort).join(t(', then ')) }, ...options];
  return <SelectField id="sim-src" value={value} options={opts} minWidth={220} onChange={(v) => onChange(JSON.parse(v) as string[])} />;
}

/* "Download in English French + Add language". Codes are three-letter ISO 639-2. */
function LanguageChips({ codes, cultures, onChange }: { codes: string[]; cultures: Culture[]; onChange: (c: string[]) => void }) {
  const [q, setQ] = useState('');
  const [above, setAbove] = useState(false);
  const nameOf = (code: string) => cultures.find((c) => c.ThreeLetterISOLanguageName === code || c.ThreeLetterISOLanguageNames?.includes(code))?.DisplayName ?? code;
  const choices = cultures
    .filter((c) => c.ThreeLetterISOLanguageName && !codes.includes(c.ThreeLetterISOLanguageName))
    .filter((c) => !q.trim() || c.DisplayName.toLowerCase().includes(q.trim().toLowerCase()));
  return (
    <div class="srv-langs">
      <span class="srv-langs-label">{t('Download in')}</span>
      {codes.map((c) => (
        <button key={c} type="button" class="srv-lang" aria-label={t('Stop downloading {language} subtitles', { language: nameOf(c) })} onClick={() => onChange(codes.filter((x) => x !== c))}>
          {nameOf(c)}<Icon name="close" size={11} class="srv-lang-x" />
        </button>
      ))}
      <MenuButton
        width={280}
        align="start"
        placement={above ? 'above' : 'below'}
        button={({ ref, open, toggle }) => (
          <button
            ref={ref}
            type="button"
            class="srv-lang-add"
            aria-haspopup="menu"
            aria-expanded={open ? 'true' : 'false'}
            onClick={() => {
              /* Open upwards when the button sits low on the screen, so the list stays in view. */
              const r = ref.current?.getBoundingClientRect();
              setAbove(!!r && r.top > window.innerHeight * 0.55);
              setQ('');
              toggle();
            }}
          >
            {t('Add language')}
          </button>
        )}
      >
        {(close) => (
          <div class="srv-lang-menu">
            <label class="search-field">
              <Icon name="search" size={16} />
              <input aria-label={t('Find a language')} placeholder={t('Find a language')} value={q} onInput={(e) => setQ((e.currentTarget as HTMLInputElement).value)} />
            </label>
            <div class="srv-lang-list">
              {choices.slice(0, 80).map((c) => (
                <button key={c.ThreeLetterISOLanguageName} type="button" class="menu-item" onClick={() => { onChange([...codes, c.ThreeLetterISOLanguageName]); close(); }}>
                  <span>{c.DisplayName}</span>
                </button>
              ))}
              {!choices.length && <span class="menu-item is-muted">{cultures.length ? t('No language matches') : t('Loading languages…')}</span>}
            </div>
          </div>
        )}
      </MenuButton>
    </div>
  );
}

/* ── Add library ── */

/* English labels and default library names; translated where shown. */
const NEW_TYPES = [
  { value: 'movies', label: 'Movies', name: 'Movies' },
  { value: 'tvshows', label: 'Shows', name: 'Shows' },
  { value: 'music', label: 'Music', name: 'Music' },
  { value: 'musicvideos', label: 'Music videos', name: 'Music videos' },
  { value: 'homevideos', label: 'Home videos and photos', name: 'Home videos' },
  { value: 'books', label: 'Books', name: 'Books' },
  { value: 'mixed', label: 'Movies and shows, mixed', name: 'Movies and shows' }
];

function AddLibrarySheet({ open, existing, onClose, onAdded }: { open: boolean; existing: VirtualFolder[]; onClose: () => void; onAdded: (name: string) => void }) {
  const [type, setType] = useState('movies');
  const [name, setName] = useState('');
  const [nameTouched, setNameTouched] = useState(false);
  const [paths, setPaths] = useState<string[]>([]);
  const [picker, setPicker] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => { if (open) { setType('movies'); setName(t('Movies')); setNameTouched(false); setPaths([]); setError(null); } }, [open]);
  const taken = existing.some((f) => f.Name.toLowerCase() === name.trim().toLowerCase());

  const submit = async () => {
    if (!name.trim() || taken) return;
    setBusy(true);
    setError(null);
    try {
      const [avail, cfg] = await Promise.all([getAvailableOptions(type === 'mixed' ? undefined : type, true), getServerConfig()]);
      const LibraryOptions: Partial<LibraryOptions> = {
        Enabled: true,
        EnableRealtimeMonitor: true,
        EnableChapterImageExtraction: false,
        ExtractChapterImagesDuringLibraryScan: false,
        EnableTrickplayImageExtraction: true,
        ExtractTrickplayImagesDuringLibraryScan: false,
        SaveLocalMetadata: false,
        SaveTrickplayWithMedia: false,
        AutomaticallyAddToCollection: false,
        SkipSubtitlesIfEmbeddedSubtitlesPresent: false,
        SkipSubtitlesIfAudioTrackMatches: true,
        RequirePerfectSubtitleMatch: true,
        SaveSubtitlesWithMedia: true,
        AutomaticRefreshIntervalDays: 0,
        PreferredMetadataLanguage: cfg.PreferredMetadataLanguage,
        MetadataCountryCode: cfg.MetadataCountryCode,
        PathInfos: paths.map((Path) => ({ Path })),
        DisabledSubtitleFetchers: [],
        SubtitleFetcherOrder: avail.SubtitleFetchers.map((x) => x.Name),
        DisabledMediaSegmentProviders: [],
        MediaSegmentProviderOrder: avail.MediaSegmentProviders.map((x) => x.Name),
        TypeOptions: avail.TypeOptions.map((t) => ({
          Type: t.Type,
          MetadataFetchers: t.MetadataFetchers.filter((x) => x.DefaultEnabled).map((x) => x.Name),
          MetadataFetcherOrder: t.MetadataFetchers.map((x) => x.Name),
          ImageFetchers: t.ImageFetchers.filter((x) => x.DefaultEnabled).map((x) => x.Name),
          ImageFetcherOrder: t.ImageFetchers.map((x) => x.Name),
          ImageOptions: t.DefaultImageOptions ?? [],
          SimilarItemProviders: (t.SimilarItemProviders ?? []).filter((x) => x.DefaultEnabled).map((x) => x.Name),
          SimilarItemProviderOrder: (t.SimilarItemProviders ?? []).map((x) => x.Name)
        }))
      };
      await addLibrary(name.trim(), type === 'mixed' ? undefined : type, paths, LibraryOptions);
      toast(t('{name} was added. Jellyfin is scanning it now.', { name: name.trim() }));
      onAdded(name.trim());
    } catch (e) {
      setError(errText(e, t('Jellyfin didn’t add the library.')));
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Sheet open={open && !picker} onClose={onClose} label={t('Add library')}>
        <div class="sheet-head">
          <div class="srv-sheet-headtext">
            <h2 class="sheet-title">{t('Add library')}</h2>
            <p class="sheet-text">{t('Point it at the folders on the server that hold this kind of media. Jellyfin scans them and fetches details and artwork.')}</p>
          </div>
        </div>
        <form class="srv-form" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
          <label class="srv-field">
            <span class="srv-field-label">{t('Kind')}</span>
            <span class="select-wrap">
              <select class="select srv-full" value={type} onChange={(e) => {
                const v = (e.currentTarget as HTMLSelectElement).value;
                setType(v);
                const kind = NEW_TYPES.find((x) => x.value === v);
                if (!nameTouched) setName(kind ? t(kind.name) : '');
              }}>
                {NEW_TYPES.map((x) => <option key={x.value} value={x.value}>{t(x.label)}</option>)}
              </select>
              <Icon name="chevronDown" size={16} />
            </span>
          </label>
          <label class="srv-field">
            <span class="srv-field-label">{t('Name')}</span>
            <input class="field" value={name} maxLength={80} onInput={(e) => { setName((e.currentTarget as HTMLInputElement).value); setNameTouched(true); }} />
            {taken && <span class="form-error"><Icon name="alert" size={16} />{t('There’s already a library with this name.')}</span>}
          </label>
          <div class="srv-field">
            <span class="srv-field-label">{t('Folders')}</span>
            {paths.map((p) => (
              <div key={p} class="srv-lib-folder">
                <span class="srv-lib-path">{p}</span>
                <button type="button" class="srv-x" aria-label={t('Remove {path}', { path: p })} onClick={() => setPaths(paths.filter((x) => x !== p))}><Icon name="close" size={14} /></button>
              </div>
            ))}
            <button type="button" class="srv-dashed" onClick={() => setPicker(true)}><Icon name="plus" size={13} />{t('Add folder')}</button>
          </div>
          {error && <span class="form-error"><Icon name="alert" size={16} />{error}</span>}
          <div class="sheet-actions">
            <button type="button" class="btn btn-ghost is-light" onClick={onClose}>{t('Cancel')}</button>
            <button type="submit" class="btn btn-primary is-sm" disabled={!name.trim() || taken || !paths.length || busy}>{busy ? t('Adding…') : t('Add library')}</button>
          </div>
        </form>
      </Sheet>
      <FolderPicker
        open={open && picker}
        start="/"
        title={t('Add a folder')}
        action={t('Add folder')}
        onClose={() => setPicker(false)}
        onPick={(p) => { if (!paths.includes(p)) setPaths([...paths, p]); setPicker(false); }}
      />
    </>
  );
}

/* ── Server-wide tabs: autosaved ── */

function useAutosave<T extends object>(load: () => Promise<T>, saveFn: (patch: Partial<T>) => Promise<unknown>) {
  const loaded = useAsync(() => load(), []);
  const [local, setLocal] = useState<T | null>(null);
  const [state, setState] = useState<'saved' | 'saving' | 'error'>('saved');
  const pending = useRef<Partial<T>>({});
  const timer = useRef(0);
  useEffect(() => { if (loaded.data) setLocal(loaded.data); }, [loaded.data]);
  const flush = async () => {
    window.clearTimeout(timer.current);
    timer.current = 0;
    const patch = pending.current;
    pending.current = {};
    if (!Object.keys(patch).length) return;
    try {
      await saveFn(patch);
      if (!timer.current) setState('saved');
    } catch {
      pending.current = { ...patch, ...pending.current };
      setState('error');
    }
  };
  useEffect(() => () => { if (timer.current) void flush(); }, []);
  const set = <K extends keyof T>(k: K, v: T[K]) => {
    setLocal((l) => (l ? { ...l, [k]: v } : l));
    pending.current = { ...pending.current, [k]: v };
    setState('saving');
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => void flush(), 600);
  };
  return { data: local, error: loaded.error, reload: loaded.reload, set, state };
}

function TabBody({ error, reload, ready, children, state }: { error?: Error; reload: () => void; ready: boolean; children: ComponentChildren; state: 'saved' | 'saving' | 'error' }) {
  if (!ready && error) return <LoadError error={error} onRetry={reload} what={t('these settings')} />;
  if (!ready) return <PanelSkeletons count={2} rows={3} />;
  return (
    <>
      {children}
      <div class="srv-lib-foot is-end"><SavedNote state={state} /></div>
    </>
  );
}

function Row({ name, help, children }: { name: string; help: string; children: ComponentChildren }) {
  return (
    <div class="setting">
      <span class="setting-label"><span class="setting-name">{name}</span><span class="setting-help">{help}</span></span>
      {children}
    </div>
  );
}

function DisplayTab() {
  const cfg = useAutosave<ServerConfig>(() => getServerConfig(), (p) => patchServerConfig(p));
  const meta = useAutosave<MetadataConfig>(() => getNamedConfig<MetadataConfig>('metadata'), (p) => patchNamedConfig<MetadataConfig>('metadata', p));
  const c = cfg.data;
  const m = meta.data;
  const state = cfg.state === 'error' || meta.state === 'error' ? 'error' : cfg.state === 'saving' || meta.state === 'saving' ? 'saving' : 'saved';
  return (
    <TabBody error={cfg.error ?? meta.error} reload={() => { cfg.reload(); meta.reload(); }} ready={!!c && !!m} state={state}>
      {c && m && (
        <>
          <section class="panel">
            <div class="panel-head"><h2 class="panel-title">{t('Browsing')}</h2><p class="panel-text">{t('How every app shows what’s in the libraries.')}</p></div>
            <Row name={t('Folders view')} help={t('Adds a library that shows the media folders as they are on disk')}><Switch checked={c.EnableFolderView} onChange={(v) => cfg.set('EnableFolderView', v)} label={t('Folders view')} /></Row>
            <Row name={t('Specials inside their seasons')} help={t('Specials sit between the episodes they aired with')}><Switch checked={c.DisplaySpecialsWithinSeasons} onChange={(v) => cfg.set('DisplaySpecialsWithinSeasons', v)} label={t('Specials inside their seasons')} /></Row>
            <Row name={t('Group movies into collections')} help={t('Movies in a collection show as one card in Movies')}><Switch checked={c.EnableGroupingMoviesIntoCollections} onChange={(v) => cfg.set('EnableGroupingMoviesIntoCollections', v)} label={t('Group movies into collections')} /></Row>
            <Row name={t('Group shows into collections')} help={t('Shows in a collection show as one card in Shows')}><Switch checked={c.EnableGroupingShowsIntoCollections} onChange={(v) => cfg.set('EnableGroupingShowsIntoCollections', v)} label={t('Group shows into collections')} /></Row>
            <Row name={t('Suggest channel content')} help={t('Live TV and channel titles can appear in suggestions')}><Switch checked={c.EnableExternalContentInSuggestions} onChange={(v) => cfg.set('EnableExternalContentInSuggestions', v)} label={t('Suggest channel content')} /></Row>
          </section>
          <section class="panel">
            <div class="panel-head is-title-only"><h2 class="panel-title">{t('New titles')}</h2></div>
            <Row name={t('Date added')} help={t('What “Recently added” sorts by')}>
              <SelectField
                value={m.UseFileCreationTimeForDateAdded ? 'file' : 'scan'}
                options={[{ value: 'file', label: t('When the file was created') }, { value: 'scan', label: t('When a scan found it') }]}
                minWidth={240}
                label={t('Date added')}
                onChange={(v) => meta.set('UseFileCreationTimeForDateAdded', v === 'file')}
              />
            </Row>
          </section>
        </>
      )}
    </TabBody>
  );
}

const CHAPTER_RES = [
  { value: 'MatchSource', label: 'Same as the video' }, { value: 'P144', label: '144p' }, { value: 'P240', label: '240p' }, { value: 'P360', label: '360p' },
  { value: 'P480', label: '480p' }, { value: 'P720', label: '720p' }, { value: 'P1080', label: '1080p' }, { value: 'P1440', label: '1440p' }, { value: 'P2160', label: '2160p' }
];

function MetadataTab() {
  const cfg = useAutosave<ServerConfig>(() => getServerConfig(), (p) => patchServerConfig(p));
  const loc = useAsync(() => locales(), []);
  const c = cfg.data;
  const langs = (loc.data?.cultures ?? []).filter((x) => x.TwoLetterISOLanguageName).map((x) => ({ value: x.TwoLetterISOLanguageName, label: x.DisplayName }));
  const countries = (loc.data?.countries ?? []).map((x) => ({ value: x.TwoLetterISORegionName, label: x.DisplayName }));
  const withCurrent = (opts: { value: string; label: string }[], v: string) => (opts.some((o) => o.value === v) ? opts : [{ value: v, label: v || t('Not set') }, ...opts]);
  return (
    <TabBody error={cfg.error} reload={cfg.reload} ready={!!c} state={cfg.state}>
      {c && (
        <>
          <section class="panel">
            <div class="panel-head"><h2 class="panel-title">{t('Language and country')}</h2><p class="panel-text">{t('What new libraries start with. Each library can still choose its own.')}</p></div>
            <Row name={t('Preferred language')} help={t('For titles, descriptions and artwork')}>
              <SelectField value={c.PreferredMetadataLanguage ?? ''} options={withCurrent(langs, c.PreferredMetadataLanguage ?? '')} minWidth={240} label={t('Preferred language')} onChange={(v) => cfg.set('PreferredMetadataLanguage', v)} />
            </Row>
            <Row name={t('Country')} help={t('For release dates and age ratings')}>
              <SelectField value={c.MetadataCountryCode ?? ''} options={withCurrent(countries, c.MetadataCountryCode ?? '')} minWidth={240} label={t('Country')} onChange={(v) => cfg.set('MetadataCountryCode', v)} />
            </Row>
          </section>
          <section class="panel">
            <div class="panel-head is-title-only"><h2 class="panel-title">{t('Chapters')}</h2></div>
            <Row name={t('Chapter image size')} help={t('Pictures for the chapter list in the player')}>
              <SelectField value={c.ChapterImageResolution ?? 'MatchSource'} options={CHAPTER_RES.map((o) => (o.value === 'MatchSource' ? { ...o, label: t(o.label) } : o))} minWidth={240} label={t('Chapter image size')} onChange={(v) => cfg.set('ChapterImageResolution', v)} />
            </Row>
            <Row name={t('Add chapters every')} help={t('For files without chapters of their own. 0 adds none')}>
              <span class="srv-num"><NumField value={Math.round((c.DummyChapterDuration ?? 0) / 60)} min={0} max={240} suffix="min" ariaLabel={t('Add chapters every, minutes')} width={56} onChange={(v) => cfg.set('DummyChapterDuration', v * 60)} /></span>
            </Row>
          </section>
          <section class="panel">
            <div class="panel-head is-title-only"><h2 class="panel-title">{t('Saved files')}</h2></div>
            <Row name={t('Artwork file names')} help={t('How artwork saved next to the media is named')}>
              <SelectField value={c.ImageSavingConvention ?? 'Legacy'} options={[{ value: 'Legacy', label: t('Jellyfin’s own names') }, { value: 'Compatible', label: t('Kodi and Emby names') }]} minWidth={240} label={t('Artwork file names')} onChange={(v) => cfg.set('ImageSavingConvention', v)} />
            </Row>
            <Row name={t('Hide saved files')} help={t('Marks artwork and NFO files Jellyfin writes as hidden')}><Switch checked={c.SaveMetadataHidden} onChange={(v) => cfg.set('SaveMetadataHidden', v)} label={t('Hide saved files')} /></Row>
          </section>
        </>
      )}
    </TabBody>
  );
}

function NfoTab() {
  const nfo = useAutosave<NfoConfig>(() => getNamedConfig<NfoConfig>('xbmcmetadata'), (p) => patchNamedConfig<NfoConfig>('xbmcmetadata', p));
  const users = useAsync(() => getUsers(), []);
  const n = nfo.data;
  const userOptions = [{ value: '', label: t('Nobody') }, ...(users.data ?? []).map((u: FullUser) => ({ value: u.Id.replace(/-/g, ''), label: u.Name }))];
  const userValue = (n?.UserId ?? '').replace(/-/g, '');
  return (
    <TabBody error={nfo.error} reload={nfo.reload} ready={!!n} state={nfo.state}>
      {n && (
        <section class="panel">
          <div class="panel-head"><h2 class="panel-title">{t('NFO files')}</h2><p class="panel-text">{t('Kodi and other apps read these files next to the media. Jellyfin writes them for libraries that save artwork next to the media.')}</p></div>
          <Row name={t('Save watched state for')} help={t('Writes which titles this person has watched into the files')}>
            <SelectField value={userOptions.some((o) => o.value === userValue) ? userValue : ''} options={userOptions} minWidth={220} label={t('Save watched state for')} onChange={(v) => nfo.set('UserId', v || null)} />
          </Row>
          <Row name={t('Release date format')} help={t('As Kodi expects it, for example yyyy-MM-dd')}>
            <input class="field srv-w220 srv-mono" aria-label={t('Release date format')} value={n.ReleaseDateFormat ?? ''} onInput={(e) => nfo.set('ReleaseDateFormat', (e.currentTarget as HTMLInputElement).value)} />
          </Row>
          <Row name={t('Save artwork paths')} help={t('Lists the artwork files inside each NFO')}><Switch checked={n.SaveImagePathsInNfo} onChange={(v) => nfo.set('SaveImagePathsInNfo', v)} label={t('Save artwork paths')} /></Row>
          <Row name={t('Use path substitution')} help={t('Rewrites those paths with the rules under Settings › Expert')}><Switch checked={n.EnablePathSubstitution} onChange={(v) => nfo.set('EnablePathSubstitution', v)} label={t('Use path substitution')} /></Row>
          <Row name={t('Copy extra fanart to extrathumbs')} help={t('For older Kodi skins that look there')}><Switch checked={n.EnableExtraThumbsDuplication} onChange={(v) => nfo.set('EnableExtraThumbsDuplication', v)} label={t('Copy extra fanart to extrathumbs')} /></Row>
        </section>
      )}
    </TabBody>
  );
}

