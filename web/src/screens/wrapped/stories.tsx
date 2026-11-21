import type { ComponentChildren } from 'preact';
import { useStore } from '../../lib/store';
import { brandingStore } from '../../lib/app-state';
import { formatNumber, t, tn } from '../../lib/i18n';
import { backdropUrl, imageUrl, primaryUrl } from '../../api/images';
import type { BaseItem } from '../../api/types';
import { Medu, type MeduMood } from '../../ui/medu';
import { MeduProps } from './medu-props';
import type { StoryKey, Wrapped } from './api';
import {
  bingeEpisode, bingeLine, bingeText, clockDay, clockMedu, clockMood, clockShare, clockTitle, coverHint, genreLead, genreMedu,
  genreName, genreOther, hourChips, hoursOf, movieMedu, movieMeta, movieTickets, movieTitle, personalityMedu, personalityName,
  personalityText, showMeta, showTime, showsFootnote, showsMedu, statLine, summaryAlso, summaryLabel, timeOf
} from './copy';
import {
  BingeBackdrop, CoverBackdrop, GenresBackdrop, HoursBackdrop, MovieBackdrop, NightBackdrop, PersonalityBackdrop, ShowsBackdrop, SummaryBackdrop
} from './backdrops';

/* The nine Wrapped stories (boards Wrapped · 1–9), drawn at the boards' 390 px width; the viewer scales them. Phone
   boards start with a 32 px status bar; the web's stories (Web · Wrapped viewer) start 14 px down instead. */

export const STORY_MS = 8000;
const ACCENT = 'var(--seedy-accent)';
const DARK = '#1e1e1e';

export interface StoryProps {
  w: Wrapped;
  story: StoryKey;
  index: number;
  total: number;
  /** Story px tall (844 on a desktop; the screen's own height on a phone). */
  height: number;
  /** A preview beside the current story, or a picture being made: finished, still, not interactive. */
  still?: boolean;
  paused?: boolean;
  /** The last story: its bar stays full. */
  done?: boolean;
  onSegmentEnd?: () => void;
  onPause?: () => void;
  onClose?: () => void;
  onNext?: () => void;
  onShare?: () => void;
  onShareCard?: () => void;
  onSaveCard?: () => void;
  onReplay?: () => void;
  /** The share card's element (Summary), for its picture. */
  cardRef?: (el: HTMLElement | null) => void;
}

interface Theme { bg: string; light: boolean }

function themeOf(story: StoryKey, w: Wrapped): Theme {
  switch (story) {
    case 'hours': return { bg: ACCENT, light: false };
    case 'genres': return { bg: '#121212', light: true };
    case 'shows': return { bg: '#b8f59f', light: false };
    case 'movie': return { bg: '#ffc98a', light: false };
    case 'binge': return { bg: '#9fd3ff', light: false };
    case 'clock': return w.clock?.kind === 'morning' ? { bg: '#ffc98a', light: false }
      : w.clock?.kind === 'afternoon' ? { bg: '#9fd3ff', light: false }
      : w.clock?.kind === 'evening' ? { bg: '#2a1f4d', light: true }
      : { bg: '#121633', light: true };
    case 'personality': return { bg: 'linear-gradient(160deg, #cdb4ff 0%, #e6b8ff 55%, #ff9fff 100%)', light: false };
    default: return { bg: '#0e0e0e', light: true };
  }
}

const isDay = (w: Wrapped) => w.clock?.kind === 'morning' || w.clock?.kind === 'afternoon';

const LABELS: Record<StoryKey, () => string> = {
  cover: () => t('Seedy Wrapped'),
  hours: () => t('Hours watched'),
  genres: () => t('Your year in genres'),
  shows: () => t('Your top shows'),
  movie: () => t('Your most-watched movie'),
  binge: () => t('Your biggest binge'),
  clock: () => '',
  personality: () => t('Your watch personality'),
  summary: () => t('Seedy Wrapped')
};

export function Story(p: StoryProps) {
  const { w, story } = p;
  const th = themeOf(story, w);
  const ink = th.light ? '#ffffff' : DARK;
  const label = story === 'clock' ? clockTitle(w).join(' ') : LABELS[story]();
  const cls = 'wr-story' + (p.still ? ' is-still' : '') + (p.paused ? ' is-paused' : '') + (p.done ? ' is-done' : '');
  return (
    <div
      class={cls}
      role="region"
      aria-roledescription="story"
      aria-label={label}
      style={`height: ${p.height}px; background: ${th.bg}; color: ${ink}`}
    >
      <Backdrop story={story} w={w} />
      <div style="height: 14px" />
      <Header {...p} light={th.light} />
      <div style="position: absolute; z-index: 2; left: 0; right: 0; top: 80px; bottom: 96px; display: flex; flex-direction: column; padding: 0 24px">
        <Content {...p} />
      </div>
      <Footer {...p} light={th.light} />
    </div>
  );
}

function Backdrop({ story, w }: { story: StoryKey; w: Wrapped }) {
  switch (story) {
    case 'cover': return <CoverBackdrop />;
    case 'hours': return <HoursBackdrop />;
    case 'genres': return <GenresBackdrop />;
    case 'shows': return <ShowsBackdrop />;
    case 'movie': return <MovieBackdrop />;
    case 'binge': return <BingeBackdrop />;
    case 'clock': return <NightBackdrop kind={w.clock?.kind} />;
    case 'personality': return <PersonalityBackdrop />;
    default: return <SummaryBackdrop />;
  }
}

const ICON = 'fill: none; stroke: currentColor; stroke-width: 2.4; stroke-linecap: round; stroke-linejoin: round';

