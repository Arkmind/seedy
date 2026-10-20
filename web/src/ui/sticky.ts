import { useEffect, useRef, useState } from 'preact/hooks';

/* The top bar stays put while the page scrolls. Once the page has moved it gets a backdrop (`stuck`), and its height
   is published as --header-h so side navs, sticky rails and anchor jumps can sit below it. */
export function useStickyHeader<T extends HTMLElement>() {
  const ref = useRef<T>(null);
  const [stuck, setStuck] = useState(() => window.scrollY > 4);

  useEffect(() => {
    const onScroll = () => setStuck(window.scrollY > 4);
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const root = document.documentElement.style;
    const publish = () => root.setProperty('--header-h', `${Math.round(el.getBoundingClientRect().height)}px`);
    publish();
    const ro = typeof ResizeObserver === 'function' ? new ResizeObserver(publish) : null;
    ro?.observe(el);
    return () => {
      ro?.disconnect();
      root.removeProperty('--header-h');
    };
  }, []);

  return { ref, stuck };
}

/* The sticky top bar's height in px (0 when there is none), for code that scrolls to a spot itself. */
export function headerHeight(): number {
  return parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--header-h')) || 0;
}
