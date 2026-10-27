import type { ComponentChildren, RefObject } from 'preact';
import { createPortal } from 'preact/compat';
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { JELLYFIN_WEB, get, getSession } from '../../api/client';
import { getItems, type ItemsQuery } from '../../api/jellyfin';
import type { BaseItem, ItemsResult } from '../../api/types';
import { meStore, viewsStore } from '../../lib/app-state';
import { Link, href, navigate } from '../../lib/router';
import { createStore, useStore } from '../../lib/store';
import { Icon } from '../icons';
import { NbIcon, libraryIcon } from './icons';
import { t } from '../../lib/i18n';

/* The Libraries menu (Navigation bar board, section 2): every library in a two-column grid with its count, the
   current one raised, then "Viewing history" and "Order and hide libraries". On phones it opens as a sheet. */

/* Views Seedy can browse; Live TV has its own pages (/livetv); photos and plain folders open in Jellyfin's own app. */
export const BROWSABLE = new Set(['movies', 'tvshows', 'music', 'boxsets', 'homevideos', 'musicvideos', 'mixed', 'playlists', 'books', undefined, '']);

export type ActiveView = { viewId: string } | { collectionType: string } | string | null | undefined;

export function currentView(views: BaseItem[], active: ActiveView): BaseItem | undefined {
  if (!active || typeof active === 'string') return undefined;
  if ('viewId' in active) return views.find((v) => v.Id === active.viewId);
  return views.find((v) => v.CollectionType === active.collectionType);
}

function jellyfinHref(v: BaseItem): string {
  const server = getSession()?.serverId;
  return `${JELLYFIN_WEB}#/list?parentId=${encodeURIComponent(v.Id)}${server ? '&serverId=' + encodeURIComponent(server) : ''}`;
}

/* ── Counts: "248 movies", "61 shows · 4 new episodes", "1,204 videos and photos" ── */

const fmt = (n: number) => n.toLocaleString('en-US');
const count = (n: number, one: string, many = one + 's') => `${fmt(n)} ${n === 1 ? one : many}`;
const countsStore = createStore<Record<string, string>>({});
let countsAt = 0;
let countsFor = '';

/* Only the total is needed: one item, no extra fields. */
const total = (q: ItemsQuery) =>
  getItems({ recursive: true, limit: 1, enableTotalRecordCount: true, fields: ['DateCreated'], enableImageTypes: ['Primary'], ...q }).then((r) => r.TotalRecordCount);

async function countLine(v: BaseItem): Promise<string> {
  const parentId = v.Id;
  switch (v.CollectionType) {
    case 'movies': return count(await total({ parentId, includeItemTypes: ['Movie'] }), 'movie');
    case 'tvshows': {
      const [shows, recent] = await Promise.all([
        total({ parentId, includeItemTypes: ['Series'] }),
        getItems({
          parentId, includeItemTypes: ['Episode'], filters: ['IsUnplayed'], isMissing: false, sortBy: ['DateCreated'], sortOrder: 'Descending',
          limit: 60, fields: ['DateCreated'], enableTotalRecordCount: false, enableImageTypes: ['Primary']
        }).catch(() => ({ Items: [] as BaseItem[], TotalRecordCount: 0 }))
      ]);
      const week = Date.now() - 7 * 86_400_000;
      const fresh = recent.Items.filter((e) => e.DateCreated && new Date(e.DateCreated).getTime() >= week).length;
      return count(shows, 'show') + (fresh ? ` · ${count(fresh, 'new episode')}` : '');
    }
    case 'boxsets': return count(await total({ parentId, includeItemTypes: ['BoxSet'] }), 'collection');
    case 'homevideos': {
      const n = await total({ parentId, includeItemTypes: ['Video', 'Photo'] });
      return n === 1 ? '1 video or photo' : `${fmt(n)} videos and photos`;
    }
    case 'photos': return count(await total({ parentId, includeItemTypes: ['Photo'] }), 'photo');
    case 'livetv': {
      const r = await get<ItemsResult>('/LiveTv/Channels', { userId: getSession()?.userId, limit: 1, enableTotalRecordCount: true, enableImages: false });
      return count(r.TotalRecordCount, 'channel');
    }
    case 'playlists': return count(await total({ parentId, includeItemTypes: ['Playlist'] }), 'playlist');
    case 'music': return count(await total({ parentId, includeItemTypes: ['MusicAlbum'] }), 'album');
    case 'musicvideos': return count(await total({ parentId, includeItemTypes: ['MusicVideo'] }), 'music video');
    case 'books': return count(await total({ parentId, includeItemTypes: ['Book'] }), 'book');
    default: return count(await total({ parentId, excludeItemTypes: ['Folder', 'CollectionFolder', 'Season'] }), 'item');
  }
}

