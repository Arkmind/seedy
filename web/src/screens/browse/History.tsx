import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import {
  clearHistory, getHistory, getHistoryOptions, hideHistoryTitle, removeHistoryEntry, type HistoryEntry, type HistoryQuery
} from '../../api/seedy';
import { cardArt } from '../../api/images';
import type { BaseItem } from '../../api/types';
import { meStore } from '../../lib/app-state';
import { clock, episodeTag, timeLeft, fmtWeekdayDate, historyGroup, plural, ticksToSeconds, timecode } from '../../lib/format';
import { Link, navigate, setQuery, useLocation } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { Art, Progress, WatchedCheck, playPath } from '../../ui/cards';
import { Header } from '../../ui/navbar/Header';
import { Icon } from '../../ui/icons';
import { MeduState } from '../../ui/medu';
import { MenuButton, MenuItem, Sheet, confirm, toast } from '../../ui/overlay';
import { FilterChips, FilterGroup, FilterOption, FiltersButton, SortButton, type FilterChip, type SortOption, type SortOrder } from './sort-filter';
import './history.css';
import { t, tc } from '../../lib/i18n';
import { useTitle } from '../../lib/title';

type Kind = 'all' | 'movies' | 'shows';
type HistorySort = 'date' | 'title';
type PlayState = 'finished' | 'inprogress';
const PAGE = 30;

const KINDS: { value: Kind; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'movies', label: 'Movies' },
  { value: 'shows', label: 'Shows' }
];

const SORTS = [
  { id: 'date', label: 'Date watched', order: 'desc', asc: 'Oldest first', desc: 'Newest first' },
  { id: 'title', label: 'Title', order: 'asc', asc: 'A to Z', desc: 'Z to A' }
] as const satisfies readonly SortOption<HistorySort>[];

/* ── Filters (kept in the address: ?show=finished&hidden=1&when=2025&devices=TV|Phone) ── */

interface Filters { state?: PlayState; hidden: boolean; when?: string; devices: string[] }
const NO_FILTERS: Filters = { hidden: false, devices: [] };

function readFilters(q: URLSearchParams): Filters {
  const show = q.get('show');
  const when = q.get('when') ?? '';
  return {
    state: show === 'finished' || show === 'inprogress' ? show : undefined,
    hidden: q.get('hidden') === '1',
    when: when === '7d' || when === '30d' || /^\d{4}$/.test(when) ? when : undefined,
    devices: (q.get('devices') ?? '').split('|').map((d) => d.trim()).filter(Boolean)
  };
}

const filterParams = (f: Filters) => ({ show: f.state, hidden: f.hidden ? '1' : undefined, when: f.when, devices: f.devices.join('|') || undefined });
const filterCount = (f: Filters) => (f.state ? 1 : 0) + (f.hidden ? 1 : 0) + (f.when ? 1 : 0) + f.devices.length;
/* Filters that leave plays out (Show hidden only adds some). */
const narrows = (f: Filters) => !!f.state || !!f.when || f.devices.length > 0;

/* The past 7 or 30 days start at midnight, so they line up with the day groups; a year is local time. */
function period(when?: string): { from?: string; to?: string } {
  if (!when) return {};
  const now = new Date();
  if (when === '7d' || when === '30d') return { from: new Date(now.getFullYear(), now.getMonth(), now.getDate() - (when === '7d' ? 6 : 29)).toISOString() };
  const y = Number(when);
  return { from: new Date(y, 0, 1).toISOString(), to: new Date(y + 1, 0, 1).toISOString() };
}

const periodLabel = (when: string) => (when === '7d' ? t('Past 7 days') : when === '30d' ? t('Past 30 days') : when);
const stateLabel = (s: PlayState) => (s === 'finished' ? t('Finished') : t('In progress'));

function withFilters(q: HistoryQuery, f: Filters): HistoryQuery {
  return { ...q, includeHidden: f.hidden || undefined, state: f.state, ...period(f.when), devices: f.devices.join('|') || undefined };
}

/* ── Copy, as on the History board ── */

function titleOf(e: HistoryEntry): string {
  const it = e.item;
  if (!it) return t('Removed title');
  return it.Type === 'Episode' ? it.SeriesName ?? it.Name : it.Name;
}

