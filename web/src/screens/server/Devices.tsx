import { useEffect, useMemo, useState } from 'preact/hooks';
import { deviceId } from '../../api/client';
import { userImageUrl } from '../../api/images';
import { t, tn, tx } from '../../lib/i18n';
import { Link } from '../../lib/router';
import { Icon } from '../../ui/icons';
import { confirm, Sheet, toast } from '../../ui/overlay';
import { AdminLayout } from '../admin/AdminLayout';
import { deleteDevices, type DeviceInfo, errText, type FullUser, getDevices, getSessions, getUsers, renameDevice, type Session } from './api';
import { LoadFailed, Select, useLoad } from './parts';
import { avatarColour, lastActive } from './format';
import { Glyph } from './glyphs';

type Kind = 'tv' | 'phone' | 'computer';

/* Busy servers collect hundreds of devices; show them a page at a time. */
const PAGE = 40;
/* With more people than fit beside the search, the person filter becomes a menu. */
const MAX_CHIPS = 5;
const THIS_DEVICE = 'You’re using this device. To sign it out, use Sign out in the account menu.';

/* The boards draw three kinds of device; guess which from the app and the device's own name. */
function kindOf(d: DeviceInfo): Kind {
  const s = `${d.Name ?? ''} ${d.CustomName ?? ''} ${d.AppName ?? ''}`.toLowerCase();
  if (/\btv\b|android ?tv|fire ?tv|roku|shield|chromecast|apple ?tv|tvos|tizen|webos|kodi|xbox|playstation|living room|bedroom/.test(s)) return 'tv';
  if (/iphone|ipad|ipod|android|mobile|phone|tablet|swiftfin|findroid|\bios\b/.test(s)) return 'phone';
  return 'computer';
}

/* "0.1.0" → "0.1", "10.10.7" → "10.10", "1.6" → "1.6". */
const shortVersion = (v?: string | null) => (v ? v.split('.').slice(0, 2).join('.') : '');
const BROWSER = /\b(Chrome|Chromium|Firefox|Safari|Edge|Opera|Vivaldi|Brave)\b/i;

function appLabel(d: DeviceInfo): string {
  const app = [d.AppName, shortVersion(d.AppVersion)].filter(Boolean).join(' ');
  const browser = BROWSER.exec(d.Name ?? '')?.[1];
  return browser && !(d.AppName ?? '').toLowerCase().includes(browser.toLowerCase()) ? `${app} · ${browser}` : app;
}

const displayName = (d: DeviceInfo) => d.CustomName?.trim() || d.Name || t('Unknown device');

function nowPlaying(s?: Session): string | null {
  const item = s?.NowPlayingItem;
  if (!item) return null;
  const title = item.Type === 'Episode' && item.SeriesName ? item.SeriesName : item.Name;
  return s?.PlayState?.IsPaused ? t('Paused {title}', { title }) : t('Playing {title}', { title });
}

