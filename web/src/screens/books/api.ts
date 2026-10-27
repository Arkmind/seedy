import { SERVER_URL, del, get, getSession, post, request } from '../../api/client';
import { getItems } from '../../api/jellyfin';
import type { BaseItem } from '../../api/types';

/* The reader's side of the Seedy API (BooksController): the book file, its length and chapters, comic pages, and
   this profile's place in each book. Jellyfin's own JSON (PascalCase) comes back from these too. */

export type BookFormat = 'epub' | 'pdf' | 'cbz' | 'cbr' | 'other';

export interface BookChapter { Title: string; Href: string; Page: number }
export interface BookInfo { Format: BookFormat; Pages: number; Words: number; Minutes: number; Chapters: BookChapter[] }

/* Reader settings saved with the place in each book (any JSON the reader likes; see reader/settings.ts). */
export type ReadingSettings = Record<string, unknown>;

export interface Reading {
  ItemId: string;
  /* An EPUB CFI, or a page index (from 0) for comics. */
  Locator?: string;
  Percent: number;
  Chapter?: string;
  ChapterIndex?: number;
  ChapterCount?: number;
  Finished: boolean;
  Settings?: ReadingSettings | null;
  UpdatedAt?: string;
}

export type ReadingPatch = Omit<Reading, 'ItemId' | 'UpdatedAt'>;

const token = () => getSession()?.token ?? '';

export const bookFileUrl = (id: string) => `${SERVER_URL}/Seedy/Books/${id}/File?ApiKey=${encodeURIComponent(token())}`;
export const comicPageUrl = (id: string, index: number) => `${SERVER_URL}/Seedy/Books/${id}/Pages/${index}?ApiKey=${encodeURIComponent(token())}`;
export const downloadUrl = (id: string) => `${SERVER_URL}/Items/${id}/Download?ApiKey=${encodeURIComponent(token())}`;

/* Book info rarely changes; one request per book per page load. */
const infoCache = new Map<string, Promise<BookInfo>>();
export function getBookInfo(id: string): Promise<BookInfo> {
  let p = infoCache.get(id);
  if (!p) {
    p = get<BookInfo>(`/Seedy/Books/${id}/Info`);
    p.catch(() => infoCache.delete(id));
    infoCache.set(id, p);
  }
  return p;
}

export async function getBookFile(id: string, signal?: AbortSignal): Promise<ArrayBuffer> {
  const res = await request<Response>('GET', `/Seedy/Books/${id}/File`, { raw: true, signal });
  return res.arrayBuffer();
}

export const listReading = (finished = false, limit = 40) => get<Reading[]>('/Seedy/Reading', { finished, limit });

/* 204 (never opened) comes back as undefined. */
export const getReading = (id: string) => get<Reading | undefined>(`/Seedy/Reading/${id}`).then((r) => (r && typeof r === 'object' ? r : undefined));

export const saveReading = (id: string, patch: ReadingPatch) => post<Reading>(`/Seedy/Reading/${id}`, patch);

/* Fire-and-forget save that survives the page closing. */
export function saveReadingOnExit(id: string, patch: ReadingPatch) {
  const session = getSession();
  if (!session) return;
  try {
    void fetch(`${SERVER_URL}/Seedy/Reading/${id}?ApiKey=${encodeURIComponent(session.token)}`, {
      method: 'POST',
      keepalive: true,
      headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
      body: JSON.stringify(patch)
    });
  } catch { /* the debounced save already ran */ }
}

export const deleteReading = (id: string) => del<void>(`/Seedy/Reading/${id}`);

/* ───────── What a library item is ───────── */

export const isAudioBook = (item: Pick<BaseItem, 'Type'>) => (item.Type as string) === 'AudioBook';

type WithPath = BaseItem & { Path?: string; Container?: string; MediaSources?: { Path?: string; Container?: string; Size?: number }[] };