/* Counts load the first time the menu opens and refresh at most every five minutes. */
function loadCounts(views: BaseItem[]) {
  const who = (getSession()?.userId ?? '') + views.map((v) => v.Id).join(',');
  if (who === countsFor && Date.now() - countsAt < 5 * 60_000) return;
  // Someone else signed in: their libraries hold other titles, so the old counts go.
  if (countsFor.slice(0, 32) !== who.slice(0, 32)) countsStore.set({});
  countsFor = who;
  countsAt = Date.now();
  for (const v of views) {
    countLine(v).then(
      (line) => countsStore.set((c) => ({ ...c, [v.Id]: line })),
      () => countsStore.set((c) => (c[v.Id] ? c : { ...c, [v.Id]: '' }))
    );
  }
}

/* "Order and hide libraries" lands on the Libraries section of Settings · Home screen. */
export function openLibrarySettings() {
  navigate('/settings/home#libs');
  let tries = 0;
  const find = () => {
    const el = document.getElementById('libs-h');
    if (el) { el.closest('section')?.scrollIntoView({ block: 'start' }); return; }
    if (++tries < 60) window.setTimeout(find, 50);
  };
  window.setTimeout(find, 30);
}

/* ── The menu's body (desktop popover and phone sheet) ── */

function LibraryEntry({ view, current, onPick }: { view: BaseItem; current: boolean; onPick: () => void }) {
  const counts = useStore(countsStore);
  const line = counts[view.Id];
  const body = (
    <>
      <span aria-hidden="true" class="nb-lib-icon"><NbIcon name={libraryIcon(view.CollectionType)} size={22} stroke={2} /></span>
      <span class="nb-lib-text">
        <span class="nb-lib-name">{view.Name}</span>
        {line === undefined ? <span class="nb-lib-count skel skel-line" aria-hidden="true" /> : line && <span class="nb-lib-count">{line}</span>}
      </span>
    </>
  );
  const cls = 'nb-lib' + (current ? ' is-current' : '');
  if (BROWSABLE.has(view.CollectionType) || view.CollectionType === 'livetv') {
    const to = view.CollectionType === 'livetv' ? '/livetv' : `/library/${view.Id}`;
    return <Link to={to} role="menuitem" class={cls} aria-current={current ? 'page' : undefined} data-nbnav onClick={onPick}>{body}</Link>;
  }
  return (
    <a href={jellyfinHref(view)} target="_blank" rel="noreferrer" role="menuitem" class={cls} data-nbnav title={t("Opens in Jellyfin's app")} onClick={onPick}>
      {body}
    </a>
  );
}

