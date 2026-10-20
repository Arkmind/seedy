import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { SERVER_URL } from '../../api/client';
import { cardArt } from '../../api/images';
import type { Channel, Program } from '../../api/livetv';
import { t } from '../../lib/i18n';
import { navigate } from '../../lib/router';
import { Icon } from '../../ui/icons';
import { toast } from '../../ui/overlay';
import { channelColor, initials, type LiveChannel } from './data';
import type { PreviewState } from './preview';

/* Small pieces the Live TV boards repeat: channel logos, LIVE / New / Premiere pills, the recording dot, programme
   art, and the muted-preview button. */

/* ── Channel logos ──
   Playlist logos come light or dark on transparent; each sits on the plate it reads on (a light logo on dark). */

type Tone = 'light' | 'dark';
const TONES_KEY = 'seedy.device.logoTones';
let tones: Record<string, Tone> = (() => {
  try { return JSON.parse(localStorage.getItem(TONES_KEY) ?? '{}') as Record<string, Tone>; } catch { return {}; }
})();
const waiting = new Map<string, Promise<Tone>>();

function measure(src: string): Promise<Tone> {
  if (tones[src]) return Promise.resolve(tones[src]);
  let p = waiting.get(src);
  if (p) return p;
  p = new Promise<Tone>((resolve) => {
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => {
      let tone: Tone = 'dark';
      try {
        const c = document.createElement('canvas');
        c.width = 32; c.height = 32;
        const g = c.getContext('2d', { willReadFrequently: true })!;
        g.drawImage(img, 0, 0, 32, 32);
        const d = g.getImageData(0, 0, 32, 32).data;
        let sum = 0; let w = 0;
        for (let i = 0; i < d.length; i += 4) {
          const a = d[i + 3] / 255;
          if (a < 0.4) continue;
          sum += a * (0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2]) / 255;
          w += a;
        }
        tone = w > 0 && sum / w > 0.62 ? 'light' : 'dark';
      } catch { /* unreadable: a light plate suits most logos */ }
      tones = { ...tones, [src]: tone };
      const keys = Object.keys(tones);
      if (keys.length > 400) tones = Object.fromEntries(keys.slice(-300).map((k) => [k, tones[k]]));
      try { localStorage.setItem(TONES_KEY, JSON.stringify(tones)); } catch { /* storage full */ }
      resolve(tone);
    };
    img.onerror = () => resolve('dark');
    img.src = src;
  });
  waiting.set(src, p);
  return p;
}

export function logoSrc(c: Pick<Channel, 'Id' | 'ImageTags'> | undefined, px: number): string | undefined {
  const tag = c?.ImageTags?.Primary;
  if (!c || !tag) return undefined;
  return `${SERVER_URL}/Items/${c.Id}/Images/Primary?tag=${tag}&maxWidth=${Math.round(px * 2)}&format=Png&quality=90`;
}

export function ChannelLogo({ channel, size, radius, class: cls }: { channel?: LiveChannel | Channel; size: number; radius?: number; class?: string }) {
  const name = (channel as LiveChannel | undefined)?.label ?? channel?.Name ?? '';
  const src = logoSrc(channel, size);
  const [tone, setTone] = useState<Tone | null>(src ? tones[src] ?? null : null);
  const [broken, setBroken] = useState(false);
  useEffect(() => {
    setBroken(false);
    if (!src) return;
    setTone(tones[src] ?? null);
    let alive = true;
    void measure(src).then((x) => alive && setTone(x));
    return () => { alive = false; };
  }, [src]);
  const style: Record<string, string> = { width: size + 'px', height: size + 'px', borderRadius: (radius ?? Math.round(size * 0.27)) + 'px' };
  if (!src || broken) {
    const col = channelColor(name);
    return (
      <span aria-hidden="true" class={'lt-logo is-initials' + (cls ? ' ' + cls : '')} style={{ ...style, background: col.bg, color: col.ink, fontSize: Math.round(size * (size >= 40 ? 0.34 : 0.35)) + 'px' }}>
        {initials(name)}
      </span>
    );
  }
  return (
    <span aria-hidden="true" class={'lt-logo' + (tone === 'light' ? ' is-dark' : tone === 'dark' ? ' is-light' : '') + (cls ? ' ' + cls : '')} style={style}>
      {tone && <img src={src} alt="" onError={() => setBroken(true)} />}
    </span>
  );
}

