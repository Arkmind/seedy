import type { MediaStream } from '../api/types';

/* "Keep my audio choice" / "Keep my subtitle choice" (Jellyfin's RememberAudioSelections and
   RememberSubtitleSelections): the next episode of a show gets the same kind of track as the last one. */

export interface TrackSig { lang: string; title: string; codec: string; forced: boolean; sdh: boolean; channels: number; external: boolean }

export const trackSig = (s: MediaStream): TrackSig => ({
  lang: (s.Language ?? '').toLowerCase(),
  title: (s.Title ?? '').trim().toLowerCase(),
  codec: (s.Codec ?? '').toLowerCase(),
  forced: !!s.IsForced,
  sdh: !!s.IsHearingImpaired,
  channels: s.Channels ?? 0,
  external: !!s.IsExternal
});

/* The stream in `list` most like `want`: same language first, then forced/SDH flags, title, codec, layout. */
export function closestTrack(list: MediaStream[], want: TrackSig): number | undefined {
  let best: MediaStream | undefined;
  let bestScore = -1;
  for (const s of list) {
    const g = trackSig(s);
    if (g.lang !== want.lang) continue;
    let score = 1;
    if (g.forced === want.forced) score += 4;
    if (g.sdh === want.sdh) score += 2;
    if (g.title && g.title === want.title) score += 3;
    if (g.codec === want.codec) score += 1;
    if (g.channels === want.channels) score += 1;
    if (g.external === want.external) score += 1;
    if (score > bestScore) { best = s; bestScore = score; }
  }
  return best?.Index;
}

/* What the last episode played with, for this tab. `sub: 'off'` means subtitles were off. */
export const lastTracks: { seriesId: string | null; audio: TrackSig | null; sub: TrackSig | 'off' | null } = { seriesId: null, audio: null, sub: null };
