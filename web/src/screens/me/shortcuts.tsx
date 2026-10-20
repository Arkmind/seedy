import { t } from '../../lib/i18n';
import './parts.css';

/* The player's keyboard shortcuts, as listed on the Controls board. Shared by Settings · Controls and the "?" overlay. */

export const SHORTCUTS: { what: string; keys: string[] }[] = [
  { what: 'Play or pause', keys: ['Space', 'K'] },
  { what: 'Back 10 s, forward 30 s', keys: ['J', 'L'] },
  { what: 'Seek 5 s', keys: ['←', '→'] },
  { what: 'Volume', keys: ['↑', '↓'] },
  { what: 'Mute', keys: ['M'] },
  { what: 'Full screen', keys: ['F'] },
  { what: 'Subtitles on or off', keys: ['C'] },
  { what: 'Next episode', keys: ['N'] },
  { what: 'Skip intro or credits', keys: ['S'] },
  { what: 'Close the player', keys: ['Esc'] }
];

const KEY_NAMES: Record<string, string> = { '←': 'Left arrow', '→': 'Right arrow', '↑': 'Up arrow', '↓': 'Down arrow', Esc: 'Escape' };

/* "Back 10 s, forward 30 s" follows the person's seek steps (Settings · Playback). */
export function shortcutLabel(what: string, back: number, forward: number) {
  return what === 'Back 10 s, forward 30 s' ? t('Back {back} s, forward {forward} s', { back, forward }) : t(what);
}

export function ShortcutGrid({ back = 10, forward = 30 }: { back?: number; forward?: number }) {
  return (
    <div class="me-keys">
      {SHORTCUTS.map((s) => (
        <div key={s.what} class="me-key-row">
          <span class="me-key-what">{shortcutLabel(s.what, back, forward)}</span>
          <span class="me-key-caps">
            {s.keys.map((k) => <kbd key={k} class="me-kbd" aria-label={KEY_NAMES[k] && t(KEY_NAMES[k])}>{k === 'Space' ? t('Space') : k}</kbd>)}
          </span>
        </div>
      ))}
    </div>
  );
}
