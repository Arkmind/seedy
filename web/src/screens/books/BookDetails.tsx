import { Fragment, type ComponentChildren } from 'preact';
import { useEffect, useMemo, useState } from 'preact/hooks';
import { primaryUrl } from '../../api/images';
import type { BaseItem } from '../../api/types';
import { playAudiobook } from '../../audio/engine';
import { userStore } from '../../lib/app-state';
import { dayOf, duration, pct, ticksToSeconds, timeLeft } from '../../lib/format';
import { formatNumber, locale, t, tn } from '../../lib/i18n';
import { Link, navigate } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { Header } from '../../ui/navbar/Header';
import { toast } from '../../ui/overlay';
import { MoreActions, useView } from '../browse/details-parts';
import { toggleFavorite } from '../browse/parts';
import { setPlayed } from '../../api/jellyfin';
import {
  authorLine, authorsOf, bookFileUrl, deleteReading, downloadUrl, fileSize, formatOf, getBookInfo, getReading, isAudioBook, narratorOf,
  normalize, saveReading, seriesOf, isComic, type Reading
} from './api';
import { useBooksData, VolumeCard } from './BooksLibrary';
import { epubLanguage } from './epub-meta';
import { AudioCover, BookCover } from './covers';
import { BookCard, HeadphonesIcon, roughTime, yearOfBook } from './parts';
import './books.css';
import './details.css';

/* Book details (Book details board): cover, series, title, author line, progress, Read / Listen and the toggles,
   description, the formats this work exists in, the metadata table, then the series row and more by the author.
   An audiobook uses the same page with Listen first. */