function Header(p: StoryProps & { light: boolean }) {
  const ink = p.light ? '#ffffff' : DARK;
  const track = p.light ? 'rgba(255, 255, 255, 0.25)' : 'rgba(30, 30, 30, 0.25)';
  return (
    <div style="position: relative; z-index: 5; display: flex; flex-direction: column; gap: 8px; padding: 2px 12px 0">
      <div role="progressbar" aria-label={t('Story {n} of {total}', { n: p.index + 1, total: p.total })} aria-valuemin={1} aria-valuemax={p.total} aria-valuenow={p.index + 1} style="display: flex; gap: 4px">
        {Array.from({ length: p.total }, (_, i) => (
          <span key={i} style={`position: relative; flex: 1; height: 3px; border-radius: 2px; background: ${track}`}>
            {i < p.index && <span style={`position: absolute; inset: 0; border-radius: 2px; background: ${ink}`} />}
            {i === p.index && (
              <span
                class="seg-now"
                style={`position: absolute; inset: 0; border-radius: 2px; background: ${ink}; animation-duration: ${STORY_MS}ms`}
                onAnimationEnd={(e) => { if (e.animationName === 'wr-seg') p.onSegmentEnd?.(); }}
              />
            )}
          </span>
        ))}
      </div>
      <div style="display: flex; align-items: center; gap: 6px">
        <span style={`flex: 1; display: flex; align-items: center; gap: 6px; padding-left: 2px; font-size: 13px; font-weight: 900; letter-spacing: 0.02em; color: ${ink}`}>
          <Medu mood="idle" size={22} glow />
          {t('Seedy Wrapped')} <span style="font-weight: 700; opacity: 0.6">{p.w.year}</span>
        </span>
        <button type="button" data-share-hide aria-label={p.paused ? t('Play') : t('Pause')} onClick={p.onPause} tabIndex={p.still ? -1 : 0} style={`width: 40px; height: 40px; display: flex; align-items: center; justify-content: center; padding: 0; border: 0; border-radius: 50%; background: transparent; color: ${ink}; cursor: pointer`}>
          {p.paused
            ? <svg viewBox="0 0 24 24" width="22" height="22" style={ICON} aria-hidden="true"><path d="M7 5l12 7-12 7z" /></svg>
            : <svg viewBox="0 0 24 24" width="22" height="22" style={ICON} aria-hidden="true"><path d="M8 5v14" /><path d="M16 5v14" /></svg>}
        </button>
        <button type="button" data-share-hide aria-label={t('Close Wrapped')} onClick={p.onClose} tabIndex={p.still ? -1 : 0} style={`width: 40px; height: 40px; display: flex; align-items: center; justify-content: center; padding: 0; border: 0; border-radius: 50%; background: transparent; color: ${ink}; cursor: pointer`}>
          <svg viewBox="0 0 24 24" width="22" height="22" style={ICON} aria-hidden="true"><path d="M6 6l12 12" /><path d="M18 6L6 18" /></svg>
        </button>
      </div>
    </div>
  );
}

const SHARE_ICON = <svg viewBox="0 0 24 24" width="18" height="18" style={ICON} aria-hidden="true"><path d="M12 3v12" /><path d="M7 8l5-5 5 5" /><path d="M5 13v6a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-6" /></svg>;

function Footer(p: StoryProps & { light: boolean }) {
  if (p.story === 'cover') return null;
  if (p.story === 'summary') {
    return (
      <div class="in" style="animation-delay: 1s; position: absolute; z-index: 5; left: 24px; right: 24px; bottom: 26px; display: flex; gap: 8px">
        <button type="button" onClick={p.onShareCard} tabIndex={p.still ? -1 : 0} style="flex: 1; display: flex; align-items: center; justify-content: center; gap: 8px; height: 52px; border: 0; border-radius: 999px; background: #f0f0f0; color: #1e1e1e; font-size: 16px; font-weight: 900; cursor: pointer">
          <svg viewBox="0 0 24 24" width="19" height="19" style={ICON} aria-hidden="true"><path d="M12 3v12" /><path d="M7 8l5-5 5 5" /><path d="M5 13v6a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1v-6" /></svg>{t('Share')}
        </button>
        <button type="button" onClick={p.onSaveCard} tabIndex={p.still ? -1 : 0} style="display: flex; align-items: center; justify-content: center; gap: 8px; height: 52px; padding: 0 18px; border: 0; border-radius: 999px; background: #262626; color: #ffffff; font-size: 15px; font-weight: 800; cursor: pointer">
          <svg viewBox="0 0 24 24" width="19" height="19" style={ICON} aria-hidden="true"><path d="M12 4v11" /><path d="M7 10l5 5 5-5" /><path d="M5 20h14" /></svg>{t('Save image')}
        </button>
        <button type="button" aria-label={t('Watch Wrapped again')} onClick={p.onReplay} tabIndex={p.still ? -1 : 0} style="flex: none; width: 52px; height: 52px; display: flex; align-items: center; justify-content: center; border: 0; border-radius: 50%; background: #262626; color: #ffffff; cursor: pointer">
          <svg viewBox="0 0 24 24" width="20" height="20" style={ICON} aria-hidden="true"><path d="M4 12a8 8 0 1 0 2.4-5.7" /><path d="M4 4v5h5" /></svg>
        </button>
      </div>
    );
  }
  const bg = p.light ? '#f0f0f0' : DARK;
  const fg = p.light ? DARK : '#ffffff';
  return (
    <div data-share-hide style="position: absolute; z-index: 5; left: 0; right: 0; bottom: 28px; display: flex; justify-content: center">
      <button type="button" onClick={p.onShare} tabIndex={p.still ? -1 : 0} style={`display: flex; align-items: center; gap: 8px; height: 46px; padding: 0 22px; border: 0; border-radius: 999px; background: ${bg}; color: ${fg}; font-size: 15px; font-weight: 800; cursor: pointer`}>
        {SHARE_ICON}{t('Share this story')}
      </button>
    </div>
  );
}

/* Medu's speech bubble; its tail points down at Medu on its [side]. */
function Say({ children, side, dark, delay, style }: { children: ComponentChildren; side: 'left' | 'right'; dark?: boolean; delay: number; style: string }) {
  const bg = dark ? DARK : '#ffffff';
  const fg = dark ? '#ffffff' : DARK;
  return (
    <span class="say" style={`animation-delay: ${delay.toFixed(2)}s; position: relative; display: block; max-width: 190px; padding: 10px 14px; border-radius: 18px; background: ${bg}; color: ${fg}; font-size: 14px; font-weight: 800; line-height: 1.3; ${style}`}>
      {children}
      <span aria-hidden="true" style={`position: absolute; bottom: -7px; ${side}: 22px; width: 16px; height: 16px; background: ${bg}; transform: rotate(45deg); border-radius: 3px`} />
    </span>
  );
}

function Pop({ delay, children, style = '' }: { delay: number; children: ComponentChildren; style?: string }) {
  return <div class="pop" style={`animation-delay: ${delay}s; ${style}`}>{children}</div>;
}

function MeduAt({ mood, size, label, glow = true }: { mood: MeduMood; size: number; label: string; glow?: boolean }) {
  return <span role="img" aria-label={label} style="display: block"><MeduProps mood={mood} size={size} glow={glow} /></span>;
}