function subOf(e: HistoryEntry): string {
  const it = e.item;
  if (!it) return t('No longer on the server');
  if (it.Type === 'Episode') return [episodeTag(it), it.Name].filter(Boolean).join(' · ');
  const kind = it.Type === 'Movie' ? t('Movie') : it.Type === 'MusicVideo' ? t('Music video') : it.Type === 'Audio' ? t('Song') : t('Video');
  return [kind, it.ProductionYear].filter(Boolean).join(' · ');
}

function stateOf(e: HistoryEntry): string {
  if (e.finished) return t('Finished');
  const pos = ticksToSeconds(e.positionTicks);
  const run = ticksToSeconds(e.runtimeTicks || e.item?.RunTimeTicks);
  const left = Math.max(0, run - pos);
  if (run <= 0) return t('Stopped at {time}', { time: timecode(pos) });
  return t('Stopped at {time}', { time: timecode(pos) }) + ' · ' + (left < 60 ? t('under a minute left') : timeLeft(left));
}

function whenOf(e: HistoryEntry, recent: boolean): string {
  const d = new Date(e.startedAt);
  const device = e.deviceName || e.client;
  return [recent ? '' : fmtWeekdayDate(d), clock(d), device].filter(Boolean).join(' · ');
}

const isMovieLike = (it?: BaseItem) => !!it && it.Type !== 'Episode';

/* The history row's 16:9 thumb: no logo or title on the art, the title sits right beside it. */
function thumbArt(it: BaseItem | undefined) {
  if (!it) return { titleOnArt: false };
  const a = cardArt(it, 360);
  return a.src ? { ...a, logo: undefined, titleOnArt: false } : a;
}

/* ── Screen ── */

