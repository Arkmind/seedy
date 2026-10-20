import { useEffect, useRef, useState } from 'preact/hooks';
import { getItems } from '../../api/jellyfin';
import { backdropUrl, logoUrl } from '../../api/images';
import type { BaseItem } from '../../api/types';
import { DEFAULT_BRANDING, applyBranding, bootState, brandingStore, meStore, setAccentFilter } from '../../lib/app-state';
import { setDateStyle } from '../../lib/format';
import { applyLanguagePref, resolveLang, t } from '../../lib/i18n';
import { back, getLocation, useLocation } from '../../lib/router';
import { createStore, useStore } from '../../lib/store';
import { BrandMark } from '../../ui/navbar/Header';
import { Icon } from '../../ui/icons';
import { Sheet } from '../../ui/overlay';
import { CUSTOM, deviceStore, readCustom, type DevicePrefs } from './prefs';
import { ShortcutGrid } from './shortcuts';
import './effects.css';

/* App-wide effects of the personal settings: they must change how Seedy looks and behaves, not just be stored.
   Started once from main.tsx; <PersonalHost /> (in app.tsx) draws the "?" overlay and the screensaver. */

const HEX = /^#[0-9a-f]{6}$/i;
const root = () => document.documentElement;
const onPlayer = () => getLocation().path.toLowerCase().startsWith('/play/');

/* ── Dates and times ── */

export const DATE_LOCALES: { value: string; label: string }[] = [
  { value: 'en-GB', label: 'English (UK)' },
  { value: 'en-US', label: 'English (US)' },
  { value: 'de-DE', label: 'Deutsch' },
  { value: 'fr-FR', label: 'Français' },
  { value: 'es-ES', label: 'Español' },
  { value: 'nl-NL', label: 'Nederlands' },
  { value: 'ja-JP', label: '日本語' },
  { value: 'iso', label: 'ISO 8601' }
];

/* Auto follows Seedy's language; English keeps Seedy's own style (day first, 24-hour clock), as on the boards. */
export function resolveDateLocale(choice: string, language: string): string {
  if (choice && choice !== 'auto') return choice;
  return resolveLang(language) === 'fr' ? 'fr-FR' : 'en-GB';
}

export function dateExample(locale: string, d: Date): string {
  if (locale === 'iso') {
    const p = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
  }
  try { return new Intl.DateTimeFormat(locale, { dateStyle: 'short', timeStyle: 'short' }).format(d); } catch { return d.toLocaleString(); }
}

function applyDates() {
  const locale = resolveDateLocale(readCustom(CUSTOM.dateLocale), readCustom(CUSTOM.language));
  if (locale === 'iso') { setDateStyle({ hour12: false, monthFirst: false, iso: true }); return; }
  try {
    const f = new Intl.DateTimeFormat(locale, { hour: 'numeric' }).resolvedOptions();
    const hour12 = f.hourCycle ? f.hourCycle === 'h11' || f.hourCycle === 'h12' : !!f.hour12;
    const parts = new Intl.DateTimeFormat(locale, { month: 'short', day: 'numeric' }).formatToParts(new Date(2026, 9, 19));
    const monthFirst = parts.findIndex((p) => p.type === 'month') < parts.findIndex((p) => p.type === 'day');
    setDateStyle({ hour12, monthFirst, iso: false });
  } catch {
    setDateStyle({ hour12: false, monthFirst: false, iso: false });
  }
}

/* ── Layout ── */

const TV_UA = /SMART-TV|SmartTV|Tizen|Web0S|webOS|NetCast|BRAVIA|HbbTV|Android TV|GoogleTV|AFT[A-Z]|CrKey|Roku|Xbox|PlayStation/i;
const padConnected = () => Array.from(navigator.getGamepads?.() ?? []).some(Boolean);

export const tvActive = createStore(false);

function layoutIsTv(d: DevicePrefs) {
  if (d.layout === 'tv') return true;
  if (d.layout === 'desktop') return false;
  return TV_UA.test(navigator.userAgent) || (d.gamepad && padConnected());
}

