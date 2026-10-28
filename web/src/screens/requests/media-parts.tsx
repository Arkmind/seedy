import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import {
  mediaApprove, mediaDecline, mediaList, mediaRemove, mediaSearchNow, mediaAcceptLower, mediaSummary,
  type BookFormat, type CardStatus, type MediaRequest, type MediaService, type MediaSummary
} from '../../api/media';
import { daysFromToday, fmtDate, fmtWeekdayDate } from '../../lib/format';
import { t, tn } from '../../lib/i18n';
import { Link } from '../../lib/router';
import { createStore, useAsync, useStore } from '../../lib/store';
import { Icon } from '../../ui/icons';
import { confirm, toast } from '../../ui/overlay';
import './media.css';

/* Shared pieces of the Music and Books request pages (RequestsMusic, RequestsBooks, RequestsMine boards): covers with
   the boards' art when there's no image, request wording, state chips and actions. */

/* ── Covers ── */

const CP = [
  ['#1c2a3a', '#2f4a66', '#f2c47e', '#e8eef4'], ['#3a1d2e', '#6b2f4f', '#ff9fbf', '#fbe4f3'], ['#13302b', '#1f5a4d', '#9fe0c8', '#def4f1'],
  ['#2b2118', '#5c3b22', '#f0a35e', '#f7ecd5'], ['#1d1d2e', '#3b3b6b', '#b9b3ff', '#efe6fb'], ['#262626', '#444444', '#f0f0f0', '#ffffff'],
  ['#36200f', '#a3471f', '#ffd27a', '#fff2d8'], ['#0f2233', '#1b6f8f', '#9fe9ff', '#e6fbff'], ['#2a1035', '#7a3fd1', '#ffb3e6', '#fbe4f3']
];
const CPATH = ['M0 140 Q50 110 100 140 T200 140 V200 H0Z', 'M0 200 L200 70 V200Z', 'M0 150 L50 110 L90 140 L140 90 L200 130 V200 H0Z', 'M0 0 H200 V40 Q100 70 0 40Z'];
const BP = [
  ['#e8dcc8', '#c9583e', '#2c3a49', '#1e1e1e'], ['#1f3a3c', '#a8e6df', '#0f1c1d', '#def4f1'], ['#2f2840', '#e3c4ff', '#16121f', '#efe6fb'],
  ['#f2cf86', '#1d170f', '#5c4a2c', '#1d170f'], ['#3d2424', '#f0b9a0', '#1c1111', '#f6e3da'], ['#dfe7ef', '#2f4a7a', '#9fb2c6', '#141b22'],
  ['#26362b', '#d9e3b5', '#121a14', '#eef2e0'], ['#141414', '#ff9fff', '#2a2a2a', '#f0f0f0']
];
const BPATH = ['M0 300 L0 210 Q100 170 200 210 V300Z', 'M0 300 L200 160 V300Z', 'M0 230 L50 190 L100 220 L150 170 L200 200 V300 H0Z', 'M0 300 V240 H200 V300Z'];

export function hash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return Math.abs(h);
}

/* An image laid over the drawn art; the art shows while it loads, and stays when there's no image. */
function Img({ src, onFail }: { src?: string | null; onFail?: () => void }) {
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [src]);
  if (!src || failed) return null;
  return <img class="reqs-art-img" src={src} alt="" loading="lazy" decoding="async" onError={() => { setFailed(true); onFail?.(); }} />;
}

