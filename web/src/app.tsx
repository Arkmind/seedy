import { useEffect, useState } from 'preact/hooks';
import { bootState, meStore } from './lib/app-state';
import { match, useLocation } from './lib/router';
import { useStore } from './lib/store';
import { ConfirmHost, ToastHost } from './ui/overlay';
import { ManageHost } from './screens/browse/manage/host';
import { AudioHost } from './audio/AudioHost';
import { Home } from './screens/browse/Home';
import { Library } from './screens/browse/Library';
import { Details } from './screens/browse/Details';
import { Favorites } from './screens/browse/Favorites';
import { History } from './screens/browse/History';
import { Search } from './screens/browse/Search';
import { SeerrDetails } from './screens/browse/SeerrDetails';
import { ListScreen } from './screens/browse/Lists';
import { SignIn } from './screens/SignIn';
import { NotFound } from './screens/NotFound';
import { Unreachable } from './screens/Unreachable';
import { startReadyWatch } from './screens/requests/ready';
import { MeduToastHost } from './ui/medu-toast';
import { PartyHost } from './party/PartyHost';
import { MeProfile } from './screens/me/Profile';
import { MeHome } from './screens/me/HomeScreen';
import { MePlayback } from './screens/me/Playback';
import { MeSubtitles } from './screens/me/Subtitles';
import { MeDisplay } from './screens/me/Display';
import { MeControls } from './screens/me/Controls';
import { PersonalHost } from './screens/me/effects';
import { RequestsBrowse, RequestsDiscover } from './screens/requests/Requests';
import { RequestsMine } from './screens/requests/mine';
import { MusicBrowse, MusicDiscover } from './screens/requests/music';
import { BooksBrowse, BooksDiscover } from './screens/requests/books';
import { LiveOnNow } from './screens/livetv/OnNow';
import { LiveGuide } from './screens/livetv/Guide';
import { LiveChannels } from './screens/livetv/Channels';
import { LiveRecordings } from './screens/livetv/Recordings';

/* A module that loads on first use (the player pulls in hls.js, the reader epub.js; the admin pages are for admins
   only). After a plugin update an open tab asks for the old chunk: the page reloads once to pick up the new build. */
function lazyModule<M>(load: () => Promise<M>): () => M | null {
  let loaded: M | null = null;
  return function useModule() {
    const [mod, setMod] = useState<M | null>(loaded);
    useEffect(() => {
      if (mod) return;
      load().then(
        (m) => {
          loaded = m;
          try { sessionStorage.removeItem('seedy.reloaded'); } catch { /* storage unavailable */ }
          setMod(m);
        },
        () => {
          let reloaded = false;
          try { reloaded = sessionStorage.getItem('seedy.reloaded') === '1'; sessionStorage.setItem('seedy.reloaded', '1'); } catch { /* storage unavailable */ }
          if (!reloaded) window.location.reload();
        }
      );
    }, []);
    return mod;
  };
}

const usePlayer = lazyModule(() => import('./player/Player'));
const useReader = lazyModule(() => import('./screens/books/reader/Reader'));
const useAdmin = lazyModule(() => import('./screens/admin-routes'));

const Spinner = ({ background }: { background?: string }) => <div class="boot" style={background ? { background } : undefined}><div class="spinner" /></div>;

function LazyPlayer(props: { id: string }) {
  const m = usePlayer();
  return m ? <m.Player {...props} /> : <Spinner background="#000" />;
}

function LazyReader(props: { id: string }) {
  const m = useReader();
  return m ? <m.Reader {...props} /> : <Spinner background="#111111" />;
}

type AdminScreens = typeof import('./screens/admin-routes');
function Admin({ render }: { render: (m: AdminScreens) => preact.JSX.Element }) {
  const m = useAdmin();
  return m ? render(m) : <Spinner />;
}

type Route = { pattern: string; render: (p: Record<string, string>) => preact.JSX.Element; admin?: boolean };

