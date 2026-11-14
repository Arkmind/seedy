import type { ComponentChildren } from 'preact';
import './sea.css';

// The deep sea Medu lives in (Not found and Can't reach the server boards): light rays, drifting snow, bubbles,
// a few fish and a seabed. Every piece is decoration; the page's own content sits on top (z-index 2+).

/** left, width, animation delay (s) */
export type Ray = readonly [number, number, number];
/** left, top, size, opacity, duration (s), delay (s) */
export type Flake = readonly [number, number, number, number, number, number];
/** left, bottom, size, duration (s), delay (s) */
export type Bubble = readonly [number, number, number, number, number];
/** top, width, colour, duration (s), delay (s), swims right to left */
export type Fish = readonly [number, number, string, number, number, boolean?];

const FISH_BODY = 'M8 15 C16 4 38 4 48 15 C38 26 16 26 8 15 Z';
const FISH_TAIL = 'M47 15 L60 5 L56 15 L60 25 Z';

export function Sea({ rays, snow, bubbles, fish, rise = 560, rayHeight = 980, class: cls, children }: {
  rays: readonly Ray[];
  snow: readonly Flake[];
  bubbles: readonly Bubble[];
  fish: readonly Fish[];
  /** How far bubbles rise before they fade. */
  rise?: number;
  rayHeight?: number;
  class?: string;
  /** The seabed and anything else drawn behind the content. */
  children?: ComponentChildren;
}) {
  return (
    <div class={'sea' + (cls ? ' ' + cls : '')} aria-hidden="true" style={{ '--sea-rise': `-${rise}px` }}>
      {rays.map(([left, width, delay]) => <span key={left} class="sea-ray" style={{ left, width, height: rayHeight, animationDelay: `${delay}s` }} />)}
      {snow.map(([left, top, size, o, dur, delay], i) => (
        <span key={i} class="sea-snow" style={{ left, top, width: size, height: size, '--o': o, animationDuration: `${dur}s`, animationDelay: `${delay}s` }} />
      ))}
      {bubbles.map(([left, bottom, size, dur, delay], i) => (
        <span key={i} class="sea-bub" style={{ left, bottom, width: size, height: size, animationDuration: `${dur}s`, animationDelay: `${delay}s` }} />
      ))}
      {fish.map(([top, width, color, dur, delay, back], i) => (
        <span key={i} class={'sea-fish' + (back ? ' is-back' : '')} style={{ top, animationDuration: `${dur}s`, animationDelay: `${delay}s` }}>
          <svg viewBox="0 0 60 30" width={width} height={width / 2}>
            <path d={FISH_BODY} fill={color} />
            <path d={FISH_TAIL} fill={color} />
            <circle cx="16" cy="13" r="1.8" fill="#0b0d12" />
          </svg>
        </span>
      ))}
      {children}
    </div>
  );
}

/** A swaying strand of seaweed for the seabed SVG. */
export const Weed = ({ d, color = '#18231f', delay = 0 }: { d: string; color?: string; delay?: number }) => (
  <g class="sea-weed" style={{ animationDelay: `${delay}s` }}>
    <path d={d} fill="none" stroke={color} stroke-width="9" stroke-linecap="round" />
  </g>
);

/** The two layers of sand every seabed shares (back ridge, front dune). */
export const SAND_BACK = 'M0 120 Q180 82 360 108 T720 100 T1080 116 T1440 92 V220 H0Z';
export const SAND_FRONT = 'M0 168 Q240 128 480 156 T960 150 T1440 140 V220 H0Z';
