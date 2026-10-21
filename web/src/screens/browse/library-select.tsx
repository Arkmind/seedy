import type { RefObject } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { setFavorite } from '../../api/jellyfin';
import type { BaseItem } from '../../api/types';
import { isAdmin, userStore } from '../../lib/app-state';
import { t, tn } from '../../lib/i18n';
import { Icon, type IconName } from '../../ui/icons';
import { MenuItem, Popover, toast } from '../../ui/overlay';
import { canCollect, markPlayed, refreshItem } from './manage/actions';
import { openManage } from './manage/host';
import { CollectionPicker, PlaylistPicker } from './manage/menu';
import type { GridSelect } from './parts';

/* Library · select several board: pick titles in the grid, then act on all of them from the bar. */

export interface Selection {
  active: boolean;
  items: BaseItem[];
  start: () => void;
  stop: () => void;
  grid: GridSelect;
  set: (items: BaseItem[]) => void;
}

export function useSelection(visible: BaseItem[]): Selection {
  const [active, setActive] = useState(false);
  const [picked, setPicked] = useState<Map<string, BaseItem>>(new Map());
  const last = useRef<number | null>(null);

  useEffect(() => {
    if (!active) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' && !document.querySelector('[role="dialog"], .popover')) { setActive(false); setPicked(new Map()); }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [active]);

  const toggle = (item: BaseItem, index: number, shift: boolean) => {
    setActive(true);
    setPicked((m) => {
      const next = new Map(m);
      if (shift && last.current !== null) {
        const [a, b] = [Math.min(last.current, index), Math.max(last.current, index)];
        for (const it of visible.slice(a, b + 1)) next.set(it.Id, it);
      } else if (next.has(item.Id)) next.delete(item.Id);
      else next.set(item.Id, item);
      return next;
    });
    last.current = index;
  };

  return {
    active,
    items: [...picked.values()],
    start: () => setActive(true),
    stop: () => { setActive(false); setPicked(new Map()); last.current = null; },
    set: (items) => setPicked(new Map(items.map((i) => [i.Id, i]))),
    grid: { active, has: (id) => picked.has(id), toggle }
  };
}

const canDeleteAny = () => isAdmin() || !!userStore.get()?.Policy?.EnableContentDeletion || !!(userStore.get()?.Policy as { EnableContentDeletionFromFolders?: string[] } | undefined)?.EnableContentDeletionFromFolders?.length;

/* Runs an action on every picked title, a few at a time. */
async function each(items: BaseItem[], fn: (id: string) => Promise<unknown>): Promise<number> {
  let failed = 0;
  for (let i = 0; i < items.length; i += 6) {
    const results = await Promise.allSettled(items.slice(i, i + 6).map((it) => fn(it.Id)));
    failed += results.filter((r) => r.status === 'rejected').length;
  }
  return failed;
}

export function SelectionBar({ sel, total, kindLabel, onSelectAll, onChanged }: {
  sel: Selection;
  /* Everything in this view (for "Select all 248"). */
  total: number;
  /* "{n} movies selected" for the toolbar's name. */
  kindLabel: (n: number) => string;
  onSelectAll: () => Promise<void>;
  onChanged: () => void;
}) {
  const [busy, setBusy] = useState<string | null>(null);
  const [picker, setPicker] = useState<'playlist' | 'collection' | 'more' | null>(null);
  const playlistRef = useRef<HTMLButtonElement>(null);
  const collectionRef = useRef<HTMLButtonElement>(null);
  const moreRef = useRef<HTMLButtonElement>(null);
  const n = sel.items.length;
  const none = n === 0;

  const run = async (key: string, fn: (id: string) => Promise<unknown>, done: (n: number) => string) => {
    setPicker(null);
    setBusy(key);
    const failed = await each(sel.items, fn);
    setBusy(null);
    if (failed) toast(tn(failed, 'Couldn’t update {n} title', 'Couldn’t update {n} titles'), 'error');
    else toast(done(n));
    onChanged();
  };

  const btn = (key: string, icon: IconName, label: string, onClick: () => void, ref?: RefObject<HTMLButtonElement>, danger?: boolean) => (
    <button ref={ref} type="button" class={'lib-sel-btn' + (danger ? ' is-danger' : '')} disabled={none || busy !== null} onClick={onClick}
      aria-haspopup={ref ? 'menu' : undefined} aria-expanded={ref ? (picker === key ? 'true' : 'false') : undefined}>
      <Icon name={icon} size={18} />{busy === key ? t('Working…') : label}
    </button>
  );

  return (
    <div role="toolbar" aria-label={kindLabel(n)} class="lib-sel">
      <div class="lib-sel-left">
        <button type="button" class="lib-sel-close" aria-label={t('Stop selecting')} onClick={sel.stop}><Icon name="close" size={18} stroke={2.2} /></button>
        <span class="lib-sel-count" aria-live="polite">{t('{n} selected', { n })}</span>
        {n < total && (
          <button type="button" class="lib-sel-all" disabled={busy !== null} onClick={() => { setBusy('all'); onSelectAll().finally(() => setBusy(null)); }}>
            {busy === 'all' ? t('Selecting…') : t('Select all {n}', { n: total })}
          </button>
        )}
      </div>
      <div class="lib-sel-actions">
        {btn('playlist', 'playlistAdd', t('Add to playlist'), () => setPicker((p) => (p === 'playlist' ? null : 'playlist')), playlistRef)}
        {canCollect() && btn('collection', 'collection', t('Add to collection'), () => setPicker((p) => (p === 'collection' ? null : 'collection')), collectionRef)}
        {btn('watched', 'check', t('Mark watched'), () => void run('watched', (id) => markPlayed(id, true), (k) => tn(k, 'Marked {n} title as watched', 'Marked {n} titles as watched')))}
        {isAdmin() && btn('refresh', 'refetch', t('Refresh metadata'), () => void run('refresh', refreshItem, (k) => tn(k, 'Refreshing {n} title', 'Refreshing {n} titles')))}
        {canDeleteAny() && btn('delete', 'trash', t('Delete'), () => openManage('delete', sel.items), undefined, true)}
        <button ref={moreRef} type="button" class="lib-sel-more" aria-label={t('More for selected')} aria-haspopup="menu" aria-expanded={picker === 'more' ? 'true' : 'false'}
          disabled={none || busy !== null} onClick={() => setPicker((p) => (p === 'more' ? null : 'more'))}>
          <Icon name="more" size={20} />
        </button>
      </div>
      <Popover anchor={playlistRef as RefObject<HTMLElement>} open={picker === 'playlist'} onClose={() => setPicker(null)} align="start" width={320}>
        <PlaylistPicker items={sel.items} onDone={() => setPicker(null)} />
      </Popover>
      <Popover anchor={collectionRef as RefObject<HTMLElement>} open={picker === 'collection'} onClose={() => setPicker(null)} align="start" width={320}>
        <CollectionPicker items={sel.items} onDone={() => { setPicker(null); onChanged(); }} />
      </Popover>
      <Popover anchor={moreRef as RefObject<HTMLElement>} open={picker === 'more'} onClose={() => setPicker(null)} width={260}>
        <div class="menu" role="menu">
          <MenuItem icon="check" label={t('Mark unwatched')} onSelect={() => void run('watched', (id) => markPlayed(id, false), (k) => tn(k, 'Marked {n} title as unwatched', 'Marked {n} titles as unwatched'))} />
          <MenuItem icon="heart" label={t('Add to favorites')} onSelect={() => void run('fav', (id) => setFavorite(id, true), (k) => tn(k, 'Added {n} title to favorites', 'Added {n} titles to favorites'))} />
          <MenuItem icon="heart" label={t('Remove from favorites')} onSelect={() => void run('fav', (id) => setFavorite(id, false), (k) => tn(k, 'Removed {n} title from favorites', 'Removed {n} titles from favorites'))} />
          <div class="menu-sep" />
          <MenuItem icon="close" label={t('Clear selection')} onSelect={() => { setPicker(null); sel.set([]); }} />
        </div>
      </Popover>
    </div>
  );
}