function Content(p: StoryProps) {
  switch (p.story) {
    case 'cover': return <Cover {...p} />;
    case 'hours': return <Hours {...p} />;
    case 'genres': return <Genres {...p} />;
    case 'shows': return <Shows {...p} />;
    case 'movie': return <MovieStory {...p} />;
    case 'binge': return <Binge {...p} />;
    case 'clock': return <Clock {...p} />;
    case 'personality': return <Personality {...p} />;
    default: return <Summary {...p} />;
  }
}

// ── 1 · Cover ──

function Cover({ w, total, onNext, still }: StoryProps) {
  const year = String(w.year);
  return (
    <div style="flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; text-align: center">
      <Pop delay={0.15} style="margin-bottom: 30px"><MeduAt mood="party" size={156} glow={false} label={t('Medu, ready to party')} /></Pop>
      <div class="in" style="animation-delay: .45s; position: relative; font-size: 118px; line-height: 0.9; font-weight: 900; letter-spacing: -0.05em">
        <span aria-hidden="true" style="position: absolute; left: 0; top: -30px; -webkit-text-stroke: 2px rgba(255, 159, 255, 0.22); color: transparent">{year}</span>
        <span aria-hidden="true" style="position: absolute; left: 0; top: -15px; -webkit-text-stroke: 2px rgba(255, 159, 255, 0.45); color: transparent">{year}</span>
        <span style={`position: relative; color: ${ACCENT}`}>{year}</span>
      </div>
      <h1 class="in" style="animation-delay: .6s; margin: 8px 0 0; font-size: 50px; line-height: 1; font-weight: 900; letter-spacing: -0.02em; text-transform: uppercase; color: #ffffff">{t('Wrapped')}</h1>
      <p class="in" style="animation-delay: .75s; margin: 16px 0 0; max-width: 300px; font-size: 16px; line-height: 1.45; color: #c8c8c8">{t('Medu took notes all year. Here’s what you watched, binged and couldn’t stop rewatching.')}</p>
      <button class="in" type="button" onClick={onNext} tabIndex={still ? -1 : 0} style="animation-delay: .9s; position: relative; z-index: 5; display: flex; align-items: center; gap: 10px; height: 54px; margin-top: 26px; padding: 0 30px; border: 0; border-radius: 999px; background: #f0f0f0; color: #1e1e1e; font-size: 17px; font-weight: 900; cursor: pointer">
        {t('Let’s go')}
        <svg viewBox="0 0 24 24" width="20" height="20" style="fill: none; stroke: currentColor; stroke-width: 2.6; stroke-linecap: round; stroke-linejoin: round" aria-hidden="true"><path d="M5 12h14" /><path d="M13 6l6 6-6 6" /></svg>
      </button>
      <span class="in" style="animation-delay: 1s; margin-top: 12px; font-size: 12px; color: #8c8c8c">{coverHint(total)}</span>
    </div>
  );
}

// ── 2 · Hours ──

/* The board's odometer: each digit rolls up to its value; separators stay put. */
function Odometer({ text }: { text: string }) {
  let d = 0;
  return (
    <span class="odo" aria-hidden="true">
      {[...text].map((ch, i) => {
        if (!/\d/.test(ch)) return <span key={i} style="animation: none">{ch}</span>;
        const delay = 0.2 + 0.08 * d++;
        return (
          <span key={i} style={`transform: translateY(-${ch}em); animation-delay: ${delay.toFixed(2)}s`}>
            {Array.from({ length: 10 }, (_, n) => <span key={n}>{n}</span>)}
          </span>
        );
      })}
    </span>
  );
}

function Hours({ w }: StoryProps) {
  const hours = hoursOf(w.watchMs);
  const text = formatNumber(hours);
  const big = text.length > 5 ? 100 : 128;
  return <>
    <span class="in" style="animation-delay: .1s; margin-top: 18px; font-size: 22px; font-weight: 800">{t('This year you watched')}</span>
    <div role="img" aria-label={tn(hours, '{n} hour', '{n} hours')} style={`margin: 6px 0 0 -6px; font-size: ${big}px; font-weight: 900; letter-spacing: -0.06em; font-variant-numeric: tabular-nums`}>
      <Odometer text={text} />
    </div>
    <span class="in" style="animation-delay: .9s; margin-top: -22px; font-size: 62px; line-height: 1; font-weight: 900; letter-spacing: -0.03em; text-transform: uppercase">{hours === 1 ? t('hour') : t('hours')}</span>
    <div class="in" style="animation-delay: 1.3s; display: flex; flex-wrap: wrap; gap: 8px; margin-top: 22px">
      {hourChips(w).map((c) => (
        <span key={c.small} style="display: flex; flex-direction: column; gap: 1px; padding: 10px 13px; border-radius: 16px; background: #1e1e1e; color: #ffffff">
          <b style="font-size: 19px; font-weight: 900">{c.big}</b><span style="font-size: 12px; color: #c8c8c8">{c.small}</span>
        </span>
      ))}
    </div>
    <div style="flex: 1" />
    <div style="display: flex; align-items: flex-end; justify-content: flex-end; gap: 0; margin-right: -14px">
      <Say side="right" delay={1.8} style="margin-bottom: 120px; margin-right: -26px">{t('I made popcorn for every single one of them.')}</Say>
      <Pop delay={1.5}><MeduAt mood="popcorn" size={150} label={t('Medu with popcorn')} /></Pop>
    </div>
  </>;
}

// ── 3 · Genres ──

const GENRE_COLORS = [ACCENT, '#ffc98a', '#9fd3ff', '#b8f59f', '#cdb4ff'];

