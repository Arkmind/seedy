import type { ComponentChildren, RefObject } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { userImageUrl } from '../../api/images';
import { t, tn } from '../../lib/i18n';
import { addNavigationGuard, getLocation, navigate } from '../../lib/router';
import { Icon } from '../../ui/icons';
import { confirm, Popover, Sheet } from '../../ui/overlay';
import { errText, type FsEntry, getDirectory, getDrives, getParentPath } from './api';
import { avatarColour } from './format';
import './server.css';

/* Pieces shared by the server dashboard pages: loading, drafts with an explicit save, setting rows and controls,
   the folder picker and the people's avatars. */

/* ── Loading ── */


/* Loads once on mount; `setData` replaces what was loaded (after a save), `reload` fetches again. */
export function useLoad<T>(fn: () => Promise<T>) {
  const [state, setState] = useState<{ data?: T; error?: Error; loading: boolean }>({ loading: true });
  const seq = useRef(0);
  const fnRef = useRef(fn);
  fnRef.current = fn;
  const load = (quiet = false) => {
    const n = ++seq.current;
    if (!quiet) setState((s) => ({ data: s.data, loading: true }));
    fnRef.current().then(
      (data) => { if (n === seq.current) setState({ data, loading: false }); },
      (error: Error) => { if (n === seq.current) setState((s) => (quiet && s.data ? { ...s, loading: false } : { error, loading: false })); }
    );
  };
  useEffect(() => { load(); return () => { seq.current++; }; }, []);
  return {
    ...state,
    reload: (quiet = false) => load(quiet),
    setData: (data: T) => { seq.current++; setState({ data, loading: false }); }
  };
}

/* Re-run a loader every few seconds while the tab is visible (and once when it becomes visible again). */
export function usePoll(fn: () => void, ms: number, deps: unknown[] = []) {
  const ref = useRef(fn);
  ref.current = fn;
  useEffect(() => {
    let timer = 0;
    const tick = () => {
      window.clearTimeout(timer);
      if (document.visibilityState === 'visible') ref.current();
      timer = window.setTimeout(tick, ms);
    };
    timer = window.setTimeout(tick, ms);
    const onVis = () => { if (document.visibilityState === 'visible') { ref.current(); } };
    document.addEventListener('visibilitychange', onVis);
    return () => { window.clearTimeout(timer); document.removeEventListener('visibilitychange', onVis); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [ms, ...deps]);
}

/* ── Drafts with an explicit save ── */

const isPlain = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v);

/* Fields that differ between two configuration objects; nested objects count field by field, lists count once. */
export function countChanges(a: unknown, b: unknown): number {
  if (isPlain(a) && isPlain(b)) {
    let n = 0;
    for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) n += countChanges(a[k], b[k]);
    return n;
  }
  return JSON.stringify(a ?? null) === JSON.stringify(b ?? null) ? 0 : 1;
}

export function useDraft<T extends object>(saved: T | undefined) {
  const [draft, setDraft] = useState<T | null>(null);
  const value = draft ?? saved;
  const changes = draft && saved ? countChanges(draft, saved) : 0;
  const edit = (fn: (v: T) => T) => setDraft((d) => {
    const base = d ?? saved;
    return base ? fn(base) : d;
  });
  return {
    value,
    draft,
    changes,
    edit,
    patch: (p: Partial<T>) => edit((v) => ({ ...v, ...p })),
    reset: () => setDraft(null)
  };
}

/* Holds in-app navigation and page unloads while there are unsaved edits. Moving between the page's own tabs
   (same path, another query) isn't leaving. `text` is the whole warning ("Your changes to … haven't been saved yet."). */
export function useUnsavedGuard(dirty: boolean, text: string) {
  const dirtyRef = useRef(dirty);
  dirtyRef.current = dirty;
  useEffect(() => {
    let asking = false;
    const remove = addNavigationGuard((to) => {
      if (!dirtyRef.current) return true;
      if (to.split('?')[0].toLowerCase() === getLocation().path.toLowerCase()) return true;
      if (asking) return false;
      asking = true;
      void confirm({
        title: t('Leave without saving?'),
        text,
        action: t('Leave without saving'),
        danger: true
      }).then((ok) => {
        asking = false;
        if (ok) { dirtyRef.current = false; navigate(to, { force: true }); }
      });
      return false;
    });
    return () => { remove(); };
  }, [text]);
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault(); e.returnValue = ''; };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [dirty]);
}

