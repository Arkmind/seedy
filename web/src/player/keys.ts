/* Player keyboard map, as Settings · Controls lists it: Space/K play-pause, J/← and L/→ seek, ↑/↓ volume, M mute,
   F full screen, C subtitles, N next, S skip (intro, recap, credits…), Esc closes a panel, else the player.
   A remote's Back key closes a panel, then leaves full screen or hides the controls — or leaves the player when
   Settings · Controls says Back closes it. */

export type KeyAction = 'toggle' | 'back' | 'forward' | 'volUp' | 'volDown' | 'mute' | 'fullscreen' | 'subtitles' | 'next' | 'skip' | 'escape' | 'remoteBack';

const TYPING = /^(INPUT|TEXTAREA|SELECT)$/;

export function keyAction(e: KeyboardEvent): KeyAction | null {
  if (e.ctrlKey || e.metaKey || e.altKey || e.defaultPrevented) return null;
  const t = e.target as HTMLElement | null;
  const tag = t?.tagName ?? '';
  const isRange = tag === 'INPUT' && (t as HTMLInputElement).type === 'range';
  if (t?.isContentEditable || (TYPING.test(tag) && !isRange)) return e.key === 'Escape' ? 'escape' : null;
  /* Sliders that own their arrow keys (volume). */
  if (isRange && t?.dataset.keys === 'native' && e.key.startsWith('Arrow')) return null;
  /* Buttons and links keep their own Space/Enter. */
  const onControl = tag === 'BUTTON' || tag === 'A' || t?.getAttribute('role') === 'switch' || t?.getAttribute('role') === 'tab';
  switch (e.key) {
    case ' ':
    case 'Spacebar':
      return onControl ? null : 'toggle';
    case 'k': case 'K': case 'MediaPlayPause':
      return 'toggle';
    case 'j': case 'J': case 'ArrowLeft':
      return 'back';
    case 'l': case 'L': case 'ArrowRight':
      return 'forward';
    case 'ArrowUp':
      return 'volUp';
    case 'ArrowDown':
      return 'volDown';
    case 'm': case 'M':
      return 'mute';
    case 'f': case 'F':
      return 'fullscreen';
    case 'c': case 'C':
      return 'subtitles';
    case 'n': case 'N': case 'MediaTrackNext':
      return 'next';
    case 's': case 'S':
      return 'skip';
    case 'Escape':
      return 'escape';
    case 'GoBack':
    case 'BrowserBack':
      return 'remoteBack';
    default:
      // webOS (461) and Tizen (10009) remotes' Back key.
      return e.keyCode === 461 || e.keyCode === 10009 ? 'remoteBack' : null;
  }
}

/* Whether the viewer is driving with a keyboard or remote (then overlays take focus for them). */
let keyboardMode = false;
if (typeof window !== 'undefined') {
  window.addEventListener('keydown', (e) => { if (!e.ctrlKey && !e.metaKey) keyboardMode = true; }, true);
  window.addEventListener('pointerdown', () => { keyboardMode = false; }, true);
  window.addEventListener('pointermove', (e) => { if (e.movementX || e.movementY) keyboardMode = false; }, true);
}
export const usingKeyboard = () => keyboardMode;
