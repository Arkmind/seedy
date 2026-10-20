import { meStore } from '../../lib/app-state';
import { t } from '../../lib/i18n';
import { useStore } from '../../lib/store';
import { Switch } from '../../ui/controls';
import { MeLayout } from './MeLayout';
import { deviceStore as playerDeviceStore, setDevice as setPlayerDevice } from '../../player/settings';
import { deviceStore, setDevice } from './prefs';
import { ShortcutGrid } from './shortcuts';
import { MeRow, MeSection, SaveLine } from './parts';
import './parts.css';

/* Settings · Controls (MeControls board). */

export function MeControls() {
  const me = useStore(meStore);
  const device = useStore(deviceStore);
  // "This device", as the board marks it: a TV remote's Back shouldn't change what Escape does on a laptop.
  const backCloses = useStore(playerDeviceStore).backClosesPlayer;
  const pb = me.prefs?.playback;
  return (
    <MeLayout page="controls" title={t('Controls')} lede={t('Keyboard, remote and gamepad. Seek steps live in Playback.')}>
      <section aria-labelledby="keys-h" class="me-section me-keys-panel">
        <h2 id="keys-h" class="me-section-title">{t('Keyboard shortcuts in the player')}</h2>
        <ShortcutGrid back={pb?.seekBackSeconds} forward={pb?.seekForwardSeconds} />
        <span class="me-foot">{t('Press ? anywhere in Seedy to see these.')}</span>
      </section>

      <MeSection
        head={<h2 id="remote-h" class="me-section-title me-remote-title">{t('Remote and gamepad')}</h2>}
        id="remote"
      >
        <MeRow name={t('Gamepad')} help={t('Move and select with a connected controller. Uses the TV layout')} device narrow>
          <Switch label={t('Gamepad')} checked={device.gamepad} onChange={(v) => setDevice('gamepad', v)} />
        </MeRow>
        <MeRow name={t('Smooth scrolling')} help={t('Glide between rows instead of jumping')} device narrow>
          <Switch label={t('Smooth scrolling')} checked={device.smoothScrolling} onChange={(v) => setDevice('smoothScrolling', v)} />
        </MeRow>
        <MeRow name={t('Back button closes the player')} help={t('On remotes, Back stops playback instead of only hiding controls')} device narrow>
          <Switch label={t('Back button closes the player')} checked={backCloses} onChange={(v) => setPlayerDevice('backClosesPlayer', v)} />
        </MeRow>
      </MeSection>

      <SaveLine />
    </MeLayout>
  );
}
