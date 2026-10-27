import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import type { BaseItem } from '../../../api/types';
import { t, tn } from '../../../lib/i18n';
import { back, setQuery, useLocation } from '../../../lib/router';
import { authorLine, comicPageUrl, type BookInfo, type Reading, type ReadingPatch } from '../api';
import { BarButton, ComicSettingsPanel, ReaderMessage, Scrubber, TopBar, TurnButtons, useFullscreen } from './chrome';
import { useReadingSaver } from './saver';
import { readComicSettings, rememberComicSettings, type ComicSettings } from './settings';

/* The comic reader (CBZ): pages come one at a time from /Seedy/Books/{id}/Pages/{n}. One page, two pages side by
   side (the cover alone) or a vertical strip; fit to the screen or the width; left to right or right to left (manga).
   The place is the page index. */

export function ComicReader({ item, info, reading }: { item: BaseItem; info: BookInfo; reading?: Reading }) {
  const loc = useLocation();
  const pages = info.Pages;
  const [settings, setSettings] = useState<ComicSettings>(() => readComicSettings(reading?.Settings));
  const [panel, setPanel] = useState(false);
  const [full, toggleFull] = useFullscreen();
  const saver = useReadingSaver(item.Id);
  const [index, setIndex] = useState(() => {
    const p = loc.query.get('percent');
    if (p !== null && Number.isFinite(Number(p))) return Math.min(pages - 1, Math.max(0, Math.round((Number(p) / 100) * pages) - 1));
    const saved = Number(reading?.Locator);
    return Number.isFinite(saved) && saved >= 0 && saved < pages && !reading?.Finished ? saved : 0;
  });
  useEffect(() => { if (loc.query.get('percent') !== null) setQuery({ percent: undefined }); }, []);
  const rtl = settings.direction === 'rtl';
  const two = settings.layout === 'two';
  const scroll = settings.layout === 'scroll';

  /* Two pages: the cover alone, then pairs (1–2, 3–4…). */
  const spread = useMemo(() => {
    if (!two || index === 0) return [index];
    const start = index % 2 === 1 ? index : index - 1;
    return start + 1 < pages ? [start, start + 1] : [start];
  }, [two, index, pages]);
  const lastShown = spread[spread.length - 1];
  const step = (dir: 1 | -1) => {
    setIndex((i) => {
      if (!two) return Math.min(pages - 1, Math.max(0, i + dir));
      if (dir > 0) { const start = i === 0 ? 0 : i % 2 === 1 ? i : i - 1; const nextStart = start === 0 ? 1 : start + 2; return Math.min(pages - 1, nextStart); }
      const start = i === 0 ? 0 : i % 2 === 1 ? i : i - 1;
      return start <= 1 ? 0 : start - 2;
    });
  };
  const next = () => step(1);
  const prev = () => step(-1);

  /* Save the page (and the settings when they changed); reopening where it was saves nothing until a page turns. */
  const place = (): ReadingPatch => ({ Locator: String(index), Percent: Math.round(((lastShown + 1) / pages) * 1000) / 10, Finished: lastShown >= pages - 1 });
  const moved = useRef(!reading || loc.query.get('percent') !== null);
  const openedAt = useRef(index);
  useEffect(() => {
    if (!moved.current && index === openedAt.current) return;
    moved.current = true;
    saver.place(place(), 1200);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [index, lastShown]);
  const change = (patch: Partial<ComicSettings>) => {
    setSettings((cur) => {
      const n = { ...cur, ...patch };
      rememberComicSettings(n);
      saver.settings(n as unknown as Record<string, unknown>, place());
      return n;
    });
  };

  /* Keep the next pages coming. */
  useEffect(() => {
    if (scroll) return;
    for (let i = 1; i <= 3; i++) {
      const n = lastShown + i;
      if (n < pages) { const img = new Image(); img.src = comicPageUrl(item.Id, n); }
    }
  }, [lastShown, scroll]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey || panel) return;
      const tag = (e.target as HTMLElement | null)?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA') return;
      if (scroll) {
        if (e.key === 'Home') setIndex(0);
        return;
      }
      const fwd = rtl ? 'ArrowLeft' : 'ArrowRight';
      const bwd = rtl ? 'ArrowRight' : 'ArrowLeft';
      if (e.key === fwd || e.key === 'PageDown' || (e.key === ' ' && !e.shiftKey)) { e.preventDefault(); next(); }
      else if (e.key === bwd || e.key === 'PageUp' || (e.key === ' ' && e.shiftKey)) { e.preventDefault(); prev(); }
      else if (e.key === 'Home') setIndex(0);
      else if (e.key === 'End') setIndex(pages - 1);
      else if (e.key === 'f' || e.key === 'F') toggleFull();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  });

  const touch = useRef<{ x: number; y: number } | null>(null);
  const onClick = (e: MouseEvent) => {
    const box = (e.currentTarget as HTMLElement).getBoundingClientRect();
    const left = e.clientX < box.left + box.width / 2;
    if (left === !rtl) prev(); else next();
  };

  const series = item.SeriesName ? [item.SeriesName, item.IndexNumber !== undefined && item.IndexNumber !== null ? t('No. {n}', { n: item.IndexNumber }) : ''].filter(Boolean).join(' · ') : '';
  const sub = [authorLine(item), series].filter(Boolean).join(' · ');
  const left = pages - lastShown - 1;
  const status: [string, string, string] = [
    left > 0 ? tn(left, '{n} page left', '{n} pages left') : t('Last page'),
    spread.length > 1
      ? t('Pages {a}–{b} of {total} · {percent}%', { a: spread[0] + 1, b: lastShown + 1, total: pages, percent: Math.round(((lastShown + 1) / pages) * 100) })
      : t('Page {n} of {total} · {percent}%', { n: lastShown + 1, total: pages, percent: Math.round(((lastShown + 1) / pages) * 100) }),
    ''
  ];

  return (
    <div class={'rd rd-comic rd-theme-' + settings.theme + (settings.fit === 'width' ? ' is-fit-width' : '')}>
      <TopBar title={item.Name} sub={sub} onBack={() => back(`/details/${item.Id}`)}>
        <BarButton id="rd-settings-btn" icon="type" label={t('Reading settings')} expanded={panel} onClick={() => setPanel(!panel)} />
        <BarButton icon={full ? 'unfull' : 'full'} label={full ? t('Exit full screen') : t('Full screen')} onClick={toggleFull} />
      </TopBar>

      {scroll ? (
        <ScrollStrip item={item} pages={pages} index={index} onIndex={setIndex} />
      ) : (
        <div
          class={'rd-comic-stage' + (spread.length > 1 ? ' is-spread' : '') + (rtl ? ' is-rtl' : '')}
          onClick={onClick}
          onTouchStart={(e) => { touch.current = { x: e.changedTouches[0].clientX, y: e.changedTouches[0].clientY }; }}
          onTouchEnd={(e) => {
            const s = touch.current;
            touch.current = null;
            if (!s) return;
            const dx = e.changedTouches[0].clientX - s.x;
            if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(e.changedTouches[0].clientY - s.y) * 1.5) { e.preventDefault(); if ((dx < 0) !== rtl) next(); else prev(); }
          }}
        >
          {spread.map((n) => <img key={n} class="rd-comic-page" src={comicPageUrl(item.Id, n)} alt={t('Page {n}', { n: n + 1 })} draggable={false} />)}
        </div>
      )}
      {!scroll && <TurnButtons onPrev={prev} onNext={next} prevDisabled={index === 0} nextDisabled={lastShown >= pages - 1} rtl={rtl} />}
      <Scrubber
        page={lastShown + 1}
        total={pages}
        ticks={[]}
        rtl={rtl}
        onCommit={(p) => setIndex(p - 1)}
        valueText={(p) => t('Page {n} of {total}, {percent} percent', { n: p, total: pages, percent: Math.round((p / pages) * 100) })}
        status={status}
      />
      {panel && <ComicSettingsPanel value={settings} onChange={change} onClose={() => setPanel(false)} />}
    </div>
  );
}

