import { SERVER_URL, get, post } from '../api/client';
import type { PlaybackPrefs, SkipMode, SubtitlePrefs } from '../api/seedy';
import { createStore, safeLocal, saveLocal } from '../lib/store';
import { formatNumber } from '../lib/i18n';
import { softMode, type SoftMode } from './soft-profile';

/* Settings behind Settings · Playback and Settings · Subtitles, as the player reads them.
   "This device" settings live in this browser (localStorage, `seedy.device.*`); the rest follow the profile
   (Seedy prefs and prefs.custom) or live in Jellyfin's own UserConfiguration. Kept free of player code so the
   settings pages can import it without pulling the player in. */

/* ───────────────────────── This device ───────────────────────── */

export type SubSize = 'small' | 'normal' | 'large' | 'xlarge';
export type SubWeight = 'normal' | 'bold';
export type SubFont = 'seedy' | 'system' | 'print' | 'typewriter' | 'console' | 'smallcaps';
export type SubColor = 'white' | 'grey' | 'yellow' | 'cyan' | 'green' | 'pink';
export type SubBackground = 'none' | 'dim' | 'black';
export type SubOutline = 'none' | 'shadow' | 'outline' | 'raised';
export type SubPosition = 'bottom' | 'lifted' | 'top';

export interface SubtitleStyle {
  size: SubSize;
  weight: SubWeight;
  font: SubFont;
  color: SubColor;
  background: SubBackground;
  outline: SubOutline;
  position: SubPosition;
}

export type BurnIn = 'auto' | 'picture' | 'styled' | 'all';
export type PictureFit = 'contain' | 'stretch' | 'smaller';
export type SubEngine = 'auto' | 'seedy' | 'browser';
/* Largest picture when the server converts: the screen's size, a fixed width, or no limit. */
export type MaxResolution = 'screen' | 'none' | 3840 | 2560 | 1920 | 1280 | 854;

export interface DeviceSettings {
  /* Bits per second; 0 = Auto. */
  qualityHome: number;
  qualityAway: number;
  qualityCast: number;
  maxResolution: MaxResolution;
  limitResolution: boolean;
  /* 0 = Auto. */
  audioChannels: 0 | 1 | 2 | 6 | 8;
  cbrAudio: boolean;
  /* null = the profile's value (prefs.playback). */
  seekBack: number | null;
  seekForward: number | null;
  /* null = Jellyfin's UserConfiguration.CastReceiverId. */
  castReceiver: string | null;
  externalPlayer: boolean;
  /* Advanced */
  preferFmp4: boolean;
  hevc: boolean;
  av1: boolean;
  hdr: boolean;
  dts: boolean;
  truehd: boolean;
  mkv: boolean;
  /* The experimental player: decode here what this browser can't, instead of having the server convert it. */
  softPlayer: SoftMode;
  /* Subtitles: how they look (unset fields follow prefs.subtitles or the defaults) and how they're drawn. */
  subtitleStyle: Partial<SubtitleStyle>;
  subtitleBurnIn: BurnIn;
  pictureSubtitles: boolean;
  pictureFit: PictureFit;
  burnInWhenConverting: boolean;
  subtitleEngine: SubEngine;
  /* Settings · Controls: a remote's Back (Escape) leaves the player instead of only hiding the controls. */
  backClosesPlayer: boolean;
}

const modernBrowser = () => {
  const ua = typeof navigator === 'undefined' ? '' : navigator.userAgent;
  return /Chrome\/|Chromium\/|Edg\/|Firefox\/|Safari\//.test(ua);
};

