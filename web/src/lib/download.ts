import { downloadUrl } from '../api/client';
import type { BaseItem } from '../api/types';
import { canDownload } from './app-state';

/* Whether this person may save [item]'s file: Jellyfin's per-title CanDownload, else the profile's permission. */
export const mayDownload = (item: BaseItem) => item.CanDownload ?? canDownload();

/* A movie, episode or other video that has a file on the server. */
export const isVideoFile = (item: BaseItem) => item.MediaType === 'Video' && !item.IsFolder && item.LocationType !== 'Virtual';

/* A season with episodes. Jellyfin calls a season "Virtual" when it has no folder of its own (episodes right in the
   show's folder), so that says nothing about its files; seasons that are really missing don't come with episodes. */
export const hasEpisodes = (season: BaseItem) => (season.ChildCount ?? 1) > 0;

/* Saves each item's original file, one after the other (the browser asks once for several). */
export function downloadItems(ids: string[]) {
  ids.forEach((id, i) => {
    window.setTimeout(() => {
      const a = document.createElement('a');
      a.href = downloadUrl(id);
      a.download = '';
      a.rel = 'noreferrer';
      document.body.appendChild(a);
      a.click();
      a.remove();
    }, i * 400);
  });
}
