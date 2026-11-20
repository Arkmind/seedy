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
export interface SyncState {
  group: SyncGroup | null;
  playlistItemId?: string;
  itemId?: string;
  /* What the group does once everyone is ready: while it waits for someone to load, it either resumes or stays
     paused (Jellyfin's ResumePlaying). Play/pause shows this while the group waits, not this player's state. */
  playing?: boolean;
}

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
/* Creating a group queues its title itself: the GroupJoined that comes meanwhile mustn't queue it again. */
let creating = false;
/* A stall is reported only once it lasts, as Jellyfin's own apps do: each report pauses everyone until this player
   is ready again, and most stalls (a seek, a slow segment) are over in a moment. */
const BUFFER_GRACE_MS = 3000;
let bufferTimer = 0;
/* While the group plays: it plays from [pos] seconds at [at] (this computer's clock). */
let timeline: { at: number; pos: number } | null = null;
/* How far from the group's position a command may find this player before it seeks there. */
const DRIFT_SECONDS = 0.5;
/* How far behind (or ahead) a playing player may fall before it seeks to where the group is. A seek, and playback
   starting, cost about half a second of their own: below this, catching up would keep chasing the group. */
const CATCH_UP_SECONDS = 1.5;
/* A catch-up at most this often. */
const CATCH_UP_MS = 10_000;
let lastCatchUp = 0;
/* While the group plays, how often this player checks it's still with it (a stall too short to report, a decoder
   falling behind): two checks in a row off by more than CATCH_UP_SECONDS bring it back. */
const CHECK_MS = 2000;
let checkTimer = 0;
let drifting = 0;

export const syncAccess = () => userStore.get()?.Policy?.SyncPlayAccess ?? 'CreateAndJoinGroups';
export const inGroup = () => !!syncStore.get().group;
/* The group waits for someone to load and then plays on by itself (an Unpause now would start it without them). */
export const groupLoading = (s: SyncState = syncStore.get()) => s.group?.State === 'Waiting' && !!s.playing;
/* Paused, as far as this viewer's play/pause goes: while the group waits, what it does once everyone is ready. */
export const groupPaused = (s: SyncState = syncStore.get()) => s.group?.State === 'Waiting' ? !s.playing : null;

function later(fn: () => void, ms: number) {
  const id = window.setTimeout(() => { timers.delete(id); fn(); }, Math.max(0, ms));
  timers.add(id);
}
function clearScheduled() {
  timers.forEach((id) => window.clearTimeout(id));
  timers.clear();
}
function clearBuffering() {
  window.clearTimeout(bufferTimer);
  bufferTimer = 0;
}
function setTimeline(t: { at: number; pos: number } | null) {
  timeline = t;
  drifting = 0;
  window.clearInterval(checkTimer);
  checkTimer = t ? window.setInterval(checkDrift, CHECK_MS) : 0;
}
function reset() {
  clearScheduled();
  clearBuffering();
  setTimeline(null);
  pendingReady = false;
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
  return () => {
    if (ctl !== c) return;
    ctl = null;
    clearBuffering();
  };
}

/* The player calls this when it can play (after loading, seeking or buffering). */
export function reportReady() {
  if (bufferTimer) {
    // A stall too short to tell the group about: catch up with it instead.
    clearBuffering();
    if (ctl && !pendingReady) catchUp(ctl);
  }
  if (!inGroup() || !ctl || !pendingReady) return;
  pendingReady = false;
  const resume = resumeWhenReady;
  resumeWhenReady = false;
  syncPlayReady(readyBody(!ctl.paused())).then(() => { if (resume) void requestUnpause(); }, () => undefined);
}

/* The player calls this when it stalls while playing. */
export function reportBuffering() {
  if (!inGroup() || !ctl || bufferTimer) return;
  bufferTimer = window.setTimeout(() => {
    bufferTimer = 0;
    if (!inGroup() || !ctl || ctl.paused()) return;
    pendingReady = true;
    syncPlayBuffering(readyBody(false)).catch(() => undefined);
  }, BUFFER_GRACE_MS);
}

export const requestPause = () => syncPlayPause().catch(() => toast(t("Couldn't reach the group"), 'error'));
export const requestUnpause = () => syncPlayUnpause().catch(() => toast(t("Couldn't reach the group"), 'error'));
export const requestSeek = (seconds: number) => syncPlaySeek(secondsToTicks(seconds)).catch(() => toast(t("Couldn't reach the group"), 'error'));
export const requestQueue = (itemId: string, seconds = 0) => syncPlaySetQueue([itemId], secondsToTicks(seconds)).catch(() => undefined);

export async function createGroup(itemId: string, seconds: number) {
  await syncTime();
  const name = `${userStore.get()?.Name ?? 'Someone'}'s group`;
  resumeWhenReady = !!ctl && !ctl.paused();
  creating = true;
  try {
    await syncPlayNew(name);
    syncStore.set((s) => ({ ...s, group: s.group ?? { GroupId: '', GroupName: name, Participants: [userStore.get()?.Name ?? ''] } }));
    await syncPlaySetQueue([itemId], secondsToTicks(seconds));
  } finally {
    creating = false;
  }
}

