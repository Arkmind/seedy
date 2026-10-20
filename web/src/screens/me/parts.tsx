import type { ComponentChildren, RefObject } from 'preact';
import { useLayoutEffect, useRef, useState } from 'preact/hooks';
import { t } from '../../lib/i18n';
import { useStore } from '../../lib/store';
import { Icon } from '../../ui/icons';
import { MenuButton, MenuItem } from '../../ui/overlay';
import { DeviceTag } from './MeLayout';
import { retrySave, saveState } from './prefs';

/* Building blocks for the personal settings pages, measured from the MeProfile … MeControls boards. */

export function MeSection({ id, title, help, device, children, class: cls, head }: {
  id?: string;
  title?: ComponentChildren;
  help?: ComponentChildren;
  device?: boolean;
  children?: ComponentChildren;
  class?: string;
  /* Replaces the title block (sections with a control beside the title). */
  head?: ComponentChildren;
}) {
  const hid = id ? id + '-h' : undefined;
  return (
    <section aria-labelledby={hid} class={'me-section' + (cls ? ' ' + cls : '')}>
      {head ?? (title && (
        <div class="me-section-head">
          <h2 id={hid} class="me-section-title">{title}{device && <DeviceTag />}</h2>
          {help && <p class="me-section-help">{help}</p>}
        </div>
      ))}
      {children}
    </section>
  );
}

export function MeRow({ name, help, device, children, htmlFor, narrow, class: cls, nameId }: {
  name: ComponentChildren;
  help?: ComponentChildren;
  device?: boolean;
  children?: ComponentChildren;
  htmlFor?: string;
  /* Display and Controls give the label 260 px before the control wraps; Profile and Home 300 px. */
  narrow?: boolean;
  class?: string;
  nameId?: string;
}) {
  const Label = htmlFor ? 'label' : 'span';
  return (
    <div class={'me-row' + (narrow ? ' is-narrow' : '') + (cls ? ' ' + cls : '')}>
      <Label {...(htmlFor ? { for: htmlFor } : {})} class="me-row-label">
        <span class="me-row-name" id={nameId}>{name}{device && <DeviceTag />}</span>
        {help && <span class="me-row-help">{help}</span>}
      </Label>
      {children}
    </div>
  );
}

/* "Changes save as you make them", or what's happening to the last change. */
export function SaveLine() {
  const state = useStore(saveState);
  return (
    <div class="me-saved" aria-live="polite">
      {state === 'error' ? (
        <>
          <span class="me-saved-text is-error"><Icon name="alert" size={16} />{t("Couldn't save your last change.")}</span>
          <button type="button" class="link-btn me-saved-retry" onClick={retrySave}>{t('Try again')}</button>
        </>
      ) : state === 'saving' ? (
        <span class="me-saved-text"><span class="dot is-pending" aria-hidden="true" />{t('Saving…')}</span>
      ) : (
        <span class="me-saved-text"><Icon name="check" size={16} stroke={2.6} color="var(--seedy-ok)" />{t('Changes save as you make them')}</span>
      )}
    </div>
  );
}

/* The board's pick button ("Français ⌄"): sized to its value, opening a menu of choices. */
export function Pick<T extends string>({ label, value, options, onChange, width = 280 }: {
  label: string; value: T; options: { value: T; label: string }[]; onChange: (v: T) => void; width?: number;
}) {
  const cur = options.find((o) => o.value === value);
  return (
    <MenuButton
      width={width}
      button={({ ref, open, toggle }) => (
        <button ref={ref} type="button" class="me-pick" aria-haspopup="listbox" aria-expanded={open ? 'true' : 'false'} aria-label={`${label}: ${cur?.label ?? value}`} onClick={toggle}>
          <span>{cur?.label ?? value}</span>
          <Icon name="chevronDown" size={16} stroke={2.2} />
        </button>
      )}
    >
      {(close) => (
        <div class="me-pick-menu">
          {options.map((o) => <MenuItem key={o.value} label={o.label} pressed={o.value === value} onSelect={() => { close(); if (o.value !== value) onChange(o.value); }} />)}
        </div>
      )}
    </MenuButton>
  );
}