let viewportOriginal: string | null = null;
function applyViewport(d: DevicePrefs) {
  const meta = document.querySelector<HTMLMetaElement>('meta[name="viewport"]');
  if (!meta) return;
  if (viewportOriginal === null) viewportOriginal = meta.content;
  // "Desktop" on a phone shows the desktop layout, the way a browser's "desktop site" does.
  const force = d.layout === 'desktop' && Math.min(screen.width, screen.height) < 768;
  meta.content = force ? 'width=1280' : viewportOriginal;
}

/* ── Look ── */

let cssEl: HTMLStyleElement | null = null;
function applyCustomCss(css: string) {
  if (!css.trim()) { cssEl?.remove(); cssEl = null; return; }
  if (!cssEl) {
    cssEl = document.createElement('style');
    cssEl.id = 'seedy-device-css';
  }
  // Last in <head> so it wins over Seedy's own styles.
  document.head.appendChild(cssEl);
  if (cssEl.textContent !== css) cssEl.textContent = css;
}

let lastAccent = '';
function applyAccent() {
  const key = String(readCustom(CUSTOM.accent)) + '|' + deviceStore.get().ignoreServerCss;
  if (key === lastAccent) return;
  lastAccent = key;
  applyBranding(brandingStore.get());
}

/* ── Soft placeholders: a blurred, tiny copy of each picture shows while the full one loads ── */

const SOFT_IMGS = 'img.art, img.art-contain, img.dhero-art, img.hero-art';
let softObserver: MutationObserver | null = null;

function tinyUrl(src: string): string | null {
  try {
    const u = new URL(src, location.href);
    if (/\/Items\/[^/]+\/Images\//i.test(u.pathname)) {
      u.searchParams.set('maxWidth', '40');
      u.searchParams.set('quality', '40');
      return u.toString();
    }
    if (u.hostname === 'image.tmdb.org') return u.toString().replace(/\/t\/p\/[^/]+\//, '/t/p/w92/');
  } catch { /* not a URL */ }
  return null;
}

function soften(img: HTMLImageElement) {
  const parent = img.parentElement;
  if (!parent || parent.classList.contains('me-soft') || (img.complete && img.naturalWidth > 0)) return;
  const tiny = tinyUrl(img.currentSrc || img.src);
  if (!tiny) return;
  parent.style.setProperty('--me-soft', `url("${tiny}")`);
  parent.classList.add('me-soft');
}

function scan(node: Node) {
  if (!(node instanceof Element)) return;
  if (node.matches(SOFT_IMGS)) soften(node as HTMLImageElement);
  node.querySelectorAll<HTMLImageElement>(SOFT_IMGS).forEach(soften);
}

function setSoft(on: boolean) {
  if (on && !softObserver) {
    softObserver = new MutationObserver((list) => {
      for (const m of list) {
        if (m.type === 'attributes') scan(m.target);
        else m.addedNodes.forEach(scan);
      }
    });
    softObserver.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['src'] });
    scan(document.body);
  } else if (!on && softObserver) {
    softObserver.disconnect();
    softObserver = null;
    document.querySelectorAll<HTMLElement>('.me-soft').forEach((el) => { el.classList.remove('me-soft'); el.style.removeProperty('--me-soft'); });
  }
}

/* ── Smooth scrolling off: scrolls that asked to glide jump instead ── */

function patchScrolling() {
  const off = () => !deviceStore.get().smoothScrolling;
  const fix = (a: unknown) => (off() && a && typeof a === 'object' && (a as ScrollOptions).behavior === 'smooth' ? { ...(a as object), behavior: 'auto' } : a);
  for (const target of [Element.prototype, window] as unknown as Record<string, (...args: unknown[]) => unknown>[]) {
    for (const name of ['scrollBy', 'scrollTo', 'scrollIntoView']) {
      const orig = target[name];
      if (typeof orig !== 'function') continue;
      target[name] = function (this: unknown, ...args: unknown[]) { return orig.apply(this, [fix(args[0]), ...args.slice(1)]); };
    }
  }
}

/* ── Spatial navigation for the TV layout, remotes and gamepads ── */

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

