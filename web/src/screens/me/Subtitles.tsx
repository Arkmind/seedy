import { meStore, userStore } from '../../lib/app-state';
import { useStore } from '../../lib/store';
import { Panel, Segmented, Switch } from '../../ui/controls';
import {
  PREVIEW_PX, SIZE_TO_PREFS, SUB_BACKGROUNDS, SUB_COLORS, SUB_FONTS, cueCss, deviceStore, resolveStyle, setDevice, setSubtitleStyle,
  type BurnIn, type PictureFit, type SubEngine, type SubtitleStyle
} from '../../player/settings';
import { DeviceTag, MeLayout } from './MeLayout';
import { PlaybackRow, Picker, languageOptions, tOptions, useFreshUser, useLanguages, type PickOption } from './playback-parts';
import { SaveLine } from './parts';
import { type FullUserConfig, savePrefsPatch, saveUserConfig } from './prefs';
import { t } from '../../lib/i18n';

/* Settings · Subtitles (MeSubtitles board): when subtitles turn on (Jellyfin's subtitle mode and language),
   how they look on this device, and how they're drawn. */

type Mode = NonNullable<FullUserConfig['SubtitleMode']>;

const MODES: { value: Mode; name: string; help: string }[] = [
  { value: 'Smart', name: 'Smart', help: 'In your language when the audio is in another one' },
  { value: 'Always', name: 'Always', help: 'In your language, whatever the audio' },
  { value: 'Default', name: 'As the file says', help: 'Follow the default and forced flags in the file' },
  { value: 'OnlyForced', name: 'Forced only', help: 'Just signs and foreign-language lines' },
  { value: 'None', name: 'Never', help: 'Turn them on yourself during playback' }
];

const BURN_IN: PickOption<BurnIn>[] = [
  { value: 'auto', label: 'Auto' },
  { value: 'picture', label: 'Picture subtitles' },
  { value: 'styled', label: 'Styled and picture' },
  { value: 'all', label: 'All subtitles' }
];

const FIT: PickOption<PictureFit>[] = [
  { value: 'contain', label: 'Contain' },
  { value: 'stretch', label: 'Stretch to screen' },
  { value: 'smaller', label: 'Smaller' }
];

const ENGINES: PickOption<SubEngine>[] = [
  { value: 'auto', label: 'Auto' },
  { value: 'seedy', label: 'Seedy' },
  { value: 'browser', label: 'Browser captions' }
];

const SIZE_LABELS: Record<SubtitleStyle['size'], string> = { small: 'Small', normal: 'Normal', large: 'Large', xlarge: 'Extra large' };

/* Where the preview line sits for each position. */
const PREVIEW_POS: Record<SubtitleStyle['position'], Record<string, string>> = {
  bottom: { bottom: '6%' },
  lifted: { bottom: '14%' },
  top: { top: '10%' }
};

