import { useEffect, useMemo, useState } from 'preact/hooks';
import { JELLYFIN_WEB } from '../../api/client';
import { getEpisodes, getItem, getItems, getNextUp, getResume, getSeasons } from '../../api/jellyfin';
import { backdropUrl, personImageUrl, primaryUrl } from '../../api/images';
import type { BaseItem } from '../../api/types';
import { seerrDetails, type SeerrDetails } from '../../api/seedy';
import { meStore } from '../../lib/app-state';
import { episodeTag, fmtDate, plural, progressFraction, remainingSeconds, runtime, timeLeft, yearRange, dayOf } from '../../lib/format';
import { Link, back, navigate, setQuery, useLocation } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { manageChanged } from './manage/host';
import { Header } from '../../ui/navbar/Header';
import { Icon } from '../../ui/icons';
import { MenuItem, toast } from '../../ui/overlay';
import { playPath } from '../../ui/cards';
import {
  CardGrid, EmptyState, ErrorState, GridSkeleton, playRandom, resolvePlayable, seasonsLabel, toggleFavorite, togglePlayed,
  useItemList, yearOf
} from './parts';
import {
  CastRow, DetailsHero, DetailsSkeleton, MetaLine, MoreActions, SeasonEpisodes, SimilarRow, SquareToggle, TrailerButton,
  fallbackLibraryName, heroArt, names, orderSeasons, peopleOf, seriesStatus, useView, type InfoRow, type MissingSeason
} from './details-parts';
import { RequestSeasonsDialog, markRequested, seasonList } from './request-seasons';
import { techBadges, techLines } from './media-info';
import './details.css';
import { MusicAlbumPage } from '../music/Album';
import { MusicArtistPage } from '../music/Artist';
import { t, tn } from '../../lib/i18n';

export function Details({ id }: { id: string }) {
  const [version, setVersion] = useState(0);
  const { data: item, error, loading, reload } = useAsync(() => getItem(id), [id, version]);
  /* A dialog changed this title (metadata, match, images) or deleted it. */
  const managed = useStore(manageChanged);
  useEffect(() => {
    if (!managed.n) return;
    if (managed.deleted.includes(id) || (item?.SeriesId && managed.deleted.includes(item.SeriesId))) {
      back(item?.SeriesId && !managed.deleted.includes(item.SeriesId) ? `/details/${item.SeriesId}` : '/');
    } else setVersion((v) => v + 1);
  }, [managed.n]);

  useEffect(() => {
    if (!item) return;
    if (item.Type === 'Season' && item.SeriesId) navigate(`/details/${item.SeriesId}?season=${item.Id}`, { replace: true });
    else if (item.Type === 'CollectionFolder' || item.Type === 'UserView') navigate(`/library/${item.Id}`, { replace: true });
    else if (item.Type === 'Audio' && item.AlbumId) navigate(`/details/${item.AlbumId}`, { replace: true });
  }, [item?.Id]);

  if (!item) {
    if (loading) return <Shell><DetailsSkeleton /></Shell>;
    const missing = (error as { status?: number } | undefined)?.status === 404 || (error as { status?: number } | undefined)?.status === 400;
    return (
      <div class="page">
        <Header active={null} />
        <main class="page-main">
          {missing
            ? <EmptyState icon="searchX" title={t('This title isn\'t here')} text={t('It may have been removed from the server, or it\'s outside what this profile can see.')} action={<Link to="/" class="btn btn-primary is-sm">{t('Back to home')}</Link>} />
            : <ErrorState error={error} retry={reload} title={t('Couldn\'t load this title')} />}
        </main>
      </div>
    );
  }

  const changed = () => setVersion((v) => v + 1);
  switch (item.Type) {
    case 'Series': return <SeriesPage item={item} onChanged={changed} />;
    case 'Season': return <Shell><DetailsSkeleton /></Shell>;
    case 'Episode': return <EpisodePage item={item} onChanged={changed} />;
    case 'Person': return <PersonPage item={item} />;
    case 'Playlist': return item.MediaType === 'Audio' ? <MusicAlbumPage item={item} playlist /> : <CollectionPage item={item} />;
    case 'BoxSet': case 'Folder': case 'PhotoAlbum': return <CollectionPage item={item} />;
    case 'MusicAlbum': return <MusicAlbumPage item={item} />;
    case 'MusicArtist': return <MusicArtistPage item={item} />;
    default: return <MoviePage item={item} />;
  }
}

