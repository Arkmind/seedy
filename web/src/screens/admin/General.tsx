import { useEffect, useRef, useState } from 'preact/hooks';
import { APP_BASE, SERVER_URL } from '../../api/client';
import { adminRemoveLogo, adminUploadLogo, logoUrl, type AdminConfig, type MaxQuality, type PlaybackPrefs, type SkipMode } from '../../api/seedy';
import { applyBranding } from '../../lib/app-state';
import { NumField, Panel, RadioCards, SavedNote, Segmented, SelectField, SettingRow, Switch } from '../../ui/controls';
import { toast } from '../../ui/overlay';
import { getLang, t } from '../../lib/i18n';
import { AdminLayout, LoadError, PanelSkeletons } from './AdminLayout';
import { editConfig, flushConfig, mergeServerConfig, useAdminConfig } from './config';

const SWATCHES = [
  { name: 'Pink', hex: '#ff9fff' },
  { name: 'Sky', hex: '#9fd3ff' },
  { name: 'Mint', hex: '#b8f59f' },
  { name: 'Apricot', hex: '#ffc98a' }
];

const SKIP_OPTIONS: { value: SkipMode; label: string }[] = [
  { value: 'button', label: 'Show button' },
  { value: 'auto', label: 'Skip automatically' },
  { value: 'off', label: 'Off' }
];

const SKIPS: { key: 'skipIntro' | 'skipRecap' | 'skipCredits'; name: string; help: string }[] = [
  { key: 'skipIntro', name: 'Intros', help: 'Opening titles from media segments' },
  { key: 'skipRecap', name: 'Recaps', help: '"Previously on" segments' },
  { key: 'skipCredits', name: 'Credits', help: 'Goes to Up next or end-of-movie picks' }
];

const PAUSE_OPTIONS = [
  { value: 5, label: 'After 5 seconds' },
  { value: 10, label: 'After 10 seconds' },
  { value: 30, label: 'After 30 seconds' },
  { value: 0, label: 'Never' }
];

const QUALITY_OPTIONS: { value: MaxQuality; label: string }[] = [
  { value: 'auto', label: 'Auto' },
  { value: '4k', label: '4K · 80 Mbps' },
  { value: '1080p', label: '1080p · 20 Mbps' },
  { value: '720p', label: '720p · 8 Mbps' }
];

const HEX = /^#[0-9a-f]{6}$/i;

/* Option labels are English constants, translated at render. */
const tr = <T,>(options: { value: T; label: string }[]) => options.map((o) => ({ ...o, label: t(o.label) }));

export function AdminGeneral() {
  const { config, error, save, reload } = useAdminConfig();

  return (
    <AdminLayout page="general" title={t('Branding & playback')} lede={t('Where Seedy opens, how it looks, and the playback defaults new profiles start with. Server status lives on Overview.')}>
      {!config && error ? (
        <LoadError error={error} onRetry={reload} />
      ) : !config ? (
        <PanelSkeletons count={3} />
      ) : (
        <>
          <OpensPanel config={config} />
          <BrandingPanel config={config} />
          <PlaybackPanel prefs={config.playbackDefaults} />
          <SavedNote state={save} />
        </>
      )}
    </AdminLayout>
  );
}

/* ── Where Seedy opens ── */

function OpensPanel({ config }: { config: AdminConfig }) {
  const server = new URL(SERVER_URL).pathname.replace(/\/$/, '');
  const path = server + '/seedy';
  const choose = (v: AdminConfig['opensAt']) => {
    editConfig((c) => ({ ...c, opensAt: v }), 0);
    /* Seedy's pages just moved (to the server address, or under /seedy): carry on from this page at its new address.
       The Vite dev server always serves Seedy at /seedy, so it stays put. */
    const next = v === 'alongside' ? path : server;
    if (import.meta.env.DEV || next === APP_BASE) return;
    flushConfig().then(() => window.location.assign(next + '/admin'), () => { /* shown by SavedNote */ });
  };
  return (
    <Panel id="opens" title={t('Where Seedy opens')} text={t("Jellyfin's own web app always stays reachable for admins.")} gap={18}>
      <RadioCards
        name="opens"
        label={t('Where Seedy opens')}
        value={config.opensAt}
        onChange={choose}
        options={[
          { value: 'replace', title: t("Replace Jellyfin's web app"), text: t("The server address opens Seedy for everyone. Jellyfin's app moves to /old.") },
          { value: 'alongside', title: t('Alongside Jellyfin'), text: t("Seedy opens at {path}. The server address still opens Jellyfin's app.", { path }) }
        ]}
      />
    </Panel>
  );
}

/* ── Branding ── */

function setBranding(patch: Partial<AdminConfig['branding']>, delay?: number) {
  editConfig((c) => ({ ...c, branding: { ...c.branding, ...patch } }), delay);
}

