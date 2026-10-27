import type { JSX } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { imageUrl } from '../api/images';
import type { BaseItem } from '../api/types';

/* Album art. Without a picture on the server a cover is drawn the way the music boards draw theirs: a sun or moon over
   a hill, a wave or a field in one of nine palettes, picked from the item's id, with the title set on it. */

export const PALETTES = [
  ['#1c2a3a', '#2f4a66', '#f2c47e', '#e8eef4'], ['#3a1d2e', '#6b2f4f', '#ff9fbf', '#fbe4f3'], ['#13302b', '#1f5a4d', '#9fe0c8', '#def4f1'],
  ['#2b2118', '#5c3b22', '#f0a35e', '#f7ecd5'], ['#1d1d2e', '#3b3b6b', '#b9b3ff', '#efe6fb'], ['#262626', '#444444', '#f0f0f0', '#ffffff'],
  ['#36200f', '#a3471f', '#ffd27a', '#fff2d8'], ['#0f2233', '#1b6f8f', '#9fe9ff', '#e6fbff'], ['#2a1035', '#7a3fd1', '#ffb3e6', '#fbe4f3']
];
const PATHS = ['M0 140 Q50 110 100 140 T200 140 V200 H0Z', 'M0 200 L200 70 V200Z', 'M0 150 L50 110 L90 140 L140 90 L200 130 V200 H0Z', 'M0 0 H200 V40 Q100 70 0 40Z'];

export function seedOf(id?: string | null): number {
  let h = 2166136261;
  for (const ch of id ?? '') { h ^= ch.charCodeAt(0); h = Math.imul(h, 16777619); }
  return Math.abs(h);
}

export const paletteOf = (id?: string | null) => PALETTES[seedOf(id) % PALETTES.length];

/* The drawn cover; `plain` leaves out the sun and the hill (artists, whose initials go on top). */
export function DrawnCover({ seed, plain }: { seed: number; plain?: boolean }) {
  const i = seed % PALETTES.length;
  const [c0, c1, c2, c3] = PALETTES[i];
  const s = i % 4;
  const by = s === 1 ? 120 : s === 3 ? 0 : 200;
  const bh = s === 1 ? 80 : s === 3 ? 30 : 0;
  const cx = [70, 130, 100, 60][s];
  const cy = [80, 70, 95, 120][s];
  return (
    <svg viewBox="0 0 200 200" preserveAspectRatio="xMidYMid slice" class="ap-drawn" aria-hidden="true">
      <rect width="200" height="200" fill={c0} />
      <rect y={by} width="200" height={bh} fill={c1} />
      {!plain && (
        <>
          <circle cx={cx} cy={cy} r={[46, 30, 58, 22][s]} fill={c2} />
          <circle cx={cx} cy={cy} r={[70, 52, 82, 40][s]} fill="none" stroke={c3} stroke-width={[3, 8, 2, 6][s]} opacity={[0.5, 0.35, 0.6, 0.4][s]} />
          <path d={PATHS[(i + 1) % 4]} fill={c1} opacity={[0.9, 0.7, 0.85, 0.6][s]} />
        </>
      )}
    </svg>
  );
}

type Pic = Pick<BaseItem, 'Id' | 'ImageTags' | 'AlbumId' | 'AlbumPrimaryImageTag' | 'ParentPrimaryImageItemId' | 'ParentPrimaryImageTag'>;

/* The item's own picture, else its album's. */
export function coverUrl(item: Pic | null | undefined, width = 300): string | undefined {
  if (!item) return undefined;
  if (item.ImageTags?.Primary) return imageUrl(item.Id, 'Primary', { tag: item.ImageTags.Primary, maxWidth: width });
  if (item.AlbumId && item.AlbumPrimaryImageTag) return imageUrl(item.AlbumId, 'Primary', { tag: item.AlbumPrimaryImageTag, maxWidth: width });
  if (item.ParentPrimaryImageItemId && item.ParentPrimaryImageTag) return imageUrl(item.ParentPrimaryImageItemId, 'Primary', { tag: item.ParentPrimaryImageTag, maxWidth: width });
  return undefined;
}