const ROUTES: Route[] = [
  { pattern: '/', render: () => <Home /> },
  { pattern: '/library/:id', render: (p) => <Library id={p.id} /> },
  { pattern: '/details/:id', render: (p) => <Details id={p.id} /> },
  { pattern: '/favorites', render: () => <Favorites /> },
  { pattern: '/history', render: () => <History /> },
  { pattern: '/search', render: () => <Search /> },
  { pattern: '/request/:type/:tmdbId', render: (p) => <SeerrDetails mediaType={p.type as 'movie' | 'tv'} tmdbId={Number(p.tmdbId)} /> },
  { pattern: '/list/:kind', render: (p) => <ListScreen kind={p.kind} /> },
  { pattern: '/play/:id', render: (p) => <LazyPlayer id={p.id} /> },
  { pattern: '/read/:id', render: (p) => <LazyReader id={p.id} /> },
  { pattern: '/livetv', render: () => <LiveOnNow /> },
  { pattern: '/livetv/guide', render: () => <LiveGuide /> },
  { pattern: '/livetv/channels', render: () => <LiveChannels /> },
  { pattern: '/livetv/recordings', render: () => <LiveRecordings /> },
  { pattern: '/settings', render: () => <MeProfile /> },
  { pattern: '/settings/home', render: () => <MeHome /> },
  { pattern: '/settings/playback', render: () => <MePlayback /> },
  { pattern: '/settings/subtitles', render: () => <MeSubtitles /> },
  { pattern: '/settings/display', render: () => <MeDisplay /> },
  { pattern: '/settings/controls', render: () => <MeControls /> },
  { pattern: '/requests', render: () => <RequestsDiscover /> },
  { pattern: '/requests/my', render: () => <RequestsMine scope="mine" /> },
  { pattern: '/requests/all', render: () => <RequestsMine scope="all" /> },
  { pattern: '/requests/discover/:kind', render: (p) => <RequestsBrowse kind={p.kind} /> },
  { pattern: '/requests/music', render: () => <MusicDiscover /> },
  { pattern: '/requests/music/discover/:row', render: (p) => <MusicBrowse row={p.row} /> },
  { pattern: '/requests/books', render: () => <BooksDiscover /> },
  { pattern: '/requests/books/discover/:row', render: (p) => <BooksBrowse row={p.row} /> },
  { pattern: '/admin', render: () => <Admin render={(m) => <m.AdminGeneral />} />, admin: true },
  { pattern: '/admin/home', render: () => <Admin render={(m) => <m.AdminHome />} />, admin: true },
  { pattern: '/admin/requests', render: () => <Admin render={(m) => <m.AdminRequests />} />, admin: true },
  { pattern: '/admin/media-requests', render: () => <Admin render={(m) => <m.AdminMediaRequests />} />, admin: true },
  { pattern: '/admin/statistics', render: () => <Admin render={(m) => <m.AdminStatistics />} />, admin: true },
  { pattern: '/admin/statistics/:date', render: (p) => <Admin render={(m) => <m.AdminStatisticsDay date={p.date} />} />, admin: true },
  { pattern: '/admin/playlog', render: () => <Admin render={(m) => <m.AdminPlayLog />} />, admin: true },
  { pattern: '/admin/server', render: () => <Admin render={(m) => <m.ServerOverview />} />, admin: true },
  { pattern: '/admin/settings', render: () => <Admin render={(m) => <m.ServerSettings />} />, admin: true },
  { pattern: '/admin/users', render: () => <Admin render={(m) => <m.ServerUsers />} />, admin: true },
  { pattern: '/admin/users/:id', render: (p) => <Admin render={(m) => <m.ServerUserEdit id={p.id} />} />, admin: true },
  { pattern: '/admin/libraries', render: () => <Admin render={(m) => <m.ServerLibraries />} />, admin: true },
  { pattern: '/admin/playback', render: () => <Admin render={(m) => <m.ServerPlayback />} />, admin: true },
  { pattern: '/admin/networking', render: () => <Admin render={(m) => <m.ServerNetworking />} />, admin: true },
  { pattern: '/admin/livetv', render: () => <Admin render={(m) => <m.ServerLiveTV />} />, admin: true },
  { pattern: '/admin/devices', render: () => <Admin render={(m) => <m.ServerDevices />} />, admin: true },
  { pattern: '/admin/activity', render: () => <Admin render={(m) => <m.ServerActivity />} />, admin: true },
  { pattern: '/admin/plugins', render: () => <Admin render={(m) => <m.ServerPlugins />} />, admin: true },
  { pattern: '/admin/tasks', render: () => <Admin render={(m) => <m.ServerTasks />} />, admin: true },
  { pattern: '/admin/backups', render: () => <Admin render={(m) => <m.ServerBackups />} />, admin: true },
  { pattern: '/admin/logs', render: () => <Admin render={(m) => <m.ServerLogs />} />, admin: true },
  { pattern: '/admin/apikeys', render: () => <Admin render={(m) => <m.ServerApiKeys />} />, admin: true }
];

export function App() {
  const state = useStore(bootState);
  const loc = useLocation();
  const me = useStore(meStore);
  useEffect(() => { if (state === 'ready') startReadyWatch(); }, [state]);

  if (state === 'booting') return <div class="boot"><div class="spinner" /></div>;
  if (state === 'signedOut') return <><SignIn /><ToastHost /><PartyHost /></>;
  if (state === 'offline') return <><Unreachable /><ToastHost /></>;

  let view: preact.JSX.Element = <NotFound />;
  for (const r of ROUTES) {
    const params = match(r.pattern, loc.path);
    if (!params) continue;
    view = r.admin && !me.isAdmin ? <NotFound /> : r.render(params);
    break;
  }

  return (
    <>
      <div key={loc.path}>{view}</div>
      <ConfirmHost />
      <ToastHost />
      <MeduToastHost />
      <PersonalHost />
      <ManageHost />
      <AudioHost />
      <PartyHost />
    </>
  );
}
