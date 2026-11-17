import { useEffect, useRef, useState } from 'preact/hooks';
import { trickplayTileUrl } from '../api/images';
import type { BaseItem } from '../api/types';
import { trickplayFor } from './Scrubber';

/* Black bars baked into the picture: a 2.39:1 film stored in a 16:9 frame, as on 4K discs. On a screen wider than
   that frame, fitting it leaves black on all four sides, so the player enlarges the frame until only those bars
   leave the screen. The bars are read from the item's trickplay thumbnails (spread over the film, the same for every
   engine), or else from frames of the playing <video>. */

/* Share of the frame each bar takes (0 when that side has none). */
export interface Bars { top: number; bottom: number; left: number; right: number }

export const NO_BARS: Bars = { top: 0, bottom: 0, left: 0, right: 0 };

/* One picture's black edges, in sampled rows/columns. */
interface Scan { top: number; bottom: number; left: number; right: number; rows: number; cols: number; lit: boolean }

/* A row (or column) is a bar when it is dark on average and has no bright point (stars, a lamp). */
const BAR_MEAN = 20;
const BAR_PEAK = 56;
/* A picture counts once its content is bright enough that its edges show. */
const LIT_MEAN = 28;

function scan(px: Uint8ClampedArray, w: number, h: number): Scan | null {
  const luma = new Uint8Array(w * h);
  for (let i = 0, j = 0; j < luma.length; i += 4, j++) luma[j] = (px[i] * 54 + px[i + 1] * 183 + px[i + 2] * 19) >> 8;
  const isBar = (sum: number, peak: number, n: number) => sum / n < BAR_MEAN && peak < BAR_PEAK;
  const rowBar = (y: number) => {
    let sum = 0;
    let peak = 0;
    for (let x = 0, o = y * w; x < w; x++, o++) { sum += luma[o]; if (luma[o] > peak) peak = luma[o]; }
    return isBar(sum, peak, w);
  };
  let top = 0;
  while (top < h && rowBar(top)) top++;
  if (top >= h * 0.9) return null;
  let bottom = 0;
  while (bottom < h - top && rowBar(h - 1 - bottom)) bottom++;
  // Columns only over the picture's rows, so the bars above and below don't darken them.
  const colBar = (x: number) => {
    let sum = 0;
    let peak = 0;
    for (let y = top, o = top * w + x; y < h - bottom; y++, o += w) { sum += luma[o]; if (luma[o] > peak) peak = luma[o]; }
    return isBar(sum, peak, h - top - bottom);
  };
  let left = 0;
  while (left < w && colBar(left)) left++;
  let right = 0;
  while (right < w - left && colBar(w - 1 - right)) right++;
  let sum = 0;
  for (let y = top; y < h - bottom; y++) for (let x = left, o = y * w + left; x < w - right; x++, o++) sum += luma[o];
  const area = (h - top - bottom) * (w - left - right);
  return { top, bottom, left, right, rows: h, cols: w, lit: area > 0 && sum / area >= LIT_MEAN };
}

/* Bars every lit picture agrees on: a dark scene only makes the bars look taller, so the smallest one wins. One
   side's bars count when both are there and about equal (letterboxing is centred); a sliver of picture past the
   edge reads better than a black line, so they're taken one sample and a little more generously. */
function combine(scans: Scan[], minLit: number): Bars | null {
  const lit = scans.filter((s) => s.lit);
  if (lit.length < minLit) return null;
  const min = (k: 'top' | 'bottom' | 'left' | 'right', n: 'rows' | 'cols') => Math.min(...lit.map((s) => s[k] / s[n]));
  const pair = (a: number, b: number, unit: number): [number, number] => {
    if (a < 0.02 || b < 0.02 || Math.abs(a - b) > 0.025 || a + b > 0.5) return [0, 0];
    return [a + unit + 0.002, b + unit + 0.002];
  };
  const [top, bottom] = pair(min('top', 'rows'), min('bottom', 'rows'), 1 / lit[0].rows);
  const [left, right] = pair(min('left', 'cols'), min('right', 'cols'), 1 / lit[0].cols);
  return { top, bottom, left, right };
}

const hasBars = (b: Bars) => b.top + b.bottom + b.left + b.right > 0;

function surface(w: number, h: number): CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D | null {
  const c: HTMLCanvasElement | OffscreenCanvas = typeof OffscreenCanvas !== 'undefined'
    ? new OffscreenCanvas(w, h)
    : Object.assign(document.createElement('canvas'), { width: w, height: h });
  return c.getContext('2d', { willReadFrequently: true }) as CanvasRenderingContext2D | OffscreenCanvasRenderingContext2D | null;
}

