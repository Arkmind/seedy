import { useEffect, useRef, useState } from 'preact/hooks';
import { ApiError, SERVER_URL, request } from '../api/client';
import { authenticateByName, authenticateWithQuickConnect, forgotPassword, forgotPasswordPin, getPublicUsers, quickConnectEnabled, quickConnectInitiate, quickConnectState, type ForgotPasswordResult } from '../api/jellyfin';
import { userImageUrl } from '../api/images';
import { signInBackdrops } from '../api/seedy';
import type { AuthResult, User } from '../api/types';
import { brandingStore, completeSignIn, loadServerName, serverNameStore } from '../lib/app-state';
import { useStore } from '../lib/store';
import { BrandMark } from '../ui/navbar/Header';
import { Icon } from '../ui/icons';
import { Medu } from '../ui/medu';
import { Sea, type Bubble, type Flake, type Ray } from '../ui/sea';
import { t } from '../lib/i18n';
import { hhmm24 } from '../lib/format';
import './sign-in.css';

/* Sign in over slowly crossfading library backdrops (admin Branding setting). "Who's watching?" shows the users the
   server lists on its sign-in screen (Jellyfin's "Hide this user from login screens" leaves one out); picking one asks
   for the password. Jellyfin 12 reports every user as having one, so the step always shows and an empty password
   is fine for users without one (never guessed: failed tries count toward Jellyfin's lockout). A server that does
   report HasPassword false signs that user straight in. "Add profile" (a name and password) and Quick Connect cover
   everyone else. Without backdrops the page is the deep sea of the Who's watching board, with Medu waving hello. */

const RAYS: Ray[] = [[88, 175, -5.7], [718, 114, -7.1], [1349, 152, -4.5]];
const SNOW: Flake[] = [[534, 421, 1.5, 0.16, 17.3, -13.7], [60, 718, 1.9, 0.30, 17.3, -15.1], [473, 358, 1.6, 0.17, 22.6, -21.5], [1177, 89, 1.8, 0.34, 19.2, -8.7], [697, 134, 2.1, 0.15, 21.7, -18.8], [300, 697, 2.8, 0.34, 17.3, -13.0], [1050, 88, 2.5, 0.24, 24.7, -20.4], [1278, 294, 3.1, 0.22, 16.8, -7.6], [556, 19, 3.2, 0.16, 19.4, -13.9], [707, 396, 2.3, 0.30, 27.9, -6.2], [896, 130, 2.4, 0.18, 22.5, -7.8], [860, 220, 2.1, 0.16, 27.0, -1.8], [539, 492, 2.8, 0.29, 25.4, -22.8], [1424, 692, 2.2, 0.27, 27.4, -24.2], [355, 630, 2.1, 0.18, 20.9, -1.4], [695, 602, 2.8, 0.15, 25.3, -6.5], [245, 50, 2.2, 0.21, 18.1, -16.2], [689, 181, 2.8, 0.35, 27.8, -14.6], [305, 603, 1.5, 0.31, 24.2, -21.3], [836, 611, 2.2, 0.21, 22.4, -12.0], [14, 493, 2.8, 0.35, 17.9, -0.4], [1226, 420, 2.6, 0.20, 22.3, -9.0]];
const BUBBLES: Bubble[] = [[245, 47, 12, 11.6, -1.1], [1090, 45, 6, 10.6, -1.3], [770, 65, 8, 8.5, -4.2], [307, 108, 7, 10.5, -2.1]];

type Step = { kind: 'pick' } | { kind: 'password'; user: User } | { kind: 'manual' } | { kind: 'quick' } | { kind: 'forgot'; name: string; back: Step };

function absolute(u: string): string {
  return /^https?:/i.test(u) ? u : SERVER_URL + (u.startsWith('/') ? u : '/' + u);
}

function errorText(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 401 || e.status === 403) return t("That username and password didn't work.");
    if (e.status >= 500) return t('The server had a problem signing you in. Try again in a moment.');
  }
  if (e instanceof TypeError) return t("Can't reach the server. Check your connection.");
  return t("That username and password didn't work.");
}

