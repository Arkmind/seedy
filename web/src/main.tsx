import { render } from 'preact';
import '@fontsource-variable/noto-sans/wght.css';
// Logs, keys and timecodes use the boards' Noto Sans Mono (weight 500).
import '@fontsource/noto-sans-mono/latin-500.css';
import './ui/tokens.css';
import './ui/base.css';
import './ui/components.css';
import './ui/cards.css';
import './ui/layout.css';
import './ui/settings.css';
import './player/player.css';
import { App } from './app';
import { boot } from './lib/app-state';
import { initI18n } from './lib/i18n';
import { startRemoteControl } from './lib/remote';
import { startPersonalEffects } from './screens/me/effects';

// The language's dictionary loads first (French only; English is the source text), then personal look and controls
// (accent, layout, screensaver, gamepad…) apply before the first paint.
initI18n().finally(() => {
  startPersonalEffects();
  render(<App />, document.getElementById('app')!);
  boot().then(() => startRemoteControl());
});
