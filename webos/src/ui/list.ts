/**
 * The virtual list's arithmetic, apart from Preact so it can be tested (test/list.test.ts).
 *
 * The list keeps its own offset (ADR-057): which rows are drawn, and where, follow from the row in
 * focus, never from the browser's `scrollTop` or a `scroll` event. The first LG version waited for
 * `scroll` events that webOS 5 did not send under its scaling, and drew no more rows; and the spike
 * saw the browser drop a list's scroll position on a re-layout. The rows move with `translateY` in
 * a box that does not scroll at all.
 */

/** How far the list must be moved so that row `index` is in view with a row to spare each side. */
export function offsetToShow(index: number, offset: number, rowPx: number, viewPx: number, count: number): number {
  const top = index * rowPx;
  const spare = viewPx >= rowPx * 3 ? rowPx : 0;
  let next = offset;
  if (top - spare < next) next = top - spare;
  if (top + rowPx + spare > next + viewPx) next = top + rowPx + spare - viewPx;
  return clampOffset(next, rowPx, viewPx, count);
}

/** The offset that puts row `index` in the middle, for a jump from elsewhere (a zap, a new filter). */
export function offsetToCentre(index: number, rowPx: number, viewPx: number, count: number): number {
  return clampOffset(index * rowPx - viewPx / 2 + rowPx / 2, rowPx, viewPx, count);
}

export function clampOffset(offset: number, rowPx: number, viewPx: number, count: number): number {
  const max = Math.max(0, count * rowPx - viewPx);
  return Math.round(Math.min(max, Math.max(0, offset)));
}

/** The rows to draw for an offset: those in view and `spare` more each side, as [first, last). */
export function rowsToDraw(offset: number, rowPx: number, viewPx: number, count: number, spare = 3): [number, number] {
  if (count === 0 || rowPx <= 0) return [0, 0];
  const first = Math.max(0, Math.floor(offset / rowPx) - spare);
  const last = Math.min(count, Math.ceil((offset + viewPx) / rowPx) + spare);
  return [first, last];
}

/** Whether row `index` lies wholly inside the view at `offset`. */
export function inView(index: number, offset: number, rowPx: number, viewPx: number): boolean {
  const top = index * rowPx;
  return top >= offset && top + rowPx <= offset + viewPx;
}
