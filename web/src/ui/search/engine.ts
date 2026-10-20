import { get, getSession } from '../../api/client';
import { getItems, getNextUp, getResume, type ItemsQuery } from '../../api/jellyfin';
import type { BaseItem, ItemsResult } from '../../api/types';
import { duration, episodeTag, plural, remainingSeconds, ticksToSeconds, timeLeft, yearRange } from '../../lib/format';
import { t } from '../../lib/i18n';

/* Search for the navigation bar and the Search page (Navigation bar board, "Typing a search").

   - Forgiving: accents don't matter (Jellyfin compares accent-free names, and so does the ranking here), and when
     the exact words find nothing a looser query runs and keeps names within a small typo distance.
   - Years, genres and actors work as search words: "thriller 2024" becomes the Thriller genre in 2024, "90s" a
     decade, and a name that matches a person also brings that person's titles.
   - Ranked by how close the match is, then by what the person watches (in progress, favourites, recently played). */

export type Size = 'dropdown' | 'phone' | 'page';
const LIMITS: Record<Size, { titles: number; episodes: number; people: number }> = {
  dropdown: { titles: 12, episodes: 6, people: 8 },
  phone: { titles: 16, episodes: 8, people: 8 },
  page: { titles: 60, episodes: 30, people: 30 }
};

const FIELDS = ['PrimaryImageAspectRatio', 'ProductionYear', 'Genres', 'ChildCount', 'RecursiveItemCount', 'OriginalTitle', 'DateCreated'];
const TITLE_TYPES = ['Movie', 'Series', 'BoxSet'];
const uid = () => getSession()?.userId ?? '';

/* ── Text ── */

/* Lower case, no accents, punctuation as spaces: the same clean form Jellyfin keeps for names. */
export function norm(s: string): string {
  return s.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
}

/* The parts of `text` that match `query`, for the accent-coloured highlight. Whole query first, else each word. */
export function marks(text: string, query: string): { t: string; m: boolean }[] {
  const q = norm(query);
  if (!q || !text) return [{ t: text, m: false }];
  // Normalised copy of the text with a map back to the original positions.
  let n = '';
  const at: number[] = [];
  let i = 0;
  for (const ch of text) {
    let c = ch.normalize('NFD').replace(/\p{M}+/gu, '').toLowerCase();
    if (!/^[\p{L}\p{N}]*$/u.test(c)) c = ' ';
    if (c === ' ' && n.endsWith(' ')) { i += ch.length; continue; }
    for (let k = 0; k < c.length; k++) { n += c[k]; at.push(i); }
    i += ch.length;
  }
  at.push(text.length);
  const ranges: [number, number][] = [];
  const add = (start: number, len: number) => {
    const end = start + len;
    ranges.push([at[start], end < at.length ? at[end] : text.length]);
  };
  const whole = findWord(n, q);
  if (whole >= 0) add(whole, q.length);
  else {
    for (const w of q.split(' ')) {
      if (w.length < 2) continue;
      const p = findWord(n, w);
      if (p >= 0) add(p, w.length);
    }
  }
  if (!ranges.length) return [{ t: text, m: false }];
  ranges.sort((a, b) => a[0] - b[0]);
  const out: { t: string; m: boolean }[] = [];
  let pos = 0;
  for (const [s, e] of ranges) {
    if (e <= pos) continue;
    const from = Math.max(s, pos);
    if (from > pos) out.push({ t: text.slice(pos, from), m: false });
    out.push({ t: text.slice(from, e), m: true });
    pos = e;
  }
  if (pos < text.length) out.push({ t: text.slice(pos), m: false });
  return out;
}

/* Prefer a match at the start of a word ("north" in "Glass Harbor North Pier", not inside "Northnorth"). */
function findWord(hay: string, needle: string): number {
  let p = hay.indexOf(needle);
  const first = p;
  while (p > 0 && hay[p - 1] !== ' ') p = hay.indexOf(needle, p + 1);
  return p >= 0 ? p : first;
}

