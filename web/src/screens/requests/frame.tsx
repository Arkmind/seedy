import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { meStore } from '../../lib/app-state';
import { t } from '../../lib/i18n';
import { Link, navigate, setQuery, useLocation } from '../../lib/router';
import { useStore } from '../../lib/store';
import { Icon, type IconName } from '../../ui/icons';
import { Header } from '../../ui/navbar/Header';
import { EmptyState } from '../browse/parts';
import { SERVICE_NAMES, useMediaSummary } from './media-parts';
import { useSummary } from './parts';

/* The Requests page's frame (Requests, RequestsMusic, RequestsBooks, RequestsMine boards): the title with the
   Movies and shows · Music · Books tabs (only for the services the server has), the line saying where requests go,
   Open in Seerr/Lidarr/Shelfarr, then the search and the Discover · My requests · All requests tabs. */

export type Service = 'movies' | 'music' | 'books';
export type Tab = 'discover' | 'my' | 'all';

const SERVICES: { id: Service; path: string; label: string; icon: IconName }[] = [
  { id: 'movies', path: '/requests', label: 'Movies and shows', icon: 'screen' },
  { id: 'music', path: '/requests/music', label: 'Music', icon: 'notes' },
  { id: 'books', path: '/requests/books', label: 'Books', icon: 'book' }
];

export const servicePath = (s: Service) => SERVICES.find((x) => x.id === s)!.path;

/* The services this person can request from. */
export function useServices(): Service[] {
  const me = useStore(meStore);
  return SERVICES.filter((s) => (s.id === 'movies' ? me.requests.connected : s.id === 'music' ? !!me.media?.music : !!me.media?.books)).map((s) => s.id);
}

/* "Ebooks and audiobooks from Shelfarr · comics and manga from Librarr", from where the admin sends each format. */
function booksLine(formats: { format: string; service: string }[]): string {
  const by = new Map<string, string[]>();
  for (const f of formats) by.set(f.service, [...(by.get(f.service) ?? []), f.format]);
  const words: Record<string, string> = { ebook: t('ebooks'), audiobook: t('audiobooks'), comic: t('comics and manga') };
  const parts = [...by.entries()].map(([service, fs]) => {
    const list = fs.map((f) => words[f] ?? f);
    const what = list.length > 1 ? t('{a} and {b}', { a: list.slice(0, -1).join(', '), b: list[list.length - 1]! }) : list[0]!;
    return t('{formats} from {service}', { formats: what, service: SERVICE_NAMES[service as keyof typeof SERVICE_NAMES] ?? service });
  });
  const line = parts.join(' · ');
  return line.charAt(0).toLocaleUpperCase() + line.slice(1);
}

function useServiceLine(service: Service | null): { line: string; open?: { url: string; name: string } } {
  const me = useStore(meStore);
  const services = useServices();
  if (service === 'movies') {
    const seerr = me.requests.seerrUrl?.trim();
    return { line: t('From Seerr · ask for anything the server doesn’t have'), open: seerr ? { url: seerr, name: 'Seerr' } : undefined };
  }
  if (service === 'music') {
    return { line: t('From Lidarr · any artist or album on MusicBrainz'), open: me.media?.lidarrUrl ? { url: me.media.lidarrUrl, name: 'Lidarr' } : undefined };
  }
  if (service === 'books') {
    const formats = (me.media?.formats ?? []).map((f) => ({ format: f, service: me.media?.bookRoutes?.[f] ?? 'shelfarr' }));
    const open = me.media?.shelfarrUrl ? { url: me.media.shelfarrUrl, name: 'Shelfarr' }
      : me.media?.librarrUrl ? { url: me.media.librarrUrl, name: 'Librarr' }
      : me.media?.readarrUrl ? { url: me.media.readarrUrl, name: 'Readarr' } : undefined;
    return { line: booksLine(formats), open };
  }
  const names = [
    ...(services.includes('movies') ? ['Seerr'] : []),
    ...(services.includes('music') ? ['Lidarr'] : []),
    ...[...new Set(Object.values(me.media?.bookRoutes ?? {}))].map((s) => SERVICE_NAMES[s as keyof typeof SERVICE_NAMES])
  ].filter(Boolean);
  const list = names.length > 1 ? t('{a} and {b}', { a: names.slice(0, -1).join(', '), b: names[names.length - 1]! }) : names[0] ?? 'Seerr';
  return { line: t('Everything you asked for, from {services}', { services: list }) };
}

/* The page's title, service tabs and Open in …; `service` is null on the shared My requests and All requests. */
export function Shell({ service, children }: { service: Service | null; children: ComponentChildren }) {
  const services = useServices();
  const { line, open } = useServiceLine(service);
  return (
    <div class="page">
      <Header active="requests" />
      <main class="reqs-main">
        <div class="page-title-row reqs-pad reqs-head">
          <div class="reqs-head-main">
            <div class="reqs-head-line">
              <h1 class="reqs-h1">{t('Requests')}</h1>
              {services.length > 1 && (
                <nav aria-label={t('What to request')} class="reqs-services hscroll">
                  {SERVICES.filter((s) => services.includes(s.id)).map((s) => (
                    <Link key={s.id} to={s.path} class="reqs-service" aria-current={s.id === service ? 'page' : undefined}>
                      <Icon name={s.icon} size={17} stroke={2.2} />
                      {t(s.label)}
                    </Link>
                  ))}
                </nav>
              )}
            </div>
            <span class="page-count">{line}</span>
          </div>
          {open && (
            <a href={open.url} target="_blank" rel="noreferrer" class="btn btn-secondary reqs-open">
              {t('Open in {app}', { app: open.name })}
              <Icon name="external" size={16} stroke={2.2} />
            </a>
          )}
        </div>
        {children}
      </main>
    </div>
  );
}

