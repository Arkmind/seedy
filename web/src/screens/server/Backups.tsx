import { useEffect, useRef, useState } from 'preact/hooks';
import { t } from '../../lib/i18n';
import { safeLocal, saveLocal, useStore } from '../../lib/store';
import { NumField, Switch } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { Sheet, confirm, toast } from '../../ui/overlay';
import { AdminLayout } from '../admin/AdminLayout';
import { type BackupFile, type BackupManifest, type BackupOptions, type BackupSchedule, createBackup, deleteSeedyBackup, getBackups, isNotFound, reasonText, restoreBackup, saveSeedyBackupSchedule, seedyBackupDownloadUrl, seedyBackups, seedyBackupSchedule } from './api';
import { copyText, dateTime, fmtSize, majorMinor, realDate, weekdayName } from './format';
import { loadServerOffset, serverOffsetStore, serverTimeNote } from './status';
import { ListEmpty, ListError } from './advanced-parts';
import './advanced.css';

/* English copy, translated where it is shown. */
const PARTS: { key: keyof BackupOptions; name: string; help: string; short: string }[] = [
  { key: 'Database', name: 'Database', help: 'Users, watch history, settings, library index', short: 'database' },
  { key: 'Metadata', name: 'Metadata', help: 'Downloaded artwork and info', short: 'metadata' },
  { key: 'Subtitles', name: 'Subtitles', help: 'Downloaded and extracted subtitles', short: 'subtitles' },
  { key: 'Trickplay', name: 'Seek previews', help: 'Trickplay images, often the largest part', short: 'seek previews' }
];

const PARTS_KEY = 'seedy.admin.backupParts';
const DEFAULT_PARTS: BackupOptions = { Database: true, Metadata: true, Subtitles: true, Trickplay: false };
const DEFAULT_SCHEDULE: BackupSchedule = { enabled: false, dayOfWeek: 'Sunday', timeOfDay: '04:00', keep: 4, options: DEFAULT_PARTS };
const KINDS: Record<BackupFile['kind'], string> = { manual: 'Manual', automatic: 'Automatic', upgrade: 'Before upgrade' };

const fileOf = (path: string) => path.split(/[\\/]/).pop() ?? path;

function partsLine(o: BackupOptions): string {
  const list = PARTS.filter((p) => o[p.key]).map((p) => t(p.short));
  if (!list.length) return t('Server settings only');
  const s = list.join(', ');
  return s.charAt(0).toUpperCase() + s.slice(1);
}

/* Whether the Seedy plugin can size, serve, delete and schedule backups (Jellyfin 12 itself can't). */
let seedySupport: boolean | null = null;

