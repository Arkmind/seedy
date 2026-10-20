import { useRef, useState } from 'preact/hooks';
import { trickplayTileUrl } from '../api/images';
import type { BaseItem, Chapter, MediaSegment, TrickplayInfo } from '../api/types';
import { pct, ticksToSeconds, timecode } from '../lib/format';
import { t } from '../lib/i18n';

/* Full-width scrubber from the Player board: buffered, intro/credits tints, chapter gaps, thumb, and a
   hover card with the trickplay frame and chapter name. A visually hidden range input carries keyboard
   and screen-reader access. */

export interface Trick { width: number; info: TrickplayInfo }

export function trickplayFor(item: BaseItem, mediaSourceId?: string): Trick | null {
  const all = item.Trickplay;
  if (!all) return null;
  const byWidth = (mediaSourceId && all[mediaSourceId]) || Object.values(all)[0];
  if (!byWidth) return null;
  const widths = Object.keys(byWidth).map(Number).filter((n) => Number.isFinite(n)).sort((a, b) => a - b);
  if (!widths.length) return null;
  const width = widths.find((w) => w >= 320) ?? widths[widths.length - 1];
  const info = byWidth[String(width)];
  return info ? { width, info } : null;
}

export function chapterAt(chapters: Chapter[], seconds: number): number {
  let idx = -1;
  for (let i = 0; i < chapters.length; i++) {
    if (ticksToSeconds(chapters[i].StartPositionTicks) <= seconds + 0.001) idx = i;
    else break;
  }
  return idx;
}

export function chapterLabel(chapters: Chapter[], i: number): string {
  if (i < 0) return '';
  const name = chapters[i].Name?.trim();
  if (!name || /^chapter\s*\d+$/i.test(name)) return t('Chapter {n}', { n: i + 1 });
  return t('Chapter {n} · {name}', { n: i + 1, name });
}

/* One trickplay frame, cropped from its tile sheet, fitted into a box of `boxWidth` × 16:9. */
export function TrickFrame({ item, trick, seconds, boxWidth, mediaSourceId }: {
  item: BaseItem; trick: Trick; seconds: number; boxWidth: number; mediaSourceId?: string;
}) {
  const { info, width } = trick;
  const perSheet = info.TileWidth * info.TileHeight;
  const n = Math.max(0, Math.min(info.ThumbnailCount - 1, Math.floor((seconds * 1000) / info.Interval)));
  const sheet = Math.floor(n / perSheet);
  const within = n % perSheet;
  const col = within % info.TileWidth;
  const row = Math.floor(within / info.TileWidth);
  const boxHeight = (boxWidth * 9) / 16;
  const aspect = info.Width / info.Height;
  const w = aspect >= 16 / 9 ? boxWidth : boxHeight * aspect;
  const scale = w / info.Width;
  const h = info.Height * scale;
  // The tile URL already carries the token: a second ApiKey makes Jellyfin read "token,token" and refuse it (401).
  const src = trickplayTileUrl(item.Id, width, sheet, mediaSourceId);
  return (
    <span
      class="pl-trick"
      style={{
        width: w + 'px',
        height: h + 'px',
        backgroundImage: `url("${src}")`,
        backgroundSize: `${info.TileWidth * info.Width * scale}px ${info.TileHeight * info.Height * scale}px`,
        backgroundPosition: `${-col * info.Width * scale}px ${-row * info.Height * scale}px`
      }}
    />
  );
}

const PREVIEW_W = 224;