function Shell({ children, viewId }: { children: preact.ComponentChildren; viewId?: string }) {
  return (
    <div class="details">
      <Header variant="overlay" active={viewId ? { viewId } : null} />
      {children}
    </div>
  );
}

/* Keep a local copy so toggles update instantly. */
function useLocal(item: BaseItem) {
  const [it, setIt] = useState(item);
  useEffect(() => setIt(item), [item]);
  return [it, setIt] as const;
}

const episodeLabel = (ep: BaseItem) =>
  ep.ParentIndexNumber ? `S${ep.ParentIndexNumber} · E${ep.IndexNumber ?? ''}` : ep.IndexNumber !== undefined ? `E${ep.IndexNumber}` : '';

function backTo(item: BaseItem, view?: BaseItem) {
  return { label: view?.Name ?? fallbackLibraryName(item), to: view ? `/library/${view.Id}` : '/' };
}

/* ───────── Series ───────── */

interface Target { ep: BaseItem; resume: boolean }

async function seriesTarget(seriesId: string): Promise<Target | null> {
  const resume = (await getResume(1, seriesId).catch(() => null))?.Items[0];
  if (resume) return { ep: resume, resume: true };
  const next = (await getNextUp(1, seriesId).catch(() => null))?.Items[0];
  if (next) return { ep: next, resume: false };
  const eps = (await getEpisodes(seriesId, undefined, { limit: 40, isMissing: false }).catch(() => null))?.Items ?? [];
  const first = eps.find((e) => (e.ParentIndexNumber ?? 1) > 0 && e.LocationType !== 'Virtual') ?? eps[0];
  return first ? { ep: first, resume: false } : null;
}