function editable(el: Element | null) {
  if (!el) return false;
  if (el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement) return true;
  if (el instanceof HTMLInputElement) return !['checkbox', 'radio', 'button', 'submit', 'reset', 'range', 'color', 'file'].includes(el.type);
  return (el as HTMLElement).isContentEditable || el.getAttribute('role') === 'slider';
}

function scope(): ParentNode {
  const sheets = document.querySelectorAll('.scrim .sheet');
  if (sheets.length) return sheets[sheets.length - 1];
  const pop = document.querySelectorAll('.popover');
  if (pop.length) return pop[pop.length - 1];
  return document;
}

function visible(el: HTMLElement) {
  if (el.getAttribute('tabindex') === '-1' || el.closest('[inert], [aria-hidden="true"]')) return false;
  const r = el.getBoundingClientRect();
  if (r.width < 2 || r.height < 2) return false;
  const cs = getComputedStyle(el);
  return cs.visibility !== 'hidden' && cs.display !== 'none' && Number(cs.opacity) > 0.05;
}

type Dir = 'up' | 'down' | 'left' | 'right';

function moveFocus(dir: Dir): boolean {
  const all = Array.from(scope().querySelectorAll<HTMLElement>(FOCUSABLE)).filter(visible);
  if (!all.length) return false;
  const cur = document.activeElement as HTMLElement | null;
  const smooth = deviceStore.get().smoothScrolling ? 'smooth' : 'auto';
  const go = (el: HTMLElement, block: ScrollLogicalPosition) => {
    el.focus({ preventScroll: true });
    el.scrollIntoView({ block, inline: 'nearest', behavior: smooth });
  };
  if (!cur || cur === document.body || !all.includes(cur)) {
    const inView = all.filter((el) => { const r = el.getBoundingClientRect(); return r.bottom > 0 && r.top < innerHeight; });
    const first = (inView.length ? inView : all).sort((a, b) => a.getBoundingClientRect().top - b.getBoundingClientRect().top || a.getBoundingClientRect().left - b.getBoundingClientRect().left)[0];
    go(first, 'nearest');
    return true;
  }
  const c = cur.getBoundingClientRect();
  const cx = (c.left + c.right) / 2;
  const cy = (c.top + c.bottom) / 2;
  /* Row by row: among the elements in that direction, take the nearest band (the next row of cards, the hero's
     buttons), then the one best lined up with where focus is now. */
  const found: { el: HTMLElement; primary: number; ortho: number; align: number }[] = [];
  for (const el of all) {
    if (el === cur || el.contains(cur) || cur.contains(el)) continue;
    const r = el.getBoundingClientRect();
    const rx = (r.left + r.right) / 2;
    const ry = (r.top + r.bottom) / 2;
    let primary: number;
    let ortho: number;
    if (dir === 'right') { if (r.left < c.right - 4 && rx <= cx + 4) continue; primary = Math.max(0, r.left - c.right); ortho = Math.max(0, r.top - c.bottom, c.top - r.bottom); }
    else if (dir === 'left') { if (r.right > c.left + 4 && rx >= cx - 4) continue; primary = Math.max(0, c.left - r.right); ortho = Math.max(0, r.top - c.bottom, c.top - r.bottom); }
    else if (dir === 'down') { if (r.top < c.bottom - 4 && ry <= cy + 4) continue; primary = Math.max(0, r.top - c.bottom); ortho = Math.max(0, r.left - c.right, c.left - r.right); }
    else { if (r.bottom > c.top + 4 && ry >= cy - 4) continue; primary = Math.max(0, c.top - r.bottom); ortho = Math.max(0, r.left - c.right, c.left - r.right); }
    found.push({ el, primary, ortho, align: dir === 'left' || dir === 'right' ? Math.abs(ry - cy) : Math.abs(rx - cx) });
  }
  if (!found.length) return false;
  const vertical = dir === 'up' || dir === 'down';
  // Sideways moves stay in their row; at its end nothing happens, as on a TV.
  const pool0 = vertical ? found : found.filter((f) => f.ortho === 0);
  if (!pool0.length) return false;
  const nearest = Math.min(...pool0.map((f) => f.primary));
  const pool = pool0.filter((f) => f.primary <= nearest + (vertical ? 56 : 24));
  let best: HTMLElement | null = null;
  let bestScore = Infinity;
  for (const f of pool) {
    const score = f.ortho * 3 + f.align * 0.15 + f.primary * 0.2;
    if (score < bestScore) { bestScore = score; best = f.el; }
  }
  if (!best) return false;
  go(best, dir === 'up' || dir === 'down' ? 'center' : 'nearest');
  return true;
}

