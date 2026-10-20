import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { get, getSession } from '../../api/client';
import type { BaseItem } from '../../api/types';
import { episodeTag } from '../../lib/format';
import { getLang, t, tn, tx } from '../../lib/i18n';
import { on, send } from '../../lib/remote';
import { Link } from '../../lib/router';
import { Icon } from '../../ui/icons';
import { confirm, toast } from '../../ui/overlay';
import { AdminLayout } from '../admin/AdminLayout';
import { type ActivityEntry, errText, type FullUser, getActivity, getNetwork, getServerConfig, getUser, getUsers, saveNetwork, saveUserPolicy } from './api';
import { LoadFailed, Select, tOpts, useLoad } from './parts';
import { dayShort, monthShort } from './format';

/* ── Turning Jellyfin's log lines into the board's rows ──
   The server writes English sentences ("Alex is playing X on Y", "IP address: …"); rows are rebuilt from those, and
   anything unrecognised (another language, a plugin's own events) is shown as the server wrote it. */

type Level = 'info' | 'ok' | 'warn' | 'error';
type Category = 'people' | 'playback' | 'signin' | 'system';
const TAG: Record<Category, string> = { people: 'People', playback: 'Playback', signin: 'Sign-in', system: 'System' };
/* Levels as the CSV export writes them. */
const LEVEL_NAME: Record<Level, string> = { info: 'info', ok: 'ok', warn: 'warn', error: 'error' };

interface Row {
  key: string;
  date: Date;
  title: string;
  detail: string;
  level: Level;
  category: Category;
  userId?: string;
  userName?: string;
  ip?: string;
  action?: 'block' | 'enable';
  entry: ActivityEntry;
}

const ipOf = (e?: ActivityEntry | null) => /IP address: (.+)$/.exec(e?.ShortOverview ?? '')?.[1]?.trim();
const cleanIp = (ip: string) => ip.replace(/^::ffff:/i, '').replace(/^\[|\]$/g, '').replace(/:\d+$/, (m) => (ip.includes('.') ? '' : m));

export function isPrivateIp(raw: string): boolean {
  const ip = cleanIp(raw);
  const v4 = /^(\d+)\.(\d+)\.(\d+)\.(\d+)$/.exec(ip);
  if (v4) {
    const [a, b] = [Number(v4[1]), Number(v4[2])];
    return a === 10 || a === 127 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 169 && b === 254) || (a === 100 && b >= 64 && b <= 127);
  }
  const l = ip.toLowerCase();
  return l === '::1' || l.startsWith('fc') || l.startsWith('fd') || l.startsWith('fe80');
}

/* "3rd" / "3e". */
const ordinal = (n: number) => (getLang() === 'fr' ? `${n}${n === 1 ? 'er' : 'e'}`
  : n + (n % 100 >= 11 && n % 100 <= 13 ? 'th' : ['th', 'st', 'nd', 'rd'][n % 10] ?? 'th'));
const SAME_MOMENT = 3000;
const PLAY_TYPES = new Set(['VideoPlayback', 'AudioPlayback', 'VideoPlaybackStopped', 'AudioPlaybackStopped', 'UserDownloadingContent']);

function itemLabel(e: ActivityEntry, fallback: string, items: Map<string, BaseItem>): string {
  const it = e.ItemId ? items.get(e.ItemId) : undefined;
  if (!it) return fallback.replace(/ - /, ' · ');
  if (it.Type === 'Episode' && it.SeriesName) return `${it.SeriesName} ${episodeTag(it)}`.trim();
  return it.Name;
}

/* Rows for entries ordered newest first. Sign-ins that came with a session start (and the previous session's end)
   on the same device at the same moment become one "signed in" row. */
