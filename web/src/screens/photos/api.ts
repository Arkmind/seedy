import { SERVER_URL, get, getSession } from '../../api/client';
import { getItems, type ItemsQuery } from '../../api/jellyfin';
import type { BaseItem, ItemsResult } from '../../api/types';
import { formatNumber, getLang, locale, t, tn } from '../../lib/i18n';

/* Photos libraries (Photos boards). Jellyfin 12 keeps photos in "Home videos and photos" libraries (the old photos
   type still shows up on servers that were upgraded): Photo and Video items, in PhotoAlbum folders (a folder with
   photos directly in it) and plain folders. A photo's DTO carries what its EXIF says: date taken (PremiereDate, also
   DateCreated), size, camera, exposure and GPS. */

export const PHOTO_LIBRARIES = new Set(['photos', 'homevideos']);
export const isPhotoLibrary = (ct?: string) => !!ct && PHOTO_LIBRARIES.has(ct);

/* What a photos library holds. */
export const MEDIA_TYPES = ['Photo', 'Video'];
export const FOLDER_TYPES = ['PhotoAlbum', 'Folder'];

export type PhotoItem = BaseItem & {
  Album?: string;
  CameraMake?: string;
  CameraModel?: string;
  ExposureTime?: number;
  FocalLength?: number;
  Aperture?: number;
  IsoSpeedRating?: number;
  Latitude?: number;
  Longitude?: number;
  Path?: string;
  ImageBlurHashes?: Record<string, Record<string, string>>;
};

export const PHOTO_FIELDS = ['DateCreated', 'PrimaryImageAspectRatio', 'Path', 'ParentId', 'MediaSources', 'Width', 'Height'];

const uid = () => getSession()?.userId ?? '';
const token = () => getSession()?.token ?? '';

export const isVideo = (it: BaseItem) => it.Type === 'Video' || it.MediaType === 'Video' || it.Type === 'Movie' || it.Type === 'MusicVideo';
export const isFolder = (it: BaseItem) => it.Type === 'PhotoAlbum' || it.Type === 'Folder';

/* Width over height; photos say so in Width/Height, videos in their video stream. */
export function ratioOf(it: BaseItem): number {
  let w = it.Width, h = it.Height;
  if ((!w || !h) && it.MediaStreams) {
    const v = it.MediaStreams.find((s) => s.Type === 'Video');
    w = v?.Width; h = v?.Height;
  }
  if ((!w || !h) && it.MediaSources?.[0]?.MediaStreams) {
    const v = it.MediaSources[0].MediaStreams.find((s) => s.Type === 'Video');
    w = v?.Width; h = v?.Height;
  }
  const r = w && h ? w / h : it.PrimaryImageAspectRatio ?? 1.5;
  // Panoramas and slivers stay readable in a row.
  return Math.min(3, Math.max(0.5, r));
}

/* When it was taken. EXIF dates carry no time zone and Jellyfin stores them as UTC: read them back as the camera's clock. */
export function takenAt(it: BaseItem): Date | undefined {
  const s = it.PremiereDate ?? it.DateCreated;
  if (!s) return undefined;
  const d = new Date(s);
  if (Number.isNaN(d.getTime())) return undefined;
  if (!it.PremiereDate) return d;
  return new Date(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate(), d.getUTCHours(), d.getUTCMinutes(), d.getUTCSeconds());
}

/* ───────── Images ───────── */

const dpr = () => (typeof window !== 'undefined' ? Math.min(2, window.devicePixelRatio || 1) : 1);

/* A tile or a thumbnail: the photo scaled to cover a box of this height (or width), never past its own size. */
export function thumbUrl(it: BaseItem, height: number): string | undefined {
  const tag = it.ImageTags?.Primary;
  if (!tag) return undefined;
  const h = Math.round(height * dpr());
  const w = Math.round(h * ratioOf(it));
  return `${SERVER_URL}/Items/${it.Id}/Images/Primary?tag=${tag}&fillWidth=${w}&fillHeight=${h}&quality=85`;
}

/* The viewer: the photo at the size of the screen; zoomed in, at its full size (Jellyfin turns HEIC and the rest into
   something the browser shows, and keeps both in its image cache). */
