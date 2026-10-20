import type { ComponentChildren } from 'preact';
import { deviceId } from '../../api/client';
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'preact/hooks';
import * as seedy from '../../api/seedy';
import { userStore } from '../../lib/app-state';
import { formatNumber, t, tn, tx } from '../../lib/i18n';
import { Link, navigate, setQuery, useLocation } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { SelectField, Switch } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { MenuButton, MenuItem, Sheet, confirm, toast } from '../../ui/overlay';
import { AdminLayout, LoadError, PanelSkeletons } from '../admin/AdminLayout';
import { accountsStore, loadAccounts, loadProfiles, profilesStore } from '../admin/config';
import { type AccessDay, type AccessSchedule, deleteUser, deleteUserImage, errText, type FullPolicy, type FullUser, getUser, type ParentalRating, patchUserPolicy, renameUser, resetUserPassword, sameId, setUserPassword, signOutEverywhere, type UnratedItem, uploadUserImage } from './api';
import { UnsavedBar, useFilePicker, UserAvatar, useUnsavedGuard } from './parts';
import { hourLabel, loadUsersData, presence, type UsersData } from './Users';

/* Server · Edit user: one person's account, playback, access, parental controls, password and Seedy switches. */

type Tab = 'account' | 'access' | 'parental' | 'password' | 'seedy';
const TABS: { id: Tab; label: string }[] = [ // labels translate where they're shown
  { id: 'account', label: 'Account and playback' },
  { id: 'access', label: 'Libraries and devices' },
  { id: 'parental', label: 'Parental controls' },
  { id: 'password', label: 'Password' },
  { id: 'seedy', label: 'Seedy' }
];

interface SeedyFlags { canRequest: boolean; top10: boolean; history: boolean }
interface SeedyInfo { account?: seedy.SeerrAccount; profile?: seedy.PlayLogProfile; error?: boolean }
interface Draft { name: string; policy: FullPolicy; seedy: SeedyFlags }
/* Only what the admin changed. The page shows it on top of what loaded, so Seedy's switches arriving after an edit
   (or a reload) don't turn into changes nobody made. */
interface Patch { name?: string; policy: Partial<FullPolicy>; seedy: Partial<SeedyFlags> }
const NO_PATCH: Patch = { policy: {}, seedy: {} };

const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);

function countChanges(d: Draft, base: Draft): number {
  let n = 0;
  if (d.name.trim() !== base.name) n++;
  const keys = new Set([...Object.keys(d.policy), ...Object.keys(base.policy)]);
  for (const k of keys) {
    /* The rating and its sub-rating are one choice on the page. */
    if (k === 'MaxParentalSubRating' && !same(d.policy.MaxParentalRating, base.policy.MaxParentalRating)) continue;
    if (!same(d.policy[k], base.policy[k])) n++;
  }
  for (const k of Object.keys(d.seedy) as (keyof SeedyFlags)[]) if (d.seedy[k] !== base.seedy[k]) n++;
  return n;
}

