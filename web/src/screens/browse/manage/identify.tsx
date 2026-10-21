import { useEffect, useRef, useState } from 'preact/hooks';
import { primaryUrl } from '../../../api/images';
import type { BaseItem, ItemKind } from '../../../api/types';
import { locale, t, tn } from '../../../lib/i18n';
import { Icon } from '../../../ui/icons';
import { toast } from '../../../ui/overlay';
import {
  applyRemoteSearch, getExternalIdInfos, getFullItem, idName, idsFor, idsLine, previewUrl, providerName, remoteSearch,
  searchKind, typeName, type ExternalIdInfo, type RemoteSearchResult
} from './api';
import { ItemButton, ItemCheck, ItemDialog, ItemField } from './dialog';
import './identify.css';

/* Identify (ItemIdentify board): look the item up by name, year or an id, pick the right match, and Jellyfin
   replaces its info (and, if asked, its artwork) from it. The files stay where they are. */

function question(type: ItemKind): string {
  switch (type) {
    case 'Series': return t('Which show is this?');
    case 'Movie': return t('Which movie is this?');
    case 'BoxSet': return t('Which collection is this?');
    case 'MusicAlbum': return t('Which album is this?');
    case 'MusicArtist': return t('Which artist is this?');
    case 'Person': return t('Who is this?');
    case 'Book': return t('Which book is this?');
    case 'MusicVideo': return t('Which music video is this?');
    case 'Trailer': return t('Which trailer is this?');
    default: return t('What is this?');
  }
}

