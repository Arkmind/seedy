import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { getGuideInfo, getPrograms, type Program } from '../../api/livetv';
import { meStore } from '../../lib/app-state';
import { clock, duration, episodeTag, fmtDayMonth, startOfDay } from '../../lib/format';
import { locale, t } from '../../lib/i18n';
import { useLocation } from '../../lib/router';
import { safeLocal, saveLocal, useAsync, useStore } from '../../lib/store';
import { Switch } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { MenuButton, MenuItem, Popover, Sheet } from '../../ui/overlay';
import {
  CATEGORIES, categoryOf, previewAllowed, endMs, fraction, isAiring, isFavorite, leftLabel, newTag, programsChanged, recState, startMs, timeRange,
  toggleRecord, toggleSeries, useChannels, useNow, useTimers, weekdayShort, type Category, type LiveChannel
} from './data';
import { LiveLayout, NoChannels } from './LiveLayout';
import { ChannelLogo, Chip, HeartIcon, LivePill, LtIcon, ProgramArt, RecDot, SeriesDot, SoundButton, watch } from './parts';
import { useLivePreview } from './preview';

/* Live TV · Guide (board "Live TV · Guide"): the selected programme on top with a muted preview, filter chips, the
   day picker and Now, then the timeline — channels down the side, three hours across, a "now" line, LIVE / NEW /
   PREMIERE tags, and "No guide information" rows. Arrow keys move, Enter opens, R records, Page Up / Page Down
   jump three hours. */

const HALF = 30 * 60_000;
const SPAN = 3 * 3_600_000;
const BLOCK = 6 * 3_600_000;
const floorHalf = (ms: number) => Math.floor(ms / HALF) * HALF;
/* The window opens on the half hour 20 minutes or more before now, so the now line sits clear of the first tick. */
const nowStart = (ms: number) => floorHalf(ms - 20 * 60_000);
const PAGE = 60;

type Filter = 'all' | 'fav' | Category;
type Tile =
  | { kind: 'prog'; p: Program; s: number; e: number }
  | { kind: 'gap'; s: number; e: number; until?: number }
  | { kind: 'none'; s: number; e: number };

const FAV_FIRST = 'seedy.device.guideFavoritesFirst';
const HIDE_EMPTY = 'seedy.device.guideHideEmpty';

