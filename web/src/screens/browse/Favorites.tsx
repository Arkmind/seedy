import { useEffect, useState } from 'preact/hooks';
import { getItems } from '../../api/jellyfin';
import type { BaseItem, ItemsResult } from '../../api/types';
import { episodeTag, plural } from '../../lib/format';
import { Link, setQuery, useLocation } from '../../lib/router';
import { useAsync } from '../../lib/store';
import { CastCard, ItemCard } from '../../ui/cards';
import { Header } from '../../ui/navbar/Header';
import { Icon } from '../../ui/icons';
import { CardGrid, EmptyState, ErrorState, GridSkeleton, PageHead, getPersonsList, join, toggleFavorite } from './parts';
import './lists.css';
import { t } from '../../lib/i18n';

type GroupId = 'movies' | 'shows' | 'episodes' | 'collections' | 'albums' | 'artists' | 'people';
interface Group { id: GroupId; title: string; types?: string[] }

const GROUPS: Group[] = [
  { id: 'movies', title: 'Movies', types: ['Movie'] },
  { id: 'shows', title: 'Shows', types: ['Series'] },
  { id: 'episodes', title: 'Episodes', types: ['Episode'] },
  { id: 'collections', title: 'Collections', types: ['BoxSet'] },
  { id: 'albums', title: 'Albums', types: ['MusicAlbum'] },
  { id: 'artists', title: 'Artists', types: ['MusicArtist'] },
  { id: 'people', title: 'People' }
];

const FIELDS = ['PrimaryImageAspectRatio', 'ProductionYear', 'Genres', 'ChildCount', 'DateCreated', 'SortName'];

export function Favorites() {
  const loc = useLocation();
  const { data, error, loading, reload } = useAsync(
    () => Promise.all(GROUPS.map((g) =>
      (g.id === 'people'
        ? getPersonsList({ isFavorite: true, limit: 200 })
        : getItems({ includeItemTypes: g.types, isFavorite: true, recursive: true, sortBy: ['SortName'], limit: 200, fields: FIELDS })
      ).catch(() => ({ Items: [], TotalRecordCount: 0 } as ItemsResult))
    )),
    []
  );
  const [lists, setLists] = useState<BaseItem[][]>([]);
  useEffect(() => { if (data) setLists(data.map((r) => r.Items)); }, [data]);

  const present = GROUPS.map((g, i) => ({ ...g, items: lists[i] ?? [] })).filter((g) => g.items.length > 0);
  const total = present.reduce((n, g) => n + g.items.length, 0);
  const only = loc.query.get('type') as GroupId | null;
  const shown = only && present.some((g) => g.id === only) ? present.filter((g) => g.id === only) : present;
  const replaceIn = (gi: number) => (next: BaseItem) =>
    setLists((all) => all.map((l, i) => (i === gi ? l.map((x) => (x.Id === next.Id ? next : x)) : l)));

  return (
    <div class="page">
      <Header active="favorites" />
      <main class="page-main">
        <PageHead title={t('Favorites')} count={data ? plural(total, 'favorite') : undefined} />
        {present.length > 1 && (
          <nav aria-label={t('Favorite types')} class="tab-row hscroll">
            <Link to="/favorites" replace class="tab" aria-current={!only ? 'page' : undefined}>{t('All')}</Link>
            {present.map((g) => (
              <Link key={g.id} to={`/favorites?type=${g.id}`} replace class="tab" aria-current={only === g.id ? 'page' : undefined}>
                {g.title}<span class="tab-count">{g.items.length}</span>
              </Link>
            ))}
          </nav>
        )}
        {loading && !data ? (
          <>
            <div class="browse-section"><span class="skel skel-line" style={{ width: '120px', height: '18px' }} /><GridSkeleton count={5} /></div>
            <div class="browse-section"><span class="skel skel-line" style={{ width: '120px', height: '18px' }} /><GridSkeleton count={5} /></div>
          </>
        ) : error && !data ? (
          <ErrorState error={error} retry={reload} />
        ) : !present.length ? (
          <EmptyState
            icon="heart"
            title={t('No favorites yet')}
            text={t('Tap the heart on any title, episode or person and it will be kept here.')}
            action={<Link to="/" class="btn btn-primary is-sm">{t('Browse home')}</Link>}
          />
        ) : (
          shown.map((g) => {
            const gi = GROUPS.findIndex((x) => x.id === g.id);
            return (
              <section key={g.id} class="browse-section fav-section" aria-label={g.title}>
                <div class="browse-section-head">
                  <div class="row-head-main">
                    <h2 class="row-title">{g.title}</h2>
                    <span class="row-sub">{g.id === 'people' ? plural(g.items.length, 'person', 'people') : plural(g.items.length, 'title')}</span>
                  </div>
                  {only && <button type="button" class="row-link link-btn" onClick={() => setQuery({ type: undefined })}>{t('Show all favorites')}</button>}
                </div>
                {g.id === 'people' ? (
                  <div class="fav-people">
                    {g.items.map((p, i) => <CastCard key={p.Id} person={{ Id: p.Id, Name: p.Name, PrimaryImageTag: p.ImageTags?.Primary }} index={i} />)}
                  </div>
                ) : g.id === 'episodes' ? (
                  <div class="lib-grid is-wide">
                    {g.items.map((ep) => (
                      <div key={ep.Id} class="grid-slot fav-episode">
                        <ItemCard item={ep} tag={episodeTag(ep)} meta={join(episodeTag(ep), ep.Name)} watched={!!ep.UserData?.Played} />
                        <button
                          type="button"
                          class="lib-tool fav-unheart"
                          aria-label={ep.UserData?.IsFavorite ? t('Remove {name} from favorites', { name: ep.Name }) : t('Add {name} to favorites', { name: ep.Name })}
                          aria-pressed={ep.UserData?.IsFavorite ? 'true' : 'false'}
                          onClick={() => toggleFavorite(ep, replaceIn(gi))}
                        >
                          <Icon name="heart" size={16} stroke={2.2} filled={!!ep.UserData?.IsFavorite} />
                        </button>
                      </div>
                    ))}
                  </div>
                ) : (
                  <CardGrid items={g.items} onReplace={replaceIn(gi)} />
                )}
              </section>
            );
          })
        )}
      </main>
    </div>
  );
}