function SeriesPage({ item: initial, onChanged }: { item: BaseItem; onChanged: () => void }) {
  const [item, setItem] = useLocal(initial);
  const view = useView(item);
  const loc = useLocation();
  const [tick, setTick] = useState(0);
  const seasons = useAsync(() => getSeasons(item.Id).then((r) => orderSeasons(r.Items)), [item.Id, tick]);
  const target = useAsync(() => seriesTarget(item.Id), [item.Id, tick]);
  const tech = useAsync(
    () => (target.data?.ep ? getItem(target.data.ep.Id, ['MediaSources', 'MediaStreams']) : Promise.resolve(null)),
    [target.data?.ep?.Id]
  );
  const refresh = () => { setTick((n) => n + 1); onChanged(); };
  const gaps = useMissingSeasons(item, seasons.data);

  const list = gaps.list;
  const requested = loc.query.get('season');
  const selected = (requested && list.some((s) => s.Id === requested) ? requested : undefined)
    ?? (target.data?.ep.SeasonId && list.some((s) => s.Id === target.data!.ep.SeasonId) ? target.data.ep.SeasonId : undefined)
    ?? list.find((s) => (s.IndexNumber ?? 1) > 0)?.Id
    ?? list[0]?.Id;

  const pt = target.data;
  const back = backTo(item, view);
  const lines = techLines(tech.data);
  const actors = peopleOf(item, 'Actor');
  const creators = peopleOf(item, 'Creator').length ? peopleOf(item, 'Creator') : peopleOf(item, 'Writer');
  const regularSeasons = list.filter((s) => (s.IndexNumber ?? 1) > 0).length;
  const seasonCount = gaps.total > gaps.onServerCount
    ? `${plural(gaps.total, 'season')} · ${t('{n} on the server', { n: gaps.onServerCount })}`
    : regularSeasons ? plural(regularSeasons, 'season') : seasonsLabel(item);

  const play = async (restart = false) => {
    const ep = pt?.ep ?? (await resolvePlayable(item));
    if (ep) navigate(playPath(ep, { restart }));
    else toast(t('Nothing to play yet'), 'error');
  };
  const playFirst = async () => {
    const eps = (await getEpisodes(item.Id, undefined, { limit: 40, isMissing: false }).catch(() => null))?.Items ?? [];
    const first = eps.find((e) => (e.ParentIndexNumber ?? 1) > 0 && e.LocationType !== 'Virtual') ?? eps[0];
    if (first) navigate(playPath(first, { restart: true }));
    else toast(t('Nothing to play yet'), 'error');
  };
  const shuffle = async () => {
    const ep = await playRandom({ parentId: item.Id, includeItemTypes: ['Episode'], isMissing: false }).catch(() => undefined);
    if (ep) navigate(playPath(ep));
    else toast(t('Nothing to shuffle yet'), 'error');
  };

  const info: InfoRow[] = [
    { label: 'Created by', value: names(creators, 2) },
    { label: 'Starring', value: names(actors, 3) },
    { label: 'Network', value: item.Studios?.[0]?.Name },
    { label: 'Status', value: seriesStatus(item) },
    { label: 'Video', value: lines.video },
    { label: 'Audio', value: lines.audio },
    { label: 'Subtitles', value: lines.subtitles }
  ];

  return (
    <Shell viewId={view?.Id}>
      <DetailsHero
        art={heroArt(item)}
        itemId={item.Id}
        overline={back.label}
        overlineTo={back.to}
        title={item.Name}
        meta={<MetaLine parts={[yearRange(item), seasonCount]} rating={item.OfficialRating} score={item.CommunityRating} genres={item.Genres?.slice(0, 3)} />}
        badges={techBadges(tech.data)}
        overview={item.Overview}
        info={info}
        progress={pt?.resume ? { fraction: progressFraction(pt.ep), text: `${timeLeft(remainingSeconds(pt.ep))} of ${pt.ep.Name}` } : undefined}
        notice={gaps.notice}
        actions={
          <>
            <button type="button" class="btn btn-primary" onClick={() => play()}>
              <Icon name="play" size={20} />
              {pt ? `${pt.resume ? t('Resume') : t('Play')} ${episodeLabel(pt.ep)}`.trim() : 'Play'}
            </button>
            <TrailerButton item={item} />
            <button type="button" class="btn btn-square" aria-label={t('Shuffle episodes')} onClick={shuffle}>
              <Icon name="shuffle" size={22} />
            </button>
            <SquareToggle
              icon="check"
              on={!!item.UserData?.Played}
              onLabel="Mark series as unwatched"
              offLabel="Mark series as watched"
              onClick={() => togglePlayed(item, setItem).then(refresh)}
            />
            <SquareToggle icon="heart" on={!!item.UserData?.IsFavorite} onLabel="Remove from favorites" offLabel="Add to favorites" onClick={() => toggleFavorite(item, setItem)} />
            <MoreActions item={item}>
              {(close) => list.length > 0 && <MenuItem icon="restart" label={t('Play from the first episode')} onSelect={() => { close(); void playFirst(); }} />}
            </MoreActions>
          </>
        }
      />
      <main class="details-main">
        {list.length > 0 && (
          <SeasonEpisodes
            series={item}
            seasons={list}
            selected={selected}
            onSelect={(sid) => setQuery({ season: sid })}
            upNextId={pt?.ep.Id}
            onChanged={refresh}
            version={tick}
            missing={gaps.missing}
            onRequest={gaps.ask}
          />
        )}
        {seasons.loading && !seasons.data && <div class="details-pad"><GridSkeleton count={2} /></div>}
        <CastRow people={item.People ?? []} />
        <SimilarRow id={item.Id} />
      </main>
      {gaps.dialog}
    </Shell>
  );
}

/* Seasons Seerr knows that aren't on the server (Series details · request missing seasons board): a banner in the
   hero, dashed tabs among the seasons, and the request dialog behind both. Only for people who can request. */