export function ServerUserEdit({ id }: { id: string }) {
  const me = useStore(userStore);
  const loc = useLocation();
  const tab = (TABS.find((x) => x.id === loc.query.get('tab'))?.id ?? 'account') as Tab;
  const loaded = useAsync(async (signal) => {
    const [user, all] = await Promise.all([getUser(id, signal), loadUsersData(signal)]);
    return { user, all };
  }, [id]);
  const seedyInfo = useAsync(async (signal): Promise<SeedyInfo> => {
    void signal;
    const [accounts, profiles] = await Promise.all([
      seedy.adminSeerrAccounts().catch(() => null),
      seedy.adminPlayLogProfiles().catch(() => null)
    ]);
    return {
      account: accounts?.find((a) => sameId(a.userId, id)),
      profile: profiles?.find((p) => sameId(p.userId, id)),
      error: !accounts && !profiles
    };
  }, [id]);
  const [imgVersion, setImgVersion] = useState(0);

  const user = loaded.data?.user;
  const base = useMemo<Draft | null>(() => (user ? {
    name: user.Name,
    policy: user.Policy,
    seedy: {
      canRequest: !!seedyInfo.data?.account?.canRequest,
      top10: seedyInfo.data?.profile?.top10 ?? true,
      history: seedyInfo.data?.profile?.history ?? true
    }
  } : null), [user, seedyInfo.data]);
  const [patch, setPatch] = useState<Patch>(NO_PATCH);
  useEffect(() => { setPatch(NO_PATCH); }, [id]);
  const d: Draft | null = base
    ? { name: patch.name ?? base.name, policy: { ...base.policy, ...patch.policy }, seedy: { ...base.seedy, ...patch.seedy } }
    : null;
  const changes = d && base ? countChanges(d, base) : 0;
  const [saving, setSaving] = useState(false);
  useUnsavedGuard(changes > 0, user
    ? t('Your changes to {name}’s account haven’t been saved yet. If you leave now, they’re lost.', { name: user.Name })
    : t('Your changes to this account haven’t been saved yet. If you leave now, they’re lost.'));

  const isMe = sameId(id, me?.Id);
  const index = loaded.data ? Math.max(0, loaded.data.all.users.findIndex((u) => sameId(u.Id, id))) : 0;

  const setPolicy = <K extends keyof FullPolicy>(k: K, v: FullPolicy[K]) => setPatch((c) => ({ ...c, policy: { ...c.policy, [k]: v } }));
  const setName = (name: string) => setPatch((c) => ({ ...c, name }));
  const setSeedy = (k: keyof SeedyFlags, v: boolean) => setPatch((c) => ({ ...c, seedy: { ...c.seedy, [k]: v } }));

  const save = async () => {
    if (!d || !base || !user) return;
    setSaving(true);
    try {
      const name = d.name.trim();
      if (name && name !== base.name) await renameUser(user, name);
      if (!same(d.policy, base.policy)) {
        /* Only the fields changed here go onto the server's current policy, so edits made elsewhere since stay. */
        const changed: Partial<FullPolicy> = {};
        for (const k of Object.keys(d.policy)) if (!same(d.policy[k], base.policy[k])) changed[k] = d.policy[k];
        if ('MaxParentalRating' in changed || 'MaxParentalSubRating' in changed) {
          changed.MaxParentalRating = d.policy.MaxParentalRating;
          changed.MaxParentalSubRating = d.policy.MaxParentalSubRating;
        }
        /* Never take admin access away from, or disable, the account doing the editing, or lock it out of this device. */
        if (isMe) {
          delete changed.IsAdministrator;
          delete changed.IsDisabled;
          if (!d.policy.EnableAllDevices && ('EnableAllDevices' in changed || 'EnabledDevices' in changed) && !d.policy.EnabledDevices.includes(deviceId()))
            changed.EnabledDevices = [...d.policy.EnabledDevices, deviceId()];
        }
        if (Object.keys(changed).length) await patchUserPolicy(user.Id, changed);
      }
      const seedyChanged = d.seedy.canRequest !== base.seedy.canRequest || d.seedy.top10 !== base.seedy.top10 || d.seedy.history !== base.seedy.history;
      if (d.seedy.canRequest !== base.seedy.canRequest) await seedy.adminSetCanRequest(user.Id, d.seedy.canRequest);
      if (d.seedy.top10 !== base.seedy.top10 || d.seedy.history !== base.seedy.history)
        await seedy.adminPlayLogProfile(user.Id, { top10: d.seedy.top10, history: d.seedy.history });
      /* Requests and Play log keep their lists for the session; refresh them if they were loaded. */
      if (seedyChanged && accountsStore.get().data) loadAccounts(true);
      if (seedyChanged && profilesStore.get().data) loadProfiles(true);
      setPatch(NO_PATCH);
      toast(t('{name}’s settings saved', { name: name || user.Name }));
      loaded.reload();
      seedyInfo.reload();
    } catch (e) {
      toast(t('Couldn’t save. {error}', { error: errText(e) }), 'error');
    } finally {
      setSaving(false);
    }
  };

  const overline = (
    <Link to="/admin/users" class="srv-overline"><Icon name="chevronLeft" size={16} stroke={2.2} />{t('Users')}</Link>
  );

  if (!loaded.data || !user || !d) {
    return (
      <AdminLayout page="users" title={loaded.error ? t('User') : <span class="skel skel-line" style={{ display: 'inline-block', width: '160px', height: '36px' }} />} wide overline={overline}>
        <div class="srv-ue-root">
          {loaded.error ? <LoadError error={loaded.error} onRetry={loaded.reload} what={t('this user')} /> : <PanelSkeletons count={2} rows={4} />}
        </div>
      </AdminLayout>
    );
  }

  const pres = presence(user, loaded.data.all);

  return (
    <AdminLayout
      page="users"
      wide
      overline={<>{overline}<UserAvatar user={user} index={index} size={72} disabled={d.policy.IsDisabled} imgVersion={imgVersion} /></>}
      title={<NameEditor name={d.name} onChange={setName} />}
      lede={<span class="srv-ue-status"><span class={'srv-ue-dot' + (pres.online ? ' is-on' : '')} />{pres.text}</span>}
      actions={<HeaderActions user={user} isMe={isMe} onPhoto={() => { setImgVersion((v) => v + 1); loaded.reload(); }} />}
    >
      <div role="tablist" aria-label={t('User settings')} class="srv-tabs srv-ue-root">
        {TABS.map((x) => (
          <button key={x.id} type="button" role="tab" class="tab" aria-selected={tab === x.id ? 'true' : 'false'} onClick={() => setQuery({ tab: x.id === 'account' ? null : x.id })}>{t(x.label)}</button>
        ))}
      </div>

      {tab === 'account' && <AccountTab p={d.policy} name={d.name || user.Name} isMe={isMe} set={setPolicy} />}
      {tab === 'access' && <AccessTab p={d.policy} user={user} data={loaded.data.all} set={setPolicy} />}
      {tab === 'parental' && <ParentalTab p={d.policy} name={d.name || user.Name} ratings={loaded.data.all.ratings} set={setPolicy} />}
      {tab === 'password' && <PasswordTab user={user} isMe={isMe} onChanged={loaded.reload} />}
      {tab === 'seedy' && <SeedyTab flags={d.seedy} info={seedyInfo.data} loading={seedyInfo.loading && !seedyInfo.data} name={d.name || user.Name} set={setSeedy} />}

      <UnsavedBar changes={changes} saving={saving} canSave={!!d.name.trim()} onDiscard={() => setPatch(NO_PATCH)} onSave={() => void save()} />
    </AdminLayout>
  );
}

