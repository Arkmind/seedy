import { createPortal } from 'preact/compat';
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { href, navigate } from '../../lib/router';
import { detailsPath } from '../cards';
import { Icon } from '../icons';
import { NbIcon } from '../navbar/icons';
import { addRecent } from './recent';
import { bestPlay, cached, phoneMeta, rowTitle, search, type Hit, type Kind, type Results } from './engine';
import { Marked, PersonLine, SearchIdle, Thumb, useSeerrSearch } from './parts';
import { t } from '../../lib/i18n';

/* Phone search (Navigation bar board, "Phone, searching"): full screen over the page and its tab bar, with type
   filters and Cancel. Results come in as on the bar, as one ranked list. */

type Filter = 'all' | 'show' | 'movie' | 'person';
const FILTERS: { id: Filter; label: string }[] = [
  { id: 'all', label: 'All' },
  { id: 'show', label: 'Shows' },
  { id: 'movie', label: 'Movies' },
  { id: 'person', label: 'People' }
];

export function PhoneSearch({ onClose }: { onClose: () => void }) {
  const [text, setText] = useState('');
  const [filter, setFilter] = useState<Filter>('all');
  const [query, setQ] = useState('');
  const [state, setState] = useState<{ q: string; data?: Results; error?: boolean } | null>(null);
  const [tick, setTick] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const seerr = useSeerrSearch();
  const closeRef = useRef(onClose);
  closeRef.current = onClose;
  const term = text.trim();

  useLayoutEffect(() => {
    // Take over from the stand-in field that kept the keyboard up (and anything typed into it meanwhile).
    const proxy = document.activeElement;
    if (proxy instanceof HTMLInputElement && proxy !== input.current && proxy.getAttribute('aria-hidden') === 'true' && proxy.value) setText(proxy.value);
    input.current?.focus({ preventScroll: true });
  }, []);
  useEffect(() => {
    const prev = document.querySelector<HTMLElement>('.nb-psearch-field');
    const root = document.documentElement;
    const overflow = root.style.overflow;
    root.style.overflow = 'hidden';
    root.classList.add('nb-psearch-open');
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.preventDefault(); closeRef.current(); } };
    document.addEventListener('keydown', onKey);
    return () => {
      root.style.overflow = overflow;
      root.classList.remove('nb-psearch-open');
      document.removeEventListener('keydown', onKey);
      if (prev?.isConnected) prev.focus({ preventScroll: true });
    };
  }, []);

  useEffect(() => {
    if (term.length < 2) { setQ(''); return; }
    if (cached(term, 'phone')) { setQ(term); return; }
    const t = window.setTimeout(() => setQ(term), 250);
    return () => window.clearTimeout(t);
  }, [term]);

  useEffect(() => {
    if (!query) return;
    const ctrl = new AbortController();
    search(query, 'phone', ctrl.signal).then(
      (data) => { if (!ctrl.signal.aborted) setState({ q: query, data }); },
      (e: Error) => { if (!ctrl.signal.aborted && e?.name !== 'AbortError') setState({ q: query, error: true }); }
    );
    return () => ctrl.abort();
  }, [query, tick]);

  const go = (to: string) => {
    addRecent(term);
    onClose();
    navigate(to);
  };

  const idle = term.length < 2;
  const data = !idle ? state?.data : undefined;
  const failed = !idle && state?.error && state.q === term;
  const ranked: Hit[] = data ? (data.best ? [data.best, ...data.hits.filter((h) => h !== data.best)] : data.hits) : [];
  const list = filter === 'all' ? ranked : ranked.filter((h) => h.kind === (filter as Kind));

  return createPortal(
    <div class="nb-psearch" role="dialog" aria-modal="true" aria-label={t('Search')}>
      <div class="nb-psearch-top">
        <label class="nb-search is-open is-phone has-text">
          <Icon name="search" size={19} stroke={2.2} />
          <input
            ref={input}
            type="search"
            aria-label={t('Search')}
            placeholder={t('Search')}
            value={text}
            autoComplete="off"
            autoCapitalize="off"
            spellcheck={false}
            enterKeyHint="search"
            onInput={(e) => setText(e.currentTarget.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && term.length >= 2) { e.preventDefault(); go(`/search?q=${encodeURIComponent(term)}`); } }}
          />
        </label>
        <button type="button" class="nb-cancel" onClick={onClose}>{t('Cancel')}</button>
      </div>

      {idle ? (
        <div class="nb-psearch-idle">
          <SearchIdle onRecent={(q) => { setText(q); input.current?.focus(); }} onGenre={(g) => go(`/search?genre=${encodeURIComponent(g)}`)} />
        </div>
      ) : (
        <>
          <div class="nb-filters" role="group" aria-label={t('Show')}>
            {FILTERS.map((f) => (
              <button key={f.id} type="button" class="nb-filter" aria-pressed={filter === f.id ? 'true' : 'false'} onClick={() => setFilter(f.id)}>{t(f.label)}</button>
            ))}
          </div>
          {failed && (
            <div class="nb-drop-note" role="alert">
              <span class="nb-drop-note-title">{t('Search didn\'t work')}</span>
              <span>{t('The server didn\'t answer.')}</span>
              <button type="button" class="btn btn-secondary is-xs" onClick={() => setTick((t) => t + 1)}>Try again</button>
            </div>
          )}
          {!failed && !data && (
            <div class="nb-plist" aria-busy="true" aria-label={t('Searching')}>
              {[0, 1, 2, 3].map((k) => (
                <div key={k} class="nb-prow is-skel">
                  <span class="nb-thumb is-phone skel" />
                  <span class="nb-prow-text"><span class="skel skel-line" style={{ width: 140 - k * 12 + 'px', height: '14px' }} /><span class="skel skel-line" style={{ width: '80px', height: '10px' }} /></span>
                </div>
              ))}
            </div>
          )}
          {data && (
            <div class="nb-plist" role="list" aria-label={`Results for ${data.q}`}>
              {list.length === 0 && (
                <p class="nb-pempty">{ranked.length ? t('No {kind} match “{q}”.', { kind: t(FILTERS.find((f) => f.id === filter)!.label).toLowerCase(), q: data.q }) : t('Nothing in your library for “{q}”.', { q: data.q })}</p>
              )}
              {list.map((h) => <PhoneRow key={h.item.Id} hit={h} onOpen={() => go(detailsPath(h.item))} />)}
            </div>
          )}
          {seerr && (
            <a class="nb-pseerr" href={seerr(term)} target="_blank" rel="noreferrer" onClick={() => addRecent(term)}>
              <NbIcon name="request" size={17} />Not here? Find it on Seerr
            </a>
          )}
        </>
      )}
    </div>,
    document.body
  );
}

function PhoneRow({ hit, onOpen }: { hit: Hit; onOpen: () => void }) {
  const it = hit.item;
  const t = rowTitle(it);
  const [resume, setResume] = useState<string | undefined>();
  useEffect(() => {
    if (hit.kind !== 'show' || !((it.UserData?.PlayedPercentage ?? 0) > 0)) return;
    let live = true;
    bestPlay(it).then((p) => { if (live && p.label === 'Resume' && p.tag) setResume(p.tag); }, () => undefined);
    return () => { live = false; };
  }, [it.Id]);
  return (
    <a href={href(detailsPath(it))} class="nb-prow" role="listitem" onClick={(e) => { if (e.button === 0 && !e.metaKey && !e.ctrlKey) { e.preventDefault(); onOpen(); } }}>
      <Thumb item={it} width={96} class="is-phone" />
      <span class="nb-prow-text">
        <span class="nb-prow-title">{t.lead}<Marked text={t.name} query={hit.match} /></span>
        <span class="nb-prow-meta">{hit.kind === 'person' ? <PersonLine person={it} short /> : phoneMeta(hit, resume)}</span>
      </span>
    </a>
  );
}
