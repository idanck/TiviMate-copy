import { render } from 'preact';
import './styles.css';
import { App } from './ui/App';
import { fitToWindow } from './ui/scale';

/**
 * Chromium 79 (webOS 6) lays out flex boxes without `gap`. Where it is missing, the stylesheet's
 * margin fallback is switched on (postcss.config.mjs); newer televisions keep the real gap.
 */
function flexGapSupported(): boolean {
  const box = document.createElement('div');
  box.style.cssText = 'display:flex;flex-direction:column;row-gap:1px;position:absolute;visibility:hidden';
  box.appendChild(document.createElement('div'));
  box.appendChild(document.createElement('div'));
  document.body.appendChild(box);
  const supported = box.scrollHeight === 1;
  box.remove();
  return supported;
}

if (!flexGapSupported()) document.documentElement.classList.add('no-flex-gap');

// Before the first render, and before any screen listens for resizes, so they read the new scale.
fitToWindow();
window.addEventListener('resize', fitToWindow);

render(<App />, document.getElementById('app')!);
