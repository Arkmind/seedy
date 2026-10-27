import type { JSX } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { fmtDate, dayOf } from '../lib/format';
import { formatNumber, t, tn } from '../lib/i18n';
import { Link } from '../lib/router';
import { useAsync, useStore } from '../lib/store';
import { audioStreamOf, getAudioItem, getLyrics, qualityLabel, type Lyrics, type Track } from './api';
import { Cover, useCoverTint } from './cover';
import {
  audioStore, clearQueue, jumpTo, moveInQueue, next, openPlayer, previous, removeFromQueue, seek, setVolume, timeStore, toggleMute,
  toggleShuffle, toggleTrackFavorite, type AudioState, type Entry
} from './engine';
import { AIcon } from './icons';
import { PlayButton, RepeatButton } from './MiniBar';
import { SeekBar, VolumeBar, artistLink, artistsOf, clock, remaining, volumeIcon } from './parts';
import { PlayerShell } from './Shell';

/* Now playing (MusicNowPlaying board): the song with its controls on the left, synced lyrics or About the song in
   the middle, Up next on the right with the songs the person added first. */

type Tab = 'lyrics' | 'about' | 'queue';

const FROM: Record<string, string> = {
  album: 'Playing from album', playlist: 'Playing from playlist', artist: 'Playing from artist', mix: 'Playing from mix',
  library: 'Playing from library', genre: 'Playing from genre', folder: 'Playing from folder', songs: 'Playing songs'
};

export function sourceLink(s: AudioState): string | undefined {
  const src = s.source;
  if (!src?.id) return s.current?.track.AlbumId ? `/details/${s.current.track.AlbumId}` : undefined;
  if (src.kind === 'library') return `/library/${src.id}`;
  if (src.kind === 'genre') return undefined;
  return `/details/${src.id}`;
}

export function NowPlaying() {
  const s = useStore(audioStore);
  const time = useStore(timeStore);
  const tk = s.current?.track;
  const tint = useCoverTint(tk);
  const [tab, setTab] = useState<Tab>('lyrics');
  if (!tk) return null;
  const fav = !!tk.UserData?.IsFavorite;
  const pos = time.position;
  const dur = time.duration || 0;
  const overline = s.source ? t(FROM[s.source.kind] ?? 'Now playing') : t('Now playing');
  const sourceName = s.source?.name ?? tk.Album ?? '';
  const quality = s.stream?.quality || qualityLabel(audioStreamOf(tk), ' ');
  const method = s.stream?.method;
  const badge = method && (
    <span class={'apn-badge' + (method === 'Transcode' ? ' is-convert' : '')}>
      <span class="apn-badge-dot" aria-hidden="true" />
      {[method === 'DirectPlay' ? t('Direct play') : method === 'DirectStream' ? t('Direct stream') : t('Converting'), quality].filter(Boolean).join(' · ')}
    </span>
  );
  const toArtist = artistLink(tk);
  return (
    <PlayerShell label={t('Now playing')} tint={tint} overline={overline} title={sourceName} titleTo={sourceLink(s)} extra={badge}>
      <div class={'apn-grid' + (tab === 'queue' ? ' is-queue' : '')}>
        <section class="apn-song" aria-label={t('{song} by {artist}', { song: tk.Name, artist: artistsOf(tk) })}>
          <Cover item={tk} width={720} class="apn-cover" />
          <div class="apn-title-row">
            <span class="apn-titles">
              <span class="apn-title">{tk.Name}</span>
              {toArtist ? <Link to={toArtist} class="apn-artist" onClick={() => openPlayer(false)}>{artistsOf(tk)}</Link> : <span class="apn-artist">{artistsOf(tk)}</span>}
            </span>
            <button type="button" class={'apn-fav' + (fav ? ' is-on' : '')} aria-pressed={fav ? 'true' : 'false'}
              aria-label={fav ? t('Remove {name} from favorites', { name: tk.Name }) : t('Add {name} to favorites', { name: tk.Name })}
              onClick={() => void toggleTrackFavorite(tk)}>
              <AIcon name="heart" size={20} filled={fav} />
            </button>
          </div>
          <div class="apn-progress">
            <SeekBar class="is-big" value={pos} max={dur} onSeek={seek} label={t('Song position')}
              valueText={t('{a} of {b}', { a: clock(pos), b: clock(dur) })} />
            <span class="apn-times"><span>{clock(pos)}</span><span>{remaining(dur - pos)}</span></span>
          </div>
          <div class="apn-controls">
            <button type="button" class={'apn-btn is-44 is-dim' + (s.shuffle ? ' is-on' : '')} aria-label={t('Shuffle')} aria-pressed={s.shuffle ? 'true' : 'false'} onClick={toggleShuffle}>
              <AIcon name="shuffle" size={20} />
            </button>
            <button type="button" class="apn-btn is-52" aria-label={t('Previous')} onClick={previous}><AIcon name="previous" size={24} /></button>
            <PlayButton playing={s.playing} loading={s.loading} size={72} icon={28} class="apn-play" />
            <button type="button" class="apn-btn is-52" aria-label={t('Next')} onClick={next}><AIcon name="next" size={24} /></button>
            <RepeatButton class="apn-btn is-44 is-dim" size={20} />
          </div>
          <div class="apn-volume">
            <button type="button" class="apn-mute" aria-label={s.muted ? t('Unmute') : t('Mute')} onClick={toggleMute}><AIcon name={volumeIcon(s.volume, s.muted)} size={18} /></button>
            <VolumeBar class="is-np" volume={s.volume} muted={s.muted} onChange={setVolume} />
          </div>
        </section>

        <section class="apn-panel" aria-label={tab === 'lyrics' ? t('Lyrics') : t('About the song')}>
          <TabsAndLyrics tab={tab} setTab={setTab} track={tk} position={pos} />
        </section>

        <UpNext s={s} onBack={tab === 'queue' ? () => setTab('lyrics') : undefined} />
      </div>
    </PlayerShell>
  );
}

