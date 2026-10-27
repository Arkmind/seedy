import { Fragment, type VNode } from 'preact';
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { activeEvents, eventFrame, outlineShadow, parseEvent, rgba, styleOf, type AssTrack, type RunStyle } from './ass';
import { activePgs, decodePicture, type PgsCue, type PgsPicture } from './pgs';
import { SUB_PX, cueCss, type PictureFit, type SubtitleStyle } from './settings';
import type { Cue } from './subtitles';

/* Everything Seedy draws over the picture: text cues in the viewer's style (Settings · Subtitles), styled ASS/SSA
   with the script's own look, and PGS pictures. */

export type LoadedSubtitles =
  | { kind: 'vtt'; cues: Cue[] }
  | { kind: 'ass'; track: AssTrack }
  | { kind: 'pgs'; cues: PgsCue[] };

interface Box { w: number; h: number }

/* Size of the player area, kept current. */
function useBox(ref: { current: HTMLElement | null }): Box {
  const [box, setBox] = useState<Box>({ w: window.innerWidth, h: window.innerHeight });
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const read = () => setBox((b) => (b.w === el.clientWidth && b.h === el.clientHeight ? b : { w: el.clientWidth, h: el.clientHeight }));
    read();
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', read);
      return () => window.removeEventListener('resize', read);
    }
    const ro = new ResizeObserver(read);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return box;
}

/* Where the picture sits inside the player (object-fit: contain). */
function contentRect(box: Box, vw: number, vh: number) {
  if (!vw || !vh || !box.w || !box.h) return { x: 0, y: 0, w: box.w, h: box.h };
  const s = Math.min(box.w / vw, box.h / vh);
  const w = vw * s;
  const h = vh * s;
  return { x: (box.w - w) / 2, y: (box.h - h) / 2, w, h };
}

export function SubtitleLayer({ subs, time, style, lifted, hidden, fit, videoWidth, videoHeight }: {
  subs: LoadedSubtitles | null;
  /* Media time with the sync offset already applied. */
  time: number;
  style: SubtitleStyle;
  /* Controls are showing (lifted or top-of-screen text moves out of their way). */
  lifted: boolean;
  hidden?: boolean;
  fit: PictureFit;
  videoWidth: number;
  videoHeight: number;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const box = useBox(ref);
  return (
    <div ref={ref} class="pl-sublayer" aria-hidden={hidden ? 'true' : undefined} style={hidden ? { visibility: 'hidden' } : undefined}>
      {subs?.kind === 'vtt' && <TextCues cues={subs.cues} time={time} style={style} lifted={lifted} />}
      {subs?.kind === 'ass' && <AssCues track={subs.track} time={time} rect={contentRect(box, videoWidth, videoHeight)} />}
      {subs?.kind === 'pgs' && <PgsCanvas cue={activePgs(subs.cues, time)} box={box} fit={fit} />}
    </div>
  );
}

/* ── Text cues in the viewer's style ── */

function TextCues({ cues, time, style, lifted }: { cues: Cue[]; time: number; style: SubtitleStyle; lifted: boolean }) {
  const shown = activeText(cues, time);
  if (!shown.length) return null;
  const css = cueCss(style, SUB_PX[style.size], 'player');
  const vars = {
    '--sub-px': String(SUB_PX[style.size]),
    '--sub-weight': css.fontWeight,
    '--sub-font': css.fontFamily,
    '--sub-variant': css.fontVariant,
    '--sub-color': css.color,
    '--sub-bg': css.background,
    '--sub-shadow': css.textShadow
  } as Record<string, string>;
  const cls = [
    'pl-subs',
    style.position === 'top' ? 'is-top' : '',
    (style.position === 'lifted' || style.position === 'top') && lifted ? 'is-lifted' : '',
    style.background !== 'none' ? 'has-bg' : ''
  ].filter(Boolean).join(' ');
  return (
    <div class={cls} style={vars}>
      {shown.map((c, i) => (
        <span key={i + ':' + c.start} class="pl-cue">
          {c.lines.map((line, j) => (
            <Fragment key={j}>
              {j > 0 && <br />}
              {line.map((sp, k) => (
                <span key={k} class={[sp.i && 'is-i', sp.b && 'is-b', sp.u && 'is-u'].filter(Boolean).join(' ') || undefined}>{sp.text}</span>
              ))}
            </Fragment>
          ))}
        </span>
      ))}
    </div>
  );
}

function activeText(cues: Cue[], t: number): Cue[] {
  const out: Cue[] = [];
  let lo = 0;
  let hi = cues.length - 1;
  let idx = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (cues[mid].start <= t) { idx = mid; lo = mid + 1; } else hi = mid - 1;
  }
  for (let i = idx; i >= 0 && i > idx - 12; i--) {
    if (cues[i].start <= t && cues[i].end > t) out.unshift(cues[i]);
  }
  return out;
}

