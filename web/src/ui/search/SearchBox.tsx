import type { JSX } from 'preact';
import { createPortal } from 'preact/compat';
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks';
import type { BaseItem } from '../../api/types';
import { meStore } from '../../lib/app-state';
import { getLocation, href, navigate, setQuery, useLocation } from '../../lib/router';
import { useStore } from '../../lib/store';
import { resolvePlayable } from '../../screens/browse/parts';
import { detailsPath, playPath } from '../cards';
import { Icon } from '../icons';
import { NbIcon } from '../navbar/icons';
import { addRecent } from './recent';
import { moveFocusFrom, tvActive } from '../../screens/me/effects';
import { bestMeta, bestPlay, cached, pick, rowMeta, rowTitle, search, type BestPlay, type Hit, type Results } from './engine';
import { Marked, PersonLine, SearchIdle, Thumb, moveChip, useSeerrSearch } from './parts';
import { PhoneSearch } from './PhoneSearch';
import './search.css';
import { t, tn } from '../../lib/i18n';

/* The search box in the middle of the bar (Navigation bar board, sections 1, 3 and 4).
   - Results drop down while typing, from the second letter after a short pause.
   - "/" or Ctrl/Cmd K puts the cursor in it from anywhere; arrows move, Enter opens, Esc closes.
   - On the Search page it holds the page's query instead: typing updates the page and its address.
   - On phones it's a button by the cast button that opens the full-screen search (as in the Android app); the
     Search page keeps its box under the bar. */

const MIN = 2;
const PAUSE = 250;

const editable = (el: Element | null) =>
  !!el && (el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement || (el as HTMLElement).isContentEditable
    || (el instanceof HTMLInputElement && !['checkbox', 'radio', 'button', 'submit', 'reset', 'range', 'color', 'file'].includes(el.type)));

/* "/" (not while typing elsewhere) or Ctrl/Cmd K. Dialogs keep their keys. */
function useShortcut(run: () => void, input: { current: HTMLInputElement | null }) {
  const runRef = useRef(run);
  runRef.current = run;
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented || e.altKey) return;
      const k = e.key.toLowerCase();
      const slash = e.key === '/' && !e.ctrlKey && !e.metaKey;
      const ctrlK = k === 'k' && (e.ctrlKey || e.metaKey) && !e.shiftKey;
      if (!slash && !ctrlK) return;
      const target = e.target as Element | null;
      if (target === input.current) { if (ctrlK) { e.preventDefault(); input.current?.select(); } return; }
      if (editable(target) || document.querySelector('.scrim')) return;
      e.preventDefault();
      runRef.current();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
}

type Opt = { id: string; open: () => void };

