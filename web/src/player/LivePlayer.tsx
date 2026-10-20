import { useEffect, useRef, useState } from 'preact/hooks';
import { ApiError } from '../api/client';
import { getPrograms, type Program } from '../api/livetv';
import type { BaseItem } from '../api/types';
import { clock, duration, episodeTag } from '../lib/format';
import { t } from '../lib/i18n';
import { back, href, navigate } from '../lib/router';
import { safeLocal, saveLocal } from '../lib/store';
import { Icon } from '../ui/icons';
import { toast } from '../ui/overlay';
import {
  categoryOf, endMs, fraction, isAiring, isFavorite, noteWatched, previousChannelId, programTitle, recState, startMs, toggleRecord,
  useChannels, useNow, useTimers, type LiveChannel
} from '../screens/livetv/data';
import { ChannelLogo, HeartIcon, LtIcon, RecDot } from '../screens/livetv/parts';
import '../screens/livetv/livetv.css';
import type { ProfileOptions } from './device-profile';
import { keyAction } from './keys';
import { closesSettled } from './live-profile';
import { PlaybackSession, audioStreams, subtitleStreams, videoStream, type ActiveStream, type QualityId } from './playback';
import { VolumeSlider } from './Scrubber';
import { CastMenu } from './TopMenus';
import { TracksPanel, type TracksTab } from './TracksPanel';
import './live.css';

/* Player · Live TV (board "Player · Live TV"): a TV channel in Seedy's player. The live stream opens through
   PlaybackInfo (AutoOpenLiveStream) and closes when the channel changes or the player goes. The channel's logo and
   number on top with the stream line; LIVE, the programme and "Next at…"; the programme's progress instead of a
   seek bar; CH down / CH up, Last channel; the Channels panel (favourites or all, ↑ ↓ browse, Enter switch, 0–9
   types a number); Record. */

type Panel = { kind: 'channels' } | { kind: 'tracks'; tab: TracksTab } | null;

interface MediaState { paused: boolean; waiting: boolean; volume: number; muted: boolean; vw: number; vh: number }

