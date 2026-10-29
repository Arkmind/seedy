/* Seedy's EPUB reader for the Android app (board "Android · Reader"): epub.js the way the web reader drives it
   (web/src/screens/books/reader/epub.tsx and pages.ts), so the place saved is the same EPUB CFI on every device and
   "Page 190 of 412" is counted the server's way. The app draws the frame around this page; this page shows the book,
   turns its pages on taps and swipes, and tells the app where it is through window.SeedyReader.post(json). */
(() => {
  'use strict';

  /* ───────── Fonts and styles ───────── */

  const LATIN = 'U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD';
  const LATIN_EXT = 'U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,U+0308,U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,U+A720-A7FF';
  const FONTS = new URL('fonts/', location.href).href;
  const face = (family, file, range, style) =>
    `@font-face{font-family:'${family}';font-style:${style};font-weight:100 900;font-display:block;src:url(${FONTS}${file}) format('woff2');unicode-range:${range}}`;
  const FONT_FACES = [
    face('Seedy Serif', 'noto-serif-latin-wght-normal.woff2', LATIN, 'normal'),
    face('Seedy Serif', 'noto-serif-latin-ext-wght-normal.woff2', LATIN_EXT, 'normal'),
    face('Seedy Serif', 'noto-serif-latin-wght-italic.woff2', LATIN, 'italic'),
    face('Seedy Serif', 'noto-serif-latin-ext-wght-italic.woff2', LATIN_EXT, 'italic'),
    face('Seedy Sans', 'noto-sans-latin-wght-normal.woff2', LATIN, 'normal'),
    face('Seedy Sans', 'noto-sans-latin-ext-wght-normal.woff2', LATIN_EXT, 'normal'),
    face('Seedy Sans', 'noto-sans-latin-wght-italic.woff2', LATIN, 'italic'),
    face('Seedy Sans', 'noto-sans-latin-ext-wght-italic.woff2', LATIN_EXT, 'italic')
  ].join('\n');

  const THEMES = {
    dark: { bg: '#111111', ink: '#d8d4cc', heading: '#f0ece4', label: '#8c8c8c' },
    black: { bg: '#000000', ink: '#c8c8c8', heading: '#e6e6e6', label: '#7a7a7a' },
    sepia: { bg: '#f1e6d0', ink: '#4a3b28', heading: '#2e2416', label: '#8a7454' },
    light: { bg: '#fafafa', ink: '#1e1e1e', heading: '#000000', label: '#7a7a7a' }
  };
  /* The board: 17 px text on 1.65 lines, 26 px from the screen's edges. */
  const LINE_HEIGHT = { tight: 1.45, normal: 1.65, airy: 1.85 };
  const MARGIN = { narrow: 16, normal: 26, wide: 40 };
  const theme = (s) => THEMES[s.theme] || THEMES.dark;
  const scrolls = (s) => s.layout === 'scroll';

  /* Book styles: quiet defaults first (the book's own CSS wins over them), then what the reader settings impose. */
  const baseCss = () => `${FONT_FACES}
h1{font-size:1.65em;line-height:1.1;margin:0 0 16px}
p{margin:0 0 12px}
img,svg,video{max-width:100%;height:auto}`;

  function themeCss(s) {
    const th = theme(s);
    const family = s.font === 'serif' ? "'Seedy Serif', 'Noto Serif', Georgia, serif" : s.font === 'sans' ? "'Seedy Sans', 'Noto Sans', system-ui, sans-serif" : '';
    const side = MARGIN[s.margins] || 26;
    return `html{background:${th.bg}!important;color-scheme:${s.theme === 'sepia' || s.theme === 'light' ? 'light' : 'dark'}}
html,body{color:${th.ink}!important}
body{background:transparent!important;padding-top:0!important;padding-bottom:0!important}
${scrolls(s) ? `body{padding-left:${side}px!important;padding-right:${side}px!important;padding-bottom:${s.size * 2}px!important}` : ''}
body{font-size:${s.size}px!important;line-height:${LINE_HEIGHT[s.spacing] || 1.65}!important;-webkit-font-smoothing:antialiased;text-rendering:optimizeLegibility${family ? `;font-family:${family}!important` : ''}}
body *{color:inherit!important;background-color:transparent!important;border-color:currentColor}
body p,body li,body blockquote,body dd,body dt,body div,body span{line-height:inherit!important}
body h1,body h2,body h3,body h4,body h5,body h6{color:${th.heading}!important;line-height:1.15!important}
body a{color:${th.heading}!important}
${family ? `body *:not(pre):not(code):not(kbd):not(samp){font-family:inherit!important}
body h1{font-size:1.6471em!important;font-weight:700!important;line-height:1.1!important;margin:0 0 ${Math.round(s.size * 16 / 17)}px!important}
body p{margin:0 0 0.7059em!important;text-align:start!important}
body :has(+ h1){font-family:'Seedy Sans','Noto Sans',system-ui,sans-serif!important;font-size:11px!important;font-weight:800!important;letter-spacing:0.14em!important;text-transform:uppercase!important;text-indent:0!important;color:${th.label}!important;margin:0 0 8px!important;line-height:1.362!important}` : ''}
::selection{background:rgba(255,159,255,0.35)}`;
  }

  function styleContents(contents, s) {
    const doc = contents.document;
    if (!doc || !doc.head) return;
    let base = doc.getElementById('seedy-rd-base');
    if (!base) {
      base = doc.createElement('style');
      base.id = 'seedy-rd-base';
      base.textContent = baseCss();
      doc.head.insertBefore(base, doc.head.firstChild);
    }
    let over = doc.getElementById('seedy-rd');
    if (!over) {
      over = doc.createElement('style');
      over.id = 'seedy-rd';
      doc.head.appendChild(over);
    }
    over.textContent = themeCss(s);
  }

  /* ───────── Pages, the server's way (pages.ts) ───────── */

  const CHARS_PER_PAGE = 1600;
  const SPACE = /\s/;

  function walk(root, visit) {
    let lastSpace = true;
    let stop = false;
    const space = (node, offset) => {
      if (lastSpace) return;
      lastSpace = true;
      if (visit(node, offset)) stop = true;
    };
    const rec = (node) => {
      for (let child = node.firstChild; child && !stop; child = child.nextSibling) {
        if (child.nodeType === 3 || child.nodeType === 4) {
          const text = child.nodeValue || '';
          for (let i = 0; i < text.length && !stop; i++) {
            if (SPACE.test(text[i])) space(child, i);
            else {
              lastSpace = false;
              if (visit(child, i)) stop = true;
            }
          }
        } else if (child.nodeType === 1) {
          space(child, 0);
          if (stop) return;
          rec(child);
          if (stop) return;
          space(null, 0);
        } else if (child.nodeType === 8) {
          space(null, 0);
        }
      }
    };
    rec(root);
  }

  const bodyOf = (doc) =>
    (doc.documentElement ? doc : doc.ownerDocument)?.querySelector('body') ?? doc.querySelector?.('body') ?? null;

  function textLength(doc) {
    const body = bodyOf(doc);
    if (!body) return 0;
    let n = 0;
    let trailingSpace = false;
    walk(body, (node, offset) => {
      n++;
      trailingSpace = !node || (node.nodeType !== 3 && node.nodeType !== 4) || SPACE.test((node.nodeValue || '')[offset] || '');
      return false;
    });
    return trailingSpace ? n - 1 : n;
  }

  function offsetOf(doc, node, offset) {
    const body = bodyOf(doc);
    if (!body) return 0;
    let target = node;
    let at = offset;
    if (node.nodeType === 1 && node.childNodes.length) {
      target = node.childNodes[Math.min(offset, node.childNodes.length - 1)];
      at = offset >= node.childNodes.length ? Number.MAX_SAFE_INTEGER : 0;
    }
    let n = 0;
    walk(body, (cur, i) => {
      if (cur && (cur === target || target.contains(cur)) && (cur !== target || i >= at)) return true;
      n++;
      return false;
    });
    return n;
  }

  function pointAt(doc, chars) {
    const body = bodyOf(doc);
    if (!body) return null;
    let n = 0;
    let hit = null;
    let lastText = null;
    walk(body, (node, offset) => {
      if (node && (node.nodeType === 3 || node.nodeType === 4)) lastText = { node, offset };
      if (n >= chars && node && (node.nodeType === 3 || node.nodeType === 4)) { hit = { node, offset }; return true; }
      n++;
      return false;
    });
    return hit || lastText;
  }

  async function loadSection(b, section) {
    try {
      const el = await section.load(b.load.bind(b));
      return section.document || el?.ownerDocument || null;
    } catch (e) {
      return null;
    }
  }

  async function buildPageModel(b) {
    const sections = [];
    let total = 0;
    for (const s of b.spine.spineItems) {
      const doc = await loadSection(b, s);
      const len = doc ? textLength(doc) : 0;
      const pages = Math.max(1, Math.ceil(len / CHARS_PER_PAGE));
      sections.push({ index: s.index, href: s.href, first: total + 1, pages });
      total += pages;
      s.unload();
    }
    return { sections, total };
  }

  const sectionForPage = (m, page) =>
    m.sections.find((s) => page >= s.first && page < s.first + s.pages) || m.sections[m.sections.length - 1];

  /* ───────── The app ───────── */

  const post = (msg) => {
    try { window.SeedyReader.post(JSON.stringify(msg)); } catch (e) { /* outside the app */ }
  };

  let book = null;
  let rend = null;
  let model = null;
  let settings = null;
  let lastCfi = null;
  let startPercent = null;
  let chapters = [];
  let flat = [];
  let pos = null;
  /* After going to a page by number (the scrubber, a saved percent), that number shows until the page turns: the screen
     holding a page's first words usually starts a little before them. */
  let jump = null;
  let spineHooks = null;

  const host = () => document.getElementById('book');

  function pageFromLocation(cfi, index) {
    const sec = model?.sections.find((s) => s.index === index);
    if (!model || !sec) return 0;
    const contents = (rend?.getContents() || []).find((c) => c.sectionIndex === index);
    let chars = 0;
    try {
      if (contents?.document) {
        const range = new ePub.CFI(cfi).toRange(contents.document);
        if (range) chars = offsetOf(contents.document, range.startContainer, range.startOffset);
      }
    } catch (e) { /* keep the section's first page */ }
    return sec.first + Math.min(sec.pages - 1, Math.floor(chars / CHARS_PER_PAGE));
  }

  // epub.js reports the place a moment after it shows it.
  const settleJump = () => { const j = jump; setTimeout(() => { if (j) j.done = true; }, 500); };
  function pageAt(cfi, index) {
    const j = jump;
    if (j) {
      if (!j.done || !j.cfi || j.cfi === cfi) { j.cfi = cfi; return j.page; }
      jump = null;
    }
    return pageFromLocation(cfi, index);
  }

  async function cfiForPage(page) {
    if (!book || !model) return null;
    const sec = sectionForPage(model, page);
    if (!sec) return null;
    const section = book.spine.get(sec.index);
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
  }

  /* Shows a place, and again once the page's fonts are in: epub.js picks the column before the text settles. */
  async function show(r, target) {
    await r.display(target);
    if (!target) return;
    const docs = (r.getContents() || []).map((c) => c.document).filter(Boolean);
    const pending = docs.some((d) => d.fonts && d.fonts.status !== 'loaded');
    await Promise.all(docs.map((d) => d.fonts?.ready.catch(() => undefined)));
    await new Promise((res) => requestAnimationFrame(() => requestAnimationFrame(res)));
    if (pending || docs.length) await r.display(target);
  }

  function waitFor(get, ms = 30000) {
    return new Promise((resolve) => {
      const start = Date.now();
      const tick = () => {
        const v = get();
        if (v || Date.now() - start > ms) resolve(v);
        else setTimeout(tick, 100);
      };
      tick();
    });
  }

  function report() {
    if (pos) post({ type: 'relocated', cfi: pos.cfi, index: pos.index, page: pos.page, atStart: pos.atStart, atEnd: pos.atEnd });
  }

  /* ── Turning pages: the left and right thirds, a swipe; the middle shows or hides the app's bars ── */

  const next = () => { if (rend) { void rend.next(); post({ type: 'turn' }); } };
  const prev = () => { if (rend) { void rend.prev(); post({ type: 'turn' }); } };
  let touch = null;
  let swiped = 0;

  function onClick(e) {
    if (Date.now() - swiped < 400) return;
    const win = e.view || null;
    if (win?.getSelection && String(win.getSelection() || '')) return;
    if (e.target?.closest?.('a')) return;
    if (scrolls(settings)) { post({ type: 'tap' }); return; }
    const frame = win?.frameElement;
    const x = e.clientX + (frame ? frame.getBoundingClientRect().left : 0);
    const w = window.innerWidth;
    if (x < w / 3) prev();
    else if (x > w - w / 3) next();
    else post({ type: 'tap' });
  }

  function onTouchEnd(e) {
    const s = touch;
    touch = null;
    if (!s || scrolls(settings)) return;
    const t = e.changedTouches[0];
    const dx = t.clientX - s.x;
    if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(t.clientY - s.y) * 1.5 && Date.now() - s.at < 800) {
      swiped = Date.now();
      if (dx < 0) next(); else prev();
    }
  }

  /* ── The rendition: made again when the layout or the margins change ── */

  function render() {
    const s = settings;
    if (rend) { rend.destroy(); rend = null; }
    // A rendition leaves its hook on the book's spine when destroyed (epub.js 0.3.93): keep the book's own only.
    if (!spineHooks) spineHooks = book.spine.hooks.content.hooks.slice();
    else book.spine.hooks.content.hooks = spineHooks.slice();
    host().innerHTML = '';
    document.documentElement.style.background = theme(s).bg;
    document.body.style.background = theme(s).bg;
    const r = book.renderTo(host(), {
      width: '100%',
      height: '100%',
      flow: scrolls(s) ? 'scrolled' : 'paginated',
      manager: scrolls(s) ? 'continuous' : 'default',
      spread: 'none',
      minSpreadWidth: 0,
      allowScriptedContent: false,
      gap: (MARGIN[s.margins] || 26) * 2
    });
    rend = r;
    r.hooks.content.register((contents) => styleContents(contents, settings));
    r.on('relocated', (l) => {
      if (rend !== r) return;
      lastCfi = l.start.cfi;
      pos = { cfi: l.start.cfi, index: l.start.index, page: pageAt(l.start.cfi, l.start.index), atStart: !!l.atStart, atEnd: !!l.atEnd };
      report();
    });
    r.on('click', onClick);
    // Within the page's frame (screenX isn't reliable in a WebView); the frame doesn't move during a swipe.
    r.on('touchstart', (e) => { const t = e.changedTouches[0]; touch = { x: t.clientX, y: t.clientY, at: Date.now() }; });
    r.on('touchend', onTouchEnd);
    void (async () => {
      let target = lastCfi || undefined;
      if (startPercent !== null && Number.isFinite(startPercent)) {
        // From the audiobook ("Read from here"), or a place saved without a CFI: the same share of the book.
        const m = model || await waitFor(() => model);
        if (m) {
          const page = Math.min(m.total, Math.max(1, Math.ceil((startPercent / 100) * m.total)));
          target = (await cfiForPage(page)) || target;
          jump = { page };
        }
        startPercent = null;
      }
      if (rend !== r) return;
      try { await show(r, target); } catch (e) { await r.display(); }
      settleJump();
    })();
  }

  function contentsList(m) {
    const fromServer = chapters.filter((c) => c.Page > 0).map((c) => ({ title: c.Title, href: c.Href, page: c.Page }));
    const fromBook = flat.map((n) => {
      const file = n.href.split('#')[0];
      const sec = m.sections.find((s) => s.href === file || s.href.endsWith('/' + file) || file.endsWith(s.href));
      return { title: n.label, href: n.href, page: sec ? sec.first : 0 };
    }).filter((c) => c.page > 0);
    return (fromServer.length ? fromServer : fromBook).sort((a, b) => a.page - b.page);
  }

  /* ───────── What the app asks ───────── */

  async function open(o) {
    settings = o.settings;
    lastCfi = o.cfi || null;
    startPercent = typeof o.percent === 'number' ? o.percent : null;
    chapters = o.chapters || [];
    document.documentElement.style.background = theme(settings).bg;
    try {
      const res = await fetch(o.file);
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const buf = await res.arrayBuffer();
      book = ePub(buf);
      await book.ready;
      const nav = await book.loaded.navigation;
      flat = [];
      const add = (list) => list.forEach((n) => { flat.push({ label: (n.label || '').trim(), href: n.href }); if (n.subitems?.length) add(n.subitems); });
      add(nav.toc || []);
      post({ type: 'ready' });
      render();
      const m = await buildPageModel(book);
      model = m;
      post({ type: 'model', total: m.total });
      post({ type: 'toc', items: contentsList(m) });
      // The page number once pages are counted (the first page may show before).
      if (pos) {
        const page = pageAt(pos.cfi, pos.index);
        if (page !== pos.page) { pos = { ...pos, page }; report(); }
      }
    } catch (e) {
      post({ type: 'error', message: (e && e.message) || '' });
    }
  }

  async function goTo(target) {
    if (target && rend) await show(rend, target).catch(() => undefined);
  }

  async function goToPage(p) {
    const cfi = await cfiForPage(p);
    jump = { page: p };
    await goTo(cfi);
    settleJump();
  }

  /* New settings: the look changes in place (then back to the same place); the layout or margins make the pages again. */
  function setSettings(s) {
    const before = settings;
    settings = s;
    if (!rend || !before) return;
    if (scrolls(before) !== scrolls(s) || before.margins !== s.margins) { render(); return; }
    document.documentElement.style.background = theme(s).bg;
    document.body.style.background = theme(s).bg;
    (rend.getContents() || []).forEach((c) => styleContents(c, s));
    const cfi = lastCfi;
    const r = rend;
    setTimeout(() => { if (cfi && rend === r) void show(r, cfi).catch(() => undefined); }, 60);
  }

  window.seedy = { open, next, prev, goTo, goToPage, settings: setSettings };
})();
