import { useEffect, useRef } from 'preact/hooks';
import { chapterImageUrl } from '../api/images';
import type { BaseItem, Chapter } from '../api/types';
import { ticksToSeconds, timecode } from '../lib/format';
import { Icon } from '../ui/icons';
import { t } from '../lib/i18n';
import { TrickFrame, chapterAt, trickplayFor } from './Scrubber';

/* Chapters as a right-hand panel: 16:9 chapter image, name and start time. */
export function ChaptersPanel({ item, chapters, time, mediaSourceId, onSeek }: {
  item: BaseItem; chapters: Chapter[]; time: number; mediaSourceId?: string; onSeek: (seconds: number) => void;
}) {
  const current = chapterAt(chapters, time);
  const trick = trickplayFor(item, mediaSourceId);
  const listRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    listRef.current?.querySelector<HTMLElement>('[aria-pressed="true"]')?.scrollIntoView({ block: 'nearest' });
  }, []);
  return (
    <section class="pl-panel" aria-label={t('Chapters')}>
      <div ref={listRef} class="pl-group">
        <span class="pl-label">{t('Chapters')}</span>
        {!chapters.length && <span class="menu-item is-muted">{t('This title has no chapters')}</span>}
        {chapters.map((c, i) => {
          const at = ticksToSeconds(c.StartPositionTicks);
          const on = i === current;
          return (
            <button key={i} type="button" class="menu-item pl-row-item" aria-pressed={on ? 'true' : 'false'} onClick={() => onSeek(at)}>
              <span class="lead">
                <span class="pl-row-thumb">
                  {c.ImageTag ? (
                    <img src={chapterImageUrl(item.Id, i, c.ImageTag, 240)} alt="" loading="lazy" />
                  ) : trick ? (
                    <TrickFrame item={item} trick={trick} seconds={at + 1} boxWidth={112} mediaSourceId={mediaSourceId} />
                  ) : null}
                </span>
                <span class="two">
                  <span>{c.Name?.trim() || t('Chapter {n}', { n: i + 1 })}</span>
                  <span class="sub tnum">{timecode(at)}</span>
                </span>
              </span>
              {on && <Icon name="check" size={20} stroke={2.6} class="tick" />}
            </button>
          );
        })}
      </div>
    </section>
  );
}
