import { useEffect, useRef, useState } from 'preact/hooks';
import { myDevices, signOutDevice, signOutOtherDevices, type MyDevice } from '../../api/seedy';
import { clock, daysFromToday, episodeTag, fmtDate, fmtDayMonth } from '../../lib/format';
import { t, tn } from '../../lib/i18n';
import { navigate } from '../../lib/router';
import { useAsync } from '../../lib/store';
import { Icon, type IconName } from '../../ui/icons';
import { confirm, toast } from '../../ui/overlay';
import { MeLayout } from './MeLayout';
import './devices.css';

/* Settings · Devices (MeDevices board): everywhere this profile is signed in, and signing those devices out. */

type Kind = 'tv' | 'phone' | 'tablet' | 'computer';

const ICON: Record<Kind, IconName> = { tv: 'deviceTv', phone: 'devicePhone', tablet: 'deviceTablet', computer: 'deviceLaptop' };
const BROWSER = /\b(Chrome|Chromium|Firefox|Safari|Edge|Opera|Vivaldi|Brave)\b/i;
/* Seedy's web app reports itself as plain "Seedy"; the Android apps say "Seedy for Android (TV)". */
const WEB_APP = 'Seedy';

/* The boards draw four kinds of device; guess which from its names and app. */
export function deviceKind(d: Pick<MyDevice, 'name' | 'reportedName' | 'appName'>): Kind {
  const s = `${d.name} ${d.reportedName} ${d.appName}`.toLowerCase();
  if (/ipad|tablet|galaxy tab/.test(s)) return 'tablet';
  if (/\btv\b|android ?tv|fire ?tv|roku|shield|chromecast|apple ?tv|tvos|tizen|webos|kodi|xbox|playstation|living room|bedroom/.test(s)) return 'tv';
  if (/iphone|ipod|android|mobile|phone|pixel|galaxy|swiftfin|findroid|\bios\b/.test(s)) return 'phone';
  return 'computer';
}

/* The server's timestamps are moments, never bare days. */
function moment(s?: string | null): Date | undefined {
  const d = s ? new Date(s) : undefined;
  return d && !Number.isNaN(d.getTime()) && d.getFullYear() > 2000 ? d : undefined;
}

const majorMinor = (v: string) => v.split('.').slice(0, 2).join('.');

/* "Chrome · Seedy web", "Seedy for Android TV 1.4": the browser only when the name doesn't already say it. */
function appParts(d: MyDevice): string[] {
  const web = d.appName === WEB_APP;
  const app = web ? t('Seedy web') : [d.appName, majorMinor(d.appVersion)].filter(Boolean).join(' ');
  const browser = BROWSER.exec(d.reportedName)?.[1];
  const said = `${d.name} ${d.appName}`.toLowerCase();
  return [browser && !said.includes(browser.toLowerCase()) ? browser : '', app].filter(Boolean);
}

const networkLabel = (d: MyDevice) => (d.network === 'home' ? t('Home network') : d.network === 'away' ? t('Away') : '');

/* Worth a second look: new, and from outside the home network. */
const flagged = (d: MyDevice) => d.isNew && d.network === 'away';

/* "Active now", "12 min ago", "2 h ago", "Yesterday, 23:12", "3 days ago", "4 Jul". */
function lastUsed(d: MyDevice): string {
  if (d.active) return t('Active now');
  const at = moment(d.lastActiveAt);
  if (!at) return t('Not used yet');
  const mins = Math.round((Date.now() - at.getTime()) / 60_000);
  const days = -daysFromToday(at);
  if (mins < 60) return t('{n} min ago', { n: Math.max(1, mins) });
  if (days === 0) return t('{n} h ago', { n: Math.round(mins / 60) });
  if (days === 1) return t('Yesterday, {time}', { time: clock(at) });
  if (days < 30) return t('{n} days ago', { n: days });
  return shortDay(at);
}

const shortDay = (d: Date) => (d.getFullYear() === new Date().getFullYear() ? fmtDayMonth(d) : fmtDate(d));

/* "Signed in 12 Sep", or for a new device "First seen yesterday". */
function since(d: MyDevice): string {
  const first = moment(d.firstSeenAt);
  if (d.isNew && first) {
    const days = -daysFromToday(first);
    return days <= 0 ? t('First seen today') : days === 1 ? t('First seen yesterday') : t('First seen {n} days ago', { n: days });
  }
  const at = moment(d.signedInAt);
  return at ? t('Signed in {date}', { date: shortDay(at) }) : '';
}

/* "yesterday at 23:12", for the warning under a new device. */
function firstSeenAt(d: MyDevice): string {
  const at = moment(d.firstSeenAt);
  if (!at) return '';
  const days = -daysFromToday(at);
  if (days <= 0) return t('today at {time}', { time: clock(at) });
  if (days === 1) return t('yesterday at {time}', { time: clock(at) });
  return t('on {date} at {time}', { date: fmtDayMonth(at), time: clock(at) });
}

