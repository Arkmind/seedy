import { JELLYFIN_WEB } from '../api/client';
import { scanItem } from '../api/jellyfin';
import type { BaseItem } from '../api/types';
import { isAdmin } from '../lib/app-state';
import { copyText } from '../lib/clipboard';
import { t } from '../lib/i18n';
import { MenuItem, toast } from './overlay';

/* The same library (or folder) in Jellyfin's own web app. */
export function jellyfinLibraryUrl(view: Pick<BaseItem, 'Id' | 'CollectionType'>) {
  const ct = view.CollectionType;
  if (ct === 'movies') return `${JELLYFIN_WEB}#/movies.html?topParentId=${view.Id}&collectionType=movies`;
  if (ct === 'tvshows') return `${JELLYFIN_WEB}#/tv.html?topParentId=${view.Id}&collectionType=tvshows`;
  if (ct === 'music') return `${JELLYFIN_WEB}#/music.html?topParentId=${view.Id}&collectionType=music`;
  return `${JELLYFIN_WEB}#/list.html?parentId=${view.Id}`;
}

/* The end of a library's ⋯ menu: Copy link, Open in Jellyfin and, for admins, a scan of its files. */
export function LibraryMenuItems({ item, close, scanLabel, scanSub }: {
  item: Pick<BaseItem, 'Id' | 'Name' | 'CollectionType'>;
  close: () => void;
  scanLabel?: string;
  scanSub?: string;
}) {
  return (
    <>
      <MenuItem icon="link" label={t('Copy link')} onSelect={() => {
        close();
        void copyText(window.location.href).then((ok) => (ok ? toast(t('Link copied')) : toast(t('Couldn’t copy the link'), 'error')));
      }} />
      <MenuItem icon="external" label={t('Open in Jellyfin')} href={jellyfinLibraryUrl(item)} />
      {isAdmin() && (
        <>
          <div class="menu-sep" />
          <MenuItem icon="refresh" label={scanLabel ?? t('Scan library files')} sub={scanSub ?? t('Look for new and changed media')} onSelect={() => {
            close();
            scanItem(item.Id).then(() => toast(t('Scanning {name}', { name: item.Name }), 'pending'), () => toast(t("Couldn't start the scan"), 'error'));
          }} />
        </>
      )}
    </>
  );
}
