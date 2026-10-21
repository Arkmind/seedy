import { useEffect, useState } from 'preact/hooks';
import { cardArt } from '../../api/images';
import type { BaseItem } from '../../api/types';
import { userStore } from '../../lib/app-state';
import { episodeTag, pct } from '../../lib/format';
import { formatNumber, t, tn, tx } from '../../lib/i18n';
import { Link, href, navigate } from '../../lib/router';
import { useStore } from '../../lib/store';
import { Art } from '../../ui/cards';
import { Icon } from '../../ui/icons';
import { Sheet, confirm, toast } from '../../ui/overlay';
import { AdminLayout } from '../admin/AdminLayout';
import { type ActivityEntry, errText, type FullUser, getActivity, getItemCounts, getPackages, getPlugins, getSessions, getStorage, getSystemInfo, getTasks, getUsers, type ItemCounts, type PackageInfo, type PluginInfo, restartingStore, restartServer, sameId, scanAllLibraries, sendSessionMessage, type Session, shutdownServer, stopSession, type SystemInfo, type TaskInfo } from './api';
import { InlineError, Notice, usePoll, UserAvatar } from './parts';
import { activityWhen, dayDiff, hhmm, majorMinor, platformLabel, playDetail, playMethod, shortDate, uptime } from './format';
import { countPluginUpdates, pluginTargetAbi } from './plugins';
import { sentenceCase, serverStartFromTasks } from './tasks';
import { pluginUpdatesStore } from './status';
import { adminStatus } from '../../api/seedy';
import { type Disk, disks, fmtDisk, fullText, level, WARN_AT } from './storage';

/* Server · Overview: what the server is doing now. Sessions, tasks and activity refresh every few seconds. */

interface Data<T> { data?: T; error?: Error }

function useLive<T>(load: (signal?: AbortSignal) => Promise<T>, every: number): Data<T> & { reload: () => void } {
  const [state, setState] = useState<Data<T>>({});
  const run = () => { load().then((data) => setState({ data }), (error: Error) => setState((s) => ({ data: s.data, error }))); };
  useEffect(() => { run(); /* eslint-disable-next-line react-hooks/exhaustive-deps */ }, []);
  usePoll(run, every);
  return { ...state, reload: run };
}

