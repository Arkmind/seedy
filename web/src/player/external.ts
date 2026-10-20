import { SERVER_URL, deviceId } from '../api/client';
import type { BaseItem, MediaSource } from '../api/types';
import { token } from './playback';
import { t } from '../lib/i18n';

/* "Offer an external player": hand the stream to another app through its URL scheme. */

export interface ExternalApp { id: string; label: string; sub: string; href: (url: string, title: string) => string }

const enc = encodeURIComponent;
const intent = (url: string, title: string, pkg?: string) =>
  `intent:${url}#Intent;${pkg ? `package=${pkg};` : ''}type=video/*;S.title=${enc(title)};end`;

export function externalApps(): ExternalApp[] {
  const ua = navigator.userAgent;
  const ios = /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
  const android = /Android/.test(ua);
  const mac = /Macintosh|Mac OS X/.test(ua) && !ios;
  const windows = /Windows/.test(ua);
  const vlc: ExternalApp = {
    id: 'vlc',
    label: 'VLC',
    sub: t('Opens the video in {app}', { app: 'VLC' }),
    href: (url, title) => (ios ? `vlc-x-callback://x-callback-url/stream?url=${enc(url)}` : android ? intent(url, title, 'org.videolan.vlc') : `vlc://${url}`)
  };
  if (ios) return [vlc, { id: 'infuse', label: 'Infuse', sub: t('Opens the video in {app}', { app: 'Infuse' }), href: (url) => `infuse://x-callback-url/play?url=${enc(url)}` }];
  if (android) {
    return [
      vlc,
      { id: 'mx', label: 'MX Player', sub: t('Opens the video in {app}', { app: 'MX Player' }), href: (url, title) => intent(url, title, 'com.mxtech.videoplayer.ad') },
      { id: 'any', label: t('Another app'), sub: t('Pick an app that plays video'), href: (url, title) => intent(url, title) }
    ];
  }
  if (mac) {
    return [
      { id: 'iina', label: 'IINA', sub: t('Opens the video in {app}', { app: 'IINA' }), href: (url) => `iina://weblink?url=${enc(url)}` },
      vlc,
      { id: 'infuse', label: 'Infuse', sub: t('Opens the video in {app}', { app: 'Infuse' }), href: (url) => `infuse://x-callback-url/play?url=${enc(url)}` }
    ];
  }
  if (windows) return [vlc, { id: 'potplayer', label: 'PotPlayer', sub: t('Opens the video in {app}', { app: 'PotPlayer' }), href: (url) => `potplayer://${url}` }];
  return [vlc];
}

/* The file itself, as the server has it (external players do their own decoding and seeking). */
export function externalStreamUrl(item: BaseItem, source?: MediaSource): string {
  const ms = source ?? item.MediaSources?.[0];
  const container = (ms?.Container ?? 'mkv').split(',')[0];
  const q = new URLSearchParams({ static: 'true', deviceId: deviceId(), ApiKey: token() });
  if (ms?.Id) q.set('mediaSourceId', ms.Id);
  return `${SERVER_URL}/Videos/${item.Id}/stream.${container}?${q.toString()}`;
}

export function openExternal(app: ExternalApp, item: BaseItem, source?: MediaSource) {
  const href = app.href(externalStreamUrl(item, source), item.Name);
  window.location.href = href;
}
