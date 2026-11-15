import type AVPlayer from '@libmedia/avplayer';
import type { MediaLike } from './media';

declare const __LIBMEDIA_VERSION__: string;

/* The experimental player's engine: libmedia's AVPlayer decoding into a canvas (WebAssembly, or the browser's own
   decoders through WebCodecs), dressed as a media element so the player drives it like its <video>. It decodes on
   several threads when the page is cross-origin isolated. When decoding can't keep up it stops with the error code
   'slow', so the player can hand the stream back to the server. */

const libBase = () => new URL(`${import.meta.env.BASE_URL}assets/libmedia-${__LIBMEDIA_VERSION__}/`, document.baseURI).href;

let lib: Promise<typeof AVPlayer> | null = null;
function loadLib(): Promise<typeof AVPlayer> {
  lib ??= import(/* @vite-ignore */ libBase() + 'avplayer.js').then((m: { default: typeof AVPlayer }) => {
    // Fatal only: libmedia logs every expected fallback (no hardware decoder, no WebGPU) as a warning or an error.
    m.default.setLogLevel(5);
    return m.default;
  });
  lib.catch(() => { lib = null; });
  return lib;
}

const NO_RANGES = {
  length: 0,
  start() { throw new DOMException('No ranges', 'IndexSizeError'); },
  end() { throw new DOMException('No ranges', 'IndexSizeError'); }
} as TimeRanges;

const MEDIA_TYPES = ['Video', 'Audio', 'Data', 'Subtitle'];
const COVER_ART = 0x400;

/* Resolves once the <video> has a first frame (picture in picture refuses an element without one). */
function hasPicture(video: HTMLVideoElement, ms: number) {
  return new Promise<void>((resolve, reject) => {
    if (video.readyState >= HTMLMediaElement.HAVE_METADATA) return resolve();
    const done = (ok: boolean) => {
      window.clearTimeout(timer);
      video.removeEventListener('loadedmetadata', loaded);
      if (ok) resolve();
      else reject(new DOMException('No picture yet', 'InvalidStateError'));
    };
    const loaded = () => done(true);
    const timer = window.setTimeout(() => done(false), ms);
    video.addEventListener('loadedmetadata', loaded);
  });
}

export interface SoftOpenOptions {
  /* Which of the file's own audio tracks to play, counting from 0 (the server numbers external files first). */
  audioTrack?: number;
  /* The video's frame rate, to tell when decoding falls behind. */
  fps?: number;
  /* Container, as libmedia names it (mkv, mp4, ts, avi, m3u8). */
  ext?: string;
}

export type SoftErrorCode = 'slow' | 'failed';

export class SoftMedia extends EventTarget implements MediaLike {
  error: { code: SoftErrorCode; message: string } | null = null;
  private player: AVPlayer | null = null;
  private ready = false;
  private started = false;
  /* The viewer wants it playing; libmedia may still be starting. */
  private want = false;
  private running = false;
  private finished = false;
  private seeking = false;
  private nextSeek: number | null = null;
  /* Audio is blocked until a click: the next play() resumes it. */
  private blocked = false;
  private time = 0;
  private dur = NaN;
  private width = 0;
  private height = 0;
  private vol = 1;
  private mute = false;
  private rate = 1;
  private fps = 0;
  private destroyed = false;
  private timer = 0;
  private resizer: ResizeObserver | null = null;
  /* This player's own box in the host: a replaced player may still be shutting down in its own. */
  private box: HTMLDivElement;
  private lastMove = 0;
  private stalled = false;
  /* Rendered frames and clock over the last few seconds, against what the video's frame rate needs. */
  private watch = { at: 0, frames: 0, time: 0, behind: 0, graceUntil: 0 };
  /* Picture in picture: the page's <video> plays a copy of the canvas while the browser's window is up. */
  private pip: { video: HTMLVideoElement; stream: MediaStream; onPause: () => void; onLeave: () => void } | null = null;
  /* A picture in picture window this player takes over once its canvas exists (after a track change). */
  private pipNext: HTMLVideoElement | null = null;