/* The name in the header doubles as the rename field. */
function NameEditor({ name, onChange }: { name: string; onChange: (v: string) => void }) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(name);
  const input = useRef<HTMLInputElement>(null);
  useLayoutEffect(() => { if (editing) { input.current?.focus(); input.current?.select(); } }, [editing]);
  if (!editing) {
    return (
      <button type="button" class="srv-ue-name" title={t('Rename')} aria-label={t('{name}. Rename', { name })} onClick={() => { setValue(name); setEditing(true); }}>
        {name}
      </button>
    );
  }
  const commit = () => { if (value.trim()) onChange(value.trim()); setEditing(false); };
  return (
    <input
      ref={input}
      class="srv-ue-name-input"
      aria-label={t('Name')}
      value={value}
      maxLength={64}
      size={Math.max(4, value.length + 1)}
      onInput={(e) => setValue((e.currentTarget as HTMLInputElement).value)}
      onBlur={commit}
      onKeyDown={(e) => {
        if (e.key === 'Enter') { e.preventDefault(); commit(); }
        if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); setEditing(false); }
      }}
    />
  );
}

function HeaderActions({ user, isMe, onPhoto }: { user: FullUser; isMe: boolean; onPhoto: () => void }) {
  const [busy, setBusy] = useState(false);
  const file = useFilePicker(async (f) => {
    setBusy(true);
    try {
      await uploadUserImage(user.Id, f);
      toast(t('Photo updated'));
      onPhoto();
    } catch (e) {
      toast(t('Couldn’t upload the photo. {error}', { error: errText(e) }), 'error');
    } finally {
      setBusy(false);
    }
  });

  const removePhoto = async () => {
    try {
      await deleteUserImage(user.Id);
      toast(t('Photo removed'));
      onPhoto();
    } catch (e) {
      toast(t('Couldn’t remove the photo. {error}', { error: errText(e) }), 'error');
    }
  };

  const signOut = async () => {
    const ok = await confirm({
      title: t('Sign {name} out everywhere?', { name: user.Name }),
      text: t('Every app and device {name} is signed in on has to sign in again.', { name: user.Name }) + (isMe ? ' ' + t('This browser stays signed in.') : ''),
      action: t('Sign out everywhere')
    });
    if (!ok) return;
    try {
      const n = await signOutEverywhere(user.Id);
      toast(n ? tn(n, 'Signed {name} out on {n} device', 'Signed {name} out on {n} devices', { name: user.Name }) : t('{name} wasn’t signed in anywhere else', { name: user.Name }));
    } catch (e) {
      toast(t('Couldn’t sign out. {error}', { error: errText(e) }), 'error');
    }
  };

  const remove = async () => {
    const ok = await confirm({
      title: t('Delete {name}?', { name: user.Name }),
      text: t('{name}’s account, watch history, favourites and settings are deleted. This can’t be undone.', { name: user.Name }),
      action: t('Delete user'),
      danger: true
    });
    if (!ok) return;
    try {
      await deleteUser(user.Id);
      toast(t('{name} was deleted', { name: user.Name }));
      navigate('/admin/users', { force: true, replace: true });
    } catch (e) {
      toast(t('Couldn’t delete. {error}', { error: errText(e) }), 'error');
    }
  };

  return (
    <>
      {file.input}
      {user.PrimaryImageTag ? (
        <MenuButton
          width={220}
          button={({ ref, open, toggle }) => (
            <button ref={ref} type="button" class="btn btn-secondary" aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} disabled={busy} onClick={toggle}>
              {busy ? t('Uploading…') : t('Change photo')}
            </button>
          )}
        >
          {(close) => (
            <>
              <MenuItem icon="upload" label={t('Upload a new photo')} onSelect={() => { close(); file.open(); }} />
              <MenuItem icon="trash" label={t('Remove photo')} onSelect={() => { close(); void removePhoto(); }} />
            </>
          )}
        </MenuButton>
      ) : (
        <button type="button" class="btn btn-secondary" disabled={busy} onClick={file.open}>{busy ? t('Uploading…') : t('Change photo')}</button>
      )}
      <button type="button" class="btn btn-secondary" onClick={() => void signOut()}>{t('Sign out everywhere')}</button>
      <button type="button" class="btn srv-btn-danger" disabled={isMe} title={isMe ? t('You can’t delete the account you’re signed in with') : undefined} onClick={() => void remove()}>{t('Delete user')}</button>
    </>
  );
}

/* ── Rows ── */

function SwitchRow({ name, help, checked, onChange, disabled, title }: {
  name: string; help: ComponentChildren; checked: boolean; onChange: (v: boolean) => void; disabled?: boolean; title?: string;
}) {
  return (
    <div class="setting">
      <span class="setting-label"><span class="setting-name">{name}</span><span class="setting-help">{help}</span></span>
      <Switch checked={checked} onChange={onChange} label={name} disabled={disabled} title={title} />
    </div>
  );
}

