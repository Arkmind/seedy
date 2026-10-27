import type { ComponentChildren } from 'preact';
import { useState } from 'preact/hooks';
import { bookCover, bookRow, bookSearch, type BookCard as Book, type BookRow } from '../../api/media';
import { dayOf, daysFromToday, fmtDayMonth, fmtMonthYear } from '../../lib/format';
import { t } from '../../lib/i18n';
import { Link } from '../../lib/router';
import { useAsync } from '../../lib/store';
import { Row } from '../../ui/Row';
import { Icon } from '../../ui/icons';
import { ListFrame } from '../browse/Lists';
import { EmptyState, ErrorState, Lazy } from '../browse/parts';
import { RequestBook } from './book-dialog';
import { Frame, NotSetUp, useServices } from './frame';
import { BookArt, cardStatus, MediaTopLists, useAsked } from './media-parts';

/* Requests · Books (RequestsBooks board): Shelfarr and Librarr inside Seedy. Your book requests and approvals, then
   the next volumes of series the person reads in Seedy, audiobooks of books they finished, and what's trending.
   Every book opens the request window. */

const ROWS: { row: BookRow; title: string; src: string }[] = [
  { row: 'next', title: 'Next in series you’re reading', src: 'From your reading progress in Seedy' },
  { row: 'audiobooks', title: 'Audiobooks of books you’ve read', src: 'Listen to the ones you finished' },
  { row: 'trending', title: 'Trending', src: 'From {source}' }
];

const num = (n?: number) => (n === undefined ? '' : Number.isInteger(n) ? String(n) : n.toFixed(1));

/* "Ebook + audio", "Audiobook", "Comic", "Upcoming", and the badge's colour. */
export function bookType(b: Book): { text: string; cls: string } {
  if (b.upcoming) return { text: t('Upcoming'), cls: 'is-upcoming' };
  if (b.content === 'comic') return { text: t('Comic'), cls: 'is-comic' };
  if (b.formats.includes('ebook') && b.formats.includes('audiobook')) return { text: t('Ebook + audio'), cls: 'is-both' };
  if (b.formats.includes('audiobook')) return { text: t('Audiobook'), cls: 'is-audio' };
  return { text: t('Ebook'), cls: 'is-ebook' };
}

function releaseWhen(b: Book): string {
  const d = dayOf(b.releaseDate);
  if (!d) return b.year ? String(b.year) : '';
  const days = daysFromToday(d);
  if (days > 0 && days <= 120) return fmtDayMonth(d) + (d.getFullYear() !== new Date().getFullYear() ? ' ' + d.getFullYear() : '');
  return days > 0 ? fmtMonthYear(d) : String(d.getFullYear());
}

export function bookMeta(b: Book, row?: BookRow): { text: string; accent: boolean } {
  const series = b.series ? (b.content === 'comic' ? t('{series} · Vol. {n}', { series: b.series, n: num(b.seriesIndex) }) : `${b.series} ${num(b.seriesIndex)}`.trim()) : '';
  if (row === 'next') {
    if (b.upcoming) return { text: [series, releaseWhen(b)].filter(Boolean).join(' · '), accent: true };
    return { text: series || b.author || '', accent: false };
  }
  if (row === 'trending') return { text: [b.year, b.rating ? `★ ${b.rating.toFixed(1)}` : ''].filter(Boolean).join(' · '), accent: false };
  if (row === 'audiobooks') return { text: b.author ?? '', accent: false };
  return { text: [b.author, b.year].filter(Boolean).join(' · '), accent: false };
}

export interface BookTarget { card: Book; row?: BookRow }

export function useBookDialog(onDone?: () => void) {
  const [target, setTarget] = useState<BookTarget | null>(null);
  return {
    open: (x: BookTarget) => setTarget(x),
    dialog: target ? <RequestBook key={target.card.workId} target={target} onClose={() => setTarget(null)} onDone={onDone} /> : null
  };
}

/* A 160 px book: cover, Request · More info on hover, then its type and state, title and line. */
export function BookCardView({ b: card, meta, onOpen, width }: { b: Book; meta: { text: string; accent: boolean }; onOpen: (request: boolean) => void; width?: number }) {
  const b = useAsked(card, card.workId);
  const type = bookType(b);
  const status = cardStatus(b.status, b.requestedBy, true);
  const onServer = b.status === 'server' && b.itemId;
  const canAsk = (b.status === 'none' || (b.status === 'server' && b.formats.some((f) => !b.onServer.includes(f)))) && b.formats.length > 0;
  return (
    <div class="card reqs-book-card" style={width ? { flex: `0 0 ${width}px`, width: width + 'px' } : undefined}>
      <div class="reqs-book-cover" tabIndex={-1}>
        <BookArt seed={b.workId} src={bookCover(b)} title={b.title} author={b.author} />
        <div class="reqs-cover-hover">
          {canAsk && (
            <button type="button" class="btn btn-primary is-sm is-block" aria-haspopup="dialog" onClick={() => onOpen(true)}>
              <Icon name="plus" size={18} stroke={2.6} />
              {t('Request')}
            </button>
          )}
          {onServer && !canAsk
            ? <Link to={`/details/${b.itemId}`} class="btn poster-more">{t('Open')}</Link>
            : <button type="button" class="btn poster-more" onClick={() => onOpen(false)}>{t('More info')}</button>}
        </div>
      </div>
      <span class="reqs-book-badges">
        <span class={'reqs-type is-inline ' + type.cls}>{type.text}</span>
        {status && <span class="reqs-book-status"><span class="reqs-dot" style={{ background: status.dot }} />{status.text}</span>}
      </span>
      <button type="button" class="card-text reqs-card-text" tabIndex={-1} onClick={() => onOpen(false)}>
        <span class="card-title is-14">{b.title}</span>
        <span class="card-meta" style={{ fontWeight: 600, color: meta.accent ? 'var(--seedy-accent)' : undefined }}>{meta.text}</span>
      </button>
    </div>
  );
}

