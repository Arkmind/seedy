import { PROFILE_DEFAULTS, buildDeviceProfile, type ProfileOptions } from './device-profile';

/* Live TV streams convert to HLS in MPEG-TS segments. Jellyfin offers fMP4 segments only for tuners that allow them,
   and otherwise falls back to a progressive stream a browser can't play — so for live channels the HLS profile asks
   for TS with H.264 and the audio codecs TS carries. */
export function liveDeviceProfile(maxBitrate: number, o: ProfileOptions = PROFILE_DEFAULTS) {
  const p = buildDeviceProfile(maxBitrate, o) as ReturnType<typeof buildDeviceProfile> & {
    TranscodingProfiles?: { Type?: string; Protocol?: string; Container?: string; VideoCodec?: string; AudioCodec?: string }[];
  };
  for (const tp of p.TranscodingProfiles ?? []) {
    if (tp.Type === 'Video' && tp.Protocol === 'hls') {
      tp.Container = 'ts';
      tp.VideoCodec = 'h264';
      tp.AudioCodec = (tp.AudioCodec ?? 'aac').split(',').filter((c) => c === 'aac' || c === 'mp3' || c === 'ac3' || c === 'eac3').join(',') || 'aac';
    }
  }
  return p;
}

/* Closing a live stream frees a tuner. A tuner with a stream limit refuses the next channel until the server has
   closed the last one, so closes are tracked and the next open waits for them (briefly). */
const closing = new Set<Promise<unknown>>();
export function trackClose(p: Promise<unknown> | undefined) {
  if (!p) return;
  const q = p.catch(() => undefined).finally(() => closing.delete(q));
  closing.add(q);
}
export function closesSettled(maxMs = 10_000): Promise<void> {
  if (!closing.size) return Promise.resolve();
  return Promise.race([Promise.all([...closing]).then(() => undefined), new Promise<void>((r) => window.setTimeout(r, maxMs))]);
}