function SelectRow<T extends string | number>({ name, help, value, options, onChange }: {
  name: string; help: string; value: T; options: { value: T; label: string }[]; onChange: (v: T) => void;
}) {
  const opts = options.some((o) => o.value === value) ? options : [...options, { value, label: String(value) }];
  return (
    <div class="setting">
      <span class="setting-label"><span class="setting-name">{name}</span><span class="setting-help">{help}</span></span>
      <SelectField value={value} options={opts} onChange={onChange} minWidth={220} label={name} />
    </div>
  );
}

function Group({ title, text, children }: { title: string; text?: ComponentChildren; children: ComponentChildren }) {
  return (
    <section class="panel srv-ue-panel">
      <div class={'panel-head' + (text ? '' : ' is-title-only')}>
        <h2 class="panel-title">{title}</h2>
        {text && <p class="panel-text">{text}</p>}
      </div>
      {children}
    </section>
  );
}

/* ── Account and playback ── */

const BITRATES = [0, 120, 80, 60, 40, 30, 20, 15, 10, 8, 6, 4, 3, 2, 1.5, 1, 0.72, 0.42].map((m) => m * 1_000_000);

function AccountTab({ p, name, isMe, set }: { p: FullPolicy; name: string; isMe: boolean; set: <K extends keyof FullPolicy>(k: K, v: FullPolicy[K]) => void }) {
  const lockDefault = p.IsAdministrator ? 5 : 3;
  const lockOptions = [
    { value: 0, label: tn(lockDefault, '{n} attempt', '{n} attempts') },
    ...[1, 2, 3, 5, 10].filter((n) => n !== lockDefault).map((n) => ({ value: n, label: tn(n, '{n} attempt', '{n} attempts') })),
    { value: -1, label: t('Never lock') }
  ].sort((a, b) => (a.value === -1 ? 1 : b.value === -1 ? -1 : (a.value || lockDefault) - (b.value || lockDefault)));
  const streams = [{ value: 0, label: t('No limit') }, ...[1, 2, 3, 4, 5, 6, 8, 10].map((n) => ({ value: n, label: n === 1 ? t('Only 1') : t('Up to {n}', { n }) }))];
  const rates = BITRATES.map((b) => ({ value: b, label: b === 0 ? t('No limit') : `${formatNumber(b / 1_000_000)} Mbps` }));
  return (
    <>
      <Group title={t('Account')}>
        <SwitchRow name={t('Administrator')} help={t('Full access to this admin area and every library')} checked={p.IsAdministrator} onChange={(v) => set('IsAdministrator', v)} disabled={isMe} title={isMe ? t('You can’t remove your own admin access') : undefined} />
        <SwitchRow name={t('Allow remote access')} help={t('Sign in from outside the home network')} checked={p.EnableRemoteAccess} onChange={(v) => set('EnableRemoteAccess', v)} />
        <SwitchRow name={t('Hide from the sign-in screen')} help={t('{name} must type the name to sign in', { name })} checked={p.IsHidden} onChange={(v) => set('IsHidden', v)} />
        <SwitchRow name={t('Disable account')} help={t('Blocks sign-in without deleting anything')} checked={p.IsDisabled} onChange={(v) => set('IsDisabled', v)} disabled={isMe || (p.IsAdministrator && !p.IsDisabled)} title={isMe ? t('You can’t disable your own account') : p.IsAdministrator ? t('Administrators can’t be disabled') : undefined} />
        <SelectRow name={t('Lock after failed sign-ins')} help={t('Counts wrong passwords in a row')} value={p.LoginAttemptsBeforeLockout} options={lockOptions} onChange={(v) => set('LoginAttemptsBeforeLockout', v)} />
        <SelectRow name={t('Simultaneous streams')} help={t('Devices playing at the same time')} value={p.MaxActiveSessions} options={streams} onChange={(v) => set('MaxActiveSessions', v)} />
      </Group>
      <Group title={t('Playback')}>
        <SwitchRow name={t('Allow playback')} help={t('Turn off to allow browsing only')} checked={p.EnableMediaPlayback} onChange={(v) => set('EnableMediaPlayback', v)} />
        <SwitchRow name={t('Allow video transcoding')} help={t('Needed for devices that can’t play the original format')} checked={p.EnableVideoPlaybackTranscoding} onChange={(v) => set('EnableVideoPlaybackTranscoding', v)} />
        <SwitchRow name={t('Allow audio transcoding')} help={t('Converts audio a device can’t play')} checked={p.EnableAudioPlaybackTranscoding} onChange={(v) => set('EnableAudioPlaybackTranscoding', v)} />
        <SwitchRow name={t('Allow remuxing')} help={t('Changes the container without re-encoding')} checked={p.EnablePlaybackRemuxing} onChange={(v) => set('EnablePlaybackRemuxing', v)} />
        <SwitchRow name={t('Always transcode remote streams')} help={t('Even when the device could play the original')} checked={p.ForceRemoteSourceTranscoding} onChange={(v) => set('ForceRemoteSourceTranscoding', v)} />
        <SelectRow name={t('Remote streaming limit')} help={t('Caps quality outside the home network')} value={p.RemoteClientBitrateLimit} options={rates} onChange={(v) => set('RemoteClientBitrateLimit', v)} />
        <SelectRow
          name={t('Watch together')}
          help={t('Synced playback with other people')}
          value={p.SyncPlayAccess}
          options={[{ value: 'CreateAndJoinGroups', label: t('Create and join groups') }, { value: 'JoinGroups', label: t('Join groups only') }, { value: 'None', label: t('Off') }]}
          onChange={(v) => set('SyncPlayAccess', v)}
        />
      </Group>
      <Group title={t('Content and control')}>
        <SwitchRow name={t('Download media')} help={t('Save files for offline viewing')} checked={p.EnableContentDownloading} onChange={(v) => set('EnableContentDownloading', v)} />
        <SwitchRow name={t('Delete media')} help={t('Remove files from the server')} checked={p.EnableContentDeletion} onChange={(v) => set('EnableContentDeletion', v)} />
        <SwitchRow name={t('Manage collections')} help={t('Create and edit collections')} checked={p.EnableCollectionManagement} onChange={(v) => set('EnableCollectionManagement', v)} />
        <SwitchRow name={t('Manage subtitles')} help={t('Search for and remove subtitles')} checked={p.EnableSubtitleManagement} onChange={(v) => set('EnableSubtitleManagement', v)} />
        <SwitchRow name={t('Live TV')} help={t('Watch channels')} checked={p.EnableLiveTvAccess} onChange={(v) => set('EnableLiveTvAccess', v)} />
        <SwitchRow name={t('Manage Live TV recordings')} help={t('Schedule and delete recordings')} checked={p.EnableLiveTvManagement} onChange={(v) => set('EnableLiveTvManagement', v)} />
        <SwitchRow name={t('Control other users’ sessions')} help={t('Send commands to someone else’s playback')} checked={p.EnableRemoteControlOfOtherUsers} onChange={(v) => set('EnableRemoteControlOfOtherUsers', v)} />
        <SwitchRow name={t('Control shared devices')} help={t('Play to devices like smart TVs')} checked={p.EnableSharedDeviceControl} onChange={(v) => set('EnableSharedDeviceControl', v)} />
      </Group>
    </>
  );
}

