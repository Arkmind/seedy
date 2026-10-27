import type { ComponentChildren, JSX } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { pct } from '../../../lib/format';
import { t } from '../../../lib/i18n';
import { tc } from '../parts';
import {
  COLUMN, SIZE_MAX, SIZE_MIN, THEMES, type ComicSettings, type EpubSettings, type Fit, type FontChoice, type Layout, type Margins,
  type PageTheme, type Spacing, type Direction
} from './settings';

/* The reader's frame (Book reader board): the top bar, the page-turn arrows, the Reading settings panel, and the
   scrubber with its three status texts. Shared by the EPUB and comic readers. */

const paths = {
  back: <path d="M15 6l-6 6 6 6" />,
  next: <path d="M9 6l6 6-6 6" />,
  toc: <><path d="M9 6h11" /><path d="M9 12h11" /><path d="M9 18h11" /><path d="M4 6h.01" /><path d="M4 12h.01" /><path d="M4 18h.01" /></>,
  search: <><circle cx="11" cy="11" r="7" /><path d="M20 20l-4-4" /></>,
  bookmark: <path d="M6 3h12v18l-6-4-6 4z" />,
  type: <><path d="M4 18L9 6l5 12" /><path d="M6 14h6" /><path d="M15 18l3-7 3 7" /><path d="M16 16h4" /></>,
  full: <><path d="M15 3h6v6" /><path d="M9 21H3v-6" /><path d="M21 3l-7 7" /><path d="M3 21l7-7" /></>,
  unfull: <><path d="M4 14h6v6" /><path d="M20 10h-6V4" /><path d="M14 10l7-7" /><path d="M3 21l7-7" /></>,
  close: <><path d="M6 6l12 12" /><path d="M18 6L6 18" /></>,
  download: <><path d="M12 3v12" /><path d="M7 10l5 5 5-5" /><path d="M5 20h14" /></>,
  file: <><path d="M6 3h8l4 4v14H6z" /><path d="M14 3v4h4" /></>
};
export type RdIconName = keyof typeof paths;

export function RdIcon({ name, size = 20, filled }: { name: RdIconName; size?: number; filled?: boolean }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" class="rd-ic" style={filled ? { fill: 'currentColor' } : undefined}>
      {paths[name]}
    </svg>
  );
}

export function TopBar({ title, sub, onBack, children }: { title: string; sub?: string; onBack: () => void; children?: ComponentChildren }) {
  return (
    <header class="rd-top">
      <button type="button" class="rd-btn is-back" aria-label={t('Back to the book')} onClick={onBack}><RdIcon name="back" size={20} /></button>
      <span class="rd-top-text">
        <span class="rd-top-title">{title}</span>
        {sub && <span class="rd-top-sub">{sub}</span>}
      </span>
      {children}
    </header>
  );
}

export function BarButton({ icon, label, onClick, pressed, expanded, filled, id }: {
  icon: RdIconName; label: string; onClick: () => void; pressed?: boolean; expanded?: boolean; filled?: boolean; id?: string;
}) {
  return (
    <button
      id={id}
      type="button"
      class={'rd-btn' + (expanded || pressed ? ' is-on' : '')}
      aria-label={label}
      aria-pressed={pressed === undefined ? undefined : pressed ? 'true' : 'false'}
      aria-expanded={expanded === undefined ? undefined : expanded ? 'true' : 'false'}
      onClick={onClick}
    >
      <RdIcon name={icon} filled={filled} />
    </button>
  );
}

