import type { ComponentChildren } from 'preact';
import { t } from '../lib/i18n';
import { Icon } from '../ui/icons';

/* The players' round chrome buttons: Back at the top left, and the icon buttons along the controls. */

export function BackButton({ onClick, label }: { onClick: () => void; label?: string }) {
  return (
    <button type="button" class="btn player-btn is-soft" aria-label={label ?? t('Back')} onClick={onClick}>
      <Icon name="arrowLeft" size={22} />
    </button>
  );
}

export function PBtn({ label, icon, onClick, expanded, pressed, children, hidden, disabled, class: cls }: {
  label: string; icon?: Parameters<typeof Icon>[0]['name']; onClick: () => void; expanded?: boolean; pressed?: boolean; children?: ComponentChildren;
  hidden?: boolean; disabled?: boolean; class?: string;
}) {
  if (hidden) return null;
  return (
    <button
      type="button"
      class={'btn player-btn' + (cls ? ' ' + cls : '')}
      aria-label={label}
      aria-pressed={pressed === undefined ? undefined : pressed ? 'true' : 'false'}
      aria-expanded={expanded === undefined ? undefined : expanded ? 'true' : 'false'}
      onClick={onClick}
      disabled={disabled}
    >
      {icon && <Icon name={icon} size={22} />}
      {children}
    </button>
  );
}
