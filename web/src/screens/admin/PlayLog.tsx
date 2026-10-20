import { useEffect, useState } from 'preact/hooks';
import { request } from '../../api/client';
import { adminClearPlayLog, adminPlayLogProfile, type AdminConfig, type PlayLogProfile } from '../../api/seedy';
import { useStore } from '../../lib/store';
import { Panel, SavedNote, SelectField, SettingRow, Switch } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { confirm, toast } from '../../ui/overlay';
import { formatNumber, t } from '../../lib/i18n';
import { AdminLayout, LoadError, PanelSkeletons } from './AdminLayout';
import { ProfileAvatar } from './Requests';
import { editConfig, loadProfiles, profilesStore, statusStore, useAdminConfig } from './config';

type Log = AdminConfig['playLog'];

const RETENTION = [
  { value: 90, label: '90 days' },
  { value: 365, label: '1 year' },
  { value: 730, label: '2 years' },
  { value: 0, label: 'Forever' }
];

const USES: { key: 'useTop10' | 'useHistory' | 'useRowOrder' | 'useGenres'; name: string; help: string }[] = [
  { key: 'useTop10', name: 'Top 10 this week', help: 'Counts plays across all profiles' },
  { key: 'useHistory', name: 'Viewing history page', help: 'Each profile sees only its own plays' },
  { key: 'useRowOrder', name: 'Row order', help: 'Learns which home rows each person plays from' },
  { key: 'useGenres', name: 'Genre rows', help: 'Picks the genres each person watches most' }
];

export function AdminPlayLog() {
  const { config, error, save, reload } = useAdminConfig();
  /* The board has no save note at rest; it appears once something on this page has been changed. */
  const [touched, setTouched] = useState(false);
  useEffect(() => loadProfiles(), []);

  const set = (patch: Partial<Log>) => {
    setTouched(true);
    editConfig((c) => ({ ...c, playLog: { ...c.playLog, ...patch } }));
    statusStore.set((s) => (s.data ? { ...s, data: { ...s.data, playLog: { ...s.data.playLog, ...patch } } } : s));
  };

  return (
    <AdminLayout page="playlog" title={t('Play log')} lede={t('Seedy records every play to build the Top 10, viewing history and row order. Jellyfin itself only keeps the last play of each title.')}>
      {!config && error ? (
        <LoadError error={error} onRetry={reload} />
      ) : !config ? (
        <PanelSkeletons count={2} />
      ) : (
        <>
          <Panel id="log" title={t('Logging')}>
            <SettingRow name={t('Record plays')} help={t('Start, stop, position, device and the row it started from')}>
              <Switch label={t('Record plays')} checked={config.playLog.enabled} onChange={(v) => set({ enabled: v })} />
            </SettingRow>
            <SettingRow name={t('Keep plays for')} help={t('Older plays are deleted every night')} htmlFor="retention" last>
              <SelectField
                id="retention"
                minWidth={180}
                value={RETENTION.some((o) => o.value === config.playLog.retentionDays) ? config.playLog.retentionDays : 365}
                options={RETENTION.map((o) => ({ ...o, label: t(o.label) }))}
                onChange={(v) => set({ retentionDays: v })}
              />
            </SettingRow>
          </Panel>

          <Panel id="use" title={t('What the log is used for')}>
            {/* The board keeps the full 16 px under the last switch here. */}
            {USES.map((u) => (
              <SettingRow key={u.key} name={t(u.name)} help={t(u.help)}>
                <Switch label={t(u.name)} checked={config.playLog[u.key]} onChange={(v) => set({ [u.key]: v })} />
              </SettingRow>
            ))}
          </Panel>
        </>
      )}

      <ProfilesPanel />
      <DangerZone />
      {config && (touched || save !== 'saved') && <SavedNote state={save} />}
    </AdminLayout>
  );
}

/* ── Profiles ── */

