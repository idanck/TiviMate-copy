/**
 * The app is laid out at 1280×720 and drawn at the television's own resolution (ADR-055).
 *
 * `appinfo.json` asks webOS for 1920×1080, the most a web app's layer is given even on a 4K panel,
 * so text and icons are drawn at the panel's pixels rather than drawn at 720p and enlarged by the
 * television. The stylesheet stays in the 1280×720 units it was written in; `#app` is that frame,
 * scaled to fill the window by a transform. A transform, not `zoom`: what the DOM reports about a
 * transformed element is the same in every Chromium a television carries and in a desktop one,
 * where `zoom` changed meaning in Chromium 128.
 */

export const BASE_WIDTH = 1280;
export const BASE_HEIGHT = 720;

let scale = 1;

/** How many window pixels one layout pixel is: 1.5 on a television, 1 in a 1280×720 window. */
export function uiScale(): number {
  return scale;
}

/** Fits the 1280×720 frame to the window; called at start and on every resize. */
export function fitToWindow(): void {
  const fit = Math.min(window.innerWidth / BASE_WIDTH, window.innerHeight / BASE_HEIGHT);
  scale = fit > 0 && Number.isFinite(fit) ? fit : 1;
  document.documentElement.style.setProperty('--ui-scale', String(scale));
}