/* Damerau–Levenshtein (optimal string alignment) distance, for typo tolerance. */
function distance(a: string, b: string): number {
  const m = a.length;
  const n = b.length;
  if (!m) return n;
  if (!n) return m;
  const d: number[][] = Array.from({ length: m + 1 }, (_, i) => [i, ...Array<number>(n).fill(0)]);
  for (let j = 1; j <= n; j++) d[0][j] = j;
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost);
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
    }
  }
  return d[m][n];
}

/* A mistyped query against a name: the fewest edits to any run of whole words in the name as long as the query
   (or the whole name). Small typos only: none under 4 letters, 1 up to 6, 2 up to 10, then 3. */
function typoDistance(q: string, name: string): number {
  const n = norm(name);
  if (!q || !n) return Infinity;
  const words = n.split(' ');
  const k = q.split(' ').length;
  let best = distance(q, n);
  for (let i = 0; i + k <= words.length; i++) best = Math.min(best, distance(q, words.slice(i, i + k).join(' ')));
  return best;
}
const typoAllowance = (q: string) => (q.length < 4 ? 0 : q.length < 7 ? 1 : q.length < 11 ? 2 : 3);

/* ── Genres (also the "Browse by genre" chips) ── */

export interface GenreInfo { name: string; count: number }
let genresP: Promise<GenreInfo[]> | null = null;
let genresUser = '';

export function loadGenres(): Promise<GenreInfo[]> {
  if (!genresP || genresUser !== uid()) {
    genresUser = uid();
    genresP = get<ItemsResult<BaseItem & { MovieCount?: number; SeriesCount?: number }>>('/Genres', {
      userId: uid(), includeItemTypes: 'Movie,Series', sortBy: 'SortName', fields: 'ItemCounts', enableImages: false
    })
      .then((r) => r.Items.map((g) => ({ name: g.Name, count: (g.MovieCount ?? 0) + (g.SeriesCount ?? 0) })))
      .catch(() => { genresP = null; return []; });
  }
  return genresP;
}

/* Words people type for a genre that the server may name differently. */
const GENRE_ALIASES: Record<string, string[]> = {
  'sci fi': ['science fiction', 'sci fi fantasy', 'sci fi'],
  scifi: ['science fiction', 'sci fi fantasy'],
  sf: ['science fiction', 'sci fi fantasy'],
  'science fiction': ['science fiction', 'sci fi fantasy'],
  fantasy: ['fantasy', 'sci fi fantasy'],
  action: ['action', 'action adventure'],
  adventure: ['adventure', 'action adventure'],
  war: ['war', 'war politics'],
  kids: ['kids', 'children', 'family'],
  children: ['children', 'kids', 'family'],
  cartoon: ['animation'],
  cartoons: ['animation'],
  animated: ['animation'],
  anime: ['anime', 'animation'],
  doc: ['documentary'],
  docs: ['documentary'],
  documentaries: ['documentary'],
  comedies: ['comedy'],
  romcom: ['romance', 'comedy'],
  'true crime': ['crime', 'documentary'],
  scary: ['horror'],
  musicals: ['musical', 'music']
};

export interface Parsed {
  /* What's left to match against names once years and genres are taken out, as typed. */
  rest: string;
  years: number[];
  /* One list per genre word; a title must carry a genre from every list. */
  genres: string[][];
}

