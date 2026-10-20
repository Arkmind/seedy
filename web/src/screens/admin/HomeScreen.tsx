import type { RefObject } from 'preact';
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { cardArt } from '../../api/images';
import { getGenres, getItems } from '../../api/jellyfin';
import type { AdminConfig, HomeRowSetting, RowKind } from '../../api/seedy';
import type { BaseItem } from '../../api/types';
import { yearRange } from '../../lib/format';
import { Art } from '../../ui/cards';
import { NumField, Panel, Segmented, SelectField, SettingRow, Stepper, Switch } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { MenuItem, Popover, Sheet, toast } from '../../ui/overlay';
import { t, tn } from '../../lib/i18n';
import { AdminLayout, LoadError, PanelSkeletons } from './AdminLayout';
import { LOCKED_ROWS, saveSection, setLeaveGuard, useAdminConfig } from './config';

type Home = AdminConfig['home'];

const ROW_INFO: Record<RowKind, { name: string; help: string; seerr?: boolean; count?: { min: number; max: number } }> = {
  featured: { name: 'Featured', help: 'Always first' },
  continue: { name: 'Continue watching', help: 'Always second when it has anything' },
  nextup: { name: 'Next up', help: 'Next unwatched episode of shows in progress' },
  top10: { name: 'Top 10 this week', help: 'Most watched on this server' },
  because: { name: 'Because you watched', help: 'One row per recently watched title', count: { min: 1, max: 10 } },
  genres: { name: 'Genre rows', help: 'Genres each person watches most', count: { min: 1, max: 8 } },
  mymedia: { name: 'My media', help: 'One tile per library' },
  latest: { name: 'Latest in each library', help: 'Newest additions' },
  discover: { name: 'Discover', help: 'Titles to request', seerr: true },
  upcomingMovies: { name: 'Upcoming movies', help: 'Requested movies not out yet', seerr: true },
  upcomingShows: { name: 'Upcoming shows', help: 'Next episodes of shows in the library' },
  collections: { name: 'Collections', help: 'One row per collection' }
};

const LOOK_BACK = [
  { value: 30, label: '30 days' },
  { value: 60, label: '60 days' },
  { value: 90, label: '90 days' },
  { value: 0, label: 'No limit' }
];

/* Option labels are English constants, translated at render. */
const tr = <T,>(options: { value: T; label: string }[]) => options.map((o) => ({ ...o, label: t(o.label) }));

const GENRE_WINDOW = [
  { value: 30, label: 'Last 30 days' },
  { value: 90, label: 'Last 90 days' },
  { value: 365, label: 'Last year' }
];

/* Fields that differ between the draft and what's saved; reordering the rows counts once. */
function countChanges(a: Home, b: Home): number {
  let n = 0;
  if (a.rows.map((r) => r.kind).join() !== b.rows.map((r) => r.kind).join()) n++;
  for (const r of a.rows) {
    const o = b.rows.find((x) => x.kind === r.kind);
    if (!o) { n++; continue; }
    if (r.enabled !== o.enabled) n++;
    if ((r.count ?? 0) !== (o.count ?? 0)) n++;
  }
  const pairs: [unknown, unknown][] = [
    [a.adaptOrder, b.adaptOrder],
    [a.because.watchedWhen, b.because.watchedWhen],
    [a.because.lookBackDays, b.because.lookBackDays],
    [a.because.minTitles, b.because.minTitles],
    [a.top10.periodDays, b.top10.periodDays],
    [a.top10.minProfiles, b.top10.minProfiles],
    [a.genres.windowDays, b.genres.windowDays],
    [a.genres.starting.join('\u0000'), b.genres.starting.join('\u0000')],
    [a.featured.mode, b.featured.mode],
    [a.featured.picks.join(), b.featured.picks.join()]
  ];
  for (const [x, y] of pairs) if (x !== y) n++;
  return n;
}