const ARROWS: Record<string, Dir> = { ArrowUp: 'up', ArrowDown: 'down', ArrowLeft: 'left', ArrowRight: 'right' };

/* For a field that keeps the arrow keys (the bar's search box): the remote moves on from it like from anything else. */
export function moveFocusFrom(dir: Dir): boolean {
  return moveFocus(dir);
}

/* ── The "?" overlay ── */

export const shortcutsOpen = createStore(false);

function onKeyDown(e: KeyboardEvent) {
  if (e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey) return;
  const target = e.target as Element | null;
  if (e.key === '?' && !editable(target) && bootState.get() === 'ready') {
    e.preventDefault();
    shortcutsOpen.set((o) => !o);
    return;
  }
  if (!tvActive.get() || onPlayer() || editable(target)) return;
  const dir = ARROWS[e.key];
  if (dir) {
    if (moveFocus(dir)) e.preventDefault();
    return;
  }
  // Remotes' Back key: leave the page (overlays close themselves on Escape before this sees it).
  if (e.key === 'Backspace' || e.key === 'BrowserBack' || e.key === 'GoBack' || e.key === 'Escape') {
    if (document.querySelector('.scrim, .popover')) return;
    e.preventDefault();
    back();
  }
}

/* ── Gamepad: d-pad or stick moves, A selects, B goes back ── */

let padRaf = 0;
const held: Record<string, number> = {};

function dispatchKey(key: string): boolean {
  const target = (document.activeElement as HTMLElement | null) ?? document.body;
  const ev = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true });
  target.dispatchEvent(ev);
  return ev.defaultPrevented;
}

function padAction(name: string) {
  idle.touch();
  if (saverOpen.get()) { saverOpen.set(false); return; }
  if (name in ARROWS) { dispatchKey(name); return; }
  if (name === 'A') {
    const el = document.activeElement as HTMLElement | null;
    if (el && el !== document.body) el.click();
    else if (onPlayer()) dispatchKey(' ');
    else moveFocus('down');
    return;
  }
  if (name === 'B') {
    if (document.querySelector('.scrim, .popover') || onPlayer()) { dispatchKey('Escape'); return; }
    back();
  }
}

function pollPads() {
  padRaf = 0;
  if (!deviceStore.get().gamepad) return;
  const pads = Array.from(navigator.getGamepads?.() ?? []).filter(Boolean) as Gamepad[];
  if (!pads.length) return;
  const now = performance.now();
  const pressed: Record<string, boolean> = {};
  for (const p of pads) {
    const b = (i: number) => !!p.buttons[i]?.pressed;
    const ax = p.axes[0] ?? 0;
    const ay = p.axes[1] ?? 0;
    pressed.ArrowUp ||= b(12) || ay < -0.6;
    pressed.ArrowDown ||= b(13) || ay > 0.6;
    pressed.ArrowLeft ||= b(14) || ax < -0.6;
    pressed.ArrowRight ||= b(15) || ax > 0.6;
    pressed.A ||= b(0);
    pressed.B ||= b(1);
  }
  for (const [name, down] of Object.entries(pressed)) {
    if (!down) { delete held[name]; continue; }
    const since = held[name];
    if (since === undefined) { held[name] = now; padAction(name); }
    else if (name in ARROWS && now - since > 420) { held[name] = now - 420 + 140; padAction(name); }
  }
  padRaf = requestAnimationFrame(pollPads);
}

function startPads() {
  if (!padRaf && deviceStore.get().gamepad && padConnected()) padRaf = requestAnimationFrame(pollPads);
}

/* ── Screensaver ── */

export const saverOpen = createStore(false);

const idle = {
  last: Date.now(),
  touch() { this.last = Date.now(); }
};

