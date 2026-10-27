import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import ePub, { EpubCFI } from 'epubjs';
import type Book from 'epubjs/types/book';
import type Rendition from 'epubjs/types/rendition';
import type Section from 'epubjs/types/section';
import type Contents from 'epubjs/types/contents';
import serifLatin from '@fontsource-variable/noto-serif/files/noto-serif-latin-wght-normal.woff2?url';
import serifLatinExt from '@fontsource-variable/noto-serif/files/noto-serif-latin-ext-wght-normal.woff2?url';
import serifItalic from '@fontsource-variable/noto-serif/files/noto-serif-latin-wght-italic.woff2?url';
import serifItalicExt from '@fontsource-variable/noto-serif/files/noto-serif-latin-ext-wght-italic.woff2?url';
import sansLatin from '@fontsource-variable/noto-sans/files/noto-sans-latin-wght-normal.woff2?url';
import sansLatinExt from '@fontsource-variable/noto-sans/files/noto-sans-latin-ext-wght-normal.woff2?url';
import sansItalic from '@fontsource-variable/noto-sans/files/noto-sans-latin-wght-italic.woff2?url';
import sansItalicExt from '@fontsource-variable/noto-sans/files/noto-sans-latin-ext-wght-italic.woff2?url';
import type { BaseItem } from '../../../api/types';
import { t, tn } from '../../../lib/i18n';
import { back, setQuery, useLocation } from '../../../lib/router';
import { getBookFile, authorLine, type BookInfo, type Reading } from '../api';
import { tenMinutes } from '../parts';
import {
  BarButton, EpubSettingsPanel, ReaderMessage, Scrubber, SidePanel, TopBar, TurnButtons, useFullscreen
} from './chrome';
import { CHARS_PER_PAGE, buildPageModel, loadSection, offsetOf, pointAt, sectionForPage, type PageModel } from './pages';
import { useReadingSaver } from './saver';
import { COLUMN, LINE_HEIGHT, THEMES, readEpubSettings, rememberEpubSettings, type Bookmark, type EpubSettings } from './settings';

/* The EPUB reader (Book reader board), on epub.js. The book file comes from /Seedy/Books/{id}/File and opens as an
   archive in the browser. Pages are counted the server's way (see pages.ts), so the scrubber, "Page 190 of 412" and
   the details page agree; the place is saved as a CFI with the percent, chapter and the reading settings. */

const LATIN = 'U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD';
const LATIN_EXT = 'U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,U+0308,U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,U+A720-A7FF';
const abs = (u: string) => new URL(u, window.location.href).href;
const face = (family: string, url: string, range: string, style: string) =>
  `@font-face{font-family:'${family}';font-style:${style};font-weight:100 900;font-display:block;src:url(${abs(url)}) format('woff2');unicode-range:${range}}`;
const FONT_FACES = () => [
  face('Seedy Serif', serifLatin, LATIN, 'normal'), face('Seedy Serif', serifLatinExt, LATIN_EXT, 'normal'),
  face('Seedy Serif', serifItalic, LATIN, 'italic'), face('Seedy Serif', serifItalicExt, LATIN_EXT, 'italic'),
  face('Seedy Sans', sansLatin, LATIN, 'normal'), face('Seedy Sans', sansLatinExt, LATIN_EXT, 'normal'),
  face('Seedy Sans', sansItalic, LATIN, 'italic'), face('Seedy Sans', sansItalicExt, LATIN_EXT, 'italic')
].join('\n');

const GAP = 80;
const TWO_COLUMN: Record<EpubSettings['margins'], number> = { narrow: 600, normal: 520, wide: 440 };

/* Book styles: quiet defaults first (the book's own CSS wins over them), then what the reader settings impose. */
function baseCss() {
  return `${FONT_FACES()}
h1{font-size:1.8em;line-height:1.1;margin:0 0 22px}
p{margin:0 0 16px}
img,svg,video{max-width:100%;height:auto}`;
}