/* The sticky "1 unsaved change · needs a restart" bar. */
export function UnsavedBar({ changes, note, saving, onDiscard, onSave, saveLabel, canSave = true }: {
  changes: number; note?: string; saving: boolean; onDiscard: () => void; onSave: () => void; saveLabel?: string; canSave?: boolean;
}) {
  if (changes <= 0) return null;
  return (
    <div class="unsaved srv-unsaved" role="region" aria-label={t('Unsaved changes')}>
      <span class="unsaved-text" aria-live="polite">
        <span class="dot is-pending" />
        {tn(changes, '{n} unsaved change', '{n} unsaved changes')}{note ? ` · ${note}` : ''}
      </span>
      <div class="srv-unsaved-actions">
        <button type="button" class="btn btn-ghost is-light" disabled={saving} onClick={onDiscard}>{t('Discard')}</button>
        <button type="button" class="btn btn-primary is-sm is-save" disabled={saving || !canSave} onClick={onSave}>{saving ? t('Saving…') : saveLabel ?? t('Save changes')}</button>
      </div>
    </div>
  );
}

/* ── Rows and controls ── */

export function SettingRow({ name, help, htmlFor, children, cls, labelCls }: {
  name: ComponentChildren; help?: ComponentChildren; htmlFor?: string; children?: ComponentChildren; cls?: string; labelCls?: string;
}) {
  const Label = htmlFor ? 'label' : 'span';
  return (
    <div class={'srv-row' + (cls ? ' ' + cls : '')}>
      <Label {...(htmlFor ? { for: htmlFor } : {})} class={'srv-label' + (labelCls ? ' ' + labelCls : '')}>
        <span class="srv-name">{name}</span>
        {help && <span class="srv-help">{help}</span>}
      </Label>
      {children}
    </div>
  );
}

/* A number with its unit ("180 s", "2 streams"). `width` is the board's input width; the number keeps the
   browser's spinner gap on its right as drawn. */
export function NumBox({ value, onChange, min, max, unit, label, width = 52, step = 1, id }: {
  value: number; onChange: (v: number) => void; min: number; max: number; unit?: string; label: string; width?: number; step?: number; id?: string;
}) {
  const [text, setText] = useState(String(value));
  useEffect(() => { setText(String(value)); }, [value]);
  const commit = (raw: string) => {
    const n = Number(raw);
    if (raw.trim() === '' || !Number.isFinite(n)) { setText(String(value)); return; }
    const v = Math.max(min, Math.min(max, step < 1 ? Math.round(n / step) * step : Math.round(n)));
    setText(String(v));
    if (v !== value) onChange(v);
  };
  return (
    <label class={'num-field srv-numbox' + (unit ? '' : ' is-bare')}>
      <input
        id={id}
        type="number"
        inputMode={step < 1 ? 'decimal' : 'numeric'}
        value={text}
        min={min}
        max={max}
        step={step}
        aria-label={label}
        style={{ width: Math.max(16, width - 15) + 'px' }}
        onInput={(e) => setText((e.currentTarget as HTMLInputElement).value)}
        onChange={(e) => commit((e.currentTarget as HTMLInputElement).value)}
        onKeyDown={(e) => { if (e.key === 'Enter') commit((e.currentTarget as HTMLInputElement).value); }}
      />
      {unit}
    </label>
  );
}