function Genres({ w }: StoryProps) {
  const top = Math.max(1, w.genres[0]?.pct ?? 1);
  return <>
    <h2 class="in" style="animation-delay: .1s; margin: 18px 0 0; font-size: 44px; line-height: 0.98; font-weight: 900; letter-spacing: -0.03em; text-transform: uppercase; color: #ffffff">{t('Your year')}<br />{t('in genres')}</h2>
    <p class="in" style="animation-delay: .25s; margin: 12px 0 0; font-size: 16px; line-height: 1.45; color: #c8c8c8">{genreLead(w)}</p>
    <ol style="list-style: none; margin: 26px 0 0; padding: 0; display: flex; flex-direction: column; gap: 18px">
      {w.genres.slice(0, 5).map((g, i) => {
        const color = GENRE_COLORS[i];
        return (
          <li key={g.name} class="in" style={`animation-delay: ${(0.5 + i * 0.12).toFixed(2)}s; display: flex; align-items: center; gap: 14px`}>
            <span style={`flex: none; width: 82px; font-size: 38px; font-weight: 900; letter-spacing: -0.04em; color: ${color}`}>{formatNumber(g.pct)}%</span>
            <span style="flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 7px">
              <span style="font-size: 16px; font-weight: 800; color: #ffffff; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{genreName(g)}</span>
              <span style="display: block; height: 14px; border-radius: 7px; background: #262626">
                <span class="grow" style={`display: block; width: ${Math.max(4, (g.pct / top) * 100).toFixed(1)}%; height: 100%; border-radius: 7px; background: ${color}; animation-delay: ${(0.6 + i * 0.12).toFixed(2)}s`} />
              </span>
            </span>
          </li>
        );
      })}
    </ol>
    {genreOther(w) && <span class="in" style="animation-delay: 1.2s; margin-top: 14px; font-size: 12px; color: #8c8c8c">{genreOther(w)}</span>}
    <div style="flex: 1" />
    <div style="display: flex; align-items: flex-end; gap: 0; margin-left: -10px">
      <Pop delay={1.4}><MeduAt mood="think" size={104} label={t('Medu, thinking')} /></Pop>
      <Say side="left" delay={1.7} style="margin-bottom: 92px; margin-left: -14px">{genreMedu(w)}</Say>
    </div>
  </>;
}

// ── Pictures ──

const PALETTE = [
  { bg: '#24313d', sun: '#c9d6e3', mid: '#3b4d5e', fg: '#151c23' },
  { bg: '#3d2424', sun: '#f0b9a0', mid: '#5a3030', fg: '#1c1111' },
  { bg: '#26362b', sun: '#d9e3b5', mid: '#3b5240', fg: '#121a14' },
  { bg: '#3f3322', sun: '#f2cf86', mid: '#5c4a2c', fg: '#1d170f' },
  { bg: '#2f2840', sun: '#e3c4ff', mid: '#463b5e', fg: '#16121f' },
  { bg: '#1f3a3c', sun: '#a8e6df', mid: '#2e5457', fg: '#0f1c1d' }
];
const MOUNTAINS = ['M0 120 L60 80 L110 110 L170 60 L230 105 L320 70 V180 H0Z', 'M0 135 Q80 90 160 125 T320 115 V180 H0Z', 'M0 100 L90 140 L150 95 L220 135 L320 90 V180 H0Z'];
const SHORES = ['M0 150 L80 130 L150 150 L230 120 L320 145 V180 H0Z', 'M0 160 Q100 135 200 158 T320 150 V180 H0Z'];

const hash = (s: string) => [...s].reduce((h, c) => (h * 31 + c.charCodeAt(0)) | 0, 7);

/* The boards' drawn landscape, for a title without a picture: the same one wherever it's drawn. */
function Landscape({ seed }: { seed: string }) {
  const i = Math.abs(hash(seed));
  const c = PALETTE[i % PALETTE.length];
  return (
    <svg viewBox="0 0 320 180" preserveAspectRatio="xMidYMid slice" style="position: absolute; inset: 0; width: 100%; height: 100%" aria-hidden="true">
      <rect width="320" height="180" fill={c.bg} />
      <circle cx={60 + (i * 71) % 200} cy={36 + (i * 13) % 30} r={14 + (i * 7) % 14} fill={c.sun} />
      <path d={MOUNTAINS[i % 3]} fill={c.mid} />
      <path d={SHORES[(i + 1) % 2]} fill={c.fg} />
    </svg>
  );
}

const wideArt = (item: BaseItem | null | undefined, width: number) => {
  if (!item) return undefined;
  if (item.ImageTags?.Thumb) return imageUrl(item.Id, 'Thumb', { tag: item.ImageTags.Thumb, maxWidth: width });
  return backdropUrl(item, width);
};

function Wide({ item, seed, width, backdrop }: { item?: BaseItem | null; seed: string; width: number; backdrop?: boolean }) {
  const src = backdrop && item ? backdropUrl(item, width) ?? wideArt(item, width) : wideArt(item, width);
  return src
    ? <img src={src} alt="" crossOrigin="anonymous" style="position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover" />
    : <Landscape seed={seed} />;
}

// ── 4 · Top shows ──

function Shows({ w }: StoryProps) {
  const [first, ...rest] = w.shows;
  if (!first) return null;
  return <>
    <h2 class="in" style="animation-delay: .1s; margin: 18px 0 0; font-size: 44px; line-height: 0.98; font-weight: 900; letter-spacing: -0.03em; text-transform: uppercase">{t('Your top')}<br />{t('shows')}</h2>
    <div class="pop" style="animation-delay: .35s; position: relative; flex: none; margin-top: 18px; height: 158px; border-radius: 22px; overflow: hidden; box-shadow: 0 14px 30px rgba(30, 30, 30, 0.25)">
      <Wide item={first.item} seed={first.id} width={700} backdrop />
      <div style="position: absolute; inset: 0; background: linear-gradient(0deg, rgba(0, 0, 0, 0.75), rgba(0, 0, 0, 0) 60%)" />
      <span aria-hidden="true" style="position: absolute; right: 16px; top: 0; font-size: 92px; line-height: 1; font-weight: 900; letter-spacing: -0.05em; color: transparent; -webkit-text-stroke: 2.5px #ffffff">1</span>
      <span style="position: absolute; left: 16px; right: 16px; bottom: 14px; display: flex; align-items: flex-end; justify-content: space-between; gap: 10px; color: #ffffff">
        <span style="display: flex; flex-direction: column; min-width: 0">
          <b style="font-size: 26px; line-height: 1.15; font-weight: 900; letter-spacing: -0.01em; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical">{first.name}</b>
          <span style="font-size: 13px; color: #d0d0d0">{showMeta(first)}</span>
        </span>
        <span style={`flex: none; padding: 4px 10px; border-radius: 999px; background: ${ACCENT}; color: #1e1e1e; font-size: 12px; font-weight: 900`}>{t('Your #1')}</span>
      </span>
    </div>
    <ol style="list-style: none; margin: 16px 0 0; padding: 0; display: flex; flex-direction: column; gap: 10px">
      {rest.slice(0, 4).map((s, i) => (
        <li key={s.id} class="in" style={`animation-delay: ${(0.6 + i * 0.12).toFixed(2)}s; display: flex; align-items: center; gap: 14px`}>
          <span style="flex: none; width: 30px; font-size: 30px; font-weight: 900; letter-spacing: -0.04em">{i + 2}</span>
          <span aria-hidden="true" style="position: relative; flex: none; width: 70px; height: 40px; border-radius: 9px; overflow: hidden"><Wide item={s.item} seed={s.id} width={160} /></span>
          <span style="flex: 1; min-width: 0; display: flex; flex-direction: column">
            <b style="font-size: 17px; font-weight: 900; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{s.name}</b>
            <span style="font-size: 13px; opacity: 0.7">{tn(s.episodes, '{n} episode', '{n} episodes')}</span>
          </span>
          <span style="flex: none; font-size: 15px; font-weight: 900">{showTime(s.watchMs)}</span>
        </li>
      ))}
    </ol>
    {showsFootnote(w) && <p class="in" style="animation-delay: 1.3s; margin: 14px 0 0; font-size: 14px; line-height: 1.45; font-weight: 600; opacity: 0.75">{showsFootnote(w)}</p>}
    <div style="flex: 1" />
    <div style="display: flex; align-items: flex-end; justify-content: flex-end; margin: 0 -10px -6px 0">
      <Say side="right" dark delay={1.8} style="margin-bottom: 52px; margin-right: -14px">{showsMedu(w)}</Say>
      <Pop delay={1.5}><MeduAt mood="wave" size={78} label={t('Medu, waving')} /></Pop>
    </div>
  </>;
}