/* Square album art: the Cover Art Archive's image, or the boards' drawn cover with the title on it. */
export function AlbumArt({ seed, src, text, round, class: cls }: { seed: string; src?: string | null; text?: string; round?: boolean; class?: string }) {
  const i = hash(seed);
  const c = CP[i % CP.length];
  const s = i % 4;
  const by = s === 1 ? 120 : s === 3 ? 0 : 200;
  const bh = s === 1 ? 80 : s === 3 ? 30 : 0;
  const cx = [70, 130, 100, 60][s], cy = [80, 70, 95, 120][s], r = [46, 30, 58, 22][s], r2 = [70, 52, 82, 40][s];
  const sw = [3, 8, 2, 6][s], ro = [0.5, 0.35, 0.6, 0.4][s], po = [0.9, 0.7, 0.85, 0.6][s];
  return (
    <span class={'reqs-art' + (round ? ' is-round' : '') + (cls ? ' ' + cls : '')} aria-hidden="true" style={{ background: c[0] }}>
      <svg viewBox="0 0 200 200" preserveAspectRatio="xMidYMid slice">
        <rect width="200" height="200" fill={c[0]} />
        <rect y={by} width="200" height={bh} fill={c[1]} />
        {!round && <circle cx={cx} cy={cy} r={r} fill={c[2]} />}
        {!round && <circle cx={cx} cy={cy} r={r2} fill="none" stroke={c[3]} stroke-width={sw} opacity={ro} />}
        {!round && <path d={CPATH[(i + 1) % 4]} fill={c[1]} opacity={po} />}
      </svg>
      {text && <span class={round ? 'reqs-art-initials' : 'reqs-art-text'} style={{ color: c[3] }}>{text}</span>}
      <Img src={src} />
    </span>
  );
}

/* A 2:3 book cover: the image, or the boards' drawn cover with title and author. */
export function BookArt({ seed, src, title, author, size = 'card', class: cls }: { seed: string; src?: string | null; title: string; author?: string; size?: 'card' | 'thumb' | 'dialog'; class?: string }) {
  const i = hash(seed);
  const c = BP[i % BP.length];
  const s = i % 4;
  return (
    <span class={'reqs-book is-' + size + (cls ? ' ' + cls : '')} aria-hidden="true" style={{ background: c[0] }}>
      <svg viewBox="0 0 200 300" preserveAspectRatio="xMidYMid slice">
        <circle cx={[140, 60, 100, 150][s]} cy={[150, 170, 130, 120][s]} r={[44, 60, 36, 50][s]} fill={c[1]} />
        <path d={BPATH[s]} fill={c[2]} />
      </svg>
      <span class="reqs-book-title" style={{ color: c[3] }}>{title}</span>
      {author && <span class="reqs-book-author" style={{ color: c[3] }}>{author}</span>}
      <Img src={src} />
    </span>
  );
}

/* Initials for an artist without a picture: "Mira Sol" → "MS". */
export const initials = (name: string) => name.split(/\s+/).filter((w) => /[\p{L}\p{N}]/u.test(w)).slice(0, 2).map((w) => w[0]!.toUpperCase()).join('') || '?';

/* The thumbnail a request row shows: album square or book. */
export function RequestThumb({ r, size = 'row' }: { r: MediaRequest; size?: 'row' | 'mine' }) {
  if (r.kind === 'music') return <AlbumArt seed={r.albums[0]?.mbid ?? r.foreignId} src={r.coverUrl} class={size === 'mine' ? 'reqs-thumb-album is-60' : 'reqs-thumb-album'} />;
  return <span class={'reqs-thumb-book' + (size === 'mine' ? ' is-mine' : '')}><BookArt seed={r.foreignId} src={r.coverUrl} title={r.title} author={r.subtitle} size="thumb" /></span>;
}

/* ── Wording ── */

export const SERVICE_NAMES: Record<MediaService, string> = { lidarr: 'Lidarr', shelfarr: 'Shelfarr', librarr: 'Librarr', readarr: 'Readarr' };

/* "Mira Sol · whole discography", "Blue Hour (Deluxe) · Lumen Fields", "Lantern Season · audiobook". */
export function mediaName(r: MediaRequest): string {
  if (r.kind === 'music') {
    if (r.mode === 'artist') return t('{artist} · whole discography', { artist: r.title });
    if (r.mode === 'future') return t('{artist} · new releases', { artist: r.title });
    if (r.albums.length === 1) return `${r.albums[0]!.title} · ${r.title}`;
    return tn(r.albums.length, '{artist} · {n} album', '{artist} · {n} albums', { artist: r.title });
  }
  if (r.formats.length === 1 && r.formats[0] === 'audiobook') return t('{title} · audiobook', { title: r.title });
  return r.title;
}

