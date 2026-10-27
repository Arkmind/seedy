import { Link } from '../lib/router';
import { t } from '../lib/i18n';
import { useStore } from '../lib/store';
import { Cover } from './cover';
import {
  audioStore, chapterAt, cycleRepeat, next, openPlayer, previous, seek, setVolume, skip, timeStore, toggleMute, togglePlay, toggleShuffle,
  toggleTrackFavorite
} from './engine';
import { AIcon, Skip30 } from './icons';
import { CastMenu, SeekBar, VolumeBar, artistLink, artistsOf, clock, remaining, volumeIcon } from './parts';
import { SleepButton, SpeedButton } from './book-controls';

/* The player bar at the bottom of every page while something plays (Music boards: song, transport and progress,
   lyrics, queue, cast, volume, open; Books board: the book and its chapter, ±30 s, speed, sleep timer, chapters). */
export function MiniBar() {
  const s = useStore(audioStore);
  const time = useStore(timeStore);
  const cur = s.current;
  if (!cur) return null;
  const tk = cur.track;
  const book = s.mode === 'audiobook' ? s.book : null;
  const pos = time.position;
  const dur = time.duration || 0;

  if (book) {
    const ch = chapterAt(book, pos);
    const inChapter = ch ? pos - ch.start : pos;
    const chLen = ch ? ch.end - ch.start : dur;
    return (
      <div role="region" aria-label={t('Now listening')} class="ap-bar is-book">
        <div class="ap-bar-left">
          <button type="button" class="ap-bar-cover-btn" aria-label={t('Open the player')} onClick={() => openPlayer(true)}>
            <Cover item={tk} width={112} class="ap-bar-cover" />
          </button>
          <span class="ap-bar-text">
            <Link to={`/details/${book.id}`} class="ap-bar-title">{book.name}</Link>
            {ch && book.chapters.length > 1 && <span class="ap-bar-sub">{t('Chapter {n} · {name}', { n: ch.index + 1, name: ch.name })}</span>}
            {(!ch || book.chapters.length <= 1) && book.author && <span class="ap-bar-sub">{book.author}</span>}
          </span>
        </div>
        <div class="ap-bar-mid">
          <div class="ap-bar-controls">
            <button type="button" class="ap-bar-btn is-hide-phone" aria-label={t('Back 30 seconds')} onClick={() => skip(-30)}><Skip30 dir="back" size={28} /></button>
            <PlayButton playing={s.playing} loading={s.loading} />
            <button type="button" class="ap-bar-btn" aria-label={t('Forward 30 seconds')} onClick={() => skip(30)}><Skip30 dir="forward" size={28} /></button>
          </div>
          <div class="ap-bar-progress">
            <span>{clock(inChapter)}</span>
            <SeekBar value={inChapter} max={chLen} label={t('Position in chapter')} valueText={`${clock(inChapter)} / ${clock(chLen)}`}
              onSeek={(v) => seek((ch?.start ?? 0) + v)} />
            <span>{remaining(chLen - inChapter)}</span>
          </div>
        </div>
        <div class="ap-bar-right is-book">
          <SpeedButton variant="bar" />
          <SleepButton variant="bar" />
          <button type="button" class="ap-bar-btn is-soft" aria-label={t('Chapters')} onClick={() => openPlayer(true)}><AIcon name="chapters" size={18} /></button>
          <button type="button" class="ap-bar-btn is-soft" aria-label={t('Open the player')} onClick={() => openPlayer(true)}><AIcon name="expand" size={16} /></button>
        </div>
      </div>
    );
  }

  const fav = !!tk.UserData?.IsFavorite;
  const artist = artistsOf(tk);
  const toArtist = artistLink(tk);
  return (
    <div role="region" aria-label={t('Now playing')} class="ap-bar">
      <div class="ap-bar-left">
        <button type="button" class="ap-bar-cover-btn" aria-label={t('Open the player')} onClick={() => openPlayer(true)}>
          <Cover item={tk} width={112} class="ap-bar-cover" />
        </button>
        <span class="ap-bar-text">
          {tk.AlbumId ? <Link to={`/details/${tk.AlbumId}`} class="ap-bar-title">{tk.Name}</Link> : <span class="ap-bar-title">{tk.Name}</span>}
          {toArtist ? <Link to={toArtist} class="ap-bar-sub">{artist}</Link> : <span class="ap-bar-sub">{artist}</span>}
        </span>
        <button type="button" class={'ap-bar-fav' + (fav ? ' is-on' : '')} aria-pressed={fav ? 'true' : 'false'}
          aria-label={fav ? t('Remove {name} from favorites', { name: tk.Name }) : t('Add {name} to favorites', { name: tk.Name })}
          onClick={() => void toggleTrackFavorite(tk)}>
          <AIcon name="heart" size={18} filled={fav} />
        </button>
      </div>
      <div class="ap-bar-mid">
        <div class="ap-bar-controls">
          <button type="button" class={'ap-bar-btn is-dim is-hide-phone' + (s.shuffle ? ' is-on' : '')} aria-label={t('Shuffle')} aria-pressed={s.shuffle ? 'true' : 'false'} onClick={toggleShuffle}>
            <AIcon name="shuffle" size={18} />
          </button>
          <button type="button" class="ap-bar-btn is-hide-phone" aria-label={t('Previous')} onClick={previous}><AIcon name="previous" size={20} /></button>
          <PlayButton playing={s.playing} loading={s.loading} />
          <button type="button" class="ap-bar-btn" aria-label={t('Next')} onClick={next}><AIcon name="next" size={20} /></button>
          <RepeatButton class="ap-bar-btn is-dim is-hide-phone" size={18} />
        </div>
        <div class="ap-bar-progress">
          <span>{clock(pos)}</span>
          <SeekBar value={pos} max={dur} label={t('Song position')} valueText={`${clock(pos)} / ${clock(dur)}`} onSeek={seek} />
          <span>{clock(dur)}</span>
        </div>
      </div>
      <div class="ap-bar-right">
        <button type="button" class="ap-bar-btn is-soft" aria-label={t('Lyrics')} onClick={() => openPlayer(true)}><AIcon name="lyrics" size={18} /></button>
        <button type="button" class="ap-bar-btn is-soft" aria-label={t('Queue')} onClick={() => openPlayer(true)}><AIcon name="queue" size={18} /></button>
        <CastMenu class="ap-bar-btn is-soft" />
        <span class="ap-bar-volume">
          <button type="button" class="ap-bar-mute" aria-label={s.muted ? t('Unmute') : t('Mute')} onClick={toggleMute}>
            <AIcon name={volumeIcon(s.volume, s.muted)} size={18} />
          </button>
          <VolumeBar volume={s.volume} muted={s.muted} onChange={setVolume} />
        </span>
        <button type="button" class="ap-bar-btn is-soft" aria-label={t('Open the player')} onClick={() => openPlayer(true)}><AIcon name="expand" size={16} /></button>
      </div>
    </div>
  );
}

