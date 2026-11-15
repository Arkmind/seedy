import { useEffect, useState } from 'preact/hooks';
import { get } from '../../api/client';
import type { SkipMode } from '../../api/seedy';
import { DEFAULT_PLAYBACK, meStore, playbackPrefs, userStore } from '../../lib/app-state';
import { useStore } from '../../lib/store';
import { NumField, Panel, Segmented, Switch } from '../../ui/controls';
import {
  BITRATES, CUSTOM_KEYS, RESOLUTIONS, STILL_RULES, bitrateLabel, customPlayback, deviceStore, seekSteps, setDevice, type DeviceSettings,
  type MaxResolution, type StillRule, type StillWatching
} from '../../player/settings';
import { softAvailable, type SoftMode } from '../../player/soft-profile';
import { Icon } from '../../ui/icons';
import { DeviceTag, MeLayout } from './MeLayout';
import { PlaybackRow, Picker, languageOptions, tOptions, useFreshUser, useLanguages, type PickOption } from './playback-parts';
import { SaveLine } from './parts';
import { type FullUserConfig, saveCustom, savePlayback, saveUserConfig } from './prefs';
import { getLang, t, tn } from '../../lib/i18n';

/* Settings · Playback (MePlayback board). Quality and a few audio/skip settings are per device;
   the rest follow the profile, and Jellyfin's own options are saved to its UserConfiguration. */

const SKIP_OPTIONS: { value: SkipMode; label: string }[] = [
  { value: 'auto', label: 'Skip' },
  { value: 'button', label: 'Show button' },
  { value: 'off', label: 'Nothing' }
];

const CHANNELS: PickOption<DeviceSettings['audioChannels']>[] = [
  { value: 0, label: 'Auto' },
  { value: 8, label: '7.1 surround' },
  { value: 6, label: '5.1 surround' },
  { value: 2, label: 'Stereo' },
  { value: 1, label: 'Mono' }
];

/* "Ask “Are you still watching?”": the rules, their words and counts (minutes for the time rule). */
const STILL_RULE: Record<StillRule, { name: string; help: string; counts: number[] }> = {
  episodes: { name: 'After episodes in a row', help: 'Shows only', counts: [2, 3, 4] },
  minutes: { name: 'After watching for a while', help: 'Shows and movies, without a break', counts: [90, 120, 180] },
  movies: { name: 'After movies in a row', help: 'Before end-of-movie picks or the next film start', counts: [1, 2, 3] }
};

const stillCount = (rule: StillRule, n: number) =>
  rule === 'episodes' ? tn(n, '{n} episode', '{n} episodes')
    : rule === 'movies' ? tn(n, '{n} movie', '{n} movies')
      : n >= 120 && n % 60 === 0 ? t('{n} h', { n: n / 60 }) : t('{n} min', { n });

/* Tick any rules; each keeps its count while unticked (dimmed), and picking a count ticks its rule. */
function StillRules({ value }: { value: StillWatching }) {
  const save = (patch: Partial<StillWatching>) => void saveCustom({ [CUSTOM_KEYS.stillWatching]: { ...value, ...patch } });
  return (
    <div role="group" aria-label={t('Ask “Are you still watching?”')} class="me-pb-rules">
      {STILL_RULES.map((rule) => {
        const on = !value.off.includes(rule);
        const { name, help, counts } = STILL_RULE[rule];
        const choices = counts.includes(value[rule]) ? counts : [...counts, value[rule]].sort((a, b) => a - b);
        return (
          <div key={rule} class={'me-pb-rule' + (on ? ' is-on' : '')}>
            <label class="me-pb-rule-label">
              <input
                type="checkbox"
                class="me-pb-rule-input"
                checked={on}
                onChange={() => save({ off: on ? [...value.off, rule] : value.off.filter((r) => r !== rule) })}
              />
              <span class="me-pb-check" aria-hidden="true">{on && <Icon name="check" size={14} stroke={3.2} />}</span>
              <span class="me-pb-rule-text">
                <span class="me-pb-rule-name">{t(name)}</span>
                <span class="me-pb-rule-help">{t(help)}</span>
              </span>
            </label>
            <div role="radiogroup" aria-label={t(name)} class="me-pb-rule-seg">
              {choices.map((n) => (
                <button
                  key={n}
                  type="button"
                  role="radio"
                  aria-checked={n === value[rule] ? 'true' : 'false'}
                  class="me-pb-rule-opt"
                  onClick={() => save({ [rule]: n, off: value.off.filter((r) => r !== rule) })}
                >
                  {stillCount(rule, n)}
                </button>
              ))}
            </div>
          </div>
        );
      })}
    </div>
  );
}

