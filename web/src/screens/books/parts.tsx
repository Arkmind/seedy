import type { ComponentChildren } from 'preact';
import { useLayoutEffect, useState } from 'preact/hooks';
import type { BaseItem } from '../../api/types';
import { dayOf, duration, pct, ticksToSeconds } from '../../lib/format';
import { t } from '../../lib/i18n';
import { Link } from '../../lib/router';
import { playAudiobook } from '../../audio/engine';
import { authorLine, isAudioBook, isComic, seriesOf, type Reading } from './api';
import { AudioCover, BookCover } from './covers';
import '@fontsource-variable/noto-serif/wght.css';

/* Pieces shared by the books screens: cards, section heads, a few labels. */

/* A word whose translation depends on where it stands ("Series" the tab is plural in French, "Normal" agrees with
   what it describes): the dictionary key is "Series|tab", English shows the word itself. */
export function tc(text: string, context: string): string {
  const key = `${text}|${context}`;
  const out = t(key);
  return out === key ? text : out;
}

export const yearOfBook = (item: BaseItem) => item.ProductionYear ?? (item.PremiereDate ? dayOf(item.PremiereDate)?.getFullYear() : undefined);

/* "About 3 h", "1 h 30 min", "25 min": rough reading/listening time for cards. */
export function roughTime(minutes: number): string {
  const m = Math.max(1, Math.round(minutes));
  if (m < 60) return `${Math.max(5, Math.round(m / 5) * 5)} min`;
  if (m < 120) {
    const r = Math.round(m / 10) * 10;
    return r % 60 ? `${Math.floor(r / 60)} h ${String(r % 60).padStart(2, '0')} min` : `${r / 60} h`;
  }
  return `${Math.round(m / 60)} h`;
}

/* "3 h 10 min" to the nearest ten minutes (the reader's status line). */
export function tenMinutes(minutes: number): string {
  const m = Math.max(0, Math.round(minutes / 10) * 10);
  if (m === 0) return t('a few minutes');
  return duration(m * 60).replace(' 00 min', '');
}

/* Under a cover in the grids: "Lantern Season — The Halden Cycle, 4", else "Author · 2026". */
export function bookSub(item: BaseItem): string {
  const series = seriesOf(item);
  if (series) return `${series}, ${item.IndexNumber}`;
  const author = authorLine(item);
  const year = yearOfBook(item);
  return [author, year ? String(year) : item.Genres?.[0]?.toLowerCase()].filter(Boolean).join(' · ');
}

/* Comics: "Kestrel · new", "Kestrel · reading" in a running series, else "Author · 2025" (or "One-shot"). */
export function comicSub(item: BaseItem, reading: Reading | undefined, running: boolean): string {
  const state = item.UserData?.Played || reading?.Finished ? t('read') : reading ? t('reading') : t('new');
  const series = seriesOf(item);
  if (series && running) return `${series} · ${state}`;
  const author = authorLine(item);
  const year = yearOfBook(item);
  return [author, year ? String(year) : ''].filter(Boolean).join(' · ') || t('One-shot');
}

export const audioLength = (item: BaseItem) => (item.RunTimeTicks ? duration(ticksToSeconds(item.RunTimeTicks)) : '');

/* ───────── Columns that fit: overview rows show one row of whatever fits ───────── */

export function useColumns<T extends HTMLElement>(min: number, gap: number): [(el: T | null) => void, number] {
  const [el, setEl] = useState<T | null>(null);
  const [cols, setCols] = useState(0);
  useLayoutEffect(() => {
    if (!el) return;
    const measure = () => setCols(Math.max(1, Math.floor((el.clientWidth + gap) / (min + gap))));
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [el, min, gap]);
  return [setEl, cols];
}

/* ───────── Section head: "Recently added ……… See all" ───────── */

export function SectionHead({ id, title, link, linkLabel, onLink }: { id: string; title: ComponentChildren; link?: string; linkLabel?: string; onLink?: () => void }) {
  return (
    <div class="bk-sec-head">
      <h2 id={id} class="bk-sec-title">{title}</h2>
      {link && linkLabel && <Link to={link} replace class="bk-sec-link" onClick={onLink}>{linkLabel}</Link>}
    </div>
  );
}

/* Jellyfin hands back the description as it was in the file, sometimes as HTML. */
export function plainText(s: string): string {
  if (!/<[a-z][\s\S]*>/i.test(s)) return s;
  const doc = new DOMParser().parseFromString(s, 'text/html');
  return (doc.body.textContent ?? '').replace(/\s+\n/g, '\n').trim();
}

/* ───────── Cards ───────── */

/* A book or comic. With a synopsis, hovering it (or focusing it) opens the cover on its first page, where the
   synopsis is printed. */
export function BookCard({ item, sub, subClass, titleClass, badge, size = 'md' }: {
  item: BaseItem; sub?: ComponentChildren; subClass?: string; titleClass?: string; badge?: ComponentChildren; size?: 'md' | 'sm';
}) {
  const blurb = item.Overview ? plainText(item.Overview) : '';
  const by = blurb ? authorLine(item) : '';
  return (
    <Link to={`/details/${item.Id}`} class={'bk-card' + (blurb ? ' can-open' : '')} aria-label={item.Name}>
      <span class="bk-card-art">
        {blurb && (
          <span aria-hidden="true" class="bk-firstpage">
            <span class="bk-fp-title">{item.Name}</span>
            {by && <span class="bk-fp-by">{by}</span>}
            <span class="bk-fp-text">{blurb}</span>
          </span>
        )}
        <span class="bk-leaf">
          <BookCover item={item} size={size} />
          {blurb && <span aria-hidden="true" class="bk-leaf-back" />}
        </span>
        {badge}
      </span>
      <span class="bk-card-text">
        <span class={'bk-card-title' + (titleClass ? ' ' + titleClass : '')}>{item.Name}</span>
        {sub !== undefined && sub !== '' && <span class={'bk-card-sub' + (subClass ? ' ' + subClass : '')}>{sub}</span>}
      </span>
    </Link>
  );
}

export function HeadphonesIcon({ size = 13 }: { size?: number }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} class="bk-ic" aria-hidden="true">
      <path d="M4 15v-3a8 8 0 0 1 16 0v3" /><rect x="3" y="14" width="4" height="6" rx="1.5" /><rect x="17" y="14" width="4" height="6" rx="1.5" />
    </svg>
  );
}