function themeCss(s: EpubSettings) {
  const th = THEMES[s.theme];
  const family = s.font === 'serif' ? "'Seedy Serif', 'Noto Serif', Georgia, serif" : s.font === 'sans' ? "'Seedy Sans', 'Noto Sans', system-ui, sans-serif" : '';
  return `html{background:${th.bg}!important;color-scheme:${s.theme === 'sepia' || s.theme === 'light' ? 'light' : 'dark'}}
html,body{color:${th.ink}!important}
body{background:transparent!important}
body{font-size:${s.size}px!important;line-height:${LINE_HEIGHT[s.spacing]}!important;-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility${family ? `;font-family:${family}!important` : ''}}
body *{color:inherit!important;background-color:transparent!important;border-color:currentColor}
body p,body li,body blockquote,body dd,body dt,body div,body span{line-height:inherit!important}
body h1,body h2,body h3,body h4,body h5,body h6{color:${th.heading}!important;line-height:1.15!important}
body a{color:${th.heading}!important}
${family ? `body *:not(pre):not(code):not(kbd):not(samp){font-family:inherit!important}
body h1{font-size:1.79em!important;font-weight:700!important;line-height:1.1!important;margin:0 0 22px!important}
body p{margin:0 0 0.84em!important;text-align:start!important}
body :has(+ h1){font-family:'Seedy Sans','Noto Sans',system-ui,sans-serif!important;font-size:12px!important;font-weight:800!important;letter-spacing:0.14em!important;text-transform:uppercase!important;text-indent:0!important;color:${th.label}!important;margin:0 0 10px!important;line-height:1.4!important}` : ''}
::selection{background:rgba(255,159,255,0.35)}`;
}

function styleContents(contents: Contents, s: EpubSettings) {
  const doc = contents.document;
  if (!doc?.head) return;
  let base = doc.getElementById('seedy-rd-base') as HTMLStyleElement | null;
  if (!base) {
    base = doc.createElement('style');
    base.id = 'seedy-rd-base';
    base.textContent = baseCss();
    doc.head.insertBefore(base, doc.head.firstChild);
  }
  let over = doc.getElementById('seedy-rd') as HTMLStyleElement | null;
  if (!over) {
    over = doc.createElement('style');
    over.id = 'seedy-rd';
    doc.head.appendChild(over);
  }
  over.textContent = themeCss(s);
}

interface Chapter { title: string; href: string; page: number }

interface Pos { cfi: string; index: number; page: number; atStart: boolean; atEnd: boolean }