export function ServerOverview() {
  const info = useLive(getSystemInfo, 15_000);
  const sessions = useLive(getSessions, 5_000);
  const tasks = useLive(getTasks, 5_000);
  const activity = useLive(() => getActivity({ limit: 40, startIndex: 0 }), 10_000);
  const counts = useLive(getItemCounts, 60_000);
  const users = useLive(getUsers, 60_000);
  const plugins = useLive(getPlugins, 60_000);
  const storage = useLive(getStorage, 60_000);
  const diskList = storage.data ? disks(storage.data) : undefined;
  const [packages, setPackages] = useState<PackageInfo[] | null>(null);
  const [started, setStarted] = useState<Date | undefined>();
  const [os, setOs] = useState('');
  const restarting = useStore(restartingStore);

  useEffect(() => {
    getPackages().then(setPackages, () => setPackages([]));
    /* The Seedy plugin reports when the server process started and its OS; otherwise the startup tasks and paths tell. */
    adminStatus().then((s) => {
      if (s.serverStartedAt) setStarted(new Date(s.serverStartedAt));
      if (s.os) setOs(s.os);
    }, () => undefined);
  }, []);

  const serverVersion = info.data?.Version ?? '';
  useEffect(() => {
    if (plugins.data && packages && serverVersion) countPluginUpdates(plugins.data, packages, serverVersion);
  }, [plugins.data, packages, serverVersion]);

  const since = started ?? (tasks.data ? serverStartFromTasks(tasks.data) : undefined);
  const scanTask = tasks.data?.find((x) => x.Key === 'RefreshLibrary');
  const scanning = scanTask?.State === 'Running';

  const scanAll = async () => {
    try {
      await scanAllLibraries();
      toast(t('Scanning all libraries'));
      tasks.reload();
    } catch (e) {
      toast(t('Couldn’t start the scan. {error}', { error: errText(e) }), 'error');
    }
  };

  const restart = async () => {
    const ok = await confirm({
      title: t('Restart the server?'),
      text: t('Everyone watching is interrupted for a minute while Jellyfin restarts. Seedy reconnects on its own when the server is back.'),
      action: t('Restart')
    });
    if (!ok) return;
    restartServer().then(() => { info.reload(); sessions.reload(); tasks.reload(); }, (e) => toast(t('Couldn’t restart. {error}', { error: errText(e) }), 'error'));
  };

  const shutdown = async () => {
    const ok = await confirm({
      title: t('Shut down the server?'),
      text: t('Playback stops for everyone, and nobody can use Seedy or Jellyfin until the server is started again on the machine it runs on.'),
      action: t('Shut down'),
      danger: true
    });
    if (!ok) return;
    try {
      await shutdownServer();
      toast(t('The server is shutting down'), 'pending', 8000);
    } catch (e) {
      toast(t('Couldn’t shut down. {error}', { error: errText(e) }), 'error');
    }
  };

  const lede = info.data
    ? [info.data.ServerName, `Jellyfin ${majorMinor(info.data.Version)}`, platformLabel(info.data, os), since ? uptime(since) : ''].filter(Boolean).join(' · ')
    : info.error ? t('Server details unavailable') : <span class="skel skel-line srv-lede-skel" />;

  const playing = (sessions.data ?? []).filter((s) => s.NowPlayingItem);

  return (
    <AdminLayout
      page="overview"
      title={t('Overview')}
      lede={<span class="srv-lede">{lede}</span>}
      wide
      actions={
        <>
          <button type="button" class="btn btn-primary is-sm srv-btn-16" disabled={scanning || restarting} onClick={() => void scanAll()}>
            <ScanIcon />
            {scanning ? t('Scanning…') : t('Scan all libraries')}
          </button>
          <button type="button" class="btn btn-secondary" disabled={restarting} onClick={() => void restart()}>{restarting ? t('Restarting…') : t('Restart')}</button>
          <button type="button" class="btn srv-btn-danger" disabled={restarting} onClick={() => void shutdown()}>{t('Shut down')}</button>
        </>
      }
    >
      <Notices info={info.data} plugins={plugins.data} packages={packages} disks={diskList} restarting={restarting} onRestart={() => void restart()} />

      <section aria-labelledby="ov-now" class="srv-ov-now">
        <div class="srv-sec-head">
          <h2 id="ov-now" class="srv-sec-title">{t('Playing now')} <span>· {sessions.data ? playing.length : '…'}</span></h2>
          <Link to="/admin/devices" class="srv-sec-link">{t('All devices')}</Link>
        </div>
        {!sessions.data && sessions.error ? (
          <InlineError text={t('Couldn’t load what’s playing.')} onRetry={sessions.reload} />
        ) : !sessions.data ? (
          <div class="srv-now-grid" aria-busy="true">{[0, 1, 2].map((i) => <div key={i} class="srv-now-card"><span class="skel srv-now-art" /><span class="skel skel-line" style={{ width: '70%' }} /><span class="skel skel-line" style={{ width: '45%' }} /></div>)}</div>
        ) : playing.length === 0 ? (
          <div class="empty srv-empty">
            <span class="empty-icon"><Icon name="tv" size={26} /></span>
            <div class="empty-body">
              <span class="empty-title">{t('Nothing playing')}</span>
              <span class="empty-text">{t('When someone starts watching, it shows up here with how it’s playing on their device.')}</span>
            </div>
          </div>
        ) : (
          <div class="srv-now-grid">
            {playing.map((s) => <NowCard key={s.Id} s={s} users={users.data} onChanged={sessions.reload} />)}
          </div>
        )}
      </section>

      <div class="srv-ov-pair">
        <TasksPanel tasks={tasks} />
        <LibraryPanel counts={counts} />
      </div>

      <StoragePanel list={diskList} error={!!storage.error} onRetry={storage.reload} />

      <ActivityPanel activity={activity} />
    </AdminLayout>
  );
}

function ScanIcon() {
  return (
    <svg viewBox="0 0 24 24" width="17" height="17" class="ic" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true">
      <path d="M20 12a8 8 0 1 1-2.3-5.6" /><path d="M20 4v5h-5" />
    </svg>
  );
}

/* ── Notices ── */

