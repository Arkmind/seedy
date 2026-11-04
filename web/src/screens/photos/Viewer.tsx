import { createPortal } from 'preact/compat';
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { getItem, setFavorite } from '../../api/jellyfin';
import { userStore } from '../../lib/app-state';
import { clock, duration, ticksToSeconds } from '../../lib/format';
import { t } from '../../lib/i18n';
import { Link, back, navigate, setQuery, useLocation } from '../../lib/router';
import { safeLocal, saveLocal, useStore } from '../../lib/store';
import { toast } from '../../ui/overlay';
import { deviceStore } from '../me/prefs';
import { patchUserData } from '../browse/parts';
import {
  PHOTO_FIELDS, bytesLabel, cameraName, coordsLabel, exposureLine, fileInfo, fileName, folderInfo, formatName, isVideo, longDay, ratioOf,
  screenUrl, takenAt, thumbUrl, downloadUrl, type PhotoItem
} from './api';
import { PIcon, type PIconName } from './icons';
import { clockOf, viewerOpened } from './parts';

/* The photo viewer (Photos · Viewer board): the photo over black with Close, its name, date and place in the list,
   Favorite, Rotate, Slideshow, Download and Details; Previous and Next at the sides, the neighbours in a strip below,
   and the Details panel on the right (date, camera, file, folder, where it was taken, the folder it's in).
   It opens over the page (?photo=<id>, Back closes it): arrows and ←/→ move, the wheel, a double click, +/− and a
   pinch zoom, a drag pans a zoomed photo or swipes to the next one, Space plays the slideshow (?play=1), I shows the
   details, Esc closes. */

interface Props {
  list: PhotoItem[];
  /* Everything in the list, loaded or not ("4 of 226"). */
  total?: number;
  /* Load the next page when the viewer nears the end of what's loaded. */
  more?: () => void;
  done?: boolean;
  /* A photo changed here (favourite). */
  onItem?: (it: PhotoItem) => void;
}

const INFO_KEY = 'seedy.photos.info';
const MAX_ZOOM = 8;

interface View { s: number; x: number; y: number }
const NONE: View = { s: 1, x: 0, y: 0 };