export function screenUrl(it: BaseItem, full = false): string | undefined {
  const tag = it.ImageTags?.Primary;
  if (!tag) return undefined;
  if (full && it.Width) return `${SERVER_URL}/Items/${it.Id}/Images/Primary?tag=${tag}&maxWidth=${it.Width}&quality=95`;
  const w = Math.round(Math.min(it.Width || 4096, (typeof window !== 'undefined' ? window.screen.width : 1920) * dpr()));
  const h = Math.round(Math.min(it.Height || 4096, (typeof window !== 'undefined' ? window.screen.height : 1080) * dpr()));
  return `${SERVER_URL}/Items/${it.Id}/Images/Primary?tag=${tag}&maxWidth=${w}&maxHeight=${h}&quality=90`;
}

/* The original file, as stored (needs the profile's download permission). */
export const downloadUrl = (id: string) => `${SERVER_URL}/Items/${id}/Download?ApiKey=${encodeURIComponent(token())}`;

/* The file's size and type, from one byte of it. */
const fileInfoCache = new Map<string, Promise<{ size?: number; type?: string }>>();
export function fileInfo(id: string): Promise<{ size?: number; type?: string }> {
  let hit = fileInfoCache.get(id);
  if (!hit) {
    hit = fetch(`${SERVER_URL}/Items/${id}/File?ApiKey=${encodeURIComponent(token())}`, { headers: { Range: 'bytes=0-0' } }).then((r) => {
      const total = /\/(\d+)$/.exec(r.headers.get('Content-Range') ?? '')?.[1] ?? (r.status === 200 ? r.headers.get('Content-Length') : null);
      r.body?.cancel().catch(() => undefined);
      return { size: total ? Number(total) : undefined, type: r.headers.get('Content-Type') ?? undefined };
    }).catch(() => ({}));
    fileInfoCache.set(id, hit);
  }
  return hit;
}

/* ───────── Words ───────── */

export function countsLabel(photos: number, videos: number): string {
  const parts: string[] = [];
  if (photos || !videos) parts.push(tn(photos, '{n} photo', '{n} photos'));
  if (videos) parts.push(tn(videos, '{n} video', '{n} videos'));
  return parts.join(', ');
}

export function bytesLabel(n?: number): string {
  if (!n) return '';
  // French writes octets: "18,4 Mo".
  const units = getLang() === 'fr' ? ['o', 'ko', 'Mo', 'Go', 'To'] : ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  let v = n;
  while (v >= 1000 && i < units.length - 1) { v /= 1000; i++; }
  return `${formatNumber(v >= 100 || i === 0 ? Math.round(v) : Math.round(v * 10) / 10)} ${units[i]}`;
}

/* "October 2026". */
export function monthYear(d: Date): string {
  try {
    const s = new Intl.DateTimeFormat(locale(), { month: 'long', year: 'numeric' }).format(d);
    return s.charAt(0).toLocaleUpperCase() + s.slice(1);
  } catch { return `${d.getMonth() + 1}/${d.getFullYear()}`; }
}

/* "Sun 9 August 2026". */
export function longDay(d: Date): string {
  try {
    const s = new Intl.DateTimeFormat(locale(), { weekday: 'short', day: 'numeric', month: 'long', year: 'numeric' }).format(d).replace(/,/g, '');
    return s.charAt(0).toLocaleUpperCase() + s.slice(1);
  } catch { return d.toDateString(); }
}

/* "9 to 16 August 2026", "28 July to 3 August 2026", "30 December 2025 to 2 January 2026". */
export function dateRange(a?: Date, b?: Date): string {
  if (!a || !b) return '';
  const [from, to] = a <= b ? [a, b] : [b, a];
  const fmt = (d: Date, o: Intl.DateTimeFormatOptions) => { try { return new Intl.DateTimeFormat(locale(), o).format(d); } catch { return d.toDateString(); } };
  if (from.toDateString() === to.toDateString()) return fmt(from, { day: 'numeric', month: 'long', year: 'numeric' });
  if (from.getFullYear() !== to.getFullYear())
    return t('{from} to {to}', { from: fmt(from, { day: 'numeric', month: 'long', year: 'numeric' }), to: fmt(to, { day: 'numeric', month: 'long', year: 'numeric' }) });
  if (from.getMonth() !== to.getMonth())
    return t('{from} to {to}', { from: fmt(from, { day: 'numeric', month: 'long' }), to: fmt(to, { day: 'numeric', month: 'long', year: 'numeric' }) });
  return t('{from} to {to}', { from: String(from.getDate()), to: fmt(to, { day: 'numeric', month: 'long', year: 'numeric' }) });
}