function Notices({ info, plugins, packages, disks: diskList, restarting, onRestart }: {
  info?: SystemInfo; plugins?: PluginInfo[]; packages: PackageInfo[] | null; disks?: Disk[]; restarting: boolean; onRestart: () => void;
}) {
  const updates = useStore(pluginUpdatesStore);
  const broken = (plugins ?? []).filter((p) => p.Status === 'Malfunctioned' || p.Status === 'NotSupported');
  const server = majorMinor(info?.Version);
  const items = [];
  for (const p of broken) {
    const abi = packages ? pluginTargetAbi(p, packages) : undefined;
    let why: string;
    if (p.Status === 'NotSupported') why = abi ? t('It needs Jellyfin {version} or newer.', { version: majorMinor(abi) }) : t('It isn’t compatible with Jellyfin {version}.', { version: server });
    else if (abi && server && majorMinor(abi) !== server && Number(majorMinor(abi).split('.')[0]) < Number(server.split('.')[0]))
      why = t('It was built for Jellyfin {built}; {server} needs a rebuilt version.', { built: majorMinor(abi), server });
    else why = t('It stopped with an error while starting. The server log has the details.');
    items.push(
      <Notice key={p.Id} tone="warn" action={t('Open plugins')} href={href('/admin/plugins')} onAction={() => navigate('/admin/plugins')}>
        <b>{t('{name} won’t load.', { name: p.Name })}</b> {why}
      </Notice>
    );
  }
  for (const d of (diskList ?? []).filter((x) => x.pct >= WARN_AT)) {
    items.push(
      <Notice key={'disk' + d.mount} tone="warn" action={t('See storage')} onAction={() => document.getElementById('ov-storage')?.scrollIntoView({ behavior: 'smooth', block: 'start' })}>
        <b>{t('{name} is {pct}% full.', { name: d.name, pct: d.pct })}</b> {fullText(d)}.
      </Notice>
    );
  }
  if (restarting) {
    items.push(<Notice key="restarting" tone="warn" action={t('Restarting…')}><b>{t('The server is restarting.')}</b> {t('Seedy reconnects when it’s back.')}</Notice>);
  } else if (info?.HasPendingRestart) {
    items.push(<Notice key="restart" tone="warn" action={t('Restart')} onAction={onRestart}><b>{t('Restart needed.')}</b> {t('Some changes take effect after the server restarts.')}</Notice>);
  }
  if (updates > 0) {
    items.push(
      <Notice key="updates" tone="accent" action={t('Review')} href={href('/admin/plugins')} onAction={() => navigate('/admin/plugins')}>
        {tx(updates === 1 ? '{updates} is ready to install.' : '{updates} are ready to install.', {
          updates: <b>{tn(updates, '{n} plugin update', '{n} plugin updates')}</b>
        })}
      </Notice>
    );
  }
  if (!items.length) return null;
  return <div class="srv-notices">{items}</div>;
}


/* ── Playing now ── */

const METHOD = {
  direct: { label: 'Direct play', dot: 'var(--seedy-ok)' },
  stream: { label: 'Direct stream', dot: '#9fd3ff' },
  transcode: { label: 'Transcoding', dot: 'var(--seedy-pending)' }
} as const;

function sessionTitle(item: BaseItem): string {
  if (item.Type === 'Episode') return [item.SeriesName, episodeTag(item)].filter(Boolean).join(' · ') || item.Name;
  if (item.Type === 'Audio') return [item.Name, item.AlbumArtist].filter(Boolean).join(' · ');
  return item.Name;
}