export function formatsLabel(formats: BookFormat[], content?: string): string {
  if (content === 'comic' || formats.includes('comic')) return t('Comic');
  if (formats.includes('ebook') && formats.includes('audiobook')) return t('Ebook and audiobook');
  return formats[0] === 'audiobook' ? t('Audiobook') : t('Ebook');
}

/* "Artist · 5 albums", "Artist, future albums only", "Album", "Ebook and audiobook". */
export function mediaKindLine(r: MediaRequest): string {
  if (r.kind === 'book') return formatsLabel(r.formats, r.content);
  if (r.mode === 'artist') return t('Artist') + ' · ' + tn(r.units, '{n} album', '{n} albums');
  if (r.mode === 'future') return t('Artist, future albums only');
  return r.albums.length === 1 ? t('Album') : tn(r.albums.length, '{n} album', '{n} albums');
}

const ACTS = {
  asked: ['asked today', 'asked yesterday', 'asked {date}'],
  approved: ['approved today', 'approved yesterday', 'approved {date}'],
  askedBy: ['asked by {name} today', 'asked by {name} yesterday', 'asked by {name} {date}']
} as const;

export function actLine(act: keyof typeof ACTS, iso: string | undefined, name = ''): string {
  const d = iso ? new Date(iso) : undefined;
  if (!d || Number.isNaN(d.getTime())) return '';
  const ago = -daysFromToday(d);
  const date = d.getFullYear() === new Date().getFullYear() ? fmtWeekdayDate(d) : fmtDate(d);
  return t(ACTS[act][ago <= 0 ? 0 : ago === 1 ? 1 : 2], { date, name });
}

const who = (r: MediaRequest) => (r.requestedByMe ? t('you') : r.requestedBy || t('someone'));

/* The line under a request on Discover: what, when (approved while it's on its way), and the app for books. */
export function mediaWhat(r: MediaRequest, everyone: boolean): string {
  const parts = [mediaKindLine(r)];
  if (everyone) parts.push(actLine('askedBy', r.requestedAt, who(r)));
  else if (r.decidedAt && ['approved', 'searching', 'downloading', 'failed'].includes(r.status)) parts.push(actLine('approved', r.decidedAt));
  else parts.push(actLine('asked', r.requestedAt));
  if (r.kind === 'book' && !everyone) parts.push(SERVICE_NAMES[r.service]);
  return parts.filter(Boolean).join(' · ');
}

export type Tone = 'ok' | 'pending' | 'accent' | 'muted' | 'danger';
export interface ChipState { text: string; tone: Tone; fraction?: number }

const pctOf = (f?: number | null) => Math.round(Math.max(0, Math.min(1, f ?? 0)) * 100);
const qualityWord = (q?: string) => (!q ? t('good') : q === 'lossless' ? t('lossless') : q);

