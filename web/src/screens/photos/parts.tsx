import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import type { BaseItem } from '../../api/types';
import { duration, ticksToSeconds } from '../../lib/format';
import { t, tn } from '../../lib/i18n';
import { Link, setQuery } from '../../lib/router';
import { safeLocal, saveLocal } from '../../lib/store';
import { useInView } from '../browse/parts';
import type { GridSelect } from '../browse/parts';
import {
  countsLabel, daySub, folderInfo, isVideo, monthYear, ratioOf, thumbUrl, type Day, type FolderInfo, type Memory, type Month, type PhotoItem,
  yearsAgo
} from './api';
import { PIcon, type PIconName } from './icons';
import { fmtMonthYear, fmtWeekdayDate } from '../../lib/format';

/* Pieces shared by the photos library, folder and viewer (Photos boards). Prefix ph-. */

/* ───────── Tile size (Small · Medium · Large), kept on this device ───────── */

export type TileSize = 'small' | 'medium' | 'large';
const ROW: Record<TileSize, number> = { small: 130, medium: 190, large: 280 };
const SIZE_KEY = 'seedy.photos.tiles';

export function useTileSize(): [TileSize, (s: TileSize) => void] {
  const [size, setSize] = useState<TileSize>(() => {
    const v = safeLocal<string>(SIZE_KEY, 'medium');
    return v === 'small' || v === 'large' ? v : 'medium';
  });
  return [size, (s) => { setSize(s); saveLocal(SIZE_KEY, s); }];
}

export function SizeSwitch({ size, onChange }: { size: TileSize; onChange: (s: TileSize) => void }) {
  const opts: { id: TileSize; label: string }[] = [{ id: 'small', label: t('Small') }, { id: 'medium', label: t('Medium') }, { id: 'large', label: t('Large') }];
  return (
    <div role="group" aria-label={t('Tile size')} class="ph-seg">
      {opts.map((o) => (
        <button key={o.id} type="button" class="ph-seg-btn" aria-pressed={o.id === size ? 'true' : 'false'} onClick={() => onChange(o.id)}>{o.label}</button>
      ))}
    </div>
  );
}

/* ───────── Buttons ───────── */

export function PhButton({ icon, label, primary, onClick, disabled, busy }: { icon: PIconName; label: string; primary?: boolean; onClick?: () => void; disabled?: boolean; busy?: boolean }) {
  return (
    <button type="button" class={'ph-btn' + (primary ? ' is-primary' : '')} disabled={disabled || busy} onClick={onClick}>
      <PIcon name={icon} size={18} />{label}
    </button>
  );
}

/* ───────── Tabs ───────── */

export function TabRow<T extends string>({ label, tabs, current, to }: { label: string; tabs: { id: T; label: string }[]; current: T; to: (id: T) => string }) {
  return (
    <nav aria-label={label} class="ph-tabs hscroll">
      {tabs.map((tb) => (
        <Link key={tb.id} to={to(tb.id)} replace class="ph-tab" aria-current={tb.id === current ? 'page' : undefined}>{tb.label}</Link>
      ))}
    </nav>
  );
}

/* ───────── Tiles ───────── */

/* Opens the viewer on a photo of this page (the page keeps its scroll; Back closes the viewer). */
export const viewerOpened = { current: false };
export function openPhoto(id: string, slideshow = false) {
  viewerOpened.current = true;
  setQuery({ photo: id, play: slideshow ? '1' : undefined }, false);
}

export function photoHref(id: string): string {
  const q = new URLSearchParams(window.location.search);
  q.set('photo', id);
  q.delete('play');
  return `${window.location.pathname}?${q.toString()}`;
}

/* A tile in a justified row: it grows with its width over height so every tile in a row has the same height, as on
   the boards (flex: ar 1 ar×row; the row's last line is held by a spacer). */
export function Tile({ item, row, index, select, onOpen }: { item: PhotoItem; row: number; index: number; select?: GridSelect; onOpen: (item: PhotoItem) => void }) {
  const ar = ratioOf(item);
  const picked = !!select?.has(item.Id);
  const fav = !!item.UserData?.IsFavorite;
  const video = isVideo(item);
  const src = thumbUrl(item, Math.round(row * 1.5));
  const secs = ticksToSeconds(item.RunTimeTicks);
  const label = video ? t('Video, {time}', { time: duration(secs) || '0:00' }) : item.Name;
  const href = photoHref(item.Id);
  return (
    <a
      href={href}
      class={'ph-tile' + (picked ? ' is-picked' : '') + (select?.active ? ' is-selecting' : '')}
      style={{ flex: `${ar} 1 ${Math.round(ar * row)}px`, aspectRatio: String(ar) }}
      aria-label={label}
      aria-pressed={select?.active ? (picked ? 'true' : 'false') : undefined}
      onClick={(e) => {
        if (e.metaKey || e.ctrlKey || e.button !== 0) return;
        e.preventDefault();
        if (select?.active) select.toggle(item, index, e.shiftKey);
        else onOpen(item);
      }}
    >
      {src && <img src={src} alt="" loading="lazy" decoding="async" draggable={false} />}
      {video && <span class="ph-dur"><svg viewBox="0 0 24 24" width="11" height="11" aria-hidden="true"><path d="M8 5v14l11-7z" /></svg>{clockOf(secs)}</span>}
      {fav && <span class="ph-fav" aria-label={t('Favorite')}><PIcon name="heart" size={16} filled /></span>}
      {select && (
        <>
          <span class="ph-tile-shade" aria-hidden="true" />
          <span
            class="ph-pick"
            role="checkbox"
            aria-checked={picked ? 'true' : 'false'}
            aria-label={t('Select {name}', { name: item.Name })}
            onClick={(e) => { e.preventDefault(); e.stopPropagation(); select.toggle(item, index, e.shiftKey); }}
          >
            {picked && <PIcon name="check" size={14} stroke={3} />}
          </span>
        </>
      )}
    </a>
  );
}