export const DEVICE_DEFAULTS: DeviceSettings = {
  qualityHome: 0,
  qualityAway: 0,
  qualityCast: 20_000_000,
  maxResolution: 'screen',
  limitResolution: false,
  audioChannels: 0,
  cbrAudio: false,
  seekBack: null,
  seekForward: null,
  castReceiver: null,
  externalPlayer: false,
  preferFmp4: modernBrowser(),
  hevc: true,
  av1: true,
  hdr: true,
  dts: false,
  truehd: false,
  mkv: true,
  softPlayer: 'off',
  subtitleStyle: {},
  subtitleBurnIn: 'auto',
  pictureSubtitles: true,
  pictureFit: 'contain',
  burnInWhenConverting: false,
  subtitleEngine: 'auto',
  backClosesPlayer: false
};

/* localStorage key of every device setting. */
export const DEVICE_KEYS: Record<keyof DeviceSettings, string> = {
  qualityHome: 'seedy.device.quality.home',
  qualityAway: 'seedy.device.quality.away',
  qualityCast: 'seedy.device.quality.cast',
  maxResolution: 'seedy.device.quality.maxResolution',
  limitResolution: 'seedy.device.quality.limitResolution',
  audioChannels: 'seedy.device.audio.channels',
  cbrAudio: 'seedy.device.audio.cbr',
  seekBack: 'seedy.device.seek.back',
  seekForward: 'seedy.device.seek.forward',
  castReceiver: 'seedy.device.cast.receiver',
  externalPlayer: 'seedy.device.externalPlayer',
  preferFmp4: 'seedy.device.advanced.fmp4',
  hevc: 'seedy.device.advanced.hevc',
  av1: 'seedy.device.advanced.av1',
  hdr: 'seedy.device.advanced.hdr',
  dts: 'seedy.device.advanced.dts',
  truehd: 'seedy.device.advanced.truehd',
  mkv: 'seedy.device.advanced.mkv',
  softPlayer: 'seedy.device.advanced.softPlayer',
  subtitleStyle: 'seedy.device.subtitles.style',
  subtitleBurnIn: 'seedy.device.subtitles.burnIn',
  pictureSubtitles: 'seedy.device.subtitles.pictureHere',
  pictureFit: 'seedy.device.subtitles.pictureFit',
  burnInWhenConverting: 'seedy.device.subtitles.burnInWhenConverting',
  subtitleEngine: 'seedy.device.subtitles.engine',
  backClosesPlayer: 'seedy.device.controls.backClosesPlayer'
};

function readDevice(): DeviceSettings {
  const out = { ...DEVICE_DEFAULTS } as Record<string, unknown>;
  for (const [k, key] of Object.entries(DEVICE_KEYS)) {
    const v = safeLocal<unknown>(key, undefined);
    const def = (DEVICE_DEFAULTS as unknown as Record<string, unknown>)[k];
    if (v === undefined) continue;
    // Keep the stored value only when its type fits (an old or hand-edited key must not break playback).
    if (def === null ? typeof v === 'number' || typeof v === 'string' || v === null : typeof v === typeof def || (k === 'maxResolution' && (typeof v === 'number' || typeof v === 'string'))) {
      out[k] = v;
    }
  }
  if (typeof out.subtitleStyle !== 'object' || out.subtitleStyle === null) out.subtitleStyle = {};
  out.softPlayer = softMode(out.softPlayer);
  return out as unknown as DeviceSettings;
}

export const deviceStore = createStore<DeviceSettings>(readDevice());

export function setDevice<K extends keyof DeviceSettings>(key: K, value: DeviceSettings[K]) {
  saveLocal(DEVICE_KEYS[key], value);
  deviceStore.set((cur) => ({ ...cur, [key]: value }));
}

export function setSubtitleStyle(patch: Partial<SubtitleStyle>) {
  setDevice('subtitleStyle', { ...deviceStore.get().subtitleStyle, ...patch });
}

if (typeof window !== 'undefined') {
  // Another tab changed a device setting.
  window.addEventListener('storage', (e) => {
    if (!e.key || e.key.startsWith('seedy.device.')) deviceStore.set(readDevice());
  });
}

/* ───────────────────────── Profile (prefs.custom) ───────────────────────── */

