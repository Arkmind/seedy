import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { Link } from '../lib/router';
import { Icon } from './icons';
import { t } from '../lib/i18n';

export function Row({
  title, sub, seeAll, tools, children, label, gap, tight, centerHead, id
}: {
  title: ComponentChildren;
  sub?: ComponentChildren;
  seeAll?: string;
  tools?: ComponentChildren;
  children: ComponentChildren;
  label?: string;
  gap?: 16 | 18 | 20;
  tight?: boolean;
  centerHead?: boolean;
  id?: string;
}) {
  const scroller = useRef<HTMLDivElement>(null);
  const [edges, setEdges] = useState({ left: false, right: false });

  useEffect(() => {
    const el = scroller.current;
    if (!el) return;
    const update = () => setEdges({ left: el.scrollLeft > 4, right: el.scrollLeft + el.clientWidth < el.scrollWidth - 4 });
    update();
    el.addEventListener('scroll', update, { passive: true });
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => {
      el.removeEventListener('scroll', update);
      ro.disconnect();
    };
  }, [children]);

  const page = (dir: 1 | -1) => {
    const el = scroller.current;
    if (!el) return;
    el.scrollBy({ left: dir * Math.max(300, el.clientWidth - 160), behavior: 'smooth' });
  };

  return (
    <section class={'row' + (tight ? ' is-tight' : '')} aria-label={label} id={id}>
      <div class={'row-head' + (centerHead || tools ? ' is-center' : '')}>
        <div class="row-head-main">
          <h2 class="row-title">{title}</h2>
          {sub && <span class="row-sub">{sub}</span>}
        </div>
        {tools ? (
          <div class="row-tools">
            {seeAll && <Link to={seeAll} class="row-link">{t('See all')}</Link>}
            {tools}
          </div>
        ) : (
          seeAll && <Link to={seeAll} class="row-link">{t('See all')}</Link>
        )}
      </div>
      <div class="row-shell">
        <button type="button" class="row-arrow is-left" aria-label={t('Scroll left')} tabIndex={-1} hidden={!edges.left} onClick={() => page(-1)}>
          <span><Icon name="chevronLeft" size={20} /></span>
        </button>
        <div ref={scroller} class={'row-scroller hscroll' + (gap && gap !== 20 ? ' is-' + gap : '')}>{children}</div>
        <button type="button" class="row-arrow is-right" aria-label={t('Scroll right')} tabIndex={-1} hidden={!edges.right} onClick={() => page(1)}>
          <span><Icon name="chevronRight" size={20} /></span>
        </button>
      </div>
    </section>
  );
}