function mediaPlaying() {
  // The Home banner's muted trailer is decoration: it mustn't keep the screensaver away.
  return Array.from(document.querySelectorAll<HTMLMediaElement>('video, audio'))
    .some((m) => !m.paused && !m.ended && m.readyState > 2 && !m.closest('.hero-trailer'));
}

function idleTick() {
  const d = deviceStore.get();
  // Playback started without anyone touching this device (another app sent it a title): the picture comes first.
  if (saverOpen.get() && (onPlayer() || mediaPlaying() || bootState.get() !== 'ready')) { saverOpen.set(false); idle.touch(); return; }
  if (d.screensaver === 'off' || saverOpen.get() || bootState.get() !== 'ready' || onPlayer() || document.hidden) { if (!saverOpen.get()) idle.touch(); return; }
  if (mediaPlaying()) { idle.touch(); return; }
  if (Date.now() - idle.last >= Math.max(10, d.screensaverAfter) * 1000) saverOpen.set(true);
}

/* ── Start ── */

let started = false;

function applyDevice(d: DevicePrefs) {
  const el = root();
  el.classList.toggle('me-fast', d.fastAnimations);
  el.classList.toggle('me-smooth', d.smoothScrolling);
  const tv = layoutIsTv(d);
  el.classList.toggle('seedy-tv', tv);
  tvActive.set(tv || (d.gamepad && padConnected()));
  applyViewport(d);
  applyCustomCss(d.customCss);
  setSoft(d.softPlaceholders);
  applyAccent();
  startPads();
}

export function startPersonalEffects() {
  if (started) return;
  started = true;
  setAccentFilter((server) => {
    const mine = readCustom(CUSTOM.accent);
    if (mine && HEX.test(mine)) return mine;
    // "Ignore the server's CSS": Seedy's stock look instead of the admin's theme.
    return deviceStore.get().ignoreServerCss ? DEFAULT_BRANDING.accent : server;
  });
  applyDates();
  let lastDates = '';
  meStore.subscribe(() => {
    applyAccent();
    /* The profile's language, once the real profile has loaded (not the stand-in shown while booting). */
    if (bootState.get() === 'ready') applyLanguagePref(readCustom(CUSTOM.language));
    const k = readCustom(CUSTOM.dateLocale) + '|' + readCustom(CUSTOM.language);
    if (k !== lastDates) { lastDates = k; applyDates(); }
  });
  bootState.subscribe((st) => { if (st === 'ready') applyLanguagePref(readCustom(CUSTOM.language)); });
  deviceStore.subscribe(applyDevice);
  applyDevice(deviceStore.get());
  patchScrolling();

  window.addEventListener('keydown', onKeyDown);
  window.addEventListener('gamepadconnected', () => applyDevice(deviceStore.get()));
  window.addEventListener('gamepaddisconnected', () => applyDevice(deviceStore.get()));
  for (const ev of ['pointermove', 'pointerdown', 'keydown', 'wheel', 'touchstart', 'scroll']) {
    window.addEventListener(ev, () => idle.touch(), { capture: true, passive: true });
  }
  window.setInterval(idleTick, 1000);
}

/* ── Overlays ── */

export function PersonalHost() {
  const open = useStore(shortcutsOpen);
  const saver = useStore(saverOpen);
  const me = useStore(meStore);
  const loc = useLocation();
  useEffect(() => { shortcutsOpen.set(false); }, [loc.path]);
  const pb = me.prefs?.playback;
  return (
    <>
      <Sheet open={open} onClose={() => shortcutsOpen.set(false)} label={t('Keyboard shortcuts')} wide>
        <div class="sheet-head">
          <h2 class="sheet-title">{t('Keyboard shortcuts in the player')}</h2>
          <button type="button" class="btn icon-btn is-bare is-40" aria-label={t('Close')} onClick={() => shortcutsOpen.set(false)}><Icon name="close" size={18} /></button>
        </div>
        <ShortcutGrid back={pb?.seekBackSeconds} forward={pb?.seekForwardSeconds} />
        <p class="me-foot">{t('Press ? anywhere in Seedy to see these.')}</p>
      </Sheet>
      {saver && <Screensaver />}
    </>
  );
}

