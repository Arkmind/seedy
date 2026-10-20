import { SERVER_URL, deviceId, get, getSession } from '../api/client';
import { reportCapabilities } from '../api/jellyfin';
import { bootState } from './app-state';
import { navigate } from './router';

/* One websocket per signed-in tab. It keeps the session alive, lets other Jellyfin apps cast to
   Seedy, and delivers SyncPlay and user-data updates. */

type Handler = (data: any) => void;
const handlers = new Map<string, Set<Handler>>();

export function on(type: string, fn: Handler): () => void {
  let set = handlers.get(type);
  if (!set) handlers.set(type, (set = new Set()));
  set.add(fn);
  return () => set!.delete(fn);
}

function emit(type: string, data: unknown) {
  handlers.get(type)?.forEach((fn) => {
    try { fn(data); } catch (e) { console.error('[seedy] handler failed for', type, e); }
  });
}

let socket: WebSocket | null = null;
let keepAlive: number | undefined;
let retryTimer: number | undefined;
let retry = 0;
let started = false;

export function send(MessageType: string, Data?: unknown) {
  if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ MessageType, Data }));
}

const SUPPORTED_COMMANDS = [
  'Play', 'Playstate', 'PlayNext', 'PlayMediaSource', 'SetAudioStreamIndex', 'SetSubtitleStreamIndex', 'SetVolume', 'Mute', 'Unmute',
  'ToggleMute', 'VolumeUp', 'VolumeDown', 'ToggleFullscreen', 'DisplayMessage', 'GoHome', 'GoToSearch', 'SetMaxStreamingBitrate', 'SetPlaybackOrder'
];

function connect() {
  const s = getSession();
  if (!s || bootState.get() !== 'ready') return;
  // One socket per tab: a quick sign-out and sign-in must not leave two (remote Play would navigate twice).
  window.clearTimeout(retryTimer);
  if (socket) {
    const old = socket;
    socket = null;
    old.onclose = null;
    old.close();
  }
  const wsUrl = SERVER_URL.replace(/^http/, 'ws') + `/socket?ApiKey=${encodeURIComponent(s.token)}&deviceId=${encodeURIComponent(deviceId())}`;
  const ws = new WebSocket(wsUrl);
  socket = ws;
  ws.onopen = () => {
    retry = 0;
    reportCapabilities({
      PlayableMediaTypes: ['Video', 'Audio'],
      SupportedCommands: SUPPORTED_COMMANDS,
      SupportsMediaControl: true,
      SupportsPersistentIdentifier: false
    }).catch(() => undefined);
    emit('open', null);
  };
  ws.onmessage = (ev) => {
    let msg: { MessageType: string; Data?: any };
    try { msg = JSON.parse(ev.data); } catch { return; }
    if (msg.MessageType === 'ForceKeepAlive') {
      window.clearInterval(keepAlive);
      const every = Math.max(5, Number(msg.Data) || 60) * 500;
      keepAlive = window.setInterval(() => send('KeepAlive'), every);
      send('KeepAlive');
      return;
    }
    if (msg.MessageType === 'KeepAlive') return;
    if (msg.MessageType === 'Play') {
      const d = msg.Data as { ItemIds?: string[]; StartPositionTicks?: number; PlayCommand?: string };
      const id = d.ItemIds?.[0];
      if (id) {
        const t = d.StartPositionTicks ? `?t=${d.StartPositionTicks}` : '';
        const queue = d.ItemIds && d.ItemIds.length > 1 ? `${t ? '&' : '?'}queue=${d.ItemIds.slice(1).join(',')}` : '';
        navigate(`/play/${id}${t}${queue}`);
      }
      return;
    }
    if (msg.MessageType === 'GeneralCommand' && msg.Data?.Name === 'GoHome') { navigate('/'); return; }
    if (msg.MessageType === 'GeneralCommand' && msg.Data?.Name === 'GoToSearch') { navigate('/search'); return; }
    emit(msg.MessageType, msg.Data);
  };
  let opened = false;
  ws.addEventListener('open', () => { opened = true; });
  ws.onclose = () => {
    window.clearInterval(keepAlive);
    if (socket === ws) socket = null;
    emit('close', null);
    if (bootState.get() !== 'ready') return;
    /* A socket that never opened may mean the sign-in was revoked (signed out elsewhere, device removed). An API call
       tells: on 401 the app signs out, and the retries stop instead of knocking on the server forever. */
    if (!opened) get('/Users/Me').catch(() => undefined);
    retry = Math.min(retry + 1, 6);
    retryTimer = window.setTimeout(connect, 1000 * 2 ** retry);
  };
}

export function startRemoteControl() {
  if (started) return;
  started = true;
  bootState.subscribe((s) => {
    if (s === 'ready' && !socket) connect();
    if (s !== 'ready') {
      window.clearTimeout(retryTimer);
      if (socket) { const old = socket; socket = null; old.onclose = null; old.close(); }
    }
  });
  connect();
}