function stripTags(html: string): string {
  try {
    return new DOMParser().parseFromString(html, 'text/html').body.textContent?.trim() ?? '';
  } catch {
    return html.replace(/<[^>]*>/g, '').trim();
  }
}

export function SignIn() {
  const brand = useStore(brandingStore);
  const [step, setStep] = useState<Step | null>(null);
  const [users, setUsers] = useState<User[]>([]);
  const [qcOn, setQcOn] = useState(false);
  const [disclaimer, setDisclaimer] = useState('');

  useEffect(() => {
    getPublicUsers().then(
      (list) => {
        const shown = (list ?? []).filter((u) => u?.Id && u.Name);
        setUsers(shown);
        setStep((s) => s ?? (shown.length ? { kind: 'pick' } : { kind: 'manual' }));
      },
      () => setStep((s) => s ?? { kind: 'manual' })
    );
    quickConnectEnabled().then((on) => setQcOn(on === true), () => setQcOn(false));
    request<{ LoginDisclaimer?: string }>('GET', '/Branding/Configuration', { token: null }).then(
      (b) => setDisclaimer(b?.LoginDisclaimer ? stripTags(b.LoginDisclaimer) : ''),
      () => {}
    );
  }, []);

  const finish = async (auth: AuthResult) => {
    await completeSignIn(auth);
  };

  const sea = brand.signInBackground !== 'backdrops';
  return (
    <div class={'signin' + (sea ? ' sea-scene' : '') + (step?.kind === 'pick' ? ' is-pick' : '')}>
      {sea ? <Sea rays={RAYS} snow={SNOW} bubbles={BUBBLES} fish={[]} rise={460} rayHeight={1100} /> : <Backdrops plain={false} />}
      <div class="signin-body">
        <div class="signin-brand">
          <BrandMark size={step?.kind === 'pick' ? 36 : 64} />
          <span class="signin-brand-name">{brand.name || 'Seedy'}</span>
        </div>

        {!step && <PickSkeleton />}

        {step?.kind === 'pick' && (
          <PickStep
            users={users}
            onDone={finish}
            onPassword={(user) => setStep({ kind: 'password', user })}
            onOther={() => setStep({ kind: 'manual' })}
            quickConnect={qcOn ? () => setStep({ kind: 'quick' }) : undefined}
          />
        )}

        {step?.kind === 'password' && (
          <PasswordStep user={step.user} onBack={() => setStep({ kind: 'pick' })} onDone={finish} onForgot={() => setStep({ kind: 'forgot', name: step.user.Name, back: step })} />
        )}

        {step?.kind === 'manual' && (
          <ManualStep
            onDone={finish}
            onBack={users.length ? () => setStep({ kind: 'pick' }) : undefined}
            quickConnect={qcOn ? () => setStep({ kind: 'quick' }) : undefined}
            onForgot={(name) => setStep({ kind: 'forgot', name, back: step })}
          />
        )}

        {step?.kind === 'forgot' && (
          <ForgotStep name={step.name} onBack={() => setStep(step.back)} />
        )}

        {step?.kind === 'quick' && (
          <QuickConnectStep onBack={() => setStep(users.length ? { kind: 'pick' } : { kind: 'manual' })} backLabel={users.length ? t("Who's watching?") : t('Username and password')} onDone={finish} />
        )}

        {step?.kind === 'pick' && <ServerLine />}
      </div>
      {disclaimer && <p class="signin-disclaimer">{disclaimer}</p>}
    </div>
  );
}

function BackLink({ onClick, label }: { onClick: () => void; label: string }) {
  return (
    <button type="button" class="signin-back" onClick={onClick}>
      <Icon name="chevronLeft" size={16} stroke={2.2} />
      {label}
    </button>
  );
}