function playingLine(d: MyDevice): string {
  const p = d.playing;
  if (!p) return '';
  const tag = p.episode != null ? episodeTag({ ParentIndexNumber: p.season ?? undefined, IndexNumber: p.episode }) : '';
  const what = tag ? `${p.title} · ${tag}` : p.title;
  return p.paused ? t('Paused {title}', { title: what }) : t('Playing {title}', { title: what });
}

/* "the TV", for "including the TV playing Northbound". */
const theKind = (k: Kind) => (k === 'tv' ? t('the TV') : k === 'phone' ? t('the phone') : k === 'tablet' ? t('the tablet') : t('the computer'));

function everywhereText(others: MyDevice[]): string {
  const n = others.length;
  const playing = others.filter((d) => d.playing && !d.playing.paused);
  if (playing.length === 0) return tn(n, 'Signs out your other device. You stay signed in here.', 'Signs out all {n} other devices. You stay signed in here.');
  if (playing.length === 1) {
    const p = playing[0];
    return tn(n, 'Signs out your other device, {device} playing {title}. You stay signed in here.', 'Signs out all {n} other devices, including {device} playing {title}. You stay signed in here.', { device: theKind(deviceKind(p)), title: p.playing!.title });
  }
  return t('Signs out all {n} other devices, including the {playing} playing right now. You stay signed in here.', { n, playing: playing.length });
}

