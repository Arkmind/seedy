import { get, request, withApiKey } from '../../api/client';
import { getItems } from '../../api/jellyfin';
import { asBook, listAudiobooks } from '../../audio/api';
import type { BaseItem } from '../../api/types';

/* The reader's side of the Seedy API (BooksController): the book file, its length and chapters, comic pages, and
   this profile's place in each book. Jellyfin's own JSON (PascalCase) comes back from these too. */

export type BookFormat = 'epub' | 'pdf' | 'cbz' | 'cbr' | 'other';

export interface BookChapter { Title: string; Href: string; Page: number }
export interface BookInfo { Format: BookFormat; Pages: number; Words: number; Minutes: number; Chapters: BookChapter[] }


export const bookFileUrl = (id: string) => withApiKey(`/Seedy/Books/${id}/File`);
export const comicPageUrl = (id: string, index: number) => withApiKey(`/Seedy/Books/${id}/Pages/${index}`);
export { downloadUrl } from '../../api/client';

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

/* This profile's place in each book lives in the Seedy API (shared with the audiobook player). */
export {
  deleteReading, getReading, listReading, saveReading, saveReadingOnExit, type Reading, type ReadingPatch, type ReadingSettings
} from '../../api/seedy';

/* ───────── What a library item is ───────── */

export const isAudioBook = (item: Pick<BaseItem, 'Type'>) => item.Type === 'AudioBook';

export function formatOf(item: BaseItem): BookFormat | 'audio' {
  if (isAudioBook(item)) return 'audio';
  const path = item.Path ?? item.MediaSources?.[0]?.Path ?? '';
  const ext = (/\.([a-z0-9]+)$/i.exec(path)?.[1] ?? item.Container ?? '').toLowerCase();
  if (ext === 'epub' || ext === 'pdf' || ext === 'cbz' || ext === 'cbr') return ext;
  return 'other';
}

export const isComic = (item: BaseItem) => { const f = formatOf(item); return f === 'cbz' || f === 'cbr'; };

export const fileSize = (item: BaseItem): number | undefined => item.MediaSources?.[0]?.Size;

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

export { narratorOf } from '../../audio/api';

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
  'MediaSources', 'SeriesName', 'ProviderIds', 'Studios', 'Overview', 'Chapters', 'CanDownload'
];

/* Everything in a books library, books and audiobooks, in one go: the tabs sort and group it themselves. An audiobook
   in several files comes as one book, where its first file was. */
export async function loadLibraryItems(parentId: string, signal?: AbortSignal): Promise<BaseItem[]> {
  const [items, books] = await Promise.all([loadAll(parentId, signal), listAudiobooks(parentId, signal).catch(() => [])]);
  if (!books.length) return items;
  const byPart = new Map<string, BaseItem>();
  for (const b of books) {
    const book = asBook(b);
    for (const p of b.Parts) byPart.set(p.Id, book);
  }
  const shown = new Set<string>();
  return items.flatMap((it) => {
    const book = byPart.get(it.Id);
    if (!book) return [it];
    if (shown.has(book.Id)) return [];
    shown.add(book.Id);
    return [book];
  });
}

async function loadAll(parentId: string, signal?: AbortSignal): Promise<BaseItem[]> {
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