export function ServerDevices() {
  const devices = useLoad(getDevices);
  const users = useLoad(getUsers);
  const [sessions, setSessions] = useState<Session[]>([]);
  const [query, setQuery] = useState('');
  const [who, setWho] = useState<string>('all');
  const [renaming, setRenaming] = useState<DeviceInfo | null>(null);
  const [shown, setShown] = useState(PAGE);
  const [busy, setBusy] = useState(false);
  const mine = deviceId();

  useEffect(() => {
    let alive = true;
    const load = () => getSessions().then((s) => { if (alive) setSessions(s); }, () => undefined);
    void load();
    const timer = window.setInterval(load, 15_000);
    return () => { alive = false; window.clearInterval(timer); };
  }, []);

  const userList: FullUser[] = users.data ?? [];
  const userIndex = (id?: string | null) => userList.findIndex((u) => u.Id === id);
  const bySession = useMemo(() => {
    const m = new Map<string, Session>();
    for (const s of sessions) if (s.DeviceId && (!m.has(s.DeviceId) || s.NowPlayingItem)) m.set(s.DeviceId, s);
    return m;
  }, [sessions]);

  const rows = useMemo(() => {
    const q = query.trim().toLowerCase();
    /* Jellyfin lists a device once per person who signed in on it; the board shows each device once, with its last user. */
    const latest = new Map<string, DeviceInfo>();
    for (const d of devices.data?.Items ?? []) {
      const prev = latest.get(d.Id);
      if (!prev || (d.DateLastActivity ?? '') > (prev.DateLastActivity ?? '')) latest.set(d.Id, d);
    }
    const list = [...latest.values()].filter((d) => {
      if (who !== 'all' && d.LastUserId !== who) return false;
      if (!q) return true;
      return [displayName(d), d.Name, d.AppName, d.LastUserName].some((x) => (x ?? '').toLowerCase().includes(q));
    });
    const playing = (d: DeviceInfo) => (nowPlaying(bySession.get(d.Id)) ? 1 : 0);
    return list.sort((a, b) => playing(b) - playing(a) || (b.DateLastActivity ?? '').localeCompare(a.DateLastActivity ?? ''));
  }, [devices.data, who, query, bySession]);

  const remove = async (d: DeviceInfo) => {
    const name = displayName(d);
    const ok = await confirm({
      title: t('Remove {name}?', { name }),
      text: nowPlaying(bySession.get(d.Id))
        ? t('{name} is signed out and its playback stops. It comes back the next time someone signs in with it.', { name })
        : t('{name} is signed out. It comes back the next time someone signs in with it.', { name }),
      action: t('Remove device'),
      danger: true
    });
    if (!ok) return;
    try {
      await deleteDevices([d.Id]);
      toast(t('{name} removed', { name }));
      devices.reload(true);
    } catch (e) {
      toast(errText(e, t('Couldn’t remove that device.')), 'error');
    }
  };

  const signOutOthers = async () => {
    const others = [...new Map((devices.data?.Items ?? []).filter((d) => d.Id !== mine).map((d) => [d.Id, d])).values()];
    if (!others.length) { toast(t('No other devices are signed in')); return; }
    const watching = others.filter((d) => nowPlaying(bySession.get(d.Id))).length;
    const ok = await confirm({
      title: t('Sign out all other devices?'),
      text: (watching === 0 ? tn(others.length, '{n} device is signed out.', '{n} devices are signed out.')
        : watching === 1 ? tn(others.length, '{n} device is signed out, and playback stops on the one in use right now.', '{n} devices are signed out, and playback stops on the one in use right now.')
        : tn(others.length, '{n} device is signed out, and playback stops on the {watching} in use right now.', '{n} devices are signed out, and playback stops on the {watching} in use right now.', { watching }))
        + ' ' + t('This one stays signed in. People sign in again to keep watching.'),
      action: t('Sign out all other devices'),
      danger: true
    });
    if (!ok) return;
    setBusy(true);
    try {
      await deleteDevices(others.map((d) => d.Id));
      toast(tn(others.length, '{n} device signed out', '{n} devices signed out'));
      devices.reload(true);
    } catch (e) {
      toast(errText(e, t('Couldn’t sign the other devices out.')), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <AdminLayout
      page="devices"
      wide
      title={t('Devices')}
      lede={t('Every app that has signed in. Removing one signs it out; it comes back the next time someone signs in with it.')}
      actions={<button type="button" class="btn srv-danger-btn" disabled={busy || !devices.data} onClick={() => void signOutOthers()}>{t('Sign out all other devices')}</button>}
    >
      <span class="srv-title16" hidden />
      <div class="srv-toolbar">
        <label class="search-field">
          <Icon name="search" size={18} />
          <input type="search" aria-label={t('Search devices')} placeholder={t('Search devices or apps')} value={query}
            onInput={(e) => { setQuery((e.currentTarget as HTMLInputElement).value); setShown(PAGE); }} />
        </label>
        {userList.length > MAX_CHIPS ? (
          <div class="srv-selects">
            <Select label={t('Filter devices by user')} minWidth={150} value={who}
              options={[{ value: 'all', label: t('Everyone') }, ...userList.map((u) => ({ value: u.Id, label: u.Name }))]}
              onChange={(v) => { setWho(v); setShown(PAGE); }} />
          </div>
        ) : (
          <nav aria-label={t('Filter devices by user')} class="srv-filters">
            <button type="button" class="tab" aria-current={who === 'all' ? 'page' : undefined} onClick={() => { setWho('all'); setShown(PAGE); }}>{t('Everyone')}</button>
            {userList.map((u) => (
              <button key={u.Id} type="button" class="tab" aria-current={who === u.Id ? 'page' : undefined} onClick={() => { setWho(u.Id); setShown(PAGE); }}>{u.Name}</button>
            ))}
          </nav>
        )}
      </div>

      {devices.error && !devices.data ? (
        <LoadFailed error={devices.error} title={t('Couldn’t load the devices')} onRetry={() => devices.reload()} />
      ) : (
        <div class="srv-table-wrap">
          <div role="table" aria-label={t('Devices')} class="srv-table" aria-busy={!devices.data ? 'true' : undefined}>
            <div role="row" class="srv-trow is-head">
              <span role="columnheader" class="srv-th">{t('Device')}</span>
              <span role="columnheader" class="srv-th">{t('App')}</span>
              <span role="columnheader" class="srv-th">{t('Last user')}</span>
              <span role="columnheader" class="srv-th">{t('Last active')}</span>
              <span role="columnheader" class="srv-th is-right">{t('Actions')}</span>
            </div>
            {!devices.data && (
              <div class="srv-table-skel" aria-hidden="true">{Array.from({ length: 5 }, (_, i) => <span key={i} class="skel" />)}</div>
            )}
            {devices.data && rows.length === 0 && (
              <div class="srv-table-empty">{query || who !== 'all' ? t('No devices match. Try another name or person.') : t('No devices have signed in yet.')}</div>
            )}
            {rows.slice(0, shown).map((d) => {
              const name = displayName(d);
              const s = bySession.get(d.Id);
              const playing = nowPlaying(s);
              const isMine = d.Id === mine;
              const ui = userIndex(d.LastUserId);
              const user = ui >= 0 ? userList[ui] : undefined;
              const img = user ? userImageUrl(user, 52) : undefined;
              const userName = user?.Name ?? d.LastUserName ?? '';
              return (
                <div key={d.Id} role="row" class="srv-trow">
                  <div role="cell" class="srv-td srv-dev">
                    <span class="srv-dev-icon" aria-hidden="true"><Glyph name={kindOf(d)} size={20} /></span>
                    <span class="srv-dev-text">
                      <span class="srv-dev-name" title={name}>{name}</span>
                      {playing && <span class="srv-dev-now"><span class="dot" /><span>{playing}</span></span>}
                      {isMine && <span class="srv-dev-this">{t('This device')}</span>}
                    </span>
                  </div>
                  <div role="cell" class="srv-td is-app">{appLabel(d)}</div>
                  <div role="cell" class="srv-td srv-who">
                    {userName && (
                      <span class="srv-avatar-sm" aria-hidden="true" style={img ? undefined : { background: avatarColour(ui) }}>
                        {img ? <img src={img} alt="" /> : userName.charAt(0).toUpperCase()}
                      </span>
                    )}
                    <span class="srv-who-name">{userName || '—'}</span>
                  </div>
                  <div role="cell" class="srv-td is-last">{playing ? t('Now') : lastActive(d.DateLastActivity)}</div>
                  <div role="cell" class="srv-td is-actions">
                    <button type="button" class="btn btn-secondary is-xs" aria-label={t('Rename {name}', { name })} onClick={() => setRenaming(d)}>{t('Rename')}</button>
                    <button type="button" class="btn btn-ghost is-36" aria-label={t('Remove {name}', { name })} aria-disabled={isMine ? 'true' : undefined}
                      title={isMine ? t(THIS_DEVICE) : undefined}
                      onClick={() => (isMine ? toast(t(THIS_DEVICE), 'pending') : void remove(d))}>{t('Remove')}</button>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {rows.length > shown && (
        <div class="srv-more">
          <button type="button" class="btn btn-secondary srv-btn-20" onClick={() => setShown((n) => n + PAGE)}>
            {t('Show more')} <span class="srv-more-count">{t('{n} more', { n: rows.length - shown })}</span>
          </button>
        </div>
      )}

      <p class="srv-foot">{tx("To stop a person signing in from a device, use their profile's Libraries and devices tab in {users}.", { users: <Link to="/admin/users" class="srv-quiet-link">{t('Users')}</Link> })}</p>

      <RenameSheet device={renaming} onClose={() => setRenaming(null)} onSaved={(d, n) => {
        setRenaming(null);
        devices.setData({ ...devices.data!, Items: (devices.data?.Items ?? []).map((x) => (x.Id === d.Id ? { ...x, CustomName: n } : x)) });
        toast(n ? t('Renamed to {name}', { name: n }) : t('{name} uses its own name again', { name: d.Name ?? '' }));
      }} />
    </AdminLayout>
  );
}

function RenameSheet({ device, onClose, onSaved }: { device: DeviceInfo | null; onClose: () => void; onSaved: (d: DeviceInfo, name: string) => void }) {
  const [name, setName] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  useEffect(() => {
    if (!device) return;
    setName(displayName(device));
    setErr(null);
  }, [device]);
  const save = async (e: Event) => {
    e.preventDefault();
    if (!device) return;
    const n = name.trim() === (device.Name ?? '') ? '' : name.trim();
    setBusy(true);
    try {
      await renameDevice(device.Id, n);
      onSaved(device, n);
    } catch (x) {
      setErr(errText(x, t('Couldn’t rename the device.')));
    } finally {
      setBusy(false);
    }
  };
  return (
    <Sheet open={!!device} onClose={onClose} label={t('Rename device')}>
      <form class="srv-sheet-form" onSubmit={save}>
        <div class="sheet-head">
          <div class="srv-sheet-titles">
            <h2 class="sheet-title">{t('Rename device')}</h2>
            <p class="sheet-text">{t('Only changes how it shows here and in other admin lists.')}</p>
          </div>
        </div>
        <label class="srv-sheet-field">{t('Name')}
          <input class="field" value={name} maxLength={64} placeholder={device?.Name ?? ''} onInput={(e) => { setName((e.currentTarget as HTMLInputElement).value); setErr(null); }} />
          <span class="srv-sheet-hint">{device?.Name ? t('Leave it empty to use the name the app reports ({name}).', { name: device.Name }) : t('Leave it empty to use the name the app reports.')}</span>
        </label>
        {err && <span class="form-error" role="alert"><Icon name="alert" size={16} />{err}</span>}
        <div class="sheet-actions">
          <button type="button" class="btn btn-ghost is-light" onClick={onClose}>{t('Cancel')}</button>
          <button type="submit" class="btn btn-primary is-sm" disabled={busy}>{busy ? t('Saving…') : t('Rename')}</button>
        </div>
      </form>
    </Sheet>
  );
}
