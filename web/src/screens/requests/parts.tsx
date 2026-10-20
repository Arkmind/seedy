import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { tmdbImage } from '../../api/images';
import {
  approveRequest, declineRequest, removeRequest, requestSummary, retryRequest,
  type MyRequest, type RequestSummary, type SeerrTitle
} from '../../api/seedy';
import { meStore } from '../../lib/app-state';
import { dayOf, daysFromToday, fmtDate, fmtMonthYear, fmtWeekdayDate } from '../../lib/format';
import { t } from '../../lib/i18n';
import { Link, setQuery, useLocation } from '../../lib/router';
import { createStore, useStore } from '../../lib/store';
import { PosterCard, seerrPath } from '../../ui/cards';
import { Icon } from '../../ui/icons';
import { Header } from '../../ui/navbar/Header';
import { confirm, toast } from '../../ui/overlay';
import { seasonList } from '../browse/request-seasons';
import type { useRequests } from '../browse/parts';
import './requests.css';

/* Shared pieces of the Requests page (Requests board): the page frame with its search and tabs, request rows and
   their state chips, and the posters on its rows. */

/* ── Tabs' counts and what the person may do, shared by the tabs; refreshed after anything changes a request. ── */

export const summaryStore = createStore<RequestSummary | null>(null);
let summaryAt = 0;
export function refreshSummary(): Promise<void> {
  summaryAt = Date.now();
  return requestSummary().then((s) => summaryStore.set(s), () => { /* tabs keep their last counts */ });
}
/* Fresh each time a Requests page opens (the pieces of one page share a single call). */
export function useSummary(): RequestSummary | null {
  const s = useStore(summaryStore);
  useEffect(() => { if (Date.now() - summaryAt > 3000) void refreshSummary(); }, []);
  return s;
}

export type RequestsTab = 'discover' | 'my' | 'all' | null;

const TABS: { id: Exclude<RequestsTab, null>; path: string; label: string }[] = [
  { id: 'discover', path: '/requests', label: 'Discover' },
  { id: 'my', path: '/requests/my', label: 'My requests' },
  { id: 'all', path: '/requests/all', label: 'All requests' }
];

/* Header, title, Seerr search and the Discover · My requests · All requests tabs. While there's a search, its
   results take the place of the tab's content. */
export function RequestsFrame({ tab, children, search: renderSearch }: {
  tab: RequestsTab;
  children: ComponentChildren;
  search: (query: string) => ComponentChildren;
}) {
  const me = useStore(meStore);
  const summary = useSummary();
  const loc = useLocation();
  const query = (loc.query.get('q') ?? '').trim();
  const [text, setText] = useState(query);
  useEffect(() => {
    const id = window.setTimeout(() => { if (text.trim() !== query) setQuery({ q: text.trim() || undefined }); }, 350);
    return () => window.clearTimeout(id);
  }, [text]);
  useEffect(() => { if (!query) setText((v) => (v.trim() ? '' : v)); }, [query]);
  const seerr = me.requests.seerrUrl?.trim();

  return (
    <div class="page reqs-page">
      <Header active="requests" />
      <main class="reqs-main">
        <div class="reqs-head">
          <div class="reqs-title">
            <h1>{t('Requests')}</h1>
            <span class="reqs-lede">{t('From Seerr · ask for anything the server doesn’t have')}</span>
          </div>
          {seerr && (
            <a href={seerr} target="_blank" rel="noreferrer" class="reqs-open">
              {t('Open in Seerr')}
              <Icon name="external" size={16} stroke={2.2} />
            </a>
          )}
        </div>

        <div class="reqs-bar">
          <label class="reqs-search">
            <Icon name="search" size={20} stroke={2.2} />
            <input
              type="search"
              aria-label={t('Search Seerr for movies and shows')}
              placeholder={t('Find any movie or show to request')}
              value={text}
              onInput={(e) => setText((e.currentTarget as HTMLInputElement).value)}
              onKeyDown={(e) => {
                if (e.key === 'Escape' && text) { e.preventDefault(); setText(''); setQuery({ q: undefined }); }
                if (e.key === 'Enter') setQuery({ q: text.trim() || undefined });
              }}
            />
          </label>
          <nav aria-label={t('Requests views')} class="reqs-tabs">
            {TABS.filter((x) => x.id !== 'all' || summary?.canViewAll).map((x) => {
              const count = x.id === 'my' ? summary?.open : x.id === 'all' ? summary?.pending : undefined;
              const current = tab === x.id && !query;
              return (
                <Link key={x.id} to={x.path} class={'reqs-tab' + (current ? ' is-current' : '')} aria-current={current ? 'page' : undefined}>
                  {t(x.label)}
                  {!!count && (
                    <span class={'reqs-count' + (x.id === 'all' ? ' is-pending' : '')} aria-label={x.id === 'all' ? t('{n} waiting for approval', { n: count }) : t('{n} open', { n: count })}>
                      {count}
                    </span>
                  )}
                </Link>
              );
            })}
          </nav>
        </div>

        {query ? renderSearch(query) : children}
      </main>
    </div>
  );
}

