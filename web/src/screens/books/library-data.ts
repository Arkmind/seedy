import { useMemo, useState } from 'preact/hooks';
import type { BaseItem } from '../../api/types';
import { mergePlace, partsOf } from '../../audio/api';
import { t } from '../../lib/i18n';
import { useAsync } from '../../lib/store';
import { authorLine, formatOf, isAudioBook, isComic, listReading, loadLibraryItems, normalize, seriesOf, workKey, type Reading } from './api';
import type { SeriesGroup } from './parts';

/* A books library's items, sorted into books, comics, audiobooks and series, with this profile's reading places;
   the library and a book's page both read it. */

/* The reader's formats, best first. */
export const PREFERRED = ['epub', 'cbz', 'pdf', 'cbr', 'other'];
export const time = (s?: string) => (s ? new Date(s).getTime() || 0 : 0);
export const sortKey = (it: BaseItem) => (it.SortName ?? it.Name ?? '').toLowerCase();

export interface BooksData {
  all: BaseItem[];
  books: BaseItem[];
  comics: BaseItem[];
  audio: BaseItem[];
  reading: Map<string, Reading>;
  series: SeriesGroup[];
}

/* Series with at least two volumes here. */
export function groupSeries(items: BaseItem[]): SeriesGroup[] {
  const map = new Map<string, BaseItem[]>();
  for (const it of items) {
    const name = seriesOf(it);
    if (!name || isAudioBook(it)) continue;
    const key = normalize(name);
    const list = map.get(key) ?? [];
    list.push(it);
    map.set(key, list);
  }
  return Array.from(map, ([key, vols]) => {
    // One cover per volume: the EPUB and the PDF of one book count once.
    const seen = new Set<string>();
    const volumes = vols
      .sort((a, b) => (a.IndexNumber ?? 9999) - (b.IndexNumber ?? 9999) || sortKey(a).localeCompare(sortKey(b))
        || PREFERRED.indexOf(formatOf(a)) - PREFERRED.indexOf(formatOf(b)))
      .filter((v) => { const k = `${v.IndexNumber ?? ''}|${normalize(v.Name)}`; if (seen.has(k)) return false; seen.add(k); return true; });
    const authors = Array.from(new Set(volumes.map(authorLine).filter(Boolean)));
    return {
      name: seriesOf(volumes[0])!,
      key,
      author: authors.length === 1 ? authors[0] : authors.length > 1 ? t('Various authors') : '',
      volumes,
      read: volumes.filter((v) => v.UserData?.Played).length,
      read_at: Math.max(0, ...volumes.map((v) => time(v.UserData?.LastPlayedDate))),
      latest: Math.max(...volumes.map((v) => time(v.DateCreated)))
    };
  }).filter((g) => g.volumes.length > 1);
}

/* One card per work: the EPUB and the PDF of a book show as the book (its page lists both), the reader's format first. */
function oneEach(items: BaseItem[]): BaseItem[] {
  const best = new Map<string, BaseItem>();
  for (const it of items) {
    const key = workKey(it) + '|' + (it.IndexNumber ?? '');
    const cur = best.get(key);
    if (!cur || PREFERRED.indexOf(formatOf(it)) < PREFERRED.indexOf(formatOf(cur))) best.set(key, it);
  }
  const keep = new Set(Array.from(best.values()).map((i) => i.Id));
  return items.filter((i) => keep.has(i.Id));
}

export function useBooksData(viewId: string) {
  const [fresh, setFresh] = useState(0);
  const items = useAsync(() => (viewId ? libraryItems(viewId, fresh > 0) : Promise.resolve(undefined)), [viewId, fresh]);
  const reading = useAsync(() => (viewId ? listReading(true, 200).catch(() => [] as Reading[]) : Promise.resolve([] as Reading[])), [viewId, fresh]);
  const data = useMemo<BooksData | undefined>(() => {
    if (!items.data) return undefined;
    const map = new Map((reading.data ?? []).map((r) => [r.ItemId, r]));
    // Audiobooks in one file take Seedy's place in them (books in several files have it from the server).
    const all = items.data.map((i) => (isAudioBook(i) && !partsOf(i) ? mergePlace(i, map.get(i.Id)) : i));
    return {
      all,
      books: oneEach(all.filter((i) => !isAudioBook(i) && !isComic(i))),
      comics: oneEach(all.filter((i) => !isAudioBook(i) && isComic(i))),
      audio: all.filter(isAudioBook),
      reading: map,
      series: groupSeries(all)
    };
  }, [items.data, reading.data]);
  return { data, error: items.error, loading: items.loading, reload: () => setFresh((n) => n + 1) };
}

/* The library's items stay for a minute, so going from the library to a book and back doesn't load them again. */
const itemsCache = new Map<string, { at: number; items: Promise<BaseItem[]> }>();
function libraryItems(viewId: string, force: boolean): Promise<BaseItem[]> {
  const hit = itemsCache.get(viewId);
  if (hit && !force && Date.now() - hit.at < 60_000) return hit.items;
  const items = loadLibraryItems(viewId);
  itemsCache.set(viewId, { at: Date.now(), items });
  items.catch(() => itemsCache.delete(viewId));
  return items;
}