function BookSkeletons({ count = 8, grid }: { count?: number; grid?: boolean }) {
  return (
    <>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} class="card reqs-book-card" style={grid ? undefined : { flex: '0 0 160px', width: '160px' }} aria-hidden="true">
          <span class="reqs-book-cover skel" style={{ aspectRatio: '2 / 3' }} />
          <span class="card-text"><span class="skel skel-line" style={{ width: '70%' }} /><span class="skel skel-line" style={{ width: '50%' }} /></span>
        </div>
      ))}
    </>
  );
}

function BookRowView({ def, open }: { def: (typeof ROWS)[number]; open: (x: BookTarget) => void }) {
  const state = useAsync(() => bookRow(def.row), [def.row]);
  const sub = t(def.src, { source: state.data?.source ?? 'Open Library' });
  if (state.loading && !state.data) return <Row title={t(def.title)} sub={sub}><BookSkeletons /></Row>;
  const items = state.data?.results ?? [];
  if (!items.length) return null;
  return (
    <Row title={t(def.title)} sub={sub} seeAll={`/requests/books/discover/${def.row}`} label={t(def.title)}>
      {items.slice(0, 20).map((b) => <BookCardView key={b.workId} b={b} meta={bookMeta(b, def.row)} onOpen={() => open({ card: b, row: def.row })} width={160} />)}
    </Row>
  );
}

function BooksFrame({ children }: { children: ComponentChildren }) {
  return (
    <Frame
      service="books"
      tab="discover"
      placeholder={t('Find any book, author or series to request')}
      label={t('Search for books, authors and series')}
      results={(query) => <BookResults query={query} />}
    >
      {children}
    </Frame>
  );
}

export function BooksDiscover() {
  const dialog = useBookDialog();
  return (
    <BooksFrame>
      <MediaTopLists kind="book" />
      {ROWS.map((r, i) => (
        <Lazy key={r.row} eager={i < 2} placeholder={<Row title={t(r.title)}><BookSkeletons /></Row>}>
          {() => <BookRowView def={r} open={dialog.open} />}
        </Lazy>
      ))}
      {dialog.dialog}
    </BooksFrame>
  );
}

function BookResults({ query }: { query: string }) {
  const dialog = useBookDialog();
  const state = useAsync(() => bookSearch(query), [query]);
  let body: ComponentChildren;
  if (state.loading && !state.data) body = <div class="reqs-grid is-books"><BookSkeletons count={12} grid /></div>;
  else if (state.error) body = <ErrorState error={state.error} retry={state.reload} title={t('Couldn’t search for books')} />;
  else if (!state.data?.results.length) body = <EmptyState icon="searchX" title={t('Nothing found for “{query}”', { query })} text={t('Try another title or author, or check the spelling.')} />;
  else body = <div class="reqs-grid is-books">{state.data.results.map((b) => <BookCardView key={b.workId} b={b} meta={bookMeta(b)} onOpen={() => dialog.open({ card: b })} />)}</div>;
  return (
    <section class="reqs-pad reqs-results" aria-labelledby="reqs-results-h">
      <div class="reqs-col-head">
        <h2 id="reqs-results-h" class="row-title">{t('Results for “{query}”', { query })}</h2>
      </div>
      {body}
      {dialog.dialog}
    </section>
  );
}

export function BooksBrowse({ row }: { row: string }) {
  const services = useServices();
  const def = ROWS.find((r) => r.row === row);
  const dialog = useBookDialog();
  const state = useAsync(() => (def && services.includes('books') ? bookRow(def.row) : Promise.resolve(null)), [row]);
  let body: ComponentChildren;
  if (!def) body = <EmptyState icon="searchX" title={t('This list isn\'t here')} text={t('The link may be old.')} />;
  else if (!services.includes('books')) body = <NotSetUp />;
  else if (state.loading && !state.data) body = <div class="reqs-grid is-books"><BookSkeletons count={14} grid /></div>;
  else if (state.error) body = <ErrorState error={state.error} retry={state.reload} />;
  else if (!state.data?.results.length) body = <EmptyState icon="clock" title={t('Nothing here right now')} text={t('Check back later for new books.')} />;
  else body = <div class="reqs-grid is-books">{state.data.results.map((b) => <BookCardView key={b.workId} b={b} meta={bookMeta(b, def.row)} onOpen={() => dialog.open({ card: b, row: def.row })} />)}</div>;
  return (
    <ListFrame
      active="requests"
      title={def ? t(def.title) : t('List not found')}
      lede={<Link to="/requests/books" class="list-seed"><Icon name="chevronLeft" size={14} stroke={2.4} />{t('Back to book requests')}</Link>}
    >
      {body}
      {dialog.dialog}
    </ListFrame>
  );
}
