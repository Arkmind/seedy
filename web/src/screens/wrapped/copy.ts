import { formatNumber, locale, t, tc, tn } from '../../lib/i18n';
import type { MeduMood } from '../../ui/medu';
import type { Wrapped, WrappedStat } from './api';

/* Every sentence Seedy Wrapped says, written from the year's numbers. The Android apps say the same English (and so
   get the same French from i18n/fr/wrapped.ts): keep both in step when changing a line here. */

export const hoursOf = (ms: number) => Math.max(0, Math.round(ms / 3_600_000));

// ── Cover ──

export const coverHint = (stories: number) =>
  tn(stories, '{n} story · about a minute · tap to skip ahead', '{n} stories · about a minute · tap to skip ahead');

// ── Hours ──

export interface Chip { big: string; small: string }

/* The three chips under the hours: days back to back (or plays, under a day), against last year, place on the server. */
export function hourChips(w: Wrapped): Chip[] {
  const chips: Chip[] = [];
  const days = Math.round(w.watchMs / 86_400_000);
  chips.push(days >= 1
    ? { big: tn(days, '{n} day', '{n} days'), small: t('back to back') }
    : { big: tn(w.plays, '{n} play', '{n} plays'), small: t('this year') });
  if (w.prevWatchMs && w.prevWatchMs > 0) {
    const pct = Math.round(((w.watchMs - w.prevWatchMs) / w.prevWatchMs) * 100);
    chips.push({ big: `${pct >= 0 ? '+' : '−'}${formatNumber(Math.abs(pct))}%`, small: t('vs {year}', { year: w.year - 1 }) });
  }
  if (w.rank) chips.push({ big: `#${formatNumber(w.rank)}`, small: t('on your server') });
  return chips;
}

// ── Genres ──

const GENRE_NAMES: Record<string, () => string> = {
  thriller: () => tc('Thriller', 'genre'), drama: () => tc('Drama', 'genre'), comedy: () => tc('Comedy', 'genre'), scifi: () => tc('Sci-fi', 'genre'),
  documentary: () => tc('Documentary', 'genre'), animation: () => tc('Animation', 'genre'), action: () => tc('Action', 'genre'), adventure: () => tc('Adventure', 'genre'),
  horror: () => tc('Horror', 'genre'), crime: () => tc('Crime', 'genre'), romance: () => tc('Romance', 'genre'), fantasy: () => tc('Fantasy', 'genre'),
  mystery: () => tc('Mystery', 'genre'), family: () => tc('Family', 'genre'), history: () => tc('History', 'genre'), war: () => tc('War', 'genre'),
  western: () => tc('Western', 'genre'), music: () => tc('Music', 'genre'), kids: () => tc('Kids', 'genre'), reality: () => tc('Reality', 'genre')
};

/* A known genre in the app's language ("Sci-Fi & Fantasy" → "Sci-fi"); others as the library names them. */
export const genreName = (g: { name: string; key?: string | null }) => (g.key && GENRE_NAMES[g.key] ? GENRE_NAMES[g.key]() : g.name);

/* "a third", "half"…: a share the way people say it. */
export function shareWords(pct: number): string {
  if (pct >= 45 && pct <= 55) return t('half');
  if (pct > 55) return t('most');
  if (pct >= 30) return t('a third');
  if (pct >= 22) return t('a quarter');
  if (pct >= 17) return t('a fifth');
  return `${formatNumber(pct)}%`;
}

/* The line under "Your year in genres", for the top genre. */
export function genreLead(w: Wrapped): string {
  const top = w.genres[0];
  if (!top) return '';
  const share = shareWords(top.pct);
  switch (top.key) {
    case 'thriller': return t('You went dark this year: {share} of your nights were thrillers.', { share });
    case 'drama': return t('You came for the feelings: {share} of your time went to drama.', { share });
    case 'comedy': return t('You kept it light: {share} of your time was comedy.', { share });
    case 'scifi': return t('Your head was in the stars: {share} of your time was sci-fi.', { share });
    case 'documentary': return t('Always learning: {share} of your time went to documentaries.', { share });
    case 'animation': return t('Drawn to it: {share} of your time was animation.', { share });
    case 'action': return t('Strap in: {share} of your time was action.', { share });
    case 'horror': return t('Lights off, volume up: {share} of your time was horror.', { share });
    case 'crime': return t('Case open: {share} of your time went to crime.', { share });
    case 'romance': return t('Hopeless romantic: {share} of your time was romance.', { share });
    case 'fantasy': return t('Dragons welcome: {share} of your time was fantasy.', { share });
    case 'mystery': return t('Whodunnit? {share} of your time went to mysteries.', { share });
    default: return t('{genre} won your year: {share} of everything you watched.', { genre: genreName(top), share });
  }
}

