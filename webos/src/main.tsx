import { render } from 'preact';
import './styles.css';
import { App } from './ui/App';
import { installDebug } from './ui/debug';

// public/polyfills.js has run before this script (index.html): what Chromium 68 lacks is there.
installDebug();
render(<App />, document.getElementById('app')!);
