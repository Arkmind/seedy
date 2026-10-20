import type { ComponentChildren } from 'preact';
import { useEffect, useState } from 'preact/hooks';
import { t, tn } from '../../lib/i18n';
import { useStore } from '../../lib/store';
import { Segmented } from '../../ui/controls';
import { confirm, toast } from '../../ui/overlay';
import { AdminLayout, PanelSkeletons } from '../admin/AdminLayout';
import { errText, getNetwork, getSessions, type NetworkConfig, restartingStore, restartServer, saveNetwork } from './api';
import { ChipList, LoadFailed, SettingRow, SwitchCtl, UnsavedBar, useDraft, useLoad, useUnsavedGuard, validAddress } from './parts';
;

/* Jellyfin keeps "" for the root; the board shows it as "/". */
const showBase = (b: string) => (b ? b : '/');
function normaliseBase(v: string): string {
  const x = v.trim().replace(/\/+$/, '');
  if (!x) return '';
  return x.startsWith('/') ? x : '/' + x;
}

type PortKey = 'InternalHttpPort' | 'InternalHttpsPort' | 'PublicHttpPort' | 'PublicHttpsPort';
const PORTS: { key: PortKey; name: string }[] = [
  { key: 'InternalHttpPort', name: 'Local HTTP port' },
  { key: 'InternalHttpsPort', name: 'Local HTTPS port' },
  { key: 'PublicHttpPort', name: 'Public HTTP port' },
  { key: 'PublicHttpsPort', name: 'Public HTTPS port' }
];
const validPort = (n: number) => Number.isInteger(n) && n >= 1 && n <= 65535;

/* Published addresses are "network=address" pairs, e.g. external=https://watch.example.com. */
function validPublished(v: string): string | null {
  const i = v.indexOf('=');
  if (i <= 0 || i === v.length - 1) return t('Write it as network=address, e.g. external=https://watch.example.com.');
  return null;
}