/* Profiles without a picture take the board's four colours in turn (light, blue, green, orange). */
function Face({ user, colour = 0 }: { user: User; colour?: number }) {
  const img = userImageUrl(user, 300);
  const [ok, setOk] = useState(true);
  return (
    <span class={'signin-face ' + (img && ok ? 'has-img' : `is-c${colour % 4}`)} aria-hidden="true">
      {img && ok ? <img src={img} alt="" onError={() => setOk(false)} /> : user.Name.trim().charAt(0).toUpperCase()}
    </span>
  );
}

function PickStep({ users, onDone, onPassword, onOther, quickConnect }: {
  users: User[];
  onDone: (a: AuthResult) => Promise<void>;
  onPassword: (user: User) => void;
  onOther: () => void;
  quickConnect?: () => void;
}) {
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState('');
  /* The first profile starts focused, so Enter (or a remote's OK) picks it. */
  const first = useRef<HTMLButtonElement>(null);
  useEffect(() => { first.current?.focus({ preventScroll: true }); }, []);
  const pick = async (u: User) => {
    if (u.HasPassword !== false) { onPassword(u); return; }
    setBusy(u.Id);
    setError('');
    try {
      await onDone(await authenticateByName(u.Name, ''));
    } catch (err) {
      setBusy(null);
      // The password may have been set since the list loaded: ask for it instead.
      if (err instanceof ApiError && (err.status === 401 || err.status === 403)) onPassword(u);
      else setError(errorText(err));
    }
  };
  return (
    <section class="signin-step is-pick" aria-labelledby="signin-h">
      <div class="signin-hello">
        <Medu mood="wave" size={120} class="signin-medu" />
        <h1 id="signin-h" class="signin-title">{t('Who’s watching?')}</h1>
      </div>
      <ul class="signin-grid">
        {users.map((u, i) => (
          <li key={u.Id}>
            <button ref={i === 0 ? first : undefined} type="button" class="signin-tile" disabled={busy !== null} aria-busy={busy === u.Id ? 'true' : undefined} onClick={() => pick(u)}>
              <Face user={u} colour={i} />
              <span class="signin-name">{u.Name}</span>
            </button>
          </li>
        ))}
        <li>
          <button type="button" class="signin-tile is-add" disabled={busy !== null} onClick={onOther}>
            <span class="signin-face is-other" aria-hidden="true"><Icon name="plus" size={44} stroke={2.2} /></span>
            <span class="signin-name">{t('Add profile')}</span>
          </button>
        </li>
      </ul>
      {error && <p class="form-error signin-error" role="alert"><Icon name="alert" size={16} />{error}</p>}
      {quickConnect && (
        <button type="button" class="btn btn-secondary medu-actions-btn signin-qc" onClick={quickConnect}>
          <Icon name="key" size={18} />
          {t('Use Quick Connect')}
        </button>
      )}
    </section>
  );
}

/* "● Arky's server · 192.168.1.20": which server these profiles are on. */
function ServerLine() {
  const name = useStore(serverNameStore);
  useEffect(() => { void loadServerName(); }, []);
  return (
    <span class="signin-server">
      <span class="signin-server-dot" aria-hidden="true" />
      {[name, new URL(SERVER_URL).hostname].filter(Boolean).join(' · ')}
    </span>
  );
}

function PasswordStep({ user, onBack, onDone, onForgot }: { user: User; onBack: () => void; onDone: (a: AuthResult) => Promise<void>; onForgot: () => void }) {
  const [pw, setPw] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => { ref.current?.focus(); }, []);
  const submit = async (e: Event) => {
    e.preventDefault();
    setBusy(true);
    setError('');
    try {
      await onDone(await authenticateByName(user.Name, pw));
    } catch (err) {
      setBusy(false);
      setError(err instanceof ApiError && (err.status === 401 || err.status === 403) ? t("That password didn't work.") : errorText(err));
      ref.current?.select();
    }
  };
  return (
    <section class="signin-step is-form" aria-labelledby="signin-h">
      <BackLink onClick={onBack} label={t("Who's watching?")} />
      <Face user={user} />
      <h1 id="signin-h" class="signin-who">{user.Name}</h1>
      <form class="signin-form" onSubmit={submit}>
        {/* Lets the browser's password manager match the saved login. */}
        <input type="text" name="username" autoComplete="username" value={user.Name} hidden readOnly />
        <label class="signin-label">
          <span>{t('Password')}</span>
          <input
            ref={ref}
            class="field signin-field"
            type="password"
            autoComplete="current-password"
            value={pw}
            aria-invalid={error ? 'true' : undefined}
            onInput={(e) => setPw((e.currentTarget as HTMLInputElement).value)}
          />
        </label>
        {error && <p class="form-error signin-error" role="alert"><Icon name="alert" size={16} />{error}</p>}
        <button type="submit" class="btn btn-primary is-block" disabled={busy}>{busy ? t('Signing in…') : t('Sign in')}</button>
      </form>
      <button type="button" class="signin-back" onClick={onForgot}>{t('Forgot password?')}</button>
    </section>
  );
}