export function LibraryMenuBody({ currentId, onPick }: { currentId?: string; onPick: () => void }) {
  const views = useStore(viewsStore);
  const me = useStore(meStore);
  useEffect(() => { loadCounts(views); }, [views]);
  return (
    <>
      {views.length > 0 ? (
        <div class="nb-libs-grid">
          {views.map((v) => <LibraryEntry key={v.Id} view={v} current={v.Id === currentId} onPick={onPick} />)}
        </div>
      ) : (
        <p class="nb-libs-empty">{t('No libraries yet. An admin adds them under Admin · Libraries.')}</p>
      )}
      <div aria-hidden="true" class="nb-libs-sep" />
      <div class="nb-libs-foot">
        {me.historyEnabled && (
          <Link to="/history" role="menuitem" class="nb-libs-link" data-nbnav onClick={onPick}>
            <Icon name="clock" size={16} stroke={2.2} />{t('Viewing history')}</Link>
        )}
        <a
          href={href('/settings/home#libs')}
          role="menuitem"
          class="nb-libs-link"
          data-nbnav
          onClick={(e) => {
            if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
            e.preventDefault();
            onPick();
            openLibrarySettings();
          }}
        >
          <Icon name="sliders" size={16} stroke={2.2} />{t('Order and hide libraries')}</a>
      </div>
    </>
  );
}


/* Arrow keys move through the grid by position (two columns), then the footer links. */
function moveIn(container: HTMLElement, key: string): boolean {
  const els = Array.from(container.querySelectorAll<HTMLElement>('[data-nbnav]'));
  if (!els.length) return false;
  const cur = document.activeElement as HTMLElement | null;
  const i = cur ? els.indexOf(cur) : -1;
  if (i < 0) { els[0].focus(); return true; }
  const c = cur!.getBoundingClientRect();
  const cx = c.left + c.width / 2;
  const cy = c.top + c.height / 2;
  let best: HTMLElement | null = null;
  let bestScore = Infinity;
  for (const el of els) {
    if (el === cur) continue;
    const r = el.getBoundingClientRect();
    const x = r.left + r.width / 2;
    const y = r.top + r.height / 2;
    let ok = false;
    let score = 0;
    if (key === 'ArrowDown') { ok = r.top >= c.bottom - 2; score = (y - cy) * 4 + Math.abs(x - cx); }
    if (key === 'ArrowUp') { ok = r.bottom <= c.top + 2; score = (cy - y) * 4 + Math.abs(x - cx); }
    if (key === 'ArrowRight') { ok = Math.abs(y - cy) < c.height / 2 && x > cx; score = x - cx; }
    if (key === 'ArrowLeft') { ok = Math.abs(y - cy) < c.height / 2 && x < cx; score = cx - x; }
    if (ok && score < bestScore) { bestScore = score; best = el; }
  }
  if (!best) return false;
  best.focus();
  return true;
}

/* ── Desktop: the button and its menu ── */

export function LibrariesButton({ active, glass }: { active: ActiveView; glass?: boolean }) {
  const views = useStore(viewsStore);
  const cur = currentView(views, active);
  /* On the Live TV pages the button reads "Live TV", even when the profile hides that library. */
  const live = !!active && typeof active === 'object' && 'collectionType' in active && active.collectionType === 'livetv';
  const name = live ? t('Live TV') : cur?.Name;
  const [open, setOpen] = useState(false);
  const btn = useRef<HTMLButtonElement>(null);
  return (
    <>
      <button
        ref={btn}
        type="button"
        class={'nb-link nb-libs-btn' + (name || open ? ' is-current' : '') + (open ? ' is-open' : '') + (glass ? ' is-glass' : '')}
        aria-haspopup="menu"
        aria-expanded={open ? 'true' : 'false'}
        aria-label={name ? t('Libraries, now in {name}', { name }) : undefined}
        onClick={() => setOpen((o) => !o)}
        onKeyDown={(e) => { if (e.key === 'ArrowDown' && !open) { e.preventDefault(); setOpen(true); } }}
      >
        <Icon name="grid" size={18} stroke={2.2} />
        {name || t('Libraries')}
        <Icon name="chevronDown" size={15} stroke={2.4} class="nb-chev" />
      </button>
      <AnchoredMenu anchor={btn} open={open} onClose={() => setOpen(false)} label={t('Libraries')}>
        <LibraryMenuBody currentId={cur?.Id} onPick={() => setOpen(false)} />
      </AnchoredMenu>
    </>
  );
}

