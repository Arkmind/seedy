import { useEffect, useState } from 'preact/hooks';
import { ApiError } from '../../api/client';
import { tmdbImage } from '../../api/images';
import { seerrDetails, seerrRequest, type SeerrDetails as SeerrTitle, type SeerrSeason, type SeerrStatus } from '../../api/seedy';
import { meStore } from '../../lib/app-state';
import { duration, fmtDate, plural, ratingLabel, dayOf, fitTitle } from '../../lib/format';
import { Link, back } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { TypePill, inLibrary as isInLibrary, playPath } from '../../ui/cards';
import { Header } from '../../ui/navbar/Header';
import { Icon } from '../../ui/icons';
import { toast } from '../../ui/overlay';
import { RequestSeasonsDialog, markRequested } from './request-seasons';
import { Row } from '../../ui/Row';
import './seerr.css';
import { t } from '../../lib/i18n';

/* A title that is not (fully) on the server yet, from Seerr. Same hero and info panel as the Details board. */

const REQUESTABLE: SeerrStatus[] = ['none', 'declined'];
const isRequestable = (s?: SeerrStatus) => !s || REQUESTABLE.includes(s);
const isRequested = (s?: SeerrStatus) => s === 'pending' || s === 'processing';

const STATUS_PILL: Record<SeerrStatus, { label: string; tone: 'ok' | 'pending' | 'muted' | 'danger' }> = {
  none: { label: 'Not requested', tone: 'muted' },
  pending: { label: 'Requested', tone: 'pending' },
  processing: { label: 'Approved', tone: 'pending' },
  partial: { label: 'Partly available', tone: 'ok' },
  available: { label: 'Available', tone: 'ok' },
  declined: { label: 'Declined', tone: 'danger' },
  blocklisted: { label: 'Blocked', tone: 'danger' }
};

function StatusPill({ status, prefix }: { status: SeerrStatus; prefix?: string }) {
  const s = STATUS_PILL[status] ?? STATUS_PILL.none;
  return (
    <span class={'status-pill seerr-status is-' + s.tone}>
      <span class="pip" />
      {prefix ? `${prefix} ${t(s.label).toLowerCase()}` : t(s.label)}
    </span>
  );
}

function statusLine(d: SeerrTitle): string {
  const who = d.requestedByMe ? t('you') : d.requestedBy;
  switch (d.status) {
    case 'pending': return who ? t('Requested by {name} · waiting for approval', { name: who }) : t('Requested · waiting for approval');
    case 'processing': return who ? t('Requested by {name} · approved, on its way to the server', { name: who }) : t('Requested · approved, on its way to the server');
    case 'available': return t('In your library');
    case 'partial': return t('Some seasons are in your library');
    case 'declined': return t('Declined in Seerr');
    case 'blocklisted': return t("Blocked in Seerr, so it can't be requested");
    default: return t('Not requested yet');
  }
}

function seasonSub(s: SeerrSeason): string {
  const year = s.airDate ? dayOf(s.airDate)!.getFullYear() : undefined;
  return [s.episodeCount ? plural(s.episodeCount, 'episode') : '', year && !Number.isNaN(year) ? String(year) : ''].filter(Boolean).join(' · ');
}