export function parse(text: string, genres: GenreInfo[]): Parsed {
  let words = text.trim().split(/\s+/).filter(Boolean);
  const years: number[] = [];
  words = words.filter((w) => {
    const y = /^(19\d\d|20\d\d)$/.exec(w);
    if (y) { years.push(Number(y[1])); return false; }
    const dec = /^(?:(19|20)?(\d)0)'?s$/i.exec(w);
    if (dec) {
      const century = dec[1] ? Number(dec[1]) * 100 : Number(dec[2]) >= 3 ? 1900 : 2000;
      const start = century + Number(dec[2]) * 10;
      for (let k = 0; k < 10; k++) years.push(start + k);
      return false;
    }
    return true;
  });

  // Genre phrases, longest first, at word boundaries.
  const byNorm = new Map<string, string>();
  for (const g of genres) byNorm.set(norm(g.name), g.name);
  const phrases = new Map<string, string[]>();
  const put = (phrase: string, names: string[]) => {
    const present = names.map((n) => byNorm.get(n)).filter((n): n is string => !!n);
    if (present.length) phrases.set(phrase, [...new Set([...(phrases.get(phrase) ?? []), ...present])]);
  };
  for (const n of byNorm.keys()) {
    put(n, [n]);
    put(n + 's', [n]);
    if (n.endsWith('y')) put(n.slice(0, -1) + 'ies', [n]);
  }
  for (const [alias, targets] of Object.entries(GENRE_ALIASES)) put(alias, targets);
  const keys = [...phrases.keys()].sort((a, b) => b.split(' ').length - a.split(' ').length || b.length - a.length);

  const found: string[][] = [];
  const keep: string[] = [];
  const normed = words.map(norm);
  for (let i = 0; i < words.length;) {
    let hit = '';
    for (const k of keys) {
      const len = k.split(' ').length;
      if (normed.slice(i, i + len).join(' ') === k) { hit = k; break; }
    }
    if (hit) {
      found.push(phrases.get(hit)!);
      i += hit.split(' ').length;
    } else {
      keep.push(words[i]);
      i++;
    }
  }
  return { rest: keep.join(' '), years, genres: found };
}

/* ── Ranking ── */

export type Kind = 'movie' | 'show' | 'collection' | 'episode' | 'person';

export interface Hit {
  kind: Kind;
  item: BaseItem;
  score: number;
  /* The words to colour in its name (empty when it came in through a genre or year). */
  match: string;
}

export function kindOf(item: BaseItem): Kind {
  switch (item.Type) {
    case 'Series': return 'show';
    case 'BoxSet': return 'collection';
    case 'Episode': return 'episode';
    case 'Person': return 'person';
    default: return 'movie';
  }
}

function nameScore(q: string, item: BaseItem): number {
  if (!q) return 0;
  let best = 0;
  for (const raw of [item.Name, item.OriginalTitle]) {
    if (!raw) continue;
    const n = norm(raw);
    let s = 0;
    if (n === q) s = 100;
    else if (n.startsWith(q + ' ')) s = 90;
    else if (n.startsWith(q)) s = 85;
    else if ((' ' + n).includes(' ' + q)) s = 72;
    else if (n.includes(q)) s = 55;
    else if (q.split(' ').every((w) => (' ' + n).includes(' ' + w))) s = 52;
    else if (q.split(' ').every((w) => n.includes(w))) s = 48;
    if (s > best) best = s;
  }
  return best;
}

/* What the person watches counts: in progress, favourites, recently played. */
function watchBoost(item: BaseItem): number {
  const ud = item.UserData;
  if (!ud) return 0;
  let b = 0;
  if ((ud.PlaybackPositionTicks ?? 0) > 0) b += 10;
  if (item.Type === 'Series' && !ud.Played && (ud.PlayedPercentage ?? 0) > 0) b += 10;
  if (ud.IsFavorite) b += 6;
  if (ud.LastPlayedDate) {
    const days = (Date.now() - new Date(ud.LastPlayedDate).getTime()) / 86_400_000;
    b += days < 30 ? 6 : days < 365 ? 3 : 1;
  }
  return b;
}

const KIND_WEIGHT: Record<Kind, number> = { movie: 0, show: 0, collection: -4, episode: -8, person: -2 };

function hit(item: BaseItem, base: number, match: string): Hit {
  const kind = kindOf(item);
  return { kind, item, match, score: base + watchBoost(item) + KIND_WEIGHT[kind] };
}

