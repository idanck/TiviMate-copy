import { render } from 'preact';
import './styles.css';
import { App } from './ui/App';
import { installDebug } from './ui/debug';
import { installIdle } from './ui/idle';
import { installWatch } from './ui/watch';

// public/polyfills.js has run before this script (index.html): what Chromium 68 lacks is there.
// The watch goes on before the first render, so an error in it is caught too.
installWatch();
// Before the remote's own listeners (ui/App.tsx): a key that gives the screen back does nothing else.
installIdle();
installDebug();
render(<App />, document.getElementById('app')!);