export function SeerrDetails({ mediaType, tmdbId }: { mediaType: 'movie' | 'tv'; tmdbId: number }) {
  const me = useStore(meStore);
  const req = me.requests;
  const { data, error, loading, reload } = useAsync(() => seerrDetails(mediaType, tmdbId), [mediaType, tmdbId]);
  const [d, setD] = useState<SeerrTitle | undefined>(undefined);
  const [busy, setBusy] = useState<'std' | '4k' | null>(null);
  const [picker, setPicker] = useState(false);

  useEffect(() => { setD(data); }, [data]);

  if (!req.connected && !loading && !d) {
    return (
      <Shell>
        <main class="seerr-message">
          <div class="empty">
            <span class="empty-icon" aria-hidden="true"><Icon name="inbox" size={26} /></span>
            <div class="empty-body">
              <h1 class="empty-title">{t('Requests aren\'t set up')}</h1>
              <p class="empty-text">{t('This server isn\'t connected to Seerr, so titles that aren\'t in the library can\'t be shown or requested.')}</p>
            </div>
            <Link to="/" class="btn btn-primary is-sm">{t('Back to home')}</Link>
          </div>
        </main>
      </Shell>
    );
  }

  if (error && !d) {
    const missing = error instanceof ApiError && error.status === 404;
    return (
      <Shell>
        <main class="seerr-message">
          {missing ? (
            <div class="empty">
              <span class="empty-icon" aria-hidden="true"><Icon name="searchX" size={26} /></span>
              <div class="empty-body">
                <h1 class="empty-title">{t('Seerr doesn\'t know this title')}</h1>
                <p class="empty-text">{t('The link may be old, or the title was removed from TMDB.')}</p>
              </div>
              <Link to="/" class="btn btn-primary is-sm">{t('Back to home')}</Link>
            </div>
          ) : (
            <div class="error-box" role="alert">
              <h2>{t('Couldn\'t reach Seerr')}</h2>
              <p>{t('Seedy asked Seerr about this title and got no answer. Try again in a moment.')}</p>
              <button type="button" class="btn btn-secondary" onClick={reload}>{t('Try again')}</button>
            </div>
          )}
        </main>
      </Shell>
    );
  }

  if (!d) return <Shell><HeroSkeleton /></Shell>;

  const tv = d.mediaType === 'tv';
  const showRequest = req.connected && req.requestButtons && req.canRequest;
  const seasons = (d.seasons ?? []).filter((s) => s.seasonNumber > 0);
  const openSeasons = seasons.filter((s) => isRequestable(s.status));
  const inLibrary = isInLibrary(d);
  const backdrop = tmdbImage(d.backdropPath, 'w1280');
  const poster = !backdrop ? tmdbImage(d.posterPath, 'w780') : undefined;
  const year = d.year ?? (d.releaseDate ? String(dayOf(d.releaseDate)!.getFullYear()) : undefined);
  const directors = d.crew.filter((c) => c.job === 'Director').map((c) => c.name);
  const creators = d.crew.filter((c) => c.job === 'Creator').map((c) => c.name);
  const stars = d.cast.slice(0, 3).map((c) => c.name);
  const totalEpisodes = seasons.reduce((n, s) => n + (s.episodeCount || 0), 0);
  const canMore = tv && showRequest && openSeasons.length > 0 && seasons.length > 0;
  const want4k = req.allow4k && showRequest;

  /* Movies only: a series goes through the seasons dialog. */
  const request = async (opts: { is4k?: boolean } = {}) => {
    setBusy(opts.is4k ? '4k' : 'std');
    try {
      const r = await seerrRequest({ mediaType: d.mediaType, tmdbId: d.tmdbId, ...(opts.is4k ? { is4k: true } : {}) });
      setD((prev) => prev && (opts.is4k ? { ...prev, status4k: r.status } : { ...prev, status: r.status, requestedByMe: true, requestedBy: undefined }));
      toast(opts.is4k ? t('Requested {title} in 4K', { title: d.title }) : t('Requested {title}', { title: d.title }));
    } catch (e) {
      toast(e instanceof ApiError && e.status === 403 ? t("Your profile can't request titles") : t("Couldn't request {title}", { title: d.title }), 'error');
    } finally {
      setBusy(null);
    }
  };

  let primary: preact.JSX.Element | null = null;
  if (inLibrary && d.jellyfinItemId) {
    primary = tv
      ? <Link to={`/details/${d.jellyfinItemId}`} class="btn btn-primary"><Icon name="tv" size={20} />{t('Open in library')}</Link>
      : <Link to={playPath({ Id: d.jellyfinItemId })} class="btn btn-primary"><Icon name="play" size={20} />{t('Play')}</Link>;
  } else if (isRequested(d.status)) {
    primary = <span class="seerr-state" role="status"><span class="dot is-pending" />{t('Requested')}</span>;
  } else if (showRequest && isRequestable(d.status) && (!tv || openSeasons.length > 0 || seasons.length === 0)) {
    // A series always goes through the seasons dialog, so the person sees what they're asking for.
    primary = tv ? (
      <button type="button" class="btn btn-primary" aria-haspopup="dialog" onClick={() => setPicker(true)}>
        <Icon name="plus" size={20} stroke={2.6} />
        {seasons.length > 1 ? t('Request seasons') : t('Request')}
      </button>
    ) : (
      <button type="button" class="btn btn-primary" disabled={busy !== null} onClick={() => request()} aria-label={`Request ${d.title}`}>
        <Icon name="plus" size={20} stroke={2.6} />
        {busy === 'std' ? t('Requesting…') : t('Request')}
      </button>
    );
  }

  const genres = d.genres.slice(0, 3).join(' · ');
  const runtimeLabel = !tv && d.runtime ? duration(d.runtime * 60) : '';
  const seasonCount = tv ? d.numberOfSeasons ?? seasons.length : 0;

  return (
    <Shell>
      <section class="seerr-hero" aria-label={d.title}>
        {backdrop && <img class="seerr-hero-art" src={backdrop} alt="" />}
        {poster && <img class="seerr-hero-art is-poster" src={poster} alt="" />}
        <div aria-hidden="true" class="seerr-shade-side" />
        <div aria-hidden="true" class="seerr-shade-bottom" />
        <div aria-hidden="true" class="seerr-shade-top" />

        <div class="seerr-hero-body">
          <div class="seerr-hero-main">
            <a href="#" class="seerr-back" onClick={(e) => { e.preventDefault(); back('/'); }}>
              <Icon name="chevronLeft" size={16} stroke={2.2} />
              {t('Discover')}
            </a>
            <div class="seerr-pills">
              <TypePill type={d.mediaType} />
              {d.status !== 'none' && <StatusPill status={d.status} />}
            </div>
            <h1 class="seerr-title fit-title" style={fitTitle(d.title)}>{d.title}</h1>
            <div class="seerr-meta">
              {year && <span>{year}</span>}
              {d.certification && <span class="rating-badge">{ratingLabel(d.certification)}</span>}
              {runtimeLabel && <span>{runtimeLabel}</span>}
              {tv && seasonCount > 0 && <span>{plural(seasonCount, 'season')}</span>}
              {d.rating ? <span class="score"><Icon name="star" size={15} />{d.rating.toFixed(1)}</span> : null}
              {genres && <span>{genres}</span>}
            </div>
            {d.tagline && <p class="seerr-tagline">{d.tagline}</p>}
            {d.overview && <p class="seerr-overview">{d.overview}</p>}

            <div class="seerr-actions">
              {primary}
              {inLibrary && !tv && d.jellyfinItemId && (
                <Link to={`/details/${d.jellyfinItemId}`} class="btn seerr-alt">{t('Open in library')}</Link>
              )}
              {tv && canMore && (inLibrary || isRequested(d.status)) && (
                <button type="button" class="btn seerr-alt" aria-haspopup="dialog" onClick={() => setPicker(true)}>
                  <Icon name="request" size={20} />
                  {inLibrary ? t('Request missing seasons') : t('Request more seasons')}
                </button>
              )}
              {want4k && !tv && isRequestable(d.status4k) && (
                <button type="button" class="btn seerr-alt" onClick={() => request({ is4k: true })} disabled={busy !== null}>
                  {busy === '4k' ? t('Requesting…') : t('Request in 4K')}
                </button>
              )}
              {want4k && !tv && isRequested(d.status4k) && (
                <span class="seerr-state is-alt" role="status"><span class="dot is-pending" />{t('4K requested')}</span>
              )}
              {d.trailerUrl && (
                <a href={d.trailerUrl} target="_blank" rel="noreferrer" class="btn btn-square" aria-label={t('Play trailer')} title={t('Trailer')}>
                  <Icon name="trailer" size={22} />
                </a>
              )}
              {req.seerrUrl && (
                <a href={`${req.seerrUrl.replace(/\/$/, '')}/${d.mediaType}/${d.tmdbId}`} target="_blank" rel="noreferrer" class="btn btn-square" aria-label={t('Open in Seerr')} title={t('Open in Seerr')}>
                  <Icon name="external" size={22} />
                </a>
              )}
            </div>

            {(isRequested(d.status) || d.status === 'declined') && (
              <p class="seerr-note"><span class={'dot ' + (d.status === 'declined' ? 'is-danger' : 'is-pending')} />{statusLine(d)}</p>
            )}
            {!showRequest && isRequestable(d.status) && req.connected && (
              <p class="seerr-note">
                {req.canRequest ? t('Requests are turned off on this server.') : t("Your profile can't make requests. Ask whoever runs this server.")}
              </p>
            )}
          </div>

          <dl class="seerr-info">
            {!tv && directors.length > 0 && <><dt>{t('Directed by')}</dt><dd>{directors.slice(0, 2).join(', ')}</dd></>}
            {tv && creators.length > 0 && <><dt>{t('Created by')}</dt><dd>{creators.slice(0, 2).join(', ')}</dd></>}
            {stars.length > 0 && <><dt>{t('Starring')}</dt><dd>{stars.join(', ')}</dd></>}
            {tv && d.networks?.length ? <><dt>{t('Network')}</dt><dd>{d.networks.slice(0, 2).join(', ')}</dd></> : null}
            {d.releaseDate && <><dt>{tv ? t('First aired') : t('Release date')}</dt><dd>{fmtDate(dayOf(d.releaseDate)!)}</dd></>}
            {tv && seasons.length > 0 && <><dt>{t('Seasons')}</dt><dd>{plural(seasons.length, 'season')}{totalEpisodes ? ` · ${plural(totalEpisodes, 'episode')}` : ''}</dd></>}
            <dt>{t('Status')}</dt><dd>{statusLine(d)}</dd>
            {want4k && d.status4k && d.status4k !== 'none' && <><dt>4K</dt><dd>{t(STATUS_PILL[d.status4k]?.label ?? '')}</dd></>}
          </dl>
        </div>
      </section>

      <main class="seerr-main">
        {tv && seasons.length > 0 && (
          <section class="seerr-section" aria-labelledby="seerr-seasons">
            <div class="seerr-section-head">
              <h2 id="seerr-seasons" class="row-title">{t('Seasons')}</h2>
              {canMore && (
                <button type="button" class="btn btn-secondary" aria-haspopup="dialog" onClick={() => setPicker(true)}>
                  <Icon name="plus" size={18} stroke={2.6} />
                  {t('Request seasons')}
                </button>
              )}
            </div>
            <div class="seerr-seasons">
              {seasons.map((s) => (
                <div key={s.seasonNumber} class="seerr-season">
                  <span class="seerr-season-num" aria-hidden="true">{s.seasonNumber}</span>
                  <span class="seerr-season-text">
                    <span class="seerr-season-name">{s.name || t('Season {n}', { n: s.seasonNumber })}</span>
                    {seasonSub(s) && <span class="seerr-season-sub">{seasonSub(s)}</span>}
                  </span>
                  <StatusPill status={s.status} />
                </div>
              ))}
            </div>
          </section>
        )}

        {d.cast.length > 0 && (
          <Row title={t('Cast & crew')} gap={18}>
            {d.cast.slice(0, 20).map((c, i) => <SeerrCastCard key={c.id} name={c.name} role={c.character} path={c.profilePath} index={i} />)}
          </Row>
        )}
      </main>

      {tv && (
        <RequestSeasonsDialog
          open={picker}
          context={inLibrary ? 'library' : 'seerr'}
          target={{ tmdbId: d.tmdbId, title: d.title, year, posterPath: d.posterPath, backdropPath: d.backdropPath }}
          details={d}
          onClose={() => setPicker(false)}
          onDone={(r) => setD((prev) => prev && {
            ...markRequested(prev, r.seasons, r.is4k, r.status),
            // Asking for the missing seasons of a partly available show keeps it playable from the library.
            ...(r.is4k ? { status4k: prev.status4k === 'partial' ? 'partial' : r.status } : { status: prev.status === 'partial' ? 'partial' : r.status, requestedByMe: true, requestedBy: undefined })
          })}
        />
      )}
    </Shell>
  );
}

