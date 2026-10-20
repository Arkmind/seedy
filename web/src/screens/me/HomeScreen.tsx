import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { get, getSession } from '../../api/client';
import { getItems } from '../../api/jellyfin';
import { adminGetConfig, getHome, type HomeRowPref, type HomeRowSetting, type RowKind } from '../../api/seedy';
import type { BaseItem, ItemsResult } from '../../api/types';
import { meStore, userStore, viewsStore } from '../../lib/app-state';
import { useAsync, useStore } from '../../lib/store';
import { NumField, Segmented, Stepper, Switch } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { Sheet } from '../../ui/overlay';
import { tabsFor, typesFor } from '../browse/Library';
import { MeLayout } from './MeLayout';
import { CUSTOM, readCustom, saveCustom, savePrefsPatch, saveUserConfig, useCustom, userConfig } from './prefs';
import { GripIcon, MeRow, MeSection, PinIcon, SaveLine, moveItem, useSortable } from './parts';
import { t, tn } from '../../lib/i18n';
import './parts.css';

/* Settings · Home screen (MeHome board). */

const ROW_INFO: Partial<Record<RowKind, { name: string; help: string; count?: { min: number; max: number } }>> = {
  nextup: { name: 'Next up', help: 'Next episode of the shows you’re watching' },
  because: { name: 'Because you watched', help: 'One row per title you watched recently', count: { min: 1, max: 10 } },
  top10: { name: 'Top 10 this week', help: 'Most watched on this server' },
  genres: { name: 'Genre rows', help: 'The genres you watch most', count: { min: 1, max: 8 } },
  latest: { name: 'Latest in each library', help: 'Newest additions' },
  mymedia: { name: 'My media', help: 'One tile per library' },
  upcomingShows: { name: 'Upcoming shows', help: 'Next episodes airing soon' },
  discover: { name: 'Discover', help: 'Popular titles you can request' },
  upcomingMovies: { name: 'Upcoming movies', help: 'Requested movies not out yet' },
  collections: { name: 'Collections', help: 'One row per collection' }
};

/* The server's starting rows when the admin's own list isn't readable (non-admins). */
const uid = () => getSession()?.userId ?? '';

const SERVER_DEFAULTS: HomeRowSetting[] = [
  { kind: 'nextup', enabled: true }, { kind: 'top10', enabled: true }, { kind: 'because', enabled: true, count: 6 },
  { kind: 'genres', enabled: true, count: 4 }, { kind: 'mymedia', enabled: true }, { kind: 'latest', enabled: true },
  { kind: 'discover', enabled: true }, { kind: 'upcomingMovies', enabled: true }, { kind: 'upcomingShows', enabled: true },
  { kind: 'collections', enabled: false }
];

export function MeHome() {
  return (
    <MeLayout page="home" title={t('Home screen')} lede={t('Which rows you see and in what order, and how your libraries show up.')}>
      <RowsSection />
      <LibrariesSection />
      <NextUpSection />
      <LatestSection />
      <SaveLine />
    </MeLayout>
  );
}

/* ── Rows ── */

interface RowView { kind: RowKind; enabled: boolean; count?: number; pinned: boolean; adminCount?: number }

function useAdminRows() {
  const me = useStore(meStore);
  return useAsync(async (): Promise<HomeRowSetting[]> => {
    const fromMe = me.homeRows;
    if (fromMe?.length) return fromMe;
    if (me.isAdmin) {
      try { return (await adminGetConfig()).home.rows; } catch { /* fall through to the defaults */ }
    }
    return SERVER_DEFAULTS.filter((r) =>
      (r.kind !== 'discover' || me.requests.discover) && (r.kind !== 'upcomingMovies' || me.requests.upcomingMovies));
  }, [me.isAdmin, me.requests.discover, me.requests.upcomingMovies]);
}