export function AdminHome() {
  const { config, error, reload } = useAdminConfig();
  const [draft, setDraft] = useState<Home | null>(null);
  const [saving, setSaving] = useState(false);
  const saved = config?.home;
  const home = draft ?? saved;
  const changes = draft && saved ? countChanges(draft, saved) : 0;
  const dirty = changes > 0;

  const dirtyRef = useRef(false);
  dirtyRef.current = dirty;
  useEffect(() => {
    setLeaveGuard(() => dirtyRef.current);
    return () => setLeaveGuard(null);
  }, []);
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ''; };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);

  const edit = (fn: (h: Home) => Home) => setDraft((d) => fn(d ?? saved!));

  const save = async () => {
    if (!draft) return;
    setSaving(true);
    try {
      await saveSection('home', draft);
      setDraft(null);
      toast(t('Home screen saved'));
    } catch (e) {
      toast(`${t('Couldn’t save.')} ${(e as Error).message || t('Check the server log.')}`, 'error');
    } finally {
      setSaving(false);
    }
  };

  return (
    <AdminLayout page="home" title={t('Home screen')} lede={t('The home screen every new profile starts with. After that, row order adapts to each person unless they or you turn it off.')}>
      {!home && error ? (
        <LoadError error={error} onRetry={reload} />
      ) : !home ? (
        <PanelSkeletons count={3} rows={4} />
      ) : (
        <>
          <Panel id="rows" title={t('Rows')} text={t('Drag to set the starting order. Switched-off rows are hidden for everyone.')} gap={14}>
            <RowsEditor rows={home.rows} onChange={(rows) => edit((h) => ({ ...h, rows }))} />
            <div class="setting is-last">
              <span class="setting-label">
                <span class="setting-name">{t('Adapt row order to each person')}</span>
                <span class="setting-help is-long">{t('Rows people play from move up, recalculated once a day. Featured and Continue watching never move. Each profile can turn this off.')}</span>
              </span>
              <Switch label={t('Adapt row order to each person')} checked={home.adaptOrder} onChange={(v) => edit((h) => ({ ...h, adaptOrder: v }))} />
            </div>
          </Panel>

          <Panel id="byw" title={t('Because you watched')}>
            <SettingRow name={t('A title counts as watched when')} help={t('Only watched titles start a row')}>
              <Segmented
                label={t('A title counts as watched when')}
                value={home.because.watchedWhen}
                options={[{ value: 'finished', label: t('Finished') }, { value: 'half', label: t('Half watched') }]}
                onChange={(v) => edit((h) => ({ ...h, because: { ...h.because, watchedWhen: v } }))}
              />
            </SettingRow>
            <SettingRow name={t('Look back')} help={t('Older plays never start a row')} htmlFor="byw-window">
              <SelectField
                id="byw-window"
                minWidth={180}
                value={LOOK_BACK.some((o) => o.value === home.because.lookBackDays) ? home.because.lookBackDays : 60}
                options={tr(LOOK_BACK)}
                onChange={(v) => edit((h) => ({ ...h, because: { ...h.because, lookBackDays: v } }))}
              />
            </SettingRow>
            <SettingRow name={t('Smallest row')} help={t('Rows with fewer titles are dropped')} last>
              <NumField
                suffix={t('titles')}
                width={44}
                min={1}
                max={12}
                value={home.because.minTitles}
                ariaLabel={t('Smallest row, in titles')}
                onChange={(v) => edit((h) => ({ ...h, because: { ...h.because, minTitles: v } }))}
              />
            </SettingRow>
          </Panel>

          <Panel id="top" title="Top 10">
            <SettingRow name={t('Period')} help={t('Each title counts once per profile')}>
              <Segmented
                label={t('Top 10 period')}
                value={home.top10.periodDays}
                options={[{ value: 7, label: t('7 days') }, { value: 30, label: t('30 days') }]}
                onChange={(v) => edit((h) => ({ ...h, top10: { ...h.top10, periodDays: v } }))}
              />
            </SettingRow>
            <SettingRow name={t('Hide on quiet weeks')} help={t('When fewer profiles than this watched anything')} last>
              <NumField
                suffix={t('profiles')}
                width={44}
                min={1}
                max={50}
                value={home.top10.minProfiles}
                ariaLabel={t('Minimum active profiles')}
                onChange={(v) => edit((h) => ({ ...h, top10: { ...h.top10, minProfiles: v } }))}
              />
            </SettingRow>
          </Panel>

          <Panel id="genre" title={t('Genre rows')}>
            <SettingRow name={t('Pick genres from')} help={t('Titles each person finished in this period')} htmlFor="genre-window">
              <SelectField
                id="genre-window"
                minWidth={180}
                value={GENRE_WINDOW.some((o) => o.value === home.genres.windowDays) ? home.genres.windowDays : 90}
                options={tr(GENRE_WINDOW)}
                onChange={(v) => edit((h) => ({ ...h, genres: { ...h.genres, windowDays: v } }))}
              />
            </SettingRow>
            <SettingRow name={t('Starting genres')} help={t('For new profiles, until they have watched enough')} stack last>
              <GenreChips genres={home.genres.starting} onChange={(starting) => edit((h) => ({ ...h, genres: { ...h.genres, starting } }))} />
            </SettingRow>
          </Panel>

          <Panel id="feat" title={t('Featured')}>
            <SettingRow name={t('What the hero shows')} help={t('5 titles in rotation')} last={home.featured.mode === 'auto'}>
              <Segmented
                label={t('What the hero shows')}
                value={home.featured.mode}
                options={[{ value: 'auto', label: t('Automatic') }, { value: 'picks', label: t('Your picks') }, { value: 'both', label: t('Both') }]}
                onChange={(v) => edit((h) => ({ ...h, featured: { ...h.featured, mode: v } }))}
              />
            </SettingRow>
            {home.featured.mode !== 'auto' && (
              <SettingRow
                name={t('Your picks')}
                help={home.featured.mode === 'picks' ? t('Shown in this order; recently added titles fill in if there are fewer than 5') : t('Shown first; recently added and in-progress titles fill the rest')}
                stack
                last
              >
                <Picks picks={home.featured.picks} onChange={(picks) => edit((h) => ({ ...h, featured: { ...h.featured, picks } }))} />
              </SettingRow>
            )}
          </Panel>

          {dirty && (
            <div class="unsaved" role="region" aria-label={t('Unsaved changes')}>
              <span class="unsaved-text" aria-live="polite"><span class="dot is-pending" />{tn(changes, '{n} unsaved change', '{n} unsaved changes')}</span>
              <div class="setting-controls">
                <button type="button" class="btn btn-ghost is-light" disabled={saving} onClick={() => setDraft(null)}>{t('Discard')}</button>
                <button type="button" class="btn btn-primary is-sm is-save" disabled={saving} onClick={() => void save()}>{saving ? t('Saving…') : t('Save changes')}</button>
              </div>
            </div>
          )}
        </>
      )}
    </AdminLayout>
  );
}

