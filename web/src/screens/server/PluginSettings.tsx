import { useEffect, useMemo, useRef, useState } from 'preact/hooks';
import { Link, href, navigate, useLocation } from '../../lib/router';
import { useStore } from '../../lib/store';
import { t } from '../../lib/i18n';
import { Icon } from '../../ui/icons';
import { confirm, toast } from '../../ui/overlay';
import { APP_BASE, request } from '../../api/client';
import { AdminLayout, PanelSkeletons } from '../admin/AdminLayout';
import { setLeaveGuard } from '../admin/config';
import { configPageUrl, normId, reasonText, type ConfigPage } from './api';
import { catalogStore, describe, installedStore, loadCatalog, loadInstalled, settingsPages, sourceLabel, statusOf, versionLabel } from './plugins';
import { frameDocument, parsePage, releaseFrame, seedyPathFor, type FrameHost, type ParsedPage } from './plugin-frame';
import { ListEmpty } from './advanced-parts';
import { FolderPicker } from './parts';
import './advanced.css';

interface Tab { name: string; page: string | null; href: string }

/* A plugin's own settings page, inside Seedy's admin: /admin/plugins/{id}, and ?page= for its other pages. */
export function ServerPluginSettings({ id }: { id: string }) {
  const loc = useLocation();
  const inst = useStore(installedStore);
  const cat = useStore(catalogStore);
  const [tabs, setTabs] = useState<{ list: Tab[]; index: number } | null>(null);
  const [busy, setBusy] = useState(false);
  const [links, setLinks] = useState<{ text: string; href: string }[]>([]);

  useEffect(() => {
    if (!inst.plugins && !inst.loading) void loadInstalled();
    void loadCatalog();
  }, []);

  const plugin = inst.plugins?.find((p) => normId(p.Id) === normId(id));
  const server = inst.serverVersion ?? '';
  const view = plugin ? describe(plugin, cat.packages, inst.pages, server) : undefined;
  const asked = loc.query.get('page');
  const page = asked ? (inst.pages ?? []).find((pg) => pg.Name === asked) : view?.page;

  useEffect(() => setLinks([]), [page?.Name]);

  /* Seedy's own settings are Seedy's admin. */
  useEffect(() => { if (view?.self) navigate('/admin', { replace: true }); }, [view?.self]);

  const pathFor = (pg: ConfigPage) => {
    const pid = normId(pg.PluginId || id);
    const first = settingsPages(inst.pages, pid)[0];
    const main = pid === normId(id) ? view?.page : first;
    return '/admin/plugins/' + pid + (main && main.Name === pg.Name ? '' : '?page=' + encodeURIComponent(pg.Name));
  };
  const openPage = (name: string) => {
    const pg = (inst.pages ?? []).find((x) => x.Name.toLowerCase() === name.toLowerCase());
    if (pg) navigate(pathFor(pg));
    else window.open(configPageUrl(name), '_blank', 'noopener');
  };

  const overline = (
    <Link to="/admin/plugins" class="srv-overline"><Icon name="chevronLeft" size={16} stroke={2.2} />{t('Plugins')}</Link>
  );
  const name = plugin?.Name ?? '';
  const st = plugin ? statusOf(plugin.Status) : null;
  const lede = view && st ? [versionLabel(view, server), sourceLabel(view), plugin?.Status !== 'Active' ? st.label : null].filter(Boolean).join(' · ') : undefined;
  const title = plugin ? name : inst.plugins || inst.error ? t('Plugin') : <span class="skel skel-line" style={{ display: 'inline-block', width: '200px', height: '36px' }} />;
  const actions = busy || links.length ? (
    <>
      {busy && <span class="plg-busy" role="status"><span class="spinner" aria-hidden="true" />{t('Working…')}</span>}
      {links.map((l) => (
        <a key={l.href} class="btn btn-secondary is-sm" href={l.href} target="_blank" rel="noreferrer">{l.text}<Icon name="external" size={16} /></a>
      ))}
    </>
  ) : undefined;

  let body: preact.JSX.Element;
  if (!inst.plugins && inst.error) {
    body = <ListEmpty title={t('Couldn’t read the installed plugins.')}>{reasonText(inst.error)} <button type="button" class="btn btn-secondary is-sm" onClick={() => void loadInstalled()}>{t('Try again')}</button></ListEmpty>;
  } else if (!inst.plugins) {
    body = <PanelSkeletons count={1} rows={4} />;
  } else if (!plugin) {
    body = <ListEmpty title={t('This plugin isn’t installed')}>{t('It may have been removed. Plugins lists what the server has now.')}</ListEmpty>;
  } else if (!page) {
    body = (
      <ListEmpty title={plugin.Status === 'Active' ? t('{plugin} has no settings', { plugin: name }) : t('{plugin} isn’t running', { plugin: name })}>
        {plugin.Status === 'Active'
          ? t('This plugin works without any setup.')
          : t('Its settings page comes back once the plugin loads. {status}.', { status: st?.label ?? '' })}
      </ListEmpty>
    );
  } else {
    body = (
      <PluginFrame
        key={page.Name}
        page={page}
        plugin={name}
        query={Object.fromEntries(loc.query.entries())}
        onBusy={setBusy}
        onTabs={(list, index) => setTabs({ list, index })}
        onLinks={setLinks}
        openPage={openPage}
      />
    );
  }

  const shownTabs = tabs && page && tabs.list.some((x) => x.page === page.Name || x.page?.toLowerCase() === page.Name.toLowerCase()) ? tabs : null;
  return (
    <AdminLayout page="plugins" title={title} lede={lede} wide overline={overline} actions={actions}>
      {shownTabs && (
        <nav aria-label={t('{plugin} pages', { plugin: name })} class="tab-row adv-tabs">
          {shownTabs.list.map((tab, i) => {
            const pg = tab.page ? (inst.pages ?? []).find((x) => x.Name.toLowerCase() === tab.page!.toLowerCase()) : undefined;
            const current = pg ? pg.Name === page?.Name : i === shownTabs.index;
            return (
              <a
                key={tab.href + i}
                class="tab"
                href={pg ? href(pathFor(pg)) : configPageUrl(tab.page ?? '')}
                aria-current={current ? 'page' : undefined}
                onClick={(e) => {
                  if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
                  e.preventDefault();
                  if (tab.page) openPage(tab.page);
                }}
              >
                {tab.name}
              </a>
            );
          })}
        </nav>
      )}
      {body}
      {page && plugin && (
        <p class="adv-foot">
          {t('This page comes from {plugin} itself.', { plugin: name })}{' '}
          <a href={configPageUrl(page.Name)} target="_blank" rel="noreferrer">{t('Open it in Jellyfin’s web app')}</a>
        </p>
      )}
    </AdminLayout>
  );
}