function RowsSection() {
  const me = useStore(meStore);
  const prefs = me.prefs;
  const admin = useAdminRows();
  const listRef = useRef<HTMLDivElement>(null);
  const [hiddenOpen, setHiddenOpen] = useState(false);
  const mode = prefs.rowOrder === 'mine' ? 'mine' : 'auto';

  /* In automatic order the list shows Home as it is today (learned from plays), snapshotted once so switching a row
     off doesn't make it jump; in My order it's the person's own list. Rows the admin switched off aren't offered. */
  const live = useAsync(() => getHome().then((h) => h.rows.map((r) => r.kind)).catch(() => [] as RowKind[]), []);
  const [autoSnap, setAutoSnap] = useState<RowKind[] | null>(null);
  useEffect(() => { if (live.data && !autoSnap) setAutoSnap([...new Set(live.data)]); }, [live.data]);

  const rows: RowView[] = useMemo(() => {
    const allowed = (admin.data ?? []).filter((r) => r.enabled && ROW_INFO[r.kind]);
    const mine = (prefs.rows ?? []).filter((p) => allowed.some((a) => a.kind === p.kind));
    const base = [...mine.map((p) => p.kind), ...allowed.map((a) => a.kind).filter((k) => !mine.some((p) => p.kind === k))];
    const ordered = mode === 'auto' && autoSnap ? [...autoSnap.filter((k) => base.includes(k)), ...base.filter((k) => !autoSnap.includes(k))] : base;
    return ordered.map((kind) => {
      const a = allowed.find((x) => x.kind === kind)!;
      const p = mine.find((x) => x.kind === kind);
      return { kind, enabled: p ? p.enabled : true, count: p?.count ?? a.count, pinned: !!p?.pinned, adminCount: a.count };
    });
  }, [admin.data, prefs.rows, mode, autoSnap]);

  const save = (next: RowView[]) => savePrefsPatch({
    rows: next.map((r): HomeRowPref => ({ kind: r.kind, enabled: r.enabled, ...(r.count ? { count: r.count } : {}), ...(r.pinned ? { pinned: true } : {}) }))
  });
  const setRow = (kind: RowKind, patch: Partial<RowView>) => save(rows.map((r) => (r.kind === kind ? { ...r, ...patch } : r)));

  const sort = useSortable({
    ids: rows.map((r) => r.kind),
    listRef,
    itemSelector: '.rows-item',
    name: (id) => ROW_INFO[id as RowKind]?.name ?? id,
    // In automatic order, moving a row pins it where it was put.
    onMove: (from, to) => {
      const next = moveItem(rows, from, to);
      if (mode === 'auto') setAutoSnap(next.map((r) => r.kind));
      save(next.map((r) => (mode === 'auto' && r.kind === rows[from].kind ? { ...r, pinned: true } : r)));
    }
  });

  const hiddenCount = (prefs.hiddenRows?.length ?? 0) + (prefs.hiddenSeeds?.length ?? 0);

  return (
    <section aria-labelledby="rows-h" class="me-section is-gap-14">
      <div class="me-rows-top">
        <div class="me-section-head is-flush me-rows-intro">
          <h2 id="rows-h" class="me-section-title">{t('Rows')}</h2>
          <p class="me-section-help">{t('Rows you play from most move up once a day. Pin a row to keep it where you put it.')}</p>
        </div>
        <Segmented
          label={t('Row order')}
          value={mode}
          options={[{ value: 'auto', label: t('Order automatically') }, { value: 'mine', label: t('My order') }]}
          onChange={(v) => {
            // "My order" starts from what's on screen now, so switching never shuffles the list.
            if (v === 'mine') savePrefsPatch({ rowOrder: v, rows: rows.map((r): HomeRowPref => ({ kind: r.kind, enabled: r.enabled, ...(r.count ? { count: r.count } : {}), ...(r.pinned ? { pinned: true } : {}) })) });
            else savePrefsPatch({ rowOrder: v });
          }}
        />
      </div>

      {admin.loading && !admin.data ? (
        <div class="rows-list">{Array.from({ length: 6 }, (_, i) => <div key={i} class="rows-item skel" />)}</div>
      ) : (
        <div class="rows-list me-sort" ref={listRef}>
          <span id="me-rows-hint" class="sr-only">{mode === 'auto' ? t('Drag to move, or use the up and down arrow keys. Moving a row pins it.') : t('Drag to move, or use the up and down arrow keys.')}</span>
          <div class="sr-only" role="status" aria-live="polite">{sort.live}</div>
          {rows.map((r, i) => {
            const info = ROW_INFO[r.kind]!;
            const count = r.count ?? info.count?.min ?? 1;
            return (
              <div key={r.kind} data-id={r.kind} class={'rows-item me-row-item' + (sort.drag?.id === r.kind ? ' is-dragging' : '')}>
                <button type="button" class="rows-grip me-grip" aria-label={t('Move {name}', { name: t(info.name) })} aria-describedby="me-rows-hint" {...sort.gripProps(i)}>
                  <GripIcon />
                </button>
                <span class="rows-text">
                  <span class={'rows-name' + (r.enabled ? '' : ' is-off')}>
                    {t(info.name)}
                    {mode === 'auto' && (
                      <button
                        type="button"
                        class={'me-pin' + (r.pinned ? ' is-on' : '')}
                        aria-pressed={r.pinned ? 'true' : 'false'}
                        aria-label={r.pinned ? t('{name} is pinned. Unpin', { name: t(info.name) }) : t('Pin {name}', { name: t(info.name) })}
                        onClick={() => setRow(r.kind, { pinned: !r.pinned })}
                      >
                        <PinIcon />{r.pinned ? t('Pinned') : t('Pin')}
                      </button>
                    )}
                  </span>
                  <span class="rows-help">{t(info.help)}</span>
                </span>
                {info.count && (
                  <Stepper
                    class="me-stepper"
                    label={t('{name}: number of rows', { name: t(info.name) })}
                    valueLabel={String(count)}
                    decLabel={t('Fewer rows')}
                    incLabel={t('More rows')}
                    decDisabled={count <= info.count.min}
                    incDisabled={count >= info.count.max}
                    onDec={() => setRow(r.kind, { count: Math.max(info.count!.min, count - 1) })}
                    onInc={() => setRow(r.kind, { count: Math.min(info.count!.max, count + 1) })}
                  />
                )}
                <Switch label={t('Show {name}', { name: t(info.name) })} checked={r.enabled} onChange={(v) => setRow(r.kind, { enabled: v })} />
              </div>
            );
          })}
          <div class={'me-line' + (sort.lineY !== null ? ' is-on' : '')} aria-hidden="true" style={sort.lineY !== null ? { transform: `translateY(${sort.lineY}px)` } : undefined} />
          {sort.drag && (
            <div class="me-ghost" aria-hidden="true" style={{ transform: `translateY(${sort.drag.top + sort.drag.dy}px)`, height: sort.drag.height + 'px' }}>
              <span class="rows-grip"><GripIcon /></span>
              <span class="rows-text">
                <span class="rows-name">{t(ROW_INFO[sort.drag.id as RowKind]?.name ?? '')}</span>
                <span class="rows-help">{t(ROW_INFO[sort.drag.id as RowKind]?.help ?? '')}</span>
              </span>
            </div>
          )}
        </div>
      )}
      <span class="me-foot">{t('Continue watching always comes right after the featured banner when it has something.')}</span>
      {hiddenCount > 0 && (
        <span class="me-foot">
          {hiddenText(prefs.hiddenRows?.length ?? 0, prefs.hiddenSeeds?.length ?? 0)}
          {' · '}
          <button type="button" class="link-btn me-inline-link" onClick={() => setHiddenOpen(true)}>{t('Show them again')}</button>
        </span>
      )}
      <HiddenSheet open={hiddenOpen} onClose={() => setHiddenOpen(false)} />
    </section>
  );
}

