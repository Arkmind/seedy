import { scanItem } from '../api/jellyfin';
import type { BaseItem } from '../api/types';
import { copyText } from '../lib/clipboard';
import { t } from '../lib/i18n';
import { MenuItem, toast } from './overlay';

/* The end of a library's ⋯ menu: Copy link, Open in Jellyfin and, for admins, a scan of its files. */
export function LibraryMenuItems({ item, jellyfinHref, admin, close, scanLabel, scanSub }: {
  item: Pick<BaseItem, 'Id' | 'Name'>;
  jellyfinHref: string;
  admin: boolean;
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
      <MenuItem icon="external" label={t('Open in Jellyfin')} href={jellyfinHref} />
      {admin && (
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
