import { useState } from 'preact/hooks';
import { primaryUrl } from '../../api/images';
import type { BaseItem } from '../../api/types';
import { authorLine } from './api';

/* Book and audiobook covers (Books library, Book details boards). A book is a 2:3 cover with a shaded spine down its
   left edge and a drop shadow; an audiobook is a square. Titles without cover art get one drawn in the boards'
   style: the title and author set on a flat colour with a disc and a hill, picked from the title so it stays put. */

const BP = [
  ['#e8dcc8', '#c9583e', '#2c3a49', '#1e1e1e'], ['#1f3a3c', '#a8e6df', '#0f1c1d', '#def4f1'], ['#2f2840', '#e3c4ff', '#16121f', '#efe6fb'],
  ['#f2cf86', '#1d170f', '#5c4a2c', '#1d170f'], ['#3d2424', '#f0b9a0', '#1c1111', '#f6e3da'], ['#dfe7ef', '#2f4a7a', '#9fb2c6', '#141b22'],
  ['#26362b', '#d9e3b5', '#121a14', '#eef2e0'], ['#141414', '#ff9fff', '#2a2a2a', '#f0f0f0']
];
const BPATH = ['M0 300 L0 210 Q100 170 200 210 V300Z', 'M0 300 L200 160 V300Z', 'M0 230 L50 190 L100 220 L150 170 L200 200 V300 H0Z', 'M0 300 V240 H200 V300Z'];

const CP = [
  ['#1c2a3a', '#2f4a66', '#f2c47e', '#e8eef4'], ['#3a1d2e', '#6b2f4f', '#ff9fbf', '#fbe4f3'], ['#13302b', '#1f5a4d', '#9fe0c8', '#def4f1'],
  ['#2b2118', '#5c3b22', '#f0a35e', '#f7ecd5'], ['#1d1d2e', '#3b3b6b', '#b9b3ff', '#efe6fb'], ['#262626', '#444444', '#f0f0f0', '#ffffff'],
  ['#36200f', '#a3471f', '#ffd27a', '#fff2d8'], ['#0f2233', '#1b6f8f', '#9fe9ff', '#e6fbff'], ['#2a1035', '#7a3fd1', '#ffb3e6', '#fbe4f3']
];
const CPATH = ['M0 140 Q50 110 100 140 T200 140 V200 H0Z', 'M0 200 L200 70 V200Z', 'M0 150 L50 110 L90 140 L140 90 L200 130 V200 H0Z', 'M0 0 H200 V40 Q100 70 0 40Z'];

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return Math.abs(h);
}

/* Cover sizes: type scales with the cover (the boards' 19/10 px on a 152 px grid cover, 40/13 px on the 280 px hero). */
export type CoverSize = 'xs' | 'sm' | 'md' | 'lg';

function DrawnBook({ title, author, seed }: { title: string; author?: string; seed: string }) {
  const i = hash(seed) % BP.length;
  const c = BP[i];
  const s = i % 4;
  return (
    <>
      <svg viewBox="0 0 200 300" preserveAspectRatio="xMidYMid slice" class="bk-drawn" aria-hidden="true" style={{ background: c[0] }}>
        <circle cx={[140, 60, 100, 150][s]} cy={[150, 170, 130, 120][s]} r={[44, 60, 36, 50][s]} fill={c[1]} />
        <path d={BPATH[s]} fill={c[2]} />
      </svg>
      <span aria-hidden="true" class="bk-cover-title" style={{ color: c[3] }}>{title}</span>
      {author && <span aria-hidden="true" class="bk-cover-author" style={{ color: c[3] }}>{author}</span>}
    </>
  );
}

export function BookCover({ item, size = 'md', title, author, class: cls, width = 320 }: {
  item?: BaseItem; size?: CoverSize; title?: string; author?: string; class?: string; width?: number;
}) {
  const src = item ? primaryUrl(item, width) : undefined;
  const [failed, setFailed] = useState(false);
  const name = title ?? item?.Name ?? '';
  const by = author ?? (item ? authorLine(item) : '');
  return (
    <span class={'bk-cover is-' + size + (cls ? ' ' + cls : '')}>
      {src && !failed
        ? <img src={src} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} />
        : <DrawnBook title={name} author={by} seed={name + '|' + by} />}
      <span aria-hidden="true" class="bk-spine" />
    </span>
  );
}

function DrawnAudio({ title, seed, top }: { title: string; seed: string; top?: boolean }) {
  const i = hash(seed) % CP.length;
  const c = CP[i];
  const s = i % 4;
  const cx = [70, 130, 100, 60][s];
  const cy = [80, 70, 95, 120][s];
  return (
    <>
      <svg viewBox="0 0 200 200" preserveAspectRatio="xMidYMid slice" class="bk-drawn" aria-hidden="true">
        <rect width="200" height="200" fill={c[0]} />
        <rect y={s === 1 ? 120 : s === 3 ? 0 : 200} width="200" height={s === 1 ? 80 : s === 3 ? 30 : 0} fill={c[1]} />
        <circle cx={cx} cy={cy} r={[46, 30, 58, 22][s]} fill={c[2]} />
        <circle cx={cx} cy={cy} r={[70, 52, 82, 40][s]} fill="none" stroke={c[3]} stroke-width={[3, 8, 2, 6][s]} opacity={[0.5, 0.35, 0.6, 0.4][s]} />
        <path d={CPATH[(i + 1) % 4]} fill={c[1]} opacity={[0.9, 0.7, 0.85, 0.6][s]} />
      </svg>
      {title && <span aria-hidden="true" class={'bk-audio-title' + (top ? ' is-top' : '')} style={{ color: c[3] }}>{title}</span>}
    </>
  );
}

/* Audiobook art is square; drawn covers carry the title (without a leading "A"/"The" at the top, as on the board). */
export function AudioCover({ item, size = 'md', class: cls, width = 320, titleTop, noTitle }: {
  item?: BaseItem; size?: CoverSize; class?: string; width?: number; titleTop?: boolean; noTitle?: boolean;
}) {
  const src = item ? primaryUrl(item, width) : undefined;
  const [failed, setFailed] = useState(false);
  const name = item?.Name ?? '';
  return (
    <span class={'bk-audio is-' + size + (cls ? ' ' + cls : '')}>
      {src && !failed
        ? <img src={src} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} />
        : <DrawnAudio title={noTitle ? '' : name.replace(/^A\s+/i, '')} seed={name + '|' + authorLine(item ?? ({} as BaseItem))} top={titleTop} />}
    </span>
  );
}
