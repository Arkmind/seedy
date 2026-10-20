/* A small ASS/SSA renderer model: styles, positions, colours, outlines, fades and moves from the script, laid out
   by the player as HTML over the picture. Drawings (\p), karaoke timing, clips and transforms (\t) are left out;
   that covers dialogue and most signs. */

export interface AssColor { r: number; g: number; b: number; a: number }

export interface AssStyle {
  name: string;
  font: string;
  size: number;
  primary: AssColor;
  outline: AssColor;
  back: AssColor;
  bold: number;
  italic: boolean;
  underline: boolean;
  strike: boolean;
  scaleX: number;
  scaleY: number;
  spacing: number;
  angle: number;
  borderStyle: number;
  outlineWidth: number;
  shadow: number;
  /* Numpad alignment 1–9. */
  align: number;
  marginL: number;
  marginR: number;
  marginV: number;
}

export interface AssEvent {
  start: number;
  end: number;
  layer: number;
  style: string;
  marginL: number;
  marginR: number;
  marginV: number;
  text: string;
  order: number;
  parsed?: ParsedEvent;
}

export interface AssTrack {
  playResX: number;
  playResY: number;
  wrapStyle: number;
  scaledBorder: boolean;
  styles: Map<string, AssStyle>;
  events: AssEvent[];
}

const DEFAULT_STYLE: AssStyle = {
  name: 'Default', font: 'Arial', size: 20, primary: { r: 255, g: 255, b: 255, a: 1 }, outline: { r: 0, g: 0, b: 0, a: 1 },
  back: { r: 0, g: 0, b: 0, a: 0.5 }, bold: 0, italic: false, underline: false, strike: false, scaleX: 100, scaleY: 100,
  spacing: 0, angle: 0, borderStyle: 1, outlineWidth: 2, shadow: 2, align: 2, marginL: 10, marginR: 10, marginV: 10
};

/* &HAABBGGRR (alpha 00 = opaque), &HBBGGRR, or a decimal number (SSA). */
export function parseColor(v: string | undefined, fallback: AssColor): AssColor {
  if (!v) return fallback;
  const t = v.trim();
  let n: number;
  let hasAlpha = false;
  const hex = /^&?H([0-9a-f]+)&?$/i.exec(t);
  if (hex) {
    n = parseInt(hex[1], 16);
    hasAlpha = hex[1].length > 6;
  } else if (/^-?\d+$/.test(t)) {
    n = Number(t) >>> 0;
    hasAlpha = n > 0xffffff;
  } else return fallback;
  const r = n & 0xff;
  const g = (n >>> 8) & 0xff;
  const b = (n >>> 16) & 0xff;
  const alpha = hasAlpha ? (n >>> 24) & 0xff : 0;
  return { r, g, b, a: 1 - alpha / 255 };
}

export const rgba = (c: AssColor, extraAlpha = 1) => `rgba(${c.r},${c.g},${c.b},${Math.max(0, Math.min(1, c.a * extraAlpha)).toFixed(3)})`;

function parseTime(s: string): number {
  const m = /(\d+):(\d{1,2}):(\d{1,2})[.:](\d{1,3})/.exec(s);
  if (!m) return 0;
  return Number(m[1]) * 3600 + Number(m[2]) * 60 + Number(m[3]) + Number(m[4].padEnd(3, '0').slice(0, 3)) / 1000;
}

/* SSA v4 alignment (1–3 bottom, 5–7 top, 9–11 middle) to numpad. */
const legacyAlign = (a: number) => (a >= 9 ? a - 5 : a >= 5 ? a + 2 : a);

