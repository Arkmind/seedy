import { useEffect, useState } from 'preact/hooks';
import { getItems, getSimilar } from '../api/jellyfin';
import type { BaseItem } from '../api/types';
import { isAdmin } from '../lib/app-state';
import { t } from '../lib/i18n';
import { Link, useLocation } from '../lib/router';
import { ItemCard } from '../ui/cards';
import { Icon } from '../ui/icons';
import { Medu } from '../ui/medu';
import { Header } from '../ui/navbar/Header';
import { toast } from '../ui/overlay';
import { openSearch } from '../ui/search/open';
import { SAND_BACK, SAND_FRONT, Sea, Weed, type Bubble, type Fish, type Flake, type Ray } from '../ui/sea';
import { seasonsLabel } from './browse/parts';
import { hhmm24 } from '../lib/format';
import { copyText } from '../lib/clipboard';
import './not-found.css';

// Not found (404) board: Medu searches the deep with a torch between the two 4s. Used for unknown pages and for
// titles the server doesn't have (or this profile can't see). Admins get the request and the reason to copy.

const RAYS: Ray[] = [[90, 130, 0], [330, 220, -3.5], [640, 100, -6], [860, 190, -1.8], [1160, 150, -8]];
const SNOW: Flake[] = [[217, 455, 1.6, 0.27, 18.5, -6.8], [730, 26, 2.3, 0.16, 14.8, -1.3], [1190, 86, 1.9, 0.29, 19.9, -18.9], [571, 683, 1.6, 0.35, 22.1, -6.4], [169, 215, 3.1, 0.18, 16.0, -9.3], [536, 383, 1.6, 0.15, 22.9, -4.7], [615, 219, 2.6, 0.25, 23.5, -7.1], [1006, 170, 2.6, 0.27, 25.1, -22.0], [414, 686, 1.7, 0.24, 24.2, -18.3], [704, 27, 2.8, 0.32, 16.1, -9.2], [451, 486, 2.6, 0.28, 26.3, -12.0], [1360, 331, 2.8, 0.15, 25.8, -18.1], [1430, 575, 2.0, 0.23, 23.1, -15.4], [664, 117, 1.7, 0.15, 14.3, -11.0], [356, 273, 3.2, 0.16, 15.8, -7.1], [1272, 573, 3.1, 0.21, 21.7, -9.0], [1273, 670, 1.8, 0.18, 19.0, -4.4], [698, 412, 2.0, 0.14, 17.3, -7.2], [815, 667, 2.8, 0.26, 19.2, -11.8], [77, 629, 3.0, 0.35, 23.5, -18.7], [574, 72, 2.7, 0.15, 19.5, -1.3], [233, 238, 1.6, 0.14, 16.9, -2.6], [523, 17, 3.2, 0.29, 15.4, -2.3], [500, 254, 1.7, 0.34, 17.5, -17.4], [696, 60, 1.7, 0.22, 20.5, -5.4], [232, 16, 3.3, 0.27, 25.6, -3.8], [38, 369, 3.4, 0.35, 21.6, -15.0], [528, 116, 3.0, 0.27, 17.7, -13.8], [321, 568, 3.4, 0.34, 18.6, -15.0], [1065, 158, 2.5, 0.23, 25.5, -0.7], [402, 181, 2.8, 0.37, 14.4, -6.4], [1422, 668, 2.2, 0.19, 27.1, -6.2], [294, 436, 3.2, 0.34, 16.8, -8.0], [1151, 59, 2.8, 0.36, 23.1, -18.1], [688, 124, 3.0, 0.22, 24.5, -19.6], [570, 280, 3.3, 0.31, 27.6, -4.7], [217, 633, 3.0, 0.18, 15.8, -13.0], [946, 245, 2.5, 0.17, 27.7, -0.4], [935, 368, 3.3, 0.24, 27.6, -24.1], [303, 176, 2.1, 0.20, 25.6, -15.0], [603, 91, 3.2, 0.22, 17.6, -8.1], [1302, 294, 3.2, 0.26, 22.2, -11.8], [26, 308, 1.8, 0.14, 21.3, -17.0], [681, 507, 2.6, 0.22, 16.4, -8.5], [1129, 74, 2.6, 0.20, 21.8, -6.0], [731, 393, 2.9, 0.36, 24.8, -11.0]];
const BUBBLES: Bubble[] = [[150, 149, 10, 8.0, -7.8], [182, 148, 6, 10.5, -5.4], [300, 127, 8, 9.0, -4.1], [1255, 144, 12, 8.5, -6.9], [1300, 142, 7, 11.0, -10.4], [1350, 143, 9, 9.5, -8.3], [980, 126, 6, 12.0, -11.1], [520, 122, 7, 10.0, -8.4]];
const FISH: Fish[] = [[150, 46, '#1b2029', 52, -20], [250, 30, '#171b23', 64, -45, true], [560, 58, '#1f2530', 40, -8, true], [620, 34, '#1a1f28', 47, -30]];

