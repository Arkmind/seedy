import type Book from 'epubjs/types/book';
import type Section from 'epubjs/types/section';

/* Pages the way the server counts them (BookFiles.ReadEpub): each spine document's text, with every tag read as a
   space and runs of whitespace collapsed, cut into pages of 1600 characters, at least one page per document. Counting
   the same way here keeps "Page 190 of 412" in the reader equal to "412 pages" on the details page. */

export const CHARS_PER_PAGE = 1600;

export interface SectionPages { index: number; href: string; first: number; pages: number }
export interface PageModel { sections: SectionPages[]; total: number }

const SPACE = /\s/;

/* Walks a body's text the server's way and calls `visit` for every character that counts, with where it is. */
function walk(root: Node, visit: (node: Node | null, offset: number) => boolean): void {
  let lastSpace = true; // leading whitespace is trimmed
  let stop = false;
  const space = (node: Node | null, offset: number) => {
    if (lastSpace) return;
    lastSpace = true;
    if (visit(node, offset)) stop = true;
  };
  const rec = (node: Node) => {
    for (let child = node.firstChild; child && !stop; child = child.nextSibling) {
      if (child.nodeType === 3 || child.nodeType === 4) {
        const text = child.nodeValue ?? '';
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

const bodyOf = (doc: Document | Element): Element | null =>
  ((doc as Document).documentElement ? (doc as Document) : (doc as Element).ownerDocument)?.querySelector('body')
  ?? (doc as Element).querySelector?.('body') ?? null;

/* Length of the text that counts (a trailing collapsed space doesn't). */
export function textLength(doc: Document | Element): number {
  const body = bodyOf(doc);
  if (!body) return 0;
  let n = 0;
  let trailingSpace = false;
  walk(body, (node, offset) => {
    n++;
    trailingSpace = !node || (node.nodeType !== 3 && node.nodeType !== 4) || SPACE.test((node.nodeValue ?? '')[offset] ?? '');
    return false;
  });
  return trailingSpace ? n - 1 : n;
}

/* Characters before a point (node, offset) in the body. */
export function offsetOf(doc: Document, node: Node, offset: number): number {
  const body = bodyOf(doc);
  if (!body) return 0;
  // A point between elements: the first text after it.
  let target: Node = node;
  let at = offset;
  if (node.nodeType === 1 && node.childNodes.length) {
    target = node.childNodes[Math.min(offset, node.childNodes.length - 1)];
    at = offset >= node.childNodes.length ? Number.MAX_SAFE_INTEGER : 0;
  }
  let n = 0;
  let found = false;
  walk(body, (cur, i) => {
    if (cur && (cur === target || target.contains(cur)) && (cur !== target || i >= at)) { found = true; return true; }
    n++;
    return false;
  });
  return found ? n : n;
}

/* The point at a number of characters into the body. */
export function pointAt(doc: Document, chars: number): { node: Node; offset: number } | null {
  const body = bodyOf(doc);
  if (!body) return null;
  let n = 0;
  let hit: { node: Node; offset: number } | null = null;
  let lastText: { node: Node; offset: number } | null = null;
  walk(body, (node, offset) => {
    if (node && (node.nodeType === 3 || node.nodeType === 4)) lastText = { node, offset };
    if (n >= chars && node && (node.nodeType === 3 || node.nodeType === 4)) { hit = { node, offset }; return true; }
    n++;
    return false;
  });
  return hit ?? lastText;
}

export async function loadSection(book: Book, section: Section): Promise<Document | null> {
  try {
    const el = await section.load(book.load.bind(book) as unknown as (url: string) => Promise<object>) as unknown as Element;
    return (section as unknown as { document?: Document }).document ?? el?.ownerDocument ?? null;
  } catch {
    return null;
  }
}

export async function buildPageModel(book: Book): Promise<PageModel> {
  const sections: SectionPages[] = [];
  let total = 0;
  const items = (book.spine as unknown as { spineItems: Section[] }).spineItems;
  for (const s of items) {
    const doc = await loadSection(book, s);
    const len = doc ? textLength(doc) : 0;
    const pages = Math.max(1, Math.ceil(len / CHARS_PER_PAGE));
    sections.push({ index: s.index, href: s.href, first: total + 1, pages });
    total += pages;
    s.unload();
  }
  return { sections, total };
}

export function sectionForPage(model: PageModel, page: number): SectionPages | undefined {
  return model.sections.find((s) => page >= s.first && page < s.first + s.pages) ?? model.sections[model.sections.length - 1];
}