/* ── ASS/SSA ── */

function runCss(r: RunStyle, sy: number, sx: number, borderStyle: number): Record<string, string> {
  const size = r.size * sy / 1.12;
  const css: Record<string, string> = {
    fontFamily: `"${r.font.replace(/"/g, '')}", var(--font)`,
    fontSize: size.toFixed(2) + 'px',
    fontWeight: r.bold ? String(r.bold) : '400',
    fontStyle: r.italic ? 'italic' : 'normal',
    textDecoration: [r.underline && 'underline', r.strike && 'line-through'].filter(Boolean).join(' ') || 'none',
    color: rgba(r.primary),
    letterSpacing: r.spacing ? (r.spacing * sx).toFixed(2) + 'px' : 'normal'
  };
  if (borderStyle === 3) {
    // Opaque box behind the text, in the outline colour.
    css.background = rgba(r.outline);
    css.padding = `0 ${(Math.max(1, r.outlineWidth) * sy).toFixed(2)}px`;
    css.textShadow = r.shadow ? `${(r.shadow * sy).toFixed(2)}px ${(r.shadow * sy).toFixed(2)}px 0 ${rgba(r.back)}` : 'none';
    css.boxDecorationBreak = 'clone';
    css.webkitBoxDecorationBreak = 'clone';
  } else {
    css.textShadow = outlineShadow(r.outlineWidth * sy, rgba(r.outline), r.shadow * sy, rgba(r.back));
  }
  if (r.scaleX !== 100 || r.scaleY !== 100) {
    css.display = 'inline-block';
    css.transform = `scale(${r.scaleX / 100}, ${r.scaleY / 100})`;
    css.transformOrigin = 'center bottom';
  }
  return css;
}

const H_ALIGN = (an: number) => (an % 3 === 1 ? 'left' : an % 3 === 2 ? 'center' : 'right');

