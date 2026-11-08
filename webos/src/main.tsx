import { render } from 'preact';
import './styles.css';
import { App } from './ui/App';
import { installDebug } from './ui/debug';
import { installWatch } from './ui/watch';

// public/polyfills.js has run before this script (index.html): what Chromium 68 lacks is there.
// The watch goes on before the first render, so an error in it is caught too.
installWatch();
installDebug();
render(<App />, document.getElementById('app')!);
