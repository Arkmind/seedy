import {
  getUtcTime, syncPlayBuffering, syncPlayJoin, syncPlayLeave, syncPlayNew, syncPlayPause, syncPlayReady, syncPlaySeek,
  syncPlaySetQueue, syncPlayUnpause
} from '../api/jellyfin';
import { userStore } from '../lib/app-state';
import { on } from '../lib/remote';
import { createStore } from '../lib/store';
import { secondsToTicks, ticksToSeconds } from '../lib/format';
import { toast } from '../ui/overlay';
import { t } from '../lib/i18n';

/* Watch together (Jellyfin SyncPlay). The group outlives a single player: when the group moves to
   another title the player navigates there and the new player binds itself again. */

export interface SyncGroup { GroupId: string; GroupName: string; State?: string; Participants: string[] }
export interface SyncState { group: SyncGroup | null; playlistItemId?: string; itemId?: string }

export const syncStore = createStore<SyncState>({ group: null });

export interface SyncController {
  itemId: string;
  play(): void;
  pause(): void;
  seek(seconds: number): void;
  position(): number;
  paused(): boolean;
  /* Switch to another title (the group's queue moved on). */
  load(itemId: string, seconds: number): void;
  stop(): void;
}

let ctl: SyncController | null = null;
let offsetMs = 0;
let pendingReady = false;
/* Starting a group mid-film shouldn't leave everyone paused: resume once the group is ready. */
let resumeWhenReady = false;
let lastSync = 0;
const timers = new Set<number>();

export const syncAccess = () => userStore.get()?.Policy?.SyncPlayAccess ?? 'CreateAndJoinGroups';
export const inGroup = () => !!syncStore.get().group;

function later(fn: () => void, ms: number) {
  const id = window.setTimeout(() => { timers.delete(id); fn(); }, Math.max(0, ms));
  timers.add(id);
}
function clearScheduled() {
  timers.forEach((id) => window.clearTimeout(id));
  timers.clear();
}

/* Server clock offset from a few /GetUtcTime round trips; the fastest one wins. */
async function syncTime() {
  if (Date.now() - lastSync < 60_000) return;
  let best = Infinity;
  for (let i = 0; i < 3; i++) {
    try {
      const sent = Date.now();
      const r = await getUtcTime();
      const received = Date.now();
      const rtt = received - sent;
      if (rtt < best) {
        best = rtt;
        offsetMs = ((Date.parse(r.RequestReceptionTime) - sent) + (Date.parse(r.ResponseTransmissionTime) - received)) / 2;
      }
    } catch { /* keep the previous offset */ }
  }
  lastSync = Date.now();
}

const serverNow = () => new Date(Date.now() + offsetMs).toISOString();
const toLocal = (when: string) => Date.parse(when) - offsetMs;

function readyBody(isPlaying: boolean) {
  return {
    When: serverNow(),
    PositionTicks: secondsToTicks(ctl?.position() ?? 0),
    IsPlaying: isPlaying,
    PlaylistItemId: syncStore.get().playlistItemId ?? ''
  };
}

export function bindSyncPlayer(c: SyncController): () => void {
  ctl = c;
  return () => { if (ctl === c) ctl = null; };
}

/* The player calls this when it can play (after loading, seeking or buffering). */
export function reportReady() {
  if (!inGroup() || !ctl || !pendingReady) return;
  pendingReady = false;
  const resume = resumeWhenReady;
  resumeWhenReady = false;
  syncPlayReady(readyBody(!ctl.paused())).then(() => { if (resume) void requestUnpause(); }, () => undefined);
}

export function reportBuffering() {
  if (!inGroup() || !ctl) return;
  pendingReady = true;
  syncPlayBuffering(readyBody(false)).catch(() => undefined);
}

export const requestPause = () => syncPlayPause().catch(() => toast(t("Couldn't reach the group"), 'error'));
export const requestUnpause = () => syncPlayUnpause().catch(() => toast(t("Couldn't reach the group"), 'error'));
export const requestSeek = (seconds: number) => syncPlaySeek(secondsToTicks(seconds)).catch(() => toast(t("Couldn't reach the group"), 'error'));
export const requestQueue = (itemId: string, seconds = 0) => syncPlaySetQueue([itemId], secondsToTicks(seconds)).catch(() => undefined);

export async function createGroup(itemId: string, seconds: number) {
  await syncTime();
  const name = `${userStore.get()?.Name ?? 'Someone'}'s group`;
  resumeWhenReady = !!ctl && !ctl.paused();
  await syncPlayNew(name);
  syncStore.set((s) => ({ ...s, group: s.group ?? { GroupId: '', GroupName: name, Participants: [userStore.get()?.Name ?? ''] } }));
  await syncPlaySetQueue([itemId], secondsToTicks(seconds));
}