/* Full screen follows the document; Esc leaves it on its own. */
export function useFullscreen(): [boolean, () => void] {
  const [on, setOn] = useState(!!document.fullscreenElement);
  useEffect(() => {
    const fn = () => setOn(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', fn);
    return () => document.removeEventListener('fullscreenchange', fn);
  }, []);
  const toggle = () => {
    if (document.fullscreenElement) void document.exitFullscreen().catch(() => undefined);
    else void document.documentElement.requestFullscreen?.().catch(() => undefined);
  };
  return [on, toggle];
}

export function TurnButtons({ onPrev, onNext, prevDisabled, nextDisabled, rtl }: { onPrev: () => void; onNext: () => void; prevDisabled?: boolean; nextDisabled?: boolean; rtl?: boolean }) {
  return (
    <>
      <button type="button" class="rd-turn is-prev" aria-label={rtl ? t('Next page') : t('Previous page')} disabled={rtl ? nextDisabled : prevDisabled} onClick={rtl ? onNext : onPrev}><RdIcon name="back" size={24} /></button>
      <button type="button" class="rd-turn is-next" aria-label={rtl ? t('Previous page') : t('Next page')} disabled={rtl ? prevDisabled : nextDisabled} onClick={rtl ? onPrev : onNext}><RdIcon name="next" size={24} /></button>
    </>
  );
}

/* ───────── Scrubber ───────── */

export function Scrubber({ page, total, ticks, onCommit, valueText, status, rtl }: {
  page: number; total: number; ticks: number[]; onCommit: (page: number) => void;
  valueText: (page: number) => string; status: [ComponentChildren, ComponentChildren, ComponentChildren]; rtl?: boolean;
}) {
  const [drag, setDrag] = useState<number | null>(null);
  const shown = drag ?? page;
  const fraction = total > 1 ? (shown - 1) / (total - 1) : total === 1 ? 1 : 0;
  const commit = (v: number) => { setDrag(null); if (v !== page) onCommit(v); };
  return (
    <footer class="rd-foot">
      <div class={'rd-scrub' + (rtl ? ' is-rtl' : '')}>
        <span class="rd-track">
          <span class="rd-fill" style={{ width: pct(fraction) }} />
          {ticks.map((f, i) => <span key={i} aria-hidden="true" class="rd-tick" style={{ left: pct(f) }} />)}
          <span aria-hidden="true" class="rd-knob" style={{ left: pct(fraction) }} />
        </span>
        <input
          type="range"
          min={1}
          max={Math.max(1, total)}
          step={1}
          value={shown}
          aria-label={t('Position in the book')}
          aria-valuetext={valueText(shown)}
          onInput={(e) => setDrag(Number(e.currentTarget.value))}
          onChange={(e) => commit(Number(e.currentTarget.value))}
          onKeyDown={(e) => e.stopPropagation()}
        />
        {drag !== null && <span class="rd-scrub-tip" style={{ left: pct(fraction) }}>{valueText(drag)}</span>}
      </div>
      <div class="rd-status">
        <span>{status[0]}</span>
        <span>{status[1]}</span>
        <span>{status[2]}</span>
      </div>
    </footer>
  );
}

/* ───────── Reading settings ───────── */

function Seg<T extends string>({ label, value, options, onChange }: { label: string; value: T; options: { value: T; label: string }[]; onChange: (v: T) => void }) {
  return (
    <div class="rs-row">
      <span class="rs-name">{label}</span>
      <div role="radiogroup" aria-label={label} class="rs-seg">
        {options.map((o) => (
          <button key={o.value} type="button" role="radio" aria-checked={o.value === value ? 'true' : 'false'} class="rs-seg-btn" onClick={() => onChange(o.value)}>{o.label}</button>
        ))}
      </div>
    </div>
  );
}

function ThemePicker({ value, onChange }: { value: PageTheme; onChange: (v: PageTheme) => void }) {
  return (
    <div class="rs-group">
      <span class="rs-label">{t('Page')}</span>
      <div role="radiogroup" aria-label={t('Page colour')} class="rs-themes">
        {(Object.keys(THEMES) as PageTheme[]).map((k) => (
          <button key={k} type="button" role="radio" aria-checked={k === value ? 'true' : 'false'} class="rs-theme" style={{ background: THEMES[k].bg, color: THEMES[k].ink }} onClick={() => onChange(k)}>
            Aa<span>{t(THEMES[k].name)}</span>
          </button>
        ))}
      </div>
    </div>
  );
}

/* The panel closes on Esc or a click outside it (but not on its own button, which toggles it). */
export function usePanelDismiss(open: boolean, close: () => void, ref: { current: HTMLElement | null }, anchorId?: string) {
  const fn = useRef(close);
  fn.current = close;
  useEffect(() => {
    if (!open) return;
    const down = (e: PointerEvent) => {
      const el = e.target as Node;
      if (ref.current?.contains(el)) return;
      if (anchorId && document.getElementById(anchorId)?.contains(el)) return;
      fn.current();
    };
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.stopPropagation(); fn.current(); } };
    document.addEventListener('pointerdown', down, true);
    document.addEventListener('keydown', key, true);
    return () => { document.removeEventListener('pointerdown', down, true); document.removeEventListener('keydown', key, true); };
  }, [open]);
}

