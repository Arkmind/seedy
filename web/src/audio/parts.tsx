import type { JSX } from 'preact';
import { useState } from 'preact/hooks';
import { sendPlayCommand } from '../api/jellyfin';
import type { SessionInfo } from '../api/types';
import { listCastTargets } from '../lib/cast';
import { t } from '../lib/i18n';
import { useStore } from '../lib/store';
import { MenuButton, MenuItem, toast } from '../ui/overlay';
import { audioStore, pause, position, upNext } from './engine';
import { AIcon } from './icons';
import type { Track } from './api';

/* "1:48", "4:12", "1:02:09". */
export function clock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds || 0));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = String(s % 60).padStart(2, '0');
  return h ? `${h}:${String(m).padStart(2, '0')}:${sec}` : `${m}:${sec}`;
}

/* The minus sign of a remaining time ("−2:24"). */
export const remaining = (seconds: number) => '−' + clock(seconds);

export const artistsOf = (tk?: Pick<Track, 'Artists' | 'AlbumArtist'> | null) => (tk?.Artists?.length ? tk.Artists.join(', ') : tk?.AlbumArtist ?? '');

/* The first artist with an id, for links. */
export function artistLink(tk?: Pick<Track, 'ArtistItems' | 'AlbumArtists'> | null): string | undefined {
  const a = tk?.ArtistItems?.[0] ?? tk?.AlbumArtists?.[0];
  return a ? `/details/${a.Id}` : undefined;
}

/* A seek bar: the boards' track and fill (and thumb on the big players) over an invisible range input that takes the
   pointer and the arrow keys. */
export function SeekBar({ value, max, onSeek, label, valueText, class: cls, step = 1 }: {
  value: number; max: number; onSeek: (v: number) => void; label: string; valueText?: string; class?: string; step?: number;
}) {
  const [drag, setDrag] = useState<number | null>(null);
  const v = drag ?? value;
  const f = max > 0 ? Math.max(0, Math.min(1, v / max)) : 0;
  const pct = (f * 100).toFixed(3) + '%';
  return (
    <span class={'ap-seek' + (cls ? ' ' + cls : '') + (drag !== null ? ' is-dragging' : '')}>
      <span class="ap-seek-track" aria-hidden="true">
        <span class="ap-seek-fill" style={{ width: pct }} />
        <span class="ap-seek-thumb" style={{ left: pct }} />
      </span>
      <input
        type="range" min={0} max={Math.max(1, max)} step={step} value={v} aria-label={label} aria-valuetext={valueText}
        onInput={(e) => setDrag(Number((e.target as HTMLInputElement).value))}
        onChange={(e) => { const n = Number((e.target as HTMLInputElement).value); setDrag(null); onSeek(n); }}
      />
    </span>
  );
}

export function VolumeBar({ volume, muted, onChange, class: cls }: { volume: number; muted: boolean; onChange: (v: number) => void; class?: string }) {
  const v = muted ? 0 : volume;
  return (
    <span class={'ap-seek ap-vol' + (cls ? ' ' + cls : '')}>
      <span class="ap-seek-track" aria-hidden="true"><span class="ap-seek-fill" style={{ width: (v * 100).toFixed(1) + '%' }} /></span>
      <input type="range" min={0} max={100} step={1} value={Math.round(v * 100)} aria-label={t('Volume')} aria-valuetext={t('Volume {n}%', { n: Math.round(v * 100) })}
        onInput={(e) => onChange(Number((e.target as HTMLInputElement).value) / 100)} />
    </span>
  );
}

export const volumeIcon = (volume: number, muted: boolean) => (muted || volume === 0 ? 'mute' : volume < 0.5 ? 'volumeLow' : 'volume');

/* "Play on another device": hands the queue to another Jellyfin app and pauses here. */
export function CastMenu({ class: cls, size = 18, button }: { class: string; size?: number; button?: (open: boolean) => JSX.Element }) {
  const s = useStore(audioStore);
  const [sessions, setSessions] = useState<SessionInfo[] | null>(null);
  const send = async (target: SessionInfo) => {
    const cur = s.current;
    if (!cur) return;
    const ids = [cur.track.Id, ...upNext().map((e) => e.track.Id)].slice(0, 100);
    try {
      await sendPlayCommand(target.Id, ids, Math.round(position() * 10_000_000));
      pause();
      const name = target.DeviceName || target.Client || '';
      toast(name ? t('Playing on {device}', { device: name }) : t('Playing on the other device'));
    } catch {
      toast(target.DeviceName ? t("Couldn't reach {device}", { device: target.DeviceName }) : t("Couldn't reach that device"), 'error');
    }
  };
  return (
    <MenuButton
      width={300}
      button={({ ref, open, toggle }) => (
        <button ref={ref} type="button" class={cls} aria-label={t('Play on another device')} aria-expanded={open ? 'true' : 'false'}
          onClick={() => { if (!open) { setSessions(null); listCastTargets().then(setSessions, () => setSessions([])); } toggle(); }}>
          {button ? button(open) : <AIcon name="cast" size={size} />}
        </button>
      )}
    >
      {(close) => (
        <>
          <span class="menu-label">{t('Play on')}</span>
          <MenuItem label={t('This device')} sub={t('Play here')} pressed onSelect={close} />
          {sessions === null && <span class="menu-item is-muted" aria-busy="true">{t('Looking for devices…')}</span>}
          {sessions?.length === 0 && <span class="menu-item is-muted">{t('No other Jellyfin apps are open')}</span>}
          {sessions?.map((x) => (
            <MenuItem key={x.Id} label={x.DeviceName || x.Client || t('Device')} sub={[x.Client, x.UserName].filter(Boolean).join(' · ')}
              onSelect={() => { close(); void send(x); }} />
          ))}
        </>
      )}
    </MenuButton>
  );
}