export function AudioCard({ item }: { item: BaseItem }) {
  const len = audioLength(item);
  return (
    <Link to={`/details/${item.Id}`} class="bk-card is-audio" aria-label={item.Name}>
      <span class="bk-card-art"><AudioCover item={item} /></span>
      <span class="bk-card-text">
        <span class="bk-card-title">{item.Name}</span>
        {len && <span class="bk-card-sub is-icon"><HeadphonesIcon />{len}</span>}
      </span>
    </Link>
  );
}

/* A series: three covers fanned out, its name, how much is read, a segment per volume. */
export interface SeriesGroup { name: string; key: string; author: string; volumes: BaseItem[]; read: number; latest: number; read_at: number }

export function seriesFan(volumes: BaseItem[]): BaseItem[] {
  if (volumes.length <= 3) return volumes;
  const current = volumes.findIndex((v) => !v.UserData?.Played);
  const pick: number[] = [0];
  if (current > 0) pick.push(current);
  if (current >= 0 && current + 1 < volumes.length) pick.push(current + 1);
  for (let i = 1; pick.length < 3 && i < volumes.length; i++) if (!pick.includes(i)) pick.push(i);
  return pick.sort((a, b) => a - b).slice(0, 3).map((i) => volumes[i]);
}

export function SeriesCard({ group, to }: { group: SeriesGroup; to: string }) {
  const fan = seriesFan(group.volumes);
  const pos = ['is-a', 'is-b', 'is-c'];
  const sub = [group.author, t('{read} of {total} read', { read: group.read, total: group.volumes.length })].filter(Boolean).join(' · ');
  return (
    <Link to={to} class="bk-series" aria-label={group.name}>
      <span class="bk-fan" aria-hidden="true">
        {fan.map((v, i) => (
          <span key={v.Id} class={'bk-fan-cover ' + pos[fan.length === 1 ? 1 : fan.length === 2 ? i : i]}>
            <BookCover item={v} size="xs" width={220} />
          </span>
        ))}
      </span>
      <span class="bk-series-text">
        <span class="bk-series-name">{group.name}</span>
        <span class="bk-series-sub">{sub}</span>
      </span>
      <span class="bk-dots" aria-hidden="true">
        {group.volumes.map((v) => <i key={v.Id} class={v.UserData?.Played ? 'is-read' : ''} />)}
      </span>
    </Link>
  );
}

/* Continue: what this profile is reading or listening to, with how far in and how long is left. */
export interface ContinueEntry {
  item: BaseItem;
  kind: string;
  live?: boolean;
  where?: string;
  fraction: number;
  done: string;
  left?: string;
}

export function ContinueCard({ entry }: { entry: ContinueEntry }) {
  const audio = isAudioBook(entry.item);
  return (
    <Link
      to={audio ? `/details/${entry.item.Id}` : `/read/${entry.item.Id}`}
      class="bk-cont"
      aria-label={entry.item.Name}
      onClick={(e) => {
        // An audiobook carries on where it was; the page is the fallback until the player can.
        if (!audio || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
        e.preventDefault();
        void playAudiobook(entry.item.Id, { open: true });
      }}
    >
      <span class={'bk-cont-art' + (audio ? ' is-audio' : '')}>
        {audio ? <AudioCover item={entry.item} size="sm" titleTop noTitle={false} /> : <BookCover item={entry.item} size="sm" />}
      </span>
      <span class="bk-cont-text">
        <span class={'bk-cont-kind' + (entry.live ? ' is-live' : '')}>{entry.kind}</span>
        <span class="bk-cont-title">{entry.item.Name}</span>
        <span class="bk-cont-author">{authorLine(entry.item)}</span>
        <span class="bk-cont-fill" />
        {entry.where && <span class="bk-cont-where">{entry.where}</span>}
        <span class="bk-cont-bar" aria-hidden="true"><i style={{ width: pct(entry.fraction) }} /></span>
        <span class="bk-cont-foot"><span>{entry.done}</span>{entry.left && <span>{entry.left}</span>}</span>
      </span>
    </Link>
  );
}

export const comicOrBook = (item: BaseItem) => (isAudioBook(item) ? 'audio' : isComic(item) ? 'comic' : 'book');
