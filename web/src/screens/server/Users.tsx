import { useMemo, useState } from 'preact/hooks';
import { userStore } from '../../lib/app-state';
import { plural } from '../../lib/format';
import { formatNumber, t, tn } from '../../lib/i18n';
import { Link, navigate } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { Icon } from '../../ui/icons';
import { MenuButton, MenuItem, Sheet, confirm, toast } from '../../ui/overlay';
import { AdminLayout, LoadError } from '../admin/AdminLayout';
import { createUser, deleteUser, type DeviceInfo, errText, type FullUser, getDevices, getParentalRatings, getSessions, getUsers, getVirtualFolders, type ParentalRating, patchUserPolicy, sameId, saveUserConfiguration, saveUserPolicy, type Session, signOutEverywhere, type VirtualFolder } from './api';
import { UserAvatar } from './parts';
import { lastSeen, mbps } from './format';

/* Server · Users: everyone with an account, with their access at a glance. */

type Filter = 'all' | 'admins' | 'disabled';

export interface UsersData {
  users: FullUser[];
  sessions: Session[];
  devices: DeviceInfo[];
  folders: VirtualFolder[];
  ratings: ParentalRating[];
}

export async function loadUsersData(signal?: AbortSignal): Promise<UsersData> {
  const [users, sessions, devices, folders, ratings] = await Promise.all([
    getUsers(signal),
    getSessions(signal).catch(() => [] as Session[]),
    getDevices(undefined, signal).then((r) => r.Items, () => [] as DeviceInfo[]),
    getVirtualFolders(signal).catch(() => [] as VirtualFolder[]),
    getParentalRatings(signal).catch(() => [] as ParentalRating[])
  ]);
  return { users, sessions, devices, folders, ratings };
}

const isToday = (s?: string) => {
  if (!s) return false;
  const d = new Date(s);
  const n = new Date();
  return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate();
};

/* "Watching now on iPhone", "Last seen yesterday on Living room TV". */
export function presence(u: FullUser, data: Pick<UsersData, 'sessions' | 'devices'>): { text: string; online: boolean; watching: boolean } {
  const mine = data.sessions.filter((s) => sameId(s.UserId, u.Id)).sort((a, b) => (b.LastActivityDate ?? '').localeCompare(a.LastActivityDate ?? ''));
  const playing = mine.find((s) => s.NowPlayingItem);
  if (playing) return { text: t('Watching now on {device}', { device: playing.DeviceName ?? playing.Client ?? t('a device') }), online: true, watching: true };
  const recent = mine.find((s) => s.LastActivityDate && Date.now() - new Date(s.LastActivityDate).getTime() < 5 * 60_000);
  if (recent) return { text: t('Online now on {device}', { device: recent.DeviceName ?? recent.Client ?? t('a device') }), online: true, watching: false };
  const device = data.devices
    .filter((d) => sameId(d.LastUserId, u.Id))
    .sort((a, b) => (b.DateLastActivity ?? '').localeCompare(a.DateLastActivity ?? ''))[0];
  const name = device?.CustomName || device?.Name;
  return { text: lastSeen(u.LastActivityDate ? new Date(u.LastActivityDate) : undefined, name || undefined), online: false, watching: false };
}

/* The rating name for a maximum score: "TV-PG", "PG-13". Same choice as the Seedy plugin's admin pages: a named rating
   (TV ratings first) over a bare age, then the US name metadata mostly carries when the server's country has none. */
export function ratingName(score: number | null | undefined, ratings: ParentalRating[]): string | undefined {
  if (score === null || score === undefined) return undefined;
  const names = ratings.filter((r) => (r.RatingScore?.score ?? r.Value) === score && r.Name !== 'Unrated' && !/^\d+\+?$/.test(r.Name)).map((r) => r.Name);
  const named = names.find((n) => n.startsWith('TV-')) ?? names[0];
  if (named) return named;
  return score <= 0 ? 'TV-G' : score <= 7 ? 'TV-Y7' : score <= 10 ? 'TV-PG' : score <= 13 ? 'PG-13' : score <= 14 ? 'TV-14' : score <= 17 ? 'R' : 'TV-MA';
}

function libraryLine(u: FullUser, folders: VirtualFolder[]): string {
  if (u.Policy.EnableAllFolders) return t('All libraries');
  const names = folders.filter((f) => u.Policy.EnabledFolders?.some((id) => sameId(id, f.ItemId))).map((f) => f.Name);
  return names.length ? names.join(', ') : t('No libraries');
}

