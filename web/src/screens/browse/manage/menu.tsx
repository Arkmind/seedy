import type { ComponentChildren, RefObject } from 'preact';
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { JELLYFIN_WEB } from '../../../api/client';
import type { BaseItem } from '../../../api/types';
import { t, tn } from '../../../lib/i18n';
import { Icon, type IconName } from '../../../ui/icons';
import { Popover, toast } from '../../../ui/overlay';
import {
  addToCollection, addToPlaylist, canCollect, canDelete, canManage, createCollection, getChildren, getCollections, getDeletePreview, getPlaylist,
  getPlaylistItems, getPlaylists, getShareWith, refreshItem, type DeletePreview
} from './actions';
import { openManage } from './host';
import { deleteSize } from './delete';

/* Details · More menu board: the item's own actions on top (from the page), Add to playlist and Add to collection
   with their side menus, the Manage group for admins, Delete, then Open in Jellyfin and Copy link. */

const TYPE_DELETE: Record<string, string> = { Series: 'Delete series', Season: 'Delete season', Episode: 'Delete episode', Movie: 'Delete movie' };

export function ItemMenu({ item, children, shortcut }: {
  item: BaseItem;
  /* The page's own entries ("Play from the first episode", "Go to season"), given the close function. */
  children?: (close: () => void) => ComponentChildren;
  /* Pressing E opens Edit metadata (once per page). */
  shortcut?: boolean;
}) {
  const ref = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const [sub, setSub] = useState<'playlist' | 'collection' | null>(null);
  const [preview, setPreview] = useState<DeletePreview | null>(null);
  const close = () => { setOpen(false); setSub(null); };
  const manage = canManage();
  const deletable = canDelete(item);
  /* People can't go in playlists; collections don't go in collections. */
  const listable = item.Type !== 'Person';
  const collectable = listable && canCollect() && item.Type !== 'BoxSet' && item.Type !== 'Playlist';

  useEffect(() => {
    if (!open || !deletable) return;
    getDeletePreview([item.Id]).then(setPreview, () => setPreview(null));
  }, [open, item.Id, deletable]);

  useEffect(() => {
    if (!shortcut || !manage) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'e' && e.key !== 'E') return;
      if (e.metaKey || e.ctrlKey || e.altKey || e.defaultPrevented) return;
      const el = document.activeElement as HTMLElement | null;
      if (el && (el.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName))) return;
      if (document.querySelector('[role="dialog"]')) return;
      e.preventDefault();
      close();
      openManage('metadata', item);
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [shortcut, manage, item]);

  const act = (fn: () => void) => () => { close(); fn(); };
  const refresh = async () => {
    try {
      await refreshItem(item.Id);
      toast(t('Refreshing {name}', { name: item.Name ?? '' }));
    } catch {
      toast(t('Couldn’t refresh the metadata'), 'error');
    }
  };
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(window.location.href);
      toast(t('Link copied'));
    } catch {
      toast(t('Couldn’t copy the link'), 'error');
    }
  };

  const p = preview?.items[0];
  const seasons = p?.groups.filter((g) => g.kind === 'season').length ?? 0;
  const deleteSub = p ? [item.Type === 'Series' && seasons ? tn(seasons, '{n} season', '{n} seasons') : '', deleteSize(p.bytes)].filter(Boolean).join(' · ') : undefined;

  return (
    <>
      <button ref={ref} type="button" class={'btn btn-square' + (open ? ' is-flipped' : '')} aria-label={t('More actions')} aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={() => (open ? close() : setOpen(true))}>
        <Icon name="more" size={22} />
      </button>
      <Popover anchor={ref as RefObject<HTMLElement>} open={open} onClose={close} align="start" width={304}>
        <div class="im-menu-wrap">
          <div class="menu im-menu" role="menu" aria-label={t('More actions for {name}', { name: item.Name ?? '' })}>
            {children?.(close)}
            {listable && <SubItem icon="playlistAdd" label={t('Add to playlist')} open={sub === 'playlist'} onOpen={() => setSub('playlist')} />}
            {collectable && <SubItem icon="collection" label={t('Add to collection')} open={sub === 'collection'} onOpen={() => setSub('collection')} />}
            {manage && (
              <>
                <div role="separator" class="im-menu-sep" />
                <span role="presentation" class="im-menu-label">{t('Manage')}</span>
                <Entry icon="pencil" label={t('Edit metadata')} kbd="E" onSelect={act(() => openManage('metadata', item))} />
                <Entry icon="identify" label={t('Identify')} sub={t('Fix a wrong match')} onSelect={act(() => openManage('identify', item))} />
                <Entry icon="image" label={t('Edit images')} onSelect={act(() => openManage('images', item))} />
                <Entry icon="refetch" label={t('Refresh metadata')} sub={t('New info and artwork from providers')} onSelect={act(() => void refresh())} />
              </>
            )}
            {deletable && (
              <>
                <div role="separator" class="im-menu-sep" />
                <Entry icon="trash" danger label={t(TYPE_DELETE[item.Type] ?? 'Delete')} sub={deleteSub} onSelect={act(() => openManage('delete', item))} />
              </>
            )}
            <div role="separator" class="im-menu-sep" />
            <Entry icon="external" label={t('Open in Jellyfin')} href={`${JELLYFIN_WEB}#/details?id=${item.Id}`} onSelect={close} />
            <Entry icon="link" label={t('Copy link')} onSelect={act(() => void copy())} />
          </div>
          {sub && (
            <SidePanel anchorSel={sub === 'playlist' ? '[data-sub="playlistAdd"]' : '[data-sub="collection"]'}>
              {sub === 'playlist'
                ? <PlaylistPicker items={[item]} onDone={close} onBack={() => setSub(null)} />
                : <CollectionPicker items={[item]} onDone={close} onBack={() => setSub(null)} />}
            </SidePanel>
          )}
        </div>
      </Popover>
    </>
  );
}