/* ───────── Lyrics and About the song ───────── */

function TabsAndLyrics({ tab, setTab, track, position }: { tab: Tab; setTab: (t: Tab) => void; track: Track; position: number }) {
  const lyrics = useAsync<Lyrics | null>(
    () => (track.HasLyrics === false ? Promise.resolve(null) : getLyrics(track.Id).catch(() => null)),
    [track.Id]
  );
  const lines = lyrics.data?.Lyrics?.filter((l) => l.Text !== undefined) ?? [];
  const synced = lines.length > 0 && lines.every((l) => typeof l.Start === 'number');
  return (
    <>
      <div role="tablist" aria-label={t('Player panels')} class="apn-tabs">
        <button type="button" role="tab" aria-selected={tab === 'lyrics' ? 'true' : 'false'} class="apn-tab" onClick={() => setTab('lyrics')}>{t('Lyrics')}</button>
        <button type="button" role="tab" aria-selected={tab === 'about' ? 'true' : 'false'} class="apn-tab" onClick={() => setTab('about')}>{t('About the song')}</button>
        <button type="button" role="tab" aria-selected="false" class="apn-tab is-narrow" onClick={() => setTab('queue')}>{t('Up next')}</button>
        <span class="apn-tabs-gap" />
        {tab !== 'about' && lines.length > 0 && <span class="apn-tabs-note">{synced ? t('Synced · click a line to jump') : t('Not synced')}</span>}
      </div>
      {tab !== 'about'
        ? (lyrics.loading && !lyrics.data ? <div class="apn-lyrics-wait"><span class="spinner" aria-label={t('Loading')} /></div>
          : lines.length ? <LyricLines lines={lines} synced={synced} position={position} />
          : <div class="apn-empty"><span class="apn-empty-title">{t('No lyrics for this song')}</span><span class="apn-empty-text">{t('Lyrics show here when the server has them, from a .lrc file next to the song or its tags.')}</span></div>)
        : <AboutSong track={track} />}
      <div aria-hidden="true" class="apn-fade" />
    </>
  );
}

