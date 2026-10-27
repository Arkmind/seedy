import type { ComponentType } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { boot, bootState, meStore } from './lib/app-state';
import { BrandMark } from './ui/navbar/Header';
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
import { AdminGeneral } from './screens/admin/General';
import { AdminHome } from './screens/admin/HomeScreen';
import { AdminRequests } from './screens/admin/Requests';
import { AdminMediaRequests } from './screens/admin/MediaRequests';
import { AdminPlayLog } from './screens/admin/PlayLog';
import { MeProfile } from './screens/me/Profile';
import { MeHome } from './screens/me/HomeScreen';
import { MePlayback } from './screens/me/Playback';
import { MeSubtitles } from './screens/me/Subtitles';
import { MeDisplay } from './screens/me/Display';
import { MeControls } from './screens/me/Controls';
import { PersonalHost } from './screens/me/effects';
import { ServerOverview } from './screens/server/Overview';
import { ServerSettings } from './screens/server/Settings';
import { ServerUsers } from './screens/server/Users';
import { ServerUserEdit } from './screens/server/UserEdit';
import { ServerLibraries } from './screens/server/Libraries';
import { ServerPlayback } from './screens/server/Playback';
import { ServerNetworking } from './screens/server/Networking';
import { ServerLiveTV } from './screens/server/LiveTV';
import { ServerDevices } from './screens/server/Devices';
import { ServerActivity } from './screens/server/Activity';
import { ServerPlugins } from './screens/server/Plugins';
import { ServerTasks } from './screens/server/Tasks';
import { ServerBackups } from './screens/server/Backups';
import { ServerLogs } from './screens/server/Logs';
import { ServerApiKeys } from './screens/server/ApiKeys';
import { RequestsBrowse, RequestsDiscover } from './screens/requests/Requests';
import { RequestsMine } from './screens/requests/mine';
import { MusicBrowse, MusicDiscover } from './screens/requests/music';
import { BooksBrowse, BooksDiscover } from './screens/requests/books';
import { LiveOnNow } from './screens/livetv/OnNow';
import { LiveGuide } from './screens/livetv/Guide';
import { LiveChannels } from './screens/livetv/Channels';
import { LiveRecordings } from './screens/livetv/Recordings';
import { t } from './lib/i18n';

/* The player pulls in hls.js, so it loads on first play. */
function LazyPlayer(props: { id: string }) {
  const [Comp, setComp] = useState<ComponentType<{ id: string }> | null>(null);
  useEffect(() => {
    import('./player/Player').then(
      (m) => {
        try { sessionStorage.removeItem('seedy.reloaded'); } catch { /* storage unavailable */ }
        setComp(() => m.Player);
      },
      () => {
        // After a plugin update an open tab asks for the old player chunk; reload once to pick up the new build.
        let reloaded = false;
        try { reloaded = sessionStorage.getItem('seedy.reloaded') === '1'; sessionStorage.setItem('seedy.reloaded', '1'); } catch { /* storage unavailable */ }
        if (!reloaded) window.location.reload();
      }
    );
  }, []);
  if (!Comp) return <div class="boot" style={{ background: '#000' }}><div class="spinner" /></div>;
  return <Comp {...props} />;
}

/* The book reader pulls in epub.js, so it loads on first use too. */
function LazyReader(props: { id: string }) {
  const [Comp, setComp] = useState<ComponentType<{ id: string }> | null>(null);
  useEffect(() => {
    import('./screens/books/reader/Reader').then((m) => setComp(() => m.Reader), () => window.location.reload());
  }, []);
  if (!Comp) return <div class="boot" style={{ background: '#111111' }}><div class="spinner" /></div>;
  return <Comp {...props} />;
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
  { pattern: '/admin', render: () => <AdminGeneral />, admin: true },
  { pattern: '/admin/home', render: () => <AdminHome />, admin: true },
  { pattern: '/admin/requests', render: () => <AdminRequests />, admin: true },
  { pattern: '/admin/media-requests', render: () => <AdminMediaRequests />, admin: true },
  { pattern: '/admin/playlog', render: () => <AdminPlayLog />, admin: true },
  { pattern: '/admin/server', render: () => <ServerOverview />, admin: true },
  { pattern: '/admin/settings', render: () => <ServerSettings />, admin: true },
  { pattern: '/admin/users', render: () => <ServerUsers />, admin: true },
  { pattern: '/admin/users/:id', render: (p) => <ServerUserEdit id={p.id} />, admin: true },
  { pattern: '/admin/libraries', render: () => <ServerLibraries />, admin: true },
  { pattern: '/admin/playback', render: () => <ServerPlayback />, admin: true },
  { pattern: '/admin/networking', render: () => <ServerNetworking />, admin: true },
  { pattern: '/admin/livetv', render: () => <ServerLiveTV />, admin: true },
  { pattern: '/admin/devices', render: () => <ServerDevices />, admin: true },
  { pattern: '/admin/activity', render: () => <ServerActivity />, admin: true },
  { pattern: '/admin/plugins', render: () => <ServerPlugins />, admin: true },
  { pattern: '/admin/tasks', render: () => <ServerTasks />, admin: true },
  { pattern: '/admin/backups', render: () => <ServerBackups />, admin: true },
  { pattern: '/admin/logs', render: () => <ServerLogs />, admin: true },
  { pattern: '/admin/apikeys', render: () => <ServerApiKeys />, admin: true }
];

export function App() {
  const state = useStore(bootState);
  const loc = useLocation();
  const me = useStore(meStore);

  if (state === 'booting') return <div class="boot"><div class="spinner" /></div>;
  if (state === 'signedOut') return <><SignIn /><ToastHost /></>;
  if (state === 'offline')
    return (
      <div class="boot">
        <div class="error-box" style={{ maxWidth: '440px', alignItems: 'center', textAlign: 'center' }}>
          <BrandMark size={48} />
          <h2>{t("Can't reach the server")}</h2>
          <p>{t("It may be restarting after an update. You're still signed in.")}</p>
          <button type="button" class="btn btn-primary is-sm" onClick={() => boot()}>{t('Try again')}</button>
        </div>
      </div>
    );

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
      <PersonalHost />
      <ManageHost />
      <AudioHost />
    </>
  );
}
