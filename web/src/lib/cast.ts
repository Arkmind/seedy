import { getControllableSessions } from '../api/jellyfin';
import { deviceId } from '../api/client';
import type { SessionInfo } from '../api/types';
import { createStore } from './store';

/* "Cast to a device" picks another Jellyfin session as the play target, the way Jellyfin's own
   apps hand playback to a TV. While a target is set, Play sends the title there instead. */

export const castTarget = createStore<SessionInfo | null>(null);

export async function listCastTargets(): Promise<SessionInfo[]> {
  const sessions = await getControllableSessions();
  const mine = deviceId();
  return sessions.filter((s) => s.DeviceId !== mine && s.SupportsRemoteControl !== false && (s.PlayableMediaTypes?.length ?? 1) > 0);
}