function NowCard({ s, users, onChanged }: { s: Session; users?: FullUser[]; onChanged: () => void }) {
  const item = s.NowPlayingItem!;
  const method = METHOD[playMethod(s)];
  const total = item.RunTimeTicks ?? 0;
  const frac = total > 0 ? Math.min(1, (s.PlayState?.PositionTicks ?? 0) / total) : 0;
  const index = Math.max(0, users?.findIndex((u) => sameId(u.Id, s.UserId)) ?? 0);
  const user = users?.find((u) => sameId(u.Id, s.UserId));
  const name = s.UserName ?? user?.Name ?? t('Someone');
  const [msgOpen, setMsgOpen] = useState(false);
  const canMessage = !!s.SupportedCommands?.includes('DisplayMessage');
  const canStop = !!s.SupportsMediaControl;
  const detail = (s.PlayState?.IsPaused ? `${t('Paused')} · ` : '') + playDetail(s);

  const stop = async () => {
    const ok = await confirm({
      title: t('Stop {name}’s playback?', { name }),
      text: t('{title} stops on {device}. {name} can pick it up again from where it stopped.', { title: sessionTitle(item), device: s.DeviceName ?? t('their device'), name }),
      action: t('Stop playback'),
      danger: true
    });
    if (!ok) return;
    try {
      await stopSession(s.Id);
      toast(t('Stopped playback on {device}', { device: s.DeviceName ?? t('the device') }));
      window.setTimeout(onChanged, 800);
    } catch (e) {
      toast(t('Couldn’t stop it. {error}', { error: errText(e) }), 'error');
    }
  };

  return (
    <article class="srv-now-card">
      <div class="srv-now-art card-media">
        <Art art={cardArt(item, 640)} title={item.SeriesName ?? item.Name} />
        <span class="pill is-scrim srv-now-pill"><span class="pip" style={{ background: method.dot }} />{t(method.label)}</span>
        <div class="srv-now-bar" role="progressbar" aria-label={t('Progress')} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(frac * 100)}><i style={{ width: pct(frac) }} /></div>
      </div>
      <div class="srv-now-who">
        <UserAvatar user={{ Id: s.UserId ?? '', Name: name, PrimaryImageTag: s.UserPrimaryImageTag }} index={index} size={32} />
        <div class="srv-now-text">
          <span class="srv-now-title">{sessionTitle(item)}</span>
          <span class="srv-now-user">{[name, s.DeviceName, s.Client].filter(Boolean).join(' · ')}</span>
          <span class="srv-now-detail">{detail}</span>
        </div>
      </div>
      <div class="srv-now-actions">
        <button type="button" class="btn srv-mini" aria-label={t('Send a message to {name}', { name })} disabled={!canMessage} title={canMessage ? undefined : t('This app can’t show messages')} onClick={() => setMsgOpen(true)}>{t('Message')}</button>
        <button type="button" class="btn srv-mini" aria-label={t('Stop playback for {name}', { name })} disabled={!canStop} title={canStop ? undefined : t('This app can’t be controlled remotely')} onClick={() => void stop()}>{t('Stop')}</button>
      </div>
      <MessageSheet open={msgOpen} session={s} name={name} onClose={() => setMsgOpen(false)} />
    </article>
  );
}

function MessageSheet({ open, session, name, onClose }: { open: boolean; session: Session; name: string; onClose: () => void }) {
  const me = useStore(userStore);
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  useEffect(() => { if (open) setText(''); }, [open]);
  const send = async () => {
    if (!text.trim()) return;
    setSending(true);
    try {
      await sendSessionMessage(session.Id, t('Message from {name}', { name: me?.Name ?? t('the admin') }), text.trim());
      toast(t('Message sent to {name}', { name: session.DeviceName ?? name }));
      onClose();
    } catch (e) {
      toast(t('Couldn’t send it. {error}', { error: errText(e) }), 'error');
    } finally {
      setSending(false);
    }
  };
  return (
    <Sheet open={open} onClose={onClose} label={t('Send a message to {name}', { name })}>
      <div class="sheet-head">
        <div class="srv-sheet-headtext">
          <h2 class="sheet-title">{t('Message {name}', { name })}</h2>
          <p class="sheet-text">{t('Shows on {device} over what’s playing, for a few seconds.', { device: session.DeviceName ?? t('their screen') })}</p>
        </div>
      </div>
      <form class="srv-form" onSubmit={(e) => { e.preventDefault(); void send(); }}>
        <textarea class="srv-textarea" rows={3} aria-label={t('Message')} placeholder={t('Dinner’s ready in 10 minutes')} value={text} maxLength={300} onInput={(e) => setText((e.currentTarget as HTMLTextAreaElement).value)} />
        <div class="sheet-actions">
          <button type="button" class="btn btn-ghost is-light" onClick={onClose}>{t('Cancel')}</button>
          <button type="submit" class="btn btn-primary is-sm" disabled={!text.trim() || sending}>{sending ? t('Sending…') : t('Send')}</button>
        </div>
      </form>
    </Sheet>
  );
}

/* ── Running tasks ── */

