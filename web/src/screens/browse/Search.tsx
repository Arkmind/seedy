import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { getItems } from '../../api/jellyfin';
import { seerrRequest, seerrSearch, type SeerrItem } from '../../api/seedy';
import type { BaseItem } from '../../api/types';
import { meStore } from '../../lib/app-state';
import { episodeTag, plural, yearRange } from '../../lib/format';
import { navigate, setQuery, useLocation } from '../../lib/router';
import { useStore } from '../../lib/store';
import { CastCard, ItemCard, PosterCard } from '../../ui/cards';
import { Header } from '../../ui/navbar/Header';
import { Icon } from '../../ui/icons';
import { toast } from '../../ui/overlay';
import { rowMeta, search, type Results } from '../../ui/search/engine';
import { SearchIdle } from '../../ui/search/parts';
import { addRecent } from '../../ui/search/recent';
import './search.css';
import { t } from '../../lib/i18n';
import { RequestSeasonsDialog } from './request-seasons';

/* Search (Search board): the bar's box holds the query; the page shows everything the bar's dropdown sums up as
   "All N results", then "Request it" from Seerr when nothing in the library matches. `?genre=` lists a genre
   ("Browse by genre"). Typing in the box updates this page and its address. */

function titleMeta(item: BaseItem): string {
  const kind = item.Type === 'Series' ? 'Series' : item.Type === 'Movie' ? 'Movie' : '';
  return [kind, yearRange(item)].filter(Boolean).join(' · ');
}

export function Search() {
  const loc = useLocation();
  const q = (loc.query.get('q') ?? '').trim();
  const genre = (loc.query.get('genre') ?? '').trim();

  /* What was searched is remembered when the person opens a result or leaves the page. */
  const lastQ = useRef(q);
  lastQ.current = q;
  useEffect(() => () => addRecent(lastQ.current), []);

  return (
    <div class="page">
      <Header active="search" />
      <main class="search-main" onClick={(e) => { if ((e.target as HTMLElement).closest('a')) addRecent(q); }}>
        {q ? <LibraryResults key={q} q={q} /> : genre ? <GenreResults key={genre} genre={genre} /> : <Idle />}
      </main>
    </div>
  );
}

/* ── Nothing typed yet ── */

function Idle() {
  return (
    <section class="search-idle" aria-label={t('Search')}>
      <SearchIdle
        page
        onRecent={(s) => navigate(`/search?q=${encodeURIComponent(s)}`, { replace: true, keepScroll: true })}
        onGenre={(g) => setQuery({ genre: g, q: null })}
      />
    </section>
  );
}

/* ── Results for a query ── */

