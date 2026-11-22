import { formatNumber, locale, t, tc, tn } from '../../lib/i18n';
import type { MeduMood } from '../../ui/medu';
import type { Wrapped, WrappedStat } from './api';

/* Every sentence Seedy Wrapped says, written from the year's numbers. The Android apps say the same English (and so
   get the same French from i18n/fr/wrapped.ts): keep both in step when changing a line here. Medu's lines follow the
   board "Wrapped · Medu's lines": the first row whose condition matches, with the mood to show. */

export const hoursOf = (ms: number) => Math.max(0, Math.round(ms / 3_600_000));

/* What Medu says on a story, and how she looks saying it. */
export interface MeduLine { text: string; mood: MeduMood }

const SPELLED: (() => string)[] = [
  () => tc('Zero', 'start'), () => tc('One', 'start'), () => tc('Two', 'start'), () => tc('Three', 'start'), () => tc('Four', 'start'),
  () => tc('Five', 'start'), () => tc('Six', 'start'), () => tc('Seven', 'start'), () => tc('Eight', 'start'), () => tc('Nine', 'start')
];

/* A number that starts a sentence: spelled out under ten ("Nine in a row?"), in figures from ten. */
export const startNumber = (n: number) => (n >= 0 && n < 10 ? SPELLED[n]() : formatNumber(n));

// ── Cover ──

/* Each story stays 16 s: twelve take about three minutes. */
export const STORY_SECONDS = 16;

export function coverHint(stories: number): string {
  const minutes = Math.max(1, Math.round((stories * STORY_SECONDS) / 60));
  return [tn(stories, '{n} story', '{n} stories'), tn(minutes, 'about a minute', 'about {n} minutes'), t('tap to skip ahead')].join(' · ');
}

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

export function hoursMedu(w: Wrapped): MeduLine {
  const hours = hoursOf(w.watchMs);
  if (hours > 1000) return { text: t('I made popcorn for every single one of them.'), mood: 'popcorn' };
  if (hours >= 200) return { text: t('That’s a lot of popcorn. Not complaining.'), mood: 'popcorn' };
  return { text: t('Quality over quantity. I respect that.'), mood: 'idle' };
}

// ── Shows or movies? ──

/* Which side won the year, by time: shows, unless movies took longer. */
export const loverShows = (w: Wrapped) => (w.showMs ?? 0) >= (w.movieMs ?? 0);

const one = (n: number) => {
  try { return new Intl.PluralRules(locale()).select(n) === 'one'; } catch { return n === 1; }
};

/* "shows" or "movies" after a big number, in the number's form. */
export const loverUnit = (n: number, shows: boolean) => tc(shows ? (one(n) ? 'show' : 'shows') : (one(n) ? 'movie' : 'movies'), 'unit');

/* "1 h 52" (or "48 min"): a movie's average length. */
export function averageTime(ms: number): string {
  const min = Math.max(1, Math.round(ms / 60_000));
  const h = Math.floor(min / 60);
  return h ? t('{h} h {mm}', { h: formatNumber(h), mm: String(min % 60).padStart(2, '0') }) : t('{m} min', { m: formatNumber(min) });
}

export const loverTitle = (w: Wrapped): [string, string] => [t('You’re a'), loverShows(w) ? t('TV show lover') : t('movie lover')];

export function loverChips(w: Wrapped): string[] {
  const hours = (ms?: number) => tn(hoursOf(ms ?? 0), '{n} hour', '{n} hours');
  if (loverShows(w)) {
    return [
      tn(w.episodes ?? 0, '{n} episode', '{n} episodes'),
      hours(w.showMs),
      (w.showsFinished ?? 0) > 0 ? tn(w.showsFinished!, '{n} show finished', '{n} shows finished') : ''
    ].filter(Boolean);
  }
  const played = w.moviesPlayed ?? 0;
  return [
    hours(w.movieMs),
    played > 0 ? t('{time} on average', { time: averageTime((w.movieMs ?? 0) / played) }) : '',
    // English says "23 rewatched" either way; French agrees with the number (revu, revus).
    (w.moviesRewatched ?? 0) > 0 ? tc('{n} rewatched', one(w.moviesRewatched!) ? 'one' : 'other', { n: formatNumber(w.moviesRewatched!) }) : ''
  ].filter(Boolean);
}

/* The winner's share of the time, 0–100. */
export function loverPct(w: Wrapped): number {
  const shows = w.showMs ?? 0;
  const movies = w.movieMs ?? 0;
  const all = shows + movies;
  return all ? Math.round(((loverShows(w) ? shows : movies) * 100) / all) : 100;
}

