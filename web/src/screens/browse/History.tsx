import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { clearHistory, getHistory, hideHistoryTitle, removeHistoryEntry, type HistoryEntry } from '../../api/seedy';
import { cardArt } from '../../api/images';
import type { BaseItem } from '../../api/types';
import { meStore } from '../../lib/app-state';
import { clock, episodeTag, timeLeft, fmtWeekdayDate, historyGroup, ticksToSeconds, timecode } from '../../lib/format';
import { Link, navigate } from '../../lib/router';
import { useStore } from '../../lib/store';
import { Art, Progress, WatchedCheck, playPath } from '../../ui/cards';
import { Switch } from '../../ui/controls';
import { Header } from '../../ui/navbar/Header';
import { Icon } from '../../ui/icons';
import { MenuButton, MenuItem, confirm, toast } from '../../ui/overlay';
import './history.css';
import { t } from '../../lib/i18n';

type Filter = 'all' | 'movies' | 'shows';
const PAGE = 30;

const FILTERS: { value: Filter; label: string }[] = [
  { value: 'all', label: 'All' },
  { value: 'movies', label: 'Movies' },
  { value: 'shows', label: 'Shows' }
];

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
  const me = useStore(meStore);
  const [filter, setFilter] = useState<Filter>('all');
  const [showHidden, setShowHidden] = useState(false);
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

  useEffect(() => {
    if (!me.historyEnabled) return;
    const id = ++req.current;
    setStatus('loading');
    getHistory({ startIndex: 0, limit: PAGE, type: filter, search: search || undefined, includeHidden: showHidden || undefined }).then(
      (r) => {
        if (id !== req.current) return;
        setItems(r.items);
        setTotal(r.total);
        setStatus('ready');
      },
      () => { if (id === req.current) setStatus('error'); }
    );
  }, [filter, search, showHidden, tick, me.historyEnabled]);

  const loadMore = () => {
    const id = ++req.current;
    setStatus('more');
    getHistory({ startIndex: items.length, limit: PAGE, type: filter, search: search || undefined, includeHidden: showHidden || undefined }).then(
      (r) => {
        if (id !== req.current) return;
        const seen = new Set(items.map((i) => i.id));
        setItems((prev) => [...prev, ...r.items.filter((i) => !seen.has(i.id))]);
        setTotal(r.total);
        setStatus('ready');
      },
      () => {
        if (id !== req.current) return;
        setStatus('ready');
        toast("Couldn't load earlier plays", 'error');
      }
    );
  };

  const groups = useMemo(() => {
    const out: { key: string; label: string; recent: boolean; items: HistoryEntry[] }[] = [];
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
  }, [items]);

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
      toast(next ? `${name} hidden. It no longer shapes your recommendations.` : `${name} is back in your history`);
    } catch {
      toast(`Couldn't ${next ? 'hide' : 'unhide'} ${name}`, 'error');
    }
  };

  const remove = async (e: HistoryEntry) => {
    try {
      await removeHistoryEntry(e.id);
      setItems((list) => list.filter((i) => i.id !== e.id));
      setTotal((t) => Math.max(0, t - 1));
      toast(t('Removed from history'));
    } catch {
      toast("Couldn't remove that play", 'error');
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
      toast("Couldn't clear history", 'error');
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

  const filtered = !!search || filter !== 'all';
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

        <div class="history-filters">
          <div
            role="tablist"
            aria-label={t('Filter history')}
            class="history-tabs"
            onKeyDown={(e) => {
              if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
              e.preventDefault();
              const i = FILTERS.findIndex((f) => f.value === filter);
              const n = (i + (e.key === 'ArrowRight' ? 1 : FILTERS.length - 1)) % FILTERS.length;
              setFilter(FILTERS[n].value);
              ((e.currentTarget as HTMLElement).children[n] as HTMLElement | undefined)?.focus();
            }}
          >
            {FILTERS.map((f) => (
              <button key={f.value} type="button" role="tab" class="tab" tabIndex={filter === f.value ? 0 : -1} aria-selected={filter === f.value ? 'true' : 'false'} onClick={() => setFilter(f.value)}>
                {f.label}
              </button>
            ))}
          </div>
          <div class="history-hidden-toggle">
            <span aria-hidden="true">{t('Show hidden')}</span>
            <Switch checked={showHidden} onChange={setShowHidden} label={t('Show hidden titles')} />
          </div>
        </div>

        {busy && <HistorySkeleton />}

        {status === 'error' && (
          <div class="error-box" role="alert">
            <h2>{t('Couldn\'t load your history')}</h2>
            <p>{t('The server didn\'t answer. Check that the Seedy plugin is running, then try again.')}</p>
            <button type="button" class="btn btn-secondary" onClick={() => setTick((n) => n + 1)}>{t('Try again')}</button>
          </div>
        )}

        {!busy && status !== 'error' && items.length === 0 && (
          <div class="empty">
            <span class="empty-icon" aria-hidden="true"><Icon name={filtered ? 'searchX' : 'history'} size={26} /></span>
            <div class="empty-body">
              {search ? (
                <>
                  <h2 class="empty-title">Nothing in your history for “{search}”</h2>
                  <p class="empty-text">Try another title, or switch to All{showHidden ? '' : ' and turn on Show hidden'}.</p>
                </>
              ) : filtered ? (
                <>
                  <h2 class="empty-title">No {filter === 'movies' ? 'movies' : 'shows'} played yet</h2>
                  <p class="empty-text">{filter === 'movies' ? t('Movies') : t('Episodes')} you play on this profile show up here, newest first.</p>
                </>
              ) : (
                <>
                  <h2 class="empty-title">{t('Nothing played yet')}</h2>
                  <p class="empty-text">{t('Every movie and episode you play on this profile shows up here, newest first.')}</p>
                </>
              )}
            </div>
            {!search && !filtered && <Link to="/" class="btn btn-primary is-sm">{t('Find something to watch')}</Link>}
          </div>
        )}

        {!busy && status !== 'error' && groups.map((g) => (
          <section key={g.key} class="history-group" aria-labelledby={'hg-' + g.key}>
            <h2 id={'hg-' + g.key} class="history-group-label">{g.label}</h2>
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
              {status === 'more' ? t('Loading…') : t('Show earlier')}
            </button>
          </div>
        )}
      </main>
    </div>
  );
}

function TitleBlock() {
  return (
    <div class="history-title">
      <h1>{t('Viewing history')}</h1>
      <p>{t('Every play on this profile, newest first. Hidden titles stop shaping your recommendations and the Top 10.')}</p>
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
