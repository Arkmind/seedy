import type { ComponentChildren } from 'preact';
import { useEffect, useMemo, useState } from 'preact/hooks';
import { mediaList, type MediaRequest } from '../../api/media';
import { tmdbImage } from '../../api/images';
import { requestList, type SeerrRequest } from '../../api/seedy';
import { dayOf, daysFromToday, fmtMonthYear, fmtWeekdayDate } from '../../lib/format';
import { t, tx } from '../../lib/i18n';
import { Link, navigate, setQuery, useLocation } from '../../lib/router';
import { seerrTarget } from '../../ui/cards';
import { Icon } from '../../ui/icons';
import { MenuButton, MenuItem } from '../../ui/overlay';
import { EmptyState, ErrorState } from '../browse/parts';
import { seasonList } from '../browse/request-seasons';
import { NotSetUp, Shell, useServices, ViewTabs, type Service } from './frame';
import {
  actLine, mediaKindLine, mediaName, mediaNote, readyAction, RequestThumb, SERVICE_NAMES, useMediaActions, type MediaActions
} from './media-parts';
import { refreshSummary, useRequestActions, useSummary, type RequestActions } from './parts';
import { meStore } from '../../lib/app-state';
import { useStore } from '../../lib/store';

/* My requests and All requests (RequestsMine board): everything asked for, from Seerr, Lidarr, Shelfarr and Librarr,
   in one list. Each row shows where the request is on its way (Asked → Approved → Searching → Downloading → Ready),
   what's happening now, and what the person can do about it. */

type Show = 'all' | 'open' | 'pending' | 'attention' | 'done';
const SHOWS: { value: Show; label: string }[] = [
  { value: 'all', label: 'Open and finished' },
  { value: 'open', label: 'Open only' },
  { value: 'pending', label: 'Waiting for approval' },
  { value: 'attention', label: 'Needs a look' },
  { value: 'done', label: 'Finished' }
];
const STAGES = ['Asked', 'Approved', 'Searching', 'Downloading', 'Ready'];

interface Item {
  key: string;
  service: Service;
  at: number;
  seerr?: SeerrRequest;
  media?: MediaRequest;
}

interface Stage { n: number; fail?: boolean; label?: string }

/* ── Seerr requests on the pipeline ── */

function seerrStage(r: SeerrRequest): Stage {
  if (r.status === 'available') return { n: 5 };
  if (r.requestStatus === 'declined') return { n: 2, fail: true, label: t('Declined') };
  if (r.requestStatus === 'failed') return { n: 3, fail: true };
  if (r.requestStatus === 'pending') return { n: 1 };
  if (r.download || r.status === 'partial') return { n: 4 };
  if (r.releaseDate || (r.unaired?.length && r.unaired.length >= (r.seasons?.length ?? 0))) return { n: 2 };
  return { n: 3 };
}

const aheadDate = (d: Date) => (daysFromToday(d) <= 31 ? fmtWeekdayDate(d) : fmtMonthYear(d));

function seerrNote(r: SeerrRequest): { text: string; tone: string } | null {
  if (r.status === 'available') return null;
  if (r.requestStatus === 'pending') return { text: t('Waiting for an admin'), tone: 'pending' };
  if (r.requestStatus === 'declined') return { text: t('Declined by an admin'), tone: 'danger' };
  if (r.requestStatus === 'failed') return { text: t('Sonarr or Radarr couldn’t add it'), tone: 'danger' };
  const parts: string[] = [];
  if (r.download) {
    const p = Math.round(r.download.fraction * 100);
    parts.push(r.download.season ? t('Season {n} at {p}%', { n: r.download.season, p }) : t('{p}% downloaded', { p }));
  }
  const unaired = r.unaired ?? [];
  if (unaired.length) {
    const what = seasonList(unaired);
    parts.push(unaired.length === 1 ? t('{seasons} waits for its air date', { seasons: what.toLocaleLowerCase() }) : t('{seasons} wait for their air dates', { seasons: what.toLocaleLowerCase() }));
  }
  const out = dayOf(r.releaseDate);
  if (out) parts.push(t('Out {date}', { date: aheadDate(out) }));
  return parts.length ? { text: parts.join(' · '), tone: r.download ? 'accent' : 'light' } : null;
}