export function History() {
  useTitle(t('Viewing history'));
  const me = useStore(meStore);
  const loc = useLocation();
  const kindParam = loc.query.get('type');
  const filter: Kind = kindParam === 'movies' || kindParam === 'shows' ? kindParam : 'all';
  const sort: HistorySort = loc.query.get('sort') === 'title' ? 'title' : 'date';
  const orderParam = loc.query.get('order');
  const order: SortOrder = orderParam === 'asc' || orderParam === 'desc' ? orderParam : SORTS.find((s) => s.id === sort)!.order;
  const filters = readFilters(loc.query);
  const filterKey = JSON.stringify(filters);
  const showHidden = filters.hidden;
  const [sheet, setSheet] = useState(false);
  const [text, setText] = useState('');
  const [search, setSearch] = useState('');
  const [items, setItems] = useState<HistoryEntry[]>([]);
  const [total, setTotal] = useState(0);
  const [status, setStatus] = useState<'loading' | 'ready' | 'error' | 'more'>('loading');
  const [tick, setTick] = useState(0);
  const req = useRef(0);

  /* Debounced server-side search. */
  useEffect(() => {
    const t = window.setTimeout(() => setSearch(text.trim()), 300);
    return () => window.clearTimeout(t);
  }, [text]);

  const base: HistoryQuery = { type: filter, search: search || undefined, sort, order };
  const query = withFilters(base, filters);

  useEffect(() => {
    if (!me.historyEnabled) return;
    const id = ++req.current;
    setStatus('loading');
    getHistory({ ...query, startIndex: 0, limit: PAGE }).then(
      (r) => {
        if (id !== req.current) return;
        setItems(r.items);
        setTotal(r.total);
        setStatus('ready');
      },
      () => { if (id === req.current) setStatus('error'); }
    );
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filter, search, sort, order, filterKey, tick, me.historyEnabled]);

  const loadMore = () => {
    const id = ++req.current;
    setStatus('more');
    getHistory({ ...query, startIndex: items.length, limit: PAGE }).then(
      (r) => {
        if (id !== req.current) return;
        const seen = new Set(items.map((i) => i.itemId));
        setItems((prev) => [...prev, ...r.items.filter((i) => !seen.has(i.itemId))]);
        setTotal(r.total);
        setStatus('ready');
      },
      () => {
        if (id !== req.current) return;
        setStatus('ready');
        toast(t("Couldn't load earlier plays"), 'error');
      }
    );
  };

  /* By day when sorted by date; one list, each row with its date, when sorted by title. */
  const groups = useMemo(() => {
    const out: { key: string; label: string; recent: boolean; items: HistoryEntry[] }[] = [];
    if (sort === 'title') return items.length ? [{ key: 'all', label: '', recent: false, items }] : out;
    for (const e of items) {
      const g = historyGroup(new Date(e.startedAt));
      let last = out[out.length - 1];
      if (!last || last.key !== g.key) {
        last = { key: g.key, label: g.label, recent: g.key === 'today' || g.key === 'yesterday', items: [] };
        out.push(last);
      }
      last.items.push(e);
    }
    return out;
  }, [items, sort]);

  const setKind = (k: Kind) => setQuery({ type: k === 'all' ? undefined : k });
  const setFilters = (f: Filters) => setQuery(filterParams(f));
  const chips: FilterChip[] = [
    ...(filters.state ? [{ key: 's', label: stateLabel(filters.state), remove: () => setFilters({ ...filters, state: undefined }) }] : []),
    ...(filters.hidden ? [{ key: 'h', label: t('Hidden titles'), remove: () => setFilters({ ...filters, hidden: false }) }] : []),
    ...(filters.when ? [{ key: 'w', label: periodLabel(filters.when), remove: () => setFilters({ ...filters, when: undefined }) }] : []),
    ...filters.devices.map((d) => ({ key: 'd' + d, label: d, remove: () => setFilters({ ...filters, devices: filters.devices.filter((x) => x !== d) }) }))
  ];

  const toggleHidden = async (e: HistoryEntry) => {
    const next = !e.hidden;
    const name = titleOf(e);
    try {
      await hideHistoryTitle(e.titleId, next);
      if (next && !showHidden) {
        const gone = items.filter((i) => i.titleId === e.titleId).length;
        setItems((list) => list.filter((i) => i.titleId !== e.titleId));
        setTotal((t) => Math.max(0, t - gone));
      } else {
        setItems((list) => list.map((i) => (i.titleId === e.titleId ? { ...i, hidden: next } : i)));
      }
      toast(next ? t('{name} hidden. It no longer shapes your recommendations.', { name }) : t('{name} is back in your history', { name }));
    } catch {
      toast(next ? t("Couldn't hide {name}", { name }) : t("Couldn't unhide {name}", { name }), 'error');
    }
  };

  const remove = async (e: HistoryEntry) => {
    try {
      await removeHistoryEntry(e.id);
      setItems((list) => list.filter((i) => i.id !== e.id));
      setTotal((t) => Math.max(0, t - 1));
      toast(t('Removed from history'));
    } catch {
      toast(t("Couldn't remove that play"), 'error');
    }
  };

  const clearAll = async () => {
    const ok = await confirm({
      title: t('Clear viewing history?'),
      text: t('Every play on this profile is removed from Seedy. Recommendations and the Top 10 start fresh. Watched marks and resume points in Jellyfin stay as they are.'),
      action: t('Clear history'),
      danger: true
    });
    if (!ok) return;
    try {
      await clearHistory();
      setItems([]);
      setTotal(0);
      toast(t('History cleared'));
    } catch {
      toast(t("Couldn't clear history"), 'error');
    }
  };

  if (!me.historyEnabled) {
    return (
      <div class="page">
        <Header active="history" />
        <main class="history-main">
          <TitleBlock />
          <div class="empty">
            <span class="empty-icon" aria-hidden="true"><Icon name="history" size={26} /></span>
            <div class="empty-body">
              <h2 class="empty-title">{t('Viewing history is off')}</h2>
              <p class="empty-text">{t('The server admin has turned off the play log for this profile, so Seedy isn\'t keeping a history. Jellyfin still remembers what you\'ve watched and where you stopped.')}</p>
            </div>
          </div>
        </main>
      </div>
    );
  }

  const filtered = !!search || filter !== 'all' || narrows(filters);
  const busy = status === 'loading';

  return (
    <div class="page">
      <Header active="history" />
      <main class="history-main">
        <div class="history-top">
          <TitleBlock />
          <div class="history-tools">
            <label class="search-field history-search">
              <Icon name="search" size={18} />
              <input
                type="search"
                aria-label={t('Search history')}
                placeholder={t('Search history')}
                value={text}
                onInput={(e) => setText((e.currentTarget as HTMLInputElement).value)}
                onKeyDown={(e) => { if (e.key === 'Escape' && text) { e.preventDefault(); setText(''); } }}
              />
            </label>
            <button type="button" class="btn btn-secondary" onClick={clearAll} disabled={!items.length && !busy}>{t('Clear history')}</button>
          </div>
        </div>

        <div class="history-toolbar">
          <div class="history-filters">
            <div
              role="tablist"
              aria-label={t('Filter history')}
              class="history-tabs"
              onKeyDown={(e) => {
                if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
                e.preventDefault();
                const i = KINDS.findIndex((f) => f.value === filter);
                const n = (i + (e.key === 'ArrowRight' ? 1 : KINDS.length - 1)) % KINDS.length;
                setKind(KINDS[n].value);
                ((e.currentTarget as HTMLElement).children[n] as HTMLElement | undefined)?.focus();
              }}
            >
              {KINDS.map((f) => (
                <button key={f.value} type="button" role="tab" class="tab" tabIndex={filter === f.value ? 0 : -1} aria-selected={filter === f.value ? 'true' : 'false'} onClick={() => setKind(f.value)}>
                  {t(f.label)}
                </button>
              ))}
            </div>
            <div class="lib-tools">
              <SortButton sorts={SORTS} sort={sort} order={order} onChange={(s, o) => setQuery({ sort: s === 'date' ? undefined : s, order: o === SORTS.find((x) => x.id === s)!.order ? undefined : o })} />
              <FiltersButton count={filterCount(filters)} onClick={() => setSheet(true)} />
            </div>
          </div>
          <FilterChips chips={chips} onClear={() => setFilters(NO_FILTERS)} />
        </div>

        {busy && <HistorySkeleton />}

        {status === 'error' && (
          <div class="error-box" role="alert">
            <h2>{t('Couldn\'t load your history')}</h2>
            <p>{t('The server didn\'t answer. Check that the Seedy plugin is running, then try again.')}</p>
            <button type="button" class="btn btn-secondary" onClick={() => setTick((n) => n + 1)}>{t('Try again')}</button>
          </div>
        )}

        {!busy && status !== 'error' && items.length === 0 && !search && !filtered && (
          <MeduState
            title={t('No history yet')}
            text={t('What you watch, listen to and read shows up here, newest first.')}
            actions={<Link to="/" class="btn btn-primary medu-actions-btn"><Icon name="home" size={18} />{t('Go to Home')}</Link>}
          />
        )}

        {!busy && status !== 'error' && items.length === 0 && (search || filtered) && (
          <div class="empty">
            <span class="empty-icon" aria-hidden="true"><Icon name={narrows(filters) && !search ? 'filters' : 'searchX'} size={26} /></span>
            <div class="empty-body">
              {search ? (
                <>
                  <h2 class="empty-title">{t('Nothing in your history for “{search}”', { search })}</h2>
                  <p class="empty-text">
                    {filter !== 'all' || narrows(filters) ? t('Try another title, or remove a filter.')
                      : showHidden ? t('Try another title, or check the spelling.')
                      : t('Try another title, or turn on Show hidden in Filters.')}
                  </p>
                </>
              ) : narrows(filters) ? (
                <>
                  <h2 class="empty-title">{t('Nothing matches these filters')}</h2>
                  <p class="empty-text">{t('Try removing a filter or two.')}</p>
                </>
              ) : (
                <>
                  <h2 class="empty-title">{filter === 'movies' ? t('No movies played yet') : t('No shows played yet')}</h2>
                  <p class="empty-text">{filter === 'movies' ? t('Movies you play on this profile show up here, newest first.') : t('Episodes you play on this profile show up here, newest first.')}</p>
                </>
              )}
            </div>
            {!search && narrows(filters) && <button type="button" class="btn btn-primary is-sm" onClick={() => setFilters(NO_FILTERS)}>{t('Clear all')}</button>}
          </div>
        )}

        {!busy && status !== 'error' && groups.map((g) => (
          <section key={g.key} class="history-group" aria-labelledby={g.label ? 'hg-' + g.key : undefined} aria-label={g.label ? undefined : t('Viewing history')}>
            {g.label && <h2 id={'hg-' + g.key} class="history-group-label">{g.label}</h2>}
            <div class="history-list">
              {g.items.map((e) => (
                <HistoryRow key={e.id} entry={e} recent={g.recent} onToggleHidden={() => toggleHidden(e)} onRemove={() => remove(e)} />
              ))}
            </div>
          </section>
        ))}

        {!busy && status !== 'error' && items.length > 0 && items.length < total && (
          <div class="history-more">
            <button type="button" class="btn btn-secondary history-more-btn" onClick={loadMore} disabled={status === 'more'}>
              {status === 'more' ? t('Loading…') : sort === 'date' && order === 'desc' ? t('Show earlier') : t('Show more')}
            </button>
          </div>
        )}
      </main>

      {sheet && (
        <FilterSheet
          kind={filter}
          base={base}
          initial={filters}
          onClose={() => setSheet(false)}
          onApply={(f) => { setSheet(false); setFilters(f); }}
        />
      )}
    </div>
  );
}