/* ── Fetching ── */

export interface Results {
  q: string;
  parsed: Parsed;
  hits: Hit[];
  best?: Hit;
  titles: Hit[];
  episodes: Hit[];
  people: Hit[];
  total: number;
  /* Nothing matched the words exactly; these are close names. */
  loose: boolean;
}

const items = (q: ItemsQuery, signal: AbortSignal) =>
  getItems({ recursive: true, fields: FIELDS, enableTotalRecordCount: true, imageTypeLimit: 1, ...q }, signal)
    .catch((e: Error) => { if (e?.name === 'AbortError') throw e; return { Items: [], TotalRecordCount: 0 } as ItemsResult; });

const persons = (searchTerm: string, limit: number, signal: AbortSignal) =>
  get<ItemsResult>('/Persons', { userId: uid(), searchTerm, limit, enableImageTypes: 'Primary', imageTypeLimit: 1 }, signal)
    .catch((e: Error) => { if (e?.name === 'AbortError') throw e; return { Items: [], TotalRecordCount: 0 } as ItemsResult; });

const EMPTY: ItemsResult = { Items: [], TotalRecordCount: 0 };

/* A title must carry a genre from every genre word ("comedy drama" = both). */
const genreOk = (item: BaseItem, groups: string[][]) =>
  groups.every((g) => (item.Genres ?? []).some((x) => g.includes(x)));

/* Recent answers, so backspacing is instant; kept a minute (watched state and resume points change). */
const cache = new Map<string, { at: number; r: Results }>();
const TTL = 60_000;

export function cached(q: string, size: Size): Results | undefined {
  const key = size + '|' + uid() + '|' + q.trim().replace(/\s+/g, ' ').toLowerCase();
  const c = cache.get(key);
  if (c && Date.now() - c.at < TTL) return c.r;
  if (c) cache.delete(key);
  return undefined;
}

