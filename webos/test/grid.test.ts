import { describe, expect, it } from 'vitest';
import type { Programme } from '../src/data/model';
import { cellAt, cellsOf, clampX, HOURS, offsetXToShow, strip, ticks, timeWindow, xOf } from '../src/ui/grid';

// A day at 6 px a minute (the television: 4 px of the computer version × 1.5), a view of 1518 px.
const PX = 6;
const VIEW = 1518;
const DAY = new Date(2026, 10, 8).getTime();
const H = 3600_000;
const prog = (start: number, end: number, title = 't'): Programme => ({ start, end, title });

describe('the guide grid keeps its own offsets (ui/grid.ts)', () => {
  it('places a time by its distance from the day’s start, growing with time', () => {
    expect(xOf(DAY, DAY, PX)).toBe(0);
    expect(xOf(DAY + H, DAY, PX)).toBe(360);
    expect(strip(PX)).toBe(HOURS * 360);
  });

  it('clamps the strip between the day’s start and its end', () => {
    expect(clampX(-10, PX, VIEW)).toBe(0);
    expect(clampX(1e9, PX, VIEW)).toBe(strip(PX) - VIEW);
    expect(clampX(500, PX, 1e9)).toBe(0);
  });

  it('moves to show a cell’s start, and its end when it fits', () => {
    // A cell well to the left of the view (later than what shows): bring its end in, start still shown.
    expect(offsetXToShow(2000, 300, 0, PX, VIEW)).toBe(2000 + 300 - VIEW);
    // A cell before the view (earlier): its start to the edge.
    expect(offsetXToShow(100, 300, 1000, PX, VIEW)).toBe(100);
    // A cell already in view: nothing moves.
    expect(offsetXToShow(1100, 300, 1000, PX, VIEW)).toBe(1000);
    // A cell wider than the view: its start shows, its end does not.
    expect(offsetXToShow(2000, 3000, 0, PX, VIEW)).toBe(2000);
  });

  it('cuts a channel’s programmes to the day, with the channel’s offset applied', () => {
    const list = [prog(DAY - 2 * H, DAY - H), prog(DAY - H, DAY + H), prog(DAY + H, DAY + 2 * H), prog(DAY + 30 * H, DAY + 31 * H)];
    const cells = cellsOf(list, 0, DAY, PX);
    expect(cells.map((c) => c.programme.start)).toEqual([DAY - H, DAY + H]);
    // The one that began yesterday is drawn from the day's start.
    expect(cells[0].x).toBe(0);
    expect(cells[0].w).toBe(360);
    expect(cells[0].start).toBe(DAY - H);
    // A shift of an hour moves every cell an hour later.
    const shifted = cellsOf(list, H, DAY, PX);
    expect(shifted[0].x).toBe(0);
    expect(shifted[0].w).toBe(720);
    expect(shifted[1].x).toBe(720);
  });

  it('finds the cell on at a time, or the next one, or the last', () => {
    const cells = cellsOf([prog(DAY, DAY + H), prog(DAY + 2 * H, DAY + 3 * H)], 0, DAY, PX);
    expect(cellAt(cells, DAY + H / 2)).toBe(0);
    expect(cellAt(cells, DAY + 1.5 * H)).toBe(1);
    expect(cellAt(cells, DAY + 10 * H)).toBe(1);
    expect(cellAt([], DAY)).toBe(-1);
  });

  it('draws an hour to spare each side of the view', () => {
    const [from, to] = timeWindow(360, VIEW, DAY, PX);
    expect(from).toBe(DAY);
    expect(to).toBe(DAY + 2 * H + (VIEW / PX) * 60_000);
    expect(ticks(DAY, DAY + H, DAY + 2 * H)).toEqual([DAY + H, DAY + 1.5 * H, DAY + 2 * H]);
  });
});
