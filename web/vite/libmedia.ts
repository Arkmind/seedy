import type { Plugin } from 'vite';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

/* The experimental player (src/player/soft-media.ts) runs libmedia's AVPlayer. Its prebuilt ES module is served as it
   is, because it loads its own chunks and workers from next to itself, at assets/libmedia-<version>/. libmedia publishes
   its WebAssembly decoders in its repository rather than on npm: the ones Seedy uses are downloaded once, checked
   against these hashes and cached in node_modules. The dev server also isolates ?isolate=1 page loads like the plugin. */

const PKG = fileURLToPath(new URL('../node_modules/@libmedia/avplayer/', import.meta.url));
const VERSION: string = JSON.parse(readFileSync(join(PKG, 'package.json'), 'utf8')).version;
const BASE = `assets/libmedia-${VERSION}`;
const CACHE = fileURLToPath(new URL(`../node_modules/.cache/seedy-libmedia/${VERSION}/`, import.meta.url));

/* Video decoders for what browsers can't decode themselves (AV1 and VP9 go through WebCodecs), audio decoders for
   everything a film may carry but TrueHD, and the resampler and time stretcher the audio path needs. */
const WASM: Record<string, string> = {
  'decode/hevc-simd.wasm': '63576fc0752535da9f7f1374e89ab12c22a0cd4425639f0f8162e589af7faf27',
  'decode/h264-simd.wasm': '0c7ff22730ef4ee7b4f99e57d60b6b6212eb2a9b75de1e8190b1501d97212295',
  'decode/mpeg2video-simd.wasm': 'c0873083a71e3965777d170191e10d0c797a46bbe10f00bebda86451a7ce8c5b',
  'decode/mpeg4-simd.wasm': 'a47ab5e8ec658609d64daa955e2ba97a0e72bb88a909485109568b144a3661a3',
  'decode/aac-simd.wasm': 'd261e0f99e83e403e62f88be4368e352eafb25ddc690a0aa10e9130637339b2d',
  'decode/ac3-simd.wasm': '1ff9224b6b2496fd15eb1a64cda11bbd5c750f529a830ba5a8556da82050e926',
  'decode/dca-simd.wasm': '37db01491b79f5c55aeef14538bb141bd7b49e4b1db740195d0fdd4426e18880',
  'decode/eac3-simd.wasm': '47ae49394a0f0056960536e0067f9358e940f1d83c581dbf8fe7f7f2b401f049',
  'decode/flac-simd.wasm': '001370dafe5d2c05886d995a3bc147e24a91b62091f3e691584638eaadca6d36',
  'decode/mp3-simd.wasm': 'b675e6ccadac5756b9e3633520699d3acdfc3063a00007e73e7d15db3a8ee5ac',
  'decode/opus-simd.wasm': '1bd4eb03441a83c177f465c67a880473b04e25c205bf57838b6ecde10b0da6d8',
  'decode/pcm-simd.wasm': '916d998e855824d7aa3b2e940cd7163ce20d08c91385f87bf816e29b3d4b8008',
  'decode/vorbis-simd.wasm': '7301becd3935333c859fe8265e569513b6b90fef48ee6eb60b58ff8c2da18b52',
  'resample/resample-simd.wasm': 'e4db620d0878198bcb6b8fb16035a259208b739ace8c54a6507057b4f4c5c9dd',
  'stretchpitch/stretchpitch-simd.wasm': '62d52214210a2e7f748cab6e18bbc1d854e76a5bc88df30449109dd6b83c9f91'
};

const sha256 = (b: Buffer) => createHash('sha256').update(b).digest('hex');

async function wasm(name: string): Promise<Buffer> {
  const cached = join(CACHE, name);
  if (existsSync(cached)) {
    const b = readFileSync(cached);
    if (sha256(b) === WASM[name]) return b;
  }
  const url = `https://cdn.jsdelivr.net/gh/zhaohappy/libmedia@${VERSION}/dist/${name}`;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`libmedia: ${url} answered ${res.status}`);
  const b = Buffer.from(await res.arrayBuffer());
  if (sha256(b) !== WASM[name]) throw new Error(`libmedia: ${name} doesn't match its pinned hash; update web/vite/libmedia.ts with the new version's hashes`);
  mkdirSync(join(cached, '..'), { recursive: true });
  writeFileSync(cached, b);
  return b;
}

let loading: Promise<Map<string, Buffer>> | null = null;

/* Everything served under assets/libmedia-<version>/, by path below it. */
function files(): Promise<Map<string, Buffer>> {
  loading ??= (async () => {
    const out = new Map<string, Buffer>();
    const esm = join(PKG, 'dist/esm');
    for (const f of readdirSync(esm)) if (f.endsWith('.js')) out.set(f, readFileSync(join(esm, f)));
    out.set('COPYING.LGPLv3', readFileSync(join(PKG, 'COPYING.LGPLv3')));
    for (const name of Object.keys(WASM)) out.set(name, await wasm(name));
    return out;
  })();
  loading.catch(() => { loading = null; });
  return loading;
}

const TYPES: Record<string, string> = { js: 'text/javascript', wasm: 'application/wasm' };

export function libmedia(): Plugin {
  return {
    name: 'seedy-libmedia',
    config: () => ({ define: { __LIBMEDIA_VERSION__: JSON.stringify(VERSION) } }),
    configureServer(server) {
      const prefix = `${server.config.base}${BASE}/`;
      server.middlewares.use((req, res, next) => {
        const url = req.url ?? '';
        if (/[?&]isolate=1(&|$)/.test(url) && req.headers['sec-fetch-dest'] === 'document') {
          res.setHeader('Cross-Origin-Opener-Policy', 'same-origin');
          res.setHeader('Cross-Origin-Embedder-Policy', 'credentialless');
        }
        if (!url.startsWith(prefix)) { next(); return; }
        const name = decodeURIComponent(url.slice(prefix.length).split('?')[0]);
        files().then((all) => {
          const body = all.get(name);
          if (!body) { next(); return; }
          res.setHeader('Content-Type', TYPES[name.split('.').pop()!] ?? 'text/plain; charset=utf-8');
          res.end(body);
        }, next);
      });
    },
    async generateBundle() {
      for (const [name, source] of await files()) this.emitFile({ type: 'asset', fileName: `${BASE}/${name}`, source });
    }
  };
}