export async function joinGroup(groupId: string) {
  await syncTime();
  await syncPlayJoin(groupId);
}

export async function leaveGroup() {
  reset();
  resumeWhenReady = false;
  syncStore.set({ group: null });
  await syncPlayLeave().catch(() => undefined);
}

/* Seek when we're off by more than DRIFT_SECONDS; otherwise there's no "seeked" to wait for. */
function seekThenReady(c: SyncController, seconds: number) {
  if (Math.abs(c.position() - seconds) > DRIFT_SECONDS) c.seek(seconds);
  else window.setTimeout(reportReady, 50);
}

/* Play along the group's timeline. Before it starts, this player starts with it; once it runs, this player joins
   where it is now. A player that already plays (it reported ready while playing) is left alone unless it drifted:
   seeking it anyway made it wait again, report a stall, and pause everyone once more. */
function follow(c: SyncController) {
  const t = timeline;
  if (!t) return;
  const wait = t.at - Date.now();
  if (wait > 0) {
    const willBe = c.position() + (c.paused() ? 0 : wait / 1000);
    if (Math.abs(willBe - t.pos) > DRIFT_SECONDS) {
      c.pause();
      c.seek(t.pos);
    }
    if (c.paused()) later(() => c.play(), wait);
    return;
  }
  const target = t.pos - wait / 1000;
  if (Math.abs(c.position() - target) > DRIFT_SECONDS) c.seek(target);
  if (c.paused()) c.play();
}

function checkDrift() {
  const c = ctl;
  const t = timeline;
  if (!c || !t || bufferTimer || pendingReady || c.paused() || Date.now() < t.at) {
    drifting = 0;
    return;
  }
  const off = Math.abs(c.position() - (t.pos + (Date.now() - t.at) / 1000));
  drifting = off > CATCH_UP_SECONDS ? drifting + 1 : 0;
  if (drifting < 2) return;
  drifting = 0;
  catchUp(c);
}

/* Off the group's pace (after a stall nobody was told about, or drifting): seek to where the group is now. */
function catchUp(c: SyncController) {
  const t = timeline;
  const now = Date.now();
  if (!t || c.paused() || now < t.at || now - lastCatchUp < CATCH_UP_MS) return;
  const target = t.pos + (now - t.at) / 1000;
  if (Math.abs(c.position() - target) <= CATCH_UP_SECONDS) return;
  lastCatchUp = now;
  c.seek(target);
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
      reset();
      syncStore.set({
        ...s,
        playing: g.State === 'Playing',
        group: { GroupId: g.GroupId, GroupName: g.GroupName, State: g.State, Participants: g.Participants ?? [] }
      });
      void syncTime();
      /* A group with nothing queued starts with what this player is showing (a new group queues it itself). */
      if (g.State === 'Idle' && ctl && !creating) void requestQueue(ctl.itemId, ctl.position());
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
      reset();
      syncStore.set({ group: null });
      break;
    case 'StateUpdate': {
      if (!s.group) break;
      const state = u.Data?.State as string | undefined;
      const reason = u.Data?.Reason as string | undefined;
      // While the group waits, what it does once everyone is ready: a pause or a play request changes it, someone
      // loading or seeking doesn't (the Pause command the others get then only holds them).
      const playing = state === 'Playing' ? true
        : state === 'Paused' || state === 'Idle' ? false
          : reason === 'Pause' || reason === 'Stop' ? false
            : reason === 'Unpause' || reason === 'Play' || reason === 'SetPlaylistItem' || reason === 'NextItem' || reason === 'PreviousItem' ? true
              : s.playing;
      if (state !== 'Playing') setTimeline(null);
      // Back to playing without a command for this player (it was the one catching up): it sets the pace.
      else if (!timeline && ctl && !ctl.paused()) setTimeline({ at: Date.now(), pos: ctl.position() });
      syncStore.set({ ...s, playing, group: { ...s.group, State: state } });
      break;
    }
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
      clearBuffering();
      setTimeline(null);
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
  if (c.Command !== 'Unpause') setTimeline(null);
  if (c.Command === 'Unpause') syncStore.set({ ...s, playing: true });
  switch (c.Command) {
    case 'Unpause':
      setTimeline({ at, pos });
      follow(c0);
      break;
    case 'Pause':
      clearBuffering();
      later(() => { c0.pause(); c0.seek(pos); }, at - Date.now());
      break;
    case 'Seek':
      clearBuffering();
      pendingReady = true;
      c0.pause();
      seekThenReady(c0, pos);
      break;
    case 'Stop':
      clearBuffering();
      later(() => c0.stop(), at - Date.now());
      break;
  }
});