function TasksPanel({ tasks }: { tasks: Data<TaskInfo[]> & { reload: () => void } }) {
  const running = (tasks.data ?? []).filter((x) => x.State !== 'Idle').sort((a, b) => (b.CurrentProgressPercentage ?? 0) - (a.CurrentProgressPercentage ?? 0));
  const lastScan = tasks.data?.find((x) => x.Key === 'RefreshLibrary')?.LastExecutionResult?.EndTimeUtc;
  return (
    <section aria-labelledby="ov-tasks" class="srv-box">
      <div class="srv-box-head">
        <h2 id="ov-tasks" class="srv-box-title">{t('Running tasks')}</h2>
        <Link to="/admin/tasks" class="srv-box-link">{t('Scheduled tasks')}</Link>
      </div>
      {!tasks.data && tasks.error ? (
        <div class="srv-box-row"><InlineError text={t('Couldn’t load tasks.')} onRetry={tasks.reload} /></div>
      ) : !tasks.data ? (
        [0, 1].map((i) => <div key={i} class="srv-task"><span class="skel skel-line" style={{ width: '50%', height: '14px' }} /><span class="skel srv-task-bar-skel" /></div>)
      ) : running.length === 0 ? (
        <div class="srv-task is-idle">
          <span class="srv-task-name">{t('Nothing running')}</span>
          <span class="srv-task-sub">{lastScan ? t('The last library scan finished {when}.', { when: whenPhrase(new Date(lastScan)) }) : t('Library scans and other jobs show up here while they run.')}</span>
        </div>
      ) : (
        running.map((task) => {
          const p = task.CurrentProgressPercentage;
          const label = task.State === 'Cancelling' ? t('Stopping') : p === undefined || p === null ? t('Starting') : formatNumber(Math.floor(p) / 100, { style: 'percent' });
          return (
            <div key={task.Id} class="srv-task">
              <div class="srv-task-line"><span class="srv-task-name">{sentenceCase(task.Name)}</span><span class="srv-task-state">{label}</span></div>
              <div class="srv-task-bar" role="progressbar" aria-label={sentenceCase(task.Name)} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(p ?? 0)}><i style={{ width: `${Math.max(0, Math.min(100, p ?? 0))}%` }} /></div>
            </div>
          );
        })
      )}
    </section>
  );
}

/* "today at 20:02", "yesterday", "on 17 Oct": the {when} of a sentence. */
function whenPhrase(d: Date): string {
  const days = dayDiff(d);
  return days === 0 ? t('today at {time}', { time: hhmm(d) }) : days === 1 ? t('yesterday') : t('on {date}', { date: shortDate(d) });
}

/* ── Library ── */

const COUNT_KINDS: { key: keyof ItemCounts; label: string }[] = [
  { key: 'MovieCount', label: 'Movies' },
  { key: 'SeriesCount', label: 'Shows' },
  { key: 'EpisodeCount', label: 'Episodes' },
  { key: 'AlbumCount', label: 'Albums' },
  { key: 'SongCount', label: 'Songs' },
  { key: 'MusicVideoCount', label: 'Music videos' },
  { key: 'BookCount', label: 'Books' }
];

function LibraryPanel({ counts }: { counts: Data<ItemCounts> & { reload: () => void } }) {
  const c = counts.data;
  let kinds = COUNT_KINDS.slice(0, 3);
  if (c) {
    const nonZero = COUNT_KINDS.filter((k) => (c[k.key] ?? 0) > 0);
    kinds = [...nonZero, ...COUNT_KINDS.filter((k) => !nonZero.includes(k))].slice(0, 3);
    /* Keep the board's order (Movies, Shows, Episodes) whenever those are what the server has. */
    kinds.sort((a, b) => COUNT_KINDS.indexOf(a) - COUNT_KINDS.indexOf(b));
  }
  return (
    <section aria-labelledby="ov-lib" class="srv-box">
      <div class="srv-box-head">
        <h2 id="ov-lib" class="srv-box-title">{t('Library')}</h2>
        <Link to="/admin/libraries" class="srv-box-link">{t('Libraries')}</Link>
      </div>
      <div class="srv-stats">
        {kinds.map((k) => (
          <div key={k.key} class="srv-stat">
            <span class="srv-stat-n">{c ? formatNumber(c[k.key] ?? 0) : counts.error ? '—' : <span class="skel skel-line" style={{ width: '56px', height: '24px', display: 'block', margin: '4px 0 5px' }} />}</span>
            <span class="srv-stat-label">{t(k.label)}</span>
          </div>
        ))}
      </div>
    </section>
  );
}