export function ServerNetworking() {
  const net = useLoad(getNetwork);
  const d = useDraft<NetworkConfig>(net.data);
  const [watching, setWatching] = useState(0);
  const [saving, setSaving] = useState(false);
  const restarting = useStore(restartingStore);
  /* Ports are typed as text so a half-typed number can be corrected; they're checked before saving. */
  const [portText, setPortText] = useState<Partial<Record<PortKey, string>>>({});
  useUnsavedGuard(d.changes > 0 || Object.keys(portText).length > 0, t('Your changes to the network settings haven’t been saved yet. If you leave now, they’re lost.'));

  useEffect(() => {
    let alive = true;
    const count = () => getSessions().then((list) => {
      if (!alive) return;
      setWatching(new Set(list.filter((s) => s.NowPlayingItem).map((s) => s.UserId ?? s.Id)).size);
    }, () => undefined);
    void count();
    const timer = window.setInterval(count, 20_000);
    return () => { alive = false; window.clearInterval(timer); };
  }, []);

  const c = d.value;
  /* Valid ports go straight into the draft; only half-typed ones wait here, and each counts as a change. */
  const portErrors = PORTS.filter((p) => portText[p.key] !== undefined);
  const changes = d.changes + portErrors.length;

  const discard = () => { d.reset(); setPortText({}); };

  const save = async () => {
    if (!c || !net.data) return;
    if (portErrors.length) { toast(t('Ports are numbers from 1 to 65535.'), 'error'); return; }
    const ok = await confirm({
      title: t('Save and restart the server?'),
      text: (watching ? tn(watching, '{n} person is watching right now; their playback stops while the server restarts.', '{n} people are watching right now; their playback stops while the server restarts.') + ' ' : '')
        + t('Network changes take effect after the restart, which usually takes less than a minute.'),
      action: t('Save and restart')
    });
    if (!ok) return;
    setSaving(true);
    try {
      /* Jellyfin replaces the whole network configuration: put only this page's changes on what the server has now. */
      const loaded = net.data;
      const changed: Partial<NetworkConfig> = {};
      for (const k of Object.keys(c)) if (JSON.stringify(c[k]) !== JSON.stringify(loaded[k])) changed[k] = c[k];
      if ('BaseUrl' in changed) changed.BaseUrl = normaliseBase(c.BaseUrl);
      const body = { ...(await getNetwork()), ...changed } as NetworkConfig;
      if (import.meta.env.DEV) console.info('[seedy] POST /System/Configuration/network', JSON.stringify(body));
      await saveNetwork(body);
      net.setData(body);
      d.reset();
      setPortText({});
    } catch (e) {
      setSaving(false);
      toast(errText(e, t('Couldn’t save the network settings.')), 'error');
      return;
    }
    try {
      // Shows "Restarting the server…" and waits until it answers again.
      await restartServer();
      net.reload(true);
    } catch (e) {
      toast(t('Settings saved, but the server didn’t restart: {reason}', { reason: errText(e, t('restart it from Overview.')) }), 'error');
    } finally {
      setSaving(false);
    }
  };

  const set = d.patch;
  const note = watching ? tn(watching, '{n} person is watching', '{n} people are watching') : undefined;

  return (
    <AdminLayout page="networking" wide title={t('Networking')} lede={t('Ports, HTTPS, remote access and your home network. Every change here needs a restart.')}>
      <span class="srv-w880" hidden />
      {net.error && !c ? (
        <LoadFailed error={net.error} title={t('Couldn’t load the network settings')} onRetry={() => net.reload()} />
      ) : !c ? (
        <PanelSkeletons count={3} rows={3} />
      ) : (
        <>
          <section aria-labelledby="addr-h" class="panel srv-addr">
            <h2 id="addr-h" class="panel-title">{t('Server address')}</h2>
            <div class="srv-ports">
              <label class="srv-port is-base">
                {t('Base URL')}
                <input type="text" class="field srv-mono-plain" value={showBase(c.BaseUrl)} spellcheck={false}
                  onChange={(e) => set({ BaseUrl: normaliseBase((e.currentTarget as HTMLInputElement).value) })} />
              </label>
              {PORTS.map((p) => {
                const text = portText[p.key] ?? String(c[p.key]);
                const bad = portText[p.key] !== undefined && !validPort(Number(text));
                return (
                  <label key={p.key} class="srv-port">
                    {t(p.name)}
                    <input type="text" inputMode="numeric" class={'field srv-mono-plain' + (bad ? ' is-invalid' : '')} value={text} aria-invalid={bad ? 'true' : undefined}
                      onInput={(e) => {
                        const v = (e.currentTarget as HTMLInputElement).value.replace(/[^\d]/g, '');
                        if (validPort(Number(v))) {
                          set({ [p.key]: Number(v) } as Partial<NetworkConfig>);
                          setPortText((pt) => { const n = { ...pt }; delete n[p.key]; return n; });
                        } else setPortText((pt) => ({ ...pt, [p.key]: v }));
                      }} />
                  </label>
                );
              })}
            </div>
            {portErrors.length > 0
              ? <span class="srv-foot is-error" role="alert">{t('Ports are numbers from 1 to 65535.')}</span>
              : <span class="srv-foot">{t('Public ports are what apps outside your network connect to, for example through router port forwarding.')}</span>}
          </section>

          <Group title="HTTPS" help={t('Most setups put a reverse proxy in front instead and leave this off.')}>
            <Sw name={t('Serve over HTTPS')} help={t('Needs a certificate on this server')} on={c.EnableHttps} set={(v) => set({ EnableHttps: v })} />
            <Sw name={t('Require HTTPS')} help={t('Refuse plain HTTP, except from the local network')} on={c.RequireHttps} set={(v) => set({ RequireHttps: v })} />
            <SettingRow name={t('Certificate file')} help={t('A .pfx file with its private key')} htmlFor="net-cert" labelCls="is-420">
              <input id="net-cert" type="text" class="field srv-mono-plain srv-text" value={c.CertificatePath ?? ''} placeholder="/config/ssl/server.pfx" spellcheck={false}
                onChange={(e) => set({ CertificatePath: (e.currentTarget as HTMLInputElement).value.trim() })} />
            </SettingRow>
            <SettingRow name={t('Certificate password')} help={t('Leave empty if the file has none')} htmlFor="net-certpw" labelCls="is-420">
              <input id="net-certpw" type="password" class="field srv-mono-plain srv-text" value={c.CertificatePassword ?? ''} placeholder={t('Password')} autocomplete="new-password"
                onChange={(e) => set({ CertificatePassword: (e.currentTarget as HTMLInputElement).value })} />
            </SettingRow>
          </Group>

          <Group title={t('Remote access')} help={t('Who can reach this server from outside your home network.')}>
            <Sw name={t('Allow remote access')} help={t('Each user also needs remote access switched on')} on={c.EnableRemoteAccess} set={(v) => set({ EnableRemoteAccess: v })} />
            <SettingRow
              name={c.IsRemoteIPFilterBlacklist ? t('Block these addresses') : t('Only allow these addresses')}
              help={c.IsRemoteIPFilterBlacklist ? t('Switch to an allow list in the menu') : t('Switch to a block list in the menu')}
              labelCls="is-420"
            >
              <ChipList
                label={c.IsRemoteIPFilterBlacklist ? t('Address to block') : t('Address to allow')}
                items={c.RemoteIPFilter ?? []}
                emptyText={c.IsRemoteIPFilterBlacklist ? t('No one is blocked') : t('Everyone can connect')}
                placeholder="203.0.113.7 or 203.0.113.0/24"
                help={t('An address or a range. Only applies to connections from outside your home network.')}
                validate={(v) => validAddress(v, { cidr: true })}
                onChange={(v) => set({ RemoteIPFilter: v })}
                menuTop={(
                  <Segmented
                    label={t('How the list works')}
                    variant="fill"
                    value={c.IsRemoteIPFilterBlacklist ? 'block' : 'allow'}
                    options={[{ value: 'allow', label: t('Allow only these') }, { value: 'block', label: t('Block these') }]}
                    onChange={(v) => set({ IsRemoteIPFilterBlacklist: v === 'block' })}
                  />
                )}
              />
            </SettingRow>
            <SettingRow name={t('Published addresses')} help={t('What apps are told to connect to, per network')} labelCls="is-420">
              <ChipList
                label={t('Published addresses')}
                items={c.PublishedServerUriBySubnet ?? []}
                emptyText={t('Worked out automatically')}
                placeholder="external=https://watch.example.com"
                help={t('network=address. The network is “all”, “internal”, “external”, an interface or a range.')}
                validate={validPublished}
                onChange={(v) => set({ PublishedServerUriBySubnet: v })}
              />
            </SettingRow>
          </Group>

          <Group title={t('Local network')} help={t('Devices here count as at home: no remote limits, no remote sign-in rules.')}>
            <SettingRow name={t('Home network')} help={t('Address ranges that count as local')} labelCls="is-420">
              <ChipList label={t('Home network')} items={c.LocalNetworkSubnets ?? []} emptyText={t('Private address ranges')} placeholder="192.168.1.0/24"
                help={t('A range like 192.168.1.0/24, or a single address.')} validate={(v) => validAddress(v, { cidr: true })}
                onChange={(v) => set({ LocalNetworkSubnets: v })} />
            </SettingRow>
            <SettingRow name={t('Known proxies')} help={t('Reverse proxies whose forwarded addresses can be trusted')} labelCls="is-420">
              <ChipList label={t('Known proxies')} items={c.KnownProxies ?? []} emptyText={t('None')} placeholder="172.18.0.2"
                help={t('The proxy’s address or host name, or a range.')} validate={(v) => validAddress(v, { cidr: true, host: true })}
                onChange={(v) => set({ KnownProxies: v })} />
            </SettingRow>
            <SettingRow name={t('Listen on')} help={t('Network interfaces to use')} labelCls="is-420">
              <ChipList label={t('Listen on')} items={c.LocalNetworkAddresses ?? []} emptyText={t('All interfaces')} placeholder="192.168.1.20"
                help={t('An address this server has on one of its interfaces.')} validate={(v) => validAddress(v)}
                onChange={(v) => set({ LocalNetworkAddresses: v })} />
            </SettingRow>
            <Sw name={t('Find this server automatically')} help={t('Apps on the home network discover it without an address')} on={c.AutoDiscovery} set={(v) => set({ AutoDiscovery: v })} />
            <Sw name="IPv6" help={t('Also listen on IPv6 addresses')} on={c.EnableIPv6} set={(v) => set({ EnableIPv6: v })} />
            <Sw name={t('Ignore virtual interfaces')} help={t('Skips Docker and VPN interfaces such as veth')} on={c.IgnoreVirtualInterfaces} set={(v) => set({ IgnoreVirtualInterfaces: v })} />
          </Group>
        </>
      )}

      {restarting ? (
        <div class="unsaved" role="status">
          <span class="unsaved-text srv-restarting"><span class="spinner" />{t('Restarting the server…')}</span>
        </div>
      ) : (
        <UnsavedBar changes={changes} note={note} saving={saving} saveLabel={t('Save and restart')} onDiscard={discard} onSave={() => void save()} />
      )}
    </AdminLayout>
  );
}

function Group({ title, help, children }: { title: string; help: string; children: ComponentChildren }) {
  return (
    <section class="panel" aria-label={title}>
      <div class="srv-head">
        <h2 class="panel-title">{title}</h2>
        <span class="panel-text">{help}</span>
      </div>
      {children}
    </section>
  );
}

const Sw = ({ name, help, on, set }: { name: string; help: string; on: boolean; set: (v: boolean) => void }) => (
  <SettingRow name={name} help={help} labelCls="is-420"><SwitchCtl on={on} set={set} label={name} /></SettingRow>
);
