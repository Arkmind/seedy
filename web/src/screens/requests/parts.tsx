import { tmdbImage } from '../../api/images';
import {
  approveRequest, declineRequest, removeRequest, requestSummary, retryRequest,
  type RequestSummary, type SeerrRequest, type SeerrTitle
} from '../../api/seedy';
import { dayOf, daysFromToday } from '../../lib/format';
import { t } from '../../lib/i18n';
import { Link } from '../../lib/router';
import { createSummary } from '../../lib/store';
import { PosterCard, Progress, seerrTarget } from '../../ui/cards';
import { Icon } from '../../ui/icons';
import { confirm } from '../../ui/overlay';
import type { useRequests } from '../browse/parts';
import { seasonList } from '../browse/request-seasons';
import { useBusyRun } from './busy-run';
import './requests.css';
import { Chip, DecideButtons, type ChipState } from './media-parts';
import { actLine, aheadDate, requesterName } from './wording';

/* Shared pieces of the Requests page (Requests board): the tabs' counts, request rows with their wording, state chips
   and actions, and the posters on its rows. */

/* ── The tabs' counts and what the person may do: fresh each time a Requests page opens, and after any change. ── */

const summary = createSummary<RequestSummary>(requestSummary);
export const refreshSummary = summary.refresh;
/* The pieces of one page share a single call. */
export const useSummary = summary.use;

/* ── Wording ── */

/* The line under a request's title: what was asked for, when (and by whom, on everyone's list), and what's still to
   come. "Seasons 2 and 4 · approved Mon 19 Oct · Season 4 downloads when it airs". */
function requestLine(r: SeerrRequest, everyone: boolean): string {
  const parts = [r.mediaType === 'tv' ? (r.seasons?.length ? seasonList(r.seasons) : t('All seasons')) : t('Movie')];
  if (r.is4k) parts.push('4K');
  if (everyone) parts.push(actLine('askedBy', r.requestedAt, requesterName(r)));
  else if (r.requestStatus === 'declined') parts.push(actLine('declined', r.updatedAt ?? r.requestedAt));
  else if ((r.requestStatus === 'approved' || r.requestStatus === 'completed') && r.status !== 'available' && r.updatedAt) parts.push(actLine('approved', r.updatedAt));
  else parts.push(actLine('asked', r.requestedAt));
  // The server only sends these while an approved request is still to come.
  const unaired = r.unaired ?? [];
  if (unaired.length && unaired.length < (r.seasons?.length ?? 0)) {
    parts.push(t(unaired.length === 1 ? '{seasons} downloads when it airs' : '{seasons} download when they air', { seasons: seasonList(unaired) }));
  }
  const out = dayOf(r.releaseDate);
  if (out) parts.push(t('out {date}', { date: aheadDate(out) }));
  return parts.filter(Boolean).join(' · ');
}

/* Where a request is now, for its chip (and its bar while it downloads). */
function requestState(r: SeerrRequest): ChipState {
  if (r.status === 'available') return { text: t('On the server'), tone: 'ok' };
  if (r.requestStatus === 'declined') return { text: t('Declined'), tone: 'danger' };
  if (r.requestStatus === 'failed') return { text: t('Couldn’t be added'), tone: 'danger' };
  if (r.requestStatus === 'pending') return { text: t('Waiting for approval'), tone: 'pending' };
  if (r.download) {
    const p = Math.round(r.download.fraction * 100);
    const text = r.download.season ? t('Season {n} downloading · {p}%', { n: r.download.season, p }) : t('Downloading · {p}%', { p });
    return { text, tone: 'accent', fraction: r.download.fraction };
  }
  if (r.status === 'partial') return { text: t('Partly on the server'), tone: 'ok' };
  if (r.releaseDate) return { text: t('Not out yet'), tone: 'muted' };
  if (r.unaired?.length && r.unaired.length >= (r.seasons?.length ?? 0)) return { text: t('Not aired yet'), tone: 'muted' };
  return { text: t('Searching · not found yet'), tone: 'muted' };
}

/* ── Rows ── */

function Thumb({ r }: { r: SeerrRequest }) {
  const src = tmdbImage(r.posterPath, 'w185');
  return <span class="reqs-thumb" aria-hidden="true">{src && <img src={src} alt="" loading="lazy" />}</span>;
}

/* Title, the line under it, and the bar while it downloads. In rows that hold buttons the title is the link. */
function RowText({ r, state, everyone, link }: { r: SeerrRequest; state: ChipState; everyone: boolean; link?: boolean }) {
  return (
    <span class={'reqs-text' + (link ? ' is-wrap' : '')}>
      {link ? <Link to={seerrTarget(r)} class="reqs-name">{r.title}</Link> : <span class="reqs-name">{r.title}</span>}
      <span class="reqs-what">{requestLine(r, everyone)}</span>
      {state.fraction !== undefined && <Progress fraction={state.fraction} size={5} class="reqs-progress" />}
    </span>
  );
}

/* Discover · Your requests: the whole row opens the title. */
export function MineRow({ r }: { r: SeerrRequest }) {
  const state = requestState(r);
  return (
    <Link to={seerrTarget(r)} class="reqs-row">
      <Thumb r={r} />
      <RowText r={r} state={state} everyone={false} />
      <Chip state={state} />
    </Link>
  );
}

/* Discover · Waiting for your approval. */
export function ApprovalRow({ r, actions }: { r: SeerrRequest; actions: RequestActions }) {
  return (
    <div class="reqs-row is-approval">
      <Thumb r={r} />
      <RowText r={r} state={requestState(r)} everyone link />
      <ApproveButtons r={r} actions={actions} />
    </div>
  );
}