export const NotSetUp = () => <EmptyState icon="inbox" title={t('Requests aren\'t set up')} text={t('Your server isn’t connected to Seerr, or your profile can’t use it. Ask your admin.')} />;

/* The Discover · My requests · All requests tabs, with their counts. */
export function ViewTabs({ service, tab, query }: { service: Service | null; tab: Tab; query?: string }) {
  const services = useServices();
  const seerr = useSummary(services.includes('movies'));
  const media = useMediaSummary(services.includes('music') || services.includes('books'));
  const loc = useLocation();
  const type = service ?? (loc.query.get('type') as Service | null);
  type Counts = { open: number; pending: number; all: boolean };
  const counts = (s: Service | null): Counts => {
    if (s === 'movies') return { open: seerr?.open ?? 0, pending: seerr?.pending ?? 0, all: !!seerr?.canViewAll };
    if (s === 'music') return { open: media?.music.open ?? 0, pending: media?.music.pending ?? 0, all: !!media?.canManage };
    if (s === 'books') return { open: media?.books.open ?? 0, pending: media?.books.pending ?? 0, all: !!media?.canManage };
    const list: Counts[] = services.map(counts);
    return { open: list.reduce((n, c) => n + c.open, 0), pending: list.reduce((n, c) => n + c.pending, 0), all: list.some((c) => c.all) };
  };
  const c = counts(service);
  const discover = servicePath(type && services.includes(type) ? type : services[0] ?? 'movies');
  const keep = service && service !== 'movies' ? `?type=${service}` : type && type !== 'movies' && !service ? `?type=${type}` : '';
  const tabs: { id: Tab; to: string; label: string; count?: number }[] = [
    { id: 'discover', to: discover, label: 'Discover' },
    { id: 'my', to: '/requests/my' + keep, label: 'My requests', count: c.open },
    ...(c.all ? [{ id: 'all' as Tab, to: '/requests/all' + keep, label: 'All requests', count: c.pending }] : [])
  ];
  return (
    <nav aria-label={t('Requests views')} class="tab-row hscroll reqs-views">
      {tabs.map((x) => {
        const current = tab === x.id && !query;
        return (
          <Link key={x.id} to={x.to} class="tab reqs-tab" aria-current={current ? 'page' : undefined}>
            {t(x.label)}
            {!!x.count && (
              <span class={'reqs-count' + (x.id === 'all' ? ' is-pending' : '')} aria-label={x.id === 'all' ? t('{n} waiting for approval', { n: x.count }) : t('{n} open', { n: x.count })}>
                {x.count}
              </span>
            )}
          </Link>
        );
      })}
    </nav>
  );
}

/* The search above a service's Discover; while there's a query its results replace the tab. */
export function Frame({ service, tab, placeholder, label, results, children }: {
  service: Service;
  tab: Tab;
  placeholder: string;
  label: string;
  results: (query: string) => ComponentChildren;
  children: ComponentChildren;
}) {
  const services = useServices();
  const query = (useLocation().query.get('q') ?? '').trim();
  const [text, setText] = useState(query);
  useEffect(() => {
    const id = window.setTimeout(() => { if (text.trim() !== query) setQuery({ q: text.trim() || undefined }); }, 350);
    return () => window.clearTimeout(id);
  }, [text]);
  useEffect(() => { if (!query) setText((v) => (v.trim() ? '' : v)); }, [query]);
  // A service the server doesn't have (Seerr isn't set up but Lidarr is): open one it has.
  const missing = !services.includes(service) && services.length > 0;
  useEffect(() => { if (missing) navigate(servicePath(services[0]!), { replace: true }); }, [missing]);

  if (!services.includes(service)) {
    return (
      <Shell service={service}>
        <div class="reqs-pad"><NotSetUp /></div>
      </Shell>
    );
  }
  return (
    <Shell service={service}>
      <div class="reqs-bar">
        <label class="reqs-search">
          <Icon name="search" size={20} stroke={2.2} />
          <input
            type="search"
            aria-label={label}
            placeholder={placeholder}
            value={text}
            onInput={(e) => setText((e.currentTarget as HTMLInputElement).value)}
            onKeyDown={(e) => {
              if (e.key === 'Escape' && text) { e.preventDefault(); setText(''); setQuery({ q: undefined }); }
              if (e.key === 'Enter') setQuery({ q: text.trim() || undefined });
            }}
          />
        </label>
        <ViewTabs service={service} tab={tab} query={query} />
      </div>
      {query ? results(query) : children}
    </Shell>
  );
}
