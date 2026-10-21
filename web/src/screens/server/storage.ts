import { formatNumber, t } from '../../lib/i18n';

/* Server · Overview storage: Jellyfin's folders (GET /System/Info/Storage) grouped by the disk they sit on, named
   after what they hold. The Android server page groups and names them the same way. */

export interface FolderStorage { Path: string; FreeSpace: number; UsedSpace: number; StorageType?: string | null; DeviceId?: string | null }
export interface SystemStorage {
  ProgramDataFolder?: FolderStorage;
  WebFolder?: FolderStorage;
  ImageCacheFolder?: FolderStorage;
  CacheFolder?: FolderStorage;
  LogFolder?: FolderStorage;
  InternalMetadataFolder?: FolderStorage;
  TranscodingTempFolder?: FolderStorage;
  Libraries?: { Id: string; Name: string; Folders?: FolderStorage[] }[];
}

type Role = 'data' | 'metadata' | 'cache' | 'images' | 'logs' | 'transcodes' | 'web' | 'library';
export type DiskKind = 'server' | 'library' | 'cache' | 'transcodes' | 'web' | 'other';

export interface Disk {
  kind: DiskKind;
  name: string;
  mount: string;
  used: number;
  total: number;
  free: number;
  pct: number;
  ram: boolean;
  /* "RAM disk, emptied on restart", "Network share". */
  tag?: string;
  folders: { label: string; path: string }[];
  /* Library names on this disk, for the "where … live" line. */
  libraries: string[];
}

const ROLE_LABEL: Record<Exclude<Role, 'library'>, string> = {
  data: 'Program data', metadata: 'Metadata', cache: 'Cache', images: 'Image cache', logs: 'Logs', transcodes: 'Transcodes', web: 'Web files'
};
const ORDER: DiskKind[] = ['server', 'library', 'cache', 'transcodes', 'web', 'other'];

export function disks(s: SystemStorage): Disk[] {
  const entries: { role: Role; label: string; f: FolderStorage }[] = [];
  const add = (role: Exclude<Role, 'library'>, f?: FolderStorage) => { if (f) entries.push({ role, label: t(ROLE_LABEL[role]), f }); };
  add('data', s.ProgramDataFolder);
  add('metadata', s.InternalMetadataFolder);
  add('cache', s.CacheFolder);
  add('images', s.ImageCacheFolder);
  add('logs', s.LogFolder);
  add('transcodes', s.TranscodingTempFolder);
  add('web', s.WebFolder);
  for (const lib of s.Libraries ?? []) for (const f of lib.Folders ?? []) entries.push({ role: 'library', label: lib.Name, f });

  const groups = new Map<string, typeof entries>();
  for (const e of entries) {
    if (!(e.f.UsedSpace + e.f.FreeSpace > 0)) continue;
    const key = e.f.DeviceId || e.f.Path;
    groups.set(key, [...(groups.get(key) ?? []), e]);
  }

  const list: Disk[] = [];
  for (const [mount, es] of groups) {
    const roles = new Set(es.map((e) => e.role));
    const only = (...rs: Role[]) => [...roles].every((r) => rs.includes(r));
    const libraries = [...new Set(es.filter((e) => e.role === 'library').map((e) => e.label))];
    let kind: DiskKind;
    let name: string;
    if (roles.has('data')) { kind = 'server'; name = t('Server data'); }
    else if (only('library')) { kind = 'library'; name = libraries.length === 1 ? libraries[0] : t('Media libraries'); }
    else if (only('transcodes')) { kind = 'transcodes'; name = t('Transcodes'); }
    else if (only('web')) { kind = 'web'; name = t('Web client'); }
    else if (roles.has('cache') || roles.has('images')) { kind = 'cache'; name = t('Cache'); }
    else { kind = 'other'; name = t('Disk'); }
    const { UsedSpace: used, FreeSpace: free, StorageType: type } = es[0].f;
    const total = used + free;
    const seen = new Set<string>();
    /* A disk that is just one folder (a RAM disk for transcodes) is already named after it. */
    const alone = es.length === 1 && es[0].f.Path === mount;
    list.push({
      kind, name, mount, used, free, total,
      pct: Math.round((used / total) * 100),
      ram: type === 'Ram',
      tag: type === 'Ram' ? t('RAM disk, emptied on restart') : type === 'Network' ? t('Network share') : undefined,
      folders: es
        .filter((e) => !alone && !seen.has(e.label + e.f.Path) && seen.add(e.label + e.f.Path))
        .map((e) => ({ label: e.label, path: e.f.Path })),
      libraries
    });
  }
  return list.sort((a, b) => ORDER.indexOf(a.kind) - ORDER.indexOf(b.kind));
}

/* Warnings start at 85 %, red at 95 %. */
export const WARN_AT = 85;
export const level = (pct: number) => (pct >= 95 ? 'danger' : pct >= WARN_AT ? 'warn' : 'ok');

/* Binary units the way the board writes them: "10.9 TiB", "242 GiB". */
export function fmtDisk(bytes: number): string {
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB'];
  let v = Math.max(0, bytes);
  let i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  const digits = v >= 100 || i === 0 ? 0 : 1;
  return `${formatNumber(Number(v.toFixed(digits)), { minimumFractionDigits: digits, maximumFractionDigits: digits })} ${units[i]}`;
}

/* "Movies, Shows and Music". */
export function joinNames(names: string[]): string {
  if (names.length <= 1) return names[0] ?? '';
  return t('{list} and {last}', { list: names.slice(0, -1).join(', '), last: names[names.length - 1] });
}

/* The notice's second sentence: "1.6 TiB left on /config, where metadata and the cache live." */
export function fullText(d: Disk): string {
  const vars = { free: fmtDisk(d.free), mount: d.mount };
  if (d.kind === 'server') {
    return d.folders.some((f) => f.label === t('Cache'))
      ? t('{free} left on {mount}, where metadata and the cache live', vars)
      : t('{free} left on {mount}, where metadata lives', vars);
  }
  if (d.kind === 'library' && d.libraries.length) return t('{free} left on {mount}, where {names} live', { ...vars, names: joinNames(d.libraries) });
  return t('{free} left on {mount}', vars);
}