/* ── Rows editor: drag the grip (pointer) or focus it and use ↑/↓ ── */

interface DragState { kind: RowKind; from: number; to: number; dy: number }

function moveItem<T>(list: T[], from: number, to: number): T[] {
  const next = list.slice();
  const [it] = next.splice(from, 1);
  next.splice(to, 0, it);
  return next;
}

function RowsEditor({ rows, onChange }: { rows: HomeRowSetting[]; onChange: (rows: HomeRowSetting[]) => void }) {
  const listRef = useRef<HTMLDivElement>(null);
  const grips = useRef(new Map<RowKind, HTMLButtonElement>());
  const [drag, setDrag] = useState<DragState | null>(null);
  const [live, setLive] = useState('');
  const focusAfter = useRef<RowKind | null>(null);
  const flip = useRef<Map<RowKind, number> | null>(null);
  const info = useRef<{
    pointerId: number; from: number; grab: number; tops: number[]; heights: number[]; lastY: number; raf: number; to: number; dy: number;
  } | null>(null);

  const firstMovable = rows.findIndex((r) => !LOCKED_ROWS.has(r.kind));
  const last = rows.length - 1;

  /* Remember where every row is now so the reorder can animate from there (FLIP). */
  const snapshot = (dragged?: RowKind) => {
    const map = new Map<RowKind, number>();
    listRef.current?.querySelectorAll<HTMLElement>('.rows-item').forEach((el) => {
      map.set(el.dataset.kind as RowKind, el.getBoundingClientRect().top);
    });
    /* A dropped row slides in from where its ghost was let go. */
    const ghost = listRef.current?.querySelector<HTMLElement>('.rows-ghost');
    if (dragged && ghost) map.set(dragged, ghost.getBoundingClientRect().top);
    flip.current = map;
  };

  useLayoutEffect(() => {
    const before = flip.current;
    flip.current = null;
    if (before && listRef.current) {
      const els = Array.from(listRef.current.querySelectorAll<HTMLElement>('.rows-item'));
      const moved: HTMLElement[] = [];
      for (const el of els) {
        const old = before.get(el.dataset.kind as RowKind);
        if (old === undefined) continue;
        const delta = old - el.getBoundingClientRect().top;
        if (Math.abs(delta) < 1) continue;
        el.style.transition = 'none';
        el.style.transform = `translateY(${delta}px)`;
        moved.push(el);
      }
      if (moved.length) {
        void listRef.current.offsetHeight;
        for (const el of moved) {
          el.style.transition = 'transform 220ms cubic-bezier(0.2, 0.8, 0.2, 1)';
          el.style.transform = '';
          const done = () => { el.style.transition = ''; el.removeEventListener('transitionend', done); };
          el.addEventListener('transitionend', done);
        }
      }
    }
    if (focusAfter.current) {
      grips.current.get(focusAfter.current)?.focus({ preventScroll: false });
      focusAfter.current = null;
    }
  }, [rows]);

  const announce = (kind: RowKind, index: number) => {
    setLive(t('{row} moved to position {pos} of {total}.', { row: t(ROW_INFO[kind].name), pos: index + 1, total: rows.length }));
  };

  /* Pointer dragging. Measurements are taken relative to the list so page scroll during the drag is fine. */
  const update = (clientY: number) => {
    const d = info.current;
    const list = listRef.current;
    if (!d || !list) return;
    const top = clientY - list.getBoundingClientRect().top - d.grab;
    const minTop = d.tops[firstMovable];
    const maxTop = d.tops[last] + d.heights[last] - d.heights[d.from];
    const clamped = Math.max(minTop - 8, Math.min(maxTop + 8, top));
    const center = clamped + d.heights[d.from] / 2;
    let n = 0;
    for (let i = firstMovable; i <= last; i++) {
      if (i === d.from) continue;
      if (d.tops[i] + d.heights[i] / 2 < center) n++;
    }
    d.to = firstMovable + n;
    d.dy = clamped - d.tops[d.from];
    setDrag({ kind: rows[d.from].kind, from: d.from, to: d.to, dy: d.dy });
  };

  const autoScroll = () => {
    const d = info.current;
    if (!d) return;
    const edge = 72;
    const y = d.lastY;
    let speed = 0;
    if (y < edge) speed = -Math.ceil(((edge - y) / edge) * 14);
    else if (y > window.innerHeight - edge) speed = Math.ceil(((y - (window.innerHeight - edge)) / edge) * 14);
    if (speed) {
      window.scrollBy(0, speed);
      update(y);
    }
    d.raf = requestAnimationFrame(autoScroll);
  };

  const endDrag = (commit: boolean) => {
    const d = info.current;
    if (!d) return;
    cancelAnimationFrame(d.raf);
    info.current = null;
    document.body.classList.remove('is-row-dragging');
    if (commit && d.to !== d.from) {
      snapshot(rows[d.from].kind);
      const kind = rows[d.from].kind;
      focusAfter.current = kind;
      announce(kind, d.to);
      onChange(moveItem(rows, d.from, d.to));
    }
    setDrag(null);
  };

  const onPointerDown = (e: PointerEvent, index: number) => {
    if (e.button !== 0 || !listRef.current) return;
    (e.currentTarget as HTMLButtonElement).setPointerCapture(e.pointerId);
    const els = Array.from(listRef.current.querySelectorAll<HTMLElement>('.rows-item'));
    const tops = els.map((el) => el.offsetTop);
    const heights = els.map((el) => el.offsetHeight);
    const listTop = listRef.current.getBoundingClientRect().top;
    info.current = { pointerId: e.pointerId, from: index, grab: e.clientY - listTop - tops[index], tops, heights, lastY: e.clientY, raf: 0, to: index, dy: 0 };
    info.current.raf = requestAnimationFrame(autoScroll);
    document.body.classList.add('is-row-dragging');
    setDrag({ kind: rows[index].kind, from: index, to: index, dy: 0 });
  };

  const onPointerMove = (e: PointerEvent) => {
    const d = info.current;
    if (!d || e.pointerId !== d.pointerId) return;
    d.lastY = e.clientY;
    update(e.clientY);
  };

  const onKeyDown = (e: KeyboardEvent, index: number) => {
    if (info.current) {
      if (e.key === 'Escape') { e.preventDefault(); endDrag(false); }
      return;
    }
    let to = index;
    if (e.key === 'ArrowUp') to = index - 1;
    else if (e.key === 'ArrowDown') to = index + 1;
    else if (e.key === 'Home') to = firstMovable;
    else if (e.key === 'End') to = last;
    else return;
    e.preventDefault();
    const kind = rows[index].kind;
    if (to < firstMovable || to > last || to === index) {
      setLive(to < firstMovable
        ? t("{row} can't move above {other}.", { row: t(ROW_INFO[kind].name), other: t(ROW_INFO[rows[firstMovable - 1]?.kind ?? 'continue'].name) })
        : t('{row} is already last.', { row: t(ROW_INFO[kind].name) }));
      return;
    }
    snapshot();
    focusAfter.current = kind;
    announce(kind, to);
    onChange(moveItem(rows, index, to));
  };

  /* Where the accent insertion line goes: the gap above the row the dragged one will land in front of. */
  let lineY: number | null = null;
  if (drag && info.current && drag.to !== drag.from) {
    const { tops, heights } = info.current;
    const others = rows.map((_, i) => i).filter((i) => i !== drag.from);
    const before = others[drag.to];
    lineY = before !== undefined ? tops[before] - 3 : tops[last] + heights[last] + 3;
  }

  const setRow = (kind: RowKind, patch: Partial<HomeRowSetting>) => onChange(rows.map((r) => (r.kind === kind ? { ...r, ...patch } : r)));

  return (
    <div class="rows-list" ref={listRef}>
      <span id="rows-move-hint" class="sr-only">{t('Drag to move, or use the up and down arrow keys.')}</span>
      <div class="sr-only" role="status" aria-live="polite">{live}</div>
      {rows.map((r, i) => {
        const meta = ROW_INFO[r.kind];
        const name = t(meta.name);
        const locked = LOCKED_ROWS.has(r.kind);
        const dragging = drag?.kind === r.kind;
        const count = r.count ?? 1;
        return (
          <div key={r.kind} data-kind={r.kind} class={'rows-item' + (dragging ? ' is-dragging' : '')}>
            {locked ? (
              <span class="rows-lock" role="img" aria-label={t('Fixed position')}><Icon name="lock" size={17} /></span>
            ) : (
              <button
                type="button"
                class="rows-grip"
                aria-label={t('Move {row}', { row: name })}
                aria-describedby="rows-move-hint"
                ref={(el) => { if (el) grips.current.set(r.kind, el); else grips.current.delete(r.kind); }}
                onPointerDown={(e) => onPointerDown(e as unknown as PointerEvent, i)}
                onPointerMove={(e) => onPointerMove(e as unknown as PointerEvent)}
                onPointerUp={() => endDrag(true)}
                onPointerCancel={() => endDrag(false)}
                onLostPointerCapture={() => endDrag(true)}
                onKeyDown={(e) => onKeyDown(e as unknown as KeyboardEvent, i)}
              >
                <Icon name="drag" size={18} />
              </button>
            )}
            <span class="rows-text">
              <span class={'rows-name' + (r.enabled ? '' : ' is-off')}>
                {name}
                {meta.seerr && <span class="rows-tag">Seerr</span>}
              </span>
              <span class="rows-help">{t(meta.help)}</span>
            </span>
            {meta.count && (
              <span class="rows-count">
              <Stepper
                label={t('{row}: number of rows', { row: name })}
                valueLabel={t('Up to {n}', { n: count })}
                decLabel={t('Fewer rows')}
                incLabel={t('More rows')}
                decDisabled={count <= meta.count.min}
                incDisabled={count >= meta.count.max}
                onDec={() => setRow(r.kind, { count: Math.max(meta.count!.min, count - 1) })}
                onInc={() => setRow(r.kind, { count: Math.min(meta.count!.max, count + 1) })}
              />
              </span>
            )}
            {!locked && <Switch label={t('Show {row}', { row: name })} checked={r.enabled} onChange={(v) => setRow(r.kind, { enabled: v })} />}
          </div>
        );
      })}
      <div class={'rows-line' + (lineY !== null ? ' is-on' : '')} aria-hidden="true" style={lineY !== null ? { transform: `translateY(${lineY}px)` } : undefined} />
      {drag && info.current && (
        <div class="rows-ghost" aria-hidden="true" style={{ transform: `translateY(${info.current.tops[drag.from] + drag.dy}px)`, height: info.current.heights[drag.from] + 'px' }}>
          <span class="rows-grip"><Icon name="drag" size={18} /></span>
          <span class="rows-text">
            <span class={'rows-name' + (rows[drag.from].enabled ? '' : ' is-off')}>
              {t(ROW_INFO[drag.kind].name)}
              {ROW_INFO[drag.kind].seerr && <span class="rows-tag">Seerr</span>}
            </span>
            <span class="rows-help">{t(ROW_INFO[drag.kind].help)}</span>
          </span>
        </div>
      )}
    </div>
  );
}