export function PhotoViewer({ list, total, more, done, onItem }: Props) {
  const loc = useLocation();
  const id = loc.query.get('photo') ?? '';
  const playing = loc.query.get('play') === '1';
  const user = useStore(userStore);
  const device = useStore(deviceStore);
  const canDownload = !!(user?.Policy as { EnableContentDownloading?: boolean } | undefined)?.EnableContentDownloading;
  const index = list.findIndex((x) => x.Id === id);
  const [alone, setAlone] = useState<PhotoItem>();
  const [local, setLocal] = useState<Record<string, PhotoItem>>({});
  const listed = index >= 0 ? list[index] : undefined;
  const raw = listed ?? (alone?.Id === id ? alone : undefined);
  const item = raw ? local[raw.Id] ?? raw : undefined;
  const [info, setInfo] = useState(() => safeLocal<boolean>(INFO_KEY, typeof window !== 'undefined' && window.innerWidth >= 1100));
  const [rot, setRot] = useState<Record<string, number>>({});
  const [view, setView] = useState<View>(NONE);
  const [idle, setIdle] = useState(false);

  // A photo that isn't in the page's list yet (a link to it): fetch it on its own.
  useEffect(() => {
    if (!id || index >= 0 || alone?.Id === id) return;
    getItem(id, PHOTO_FIELDS).then((x) => setAlone(x as PhotoItem), () => undefined);
  }, [id, index >= 0]);

  // Near the end of what's loaded: load more.
  useEffect(() => {
    if (index >= 0 && !done && more && index >= list.length - 6) more();
  }, [index, list.length, done]);

  // Back to fit when the photo changes.
  useEffect(() => setView(NONE), [id]);

  // The page doesn't scroll under the viewer.
  useEffect(() => {
    const html = document.documentElement;
    const prev = html.style.overflow;
    html.style.overflow = 'hidden';
    return () => { html.style.overflow = prev; };
  }, []);

  const step = (d: number, wrap = false) => {
    if (index < 0) return;
    let next = index + d;
    if (next >= list.length) {
      if (!done && more) { more(); return; }
      if (!wrap) return;
      next = 0;
    }
    if (next < 0) { if (!wrap) return; next = list.length - 1; }
    setQuery({ photo: list[next].Id }, true);
  };
  const close = () => {
    if (viewerOpened.current) { viewerOpened.current = false; back(); }
    else setQuery({ photo: undefined, play: undefined }, true);
  };
  const setPlaying = (on: boolean) => setQuery({ play: on ? '1' : undefined }, true);
  const toggleInfo = () => setInfo((v) => { saveLocal(INFO_KEY, !v); return !v; });
  const rotate = () => item && setRot((r) => ({ ...r, [item.Id]: ((r[item.Id] ?? 0) + 270) % 360 }));
  const favorite = async () => {
    if (!item) return;
    const on = !item.UserData?.IsFavorite;
    const next = patchUserData(item, { IsFavorite: on }) as PhotoItem;
    setLocal((m) => ({ ...m, [item.Id]: next }));
    onItem?.(next);
    try { await setFavorite(item.Id, on); } catch {
      setLocal((m) => ({ ...m, [item.Id]: item }));
      onItem?.(item);
      toast(t('Couldn’t update {name}', { name: item.Name }), 'error');
    }
  };

  // Slideshow: the next photo (videos skipped) after the device's "Photo slideshows" time; round again at the end.
  useEffect(() => {
    if (!playing || index < 0) return;
    const every = Math.max(2, device.slideshowEvery || 5) * 1000;
    const timer = window.setTimeout(() => {
      let next = index + 1;
      while (next < list.length && isVideo(list[next])) next++;
      if (next >= list.length) {
        if (!done && more) { more(); return; }
        next = list.findIndex((x) => !isVideo(x));
        if (next < 0) return;
      }
      setQuery({ photo: list[next].Id }, true);
    }, every);
    return () => window.clearTimeout(timer);
  }, [playing, id, index, list.length, done, device.slideshowEvery]);

  // While the slideshow plays, the controls fade after a moment without the mouse.
  useEffect(() => {
    if (!playing) { setIdle(false); return; }
    let timer = window.setTimeout(() => setIdle(true), 2500);
    const wake = () => { setIdle(false); window.clearTimeout(timer); timer = window.setTimeout(() => setIdle(true), 2500); };
    window.addEventListener('pointermove', wake);
    window.addEventListener('keydown', wake);
    return () => { window.clearTimeout(timer); window.removeEventListener('pointermove', wake); window.removeEventListener('keydown', wake); };
  }, [playing]);

  // Keys.
  const keys = useRef<(e: KeyboardEvent) => void>(() => undefined);
  keys.current = (e: KeyboardEvent) => {
    if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement || e.altKey || e.metaKey || e.ctrlKey) return;
    const k = e.key;
    let done = true;
    if (k === 'ArrowRight') step(1);
    else if (k === 'ArrowLeft') step(-1);
    else if (k === 'Escape') { if (view.s > 1) setView(NONE); else close(); }
    else if (k === ' ') setPlaying(!playing);
    else if (k === 'i' || k === 'I') toggleInfo();
    else if (k === 'f' || k === 'F') void favorite();
    else if (k === 'r' || k === 'R') rotate();
    else if (k === '+' || k === '=') setView((v) => zoomTo(v, v.s * 1.5));
    else if (k === '-' || k === '_') setView((v) => zoomTo(v, v.s / 1.5));
    else if (k === '0') setView(NONE);
    else done = false;
    if (done) { e.preventDefault(); e.stopPropagation(); }
  };
  useEffect(() => {
    const on = (e: KeyboardEvent) => keys.current(e);
    document.addEventListener('keydown', on, true);
    return () => document.removeEventListener('keydown', on, true);
  }, []);

  // The neighbours load ahead.
  useEffect(() => {
    for (const d of [1, -1, 2]) {
      const n = list[index + d];
      const src = n && !isVideo(n) ? screenUrl(n) : undefined;
      if (src) { const img = new Image(); img.src = src; }
    }
  }, [index]);

  const taken = item ? takenAt(item) : undefined;
  const count = total ?? list.length;
  const sub = [taken ? longDay(taken) : '', taken ? clock(taken) : '', index >= 0 ? t('{n} of {total}', { n: index + 1, total: count }) : ''].filter(Boolean).join(' · ');

  return createPortal(
    <div class={'ph-viewer' + (info ? ' has-info' : '') + (idle ? ' is-idle' : '') + (playing ? ' is-playing' : '')} role="dialog" aria-modal="true" aria-label={item ? fileName(item) : t('Photo')}>
      <div class="ph-v-main">
        <div class="ph-v-top">
          <VButton icon="close" size={18} label={t('Close')} onClick={close} />
          <span class="ph-v-title">
            <span class="ph-v-name">{item ? fileName(item) : ' '}</span>
            <span class="ph-v-sub">{sub || ' '}</span>
          </span>
          <VButton icon="heart" size={20} filled={!!item?.UserData?.IsFavorite} on={!!item?.UserData?.IsFavorite} label={item?.UserData?.IsFavorite ? t('Remove from favorites') : t('Add to favorites')} onClick={() => void favorite()} />
          <VButton icon="rotate" size={18} label={t('Rotate')} onClick={rotate} disabled={!item || isVideo(item)} />
          <VButton icon={playing ? 'pause' : 'slideshow'} size={18} label={playing ? t('Pause slideshow') : t('Start slideshow')} onClick={() => setPlaying(!playing)} />
          {canDownload && item && (
            <a class="ph-v-btn" href={downloadUrl(item.Id)} download aria-label={t('Download original')} title={t('Download original')}>
              <PIcon name="download" size={18} />
            </a>
          )}
          <VButton icon="info" size={20} label={info ? t('Hide details') : t('Show details')} pressed={info} onClick={toggleInfo} />
        </div>

        {item && <Stage key={item.Id} item={item} rotation={rot[item.Id] ?? 0} view={view} setView={setView} onSwipe={(d) => step(d)} />}

        <button type="button" class="ph-v-nav is-prev" aria-label={t('Previous photo')} disabled={index <= 0} onClick={() => step(-1)}><PIcon name="prev" size={24} /></button>
        <button type="button" class="ph-v-nav is-next" aria-label={t('Next photo')} disabled={index < 0 || (index >= list.length - 1 && !!done)} onClick={() => step(1)}><PIcon name="next" size={24} /></button>

        {index >= 0 && <Strip list={list} index={index} />}
      </div>

      {info && item && <Details item={item} />}
    </div>,
    document.body
  );
}