export const CUSTOM_KEYS = {
  skipPreview: 'playback.skipPreview',
  skipCommercial: 'playback.skipCommercial',
  stillWatching: 'playback.stillWatching',
  cinemaMode: 'playback.cinemaMode',
  moviePicks: 'playback.moviePicks'
} as const;

export interface StillWatching { episodes: number; minutes: number }

export interface CustomPlayback {
  skipPreview: SkipMode;
  skipCommercial: SkipMode;
  /* null = never ask. */
  stillWatching: StillWatching | null;
  cinemaMode: boolean;
  moviePicks: boolean;
}

export const STILL_WATCHING_DEFAULT: StillWatching = { episodes: 3, minutes: 90 };

const isSkip = (v: unknown): v is SkipMode => v === 'auto' || v === 'button' || v === 'off';

export function customPlayback(custom: Record<string, unknown> | undefined): CustomPlayback {
  const c = custom ?? {};
  const sw = c[CUSTOM_KEYS.stillWatching];
  let stillWatching: StillWatching | null = STILL_WATCHING_DEFAULT;
  if (sw === false || sw === 'off') stillWatching = null;
  else if (sw && typeof sw === 'object' && Number((sw as StillWatching).episodes) > 0) {
    stillWatching = { episodes: Number((sw as StillWatching).episodes), minutes: Number((sw as StillWatching).minutes) || 0 };
  }
  return {
    skipPreview: isSkip(c[CUSTOM_KEYS.skipPreview]) ? (c[CUSTOM_KEYS.skipPreview] as SkipMode) : 'auto',
    skipCommercial: isSkip(c[CUSTOM_KEYS.skipCommercial]) ? (c[CUSTOM_KEYS.skipCommercial] as SkipMode) : 'auto',
    stillWatching,
    cinemaMode: c[CUSTOM_KEYS.cinemaMode] === true,
    moviePicks: c[CUSTOM_KEYS.moviePicks] !== false
  };
}

/* What the player does with each kind of media segment. */
export function segmentMode(type: string, pb: PlaybackPrefs, custom: CustomPlayback): SkipMode {
  switch (type) {
    case 'Intro': return pb.skipIntro;
    case 'Recap': return pb.skipRecap;
    case 'Outro': return pb.skipCredits;
    case 'Preview': return custom.skipPreview;
    case 'Commercial': return custom.skipCommercial;
    default: return 'off';
  }
}

/* ───────────────────────── Quality ───────────────────────── */

export const AUTO_BITRATE = 120_000_000;

/* Picker choices on Settings · Playback (bits per second). */
export const BITRATES = [80, 60, 40, 20, 15, 10, 8, 6, 4, 3, 2, 1.5, 1, 0.72, 0.42].map((m) => Math.round(m * 1_000_000));

export function bitrateLabel(bps: number): string {
  const num = (n: number) => formatNumber(n, { maximumFractionDigits: 1, useGrouping: false });
  if (bps >= 1_000_000) return `${num(Number((bps / 1_000_000).toFixed(1)))} Mbps`;
  return `${num(Math.round(bps / 1000))} kbps`;
}

export const RESOLUTIONS: { value: MaxResolution; label: string }[] = [
  { value: 'screen', label: 'Screen size' },
  { value: 3840, label: '4K' },
  { value: 2560, label: '1440p' },
  { value: 1920, label: '1080p' },
  { value: 1280, label: '720p' },
  { value: 854, label: '480p' },
  { value: 'none', label: 'No limit' }
];

/* Width in pixels the server should not exceed when converting, or null for no limit. */
export function maxVideoWidth(r: MaxResolution): number | null {
  if (r === 'none') return null;
  if (r === 'screen') {
    try {
      const dpr = window.devicePixelRatio || 1;
      return Math.round(Math.max(window.screen.width, window.screen.height) * dpr) || null;
    } catch {
      return null;
    }
  }
  return r;
}