/* ── Libraries and devices ── */

const TYPE_LABEL: Record<string, string> = {
  movies: 'Movies', tvshows: 'Shows', music: 'Music', musicvideos: 'Music videos', homevideos: 'Home videos', boxsets: 'Collections',
  books: 'Books', photos: 'Photos', livetv: 'Live TV', playlists: 'Playlists', mixed: 'Mixed'
};

function toggleIn(list: string[], id: string, on: boolean): string[] {
  const rest = list.filter((x) => !sameId(x, id));
  return on ? [...rest, id] : rest;
}

function AccessTab({ p, user, data, set }: { p: FullPolicy; user: FullUser; data: UsersData; set: <K extends keyof FullPolicy>(k: K, v: FullPolicy[K]) => void }) {
  const [allDevices, setAllDevices] = useState(false);
  const devices = useMemo(() => {
    const sorted = [...data.devices].sort((a, b) => (b.DateLastActivity ?? '').localeCompare(a.DateLastActivity ?? ''));
    const relevant = sorted.filter((dv) => sameId(dv.LastUserId, user.Id) || p.EnabledDevices.includes(dv.Id));
    return allDevices ? sorted : relevant;
  }, [data.devices, user.Id, p.EnabledDevices, allDevices]);
  return (
    <>
      <Group title={t('Libraries')} text={t('What {name} can browse and play.', { name: user.Name })}>
        <SwitchRow name={t('All libraries')} help={t('Including libraries you add later')} checked={p.EnableAllFolders} onChange={(v) => set('EnableAllFolders', v)} />
        {!p.EnableAllFolders && (
          data.folders.length ? (
            <div class="srv-grid2 srv-grid2-rows">
              {data.folders.map((f) => (
                <div key={f.ItemId} class="srv-sw">
                  <span class="srv-sw-text"><span class="srv-sw-name">{f.Name}</span><span class="srv-sw-sub">{[t(TYPE_LABEL[f.CollectionType ?? 'mixed'] ?? 'Mixed'), ...f.Locations].join(' · ')}</span></span>
                  <Switch checked={p.EnabledFolders.some((x) => sameId(x, f.ItemId))} onChange={(v) => set('EnabledFolders', toggleIn(p.EnabledFolders, f.ItemId, v))} label={t('Allow {name}', { name: f.Name })} />
                </div>
              ))}
            </div>
          ) : <p class="srv-note-row">{t('There are no libraries yet.')}</p>
        )}
      </Group>
      <Group title={t('Devices')} text={t('Where {name} can sign in.', { name: user.Name })}>
        <SwitchRow name={t('Any device')} help={t('New phones, TVs and browsers included')} checked={p.EnableAllDevices} onChange={(v) => set('EnableAllDevices', v)} />
        {!p.EnableAllDevices && (
          <>
            {devices.length ? (
              <div class="srv-grid2 srv-grid2-rows">
                {devices.map((dv) => (
                  <div key={dv.Id} class="srv-sw">
                    <span class="srv-sw-text"><span class="srv-sw-name">{dv.CustomName || dv.Name || dv.Id}</span><span class="srv-sw-sub">{[dv.AppName, dv.LastUserName].filter(Boolean).join(' · ')}</span></span>
                    <Switch checked={p.EnabledDevices.includes(dv.Id)} onChange={(v) => set('EnabledDevices', toggleIn(p.EnabledDevices, dv.Id, v))} label={t('Allow {name}', { name: dv.CustomName || dv.Name || dv.Id })} />
                  </div>
                ))}
              </div>
            ) : <p class="srv-note-row">{t('{name} hasn’t signed in on any device yet.', { name: user.Name })}</p>}
            {data.devices.length > devices.length || allDevices ? (
              <button type="button" class="link-btn srv-more-link" onClick={() => setAllDevices((v) => !v)}>
                {allDevices ? t('Only {name}’s devices', { name: user.Name }) : t('Show every device on the server ({n})', { n: formatNumber(data.devices.length) })}
              </button>
            ) : null}
          </>
        )}
      </Group>
    </>
  );
}

