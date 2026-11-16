import { post } from '../api/client';
import { toast } from '../ui/overlay';
import { on } from './remote';

/* What this tab tells the phones controlling it (Seedy's phone remote, "Remote for a computer"): whether the player
   fills the screen and whether it's in its mini player (picture in picture). Jellyfin's play state doesn't say. */

let last = '';

export function reportScreen() {
  const state = { fullscreen: !!document.fullscreenElement, miniPlayer: !!document.pictureInPictureElement };
  const key = JSON.stringify(state);
  if (key === last) return;
  last = key;
  post('/Seedy/Remote/State', state).catch(() => { last = ''; });
}

let started = false;

export function startScreenReports() {
  if (started) return;
  started = true;
  document.addEventListener('fullscreenchange', reportScreen);
  // Picture in picture's events fire on the video and don't bubble.
  document.addEventListener('enterpictureinpicture', reportScreen, true);
  document.addEventListener('leavepictureinpicture', reportScreen, true);
  on('open', () => { last = ''; reportScreen(); });
}

/* Browsers open full screen and picture in picture only from a click on the page. When a phone asks for one, the
   next click anywhere does it (and goes no further), for 15 s. */
let armed: (() => void) | null = null;

export function afterClick(run: () => void, message: string) {
  armed?.();
  const swallow = (e: Event) => { e.stopPropagation(); e.preventDefault(); };
  const fire = (e: Event) => {
    swallow(e);
    disarm();
    window.addEventListener('click', swallow, { capture: true, once: true });
    run();
  };
  const timer = window.setTimeout(() => disarm(), 15_000);
  const disarm = () => {
    window.clearTimeout(timer);
    window.removeEventListener('pointerdown', fire, true);
    if (armed === disarm) armed = null;
  };
  window.addEventListener('pointerdown', fire, true);
  armed = disarm;
  toast(message, 'ok', 15_000);
}