export function LiveGuide() {
  const loc = useLocation();
  const me = useStore(meStore);
  const { list } = useChannels();
  const { timers } = useTimers();
  const now = useNow(30_000);
  const bump = useStore(programsChanged);
  const info = useAsync(() => getGuideInfo(), []);

  const qCat = loc.query.get('cat');
  const [filter, setFilter] = useState<Filter>(CATEGORIES.some((c) => c.id === qCat) ? (qCat as Category) : 'all');
  const qAt = Number(loc.query.get('at'));
  const [ws, setWs] = useState(() => (qAt > 0 ? floorHalf(qAt) : nowStart(Date.now())));
  const [limit, setLimit] = useState(PAGE);
  const [selId, setSelId] = useState<string | null>(loc.query.get('program'));
  const [favFirst, setFavFirst] = useState(() => safeLocal(FAV_FIRST, true));
  const [hideEmpty, setHideEmpty] = useState(() => safeLocal(HIDE_EMPTY, false));
  const [aboutOpen, setAboutOpen] = useState(false);

  /* Programmes, fetched in six-hour blocks around the window and kept while the page is open. */
  const cache = useRef(new Map<string, Program>());
  const blocks = useRef(new Map<number, 'loading' | 'done'>());
  const [ver, setVer] = useState(0);
  const [loadError, setLoadError] = useState(false);
  useEffect(() => {
    cache.current.clear();
    blocks.current.clear();
    setVer((v) => v + 1);
  }, [bump]);
  useEffect(() => {
    const need = [Math.floor((ws - HALF) / BLOCK), Math.floor((ws + SPAN) / BLOCK), Math.floor((ws + SPAN + BLOCK / 2) / BLOCK)];
    for (const b of new Set(need)) {
      if (blocks.current.has(b)) continue;
      blocks.current.set(b, 'loading');
      getPrograms({ MinEndDate: new Date(b * BLOCK).toISOString(), MaxStartDate: new Date((b + 1) * BLOCK).toISOString() }).then(
        (r) => {
          for (const p of r.Items ?? []) cache.current.set(p.Id, p);
          blocks.current.set(b, 'done');
          setLoadError(false);
          setVer((v) => v + 1);
        },
        () => { blocks.current.delete(b); setLoadError(true); setVer((v) => v + 1); }
      );
    }
  }, [ws, bump, ver === 0]);

  const we = ws + SPAN;
  const byChannel = useMemo(() => {
    const m = new Map<string, Program[]>();
    for (const p of cache.current.values()) {
      if (!p.ChannelId) continue;
      const arr = m.get(p.ChannelId) ?? [];
      arr.push(p);
      m.set(p.ChannelId, arr);
    }
    for (const arr of m.values()) arr.sort((a, b) => startMs(a) - startMs(b));
    return m;
  }, [ver]);
  const loadingWindow = [Math.floor(ws / BLOCK), Math.floor((we - 1) / BLOCK)].some((b) => blocks.current.get(b) !== 'done');

  const hasData = (c: LiveChannel) => byChannel.has(c.Id) || !!c.CurrentProgram;
  const inWindow = (p: Program) => endMs(p) > ws && startMs(p) < we;
  const all = list ?? [];
  const favCount = all.filter(isFavorite).length;
  const matches = (c: LiveChannel): boolean => {
    if (filter === 'all') return true;
    if (filter === 'fav') return isFavorite(c);
    const test = CATEGORIES.find((x) => x.id === filter)!.test;
    return (byChannel.get(c.Id) ?? []).some((p) => inWindow(p) && test(p));
  };
  /* Favourites first (when set), then channels with a guide, each in number order. */
  const matching = all
    .filter((c) => matches(c) && (!hideEmpty || hasData(c)))
    .sort((a, b) => (favFirst ? Number(isFavorite(b)) - Number(isFavorite(a)) : 0) || Number(hasData(b)) - Number(hasData(a)));
  const rows = matching.slice(0, limit);
  const more = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = more.current;
    if (!el || typeof IntersectionObserver === 'undefined') return;
    const io = new IntersectionObserver((es) => { if (es.some((x) => x.isIntersecting)) setLimit((l) => l + PAGE); }, { rootMargin: '600px 0px' });
    io.observe(el);
    return () => io.disconnect();
  }, [matching.length > limit, limit]);

  /* The tiles of one row inside the window: programmes, gaps ("Off air until 06:00"), or no guide at all. */
  const tilesFor = (c: LiveChannel): Tile[] => {
    const progs = byChannel.get(c.Id);
    if (!progs?.length) return loadingWindow ? [] : [{ kind: 'none', s: ws, e: we }];
    const out: Tile[] = [];
    let at = ws;
    for (const p of progs) {
      const s = startMs(p);
      const e = endMs(p);
      if (e <= ws || s >= we) continue;
      if (s - at >= 5 * 60_000) out.push({ kind: 'gap', s: at, e: s, until: s });
      out.push({ kind: 'prog', p, s, e });
      at = Math.max(at, e);
    }
    if (we - at >= 5 * 60_000) {
      const next = progs.find((p) => startMs(p) >= at);
      out.push({ kind: 'gap', s: at, e: we, until: next ? startMs(next) : undefined });
    }
    return out;
  };

  /* The selected programme: from the address, else what's on in the first row that has a guide. */
  const selected = useMemo(() => {
    if (selId) {
      const p = cache.current.get(selId);
      if (p) return p;
    }
    for (const c of rows) {
      const p = (byChannel.get(c.Id) ?? []).find((x) => isAiring(x, now));
      if (p) return p;
    }
    return undefined;
  }, [selId, ver, rows.length, filter]);
  const selChannel = selected?.ChannelId ? all.find((c) => c.Id === selected.ChannelId) : undefined;

  /* ── Keyboard: a roving focus over the tiles ── */
  const gridRef = useRef<HTMLDivElement>(null);
  const [focus, setFocus] = useState<{ row: number; key: string } | null>(null);
  const anchor = useRef<number>(Date.now());
  const wantFocus = useRef(false);
  const keyOf = (c: LiveChannel, tl: Tile) => (tl.kind === 'prog' ? tl.p.Id : `${c.Id}:${tl.kind}:${tl.s}`);
  useEffect(() => {
    if (!wantFocus.current || !focus) return;
    wantFocus.current = false;
    gridRef.current?.querySelector<HTMLElement>(`[data-key="${CSS.escape(focus.key)}"]`)?.focus({ preventScroll: false });
  });

  const tileAt = (row: number, time: number): { key: string; tile: Tile } | null => {
    const c = rows[row];
    if (!c) return null;
    const tiles = tilesFor(c);
    const tl = tiles.find((x) => x.s <= time && time < x.e) ?? tiles.find((x) => x.s >= time) ?? tiles[tiles.length - 1];
    return tl ? { key: keyOf(c, tl), tile: tl } : null;
  };
  const keyMove = useRef(false);
  const moveTo = (row: number, key: string) => {
    wantFocus.current = true;
    keyMove.current = true;
    setFocus({ row, key });
  };
  const onGridKey = (e: KeyboardEvent) => {
    if (!focus || e.altKey || e.ctrlKey || e.metaKey) return;
    const c = rows[focus.row];
    if (!c) return;
    const progs = byChannel.get(c.Id) ?? [];
    const cur = cache.current.get(focus.key);
    const curIndex = cur ? progs.findIndex((p) => p.Id === cur.Id) : -1;
    const go = (fn: () => void) => { e.preventDefault(); fn(); };
    switch (e.key) {
      case 'ArrowRight': return go(() => {
        const next = curIndex >= 0 ? progs[curIndex + 1] : progs.find((p) => startMs(p) >= we - HALF);
        if (!next) return;
        const s = startMs(next);
        if (s >= we) setWs(floorHalf(s));
        anchor.current = Math.max(s, ws);
        moveTo(focus.row, next.Id);
      });
      case 'ArrowLeft': return go(() => {
        const prev = curIndex > 0 ? progs[curIndex - 1] : undefined;
        if (!prev) {
          if (ws > Date.now() - 2 * 3_600_000) setWs(ws - HALF);
          return;
        }
        if (endMs(prev) <= ws) setWs(floorHalf(Math.max(startMs(prev), endMs(prev) - SPAN / 2)));
        anchor.current = Math.max(startMs(prev), ws);
        moveTo(focus.row, prev.Id);
      });
      case 'ArrowDown':
      case 'ArrowUp': return go(() => {
        const row = focus.row + (e.key === 'ArrowDown' ? 1 : -1);
        if (row < 0 || row >= rows.length) return;
        const hit = tileAt(row, Math.max(anchor.current, ws));
        if (hit) moveTo(row, hit.key);
      });
      case 'PageDown':
      case 'PageUp': return go(() => {
        const nws = ws + (e.key === 'PageDown' ? SPAN : -SPAN);
        setWs(nws);
        anchor.current = nws;
        setFocus(null);
        window.setTimeout(() => {
          const hit = tileAtRef.current(focus.row, nws);
          if (hit) moveTo(focus.row, hit.key);
        }, 0);
      });
      case 'Enter': return go(() => {
        if (!cur) { watch(c.Id); return; }
        if (selected?.Id === cur.Id && isAiring(cur, Date.now())) watch(c.Id);
        else setSelId(cur.Id);
      });
      case 'r':
      case 'R': return go(() => { if (cur && endMs(cur) > Date.now()) void toggleRecord(cur); });
    }
  };
  const tileAtRef = useRef(tileAt);
  tileAtRef.current = tileAt;

  if (list && !list.length) return <LiveLayout tab="guide"><NoChannels admin={me.isAdmin} /></LiveLayout>;

  const days = guideDays(info.data?.EndDate, now);
  const dayOfWs = startOfDay(new Date(ws)).getTime();
  const nowIn = now >= ws && now < we;
  const frac = (now - ws) / SPAN;
  const pctOf = (ms: number) => (((ms - ws) / SPAN) * 100).toFixed(3) + '%';
  const filterCounts: Record<string, number | undefined> = { all: all.length, fav: favCount };

  return (
    <LiveLayout tab="guide">
      {selected ? (
        <GuideHero program={selected} channel={selChannel} now={now} timers={timers} onAbout={() => setAboutOpen(true)} programs={byChannel.get(selected.ChannelId ?? '') ?? []} />
      ) : list && !loadingWindow ? null : <GuideHeroSkeleton />}

      <div class="lt-guide-bar">
        <div role="group" aria-label={t('Show channels')} class="lt-chips">
          <Chip label={t('All')} count={filterCounts.all} pressed={filter === 'all'} onClick={() => setFilter('all')} />
          <Chip label={t('Favorites')} count={favCount} pressed={filter === 'fav'} onClick={() => setFilter('fav')} />
          <span aria-hidden="true" class="lt-chip-sep" />
          {CATEGORIES.map((x) => <Chip key={x.id} label={t(x.label)} pressed={filter === x.id} onClick={() => setFilter(filter === x.id ? 'all' : x.id)} />)}
        </div>
        <div class="lt-guide-tools">
          <MenuButton
            width={240}
            button={({ ref, open, toggle }) => (
              <button ref={ref} type="button" class="lt-btn lt-day" aria-haspopup="listbox" aria-expanded={open ? 'true' : 'false'} onClick={toggle}>
                <LtIcon name="calendar" size={18} />
                {dayLabel(dayOfWs, now)}
                <Icon name="chevronDown" size={15} stroke={2.4} class="nb-chev" />
              </button>
            )}
          >
            {(close) => days.map((d) => (
              <MenuItem key={d} label={dayLabel(d, now)} pressed={d === dayOfWs} onSelect={() => {
                close();
                const today = startOfDay(new Date(now)).getTime();
                const sinceMidnight = nowStart(now) - today;
                setWs(d === today ? nowStart(now) : d + sinceMidnight);
              }} />
            ))}
          </MenuButton>
          <button type="button" class="lt-btn" onClick={() => { setWs(nowStart(Date.now())); setSelId(null); }}>
            <LtIcon name="now" size={18} />{t('Now')}
          </button>
          <GuideSettings favFirst={favFirst} hideEmpty={hideEmpty}
            onFavFirst={(v) => { setFavFirst(v); saveLocal(FAV_FIRST, v); }}
            onHideEmpty={(v) => { setHideEmpty(v); saveLocal(HIDE_EMPTY, v); }} />
        </div>
      </div>

      <div
        ref={gridRef}
        role="grid"
        aria-label={t('TV guide, {day}', { day: new Intl.DateTimeFormat(locale(), { weekday: 'long', day: 'numeric', month: 'long' }).format(new Date(ws)) })}
        aria-rowcount={rows.length + 1}
        class="lt-grid"
        onKeyDown={(e) => onGridKey(e as unknown as KeyboardEvent)}
      >
        <div role="row" class="lt-grid-head">
          <div role="columnheader" class="lt-grid-corner">
            <span>{`${weekdayShort(new Date(ws))} ${fmtDayMonth(new Date(ws))}`}</span>
            <span class="lt-grid-nav">
              <button type="button" class="lt-grid-arrow" aria-label={t('Earlier')} onClick={() => setWs(ws - HALF * 2)}><Icon name="chevronLeft" size={16} stroke={2.4} /></button>
              <button type="button" class="lt-grid-arrow" aria-label={t('Later')} onClick={() => setWs(ws + HALF * 2)}><Icon name="chevronRight" size={16} stroke={2.4} /></button>
            </span>
          </div>
          <div class="lt-grid-times">
            {[0, 1, 2, 3, 4, 5].map((i) => (
              <span key={i} role="columnheader" class={'lt-tick' + (nowIn && Math.abs(ws + i * HALF + 6 * 60_000 - now) < 12 * 60_000 ? ' is-covered' : '')}
                style={{ left: ((i * HALF) / SPAN * 100).toFixed(3) + '%' }}>{clock(new Date(ws + i * HALF))}</span>
            ))}
            {nowIn && <span class="lt-now-chip" style={{ left: (frac * 100).toFixed(3) + '%' }}>{clock(new Date(now))}</span>}
          </div>
        </div>
        <div class="lt-grid-body">
          {!list && Array.from({ length: 8 }, (_, i) => <div key={i} class="lt-grow" aria-hidden="true"><div class="lt-rowhead"><span class="skel skel-line" style={{ width: '70%' }} /></div><div class="lt-rowline"><span class="lt-tile-wrap" style={{ left: '0%', width: '100%' }}><span class="lt-tile skel" /></span></div></div>)}
          {rows.map((c, ri) => {
            const tiles = tilesFor(c);
            const fav = isFavorite(c);
            return (
              <div key={c.Id} role="row" class="lt-grow" aria-rowindex={ri + 2}>
                <a
                  href="#"
                  role="rowheader"
                  class="lt-rowhead"
                  aria-label={t('Channel {n}, {name}', { n: c.num, name: c.label })}
                  onClick={(e) => { e.preventDefault(); watch(c.Id); }}
                >
                  <span class="lt-rownum">{c.num}</span>
                  <ChannelLogo channel={c} size={36} radius={10} />
                  <span class="lt-rowname">{c.label}</span>
                  {fav && <span class="lt-rowfav" aria-label={t('Favorite channel')} role="img"><HeartIcon size={14} filled /></span>}
                </a>
                <div class="lt-rowline">
                  {!tiles.length && <span class="lt-tile-wrap" style={{ left: '0%', width: '100%' }}><span class="lt-tile skel" /></span>}
                  {tiles.map((tl) => {
                    const key = keyOf(c, tl);
                    const a = Math.max(tl.s, ws);
                    const b = Math.min(tl.e, we);
                    const clipL = tl.s < ws;
                    const clipR = tl.e > we;
                    const radius = clipL && clipR ? '0' : clipL ? '0 10px 10px 0' : clipR ? '10px 0 0 10px' : '10px';
                    const focusable = focus ? focus.key === key : ri === 0 && tl === tiles.find((x) => x.e > now) ;
                    const common = {
                      'data-key': key,
                      role: 'gridcell' as const,
                      tabIndex: focusable ? 0 : -1,
                      style: { borderRadius: radius },
                      onFocus: () => {
                        if (focus?.key !== key) setFocus({ row: ri, key });
                        // Moving up and down keeps the time the viewer started from; a click or Tab starts from this tile.
                        if (!keyMove.current) anchor.current = Math.max(a, ws);
                        keyMove.current = false;
                      }
                    };
                    if (tl.kind === 'prog') {
                      const p = tl.p;
                      const airing = isAiring(p, now);
                      const sel = selected?.Id === p.Id;
                      const rec = recState(p, timers);
                      const tag = p.IsLive ? 'live' : newTag(p);
                      const sub = [p.IsMovie ? t('Film') : p.IsSeries && p.IndexNumber != null ? episodeTag(p) : '', timeRange(p)].filter(Boolean).join(' · ');
                      return (
                        <span key={key} class="lt-tile-wrap" style={{ left: pctOf(a), width: (((b - a) / SPAN) * 100).toFixed(3) + '%' }}>
                          <button
                            type="button"
                            {...common}
                            class={'lt-tile' + (airing ? ' is-airing' : '') + (sel ? ' is-selected' : '')}
                            aria-label={[p.Name, p.IsSeries && p.IndexNumber != null ? episodeTag(p) : '', t('{start} to {end}', { start: clock(new Date(tl.s)), end: clock(new Date(tl.e)) }), c.label, rec === 'now' ? t('Recording') : rec === 'set' ? t('Recording set') : ''].filter(Boolean).join(', ')}
                            aria-selected={sel ? 'true' : 'false'}
                            onClick={() => { setSelId(p.Id); setFocus({ row: ri, key }); }}
                            onDblClick={() => { if (isAiring(p, Date.now())) watch(c.Id); }}
                          >
                            <span class="lt-tile-top">
                              {rec === 'now' && <span class="lt-tile-rec" title={t('Recording')} />}
                              {rec === 'set' && <span class="lt-tile-rec is-set" title={t('Recording set')} />}
                              <span class="lt-tile-title">{p.Name}</span>
                              {tag && <span class={'lt-gtag is-' + tag}>{tag === 'live' ? t('LIVE') : tag === 'new' ? t('New') : t('Premiere')}</span>}
                            </span>
                            <span class="lt-tile-sub">{sub}</span>
                          </button>
                        </span>
                      );
                    }
                    if (tl.kind === 'gap') {
                      return (
                        <span key={key} class="lt-tile-wrap" style={{ left: pctOf(a), width: (((b - a) / SPAN) * 100).toFixed(3) + '%' }}>
                          <span {...common} class="lt-tile is-off">
                            <span class="lt-tile-top"><span class="lt-tile-title">{tl.until ? t('Off air until {time}', { time: clock(new Date(tl.until)) }) : t('Nothing listed')}</span></span>
                          </span>
                        </span>
                      );
                    }
                    return (
                      <span key={key} class="lt-tile-wrap" style={{ left: '0%', width: '100%' }}>
                        <span {...common} class="lt-tile is-none" style={{ borderRadius: '10px 0 0 10px' }}>
                          <span class="lt-tile-top"><span class="lt-tile-title">{t('No guide information')}</span></span>
                          <span class="lt-tile-sub">{t('This playlist channel has no guide data')}</span>
                        </span>
                      </span>
                    );
                  })}
                </div>
              </div>
            );
          })}
          {nowIn && rows.length > 0 && (
            <>
              <div aria-hidden="true" class="lt-past" style={{ width: `calc((100% - var(--lt-col)) * ${frac.toFixed(4)})` }} />
              <div aria-hidden="true" class="lt-nowline" style={{ left: `calc(var(--lt-col) + (100% - var(--lt-col)) * ${frac.toFixed(4)})` }} />
            </>
          )}
          {matching.length > rows.length && (
            <div ref={more} class="lt-grid-more">
              <button type="button" class="link-btn" onClick={() => setLimit((l) => l + PAGE)}>{t('Show more channels')}</button>
            </div>
          )}
          {list && !rows.length && (
            <div class="lt-grid-empty">
              {filter === 'fav' ? t('No favorite channels yet. Use the heart on a channel to add one.') : t('No channels match this filter right now.')}
            </div>
          )}
        </div>
      </div>

      <div class="lt-guide-foot">
        <span>
          {t('Showing {shown} of {total} channels', { shown: rows.length, total: all.length })}
          {favFirst && favCount > 0 ? ' · ' + t('favorites first') : ''}
          {loadError ? ' · ' + t("Some of the guide couldn't load") : ''}
        </span>
        <span class="lt-keys">{t('Arrow keys move · Enter opens · R records · Page Up and Page Down jump 3 hours')}</span>
      </div>

      {selected && (
        <Sheet open={aboutOpen} onClose={() => setAboutOpen(false)} label={selected.Name ?? ''}>
          <AboutProgram program={selected} channel={selChannel} onClose={() => setAboutOpen(false)} />
        </Sheet>
      )}
    </LiveLayout>
  );
}