function Screensaver() {
  const d = useStore(deviceStore);
  const ref = useRef<HTMLDivElement>(null);
  const [items, setItems] = useState<BaseItem[]>([]);
  const [idx, setIdx] = useState(0);
  const [pos, setPos] = useState({ x: 50, y: 50 });
  const mode = d.screensaver;

  useEffect(() => {
    // Focus comes back where it was on waking (on a TV that's the card the remote was on).
    const prev = document.activeElement as HTMLElement | null;
    ref.current?.focus();
    const start = { x: -1, y: -1 };
    const close = (e: Event) => {
      if (e.type === 'pointermove') {
        const p = e as PointerEvent;
        if (start.x < 0) { start.x = p.clientX; start.y = p.clientY; return; }
        if (Math.hypot(p.clientX - start.x, p.clientY - start.y) < 12) return;
      }
      // The input that wakes Seedy doesn't also act on the page underneath (a tap's click comes after the overlay is gone).
      if (e.type === 'keydown' || e.type === 'pointerdown' || e.type === 'click') { e.preventDefault(); e.stopPropagation(); }
      if (e.type === 'pointerdown') {
        const swallow = (c: Event) => { c.preventDefault(); c.stopPropagation(); };
        window.addEventListener('click', swallow, { capture: true, once: true });
        window.setTimeout(() => window.removeEventListener('click', swallow, { capture: true }), 600);
      }
      idle.touch();
      saverOpen.set(false);
    };
    const evs = ['keydown', 'pointerdown', 'pointermove', 'wheel', 'touchstart', 'click'];
    evs.forEach((ev) => window.addEventListener(ev, close, { capture: true }));
    return () => {
      evs.forEach((ev) => window.removeEventListener(ev, close, { capture: true }));
      if (prev && prev !== document.body && prev.isConnected) prev.focus({ preventScroll: true });
    };
  }, []);

  useEffect(() => {
    if (mode !== 'backdrops') return;
    getItems({ includeItemTypes: ['Movie', 'Series'], sortBy: ['Random'], limit: 40, imageTypeLimit: 1, enableImageTypes: ['Backdrop', 'Logo'], fields: ['Overview'], enableTotalRecordCount: false })
      .then((r) => setItems(r.Items.filter((i) => i.BackdropImageTags?.length)), () => setItems([]));
  }, [mode]);

  useEffect(() => {
    const every = mode === 'backdrops' ? Math.max(3, d.backdropEvery) : 9;
    const timer = window.setInterval(() => {
      if (mode === 'backdrops') setIdx((i) => i + 1);
      else setPos({ x: 15 + Math.random() * 70, y: 15 + Math.random() * 70 });
    }, every * 1000);
    return () => window.clearInterval(timer);
  }, [mode, d.backdropEvery]);

  const brand = useStore(brandingStore);
  const n = items.length;
  return (
    <div ref={ref} class={'me-saver is-' + mode} tabIndex={-1} role="dialog" aria-label={t('Screensaver. Press any key to wake Seedy.')}>
      {mode === 'backdrops' && n > 0 && [idx - 1, idx, idx + 1].filter((p) => p >= 0).map((p) => {
        // The previous slide fades out under the current one; the next one loads unseen.
        const it = items[p % n];
        const logo = logoUrl(it, 600);
        return (
          <div key={'s' + p} class={'me-saver-slide' + (p === idx ? ' is-on' : '')} style={{ zIndex: p === idx ? 2 : 1 }} aria-hidden={p !== idx}>
            <img class="me-saver-art" src={backdropUrl(it, 1920)} alt="" />
            <div class="me-saver-shade" />
            <div class="me-saver-title">
              {logo ? <img class="me-saver-logo" src={logo} alt={it.Name} /> : <span>{it.Name}</span>}
            </div>
          </div>
        );
      })}
      {mode === 'logo' || n === 0 ? (
        <div class="me-saver-mark" style={{ left: pos.x + '%', top: pos.y + '%' }}>
          <BrandMark size={72} />
          <span>{brand.name || 'Seedy'}</span>
        </div>
      ) : (
        <div class="me-saver-corner"><BrandMark size={28} /><span>{brand.name || 'Seedy'}</span></div>
      )}
    </div>
  );
}
