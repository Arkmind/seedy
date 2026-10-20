import { Fragment, type ComponentChildren } from 'preact';
import { useEffect, useMemo, useState } from 'preact/hooks';
import { JELLYFIN_WEB } from '../../api/client';
import { getEpisodes, getLocalTrailers, getSimilar, setPlayed } from '../../api/jellyfin';
import { backdropUrl, cardArt } from '../../api/images';
import type { BaseItem, Person } from '../../api/types';
import { viewsStore } from '../../lib/app-state';
import { fmtDate, fmtWeekdayDate, pct, plural, progressFraction, remainingSeconds, runtime, timeLeft, ratingLabel, dayOf, fitTitle } from '../../lib/format';
import { Link, href, navigate } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { Art, CastCard, ItemCard, SkeletonCards, WatchedCheck, playPath } from '../../ui/cards';
import { Icon } from '../../ui/icons';
import { MenuButton, MenuItem, toast } from '../../ui/overlay';
import { Row } from '../../ui/Row';
import { CUSTOM, useCustom } from '../me/prefs';
import { useThemeMedia } from '../me/theme';
import { findView, isNewEpisode, join, yearOf } from './parts';
import { t } from '../../lib/i18n';

/* ───────── Which library a title belongs to (header tab + back link) ───────── */

const EXPECTED_TYPE: Partial<Record<string, string>> = {
  Series: 'tvshows', Season: 'tvshows', Episode: 'tvshows', Movie: 'movies', BoxSet: 'boxsets', MusicAlbum: 'music', MusicArtist: 'music', Audio: 'music', MusicVideo: 'musicvideos'
};

export function useView(item?: BaseItem | null): BaseItem | undefined {
  const views = useStore(viewsStore);
  const [view, setView] = useState<BaseItem | undefined>(undefined);
  const lookup = item ? (item.Type === 'Episode' || item.Type === 'Season' ? item.SeriesId ?? item.Id : item.Type === 'Audio' ? item.AlbumId ?? item.Id : item.Id) : undefined;
  useEffect(() => {
    if (!item || !lookup) return;
    let alive = true;
    findView(lookup, views).then((v) => {
      if (!alive) return;
      setView(v ?? views.find((x) => x.CollectionType === EXPECTED_TYPE[item.Type]));
    });
    return () => { alive = false; };
  }, [lookup, views.length]);
  return view;
}

export function fallbackLibraryName(item: BaseItem): string {
  switch (EXPECTED_TYPE[item.Type]) {
    case 'tvshows': return 'Shows';
    case 'movies': return 'Movies';
    case 'boxsets': return 'Collections';
    case 'music': return 'Music';
    default: return 'Home';
  }
}

/* ───────── Hero ───────── */

export interface InfoRow { label: string; value?: ComponentChildren }

