/// <reference types="vite/client" />

/** Stamped in by vite.config.ts from appinfo/appinfo.json and git. */
declare const __APP_VERSION__: string;
declare const __APP_SHA__: string;
declare const __APP_BUILT__: string;

/** The guide worker's own script, polyfills first (vite.config.ts `epgWorker`). */
declare module 'virtual:epg-worker' {
  const source: string;
  export default source;
}