export async function joinGroup(groupId: string) {
  await syncTime();
  await syncPlayJoin(groupId);
}

export async function leaveGroup() {
  clearScheduled();
  pendingReady = false;
  resumeWhenReady = false;
  syncStore.set({ group: null });
  await syncPlayLeave().catch(() => undefined);
}

/* Seek when we're off by more than half a second; otherwise there's no "seeked" to wait for. */
function seekThenReady(c: SyncController, seconds: number) {
  if (Math.abs(c.position() - seconds) > 0.5) c.seek(seconds);
  else window.setTimeout(reportReady, 50);
}

interface GroupUpdate { GroupId: string; Type: string; Data: any }
interface PlayQueueUpdate {
  Reason: string;
  Playlist: { ItemId: string; PlaylistItemId: string }[];
  PlayingItemIndex: number;
  StartPositionTicks: number;
  IsPlaying: boolean;
}

on('SyncPlayGroupUpdate', (u: GroupUpdate) => {
  const s = syncStore.get();
  switch (u.Type) {
    case 'GroupJoined': {
      const g = u.Data as SyncGroup;
      syncStore.set({ ...s, group: { GroupId: g.GroupId, GroupName: g.GroupName, State: g.State, Participants: g.Participants ?? [] } });
      void syncTime();
      /* A group with nothing queued starts with what this player is showing. */
      if (g.State === 'Idle' && ctl) void requestQueue(ctl.itemId, ctl.position());
      break;
    }
    case 'UserJoined':
      if (s.group) syncStore.set({ ...s, group: { ...s.group, Participants: [...s.group.Participants, String(u.Data)] } });
      toast(t('{name} joined', { name: String(u.Data) }));
      break;
    case 'UserLeft':
      if (s.group) {
        const list = [...s.group.Participants];
        const i = list.indexOf(String(u.Data));
        if (i >= 0) list.splice(i, 1);
        syncStore.set({ ...s, group: { ...s.group, Participants: list } });
      }
      toast(t('{name} left', { name: String(u.Data) }));
      break;
    case 'GroupLeft':
    case 'NotInGroup':
    case 'GroupDoesNotExist':
      clearScheduled();
      syncStore.set({ group: null });
      break;
    case 'StateUpdate':
      if (s.group) syncStore.set({ ...s, group: { ...s.group, State: u.Data?.State } });
      break;
    case 'CreateGroupDenied':
    case 'JoinGroupDenied':
    case 'LibraryAccessDenied':
      toast(t("You can't join that group"), 'error');
      break;
    case 'PlayQueue': {
      const q = u.Data as PlayQueueUpdate;
      const cur = q.Playlist?.[q.PlayingItemIndex];
      if (!cur) break;
      syncStore.set({ ...s, playlistItemId: cur.PlaylistItemId, itemId: cur.ItemId });
      pendingReady = true;
      const at = ticksToSeconds(q.StartPositionTicks);
      if (!ctl) break;
      if (ctl.itemId !== cur.ItemId) {
        ctl.load(cur.ItemId, at);
        break;
      }
      ctl.pause();
      seekThenReady(ctl, at);
      break;
    }
    default:
      break;
  }
});

interface SyncCommand { GroupId: string; PlaylistItemId: string; When: string; PositionTicks?: number; Command: 'Unpause' | 'Pause' | 'Seek' | 'Stop' }

on('SyncPlayCommand', (c: SyncCommand) => {
  const s = syncStore.get();
  if (!s.group || !ctl) return;
  if (s.playlistItemId && c.PlaylistItemId && c.PlaylistItemId !== s.playlistItemId) return;
  clearScheduled();
  const at = toLocal(c.When);
  const pos = ticksToSeconds(c.PositionTicks ?? 0);
  const c0 = ctl;
  switch (c.Command) {
    case 'Unpause': {
      const wait = at - Date.now();
      if (wait > 0) {
        c0.seek(pos);
        later(() => c0.play(), wait);
      } else {
        c0.seek(pos + -wait / 1000);
        c0.play();
      }
      break;
    }
    case 'Pause':
      later(() => { c0.pause(); c0.seek(pos); }, at - Date.now());
      break;
    case 'Seek':
      pendingReady = true;
      c0.pause();
      seekThenReady(c0, pos);
      break;
    case 'Stop':
      later(() => c0.stop(), at - Date.now());
      break;
  }
});