// ── 5 · The movie ──

const TICKET_MASK = 'radial-gradient(circle 5px at 0 50%, transparent 98%, #000) left / 51% 100% no-repeat, radial-gradient(circle 5px at 100% 50%, transparent 98%, #000) right / 51% 100% no-repeat';

function LanternArt({ name }: { name: string }) {
  return (
    <svg viewBox="0 0 400 600" style="position: absolute; inset: 0; width: 100%; height: 100%" aria-hidden="true">
      <rect width="400" height="600" fill="#2a1519" /><circle cx="270" cy="200" r="120" fill="#f3b69a" opacity="0.12" /><circle cx="270" cy="200" r="58" fill="#f3b69a" />
      <path d="M0 380 C90 330 170 350 240 380 C310 410 360 360 400 370 V600 H0Z" fill="#4a2228" />
      <path d="M0 460 C110 420 220 450 300 470 C350 482 380 470 400 472 V600 H0Z" fill="#1f0e12" />
      {([[70, 120, 0.9], [130, 170, 0.75], [190, 96, 0.85], [52, 236, 0.6]] as const).map(([x, y, o]) => (
        <g key={x} transform={`translate(${x} ${y})`}><path d="M0 0v16" stroke="#3a1a1f" stroke-width="3" /><rect x="-14" y="16" width="28" height="38" rx="12" fill="#ffb38a" opacity={o} /></g>
      ))}
      <foreignObject x="20" y="490" width="360" height="100">
        <div style="height: 100%; display: flex; align-items: center; justify-content: center; text-align: center; font: 900 40px/1.05 var(--font); letter-spacing: 1px; color: #ffffff; text-transform: uppercase; overflow: hidden">{name}</div>
      </foreignObject>
    </svg>
  );
}

function MovieStory({ w }: StoryProps) {
  const m = w.movie;
  if (!m) return null;
  const poster = m.item ? primaryUrl(m.item, 420) : undefined;
  const title = movieTitle(w);
  return <>
    <h2 class="in" style="animation-delay: .1s; margin: 18px 0 0; font-size: 40px; line-height: 0.98; font-weight: 900; letter-spacing: -0.03em; text-transform: uppercase">{title}</h2>
    <div style="position: relative; display: flex; justify-content: center; margin-top: 26px">
      <div class="pop" style="animation-delay: .35s; position: relative; width: 196px; height: 294px; border-radius: 16px; overflow: hidden; transform: rotate(-4deg); box-shadow: 0 22px 44px rgba(60, 30, 0, 0.35); background: #2a1519">
        {poster
          ? <img src={poster} alt="" crossOrigin="anonymous" style="position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover" />
          : <LanternArt name={m.name} />}
      </div>
      <span class="pop" role="img" aria-label={tn(m.views, 'Watched {n} time', 'Watched {n} times')} style="animation-delay: .8s; position: absolute; right: 6px; top: -10px; width: 108px; height: 108px; border-radius: 50%; background: #1e1e1e; color: #ffffff; display: flex; flex-direction: column; align-items: center; justify-content: center; transform: rotate(8deg)">
        <b style="font-size: 50px; line-height: 0.9; font-weight: 900; letter-spacing: -0.04em">{formatNumber(m.views)}×</b>
        <span style={`font-size: 11px; font-weight: 800; color: ${ACCENT}`}>{t('watched')}</span>
      </span>
    </div>
    <div class="in" style="animation-delay: 1s; display: flex; flex-direction: column; align-items: center; gap: 4px; margin-top: 22px; text-align: center">
      <b style="font-size: 30px; line-height: 1.1; font-weight: 900; letter-spacing: -0.02em">{m.name}</b>
      <span style="font-size: 14px; font-weight: 600; opacity: 0.75">{movieMeta(w)}</span>
    </div>
    <div style="display: flex; justify-content: center; gap: 6px; margin-top: 14px">
      {movieTickets(w).map((month, i) => (
        <span key={i} class="pop" style={`animation-delay: ${(1.2 + i * 0.12).toFixed(2)}s; display: flex; flex-direction: column; align-items: center; justify-content: center; flex: 0 1 58px; min-width: 0; height: 34px; border-radius: 8px; background: #1e1e1e; color: #ffffff; font-size: 11px; font-weight: 800; line-height: 1.15; -webkit-mask: ${TICKET_MASK}; mask: ${TICKET_MASK}`}>{month}</span>
      ))}
    </div>
    <div style="flex: 1" />
    <div style="display: flex; align-items: flex-end; margin-left: -12px">
      <Pop delay={1.7}><MeduAt mood="love" size={96} label={t('Medu, in love')} /></Pop>
      <Say side="left" delay={2} style="margin-bottom: 80px; margin-left: -10px">{movieMedu(w)}</Say>
    </div>
  </>;
}

