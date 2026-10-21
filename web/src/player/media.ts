/* What the player drives: the page's <video> element, or the experimental player standing in for it
   (soft-media.ts). Both answer the same HTMLMediaElement properties and events. */

export interface MediaLike extends EventTarget {
  currentTime: number;
  readonly duration: number;
  readonly paused: boolean;
  readonly ended: boolean;
  volume: number;
  muted: boolean;
  playbackRate: number;
  readonly buffered: TimeRanges;
  readonly videoWidth: number;
  readonly videoHeight: number;
  play(): Promise<void>;
  pause(): void;
}

export const MEDIA_EVENTS = [
  'timeupdate', 'progress', 'durationchange', 'loadedmetadata', 'resize', 'play', 'pause', 'playing', 'waiting',
  'seeking', 'seeked', 'canplay', 'volumechange', 'ratechange', 'ended'
] as const;

/* One target for the player's listeners while each stream picks its engine. Volume, mute and speed carry over. */
export class MediaBridge extends EventTarget implements MediaLike {
  private cur: MediaLike;
  private unbind: () => void = () => undefined;

  constructor(readonly video: HTMLVideoElement) {
    super();
    this.cur = video;
    this.bind(video);
  }

  get current(): MediaLike { return this.cur; }

  use(next: MediaLike) {
    if (next === this.cur) return;
    const prev = this.cur;
    this.unbind();
    next.volume = prev.volume;
    next.muted = prev.muted;
    next.playbackRate = prev.playbackRate;
    this.cur = next;
    this.bind(next);
  }

  private bind(m: MediaLike) {
    const forward = (e: Event) => this.dispatchEvent(new Event(e.type));
    MEDIA_EVENTS.forEach((t) => m.addEventListener(t, forward));
    this.unbind = () => MEDIA_EVENTS.forEach((t) => m.removeEventListener(t, forward));
  }

  get currentTime() { return this.cur.currentTime; }
  set currentTime(v: number) { this.cur.currentTime = v; }
  get duration() { return this.cur.duration; }
  get paused() { return this.cur.paused; }
  get ended() { return this.cur.ended; }
  get volume() { return this.cur.volume; }
  set volume(v: number) { this.cur.volume = v; }
  get muted() { return this.cur.muted; }
  set muted(v: boolean) { this.cur.muted = v; }
  get playbackRate() { return this.cur.playbackRate; }
  set playbackRate(v: number) { this.cur.playbackRate = v; }
  get buffered() { return this.cur.buffered; }
  get videoWidth() { return this.cur.videoWidth; }
  get videoHeight() { return this.cur.videoHeight; }
  play() { return this.cur.play(); }
  pause() { this.cur.pause(); }
}
