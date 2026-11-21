import type { ComponentChildren } from 'preact';
import { t } from '../../lib/i18n';
import { Icon } from '../../ui/icons';
import { MenuButton, MenuItem } from '../../ui/overlay';
import './library.css';

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

export function FiltersButton({ count, onClick }: { count: number; onClick: () => void }) {
  return (
    <button type="button" class="btn btn-secondary lib-filters" aria-haspopup="dialog" onClick={onClick}>
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
