import type { ComponentChildren, RefObject } from 'preact';
import { useCallback, useEffect, useRef, useState } from 'preact/hooks';
import { get } from '../../api/client';
import * as jf from '../../api/jellyfin';
import { userStore } from '../../lib/app-state';
import { getLang, locale, t } from '../../lib/i18n';
import { Icon } from '../../ui/icons';
import { Popover } from '../../ui/overlay';
import { DeviceTag } from './MeLayout';
import './playback.css';

/* Building blocks shared by Settings · Playback and Settings · Subtitles. */

/* ── A setting row as on the boards: name (+ "This device"), help, control ── */
export function PlaybackRow({ name, help, device, children, id }: {
  name: ComponentChildren; help?: ComponentChildren; device?: boolean; children: ComponentChildren; id?: string;
}) {
  return (
    <div class="me-pb-row">
      <span class="me-pb-label">
        <span class="me-pb-name" id={id}>{name}{device && <DeviceTag />}</span>
        {/* Present even when empty, as on the board (its 3 px gap sets where the name sits). */}
        <span class="me-pb-help">{help}</span>
      </span>
      {children}
    </div>
  );
}

/* ── Picker: a listbox button (Foundations menu) ── */
export interface PickOption<T> { value: T; label: string }

/* Option lists are English constants: translate their labels where they're shown ("4K", "1080p" stay as they are). */
export const tOptions = <T, O extends { label: string }>(list: (O & { value: T })[]): (O & { value: T })[] =>
  list.map((o) => (/[a-z]{2}/.test(o.label) ? { ...o, label: t(o.label) } : o));

export function Picker<T extends string | number>({ value, options, onChange, label, minWidth = 210, display, placeholder }: {
  value: T;
  options: PickOption<T>[];
  onChange: (v: T) => void;
  label: string;
  minWidth?: number;
  display?: (label: string) => ComponentChildren;
  placeholder?: string;
}) {
  const ref = useRef<HTMLButtonElement>(null);
  const listRef = useRef<HTMLDivElement>(null);
  const [open, setOpen] = useState(false);
  const [width, setWidth] = useState(240);
  const current = options.find((o) => o.value === value);
  const text = current?.label ?? placeholder ?? String(value);
  const typed = useRef({ text: '', at: 0 });
  const close = useCallback(() => setOpen(false), []);

  useEffect(() => {
    if (!open) return;
    // After the popover's own first focus: land on the chosen option and keep it in view.
    const id = requestAnimationFrame(() => requestAnimationFrame(() => {
      const sel = listRef.current?.querySelector<HTMLElement>('[aria-selected="true"]');
      if (sel) {
        sel.focus({ preventScroll: true });
        sel.scrollIntoView({ block: 'center' });
      }
    }));
    return () => cancelAnimationFrame(id);
  }, [open]);

  const choose = (v: T) => {
    setOpen(false);
    ref.current?.focus();
    if (v !== value) onChange(v);
  };

  /* Type-ahead: letters jump to the first option starting with them. */
  const onKey = (e: KeyboardEvent) => {
    if (e.key.length !== 1 || e.ctrlKey || e.metaKey || e.altKey) return;
    const now = Date.now();
    typed.current = { text: (now - typed.current.at < 700 ? typed.current.text : '') + e.key.toLowerCase(), at: now };
    const items = Array.from(listRef.current?.querySelectorAll<HTMLElement>('[role="option"]') ?? []);
    const hit = items.find((el) => (el.dataset.label ?? '').toLowerCase().startsWith(typed.current.text));
    if (hit) {
      e.preventDefault();
      hit.focus();
      hit.scrollIntoView({ block: 'nearest' });
    }
  };

  return (
    <>
      <button
        ref={ref}
        type="button"
        class="me-pb-pick"
        style={{ minWidth: minWidth + 'px' }}
        aria-haspopup="listbox"
        aria-expanded={open ? 'true' : 'false'}
        aria-label={`${label}: ${text}`}
        onClick={() => {
          if (!open && ref.current) setWidth(Math.max(ref.current.offsetWidth, 240));
          setOpen((o) => !o);
        }}
      >
        <span class="me-pb-pick-text">{display ? display(text) : text}</span>
        <Icon name="chevronDown" size={16} />
      </button>
      <Popover anchor={ref as RefObject<HTMLElement>} open={open} onClose={close} width={width}>
        <div ref={listRef} class="menu me-pb-listbox" role="listbox" aria-label={label} onKeyDown={onKey}>
          {options.map((o) => {
            const on = o.value === value;
            return (
              <button
                key={String(o.value)}
                type="button"
                role="option"
                class="menu-item"
                aria-selected={on ? 'true' : 'false'}
                data-label={o.label}
                onClick={() => choose(o.value)}
              >
                <span>{o.label}</span>
                {on && <Icon name="check" size={20} stroke={2.6} class="tick" />}
              </button>
            );
          })}
        </div>
      </Popover>
    </>
  );
}