/* Where a request is now, for its chip. */
export function mediaChip(r: MediaRequest): ChipState {
  const n = r.note;
  switch (r.status) {
    case 'ready': return { text: t('On the server'), tone: 'ok' };
    case 'pending': return { text: t('Waiting for approval'), tone: 'pending' };
    case 'declined': return { text: t('Declined'), tone: 'danger' };
    case 'failed': return { text: t('Needs a look'), tone: 'danger' };
    case 'downloading':
      if (n?.code === 'scanning') return { text: t('Adding to the library'), tone: 'accent', fraction: 1 };
      if (n?.code === 'albums' && (n.total ?? 0) > 1) return { text: t('Downloading · {n} of {total} albums', { n: n.n ?? 0, total: n.total ?? 0 }), tone: 'accent', fraction: r.progress ?? undefined };
      return { text: t('Downloading · {p}%', { p: pctOf(r.progress) }), tone: 'accent', fraction: r.progress ?? undefined };
    case 'searching':
      if (n?.code === 'noCopy') return { text: t('Searching · no {quality} copy yet', { quality: qualityWord(n.name) }), tone: 'muted' };
      if (n?.code === 'found' && n.n) return { text: tn(n.n, 'Searching · {n} release found', 'Searching · {n} releases found'), tone: 'muted' };
      if (n?.code === 'notFound') return { text: t('Searching · nothing found yet'), tone: 'muted' };
      return { text: t('Searching'), tone: 'muted' };
    default:
      if (n?.code === 'unreachable') return { text: t('Waiting for {service}', { service: n.name ?? '' }), tone: 'muted' };
      if (n?.code === 'future') return { text: t('Watching for new releases'), tone: 'muted' };
      return { text: t('Approved'), tone: 'muted' };
  }
}

/* The extra line on My requests, in the tone it's shown in. */
export function mediaNote(r: MediaRequest): { text: string; tone: Tone | 'light' } | null {
  const n = r.note;
  if (r.status === 'pending') return { text: t('Waiting for an admin'), tone: 'pending' };
  if (r.status === 'declined') return { text: t('Declined by an admin'), tone: 'danger' };
  if (!n) return r.status === 'downloading' ? { text: t('Downloading · {p}%', { p: pctOf(r.progress) }), tone: 'accent' } : null;
  switch (n.code) {
    case 'albums':
      if (r.status !== 'downloading') return { text: t('{n} of {total} albums imported', { n: n.n ?? 0, total: n.total ?? 0 }), tone: 'light' };
      return {
        text: n.name
          ? t('{n} of {total} albums imported · {album} at {p}%', { n: n.n ?? 0, total: n.total ?? 0, album: n.name, p: pctOf(n.fraction) })
          : t('{n} of {total} albums imported', { n: n.n ?? 0, total: n.total ?? 0 }),
        tone: 'accent'
      };
    case 'noCopy': return { text: tn(n.days ?? 0, 'No {quality} copy after {n} day · Lidarr retries every 6 hours', 'No {quality} copy after {n} days · Lidarr retries every 6 hours', { quality: qualityWord(n.name) }), tone: 'danger' };
    case 'found': return { text: tn(n.n ?? 0, '{service} found {n} release · picking the best', '{service} found {n} releases · picking the best', { service: n.name ?? '' }), tone: 'light' };
    case 'notFound': return { text: t('{service} found nothing yet · it keeps looking', { service: n.name ?? '' }), tone: 'danger' };
    case 'purchase': return { text: t('{service} only found it for sale', { service: n.name ?? '' }), tone: 'danger' };
    case 'service': return { text: n.text ?? '', tone: r.attention ? 'danger' : 'light' };
    case 'unreachable': return { text: t('Couldn’t reach {service} · Seedy tries again every minute', { service: n.name ?? '' }), tone: 'danger' };
    case 'future': return { text: t('Lidarr grabs new albums when they come out'), tone: 'light' };
    case 'scanning': return { text: t('Adding it to the library'), tone: 'accent' };
    case 'gone': return { text: t('{service} no longer has this request', { service: n.name ?? '' }), tone: 'danger' };
    default: return null;
  }
}

export function Chip({ state }: { state: ChipState }) {
  return <span class={'reqs-chip is-' + state.tone}><span class="reqs-chip-dot" aria-hidden="true" />{state.text}</span>;
}

/* "On the server", "Requested", "Waiting", "Downloading" on covers. */
export function cardStatus(status: CardStatus, requestedBy?: string | null, short = false): { text: string; dot: string } | null {
  switch (status) {
    case 'server': return { text: t('On the server'), dot: 'var(--seedy-ok)' };
    case 'waiting': return { text: short ? t('Waiting') : requestedBy ? t('Requested by {name}', { name: requestedBy }) : t('Waiting for approval'), dot: 'var(--seedy-pending)' };
    case 'downloading': return { text: t('Downloading'), dot: 'var(--seedy-accent)' };
    case 'requested': return { text: requestedBy && !short ? t('Requested by {name}', { name: requestedBy }) : t('Requested'), dot: 'var(--seedy-pending)' };
    default: return null;
  }
}