export async function search(raw: string, size: Size, signal: AbortSignal): Promise<Results> {
  const text = raw.trim().replace(/\s+/g, ' ');
  const key = size + '|' + uid() + '|' + text.toLowerCase();
  const hitCache = cached(text, size);
  if (hitCache) return hitCache;

  const L = LIMITS[size];
  const parsed = parse(text, await loadGenres());
  const filters = parsed.years.length > 0 || parsed.genres.length > 0;
  const words = filters ? parsed.rest : text;
  const nq = norm(text);
  const nw = norm(words);
  const genreList = [...new Set(parsed.genres.flat())];

  const [byName, eps, people, filtered] = await Promise.all([
    items({ searchTerm: text, includeItemTypes: TITLE_TYPES, limit: L.titles }, signal),
    items({ searchTerm: text, includeItemTypes: ['Episode'], limit: L.episodes }, signal),
    nw.length >= 2 ? persons(words, L.people, signal) : Promise.resolve(EMPTY),
    filters
      ? items({
          searchTerm: parsed.rest || undefined, includeItemTypes: ['Movie', 'Series'], genres: genreList.length ? genreList : undefined,
          years: parsed.years.length ? parsed.years : undefined, limit: L.titles * 2, sortBy: ['CommunityRating', 'SortName'], sortOrder: 'Descending'
        }, signal)
      : Promise.resolve(EMPTY)
  ]);

  const titles = new Map<string, Hit>();
  const addTitle = (h: Hit) => { const prev = titles.get(h.item.Id); if (!prev || prev.score < h.score) titles.set(h.item.Id, h); };
  for (const it of byName.Items) addTitle(hit(it, nameScore(nq, it) || 45, text));
  const fItems = filtered.Items.filter((it) => genreOk(it, parsed.genres));
  for (const it of fItems) addTitle(hit(it, nw ? (nameScore(nw, it) || 45) + 4 : 40, parsed.rest));

  /* Actors as search words: a person whose name holds every word brings their titles (within the genre and year). */
  const peopleHits = people.Items.map((p) => hit(p, nameScore(nw, p) || 45, words));
  const strong = peopleHits.filter((h) => h.score >= 50).sort((a, b) => b.score - a.score)[0];
  let personTotal = 0;
  if (strong && (filters || titles.size < 4)) {
    const r = await items({
      personIds: [strong.item.Id], includeItemTypes: ['Movie', 'Series'], genres: genreList.length ? genreList : undefined,
      years: parsed.years.length ? parsed.years : undefined, limit: L.titles, sortBy: ['PremiereDate', 'SortName'], sortOrder: 'Descending'
    }, signal);
    for (const it of r.Items.filter((x) => genreOk(x, parsed.genres))) addTitle(hit(it, 38, ''));
    personTotal = r.TotalRecordCount;
  }

  let titleHits = [...titles.values()];
  let epHits = eps.Items.map((it) => hit(it, nameScore(nq, it) || 45, text));
  let personHits = peopleHits;
  let loose = false;

  /* Never a dead end for a typo: try looser pieces of the words and keep names that are close. */
  if (!titleHits.length && !epHits.length && !personHits.length && nq.length >= 3 && !filters) {
    const found = await looseSearch(nq, L.titles, signal);
    titleHits = found.titles;
    personHits = found.people;
    loose = titleHits.length + personHits.length > 0;
  }

  const byScore = (a: Hit, b: Hit) => b.score - a.score || a.item.Name.localeCompare(b.item.Name);
  titleHits.sort(byScore);
  epHits.sort(byScore);
  personHits.sort(byScore);
  const all = [...titleHits, ...epHits, ...personHits].sort(byScore);
  const best = [...titleHits, ...epHits].sort(byScore)[0];

  const titlesTotal = loose ? titleHits.length : Math.max(titleHits.length, byName.TotalRecordCount, fItems.length < filtered.Items.length ? 0 : filtered.TotalRecordCount, personTotal);
  const total = titlesTotal + (loose ? 0 : eps.TotalRecordCount) + (loose ? personHits.length : Math.max(people.TotalRecordCount, personHits.length));
  const res: Results = { q: text, parsed, hits: all, best, titles: titleHits, episodes: epHits, people: personHits, total, loose };
  cache.set(key, { at: Date.now(), r: res });
  if (cache.size > 40) cache.delete(cache.keys().next().value as string);
  return res;
}

async function looseSearch(nq: string, limit: number, signal: AbortSignal): Promise<{ titles: Hit[]; people: Hit[] }> {
  const allow = typoAllowance(nq);
  if (!allow) return { titles: [], people: [] };
  const words = nq.split(' ').filter((w) => w.length >= 3).sort((a, b) => b.length - a.length);
  const main = words[0] ?? nq;
  const tries: string[] = [];
  if (words.length > 1) tries.push(...words.slice(0, 2));
  for (const n of [main.length - 1, main.length - 2, Math.ceil(main.length / 2)]) if (n >= 3 && n < main.length) tries.push(main.slice(0, n));
  // A typo near the start: the end of the word still matches.
  if (main.length >= 6) tries.push(main.slice(-Math.ceil(main.length / 2)));
  const near = (it: BaseItem) => Math.min(typoDistance(nq, it.Name), it.OriginalTitle ? typoDistance(nq, it.OriginalTitle) : Infinity);
  const seen = new Set<string>();
  for (const t of [...new Set(tries)].slice(0, 4)) {
    const [ti, pe] = await Promise.all([
      items({ searchTerm: t, includeItemTypes: TITLE_TYPES, limit: 40 }, signal),
      persons(t, 20, signal)
    ]);
    const keep = (it: BaseItem) => !seen.has(it.Id) && near(it) <= allow;
    const score = (it: BaseItem) => 30 * (1 - near(it) / Math.max(4, nq.length));
    const titles = ti.Items.filter(keep).map((it) => ({ ...hit(it, 0, ''), score: score(it) + watchBoost(it) }));
    const people = pe.Items.filter(keep).map((it) => ({ ...hit(it, 0, ''), score: score(it) }));
    if (titles.length || people.length) return { titles: titles.slice(0, limit), people };
    [...ti.Items, ...pe.Items].forEach((it) => seen.add(it.Id));
  }
  return { titles: [], people: [] };
}

