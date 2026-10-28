import { t } from '../lib/i18n';
import { Link } from '../lib/router';

export interface Crumb { label: string; to?: string }

/* Back button and breadcrumbs (Music and Books boards): the button goes up to the last crumb with a link, the
   last crumb is this page. Over art, the links are brighter (`over`). */
export function BackBar({ crumbs, over, class: cls }: { crumbs: Crumb[]; over?: boolean; class?: string }) {
  // Until the page knows where it sits, the button goes home.
  const up = [...crumbs].reverse().find((c) => c.to) ?? { label: t('Home'), to: '/' };
  return (
    <div class={'backbar' + (over ? ' is-over' : '') + (cls ? ' ' + cls : '')}>
      <Link to={up.to!} class="backbar-btn" aria-label={t('Back to {name}', { name: up.label })}>
        <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path d="M15 5l-7 7 7 7" /></svg>
      </Link>
      <nav aria-label={t('Breadcrumb')} class="backbar-nav">
        <ol class="crumbs">
          {crumbs.map((c, i) => i < crumbs.length - 1 ? (
            <li key={i} class="crumb">
              {c.to ? <Link to={c.to} class="crumb-link">{c.label}</Link> : <span class="crumb-link">{c.label}</span>}
              <svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true"><path d="M9 6l6 6-6 6" /></svg>
            </li>
          ) : (
            <li key={i} class="crumb"><span aria-current="page" class="crumb-here">{c.label}</span></li>
          ))}
        </ol>
      </nav>
    </div>
  );
}