const PAUSE_SCREEN: PickOption<number>[] = [
  { value: 0, label: 'Never' },
  { value: 5, label: 'After 5 seconds' },
  { value: 10, label: 'After 10 seconds' },
  { value: 30, label: 'After 30 seconds' },
  { value: 60, label: 'After 1 minute' },
  { value: 300, label: 'After 5 minutes' }
];

const FALLBACK_RECEIVERS: PickOption<string>[] = [
  { value: 'F007D354', label: 'Stable' },
  { value: '6F511C87', label: 'Unstable' }
];

/* Codec and streaming tweaks (the Advanced block). All of them shape the device profile sent to the server. */
const ADVANCED: { key: 'preferFmp4' | 'hevc' | 'av1' | 'hdr' | 'dts' | 'truehd' | 'mkv'; name: string; help: string }[] = [
  { key: 'preferFmp4', name: 'Prefer fMP4 when converting', help: 'Newer HLS segments; turn off if converted video stutters or won’t start' },
  { key: 'hevc', name: 'Play HEVC directly', help: 'When this browser says it can decode H.265' },
  { key: 'av1', name: 'Play AV1 directly', help: 'When this browser says it can decode AV1' },
  { key: 'hdr', name: 'Play HDR directly', help: 'Off asks the server to tone-map HDR to SDR' },
  { key: 'dts', name: 'DTS audio', help: 'Turn on if this device or your receiver decodes DTS' },
  { key: 'truehd', name: 'Dolby TrueHD audio', help: 'Turn on if this device or your receiver decodes TrueHD' },
  { key: 'mkv', name: 'Play Matroska files directly', help: 'Off has the server repackage MKV files first' }
];

/* The experimental player (player/soft-profile.ts): when this browser decodes instead of the server converting. */
const SOFT_PLAYER: PickOption<SoftMode>[] = [
  { value: 'off', label: 'Off' },
  { value: 'needed', label: 'When needed, up to 1080p' },
  { value: 'any', label: 'When needed, any size' },
  { value: 'always', label: 'Every video (for testing)' }
];