export function BookDetails({ item: initial, onChanged }: { item: BaseItem; onChanged: () => void }) {
  const [item, setItem] = useState(initial);
  useEffect(() => setItem(initial), [initial]);
  const view = useView(item);
  const lib = useBooksData(view?.Id ?? '');
  const audio = isAudioBook(item);
  const [readingTick, setReadingTick] = useState(0);
  const info = useAsync(() => (audio ? Promise.resolve(undefined) : getBookInfo(item.Id).catch(() => undefined)), [item.Id]);
  const reading = useAsync(() => (audio ? Promise.resolve(undefined) : getReading(item.Id).catch(() => undefined)), [item.Id, readingTick]);
  const me = useStore(userStore);
  const policyDownload = !!(me?.Policy as { EnableContentDownloading?: boolean } | undefined)?.EnableContentDownloading;

  /* Every file of this work in the library: the EPUB, the PDF, the audiobook… */
  const work = useMemo(() => {
    const all = lib.data?.all ?? [];
    const key = normalize(item.Name);
    const authors = new Set(authorsOf(item).map((a) => normalize(a.Name)));
    const same = all.filter((o) => normalize(o.Name) === key && (!authors.size || !authorsOf(o).length || authorsOf(o).some((a) => authors.has(normalize(a.Name)))));
    if (!same.some((o) => o.Id === item.Id)) same.unshift(item);
    const order = (o: BaseItem) => ['epub', 'audio', 'pdf', 'cbz', 'cbr', 'other'].indexOf(formatOf(o));
    return same.sort((a, b) => order(a) - order(b));
  }, [lib.data, item]);
  const audioTwin = audio ? item : work.find(isAudioBook);
  const readable = audio ? work.find((o) => formatOf(o) === 'epub') ?? work.find((o) => !isAudioBook(o)) : item;
  const twinReading = useAsync(() => (audio && readable ? getReading(readable.Id).catch(() => undefined) : Promise.resolve(undefined)), [readable?.Id, audio]);

  // An audiobook takes its series (and publisher) from the book it reads out.
  const source = audio && readable ? readable : item;
  const seriesName = seriesOf(source);
  const volume = source.IndexNumber;
  const series = useMemo(() => {
    if (!seriesName || !lib.data) return undefined;
    return lib.data.series.find((s) => s.key === normalize(seriesName));
  }, [lib.data, seriesName]);
  const author = authorsOf(item)[0];
  const more = useMemo(() => {
    if (!author || !lib.data) return [];
    const k = normalize(author.Name);
    const inSeries = new Set(series?.volumes.map((v) => v.Id) ?? []);
    const seen = new Set<string>([normalize(item.Name)]);
    return lib.data.all
      .filter((o) => !isAudioBook(o) && !inSeries.has(o.Id) && authorsOf(o).some((a) => normalize(a.Name) === k))
      .filter((o) => { const n = normalize(o.Name); if (seen.has(n)) return false; seen.add(n); return true; })
      .sort((a, b) => (yearOfBook(b) ?? 0) - (yearOfBook(a) ?? 0));
  }, [lib.data, author?.Name, series, item.Name]);

  const r = reading.data;
  const libPath = view ? `/library/${view.Id}` : '/';
  const glow = useCoverGlow(item);

  /* ── Progress line ── */
  let progress: { fraction: number; text: string } | undefined;
  if (audio) {
    const pos = item.UserData?.PlaybackPositionTicks ?? 0;
    const total = item.RunTimeTicks ?? 0;
    if (pos > 0 && total && !item.UserData?.Played) {
      const chapters = item.Chapters ?? [];
      let ci = -1;
      chapters.forEach((c, i) => { if (c.StartPositionTicks <= pos) ci = i; });
      progress = {
        fraction: pos / total,
        text: [`${Math.round((pos / total) * 100)}%`, ci >= 0 && chapters.length > 1 ? t('Chapter {n} of {total}', { n: ci + 1, total: chapters.length }) : '', timeLeft(ticksToSeconds(total - pos))].filter(Boolean).join(' · ')
      };
    }
  } else if (r && !r.Finished && r.Percent > 0) {
    const fraction = Math.min(1, r.Percent / 100);
    const minutes = info.data?.Minutes ? info.data.Minutes * (1 - fraction) : 0;
    const comic = formatOf(lib.data?.all.find((o) => o.Id === item.Id) ?? item) === 'cbz' || info.data?.Format === 'cbz';
    const pages = info.data?.Pages ?? 0;
    progress = {
      fraction,
      text: [
        `${Math.round(r.Percent)}%`,
        comic && pages ? t('Page {n} of {total}', { n: Math.min(pages, (Number(r.Locator) || 0) + 1), total: pages })
          : r.ChapterIndex && r.ChapterCount ? t('Chapter {n} of {total}', { n: r.ChapterIndex, total: r.ChapterCount }) : '',
        minutes ? t('about {time} left', { time: roughTime(minutes) }) : comic && pages ? tn(pages - (Number(r.Locator) || 0) - 1, '{n} page left', '{n} pages left') : ''
      ].filter(Boolean).join(' · ')
    };
  }

  /* ── Meta line: author · 2023 · 412 pages · English ★ 4.3 ── */
  const self = lib.data?.all.find((o) => o.Id === item.Id) ?? item;
  // Jellyfin says per title whether this profile may download it; the policy is the fallback.
  const canDownload = (self as BaseItem & { CanDownload?: boolean }).CanDownload ?? policyDownload;
  const year = yearOfBook(item);
  // The language comes from the EPUB of this work (the PDF and the audiobook share it).
  const epubId = (formatOf(self) === 'epub' ? self : work.find((o) => formatOf(o) === 'epub'))?.Id;
  const fileLang = useAsync(() => (epubId ? epubLanguage(epubId) : Promise.resolve(undefined)), [epubId]);
  const lang = languageName(fileLang.data);
  const meta = [
    year ? String(year) : '',
    audio ? (item.RunTimeTicks ? duration(ticksToSeconds(item.RunTimeTicks)) : '') : info.data?.Pages ? tn(info.data.Pages, '{n} page', '{n} pages') : '',
    lang
  ].filter(Boolean);

  const listenLabel = audioTwin?.RunTimeTicks ? t('Listen · {time}', { time: duration(ticksToSeconds(audioTwin.RunTimeTicks)) }) : t('Listen');
  const listen = () => { if (audioTwin) void playAudiobook(audioTwin.Id, { open: true }); };
  const readLabel = (rec?: Reading) => (rec && !rec.Finished && rec.Percent > 0 ? t('Continue reading') : t('Read'));

  /* ✓: books follow the reader's record (the server marks them played); audiobooks Jellyfin's played state. */
  const played = audio ? !!item.UserData?.Played : !!(r?.Finished || item.UserData?.Played);
  const togglePlayedState = async () => {
    const on = !played;
    const apply = (p: boolean) => setItem((it) => ({ ...it, UserData: { ...it.UserData, Played: p } }));
    apply(on);
    try {
      if (audio) await setPlayed(item.Id, on);
      else if (on) await saveReading(item.Id, { Locator: r?.Locator ?? '', Percent: 100, Chapter: r?.Chapter, ChapterIndex: r?.ChapterIndex, ChapterCount: r?.ChapterCount, Finished: true });
      else await deleteReading(item.Id);
      toast(on ? t('Marked {name} as read', { name: item.Name }) : t('Marked {name} as unread', { name: item.Name }));
      setReadingTick((n) => n + 1);
      lib.reload();
      onChanged();
    } catch {
      apply(!on);
      toast(t('Couldn’t update {name}', { name: item.Name }), 'error');
    }
  };

  const fav = !!item.UserData?.IsFavorite;
  const format = formatOf(self);
  const isbn = item.ProviderIds?.Isbn ?? item.ProviderIds?.ISBN ?? item.ProviderIds?.isbn;
  const added = item.DateCreated ? longDate(item.DateCreated) : undefined;
  const rows: { label: string; value?: ComponentChildren }[] = [
    { label: t('Publisher'), value: (item.Studios?.length ? item : source).Studios?.map((s) => s.Name).join(', ') },
    { label: t('Narrator'), value: audio ? narratorOf(item) : undefined },
    { label: t('Genres'), value: item.Genres?.join(' · ') },
    { label: 'ISBN', value: isbn },
    { label: t('Added'), value: added }
  ].filter((x) => x.value);

  return (
    <div class="page bkd">
      <Header active={view ? { viewId: view.Id } : null} />
      <div class="bkd-hero-wrap">
        <div aria-hidden="true" class="bkd-glow" style={{ background: `radial-gradient(900px 460px at 16% 0%, ${glow} 0%, rgba(14, 14, 14, 0) 70%)` }} />
        <section class="bkd-hero" aria-labelledby="bkd-h">
          <span class={'bkd-cover' + (audio ? ' is-audio' : '')}>
            {audio ? <AudioCover item={item} size="lg" width={600} /> : <BookCover item={item} size="lg" width={600} />}
          </span>
          <div class="bkd-info">
            {seriesName && (
              <Link to={series ? `${libPath}?tab=series&series=${encodeURIComponent(seriesName)}` : libPath} class="bkd-series">
                {isComic(self) ? t('{series} · No. {n}', { series: seriesName, n: volume! }) : t('{series} · Book {n}', { series: seriesName, n: volume! })}
              </Link>
            )}
            <h1 id="bkd-h" class="bkd-title">{item.Name}</h1>
            <span class="bkd-meta">
              {authorsOf(item).length > 0 && (
                <Link to={`${libPath}?tab=authors&author=${encodeURIComponent(authorsOf(item)[0].Name)}`} class="bkd-author">{authorLine(item)}</Link>
              )}
              {meta.length > 0 && <span>{(authorsOf(item).length ? '· ' : '') + meta.join(' · ')}</span>}
              {/* Book ratings are out of five stars; Jellyfin keeps them out of ten. */}
              {item.CommunityRating ? (
                <span class="bkd-score">
                  <svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true"><path d="M12 3.5l2.6 5.3 5.8.8-4.2 4.1 1 5.8L12 16.8l-5.2 2.7 1-5.8-4.2-4.1 5.8-.8z" fill="var(--seedy-accent)" /></svg>
                  {formatNumber(item.CommunityRating / 2, { maximumFractionDigits: 1, minimumFractionDigits: 1 })}
                </span>
              ) : null}
            </span>
            {progress && (
              <div class="bkd-progress">
                <span class="bkd-bar" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(progress.fraction * 100)} aria-label={t('Progress')}>
                  <i style={{ width: pct(progress.fraction) }} />
                </span>
                {progress.text}
              </div>
            )}
            <div class="bkd-actions">
              {audio ? (
                <>
                  <button type="button" class="bkd-btn is-primary" onClick={listen}>
                    <HeadphonesIcon size={18} />
                    {progress ? t('Continue listening') : listenLabel}
                  </button>
                  {readable && (
                    <button type="button" class="bkd-btn" onClick={() => navigate(`/read/${readable.Id}`)}>
                      <BookIcon />
                      {readLabel(twinReading.data)}
                    </button>
                  )}
                </>
              ) : (
                <>
                  <button type="button" class="bkd-btn is-primary" onClick={() => navigate(`/read/${item.Id}`)}>
                    <BookIcon />
                    {readLabel(r)}
                  </button>
                  {audioTwin && (
                    <button type="button" class="bkd-btn" onClick={listen}>
                      <HeadphonesIcon size={18} />
                      {listenLabel}
                    </button>
                  )}
                </>
              )}
              <button type="button" class="bk-icon-btn" aria-pressed={played ? 'true' : 'false'} aria-label={audio ? (played ? t('Mark as not listened') : t('Mark as listened')) : played ? t('Mark as unread') : t('Mark as read')} onClick={togglePlayedState}>
                <svg viewBox="0 0 24 24" width="20" height="20" class="bk-ic" aria-hidden="true" style={{ strokeWidth: played ? 3 : 2.2 }}><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
              </button>
              <button type="button" class="bk-icon-btn" aria-pressed={fav ? 'true' : 'false'} aria-label={fav ? t('Remove from favorites') : t('Add to favorites')} onClick={() => toggleFavorite(item, setItem)}>
                <svg viewBox="0 0 24 24" width="20" height="20" class="bk-ic" aria-hidden="true" style={{ fill: fav ? 'currentColor' : 'none' }}><path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z" /></svg>
              </button>
              {canDownload && (
                <a class="bk-icon-btn" href={downloadUrl(item.Id)} download aria-label={t('Download {format}', { format: formatName(self) })}>
                  <svg viewBox="0 0 24 24" width="20" height="20" class="bk-ic" aria-hidden="true"><path d="M12 3v12" /><path d="M7 10l5 5 5-5" /><path d="M5 20h14" /></svg>
                </a>
              )}
              <span class="bkd-more"><MoreActions item={item} /></span>
            </div>
            {item.Overview && <p class="bkd-overview">{plainText(item.Overview)}</p>}
            {work.length > 1 || format !== 'other' ? (
              <div class="bkd-formats">
                {work.map((w) => <FormatCard key={w.Id} item={w} current={w.Id === item.Id} canDownload={canDownload} />)}
              </div>
            ) : null}
            {rows.length > 0 && (
              <dl class="bkd-table">
                {rows.map((row) => <Fragment key={row.label}><dt>{row.label}</dt><dd>{row.value}</dd></Fragment>)}
              </dl>
            )}
          </div>
        </section>
      </div>

      <main class="bkd-main">
        {series && series.volumes.length > 1 && (
          <section class="bk-sec" aria-labelledby="bkd-ser">
            <div class="bk-sec-head">
              <h2 id="bkd-ser" class="bk-sec-title">{series.name}</h2>
              <Link to={`${libPath}?tab=series&series=${encodeURIComponent(series.name)}`} class="bk-sec-link">{t('Open series')}</Link>
            </div>
            <div class="bk-grid">
              {series.volumes.map((v) => (
                <div key={v.Id} class="bk-slot"><VolumeCard item={v} reading={lib.data?.reading.get(v.Id)} current={normalize(v.Name) === normalize(item.Name)} /></div>
              ))}
            </div>
          </section>
        )}
        {author && more.length > 0 && (
          <section class="bk-sec" aria-labelledby="bkd-by">
            <div class="bk-sec-head">
              <h2 id="bkd-by" class="bk-sec-title">{t('More by {name}', { name: author.Name })}</h2>
              <Link to={`${libPath}?tab=authors&author=${encodeURIComponent(author.Name)}`} class="bk-sec-link">{t('See all')}</Link>
            </div>
            <div class="bk-grid">
              {more.slice(0, 16).map((o) => (
                <div key={o.Id} class="bk-slot"><BookCard item={o} sub={[o.Genres?.[0], yearOfBook(o)].filter(Boolean).join(' · ')} /></div>
              ))}
            </div>
          </section>
        )}
      </main>
    </div>
  );
}

