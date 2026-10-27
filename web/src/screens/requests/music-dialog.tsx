import type { ComponentChildren } from 'preact';
import { useMemo, useState } from 'preact/hooks';
import { mediaCreate, mediaOptions, musicArtist, type MediaOptions, type MusicArtist, type MusicRelease } from '../../api/media';
import { dayOf, fmtDate } from '../../lib/format';
import { t, tn } from '../../lib/i18n';
import { useAsync } from '../../lib/store';
import { Switch } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { Sheet, toast } from '../../ui/overlay';
import { AlbumArt, cardStatus, initials, noteRequest, refreshMediaSummary } from './media-parts';
import type { MusicTarget } from './music';

/* Request an artist (RequestMusicArtist board): the whole discography, picked albums, or only what comes out from
   now on. Albums on the server or already asked for are locked; the quality is the admin's; the person's weekly
   album limit and whether an admin approves first are shown before sending. */

type Mode = 'artist' | 'albums' | 'future';
type Type = 'album' | 'ep' | 'single';
const TYPES: { id: Type; one: string; many: string }[] = [
  { id: 'album', one: 'Albums · {n}', many: 'Albums · {n}' },
  { id: 'ep', one: 'EPs · {n}', many: 'EPs · {n}' },
  { id: 'single', one: 'Singles · {n}', many: 'Singles · {n}' }
];

/* "2026 · 11 songs · 44 min", or "Out 6 Nov 2026" for what isn't out yet. */
function releaseSub(r: MusicRelease): string {
  if (r.upcoming) {
    const d = dayOf(r.date);
    return d && r.date!.length >= 10 ? t('Out {date}', { date: fmtDate(d) }) : t('Announced');
  }
  const parts = [r.date?.slice(0, 4) ?? ''];
  if (r.secondary?.includes('live')) parts.push(t('Live'));
  else if (r.secondary?.includes('soundtrack')) parts.push(t('Soundtrack'));
  if (r.tracks) parts.push(tn(r.tracks, '{n} song', '{n} songs'));
  if (r.lengthMs) parts.push(t('{n} min', { n: Math.round(r.lengthMs / 60000) }));
  return parts.filter(Boolean).join(' · ');
}

const locked = (r: MusicRelease) => r.status !== 'none';

export function RequestMusicArtist({ target, onClose }: { target: MusicTarget; onClose: () => void }) {
  const state = useAsync(() => Promise.all([musicArtist(target.mbid), mediaOptions('music')]), [target.mbid]);
  const data = state.data?.[0];
  const opts = state.data?.[1];
  const pre = data?.releases.find((r) => r.mbid === target.preselect);
  return (
    <Sheet open onClose={onClose} class="reqs-dlg" scrimClass="reqs-dlg-scrim" labelledBy="reqs-dlg-h">
      {!data || !opts ? (
        <Loading name={target.name} error={state.error} onRetry={state.reload} onClose={onClose} />
      ) : (
        <Body key={data.artist.mbid} data={data} opts={opts} preselect={pre} imageUrl={target.imageUrl} onClose={onClose} />
      )}
    </Sheet>
  );
}

function Head({ name, art, meta, onClose }: { name: string; art: ComponentChildren; meta: ComponentChildren; onClose: () => void }) {
  return (
    <div class="reqs-dlg-head">
      {art}
      <div class="reqs-dlg-head-text">
        <span class="reqs-dlg-eyebrow">{t('Request from Lidarr')}</span>
        <h2 id="reqs-dlg-h" class="reqs-dlg-title">{name}</h2>
        <span class="reqs-dlg-meta">{meta}</span>
      </div>
      <button type="button" class="reqs-dlg-close" aria-label={t('Close')} onClick={onClose}><Icon name="close" size={18} stroke={2.2} /></button>
    </div>
  );
}