function Entry({ icon, label, sub, kbd, danger, href, onSelect }: {
  icon: IconName; label: string; sub?: string; kbd?: string; danger?: boolean; href?: string; onSelect: () => void;
}) {
  const body = (
    <>
      <Icon name={icon} size={18} />
      <span class="im-menu-text"><span>{label}</span>{sub && <span class="im-menu-sub">{sub}</span>}</span>
      {kbd && <kbd class="im-kbd">{kbd}</kbd>}
    </>
  );
  const cls = 'menu-item im-menu-item' + (danger ? ' is-danger' : '');
  if (href) return <a role="menuitem" class={cls} href={href} target="_blank" rel="noreferrer" onClick={onSelect}>{body}</a>;
  return <button type="button" role="menuitem" class={cls} onClick={onSelect}>{body}</button>;
}

function SubItem({ icon, label, open, onOpen }: { icon: IconName; label: string; open: boolean; onOpen: () => void }) {
  return (
    <button
      type="button" role="menuitem" data-sub={icon} class={'menu-item im-menu-item' + (open ? ' is-open' : '')}
      aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'}
      onClick={onOpen}
      onPointerEnter={(e) => { if (e.pointerType === 'mouse') onOpen(); }}
      onKeyDown={(e) => { if (e.key === 'ArrowRight') { e.preventDefault(); onOpen(); } }}
    >
      <Icon name={icon} size={18} />
      <span class="im-menu-text"><span>{label}</span></span>
      <Icon name="chevronRight" size={15} stroke={2.4} />
    </button>
  );
}

/* The side menu sits right of the main one, level with the entry that opened it (left of it when there's no room;
   in place of it on a phone). */