export function Scrubber({
  item, time, duration, buffered, chapters, segments, mediaSourceId, onSeek, onScrub, onDrag, chapterImage
}: {
  item: BaseItem;
  time: number;
  duration: number;
  buffered: number;
  chapters: Chapter[];
  segments: MediaSegment[];
  mediaSourceId?: string;
  onSeek: (seconds: number) => void;
  onScrub?: (active: boolean) => void;
  /* Position under the finger/pointer while dragging (null when released). */
  onDrag?: (seconds: number | null) => void;
  chapterImage?: (index: number) => string | undefined;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [hover, setHover] = useState<number | null>(null);
  const [drag, setDrag] = useState<number | null>(null);
  const trick = trickplayFor(item, mediaSourceId);

  const fracAt = (clientX: number) => {
    const r = ref.current!.getBoundingClientRect();
    return Math.max(0, Math.min(1, (clientX - r.left) / r.width));
  };

  const d = duration > 0 ? duration : 0;
  const shown = drag !== null ? drag * d : time;
  const playedF = d ? Math.min(1, shown / d) : 0;
  const bufF = d ? Math.min(1, Math.max(buffered, shown) / d) : 0;
  const peek = drag ?? hover;
  const peekSec = peek !== null ? peek * d : 0;
  const chIdx = peek !== null ? chapterAt(chapters, peekSec) : -1;
  const chImg = peek !== null && !trick && chIdx >= 0 ? chapterImage?.(chIdx) : undefined;

  /* Keep the preview card inside the bar. */
  const width = ref.current?.offsetWidth ?? 0;
  const half = PREVIEW_W / 2;
  const cardLeft = peek !== null && width ? Math.max(half, Math.min(width - half, peek * width)) : 0;

  const segs = segments
    .filter((s) => s.Type === 'Intro' || s.Type === 'Outro' || s.Type === 'Recap')
    .map((s) => {
      const a = d ? Math.max(0, ticksToSeconds(s.StartTicks) / d) : 0;
      const b = d ? Math.min(1, ticksToSeconds(s.EndTicks) / d) : 0;
      return { type: s.Type, left: a, width: Math.max(0, b - a), toEnd: b >= 0.995, fromStart: a <= 0.005 };
    })
    .filter((s) => s.width > 0);

  return (
    <div
      ref={ref}
      class={'pl-scrub' + (peek !== null ? ' is-peek' : '')}
      onPointerMove={(e) => {
        if (e.pointerType === 'touch' && drag === null) return;
        const f = fracAt(e.clientX);
        if (drag !== null) { setDrag(f); onDrag?.(f * d); }
        else setHover(f);
      }}
      onPointerLeave={() => { if (drag === null) setHover(null); }}
      onPointerDown={(e) => {
        if (e.button !== 0 || !d) return;
        (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
        const f = fracAt(e.clientX);
        setDrag(f);
        onDrag?.(f * d);
        onScrub?.(true);
      }}
      onPointerUp={(e) => {
        if (drag === null) return;
        const f = fracAt(e.clientX);
        setDrag(null);
        onDrag?.(null);
        if (e.pointerType === 'touch') setHover(null);
        onScrub?.(false);
        onSeek(f * d);
      }}
      onPointerCancel={() => { setDrag(null); setHover(null); onDrag?.(null); onScrub?.(false); }}
    >
      <div class="pl-track">
        <div class="pl-buffered" style={{ width: pct(bufF) }} />
        {segs.map((s, i) => (
          <div
            key={i}
            title={t(s.type === 'Outro' ? 'Credits' : s.type)}
            class={'pl-seg' + (s.type === 'Outro' ? ' is-credits' : '') + (s.toEnd ? ' is-end' : '') + (s.fromStart ? ' is-start' : '')}
            style={{ left: pct(s.left), width: pct(s.width) }}
          />
        ))}
        <div class="pl-played" style={{ width: pct(playedF) }} />
        {d > 0 && chapters.slice(1).map((c, i) => {
          const f = ticksToSeconds(c.StartPositionTicks) / d;
          return f > 0 && f < 1 ? <span key={i} class="pl-gap" style={{ left: pct(f) }} /> : null;
        })}
        <span aria-hidden="true" class="pl-thumb" style={{ left: pct(playedF) }} />
        {peek !== null && (
          <>
            <span aria-hidden="true" class="pl-hoverline" style={{ left: pct(peek) }} />
            <div aria-hidden="true" class="pl-preview" style={{ left: cardLeft + 'px' }}>
              {(trick || chImg) && (
                <div class="pl-preview-art">
                  {trick ? (
                    <TrickFrame item={item} trick={trick} seconds={peekSec} boxWidth={PREVIEW_W - 14} mediaSourceId={mediaSourceId} />
                  ) : (
                    <img src={chImg} alt="" />
                  )}
                </div>
              )}
              <div class="pl-preview-text">
                <span class="pl-preview-time">{timecode(peekSec)}</span>
                {chIdx >= 0 && <span class="pl-preview-chapter">{chapterLabel(chapters, chIdx)}</span>}
              </div>
            </div>
          </>
        )}
      </div>
      <input
        type="range"
        class="pl-range"
        min={0}
        max={Math.max(1, Math.round(d))}
        step={1}
        value={Math.round(shown)}
        aria-label={t('Seek')}
        aria-valuetext={t('{time} of {total}', { time: timecode(shown), total: timecode(d) })}
        onKeyDown={(e) => {
          /* Arrow keys bubble to the player, which seeks by the configured seconds. */
          if (e.key === 'Home') { e.preventDefault(); onSeek(0); }
          if (e.key === 'End') { e.preventDefault(); onSeek(Math.max(0, d - 1)); }
          if (e.key === 'PageUp') { e.preventDefault(); onSeek(Math.min(d, time + 60)); }
          if (e.key === 'PageDown') { e.preventDefault(); onSeek(Math.max(0, time - 60)); }
        }}
        onChange={(e) => onSeek(Number((e.currentTarget as HTMLInputElement).value))}
      />
    </div>
  );
}

/* 110 px volume slider with a 14 px knob. */
export function VolumeSlider({ value, muted, onChange }: { value: number; muted: boolean; onChange: (v: number) => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const [dragging, setDragging] = useState(false);
  const v = muted ? 0 : value;
  const at = (x: number) => {
    const r = ref.current!.getBoundingClientRect();
    return Math.max(0, Math.min(1, (x - r.left) / r.width));
  };
  return (
    <div
      ref={ref}
      class="pl-vol"
      onPointerDown={(e) => {
        if (e.button !== 0) return;
        (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
        setDragging(true);
        onChange(at(e.clientX));
      }}
      onPointerMove={(e) => { if (dragging) onChange(at(e.clientX)); }}
      onPointerUp={() => setDragging(false)}
      onPointerCancel={() => setDragging(false)}
    >
      <div class="pl-vol-track">
        <div class="pl-vol-fill" style={{ width: pct(v) }} />
        <span aria-hidden="true" class="pl-vol-knob" style={{ left: pct(v) }} />
      </div>
      <input
        type="range"
        class="pl-range"
        data-keys="native"
        min={0}
        max={100}
        step={5}
        value={Math.round(v * 100)}
        aria-label={t('Volume')}
        onInput={(e) => onChange(Number((e.currentTarget as HTMLInputElement).value) / 100)}
      />
    </div>
  );
}