export function MeDevices() {
  const list = useAsync(() => myDevices().then((r) => r.devices), []);
  const [open, setOpen] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  /* What's playing and who's active change while the page is open. */
  useEffect(() => {
    const timer = window.setInterval(() => { if (document.visibilityState === 'visible') list.reload(); }, 30_000);
    return () => window.clearInterval(timer);
  }, []);

  const devices = list.data ?? [];
  const here = devices.filter((d) => d.current);
  const others = devices.filter((d) => !d.current);
  const playing = others.filter((d) => d.playing && !d.playing.paused).length;

  const signOut = async (d: MyDevice, thenPassword: boolean) => {
    setBusy(true);
    try {
      await signOutDevice(d.id);
      setOpen(null);
      toast(t('{name} signed out', { name: d.name }));
      if (thenPassword) navigate('/settings?focus=password');
      else list.reload();
    } catch {
      toast(t('Couldn’t sign that device out.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  const signOutAll = async () => {
    const n = others.length;
    const ok = await confirm({ title: t('Sign out everywhere else?'), text: everywhereText(others), action: tn(n, 'Sign out {n} device', 'Sign out {n} devices'), danger: true });
    if (!ok) return;
    setBusy(true);
    try {
      const r = await signOutOtherDevices();
      setOpen(null);
      toast(tn(r.signedOut, '{n} device signed out', '{n} devices signed out'));
      list.reload();
    } catch {
      toast(t('Couldn’t sign the other devices out.'), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <MeLayout
      page="devices"
      title={t('Devices')}
      lede={t('Everywhere you’re signed in. Signing a device out ends it right away; it needs your password or a Quick Connect code to come back.')}
    >
      {list.error && !list.data ? (
        <section class="me-section me-dev-failed" role="alert">
          <p class="me-section-help">{t('Couldn’t load your devices.')}</p>
          <button type="button" class="me-chip-link" onClick={list.reload}>{t('Try again')}</button>
        </section>
      ) : (
        <>
          <div class="me-dev-head"><h2>{t('This device')}</h2></div>
          <section aria-label={t('This device')} class="me-dev-list">
            <div role="list">
              {!list.data ? <SkeletonRow /> : here.map((d) => <DeviceRow key={d.id} d={d} />)}
            </div>
          </section>

          <div class="me-dev-head">
            <h2>{t('Other devices')}</h2>
            {list.data && others.length > 0 && (
              <p>{[tn(others.length, '{n} device', '{n} devices'), playing ? tn(playing, '{n} playing right now', '{n} playing right now') : ''].filter(Boolean).join(' · ')}</p>
            )}
          </div>
          <section aria-label={t('Other devices')} class="me-dev-list">
            {!list.data ? (
              <div role="list"><SkeletonRow /><SkeletonRow /></div>
            ) : others.length === 0 ? (
              <p class="me-dev-none">{t('You’re not signed in anywhere else.')}</p>
            ) : (
              <div role="list">
                {others.map((d) => (
                  <DeviceRow
                    key={d.id}
                    d={d}
                    open={open === d.id}
                    busy={busy}
                    onToggle={() => setOpen(open === d.id ? null : d.id)}
                    onSignOut={(pw) => void signOut(d, pw)}
                  />
                ))}
              </div>
            )}
          </section>

          {others.length > 0 && (
            <section aria-labelledby="all-h" class="me-dev-all">
              <div class="me-dev-all-text">
                <h2 id="all-h">{t('Sign out everywhere else')}</h2>
                <p>{everywhereText(others)}</p>
              </div>
              <button type="button" class="me-dev-all-btn" disabled={busy} onClick={() => void signOutAll()}>
                {tn(others.length, 'Sign out {n} device', 'Sign out {n} devices')}
              </button>
            </section>
          )}

          <span class="me-foot">{t('Your admin can see these devices too, and sign them out from Admin › Devices.')}</span>
        </>
      )}
    </MeLayout>
  );
}

function SkeletonRow() {
  return (
    <div role="listitem" class="me-dev" aria-hidden="true">
      <div class="me-dev-row">
        <span class="me-dev-icon skel" />
        <div class="me-dev-text"><span class="skel skel-line" style={{ width: '40%' }} /><span class="skel skel-line" style={{ width: '65%' }} /></div>
        <div class="me-dev-when"><span class="skel skel-line" style={{ width: '70%' }} /></div>
      </div>
    </div>
  );
}

function DeviceRow({ d, open, busy, onToggle, onSignOut }: {
  d: MyDevice;
  open?: boolean;
  busy?: boolean;
  onToggle?: () => void;
  onSignOut?: (thenPassword: boolean) => void;
}) {
  const kind = deviceKind(d);
  const warn = flagged(d);
  const showIp = d.ip && (d.current || d.isNew);
  const parts = [...appParts(d), networkLabel(d)].filter(Boolean);
  return (
    <div role="listitem" class="me-dev">
      <div class="me-dev-row">
        <span aria-hidden="true" class="me-dev-icon"><Icon name={ICON[kind]} size={24} /></span>
        <div class="me-dev-text">
          <span class="me-dev-name">
            {d.name || t('Unknown device')}
            {d.current && <span class="me-dev-tag">{t('This device')}</span>}
            {d.playing && !d.playing.paused && <span class="me-dev-tag is-playing">{t('Playing now')}</span>}
            {d.isNew && <span class="me-dev-tag is-new">{t('New')}</span>}
          </span>
          <span class="me-dev-app">
            {parts.join(' · ')}
            {showIp && <>{parts.length ? ' · ' : ''}<span class="me-dev-ip">{d.ip}</span></>}
          </span>
          {d.playing && <span class="me-dev-playing"><Icon name="playLine" size={13} />{playingLine(d)}</span>}
        </div>
        <div class="me-dev-when">
          <span class={'me-dev-last' + (d.active ? ' is-active' : '')}>{lastUsed(d)}</span>
          <span class="me-dev-since">{since(d)}</span>
        </div>
        {d.current ? (
          <span class="me-dev-here">{t('You’re here')}</span>
        ) : (
          <button
            type="button"
            class={'me-dev-out' + (warn || open ? ' is-danger' : '')}
            aria-label={t('Sign out {name}', { name: d.name })}
            aria-expanded={open ? 'true' : 'false'}
            disabled={busy}
            onClick={onToggle}
          >
            {t('Sign out')}
          </button>
        )}
      </div>
      {warn && (
        <p class="me-dev-warn">
          <Icon name="warn" size={16} />
          {t('First seen {when}, from outside your home network. Not you? Sign it out, then change your password.', { when: firstSeenAt(d) })}
        </p>
      )}
      {open && onSignOut && <SignOutPanel d={d} busy={!!busy} suggestPassword={warn} onSignOut={onSignOut} onCancel={onToggle!} />}
    </div>
  );
}

/* The board's inline question under the row, rather than a sheet over the page. */
function SignOutPanel({ d, busy, suggestPassword, onSignOut, onCancel }: {
  d: MyDevice;
  busy: boolean;
  suggestPassword: boolean;
  onSignOut: (thenPassword: boolean) => void;
  onCancel: () => void;
}) {
  const [password, setPassword] = useState(suggestPassword);
  const box = useRef<HTMLDivElement>(null);
  const hid = `so-${d.id.replace(/[^\w-]/g, '')}`;
  useEffect(() => { box.current?.focus(); }, []);
  return (
    <div
      ref={box}
      role="alertdialog"
      aria-labelledby={hid}
      aria-describedby={hid + '-d'}
      tabIndex={-1}
      class="me-dev-confirm"
      onKeyDown={(e) => { if (e.key === 'Escape') { e.stopPropagation(); onCancel(); } }}
    >
      <div class="me-dev-confirm-text">
        <span id={hid} class="me-dev-confirm-title">{t('Sign out {name}?', { name: d.name })}</span>
        <span id={hid + '-d'}>{t('It stops right away and needs a password or a Quick Connect code to get back in.')}</span>
      </div>
      <label class="me-dev-check">
        <input type="checkbox" checked={password} onChange={(e) => setPassword((e.currentTarget as HTMLInputElement).checked)} />
        <span aria-hidden="true" class="me-dev-box"><Icon name="check" size={15} stroke={3} /></span>
        {t('Change my password next')}
      </label>
      <div class="me-dev-confirm-actions">
        <button type="button" class="me-dev-yes" disabled={busy} onClick={() => onSignOut(password)}>{busy ? t('Signing out…') : t('Sign it out')}</button>
        <button type="button" class="me-dev-no" onClick={onCancel}>{t('Cancel')}</button>
      </div>
    </div>
  );
}