// ── 6 · The binge ──

function Binge({ w }: StoryProps) {
  const b = w.binge;
  if (!b) return null;
  const minutes = b.minutes?.length ? b.minutes : Array.from({ length: b.episodes }, () => 1);
  const boxes = minutes.slice(0, 12);
  const more = b.episodes - boxes.length;
  return <>
    <h2 class="in" style="animation-delay: .1s; margin: 18px 0 0; font-size: 40px; line-height: 0.98; font-weight: 900; letter-spacing: -0.03em; text-transform: uppercase">{t('Your biggest')}<br />{t('binge')}</h2>
    <div class="in" style="animation-delay: .3s; display: flex; align-items: baseline; gap: 10px; margin-top: 10px">
      <b style="font-size: 104px; line-height: 0.9; font-weight: 900; letter-spacing: -0.06em">{formatNumber(b.episodes)}</b>
      <span style="font-size: 26px; line-height: 1; font-weight: 900">{t('episodes')}<br />{t('in one go')}</span>
    </div>
    <div role="img" aria-label={`${tn(b.episodes, '{n} episode', '{n} episodes')}, ${timeOf(b.start)}–${timeOf(b.end)}`} style="display: flex; gap: 4px; margin-top: 18px">
      {boxes.map((min, i) => (
        <span key={i} class="pop" style={`animation-delay: ${(0.7 + i * 0.08).toFixed(2)}s; flex: ${Math.max(1, min)}; min-width: 0; height: 30px; border-radius: 8px; background: #1e1e1e; color: #9fd3ff; display: flex; align-items: center; justify-content: center; font-size: 11px; font-weight: 900`}>
          {i === boxes.length - 1 && more > 0 ? `+${more + 1}` : i + 1}
        </span>
      ))}
    </div>
    <div class="in" style="animation-delay: 1.2s; display: flex; justify-content: space-between; gap: 8px; margin-top: 6px; font-size: 12px; font-weight: 800">
      <span>{timeOf(b.start)}</span><span style="opacity: 0.7; text-align: center; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{bingeLine(w)}</span><span>{timeOf(b.end)}</span>
    </div>
    {w.still.asked > 0 && (
      <div style="position: relative; height: 158px; margin-top: 22px">
        <div class="fan" style="animation-delay: 1.30s; position: absolute; left: 50%; top: 22px; width: 246px; height: 108px; margin-left: -123px; border-radius: 18px; background: #1e1e1e; box-shadow: 0 10px 26px rgba(0, 30, 60, 0.3); transform: rotate(-8deg); opacity: 0.35" />
        <div class="fan" style="animation-delay: 1.38s; position: absolute; left: 50%; top: 14px; width: 246px; height: 108px; margin-left: -123px; border-radius: 18px; background: #1e1e1e; box-shadow: 0 10px 26px rgba(0, 30, 60, 0.3); transform: rotate(6deg); opacity: 0.6" />
        <div class="fan" style="animation-delay: 1.45s; position: absolute; left: 50%; top: 26px; width: 246px; margin-left: -123px; padding: 14px; box-sizing: border-box; border-radius: 18px; background: #1e1e1e; color: #ffffff; box-shadow: 0 10px 26px rgba(0, 30, 60, 0.3); transform: rotate(0deg); opacity: 1">
          <b style="display: block; font-size: 16px; font-weight: 900">{t('Still watching?')}</b>
          <span style="display: block; margin-top: 2px; font-size: 12px; color: #a1a1a1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">{bingeEpisode(w)}</span>
          <span style="display: flex; gap: 6px; margin-top: 10px">
            <span style="flex: 1; height: 30px; border-radius: 9px; background: #f0f0f0; color: #1e1e1e; display: flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 800">{t('Yes, keep going')}</span>
            <span style="height: 30px; padding: 0 10px; border-radius: 9px; background: #2a2a2a; display: flex; align-items: center; font-size: 12px; font-weight: 700">{t('Stop')}</span>
          </span>
        </div>
        <span class="pop" style={`animation-delay: 1.8s; position: absolute; right: 4px; top: 0; z-index: 2; padding: 6px 10px; border-radius: 999px; background: ${ACCENT}; color: #1e1e1e; font-size: 15px; font-weight: 900; transform: rotate(10deg)`}>×{formatNumber(w.still.asked)}</span>
      </div>
    )}
    <p class="in" style="animation-delay: 1.9s; margin: 14px 0 0; font-size: 15px; line-height: 1.45; font-weight: 700">{bingeText(w)}</p>
    <div style="flex: 1" />
    <div style="display: flex; align-items: flex-end; margin: 0 0 -6px -10px">
      <Pop delay={2.1}><MeduAt mood="search" size={80} label={t('Medu, baffled')} /></Pop>
      <Say side="left" dark delay={2.4} style="margin-bottom: 56px; margin-left: -8px">{t('Did you… blink at all?')}</Say>
    </div>
  </>;
}

// ── 7 · The clock ──

/* 24 bars around a clock face, midnight at the top; the five hours around the peak in the accent. */
function ClockFace({ w, day }: { w: Wrapped; day: boolean }) {
  const c = w.clock!;
  const max = Math.max(1, ...c.hours);
  const peakHour = Number(c.peak.slice(0, 2));
  const near = (h: number) => Math.min(Math.abs(h - peakHour), 24 - Math.abs(h - peakHour)) <= 2;
  const rest = day ? 'rgba(30, 30, 30, 0.22)' : 'rgba(255, 255, 255, 0.32)';
  const label = day ? '#6b6b6b' : '#8c8c8c';
  const order = Array.from({ length: 24 }, (_, i) => i);
  return (
    <svg role="img" aria-label={t('Watching by hour of day, peaking at {time}', { time: c.peak })} viewBox="0 0 300 300" width="270" height="270" style="display: block; margin: 8px auto 0; overflow: visible">
      {order.map((h, i) => {
        const a = (h / 24) * Math.PI * 2;
        const len = 6.6 + 57 * (c.hours[h] / max);
        const x1 = 150 + 62 * Math.sin(a), y1 = 150 - 62 * Math.cos(a);
        const x2 = 150 + (62 + len) * Math.sin(a), y2 = 150 - (62 + len) * Math.cos(a);
        return <path key={h} class="draw" pathLength={1} d={`M${x1.toFixed(1)} ${y1.toFixed(1)} L${x2.toFixed(1)} ${y2.toFixed(1)}`} stroke={near(h) ? ACCENT : rest} stroke-width="9" stroke-linecap="round" stroke-dasharray="1" stroke-dashoffset="0" style={`animation-delay: ${(0.5 + i * 0.035).toFixed(2)}s`} />;
      })}
      {([['00', 150, 0], ['06', 300, 150], ['12', 150, 300], ['18', 0, 150]] as const).map(([txt, x, y]) => (
        <text key={txt} x={x} y={y} text-anchor="middle" dominant-baseline="middle" font-family="Noto Sans Variable, Noto Sans, sans-serif" font-size="11" font-weight="800" fill={label}>{txt}</text>
      ))}
      <text x="150" y="146" text-anchor="middle" font-family="Noto Sans Variable, Noto Sans, sans-serif" font-size="34" font-weight="900" fill={day ? DARK : '#ffffff'} letter-spacing="-1">{c.peak}</text>
      <text x="150" y="168" text-anchor="middle" font-family="Noto Sans Variable, Noto Sans, sans-serif" font-size="12" font-weight="700" fill={day ? '#5a5a5a' : '#a1a1a1'}>{t('your peak')}</text>
    </svg>
  );
}