const DAY: Record<string, string> = {
  Everyday: '', Weekday: 'Weekdays', Weekend: 'Weekends', Sunday: 'Sun', Monday: 'Mon', Tuesday: 'Tue', Wednesday: 'Wed', Thursday: 'Thu', Friday: 'Fri', Saturday: 'Sat'
};
export const hourLabel = (h: number) => `${Math.floor(h)}:${String(Math.round((h % 1) * 60)).padStart(2, '0')}`;

function limitsLine(u: FullUser): string {
  const p = u.Policy;
  if (p.IsDisabled && p.LoginAttemptsBeforeLockout > 0 && (p.InvalidLoginAttemptCount ?? 0) >= p.LoginAttemptsBeforeLockout)
    /* English says "sign-ins" for any number; the {count} key gives French its singular. */
    return tn(p.LoginAttemptsBeforeLockout, 'Locked after {count} failed sign-ins', 'Locked after {n} failed sign-ins', { count: formatNumber(p.LoginAttemptsBeforeLockout) });
  const parts: string[] = [];
  if (!p.EnableRemoteAccess) parts.push(t('No remote access'));
  else if (p.RemoteClientBitrateLimit > 0) parts.push(t('Remote: {rate}', { rate: mbps(p.RemoteClientBitrateLimit) }));
  if (p.MaxActiveSessions > 0) parts.push(tn(p.MaxActiveSessions, 'Up to {n} stream', 'Up to {n} streams'));
  const s = p.AccessSchedules?.[0];
  if (s) {
    const day = DAY[s.DayOfWeek];
    parts.push(`${day ? t(day) + ' ' : ''}${hourLabel(s.StartHour)}–${hourLabel(s.EndHour)}${p.AccessSchedules.length > 1 ? ` +${p.AccessSchedules.length - 1}` : ''}`);
  }
  if (!p.EnableMediaPlayback) parts.push(t('Can’t play'));
  return parts.length ? parts.join(' · ') : t('No limits');
}

export function ServerUsers() {
  const me = useStore(userStore);
  const loaded = useAsync((signal) => loadUsersData(signal), []);
  const [filter, setFilter] = useState<Filter>('all');
  const [query, setQuery] = useState('');
  const [adding, setAdding] = useState(false);
  const data = loaded.data;

  const users = data?.users ?? [];
  const shown = useMemo(() => users
    .map((u, index) => ({ u, index }))
    .filter(({ u }) => (filter === 'admins' ? u.Policy.IsAdministrator : filter === 'disabled' ? u.Policy.IsDisabled : true))
    .filter(({ u }) => !query.trim() || u.Name.toLowerCase().includes(query.trim().toLowerCase())), [users, filter, query]);

  const today = users.filter((u) => isToday(u.LastActivityDate)).length;
  /* "1 signed in today" and "3 signed in today" read the same in English; the {count} key gives French its singular. */
  const lede = data ? `${plural(users.length, 'user')} · ${tn(today, '{count} signed in today', '{n} signed in today', { count: formatNumber(today) })}` : loaded.error ? t('Users unavailable') : <span class="skel skel-line srv-lede-skel" style={{ width: '180px' }} />;

  return (
    <AdminLayout
      page="users"
      title={t('Users')}
      lede={<span class="srv-lede">{lede}</span>}
      wide
      actions={
        <button type="button" class="btn btn-primary is-sm" disabled={!data} onClick={() => setAdding(true)}>
          <Icon name="plus" size={17} />{t('Add user')}
        </button>
      }
    >
      <div class="srv-filter-row">
        <div role="tablist" aria-label={t('Filter users')} class="srv-tabs">
          {([['all', 'All'], ['admins', 'Administrators'], ['disabled', 'Disabled']] as const).map(([id, label]) => (
            <button key={id} type="button" role="tab" class="tab" aria-selected={filter === id ? 'true' : 'false'} onClick={() => setFilter(id)}>{t(label)}</button>
          ))}
        </div>
        <label class="search-field srv-search">
          <Icon name="search" size={18} />
          <input type="search" aria-label={t('Search users')} placeholder={t('Search users')} value={query} onInput={(e) => setQuery((e.currentTarget as HTMLInputElement).value)} />
        </label>
      </div>

      {!data && loaded.error ? (
        <LoadError error={loaded.error} onRetry={loaded.reload} what={t('the users')} />
      ) : !data ? (
        <div class="srv-list" aria-busy="true">
          {[0, 1, 2, 3].map((i) => (
            <div key={i} class="srv-user">
              <span class="skel srv-avatar" style={{ width: '48px', height: '48px' }} />
              <div class="srv-user-main"><span class="skel skel-line" style={{ width: '120px', height: '16px' }} /><span class="skel skel-line" style={{ width: '220px' }} /></div>
            </div>
          ))}
        </div>
      ) : shown.length === 0 ? (
        <div class="srv-list">
          <div class="srv-user is-empty">
            {query.trim() ? t('No users match “{query}”.', { query: query.trim() }) : filter === 'admins' ? t('No administrators.') : filter === 'disabled' ? t('No disabled accounts. Everyone can sign in.') : t('No users yet.')}
          </div>
        </div>
      ) : (
        <div class="srv-list">
          {shown.map(({ u, index }) => (
            <UserRow key={u.Id} u={u} index={index} data={data} isMe={sameId(u.Id, me?.Id)} onChanged={loaded.reload} />
          ))}
        </div>
      )}

      {data && <AddUserSheet open={adding} users={users} onClose={() => setAdding(false)} />}
    </AdminLayout>
  );
}