export function parseAss(src: string): AssTrack {
  const text = src.replace(/^﻿/, '').replace(/\r\n?/g, '\n');
  const track: AssTrack = { playResX: 0, playResY: 0, wrapStyle: 0, scaledBorder: true, styles: new Map(), events: [] };
  let section = '';
  let styleFormat: string[] = [];
  let eventFormat: string[] = [];
  let isV4Plus = true;
  let order = 0;
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (!line || line.startsWith(';')) continue;
    const sec = /^\[(.+)\]$/.exec(line);
    if (sec) {
      section = sec[1].toLowerCase();
      if (section === 'v4 styles') isV4Plus = false;
      continue;
    }
    const colon = line.indexOf(':');
    if (colon < 0) continue;
    const key = line.slice(0, colon).trim().toLowerCase();
    const value = line.slice(colon + 1).trim();
    if (section === 'script info') {
      if (key === 'playresx') track.playResX = Number(value) || 0;
      else if (key === 'playresy') track.playResY = Number(value) || 0;
      else if (key === 'wrapstyle') track.wrapStyle = Number(value) || 0;
      else if (key === 'scaledborderandshadow') track.scaledBorder = !/^no$/i.test(value);
    } else if (section.endsWith('styles')) {
      if (key === 'format') styleFormat = value.split(',').map((f) => f.trim().toLowerCase());
      else if (key === 'style') {
        const parts = value.split(',');
        const get = (f: string) => {
          const i = styleFormat.indexOf(f);
          return i >= 0 ? parts[i]?.trim() : undefined;
        };
        const num = (f: string, d: number) => {
          const v = Number(get(f));
          return Number.isFinite(v) ? v : d;
        };
        const flag = (f: string) => {
          const v = Number(get(f));
          return v !== 0 && Number.isFinite(v);
        };
        const align = num('alignment', 2);
        const st: AssStyle = {
          name: get('name') ?? 'Default',
          font: get('fontname') || DEFAULT_STYLE.font,
          size: num('fontsize', DEFAULT_STYLE.size),
          primary: parseColor(get('primarycolour') ?? get('primarycolor'), DEFAULT_STYLE.primary),
          outline: parseColor(get('outlinecolour') ?? get('outlinecolor') ?? get('tertiarycolour'), DEFAULT_STYLE.outline),
          back: parseColor(get('backcolour') ?? get('backcolor'), DEFAULT_STYLE.back),
          bold: Number(get('bold')) === -1 || Number(get('bold')) === 1 ? 700 : Number(get('bold')) > 1 ? Number(get('bold')) : 0,
          italic: flag('italic'),
          underline: flag('underline'),
          strike: flag('strikeout'),
          scaleX: num('scalex', 100),
          scaleY: num('scaley', 100),
          spacing: num('spacing', 0),
          angle: num('angle', 0),
          borderStyle: num('borderstyle', 1),
          outlineWidth: num('outline', 2),
          shadow: num('shadow', 2),
          align: isV4Plus ? align : legacyAlign(align),
          marginL: num('marginl', 10),
          marginR: num('marginr', 10),
          marginV: num('marginv', 10)
        };
        track.styles.set(st.name, st);
      }
    } else if (section === 'events') {
      if (key === 'format') eventFormat = value.split(',').map((f) => f.trim().toLowerCase());
      else if (key === 'dialogue') {
        // Text is the last field and may contain commas.
        const n = eventFormat.length || 10;
        const parts: string[] = [];
        let rest = value;
        for (let i = 0; i < n - 1; i++) {
          const c = rest.indexOf(',');
          if (c < 0) break;
          parts.push(rest.slice(0, c));
          rest = rest.slice(c + 1);
        }
        parts.push(rest);
        const fmt = eventFormat.length ? eventFormat : ['layer', 'start', 'end', 'style', 'name', 'marginl', 'marginr', 'marginv', 'effect', 'text'];
        const get = (f: string) => {
          const i = fmt.indexOf(f);
          return i >= 0 ? parts[i] ?? '' : '';
        };
        const ev: AssEvent = {
          layer: Number(get('layer')) || 0,
          start: parseTime(get('start')),
          end: parseTime(get('end')),
          style: get('style').trim().replace(/^\*/, '') || 'Default',
          marginL: Number(get('marginl')) || 0,
          marginR: Number(get('marginr')) || 0,
          marginV: Number(get('marginv')) || 0,
          text: get('text'),
          order: order++
        };
        if (ev.end > ev.start) track.events.push(ev);
      }
    }
  }
  // libass defaults: 384×288, or derive the missing side from 4:3 (and 1280×1024 for a 1024 height).
  if (!track.playResX && !track.playResY) { track.playResX = 384; track.playResY = 288; }
  else if (!track.playResY) track.playResY = track.playResX === 1280 ? 1024 : Math.round(track.playResX * 3 / 4);
  else if (!track.playResX) track.playResX = track.playResY === 1024 ? 1280 : Math.round(track.playResY * 4 / 3);
  track.events.sort((a, b) => a.start - b.start || a.layer - b.layer || a.order - b.order);
  return track;
}

