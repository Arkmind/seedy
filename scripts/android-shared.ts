// Copies what the Android apps share with the web app, so both stay in step:
//   web/src/ui/tokens.css   → android/ui/src/main/kotlin/…/ui/theme/Tokens.kt   (colours, radii, motion)
//   web/src/i18n/fr/*.ts    → android/core/src/main/assets/i18n/fr.web.json      (French for the same English text)
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

console.log(`${colors.length} colours, ${radii.length} radii, ${motion.length} durations; ${Object.keys(sorted).length} French strings`);