function buildRows(entries: ActivityEntry[], items: Map<string, BaseItem>, users: FullUser[]): Row[] {
  const rows: Row[] = [];
  const used = new Set<number>();
  const userName = (id?: string | null) => users.find((u) => u.Id === id)?.Name;
  const at = (e: ActivityEntry) => new Date(e.Date).getTime();

  entries.forEach((e, i) => {
    if (used.has(e.Id)) return;
    const date = new Date(e.Date);
    const base = { key: String(e.Id), date, entry: e, userId: e.UserId && !/^0+$/.test(e.UserId.replace(/-/g, '')) ? e.UserId : undefined };
    const ip = ipOf(e);
    let m: RegExpExecArray | null;

    if (e.Type === 'AuthenticationSucceeded') {
      const who = /^(.+?) successfully authenticated$/.exec(e.Name)?.[1] ?? userName(e.UserId) ?? e.Name;
      let device = '';
      for (let j = Math.max(0, i - 4); j < Math.min(entries.length, i + 5); j++) {
        const o = entries[j];
        if (o === e || o.UserId !== e.UserId || Math.abs(at(o) - at(e)) > SAME_MOMENT) continue;
        if (o.Type === 'SessionStarted' && !device) { device = /^.+? is online from (.+)$/.exec(o.Name)?.[1] ?? ''; used.add(o.Id); }
        else if (o.Type === 'SessionEnded') used.add(o.Id);
      }
      rows.push({ ...base, title: t('{name} signed in', { name: who }), detail: [device, ip].filter(Boolean).join(' · '), level: 'info', category: 'signin', userName: who, ip });
      return;
    }
    if (e.Type === 'AuthenticationFailed') {
      const who = /^Failed login attempt from (.+)$/.exec(e.Name)?.[1] ?? '';
      /* Attempts from the same address in the ten minutes up to this one. */
      const burst = ip ? entries.filter((o) => o.Type === 'AuthenticationFailed' && ipOf(o) === ip && at(o) <= at(e) && at(e) - at(o) <= 600_000) : [e];
      const n = burst.length;
      /* The earlier attempts of a burst are told by this row ("3rd attempt in 10 minutes"). */
      for (const o of burst) if (o !== e && /^Failed login attempt from (.+)$/.exec(o.Name)?.[1] === who) used.add(o.Id);
      const detail = ip ? (n > 1 ? t('From {ip} · {nth} attempt in 10 minutes', { ip, nth: ordinal(n) }) : t('From {ip}', { ip })) : e.ShortOverview ?? '';
      rows.push({ ...base, title: who ? t('Failed sign-in for “{name}”', { name: who }) : e.Name, detail, level: 'error', category: 'signin', userName: who, ip, action: ip && !isPrivateIp(ip) ? 'block' : undefined });
      return;
    }
    if (e.Type === 'UserLockedOut') {
      const who = /^User (.+) has been locked out$/.exec(e.Name)?.[1] ?? userName(e.UserId) ?? '';
      const fails = entries.filter((o) => o.Type === 'AuthenticationFailed' && at(o) <= at(e) && at(e) - at(o) <= 3_600_000
        && (/^Failed login attempt from (.+)$/.exec(o.Name)?.[1] ?? '').toLowerCase() === who.toLowerCase()).length;
      rows.push({ ...base, title: who ? t('{name} was locked out', { name: who }) : e.Name,
        detail: fails ? tn(fails, '{n} wrong password in a row', '{n} wrong passwords in a row') : t('Too many wrong passwords'),
        level: 'warn', category: 'signin', userName: who, action: base.userId ? 'enable' : undefined });
      return;
    }
    if (e.Type === 'SessionStarted' && (m = /^(.+?) is online from (.+)$/.exec(e.Name))) {
      /* An app that drops its connection and comes straight back logs "disconnected" and "online" together. */
      const device = m[2];
      const ended = entries.slice(Math.max(0, i - 4), i + 5).find((o) => o.Type === 'SessionEnded' && !used.has(o.Id) && o.UserId === e.UserId
        && Math.abs(at(o) - at(e)) <= SAME_MOMENT && /^.+? has disconnected from (.+)$/.exec(o.Name)?.[1] === device);
      if (ended) used.add(ended.Id);
      rows.push({ ...base, title: ended ? t('{name} reconnected', { name: m[1] }) : t('{name} came online', { name: m[1] }), detail: [device, ip].filter(Boolean).join(' · '), level: 'info', category: 'signin', userName: m[1], ip });
      return;
    }
    if (e.Type === 'SessionEnded' && (m = /^(.+?) has disconnected from (.+)$/.exec(e.Name))) {
      rows.push({ ...base, title: t('{name} went offline', { name: m[1] }), detail: [m[2], ip].filter(Boolean).join(' · '), level: 'info', category: 'signin', userName: m[1], ip });
      return;
    }
    if ((e.Type === 'VideoPlayback' || e.Type === 'AudioPlayback') && (m = /^(.+?) is playing (.+?) on (.+)$/.exec(e.Name))) {
      rows.push({ ...base, title: t('{name} started {item}', { name: m[1], item: itemLabel(e, m[2], items) }), detail: m[3], level: 'info', category: 'playback', userName: m[1] });
      return;
    }
    if ((e.Type === 'VideoPlaybackStopped' || e.Type === 'AudioPlaybackStopped') && (m = /^(.+?) has finished playing (.+?) on (.+)$/.exec(e.Name))) {
      rows.push({ ...base, title: t(e.Type === 'AudioPlaybackStopped' ? '{name} stopped listening to {item}' : '{name} stopped watching {item}', { name: m[1], item: itemLabel(e, m[2], items) }), detail: m[3], level: 'info', category: 'playback', userName: m[1] });
      return;
    }
    if (e.Type === 'UserDownloadingContent' && (m = /^(.+?) is downloading (.+)$/.exec(e.Name))) {
      const app = /^App: (.+?), Device: (.+)$/.exec(e.ShortOverview ?? '');
      rows.push({ ...base, title: t('{name} downloaded {item}', { name: m[1], item: itemLabel(e, m[2], items) }), detail: app ? `${app[2]} · ${app[1]}` : e.ShortOverview ?? '', level: 'info', category: 'playback', userName: m[1] });
      return;
    }
    if ((m = /^User (.+) has been created$/.exec(e.Name))) { rows.push({ ...base, title: t('{name} was added', { name: m[1] }), detail: t('New profile'), level: 'ok', category: 'people', userName: m[1] }); return; }
    if ((m = /^User (.+) has been deleted$/.exec(e.Name))) { rows.push({ ...base, title: t('{name} was deleted', { name: m[1] }), detail: t('Profile and its watch history removed'), level: 'warn', category: 'people', userName: m[1] }); return; }
    if ((m = /^Password has been changed for user (.+)$/.exec(e.Name))) { rows.push({ ...base, title: t('{name}’s password was changed', { name: m[1] }), detail: e.ShortOverview ?? '', level: 'info', category: 'people', userName: m[1] }); return; }

    const version = /^Version (.+)$/.exec(e.ShortOverview ?? '')?.[1];
    if (e.Type === 'PluginUpdateInstalled' && (m = /^(.+) was updated$/.exec(e.Name))) {
      rows.push({ ...base, title: version ? t('{plugin} plugin updated to {version}', { plugin: m[1], version }) : t('{plugin} plugin updated', { plugin: m[1] }),
        detail: t('Takes effect after the next restart'), level: 'info', category: 'system' });
      return;
    }
    if (e.Type === 'PluginInstalled' && (m = /^(.+) was installed$/.exec(e.Name))) {
      rows.push({ ...base, title: t('{plugin} plugin installed', { plugin: m[1] }),
        detail: [version && t('Version {version}', { version }), t('Takes effect after the next restart')].filter(Boolean).join(' · '), level: 'ok', category: 'system' });
      return;
    }
    if (e.Type === 'PluginUninstalled' && (m = /^(.+) was uninstalled$/.exec(e.Name))) {
      rows.push({ ...base, title: t('{plugin} plugin removed', { plugin: m[1] }), detail: t('Takes effect after the next restart'), level: 'info', category: 'system' });
      return;
    }
    if (e.Type === 'InstallationFailed' && (m = /^(.+) installation failed$/.exec(e.Name))) {
      rows.push({ ...base, title: t('{plugin} couldn’t be installed', { plugin: m[1] }), detail: [version && t('Version {version}', { version }), e.Overview].filter(Boolean).join(' · '), level: 'error', category: 'system' });
      return;
    }
    if (e.Type === 'TaskFailed' && (m = /^(.+) failed$/.exec(e.Name))) {
      const ran = /^Running time: ([\s\S]*)$/.exec(e.ShortOverview ?? '');
      rows.push({ ...base, title: t('{task} failed', { task: m[1] }), detail: ran ? t('Ran for {time}', { time: ran[1] }) : e.ShortOverview ?? '', level: 'error', category: 'system' });
      return;
    }
    if (e.Type === 'SubtitleDownloadFailure' || e.Type === 'LyricDownloadFailure') {
      rows.push({ ...base, title: e.Name, detail: e.ShortOverview ?? '', level: 'warn', category: 'system' });
      return;
    }

    const sev: Level = e.Severity === 'Error' || e.Severity === 'Critical' ? 'error' : e.Severity === 'Warning' ? 'warn' : 'info';
    const category: Category = PLAY_TYPES.has(e.Type) ? 'playback' : /^(Authentication|Session)/.test(e.Type) ? 'signin' : /^User/.test(e.Type) ? 'people' : 'system';
    rows.push({ ...base, title: e.Name, detail: e.ShortOverview ?? e.Overview ?? '', level: sev, category, ip });
  });
  return collapse(rows);
}