function LyricLines({ lines, synced, position }: { lines: { Text: string; Start?: number }[]; synced: boolean; position: number }) {
  const box = useRef<HTMLDivElement>(null);
  const hold = useRef(0);
  const at = position * 10_000_000;
  let current = -1;
  if (synced) for (let i = 0; i < lines.length; i++) if ((lines[i].Start ?? 0) <= at + 2_000_000) current = i;
  useEffect(() => {
    const el = box.current;
    if (!el || current < 0 || Date.now() < hold.current) return;
    const rows = el.querySelectorAll<HTMLElement>('.apn-line');
    const anchor = rows[Math.max(0, current - 2)];
    if (anchor) el.scrollTo({ top: anchor.offsetTop - (rows[0]?.offsetTop ?? 0), behavior: 'smooth' });
  }, [current]);
  return (
    <div ref={box} class={'apn-lyrics' + (synced ? ' is-synced' : '')} onWheel={() => { hold.current = Date.now() + 4000; }} onTouchMove={() => { hold.current = Date.now() + 4000; }}>
      {lines.map((l, i) => {
        const cls = 'apn-line' + (!synced ? '' : i === current ? ' is-current' : i < current ? ' is-past' : ' is-next');
        if (!synced || typeof l.Start !== 'number') return <p key={i} class={cls}>{l.Text || ' '}</p>;
        return (
          <p key={i} class={cls}>
            <button type="button" class="apn-line-btn" aria-current={i === current ? 'true' : undefined}
              onClick={() => { hold.current = 0; seek((l.Start ?? 0) / 10_000_000); }}>{l.Text || ' '}</button>
          </p>
        );
      })}
    </div>
  );
}

function AboutSong({ track }: { track: Track }) {
  const full = useAsync(() => getAudioItem(track.Id), [track.Id]);
  const album = useAsync(() => (track.AlbumId ? getAudioItem(track.AlbumId).catch(() => null) : Promise.resolve(null)), [track.AlbumId]);
  const it = full.data ?? track;
  const al = album.data;
  const people = (type: string) => (it.People ?? []).filter((p) => p.Type === type).map((p) => p.Name).join(', ');
  const released = al?.PremiereDate ? fmtDate(dayOf(al.PremiereDate)!) : it.ProductionYear ? String(it.ProductionYear) : '';
  const size = it.MediaSources?.[0]?.Size;
  const facts: [string, JSX.Element | string | undefined][] = [
    [t('Album'), it.AlbumId && it.Album ? <Link to={`/details/${it.AlbumId}`} onClick={() => openPlayer(false)}>{it.Album}</Link> : it.Album],
    [t('Track'), it.IndexNumber ? (it.ParentIndexNumber && it.ParentIndexNumber > 1 ? t('Disc {d}, track {n}', { d: it.ParentIndexNumber, n: it.IndexNumber }) : t('Track {n}', { n: it.IndexNumber })) : undefined],
    [t('Released'), released],
    [t('Genres'), (it.Genres ?? al?.Genres ?? []).join(', ') || undefined],
    [t('Written by'), people('Composer') || people('Lyricist') || undefined],
    [t('Plays'), it.UserData?.PlayCount ? tn(it.UserData.PlayCount, '{n} play', '{n} plays') : t('Not played yet')],
    [t('Length'), it.RunTimeTicks ? clock(it.RunTimeTicks / 10_000_000) : undefined],
    [t('Format'), qualityLabel(audioStreamOf(it)) || undefined],
    [t('File size'), size ? sizeLabel(size) : undefined]
  ];
  const overview = it.Overview || al?.Overview;
  return (
    <div class="apn-about">
      <h2 class="apn-about-title">{it.Name}</h2>
      <p class="apn-about-by">{artistsOf(it)}{al?.Name ? ` · ${al.Name}` : ''}</p>
      {overview && <p class="apn-about-text">{overview}</p>}
      <dl class="apn-facts">
        {facts.filter(([, v]) => v).map(([k, v]) => (
          <div key={k} class="apn-fact"><dt>{k}</dt><dd>{v}</dd></div>
        ))}
      </dl>
    </div>
  );
}

export function sizeLabel(bytes: number): string {
  const gb = bytes / 1024 ** 3;
  if (gb >= 1) return `${formatNumber(Math.round(gb * 10) / 10)} GB`;
  return `${formatNumber(Math.max(1, Math.round(bytes / 1024 ** 2)))} MB`;
}

/* ───────── Up next ───────── */

