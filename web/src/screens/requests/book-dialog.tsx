import { useEffect, useState } from 'preact/hooks';
import { bookDetails, mediaCreate, mediaOptions, type BookCard, type BookFormat, type MediaOptions } from '../../api/media';
import { getLang, t, tn, tx } from '../../lib/i18n';
import { useAsync } from '../../lib/store';
import { Icon, type IconName } from '../../ui/icons';
import { Sheet, toast } from '../../ui/overlay';
import type { BookTarget } from './books';
import { BookArt, refreshMediaSummary, SERVICE_NAMES } from './media-parts';
import { Quota } from './music-dialog';

/* Request a book (RequestBook board): the ebook, the audiobook or both (a comic for comics), the language, the
   person's monthly limit (an ebook and its audiobook count as two), the libraries it lands in, and whether an admin
   approves it first. */

const LANGS: { code: string; name: string; ol: string[] }[] = [
  { code: 'en', name: 'English', ol: ['eng'] },
  { code: 'fr', name: 'French', ol: ['fre', 'fra'] },
  { code: 'de', name: 'German', ol: ['ger', 'deu'] },
  { code: 'es', name: 'Spanish', ol: ['spa'] },
  { code: 'it', name: 'Italian', ol: ['ita'] },
  { code: 'nl', name: 'Dutch', ol: ['dut', 'nld'] },
  { code: 'pt', name: 'Portuguese', ol: ['por'] },
  { code: 'ja', name: 'Japanese', ol: ['jpn'] }
];

const FORMAT: Record<BookFormat, { name: string; icon: IconName; sub: string }> = {
  ebook: { name: 'Ebook', icon: 'book', sub: 'EPUB, or AZW3 if there’s no EPUB · read in Seedy' },
  audiobook: { name: 'Audiobook', icon: 'headphones', sub: 'Listen in Seedy, with your place saved' },
  comic: { name: 'Comic', icon: 'book', sub: 'CBZ or CBR · read in Seedy page by page' }
};

/* "Shelfarr · indexers first": what the app does with this format. */
function routeLine(format: BookFormat, service: string): string {
  const name = SERVICE_NAMES[service as keyof typeof SERVICE_NAMES] ?? service;
  if (service === 'shelfarr') return format === 'audiobook' ? t('{app} · M4B or MP3 folders', { app: name }) : t('{app} · indexers first', { app: name });
  if (service === 'librarr') return format === 'comic' ? t('{app} · CBZ or CBR', { app: name }) : t('{app} · direct sources', { app: name });
  return name;
}

const num = (n?: number) => (n === undefined ? '' : Number.isInteger(n) ? String(n) : n.toFixed(1));

export function RequestBook({ target, onClose, onDone }: { target: BookTarget; onClose: () => void; onDone?: () => void }) {
  const state = useAsync(() => Promise.all([bookDetails(target.card), mediaOptions('book')]), [target.card.workId]);
  const book = state.data?.[0] ?? target.card;
  const opts = state.data?.[1];
  return (
    <Sheet open onClose={onClose} class="reqs-dlg" scrimClass="reqs-dlg-scrim" labelledBy="reqs-dlg-h">
      <Body book={book} opts={opts} row={target.row} loading={!state.data && !state.error} error={state.error} retry={state.reload} onClose={onClose} onDone={onDone} />
    </Sheet>
  );
}

