import { useRef } from 'preact/hooks';
import { brandingStore, userStore } from '../../lib/app-state';
import { LANGS, applyLanguagePref, browserLang, resolveLang, t } from '../../lib/i18n';
import { useStore } from '../../lib/store';
import { NumField, Segmented, Switch } from '../../ui/controls';
import { DeviceTag, MeLayout } from './MeLayout';
import { DATE_LOCALES, dateExample, resolveDateLocale } from './effects';
import { CUSTOM, deviceStore, saveCustom, saveUserConfig, setDevice, useCustom, userConfig } from './prefs';
import { MeRow, MeSection, Pick, SaveLine } from './parts';
import './parts.css';

/* Settings · Display (MeDisplay board). */

const SWATCHES: { label: string; color: string | null }[] = [
  { label: 'Server colour', color: null },
  { label: 'Sky', color: '#9fd3ff' },
  { label: 'Mint', color: '#b8f59f' },
  { label: 'Apricot', color: '#ffc98a' },
  { label: 'Lilac', color: '#c9b8ff' },
  { label: 'White', color: '#f0f0f0' }
];

export function MeDisplay() {
  const device = useStore(deviceStore);
  const user = useStore(userStore);
  const backdrops = useCustom(CUSTOM.backdrops);
  const themeMusic = useCustom(CUSTOM.themeMusic);
  const themeVideos = useCustom(CUSTOM.themeVideos);
  const language = useCustom(CUSTOM.language);
  const dateLocale = useCustom(CUSTOM.dateLocale);
  // The languages Seedy is translated into; Auto follows the browser. Saving reloads the app in the new language.
  const browser = LANGS.find((l) => l.value === browserLang())?.label ?? 'English';
  const langOptions = [
    { value: 'auto', label: t('Auto · {lang}', { lang: browser }) },
    ...LANGS.map((l) => ({ value: l.value, label: l.label }))
  ];
  const langValue = language === 'auto' || !language ? 'auto' : resolveLang(language);

  const now = new Date();
  const dateOptions = [
    { value: 'auto', label: t('Auto · {example}', { example: dateExample(resolveDateLocale('auto', language), now) }) },
    ...DATE_LOCALES.map((d) => ({ value: d.value, label: `${d.label.startsWith('English') ? t(d.label) : d.label} · ${dateExample(d.value, now)}` }))
  ];

  return (
    <MeLayout page="display" title={t('Display')} lede={t('Language, colour, what plays in the background, and the screensaver.')}>
      <AccentSection />

      <MeSection id="lang" title={t('Language and region')}>
        <MeRow name={t('Language')} help={t('Seedy’s menus and labels')} narrow>
          <Pick label={t('Language')} value={langValue} options={langOptions} onChange={(v) => { void saveCustom({ [CUSTOM.language]: v === 'auto' ? null : v }).then((ok) => { if (ok) applyLanguagePref(v); }); }} />
        </MeRow>
        <MeRow name={t('Dates and times')} help={t('Auto follows the language')} narrow>
          <Pick label={t('Dates and times')} value={dateLocale} options={dateOptions} width={300} onChange={(v) => saveCustom({ [CUSTOM.dateLocale]: v === 'auto' ? null : v })} />
        </MeRow>
      </MeSection>

      <MeSection id="browse" title={t('While browsing')}>
        <MeRow name={t('Backdrops')} help={t('Artwork behind details pages and the home banner')} narrow>
          <Switch label={t('Backdrops')} checked={backdrops} onChange={(v) => saveCustom({ [CUSTOM.backdrops]: v ? null : false })} />
        </MeRow>
        <MeRow name={t('Theme music')} help={t('Plays a show’s theme on its page, if the server has one')} narrow>
          <Switch label={t('Theme music')} checked={themeMusic} onChange={(v) => saveCustom({ [CUSTOM.themeMusic]: v || null })} />
        </MeRow>
        <MeRow name={t('Theme videos')} help={t('Plays a short clip behind a title’s page instead')} narrow>
          <Switch label={t('Theme videos')} checked={themeVideos} onChange={(v) => saveCustom({ [CUSTOM.themeVideos]: v || null })} />
        </MeRow>
        <MeRow name={t('Show missing episodes')} help={t('List episodes you don’t have, greyed out, in each season')} narrow>
          <Switch label={t('Show missing episodes')} checked={!!userConfig(user).DisplayMissingEpisodes} onChange={(v) => saveUserConfig({ DisplayMissingEpisodes: v })} />
        </MeRow>
        <MeRow name={t('Soft placeholders while images load')} help={t('A blurred preview instead of an empty box')} device narrow>
          <Switch label={t('Soft placeholders while images load')} checked={device.softPlaceholders} onChange={(v) => setDevice('softPlaceholders', v)} />
        </MeRow>
        <MeRow name={t('Faster animations')} help={t('Shorter transitions between pages')} device narrow>
          <Switch label={t('Faster animations')} checked={device.fastAnimations} onChange={(v) => setDevice('fastAnimations', v)} />
        </MeRow>
      </MeSection>

      <MeSection id="saver" title={t('Screensaver')}>
        <MeRow name={t('Screensaver')} help={t('Mainly for TVs')} device narrow>
          <Segmented
            label={t('Screensaver')}
            value={device.screensaver}
            options={[{ value: 'off', label: t('Off') }, { value: 'backdrops', label: t('Backdrops') }, { value: 'logo', label: t('Logo') }]}
            onChange={(v) => setDevice('screensaver', v)}
          />
        </MeRow>
        <MeRow name={t('Start after')} help={t('With nothing playing and no input')} device narrow>
          <NumField value={device.screensaverAfter} min={10} max={7200} width={52} suffix="s" ariaLabel={t('Start after')} onChange={(v) => setDevice('screensaverAfter', v)} />
        </MeRow>
        <MeRow name={t('Change backdrop every')} device narrow>
          <NumField value={device.backdropEvery} min={3} max={600} width={52} suffix="s" ariaLabel={t('Change backdrop every')} onChange={(v) => setDevice('backdropEvery', v)} />
        </MeRow>
        <MeRow name={t('Photo slideshows')} help={t('Time on each photo')} device narrow>
          <NumField value={device.slideshowEvery} min={2} max={600} width={52} suffix="s" ariaLabel={t('Photo slideshows')} onChange={(v) => setDevice('slideshowEvery', v)} />
        </MeRow>
      </MeSection>

      <MeSection id="layout" title={t('Layout')} help={t('Auto picks the TV layout on televisions and the phone layout on small screens.')}>
        <MeRow name={t('Layout')} device narrow>
          <Segmented
            label={t('Layout')}
            value={device.layout}
            options={[{ value: 'auto', label: t('Auto') }, { value: 'desktop', label: t('Desktop') }, { value: 'tv', label: t('TV') }]}
            onChange={(v) => setDevice('layout', v)}
          />
        </MeRow>
      </MeSection>

      <CustomCssSection />
      <SaveLine />
    </MeLayout>
  );
}