  constructor(host: HTMLElement) {
    super();
    this.box = document.createElement('div');
    this.box.className = 'pl-soft-box';
    host.appendChild(this.box);
  }

  private emit(type: string) {
    if (!this.destroyed) this.dispatchEvent(new Event(type));
  }

  async open(url: string, startSeconds: number, o: SoftOpenOptions = {}) {
    this.time = Math.max(0, startSeconds);
    this.fps = o.fps && o.fps > 0 ? o.fps : 0;
    try {
      const AVPlayerClass = await loadLib();
      if (this.destroyed) return;
      const p: AVPlayer = new AVPlayerClass({
        container: this.box,
        wasmBaseUrl: libBase().replace(/\/$/, ''),
        enableHardware: true,
        enableWebCodecs: true,
        enableWorker: true,
        // Always decode here: playing through the <video> element is what the normal player already does.
        checkUseMSE: () => false,
        findBestStream: (streams, type) => {
          const info = p.getStreams();
          const kind = MEDIA_TYPES[type as number];
          const ofType = streams.filter((s) => info.find((i) => i.index === s.index)?.mediaType === kind);
          if (kind === 'Audio' && o.audioTrack !== undefined) {
            const picked = [...ofType].sort((a, b) => a.index - b.index)[o.audioTrack];
            if (picked) return picked;
          }
          const usable = ofType.filter((s) => !(s.disposition & COVER_ART));
          return usable.find((s) => s.disposition & 1) ?? usable[0];
        }
      });
      this.player = p;
      // libmedia stops drawing in a hidden page unless it is in picture in picture, which it only knows for its own windows.
      p.isPictureInPicture = () => this.inPip();
      p.on('played', () => {
        this.running = true;
        this.stalled = false;
        this.lastMove = performance.now();
        this.emit('playing');
      });
      p.on('ended', () => {
        this.running = false;
        this.want = false;
        this.finished = true;
        this.time = this.dur || this.time;
        this.mirrorPip();
        this.emit('timeupdate');
        this.emit('pause');
        this.emit('ended');
      });
      p.on('error', (e: unknown) => this.fail('failed', e));
      // The browser won't start sound without a click: wait paused for one rather than play silently.
      p.on('resume', () => {
        this.blocked = true;
        this.pause();
      });
      p.setVolume(this.mute ? 0 : this.vol);
      await p.load(url, { ext: o.ext });
      if (this.destroyed) return;
      this.dur = Number(p.getDuration()) / 1000 || NaN;
      const v = p.getStreams().find((s) => s.mediaType === 'Video' && !(s.disposition & COVER_ART));
      this.width = v?.codecparProxy.width ?? 0;
      this.height = v?.codecparProxy.height ?? 0;
      this.emit('durationchange');
      this.emit('loadedmetadata');
      this.emit('resize');
      if (this.time > 0) await p.seek(BigInt(Math.round(this.time * 1000)));
      if (this.destroyed) return;
      this.ready = true;
      this.emit('canplay');
      this.resizer = new ResizeObserver(() => p.resize(this.box.clientWidth, this.box.clientHeight));
      this.resizer.observe(this.box);
      this.timer = window.setInterval(() => this.tick(), 250);
      if (this.rate !== 1) p.setPlaybackRate(this.rate);
      if (this.want) await this.start();
      const next = this.pipNext;
      this.pipNext = null;
      if (next && document.pictureInPictureElement === next) await this.pictureInPicture(next).catch(() => undefined);
    } catch (e) {
      this.fail('failed', e);
    }
  }

  private async start() {
    const p = this.player;
    if (!p || this.destroyed) return;
    if (this.blocked || p.isSuspended()) {
      await p.resume().catch(() => undefined);
      this.blocked = p.isSuspended();
      if (this.blocked) {
        this.pause();
        return;
      }
    }
    this.watch.graceUntil = performance.now() + 5000;
    if (!this.started) {
      this.started = true;
      await p.play({ audio: true, video: true, subtitle: false });
    } else await p.play();
  }