function LibraryResults({ q }: { q: string }) {
  const me = useStore(meStore);
  const [lib, setLib] = useState<{ data?: Results; error?: boolean } | null>(null);
  const [tick, setTick] = useState(0);
  const [seerr, setSeerr] = useState<{ items?: SeerrItem[]; total?: number; error?: boolean } | null>(null);
  const [requesting, setRequesting] = useState<number | null>(null);
  const [series, setSeries] = useState<SeerrItem | null>(null);

  useEffect(() => {
    const ctrl = new AbortController();
    setLib(null);
    search(q, 'page', ctrl.signal).then(
      (data) => { if (!ctrl.signal.aborted) setLib({ data }); },
      (e: Error) => { if (!ctrl.signal.aborted && e?.name !== 'AbortError') setLib({ error: true }); }
    );
    return () => ctrl.abort();
  }, [q, tick]);

  const data = lib?.data;
  const titles = data ? data.titles.filter((h) => h.kind === 'movie' || h.kind === 'show').map((h) => h.item) : [];
  const collections = data ? data.titles.filter((h) => h.kind === 'collection').map((h) => h.item) : [];
  const episodes = data ? data.episodes.map((h) => h.item) : [];
  const music = data ? data.titles.filter((h) => h.kind === 'music').map((h) => h.item) : [];
  const people = data ? data.people.map((h) => h.item) : [];
  const empty = !!data && !data.hits.length;

  /* Seerr answers after the library: "Request it" when nothing matches (Search board), and "Not what you're searching
     for? Request it" under the library's results, so a title that isn't on the server is always one click away. */
  const wantSeerr = !!data && me.requests.connected && me.requests.requestInSearch;
  useEffect(() => {
    if (!wantSeerr) { setSeerr(null); return; }
    let live = true;
    setSeerr({});
    seerrSearch(q).then(
      (r) => { if (live) setSeerr({ items: r.results, total: r.total }); },
      () => { if (live) setSeerr({ error: true }); }
    );
    return () => { live = false; };
  }, [wantSeerr, q]);

  const request = async (item: SeerrItem) => {
    if (item.mediaType === 'tv') { setSeries(item); return; }
    setRequesting(item.tmdbId);
    try {
      const r = await seerrRequest({ mediaType: item.mediaType, tmdbId: item.tmdbId });
      setSeerr((s) => s && s.items ? { ...s, items: s.items.map((i) => (i.tmdbId === item.tmdbId && i.mediaType === item.mediaType ? { ...i, status: r.status, requestedByMe: true, requestedBy: undefined } : i)) } : s);
      toast(t('Requested {title}', { title: item.title }));
    } catch {
      toast(t("Couldn't request {title}", { title: item.title }), 'error');
    } finally {
      setRequesting(null);
    }
  };

  const seasonsDialog = series && (
    <RequestSeasonsDialog
      open
      context="seerr"
      target={{ tmdbId: series.tmdbId, title: series.title, year: series.year, posterPath: series.posterPath, backdropPath: series.backdropPath }}
      onClose={() => setSeries(null)}
      onDone={(r) => setSeerr((s) => s && s.items ? { ...s, items: s.items.map((i) => (i.tmdbId === series.tmdbId && i.mediaType === 'tv' ? { ...i, status: r.status, requestedByMe: true, requestedBy: undefined } : i)) } : s)}
    />
  );

  if (lib?.error) {
    return (
      <div class="error-box" role="alert">
        <h2>{t('Search didn\'t work')}</h2>
        <p>{t('The server didn\'t answer. Check your connection and try again.')}</p>
        <button type="button" class="btn btn-secondary" onClick={() => setTick((n) => n + 1)}>{t('Try again')}</button>
      </div>
    );
  }

  if (!data) {
    return (
      <section class="search-section" aria-busy="true" aria-label={t('Searching')}>
        <div class="search-head"><span class="skel skel-line" style={{ width: '160px', height: '18px' }} /></div>
        <div class="search-grid"><GridSkeleton count={8} /></div>
      </section>
    );
  }

  if (empty) {
    return (
      <>
        <section aria-label={t('Library results')} class="empty search-empty">
          <span class="empty-icon" aria-hidden="true"><Icon name="searchX" size={26} /></span>
          <div class="empty-body">
            <h1 class="empty-title">{t('Nothing in your library for “{q}”', { q })}</h1>
            <p class="empty-text">
              {wantSeerr
                ? me.requests.canRequest
                  ? t('No movies, shows, episodes or people match. It may not be on the server yet: request it below.') : t('No movies, shows, episodes or people match. It may not be on the server yet: here is what Seerr knows about it.')
                : t('No movies, shows, episodes or people match. Check the spelling, or try a shorter part of the title.')}
            </p>
          </div>
        </section>

        {wantSeerr && seerr && <SeerrResults q={q} seerr={seerr} requesting={requesting} onRequest={request} />}
        {seasonsDialog}
      </>
    );
  }

  return (
    <>
      {data.loose && <p class="search-note" role="status">{t('Nothing matched “{q}” exactly. These are close.', { q })}</p>}
      {titles.length > 0 && (
        <Section title={t('Movies & shows')} count={titles.length}>
          <div class="search-grid">
            {titles.map((it) => (
              <ItemCard key={it.Id} item={it} meta={titleMeta(it)} titleSize={14} titleWeight={600} artWidth={520} watched={!!it.UserData?.Played} />
            ))}
          </div>
        </Section>
      )}
      {music.length > 0 && (
        <Section title={t('Music')} count={music.length}>
          <div class="search-grid">
            {music.map((it) => <ItemCard key={it.Id} item={it} meta={rowMeta(it)} titleSize={14} titleWeight={600} artWidth={520} />)}
          </div>
        </Section>
      )}
      {episodes.length > 0 && (
        <Section title={t('Episodes')} count={episodes.length}>
          <div class="search-grid">
            {episodes.map((it) => (
              <ItemCard
                key={it.Id}
                item={it}
                meta={[episodeTag(it), it.Name].filter(Boolean).join(' · ')}
                titleSize={14}
                titleWeight={600}
                artWidth={520}
                watched={!!it.UserData?.Played}
                progress={!it.UserData?.Played && it.RunTimeTicks ? (it.UserData?.PlaybackPositionTicks ?? 0) / it.RunTimeTicks : undefined}
                barSize={6}
              />
            ))}
          </div>
        </Section>
      )}
      {people.length > 0 && (
        <Section title={t('People')} count={people.length}>
          <div class="search-people">
            {people.map((p, i) => <CastCard key={p.Id} person={{ Id: p.Id, Name: p.Name, PrimaryImageTag: p.ImageTags?.Primary }} index={i} />)}
          </div>
        </Section>
      )}
      {collections.length > 0 && (
        <Section title={t('Collections')} count={collections.length}>
          <div class="search-grid">
            {collections.map((it) => (
              <ItemCard key={it.Id} item={it} meta={it.ChildCount ? plural(it.ChildCount, 'title') : t('Collection')} titleSize={14} titleWeight={600} artWidth={520} />
            ))}
          </div>
        </Section>
      )}
      {wantSeerr && seerr && <SeerrResults q={q} seerr={seerr} requesting={requesting} onRequest={request} more />}
      {seasonsDialog}
    </>
  );
}