export function MeSubtitles() {
  const me = useStore(meStore);
  const user = useStore(userStore);
  const dev = useStore(deviceStore);
  const languages = useLanguages();
  useFreshUser();

  const cfg: FullUserConfig = user?.Configuration ?? {};
  const mode: Mode = cfg.SubtitleMode ?? 'Default';
  const lang = cfg.SubtitleLanguagePreference ?? '';
  const subs = me.prefs?.subtitles ?? { size: 'M' as const, lift: true };
  const style = resolveStyle(dev, subs);

  /* Size and position also feed the player's quick S / M / L and "Lift above controls", which follow the profile. */
  const setStyle = (patch: Partial<SubtitleStyle>) => {
    setSubtitleStyle(patch);
    const next = { ...style, ...patch };
    const size = SIZE_TO_PREFS[next.size];
    const lift = next.position === 'top' ? subs.lift : next.position === 'lifted';
    if (size !== subs.size || lift !== subs.lift) void savePrefsPatch({ subtitles: { size, lift } });
  };

  const seg = <K extends keyof SubtitleStyle>(name: string, key: K, options: { value: SubtitleStyle[K]; label: string }[]) => (
    <div class="me-pb-look-row">
      <span class="me-pb-look-name">{t(name)}</span>
      <Segmented<SubtitleStyle[K] & string> label={t(name)} value={style[key] as SubtitleStyle[K] & string} options={tOptions(options) as { value: SubtitleStyle[K] & string; label: string }[]} onChange={(v) => setStyle({ [key]: v } as Partial<SubtitleStyle>)} />
    </div>
  );

  const swatches = (name: string, list: { value: string; label: string; css: string }[], value: string, onPick: (v: string) => void) => (
    <div class="me-pb-look-row">
      <span class="me-pb-look-name">{t(name)}</span>
      <div role="radiogroup" aria-label={t(name)} class="me-pb-swatches">
        {list.map((w) => (
          <button
            key={w.value}
            type="button"
            role="radio"
            class="me-pb-swatch"
            aria-checked={w.value === value ? 'true' : 'false'}
            aria-label={t(w.label)}
            style={{ background: w.css }}
            onClick={() => onPick(w.value)}
            onKeyDown={(e) => {
              // Arrow keys move through a radio group.
              if (!['ArrowRight', 'ArrowDown', 'ArrowLeft', 'ArrowUp'].includes(e.key)) return;
              e.preventDefault();
              const i = list.findIndex((x) => x.value === value);
              const n = list[(i + (e.key === 'ArrowRight' || e.key === 'ArrowDown' ? 1 : list.length - 1)) % list.length];
              onPick(n.value);
              const btns = (e.currentTarget as HTMLElement).parentElement?.querySelectorAll<HTMLButtonElement>('button');
              btns?.[list.indexOf(n)]?.focus();
            }}
            tabIndex={w.value === value ? 0 : -1}
          />
        ))}
      </div>
    </div>
  );

  /* The preview's text scales with the preview (sizes are for the board's 772 px wide preview). */
  const { fontSize: _px, ...line } = cueCss(style, PREVIEW_PX[style.size]);
  const lineStyle = { ...line, '--pv-px': String(PREVIEW_PX[style.size]) } as Record<string, string>;

  return (
    <MeLayout page="subtitles" title={t('Subtitles')} lede={t('When subtitles turn on by themselves, and how they look on this device.')}>
      <div class="me-pb-page" style={{ display: 'contents' }}>
        <section aria-labelledby="me-pb-when-h" class="me-pb-when">
          <div class="me-pb-when-head">
            <h2 id="me-pb-when-h">{t('When to show them')}</h2>
            <Picker
              label={t('Preferred subtitle language')}
              value={lang}
              options={languageOptions(languages, { value: '', label: t('Any') }, lang)}
              display={(text) => <span><span class="me-pb-pick-pre">{t('Language')}</span> {text}</span>}
              onChange={(v) => void saveUserConfig({ SubtitleLanguagePreference: v })}
            />
          </div>
          <div role="radiogroup" aria-label={t('When to show subtitles')} class="me-pb-modes">
            {MODES.map((m) => (
              <label key={m.value} class={'me-pb-mode' + (m.value === mode ? ' is-on' : '')}>
                <input
                  type="radio"
                  name="me-pb-submode"
                  checked={m.value === mode}
                  onChange={() => void saveUserConfig({ SubtitleMode: m.value })}
                />
                <span class="me-pb-mode-body">
                  <span class="me-pb-mode-name">{t(m.name)}</span>
                  <span class="me-pb-mode-help">{t(m.help)}</span>
                </span>
              </label>
            ))}
          </div>
        </section>

        <section aria-labelledby="me-pb-look-h" class="me-pb-look">
          <div class="me-pb-look-head">
            <h2 id="me-pb-look-h">{t('How they look')}</h2>
            <DeviceTag />
          </div>

          <div class="me-pb-preview" role="img" aria-label={t('Preview: {size} {weight} subtitles', { size: t(SIZE_LABELS[style.size] ?? style.size).toLowerCase(), weight: t(style.weight === 'bold' ? 'Bold' : 'Normal').toLowerCase() })}>
            <svg viewBox="0 0 320 140" preserveAspectRatio="xMidYMid slice" aria-hidden="true">
              <rect width="320" height="140" fill="#0d141c" />
              <circle cx="70" cy="34" r="12" fill="#dfe7ef" />
              <path d="M0 90 L60 60 L110 82 L170 46 L230 80 L320 58 V140 H0Z" fill="#1d2a38" />
              <path d="M0 118 L90 100 L170 114 L250 96 L320 110 V140 H0Z" fill="#0a0f15" />
            </svg>
            <span class="me-pb-preview-line" style={PREVIEW_POS[style.position]}>
              <span style={lineStyle}>{t("We won't make the pass before dark.")}</span>
            </span>
            <span class="me-pb-preview-tag">{t('Preview')}</span>
          </div>

          {seg('Size', 'size', Object.entries(SIZE_LABELS).map(([value, label]) => ({ value: value as SubtitleStyle['size'], label })))}
          {seg('Weight', 'weight', [{ value: 'normal', label: 'Normal' }, { value: 'bold', label: 'Bold' }])}
          <div class="me-pb-look-row">
            <span class="me-pb-look-name">{t('Font')}</span>
            <Picker label={t('Font')} minWidth={180} value={style.font} options={tOptions(SUB_FONTS)} onChange={(v) => setStyle({ font: v })} />
          </div>
          {swatches('Text colour', SUB_COLORS, style.color, (v) => setStyle({ color: v as SubtitleStyle['color'] }))}
          {swatches('Background', SUB_BACKGROUNDS, style.background, (v) => setStyle({ background: v as SubtitleStyle['background'] }))}
          {seg('Outline', 'outline', [
            { value: 'none', label: 'None' }, { value: 'shadow', label: 'Shadow' }, { value: 'outline', label: 'Outline' }, { value: 'raised', label: 'Raised' }
          ])}
          {seg('Position', 'position', [
            { value: 'bottom', label: 'Bottom' }, { value: 'lifted', label: 'Lifted above controls' }, { value: 'top', label: 'Top' }
          ])}
          <span class="me-pb-note">
            {t('Styled subtitles (ASS and SSA) and picture subtitles (PGS, VobSub) keep their own look. These settings also apply when you cast from this device.')}
          </span>
        </section>

        <Panel
          title={t('Rendering')}
          text={t('How subtitles get drawn. Burning them into the picture costs the server a lot, so leave these alone unless subtitles go out of sync or don’t show.')}
        >
          <PlaybackRow name={t('Burn subtitles in')} help={t('Auto burns in picture subtitles only when needed')} device>
            <Picker label={t('Burn subtitles in')} value={dev.subtitleBurnIn} options={tOptions(BURN_IN)} onChange={(v) => setDevice('subtitleBurnIn', v)} />
          </PlaybackRow>
          <PlaybackRow name={t('Show picture subtitles here')} help={t('Draw PGS and VobSub in Seedy instead of on the server')} device>
            <Switch label={t('Show picture subtitles here')} checked={dev.pictureSubtitles} onChange={(v) => setDevice('pictureSubtitles', v)} />
          </PlaybackRow>
          <PlaybackRow name={t('Picture subtitle fit')} help={t('How PGS and VobSub are scaled')} device>
            <Picker label={t('Picture subtitle fit')} value={dev.pictureFit} options={tOptions(FIT)} onChange={(v) => setDevice('pictureFit', v)} />
          </PlaybackRow>
          <PlaybackRow name={t('Always burn in while converting')} help={t('Keeps them in sync, but the server works harder')} device>
            <Switch label={t('Always burn in while converting')} checked={dev.burnInWhenConverting} onChange={(v) => setDevice('burnInWhenConverting', v)} />
          </PlaybackRow>
          <PlaybackRow name={t('Styling engine')} help={t('Auto picks what works best on this device')} device>
            <Picker label={t('Styling engine')} value={dev.subtitleEngine} options={tOptions(ENGINES)} onChange={(v) => setDevice('subtitleEngine', v)} />
          </PlaybackRow>
        </Panel>

        <SaveLine />
      </div>
    </MeLayout>
  );
}