/* "1 year ago", "3 years ago". */
export const yearsAgo = (n: number) => tn(n, '{n} year ago', '{n} years ago');

/* "23 mm · f/2.8 · 1/250 s · ISO 160". */
export function exposureLine(it: PhotoItem): string {
  const parts: string[] = [];
  if (it.FocalLength) parts.push(`${Math.round(it.FocalLength)} mm`);
  if (it.Aperture) parts.push(`f/${Number(it.Aperture.toFixed(1))}`);
  if (it.ExposureTime) parts.push(it.ExposureTime >= 1 ? `${Number(it.ExposureTime.toFixed(1))} s` : `1/${Math.round(1 / it.ExposureTime)} s`);
  if (it.IsoSpeedRating) parts.push(`ISO ${it.IsoSpeedRating}`);
  return parts.join(' · ');
}

/* "Fujifilm X100VI": the model usually repeats the make ("NIKON D4"), else both. */
export function cameraName(it: PhotoItem): string {
  const make = (it.CameraMake ?? '').replace(/\s+(CORPORATION|Corporation|Camera AG|CORP\.?|Co\.,? ?Ltd\.?|IMAGING CORP\.?|OPTICAL CO\.,LTD)$/i, '').trim();
  const model = (it.CameraModel ?? '').trim();
  if (!model) return make;
  if (!make || model.toLowerCase().startsWith(make.toLowerCase().split(' ')[0])) return tidyCase(model);
  return `${tidyCase(make)} ${model}`;
}
const tidyCase = (s: string) => s.split(' ').map((w) => (w.length > 3 && w === w.toUpperCase() && /^[A-Z]+$/.test(w) ? w.charAt(0) + w.slice(1).toLowerCase() : w)).join(' ');

/* "38.7105° N, 9.1335° W". */
export function coordsLabel(lat: number, lon: number): string {
  return `${Math.abs(lat).toFixed(4)}° ${lat >= 0 ? 'N' : 'S'}, ${Math.abs(lon).toFixed(4)}° ${lon >= 0 ? 'E' : 'W'}`;
}

export const fileName = (it: PhotoItem) => (it.Path ? it.Path.split(/[\\/]/).pop() ?? it.Name : it.Name);
export const folderOf = (it: PhotoItem) => (it.Path ? it.Path.split(/[\\/]/).slice(0, -1).join('/') : '');

export function formatName(it: PhotoItem, type?: string): string {
  const ext = (it.Path?.split('.').pop() ?? '').toLowerCase();
  const fromType = type?.split('/')[1]?.toUpperCase();
  const map: Record<string, string> = { jpg: 'JPEG', jpeg: 'JPEG', png: 'PNG', heic: 'HEIC', heif: 'HEIF', webp: 'WebP', gif: 'GIF', tif: 'TIFF', tiff: 'TIFF', avif: 'AVIF', dng: 'DNG', mp4: 'MP4', mov: 'MOV', mkv: 'MKV' };
  return map[ext] ?? fromType ?? ext.toUpperCase();
}

/* ───────── Queries ───────── */

export function mediaQuery(parentId: string, extra: ItemsQuery = {}): ItemsQuery {
  const set = Object.fromEntries(Object.entries(extra).filter(([, v]) => v !== undefined)) as ItemsQuery;
  return {
    parentId, recursive: true, includeItemTypes: MEDIA_TYPES, sortBy: ['DateCreated', 'SortName'], sortOrder: 'Descending',
    fields: PHOTO_FIELDS, enableImageTypes: ['Primary'], imageTypeLimit: 1, ...set
  };
}

export const getMedia = (q: ItemsQuery, signal?: AbortSignal) => getItems(q, signal) as Promise<ItemsResult<PhotoItem>>;

