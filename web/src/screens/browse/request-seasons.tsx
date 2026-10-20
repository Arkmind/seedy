import { useEffect, useMemo, useState } from 'preact/hooks';
import { ApiError } from '../../api/client';
import { tmdbImage } from '../../api/images';
import {
  seerrDetails, seerrRequest, seerrRequestOptions,
  type RequestBody, type RequestOptions, type SeerrDetails, type SeerrSeason, type SeerrStatus
} from '../../api/seedy';
import { dayOf, fmtDate, plural } from '../../lib/format';
import { t, tn } from '../../lib/i18n';
import { Switch } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { Sheet, toast } from '../../ui/overlay';
import './request-seasons.css';

/* Request seasons (Series details · request missing seasons, Search · request seasons boards). Every series request
   goes through here: the person sees which seasons are on the server or already asked for, picks the rest, and
   learns before sending whether it needs an admin's approval. */

export interface SeasonsTarget {
  tmdbId: number;
  title: string;
  year?: string;
  posterPath?: string;
  backdropPath?: string;
  /* The server's own art, for a series in the library. */
  posterUrl?: string;
  backdropUrl?: string;
}

export interface SeasonsResult { status: SeerrStatus; seasons: number[]; is4k: boolean }

type Kind = 'server' | 'pending' | 'missing' | 'soon' | 'blocked';

interface Row {
  n: number;
  name: string;
  sub: string;
  kind: Kind;
  status: string;
  locked: boolean;
  thumb?: string;
  airs?: Date;
}

/* The person's options rarely change; keep them a minute so opening the dialog again is instant. */
let optionsCache: { at: number; value: Promise<RequestOptions> } | null = null;
function loadOptions(): Promise<RequestOptions> {
  if (!optionsCache || Date.now() - optionsCache.at > 60_000) {
    const value = seerrRequestOptions('tv');
    optionsCache = { at: Date.now(), value };
    value.catch(() => { optionsCache = null; });
  }
  return optionsCache.value;
}

const yearOfDate = (s?: string) => { const d = dayOf(s); return d && !Number.isNaN(d.getTime()) ? d.getFullYear() : undefined; };

function rowsFor(seasons: SeerrSeason[], is4k: boolean, onServer?: Set<number>): Row[] {
  const today = new Date();
  return seasons.filter((s) => s.seasonNumber > 0).map((s) => {
    const st = is4k ? s.status4k : s.status;
    const who = is4k ? (s.requestedByMe4k ? t('you') : s.requestedBy4k) : (s.requestedByMe ? t('you') : s.requestedBy);
    const airs = dayOf(s.airDate);
    const year = yearOfDate(s.airDate);
    const unaired = !airs || airs.getTime() > today.getTime();
    const name = s.name || t('Season {n}', { n: s.seasonNumber });
    const thumb = tmdbImage(s.posterPath, 'w185');
    let sub: string;
    if (unaired) {
      const soon = airs && airs.getTime() - today.getTime() < 120 * 86_400_000;
      sub = !airs ? t('Announced') : soon ? t('Airs {date}', { date: fmtDate(airs) }) : t('Announced for {year}', { year: airs.getFullYear() });
    } else if (s.airedEpisodes !== undefined && s.airedEpisodes !== null && s.airedEpisodes < s.episodeCount) {
      sub = [t('{n} of {total} episodes aired', { n: s.airedEpisodes, total: s.episodeCount }), year].filter(Boolean).join(' · ');
    } else {
      sub = [s.episodeCount ? plural(s.episodeCount, 'episode') : '', year].filter(Boolean).join(' · ');
    }
    const base = { n: s.seasonNumber, name, sub, thumb, airs: unaired ? airs ?? undefined : undefined };
    if (st === 'available' || st === 'partial' || (!is4k && onServer?.has(s.seasonNumber))) return { ...base, kind: 'server', status: t('On the server'), locked: true };
    if (st === 'pending' || st === 'processing') return { ...base, kind: 'pending', status: who ? t('Requested by {name}', { name: who }) : t('Requested'), locked: true };
    if (st === 'blocklisted') return { ...base, kind: 'blocked', status: t('Blocked'), locked: true };
    if (unaired) return { ...base, kind: 'soon', status: t('Not aired yet'), locked: false };
    return { ...base, kind: 'missing', status: t('Not on the server'), locked: false };
  });
}