/* Seerr's answer as posters with Request buttons. Under library results ("more") it leaves out what the library
   already has, and stays hidden when Seerr has nothing else. */
function SeerrResults({ q, seerr, requesting, onRequest, more }: {
  q: string;
  seerr: { items?: SeerrItem[]; total?: number; error?: boolean };
  requesting: number | null;
  onRequest: (item: SeerrItem) => void;
  more?: boolean;
}) {
  const me = useStore(meStore);
  const items = more ? seerr.items?.filter((it) => it.status !== 'available') : seerr.items;
  if (more && (seerr.error || (items && items.length === 0))) return null;
  const total = more ? items?.length ?? 0 : seerr.total ?? seerr.items?.length ?? 0;
  return (
    <section aria-label={t('Request it')} class={'search-section is-request' + (more ? ' is-more' : '')}>
      <div class="search-head">
        <h2 class="search-title">{more ? t('Not what you’re searching for? Request it') : t('Request it')}</h2>
        <span class="search-count">
          {items ? t('From Seerr · {count}', { count: plural(total, 'result') }) : seerr.error ? t('From Seerr') : t('Asking Seerr…')}
        </span>
      </div>
      {!items && !seerr.error && <div class="search-posters"><GridSkeleton count={4} poster /></div>}
      {seerr.error && <p class="search-note">{t('Seerr didn\'t answer, so requests can\'t be made right now.')}</p>}
      {items && items.length === 0 && <p class="search-note">{t('Seerr doesn’t know “{q}” either. Check the spelling, or try the original title.', { q })}</p>}
      {items && items.length > 0 && (
        <div class="search-posters">
          {items.map((it) => (
            <PosterCard
              key={it.mediaType + it.tmdbId}
              item={it}
              canRequest={me.requests.canRequest}
              onRequest={onRequest}
              requesting={requesting === it.tmdbId}
              showRequestButton
              requestedLabel={it.requestedByMe ? t('Requested by you') : it.requestedBy ? t('Requested by {name}', { name: it.requestedBy }) : t('Requested')}
            />
          ))}
        </div>
      )}
    </section>
  );
}

/* ── Browse by genre ── */

const GENRE_PAGE = 60;