export function EpubReader({ item, info, reading }: { item: BaseItem; info?: BookInfo; reading?: Reading }) {
  const loc = useLocation();
  const host = useRef<HTMLDivElement>(null);
  const bookRef = useRef<Book | null>(null);
  const rendRef = useRef<Rendition | null>(null);
  const lastCfi = useRef<string | null>(reading?.Locator?.startsWith('epubcfi(') ? reading.Locator : null);
  const startPercent = useRef<number | null>(loc.query.get('percent') !== null ? Number(loc.query.get('percent')) : null);
  const [phase, setPhase] = useState<'loading' | 'ready' | 'error'>('loading');
  const [error, setError] = useState('');
  const [settings, setSettings] = useState<EpubSettings>(() => readEpubSettings(reading?.Settings));
  const settingsRef = useRef(settings);
  settingsRef.current = settings;
  const [model, setModel] = useState<PageModel | null>(null);
  const modelRef = useRef<PageModel | null>(null);
  modelRef.current = model;
  const [toc, setToc] = useState<Chapter[]>([]);
  const [pos, setPos] = useState<Pos | null>(null);
  const [panel, setPanel] = useState<null | 'settings' | 'toc' | 'search'>(null);
  const [full, toggleFull] = useFullscreen();
  const saver = useReadingSaver(item.Id);
  const author = authorLine(item);

  /* ── Open the book ── */
  useEffect(() => {
    const ctrl = new AbortController();
    let book: Book | null = null;
    (async () => {
      try {
        const buf = await getBookFile(item.Id, ctrl.signal);
        if (ctrl.signal.aborted) return;
        book = ePub(buf as unknown as string);
        bookRef.current = book;
        await book.ready;
        if (ctrl.signal.aborted) return;
        const nav = await book.loaded.navigation;
        const flat: { label: string; href: string }[] = [];
        const add = (list: { label: string; href: string; subitems?: unknown[] }[]) => list.forEach((n) => { flat.push({ label: n.label.trim(), href: n.href }); if (n.subitems?.length) add(n.subitems as typeof list); });
        add(nav.toc as unknown as { label: string; href: string; subitems?: unknown[] }[]);
        setPhase('ready');
        const m = await buildPageModel(book);
        if (ctrl.signal.aborted) return;
        setModel(m);
        // Chapters: the server's list when it has one (same pages), else the book's own table of contents.
        const fromServer = (info?.Chapters ?? []).filter((c) => c.Page > 0).map((c) => ({ title: c.Title, href: c.Href, page: c.Page }));
        const fromBook = flat.map((n) => {
          const file = n.href.split('#')[0];
          const sec = m.sections.find((s) => s.href === file || s.href.endsWith('/' + file) || file.endsWith(s.href));
          return { title: n.label, href: n.href, page: sec?.first ?? 0 };
        }).filter((c) => c.page > 0);
        setToc((fromServer.length ? fromServer : fromBook).sort((a, b) => a.page - b.page));
      } catch (e) {
        if (ctrl.signal.aborted) return;
        setError((e as Error)?.message ?? '');
        setPhase('error');
      }
    })();
    return () => {
      ctrl.abort();
      rendRef.current?.destroy();
      rendRef.current = null;
      book?.destroy();
      bookRef.current = null;
    };
  }, [item.Id]);

  /* ── Page numbers ── */
  const pageFromLocation = (cfi: string, index: number): number => {
    const m = modelRef.current;
    const sec = m?.sections.find((s) => s.index === index);
    if (!m || !sec) return 0;
    const rend = rendRef.current;
    const contents = (rend?.getContents() as unknown as Contents[] | undefined)?.find((c) => (c as unknown as { sectionIndex: number }).sectionIndex === index);
    let chars = 0;
    try {
      if (contents?.document) {
        const range = new EpubCFI(cfi).toRange(contents.document);
        if (range) chars = offsetOf(contents.document, range.startContainer, range.startOffset);
      }
    } catch { /* keep the section's first page */ }
    return sec.first + Math.min(sec.pages - 1, Math.floor(chars / CHARS_PER_PAGE));
  };

  const cfiForPage = async (page: number): Promise<string | null> => {
    const book = bookRef.current;
    const m = modelRef.current;
    if (!book || !m) return null;
    const sec = sectionForPage(m, page);
    if (!sec) return null;
    const section = book.spine.get(sec.index) as Section;
    const chars = (page - sec.first) * CHARS_PER_PAGE;
    if (chars <= 0) return section.href;
    const doc = await loadSection(book, section);
    if (!doc) return section.href;
    const point = pointAt(doc, chars);
    if (!point) return section.href;
    const range = doc.createRange();
    range.setStart(point.node, point.offset);
    range.collapse(true);
    return section.cfiFromRange(range);
  };

  /* ── Rendition: made again when the layout or the margins change ── */
  useEffect(() => {
    const book = bookRef.current;
    const el = host.current;
    if (phase !== 'ready' || !book || !el) return;
    const s = settingsRef.current;
    const rend = book.renderTo(el, {
      width: '100%', height: '100%',
      flow: s.layout === 'scroll' ? 'scrolled' : 'paginated',
      manager: s.layout === 'scroll' ? 'continuous' : 'default',
      spread: s.layout === 'two' ? 'always' : 'none',
      minSpreadWidth: 0,
      allowScriptedContent: false,
      gap: GAP
    } as Parameters<Book['renderTo']>[1] & { gap: number });
    rendRef.current = rend;
    rend.hooks.content.register((contents: Contents) => styleContents(contents, settingsRef.current));
    rend.on('relocated', (l: { start: { cfi: string; index: number }; atStart?: boolean; atEnd?: boolean }) => {
      lastCfi.current = l.start.cfi;
      setPos({ cfi: l.start.cfi, index: l.start.index, page: pageFromLocation(l.start.cfi, l.start.index), atStart: !!l.atStart, atEnd: !!l.atEnd });
    });
    const keys = (e: KeyboardEvent) => onKey(e);
    rend.on('keydown', keys);
    rend.on('click', (e: MouseEvent) => onPageClick(e));
    rend.on('touchstart', (e: TouchEvent) => { touch.current = { x: e.changedTouches[0].screenX, y: e.changedTouches[0].screenY, at: Date.now() }; });
    rend.on('touchend', (e: TouchEvent) => onSwipe(e.changedTouches[0].screenX, e.changedTouches[0].screenY));
    void (async () => {
      let target: string | undefined = lastCfi.current ?? undefined;
      if (startPercent.current !== null && Number.isFinite(startPercent.current)) {
        // From the audiobook ("Read the EPUB from here"): the same share of the book, once its pages are counted.
        const m = modelRef.current ?? await waitFor(() => modelRef.current);
        if (m) {
          const page = Math.min(m.total, Math.max(1, Math.ceil((startPercent.current / 100) * m.total)));
          target = (await cfiForPage(page)) ?? target;
        }
        startPercent.current = null;
        setQuery({ percent: undefined });
      }
      try { await rend.display(target); } catch { await rend.display(); }
    })();
    return () => {
      rend.destroy();
      if (rendRef.current === rend) rendRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase, settings.layout, settings.margins]);

  /* Restyle the pages in place when the look changes, then come back to the same place. */
  const styleKey = `${settings.theme}|${settings.font}|${settings.size}|${settings.spacing}`;
  const firstStyle = useRef(true);
  useEffect(() => {
    if (firstStyle.current) { firstStyle.current = false; return; }
    const rend = rendRef.current;
    if (!rend) return;
    (rend.getContents() as unknown as Contents[]).forEach((c) => styleContents(c, settings));
    const cfi = lastCfi.current;
    const tm = window.setTimeout(() => { if (cfi) void rend.display(cfi).catch(() => undefined); }, 60);
    return () => window.clearTimeout(tm);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [styleKey]);

  /* The page number once pages are counted (the first page may show before). */
  useEffect(() => {
    if (!model || !pos) return;
    const page = pageFromLocation(pos.cfi, pos.index);
    if (page !== pos.page) setPos({ ...pos, page });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [model]);

  /* ── Where we are ── */
  const total = model?.total ?? info?.Pages ?? 0;
  const page = pos?.page || 0;
  const chapterIdx = useMemo(() => {
    let ci = -1;
    toc.forEach((c, i) => { if (c.page <= page) ci = i; });
    return ci;
  }, [toc, page]);
  const chapter = chapterIdx >= 0 ? toc[chapterIdx] : undefined;
  const nextChapterPage = chapterIdx + 1 < toc.length ? toc[chapterIdx + 1].page : total + 1;
  const fraction = total ? Math.min(1, page / total) : 0;
  const percent = pos?.atEnd ? 100 : Math.round(fraction * 1000) / 10;

  /* Save the place a moment after it stops moving. */
  useEffect(() => {
    if (!pos || !page) return;
    saver.place({
      Locator: pos.cfi,
      Percent: percent,
      Chapter: chapter?.title,
      ChapterIndex: chapterIdx >= 0 ? chapterIdx + 1 : undefined,
      ChapterCount: toc.length || undefined,
      Finished: !!pos.atEnd
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pos?.cfi, page, chapterIdx]);

  /* ── Turning pages ── */
  const next = () => { void rendRef.current?.next(); };
  const prev = () => { void rendRef.current?.prev(); };
  const goTo = async (target: string | null) => { if (target) await rendRef.current?.display(target).catch(() => undefined); };
  const goToPage = async (p: number) => goTo(await cfiForPage(p));

  const panelRef = useRef(panel);
  panelRef.current = panel;
  const onKey = (e: KeyboardEvent) => {
    if (e.defaultPrevented || e.altKey || e.ctrlKey || e.metaKey) return;
    const tag = (e.target as HTMLElement | null)?.tagName;
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
    if (panelRef.current && e.key !== 'Escape') return;
    const scroll = settingsRef.current.layout === 'scroll';
    if (e.key === 'ArrowRight' || e.key === 'PageDown' || (e.key === ' ' && !e.shiftKey && !scroll)) { e.preventDefault(); next(); }
    else if (e.key === 'ArrowLeft' || e.key === 'PageUp' || (e.key === ' ' && e.shiftKey && !scroll)) { e.preventDefault(); prev(); }
    else if (e.key === 'f' || e.key === 'F') toggleFull();
  };
  useEffect(() => {
    const fn = (e: KeyboardEvent) => onKey(e);
    window.addEventListener('keydown', fn);
    return () => window.removeEventListener('keydown', fn);
  });

  /* Tapping the left or right third of the page turns it (not while selecting text or on a link). */
  const onPageClick = (e: MouseEvent) => {
    if (settingsRef.current.layout === 'scroll' || panelRef.current) return;
    const win = (e.view ?? null) as Window | null;
    if (win?.getSelection()?.toString()) return;
    if ((e.target as Element | null)?.closest?.('a')) return;
    const frame = win?.frameElement as HTMLElement | null;
    const box = host.current?.getBoundingClientRect();
    if (!frame || !box) return;
    const x = e.clientX + frame.getBoundingClientRect().left;
    if (x < box.left + box.width / 3) prev();
    else if (x > box.right - box.width / 3) next();
  };
  const touch = useRef<{ x: number; y: number; at: number } | null>(null);
  const onSwipe = (x: number, y: number) => {
    const s = touch.current;
    touch.current = null;
    if (!s || settingsRef.current.layout === 'scroll') return;
    const dx = x - s.x;
    if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(y - s.y) * 1.5 && Date.now() - s.at < 800) { if (dx < 0) next(); else prev(); }
  };

  /* ── Settings and bookmarks ── */
  const change = (patch: Partial<EpubSettings>) => {
    setSettings((cur) => {
      const nextS = { ...cur, ...patch };
      rememberEpubSettings(nextS);
      saver.settings(nextS as unknown as Record<string, unknown>);
      return nextS;
    });
  };
  const marked = settings.bookmarks.find((b) => b.page === page);
  const toggleBookmark = () => {
    if (!pos) return;
    const list = marked
      ? settings.bookmarks.filter((b) => b !== marked)
      : [...settings.bookmarks, { cfi: pos.cfi, page, label: chapter?.title ?? item.Name, at: Date.now() } satisfies Bookmark].sort((a, b) => a.page - b.page);
    change({ bookmarks: list });
  };

  /* ── Labels ── */
  const chapterName = (c: Chapter, i: number) => (/^(chapter|chapitre|part|partie|book|livre|prologue|epilogue|épilogue)\b|^\d/i.test(c.title) ? c.title : t('Chapter {n}, {title}', { n: i + 1, title: c.title }));
  const sub = [author, chapter ? chapterName(chapter, chapterIdx) : ''].filter(Boolean).join(' · ');
  const leftInChapter = Math.max(1, nextChapterPage - page);
  const status: [string, string, string] = page && total ? [
    chapter ? (leftInChapter <= 1 ? t('Chapter {n} · last page in chapter', { n: chapterIdx + 1 }) : t('Chapter {n} · {count}', { n: chapterIdx + 1, count: tn(leftInChapter, '{n} page left in chapter', '{n} pages left in chapter') })) : '',
    t('Page {n} of {total} · {percent}%', { n: page, total, percent: Math.round(fraction * 100) }),
    info?.Minutes ? (pos?.atEnd ? t('The end of the book') : t('About {time} left in the book', { time: tenMinutes(info.Minutes * (1 - (page - 1) / total)) })) : ''
  ] : ['', '', ''];
  const ticks = total > 1 ? toc.filter((c) => c.page > 1).map((c) => (c.page - 1) / (total - 1)) : [];

  const s = settings;
  const width = s.layout === 'two' ? 2 * (TWO_COLUMN[s.margins] + GAP) : COLUMN[s.margins] + GAP;

  return (
    <div class={'rd rd-theme-' + s.theme}>
      <TopBar title={item.Name} sub={sub} onBack={() => back(`/details/${item.Id}`)}>
        <BarButton id="rd-toc-btn" icon="toc" label={t('Contents')} expanded={panel === 'toc'} onClick={() => setPanel(panel === 'toc' ? null : 'toc')} />
        <BarButton id="rd-search-btn" icon="search" label={t('Search in book')} expanded={panel === 'search'} onClick={() => setPanel(panel === 'search' ? null : 'search')} />
        <BarButton icon="bookmark" label={marked ? t('Remove bookmark') : t('Add bookmark')} pressed={!!marked} filled={!!marked} onClick={toggleBookmark} />
        <BarButton id="rd-settings-btn" icon="type" label={t('Reading settings')} expanded={panel === 'settings'} onClick={() => setPanel(panel === 'settings' ? null : 'settings')} />
        <BarButton icon={full ? 'unfull' : 'full'} label={full ? t('Exit full screen') : t('Full screen')} onClick={toggleFull} />
      </TopBar>

      <div
        ref={host}
        class={'rd-pages' + (s.layout === 'scroll' ? ' is-scroll' : '')}
        style={{ '--rd-w': width + 'px' }}
        aria-label={chapter ? chapterName(chapter, chapterIdx) : item.Name}
        role="document"
      />
      {phase === 'loading' && <div class="rd-loading"><span class="spinner" aria-label={t('Opening the book')} /></div>}
      {phase === 'error' && (
        <ReaderMessage title={t('Couldn’t open this book')} text={error ? t('The file may be damaged or not an EPUB. ({message})', { message: error }) : t('The file may be damaged or not an EPUB.')} />
      )}
      {phase === 'ready' && s.layout !== 'scroll' && <TurnButtons onPrev={prev} onNext={next} prevDisabled={pos?.atStart} nextDisabled={pos?.atEnd} />}

      {phase === 'ready' && (
        <Scrubber
          page={page || 1}
          total={total || 1}
          ticks={ticks}
          onCommit={(p) => void goToPage(p)}
          valueText={(p) => t('Page {n} of {total}, {percent} percent', { n: p, total, percent: total ? Math.round((p / total) * 100) : 0 })}
          status={status}
        />
      )}

      {panel === 'settings' && <EpubSettingsPanel value={settings} onChange={change} onClose={() => setPanel(null)} />}
      {panel === 'toc' && (
        <SidePanel title={t('Contents')} label={t('Contents')} anchorId="rd-toc-btn" onClose={() => setPanel(null)}>
          <ol class="rs-list">
            {toc.map((c, i) => (
              <li key={c.href + i}>
                <button type="button" class={'rs-list-item' + (i === chapterIdx ? ' is-current' : '')} aria-current={i === chapterIdx ? 'true' : undefined} onClick={() => { setPanel(null); void goTo(c.href); }}>
                  <span class="rs-list-title">{c.title}</span>
                  <span class="rs-list-page">{c.page}</span>
                </button>
              </li>
            ))}
            {!toc.length && <li class="rs-empty">{model ? t('This book has no table of contents.') : t('Counting pages…')}</li>}
          </ol>
          {settings.bookmarks.length > 0 && (
            <>
              <span class="rs-label is-list">{t('Bookmarks')}</span>
              <ol class="rs-list">
                {settings.bookmarks.map((b) => (
                  <li key={b.cfi}>
                    <button type="button" class="rs-list-item" onClick={() => { setPanel(null); void goTo(b.cfi); }}>
                      <span class="rs-list-title">{b.label}</span>
                      <span class="rs-list-page">{b.page}</span>
                    </button>
                  </li>
                ))}
              </ol>
            </>
          )}
        </SidePanel>
      )}
      {panel === 'search' && <SearchPanel book={bookRef.current} model={model} onClose={() => setPanel(null)} onPick={(cfi) => { setPanel(null); void goTo(cfi); }} />}
    </div>
  );
}

function waitFor<T>(get: () => T | null, ms = 30000): Promise<T | null> {
  return new Promise((resolve) => {
    const start = Date.now();
    const tick = () => {
      const v = get();
      if (v || Date.now() - start > ms) resolve(v);
      else window.setTimeout(tick, 100);
    };
    tick();
  });
}

/* ───────── Search in the book ───────── */

interface Hit { cfi: string; excerpt: string; page: number }

function SearchPanel({ book, model, onClose, onPick }: { book: Book | null; model: PageModel | null; onClose: () => void; onPick: (cfi: string) => void }) {
  const [q, setQ] = useState('');
  const [hits, setHits] = useState<Hit[] | null>(null);
  const [busy, setBusy] = useState(false);
  const run = useRef(0);
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => { input.current?.focus(); }, []);
  useEffect(() => {
    const term = q.trim();
    const id = ++run.current;
    if (term.length < 2 || !book) { setHits(null); setBusy(false); return; }
    const tm = window.setTimeout(async () => {
      setBusy(true);
      const found: Hit[] = [];
      const items = (book.spine as unknown as { spineItems: Section[] }).spineItems;
      for (const section of items) {
        if (run.current !== id) return;
        const doc = await loadSection(book, section);
        if (!doc) continue;
        const results = (section as unknown as { find: (q: string) => { cfi: string; excerpt: string }[] }).find(term) ?? [];
        const sec = model?.sections.find((x) => x.index === section.index);
        for (const r of results) {
          let page = sec?.first ?? 0;
          try {
            const range = new EpubCFI(r.cfi).toRange(doc);
            if (range && sec) page = sec.first + Math.min(sec.pages - 1, Math.floor(offsetOf(doc, range.startContainer, range.startOffset) / CHARS_PER_PAGE));
          } catch { /* keep the section's page */ }
          found.push({ cfi: r.cfi, excerpt: r.excerpt.trim(), page });
          if (found.length >= 200) break;
        }
        section.unload();
        if (found.length >= 200) break;
        setHits(found.slice());
      }
      if (run.current === id) { setHits(found); setBusy(false); }
    }, 300);
    return () => window.clearTimeout(tm);
  }, [q, book]);
  const mark = (text: string) => {
    const term = q.trim();
    const i = text.toLowerCase().indexOf(term.toLowerCase());
    if (i < 0) return text;
    return <>{text.slice(0, i)}<mark>{text.slice(i, i + term.length)}</mark>{text.slice(i + term.length)}</>;
  };
  return (
    <SidePanel title={t('Search in book')} label={t('Search in book')} anchorId="rd-search-btn" onClose={onClose}>
      <label class="rs-search">
        <svg viewBox="0 0 24 24" width="18" height="18" class="rd-ic" aria-hidden="true"><circle cx="11" cy="11" r="7" /><path d="M20 20l-4-4" /></svg>
        <input ref={input} type="search" value={q} placeholder={t('Words or a phrase')} aria-label={t('Search in book')} onInput={(e) => setQ(e.currentTarget.value)} onKeyDown={(e) => e.stopPropagation()} />
      </label>
      {hits !== null && (
        <span class="rs-count" aria-live="polite">{busy ? t('Searching…') : hits.length ? tn(hits.length, '{n} result', '{n} results') : t('Nothing found')}</span>
      )}
      <ol class="rs-list is-hits">
        {(hits ?? []).map((h, i) => (
          <li key={h.cfi + i}>
            <button type="button" class="rs-list-item is-hit" onClick={() => onPick(h.cfi)}>
              <span class="rs-list-title">{mark(h.excerpt)}</span>
              {h.page > 0 && <span class="rs-list-page">{h.page}</span>}
            </button>
          </li>
        ))}
      </ol>
    </SidePanel>
  );
}
