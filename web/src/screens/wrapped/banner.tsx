import { useEffect, useState } from 'preact/hooks';
import { Link } from '../../lib/router';
import { t } from '../../lib/i18n';
import { getWrappedStatus, type WrappedStatus } from './api';
import { bannerText } from './copy';
import { MeduProps } from './medu-props';
import './banner.css';

/* Whether there's a Wrapped to open, asked once a quarter of an hour at most (Home and Settings both look). */
let cached: { at: number; value: Promise<WrappedStatus | null> } | null = null;

export function wrappedStatus(): Promise<WrappedStatus | null> {
  if (!cached || Date.now() - cached.at > 15 * 60_000) {
    cached = { at: Date.now(), value: getWrappedStatus().catch(() => null) };
  }
  return cached.value;
}

export function useWrappedStatus(): WrappedStatus | null {
  const [status, setStatus] = useState<WrappedStatus | null>(null);
  useEffect(() => {
    let live = true;
    wrappedStatus().then((s) => { if (live) setStatus(s); });
    return () => { live = false; };
  }, []);
  return status;
}

/* Home in December (board Android · Home, Wrapped is ready): this year's Wrapped is out. */
export function WrappedBanner() {
  const current = useWrappedStatus()?.current;
  if (!current) return null;
  return (
    <div class="wr-banner-slot">
      <Link to={`/wrapped/${current.year}`} class="wr-banner" aria-label={t('Open Seedy Wrapped {year}', { year: current.year })}>
        <span aria-hidden="true" class="wr-banner-ring" />
        <span class="wr-banner-medu" aria-hidden="true"><MeduProps mood="party" size={62} /></span>
        <span class="wr-banner-text">
          <span class="wr-banner-kicker">{t('New · Seedy Wrapped')}</span>
          <b class="wr-banner-title">{t('Your {year} is ready', { year: current.year })}</b>
          <span class="wr-banner-sub">{bannerText(current.watchMs, current.stories)}</span>
        </span>
        <span aria-hidden="true" class="wr-banner-go">
          <svg viewBox="0 0 24 24" width="18" height="18" style="fill: none; stroke: currentColor; stroke-width: 2.6; stroke-linecap: round; stroke-linejoin: round"><path d="M9 6l6 6-6 6" /></svg>
        </span>
      </Link>
    </div>
  );
}