  private tick() {
    const p = this.player;
    if (!p || !this.ready || this.seeking || !this.running || !this.want) return;
    const now = performance.now();
    const t = Number(p.currentTime) / 1000;
    if (Math.abs(t - this.time) > 0.001) {
      this.time = t;
      this.lastMove = now;
      if (this.stalled) {
        this.stalled = false;
        this.emit('playing');
      }
      this.emit('timeupdate');
    } else if (!this.stalled && now - this.lastMove > 750) {
      this.stalled = true;
      this.emit('waiting');
    }
    if (now - this.watch.at >= 4000) this.checkSpeed(p, now);
  }

  /* Behind for two windows in a row with packets waiting: decoding, not the network, is what's slow. */
  private checkSpeed(p: AVPlayer, now: number) {
    const w = this.watch;
    // A hidden page (another tab, a minimized or covered window) draws no frames: libmedia skips what nobody sees.
    if (document.visibilityState === 'hidden' && !this.inPip()) {
      w.at = 0;
      w.behind = 0;
      w.graceUntil = now + 5000;
      return;
    }
    const s = p.getStats();
    const frames = Number(s.videoFrameRenderCount);
    if (w.at && now >= w.graceUntil && !this.stalled) {
      const secs = (now - w.at) / 1000;
      const rendered = (frames - w.frames) / secs;
      const clock = (this.time - w.time) / secs;
      const behind = (this.fps > 0 && this.width > 0 && rendered < this.fps * this.rate * 0.9) || clock < this.rate * 0.85;
      w.behind = behind && Number(s.videoPacketQueueLength) > 0 ? w.behind + 1 : 0;
      if (w.behind >= 2) {
        this.fail('slow', new Error(`${rendered.toFixed(1)} of ${(this.fps * this.rate).toFixed(1)} fps, clock ×${clock.toFixed(2)}`));
        return;
      }
    }
    w.at = now;
    w.frames = frames;
    w.time = this.time;
  }

  private fail(code: SoftErrorCode, err?: unknown) {
    if (this.error || this.destroyed) return;
    console.warn('[seedy] experimental player:', code, err);
    this.error = { code, message: err instanceof Error ? err.message : String(err ?? '') };
    this.want = false;
    if (this.started) void this.player?.pause().catch(() => undefined);
    this.mirrorPip();
    this.emit('error');
  }

  private async seek(to: number) {
    const target = Math.max(0, Number.isFinite(this.dur) ? Math.min(to, this.dur) : to);
    this.time = target;
    this.finished = false;
    const p = this.player;
    // Before the file is open, this is where it will start.
    if (!p || !this.ready) return;
    if (this.seeking) {
      this.nextSeek = target;
      return;
    }
    this.seeking = true;
    this.emit('seeking');
    try {
      await p.seek(BigInt(Math.round(target * 1000)));
    } catch (e) {
      console.warn('[seedy] experimental player: seek failed', e);
    }
    this.seeking = false;
    if (this.destroyed) return;
    if (this.nextSeek !== null) {
      const next = this.nextSeek;
      this.nextSeek = null;
      await this.seek(next);
      return;
    }
    // Seeking back from the end restarts libmedia; keep it paused if the viewer was.
    if (!this.want && p.getStatus() === 6) await p.pause().catch(() => undefined);
    this.watch.graceUntil = performance.now() + 5000;
    this.lastMove = performance.now();
    this.emit('seeked');
    this.emit('timeupdate');
  }

  /* ── Picture in picture ── */

  private inPip() {
    return !!this.pip && document.pictureInPictureElement === this.pip.video;
  }