/* The other side, on the band: its count and what it took. */
export function loverOther(w: Wrapped): { count: number; line: string } {
  if (loverShows(w)) {
    const n = w.moviesPlayed ?? 0;
    return {
      count: n,
      line: n > 0 ? `${tn(hoursOf(w.movieMs ?? 0), '{n} hour', '{n} hours')} · ${t('{time} on average', { time: averageTime((w.movieMs ?? 0) / n) })}` : t('Not one movie this year.')
    };
  }
  const n = w.showsWatched ?? 0;
  return {
    count: n,
    line: n > 0 ? `${tn(w.episodes ?? 0, '{n} episode', '{n} episodes')} · ${tn(hoursOf(w.showMs ?? 0), '{n} hour', '{n} hours')}` : t('Not one show this year.')
  };
}

export function loverMedu(w: Wrapped): MeduLine {
  if (loverPct(w) <= 55) return { text: t('Shows or movies? Why choose.'), mood: 'idle' };
  return loverShows(w) ? { text: t('Me too. One more episode?'), mood: 'love' } : { text: t('Me too. Another movie tonight?'), mood: 'love' };
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

export function genreMedu(w: Wrapped): MeduLine {
  switch (w.genres[0]?.key) {
    case 'thriller': case 'crime': return { text: t('That many thrillers… should I be worried?'), mood: 'search' };
    case 'comedy': return { text: t('You laughed a lot this year. I could hear you.'), mood: 'yay' };
    case 'horror': return { text: t('I watched those through my tentacles.'), mood: 'oops' };
    case 'documentary': return { text: t('You know so much now. Teach me?'), mood: 'think' };
    case 'romance': return { text: t('Okay, I cried too.'), mood: 'love' };
    case 'animation': case 'family': case 'kids': return { text: t('Same. Cartoons are the best.'), mood: 'yay' };
    case 'drama': return { text: t('I cried too. Twice.'), mood: 'sad' };
    case 'scifi': return { text: t('Take me with you next time.'), mood: 'think' };
    case 'fantasy': return { text: t('Is that a dragon? Can I keep it?'), mood: 'think' };
    case 'mystery': return { text: t('I knew it was the butler.'), mood: 'think' };
    default: return { text: t('Good taste, if you ask me.'), mood: 'idle' };
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

export function showsMedu(w: Wrapped): MeduLine {
  const top = w.shows[0];
  const text = top ? tn(top.episodes, '{n} episode of {show}. I counted.', '{n} episodes of {show}. I counted.', { show: top.name, n: startNumber(top.episodes) }) : '';
  return { text, mood: 'think' };
}

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

export function movieMedu(w: Wrapped): MeduLine {
  const views = w.movie?.views ?? 0;
  if (views < 2) return { text: t('Good pick. I’d watch it again.'), mood: 'idle' };
  const vars = { n: startNumber(views) };
  return w.movie?.genres?.some((g) => g === 'drama' || g === 'romance')
    ? { text: t('{n} times, and I still cry at the end.', vars), mood: 'sad' }
    : { text: t('{n} times? You must know it by heart.', vars), mood: 'love' };
}

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

export function bingeMedu(w: Wrapped): MeduLine {
  const n = w.binge?.episodes ?? 0;
  return n >= 5
    ? { text: t('{n} in a row? I had to unplug.', { n: startNumber(n) }), mood: 'oops' }
    : { text: t('Only {n} in a row. Very civilised.', { n: formatNumber(n) }), mood: 'idle' };
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

export function clockMedu(w: Wrapped): MeduLine {
  switch (w.clock?.kind) {
    case 'morning': return { text: t('An episode with breakfast? Fancy.'), mood: 'idle' };
    case 'afternoon': return { text: t('Lunch breaks well spent.'), mood: 'wave' };
    case 'evening': return { text: t('Dinner, then a show. Classic.'), mood: 'popcorn' };
    default: return { text: t('Some of us need our sleep, you know.'), mood: 'sleepy' };
  }
}

// ── Your requests ──

/* "14 min", "2 h 5 min" or "3 days": how long the quickest request took to land. */
export function arrivalTime(ms: number): string {
  if (ms < 86_400_000) return duration(ms);
  const days = Math.round(ms / 86_400_000);
  return tn(days, '{n} day', '{n} days');
}

/* The one that landed soonest: "Glass Harbor, season 2". */
export function arrivalName(f: { name: string; season?: number | null; seasonTo?: number | null }): string {
  if (f.season == null) return f.name;
  return f.seasonTo ? t('{show}, seasons {from}–{to}', { show: f.name, from: f.season, to: f.seasonTo }) : t('{show}, season {n}', { show: f.name, n: f.season });
}

export function requestsMedu(w: Wrapped): MeduLine {
  return (w.requests?.count ?? 0) >= 10
    ? { text: t('Fetched every single one. You’re welcome.'), mood: 'wave' }
    : { text: t('Short list, great taste.'), mood: 'idle' };
}

// ── The oddest request ──

/* "★ 3.9": TMDB's rating, one decimal. */
export const oddRating = (rating: number) => `★ ${formatNumber(rating, { minimumFractionDigits: 1, maximumFractionDigits: 1 })}`;

export function oddestMedu(w: Wrapped): MeduLine {
  const o = w.oddest;
  if (!o || o.rating < 5) return { text: t('I have questions.'), mood: 'think' };
  if (o.year != null && o.year < 1970) return { text: t('Older than your parents. Respect.'), mood: 'love' };
  return { text: tn(o.votes, 'You and {n} other person. Very exclusive.', 'You and {n} other people. Very exclusive.'), mood: 'yay' };
}

// ── The personality ──

export function personalityName(key: string): string {
  switch (key) {
    case 'sprinter': return t('The Season Sprinter');
    case 'completionist': return t('The Completionist');
    case 'rewatcher': return t('The Comfort Rewatcher');
    case 'cinephile': return t('The Movie Buff');
    case 'explorer': return t('The Explorer');
    case 'regular': return t('The Daily Regular');
    case 'slowburner': return t('The Slow Savourer');
    case 'weekender': return t('The Weekend Warrior');
    default: return t('The Easygoing Viewer');
  }
}

export function personalityText(key: string): string {
  switch (key) {
    case 'sprinter': return t('Once you start a season, nothing gets in the way. Not sleep, not intros, not Medu.');
    case 'completionist': return t('You finish what you start. Every season, every finale, right to the last episode.');
    case 'rewatcher': return t('Why gamble on something new when you know what you love? Comfort, on repeat.');
    case 'cinephile': return t('Lights down, phone away. For you, a night in means a proper film.');
    case 'explorer': return t('A bit of everything, from everywhere. You never stayed in one place for long.');
    case 'regular': return t('Rain or shine, there was something on. Seedy was part of your day.');
    case 'slowburner': return t('You take your time and savour every episode. Good things are worth the wait.');
    case 'weekender': return t('Weekdays are for everything else. Weekends are for the couch.');
    default: return t('No marathons, no pressure. You watch what you like, when you like.');
  }
}

export function personalityMedu(key: string): MeduLine {
  switch (key) {
    case 'sprinter': return { text: t('Teach me your ways.'), mood: 'yay' };
    case 'completionist': return { text: t('Not a single episode left behind.'), mood: 'yay' };
    case 'explorer': return { text: t('So many new shows. Where to next?'), mood: 'wave' };
    case 'rewatcher': return { text: t('Old favourites are the best favourites.'), mood: 'love' };
    case 'slowburner': return { text: t('No rush. Good things take time.'), mood: 'sleepy' };
    case 'cinephile': return { text: t('Popcorn’s on me.'), mood: 'popcorn' };
    case 'regular': return { text: t('Same time tomorrow?'), mood: 'wave' };
    case 'weekender': return { text: t('See you Saturday!'), mood: 'yay' };
    default: return { text: t('Quality over quantity.'), mood: 'idle' };
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
    case 'showsFinished': return { big: tn(n, '{n} show', '{n} shows'), small: t('watched to the very last episode') };
    default: return { big: formatNumber(n), small: '' };
  }
}

// ── Summary ──

export function summaryAlso(w: Wrapped): string {
  const parts: string[] = [];
  const music = hoursOf(w.musicMs);
  if (music > 0) parts.push(t('{n} h of music', { n: formatNumber(music) }));
  if (w.books > 0) parts.push(tn(w.books, '{n} book', '{n} books'));
  if ((w.requests?.count ?? 0) > 0) parts.push(tn(w.requests!.count, '{n} request', '{n} requests'));
  return parts.length ? t('Also {list}', { list: parts.join(' · ') }) : '';
}

/* Under the hours on the share card: "38 shows · 141 movies". */
export function summaryCounts(w: Wrapped): string {
  const parts: string[] = [];
  if ((w.showsWatched ?? 0) > 0) parts.push(tn(w.showsWatched!, '{n} show', '{n} shows'));
  if ((w.moviesPlayed ?? 0) > 0) parts.push(tn(w.moviesPlayed!, '{n} movie', '{n} movies'));
  return parts.join(' · ');
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