export function LivePlayer({ channel: initial, profile, bitrateFor }: {
  channel: BaseItem; profile: ProfileOptions; bitrateFor: (q: QualityId) => number;
}) {
  const { list } = useChannels();
  const { timers } = useTimers();
  const now = useNow(5_000);
  const [chId, setChId] = useState(initial.Id);
  const [prevId, setPrevId] = useState<string | null>(() => {
    const p = previousChannelId();
    return p && p !== initial.Id ? p : null;
  });
  const channel = list?.find((c) => c.Id === chId);
  const chRef = useRef<BaseItem>(initial);
  chRef.current = channel ?? (chId === initial.Id ? initial : { Id: chId, Name: '', Type: 'TvChannel', MediaType: 'Video' } as BaseItem);

  const mediaRef = useRef<HTMLVideoElement>(null);
  const sessRef = useRef<PlaybackSession | null>(null);
  const [stream, setStream] = useState<ActiveStream | null>(null);
  const [fatal, setFatal] = useState<{ title: string; text: string } | null>(null);
  const [attempt, setAttempt] = useState(0);
  const [quality, setQuality] = useState<QualityId>('auto');
  const [audioIndex, setAudioIndex] = useState<number | undefined>(undefined);
  const [subIndex, setSubIndex] = useState(-1);
  const [rate, setRate] = useState(1);
  const [m, setM] = useState<MediaState>({
    paused: true, waiting: true, volume: safeLocal<number>('seedy.volume', 1), muted: safeLocal('seedy.muted', false), vw: 0, vh: 0
  });
  const [panel, setPanel] = useState<Panel>(null);
  const [active, setActive] = useState(true);
  const [fullscreen, setFullscreen] = useState(!!document.fullscreenElement);

  /* ── One session per channel ── */
  useEffect(() => {
    const el = mediaRef.current!;
    setStream(null);
    setFatal(null);
    setM((p) => ({ ...p, waiting: true, vw: 0, vh: 0 }));
    el.volume = Math.max(0, Math.min(1, m.volume));
    el.muted = m.muted;
    const sess = new PlaybackSession({ ...chRef.current, Id: chId, Type: 'TvChannel', MediaType: 'Video' }, el, {
      onStream: (st) => setStream(st),
      onFatal: (code) => {
        sess.stop({ failed: true });
        setFatal(liveError(code));
      }
    }, { subtitleIndex: subIndex, audioIndex, maxBitrate: bitrateFor(quality), startSeconds: 0, profile, burnSubtitle: subIndex >= 0 });
    sessRef.current = sess;
    let retries = 0;
    const busy = (e: unknown) => e instanceof ApiError && (e.status === 409 || e.status === 500 || /limit|host to play|conflict/i.test(e.message));
    /* A dead playlist stream keeps failing on the server without hls.js giving up: 25 s without a picture is an error. */
    let watchdog = 0;
    const onPlaying = () => window.clearTimeout(watchdog);
    el.addEventListener('playing', onPlaying);
    const open = () => closesSettled().then(() => sess.open()).then((st) => {
      if (!st) return;
      sess.play();
      noteWatched(chId);
      watchdog = window.setTimeout(() => {
        if (sess.stopped || !el.paused && el.readyState >= 3) return;
        sess.stop({ failed: true });
        setFatal(liveError('network'));
      }, 25_000);
    }, (e: unknown) => {
      // The last channel's tuner may still be closing on the server: try again a moment later (three times).
      if (busy(e) && retries < 3 && !sess.stopped) {
        retries++;
        window.setTimeout(() => { if (!sess.stopped) void open(); }, 2000);
        return;
      }
      sess.stop({ failed: true });
      if (e instanceof ApiError) setFatal(liveError(e.status === 409 || /limit|host to play|conflict/i.test(e.message) ? 'busy' : 'open'));
      else setFatal(liveError((e as { code?: string })?.code ?? 'open'));
    });
    void open();
    // The address follows the channel, so a reload lands on it.
    if (!window.location.pathname.endsWith('/play/' + chId)) {
      try { window.history.replaceState(window.history.state, '', href('/play/' + chId)); } catch { /* fine */ }
    }
    const onHide = () => sess.stop();
    window.addEventListener('pagehide', onHide);
    pokeRef.current();
    return () => {
      window.clearTimeout(watchdog);
      el.removeEventListener('playing', onPlaying);
      window.removeEventListener('pagehide', onHide);
      sess.release();
      if (sessRef.current === sess) sessRef.current = null;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [chId, attempt]);

  /* ── Media element → state ── */
  useEffect(() => {
    const el = mediaRef.current!;
    const read = (patch: Partial<MediaState> = {}) => setM((p) => ({
      ...p, paused: el.paused, volume: el.volume, muted: el.muted, vw: el.videoWidth || p.vw, vh: el.videoHeight || p.vh, ...patch
    }));
    const report = (ev: string) => sessRef.current?.report('progress', ev);
    const hs: [string, () => void][] = [
      ['play', () => { read(); report('unpause'); }],
      ['pause', () => { read(); report('pause'); }],
      ['playing', () => read({ waiting: false })],
      ['waiting', () => read({ waiting: true })],
      ['canplay', () => read({ waiting: false })],
      ['loadedmetadata', () => read()],
      ['resize', () => read()],
      ['volumechange', () => { read(); saveLocal('seedy.volume', el.volume); saveLocal('seedy.muted', el.muted); report('volumechange'); }]
    ];
    hs.forEach(([ev, fn]) => el.addEventListener(ev, fn));
    return () => hs.forEach(([ev, fn]) => el.removeEventListener(ev, fn));
  }, []);

  /* ── What's on: this channel's programmes from now ── */
  const [progs, setProgs] = useState<Program[]>([]);
  useEffect(() => {
    let alive = true;
    setProgs([]);
    const load = () => getPrograms({ ChannelIds: [chId], MinEndDate: new Date().toISOString(), Limit: 4 })
      .then((r) => { if (alive) setProgs(r.Items ?? []); }, () => undefined);
    void load();
    const id = window.setInterval(load, 60_000);
    return () => { alive = false; window.clearInterval(id); };
  }, [chId]);
  const cur = progs.find((p) => isAiring(p, now)) ?? (isAiring(channel?.CurrentProgram, now) ? channel!.CurrentProgram : undefined);
  const next = progs.find((p) => startMs(p) >= (cur ? endMs(cur) - 60_000 : now) && p.Id !== cur?.Id);
  const rec = recState(cur, timers);

  /* ── Channel list (favourites or all) ── */
  const all = list ?? [];
  const favs = all.filter(isFavorite);
  const [scope, setScope] = useState<'fav' | 'all'>(() => (safeLocal<string>('seedy.device.liveScope', 'fav') === 'all' ? 'all' : 'fav'));
  const scoped = scope === 'fav' && favs.length ? favs : all;
  const order = scoped.some((c) => c.Id === chId) ? scoped : all;
  const switchTo = (id?: string) => {
    if (!id || id === chId) return;
    setPrevId(chId);
    setFatal(null);
    setChId(id);
  };
  const step = (dir: 1 | -1) => {
    if (!order.length) return;
    const i = order.findIndex((c) => c.Id === chId);
    switchTo(order[(i + dir + order.length) % order.length].Id);
  };
  const prev = prevId ? all.find((c) => c.Id === prevId) : undefined;

  /* ── Typing a channel number ── */
  const [typed, setTyped] = useState('');
  const typedTimer = useRef(0);
  const commitTyped = (value: string) => {
    window.clearTimeout(typedTimer.current);
    setTyped('');
    if (!value) return;
    const c = all.find((x) => x.num === value) ?? all.find((x) => parseFloat(x.num) === parseFloat(value));
    if (c) switchTo(c.Id);
    else toast(t('There is no channel {n}', { n: value }), 'error');
  };
  const typeDigit = (d: string) => {
    const v = (typed + d).slice(0, 5);
    setTyped(v);
    window.clearTimeout(typedTimer.current);
    typedTimer.current = window.setTimeout(() => commitTyped(v), 1500);
  };

  /* ── Controls show on activity and hide after 3 s while playing ── */
  const hideTimer = useRef(0);
  const poke = () => {
    window.clearTimeout(hideTimer.current);
    setActive(true);
    hideTimer.current = window.setTimeout(() => {
      if (!document.querySelector('.popover')) setActive(false);
    }, 3000);
  };
  const pokeRef = useRef(poke);
  pokeRef.current = poke;
  useEffect(() => {
    const fn = () => pokeRef.current();
    window.addEventListener('pointermove', fn, { passive: true });
    window.addEventListener('pointerdown', fn, { passive: true });
    poke();
    const fs = () => setFullscreen(!!document.fullscreenElement);
    document.addEventListener('fullscreenchange', fs);
    return () => {
      window.removeEventListener('pointermove', fn);
      window.removeEventListener('pointerdown', fn);
      document.removeEventListener('fullscreenchange', fs);
      window.clearTimeout(hideTimer.current);
      window.clearTimeout(typedTimer.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  /* ── Actions ── */
  const el = () => mediaRef.current!;
  const togglePlay = () => {
    const e = el();
    if (e.paused) sessRef.current?.play(); else e.pause();
  };
  const setVolume = (v: number) => { const e = el(); e.volume = Math.max(0, Math.min(1, v)); e.muted = v <= 0; };
  const toggleMute = () => {
    const e = el();
    if (e.muted || e.volume === 0) { e.muted = false; if (e.volume === 0) e.volume = 0.5; } else e.muted = true;
  };
  const toggleFullscreen = () => {
    if (document.fullscreenElement) { void document.exitFullscreen().catch(() => undefined); return; }
    void document.documentElement.requestFullscreen?.().catch(() => undefined);
  };
  const pipSupported = (document as Document & { pictureInPictureEnabled?: boolean }).pictureInPictureEnabled === true;
  const togglePip = () => {
    if (document.pictureInPictureElement) void document.exitPictureInPicture().catch(() => undefined);
    else void el().requestPictureInPicture?.().catch(() => toast(t("Picture in picture isn't available"), 'error'));
  };
  const exit = () => {
    sessRef.current?.stop();
    if (document.fullscreenElement) void document.exitFullscreen().catch(() => undefined);
    back('/livetv');
  };
  const restart = (patch: { audioIndex?: number; subtitleIndex?: number; maxBitrate?: number }) => {
    const s = sessRef.current;
    if (!s) return;
    void s.change({ ...patch, startSeconds: 0, burnSubtitle: (patch.subtitleIndex ?? subIndex) >= 0 }).then((st) => { if (st) s.play(); }, () => setFatal(liveError('open')));
  };

  /* ── Panel: keyboard focus in the channel list ── */
  const [focusId, setFocusId] = useState<string | null>(null);
  const listRef = useRef<HTMLDivElement>(null);
  useEffect(() => { if (panel?.kind === 'channels') setFocusId(chId); }, [panel?.kind]);
  useEffect(() => {
    listRef.current?.querySelector<HTMLElement>(`[data-id="${focusId}"]`)?.scrollIntoView({ block: 'nearest' });
  }, [focusId]);
  const [nextOf, setNextOf] = useState<Record<string, Program | null>>({});
  useEffect(() => {
    if (!focusId || panel?.kind !== 'channels' || focusId in nextOf) return;
    const tm = window.setTimeout(() => {
      getPrograms({ ChannelIds: [focusId], MinStartDate: new Date().toISOString(), Limit: 1 })
        .then((r) => setNextOf((n) => ({ ...n, [focusId]: r.Items?.[0] ?? null })), () => setNextOf((n) => ({ ...n, [focusId]: null })));
    }, 200);
    return () => window.clearTimeout(tm);
  }, [focusId, panel?.kind]);

  /* ── Keyboard ── */
  const keyRef = useRef<(e: KeyboardEvent) => void>(() => undefined);
  keyRef.current = (e: KeyboardEvent) => {
    if (e.ctrlKey || e.metaKey || e.altKey || e.defaultPrevented) return;
    if (document.querySelector('.scrim')) return;
    const target = e.target as HTMLElement | null;
    const typing = !!target && (/^(TEXTAREA|SELECT)$/.test(target.tagName) || (target.tagName === 'INPUT' && (target as HTMLInputElement).type !== 'range'));
    if (typing) return;
    poke();
    if (/^[0-9]$/.test(e.key)) { e.preventDefault(); typeDigit(e.key); return; }
    if (typed && e.key === 'Enter') { e.preventDefault(); commitTyped(typed); return; }
    if (panel?.kind === 'channels') {
      const i = order.findIndex((c) => c.Id === focusId);
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        e.preventDefault();
        const n = order[Math.max(0, Math.min(order.length - 1, (i < 0 ? 0 : i) + (e.key === 'ArrowDown' ? 1 : -1)))];
        if (n) setFocusId(n.Id);
        return;
      }
      if (e.key === 'Enter' && focusId && !(target?.tagName === 'BUTTON' && !target.closest('.lt-pl-list'))) {
        e.preventDefault();
        switchTo(focusId);
        return;
      }
    }
    switch (e.key) {
      case 'PageUp': case 'ChannelUp': e.preventDefault(); step(1); return;
      case 'PageDown': case 'ChannelDown': e.preventDefault(); step(-1); return;
      case 'r': case 'R': e.preventDefault(); if (cur) void toggleRecord(cur); return;
      case 'g': case 'G': e.preventDefault(); setPanel((p) => (p?.kind === 'channels' ? null : { kind: 'channels' })); return;
      case 'Backspace': if (prev) { e.preventDefault(); switchTo(prev.Id); } return;
    }
    const a = keyAction(e);
    if (!a) return;
    switch (a) {
      case 'toggle': e.preventDefault(); togglePlay(); break;
      case 'volUp': e.preventDefault(); setVolume((el().muted ? 0 : el().volume) + 0.05); break;
      case 'volDown': e.preventDefault(); setVolume(el().volume - 0.05); break;
      case 'mute': e.preventDefault(); toggleMute(); break;
      case 'fullscreen': e.preventDefault(); toggleFullscreen(); break;
      case 'escape':
      case 'remoteBack':
        e.preventDefault();
        if (typed) { window.clearTimeout(typedTimer.current); setTyped(''); }
        else if (panel) setPanel(null);
        else exit();
        break;
    }
  };
  useEffect(() => {
    const fn = (e: KeyboardEvent) => keyRef.current(e);
    window.addEventListener('keydown', fn);
    return () => window.removeEventListener('keydown', fn);
  }, []);

  /* ── OS media controls ── */
  useEffect(() => {
    const nav = navigator as Navigator & { mediaSession?: MediaSession };
    if (!nav.mediaSession || typeof MediaMetadata === 'undefined') return;
    try {
      nav.mediaSession.metadata = new MediaMetadata({ title: cur ? programTitle(cur) : channel?.label ?? '', artist: channel?.label ?? '' });
      nav.mediaSession.setActionHandler('nexttrack', () => step(1));
      nav.mediaSession.setActionHandler('previoustrack', () => step(-1));
    } catch { /* unsupported */ }
    return () => {
      try {
        nav.mediaSession!.setActionHandler('nexttrack', null);
        nav.mediaSession!.setActionHandler('previoustrack', null);
        nav.mediaSession!.metadata = null;
      } catch { /* unsupported */ }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cur?.Id, chId, order.length]);

  /* ── View ── */
  const channelsOpen = panel?.kind === 'channels';
  const visible = !fatal ? active || m.paused || panel !== null || !stream : true;
  const status = stream ? streamLine(stream, m.vw, m.vh) : '';
  const cat = cur ? categoryOf(cur) : '';
  const name = channel?.label ?? chRef.current.Name ?? '';
  const src = stream?.source;
  const subs = subtitleStreams(src);
  const auds = audioStreams(src);
  const leftMin = cur ? Math.max(1, Math.ceil((endMs(cur) - now) / 60_000)) : 0;

  return (
    <div class={'pl-root is-live' + (visible ? '' : ' is-chrome-hidden is-idle')}>
      <video ref={mediaRef} class="pl-video" playsInline preload="auto" aria-label={name} />
      <div class="pl-hit" onClick={() => { if (panel) { setPanel(null); return; } togglePlay(); }} onDblClick={toggleFullscreen} />
      <div class="pl-dim" aria-hidden="true" />
      <div class="pl-grad-top pl-fade" aria-hidden="true" />
      <div class="pl-grad-bottom pl-fade" aria-hidden="true" />
      {!fatal && (!stream || m.waiting) && <div class="pl-spinner spinner" aria-label={t('Loading')} />}

      <header class="pl-top">
        <div class="pl-top-left pl-fade">
          <button type="button" class="btn player-btn is-soft" aria-label={t('Back')} onClick={exit}><Icon name="arrowLeft" size={22} /></button>
          <ChannelLogo channel={channel ?? (chRef.current as LiveChannel)} size={44} radius={12} />
          <div class="pl-top-titles">
            <span class="pl-top-name">{name}</span>
            <span class="pl-top-sub">{[channel ? t('Channel {n}', { n: channel.num }) : '', channel?.hd].filter(Boolean).join(' · ')}</span>
          </div>
        </div>
        <div class="pl-top-right pl-fade">
          {status && !fatal && <span class={'status-pill lt-pl-status' + (stream?.kind === 'Transcoding' ? ' is-pending' : '')}><span class="pip" />{status}</span>}
          <CastMenu itemId={chId} position={() => 0} onCast={() => { sessRef.current?.stop(); back('/livetv'); }} />
        </div>
      </header>

      {typed && (
        <div class="lt-pl-typed" role="status" aria-live="assertive">
          <span class="lt-pl-typed-label">{t('Channel')}</span>
          <span class="lt-pl-typed-num">{typed}<span class="lt-pl-caret" /></span>
          <span class="lt-pl-typed-name">{all.find((c) => c.num === typed)?.label ?? ''}</span>
        </div>
      )}

      {fatal && (
        <div class="pl-error lt-pl-error" role="alert">
          <div class="empty pl-error-box">
            <span class="empty-icon"><Icon name="alert" size={26} /></span>
            <div class="empty-body">
              <span class="empty-title">{fatal.title}</span>
              <span class="empty-text">{fatal.text}</span>
              <div class="pl-error-actions">
                <button type="button" class="btn btn-primary is-sm" onClick={() => { setFatal(null); setAttempt((a) => a + 1); }}><Icon name="refresh" size={18} />{t('Try again')}</button>
                {order.length > 1 && <button type="button" class="btn btn-ghost is-light" onClick={() => step(1)}>{t('Next channel')}</button>}
                <button type="button" class="btn btn-ghost is-light" onClick={exit}>{t('Back')}</button>
              </div>
            </div>
          </div>
        </div>
      )}

      {channelsOpen && (
        <aside class="lt-pl-panel" aria-labelledby="lt-pl-h">
          <div class="lt-pl-head">
            <h2 id="lt-pl-h" class="lt-pl-title">{t('Channels')}</h2>
            <button type="button" class="lt-pl-scope" aria-haspopup="listbox" onClick={() => {
              const nextScope = scope === 'fav' ? 'all' : 'fav';
              setScope(nextScope);
              saveLocal('seedy.device.liveScope', nextScope);
            }} aria-label={scope === 'fav' && favs.length ? t('Showing favorite channels. Show all channels') : t('Showing all channels. Show favorite channels')}>
              {scope === 'fav' && favs.length ? <><HeartIcon size={14} filled />{t('Favorites')}</> : t('All channels')}
              <Icon name="chevronDown" size={14} stroke={2.4} class="nb-chev" />
            </button>
            <button type="button" class="lt-pl-icon" aria-label={t('Open full guide')} onClick={() => { sessRef.current?.stop(); navigate('/livetv/guide'); }}><LtIcon name="guide" size={17} /></button>
            <button type="button" class="lt-pl-icon is-bare" aria-label={t('Close channel list')} onClick={() => setPanel(null)}><LtIcon name="close" size={17} stroke={2.4} /></button>
          </div>
          <div ref={listRef} role="listbox" aria-label={scope === 'fav' && favs.length ? t('Favorite channels') : t('All channels')} class="lt-pl-list" aria-activedescendant={focusId ? 'lt-pl-' + focusId : undefined}>
            {scoped.map((c) => {
              const p = isAiring(c.CurrentProgram, now) ? c.CurrentProgram : undefined;
              const watching = c.Id === chId;
              const focused = c.Id === focusId;
              const nx = focused ? nextOf[c.Id] : undefined;
              return (
                <div
                  key={c.Id}
                  id={'lt-pl-' + c.Id}
                  data-id={c.Id}
                  role="option"
                  aria-selected={focused ? 'true' : 'false'}
                  class={'lt-pl-opt' + (watching ? ' is-watching' : '') + (focused ? ' is-focused' : '')}
                  onClick={() => { setFocusId(c.Id); switchTo(c.Id); }}
                  onMouseEnter={() => setFocusId(c.Id)}
                >
                  <div class="lt-pl-row">
                    <span class="lt-pl-num">{c.num}</span>
                    <ChannelLogo channel={c} size={34} radius={9} />
                    <span class="lt-pl-text">
                      <span class="lt-pl-name">
                        <span class="lt-pl-cname">{c.label}</span>
                        {watching && <span class="lt-pl-watching">{t('WATCHING')}</span>}
                        {recState(p, timers) === 'now' && <span class="lt-pl-rec" title={t('Recording')} />}
                      </span>
                      <span class="lt-pl-prog">{p ? programTitle(p) : t('No guide information')}</span>
                    </span>
                    {p && <span class="lt-pl-left">{duration((endMs(p) - now) / 1000)}</span>}
                  </div>
                  <div class="lt-pl-bar"><i style={{ width: (fraction(p, now) * 100).toFixed(1) + '%' }} /></div>
                  {focused && nx && <span class="lt-pl-next">{t('Next')} · <b>{clock(new Date(startMs(nx)))} {programTitle(nx)}</b></span>}
                </div>
              );
            })}
          </div>
          <div class="lt-pl-keys">
            <span><b>↑ ↓</b> {t('browse')}</span><span><b>Enter</b> {t('switch')}</span><span><b>0–9</b> {t('channel number')}</span>
          </div>
        </aside>
      )}

      {panel?.kind === 'tracks' && (
        <TracksPanel
          tab={panel.tab}
          onTab={(tab) => setPanel({ kind: 'tracks', tab })}
          itemId={chId}
          subtitles={subs}
          subtitleIndex={subIndex}
          onSubtitle={(i) => { setSubIndex(i); restart({ subtitleIndex: i }); }}
          audio={auds}
          audioIndex={stream?.audioIndex ?? audioIndex}
          onAudio={(i) => { setAudioIndex(i); restart({ audioIndex: i }); }}
          quality={quality}
          currentQuality={resLabel(m.vw || videoStream(src)?.Width, m.vh || videoStream(src)?.Height)}
          onQuality={(q) => { setQuality(q); restart({ maxBitrate: bitrateFor(q) }); }}
          speed={rate}
          onSpeed={(r) => { el().playbackRate = r; setRate(r); }}
          subPrefs={{ size: 'M', lift: true }}
          onSubPrefs={() => undefined}
          offset={0}
          onOffset={() => undefined}
          canFindSubtitles={false}
          searchLanguage="eng"
          onDownloaded={() => undefined}
        />
      )}

      <div class="pl-bottom lt-pl-bottom">
        <div class="pl-info pl-fade">
          <div class="pl-titles">
            <span class="lt-pl-over">
              <span class="lt-live lt-pl-live"><span class="lt-live-dot" />{t('LIVE')}</span>
              {cat && <span class="lt-pl-cat">{cat}</span>}
            </span>
            <h1 class="pl-h1 lt-pl-h1">{cur ? (cur.IsSeries && cur.IndexNumber != null ? `${cur.Name} · ${episodeTag(cur)}` : cur.Name) : name}</h1>
            {next
              ? <span class="lt-pl-nextline">{t('Next at {time}', { time: clock(new Date(startMs(next))) })} · <b>{programTitle(next)}</b></span>
              : !cur && <span class="lt-pl-nextline">{t('No guide information')}</span>}
          </div>
        </div>

        {cur && (
          <>
            <div class="lt-pl-progress pl-fade" role="progressbar" aria-label={t('Programme progress')}
              aria-valuemin={0} aria-valuemax={Math.round((endMs(cur) - startMs(cur)) / 60_000)} aria-valuenow={Math.round((now - startMs(cur)) / 60_000)}
              aria-valuetext={t('{done} of {total} minutes, {left} minutes left', { done: Math.round((now - startMs(cur)) / 60_000), total: Math.round((endMs(cur) - startMs(cur)) / 60_000), left: leftMin })}>
              <div class="lt-pl-fill" style={{ width: (fraction(cur, now) * 100).toFixed(2) + '%' }} />
              <span aria-hidden="true" class="lt-pl-dot" style={{ left: (fraction(cur, now) * 100).toFixed(2) + '%' }} />
            </div>
            <div class="pl-times pl-fade">
              <span>{clock(new Date(startMs(cur)))}</span>
              <span class="lt-pl-left2">{t('{time} left', { time: duration((endMs(cur) - now) / 1000) })} · {t('ends {time}', { time: clock(new Date(endMs(cur))) })}</span>
            </div>
          </>
        )}

        <div class="pl-controls pl-fade">
          <div class="pl-left">
            <button type="button" class="btn player-btn is-primary" aria-label={m.paused ? t('Play') : t('Pause')} onClick={togglePlay}>
              <Icon name={m.paused ? 'play' : 'pause'} size={22} />
            </button>
            <button type="button" class="btn player-btn lt-pl-ch" aria-label={t('Previous channel in list')} onClick={() => step(-1)} disabled={order.length < 2}>
              <LtIcon name="chevronDown" size={20} stroke={2.4} />{t('CH')}
            </button>
            <button type="button" class="btn player-btn lt-pl-ch" aria-label={t('Next channel in list')} onClick={() => step(1)} disabled={order.length < 2}>
              <LtIcon name="chevronUp" size={20} stroke={2.4} />{t('CH')}
            </button>
            {prev && (
              <button type="button" class="btn player-btn lt-pl-ch is-last" aria-label={t('Back to {name}, the last channel', { name: prev.label })} onClick={() => switchTo(prev.Id)}>
                <LtIcon name="swap" size={20} />{t('Last channel')}
              </button>
            )}
            <div class="pl-volume">
              <button type="button" class="btn player-btn" aria-label={m.muted || m.volume === 0 ? t('Unmute') : t('Mute')} onClick={toggleMute}>
                <Icon name={m.muted || m.volume === 0 ? 'mute' : m.volume < 0.5 ? 'volumeLow' : 'volume'} size={22} />
              </button>
              <VolumeSlider value={m.volume} muted={m.muted} onChange={setVolume} />
            </div>
          </div>
          <div class="pl-right">
            {cur && (
              <button type="button" class="btn player-btn lt-pl-record" aria-pressed={rec ? 'true' : 'false'} onClick={() => void toggleRecord(cur)}>
                <RecDot />{rec === 'now' ? t('Recording') : rec === 'set' ? t('Recording set') : t('Record')}
              </button>
            )}
            <button type="button" class="btn player-btn" aria-label={t('Channel list')} aria-pressed={channelsOpen ? 'true' : 'false'} aria-expanded={channelsOpen ? 'true' : 'false'}
              onClick={() => setPanel((p) => (p?.kind === 'channels' ? null : { kind: 'channels' }))}>
              <LtIcon name="channels" size={22} />
            </button>
            <button type="button" class="btn player-btn" aria-label={subIndex >= 0 ? t('Subtitles: On') : t('Subtitles: Off')} aria-expanded={panel?.kind === 'tracks' && panel.tab === 'subtitles' ? 'true' : 'false'}
              onClick={() => setPanel((p) => (p?.kind === 'tracks' && p.tab === 'subtitles' ? null : { kind: 'tracks', tab: 'subtitles' }))}>
              <Icon name="subtitles" size={22} />
            </button>
            <button type="button" class="btn player-btn" aria-label={t('Audio track')} aria-expanded={panel?.kind === 'tracks' && panel.tab === 'audio' ? 'true' : 'false'}
              onClick={() => setPanel((p) => (p?.kind === 'tracks' && p.tab === 'audio' ? null : { kind: 'tracks', tab: 'audio' }))}>
              <Icon name="audio" size={22} />
            </button>
            {pipSupported && (
              <button type="button" class="btn player-btn" aria-label={t('Picture in picture')} onClick={togglePip}><Icon name="pip" size={22} /></button>
            )}
            <button type="button" class="btn player-btn" aria-label={fullscreen ? t('Exit full screen') : t('Full screen')} onClick={toggleFullscreen}>
              <Icon name={fullscreen ? 'exitFullscreen' : 'fullscreen'} size={22} />
            </button>
          </div>
        </div>
      </div>
      <span class="sr-only" aria-live="polite">{cur ? t('Now on {channel}: {title}', { channel: name, title: programTitle(cur) }) : name}</span>
    </div>
  );
}

function liveError(code: string): { title: string; text: string } {
  switch (code) {
    case 'busy':
      return { title: t('All tuners are busy'), text: t('The tuner is already playing as many streams as it can. Stop another stream or a recording, then try again.') };
    case 'network':
    case 'media':
      return { title: t("This channel isn't playing"), text: t("Its stream stopped or didn't answer. The channel may be off air, or not available where the server is.") };
    case 'NotAllowed':
      return { title: t('Not allowed on this profile'), text: t("Your profile isn't allowed to watch live TV. Ask the server's admin if that's unexpected.") };
    default:
      return { title: t("Couldn't open this channel"), text: t('The server could not open its stream. The tuner may be busy with other streams or recordings, or the channel may be off air.') };
  }
}

function resLabel(w?: number, h?: number): string {
  const W = w ?? 0;
  const H = h ?? 0;
  if (!W && !H) return '';
  if (W >= 3800 || H >= 2100) return '4K';
  if (W >= 1900 || H >= 1000) return '1080p';
  if (W >= 1200 || H >= 700) return '720p';
  if (H >= 560) return '576p';
  return H ? `${H}p` : '';
}

/* "Direct stream · 1080p · AAC 2.0" */
function streamLine(stream: ActiveStream, vw: number, vh: number): string {
  const s = stream.source;
  const v = videoStream(s);
  const a = audioStreams(s).find((x) => x.Index === stream.audioIndex) ?? audioStreams(s)[0];
  const ch = a?.Channels;
  const layout = ch === 1 ? '1.0' : ch === 2 ? '2.0' : ch === 6 ? '5.1' : ch === 8 ? '7.1' : ch ? String(ch) : '';
  const audio = a ? [stream.kind === 'Transcoding' ? 'AAC' : (a.Codec ?? '').toUpperCase(), layout].filter(Boolean).join(' ') : '';
  return [t(stream.kind), resLabel(vw || v?.Width, vh || v?.Height), audio].filter(Boolean).join(' · ');
}