/* Counts, focus ring and selections: the server's colour or one of five personal ones. */
function AccentSection() {
  const branding = useStore(brandingStore);
  const accent = useCustom(CUSTOM.accent);
  const refs = useRef<(HTMLButtonElement | null)[]>([]);
  const current = Math.max(0, SWATCHES.findIndex((s) => s.color === (accent?.toLowerCase() ?? null)));
  const pick = (i: number) => saveCustom({ [CUSTOM.accent]: SWATCHES[i].color });
  const onKey = (e: KeyboardEvent, i: number) => {
    const d = e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : e.key === 'ArrowLeft' || e.key === 'ArrowUp' ? -1 : 0;
    if (!d) return;
    e.preventDefault();
    const n = (i + d + SWATCHES.length) % SWATCHES.length;
    pick(n);
    refs.current[n]?.focus();
  };
  return (
    <section aria-labelledby="accent-h" class="me-section me-accent">
      <div class="me-accent-text">
        <h2 id="accent-h" class="me-section-title">{t('Accent colour')}</h2>
        <p class="me-section-help">{t('Counts, focus ring and selections. Starts as your server’s colour.')}</p>
      </div>
      <div role="radiogroup" aria-label={t('Accent colour')} class="me-swatches">
        {SWATCHES.map((s, i) => (
          <button
            key={s.label}
            ref={(el) => { refs.current[i] = el; }}
            type="button"
            role="radio"
            class="me-swatch"
            aria-checked={i === current ? 'true' : 'false'}
            aria-label={t(s.label)}
            title={t(s.label)}
            tabIndex={i === current ? 0 : -1}
            style={{ background: s.color ?? branding.accent }}
            onClick={() => pick(i)}
            onKeyDown={(e) => onKey(e as unknown as KeyboardEvent, i)}
          />
        ))}
      </div>
    </section>
  );
}

function CustomCssSection() {
  const device = useStore(deviceStore);
  return (
    <section aria-labelledby="css-h" class="me-section me-css">
      <div class="me-css-top">
        <div class="me-section-head is-flush">
          <span class="me-css-title"><h2 id="css-h" class="me-section-title">{t('Custom CSS')}</h2><DeviceTag /></span>
          <p class="me-section-help">{t('For tinkerers. Applies on top of your server’s look.')}</p>
        </div>
        <span class="me-css-ignore">
          {t('Ignore the server’s CSS')}
          <Switch label={t('Ignore the server’s CSS')} checked={device.ignoreServerCss} onChange={(v) => setDevice('ignoreServerCss', v)} />
        </span>
      </div>
      <textarea
        class="me-css-box"
        aria-label={t('Custom CSS for this device')}
        rows={4}
        spellcheck={false}
        placeholder={t('/* e.g. .card { border-radius: 4px; } */')}
        value={device.customCss}
        onInput={(e) => setDevice('customCss', (e.currentTarget as HTMLTextAreaElement).value)}
      />
    </section>
  );
}