/* ── Override tags ── */

export interface RunStyle {
  font: string;
  size: number;
  bold: number;
  italic: boolean;
  underline: boolean;
  strike: boolean;
  primary: AssColor;
  outline: AssColor;
  back: AssColor;
  outlineWidth: number;
  shadow: number;
  spacing: number;
  scaleX: number;
  scaleY: number;
}

export interface Run { text: string; style: RunStyle }

export interface ParsedEvent {
  runs: Run[];
  align: number;
  pos?: { x: number; y: number };
  move?: { x1: number; y1: number; x2: number; y2: number; t1: number; t2: number };
  fade?: { in: number; out: number };
  rotate: number;
  wrap: number;
  borderStyle: number;
}

const baseRun = (s: AssStyle): RunStyle => ({
  font: s.font, size: s.size, bold: s.bold, italic: s.italic, underline: s.underline, strike: s.strike,
  primary: s.primary, outline: s.outline, back: s.back, outlineWidth: s.outlineWidth, shadow: s.shadow,
  spacing: s.spacing, scaleX: s.scaleX, scaleY: s.scaleY
});

const args = (v: string) => v.replace(/^\(|\)$/g, '').split(',').map((x) => Number(x.trim()));

export function parseEvent(track: AssTrack, ev: AssEvent): ParsedEvent {
  if (ev.parsed) return ev.parsed;
  const style = track.styles.get(ev.style) ?? track.styles.get('Default') ?? DEFAULT_STYLE;
  const out: ParsedEvent = { runs: [], align: style.align, rotate: style.angle, wrap: track.wrapStyle, borderStyle: style.borderStyle };
  let cur = baseRun(style);
  let drawing = false;
  const re = /\{([^}]*)\}/g;
  let last = 0;
  let m: RegExpExecArray | null;
  const push = (raw: string) => {
    if (!raw || drawing) return;
    let t = raw.replace(/\\h/g, ' ').replace(/\\N/g, '\n');
    t = t.replace(/\\n/g, out.wrap === 2 ? '\n' : ' ');
    if (t) out.runs.push({ text: t, style: { ...cur } });
  };
  const applyTags = (block: string) => {
    // Tags start with a backslash; parenthesised arguments may hold commas.
    const tagRe = /\\(\d?[a-z]+)(\([^)]*\)|[^\\]*)/gi;
    let t: RegExpExecArray | null;
    while ((t = tagRe.exec(block))) {
      const name = t[1].toLowerCase();
      const val = t[2].trim();
      const n = Number(val);
      switch (name) {
        case 'b': cur.bold = n === 1 ? 700 : n > 1 ? n : 0; break;
        case 'i': cur.italic = n === 1; break;
        case 'u': cur.underline = n === 1; break;
        case 's': cur.strike = n === 1; break;
        case 'fn': cur.font = val || style.font; break;
        case 'fs': if (n > 0) cur.size = n; break;
        case 'fsp': cur.spacing = n || 0; break;
        case 'fscx': cur.scaleX = Number.isFinite(n) ? n : 100; break;
        case 'fscy': cur.scaleY = Number.isFinite(n) ? n : 100; break;
        case 'bord': if (n >= 0) cur.outlineWidth = n; break;
        case 'shad': if (n >= 0) cur.shadow = n; break;
        case 'c': case '1c': cur.primary = { ...parseColor(val, cur.primary), a: cur.primary.a }; break;
        case '3c': cur.outline = { ...parseColor(val, cur.outline), a: cur.outline.a }; break;
        case '4c': cur.back = { ...parseColor(val, cur.back), a: cur.back.a }; break;
        case 'alpha': {
          const a = alphaOf(val);
          if (a !== null) { cur.primary = { ...cur.primary, a }; cur.outline = { ...cur.outline, a }; cur.back = { ...cur.back, a: Math.min(cur.back.a, a) }; }
          break;
        }
        case '1a': { const a = alphaOf(val); if (a !== null) cur.primary = { ...cur.primary, a }; break; }
        case '3a': { const a = alphaOf(val); if (a !== null) cur.outline = { ...cur.outline, a }; break; }
        case '4a': { const a = alphaOf(val); if (a !== null) cur.back = { ...cur.back, a }; break; }
        case 'an': if (n >= 1 && n <= 9) out.align = n; break;
        case 'a': if (n > 0) out.align = legacyAlign(n); break;
        case 'pos': { const [x, y] = args(val); if (Number.isFinite(x) && Number.isFinite(y)) out.pos = { x, y }; break; }
        case 'move': {
          const [x1, y1, x2, y2, t1 = 0, t2 = 0] = args(val);
          if ([x1, y1, x2, y2].every(Number.isFinite)) { out.move = { x1, y1, x2, y2, t1, t2 }; out.pos ??= { x: x1, y: y1 }; }
          break;
        }
        case 'fad': { const [a, b] = args(val); if (Number.isFinite(a)) out.fade = { in: a, out: b || 0 }; break; }
        case 'frz': case 'fr': if (Number.isFinite(n)) out.rotate = n; break;
        case 'q': if (Number.isFinite(n)) out.wrap = n; break;
        case 'r': {
          const st = val ? track.styles.get(val) : style;
          cur = baseRun(st ?? style);
          break;
        }
        case 'p': drawing = n > 0; break;
        default: break; // \k, \t, \clip, \blur… are ignored
      }
    }
  };
  while ((m = re.exec(ev.text))) {
    push(ev.text.slice(last, m.index));
    last = m.index + m[0].length;
    applyTags(m[1]);
  }
  push(ev.text.slice(last));
  ev.parsed = out;
  return out;
}