function SidePanel({ anchorSel, children }: { anchorSel: string; children: ComponentChildren }) {
  const ref = useRef<HTMLDivElement>(null);
  const [style, setStyle] = useState<Record<string, string>>({ visibility: 'hidden' });
  useEffect(() => {
    const el = ref.current;
    const wrap = el?.parentElement;
    const anchor = wrap?.querySelector<HTMLElement>(anchorSel);
    if (!el || !wrap || !anchor) return;
    const w = wrap.getBoundingClientRect();
    const a = anchor.getBoundingClientRect();
    const vw = document.documentElement.clientWidth;
    const vh = document.documentElement.clientHeight;
    const width = el.offsetWidth;
    const top = Math.max(8 - w.top, Math.min(a.top - w.top - 8, vh - 8 - w.top - el.offsetHeight));
    if (vw < 720) setStyle({ top: '0px', left: '0px', width: w.width + 'px' });
    else if (w.right + 8 + width <= vw - 8) setStyle({ top: top + 'px', left: 'calc(100% + 8px)' });
    else setStyle({ top: top + 'px', right: 'calc(100% + 8px)' });
    requestAnimationFrame(() => el.querySelector<HTMLElement>('input')?.focus({ preventScroll: true }));
  }, [anchorSel]);
  return <div ref={ref} class="im-side" style={style}>{children}</div>;
}

/* ── Pickers (also used by the library's selection bar) ── */

interface Row { id: string; name: string; sub: string; added: boolean }

function PickerPanel({ label, placeholder, rows, onPick, newLabel, onNew, onBack, icon, busy }: {
  label: string; placeholder: string; rows: Row[] | null; onPick: (r: Row) => void; newLabel: (q: string) => string; onNew: (q: string) => void;
  onBack?: () => void; icon: IconName; busy: string | null;
}) {
  const [q, setQ] = useState('');
  const shown = useMemo(() => (rows ?? []).filter((r) => r.name.toLowerCase().includes(q.trim().toLowerCase())), [rows, q]);
  return (
    <div class="menu im-menu im-picker" role="menu" aria-label={label} onKeyDown={(e) => { if (e.key === 'ArrowLeft' && onBack && !(e.target as HTMLElement).matches('input')) { e.preventDefault(); onBack(); } }}>
      <label class="im-find">
        <Icon name="search" size={16} stroke={2.2} />
        <input type="text" aria-label={placeholder} placeholder={placeholder} value={q} onInput={(e) => setQ((e.target as HTMLInputElement).value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && q.trim() && !shown.length) onNew(q.trim()); }} />
      </label>
      {!rows ? (
        [0, 1, 2].map((i) => <div key={i} class="im-pick is-skel"><span class="skel im-pick-icon" /><span class="skel skel-line" /></div>)
      ) : (
        shown.map((r) => (
          <button key={r.id} type="button" role="menuitemcheckbox" aria-checked={r.added ? 'true' : 'false'} class="menu-item im-pick" disabled={busy !== null} onClick={() => onPick(r)}>
            <span aria-hidden="true" class="im-pick-icon"><Icon name={icon} size={17} /></span>
            <span class="im-menu-text"><span class="im-pick-name">{r.name}</span><span class="im-menu-sub">{busy === r.id ? t('Adding…') : r.sub}</span></span>
            {r.added && <span class="im-added"><Icon name="check" size={15} stroke={3} />{t('Added')}</span>}
          </button>
        ))
      )}
      {rows && !shown.length && !q && <p class="im-pick-empty">{t('None yet.')}</p>}
      <div role="separator" class="im-menu-sep" />
      <button type="button" role="menuitem" class="menu-item im-menu-item im-new" disabled={busy !== null} onClick={() => onNew(q.trim())}>
        <Icon name="plus" size={18} stroke={2.4} />
        <span class="im-menu-text"><span>{newLabel(q.trim())}</span></span>
      </button>
    </div>
  );
}

function shareLabel(info: { OpenAccess?: boolean; Shares?: { UserId: string }[] } | null, names: Map<string, string>): string {
  if (info?.OpenAccess) return t('everyone on the server');
  const shares = info?.Shares ?? [];
  if (!shares.length) return t('only you');
  const who = shares.map((s) => names.get(s.UserId.replace(/-/g, ''))).filter(Boolean) as string[];
  if (who.length === shares.length && who.length <= 2) return t('shared with {names}', { names: who.join(t(' and ')) });
  return tn(shares.length, 'shared with {n} person', 'shared with {n} people');
}