export function EpubSettingsPanel({ value, onChange, onClose }: { value: EpubSettings; onChange: (next: Partial<EpubSettings>) => void; onClose: () => void }) {
  const ref = useRef<HTMLElement>(null);
  usePanelDismiss(true, onClose, ref, 'rd-settings-btn');
  useEffect(() => { ref.current?.querySelector<HTMLElement>('[aria-checked="true"]')?.focus({ preventScroll: true }); }, []);
  const fonts: { value: FontChoice; label: string; style: JSX.CSSProperties }[] = [
    { value: 'serif', label: 'Noto Serif', style: { fontFamily: "'Noto Serif Variable', 'Noto Serif', Georgia, serif" } },
    { value: 'sans', label: 'Noto Sans', style: { fontFamily: 'var(--font)' } },
    { value: 'book', label: t('Book’s own'), style: {} }
  ];
  return (
    <section ref={ref} role="dialog" aria-labelledby="rs-h" class="rs-panel">
      <h2 id="rs-h" class="rs-title">{t('Reading settings')}</h2>
      <ThemePicker value={value.theme} onChange={(theme) => onChange({ theme })} />
      <div class="rs-group">
        <span class="rs-label">{t('Font')}</span>
        <div role="radiogroup" aria-label={t('Font')} class="rs-fonts">
          {fonts.map((f) => (
            <button key={f.value} type="button" role="radio" aria-checked={f.value === value.font ? 'true' : 'false'} class="rs-font" style={f.style} onClick={() => onChange({ font: f.value })}>{f.label}</button>
          ))}
        </div>
      </div>
      <div class="rs-row">
        <span id="rs-size" class="rs-name">{t('Text size')}</span>
        <div role="group" aria-labelledby="rs-size" class="rs-seg is-size">
          <button type="button" class="rs-size-btn" aria-label={t('Smaller text')} disabled={value.size <= SIZE_MIN} onClick={() => onChange({ size: Math.max(SIZE_MIN, value.size - 1) })}>A−</button>
          <output class="rs-size">{t('{n} px', { n: value.size })}</output>
          <button type="button" class="rs-size-btn is-big" aria-label={t('Bigger text')} disabled={value.size >= SIZE_MAX} onClick={() => onChange({ size: Math.min(SIZE_MAX, value.size + 1) })}>A+</button>
        </div>
      </div>
      <Seg<Spacing> label={t('Line spacing')} value={value.spacing} onChange={(spacing) => onChange({ spacing })}
        options={[{ value: 'tight', label: t('Tight') }, { value: 'normal', label: tc('Normal', 'spacing') }, { value: 'airy', label: t('Airy') }]} />
      <Seg<Margins> label={t('Margins')} value={value.margins} onChange={(margins) => onChange({ margins })}
        options={[{ value: 'narrow', label: t('Narrow') }, { value: 'normal', label: tc('Normal', 'margins') }, { value: 'wide', label: t('Wide') }]} />
      <Seg<Layout> label={t('Layout')} value={value.layout} onChange={(layout) => onChange({ layout })}
        options={[{ value: 'one', label: t('One page') }, { value: 'two', label: t('Two pages') }, { value: 'scroll', label: t('Scroll') }]} />
      <span class="rs-note">{t('Saved for this book on every device. Your place syncs through the server.')}</span>
    </section>
  );
}

export function ComicSettingsPanel({ value, onChange, onClose }: { value: ComicSettings; onChange: (next: Partial<ComicSettings>) => void; onClose: () => void }) {
  const ref = useRef<HTMLElement>(null);
  usePanelDismiss(true, onClose, ref, 'rd-settings-btn');
  useEffect(() => { ref.current?.querySelector<HTMLElement>('[aria-checked="true"]')?.focus({ preventScroll: true }); }, []);
  return (
    <section ref={ref} role="dialog" aria-labelledby="rs-h" class="rs-panel">
      <h2 id="rs-h" class="rs-title">{t('Reading settings')}</h2>
      <ThemePicker value={value.theme} onChange={(theme) => onChange({ theme })} />
      <Seg<Layout> label={t('Layout')} value={value.layout} onChange={(layout) => onChange({ layout })}
        options={[{ value: 'one', label: t('One page') }, { value: 'two', label: t('Two pages') }, { value: 'scroll', label: t('Scroll') }]} />
      <Seg<Fit> label={t('Fit')} value={value.fit} onChange={(fit) => onChange({ fit })}
        options={[{ value: 'screen', label: t('Screen') }, { value: 'width', label: t('Width') }]} />
      <Seg<Direction> label={t('Reading')} value={value.direction} onChange={(direction) => onChange({ direction })}
        options={[{ value: 'ltr', label: t('Left to right') }, { value: 'rtl', label: t('Right to left') }]} />
      <span class="rs-note">{t('Saved for this book on every device. Your place syncs through the server.')}</span>
    </section>
  );
}

export { COLUMN };

/* A side panel under the top bar (Contents, Search). */
export function SidePanel({ title, onClose, children, anchorId, label }: { title: ComponentChildren; label: string; onClose: () => void; children: ComponentChildren; anchorId: string }) {
  const ref = useRef<HTMLElement>(null);
  usePanelDismiss(true, onClose, ref, anchorId);
  return (
    <section ref={ref} role="dialog" aria-label={label} class="rs-panel is-side">
      <div class="rs-side-head">
        <h2 class="rs-title">{title}</h2>
        <button type="button" class="rd-btn is-sm" aria-label={t('Close')} onClick={onClose}><RdIcon name="close" size={18} /></button>
      </div>
      {children}
    </section>
  );
}

/* Loading and "can't open" states inside the reader frame. */
export function ReaderMessage({ title, text, action }: { title: string; text?: ComponentChildren; action?: ComponentChildren }) {
  return (
    <div class="rd-message" role="status">
      <span aria-hidden="true" class="rd-message-icon"><RdIcon name="file" size={26} /></span>
      <h2>{title}</h2>
      {text && <p>{text}</p>}
      {action}
    </div>
  );
}
