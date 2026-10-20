/* Glyphs the server boards draw that the shared icon set doesn't have, path for path from the boards. */

type GlyphName = 'tuner' | 'playlist' | 'tv' | 'phone' | 'computer' | 'refresh';

const GLYPHS: Record<GlyphName, { stroke: number; body: preact.JSX.Element }> = {
  tuner: { stroke: 1.8, body: <><rect x="3" y="7" width="18" height="12" rx="2" /><path d="M8 3l4 4 4-4" /><path d="M7 15h.01" /><path d="M10 15h.01" /></> },
  playlist: { stroke: 1.8, body: <><path d="M8 6h13" /><path d="M8 12h13" /><path d="M8 18h13" /><path d="M3 6h.01" /><path d="M3 12h.01" /><path d="M3 18h.01" /></> },
  tv: { stroke: 1.8, body: <><rect x="3" y="5" width="18" height="12" rx="2" /><path d="M8 21h8" /></> },
  phone: { stroke: 1.8, body: <><rect x="7" y="3" width="10" height="18" rx="2" /><path d="M11 18h2" /></> },
  computer: { stroke: 1.8, body: <><rect x="4" y="5" width="16" height="11" rx="2" /><path d="M2 19h20" /></> },
  refresh: { stroke: 2.2, body: <><path d="M20 12a8 8 0 1 1-2.3-5.6" /><path d="M20 4v5h-5" /></> }
};

export function Glyph({ name, size = 24, class: cls }: { name: GlyphName; size?: number; class?: string }) {
  const g = GLYPHS[name];
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" class={'ic' + (cls ? ' ' + cls : '')}
      fill="none" stroke="currentColor" stroke-width={g.stroke}>
      {g.body}
    </svg>
  );
}
