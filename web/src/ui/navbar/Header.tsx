import { createPortal } from 'preact/compat';
import { useEffect, useLayoutEffect, useState } from 'preact/hooks';
import { logoUrl as seedyLogoUrl } from '../../api/seedy';
import { userImageUrl } from '../../api/images';
import { JELLYFIN_WEB } from '../../api/client';
import type { SessionInfo } from '../../api/types';
import { brandingStore, meStore, signOut, userStore, viewsStore } from '../../lib/app-state';
import { castTarget, listCastTargets } from '../../lib/cast';
import { Link, navigate } from '../../lib/router';
import { useStore } from '../../lib/store';
import { Icon, type IconName } from '../icons';
import { useStickyHeader } from '../sticky';
import { MenuButton, MenuItem, toast } from '../overlay';
import { LibrariesButton, LibrariesSheet, currentView } from './LibrariesMenu';
import { NbIcon, type NbIconName } from './icons';
import { SearchBox } from '../search/SearchBox';
import './navbar.css';
import { t } from '../../lib/i18n';

/* The navigation bar (Navigation bar board): brand; Home, Libraries and Favorites; a real search box in the
   middle; Requests, cast and the account menu on the right. Over the Home banner and a details hero it sits on the
   art with glass controls; elsewhere it uses the solid controls and a hairline under it. On phones (as in the Android
   app): brand, then search, cast and avatar on one row (the Search page adds its box under them), and a tab bar at
   the bottom. */

export function BrandMark({ size = 34 }: { size?: number }) {
  const b = useStore(brandingStore);
  const initial = (b.name || 'S').trim().charAt(0).toUpperCase();
  if (b.hasLogo) {
    return <img src={seedyLogoUrl(b.logoVersion)} alt="" class="brand-logo" style={{ height: size + 'px', maxWidth: size * 3 + 'px' }} />;
  }
  return (
    <span class="brand-mark" style={{ width: size + 'px', height: size + 'px', fontSize: Math.round(size * 0.53) + 'px', borderRadius: Math.round(size * 0.29) + 'px' }}>
      {initial}
    </span>
  );
}

export function Brand({ small }: { small?: boolean }) {
  const b = useStore(brandingStore);
  return (
    <Link to="/" aria-label={t('{name} home', { name: b.name || 'Seedy' })} class={'brand' + (small ? ' is-small' : '')}>
      <BrandMark size={small ? 30 : 34} />
      <span class="brand-name">{b.name || 'Seedy'}</span>
    </Link>
  );
}

export type HeaderActive = 'home' | 'favorites' | 'history' | 'search' | 'settings' | 'requests' | { viewId: string } | { collectionType: string } | null;

/* The app's phone breakpoint (tokens.css: the gutter drops to 16 px). */
const PHONE = typeof window !== 'undefined' && window.matchMedia ? window.matchMedia('(max-width: 720px)') : null;
function usePhone(): boolean {
  const [phone, setPhone] = useState(() => !!PHONE?.matches);
  useEffect(() => {
    if (!PHONE) return;
    const on = () => setPhone(PHONE.matches);
    on();
    PHONE.addEventListener('change', on);
    return () => PHONE.removeEventListener('change', on);
  }, []);
  return phone;
}

export function Header({ variant = 'plain', active }: { variant?: 'overlay' | 'plain'; active: HeaderActive }) {
  const glass = variant === 'overlay';
  const phone = usePhone();
  const { ref, stuck } = useStickyHeader<HTMLElement>();
  const pageMode = active === 'search';
  // In a books library the box says what it finds there (Books library board).
  const scope = currentView(useStore(viewsStore), active)?.CollectionType;
  const placeholder = scope === 'books' ? t('Search books, authors and series') : undefined;
  return (
    <>
      <header ref={ref} class={'site-header nb' + (glass ? ' is-overlay' : ' is-plain') + (stuck ? ' is-stuck' : '') + (phone ? ' is-phone' : '')}>
        {phone ? (
          <>
            <div class="nb-ptop">
              <Brand small />
              <div class="nb-pactions">
                {!pageMode && <SearchBox phone glass={glass} />}
                <CastButton glass={glass} />
                <AccountButton variant="phone" highlighted={active === 'settings'} />
              </div>
            </div>
            {pageMode && <SearchBox phone glass={glass} pageMode />}
          </>
        ) : (
          <>
            <Brand />
            <nav aria-label={t('Main')} class="nb-nav">
              <NavLink to="/" icon="home" label={t('Home')} current={active === 'home'} glass={glass} />
              <LibrariesButton active={active} glass={glass} />
              <NavLink to="/favorites" icon="heart" label={t('Favorites')} current={active === 'favorites'} glass={glass} />
            </nav>
            <SearchBox glass={glass} pageMode={pageMode} placeholder={placeholder} />
            <HeaderActions glass={glass} active={active} />
          </>
        )}
      </header>
      {phone && <TabBar active={active} />}
    </>
  );
}

