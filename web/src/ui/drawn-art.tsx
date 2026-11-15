import type { JSX } from 'preact';

/* The boards' drawn covers for titles without art: a 2:3 book (a disc and a hill on a flat colour) and a square
   (bands, a disc with a ring, a hill). Each caller picks the palette and shape from its own hash of the title, so
   a cover stays the same wherever it's drawn. */

export const BOOK_PALETTES = [
  ['#e8dcc8', '#c9583e', '#2c3a49', '#1e1e1e'], ['#1f3a3c', '#a8e6df', '#0f1c1d', '#def4f1'], ['#2f2840', '#e3c4ff', '#16121f', '#efe6fb'],
  ['#f2cf86', '#1d170f', '#5c4a2c', '#1d170f'], ['#3d2424', '#f0b9a0', '#1c1111', '#f6e3da'], ['#dfe7ef', '#2f4a7a', '#9fb2c6', '#141b22'],
  ['#26362b', '#d9e3b5', '#121a14', '#eef2e0'], ['#141414', '#ff9fff', '#2a2a2a', '#f0f0f0']
];
const BOOK_HILLS = ['M0 300 L0 210 Q100 170 200 210 V300Z', 'M0 300 L200 160 V300Z', 'M0 230 L50 190 L100 220 L150 170 L200 200 V300 H0Z', 'M0 300 V240 H200 V300Z'];

export const SQUARE_PALETTES = [
  ['#1c2a3a', '#2f4a66', '#f2c47e', '#e8eef4'], ['#3a1d2e', '#6b2f4f', '#ff9fbf', '#fbe4f3'], ['#13302b', '#1f5a4d', '#9fe0c8', '#def4f1'],
  ['#2b2118', '#5c3b22', '#f0a35e', '#f7ecd5'], ['#1d1d2e', '#3b3b6b', '#b9b3ff', '#efe6fb'], ['#262626', '#444444', '#f0f0f0', '#ffffff'],
  ['#36200f', '#a3471f', '#ffd27a', '#fff2d8'], ['#0f2233', '#1b6f8f', '#9fe9ff', '#e6fbff'], ['#2a1035', '#7a3fd1', '#ffb3e6', '#fbe4f3']
];
const SQUARE_HILLS = ['M0 140 Q50 110 100 140 T200 140 V200 H0Z', 'M0 200 L200 70 V200Z', 'M0 150 L50 110 L90 140 L140 90 L200 130 V200 H0Z', 'M0 0 H200 V40 Q100 70 0 40Z'];

type SvgProps = { class?: string; hidden?: boolean; style?: JSX.CSSProperties };

/* [c]: a BOOK_PALETTES entry; [s]: the shape, 0–3. */
export function DrawnBookSvg({ c, s, class: cls, hidden, style }: { c: string[]; s: number } & SvgProps) {
  return (
    <svg viewBox="0 0 200 300" preserveAspectRatio="xMidYMid slice" class={cls} aria-hidden={hidden ? 'true' : undefined} style={style}>
      <circle cx={[140, 60, 100, 150][s]} cy={[150, 170, 130, 120][s]} r={[44, 60, 36, 50][s]} fill={c[1]} />
      <path d={BOOK_HILLS[s]} fill={c[2]} />
    </svg>
  );
}

/* [c]: a SQUARE_PALETTES entry; [s]: the shape, 0–3; [hill]: which hill (0–3). [bare] leaves only the bands (behind a
   round artist's initials). */
export function DrawnSquareSvg({ c, s, hill, bare, class: cls, hidden }: { c: string[]; s: number; hill: number; bare?: boolean } & SvgProps) {
  const cx = [70, 130, 100, 60][s];
  const cy = [80, 70, 95, 120][s];
  return (
    <svg viewBox="0 0 200 200" preserveAspectRatio="xMidYMid slice" class={cls} aria-hidden={hidden ? 'true' : undefined}>
      <rect width="200" height="200" fill={c[0]} />
      <rect y={s === 1 ? 120 : s === 3 ? 0 : 200} width="200" height={s === 1 ? 80 : s === 3 ? 30 : 0} fill={c[1]} />
      {!bare && <circle cx={cx} cy={cy} r={[46, 30, 58, 22][s]} fill={c[2]} />}
      {!bare && <circle cx={cx} cy={cy} r={[70, 52, 82, 40][s]} fill="none" stroke={c[3]} stroke-width={[3, 8, 2, 6][s]} opacity={[0.5, 0.35, 0.6, 0.4][s]} />}
      {!bare && <path d={SQUARE_HILLS[hill]} fill={c[1]} opacity={[0.9, 0.7, 0.85, 0.6][s]} />}
    </svg>
  );
}
