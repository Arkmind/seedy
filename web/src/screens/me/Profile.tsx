import { useEffect, useRef, useState } from 'preact/hooks';
import { ApiError, deviceId, deviceName, getSession, get, request } from '../../api/client';
import { quickConnectEnabled } from '../../api/jellyfin';
import { userImageUrl } from '../../api/images';
import { myRequests, passwordState } from '../../api/seedy';
import type { SessionInfo } from '../../api/types';
import { meStore, userStore } from '../../lib/app-state';
import { Link } from '../../lib/router';
import { useAsync, useStore } from '../../lib/store';
import { Switch } from '../../ui/controls';
import { Icon } from '../../ui/icons';
import { confirm, toast } from '../../ui/overlay';
import { DeviceTag, MeLayout } from './MeLayout';
import { reloadUser, removeUserImage, savePrefsPatch, uploadUserImage } from './prefs';
import { MeRow, MeSection, SaveLine } from './parts';
import './parts.css';
import { t, tx } from '../../lib/i18n';

/* Settings · Profile and sign-in (MeProfile board). */

export function MeProfile() {
  const me = useStore(meStore);
  const user = useStore(userStore);
  const prefs = me.prefs;
  const privacy = prefs.privacy ?? { personalRecs: true, countInTop10: true };
  const requests = useAsync(() => (me.requests.connected ? myRequests() : Promise.resolve(null)), [me.requests.connected]);
  const seerrName = requests.data?.results.find((r) => r.requestedBy)?.requestedBy ?? user?.Name ?? '';

  return (
    <MeLayout
      page="profile"
      title={t('Profile and sign-in')}
      lede={<>{tx('Your picture, password and the devices you sign in on. Settings follow your profile to every device unless marked {tag}.', { tag: <DeviceTag /> })}</>}
    >
      <ProfileCard />
      <PasswordSection />
      <QuickConnectSection />

      {me.requests.connected && (
        <MeSection id="requests" title={t('Requests')} help={t('Your Seerr account, used when you request a movie or show.')}>
          <MeRow
            name={t('Seerr account')}
            help={me.requests.account
              ? `${me.requests.account.name.toLowerCase()} · ${me.requests.account.linkedBy === 'manual' ? t('linked by your admin') : t('linked through Jellyfin sign-in')}`
              : me.requests.canRequest ? `${seerrName.toLowerCase()} · ${t('linked through Jellyfin sign-in')}`
              : t('Not linked yet · ask your admin to link your Seerr account')}
          >
            <Link to="/requests/my" class="me-chip-link">
              {requests.data ? t('My requests · {n} open', { n: requests.data.open }) : t('My requests')}
            </Link>
          </MeRow>
          <MeRow name={t('Tell me when a request is ready')} help={t('A notice on Home when something you asked for arrives')}>
            <Switch label={t('Tell me when a request is ready')} checked={prefs.notifyRequests !== false} onChange={(v) => savePrefsPatch({ notifyRequests: v })} />
          </MeRow>
        </MeSection>
      )}

      <MeSection id="privacy" title={t('Privacy')} help={t('What Seedy does with what you watch. Your admin can turn these off for everyone.')}>
        <MeRow name={t('Personal recommendations')} help={t('Because you watched, genre rows and row order learn from your plays')}>
          <Switch label={t('Personal recommendations')} checked={privacy.personalRecs} onChange={(v) => savePrefsPatch({ privacy: { ...privacy, personalRecs: v } })} />
        </MeRow>
        <MeRow name={t('Count my plays in the Top 10')} help={t('Others see totals only, never who watched')}>
          <Switch label={t('Count my plays in the Top 10')} checked={privacy.countInTop10} onChange={(v) => savePrefsPatch({ privacy: { ...privacy, countInTop10: v } })} />
        </MeRow>
        {me.historyEnabled && (
          <MeRow name={t('Viewing history')} help={t('See, hide or clear what you watched')}>
            <Link to="/history" class="me-chip-link">{t('Open history')}</Link>
          </MeRow>
        )}
      </MeSection>

      <SaveLine />
    </MeLayout>
  );
}

/* ── Picture, name and where you're signed in ── */

function browserName() {
  const ua = navigator.userAgent;
  return /Edg\//.test(ua) ? 'Edge' : /Firefox\//.test(ua) ? 'Firefox' : /Chrome\//.test(ua) ? 'Chrome' : /Safari\//.test(ua) ? 'Safari' : t('a browser');
}

/* "Alex's MacBook · Chrome": the device's name as the server knows it (an admin may have renamed it), then the browser. */
function sessionLine(s: SessionInfo | undefined): string {
  const browser = browserName();
  const ours = deviceName();
  const name = s?.DeviceName && s.DeviceName !== ours ? s.DeviceName : ours.replace(/^\S+ on /, '') || ours;
  return name && name !== browser ? t('Signed in on {device} · {browser}', { device: name, browser }) : t('Signed in on {browser}', { browser });
}