function UserRow({ u, index, data, isMe, onChanged }: { u: FullUser; index: number; data: UsersData; isMe: boolean; onChanged: () => void }) {
  const p = u.Policy;
  const pres = presence(u, data);
  const tags: string[] = [];
  if (p.IsAdministrator) tags.push(t('Administrator'));
  if (p.IsDisabled) tags.push(t('Disabled'));
  if (p.IsHidden) tags.push(t('Hidden'));
  const rating = ratingName(p.MaxParentalRating, data.ratings);
  if (rating) tags.push(t('Maturity {rating}', { rating }));
  const editPath = `/admin/users/${u.Id}`;

  const toggleDisabled = async () => {
    const next = !p.IsDisabled;
    if (next) {
      const ok = await confirm({
        title: t('Disable {name}?', { name: u.Name }),
        text: t('{name} is signed out everywhere and can’t sign in until you enable the account again. Nothing is deleted.', { name: u.Name }),
        action: t('Disable account'),
        danger: true
      });
      if (!ok) return;
    }
    try {
      await patchUserPolicy(u.Id, { IsDisabled: next });
      toast(next ? t('{name} is disabled', { name: u.Name }) : t('{name} can sign in again', { name: u.Name }));
      onChanged();
    } catch (e) {
      toast(t('Couldn’t change it. {error}', { error: errText(e) }), 'error');
    }
  };

  const remove = async () => {
    const ok = await confirm({
      title: t('Delete {name}?', { name: u.Name }),
      text: t('{name}’s account, watch history, favourites and settings are deleted. This can’t be undone.', { name: u.Name }),
      action: t('Delete user'),
      danger: true
    });
    if (!ok) return;
    try {
      await deleteUser(u.Id);
      toast(t('{name} was deleted', { name: u.Name }));
      onChanged();
    } catch (e) {
      toast(t('Couldn’t delete. {error}', { error: errText(e) }), 'error');
    }
  };

  const signOut = async () => {
    const ok = await confirm({
      title: t('Sign {name} out everywhere?', { name: u.Name }),
      text: t('Every app and device {name} is signed in on has to sign in again.', { name: u.Name }) + (isMe ? ' ' + t('This browser stays signed in.') : ''),
      action: t('Sign out everywhere')
    });
    if (!ok) return;
    try {
      const n = await signOutEverywhere(u.Id);
      toast(n ? tn(n, 'Signed {name} out on {n} device', 'Signed {name} out on {n} devices', { name: u.Name }) : t('{name} wasn’t signed in anywhere else', { name: u.Name }));
      onChanged();
    } catch (e) {
      toast(t('Couldn’t sign out. {error}', { error: errText(e) }), 'error');
    }
  };

  return (
    <div class={'srv-user' + (p.IsDisabled ? ' is-disabled' : '')}>
      <UserAvatar user={u} index={index} size={48} online={pres.online} disabled={p.IsDisabled} />
      <div class="srv-user-main">
        <span class="srv-user-name-row">
          <Link to={editPath} class="srv-user-name">{u.Name}</Link>
          {tags.map((tag) => <span key={tag} class="srv-tag">{tag}</span>)}
        </span>
        <span class="srv-user-seen">{pres.text}</span>
      </div>
      <div class="srv-user-access">
        <span class="srv-user-libs">{libraryLine(u, data.folders)}</span>
        <span class="srv-user-limits">{limitsLine(u)}</span>
      </div>
      <div class="srv-user-actions">
        <Link to={editPath} class="btn btn-secondary is-sm" aria-label={t('Edit {name}', { name: u.Name })}>{t('Edit')}</Link>
        <MenuButton
          width={240}
          button={({ ref, open, toggle }) => (
            <button ref={ref} type="button" class="btn icon-btn is-bare is-40" aria-label={t('More actions for {name}', { name: u.Name })} aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={toggle}>
              <Icon name="more" size={20} />
            </button>
          )}
        >
          {(close) => (
            <>
              <MenuItem icon="user" label={t('Edit')} onSelect={() => { close(); navigate(editPath); }} />
              <MenuItem
                icon={p.IsDisabled ? 'check' : 'lock'}
                label={p.IsDisabled ? t('Enable account') : t('Disable account')}
                sub={isMe ? t('Not your own account') : p.IsAdministrator && !p.IsDisabled ? t('Remove admin access first') : undefined}
                disabled={isMe || (p.IsAdministrator && !p.IsDisabled)}
                onSelect={() => { close(); void toggleDisabled(); }}
              />
              <MenuItem icon="signOut" label={t('Sign out everywhere')} onSelect={() => { close(); void signOut(); }} />
              <div class="menu-sep" />
              <MenuItem icon="trash" label={t('Delete user')} danger sub={isMe ? t('Not your own account') : undefined} disabled={isMe} onSelect={() => { close(); void remove(); }} />
            </>
          )}
        </MenuButton>
      </div>
    </div>
  );
}

