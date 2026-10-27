/* The audio engine behind the music and audiobook players (mini bar, Now playing, Audiobook player).
   Contract shared by the music and books screens; the implementation lives here.

   One <audio> element plays a queue: the songs of what was played (an album, playlist, artist, mix…, "Next from …")
   with the songs the person queued themselves ("Added by you") played first. Audiobooks play alone, with chapters,
   speed and a sleep timer. Playback is reported to Jellyfin (play counts, last played, audiobook resume points),
   shows in the system's media controls, and the queue survives a reload. */

import { setFavorite } from '../api/jellyfin';
import { imageUrl } from '../api/images';
import { bootState, currentUserId } from '../lib/app-state';
import { t } from '../lib/i18n';
import { on as onRemote } from '../lib/remote';
import { getLocation } from '../lib/router';
import { createStore } from '../lib/store';
import { toast } from '../ui/overlay';
import {
  audioStreamOf, containerTracks, getAudioItem, getTracksByIds, openStream, qualityLabel, reportProgress, reportStart, reportStopped,
  type Report, type Stream, type Track
} from './api';

export type SourceKind = 'album' | 'playlist' | 'artist' | 'mix' | 'library' | 'genre' | 'songs' | 'folder' | 'audiobook';

export type PlayOptions = {
  /* Where to start the first item, in ticks. */
  startTicks?: number;
  /* Open the full-screen player instead of only the mini bar. */
  open?: boolean;
  /* Shuffle the items. */
  shuffle?: boolean;
  /* What the queue plays from, shown as "Playing from album …" (album, playlist, artist, mix, library). */
  source?: { kind: SourceKind; id?: string; name: string };
  /* playContainer: start at this song of the album or playlist. */
  startId?: string;
};

export type Source = NonNullable<PlayOptions['source']>;
export type Repeat = 'off' | 'all' | 'one';
export interface Entry { key: string; track: Track }
export interface BookChapter { index: number; name: string; start: number; end: number }
export interface BookInfo { id: string; name: string; author?: string; narrator?: string; duration: number; chapters: BookChapter[] }
export type Sleep = { kind: 'time'; at: number; minutes: number } | { kind: 'chapter'; index: number } | null;
export interface StreamInfo { method: Stream['method']; quality: string }

export interface AudioState {
  mode: 'music' | 'audiobook' | null;
  current: Entry | null;
  /* The current song came from "Added by you"; context[pos] is then the song played before it. */
  fromAdded: boolean;
  /* What the queue plays from, in play order (shuffled when shuffle is on). */
  context: Entry[];
  /* The unshuffled order, while shuffled. */
  original: Entry[] | null;
  pos: number;
  added: Entry[];
  source: Source | null;
  shuffle: boolean;
  repeat: Repeat;
  playing: boolean;
  loading: boolean;
  volume: number;
  muted: boolean;
  speed: number;
  sleep: Sleep;
  book: BookInfo | null;
  stream: StreamInfo | null;
  /* The full-screen player is open. */
  open: boolean;
}

const VOLUME_KEY = 'seedy.audio.volume';
const SPEED_KEY = 'seedy.audio.speed';
const QUEUE_KEY = 'seedy.audio.queue';
const RECENT_KEY = 'seedy.music.recent';

function readLocal<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw === null ? fallback : (JSON.parse(raw) as T);
  } catch {
    return fallback;
  }
}
function writeLocal(key: string, value: unknown) {
  try {
    if (value === null) localStorage.removeItem(key);
    else localStorage.setItem(key, JSON.stringify(value));
  } catch { /* storage unavailable: the queue lasts for this tab */ }
}

const initial = (): AudioState => {
  const vol = readLocal<{ volume?: number; muted?: boolean }>(VOLUME_KEY, {});
  return {
    mode: null, current: null, fromAdded: false, context: [], original: null, pos: 0, added: [], source: null,
    shuffle: false, repeat: 'off', playing: false, loading: false,
    volume: typeof vol.volume === 'number' ? Math.max(0, Math.min(1, vol.volume)) : 1, muted: !!vol.muted,
    speed: readLocal<number>(SPEED_KEY, 1) || 1, sleep: null, book: null, stream: null, open: false
  };
};

