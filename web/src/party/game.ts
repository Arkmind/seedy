import steps from './smack-attack.steps.json';

/* Medu Dance Revolution's rules: the song's step charts, timing windows, score and lives. No DOM here. */

export const LANES = ['left', 'down', 'up', 'right'] as const;
export type Lane = (typeof LANES)[number];
export type Level = 'Light' | 'Tentacular';
export const LEVELS: Level[] = ['Light', 'Tentacular'];
export type Judgment = 'perfect' | 'great' | 'good' | 'miss';

export const BPM = steps.bpm;
/** Seconds per beat. */
export const BEAT = 60 / BPM;

/** How far from the beat a press may land, either side, in seconds. Later than `good`, the note is missed. */
const WINDOW = { perfect: 0.05, great: 0.1, good: 0.15 };
const POINTS = { perfect: 1000, great: 500, good: 200 };
/** "+30, as tradition demands": the Konami code's thirty lives. */
export const LIVES = 30;

export interface Note {
  /** Seconds into the song. */
  t: number;
  lane: number;
  /** Null while it's still coming. */
  result: Judgment | null;
}

export class Run {
  readonly notes: Note[];
  score = 0;
  combo = 0;
  maxCombo = 0;
  lives = LIVES;
  misses = 0;
  /** Notes before this index are all judged. */
  private first = 0;

  constructor(readonly level: Level) {
    // A step names one lane, or several for a jump (both arrows at once): each arrow is its own note.
    const chart: { beat: number; lane: string | string[] }[] = steps.charts[level];
    this.notes = chart
      .flatMap((n) => (Array.isArray(n.lane) ? n.lane : [n.lane]).map((lane) => ({ t: steps.offset_seconds + n.beat * BEAT, lane: LANES.indexOf(lane as Lane), result: null })))
      .filter((n) => n.lane >= 0)
      .sort((a, b) => a.t - b.t);
  }

  get over() { return this.lives <= 0; }

  /** A press on `lane` at song time `t`: the nearest note still coming in that lane, if it's close enough. */
  hit(lane: number, t: number): Judgment | null {
    let best: Note | null = null;
    for (let i = this.first; i < this.notes.length; i++) {
      const n = this.notes[i];
      if (n.t - t > WINDOW.good) break;
      if (n.result || n.lane !== lane || Math.abs(n.t - t) > WINDOW.good) continue;
      if (!best || Math.abs(n.t - t) < Math.abs(best.t - t)) best = n;
    }
    if (!best) return null;
    const off = Math.abs(best.t - t);
    const j: Judgment = off <= WINDOW.perfect ? 'perfect' : off <= WINDOW.great ? 'great' : 'good';
    best.result = j;
    this.score += POINTS[j] + Math.min(this.combo, 100) * 10;
    this.combo += 1;
    this.maxCombo = Math.max(this.maxCombo, this.combo);
    return j;
  }

  /** Notes that went by unpressed at song time `t`; each costs a life and the combo. Returns how many. */
  sweep(t: number): number {
    let missed = 0;
    while (this.first < this.notes.length) {
      const n = this.notes[this.first];
      if (!n.result) {
        if (t - n.t <= WINDOW.good) break;
        n.result = 'miss';
        missed += 1;
      }
      this.first += 1;
    }
    if (missed) {
      this.combo = 0;
      this.misses += missed;
      this.lives = Math.max(0, this.lives - missed);
    }
    return missed;
  }
}

/** 42300 → "0 042 300", the board's seven-digit arcade score. */
export const formatScore = (n: number) => String(Math.min(n, 9_999_999)).padStart(7, '0').replace(/\B(?=(\d{3})+$)/g, ' ');
