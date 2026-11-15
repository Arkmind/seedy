import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import {
  mediaAcceptLower, mediaApprove, mediaDecline, mediaList, mediaRemove, mediaSearchNow, mediaSummary, type BookFormat, type CardStatus,
  type MediaOptions, type MediaRequest, type MediaService, type MediaSummary
} from '../../api/media';
import { formatNumber, t, tn } from '../../lib/i18n';
import { Link } from '../../lib/router';
import { createStore, createSummary, useAsync, useStore } from '../../lib/store';
import { Icon } from '../../ui/icons';
import { confirm, toastError } from '../../ui/overlay';
import { actLine, requesterName } from './wording';
import { useBusyRun } from './busy-run';
import { pctOf as widthOf } from '../../lib/format';
import { BOOK_PALETTES, DrawnBookSvg, DrawnSquareSvg, SQUARE_PALETTES } from '../../ui/drawn-art';
import './media.css';

/* Shared pieces of the Music and Books request pages (RequestsMusic, RequestsBooks, RequestsMine boards): covers with
   the boards' art when there's no image, request wording, state chips and actions. */

/* ── Covers ── */

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
  const c = SQUARE_PALETTES[i % SQUARE_PALETTES.length];
  return (
    <span class={'reqs-art' + (round ? ' is-round' : '') + (cls ? ' ' + cls : '')} aria-hidden="true" style={{ background: c[0] }}>
      <DrawnSquareSvg c={c} s={i % 4} hill={(i + 1) % 4} bare={round} />
      {text && <span class={round ? 'reqs-art-initials' : 'reqs-art-text'} style={{ color: c[3] }}>{text}</span>}
      <Img src={src} />
    </span>
  );
}

/* A 2:3 book cover: the image, or the boards' drawn cover with title and author. */
export function BookArt({ seed, src, title, author, size = 'card', class: cls }: { seed: string; src?: string | null; title: string; author?: string; size?: 'card' | 'thumb' | 'dialog'; class?: string }) {
  const i = hash(seed);
  const c = BOOK_PALETTES[i % BOOK_PALETTES.length];
  return (
    <span class={'reqs-book is-' + size + (cls ? ' ' + cls : '')} aria-hidden="true" style={{ background: c[0] }}>
      <DrawnBookSvg c={c} s={i % 4} />
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

/* A book's place in its series: "3", "2.5" ("2,5"). */
export const seriesNumber = (n?: number) =>
  n === undefined ? '' : Number.isInteger(n) ? String(n) : formatNumber(Number(n.toFixed(1)), { minimumFractionDigits: 1, maximumFractionDigits: 1 });

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

/* The line under a request on Discover: what, when (approved while it's on its way), and the app for books. */
export function mediaWhat(r: MediaRequest, everyone: boolean): string {
  const parts = [mediaKindLine(r)];
  if (everyone) parts.push(actLine('askedBy', r.requestedAt, requesterName(r)));
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
  const p = { title: mediaName(r), name: requesterName(r) };
  const busy = actions.busy(r);
  return (
    <div class={'reqs-row is-approval is-media is-' + r.kind}>
      <RequestThumb r={r} />
      <span class="reqs-text is-wrap">
        <span class="reqs-name">{mediaName(r)}</span>
        <span class="reqs-what">{mediaWhat(r, true)}</span>
      </span>
      <DecideButtons {...p} busy={busy} onDecline={() => actions.decline(r)} onApprove={() => actions.approve(r)} />
    </div>
  );
}

/* Decline and Approve on a request waiting for an admin. */
export function DecideButtons({ title, name, busy, onDecline, onApprove }: { title: string; name: string; busy: boolean; onDecline: () => void; onApprove: () => void }) {
  return (
    <span class="reqs-decide">
      <button type="button" class="btn reqs-decline" disabled={busy} aria-label={t('Decline {title} for {name}', { title, name })} onClick={onDecline}>{t('Decline')}</button>
      <button type="button" class="btn btn-primary is-sm reqs-approve" disabled={busy} aria-label={t('Approve {title} for {name}', { title, name })} onClick={onApprove}>
        <Icon name="check" size={16} stroke={2.8} />
        {t('Approve')}
      </button>
    </span>
  );
}

/* Request rows while they load: [thumb] is the cover's class (a poster, an album, a book). */
export function RowSkeletons({ count, approval, media, thumb = 'reqs-thumb skel' }: { count: number; approval?: boolean; media?: boolean; thumb?: string }) {
  return (
    <>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} class={'reqs-row' + (media ? ' is-media' : '') + (approval ? ' is-approval' : '')} aria-hidden="true">
          <span class={thumb} />
          <span class="reqs-text">
            <span class="skel skel-line" style={{ width: '38%' }} />
            <span class="skel skel-line" style={{ width: '62%' }} />
          </span>
        </div>
      ))}
    </>
  );
}

export const MediaRowSkeletons = ({ count, kind, approval }: { count: number; kind: 'music' | 'book'; approval?: boolean }) =>
  <RowSkeletons count={count} approval={approval} media thumb={(kind === 'music' ? 'reqs-thumb-album' : 'reqs-thumb-book is-skel') + ' skel'} />;

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