/* ── Wording ── */

const dayKey = (iso?: string): { key: 'today' | 'yesterday' | 'date'; date: string } | null => {
  const d = iso ? new Date(iso) : undefined;
  if (!d || Number.isNaN(d.getTime())) return null;
  const ago = -daysFromToday(d);
  const date = d.getFullYear() === new Date().getFullYear() ? fmtWeekdayDate(d) : fmtDate(d);
  return { key: ago <= 0 ? 'today' : ago === 1 ? 'yesterday' : 'date', date };
};

/* "asked Sat 17 Oct", "approved today", "asked by Mia yesterday". */
function actLine(act: 'asked' | 'approved' | 'declined', iso?: string, who?: string): string {
  const k = dayKey(iso);
  if (!k) return '';
  const p = { date: k.date, name: who ?? '' };
  if (who) {
    if (k.key === 'today') return t('asked by {name} today', p);
    if (k.key === 'yesterday') return t('asked by {name} yesterday', p);
    return t('asked by {name} {date}', p);
  }
  if (act === 'approved') return k.key === 'today' ? t('approved today') : k.key === 'yesterday' ? t('approved yesterday') : t('approved {date}', p);
  if (act === 'declined') return k.key === 'today' ? t('declined today') : k.key === 'yesterday' ? t('declined yesterday') : t('declined {date}', p);
  return k.key === 'today' ? t('asked today') : k.key === 'yesterday' ? t('asked yesterday') : t('asked {date}', p);
}

const settled = (r: MyRequest) => r.status === 'available' || r.requestStatus === 'declined' || r.requestStatus === 'failed';

/* The line under a request's title: what was asked for, when (and by whom, on everyone's list), and what's still to come.
   "Seasons 2 and 4 · approved Mon 19 Oct · Season 4 downloads when it airs". */
export function requestLine(r: MyRequest, withWho: boolean): string {
  const what = r.mediaType === 'tv' ? (r.seasons?.length ? seasonList(r.seasons) : t('All seasons')) : t('Movie');
  const parts = [what];
  if (r.is4k) parts.push('4K');
  if (withWho) parts.push(actLine('asked', r.requestedAt, r.requestedByMe ? t('you') : r.requestedBy || t('someone')));
  else if (r.requestStatus === 'declined') parts.push(actLine('declined', r.updatedAt ?? r.requestedAt));
  else if ((r.requestStatus === 'approved' || r.requestStatus === 'completed') && r.status !== 'available' && r.updatedAt) parts.push(actLine('approved', r.updatedAt));
  else parts.push(actLine('asked', r.requestedAt));
  if (!settled(r)) {
    const unaired = r.unaired ?? [];
    if (r.mediaType === 'tv' && unaired.length && unaired.length < (r.seasons?.length ?? 0)) {
      parts.push(unaired.length === 1 ? t('{seasons} downloads when it airs', { seasons: seasonList(unaired) }) : t('{seasons} download when they air', { seasons: seasonList(unaired) }));
    }
    const out = dayOf(r.releaseDate);
    if (r.mediaType === 'movie' && out) parts.push(t('out {date}', { date: daysFromToday(out) < 300 ? fmtWeekdayDate(out) : fmtMonthYear(out) }));
  }
  return parts.filter(Boolean).join(' · ');
}

export type Tone = 'ok' | 'pending' | 'accent' | 'muted' | 'danger';

/* Where a request is now, for its chip. */
export function requestState(r: MyRequest): { text: string; tone: Tone; fraction?: number } {
  if (r.status === 'available') return { text: t('On the server'), tone: 'ok' };
  if (r.requestStatus === 'declined') return { text: t('Declined'), tone: 'danger' };
  if (r.requestStatus === 'failed') return { text: t('Couldn’t be added'), tone: 'danger' };
  if (r.requestStatus === 'pending') return { text: t('Waiting for approval'), tone: 'pending' };
  if (r.download) {
    const p = Math.round(r.download.fraction * 100);
    return {
      text: r.download.season ? t('Season {n} downloading · {p}%', { n: r.download.season, p }) : t('Downloading · {p}%', { p }),
      tone: 'accent',
      fraction: r.download.fraction
    };
  }
  if (r.status === 'partial') return { text: t('Partly on the server'), tone: 'ok' };
  if (r.mediaType === 'movie' && r.releaseDate) return { text: t('Not out yet'), tone: 'muted' };
  if (r.mediaType === 'tv' && r.unaired?.length && r.unaired.length >= (r.seasons?.length ?? 0)) return { text: t('Not aired yet'), tone: 'muted' };
  return { text: t('Searching · not found yet'), tone: 'muted' };
}