/* ── Storage ── */

function DiskIcon({ ram }: { ram: boolean }) {
  return (
    <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
      {ram
        ? <><path d="M7 7h10v10H7z" /><path d="M9.5 3v4M14.5 3v4M9.5 17v4M14.5 17v4" /><path d="M3 9.5h4M3 14.5h4M17 9.5h4M17 14.5h4" /></>
        : <><path d="M4 5h16v6H4z" /><path d="M4 13h16v6H4z" /><path d="M7.5 8h.01M7.5 16h.01" /></>}
    </svg>
  );
}

function StoragePanel({ list, error, onRetry }: { list?: Disk[]; error: boolean; onRetry: () => void }) {
  return (
    <section aria-labelledby="ov-storage" class="srv-box srv-storage">
      <div class="srv-box-head">
        <h2 id="ov-storage" class="srv-box-title">{t('Storage')}{list && <span> · {tn(list.length, '{n} disk', '{n} disks')}</span>}</h2>
        <Link to="/admin/settings" class="srv-box-link">{t('Change paths in Settings')}</Link>
      </div>
      {!list && error ? (
        <InlineError text={t('Couldn’t read the server’s disks.')} onRetry={onRetry} />
      ) : !list ? (
        [0, 1].map((i) => <div key={i} class="srv-disk"><span class="skel srv-disk-icon" /><span class="skel skel-line" /></div>)
      ) : list.map((d) => {
        const lvl = level(d.pct);
        const free = t('{size} free', { size: fmtDisk(d.free) });
        return (
          <div key={d.mount} class="srv-disk">
            <div class="srv-disk-name">
              <span aria-hidden="true" class="srv-disk-icon"><DiskIcon ram={d.ram} /></span>
              <span class="srv-disk-text"><span class="srv-disk-title">{d.name}</span><span class="srv-disk-mount">{t('On {mount}', { mount: d.mount })}</span></span>
            </div>
            <div class="srv-disk-use">
              <div class="srv-disk-line">
                <span>{tx('{used} of {total} used · {pct}%', { used: <b>{fmtDisk(d.used)}</b>, total: fmtDisk(d.total), pct: d.pct })}</span>
                <span class={'srv-disk-free is-' + lvl}>{free}</span>
              </div>
              <span role="meter" class="srv-disk-meter" aria-label={t('{name} disk space used', { name: d.name })} aria-valuemin={0} aria-valuemax={100} aria-valuenow={d.pct} aria-valuetext={t('{pct}% used, {free}', { pct: d.pct, free })}>
                <span class={'is-' + lvl} style={{ width: Math.min(100, d.pct) + '%' }} />
              </span>
            </div>
            {(d.tag || d.folders.length > 0) && (
              <div class="srv-disk-folders">
                {d.tag && <span class="srv-disk-tag">{d.tag}</span>}
                {d.folders.map((f) => <span key={f.label + f.path} class="srv-disk-folder"><b>{f.label}</b><span>{f.path}</span></span>)}
              </div>
            )}
          </div>
        );
      })}
      <p class="srv-storage-note">{t('Folders on the same disk share its space. A warning shows at the top of this page when a disk passes 85%.')}</p>
    </section>
  );
}

/* ── Recent activity ── */

interface Line { id: number; text: string; when: string; tone: 'muted' | 'danger' | 'pending' | 'ok' }

/* Jellyfin's English activity names, said the way the boards say them (in Seedy's language). Lines Jellyfin
   already wrote in another language don't match and show as they are. */