export function Select<T extends string | number>({ value, options, onChange, label, id, minWidth, mono }: {
  value: T; options: { value: T; label: string }[]; onChange: (v: T) => void; label?: string; id?: string; minWidth?: number; mono?: boolean;
}) {
  return (
    <span class={'select-wrap' + (mono ? ' srv-mono-select' : '')}>
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

export const SwitchCtl = ({ on, set, label, disabled }: { on: boolean; set: (v: boolean) => void; label: string; disabled?: boolean }) => (
  <button type="button" role="switch" class="switch" aria-checked={on ? 'true' : 'false'} aria-label={label} disabled={disabled} onClick={() => set(!on)} />
);

/* Select options whose labels are English keys: translated where they're rendered. */
export const tOpts = <T,>(opts: { value: T; label: string }[]) => opts.map((o) => ({ ...o, label: t(o.label) }));

/* ── Lists of addresses (Networking) ── */

export function ChipList({ items, onChange, label, emptyText, placeholder, validate, help, menuTop }: {
  items: string[]; onChange: (v: string[]) => void; label: string; emptyText?: string; placeholder: string;
  validate?: (v: string) => string | null; help?: string; menuTop?: ComponentChildren;
}) {
  const addRef = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  return (
    <div class="srv-chips">
      {items.map((v, i) => (
        <span key={v + i} class="srv-chip">
          {v}
          <button type="button" aria-label={t('Remove {name}', { name: v })} onClick={() => onChange(items.filter((_, j) => j !== i))}>
            <Icon name="close" size={12} />
          </button>
        </span>
      ))}
      {items.length === 0 && emptyText && <span class="srv-chips-empty">{emptyText}</span>}
      <button ref={addRef} type="button" class="srv-add" aria-label={t('Add to {list}', { list: label })} aria-expanded={open ? 'true' : 'false'} onClick={() => setOpen((o) => !o)}>{t('Add')}</button>
      <AddPopover
        anchor={addRef}
        open={open}
        onClose={() => setOpen(false)}
        title={label}
        help={help}
        top={menuTop}
        placeholder={placeholder}
        onAdd={(v) => {
          const err = validate?.(v) ?? null;
          if (err) return err;
          if (items.some((x) => x.toLowerCase() === v.toLowerCase())) return t('That’s already in the list.');
          onChange([...items, v]);
          setOpen(false);
          return null;
        }}
      />
    </div>
  );
}

function AddPopover({ anchor, open, onClose, title, help, placeholder, onAdd, top }: {
  anchor: RefObject<HTMLButtonElement>; open: boolean; onClose: () => void; title: string; help?: string; placeholder: string; onAdd: (v: string) => string | null;
  top?: ComponentChildren;
}) {
  const [text, setText] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (!open) return;
    setText('');
    setErr(null);
    /* The popover focuses its first control; the address field is what people came for. */
    requestAnimationFrame(() => requestAnimationFrame(() => input.current?.focus()));
  }, [open]);
  const submit = (e: Event) => {
    e.preventDefault();
    const v = text.trim();
    if (!v) return;
    setErr(onAdd(v));
  };
  return (
    <Popover anchor={anchor as RefObject<HTMLElement>} open={open} onClose={onClose} align="end" width={340}>
      <form class="menu srv-add-pop" onSubmit={submit}>
        {top}
        <span class="menu-label">{title}</span>
        <input ref={input} class="field srv-mono-plain" value={text} placeholder={placeholder} aria-label={title} spellcheck={false} autocomplete="off"
          onInput={(e) => { setText((e.currentTarget as HTMLInputElement).value); setErr(null); }} />
        {err ? <span class="form-error"><Icon name="alert" size={16} />{err}</span> : help && <span class="srv-add-help">{help}</span>}
        <div class="srv-add-actions">
          <button type="button" class="btn btn-ghost is-light is-sm" onClick={onClose}>{t('Cancel')}</button>
          <button type="submit" class="btn btn-primary is-sm" disabled={!text.trim()}>{t('Add')}</button>
        </div>
      </form>
    </Popover>
  );
}