/* ── Parental controls ── */

const UNRATED: { id: UnratedItem; label: string }[] = [
  { id: 'Movie', label: 'Movies' },
  { id: 'Series', label: 'Shows' },
  { id: 'Trailer', label: 'Trailers' },
  { id: 'Music', label: 'Music' },
  { id: 'Book', label: 'Books' },
  { id: 'LiveTvChannel', label: 'Live TV channels' },
  { id: 'LiveTvProgram', label: 'Live TV programmes' },
  { id: 'ChannelContent', label: 'Channel content' },
  { id: 'Other', label: 'Everything else' }
];

function ratingGroups(ratings: ParentalRating[]) {
  const groups = new Map<string, { score: number; sub: number | null; names: string[] }>();
  for (const r of ratings) {
    const score = r.RatingScore?.score ?? r.Value;
    if (score === null || score === undefined || r.Name === 'Unrated') continue;
    const sub = r.RatingScore?.subScore ?? null;
    const key = `${score}:${sub ?? ''}`;
    const g = groups.get(key) ?? { score, sub, names: [] };
    g.names.push(r.Name);
    groups.set(key, g);
  }
  return [...groups.values()].sort((a, b) => a.score - b.score || (a.sub ?? 0) - (b.sub ?? 0));
}

function ParentalTab({ p, name, ratings, set }: { p: FullPolicy; name: string; ratings: ParentalRating[]; set: <K extends keyof FullPolicy>(k: K, v: FullPolicy[K]) => void }) {
  const groups = ratingGroups(ratings);
  const current = p.MaxParentalRating === null || p.MaxParentalRating === undefined ? '' : `${p.MaxParentalRating}:${p.MaxParentalSubRating ?? ''}`;
  const options = [{ value: '', label: t('No limit') }, ...groups.map((g) => ({ value: `${g.score}:${g.sub ?? ''}`, label: g.names.slice(0, 4).join(' · ') }))];
  if (current && !options.some((o) => o.value === current)) {
    const loose = options.find((o) => o.value.startsWith(`${p.MaxParentalRating}:`));
    if (!loose) options.push({ value: current, label: t('Rating {n}', { n: p.MaxParentalRating ?? '' }) });
  }
  const value = options.some((o) => o.value === current) ? current : options.find((o) => o.value.startsWith(`${p.MaxParentalRating}:`))?.value ?? current;
  const [schedOpen, setSchedOpen] = useState(false);

  return (
    <>
      <Group title={t('Ratings')} text={t('Titles above the limit are hidden from {name} everywhere, including search.', { name })}>
        <div class="setting">
          <span class="setting-label"><span class="setting-name">{t('Highest rating allowed')}</span><span class="setting-help">{ratings.length ? t('Ratings from the server’s country, lowest first') : t('Ratings from the metadata, lowest first')}</span></span>
          <SelectField
            value={value}
            options={options}
            minWidth={220}
            label={t('Highest rating allowed')}
            onChange={(v) => {
              if (!v) { set('MaxParentalRating', null); set('MaxParentalSubRating', null); return; }
              const [s, sub] = v.split(':');
              set('MaxParentalRating', Number(s));
              set('MaxParentalSubRating', sub === '' ? null : Number(sub));
            }}
          />
        </div>
        <div class="setting is-stack">
          <span class="setting-label"><span class="setting-name">{t('Hide titles without a rating')}</span><span class="setting-help">{t('Turn on for each kind of title that should stay hidden when it has no rating')}</span></span>
          <div class="srv-grid2">
            {UNRATED.map((u) => (
              <div key={u.id} class="srv-sw">
                <span class="srv-sw-name is-plain">{t(u.label)}</span>
                <Switch
                  checked={p.BlockUnratedItems.includes(u.id)}
                  onChange={(v) => set('BlockUnratedItems', v ? [...p.BlockUnratedItems.filter((x) => x !== u.id), u.id] : p.BlockUnratedItems.filter((x) => x !== u.id))}
                  label={t('Hide unrated {kind}', { kind: t(u.label).toLowerCase() })}
                />
              </div>
            ))}
          </div>
        </div>
      </Group>

      <Group title={t('Tags')} text={t('Tags come from the metadata, or from what you add on a title’s page.')}>
        <TagRow name={t('Only show titles tagged')} help={t('Leave empty to allow everything')} tags={p.AllowedTags} onChange={(v) => set('AllowedTags', v)} />
        <TagRow name={t('Hide titles tagged')} help={t('Wins over the list above')} tags={p.BlockedTags} onChange={(v) => set('BlockedTags', v)} />
      </Group>

      <Group title={t('When {name} can watch', { name })} text={t('Outside these hours, sign-in and playback are blocked. No times means any time.')}>
        <div class="srv-sched">
          {p.AccessSchedules.map((s, i) => (
            <div key={i} class="srv-sched-row">
              <Icon name="clock" size={18} />
              <span class="srv-sched-text">{dayLabel(s.DayOfWeek)} · {hourLabel(s.StartHour)}–{hourLabel(s.EndHour)}</span>
              <button type="button" class="srv-x" aria-label={t('Remove {day} {from} to {to}', { day: dayLabel(s.DayOfWeek), from: hourLabel(s.StartHour), to: hourLabel(s.EndHour) })} onClick={() => set('AccessSchedules', p.AccessSchedules.filter((_, j) => j !== i))}><Icon name="close" size={14} /></button>
            </div>
          ))}
          <button type="button" class="srv-dashed" onClick={() => setSchedOpen(true)}><Icon name="plus" size={13} />{t('Add a time')}</button>
        </div>
      </Group>
      <ScheduleSheet open={schedOpen} onClose={() => setSchedOpen(false)} onAdd={(s) => { set('AccessSchedules', [...p.AccessSchedules, s]); setSchedOpen(false); }} />
    </>
  );
}