export function StateChip({ r }: { r: MyRequest }) {
  const s = requestState(r);
  return <span class={'reqs-chip is-' + s.tone}><span class="reqs-chip-dot" aria-hidden="true" />{s.text}</span>;
}

export const requestTarget = (r: MyRequest) =>
  r.jellyfinItemId && (r.status === 'available' || r.status === 'partial') ? `/details/${r.jellyfinItemId}` : seerrPath(r);

function Thumb({ r }: { r: MyRequest }) {
  const src = tmdbImage(r.posterPath, 'w185');
  return <span class="reqs-thumb" aria-hidden="true">{src && <img src={src} alt="" loading="lazy" />}</span>;
}

function Bar({ fraction }: { fraction?: number }) {
  if (fraction === undefined) return null;
  return (
    <span class="reqs-bar-track" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(fraction * 100)}>
      <span style={{ width: `${(Math.max(0, Math.min(1, fraction)) * 100).toFixed(1)}%` }} />
    </span>
  );
}

/* Discover · Your requests: the whole row opens the title. */
export function MineRow({ r }: { r: MyRequest }) {
  const s = requestState(r);
  return (
    <Link to={requestTarget(r)} class="reqs-row is-link">
      <Thumb r={r} />
      <span class="reqs-text">
        <span class="reqs-name">{r.title}</span>
        <span class="reqs-what">{requestLine(r, false)}</span>
        <Bar fraction={s.fraction} />
      </span>
      <StateChip r={r} />
    </Link>
  );
}

/* ── Actions on a request ── */

export interface RequestActions {
  approve: (r: MyRequest) => void;
  decline: (r: MyRequest) => void;
  retry: (r: MyRequest) => void;
  remove: (r: MyRequest) => void;
  busy: (r: MyRequest) => boolean;
}

/* Approve, decline, retry and remove, as Seerr allows the person. `onChange` gets the request as it is after the action
   (null once it's gone); the tabs' counts refresh. */
export function useRequestActions(onChange: (r: MyRequest, next: MyRequest | null) => void): RequestActions {
  const [busy, setBusy] = useState<Record<number, boolean>>({});
  const run = async (r: MyRequest, call: () => Promise<MyRequest | null>, done: string, failed: string) => {
    setBusy((b) => ({ ...b, [r.requestId]: true }));
    try {
      const next = await call();
      onChange(r, next);
      toast(done);
      void refreshSummary();
    } catch (e) {
      toast(failed + ((e as Error)?.message ? ': ' + (e as Error).message : ''), 'error');
    } finally {
      setBusy((b) => { const n = { ...b }; delete n[r.requestId]; return n; });
    }
  };
  const merge = (r: MyRequest, next?: MyRequest) => (next ? { ...r, ...next } : r);
  return {
    approve: (r) => run(r, async () => merge(r, await approveRequest(r.requestId)), t('Approved {title}', { title: r.title }), t('Couldn’t approve {title}', { title: r.title })),
    decline: (r) => run(r, async () => merge(r, await declineRequest(r.requestId)), t('Declined {title}', { title: r.title }), t('Couldn’t decline {title}', { title: r.title })),
    retry: (r) => run(r, async () => merge(r, await retryRequest(r.requestId)), t('Sent {title} to the downloader again', { title: r.title }), t('Couldn’t retry {title}', { title: r.title })),
    remove: async (r) => {
      const own = r.requestedByMe && r.requestStatus === 'pending';
      const ok = await confirm(own
        ? { title: t('Withdraw your request?'), text: t('{title} won’t be requested. You can ask for it again later.', { title: r.title }), action: t('Withdraw') }
        : { title: t('Remove this request?'), text: t('The request for {title} is removed from Seerr. Anything already downloaded stays on the server.', { title: r.title }), action: t('Remove'), danger: true });
      if (!ok) return;
      await run(r, async () => { await removeRequest(r.requestId); return null; }, own ? t('Request withdrawn') : t('Request removed'), t('Couldn’t remove {title}', { title: r.title }));
    },
    busy: (r) => !!busy[r.requestId]
  };
}