/* Loose checks: Jellyfin accepts IPs, CIDR ranges and (for proxies) host names; this only catches typos. */
const IPV4 = /^(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}$/;
const IPV6 = /^[0-9a-f:]+(%\w+)?$/i;
export function validAddress(v: string, opts: { cidr?: boolean; host?: boolean } = {}): string | null {
  const [ip, bits, extra] = v.split('/');
  if (extra !== undefined) return t('Use one address, or one address and a /prefix.');
  const isIp = IPV4.test(ip) || (ip.includes(':') && IPV6.test(ip));
  if (bits !== undefined) {
    if (!opts.cidr || !isIp) return t('Ranges are written like 192.168.1.0/24.');
    const n = Number(bits);
    if (!Number.isInteger(n) || n < 0 || n > (ip.includes(':') ? 128 : 32)) return t('The /prefix must be between 0 and 32 (128 for IPv6).');
    return null;
  }
  if (isIp) return null;
  if (opts.host && /^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$/i.test(v)) return null;
  return opts.cidr ? t('Write an address like 192.168.1.20 or a range like 192.168.1.0/24.') : t('Write an address like 192.168.1.20.');
}

/* ── Folder picker: browses the server's file system (/Environment) ── */

export function PathField({ value, onChange, label, placeholder, id, title, files }: {
  value: string; onChange: (v: string) => void; label: string; placeholder?: string; id?: string; title?: string; files?: boolean;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div class="srv-browse-path">
      <input id={id} type="text" class="field srv-mono-plain" value={value} placeholder={placeholder} aria-label={id ? undefined : label} spellcheck={false}
        onChange={(e) => onChange((e.currentTarget as HTMLInputElement).value.trim())} />
      <button type="button" class="btn btn-secondary" onClick={() => setOpen(true)}>{t('Browse')}</button>
      <FolderPicker open={open} start={files ? value.replace(/[\\/][^\\/]*$/, '') : value} title={title ?? label} files={files}
        onClose={() => setOpen(false)} onPick={(p) => { onChange(p); setOpen(false); }} />
    </div>
  );
}