/* Reads up to two thumbnail sheets from the middle of the film (logos and credits sit at the ends). */
async function fromTrickplay(item: BaseItem, mediaSourceId: string | undefined, signal: AbortSignal): Promise<Bars | null> {
  const trick = trickplayFor(item, mediaSourceId);
  if (!trick) return null;
  const { info, width } = trick;
  const perSheet = info.TileWidth * info.TileHeight;
  if (!perSheet || !info.Width || !info.Height || !info.ThumbnailCount) return null;
  const sheets = Math.ceil(info.ThumbnailCount / perSheet);
  const picks = sheets >= 3 ? [Math.floor(sheets / 3), Math.floor((sheets * 2) / 3)] : [Math.floor(sheets / 2)];
  const ctx = surface(info.Width, info.Height);
  if (!ctx) return null;
  const scans: Scan[] = [];
  for (const sheet of picks) {
    const res = await fetch(trickplayTileUrl(item.Id, width, sheet, mediaSourceId), { signal });
    if (!res.ok) continue;
    const img = await createImageBitmap(await res.blob());
    const count = Math.min(perSheet, info.ThumbnailCount - sheet * perSheet);
    for (let n = 0; n < count; n++) {
      const col = n % info.TileWidth;
      const row = Math.floor(n / info.TileWidth);
      ctx.clearRect(0, 0, info.Width, info.Height);
      ctx.drawImage(img, col * info.Width, row * info.Height, info.Width, info.Height, 0, 0, info.Width, info.Height);
      const s = scan(ctx.getImageData(0, 0, info.Width, info.Height).data, info.Width, info.Height);
      if (s) scans.push(s);
    }
    img.close();
  }
  // Short items have only a few thumbnails.
  return combine(scans, Math.min(8, Math.max(3, Math.ceil(scans.length / 2))));
}

/* Bars of the item being played, or null while unknown (and when there are none). `frame` hands over what's on
   screen for the fallback: the <video> when it has a picture, else null. */
export function useBlackBars(item: BaseItem, mediaSourceId: string | undefined, enabled: boolean, frame: () => HTMLVideoElement | null): Bars | null {
  const [bars, setBars] = useState<Bars | null>(null);
  const key = item.Id + ':' + (mediaSourceId ?? '');
  const frameRef = useRef(frame);
  frameRef.current = frame;

  useEffect(() => {
    setBars(null);
    if (!enabled) return;
    const ctrl = new AbortController();
    let timer = 0;
    const scans: Scan[] = [];
    let ctx: ReturnType<typeof surface> = null;
    let lastTime = -1;
    let ticks = 0;
    const show = (b: Bars | null) => {
      if (ctrl.signal.aborted) return;
      setBars((prev) => {
        const next = b && hasBars(b) ? b : null;
        const same = prev && next && Math.abs(prev.top - next.top) < 0.004 && Math.abs(prev.bottom - next.bottom) < 0.004
          && Math.abs(prev.left - next.left) < 0.004 && Math.abs(prev.right - next.right) < 0.004;
        return same ? prev : next;
      });
    };
    /* Fallback: a frame every few seconds of playback; the answer only ever shrinks as new scenes come in. */
    const sample = () => {
      timer = 0;
      if (ctrl.signal.aborted) return;
      const v = frameRef.current();
      if (v && document.visibilityState === 'visible' && v.readyState >= 2 && v.videoWidth && v.currentTime !== lastTime) {
        lastTime = v.currentTime;
        const w = 320;
        const h = Math.max(2, Math.min(360, Math.round((w * v.videoHeight) / v.videoWidth)));
        try {
          if (!ctx || ctx.canvas.width !== w || ctx.canvas.height !== h) ctx = surface(w, h);
          if (!ctx) return;
          ctx.drawImage(v, 0, 0, w, h);
          const s = scan(ctx.getImageData(0, 0, w, h).data, w, h);
          if (s) scans.push(s);
          const b = combine(scans, 4);
          if (b) show(b);
        } catch {
          return; // a picture the page may not read: stay as is
        }
      }
      if (++ticks < 90) timer = window.setTimeout(sample, scans.filter((s) => s.lit).length < 4 ? 2000 : 8000);
    };
    fromTrickplay(item, mediaSourceId, ctrl.signal)
      .catch(() => null)
      .then((b) => {
        if (ctrl.signal.aborted) return;
        if (b) show(b);
        else timer = window.setTimeout(sample, 1000);
      });
    return () => { ctrl.abort(); window.clearTimeout(timer); };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key, enabled]);

  return enabled ? bars : null;
}

/* The window's size, kept current (the player covers it). */
export function useViewport(): { w: number; h: number } {
  const [v, setV] = useState(() => ({ w: window.innerWidth, h: window.innerHeight }));
  useEffect(() => {
    const read = () => setV((p) => (p.w === window.innerWidth && p.h === window.innerHeight ? p : { w: window.innerWidth, h: window.innerHeight }));
    window.addEventListener('resize', read);
    read();
    return () => window.removeEventListener('resize', read);
  }, []);
  return v;
}

/* Where the frame element goes so its picture, without the bars, fills `box` as far as it can (null: as it is). */
export function cropRect(bars: Bars, vw: number, vh: number, boxW: number, boxH: number): { left: number; top: number; width: number; height: number } | null {
  if (!vw || !vh || !boxW || !boxH) return null;
  const k = Math.min(boxW / vw, boxH / vh);
  const fw = vw * k;
  const fh = vh * k;
  const pw = fw * (1 - bars.left - bars.right);
  const ph = fh * (1 - bars.top - bars.bottom);
  if (pw <= 0 || ph <= 0) return null;
  const s = Math.min(boxW / pw, boxH / ph);
  if (s < 1.01) return null;
  // The picture's centre sits off the frame's when the bars differ a little; bring it to the middle.
  const dx = ((bars.left - bars.right) / 2) * fw * s;
  const dy = ((bars.top - bars.bottom) / 2) * fh * s;
  const width = boxW * s;
  const height = boxH * s;
  return { left: (boxW - width) / 2 - dx, top: (boxH - height) / 2 - dy, width, height };
}
