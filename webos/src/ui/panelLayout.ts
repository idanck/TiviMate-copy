/**
 * Where Sport and News mode's panels go (ADR-053), in pixels: a port of the Android app's
 * `ui/tv/SportLayout.kt`, kept free of the DOM so it can be tested.
 *
 * Four panels are a fixed two-by-two grid whose lines move toward the panels that are not in
 * focus: the one in focus gets [FOCUS_SHARE] of the width and of the height, so it is the largest
 * and keeps the screen's own shape. Two stand side by side, the one in focus taking
 * [FOCUS_SHARE] of the width. Nothing overlaps and no panel changes place, so an arrow always
 * means the same neighbour. Expanded, the panel in focus fills the screen and the others measure
 * nothing: their players keep running, so going back to the grid shows them at once.
 *
 * `x` is measured from the start edge — the right, in Hebrew — so the first panel is at the top right.
 */

export const SLOTS = 4;
export const PAIR = 2;
export const FOCUS_SHARE = 0.58;

export interface Cell {
  x: number;
  y: number;
  width: number;
  height: number;
}

/** [count] as the modes understand it: two, or else four. */
export const panelCount = (count: number): 2 | 4 => (count <= PAIR ? PAIR : SLOTS);

export function cells(width: number, height: number, focused: number, expanded: boolean, gap: number, count: number = SLOTS): Cell[] {
  const n = panelCount(count);
  const target = Math.min(Math.max(focused, 0), n - 1);
  if (expanded) {
    return Array.from({ length: n }, (_, i) => (i === target ? { x: 0, y: 0, width, height } : { x: 0, y: 0, width: 0, height: 0 }));
  }
  if (n === SLOTS) {
    const columns = spans(width, gap, 2, target % 2);
    const rows = spans(height, gap, 2, Math.floor(target / 2));
    return Array.from({ length: n }, (_, slot) => {
      const [x, w] = columns[slot % 2];
      const [y, h] = rows[Math.floor(slot / 2)];
      return { x, y, width: w, height: h };
    });
  }
  return spans(width, gap, n, target).map(([x, w]) => ({ x, y: 0, width: w, height }));
}

/**
 * [n] spans across [length] with [gap] between them, as [start, size]: the one at [big] takes
 * [FOCUS_SHARE] of the length and the rest split what is left, the last taking any remainder.
 */
function spans(length: number, gap: number, n: number, big: number): Array<[number, number]> {
  const usable = Math.max(0, length - gap * (n - 1));
  const large = Math.round(usable * FOCUS_SHARE);
  const small = Math.floor((usable - large) / (n - 1));
  const sizes = Array.from({ length: n }, (_, i) => (i === big ? large : small));
  sizes[n - 1] += usable - sizes.reduce((a, b) => a + b, 0);
  let start = 0;
  return sizes.map((size) => {
    const span: [number, number] = [start, size];
    start += size + gap;
    return span;
  });
}

/**
 * The panels that stay when the grid goes down to [keep] panels, in their new order: the one in
 * focus first, then the next ones with something in them, counting on from it and round, and
 * empty ones only if there are not enough of those (`SportSession.keptPanels`).
 */
export function keptPanels(filled: boolean[], focused: number, keep: number): number[] {
  if (filled.length === 0 || keep <= 0) return [];
  const from = Math.min(Math.max(focused, 0), filled.length - 1);
  const round = Array.from({ length: filled.length }, (_, i) => (from + i) % filled.length);
  const kept = [from, ...round.slice(1).filter((i) => filled[i])];
  for (const i of round) if (kept.length < keep && !kept.includes(i)) kept.push(i);
  return kept.slice(0, keep);
}