/* "Today, Mon 19", "Tomorrow, Tue 20", "Wed 21" */
function dayLabel(day: number, now: number): string {
  const d = new Date(day);
  const diff = Math.round((day - startOfDay(new Date(now)).getTime()) / 86_400_000);
  const short = `${weekdayShort(d)} ${d.getDate()}`;
  if (diff === 0) return t('Today, {day}', { day: short });
  if (diff === 1) return t('Tomorrow, {day}', { day: short });
  if (diff === -1) return t('Yesterday, {day}', { day: short });
  return short;
}

function guideDays(end: string | undefined, now: number): number[] {
  const first = startOfDay(new Date(now)).getTime();
  const last = end ? startOfDay(new Date(end)).getTime() : first + 6 * 86_400_000;
  const out: number[] = [];
  for (let d = new Date(first); d.getTime() <= Math.max(last, first); d.setDate(d.getDate() + 1)) out.push(startOfDay(d).getTime());
  return out.slice(0, 15);
}

function GuideSettings({ favFirst, hideEmpty, onFavFirst, onHideEmpty }: {
  favFirst: boolean; hideEmpty: boolean; onFavFirst: (v: boolean) => void; onHideEmpty: (v: boolean) => void;
}) {
  const ref = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  return (
    <>
      <button ref={ref} type="button" class="lt-btn is-icon" aria-label={t('Guide settings')} aria-expanded={open ? 'true' : 'false'} onClick={() => setOpen((o) => !o)}>
        <Icon name="sliders" size={20} stroke={2} />
      </button>
      <Popover anchor={ref} open={open} onClose={() => setOpen(false)} width={320}>
        <div class="menu lt-settings">
          <span class="menu-label">{t('Guide settings')}</span>
          <div class="lt-setting">
            <span class="lt-two"><span class="lt-setting-name">{t('Favorites first')}</span><span class="lt-setting-help">{t('Your favorite channels at the top')}</span></span>
            <Switch checked={favFirst} onChange={onFavFirst} label={t('Favorites first')} />
          </div>
          <div class="lt-setting">
            <span class="lt-two"><span class="lt-setting-name">{t('Hide channels without a guide')}</span><span class="lt-setting-help">{t('Playlist channels with no guide data')}</span></span>
            <Switch checked={hideEmpty} onChange={onHideEmpty} label={t('Hide channels without a guide')} />
          </div>
        </div>
      </Popover>
    </>
  );
}