const DAYS: { value: AccessDay; label: string }[] = [
  { value: 'Everyday', label: 'Every day' }, { value: 'Weekday', label: 'Weekdays' }, { value: 'Weekend', label: 'Weekends' },
  { value: 'Monday', label: 'Monday' }, { value: 'Tuesday', label: 'Tuesday' }, { value: 'Wednesday', label: 'Wednesday' },
  { value: 'Thursday', label: 'Thursday' }, { value: 'Friday', label: 'Friday' }, { value: 'Saturday', label: 'Saturday' }, { value: 'Sunday', label: 'Sunday' }
];
const dayLabel = (d: AccessDay) => t(DAYS.find((x) => x.value === d)?.label ?? d);
const HOURS = Array.from({ length: 49 }, (_, i) => i / 2);

function ScheduleSheet({ open, onClose, onAdd }: { open: boolean; onClose: () => void; onAdd: (s: AccessSchedule) => void }) {
  const [day, setDay] = useState<AccessDay>('Everyday');
  const [start, setStart] = useState(9);
  const [end, setEnd] = useState(20);
  return (
    <Sheet open={open} onClose={onClose} label={t('Add a time')}>
      <div class="sheet-head"><h2 class="sheet-title">{t('Add a time')}</h2></div>
      <div class="srv-sched-form">
        <label class="srv-field"><span class="srv-field-label">{t('Days')}</span><SelectField value={day} options={DAYS.map((x) => ({ value: x.value, label: t(x.label) }))} onChange={setDay} label={t('Days')} /></label>
        <label class="srv-field"><span class="srv-field-label">{t('From')}</span><SelectField value={start} options={HOURS.slice(0, -1).map((h) => ({ value: h, label: hourLabel(h) }))} onChange={setStart} label={t('From')} /></label>
        <label class="srv-field"><span class="srv-field-label">{t('Until')}</span><SelectField value={end} options={HOURS.slice(1).map((h) => ({ value: h, label: hourLabel(h) }))} onChange={setEnd} label={t('Until')} /></label>
      </div>
      {end <= start && <span class="form-error"><Icon name="alert" size={16} />{t('The end has to be after the start.')}</span>}
      <div class="sheet-actions">
        <button type="button" class="btn btn-ghost is-light" onClick={onClose}>{t('Cancel')}</button>
        <button type="button" class="btn btn-primary is-sm" disabled={end <= start} onClick={() => onAdd({ DayOfWeek: day, StartHour: start, EndHour: end })}>{t('Add')}</button>
      </div>
    </Sheet>
  );
}

function TagRow({ name, help, tags, onChange, last }: { name: string; help: string; tags: string[]; onChange: (tags: string[]) => void; last?: boolean }) {
  const [adding, setAdding] = useState(false);
  const [value, setValue] = useState('');
  const add = () => {
    const tag = value.trim();
    if (tag && !tags.some((x) => x.toLowerCase() === tag.toLowerCase())) onChange([...tags, tag]);
    setValue('');
    setAdding(false);
  };
  return (
    <div class={'setting is-stack' + (last ? ' is-last' : '')}>
      <span class="setting-label"><span class="setting-name">{name}</span><span class="setting-help">{help}</span></span>
      <div class="chips">
        {tags.map((tag) => (
          <span key={tag} class="chip">{tag}<button type="button" aria-label={t('Remove {tag}', { tag })} onClick={() => onChange(tags.filter((x) => x !== tag))}><Icon name="close" size={12} /></button></span>
        ))}
        {adding ? (
          <form class="srv-chip-form" onSubmit={(e) => { e.preventDefault(); add(); }}>
            <input
              class="srv-chip-input"
              aria-label={t('{name}: new tag', { name })}
              placeholder={t('Tag')}
              value={value}
              ref={(el) => el?.focus()}
              onInput={(e) => setValue((e.currentTarget as HTMLInputElement).value)}
              onBlur={add}
              onKeyDown={(e) => { if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); setValue(''); setAdding(false); } }}
            />
          </form>
        ) : (
          <button type="button" class="chip-add" onClick={() => setAdding(true)}><Icon name="plus" size={12} />{t('Add tag')}</button>
        )}
      </div>
    </div>
  );
}

