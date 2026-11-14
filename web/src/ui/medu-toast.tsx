import { createStore, useStore } from '../lib/store';
import { t } from '../lib/i18n';
import { Icon, type IconName } from './icons';
import { Medu, type MeduMood } from './medu';

// Medu's toasts, bottom right: a small one for something finishing in the background (Backup finished, on the new
// server Overview board) and a card with actions for good news (Request ready toast board). One shows at a time.

export interface MeduToastAction { label: string; icon?: IconName; primary?: boolean; onClick: () => void }
export interface MeduToast {
  mood?: MeduMood;
  /** The small accent line above the title ("Ready to watch"); only the card has one. */
  eyebrow?: string;
  title: string;
  text?: string;
  actions?: MeduToastAction[];
  /** The card (Medu at 96 px, a close button) rather than the compact toast. */
  card?: boolean;
  /** How long it stays; 0 keeps it until it's closed. */
  ms?: number;
}

const store = createStore<(MeduToast & { id: number }) | null>(null);
let seq = 0;
let timer = 0;

export function meduToast(toast: MeduToast) {
  const id = ++seq;
  store.set({ ...toast, id });
  window.clearTimeout(timer);
  const ms = toast.ms ?? (toast.card ? 0 : 8000);
  if (ms) timer = window.setTimeout(() => { if (store.get()?.id === id) store.set(null); }, ms);
}

export const closeMeduToast = () => store.set(null);

export function MeduToastHost() {
  const toast = useStore(store);
  if (!toast) return null;
  const run = (a: MeduToastAction) => () => { closeMeduToast(); a.onClick(); };
  if (!toast.card) {
    return (
      <div key={toast.id} class="medu-toast" role="status">
        <Medu mood={toast.mood ?? 'yay'} size={48} />
        <div class="medu-toast-body">
          <span class="medu-toast-title">{toast.title}</span>
          {toast.text && <span class="medu-toast-text">{toast.text}</span>}
        </div>
        {toast.actions?.map((a) => <button key={a.label} type="button" class="medu-toast-btn" onClick={run(a)}>{a.label}</button>)}
      </div>
    );
  }
  return (
    <div key={toast.id} class="medu-toast is-card" role="status">
      <span class="medu-toast-medu"><Medu mood={toast.mood ?? 'yay'} size={96} /></span>
      <div class="medu-toast-body">
        {toast.eyebrow && <span class="medu-toast-eyebrow">{toast.eyebrow}</span>}
        <span class="medu-toast-title">{toast.title}</span>
        {toast.text && <span class="medu-toast-text">{toast.text}</span>}
        {!!toast.actions?.length && (
          <div class="medu-toast-actions">
            {toast.actions.map((a) => (
              <button key={a.label} type="button" class={'medu-toast-btn' + (a.primary ? ' is-primary' : '')} onClick={run(a)}>
                {a.icon && <Icon name={a.icon} size={15} />}{a.label}
              </button>
            ))}
          </div>
        )}
      </div>
      <button type="button" class="medu-toast-close" aria-label={t('Dismiss')} onClick={closeMeduToast}><Icon name="close" size={16} /></button>
    </div>
  );
}
