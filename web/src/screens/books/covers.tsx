import { useState } from 'preact/hooks';
import { primaryUrl } from '../../api/images';
import type { BaseItem } from '../../api/types';
import { authorLine } from './api';
import { BOOK_PALETTES, DrawnBookSvg, DrawnSquareSvg, SQUARE_PALETTES } from '../../ui/drawn-art';

/* Book and audiobook covers (Books library, Book details boards). A book is a 2:3 cover with a shaded spine down its
   left edge and a drop shadow; an audiobook is a square. Titles without cover art get one drawn in the boards'
   style: the title and author set on a flat colour with a disc and a hill, picked from the title so it stays put. */

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) { h ^= s.charCodeAt(i); h = Math.imul(h, 16777619); }
  return Math.abs(h);
}

/* Cover sizes: type scales with the cover (the boards' 19/10 px on a 152 px grid cover, 40/13 px on the 280 px hero). */
export type CoverSize = 'xs' | 'sm' | 'md' | 'lg';

function DrawnBook({ title, author, seed }: { title: string; author?: string; seed: string }) {
  const i = hash(seed) % BOOK_PALETTES.length;
  const c = BOOK_PALETTES[i];
  return (
    <>
      <DrawnBookSvg c={c} s={i % 4} class="bk-drawn" hidden style={{ background: c[0] }} />
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
  const i = hash(seed) % SQUARE_PALETTES.length;
  const c = SQUARE_PALETTES[i];
  return (
    <>
      <DrawnSquareSvg c={c} s={i % 4} hill={(i + 1) % 4} class="bk-drawn" hidden />
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