export function DetailsHero({ art: artIn, itemId, overline, overlineTo, title, meta, badges, overview, actions, progress, notice, info, poster }: {
  art?: string;
  /* The title whose theme video / theme song may play here (Settings · Display). */
  itemId?: string;
  overline?: string;
  overlineTo?: string;
  title: string;
  meta?: ComponentChildren;
  badges?: string[];
  overview?: string;
  actions?: ComponentChildren;
  progress?: { fraction: number; text: string };
  /* Under the actions: "Seasons 2 and 4 aren't on the server · Request 2 seasons". */
  notice?: ComponentChildren;
  info?: InfoRow[];
  poster?: ComponentChildren;
}) {
  const rows = (info ?? []).filter((r) => r.value !== undefined && r.value !== null && r.value !== '');
  const [loaded, setLoaded] = useState(false);
  const [videoOn, setVideoOn] = useState(false);
  // Settings · Display: Backdrops off leaves the plain ground; a theme video plays here instead of the backdrop.
  const art = useCustom(CUSTOM.backdrops) ? artIn : undefined;
  const theme = useThemeMedia(itemId);
  useEffect(() => setVideoOn(false), [theme.video]);
  return (
    <section class="dhero" aria-label={title}>
      {art && !videoOn && <img class={'dhero-art' + (loaded ? ' is-loaded' : '')} src={art} alt="" decoding="async" onLoad={() => setLoaded(true)} />}
      {theme.video && (
        <video
          class={'dhero-art' + (videoOn ? ' is-loaded' : '')}
          src={theme.video}
          autoplay
          muted
          loop
          playsInline
          aria-hidden="true"
          onPlaying={() => setVideoOn(true)}
          onError={() => setVideoOn(false)}
        />
      )}
      <div aria-hidden="true" class="dhero-shade-x" />
      <div aria-hidden="true" class="dhero-shade-b" />
      <div aria-hidden="true" class="dhero-shade-t" />
      <div class="dhero-body">
        <div class="dhero-main">
          {poster}
          <div class="dhero-info">
            {overline && (
              overlineTo
                ? <Link to={overlineTo} class="dhero-back"><Icon name="chevronLeft" size={16} stroke={2.2} />{overline}</Link>
                : <span class="dhero-back">{overline}</span>
            )}
            <h1 class={'dhero-title fit-title' + (title.length > 22 ? ' is-long' : '')} style={fitTitle(title)}>{title}</h1>
            {meta && <div class="dhero-meta">{meta}</div>}
            {badges && badges.length > 0 && (
              <div class="dhero-badges" aria-label={t('Formats')}>
                {badges.map((b) => <span key={b} class="tech-badge">{b}</span>)}
              </div>
            )}
            {overview && <p class="dhero-overview">{overview}</p>}
            {actions && <div class="dhero-actions">{actions}</div>}
            {progress && (
              <div class="dhero-progress">
                <div class="dhero-bar" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(progress.fraction * 100)} aria-label={t('Progress')}>
                  <i style={{ width: pct(progress.fraction) }} />
                </div>
                <span>{progress.text}</span>
              </div>
            )}
            {notice}
          </div>
        </div>
        {rows.length > 0 && (
          <dl class="dhero-panel">
            {rows.map((r) => (
              <Fragment key={r.label}>
                <dt>{t(r.label)}</dt>
                <dd>{r.value}</dd>
              </Fragment>
            ))}
          </dl>
        )}
      </div>
    </section>
  );
}

export function heroArt(item: BaseItem, fallback?: BaseItem | null): string | undefined {
  return backdropUrl(item, 1920) ?? (fallback ? backdropUrl(fallback, 1920) : undefined);
}

export function DetailsSkeleton() {
  return (
    <section class="dhero is-loading" aria-busy="true" aria-label={t('Loading')}>
      <div class="dhero-body">
        <div class="dhero-main">
          <div class="dhero-info">
            <span class="skel skel-line" style={{ width: '90px' }} />
            <span class="skel" style={{ width: 'min(560px, 80vw)', height: '84px', borderRadius: '10px' }} />
            <span class="skel skel-line" style={{ width: '340px' }} />
            <span class="skel skel-line" style={{ width: 'min(620px, 80vw)' }} />
            <span class="skel skel-line" style={{ width: 'min(520px, 70vw)' }} />
            <span class="skel" style={{ width: '240px', height: '52px', borderRadius: '13px' }} />
          </div>
        </div>
      </div>
    </section>
  );
}

/* Meta line: "2024–2026  [TV-MA]  3 seasons  ★ 8.4  Drama · Thriller · Mystery". */
export function MetaLine({ parts, rating, score, genres }: { parts: (string | undefined | false)[]; rating?: string; score?: number; genres?: string[] }) {
  const [first, ...rest] = parts.filter(Boolean) as string[];
  return (
    <>
      {first && <span>{first}</span>}
      {rating && <span class="rating-badge">{ratingLabel(rating)}</span>}
      {rest.map((p) => <span key={p}>{p}</span>)}
      {score ? <span class="score"><Icon name="star" size={15} />{score.toFixed(1)}</span> : null}
      {genres && genres.length > 0 && <span>{genres.join(' · ')}</span>}
    </>
  );
}

/* ───────── People ───────── */

export const peopleOf = (item: BaseItem, type: string) => (item.People ?? []).filter((p) => p.Type === type);
export const names = (list: Person[], max = 3) => list.slice(0, max).map((p) => p.Name).join(', ');

