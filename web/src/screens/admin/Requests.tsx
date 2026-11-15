import { useEffect, useState } from 'preact/hooks';
import {
  adminSeerrLink, adminSeerrMatch, adminSeerrSetKey, adminSeerrUsers, adminSetCanRequest,
  type AdminConfig, type SeerrAccount, type SeerrUser
} from '../../api/seedy';
import { useStore } from '../../lib/store';
import { meStore, refreshMe } from '../../lib/app-state';
import { Panel, RadioCards, SavedNote, SelectField, SettingRow, Switch } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { Sheet, toast, toastError } from '../../ui/overlay';
import { t } from '../../lib/i18n';
import { AdminLayout, LoadError, PanelSkeletons } from './AdminLayout';
import { ApiKeyRow, ApiKeySheet, ConnLine, OpenAppButton, patchRow, PROFILE_COLOURS, ProfileAvatar, roleOf, TableError, TableSkeleton, TestButton, UrlInput } from './parts';
import {
  accountsStore, editConfig, flushConfig, loadAccounts, mergeServerConfig, seerrTestStore, testSeerr, useAdminConfig
} from './config';

type Req = AdminConfig['requests'];


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
  const [keyOpen, setKeyOpen] = useState(false);
  const test = useStore(seerrTestStore);

  const commitUrl = async (v: string) => {
    setReq({ url: v }, 0);
    if (!v) return;
    try { await flushConfig(); } catch { return; }
    void testSeerr();
  };

  return (
    <Panel id="conn" title={t('Seerr connection')}>
      <SettingRow name={t('Address')} help={t('As this Jellyfin server reaches it')} htmlFor="seerr-url">
        <UrlInput id="seerr-url" value={req.url} placeholder="http://seerr.local:5055" onCommit={commitUrl} />
      </SettingRow>
      <ApiKeyRow name={t('API key')} help={t('Stored on the server, never sent to browsers')} hint={req.apiKeyHint} onOpen={() => setKeyOpen(true)} />
      <div class="setting is-last">
        <ConnState hasUrl={!!req.url} hasKey={!!req.apiKeyHint} />
        <div class="setting-controls">
          <TestButton disabled={!req.url} testing={test.state === 'testing'} onTest={() => void testSeerr()} />
          <OpenAppButton href={req.url ? publicUrl ?? req.url : undefined} label={t('Open Seerr')} />
        </div>
      </div>
      <ApiKeySheet
        open={keyOpen}
        onClose={() => setKeyOpen(false)}
        title={req.apiKeyHint ? t('Replace API key') : t('Add API key')}
        text={t('Copy the key from Settings › General in Seerr. Seedy keeps it on the server and only shows its last four characters.')}
        label={t('Seerr API key')}
        formClass="key-sheet"
        save={async (key) => {
          const res = await adminSeerrSetKey(key);
          mergeServerConfig(res, (server, local) => ({ ...local, requests: { ...local.requests, apiKeyHint: server.requests.apiKeyHint } }));
        }}
        onSaved={() => void testSeerr()}
      />
    </Panel>
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
  return <ConnLine dot={dot} lead={lead} rest={rest} />;
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
      toastError(t('Couldn’t match accounts.'), e);
    } finally {
      setMatching(false);
    }
  };

  const setCan = async (a: SeerrAccount, on: boolean) => {
    patchRow(accountsStore, a.userId, { canRequest: on });
    try {
      const list = await adminSetCanRequest(a.userId, on);
      refreshMe();
      accountsStore.set({ data: list, loading: false });
    } catch (e) {
      patchRow(accountsStore, a.userId, { canRequest: !on });
      toastError(t('Couldn’t change {name}.', { name: a.name }), e);
    }
  };

  const unlink = async (a: SeerrAccount) => {
    setBusy(a.userId);
    try {
      accountsStore.set({ data: await adminSeerrLink(a.userId, null), loading: false });
      refreshMe();
      toast(t('{name} unlinked', { name: a.name }));
    } catch (e) {
      toastError(t('Couldn’t unlink {name}.', { name: a.name }), e);
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
        <TableError text={t('Couldn’t load the accounts.')} error={error.message} onRetry={() => loadAccounts(true)} />
      ) : !data ? (
        <TableSkeleton busy={loading} />
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
      toastError(t('Couldn’t link the account.'), e);
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
        <TableError text={t('Couldn’t load Seerr’s users.')} error={error} />
      ) : !users ? (
        <TableSkeleton rows={3} busy />
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
                  <span class="avatar" aria-hidden="true" style={{ background: PROFILE_COLOURS[i % PROFILE_COLOURS.length] }}>
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