/* ── Password ── */

function PasswordTab({ user, isMe, onChanged }: { user: FullUser; isMe: boolean; onChanged: () => void }) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [again, setAgain] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const mismatch = again.length > 0 && next !== again;

  const submit = async () => {
    if (!next || next !== again) return;
    setBusy(true);
    setError(null);
    try {
      await setUserPassword(user.Id, next, isMe ? current : undefined);
      setCurrent(''); setNext(''); setAgain('');
      toast(t('{name}’s password was changed', { name: user.Name }));
      onChanged();
    } catch (e) {
      setError(errText(e, t('Jellyfin didn’t accept the password.')));
    } finally {
      setBusy(false);
    }
  };

  const clear = async () => {
    const ok = await confirm({
      title: t('Remove {name}’s password?', { name: user.Name }),
      text: t('Anyone who can reach the sign-in screen can then sign in as {name} without a password.', { name: user.Name }),
      action: t('Remove password'),
      danger: true
    });
    if (!ok) return;
    try {
      await resetUserPassword(user.Id);
      toast(t('{name} signs in without a password now', { name: user.Name }));
      onChanged();
    } catch (e) {
      toast(t('Couldn’t remove it. {error}', { error: errText(e) }), 'error');
    }
  };

  return (
    <Group
      title={t('Password')}
      text={isMe ? t('Changing your password signs you out on your other devices.') : t('A new password signs {name} out on their other devices. Remove it to let {name} sign in without one.', { name: user.Name })}
    >
      <form class="srv-pw" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
        {isMe && (
          <div class="setting">
            <label for="pw-cur" class="setting-label"><span class="setting-name">{t('Current password')}</span><span class="setting-help">{t('Needed to change your own password')}</span></label>
            <input id="pw-cur" class="field srv-w300" type="password" autocomplete="current-password" value={current} onInput={(e) => setCurrent((e.currentTarget as HTMLInputElement).value)} />
          </div>
        )}
        <div class="setting">
          <label for="pw-new" class="setting-label"><span class="setting-name">{t('New password')}</span><span class="setting-help">{t('Any length; longer is safer')}</span></label>
          <input id="pw-new" class="field srv-w300" type="password" autocomplete="new-password" value={next} onInput={(e) => setNext((e.currentTarget as HTMLInputElement).value)} />
        </div>
        <div class="setting">
          <label for="pw-again" class="setting-label"><span class="setting-name">{t('Type it again')}</span><span class="setting-help">{t('To be sure there’s no typo')}</span></label>
          <input id="pw-again" class="field srv-w300" type="password" autocomplete="new-password" value={again} onInput={(e) => setAgain((e.currentTarget as HTMLInputElement).value)} />
        </div>
        <div class="setting srv-pw-actions">
          <span class="srv-pw-msg">
            {mismatch && <span class="form-error"><Icon name="alert" size={16} />{t('The two passwords don’t match.')}</span>}
            {error && <span class="form-error"><Icon name="alert" size={16} />{error}</span>}
          </span>
          <div class="setting-controls">
            {!isMe && !user.Policy.IsAdministrator && <button type="button" class="btn btn-ghost is-light" onClick={() => void clear()}>{t('Remove password')}</button>}
            <button type="submit" class="btn btn-primary is-sm" disabled={!next || next !== again || busy || (isMe && !current)}>{busy ? t('Saving…') : t('Set password')}</button>
          </div>
        </div>
      </form>
    </Group>
  );
}

/* ── Seedy ── */

function SeedyTab({ flags, info, loading, name, set }: { flags: SeedyFlags; info?: SeedyInfo; loading: boolean; name: string; set: (k: keyof SeedyFlags, v: boolean) => void }) {
  if (loading) return <PanelSkeletons count={1} rows={3} />;
  const linked = !!info?.account?.seerrUserId;
  return (
    <Group title="Seedy" text={t('What {name} gets in Seedy, on top of Jellyfin’s access.', { name })}>
      {info?.error && <p class="srv-note-row is-warn"><Icon name="alert" size={16} />{t('Seedy’s settings didn’t load, so these show their defaults.')}</p>}
      <SwitchRow
        name={t('Request titles')}
        help={linked
          ? t('Through Seerr, as {name}', { name: info?.account?.seerrName ?? t('their Seerr account') })
          : <>{tx('Link {name} to a Seerr account under {requests} first', { name, requests: <Link to="/admin/requests" class="srv-inline-link">{t('Requests')}</Link> })}</>}
        checked={flags.canRequest && linked}
        disabled={!linked}
        onChange={(v) => set('canRequest', v)}
      />
      <SwitchRow name={t('Counts toward Top 10')} help={t('Their plays shape the Top 10 row everyone sees')} checked={flags.top10} onChange={(v) => set('top10', v)} />
      <SwitchRow name={t('Keep viewing history')} help={<>{tx('Their plays show up in Viewing history. Details under {playlog}', { playlog: <Link to="/admin/playlog" class="srv-inline-link">{t('Play log')}</Link> })}</>} checked={flags.history} onChange={(v) => set('history', v)} />
    </Group>
  );
}