export function CastRow({ people }: { people: Person[] }) {
  const [all, setAll] = useState(false);
  const ordered = useMemo(() => {
    const actors = people.filter((p) => p.Type === 'Actor' || p.Type === 'GuestStar');
    const crewOrder = ['Creator', 'Director', 'Writer', 'Producer', 'Composer'];
    const crew = people.filter((p) => p.Type && crewOrder.includes(p.Type)).sort((a, b) => crewOrder.indexOf(a.Type!) - crewOrder.indexOf(b.Type!));
    const seen = new Set<string>();
    return [...actors, ...crew].filter((p) => (seen.has(p.Id + p.Type) ? false : (seen.add(p.Id + p.Type), true)));
  }, [people]);
  if (!ordered.length) return null;
  const role = (p: Person) => (p.Type === 'Actor' || p.Type === 'GuestStar' ? p.Role || (p.Type === 'GuestStar' ? t('Guest star') : '') : t(p.Type ?? ''));
  const more = ordered.length > 8;
  const toggle = more ? (
    <button type="button" class="row-link link-btn" aria-expanded={all ? 'true' : 'false'} onClick={() => setAll((a) => !a)}>{all ? t('Show less') : t('See all')}</button>
  ) : undefined;
  if (all) {
    return (
      <section class="row" aria-label={t('Cast & crew')}>
        <div class="row-head is-center">
          <div class="row-head-main"><h2 class="row-title">{t('Cast & crew')}</h2><span class="row-sub">{plural(ordered.length, 'person', 'people')}</span></div>
          <div class="row-tools">{toggle}</div>
        </div>
        <div class="cast-grid">
          {ordered.map((p, i) => <CastCard key={p.Id + p.Type} person={p} index={i} role={role(p)} />)}
        </div>
      </section>
    );
  }
  return (
    <Row title={t('Cast & crew')} gap={18} label={t('Cast & crew')} tools={toggle}>
      {ordered.slice(0, 24).map((p, i) => <CastCard key={p.Id + p.Type} person={p} index={i} role={role(p)} />)}
    </Row>
  );
}

export function SimilarRow({ id, title: given }: { id: string; title?: string }) {
  const title = given ?? t('More like this');
  const { data, loading } = useAsync(() => getSimilar(id, 12).then((r) => r.Items), [id]);
  if (loading && !data) {
    return (
      <div class="details-similar">
        <section class="row"><div class="row-head"><h2 class="row-title">{title}</h2></div><div class="row-shell"><div class="row-scroller hscroll"><SkeletonCards count={6} width={300} /></div></div></section>
      </div>
    );
  }
  if (!data?.length) return null;
  return (
    <div class="details-similar">
      <Row title={title} label={title}>
        {data.map((it) => <ItemCard key={it.Id} item={it} width={300} titleSize={14} titleWeight={600} meta={yearOf(it)} />)}
      </Row>
    </div>
  );
}

/* ───────── Hero actions ───────── */

export function TrailerButton({ item }: { item: BaseItem }) {
  const remote = item.RemoteTrailers?.find((t) => t.Url)?.Url;
  if (!item.LocalTrailerCount && !remote) return null;
  const play = async () => {
    if (item.LocalTrailerCount) {
      const list = await getLocalTrailers(item.Id).catch(() => [] as BaseItem[]);
      if (list[0]) { navigate(playPath(list[0])); return; }
    }
    if (remote) window.open(remote, '_blank', 'noopener,noreferrer');
    else toast("Couldn't find the trailer", 'error');
  };
  return (
    <button type="button" class="btn btn-square" aria-label={t('Play trailer')} onClick={play}>
      <Icon name="trailer" size={22} />
    </button>
  );
}

export function SquareToggle({ on, onLabel, offLabel, icon, onClick }: { on: boolean; onLabel: string; offLabel: string; icon: 'heart' | 'check'; onClick: () => void }) {
  return (
    <button type="button" class="btn btn-square" aria-label={t(on ? onLabel : offLabel)} aria-pressed={on ? 'true' : 'false'} onClick={onClick}>
      {icon === 'heart' ? <Icon name="heart" size={22} filled={on} /> : <Icon name="check" size={22} stroke={on ? 3 : 2.2} />}
    </button>
  );
}