/* "Season 4", "Seasons 2 and 4", "Seasons 2, 3 and 5". */
export function seasonList(nums: number[]): string {
  const n = [...nums].sort((a, b) => a - b).map(String);
  if (n.length <= 1) return t('Season {n}', { n: n[0] ?? '' });
  return t('Seasons {list} and {last}', { list: n.slice(0, -1).join(', '), last: n[n.length - 1] });
}

export function RequestSeasonsDialog({ open, target, details: given, context, onServer, initial, meta, onClose, onDone }: {
  open: boolean;
  target: SeasonsTarget;
  /* Already loaded by the page; otherwise the dialog asks Seerr. */
  details?: SeerrDetails;
  /* "library": the series is on the server and some seasons aren't; "seerr": from Search, Discover or a Seerr title. */
  context: 'library' | 'seerr';
  /* Season numbers Jellyfin has, so a season counts as on the server even before Seerr notices. */
  onServer?: Set<number>;
  /* Seasons to start with ticked (default: every one that can be requested). */
  initial?: number[];
  /* Header line under the title; default "2021 · 3 seasons · Network". */
  meta?: string;
  onClose: () => void;
  onDone?: (r: SeasonsResult) => void;
}) {
  const [fetched, setFetched] = useState<SeerrDetails | undefined>(undefined);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [options, setOptions] = useState<RequestOptions | null | undefined>(undefined);
  const [is4k, setIs4k] = useState(false);
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [busy, setBusy] = useState(false);
  const [advOpen, setAdvOpen] = useState(false);
  const [adv, setAdv] = useState<{ serverId?: number; profileId?: number; rootFolder?: string; userId?: number }>({});

  const d = given ?? fetched;
  useEffect(() => {
    if (!open || given) return;
    let live = true;
    setFailed(false);
    seerrDetails('tv', target.tmdbId).then((r) => { if (live) setFetched(r); }, () => { if (live) setFailed(true); });
    return () => { live = false; };
  }, [open, target.tmdbId, attempt, !!given]);
  useEffect(() => {
    if (!open) return;
    let live = true;
    loadOptions().then((o) => { if (live) setOptions(o); }, () => { if (live) setOptions(null); });
    return () => { live = false; };
  }, [open]);

  const seasons = d?.seasons ?? [];
  const has4kOnServer = seasons.some((s) => s.status4k === 'available' || s.status4k === 'partial');
  const can4k = !!options?.can4k;

  // A fresh start each time the dialog opens: 4K follows what's on the server, every open season ticked.
  const rows = useMemo(() => rowsFor(seasons, is4k, onServer), [seasons, is4k, onServer]);
  const open_ = rows.filter((r) => !r.locked).map((r) => r.n);
  useEffect(() => { if (open) { setIs4k(false); setAdvOpen(false); setAdv({}); } }, [open]);
  useEffect(() => { if (open && can4k && has4kOnServer) setIs4k(true); }, [open, can4k, has4kOnServer]);
  useEffect(() => {
    if (!open) return;
    const start = initial?.filter((n) => open_.includes(n));
    // With a quota, only as many seasons as are left start ticked (the earliest first), so the dialog never
    // opens over the limit.
    const left = options?.quota?.remaining;
    setPicked(new Set((start && start.length ? start : open_).slice(0, left ?? undefined)));
  }, [open, d, is4k, options?.quota?.remaining]);

  const toggle = (n: number) => setPicked((p) => { const next = new Set(p); if (next.has(n)) next.delete(n); else next.add(n); return next; });
  const all = open_.length > 0 && open_.every((n) => picked.has(n));
  const some = !all && open_.some((n) => picked.has(n));
  const count = open_.filter((n) => picked.has(n)).length;

  const quota = options?.quota;
  const over = quota ? Math.max(0, count - quota.remaining) : 0;
  const approve = options ? (is4k ? options.autoApprove4k : options.autoApprove) : undefined;
  const unairedPicked = rows.filter((r) => r.kind === 'soon' && picked.has(r.n)).map((r) => r.n);

  /* Advanced: the server for this kind of request, its profile and folder, and who it's for. */
  const advanced = options?.advanced;
  const servers = (advanced?.servers ?? []).filter((s) => s.is4k === is4k);
  const server = servers.find((s) => s.id === adv.serverId) ?? servers.find((s) => s.isDefault) ?? servers[0];
  const profileId = adv.profileId ?? server?.profileId;
  const rootFolder = adv.rootFolder ?? server?.rootFolder;
  const forUser = adv.userId ?? advanced?.me;
  const forName = advanced?.users.find((u) => u.id === forUser)?.displayName;
  const advSummary = [
    server?.profiles.find((p) => p.id === profileId)?.name,
    rootFolder,
    forUser === advanced?.me || !forName ? t('requested as you') : t('requested for {name}', { name: forName })
  ].filter(Boolean).join(' · ');

  const title = target.title || d?.title || '';
  const network = d?.networks?.[0];
  const seasonCount = d?.numberOfSeasons ?? seasons.length;
  const headMeta = meta ?? [target.year ?? d?.year, seasonCount ? plural(seasonCount, 'season') : '', network].filter(Boolean).join(' · ');
  const backdrop = target.backdropUrl ?? tmdbImage(target.backdropPath ?? d?.backdropPath, 'w780');
  const poster = target.posterUrl ?? tmdbImage(target.posterPath ?? d?.posterPath, 'w185');

  const submit = async () => {
    if (!count || over) return;
    const list = open_.filter((n) => picked.has(n));
    const body: RequestBody = { mediaType: 'tv', tmdbId: target.tmdbId, seasons: list, ...(is4k ? { is4k: true } : {}) };
    if (adv.serverId !== undefined) body.serverId = adv.serverId;
    if (adv.profileId !== undefined) body.profileId = adv.profileId;
    if (adv.rootFolder !== undefined) body.rootFolder = adv.rootFolder;
    if (adv.userId !== undefined && adv.userId !== advanced?.me) body.userId = adv.userId;
    setBusy(true);
    try {
      const r = await seerrRequest(body);
      optionsCache = null;
      const what = list.length === open_.length && list.length === seasons.length ? title : t('{seasons} of {title}', { seasons: plural(list.length, 'season'), title });
      toast(is4k ? t('Requested {title} in 4K', { title: what }) : t('Requested {title}', { title: what }), r.status === 'processing' ? 'ok' : 'pending');
      onDone?.({ status: r.status, seasons: list, is4k });
      if (!given) setFetched((prev) => prev && markRequested(prev, list, is4k, r.status));
      onClose();
    } catch (e) {
      const msg = e instanceof ApiError && e.status === 403 ? t("Your profile can't request titles") : t("Couldn't request {title}", { title });
      toast(msg, 'error');
    } finally {
      setBusy(false);
    }
  };

  const noteText = approve === undefined ? ''
    : approve
      ? [t('Approved automatically.'), unairedPicked.length
        ? tn(unairedPicked.length, '{season} downloads once it airs.', '{season} download once they air.', { season: seasonList(unairedPicked) })
        : ''].filter(Boolean).join(' ')
      : t("An admin approves requests before they download. You'll get a notification when it's ready.");

  return (
    <Sheet open={open} onClose={onClose} labelledBy="rq-title" class="rq" scrimClass="rq-scrim">
      <div class="rq-head">
        {backdrop && <img class="rq-head-art" src={backdrop} alt="" />}
        <div aria-hidden="true" class="rq-head-shade" />
        <div class="rq-poster">{poster ? <img src={poster} alt="" /> : null}</div>
        <div class="rq-head-text">
          <span class="rq-eyebrow">{context === 'library' ? t('Request missing seasons') : t('Request from Seerr')}</span>
          <h2 id="rq-title" class="rq-title">{title}</h2>
          <span class="rq-meta"><span class="rq-type">{t('SERIES')}</span>{headMeta}</span>
        </div>
        <button type="button" class="rq-close" aria-label={t('Close')} onClick={onClose}><Icon name="close" size={18} stroke={2.4} /></button>
      </div>

      <div class="rq-body">
        {quota && (
          <div class="rq-quota">
            <div class="rq-quota-line">
              <span class="rq-quota-name">
                {quota.days === 7 ? t('Season requests this week') : quota.days === 1 ? t('Season requests today') : tn(quota.days, 'Season requests, per {n} day', 'Season requests, per {n} days')}
              </span>
              <span class={over ? 'is-over' : undefined}>
                {over
                  ? tn(over, '{left} of {limit} left · {n} season too many', '{left} of {limit} left · {n} seasons too many', { left: quota.remaining, limit: quota.limit })
                  : t('{left} of {limit} left · {after} after this request', { left: quota.remaining, limit: quota.limit, after: quota.remaining - count })}
              </span>
            </div>
            <div class="rq-quota-bar" role="img" aria-label={t('{used} used, {count} in this request, {left} left', { used: quota.used, count, left: Math.max(0, quota.remaining - count) })}>
              <span style={{ width: pctOf(quota.used, quota.limit) }} />
              <span class={over ? 'is-over' : undefined} style={{ width: pctOf(Math.min(count, quota.limit - quota.used), quota.limit) }} />
            </div>
          </div>
        )}

        <div class="rq-seasons-head">
          <h3 id="rq-seasons" class="rq-seasons-title">{t('Seasons')}</h3>
          {open_.length > 1 && (
            <button
              type="button"
              role="checkbox"
              aria-checked={all ? 'true' : some ? 'mixed' : 'false'}
              class="rq-all"
              onClick={() => setPicked(all ? new Set() : new Set(open_))}
            >
              {context === 'library'
                ? tn(open_.length, 'Select the {n} missing season', 'Select all {n} missing seasons')
                : tn(open_.length, 'Select the {n} open season', 'Select all {n} open seasons')}
              <span aria-hidden="true" class={'rq-box' + (all || some ? ' is-on' : '')}><Icon name={some ? 'minus' : 'check'} size={15} stroke={3.2} /></span>
            </button>
          )}
        </div>

        {failed && !d ? (
          <div class="rq-error" role="alert">
            <span>{t("Couldn't load the seasons from Seerr.")}</span>
            <button type="button" class="btn btn-secondary is-sm" onClick={() => setAttempt((n) => n + 1)}><Icon name="refresh" size={16} />{t('Try again')}</button>
          </div>
        ) : !d ? (
          <div class="rq-rows" aria-busy="true" aria-label={t('Loading seasons')}>
            {Array.from({ length: 3 }, (_, i) => <div key={i} class="rq-row is-skel skel" />)}
          </div>
        ) : rows.length === 0 ? (
          <p class="rq-empty">{t('Seerr doesn’t list any seasons for this series yet.')}</p>
        ) : (
          <div class="rq-rows" role="group" aria-labelledby="rq-seasons">
            {rows.map((r) => {
              const on = !r.locked && picked.has(r.n);
              const state = r.locked ? 'is-locked' : on ? 'is-on' : 'is-off';
              return (
                <button
                  key={r.n}
                  type="button"
                  role="checkbox"
                  class={'rq-row ' + state}
                  aria-checked={on || r.locked ? 'true' : 'false'}
                  aria-disabled={r.locked ? 'true' : undefined}
                  aria-label={`${r.name}, ${r.sub ? r.sub + ', ' : ''}${r.status}`}
                  onClick={() => { if (!r.locked) toggle(r.n); }}
                >
                  <span aria-hidden="true" class={'rq-box' + (on ? ' is-on' : r.locked ? ' is-locked' : '')}><Icon name="check" size={16} stroke={3.2} /></span>
                  <span aria-hidden="true" class="rq-thumb">{r.thumb && <img src={r.thumb} alt="" loading="lazy" />}</span>
                  <span class="rq-row-text">
                    <span class="rq-row-name">{r.name}</span>
                    {r.sub && <span class="rq-row-sub">{r.sub}</span>}
                  </span>
                  <span class={'rq-status is-' + r.kind}><span class="rq-dot" />{r.status}</span>
                </button>
              );
            })}
          </div>
        )}

        {can4k && (
          <div class="rq-option">
            <span class="rq-option-text">
              <span class="rq-option-name" id="rq-4k">{t('Request in 4K')}</span>
              <span class="rq-option-help">{has4kOnServer ? t('Matches the 4K seasons already on the server') : t('Sent to the 4K server as its own request')}</span>
            </span>
            <Switch checked={is4k} onChange={(v) => { setIs4k(v); setAdv((a) => ({ userId: a.userId })); }} label={t('Request in 4K')} />
          </div>
        )}

        {advanced && (servers.length > 0 || advanced.users.length > 1) && (
          <div class={'rq-adv' + (advOpen ? ' is-open' : '')}>
            <button type="button" class="rq-adv-toggle" aria-expanded={advOpen ? 'true' : 'false'} aria-controls="rq-adv-body" onClick={() => setAdvOpen((v) => !v)}>
              <Icon name="sliders" size={18} color="var(--seedy-text-2)" />
              <span class="rq-option-text">
                <span class="rq-option-name">{t('Advanced')}</span>
                <span class="rq-option-help">{advSummary}</span>
              </span>
              <Icon name="chevronDown" size={18} stroke={2.4} color="var(--seedy-text-2)" class="rq-adv-chev" />
            </button>
            {advOpen && (
              <div class="rq-adv-body" id="rq-adv-body">
                {servers.length > 1 && (
                  <AdvField label={t('Server')} value={server?.id ?? ''} options={servers.map((s) => ({ value: s.id, label: s.name }))}
                    onChange={(v) => setAdv((a) => ({ userId: a.userId, serverId: Number(v) }))} />
                )}
                {server && server.profiles.length > 0 && (
                  <AdvField label={t('Quality profile')} value={profileId ?? ''} options={server.profiles.map((p) => ({ value: p.id, label: p.name }))}
                    onChange={(v) => setAdv((a) => ({ ...a, profileId: Number(v) }))} />
                )}
                {server && server.rootFolders.length > 0 && (
                  <AdvField label={t('Folder')} value={rootFolder ?? ''} options={server.rootFolders.map((f) => ({ value: f.path, label: f.freeSpace ? `${f.path} · ${t('{size} free', { size: freeSize(f.freeSpace) })}` : f.path }))}
                    onChange={(v) => setAdv((a) => ({ ...a, rootFolder: String(v) }))} />
                )}
                {advanced.users.length > 1 && (
                  <AdvField label={t('Request for')} value={forUser ?? ''} options={advanced.users.map((u) => ({ value: u.id, label: u.id === advanced.me ? t('{name} (you)', { name: u.displayName }) : u.displayName }))}
                    onChange={(v) => setAdv((a) => ({ ...a, userId: Number(v) }))} />
                )}
              </div>
            )}
          </div>
        )}
      </div>

      <div class="rq-foot">
        <span class="rq-note">
          {approve !== undefined && (
            <span aria-hidden="true" class={'rq-note-icon ' + (approve ? 'is-ok' : 'is-pending')}>
              {approve
                ? <svg viewBox="0 0 24 24" width="12" height="12"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
                : <svg viewBox="0 0 24 24" width="12" height="12"><path d="M12 7v5l3 2" /></svg>}
            </span>
          )}
          {noteText}
        </span>
        <div class="rq-buttons">
          <button type="button" class="rq-cancel" onClick={onClose}>{t('Cancel')}</button>
          <button type="button" class="btn btn-primary rq-send" disabled={busy || !count || over > 0 || !d} onClick={submit}>
            <Icon name="request" size={18} />
            {busy ? t('Requesting…') : count ? tn(count, 'Request {n} season', 'Request {n} seasons') : t('Request seasons')}
          </button>
        </div>
      </div>
    </Sheet>
  );
}

