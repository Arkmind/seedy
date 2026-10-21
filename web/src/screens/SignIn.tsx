import { useEffect, useRef, useState } from 'preact/hooks';
import { ApiError, SERVER_URL, request } from '../api/client';
import { authenticateByName, authenticateWithQuickConnect, getPublicUsers, quickConnectEnabled, quickConnectInitiate, quickConnectState } from '../api/jellyfin';
import { userImageUrl } from '../api/images';
import { signInBackdrops } from '../api/seedy';
import type { AuthResult, User } from '../api/types';
import { brandingStore, completeSignIn } from '../lib/app-state';
import { useStore } from '../lib/store';
import { BrandMark } from '../ui/navbar/Header';
import { Icon } from '../ui/icons';
import { t } from '../lib/i18n';
import './sign-in.css';

/* Sign in over slowly crossfading library backdrops (admin Branding setting). "Who's watching?" shows the users the
   server lists on its sign-in screen (Jellyfin's "Hide this user from login screens" leaves one out); picking one asks
   for the password. Jellyfin 12 reports every user as having one, so the step always shows and an empty password
   is fine for users without one (never guessed: failed tries count toward Jellyfin's lockout). A server that does
   report HasPassword false signs that user straight in. "Other" and Quick Connect cover everyone else. */

type Step = { kind: 'pick' } | { kind: 'password'; user: User } | { kind: 'manual' } | { kind: 'quick' };

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

  return (
    <div class="signin">
      <Backdrops plain={brand.signInBackground !== 'backdrops'} />
      <div class="signin-body">
        <div class="signin-brand">
          <BrandMark size={64} />
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
          <PasswordStep user={step.user} onBack={() => setStep({ kind: 'pick' })} onDone={finish} />
        )}

        {step?.kind === 'manual' && (
          <ManualStep
            onDone={finish}
            onBack={users.length ? () => setStep({ kind: 'pick' }) : undefined}
            quickConnect={qcOn ? () => setStep({ kind: 'quick' }) : undefined}
          />
        )}

        {step?.kind === 'quick' && (
          <QuickConnectStep onBack={() => setStep(users.length ? { kind: 'pick' } : { kind: 'manual' })} backLabel={users.length ? t("Who's watching?") : t('Username and password')} onDone={finish} />
        )}
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

function Face({ user }: { user: User }) {
  const img = userImageUrl(user, 240);
  const [ok, setOk] = useState(true);
  return (
    <span class="signin-face" aria-hidden="true">
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
    <section class="signin-step" aria-labelledby="signin-h">
      <h1 id="signin-h" class="signin-title">{t("Who's watching?")}</h1>
      <ul class="signin-grid">
        {users.map((u) => (
          <li key={u.Id}>
            <button type="button" class="signin-tile" disabled={busy !== null} aria-busy={busy === u.Id ? 'true' : undefined} onClick={() => pick(u)}>
              <Face user={u} />
              <span class="signin-name">{u.Name}</span>
            </button>
          </li>
        ))}
        <li>
          <button type="button" class="signin-tile" disabled={busy !== null} onClick={onOther}>
            <span class="signin-face is-other" aria-hidden="true"><Icon name="plus" size={40} stroke={2.2} /></span>
            <span class="signin-name">{t('Other')}</span>
          </button>
        </li>
      </ul>
      {error && <p class="form-error signin-error" role="alert"><Icon name="alert" size={16} />{error}</p>}
      {quickConnect && (
        <button type="button" class="btn btn-ghost is-light signin-qc" onClick={quickConnect}>
          <Icon name="key" size={18} />
          {t('Use Quick Connect')}
        </button>
      )}
    </section>
  );
}

function PasswordStep({ user, onBack, onDone }: { user: User; onBack: () => void; onDone: (a: AuthResult) => Promise<void> }) {
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

function ManualStep({ onDone, onBack, quickConnect }: { onDone: (a: AuthResult) => Promise<void>; onBack?: () => void; quickConnect?: () => void }) {
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
      {quickConnect && (
        <button type="button" class="btn btn-ghost is-light signin-qc" onClick={quickConnect}>
          <Icon name="key" size={18} />
          {t('Use Quick Connect')}
        </button>
      )}
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