export function MoreActions({ item, children }: { item: BaseItem; children?: (close: () => void) => ComponentChildren }) {
  const copy = async (close: () => void) => {
    close();
    try {
      await navigator.clipboard.writeText(window.location.href);
      toast(t('Link copied'));
    } catch {
      toast("Couldn't copy the link", 'error');
    }
  };
  return (
    <MenuButton
      width={260}
      button={({ ref, open, toggle }) => (
        <button ref={ref} type="button" class={'btn btn-square' + (open ? ' is-flipped' : '')} aria-label={t('More actions')} aria-haspopup="menu" aria-expanded={open ? 'true' : 'false'} onClick={toggle}>
          <Icon name="more" size={22} />
        </button>
      )}
    >
      {(close) => (
        <>
          {children?.(close)}
          <MenuItem icon="external" label={t('Open in Jellyfin')} href={`${JELLYFIN_WEB}#/details?id=${item.Id}`} />
          <MenuItem icon="link" label={t('Copy link')} onSelect={() => copy(close)} />
        </>
      )}
    </MenuButton>
  );
}

export function airsPhrase(days?: string[]): string {
  if (!days?.length) return '';
  const pluralDays = days.map((d) => d + 's');
  if (pluralDays.length === 1) return pluralDays[0];
  if (pluralDays.length === 7) return 'daily';
  return pluralDays.slice(0, -1).join(', ') + ' and ' + pluralDays[pluralDays.length - 1];
}

export function seriesStatus(item: BaseItem): string | undefined {
  if (item.Status === 'Continuing') {
    const days = airsPhrase(item.AirDays);
    return days ? t('Continuing · new episodes {days}', { days }) : t('Continuing');
  }
  if (item.Status === 'Ended') {
    const end = item.EndDate ? dayOf(item.EndDate)!.getFullYear() : undefined;
    return end ? t('Ended · {year}', { year: end }) : t('Ended');
  }
  return item.Status || undefined;
}

/* ───────── Seasons and episodes ───────── */

/* Seasons on the server and the missing ones, by number; Specials stay last. */
function withMissing(seasons: BaseItem[], missing?: MissingSeason[]): (BaseItem | MissingSeason)[] {
  if (!missing?.length) return seasons;
  const num = (s: BaseItem | MissingSeason) => ('n' in s ? s.n : s.IndexNumber ?? 0);
  const specials = seasons.filter((s) => s.IndexNumber === 0);
  const rest: (BaseItem | MissingSeason)[] = [...seasons.filter((s) => s.IndexNumber !== 0), ...missing];
  return [...rest.sort((a, b) => num(a) - num(b)), ...specials];
}

export function orderSeasons(list: BaseItem[]): BaseItem[] {
  const regular = list.filter((s) => (s.IndexNumber ?? 1) !== 0);
  const specials = list.filter((s) => s.IndexNumber === 0);
  return [...regular, ...specials];
}

const EPISODE_FIELDS = 'Overview,PrimaryImageAspectRatio,DateCreated';

/* A season that isn't on the server, shown as a dashed tab that opens the request dialog. */
export interface MissingSeason { n: number; name: string; year?: number; requested: boolean }