/* The best match and up to five more, grouped as on the board: Movies, Shows, Collections, Episodes, People.
   Every group that has a result gets its top one first, then the rest go by score. */
export const GROUPS: { kind: Kind; label: string }[] = [
  { kind: 'movie', label: 'Movies' },
  { kind: 'show', label: 'Shows' },
  { kind: 'collection', label: 'Collections' },
  { kind: 'episode', label: 'Episodes' },
  { kind: 'person', label: 'People' }
];

export function pick(r: Results, max = 6): { best?: Hit; groups: { kind: Kind; label: string; hits: Hit[] }[] } {
  const rest = r.hits.filter((h) => h !== r.best);
  const slots = max - (r.best ? 1 : 0);
  const chosen = new Set<Hit>();
  for (const g of GROUPS) {
    if (chosen.size >= slots) break;
    const top = rest.find((h) => h.kind === g.kind);
    if (top) chosen.add(top);
  }
  for (const h of rest) {
    if (chosen.size >= slots) break;
    chosen.add(h);
  }
  return {
    best: r.best,
    groups: GROUPS.map((g) => ({ ...g, hits: rest.filter((h) => chosen.has(h) && h.kind === g.kind) })).filter((g) => g.hits.length)
  };
}

/* ── Copy ── */

/* "2021 · Drama · 1 h 49 min", "2019 · Thriller · not watched", "S1 E2 · 48 min". */
export function rowMeta(item: BaseItem): string {
  const ud = item.UserData;
  const inProgress = (ud?.PlaybackPositionTicks ?? 0) > 0 && !!item.RunTimeTicks;
  const run = item.RunTimeTicks ? duration(ticksToSeconds(item.RunTimeTicks)) : '';
  switch (item.Type) {
    case 'Series': {
      const seasons = item.ChildCount ? plural(item.ChildCount, 'season') : '';
      return join(yearRange(item), seasons);
    }
    case 'BoxSet': return join('Collection', item.ChildCount ? plural(item.ChildCount, 'title') : '');
    case 'Episode': return join(episodeTag(item), inProgress ? timeLeft(remainingSeconds(item)) : run);
    case 'Person': return '';
    default:
      return join(
        item.ProductionYear ? String(item.ProductionYear) : '',
        item.Genres?.[0],
        inProgress ? timeLeft(remainingSeconds(item)) : ud?.Played ? run : ud ? 'not watched' : run
      );
  }
}

export const join = (...parts: (string | undefined | null | false)[]) => parts.filter(Boolean).join(' · ');

/* The name a row shows: episodes carry their show ("Glass Harbor · North Pier"). */
export function rowTitle(item: BaseItem): { lead: string; name: string } {
  if (item.Type === 'Episode' && item.SeriesName) return { lead: item.SeriesName + ' · ', name: item.Name };
  return { lead: '', name: item.Name };
}

/* ── The best match's play button ── */

export interface BestPlay {
  /* "You're on S3 E2" for a show in progress. */
  where?: string;
  /** The episode tag in "where", e.g. "S3 E2". */
  tag?: string;
  label: 'Resume' | 'Play';
  target?: BaseItem;
}

