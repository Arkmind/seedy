import type { ComponentChildren } from 'preact';
import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { ApiError, get } from '../api/client';
import { PLAYER_FIELDS, getEpisodes, getItem, getMediaSegments, getSimilar, sendPlayCommand } from '../api/jellyfin';
import { castTarget } from '../lib/cast';
import { isAudioItem } from '../audio/api';
import { holdSession, playItem, playTracks, releaseSession } from '../audio/engine';
import { resolvePlayable } from '../screens/browse/parts';
import { chapterImageUrl, primaryUrl } from '../api/images';
import type { PlaybackPrefs, SubtitlePrefs } from '../api/seedy';
import { savePrefsPatch as queuePrefs } from '../screens/me/prefs';
import type { BaseItem, MediaSegment, MediaSource, MediaStream } from '../api/types';
import { DEFAULT_PLAYBACK, currentUserId, meStore, userStore } from '../lib/app-state';
import {
  endsAt, episodeTag, seasonEpisodeLong, secondsToTicks, ticksToSeconds, timeLeft, timecode
} from '../lib/format';
import { sendOrigin } from '../lib/origin';
import { on } from '../lib/remote';
import { back, navigate, useLocation } from '../lib/router';
import { safeLocal, saveLocal, useStore } from '../lib/store';
import { t as tr } from '../lib/i18n';
import { Icon, SeekIcon } from '../ui/icons';
import { toast } from '../ui/overlay';
import { parseAss } from './ass';
import { closestTrack, lastTracks, trackSig } from './carry';
import { ChaptersPanel } from './ChaptersPanel';
import { Compare } from './Compare';
import { TEXT_SUB_CODECS, type ProfileOptions } from './device-profile';
import { EndOfMovie, type TileRect } from './EndOfMovie';
import { EpisodesPanel } from './EpisodesPanel';
import { externalApps, openExternal } from './external';
import { keyAction } from './keys';
import { MediaBridge } from './media';
import { useNativeCues } from './native-cues';
import { PausedOverlay } from './PausedOverlay';
import {
  PlaybackError, PlaybackSession, audioStreams, errorCopy, nextAttempt, qualityBitrate, subtitleFormat, subtitleStreams, subtitleUrl,
  videoStream, type ActiveStream, type FailedPart, type QualityId, type StreamOptions
} from './playback';
import { loadPgs } from './pgs';
import { Scrubber, VolumeSlider } from './Scrubber';
import {
  applyCastQuality,
  AUTO_BITRATE, SIZE_TO_PREFS, autoBitrate, bitrateLabel, customPlayback, detectLocation, deviceStore, locationStore, maxVideoWidth,
  resolveStyle, seekSteps, segmentMode, setSubtitleStyle, type DeviceSettings, type NetworkLocation, type SubSize, type SubtitleStyle
} from './settings';
import { StillWatching } from './StillWatching';
import { SubtitleLayer, type LoadedSubtitles } from './SubtitleLayer';
import { loadCues, type Cue } from './subtitles';
import {
  bindSyncPlayer, inGroup, leaveGroup, reportBuffering, reportReady, requestPause, requestQueue, requestSeek, requestUnpause
} from './syncplay';
import { CastMenu, WatchTogether } from './TopMenus';
import { TracksPanel, channelsLabel, subtitleTitle, type TracksTab } from './TracksPanel';
import { EdgeProgress, UpNext } from './UpNext';
import { useWakeLock } from './wake-lock';
import { LivePlayer } from './LivePlayer';

/* ───────────────────────── Route entry ───────────────────────── */

interface Params {
  t: string | null;
  restart: boolean;
  from: string | null;
  queue: string[];
  /* Trailers before a movie (cinema mode): the movie they lead to, and the trailers still to come. */
  preroll: string | null;
  pre: string[];
  /* The movie after its trailers: don't look for trailers again. */
  noIntros: boolean;
}

const fromParam = (from: string | null) => (from ? '&from=' + encodeURIComponent(from) : '');
const prerollPath = (ids: string[], movieId: string, from: string | null) =>
  `/play/${ids[0]}?restart=1&preroll=${movieId}${ids.length > 1 ? '&pre=' + ids.slice(1).join(',') : ''}${fromParam(from)}`;
const afterPrerollPath = (movieId: string, from: string | null) => `/play/${movieId}?restart=1&intros=0${fromParam(from)}`;

export function Player({ id }: { id: string }) {
  const loc = useLocation();
  const params = useMemo<Params>(() => ({
    t: loc.query.get('t'),
    restart: loc.query.get('restart') === '1',
    from: loc.query.get('from'),
    queue: (loc.query.get('queue') ?? '').split(',').map((s) => s.trim()).filter(Boolean),
    preroll: loc.query.get('preroll'),
    pre: (loc.query.get('pre') ?? '').split(',').map((s) => s.trim()).filter(Boolean),
    noIntros: loc.query.get('intros') === '0'
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }), []);
  const [state, setState] = useState<{ item?: BaseItem; error?: Error }>({});
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let alive = true;
    setState({});
    getItem(id, PLAYER_FIELDS).then(
      async (item) => {
        if (!alive) return;
        // Songs, albums, artists, music playlists and audiobooks play in the audio player, over the page they came from.
        if (isAudioItem(item) && !castTarget.get()) {
          // Another app may hand over a whole queue (remote Play with several songs).
          if (item.Type === 'Audio' && params.queue.length) void playTracks([item.Id, ...params.queue], 0, { startTicks: params.t ? Number(params.t) || 0 : undefined });
          else void playItem(item, { startTicks: params.t ? Number(params.t) || 0 : undefined });
          back('/');
          return;
        }
        // Books open in the reader.
        if (item.Type === 'Book') {
          navigate(`/read/${item.Id}`, { replace: true, force: true });
          return;
        }
        // A show, season, collection, album or playlist: play what it leads to (resume point, next up, first track).
        if (!isPlayable(item)) {
          const target = await resolvePlayable(item).catch(() => undefined);
          if (!alive) return;
          if (target && target.Id !== item.Id) {
            const q = new URLSearchParams(window.location.search);
            navigate(`/play/${target.Id}${q.toString() ? '?' + q.toString() : ''}`, { replace: true, force: true });
            return;
          }
        }
        // "Play on" another device from the header: hand the title over instead of playing here.
        const target = castTarget.get();
        if (target && isPlayable(item)) {
          const ticks = params.t ? Number(params.t) || 0 : params.restart ? 0 : item.UserData?.PlaybackPositionTicks ?? 0;
          try {
            await sendPlayCommand(target.Id, [item.Id], ticks);
            applyCastQuality(target.Id);
            const name = target.DeviceName || target.Client;
            toast(name ? tr('Playing on {device}', { device: name }) : tr('Playing on the other device'));
          } catch {
            toast(target.DeviceName ? tr("Couldn't reach {device}", { device: target.DeviceName }) : tr("Couldn't reach that device"), 'error');
          }
          if (alive) back('/');
          return;
        }
        // "Trailers before a movie": play the intros the server has for it first, when starting from the top.
        const custom = customPlayback(meStore.get().prefs?.custom);
        const fromTop = params.t !== null && params.t !== '' ? Number(params.t) === 0 : params.restart || !(item.UserData?.PlaybackPositionTicks ?? 0);
        if (custom.cinemaMode && item.Type === 'Movie' && !params.preroll && !params.noIntros && fromTop) {
          const intros = await get<{ Items?: BaseItem[] }>(`/Items/${item.Id}/Intros`, { userId: currentUserId() })
            .then((r) => (r?.Items ?? []).filter((x) => x.Id !== item.Id), () => [] as BaseItem[]);
          if (!alive) return;
          if (intros.length) {
            navigate(prerollPath(intros.map((x) => x.Id), item.Id, params.from), { replace: true, force: true });
            return;
          }
        }
        setState({ item });
      },
      (error: Error) => alive && setState({ error })
    );
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, attempt]);

  if (state.error || (state.item && !isPlayable(state.item))) {
    const copy = state.error
      ? state.error instanceof ApiError && state.error.status === 404
        ? { title: tr('Title not found'), text: tr('It may have been removed from the library.') }
        : { title: tr("Couldn't load this title"), text: state.error.message || tr('The server did not answer.') }
      : { title: tr('Nothing to play here'), text: tr('Pick an episode or a movie to start watching.') };
    return (
      <div class="pl-root">
        <ErrorPanel {...copy} onRetry={() => setAttempt((a) => a + 1)} onBack={() => back('/')} />
      </div>
    );
  }
  if (!state.item) {
    return (
      <div class="pl-root">
        <div class="pl-top"><BackButton onClick={() => back('/')} /></div>
        <div class="pl-spinner spinner" aria-label={tr('Loading')} />
      </div>
    );
  }
  if (state.item.Type === 'TvChannel') {
    // Live TV: its own player (Player · Live TV board); the address follows the channel without reloading it.
    const prefs: PlaybackPrefs = { ...DEFAULT_PLAYBACK, ...(meStore.get().prefs?.playback ?? {}) };
    const cap = prefs.maxQuality !== 'auto' ? qualityBitrate(prefQuality(prefs.maxQuality)) : null;
    return (
      <LivePlayer
        key="live"
        channel={state.item}
        profile={profileOptions(deviceStore.get())}
        bitrateFor={(q) => (q === 'auto' ? autoBitrate(deviceStore.get(), locationStore.get() ?? 'home', cap) : qualityBitrate(q))}
      />
    );
  }
  return <PlayerView key={state.item.Id} item={state.item} params={params} />;
}