function PickSkeleton() {
  return (
    <section class="signin-step" aria-busy="true" aria-label={t('Loading')}>
      <ul class="signin-grid">
        {[0, 1, 2].map((i) => <li key={i}><span class="signin-tile"><span class="signin-face skel" /><span class="skel skel-line" style={{ width: '72px' }} /></span></li>)}
      </ul>
    </section>
  );
}

function ManualStep({ onDone, onBack, quickConnect, onForgot }: { onDone: (a: AuthResult) => Promise<void>; onBack?: () => void; quickConnect?: () => void; onForgot: (name: string) => void }) {
  const [name, setName] = useState('');
  const [pw, setPw] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const nameRef = useRef<HTMLInputElement>(null);
  useEffect(() => { nameRef.current?.focus(); }, []);

  const submit = async (e: Event) => {
    e.preventDefault();
    if (!name.trim()) { setError(t('Enter your username.')); nameRef.current?.focus(); return; }
    setBusy(true);
    setError('');
    try {
      await onDone(await authenticateByName(name.trim(), pw));
    } catch (err) {
      setBusy(false);
      setError(errorText(err));
    }
  };

  return (
    <section class="signin-step is-form" aria-labelledby="signin-h">
      {onBack && <BackLink onClick={onBack} label={t("Who's watching?")} />}
      <h1 id="signin-h" class={onBack ? 'signin-who' : 'signin-title'}>{t('Sign in')}</h1>
      <form class="signin-form" onSubmit={submit}>
        <label class="signin-label">
          <span>{t('Username')}</span>
          <input
            ref={nameRef}
            class="field signin-field"
            type="text"
            autoComplete="username"
            autoCapitalize="none"
            spellcheck={false}
            value={name}
            onInput={(e) => setName((e.currentTarget as HTMLInputElement).value)}
          />
        </label>
        <label class="signin-label">
          <span>{t('Password')}</span>
          <input
            class="field signin-field"
            type="password"
            autoComplete="current-password"
            value={pw}
            aria-invalid={error ? 'true' : undefined}
            onInput={(e) => setPw((e.currentTarget as HTMLInputElement).value)}
          />
        </label>
        {error && <p class="form-error signin-error" role="alert"><Icon name="alert" size={16} />{error}</p>}
        <button type="submit" class="btn btn-primary is-block" disabled={busy}>{busy ? t('Signing in…') : t('Sign in')}</button>
      </form>
      <button
        type="button"
        class="signin-back"
        onClick={() => {
          if (!name.trim()) { setError(t('Enter your username first.')); nameRef.current?.focus(); return; }
          onForgot(name.trim());
        }}
      >
        {t('Forgot password?')}
      </button>
      {quickConnect && (
        <button type="button" class="btn btn-ghost is-light signin-qc" onClick={quickConnect}>
          <Icon name="key" size={18} />
          {t('Use Quick Connect')}
        </button>
      )}
    </section>
  );
}

/* Jellyfin's "Forgot password": the server says whether to ask the admin, to come back on its network, or to read a
   PIN it wrote to a file on its disk. Redeeming that PIN makes it the new password. */
