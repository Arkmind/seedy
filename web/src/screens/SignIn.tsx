import { useEffect, useRef, useState } from 'preact/hooks';
import { ApiError, SERVER_URL, request } from '../api/client';
import { authenticateByName, authenticateWithQuickConnect, quickConnectEnabled, quickConnectInitiate, quickConnectState } from '../api/jellyfin';
import { signInBackdrops } from '../api/seedy';
import type { AuthResult } from '../api/types';
import { brandingStore, completeSignIn } from '../lib/app-state';
import { useStore } from '../lib/store';
import { BrandMark } from '../ui/navbar/Header';
import { Icon } from '../ui/icons';
import { t } from '../lib/i18n';
import './sign-in.css';

/* Sign in with a Jellyfin account (username and password, or Quick Connect) over slowly crossfading library
   backdrops (admin Branding setting). There's no profile picker: Seedy uses accounts, not shared-screen profiles. */

type Step = { kind: 'manual' } | { kind: 'quick' };

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
  const [step, setStep] = useState<Step>({ kind: 'manual' });
  const [qcOn, setQcOn] = useState(false);
  const [disclaimer, setDisclaimer] = useState('');

  useEffect(() => {
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

        {step.kind === 'manual' && (
          <ManualStep onDone={finish} quickConnect={qcOn ? () => setStep({ kind: 'quick' }) : undefined} />
        )}

        {step.kind === 'quick' && (
          <QuickConnectStep onBack={() => setStep({ kind: 'manual' })} onDone={finish} />
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

function ManualStep({ onDone, quickConnect }: { onDone: (a: AuthResult) => Promise<void>; quickConnect?: () => void }) {
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
      <h1 id="signin-h" class="signin-title">{t('Sign in')}</h1>
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

function QuickConnectStep({ onBack, onDone }: { onBack: () => void; onDone: (a: AuthResult) => Promise<void> }) {
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
      <BackLink onClick={onBack} label={t('Username and password')} />
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
