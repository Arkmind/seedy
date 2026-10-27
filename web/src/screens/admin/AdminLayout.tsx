import type { ComponentChildren } from 'preact';
import { useLayoutEffect, useRef } from 'preact/hooks';
import { APP_BASE } from '../../api/client';
import { Link, navigate } from '../../lib/router';
import { AccountButton, Brand } from '../../ui/navbar/Header';
import { useStickyHeader } from '../../ui/sticky';
import { useStore } from '../../lib/store';
import { pluginUpdatesStore } from '../server/status';
import { canLeave, hasUnsavedChanges } from './config';
import { t, tn } from '../../lib/i18n';
import './admin.css';

export type AdminPage =
  | 'general' | 'home' | 'requests' | 'mediaRequests' | 'playlog'
  | 'overview' | 'settings' | 'users' | 'libraries' | 'playback' | 'networking' | 'livetv'
  | 'devices' | 'activity'
  | 'plugins' | 'tasks' | 'backups' | 'logs' | 'apikeys';

interface NavItem { id: AdminPage; path: string; label: string }

/* The admin's sections, grouped as on the boards. Seedy's own settings first, then Jellyfin's server dashboard. */
export const ADMIN_NAV: { label: string; items: NavItem[] }[] = [
  {
    label: 'Seedy',
    items: [
      { id: 'general', path: '/admin', label: 'Branding & playback' },
      { id: 'home', path: '/admin/home', label: 'Home screen' },
      { id: 'requests', path: '/admin/requests', label: 'Requests' },
      { id: 'mediaRequests', path: '/admin/media-requests', label: 'Music and book requests' },
      { id: 'playlog', path: '/admin/playlog', label: 'Play log' }
    ]
  },
  {
    label: 'Server',
    items: [
      { id: 'overview', path: '/admin/server', label: 'Overview' },
      { id: 'settings', path: '/admin/settings', label: 'Settings' },
      { id: 'users', path: '/admin/users', label: 'Users' },
      { id: 'libraries', path: '/admin/libraries', label: 'Libraries' },
      { id: 'playback', path: '/admin/playback', label: 'Playback' },
      { id: 'networking', path: '/admin/networking', label: 'Networking' },
      { id: 'livetv', path: '/admin/livetv', label: 'Live TV' }
    ]
  },
  {
    label: 'Activity',
    items: [
      { id: 'devices', path: '/admin/devices', label: 'Devices' },
      { id: 'activity', path: '/admin/activity', label: 'Activity log' }
    ]
  },
  {
    label: 'Advanced',
    items: [
      { id: 'plugins', path: '/admin/plugins', label: 'Plugins' },
      { id: 'tasks', path: '/admin/tasks', label: 'Scheduled tasks' },
      { id: 'backups', path: '/admin/backups', label: 'Backups' },
      { id: 'logs', path: '/admin/logs', label: 'Logs' },
      { id: 'apikeys', path: '/admin/apikeys', label: 'API keys' }
    ]
  }
];

