import { formatNumber, t, tn } from '../lib/i18n';
import { useStore } from '../lib/store';
import { MenuButton, MenuItem } from '../ui/overlay';
import { audioStore, chapterAt, setSleep, setSpeed, timeStore } from './engine';
import { AIcon } from './icons';

/* Audiobook speed and sleep timer: the mini bar's compact buttons and the Audiobook player's chips. */

export const SPEEDS = [0.75, 0.9, 1, 1.1, 1.2, 1.25, 1.5, 1.75, 2];
export const speedLabel = (v: number) => formatNumber(v, { maximumFractionDigits: 2 }) + '×';
const SLEEP_MINUTES = [5, 10, 15, 30, 45, 60];

export function SpeedButton({ variant }: { variant: 'bar' | 'chip' }) {
  const s = useStore(audioStore);
  return (
    <MenuButton
      width={220}
      placement={variant === 'bar' ? 'above' : 'below'}
      align={variant === 'bar' ? 'end' : 'start'}
      button={({ ref, open, toggle }) => (
        <button ref={ref} type="button" aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={toggle}
          class={variant === 'bar' ? 'ap-bar-speed' : 'apb-chip'}
          data-tip={variant === 'bar' ? t('Playback speed') : undefined}
          aria-label={variant === 'bar' ? t('Playback speed {speed}', { speed: speedLabel(s.speed) }) : undefined}>
          {variant === 'bar' ? speedLabel(s.speed) : <>{t('Speed')} <b>{speedLabel(s.speed)}</b></>}
        </button>
      )}
    >
      {(close) => (
        <>
          <span class="menu-label">{t('Playback speed')}</span>
          {SPEEDS.map((v) => <MenuItem key={v} label={speedLabel(v)} sub={v === 1 ? t('Normal') : undefined} pressed={Math.abs(s.speed - v) < 0.001} onSelect={() => { close(); setSpeed(v); }} />)}
        </>
      )}
    </MenuButton>
  );
}

export function sleepLabel(): string {
  const s = audioStore.get();
  if (s.sleep?.kind === 'chapter') return t('Sleep at the end of the chapter');
  if (s.sleep?.kind === 'time') {
    const min = Math.max(1, Math.ceil((s.sleep.at - Date.now()) / 60_000));
    return tn(min, 'Sleep in {n} min', 'Sleep in {n} min');
  }
  return t('Sleep timer');
}

export function SleepButton({ variant }: { variant: 'bar' | 'chip' }) {
  const s = useStore(audioStore);
  useStore(timeStore);
  const on = !!s.sleep;
  const ch = chapterAt(s.book, timeStore.get().position);
  const label = sleepLabel();
  return (
    <MenuButton
      width={260}
      placement={variant === 'bar' ? 'above' : 'below'}
      align={variant === 'bar' ? 'end' : 'start'}
      button={({ ref, open, toggle }) => (
        <button ref={ref} type="button" aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={toggle}
          class={(variant === 'bar' ? 'ap-bar-btn ap-bar-sleep' : 'apb-chip apb-sleep') + (on ? ' is-on' : '')}
          data-tip={variant === 'bar' ? (on ? t('Sleep timer: {state}', { state: label }) : t('Sleep timer')) : undefined}
          aria-label={variant === 'bar' ? (on ? t('Sleep timer: {state}', { state: label }) : t('Sleep timer')) : undefined}>
          <AIcon name="moon" size={variant === 'bar' ? 18 : 17} />
          {variant === 'chip' && label}
        </button>
      )}
    >
      {(close) => (
        <>
          <span class="menu-label">{t('Sleep timer')}</span>
          <MenuItem label={t('Off')} pressed={!on} onSelect={() => { close(); setSleep(null); }} />
          {ch && (
            <MenuItem label={t('At the end of the chapter')} sub={ch.name} pressed={s.sleep?.kind === 'chapter'}
              onSelect={() => { close(); setSleep({ kind: 'chapter', index: ch.index }); }} />
          )}
          {SLEEP_MINUTES.map((m) => (
            <MenuItem key={m} label={tn(m, 'In {n} minute', 'In {n} minutes')} pressed={s.sleep?.kind === 'time' && s.sleep.minutes === m}
              onSelect={() => { close(); setSleep({ kind: 'time', at: Date.now() + m * 60_000, minutes: m }); }} />
          ))}
        </>
      )}
    </MenuButton>
  );
}
