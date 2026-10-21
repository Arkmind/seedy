import type { ComponentChildren, RefObject } from 'preact';
import { createPortal } from 'preact/compat';
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { createStore, useStore } from '../lib/store';
import { Icon, type IconName } from './icons';
import { t } from '../lib/i18n';

/* ── Popover anchored to a button ── */

export function Popover({
  anchor, open, onClose, children, align = 'end', placement = 'below', width
}: {
  anchor: RefObject<HTMLElement>;
  open: boolean;
  onClose: () => void;
  children: ComponentChildren;
  align?: 'start' | 'end';
  placement?: 'below' | 'above';
  width?: number;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ top?: number; bottom?: number; left?: number; right?: number; maxHeight?: number; overflowY?: 'auto' }>({});

  useLayoutEffect(() => {
    if (!open || !anchor.current) return;
    const place = () => {
      const a = anchor.current;
      if (!a) return;
      const r = a.getBoundingClientRect();
      // The window without its scrollbars: what fixed positions count from.
      const vw = document.documentElement.clientWidth;
      const vh = document.documentElement.clientHeight;
      // Its whole height, even while capped below.
      const full = ref.current?.scrollHeight ?? 0;
      const h = Math.min(full, vh - 16);
      const next: typeof pos = {};
      // Under its button, moved up as far as it takes for its bottom to stay in the window.
      if (placement === 'below') next.top = Math.max(8, Math.min(r.bottom + 8, vh - 8 - h));
      else next.bottom = vh - r.top + 8;
      // Taller than the window: it scrolls.
      if (full > vh - 16) Object.assign(next, { maxHeight: vh - 16, overflowY: 'auto' });
      if (align === 'end') next.right = Math.max(8, vw - r.right);
      else next.left = Math.max(8, r.left);
      setPos(next);
    };
    place();
    // Its height is only known once it's drawn, and changes with what it shows.
    const resized = typeof ResizeObserver === 'function' && ref.current ? new ResizeObserver(place) : null;
    if (ref.current) resized?.observe(ref.current);
    window.addEventListener('resize', place);
    window.addEventListener('scroll', place, true);
    return () => {
      resized?.disconnect();
      window.removeEventListener('resize', place);
      window.removeEventListener('scroll', place, true);
    };
  }, [open, anchor, align, placement]);

  useEffect(() => {
    if (!open) return;
    const onDown = (e: PointerEvent) => {
      const t = e.target as Node;
      if (ref.current?.contains(t) || anchor.current?.contains(t)) return;
      onClose();
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation();
        onClose();
        anchor.current?.focus();
      }
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        const items = Array.from(ref.current?.querySelectorAll<HTMLElement>('.menu-item:not([disabled])') ?? []);
        if (!items.length) return;
        e.preventDefault();
        const i = items.indexOf(document.activeElement as HTMLElement);
        const n = e.key === 'ArrowDown' ? (i + 1) % items.length : (i - 1 + items.length) % items.length;
        items[n].focus();
      }
    };
    document.addEventListener('pointerdown', onDown, true);
    document.addEventListener('keydown', onKey, true);
    requestAnimationFrame(() => {
      const first = ref.current?.querySelector<HTMLElement>('[aria-pressed="true"].menu-item, .menu-item:not([disabled]), input, button');
      first?.focus({ preventScroll: true });
    });
    return () => {
      document.removeEventListener('pointerdown', onDown, true);
      document.removeEventListener('keydown', onKey, true);
    };
  }, [open, onClose, anchor]);

  if (!open) return null;
  return createPortal(
    <div ref={ref} class="popover" style={{ ...pos, width: width ? width + 'px' : undefined }}>
      {children}
    </div>,
    document.body
  );
}

export function MenuItem({ icon, label, sub, onSelect, pressed, danger, href, disabled, trailing }: {
  icon?: IconName; label: ComponentChildren; sub?: ComponentChildren; onSelect?: () => void; pressed?: boolean; danger?: boolean;
  href?: string; disabled?: boolean; trailing?: ComponentChildren;
}) {
  const body = (
    <>
      <span class="lead">
        {icon && <Icon name={icon} size={18} />}
        {sub ? <span class="two"><span>{label}</span><span class="sub">{sub}</span></span> : <span>{label}</span>}
      </span>
      {trailing}
      {pressed && <Icon name="check" size={20} stroke={2.6} class="tick" />}
    </>
  );
  const cls = 'menu-item' + (danger ? ' is-danger' : '');
  if (href) return <a class={cls} href={href} target="_blank" rel="noreferrer">{body}</a>;
  return (
    <button type="button" class={cls} aria-pressed={pressed === undefined ? undefined : pressed ? 'true' : 'false'} disabled={disabled} onClick={onSelect}>
      {body}
    </button>
  );
}

