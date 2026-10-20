import { useMemo } from 'preact/hooks';
import { getPrograms, type Program } from '../../api/livetv';
import { meStore } from '../../lib/app-state';
import { t } from '../../lib/i18n';
import { Link, navigate } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { Icon } from '../../ui/icons';
import {
  categoryOf, fraction, isAiring, previewAllowed, isFavorite, lastChannelId, leftLabel, newTag, programTitle, programsChanged, recState, soonLabel,
  startMs, timeRange, toggleFavorite, toggleRecord, tonightEnd, useChannels, useNow, useTimers, whenLine, type LiveChannel
} from './data';
import { LiveLayout, NoChannels } from './LiveLayout';
import { ChannelLogo, HeartIcon, LivePill, ProgramArt, RecDot, SoundButton, TagPill, hasProgramArt, watch } from './parts';
import { useLivePreview } from './preview';

/* Live TV · On now (board "Live TV · On now"): the last watched channel with a muted live preview, then rows —
   on now on favourite channels, starting soon, and the guide's categories for tonight. */

export function LiveOnNow() {
  const { list } = useChannels();
  const { timers } = useTimers();
  const me = useStore(meStore);
  const now = useNow();
  const bump = useStore(programsChanged);
  const minute = Math.floor(now / 120_000);
  const progs = useAsync(
    (signal) => getPrograms({ MinEndDate: new Date().toISOString(), MaxStartDate: new Date(tonightEnd(Date.now()) + 6 * 3_600_000).toISOString() }, signal)
      .then((r) => r.Items ?? []),
    [minute, bump]
  );
  const byId = useMemo(() => new Map((list ?? []).map((c) => [c.Id, c])), [list]);

  if (list && !list.length) return <LiveLayout tab="now"><NoChannels admin={me.isAdmin} /></LiveLayout>;

  const programs = progs.data ?? [];
  const chanOf = (p: Program) => (p.ChannelId ? byId.get(p.ChannelId) : undefined);
  const order = (p: Program) => {
    const c = chanOf(p);
    return c ? list!.indexOf(c) : 9999;
  };

  // Hero: the channel last watched here; else a favourite that's on; else the first channel with a guide.
  const lastId = lastChannelId();
  const last = lastId ? byId.get(lastId) : undefined;
  const favs = (list ?? []).filter(isFavorite);
  const hero = last
    ?? favs.find((c) => c.CurrentProgram)
    ?? (list ?? []).find((c) => c.CurrentProgram)
    ?? list?.[0];

  const airing = programs.filter((p) => isAiring(p, now));
  const airingOn = (c: LiveChannel) => airing.find((p) => p.ChannelId === c.Id) ?? (isAiring(c.CurrentProgram, now) ? c.CurrentProgram : undefined);
  const tonight = tonightEnd(now);
  const upTo = (p: Program, until: number) => isAiring(p, now) || (startMs(p) > now && startMs(p) < until);
  const sortTime = (a: Program, b: Program) => startMs(a) - startMs(b) || order(a) - order(b);

  type Item = { program?: Program; channel?: LiveChannel; key: string };
  const onNowRow: Item[] = favs.length
    ? favs.map((c) => ({ channel: c, program: airingOn(c), key: c.Id }))
    : airing.sort((a, b) => order(a) - order(b)).map((p) => ({ program: p, channel: chanOf(p), key: p.Id }));
  const soon = programs.filter((p) => startMs(p) > now && startMs(p) <= now + 30 * 60_000).sort(sortTime)
    .map((p) => ({ program: p, channel: chanOf(p), key: p.Id }));
  const cat = (test: (p: Program) => boolean, until: number) => programs.filter((p) => test(p) && upTo(p, until)).sort(sortTime).slice(0, 18)
    .map((p) => ({ program: p, channel: chanOf(p), key: p.Id }));
  const rows: { id: string; title: string; note: string; link: string; to: string; items: Item[] }[] = [
    { id: 'fav', title: t('On now'), note: favs.length ? t('on your favorite channels') : t('across your channels'), link: t('Open guide'), to: '/livetv/guide', items: onNowRow },
    { id: 'soon', title: t('Starting soon'), note: t('next 30 minutes'), link: t('See all'), to: '/livetv/guide', items: soon },
    { id: 'sports', title: t('Sports'), note: t('on now and tonight'), link: t('See all'), to: '/livetv/guide?cat=sports', items: cat((p) => !!p.IsSports, tonight) },
    { id: 'movies', title: t('Movies tonight'), note: '', link: t('See all'), to: '/livetv/guide?cat=movies', items: cat((p) => !!p.IsMovie, tonight) },
    { id: 'news', title: t('News'), note: t('on now and tonight'), link: t('See all'), to: '/livetv/guide?cat=news', items: cat((p) => !!p.IsNews && !p.IsSports, tonight) },
    { id: 'kids', title: t('Kids'), note: t('on now and tonight'), link: t('See all'), to: '/livetv/guide?cat=kids', items: cat((p) => !!p.IsKids, tonight) }
  ];
  const loading = !list || (progs.loading && !progs.data);

  return (
    <LiveLayout tab="now">
      {hero ? <Hero channel={hero} last={!!last} program={airingOn(hero)} next={programs.filter((p) => p.ChannelId === hero.Id && startMs(p) > now).sort(sortTime)[0]} now={now} timers={timers} /> : <HeroSkeleton />}
      {loading
        ? [0, 1].map((i) => <RowSkeleton key={i} />)
        : rows.filter((r) => r.items.length).map((r, i) => (
          <section key={r.id} class="lt-row" aria-labelledby={'lt-row-' + r.id}>
            <div class="lt-row-head">
              <h2 id={'lt-row-' + r.id} class="lt-row-title">{r.title} {r.note && <span class="lt-row-note">{r.note}</span>}</h2>
              <Link to={r.to} class="lt-row-link">{r.link}</Link>
            </div>
            <div class="lt-row-scroll hscroll">
              {r.items.map((it, j) => <ProgramCard key={it.key} program={it.program} channel={it.channel} now={now} timers={timers} first={i === 0 && j === 0} />)}
            </div>
          </section>
        ))}
      {!loading && progs.error && <p class="lt-pad lt-muted">{t("Couldn't load the guide. What's on now still shows on each channel.")}</p>}
    </LiveLayout>
  );
}