function useMissingSeasons(item: BaseItem, seasons: BaseItem[] | undefined) {
  const rq = useStore(meStore).requests;
  const tmdbId = Number(item.ProviderIds?.Tmdb ?? item.ProviderIds?.tmdb ?? 0) || 0;
  const can = rq.connected && rq.canRequest && rq.requestButtons && tmdbId > 0;
  const fetched = useAsync(() => (can ? seerrDetails('tv', tmdbId).catch(() => null) : Promise.resolve(null)), [tmdbId, can]);
  const [details, setDetails] = useState<SeerrDetails | null>(null);
  useEffect(() => setDetails(fetched.data ?? null), [fetched.data]);
  const [ask, setAsk] = useState<number[] | null>(null);

  const all = seasons ?? [];
  // Seasons with files; a virtual one (Jellyfin's "missing episodes") counts as missing.
  const onServer = useMemo(
    () => new Set(all.filter((s) => s.LocationType !== 'Virtual' && (s.IndexNumber ?? 0) > 0).map((s) => s.IndexNumber!)),
    [seasons]
  );
  const known = (details?.seasons ?? []).filter((s) => s.seasonNumber > 0);
  const away = known.filter((s) => !onServer.has(s.seasonNumber) && s.status !== 'available' && s.status !== 'partial' && s.status !== 'blocklisted');
  const awayNums = new Set(away.map((s) => s.seasonNumber));
  const now = Date.now();
  const unaired = (s: { airDate?: string }) => { const d = dayOf(s.airDate); return !d || d.getTime() > now; };
  const missing: MissingSeason[] = away.map((s) => ({
    n: s.seasonNumber,
    name: s.name || t('Season {n}', { n: s.seasonNumber }),
    year: unaired(s) ? dayOf(s.airDate)?.getFullYear() : undefined,
    requested: s.status === 'pending' || s.status === 'processing'
  }));
  const open = missing.filter((m) => !m.requested);
  const requested = missing.filter((m) => m.requested);
  const soon = away.filter((s) => !(s.status === 'pending' || s.status === 'processing') && unaired(s));

  const notice = open.length > 0 && (
    <div class="dhero-missing">
      <span aria-hidden="true" class="dhero-missing-icon"><Icon name="request" size={18} /></span>
      <span class="dhero-missing-text">
        <span class="dhero-missing-title">
          {open.length === 1 ? t("{season} isn't on the server", { season: seasonList([open[0].n]) })
            : open.length <= 4 ? t("{seasons} aren't on the server", { seasons: seasonList(open.map((m) => m.n)) })
            : t("{n} seasons aren't on the server", { n: open.length })}
        </span>
        {soon.length > 0 ? (
          <span class="dhero-missing-sub">
            {soon.length === 1 && dayOf(soon[0].airDate)
              ? t('{season} is announced for {year}', { season: seasonList([soon[0].seasonNumber]), year: dayOf(soon[0].airDate)!.getFullYear() })
              : tn(soon.length, '{season} is announced', '{season} are announced', { season: seasonList(soon.map((s) => s.seasonNumber)) })}
          </span>
        ) : requested.length > 0 ? (
          <span class="dhero-missing-sub">{tn(requested.length, '{season} is already requested', '{season} are already requested', { season: seasonList(requested.map((m) => m.n)) })}</span>
        ) : null}
      </span>
      <button type="button" class="btn btn-primary dhero-missing-btn" aria-haspopup="dialog" onClick={() => setAsk([])}>
        <Icon name="request" size={17} />
        {tn(open.length, 'Request {n} season', 'Request {n} seasons')}
      </button>
    </div>
  );

  const dialog = details && (
    <RequestSeasonsDialog
      open={ask !== null}
      context="library"
      target={{ tmdbId, title: item.Name, posterUrl: primaryUrl(item, 200), backdropUrl: backdropUrl(item, 780), posterPath: details.posterPath, backdropPath: details.backdropPath }}
      details={details}
      onServer={onServer}
      initial={ask ?? undefined}
      meta={[yearRange(item), known.length ? plural(known.length, 'season') : '', item.Studios?.[0]?.Name].filter(Boolean).join(' · ')}
      onClose={() => setAsk(null)}
      onDone={(r) => setDetails((d) => d && markRequested(d, r.seasons, r.is4k, r.status))}
    />
  );

  return {
    // Jellyfin's virtual seasons give way to the dashed tab for the same season.
    list: all.filter((s) => !(s.LocationType === 'Virtual' && awayNums.has(s.IndexNumber ?? -1))),
    missing: can ? missing : undefined,
    total: known.length,
    onServerCount: known.length - away.length,
    ask: (n: number) => setAsk(open.some((m) => m.n === n) ? [n] : []),
    notice,
    dialog
  };
}

/* ───────── Movie (and any other playable video) ───────── */