/* Every photo and video in a library, a page at a time (the Cameras tab groups them). */
const allCache = new Map<string, { at: number; items: Promise<PhotoItem[]> }>();
export function allMedia(parentId: string): Promise<PhotoItem[]> {
  const hit = allCache.get(parentId);
  if (hit && Date.now() - hit.at < 120_000) return hit.items;
  const items = (async () => {
    const out: PhotoItem[] = [];
    for (let start = 0; ; start += 500) {
      const r = await getMedia(mediaQuery(parentId, { startIndex: start, limit: 500, enableTotalRecordCount: start === 0 }));
      out.push(...r.Items);
      if (r.Items.length < 500) break;
    }
    return out;
  })();
  allCache.set(parentId, { at: Date.now(), items });
  items.catch(() => allCache.delete(parentId));
  return items;
}

/* The years with photos, newest first. */
export async function yearsOf(parentId: string): Promise<number[]> {
  const r = await get<ItemsResult>('/Years', { userId: uid(), parentId, recursive: true, includeItemTypes: MEDIA_TYPES.join(','), sortBy: 'SortName', sortOrder: 'Descending', enableImages: false });
  return r.Items.map((y) => Number(y.Name)).filter((y) => y > 1800).sort((a, b) => b - a);
}

/* The months of a year with photos (12 small counts, kept for the session). */
const monthsCache = new Map<string, Promise<number[]>>();
export function monthsOf(parentId: string, year: number): Promise<number[]> {
  const key = `${parentId}:${year}`;
  let hit = monthsCache.get(key);
  if (!hit) {
    hit = Promise.all(Array.from({ length: 12 }, (_, m) => getItems({
      parentId, includeItemTypes: MEDIA_TYPES, limit: 0, fields: [], enableImageTypes: [],
      minPremiereDate: new Date(Date.UTC(year, m, 1)).toISOString(), maxPremiereDate: new Date(Date.UTC(year, m + 1, 1) - 1).toISOString()
    }).then((r) => (r.TotalRecordCount > 0 ? m : -1), () => -1))).then((ms) => ms.filter((m) => m >= 0).sort((a, b) => b - a));
    monthsCache.set(key, hit);
  }
  return hit;
}

/* ───────── On this day ───────── */

export interface Memory {
  key: string;
  years: number;
  title: string;
  date: Date;
  items: PhotoItem[];
}

/* Photos taken within a few days of today's date in earlier years, one memory per year and folder, newest first. */
export async function memoriesOf(parentId: string, today = new Date(), days = 3): Promise<Memory[]> {
  const years = (await yearsOf(parentId)).filter((y) => y < today.getFullYear());
  const lists = await Promise.all(years.slice(0, 30).map(async (y) => {
    const mid = Date.UTC(y, today.getMonth(), today.getDate());
    const r = await getMedia({
      parentId, recursive: true, includeItemTypes: ['Photo'], sortBy: ['DateCreated'], sortOrder: 'Ascending', limit: 300,
      fields: PHOTO_FIELDS, enableImageTypes: ['Primary'], imageTypeLimit: 1,
      minPremiereDate: new Date(mid - days * 86_400_000).toISOString(), maxPremiereDate: new Date(mid + (days + 1) * 86_400_000 - 1).toISOString()
    }).catch(() => ({ Items: [] as PhotoItem[], TotalRecordCount: 0 }));
    return { y, items: r.Items };
  }));
  const out: Memory[] = [];
  for (const { y, items } of lists) {
    const groups = new Map<string, PhotoItem[]>();
    for (const it of items) {
      const k = it.AlbumId ?? '';
      groups.set(k, [...(groups.get(k) ?? []), it]);
    }
    for (const [k, list] of groups) {
      const date = takenAt(list[0]) ?? new Date(y, today.getMonth(), today.getDate());
      out.push({ key: `${y}:${k}`, years: today.getFullYear() - y, title: list[0].Album || longDay(date), date, items: list });
    }
  }
  return out.sort((a, b) => a.years - b.years || b.items.length - a.items.length);
}

/* ───────── Grouping ───────── */

export interface Day { key: string; date: Date; items: PhotoItem[] }
export interface Month { key: string; date: Date; days: Day[] }