export function SearchBox({ glass, pageMode, phone, placeholder }: { glass?: boolean; pageMode?: boolean; phone?: boolean; placeholder?: string }) {
  const loc = useLocation();
  const urlQ = pageMode ? loc.query.get('q') ?? '' : '';
  const [text, setText] = useState(urlQ);
  const [open, setOpen] = useState(false);
  const [phoneOpen, setPhoneOpen] = useState(false);
  const input = useRef<HTMLInputElement>(null);
  const wrap = useRef<HTMLDivElement>(null);
  const panel = useRef<HTMLDivElement>(null);
  const dropKeys = useRef<((e: JSX.TargetedKeyboardEvent<HTMLInputElement>) => void) | null>(null);

  /* Phones only raise the keyboard for a focus made during the tap itself, and the full-screen search mounts just
     after it: a stand-in field takes the focus now and hands it over (see PhoneSearch). */
  const openPhone = () => {
    const proxy = document.createElement('input');
    proxy.setAttribute('aria-hidden', 'true');
    proxy.tabIndex = -1;
    proxy.style.cssText = 'position:fixed;top:0;left:0;width:1px;height:1px;opacity:0;border:0;padding:0;font-size:16px;';
    document.body.appendChild(proxy);
    proxy.focus({ preventScroll: true });
    window.setTimeout(() => proxy.remove(), 1500);
    setPhoneOpen(true);
  };

  useShortcut(() => {
    if (phone && !pageMode) { openPhone(); return; }
    input.current?.focus();
    input.current?.select();
  }, input);

  /* Search page: the box writes the page's query (replace, so Back leaves the page) and follows outside changes. */
  const pushed = useRef(urlQ);
  useEffect(() => {
    if (!pageMode) return;
    if (urlQ !== pushed.current) { pushed.current = urlQ; setText(urlQ); }
  }, [urlQ, pageMode]);
  useEffect(() => {
    if (!pageMode) return;
    const t = window.setTimeout(() => commitPage(text), PAUSE);
    return () => window.clearTimeout(t);
  }, [text, pageMode]);
  const commitPage = (value: string) => {
    const next = value.trim();
    const here = getLocation();
    if (here.path.toLowerCase() !== '/search') return;
    if (next === pushed.current.trim() && !(next && here.query.get('genre'))) return;
    pushed.current = next;
    // Typing leaves a genre view; clearing the box keeps it.
    setQuery(next ? { q: next, genre: null } : { q: null });
  };
  useEffect(() => () => document.documentElement.classList.remove('nb-typing'), []);
  /* The page opens with the cursor in its box (desktop). */
  useEffect(() => {
    if (pageMode && !phone && !loc.query.get('genre')) input.current?.focus({ preventScroll: true });
  }, []);

  if (phone && !pageMode) {
    return (
      <>
        <button type="button" class={'btn icon-btn nb-psearch-btn' + (glass ? ' is-glass' : '')} aria-label={t('Search')} aria-haspopup="dialog" onClick={openPhone}>
          <Icon name="search" size={20} stroke={2.2} />
        </button>
        {phoneOpen && <PhoneSearch onClose={() => setPhoneOpen(false)} />}
      </>
    );
  }

  const clear = () => {
    setText('');
    if (pageMode) commitPage('');
    input.current?.focus();
  };

  const onInputKey = (e: JSX.TargetedKeyboardEvent<HTMLInputElement>) => {
    // TV remotes and gamepads: at the box's edges the arrows move on, as from any other control.
    if (tvActive.get() && !e.altKey && !e.ctrlKey && !e.metaKey && !e.shiftKey) {
      const el = e.currentTarget;
      const atStart = el.selectionStart === 0 && el.selectionEnd === 0;
      const atEnd = el.selectionStart === el.value.length;
      const dir = e.key === 'ArrowLeft' && atStart ? 'left'
        : e.key === 'ArrowRight' && atEnd ? 'right'
        : e.key === 'ArrowUp' && (pageMode || !text.trim()) ? 'up'
        : e.key === 'ArrowDown' && pageMode ? 'down' : null;
      if (dir && moveFocusFrom(dir)) { e.preventDefault(); setOpen(false); return; }
    }
    if (pageMode) {
      if (e.key === 'Enter') { e.preventDefault(); commitPage(text); addRecent(text); }
      else if (e.key === 'Escape') { e.preventDefault(); if (text) clear(); else input.current?.blur(); }
      return;
    }
    dropKeys.current?.(e);
  };

  const hasText = text.length > 0;
  const showDrop = open && !pageMode;

  return (
    <div
      ref={wrap}
      class={'nb-search-wrap' + (phone ? ' is-phone' : '')}
      onFocusOut={(e) => {
        const next = e.relatedTarget as Node | null;
        if (!next || !wrap.current?.contains(next)) setOpen(false);
      }}
    >
      <label class={'nb-search' + (glass ? ' is-glass' : '') + (showDrop ? ' is-open' : '') + (pageMode ? ' is-page' : '') + (hasText ? ' has-text' : '') + (phone ? ' is-phone' : '')}>
        <Icon name="search" size={phone ? 19 : 20} stroke={2.2} />
        <input
          ref={input}
          type="search"
          role={pageMode ? undefined : 'combobox'}
          aria-label={placeholder ?? t('Search movies, shows and people')}
          aria-expanded={pageMode ? undefined : showDrop ? 'true' : 'false'}
          aria-controls={pageMode ? undefined : 'nb-search-panel'}
          aria-autocomplete={pageMode ? undefined : 'list'}
          placeholder={phone ? t('Search') : placeholder ?? t('Search movies, shows and people')}
          value={text}
          autoComplete="off"
          autoCapitalize="off"
          spellcheck={false}
          enterKeyHint="search"
          onFocus={() => { setOpen(true); if (phone) document.documentElement.classList.add('nb-typing'); }}
          onBlur={() => document.documentElement.classList.remove('nb-typing')}
          onClick={() => setOpen(true)}
          onInput={(e) => { setText(e.currentTarget.value); setOpen(true); }}
          onKeyDown={onInputKey}
        />
        {hasText ? (
          <button type="button" class="nb-clear" aria-label={t('Clear search')} onClick={clear} onMouseDown={(e) => e.preventDefault()}>
            <Icon name="close" size={pageMode ? 16 : 14} stroke={pageMode ? 2.4 : 2.6} />
          </button>
        ) : !phone && (
          open ? <kbd class="nb-kbd is-esc" aria-hidden="true">{t('Esc')}</kbd> : <kbd class="nb-kbd" aria-hidden="true">/</kbd>
        )}
      </label>
      {showDrop && (
        <Dropdown
          text={text}
          panel={panel}
          setText={(t) => { setText(t); input.current?.focus(); }}
          close={(blur) => { setOpen(false); if (blur) input.current?.blur(); }}
          keysRef={dropKeys}
          input={input}
        />
      )}
    </div>
  );
}