function MoviePage({ item: initial }: { item: BaseItem }) {
  const [item, setItem] = useLocal(initial);
  const view = useView(item);
  const back = backTo(item, view);
  const fraction = item.UserData?.Played ? 0 : progressFraction(item);
  const resume = fraction > 0;
  const lines = techLines(item);
  const released = item.PremiereDate ? fmtDate(dayOf(item.PremiereDate)!) : item.ProductionYear ? String(item.ProductionYear) : undefined;
  const info: InfoRow[] = [
    { label: 'Directed by', value: names(peopleOf(item, 'Director'), 2) },
    { label: 'Starring', value: names(peopleOf(item, 'Actor'), 3) },
    { label: 'Studio', value: item.Studios?.slice(0, 2).map((s) => s.Name).join(', ') },
    { label: 'Released', value: released },
    { label: 'Video', value: lines.video },
    { label: 'Audio', value: lines.audio },
    { label: 'Subtitles', value: lines.subtitles }
  ];
  return (
    <Shell viewId={view?.Id}>
      <DetailsHero
        art={heroArt(item)}
        itemId={item.Id}
        overline={back.label}
        overlineTo={back.to}
        title={item.Name}
        meta={<MetaLine parts={[yearOf(item), runtime(item)]} rating={item.OfficialRating} score={item.CommunityRating} genres={item.Genres?.slice(0, 3)} />}
        badges={techBadges(item)}
        overview={item.Overview}
        info={info}
        progress={resume ? { fraction, text: timeLeft(remainingSeconds(item)) } : undefined}
        actions={
          <>
            <button type="button" class="btn btn-primary" onClick={() => navigate(playPath(item))}>
              <Icon name="play" size={20} />
              {resume ? t('Resume') : t('Play')}
            </button>
            <TrailerButton item={item} />
            <SquareToggle icon="check" on={!!item.UserData?.Played} onLabel="Mark as unwatched" offLabel="Mark as watched" onClick={() => togglePlayed(item, setItem)} />
            <SquareToggle icon="heart" on={!!item.UserData?.IsFavorite} onLabel="Remove from favorites" offLabel="Add to favorites" onClick={() => toggleFavorite(item, setItem)} />
            <MoreActions item={item}>
              {(close) => resume && <MenuItem icon="refresh" label={t('Play from the beginning')} onSelect={() => { close(); navigate(playPath(item, { restart: true })); }} />}
            </MoreActions>
          </>
        }
      />
      <main class="details-main">
        <CastRow people={item.People ?? []} />
        <SimilarRow id={item.Id} />
      </main>
    </Shell>
  );
}

/* ───────── Episode ───────── */

function EpisodePage({ item: initial, onChanged }: { item: BaseItem; onChanged: () => void }) {
  const [item, setItem] = useLocal(initial);
  const view = useView(item);
  const [tick, setTick] = useState(0);
  const series = useAsync(() => (item.SeriesId ? getItem(item.SeriesId, ['Overview', 'Genres']) : Promise.resolve(null)), [item.SeriesId]);
  const seasons = useAsync(() => (item.SeriesId ? getSeasons(item.SeriesId).then((r) => orderSeasons(r.Items)) : Promise.resolve([] as BaseItem[])), [item.SeriesId]);
  const [season, setSeason] = useState(item.SeasonId);
  const fraction = item.UserData?.Played ? 0 : progressFraction(item);
  const resume = fraction > 0;
  const lines = techLines(item);
  const aired = item.PremiereDate ? fmtDate(dayOf(item.PremiereDate)!) : undefined;
  const guests = peopleOf(item, 'GuestStar');
  const info: InfoRow[] = [
    { label: 'Directed by', value: names(peopleOf(item, 'Director'), 2) },
    { label: 'Written by', value: names(peopleOf(item, 'Writer'), 2) },
    { label: guests.length ? t('Guest stars') : t('Starring'), value: names(guests.length ? guests : peopleOf(item, 'Actor'), 3) },
    { label: 'Aired', value: aired },
    { label: 'Video', value: lines.video },
    { label: 'Audio', value: lines.audio },
    { label: 'Subtitles', value: lines.subtitles }
  ];
  const changed = () => { setTick((n) => n + 1); onChanged(); };
  return (
    <Shell viewId={view?.Id}>
      <DetailsHero
        art={heroArt(item, series.data)}
        itemId={item.Id}
        overline={item.SeriesName ?? 'Series'}
        overlineTo={item.SeriesId ? `/details/${item.SeriesId}` : undefined}
        title={item.Name}
        meta={<MetaLine parts={[episodeTag(item), item.PremiereDate ? String(dayOf(item.PremiereDate)!.getFullYear()) : undefined, runtime(item)]} rating={item.OfficialRating} score={item.CommunityRating} />}
        badges={techBadges(item)}
        overview={item.Overview}
        info={info}
        progress={resume ? { fraction, text: `${timeLeft(remainingSeconds(item))} of ${item.Name}` } : undefined}
        actions={
          <>
            <button type="button" class="btn btn-primary" onClick={() => navigate(playPath(item))}>
              <Icon name="play" size={20} />
              {`${resume ? t('Resume') : t('Play')} ${episodeLabel(item)}`.trim()}
            </button>
            <SquareToggle icon="check" on={!!item.UserData?.Played} onLabel="Mark as unwatched" offLabel="Mark as watched" onClick={() => togglePlayed(item, setItem).then(changed)} />
            <SquareToggle icon="heart" on={!!item.UserData?.IsFavorite} onLabel="Remove from favorites" offLabel="Add to favorites" onClick={() => toggleFavorite(item, setItem)} />
            <MoreActions item={item}>
              {(close) => (
                <>
                  {resume && <MenuItem icon="refresh" label={t('Play from the beginning')} onSelect={() => { close(); navigate(playPath(item, { restart: true })); }} />}
                  {item.SeriesId && <MenuItem icon="tv" label={item.SeriesName ? t('Go to {name}', { name: item.SeriesName }) : t('Go to series')} onSelect={() => { close(); navigate(`/details/${item.SeriesId}?season=${item.SeasonId ?? ''}`); }} />}
                </>
              )}
            </MoreActions>
          </>
        }
      />
      <main class="details-main">
        {series.data && (seasons.data?.length ?? 0) > 0 && (
          <SeasonEpisodes series={series.data} seasons={seasons.data!} selected={season} onSelect={setSeason} currentId={item.Id} onChanged={changed} version={tick} />
        )}
        <CastRow people={item.People ?? []} />
      </main>
    </Shell>
  );
}

