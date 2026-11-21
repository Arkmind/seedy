import { saveBlob } from '../../lib/clipboard';

/* Pictures of Wrapped stories, to share or keep. The story is copied into an SVG <foreignObject> with the styles it
   uses, its fonts and pictures inlined (an SVG drawn as an image can't fetch anything), then drawn onto a canvas. */

const SCALE = 3;

const toDataUrl = (blob: Blob) => new Promise<string>((resolve, reject) => {
  const r = new FileReader();
  r.onload = () => resolve(String(r.result));
  r.onerror = () => reject(r.error);
  r.readAsDataURL(blob);
});

async function fetchData(url: string): Promise<string | null> {
  try {
    const res = await fetch(url);
    return res.ok ? await toDataUrl(await res.blob()) : null;
  } catch {
    return null;
  }
}

let fontCss: Promise<string> | null = null;

/* The app's Noto Sans faces (Latin subsets), inlined once. */
function fonts(): Promise<string> {
  fontCss ??= (async () => {
    const out: string[] = [];
    for (const sheet of Array.from(document.styleSheets)) {
      let rules: CSSRuleList;
      try { rules = sheet.cssRules; } catch { continue; }
      for (const rule of Array.from(rules)) {
        if (!(rule instanceof CSSFontFaceRule)) continue;
        const family = rule.style.getPropertyValue('font-family');
        const range = rule.style.getPropertyValue('unicode-range');
        if (!/Noto Sans/i.test(family) || /Mono/i.test(family)) continue;
        if (range && !/U\+0000-00FF/i.test(range) && !/U\+0100-02BA/i.test(range) && !/U\+2000-206F/i.test(range)) continue;
        const src = rule.style.getPropertyValue('src');
        const m = src.match(/url\(["']?([^"')]+)["']?\)/);
        if (!m) continue;
        const data = await fetchData(new URL(m[1], sheet.href ?? location.href).href);
        if (data) out.push(rule.cssText.replace(m[0], `url("${data}")`));
      }
    }
    return out.join('\n');
  })();
  return fontCss;
}

/* Every rule that styles a story: the Wrapped and Medu sheets. */
function storyCss(): string {
  const out: string[] = [];
  for (const sheet of Array.from(document.styleSheets)) {
    let rules: CSSRuleList;
    try { rules = sheet.cssRules; } catch { continue; }
    for (const rule of Array.from(rules)) {
      const text = rule.cssText;
      if (/\.(wr-story|medu|m-)/.test(text) || rule instanceof CSSKeyframesRule) out.push(text);
    }
  }
  return out.join('\n');
}

/* Draws [el] (a story, or the share card) into a PNG, [SCALE] times its CSS size. */
export async function picture(el: HTMLElement): Promise<Blob> {
  const width = el.offsetWidth;
  const height = el.offsetHeight;
  const clone = el.cloneNode(true) as HTMLElement;
  clone.classList.add('is-still');
  clone.querySelectorAll('[data-share-hide]').forEach((n) => n.remove());
  // The finished story: segments full, nothing waiting to come in.
  clone.style.transform = 'none';
  await Promise.all(Array.from(clone.querySelectorAll('img')).map(async (img) => {
    const data = img.src ? await fetchData(img.src) : null;
    if (data) img.src = data; else img.remove();
  }));
  const accent = getComputedStyle(document.documentElement).getPropertyValue('--seedy-accent').trim() || '#ff9fff';
  const font = getComputedStyle(document.documentElement).getPropertyValue('--font').trim();
  const css = `${await fonts()}
:root{--seedy-accent:${accent};--font:${font}}
${storyCss()}
* { animation: none !important; transition: none !important; }
.is-still.wr-story .seg-now, .is-still .seg-now { transform: none !important; }`;
  const wrap = document.createElement('div');
  wrap.setAttribute('xmlns', 'http://www.w3.org/1999/xhtml');
  wrap.style.cssText = `width:${width}px;height:${height}px;font-family:${font || 'sans-serif'}`;
  const style = document.createElement('style');
  style.textContent = css;
  wrap.append(style, clone);
  const xhtml = new XMLSerializer().serializeToString(wrap);
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}"><foreignObject width="100%" height="100%">${xhtml}</foreignObject></svg>`;
  const img = new Image();
  img.decoding = 'sync';
  await new Promise<void>((resolve, reject) => {
    img.onload = () => resolve();
    img.onerror = () => reject(new Error('picture'));
    img.src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);
  });
  const canvas = document.createElement('canvas');
  canvas.width = Math.round(width * SCALE);
  canvas.height = Math.round(height * SCALE);
  const ctx = canvas.getContext('2d')!;
  ctx.scale(SCALE, SCALE);
  ctx.drawImage(img, 0, 0, width, height);
  return await new Promise<Blob>((resolve, reject) => canvas.toBlob((b) => (b ? resolve(b) : reject(new Error('picture'))), 'image/png'));
}

/* Shares a picture where the browser can (phones), else saves it. Resolves 'shared', 'saved' or 'cancelled'. */
export async function sharePicture(blob: Blob, name: string, title: string): Promise<'shared' | 'saved' | 'cancelled'> {
  const file = new File([blob], name, { type: 'image/png' });
  const nav = navigator as Navigator & { canShare?: (d: ShareData) => boolean };
  if (nav.share && nav.canShare?.({ files: [file] })) {
    try {
      await nav.share({ files: [file], title });
      return 'shared';
    } catch (e) {
      if ((e as Error)?.name === 'AbortError') return 'cancelled';
    }
  }
  saveBlob(name, blob);
  return 'saved';
}

export const savePicture = (blob: Blob, name: string) => saveBlob(name, blob);
