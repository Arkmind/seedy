// Copies what the Android apps share with the web app, so both stay in step:
//   web/src/ui/tokens.css   → android/ui/src/main/kotlin/…/ui/theme/Tokens.kt   (colours, radii, motion)
//   web/src/i18n/fr/*.ts    → android/core/src/main/assets/i18n/fr.web.json      (French for the same English text)
//   web/src/ui/icons.tsx    → android/ui/src/main/kotlin/…/ui/icons/WebIcons.kt  (the boards’ outline icons)
// Run with `make android-shared` (bun scripts/android-shared.ts) after changing either; CI checks the output is current.
import { readdirSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join, dirname } from 'node:path';

const root = join(import.meta.dir, '..');
const write = (path: string, text: string) => {
  mkdirSync(dirname(join(root, path)), { recursive: true });
  writeFileSync(join(root, path), text);
};

// ── Tokens ──

const css = readFileSync(join(root, 'web/src/ui/tokens.css'), 'utf8');
const rootBlock = css.slice(css.indexOf(':root {'), css.indexOf('}', css.indexOf(':root {')));
const vars = [...rootBlock.matchAll(/--([\w-]+):\s*([^;]+);/g)].map(([, name, value]) => ({ name, value: value.trim() }));

const pascal = (s: string) => s.split('-').map((p) => p.charAt(0).toUpperCase() + p.slice(1)).join('');
const hex2 = (n: number) => Math.round(n).toString(16).padStart(2, '0').toUpperCase();

function color(value: string): string | null {
  let m = value.match(/^#([0-9a-f]{6})$/i);
  if (m) return `Color(0xFF${m[1].toUpperCase()})`;
  m = value.match(/^rgba\((\d+),\s*(\d+),\s*(\d+),\s*([\d.]+)\)$/);
  if (m) return `Color(0x${hex2(+m[4] * 255)}${hex2(+m[1])}${hex2(+m[2])}${hex2(+m[3])})`;
  return null;
}

const colors: string[] = [];
const radii: string[] = [];
const motion: string[] = [];
for (const { name, value } of vars) {
  if (name.startsWith('seedy-')) {
    const c = color(value);
    if (c) colors.push(`    val ${pascal(name.slice('seedy-'.length))} = ${c}`);
  } else if (name.startsWith('r-')) {
    radii.push(`    val ${pascal(name.slice(2))} = ${parseFloat(value)}.dp`);
  } else if (name.startsWith('t-')) {
    motion.push(`    const val ${pascal(name.slice(2))} = ${parseFloat(value)}`);
  }
}

write('android/ui/src/main/kotlin/io/github/arkmind/seedy/ui/theme/Tokens.kt', `// Generated from web/src/ui/tokens.css by scripts/android-shared.ts. Edit the CSS, then run \`make android-shared\`.
package io.github.arkmind.seedy.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Seedy's colours (Foundations board). */
object SeedyColors {
${colors.join('\n')}
}

/** Corner radii (Foundations board). */
object SeedyRadii {
${radii.join('\n')}
}

/** Animation durations in milliseconds. */
object SeedyMotion {
${motion.join('\n')}
}
`);

// ── French ──

// The apps have no admin pages, so the admin and server-dashboard dictionaries stay out.
const dir = join(root, 'web/src/i18n/fr');
const skip = new Set(['admin.ts', 'server.ts']);
const fr: Record<string, string> = {};
for (const file of readdirSync(dir).filter((f) => f.endsWith('.ts') && !skip.has(f)).sort()) {
  const mod = (await import(join(dir, file))) as { default: Record<string, string> };
  Object.assign(fr, mod.default);
}
const sorted = Object.fromEntries(Object.entries(fr).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)));
write('android/core/src/main/assets/i18n/fr.web.json', JSON.stringify(sorted, null, 1) + '\n');

// ── Icons ──

const tsx = readFileSync(join(root, 'web/src/ui/icons.tsx'), 'utf8');
const block = tsx.slice(tsx.indexOf('const ICONS = {'), tsx.indexOf('} satisfies Record<string, IconDef>'));
const num = (s: string, name: string) => parseFloat(s.match(new RegExp(`\\b${name}="([\\d.-]+)"`))?.[1] ?? '0');
const fmt = (n: number) => String(+n.toFixed(3));

function rectPath(x: number, y: number, w: number, h: number, r: number): string {
  if (!r) return `M${fmt(x)} ${fmt(y)}h${fmt(w)}v${fmt(h)}h${fmt(-w)}Z`;
  return `M${fmt(x + r)} ${fmt(y)}h${fmt(w - 2 * r)}a${fmt(r)} ${fmt(r)} 0 0 1 ${fmt(r)} ${fmt(r)}v${fmt(h - 2 * r)}` +
    `a${fmt(r)} ${fmt(r)} 0 0 1 ${fmt(-r)} ${fmt(r)}h${fmt(-(w - 2 * r))}a${fmt(r)} ${fmt(r)} 0 0 1 ${fmt(-r)} ${fmt(-r)}` +
    `v${fmt(-(h - 2 * r))}a${fmt(r)} ${fmt(r)} 0 0 1 ${fmt(r)} ${fmt(-r)}Z`;
}

const icons: string[] = [];
for (const line of block.split('\n')) {
  const m = line.match(/^\s+(\w+): \{ w: ([\d.]+)(, fill: true)?, body: (.*) \},?$/);
  if (!m) continue;
  const [, name, w, fillAll, body] = m;
  const parts: string[] = [];
  for (const el of body.matchAll(/p\('([^']+)'\)|<(path|circle|rect)\b([^>]*)\/>/g)) {
    const [, short, tag, attrs = ''] = el;
    const filled = !!fillAll || /fill="currentColor"/.test(attrs);
    let d: string;
    if (short) d = short;
    else if (tag === 'path') d = attrs.match(/\bd="([^"]+)"/)![1];
    else if (tag === 'circle') {
      const [cx, cy, r] = [num(attrs, 'cx'), num(attrs, 'cy'), num(attrs, 'r')];
      d = `M${fmt(cx - r)} ${fmt(cy)}a${fmt(r)} ${fmt(r)} 0 1 0 ${fmt(2 * r)} 0a${fmt(r)} ${fmt(r)} 0 1 0 ${fmt(-2 * r)} 0Z`;
    } else d = rectPath(num(attrs, 'x'), num(attrs, 'y'), num(attrs, 'width'), num(attrs, 'height'), num(attrs, 'rx'));
    parts.push(`IconPart("${d}"${filled ? ', filled = true' : ''})`);
  }
  icons.push(`    val ${pascal(name.replace(/([A-Z])/g, '-$1').toLowerCase())} = IconDef(${w}f, listOf(${parts.join(', ')}))`);
}

write('android/ui/src/main/kotlin/io/github/arkmind/seedy/ui/icons/WebIcons.kt', `// Generated from web/src/ui/icons.tsx by scripts/android-shared.ts. Edit the web icons, then run \`make android-shared\`.
package io.github.arkmind.seedy.ui.icons

/** The web app's outline icons (24 px grid, round caps), copied path-for-path from the design boards. */
object WebIcons {
${icons.join('\n')}
}
`);

console.log(`${colors.length} colours, ${radii.length} radii, ${motion.length} durations; ${Object.keys(sorted).length} French strings; ${icons.length} icons`);