function ForgotStep({ name, onBack }: { name: string; onBack: () => void }) {
  const [result, setResult] = useState<ForgotPasswordResult | 'error' | null>(null);
  const [pin, setPin] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [reset, setReset] = useState<string[] | null>(null);
  const ref = useRef<HTMLInputElement>(null);

  useEffect(() => {
    let live = true;
    forgotPassword(name).then((r) => { if (live) setResult(r); }, () => { if (live) setResult('error'); });
    return () => { live = false; };
  }, [name]);
  useEffect(() => { if (result !== null && result !== 'error' && result.Action === 'PinCode') ref.current?.focus(); }, [result]);

  const redeem = async (e: Event) => {
    e.preventDefault();
    if (!pin.trim()) { ref.current?.focus(); return; }
    setBusy(true);
    setError('');
    try {
      const r = await forgotPasswordPin(pin.trim());
      if (r.Success) setReset(r.UsersReset ?? []);
      else setError(t("That PIN didn't work. Check it, or start again for a new one."));
    } catch {
      setError(t("That PIN didn't work. Check it, or start again for a new one."));
    } finally {
      setBusy(false);
    }
  };

  const until = result && result !== 'error' && result.PinExpirationDate ? new Date(result.PinExpirationDate) : null;
  const time = until && !isNaN(until.getTime()) ? hhmm24(until) : '';
  let body;
  if (result === null) {
    body = (
      <>
        <h1 id="signin-h" class="signin-who">{t('Forgot password?')}</h1>
        <p class="signin-text">{t('Asking the server…')}</p>
      </>
    );
  } else if (reset) {
    body = (
      <>
        <h1 id="signin-h" class="signin-who">{t('Password reset')}</h1>
        <p class="signin-text">{t('Sign in with the PIN as your password, then change it in Settings › Profile and sign-in.')}</p>
        <button type="button" class="btn btn-primary is-block" onClick={onBack}>{t('Back to sign in')}</button>
      </>
    );
  } else if (result !== 'error' && result.Action === 'PinCode') {
    body = (
      <>
        <h1 id="signin-h" class="signin-who">{t('Get the PIN from the server')}</h1>
        <p class="signin-text">
          {time
            ? t('The server wrote a PIN to a file on its disk. Whoever runs the server can open it and read it to you. It works until {time}.', { time })
            : t('The server wrote a PIN to a file on its disk. Whoever runs the server can open it and read it to you.')}
        </p>
        {result.PinFile && <p class="signin-file">{result.PinFile}</p>}
        <form class="signin-form" onSubmit={redeem}>
          <label class="signin-label">
            <span>{t('PIN')}</span>
            <input
              ref={ref}
              class="field signin-field"
              type="text"
              autoComplete="one-time-code"
              autoCapitalize="none"
              spellcheck={false}
              value={pin}
              aria-invalid={error ? 'true' : undefined}
              onInput={(e) => setPin((e.currentTarget as HTMLInputElement).value)}
            />
          </label>
          {error && <p class="form-error signin-error" role="alert"><Icon name="alert" size={16} />{error}</p>}
          <button type="submit" class="btn btn-primary is-block" disabled={busy}>{busy ? t('Resetting…') : t('Reset password')}</button>
        </form>
        <p class="signin-hint">{t('Your new password is the PIN. Change it once you’re in.')}</p>
      </>
    );
  } else if (result !== 'error' && result.Action === 'InNetworkRequired') {
    body = (
      <>
        <h1 id="signin-h" class="signin-who">{t('Only from home')}</h1>
        <p class="signin-text">{t('This server resets passwords only from its own network. Try again from home, or ask your server admin.')}</p>
      </>
    );
  } else {
    body = (
      <>
        <h1 id="signin-h" class="signin-who">{t('Ask your server admin')}</h1>
        <p class="signin-text">{t('Your server admin can set a new password for {name} in the server’s user settings.', { name })}</p>
      </>
    );
  }

  return (
    <section class="signin-step is-form" aria-labelledby="signin-h" aria-busy={result === null ? 'true' : undefined}>
      <BackLink onClick={onBack} label={t('Sign in')} />
      {body}
    </section>
  );
}