export function formatOf(item: BaseItem): BookFormat | 'audio' {
  if (isAudioBook(item)) return 'audio';
  const it = item as WithPath;
  const path = it.Path ?? it.MediaSources?.[0]?.Path ?? '';
  const ext = (/\.([a-z0-9]+)$/i.exec(path)?.[1] ?? it.Container ?? '').toLowerCase();
  if (ext === 'epub' || ext === 'pdf' || ext === 'cbz' || ext === 'cbr') return ext;
  return 'other';
}

export const isComic = (item: BaseItem) => { const f = formatOf(item); return f === 'cbz' || f === 'cbr'; };

export const fileSize = (item: BaseItem): number | undefined => (item as WithPath).MediaSources?.[0]?.Size;

/* Who wrote it: Jellyfin keeps book authors as people (Author, or Writer from ComicInfo), audiobook authors as
   album artists or artists. */
export function authorsOf(item: BaseItem): { Name: string; Id?: string }[] {
  const people = item.People ?? [];
  const by = (type: string) => people.filter((p) => p.Type === type);
  const list = by('Author').length ? by('Author') : by('Writer').length ? by('Writer') : [];
  if (list.length) return list.map((p) => ({ Name: p.Name, Id: p.Id }));
  const it = item as BaseItem & { AlbumArtists?: { Name: string; Id: string }[]; AlbumArtist?: string; Artists?: string[] };
  if (it.AlbumArtists?.length) return it.AlbumArtists;
  if (it.ArtistItems?.length) return it.ArtistItems;
  if (it.Artists?.length) return it.Artists.map((Name) => ({ Name }));
  if (it.AlbumArtist) return [{ Name: it.AlbumArtist }];
  return [];
}

/* "Hale & Ito" for two, "Hale, Ito & Moss" for more. */
export function authorLine(item: BaseItem): string {
  const names = authorsOf(item).map((a) => a.Name);
  if (names.length <= 1) return names[0] ?? '';
  return names.slice(0, -1).join(', ') + ' & ' + names[names.length - 1];
}

/* Audiobooks keep their narrator as the composer (or a Narrator person when a plugin set one). */
export const narratorOf = (item: BaseItem) => (item.People ?? []).find((p) => p.Type === 'Narrator' || p.Role === 'Narrator')?.Name
  ?? (item.People ?? []).find((p) => p.Type === 'Composer')?.Name
  ?? (item as BaseItem & { Composers?: { Name: string }[] }).Composers?.[0]?.Name;

/* The series a book is part of. Jellyfin also names a "series" after the folder a book sits in (the author's), with no
   number: that isn't one. */
export function seriesOf(item: BaseItem): string | undefined {
  const name = item.SeriesName?.trim();
  if (!name || item.IndexNumber === undefined || item.IndexNumber === null) return undefined;
  if (authorsOf(item).some((a) => normalize(a.Name) === normalize(name))) return undefined;
  return name;
}

export const workKey = (item: BaseItem) => `${normalize(item.Name)}|${normalize(authorLine(item))}`;
export const normalize = (s?: string) => (s ?? '').toLowerCase().replace(/^(the|a|an)\s+/, '').replace(/[^\p{L}\p{N}]+/gu, '');

/* ───────── Library loading ───────── */

export const BOOK_FIELDS = [
  'PrimaryImageAspectRatio', 'ProductionYear', 'PremiereDate', 'Genres', 'DateCreated', 'SortName', 'People', 'Path',
  'MediaSources', 'SeriesName', 'ProviderIds', 'Studios', 'Overview', 'Chapters'
];

/* Everything in a books library, books and audiobooks, in one go: the tabs sort and group it themselves. */
export async function loadLibraryItems(parentId: string, signal?: AbortSignal): Promise<BaseItem[]> {
  const out: BaseItem[] = [];
  const page = 500;
  for (let start = 0; start < 20000; start += page) {
    const r = await getItems({
      parentId, includeItemTypes: ['Book', 'AudioBook'], recursive: true, startIndex: start, limit: page, fields: BOOK_FIELDS,
      sortBy: ['DateCreated', 'SortName'], sortOrder: 'Descending', enableImageTypes: ['Primary'], enableTotalRecordCount: true
    }, signal);
    out.push(...r.Items);
    if (out.length >= r.TotalRecordCount || r.Items.length < page) break;
  }
  return out;
}
