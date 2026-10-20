import type { ComponentChildren } from 'preact';
import { t } from '../../lib/i18n';
import { reasonText } from './api';
import './advanced.css';

/* Loading, empty and error pieces shared by the Advanced admin pages, built from the Foundations skeletons. */

export function ListSkeleton({ count = 4, tile = true }: { count?: number; tile?: boolean }) {
  return (
    <div class="adv-list" aria-busy="true" aria-label={t('Loading')}>
      {Array.from({ length: count }, (_, i) => (
        <div key={i} class="adv-skel-row">
          {tile && <span class="skel" style={{ width: '48px', height: '48px', borderRadius: '13px', flex: 'none' }} />}
          <span style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: '8px' }}>
            <span class="skel skel-line" style={{ width: `${180 + ((i * 47) % 90)}px`, height: '15px' }} />
            <span class="skel skel-line" style={{ width: `${110 + ((i * 31) % 70)}px` }} />
          </span>
          <span class="skel" style={{ width: '84px', height: '40px', borderRadius: '12px', flex: 'none' }} />
        </div>
      ))}
    </div>
  );
}

/* `message` is the whole sentence ("Couldn’t load the backups."); the server's reason follows it. */
export function ListError({ message, error, onRetry }: { message: string; error: unknown; onRetry: () => void }) {
  return (
    <div class="adv-list">
      <div class="adv-error" role="alert">
        <span class="dot is-danger" />
        <span style={{ flex: '1 1 260px' }}>{message} {reasonText(error)}</span>
        <button type="button" class="btn btn-secondary is-sm" onClick={onRetry}>{t('Try again')}</button>
      </div>
    </div>
  );
}

export function ListEmpty({ title, children }: { title: string; children?: ComponentChildren }) {
  return (
    <div class="adv-list">
      <div class="adv-empty">
        <span class="adv-empty-title">{title}</span>
        {children && <span class="adv-empty-text">{children}</span>}
      </div>
    </div>
  );
}