/* ── Pills ── */

export function LivePill({ small, label = 'LIVE' }: { small?: boolean; label?: string }) {
  return <span class={'lt-live' + (small ? ' is-sm' : '')}><span class="lt-live-dot" />{t(label)}</span>;
}

export function TagPill({ kind }: { kind: 'new' | 'premiere' }) {
  return <span class="lt-tag"><span class="lt-tag-dot" />{kind === 'new' ? t('New') : t('Premiere')}</span>;
}

export function RecDot({ size = 18 }: { size?: number }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" class="lt-recdot">
      <circle cx="12" cy="12" r="6.5" fill="#ff5c5c" />
    </svg>
  );
}

export function SeriesDot({ size = 17 }: { size?: number }) {
  return (
    <svg viewBox="0 0 24 24" width={Math.round(size * 1.18)} height={size} aria-hidden="true">
      <circle cx="8.5" cy="12" r="5" fill="#ff5c5c" /><circle cx="16" cy="12" r="4.5" fill="none" stroke="#ff5c5c" stroke-width="2" />
    </svg>
  );
}

/* ── Programme art ──
   The programme's own picture when the guide has one; otherwise the channel's logo, large, on a dark plate. */

export function ProgramArt({ program, channel, width = 640 }: { program?: Program; channel?: LiveChannel; width?: number }) {
  const art = program ? cardArt(program, width) : { titleOnArt: true };
  if (art.src) {
    return (
      <>
        {art.contain && <img src={art.src} alt="" class="art-blur" loading="lazy" />}
        <img src={art.src} alt="" class={art.contain ? 'art-contain' : 'art'} loading="lazy" />
      </>
    );
  }
  const size = Math.max(56, Math.round(width / 6.5));
  return (
    <div class="lt-art-logo" aria-hidden="true">
      {channel
        ? <ChannelLogo channel={channel} size={size} />
        : <span class="lt-logo lt-art-icon" style={{ width: size + 'px', height: size + 'px', borderRadius: Math.round(size * 0.27) + 'px' }}><LtIcon name="recordings" size={Math.round(size * 0.42)} /></span>}
    </div>
  );
}

export const hasProgramArt = (p?: Program) => !!p && !!cardArt(p, 320).src;

/* "Muted preview" — turns the sound on (and off again). */
export function SoundButton({ video, state, small }: { video: { current: HTMLVideoElement | null }; state: PreviewState; small?: boolean }) {
  const [muted, setMuted] = useState(true);
  useEffect(() => { setMuted(true); }, [state === 'idle']);
  if (state !== 'playing') return null;
  return (
    <button
      type="button"
      class={'lt-sound' + (small ? ' is-sm' : '')}
      aria-label={muted ? t('Turn on sound') : t('Turn off sound')}
      onClick={(e) => {
        e.stopPropagation();
        const v = video.current;
        if (!v) return;
        v.muted = !v.muted;
        if (!v.muted && v.volume === 0) v.volume = 0.6;
        setMuted(v.muted);
      }}
    >
      {muted ? <LtIcon name="muted" size={small ? 15 : 16} /> : <Icon name="volume" size={small ? 15 : 16} stroke={2.2} />}
      {muted ? t('Muted preview') : t('Sound on')}
    </button>
  );
}