function seerrWhat(r: SeerrRequest, everyone: boolean): string {
  const what = r.mediaType === 'tv' ? (r.seasons?.length ? seasonList(r.seasons) : t('All seasons')) : t('Movie');
  const who = r.requestedByMe ? t('you') : r.requestedBy || t('someone');
  return [what, r.is4k ? '4K' : '', everyone ? actLine('askedBy', r.requestedAt, who) : actLine('asked', r.requestedAt)].filter(Boolean).join(' · ');
}

/* ── Media requests on the pipeline ── */

function mediaStage(r: MediaRequest): Stage {
  switch (r.status) {
    case 'pending': return { n: 1 };
    case 'declined': return { n: 2, fail: true, label: t('Declined') };
    case 'approved': return { n: 2, fail: r.attention };
    case 'searching': return { n: 3, fail: r.attention };
    case 'downloading': return { n: 4, fail: r.attention };
    case 'failed': return { n: 3, fail: true };
    default: return { n: 5 };
  }
}

function mediaWhatLine(r: MediaRequest, everyone: boolean): string {
  const who = r.requestedByMe ? t('you') : r.requestedBy || t('someone');
  return [mediaKindLine(r), everyone ? actLine('askedBy', r.requestedAt, who) : actLine('asked', r.requestedAt)].filter(Boolean).join(' · ');
}

/* ── Page ── */

function matchesShow(item: Item, show: Show): boolean {
  const st = item.media ? mediaStage(item.media) : seerrStage(item.seerr!);
  const pending = item.media ? item.media.status === 'pending' : item.seerr!.requestStatus === 'pending';
  switch (show) {
    case 'open': return st.n < 5 && !(st.fail && st.label);
    case 'pending': return pending;
    case 'attention': return !!st.fail;
    case 'done': return st.n === 5;
    default: return true;
  }
}

