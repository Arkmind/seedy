import { useEffect, useState } from 'preact/hooks';
import { t } from '../../lib/i18n';
import { Icon } from '../../ui/icons';
import { Sheet, confirm, toast } from '../../ui/overlay';
import { AdminLayout } from '../admin/AdminLayout';
import { type ApiKeyInfo, createApiKey, getApiKeys, reasonText, revokeApiKey } from './api';
import { ago, copyText, createdDay, realDate } from './format';
import './advanced.css';

const STALE_DAYS = 90;

export function ServerApiKeys() {
  const [keys, setKeys] = useState<ApiKeyInfo[] | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [created, setCreated] = useState<{ app: string; key: string } | null>(null);
  const [adding, setAdding] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);

  const load = () => getApiKeys().then(
    (r) => {
      const list = [...r.Items].sort((a, b) => (realDate(b.DateCreated)?.getTime() ?? 0) - (realDate(a.DateCreated)?.getTime() ?? 0));
      setKeys(list);
      setError(null);
      return list;
    },
    (e: Error) => { setError(e); return null; }
  );
  useEffect(() => { void load(); }, []);

  const add = async (app: string) => {
    const before = new Set((keys ?? []).map((k) => k.AccessToken));
    await createApiKey(app);
    const list = await load();
    const fresh = list?.find((k) => !before.has(k.AccessToken) && k.AppName === app) ?? list?.find((k) => !before.has(k.AccessToken));
    if (fresh) setCreated({ app, key: fresh.AccessToken });
    else toast(t('Key created for {app}', { app }));
  };

  const revoke = async (k: ApiKeyInfo) => {
    const ok = await confirm({
      title: t('Revoke the key for {app}?', { app: k.AppName }),
      text: t('{app} is cut off immediately. To connect it again you’ll have to give it a new key.', { app: k.AppName }),
      action: t('Revoke key'),
      danger: true
    });
    if (!ok) return;
    setBusy(k.AccessToken);
    try {
      await revokeApiKey(k.AccessToken);
      if (created?.key === k.AccessToken) setCreated(null);
      toast(t('Key for {app} revoked', { app: k.AppName }));
      await load();
    } catch (e) {
      toast(t('Couldn’t revoke the key. {error}', { error: reasonText(e) }), 'error', 6000);
    } finally {
      setBusy(null);
    }
  };

  return (
    <AdminLayout
      page="apikeys"
      title={t('API keys')}
      lede={<span class="adv-narrow">{t('For apps that talk to this server without signing in, like Seerr, Sonarr or a monitoring tool. Each key has full admin access.')}</span>}
      actions={
        <button type="button" class="btn btn-primary is-sm" onClick={() => setAdding(true)}>
          <Icon name="plus" size={15} stroke={2.6} />
          {t('New key')}
        </button>
      }
    >
      {created && (
        <section aria-labelledby="adv-created-h" class="adv-created">
          <div class="adv-created-head">
            <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="var(--seedy-ok)" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5" /></svg>
            <h2 id="adv-created-h" class="adv-created-title">{t('Key created for {app}', { app: created.app })}</h2>
          </div>
          <p class="adv-created-text">{t("Copy it now. It won't be shown in full again.")}</p>
          <div class="adv-created-row">
            <code class="adv-keycode adv-mono">{created.key}</code>
            <button type="button" class="btn btn-primary adv-btn48" onClick={() => void copyText(created.key).then((ok) => toast(ok ? t('Key copied') : t('Couldn’t copy. Select the key instead.'), ok ? 'ok' : 'error'))}>{t('Copy key')}</button>
          </div>
        </section>
      )}

      <div class="adv-keys adv-gap24">
        <div role="table" aria-label={t('API keys')} class="adv-keys-table">
          <div role="row" class="adv-krow">
            <span role="columnheader" class="adv-khead">{t('App')}</span>
            <span role="columnheader" class="adv-khead">{t('Key')}</span>
            <span role="columnheader" class="adv-khead">{t('Created')}</span>
            <span role="columnheader" class="adv-khead">{t('Last used')}</span>
            <span role="columnheader" class="adv-khead is-right">{t('Revoke')}</span>
          </div>
          {!keys && error ? (
            <div role="row" class="adv-krow">
              <div role="cell" class="adv-error" style={{ gridColumn: '1 / -1', padding: '10px 8px' }}>
                <span class="dot is-danger" />
                <span style={{ flex: '1 1 260px' }}>{t('Couldn’t load the keys.')} {reasonText(error)}</span>
                <button type="button" class="btn btn-secondary is-sm" onClick={() => void load()}>{t('Try again')}</button>
              </div>
            </div>
          ) : !keys ? (
            [0, 1, 2].map((i) => (
              <div key={i} role="row" class="adv-krow" aria-hidden="true">
                <span class="adv-kcell"><span class="skel skel-line" style={{ display: 'block', width: '120px', height: '14px' }} /></span>
                <span class="adv-kcell"><span class="skel skel-line" style={{ display: 'block', width: '70px' }} /></span>
                <span class="adv-kcell"><span class="skel skel-line" style={{ display: 'block', width: '90px' }} /></span>
                <span class="adv-kcell"><span class="skel skel-line" style={{ display: 'block', width: '80px' }} /></span>
                <span class="adv-kcell adv-krevoke"><span class="skel" style={{ display: 'block', width: '72px', height: '36px', borderRadius: '10px' }} /></span>
              </div>
            ))
          ) : !keys.length ? (
            <div role="row" class="adv-krow">
              <div role="cell" class="adv-kcell" style={{ gridColumn: '1 / -1' }}>{t('No keys yet. Create one for each app that needs to reach the server.')}</div>
            </div>
          ) : (
            keys.map((k) => <KeyRow key={k.AccessToken} k={k} busy={busy === k.AccessToken} onRevoke={() => void revoke(k)} />)
          )}
        </div>
      </div>

      <p class="adv-foot">{t('Revoking a key cuts that app off immediately. Keys unused for {n} days are flagged so you can clean them up.', { n: STALE_DAYS })}</p>

      <NewKeySheet open={adding} onClose={() => setAdding(false)} onCreate={add} />
    </AdminLayout>
  );
}