/* Refresh the signed-in user once on mount so the page shows what Jellyfin has now. */
export function useFreshUser() {
  useEffect(() => {
    jf.getMe().then((u) => userStore.set(u), () => undefined);
  }, []);
}

/* ── Languages (Jellyfin's culture list; values are ISO 639-2 codes, as Jellyfin stores them) ── */

interface Culture { DisplayName: string; Name: string; ThreeLetterISOLanguageName: string }
let culturesCache: Promise<PickOption<string>[]> | null = null;

export function loadLanguages(): Promise<PickOption<string>[]> {
  culturesCache ??= get<Culture[]>('/Localization/Cultures').then((list) => {
    const seen = new Set<string>();
    return (list ?? [])
      .filter((c) => c.ThreeLetterISOLanguageName && !seen.has(c.ThreeLetterISOLanguageName) && seen.add(c.ThreeLetterISOLanguageName))
      .map((c) => ({ value: c.ThreeLetterISOLanguageName, label: localName(c.ThreeLetterISOLanguageName, c.DisplayName || c.Name) }))
      .sort((a, b) => a.label.localeCompare(b.label));
  }).catch(() => {
    // Server unreachable for this call (or a proxy that doesn't pass /Localization): common languages, as Jellyfin codes them.
    culturesCache = null;
    return FALLBACK_LANGUAGES.map((o) => ({ value: o.value, label: localName(o.value, o.label) }));
  });
  return culturesCache;
}

const FALLBACK_LANGUAGES: PickOption<string>[] = ([
  ['ara', 'Arabic'], ['bul', 'Bulgarian'], ['cat', 'Catalan'], ['chi', 'Chinese'], ['hrv', 'Croatian'], ['cze', 'Czech'], ['dan', 'Danish'],
  ['dut', 'Dutch'], ['eng', 'English'], ['est', 'Estonian'], ['fil', 'Filipino'], ['fin', 'Finnish'], ['fre', 'French'], ['ger', 'German'],
  ['gre', 'Greek'], ['heb', 'Hebrew'], ['hin', 'Hindi'], ['hun', 'Hungarian'], ['ice', 'Icelandic'], ['ind', 'Indonesian'], ['ita', 'Italian'],
  ['jpn', 'Japanese'], ['kor', 'Korean'], ['lav', 'Latvian'], ['lit', 'Lithuanian'], ['may', 'Malay'], ['nor', 'Norwegian'], ['per', 'Persian'],
  ['pol', 'Polish'], ['por', 'Portuguese'], ['rum', 'Romanian'], ['rus', 'Russian'], ['srp', 'Serbian'], ['slo', 'Slovak'], ['slv', 'Slovenian'],
  ['spa', 'Spanish'], ['swe', 'Swedish'], ['tha', 'Thai'], ['tur', 'Turkish'], ['ukr', 'Ukrainian'], ['vie', 'Vietnamese']
] as const).map(([value, label]) => ({ value, label }));

export function useLanguages(): PickOption<string>[] {
  const [list, setList] = useState<PickOption<string>[]>([]);
  useEffect(() => {
    let alive = true;
    loadLanguages().then((l) => alive && setList(l), () => undefined);
    return () => { alive = false; };
  }, []);
  return list;
}

let names: Intl.DisplayNames | null = null;
/* A language code's name before the culture list has loaded. */
export function languageName(code: string): string {
  if (getLang() !== 'en') return localName(code, code);
  try {
    names ??= new Intl.DisplayNames(['en'], { type: 'language' });
    return names.of(code) ?? code;
  } catch {
    return code;
  }
}

/* Jellyfin's culture list names languages in English: in French, name them from the code instead. */
let localNames: Intl.DisplayNames | null = null;
function localName(code: string, fallback: string): string {
  if (getLang() === 'en') return fallback;
  try {
    localNames ??= new Intl.DisplayNames([locale()], { type: 'language' });
    const n = localNames.of(code);
    if (!n || n.toLowerCase() === code.toLowerCase() || n === 'root') return fallback;
    return n.charAt(0).toLocaleUpperCase(locale()) + n.slice(1);
  } catch {
    return fallback;
  }
}

/* Options for a language picker, with the current value kept even if the list hasn't loaded. */
export function languageOptions(list: PickOption<string>[], first: PickOption<string>, current: string | undefined | null): PickOption<string>[] {
  const out = [first, ...list];
  if (current && !out.some((o) => o.value === current)) out.splice(1, 0, { value: current, label: languageName(current) });
  return out;
}
