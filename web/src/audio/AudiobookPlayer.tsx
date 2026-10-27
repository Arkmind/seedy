import { useEffect, useRef } from 'preact/hooks';
import { getItems } from '../api/jellyfin';
import type { BaseItem } from '../api/types';
import { duration } from '../lib/format';
import { formatNumber, t } from '../lib/i18n';
import { navigate } from '../lib/router';
import { useAsync, useStore } from '../lib/store';
import { Cover, useCoverTint } from './cover';
import { audioStore, chapterAt, nextChapter, openPlayer, pause, previousChapter, seek, seekChapter, skip, timeStore, type BookInfo } from './engine';
import { AIcon, Skip30 } from './icons';
import { PlayButton } from './MiniBar';
import { SeekBar, clock, remaining } from './parts';
import { SleepButton, SpeedButton } from './book-controls';
import { PlayerShell } from './Shell';

/* The Audiobook player (Audiobook board): cover; the chapter with its progress, ±30 s and chapter skips; speed, sleep
   timer and "Read the EPUB from here" when the server has the book too; the chapters, listened ones ticked. */

const norm = (s?: string) => (s ?? '').toLowerCase().normalize('NFKD').replace(/[̀-ͯ]/g, '').replace(/[^\p{L}\p{N}]+/gu, ' ').trim();

/* The same book as an e-book: same title, and the same author when both say who wrote it. */
async function findEbook(book: BookInfo): Promise<BaseItem | null> {
  const r = await getItems({ includeItemTypes: ['Book'], searchTerm: book.name, recursive: true, limit: 20, fields: ['People', 'Path'] }).catch(() => null);
  const author = norm(book.author);
  return r?.Items.find((b) => {
    if (norm(b.Name) !== norm(book.name)) return false;
    const writers = (b.People ?? []).filter((p) => p.Type === 'Author' || p.Type === 'Writer').map((p) => norm(p.Name));
    const also = [norm(b.AlbumArtist), ...(b.Artists ?? []).map(norm)].filter(Boolean);
    const known = [...writers, ...also];
    return !author || !known.length || known.includes(author);
  }) ?? null;
}

export function AudiobookPlayer() {
  const s = useStore(audioStore);
  const time = useStore(timeStore);
  const book = s.book;
  const tk = s.current?.track;
  const tint = useCoverTint(tk);
  const ebook = useAsync(() => (book ? findEbook(book) : Promise.resolve(null)), [book?.id]);
  const listRef = useRef<HTMLDivElement>(null);
  const pos = time.position;
  const ch = chapterAt(book, pos);
  useEffect(() => {
    // Keeps the current chapter in view, with a couple of the next ones under it.
    const box = listRef.current;
    const row = box?.querySelector<HTMLElement>('[aria-current="true"]');
    if (!row || !box) return;
    const top = row.offsetTop - box.offsetTop;
    const want = row.offsetHeight * 3;
    if (top < box.scrollTop) box.scrollTo({ top: Math.max(0, top - row.offsetHeight), behavior: 'smooth' });
    else if (top + want > box.scrollTop + box.clientHeight) box.scrollTo({ top: top + want - box.clientHeight, behavior: 'smooth' });
  }, [ch?.index]);
  if (!book || !tk) return null;
  const total = book.duration || time.duration;
  const inChapter = ch ? pos - ch.start : pos;
  const chLen = ch ? ch.end - ch.start : total;
  const listened = total ? Math.round((pos / total) * 100) : 0;
  const by = [book.author, book.narrator ? t('read by {name}', { name: book.narrator }) : ''].filter(Boolean).join(' · ');
  const multi = book.chapters.length > 1;
  return (
    <PlayerShell label={t('Audiobook player')} tint={tint} at="22% 30%" stops={[40, 82]} overline={t('Audiobook')} title={book.name} titleTo={`/details/${book.id}`}>
      <div class="apb-grid">
        <section class="apb-main" aria-label={book.name}>
          <Cover item={tk} width={720} title={book.name} class="apb-cover" />
          <div class="apb-side">
            <div class="apb-heads">
              {multi && ch && <span class="apb-over">{t('Chapter {n} of {total}', { n: ch.index + 1, total: book.chapters.length })}</span>}
              <span class="apb-title">{multi && ch ? ch.name : book.name}</span>
              {by && <span class="apb-by">{by}</span>}
            </div>
            <div class="apb-progress">
              <SeekBar class="is-big is-tall" value={inChapter} max={chLen} onSeek={(v) => seek((ch?.start ?? 0) + v)}
                label={multi ? t('Position in chapter') : t('Position in book')} valueText={t('{a} of {b}', { a: clock(inChapter), b: clock(chLen) })} />
              <span class="apb-times">
                <span>{clock(inChapter)}</span>
                <span>{multi
                  ? t('{left} in chapter · {book} left in book', { left: remaining(chLen - inChapter), book: duration(total - pos) })
                  : t('{left} left in book', { left: duration(total - pos) })}</span>
              </span>
            </div>
            <div class="apb-controls">
              <button type="button" class="apb-btn is-48" aria-label={t('Previous chapter')} onClick={previousChapter}><AIcon name="previous" size={22} /></button>
              <button type="button" class="apb-btn is-60" aria-label={t('Back 30 seconds')} onClick={() => skip(-30)}><Skip30 dir="back" size={42} /></button>
              <PlayButton playing={s.playing} loading={s.loading} size={76} icon={30} class="apb-play" />
              <button type="button" class="apb-btn is-60" aria-label={t('Forward 30 seconds')} onClick={() => skip(30)}><Skip30 dir="forward" size={42} /></button>
              <button type="button" class="apb-btn is-48" aria-label={t('Next chapter')} disabled={!ch || ch.index + 1 >= book.chapters.length} onClick={nextChapter}><AIcon name="next" size={22} /></button>
            </div>
            <div class="apb-chips">
              <SpeedButton variant="chip" />
              <SleepButton variant="chip" />
              {ebook.data && (
                <button type="button" class="apb-chip" onClick={() => {
                  const percent = total ? Math.round((pos / total) * 1000) / 10 : 0;
                  // Reading takes over from listening: the place is kept for when they come back to it.
                  pause();
                  openPlayer(false);
                  navigate(`/read/${ebook.data!.Id}?percent=${percent}`);
                }}>
                  <AIcon name="book" size={17} />{t('Read the EPUB from here')}
                </button>
              )}
            </div>
          </div>
        </section>

        <section class="apb-chapters" aria-labelledby="apb-ch-h">
          <div class="apb-ch-head">
            <h2 id="apb-ch-h" class="apb-ch-title">{t('Chapters')}</h2>
            <span class="apb-ch-sum">{t('{length} · {n}% listened', { length: duration(total), n: formatNumber(listened) })}</span>
          </div>
          <div ref={listRef} class="apb-ch-list">
            {book.chapters.map((c) => {
              const current = ch?.index === c.index;
              const done = !!ch && c.index < ch.index;
              return (
                <button key={c.index} type="button" class={'apb-ch' + (current ? ' is-current' : done ? ' is-done' : '')} aria-current={current ? 'true' : undefined}
                  onClick={() => seekChapter(c.index)}>
                  <span class="apb-ch-n">{done ? <AIcon name="check" size={15} stroke={3} /> : c.index + 1}</span>
                  <span class="apb-ch-name">{c.name}</span>
                  <span class="apb-ch-time">{clock(c.end - c.start)}</span>
                </button>
              );
            })}
          </div>
        </section>
      </div>
    </PlayerShell>
  );
}