function hiddenText(rows: number, seeds: number) {
  const a = rows ? tn(rows, '{n} row you hid from Home', '{n} rows you hid from Home') : '';
  const b = seeds ? tn(seeds, '{n} title not used for Because you watched', '{n} titles not used for Because you watched') : '';
  return a && b ? t('{a} and {b}', { a, b }) : a || b;
}

/* Rows and Because-you-watched titles hidden from Home's row menus, with a way back. */
function HiddenSheet({ open, onClose }: { open: boolean; onClose: () => void }) {
  const me = useStore(meStore);
  const views = useStore(viewsStore);
  const prefs = me.prefs;
  const ids = useMemo(() => {
    const set = new Set<string>(prefs.hiddenSeeds ?? []);
    for (const r of prefs.hiddenRows ?? []) {
      const [kind, ...rest] = r.split(':');
      if (kind === 'because' || kind === 'collection') set.add(rest.join(':'));
    }
    return [...set].filter(Boolean);
  }, [prefs.hiddenRows, prefs.hiddenSeeds]);
  const names = useAsync(
    () => (open && ids.length ? getItems({ ids, fields: [], enableImageTypes: [], limit: ids.length }).then((r) => Object.fromEntries(r.Items.map((i) => [i.Id, i.Name]))) : Promise.resolve({} as Record<string, string>)),
    [open, ids.join(',')]
  );
  const n = names.data ?? {};
  const label = (id: string) => {
    const [kind, ...rest] = id.split(':');
    const arg = rest.join(':');
    if (kind === 'because') return { name: t('Because you watched {title}', { title: n[arg] ?? '…' }), sub: t('Row') };
    if (kind === 'latest') { const v = views.find((x) => x.Id === arg); return { name: v ? t('Latest {library}', { library: v.Name.toLowerCase(), name: v.Name }) : t('Latest'), sub: t('Row') }; }
    if (kind === 'genre' || kind === 'genres') return { name: arg || t('Genre row'), sub: t('Genre row') };
    if (kind === 'collection') return { name: n[arg] ?? '…', sub: t('Collection row') };
    const info = ROW_INFO[kind as RowKind];
    return { name: info ? t(info.name) : id, sub: t('Row') };
  };
  const restoreRow = (id: string) => savePrefsPatch({ hiddenRows: (meStore.get().prefs.hiddenRows ?? []).filter((r) => r !== id) });
  const restoreSeed = (id: string) => savePrefsPatch({ hiddenSeeds: (meStore.get().prefs.hiddenSeeds ?? []).filter((r) => r !== id) });
  const empty = !(prefs.hiddenRows?.length || prefs.hiddenSeeds?.length);
  useEffect(() => { if (open && empty) onClose(); }, [open, empty]);

  return (
    <Sheet open={open} onClose={onClose} label={t('Hidden from Home')}>
      <div class="sheet-head">
        <h2 class="sheet-title">{t('Hidden from Home')}</h2>
        <button type="button" class="btn icon-btn is-bare is-40" aria-label={t('Close')} onClick={onClose}><Icon name="close" size={18} /></button>
      </div>
      <p class="sheet-text">{t('Rows you hid from Home’s row menus, and titles you stopped from starting a Because you watched row.')}</p>
      <div class="me-hidden-list">
        {(prefs.hiddenRows ?? []).map((id) => {
          const l = label(id);
          return (
            <div key={id} class="me-hidden-item">
              <span class="rows-text"><span class="rows-name">{l.name}</span><span class="rows-help">{l.sub}</span></span>
              <button type="button" class="btn btn-secondary is-xs" aria-label={t('Show {name} again', { name: l.name })} onClick={() => restoreRow(id)}>{t('Show again')}</button>
            </div>
          );
        })}
        {(prefs.hiddenSeeds ?? []).map((id) => (
          <div key={id} class="me-hidden-item">
            <span class="rows-text"><span class="rows-name">{n[id] ?? '…'}</span><span class="rows-help">{t('Not used for Because you watched')}</span></span>
            <button type="button" class="btn btn-secondary is-xs" aria-label={t('Use {title} again', { title: n[id] ?? t('title') })} onClick={() => restoreSeed(id)}>{t('Use again')}</button>
          </div>
        ))}
      </div>
    </Sheet>
  );
}