function BookIcon() {
  return <svg viewBox="0 0 24 24" width="18" height="18" class="bk-ic" aria-hidden="true"><path d="M4 5a2 2 0 0 1 2-2h13v16H6a2 2 0 0 0-2 2z" /><path d="M4 21a2 2 0 0 1 2-2h13v2" /></svg>;
}

function FileIcon() {
  return <svg viewBox="0 0 24 24" width="19" height="19" class="bk-ic" style={{ strokeWidth: 2 }} aria-hidden="true"><path d="M6 3h8l4 4v14H6z" /><path d="M14 3v4h4" /></svg>;
}

function formatName(item: BaseItem): string {
  const f = formatOf(item);
  if (f === 'audio') return t('Audiobook');
  if (f === 'other') return t('File');
  return f.toUpperCase();
}

/* One format of the work: EPUB "2.1 MB · reads in Seedy", Audiobook "M4B · narrated by …", PDF "8.6 MB · print edition". */
function FormatCard({ item, current, canDownload }: { item: BaseItem; current: boolean; canDownload: boolean }) {
  const f = formatOf(item);
  const size = useFileSize(item);
  const audio = f === 'audio';
  const container = ((item as { Path?: string }).Path ?? '').split('.').pop()?.toUpperCase() ?? '';
  const narrator = audio ? narratorOf(item) : undefined;
  const note = audio ? (narrator ? t('narrated by {name}', { name: narrator }) : item.RunTimeTicks ? duration(ticksToSeconds(item.RunTimeTicks)) : '')
    : f === 'epub' || f === 'cbz' ? t('reads in Seedy')
    : f === 'pdf' ? t('print edition')
    : f === 'cbr' ? t('download to read') : '';
  const sub = [audio ? container || 'M4B' : size, note].filter(Boolean).join(' · ');
  const body = (
    <>
      <span aria-hidden="true" class={'bkd-format-icon' + (audio ? ' is-accent' : '')}>
        {audio ? <HeadphonesIcon size={19} /> : f === 'epub' || f === 'cbz' ? <BookIcon /> : <FileIcon />}
      </span>
      <span class="bkd-format-text">
        <span class="bkd-format-name">{formatName(item)}</span>
        {sub && <span class="bkd-format-sub">{sub}</span>}
      </span>
    </>
  );
  const cls = 'bkd-format' + (current ? ' is-current' : '');
  if (audio) {
    return (
      <button type="button" class={cls} onClick={() => void playAudiobook(item.Id, { open: true })}>
        {body}
      </button>
    );
  }
  if (f === 'cbr' || f === 'other') {
    return canDownload ? <a class={cls} href={downloadUrl(item.Id)} download>{body}</a> : <div class={cls}>{body}</div>;
  }
  return <Link to={`/read/${item.Id}`} class={cls}>{body}</Link>;
}