export const audioStore = createStore<AudioState>(initial());
/* Seconds into the current song or book, and its length; updated a few times a second while playing. */
export const timeStore = createStore<{ position: number; duration: number }>({ position: 0, duration: 0 });
/* Bumped after a song or book stopped, so pages showing play counts or resume points can refresh. */
export const playedStore = createStore<{ n: number; id: string }>({ n: 0, id: '' });
/* A song's favourite state changed from a player. */
export const favoriteStore = createStore<{ n: number; id: string; on: boolean }>({ n: 0, id: '', on: false });

const state = () => audioStore.get();
const set = (patch: Partial<AudioState>) => audioStore.set((s) => ({ ...s, ...patch }));

let keySeq = 0;
const mk = (track: Track): Entry => ({ key: `${Date.now().toString(36)}-${(++keySeq).toString(36)}`, track });
const seconds = (ticks?: number | null) => (ticks ?? 0) / 10_000_000;
const ticks = (s: number) => Math.round(s * 10_000_000);

function shuffled<T>(list: T[]): T[] {
  const a = list.slice();
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

/* ───────── The element ───────── */

let el: HTMLAudioElement | null = null;
let stream: Stream | null = null;
let loadSeq = 0;
let started = false;
let converted = false;
let progressTimer = 0;
let sleepTimer = 0;
let lastSave = 0;
/* Between two songs: the old one's pause isn't the person pausing. */
let switching = false;

function media(): HTMLAudioElement {
  if (el) return el;
  const a = new Audio();
  a.preload = 'auto';
  a.crossOrigin = null;
  const s = state();
  a.volume = s.volume;
  a.muted = s.muted;
  a.addEventListener('playing', () => {
    switching = false;
    set({ playing: true, loading: false });
    if (!started) begin();
    else report('unpause');
    syncSession();
  });
  a.addEventListener('play', () => set({ playing: true }));
  a.addEventListener('pause', () => {
    if (a.ended || switching) return;
    set({ playing: false });
    report('pause');
    save(true);
    syncSession();
  });
  a.addEventListener('waiting', () => set({ loading: true }));
  a.addEventListener('canplay', () => set({ loading: false }));
  a.addEventListener('timeupdate', onTime);
  a.addEventListener('durationchange', onTime);
  a.addEventListener('ended', () => void onEnded());
  a.addEventListener('error', () => void onError());
  el = a;
  return a;
}

export const position = () => (stream && el ? stream.offset + (el.currentTime || 0) : timeStore.get().position);

function durationOf(entry: Entry | null): number {
  const s = state();
  if (s.mode === 'audiobook' && s.book) return s.book.duration;
  const d = seconds(entry?.track.RunTimeTicks);
  if (d) return d;
  return el && Number.isFinite(el.duration) ? el.duration + (stream?.offset ?? 0) : 0;
}

function onTime() {
  const pos = position();
  timeStore.set({ position: pos, duration: durationOf(state().current) });
  checkSleep(pos);
  if (Date.now() - lastSave > 5000) save();
  if (Date.now() - lastPositionState > 1000) syncPosition();
}

/* ───────── Loading ───────── */

async function load(entry: Entry, start: number, autoplay: boolean, convert = false) {
  const seq = ++loadSeq;
  switching = autoplay;
  end(false, false);
  converted = convert;
  set({ current: entry, loading: true, stream: null, playing: autoplay ? state().playing : false });
  timeStore.set({ position: start, duration: durationOf(entry) });
  syncSession();
  const a = media();
  try {
    const s = await openStream(entry.track.Id, start, convert);
    if (seq !== loadSeq) return;
    stream = s;
    started = false;
    const quality = qualityLabel(audioStreamOf({ MediaSources: [s.source] }), ' ');
    set({ stream: { method: s.method, quality } });
    a.src = s.url;
    const rate = state().mode === 'audiobook' ? state().speed : 1;
    a.defaultPlaybackRate = rate;
    a.playbackRate = rate;
    if (s.offset === 0 && start > 0) {
      const at = start;
      const seekOnce = () => { a.removeEventListener('loadedmetadata', seekOnce); try { a.currentTime = at; } catch { /* not seekable yet */ } };
      a.addEventListener('loadedmetadata', seekOnce);
    }
    a.load();
    if (autoplay) {
      await a.play().catch((e: Error) => {
        if (seq !== loadSeq) return;
        // Autoplay blocked (a reload): wait for a press.
        if (e?.name === 'NotAllowedError') set({ playing: false, loading: false });
        else throw e;
      });
    } else set({ loading: false });
    save(true);
  } catch {
    if (seq !== loadSeq) return;
    switching = false;
    failed(entry);
  }
}

async function onError() {
  const s = state();
  if (!s.current || !stream) return;
  const at = position();
  // The browser couldn't play the file as it is: ask the server to convert it once.
  if (!converted) {
    void load(s.current, at, true, true);
    return;
  }
  failed(s.current);
}

function failed(entry: Entry) {
  end();
  set({ playing: false, loading: false });
  toast(t('Couldn’t play {name}', { name: entry.track.Name }), 'error');
  const s = state();
  // In a queue, carry on with the next song.
  if (s.mode === 'music' && peek(s)) window.setTimeout(() => { if (state().current?.key === entry.key) next(); }, 1200);
}

async function onEnded() {
  const s = state();
  end(true, false);
  if (s.mode === 'audiobook') {
    set({ playing: false, sleep: null });
    timeStore.set({ position: 0, duration: durationOf(s.current) });
    save(true);
    return;
  }
  if (s.repeat === 'one' && s.current) { void load(s.current, 0, true); return; }
  const n = peek(s);
  if (n) { apply(n, true); return; }
  // The end of the queue: back to its first song, paused.
  set({ playing: false });
  const first = s.context[0];
  if (first) {
    set({ pos: 0, fromAdded: false });
    void load(first, 0, false);
  }
}

/* ───────── Reports ───────── */

function body(event?: string): Report | null {
  const s = state();
  if (!s.current || !stream) return null;
  const queue = s.mode === 'music' ? [s.current, ...upNext(s)].slice(0, 60).map((e) => ({ Id: e.track.Id, PlaylistItemId: e.key })) : undefined;
  return {
    ItemId: s.current.track.Id, MediaSourceId: stream.source.Id, PlaySessionId: stream.playSessionId, PositionTicks: ticks(position()),
    IsPaused: el ? el.paused : true, IsMuted: s.muted, VolumeLevel: Math.round(s.volume * 100), PlayMethod: stream.method, CanSeek: true,
    PlaybackRate: el?.playbackRate ?? 1, RepeatMode: s.repeat === 'all' ? 'RepeatAll' : s.repeat === 'one' ? 'RepeatOne' : 'RepeatNone',
    PlaybackOrder: s.shuffle ? 'Shuffle' : 'Default', EventName: event, NowPlayingQueue: queue
  };
}

function begin() {
  const b = body();
  if (!b) return;
  started = true;
  reportStart(b).catch(() => undefined);
  window.clearInterval(progressTimer);
  progressTimer = window.setInterval(() => { if (el && !el.paused) report('timeupdate'); }, 10_000);
}

function report(event: string) {
  if (!started) return;
  const b = body(event);
  if (b) reportProgress(b).catch(() => undefined);
}

/* Ends the current stream: tells the server where it stopped (at the end when [finished]); [clear] unloads the
   element (not when another song is about to load into it). */
function end(finished = false, clear = true) {
  window.clearInterval(progressTimer);
  if (stream && started) {
    const b = body();
    const s = state();
    if (b) {
      if (finished && s.current) b.PositionTicks = s.current.track.RunTimeTicks ?? b.PositionTicks;
      void reportStopped(b);
      const id = b.ItemId;
      // Give the server a moment to count it before pages ask again.
      window.setTimeout(() => playedStore.set((p) => ({ n: p.n + 1, id })), 600);
    }
  }
  started = false;
  stream = null;
  if (clear && el && el.src) {
    el.removeAttribute('src');
    try { el.load(); } catch { /* nothing loaded */ }
  }
}

/* ───────── Queue ───────── */

export const upNext = (s: AudioState = state()) => [...s.added, ...s.context.slice(s.pos + 1)];

function peek(s: AudioState): { entry: Entry; patch: Partial<AudioState> } | null {
  if (s.added.length) return { entry: s.added[0], patch: { added: s.added.slice(1), fromAdded: true } };
  if (s.pos + 1 < s.context.length) return { entry: s.context[s.pos + 1], patch: { pos: s.pos + 1, fromAdded: false } };
  if (s.repeat === 'all' && s.context.length) return { entry: s.context[0], patch: { pos: 0, fromAdded: false } };
  return null;
}

function apply(n: { entry: Entry; patch: Partial<AudioState> }, autoplay = true) {
  set(n.patch);
  void load(n.entry, 0, autoplay);
}

export function next() {
  const s = state();
  if (s.mode !== 'music' || !s.current) return;
  const n = peek(s);
  if (n) apply(n, true);
}

export function previous() {
  const s = state();
  if (!s.current) return;
  if (s.mode === 'audiobook') { previousChapter(); return; }
  if (position() > 3) { seek(0); return; }
  if (s.fromAdded) {
    // Back to the song before the queued one; the queued one goes back to the top of the queue.
    const back = s.context[s.pos];
    if (back) { set({ added: [s.current, ...s.added], fromAdded: false }); void load(back, 0, true); }
    else seek(0);
    return;
  }
  if (s.pos > 0) { set({ pos: s.pos - 1 }); void load(s.context[s.pos - 1], 0, true); return; }
  if (s.repeat === 'all' && s.context.length > 1) { set({ pos: s.context.length - 1 }); void load(s.context[s.context.length - 1], 0, true); return; }
  seek(0);
}

/* Plays a song from Up next: queued songs before it are skipped (dropped); songs of the context before it count as played. */
export function jumpTo(key: string) {
  const s = state();
  const ai = s.added.findIndex((e) => e.key === key);
  if (ai >= 0) { set({ added: s.added.slice(ai + 1), fromAdded: true }); void load(s.added[ai], 0, true); return; }
  const ci = s.context.findIndex((e, i) => e.key === key && i > s.pos);
  if (ci >= 0) { set({ pos: ci, fromAdded: false }); void load(s.context[ci], 0, true); }
}

export function addToQueue(tracks: Track[], where: 'next' | 'end' = 'end') {
  if (!tracks.length) return;
  const s = state();
  if (s.mode !== 'music' || !s.current) {
    void playList(tracks, 0, { source: { kind: 'songs', name: t('Songs') } });
    return;
  }
  const entries = tracks.map(mk);
  set({ added: where === 'next' ? [...entries, ...s.added] : [...s.added, ...entries] });
  save(true);
}

export function removeFromQueue(key: string) {
  const s = state();
  if (s.added.some((e) => e.key === key)) set({ added: s.added.filter((e) => e.key !== key) });
  else {
    const i = s.context.findIndex((e) => e.key === key);
    if (i > s.pos) set({ context: s.context.filter((e) => e.key !== key), original: s.original?.filter((e) => e.key !== key) ?? null });
  }
  save(true);
}

/* Moves a song within its part of Up next: "added" (Added by you) or "context" (Next from …); indexes count from
   the top of that part. */
export function moveInQueue(part: 'added' | 'context', from: number, to: number) {
  const s = state();
  if (part === 'added') {
    const list = s.added.slice();
    if (from < 0 || from >= list.length) return;
    const [x] = list.splice(from, 1);
    list.splice(Math.max(0, Math.min(to, list.length)), 0, x);
    set({ added: list });
  } else {
    const head = s.context.slice(0, s.pos + 1);
    const rest = s.context.slice(s.pos + 1);
    if (from < 0 || from >= rest.length) return;
    const [x] = rest.splice(from, 1);
    rest.splice(Math.max(0, Math.min(to, rest.length)), 0, x);
    set({ context: [...head, ...rest] });
  }
  save(true);
}

export function clearQueue() {
  const s = state();
  set({ added: [], context: s.context.slice(0, s.pos + 1), original: null });
  save(true);
}

export function toggleShuffle() {
  const s = state();
  if (s.mode !== 'music') return;
  if (!s.shuffle) {
    const here = s.context[s.pos];
    const others = s.context.filter((e) => e !== here);
    set({ shuffle: true, original: s.context, context: here ? [here, ...shuffled(others)] : shuffled(others), pos: 0 });
  } else {
    const here = s.context[s.pos];
    const original = s.original ?? s.context;
    const i = here ? original.findIndex((e) => e.key === here.key) : -1;
    set({ shuffle: false, original: null, context: original, pos: Math.max(0, i) });
  }
  report('shufflechange');
  save(true);
}

export function cycleRepeat() {
  const s = state();
  set({ repeat: s.repeat === 'off' ? 'all' : s.repeat === 'all' ? 'one' : 'off' });
  report('repeatmodechange');
  save(true);
}

/* ───────── Transport ───────── */

export function togglePlay() {
  const s = state();
  if (!s.current) return;
  if (!stream) { void load(s.current, timeStore.get().position, true); return; }
  const a = media();
  if (a.paused) void a.play().catch(() => set({ playing: false }));
  else a.pause();
}

export function play() {
  const s = state();
  if (!s.current) return;
  if (!stream) { void load(s.current, timeStore.get().position, true); return; }
  void media().play().catch(() => set({ playing: false }));
}

export function pause() {
  if (el && !el.paused) el.pause();
}

export function seek(to: number) {
  const s = state();
  if (!s.current) return;
  const dur = durationOf(s.current);
  const at = Math.max(0, dur ? Math.min(to, dur - 0.25) : to);
  timeStore.set({ position: at, duration: dur });
  if (!stream) { save(true); return; }
  // A conversion can't seek in the file: start a new one there.
  if (stream.method !== 'DirectPlay') { void load(s.current, at, !media().paused, converted); return; }
  try { media().currentTime = at; } catch { /* not seekable yet */ }
  report('timeupdate');
  syncPosition();
}

export const skip = (by: number) => seek(position() + by);

export function setVolume(v: number) {
  const volume = Math.max(0, Math.min(1, v));
  set({ volume, muted: volume === 0 ? state().muted : false });
  if (el) { el.volume = volume; el.muted = state().muted; }
  writeLocal(VOLUME_KEY, { volume, muted: state().muted });
  report('volumechange');
}

export function toggleMute() {
  const muted = !state().muted;
  set({ muted });
  if (el) el.muted = muted;
  writeLocal(VOLUME_KEY, { volume: state().volume, muted });
  report('volumechange');
}

export function setSpeed(v: number) {
  set({ speed: v });
  writeLocal(SPEED_KEY, v);
  if (el && state().mode === 'audiobook') { el.defaultPlaybackRate = v; el.playbackRate = v; }
  report('ratechange');
}

export function openPlayer(open = true) {
  set({ open });
}

/* Stops and forgets everything (signing out, or the person closing the player). */
export function stop() {
  ++loadSeq;
  end();
  window.clearTimeout(sleepTimer);
  const s = initial();
  audioStore.set({ ...s, volume: state().volume, muted: state().muted });
  timeStore.set({ position: 0, duration: 0 });
  writeLocal(QUEUE_KEY, null);
  syncSession();
}

/* ───────── Favourites ───────── */

export async function toggleTrackFavorite(track: Pick<Track, 'Id' | 'Name' | 'UserData'>, on = !track.UserData?.IsFavorite) {
  const patch = (e: Entry) => (e.track.Id === track.Id ? { ...e, track: { ...e.track, UserData: { ...e.track.UserData, IsFavorite: on } } } : e);
  const applyAll = () => {
    const s = state();
    set({ current: s.current ? patch(s.current) : null, context: s.context.map(patch), added: s.added.map(patch), original: s.original?.map(patch) ?? null });
    favoriteStore.set((f) => ({ n: f.n + 1, id: track.Id, on }));
  };
  applyAll();
  try {
    await setFavorite(track.Id, on);
    save(true);
  } catch {
    on = !on;
    applyAll();
    toast(t('Couldn’t update {name}', { name: track.Name }), 'error');
  }
}

/* ───────── Audiobooks ───────── */

export function chapterAt(book: BookInfo | null, pos: number): BookChapter | undefined {
  if (!book?.chapters.length) return undefined;
  let found = book.chapters[0];
  for (const c of book.chapters) if (c.start <= pos + 0.01) found = c;
  return found;
}

export function seekChapter(index: number) {
  const b = state().book;
  const c = b?.chapters[index];
  if (c) seek(c.start);
}

export function previousChapter() {
  const s = state();
  const c = chapterAt(s.book, position());
  if (!c) { seek(0); return; }
  if (position() - c.start > 3 || c.index === 0) seek(c.start);
  else seekChapter(c.index - 1);
}

export function nextChapter() {
  const s = state();
  const c = chapterAt(s.book, position());
  if (c && s.book && c.index + 1 < s.book.chapters.length) seekChapter(c.index + 1);
}

export function setSleep(sleep: Sleep) {
  window.clearTimeout(sleepTimer);
  set({ sleep });
  if (sleep?.kind === 'time') {
    sleepTimer = window.setTimeout(() => {
      if (state().sleep === sleep) { pause(); set({ sleep: null }); }
    }, Math.max(0, sleep.at - Date.now()));
  }
}

function checkSleep(pos: number) {
  const s = state();
  if (s.sleep?.kind !== 'chapter' || !s.book) return;
  const c = s.book.chapters[s.sleep.index];
  if (c && pos >= c.end - 0.4) {
    pause();
    set({ sleep: null });
  }
}

const people = (item: Track, ...types: string[]) => (item.People ?? []).filter((p) => types.includes(p.Type ?? '')).map((p) => p.Name);

function bookInfo(item: Track): BookInfo {
  const duration = seconds(item.RunTimeTicks);
  const list = (item.Chapters ?? []).slice().sort((a, b) => a.StartPositionTicks - b.StartPositionTicks);
  const chapters: BookChapter[] = list.length
    ? list.map((c, i) => ({ index: i, name: c.Name || t('Chapter {n}', { n: i + 1 }), start: seconds(c.StartPositionTicks), end: i + 1 < list.length ? seconds(list[i + 1].StartPositionTicks) : duration }))
    : [{ index: 0, name: item.Name, start: 0, end: duration }];
  const author = people(item, 'Author', 'Writer')[0] ?? item.AlbumArtist ?? item.Artists?.[0];
  const narrator = people(item, 'Narrator', 'Composer', 'Reader')[0];
  return { id: item.Id, name: item.Album && item.Album !== item.Name ? item.Album : item.Name, author, narrator: narrator !== author ? narrator : undefined, duration, chapters };
}

/* ───────── Starting playback ───────── */

async function playList(tracks: Track[], start: number, opts: PlayOptions) {
  if (!tracks.length) {
    toast(t('Nothing to play here yet'), 'error');
    return;
  }
  let entries = tracks.map(mk);
  let original: Entry[] | null = null;
  let pos = Math.max(0, Math.min(start, entries.length - 1));
  if (opts.shuffle) {
    original = entries;
    const first = opts.startId || start > 0 ? entries[pos] : entries[Math.floor(Math.random() * entries.length)];
    entries = [first, ...shuffled(entries.filter((e) => e !== first))];
    pos = 0;
  }
  const s = state();
  set({
    mode: 'music', context: entries, original, pos, fromAdded: false, source: opts.source ?? null, shuffle: !!opts.shuffle,
    added: s.mode === 'music' ? s.added : [], book: null, sleep: null, open: opts.open ?? s.open
  });
  if (opts.source && opts.source.kind !== 'songs') remember(opts.source);
  await load(entries[pos], seconds(opts.startTicks), true);
}

/* Plays these tracks (Audio item ids) from startIndex, replacing the queue. */
export async function playTracks(ids: string[], startIndex = 0, opts: PlayOptions = {}): Promise<void> {
  const tracks = await getTracksByIds(ids);
  const start = Math.max(0, tracks.findIndex((x) => x.Id === ids[startIndex]));
  await playList(tracks, start, opts);
}

/* Plays songs already fetched (a page's track list) from [start]. */
export function playLoaded(tracks: Track[], start = 0, opts: PlayOptions = {}): Promise<void> {
  return playList(tracks, start, opts);
}

const KIND: Partial<Record<string, SourceKind>> = {
  MusicAlbum: 'album', Playlist: 'playlist', MusicArtist: 'artist', MusicGenre: 'genre', Genre: 'genre', CollectionFolder: 'library', UserView: 'library', Folder: 'folder'
};

/* Plays an album, playlist, artist or genre by id: their tracks in order (or shuffled). */
export async function playContainer(id: string, opts: PlayOptions = {}): Promise<void> {
  try {
    const item = await getAudioItem(id);
    if (item.Type === 'AudioBook') { await playAudiobook(id, opts); return; }
    const tracks = await containerTracks(item);
    const start = opts.startId ? Math.max(0, tracks.findIndex((x) => x.Id === opts.startId)) : 0;
    await playList(tracks, start, { ...opts, source: opts.source ?? { kind: KIND[item.Type] ?? 'songs', id: item.Id, name: item.Name } });
  } catch {
    toast(t('Couldn’t start playback'), 'error');
  }
}

/* "Play" on any audio item: a song plays with the rest of its album after it. */
export async function playItem(item: Pick<Track, 'Id' | 'Type' | 'AlbumId' | 'Album'>, opts: PlayOptions = {}): Promise<void> {
  if (item.Type === 'AudioBook') return playAudiobook(item.Id, opts);
  if (item.Type === 'Audio') {
    if (item.AlbumId) return playContainer(item.AlbumId, { ...opts, startId: item.Id });
    return playTracks([item.Id], 0, opts);
  }
  return playContainer(item.Id, opts);
}

/* Plays an audiobook (AudioBook item id), resuming where this profile left off unless startTicks is given. */
export async function playAudiobook(id: string, opts: PlayOptions = {}): Promise<void> {
  try {
    const item = await getAudioItem(id);
    const book = bookInfo(item);
    let start = opts.startTicks !== undefined ? seconds(opts.startTicks) : seconds(item.UserData?.PlaybackPositionTicks);
    if (start >= book.duration - 5) start = 0;
    const entry = mk(item);
    set({
      mode: 'audiobook', current: entry, context: [entry], original: null, pos: 0, added: [], fromAdded: false,
      source: { kind: 'audiobook', id: item.Id, name: book.name }, shuffle: false, repeat: 'off', book, sleep: null,
      open: opts.open ?? state().open
    });
    await load(entry, start, true);
  } catch {
    toast(t('Couldn’t start playback'), 'error');
  }
}

/* ───────── Recently played sources (Jump back in) ───────── */

export interface RecentSource { kind: SourceKind; id: string; name: string; at: number }

function remember(src: Source) {
  if (!src.id) return;
  const user = currentUserId();
  const all = readLocal<Record<string, RecentSource[]>>(RECENT_KEY, {});
  const list = (all[user] ?? []).filter((r) => r.id !== src.id);
  list.unshift({ kind: src.kind, id: src.id, name: src.name, at: Date.now() });
  all[user] = list.slice(0, 24);
  writeLocal(RECENT_KEY, all);
}

export function recentSources(): RecentSource[] {
  return readLocal<Record<string, RecentSource[]>>(RECENT_KEY, {})[currentUserId()] ?? [];
}

/* ───────── Saving the queue ───────── */

const KEEP: (keyof Track)[] = [
  'Id', 'Name', 'Type', 'MediaType', 'Artists', 'ArtistItems', 'AlbumArtist', 'AlbumArtists', 'Album', 'AlbumId', 'AlbumPrimaryImageTag',
  'RunTimeTicks', 'IndexNumber', 'ParentIndexNumber', 'ProductionYear', 'HasLyrics', 'Genres', 'ParentId', 'Chapters', 'People', 'DateCreated'
];

function slim(e: Entry): Entry {
  const tk: Record<string, unknown> = {};
  for (const k of KEEP) if (e.track[k] !== undefined) tk[k] = e.track[k];
  if (e.track.ImageTags?.Primary) tk.ImageTags = { Primary: e.track.ImageTags.Primary };
  if (e.track.UserData) tk.UserData = { IsFavorite: e.track.UserData.IsFavorite, PlayCount: e.track.UserData.PlayCount };
  const audio = audioStreamOf(e.track);
  if (audio) tk.MediaStreams = [audio];
  return { key: e.key, track: tk as unknown as Track };
}

interface Saved {
  v: 1; user: string; mode: AudioState['mode']; current: Entry | null; fromAdded: boolean; context: Entry[]; original: Entry[] | null;
  pos: number; added: Entry[]; source: Source | null; shuffle: boolean; repeat: Repeat; book: BookInfo | null; position: number;
}

function save(now = false) {
  if (!now && Date.now() - lastSave < 5000) return;
  lastSave = Date.now();
  const s = state();
  if (!s.current) { writeLocal(QUEUE_KEY, null); return; }
  // Long contexts (a shuffled library) keep the part around the current song.
  const from = Math.max(0, s.pos - 50);
  const saved: Saved = {
    v: 1, user: currentUserId(), mode: s.mode, current: slim(s.current), fromAdded: s.fromAdded,
    context: s.context.slice(from, from + 500).map(slim), original: s.original && s.original.length <= 500 ? s.original.map(slim) : null,
    pos: s.pos - from, added: s.added.slice(0, 200).map(slim), source: s.source, shuffle: s.shuffle, repeat: s.repeat, book: s.book,
    position: position()
  };
  writeLocal(QUEUE_KEY, saved);
}

function restore() {
  if (state().current) return;
  const saved = readLocal<Saved | null>(QUEUE_KEY, null);
  if (!saved || saved.v !== 1 || saved.user !== currentUserId() || !saved.current) return;
  // Keys must stay unique after a reload.
  keySeq = Math.max(keySeq, 1000);
  set({
    mode: saved.mode, current: saved.current, fromAdded: saved.fromAdded, context: saved.context, original: saved.original, pos: saved.pos,
    added: saved.added, source: saved.source, shuffle: saved.shuffle, repeat: saved.repeat, book: saved.book, playing: false, loading: false
  });
  timeStore.set({ position: saved.position || 0, duration: durationOf(saved.current) });
  syncSession();
}

/* ───────── System media controls ───────── */

let lastPositionState = 0;

function artwork(track: Track): MediaImage[] {
  const tag = track.ImageTags?.Primary;
  const src = tag ? imageUrl(track.Id, 'Primary', { tag, maxWidth: 512 })
    : track.AlbumId && track.AlbumPrimaryImageTag ? imageUrl(track.AlbumId, 'Primary', { tag: track.AlbumPrimaryImageTag, maxWidth: 512 }) : '';
  return src ? [{ src, sizes: '512x512', type: 'image/jpeg' }] : [];
}

function syncSession() {
  if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
  const s = state();
  const tk = s.current?.track;
  try {
    if (!tk) { navigator.mediaSession.metadata = null; navigator.mediaSession.playbackState = 'none'; return; }
    const chapter = s.mode === 'audiobook' ? chapterAt(s.book, position()) : undefined;
    navigator.mediaSession.metadata = new MediaMetadata({
      title: chapter && s.book && s.book.chapters.length > 1 ? chapter.name : tk.Name,
      artist: s.mode === 'audiobook' ? s.book?.author ?? '' : (tk.Artists ?? []).join(', ') || tk.AlbumArtist || '',
      album: s.mode === 'audiobook' ? s.book?.name ?? '' : tk.Album ?? '',
      artwork: artwork(tk)
    });
    navigator.mediaSession.playbackState = s.playing ? 'playing' : 'paused';
  } catch { /* old browsers */ }
}

function syncPosition() {
  lastPositionState = Date.now();
  if (!('mediaSession' in navigator) || !navigator.mediaSession.setPositionState) return;
  const dur = durationOf(state().current);
  if (!dur) return;
  try { navigator.mediaSession.setPositionState({ duration: dur, position: Math.min(dur, position()), playbackRate: el?.playbackRate || 1 }); } catch { /* bad values */ }
}

function installSession() {
  if (typeof navigator === 'undefined' || !('mediaSession' in navigator)) return;
  const h = (action: MediaSessionAction, fn: (d: MediaSessionActionDetails) => void) => {
    try { navigator.mediaSession.setActionHandler(action, fn); } catch { /* unsupported action */ }
  };
  const book = () => state().mode === 'audiobook';
  h('play', () => play());
  h('pause', () => pause());
  h('stop', () => pause());
  h('previoustrack', () => (book() ? previousChapter() : previous()));
  h('nexttrack', () => (book() ? nextChapter() : next()));
  h('seekbackward', (d) => skip(-(d.seekOffset ?? (book() ? 30 : 10))));
  h('seekforward', (d) => skip(d.seekOffset ?? (book() ? 30 : 10)));
  h('seekto', (d) => { if (typeof d.seekTime === 'number') seek(d.seekTime); });
}

/* ───────── Start-up ───────── */

function init() {
  installSession();
  bootState.subscribe((b) => {
    if (b === 'ready') restore();
    if (b === 'signedOut' && state().current) stop();
  });
  if (bootState.get() === 'ready') restore();
  window.addEventListener('pagehide', () => { save(true); end(); });
  // Other Jellyfin apps controlling this one (the video player handles them while it's open).
  onRemote('Playstate', (d: { Command?: string; SeekPositionTicks?: number }) => {
    if (!state().current || /^\/play\//i.test(getLocation().path)) return;
    switch (d?.Command) {
      case 'PlayPause': togglePlay(); break;
      case 'Pause': pause(); break;
      case 'Unpause': play(); break;
      case 'Stop': pause(); break;
      case 'NextTrack': state().mode === 'audiobook' ? nextChapter() : next(); break;
      case 'PreviousTrack': state().mode === 'audiobook' ? previousChapter() : previous(); break;
      case 'Seek': if (typeof d.SeekPositionTicks === 'number') seek(seconds(d.SeekPositionTicks)); break;
      case 'Rewind': skip(-10); break;
      case 'FastForward': skip(30); break;
    }
  });
}

init();
