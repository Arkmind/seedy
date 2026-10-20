import { useEffect, useState } from 'preact/hooks';
import { cardArt, personImageUrl } from '../../api/images';
import type { BaseItem } from '../../api/types';
import { meStore } from '../../lib/app-state';
import { useStore } from '../../lib/store';
import { Icon } from '../icons';
import { loadGenres, marks, personLine, personMeta, type GenreInfo, type PersonInfo } from './engine';
import { clearRecent, useRecent } from './recent';
import { t } from '../../lib/i18n';

/* Pieces shared by the bar's dropdown, the phone's full-screen search and the Search page. */

/* Seerr's own search, in a new tab ("Not here? Find “north” on Seerr"). Only when this person has Seerr. */
export function useSeerrSearch(): ((q: string) => string) | null {
  const me = useStore(meStore);
  const base = me.requests.connected ? me.requests.seerrUrl?.trim().replace(/\/+$/, '') : '';
  if (!base) return null;
  return (q: string) => `${base}/search?query=${encodeURIComponent(q.trim())}`;
}

/* The name with the matched part in the accent colour. */
export function Marked({ text, query }: { text: string; query: string }) {
  return <>{marks(text, query).map((p, i) => (p.m ? <b key={i} class="nb-mark">{p.t}</b> : p.t))}</>;
}

/* Fallback tiles when a title has no artwork: initials on the boards' colours. */
const TILES = [
  ['#2f2840', '#e3c4ff'], ['#26362b', '#d9e3b5'], ['#1f3a3c', '#a8e6df'], ['#3d2436', '#ffb3e6'], ['#24313d', '#c9d6e3'], ['#3f3322', '#f2cf86']
];
function tile(name: string) {
  let h = 0;
  for (const ch of name) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return TILES[h % TILES.length];
}
const initials = (name: string) => name.split(/\s+/).filter(Boolean).map((w) => w.charAt(0)).join('').slice(0, 2).toUpperCase();

/* 16:9 artwork (a round photo for people), or initials on a colour while there's none. */
export function Thumb({ item, width, class: cls }: { item: BaseItem; width: number; class?: string }) {
  const person = item.Type === 'Person';
  const src = person ? personImageUrl({ Id: item.Id, PrimaryImageTag: item.ImageTags?.Primary }, width * 2) : cardArt(item, Math.max(160, width * 2)).src;
  const [failed, setFailed] = useState(false);
  const [bg, ink] = tile(item.Name);
  return (
    <span aria-hidden="true" class={'nb-thumb' + (person ? ' is-person' : '') + (cls ? ' ' + cls : '')} style={{ background: bg, color: ink }}>
      {initials(item.Name)}
      {src && !failed && <img src={src} alt="" loading="lazy" decoding="async" onError={() => setFailed(true)} />}
    </span>
  );
}

/* "Actor · 4 titles in your library", filled in once known. */
export function PersonLine({ person, fallback = 'Person', short }: { person: BaseItem; fallback?: string; short?: boolean }) {
  const [info, setInfo] = useState<PersonInfo | undefined>();
  useEffect(() => {
    let live = true;
    personMeta(person).then((i) => { if (live) setInfo(i); });
    return () => { live = false; };
  }, [person.Id]);
  return <>{info ? (short ? info.role : personLine(info)) : fallback}</>;
}

/* Genres for "Browse by genre": the server's most used, at most eight. */
export function useTopGenres(max = 8): GenreInfo[] | null {
  const [g, setG] = useState<GenreInfo[] | null>(null);
  useEffect(() => {
    let live = true;
    loadGenres().then((list) => { if (live) setG([...list].filter((x) => x.count > 0).sort((a, b) => b.count - a.count || a.name.localeCompare(b.name)).slice(0, max)); });
    return () => { live = false; };
  }, []);
  return g;
}

/* Search opened, nothing typed yet: recent searches, genres, and the tip (Navigation bar board, section 4). */
export function SearchIdle({ onRecent, onGenre, page, afterClear }: {
  onRecent: (q: string) => void; onGenre: (name: string) => void; page?: boolean; afterClear?: () => void;
}) {
  const recent = useRecent();
  const genres = useTopGenres();
  const H = page ? 'h2' : 'span';
  return (
    <>
      {recent.length > 0 && (
        <div class="nb-idle-group" role="group" aria-label={t('Recent searches')}>
          <span class="nb-idle-head">
            <H class="nb-idle-label">{page ? t('Recent searches') : t('Recent')}</H>
            <button type="button" class="nb-idle-clear" onClick={() => { clearRecent(); afterClear?.(); }} data-nbchip>{t('Clear')}</button>
          </span>
          <div class="nb-chips">
            {recent.map((s) => (
              <button key={s} type="button" class="nb-chip is-recent" onClick={() => onRecent(s)} data-nbchip>
                <Icon name="clock" size={14} stroke={2.2} />{s}
              </button>
            ))}
          </div>
        </div>
      )}
      <div class="nb-idle-group" role="group" aria-label={t('Browse by genre')}>
        <H class="nb-idle-label">{t('Browse by genre')}</H>
        <div class="nb-chips">
          {genres === null && [72, 60, 84, 96, 64, 88].map((w, i) => <span key={i} aria-hidden="true" class="nb-chip is-skel skel" style={{ width: w + 'px' }} />)}
          {genres?.length === 0 && <span class="nb-idle-note">{t('No genres on this server yet.')}</span>}
          {genres?.map((g) => (
            <button key={g.name} type="button" class="nb-chip" onClick={() => onGenre(g.name)} data-nbchip>{g.name}</button>
          ))}
        </div>
      </div>
      <span class="nb-tip">{t('Tip: type a year, a genre or an actor. “thriller 2024” works too.')}</span>
    </>
  );
}

/* Arrow keys between the idle panel's chips; true when focus moved. */
export function moveChip(container: HTMLElement, key: string): 'moved' | 'out' | 'none' {
  const chips = Array.from(container.querySelectorAll<HTMLElement>('[data-nbchip]'));
  const i = chips.indexOf(document.activeElement as HTMLElement);
  if (i < 0) return 'none';
  const back = key === 'ArrowLeft' || key === 'ArrowUp';
  const fwd = key === 'ArrowRight' || key === 'ArrowDown';
  if (!back && !fwd) return 'none';
  if (back && i === 0) return 'out';
  const n = Math.max(0, Math.min(chips.length - 1, i + (back ? -1 : 1)));
  chips[n].focus();
  return 'moved';
}