function PluginFrame({ page, plugin, query, onBusy, onTabs, onLinks, openPage }: {
  page: ConfigPage;
  plugin: string;
  query: Record<string, string>;
  onBusy: (on: boolean) => void;
  onTabs: (list: Tab[], index: number) => void;
  onLinks: (list: { text: string; href: string }[]) => void;
  openPage: (name: string) => void;
}) {
  const frame = useRef<HTMLIFrameElement>(null);
  const [parsed, setParsed] = useState<ParsedPage | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [attempt, setAttempt] = useState(0);
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [waited, setWaited] = useState(false);
  const [height, setHeight] = useState(0);
  const [picker, setPicker] = useState<{ title?: string; files?: boolean; path?: string; resolve: (p: string | null) => void } | null>(null);
  /* The frame's callbacks outlive renders; they read the latest props from here. */
  const latest = useRef({ plugin, onBusy, onTabs, onLinks, openPage });
  latest.current = { plugin, onBusy, onTabs, onLinks, openPage };
  const key = useMemo(() => 'p' + Math.random().toString(36).slice(2), [page.Name, attempt]);

  useEffect(() => {
    let live = true;
    setParsed(null);
    setError(null);
    setReady(false);
    request<Response>('GET', '/web/ConfigurationPage', { query: { name: page.Name }, raw: true })
      .then((res) => res.text())
      .then((html) => { if (live) setParsed(parsePage(html)); }, (e) => { if (live) setError(e); });
    return () => { live = false; };
  }, [page.Name, attempt]);

  useEffect(() => () => { releaseFrame(key); latest.current.onBusy(false); }, [key]);

  /* Pages often show "loading" until their settings arrive: reveal then, or after a moment regardless. */
  useEffect(() => {
    if (!ready) return;
    const id = window.setTimeout(() => setWaited(true), 2500);
    return () => window.clearTimeout(id);
  }, [ready]);

  const srcdoc = useMemo(() => {
    if (!parsed) return '';
    const host: FrameHost = {
      loading: (on) => { setBusy(on); latest.current.onBusy(on); },
      toast: (text, tone) => toast(text, tone ?? 'ok', tone === 'error' ? 6000 : 3600),
      alert: (title, text) => confirm({ title: title || latest.current.plugin, text, action: t('OK'), cancel: false }).then(() => undefined),
      confirm: (title, text) => confirm({ title: title || latest.current.plugin, text, action: t('OK') }),
      tabs: (list, index) => latest.current.onTabs(list, index),
      openPage: (name) => latest.current.openPage(name),
      openUrl: (url) => {
        const u = new URL(url);
        const inApp = u.origin === window.location.origin && u.pathname.toLowerCase().startsWith(APP_BASE.toLowerCase() + '/')
          && !/^\/(old|web|seedy)(\/|$)/i.test(u.pathname.slice(APP_BASE.length));
        const path = inApp ? u.pathname.slice(APP_BASE.length) + u.search : seedyPathFor(url);
        if (path) navigate(path);
        else window.open(url, '_blank', 'noopener');
      },
      pickPath: (o) => new Promise((resolve) => setPicker({ ...o, resolve })),
      closePicker: () => setPicker(null),
      height: (px) => setHeight(px),
      links: (list) => latest.current.onLinks(list),
      ready: () => setReady(true),
      failed: (e) => { console.warn('[plugin page]', e); setError(e); }
    };
    return frameDocument(parsed, key, host, page.Name, query, latest.current.plugin);
  }, [parsed, key]);

  /* The part of the frame on screen (below the sticky header), for the page's fixed bars and dialogs. */
  useEffect(() => {
    let raf = 0;
    const update = () => {
      raf = 0;
      const f = frame.current;
      const root = f?.contentDocument?.documentElement;
      if (!f || !root) return;
      const r = f.getBoundingClientRect();
      const top = Math.max(0, document.querySelector('.admin-header')?.getBoundingClientRect().bottom ?? 0);
      const from = Math.max(r.top, top);
      root.style.setProperty('--seedy-vt', Math.max(0, from - r.top) + 'px');
      root.style.setProperty('--seedy-vh', Math.max(0, Math.min(r.bottom, window.innerHeight) - from) + 'px');
    };
    const later = () => { if (!raf) raf = requestAnimationFrame(update); };
    later();
    window.addEventListener('scroll', later, { passive: true });
    window.addEventListener('resize', later);
    return () => { window.removeEventListener('scroll', later); window.removeEventListener('resize', later); cancelAnimationFrame(raf); };
  }, [srcdoc, height, ready]);

  /* Pages that warn before closing with unsaved changes (a beforeunload listener) hold Seedy's links too. */
  useEffect(() => {
    if (!ready) return;
    setLeaveGuard(() => {
      const win = frame.current?.contentWindow as (Window & typeof globalThis) | null | undefined;
      if (!win) return false;
      const ev = new win.Event('beforeunload', { cancelable: true });
      win.dispatchEvent(ev);
      return ev.defaultPrevented;
    }, t('Your changes to {plugin}’s settings haven’t been saved yet. If you leave now, they’re lost.', { plugin: latest.current.plugin }));
    return () => setLeaveGuard(null);
  }, [ready, key]);

  if (error) {
    return (
      <div class="error-box" role="alert">
        <h2>{t('Couldn’t open this settings page')}</h2>
        <p>{reasonText(error) || t('The plugin’s page didn’t load.')} {t('Try again, or open it in Jellyfin’s web app.')}</p>
        <div class="plg-error-actions">
          <button type="button" class="btn btn-primary is-sm" onClick={() => setAttempt((n) => n + 1)}>{t('Try again')}</button>
          <a class="btn btn-secondary is-sm" href={configPageUrl(page.Name)} target="_blank" rel="noreferrer">{t('Open in Jellyfin’s web app')}<Icon name="external" size={16} /></a>
        </div>
      </div>
    );
  }

  const shown = ready && (!busy || waited);
  return (
    <section class={'panel plg-panel' + (shown ? '' : ' is-loading')} aria-busy={shown ? undefined : 'true'}>
      {!shown && <PluginSkeleton />}
      {srcdoc && (
        <iframe
          ref={frame}
          key={key}
          class="plg-frame"
          title={t('{plugin} settings', { plugin })}
          srcdoc={srcdoc}
          style={{ height: Math.max(height, 120) + 'px' }}
          tabIndex={shown ? undefined : -1}
        />
      )}
      <FolderPicker
        open={!!picker}
        start={picker?.path}
        title={picker?.title || (picker?.files ? t('Choose a file') : t('Choose a folder'))}
        files={picker?.files}
        onClose={() => { picker?.resolve(null); setPicker(null); }}
        onPick={(p) => { picker?.resolve(p); setPicker(null); }}
      />
    </section>
  );
}

function PluginSkeleton() {
  return (
    <div class="plg-skel" aria-hidden="true">
      {Array.from({ length: 4 }, (_, i) => (
        <div key={i} class="plg-skel-row">
          <span class="skel skel-line" style={{ width: '180px', height: '14px' }} />
          <span class="skel plg-skel-field" />
          <span class="skel skel-line" style={{ width: i % 2 ? '52%' : '68%' }} />
        </div>
      ))}
    </div>
  );
}
