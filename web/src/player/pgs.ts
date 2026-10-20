/* PGS (Blu-ray picture subtitles, .sup) for "Show picture subtitles here": the server hands the stream over as it
   is and Seedy decodes the run-length bitmaps onto a canvas over the picture. */

interface PgsObject { id: number; width: number; height: number; data: Uint8Array }

export interface PgsPicture {
  x: number;
  y: number;
  width: number;
  height: number;
  data: Uint8Array;
  /* 256 colours as little-endian RGBA words, ready for an ImageData view. */
  palette: Uint32Array;
  crop?: { x: number; y: number; width: number; height: number };
}

export interface PgsCue {
  start: number;
  end: number;
  /* Size of the frame the pictures are placed on (normally the video's). */
  width: number;
  height: number;
  pictures: PgsPicture[];
}

/* Y, Cr, Cb, alpha (BT.709, limited range) to an RGBA word. */
function ycrcb(y: number, cr: number, cb: number, a: number): number {
  const Y = 1.164 * (y - 16);
  const r = Math.round(Y + 1.793 * (cr - 128));
  const g = Math.round(Y - 0.213 * (cb - 128) - 0.533 * (cr - 128));
  const b = Math.round(Y + 2.112 * (cb - 128));
  const c = (v: number) => (v < 0 ? 0 : v > 255 ? 255 : v);
  return ((a & 0xff) << 24 | c(b) << 16 | c(g) << 8 | c(r)) >>> 0;
}

export function parsePgs(buf: ArrayBuffer): PgsCue[] {
  const bytes = new Uint8Array(buf);
  const view = new DataView(buf);
  const cues: PgsCue[] = [];
  const palettes = new Map<number, Uint32Array>();
  const objects = new Map<number, PgsObject & { chunks: Uint8Array[] }>();
  type CompObj = { id: number; x: number; y: number; crop?: PgsPicture['crop'] };
  let comp: { pts: number; width: number; height: number; paletteId: number; objs: CompObj[] } | null = null;
  let pos = 0;
  while (pos + 13 <= bytes.length) {
    if (bytes[pos] !== 0x50 || bytes[pos + 1] !== 0x47) {
      // Lost sync: look for the next "PG".
      pos++;
      continue;
    }
    const pts = view.getUint32(pos + 2) / 90000;
    const type = bytes[pos + 10];
    const size = view.getUint16(pos + 11);
    const at = pos + 13;
    const end = at + size;
    if (end > bytes.length) break;
    // A segment too short for its own header is skipped (reading past it would throw or take the next one's bytes).
    const minSize = type === 0x16 ? 11 : type === 0x14 ? 2 : type === 0x15 ? 4 : 0;
    if (size < minSize) { pos = end; continue; }
    switch (type) {
      case 0x16: { // Presentation composition
        const width = view.getUint16(at);
        const height = view.getUint16(at + 2);
        const state = bytes[at + 7];
        const paletteId = bytes[at + 9];
        const count = bytes[at + 10];
        if (state & 0x80) { objects.clear(); palettes.clear(); }
        const objs: CompObj[] = [];
        let o = at + 11;
        for (let i = 0; i < count && o + 8 <= end; i++) {
          const id = view.getUint16(o);
          const cropped = (bytes[o + 3] & 0x40) !== 0;
          const x = view.getUint16(o + 4);
          const y = view.getUint16(o + 6);
          o += 8;
          let crop: PgsPicture['crop'];
          if (cropped && o + 8 <= end) {
            crop = { x: view.getUint16(o), y: view.getUint16(o + 2), width: view.getUint16(o + 4), height: view.getUint16(o + 6) };
            o += 8;
          }
          objs.push({ id, x, y, crop });
        }
        comp = { pts, width, height, paletteId, objs };
        break;
      }
      case 0x14: { // Palette definition
        const id = bytes[at];
        const pal = new Uint32Array(palettes.get(id) ?? new Uint32Array(256));
        for (let o = at + 2; o + 5 <= end; o += 5) pal[bytes[o]] = ycrcb(bytes[o + 1], bytes[o + 2], bytes[o + 3], bytes[o + 4]);
        palettes.set(id, pal);
        break;
      }
      case 0x15: { // Object definition (may span several segments)
        const id = view.getUint16(at);
        const seq = bytes[at + 3];
        if (seq & 0x80) {
          if (size < 11) break;
          const width = view.getUint16(at + 7);
          const height = view.getUint16(at + 9);
          objects.set(id, { id, width, height, data: new Uint8Array(0), chunks: [bytes.slice(at + 11, end)] });
        } else {
          objects.get(id)?.chunks.push(bytes.slice(at + 4, end));
        }
        if (seq & 0x40) {
          const ob = objects.get(id);
          if (ob) {
            const len = ob.chunks.reduce((n, c) => n + c.length, 0);
            const data = new Uint8Array(len);
            let off = 0;
            for (const c of ob.chunks) { data.set(c, off); off += c.length; }
            ob.data = data;
            ob.chunks = [];
          }
        }
        break;
      }
      case 0x80: { // End of display set
        if (comp) {
          const prev = cues[cues.length - 1];
          if (prev && prev.end === Infinity) prev.end = comp.pts;
          const palette = palettes.get(comp.paletteId) ?? new Uint32Array(256);
          const pictures: PgsPicture[] = [];
          for (const o of comp.objs) {
            const ob = objects.get(o.id);
            // A picture no frame could hold is damaged data; drawing it would need gigabytes.
            if (ob && ob.data.length && ob.width && ob.height && ob.width <= MAX_SIDE && ob.height <= MAX_SIDE) {
              pictures.push({ x: o.x, y: o.y, width: ob.width, height: ob.height, data: ob.data, palette: new Uint32Array(palette), crop: o.crop });
            }
          }
          cues.push({ start: comp.pts, end: pictures.length ? Infinity : comp.pts, width: comp.width, height: comp.height, pictures });
          comp = null;
        }
        break;
      }
      default:
        break; // 0x17 window definitions: the pictures carry their own positions
    }
    pos = end;
  }
  const last = cues[cues.length - 1];
  if (last && last.end === Infinity) last.end = last.start + 10;
  return cues.filter((c) => c.pictures.length > 0 && c.end > c.start);
}