function Clock({ w }: StoryProps) {
  if (!w.clock) return null;
  const day = isDay(w);
  const [lead, accent] = clockTitle(w);
  const share = clockShare(w);
  const at = share.indexOf('{pct}');
  const pct = <b style={`color: ${day ? DARK : '#ffffff'}`}>{formatNumber(w.clock.pct)}%</b>;
  return <>
    <h2 class="in" style={`animation-delay: .1s; margin: 18px 0 0; font-size: 44px; line-height: 0.98; font-weight: 900; letter-spacing: -0.03em; text-transform: uppercase; color: ${day ? DARK : '#ffffff'}`}>
      {lead}<br /><span style={`color: ${day ? '#7a3fd1' : ACCENT}`}>{accent}</span>
    </h2>
    <div class="in" style="animation-delay: .3s"><ClockFace w={w} day={day} /></div>
    <p class="in" style={`animation-delay: 1.4s; margin: 6px 0 0; font-size: 16px; line-height: 1.45; color: ${day ? '#3a3a3a' : '#c8c8c8'}`}>
      {at >= 0 ? <>{share.slice(0, at)}{pct}{share.slice(at + 5)}</> : share} {clockDay(w)}
    </p>
    <div style="flex: 1" />
    <div style="display: flex; align-items: flex-end; justify-content: flex-end; margin-right: -10px">
      <Say side="right" dark={day} delay={1.9} style="margin-bottom: 70px; margin-right: -18px">{clockMedu(w)}</Say>
      <Pop delay={1.6}><MeduAt mood={clockMood(w)} size={104} label={w.clock.kind === 'night' ? t('Medu, sleepy') : t('Medu, waving')} /></Pop>
    </div>
  </>;
}

// ── 8 · The personality ──

export function PersonalityIcon({ kind, size }: { kind: string; size: number }) {
  const paths: Record<string, preact.JSX.Element> = {
    sprinter: <path d="M13 2L4.5 13.5H11l-1 8.5 8.5-11.5H12z" />,
    rewatcher: <><path d="M4 12a8 8 0 1 0 2.4-5.7" /><path d="M4 4v5h5" /></>,
    cinephile: <><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M7 4v16M17 4v16M3 9h4M3 15h4M17 9h4M17 15h4" /></>,
    explorer: <><circle cx="12" cy="12" r="9" /><path d="M15.5 8.5l-2 5-5 2 2-5z" /></>,
    regular: <><rect x="3.5" y="5" width="17" height="15" rx="2" /><path d="M3.5 10h17M8 3v4M16 3v4" /></>,
    slowburner: <><path d="M7 3h10M7 21h10" /><path d="M8 3c0 5 8 5 8 9s-8 4-8 9M16 3c0 5-8 5-8 9s8 4 8 9" /></>,
    weekender: <><path d="M4 11V8a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v3" /><path d="M3 11a2 2 0 0 1 2 2v2h14v-2a2 2 0 1 1 2 2v3H3v-3a2 2 0 0 1 0-4z" /><path d="M6 18v2M18 18v2" /></>,
    easygoing: <path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z" />
  };
  return <svg viewBox="0 0 24 24" width={size} height={size} style={ICON} aria-hidden="true">{paths[kind] ?? paths.easygoing}</svg>;
}

function Personality({ w }: StoryProps) {
  const key = w.personality.key;
  const name = personalityName(key);
  return <>
    <span class="in" style="animation-delay: .1s; margin-top: 18px; font-size: 20px; font-weight: 800">{t('Your watch personality')}</span>
    <div class="pop" style="animation-delay: .4s; position: relative; margin-top: 16px; padding: 22px 22px 20px; border-radius: 26px; background: #1e1e1e; color: #ffffff">
      <span aria-hidden="true" style={`position: absolute; right: 18px; top: 18px; width: 52px; height: 52px; border-radius: 16px; background: ${ACCENT}; color: #1e1e1e; display: flex; align-items: center; justify-content: center`}><PersonalityIcon kind={key} size={28} /></span>
      <span aria-hidden="true" style="float: right; width: 52px; height: 50px; margin-right: -4px" />
      <span style={`display: block; font-size: 12px; font-weight: 800; letter-spacing: 0.12em; text-transform: uppercase; color: ${ACCENT}`}>{t('You are')}</span>
      <h2 style={`margin: 6px 0 0; font-size: ${name.length > 19 ? 36 : 40}px; line-height: 0.95; font-weight: 900; letter-spacing: -0.03em; text-transform: uppercase`}>{name}</h2>
      <p style="clear: both; margin: 12px 0 0; font-size: 15px; line-height: 1.45; color: #c8c8c8">{personalityText(key)}</p>
    </div>
    <ul style="list-style: none; margin: 18px 0 0; padding: 0">
      {w.personality.stats.map((s, i) => {
        const line = statLine(s);
        return (
          <li key={s.key} class="in" style={`animation-delay: ${(1.1 + i * 0.12).toFixed(2)}s; display: flex; align-items: baseline; gap: 12px; padding: 12px 0; border-top: 2px solid rgba(30, 30, 30, 0.15)`}>
            <b style="flex: none; min-width: 126px; max-width: 58%; font-size: 24px; line-height: 1.1; font-weight: 900; letter-spacing: -0.02em">{line.big}</b>
            <span style="font-size: 14px; line-height: 1.35; font-weight: 600">{line.small}</span>
          </li>
        );
      })}
    </ul>
    <div style="flex: 1" />
    <div style="display: flex; align-items: flex-end; justify-content: flex-end; margin-right: -8px">
      <Say side="right" dark delay={1.9} style="margin-bottom: 84px; margin-right: -16px">{personalityMedu(key)}</Say>
      <Pop delay={1.6}><MeduAt mood="yay" size={108} label={t('Medu, cheering')} /></Pop>
    </div>
  </>;
}

