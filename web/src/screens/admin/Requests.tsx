import { useEffect, useState } from 'preact/hooks';
import {
  adminSeerrLink, adminSeerrMatch, adminSeerrSetKey, adminSeerrUsers, adminSetCanRequest,
  type AdminConfig, type SeerrAccount, type SeerrUser
} from '../../api/seedy';
import { userImageUrl } from '../../api/images';
import { useStore } from '../../lib/store';
import { meStore, refreshMe } from '../../lib/app-state';
import { Panel, RadioCards, SavedNote, SelectField, SettingRow, Switch } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { Sheet, toast } from '../../ui/overlay';
import { t } from '../../lib/i18n';
import { AdminLayout, LoadError, PanelSkeletons } from './AdminLayout';
import {
  accountsStore, editConfig, flushConfig, loadAccounts, mergeServerConfig, seerrTestStore, testSeerr, useAdminConfig
} from './config';

type Req = AdminConfig['requests'];

const AVATAR_COLOURS = ['#f0f0f0', '#9fd3ff', '#b8f59f', '#ffc98a'];

const SHOWS: { key: 'showDiscover' | 'showUpcomingMovies' | 'showRequestButtons' | 'showRequestInSearch'; name: string; help: string }[] = [
  { key: 'showDiscover', name: 'Discover row', help: 'Popular titles not in the library' },
  { key: 'showUpcomingMovies', name: 'Upcoming movies row', help: 'Requested movies not out yet' },
  { key: 'showRequestButtons', name: 'Request buttons', help: 'On Seerr posters and details pages' },
  { key: 'showRequestInSearch', name: 'Request it in search', help: 'When nothing in the library matches' }
];

const setReq = (patch: Partial<Req>, delay?: number) => editConfig((c) => ({ ...c, requests: { ...c.requests, ...patch } }), delay);

/* Tests are re-run when the page opens unless one finished in the last minute. */
const TEST_FRESH_MS = 60_000;

export function AdminRequests() {
  const { config, error, save, reload } = useAdminConfig();
  const hasUrl = !!config?.requests.url;

  useEffect(() => {
    if (!hasUrl) return;
    const test = seerrTestStore.get();
    if (test.state !== 'testing' && Date.now() - test.at > TEST_FRESH_MS) void testSeerr();
  }, [hasUrl]);
  useEffect(() => loadAccounts(), []);

  return (
    <AdminLayout page="requests" title={t('Requests')} lede={t('Connect Seerr to show Discover, Upcoming movies and Request buttons. Approval rules and request limits stay in Seerr.')}>
      {!config && error ? (
        <LoadError error={error} onRetry={reload} />
      ) : !config ? (
        <PanelSkeletons count={3} />
      ) : (
        <>
          <ConnectionPanel req={config.requests} />
          <ShowsPanel req={config.requests} />
          <WhoPanel req={config.requests} />
          <AccountsPanel />
          <SavedNote state={save} />
        </>
      )}
    </AdminLayout>
  );
}

/* ── Seerr connection ── */