export function FolderPicker({ open, start, title = t('Choose a folder'), action = t('Use this folder'), onClose, onPick, files }: {
  open: boolean; start?: string; title?: string; action?: string; onClose: () => void; onPick: (path: string) => void; files?: boolean;
}) {
  const [path, setPath] = useState('');
  const [typed, setTyped] = useState('');
  const [entries, setEntries] = useState<FsEntry[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [parent, setParent] = useState<string | null>(null);
  const seq = useRef(0);

  const go = (p: string) => {
    const n = ++seq.current;
    setPath(p);
    setTyped(p);
    setEntries(null);
    setErr(null);
    const list = p ? getDirectory(p, !!files) : getDrives();
    list.then(
      (e) => { if (n === seq.current) setEntries(e.filter((x) => x.Type !== 'NetworkComputer')); },
      () => { if (n === seq.current) { setEntries([]); setErr(p ? t('The server can’t open this folder.') : t('The server didn’t list its drives.')); } }
    );
    if (p) getParentPath(p).then((pp) => { if (n === seq.current) setParent(typeof pp === 'string' && pp !== p ? pp : ''); }, () => { if (n === seq.current) setParent(''); });
    else setParent(null);
  };

  useEffect(() => { if (open) go((start ?? '').trim()); }, [open]);

  return (
    <Sheet open={open} onClose={onClose} label={title} wide>
      <div class="sheet-head">
        <div class="srv-sheet-titles">
          <h2 class="sheet-title">{title}</h2>
          <p class="sheet-text">{t('Folders on the server, not on this device.')}</p>
        </div>
        <button type="button" class="btn icon-btn is-40" aria-label={t('Close')} onClick={onClose}><Icon name="close" size={18} /></button>
      </div>
      <form class="srv-picker-path" onSubmit={(e) => { e.preventDefault(); go(typed.trim()); }}>
        <button type="button" class="btn btn-secondary is-sm" disabled={parent === null} onClick={() => go(parent ?? '')} aria-label={t('Up one folder')}>
          <Icon name="chevronLeft" size={16} />{t('Up')}
        </button>
        <input class="field srv-mono-plain" value={typed} placeholder="/" aria-label={t('Folder path')} spellcheck={false}
          onInput={(e) => setTyped((e.currentTarget as HTMLInputElement).value)} />
        <button type="submit" class="btn btn-secondary is-sm">{t('Go')}</button>
      </form>
      <div class="srv-picker-list" role="listbox" aria-label={t('Folders')} aria-busy={entries === null ? 'true' : undefined}>
        {entries === null && Array.from({ length: 5 }, (_, i) => <span key={i} class="skel srv-picker-skel" />)}
        {entries && entries.length === 0 && <p class="srv-picker-empty">{err ?? t('No folders in here.')}</p>}
        {entries?.map((e) => (
          <button key={e.Path} type="button" role="option" aria-selected="false" class="menu-item srv-picker-item"
            onClick={() => (e.Type === 'File' ? onPick(e.Path) : go(e.Path))}>
            <span class="lead"><Icon name={e.Type === 'File' ? 'list' : 'folder'} size={18} /><span>{e.Name || e.Path}</span></span>
            {e.Type !== 'File' && <Icon name="chevronRight" size={16} />}
          </button>
        ))}
      </div>
      <div class="sheet-actions">
        <button type="button" class="btn btn-ghost is-light" onClick={onClose}>{t('Cancel')}</button>
        {!files && <button type="button" class="btn btn-primary is-sm" disabled={!path} onClick={() => onPick(path)}>{action}</button>}
      </div>
    </Sheet>
  );
}

/* ── People ── */

/* A user's picture, or their initial on a colour. Disabled accounts are grey. */
export function UserAvatar({ user, index, size = 48, online, disabled, imgVersion }: {
  user: { Id: string; Name: string; PrimaryImageTag?: string };
  index: number;
  size?: number;
  online?: boolean;
  disabled?: boolean;
  imgVersion?: number;
}) {
  const [failed, setFailed] = useState(false);
  const src = user.PrimaryImageTag && !failed ? userImageUrl(user, size * 2) : undefined;
  return (
    <span
      class="srv-avatar"
      aria-hidden="true"
      style={{
        width: size + 'px', height: size + 'px', fontSize: (size <= 32 ? 13 : Math.round(size * (size >= 64 ? 0.39 : 0.375))) + 'px',
        background: src ? 'var(--seedy-surface)' : disabled ? 'var(--seedy-text-3)' : avatarColour(index)
      }}
    >
      {src ? <img src={src + (imgVersion ? `&v=${imgVersion}` : '')} alt="" onError={() => setFailed(true)} /> : (user.Name || '?').trim().charAt(0).toUpperCase()}
      {online && <span class="srv-online" />}
    </span>
  );
}

/* A notice above the page: "File Transformation won't load. …  Open plugins". */
export function Notice({ tone, children, action, onAction, href }: {
  tone: 'warn' | 'accent';
  children: ComponentChildren;
  action: string;
  onAction?: () => void;
  href?: string;
}) {
  const body = (
    <>
      <span class={'srv-notice-dot' + (tone === 'warn' ? ' is-warn' : '')} />
      <span class="srv-notice-text">{children}</span>
      <span class="srv-notice-action">{action}</span>
    </>
  );
  const cls = 'srv-notice' + (tone === 'warn' ? ' is-warn' : '');
  if (href) {
    return (
      <a class={cls} href={href} onClick={(e) => { if (e.button !== 0 || e.metaKey || e.ctrlKey) return; e.preventDefault(); onAction?.(); }}>
        {body}
      </a>
    );
  }
  return <button type="button" class={cls} onClick={onAction}>{body}</button>;
}

/* A hidden file input opened by a button. */
export function useFilePicker(onFile: (f: File) => void, accept = 'image/*') {
  const ref = useRef<HTMLInputElement>(null);
  const input = (
    <input
      ref={ref}
      type="file"
      accept={accept}
      hidden
      onChange={(e) => {
        const f = (e.currentTarget as HTMLInputElement).files?.[0];
        (e.currentTarget as HTMLInputElement).value = '';
        if (f) onFile(f);
      }}
    />
  );
  return { input, open: () => ref.current?.click() };
}

/* Two-column rows editor in a sheet: path substitutions (From → To) and cast receivers (Name, ID). */
export function PairsSheet<T extends Record<string, string>>({ open, title, text, rows, keys, labels, placeholders, addLabel, mono, onClose, onDone }: {
  open: boolean;
  title: string;
  text: string;
  rows: T[];
  keys: [keyof T & string, keyof T & string];
  labels: [string, string];
  placeholders: [string, string];
  addLabel: string;
  mono?: [boolean, boolean];
  onClose: () => void;
  onDone: (rows: T[]) => void;
}) {
  const [list, setList] = useState<T[]>(rows);
  useEffect(() => { if (open) setList(rows.length ? rows : []); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, [open]);
  const set = (i: number, k: keyof T, v: string) => setList((l) => l.map((r, j) => (j === i ? { ...r, [k]: v } : r)));
  const clean = list.filter((r) => String(r[keys[0]]).trim() || String(r[keys[1]]).trim());
  return (
    <Sheet open={open} onClose={onClose} label={title} wide>
      <div class="sheet-head">
        <div class="srv-sheet-headtext">
          <h2 class="sheet-title">{title}</h2>
          <p class="sheet-text">{text}</p>
        </div>
        <button type="button" class="btn icon-btn is-36" aria-label={t('Close')} onClick={onClose}><Icon name="close" size={16} /></button>
      </div>
      <div class="srv-pairs">
        {list.length > 0 && (
          <div class="srv-pairs-head" aria-hidden="true"><span>{labels[0]}</span><span>{labels[1]}</span></div>
        )}
        {list.map((r, i) => (
          <div key={i} class="srv-pairs-row">
            <input class={'field' + (mono?.[0] ? ' srv-mono' : '')} aria-label={`${labels[0]} ${i + 1}`} placeholder={placeholders[0]} value={String(r[keys[0]] ?? '')} onInput={(e) => set(i, keys[0], (e.currentTarget as HTMLInputElement).value)} />
            <input class={'field' + (mono?.[1] ? ' srv-mono' : '')} aria-label={`${labels[1]} ${i + 1}`} placeholder={placeholders[1]} value={String(r[keys[1]] ?? '')} onInput={(e) => set(i, keys[1], (e.currentTarget as HTMLInputElement).value)} />
            <button type="button" class="srv-x" aria-label={t('Remove row {n}', { n: i + 1 })} onClick={() => setList((l) => l.filter((_, j) => j !== i))}><Icon name="close" size={14} /></button>
          </div>
        ))}
        {list.length === 0 && <p class="srv-picker-note">{t('Nothing here yet.')}</p>}
        <button type="button" class="srv-dashed" onClick={() => setList((l) => [...l, { [keys[0]]: '', [keys[1]]: '' } as T])}>
          <Icon name="plus" size={13} />{addLabel}
        </button>
      </div>
      <div class="sheet-actions">
        <button type="button" class="btn btn-ghost is-light" onClick={onClose}>{t('Cancel')}</button>
        <button type="button" class="btn btn-primary is-sm" onClick={() => onDone(clean.map((r) => ({ ...r, [keys[0]]: String(r[keys[0]]).trim(), [keys[1]]: String(r[keys[1]]).trim() })))}>{t('Done')}</button>
      </div>
    </Sheet>
  );
}

/* ── Errors ── */

/* Inline error with a retry, for a panel whose data didn't load. */
export function InlineError({ text, onRetry }: { text: string; onRetry?: () => void }) {
  return (
    <div class="admin-inline-error" role="alert">
      <Icon name="alert" size={18} color="var(--seedy-danger)" />
      <span>{text}</span>
      {onRetry && <button type="button" class="link-btn" onClick={onRetry}>{t('Try again')}</button>}
    </div>
  );
}

/* Like the admin shell's LoadError, worded for Jellyfin's own settings rather than Seedy's plugin.
   `title` is the translated heading ("Couldn’t load the devices"). */
export function LoadFailed({ error, title, onRetry }: { error: Error; title: string; onRetry: () => void }) {
  const msg = errText(error, '');
  return (
    <div class="error-box" role="alert">
      <h2>{title}</h2>
      <p>{(msg || t('The server didn’t answer.')) + ' ' + t('Check that the server is running and that you’re signed in as an administrator, then try again.')}</p>
      <button type="button" class="btn btn-primary is-sm" onClick={onRetry}>{t('Try again')}</button>
    </div>
  );
}
