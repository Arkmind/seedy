import { t } from '../lib/i18n';
import { saveLocal } from '../lib/store';
import { toast } from '../ui/overlay';

/* Volume, mute and picture in picture, the same in the video and live players. */

interface Volume { volume: number; muted: boolean }

export function setVolume(m: Volume, v: number) {
  m.volume = Math.max(0, Math.min(1, v));
  m.muted = v <= 0;
}

export function toggleMute(m: Volume) {
  if (m.muted || m.volume === 0) {
    m.muted = false;
    if (m.volume === 0) m.volume = 0.5;
  } else m.muted = true;
}

/* The volume carries over to the next title and the other player. */
export function saveVolume(m: Volume) {
  saveLocal('seedy.volume', m.volume);
  saveLocal('seedy.muted', m.muted);
}

export const pipSupported = () => (document as Document & { pictureInPictureEnabled?: boolean }).pictureInPictureEnabled === true;

export function togglePip(video?: HTMLVideoElement | null) {
  if (document.pictureInPictureElement) void document.exitPictureInPicture().catch(() => undefined);
  else void video?.requestPictureInPicture?.().catch(() => toast(t("Picture in picture isn't available"), 'error'));
}
