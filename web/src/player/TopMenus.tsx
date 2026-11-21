import { useState } from 'preact/hooks';
import { syncPlayList } from '../api/jellyfin';
import type { SyncPlayGroup } from '../api/types';
import { castTarget } from '../lib/cast';
import { secondsToTicks } from '../lib/format';
import { useStore } from '../lib/store';
import { t } from '../lib/i18n';
import { Icon } from '../ui/icons';
import { MenuButton, MenuItem, toast } from '../ui/overlay';
import type { ExternalApp } from './external';
import { createGroup, groupLoading, joinGroup, leaveGroup, syncAccess, syncStore } from './syncplay';
import { CastTargets, sendToDevice, useCastSessions } from '../ui/cast-menu';

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
          {others === 0 ? t('Waiting for others')
            : groupLoading(sync) ? t('Waiting for everyone')
              : t('Watching with {n}', { n: others, total: others + 1 })}
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
  const { sessions, load } = useCastSessions();
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
          <CastTargets
            sessions={sessions}
            target={target}
            hereSub={t('Keep watching here')}
            herePressed={!target}
            onHere={() => { castTarget.set(null); close(); }}
            onPick={(s) => {
              close();
              void sendToDevice(s, [itemId], secondsToTicks(position())).then((ok) => {
                if (!ok) return;
                castTarget.set(s);
                onCast(s.Id);
              });
            }}
          />
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