/* My requests and All requests: the state chip, then what the person may do with it. */
export function ListRow({ r, actions, everyone, canManage }: { r: SeerrRequest; actions: RequestActions; everyone: boolean; canManage: boolean }) {
  const state = requestState(r);
  const decide = canManage && r.requestStatus === 'pending';
  const withdraw = !canManage && r.requestedByMe && r.requestStatus === 'pending';
  const busy = actions.busy(r);
  return (
    <div class={'reqs-row is-list' + (decide ? ' is-approval' : '')}>
      <Link to={seerrTarget(r)} class="reqs-thumb-link" tabIndex={-1} aria-hidden="true"><Thumb r={r} /></Link>
      <RowText r={r} state={state} everyone={everyone} link />
      {!decide && <Chip state={state} />}
      <span class="reqs-acts">
        {decide && <ApproveButtons r={r} actions={actions} />}
        {canManage && r.requestStatus === 'failed' && (
          <button type="button" class="btn btn-secondary is-sm" disabled={busy} onClick={() => actions.retry(r)}>
            <Icon name="refresh" size={16} />{t('Retry')}
          </button>
        )}
        {withdraw && <button type="button" class="btn btn-secondary is-sm" disabled={busy} onClick={() => actions.remove(r)}>{t('Withdraw')}</button>}
        {canManage && (
          <button type="button" class="btn icon-btn is-bare is-40" disabled={busy} aria-label={t('Remove the request for {title}', { title: r.title })} title={t('Remove request')} onClick={() => actions.remove(r)}>
            <Icon name="trash" size={18} />
          </button>
        )}
      </span>
    </div>
  );
}

/* ── Actions ── */

export interface RequestActions {
  approve: (r: SeerrRequest) => void;
  decline: (r: SeerrRequest) => void;
  retry: (r: SeerrRequest) => void;
  remove: (r: SeerrRequest) => void;
  busy: (r: SeerrRequest) => boolean;
}

/* Approve, decline, retry and remove, as Seerr allows the person. `onChange` gets the request as it is after the action
   (null once it's gone); the tabs' counts refresh. */
export function useRequestActions(onChange: (r: SeerrRequest, next: SeerrRequest | null) => void): RequestActions {
  const { run, busy } = useBusyRun<SeerrRequest, SeerrRequest | null>((r) => r.requestId, onChange, refreshSummary);
  // Approve, decline and retry answer with the request as it is now.
  const change = (call: (id: number) => Promise<SeerrRequest | undefined>, done: string, failed: string) => (r: SeerrRequest) =>
    run(r, async () => ({ ...r, ...(await call(r.requestId)) }), t(done, { title: r.title }), t(failed, { title: r.title }));
  return {
    approve: change(approveRequest, 'Approved {title}', 'Couldn’t approve {title}'),
    decline: change(declineRequest, 'Declined {title}', 'Couldn’t decline {title}'),
    retry: change(retryRequest, 'Sent {title} to the downloader again', 'Couldn’t retry {title}'),
    remove: async (r) => {
      const own = r.requestedByMe && r.requestStatus === 'pending';
      const ok = await confirm(own
        ? { title: t('Withdraw your request?'), text: t('{title} won’t be requested. You can ask for it again later.', { title: r.title }), action: t('Withdraw') }
        : { title: t('Remove this request?'), text: t('The request for {title} is removed from Seerr. Anything already downloaded stays on the server.', { title: r.title }), action: t('Remove'), danger: true });
      if (ok) await run(r, async () => { await removeRequest(r.requestId); return null; }, own ? t('Request withdrawn') : t('Request removed'), t('Couldn’t remove {title}', { title: r.title }));
    },
    busy
  };
}

function ApproveButtons({ r, actions }: { r: SeerrRequest; actions: RequestActions }) {
  const p = { title: r.title, name: requesterName(r) };
  const busy = actions.busy(r);
  return <DecideButtons {...p} busy={busy} onDecline={() => actions.decline(r)} onApprove={() => actions.approve(r)} />;
}

/* ── Titles on the rows ── */

/* The date under an upcoming title, in the accent when it's within two weeks or the person asked for it. */
function upcomingMeta(s: SeerrTitle): { text: string; accent: boolean } {
  const d = dayOf(s.releaseDate);
  const mine = !!s.requestedByMe && s.status !== 'none';
  if (!d) return { text: mine ? t('You requested it') : t('Announced'), accent: mine };
  const days = daysFromToday(d);
  const when = days < 0 ? t('Out now') : aheadDate(d);
  return { text: mine ? t('{when} · you requested it', { when }) : when, accent: mine || (days >= 0 && days <= 14) };
}

export function TitleCard({ item, upcoming, req, width }: { item: SeerrTitle; upcoming: boolean; req: ReturnType<typeof useRequests>; width?: number }) {
  const shown = req.apply(item);
  const meta = upcoming ? upcomingMeta(shown) : undefined;
  // An upcoming season of a requested series opens the series, where that season can be requested.
  const season = item.season !== undefined;
  return (
    <PosterCard
      item={shown}
      width={width}
      title={season ? `${item.title} · ${t('Season {n}', { n: item.season! })}` : undefined}
      upcoming={upcoming}
      meta={meta?.text}
      metaAccent={meta?.accent}
      canRequest={req.canRequest && !season}
      onRequest={req.request}
      requesting={req.busy(item)}
    />
  );
}
