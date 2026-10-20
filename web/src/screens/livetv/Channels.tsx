import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { getPrograms, type Program } from '../../api/livetv';
import { meStore } from '../../lib/app-state';
import { t } from '../../lib/i18n';
import { useAsync, useStore } from '../../lib/store';
import { Icon } from '../../ui/icons';
import {
  CATEGORIES, categoryOf, fraction, isAiring, isFavorite, leftLabel, programTitle, recState, startMs, timeRange, toggleFavorite,
  useChannels, useNow, useTimers, type Category, type LiveChannel
} from './data';
import { LiveLayout, NoChannels } from './LiveLayout';
import { ChannelLogo, Chip, HeartIcon, LtIcon, watch } from './parts';

/* Live TV · Channels (no board of its own; built from the Guide's pieces): every channel as a tile with its number,
   logo and name, what's on now with its progress and what's next, and the favourite heart. Filter chips as on the
   Guide, and a field to find a channel by name or number. */

type Filter = 'all' | 'fav' | Category;
const PAGE = 60;

export function LiveChannels() {
  const me = useStore(meStore);
  const { list } = useChannels();
  const { timers } = useTimers();
  const now = useNow(30_000);
  const [filter, setFilter] = useState<Filter>('all');
  const [query, setQuery] = useState('');
  const [limit, setLimit] = useState(PAGE);
  const slot = Math.floor(now / 300_000);
  const progs = useAsync(
    (signal) => getPrograms({ MinEndDate: new Date().toISOString(), MaxStartDate: new Date(Date.now() + 4 * 3_600_000).toISOString() }, signal).then((r) => r.Items ?? []),
    [slot]
  );
  const byChannel = useMemo(() => {
    const m = new Map<string, Program[]>();
    for (const p of progs.data ?? []) {
      if (!p.ChannelId) continue;
      const arr = m.get(p.ChannelId) ?? [];
      arr.push(p);
      m.set(p.ChannelId, arr);
    }
    for (const arr of m.values()) arr.sort((a, b) => startMs(a) - startMs(b));
    return m;
  }, [progs.data]);

  const all = list ?? [];
  const onNow = (c: LiveChannel) => (byChannel.get(c.Id) ?? []).find((p) => isAiring(p, now)) ?? (isAiring(c.CurrentProgram, now) ? c.CurrentProgram : undefined);
  const nextOf = (c: LiveChannel) => (byChannel.get(c.Id) ?? []).find((p) => startMs(p) > now);
  const q = query.trim().toLowerCase();
  const shown = all
    .filter((c) => {
      if (q && !c.label.toLowerCase().includes(q) && c.num !== q && !c.num.startsWith(q)) return false;
      if (filter === 'all') return true;
      if (filter === 'fav') return isFavorite(c);
      const cur = onNow(c);
      return !!cur && CATEGORIES.find((x) => x.id === filter)!.test(cur);
    })
    .sort((a, b) => Number(isFavorite(b)) - Number(isFavorite(a)));
  const page = shown.slice(0, limit);

  const more = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = more.current;
    if (!el || typeof IntersectionObserver === 'undefined') return;
    const io = new IntersectionObserver((es) => { if (es.some((x) => x.isIntersecting)) setLimit((l) => l + PAGE); }, { rootMargin: '600px 0px' });
    io.observe(el);
    return () => io.disconnect();
  }, [shown.length > limit, limit]);
  useEffect(() => { setLimit(PAGE); }, [filter, q]);

  if (list && !list.length) return <LiveLayout tab="channels"><NoChannels admin={me.isAdmin} /></LiveLayout>;
  const favCount = all.filter(isFavorite).length;

  return (
    <LiveLayout tab="channels">
      <div class="lt-guide-bar">
        <div role="group" aria-label={t('Show channels')} class="lt-chips">
          <Chip label={t('All')} count={all.length} pressed={filter === 'all'} onClick={() => setFilter('all')} />
          <Chip label={t('Favorites')} count={favCount} pressed={filter === 'fav'} onClick={() => setFilter('fav')} />
          <span aria-hidden="true" class="lt-chip-sep" />
          {CATEGORIES.map((x) => <Chip key={x.id} label={t(x.label)} pressed={filter === x.id} onClick={() => setFilter(filter === x.id ? 'all' : x.id)} />)}
        </div>
        <label class="lt-find">
          <LtIcon name="search" size={18} />
          <input type="search" value={query} placeholder={t('Find a channel')} aria-label={t('Find a channel by name or number')}
            onInput={(e) => setQuery((e.currentTarget as HTMLInputElement).value)} />
          {query && <button type="button" class="lt-find-clear" aria-label={t('Clear')} onClick={() => setQuery('')}><Icon name="close" size={14} /></button>}
        </label>
      </div>

      {!list ? (
        <div class="lt-chgrid lt-pad" aria-hidden="true">
          {Array.from({ length: 12 }, (_, i) => <div key={i} class="lt-ch is-skel"><span class="skel skel-line" style={{ width: '60%' }} /><span class="skel skel-line" style={{ width: '85%' }} /><span class="skel skel-line" style={{ width: '40%' }} /></div>)}
        </div>
      ) : shown.length ? (
        <ul class="lt-chgrid lt-pad" aria-label={t('Channels')}>
          {page.map((c) => <ChannelTile key={c.Id} channel={c} program={onNow(c)} next={nextOf(c)} now={now} rec={recState(onNow(c), timers)} />)}
        </ul>
      ) : (
        <div class="lt-pad">
          <div class="empty">
            <span class="empty-icon"><LtIcon name="channels" size={26} /></span>
            <div class="empty-body">
              <span class="empty-title">{filter === 'fav' && !q ? t('No favorite channels yet') : t('No channels found')}</span>
              <span class="empty-text">{filter === 'fav' && !q ? t('Use the heart on a channel to keep it at the top of the guide and the player’s channel list.') : t('Try another name or number, or another filter.')}</span>
            </div>
          </div>
        </div>
      )}
      {shown.length > page.length && (
        <div ref={more} class="lt-pad lt-more">
          <button type="button" class="link-btn" onClick={() => setLimit((l) => l + PAGE)}>{t('Show more channels')}</button>
        </div>
      )}
      {list && shown.length > 0 && (
        <div class="lt-guide-foot">
          <span>{t('Showing {shown} of {total} channels', { shown: page.length, total: all.length })}{favCount > 0 ? ' · ' + t('favorites first') : ''}</span>
          <span class="lt-keys">{t('Type a channel number anywhere to jump to it')}</span>
        </div>
      )}
    </LiveLayout>
  );
}

