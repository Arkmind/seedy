import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks';
import { cardArt } from '../api/images';
import type { BaseItem } from '../api/types';
import { progressFraction, runtime } from '../lib/format';
import { Art, Progress } from '../ui/cards';
import { Segmented } from '../ui/controls';
import { Icon } from '../ui/icons';
import { t } from '../lib/i18n';

/* Episodes of the playing season, with a season switcher. */
export function EpisodesPanel({ current, episodes, onPlay }: { current: BaseItem; episodes: BaseItem[] | null; onPlay: (ep: BaseItem) => void }) {
  const seasons = useMemo(() => {
    const map = new Map<string, { id: string; name: string; num?: number; eps: BaseItem[] }>();
    for (const ep of episodes ?? []) {
      const id = ep.SeasonId ?? String(ep.ParentIndexNumber ?? 0);
      let s = map.get(id);
      if (!s) {
        const num = ep.ParentIndexNumber;
        s = { id, num, name: ep.SeasonName || (num === 0 ? t('Specials') : t('Season {n}', { n: num ?? '' })), eps: [] };
        map.set(id, s);
      }
      s.eps.push(ep);
    }
    return [...map.values()];
  }, [episodes]);

  const [season, setSeason] = useState<string>(current.SeasonId ?? String(current.ParentIndexNumber ?? 0));
  const shown = seasons.find((s) => s.id === season) ?? seasons[0];
  const listRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    listRef.current?.querySelector<HTMLElement>('[aria-current="true"]')?.scrollIntoView({ block: 'nearest' });
  }, [episodes]);

  return (
    <section class="pl-panel" aria-label={t('Episodes')}>
      {seasons.length > 1 && (
        <SeasonTabs
          value={shown?.id ?? ''}
          onChange={setSeason}
          options={seasons.map((s) => ({ value: s.id, label: s.num === 0 ? t('Specials') : s.num !== undefined ? t('Season {n}', { n: s.num }) : s.name }))}
        />
      )}
      <div ref={listRef} class="pl-group">
        <span class="pl-label">{current.SeriesName ? `${current.SeriesName} · ${shown?.name ?? ''}` : shown?.name}</span>
        {episodes === null && [0, 1, 2, 3].map((i) => <span key={i} class="pl-row-skel skel" />)}
        {shown?.eps.map((ep) => {
          const on = ep.Id === current.Id;
          const prog = progressFraction(ep);
          const watched = !!ep.UserData?.Played;
          return (
            <button
              key={ep.Id}
              type="button"
              class="menu-item pl-row-item is-episode"
              aria-pressed={on ? 'true' : 'false'}
              aria-current={on ? 'true' : undefined}
              onClick={() => { if (!on) onPlay(ep); }}
            >
              <span class="lead">
                <span class="pl-row-thumb is-episode">
                  <Art art={cardArt(ep, 320)} title={ep.Name} titleClass="pl-row-art-title" />
                  {watched && !on && <span class="pl-row-watched" aria-label={t('Watched')}><Icon name="check" size={11} stroke={3} /></span>}
                  {!watched && prog > 0 && !on && <Progress fraction={prog} size={4} class="pl-row-bar" />}
                </span>
                <span class="two">
                  <span>{ep.IndexNumber !== undefined ? `${ep.IndexNumber}. ` : ''}{ep.Name}</span>
                  <span class="sub">{on ? t('Playing now') : [runtime(ep), watched ? t('Watched') : ''].filter(Boolean).join(' · ')}</span>
                </span>
              </span>
              {on && <Icon name="check" size={20} stroke={2.6} class="tick" />}
            </button>
          );
        })}
      </div>
    </section>
  );
}

// Scrolls the tabs; near the end it heads for, it goes all the way rather than leave an arrow with a few pixels behind it.
const slide = (el: HTMLElement, by: number) => {
  const end = el.scrollWidth - el.clientWidth;
  const to = el.scrollLeft + by;
  el.scrollTo({ left: by > 0 ? (to > end - 56 ? end : to) : to < 56 ? 0 : to, behavior: 'smooth' });
};

// Brings a tab out from under the arrows.
const reveal = (el: HTMLElement, tab: HTMLElement) => {
  const box = el.getBoundingClientRect();
  const r = tab.getBoundingClientRect();
  const room = 64;
  const off = r.left < box.left + room ? r.left - box.left - room : r.right > box.right - room ? r.right - box.right + room : 0;
  if (off) slide(el, off);
};

/* A long-running show has more seasons than the panel holds: the tabs slide inside the pill (arrows, wheel or swipe)
   and the picked season stays in view. */
function SeasonTabs({ value, options, onChange }: { value: string; options: { value: string; label: string }[]; onChange: (v: string) => void }) {
  const track = useRef<HTMLDivElement>(null);
  const placed = useRef(false);
  const [edges, setEdges] = useState({ left: false, right: false });

  useEffect(() => {
    const el = track.current;
    if (!el) return;
    const update = () => setEdges({ left: el.scrollLeft > 4, right: el.scrollLeft + el.clientWidth < el.scrollWidth - 4 });
    update();
    el.addEventListener('scroll', update, { passive: true });
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => {
      el.removeEventListener('scroll', update);
      ro.disconnect();
    };
  }, [options.length]);

  // Centred when the panel opens; later, brought out from under an arrow, like a tab the keyboard lands on.
  useLayoutEffect(() => {
    const el = track.current;
    const tab = el?.querySelector<HTMLElement>('[aria-pressed="true"]');
    if (!el || !tab) return;
    if (placed.current) return reveal(el, tab);
    placed.current = true;
    const box = el.getBoundingClientRect();
    const r = tab.getBoundingClientRect();
    el.scrollLeft += r.left + r.width / 2 - (box.left + box.width / 2);
  }, [value]);

  const page = (dir: 1 | -1) => {
    const el = track.current;
    if (el) slide(el, dir * Math.max(120, el.clientWidth - 120));
  };

  // A mouse wheel moves the tabs sideways until they run out, then the panel scrolls as usual.
  const wheel = (e: WheelEvent) => {
    const el = track.current;
    if (!el || Math.abs(e.deltaX) >= Math.abs(e.deltaY)) return;
    const before = el.scrollLeft;
    el.scrollLeft += e.deltaY * (e.deltaMode === 1 ? 16 : 1);
    if (el.scrollLeft !== before) e.preventDefault();
  };

  return (
    <div class="pl-seasons">
      <div ref={track} class="pl-seasons-track hscroll" onWheel={wheel} onFocusIn={(e) => track.current && reveal(track.current, e.target as HTMLElement)}>
        <Segmented<string> label={t('Season')} value={value} onChange={onChange} options={options} />
      </div>
      <button type="button" class="pl-seasons-arrow is-left" aria-label={t('Scroll left')} tabIndex={-1} hidden={!edges.left} onClick={() => page(-1)}>
        <span><Icon name="chevronLeft" size={16} stroke={2.4} /></span>
      </button>
      <button type="button" class="pl-seasons-arrow is-right" aria-label={t('Scroll right')} tabIndex={-1} hidden={!edges.right} onClick={() => page(1)}>
        <span><Icon name="chevronRight" size={16} stroke={2.4} /></span>
      </button>
    </div>
  );
}