/* ── Home or away: is the server on this network? ── */

export type NetworkLocation = 'home' | 'away';

/* A server address on a private range (or a local name) means this device is at home. */
export function isPrivateHost(host: string): boolean {
  const h = host.replace(/^\[|\]$/g, '').toLowerCase();
  if (h === 'localhost' || h.endsWith('.localhost') || h === '::1') return true;
  if (/\.(local|lan|home|internal|home\.arpa|localdomain)$/.test(h)) return true;
  const v4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(h);
  if (v4) {
    const [a, b] = [Number(v4[1]), Number(v4[2])];
    return a === 10 || a === 127 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) || (a === 169 && b === 254);
  }
  if (h.includes(':')) return /^f[cd][0-9a-f]{2}:/.test(h) || h.startsWith('fe80:');
  // A single-label name ("nas", "jellyfin") only resolves on a local network.
  return !h.includes('.');
}

export const locationStore = createStore<NetworkLocation | null>(null);
let locating: Promise<NetworkLocation> | null = null;

export function detectLocation(): Promise<NetworkLocation> {
  if (locating) return locating;
  locating = (async () => {
    let loc: NetworkLocation = 'away';
    let host = '';
    try { host = new URL(SERVER_URL).hostname; } catch { /* keep away */ }
    if (host && isPrivateHost(host)) loc = 'home';
    else {
      // A public address can still be the home server (split DNS, hairpin NAT): ask the server who we are.
      try {
        const ep = await get<{ IsLocal?: boolean; IsInNetwork?: boolean }>('/System/Endpoint');
        if (ep?.IsInNetwork || ep?.IsLocal) loc = 'home';
      } catch { /* away */ }
    }
    locationStore.set(loc);
    return loc;
  })();
  return locating;
}

/* The bitrate "Auto" stands for in the player: this device's limit where it is now, under the admin's cap. */
export function autoBitrate(dev: DeviceSettings, loc: NetworkLocation, adminCap: number | null): number {
  const local = loc === 'home' ? dev.qualityHome : dev.qualityAway;
  const cap = local > 0 ? local : AUTO_BITRATE;
  // An explicit choice on this device wins over the profile's default cap.
  if (local > 0) return cap;
  return adminCap ? Math.min(cap, adminCap) : cap;
}

/* ───────────────────────── Subtitle look ───────────────────────── */

const SIZE_FROM_PREFS: Record<SubtitlePrefs['size'], SubSize> = { S: 'small', M: 'normal', L: 'large' };
export const SIZE_TO_PREFS: Record<SubSize, SubtitlePrefs['size']> = { small: 'S', normal: 'M', large: 'L', xlarge: 'L' };

export function resolveStyle(dev: DeviceSettings, subs: SubtitlePrefs | undefined): SubtitleStyle {
  const s = dev.subtitleStyle ?? {};
  const prefs = subs ?? { size: 'M', lift: true };
  return {
    size: s.size ?? SIZE_FROM_PREFS[prefs.size] ?? 'normal',
    weight: s.weight ?? 'normal',
    font: s.font ?? 'seedy',
    color: s.color ?? 'white',
    background: s.background ?? 'none',
    outline: s.outline ?? 'shadow',
    position: s.position ?? (prefs.lift ? 'lifted' : 'bottom')
  };
}

/* Player text size in px at full size, and the preview's on Settings · Subtitles. */
export const SUB_PX: Record<SubSize, number> = { small: 24, normal: 30, large: 38, xlarge: 48 };
export const PREVIEW_PX: Record<SubSize, number> = { small: 14, normal: 18, large: 22, xlarge: 28 };