/** The seabed: sand, seaweed, a lost film reel and a poster with a question mark on it. */
function Seabed() {
  return (
    <svg class="sea-bed" viewBox="0 0 1440 220" preserveAspectRatio="none">
      <path d={SAND_BACK} fill="#11141a" />
      <Weed d="M110 200 C102 155 132 125 116 101 C104 80 129 65 120 50" />
      <Weed d="M138 200 C120 167 150 145 134 127 C118 112 141 101 130 90" color="#1b2a24" delay={-1.6} />
      <Weed d="M170 200 C166 149 196 115 180 87 C170 64 196 47 188 30" delay={-0.8} />
      <Weed d="M1250 200 C1228 152 1258 120 1242 94 C1224 72 1247 56 1235 40" delay={-2.2} />
      <Weed d="M1284 200 C1276 164 1306 140 1290 120 C1278 104 1303 92 1294 80" color="#1b2a24" delay={-0.4} />
      <Weed d="M1395 200 C1377 158 1407 130 1391 107 C1375 88 1398 74 1387 60" delay={-3} />
      <g transform="translate(330 128) rotate(-8)">
        <circle r="40" fill="#161a21" stroke="#262b34" stroke-width="4" />
        <circle r="8" fill="#0d0f14" />
        {[[22, 0], [6.8, 20.9], [-17.8, 12.9], [-17.8, -12.9], [6.8, -20.9]].map(([cx, cy]) => <circle key={`${cx},${cy}`} cx={cx} cy={cy} r="9" fill="#0d0f14" />)}
        <path d="M40 6 C70 10 90 -4 120 6" fill="none" stroke="#262b34" stroke-width="5" stroke-linecap="round" />
      </g>
      <g transform="translate(1080 64) rotate(-13)">
        <rect x="0" y="0" width="84" height="124" rx="9" fill="#151920" stroke="#2a2f39" stroke-width="4" />
        <rect x="12" y="12" width="60" height="100" rx="4" fill="none" stroke="#222730" stroke-width="2" stroke-dasharray="6 6" />
        <text x="42" y="74" text-anchor="middle" font-family="Noto Sans Variable, Noto Sans, sans-serif" font-size="40" font-weight="900" fill="#2a2f39">?</text>
      </g>
      <defs>
        <linearGradient id="nf-dune" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#101318" /><stop offset="1" stop-color="#0e0e0e" /></linearGradient>
      </defs>
      <path d={SAND_FRONT} fill="url(#nf-dune)" />
      <g style={{ fill: 'var(--seedy-accent)' }} opacity="0.16">
        <circle cx="560" cy="160" r="6" /><circle cx="574" cy="152" r="4" /><circle cx="586" cy="162" r="5" />
        <circle cx="890" cy="158" r="4" /><circle cx="902" cy="166" r="6" />
      </g>
    </svg>
  );
}

/* "Picked from what you've been watching": titles like the last one played, else a few from the server. */
function usePicks() {
  const [picks, setPicks] = useState<{ items: BaseItem[]; seed?: { id: string; name: string } } | null>(null);
  useEffect(() => {
    const ctl = new AbortController();
    (async () => {
      const last = (await getItems({ recursive: true, includeItemTypes: ['Movie', 'Episode'], filters: ['IsPlayed'], sortBy: ['DatePlayed'], sortOrder: 'Descending', limit: 1 }, ctl.signal).catch(() => null))?.Items[0];
      const seed = last && { id: (last.Type === 'Episode' && last.SeriesId) || last.Id, name: (last.Type === 'Episode' && last.SeriesName) || last.Name || '' };
      const similar = seed ? (await getSimilar(seed.id, 5).catch(() => null))?.Items ?? [] : [];
      const items = similar.length ? similar
        : (await getItems({ recursive: true, includeItemTypes: ['Movie', 'Series'], sortBy: ['Random'], limit: 5 }, ctl.signal).catch(() => null))?.Items ?? [];
      if (!ctl.signal.aborted) setPicks({ items: items.slice(0, 5), seed: similar.length ? seed : undefined });
    })();
    return () => ctl.abort();
  }, []);
  return picks;
}

