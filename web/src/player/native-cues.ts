import { useEffect } from 'preact/hooks';
import { SUB_PX, cueCss, type SubtitleStyle } from './settings';
import type { Cue } from './subtitles';

/* "Browser captions": text subtitles handed to the video element as a WebVTT track, so the browser draws them —
   also where Seedy's own layer can't be seen (picture in picture, iOS full screen). Styled with ::cue. */

const TRACK_KEY = '__seedyTrack';
type WithTrack = HTMLVideoElement & { [TRACK_KEY]?: TextTrack };

function trackFor(video: HTMLVideoElement): TextTrack | null {
  const v = video as WithTrack;
  if (v[TRACK_KEY]) return v[TRACK_KEY]!;
  if (typeof video.addTextTrack !== 'function') return null;
  // Tracks added this way can't be removed, so each element keeps one and reuses it.
  const t = video.addTextTrack('subtitles', 'Seedy', 'und');
  v[TRACK_KEY] = t;
  return t;
}

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

function cueText(c: Cue): string {
  return c.lines.map((line) => line.map((sp) => {
    let t = esc(sp.text);
    if (sp.u) t = `<u>${t}</u>`;
    if (sp.b) t = `<b>${t}</b>`;
    if (sp.i) t = `<i>${t}</i>`;
    return t;
  }).join('')).join('\n');
}

function applyCueStyle(style: SubtitleStyle) {
  const css = cueCss(style, SUB_PX[style.size], 'player');
  let el = document.getElementById('seedy-cue-style') as HTMLStyleElement | null;
  if (!el) {
    el = document.createElement('style');
    el.id = 'seedy-cue-style';
    document.head.appendChild(el);
  }
  const font = css.fontFamily.replace('var(--font)', "'Noto Sans Variable', 'Noto Sans', system-ui, sans-serif");
  el.textContent = `.pl-video::cue { color: ${css.color}; background-color: ${css.background === 'transparent' ? 'transparent' : css.background};`
    + ` font-family: ${font}; font-weight: ${css.fontWeight}; font-variant: ${css.fontVariant}; text-shadow: ${css.textShadow};`
    + ` font-size: ${(SUB_PX[style.size] / 810 * 100).toFixed(2)}vh; line-height: 1.3; }`;
}

/* Keep the video's own text track in step with Seedy's cues; `on` decides whether the browser shows it. */
export function useNativeCues(video: HTMLVideoElement | null, cues: Cue[], on: boolean, offset: number, style: SubtitleStyle) {
  useEffect(() => {
    if (!video) return;
    const track = trackFor(video);
    if (!track) return;
    for (const c of Array.from(track.cues ?? [])) track.removeCue(c);
    if (!on || !cues.length || typeof VTTCue === 'undefined') {
      track.mode = 'disabled';
      return;
    }
    applyCueStyle(style);
    for (const c of cues) {
      const cue = new VTTCue(c.start + offset, c.end + offset, cueText(c));
      // Position as chosen: top lines, lines lifted clear of the controls area, or the bottom.
      if (style.position === 'top') { cue.snapToLines = true; cue.line = 0; }
      else if (style.position === 'lifted') { cue.snapToLines = false; cue.line = 80; }
      track.addCue(cue);
    }
    track.mode = 'showing';
  }, [video, cues, on, offset, style.size, style.weight, style.font, style.color, style.background, style.outline, style.position]);

  useEffect(() => () => {
    const t = video ? (video as WithTrack)[TRACK_KEY] : undefined;
    if (t) t.mode = 'disabled';
  }, [video]);
}