export const SUB_COLORS: { value: SubColor; label: string; css: string }[] = [
  { value: 'white', label: 'White', css: '#ffffff' },
  { value: 'grey', label: 'Light grey', css: '#d0d0d0' },
  { value: 'yellow', label: 'Yellow', css: '#ffe14d' },
  { value: 'cyan', label: 'Cyan', css: '#7fe7ff' },
  { value: 'green', label: 'Green', css: '#8ff59a' },
  { value: 'pink', label: 'Pink', css: '#ff9fff' }
];

export const SUB_BACKGROUNDS: { value: SubBackground; label: string; css: string }[] = [
  { value: 'none', label: 'None', css: 'transparent' },
  { value: 'dim', label: 'Dark, see-through', css: 'rgba(0,0,0,0.5)' },
  { value: 'black', label: 'Black', css: '#000000' }
];

export const SUB_FONTS: { value: SubFont; label: string; css: string }[] = [
  { value: 'seedy', label: 'Seedy (Noto Sans)', css: "var(--font)" },
  { value: 'system', label: 'System', css: "system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif" },
  { value: 'print', label: 'Print (serif)', css: "Georgia, 'Times New Roman', serif" },
  { value: 'typewriter', label: 'Typewriter', css: "'Courier New', Courier, monospace" },
  { value: 'console', label: 'Console', css: "ui-monospace, Menlo, Consolas, 'DejaVu Sans Mono', monospace" },
  { value: 'smallcaps', label: 'Small capitals', css: "var(--font)" }
];

const OUTLINE: Record<SubOutline, string> = {
  none: 'none',
  /* The player's shadow (Player boards); the preview uses the Subtitles board's lighter one. */
  shadow: '0 0 2px #000000, 0 2px 8px rgba(0, 0, 0, 0.9)',
  outline: [[-1, -1], [0, -1], [1, -1], [-1, 0], [1, 0], [-1, 1], [0, 1], [1, 1]].map(([x, y]) => `${x * 0.055}em ${y * 0.055}em 0 #000`).join(', ') + ', 0 0 0.08em #000',
  raised: '-0.035em -0.035em 0 rgba(255,255,255,0.4), 0.045em 0.045em 0 #000, 0.09em 0.09em 0.03em rgba(0,0,0,0.75)'
};

/* CSS for one subtitle line in this style; `px` is the text size to use. */
export function cueCss(style: SubtitleStyle, px: number, where: 'player' | 'preview' = 'preview'): Record<string, string> {
  const font = SUB_FONTS.find((f) => f.value === style.font) ?? SUB_FONTS[0];
  const bg = SUB_BACKGROUNDS.find((b) => b.value === style.background)?.css ?? 'transparent';
  return {
    fontSize: px + 'px',
    /* Normal is the player boards' 600; Bold is the Subtitles board's 800. */
    fontWeight: style.weight === 'bold' ? '800' : '600',
    fontFamily: font.css,
    fontVariant: style.font === 'smallcaps' ? 'small-caps' : 'normal',
    color: SUB_COLORS.find((c) => c.value === style.color)?.css ?? '#ffffff',
    background: bg,
    textShadow: style.outline === 'shadow' && where === 'preview' ? '0 2px 4px rgba(0,0,0,0.9), 0 0 2px #000' : OUTLINE[style.outline] ?? OUTLINE.shadow
  };
}

/* ── Seek steps: this device's, else the profile's ── */
export function seekSteps(dev: DeviceSettings, pb: PlaybackPrefs): { back: number; forward: number } {
  return { back: dev.seekBack ?? pb.seekBackSeconds, forward: dev.seekForward ?? pb.seekForwardSeconds };
}

/* "When casting": cap the bitrate on the device Seedy just handed playback to. Lives here (not in external.ts)
   so the header's cast button can use it without pulling in the player. */
export function applyCastQuality(sessionId: string) {
  const bps = deviceStore.get().qualityCast;
  if (!bps) return;
  void post<void>(`/Sessions/${sessionId}/Command`, { Name: 'SetMaxStreamingBitrate', Arguments: { MaxBitrate: String(bps) } }).catch(() => undefined);
}