/* A file's size: from its media source, else from the file endpoint (one byte of it, whose range header says the total). */
function useFileSize(item: BaseItem): string {
  const known = fileSize(item);
  const fetched = useAsync(async (signal) => {
    if (known || isAudioBook(item)) return known;
    try {
      const res = await fetch(bookFileUrl(item.Id), { headers: { Range: 'bytes=0-0' }, signal });
      const total = Number(/\/(\d+)$/.exec(res.headers.get('content-range') ?? '')?.[1] ?? res.headers.get('content-length') ?? 0);
      void res.body?.cancel();
      return total || undefined;
    } catch { return undefined; }
  }, [item.Id, known]);
  const bytes = fetched.data ?? known;
  if (!bytes) return '';
  return bytes >= 1024 * 1024
    ? `${formatNumber(bytes / (1024 * 1024), { maximumFractionDigits: 1, minimumFractionDigits: 1 })} MB`
    : `${formatNumber(Math.max(1, Math.round(bytes / 1024)))} KB`;
}

/* Jellyfin hands back the description as it was in the file, sometimes as HTML. */
function plainText(s: string): string {
  if (!/<[a-z][\s\S]*>/i.test(s)) return s;
  const doc = new DOMParser().parseFromString(s, 'text/html');
  return (doc.body.textContent ?? '').replace(/\s+\n/g, '\n').trim();
}

