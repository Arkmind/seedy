/* WebVTT for Seedy's own subtitle overlay. The server converts every text format (SRT, ASS, mov_text…)
   to VTT, so one small parser covers them all. Styling tags are kept to <i>, <b> and <u>. */

import { formatNumber } from '../lib/i18n';

export interface CueSpan { text: string; i?: boolean; b?: boolean; u?: boolean }
export interface Cue { start: number; end: number; lines: CueSpan[][] }

const TIME = /(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})/;

function parseTime(s: string): number | null {
  const m = TIME.exec(s);
  if (!m) return null;
  const ms = m[4].padEnd(3, '0');
  return (Number(m[1] ?? 0) * 3600) + Number(m[2]) * 60 + Number(m[3]) + Number(ms) / 1000;
}

const ENTITIES: Record<string, string> = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', lrm: '‎', rlm: '‏' };

function decode(s: string): string {
  return s.replace(/&(#x?[0-9a-f]+|[a-z]+);/gi, (all, e: string) => {
    if (e[0] === '#') {
      const n = e[1] === 'x' || e[1] === 'X' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
      return Number.isFinite(n) ? String.fromCodePoint(n) : all;
    }
    return ENTITIES[e.toLowerCase()] ?? all;
  });
}

function parseLine(line: string, state: { i: boolean; b: boolean; u: boolean }): CueSpan[] {
  const spans: CueSpan[] = [];
  /* Leftover ASS override blocks such as {\an8} or {\i1}. */
  const clean = line.replace(/\{\\[^}]*\}/g, '');
  const re = /<\s*(\/?)\s*([a-z]+)[^>]*>/gi;
  let last = 0;
  let m: RegExpExecArray | null;
  const push = (text: string) => {
    if (!text) return;
    spans.push({ text: decode(text), i: state.i || undefined, b: state.b || undefined, u: state.u || undefined });
  };
  while ((m = re.exec(clean))) {
    push(clean.slice(last, m.index));
    last = m.index + m[0].length;
    const tag = m[2].toLowerCase() as 'i' | 'b' | 'u';
    if (tag === 'i' || tag === 'b' || tag === 'u') state[tag] = m[1] !== '/';
  }
  push(clean.slice(last));
  return spans;
}

export function parseVtt(src: string): Cue[] {
  const text = src.replace(/^﻿/, '').replace(/\r\n?/g, '\n');
  const blocks = text.split(/\n{2,}/);
  const cues: Cue[] = [];
  for (const block of blocks) {
    const lines = block.split('\n');
    const at = lines.findIndex((l) => l.includes('-->'));
    if (at < 0) continue;
    const [a, b] = lines[at].split('-->');
    const start = parseTime(a);
    const end = parseTime(b ?? '');
    if (start === null || end === null) continue;
    const state = { i: false, b: false, u: false };
    const body = lines.slice(at + 1).filter((l) => l.trim() !== '').map((l) => parseLine(l, state)).filter((l) => l.length);
    if (body.length) cues.push({ start, end, lines: body });
  }
  cues.sort((x, y) => x.start - y.start);
  return cues;
}

export async function loadCues(url: string, signal?: AbortSignal): Promise<Cue[]> {
  const res = await fetch(url, { signal });
  if (!res.ok) throw new Error(`Subtitle request failed (${res.status})`);
  return parseVtt(await res.text());
}

/* "−0.2 s", "0.0 s", "+0.3 s" */
export function offsetLabel(seconds: number): string {
  const v = Math.round(seconds * 10) / 10;
  const one = (n: number) => formatNumber(n, { minimumFractionDigits: 1, maximumFractionDigits: 1, useGrouping: false });
  if (v === 0) return `${one(0)} s`;
  return `${v < 0 ? '−' : '+'}${one(Math.abs(v))} s`;
}