/* ── The dropdown under the box ── */

function Dropdown({ text, panel, setText, close, keysRef, input }: {
  text: string;
  panel: { current: HTMLDivElement | null };
  setText: (t: string) => void;
  close: (blur: boolean) => void;
  keysRef: { current: ((e: JSX.TargetedKeyboardEvent<HTMLInputElement>) => void) | null };
  input: { current: HTMLInputElement | null };
}) {
  const term = text.trim();
  const [query, setQ] = useState(term.length >= MIN ? term : '');
  const [state, setState] = useState<{ q: string; data?: Results; error?: boolean } | null>(() => {
    const c = term.length >= MIN ? cached(term, 'dropdown') : undefined;
    return c ? { q: term, data: c } : null;
  });
  const [active, setActive] = useState(-1);
  // Keyboard highlight gets the accent ring; the mouse just raises the row.
  const [via, setVia] = useState<'key' | 'mouse'>('key');
  const [tick, setTick] = useState(0);

  /* From the second letter, after a short pause (a remembered query shows at once). */
  useEffect(() => {
    if (term.length < MIN) { setQ(''); return; }
    if (cached(term, 'dropdown')) { setQ(term); return; }
    const t = window.setTimeout(() => setQ(term), PAUSE);
    return () => window.clearTimeout(t);
  }, [term]);

  useEffect(() => {
    if (!query) return;
    const ctrl = new AbortController();
    search(query, 'dropdown', ctrl.signal).then(
      (data) => { if (!ctrl.signal.aborted) setState({ q: query, data }); },
      (e: Error) => { if (!ctrl.signal.aborted && e?.name !== 'AbortError') setState({ q: query, error: true }); }
    );
    return () => ctrl.abort();
  }, [query, tick]);

  useEffect(() => { setActive(-1); }, [state?.q]);

  const idle = term.length < MIN;
  // While a newer query loads, the last results stay (they're usually close); the first load shows skeletons.
  const data = !idle && state?.data ? state.data : undefined;
  const loading = !idle && (!state || state.q !== query || (!state.data && !state.error) || query !== term);
  const failed = !idle && state?.error && state.q === term;

  const seerr = useSeerrSearch();
  const me = useStore(meStore);
  const shown = useMemo(() => (data ? pick(data) : null), [data]);
  const canRequestHere = me.requests.connected && me.requests.requestInSearch;

  /* Options in screen order, for the arrow keys. */
  const opts: Opt[] = [];
  const optId = (i: number) => `nb-opt-${i}`;
  const go = (to: string) => { addRecent(term); close(true); navigate(to); };
  const allPath = `/search?q=${encodeURIComponent(term)}`;
  const add = (open: () => void) => opts.push({ id: optId(opts.length), open });
  if (shown && data) {
    if (shown.best) { const it = shown.best.item; add(() => go(detailsPath(it))); }
    for (const g of shown.groups) for (const h of g.hits) { const it = h.item; add(() => go(detailsPath(it))); }
    if (data.hits.length || canRequestHere) add(() => go(allPath));
    // Seerr opens in a new tab: Enter clicks the link itself.
    if (seerr) { const id = optId(opts.length); add(() => document.getElementById(id)?.click()); }
  }
  const activeId = active >= 0 && active < opts.length ? opts[active].id : undefined;
  useLayoutEffect(() => {
    input.current?.setAttribute('aria-activedescendant', activeId ?? '');
    if (activeId) document.getElementById(activeId)?.scrollIntoView({ block: 'nearest' });
  }, [activeId]);
  useEffect(() => () => input.current?.removeAttribute('aria-activedescendant'), []);

  keysRef.current = (e) => {
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      if (idle) {
        if (e.key === 'ArrowDown') {
          const first = panel.current?.querySelector<HTMLElement>('.nb-chip[data-nbchip]') ?? panel.current?.querySelector<HTMLElement>('[data-nbchip]');
          if (first) { e.preventDefault(); first.focus(); }
        }
        return;
      }
      if (!opts.length) return;
      e.preventDefault();
      setVia('key');
      const n = opts.length;
      setActive((a) => (e.key === 'ArrowDown' ? (a + 1 >= n ? -1 : a + 1) : a <= -1 ? n - 1 : a - 1));
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (active >= 0 && opts[active]) opts[active].open();
      else if (term.length >= MIN) go(allPath);
    } else if (e.key === 'Escape') {
      // Esc drops the highlight first, then closes search.
      e.preventDefault();
      e.stopPropagation();
      if (active >= 0) setActive(-1);
      else close(true);
    }
  };

  const top = useDropTop(panel);

  let body: JSX.Element;
  if (idle) {
    body = (
      <div
        class="nb-drop-idle"
        onKeyDown={(e) => {
          if (e.key === 'Escape') { e.preventDefault(); input.current?.focus(); return; }
          const r = panel.current ? moveChip(panel.current, e.key) : 'none';
          if (r === 'out') { e.preventDefault(); input.current?.focus(); }
          else if (r === 'moved') e.preventDefault();
        }}
      >
        <SearchIdle onRecent={(q) => setText(q)} afterClear={() => input.current?.focus()} onGenre={(g) => { close(true); navigate(`/search?genre=${encodeURIComponent(g)}`); }} />
      </div>
    );
  } else if (failed) {
    body = (
      <div class="nb-drop-note" role="alert">
        <span class="nb-drop-note-title">{t('Search didn\'t work')}</span>
        <span>{t('The server didn\'t answer.')}</span>
        <button type="button" class="btn btn-secondary is-xs" onClick={() => setTick((n) => n + 1)}>{t('Try again')}</button>
      </div>
    );
  } else if (!data) {
    body = <DropSkeleton />;
  } else {
    let i = 0;
    const optProps = (open: () => void) => {
      const idx = i++;
      return {
        id: optId(idx),
        role: 'option' as const,
        'aria-selected': active === idx ? ('true' as const) : ('false' as const),
        class: active === idx ? (via === 'key' ? 'is-active' : 'is-hover') : '',
        onMouseMove: () => { if (active !== idx || via !== 'mouse') { setActive(idx); setVia('mouse'); } },
        onClick: (e: MouseEvent) => {
          // New-tab clicks go to the browser; a plain click opens here.
          if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) { addRecent(term); return; }
          e.preventDefault();
          open();
        }
      };
    };
    const empty = data.hits.length === 0;
    body = (
      <div id="nb-search-list" role="listbox" aria-label={`Search results for ${data.q}`} class={'nb-drop-list' + (loading ? ' is-stale' : '')} aria-busy={loading ? 'true' : undefined}>
        {empty && (
          <div class="nb-drop-empty">
            <span aria-hidden="true" class="nb-drop-empty-icon"><Icon name="searchX" size={22} /></span>
            <span class="nb-drop-empty-text">
              <span class="nb-drop-empty-title">Nothing in your library for “{data.q}”</span>
              <span>{canRequestHere ? t('It may not be on the server yet. You can request it.') : t('Check the spelling, or try a shorter part of the title.')}</span>
            </span>
          </div>
        )}
        {shown?.best && <BestRow hit={shown.best} props={optProps(() => go(detailsPath(shown.best!.item)))} onPlay={() => { addRecent(term); close(true); }} />}
        {shown?.groups.map((g) => (
          <div key={g.kind} role="group" aria-label={t(g.label)} class="nb-group">
            <span aria-hidden="true" class="nb-group-label">{t(g.label)}</span>
            {g.hits.map((h) => <ResultRow key={h.item.Id} hit={h} props={optProps(() => go(detailsPath(h.item)))} />)}
          </div>
        ))}
        {opts.length > 0 && <div aria-hidden="true" class="nb-drop-sep" />}
        {(!empty || canRequestHere) && (
          <a href={href(allPath)} tabIndex={-1} {...optProps(() => go(allPath))} class={'nb-drop-all ' + (active === i - 1 ? (via === 'key' ? 'is-active' : 'is-hover') : '')}>
            <span class="nb-drop-line"><Icon name="search" size={17} stroke={2.2} />{empty ? t('Request “{q}”', { q: data.q }) : tn(data.total, 'All {n} result for “{q}”', 'All {n} results for “{q}”', { q: data.q })}</span>
            <kbd class="nb-kbd is-enter" aria-hidden="true">↵</kbd>
          </a>
        )}
        {seerr && (
          <a href={seerr(term)} target="_blank" rel="noreferrer" tabIndex={-1} {...optProps(() => undefined)}
            onClick={() => { addRecent(term); }}
            class={'nb-drop-seerr ' + (active === i - 1 ? (via === 'key' ? 'is-active' : 'is-hover') : '')}>
            <span class="nb-drop-line"><NbIcon name="request" size={17} />Not here? Find “{term}” on Seerr</span>
            <NbIcon name="out" size={14} stroke={2.4} class="nb-out" />
          </a>
        )}
        {opts.length > 0 && <div aria-hidden="true" class="nb-hints">
          <span><kbd>↑ ↓</kbd> {t('move')}</span><span><kbd>↵</kbd> {t('open')}</span><span><kbd>{t('Esc')}</kbd> {t('close')}</span>
        </div>}
      </div>
    );
  }

  return (
    <>
      <div
        ref={panel}
        id="nb-search-panel"
        class={'nb-drop' + (idle ? ' is-idle' : '')}
        style={top ? { maxHeight: `calc(100dvh - ${top}px - 16px)` } : undefined}
        onMouseDown={(e) => { if (!(e.target as Element).closest('button, a')) e.preventDefault(); }}
      >
        {body}
      </div>
      <Dim onClose={() => close(true)} />
    </>
  );
}