/* Scroll: every page down one strip; the page most in view is the place. */
function ScrollStrip({ item, pages, index, onIndex }: { item: BaseItem; pages: number; index: number; onIndex: (n: number) => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const current = useRef(index);
  useEffect(() => {
    ref.current?.querySelector<HTMLElement>(`[data-page="${index}"]`)?.scrollIntoView({ block: 'start' });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  useEffect(() => {
    if (index === current.current) return;
    ref.current?.querySelector<HTMLElement>(`[data-page="${index}"]`)?.scrollIntoView({ block: 'start' });
    current.current = index;
  }, [index]);
  useEffect(() => {
    const root = ref.current;
    if (!root) return;
    const seen = new Map<number, number>();
    const io = new IntersectionObserver((entries) => {
      entries.forEach((e) => seen.set(Number((e.target as HTMLElement).dataset.page), e.intersectionRatio));
      let best = current.current;
      let ratio = 0;
      seen.forEach((r, n) => { if (r > ratio) { ratio = r; best = n; } });
      if (best !== current.current) { current.current = best; onIndex(best); }
    }, { root, threshold: [0, 0.25, 0.5, 0.75, 1] });
    root.querySelectorAll('[data-page]').forEach((el) => io.observe(el));
    return () => io.disconnect();
  }, [pages]);
  return (
    <div ref={ref} class="rd-comic-strip">
      {Array.from({ length: pages }, (_, n) => (
        <img key={n} data-page={n} class="rd-comic-page" src={comicPageUrl(item.Id, n)} alt={t('Page {n}', { n: n + 1 })} loading="lazy" draggable={false} />
      ))}
    </div>
  );
}

export function NoPages({ item }: { item: BaseItem }) {
  return (
    <div class="rd rd-theme-black">
      <TopBar title={item.Name} onBack={() => back(`/details/${item.Id}`)} />
      <ReaderMessage title={t('This comic has no pages')} text={t('Seedy found no images in the file.')} />
    </div>
  );
}