export function SeasonEpisodes({ series, seasons, selected, onSelect, upNextId, currentId, onChanged, version, missing, onRequest }: {
  series: BaseItem;
  seasons: BaseItem[];
  missing?: MissingSeason[];
  onRequest?: (season: number) => void;
  selected?: string;
  onSelect?: (seasonId: string) => void;
  upNextId?: string;
  currentId?: string;
  onChanged?: () => void;
  version?: number;
}) {
  const [desc, setDesc] = useState(false);
  const [showAll, setShowAll] = useState(false);
  const [tick, setTick] = useState(0);
  const [busy, setBusy] = useState(false);
  const { data: result, loading: fetching, error, reload } = useAsync(
    () => (selected
      ? getEpisodes(series.Id, selected, { fields: EPISODE_FIELDS }).then((r) => ({ season: selected, items: r.Items }))
      : Promise.resolve({ season: selected, items: [] as BaseItem[] })),
    [series.Id, selected, tick, version]
  );
  useEffect(() => setShowAll(false), [selected]);

  // While another season loads, the previous season's list must not show (or be marked watched).
  const current = result && result.season === selected ? result.items : undefined;
  const loading = fetching || current === undefined;
  const data = current;
  const episodes = current ?? [];
  const ordered = desc ? [...episodes].reverse() : episodes;
  const total = episodes.length;
  const watched = episodes.filter((e) => e.UserData?.Played).length;
  const available = episodes.filter((e) => e.LocationType !== 'Virtual');
  const allWatched = available.length > 0 && available.every((e) => e.UserData?.Played);
  const season = seasons.find((s) => s.Id === selected);

  /* Show six, starting near the episode to watch next. */
  const focusId = currentId ?? upNextId;
  const focusIdx = focusId ? ordered.findIndex((e) => e.Id === focusId) : -1;
  const start = showAll || focusIdx < 5 ? 0 : focusIdx - 1;
  const shown = showAll ? ordered : ordered.slice(start, start + 6);

  const markSeason = async () => {
    if (!selected) return;
    setBusy(true);
    try {
      await setPlayed(selected, !allWatched);
      toast(allWatched ? `Marked ${season?.Name ?? 'season'} as unwatched` : `Marked ${season?.Name ?? 'season'} as watched`);
      setTick((t) => t + 1);
      onChanged?.();
    } catch {
      toast("Couldn't update the season", 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section class="seasons" id="seasons" aria-label={t('Episodes')}>
      <div class="seasons-bar">
        <div class="tab-row hscroll" role="tablist" aria-label={t('Seasons')}>
          {withMissing(seasons, missing).map((s) => 'n' in s ? (
            <button
              key={'missing-' + s.n}
              type="button"
              class={'tab is-missing' + (s.requested ? ' is-requested' : '')}
              aria-haspopup="dialog"
              aria-label={s.requested
                ? t('{name}, requested, not on the server yet', { name: s.name })
                : s.year ? t('{name}, airs in {year}, not on the server', { name: s.name, year: s.year }) : t('{name}, not on the server', { name: s.name })}
              onClick={() => onRequest?.(s.n)}
            >
              {s.requested ? <span class="tab-pending" aria-hidden="true" /> : <Icon name="plus" size={14} stroke={2.4} />}
              {s.name}
              {s.year && <span class="tab-year">{s.year}</span>}
            </button>
          ) : (
            <button
              key={s.Id}
              type="button"
              role="tab"
              class="tab"
              aria-selected={s.Id === selected ? 'true' : 'false'}
              tabIndex={s.Id === selected ? 0 : -1}
              onClick={() => onSelect?.(s.Id)}
              onKeyDown={(e) => {
                if (e.key !== 'ArrowRight' && e.key !== 'ArrowLeft') return;
                e.preventDefault();
                const i = seasons.findIndex((x) => x.Id === selected);
                const n = seasons[(i + (e.key === 'ArrowRight' ? 1 : -1) + seasons.length) % seasons.length];
                onSelect?.(n.Id);
                requestAnimationFrame(() => (document.querySelector(`[data-season="${n.Id}"]`) as HTMLElement | null)?.focus());
              }}
              data-season={s.Id}
            >
              {s.Name}
            </button>
          ))}
        </div>
        <div class="seasons-tools">
          {total > 0 && <span class="seasons-count">{watched} of {total} watched</span>}
          <button type="button" class="btn btn-secondary" disabled={busy || !available.length} aria-pressed={allWatched ? 'true' : 'false'} onClick={markSeason}>
            <Icon name="check" size={18} stroke={2.2} />
            {allWatched ? t('Mark season unwatched') : t('Mark season watched')}
          </button>
          <button
            type="button"
            class="btn icon-btn"
            aria-label={desc ? t('Sort episodes, last first') : t('Sort episodes, first first')}
            title={desc ? t('Newest first') : t('Oldest first')}
            aria-pressed={desc ? 'true' : 'false'}
            onClick={() => setDesc((d) => !d)}
          >
            <Icon name="sortEpisodes" size={18} />
          </button>
        </div>
      </div>

      {error && !data ? (
        <div class="error-box" role="alert">
          <h2>{t('Couldn\'t load the episodes')}</h2>
          <button type="button" class="btn btn-secondary" onClick={reload}><Icon name="refresh" size={18} />{t('Try again')}</button>
        </div>
      ) : loading && !data ? (
        <div class="ep-grid" aria-busy="true">
          {Array.from({ length: 4 }, (_, i) => (
            <div key={i} class="ep-row" aria-hidden="true">
              <div class="ep-thumb skel" />
              <div class="ep-text"><span class="skel skel-line" style={{ width: '50%' }} /><span class="skel skel-line" style={{ width: '30%' }} /><span class="skel skel-line" style={{ width: '90%' }} /></div>
            </div>
          ))}
        </div>
      ) : !episodes.length ? (
        <p class="seasons-empty">{t('No episodes in this season yet.')}</p>
      ) : (
        <div class="ep-grid">
          {shown.map((ep) => <EpisodeRow key={ep.Id} ep={ep} isNext={ep.Id === upNextId} isCurrent={ep.Id === currentId} />)}
        </div>
      )}

      {!showAll && ordered.length > 6 && (
        <button type="button" class="btn btn-secondary seasons-more" onClick={() => setShowAll(true)}>
          Show all {total} episodes
          <Icon name="chevronDown" size={16} stroke={2.2} />
        </button>
      )}
    </section>
  );
}

function EpisodeRow({ ep, isNext, isCurrent }: { ep: BaseItem; isNext: boolean; isCurrent: boolean }) {
  const played = !!ep.UserData?.Played;
  const progress = progressFraction(ep);
  const inProgress = !played && progress > 0;
  const premiere = ep.PremiereDate ? dayOf(ep.PremiereDate)! : undefined;
  const upcoming = ep.LocationType === 'Virtual' && (!premiere || premiere.getTime() > Date.now());
  const missing = ep.LocationType === 'Virtual' && !upcoming;
  const art = cardArt(ep, 480);
  const num = ep.IndexNumber !== undefined && ep.IndexNumber !== null ? (ep.IndexNumberEnd ? `${ep.IndexNumber}–${ep.IndexNumberEnd}` : String(ep.IndexNumber)) : '';
  const meta = upcoming || missing
    ? (premiere ? fmtDate(premiere) : '')
    : join(runtime(ep), premiere && fmtDate(premiere), inProgress && timeLeft(remainingSeconds(ep)));
  const synopsis = ep.Overview || (upcoming ? t('Episode details will appear once the server receives metadata for this episode.') : '');
  const cls = 'ep-row' + (isNext || isCurrent ? ' is-next' : '') + (upcoming || missing ? ' is-upcoming' : '');
  const body = (
    <>
      <div class="ep-thumb">
        <div class="ep-art"><Art art={art} title={ep.SeriesName ?? ep.Name} /></div>
        {played && <WatchedCheck />}
        {inProgress && (
          <>
            <div class="ep-resume"><span class="play-disc is-48"><Icon name="playRound" size={20} /></span></div>
            <div class="bar is-6 ep-bar" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(progress * 100)} aria-label={t('Progress')}>
              <i style={{ width: pct(progress) }} />
            </div>
          </>
        )}
        {!inProgress && !upcoming && !missing && <div class="ep-hover" aria-hidden="true"><span class="play-disc is-48"><Icon name="playRound" size={20} /></span></div>}
        {upcoming && premiere && <span class="tag ep-airs">Airs {fmtWeekdayDate(premiere)}</span>}
        {missing && <span class="tag ep-airs">{t('Missing')}</span>}
      </div>
      <div class="ep-text">
        <div class="ep-title-line">
          <span class="ep-title">{num ? `${num} · ${ep.Name}` : ep.Name}</span>
          {isNext && <span class="ep-next">{t('Up next')}</span>}
          {isNewEpisode(ep) && !upcoming && <span class="ep-new">{t('New')}</span>}
        </div>
        {meta && <span class="ep-meta">{meta}</span>}
        {synopsis && <span class="ep-synopsis">{synopsis}</span>}
      </div>
    </>
  );
  if (upcoming || missing) return <div class={cls} aria-disabled="true">{body}</div>;
  return (
    <a
      href={href(playPath(ep))}
      class={cls}
      aria-label={`${inProgress ? t('Resume') : t('Play')} episode ${num}, ${ep.Name}${played ? ', watched' : ''}`}
      onClick={(e) => {
        if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey) return;
        e.preventDefault();
        navigate(playPath(ep));
      }}
    >
      {body}
    </a>
  );
}