function GenreResults({ genre }: { genre: string }) {
  const [items, setItems] = useState<BaseItem[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(false);
  const [page, setPage] = useState(0);
  const [tick, setTick] = useState(0);
  const sentinel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const ctrl = new AbortController();
    setLoading(true);
    setError(false);
    getItems({
      genres: [genre], includeItemTypes: ['Movie', 'Series'], recursive: true, sortBy: ['SortName'], sortOrder: 'Ascending',
      startIndex: page * GENRE_PAGE, limit: GENRE_PAGE, fields: ['PrimaryImageAspectRatio', 'ProductionYear', 'Genres', 'ChildCount']
    }, ctrl.signal).then(
      (r) => {
        if (ctrl.signal.aborted) return;
        setItems((list) => (page === 0 ? r.Items : [...list, ...r.Items.filter((x) => !list.some((y) => y.Id === x.Id))]));
        setTotal(r.TotalRecordCount);
        setLoading(false);
      },
      (e: Error) => { if (!ctrl.signal.aborted && e?.name !== 'AbortError') { setError(true); setLoading(false); } }
    );
    return () => ctrl.abort();
  }, [genre, page, tick]);

  const more = total !== null && items.length < total;
  useEffect(() => {
    const el = sentinel.current;
    if (!el || !more || loading || error || typeof IntersectionObserver !== 'function') return;
    const io = new IntersectionObserver((entries) => { if (entries.some((e) => e.isIntersecting)) setPage((p) => p + 1); }, { rootMargin: '600px' });
    io.observe(el);
    return () => io.disconnect();
  }, [more, loading, error, items.length]);

  return (
    <section class="search-section" aria-label={genre}>
      <div class="page-title-row">
        <div class="page-title">
          <h1>{genre}</h1>
          {total !== null && <span class="page-count">{plural(total, 'title')}</span>}
        </div>
      </div>
      {error && !items.length && (
        <div class="error-box" role="alert">
          <h2>Couldn't load {genre}</h2>
          <p>{t('The server didn\'t answer. Check your connection and try again.')}</p>
          <button type="button" class="btn btn-secondary" onClick={() => setTick((n) => n + 1)}>{t('Try again')}</button>
        </div>
      )}
      {!error && total === 0 && (
        <section class="empty search-empty">
          <span class="empty-icon" aria-hidden="true"><Icon name="film" size={26} /></span>
          <div class="empty-body">
            <h2 class="empty-title">No {genre.toLowerCase()} titles</h2>
            <p class="empty-text">{t('Nothing on this server is tagged with this genre yet.')}</p>
          </div>
        </section>
      )}
      {(items.length > 0 || (loading && page === 0)) && (
        <div class="search-grid">
          {items.map((it) => (
            <ItemCard key={it.Id} item={it} meta={titleMeta(it)} titleSize={14} titleWeight={600} artWidth={520} watched={!!it.UserData?.Played} />
          ))}
          {loading && <GridSkeleton count={page === 0 ? 12 : 4} />}
        </div>
      )}
      {more && !loading && <div ref={sentinel} aria-hidden="true" />}
      {error && items.length > 0 && (
        <button type="button" class="btn btn-secondary" onClick={() => setTick((n) => n + 1)}>{t('Try again')}</button>
      )}
    </section>
  );
}

function Section({ title, count, children }: { title: string; count: number; children: ComponentChildren }) {
  return (
    <section class="search-section" aria-label={title}>
      <div class="search-head">
        <h2 class="search-title">{title}</h2>
        <span class="search-count">{plural(count, 'result')}</span>
      </div>
      {children}
    </section>
  );
}

function GridSkeleton({ count, poster }: { count: number; poster?: boolean }) {
  return (
    <>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} class="card" aria-hidden="true">
          <div class={'card-media skel' + (poster ? ' is-poster' : '')} />
          <div class="card-text" style={{ gap: '8px' }}>
            <span class="skel skel-line" style={{ width: '70%' }} />
            <span class="skel skel-line" style={{ width: '45%' }} />
          </div>
        </div>
      ))}
    </>
  );
}