function KeyRow({ k, busy, onRevoke }: { k: ApiKeyInfo; busy: boolean; onRevoke: () => void }) {
  const createdAt = realDate(k.DateCreated);
  const used = realDate(k.DateLastActivity);
  const ref = used ?? createdAt;
  const stale = !!ref && Date.now() - ref.getTime() > STALE_DAYS * 86_400_000;
  const when = used ? ago(used) : t('Never');
  const usedText = stale ? t('{when} · unused', { when }) : when;
  return (
    <div role="row" class="adv-krow">
      <div role="cell" class="adv-kcell adv-kapp">{k.AppName || t('Unnamed app')}</div>
      <div role="cell" class="adv-kcell adv-kkey adv-mono">•••• {k.AccessToken.slice(-4)}</div>
      <div role="cell" class="adv-kcell">{createdAt ? createdDay(createdAt) : '—'}</div>
      <div role="cell" class={'adv-kcell adv-kused' + (stale ? ' is-stale' : '')} title={used ? used.toLocaleString() : undefined}>
        <span class={'dot' + (stale ? ' is-pending' : '')} />{usedText}
      </div>
      <div role="cell" class="adv-kcell adv-krevoke">
        <button type="button" class="adv-revoke" aria-label={t('Revoke key for {app}', { app: k.AppName })} disabled={busy} onClick={onRevoke}>{busy ? t('Revoking…') : t('Revoke')}</button>
      </div>
    </div>
  );
}

function NewKeySheet({ open, onClose, onCreate }: { open: boolean; onClose: () => void; onCreate: (app: string) => Promise<void> }) {
  const [app, setApp] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (open) { setApp(''); setErr(''); } }, [open]);

  const submit = async (e: Event) => {
    e.preventDefault();
    const name = app.trim();
    if (!name) { setErr(t('Name the app the key is for.')); return; }
    setBusy(true);
    try {
      await onCreate(name);
      onClose();
    } catch (x) {
      setErr(t('Couldn’t create the key. {error}', { error: reasonText(x) }));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Sheet open={open} onClose={onClose} label={t('New API key')}>
      <form class="adv-sheet-fields" onSubmit={(e) => void submit(e)}>
        <div class="sheet-head"><h2 class="sheet-title">{t('New key')}</h2></div>
        <p class="sheet-text">{t('The key gives full admin access to this server. Name it after the app that will use it, so you know which one to revoke later.')}</p>
        <label class="adv-sheet-field">
          <span>{t('App name')}</span>
          <input class="field" type="text" value={app} maxLength={64} placeholder="Home Assistant" autoComplete="off" onInput={(e) => { setApp((e.currentTarget as HTMLInputElement).value); setErr(''); }} />
        </label>
        {err && <p class="form-error" role="alert"><Icon name="alert" size={16} />{err}</p>}
        <div class="sheet-actions">
          <button type="button" class="btn btn-ghost is-light" onClick={onClose}>{t('Cancel')}</button>
          <button type="submit" class="btn btn-primary is-sm" disabled={busy}>{busy ? t('Creating…') : t('Create key')}</button>
        </div>
      </form>
    </Sheet>
  );
}