function BrandingPanel({ config }: { config: AdminConfig }) {
  const b = config.branding;
  return (
    <Panel id="brand" title={t('Branding')} text={t('Shown in the header, on the sign-in page and in browser tabs.')}>
      <NameRow name={b.name} />
      <LogoRow branding={b} />
      <AccentRow accent={b.accent} />
      <SettingRow name={t('Sign-in background')} help={t('Behind the profile picker')} last>
        <Segmented
          label={t('Sign-in background')}
          value={b.signInBackground}
          onChange={(v) => setBranding({ signInBackground: v })}
          options={[{ value: 'backdrops', label: t('Library backdrops') }, { value: 'plain', label: t('Plain') }]}
        />
      </SettingRow>
    </Panel>
  );
}

function NameRow({ name }: { name: string }) {
  const [value, setValue] = useState(name);
  useEffect(() => setValue(name), [name]);
  const commit = () => {
    const v = value.trim();
    if (!v) { setValue(name); return; }
    if (v !== value) setValue(v);
    if (v !== name) setBranding({ name: v }, 0);
  };
  return (
    <SettingRow name={t('Name')} help={t('Next to the logo')} htmlFor="server-name">
      <input
        id="server-name"
        class="field is-280"
        type="text"
        value={value}
        maxLength={40}
        autoComplete="off"
        onInput={(e) => setValue((e.currentTarget as HTMLInputElement).value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') { e.preventDefault(); commit(); }
          if (e.key === 'Escape') setValue(name);
        }}
      />
    </SettingRow>
  );
}

const LOGO_TYPES = ['image/svg+xml', 'image/png', 'image/webp'];