/* Where the dropdown starts on screen, so a long list scrolls inside the window. */
function useDropTop(panel: { current: HTMLDivElement | null }) {
  const [top, setTop] = useState(0);
  useLayoutEffect(() => {
    const measure = () => setTop(Math.round(panel.current?.getBoundingClientRect().top ?? 0));
    measure();
    window.addEventListener('resize', measure);
    window.addEventListener('scroll', measure, { passive: true });
    return () => { window.removeEventListener('resize', measure); window.removeEventListener('scroll', measure); };
  }, []);
  return top;
}

/* While search is open the page dims behind the bar (see .nb-dim); clicking it closes the search. */
function Dim({ onClose }: { onClose: () => void }) {
  return createPortal(<div class="nb-dim" aria-hidden="true" onPointerDown={(e) => { e.preventDefault(); onClose(); }} />, document.body);
}

type OptProps = Record<string, unknown> & { class: string };

function BestRow({ hit, props, onPlay }: { hit: Hit; props: OptProps; onPlay: () => void }) {
  const it = hit.item;
  const [play, setPlay] = useState<BestPlay | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let live = true;
    setPlay(null);
    bestPlay(it).then((p) => { if (live) setPlay(p); }, () => { if (live) setPlay({ label: 'Play' }); });
    return () => { live = false; };
  }, [it.Id]);
  const rt = rowTitle(it);
  const start = async () => {
    setBusy(true);
    try {
      const target = play?.target ?? (await resolvePlayable(it));
      if (target) { onPlay(); navigate(playPath(target)); }
    } finally {
      setBusy(false);
    }
  };
  const { class: cls, ...rest } = props;
  return (
    <div {...rest} class={'nb-best ' + cls}>
      <a href={href(detailsPath(it))} tabIndex={-1} class="nb-best-main">
        <Thumb item={it} width={128} class="is-best" />
        <span class="nb-best-text">
          <span class="nb-best-title">{rt.lead}<Marked text={rt.name} query={hit.match} /></span>
          <span class="nb-best-meta">{bestMeta(it, play ?? undefined)}</span>
        </span>
      </a>
      <button
        type="button"
        class="nb-play"
        aria-label={t(play?.label === 'Resume' ? 'Resume {name}' : 'Play {name}', { name: it.Name })}
        disabled={busy}
        onClick={(e) => { e.preventDefault(); e.stopPropagation(); void start(); }}
      >
        <Icon name="play" size={15} />{t(play?.label ?? 'Play')}
      </button>
    </div>
  );
}

