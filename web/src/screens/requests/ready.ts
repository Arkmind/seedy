import { getEpisodes } from '../../api/jellyfin';
import { requestList, type SeerrRequest } from '../../api/seedy';
import { currentUserId, meStore, viewsStore } from '../../lib/app-state';
import { t } from '../../lib/i18n';
import { navigate } from '../../lib/router';
import { seerrTarget } from '../../ui/cards';
import { meduToast } from '../../ui/medu-toast';
import { ago } from '../server/format';

// Request ready toast board: when something this person asked for lands on the server, Medu says so with a way to
// start it. Seedy looks every few minutes and whenever the tab comes back; what it has already announced is kept per
// person in this browser, and the first look only takes note (so an old library doesn't flood in at once).

const EVERY = 5 * 60_000;
const key = () => `seedy.readySeen.${currentUserId()}`;

function seenSet(): Set<number> | null {
  try {
    const raw = window.localStorage.getItem(key());
    return raw ? new Set(JSON.parse(raw) as number[]) : null;
  } catch { return null; }
}
function saveSeen(ids: Set<number>) {
  try { window.localStorage.setItem(key(), JSON.stringify([...ids].slice(-300))); } catch { /* private window */ }
}

const seasonOf = (r: SeerrRequest) => (r.mediaType === 'tv' && r.seasons?.length ? Math.max(...r.seasons) : undefined);

/* Season n's first episode, to play straight from the toast. */
async function firstEpisode(seriesId: string, season: number): Promise<string | undefined> {
  const eps = (await getEpisodes(seriesId, undefined, { season })).Items;
  return eps.find((e) => e.ParentIndexNumber === season && e.LocationType !== 'Virtual')?.Id ?? eps[0]?.Id;
}

function announce(r: SeerrRequest) {
  const season = seasonOf(r);
  const views = viewsStore.get();
  const library = views.find((v) => v.CollectionType === (r.mediaType === 'tv' ? 'tvshows' : 'movies'))?.Name;
  const when = r.availableAt ? ago(new Date(r.availableAt)) : '';
  const id = r.jellyfinItemId;
  meduToast({
    card: true,
    eyebrow: t('Ready to watch'),
    title: season ? `${r.title} · ${t('Season {n}', { n: season })}` : r.title,
    text: library
      ? when === t('just now') ? t('Your request was added to {library} just now.', { library }) : t('Your request was added to {library} {when}.', { library, when })
      : t('Your request is on the server now.'),
    actions: [
      id
        ? {
          label: season ? t('Play S{s} E1', { s: season }) : t('Play'),
          icon: 'play',
          primary: true,
          onClick: () => {
            if (!season) { navigate(`/play/${id}`); return; }
            firstEpisode(id, season).then((ep) => navigate(ep ? `/play/${ep}` : `/details/${id}`), () => navigate(`/details/${id}`));
          }
        }
        : { label: t('Open'), primary: true, onClick: () => navigate(seerrTarget(r)) },
      { label: t('Later'), onClick: () => undefined }
    ]
  });
}

async function look() {
  const me = meStore.get();
  if (!me.requests?.connected || !currentUserId() || document.hidden) return;
  const list = (await requestList({ scope: 'mine', filter: 'available', sort: 'modified', take: 20 }).catch(() => null))?.results;
  if (!list) return;
  const seen = seenSet();
  const ready = list.filter((r) => r.requestedByMe !== false);
  if (!seen) { saveSeen(new Set(ready.map((r) => r.requestId))); return; }
  const fresh = ready.filter((r) => !seen.has(r.requestId));
  if (!fresh.length) return;
  fresh.forEach((r) => seen.add(r.requestId));
  saveSeen(seen);
  // One at a time: the newest. The rest are on the My requests page.
  announce(fresh[0]);
}

let started = false;
export function startReadyWatch() {
  if (started) return;
  started = true;
  window.setTimeout(() => void look(), 4000);
  window.setInterval(() => void look(), EVERY);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) void look(); });
}