function QuickConnectStep({ onBack, backLabel, onDone }: { onBack: () => void; backLabel: string; onDone: (a: AuthResult) => Promise<void> }) {
  const [code, setCode] = useState('');
  const [error, setError] = useState('');
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let live = true;
    let timer: number | undefined;
    setCode('');
    setError('');
    quickConnectInitiate().then(
      ({ Code, Secret }) => {
        if (!live) return;
        setCode(Code);
        const poll = async () => {
          try {
            const s = await quickConnectState(Secret);
            if (!live) return;
            if (s.Authenticated) {
              await onDone(await authenticateWithQuickConnect(Secret));
              return;
            }
          } catch (e) {
            if (!live) return;
            if (e instanceof ApiError && (e.status === 404 || e.status === 401)) {
              setError(t('The code expired. Get a new one to try again.'));
              return;
            }
          }
          timer = window.setTimeout(poll, 3000);
        };
        timer = window.setTimeout(poll, 3000);
      },
      () => { if (live) setError(t("Quick Connect isn't available on this server right now.")); }
    );
    return () => { live = false; window.clearTimeout(timer); };
  }, [tick]);

  return (
    <section class="signin-step is-form" aria-labelledby="signin-h">
      <BackLink onClick={onBack} label={backLabel} />
      <h1 id="signin-h" class="signin-who">{t('Quick Connect')}</h1>
      <p class="signin-text">{t("On a phone or computer that's already signed in, open Quick Connect in Jellyfin's settings and enter this code.")}</p>
      <div class="signin-code tnum" aria-live="polite" aria-label={code ? t('Code {code}', { code: code.split('').join(' ') }) : t('Getting a code')}>
        {code ? code : <span class="skel signin-code-skel" />}
      </div>
      {error ? (
        <>
          <p class="form-error signin-error" role="alert"><Icon name="alert" size={16} />{error}</p>
          <button type="button" class="btn btn-secondary" onClick={() => setTick((n) => n + 1)}>
            <Icon name="refresh" size={16} />
            {t('Get a new code')}
          </button>
        </>
      ) : (
        code && <p class="signin-waiting"><span class="dot is-pending" />{t('Waiting for approval…')}</p>
      )}
    </section>
  );
}

/* Library backdrops crossfade slowly under a dark scrim; plain ground when turned off or there's nothing to show. */
function Backdrops({ plain }: { plain: boolean }) {
  const [urls, setUrls] = useState<string[]>([]);
  const [index, setIndex] = useState(0);
  const [loaded, setLoaded] = useState<Record<number, boolean>>({});

  useEffect(() => {
    if (plain) return;
    signInBackdrops().then(
      (list) => {
        const clean = (list ?? []).filter(Boolean).map(absolute);
        for (let i = clean.length - 1; i > 0; i--) {
          const j = Math.floor(Math.random() * (i + 1));
          [clean[i], clean[j]] = [clean[j], clean[i]];
        }
        setUrls(clean.slice(0, 12));
      },
      () => setUrls([])
    );
  }, [plain]);

  useEffect(() => {
    if (urls.length < 2) return;
    const reduce = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    const t = window.setInterval(() => setIndex((i) => (i + 1) % urls.length), reduce ? 20000 : 9000);
    return () => window.clearInterval(t);
  }, [urls.length]);

  if (plain || !urls.length) return null;
  /* Only the previous (fading out), current and next (preloading) images are mounted. */
  const next = (index + 1) % urls.length;
  const prev = (index - 1 + urls.length) % urls.length;
  return (
    <div class="signin-bg" aria-hidden="true">
      {urls.map((u, i) => (i === index || i === next || i === prev ? (
        <img
          key={u}
          src={u}
          alt=""
          class={'signin-bg-img' + (i === index && loaded[i] ? ' is-on' : '')}
          onLoad={() => setLoaded((l) => ({ ...l, [i]: true }))}
        />
      ) : null))}
      <div class="signin-scrim" />
    </div>
  );
}