export const genreOther = (w: Wrapped) => (w.otherPct > 0 ? t('The other {pct}% was a bit of everything.', { pct: formatNumber(w.otherPct) }) : '');

export function genreMedu(w: Wrapped): string {
  switch (w.genres[0]?.key) {
    case 'thriller': return t('That many thrillers… should I be worried?');
    case 'drama': return t('I cried too. Twice.');
    case 'comedy': return t('You laughed. I heard.');
    case 'scifi': return t('Take me with you next time.');
    case 'documentary': return t('Fun fact: jellyfish have no brain. I’m doing fine.');
    case 'horror': return t('I watched through my tentacles.');
    case 'animation': return t('Cartoons are for everyone.');
    case 'crime': return t('It wasn’t me. I was at home.');
    case 'romance': return t('Aww. Pass the tissues.');
    case 'fantasy': return t('Is that a dragon? Can I keep it?');
    case 'mystery': return t('I knew it was the butler.');
    default: return t('Good taste, if you ask me.');
  }
}

// ── Top shows ──

/* "51 h", or minutes under an hour. */
export const showTime = (ms: number) => (ms >= 3_600_000 ? t('{n} h', { n: formatNumber(hoursOf(ms)) }) : duration(ms));

export const showMeta = (s: { episodes: number; watchMs: number }) => `${tn(s.episodes, '{n} episode', '{n} episodes')} · ${showTime(s.watchMs)}`;

export function showsFootnote(w: Wrapped): string {
  const f = w.seasonsFinished;
  const s = w.showsStarted;
  if (f > 0 && s > 0) {
    return `${tn(f, 'You finished {n} season', 'You finished {n} seasons')} ${tn(s, 'and started {n} new show.', 'and started {n} new shows.')}`;
  }
  if (f > 0) return tn(f, 'You finished {n} season.', 'You finished {n} seasons.');
  if (s > 0) return tn(s, 'You started {n} new show.', 'You started {n} new shows.');
  return '';
}

export const showsMedu = (w: Wrapped) =>
  w.shows[0] ? tn(w.shows[0].episodes, '{n} episode of {show}. I counted.', '{n} episodes of {show}. I counted.', { show: w.shows[0].name }) : '';

// ── The movie ──

export const movieTitle = (w: Wrapped) => ((w.movie?.views ?? 0) >= 2 ? t('The one you couldn’t let go') : t('Your movie of the year'));

export function duration(ms: number): string {
  const min = Math.max(1, Math.round(ms / 60_000));
  const h = Math.floor(min / 60);
  const m = min % 60;
  if (h === 0) return t('{m} min', { m: formatNumber(m) });
  if (m === 0) return t('{h} h', { h: formatNumber(h) });
  return t('{h} h {m} min', { h: formatNumber(h), m: formatNumber(m) });
}

const day = (iso: string, opts: Intl.DateTimeFormatOptions) => {
  const [y, mo, d] = iso.slice(0, 10).split('-').map(Number);
  try { return new Intl.DateTimeFormat(locale(), { ...opts, timeZone: 'UTC' }).format(Date.UTC(y, mo - 1, d)); } catch { return iso.slice(0, 10); }
};

export function movieMeta(w: Wrapped): string {
  const m = w.movie;
  if (!m) return '';
  const last = m.dates[m.dates.length - 1];
  const all = t('{time} all in', { time: duration(m.watchMs) });
  return last ? `${all} · ${t('last time on {date}', { date: day(last, { day: 'numeric', month: 'long' }) })}` : all;
}

/* One ticket per view (at most six, the latest), named by its month. */
export const movieTickets = (w: Wrapped) =>
  (w.movie?.dates ?? []).slice(-6).map((d) => day(d, { month: 'short' }).replace('.', ''));

export const movieMedu = (w: Wrapped) => ((w.movie?.views ?? 0) >= 2 ? t('Same, honestly.') : t('Good pick.'));

// ── The binge ──

export function bingeLine(w: Wrapped): string {
  const b = w.binge;
  if (!b) return '';
  const season = b.season == null ? b.name
    : b.seasonTo ? t('{show}, seasons {from}–{to}', { show: b.name, from: b.season, to: b.seasonTo })
    : t('{show}, season {n}', { show: b.name, n: b.season });
  return `${season} · ${day(b.start, { weekday: 'short', day: 'numeric', month: 'long' })}`;
}

