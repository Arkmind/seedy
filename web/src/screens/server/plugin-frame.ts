import { h, render } from 'preact';
import { APP_BASE, APP_VERSION, CLIENT_NAME, SERVER_URL, authHeader, currentUserId, deviceId, deviceName, getSession, sessionToken } from '../../api/client';
import { getLang, t } from '../../lib/i18n';
import { Icon, type IconName } from '../../ui/icons';
import css from './plugin-page.css?inline';

/* A plugin's own settings page, shown inside Seedy. Plugins write these pages for Jellyfin's web app: HTML with inline
   scripts or a controller module that call the app's globals (ApiClient, Dashboard, LibraryMenu…) and use its emby-*
   elements. The page runs in a same-origin frame of its own (its scripts and styles can't touch Seedy's, and leave
   with it), next to a small stand-in for those globals that talks to the server as the signed-in admin and hands
   dialogs, toasts, tabs and the folder picker to the Seedy page around it. Behaviour follows jellyfin-web's
   viewContainer/viewManager (page events) and jellyfin-apiclient (ApiClient.fetch/ajax). */

/** What the frame asks of the Seedy page around it. */
export interface FrameHost {
  loading(on: boolean): void;
  toast(text: string, tone?: 'ok' | 'error'): void;
  alert(title: string, text: string): Promise<void>;
  confirm(title: string, text: string): Promise<boolean>;
  /** LibraryMenu.setTabs: the plugin's own page tabs. */
  tabs(list: { name: string; page: string | null; href: string }[], index: number): void;
  /** Another plugin page ("configurationpage?name=…"). */
  openPage(name: string): void;
  /** Any other link the page follows. */
  openUrl(url: string): void;
  pickPath(opts: { title?: string; files?: boolean; path?: string }): Promise<string | null>;
  closePicker(): void;
  height(px: number): void;
  /** Links that sat beside the plugin's own title (its "Help"), shown beside Seedy's. */
  links(list: { text: string; href: string }[]): void;
  ready(): void;
  failed(error: unknown): void;
}

type Win = Window & typeof globalThis & Record<string, unknown>;
type Req = { url: string; type?: string; method?: string; data?: unknown; contentType?: string; dataType?: string; headers?: Record<string, string> };
type Query = Record<string, unknown> | undefined;

interface Entry { host: FrameHost; page: string; params: Record<string, string>; install?: unknown; start?: unknown }
const hosts: Record<string, Entry> = {};
(window as unknown as Record<string, unknown>).__seedyPluginFrames = hosts;