const isPlayable = (it: BaseItem) => !it.IsFolder && (it.MediaType === 'Video' || it.MediaType === 'Audio' || !!it.MediaSources?.length);

/* ───────────────────────── Small pieces ───────────────────────── */

function BackButton({ onClick, label }: { onClick: () => void; label?: string }) {
  return (
    <button type="button" class="btn player-btn is-soft" aria-label={label ?? tr('Back')} onClick={onClick}>
      <Icon name="arrowLeft" size={22} />
    </button>
  );
}

function ErrorPanel({ title, text, onRetry, onBack }: { title: string; text: string; onRetry: () => void; onBack: () => void }) {
  return (
    <div class="pl-error" role="alert">
      <div class="empty pl-error-box">
        <span class="empty-icon"><Icon name="alert" size={26} /></span>
        <div class="empty-body">
          <span class="empty-title">{title}</span>
          <span class="empty-text">{text}</span>
          <div class="pl-error-actions">
            <button type="button" class="btn btn-primary is-sm" onClick={onRetry}><Icon name="refresh" size={18} />{tr('Try again')}</button>
            <button type="button" class="btn btn-ghost is-light" onClick={onBack}>{tr('Back')}</button>
          </div>
        </div>
      </div>
    </div>
  );
}

function PBtn({ label, icon, onClick, expanded, children, hidden, disabled }: {
  label: string; icon?: Parameters<typeof Icon>[0]['name']; onClick: () => void; expanded?: boolean; children?: ComponentChildren; hidden?: boolean; disabled?: boolean;
}) {
  if (hidden) return null;
  return (
    <button
      type="button"
      class="btn player-btn"
      aria-label={label}
      aria-expanded={expanded === undefined ? undefined : expanded ? 'true' : 'false'}
      onClick={onClick}
      disabled={disabled}
    >
      {icon && <Icon name={icon} size={22} />}
      {children}
    </button>
  );
}

function resLabel(w?: number, h?: number): string {
  if (!w && !h) return '';
  const W = w ?? 0;
  const H = h ?? 0;
  if (W >= 3800 || H >= 2100) return '4K';
  if (W >= 2500 || H >= 1400) return '1440p';
  if (W >= 1900 || H >= 1000) return '1080p';
  if (W >= 1200 || H >= 700) return '720p';
  if (H >= 560) return '576p';
  if (H >= 460) return '480p';
  return H ? `${H}p` : '';
}

function hdrLabel(range?: string): string {
  if (!range || range === 'SDR' || range === 'Unknown') return '';
  if (range.startsWith('DOVI')) return 'Dolby Vision';
  if (range === 'HDR10Plus') return 'HDR10+';
  return range;
}

function statusText(stream: ActiveStream, isAudio: boolean, vw: number, vh: number): string {
  const s = stream.source;
  const a = (s.MediaStreams ?? []).find((m) => m.Type === 'Audio' && m.Index === stream.audioIndex) ?? audioStreams(s)[0];
  const ch = channelsLabel(a?.Channels);
  if (isAudio) return [tr(stream.kind), (a?.Codec ?? '').toUpperCase(), ch].filter(Boolean).join(' · ');
  if (stream.kind === 'Transcoding') return [tr('Transcoding'), resLabel(vw, vh)].filter(Boolean).join(' · ');
  const v = videoStream(s);
  const pic = [resLabel(v?.Width, v?.Height), hdrLabel(v?.VideoRangeType)].filter(Boolean).join(' ');
  return [stream.engine === 'soft' ? tr('Decoded here') : tr(stream.kind), pic, ch].filter(Boolean).join(' · ');
}

/* Whether the server hands this subtitle to Seedy to draw (text, styled, PGS) rather than burning it in. */
const drawnHere = (s: MediaStream) =>
  s.DeliveryMethod ? s.DeliveryMethod === 'External' : s.IsTextSubtitleStream ?? TEXT_SUB_CODECS.has((s.Codec ?? '').toLowerCase());
const isStyledSub = (s?: MediaStream) => !!s && /^(ass|ssa)$/i.test(s.Codec ?? '');

/* Choices that carry over to the next title in this tab. */
const memory: { quality: QualityId | null; speed: number } = { quality: null, speed: 1 };

const NO_CUES: Cue[] = [];

const skippedCopy = (type: string) =>
  tr(type === 'Intro' ? 'Skipped intro' : type === 'Recap' ? 'Skipped recap' : type === 'Preview' ? 'Skipped preview' : type === 'Commercial' ? 'Skipped ad' : 'Skipped');

/* "Are you still watching?": episodes auto-played in a row, and since when nobody has touched anything. */
const presence = { episodes: 0, since: Date.now(), auto: false };

const prefQuality = (q: PlaybackPrefs['maxQuality']): QualityId => (q === '4k' || q === '1080p' || q === '720p' ? q : 'auto');

const SIZE_FROM_PREFS: Record<SubtitlePrefs['size'], SubSize> = { S: 'small', M: 'normal', L: 'large' };

/* This device's settings, as the device profile takes them. */
function profileOptions(d: DeviceSettings): ProfileOptions {
  return {
    maxAudioChannels: d.audioChannels,
    cbrAudio: d.cbrAudio,
    maxWidth: maxVideoWidth(d.maxResolution),
    limitResolution: d.limitResolution,
    preferFmp4: d.preferFmp4,
    hevc: d.hevc,
    av1: d.av1,
    hdr: d.hdr,
    dts: d.dts,
    truehd: d.truehd,
    mkv: d.mkv,
    burnIn: d.subtitleBurnIn,
    pictureSubtitles: d.pictureSubtitles,
    styledSubtitles: d.subtitleEngine === 'auto'
  };
}

/* Same stream after the item's streams were re-read (a downloaded subtitle shifts indexes). */
function remapIndex(oldSrc: MediaSource | undefined, newSrc: MediaSource | undefined, index: number | undefined): number | undefined {
  if (index === undefined || index < 0 || !oldSrc || !newSrc) return index;
  const o = oldSrc.MediaStreams?.find((m) => m.Index === index);
  if (!o) return index;
  const n = newSrc.MediaStreams?.find((m) =>
    m.Type === o.Type && m.Codec === o.Codec && m.Language === o.Language && !!m.IsExternal === !!o.IsExternal
    && (m.Title ?? '') === (o.Title ?? '') && (m.Path ?? '') === (o.Path ?? '') && !!m.IsForced === !!o.IsForced);
  return n?.Index ?? index;
}

function useCountdown(active: boolean, running: boolean, seconds: number, onDone: () => void): number | null {
  const [left, setLeft] = useState(seconds);
  const done = useRef(onDone);
  done.current = onDone;
  useEffect(() => { if (active) setLeft(seconds); }, [active, seconds]);
  useEffect(() => {
    if (!active || !running || seconds <= 0) return;
    const t = window.setInterval(() => setLeft((l) => l - 1), 1000);
    return () => window.clearInterval(t);
  }, [active, running, seconds]);
  useEffect(() => {
    if (active && seconds > 0 && left <= 0) done.current();
  }, [active, left, seconds]);
  return active && seconds > 0 ? Math.max(0, left) : null;
}

type Panel = { kind: 'tracks'; tab: TracksTab } | { kind: 'chapters' } | { kind: 'episodes' } | null;
type Flow = 'idle' | 'shown' | 'dismissed';

interface MediaState {
  time: number;
  paused: boolean;
  waiting: boolean;
  buffered: number;
  volume: number;
  muted: boolean;
  rate: number;
  vw: number;
  vh: number;
  ended: boolean;
  mediaDuration: number;
}

/* ───────────────────────── The player ───────────────────────── */