export const timeOf = (localIso: string) => localIso.slice(11, 16);

export const bingeEpisode = (w: Wrapped) => {
  const b = w.binge;
  if (!b) return '';
  return b.lastSeason != null && b.lastEpisode != null ? `${b.name} · ${t('S{s} E{e}', { s: b.lastSeason, e: b.lastEpisode })}` : b.name;
};

export function bingeText(w: Wrapped): string {
  const s = w.still;
  if (s.asked > 0) {
    const asked = tn(s.asked, 'We asked “Still watching?” {n} time this year.', 'We asked “Still watching?” {n} times this year.');
    const yes = s.yes >= s.asked
      ? (s.asked === 1 ? t('You said yes.') : t('You said yes every single time.'))
      : tn(s.yes, 'You said yes {n} time.', 'You said yes {n} times.');
    return `${asked} ${yes}`;
  }
  const b = w.binge;
  if (!b) return '';
  const ms = Date.parse(b.end + ':00Z') - Date.parse(b.start + ':00Z');
  return t('{time} from the first episode to the last.', { time: duration(Math.max(60_000, ms)) });
}

// ── The clock ──

export function clockTitle(w: Wrapped): [string, string] {
  switch (w.clock?.kind) {
    case 'morning': return [t('You’re an'), t('early bird')];
    case 'afternoon': return [t('You’re an'), t('afternoon person')];
    case 'evening': return [t('You’re a'), t('prime-time regular')];
    default: return [t('You’re a'), t('night owl')];
  }
}

/* "{pct} of your watching happened after 22:00." with the percentage on its own, to set in bold. */
export function clockShare(w: Wrapped): string {
  switch (w.clock?.kind) {
    case 'morning': return t('{pct} of your watching happened before noon.');
    case 'afternoon': return t('{pct} of your watching happened between noon and 18:00.');
    case 'evening': return t('{pct} of your watching happened between 18:00 and 22:00.');
    default: return t('{pct} of your watching happened after 22:00.');
  }
}

export function clockDay(w: Wrapped): string {
  const nights = w.clock?.kind === 'night' || w.clock?.kind === 'evening';
  switch (w.clock?.day) {
    case 1: return nights ? t('Mondays were the big nights.') : t('Mondays were the big days.');
    case 2: return nights ? t('Tuesdays were the big nights.') : t('Tuesdays were the big days.');
    case 3: return nights ? t('Wednesdays were the big nights.') : t('Wednesdays were the big days.');
    case 4: return nights ? t('Thursdays were the big nights.') : t('Thursdays were the big days.');
    case 5: return nights ? t('Fridays were the big nights.') : t('Fridays were the big days.');
    case 6: return nights ? t('Saturdays were the big nights.') : t('Saturdays were the big days.');
    default: return nights ? t('Sundays were the big nights.') : t('Sundays were the big days.');
  }
}

export function clockMedu(w: Wrapped): string {
  switch (w.clock?.kind) {
    case 'morning': return t('Coffee first? No? Okay.');
    case 'afternoon': return t('A matinee a day keeps the boredom away.');
    case 'evening': return t('Dinner, then a show. Classic.');
    default: return t('Some of us need our sleep, you know.');
  }
}

export const clockMood = (w: Wrapped): MeduMood => (w.clock?.kind === 'night' ? 'sleepy' : w.clock?.kind === 'evening' ? 'popcorn' : 'wave');

// ── The personality ──

export function personalityName(key: string): string {
  switch (key) {
    case 'sprinter': return t('The Season Sprinter');
    case 'rewatcher': return t('The Rewatcher');
    case 'cinephile': return t('The Movie Buff');
    case 'explorer': return t('The Explorer');
    case 'regular': return t('The Daily Regular');
    case 'slowburner': return t('The Slow Burner');
    case 'weekender': return t('The Weekend Warrior');
    default: return t('The Easygoing Viewer');
  }
}

