import { bookFileUrl } from './api';

/* The language an EPUB says it's in (dc:language), which Jellyfin doesn't keep. Read without downloading the book:
   a range request for the end of the file (the zip's directory), then the two small entries that matter
   (META-INF/container.xml and the package document). Remembered per book on this device. */

interface Entry { method: number; compressed: number; offset: number }

const u16 = (b: DataView, o: number) => b.getUint16(o, true);
const u32 = (b: DataView, o: number) => b.getUint32(o, true);

async function range(url: string, spec: string): Promise<{ buf: ArrayBuffer; total: number; start: number }> {
  const res = await fetch(url, { headers: { Range: `bytes=${spec}` } });
  if (res.status !== 206) throw new Error('no ranges');
  const m = /bytes (\d+)-(\d+)\/(\d+)/.exec(res.headers.get('content-range') ?? '');
  return { buf: await res.arrayBuffer(), total: Number(m?.[3] ?? 0), start: Number(m?.[1] ?? 0) };
}

function directory(view: DataView, base: number): Map<string, Entry> {
  // End of central directory: the last "PK\x05\x06".
  let eocd = -1;
  for (let i = view.byteLength - 22; i >= 0; i--) if (u32(view, i) === 0x06054b50) { eocd = i; break; }
  if (eocd < 0) throw new Error('not a zip');
  const count = u16(view, eocd + 10);
  let p = u32(view, eocd + 16) - base;
  const out = new Map<string, Entry>();
  const dec = new TextDecoder();
  for (let n = 0; n < count && p >= 0 && p + 46 <= view.byteLength; n++) {
    if (u32(view, p) !== 0x02014b50) break;
    const nameLen = u16(view, p + 28), extra = u16(view, p + 30), comment = u16(view, p + 32);
    const name = dec.decode(new Uint8Array(view.buffer, view.byteOffset + p + 46, nameLen));
    out.set(name, { method: u16(view, p + 10), compressed: u32(view, p + 20), offset: u32(view, p + 42) });
    p += 46 + nameLen + extra + comment;
  }
  return out;
}

async function read(url: string, e: Entry): Promise<string> {
  const { buf } = await range(url, `${e.offset}-${e.offset + 30 + 1024 + e.compressed}`);
  const v = new DataView(buf);
  const start = 30 + u16(v, 26) + u16(v, 28);
  const data = new Uint8Array(buf, start, e.compressed);
  if (e.method === 0) return new TextDecoder().decode(data);
  const stream = new Blob([data]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
  return new Response(stream).text();
}

const cache = new Map<string, Promise<string | undefined>>();

export function epubLanguage(id: string): Promise<string | undefined> {
  const key = 'seedy.bookLang.' + id;
  try {
    const hit = localStorage.getItem(key);
    if (hit !== null) return Promise.resolve(hit || undefined);
  } catch { /* storage unavailable */ }
  let p = cache.get(id);
  if (p) return p;
  p = (async () => {
    const url = bookFileUrl(id);
    const tail = await range(url, '-65536').catch(() => null);
    if (!tail) return undefined;
    const files = directory(new DataView(tail.buf), tail.start);
    const container = files.get('META-INF/container.xml');
    if (!container) return undefined;
    const rootPath = /full-path="([^"]+)"/.exec(await read(url, container))?.[1];
    const opf = rootPath ? files.get(rootPath) : undefined;
    if (!opf) return undefined;
    const lang = /<dc:language[^>]*>\s*([^<\s]+)\s*<\/dc:language>/i.exec(await read(url, opf))?.[1];
    try { localStorage.setItem(key, lang ?? ''); } catch { /* fine */ }
    return lang;
  })().catch(() => undefined);
  cache.set(id, p);
  return p;
}
