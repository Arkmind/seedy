import { getControllableSessions } from '../api/jellyfin';
import { currentUserId, deviceId } from '../api/client';
import type { SessionInfo } from '../api/types';
import { createStore } from './store';

/* "Cast to a device" picks another Jellyfin session as the play target, the way Jellyfin's own
   apps hand playback to a TV. While a target is set, Play sends the title there instead. */

const norm = (id?: string | null) => (id ?? '').replace(/-/g, '').toLowerCase();

export const castTarget = createStore<SessionInfo | null>(null);

export async function listCastTargets(): Promise<SessionInfo[]> {
  const sessions = await getControllableSessions();
  const mine = deviceId();
  // Only screens signed in as this person: Seedy's server refuses commands to anyone else's.
  const me = norm(currentUserId());
  return sessions.filter((s) => !!me && norm(s.UserId) === me && s.DeviceId !== mine && s.SupportsRemoteControl !== false && (s.PlayableMediaTypes?.length ?? 1) > 0);
}

