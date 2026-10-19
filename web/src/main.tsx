import { render } from 'preact';
import './styles.css';
import { App } from './ui/App';
import { installPrompt } from './ui/SettingsScreen';

// The browser offers installation once; keep the offer for the button in Settings.
window.addEventListener('beforeinstallprompt', (e) => {
  e.preventDefault();
  const event = e as Event & { prompt: () => Promise<void> };
  installPrompt.value = { prompt: () => event.prompt() };
});

if ('serviceWorker' in navigator && import.meta.env.PROD) {
  window.addEventListener('load', () => void navigator.serviceWorker.register('/sw.js').catch(() => {}));
}

render(<App />, document.getElementById('app')!);