/* ── The hero ── */

function Hero({ channel, program, next, now, last, timers }: {
  channel: LiveChannel; program?: Program; next?: Program; now: number; last: boolean; timers: ReturnType<typeof useTimers>['timers'];
}) {
  const preview = useLivePreview(channel.Id, previewAllowed(timers, now));
  const rec = recState(program, timers);
  const fav = isFavorite(channel);
  const cat = program ? categoryOf(program) : '';
  return (
    <section class="lt-hero" aria-labelledby="lt-hero-h">
      <div class={'lt-hero-video' + (preview.state === 'playing' ? ' is-playing' : '')}>
        <div class="lt-hero-art"><ProgramArt program={program} channel={channel} width={720} /></div>
        <video ref={preview.ref} class="lt-hero-preview" muted playsInline aria-hidden="true" />
        <span class="lt-hero-live"><LivePill /></span>
        <SoundButton video={preview.ref} state={preview.state} />
      </div>
      <div class="lt-hero-info">
        <span class="lt-overline">{last ? t('Last watched') : t('On now')}</span>
        <div class="lt-hero-channel">
          <ChannelLogo channel={channel} size={44} radius={12} />
          <span class="lt-two">
            <span class="lt-hero-chname">{channel.label}</span>
            <span class="lt-hero-chsub">{[t('Channel {n}', { n: channel.num }), cat].filter(Boolean).join(' · ')}</span>
          </span>
        </div>
        <h2 id="lt-hero-h" class="lt-hero-title">{program ? programTitle(program) : channel.label}</h2>
        {program ? (
          <div class="lt-hero-meta">
            <div class="lt-hero-line"><span>{[cat, timeRange(program)].filter(Boolean).join(' · ')}</span><b>{leftLabel(program, now)}</b></div>
            <div class="lt-bar is-6"><i style={{ width: (fraction(program, now) * 100).toFixed(2) + '%' }} /></div>
          </div>
        ) : (
          <div class="lt-hero-line"><span>{t('No guide information')}</span></div>
        )}
        {program?.Overview && <p class="lt-hero-text">{program.Overview}</p>}
        <div class="lt-hero-actions">
          <button type="button" class="lt-btn is-primary is-48" onClick={() => watch(channel.Id)}>
            <Icon name="playRound" size={20} />{t('Watch live')}
          </button>
          {program && (
            <button type="button" class="lt-btn is-48" onClick={() => void toggleRecord(program)} aria-pressed={rec ? 'true' : 'false'}>
              <RecDot />{rec === 'now' ? t('Recording') : rec === 'set' ? t('Recording set') : t('Record')}
            </button>
          )}
          <button
            type="button"
            class={'lt-btn is-icon is-48' + (fav ? ' is-fav' : '')}
            aria-pressed={fav ? 'true' : 'false'}
            aria-label={fav ? t('Remove {name} from favorite channels', { name: channel.label }) : t('Add {name} to favorite channels', { name: channel.label })}
            onClick={() => void toggleFavorite(channel)}
          >
            <HeartIcon size={20} filled={fav} />
          </button>
        </div>
        {next && <span class="lt-hero-next">{t('Next')} · <b>{clockOf(next)} {next.Name}</b></span>}
      </div>
    </section>
  );
}