export function groupByDay(items: PhotoItem[]): Month[] {
  const months: Month[] = [];
  for (const it of items) {
    const d = takenAt(it) ?? new Date(0);
    const mk = `${d.getFullYear()}-${d.getMonth()}`;
    const dk = `${mk}-${d.getDate()}`;
    let m = months[months.length - 1];
    if (!m || m.key !== mk) { m = { key: mk, date: new Date(d.getFullYear(), d.getMonth(), 1), days: [] }; months.push(m); }
    let day = m.days[m.days.length - 1];
    if (!day || day.key !== dk) { day = { key: dk, date: new Date(d.getFullYear(), d.getMonth(), d.getDate()), items: [] }; m.days.push(day); }
    day.items.push(it);
  }
  return months;
}

/* "14 photos · Home": the counts, then the folder most of the day is in (when it isn't the page's own). */
export function daySub(day: Day, own?: string): string {
  const photos = day.items.filter((i) => !isVideo(i)).length;
  const counts = countsLabel(photos, day.items.length - photos);
  const tally = new Map<string, number>();
  for (const it of day.items) if (it.Album && it.Album !== own) tally.set(it.Album, (tally.get(it.Album) ?? 0) + 1);
  const top = [...tally].sort((a, b) => b[1] - a[1])[0]?.[0];
  return top ? `${counts} · ${top}` : counts;
}

/* ───────── Folders ───────── */

export interface FolderInfo { item: BaseItem; count: number; photos: number; videos: number; covers: PhotoItem[]; from?: Date; to?: Date }

/* A folder's counts, first photos (for its cover) and dates, cached for a minute. */
const folderCache = new Map<string, { at: number; info: Promise<FolderInfo> }>();
export function folderInfo(item: BaseItem, recursive = true): Promise<FolderInfo> {
  const key = item.Id + (recursive ? ':r' : '');
  const hit = folderCache.get(key);
  if (hit && Date.now() - hit.at < 60_000) return hit.info;
  const info = (async () => {
    const base = { parentId: item.Id, recursive, fields: PHOTO_FIELDS, enableImageTypes: ['Primary'], imageTypeLimit: 1 } as ItemsQuery;
    const [newest, oldest, videos] = await Promise.all([
      getMedia({ ...base, includeItemTypes: MEDIA_TYPES, sortBy: ['DateCreated'], sortOrder: 'Descending', limit: 3 }),
      getMedia({ ...base, includeItemTypes: MEDIA_TYPES, sortBy: ['DateCreated'], sortOrder: 'Ascending', limit: 3 }),
      getItems({ parentId: item.Id, recursive, includeItemTypes: ['Video'], limit: 0, fields: [], enableImageTypes: [] })
    ]);
    const covers = [...oldest.Items, ...newest.Items.slice().reverse()].filter((x, i, all) => all.findIndex((y) => y.Id === x.Id) === i && !isVideo(x));
    return {
      item, count: newest.TotalRecordCount, videos: videos.TotalRecordCount, photos: newest.TotalRecordCount - videos.TotalRecordCount,
      covers: covers.length ? covers : oldest.Items, from: oldest.Items[0] && takenAt(oldest.Items[0]), to: newest.Items[0] && takenAt(newest.Items[0])
    };
  })();
  folderCache.set(key, { at: Date.now(), info });
  info.catch(() => folderCache.delete(key));
  return info;
}

export function subfolders(parentId: string, signal?: AbortSignal) {
  return getItems({
    parentId, recursive: false, includeItemTypes: FOLDER_TYPES, sortBy: ['SortName'], sortOrder: 'Ascending',
    fields: ['DateCreated', 'Path', 'ChildCount', 'RecursiveItemCount'], enableImageTypes: ['Primary'], imageTypeLimit: 1
  }, signal);
}

/* The folders up from an item to its library: [library, …, parent]. */
export async function ancestorsOf(id: string): Promise<BaseItem[]> {
  const list = await get<BaseItem[]>(`/Items/${id}/Ancestors`, { userId: uid() });
  const top = list.findIndex((a) => a.Type === 'CollectionFolder' || a.Type === 'UserView');
  return (top >= 0 ? list.slice(0, top + 1) : list).reverse();
}