/* ── Starting genres ── */

let genreCache: string[] | null = null;

function GenreChips({ genres, onChange }: { genres: string[]; onChange: (g: string[]) => void }) {
  const addRef = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const [all, setAll] = useState<string[] | null>(genreCache);
  const [failed, setFailed] = useState(false);
  const [filter, setFilter] = useState('');

  useEffect(() => {
    if (!open || all) return;
    setFailed(false);
    getGenres().then(
      (r) => {
        genreCache = r.Items.map((g) => g.Name).filter(Boolean);
        setAll(genreCache);
      },
      () => setFailed(true)
    );
  }, [open, all]);
  useEffect(() => { if (open) setFilter(''); }, [open]);

  const chosen = new Set(genres.map((g) => g.toLowerCase()));
  const choices = (all ?? []).filter((g) => !chosen.has(g.toLowerCase()) && g.toLowerCase().includes(filter.trim().toLowerCase()));
  const close = () => setOpen(false);

  return (
    <div class="chips">
      {genres.map((g) => (
        <span class="chip" key={g}>
          {g}
          <button type="button" aria-label={t('Remove {name}', { name: g })} onClick={() => onChange(genres.filter((x) => x !== g))}>
            <Icon name="close" size={13} />
          </button>
        </span>
      ))}
      <button ref={addRef} type="button" class="chip-add" aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={() => setOpen((o) => !o)}>
        <Icon name="plus" size={13} />
        {t('Add genre')}
      </button>
      <Popover anchor={addRef as RefObject<HTMLElement>} open={open} onClose={close} align="start" width={280}>
        <div class="menu genre-menu" role="menu" aria-label={t('Add a starting genre')}>
          {(all?.length ?? 0) > 8 && (
            <label class="search-field">
              <Icon name="search" size={16} />
              <input
                type="search"
                value={filter}
                placeholder={t('Find a genre')}
                aria-label={t('Find a genre')}
                onInput={(e) => setFilter((e.currentTarget as HTMLInputElement).value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && choices[0]) { e.preventDefault(); onChange([...genres, choices[0]]); close(); }
                }}
              />
            </label>
          )}
          {failed ? (
            <span class="menu-item is-muted"><span class="dot is-danger" />{t('Couldn’t load genres')}</span>
          ) : !all ? (
            <span class="menu-item is-muted" aria-busy="true">{t('Loading genres…')}</span>
          ) : choices.length === 0 ? (
            <span class="menu-item is-muted">{filter ? t('No genre matches') : t('Every genre is already added')}</span>
          ) : (
            choices.map((g) => <MenuItem key={g} label={g} onSelect={() => { onChange([...genres, g]); close(); }} />)
          )}
        </div>
      </Popover>
    </div>
  );
}