const summary = createSummary<MediaSummary>(mediaSummary);
export const refreshMediaSummary = summary.refresh;
export const useMediaSummary = summary.use;

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
  const { run, busy } = useBusyRun<MediaRequest, MediaRequest | null>((r) => r.id, (r, next) => { onChange(r, next); noteRequest(r, next); }, refreshMediaSummary);
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
    busy
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
  const music = kind === 'music';
  return (
    <TopColumns
      mineTitle={music ? t('Your music requests') : t('Your book requests')}
      mineSeeAll={(mine.data?.results.length ?? 0) > 3 ? `/requests/my?type=${music ? 'music' : 'books'}` : undefined}
      mine={mine.loading && !mine.data ? <MediaRowSkeletons count={3} kind={kind} />
        : mine.error ? <Note icon="alert" text={t('Seedy couldn’t load your requests.')} action={<RetryButton onClick={mine.reload} />} />
        : !mineList.length ? <Note icon="inbox" text={music ? t('Nothing requested yet. Pick an album below, or search for any artist above.') : t('Nothing requested yet. Pick a book below, or search for any title above.')} />
        : mineList.map((r) => <MediaMineRow key={r.id} r={r} />)}
      approvals={canManage ? (pending.loading && !pending.data ? <MediaRowSkeletons count={2} kind={kind} approval />
        : pending.error ? <Note icon="alert" text={t('Couldn’t load the requests waiting for approval.')} action={<RetryButton onClick={pending.reload} />} />
        : !waiting.length ? <Note icon="check" text={t('All caught up. New requests that need you show up here.')} />
        : waiting.slice(0, 4).map((r) => <MediaApprovalRow key={r.id} r={r} actions={actions} />)) : undefined}
      approvalsSeeAll={waiting.length > 4 ? { to: `/requests/all?type=${music ? 'music' : 'books'}&show=pending`, n: waiting.length } : undefined}
    />
  );
}

export const RetryButton = ({ onClick }: { onClick: () => void }) => <button type="button" class="btn btn-secondary is-xs" onClick={onClick}>{t('Try again')}</button>;

/* The two columns at the top of a Requests page: the person's own requests, and (for admins) what waits for them. */
export function TopColumns({ mineTitle, mineSeeAll, mine, approvals, approvalsSeeAll }: {
  mineTitle: string; mineSeeAll?: string; mine: ComponentChildren; approvals?: ComponentChildren; approvalsSeeAll?: { to: string; n: number };
}) {
  return (
    <div class="reqs-columns">
      <section aria-labelledby="reqs-mine-h" class="reqs-col is-mine">
        <div class="reqs-col-head">
          <h2 id="reqs-mine-h" class="row-title">{mineTitle}</h2>
          {mineSeeAll && <Link to={mineSeeAll} class="row-link">{t('See all')}</Link>}
        </div>
        <div class="reqs-list">{mine}</div>
      </section>
      {approvals !== undefined && (
        <section aria-labelledby="reqs-appr-h" class="reqs-col is-approvals">
          <div class="reqs-col-head">
            <h2 id="reqs-appr-h" class="row-title">{t('Waiting for your approval')}</h2>
            <span class="row-sub">{t('Admins only')}</span>
            {approvalsSeeAll && <Link to={approvalsSeeAll.to} class="row-link">{t('See all {n}', { n: approvalsSeeAll.n })}</Link>}
          </div>
          <div class="reqs-list">{approvals}</div>
        </section>
      )}
    </div>
  );
}

/* ── Request dialogs (music and books) ── */

export const DialogClose = ({ onClose }: { onClose: () => void }) => (
  <button type="button" class="reqs-dlg-close" aria-label={t('Close')} onClick={onClose}><Icon name="close" size={18} stroke={2.2} /></button>
);

export const DialogError = ({ message, onRetry }: { message: string; onRetry: () => void }) => (
  <div class="rq-error">
    <span>{message}</span>
    <button type="button" class="btn btn-secondary is-sm" onClick={onRetry}>{t('Try again')}</button>
  </div>
);

export const sendFailed = (e: unknown) => toastError(t('Couldn’t send the request.'), e);

/* What happens to the request (the dot's tone and a sentence), Cancel, and Request. */
export function DialogFoot({ tone, note, busy, disabled, label, onSend, onClose }: {
  tone?: 'danger' | 'ok' | 'pending'; note: ComponentChildren; busy: boolean; disabled: boolean; label: string; onSend: () => void; onClose: () => void;
}) {
  return (
    <div class="reqs-dlg-foot">
      <span class="reqs-dlg-note">
        {tone && <span class={'reqs-dot is-8 is-' + tone} aria-hidden="true" />}
        {note}
      </span>
      <span class="reqs-dlg-buttons">
        <button type="button" class="reqs-dlg-cancel" onClick={onClose}>{t('Cancel')}</button>
        <button type="button" data-autofocus class="btn btn-primary reqs-dlg-send" disabled={disabled} onClick={onSend}>
          <Icon name="request" size={18} stroke={2.2} />
          {busy ? t('Requesting…') : label}
        </button>
      </span>
    </div>
  );
}

/* "Album requests this week · 3 of 5 left", with what's used in grey and this request in the accent. */
export function Quota({ label, opts, units, aria, extra }: { label: string; opts: MediaOptions; units: number; aria: string; extra?: string }) {
  const limit = opts.limit;
  const left = Math.max(0, limit - opts.used);
  const over = limit >= 0 && units > left;
  return (
    <div class="reqs-dlg-card is-quota">
      <span class="reqs-dlg-quota-line">
        <span>{label}</span>
        <span class={over ? 'is-over' : undefined}>{limit < 0 ? t('No limit') : limit === 0 ? t('Can’t request') : t('{left} of {limit} left', { left, limit })}</span>
      </span>
      <span role="img" aria-label={aria} class="reqs-dlg-quota-bar">
        {limit > 0 && <span style={{ width: widthOf(opts.used, limit) }} />}
        {limit > 0 && <span class={over ? 'is-over' : undefined} style={{ width: widthOf(Math.min(units, Math.max(0, limit - opts.used)), limit) }} />}
      </span>
      {extra && <span class="reqs-dlg-card-label">{extra}</span>}
    </div>
  );
}