function longDate(s: string): string {
  const d = dayOf(s);
  if (!d) return '';
  try { return new Intl.DateTimeFormat(locale(), { day: 'numeric', month: 'long', year: 'numeric' }).format(d); } catch { return d.toDateString(); }
}

/* "English" for "en". */
function languageName(code?: string): string {
  if (!code) return '';
  try {
    const name = new Intl.DisplayNames([locale()], { type: 'language' }).of(code);
    return name ? name.charAt(0).toLocaleUpperCase() + name.slice(1) : '';
  } catch { return ''; }
}

/* The glow behind the hero takes the cover's hue, at the board's strength (its violet when there's no art to read). */
function useCoverGlow(item: BaseItem): string {
  const src = primaryUrl(item, 48);
  const [glow, setGlow] = useState('rgba(122, 63, 209, 0.38)');
  useEffect(() => {
    if (!src) return;
    let alive = true;
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => {
      if (!alive) return;
      try {
        const c = document.createElement('canvas');
        c.width = 24; c.height = 36;
        const g = c.getContext('2d', { willReadFrequently: true });
        if (!g) return;
        g.drawImage(img, 0, 0, 24, 36);
        const px = g.getImageData(0, 0, 24, 36).data;
        // Average hue, weighted by how colourful each pixel is.
        let x = 0, y = 0, w = 0;
        for (let i = 0; i < px.length; i += 4) {
          const r = px[i] / 255, gg = px[i + 1] / 255, b = px[i + 2] / 255;
          const max = Math.max(r, gg, b), min = Math.min(r, gg, b), d = max - min;
          if (d < 0.04) continue;
          const h = max === r ? ((gg - b) / d) % 6 : max === gg ? (b - r) / d + 2 : (r - gg) / d + 4;
          const weight = d * max;
          x += Math.cos((h * Math.PI) / 3) * weight;
          y += Math.sin((h * Math.PI) / 3) * weight;
          w += weight;
        }
        if (w < 1.5) return;
        const hue = ((Math.atan2(y, x) * 180) / Math.PI + 360) % 360;
        setGlow(`hsla(${Math.round(hue)}, 60%, 53%, 0.38)`);
      } catch { /* a cover from elsewhere can't be read: keep the default */ }
    };
    img.src = src;
    return () => { alive = false; };
  }, [src]);
  return glow;
}

export { BookIcon };