function alphaOf(v: string): number | null {
  const h = /&?H([0-9a-f]{1,2})&?/i.exec(v);
  if (!h) return null;
  return 1 - parseInt(h[1], 16) / 255;
}

/* Events on screen at `t` (seconds), in paint order. */
export function activeEvents(track: AssTrack, t: number): AssEvent[] {
  const out: AssEvent[] = [];
  // Events are sorted by start; long-running signs make a plain scan the safe choice, and lists are small.
  for (const ev of track.events) {
    if (ev.start > t) break;
    if (ev.end > t) out.push(ev);
  }
  return out.sort((a, b) => a.layer - b.layer || a.order - b.order);
}

/* Position and opacity of an event at time `t` (script coordinates). */
export function eventFrame(p: ParsedEvent, ev: AssEvent, t: number): { pos?: { x: number; y: number }; opacity: number } {
  let pos = p.pos;
  if (p.move) {
    const ms = (t - ev.start) * 1000;
    const dur = (ev.end - ev.start) * 1000;
    const t1 = p.move.t1 || 0;
    const t2 = p.move.t2 || dur;
    const k = t2 > t1 ? Math.max(0, Math.min(1, (ms - t1) / (t2 - t1))) : 1;
    pos = { x: p.move.x1 + (p.move.x2 - p.move.x1) * k, y: p.move.y1 + (p.move.y2 - p.move.y1) * k };
  }
  let opacity = 1;
  if (p.fade) {
    const sinceStart = (t - ev.start) * 1000;
    const untilEnd = (ev.end - t) * 1000;
    if (p.fade.in > 0 && sinceStart < p.fade.in) opacity = Math.max(0, sinceStart / p.fade.in);
    if (p.fade.out > 0 && untilEnd < p.fade.out) opacity = Math.min(opacity, Math.max(0, untilEnd / p.fade.out));
  }
  return { pos, opacity };
}

/* CSS text-shadow for an outline of `w` px plus a drop shadow of `d` px. */
export function outlineShadow(w: number, outline: string, d: number, shadow: string): string {
  const parts: string[] = [];
  if (w > 0) {
    const steps = w > 2.5 ? 16 : 8;
    for (let i = 0; i < steps; i++) {
      const a = (i / steps) * Math.PI * 2;
      parts.push(`${(Math.cos(a) * w).toFixed(2)}px ${(Math.sin(a) * w).toFixed(2)}px 0 ${outline}`);
    }
    parts.push(`0 0 ${(w * 0.6).toFixed(2)}px ${outline}`);
  }
  if (d > 0) parts.push(`${d.toFixed(2)}px ${d.toFixed(2)}px 0 ${shadow}`);
  return parts.join(', ') || 'none';
}

export function styleOf(track: AssTrack, ev: AssEvent): AssStyle {
  return track.styles.get(ev.style) ?? track.styles.get('Default') ?? DEFAULT_STYLE;
}
