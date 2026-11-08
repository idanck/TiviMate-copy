/**
 * When the app takes the screen by itself (ui/idle.ts, option B of ADR-057's vanishing
 * interface). Pure, for the tests.
 *
 * 25 minutes is the owner's figure (2026-11-08): under the 30 minutes LG gives the interface with
 * `preferredType: 2` (appinfo.json, option A), so full screen comes before the dimming does.
 */
export const IDLE_MINUTES = 25;

export function shouldTakeOver(at: number, lastActivityAt: number, limitMs: number, tvPlaying: boolean, fullscreen: boolean): boolean {
  return tvPlaying && !fullscreen && at - lastActivityAt >= limitMs;
}