/* ── Featured picks ── */

const PICK_FIELDS = ['PrimaryImageAspectRatio', 'ProductionYear', 'Genres'];
const itemCache = new Map<string, BaseItem | null>();

function Picks({ picks, onChange }: { picks: string[]; onChange: (p: string[]) => void }) {
  const [, rerender] = useState(0);
  const [open, setOpen] = useState(false);

  useEffect(() => {
    const missing = picks.filter((id) => !itemCache.has(id));
    if (!missing.length) return;
    getItems({ ids: missing, fields: PICK_FIELDS, limit: missing.length }).then(
      (r) => {
        for (const it of r.Items) itemCache.set(it.Id, it);
        for (const id of missing) if (!itemCache.has(id)) itemCache.set(id, null);
        rerender((n) => n + 1);
      },
      () => { /* chips keep their loading look; the ids are still saved */ }
    );
  }, [picks.join()]);

  return (
    <div class="picks">
      {picks.map((id) => {
        const it = itemCache.get(id);
        const title = it ? it.Name : it === null ? t('Title not found') : t('Loading…');
        return (
          <div class="pick" key={id}>
            <span class={'pick-thumb' + (it === undefined ? ' skel' : '')} aria-hidden="true">
              {it && <Art art={cardArt(it, 240)} title={it.Name} />}
            </span>
            <span class={'pick-title' + (it ? '' : ' is-muted')}>{title}</span>
            <button type="button" class="pick-remove" aria-label={it?.Name != null ? t('Remove {title} from picks', { title: it.Name }) : t('Remove this title from picks')} onClick={() => onChange(picks.filter((x) => x !== id))}>
              <Icon name="close" size={14} />
            </button>
          </div>
        );
      })}
      <button type="button" class="pick-add" aria-haspopup="dialog" onClick={() => setOpen(true)}>
        <Icon name="plus" size={15} />
        {t('Add a title')}
      </button>
      <PickSheet
        open={open}
        picks={picks}
        onClose={() => setOpen(false)}
        onToggle={(item) => {
          itemCache.set(item.Id, item);
          onChange(picks.includes(item.Id) ? picks.filter((x) => x !== item.Id) : [...picks, item.Id]);
        }}
      />
    </div>
  );
}