/* Watch a channel in Seedy's player. */
export const watchPath = (channelId: string) => `/play/${channelId}`;
export function watch(channelId?: string) {
  if (!channelId) { toast(t('This programme has no channel to watch'), 'error'); return; }
  navigate(watchPath(channelId));
}

/* A chip as on the guide's filter row: "All 301". */
export function Chip({ label, count, pressed, onClick }: { label: ComponentChildren; count?: number | string; pressed: boolean; onClick: () => void }) {
  return (
    <button type="button" class="lt-chip" aria-pressed={pressed ? 'true' : 'false'} onClick={onClick}>
      {label}
      {count !== undefined && count !== '' && <span class="lt-chip-count">{count}</span>}
    </button>
  );
}

/* ── The Live TV boards' own icons (24 px grid, round caps), path for path ── */

const LT_ICONS = {
  onNow: <><circle cx="12" cy="12" r="2" /><path d="M8.5 8.5a5 5 0 0 0 0 7" /><path d="M15.5 8.5a5 5 0 0 1 0 7" /><path d="M5.6 5.6a9 9 0 0 0 0 12.8" /><path d="M18.4 5.6a9 9 0 0 1 0 12.8" /></>,
  guide: <><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M3 10h18" /><path d="M3 15h18" /><path d="M9 4v16" /></>,
  channels: <><rect x="3" y="7" width="18" height="13" rx="2" /><path d="M8 3l4 4 4-4" /></>,
  recordings: <><circle cx="12" cy="12" r="8" /><circle cx="12" cy="12" r="3.5" fill="currentColor" /></>,
  hash: <><path d="M5 9h14" /><path d="M5 15h14" /><path d="M10 4L8 20" /><path d="M16 4l-2 16" /></>,
  muted: <><path d="M4 9v6h4l5 4V5L8 9z" /><path d="M17 9l4 6" /><path d="M21 9l-4 6" /></>,
  swap: <><path d="M4 8h13" /><path d="M13 4l4 4-4 4" /><path d="M20 16H7" /><path d="M11 12l-4 4 4 4" /></>,
  calendar: <><rect x="4" y="5" width="16" height="15" rx="2" /><path d="M4 10h16" /><path d="M9 3v4" /><path d="M15 3v4" /></>,
  now: <><circle cx="12" cy="12" r="8" /><path d="M12 8v4l3 2" /></>,
  info: <><circle cx="12" cy="12" r="8.5" /><path d="M12 11v5" /><path d="M12 8h.01" /></>,
  warn: <><path d="M12 4l9 16H3z" /><path d="M12 10v4" /><path d="M12 17h.01" /></>,
  chevronUp: <path d="M6 15l6-6 6 6" />,
  chevronDown: <path d="M6 9l6 6 6-6" />,
  close: <><path d="M6 6l12 12" /><path d="M18 6L6 18" /></>,
  search: <><circle cx="11" cy="11" r="7" /><path d="M20 20l-4-4" /></>
};
export type LtIconName = keyof typeof LT_ICONS;

export function LtIcon({ name, size = 18, stroke = 2.2, class: cls }: { name: LtIconName; size?: number; stroke?: number; class?: string }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" class={'ic' + (cls ? ' ' + cls : '')}
      fill="none" stroke="currentColor" stroke-width={stroke} stroke-linecap="round" stroke-linejoin="round">
      {LT_ICONS[name]}
    </svg>
  );
}

export function StopIcon({ size = 16 }: { size?: number }) {
  return <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true"><rect x="6" y="6" width="12" height="12" rx="2" fill="currentColor" /></svg>;
}

export function HeartIcon({ size = 20, filled }: { size?: number; filled?: boolean }) {
  return (
    <svg viewBox="0 0 24 24" width={size} height={size} aria-hidden="true" fill={filled ? 'currentColor' : 'none'} stroke="currentColor" stroke-width="2" stroke-linejoin="round" stroke-linecap="round">
      <path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z" />
    </svg>
  );
}