export function RequestsMine({ scope }: { scope: 'mine' | 'all' }) {
  const services = useServices();
  const me = useStore(meStore);
  const loc = useLocation();
  const everyone = scope === 'all';
  const type = (['movies', 'music', 'books'] as Service[]).find((s) => s === loc.query.get('type') && services.includes(s)) ?? null;
  const show = SHOWS.find((s) => s.value === loc.query.get('show'))?.value ?? 'all';
  const seerrSummary = useSummary(services.includes('movies'));

  const [seerr, setSeerr] = useState<SeerrRequest[] | null>(null);
  const [media, setMedia] = useState<MediaRequest[] | null>(null);
  const [error, setError] = useState(false);
  const [tick, setTick] = useState(0);

  const useSeerr = services.includes('movies') && (!everyone || seerrSummary?.canViewAll !== false);
  const useMedia = services.includes('music') || services.includes('books');
  useEffect(() => {
    let live = true;
    setError(false);
    const s = useSeerr
      ? requestList({ scope, filter: 'all', sort: 'added', skip: 0, take: 60 }).then((r) => r.results, () => { if (live) setError(true); return [] as SeerrRequest[]; })
      : Promise.resolve([] as SeerrRequest[]);
    const m = useMedia && (!everyone || me.isAdmin)
      ? mediaList(scope).then((r) => r.results, () => { if (live) setError(true); return [] as MediaRequest[]; })
      : Promise.resolve([] as MediaRequest[]);
    void s.then((list) => { if (live) setSeerr(list); });
    void m.then((list) => { if (live) setMedia(list); });
    return () => { live = false; };
  }, [scope, tick, useSeerr, useMedia]);

  const items = useMemo<Item[]>(() => {
    const list: Item[] = [
      ...(seerr ?? []).map((r) => ({ key: 's' + r.requestId, service: 'movies' as Service, at: new Date(r.requestedAt).getTime(), seerr: r })),
      ...(media ?? []).map((r) => ({ key: 'm' + r.id, service: (r.kind === 'music' ? 'music' : 'books') as Service, at: new Date(r.requestedAt).getTime(), media: r }))
    ];
    return list.sort((a, b) => b.at - a.at);
  }, [seerr, media]);

  const shown = items.filter((i) => matchesShow(i, show));
  const counts = (s: Service | null) => shown.filter((i) => !s || i.service === s).length;
  const visible = shown.filter((i) => !type || i.service === type);

  const seerrActions = useRequestActions((r, next) => setSeerr((list) => (list ?? []).flatMap((x) => (x.requestId !== r.requestId ? [x] : next ? [next] : []))));
  const mediaActions = useMediaActions((r, next) => setMedia((list) => (list ?? []).flatMap((x) => (x.id !== r.id ? [x] : next ? [next] : []))));

  const loading = (useSeerr && seerr === null) || (useMedia && media === null);
  let body: ComponentChildren;
  if (!services.length) body = <NotSetUp />;
  else if (loading) body = <div class="reqs-mine-list">{[0, 1, 2, 3, 4].map((i) => <div key={i} class="reqs-mine-row is-skel skel" aria-hidden="true" />)}</div>;
  else if (error && !items.length) body = <ErrorState title={t('Couldn’t load requests')} retry={() => setTick((n) => n + 1)} />;
  else if (!visible.length) {
    body = show !== 'all' || type
      ? <EmptyState icon="inbox" title={t('No requests here')} text={t('Nothing matches what you picked.')} action={<button type="button" class="btn btn-secondary" onClick={() => setQuery({ show: undefined, type: undefined })}>{t('Show everything')}</button>} />
      : <EmptyState icon="inbox" title={everyone ? t('No requests yet') : t('You haven’t requested anything yet')} text={everyone ? t('Requests from everyone on the server show up here.') : t('What you request shows up here, with where it is on its way to the server.')} action={<Link to="/requests" class="btn btn-secondary">{t('Find something to request')}</Link>} />;
  } else {
    body = (
      <div class="reqs-mine-list">
        {visible.map((i) => (i.media
          ? <MediaPipelineRow key={i.key} r={i.media} everyone={everyone} actions={mediaActions} />
          : <SeerrPipelineRow key={i.key} r={i.seerr!} everyone={everyone} actions={seerrActions} canManage={!!seerrSummary?.canManage} />))}
      </div>
    );
  }

  const chips: { id: Service | null; label: string }[] = [
    { id: null, label: 'Everything' },
    ...(services.includes('movies') ? [{ id: 'movies' as Service, label: 'Movies and shows' }] : []),
    ...(services.includes('music') ? [{ id: 'music' as Service, label: 'Music' }] : []),
    ...(services.includes('books') ? [{ id: 'books' as Service, label: 'Books' }] : [])
  ];
  const showLabel = SHOWS.find((s) => s.value === show)!.label;
  return (
    <Shell service={null}>
      <div class="reqs-bar">
        <ViewTabs service={null} tab={everyone ? 'all' : 'my'} />
      </div>
      <div class="reqs-pad reqs-mine-filters">
        <nav aria-label={t('Show requests for')} class="tab-row hscroll">
          {chips.length > 2 && chips.map((c) => (
            <Link
              key={c.id ?? 'all'}
              to={(everyone ? '/requests/all' : '/requests/my') + query({ type: c.id ?? undefined, show: show === 'all' ? undefined : show })}
              class="tab"
              aria-current={type === c.id ? 'page' : undefined}
            >
              {t('{label} · {n}', { label: t(c.label), n: counts(c.id) })}
            </Link>
          ))}
        </nav>
        <MenuButton
          width={240}
          align="end"
          button={({ ref, open, toggle }) => (
            <button ref={ref} type="button" class="reqs-show" aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={toggle}>
              <span class="reqs-show-label">{tx('Show {what}', { what: <span class="reqs-show-value">{t(showLabel)}</span> })}</span>
              <Icon name="chevronDown" size={16} stroke={2.4} />
            </button>
          )}
        >
          {(close) => SHOWS.map((s) => (
            <MenuItem key={s.value} label={t(s.label)} pressed={s.value === show} onSelect={() => { close(); setQuery({ show: s.value === 'all' ? undefined : s.value }); }} />
          ))}
        </MenuButton>
      </div>
      <div class="reqs-pad">{body}</div>
    </Shell>
  );
}

