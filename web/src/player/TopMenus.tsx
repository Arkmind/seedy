import { useState } from 'preact/hooks';
import { sendPlayCommand, syncPlayList } from '../api/jellyfin';
import type { SessionInfo, SyncPlayGroup } from '../api/types';
import { castTarget, listCastTargets } from '../lib/cast';
import { secondsToTicks } from '../lib/format';
import { useStore } from '../lib/store';
import { t } from '../lib/i18n';
import { Icon } from '../ui/icons';
import { MenuButton, MenuItem, toast } from '../ui/overlay';
import type { ExternalApp } from './external';
import { createGroup, joinGroup, leaveGroup, syncAccess, syncStore } from './syncplay';

/* Top-right player menus: Watch together (SyncPlay) and Cast. */

export function WatchTogether({ itemId, position }: { itemId: string; position: () => number }) {
  const sync = useStore(syncStore);
  const [groups, setGroups] = useState<SyncPlayGroup[] | null>(null);
  const access = syncAccess();
  if (access === 'None') return null;
  const load = () => {
    setGroups(null);
    syncPlayList().then((g) => setGroups(g ?? []), () => setGroups([]));
  };
  const others = sync.group ? Math.max(0, sync.group.Participants.length - 1) : 0;
  return (
    <>
      {sync.group && (
        <span class="status-pill pl-together" role="status">
          <Icon name="users" size={14} />
          {others > 0 ? t('Watching with {n}', { n: others, total: others + 1 }) : t('Waiting for others')}
        </span>
      )}
      <MenuButton
        width={300}
        button={({ ref, open, toggle }) => (
          <button
            ref={ref}
            type="button"
            class={'btn player-btn' + (sync.group ? ' is-on' : '')}
            aria-label={sync.group ? t('Watch together: {group}', { group: sync.group.GroupName }) : t('Watch together')}
            aria-expanded={open ? 'true' : 'false'}
            onClick={() => { if (!open && !sync.group) load(); toggle(); }}
          >
            <Icon name="watchTogether" size={22} />
          </button>
        )}
      >
        {(close) => sync.group ? (
          <>
            <span class="menu-label">{t('Watching together')}</span>
            <div class="menu-who">
              <span class="two">
                <span class="menu-who-name">{sync.group.GroupName}</span>
                <span class="sub">{sync.group.Participants.join(', ') || t('Just you so far')}</span>
              </span>
            </div>
            <div class="menu-sep" />
            <MenuItem icon="signOut" label={t('Leave group')} onSelect={() => { close(); void leaveGroup().then(() => toast(t('You left the group'))); }} />
          </>
        ) : (
          <>
            <span class="menu-label">{t('Watch together')}</span>
            {groups === null && <span class="menu-item is-muted" aria-busy="true">{t('Looking for groups…')}</span>}
            {groups?.length === 0 && <span class="menu-item is-muted">{t('No groups on this server yet')}</span>}
            {groups?.map((g) => (
              <MenuItem
                key={g.GroupId}
                icon="users"
                label={g.GroupName}
                sub={t('{n} watching', { n: g.Participants.length })}
                onSelect={() => {
                  close();
                  joinGroup(g.GroupId).catch(() => toast(t("Couldn't join that group"), 'error'));
                }}
              />
            ))}
            {access === 'CreateAndJoinGroups' && (
              <>
                <div class="menu-sep" />
                <MenuItem
                  icon="plus"
                  label={t('New group')}
                  sub={t('Friends join from their player')}
                  onSelect={() => {
                    close();
                    createGroup(itemId, position()).then(() => toast(t('Group started')), () => toast(t("Couldn't start a group"), 'error'));
                  }}
                />
              </>
            )}
          </>
        )}
      </MenuButton>
    </>
  );
}

/* Play on: this device, another Jellyfin app, or (with "Offer an external player") an app on this device. */
export function CastMenu({ itemId, position, onCast, external }: {
  itemId: string;
  position: () => number;
  onCast: (sessionId: string) => void;
  external?: { apps: ExternalApp[]; open: (app: ExternalApp) => void };
}) {
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
          class={'btn player-btn' + (target ? ' is-on' : '')}
          aria-label={target ? t('Casting to {device}', { device: target.DeviceName ?? '' }) : t('Cast to a device')}
          aria-expanded={open ? 'true' : 'false'}
          onClick={() => { if (!open) load(); toggle(); }}
        >
          <Icon name="cast" size={22} />
        </button>
      )}
    >
      {(close) => (
        <>
          <span class="menu-label">{t('Play on')}</span>
          <MenuItem label={t('This device')} sub={t('Keep watching here')} pressed={!target} onSelect={() => { castTarget.set(null); close(); }} />
          {sessions === null && <span class="menu-item is-muted" aria-busy="true">{t('Looking for devices…')}</span>}
          {sessions?.length === 0 && <span class="menu-item is-muted">{t('No other Jellyfin apps are open')}</span>}
          {sessions?.map((s) => (
            <MenuItem
              key={s.Id}
              label={s.DeviceName || s.Client || t('Device')}
              sub={[s.Client, s.UserName].filter(Boolean).join(' · ')}
              pressed={target?.Id === s.Id}
              onSelect={() => {
                close();
                sendPlayCommand(s.Id, [itemId], secondsToTicks(position())).then(
                  () => {
                    castTarget.set(s);
                    toast(t('Playing on {device}', { device: s.DeviceName || s.Client || '' }));
                    onCast(s.Id);
                  },
                  () => toast(s.DeviceName ? t("Couldn't reach {device}", { device: s.DeviceName }) : t("Couldn't reach that device"), 'error')
                );
              }}
            />
          ))}
          {external && (
            <>
              <div class="menu-sep" />
              <span class="menu-label">{t('Open in another app')}</span>
              {external.apps.map((app) => (
                <MenuItem key={app.id} icon="external" label={app.label} sub={app.sub} onSelect={() => { close(); external.open(app); }} />
              ))}
            </>
          )}
        </>
      )}
    </MenuButton>
  );
}
