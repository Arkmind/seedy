import { adminStatus } from '../../api/seedy';
import { t } from '../../lib/i18n';
import { createStore } from '../../lib/store';

/* Shared by the admin shell and the server pages. */

/* Plugin updates waiting to install: the badge next to "Plugins" in the admin nav. The Plugins page and the
   Overview page refresh it; the shell shows whatever was last counted. */
export const pluginUpdatesStore = createStore<number>(0);

/* The server clock's UTC offset in minutes, once known. Jellyfin runs daily and weekly task triggers (and Seedy its
   weekly backup) on the server's clock, which can differ from the browser's. */
export const serverOffsetStore = createStore<number | null>(null);
let offsetAsked = false;
export function loadServerOffset() {
  if (offsetAsked) return;
  offsetAsked = true;
  adminStatus().then((s) => serverOffsetStore.set(s.utcOffsetMinutes ?? null), () => { offsetAsked = false; });
}
/* " (server time)" when the server's clock isn't this browser's, so "Daily at 03:00" isn't misread. */
export const serverTimeNote = (offset: number | null) =>
  offset !== null && offset !== -new Date().getTimezoneOffset() ? ` ${t('(server time)')}` : '';