  /* Opens picture in picture on the page's <video>, playing a live copy of the canvas (or moves an open window to it). */
  async pictureInPicture(video: HTMLVideoElement) {
    const canvas = this.box.querySelector('canvas');
    if (!this.ready || !canvas) throw new DOMException('Nothing to show yet', 'InvalidStateError');
    this.endPip(false);
    const stream = canvas.captureStream();
    video.srcObject = stream;
    video.muted = true;
    void video.play().catch(() => undefined);
    // A paused canvas sends no frame: libmedia draws the waiting one again when resized.
    if (!this.want) this.player?.resize(this.box.clientWidth, this.box.clientHeight);
    try {
      await hasPicture(video, 3000);
      if (this.destroyed) throw new DOMException('The experimental player stopped', 'AbortError');
      if (document.pictureInPictureElement !== video) await video.requestPictureInPicture();
    } catch (e) {
      stream.getTracks().forEach((t) => t.stop());
      if (video.srcObject === stream) video.srcObject = null;
      throw e;
    }
    // The window's close button pauses its video: pause the film too, as it would the normal player.
    const onPause = () => { if (this.want && this.pip?.video === video) this.pause(); };
    const onLeave = () => this.endPip(true);
    video.addEventListener('pause', onPause);
    video.addEventListener('leavepictureinpicture', onLeave);
    this.pip = { video, stream, onPause, onLeave };
    this.mirrorPip();
  }

  /* After a track change, this player takes over the window the previous one had open on the <video>. */
  keepPictureInPicture(video: HTMLVideoElement) {
    this.pipNext = video;
  }

  /* The window's play/pause button follows the film. */
  private mirrorPip() {
    const v = this.pip?.video;
    if (!v) return;
    if (this.want) void v.play().catch(() => undefined);
    else v.pause();
  }

  /* Stops the copy; `clear` also empties the <video> (a replaced player leaves its last frame until the next one). */
  private endPip(clear: boolean) {
    const pip = this.pip;
    if (!pip) return;
    this.pip = null;
    pip.video.removeEventListener('pause', pip.onPause);
    pip.video.removeEventListener('leavepictureinpicture', pip.onLeave);
    if (!clear) return;
    pip.stream.getTracks().forEach((t) => t.stop());
    if (pip.video.srcObject === pip.stream) pip.video.srcObject = null;
  }

  /* ── HTMLMediaElement ── */

  /* Live while playing (the comparison keeps in step with it), else where it stopped or is going. */
  get currentTime() {
    if (this.running && this.want && !this.seeking && this.player) return Number(this.player.currentTime) / 1000;
    return this.time;
  }
  set currentTime(v: number) { void this.seek(v); }
  get duration() { return this.dur; }
  get paused() { return !this.want; }
  get ended() { return this.finished; }
  get buffered() { return NO_RANGES; }
  get videoWidth() { return this.width; }
  get videoHeight() { return this.height; }

  get volume() { return this.vol; }
  set volume(v: number) {
    this.vol = Math.max(0, Math.min(1, v));
    this.player?.setVolume(this.mute ? 0 : this.vol);
    this.emit('volumechange');
  }
  get muted() { return this.mute; }
  set muted(v: boolean) {
    this.mute = v;
    this.player?.setVolume(this.mute ? 0 : this.vol);
    this.emit('volumechange');
  }
  get playbackRate() { return this.rate; }
  set playbackRate(v: number) {
    this.rate = v;
    if (this.ready) this.player?.setPlaybackRate(v);
    this.watch.graceUntil = performance.now() + 5000;
    this.emit('ratechange');
  }

  async play() {
    if (this.error) throw new DOMException('The experimental player stopped', 'NotSupportedError');
    if (!this.want) {
      this.want = true;
      this.mirrorPip();
      this.emit('play');
    }
    if (this.finished) await this.seek(0);
    if (!this.ready) {
      this.emit('waiting');
      return;
    }
    await this.start();
    if (this.blocked) throw new DOMException('Sound needs a click first', 'NotAllowedError');
  }

  pause() {
    const was = this.want;
    this.want = false;
    this.running = false;
    if (this.started) void this.player?.pause().catch(() => undefined);
    this.mirrorPip();
    if (was) this.emit('pause');
  }

  /* Stop decoding and release the canvas, threads and memory. */
  async destroy() {
    if (this.destroyed) return;
    this.destroyed = true;
    window.clearInterval(this.timer);
    this.resizer?.disconnect();
    this.endPip(false);
    const p = this.player;
    this.player = null;
    if (p) {
      try { await p.destroy(); } catch { /* already gone */ }
    }
    this.box.remove();
  }
}
