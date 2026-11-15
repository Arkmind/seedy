import { useState } from 'preact/hooks';
import { sendPlayCommand } from '../api/jellyfin';
import type { SessionInfo } from '../api/types';
import { listCastTargets } from '../lib/cast';
import { t } from '../lib/i18n';
import { MenuItem, toast } from './overlay';

/* The other Jellyfin apps a "Play on" menu offers, looked up each time it opens (null while looking). */
export function useCastSessions() {
  const [sessions, setSessions] = useState<SessionInfo[] | null>(null);
  const load = () => {
    setSessions(null);
    listCastTargets().then(setSessions, () => setSessions([]));
  };
  return { sessions, load };
}

/* The body of a "Play on" menu: this device, then each other app. [target] ticks the device playing now; leave it
   out for a menu that only hands playback over. */
export function CastTargets({ sessions, target, hereSub, herePressed, onHere, onPick }: {
  sessions: SessionInfo[] | null;
  target?: SessionInfo | null;
  hereSub: string;
  herePressed: boolean;
  onHere: () => void;
  onPick: (s: SessionInfo) => void;
}) {
  return (
    <>
      <span class="menu-label">{t('Play on')}</span>
      <MenuItem label={t('This device')} sub={hereSub} pressed={herePressed} onSelect={onHere} />
      {sessions === null && <span class="menu-item is-muted" aria-busy="true">{t('Looking for devices…')}</span>}
      {sessions?.length === 0 && <span class="menu-item is-muted">{t('No other Jellyfin apps are open')}</span>}
      {sessions?.map((s) => (
        <MenuItem
          key={s.Id}
          label={s.DeviceName || s.Client || t('Device')}
          sub={[s.Client, s.UserName].filter(Boolean).join(' · ')}
          pressed={target === undefined ? undefined : target?.Id === s.Id}
          onSelect={() => onPick(s)}
        />
      ))}
    </>
  );
}

/* Hands [ids] to another app, starting [ticks] in, and says how it went. */
export async function sendToDevice(target: SessionInfo, ids: string[], ticks: number): Promise<boolean> {
  try {
    await sendPlayCommand(target.Id, ids, ticks);
  } catch {
    toast(target.DeviceName ? t("Couldn't reach {device}", { device: target.DeviceName }) : t("Couldn't reach that device"), 'error');
    return false;
  }
  const name = target.DeviceName || target.Client;
  toast(name ? t('Playing on {device}', { device: name }) : t('Playing on the other device'));
  return true;
}