const query = (q: Record<string, string | undefined>) => {
  const s = new URLSearchParams();
  for (const [k, v] of Object.entries(q)) if (v) s.set(k, v);
  const out = s.toString();
  return out ? '?' + out : '';
};

/* ── Rows ── */

function Pipeline({ stage }: { stage: Stage }) {
  const label = (i: number) => (stage.label && i === stage.n - 1 ? stage.label : t(STAGES[i]!));
  return (
    <ol class="reqs-pipe" aria-label={t('Stage {n} of 5: {stage}', { n: stage.n, stage: label(stage.n - 1) })}>
      {STAGES.map((_, i) => {
        const cls = i < stage.n ? (stage.fail && i === stage.n - 1 ? 'is-fail' : i === 4 ? 'is-ready' : 'is-done') : 'is-todo';
        return (
          <li key={i} class={cls + (i === stage.n - 1 ? ' is-current' : '')}>
            <span class="reqs-pipe-bar" aria-hidden="true" />
            <span class="reqs-pipe-label" title={label(i)}>{label(i)}</span>
          </li>
        );
      })}
    </ol>
  );
}

function RowFrame({ thumb, title, service, what, note, stage, main, second, more, attention }: {
  thumb: ComponentChildren; title: ComponentChildren; service: string; what: string; note: { text: string; tone: string } | null; stage: Stage;
  main?: ComponentChildren; second?: ComponentChildren; more: ComponentChildren; attention?: boolean;
}) {
  return (
    <article class={'reqs-mine-row' + (attention ? ' is-attention' : '')}>
      <div class="reqs-mine-who">
        <span class="reqs-mine-thumb" aria-hidden="true">{thumb}</span>
        <span class="reqs-mine-text">
          <span class="reqs-mine-title"><span class="reqs-mine-name">{title}</span><span class="reqs-service-badge">{service}</span></span>
          <span class="reqs-mine-what">{what}</span>
          {note && note.text && <span class={'reqs-mine-note is-' + note.tone}>{note.text}</span>}
        </span>
      </div>
      <Pipeline stage={stage} />
      <div class="reqs-mine-acts">
        {main}
        {second}
        {more}
      </div>
    </article>
  );
}

function MoreMenu({ label, children }: { label: string; children: (close: () => void) => ComponentChildren }) {
  return (
    <MenuButton
      width={260}
      align="end"
      button={({ ref, open, toggle }) => (
        <button ref={ref} type="button" class="reqs-more" aria-label={label} aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={toggle}>
          <Icon name="more" size={18} />
        </button>
      )}
    >
      {children}
    </MenuButton>
  );
}

function MediaPipelineRow({ r, everyone, actions }: { r: MediaRequest; everyone: boolean; actions: MediaActions }) {
  const stage = mediaStage(r);
  const note = mediaNote(r);
  const ready = readyAction(r);
  const busy = actions.busy(r);
  const name = mediaName(r);
  const mine = r.requestedByMe;
  let main: ComponentChildren = null;
  let second: ComponentChildren = null;
  if (ready) main = <Link to={ready.to} class="reqs-act is-main">{ready.label}</Link>;
  else if (r.canDecide && (everyone || !mine)) {
    main = <button type="button" class="reqs-act is-main" disabled={busy} onClick={() => actions.approve(r)}>{t('Approve')}</button>;
    second = <button type="button" class="reqs-act" disabled={busy} onClick={() => actions.decline(r)}>{t('Decline')}</button>;
  } else if (r.canSearch && (r.attention || r.status === 'failed')) {
    main = <button type="button" class="reqs-act is-main" disabled={busy} onClick={() => actions.search(r)}>{t('Search now')}</button>;
    if (r.lowerQuality) second = <button type="button" class="reqs-act" disabled={busy} onClick={() => actions.acceptLower(r)}>{t('Accept {quality}', { quality: r.lowerQuality })}</button>;
  } else if (mine && r.status === 'pending') {
    second = <button type="button" class="reqs-act" disabled={busy} onClick={() => actions.remove(r)}>{t('Cancel request')}</button>;
  }
  const urls = useStore(meStore).media;
  const appUrl = urls?.[`${r.service}Url` as 'lidarrUrl' | 'shelfarrUrl' | 'librarrUrl' | 'readarrUrl'];
  return (
    <RowFrame
      thumb={<RequestThumb r={r} size="mine" />}
      title={name}
      service={SERVICE_NAMES[r.service]}
      what={mediaWhatLine(r, everyone)}
      note={note}
      stage={stage}
      main={main}
      second={second}
      attention={stage.fail && r.status !== 'declined'}
      more={(
        <MoreMenu label={t('More for {title}', { title: name })}>
          {(close) => (
            <>
              {r.itemId && <MenuItem icon="play" label={t('Open on the server')} onSelect={() => { close(); navigate(`/details/${r.itemId}`); }} />}
              {r.canSearch && <MenuItem icon="refresh" label={t('Search now')} onSelect={() => { close(); actions.search(r); }} />}
              {r.lowerQuality && r.canSearch && <MenuItem icon="quality" label={t('Accept {quality}', { quality: r.lowerQuality })} onSelect={() => { close(); actions.acceptLower(r); }} />}
              {appUrl && <MenuItem icon="external" label={t('Open {app}', { app: SERVICE_NAMES[r.service] })} href={appUrl} />}
              {r.canCancel && <MenuItem icon="close" danger label={mine && r.status === 'pending' ? t('Cancel request') : t('Remove request')} onSelect={() => { close(); actions.remove(r); }} />}
              {!r.itemId && !r.canSearch && !appUrl && !r.canCancel && <MenuItem label={t('Nothing to do here yet')} disabled />}
            </>
          )}
        </MoreMenu>
      )}
    />
  );
}

