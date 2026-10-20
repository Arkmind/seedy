import type { ComponentChildren } from 'preact';
import { meStore, signOut } from '../../lib/app-state';
import { t } from '../../lib/i18n';
import { Link, navigate } from '../../lib/router';
import { useStore } from '../../lib/store';
import { Header } from '../../ui/navbar/Header';
import './me.css';

export type MePage = 'profile' | 'home' | 'playback' | 'subtitles' | 'display' | 'controls';

/* Personal settings, grouped as on the Settings boards. They follow the profile to every device unless a row
   carries the "This device" tag. */
export const ME_NAV: { label: string; items: { id: MePage; path: string; label: string }[] }[] = [
  { label: 'Account', items: [{ id: 'profile', path: '/settings', label: 'Profile and sign-in' }] },
  {
    label: 'Watching',
    items: [
      { id: 'home', path: '/settings/home', label: 'Home screen' },
      { id: 'playback', path: '/settings/playback', label: 'Playback' },
      { id: 'subtitles', path: '/settings/subtitles', label: 'Subtitles' }
    ]
  },
  {
    label: 'This app',
    items: [
      { id: 'display', path: '/settings/display', label: 'Display' },
      { id: 'controls', path: '/settings/controls', label: 'Controls' }
    ]
  }
];

/* The grey pill that marks a setting stored on this device only. */
export function DeviceTag() {
  return <span class="device-tag">{t('This device')}</span>;
}

export function MeLayout({ page, title, lede, children, actions }: {
  page: MePage;
  title: ComponentChildren;
  lede?: ComponentChildren;
  children: ComponentChildren;
  actions?: ComponentChildren;
}) {
  const me = useStore(meStore);
  const leave = () => signOut().then(() => navigate('/', { replace: true, force: true }));
  return (
    <div class="page me-page">
      <Header active="settings" />
      <div class="me-body">
        <nav aria-label={t('Settings sections')} class="me-nav hscroll">
          {ME_NAV.map((g) => (
            <div key={g.label} class="me-nav-group">
              <span class="me-nav-label">{t(g.label)}</span>
              {g.items.map((i) => (
                <Link key={i.id} to={i.path} aria-current={i.id === page ? 'page' : undefined}>{t(i.label)}</Link>
              ))}
            </div>
          ))}
          <div aria-hidden="true" class="me-nav-sep" />
          <div class="me-nav-group is-links">
            {me.historyEnabled && <Link to="/history">{t('Viewing history')}</Link>}
            {me.requests.connected && <Link to="/requests/my">{t('My requests')}</Link>}
            {me.isAdmin && <Link to="/admin">{t('Admin dashboard')}</Link>}
            <button type="button" class="me-nav-btn is-danger" onClick={leave}>{t('Sign out')}</button>
          </div>
        </nav>

        <main class="me-main">
          <div class="me-title-row">
            <div class="me-title">
              <h1>{title}</h1>
              {lede && <p>{lede}</p>}
            </div>
            {actions && <div class="me-title-actions">{actions}</div>}
          </div>
          {children}
        </main>
      </div>
    </div>
  );
}
