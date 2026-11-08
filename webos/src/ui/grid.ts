import type { Programme } from '../data/model';

/**
 * The guide grid's arithmetic, apart from Preact so it can be tested (test/grid.test.ts).
 *
 * The grid keeps its own two offsets, as the lists keep theirs (ui/list.ts, ADR-057): the rows
 * move with `translateY` and the time axis with `translateX`, in boxes that never scroll. Nothing
 * here reads `scrollLeft`, so the right-to-left `scrollLeft` model Chromium 68 uses (the old one:
 * it starts at the maximum and counts down, the spike found) never comes into it.
 *
 * Time runs right to left, as the Hebrew television guide does: `x` is the distance, in pixels,
 * from the day's start at the channel column's edge, growing leftwards. A cell is placed with
 * `right: x`; the whole strip is moved `offsetX` pixels to the right to show later hours.
 */

/** Sizes in the computer version's pixels (ui/common.tsx `rem`: ÷ 16 rem, so × 1.5 on the television). */
export const PX_PER_MIN = 4;
export const CH_COL = 220;
export const ROW_H = 60;
export const RULER_H = 34;
/** A day, and the first two hours of the next for what runs past midnight. */
export const HOURS = 26;
export const DAY_MS = 24 * 3600_000;
export const MIN_MS = 60_000;

/** A programme as the grid draws it: its times moved by the channel's offset, clamped to the day. */
export interface Cell {
  programme: Programme;
  /** Start and end in wall-clock time (the channel's offset applied). */
  start: number;
  end: number;
  /** Where it is drawn: from the day's start, in pixels, and how wide. */
  x: number;
  w: number;
}

export const xOf = (t: number, day: number, pxPerMin: number) => ((t - day) / MIN_MS) * pxPerMin;
export const strip = (pxPerMin: number) => HOURS * 60 * pxPerMin;

/** The cells of one channel on one day, in time order. */
export function cellsOf(list: Programme[] | undefined, shift: number, day: number, pxPerMin: number): Cell[] {
  if (!list || list.length === 0) return [];
  const dayEnd = day + HOURS * 3600_000;
  const out: Cell[] = [];
  for (const p of list) {
    const start = p.start + shift;
    const end = p.end + shift;
    if (end <= day || start >= dayEnd) continue;
    const x = xOf(Math.max(start, day), day, pxPerMin);
    const w = xOf(Math.min(end, dayEnd), day, pxPerMin) - x;
    if (w <= 0) continue;
    out.push({ programme: p, start, end, x, w });
  }
  return out;
}

/** The cell on at `t`, else the first one after it, else the last; -1 with no cells at all. */
export function cellAt(cells: Cell[], t: number): number {
  if (cells.length === 0) return -1;
  for (let i = 0; i < cells.length; i++) {
    if (cells[i].end > t) return i;
  }
  return cells.length - 1;
}

export function clampX(x: number, pxPerMin: number, viewW: number): number {
  const max = Math.max(0, strip(pxPerMin) - viewW);
  return Math.round(Math.min(max, Math.max(0, x)));
}

/**
 * How far the strip must be moved so that the cell from `x` to `x + w` shows: its start always,
 * and its end too when the cell fits the view.
 */
export function offsetXToShow(x: number, w: number, offsetX: number, pxPerMin: number, viewW: number): number {
  let next = offsetX;
  if (x < next) next = x;
  else if (x + w > next + viewW) next = Math.min(x, x + w - viewW);
  return clampX(next, pxPerMin, viewW);
}

/** The time window a view at `offsetX` shows, with an hour to spare each side, for drawing. */
export function timeWindow(offsetX: number, viewW: number, day: number, pxPerMin: number): [number, number] {
  const from = day + (offsetX / pxPerMin) * MIN_MS - 3600_000;
  const to = day + ((offsetX + viewW) / pxPerMin) * MIN_MS + 3600_000;
  return [from, to];
}

/** The half hours inside [from, to] on `day`, for the ruler. */
export function ticks(day: number, from: number, to: number): number[] {
  const out: number[] = [];
  for (let i = 0; i < HOURS * 2; i++) {
    const t = day + i * 30 * MIN_MS;
    if (t >= from && t <= to) out.push(t);
  }
  return out;
}
