import { useEffect, useRef } from 'preact/hooks';
import type { BaseItem } from '../api/types';
import { episodeTag } from './format';

/* The tab title: what's on screen, then the server's name — "Dune • Seedy" in the player, "Details • Dune • Seedy",
   "Playback • Settings • Seedy". Screens name themselves with useTitle; parts still loading are left out, and the
   title falls back to the server's name when the screen closes. When two screens name themselves at once, the one
   rendered inside the other (or opened last) wins. */

const SEP = ' • ';
let brand = 'Seedy';
let order = 0;
const named = new Map<number, string[]>();

function render() {
  let parts: string[] = [];
  let top = -1;
  for (const [id, p] of named) if (id > top && p.length) { top = id; parts = p; }
  const text = [...parts, brand].join(SEP);
  if (document.title !== text) document.title = text;
}

/* The server's name (branding) ends every title. */
export function setTitleBrand(name: string) {
  brand = name.trim() || 'Seedy';
  render();
}

export function useTitle(...parts: (string | null | undefined | false)[]) {
  const id = useRef(0);
  if (!id.current) id.current = ++order;
  const list = parts.map((p) => (p || '').trim()).filter(Boolean);
  const key = list.join('\n');
  useEffect(() => {
    named.set(id.current, list);
    render();
  }, [key]);
  useEffect(() => () => { named.delete(id.current); render(); }, []);
}

/* A title as the tab names it: "Severance · S1 E3 · Good News About Hell", "Abbey Road · The Beatles". */
export function itemTitle(item: BaseItem | null | undefined): string {
  if (!item) return '';
  const join = (...p: (string | null | undefined)[]) => p.filter(Boolean).join(' · ');
  switch (item.Type) {
    case 'Episode': return join(item.SeriesName, episodeTag(item), item.Name);
    case 'Season': return join(item.SeriesName, item.Name);
    case 'MusicAlbum':
    case 'Audio': return join(item.Name, item.AlbumArtist ?? item.Artists?.[0]);
    default: return item.Name ?? '';
  }
}
