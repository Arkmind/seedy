import { useEffect, useRef, useState } from 'preact/hooks';
import { SERVER_URL, authHeader, deviceId, getSession } from '../../api/client';
import { getPlaybackInfo } from '../../api/jellyfin';
import { closesSettled, liveDeviceProfile, trackClose } from '../../player/live-profile';

/* The heroes' muted live preview: opens the channel's live stream through PlaybackInfo (AutoOpenLiveStream),
   plays it muted with hls.js, and closes the stream and its remux when the hero changes or the page goes.
   It reports no playback, so a preview never counts as watching. */

export type PreviewState = 'idle' | 'loading' | 'playing' | 'failed';

const withKey = (path: string) => {
  const p = path.replace(/^\/videos\//, '/Videos/');
  if (/[?&]ApiKey=/i.test(p)) return SERVER_URL + p;
  return SERVER_URL + p + (p.includes('?') ? '&' : '?') + 'ApiKey=' + encodeURIComponent(getSession()?.token ?? '');
};

function fire(method: string, path: string): Promise<unknown> | undefined {
  try {
    return fetch(SERVER_URL + path, { method, keepalive: true, headers: { Authorization: authHeader(), Accept: 'application/json' } }).catch(() => undefined);
  } catch { return undefined; /* page closing */ }
}

const saveData = () => !!(navigator as Navigator & { connection?: { saveData?: boolean } }).connection?.saveData;

export function useLivePreview(channelId: string | undefined, enabled = true) {
  const ref = useRef<HTMLVideoElement>(null);
  const [state, setState] = useState<PreviewState>('idle');
  useEffect(() => {
    setState('idle');
    if (!channelId || !enabled || saveData()) return;
    let alive = true;
    let hls: { destroy(): void } | null = null;
    let session: { play: string; live?: string } | null = null;
    const timer = window.setTimeout(async () => {
      const el = ref.current;
      if (!alive || !el || document.visibilityState === 'hidden') return;
      setState('loading');
      try {
        await closesSettled();
        if (!alive) return;
        const res = await getPlaybackInfo(channelId, {
          DeviceProfile: liveDeviceProfile(20_000_000),
          MaxStreamingBitrate: 20_000_000,
          EnableDirectPlay: false,
          EnableDirectStream: true,
          EnableTranscoding: true,
          AllowVideoStreamCopy: true,
          AllowAudioStreamCopy: true,
          AutoOpenLiveStream: true
        });
        const src = res.MediaSources?.[0] as (typeof res.MediaSources)[number] & { LiveStreamId?: string } | undefined;
        session = { play: res.PlaySessionId, live: src?.LiveStreamId };
        if (!alive) return;
        if (res.ErrorCode || !src?.TranscodingUrl) throw new Error(res.ErrorCode ?? 'NoStream');
        const url = withKey(src.TranscodingUrl);
        el.muted = true;
        if (el.canPlayType('application/vnd.apple.mpegurl') && !/Chrome|Firefox/.test(navigator.userAgent)) {
          el.src = url;
        } else {
          const Hls = (await import('hls.js')).default;
          if (!alive) return;
          if (!Hls.isSupported()) throw new Error('NoHls');
          const h = new Hls({ maxBufferLength: 12, backBufferLength: 10, manifestLoadingTimeOut: 20000 });
          hls = h;
          h.on(Hls.Events.ERROR, (_e, data) => { if (data.fatal && alive) setState('failed'); });
          h.loadSource(url);
          h.attachMedia(el);
        }
        el.onplaying = () => alive && setState('playing');
        el.onerror = () => alive && setState('failed');
        void el.play().catch(() => undefined);
      } catch {
        if (alive) setState('failed');
      }
    }, 900);
    // Closing the tab closes the stream too (the effect's cleanup doesn't run then).
    const onHide = () => {
      if (!session) return;
      fire('DELETE', `/Videos/ActiveEncodings?deviceId=${encodeURIComponent(deviceId())}&playSessionId=${encodeURIComponent(session.play)}`);
      if (session.live) fire('POST', `/LiveStreams/Close?liveStreamId=${encodeURIComponent(session.live)}`);
      session = null;
    };
    window.addEventListener('pagehide', onHide);
    return () => {
      alive = false;
      window.removeEventListener('pagehide', onHide);
      window.clearTimeout(timer);
      const el = ref.current;
      try { hls?.destroy(); } catch { /* gone */ }
      if (el) {
        el.onplaying = null;
        el.onerror = null;
        try { el.pause(); el.removeAttribute('src'); el.load(); } catch { /* detached */ }
      }
      if (session) {
        fire('DELETE', `/Videos/ActiveEncodings?deviceId=${encodeURIComponent(deviceId())}&playSessionId=${encodeURIComponent(session.play)}`);
        if (session.live) trackClose(fire('POST', `/LiveStreams/Close?liveStreamId=${encodeURIComponent(session.live)}`));
      }
    };
  }, [channelId, enabled]);
  return { ref, state };
}
