import type { ComponentChildren, RefObject } from 'preact';
import { useEffect, useId, useRef } from 'preact/hooks';
import { t } from '../../lib/i18n';
import { Icon } from '../../ui/icons';
import { MenuButton, MenuItem, Popover } from '../../ui/overlay';
import './library.css';
import './filter-panel.css';

/* The Sort menu, Filters button, filter chips and filter-sheet options shared by libraries and Viewing history. */

export type SortOrder = 'asc' | 'desc';

/* A sort: its English label (translated when shown), the order it starts in, and how each direction reads. */
export interface SortOption<Id extends string = string> {
  id: Id;
  label: string;
  order: SortOrder;
  asc?: string;
  desc?: string;
  /* No direction to pick (Random). */
  fixed?: boolean;
}

export function SortButton<Id extends string>({ sorts, sort, order, onChange }: {
  sorts: readonly SortOption<Id>[]; sort: Id; order: SortOrder; onChange: (s: Id, o: SortOrder) => void;
}) {
  const cur = sorts.find((s) => s.id === sort) ?? sorts[0];
  return (
    <MenuButton
      width={240}
      button={({ ref, open, toggle }) => (
        <button ref={ref} type="button" class="btn btn-secondary lib-sort" aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} aria-label={order === 'asc' ? t('Sort by {name}, ascending', { name: t(cur.label) }) : t('Sort by {name}, descending', { name: t(cur.label) })} onClick={toggle}>
          <Icon name="sort" size={18} />
          <span class="lib-sort-k">{t('Sort')}</span> {t(cur.label)}
          <Icon name="chevronDown" size={16} stroke={2.2} />
        </button>
      )}
    >
      {(close) => (
        <>
          <span class="menu-label">{t('Sort by')}</span>
          {sorts.map((s) => (
            <MenuItem key={s.id} label={t(s.label)} pressed={s.id === sort} onSelect={() => { close(); onChange(s.id, s.id === sort ? order : s.order); }} />
          ))}
          {!cur.fixed && (
            <>
              <div class="menu-sep" />
              <MenuItem icon="sortEpisodes" label={t('Ascending')} sub={t(cur.asc ?? 'Oldest or lowest first')} pressed={order === 'asc'} onSelect={() => { close(); onChange(cur.id, 'asc'); }} />
              <MenuItem icon="sortEpisodes" label={t('Descending')} sub={t(cur.desc ?? 'Newest or highest first')} pressed={order === 'desc'} onSelect={() => { close(); onChange(cur.id, 'desc'); }} />
            </>
          )}
        </>
      )}
    </MenuButton>
  );
}

export function FiltersButton({ count, onClick, buttonRef, open }: { count: number; onClick: () => void; buttonRef?: RefObject<HTMLButtonElement>; open?: boolean }) {
  return (
    <button ref={buttonRef} type="button" class="btn btn-secondary lib-filters" aria-haspopup="dialog" aria-expanded={open === undefined ? undefined : open ? 'true' : 'false'} onClick={onClick}>
      <Icon name="filters" size={18} />
      {t('Filters')}
      {count > 0 && <span class="accent-badge" aria-label={t('{n} active', { n: count })}>{count}</span>}
    </button>
  );
}

export interface FilterChip { key: string; label: string; remove: () => void }

/* The active filters under the toolbar, each with its ×, then Clear all. */
export function FilterChips({ chips, onClear }: { chips: FilterChip[]; onClear: () => void }) {
  if (!chips.length) return null;
  return (
    <div class="lib-chips" aria-label={t('Active filters')}>
      {chips.map((c) => (
        <span key={c.key} class="fchip">
          {c.label}
          <button type="button" aria-label={t('Remove {name} filter', { name: c.label })} onClick={c.remove}><Icon name="close" size={14} stroke={2.6} /></button>
        </span>
      ))}
      <button type="button" class="btn btn-ghost is-sm" onClick={onClear}>{t('Clear all')}</button>
    </div>
  );
}

export function FilterGroup({ label, children }: { label: string; children: ComponentChildren }) {
  return (
    <fieldset class="fgroup">
      <legend class="menu-label">{label}</legend>
      <div class="fopts">{children}</div>
    </fieldset>
  );
}

export function FilterOption({ on, label, onClick }: { on: boolean; label: string; onClick: () => void }) {
  return (
    <button type="button" class="fopt" aria-pressed={on ? 'true' : 'false'} onClick={onClick}>
      {on && <Icon name="check" size={14} stroke={2.8} />}
      {label}
    </button>
  );
}

/* ───────── The Filters panel under its button (RequestsResults, LibraryFilters boards) ───────── */

export function FilterPanel({ anchor, open, onClose, onReset, canReset, apply, onApply, children }: {
  anchor: RefObject<HTMLElement>; open: boolean; onClose: () => void;
  onReset: () => void; canReset: boolean;
  /* "Show 214 titles". */
  apply: string; onApply: () => void;
  children: ComponentChildren;
}) {
  const ref = useRef<HTMLDivElement>(null);
  // The panel takes focus, not its first button (the Popover's choice for menus); Tab goes on from there.
  useEffect(() => {
    if (!open) return;
    const id = requestAnimationFrame(() => requestAnimationFrame(() => ref.current?.focus({ preventScroll: true })));
    return () => cancelAnimationFrame(id);
  }, [open]);
  return (
    <Popover anchor={anchor} open={open} onClose={onClose} align="end" stay>
      <div ref={ref} class="fpanel" role="dialog" aria-label={t('Filters')} tabIndex={-1}>
        {children}
        <div class="fpanel-foot">
          <button type="button" class="btn fpanel-reset" disabled={!canReset} onClick={onReset}>{t('Reset')}</button>
          <button type="button" class="btn fpanel-apply" onClick={onApply}>{apply}</button>
        </div>
      </div>
    </Popover>
  );
}