function describe(e: ActivityEntry): string {
  const n = e.Name.trim();
  let m: RegExpMatchArray | null;
  if ((m = n.match(/^(.+) is online from (.+)$/))) return t('{user} signed in from {device}', { user: m[1], device: m[2] });
  if ((m = n.match(/^(.+) successfully authenticated$/))) return t('{user} signed in', { user: m[1] });
  if ((m = n.match(/^(.+) has disconnected from (.+)$/))) return t('{user} went offline on {device}', { user: m[1], device: m[2] });
  if ((m = n.match(/^(.+?) is playing (.+) on (.+)$/))) return t('{user} started {title} on {device}', { user: m[1], title: m[2], device: m[3] });
  if ((m = n.match(/^(.+?) has finished playing (.+) on (.+)$/))) return t('{user} stopped {title} on {device}', { user: m[1], title: m[2], device: m[3] });
  if ((m = n.match(/^Failed login attempt from (.+)$/))) {
    const ip = e.ShortOverview?.match(/:\s*(.+)$/)?.[1];
    return ip ? t('Failed sign-in for "{user}" from {ip}', { user: m[1], ip }) : t('Failed sign-in for "{user}"', { user: m[1] });
  }
  if ((m = n.match(/^(.+) was installed$/))) return t('{name} plugin installed', { name: m[1] });
  if ((m = n.match(/^(.+) was updated$/))) return t('{name} plugin updated', { name: m[1] });
  if ((m = n.match(/^(.+) was uninstalled$/))) return t('{name} plugin removed', { name: m[1] });
  if ((m = n.match(/^(.+) installation failed$/))) return t('{name} couldn’t be installed', { name: m[1] });
  if ((m = n.match(/^User (.+) has been created$/))) return t('{name} was added as a user', { name: m[1] });
  if ((m = n.match(/^User (.+) has been deleted$/))) return t('{name} was removed as a user', { name: m[1] });
  if ((m = n.match(/^User (.+) has been locked out$/))) return t('{name} was locked out after too many failed sign-ins', { name: m[1] });
  if ((m = n.match(/^Password has been changed for user (.+)$/))) return t('{name}’s password was changed', { name: m[1] });
  if (e.Type === 'TaskFailed' && (m = n.match(/^(.+) failed$/))) return t('{task} failed', { task: sentenceCase(m[1]) });
  return n;
}

function toLines(entries: ActivityEntry[]): Line[] {
  const lines: (Line & { count: number })[] = [];
  entries.forEach((e, i) => {
    /* A sign-in logs "authenticated" and "is online" together; keep one. */
    if (e.Type === 'AuthenticationSucceeded') {
      const at = new Date(e.Date).getTime();
      const near = entries.slice(Math.max(0, i - 3), i + 4).some((o) => o.Type === 'SessionStarted' && sameId(o.UserId, e.UserId) && Math.abs(new Date(o.Date).getTime() - at) < 3000);
      if (near) return;
    }
    const tone: Line['tone'] = e.Severity === 'Error' || e.Severity === 'Critical' || e.Type === 'AuthenticationFailed' || e.Type === 'UserLockedOut'
      ? 'danger' : e.Severity === 'Warning' ? 'pending' : 'muted';
    const text = describe(e);
    const prev = lines[lines.length - 1];
    if (prev && prev.text === text.replace(/ · \d+ times$/, '') && prev.tone === tone) { prev.count++; return; }
    lines.push({ id: e.Id, text, when: activityWhen(new Date(e.Date)), tone, count: 1 });
  });
  return lines.map((l) => ({ ...l, text: l.count > 1 ? t('{text} · {n} times', { text: l.text, n: formatNumber(l.count) }) : l.text }));
}

function ActivityPanel({ activity }: { activity: Data<{ Items: ActivityEntry[] }> & { reload: () => void } }) {
  const lines = activity.data ? toLines(activity.data.Items).slice(0, 6) : [];
  return (
    <section aria-labelledby="ov-act" class="srv-box">
      <div class="srv-box-head">
        <h2 id="ov-act" class="srv-box-title">{t('Recent activity')}</h2>
        <Link to="/admin/activity" class="srv-box-link">{t('Activity log')}</Link>
      </div>
      {!activity.data && activity.error ? (
        <div class="srv-box-row"><InlineError text={t('Couldn’t load the activity log.')} onRetry={activity.reload} /></div>
      ) : !activity.data ? (
        [0, 1, 2, 3, 4].map((i) => <div key={i} class="srv-act"><span class="skel skel-line" style={{ width: `${40 + i * 8}%` }} /></div>)
      ) : lines.length === 0 ? (
        <div class="srv-act"><span class="srv-act-text is-muted">{t('Nothing has happened yet. Sign-ins, playback and changes show up here.')}</span></div>
      ) : (
        lines.map((l) => (
          <div key={l.id} class="srv-act">
            <span class={'srv-act-dot is-' + l.tone} />
            <span class="srv-act-text">{l.text}</span>
            <span class="srv-act-when">{l.when}</span>
          </div>
        ))
      )}
    </section>
  );
}