const norm = (s?: string) => (s ?? '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/[^a-z0-9]+/g, ' ').trim();

/* How far a result is from what was asked: an id that was typed wins, then the name, then the year. Ties keep the
   order the providers gave. */
function distance(r: RemoteSearchResult, name: string, year: number | undefined, ids: Record<string, string>): number {
  const idHit = Object.entries(ids).some(([k, v]) => r.ProviderIds?.[k] === v);
  const a = norm(r.Name), b = norm(name);
  const named = !b ? 1 : a === b ? 0 : a.startsWith(b) || b.startsWith(a) ? 1 : a.includes(b) || b.includes(a) ? 2 : 3;
  const y = r.ProductionYear ?? (r.PremiereDate ? Number(r.PremiereDate.slice(0, 4)) : undefined);
  const dated = !year || !y ? 0.5 : Math.min(Math.abs(y - year), 10) / 5;
  return (idHit ? -10 : 0) + named + dated;
}

const resultYear = (r: RemoteSearchResult) => r.ProductionYear ?? (r.PremiereDate ? Number(r.PremiereDate.slice(0, 4)) : undefined);

/* "2010 · A Cold War relic returns…": the year, the artist when there is one, then the start of the overview. */
function metaLine(r: RemoteSearchResult): string {
  const parts: string[] = [];
  const y = resultYear(r);
  if (y) parts.push(String(y));
  const artist = r.AlbumArtist?.Name ?? r.Artists?.map((a) => a.Name).filter(Boolean).join(', ');
  if (artist) parts.push(artist);
  if (r.Overview) parts.push(r.Overview.trim());
  if (!parts.length && r.SearchProviderName) parts.push(providerName(r.SearchProviderName));
  return parts.join(' · ');
}

function listOf(names: string[]): string {
  try { return new Intl.ListFormat(locale(), { type: 'conjunction' }).format(names); } catch { return names.join(', '); }
}

export function IdentifyDialog({ item, onClose, onDone }: { item: BaseItem; onClose: () => void; onDone: () => void }) {
  const kind = searchKind(item.Type);
  const [name, setName] = useState(item.Name ?? '');
  const [year, setYear] = useState(item.ProductionYear ? String(item.ProductionYear) : '');
  const [ids, setIds] = useState<Record<string, string>>({});
  const [infos, setInfos] = useState<ExternalIdInfo[] | null>(null);
  const [current, setCurrent] = useState<Record<string, string>>(item.ProviderIds ?? {});
  const [results, setResults] = useState<RemoteSearchResult[] | null>(null);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState(0);
  const [replace, setReplace] = useState(true);
  const [applying, setApplying] = useState(false);
  const seq = useRef(0);
  const grid = useRef<HTMLDivElement>(null);

  const search = async () => {
    if (!kind) return;
    const n = ++seq.current;
    const y = Number.parseInt(year, 10);
    const typed = Object.fromEntries(Object.entries(ids).map(([k, v]) => [k, v.trim()]).filter(([, v]) => v));
    setSearching(true);
    try {
      const found = await remoteSearch(item, { Name: name.trim() || undefined, Year: Number.isFinite(y) ? y : undefined, ProviderIds: typed });
      if (n !== seq.current) return;
      const ranked = (found ?? [])
        .map((r, at) => ({ r, at, d: distance(r, name, Number.isFinite(y) ? y : undefined, typed) }))
        .sort((a, b) => a.d - b.d || a.at - b.at)
        .map((x) => x.r);
      setResults(ranked);
      setSelected(0);
    } catch (e) {
      if (n !== seq.current) return;
      toast(t('Couldn’t search: {reason}', { reason: (e as Error).message }), 'error');
      setResults((r) => r ?? []);
    } finally {
      if (n === seq.current) setSearching(false);
    }
  };

  useEffect(() => {
    const ctrl = new AbortController();
    getExternalIdInfos(item.Id, ctrl.signal).then((list) => setInfos(idsFor(list, item.Type)), () => setInfos([]));
    // The item handed in may lack its ids (lists don't ask for them); the current match needs them.
    if (!item.ProviderIds) getFullItem(item.Id, ctrl.signal).then((full) => setCurrent(full.ProviderIds ?? {}), () => undefined);
    void search();
    return () => { ctrl.abort(); seq.current++; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [item.Id]);

  const apply = async () => {
    const r = results?.[selected];
    if (!r) return;
    setApplying(true);
    try {
      await applyRemoteSearch(item.Id, r, replace);
      toast(t('Matched {name}', { name: r.Name ?? item.Name }));
      onDone();
    } catch (e) {
      toast(t('Couldn’t use this match: {reason}', { reason: (e as Error).message }), 'error');
      setApplying(false);
    }
  };

  /* Arrow keys move the choice; only the chosen card is in the tab order (a radio group). */
  const onGridKey = (e: KeyboardEvent) => {
    if (!results?.length) return;
    const cols = grid.current ? getComputedStyle(grid.current).gridTemplateColumns.split(' ').length : 1;
    const step: Record<string, number> = { ArrowRight: 1, ArrowLeft: -1, ArrowDown: cols, ArrowUp: -cols };
    let next = selected;
    if (e.key in step) next = Math.max(0, Math.min(results.length - 1, selected + step[e.key]));
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = results.length - 1;
    else return;
    e.preventDefault();
    setSelected(next);
    grid.current?.querySelectorAll<HTMLElement>('[role=radio]')[next]?.focus();
  };

  const matchesCurrent = (r: RemoteSearchResult) => Object.entries(r.ProviderIds ?? {}).some(([k, v]) => v && current[k] === v);
  const providers = [...new Set((results ?? []).map((r) => providerName(r.SearchProviderName)).filter(Boolean))];
  const idFields = infos ?? [];
  const square = item.Type === 'MusicAlbum' || item.Type === 'MusicArtist';

  return (
    <ItemDialog
      id="idf"
      width={1080}
      onClose={onClose}
      poster={primaryUrl(item, 120) ?? null}
      overline={t('Identify')}
      title={question(item.Type)}
      sub={t('Pick the right match and Seedy replaces the info. The files stay where they are.')}
      foot={<ItemCheck checked={replace} onChange={setReplace} label={t('Replace the existing artwork')} disabled={!kind} />}
      actions={
        <>
          <ItemButton onClick={onClose}>{t('Cancel')}</ItemButton>
          <ItemButton kind="primary" onClick={apply} busy={applying} disabled={!results?.[selected] || searching}>{t('Use this match')}</ItemButton>
        </>
      }
    >
      {!kind ? (
        <p class="idf-note">{t('Jellyfin can’t look up a {type} on its own. Identify the show, movie or album it belongs to instead.', { type: typeName(item.Type).toLowerCase() })}</p>
      ) : (
        <>
          <form
            class="idf-fields"
            style={{ gridTemplateColumns: `minmax(0, 2fr) minmax(0, 1fr)${' minmax(0, 1fr)'.repeat(idFields.length)} auto` }}
            onSubmit={(e) => { e.preventDefault(); void search(); }}
          >
            <ItemField label={t('Name')} htmlFor="idf-name" class="idf-name">
              <input id="idf-name" type="text" class="im-input" value={name} onInput={(e) => setName((e.target as HTMLInputElement).value)} />
            </ItemField>
            <ItemField label={t('Year')} htmlFor="idf-year">
              <input id="idf-year" type="text" inputMode="numeric" class="im-input" value={year} placeholder={t('Any')} onInput={(e) => setYear((e.target as HTMLInputElement).value)} />
            </ItemField>
            {idFields.map((info) => (
              <ItemField key={info.Key} label={t('{name} ID', { name: idName(info.Key) })} htmlFor={'idf-' + info.Key}>
                <input id={'idf-' + info.Key} type="text" class="im-input" value={ids[info.Key] ?? ''} placeholder={info.Key === 'Imdb' ? 'tt…' : t('Optional')}
                  onInput={(e) => setIds((m) => ({ ...m, [info.Key]: (e.target as HTMLInputElement).value }))} />
              </ItemField>
            ))}
            <ItemButton kind="primary" type="submit" icon="search" stroke={2.2} busy={searching}>{t('Search')}</ItemButton>
          </form>
          <div class="idf-count" aria-live="polite">
            <span class="idf-count-n">{searching && !results ? t('Searching…') : tn(results?.length ?? 0, '{n} match', '{n} matches')}</span>
            {!!providers.length && <span class="idf-count-from">{t('From {providers} · sorted by how close they are', { providers: listOf(providers) })}</span>}
          </div>
          {results && !results.length && !searching ? (
            <p class="idf-note">{t('Nothing matched. Try the original title, leave the year out, or paste an ID.')}</p>
          ) : (
            <div ref={grid} role="radiogroup" aria-label={t('Matches')} aria-busy={searching ? 'true' : undefined} class={'idf-grid' + (searching ? ' is-busy' : '')} onKeyDown={onGridKey}>
              {results
                ? results.map((r, i) => (
                  <Match key={i} result={r} on={i === selected} current={matchesCurrent(r)} square={square} onPick={() => setSelected(i)} />
                ))
                : Array.from({ length: 6 }, (_, i) => (
                  <span key={i} class="idf-card is-skel" aria-hidden="true">
                    <span class={'idf-art skel' + (square ? ' is-square' : '')} />
                    <span class="skel skel-line" />
                    <span class="skel skel-line is-short" />
                  </span>
                ))}
            </div>
          )}
        </>
      )}
    </ItemDialog>
  );
}

function Match({ result: r, on, current, square, onPick }: { result: RemoteSearchResult; on: boolean; current: boolean; square: boolean; onPick: () => void }) {
  const [broken, setBroken] = useState(false);
  const src = previewUrl(r.ImageUrl);
  const meta = metaLine(r);
  return (
    <button type="button" role="radio" aria-checked={on ? 'true' : 'false'} tabIndex={on ? 0 : -1} class="idf-card" onClick={onPick} title={r.Overview || undefined}>
      <span class={'idf-art' + (square ? ' is-square' : '')}>
        {src && !broken ? (
          <img src={src} alt="" loading="lazy" onError={() => setBroken(true)} />
        ) : (
          <span aria-hidden="true" class="idf-art-name">{r.Name}</span>
        )}
        {current && <span class="idf-current">{t('Current match')}</span>}
        {on && <span aria-hidden="true" class="idf-tick"><Icon name="check" size={16} stroke={3.2} /></span>}
      </span>
      <span class="idf-lines">
        <span class="idf-title">{r.Name}</span>
        {meta && <span class="idf-meta">{meta}</span>}
        <span class="idf-ids">{idsLine(r.ProviderIds) || providerName(r.SearchProviderName)}</span>
      </span>
    </button>
  );
}