/* A section: its title, what's picked on the right ("1 picked", "2015 – 2026"), then its controls. */
export function PanelSection({ title, value, children }: { title: string; value?: ComponentChildren; children: ComponentChildren }) {
  const id = useId();
  return (
    <section class="fsec" aria-labelledby={id}>
      <div class="fsec-head">
        <h3 id={id} class="fsec-title">{title}</h3>
        {value !== undefined && <span class="fsec-value">{value}</span>}
      </div>
      {children}
    </section>
  );
}

export function PanelSegment<Id extends string>({ label, options, value, onChange }: {
  label: string; options: readonly { id: Id; label: string }[]; value: Id; onChange: (id: Id) => void;
}) {
  return (
    <div role="radiogroup" aria-label={label} class="fseg">
      {options.map((o) => (
        <button key={o.id} type="button" role="radio" aria-checked={o.id === value ? 'true' : 'false'} class="fseg-btn" onClick={() => onChange(o.id)}>
          {o.label}
        </button>
      ))}
    </div>
  );
}

/* A check row: box, an optional status dot, the label and something small on the right. */
export function PanelCheck({ on, label, dot, sub, onChange }: { on: boolean; label: string; dot?: string; sub?: ComponentChildren; onChange: (on: boolean) => void }) {
  return (
    <label class="fcheck">
      <input type="checkbox" class="fcheck-input" checked={on} onChange={(e) => onChange((e.currentTarget as HTMLInputElement).checked)} />
      <span class="fcheck-box" aria-hidden="true"><Icon name="check" size={14} stroke={3} /></span>
      {dot && <span class="fcheck-dot" aria-hidden="true" style={{ background: dot }} />}
      <span class="fcheck-label">{label}</span>
      {sub !== undefined && <span class="fcheck-sub">{sub}</span>}
    </label>
  );
}

export function PanelPick({ on, label, onClick }: { on: boolean; label: string; onClick: () => void }) {
  return (
    <button type="button" class="fpick" aria-pressed={on ? 'true' : 'false'} onClick={onClick}>
      {on && <Icon name="check" size={14} stroke={2.8} />}
      {label}
    </button>
  );
}

/* One thumb (a lowest value, the rest of the track picked) or two (a range). Thumbs at the ends mean no limit. */
export function RangeSlider({ min, max, step, value, onChange, labels }: {
  min: number; max: number; step: number;
  value: [number, number] | number;
  onChange: (v: [number, number] | number) => void;
  /* The thumbs' spoken names: "From", "To" or "Minimum rating". */
  labels: [string, string?];
  }) {
  const pct = (v: number) => ((v - min) / (max - min)) * 100;
  const range = Array.isArray(value);
  const lo = range ? value[0] : value;
  const hi = range ? value[1] : max;
  const input = (v: number, label: string, set: (n: number) => void) => (
    <input type="range" min={min} max={max} step={step} value={v} aria-label={label} onInput={(e) => set(Number((e.currentTarget as HTMLInputElement).value))} />
  );
  return (
    <div class="frange">
      <span class="frange-track" />
      <span class="frange-fill" style={{ left: pct(lo) + '%', width: pct(hi) - pct(lo) + '%' }} />
      {range
        ? <>
            {input(lo, labels[0], (n) => onChange([Math.min(n, hi), hi]))}
            {input(hi, labels[1] ?? labels[0], (n) => onChange([lo, Math.max(n, lo)]))}
          </>
        : input(lo, labels[0], (n) => onChange(n))}
    </div>
  );
}

/* A row that opens in place, saying what's picked while closed. */
export function PanelDrill({ title, sub, set, open, onToggle, children }: {
  title: string; sub: string; set?: boolean; open: boolean; onToggle: () => void; children: ComponentChildren;
}) {
  const id = useId();
  return (
    <div class="fdrill">
      <button type="button" class="fdrill-btn" aria-expanded={open ? 'true' : 'false'} aria-controls={id} onClick={onToggle}>
        <span class="fdrill-text">
          <span class="fdrill-title">{title}</span>
          <span class={'fdrill-sub' + (set ? ' is-set' : '')}>{sub}</span>
        </span>
        <Icon name="chevronRight" size={18} stroke={2.2} />
      </button>
      {open && <div id={id} class="fdrill-body">{children}</div>}
    </div>
  );
}

/* The search inside a row that opens in place; it takes focus as the row opens. */
export function PanelSearch({ value, onInput, placeholder }: { value: string; onInput: (v: string) => void; placeholder: string }) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => { ref.current?.focus({ preventScroll: true }); }, []);
  return (
    <label class="fsearch">
      <Icon name="search" size={16} stroke={2.2} />
      <input ref={ref} type="search" value={value} placeholder={placeholder} aria-label={placeholder} onInput={(e) => onInput((e.currentTarget as HTMLInputElement).value)} />
    </label>
  );
}