export const initialsOf = (name?: string) =>
  (name ?? '').split(/\s+/).filter((w) => /[\p{L}\p{N}]/u.test(w)).map((w) => w.match(/[\p{L}\p{N}]/u)![0]).join('').slice(0, 2).toUpperCase();

/* A cover box (size and radius come from the class). `title` is set on a drawn cover; `initials` puts the name's
   initials on an artist's. */
export function Cover({ item, width = 300, title, initials, class: cls, style, seedId }: {
  item?: Pic | null; width?: number; title?: string; initials?: string; class?: string; style?: JSX.CSSProperties;
  /* Draw from this id instead of the item's (a song draws its album's cover). */
  seedId?: string;
}) {
  const src = coverUrl(item, width);
  const [state, setState] = useState<'loading' | 'ok' | 'failed'>('loading');
  useEffect(() => setState('loading'), [src]);
  const seed = seedOf(seedId ?? item?.AlbumId ?? item?.Id);
  const ink = PALETTES[seed % PALETTES.length][3];
  const drawn = !src || state === 'failed';
  return (
    <span class={'ap-cover' + (cls ? ' ' + cls : '')} style={{ background: PALETTES[seed % PALETTES.length][0], ...(style as object) }}>
      {drawn && <DrawnCover seed={seed} plain={!!initials} />}
      {drawn && title && <span aria-hidden="true" class="ap-cover-title" style={{ color: ink }}>{title}</span>}
      {drawn && initials && <span aria-hidden="true" class="ap-cover-initials" style={{ color: ink }}>{initials}</span>}
      {src && state !== 'failed' && (
        <img src={src} alt="" loading="lazy" decoding="async" class={state === 'ok' ? 'is-loaded' : ''}
          onLoad={() => setState('ok')} onError={() => setState('failed')} />
      )}
    </span>
  );
}

/* Two colours for a player's backdrop: the cover's own (averaged from the picture) or the drawn palette's. */
export function useCoverTint(item: Pic | null | undefined): [string, string] {
  const seed = seedOf(item?.AlbumId ?? item?.Id);
  const pal = PALETTES[seed % PALETTES.length];
  const src = coverUrl(item, 64);
  const [tint, setTint] = useState<[string, string] | null>(null);
  useEffect(() => {
    setTint(null);
    if (!src) return;
    let alive = true;
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => {
      if (!alive) return;
      try {
        const c = document.createElement('canvas');
        c.width = 16; c.height = 16;
        const g = c.getContext('2d');
        if (!g) return;
        g.drawImage(img, 0, 0, 16, 16);
        const d = g.getImageData(0, 0, 16, 16).data;
        // The cover's main colour: the most common colourful shade (greys, black and white count little).
        const buckets = new Map<number, { n: number; r: number; g: number; b: number }>();
        for (let i = 0; i < d.length; i += 4) {
          const [r, gr, b] = [d[i], d[i + 1], d[i + 2]];
          const max = Math.max(r, gr, b);
          const min = Math.min(r, gr, b);
          if (max < 24) continue;
          const w = 0.15 + (max - min) / 255;
          const k = ((r >> 5) << 6) | ((gr >> 5) << 3) | (b >> 5);
          const x = buckets.get(k) ?? { n: 0, r: 0, g: 0, b: 0 };
          x.n += w; x.r += r * w; x.g += gr * w; x.b += b * w;
          buckets.set(k, x);
        }
        let best: { n: number; r: number; g: number; b: number } | null = null;
        for (const x of buckets.values()) if (!best || x.n > best.n) best = x;
        if (!best) return;
        const rgb = [best.r / best.n, best.g / best.n, best.b / best.n];
        // Brought to the boards' brightness: the glow like #2f4a66, the edge like #1c2a3a.
        const at = (top: number) => {
          const k = top / Math.max(1, ...rgb);
          return `rgb(${rgb.map((v) => Math.round(v * k)).join(', ')})`;
        };
        setTint([at(104), at(58)]);
      } catch { /* a tainted canvas: keep the palette */ }
    };
    img.src = src;
    return () => { alive = false; };
  }, [src]);
  return tint ?? [pal[1], pal[0]];
}