function SeerrPipelineRow({ r, everyone, actions, canManage }: { r: SeerrRequest; everyone: boolean; actions: RequestActions; canManage: boolean }) {
  const stage = seerrStage(r);
  const busy = actions.busy(r);
  const poster = tmdbImage(r.posterPath, 'w185');
  let main: ComponentChildren = null;
  let second: ComponentChildren = null;
  if (r.status === 'available' && r.jellyfinItemId) {
    main = <Link to={r.mediaType === 'movie' ? `/play/${r.jellyfinItemId}` : `/details/${r.jellyfinItemId}`} class="reqs-act is-main">{t('Play')}</Link>;
  } else if (canManage && r.requestStatus === 'pending' && (everyone || !r.requestedByMe)) {
    main = <button type="button" class="reqs-act is-main" disabled={busy} onClick={() => actions.approve(r)}>{t('Approve')}</button>;
    second = <button type="button" class="reqs-act" disabled={busy} onClick={() => actions.decline(r)}>{t('Decline')}</button>;
  } else if (canManage && r.requestStatus === 'failed') {
    main = <button type="button" class="reqs-act is-main" disabled={busy} onClick={() => actions.retry(r)}>{t('Retry')}</button>;
  } else if (r.requestedByMe && r.requestStatus === 'pending') {
    second = <button type="button" class="reqs-act" disabled={busy} onClick={() => actions.remove(r)}>{t('Cancel request')}</button>;
  }
  return (
    <RowFrame
      thumb={<span class="reqs-mine-poster">{poster && <img src={poster} alt="" loading="lazy" />}</span>}
      title={r.title}
      service="Seerr"
      what={seerrWhat(r, everyone)}
      note={seerrNote(r)}
      stage={stage}
      main={main}
      second={second}
      attention={stage.fail && r.requestStatus === 'failed'}
      more={(
        <MoreMenu label={t('More for {title}', { title: r.title })}>
          {(close) => (
            <>
              <MenuItem icon="info" label={t('Open {title}', { title: r.title })} onSelect={() => { close(); navigate(seerrTarget(r)); }} />
              {canManage && r.requestStatus === 'failed' && <MenuItem icon="refresh" label={t('Retry')} onSelect={() => { close(); actions.retry(r); }} />}
              {(canManage || (r.requestedByMe && r.requestStatus === 'pending')) && (
                <MenuItem icon="close" danger label={canManage ? t('Remove request') : t('Cancel request')} onSelect={() => { close(); actions.remove(r); void refreshSummary(); }} />
              )}
            </>
          )}
        </MoreMenu>
      )}
    />
  );
}