function NavLink({ to, icon, label, current, glass }: { to: string; icon: IconName; label: string; current: boolean; glass?: boolean }) {
  return (
    <Link to={to} class={'nb-link' + (current ? ' is-current' : '') + (glass ? ' is-glass' : '')} aria-current={current ? 'page' : undefined}>
      <Icon name={icon} size={18} stroke={2.2} />
      {label}
    </Link>
  );
}

/* Requests (when Seerr is connected), cast, and the account button. */
export function HeaderActions({ glass, active }: { glass?: boolean; active?: HeaderActive; hideSearch?: boolean }) {
  const me = useStore(meStore);
  const current = active === 'requests';
  return (
    <div class="nb-actions header-actions">
      {me.requests.connected && (
        <Link to="/requests" class={'nb-req' + (glass ? ' is-glass' : '') + (current ? ' is-current' : '')} aria-current={current ? 'page' : undefined}>
          <NbIcon name="request" size={18} />
          {t('Requests')}
        </Link>
      )}
      <CastButton glass={glass} />
      <AccountButton variant="pill" glass={glass} highlighted={active === 'settings'} />
    </div>
  );
}

/* ── Phone tab bar: Home, Libraries, Favorites, Requests ── */

function TabBar({ active }: { active: HeaderActive }) {
  const me = useStore(meStore);
  const requests = me.requests.connected;
  const [libs, setLibs] = useState(false);
  // Pages leave room for the bar at the bottom while it's there.
  useLayoutEffect(() => {
    const root = document.documentElement;
    root.classList.add('nb-has-tabbar');
    return () => root.classList.remove('nb-has-tabbar');
  }, []);
  const inLibrary = !!active && typeof active === 'object';
  const tab = (icon: NbIconName, label: string) => (
    <>
      <span class="nb-tab-pill"><NbIcon name={icon} size={20} /></span>
      {t(label)}
    </>
  );
  return createPortal(
    <>
      <nav aria-label={t('Main')} class={'nb-tabbar' + (requests ? '' : ' is-3')}>
        <Link to="/" class="nb-tab" aria-current={active === 'home' ? 'page' : undefined}>{tab('home', 'Home')}</Link>
        <button type="button" class="nb-tab" aria-haspopup="dialog" aria-expanded={libs ? 'true' : 'false'} aria-current={inLibrary ? 'page' : undefined} onClick={() => setLibs(true)}>
          {tab('grid', 'Libraries')}
        </button>
        <Link to="/favorites" class="nb-tab" aria-current={active === 'favorites' ? 'page' : undefined}>{tab('heart', 'Favorites')}</Link>
        {requests && <Link to="/requests" class="nb-tab" aria-current={active === 'requests' ? 'page' : undefined}>{tab('request', 'Requests')}</Link>}
      </nav>
      <LibrariesSheet open={libs} onClose={() => setLibs(false)} active={active} />
    </>,
    document.body
  );
}