function ResultRow({ hit, props }: { hit: Hit; props: OptProps }) {
  const it: BaseItem = hit.item;
  const rt = rowTitle(it);
  const { class: cls, ...rest } = props;
  return (
    <a href={href(detailsPath(it))} tabIndex={-1} {...rest} class={'nb-row ' + cls}>
      <Thumb item={it} width={hit.kind === 'person' ? 40 : 71} />
      <span class="nb-row-text">
        <span class="nb-row-title">{rt.lead}<Marked text={rt.name} query={hit.match} /></span>
        <span class="nb-row-meta">{hit.kind === 'person' ? <PersonLine person={it} /> : rowMeta(it)}</span>
      </span>
    </a>
  );
}

function DropSkeleton() {
  return (
    <div class="nb-drop-list" aria-busy="true" aria-label={t('Searching')}>
      <div class="nb-best is-skel">
        <span class="nb-thumb is-best skel" />
        <span class="nb-best-text"><span class="skel skel-line" style={{ width: '46%', height: '16px' }} /><span class="skel skel-line" style={{ width: '70%' }} /></span>
      </div>
      {[0, 1, 2].map((k) => (
        <div key={k} class="nb-row is-skel">
          <span class="nb-thumb skel" />
          <span class="nb-row-text"><span class="skel skel-line" style={{ width: 120 + k * 30 + 'px', height: '13px' }} /><span class="skel skel-line" style={{ width: 90 + k * 20 + 'px', height: '10px' }} /></span>
        </div>
      ))}
    </div>
  );
}