/* Requests made, approved or dropped since the page loaded. Cards follow them at once (Requested, Waiting, or Request
   again) and "Your requests" reloads, without fetching every row again. Keyed by a book's work id, an album's id, or
   "artist:" + an artist's id for a whole discography. */
const askedStore = createStore<{ n: number; cards: Record<string, { status: CardStatus; by?: string | null }> }>({ n: 0, cards: {} });

export function noteRequest(r: MediaRequest, next: MediaRequest | null = r) {
  const status: CardStatus = !next || next.status === 'declined' || next.status === 'cancelled' ? 'none'
    : next.status === 'pending' ? 'waiting' : next.status === 'downloading' ? 'downloading' : 'requested';
  const keys = r.kind === 'book' ? [r.foreignId] : r.mode === 'artist' ? ['artist:' + r.foreignId] : r.albums.map((a) => a.mbid);
  const card = { status, by: r.requestedByMe ? null : r.requestedBy };
  askedStore.set((s) => ({ n: s.n + 1, cards: { ...s.cards, ...Object.fromEntries(keys.map((k) => [k, card])) } }));
}

/* A card as it stands after this page's requests; what's on the server stays so. */
export function useAsked<T extends { status: CardStatus; requestedBy?: string | null }>(card: T, ...keys: (string | undefined)[]): T {
  const { cards } = useStore(askedStore);
  const hit = keys.map((k) => (k ? cards[k] : undefined)).find(Boolean);
  return hit && card.status !== 'server' ? { ...card, status: hit.status, requestedBy: hit.by } : card;
}

/* Where a finished request opens: the reader for ebooks and comics, the title's page otherwise. */
export function readyAction(r: MediaRequest): { label: string; to: string } | null {
  if (r.status !== 'ready' || !r.itemId) return null;
  if (r.kind === 'book' && (r.formats.includes('ebook') || r.formats.includes('comic'))) return { label: t('Read'), to: `/read/${r.itemId}` };
  return { label: t('Play'), to: `/details/${r.itemId}` };
}

/* ── Rows on Discover ── */

/* Discover · Your requests. */
export function MediaMineRow({ r }: { r: MediaRequest }) {
  const state = mediaChip(r);
  const ready = readyAction(r);
  const body = (
    <>
      <RequestThumb r={r} />
      <span class="reqs-text">
        <span class="reqs-name">{mediaName(r)}</span>
        <span class="reqs-what">{mediaWhat(r, false)}</span>
        {state.fraction !== undefined && <span class="reqs-bar-line" aria-hidden="true"><i style={{ width: `${pctOf(state.fraction)}%` }} /></span>}
      </span>
      <Chip state={state} />
    </>
  );
  const cls = 'reqs-row is-media is-' + r.kind;
  return ready ? <Link to={ready.to} class={cls}>{body}</Link> : <Link to={`/requests/my?type=${r.kind === 'music' ? 'music' : 'books'}`} class={cls}>{body}</Link>;
}

/* Discover · Waiting for your approval. */
export function MediaApprovalRow({ r, actions }: { r: MediaRequest; actions: MediaActions }) {
  const p = { title: mediaName(r), name: who(r) };
  const busy = actions.busy(r);
  return (
    <div class={'reqs-row is-approval is-media is-' + r.kind}>
      <RequestThumb r={r} />
      <span class="reqs-text is-wrap">
        <span class="reqs-name">{mediaName(r)}</span>
        <span class="reqs-what">{mediaWhat(r, true)}</span>
      </span>
      <span class="reqs-decide">
        <button type="button" class="btn reqs-decline" disabled={busy} aria-label={t('Decline {title} for {name}', p)} onClick={() => actions.decline(r)}>{t('Decline')}</button>
        <button type="button" class="btn btn-primary is-sm reqs-approve" disabled={busy} aria-label={t('Approve {title} for {name}', p)} onClick={() => actions.approve(r)}>
          <Icon name="check" size={16} stroke={2.8} />
          {t('Approve')}
        </button>
      </span>
    </div>
  );
}

