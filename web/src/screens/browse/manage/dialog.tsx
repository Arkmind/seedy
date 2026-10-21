import type { ComponentChildren } from 'preact';
import { t } from '../../../lib/i18n';
import { Icon } from '../../../ui/icons';
import { Sheet } from '../../../ui/overlay';
import './manage.css';

/* The shell every item dialog shares (Edit metadata, Identify, Edit images, New playlist and Delete boards): a head
   with the poster or an icon, an overline, the title and a line under it; a scrolling body; a footer with a note or
   a switch on the left and the buttons on the right. */
export function ItemDialog({ onClose, width, id, poster, danger, overline, title, sub, children, foot, actions, class: cls }: {
  onClose: () => void;
  /* The board's dialog width; the dialog never grows past the window. */
  width: number;
  /* Prefix for the heading's id (names the dialog). */
  id: string;
  /* Poster art in the head; `null` keeps the empty slot, `undefined` leaves it out. */
  poster?: string | null;
  /* The red bin badge of the Delete board. */
  danger?: boolean;
  overline?: string;
  title: ComponentChildren;
  sub?: ComponentChildren;
  children: ComponentChildren;
  /* Left side of the footer. */
  foot?: ComponentChildren;
  /* Right side of the footer: ItemButton's. */
  actions?: ComponentChildren;
  class?: string;
}) {
  return (
    <Sheet open onClose={onClose} labelledBy={id + '-h'} class={'im' + (cls ? ' ' + cls : '')} scrimClass="im-scrim" style={{ maxWidth: width + 'px' }}>
      <div class="im-head">
        {poster !== undefined && <span aria-hidden="true" class="im-poster">{poster && <img src={poster} alt="" />}</span>}
        {danger && <span aria-hidden="true" class="im-danger"><Icon name="trash" size={24} /></span>}
        <div class="im-head-text">
          {overline && <span class="im-overline">{overline}</span>}
          <h2 id={id + '-h'} class="im-title">{title}</h2>
          {sub && <span class="im-sub">{sub}</span>}
        </div>
        <button type="button" class="im-close" aria-label={t('Close')} onClick={onClose}><Icon name="close" size={18} /></button>
      </div>
      <div class="im-body">{children}</div>
      {(foot || actions) && (
        <div class="im-foot">
          <div class="im-foot-left">{foot}</div>
          <div class="im-foot-actions">{actions}</div>
        </div>
      )}
    </Sheet>
  );
}

/* Footer buttons: Cancel (plain), the main action (light) or a destructive one (red). */
export function ItemButton({ kind = 'plain', icon, children, onClick, disabled, type = 'button' }: {
  kind?: 'plain' | 'primary' | 'danger';
  icon?: Parameters<typeof Icon>[0]['name'];
  children: ComponentChildren;
  onClick?: () => void;
  disabled?: boolean;
  type?: 'button' | 'submit';
}) {
  return (
    <button type={type} class={'im-btn is-' + kind} onClick={onClick} disabled={disabled}>
      {icon && <Icon name={icon} size={18} stroke={2.4} />}
      {children}
    </button>
  );
}

/* A labelled field: the label on the left of its row, an optional chip (Locked) on the right. */
export function ItemField({ label, htmlFor, chip, children, class: cls }: {
  label: ComponentChildren;
  htmlFor?: string;
  chip?: ComponentChildren;
  children: ComponentChildren;
  class?: string;
}) {
  return (
    <div class={'im-field' + (cls ? ' ' + cls : '')}>
      <span class="im-label">
        {htmlFor ? <label for={htmlFor}>{label}</label> : <span>{label}</span>}
        {chip}
      </span>
      {children}
    </div>
  );
}

/* The accent tick box with a name and a line under it ("Replace the existing artwork", "Also clear it in Seerr"). */
export function ItemCheck({ checked, onChange, label, help, disabled }: {
  checked: boolean; onChange: (v: boolean) => void; label: ComponentChildren; help?: ComponentChildren; disabled?: boolean;
}) {
  return (
    <button type="button" role="checkbox" aria-checked={checked ? 'true' : 'false'} class="im-check" disabled={disabled} onClick={() => onChange(!checked)}>
      <span aria-hidden="true" class="im-check-box"><Icon name="check" size={15} stroke={3.2} /></span>
      <span class="im-check-text">
        <span class="im-check-name">{label}</span>
        {help && <span class="im-check-help">{help}</span>}
      </span>
    </button>
  );
}