function UpNext({ s, onBack }: { s: AudioState; onBack?: () => void }) {
  const added = s.added;
  const rest = s.context.slice(s.pos + 1);
  const empty = !added.length && !rest.length;
  const fromName = s.source?.name ?? s.current?.track.Album ?? '';
  return (
    <section class="apn-queue" aria-labelledby="apn-q-h">
      {onBack && (
        <div role="tablist" aria-label={t('Player panels')} class="apn-tabs apn-q-tabs">
          <button type="button" role="tab" aria-selected="false" class="apn-tab" onClick={onBack}>{t('Lyrics')}</button>
          <button type="button" role="tab" aria-selected="true" class="apn-tab">{t('Up next')}</button>
        </div>
      )}
      <div class="apn-q-head">
        <h2 id="apn-q-h" class="apn-q-title">{t('Up next')}</h2>
        {!empty && <button type="button" class="apn-q-clear" onClick={clearQueue}>{t('Clear queue')}</button>}
      </div>
      <div class="apn-q-scroll">
        {empty && <p class="apn-q-empty">{t('Nothing else is queued. Pick Play next or Add to queue on any song.')}</p>}
        {added.length > 0 && (
          <>
            <span class="apn-q-label">{t('Added by you')}</span>
            <QueueList part="added" entries={added} />
          </>
        )}
        {rest.length > 0 && (
          <>
            <span class={'apn-q-label' + (added.length ? ' is-second' : '')}>{fromName ? t('Next from {name}', { name: fromName }) : t('Next up')}</span>
            <QueueList part="context" entries={rest.slice(0, 200)} />
          </>
        )}
      </div>
    </section>
  );
}

const ROW_STEP = 56;

/* Rows move with the pointer (or the arrow keys on the handle) and drop where they're let go. */
function QueueList({ part, entries }: { part: 'added' | 'context'; entries: Entry[] }) {
  const [drag, setDrag] = useState<{ from: number; dy: number } | null>(null);
  const to = drag ? Math.max(0, Math.min(entries.length - 1, drag.from + Math.round(drag.dy / ROW_STEP))) : -1;
  const offset = (i: number) => {
    if (!drag) return 0;
    if (i === drag.from) return drag.dy;
    if (drag.from < to && i > drag.from && i <= to) return -ROW_STEP;
    if (drag.from > to && i < drag.from && i >= to) return ROW_STEP;
    return 0;
  };
  const start = (i: number, e: PointerEvent) => {
    if (e.button !== 0) return;
    e.preventDefault();
    const handle = e.currentTarget as HTMLElement;
    const y0 = e.clientY;
    let moved = false;
    handle.setPointerCapture?.(e.pointerId);
    const move = (ev: PointerEvent) => { moved = moved || Math.abs(ev.clientY - y0) > 3; setDrag({ from: i, dy: ev.clientY - y0 }); };
    const up = (ev: PointerEvent) => {
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', up);
      handle.removeEventListener('pointercancel', up);
      setDrag(null);
      const target = Math.max(0, Math.min(entries.length - 1, i + Math.round((ev.clientY - y0) / ROW_STEP)));
      if (moved && target !== i) moveInQueue(part, i, target);
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', up);
    handle.addEventListener('pointercancel', up);
  };
  return (
    <div class={'apn-q-list' + (drag ? ' is-dragging' : '')}>
      {entries.map((e, i) => {
        const tk = e.track;
        return (
          <div key={e.key} class={'apn-q-row' + (drag?.from === i ? ' is-lifted' : '')} style={{ transform: offset(i) ? `translateY(${offset(i)}px)` : undefined }}>
            <button type="button" class="apn-q-main" aria-label={t('Play {name}', { name: tk.Name })} onClick={() => jumpTo(e.key)}>
              <Cover item={tk} width={88} class="apn-q-cover" />
              <span class="apn-q-text">
                <span class="apn-q-name">{tk.Name}</span>
                <span class="apn-q-sub">{artistsOf(tk)}</span>
              </span>
            </button>
            {part === 'context' && <span class="apn-q-time">{tk.RunTimeTicks ? clock(tk.RunTimeTicks / 10_000_000) : ''}</span>}
            <button type="button" class="apn-q-remove" aria-label={t('Remove {name} from the queue', { name: tk.Name })} onClick={() => removeFromQueue(e.key)}>
              <AIcon name="close" size={14} stroke={2.4} />
            </button>
            <button type="button" class={'apn-q-handle' + (part === 'context' ? ' is-hover' : '')} aria-label={t('Move {name} in the queue', { name: tk.Name })}
              onPointerDown={(ev) => start(i, ev as unknown as PointerEvent)}
              onKeyDown={(ev) => {
                if (ev.key === 'ArrowUp' && i > 0) { ev.preventDefault(); moveInQueue(part, i, i - 1); }
                if (ev.key === 'ArrowDown' && i < entries.length - 1) { ev.preventDefault(); moveInQueue(part, i, i + 1); }
              }}>
              <AIcon name="handle" size={18} />
            </button>
          </div>
        );
      })}
    </div>
  );
}