/* Repeats close together ("Sam reconnected" five times in ten minutes) read as one row with a count. */
function collapse(rows: Row[]): Row[] {
  const out: (Row & { times?: number; last?: Date })[] = [];
  for (const r of rows) {
    const prev = out[out.length - 1];
    if (prev && !r.action && !prev.action && prev.title === r.title && prev.detail === r.detail && prev.category === r.category
      && (prev.last ?? prev.date).getTime() - r.date.getTime() <= 30 * 60_000 && startOfDay(prev.date).getTime() === startOfDay(r.date).getTime()) {
      prev.times = (prev.times ?? 1) + 1;
      prev.last = r.date;
      continue;
    }
    out.push({ ...r });
  }
  return out.map((r) => (r.times
    ? { ...r, detail: [r.detail, hm(r.last!) !== hm(r.date) ? t('{n} times since {time}', { n: r.times, time: hm(r.last!) }) : t('{n} times', { n: r.times })].filter(Boolean).join(' · ') }
    : r));
}

/* ── Filters ── */

type TypeFilter = 'all' | Category;
const TYPE_TABS: { id: TypeFilter; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'people', label: 'People' },
  { id: 'playback', label: 'Playback' },
  { id: 'signin', label: 'Sign-ins' },
  { id: 'system', label: 'System' }
];
type LevelFilter = 'all' | 'warn' | 'error';
const LEVELS: { value: LevelFilter; label: string }[] = [
  { value: 'all', label: 'All levels' },
  { value: 'warn', label: 'Warnings and errors' },
  { value: 'error', label: 'Errors only' }
];