export function ApproveButtons({ r, actions }: { r: MyRequest; actions: RequestActions }) {
  const who = r.requestedByMe ? t('you') : r.requestedBy || t('someone');
  const busy = actions.busy(r);
  return (
    <span class="reqs-decide">
      <button type="button" class="reqs-decline" disabled={busy} aria-label={t('Decline {title} for {name}', { title: r.title, name: who })} onClick={() => actions.decline(r)}>
        {t('Decline')}
      </button>
      <button type="button" class="reqs-approve" disabled={busy} aria-label={t('Approve {title} for {name}', { title: r.title, name: who })} onClick={() => actions.approve(r)}>
        <Icon name="check" size={16} stroke={2.8} />
        {t('Approve')}
      </button>
    </span>
  );
}

/* Discover · Waiting for your approval. */
export function ApprovalRow({ r, actions }: { r: MyRequest; actions: RequestActions }) {
  return (
    <div class="reqs-row is-approval">
      <Thumb r={r} />
      <span class="reqs-text is-wrap">
        <Link to={requestTarget(r)} class="reqs-name">{r.title}</Link>
        <span class="reqs-what">{requestLine(r, true)}</span>
      </span>
      <ApproveButtons r={r} actions={actions} />
    </div>
  );
}

/* My requests and All requests: the state chip, then what the person may do with it. */
export function ListRow({ r, actions, everyone, canManage }: { r: MyRequest; actions: RequestActions; everyone: boolean; canManage: boolean }) {
  const s = requestState(r);
  const decide = canManage && r.requestStatus === 'pending';
  const busy = actions.busy(r);
  const canRemove = canManage || (r.requestedByMe && r.requestStatus === 'pending');
  return (
    <div class={'reqs-row is-list' + (decide ? ' is-approval' : '')}>
      <Link to={requestTarget(r)} class="reqs-thumb-link" tabIndex={-1} aria-hidden="true"><Thumb r={r} /></Link>
      <span class="reqs-text is-wrap">
        <Link to={requestTarget(r)} class="reqs-name">{r.title}</Link>
        <span class="reqs-what">{requestLine(r, everyone)}</span>
        <Bar fraction={s.fraction} />
      </span>
      {!decide && <StateChip r={r} />}
      <span class="reqs-acts">
        {decide && <ApproveButtons r={r} actions={actions} />}
        {canManage && r.requestStatus === 'failed' && (
          <button type="button" class="btn btn-secondary is-sm" disabled={busy} onClick={() => actions.retry(r)}>
            <Icon name="refresh" size={16} />{t('Retry')}
          </button>
        )}
        {!canManage && canRemove && (
          <button type="button" class="btn btn-secondary is-sm" disabled={busy} onClick={() => actions.remove(r)}>{t('Withdraw')}</button>
        )}
        {canManage && (
          <button type="button" class="btn icon-btn is-bare is-40" disabled={busy} aria-label={t('Remove the request for {title}', { title: r.title })} title={t('Remove request')} onClick={() => actions.remove(r)}>
            <Icon name="trash" size={18} />
          </button>
        )}
      </span>
    </div>
  );
}

export function RowSkeletons({ count, approval }: { count: number; approval?: boolean }) {
  return (
    <>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} class={'reqs-row' + (approval ? ' is-approval' : '')} aria-hidden="true">
          <span class="reqs-thumb skel" />
          <span class="reqs-text">
            <span class="skel skel-line" style={{ width: '38%' }} />
            <span class="skel skel-line" style={{ width: '62%' }} />
          </span>
        </div>
      ))}
    </>
  );
}

/* ── Titles on the rows ── */

/* "Fri 31 Oct" in the next month, else "Nov 2026"; accent when it's within two weeks or the person asked for it. */
function upcomingMeta(s: SeerrTitle): { text: string; accent: boolean } {
  const d = dayOf(s.releaseDate);
  const mine = !!s.requestedByMe && s.status !== 'none';
  if (!d) return { text: mine ? t('You requested it') : t('Announced'), accent: mine };
  const days = daysFromToday(d);
  const when = days < 0 ? t('Out now') : days <= 31 ? fmtWeekdayDate(d) : fmtMonthYear(d);
  return { text: mine ? t('{when} · you requested it', { when }) : when, accent: mine || (days >= 0 && days <= 14) };
}

export function TitleCard({ item, upcoming, req, width }: { item: SeerrTitle; upcoming: boolean; req: ReturnType<typeof useRequests>; width?: number }) {
  const shown = req.apply(item);
  const meta = upcoming ? upcomingMeta(shown) : null;
  // An upcoming season of a requested series: its poster opens the series, where the season can be requested.
  const season = item.season !== undefined && item.season !== null;
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

export const titleKey = (s: SeerrTitle) => `${s.mediaType}:${s.tmdbId}:${s.season ?? ''}`;