/* ── The selected programme ── */

function GuideHero({ program: p, channel, now, timers, onAbout, programs }: {
  program: Program; channel?: LiveChannel; now: number; timers: ReturnType<typeof useTimers>['timers']; onAbout: () => void; programs: Program[];
}) {
  const airing = isAiring(p, now);
  const preview = useLivePreview(airing ? channel?.Id : undefined, previewAllowed(timers, now));
  const rec = recState(p, timers);
  const tag = newTag(p);
  const cat = categoryOf(p);
  const ep = p.IsSeries && p.IndexNumber != null ? episodeTag(p) : '';
  const epLine = [ep, p.EpisodeTitle && p.EpisodeTitle !== p.Name ? p.EpisodeTitle : ''].filter(Boolean).join(' · ');
  const nextEp = p.IsSeries ? programs.find((x) => startMs(x) > startMs(p) && x.Name === p.Name && (x.IndexNumber !== p.IndexNumber || x.ParentIndexNumber !== p.ParentIndexNumber) && !x.IsRepeat) : undefined;
  const future = startMs(p) > now;
  const past = endMs(p) <= now;
  const day = startOfDay(new Date(startMs(p))).getTime() !== startOfDay(new Date(now)).getTime();
  return (
    <section class="lt-ghero" aria-labelledby="lt-ghero-h">
      <div class={'lt-ghero-art' + (preview.state === 'playing' ? ' is-playing' : '')}>
        <div class="lt-hero-art"><ProgramArt program={p} channel={channel} width={480} /></div>
        <video ref={preview.ref} class="lt-hero-preview" muted playsInline aria-hidden="true" />
        {airing && <span class="lt-ghero-live"><LivePill small /></span>}
        <SoundButton video={preview.ref} state={preview.state} small />
      </div>
      <div class="lt-ghero-info">
        <div class="lt-ghero-chan">
          <ChannelLogo channel={channel} size={32} radius={9} />
          <span class="lt-ghero-chname">{channel ? `${channel.num} · ${channel.label}` : p.ChannelName}</span>
          {p.IsLive && <span class="lt-ring is-live">{t('LIVE')}</span>}
          {tag && <span class={'lt-ring is-' + tag}>{tag === 'new' ? t('New') : t('Premiere')}</span>}
          {channel?.hd && <span class="lt-ring">{channel.hd}</span>}
        </div>
        <h2 id="lt-ghero-h" class="lt-ghero-title">{p.Name} {epLine && <span class="lt-ghero-ep">{epLine}</span>}</h2>
        <div class="lt-ghero-meta">
          <span>{[cat, day ? `${weekdayShort(new Date(startMs(p)))} ${fmtDayMonth(new Date(startMs(p)))}` : '', timeRange(p)].filter(Boolean).join(' · ')}</span>
          {airing && <div class="lt-bar is-5 lt-ghero-bar"><i style={{ width: (fraction(p, now) * 100).toFixed(2) + '%' }} /></div>}
          <b>{airing ? leftLabel(p, now) : future ? t('Starts in {time}', { time: duration((startMs(p) - now) / 1000) }) : t('Ended')}</b>
        </div>
        {p.Overview && <p class="lt-ghero-text">{p.Overview}</p>}
        <div class="lt-ghero-actions">
          <button type="button" class="lt-btn is-primary" onClick={() => watch(channel?.Id ?? p.ChannelId)}>
            <Icon name="playRound" size={18} />{t('Watch live')}
          </button>
          {!past && (
            <button type="button" class="lt-btn" aria-pressed={rec ? 'true' : 'false'} onClick={() => void toggleRecord(p)}>
              <RecDot size={17} />{rec === 'now' ? t('Recording') : rec === 'set' ? t('Recording set') : t('Record')}
            </button>
          )}
          {p.IsSeries && !past && (
            <button type="button" class="lt-btn" aria-pressed={p.SeriesTimerId ? 'true' : 'false'} onClick={() => void toggleSeries(p)}>
              <SeriesDot />{p.SeriesTimerId ? t('Series rule set') : t('Record series')}
            </button>
          )}
          <button type="button" class="lt-btn is-icon" aria-label={t('More about {title}', { title: p.Name ?? '' })} onClick={onAbout}>
            <LtIcon name="info" size={19} />
          </button>
          {nextEp && (
            <span class="lt-ghero-next">{t('Next episode')} · <b>{`${weekdayShort(new Date(startMs(nextEp)))} ${fmtDayMonth(new Date(startMs(nextEp)))}, ${clock(new Date(startMs(nextEp)))}`}</b></span>
          )}
        </div>
      </div>
    </section>
  );
}

