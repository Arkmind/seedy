import type { ComponentChildren } from 'preact';
import { useEffect, useRef, useState } from 'preact/hooks';
import { t, tn } from '../../lib/i18n';
import { Link, navigate } from '../../lib/router';
import { Header } from '../../ui/navbar/Header';
import { toast } from '../../ui/overlay';
import { loadTuners, recordingNow, useChannels, useTimers } from './data';
import { LtIcon, type LtIconName } from './parts';
import './livetv.css';

/* The Live TV page shell (all four Live TV boards): "Live TV" with the channel count, the "Channel number" box
   with Go, and the tab tray — On now, Guide, Channels, Recordings with the "N now" recording badge. */

export type LiveTab = 'now' | 'guide' | 'channels' | 'recordings';

const TABS: { id: LiveTab; path: string; label: string; icon: LtIconName }[] = [
  { id: 'now', path: '/livetv', label: 'On now', icon: 'onNow' },
  { id: 'guide', path: '/livetv/guide', label: 'Guide', icon: 'guide' },
  { id: 'channels', path: '/livetv/channels', label: 'Channels', icon: 'channels' },
  { id: 'recordings', path: '/livetv/recordings', label: 'Recordings', icon: 'recordings' }
];

export function LiveLayout({ tab, children, wide }: { tab: LiveTab; children: ComponentChildren; wide?: boolean }) {
  const channels = useChannels();
  const timers = useTimers();
  const recNow = recordingNow(timers.timers).length;
  const total = channels.list?.length;

  useEffect(() => { loadTuners(); }, []);
  useEffect(() => {
    const name = TABS.find((x) => x.id === tab)!.label;
    document.title = `${t(name)} · ${t('Live TV')}`;
  }, [tab]);

  return (
    <div class={'page lt-page' + (wide ? ' is-wide' : '')}>
      <Header active={{ collectionType: 'livetv' }} />
      <main class="lt-main">
        <div class="lt-head">
          <div class="page-title">
            <h1>{t('Live TV')}</h1>
            {total !== undefined
              ? <span class="page-count">{tn(total, '{n} channel', '{n} channels')}</span>
              : <span class="skel skel-line" style={{ width: '96px' }} aria-hidden="true" />}
          </div>
          <ChannelNumberBox />
        </div>
        <nav aria-label={t('Live TV views')} class="lt-tabs hscroll">
          {TABS.map((x) => (
            <Link key={x.id} to={x.path} class="lt-tab" aria-current={x.id === tab ? 'page' : undefined}>
              <LtIcon name={x.icon} size={18} />
              {t(x.label)}
              {x.id === 'recordings' && recNow > 0 && (
                <span class="lt-now-badge"><span class="lt-now-dot" />{tn(recNow, '{n} now', '{n} now')}</span>
              )}
            </Link>
          ))}
        </nav>
        {channels.error && !channels.list ? <LiveError onRetry={channels.reload} /> : children}
      </main>
    </div>
  );
}

/* "Channel number" + Go: plays that channel. Digits typed anywhere on a Live TV page land here. */
function ChannelNumberBox() {
  const { list } = useChannels();
  const [value, setValue] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const go = () => {
    const n = value.trim();
    if (!n) { input.current?.focus(); return; }
    const ch = list?.find((c) => c.num === n) ?? list?.find((c) => parseFloat(c.num) === parseFloat(n));
    if (!ch) { toast(t('There is no channel {n}', { n }), 'error'); return; }
    navigate(`/play/${ch.Id}`);
  };
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey || e.defaultPrevented || !/^[0-9]$/.test(e.key)) return;
      const el = e.target as HTMLElement | null;
      if (el && (/^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName) || el.isContentEditable)) return;
      if (document.querySelector('.popover, .scrim')) return;
      e.preventDefault();
      setValue((v) => (document.activeElement === input.current ? v : '') + e.key);
      input.current?.focus();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  return (
    <form class="lt-numbox" onSubmit={(e) => { e.preventDefault(); go(); }}>
      <LtIcon name="hash" size={18} />
      <input
        ref={input}
        type="text"
        inputMode="numeric"
        autoComplete="off"
        aria-label={t('Go to channel number')}
        placeholder={t('Channel number')}
        value={value}
        onInput={(e) => setValue((e.currentTarget as HTMLInputElement).value.replace(/[^0-9.]/g, ''))}
      />
      <button type="submit" class="lt-numbox-go">{t('Go')}</button>
    </form>
  );
}

export function LiveError({ onRetry, text }: { onRetry: () => void; text?: string }) {
  return (
    <div class="lt-pad">
      <div class="empty" role="alert">
        <span class="empty-icon"><LtIcon name="warn" size={26} /></span>
        <div class="empty-body">
          <span class="empty-title">{t("Couldn't load Live TV")}</span>
          <span class="empty-text">{text ?? t('The server did not answer. Check that Live TV is set up, then try again.')}</span>
        </div>
        <button type="button" class="btn btn-secondary" onClick={onRetry}>{t('Try again')}</button>
      </div>
    </div>
  );
}

/* No tuner yet: say so, and send admins to the server's Live TV settings. */
export function NoChannels({ admin }: { admin: boolean }) {
  return (
    <div class="lt-pad">
      <div class="empty">
        <span class="empty-icon"><LtIcon name="channels" size={26} /></span>
        <div class="empty-body">
          <span class="empty-title">{t('No channels yet')}</span>
          <span class="empty-text">
            {admin ? t('Add a tuner (an M3U playlist or HDHomeRun) and a guide under Admin · Live TV.') : t('Live TV appears here once an admin adds a tuner.')}
          </span>
        </div>
        {admin && <Link to="/admin/livetv" class="btn btn-secondary">{t('Set up Live TV')}</Link>}
      </div>
    </div>
  );
}