const clockOf = (p: Program) => timeRange(p).split(' – ')[0];

function HeroSkeleton() {
  return (
    <section class="lt-hero" aria-hidden="true">
      <div class="lt-hero-video skel" />
      <div class="lt-hero-info">
        <span class="skel skel-line" style={{ width: '120px' }} />
        <span class="skel skel-line" style={{ width: '60%', height: '34px', borderRadius: '10px' }} />
        <span class="skel skel-line" style={{ width: '90%' }} />
        <span class="skel skel-line" style={{ width: '70%' }} />
      </div>
    </section>
  );
}

function RowSkeleton() {
  return (
    <section class="lt-row" aria-hidden="true">
      <div class="lt-row-head"><span class="skel skel-line" style={{ width: '220px', height: '20px' }} /></div>
      <div class="lt-row-scroll hscroll">
        {[0, 1, 2, 3, 4].map((i) => (
          <div key={i} class="lt-card"><div class="lt-card-media skel" /><div class="lt-card-text"><span class="skel skel-line" style={{ width: '70%' }} /><span class="skel skel-line" style={{ width: '45%' }} /></div></div>
        ))}
      </div>
    </section>
  );
}

/* ── A programme card (16:9, 320 px): LIVE or "In 10 min", New / Premiere, the channel's logo, recording state,
   progress while it airs, and a record button on what's still to come. ── */

export function ProgramCard({ program, channel, now, timers, first, grid }: {
  program?: Program; channel?: LiveChannel; now: number; timers: ReturnType<typeof useTimers>['timers']; first?: boolean; grid?: boolean;
}) {
  const live = program ? isAiring(program, now) : !!channel;
  const tag = program ? newTag(program) : null;
  const rec = recState(program, timers);
  const art = hasProgramArt(program);
  const name = channel?.label ?? (program?.ChannelName ?? '');
  const title = program ? programTitle(program) : name;
  const meta = !program
    ? t('No guide information')
    : live ? `${name} · ${leftLabel(program, now)}` : `${name} · ${whenLine(program, now)}`;
  const open = () => {
    if (live) watch(channel?.Id ?? program?.ChannelId);
    else if (program) navigate(`/livetv/guide?program=${program.Id}&at=${startMs(program)}`);
  };
  return (
    <div class={'lt-card' + (grid ? ' is-grid' : '')}>
      <a
        href="#"
        class="lt-card-link"
        aria-label={live ? t('Watch {title} on {channel}', { title, channel: name }) : t('{title} on {channel}, {when}', { title, channel: name, when: program ? whenLine(program, now) : '' })}
        data-first={first ? '' : undefined}
        onClick={(e) => { e.preventDefault(); open(); }}
      >
        <div class="lt-card-media">
          <ProgramArt program={program} channel={channel} width={640} />
          {live && <div class="card-hover"><span class="play-disc"><Icon name="play" size={24} /></span></div>}
          <div class="lt-card-pills">
            {live ? <LivePill small /> : program && <span class="lt-soon">{soonLabel(program, now)}</span>}
            {tag && <TagPill kind={tag} />}
          </div>
          {art && <ChannelLogo channel={channel} size={34} radius={9} class="lt-card-logo" />}
          {rec && <span class="lt-card-rec"><span class="lt-rec-dot" />{rec === 'now' ? t('Recording') : t('Recording set')}</span>}
          {live && program && <div class="lt-card-bar"><i style={{ width: (fraction(program, now) * 100).toFixed(2) + '%' }} /></div>}
        </div>
        <div class="lt-card-text">
          <span class="lt-card-title">{title}</span>
          <span class="lt-card-meta">{meta}</span>
        </div>
      </a>
      {program && !live && !rec && (
        <div class="lt-card-over">
          <button type="button" class="lt-card-record" aria-label={t('Record {title}', { title: program.Name ?? '' })} onClick={() => void toggleRecord(program)}>
            <RecDot size={16} />
          </button>
        </div>
      )}
    </div>
  );
}