function Shell({ children }: { children: preact.ComponentChildren }) {
  return (
    <div class="page seerr-page">
      <Header variant="overlay" active={null} />
      {children}
    </div>
  );
}

const CAST_BG = ['#3d2424', '#26362b', '#3f3322', '#2f2840', '#1f3a3c', '#3d2436', '#2e2e2e', '#24313d'];
const CAST_INK = ['#f0b9a0', '#d9e3b5', '#f2cf86', '#e3c4ff', '#a8e6df', '#ffb3e6', '#e0e0e0', '#c9d6e3'];

/* Same look as the library's cast card, but TMDB people aren't on the server, so it doesn't link. */
function SeerrCastCard({ name, role, path, index }: { name: string; role?: string; path?: string; index: number }) {
  const img = tmdbImage(path, 'w300_and_h450_face');
  const [ok, setOk] = useState(true);
  const initials = name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase();
  return (
    <div class="cast-card">
      <span aria-hidden="true" class="cast-face" style={{ background: CAST_BG[index % CAST_BG.length], color: CAST_INK[index % CAST_INK.length] }}>
        {img && ok ? <img src={img} alt="" class="art" loading="lazy" onError={() => setOk(false)} /> : initials}
      </span>
      <span class="cast-text">
        <span class="cast-name">{name}</span>
        {role && <span class="cast-role">{role}</span>}
      </span>
    </div>
  );
}

function HeroSkeleton() {
  return (
    <section class="seerr-hero is-loading" aria-busy="true" aria-label={t('Loading')}>
      <div class="seerr-hero-body">
        <div class="seerr-hero-main">
          <span class="skel skel-line" style={{ width: '110px' }} />
          <span class="skel" style={{ width: '70%', height: '84px', borderRadius: '13px' }} />
          <span class="skel skel-line" style={{ width: '50%' }} />
          <span class="skel skel-line" style={{ width: '90%' }} />
          <span class="skel skel-line" style={{ width: '80%' }} />
          <span class="skel" style={{ width: '220px', height: '52px', borderRadius: '13px' }} />
        </div>
      </div>
    </section>
  );
}