/* Name, password, and whose access to start from. Opens the new user's page when done. */
function AddUserSheet({ open, users, onClose }: { open: boolean; users: FullUser[]; onClose: () => void }) {
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [copyFrom, setCopyFrom] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const taken = users.some((u) => u.Name.trim().toLowerCase() === name.trim().toLowerCase());

  const reset = () => { setName(''); setPassword(''); setCopyFrom(''); setError(null); };

  const submit = async () => {
    if (!name.trim() || taken) return;
    setBusy(true);
    setError(null);
    try {
      const created = await createUser(name.trim(), password);
      const source = users.find((u) => u.Id === copyFrom);
      if (source) {
        const policy = {
          ...source.Policy,
          IsAdministrator: false, IsDisabled: false, IsHidden: source.Policy.IsHidden, InvalidLoginAttemptCount: 0,
          AuthenticationProviderId: created.Policy?.AuthenticationProviderId ?? source.Policy.AuthenticationProviderId,
          PasswordResetProviderId: created.Policy?.PasswordResetProviderId ?? source.Policy.PasswordResetProviderId
        };
        await saveUserPolicy(created.Id, policy);
        await saveUserConfiguration(created.Id, { ...source.Configuration }).catch(() => undefined);
      }
      toast(t('{name} was added', { name: created.Name }));
      reset();
      onClose();
      navigate(`/admin/users/${created.Id}`);
    } catch (e) {
      setError(errText(e, t('Jellyfin didn’t create the user.')));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Sheet open={open} onClose={() => { reset(); onClose(); }} label={t('Add user')}>
      <div class="sheet-head">
        <div class="srv-sheet-headtext">
          <h2 class="sheet-title">{t('Add user')}</h2>
          <p class="sheet-text">{t('They can sign in on any app with this name. You can change what they can watch and do next.')}</p>
        </div>
      </div>
      <form class="srv-form" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
        <label class="srv-field">
          <span class="srv-field-label">{t('Name')}</span>
          <input class="field" autocomplete="off" value={name} maxLength={64} onInput={(e) => setName((e.currentTarget as HTMLInputElement).value)} />
          {taken && <span class="form-error"><Icon name="alert" size={16} />{t('Someone already has this name.')}</span>}
        </label>
        <label class="srv-field">
          <span class="srv-field-label">{t('Password')} <span class="srv-field-opt">· {t('optional')}</span></span>
          <input class="field" type="password" autocomplete="new-password" value={password} onInput={(e) => setPassword((e.currentTarget as HTMLInputElement).value)} />
        </label>
        <label class="srv-field">
          <span class="srv-field-label">{t('Copy settings from')}</span>
          <span class="select-wrap">
            <select class="select srv-full" value={copyFrom} onChange={(e) => setCopyFrom((e.currentTarget as HTMLSelectElement).value)}>
              <option value="">{t('Nobody · start with the defaults')}</option>
              {users.map((u) => <option key={u.Id} value={u.Id}>{u.Policy.IsAdministrator ? t('{name} (access only, not admin)', { name: u.Name }) : u.Name}</option>)}
            </select>
            <Icon name="chevronDown" size={16} />
          </span>
          <span class="srv-field-help">{t('Libraries, devices, playback limits and parental controls. Never admin access.')}</span>
        </label>
        {error && <span class="form-error"><Icon name="alert" size={16} />{error}</span>}
        <div class="sheet-actions">
          <button type="button" class="btn btn-ghost is-light" onClick={() => { reset(); onClose(); }}>{t('Cancel')}</button>
          <button type="submit" class="btn btn-primary is-sm" disabled={!name.trim() || taken || busy}>{busy ? t('Adding…') : t('Add user')}</button>
        </div>
      </form>
    </Sheet>
  );
}
