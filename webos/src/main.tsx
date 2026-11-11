import { render } from 'preact';
import './styles.css';
import { App } from './ui/App';
import { installDebug } from './ui/debug';
import { installIdle } from './ui/idle';
import { tellShellFirstScreen } from './ui/shell';
import { installWatch } from './ui/watch';

// polyfills.js has run before this script (the shell, shell/boot.js, loads it first): what Chromium 68 lacks is there.
// The watch goes on before the first render, so an error in it is caught too.
installWatch();
// Before the remote's own listeners (ui/App.tsx): a key that gives the screen back does nothing else.
installIdle();
installDebug();
render(<App />, document.getElementById('app')!);
// The first screen is drawn by the next task (a render that threw is replaced by ui/Guard.tsx's
// crashed screen only then): the shell learns whether it is a working one (shell/boot.js).
setTimeout(tellShellFirstScreen, 0);