function GuideHeroSkeleton() {
  return (
    <section class="lt-ghero" aria-hidden="true">
      <div class="lt-ghero-art skel" />
      <div class="lt-ghero-info">
        <span class="skel skel-line" style={{ width: '160px' }} />
        <span class="skel skel-line" style={{ width: '55%', height: '30px', borderRadius: '10px' }} />
        <span class="skel skel-line" style={{ width: '80%' }} />
      </div>
    </section>
  );
}

function AboutProgram({ program: p, channel, onClose }: { program: Program; channel?: LiveChannel; onClose: () => void }) {
  const ep = p.IsSeries && p.IndexNumber != null ? episodeTag(p) : '';
  const facts = [
    channel ? `${channel.num} · ${channel.label}` : p.ChannelName,
    `${weekdayShort(new Date(startMs(p)))} ${fmtDayMonth(new Date(startMs(p)))} · ${timeRange(p)}`,
    duration((endMs(p) - startMs(p)) / 1000),
    p.OfficialRating
  ].filter(Boolean);
  return (
    <>
      <div class="sheet-head">
        <div class="lt-two" style={{ gap: '6px' }}>
          <span class="sheet-title">{p.Name}</span>
          {(ep || p.EpisodeTitle) && <span class="lt-muted">{[ep, p.EpisodeTitle !== p.Name ? p.EpisodeTitle : ''].filter(Boolean).join(' · ')}</span>}
        </div>
        <button type="button" class="btn icon-btn is-bare is-40" aria-label={t('Close')} onClick={onClose}><Icon name="close" size={18} /></button>
      </div>
      <p class="sheet-text">{facts.join(' · ')}</p>
      {(p.Genres ?? []).length > 0 && <p class="sheet-text">{(p.Genres ?? []).join(' · ')}</p>}
      <p class="sheet-text" style={{ color: 'var(--seedy-text-hero)' }}>{p.Overview || t('The guide has no description for this programme.')}</p>
    </>
  );
}