async function exportPlays(p: PlayLogProfile) {
  const res = await request<Response>('GET', '/Seedy/Admin/PlayLog/Export', { query: { userId: p.userId }, raw: true });
  const blob = await res.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `seedy-plays-${p.name.trim().replace(/[\\/:*?"<>|\s]+/g, '-') || p.userId}.csv`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function ProfilesPanel() {
  const { data, error, loading } = useStore(profilesStore);
  const [busy, setBusy] = useState<string | null>(null);

  const toggle = async (p: PlayLogProfile, key: 'top10' | 'history', on: boolean) => {
    profilesStore.set((s) => ({ ...s, data: s.data?.map((x) => (x.userId === p.userId ? { ...x, [key]: on } : x)) }));
    try {
      profilesStore.set({ data: await adminPlayLogProfile(p.userId, { [key]: on }), loading: false });
    } catch (e) {
      profilesStore.set((s) => ({ ...s, data: s.data?.map((x) => (x.userId === p.userId ? { ...x, [key]: !on } : x)) }));
      toast(`${t('Couldn’t change {name}.', { name: p.name })} ${(e as Error).message || ''}`.trim(), 'error');
    }
  };

  const doExport = async (p: PlayLogProfile) => {
    setBusy(p.userId);
    try {
      await exportPlays(p);
    } catch (e) {
      toast(`${t('Couldn’t export {name}’s plays.', { name: p.name })} ${(e as Error).message || ''}`.trim(), 'error');
    } finally {
      setBusy(null);
    }
  };

  const clear = async (p: PlayLogProfile) => {
    const ok = await confirm({
      title: t('Clear {name}’s plays?', { name: p.name }),
      text: t('Removes {name}’s viewing history and their part in the Top 10 and row order. Watched status in Jellyfin is not touched. This can’t be undone.', { name: p.name }),
      action: t('Clear plays'),
      danger: true
    });
    if (!ok) return;
    setBusy(p.userId);
    try {
      await adminClearPlayLog(p.userId);
      toast(t('{name}’s plays cleared', { name: p.name }));
      loadProfiles(true);
    } catch (e) {
      toast(`${t('Couldn’t clear {name}’s plays.', { name: p.name })} ${(e as Error).message || ''}`.trim(), 'error');
    } finally {
      setBusy(null);
    }
  };

  return (
    <Panel id="prof" title={t('Profiles')} text={t('Leave a profile out of the Top 10, hide its history page, or export and clear its plays.')} gap={14}>
      {!data && error ? (
        <div class="admin-inline-error" role="alert">
          <span class="dot is-danger" />
          {t('Couldn’t load the profiles.')} {error.message}
          <button type="button" class="btn btn-secondary is-sm" onClick={() => loadProfiles(true)}>{t('Try again')}</button>
        </div>
      ) : !data ? (
        <div class="table-skel" aria-busy={loading ? 'true' : undefined}>
          {[0, 1, 2, 3].map((i) => <span key={i} class="skel" />)}
        </div>
      ) : (
        <div class="table-scroll">
          <div role="table" aria-label={t('Play log by profile')} class="table log-table">
            <div role="row" class="table-row">
              <span role="columnheader" class="table-head">{t('Profile')}</span>
              <span role="columnheader" class="table-head is-right">{t('Plays')}</span>
              <span role="columnheader" class="table-head is-center">Top 10</span>
              <span role="columnheader" class="table-head is-center">{t('History')}</span>
              <span role="columnheader" class="table-head is-right">{t('Data')}</span>
            </div>
            {data.map((p, i) => (
              <div role="row" class="table-row" key={p.userId}>
                <div role="cell" class="table-cell who">
                  <ProfileAvatar userId={p.userId} name={p.name} imageTag={p.imageTag} index={i} />
                  <span class="who-name">{p.name}</span>
                </div>
                <div role="cell" class="table-cell is-right is-plays">{formatNumber(p.plays)}</div>
                <div role="cell" class="table-cell is-flex is-center">
                  <Switch label={t('Count {name} in the Top 10', { name: p.name })} checked={p.top10} onChange={(v) => void toggle(p, 'top10', v)} />
                </div>
                <div role="cell" class="table-cell is-flex is-center">
                  <Switch label={t('Show {name} a history page', { name: p.name })} checked={p.history} onChange={(v) => void toggle(p, 'history', v)} />
                </div>
                <div role="cell" class="table-cell is-flex is-right log-data">
                  <button type="button" class="btn btn-secondary is-xs" disabled={busy === p.userId} aria-label={t('Export plays for {name}', { name: p.name })} onClick={() => void doExport(p)}>
                    <Icon name="download" size={14} />
                    {t('Export')}
                  </button>
                  <button type="button" class="btn btn-ghost is-36" disabled={busy === p.userId || p.plays === 0} aria-label={t('Clear plays for {name}', { name: p.name })} onClick={() => void clear(p)}>
                    {t('Clear')}
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </Panel>
  );
}

/* ── Danger zone ── */

function DangerZone() {
  const [busy, setBusy] = useState(false);
  const clearAll = async () => {
    const ok = await confirm({
      title: t('Clear the whole play log?'),
      text: t('Every profile’s history, the Top 10 and what Seedy learned about row order will be removed. Watched status in Jellyfin is not touched. This can’t be undone.'),
      action: t('Clear play log'),
      danger: true
    });
    if (!ok) return;
    setBusy(true);
    try {
      await adminClearPlayLog();
      toast(t('Play log cleared'));
      loadProfiles(true);
      statusStore.set((s) => (s.data ? { ...s, data: { ...s.data, playLog: { ...s.data.playLog, plays: 0 } } } : s));
    } catch (e) {
      toast(`${t('Couldn’t clear the play log.')} ${(e as Error).message || ''}`.trim(), 'error');
    } finally {
      setBusy(false);
    }
  };
  return (
    <section aria-labelledby="danger-h" class="danger-zone">
      <div>
        <h2 id="danger-h" class="panel-title">{t('Clear the whole play log')}</h2>
        <p class="panel-text">{t("Removes every profile's history, the Top 10 and what Seedy learned about row order. Watched status in Jellyfin is not touched. This can't be undone.")}</p>
      </div>
      <button type="button" class="btn btn-danger" disabled={busy} onClick={() => void clearAll()}>{busy ? t('Clearing…') : t('Clear play log')}</button>
    </section>
  );
}