function AssCues({ track, time, rect }: { track: AssTrack; time: number; rect: { x: number; y: number; w: number; h: number } }) {
  const evs = activeEvents(track, time);
  if (!evs.length || !rect.w) return null;
  const sx = rect.w / track.playResX;
  const sy = rect.h / track.playResY;
  const groups = new Map<number, VNode[]>();
  const groupMargins = new Map<number, { l: number; r: number; v: number }>();
  const placed: VNode[] = [];

  evs.forEach((ev) => {
    const p = parseEvent(track, ev);
    if (!p.runs.length) return;
    const st = styleOf(track, ev);
    const f = eventFrame(p, ev, time);
    const mL = ev.marginL || st.marginL;
    const mR = ev.marginR || st.marginR;
    const mV = ev.marginV || st.marginV;
    const body = p.runs.map((r, i) => <span key={i} style={runCss(r.style, sy, sx, p.borderStyle)}>{r.text}</span>);
    const lineStyle: Record<string, string> = {
      textAlign: H_ALIGN(p.align),
      whiteSpace: p.wrap === 2 ? 'pre' : 'pre-wrap',
      opacity: String(f.opacity),
      zIndex: String(ev.layer)
    };
    if (f.pos) {
      const tx = p.align % 3 === 1 ? '0' : p.align % 3 === 2 ? '-50%' : '-100%';
      const ty = p.align >= 7 ? '0' : p.align >= 4 ? '-50%' : '-100%';
      placed.push(
        <div
          key={ev.order}
          class="pl-ass-line is-placed"
          style={{
            ...lineStyle,
            left: (f.pos.x * sx).toFixed(2) + 'px',
            top: (f.pos.y * sy).toFixed(2) + 'px',
            maxWidth: Math.max(40, (track.playResX - mL - mR) * sx).toFixed(0) + 'px',
            transform: `translate(${tx}, ${ty})${p.rotate ? ` rotate(${-p.rotate}deg)` : ''}`,
            transformOrigin: `${tx === '0' ? '0' : tx === '-50%' ? '50%' : '100%'} ${ty === '0' ? '0' : ty === '-50%' ? '50%' : '100%'}`
          }}
        >
          {body}
        </div>
      );
      return;
    }
    if (!groups.has(p.align)) { groups.set(p.align, []); groupMargins.set(p.align, { l: mL, r: mR, v: mV }); }
    groups.get(p.align)!.push(
      <div key={ev.order} class="pl-ass-line" style={{ ...lineStyle, transform: p.rotate ? `rotate(${-p.rotate}deg)` : undefined }}>{body}</div>
    );
  });

  return (
    <div class="pl-ass" style={{ left: rect.x + 'px', top: rect.y + 'px', width: rect.w + 'px', height: rect.h + 'px' }}>
      {Array.from(groups.entries()).map(([an, lines]) => {
        const m = groupMargins.get(an)!;
        const v = an >= 7 ? { top: (m.v * sy) + 'px' } : an >= 4 ? { top: '50%', transform: 'translateY(-50%)' } : { bottom: (m.v * sy) + 'px' };
        return (
          <div
            key={'g' + an}
            class="pl-ass-group"
            style={{
              ...v,
              left: (m.l * sx) + 'px',
              right: (m.r * sx) + 'px',
              // The first line stays nearest its edge; later ones stack away from it.
              flexDirection: an <= 3 ? 'column-reverse' : 'column',
              alignItems: an % 3 === 1 ? 'flex-start' : an % 3 === 2 ? 'center' : 'flex-end'
            }}
          >
            {lines}
          </div>
        );
      })}
      {placed}
    </div>
  );
}

/* ── PGS pictures ── */

/* The last few decoded pictures (seeking back shows them again). Keeping every one a film shows grew by hundreds
   of MB. */
const decoded = new Map<PgsPicture, ImageData | null>();
const KEEP_DECODED = 8;

function decodedPicture(p: PgsPicture): ImageData | null {
  let img = decoded.get(p);
  if (img === undefined) img = decodePicture(p);
  decoded.delete(p);
  decoded.set(p, img);
  if (decoded.size > KEEP_DECODED) decoded.delete(decoded.keys().next().value!);
  return img;
}

function PgsCanvas({ cue, box, fit }: { cue: PgsCue | null; box: Box; fit: PictureFit }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    // Damaged subtitle data must never take the player down: a picture that can't be drawn is left out.
    try {
      const ctx = canvas.getContext('2d');
      if (!ctx) return;
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      if (!cue) return;
      for (const p of cue.pictures) {
        const img = decodedPicture(p);
        if (img) ctx.putImageData(img, p.x, p.y);
      }
    } catch { /* leave the frame empty */ }
  }, [cue]);
  if (!cue) return null;
  // Frame sizes outside what a picture subtitle can have fall back to 1080p.
  const valid = (n: number) => n > 0 && n <= 8192;
  const cw = valid(cue.width) && valid(cue.height) ? cue.width : 1920;
  const ch = valid(cue.width) && valid(cue.height) ? cue.height : 1080;
  let css: Record<string, string>;
  if (fit === 'stretch') css = { left: '0', top: '0', width: box.w + 'px', height: box.h + 'px' };
  else {
    // The pictures are placed on the full frame, so fit that frame (not the possibly cropped video) to the screen.
    const s = Math.min(box.w / cw, box.h / ch) * (fit === 'smaller' ? 0.8 : 1);
    const full = Math.min(box.w / cw, box.h / ch);
    const w = cw * s;
    const h = ch * s;
    const bottom = (box.h - ch * full) / 2;
    css = { left: ((box.w - w) / 2) + 'px', top: (box.h - bottom - h) + 'px', width: w + 'px', height: h + 'px' };
  }
  return <canvas ref={ref} class="pl-pgs" width={cw} height={ch} style={css} />;
}