/* ───────── Person ───────── */

function PersonPage({ item: initial }: { item: BaseItem }) {
  const [item, setItem] = useLocal(initial);
  const [more, setMore] = useState(false);
  const { items, setItems, replace } = useItemList();
  const films = useAsync(
    () => getItems({ personIds: [item.Id], includeItemTypes: ['Movie', 'Series'], sortBy: ['PremiereDate', 'ProductionYear', 'SortName'], sortOrder: 'Descending', limit: 200, fields: ['PrimaryImageAspectRatio', 'ProductionYear', 'Genres', 'ChildCount', 'DateCreated', 'SortName'] }),
    [item.Id]
  );
  useEffect(() => { if (films.data) setItems(films.data.Items); }, [films.data]);
  const portrait = personImageUrl({ Id: item.Id, PrimaryImageTag: item.ImageTags?.Primary }, 480);
  const born = item.PremiereDate ? dayOf(item.PremiereDate)! : undefined;
  const died = item.EndDate ? dayOf(item.EndDate)! : undefined;
  const place = (item as BaseItem & { ProductionLocations?: string[] }).ProductionLocations?.[0];
  const birth = [
    born ? (place ? t('Born {date} in {place}', { date: fmtDate(born), place }) : t('Born {date}', { date: fmtDate(born) })) : place ? t('Born in {place}', { place }) : '',
    died ? t('Died {date}', { date: fmtDate(died) }) : ''
  ].filter(Boolean).join(' · ');
  const initials = item.Name.split(/\s+/).filter(Boolean).slice(0, 2).map((w) => w[0]).join('').toUpperCase();
  const fav = !!item.UserData?.IsFavorite;
  return (
    <div class="page">
      <Header active={null} />
      <main class="page-main person">
        <div class="person-head">
          <div class="person-portrait" aria-hidden="true">{portrait ? <img src={portrait} alt="" /> : initials}</div>
          <div class="person-text">
            <span class="overline">{t('Cast & crew')}</span>
            <h1 class="person-name">{item.Name}</h1>
            {birth && <span class="person-born">{birth}</span>}
            {item.Overview && (
              <>
                <p class={'person-bio' + (more ? ' is-open' : '')}>{item.Overview}</p>
                {item.Overview.length > 420 && (
                  <button type="button" class="link-btn row-link" aria-expanded={more ? 'true' : 'false'} onClick={() => setMore((m) => !m)}>{more ? t('Show less') : t('Read more')}</button>
                )}
              </>
            )}
            <div class="person-actions">
              <button type="button" class="btn btn-secondary" aria-pressed={fav ? 'true' : 'false'} onClick={() => toggleFavorite(item, setItem)}>
                <Icon name="heart" size={18} filled={fav} color={fav ? 'var(--seedy-accent)' : undefined} />
                {fav ? t('In favorites') : t('Add to favorites')}
              </button>
              <a class="btn btn-ghost" href={`${JELLYFIN_WEB}#/details?id=${item.Id}`} target="_blank" rel="noreferrer"><Icon name="external" size={18} />{t('Open in Jellyfin')}</a>
            </div>
          </div>
        </div>
        <section class="browse-section" aria-label={t('Filmography')}>
          <div class="browse-section-head">
            <div class="row-head-main"><h2 class="row-title">{t('Filmography')}</h2>{films.data && <span class="row-sub">{plural(films.data.TotalRecordCount, 'title')} in your library</span>}</div>
          </div>
          {films.loading && !films.data ? <GridSkeleton count={8} />
            : films.error && !films.data ? <ErrorState error={films.error} retry={films.reload} />
            : items.length ? <CardGrid items={items} onReplace={replace} />
            : <EmptyState icon="film" title={t('Nothing in your library yet')} text={`Titles with ${item.Name} will show up here once they're on the server.`} />}
        </section>
      </main>
    </div>
  );
}

