import { useEffect, useState } from 'preact/hooks';
import { getPublicInfo } from '../api/jellyfin';
import { SERVER_URL, lastSeen } from '../api/client';
import { boot, serverNameStore } from '../lib/app-state';
import { t } from '../lib/i18n';
import { useStore } from '../lib/store';
import { Icon } from '../ui/icons';
import { Medu } from '../ui/medu';
import { SAND_BACK, SAND_FRONT, Sea, Weed, type Bubble, type Fish, type Flake, type Ray } from '../ui/sea';
import { ago } from './server/format';
import './not-found.css';

// Can't reach the server board: shown when the server stops answering at start-up (restarting, network down).
// Medu has dropped the cable; Seedy keeps the session and quietly tries again on its own, a little less often each
// time. When the server answers, Medu flings the cable away and waves (Oops → Hello, board "Moving between moods"),
// then Seedy starts properly.

const RAYS: Ray[] = [[111, 158, -8.3], [515, 152, -5.3], [896, 152, -5.7], [1348, 98, -2.7]];
const SNOW: Flake[] = [[998, 30, 2.9, 0.36, 15.3, -14.7], [226, 10, 2.5, 0.26, 23.2, -1.4], [43, 334, 1.9, 0.24, 16.7, -14.0], [719, 476, 2.6, 0.24, 21.3, -5.9], [1209, 509, 3.2, 0.21, 28.0, -6.4], [1103, 288, 1.6, 0.33, 18.0, -7.0], [0, 150, 2.9, 0.34, 27.4, -12.9], [105, 453, 2.2, 0.31, 27.7, -7.5], [1388, 545, 2.1, 0.17, 15.2, -3.8], [1147, 127, 1.6, 0.26, 15.4, -6.9], [188, 463, 2.7, 0.17, 16.7, -7.0], [1398, 578, 2.0, 0.21, 17.0, -15.0], [1230, 462, 2.2, 0.16, 16.9, -16.8], [1112, 236, 1.9, 0.21, 17.0, -1.2], [349, 432, 2.5, 0.22, 15.3, -6.9], [827, 623, 2.3, 0.18, 27.4, -4.2], [359, 136, 2.9, 0.30, 26.7, -25.1], [1270, 434, 3.1, 0.23, 16.8, -1.7], [343, 507, 3.1, 0.20, 14.5, -12.0], [252, 518, 2.0, 0.16, 22.4, -5.1], [884, 201, 2.9, 0.34, 21.8, -4.5], [641, 43, 2.0, 0.18, 14.2, -5.2], [521, 641, 1.7, 0.36, 22.0, -14.5], [202, 25, 2.5, 0.14, 23.7, -21.6], [30, 458, 3.1, 0.25, 23.8, -17.4], [108, 393, 3.2, 0.30, 18.5, -16.6], [1142, 658, 2.7, 0.22, 24.3, -16.7], [600, 569, 3.0, 0.33, 26.6, -15.2], [839, 438, 2.1, 0.16, 22.7, -14.5], [1048, 279, 3.0, 0.30, 27.9, -16.2]];
const BUBBLES: Bubble[] = [[773, 81, 9, 9.9, -5.1], [695, 58, 8, 10.4, -7.3], [927, 78, 9, 11.1, -10.0], [950, 56, 8, 9.2, -1.6], [644, 111, 8, 10.6, -3.5], [893, 104, 11, 9.0, -6.8]];
const FISH: Fish[] = [[423, 36, '#1b2029', 49, -15], [578, 34, '#1f2530', 48, -1, true]];

/* Seconds before each quiet retry: soon at first, then once a minute. */
const WAITS = [12, 20, 30, 45, 60];

function Seabed() {
  return (
    <svg class="sea-bed" viewBox="0 0 1440 220" preserveAspectRatio="xMidYMax slice">
      <path d={SAND_BACK} fill="#11141a" />
      <Weed d="M115 220 C107 175 137 145 121 121 C109 100 134 85 125 70" />
      <Weed d="M144 220 C126 187 156 165 140 147 C124 132 147 121 136 110" color="#1b2a24" delay={-1.6} />
      <Weed d="M1267 220 C1245 172 1275 140 1259 114 C1241 92 1264 76 1252 60" delay={-2.2} />
      <Weed d="M1324 220 C1316 184 1346 160 1330 140 C1318 124 1343 112 1334 100" color="#1b2a24" delay={-0.4} />
      <defs>
        <linearGradient id="un-dune" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#101318" /><stop offset="1" stop-color="#0e0e0e" /></linearGradient>
      </defs>
      <path d={SAND_FRONT} fill="url(#un-dune)" />
    </svg>
  );
}

export function Unreachable() {
  const name = useStore(serverNameStore);
  const host = new URL(SERVER_URL).host;
  const [tries, setTries] = useState(0);
  const [left, setLeft] = useState(WAITS[0]);
  const [checking, setChecking] = useState(false);
  const [back, setBack] = useState(false);
  const seen = lastSeen();

  // Ask the server without leaving this page; Seedy starts once Medu has said hello.
  const check = () => {
    if (checking || back) return;
    setChecking(true);
    getPublicInfo().then(() => setBack(true), () => { setChecking(false); setTries((n) => n + 1); });
  };

  // Count down, then ask.
  useEffect(() => {
    if (back) return;
    const wait = WAITS[Math.min(tries, WAITS.length - 1)];
    setLeft(wait);
    const started = Date.now();
    const tick = window.setInterval(() => {
      const remaining = wait - Math.floor((Date.now() - started) / 1000);
      if (remaining > 0) { setLeft(remaining); return; }
      window.clearInterval(tick);
      check();
    }, 1000);
    return () => window.clearInterval(tick);
  }, [tries]);

  const server = name || t('your server');
  return (
    <div class="sea-scene un-page">
      <Sea rays={RAYS} snow={SNOW} bubbles={BUBBLES} fish={FISH} rise={460} rayHeight={1100}><Seabed /></Sea>
      <div class="un-brand"><span class="un-brand-mark" aria-hidden="true">S</span><span class="un-brand-name">Seedy</span></div>
      <main class="un-main">
        <Medu mood={back ? 'wave' : 'oops'} size={230} onSettled={(mood) => { if (mood === 'wave') window.setTimeout(() => void boot(), 900); }} />
        <h1 class="un-title">{back ? t('Back online') : t('Can’t reach your server')}</h1>
        <p class="un-text">
          {back
            ? name ? t('{server} is answering again.', { server: name }) : t('Your server is answering again.')
            : t('Medu dropped the cable. Check that {server} is switched on and connected to the network, then try again.', { server })}
        </p>
        <div class="nf-actions un-actions">
          <button type="button" class="btn btn-primary medu-actions-btn" disabled={back} onClick={check}><Icon name="refresh" size={18} />{t('Try again')}</button>
        </div>
        <div class="un-status" role="status">
          <span class="un-status-server"><span class={'un-dot' + (back ? ' is-ok' : '')} aria-hidden="true" />{name ? `${name} · ` : ''}<span class="un-mono">{host}</span></span>
          {seen && !back && <span>{t('Last seen {when}', { when: ago(new Date(seen)) })}</span>}
          <span class="un-status-retry">
            {!back && <svg class="un-spin" viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M12 3a9 9 0 1 1-9 9" /></svg>}
            {back ? t('Connected') : checking ? t('Trying again…') : t('Trying again in {n} s', { n: left })}
          </span>
        </div>
      </main>
    </div>
  );
}
