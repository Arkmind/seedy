import { useEffect, useRef } from 'preact/hooks';
import { t } from '../lib/i18n';
import { useStore } from '../lib/store';
import { artistsOf } from './parts';
import { audioStore, openPlayer, timeStore } from './engine';
import { AIcon } from './icons';
import { LyricsOnly, UpNext } from './NowPlaying';

export type BarPanelKind = 'lyrics' | 'queue';

/* The player bar's Lyrics and Up next panels: a look at them above the bar without leaving the page, with the full
   player one click away. Clicking elsewhere or Escape closes them; the bar's own buttons (data-bar-panel) toggle. */
export function BarPanel({ kind, onClose }: { kind: BarPanelKind; onClose: () => void }) {
  const s = useStore(audioStore);
  const time = useStore(timeStore);
  const ref = useRef<HTMLDivElement>(null);
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  useEffect(() => {
    const down = (e: PointerEvent) => {
      const el = e.target as Element | null;
      if (!el || ref.current?.contains(el) || el.closest('[data-bar-panel], .popover')) return;
      closeRef.current();
    };
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape' && !e.defaultPrevented) { e.preventDefault(); closeRef.current(); } };
    document.addEventListener('pointerdown', down, true);
    document.addEventListener('keydown', key);
    return () => {
      document.removeEventListener('pointerdown', down, true);
      document.removeEventListener('keydown', key);
    };
  }, []);
  const tk = s.current?.track;
  if (!tk) return null;
  const actions = (
    <span class="ap-pop-actions">
      <button type="button" class="ap-pop-btn" data-tip={t('Open the player')} aria-label={t('Open the player')}
        onClick={() => { onClose(); openPlayer(true, kind); }}>
        <AIcon name="expand" size={14} />
      </button>
      <button type="button" class="ap-pop-btn" data-tip={t('Close')} aria-label={t('Close')} onClick={onClose}>
        <AIcon name="close" size={14} stroke={2.4} />
      </button>
    </span>
  );
  return (
    <div ref={ref} role="dialog" aria-label={kind === 'queue' ? t('Up next') : t('Lyrics')} class={'ap-pop is-' + kind}>
      {kind === 'queue' ? <UpNext s={s} actions={actions} /> : (
        <>
          <div class="ap-pop-head">
            <span class="ap-pop-titles">
              <span class="ap-pop-title">{t('Lyrics')}</span>
              <span class="ap-pop-sub">{[tk.Name, artistsOf(tk)].filter(Boolean).join(' · ')}</span>
            </span>
            {actions}
          </div>
          <div class="ap-pop-lyrics"><LyricsOnly track={tk} position={time.position} /></div>
        </>
      )}
    </div>
  );
}