/* "configurationpage?name=Foo", "#!/configurationpage?name=Foo", "/web/#/configurationpage?name=Foo" → "Foo". */
export function pageFromUrl(url: string): string | null {
  const m = /configurationpage\?(?:[^#]*?&)?name=([^&#]+)/i.exec(url);
  if (!m) return null;
  try { return decodeURIComponent(m[1].replace(/\+/g, ' ')); } catch { return m[1]; }
}

/* Jellyfin's own dashboard links, where Seedy has the same page. */
const DASHBOARD: [RegExp, string][] = [
  [/^\/?dashboard\/users\b|^\/?userprofiles/i, '/admin/users'],
  [/^\/?dashboard\/libraries\b|^\/?library\.html/i, '/admin/libraries'],
  [/^\/?dashboard\/playback\b|^\/?encodingsettings/i, '/admin/playback'],
  [/^\/?dashboard\/networking\b|^\/?networking/i, '/admin/networking'],
  [/^\/?dashboard\/livetv\b|^\/?livetvstatus/i, '/admin/livetv'],
  [/^\/?dashboard\/devices\b|^\/?devices/i, '/admin/devices'],
  [/^\/?dashboard\/activity\b/i, '/admin/activity'],
  [/^\/?dashboard\/plugins\/catalog\b|^\/?availableplugins/i, '/admin/plugins?view=catalog'],
  [/^\/?dashboard\/plugins\/repositories\b|^\/?repositories/i, '/admin/plugins?view=repositories'],
  [/^\/?dashboard\/plugins\b|^\/?installedplugins/i, '/admin/plugins'],
  [/^\/?dashboard\/tasks\b|^\/?scheduledtasks/i, '/admin/tasks'],
  [/^\/?dashboard\/backups\b/i, '/admin/backups'],
  [/^\/?dashboard\/logs\b|^\/?log\.html/i, '/admin/logs'],
  [/^\/?dashboard\/keys\b|^\/?apikeys/i, '/admin/apikeys'],
  [/^\/?dashboard\/settings\b|^\/?dashboardgeneral/i, '/admin/settings'],
  [/^\/?dashboard\/?$|^\/?dashboard\.html/i, '/admin/server']
];

/** The Seedy page for a link into Jellyfin's web app, when there is one ("#/details?id=…" → "/details/…"). */
export function seedyPathFor(url: string): string | null {
  const hash = url.replace(/^[^#]*#!?/, '');
  if (hash === url) return null;
  const [route, query = ''] = hash.split('?');
  const id = new URLSearchParams(query).get('id');
  if (/^\/?details\b/i.test(route) && id) return '/details/' + id;
  for (const [re, path] of DASHBOARD) if (re.test(route)) return path;
  return null;
}

/* ── The page's HTML ── */

/* Jellyfin's dictionary keys that plugin pages write as ${Key}; English text, translated by Seedy. */
const KEYS: Record<string, string> = {
  Save: 'Save', ButtonSave: 'Save', ButtonCancel: 'Cancel', Cancel: 'Cancel', ButtonOk: 'OK', Ok: 'OK',
  ButtonLearnMore: 'Learn more', Help: 'Help', ButtonHelp: 'Help', ButtonAdd: 'Add', Add: 'Add', ButtonEdit: 'Edit', Edit: 'Edit',
  ButtonDelete: 'Delete', Delete: 'Delete', ButtonRemove: 'Remove', Remove: 'Remove', ButtonRefresh: 'Refresh', Refresh: 'Refresh',
  LabelUsername: 'Username', LabelPassword: 'Password', LabelName: 'Name', Name: 'Name', LabelEnabled: 'Enabled',
  HeaderLibraries: 'Libraries', HeaderLibraryAccess: 'Library access', OptionEnableAccessToAllLibraries: 'Enable access to all libraries',
  LibraryAccessHelp: 'Select the libraries to share with this user. Administrators will be able to edit all folders using the metadata manager.',
  HeaderAdvanced: 'Advanced', Settings: 'Settings', HeaderSettings: 'Settings', ButtonBack: 'Back', Back: 'Back', Users: 'Users', HeaderUsers: 'Users'
};

/* jellyfin-web answers an unknown key with the key itself; "HeaderMyThings" reads better as "My things". */
function translateKey(key: string): string {
  if (KEYS[key]) return t(KEYS[key]);
  const words = key.replace(/^(Button|Label|Header|Option|Title|Message)(?=[A-Z])/, '').replace(/([a-z0-9])([A-Z])/g, '$1 $2').toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/* ${Key} outside scripts, as jellyfin-web's globalize.translateHtml does (scripts are left alone). */
function translateHtml(html: string): string {
  return html.split(/(<script\b[\s\S]*?<\/script>)/i).map((part, i) => (i % 2 ? part : part.replace(/\$\{([A-Za-z][A-Za-z0-9_]*)\}/g, (_, k: string) => translateKey(k)))).join('');
}

export interface ParsedPage { html: string; controller: string | null; title: string | null }

/** The page's element, as jellyfin-web's viewContainer takes it: the first div[data-role=page], scripts included. */
export function parsePage(raw: string): ParsedPage {
  const text = translateHtml(raw.replaceAll('<!--<script', '<script').replaceAll('</script>-->', '</script>'));
  const doc = new DOMParser().parseFromString(text, 'text/html');
  const view = doc.querySelector('div[data-role="page"]') ?? doc.querySelector('[data-role="page"]');
  const el = view ?? doc.body;
  let controller = view?.getAttribute('data-controller') ?? null;
  if (controller) controller = SERVER_URL + '/web/ConfigurationPage?name=' + encodeURIComponent(controller.replace(/^__plugin\//, ''));
  const heading = el.querySelector('h1, h2, .sectionTitle');
  return { html: view ? view.outerHTML : el.innerHTML, controller, title: heading?.textContent?.trim() || null };
}

/* Fonts are per document: Seedy's @font-face rules again, with their files as absolute URLs. */
function fontFaces(): string {
  const out: string[] = [];
  for (const sheet of Array.from(document.styleSheets)) {
    let rules: CSSRuleList;
    try { rules = sheet.cssRules; } catch { continue; }
    const base = sheet.href || document.baseURI;
    for (const rule of Array.from(rules)) {
      if (!(rule instanceof CSSFontFaceRule)) continue;
      out.push(rule.cssText.replace(/url\((['"]?)([^'")]+)\1\)/g, (_, q: string, u: string) => {
        try { return `url(${q}${new URL(u, base).href}${q})`; } catch { return `url(${q}${u}${q})`; }
      }));
    }
  }
  return out.join('\n');
}

const escAttr = (s: string) => s.replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');

/**
 * The frame's whole document. `key` names the host the frame's first script installs itself with; the last script
 * starts the page once it has parsed: controller module (if any), then the page events plugins listen for.
 */
export function frameDocument(page: ParsedPage, key: string, host: FrameHost, name: string, params: Record<string, string>, hideTitle: string): string {
  const entry: Entry = { host, page: name, params };
  entry.install = install.bind(entry);
  entry.start = start.bind(entry);
  hosts[key] = entry;
  const accent = getComputedStyle(document.documentElement).getPropertyValue('--seedy-accent').trim();
  const k = JSON.stringify(key);
  return '<!doctype html><html lang="' + getLang() + '"' + (accent ? ' style="--seedy-accent:' + escAttr(accent) + '"' : '') + '><head><meta charset="utf-8">'
    + '<meta name="color-scheme" content="dark">'
    + '<base href="' + escAttr(SERVER_URL + '/web/') + '">'
    + '<style>' + fontFaces() + '</style><style>' + css + '</style>'
    + '<script>parent.__seedyPluginFrames[' + k + '].install(window,' + JSON.stringify(hideTitle) + ')</script>'
    + '</head><body><div class="seedy-plugin-root">' + page.html + '</div>'
    + '<script>(function(f){document.addEventListener("DOMContentLoaded",function(){var c=' + JSON.stringify(page.controller) + ';'
    + '(c?import(c):Promise.resolve(null)).then(function(m){f.start(window,m)},function(e){f.host.failed(e)})})})(parent.__seedyPluginFrames[' + k + '])</script>'
    + '</body></html>';
}

export function releaseFrame(key: string) {
  delete hosts[key];
}

/* ── Inside the frame ── */

const MATERIAL: Record<string, IconName> = {
  add: 'plus', add_circle: 'plus', add_circle_outline: 'plus', remove: 'minus', remove_circle: 'minus', remove_circle_outline: 'minus',
  delete: 'trash', delete_outline: 'trash', delete_forever: 'trash', clear: 'close', close: 'close', cancel: 'close',
  edit: 'pencil', create: 'pencil', mode_edit: 'pencil', check: 'check', done: 'check', check_circle: 'check',
  search: 'search', refresh: 'refresh', sync: 'refresh', autorenew: 'refresh', replay: 'refresh',
  info: 'info', info_outline: 'info', help: 'info', help_outline: 'info', warning: 'warn', error: 'alert', error_outline: 'alert', report_problem: 'warn',
  lock: 'lock', lock_outline: 'lock', folder: 'folder', folder_open: 'folder', settings: 'sliders', tune: 'sliders',
  arrow_back: 'chevronLeft', chevron_left: 'chevronLeft', navigate_before: 'chevronLeft', keyboard_arrow_left: 'chevronLeft',
  arrow_forward: 'chevronRight', chevron_right: 'chevronRight', navigate_next: 'chevronRight', keyboard_arrow_right: 'chevronRight',
  keyboard_arrow_down: 'chevronDown', expand_more: 'chevronDown', arrow_drop_down: 'chevronDown',
  more_vert: 'more', more_horiz: 'more', photo: 'image', image: 'image', insert_photo: 'image', videocam: 'film', movie: 'film', theaters: 'film',
  tv: 'tv', live_tv: 'tv', person: 'person', person_outline: 'person', people: 'people', group: 'people', supervisor_account: 'people',
  play_arrow: 'play', file_download: 'download', download: 'download', cloud_download: 'download', file_upload: 'upload', upload: 'upload', cloud_upload: 'upload',
  link: 'link', open_in_new: 'external', launch: 'external', visibility: 'eye', star: 'star', history: 'history', music_note: 'music', library_music: 'music',
  book: 'book', menu_book: 'book', schedule: 'clock', access_time: 'clock', home: 'home', list: 'list', view_list: 'list', filter_list: 'filters',
  sort: 'sort', drag_handle: 'drag', key: 'key', vpn_key: 'key', language: 'globe', public: 'globe', subtitles: 'subtitles', closed_caption: 'captions',
  queue_music: 'music', speed: 'speed', security: 'shield', shield: 'shield', notifications: 'inbox', inbox: 'inbox', content_copy: 'notes'
};

const iconSvg = new Map<IconName, string>();
function svgFor(name: IconName): string {
  let s = iconSvg.get(name);
  if (!s) {
    const box = document.createElement('div');
    render(h(Icon, { name, size: 20 }), box);
    s = box.innerHTML;
    iconSvg.set(name, s);
  }
  return s;
}

let fieldId = 0;

/* What jellyfin-web's emby-* elements add to the markup, done once per element. */
function enhance(root: Document | Element) {
  /* The frame's nodes come from its own realm: no instanceof against Seedy's constructors. */
  const doc = root.nodeType === 9 ? (root as Document) : (root as Element).ownerDocument;
  for (const el of Array.from(root.querySelectorAll<HTMLElement>('[is="emby-input"], [is="emby-textarea"], [is="emby-select"]'))) {
    if (el.dataset.seedy) continue;
    el.dataset.seedy = '1';
    if (!el.id) el.id = 'seedyfield' + ++fieldId;
    const kind = el.getAttribute('is') === 'emby-select' ? 'selectLabel' : el.getAttribute('is') === 'emby-textarea' ? 'textareaLabel' : 'inputLabel';
    const text = el.getAttribute('label');
    let label = el.previousElementSibling as HTMLLabelElement | null;
    if (!label || label.tagName !== 'LABEL' || label.htmlFor !== el.id) {
      label = doc.createElement('label');
      label.className = kind;
      label.htmlFor = el.id;
      if (text) label.textContent = text; else label.hidden = true;
      el.parentNode?.insertBefore(label, el);
    }
    const set = (v: string) => { label!.textContent = v; label!.hidden = !v; };
    Object.assign(el, { label: set, setLabel: set, labelElement: label });
  }
  for (const el of Array.from(root.querySelectorAll<HTMLElement>('[is="emby-collapse"]'))) {
    if (el.dataset.seedy) continue;
    el.dataset.seedy = '1';
    el.classList.add('emby-collapse');
    const title = el.getAttribute('title') ?? '';
    el.removeAttribute('title');
    const button = doc.createElement('button');
    button.type = 'button';
    button.className = 'emby-collapsible-button';
    const h3 = doc.createElement('h3');
    h3.className = 'emby-collapsible-title';
    h3.textContent = title;
    const icon = doc.createElement('span');
    icon.className = 'material-icons emby-collapse-expandIcon expand_more';
    button.append(h3, icon);
    el.insertBefore(button, el.firstChild);
    const content = el.querySelector<HTMLElement>(':scope > .collapseContent');
    const open = el.getAttribute('data-expanded') === 'true';
    button.setAttribute('aria-expanded', String(open));
    if (content && !open) content.classList.add('hide');
    button.addEventListener('click', () => {
      const next = button.getAttribute('aria-expanded') !== 'true';
      button.setAttribute('aria-expanded', String(next));
      content?.classList.toggle('hide', !next);
    });
  }
  for (const el of Array.from(root.querySelectorAll<HTMLElement>('.material-icons:not([data-seedy-icon])'))) {
    const name = Array.from(el.classList).find((c) => MATERIAL[c]) ?? el.textContent?.trim() ?? '';
    const icon = MATERIAL[name];
    if (!icon) continue;
    el.dataset.seedyIcon = icon;
    el.innerHTML = svgFor(icon);
  }
  if (root.nodeType === 1 && (root as Element).matches('[is^="emby-"], .material-icons') && (root as Element).parentElement) enhance((root as Element).parentElement!);
}

/* jellyfin-apiclient's paramsToString: skips empty values. */
function params(q: Query): string {
  if (!q) return '';
  const out: string[] = [];
  for (const [k, v] of Object.entries(q)) {
    if (v === undefined || v === null || v === '') continue;
    out.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(v)));
  }
  return out.join('&');
}

const sameOrigin = (u: URL) => u.origin === new URL(SERVER_URL).origin;

function apiClient(win: Win) {
  const server = SERVER_URL;
  const api = {
    _serverAddress: server,
    serverAddress: () => server,
    serverId: () => getSession()?.serverId ?? '',
    serverInfo: () => ({ Id: getSession()?.serverId ?? '', AccessToken: sessionToken(), UserId: currentUserId(), ManualAddress: server }),
    accessToken: () => sessionToken(),
    deviceId: () => deviceId(),
    deviceName: () => deviceName(),
    appName: () => CLIENT_NAME,
    appVersion: () => APP_VERSION,
    isLoggedIn: () => !!sessionToken(),
    getCurrentUserId: () => currentUserId(),
    getDefaultImageQuality: () => 90,
    getUrl(name: string, query?: Query) {
      if (!name) throw new Error('Url name cannot be empty');
      const q = params(query);
      return server + (name.startsWith('/') ? '' : '/') + name + (q ? '?' + q : '');
    },
    /* As jellyfin-apiclient: JSON when asked for, text for text responses, the Response otherwise; failures reject
       with the Response (plugins read .status and .text()). The token only goes to this server. */
    async fetch(req: Req, auth = true) {
      const target = new URL(req.url, win.document.baseURI);
      const headers: Record<string, string> = { ...(req.headers ?? {}) };
      if (auth !== false && sameOrigin(target)) headers.Authorization = authHeader();
      if (req.dataType === 'json') headers.accept = 'application/json';
      let body: BodyInit | undefined;
      let type = req.contentType;
      if (req.data !== undefined && req.data !== null && req.data !== '') {
        if (typeof req.data === 'string' || req.data instanceof win.FormData || req.data instanceof win.Blob || req.data instanceof FormData || req.data instanceof Blob) body = req.data as BodyInit;
        else {
          body = params(req.data as Query);
          type = type || 'application/x-www-form-urlencoded; charset=UTF-8';
        }
      }
      if (type) headers['Content-Type'] = type;
      const res = await fetch(target.href, { method: (req.type || req.method || 'GET').toUpperCase(), headers, body, credentials: 'same-origin' });
      if (res.status >= 400) throw res;
      const accept = headers.accept ?? headers.Accept;
      if (req.dataType === 'json' || accept === 'application/json') {
        const text = await res.text();
        if (!text) return null;
        try { return JSON.parse(text); } catch { return text; }
      }
      if (req.dataType === 'text' || (res.headers.get('Content-Type') ?? '').toLowerCase().startsWith('text/')) return res.text();
      return res;
    },
    ajax(req: Req, auth?: boolean) { return this.fetch(req, auth); },
    getJSON(url: string, auth?: boolean) { return this.fetch({ url, type: 'GET', dataType: 'json', headers: { accept: 'application/json' } }, auth); },
    get(url: string) { return this.ajax({ type: 'GET', url }); },
    post(url: string, data?: unknown) { return this.send('POST', url, data); },
    send(type: string, url: string, data?: unknown) {
      return this.ajax({ type, url, data: data === undefined ? undefined : JSON.stringify(data), contentType: data === undefined ? undefined : 'application/json' });
    },
    getPluginConfiguration(id: string) { return this.getJSON(this.getUrl(`Plugins/${id}/Configuration`)); },
    updatePluginConfiguration(id: string, config: unknown) { return this.send('POST', this.getUrl(`Plugins/${id}/Configuration`), config); },
    getServerConfiguration() { return this.getJSON(this.getUrl('System/Configuration')); },
    updateServerConfiguration(config: unknown) { return this.send('POST', this.getUrl('System/Configuration'), config); },
    getNamedConfiguration(name: string) { return this.getJSON(this.getUrl(`System/Configuration/${name}`)); },
    updateNamedConfiguration(name: string, config: unknown) { return this.send('POST', this.getUrl(`System/Configuration/${name}`), config); },
    getSystemInfo() { return this.getJSON(this.getUrl('System/Info')); },
    getPublicSystemInfo() { return this.getJSON(this.getUrl('System/Info/Public'), false); },
    getEndpointInfo() { return this.getJSON(this.getUrl('System/Endpoint')); },
    getInstalledPlugins() { return this.getJSON(this.getUrl('Plugins')); },
    getPackages() { return this.getJSON(this.getUrl('Packages')); },
    getUsers(q?: Query) { return this.getJSON(this.getUrl('Users', q)); },
    getUser(id: string) { return this.getJSON(this.getUrl(`Users/${id}`)); },
    getCurrentUser() { return this.getUser(currentUserId()); },
    getUserViews(q?: Query, userId?: string) { return this.getJSON(this.getUrl('UserViews', { ...q, userId: userId || currentUserId() })); },
    getVirtualFolders() { return this.getJSON(this.getUrl('Library/VirtualFolders')); },
    getItems(userId: string | null, q?: Query) { return this.getJSON(this.getUrl('Items', { ...q, userId: userId || undefined })); },
    getItem(userId: string | null, id: string) { return this.getJSON(this.getUrl(`Items/${id}`, { userId: userId || undefined })); },
    getSessions(q?: Query) { return this.getJSON(this.getUrl('Sessions', q)); },
    getScheduledTasks(q?: Query) { return this.getJSON(this.getUrl('ScheduledTasks', q)); },
    getScheduledTask(id: string) { return this.getJSON(this.getUrl(`ScheduledTasks/${id}`)); },
    startScheduledTask(id: string) { return this.send('POST', this.getUrl(`ScheduledTasks/Running/${id}`)); },
    stopScheduledTask(id: string) { return this.send('DELETE', this.getUrl(`ScheduledTasks/Running/${id}`)); },
    getParentalRatings() { return this.getJSON(this.getUrl('Localization/ParentalRatings')); },
    getCultures() { return this.getJSON(this.getUrl('Localization/Cultures')); },
    getCountries() { return this.getJSON(this.getUrl('Localization/Countries')); },
    getDrives() { return this.getJSON(this.getUrl('Environment/Drives')); },
    getDirectoryContents(path: string, q?: Query) { return this.getJSON(this.getUrl('Environment/DirectoryContents', { includeDirectories: true, ...q, path })); },
    getParentPath(path: string) { return this.ajax({ type: 'GET', url: this.getUrl('Environment/ParentPath', { path }), dataType: 'text' }); },
    sendMessageCommand(sessionId: string, options: unknown) { return this.send('POST', this.getUrl(`Sessions/${sessionId}/Message`), options); },
    getImageUrl(itemId: string, o: Record<string, unknown> = {}) {
      const { type = 'Primary', index, ...rest } = o;
      return this.getUrl(`Items/${itemId}/Images/${type}` + (index !== undefined ? '/' + index : ''), rest);
    },
    getScaledImageUrl(itemId: string, o: Record<string, unknown> = {}) { return this.getImageUrl(itemId, o); },
    getUserImageUrl(userId: string, o: Record<string, unknown> = {}) {
      const { type = 'Primary', index, ...rest } = o;
      return this.getUrl(`Users/${userId}/Images/${type}` + (index !== undefined ? '/' + index : ''), rest);
    }
  };
  return api;
}

/* Fixed bars, toasts and dialogs place themselves in the frame's viewport, which here is the whole (tall) page. The
   Seedy page keeps --seedy-vt / --seedy-vh at the part of the frame that's on screen, and every `position: fixed` (or
   sticky: the frame never scrolls) rule in the page's stylesheets gets a twin that measures from there instead. */
const VT = 'var(--seedy-vt, 0px)';
const VH = 'var(--seedy-vh, 100%)';

function pinDeclarations(style: CSSStyleDeclaration): string {
  const out: string[] = [];
  const pct = (v: string) => { const m = /^(-?[\d.]+)%$/.exec(v); return m ? Number(m[1]) / 100 : null; };
  for (const [prop, top] of [['top', true], ['inset-block-start', true], ['bottom', false], ['inset-block-end', false]] as const) {
    const v = style.getPropertyValue(prop).trim();
    if (!v || v === 'auto') continue;
    const k = pct(v);
    const offset = k !== null ? `${VH} * ${k}` : v;
    out.push(`${prop}:${top ? `calc(${VT} + ${offset})` : `calc(100% - ${VT} - ${VH} + ${offset})`}${style.getPropertyPriority(prop) ? ' !important' : ''}`);
  }
  for (const prop of ['height', 'min-height', 'max-height']) {
    if (/^100(vh|dvh|svh|lvh|%)$/.test(style.getPropertyValue(prop).trim())) out.push(`${prop}:${VH}${style.getPropertyPriority(prop) ? ' !important' : ''}`);
  }
  return out.join(';');
}

function pinRules(win: Win, rules: CSSRuleList, out: string[], wrap: (css: string) => string) {
  const W = win as unknown as Record<string, (new () => CSSRule) | undefined>;
  const is = (r: CSSRule, name: string) => !!W[name] && r instanceof W[name]!;
  for (const r of Array.from(rules)) {
    if (is(r, 'CSSStyleRule')) {
      const sr = r as CSSStyleRule;
      if (sr.style.position === 'fixed' || sr.style.position === 'sticky') {
        const decl = pinDeclarations(sr.style);
        if (decl) out.push(wrap(`${sr.selectorText}{${decl}}`));
      }
    } else if (is(r, 'CSSMediaRule') || is(r, 'CSSSupportsRule') || is(r, 'CSSContainerRule')) {
      const cr = r as CSSConditionRule;
      const at = is(r, 'CSSMediaRule') ? '@media' : is(r, 'CSSSupportsRule') ? '@supports' : '@container';
      pinRules(win, cr.cssRules, out, (css) => wrap(`${at} ${cr.conditionText}{${css}}`));
    } else if ('cssRules' in r) {
      /* @layer blocks: the twin, unlayered, wins over them anyway. */
      pinRules(win, (r as CSSGroupingRule).cssRules, out, wrap);
    }
  }
}

/* Called by the frame's first script, before any of the page's own. */
function install(this: Entry, win: Win, hideTitle: string) {
  const { host } = this;
  const doc = win.document;
  const api = apiClient(win);
  const pluginUrl = (name: string) => 'configurationpage?name=' + encodeURIComponent(name);
  const follow = (url: string) => {
    const page = pageFromUrl(url);
    if (page) host.openPage(page);
    else host.openUrl(new URL(url, doc.baseURI).href);
  };

  win.ApiClient = api;
  win.Dashboard = {
    showLoadingMsg: () => host.loading(true),
    hideLoadingMsg: () => host.loading(false),
    alert(o: string | { title?: string; message?: string; text?: string; callback?: () => void }) {
      const opts = typeof o === 'string' ? { message: o } : o ?? {};
      host.loading(false);
      return host.alert(opts.title ?? '', opts.message ?? opts.text ?? '').then(() => opts.callback?.());
    },
    confirm(message: string, title: string, callback?: (ok: boolean) => void) {
      return host.confirm(title ?? '', message ?? '').then((ok) => { callback?.(ok); if (!callback && !ok) throw new Error('cancelled'); return ok; });
    },
    processPluginConfigurationUpdateResult() { host.loading(false); host.toast(t('Settings saved')); },
    processServerConfigurationUpdateResult() { host.loading(false); host.toast(t('Settings saved')); },
    processErrorResponse(res?: { status?: number; statusText?: string }) {
      host.loading(false);
      host.toast(t('The server refused the change. {error}', { error: res?.statusText || (res?.status ? 'HTTP ' + res.status : '') }).trim(), 'error');
    },
    navigate(url: string) { follow(url); return Promise.resolve(); },
    getPluginUrl: pluginUrl,
    getConfigurationPageUrl: pluginUrl,
    getCurrentUserId: () => currentUserId(),
    getCurrentUser: () => api.getCurrentUser(),
    serverAddress: () => SERVER_URL,
    capabilities: () => ({}),
    selectServer() { /* one server here */ },
    logout() { /* Seedy's account menu signs out */ },
    DirectoryBrowser: class {
      show(o: { callback?: (path: string) => void; header?: string; includeFiles?: boolean; path?: string } = {}) {
        void host.pickPath({ title: o.header, files: !!o.includeFiles, path: o.path }).then((p) => { if (p !== null) o.callback?.(p); });
      }
      close() { host.closePicker(); }
    }
  };
  win.Loading = { show: () => host.loading(true), hide: () => host.loading(false) };
  win.LibraryMenu = {
    setTabs(_name: string, index: number, getTabs: () => { href: string; name: string }[]) {
      const list = (typeof getTabs === 'function' ? getTabs() : []) ?? [];
      host.tabs(list.map((x) => ({ name: x.name, href: x.href, page: pageFromUrl(x.href) })), index);
    },
    setTitle() { /* the plugin's name is the title */ },
    setTransparentMenu() { /* no app bar here */ }
  };
  win.Emby = {
    Page: {
      getRouteUrl: (item: { Id?: string }) => (item?.Id ? APP_BASE + '/details/' + item.Id : APP_BASE + '/'),
      show: (url: string) => { follow(url); return Promise.resolve(); },
      back: () => win.parent.history.back()
    }
  };
  win.globalize = { translate: (key: string) => translateKey(key), getCurrentLocale: () => getLang() };

  /* emby-* upgrades for the page's markup and anything its scripts add later. */
  new win.MutationObserver((records) => {
    let styles = false;
    for (const r of records) for (const n of Array.from(r.addedNodes)) {
      if (n.nodeType !== 1) continue;
      if (n.nodeName === 'STYLE' && n !== twins) styles = true;
      try { enhance(n as Element); } catch (e) { console.warn('[plugin page] enhance', e); }
    }
    if (styles) pin();
    hideDupTitle();
  }).observe(doc, { childList: true, subtree: true });

  /* The plugin's name heading ("TMDb", "DLNA Settings:") repeats Seedy's title above the frame: hide it, and move
     the links beside it (its Help) next to Seedy's. Pages that draw themselves after loading get five seconds. */
  const norm = (v: string) => v.toLowerCase().replace(/[:\s]*$/, '').replace(/\s+(settings|configuration|config|options)$/, '').replace(/[^\p{L}\p{N}]+/gu, '');
  let matched = false;
  const until = Date.now() + 5000;
  const hideDupTitle = () => {
    if (!hideTitle || (!matched && Date.now() > until)) return;
    const first = doc.querySelector('.seedy-plugin-root :is(h1, h2, h3, .sectionTitle)');
    if (!first || first.closest('.seedy-dup-title') || norm(first.textContent ?? '') !== norm(hideTitle)) return;
    /* Pages that redraw replace the heading: once it matched, each new one is hidden too. */
    matched = true;
    const box = first.parentElement?.classList.contains('sectionTitleContainer') ? first.parentElement : null;
    const others = box ? Array.from(box.children).filter((c) => c !== first) : [];
    const links = others.filter((c): c is HTMLAnchorElement => c.tagName === 'A' && /^https?:/i.test((c as HTMLAnchorElement).href));
    if (box && links.length === others.length) {
      box.classList.add('seedy-dup-title');
      if (links.length) host.links(links.map((a) => ({ text: a.textContent?.trim() || t('Help'), href: a.href })));
    } else first.classList.add('seedy-dup-title');
  };
  doc.addEventListener('DOMContentLoaded', hideDupTitle, { once: true });

  /* The page lives in this frame: links go through Seedy, and nothing navigates the frame away. */
  doc.addEventListener('click', (e) => {
    if (e.defaultPrevented || e.button !== 0) return;
    const a = (e.target as Element | null)?.closest?.('a[href]') as HTMLAnchorElement | null;
    if (!a) return;
    const raw = a.getAttribute('href') ?? '';
    if (/^javascript:/i.test(raw)) return;
    e.preventDefault();
    if (raw.startsWith('#') && !/^#!?\//.test(raw)) {
      if (raw.length > 1) doc.getElementById(decodeURIComponent(raw.slice(1)))?.scrollIntoView({ behavior: 'smooth', block: 'start' });
      return;
    }
    follow(raw);
  });
  doc.addEventListener('submit', (e) => e.preventDefault());

  const pinned = new WeakSet<CSSStyleSheet>();
  const twins = doc.createElement('style');
  const pin = () => {
    const out: string[] = [];
    for (const sheet of Array.from(doc.styleSheets)) {
      if (pinned.has(sheet) || sheet.ownerNode === twins) continue;
      let rules: CSSRuleList;
      try { rules = sheet.cssRules; } catch { continue; }
      pinned.add(sheet);
      pinRules(win, rules, out, (css) => css);
    }
    if (!out.length) return;
    twins.textContent += out.join('\n');
    (doc.body ?? doc.head).appendChild(twins);
  };
  doc.addEventListener('DOMContentLoaded', pin, { once: true });
  win.addEventListener('load', pin, { once: true });
  doc.addEventListener('load', (e) => { if ((e.target as Element)?.nodeName === 'LINK') pin(); }, true);

  /* The frame is as tall as its page: Seedy's page scrolls, not the frame. Something sized to the viewport (100vh)
     grows with the frame; after a run of back-to-back growths the height stays put. */
  let last = 0;
  let run = 0;
  let at = 0;
  const measure = () => {
    const root = doc.querySelector('.seedy-plugin-root');
    const px = Math.ceil(root ? root.getBoundingClientRect().height : doc.documentElement.scrollHeight);
    if (Math.abs(px - last) <= 1) return;
    const now = Date.now();
    run = px > last && now - at < 200 ? run + 1 : 0;
    at = now;
    if (run > 40) return;
    last = px;
    host.height(px);
  };
  doc.addEventListener('DOMContentLoaded', () => {
    const root = doc.querySelector('.seedy-plugin-root');
    if (root) new win.ResizeObserver(measure).observe(root);
    measure();
  }, { once: true });
  win.addEventListener('error', (e) => console.warn('[plugin page]', e.message));
}

/* Called once the page has parsed and its controller (if any) has loaded: what jellyfin-web's viewManager does. */
function start(this: Entry, win: Win, mod: { default?: unknown } | null) {
  const { host, params: query } = this;
  const doc = win.document;
  try {
    enhance(doc);
    const view = doc.querySelector<HTMLElement>('.seedy-plugin-root > [data-role="page"]') ?? doc.querySelector<HTMLElement>('.seedy-plugin-root')!;
    const detail = { type: view.getAttribute('data-type'), params: { ...query, name: this.page }, isRestored: false, state: null, options: {}, properties: [] };
    const Factory = mod?.default;
    if (typeof Factory === 'function') new (Factory as new (v: HTMLElement, p: Record<string, string>) => unknown)(view, detail.params);
    const fire = (name: string) => view.dispatchEvent(new win.CustomEvent(name, { detail, bubbles: true, cancelable: false }));
    for (const ev of ['init', 'beforeshow', 'show']) { fire('view' + ev); fire('page' + ev); }
    host.ready();
  } catch (e) {
    host.failed(e);
  }
}