/* ── Libraries ── */

const NOUNS: Record<string, [string, string]> = {
  movies: ['movie', 'movies'], tvshows: ['show', 'shows'], boxsets: ['collection', 'collections'], music: ['album', 'albums'],
  musicvideos: ['music video', 'music videos'], playlists: ['playlist', 'playlists'], books: ['book', 'books']
};

function countLabel(n: number, ct?: string) {
  const [one, many] = NOUNS[ct ?? ''] ?? ['item', 'items'];
  return tn(n, `{n} ${one}`, `{n} ${many}`);
}

function LibrariesSection() {
  const user = useStore(userStore);
  const cfg = userConfig(user);
  const listRef = useRef<HTMLDivElement>(null);
  const [tick, setTick] = useState(0);
  const libs = useAsync(
    () => get<ItemsResult>('/UserViews', { userId: uid(), includeHidden: true, includeExternalContent: false }).then((r) => r.Items),
    [tick]
  );
  const counts = useAsync(
    () => Promise.all((libs.data ?? []).map((v) =>
      getItems({ parentId: v.Id, includeItemTypes: typesFor(v.CollectionType), limit: 0, enableImageTypes: [], fields: [] })
        .then((r) => [v.Id, r.TotalRecordCount] as const, () => [v.Id, -1] as const))).then((l) => Object.fromEntries(l)),
    [libs.data?.map((v) => v.Id).join()]
  );
  const opensOn = useCustom(CUSTOM.opensOn);
  const list = libs.data ?? [];
  /* Show a new order straight away; the server's answer replaces it. */
  const [order, setOrder] = useState<BaseItem[] | null>(null);
  useEffect(() => setOrder(null), [libs.data]);
  const shown = order ?? list;

  const toggle = (key: 'MyMediaExcludes' | 'LatestItemsExcludes', id: string, on: boolean) => {
    const cur = userConfig()[key] ?? [];
    const next = on ? cur.filter((x) => x !== id) : [...cur.filter((x) => x !== id), id];
    saveUserConfig({ [key]: next }, { refreshViews: key === 'MyMediaExcludes' });
  };

  const sort = useSortable({
    ids: shown.map((v) => v.Id),
    listRef,
    itemSelector: '.me-lib-row',
    name: (id) => shown.find((v) => v.Id === id)?.Name ?? t('Library'),
    onMove: (from, to) => {
      const next = moveItem(shown, from, to);
      setOrder(next);
      saveUserConfig({ OrderedViews: next.map((v) => v.Id) }, { refreshViews: true }).then(() => setTick((k) => k + 1));
    }
  });
  return (
    <MeSection id="libs" title={t('Libraries')} help={t('The order of tabs and My media tiles, and what each library feeds into Home.')} class="is-gap-14">
      {libs.error ? (
        <p class="me-foot">{t("Couldn't load your libraries.")} <button type="button" class="link-btn me-inline-link" onClick={() => setTick((k) => k + 1)}>{t('Try again')}</button></p>
      ) : (
        <div class="me-table-scroll">
          <div role="table" aria-label={t('Libraries on Home')} class="me-libs">
            <div role="row" class="me-lib-head">
              <span role="columnheader" class="me-th"><span class="sr-only">{t('Order')}</span></span>
              <span role="columnheader" class="me-th">{t('Library')}</span>
              <span role="columnheader" class="me-th is-center">{t('My media')}</span>
              <span role="columnheader" class="me-th is-center">{t('Home rows')}</span>
              <span role="columnheader" class="me-th">{t('Opens on')}</span>
            </div>
            <div class="me-sort me-lib-body" ref={listRef} role="rowgroup">
              <span id="me-libs-hint" class="sr-only">{t('Drag to move, or use the up and down arrow keys.')}</span>
              <div class="sr-only" role="status" aria-live="polite">{sort.live}</div>
              {libs.loading && !libs.data && Array.from({ length: 3 }, (_, i) => (
                <div key={i} role="row" class="me-lib-row"><span class="skel" style={{ gridColumn: '1 / -1', height: '40px', borderRadius: '12px', margin: '8px 0' }} /></div>
              ))}
              {shown.map((v, i) => {
                const tabs = tabsFor(v.CollectionType);
                const opens = tabs.some((tab) => tab.id === opensOn[v.Id]) ? opensOn[v.Id] : tabs[0].id;
                const n = counts.data?.[v.Id];
                return (
                  <div key={v.Id} data-id={v.Id} role="row" class={'me-lib-row' + (sort.drag?.id === v.Id ? ' is-dragging' : '')}>
                    <div role="cell" class="me-td is-grip">
                      <button type="button" class="rows-grip me-grip" aria-label={t('Move {name}', { name: v.Name })} aria-describedby="me-libs-hint" {...sort.gripProps(i)}><GripIcon /></button>
                    </div>
                    <div role="cell" class="me-td me-lib-name">
                      <span class="me-lib-title">{v.Name}</span>
                      <span class="me-lib-count">{n === undefined ? ' ' : n < 0 ? '' : countLabel(n, v.CollectionType)}</span>
                    </div>
                    <div role="cell" class="me-td is-center">
                      <input type="checkbox" class="me-check" checked={!(cfg.MyMediaExcludes ?? []).includes(v.Id)} aria-label={t('Show {name} in My media', { name: v.Name })}
                        onChange={(e) => toggle('MyMediaExcludes', v.Id, (e.currentTarget as HTMLInputElement).checked)} />
                    </div>
                    <div role="cell" class="me-td is-center">
                      <input type="checkbox" class="me-check" checked={!(cfg.LatestItemsExcludes ?? []).includes(v.Id)} aria-label={t('Use {name} in Continue watching and Latest', { name: v.Name })}
                        onChange={(e) => toggle('LatestItemsExcludes', v.Id, (e.currentTarget as HTMLInputElement).checked)} />
                    </div>
                    <div role="cell" class="me-td">
                      <span class="select-wrap me-opens">
                        <select
                          class="select"
                          aria-label={t('{name} opens on', { name: v.Name })}
                          value={opens}
                          disabled={tabs.length < 2}
                          onChange={(e) => {
                            const tab = (e.currentTarget as HTMLSelectElement).value;
                            const map = { ...readCustom(CUSTOM.opensOn) };
                            if (tab === tabs[0].id) delete map[v.Id]; else map[v.Id] = tab;
                            saveCustom({ [CUSTOM.opensOn]: map });
                          }}
                        >
                          {tabs.map((tab) => <option key={tab.id} value={tab.id}>{tab.id === 'collections' ? t('All collections') : t(tab.label)}</option>)}
                        </select>
                        <Icon name="chevronDown" size={15} stroke={2.2} />
                      </span>
                    </div>
                  </div>
                );
              })}
              <div class={'me-line' + (sort.lineY !== null ? ' is-on' : '')} aria-hidden="true" style={sort.lineY !== null ? { transform: `translateY(${sort.lineY}px)` } : undefined} />
              {sort.drag && (
                <div class="me-ghost" aria-hidden="true" style={{ transform: `translateY(${sort.drag.top + sort.drag.dy}px)`, height: sort.drag.height + 'px' }}>
                  <span class="rows-grip"><GripIcon /></span>
                  <span class="rows-text"><span class="rows-name">{shown.find((v) => v.Id === sort.drag!.id)?.Name}</span></span>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </MeSection>
  );
}

/* ── Next up and Continue watching ── */

function NextUpSection() {
  const days = useCustom(CUSTOM.nextUpDays);
  const rewatching = useCustom(CUSTOM.rewatching);
  const episodeImages = useCustom(CUSTOM.episodeImages);
  return (
    <MeSection id="nextup" title={t('Next up and Continue watching')}>
      <MeRow name={t('Drop a show from Next up after')} help={t('Days since you last watched it')}>
        <NumField value={days} min={0} max={3650} width={52} suffix={t('days')} ariaLabel={t('Drop a show from Next up after, in days')}
          onChange={(v) => saveCustom({ [CUSTOM.nextUpDays]: v }, 400)} />
      </MeRow>
      <MeRow name={t('Include shows I’m rewatching')} help={t('Show the next episode even if you’ve seen it before')}>
        <Switch label={t('Include shows I’m rewatching')} checked={rewatching} onChange={(v) => saveCustom({ [CUSTOM.rewatching]: v })} />
      </MeRow>
      <MeRow name={t('Use episode pictures')} help={t('Instead of the show’s artwork')}>
        <Switch label={t('Use episode pictures')} checked={episodeImages} onChange={(v) => saveCustom({ [CUSTOM.episodeImages]: v })} />
      </MeRow>
    </MeSection>
  );
}

/* ── Latest and folders ── */

function LatestSection() {
  const user = useStore(userStore);
  const cfg = userConfig(user);
  const [open, setOpen] = useState(false);
  const options = useAsync(() => get<{ Id: string; Name: string }[]>('/UserViews/GroupingOptions', { userId: uid() }), []);
  const grouped = (cfg.GroupedFolders ?? []).filter((id) => options.data?.some((o) => o.Id === id));
  const total = options.data?.length ?? 0;
  return (
    <MeSection id="latest" title={t('Latest and folders')}>
      <MeRow name={t('Hide what I’ve watched from Latest')} help={t('Only new-to-you titles in Latest rows')}>
        <Switch label={t('Hide what I’ve watched from Latest')} checked={cfg.HidePlayedInLatest !== false} onChange={(v) => saveUserConfig({ HidePlayedInLatest: v })} />
      </MeRow>
      <MeRow name={t('Combine folders into Movies and Shows')} help={t('For libraries set up as plain folders')}>
        <button type="button" class="me-chip-link" aria-haspopup="dialog" disabled={!total} onClick={() => setOpen(true)}>
          {options.loading ? '…' : total ? t('{n} of {total} combined', { n: grouped.length, total }) : t('No folders to combine')}
        </button>
      </MeRow>
      <Sheet open={open} onClose={() => setOpen(false)} label={t('Combine folders into Movies and Shows')}>
        <div class="sheet-head">
          <h2 class="sheet-title">{t('Combine folders')}</h2>
          <button type="button" class="btn icon-btn is-bare is-40" aria-label={t('Close')} onClick={() => setOpen(false)}><Icon name="close" size={18} /></button>
        </div>
        <p class="sheet-text">{t('Ticked libraries stop being tabs of their own: their movies join a Movies tab and their shows a Shows tab.')}</p>
        <div class="me-hidden-list">
          {(options.data ?? []).map((o) => {
            const on = (cfg.GroupedFolders ?? []).includes(o.Id);
            return (
              <label key={o.Id} class="me-hidden-item me-check-row">
                <span class="rows-name">{o.Name}</span>
                <input type="checkbox" class="me-check" checked={on} onChange={(e) => {
                  const cur = userConfig().GroupedFolders ?? [];
                  const v = (e.currentTarget as HTMLInputElement).checked;
                  saveUserConfig({ GroupedFolders: v ? [...cur.filter((x) => x !== o.Id), o.Id] : cur.filter((x) => x !== o.Id) }, { refreshViews: true });
                }} />
              </label>
            );
          })}
        </div>
        <div class="sheet-actions"><button type="button" class="btn btn-primary is-sm" onClick={() => setOpen(false)}>{t('Done')}</button></div>
      </Sheet>
    </MeSection>
  );
}
