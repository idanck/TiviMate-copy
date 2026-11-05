/* The guide's ruler (GuideScreen.tsx): where its labels may go. */

/** Half the now pill's width, and where a half-hour label starts and ends after its tick. */
const NOW_PILL_HALF = 26;
const TICK_LABEL_FROM = 8;
const TICK_LABEL_TO = 44;

/**
 * Whether a half-hour label `d` pixels after the now pill's middle (before it when negative) would
 * run under the pill: the label covers d+8 to d+44, the pill −26 to 26.
 */
export function underNowPill(d: number): boolean {
  return d + TICK_LABEL_FROM < NOW_PILL_HALF && d + TICK_LABEL_TO > -NOW_PILL_HALF;
}
