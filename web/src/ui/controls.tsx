import type { ComponentChildren } from 'preact';
import { Icon } from './icons';
import { t } from '../lib/i18n';

export function Switch({ checked, onChange, label, disabled, describedBy, title }: {
  checked: boolean; onChange: (v: boolean) => void; label: string; disabled?: boolean; describedBy?: string; title?: string;
}) {
  return (
    <button
      type="button"
      role="switch"
      class="switch"
      aria-checked={checked ? 'true' : 'false'}
      aria-label={label}
      aria-describedby={describedBy}
      title={title}
      disabled={disabled}
      onClick={() => onChange(!checked)}
    />
  );
}

export interface SegOption<T extends string | number> { value: T; label: ComponentChildren; ariaLabel?: string }

export function Segmented<T extends string | number>({
  value, options, onChange, label, variant, role = 'group'
}: {
  value: T;
  options: SegOption<T>[];
  onChange: (v: T) => void;
  label: string;
  variant?: 'small' | 'fill' | 'icons';
  role?: 'group' | 'tablist';
}) {
  const cls = 'seg' + (variant ? ' is-' + variant : '');
  return (
    <div role={role} aria-label={label} class={cls}>
      {options.map((o) => {
        const on = o.value === value;
        return (
          <button
            key={String(o.value)}
            type="button"
            class="seg-btn"
            role={role === 'tablist' ? 'tab' : undefined}
            aria-selected={role === 'tablist' ? (on ? 'true' : 'false') : undefined}
            aria-pressed={role === 'group' ? (on ? 'true' : 'false') : undefined}
            aria-label={o.ariaLabel}
            onClick={() => onChange(o.value)}
          >
            {o.label}
          </button>
        );
      })}
    </div>
  );
}

export function SelectField<T extends string | number>({
  id, value, options, onChange, minWidth, label
}: {
  id?: string;
  value: T;
  options: { value: T; label: string }[];
  onChange: (v: T) => void;
  minWidth?: number;
  label?: string;
}) {
  return (
    <span class="select-wrap">
      <select
        id={id}
        class="select"
        aria-label={label}
        style={minWidth ? { minWidth: minWidth + 'px' } : undefined}
        value={String(value)}
        onChange={(e) => {
          const raw = (e.currentTarget as HTMLSelectElement).value;
          const opt = options.find((o) => String(o.value) === raw);
          if (opt) onChange(opt.value);
        }}
      >
        {options.map((o) => <option key={String(o.value)} value={String(o.value)}>{o.label}</option>)}
      </select>
      <Icon name="chevronDown" size={16} />
    </span>
  );
}

export function NumField({
  value, onChange, min, max, label, prefix, suffix, ariaLabel, width
}: {
  value: number;
  onChange: (v: number) => void;
  min: number;
  max: number;
  label?: string;
  prefix?: string;
  suffix?: string;
  ariaLabel: string;
  width?: number;
}) {
  return (
    <label class="num-field">
      {prefix ?? label}
      <input
        type="number"
        inputMode="numeric"
        value={value}
        min={min}
        max={max}
        aria-label={ariaLabel}
        style={width ? { width: width + 'px' } : undefined}
        onChange={(e) => {
          const n = Number((e.currentTarget as HTMLInputElement).value);
          if (!Number.isFinite(n)) return;
          const v = Math.max(min, Math.min(max, Math.round(n)));
          (e.currentTarget as HTMLInputElement).value = String(v);
          onChange(v);
        }}
      />
      {suffix}
    </label>
  );
}

export function Stepper({ label, valueLabel, onDec, onInc, decLabel, incLabel, decDisabled, incDisabled, class: cls }: {
  label: string; valueLabel: string; onDec: () => void; onInc: () => void; decLabel: string; incLabel: string; decDisabled?: boolean; incDisabled?: boolean; class?: string;
}) {
  return (
    <div role="group" aria-label={label} class={'stepper' + (cls ? ' ' + cls : '')}>
      <button type="button" class="btn icon-btn" aria-label={decLabel} onClick={onDec} disabled={decDisabled}><Icon name="minus" size={14} /></button>
      <span class="stepper-value">{valueLabel}</span>
      <button type="button" class="btn icon-btn" aria-label={incLabel} onClick={onInc} disabled={incDisabled}><Icon name="plus" size={14} /></button>
    </div>
  );
}

/* Admin and settings building blocks: a 16px panel of label/control rows split by hairlines. */

export function Panel({ title, text, id, children, gap, class: cls }: { title?: string; text?: ComponentChildren; id?: string; children: ComponentChildren; gap?: number; class?: string }) {
  const hid = id ? id + '-h' : undefined;
  return (
    <section aria-labelledby={hid} class={'panel' + (gap !== undefined ? ' is-gap' : '') + (cls ? ' ' + cls : '')} style={gap !== undefined ? { gap: gap + 'px' } : undefined}>
      {(title || text) && (
        <div class={'panel-head' + (text ? '' : ' is-title-only')}>
          {title && <h2 id={hid} class="panel-title">{title}</h2>}
          {text && <p class="panel-text">{text}</p>}
        </div>
      )}
      {children}
    </section>
  );
}

export function SettingRow({ name, help, htmlFor, children, last, stack }: {
  name: ComponentChildren; help?: ComponentChildren; htmlFor?: string; children?: ComponentChildren; last?: boolean; stack?: boolean;
}) {
  const Label = htmlFor ? 'label' : 'span';
  return (
    <div class={'setting' + (last ? ' is-last' : '') + (stack ? ' is-stack' : '')}>
      <Label {...(htmlFor ? { for: htmlFor } : {})} class="setting-label">
        <span class="setting-name">{name}</span>
        {help && <span class="setting-help">{help}</span>}
      </Label>
      {children}
    </div>
  );
}

export function RadioCards<T extends string>({ name, value, options, onChange, label }: {
  name: string; value: T; options: { value: T; title: string; text: string }[]; onChange: (v: T) => void; label: string;
}) {
  return (
    <div role="radiogroup" aria-label={label} class="radio-cards">
      {options.map((o) => (
        <label key={o.value} class={'radio-card' + (o.value === value ? ' is-on' : '')}>
          <input type="radio" name={name} checked={o.value === value} onChange={() => onChange(o.value)} />
          <span class="radio-card-body">
            <span class="radio-card-title">{o.title}</span>
            <span class="radio-card-text">{o.text}</span>
          </span>
        </label>
      ))}
    </div>
  );
}

export function SavedNote({ state }: { state: 'saved' | 'saving' | 'error'; }) {
  if (state === 'error') return <span class="saved-note is-error"><Icon name="alert" size={16} />{t("Couldn't save. Check the server log.")}</span>;
  if (state === 'saving') return <span class="saved-note"><span class="dot is-pending" />{t('Saving…')}</span>;
  return <span class="saved-note"><Icon name="check" size={16} stroke={2.6} color="var(--seedy-ok)" />{t('All changes saved')}</span>;
}