/* A button that owns a menu. */
export function MenuButton({ button, children, align, width, placement }: {
  button: (props: { ref: RefObject<HTMLButtonElement>; open: boolean; toggle: () => void }) => ComponentChildren;
  children: (close: () => void) => ComponentChildren;
  align?: 'start' | 'end';
  width?: number;
  placement?: 'below' | 'above';
}) {
  const ref = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  const close = () => setOpen(false);
  return (
    <>
      {button({ ref, open, toggle: () => setOpen((o) => !o) })}
      <Popover anchor={ref as RefObject<HTMLElement>} open={open} onClose={close} align={align} width={width} placement={placement}>
        <div class="menu" role="menu">{children(close)}</div>
      </Popover>
    </>
  );
}

/* ── Modal sheet ── */

export function Sheet({ open, onClose, children, wide, label, labelledBy, class: cls, scrimClass, style }: {
  open: boolean; onClose: () => void; children: ComponentChildren; wide?: boolean; label?: string;
  style?: Record<string, string>;
  /* A heading inside the dialog to name it by, instead of `label`. */
  labelledBy?: string;
  /* Replaces the sheet's own look (the request dialog has its own). */
  class?: string;
  scrimClass?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);
  // Callers pass inline closures; keep the latest without re-running the open/close effect (which moves focus).
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    if (!open) return;
    const prev = document.activeElement as HTMLElement | null;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { e.stopPropagation(); closeRef.current(); return; }
      if (e.key !== 'Tab' || !ref.current) return;
      // Keep Tab inside the dialog.
      const items = Array.from(ref.current.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'))
        .filter((el) => el.offsetParent !== null);
      if (!items.length) return;
      const first = items[0];
      const last = items[items.length - 1];
      if (e.shiftKey && document.activeElement === first) { e.preventDefault(); last.focus(); }
      else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    };
    document.addEventListener('keydown', onKey, true);
    // First focus: a field, else the primary action, else any control (in that order of preference).
    requestAnimationFrame(() => {
      for (const sel of ['input:not([type=hidden]), textarea, select', 'button.btn-primary', 'button']) {
        const el = ref.current?.querySelector<HTMLElement>(sel);
        if (el) { el.focus(); break; }
      }
    });
    return () => {
      document.removeEventListener('keydown', onKey, true);
      prev?.focus?.();
    };
  }, [open]);
  if (!open) return null;
  return createPortal(
    <div class={'scrim' + (scrimClass ? ' ' + scrimClass : '')} onPointerDown={(e) => { if (e.target === e.currentTarget) closeRef.current(); }}>
      <div ref={ref} class={cls ?? 'sheet' + (wide ? ' is-wide' : '')} role="dialog" aria-modal="true" aria-label={labelledBy ? undefined : label} aria-labelledby={labelledBy} style={style}>
        {children}
      </div>
    </div>,
    document.body
  );
}

interface ConfirmState { title: string; text: string; action: string; danger?: boolean; resolve: (ok: boolean) => void }
const confirmStore = createStore<ConfirmState | null>(null);

export function confirm(opts: { title: string; text: string; action: string; danger?: boolean }): Promise<boolean> {
  /* Only one question at a time: a newer one answers the older with "no", so whoever awaited it isn't left hanging. */
  confirmStore.get()?.resolve(false);
  return new Promise((resolve) => confirmStore.set({ ...opts, resolve }));
}

export function ConfirmHost() {
  const c = useStore(confirmStore);
  const done = (ok: boolean) => { c?.resolve(ok); confirmStore.set(null); };
  return (
    <Sheet open={!!c} onClose={() => done(false)} label={c?.title ?? ''}>
      {c && (
        <>
          <div class="sheet-head"><h2 class="sheet-title">{c.title}</h2></div>
          <p class="sheet-text">{c.text}</p>
          <div class="sheet-actions">
            <button type="button" class="btn btn-ghost is-light" onClick={() => done(false)}>{t('Cancel')}</button>
            <button type="button" class={'btn ' + (c.danger ? 'btn-danger' : 'btn-primary is-sm')} onClick={() => done(true)}>{c.action}</button>
          </div>
        </>
      )}
    </Sheet>
  );
}

/* ── Toasts ── */

interface ToastItem { id: number; text: string; tone?: 'ok' | 'error' | 'pending' }
const toastStore = createStore<ToastItem[]>([]);
let toastId = 0;

export function toast(text: string, tone: ToastItem['tone'] = 'ok', ms = 3600) {
  const id = ++toastId;
  toastStore.set((list) => [...list.slice(-2), { id, text, tone }]);
  window.setTimeout(() => toastStore.set((list) => list.filter((t) => t.id !== id)), ms);
}

export function ToastHost() {
  const list = useStore(toastStore);
  return (
    <div class="toasts" role="status" aria-live="polite">
      {list.map((item) => (
        <div key={item.id} class="toast">
          {item.tone === 'error' ? <span class="dot is-danger" /> : item.tone === 'pending' ? <span class="dot is-pending" /> : <Icon name="check" size={16} stroke={2.6} color="var(--seedy-ok)" />}
          {item.text}
        </div>
      ))}
    </div>
  );
}