export function personalityText(key: string): string {
  switch (key) {
    case 'sprinter': return t('Once you start a season, nothing gets in the way. Not sleep, not intros, not Medu.');
    case 'rewatcher': return t('Why gamble on something new when you know what you love? Comfort, on repeat.');
    case 'cinephile': return t('Lights down, phone away. For you, a night in means a proper film.');
    case 'explorer': return t('A bit of everything, from everywhere. You never stayed in one place for long.');
    case 'regular': return t('Rain or shine, there was something on. Seedy was part of your day.');
    case 'slowburner': return t('You take your time and savour every episode. Good things are worth the wait.');
    case 'weekender': return t('Weekdays are for everything else. Weekends are for the couch.');
    default: return t('No marathons, no pressure. You watch what you like, when you like.');
  }
}

export function personalityMedu(key: string): string {
  switch (key) {
    case 'sprinter': return t('Teach me your ways.');
    case 'rewatcher': return t('Again? Again!');
    case 'cinephile': return t('Popcorn’s on me.');
    case 'explorer': return t('Where to next?');
    case 'regular': return t('Same time tomorrow?');
    case 'slowburner': return t('No spoilers, I promise.');
    case 'weekender': return t('See you Saturday!');
    default: return t('Quality over quantity.');
  }
}

/* One number under the personality: the big part and what it means. */
export function statLine(s: WrappedStat): Chip {
  const v = s.value;
  const n = Math.round(v);
  switch (s.key) {
    case 'seasonDays': return { big: tn(v, '{n} day', '{n} days'), small: t('to finish a season, on average') };
    case 'introsSkipped': {
      const saved = s.extra ?? 0;
      const hours = Math.round(saved / 3_600_000);
      const small = hours >= 1
        ? tn(hours, 'skipped. That’s {n} hour you got back', 'skipped. That’s {n} hours you got back')
        : tn(Math.max(1, Math.round(saved / 60_000)), 'skipped. That’s {n} minute you got back', 'skipped. That’s {n} minutes you got back');
      return { big: tn(n, '{n} intro', '{n} intros'), small };
    }
    case 'gapSeconds': return {
      big: v < 60 ? tn(n, '{n} second', '{n} seconds') : tn(Math.round(v / 60), '{n} minute', '{n} minutes'),
      small: t('between episodes, on average')
    };
    case 'seasonsFinished': return { big: tn(n, '{n} season', '{n} seasons'), small: t('finished, start to end') };
    case 'movies': return { big: tn(n, '{n} movie', '{n} movies'), small: t('watched to the end') };
    case 'titles': return { big: tn(n, '{n} title', '{n} titles'), small: t('different movies and shows') };
    case 'genres': return { big: tn(n, '{n} genre', '{n} genres'), small: t('explored along the way') };
    case 'rewatches': return { big: tn(n, '{n} rewatch', '{n} rewatches'), small: t('of things you’d already seen') };
    case 'streak': return { big: tn(n, '{n} day', '{n} days'), small: t('in a row, your longest streak') };
    case 'activeDays': return { big: tn(n, '{n} day', '{n} days'), small: t('with something on') };
    case 'bigDay': {
      const h = Math.round(v / 360_000) / 10;
      return { big: tn(h, '{n} hour', '{n} hours'), small: t('on your biggest day') };
    }
    case 'weekendPct': return { big: `${formatNumber(n)}%`, small: t('of your watching on weekends') };
    case 'showsStarted': return { big: tn(n, '{n} new show', '{n} new shows'), small: t('started from episode one') };
    default: return { big: formatNumber(n), small: '' };
  }
}

// ── Summary ──

export function summaryAlso(w: Wrapped): string {
  const parts: string[] = [];
  const music = hoursOf(w.musicMs);
  if (music > 0) parts.push(t('{n} h of music', { n: formatNumber(music) }));
  if (w.books > 0) parts.push(tn(w.books, '{n} book', '{n} books'));
  return parts.length ? t('Also {list}', { list: parts.join(' · ') }) : '';
}

/* What a screen reader hears for the share card. */
export function summaryLabel(w: Wrapped): string {
  const bits = [tn(hoursOf(w.watchMs), '{n} hour', '{n} hours')];
  if (w.shows[0]) bits.push(t('top show {name}', { name: w.shows[0].name }));
  if (w.genres[0]) bits.push(t('top genre {name}', { name: genreName(w.genres[0]) }));
  bits.push(personalityName(w.personality.key));
  return t('My {year} on Seedy: {list}', { year: w.year, list: bits.join(', ') });
}

// ── Home banner ──

export const bannerText = (watchMs: number, stories: number) =>
  t('{hours} in {stories}. Medu took notes.', { hours: tn(hoursOf(watchMs), '{n} hour', '{n} hours'), stories: tn(stories, '{n} story', '{n} stories') });