/* Whether a playlist or collection already holds the items: their own ids, or (for a series or season) its episodes. */
function holds(entries: BaseItem[], items: BaseItem[]): boolean {
  return items.every((it) => entries.some((e) => e.Id === it.Id || e.SeriesId === it.Id || e.SeasonId === it.Id));
}

export function PlaylistPicker({ items, onDone, onBack }: { items: BaseItem[]; onDone: () => void; onBack?: () => void }) {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    (async () => {
      const [lists, people] = await Promise.all([getPlaylists(), getShareWith()]);
      const names = new Map((people ?? []).map((p) => [p.id, p.name]));
      const out = await Promise.all(lists.Items.slice(0, 30).map(async (pl) => {
        const [info, entries] = await Promise.all([getPlaylist(pl.Id).catch(() => null), getPlaylistItems(pl.Id).then((r) => r.Items, () => [])]);
        const n = pl.ChildCount ?? entries.length;
        return { id: pl.Id, name: pl.Name ?? '', sub: `${tn(n, '{n} item', '{n} items')} · ${shareLabel(info, names)}`, added: holds(entries, items) };
      }));
      if (live) setRows(out);
    })().catch(() => { if (live) setRows([]); });
    return () => { live = false; };
  }, [items]);
  const pick = async (r: Row) => {
    if (r.added) { toast(t('Already in {name}', { name: r.name })); return; }
    setBusy(r.id);
    try {
      await addToPlaylist(r.id, items.map((i) => i.Id));
      toast(t('Added to {name}', { name: r.name }));
      onDone();
    } catch {
      toast(t('Couldn’t add to {name}', { name: r.name }), 'error');
      setBusy(null);
    }
  };
  return (
    <PickerPanel
      label={t('Add to playlist')} placeholder={t('Find a playlist')} rows={rows} icon="playlistAdd" busy={busy} onBack={onBack}
      onPick={(r) => void pick(r)} newLabel={() => t('New playlist…')}
      onNew={(q) => { onDone(); openManage('playlist', items, { name: q }); }}
    />
  );
}

export function CollectionPicker({ items, onDone, onBack }: { items: BaseItem[]; onDone: () => void; onBack?: () => void }) {
  const [rows, setRows] = useState<Row[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    (async () => {
      const lists = await getCollections();
      const out = await Promise.all(lists.Items.slice(0, 40).map(async (c) => {
        const entries = await getChildren(c.Id).then((r) => r.Items, () => []);
        const n = c.ChildCount ?? entries.length;
        return { id: c.Id, name: c.Name ?? '', sub: tn(n, '{n} item', '{n} items'), added: holds(entries, items) };
      }));
      if (live) setRows(out);
    })().catch(() => { if (live) setRows([]); });
    return () => { live = false; };
  }, [items]);
  const ids = items.map((i) => i.Id);
  const pick = async (r: Row) => {
    if (r.added) { toast(t('Already in {name}', { name: r.name })); return; }
    setBusy(r.id);
    try {
      await addToCollection(r.id, ids);
      toast(t('Added to {name}', { name: r.name }));
      onDone();
    } catch {
      toast(t('Couldn’t add to {name}', { name: r.name }), 'error');
      setBusy(null);
    }
  };
  const create = async (name: string) => {
    if (!name) { (document.querySelector('.im-picker .im-find input') as HTMLInputElement | null)?.focus(); return; }
    setBusy('new');
    try {
      await createCollection(name, ids);
      toast(t('Added to {name}', { name }));
      onDone();
    } catch {
      toast(t('Couldn’t create the collection'), 'error');
      setBusy(null);
    }
  };
  return (
    <PickerPanel
      label={t('Add to collection')} placeholder={t('Find or name a collection')} rows={rows} icon="collection" busy={busy} onBack={onBack}
      onPick={(r) => void pick(r)} newLabel={(q) => (q ? t('New collection “{name}”', { name: q }) : t('New collection…'))}
      onNew={(q) => void create(q)}
    />
  );
}