function ProfileCard() {
  const user = useStore(userStore);
  const me = useStore(meStore);
  const file = useRef<HTMLInputElement>(null);
  const [busy, setBusy] = useState(false);
  const session = useAsync(() => get<SessionInfo[]>('/Sessions', { deviceId: deviceId() }).then((l) => l[0]).catch(() => undefined), []);
  if (!user) return null;
  const img = userImageUrl(user, 208);

  const pick = async (f: File | undefined) => {
    if (!f) return;
    if (!/^image\/(jpeg|png|webp|gif|bmp)$/.test(f.type)) { toast(t('Pick a JPEG, PNG or WebP picture'), 'error'); return; }
    if (f.size > 10 * 1024 * 1024) { toast(t('That picture is over 10 MB'), 'error'); return; }
    setBusy(true);
    try {
      await uploadUserImage(user.Id, f);
      await reloadUser();
      toast(t('Picture changed'));
    } catch {
      toast(t("Couldn't change the picture"), 'error');
    } finally {
      setBusy(false);
      if (file.current) file.current.value = '';
    }
  };
  const remove = async () => {
    setBusy(true);
    try {
      await removeUserImage(user.Id);
      await reloadUser();
      toast(t('Picture removed'));
    } catch {
      toast(t("Couldn't remove the picture"), 'error');
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-label={t('Profile')} class="me-section me-profile">
      <span aria-hidden="true" class="me-avatar">{img ? <img src={img} alt="" /> : user.Name.charAt(0).toUpperCase()}</span>
      <div class="me-profile-text">
        <span class="me-profile-name">{user.Name}</span>
        <span class="me-profile-meta">
          {me.isAdmin && <span class="me-admin-pill">{t('Admin')}</span>}
          {session.loading ? <span class="skel skel-line" style={{ width: '200px' }} /> : sessionLine(session.data)}
        </span>
        <span class="me-profile-note">{t('Only an admin can change your name.')}</span>
      </div>
      <div class="me-profile-actions">
        <input ref={file} type="file" accept="image/jpeg,image/png,image/webp,image/gif" class="sr-only" tabIndex={-1} aria-hidden="true" onChange={(e) => pick((e.currentTarget as HTMLInputElement).files?.[0])} />
        <button type="button" class="btn btn-primary me-btn-light" disabled={busy} onClick={() => file.current?.click()}>{t('Change picture')}</button>
        {user.PrimaryImageTag && <button type="button" class="btn btn-ghost me-btn-quiet" disabled={busy} onClick={remove}>{t('Remove')}</button>}
      </div>
    </section>
  );
}

/* ── Password ── */

/* "Changed 3 days ago": one sentence per case, so French can word each its own way. */
function changedLabel(d: Date): string {
  const days = Math.floor((Date.now() - d.getTime()) / 86_400_000);
  if (days < 1) return t('Changed today');
  if (days === 1) return t('Changed yesterday');
  if (days < 30) return t('Changed {n} days ago', { n: days });
  const months = Math.round(days / 30.4);
  if (months < 12) return months === 1 ? t('Changed a month ago') : t('Changed {n} months ago', { n: months });
  const years = Math.round(days / 365);
  return years === 1 ? t('Changed a year ago') : t('Changed {n} years ago', { n: years });
}

function PasswordSection() {
  const user = useStore(userStore);
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [again, setAgain] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [changedNow, setChangedNow] = useState(false);
  /* Only the activity log knows when a password changed; the Seedy plugin reads it for everyone. */
  const changed = useAsync(async () => {
    if (!user) return null;
    try {
      const r = await passwordState();
      return r.changedAt ? new Date(r.changedAt) : null;
    } catch { return null; }
  }, [user?.Id]);

  const hasPassword = user?.HasPassword !== false;
  const line = changedNow ? t('Changed just now')
    : changed.data ? changedLabel(changed.data)
    : !hasPassword ? t('No password yet. Anyone who picks your profile can open it.')
    : t('The same password for every Jellyfin app you use');

  const submit = async (e: Event) => {
    e.preventDefault();
    if (!user) return;
    if (!current && !next && !again) { setError(hasPassword ? t('Type your current password, then the new one twice.') : t('Type the new password twice.')); return; }
    if (next !== again) { setError(t("The new passwords don't match.")); return; }
    if (!next && hasPassword && !(await confirm({ title: t('Remove your password?'), text: t('Anyone who picks your profile will be able to open it, on every Jellyfin app.'), action: t('Remove password'), danger: true }))) return;
    const s = getSession();
    if (!s) return;
    setBusy(true);
    setError('');
    try {
      /* userId makes Jellyfin check the current password even for admins; the explicit token keeps a refusal from signing out. */
      await request<void>('POST', '/Users/Password', { query: { userId: user.Id }, body: { CurrentPw: current, NewPw: next }, token: s.token });
      setCurrent(''); setNext(''); setAgain('');
      setChangedNow(true);
      await reloadUser();
      toast(next ? t('Password changed. Other devices need to sign in again.') : t('Password removed'));
    } catch (err) {
      setError(err instanceof ApiError && (err.status === 401 || err.status === 403) ? t("That current password didn't work.") : t("Couldn't change the password. Try again."));
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby="pw-h" class="me-section is-gap-14">
      <div class="me-section-head is-flush">
        <h2 id="pw-h" class="me-section-title">{t('Password')}</h2>
        <p class="me-section-help is-tight">{changed.loading && !changedNow ? <span class="skel skel-line" style={{ display: 'block', width: '160px', marginTop: '4px' }} /> : line}</p>
      </div>
      <form class="me-pw" onSubmit={submit}>
        {/* A hidden username helps password managers file the change under the right account. */}
        <input type="text" autoComplete="username" value={user?.Name ?? ''} class="sr-only" tabIndex={-1} aria-hidden="true" readOnly />
        <div class="me-pw-grid">
          <label class="me-field-label">{t('Current password')}
            <input class="me-input" type="password" autoComplete="current-password" value={current} onInput={(e) => setCurrent((e.currentTarget as HTMLInputElement).value)} />
          </label>
          <label class="me-field-label">{t('New password')}
            <input class="me-input" type="password" autoComplete="new-password" value={next} onInput={(e) => { setNext((e.currentTarget as HTMLInputElement).value); setError(''); }} />
          </label>
          <label class="me-field-label">{t('Repeat new password')}
            <input class="me-input" type="password" autoComplete="new-password" value={again} onInput={(e) => { setAgain((e.currentTarget as HTMLInputElement).value); setError(''); }} />
          </label>
        </div>
        <div class="me-pw-foot">
          {error && <p class="form-error" role="alert"><Icon name="alert" size={16} />{error}</p>}
          <button type="submit" class="btn btn-primary me-btn-light is-wide" disabled={busy}>{busy ? t('Changing…') : t('Change password')}</button>
        </div>
      </form>
    </section>
  );
}

/* ── Quick Connect: the TV shows a code, this signs it in as you ── */

function QuickConnectSection() {
  const enabled = useAsync(() => quickConnectEnabled(), []);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);
  const codeRef = useRef<HTMLInputElement>(null);
  const digits = code.replace(/\D/g, '');
  const off = enabled.data === false;

  useEffect(() => { if (msg?.ok) { const timer = window.setTimeout(() => setMsg(null), 6000); return () => window.clearTimeout(timer); } }, [msg]);

  const submit = async (e: Event) => {
    e.preventDefault();
    if (busy) return;
    if (digits.length !== 6) { setMsg({ ok: false, text: t('Type the 6-digit code the TV shows.') }); codeRef.current?.focus(); return; }
    setBusy(true);
    setMsg(null);
    try {
      const ok = await request<boolean>('POST', '/QuickConnect/Authorize', { query: { code: digits }, token: getSession()?.token });
      if (ok === false) throw new ApiError(404, 'not found');
      setCode('');
      setMsg({ ok: true, text: t('The TV is signed in as you.') });
    } catch (err) {
      const status = err instanceof ApiError ? err.status : 0;
      setMsg({ ok: false, text: status === 404 ? t("That code didn't work. Check the TV and try again.") : status === 401 ? t('Quick Connect is turned off on this server.') : t("Couldn't sign the TV in. The code may have been used already.") });
    } finally {
      setBusy(false);
    }
  };

  return (
    <section aria-labelledby="qc-h" class="me-section me-qc">
      <div class="me-qc-text">
        <h2 id="qc-h" class="me-section-title">{t('Sign in a TV with a code')}</h2>
        <p class="me-section-help">
          {off ? t('Quick Connect is turned off on this server. An admin can turn it on under Server › Settings.') : t('Choose Quick Connect on the TV, then type the code it shows. The TV signs in as you.')}
        </p>
        {msg && (
          <p class={'me-qc-msg' + (msg.ok ? ' is-ok' : '')} role={msg.ok ? 'status' : 'alert'}>
            <Icon name={msg.ok ? 'check' : 'alert'} size={16} stroke={msg.ok ? 2.6 : 2} />{msg.text}
          </p>
        )}
      </div>
      <form class="me-qc-form" onSubmit={submit}>
        <input
          ref={codeRef}
          type="text"
          class="me-qc-code"
          aria-label={t('Quick Connect code')}
          inputMode="numeric"
          autoComplete="one-time-code"
          placeholder="000 000"
          maxLength={7}
          disabled={off}
          value={digits.length > 3 ? digits.slice(0, 3) + ' ' + digits.slice(3, 6) : digits}
          onInput={(e) => { setCode((e.currentTarget as HTMLInputElement).value.replace(/\D/g, '').slice(0, 6)); setMsg(null); }}
        />
        <button type="submit" class="btn btn-primary me-btn-light is-qc" disabled={off || busy}>{busy ? t('Signing in…') : t('Sign in TV')}</button>
      </form>
    </section>
  );
}