/* Largest picture side Seedy decodes (PGS frames are at most 4K). */
const MAX_SIDE = 4096;

/* Run-length decode one picture into RGBA pixels; null when the picture can't be drawn. */
export function decodePicture(p: PgsPicture): ImageData | null {
  if (!(p.width > 0 && p.height > 0 && p.width <= MAX_SIDE && p.height <= MAX_SIDE)) return null;
  let img: ImageData;
  try { img = new ImageData(p.width, p.height); } catch { return null; }
  const out = new Uint32Array(img.data.buffer);
  const rle = p.data;
  const pal = p.palette;
  const total = p.width * p.height;
  let i = 0;
  let px = 0;
  let row = 0;
  while (i < rle.length && px < total) {
    const c = rle[i++];
    if (c !== 0) { out[px++] = pal[c]; continue; }
    const b = rle[i++];
    if (b === 0) { row++; px = row * p.width; continue; }
    let len: number;
    let color = 0;
    switch (b & 0xc0) {
      case 0x00: len = b & 0x3f; break;
      case 0x40: len = ((b & 0x3f) << 8) | rle[i++]; break;
      case 0x80: len = b & 0x3f; color = rle[i++]; break;
      default: len = ((b & 0x3f) << 8) | rle[i++]; color = rle[i++]; break;
    }
    const v = pal[color];
    const stop = Math.min(total, px + len);
    if (v) out.fill(v, px, stop);
    px = stop;
  }
  return img;
}

/* The cue on screen at `t`, if any (cues are sorted and don't overlap). */
export function activePgs(cues: PgsCue[], t: number): PgsCue | null {
  let lo = 0;
  let hi = cues.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (cues[mid].start <= t) lo = mid + 1;
    else hi = mid - 1;
  }
  const c = cues[hi];
  return c && t < c.end ? c : null;
}

export async function loadPgs(url: string, signal?: AbortSignal): Promise<PgsCue[]> {
  const res = await fetch(url, { signal });
  if (!res.ok) throw new Error(`Subtitle request failed (${res.status})`);
  return parsePgs(await res.arrayBuffer());
}