export function ServerBackups() {
  const offset = useStore(serverOffsetStore);
  useEffect(loadServerOffset, []);
  const [list, setList] = useState<BackupManifest[] | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [files, setFiles] = useState<Map<string, BackupFile> | null>(null);
  const [supported, setSupported] = useState<boolean | null>(seedySupport);
  const [schedule, setSchedule] = useState<BackupSchedule | null>(null);
  const [parts, setParts] = useState<BackupOptions>(() => ({ ...DEFAULT_PARTS, ...safeLocal<Partial<BackupOptions>>(PARTS_KEY, {}) }));
  const [creating, setCreating] = useState(false);
  const [createError, setCreateError] = useState('');
  const [pathOf, setPathOf] = useState<BackupManifest | null>(null);
  const saveTimer = useRef<number>();
  /* A schedule edit still waiting for its delay when the page closes is sent then, not dropped. */
  const pendingSave = useRef<BackupSchedule | null>(null);
  const saveSeq = useRef(0);

  const loadList = () => {
    setError(null);
    getBackups().then(
      (l) => setList([...l].sort((a, b) => (realDate(b.DateCreated)?.getTime() ?? 0) - (realDate(a.DateCreated)?.getTime() ?? 0))),
      (e: Error) => setError(e)
    );
    if (seedySupport === false) return;
    seedyBackups().then(
      (f) => { seedySupport = true; setSupported(true); setFiles(new Map(f.map((x) => [x.fileName.toLowerCase(), x]))); },
      (e) => { if (isNotFound(e)) { seedySupport = false; setSupported(false); } }
    );
  };

  useEffect(() => {
    loadList();
    if (seedySupport !== false) {
      seedyBackupSchedule().then(
        (s) => { setSchedule(s); setParts(s.options); },
        (e) => { if (isNotFound(e)) { seedySupport = false; setSupported(false); } }
      );
    }
    return () => {
      window.clearTimeout(saveTimer.current);
      if (pendingSave.current) saveSeedyBackupSchedule(pendingSave.current).catch((e) => toast(t('Couldn’t save automatic backups. {error}', { error: reasonText(e) }), 'error', 6000));
    };
  }, []);

  const saveSchedule = (next: BackupSchedule, delay = 0) => {
    setSchedule(next);
    pendingSave.current = next;
    window.clearTimeout(saveTimer.current);
    saveTimer.current = window.setTimeout(() => {
      pendingSave.current = null;
      const seq = ++saveSeq.current;
      /* Only the newest save's answer replaces what's on screen. */
      saveSeedyBackupSchedule(next).then((r) => { if (seq === saveSeq.current) setSchedule(r); }, (e) => toast(t('Couldn’t save automatic backups. {error}', { error: reasonText(e) }), 'error', 6000));
    }, delay);
  };

  const setPart = (key: keyof BackupOptions, on: boolean) => {
    const next = { ...parts, [key]: on };
    setParts(next);
    saveLocal(PARTS_KEY, next);
    setCreateError('');
    if (schedule && supported) saveSchedule({ ...schedule, options: next }, 400);
  };

  const create = async () => {
    setCreating(true);
    setCreateError('');
    try {
      const m = await createBackup(parts);
      toast(t('Backup created · {when}', { when: dateTime(realDate(m.DateCreated) ?? new Date()) }));
      loadList();
    } catch (e) {
      setCreateError(reasonText(e));
    } finally {
      setCreating(false);
    }
  };

  const restore = async (b: BackupManifest) => {
    const when = dateTime(realDate(b.DateCreated) ?? new Date());
    const ok = await confirm({
      title: t('Restore the backup from {when}?', { when }),
      text: t('The server restarts and replaces its {parts} and settings with the backup’s. Anything changed since {when} is lost, and everyone watching is stopped while it restarts.', { parts: partsLine(b.Options).toLowerCase(), when }),
      action: t('Restore and restart'),
      danger: true
    });
    if (!ok) return;
    try {
      await restoreBackup(fileOf(b.Path));
      toast(t('Restoring. The server is restarting…'), 'pending', 8000);
    } catch (e) {
      toast(t('Couldn’t start the restore. {error}', { error: reasonText(e) }), 'error', 6000);
    }
  };

  const remove = async (b: BackupManifest) => {
    const when = dateTime(realDate(b.DateCreated) ?? new Date());
    const ok = await confirm({ title: t('Delete the backup from {when}?', { when }), text: t('The archive is deleted from the server. This can’t be undone.'), action: t('Delete backup'), danger: true });
    if (!ok) return;
    try {
      await deleteSeedyBackup(fileOf(b.Path));
      toast(t('Backup deleted'));
      loadList();
    } catch (e) {
      toast(t('Couldn’t delete the backup. {error}', { error: reasonText(e) }), 'error', 6000);
    }
  };

  const anyPart = PARTS.some((p) => parts[p.key]);
  const sched = schedule ?? DEFAULT_SCHEDULE;
  const autoOff = supported === false;

  return (
    <AdminLayout page="backups" title={t('Backups')} lede={t('Snapshots taken while the server runs. Restoring one replaces the current server state and restarts it.')}>
      <section aria-labelledby="adv-new-h" class="adv-panel is-16 adv-gap24">
        <h2 id="adv-new-h" class="adv-panel-title">{t('New backup')}</h2>
        <div class="adv-parts">
          {PARTS.map((p) => (
            <label key={p.key} class="adv-part">
              <input type="checkbox" checked={parts[p.key]} onChange={(e) => setPart(p.key, (e.currentTarget as HTMLInputElement).checked)} />
              <span class="adv-part-text"><span class="adv-part-name">{t(p.name)}</span><span class="adv-part-help">{t(p.help)}</span></span>
            </label>
          ))}
        </div>
        <div class="adv-panel-foot">
          {createError
            ? <p class="form-error" role="alert"><Icon name="alert" size={16} />{createError}</p>
            : <span>{anyPart ? t('Saved on the server. Download a copy to keep it somewhere else.') : t('Check at least one part to back up.')}</span>}
          <button type="button" class={'btn btn-primary adv-btn44' + (creating ? ' is-busy' : '')} disabled={creating || !anyPart} onClick={() => void create()}>
            {creating ? t('Creating backup…') : t('Create backup')}
          </button>
        </div>
      </section>

      <section aria-labelledby="adv-auto-h" class="adv-panel">
        <h2 id="adv-auto-h" class="adv-panel-title is-spaced">{t('Automatic backups')}</h2>
        <div class="adv-auto-row">
          <span class="adv-auto-label">
            <span class="adv-auto-name">{t('Back up every week')}</span>
            {autoOff
              ? <span class="adv-auto-help is-pending">{t('Not available on this server yet. It needs a newer Seedy plugin.')}</span>
              : <span class="adv-auto-help">{t(serverTimeNote(offset) ? '{day} at {time} (server time), with the parts checked above' : '{day} at {time}, with the parts checked above', { day: weekdayName(sched.dayOfWeek), time: sched.timeOfDay })}</span>}
          </span>
          <Switch label={t('Back up every week')} checked={!autoOff && sched.enabled} disabled={autoOff || !schedule} onChange={(on) => saveSchedule({ ...sched, enabled: on, options: parts })} />
        </div>
        <div class="adv-auto-row is-last">
          <span class="adv-auto-label">
            <span class="adv-auto-name">{t('Keep')}</span>
            <span class="adv-auto-help">{t('Older automatic backups are deleted')}</span>
          </span>
          <span class={'adv-keep' + (autoOff || !schedule ? ' is-disabled' : '')}>
            <NumField
              label={t('The last')}
              ariaLabel={t('Number of automatic backups to keep')}
              value={sched.keep}
              min={1}
              max={52}
              onChange={(n) => { if (schedule && !autoOff) saveSchedule({ ...sched, keep: n }, 300); }}
            />
          </span>
        </div>
      </section>

      <section aria-labelledby="adv-list-h" class="adv-group">
        <h2 id="adv-list-h" class="adv-group-label">{t('On this server')}{list ? ` · ${list.length}` : ''}</h2>
        {!list && error ? (
          <ListError message={t('Couldn’t load the backups.')} error={error} onRetry={loadList} />
        ) : !list ? (
          <div class="adv-list" aria-busy="true">
            {[0, 1].map((i) => (
              <div key={i} class="adv-skel-row">
                <span style={{ flex: '1 1 280px', display: 'flex', flexDirection: 'column', gap: '8px' }}>
                  <span class="skel skel-line" style={{ width: '200px', height: '15px' }} />
                  <span class="skel skel-line" style={{ width: '220px' }} />
                </span>
                <span class="skel" style={{ width: '230px', height: '40px', borderRadius: '12px' }} />
              </div>
            ))}
          </div>
        ) : !list.length ? (
          <ListEmpty title={t('No backups yet')}>{t('Create one above before changing big settings or upgrading Jellyfin.')}</ListEmpty>
        ) : (
          <div class="adv-list">
            {list.map((b) => {
              const name = fileOf(b.Path);
              const f = files?.get(name.toLowerCase());
              const when = dateTime(realDate(b.DateCreated) ?? new Date());
              return (
                <div key={b.Path} class="adv-brow">
                  <div class="adv-bmain">
                    <span class="adv-bwhen-line">
                      <span class="adv-bwhen">{when}</span>
                      <span class="adv-bkind">{t(KINDS[f?.kind ?? 'manual'])}</span>
                    </span>
                    <span class="adv-bparts">{partsLine(b.Options)}</span>
                  </div>
                  <span class="adv-bsize">{[f ? fmtSize(f.size) : '', `Jellyfin ${majorMinor(b.ServerVersion)}`].filter(Boolean).join(' · ')}</span>
                  <div class="adv-bbuttons">
                    {supported ? (
                      <a class="btn btn-secondary is-sm" href={seedyBackupDownloadUrl(name)} download={name}>{t('Download')}</a>
                    ) : (
                      <button type="button" class="btn btn-secondary is-sm" onClick={() => setPathOf(b)}>{t('Download')}</button>
                    )}
                    <button type="button" class="btn btn-secondary is-sm" onClick={() => void restore(b)}>{t('Restore')}</button>
                    <button
                      type="button"
                      class="btn icon-btn is-bare is-40"
                      aria-label={t('Delete backup from {when}', { when })}
                      title={supported ? undefined : t('Jellyfin can’t delete backups from here yet')}
                      disabled={!supported}
                      onClick={() => void remove(b)}
                    >
                      <TrashIcon />
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </section>

      <PathSheet backup={pathOf} onClose={() => setPathOf(null)} />
    </AdminLayout>
  );
}

/* The board's bin (slimmer than the Foundations one). */
function TrashIcon() {
  return (
    <svg viewBox="0 0 24 24" width="17" height="17" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      <path d="M4 7h16" /><path d="M10 11v6" /><path d="M14 11v6" /><path d="M6 7l1 12a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1l1-12" /><path d="M9 7V4h6v3" />
    </svg>
  );
}

/* Without Seedy's download endpoint, the archive can only be fetched from the server's disk. */
function PathSheet({ backup, onClose }: { backup: BackupManifest | null; onClose: () => void }) {
  return (
    <Sheet open={!!backup} onClose={onClose} label={t('Download backup')}>
      {backup && (
        <>
          <div class="sheet-head">
            <h2 class="sheet-title">{t('Copy it from the server')}</h2>
            <button type="button" class="btn icon-btn is-bare is-36" aria-label={t('Close')} onClick={onClose}><Icon name="close" size={18} /></button>
          </div>
          <p class="sheet-text">{t('Jellyfin doesn’t serve backups over the web yet. The archive is in the server’s data folder; copy it from there to keep it somewhere else.')}</p>
          <code class="adv-path adv-mono">{backup.Path}</code>
          <div class="sheet-actions">
            <button type="button" class="btn btn-ghost is-light" onClick={onClose}>{t('Close')}</button>
            <button type="button" class="btn btn-primary is-sm" onClick={() => void copyText(backup.Path).then((ok) => toast(ok ? t('Path copied') : t('Couldn’t copy. Select the path instead.'), ok ? 'ok' : 'error'))}>{t('Copy path')}</button>
          </div>
        </>
      )}
    </Sheet>
  );
}
