import { userImageUrl } from '../../api/images';
import { t } from '../../lib/i18n';
import type { Store } from '../../lib/store';

/* Pieces the Seedy admin pages share: the profile tables of Requests, Music & Books and Play log. */

/* Letters without a picture take turns down these four colours. */
export const PROFILE_COLOURS = ['#f0f0f0', '#9fd3ff', '#b8f59f', '#ffc98a'];

export function ProfileAvatar({ userId, name, imageTag, index }: { userId: string; name: string; imageTag?: string; index: number }) {
  const img = imageTag ? userImageUrl({ Id: userId, PrimaryImageTag: imageTag }, 72) : undefined;
  return (
    <span class="avatar" aria-hidden="true" style={img ? undefined : { background: PROFILE_COLOURS[index % PROFILE_COLOURS.length] }}>
      {img ? <img src={img} alt="" /> : name.trim().charAt(0).toUpperCase()}
    </span>
  );
}

/* "Administrator", "Maturity limit: 12" or "User", under a profile's name. */
export function roleOf(p: { isAdmin?: boolean; maxParentalRating?: string | null }) {
  if (p.isAdmin) return t('Administrator');
  if (p.maxParentalRating) return t('Maturity limit: {rating}', { rating: p.maxParentalRating });
  return t('User');
}

export function TableError({ text, error, onRetry }: { text: string; error: string; onRetry?: () => void }) {
  return (
    <div class="admin-inline-error" role="alert">
      <span class="dot is-danger" />
      {text} {error}
      {onRetry && <button type="button" class="btn btn-secondary is-sm" onClick={onRetry}>{t('Try again')}</button>}
    </div>
  );
}

export function TableSkeleton({ rows = 4, busy }: { rows?: number; busy?: boolean }) {
  return (
    <div class="table-skel" aria-busy={busy ? 'true' : undefined}>
      {Array.from({ length: rows }, (_, i) => <span key={i} class="skel" />)}
    </div>
  );
}

/* Changes one profile's row in place, before the server answers (and back again if it refuses). */
export function patchRow<S extends { data?: { userId: string }[] }>(store: Store<S>, userId: string, patch: Partial<NonNullable<S['data']>[number]>) {
  store.set((s) => ({ ...s, data: s.data?.map((x) => (x.userId === userId ? { ...x, ...patch } : x)) }));
}
