import type { Plugin } from 'vite';
import type { IncomingMessage, ServerResponse } from 'node:http';

/* Dev-only stand-in for the Seedy plugin's /Seedy/* API. Built from real Jellyfin items so screens
   look right before the plugin is installed. Payload shapes match src/api/seedy.ts. */

const JF = process.env.SEEDY_SERVER ?? 'http://localhost:8096';

type Json = any;

const state: {
  prefs: Json;
  config: Json;
  hidden: Set<string>;
  accounts: Json[];
  profiles: Json[];
  requested: Set<number>;
} = {
  prefs: {
    playback: { skipIntro: 'button', skipRecap: 'button', skipCredits: 'button', autoPlayNext: true, upNextSeconds: 15, moviePicksSeconds: 15, seekBackSeconds: 10, seekForwardSeconds: 30, pauseScreenSeconds: 10, maxQuality: 'auto' },
    subtitles: { size: 'M', lift: true },
    adaptRowOrder: true,
    hiddenRows: [],
    hiddenSeeds: []
  },
  config: {
    opensAt: 'alongside',
    branding: { name: 'Seedy', accent: '#ff9fff', hasLogo: false, logoVersion: '0', signInBackground: 'backdrops' },
    playbackDefaults: { skipIntro: 'button', skipRecap: 'button', skipCredits: 'button', autoPlayNext: true, upNextSeconds: 15, moviePicksSeconds: 15, seekBackSeconds: 10, seekForwardSeconds: 30, pauseScreenSeconds: 10, maxQuality: 'auto' },
    home: {
      rows: [
        { kind: 'featured', enabled: true }, { kind: 'continue', enabled: true }, { kind: 'nextup', enabled: true }, { kind: 'top10', enabled: true },
        { kind: 'because', enabled: true, count: 6 }, { kind: 'genres', enabled: true, count: 4 }, { kind: 'mymedia', enabled: true },
        { kind: 'latest', enabled: true }, { kind: 'discover', enabled: true }, { kind: 'upcomingMovies', enabled: true },
        { kind: 'upcomingShows', enabled: true }, { kind: 'collections', enabled: false }
      ],
      adaptOrder: true,
      because: { watchedWhen: 'finished', lookBackDays: 60, minTitles: 4 },
      top10: { periodDays: 7, minProfiles: 3 },
      genres: { windowDays: 90, starting: ['Thriller', 'Comedy', 'Drama', 'Documentary'] },
      featured: { mode: 'both', picks: [] }
    },
    requests: {
      url: 'http://seerr.local:5055', apiKeyHint: '4f2a', showDiscover: true, showUpcomingMovies: true, showRequestButtons: true,
      showRequestInSearch: true, upcomingSource: 'requests', who: 'everyone', allow4k: false
    },
    playLog: { enabled: true, retentionDays: 365, useTop10: true, useHistory: true, useRowOrder: true, useGenres: true }
  },
  hidden: new Set(),
  accounts: [],
  profiles: [],
  requested: new Set()
};

const DISCOVER = [
  ['Meridian', 'movie', 8.3, '2026'], ['Lowland', 'tv', 8.4, '2026'], ['The Cartographer', 'movie', 7.1, '2026'], ['Tidewater', 'movie', 7.2, '2026'],
  ['Deep Station', 'movie', 6.8, '2026'], ['Iron Mile', 'tv', 8.3, '2022'], ['Glasshouse', 'movie', 8.4, '2026'], ['Salt & Light', 'movie', 6.8, '2026'],
  ['Harrowgate', 'movie', 7.4, '2026'], ['Harrowgate', 'tv', 8.1, '2023'], ['Harrowgate Hall', 'movie', 6.2, '2019'], ['The Harrowgate Files', 'tv', 7.0, '2021']
].map(([title, mediaType, rating, year], i) => ({
  tmdbId: 9000 + i, mediaType, title, rating, year, posterPath: null, backdropPath: null,
  overview: 'A placeholder overview from the Seedy dev mock. The real plugin fills this in from Seerr.',
  status: i === 1 || i === 9 ? 'pending' : i === 3 ? 'available' : 'none',
  requestedBy: i === 1 ? 'Alex' : i === 9 ? 'Sam' : undefined
}));

function send(res: ServerResponse, status: number, body?: unknown) {
  res.statusCode = status;
  if (body === undefined) { res.end(); return; }
  res.setHeader('Content-Type', 'application/json');
  res.end(JSON.stringify(body));
}

async function readBody(req: IncomingMessage): Promise<Json> {
  const chunks: Buffer[] = [];
  for await (const c of req) chunks.push(c as Buffer);
  const raw = Buffer.concat(chunks).toString('utf8');
  try { return raw ? JSON.parse(raw) : {}; } catch { return {}; }
}