export function CastButton({ glass, player }: { glass?: boolean; player?: boolean }) {
  const target = useStore(castTarget);
  const [sessions, setSessions] = useState<SessionInfo[] | null>(null);
  const load = () => {
    setSessions(null);
    listCastTargets().then(setSessions, () => setSessions([]));
  };
  return (
    <MenuButton
      width={300}
      button={({ ref, open, toggle }) => (
        <button
          ref={ref}
          type="button"
          aria-label={target ? t('Casting to {device}', { device: target.DeviceName ?? '' }) : t('Cast to a device')}
          aria-expanded={open ? 'true' : 'false'}
          class={player ? 'btn player-btn' : 'btn icon-btn nb-cast' + (glass ? ' is-glass' : '')}
          style={target ? { color: 'var(--seedy-accent)' } : undefined}
          onClick={() => { if (!open) load(); toggle(); }}
        >
          <Icon name="cast" size={player ? 22 : 20} />
        </button>
      )}
    >
      {(close) => (
        <>
          <span class="menu-label">{t('Play on')}</span>
          <MenuItem label={t('This device')} sub={t('Play here')} pressed={!target} onSelect={() => { castTarget.set(null); close(); }} />
          {sessions === null && <span class="menu-item is-muted" aria-busy="true">{t('Looking for devices…')}</span>}
          {sessions?.length === 0 && <span class="menu-item is-muted">{t('No other Jellyfin apps are open')}</span>}
          {sessions?.map((s) => (
            <MenuItem
              key={s.Id}
              label={s.DeviceName || s.Client || t('Device')}
              sub={[s.Client, s.UserName].filter(Boolean).join(' · ')}
              pressed={target?.Id === s.Id}
              onSelect={() => {
                castTarget.set(s);
                toast(`Playing on ${s.DeviceName || s.Client}`);
                close();
              }}
            />
          ))}
        </>
      )}
    </MenuButton>
  );
}

export function Avatar({ size = 44, light }: { size?: number; light?: boolean }) {
  const user = useStore(userStore);
  const img = user ? userImageUrl(user, size * 2) : undefined;
  return (
    <span class={'header-avatar' + (light ? ' is-light' : '')} style={{ width: size + 'px', height: size + 'px' }}>
      {img ? <img src={img} alt="" /> : (user?.Name ?? '?').charAt(0).toUpperCase()}
    </span>
  );
}

/* The account menu. `circle` is the admin header's avatar button, `pill` the bar's avatar and chevron, `phone`
   the phone bar's light avatar. */
export function AccountButton({ highlighted, variant = 'circle', glass }: { highlighted?: boolean; variant?: 'circle' | 'pill' | 'phone'; glass?: boolean }) {
  const user = useStore(userStore);
  const me = useStore(meStore);
  const leave = () => signOut().then(() => navigate('/', { replace: true, force: true }));
  return (
    <MenuButton
      width={260}
      button={({ ref, open, toggle }) => {
        const label = highlighted ? t('Account menu, Settings open') : t('Account menu');
        if (variant === 'pill') {
          return (
            <button ref={ref} type="button" aria-label={label} aria-expanded={open ? 'true' : 'false'} onClick={toggle}
              class={'nb-account' + (glass ? ' is-glass' : '') + (highlighted || open ? ' is-current' : '')}>
              <Avatar size={36} light />
              <Icon name="chevronDown" size={15} stroke={2.4} class="nb-chev" />
            </button>
          );
        }
        if (variant === 'phone') {
          return (
            <button ref={ref} type="button" aria-label={label} aria-expanded={open ? 'true' : 'false'} onClick={toggle}
              class={'nb-account-phone' + (highlighted || open ? ' is-current' : '')}>
              <Avatar size={44} light />
            </button>
          );
        }
        return (
          <button ref={ref} type="button" aria-label={label} aria-expanded={open ? 'true' : 'false'} class="avatar-btn" onClick={toggle}>
            <Avatar light={highlighted || open} />
          </button>
        );
      }}
    >
      {(close) => (
        <>
          <div class="menu-who">
            <Avatar size={36} />
            <span class="two"><span class="menu-who-name">{user?.Name}</span><span class="sub">{me.isAdmin ? t('Administrator') : t('Profile')}</span></span>
          </div>
          <div class="menu-sep" />
          {me.historyEnabled && <MenuItem icon="history" label={t('Viewing history')} onSelect={() => { close(); navigate('/history'); }} />}
          <MenuItem icon="sliders" label={t('Settings')} onSelect={() => { close(); navigate('/settings'); }} />
          {me.isAdmin && <MenuItem icon="shield" label={t('Admin')} onSelect={() => { close(); navigate('/admin'); }} />}
          <MenuItem icon="external" label={t('Jellyfin app')} href={JELLYFIN_WEB} />
          <div class="menu-sep" />
          <MenuItem icon="signOut" label={t('Sign out')} onSelect={() => { close(); void leave(); }} />
        </>
      )}
    </MenuButton>
  );
}