/* The board's grip: six dots. */
export function GripIcon() {
  return (
    <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true" fill="currentColor">
      <circle cx="9" cy="6" r="1.6" /><circle cx="15" cy="6" r="1.6" /><circle cx="9" cy="12" r="1.6" />
      <circle cx="15" cy="12" r="1.6" /><circle cx="9" cy="18" r="1.6" /><circle cx="15" cy="18" r="1.6" />
    </svg>
  );
}

/* The board's pin, filled. */
export function PinIcon({ size = 11 }: { size?: number }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" fill="currentColor">
      <path d="M15 3l6 6-3 1-4 4 1 5-2 2-4-4-5 5-1-1 5-5-4-4 2-2 5 1 4-4z" />
    </svg>
  );
}

/* ── Drag to reorder: grab the grip (pointer), or focus it and use ↑ / ↓ / Home / End ── */

export interface SortDrag { id: string; from: number; to: number; dy: number; top: number; height: number }

export function useSortable({ ids, listRef, itemSelector, onMove, name }: {
  ids: string[];
  listRef: RefObject<HTMLElement>;
  itemSelector: string;
  onMove: (from: number, to: number) => void;
  name: (id: string) => string;
}) {
  const [drag, setDrag] = useState<SortDrag | null>(null);
  const [live, setLive] = useState('');
  const grips = useRef(new Map<string, HTMLElement>());
  const focusAfter = useRef<string | null>(null);
  const flip = useRef<Map<string, number> | null>(null);
  const info = useRef<{ pointerId: number; from: number; grab: number; tops: number[]; heights: number[]; lastY: number; raf: number; to: number; dy: number } | null>(null);
  const last = ids.length - 1;

  const items = () => Array.from(listRef.current?.querySelectorAll<HTMLElement>(itemSelector) ?? []);

  /* FLIP: remember where each item is so the reorder slides instead of jumping. */
  const snapshot = (dropped?: string) => {
    const map = new Map<string, number>();
    for (const el of items()) map.set(el.dataset.id ?? '', el.getBoundingClientRect().top);
    const ghost = listRef.current?.querySelector<HTMLElement>('.me-ghost');
    if (dropped && ghost) map.set(dropped, ghost.getBoundingClientRect().top);
    flip.current = map;
  };

  useLayoutEffect(() => {
    const before = flip.current;
    flip.current = null;
    if (before) {
      const moved: HTMLElement[] = [];
      for (const el of items()) {
        const old = before.get(el.dataset.id ?? '');
        if (old === undefined) continue;
        const delta = old - el.getBoundingClientRect().top;
        if (Math.abs(delta) < 1) continue;
        el.style.transition = 'none';
        el.style.transform = `translateY(${delta}px)`;
        moved.push(el);
      }
      if (moved.length) {
        void listRef.current?.offsetHeight;
        for (const el of moved) {
          el.style.transition = 'transform 220ms cubic-bezier(0.2, 0.8, 0.2, 1)';
          el.style.transform = '';
          const done = () => { el.style.transition = ''; el.removeEventListener('transitionend', done); };
          el.addEventListener('transitionend', done);
        }
      }
    }
    if (focusAfter.current) {
      grips.current.get(focusAfter.current)?.focus();
      focusAfter.current = null;
    }
  }, [ids.join('|')]);

  const commit = (from: number, to: number, pointer: boolean) => {
    const id = ids[from];
    snapshot(pointer ? id : undefined);
    focusAfter.current = id;
    setLive(t('{name} moved to position {pos} of {count}.', { name: name(id), pos: to + 1, count: ids.length }));
    onMove(from, to);
  };

  const update = (clientY: number) => {
    const d = info.current;
    const list = listRef.current;
    if (!d || !list) return;
    const top = clientY - list.getBoundingClientRect().top - d.grab;
    const minTop = d.tops[0];
    const maxTop = d.tops[last] + d.heights[last] - d.heights[d.from];
    const clamped = Math.max(minTop - 8, Math.min(maxTop + 8, top));
    const center = clamped + d.heights[d.from] / 2;
    let n = 0;
    for (let i = 0; i <= last; i++) {
      if (i !== d.from && d.tops[i] + d.heights[i] / 2 < center) n++;
    }
    d.to = n;
    d.dy = clamped - d.tops[d.from];
    setDrag({ id: ids[d.from], from: d.from, to: d.to, dy: d.dy, top: d.tops[d.from], height: d.heights[d.from] });
  };

  const autoScroll = () => {
    const d = info.current;
    if (!d) return;
    const edge = 72;
    const y = d.lastY;
    let speed = 0;
    if (y < edge) speed = -Math.ceil(((edge - y) / edge) * 14);
    else if (y > window.innerHeight - edge) speed = Math.ceil(((y - (window.innerHeight - edge)) / edge) * 14);
    if (speed) { window.scrollBy(0, speed); update(y); }
    d.raf = requestAnimationFrame(autoScroll);
  };

  const end = (ok: boolean) => {
    const d = info.current;
    if (!d) return;
    cancelAnimationFrame(d.raf);
    info.current = null;
    document.body.classList.remove('me-dragging');
    if (ok && d.to !== d.from) commit(d.from, d.to, true);
    setDrag(null);
  };

  const gripProps = (index: number) => ({
    ref: (el: HTMLElement | null) => { const id = ids[index]; if (el) grips.current.set(id, el); else grips.current.delete(id); },
    onPointerDown: (e: PointerEvent) => {
      if (e.button !== 0 || !listRef.current) return;
      (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
      const els = items();
      const listTop = listRef.current.getBoundingClientRect().top;
      const tops = els.map((el) => el.getBoundingClientRect().top - listTop);
      const heights = els.map((el) => el.offsetHeight);
      info.current = { pointerId: e.pointerId, from: index, grab: e.clientY - listTop - tops[index], tops, heights, lastY: e.clientY, raf: 0, to: index, dy: 0 };
      info.current.raf = requestAnimationFrame(autoScroll);
      document.body.classList.add('me-dragging');
      setDrag({ id: ids[index], from: index, to: index, dy: 0, top: tops[index], height: heights[index] });
    },
    onPointerMove: (e: PointerEvent) => {
      const d = info.current;
      if (!d || e.pointerId !== d.pointerId) return;
      d.lastY = e.clientY;
      update(e.clientY);
    },
    onPointerUp: () => end(true),
    onPointerCancel: () => end(false),
    onLostPointerCapture: () => end(true),
    onKeyDown: (e: KeyboardEvent) => {
      if (info.current) {
        if (e.key === 'Escape') { e.preventDefault(); end(false); }
        return;
      }
      let to = index;
      if (e.key === 'ArrowUp') to = index - 1;
      else if (e.key === 'ArrowDown') to = index + 1;
      else if (e.key === 'Home') to = 0;
      else if (e.key === 'End') to = last;
      else return;
      e.preventDefault();
      e.stopPropagation();
      if (to < 0 || to > last || to === index) {
        setLive(to < 0 ? t('{name} is already first.', { name: name(ids[index]) }) : t('{name} is already last.', { name: name(ids[index]) }));
        return;
      }
      commit(index, to, false);
    }
  });

  /* The accent insertion line sits in the gap above the item the dragged one will land in front of. */
  let lineY: number | null = null;
  const d = info.current;
  if (drag && d && drag.to !== drag.from) {
    const others = ids.map((_, i) => i).filter((i) => i !== drag.from);
    const before = others[drag.to];
    lineY = before !== undefined ? d.tops[before] - 3 : d.tops[last] + d.heights[last] + 3;
  }

  return { drag, live, lineY, gripProps };
}

export const moveItem = <T,>(list: T[], from: number, to: number): T[] => {
  const next = list.slice();
  const [it] = next.splice(from, 1);
  next.splice(to, 0, it);
  return next;
};
