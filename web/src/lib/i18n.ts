import type { ComponentChildren } from 'preact';

/* Seedy's languages: English (the source text) and French.
   Text is written in English in the code and wrapped in t(); French comes from the dictionaries in src/i18n/fr/*.ts,
   keyed by the exact English text. The language is a profile setting (Settings · Display · Language, "Auto" follows
   the browser); this device remembers the last one so the sign-in page and the first paint are already right.
   Changing it reloads the app, so t() may be called anywhere at render time — never at module load. */

export type Lang = 'en' | 'fr';
export const LANGS: { value: Lang; label: string }[] = [
  { value: 'en', label: 'English' },
  { value: 'fr', label: 'Français' }
];

const STORE_KEY = 'seedy.lang';
const RELOAD_KEY = 'seedy.langReload';

export function browserLang(): Lang {
  const list = typeof navigator === 'undefined' ? [] : (navigator.languages?.length ? navigator.languages : [navigator.language]);
  for (const l of list) {
    const code = (l ?? '').toLowerCase();
    if (code.startsWith('fr')) return 'fr';
    if (code.startsWith('en')) return 'en';
  }
  return 'en';
}

/* A stored preference ("auto", "fr", "fr-FR", an old "en-US"…) → the language Seedy shows. */
export function resolveLang(pref: unknown): Lang {
  const v = typeof pref === 'string' ? pref.toLowerCase() : '';
  if (v.startsWith('fr')) return 'fr';
  if (v.startsWith('en')) return 'en';
  return browserLang();
}

let lang: Lang = (() => {
  try {
    const v = localStorage.getItem(STORE_KEY);
    if (v === 'en' || v === 'fr') return v;
  } catch { /* storage blocked: follow the browser */ }
  return browserLang();
})();

let dict: Record<string, string> = {};
const missing = new Set<string>();

export const getLang = () => lang;
/* The locale for Intl formatting. English keeps the boards' day-first style. */
export const locale = () => (lang === 'fr' ? 'fr-FR' : 'en-GB');

/* Loads the dictionary for the current language. Call (and await) before the first render. */
export async function initI18n(): Promise<void> {
  document.documentElement.lang = lang;
  if (lang === 'en') return;
  const modules = import.meta.glob<{ default: Record<string, string> }>('../i18n/fr/*.ts');
  const loaded = await Promise.all(Object.values(modules).map((load) => load().catch(() => ({ default: {} }))));
  dict = Object.assign({}, ...loaded.map((m) => m.default));
}

/* The profile's language just became known or changed: remember it on this device and reload into it. */
export function applyLanguagePref(pref: unknown) {
  const next = resolveLang(pref);
  try { localStorage.setItem(STORE_KEY, next); } catch { /* the reload below still applies it */ }
  if (next === lang) return;
  // Never loop: one reload per language switch, even if storage is blocked.
  try {
    const last = JSON.parse(sessionStorage.getItem(RELOAD_KEY) ?? 'null') as { to: Lang; at: number } | null;
    if (last && last.to === next && Date.now() - last.at < 10_000) return;
    sessionStorage.setItem(RELOAD_KEY, JSON.stringify({ to: next, at: Date.now() }));
  } catch { /* fine */ }
  window.location.reload();
}

const fill = (s: string, vars?: Record<string, string | number>) =>
  vars ? s.replace(/\{(\w+)\}/g, (m, k: string) => (k in vars ? String(vars[k]) : m)) : s;

/* Translate English text. Placeholders like {n} or {name} are filled from vars (in both languages). */
export function t(text: string, vars?: Record<string, string | number>): string {
  if (lang === 'en') return fill(text, vars);
  const hit = dict[text];
  if (hit === undefined && import.meta.env.DEV && !missing.has(text)) {
    missing.add(text);
    (window as unknown as { __i18nMissing?: Set<string> }).__i18nMissing = missing;
  }
  return fill(hit ?? text, vars);
}

/* Plural forms: tn(3, '{n} movie', '{n} movies'). The English forms are the keys; the language's own plural rule
   picks one (French treats 0 and 1 as singular). */
export function tn(n: number, one: string, other: string, vars?: Record<string, string | number>): string {
  let form: Intl.LDMLPluralRule = 'other';
  try { form = new Intl.PluralRules(locale()).select(n); } catch { form = n === 1 ? 'one' : 'other'; }
  return t(form === 'one' ? one : other, { n: formatNumber(n), ...vars });
}

/* Translate text that has elements inside: tx('Marked {tag} below', { tag: <DeviceTag /> }). */
export function tx(text: string, parts: Record<string, ComponentChildren>): ComponentChildren[] {
  const s = lang === 'en' ? text : (dict[text] ?? text);
  const out: ComponentChildren[] = [];
  let last = 0;
  s.replace(/\{(\w+)\}/g, (m, k: string, at: number) => {
    out.push(s.slice(last, at));
    out.push(k in parts ? parts[k] : m);
    last = at + m.length;
    return m;
  });
  out.push(s.slice(last));
  return out;
}

export function formatNumber(n: number, opts?: Intl.NumberFormatOptions): string {
  try { return new Intl.NumberFormat(locale(), opts).format(n); } catch { return String(n); }
}