function LogoRow({ branding }: { branding: AdminConfig['branding'] }) {
  const input = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const initial = (branding.name || 'S').trim().charAt(0).toUpperCase();
  const fileName = branding.hasLogo ? branding.logoFileName || t('Custom logo') : t('No logo yet');

  const takeBranding = (res: AdminConfig) => {
    mergeServerConfig(res, (server, local) => ({ ...local, branding: { ...local.branding, hasLogo: server.branding.hasLogo, logoVersion: server.branding.logoVersion, logoFileName: server.branding.logoFileName } }));
    applyBranding({ ...res.branding, name: branding.name, accent: branding.accent });
  };

  const upload = async (file: File) => {
    if (!LOGO_TYPES.includes(file.type) && !/\.(svg|png|webp)$/i.test(file.name)) {
      toast(t('Choose an SVG, PNG or WebP file'), 'error');
      return;
    }
    if (file.size > 2 * 1024 * 1024) {
      toast(t('That file is over 2 MB. Try a smaller logo.'), 'error');
      return;
    }
    setBusy(true);
    try {
      await flushConfig().catch(() => { /* the upload still goes ahead */ });
      takeBranding(await adminUploadLogo(file));
      toast(t('Logo updated'));
    } catch (e) {
      toast(`${t('Couldn’t upload the logo.')} ${(e as Error).message || ''}`.trim(), 'error');
    } finally {
      setBusy(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    try {
      takeBranding(await adminRemoveLogo());
      toast(t('Logo removed'));
    } catch (e) {
      toast(`${t('Couldn’t remove the logo.')} ${(e as Error).message || ''}`.trim(), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <SettingRow name={t('Logo')} help={t('SVG or PNG, square or wide')}>
      <div class="setting-controls is-12">
        <span class={'logo-box' + (busy ? ' is-busy' : '')} aria-hidden="true">
          {branding.hasLogo ? <img src={logoUrl(branding.logoVersion)} alt="" /> : <span class="brand-mark">{initial}</span>}
        </span>
        <span class="logo-file" title={fileName}>{fileName}</span>
        <button type="button" class="btn btn-secondary is-sm" disabled={busy} onClick={() => input.current?.click()} aria-label={branding.hasLogo ? t('Replace logo') : t('Upload a logo')}>
          {branding.hasLogo ? t('Replace') : t('Upload')}
        </button>
        {branding.hasLogo && (
          <button type="button" class="btn btn-ghost is-40" disabled={busy} onClick={remove} aria-label={t('Remove logo')}>{t('Remove')}</button>
        )}
        <input
          ref={input}
          type="file"
          accept=".svg,.png,.webp,image/svg+xml,image/png,image/webp"
          hidden
          onChange={(e) => {
            const el = e.currentTarget as HTMLInputElement;
            const file = el.files?.[0];
            el.value = '';
            if (file) void upload(file);
          }}
        />
      </div>
    </SettingRow>
  );
}

function AccentRow({ accent }: { accent: string }) {
  const [hex, setHex] = useState(accent);
  useEffect(() => setHex(accent), [accent]);
  const valid = HEX.test(hex);
  const current = SWATCHES.findIndex((s) => s.hex.toLowerCase() === accent.toLowerCase());
  const refs = useRef<(HTMLButtonElement | null)[]>([]);

  const choose = (v: string) => {
    const next = v.toLowerCase();
    setHex(next);
    if (next !== accent.toLowerCase()) setBranding({ accent: next });
  };

  const onKey = (e: KeyboardEvent) => {
    const keys: Record<string, number> = { ArrowRight: 1, ArrowDown: 1, ArrowLeft: -1, ArrowUp: -1 };
    if (!(e.key in keys) && e.key !== 'Home' && e.key !== 'End') return;
    e.preventDefault();
    const from = Math.max(0, refs.current.indexOf(document.activeElement as HTMLButtonElement));
    const n = SWATCHES.length;
    const to = e.key === 'Home' ? 0 : e.key === 'End' ? n - 1 : (from + keys[e.key] + n) % n;
    refs.current[to]?.focus();
    choose(SWATCHES[to].hex);
  };

  return (
    <SettingRow name={t('Accent colour')} help={t('Counts, focus ring, selection')}>
      <div class="setting-controls is-10">
        <div role="radiogroup" aria-label={t('Accent colour')} class="swatches" onKeyDown={onKey}>
          {SWATCHES.map((s, i) => {
            const on = i === current;
            return (
              <button
                key={s.hex}
                ref={(el) => { refs.current[i] = el; }}
                type="button"
                role="radio"
                class="swatch"
                aria-checked={on ? 'true' : 'false'}
                aria-label={t(s.name)}
                tabIndex={on || (current < 0 && i === 0) ? 0 : -1}
                style={{ background: s.hex }}
                onClick={() => choose(s.hex)}
              />
            );
          })}
        </div>
        <label class={'hex-field' + (valid ? '' : ' is-invalid')}>
          Hex
          <input
            type="text"
            value={hex}
            maxLength={7}
            spellcheck={false}
            autoComplete="off"
            aria-label={t('Accent colour hex value')}
            aria-invalid={valid ? undefined : 'true'}
            onInput={(e) => {
              let v = (e.currentTarget as HTMLInputElement).value.trim();
              if (v && !v.startsWith('#')) v = '#' + v;
              setHex(v);
              if (HEX.test(v)) choose(v);
            }}
            onBlur={() => { if (!HEX.test(hex)) setHex(accent); }}
            onKeyDown={(e) => { if (e.key === 'Escape') setHex(accent); }}
          />
        </label>
      </div>
    </SettingRow>
  );
}

/* ── Playback defaults ── */

function PlaybackPanel({ prefs }: { prefs: PlaybackPrefs }) {
  const set = (patch: Partial<PlaybackPrefs>) => editConfig((c) => ({ ...c, playbackDefaults: { ...c.playbackDefaults, ...patch } }));
  return (
    <Panel id="play" title={t('Playback defaults')} text={t('New profiles start with these. Each profile can change them in its own settings.')}>
      {SKIPS.map((s) => (
        <SettingRow key={s.key} name={t(s.name)} help={t(s.help)}>
          <Segmented label={t(s.name)} value={prefs[s.key]} options={tr(SKIP_OPTIONS)} onChange={(v) => set({ [s.key]: v })} />
        </SettingRow>
      ))}
      <SettingRow name={t('Auto-play next episode')} help={t('From the Up next card')}>
        <Switch label={t('Auto-play next episode')} checked={prefs.autoPlayNext} onChange={(v) => set({ autoPlayNext: v })} />
      </SettingRow>
      <SettingRow name={t('Countdowns')} help={t('Before Up next and end-of-movie picks start')}>
        <div class="setting-controls">
          <NumField prefix={t('Up next')} suffix="s" value={prefs.upNextSeconds} min={5} max={60} ariaLabel={t('Up next countdown in seconds')} onChange={(v) => set({ upNextSeconds: v })} />
          <NumField prefix={t('Movie picks')} suffix="s" value={prefs.moviePicksSeconds} min={5} max={60} ariaLabel={t('End-of-movie countdown in seconds')} onChange={(v) => set({ moviePicksSeconds: v })} />
        </div>
      </SettingRow>
      <SettingRow name={t('Seek buttons')} help={t('Also the J and L keys')}>
        <div class="setting-controls">
          <NumField prefix={getLang() === 'en' ? 'Back' : t('Back (seek)')} suffix="s" value={prefs.seekBackSeconds} min={5} max={60} ariaLabel={t('Seek back in seconds')} onChange={(v) => set({ seekBackSeconds: v })} />
          <NumField prefix={getLang() === 'en' ? 'Forward' : t('Forward (seek)')} suffix="s" value={prefs.seekForwardSeconds} min={5} max={120} ariaLabel={t('Seek forward in seconds')} onChange={(v) => set({ seekForwardSeconds: v })} />
        </div>
      </SettingRow>
      <SettingRow name={t('Pause screen')} help={t('Shows title and synopsis while paused')} htmlFor="pause-delay">
        <SelectField id="pause-delay" value={PAUSE_OPTIONS.some((o) => o.value === prefs.pauseScreenSeconds) ? prefs.pauseScreenSeconds : 10} options={tr(PAUSE_OPTIONS)} onChange={(v) => set({ pauseScreenSeconds: v })} />
      </SettingRow>
      <SettingRow name={t('Maximum streaming quality')} help={t("Jellyfin's per-user bitrate limits still apply")} htmlFor="max-quality" last>
        <SelectField id="max-quality" value={prefs.maxQuality} options={QUALITY_OPTIONS.map((o) => (o.value === 'auto' ? { ...o, label: t(o.label) } : o))} onChange={(v) => set({ maxQuality: v })} />
      </SettingRow>
    </Panel>
  );
}