function Body({ book, opts, row, loading, error, retry, onClose, onDone }: {
  book: BookCard; opts?: MediaOptions; row?: string; loading: boolean; error?: Error; retry: () => void; onClose: () => void; onDone?: () => void;
}) {
  const available = (opts?.formats ?? []).filter((f) => f.format !== 'music') as { format: BookFormat; service: string; library?: string | null }[];
  const offered = (book.content === 'comic' ? ['comic'] : ['ebook', 'audiobook'] as BookFormat[])
    .filter((f) => available.some((a) => a.format === f)) as BookFormat[];
  const [picked, setPicked] = useState<Set<BookFormat>>(new Set());
  const [lang, setLang] = useState('');
  const [busy, setBusy] = useState(false);

  // Start with what the row suggests: the audiobook on "Audiobooks of books you've read", else every format not yet on the server.
  useEffect(() => {
    if (!opts) return;
    const start = offered.filter((f) => !book.onServer.includes(f) && (row !== 'audiobooks' || f === 'audiobook'));
    setPicked(new Set(start));
  }, [opts, book.workId]);
  const original = LANGS.find((l) => book.languages.some((x) => l.ol.includes(x)));
  useEffect(() => { setLang(original?.code ?? (getLang() === 'fr' ? 'fr' : 'en')); }, [original?.code]);

  const units = picked.size;
  const limit = opts?.limit ?? -1;
  const left = limit < 0 ? Infinity : Math.max(0, limit - (opts?.used ?? 0));
  const over = limit >= 0 && units > left;
  const blocked = !!opts && (!opts.canRequest || limit === 0);
  const toggle = (f: BookFormat) => setPicked((s) => { const n = new Set(s); if (n.has(f)) n.delete(f); else n.add(f); return n; });

  const submit = async () => {
    if (busy || !units) return;
    setBusy(true);
    try {
      const res = await mediaCreate({
        kind: 'book',
        mode: 'book',
        foreignId: book.workId,
        title: book.title,
        subtitle: book.author,
        coverUrl: book.coverUrl,
        formats: [...picked],
        content: book.content,
        series: book.series,
        seriesIndex: book.seriesIndex,
        year: book.year,
        language: lang || undefined
      });
      toast(res.status === 'pending' ? t('Requested. An admin approves it first.') : t('Requested. {app} is on it.', { app: SERVICE_NAMES[res.service] }));
      void refreshMediaSummary();
      onDone?.();
      onClose();
    } catch (e) {
      toast(`${t('Couldn’t send the request.')} ${(e as Error).message || ''}`.trim(), 'error');
    } finally {
      setBusy(false);
    }
  };

  const meta = [
    book.series ? t('{series}, book {n}', { series: book.series, n: num(book.seriesIndex) }) : '',
    book.year ? String(book.year) : '',
    book.pages ? t('{n} pages', { n: book.pages }) : ''
  ].filter(Boolean);
  const read = book.readTo !== undefined && book.readFrom !== undefined
    ? (book.readFrom === book.readTo ? t('You’ve read book {n} on Seedy.', { n: num(book.readTo) }) : t('You’ve read books {from} to {to} on Seedy.', { from: num(book.readFrom), to: num(book.readTo) }))
    : '';
  const services = [...new Set([...picked].map((f) => available.find((a) => a.format === f)?.service).filter(Boolean))] as string[];
  const libraries = [...new Set([...picked].map((f) => available.find((a) => a.format === f)?.library).filter(Boolean))] as string[];
  const lands = libraries.length === 2
    ? tx('Lands in {a} and {b}.', { a: <b>{libraries[0]}</b>, b: <b>{libraries[1]}</b> })
    : libraries.length === 1 ? tx('Lands in {a}.', { a: <b>{libraries[0]}</b> }) : null;
  const label = picked.has('ebook') && picked.has('audiobook') ? t('Request ebook and audiobook')
    : picked.has('audiobook') ? t('Request audiobook') : picked.has('comic') ? t('Request comic') : t('Request ebook');
  const quotaAria = limit < 0 ? t('No limit') : t('{used} used, {now} in this request, {left} left', { used: opts?.used ?? 0, now: units, left: Math.max(0, (left as number) - units) });

  return (
    <>
      <div class="reqs-dlg-head is-book">
        <span class="reqs-dlg-cover"><BookArt seed={book.workId} src={book.coverUrl} title={book.title} author={book.author} size="dialog" /></span>
        <div class="reqs-dlg-head-text is-book">
          <span class="reqs-dlg-eyebrow">{book.content === 'comic' ? t('Request a comic') : t('Request a book')}</span>
          <h2 id="reqs-dlg-h" class="reqs-dlg-title">{book.title}</h2>
          <span class="reqs-dlg-byline">{book.author && <b>{book.author}</b>}{book.author && meta.length ? ' · ' : ''}{meta.join(' · ')}</span>
          {(book.description || read) && <span class="reqs-dlg-desc">{[book.description, read].filter(Boolean).join(' ')}</span>}
          {loading && !book.description && <span class="skel skel-line" style={{ width: '80%' }} />}
        </div>
        <button type="button" class="reqs-dlg-close" aria-label={t('Close')} onClick={onClose}><Icon name="close" size={18} stroke={2.2} /></button>
      </div>
      <div class="reqs-dlg-body">
        {error ? (
          <div class="rq-error">
            <span>{error.message || t('Couldn’t load this book.')}</span>
            <button type="button" class="btn btn-secondary is-sm" onClick={retry}>{t('Try again')}</button>
          </div>
        ) : (
          <>
            <span class="reqs-dlg-label">{t('Formats')}</span>
            <div role="group" aria-label={t('Formats')} class={'reqs-dlg-formats' + (offered.length === 1 ? ' is-one' : '')}>
              {!opts ? [0, 1].map((i) => <span key={i} class="reqs-format is-skel skel" />)
                : !offered.length ? <span class="reqs-dlg-empty">{t('This server can’t request this kind of book.')}</span>
                : offered.map((f) => {
                  const onServer = book.onServer.includes(f);
                  const on = !onServer && picked.has(f);
                  const svc = available.find((a) => a.format === f)!.service;
                  return (
                    <button
                      key={f}
                      type="button"
                      role="checkbox"
                      aria-checked={on || onServer ? 'true' : 'false'}
                      aria-disabled={onServer ? 'true' : undefined}
                      class={'reqs-format' + (on ? ' is-on' : onServer ? ' is-locked' : '')}
                      onClick={() => { if (!onServer) toggle(f); }}
                    >
                      <span class="reqs-format-icon" aria-hidden="true"><Icon name={FORMAT[f].icon} size={22} stroke={2} /></span>
                      <span class="reqs-format-text">
                        <span class="reqs-format-name">{t(FORMAT[f].name)}</span>
                        <span class="reqs-format-sub">{onServer ? t('Already on the server') : t(FORMAT[f].sub)}</span>
                        <span class="reqs-format-route">{routeLine(f, svc)}</span>
                      </span>
                      <span aria-hidden="true" class={'reqs-box' + (on ? ' is-on' : onServer ? ' is-locked' : '')}><Icon name="check" size={16} stroke={3.2} /></span>
                    </button>
                  );
                })}
            </div>
            <div class="reqs-dlg-grid">
              <label class="reqs-dlg-card is-lang">
                <span class="reqs-dlg-card-label">{t('Language')}</span>
                <span class="reqs-dlg-select">
                  <select value={lang} onChange={(e) => setLang((e.currentTarget as HTMLSelectElement).value)}>
                    {LANGS.map((l) => <option key={l.code} value={l.code}>{l.code === original?.code ? t('{language} (original)', { language: t(l.name) }) : t(l.name)}</option>)}
                  </select>
                  <Icon name="chevronDown" size={16} stroke={2.2} />
                </span>
              </label>
              {opts ? (
                <Quota label={t('Book requests this month')} opts={opts} units={units} aria={quotaAria} extra={book.content === 'comic' ? undefined : t('Ebook and audiobook count as two')} />
              ) : <span class="reqs-dlg-card skel" />}
            </div>
            {lands && (
              <div class="reqs-dlg-lands">
                <Icon name="folder" size={18} stroke={2.2} />
                <span>
                  {lands}
                  {opts?.scanOnReady && services.length > 0 ? ' ' + t('Seedy scans the library when {app} finishes.', { app: services.map((s) => SERVICE_NAMES[s as keyof typeof SERVICE_NAMES]).join(t(' and ')) }) : ''}
                </span>
              </div>
            )}
          </>
        )}
      </div>
      <div class="reqs-dlg-foot">
        <span class="reqs-dlg-note">
          {opts && <span class={'reqs-dot is-8 ' + (blocked || over ? 'is-danger' : opts.autoApprove ? 'is-ok' : 'is-pending')} aria-hidden="true" />}
          {!opts ? '' : blocked ? t('Book requests are switched off for your profile')
            : over ? tn(left as number, 'You have {n} book request left this month', 'You have {n} book requests left this month')
            : opts.autoApprove ? t('Approved automatically for you') : t('An admin approves requests before {app} searches', { app: SERVICE_NAMES[(services[0] ?? 'shelfarr') as keyof typeof SERVICE_NAMES] })}
        </span>
        <span class="reqs-dlg-buttons">
          <button type="button" class="reqs-dlg-cancel" onClick={onClose}>{t('Cancel')}</button>
          <button type="button" data-autofocus class="btn btn-primary reqs-dlg-send" disabled={busy || blocked || over || !units || !opts} onClick={() => void submit()}>
            <Icon name="request" size={18} stroke={2.2} />
            {busy ? t('Requesting…') : label}
          </button>
        </span>
      </div>
    </>
  );
}