function ChannelTile({ channel: c, program: p, next, now, rec }: { channel: LiveChannel; program?: Program; next?: Program; now: number; rec: 'now' | 'set' | null }) {
  const fav = isFavorite(c);
  const cat = p ? categoryOf(p) : '';
  return (
    <li class="lt-ch">
      <a
        href="#"
        class="lt-ch-link"
        aria-label={p ? t('Watch {channel}, now showing {title}', { channel: c.label, title: programTitle(p) }) : t('Watch {channel}', { channel: c.label })}
        onClick={(e) => { e.preventDefault(); watch(c.Id); }}
      >
        <span class="lt-ch-head">
          <span class="lt-rownum">{c.num}</span>
          <ChannelLogo channel={c} size={44} radius={12} />
          <span class="lt-two">
            <span class="lt-ch-name">{c.label}</span>
            {(cat || c.hd) && <span class="lt-ch-sub">{[cat, c.hd].filter(Boolean).join(' · ')}</span>}
          </span>
        </span>
        {p ? (
          <span class="lt-ch-now">
            <span class="lt-ch-title">
              {rec === 'now' && <span class="lt-tile-rec" title={t('Recording')} />}
              <span class="lt-ch-titletext">{programTitle(p)}</span>
            </span>
            <span class="lt-ch-meta"><span>{timeRange(p)}</span><b>{leftLabel(p, now)}</b></span>
            <span class="lt-bar is-5"><i style={{ width: (fraction(p, now) * 100).toFixed(2) + '%' }} /></span>
            {next && <span class="lt-ch-next">{t('Next')} · <b>{timeRange(next).split(' – ')[0]} {next.Name}</b></span>}
          </span>
        ) : (
          <span class="lt-ch-now is-none">
            <span class="lt-ch-title"><span class="lt-ch-titletext">{t('No guide information')}</span></span>
            <span class="lt-ch-meta"><span>{t('This playlist channel has no guide data')}</span></span>
          </span>
        )}
      </a>
      <button
        type="button"
        class={'lt-ch-fav' + (fav ? ' is-on' : '')}
        aria-pressed={fav ? 'true' : 'false'}
        aria-label={fav ? t('Remove {name} from favorite channels', { name: c.label }) : t('Add {name} to favorite channels', { name: c.label })}
        onClick={() => void toggleFavorite(c)}
      >
        <HeartIcon size={18} filled={fav} />
      </button>
    </li>
  );
}