/* ───────── Collections, playlists and folders ───────── */

function CollectionPage({ item: initial }: { item: BaseItem }) {
  const [item, setItem] = useLocal(initial);
  const view = useView(item);
  const { items, setItems, replace } = useItemList();
  const kids = useAsync(
    () => getItems({ parentId: item.Id, recursive: false, sortBy: item.Type === 'Playlist' ? undefined : ['PremiereDate', 'ProductionYear', 'SortName'], limit: 300, fields: ['PrimaryImageAspectRatio', 'ProductionYear', 'Genres', 'ChildCount', 'DateCreated', 'SortName', 'Overview'] }),
    [item.Id]
  );
  useEffect(() => { if (kids.data) setItems(kids.data.Items); }, [kids.data]);
  const first = items[0];
  const years = items.map((i) => i.ProductionYear).filter((y): y is number => !!y);
  const span = years.length ? (Math.min(...years) === Math.max(...years) ? String(years[0]) : `${Math.min(...years)}–${Math.max(...years)}`) : undefined;
  const back = item.Type === 'BoxSet' ? backTo(item, view) : view ? backTo(item, view) : { label: item.Type === 'Playlist' ? t('Playlists') : t('Home'), to: '/' };
  const playFirst = async () => {
    const target = first ? await resolvePlayable(first) : undefined;
    if (target) navigate(playPath(target));
  };
  const shuffle = async () => {
    const r = await playRandom({ parentId: item.Id, excludeItemTypes: ['Folder', 'BoxSet', 'Series', 'Season'], isMissing: false }).catch(() => undefined);
    if (r) navigate(playPath(r));
  };
  return (
    <Shell viewId={view?.Id}>
      <DetailsHero
        art={heroArt(item, first)}
        overline={back.label}
        overlineTo={back.to}
        title={item.Name}
        meta={<MetaLine parts={[kids.data ? plural(kids.data.TotalRecordCount, 'title') : undefined, span]} rating={item.OfficialRating} score={item.CommunityRating} genres={item.Genres?.slice(0, 3)} />}
        overview={item.Overview}
        actions={
          <>
            <button type="button" class="btn btn-primary" disabled={!first} onClick={playFirst}>
              <Icon name="play" size={20} />
              {t('Play')}
            </button>
            <button type="button" class="btn btn-square" aria-label={t('Shuffle')} disabled={!first} onClick={shuffle}><Icon name="shuffle" size={22} /></button>
            <SquareToggle icon="check" on={!!item.UserData?.Played} onLabel="Mark as unwatched" offLabel="Mark as watched" onClick={() => togglePlayed(item, setItem).then(kids.reload)} />
            <SquareToggle icon="heart" on={!!item.UserData?.IsFavorite} onLabel="Remove from favorites" offLabel="Add to favorites" onClick={() => toggleFavorite(item, setItem)} />
            <MoreActions item={item} />
          </>
        }
      />
      <main class="details-main">
        <section class="browse-section details-pad" aria-label={t('In this collection')}>
          <div class="browse-section-head"><div class="row-head-main"><h2 class="row-title">{item.Type === 'Playlist' ? t('In this playlist') : t('In this collection')}</h2></div></div>
          {kids.loading && !kids.data ? <GridSkeleton count={8} />
            : kids.error && !kids.data ? <ErrorState error={kids.error} retry={kids.reload} />
            : items.length ? <CardGrid items={items} onReplace={replace} />
            : <EmptyState icon="folder" title={t('Nothing here yet')} text={t('Titles added to this collection will show up here.')} />}
        </section>
      </main>
    </Shell>
  );
}