// ── 9 · Summary: the share card ──

export function SummaryCard({ w, cardRef }: { w: Wrapped; cardRef?: (el: HTMLElement | null) => void }) {
  const brand = useStore(brandingStore).name || 'Seedy';
  const first = w.shows[0];
  const also = summaryAlso(w);
  const label = 'font-size: 11px; font-weight: 800; letter-spacing: 0.08em; text-transform: uppercase; opacity: 0.6';
  return (
    <div ref={cardRef} class="pop" role="img" aria-label={summaryLabel(w)} style={`animation-delay: .3s; position: relative; width: 342px; box-sizing: border-box; padding: 20px; border-radius: 28px; background: ${ACCENT}; color: #1e1e1e; overflow: hidden`}>
      <svg viewBox="0 0 342 844" width="382" height="844" style="position: absolute; left: -20px; top: 0; overflow: visible" aria-hidden="true">
        <path d="M-40 470 Q0 488 40 470 Q80 452 120 470 Q160 488 200 470 Q240 452 280 470 Q320 488 360 470 Q400 452 440 470" fill="none" stroke="#ffffff" stroke-opacity="0.25" stroke-width="20" stroke-linecap="round" />
        <path d="M-40 510 Q0 528 40 510 Q80 492 120 510 Q160 528 200 510 Q240 492 280 510 Q320 528 360 510 Q400 492 440 510" fill="none" stroke="#ffffff" stroke-opacity="0.25" stroke-width="20" stroke-linecap="round" />
      </svg>
      <div style="position: relative; display: flex; align-items: center; gap: 10px">
        <MeduProps mood="party" size={64} />
        <span style="display: flex; flex-direction: column">
          <span style="font-size: 12px; font-weight: 800; letter-spacing: 0.08em; text-transform: uppercase; opacity: 0.6">{t('Seedy Wrapped')}</span>
          <b style="font-size: 26px; line-height: 1; font-weight: 900; letter-spacing: -0.02em">{t('My {year}', { year: w.year })}</b>
        </span>
      </div>
      {first && (
        <div style="position: relative; height: 128px; margin-top: 14px; border-radius: 18px; overflow: hidden">
          <Wide item={first.item} seed={first.id} width={700} backdrop />
          <span style="position: absolute; left: 12px; bottom: 10px; max-width: calc(100% - 24px); box-sizing: border-box; padding: 3px 9px; border-radius: 999px; background: rgba(14, 14, 14, 0.8); color: #ffffff; font-size: 11px; font-weight: 800; overflow: hidden; text-overflow: ellipsis; white-space: nowrap">#1 · {first.name}</span>
        </div>
      )}
      <div style="position: relative; display: grid; grid-template-columns: 1.15fr 1fr; gap: 14px; margin-top: 16px">
        <div style="min-width: 0">
          {w.shows.length > 0 && <>
            <span style={label}>{t('Top shows')}</span>
            <ol style="list-style: none; margin: 6px 0 0; padding: 0; display: flex; flex-direction: column; gap: 5px">
              {w.shows.slice(0, 5).map((s, i) => (
                <li key={s.id} style="display: flex; align-items: baseline; gap: 8px; font-size: 14px; font-weight: 800; white-space: nowrap; min-width: 0">
                  <span style="flex: none; width: 12px; font-weight: 900; opacity: 0.55">{i + 1}</span>
                  <span style="overflow: hidden; text-overflow: ellipsis">{s.name}</span>
                </li>
              ))}
            </ol>
          </>}
        </div>
        <div style="display: flex; flex-direction: column; gap: 10px; min-width: 0">
          <div style="display: flex; flex-direction: column; gap: 1px">
            <span style={label}>{t('Hours')}</span>
            <b style="font-size: 30px; line-height: 1.05; font-weight: 900; letter-spacing: -0.02em">{formatNumber(hoursOf(w.watchMs))}</b>
          </div>
          {w.genres[0] && (
            <div style="display: flex; flex-direction: column; gap: 1px">
              <span style={label}>{t('Top genre')}</span>
              <b style="font-size: 18px; line-height: 1.05; font-weight: 900; letter-spacing: -0.02em">{genreName(w.genres[0])}</b>
            </div>
          )}
          {w.movie && (
            <div style="display: flex; flex-direction: column; gap: 1px">
              <span style={label}>{t('Top movie')}</span>
              <b style="font-size: 18px; line-height: 1.05; font-weight: 900; letter-spacing: -0.02em; overflow-wrap: anywhere">{w.movie.name}</b>
            </div>
          )}
        </div>
      </div>
      <div style="position: relative; display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 16px; padding: 12px 14px; border-radius: 16px; background: #1e1e1e; color: #ffffff">
        <span style="display: flex; flex-direction: column">
          <span style={`font-size: 10px; font-weight: 800; letter-spacing: 0.1em; text-transform: uppercase; color: ${ACCENT}`}>{t('Watch personality')}</span>
          <b style="font-size: 17px; font-weight: 900">{personalityName(w.personality.key)}</b>
        </span>
        <PersonalityIcon kind={w.personality.key} size={24} />
      </div>
      <div style="position: relative; display: flex; justify-content: space-between; gap: 10px; margin-top: 12px; font-size: 12px; font-weight: 800">
        <span>{also}</span>
        <span style="opacity: 0.6">{brand.toLowerCase()}</span>
      </div>
    </div>
  );
}

function Summary({ w, cardRef }: StoryProps) {
  return <>
    <span class="in" style="animation-delay: .05s; margin-top: 8px; font-size: 18px; font-weight: 900; color: #ffffff; text-align: center">{t('That’s your {year}. Thanks for watching with Medu.', { year: w.year })}</span>
    <div style="display: flex; justify-content: center; margin-top: 14px">
      <SummaryCard w={w} cardRef={cardRef} />
    </div>
  </>;
}
