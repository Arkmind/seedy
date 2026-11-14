/* Disco accent, unlocked by the Konami code: the accent walks slowly round the colour wheel at the lightness of
   Seedy's own pastels (#ff9fff is hsl(300 100% 81%)). Settings · Display turns it off (prefs.custom display.disco:
   null = still locked, 'off' = unlocked, 'on'). The personal effects (screens/me/effects.tsx) start and stop it with
   the profile, and their accent filter answers discoColour() while it runs, so a branding refresh doesn't flash. */

/** One trip round the wheel, in ms. */
const PERIOD = 60_000;
const STEP = 250;

let timer = 0;

function hslHex(h: number, s: number, l: number) {
  const a = s * Math.min(l, 1 - l);
  const f = (n: number) => {
    const k = (n + h / 30) % 12;
    return Math.round(255 * (l - a * Math.max(-1, Math.min(k - 3, 9 - k, 1)))).toString(16).padStart(2, '0');
  };
  return `#${f(0)}${f(8)}${f(4)}`;
}

/** The colour the accent is passing through now (each minute starts on Seedy pink). */
export const discoColour = () => hslHex((300 + ((Date.now() % PERIOD) / PERIOD) * 360) % 360, 1, 0.81);

export const discoOn = () => timer !== 0;

const paint = () => { if (!document.hidden) document.documentElement.style.setProperty('--seedy-accent', discoColour()); };

/** `restore` puts the usual accent back when it stops. */
export function setDisco(on: boolean, restore: () => void) {
  if (on === discoOn()) return;
  if (on) {
    timer = window.setInterval(paint, STEP);
    paint();
  } else {
    window.clearInterval(timer);
    timer = 0;
    restore();
  }
}