export function MePlayback() {
  const me = useStore(meStore);
  const user = useStore(userStore);
  const dev = useStore(deviceStore);
  const languages = useLanguages();
  const [receivers, setReceivers] = useState<PickOption<string>[]>(FALLBACK_RECEIVERS);
  const [advOpen, setAdvOpen] = useState(false);
  useFreshUser();

  useEffect(() => {
    // The Chromecast receivers the admin offers (any signed-in user can read the server configuration).
    get<{ CastReceiverApplications?: { Id: string; Name: string }[] }>('/System/Configuration').then(
      (c) => { if (c?.CastReceiverApplications?.length) setReceivers(c.CastReceiverApplications.map((r) => ({ value: r.Id, label: r.Name }))); },
      () => undefined
    );
  }, []);

  const pb = playbackPrefs(me.prefs);
  const custom = customPlayback(me.prefs?.custom);
  const cfg: FullUserConfig = user?.Configuration ?? {};
  const remoteLimit = (user?.Policy as { RemoteClientBitrateLimit?: number } | undefined)?.RemoteClientBitrateLimit ?? 0;

  const quality = (auto: string): PickOption<number>[] => [{ value: 0, label: auto }, ...BITRATES.map((b) => ({ value: b, label: bitrateLabel(b) }))];
  const pauseOptions = PAUSE_SCREEN.some((o) => o.value === pb.pauseScreenSeconds)
    ? tOptions(PAUSE_SCREEN)
    : [...tOptions(PAUSE_SCREEN), { value: pb.pauseScreenSeconds, label: t('After {n} seconds', { n: pb.pauseScreenSeconds }) }].sort((a, b) => a.value - b.value);
  const receiver = dev.castReceiver ?? cfg.CastReceiverId ?? receivers[0]?.value ?? '';
  const audioLang = cfg.AudioLanguagePreference ?? '';

  return (
    <MeLayout
      page="playback"
      title={t('Playback')}
      lede={t('Quality, audio, what happens between episodes, and skipping. Quality is set per device, because a phone and a TV need different things.')}
    >
      <div class="me-pb-page" style={{ display: 'contents' }}>
        <Panel title={t('Quality')} text={t('Auto picks the best your connection and screen allow.')}>
          <PlaybackRow name={t('At home')} help={t('On the same network as the server')} device>
            <Picker label={t('At home')} value={dev.qualityHome} options={quality(t('Auto · up to {rate}', { rate: '120 Mbps' }))} onChange={(v) => setDevice('qualityHome', v)} />
          </PlaybackRow>
          <PlaybackRow name={t('Away from home')} help={t('Your admin may set a lower limit')} device>
            <Picker
              label={t('Away from home')}
              value={dev.qualityAway}
              options={quality(remoteLimit > 0 ? t('Auto · up to {rate}', { rate: bitrateLabel(remoteLimit) }) : t('Auto'))}
              onChange={(v) => setDevice('qualityAway', v)}
            />
          </PlaybackRow>
          <PlaybackRow name={t('When casting')} help={t('Chromecast and Google TV')} device>
            <Picker label={t('When casting')} value={dev.qualityCast} options={quality(t('Auto'))} onChange={(v) => setDevice('qualityCast', v)} />
          </PlaybackRow>
          <PlaybackRow name={t('Largest picture when converting')} help={t('Used when the server has to transcode')} device>
            <Picker<MaxResolution>
              label={t('Largest picture when converting')}
              value={dev.maxResolution}
              options={tOptions(RESOLUTIONS)}
              onChange={(v) => setDevice('maxResolution', v)}
            />
          </PlaybackRow>
          <PlaybackRow name={t('Treat that as this screen’s limit')} help={t('Never ask for a bigger picture than this, even without converting')} device>
            <Switch label={t('Treat that as this screen’s limit')} checked={dev.limitResolution} onChange={(v) => setDevice('limitResolution', v)} />
          </PlaybackRow>
        </Panel>

        <Panel title={t('Audio')}>
          <PlaybackRow name={t('Preferred audio language')} help={t('Picked when a title has several')}>
            <Picker
              label={t('Preferred audio language')}
              value={audioLang}
              options={languageOptions(languages, { value: '', label: t('Original language') }, audioLang)}
              onChange={(v) => void saveUserConfig({ AudioLanguagePreference: v })}
            />
          </PlaybackRow>
          <PlaybackRow name={t('Always use the default track')} help={t('Even when it isn’t in your language')}>
            <Switch
              label={t('Always use the default track')}
              checked={cfg.PlayDefaultAudioTrack ?? true}
              onChange={(v) => void saveUserConfig({ PlayDefaultAudioTrack: v })}
            />
          </PlaybackRow>
          <PlaybackRow name={t('Most audio channels')} help={t('Lower this for headphones or TV speakers')} device>
            <Picker label={t('Most audio channels')} value={dev.audioChannels} options={tOptions(CHANNELS)} onChange={(v) => setDevice('audioChannels', v)} />
          </PlaybackRow>
          <PlaybackRow name={t('Constant bitrate audio only')} help={t('Some players stutter with variable bitrate')} device>
            <Switch label={t('Constant bitrate audio only')} checked={dev.cbrAudio} onChange={(v) => setDevice('cbrAudio', v)} />
          </PlaybackRow>
        </Panel>

        <Panel title={t('Between episodes')}>
          <PlaybackRow name={t('Play the next episode automatically')} help={t('From the Up next card at the credits')}>
            <Switch
              label={t('Play the next episode automatically')}
              checked={pb.autoPlayNext}
              onChange={(v) => {
                void savePlayback({ autoPlayNext: v });
                void saveUserConfig({ EnableNextEpisodeAutoPlay: v });
              }}
            />
          </PlaybackRow>
          <PlaybackRow name={t('Up next countdown')} help={t('Your admin’s default is {n} seconds', { n: me.defaults?.upNextSeconds ?? DEFAULT_PLAYBACK.upNextSeconds })}>
            <NumField
              ariaLabel={t('Up next countdown')}
              value={pb.upNextSeconds}
              min={5}
              max={60}
              suffix="s"
              onChange={(v) => void savePlayback({ upNextSeconds: v })}
            />
          </PlaybackRow>
          <PlaybackRow
            name={t('Ask “Are you still watching?”')}
            help={t('Seedy pauses and asks when any rule you tick is met. Touching the remote, the screen or the volume starts the count again.')}
          >
            <StillRules value={custom.stillWatching} />
          </PlaybackRow>
          <PlaybackRow name={t('Keep my audio choice')} help={t('Pick the same kind of track as the last episode')}>
            <Switch
              label={t('Keep my audio choice')}
              checked={cfg.RememberAudioSelections ?? true}
              onChange={(v) => void saveUserConfig({ RememberAudioSelections: v })}
            />
          </PlaybackRow>
          <PlaybackRow name={t('Keep my subtitle choice')} help={t('Pick the same kind of track as the last episode')}>
            <Switch
              label={t('Keep my subtitle choice')}
              checked={cfg.RememberSubtitleSelections ?? true}
              onChange={(v) => void saveUserConfig({ RememberSubtitleSelections: v })}
            />
          </PlaybackRow>
        </Panel>

        <Panel title={t('Skipping')} text={t('For titles where the server knows these parts.')}>
          <PlaybackRow name={t('Intros')}>
            <Segmented label={t('Intros')} value={pb.skipIntro} options={tOptions(SKIP_OPTIONS)} onChange={(v) => void savePlayback({ skipIntro: v })} />
          </PlaybackRow>
          <PlaybackRow name={t('Recaps')}>
            <Segmented label={t('Recaps')} value={pb.skipRecap} options={tOptions(SKIP_OPTIONS)} onChange={(v) => void savePlayback({ skipRecap: v })} />
          </PlaybackRow>
          <PlaybackRow name={t('Previews')}>
            <Segmented label={t('Previews')} value={custom.skipPreview} options={tOptions(SKIP_OPTIONS)} onChange={(v) => void saveCustom({ [CUSTOM_KEYS.skipPreview]: v })} />
          </PlaybackRow>
          <PlaybackRow name={t('Ads')} help={t('Mostly in recordings')}>
            <Segmented label={t('Ads')} value={custom.skipCommercial} options={tOptions(SKIP_OPTIONS)} onChange={(v) => void saveCustom({ [CUSTOM_KEYS.skipCommercial]: v })} />
          </PlaybackRow>
          <PlaybackRow name={t('Credits')} help={t('Goes to Up next or end-of-movie picks')}>
            <Segmented label={t('Credits')} value={pb.skipCredits} options={tOptions(SKIP_OPTIONS)} onChange={(v) => void savePlayback({ skipCredits: v })} />
          </PlaybackRow>
          <PlaybackRow name={t('Skip back')} help={t('Also the J key')} device>
            <NumField ariaLabel={t('Skip back')} value={seekSteps(dev, pb).back} min={5} max={60} suffix="s" onChange={(v) => setDevice('seekBack', v)} />
          </PlaybackRow>
          <PlaybackRow name={t('Skip forward')} help={t('Also the L key')} device>
            <NumField ariaLabel={t('Skip forward')} value={seekSteps(dev, pb).forward} min={5} max={120} suffix="s" onChange={(v) => setDevice('seekForward', v)} />
          </PlaybackRow>
        </Panel>

        <Panel title={t('Around a movie')}>
          <PlaybackRow name={t('Trailers before a movie')} help={t('Cinema mode: plays trailers and intros your admin set up')}>
            <Switch
              label={t('Trailers before a movie')}
              checked={custom.cinemaMode}
              onChange={(v) => void saveCustom({ [CUSTOM_KEYS.cinemaMode]: v })}
            />
          </PlaybackRow>
          <PlaybackRow name={t('Pause screen')} help={t('Shows the title and synopsis while paused')}>
            <Picker label={t('Pause screen')} value={pb.pauseScreenSeconds} options={pauseOptions} onChange={(v) => void savePlayback({ pauseScreenSeconds: v })} />
          </PlaybackRow>
          <PlaybackRow name={t('Suggestions when a movie ends')} help={t('Three similar titles over the credits')}>
            <Switch
              label={t('Suggestions when a movie ends')}
              checked={custom.moviePicks}
              onChange={(v) => void saveCustom({ [CUSTOM_KEYS.moviePicks]: v })}
            />
          </PlaybackRow>
        </Panel>

        <Panel title={t('Other players')}>
          <PlaybackRow name={t('Chromecast app')} help={t('The receiver your TV runs')} device>
            <Picker
              label={t('Chromecast app')}
              value={receiver}
              options={receivers.some((r) => r.value === receiver) || !receiver ? receivers : [...receivers, { value: receiver, label: receiver }]}
              onChange={(v) => {
                setDevice('castReceiver', v);
                // Jellyfin keeps the receiver on the profile; its own apps launch the one picked here.
                void saveUserConfig({ CastReceiverId: v });
              }}
            />
          </PlaybackRow>
          <PlaybackRow name={t('Offer an external player')} help={t('Lets you open a video in VLC or another app')} device>
            <Switch label={t('Offer an external player')} checked={dev.externalPlayer} onChange={(v) => setDevice('externalPlayer', v)} />
          </PlaybackRow>
        </Panel>

        <details class="me-pb-adv" open={advOpen} onToggle={(e) => setAdvOpen((e.currentTarget as HTMLDetailsElement).open)}>
          <summary class="me-pb-adv-sum">
            <span class="me-pb-adv-text">
              <span class="me-pb-adv-title">{t('Advanced')}<DeviceTag /></span>
              <span class="me-pb-adv-help">{t('Codec and streaming tweaks for when something won’t play. {n} settings.', { n: ADVANCED.length + 1 })}</span>
            </span>
            <span class="me-pb-adv-toggle">{advOpen ? t('Hide') : getLang() === 'en' ? 'Show' : t('Show (verb)')}</span>
          </summary>
          <div class="me-pb-adv-body">
            {ADVANCED.map((a) => (
              <PlaybackRow key={a.key} name={t(a.name)} help={t(a.help)}>
                <Switch label={t(a.name)} checked={dev[a.key]} onChange={(v) => setDevice(a.key, v)} />
              </PlaybackRow>
            ))}
            <PlaybackRow
              name={t('Experimental player')}
              help={softAvailable()
                ? t('Decodes HEVC and other formats here instead of the server converting them. Needs a fast processor')
                : t('This browser can’t run it')}
            >
              <Picker<SoftMode> label={t('Experimental player')} value={dev.softPlayer} options={tOptions(SOFT_PLAYER)} onChange={(v) => setDevice('softPlayer', v)} />
            </PlaybackRow>
          </div>
        </details>

        <SaveLine />
      </div>
    </MeLayout>
  );
}
