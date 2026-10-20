import { Header } from '../ui/navbar/Header';
import { Link } from '../lib/router';
import { t } from '../lib/i18n';

export function NotFound() {
  return (
    <div class="page">
      <Header active={null} />
      <main class="page-main">
        <div class="empty">
          <div class="empty-body">
            <h1 class="empty-title">{t("This page isn't here")}</h1>
            <p class="empty-text">{t('The link may be old, or the title was removed from the server.')}</p>
          </div>
          <Link to="/" class="btn btn-primary is-sm">{t('Back to home')}</Link>
        </div>
      </main>
    </div>
  );
}