/* "0:42", "2:10", "1:04:09". */
export function clockOf(secs: number): string {
  const s = Math.max(0, Math.round(secs));
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
  return h ? `${h}:${String(m).padStart(2, '0')}:${String(r).padStart(2, '0')}` : `${m}:${String(r).padStart(2, '0')}`;
}

/* One day: "Sat 17 Oct  14 photos · Home", then its photos in justified rows. */
export function DaySection({ day, row, offset, select, own, onOpen, id }: { day: Day; row: number; offset: number; select?: GridSelect; own?: string; onOpen: (item: PhotoItem) => void; id?: string }) {
  return (
    <section class="ph-day" aria-label={fmtWeekdayDate(day.date)} id={id}>
      <div class="ph-day-head"><h3>{fmtWeekdayDate(day.date)}</h3><span>{daySub(day, own)}</span></div>
      <JustifiedRows items={day.items} row={row} offset={offset} select={select} onOpen={onOpen} />
    </section>
  );
}

export function JustifiedRows({ items, row, offset = 0, select, onOpen }: { items: PhotoItem[]; row: number; offset?: number; select?: GridSelect; onOpen: (item: PhotoItem) => void }) {
  return (
    <div class="ph-rows">
      {items.map((it, i) => <Tile key={it.Id} item={it} row={row} index={offset + i} select={select} onOpen={onOpen} />)}
      <span aria-hidden="true" class="ph-rows-end" />
    </div>
  );
}

/* Months and days, as on the Timeline board ("October 2026", then each day). */
/* A folder of one month lists its days only (Folder board); `months` false hides the headings. */
export function Timeline({ months, row, select, own, onOpen, headings = true }: { months: Month[]; row: number; select?: GridSelect; own?: string; onOpen: (item: PhotoItem) => void; headings?: boolean }) {
  let offset = 0;
  return (
    <>
      {months.map((m, mi) => (
        <div key={m.key} class="ph-month" data-month={`${m.date.getFullYear()}-${m.date.getMonth()}`}>
          {headings && <h2 class={'ph-month-h' + (mi ? ' is-next' : '')}>{monthYear(m.date)}</h2>}
          {m.days.map((d) => {
            const at = offset;
            offset += d.items.length;
            return <DaySection key={d.key} day={d} row={row} offset={at} select={select} own={own} onOpen={onOpen} />;
          })}
        </div>
      ))}
    </>
  );
}

/* ───────── On this day ───────── */

export function MemoryCard({ memory, onOpen }: { memory: Memory; onOpen: () => void }) {
  const cover = memory.items.find((i) => (i.Height ?? 0) >= (i.Width ?? 0)) ?? memory.items[0];
  const src = thumbUrl(cover, 420);
  const photos = memory.items.filter((i) => !isVideo(i)).length;
  return (
    <a href={photoHref(memory.items[0].Id)} class="ph-memory" onClick={(e) => { if (e.metaKey || e.ctrlKey) return; e.preventDefault(); onOpen(); }}>
      {src && <img src={src} alt="" loading="lazy" decoding="async" draggable={false} />}
      <span aria-hidden="true" class="ph-memory-shade" />
      <span class="ph-memory-text">
        <span class="ph-memory-when">{yearsAgo(memory.years)}</span>
        <span class="ph-memory-title">{memory.title}</span>
        <span class="ph-memory-sub">{countsLabel(photos, memory.items.length - photos)} · {fmtMonthYear(memory.date)}</span>
      </span>
    </a>
  );
}

/* ───────── Folders ───────── */

/* A folder card: three photos in the hero's mosaic, then its name, counts and dates. */
export function FolderCard({ item, to }: { item: BaseItem; to: string }) {
  const [ref, seen] = useInView<HTMLDivElement>('400px');
  const [info, setInfo] = useState<FolderInfo>();
  useEffect(() => { if (seen) folderInfo(item).then(setInfo, () => undefined); }, [seen, item.Id]);
  const covers = info?.covers ?? [];
  return (
    <div ref={ref} class="ph-folder-wrap"><Link to={to} class="ph-folder">
      <span class={'ph-folder-art' + (covers.length >= 3 ? ' is-three' : '')} aria-hidden="true">
        {covers.slice(0, covers.length >= 3 ? 3 : 1).map((c, i) => <span key={c.Id}><img src={thumbUrl(c, i ? 160 : 320)} alt="" loading="lazy" decoding="async" draggable={false} /></span>)}
        {!covers.length && <span class="ph-folder-empty"><PIcon name="folder" size={28} stroke={2} /></span>}
      </span>
      <span class="ph-folder-name">{item.Name}</span>
      <span class="ph-folder-sub">
        {info ? [countsLabel(info.photos, info.videos), info.to ? fmtMonthYear(info.to) : ''].filter(Boolean).join(' · ') : ' '}
      </span>
    </Link></div>
  );
}

export function FolderGrid({ children }: { children: ComponentChildren }) {
  return <div class="ph-folder-grid">{children}</div>;
}

/* "212 photos · 14 videos". */
export function countsLine(photos: number, videos: number): string {
  return [photos || !videos ? tn(photos, '{n} photo', '{n} photos') : '', videos ? tn(videos, '{n} video', '{n} videos') : ''].filter(Boolean).join(' · ');
}

export { ROW };
