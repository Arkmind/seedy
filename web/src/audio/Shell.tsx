import type { ComponentChildren } from 'preact';
import { useEffect, useLayoutEffect, useRef } from 'preact/hooks';
import { Link } from '../lib/router';
import { t } from '../lib/i18n';
import { audioStore, openPlayer, skip, togglePlay } from './engine';
import { AIcon } from './icons';
import { CastMenu } from './parts';

/* The full-screen players' frame (Now playing and Audiobook boards): the cover's colours glowing from the left,
   collapse on the left of the top bar, where it plays from in the middle, cast on the right. */
export function PlayerShell({ label, tint, at = '20% 30%', stops = [38, 80], overline, title, titleTo, extra, children }: {
  label: string; tint: [string, string]; at?: string; stops?: [number, number];
  overline: string; title: string; titleTo?: string; extra?: ComponentChildren; children: ComponentChildren;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const root = document.documentElement;
    root.classList.add('ap-open');
    return () => root.classList.remove('ap-open');
  }, []);
  useEffect(() => {
    const prev = document.activeElement as HTMLElement | null;
    ref.current?.focus({ preventScroll: true });
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey) return;
      // A menu open inside the player handles its own keys.
      if (document.querySelector('.popover')) return;
      if (e.key === 'Escape') { e.preventDefault(); openPlayer(false); return; }
      // Space plays and pauses, the arrows seek, unless a control has the keys.
      const el = e.target as HTMLElement | null;
      if (el && el.closest('button, a, input, select, textarea, [role="button"]')) return;
      const book = audioStore.get().mode === 'audiobook';
      if (e.key === ' ') { e.preventDefault(); togglePlay(); }
      else if (e.key === 'ArrowLeft') { e.preventDefault(); skip(book ? -30 : -10); }
      else if (e.key === 'ArrowRight') { e.preventDefault(); skip(book ? 30 : 10); }
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      prev?.focus?.({ preventScroll: true });
    };
  }, []);
  const bg = `radial-gradient(1000px 700px at ${at}, ${tint[0]} 0%, ${tint[1]} ${stops[0]}%, #0b0b0b ${stops[1]}%)`;
  return (
    <div ref={ref} role="dialog" aria-modal="true" aria-label={label} class="ap-full" tabIndex={-1}>
      <div aria-hidden="true" class="ap-full-bg" style={{ background: bg }} />
      <div class="ap-full-top">
        <button type="button" class="ap-full-btn ap-close" aria-label={t('Close the player')} onClick={() => openPlayer(false)}>
          <AIcon name="chevronDown" size={20} />
        </button>
        <span class="ap-full-from">
          <span class="ap-full-over">{overline}</span>
          {titleTo ? <Link to={titleTo} class="ap-full-name" onClick={() => openPlayer(false)}>{title}</Link> : <span class="ap-full-name">{title}</span>}
        </span>
        {extra}
        <CastMenu class="ap-full-btn" size={18} />
      </div>
      {children}
    </div>
  );
}