function Loading({ name, error, onRetry, onClose }: { name?: string; error?: Error; onRetry: () => void; onClose: () => void }) {
  return (
    <>
      <Head
        name={name ?? t('Loading…')}
        art={<span class="reqs-dlg-avatar skel" />}
        meta={error ? t('MusicBrainz didn’t answer.') : <span class="skel skel-line" style={{ width: '260px' }} />}
        onClose={onClose}
      />
      <div class="reqs-dlg-body">
        {error ? (
          <div class="rq-error">
            <span>{error.message || t('Couldn’t load this artist.')}</span>
            <button type="button" class="btn btn-secondary is-sm" onClick={onRetry}>{t('Try again')}</button>
          </div>
        ) : (
          <>
            <span class="reqs-dlg-seg skel" style={{ height: '50px' }} />
            {[0, 1, 2, 3].map((i) => <span key={i} class="reqs-dlg-row is-skel skel" />)}
          </>
        )}
      </div>
    </>
  );
}

function Body({ data, opts, preselect, imageUrl, onClose }: { data: MusicArtist; opts: MediaOptions; preselect?: MusicRelease; imageUrl?: string; onClose: () => void }) {
  const a = { ...data.artist, imageUrl: data.artist.imageUrl ?? imageUrl };
  const counts = useMemo(() => {
    const c: Record<Type, number> = { album: 0, ep: 0, single: 0 };
    for (const r of data.releases) c[r.type] = (c[r.type] ?? 0) + 1;
    return c;
  }, [data]);
  const [mode, setMode] = useState<Mode>('albums');
  const [type, setType] = useState<Type>(preselect?.type ?? (counts.album ? 'album' : counts.ep ? 'ep' : 'single'));
  const [picked, setPicked] = useState<Set<string>>(() => new Set(preselect && !locked(preselect) ? [preselect.mbid] : []));
  const [keepNew, setKeepNew] = useState(true);
  const [busy, setBusy] = useState(false);

  // Studio releases first, newest first; live recordings and soundtracks after them.
  const extra = (r: MusicRelease) => (r.secondary?.length ? 1 : 0);
  const rows = data.releases.filter((r) => r.type === type).sort((x, y) => extra(x) - extra(y));
  const open = rows.filter((r) => !locked(r));
  const allOn = open.length > 0 && open.every((r) => picked.has(r.mbid));
  const wholeCount = data.releases.filter((r) => !locked(r) && !r.upcoming && !r.secondary?.length && (r.type === 'album' || r.type === 'ep')).length;
  const units = mode === 'albums' ? picked.size : mode === 'artist' ? Math.max(1, wholeCount) : 0;

  const toggle = (id: string) => setPicked((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n; });
  const toggleAll = () => setPicked((s) => {
    const n = new Set(s);
    for (const r of open) { if (allOn) n.delete(r.mbid); else n.add(r.mbid); }
    return n;
  });

  const limit = opts.limit;
  const left = limit < 0 ? Infinity : Math.max(0, limit - opts.used);
  const over = limit >= 0 && units > left;
  const blocked = !opts.canRequest || limit === 0;

  const submit = async () => {
    if (busy) return;
    setBusy(true);
    const albums = mode === 'albums' ? data.releases.filter((r) => picked.has(r.mbid)) : [];
    try {
      const res = await mediaCreate({
        kind: 'music',
        mode,
        foreignId: a.mbid,
        title: a.name,
        coverUrl: albums[0]?.coverUrl ?? a.imageUrl ?? data.releases.find((r) => r.type === 'album')?.coverUrl,
        albums: albums.map((r) => ({ mbid: r.mbid, title: r.title, type: r.type, year: r.date ? Number(r.date.slice(0, 4)) : undefined })),
        keepNew: mode !== 'albums' || keepNew,
        albumCount: units
      });
      toast(res.status === 'pending' ? t('Requested. An admin approves it first.') : t('Requested. Lidarr is on it.'));
      noteRequest(res);
      void refreshMediaSummary();
      onClose();
    } catch (e) {
      toast(`${t('Couldn’t send the request.')} ${(e as Error).message || ''}`.trim(), 'error');
    } finally {
      setBusy(false);
    }
  };

  const parts = [a.type === 'Group' ? t('Band') : t('Artist'), a.area, [
    counts.album ? tn(counts.album, '{n} album', '{n} albums') : '',
    counts.ep ? tn(counts.ep, '{n} EP', '{n} EPs') : '',
    counts.single ? tn(counts.single, '{n} single', '{n} singles') : ''
  ].filter(Boolean).join(', ') + ' ' + t('on MusicBrainz')];
  if (data.albumsOnServer) parts.push(tn(data.albumsOnServer, '{n} album on the server', '{n} albums on the server'));

  const label = mode === 'artist' ? t('Request whole artist') : mode === 'future' ? t('Request new releases') : units ? tn(units, 'Request {n} album', 'Request {n} albums') : t('Request albums');
  const quotaAria = limit < 0 ? t('No limit') : t('{used} used, {now} in this request, {left} left', { used: opts.used, now: units, left: Math.max(0, left - units) });

  return (
    <>
      <Head
        name={a.name}
        art={<AlbumArt seed={a.mbid} src={a.imageUrl} text={initials(a.name)} round class="reqs-dlg-avatar" />}
        meta={parts.filter(Boolean).join(' · ')}
        onClose={onClose}
      />
      <div class="reqs-dlg-body">
        <div role="radiogroup" aria-label={t('What to request')} class="reqs-dlg-seg">
          {([['artist', 'Whole artist'], ['albums', 'Choose albums'], ['future', 'New releases only']] as [Mode, string][]).map(([id, text]) => (
            <button key={id} type="button" role="radio" aria-checked={mode === id ? 'true' : 'false'} onClick={() => setMode(id)}>{t(text)}</button>
          ))}
        </div>

        {mode === 'future' ? (
          <div class="reqs-dlg-info">
            <Icon name="clock" size={18} />
            <span>{t('Lidarr watches {artist} and grabs albums that come out from now on. Nothing already out is downloaded.', { artist: a.name })}</span>
          </div>
        ) : (
          <>
            <div class="reqs-dlg-types">
              <nav aria-label={t('Release types')} class="tab-row hscroll">
                {TYPES.filter((x) => counts[x.id]).map((x) => (
                  <button key={x.id} type="button" class="tab" aria-pressed={type === x.id ? 'true' : 'false'} aria-current={type === x.id ? 'page' : undefined} onClick={() => setType(x.id)}>
                    {t(x.many, { n: counts[x.id] })}
                  </button>
                ))}
              </nav>
              {mode === 'albums' && open.length > 0 && (
                <button type="button" role="checkbox" aria-checked={allOn ? 'true' : 'false'} class="reqs-dlg-all" onClick={toggleAll}>
                  {t('Select all')}
                  <span aria-hidden="true" class={'reqs-box is-22' + (allOn ? ' is-on' : '')}><Icon name="check" size={14} stroke={3.2} /></span>
                </button>
              )}
            </div>
            <div role="group" aria-label={t(TYPES.find((x) => x.id === type)!.many.split(' ·')[0]!)} class="reqs-dlg-rows">
              {rows.map((r) => {
                const isLocked = locked(r);
                const on = !isLocked && (mode === 'artist' ? (r.type === 'album' || r.type === 'ep') && !r.upcoming && !r.secondary?.length : picked.has(r.mbid));
                const status = cardStatus(r.status, r.requestedBy) ?? { text: r.upcoming ? t('Not out yet') : t('Not on the server'), dot: '#c8c8c8' };
                const tone = r.status === 'server' ? 'is-server' : r.status === 'none' ? '' : 'is-pending';
                return (
                  <button
                    key={r.mbid}
                    type="button"
                    role="checkbox"
                    class={'reqs-dlg-row' + (isLocked ? ' is-locked' : on ? ' is-on' : '')}
                    aria-checked={on || isLocked ? 'true' : 'false'}
                    aria-disabled={isLocked || mode === 'artist' ? 'true' : undefined}
                    onClick={() => { if (!isLocked && mode === 'albums') toggle(r.mbid); }}
                  >
                    <AlbumArt seed={r.mbid} src={r.coverUrl} class="reqs-dlg-thumb" />
                    <span class="reqs-dlg-row-text">
                      <span class="reqs-dlg-row-name">{r.title}</span>
                      <span class="reqs-dlg-row-sub">{releaseSub(r)}</span>
                    </span>
                    <span class={'reqs-dlg-status ' + tone}><span class="reqs-dot" style={{ background: r.status === 'none' ? '#c8c8c8' : status.dot }} />{status.text}</span>
                    <span aria-hidden="true" class={'reqs-box' + (on ? ' is-on' : isLocked ? ' is-locked' : '')}><Icon name="check" size={16} stroke={3.2} /></span>
                  </button>
                );
              })}
            </div>
          </>
        )}

        {mode === 'albums' && (
          <div class="reqs-dlg-option">
            <span class="reqs-dlg-option-text">
              <span class="reqs-dlg-option-name">{t('Keep getting new albums')}</span>
              <span class="reqs-dlg-option-help">{t('Lidarr keeps watching {artist} and grabs new releases', { artist: a.name })}</span>
            </span>
            <Switch checked={keepNew} onChange={setKeepNew} label={t('Keep getting new albums')} />
          </div>
        )}

        <div class="reqs-dlg-grid">
          <div class="reqs-dlg-card">
            <span class="reqs-dlg-card-label">{t('Quality')}</span>
            <span class="reqs-dlg-card-value">{opts.quality ? t('{quality} · set by your admin', { quality: opts.quality }) : t('Set by your admin')}</span>
          </div>
          <Quota label={t('Album requests this week')} opts={opts} units={units} aria={quotaAria} />
        </div>
      </div>

      <div class="reqs-dlg-foot">
        <span class="reqs-dlg-note">
          <span class={'reqs-dot is-8 ' + (blocked || over ? 'is-danger' : opts.autoApprove ? 'is-ok' : 'is-pending')} aria-hidden="true" />
          {blocked ? t('Music requests are switched off for your profile')
            : over ? t('That’s more albums than you have left this week')
            : opts.autoApprove ? t('Approved automatically for you') : t('An admin approves requests before Lidarr searches')}
        </span>
        <span class="reqs-dlg-buttons">
          <button type="button" class="reqs-dlg-cancel" onClick={onClose}>{t('Cancel')}</button>
          <button type="button" data-autofocus class="btn btn-primary reqs-dlg-send" disabled={busy || blocked || over || (mode === 'albums' && !units)} onClick={() => void submit()}>
            <Icon name="request" size={18} stroke={2.2} />
            {busy ? t('Requesting…') : label}
          </button>
        </span>
      </div>
    </>
  );
}

/* "Album requests this week · 3 of 5 left", with what's used in grey and this request in the accent. */
export function Quota({ label, opts, units, aria, extra }: { label: string; opts: MediaOptions; units: number; aria: string; extra?: string }) {
  const limit = opts.limit;
  const pct = (n: number) => `${limit > 0 ? Math.max(0, Math.min(100, (n / limit) * 100)) : 0}%`;
  const left = Math.max(0, limit - opts.used);
  const over = limit >= 0 && units > left;
  return (
    <div class="reqs-dlg-card is-quota">
      <span class="reqs-dlg-quota-line">
        <span>{label}</span>
        <span class={over ? 'is-over' : undefined}>{limit < 0 ? t('No limit') : limit === 0 ? t('Can’t request') : t('{left} of {limit} left', { left, limit })}</span>
      </span>
      <span role="img" aria-label={aria} class="reqs-dlg-quota-bar">
        {limit > 0 && <span style={{ width: pct(opts.used) }} />}
        {limit > 0 && <span class={over ? 'is-over' : undefined} style={{ width: pct(Math.min(units, Math.max(0, limit - opts.used))) }} />}
      </span>
      {extra && <span class="reqs-dlg-card-label">{extra}</span>}
    </div>
  );
}