function VButton({ icon, size, label, onClick, pressed, on, filled, disabled }: { icon: PIconName; size: number; label: string; onClick: () => void; pressed?: boolean; on?: boolean; filled?: boolean; disabled?: boolean }) {
  return (
    <button type="button" class={'ph-v-btn' + (on ? ' is-on' : '')} aria-label={label} title={label} aria-pressed={pressed === undefined ? undefined : pressed ? 'true' : 'false'} onClick={onClick} disabled={disabled}>
      <PIcon name={icon} size={size} filled={filled} />
    </button>
  );
}

const clamp = (v: number, a: number, b: number) => Math.min(b, Math.max(a, v));
function zoomTo(v: View, s: number): View {
  const n = clamp(s, 1, MAX_ZOOM);
  if (n === 1) return NONE;
  const k = n / v.s;
  return { s: n, x: v.x * k, y: v.y * k };
}

/* The photo itself, fitted into the space between the bars, zoomed and panned with a transform. */
function Stage({ item, rotation, view, setView, onSwipe }: { item: PhotoItem; rotation: number; view: View; setView: (v: View | ((p: View) => View)) => void; onSwipe: (d: number) => void }) {
  const box = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ w: 0, h: 0 });
  const [loaded, setLoaded] = useState(false);
  const [hiReady, setHiReady] = useState(false);
  const [drag, setDrag] = useState(0);
  const video = isVideo(item);
  const turned = rotation % 180 !== 0;
  const ar0 = ratioOf(item);
  const ar = turned ? 1 / ar0 : ar0;

  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const measure = () => setSize({ w: el.clientWidth, h: el.clientHeight });
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const fw = Math.min(size.w, size.h * ar);
  const fh = fw / ar;
  const limit = (v: View): View => {
    const mx = Math.max(0, (fw * v.s - size.w) / 2);
    const my = Math.max(0, (fh * v.s - size.h) / 2);
    return { s: v.s, x: clamp(v.x, -mx, mx), y: clamp(v.y, -my, my) };
  };
  // Zoom about a point given relative to the box's centre.
  const zoomAt = (v: View, s: number, px: number, py: number): View => {
    const n = clamp(s, 1, MAX_ZOOM);
    if (n === 1) return NONE;
    const k = n / v.s;
    return limit({ s: n, x: px - (px - v.x) * k, y: py - (py - v.y) * k });
  };
  const rel = (e: { clientX: number; clientY: number }) => {
    const r = box.current!.getBoundingClientRect();
    return [e.clientX - r.left - r.width / 2, e.clientY - r.top - r.height / 2];
  };

  // Wheel zoom (listened to directly: Preact's onWheel is passive).
  const viewRef = useRef(view);
  viewRef.current = view;
  useEffect(() => {
    const el = box.current;
    if (!el || video) return;
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const [px, py] = rel(e);
      const factor = Math.exp(-e.deltaY * (e.deltaMode === 1 ? 0.05 : 0.0022));
      setView((v) => zoomAt(v, v.s * factor, px, py));
    };
    el.addEventListener('wheel', onWheel, { passive: false });
    return () => el.removeEventListener('wheel', onWheel);
  }, [video, fw, fh, size.w, size.h]);

  // Pointers: one pans (zoomed) or swipes (not zoomed); two pinch.
  const pointers = useRef(new Map<number, { x: number; y: number }>());
  const gesture = useRef<{ x: number; y: number; v: View; dist?: number; mid?: [number, number]; moved: boolean; t: number } | null>(null);
  const onDown = (e: PointerEvent) => {
    if (video || e.button > 0) return;
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const pts = [...pointers.current.values()];
    if (pts.length === 2) {
      const [a, b] = pts;
      gesture.current = { x: 0, y: 0, v: viewRef.current, dist: Math.hypot(a.x - b.x, a.y - b.y), mid: rel({ clientX: (a.x + b.x) / 2, clientY: (a.y + b.y) / 2 }) as [number, number], moved: true, t: Date.now() };
      setDrag(0);
    } else {
      gesture.current = { x: e.clientX, y: e.clientY, v: viewRef.current, moved: false, t: Date.now() };
    }
  };
  const onMove = (e: PointerEvent) => {
    if (!pointers.current.has(e.pointerId) || !gesture.current) return;
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const g = gesture.current;
    const pts = [...pointers.current.values()];
    if (pts.length >= 2 && g.dist) {
      const [a, b] = pts;
      const d = Math.hypot(a.x - b.x, a.y - b.y);
      setView(zoomAt(g.v, g.v.s * (d / g.dist), g.mid![0], g.mid![1]));
      return;
    }
    const dx = e.clientX - g.x, dy = e.clientY - g.y;
    if (Math.abs(dx) + Math.abs(dy) > 4) g.moved = true;
    if (g.v.s > 1) setView(limit({ s: g.v.s, x: g.v.x + dx, y: g.v.y + dy }));
    else setDrag(dx);
  };
  const onUp = (e: PointerEvent) => {
    pointers.current.delete(e.pointerId);
    const g = gesture.current;
    if (!g) return;
    if (pointers.current.size === 1 && g.dist) {
      // From a pinch back to one finger: pan from here.
      const [p] = [...pointers.current.values()];
      gesture.current = { x: p.x, y: p.y, v: viewRef.current, moved: true, t: Date.now() };
      return;
    }
    if (pointers.current.size) return;
    gesture.current = null;
    if (g.v.s <= 1 && !g.dist) {
      const dx = e.clientX - g.x;
      const fast = Math.abs(dx) > 30 && Date.now() - g.t < 250;
      if (Math.abs(dx) > 70 || fast) onSwipe(dx < 0 ? 1 : -1);
      setDrag(0);
    }
  };
  const onDouble = (e: MouseEvent) => {
    if (video) return;
    const [px, py] = rel(e);
    setView(view.s > 1 ? NONE : zoomAt(view, 2.5, px, py));
  };

  // Zoomed in: the full-size photo replaces the screen-sized one once it's here.
  const wantHi = view.s > 1.25;
  const hi = wantHi || hiReady ? screenUrl(item, true) : undefined;
  const src = video ? thumbUrl(item, 1080) : screenUrl(item);
  const frame = { width: `${turned ? fh : fw}px`, height: `${turned ? fw : fh}px`, transform: `translate(-50%, -50%) rotate(${rotation}deg)` };

  return (
    <div
      ref={box}
      class={'ph-v-stage' + (view.s > 1 ? ' is-zoomed' : '')}
      onPointerDown={onDown as never}
      onPointerMove={onMove as never}
      onPointerUp={onUp as never}
      onPointerCancel={onUp as never}
      onDblClick={onDouble as never}
    >
      <div class="ph-v-pan" style={{ transform: `translate(${view.x + drag}px, ${view.y}px) scale(${view.s})`, transition: gesture.current ? 'none' : undefined }}>
        <div class={'ph-v-photo' + (loaded ? ' is-loaded' : '')} style={frame} role="img" aria-label={item.Name}>
          {src && <img src={src} alt="" draggable={false} onLoad={() => setLoaded(true)} />}
          {hi && <img src={hi} alt="" draggable={false} class={'ph-v-hi' + (hiReady ? ' is-ready' : '')} onLoad={() => setHiReady(true)} />}
          {video && (
            <button type="button" class="ph-v-play" aria-label={t('Play {name}', { name: item.Name })} onClick={() => navigate(`/play/${item.Id}`)}>
              <PIcon name="play" size={34} />
              <span>{clockOf(ticksToSeconds(item.RunTimeTicks))}</span>
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

/* The strip of neighbours: eleven around the current one, as on the board. */
function Strip({ list, index }: { list: PhotoItem[]; index: number }) {
  const start = Math.max(0, Math.min(index - 4, list.length - 11));
  const shown = list.slice(start, start + 11);
  return (
    <div class="ph-v-strip" role="list" aria-label={t('Photos in this view')}>
      {shown.map((it, i) => {
        const cur = start + i === index;
        const ar = ratioOf(it);
        return (
          <a
            key={it.Id}
            role="listitem"
            href={`?photo=${it.Id}`}
            aria-label={it.Name}
            aria-current={cur ? 'true' : undefined}
            class={'ph-v-thumb' + (cur ? ' is-current' : '')}
            style={{ width: `${Math.round(ar * 72)}px` }}
            onClick={(e) => { e.preventDefault(); setQuery({ photo: it.Id }, true); }}
          >
            <img src={thumbUrl(it, 72)} alt="" loading="lazy" draggable={false} />
          </a>
        );
      })}
    </div>
  );
}

/* ───────── Details ───────── */

function Fact({ icon, title, sub }: { icon: PIconName; title: string; sub?: string }) {
  return (
    <div class="ph-fact">
      <span class="ph-fact-icon" aria-hidden="true"><PIcon name={icon} size={19} stroke={2} /></span>
      <span class="ph-fact-text"><span class="ph-fact-title">{title}</span>{sub && <span class="ph-fact-sub">{sub}</span>}</span>
    </div>
  );
}

function Details({ item }: { item: PhotoItem }) {
  const [file, setFile] = useState<{ size?: number; type?: string }>({});
  const [folderCover, setFolderCover] = useState<PhotoItem>();
  useEffect(() => { setFile({}); fileInfo(item.Id).then(setFile); }, [item.Id]);
  useEffect(() => {
    setFolderCover(undefined);
    if (item.AlbumId) folderInfo({ Id: item.AlbumId, Name: item.Album ?? '', Type: 'PhotoAlbum' }, false).then((f) => setFolderCover(f.covers[0]), () => undefined);
  }, [item.AlbumId]);
  const taken = takenAt(item);
  const video = isVideo(item);
  const w = item.Width, h = item.Height;
  const mp = w && h ? Math.round((w * h) / 1e6) : 0;
  const camera = cameraName(item);
  const folderPath = item.Path ? item.Path : '';
  const folderName = item.Album || (item.Path ? item.Path.split(/[\\/]/).slice(-2, -1)[0] : '');
  const geo = item.Latitude !== undefined && item.Longitude !== undefined && item.Latitude !== null && item.Longitude !== null && !(item.Latitude === 0 && item.Longitude === 0);
  const sizeLine = [w && h ? `${w} × ${h}` : '', video ? duration(ticksToSeconds(item.RunTimeTicks)) : mp ? `${mp} MP` : ''].filter(Boolean).join(' · ');
  const fileLine = [formatName(item, file.type), bytesLabel(file.size)].filter(Boolean).join(' · ');
  // The folder's first photo, or this one when that has no picture.
  const cover = folderCover?.ImageTags?.Primary ? folderCover : item;
  return (
    <aside class="ph-v-info" aria-labelledby="ph-info-h">
      <h2 id="ph-info-h">{t('Details')}</h2>
      {taken && <Fact icon="calendar" title={`${longDay(taken)}, ${clock(taken)}`} sub={item.PremiereDate ? t('Camera’s local time') : t('Date the file was saved')} />}
      {camera && <Fact icon="camera" title={camera} sub={exposureLine(item) || undefined} />}
      {(sizeLine || fileLine) && <Fact icon={video ? 'video' : 'file'} title={sizeLine || fileLine} sub={sizeLine ? fileLine : undefined} />}
      {folderName && <Fact icon="folder" title={folderName} sub={folderPath || undefined} />}
      {geo && <MapCard lat={item.Latitude!} lon={item.Longitude!} />}
      {item.AlbumId && (
        <Link to={`/details/${item.AlbumId}`} class="ph-infolder">
          <span class="ph-infolder-art"><img src={thumbUrl(cover, 52)} alt="" draggable={false} /></span>
          <span class="ph-infolder-text"><span>{t('In folder')}</span><span>{item.Album}</span></span>
          <PIcon name="chevronRight" size={16} stroke={2.4} />
        </Link>
      )}
    </aside>
  );
}

/* Where it was taken: a drawn map with the pin and the coordinates; it opens the place on OpenStreetMap. */
function MapCard({ lat, lon }: { lat: number; lon: number }) {
  const label = coordsLabel(lat, lon);
  const href = `https://www.openstreetmap.org/?mlat=${lat.toFixed(5)}&mlon=${lon.toFixed(5)}#map=13/${lat.toFixed(5)}/${lon.toFixed(5)}`;
  return (
    <div class="ph-where">
      <span class="ph-where-h">{t('Where it was taken')}</span>
      <a class="ph-map" href={href} target="_blank" rel="noreferrer" aria-label={t('Open {place} on a map', { place: label })}>
        <svg viewBox="0 0 312 150" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
          <rect width="312" height="150" fill="#1a2229" />
          <path d="M0 104 Q80 92 150 110 T312 100 V150 H0Z" fill="#173447" />
          <path d="M20 0 L60 150 M120 0 L95 150 M210 0 L240 150 M0 40 H312 M0 78 H312" stroke="#26313a" stroke-width="3" />
          <circle cx="150" cy="62" r="16" style={{ fill: 'var(--seedy-accent)' }} opacity="0.25" />
          <circle cx="150" cy="62" r="7" style={{ fill: 'var(--seedy-accent)' }} />
        </svg>
        <span class="ph-map-label">{label}</span>
      </a>
    </div>
  );
}