export function MediaRowSkeletons({ count, kind, approval }: { count: number; kind: 'music' | 'book'; approval?: boolean }) {
  return (
    <>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} class={'reqs-row is-media' + (approval ? ' is-approval' : '')} aria-hidden="true">
          <span class={(kind === 'music' ? 'reqs-thumb-album' : 'reqs-thumb-book is-skel') + ' skel'} />
          <span class="reqs-text">
            <span class="skel skel-line" style={{ width: '38%' }} />
            <span class="skel skel-line" style={{ width: '62%' }} />
          </span>
        </div>
      ))}
    </>
  );
}

export function Note({ icon, text, action }: { icon: 'alert' | 'inbox' | 'check' | 'user'; text: string; action?: ComponentChildren }) {
  return (
    <div class="reqs-note">
      <Icon name={icon} size={18} />
      <span>{text}</span>
      {action}
    </div>
  );
}

/* ── Counts ── */

const summaryStore = createStore<MediaSummary | null>(null);
let summaryAt = 0;
export function refreshMediaSummary(): Promise<void> {
  summaryAt = Date.now();
  return mediaSummary().then((s) => summaryStore.set(s), () => { /* keep the last counts */ });
}
export function useMediaSummary(enabled = true): MediaSummary | null {
  const s = useStore(summaryStore);
  useEffect(() => { if (enabled && Date.now() - summaryAt > 3000) void refreshMediaSummary(); }, [enabled]);
  return s;
}

/* ── Actions ── */

export interface MediaActions {
  approve: (r: MediaRequest) => void;
  decline: (r: MediaRequest) => void;
  search: (r: MediaRequest) => void;
  acceptLower: (r: MediaRequest) => void;
  remove: (r: MediaRequest) => void;
  busy: (r: MediaRequest) => boolean;
}

/* Approve, decline, search again, accept a lesser quality, cancel. `onChange` gets the request after the action (null
   once it's gone); the tabs' counts refresh. */
export function useMediaActions(onChange: (r: MediaRequest, next: MediaRequest | null) => void): MediaActions {
  const [busy, setBusy] = useState<Record<string, boolean>>({});
  const run = async (r: MediaRequest, call: () => Promise<MediaRequest | null>, done: string, failed: string) => {
    setBusy((b) => ({ ...b, [r.id]: true }));
    try {
      const next = await call();
      onChange(r, next);
      noteRequest(r, next);
      toast(done);
      void refreshMediaSummary();
    } catch (e) {
      toast(failed + ((e as Error)?.message ? ': ' + (e as Error).message : ''), 'error');
    } finally {
      setBusy((b) => { const n = { ...b }; delete n[r.id]; return n; });
    }
  };
  const name = (r: MediaRequest) => ({ title: mediaName(r) });
  return {
    approve: (r) => run(r, () => mediaApprove(r.id), t('Approved {title}', name(r)), t('Couldn’t approve {title}', name(r))),
    decline: (r) => run(r, () => mediaDecline(r.id), t('Declined {title}', name(r)), t('Couldn’t decline {title}', name(r))),
    search: (r) => run(r, () => mediaSearchNow(r.id), t('Searching for {title} again', name(r)), t('Couldn’t search for {title}', name(r))),
    acceptLower: (r) => run(r, () => mediaAcceptLower(r.id), t('{quality} is fine for {title} now', { ...name(r), quality: r.lowerQuality ?? '' }), t('Couldn’t change the quality for {title}', name(r))),
    remove: async (r) => {
      const own = r.requestedByMe && r.status === 'pending';
      const ok = await confirm(own
        ? { title: t('Cancel your request?'), text: t('{title} won’t be requested. You can ask for it again later.', name(r)), action: t('Cancel request') }
        : { title: t('Remove this request?'), text: t('The request for {title} is removed, and cancelled in {service} when it’s still on its way. Anything already downloaded stays on the server.', { ...name(r), service: SERVICE_NAMES[r.service] }), action: t('Remove'), danger: true });
      if (ok) await run(r, async () => { await mediaRemove(r.id); return null; }, own ? t('Request cancelled') : t('Request removed'), t('Couldn’t remove {title}', name(r)));
    },
    busy: (r) => !!busy[r.id]
  };
}