function AdvField({ label, value, options, onChange }: { label: string; value: string | number; options: { value: string | number; label: string }[]; onChange: (v: string) => void }) {
  return (
    <label class="rq-field">
      <span class="rq-field-name">{label}</span>
      <span class="select-wrap">
        <select class="select" value={String(value)} onChange={(e) => onChange((e.currentTarget as HTMLSelectElement).value)}>
          {options.map((o) => <option key={String(o.value)} value={String(o.value)}>{o.label}</option>)}
        </select>
        <Icon name="chevronDown" size={16} />
      </span>
    </label>
  );
}

const pctOf = (n: number, of: number) => `${of > 0 ? Math.max(0, Math.min(100, (n / of) * 100)) : 0}%`;

function freeSize(bytes: number): string {
  const tb = bytes / 1024 ** 4;
  return tb >= 1 ? `${tb.toFixed(1)} TB` : `${Math.round(bytes / 1024 ** 3)} GB`;
}

/* The seasons just asked for, as Seerr will show them on the next load. */
export function markRequested(d: SeerrDetails, seasons: number[], is4k: boolean, status: SeerrStatus): SeerrDetails {
  const picked = new Set(seasons);
  const next: SeerrStatus = status === 'available' ? 'available' : status === 'processing' ? 'processing' : 'pending';
  return {
    ...d,
    seasons: d.seasons?.map((s) => (!picked.has(s.seasonNumber) ? s : is4k
      ? { ...s, status4k: next, requestedBy4k: undefined, requestedByMe4k: true }
      : { ...s, status: next, requestedBy: undefined, requestedByMe: true }))
  };
}
