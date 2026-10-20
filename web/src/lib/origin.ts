import { recordOrigin } from '../api/seedy';
import type { BaseItem } from '../api/types';

/* Remembers which home row a title was opened from, so the play that follows can be credited to it.
   The play log uses this to move the rows each person plays from up their home screen. */

interface Hint { id: string; row: string; at: number }
let hint: Hint | null = null;
const WINDOW_MS = 30 * 60 * 1000;

export function noteOrigin(id: string, row: string) {
  hint = { id, row, at: Date.now() };
}

export function originFor(item: BaseItem): string | undefined {
  if (!hint || Date.now() - hint.at > WINDOW_MS) return undefined;
  const ids = [item.Id, item.SeriesId, item.SeasonId, item.ParentId, item.AlbumId].filter(Boolean);
  return ids.includes(hint.id) ? hint.row : undefined;
}

export function sendOrigin(item: BaseItem, explicit?: string | null) {
  const row = explicit || originFor(item);
  if (!row) return;
  hint = null;
  recordOrigin(item.Id, row).catch(() => undefined);
}