function ConnectionPanel({ req }: { req: Req }) {
  // Browsers use Seerr's own Application URL when it has one; the address above is how the server reaches it.
  const publicUrl = useStore(meStore).requests.seerrUrl;
  const [url, setUrl] = useState(req.url);
  const [keyOpen, setKeyOpen] = useState(false);
  useEffect(() => setUrl(req.url), [req.url]);

  const commitUrl = async () => {
    let v = url.trim().replace(/\/+$/, '');
    if (v && !/^https?:\/\//i.test(v)) v = 'http://' + v;
    if (v !== url) setUrl(v);
    if (v === req.url) return;
    setReq({ url: v }, 0);
    if (!v) return;
    try { await flushConfig(); } catch { return; }
    void testSeerr();
  };

  return (
    <Panel id="conn" title={t('Seerr connection')}>
      <SettingRow name={t('Address')} help={t('As this Jellyfin server reaches it')} htmlFor="seerr-url">
        <input
          id="seerr-url"
          class="field is-340"
          type="url"
          inputMode="url"
          value={url}
          placeholder="http://seerr.local:5055"
          autoComplete="off"
          spellcheck={false}
          onInput={(e) => setUrl((e.currentTarget as HTMLInputElement).value)}
          onBlur={() => void commitUrl()}
          onKeyDown={(e) => {
            if (e.key === 'Enter') { e.preventDefault(); void commitUrl(); }
            if (e.key === 'Escape') setUrl(req.url);
          }}
        />
      </SettingRow>
      <SettingRow name={t('API key')} help={t('Stored on the server, never sent to browsers')}>
        <div class="setting-controls is-10">
          <span class="key-saved">
            <Icon name="lock" size={16} />
            {req.apiKeyHint ? t('Saved · ends in {hint}', { hint: req.apiKeyHint }) : t('No key saved')}
          </span>
          <button type="button" class="btn btn-secondary" onClick={() => setKeyOpen(true)}>{req.apiKeyHint ? t('Replace key') : t('Add key')}</button>
        </div>
      </SettingRow>
      <div class="setting is-last">
        <ConnState hasUrl={!!req.url} hasKey={!!req.apiKeyHint} />
        <div class="setting-controls">
          <TestButton disabled={!req.url} />
          {req.url ? (
            <a class="btn btn-secondary" href={publicUrl ?? req.url} target="_blank" rel="noreferrer">{t('Open Seerr')}<Icon name="external" size={16} /></a>
          ) : (
            <button type="button" class="btn btn-secondary" disabled>{t('Open Seerr')}<Icon name="external" size={16} /></button>
          )}
        </div>
      </div>
      <KeySheet open={keyOpen} onClose={() => setKeyOpen(false)} replacing={!!req.apiKeyHint} />
    </Panel>
  );
}

function TestButton({ disabled }: { disabled: boolean }) {
  const test = useStore(seerrTestStore);
  const testing = test.state === 'testing';
  return (
    <button type="button" class="btn btn-primary is-sm" disabled={disabled || testing} aria-busy={testing ? 'true' : undefined} onClick={() => void testSeerr()}>
      {testing ? t('Testing…') : t('Test connection')}
    </button>
  );
}

/* "Connected. Seerr can see Radarr and Sonarr." with the first sentence in bold. */
function splitLead(message: string, fallbackLead: string): [string, string] {
  const m = message.trim().match(/^(.+?[.!])\s+(.*)$/s);
  if (m) return [m[1], m[2]];
  return message.trim() ? [fallbackLead, message.trim()] : [fallbackLead, ''];
}

function ConnState({ hasUrl, hasKey }: { hasUrl: boolean; hasKey: boolean }) {
  const test = useStore(seerrTestStore);
  let dot = 'is-muted';
  let lead = '';
  let rest = '';
  /* The server's test messages are fixed English sentences (see admin.ts), translated before splitting. */
  if (!hasUrl || !hasKey) {
    lead = t('Not connected.');
    rest = !hasUrl ? t('Add the address Jellyfin reaches Seerr at.') : t('Add Seerr’s API key from Settings › General in Seerr.');
  } else if (test.state === 'testing' && !test.result && !test.error) {
    dot = 'is-pending';
    lead = t('Testing…');
    rest = t('Asking Seerr for its status.');
  } else if (test.error) {
    dot = 'is-danger';
    [lead, rest] = [t('Couldn’t reach Seerr.'), test.error];
  } else if (test.result?.ok) {
    dot = '';
    const servers = test.result.radarr && test.result.sonarr ? t('Seerr can see Radarr and Sonarr.')
      : test.result.radarr ? t('Seerr can see Radarr, but no Sonarr server is set up.')
      : test.result.sonarr ? t('Seerr can see Sonarr, but no Radarr server is set up.')
      : t('Seerr has no Radarr or Sonarr server set up yet.');
    [lead, rest] = test.result.message ? splitLead(t(test.result.message), t('Connected.')) : [t('Connected.'), servers];
  } else if (test.result) {
    dot = 'is-danger';
    [lead, rest] = splitLead(test.result.message ? t(test.result.message) : t('Seerr did not accept the connection.'), t('Not connected.'));
  } else {
    dot = 'is-pending';
    lead = t('Not tested yet.');
    rest = t('Test the connection to check the address and key.');
  }
  return (
    <span class="conn-state" role="status" aria-live="polite">
      <span class={'dot ' + dot} />
      <span><b>{lead}</b>{rest ? ' ' + rest : ''}</span>
    </span>
  );
}

function KeySheet({ open, onClose, replacing }: { open: boolean; onClose: () => void; replacing: boolean }) {
  const [key, setKey] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => { if (open) setKey(''); }, [open]);

  const submit = async (e: Event) => {
    e.preventDefault();
    const v = key.trim();
    if (!v) return;
    setBusy(true);
    try {
      await flushConfig().catch(() => { /* the key is saved separately */ });
      const res = await adminSeerrSetKey(v);
      mergeServerConfig(res, (server, local) => ({ ...local, requests: { ...local.requests, apiKeyHint: server.requests.apiKeyHint } }));
      toast(t('API key saved'));
      onClose();
      void testSeerr();
    } catch (err) {
      toast(`${t('Couldn’t save the key.')} ${(err as Error).message || ''}`.trim(), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Sheet open={open} onClose={onClose} label={replacing ? t('Replace API key') : t('Add API key')}>
      <form class="key-sheet" style={{ display: 'contents' }} onSubmit={submit}>
        <div class="sheet-head"><h2 class="sheet-title">{replacing ? t('Replace API key') : t('Add API key')}</h2></div>
        <p class="sheet-text">{t('Copy the key from Settings › General in Seerr. Seedy keeps it on the server and only shows its last four characters.')}</p>
        <input
          class="field"
          type="password"
          value={key}
          aria-label={t('Seerr API key')}
          placeholder={t('Seerr API key')}
          autoComplete="off"
          spellcheck={false}
          onInput={(e) => setKey((e.currentTarget as HTMLInputElement).value)}
        />
        <div class="sheet-actions">
          <button type="button" class="btn btn-ghost is-light" onClick={onClose}>{t('Cancel')}</button>
          <button type="submit" class="btn btn-primary is-sm" disabled={!key.trim() || busy}>{busy ? t('Saving…') : t('Save key')}</button>
        </div>
      </form>
    </Sheet>
  );
}

/* ── What Seedy shows / Who can request ── */

function ShowsPanel({ req }: { req: Req }) {
  return (
    <Panel id="show" title={t('What Seedy shows')}>
      {SHOWS.map((s) => (
        <SettingRow key={s.key} name={t(s.name)} help={t(s.help)}>
          <Switch label={t(s.name)} checked={req[s.key]} onChange={(v) => setReq({ [s.key]: v })} />
        </SettingRow>
      ))}
      <SettingRow name={t('Upcoming movies come from')} help={t('Upcoming shows always come from Jellyfin')} htmlFor="upcoming-src" last>
        <SelectField
          id="upcoming-src"
          minWidth={260}
          value={req.upcomingSource}
          onChange={(v) => setReq({ upcomingSource: v })}
          options={[{ value: 'requests', label: t('Seerr requests') }, { value: 'requestsAndRadarr', label: t('Seerr requests and Radarr calendar') }]}
        />
      </SettingRow>
    </Panel>
  );
}

function WhoPanel({ req }: { req: Req }) {
  return (
    <Panel id="who" title={t('Who can request')} text={t('Requests are made as the signed-in person, so their Seerr limits and auto-approval apply.')} gap={16}>
      <RadioCards
        name="who"
        label={t('Who can request')}
        value={req.who}
        onChange={(v) => setReq({ who: v }, 0)}
        options={[
          { value: 'everyone', title: t('Everyone with a Seerr account'), text: t('Profiles matched below can request.') },
          { value: 'selected', title: t('Only profiles I switch on'), text: t('Others still see Discover, without Request buttons.') }
        ]}
      />
      <SettingRow name={t('Allow 4K requests')} help={t('Needs a 4K server set up in Seerr')} last>
        <Switch label={t('Allow 4K requests')} checked={req.allow4k} onChange={(v) => setReq({ allow4k: v })} />
      </SettingRow>
    </Panel>
  );
}

/* ── Accounts ── */

function roleOf(a: SeerrAccount) {
  if (a.isAdmin) return t('Administrator');
  if (a.maxParentalRating) return t('Maturity limit: {rating}', { rating: a.maxParentalRating });
  return t('User');
}

export function ProfileAvatar({ userId, name, imageTag, index }: { userId: string; name: string; imageTag?: string; index: number }) {
  const img = imageTag ? userImageUrl({ Id: userId, PrimaryImageTag: imageTag }, 72) : undefined;
  return (
    <span class="avatar" aria-hidden="true" style={img ? undefined : { background: AVATAR_COLOURS[index % AVATAR_COLOURS.length] }}>
      {img ? <img src={img} alt="" /> : name.trim().charAt(0).toUpperCase()}
    </span>
  );
}

function AccountsPanel() {
  const { data, error, loading } = useStore(accountsStore);
  const [matching, setMatching] = useState(false);
  const [linking, setLinking] = useState<SeerrAccount | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const matchAgain = async () => {
    setMatching(true);
    try {
      const list = await adminSeerrMatch();
      accountsStore.set({ data: list, loading: false });
      const n = list.filter((a) => a.seerrUserId !== undefined && a.seerrUserId !== null).length;
      toast(t('{n} of {total} profiles matched', { n, total: list.length }));
    } catch (e) {
      toast(`${t('Couldn’t match accounts.')} ${(e as Error).message || ''}`.trim(), 'error');
    } finally {
      setMatching(false);
    }
  };

  const setCan = async (a: SeerrAccount, on: boolean) => {
    accountsStore.set((s) => ({ ...s, data: s.data?.map((x) => (x.userId === a.userId ? { ...x, canRequest: on } : x)) }));
    try {
      const list = await adminSetCanRequest(a.userId, on);
      refreshMe();
      accountsStore.set({ data: list, loading: false });
    } catch (e) {
      accountsStore.set((s) => ({ ...s, data: s.data?.map((x) => (x.userId === a.userId ? { ...x, canRequest: !on } : x)) }));
      toast(`${t('Couldn’t change {name}.', { name: a.name })} ${(e as Error).message || ''}`.trim(), 'error');
    }
  };

  const unlink = async (a: SeerrAccount) => {
    setBusy(a.userId);
    try {
      accountsStore.set({ data: await adminSeerrLink(a.userId, null), loading: false });
      refreshMe();
      toast(t('{name} unlinked', { name: a.name }));
    } catch (e) {
      toast(`${t('Couldn’t unlink {name}.', { name: a.name })} ${(e as Error).message || ''}`.trim(), 'error');
    } finally {
      setBusy(null);
    }
  };

  return (
    <section aria-labelledby="acc-h" class="panel is-gap" style={{ gap: '14px' }}>
      <div class="panel-head-row">
        <div class="panel-head">
          <h2 id="acc-h" class="panel-title">{t('Accounts')}</h2>
          <p class="panel-text">{t('Matched automatically when Seerr uses Jellyfin sign-in.')}</p>
        </div>
        <button type="button" class="btn btn-secondary is-sm" disabled={matching} onClick={() => void matchAgain()}>
          {matching ? t('Matching…') : t('Match again')}
        </button>
      </div>
      {!data && error ? (
        <div class="admin-inline-error" role="alert">
          <span class="dot is-danger" />
          {t('Couldn’t load the accounts.')} {error.message}
          <button type="button" class="btn btn-secondary is-sm" onClick={() => loadAccounts(true)}>{t('Try again')}</button>
        </div>
      ) : !data ? (
        <div class="table-skel" aria-busy={loading ? 'true' : undefined}>
          {[0, 1, 2, 3].map((i) => <span key={i} class="skel" />)}
        </div>
      ) : (
        <div class="table-scroll">
          <div role="table" aria-label={t('Seerr accounts')} class="table acc-table">
            <div role="row" class="table-row">
              <span role="columnheader" class="table-head">{t('Profile')}</span>
              <span role="columnheader" class="table-head">{t('Seerr account')}</span>
              <span role="columnheader" class="table-head is-right">{t('Can request')}</span>
            </div>
            {data.map((a, i) => {
              const linked = a.seerrUserId !== undefined && a.seerrUserId !== null;
              return (
                <div role="row" class="table-row" key={a.userId}>
                  <div role="cell" class="table-cell">
                    <span class="who">
                      <ProfileAvatar userId={a.userId} name={a.name} imageTag={a.hasImage ? a.imageTag : undefined} index={i} />
                      <span class="who-text">
                        <span class="who-name">{a.name}</span>
                        <span class="who-sub">{roleOf(a)}</span>
                      </span>
                    </span>
                  </div>
                  <div role="cell" class="table-cell">
                    {linked ? (
                      <span class="acc-linked">
                        <span class="who-text">
                          <span class="acc-name">{a.seerrName || t('Seerr user {id}', { id: String(a.seerrUserId) })}</span>
                          <span class="acc-sub">
                            <span class="dot" />
                            {a.matchedBy === 'manual' ? t('Linked by an admin') : t('Matched by Jellyfin sign-in')}
                          </span>
                        </span>
                        {a.matchedBy === 'manual' && (
                          <button type="button" class="btn btn-ghost is-sm" disabled={busy === a.userId} onClick={() => void unlink(a)} aria-label={t("Unlink {name}'s Seerr account", { name: a.name })}>
                            {t('Unlink')}
                          </button>
                        )}
                      </span>
                    ) : (
                      <span class="acc-none">
                        <span class="acc-none-label"><span class="dot is-pending" />{t('No Seerr account')}</span>
                        <button type="button" class="btn btn-secondary is-xxs" onClick={() => setLinking(a)} aria-label={t('Link a Seerr account to {name}', { name: a.name })}>
                          {t('Link account')}
                        </button>
                      </span>
                    )}
                  </div>
                  <div role="cell" class="table-cell is-flex is-right">
                    <span class="acc-switch" title={linked ? undefined : t('Link a Seerr account first')}>
                      <Switch
                        label={linked ? t('{name} can request', { name: a.name }) : t('{name} can request. Link a Seerr account first', { name: a.name })}
                        checked={linked && a.canRequest}
                        disabled={!linked}
                        onChange={(v) => void setCan(a, v)}
                      />
                    </span>
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}
      <LinkSheet account={linking} accounts={data ?? []} onClose={() => setLinking(null)} />
    </section>
  );
}

function LinkSheet({ account, accounts, onClose }: { account: SeerrAccount | null; accounts: SeerrAccount[]; onClose: () => void }) {
  const [users, setUsers] = useState<SeerrUser[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const open = !!account;

  useEffect(() => {
    if (!open) return;
    setError(null);
    adminSeerrUsers().then(setUsers, (e: Error) => setError(e.message || t('Seerr did not answer.')));
  }, [open]);

  const pick = async (u: SeerrUser) => {
    if (!account) return;
    setBusy(true);
    try {
      accountsStore.set({ data: await adminSeerrLink(account.userId, u.id), loading: false });
      refreshMe();
      toast(t('{name} linked to {seerr}', { name: account.name, seerr: u.displayName }));
      onClose();
    } catch (e) {
      toast(`${t('Couldn’t link the account.')} ${(e as Error).message || ''}`.trim(), 'error');
    } finally {
      setBusy(false);
    }
  };

  const linkedTo = (id: number) => accounts.find((a) => a.seerrUserId === id && a.userId !== account?.userId)?.name;

  return (
    <Sheet open={open} onClose={onClose} label={t('Link a Seerr account to {name}', { name: account?.name ?? '' })}>
      <div class="sheet-head">
        <h2 class="sheet-title">{t('Link {name}', { name: account?.name ?? '' })}</h2>
        <button type="button" class="btn icon-btn is-40 is-bare" aria-label={t('Close')} onClick={onClose}><Icon name="close" size={16} /></button>
      </div>
      <p class="sheet-text">{t('Pick the Seerr account {name} requests as. Their Seerr limits and auto-approval apply.', { name: account?.name ?? '' })}</p>
      {error ? (
        <div class="admin-inline-error" role="alert"><span class="dot is-danger" />{t('Couldn’t load Seerr’s users.')} {error}</div>
      ) : !users ? (
        <div class="table-skel" aria-busy="true">{[0, 1, 2].map((i) => <span key={i} class="skel" />)}</div>
      ) : users.length === 0 ? (
        <p class="sheet-text">{t('Seerr has no users yet.')}</p>
      ) : (
        <div class="link-list">
          {users.map((u, i) => {
            const other = linkedTo(u.id);
            const current = account?.seerrUserId === u.id;
            return (
              <button
                key={u.id}
                type="button"
                class="menu-item"
                aria-pressed={current ? 'true' : 'false'}
                disabled={busy}
                onClick={() => void pick(u)}
              >
                <span class="lead">
                  <span class="avatar" aria-hidden="true" style={{ background: AVATAR_COLOURS[i % AVATAR_COLOURS.length] }}>
                    {u.avatar ? <img src={u.avatar} alt="" /> : u.displayName.charAt(0).toUpperCase()}
                  </span>
                  <span class="two">
                    <span>{u.displayName}</span>
                    <span class="sub">{other ? t('Linked to {name}', { name: other }) : u.email || t('Not linked to a profile')}</span>
                  </span>
                </span>
                {current && <Icon name="check" size={20} stroke={2.6} class="tick" />}
              </button>
            );
          })}
        </div>
      )}
    </Sheet>
  );
}
