import { useEffect, useState } from 'preact/hooks';
import '@fontsource-variable/noto-serif/wght.css';
import { getItem, DETAIL_FIELDS } from '../../../api/jellyfin';
import type { BaseItem } from '../../../api/types';
import { userStore } from '../../../lib/app-state';
import { t } from '../../../lib/i18n';
import { back } from '../../../lib/router';
import { useAsync, useStore } from '../../../lib/store';
import { downloadUrl, formatOf, getBookFile, getBookInfo, getReading, isAudioBook, saveReading, type BookInfo, type Reading } from '../api';
import { BarButton, ReaderMessage, RdIcon, TopBar, useFullscreen } from './chrome';
import { ComicReader, NoPages } from './comic';
import { EpubReader } from './epub';
import './reader.css';

/* /read/:id — the reader for a book: EPUB (epub.js), comics (CBZ pages), PDF (the browser's own viewer), and a clear
   "download it" for what the browser can't open (CBR). Loaded on first use, like the player. */

export function Reader({ id }: { id: string }) {
  const item = useAsync(() => getItem(id, [...DETAIL_FIELDS, 'Path']), [id]);
  const info = useAsync(() => getBookInfo(id).catch(() => undefined), [id]);
  const reading = useAsync(() => getReading(id).catch(() => undefined), [id]);
  useEffect(() => {
    document.documentElement.classList.add('rd-open');
    return () => document.documentElement.classList.remove('rd-open');
  }, []);

  if (item.error) {
    return (
      <div class="rd rd-theme-dark">
        <TopBar title={t('Book')} onBack={() => back('/')} />
        <ReaderMessage title={t('This book isn’t here')} text={t('It may have been removed from the server, or it’s outside what this profile can see.')} />
      </div>
    );
  }
  if (!item.data || info.loading || reading.loading) return <div class="rd rd-theme-dark"><div class="rd-loading"><span class="spinner" aria-label={t('Opening the book')} /></div></div>;
  const it = item.data;
  if (isAudioBook(it)) return <Unsupported item={it} audio />;
  const format = info.data?.Format && info.data.Format !== 'other' ? info.data.Format : formatOf(it);
  if (format === 'epub') return <EpubReader item={it} info={info.data} reading={reading.data} />;
  if (format === 'cbz') return info.data?.Pages ? <ComicReader item={it} info={info.data} reading={reading.data} /> : <NoPages item={it} />;
  if (format === 'pdf') return <PdfReader item={it} info={info.data} reading={reading.data} />;
  return <Unsupported item={it} />;
}

function canDownload(): boolean {
  return !!(userStore.get()?.Policy as { EnableContentDownloading?: boolean } | undefined)?.EnableContentDownloading;
}

/* PDF: the browser's viewer in a frame, from a copy of the file (the file endpoint asks browsers to save it). */
function PdfReader({ item, reading }: { item: BaseItem; info?: BookInfo; reading?: Reading }) {
  const [src, setSrc] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  const [full, toggleFull] = useFullscreen();
  useStore(userStore);
  useEffect(() => {
    const ctrl = new AbortController();
    let made: string | null = null;
    getBookFile(item.Id, ctrl.signal).then(
      (buf) => { made = URL.createObjectURL(new Blob([buf], { type: 'application/pdf' })); setSrc(made); },
      () => { if (!ctrl.signal.aborted) setFailed(true); }
    );
    // Opening it counts as reading it (it shows under Continue); the browser's viewer doesn't say which page is open.
    if (!reading) void saveReading(item.Id, { Locator: '0', Percent: 0, Finished: false }).catch(() => undefined);
    return () => { ctrl.abort(); if (made) URL.revokeObjectURL(made); };
  }, [item.Id]);
  return (
    <div class="rd rd-pdf rd-theme-dark">
      <TopBar title={item.Name} sub={t('PDF')} onBack={() => back(`/details/${item.Id}`)}>
        {canDownload() && (
          <a class="rd-btn" href={downloadUrl(item.Id)} download aria-label={t('Download PDF')}><RdIcon name="download" /></a>
        )}
        <BarButton icon={full ? 'unfull' : 'full'} label={full ? t('Exit full screen') : t('Full screen')} onClick={toggleFull} />
      </TopBar>
      {failed ? <ReaderMessage title={t('Couldn’t open this PDF')} text={t('The server didn’t send the file. Try again in a moment.')} />
        : src ? <iframe class="rd-pdf-frame" src={src} title={item.Name} />
        : <div class="rd-loading"><span class="spinner" aria-label={t('Opening the book')} /></div>}
    </div>
  );
}

/* CBR and other formats: say so, and offer the file. */
function Unsupported({ item, audio }: { item: BaseItem; audio?: boolean }) {
  const f = formatOf(item);
  const name = f === 'cbr' ? 'CBR' : f === 'other' ? t('this kind of file') : f.toUpperCase();
  return (
    <div class="rd rd-theme-dark">
      <TopBar title={item.Name} onBack={() => back(`/details/${item.Id}`)} />
      <ReaderMessage
        title={audio ? t('This is an audiobook') : t('Seedy can’t open {format} in the browser', { format: name })}
        text={audio ? t('Listen to it from its page.') : f === 'cbr'
          ? t('CBR comics are RAR archives, which browsers can’t read. Download the file and open it in a comic reader app, or ask an admin for a CBZ copy.')
          : t('Download the file and open it in an app that reads it.')}
        action={
          audio ? <button type="button" class="btn btn-primary is-sm" onClick={() => back(`/details/${item.Id}`)}>{t('Go to the audiobook')}</button>
            : canDownload() ? <a class="btn btn-primary is-sm" href={downloadUrl(item.Id)} download><RdIcon name="download" size={18} />{t('Download {format}', { format: f === 'other' ? t('file') : f.toUpperCase() })}</a>
            : <span class="rd-message-note">{t('Downloading is turned off for this profile. An admin can turn it on.')}</span>
        }
      />
    </div>
  );
}