function matches(r: Row, type: TypeFilter, level: LevelFilter, person: FullUser | null): boolean {
  if (type !== 'all' && r.category !== type) return false;
  if (level === 'error' && r.level !== 'error') return false;
  if (level === 'warn' && r.level !== 'error' && r.level !== 'warn') return false;
  if (person && r.userId !== person.Id && (r.userName ?? '').toLowerCase() !== person.Name.toLowerCase()) return false;
  return true;
}

/* ── Day labels: "Today · Mon 19 Oct", "Yesterday · Sun 18 Oct", "Fri 16 Oct" ── */

const startOfDay = (d: Date) => { const x = new Date(d); x.setHours(0, 0, 0, 0); return x; };
function dayLabel(d: Date): string {
  const wd = `${dayShort(d)} ${d.getDate()} ${monthShort(d)}${d.getFullYear() !== new Date().getFullYear() ? ' ' + d.getFullYear() : ''}`;
  const diff = Math.round((startOfDay(new Date()).getTime() - startOfDay(d).getTime()) / 86_400_000);
  return diff === 0 ? t('Today · {date}', { date: wd }) : diff === 1 ? t('Yesterday · {date}', { date: wd }) : wd;
}
const hm = (d: Date) => `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;

/* ── Page ── */

const PAGE = 100;

export function ServerActivity() {
  const cfg = useLoad(getServerConfig);
  const users = useLoad(getUsers);
  const [entries, setEntries] = useState<ActivityEntry[] | null>(null);
  const [total, setTotal] = useState(0);
  const [error, setError] = useState<Error | null>(null);
  const [today, setToday] = useState<ActivityEntry[] | null>(null);
  const [items, setItems] = useState<Map<string, BaseItem>>(new Map());
  const [loadingMore, setLoadingMore] = useState(false);
  const [live, setLive] = useState(true);
  const [type, setType] = useState<TypeFilter>('all');
  const [level, setLevel] = useState<LevelFilter>('all');
  const [personId, setPersonId] = useState('all');
  const [fresh, setFresh] = useState<Set<number>>(new Set());
  const [exporting, setExporting] = useState(false);
  const entriesRef = useRef<ActivityEntry[] | null>(null);
  entriesRef.current = entries;

  const userList = users.data ?? [];
  const person = userList.find((u) => u.Id === personId) ?? null;

  /* Item names for playback rows ("Glass Harbor S1 E6"), fetched once per batch of entries. */
  const fetchItems = (list: ActivityEntry[]) => {
    const ids = [...new Set(list.filter((e) => e.ItemId && PLAY_TYPES.has(e.Type)).map((e) => e.ItemId!))].filter((id) => !items.has(id));
    if (!ids.length) return;
    const uid = getSession()?.userId;
    for (let i = 0; i < ids.length; i += 60) {
      get<{ Items: BaseItem[] }>('/Items', { ids: ids.slice(i, i + 60), userId: uid, enableImages: false, enableUserData: false, fields: ['SeriesName'] }).then(
        (r) => setItems((m) => { const n = new Map(m); for (const it of r.Items) n.set(it.Id, it); return n; }),
        () => undefined
      );
    }
  };

  const loadToday = () => {
    const minDate = startOfDay(new Date()).toISOString();
    getActivity({ minDate, limit: 2000 }).then((r) => setToday(r.Items), () => setToday((x) => x ?? []));
  };

  const loadFirst = () => {
    setError(null);
    getActivity({ startIndex: 0, limit: PAGE }).then(
      (r) => { setEntries(r.Items); setTotal(r.TotalRecordCount); fetchItems(r.Items); },
      (e: Error) => setError(e)
    );
    loadToday();
  };
  useEffect(loadFirst, []);

  /* New entries since the newest one shown: prepended, briefly highlighted. */
  const refresh = () => {
    const cur = entriesRef.current;
    if (!cur) return;
    getActivity({ startIndex: 0, limit: 50 }).then((r) => {
      const known = new Set(cur.map((e) => e.Id));
      const added = r.Items.filter((e) => !known.has(e.Id));
      if (!added.length) return;
      setEntries((list) => [...added, ...(list ?? [])]);
      setTotal(r.TotalRecordCount);
      setFresh(new Set(added.map((e) => e.Id)));
      fetchItems(added);
      loadToday();
    }, () => undefined);
  };

  useEffect(() => {
    if (!live) return;
    const off = on('ActivityLogEntry', () => refresh());
    const offOpen = on('open', () => send('ActivityLogEntryStart', '0,1500'));
    send('ActivityLogEntryStart', '0,1500');
    const timer = window.setInterval(refresh, 10_000);
    return () => { off(); offOpen(); send('ActivityLogEntryStop'); window.clearInterval(timer); };
  }, [live]);

  const loadMore = async () => {
    if (!entries) return;
    setLoadingMore(true);
    try {
      /* Keep fetching pages until there's something new to show for the current filters (or the log ends). */
      let list = entries;
      let tot = total;
      const before = buildRows(list, items, userList).filter((r) => matches(r, type, level, person)).length;
      for (let n = 0; n < 6 && list.length < tot; n++) {
        const r = await getActivity({ startIndex: list.length, limit: PAGE });
        const known = new Set(list.map((e) => e.Id));
        list = [...list, ...r.Items.filter((e) => !known.has(e.Id))];
        tot = r.TotalRecordCount;
        fetchItems(r.Items);
        if (!r.Items.length) { tot = list.length; break; }
        if (buildRows(list, items, userList).filter((x) => matches(x, type, level, person)).length - before >= 20) break;
      }
      setEntries(list);
      setTotal(tot);
    } catch (e) {
      toast(errText(e, t('Couldn’t load earlier activity.')), 'error');
    } finally {
      setLoadingMore(false);
    }
  };

  const rows = useMemo(() => (entries ? buildRows(entries, items, userList) : []), [entries, items, userList]);
  const shown = useMemo(() => rows.filter((r) => matches(r, type, level, person)), [rows, type, level, person]);
  const groups = useMemo(() => {
    const out: { key: string; label: string; rows: Row[] }[] = [];
    for (const r of shown) {
      const k = startOfDay(r.date).toISOString();
      const g = out[out.length - 1];
      if (g && g.key === k) g.rows.push(r);
      else out.push({ key: k, label: dayLabel(r.date), rows: [r] });
    }
    return out;
  }, [shown]);

  /* Today's tiles. */
  const stats = useMemo(() => {
    if (!today) return null;
    const plays = today.filter((e) => e.Type === 'VideoPlayback' || e.Type === 'AudioPlayback').length;
    const signins = today.filter((e) => e.Type === 'AuthenticationSucceeded').length;
    const failed = today.filter((e) => e.Type === 'AuthenticationFailed');
    const addresses = new Set(failed.map((e) => ipOf(e) ?? '?')).size;
    const errors = today.filter((e) => (e.Severity === 'Error' || e.Severity === 'Critical') && e.Type !== 'AuthenticationFailed' && e.Type !== 'UserLockedOut').length;
    return { plays, signins, failed: failed.length, addresses, errors };
  }, [today]);

  const failedOnly = type === 'signin' && level === 'error';

  const blockAddress = async (r: Row) => {
    const ip = r.ip ? cleanIp(r.ip) : '';
    if (!ip) return;
    const ok = await confirm({
      title: t('Block {ip}?', { ip }),
      text: t('Connections from {ip} are refused from now on. It only affects connections from outside your home network, and you can undo it in Networking under Remote access.', { ip }),
      action: t('Block address'),
      danger: true
    });
    if (!ok) return;
    try {
      const net = await getNetwork();
      const list = net.RemoteIPFilter ?? [];
      let next: typeof net;
      if (net.IsRemoteIPFilterBlacklist || list.length === 0) {
        if (net.IsRemoteIPFilterBlacklist && list.includes(ip)) { toast(t('{ip} is already blocked', { ip })); return; }
        next = { ...net, IsRemoteIPFilterBlacklist: true, RemoteIPFilter: [...(net.IsRemoteIPFilterBlacklist ? list : []), ip] };
      } else {
        /* An allow list is in use: blocking means taking the address off it. */
        if (!list.includes(ip)) { toast(t('Only listed addresses can connect, so {ip} is already refused', { ip })); return; }
        /* An empty allow list lets every address in, so the last entry can't be taken off here. */
        if (list.length === 1) {
          toast(t('{ip} is the only address allowed to connect. Removing it would let every address in; change the list in Networking instead.', { ip }), 'error', 8000);
          return;
        }
        next = { ...net, RemoteIPFilter: list.filter((x) => x !== ip) };
      }
      if (import.meta.env.DEV) console.info('[seedy] POST /System/Configuration/network', JSON.stringify(next));
      await saveNetwork(next);
      toast(t('{ip} is blocked', { ip }));
    } catch (e) {
      toast(errText(e, t('Couldn’t block that address.')), 'error');
    }
  };

  const reenable = async (r: Row) => {
    if (!r.userId) return;
    const name = r.userName || t('This person');
    const ok = await confirm({
      title: t('Let {name} sign in again?', { name }),
      text: t('{name} was locked out after too many wrong passwords. Re-enabling lets them try again straight away.', { name }),
      action: t('Re-enable')
    });
    if (!ok) return;
    try {
      const u = await getUser(r.userId);
      if (!u.Policy?.IsDisabled) { toast(t('{name} can already sign in', { name })); return; }
      await saveUserPolicy(r.userId, { ...u.Policy, IsDisabled: false });
      toast(t('{name} can sign in again', { name }));
    } catch (e) {
      toast(errText(e, t('Couldn’t re-enable that profile.')), 'error');
    }
  };

  const exportCsv = async () => {
    setExporting(true);
    try {
      let all = entries ?? [];
      let tot = total;
      while (all.length < tot && all.length < 20_000) {
        const r = await getActivity({ startIndex: all.length, limit: 500 });
        if (!r.Items.length) break;
        const known = new Set(all.map((e) => e.Id));
        all = [...all, ...r.Items.filter((e) => !known.has(e.Id))];
        tot = r.TotalRecordCount;
      }
      const out = buildRows(all, items, userList).filter((x) => matches(x, type, level, person));
      const esc = (v: string) => (/[",\n\r]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
      const lines = [['Date', 'Time', 'Type', 'Level', 'What happened', 'Details', 'Person', 'Server message'].map((h) => esc(t(h))).join(',')];
      for (const r of out) {
        const d = r.date;
        const day = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
        lines.push([day, hm(d), t(TAG[r.category]), t(LEVEL_NAME[r.level]), r.title, r.detail, r.userName ?? '', r.entry.Name].map((v) => esc(String(v ?? ''))).join(','));
      }
      const blob = new Blob(['﻿' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
      const a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = `activity-log-${new Date().toISOString().slice(0, 10)}.csv`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      window.setTimeout(() => URL.revokeObjectURL(a.href), 2000);
      toast(tn(out.length, 'Exported {n} entry', 'Exported {n} entries'));
    } catch (e) {
      toast(errText(e, t('Couldn’t export the log.')), 'error');
    } finally {
      setExporting(false);
    }
  };

  const days = cfg.data?.ActivityLogRetentionDays;
  const settingsLink = <Link to="/admin/settings" class="srv-quiet-link">{t('Settings')}</Link>;
  const lede = days
    ? tx('Sign-ins, playback, changes and server events. Kept for {days}; change that in {settings}.', { days: tn(days, '{n} day', '{n} days'), settings: settingsLink })
    : tx('Sign-ins, playback, changes and server events. Kept until you clear it; change that in {settings}.', { settings: settingsLink });

  return (
    <AdminLayout
      page="activity"
      wide
      title={t('Activity log')}
      lede={<>{lede}</>}
      actions={(
        <>
          <button type="button" class="srv-live" aria-pressed={live ? 'true' : 'false'} title={live ? t('New activity appears as it happens. Click to pause.') : t('Paused. Click to show new activity as it happens.')}
            onClick={() => { setLive((l) => !l); if (!live) refresh(); }}>
            <span class={'dot' + (live ? '' : ' is-muted')} />{live ? t('Live') : t('Paused')}
          </button>
          <button type="button" class="btn btn-secondary" disabled={exporting || !entries} onClick={() => void exportCsv()}>
            <Icon name="download" size={16} />{exporting ? t('Exporting…') : t('Export CSV')}
          </button>
        </>
      )}
    >
      <span class="srv-title16" hidden />
      <div class="srv-tiles" aria-label={t('Today')}>
        <Tile value={stats?.plays} label={t('Plays today')} />
        <Tile value={stats?.signins} label={t('Sign-ins today')} />
        {stats && stats.failed > 0 ? (
          <button type="button" class="srv-tile is-alert" aria-pressed={failedOnly ? 'true' : 'false'}
            onClick={() => { if (failedOnly) { setType('all'); setLevel('all'); } else { setType('signin'); setLevel('error'); } }}>
            <span class="srv-tile-value">{stats.failed}</span>
            <span class="srv-tile-label">{tn(stats.addresses, 'Failed sign-ins, {n} address', 'Failed sign-ins, {n} addresses')}</span>
          </button>
        ) : <Tile value={stats?.failed} label={t('Failed sign-ins')} />}
        <Tile value={stats?.errors} label={t('Errors today')} />
      </div>

      <div class="srv-toolbar">
        <nav aria-label={t('Filter by type')} class="srv-filters">
          {TYPE_TABS.map((tab) => (
            <button key={tab.id} type="button" class="tab" aria-current={type === tab.id ? 'page' : undefined} onClick={() => setType(tab.id)}>{t(tab.label)}</button>
          ))}
        </nav>
        <div class="srv-selects">
          <Select label={t('Person')} minWidth={150} value={personId}
            options={[{ value: 'all', label: t('Everyone') }, ...userList.map((u) => ({ value: u.Id, label: u.Name }))]} onChange={setPersonId} />
          <Select label={t('Level')} minWidth={170} value={level} options={tOpts(LEVELS)} onChange={setLevel} />
        </div>
      </div>

      {error && !entries ? (
        <LoadFailed error={error} title={t('Couldn’t load the activity log')} onRetry={loadFirst} />
      ) : !entries ? (
        <div class="srv-events-skel" aria-hidden="true">
          <span class="skel skel-line" style={{ width: '160px', height: '14px' }} />
          {Array.from({ length: 5 }, (_, i) => <span key={i} class="skel" />)}
        </div>
      ) : groups.length === 0 ? (
        <div class="empty">
          <span class="empty-icon"><Icon name="history" size={26} /></span>
          <div class="empty-body">
            <span class="empty-title">{rows.length ? t('Nothing matches') : t('Nothing logged yet')}</span>
            <span class="empty-text">{rows.length ? (entries.length < total ? t('Nothing in the most recent activity matches these filters. Load earlier activity, or change the filters.') : t('Try another type, person or level.')) : t('Sign-ins, playback and server events show up here as they happen.')}</span>
          </div>
        </div>
      ) : (
        groups.map((g) => (
          <section key={g.key} class="srv-day" aria-label={g.label}>
            <h2 class="srv-day-label">{g.label}</h2>
            <div class="srv-events">
              {g.rows.map((r) => (
                <div key={r.key} class={'srv-event' + (r.level === 'error' ? ' is-error' : '') + (fresh.has(r.entry.Id) ? ' srv-event-new' : '')}>
                  <span class="srv-event-time">{hm(r.date)}</span>
                  <span class={'srv-event-dot is-' + r.level} role="img" aria-label={r.level === 'error' ? t('Error') : r.level === 'warn' ? t('Warning') : r.level === 'ok' ? t('Done') : t('Information')} />
                  <span class="srv-event-text">
                    <span class="srv-event-title">{r.title}</span>
                    {r.detail && <span class="srv-event-detail">{r.detail}</span>}
                  </span>
                  <span class="srv-event-tag">{t(TAG[r.category])}</span>
                  {r.action === 'block' && <button type="button" class="btn btn-secondary is-xxs" onClick={() => void blockAddress(r)}>{t('Block address')}</button>}
                  {r.action === 'enable' && <button type="button" class="btn btn-secondary is-xxs" onClick={() => void reenable(r)}>{t('Re-enable')}</button>}
                </div>
              ))}
            </div>
          </section>
        ))
      )}

      {entries && entries.length < total && (
        <div class="srv-more">
          <button type="button" class="btn btn-secondary srv-btn-20" disabled={loadingMore} onClick={() => void loadMore()}>{loadingMore ? t('Loading…') : t('Show earlier')}</button>
        </div>
      )}
    </AdminLayout>
  );
}

function Tile({ value, label }: { value?: number; label: string }) {
  return (
    <div class="srv-tile">
      {value === undefined ? <span class="skel skel-line" /> : <span class="srv-tile-value">{value}</span>}
      <span class="srv-tile-label">{label}</span>
    </div>
  );
}