const pickMeta = (item: BaseItem) =>
  item.Type === 'Series' ? [t('Series'), seasonsLabel(item)].filter(Boolean).join(' · ')
    : [item.Type === 'Movie' ? t('Movie') : '', item.ProductionYear].filter(Boolean).join(' · ');

export function NotFound({ title = false }: {
  /** A title link (/details/…) rather than a page. */
  title?: boolean;
}) {
  const loc = useLocation();
  const [at] = useState(() => new Date());
  const picks = usePicks();
  const why = title ? t('no item with this ID in any library') : t('no page at this address');
  // Item ids are shortened to their first 8 characters on screen; Copy details keeps the whole request.
  const shown = loc.path.replace(/^(\/details\/[0-9a-f]{8})[0-9a-f]{24}$/i, '$1');
  const query = loc.query.toString();
  const copy = () => {
    const full = [`GET ${loc.path}${query ? '?' + query : ''}`, why, at.toISOString(), navigator.userAgent].join(' · ');
    void copyText(full).then((ok) => toast(ok ? t('Details copied') : t('Couldn’t copy the details')));
  };
  return (
    <div class="page">
      <Header active={null} />
      <main>
        <section class="sea-scene nf-sea" aria-labelledby="nf-h">
          <Sea rays={RAYS} snow={SNOW} bubbles={BUBBLES} fish={FISH}><Seabed /></Sea>
          <div class="nf-404" role="img" aria-label={t('Error 404')}>
            <span class="nf-digit" aria-hidden="true">4</span>
            <div class="nf-medu">
              <button type="button" class="nf-poke" aria-label={t('Poke Medu')}>
                <Medu mood="search" size={250} torch class="nf-main" />
                <Medu mood="yay" size={250} class="nf-alt" />
                <span class="nf-found" aria-hidden="true">{t('Well, you found Medu')}</span>
              </button>
            </div>
            <span class="nf-digit" aria-hidden="true">4</span>
          </div>
          <div class="nf-copy">
            <h1 id="nf-h" class="nf-title">{title ? t('This title swam off.') : t('This page swam off.')}</h1>
            <p class="nf-text">
              {title
                ? t('Medu has been looking everywhere. It may have been removed from the server, or it’s outside what this profile can see.')
                : t('Medu has been looking everywhere. The link may be old, or the page has moved.')}
            </p>
            <div class="nf-actions">
              <Link to="/" class="btn btn-primary medu-actions-btn"><Icon name="home" size={18} />{t('Back to home')}</Link>
              <button type="button" class="btn btn-secondary medu-actions-btn" onClick={openSearch}>
                <Icon name="search" size={18} />{t('Search Seedy')}<kbd class="nf-kbd">/</kbd>
              </button>
            </div>
          </div>
          {isAdmin() && (
            <div class="nf-admin">
              <span class="nf-admin-tag">{t('Admins only')}</span>
              <span class="nf-admin-line">GET {shown} · {why} · {hhmm24(at)}</span>
              <button type="button" class="nf-admin-copy" onClick={copy}>{t('Copy details')}</button>
            </div>
          )}
        </section>
        {picks && picks.items.length > 0 && (
          <section class="nf-picks" aria-labelledby="nf-picks-h">
            <div class="nf-picks-head">
              <div class="nf-picks-titles">
                <h2 id="nf-picks-h" class="nf-picks-title">{t('While you’re here')}</h2>
                <span class="nf-picks-sub">{picks.seed ? t('Picked from what you’ve been watching') : t('Picked from this server')}</span>
              </div>
              {picks.seed && <Link to={`/list/similar?seed=${picks.seed.id}&name=${encodeURIComponent(picks.seed.name)}`} class="nf-picks-all">{t('See all')}</Link>}
            </div>
            <div class="nf-picks-grid">
              {picks.items.map((item) => <ItemCard key={item.Id} item={item} meta={pickMeta(item)} play={false} grid />)}
            </div>
          </section>
        )}
      </main>
    </div>
  );
}
