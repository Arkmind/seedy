import { safeLocal, saveLocal } from '../../../lib/store';
import type { ReadingSettings } from '../api';

/* Reading settings (Book reader board): saved with the place in each book, so they follow the person to every device.
   A book opened for the first time starts from the settings last used on this device. */

export type PageTheme = 'dark' | 'black' | 'sepia' | 'light';
export type FontChoice = 'serif' | 'sans' | 'book';
export type Spacing = 'tight' | 'normal' | 'airy';
export type Margins = 'narrow' | 'normal' | 'wide';
export type Layout = 'one' | 'two' | 'scroll';
export type Fit = 'screen' | 'width';
export type Direction = 'ltr' | 'rtl';

export interface Bookmark { cfi: string; page: number; label: string; at: number }

export interface EpubSettings { theme: PageTheme; font: FontChoice; size: number; spacing: Spacing; margins: Margins; layout: Layout; bookmarks: Bookmark[] }
export interface ComicSettings { theme: PageTheme; layout: Layout; fit: Fit; direction: Direction }

export const EPUB_DEFAULTS: EpubSettings = { theme: 'dark', font: 'serif', size: 19, spacing: 'normal', margins: 'normal', layout: 'one', bookmarks: [] };
export const COMIC_DEFAULTS: ComicSettings = { theme: 'black', layout: 'one', fit: 'screen', direction: 'ltr' };

export const SIZE_MIN = 14;
export const SIZE_MAX = 30;

export const THEMES: Record<PageTheme, { bg: string; ink: string; heading: string; label: string; name: string }> = {
  dark: { bg: '#111111', ink: '#d8d4cc', heading: '#f0ece4', label: '#8c8c8c', name: 'Dark' },
  black: { bg: '#000000', ink: '#c8c8c8', heading: '#e6e6e6', label: '#7a7a7a', name: 'Black' },
  sepia: { bg: '#f1e6d0', ink: '#4a3b28', heading: '#2e2416', label: '#8a7454', name: 'Sepia' },
  light: { bg: '#fafafa', ink: '#1e1e1e', heading: '#000000', label: '#7a7a7a', name: 'Light' }
};

export const LINE_HEIGHT: Record<Spacing, number> = { tight: 1.5, normal: 1.7, airy: 1.9 };
/* The text column's width for one page; two pages use two columns of the next size down. */
export const COLUMN: Record<Margins, number> = { narrow: 760, normal: 660, wide: 560 };

const pick = <T extends string>(v: unknown, list: readonly T[], d: T): T => (list.includes(v as T) ? (v as T) : d);

export function readEpubSettings(saved?: ReadingSettings | null): EpubSettings {
  const base = { ...EPUB_DEFAULTS, ...safeLocal<Partial<EpubSettings>>('seedy.reader.epub', {}), bookmarks: [] };
  const s = (saved ?? {}) as Partial<EpubSettings>;
  const src = saved ? { ...base, ...s } : base;
  return {
    theme: pick(src.theme, ['dark', 'black', 'sepia', 'light'], 'dark'),
    font: pick(src.font, ['serif', 'sans', 'book'], 'serif'),
    size: Math.min(SIZE_MAX, Math.max(SIZE_MIN, Number(src.size) || 19)),
    spacing: pick(src.spacing, ['tight', 'normal', 'airy'], 'normal'),
    margins: pick(src.margins, ['narrow', 'normal', 'wide'], 'normal'),
    layout: pick(src.layout, ['one', 'two', 'scroll'], 'one'),
    bookmarks: Array.isArray(s.bookmarks) ? s.bookmarks.filter((b) => b && typeof b.cfi === 'string') : []
  };
}

export function rememberEpubSettings(s: EpubSettings) {
  const { bookmarks: _b, ...rest } = s;
  saveLocal('seedy.reader.epub', rest);
}

export function readComicSettings(saved?: ReadingSettings | null): ComicSettings {
  const base = { ...COMIC_DEFAULTS, ...safeLocal<Partial<ComicSettings>>('seedy.reader.comic', {}) };
  const src = { ...base, ...((saved ?? {}) as Partial<ComicSettings>) };
  return {
    theme: pick(src.theme, ['dark', 'black', 'sepia', 'light'], 'black'),
    layout: pick(src.layout, ['one', 'two', 'scroll'], 'one'),
    fit: pick(src.fit, ['screen', 'width'], 'screen'),
    direction: pick(src.direction, ['ltr', 'rtl'], 'ltr')
  };
}

export const rememberComicSettings = (s: ComicSettings) => saveLocal('seedy.reader.comic', s);
