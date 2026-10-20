import { useEffect, useState } from 'preact/hooks';
import { deviceId, get, getSession, url } from '../../api/client';
import type { ItemsResult } from '../../api/types';
import { CUSTOM, readCustom } from './prefs';

/* Theme music and theme videos on a title's page (Settings · Display › While browsing). A theme video plays muted
   behind the page instead of the backdrop; a theme song plays quietly and fades out when the page closes. */

interface ThemeMedia { ThemeVideosResult?: ItemsResult; ThemeSongsResult?: ItemsResult }

const VOLUME = 0.3;

function fade(el: HTMLMediaElement, to: number, ms: number, done?: () => void) {
  const from = el.volume;
  const start = performance.now();
  const step = () => {
    const t = Math.min(1, (performance.now() - start) / ms);
    el.volume = Math.max(0, Math.min(1, from + (to - from) * t));
    if (t < 1) requestAnimationFrame(step);
    else done?.();
  };
  requestAnimationFrame(step);
}

export function useThemeMedia(itemId?: string): { video?: string } {
  const [video, setVideo] = useState<string | undefined>();
  useEffect(() => {
    setVideo(undefined);
    const wantMusic = readCustom(CUSTOM.themeMusic);
    const wantVideo = readCustom(CUSTOM.themeVideos);
    const s = getSession();
    if (!itemId || !s || (!wantMusic && !wantVideo)) return;
    let alive = true;
    let audio: HTMLAudioElement | null = null;
    get<ThemeMedia>(`/Items/${itemId}/ThemeMedia`, { userId: s.userId, inheritFromParent: true }).then((m) => {
      if (!alive) return;
      const v = wantVideo ? m.ThemeVideosResult?.Items?.[0] : undefined;
      // Theme videos play muted (browsers allow that without a click); a theme song can still play over one.
      if (v) setVideo(url(`/Videos/${v.Id}/stream`, { static: true, ApiKey: s.token }));
      const song = wantMusic ? m.ThemeSongsResult?.Items?.[0] : undefined;
      if (!song) return;
      audio = new Audio(url(`/Audio/${song.Id}/universal`, {
        UserId: s.userId, DeviceId: deviceId(), MaxStreamingBitrate: 320000, Container: 'mp3,aac,m4a|aac,m4b|aac,flac,webma,webm|webma,wav,ogg',
        TranscodingContainer: 'mp3', TranscodingProtocol: 'http', AudioCodec: 'mp3', ApiKey: s.token
      }));
      audio.loop = true;
      audio.volume = 0;
      audio.dataset.seedyTheme = '1';
      // Browsers may refuse sound before the first click on the page; then the page stays quiet.
      audio.play().then(() => { if (alive && audio) fade(audio, VOLUME, 1500); }, () => {});
    }, () => {});
    return () => {
      alive = false;
      const a = audio;
      if (a) {
        const stop = () => { a.pause(); a.removeAttribute('src'); a.load(); };
        fade(a, 0, 600, stop);
        // The fade runs on animation frames, which a hidden tab doesn't get: stop regardless.
        window.setTimeout(stop, 800);
      }
    };
  }, [itemId]);
  return { video };
}