/* ── Discover: Your requests · Waiting for your approval ── */

export function MediaTopLists({ kind }: { kind: 'music' | 'book' }) {
  const summary = useMediaSummary();
  const canManage = !!summary?.canManage;
  const asked = useStore(askedStore).n;
  const mine = useAsync(() => mediaList('mine', kind), [kind, asked]);
  const pending = useAsync(() => (canManage ? mediaList('all', kind) : Promise.resolve(null)), [canManage, kind, asked]);
  const [gone, setGone] = useState<Set<string>>(new Set());
  const actions = useMediaActions((r) => setGone((g) => new Set(g).add(r.id)));
  const mineList = (mine.data?.results ?? []).filter((r) => r.status !== 'declined').slice(0, 3);
  const waiting = (pending.data?.results ?? []).filter((r) => r.status === 'pending' && !gone.has(r.id));
  const retry = (reload: () => void) => <button type="button" class="btn btn-secondary is-xs" onClick={reload}>{t('Try again')}</button>;
  const music = kind === 'music';
  return (
    <div class="reqs-columns">
      <section aria-labelledby="reqs-mine-h" class="reqs-col is-mine">
        <div class="reqs-col-head">
          <h2 id="reqs-mine-h" class="row-title">{music ? t('Your music requests') : t('Your book requests')}</h2>
          {(mine.data?.results.length ?? 0) > 3 && <Link to={`/requests/my?type=${music ? 'music' : 'books'}`} class="row-link">{t('See all')}</Link>}
        </div>
        <div class="reqs-list">
          {mine.loading && !mine.data ? <MediaRowSkeletons count={3} kind={kind} />
            : mine.error ? <Note icon="alert" text={t('Seedy couldn’t load your requests.')} action={retry(mine.reload)} />
            : !mineList.length ? <Note icon="inbox" text={music ? t('Nothing requested yet. Pick an album below, or search for any artist above.') : t('Nothing requested yet. Pick a book below, or search for any title above.')} />
            : mineList.map((r) => <MediaMineRow key={r.id} r={r} />)}
        </div>
      </section>
      {canManage && (
        <section aria-labelledby="reqs-appr-h" class="reqs-col is-approvals">
          <div class="reqs-col-head">
            <h2 id="reqs-appr-h" class="row-title">{t('Waiting for your approval')}</h2>
            <span class="row-sub">{t('Admins only')}</span>
            {waiting.length > 4 && <Link to={`/requests/all?type=${music ? 'music' : 'books'}&show=pending`} class="row-link">{t('See all {n}', { n: waiting.length })}</Link>}
          </div>
          <div class="reqs-list">
            {pending.loading && !pending.data ? <MediaRowSkeletons count={2} kind={kind} approval />
              : pending.error ? <Note icon="alert" text={t('Couldn’t load the requests waiting for approval.')} action={retry(pending.reload)} />
              : !waiting.length ? <Note icon="check" text={t('All caught up. New requests that need you show up here.')} />
              : waiting.slice(0, 4).map((r) => <MediaApprovalRow key={r.id} r={r} actions={actions} />)}
          </div>
        </section>
      )}
    </div>
  );
}