/* What to show (finished or not, hidden titles), when it was watched and on which device, with the count. */
function FilterSheet({ kind, base, initial, onClose, onApply }: { kind: Kind; base: HistoryQuery; initial: Filters; onClose: () => void; onApply: (f: Filters) => void }) {
  const [draft, setDraft] = useState<Filters>(initial);
  const opts = useAsync(() => getHistoryOptions(kind), [kind]);
  const [count, setCount] = useState<number | null>(null);
  useEffect(() => {
    setCount(null);
    let alive = true;
    const timer = window.setTimeout(() => {
      getHistory({ ...withFilters(base, draft), startIndex: 0, limit: 0 })
        .then((r) => alive && setCount(r.total), () => alive && setCount(null));
    }, 220);
    // An older count must not land after a newer one.
    return () => { alive = false; window.clearTimeout(timer); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [JSON.stringify(draft)]);

  // A year or device picked earlier stays offered, so it can be turned off.
  const years = Array.from(new Set([...(opts.data?.years ?? []).map(String), ...(draft.when && /^\d{4}$/.test(draft.when) ? [draft.when] : [])])).sort().reverse();
  const devices = [...(opts.data?.devices ?? []), ...draft.devices.filter((d) => !opts.data?.devices.includes(d))];
  const pickState = (s: PlayState) => setDraft((d) => ({ ...d, state: d.state === s ? undefined : s }));
  const pickWhen = (w: string) => setDraft((d) => ({ ...d, when: d.when === w ? undefined : w }));
  const pickDevice = (v: string) => setDraft((d) => ({ ...d, devices: d.devices.includes(v) ? d.devices.filter((x) => x !== v) : [...d.devices, v] }));

  return (
    <Sheet open onClose={onClose} label={t('Filters')} wide>
      <div class="sheet-head">
        <h2 class="sheet-title">{t('Filters')}</h2>
        <button type="button" class="btn icon-btn is-bare is-40" aria-label={t('Close filters')} onClick={onClose}><Icon name="close" size={18} /></button>
      </div>
      <FilterGroup label={tc('Show', 'verb')}>
        {(['finished', 'inprogress'] as const).map((s) => <FilterOption key={s} on={draft.state === s} label={stateLabel(s)} onClick={() => pickState(s)} />)}
      </FilterGroup>
      <FilterGroup label={t('Hidden titles')}>
        <FilterOption on={draft.hidden} label={t('Show hidden')} onClick={() => setDraft((d) => ({ ...d, hidden: !d.hidden }))} />
      </FilterGroup>
      <FilterGroup label={t('When')}>
        {['7d', '30d', ...years].map((w) => <FilterOption key={w} on={draft.when === w} label={periodLabel(w)} onClick={() => pickWhen(w)} />)}
        {opts.loading && !opts.data && <span class="skel" style={{ width: '70px', height: '36px', borderRadius: '999px' }} />}
      </FilterGroup>
      {opts.loading && !opts.data ? (
        <FilterGroup label={t('Devices')}>
          {[0, 1, 2].map((i) => <span key={i} class="skel" style={{ width: '110px', height: '36px', borderRadius: '999px' }} />)}
        </FilterGroup>
      ) : devices.length > 0 && (
        <FilterGroup label={t('Devices')}>
          {devices.map((d) => <FilterOption key={d} on={draft.devices.includes(d)} label={d} onClick={() => pickDevice(d)} />)}
        </FilterGroup>
      )}
      <div class="sheet-actions fsheet-actions">
        <button type="button" class="btn btn-ghost is-light" disabled={!filterCount(draft)} onClick={() => setDraft(NO_FILTERS)}>{t('Clear all')}</button>
        <button type="button" class="btn btn-primary is-sm" onClick={() => onApply(draft)}>
          {count === null ? t('Show titles') : count === 0 ? t('No titles match') : t('Show {count}', { count: plural(count, 'title') })}
        </button>
      </div>
    </Sheet>
  );
}

function TitleBlock() {
  return (
    <div class="history-title">
      <h1>{t('Viewing history')}</h1>
      <p>{t("What you've watched on this profile and where you left off, newest first. Hidden titles stop shaping your recommendations and the Top 10.")}</p>
    </div>
  );
}

function HistoryRow({ entry: e, recent, onToggleHidden, onRemove }: { entry: HistoryEntry; recent: boolean; onToggleHidden: () => void; onRemove: () => void }) {
  const it = e.item;
  const title = titleOf(e);
  const art = thumbArt(it);
  const run = e.runtimeTicks || it?.RunTimeTicks || 0;
  const frac = run > 0 ? Math.min(1, e.positionTicks / run) : 0;
  const detailsTo = `/details/${e.titleId}`;
  const resumable = !e.finished && e.positionTicks > 0;
  const playable = !!it;
  const movie = isMovieLike(it);

  return (
    <div class={'history-row' + (e.hidden ? ' is-hidden' : '')}>
      {playable ? (
        <Link to={playPath({ Id: e.itemId }, { from: 'history', startTicks: e.finished ? undefined : e.positionTicks })} class="history-thumb" aria-label={t('Play {name}', { name: title + (it?.Type === 'Episode' ? ', ' + it.Name : '') })}>
          <Art art={art} title={title} titleClass="is-small" />
          {!e.finished && frac > 0 && (
            <Progress fraction={frac} size={5} class="history-bar" />
          )}
          {e.finished && <WatchedCheck size={24} />}
        </Link>
      ) : (
        <span class="history-thumb" aria-hidden="true" />
      )}
      <div class="history-text">
        <span class="history-name">
          {playable ? <Link to={detailsTo} class="history-title-link">{title}</Link> : <span class="history-title-link">{title}</span>}
          {e.hidden && <span class="pill history-hidden-pill">{t('Hidden')}</span>}
        </span>
        <span class="history-sub">{subOf(e)}</span>
      </div>
      <div class="history-state">
        <span class="history-state-main">{stateOf(e)}</span>
        <span class="history-when">{whenOf(e, recent)}</span>
      </div>
      <div class="history-actions">
        <button
          type="button"
          class="btn icon-btn is-bare history-act"
          aria-label={e.hidden ? t('Unhide {name}', { name: title }) : t('Hide {name} from history', { name: title })}
          title={e.hidden ? t('Unhide') : t('Hide from history')}
          onClick={onToggleHidden}
        >
          <Icon name={e.hidden ? 'eye' : 'eyeOff'} size={20} />
        </button>
        <MenuButton
          width={270}
          button={({ ref, open, toggle }) => (
            <button
              ref={ref}
              type="button"
              class="btn icon-btn is-bare history-act"
              aria-label={t('More actions for {name}', { name: title })}
              aria-haspopup="menu"
              aria-expanded={open ? 'true' : 'false'}
              onClick={toggle}
            >
              <Icon name="more" size={20} />
            </button>
          )}
        >
          {(close) => (
            <>
              {playable && (
                <MenuItem icon={movie ? 'film' : 'tv'} label={movie ? t('Go to movie') : t('Go to series')} onSelect={() => { close(); navigate(detailsTo); }} />
              )}
              {playable && resumable && (
                <MenuItem icon="play" label={t('Play from where you stopped')} sub={timecode(ticksToSeconds(e.positionTicks))} onSelect={() => { close(); navigate(playPath({ Id: e.itemId }, { from: 'history', startTicks: e.positionTicks })); }} />
              )}
              {playable && (
                <MenuItem icon="refresh" label={t('Play from the beginning')} onSelect={() => { close(); navigate(playPath({ Id: e.itemId }, { from: 'history', restart: true })); }} />
              )}
              {playable && <div class="menu-sep" />}
              <MenuItem icon="trash" label={t('Remove from history')} danger onSelect={() => { close(); onRemove(); }} />
            </>
          )}
        </MenuButton>
      </div>
    </div>
  );
}

function HistorySkeleton() {
  return (
    <section class="history-group" aria-busy="true" aria-label={t('Loading history')}>
      <span class="skel skel-line history-skel-label" />
      <div class="history-list">
        {[0, 1, 2, 3].map((i) => (
          <div key={i} class="history-row" aria-hidden="true">
            <span class="history-thumb skel" />
            <div class="history-text">
              <span class="skel skel-line" style={{ width: '42%' }} />
              <span class="skel skel-line" style={{ width: '28%' }} />
            </div>
            <div class="history-state">
              <span class="skel skel-line" style={{ width: '80%' }} />
              <span class="skel skel-line" style={{ width: '55%' }} />
            </div>
            <div class="history-actions" style={{ width: '92px' }} />
          </div>
        ))}
      </div>
    </section>
  );
}
