import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { userImageUrl } from '../../api/images';
import { t } from '../../lib/i18n';
import type { Store } from '../../lib/store';
import { SettingRow } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { Sheet, toast, toastError } from '../../ui/overlay';
import { flushConfig } from './config';

/* Pieces the Seedy admin pages share: the profile tables of Requests, Music & Books and Play log, and the
   address, key and test rows of the apps Seedy connects to (Seerr, Lidarr, the book apps). */

/* Letters without a picture take turns down these four colours. */
export const PROFILE_COLOURS = ['#f0f0f0', '#9fd3ff', '#b8f59f', '#ffc98a'];

export function ProfileAvatar({ userId, name, imageTag, index }: { userId: string; name: string; imageTag?: string; index: number }) {
  const img = imageTag ? userImageUrl({ Id: userId, PrimaryImageTag: imageTag }, 72) : undefined;
  return (
    <span class="avatar" aria-hidden="true" style={img ? undefined : { background: PROFILE_COLOURS[index % PROFILE_COLOURS.length] }}>
      {img ? <img src={img} alt="" /> : name.trim().charAt(0).toUpperCase()}
    </span>
  );
}

/* "Administrator", "Maturity limit: 12" or "User", under a profile's name. */
export function roleOf(p: { isAdmin?: boolean; maxParentalRating?: string | null }) {
  if (p.isAdmin) return t('Administrator');
  if (p.maxParentalRating) return t('Maturity limit: {rating}', { rating: p.maxParentalRating });
  return t('User');
}

export function TableError({ text, error, onRetry }: { text: string; error: string; onRetry?: () => void }) {
  return (
    <div class="admin-inline-error" role="alert">
      <span class="dot is-danger" />
      {text} {error}
      {onRetry && <button type="button" class="btn btn-secondary is-sm" onClick={onRetry}>{t('Try again')}</button>}
    </div>
  );
}

export function TableSkeleton({ rows = 4, busy }: { rows?: number; busy?: boolean }) {
  return (
    <div class="table-skel" aria-busy={busy ? 'true' : undefined}>
      {Array.from({ length: rows }, (_, i) => <span key={i} class="skel" />)}
    </div>
  );
}

/* Changes one profile's row in place, before the server answers (and back again if it refuses). */
export function patchRow<S extends { data?: { userId: string }[] }>(store: Store<S>, userId: string, patch: Partial<NonNullable<S['data']>[number]>) {
  store.set((s) => ({ ...s, data: s.data?.map((x) => (x.userId === userId ? { ...x, ...patch } : x)) }));
}

/* ── App connections ── */

/* "seerr.local:5055/" → "http://seerr.local:5055". */
export function normaliseUrl(raw: string): string {
  let v = raw.trim().replace(/\/+$/, '');
  if (v && !/^https?:\/\//i.test(v)) v = 'http://' + v;
  return v;
}

/* An app's address: kept as typed until Enter or leaving the field, then tidied and handed on if it changed. */
export function UrlInput({ id, value, placeholder, label, onCommit }: {
  id: string; value: string; placeholder: string; label?: string; onCommit: (v: string) => void | Promise<void>;
}) {
  const [url, setUrl] = useState(value);
  useEffect(() => setUrl(value), [value]);
  const commit = () => {
    const v = normaliseUrl(url);
    if (v !== url) setUrl(v);
    if (v !== value) void onCommit(v);
  };
  return (
    <input
      id={id}
      class="field is-340"
      type="url"
      inputMode="url"
      value={url}
      placeholder={placeholder}
      autoComplete="off"
      spellcheck={false}
      aria-label={label}
      onInput={(e) => setUrl((e.currentTarget as HTMLInputElement).value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === 'Enter') { e.preventDefault(); commit(); }
        if (e.key === 'Escape') setUrl(value);
      }}
    />
  );
}

/* "Saved · ends in 4f2a" and the button that opens the key sheet (passed as children). */
export function ApiKeyRow({ name, help, hint, onOpen, children }: { name: string; help: string; hint?: string; onOpen: () => void; children?: ComponentChildren }) {
  return (
    <SettingRow name={name} help={help}>
      <div class="setting-controls is-10">
        <span class="key-saved">
          <Icon name="lock" size={16} />
          {hint ? t('Saved · ends in {hint}', { hint }) : t('No key saved')}
        </span>
        <button type="button" class="btn btn-secondary" onClick={onOpen}>{hint ? t('Replace key') : t('Add key')}</button>
      </div>
      {children}
    </SettingRow>
  );
}

/* Asks for an API key; `save` sends it (after any pending config edits), then the sheet closes and `onSaved` runs. */
export function ApiKeySheet({ open, onClose, title, text, label, formClass, save, onSaved }: {
  open: boolean; onClose: () => void; title: string; text: string; label: string; formClass?: string;
  save: (key: string) => Promise<void>; onSaved: () => void;
}) {
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (open) setKey(''); }, [open]);
  const submit = async (e: Event) => {
    e.preventDefault();
    const v = key.trim();
    if (!v) return;
    setBusy(true);
    try {
      await flushConfig().catch(() => { /* the key is saved on its own */ });
      await save(v);
      toast(t('API key saved'));
      onClose();
      onSaved();
    } catch (err) {
      toastError(t('Couldn’t save the key.'), err);
    } finally {
      setBusy(false);
    }
  };
  return (
    <Sheet open={open} onClose={onClose} label={title}>
      <form class={formClass} style={{ display: 'contents' }} onSubmit={submit}>
        <div class="sheet-head"><h2 class="sheet-title">{title}</h2></div>
        <p class="sheet-text">{text}</p>
        <input class="field" type="password" value={key} aria-label={label} placeholder={label} autoComplete="off" spellcheck={false} onInput={(e) => setKey((e.currentTarget as HTMLInputElement).value)} />
        <div class="sheet-actions">
          <button type="button" class="btn btn-ghost is-light" onClick={onClose}>{t('Cancel')}</button>
          <button type="submit" class="btn btn-primary is-sm" disabled={!key.trim() || busy}>{busy ? t('Saving…') : t('Save key')}</button>
        </div>
      </form>
    </Sheet>
  );
}

/* "Connected. Seerr can see Radarr and Sonarr.", with the first sentence in bold. */
export function ConnLine({ dot, lead, rest }: { dot: string; lead: string; rest: string }) {
  return (
    <span class="conn-state" role="status" aria-live="polite">
      <span class={'dot ' + dot} />
      <span><b>{lead}</b>{rest ? ' ' + rest : ''}</span>
    </span>
  );
}

export function TestButton({ disabled, testing, onTest }: { disabled: boolean; testing: boolean; onTest: () => void }) {
  return (
    <button type="button" class="btn btn-primary is-sm" disabled={disabled || testing} aria-busy={testing ? 'true' : undefined} onClick={onTest}>
      {testing ? t('Testing…') : t('Test connection')}
    </button>
  );
}

/* "Open Seerr ↗", greyed out until the app has an address. */
export function OpenAppButton({ href, label }: { href?: string; label: string }) {
  return href
    ? <a class="btn btn-secondary" href={href} target="_blank" rel="noreferrer">{label}<Icon name="external" size={16} /></a>
    : <button type="button" class="btn btn-secondary" disabled>{label}<Icon name="external" size={16} /></button>;
}