async function jf(req: IncomingMessage, path: string): Promise<Json> {
  const r = await fetch(JF + path, { headers: { Authorization: String(req.headers.authorization ?? ''), Accept: 'application/json' } });
  if (!r.ok) throw new Error(`${r.status} ${path}`);
  return r.json();
}

async function me(req: IncomingMessage): Promise<Json> {
  return jf(req, '/Users/Me');
}

const FIELDS = 'PrimaryImageAspectRatio,ProductionYear,Genres,ChildCount,RecursiveItemCount,Overview,DateCreated';

async function handle(req: IncomingMessage, res: ServerResponse, path: string, query: URLSearchParams): Promise<boolean> {
  const m = req.method ?? 'GET';
  const user = path.startsWith('/Seedy/Client') ? null : await me(req).catch(() => null);
  const uid = user?.Id;

  if (path === '/Seedy/Client') return send(res, 200, state.config.branding), true;
  if (path === '/Seedy/Client/Backdrops') return send(res, 200, []), true;

  if (!user) return send(res, 401, { message: 'Unauthorized' }), true;

  if (path === '/Seedy/Me' && m === 'GET') {
    const c = state.config;
    return send(res, 200, {
      isAdmin: !!user.Policy?.IsAdministrator,
      branding: c.branding,
      prefs: state.prefs,
      defaults: c.playbackDefaults,
      requests: {
        connected: true, discover: c.requests.showDiscover, upcomingMovies: c.requests.showUpcomingMovies, requestButtons: c.requests.showRequestButtons,
        requestInSearch: c.requests.showRequestInSearch, canRequest: true, allow4k: c.requests.allow4k, seerrUrl: c.requests.url
      },
      historyEnabled: true,
      pluginVersion: '0.1.0'
    }), true;
  }
  if (path === '/Seedy/Me/Prefs' && m === 'POST') {
    const b = await readBody(req);
    state.prefs = { ...state.prefs, ...b, playback: { ...state.prefs.playback, ...(b.playback ?? {}) }, subtitles: { ...state.prefs.subtitles, ...(b.subtitles ?? {}) } };
    return send(res, 200, state.prefs), true;
  }
  if (path === '/Seedy/Me/RowOrder' && m === 'DELETE') return send(res, 204), true;

  if (path === '/Seedy/Home') {
    const views = (await jf(req, `/UserViews?userId=${uid}`)).Items as Json[];
    const played = (await jf(req, `/Items?userId=${uid}&Recursive=true&IncludeItemTypes=Movie,Series&SortBy=Random&Limit=2&fields=${FIELDS}`)).Items as Json[];
    const libs = views.filter((v) => v.CollectionType === 'movies' || v.CollectionType === 'tvshows');
    const rows: Json[] = [
      { id: 'featured', kind: 'featured' }, { id: 'continue', kind: 'continue' }, { id: 'nextup', kind: 'nextup' }, { id: 'top10', kind: 'top10' }
    ];
    if (played[0]) rows.push({ id: 'because:' + played[0].Id, kind: 'because', seedId: played[0].Id, seedName: played[0].Name });
    rows.push({ id: 'mymedia', kind: 'mymedia' });
    if (libs[0]) rows.push({ id: 'latest:' + libs[0].Id, kind: 'latest', libraryId: libs[0].Id, libraryName: libs[0].Name, collectionType: libs[0].CollectionType });
    rows.push({ id: 'genre:Drama', kind: 'genres', genre: 'Drama', title: 'Dramas' });
    if (played[1]) rows.push({ id: 'because:' + played[1].Id, kind: 'because', seedId: played[1].Id, seedName: played[1].Name });
    if (libs[1]) rows.push({ id: 'latest:' + libs[1].Id, kind: 'latest', libraryId: libs[1].Id, libraryName: libs[1].Name, collectionType: libs[1].CollectionType });
    rows.push({ id: 'genre:Comedy', kind: 'genres', genre: 'Comedy', title: 'Comedies' });
    rows.push({ id: 'discover', kind: 'discover' }, { id: 'upcomingMovies', kind: 'upcomingMovies' }, { id: 'upcomingShows', kind: 'upcomingShows' });
    return send(res, 200, { rows }), true;
  }
  if (path === '/Seedy/Home/Featured') {
    const items = (await jf(req, `/Items?userId=${uid}&Recursive=true&IncludeItemTypes=Movie,Series&SortBy=DateCreated&SortOrder=Descending&Limit=5&HasBackdrop=true&fields=${FIELDS},Overview,Genres,OfficialRating,CommunityRating`)).Items as Json[];
    const out = [];
    for (const it of items) {
      let play = it;
      if (it.Type === 'Series') {
        const nu = (await jf(req, `/Shows/NextUp?userId=${uid}&seriesId=${it.Id}&limit=1&enableResumable=true&disableFirstEpisode=false`)).Items[0];
        play = nu ?? it;
      }
      out.push({ item: it, reason: it.Type === 'Series' ? 'New season' : 'Just added', play });
    }
    return send(res, 200, out), true;
  }
  if (path === '/Seedy/Home/Top10') {
    const items = (await jf(req, `/Items?userId=${uid}&Recursive=true&IncludeItemTypes=Movie,Series&SortBy=Random&Limit=10&fields=${FIELDS}`)).Items as Json[];
    return send(res, 200, { quiet: false, items: items.map((item, i) => ({ item, profiles: Math.max(2, 9 - i), plays: 20 - i })) }), true;
  }
  if (path === '/Seedy/Plays/Origin') return send(res, 204), true;

  if (path === '/Seedy/Plays' && m === 'GET') {
    const items = (await jf(req, `/Items?userId=${uid}&Recursive=true&IncludeItemTypes=Movie,Episode&SortBy=Random&Limit=12&fields=${FIELDS}`)).Items as Json[];
    const now = Date.now();
    const devices = ['Living room TV', 'iPhone', 'Laptop', 'iPad'];
    const list = items.map((item, i) => {
      const run = item.RunTimeTicks ?? 0;
      const finished = i % 3 === 2;
      const pos = finished ? run : Math.round(run * (0.2 + (i % 5) * 0.15));
      const titleId = item.SeriesId ?? item.Id;
      return {
        id: 'p' + i, itemId: item.Id, titleId, item, startedAt: new Date(now - i * 11 * 3600 * 1000).toISOString(),
        stoppedAt: new Date(now - i * 11 * 3600 * 1000 + 40 * 60000).toISOString(), positionTicks: pos, runtimeTicks: run, finished,
        deviceName: devices[i % devices.length], client: 'Seedy', hidden: state.hidden.has(titleId)
      };
    }).filter((e) => query.get('includeHidden') === 'true' || !e.hidden);
    return send(res, 200, { items: list, total: list.length }), true;
  }
  const hideMatch = path.match(/^\/Seedy\/Plays\/Titles\/([^/]+)\/Hidden$/);
  if (hideMatch && m === 'POST') {
    const b = await readBody(req);
    if (b.hidden) state.hidden.add(hideMatch[1]); else state.hidden.delete(hideMatch[1]);
    return send(res, 204), true;
  }
  if (path.startsWith('/Seedy/Plays') && m === 'DELETE') return send(res, 204), true;

  if (path === '/Seedy/Requests/Discover') return send(res, 200, { results: DISCOVER.slice(0, 8).map(withReq) }), true;
  if (path === '/Seedy/Requests/Search') {
    const q = (query.get('query') ?? '').toLowerCase();
    const results = DISCOVER.filter((d) => d.title.toLowerCase().includes(q)).map(withReq);
    return send(res, 200, { results, total: results.length }), true;
  }
  if (path === '/Seedy/Requests/Upcoming') {
    const t = Date.now();
    const days = [0, 8, 15, 29, 43, 66];
    return send(res, 200, { results: ['Fever Ward', 'The Lost Isle', 'Prime Hours', 'Long Voyage', 'Knockout Alley', 'Captain Bramble'].map((title, i) => ({
      tmdbId: 9100 + i, mediaType: 'movie', title, year: '2026', status: 'processing', posterPath: null,
      releaseDate: new Date(t + days[i] * 86400000).toISOString(), releaseKind: 'digital'
    })) }), true;
  }
  const det = path.match(/^\/Seedy\/Requests\/Media\/(movie|tv)\/(\d+)$/);
  if (det) {
    const base = DISCOVER.find((d) => d.tmdbId === Number(det[2])) ?? { ...DISCOVER[0], tmdbId: Number(det[2]), mediaType: det[1] };
    return send(res, 200, {
      ...withReq(base), tagline: 'Every map has an edge.', genres: ['Drama', 'Thriller'], runtime: det[1] === 'movie' ? 112 : undefined,
      releaseDate: '2026-03-14', certification: '15', cast: [{ id: 1, name: 'Petra Lind', character: 'Kit' }, { id: 2, name: 'Tomas Reyes', character: 'Oren' }],
      crew: [{ name: 'Greta Moss', job: 'Director' }], networks: det[1] === 'tv' ? ['Halden'] : undefined, numberOfSeasons: det[1] === 'tv' ? 2 : undefined,
      seasons: det[1] === 'tv' ? [{ seasonNumber: 1, name: 'Season 1', episodeCount: 8, status: 'none' }, { seasonNumber: 2, name: 'Season 2', episodeCount: 8, status: 'none' }] : undefined
    }), true;
  }
  if (path === '/Seedy/Requests' && m === 'POST') {
    const b = await readBody(req);
    state.requested.add(Number(b.tmdbId));
    return send(res, 200, { status: 'pending' }), true;
  }

  if (path.startsWith('/Seedy/Admin') && !user.Policy?.IsAdministrator) return send(res, 403, { message: 'Forbidden' }), true;
  if (path === '/Seedy/Admin/Config') {
    if (m === 'POST') state.config = await readBody(req);
    return send(res, 200, state.config), true;
  }
  if (path === '/Seedy/Admin/Status') {
    return send(res, 200, {
      pluginVersion: '0.1.0', latestVersion: '0.1.0', upToDate: true, serverVersion: '12.2.0', serverSupported: true,
      seerr: { state: 'connected', checkedAt: new Date(Date.now() - 120000).toISOString() },
      playLog: { enabled: state.config.playLog.enabled, retentionDays: state.config.playLog.retentionDays, plays: 953 }
    }), true;
  }
  if (path === '/Seedy/Admin/Logo') return send(res, 200, state.config), true;
  if (path === '/Seedy/Admin/Seerr/Test') return send(res, 200, { ok: true, message: 'Connected. Seerr can see Radarr and Sonarr.', version: '2.7.3', radarr: true, sonarr: true }), true;
  if (path === '/Seedy/Admin/Seerr/Key') { state.config.requests.apiKeyHint = 'a91c'; return send(res, 200, state.config), true; }
  if (path.startsWith('/Seedy/Admin/Seerr/') && path !== '/Seedy/Admin/Seerr/Users') {
    if (!state.accounts.length) {
      const users = (await jf(req, '/Users')) as Json[];
      state.accounts = users.map((u, i) => ({
        userId: u.Id, name: u.Name, isAdmin: !!u.Policy?.IsAdministrator, hasImage: !!u.PrimaryImageTag, imageTag: u.PrimaryImageTag,
        maxParentalRating: u.Name === 'Kids' ? 'TV-PG' : undefined,
        seerrUserId: i < 2 ? i + 1 : undefined, seerrName: i < 2 ? u.Name.toLowerCase() : undefined, matchedBy: i < 2 ? 'jellyfin' : undefined, canRequest: i < 2
      }));
    }
    if (path === '/Seedy/Admin/Seerr/CanRequest') {
      const b = await readBody(req);
      state.accounts = state.accounts.map((a) => (a.userId === b.userId ? { ...a, canRequest: b.canRequest } : a));
    }
    if (path === '/Seedy/Admin/Seerr/Link') {
      const b = await readBody(req);
      state.accounts = state.accounts.map((a) => (a.userId === b.userId ? { ...a, seerrUserId: b.seerrUserId ?? undefined, seerrName: b.seerrUserId ? 'user' + b.seerrUserId : undefined, matchedBy: b.seerrUserId ? 'manual' : undefined } : a));
    }
    return send(res, 200, state.accounts), true;
  }
  if (path === '/Seedy/Admin/Seerr/Users') return send(res, 200, [{ id: 1, displayName: 'alex' }, { id: 2, displayName: 'sam' }, { id: 3, displayName: 'lea' }]), true;
  if (path.startsWith('/Seedy/Admin/PlayLog/Profiles')) {
    if (!state.profiles.length) {
      const users = (await jf(req, '/Users')) as Json[];
      state.profiles = users.map((u, i) => ({ userId: u.Id, name: u.Name, imageTag: u.PrimaryImageTag, plays: [412, 288, 157, 96][i] ?? 10, top10: i < 2, history: i < 3 }));
    }
    const one = path.match(/Profiles\/([^/]+)$/);
    if (one && m === 'POST') {
      const b = await readBody(req);
      state.profiles = state.profiles.map((p) => (p.userId === one[1] ? { ...p, ...b } : p));
    }
    return send(res, 200, state.profiles), true;
  }
  if (path === '/Seedy/Admin/PlayLog' && m === 'DELETE') return send(res, 204), true;
  if (path === '/Seedy/Admin/PlayLog/Export') {
    res.setHeader('Content-Disposition', 'attachment; filename="plays.csv"');
    res.setHeader('Content-Type', 'text/csv');
    res.end('started,title\n');
    return true;
  }
  return false;
}

function withReq(d: Json) {
  return state.requested.has(d.tmdbId) ? { ...d, status: 'pending', requestedBy: 'you', requestedByMe: true } : d;
}

export function seedyMock(): Plugin {
  return {
    name: 'seedy-mock',
    configureServer(server) {
      server.middlewares.use(async (req, res, next) => {
        const u = new URL(req.url ?? '/', 'http://x');
        if (!u.pathname.startsWith('/Seedy/')) return next();
        try {
          if (!(await handle(req, res, u.pathname, u.searchParams))) send(res, 404, { message: 'Not in the mock' });
        } catch (e) {
          send(res, 500, { message: String(e) });
        }
      });
    }
  };
}