/* Clicks on in-app links are held back while a page has unsaved changes, and go ahead once confirmed. */
function guardLinks(e: MouseEvent) {
  if (!hasUnsavedChanges() || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
  const a = (e.target as Element | null)?.closest?.('a[href]') as HTMLAnchorElement | null;
  if (!a || a.target === '_blank') return;
  const url = new URL(a.href, window.location.href);
  if (url.origin !== window.location.origin || !url.pathname.toLowerCase().startsWith(APP_BASE.toLowerCase())) return;
  const path = url.pathname.slice(APP_BASE.length) || '/';
  /* At the server address every same-origin link starts with APP_BASE; Jellyfin's own pages and API aren't Seedy's. */
  if (!path.startsWith('/') || /^\/(old|web|seedy)(\/|$)/i.test(path) || /^\/[A-Z]/.test(path)) return;
  e.preventDefault();
  e.stopPropagation();
  canLeave().then((ok) => { if (ok) navigate(path + url.search, { force: true }); });
}

export function AdminLayout({ page, title, lede, children, wide, actions, overline }: {
  page: AdminPage;
  title: ComponentChildren;
  lede?: ComponentChildren;
  children: ComponentChildren;
  /* Server dashboard pages use the wider 1020 px column. */
  wide?: boolean;
  /* Buttons on the right of the title ("Scan all libraries", "Add user"). */
  actions?: ComponentChildren;
  /* A back link above the title ("‹ Users"). */
  overline?: ComponentChildren;
}) {
  const nav = useRef<HTMLElement>(null);
  const header = useStickyHeader<HTMLElement>();
  const updates = useStore(pluginUpdatesStore);
  /* On phones the section nav scrolls sideways; bring the current page into view. */
  useLayoutEffect(() => {
    const el = nav.current;
    const current = el?.querySelector<HTMLElement>('[aria-current="page"]');
    if (!el || !current || el.scrollWidth <= el.clientWidth) return;
    el.scrollLeft = Math.max(0, current.offsetLeft - el.offsetLeft - (el.clientWidth - current.offsetWidth) / 2);
  }, [page]);

  return (
    <div class={'page admin-page' + (wide ? ' is-wide' : '')} onClickCapture={guardLinks}>
      <header ref={header.ref} class={'admin-header' + (header.stuck ? ' is-stuck' : '')}>
        <div class="admin-brand">
          <Brand />
          <span class="admin-pill">{t('Admin')}</span>
        </div>
        <div class="admin-actions">
          <Link to="/" class="btn btn-primary is-sm admin-back">{t('Back to Seedy')}</Link>
          <AccountButton />
        </div>
      </header>

      <div class="admin-body">
        <nav ref={nav} aria-label={t('Admin sections')} class="admin-nav hscroll">
          {ADMIN_NAV.map((g) => (
            <div key={g.label} class="admin-nav-group">
              <span class="admin-nav-label">{t(g.label)}</span>
              {g.items.map((p) => (
                <Link key={p.id} to={p.path} aria-current={p.id === page ? 'page' : undefined}>
                  {t(p.label)}
                  {p.id === 'plugins' && updates > 0 && <span class="accent-badge" aria-label={tn(updates, '{n} update', '{n} updates')}>{updates}</span>}
                </Link>
              ))}
            </div>
          ))}
        </nav>

        <main class="admin-main">
          <div class="admin-title-row">
            <div class="admin-title">
              {overline}
              <h1>{title}</h1>
              {lede && <p>{lede}</p>}
            </div>
            {actions && <div class="admin-title-actions">{actions}</div>}
          </div>
          {children}
        </main>
      </div>
    </div>
  );
}

/* Shown while the config loads: panels in the Foundations skeleton shimmer. */
export function PanelSkeletons({ count = 2, rows = 3 }: { count?: number; rows?: number }) {
  return (
    <>
      {Array.from({ length: count }, (_, i) => (
        <section key={i} class="panel admin-skel" aria-hidden="true">
          <div class="panel-head">
            <span class="skel skel-line" style={{ width: '180px', height: '18px' }} />
            <span class="skel skel-line" style={{ width: '62%' }} />
          </div>
          {Array.from({ length: rows }, (_, j) => (
            <div key={j} class={'setting' + (j === rows - 1 ? ' is-last' : '')}>
              <span class="setting-label">
                <span class="skel skel-line" style={{ width: '160px', height: '14px' }} />
                <span class="skel skel-line" style={{ width: '240px' }} />
              </span>
              <span class="skel admin-skel-ctl" />
            </div>
          ))}
        </section>
      ))}
    </>
  );
}

const sentence = (m?: string) => (m ? m.trim().replace(/([^.!?])$/, '$1.') : '');

/* `what` is an English noun phrase ("the users") translated here: give new ones a French entry in i18n/fr/admin.ts. */
export function LoadError({ error, onRetry, what = 'Seedy’s settings' }: { error: Error; onRetry: () => void; what?: string }) {
  return (
    <div class="error-box" role="alert">
      <h2>{t('Couldn’t load {what}', { what: t(what) })}</h2>
      <p>{sentence(error.message) || t('The server did not answer.')} {t('Check that the Seedy plugin is installed and running, then try again.')}</p>
      <button type="button" class="btn btn-primary is-sm" onClick={onRetry}>{t('Try again')}</button>
    </div>
  );
}