function PickSheet({ open, picks, onClose, onToggle }: { open: boolean; picks: string[]; onClose: () => void; onToggle: (item: BaseItem) => void }) {
  const [term, setTerm] = useState('');
  const [state, setState] = useState<{ items?: BaseItem[]; error?: boolean; loading: boolean }>({ loading: false });
  const searchRef = useRef<HTMLInputElement>(null);

  /* Start in the search field (the sheet itself focuses its first button). */
  useEffect(() => {
    if (!open) return;
    setTerm('');
    const id = requestAnimationFrame(() => requestAnimationFrame(() => searchRef.current?.focus()));
    return () => cancelAnimationFrame(id);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const ctrl = new AbortController();
    setState((s) => ({ ...s, loading: true, error: false }));
    const q = term.trim();
    const timer = window.setTimeout(() => {
      getItems(
        q
          ? { searchTerm: q, includeItemTypes: ['Movie', 'Series'], limit: 20, fields: PICK_FIELDS }
          : { includeItemTypes: ['Movie', 'Series'], sortBy: ['DateCreated'], sortOrder: 'Descending', limit: 20, fields: PICK_FIELDS },
        ctrl.signal
      ).then(
        (r) => { if (!ctrl.signal.aborted) setState({ items: r.Items, loading: false }); },
        (e: Error) => { if (!ctrl.signal.aborted && e.name !== 'AbortError') setState({ error: true, loading: false }); }
      );
    }, q ? 250 : 0);
    return () => { window.clearTimeout(timer); ctrl.abort(); };
  }, [open, term]);

  const n = picks.length;
  return (
    <Sheet open={open} onClose={onClose} label={t('Add a title to Featured')} wide>
      <div class="sheet-head">
        <div>
          <h2 class="sheet-title">{t('Add a title')}</h2>
          <p class="sheet-text">{n ? tn(n, '{n} pick so far.', '{n} picks so far.') + ' ' : ''}{t('Picks show first in the Featured hero.')}</p>
        </div>
        <button type="button" class="btn icon-btn is-40 is-bare" aria-label={t('Close')} onClick={onClose}><Icon name="close" size={16} /></button>
      </div>
      <div class="pick-search">
        <label class="search-field">
          <Icon name="search" size={18} />
          <input
            ref={searchRef}
            type="search"
            value={term}
            placeholder={t('Search movies and shows')}
            aria-label={t('Search movies and shows')}
            onInput={(e) => setTerm((e.currentTarget as HTMLInputElement).value)}
          />
        </label>
        <span class="overline">{term.trim() ? t('Results') : t('Recently added')}</span>
        <div class="pick-results" aria-busy={state.loading ? 'true' : undefined}>
          {state.error ? (
            <p class="pick-status">{t('Couldn’t search the library. Try again in a moment.')}</p>
          ) : !state.items ? (
            [0, 1, 2, 3].map((i) => <span key={i} class="skel" style={{ height: '66px', borderRadius: '13px', flex: 'none' }} />)
          ) : state.items.length === 0 ? (
            <p class="pick-status">{t('Nothing in the library matches “{term}”.', { term: term.trim() })}</p>
          ) : (
            state.items.map((it) => {
              const on = picks.includes(it.Id);
              return (
                <button key={it.Id} type="button" class="pick-option" aria-pressed={on ? 'true' : 'false'} onClick={() => onToggle(it)}>
                  <span class="pick-thumb" aria-hidden="true"><Art art={cardArt(it, 240)} title={it.Name} /></span>
                  <span class="pick-option-text">
                    <span class="pick-option-title">{it.Name}</span>
                    <span class="pick-option-meta">{[yearRange(it), it.Type === 'Series' ? t('Show') : t('Movie')].filter(Boolean).join(' · ')}</span>
                  </span>
                  {on && <Icon name="check" size={20} stroke={2.6} class="tick" />}
                </button>
              );
            })
          )}
        </div>
      </div>
      <div class="sheet-actions">
        <button type="button" class="btn btn-primary is-sm" onClick={onClose}>{t('Done')}</button>
      </div>
    </Sheet>
  );
}