function PlayerView({ item: initialItem, params }: { item: BaseItem; params: Params }) {
  const me = useStore(meStore);
  const user = useStore(userStore);
  const dev = useStore(deviceStore);
  const prefs: PlaybackPrefs = { ...DEFAULT_PLAYBACK, ...(me.prefs?.playback ?? {}) };
  const custom = customPlayback(me.prefs?.custom);
  const steps = seekSteps(dev, prefs);
  const isPreroll = !!params.preroll;
  const [item, setItem] = useState(initialItem);
  const itemRef = useRef(item);
  itemRef.current = item;
  const isAudio = item.MediaType === 'Audio';
  const isEpisode = item.Type === 'Episode';
  const isMovie = item.Type === 'Movie';

  const mediaRef = useRef<HTMLVideoElement>(null);
  /* Where the experimental player draws, and what the controls drive: the <video> element or that player. */
  const softRef = useRef<HTMLDivElement>(null);
  const bridgeRef = useRef<MediaBridge | null>(null);
  const media = () => (bridgeRef.current ??= new MediaBridge(mediaRef.current!));
  const sessRef = useRef<PlaybackSession | null>(null);
  const ms0 = item.MediaSources?.[0];
  const startSeconds = useMemo(() => {
    if (params.t !== null && params.t !== '') return ticksToSeconds(Number(params.t) || 0);
    if (params.restart) return 0;
    const pos = ticksToSeconds(item.UserData?.PlaybackPositionTicks);
    const total = ticksToSeconds(item.RunTimeTicks);
    return total && pos > total - 10 ? 0 : pos;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  /* "Auto" is this device's limit where it is now (home or away), under the profile's cap. */
  const [quality, setQuality] = useState<QualityId>(memory.quality ?? 'auto');
  const adminCap = prefs.maxQuality !== 'auto' ? qualityBitrate(prefQuality(prefs.maxQuality)) : null;
  const bitrateFor = (q: QualityId, loc: NetworkLocation = locationStore.get() ?? 'home') =>
    (q === 'auto' ? autoBitrate(deviceStore.get(), loc, adminCap) : qualityBitrate(q));
  /* Same kind of tracks as the last episode of this show ("Keep my audio/subtitle choice"). */
  const initialTracks = useMemo(() => {
    let audioIndex = ms0?.DefaultAudioStreamIndex;
    let subtitleIndex = ms0?.DefaultSubtitleStreamIndex ?? -1;
    const cfg = user?.Configuration;
    if (isEpisode && item.SeriesId && lastTracks.seriesId === item.SeriesId && ms0) {
      if (cfg?.RememberAudioSelections !== false && lastTracks.audio) audioIndex = closestTrack(audioStreams(ms0), lastTracks.audio) ?? audioIndex;
      if (cfg?.RememberSubtitleSelections !== false && lastTracks.sub) {
        subtitleIndex = lastTracks.sub === 'off' ? -1 : closestTrack(subtitleStreams(ms0), lastTracks.sub) ?? subtitleIndex;
      }
    }
    return { audioIndex, subtitleIndex };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const optsRef = useRef<StreamOptions>({
    mediaSourceId: ms0?.Id,
    audioIndex: initialTracks.audioIndex,
    subtitleIndex: initialTracks.subtitleIndex,
    maxBitrate: bitrateFor(quality),
    startSeconds,
    profile: profileOptions(dev),
    burnWhenConverting: dev.burnInWhenConverting,
    burnSubtitle: dev.subtitleBurnIn === 'styled' && isStyledSub(subtitleStreams(ms0).find((x) => x.Index === initialTracks.subtitleIndex)),
    soft: dev.softPlayer
  });
  const lastPos = useRef(startSeconds);
  const [attempt, setAttempt] = useState(0);
  const [stream, setStream] = useState<ActiveStream | null>(null);
  const [fatal, setFatal] = useState<{ title: string; text: string } | null>(null);
  const [busy, setBusy] = useState(false);
  /* Whether playback should be running (the element pauses by itself on errors and stream switches). */
  const wantPlay = useRef(true);
  const originSent = useRef(false);

  const initialVolume = safeLocal<number>('seedy.volume', me.prefs?.volume ?? 1);
  const [m, setM] = useState<MediaState>({
    time: startSeconds, paused: true, waiting: true, buffered: 0, volume: initialVolume, muted: safeLocal('seedy.muted', false),
    rate: memory.speed, vw: 0, vh: 0, ended: false, mediaDuration: 0
  });

  const [subIndex, setSubIndex] = useState<number>(optsRef.current.subtitleIndex);
  const lastSub = useRef<number>(subIndex >= 0 ? subIndex : -1);
  const [loaded, setLoaded] = useState<LoadedSubtitles | null>(null);
  const [subOffset, setSubOffset] = useState(0);
  const [subPrefs, setSubPrefs] = useState<SubtitlePrefs>(me.prefs?.subtitles ?? { size: 'M', lift: true });
  /* How subtitles look on this device (Settings · Subtitles); the quick S / M / L and lift write to it too. */
  const subStyle: SubtitleStyle = resolveStyle(dev, subPrefs);
  const [pip, setPip] = useState(false);
  /* The run as it was when the question came up (moving the mouse to answer starts a new run). */
  const [ask, setAsk] = useState<{ byTime: boolean; episodes: number; minutes: number } | null>(null);
  const [itemVersion, setItemVersion] = useState(0);

  const [panel, setPanel] = useState<Panel>(null);
  const [active, setActive] = useState(true);
  const [scrubbing, setScrubbing] = useState(false);
  const [dragAt, setDragAt] = useState<number | null>(null);
  const [wake, setWake] = useState(0);
  const [pauseScreen, setPauseScreen] = useState(false);
  const [fullscreen, setFullscreen] = useState(!!document.fullscreenElement);
  const [upNext, setUpNext] = useState<Flow>('idle');
  const [endFlow, setEndFlow] = useState<Flow>('idle');
  const [tile, setTile] = useState<TileRect | null>(null);
  /* Once the video has shrunk onto its tile it follows the tile without easing (scrolling, resizing). */
  const [tileSettled, setTileSettled] = useState(false);

  const [segments, setSegments] = useState<MediaSegment[]>([]);
  const [episodes, setEpisodes] = useState<BaseItem[] | null>(null);
  const [queueNext, setQueueNext] = useState<BaseItem | null>(null);
  const [picks, setPicks] = useState<BaseItem[]>([]);
  const [seriesRating, setSeriesRating] = useState<string | undefined>(undefined);
  const skipped = useRef(new Set<string>());

  const duration = ticksToSeconds(stream?.source.RunTimeTicks ?? item.RunTimeTicks) || m.mediaDuration;

  /* ── Side data: segments, episodes, queue, similar ── */
  useEffect(() => {
    getMediaSegments(item.Id).then((r) => setSegments(r?.Items ?? []), () => setSegments([]));
    if (isEpisode && item.SeriesId && !item.OfficialRating) {
      getItem(item.SeriesId, []).then((sr) => setSeriesRating(sr.OfficialRating), () => undefined);
    }
    if (isEpisode && item.SeriesId) {
      getEpisodes(item.SeriesId, undefined, { isMissing: false }).then((r) => setEpisodes(r.Items ?? []), () => setEpisodes([]));
    }
    if (params.queue.length) getItem(params.queue[0], ['Overview']).then(setQueueNext, () => setQueueNext(null));
    if (isMovie && custom.moviePicks && !isPreroll) {
      getSimilar(item.Id, 8).then(
        (r) => setPicks((r.Items ?? []).filter((x) => x.Id !== item.Id && !x.IsFolder && x.Type !== 'Series').slice(0, 3)),
        () => setPicks([])
      );
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.Id]);

  const epIndex = episodes ? episodes.findIndex((e) => e.Id === item.Id) : -1;
  const nextEp = epIndex >= 0 ? episodes![epIndex + 1] ?? null : null;
  const prevEp = epIndex > 0 ? episodes![epIndex - 1] : null;
  const next: BaseItem | null = isPreroll ? null : params.queue.length ? queueNext : nextEp;
  const nextPath = params.queue.length
    ? `/play/${params.queue[0]}${params.queue.length > 1 ? '?queue=' + params.queue.slice(1).join(',') : ''}`
    : nextEp ? `/play/${nextEp.Id}` : null;
  /* After a trailer: the next trailer, or the movie. */
  const prerollNext = params.preroll
    ? params.pre.length ? prerollPath(params.pre, params.preroll, params.from) : afterPrerollPath(params.preroll, params.from)
    : null;

  /* ── Errors ── */
  /* A trailer that won't play mustn't stand between the viewer and the movie: go on to the next one. */
  const skipBrokenPreroll = () => {
    // No session: the player has closed meanwhile, and must not navigate anywhere.
    if (!prerollNext || !sessRef.current) return false;
    sessRef.current.stop({ failed: true });
    navigate(prerollNext, { replace: true });
    return true;
  };
  const fail = (err: unknown) => {
    if (err instanceof Error && err.name === 'AbortError') return;
    if (skipBrokenPreroll()) return;
    if (err instanceof PlaybackError) setFatal(errorCopy(err.code));
    else if (err instanceof ApiError) setFatal({ title: tr("Couldn't start playback"), text: err.message });
    else setFatal(errorCopy('network'));
    sessRef.current?.stop({ failed: true });
  };
  const onFatal = (code: string, st: ActiveStream, part?: FailedPart) => {
    const s = sessRef.current;
    if (!s) return;
    const me = mediaRef.current?.error;
    console.warn('[seedy] playback error', code, st.method, part ?? '', me ? `${me.code} ${me.message}` : '');
    /* The experimental player couldn't play it, or not fast enough: the server converts it, as without that player. */
    if (st.engine === 'soft') {
      toast(code === 'soft-slow'
        ? tr('This device couldn’t keep up, so the server is converting this video')
        : tr('The experimental player couldn’t play this, so the server is converting it'));
      s.change({ soft: 'off' }).then((ns) => { if (ns && wantPlay.current && !inGroup()) s.play(); }, fail);
      return;
    }
    /* A stream the browser said it could play but couldn't: the server converts it, a step further each time. */
    const fallback = code === 'media' ? nextAttempt(s.opts, st.method, part) : null;
    if (fallback) {
      s.change(fallback).then((ns) => { if (ns && wantPlay.current && !inGroup()) s.play(); }, fail);
      return;
    }
    if (skipBrokenPreroll()) return;
    s.stop({ failed: true });
    setFatal(errorCopy(code));
  };
  const onFatalRef = useRef(onFatal);
  onFatalRef.current = onFatal;

  /* ── Session (re-created on "Try again") ── */
  useEffect(() => {
    const el = media();
    setFatal(null);
    el.volume = Math.max(0, Math.min(1, m.volume));
    el.muted = m.muted;
    let s: PlaybackSession | null = null;
    let cancelled = false;
    // The quality limit depends on whether this device is home or away, so find that out first (once per tab).
    const start = (loc: NetworkLocation) => {
      if (cancelled) return;
      const d = deviceStore.get();
      const opts: StreamOptions = {
        ...optsRef.current,
        startSeconds: lastPos.current,
        profile: profileOptions(d),
        burnWhenConverting: d.burnInWhenConverting
      };
      if (quality === 'auto') opts.maxBitrate = bitrateFor('auto', loc);
      const sess = new PlaybackSession(itemRef.current, el, {
        onStream: (st) => setStream(st),
        onFatal: (code, st, part) => onFatalRef.current(code, st, part),
        softHost: () => softRef.current,
        isolate: isolateReload
      }, opts);
      s = sess;
      sessRef.current = sess;
      sess.open().then((st) => {
        if (!st) return;
        el.playbackRate = memory.speed;
        if (!inGroup()) sess.play();
        if (!originSent.current && !params.preroll) {
          originSent.current = true;
          sendOrigin(itemRef.current, params.from);
        }
      }, fail);
    };
    const known = locationStore.get();
    if (known) start(known);
    else detectLocation().then(start, () => start('away'));
    const onHide = () => s?.stop();
    window.addEventListener('pagehide', onHide);
    return () => {
      cancelled = true;
      window.removeEventListener('pagehide', onHide);
      if (s) {
        lastPos.current = s.position();
        optsRef.current = { ...s.opts };
        s.release();
        if (sessRef.current === s) sessRef.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attempt]);

  /* The experimental player decodes on several threads only in a cross-origin isolated page, which the server makes
     of a page load that asks for it. Asked once per page: a browser that can't isolate plays on one thread. */
  function isolateReload(seconds: number): boolean {
    const q = new URLSearchParams(window.location.search);
    if (q.get('isolate') === '1') return false;
    q.set('isolate', '1');
    q.set('t', String(secondsToTicks(seconds)));
    q.delete('restart');
    window.location.replace(window.location.pathname + '?' + q.toString());
    return true;
  }

  const retry = () => {
    setFatal(null);
    setStream(null);
    setAttempt((a) => a + 1);
  };

  /* ── Media element → state, reports, SyncPlay readiness ── */
  const endedRef = useRef<() => void>(() => undefined);
  useEffect(() => {
    const el = media();
    const read = (patch: Partial<MediaState> = {}) => {
      const s = sessRef.current;
      const offset = s?.stream?.timeOffset ?? 0;
      let buffered = 0;
      try {
        for (let i = 0; i < el.buffered.length; i++) {
          if (el.buffered.start(i) <= el.currentTime + 1 && el.buffered.end(i) >= el.currentTime) buffered = el.buffered.end(i) + offset;
        }
      } catch { /* detached */ }
      setM((prev) => ({
        ...prev,
        time: s ? s.position() : el.currentTime,
        paused: el.paused,
        buffered,
        volume: el.volume,
        muted: el.muted,
        rate: el.playbackRate,
        vw: el.videoWidth || prev.vw,
        vh: el.videoHeight || prev.vh,
        mediaDuration: Number.isFinite(el.duration) ? el.duration + offset : prev.mediaDuration,
        ...patch
      }));
    };
    const report = (ev: string) => sessRef.current?.report('progress', ev);
    const handlers: [string, () => void][] = [
      ['timeupdate', () => read()],
      ['progress', () => read()],
      ['durationchange', () => read()],
      ['loadedmetadata', () => read()],
      ['resize', () => read()],
      ['play', () => { read({ ended: false }); report('unpause'); }],
      ['pause', () => { read(); report('pause'); }],
      ['playing', () => { read({ waiting: false, ended: false }); reportReady(); }],
      ['waiting', () => { read({ waiting: true }); if (!el.paused) reportBuffering(); }],
      ['seeking', () => read()],
      ['seeked', () => { read(); report('timeupdate'); reportReady(); }],
      ['canplay', () => { read({ waiting: false }); reportReady(); }],
      ['volumechange', () => {
        read();
        saveLocal('seedy.volume', el.volume);
        saveLocal('seedy.muted', el.muted);
        report('volumechange');
      }],
      ['ratechange', () => { read(); report('ratechange'); }],
      ['ended', () => { read({ ended: true, paused: true }); endedRef.current(); }]
    ];
    handlers.forEach(([ev, fn]) => el.addEventListener(ev, fn));
    return () => handlers.forEach(([ev, fn]) => el.removeEventListener(ev, fn));
  }, []);

  /* ── Subtitle cues ── */
  const source: MediaSource | undefined = stream?.source ?? item.MediaSources?.find((s) => s.Id === optsRef.current.mediaSourceId) ?? ms0;
  const subs = subtitleStreams(source);
  const auds = audioStreams(source);
  const subKey = stream ? `${stream.source.Id}:${stream.playSessionId}:${stream.burnIn ? 'b' : 't'}` : '';
  useEffect(() => {
    setLoaded(null);
    if (!stream || subIndex < 0 || stream.burnIn) return;
    const st = subs.find((x) => x.Index === subIndex);
    if (!st || !drawnHere(st)) return;
    const ctrl = new AbortController();
    const format = subtitleFormat(st);
    const url = subtitleUrl(item, stream.source, st, format);
    const load: Promise<LoadedSubtitles> = format === 'pgs'
      ? loadPgs(url, ctrl.signal).then((cues) => ({ kind: 'pgs', cues }))
      : format === 'ass'
        ? fetch(url, { signal: ctrl.signal }).then((r) => {
          if (!r.ok) throw new Error(`Subtitle request failed (${r.status})`);
          return r.text();
        }).then((text) => ({ kind: 'ass', track: parseAss(text) }))
        : loadCues(url, ctrl.signal).then((cues) => ({ kind: 'vtt', cues }));
    load.then(setLoaded, (e: Error) => {
      if (e.name !== 'AbortError') toast(tr("Couldn't load those subtitles"), 'error');
    });
    return () => ctrl.abort();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [subKey, subIndex, itemVersion]);

  /* Remember this episode's tracks for the next one. */
  useEffect(() => {
    if (!stream || !isEpisode || !item.SeriesId) return;
    const a = auds.find((x) => x.Index === stream.audioIndex);
    const sb = subs.find((x) => x.Index === subIndex);
    lastTracks.seriesId = item.SeriesId;
    lastTracks.audio = a ? trackSig(a) : null;
    lastTracks.sub = subIndex < 0 ? 'off' : sb ? trackSig(sb) : null;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [stream?.source.Id, stream?.audioIndex, subIndex]);

  /* Browser captions: always with that engine, and in picture in picture / iOS full screen with Auto. */
  useEffect(() => {
    const v = mediaRef.current;
    if (!v) return;
    const on = () => setPip(true);
    const off = () => setPip(!!document.pictureInPictureElement);
    const evs: [string, () => void][] = [
      ['enterpictureinpicture', on], ['leavepictureinpicture', off], ['webkitbeginfullscreen', on], ['webkitendfullscreen', off]
    ];
    evs.forEach(([e, fn]) => v.addEventListener(e, fn));
    return () => evs.forEach(([e, fn]) => v.removeEventListener(e, fn));
  }, []);
  const textCues: Cue[] = loaded?.kind === 'vtt' ? loaded.cues : NO_CUES;
  const soft = stream?.engine === 'soft';
  /* (The experimental player has no text tracks: Seedy draws every subtitle over it.) */
  const nativeCues = loaded?.kind === 'vtt' && subIndex >= 0 && !stream?.burnIn && !soft
    && (dev.subtitleEngine === 'browser' || (dev.subtitleEngine === 'auto' && pip));
  useNativeCues(mediaRef.current, textCues, nativeCues, subOffset, subStyle);

  /* ── Actions ── */
  const el = () => media();
  const sess = () => sessRef.current;

  const play = () => {
    wantPlay.current = true;
    if (inGroup()) { void requestUnpause(); return; }
    sess()?.play();
  };
  const pause = () => {
    wantPlay.current = false;
    if (inGroup()) { void requestPause(); return; }
    el().pause();
  };
  const togglePlay = () => (el().paused ? play() : pause());
  const seekTo = (seconds: number) => {
    const t = Math.max(0, Math.min(seconds, duration || seconds));
    if (inGroup()) { void requestSeek(t); return; }
    setM((p) => ({ ...p, time: t }));
    sess()?.seek(t);
  };
  const seekBy = (delta: number) => seekTo((sess()?.position() ?? m.time) + delta);
  const setVolume = (v: number) => {
    const e = el();
    e.volume = Math.max(0, Math.min(1, v));
    e.muted = v <= 0;
  };
  const toggleMute = () => {
    const e = el();
    if (e.muted || e.volume === 0) {
      e.muted = false;
      if (e.volume === 0) e.volume = 0.5;
    } else e.muted = true;
  };
  const toggleFullscreen = () => {
    if (document.fullscreenElement) { void document.exitFullscreen().catch(() => undefined); return; }
    const root = document.documentElement;
    if (root.requestFullscreen) void root.requestFullscreen().catch(() => undefined);
    else (mediaRef.current as HTMLVideoElement & { webkitEnterFullscreen?: () => void } | null)?.webkitEnterFullscreen?.();
  };
  const pipSupported = !isAudio && !soft && typeof document !== 'undefined' && (document as Document & { pictureInPictureEnabled?: boolean }).pictureInPictureEnabled === true;
  const togglePip = () => {
    if (document.pictureInPictureElement) void document.exitPictureInPicture().catch(() => undefined);
    else void mediaRef.current?.requestPictureInPicture?.().catch(() => toast(tr("Picture in picture isn't available"), 'error'));
  };

  const exit = () => {
    if (inGroup()) void leaveGroup();
    sess()?.stop();
    if (document.fullscreenElement) void document.exitFullscreen().catch(() => undefined);
    back('/');
  };
  const goNext = (fromStart: boolean) => {
    if (!nextPath || !next) return;
    if (inGroup()) { void requestQueue(next.Id, 0); return; }
    navigate(nextPath + (fromStart ? (nextPath.includes('?') ? '&' : '?') + 'restart=1' : ''), { replace: true });
  };
  /* Auto-play, unless it's time to ask whether anyone is still there. */
  const autoNext = () => {
    if (!next) return;
    const sw = custom.stillWatching;
    if (sw && !inGroup()) {
      const minutes = (Date.now() - presence.since) / 60000;
      const byEpisodes = presence.episodes + 1 >= sw.episodes;
      const byTime = sw.minutes > 0 && minutes >= sw.minutes;
      if (byEpisodes || byTime) {
        if (!el().paused) pause();
        setUpNext('dismissed');
        setPanel(null);
        setAsk({ byTime: byTime && !byEpisodes, episodes: presence.episodes + 1, minutes: Math.round(minutes) });
        return;
      }
    }
    presence.auto = true;
    goNext(true);
  };
  const skipTrailers = () => {
    if (!params.preroll) return;
    sess()?.stop();
    navigate(afterPrerollPath(params.preroll, params.from), { replace: true });
  };
  const goPrev = () => {
    if ((sess()?.position() ?? 0) > 5 || !prevEp) { seekTo(0); return; }
    if (inGroup()) { void requestQueue(prevEp.Id, 0); return; }
    navigate(`/play/${prevEp.Id}?restart=1`, { replace: true });
  };
  const playItem = (it: BaseItem) => {
    if (inGroup()) { void requestQueue(it.Id, 0); return; }
    navigate(`/play/${it.Id}`, { replace: true });
  };

  const setSubtitle = (index: number) => {
    const s = sess();
    if (!s) return;
    if (index >= 0) lastSub.current = index;
    const st = subs.find((x) => x.Index === index);
    const d = deviceStore.get();
    /* Burnt in: what the server won't hand over, styled subtitles under "Styled and picture", and anything while
       converting with "Always burn in while converting". */
    const burnStyled = d.subtitleBurnIn === 'styled' && isStyledSub(st);
    const converting = !!stream && stream.method !== 'DirectPlay';
    const needsBurn = !!st && (!drawnHere(st) || burnStyled || (d.burnInWhenConverting && converting));
    setSubIndex(index);
    s.subtitleIndex = index;
    s.opts = { ...s.opts, subtitleIndex: index, burnSubtitle: burnStyled };
    if (needsBurn || stream?.burnIn) {
      setBusy(true);
      s.change({ subtitleIndex: index, burnSubtitle: burnStyled, profile: profileOptions(d), burnWhenConverting: d.burnInWhenConverting }, 'SubtitleTrackChange')
        .catch(fail).finally(() => setBusy(false));
    } else s.report('progress', 'SubtitleTrackChange');
  };
  const toggleSubtitles = () => {
    if (subIndex >= 0) { setSubtitle(-1); return; }
    const pick = lastSub.current >= 0 ? lastSub.current : (source?.DefaultSubtitleStreamIndex ?? -1) >= 0 ? source!.DefaultSubtitleStreamIndex! : subs[0]?.Index;
    if (pick !== undefined && pick >= 0) setSubtitle(pick);
  };
  const setAudio = (index: number) => {
    const s = sess();
    if (!s || index === stream?.audioIndex) return;
    setBusy(true);
    s.change({ audioIndex: index, profile: profileOptions(deviceStore.get()) }, 'AudioTrackChange').catch(fail).finally(() => setBusy(false));
  };
  const chooseQuality = (q: QualityId) => {
    const s = sess();
    memory.quality = q;
    setQuality(q);
    if (!s || bitrateFor(q) === stream?.maxBitrate) return;
    setBusy(true);
    s.change({ maxBitrate: bitrateFor(q), profile: profileOptions(deviceStore.get()) }, 'QualityChange').catch(fail).finally(() => setBusy(false));
  };
  const chooseSpeed = (r: number) => {
    memory.speed = r;
    el().playbackRate = r;
  };
  const updateSubPrefs = (p: SubtitlePrefs) => {
    // The quick S / M / L and lift follow the profile and change this device's look with them.
    const patch: Partial<SubtitleStyle> = {};
    if (p.size !== SIZE_TO_PREFS[subStyle.size]) patch.size = SIZE_FROM_PREFS[p.size];
    if (p.lift !== (subStyle.position === 'lifted')) patch.position = p.lift ? 'lifted' : 'bottom';
    if (Object.keys(patch).length) setSubtitleStyle(patch);
    setSubPrefs(p);
    // The shared prefs queue: quick S / M / L taps reach the server in order.
    void queuePrefs({ subtitles: p });
  };
  const afterSubtitleDownload = async () => {
    const s = sess();
    const fresh = await getItem(item.Id, PLAYER_FIELDS).catch(() => null);
    if (!fresh || !s) return;
    const oldSrc = stream?.source;
    const newSrc = fresh.MediaSources?.find((x) => x.Id === oldSrc?.Id) ?? fresh.MediaSources?.[0];
    const added = subtitleStreams(newSrc).find((n) => n.IsExternal && n.Path && !subtitleStreams(oldSrc).some((o) => o.Path === n.Path));
    s.item = fresh;
    setItem(fresh);
    setItemVersion((v) => v + 1);
    const audioIndex = remapIndex(oldSrc, newSrc, stream?.audioIndex);
    const subtitleIndex = added?.Index ?? remapIndex(oldSrc, newSrc, subIndex) ?? -1;
    setBusy(true);
    try {
      await s.change({ audioIndex, subtitleIndex });
      s.subtitleIndex = subtitleIndex;
      setSubIndex(subtitleIndex);
      if (subtitleIndex >= 0) lastSub.current = subtitleIndex;
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  /* ── Panels ── */
  const tabGroup = (t: TracksTab) => (t === 'speed' ? 'quality' : t);
  const openTracks = (tab: TracksTab) =>
    setPanel((p) => (p?.kind === 'tracks' && tabGroup(p.tab) === tabGroup(tab) ? null : { kind: 'tracks', tab }));
  const tracksTab = panel?.kind === 'tracks' ? panel.tab : null;

  /* ── Activity: controls hide after 3 s of no input while playing ── */
  const hideTimer = useRef(0);
  const lastWake = useRef(0);
  const poke = () => {
    window.clearTimeout(hideTimer.current);
    const arm = () => {
      hideTimer.current = window.setTimeout(() => {
        /* Keep the controls while one of their menus is open. */
        if (document.querySelector('.popover')) arm();
        else setActive(false);
      }, 3000);
    };
    arm();
    setActive(true);
    const now = Date.now();
    if (now - lastWake.current > 400) {
      lastWake.current = now;
      setWake((w) => w + 1);
    }
  };
  const pokeRef = useRef(poke);
  pokeRef.current = poke;
  useEffect(() => {
    const fn = () => pokeRef.current();
    window.addEventListener('pointermove', fn, { passive: true });
    window.addEventListener('pointerdown', fn, { passive: true });
    window.addEventListener('wheel', fn, { passive: true });
    poke();
    return () => {
      window.removeEventListener('pointermove', fn);
      window.removeEventListener('pointerdown', fn);
      window.removeEventListener('wheel', fn);
      window.clearTimeout(hideTimer.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const fn = () => setFullscreen(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', fn);
    return () => document.removeEventListener('fullscreenchange', fn);
  }, []);

  /* Someone is there whenever the player gets real input; an auto-played episode adds to the run. */
  useEffect(() => {
    if (presence.auto) presence.episodes++;
    else { presence.episodes = 0; presence.since = Date.now(); }
    presence.auto = false;
    const here = (e: Event) => {
      if (e.type === 'pointermove' && !(e as PointerEvent).movementX && !(e as PointerEvent).movementY) return;
      presence.episodes = 0;
      presence.since = Date.now();
    };
    const types = ['pointermove', 'pointerdown', 'wheel', 'keydown'];
    types.forEach((t) => window.addEventListener(t, here, { passive: true }));
    return () => types.forEach((t) => window.removeEventListener(t, here));
  }, []);

  /* ── Pause screen ── */
  useEffect(() => {
    setPauseScreen(false);
    if (!m.paused || m.ended || !stream || fatal || panel || upNext === 'shown' || endFlow === 'shown' || scrubbing || ask) return;
    if (prefs.pauseScreenSeconds <= 0) return;
    const t = window.setTimeout(() => setPauseScreen(true), prefs.pauseScreenSeconds * 1000);
    return () => window.clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [m.paused, m.ended, wake, !!stream, !!fatal, panel, upNext, endFlow, scrubbing, prefs.pauseScreenSeconds, !!ask]);

  /* The screen stays on while a video plays; paused, the computer may sleep as usual. */
  useWakeLock(!isAudio && !!stream && !fatal && !m.paused && !m.ended);

  /* ── Media segments: skip buttons, auto-skip, credits → Up next / end of movie ── */
  const t = m.time;
  const segOf = (type: string) => segments.find((s) => s.Type === type);
  const outro = segOf('Outro');
  const creditsAt = outro ? ticksToSeconds(outro.StartTicks) : null;
  const creditsMode = segmentMode('Outro', prefs, custom);
  let trigger: number | null = null;
  if (duration > 0 && creditsMode !== 'off' && !isAudio && !isPreroll) {
    if (next) trigger = creditsAt ?? duration - Math.min(25, duration * 0.15);
    else if (isMovie && picks.length) trigger = creditsAt ?? duration - Math.min(120, Math.max(20, duration * 0.1));
  }

  /* Intros, recaps, previews and ads: skipped, offered with a button, or left alone, as set per kind. */
  const skipMode = (type: string) => segmentMode(type, prefs, custom);
  const skippable = segments.find((s) => {
    if (s.Type === 'Outro' || s.Type === 'Unknown' || skipMode(s.Type) === 'off') return false;
    const a = ticksToSeconds(s.StartTicks);
    const b = ticksToSeconds(s.EndTicks);
    return t >= a && t < b - 1;
  });
  const skipLabel = (type: string) => tr(type === 'Intro' ? 'Skip intro' : type === 'Recap' ? 'Skip recap' : type === 'Preview' ? 'Skip preview' : type === 'Commercial' ? 'Skip ad' : 'Skip');
  /* Credits without anything to go on to: a plain "Skip credits". */
  const inOutro = !!outro && t >= ticksToSeconds(outro.StartTicks) && t < ticksToSeconds(outro.EndTicks) - 1;
  const creditsSkip = outro && !next && !(isMovie && picks.length) && creditsMode === 'button' && inOutro;

  useEffect(() => {
    if (!stream || m.ended) return;
    if (skippable && skipMode(skippable.Type) === 'auto' && !skipped.current.has(skippable.Id)) {
      skipped.current.add(skippable.Id);
      seekTo(ticksToSeconds(skippable.EndTicks));
      toast(skippedCopy(skippable.Type));
      return;
    }
    // Credits set to Skip with nothing to go on to: jump past them.
    if (outro && inOutro && creditsMode === 'auto' && !next && !(isMovie && picks.length) && !skipped.current.has(outro.Id)) {
      skipped.current.add(outro.Id);
      seekTo(ticksToSeconds(outro.EndTicks));
      toast(tr('Skipped credits'));
      return;
    }
    if (trigger === null) return;
    if (t >= trigger) {
      if (next && upNext === 'idle') {
        if (creditsMode === 'auto' && creditsAt !== null && !inGroup() && (prefs.autoPlayNext || params.queue.length)) {
          toast(tr('Skipped credits'));
          setUpNext('dismissed');
          autoNext();
        } else {
          setUpNext('shown');
          setPanel(null);
        }
      } else if (!next && isMovie && picks.length && endFlow === 'idle') {
        setEndFlow('shown');
        setPanel(null);
      }
    } else if (t < trigger - 2) {
      if (upNext !== 'idle') setUpNext('idle');
      if (endFlow !== 'idle') setEndFlow('idle');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [Math.floor(t * 2), trigger, !!stream, skippable?.Id, next?.Id, picks.length]);

  endedRef.current = () => {
    const s = sess();
    if (prerollNext) {
      s?.stop({ ended: true });
      navigate(prerollNext, { replace: true });
      return;
    }
    if (next && (prefs.autoPlayNext || params.queue.length)) {
      s?.stop({ ended: true });
      autoNext();
      return;
    }
    if (next && upNext !== 'shown' && !ask) {
      // Auto-play is off: offer the next episode instead of leaving.
      s?.stop({ ended: true });
      setUpNext('shown');
      return;
    }
    if (isMovie && picks.length) {
      s?.stop({ ended: true });
      setEndFlow('shown');
      return;
    }
    if (upNext === 'shown') { s?.stop({ ended: true }); return; }
    s?.stop({ ended: true });
    exit();
  };

  const upNextShown = upNext === 'shown' && !!next;
  const endShown = endFlow === 'shown' && isMovie && picks.length > 0;
  const upCount = useCountdown(upNextShown && prefs.autoPlayNext && !ask, !m.paused || m.ended, prefs.upNextSeconds, autoNext);
  const endCount = useCountdown(endShown, !m.paused || m.ended, prefs.moviePicksSeconds, () => picks[0] && playItem(picks[0]));

  /* ── SyncPlay binding ── */
  useEffect(() => bindSyncPlayer({
    itemId: item.Id,
    play: () => sess()?.play(),
    pause: () => media().pause(),
    seek: (sec) => {
      const s = sess();
      if (s && Math.abs(s.position() - sec) > 0.5) s.seek(sec);
    },
    position: () => sess()?.position() ?? 0,
    paused: () => media().paused,
    load: (id, sec) => navigate(`/play/${id}?t=${secondsToTicks(sec)}`, { replace: true }),
    stop: () => {
      media().pause();
      toast(tr('The group stopped playback'));
    }
  }), [item.Id]);

  /* ── Keyboard ── */
  const keyRef = useRef<(e: KeyboardEvent) => void>(() => undefined);
  keyRef.current = (e: KeyboardEvent) => {
    const a = keyAction(e);
    const isBack = a === 'escape' || a === 'remoteBack';
    /* Esc closes the player (Settings · Controls lists it so); a remote's Back only does with "Back button closes
       the player" on this device, and otherwise hides the controls. */
    const leave = a === 'escape' || (a === 'remoteBack' && dev.backClosesPlayer);
    if (ask) {
      if (isBack) { e.preventDefault(); exit(); }
      return;
    }
    if (pauseScreen) {
      if (leave) { e.preventDefault(); exit(); return; }
      if (a || e.key.length === 1) { e.preventDefault(); setPauseScreen(false); poke(); }
      return;
    }
    if (!isBack) poke();
    if (!a) return;
    if (endShown && !isBack) return;
    e.preventDefault();
    switch (a) {
      case 'toggle': togglePlay(); break;
      case 'back': seekBy(-steps.back); break;
      case 'forward': seekBy(steps.forward); break;
      case 'volUp': setVolume((el().muted ? 0 : el().volume) + 0.05); break;
      case 'volDown': setVolume(el().volume - 0.05); break;
      case 'mute': toggleMute(); break;
      case 'fullscreen': toggleFullscreen(); break;
      case 'subtitles': toggleSubtitles(); break;
      case 'next': goNext(false); break;
      case 'skip':
        if (skipBtn) skipBtn.go();
        else if (upNextShown) goNext(true);
        break;
      case 'escape':
      case 'remoteBack':
        if (panel) { setPanel(null); poke(); }
        else if (leave) exit();
        else if (document.fullscreenElement) void document.exitFullscreen().catch(() => undefined);
        else if (!endShown) {
          window.clearTimeout(hideTimer.current);
          if (!m.paused) setActive(false);
        }
        break;
    }
  };
  useEffect(() => {
    const fn = (e: KeyboardEvent) => keyRef.current(e);
    window.addEventListener('keydown', fn);
    return () => window.removeEventListener('keydown', fn);
  }, []);

  /* ── Remote control from other Jellyfin apps ── */
  const remoteRef = useRef<{ playstate: (d: any) => void; general: (d: any) => void }>({ playstate: () => undefined, general: () => undefined });
  remoteRef.current = {
    playstate: (d: { Command?: string; SeekPositionTicks?: number }) => {
      switch (d?.Command) {
        case 'Pause': pause(); break;
        case 'Unpause': play(); break;
        case 'PlayPause': togglePlay(); break;
        case 'Stop': exit(); break;
        case 'Seek': if (d.SeekPositionTicks !== undefined) seekTo(ticksToSeconds(d.SeekPositionTicks)); break;
        case 'NextTrack': goNext(false); break;
        case 'PreviousTrack': goPrev(); break;
        case 'FastForward': seekBy(steps.forward); break;
        case 'Rewind': seekBy(-steps.back); break;
      }
    },
    general: (d: { Name?: string; Arguments?: Record<string, string> }) => {
      const args = d?.Arguments ?? {};
      switch (d?.Name) {
        case 'SetVolume': setVolume(Number(args.Volume ?? 100) / 100); break;
        case 'VolumeUp': setVolume(el().volume + 0.05); break;
        case 'VolumeDown': setVolume(el().volume - 0.05); break;
        case 'ToggleMute': toggleMute(); break;
        case 'Mute': el().muted = true; break;
        case 'Unmute': el().muted = false; break;
        case 'SetAudioStreamIndex': setAudio(Number(args.Index)); break;
        case 'SetSubtitleStreamIndex': setSubtitle(Number(args.Index)); break;
        case 'ToggleFullscreen': toggleFullscreen(); break;
        case 'SetMaxStreamingBitrate': {
          const b = Number(args.MaxBitrate);
          const q = b >= 80_000_000 ? '4k' : b >= 20_000_000 ? '1080p' : b >= 8_000_000 ? '720p' : b > 0 ? '480p' : 'auto';
          chooseQuality(q);
          break;
        }
        case 'DisplayMessage': {
          const text = [args.Header, args.Text].filter(Boolean).join(': ');
          if (text) toast(text, 'ok', Number(args.TimeoutMs) || 5000);
          break;
        }
      }
    }
  };
  useEffect(() => {
    const offs = [
      on('Playstate', (d) => remoteRef.current.playstate(d)),
      on('GeneralCommand', (d) => remoteRef.current.general(d))
    ];
    return () => offs.forEach((off) => off());
  }, []);

  /* ── Media Session (OS media keys, lock screen) ── */
  const msRef = useRef({ play, pause, seekBy, seekTo, goNext, goPrev });
  msRef.current = { play, pause, seekBy, seekTo, goNext, goPrev };
  useEffect(() => {
    const nav = navigator as Navigator & { mediaSession?: MediaSession };
    if (!nav.mediaSession || typeof MediaMetadata === 'undefined') return;
    holdSession();
    const art = primaryUrl(item, 512);
    try {
      nav.mediaSession.metadata = new MediaMetadata({
        title: item.Name,
        artist: isEpisode ? `${item.SeriesName ?? ''} · ${episodeTag(item)}` : item.AlbumArtist ?? item.Artists?.[0] ?? '',
        album: item.SeasonName ?? item.Album ?? '',
        artwork: art ? [{ src: art, sizes: '512x512' }] : []
      });
    } catch { /* unsupported */ }
    const set = (a: MediaSessionAction, fn: MediaSessionActionHandler | null) => { try { nav.mediaSession!.setActionHandler(a, fn); } catch { /* unsupported action */ } };
    set('play', () => msRef.current.play());
    set('pause', () => msRef.current.pause());
    set('seekbackward', (d) => msRef.current.seekBy(-(d.seekOffset ?? steps.back)));
    set('seekforward', (d) => msRef.current.seekBy(d.seekOffset ?? steps.forward));
    set('seekto', (d) => { if (d.seekTime !== undefined) msRef.current.seekTo(d.seekTime); });
    set('nexttrack', next ? () => msRef.current.goNext(false) : null);
    set('previoustrack', prevEp ? () => msRef.current.goPrev() : null);
    return releaseSession;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.Id, next?.Id, prevEp?.Id]);
  useEffect(() => {
    const nav = navigator as Navigator & { mediaSession?: MediaSession };
    if (!nav.mediaSession?.setPositionState || !duration) return;
    try { nav.mediaSession.setPositionState({ duration, position: Math.min(duration, m.time), playbackRate: m.rate || 1 }); } catch { /* ignore */ }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [Math.floor(m.time / 5), duration, m.rate]);

  /* ── Derived view state ── */
  const overlayOwnsScreen = upNextShown || endShown || pauseScreen || !!ask;
  const controlsVisible = !overlayOwnsScreen && !fatal && (isAudio || m.paused || active || panel !== null || scrubbing);
  const remaining = Math.max(0, duration - m.time);
  const fraction = duration ? m.time / duration : 0;
  const chapters = item.Chapters ?? [];
  const subtitlesOn = subIndex >= 0;
  const currentSub = subs.find((s) => s.Index === subIndex);
  const skipBtn: { label: string; go: () => void; pinned: boolean } | null = isPreroll
    ? { label: tr('Skip trailers'), go: skipTrailers, pinned: true }
    : skippable && skipMode(skippable.Type) === 'button'
      ? { label: skipLabel(skippable.Type), go: () => seekTo(ticksToSeconds(skippable.EndTicks)), pinned: t - ticksToSeconds(skippable.StartTicks) < 8 }
      : creditsSkip && outro
        ? { label: tr('Skip credits'), go: () => seekTo(ticksToSeconds(outro.EndTicks)), pinned: t - ticksToSeconds(outro.StartTicks) < 8 }
        : null;
  const autoCap = bitrateFor('auto');
  const ext = dev.externalPlayer && !isAudio ? externalApps() : [];

  const topName = isEpisode ? item.SeriesName ?? item.Name : isAudio ? item.Album ?? item.Name : item.Name;
  const topSub = isEpisode ? seasonEpisodeLong(item) : isAudio ? item.AlbumArtist ?? item.Artists?.[0] ?? '' : item.ProductionYear ? String(item.ProductionYear) : '';
  const overline = isEpisode
    ? [item.SeriesName, episodeTag(item)].filter(Boolean).join(' · ')
    : isAudio
      ? [item.AlbumArtist ?? item.Artists?.[0], item.Album].filter(Boolean).join(' · ')
      : [item.ProductionYear, ...(item.Genres ?? []).slice(0, 2)].filter(Boolean).join(' · ');
  const quickStatus = stream ? statusText(stream, isAudio, m.vw, m.vh) : '';
  const currentQuality = stream ? resLabel(m.vw || videoStream(stream.source)?.Width, m.vh || videoStream(stream.source)?.Height) : '';
  const canFindSubs = !isAudio && !!(user?.Policy?.IsAdministrator || user?.Policy?.EnableSubtitleManagement || me.isAdmin);
  const searchLang = user?.Configuration?.SubtitleLanguagePreference || 'eng';
  const art = isAudio ? primaryUrl(item, 800) : undefined;
  const volIcon = m.muted || m.volume === 0 ? 'mute' : m.volume < 0.5 ? 'volumeLow' : 'volume';

  useEffect(() => {
    setTileSettled(false);
    if (!endShown) return;
    const t = window.setTimeout(() => setTileSettled(true), 650);
    return () => window.clearTimeout(t);
  }, [endShown]);

  const videoStyle = endShown && tile
    ? { left: tile.left + 'px', top: tile.top + 'px', width: tile.width + 'px', height: tile.height + 'px' }
    : undefined;

  const rootCls = [
    'pl-root',
    controlsVisible ? '' : 'is-chrome-hidden',
    !controlsVisible && !overlayOwnsScreen && !isAudio ? 'is-idle' : '',
    overlayOwnsScreen ? 'is-overlay' : '',
    endShown ? 'is-end' : '',
    isAudio ? 'is-audio' : ''
  ].filter(Boolean).join(' ');

  const lastPointer = useRef('mouse');
  const swallowClick = useRef(false);
  /* Lifted subtitles clear the control block (300 px on the board; more when controls wrap). */
  const bottomRef = useRef<HTMLDivElement>(null);
  const [lift, setLift] = useState(300);
  useEffect(() => {
    const el = bottomRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(() => setLift(Math.max(300, Math.round(el.offsetHeight + 24))));
    ro.observe(el);
    return () => ro.disconnect();
  }, [endShown, upNextShown]);

  return (
    <div class={rootCls} style={{ '--sub-lift': lift + 'px' } as Record<string, string>}>
      {isAudio && (
        <div class="pl-audio" aria-hidden="true">
          {art && <div class="pl-audio-bg" style={{ backgroundImage: `url("${art}")` }} />}
          <div class="pl-audio-art">{art ? <img src={art} alt="" /> : <Icon name="music" size={64} />}</div>
        </div>
      )}

      <video
        ref={mediaRef}
        class={'pl-video' + (endShown ? ' is-tile' : '') + (endShown && tileSettled ? ' is-settled' : '') + (isAudio || soft ? ' is-hidden' : '')}
        style={videoStyle}
        playsInline
        preload="auto"
        aria-label={item.Name}
      />
      <div
        ref={softRef}
        class={'pl-video pl-soft' + (endShown ? ' is-tile' : '') + (endShown && tileSettled ? ' is-settled' : '') + (soft ? '' : ' is-hidden')}
        style={videoStyle}
        role={soft ? 'img' : undefined}
        aria-label={soft ? item.Name : undefined}
        aria-hidden={soft ? undefined : 'true'}
      />

      <div
        class="pl-hit"
        onPointerDown={(e) => {
          lastPointer.current = e.pointerType;
          /* A click that only closes an open menu shouldn't also pause. */
          swallowClick.current = !!document.querySelector('.popover');
        }}
        onClick={() => {
          if (swallowClick.current) { swallowClick.current = false; return; }
          if (panel) { setPanel(null); return; }
          if (lastPointer.current === 'touch' && !controlsVisible) { poke(); return; }
          togglePlay();
        }}
        onDblClick={() => { if (!isAudio) toggleFullscreen(); }}
      />

      {/* "Every video (for testing)": the usual player on the right of a bar that moves. */}
      {soft && dev.softPlayer === 'always' && !endShown && sessRef.current && (
        <Compare item={item} master={media()} opts={sessRef.current.opts} describe={(st, w, h) => statusText(st, false, w, h)} />
      )}

      {panel && <div class="pl-dim" aria-hidden="true" />}
      <div class="pl-grad-top pl-fade" aria-hidden="true" />
      <div class="pl-grad-bottom pl-fade" aria-hidden="true" />

      {subtitlesOn && !stream?.burnIn && loaded && (
        <SubtitleLayer
          subs={loaded}
          time={m.time - subOffset}
          style={subStyle}
          lifted={controlsVisible}
          hidden={endShown || pauseScreen || !!ask || (nativeCues && loaded.kind === 'vtt')}
          fit={dev.pictureFit}
          videoWidth={m.vw || videoStream(source)?.Width || 0}
          videoHeight={m.vh || videoStream(source)?.Height || 0}
        />
      )}

      {!fatal && !endShown && (!stream || busy || (m.waiting && !m.paused)) && <div class="pl-spinner spinner" aria-label={tr('Loading')} />}

      {!endShown && !upNextShown && (
        <header class="pl-top">
          <div class="pl-top-left pl-fade">
            <BackButton onClick={exit} />
            <div class="pl-top-titles">
              <span class="pl-top-name">{topName}</span>
              {topSub && <span class="pl-top-sub">{topSub}</span>}
            </div>
          </div>
          <div class="pl-top-right pl-fade">
            {quickStatus && (
              <span class={'status-pill' + (stream?.kind === 'Transcoding' ? ' is-pending' : '')} title={stream ? tr(stream.kind) : undefined}>
                <span class="pip" />{quickStatus}
              </span>
            )}
            <WatchTogether itemId={item.Id} position={() => sess()?.position() ?? 0} />
            <CastMenu
              itemId={item.Id}
              position={() => sess()?.position() ?? 0}
              onCast={(id) => { applyCastQuality(id); sess()?.stop(); back('/'); }}
              external={ext.length ? {
                apps: ext,
                open: (app) => { pause(); openExternal(app, item, stream?.source ?? source); }
              } : undefined}
            />
          </div>
        </header>
      )}

      {!endShown && !upNextShown && (
        <div ref={bottomRef} class="pl-bottom">
          <div class="pl-info">
            <div class="pl-titles pl-fade">
              {overline && <span class="overline">{overline}</span>}
              <h1 class="pl-h1">{item.Name}</h1>
              {duration > 0 && <span class="pl-ends">{endsAt(remaining, m.rate)} · {timeLeft(Math.ceil(remaining / 60) * 60)}</span>}
            </div>
            {skipBtn && (
              <button
                type="button"
                class={'btn btn-primary is-compact pl-skip' + (skipBtn.pinned ? '' : ' pl-fade')}
                onClick={skipBtn.go}
              >
                {skipBtn.label}
                <Icon name="skip" size={20} />
              </button>
            )}
          </div>

          <div class="pl-fade">
            <Scrubber
              item={item}
              time={m.time}
              duration={duration}
              buffered={m.buffered}
              chapters={chapters}
              segments={segments}
              mediaSourceId={source?.Id}
              onSeek={seekTo}
              onScrub={setScrubbing}
              onDrag={setDragAt}
              chapterImage={(i) => chapters[i]?.ImageTag ? chapterImageUrl(item.Id, i, chapters[i].ImageTag!, 320) : undefined}
            />
          </div>
          <div class="pl-times pl-fade">
            <span>{timecode(dragAt ?? m.time)}</span>
            <span>{timecode(duration)}</span>
          </div>

          <div class="pl-controls pl-fade">
            <div class="pl-left">
              <button type="button" class="btn player-btn is-primary" aria-label={m.paused ? tr('Play') : tr('Pause')} onClick={togglePlay}>
                <Icon name={m.paused ? 'play' : 'pause'} size={22} />
              </button>
              <PBtn label={tr('Back {n} seconds', { n: steps.back })} onClick={() => seekBy(-steps.back)}>
                <SeekIcon dir="back" seconds={steps.back} />
              </PBtn>
              <PBtn label={tr('Forward {n} seconds', { n: steps.forward })} onClick={() => seekBy(steps.forward)}>
                <SeekIcon dir="forward" seconds={steps.forward} />
              </PBtn>
              <PBtn label={isEpisode ? tr('Next episode') : tr('Next')} icon="nextEpisode" onClick={() => goNext(false)} hidden={!next} />
              <div class="pl-volume">
                <PBtn label={m.muted || m.volume === 0 ? tr('Unmute') : tr('Mute')} icon={volIcon} onClick={toggleMute} />
                <VolumeSlider value={m.volume} muted={m.muted} onChange={setVolume} />
              </div>
            </div>
            <div class="pl-right">
              {!isAudio && (
                <PBtn
                  label={subtitlesOn && currentSub ? tr('Subtitles: {track}', { track: subtitleTitle(currentSub) }) : tr('Subtitles: Off')}
                  onClick={() => openTracks('subtitles')}
                  expanded={tracksTab === 'subtitles'}
                >
                  <Icon name="subtitles" size={22} />
                  {subtitlesOn && tracksTab !== 'subtitles' && <span class="dot" aria-hidden="true" />}
                </PBtn>
              )}
              <PBtn label={tr('Audio track')} icon="audio" onClick={() => openTracks('audio')} expanded={tracksTab === 'audio'} hidden={isAudio} />
              <PBtn
                label={tr('Chapters')}
                icon="chapters"
                onClick={() => setPanel((p) => (p?.kind === 'chapters' ? null : { kind: 'chapters' }))}
                expanded={panel?.kind === 'chapters'}
                hidden={isAudio || !chapters.length}
              />
              <PBtn
                label={tr('Episodes')}
                icon="episodes"
                onClick={() => setPanel((p) => (p?.kind === 'episodes' ? null : { kind: 'episodes' }))}
                expanded={panel?.kind === 'episodes'}
                hidden={!isEpisode}
              />
              <PBtn
                label={tr('Playback settings')}
                icon="sliders"
                onClick={() => openTracks('quality')}
                expanded={tracksTab === 'quality' || tracksTab === 'speed'}
              />
              <PBtn label={document.pictureInPictureElement ? tr('Exit picture in picture') : tr('Picture in picture')} icon="pip" onClick={togglePip} hidden={!pipSupported} />
              <PBtn label={fullscreen ? tr('Exit full screen') : tr('Full screen')} icon={fullscreen ? 'exitFullscreen' : 'fullscreen'} onClick={toggleFullscreen} hidden={isAudio} />
            </div>
          </div>
        </div>
      )}

      {panel?.kind === 'tracks' && controlsVisible && (
        <TracksPanel
          tab={panel.tab}
          onTab={(tab) => setPanel({ kind: 'tracks', tab })}
          itemId={item.Id}
          subtitles={subs}
          subtitleIndex={subIndex}
          onSubtitle={setSubtitle}
          audio={auds}
          audioIndex={stream?.audioIndex ?? optsRef.current.audioIndex}
          onAudio={setAudio}
          quality={quality}
          currentQuality={currentQuality}
          autoLimit={autoCap < AUTO_BITRATE ? bitrateLabel(autoCap) : undefined}
          onQuality={chooseQuality}
          speed={m.rate}
          onSpeed={chooseSpeed}
          subPrefs={{ size: SIZE_TO_PREFS[subStyle.size], lift: subStyle.position === 'lifted' }}
          onSubPrefs={updateSubPrefs}
          offset={subOffset}
          onOffset={setSubOffset}
          canFindSubtitles={canFindSubs}
          searchLanguage={searchLang}
          onDownloaded={afterSubtitleDownload}
          busy={busy}
        />
      )}
      {panel?.kind === 'chapters' && controlsVisible && (
        <ChaptersPanel item={item} chapters={chapters} time={m.time} mediaSourceId={source?.Id} onSeek={(s) => { seekTo(s); }} />
      )}
      {panel?.kind === 'episodes' && controlsVisible && (
        <EpisodesPanel current={item} episodes={episodes} onPlay={(ep) => { setPanel(null); playItem(ep); }} />
      )}

      {upNextShown && next && (
        <>
          <UpNext
            next={next}
            remaining={upCount}
            total={prefs.upNextSeconds}
            onPlay={() => goNext(true)}
            onDismiss={() => { setUpNext('dismissed'); poke(); }}
          />
          <EdgeProgress fraction={fraction} />
        </>
      )}

      {ask && next && (
        <StillWatching
          next={next}
          episodes={ask.episodes}
          minutes={ask.minutes}
          byTime={ask.byTime}
          onContinue={() => { presence.episodes = 0; presence.since = Date.now(); setAsk(null); goNext(true); }}
          onLeave={exit}
        />
      )}

      {pauseScreen && <PausedOverlay item={item} rating={item.OfficialRating ?? seriesRating} fraction={fraction} onWake={() => { setPauseScreen(false); poke(); }} />}

      {endShown && (
        <EndOfMovie
          item={item}
          picks={picks}
          creditsLeft={remaining}
          fraction={fraction}
          remaining={endCount}
          total={prefs.moviePicksSeconds}
          onWatchCredits={() => {
            setEndFlow('dismissed');
            if (m.ended) exit();
            else poke();
          }}
          onBack={exit}
          onPlay={playItem}
          onTileRect={setTile}
        />
      )}

      {fatal && <ErrorPanel {...fatal} onRetry={retry} onBack={exit} />}
      <span class="sr-only" aria-live="polite">{m.paused && stream ? tr('Paused') : ''}</span>
      {!stream && !fatal && <span class="sr-only">{tr('Loading {title}', { title: item.Name ?? '' })}</span>}
    </div>
  );
}