export async function bestPlay(item: BaseItem): Promise<BestPlay> {
  if (item.Type === 'Series') {
    const resume = await getResume(1, item.Id).catch(() => null);
    const r = resume?.Items[0];
    if (r) return { where: t("You're on {ep}", { ep: episodeTag(r) }), tag: episodeTag(r), label: 'Resume', target: r };
    const started = !!item.UserData?.Played || (item.UserData?.PlayedPercentage ?? 0) > 0;
    if (started) {
      const next = await getNextUp(1, item.Id).catch(() => null);
      const n = next?.Items[0];
      if (n) return { where: t("You're on {ep}", { ep: episodeTag(n) }), tag: episodeTag(n), label: 'Play', target: n };
    }
    return { label: 'Play' };
  }
  if ((item.UserData?.PlaybackPositionTicks ?? 0) > 0 && !item.UserData?.Played) return { label: 'Resume', target: item };
  return { label: 'Play', target: item.Type === 'BoxSet' ? undefined : item };
}

/* "Show · 2024–2026 · 3 seasons · You're on S3 E2", "Movie · 2021 · Drama · 1 h 49 min". */
export function bestMeta(item: BaseItem, play?: BestPlay): string {
  switch (item.Type) {
    case 'Series': return join(t('Show'), rowMeta(item), play?.where);
    case 'BoxSet': return rowMeta(item);
    case 'Episode': return join(t('Episode'), rowMeta(item));
    default: {
      const ud = item.UserData;
      const inProgress = (ud?.PlaybackPositionTicks ?? 0) > 0 && !!item.RunTimeTicks && !ud?.Played;
      return join(t('Movie'), item.ProductionYear ? String(item.ProductionYear) : '', item.Genres?.[0],
        inProgress ? timeLeft(remainingSeconds(item)) : item.RunTimeTicks ? duration(ticksToSeconds(item.RunTimeTicks)) : '');
    }
  }
}

/* Phone list: "Show · Resume S3 E2", "Movie · 2021", "Episode · S1 E2", "Actor". */
export function phoneMeta(h: Hit, resumeTag?: string): string {
  const it = h.item;
  switch (h.kind) {
    case 'show': return join(t('Show'), resumeTag ? t('Resume {ep}', { ep: resumeTag }) : yearRange(it));
    case 'collection': return join(t('Collection'), it.ChildCount ? plural(it.ChildCount, 'title') : '');
    case 'episode': return join(t('Episode'), episodeTag(it));
    case 'person': return t('Person');
    default: return join(t('Movie'), it.ProductionYear ? String(it.ProductionYear) : '');
  }
}

/* People: "Actor · 4 titles in your library" ("1 episode" for someone only in episodes). One small request per
   person shown, two for guest stars. */
export interface PersonInfo { role: string; count: number; unit: 'title' | 'episode' }
const personInfo = new Map<string, Promise<PersonInfo>>();
const ROLES: Record<string, string> = { Actor: 'Actor', GuestStar: 'Actor', Director: 'Director', Writer: 'Writer', Producer: 'Producer', Composer: 'Composer', Creator: 'Creator' };

export function personMeta(person: BaseItem): Promise<PersonInfo> {
  let p = personInfo.get(person.Id);
  if (!p) {
    const look = (types: string[]) => getItems({ personIds: [person.Id], includeItemTypes: types, recursive: true, limit: 1, fields: ['People'], enableTotalRecordCount: true, enableImageTypes: ['Primary'] });
    const role = (r: ItemsResult) => ROLES[r.Items[0]?.People?.find((x) => x.Id === person.Id)?.Type ?? ''] ?? 'Person';
    p = look(['Movie', 'Series'])
      .then(async (r): Promise<PersonInfo> => {
        if (r.TotalRecordCount > 0) return { role: role(r), count: r.TotalRecordCount, unit: 'title' };
        const e = await look(['Episode']);
        return { role: role(e), count: e.TotalRecordCount, unit: 'episode' };
      })
      .catch((): PersonInfo => { personInfo.delete(person.Id); return { role: 'Person', count: 0, unit: 'title' }; });
    personInfo.set(person.Id, p);
  }
  return p;
}

export const personLine = (info?: PersonInfo) =>
  info ? join(t(info.role), info.count ? t('{count} in your library', { count: plural(info.count, info.unit) }) : '') : '';