/* A menu placed under its button, its icons lined up with the button's icon, kept inside the window. */
function AnchoredMenu({ anchor, open, onClose, label, children }: {
  anchor: RefObject<HTMLElement>; open: boolean; onClose: () => void; label: string; children: ComponentChildren;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ top: number; left: number; width: number } | null>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  useLayoutEffect(() => {
    if (!open) { setPos(null); return; }
    const place = () => {
      const a = anchor.current;
      if (!a) return;
      const r = a.getBoundingClientRect();
      const vw = document.documentElement.clientWidth;
      const width = Math.min(560, vw - 16);
      setPos({ top: r.bottom + 14, left: Math.max(8, Math.min(r.left - 8, vw - width - 8)), width });
    };
    place();
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    return () => {
      window.removeEventListener('resize', place);
      window.removeEventListener('scroll', place, true);
    };
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      const t = e.target as Node;
      if (ref.current?.contains(t) || anchor.current?.contains(t)) return;
      closeRef.current();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        e.preventDefault();
        closeRef.current();
        anchor.current?.focus();
      }
    };
    document.addEventListener('pointerdown', onDown, true);
    document.addEventListener('keydown', onKey, true);
    const raf = requestAnimationFrame(() => {
      const first = ref.current?.querySelector<HTMLElement>('[data-nbnav][aria-current="page"], [data-nbnav]');
      first?.focus({ preventScroll: true });
    });
    return () => {
      cancelAnimationFrame(raf);
      document.removeEventListener('pointerdown', onDown, true);
      document.removeEventListener('keydown', onKey, true);
    };
  }, [open]);

  if (!open || !pos) return null;
  return createPortal(
    <div
      ref={ref}
      class="popover nb-libs"
      role="menu"
      aria-label={label}
      style={{ top: pos.top + 'px', left: pos.left + 'px', width: pos.width + 'px' }}
      onKeyDown={(e) => {
        if (e.key.startsWith('Arrow') && ref.current && moveIn(ref.current, e.key)) e.preventDefault();
      }}
      onFocusOut={(e) => {
        const next = e.relatedTarget as Node | null;
        if (next && !ref.current?.contains(next) && !anchor.current?.contains(next)) closeRef.current();
      }}
    >
      {children}
    </div>,
    document.body
  );
}

/* ── Phone: the Libraries tab opens the same menu as a sheet from the bottom ── */

export function LibrariesSheet({ open, onClose, active }: { open: boolean; onClose: () => void; active: ActiveView }) {
  const views = useStore(viewsStore);
  const cur = currentView(views, active);
  const ref = useRef<HTMLDivElement>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    if (!open) return;
    const prev = document.activeElement as HTMLElement | null;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.stopPropagation(); closeRef.current(); return; }
      if (e.key.startsWith('Arrow') && ref.current && moveIn(ref.current, e.key)) e.preventDefault();
    };
    document.addEventListener('keydown', onKey, true);
    requestAnimationFrame(() => ref.current?.querySelector<HTMLElement>('[data-nbnav][aria-current="page"], [data-nbnav]')?.focus({ preventScroll: true }));
    return () => {
      document.removeEventListener('keydown', onKey, true);
      if (prev?.isConnected) prev.focus({ preventScroll: true });
    };
  }, [open]);
  if (!open) return null;
  return createPortal(
    <div class="scrim nb-sheet-scrim" onPointerDown={(e) => { if (e.target === e.currentTarget) closeRef.current(); }}>
      <div ref={ref} class="sheet nb-sheet" role="dialog" aria-modal="true" aria-label={t('Libraries')}>
        <div class="nb-sheet-head">
          <span aria-hidden="true" class="nb-sheet-grab" />
          <h2 class="nb-sheet-title">{t('Libraries')}</h2>
          <button type="button" class="btn icon-btn is-bare is-40" aria-label={t('Close')} onClick={() => closeRef.current()}><Icon name="close" size={18} /></button>
        </div>
        <div class="nb-libs is-sheet" role="menu" aria-label={t('Libraries')}>
          <LibraryMenuBody currentId={cur?.Id} onPick={() => closeRef.current()} />
        </div>
      </div>
    </div>,
    document.body
  );
}