function PlayButton({ playing, loading, size = 44, icon = 20, class: cls = 'ap-bar-play' }: { playing: boolean; loading: boolean; size?: number; icon?: number; class?: string }) {
  return (
    <button type="button" class={cls + (loading ? ' is-loading' : '')} style={size !== 44 ? { width: size + 'px', height: size + 'px' } : undefined}
      aria-label={playing ? t('Pause') : t('Play')} onClick={togglePlay}>
      <AIcon name={playing ? 'pause' : 'play'} size={icon} />
    </button>
  );
}

export { PlayButton };

export function RepeatButton({ class: cls, size }: { class: string; size: number }) {
  const s = useStore(audioStore);
  const label = s.repeat === 'one' ? t('Repeat this song') : s.repeat === 'all'
    ? (s.source?.kind === 'album' ? t('Repeat album') : s.source?.kind === 'playlist' ? t('Repeat playlist') : t('Repeat queue'))
    : t('Repeat');
  return (
    <button type="button" class={cls + (s.repeat !== 'off' ? ' is-on' : '')} aria-label={label} aria-pressed={s.repeat !== 'off' ? 'true' : 'false'} onClick={cycleRepeat}>
      <AIcon name="repeat" size={size} />
      {s.repeat === 'one' && <span class="ap-one" aria-hidden="true">1</span>}
    </button>
  );
}
