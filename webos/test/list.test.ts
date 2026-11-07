import { describe, expect, it } from 'vitest';
import { clampOffset, inView, offsetToCentre, offsetToShow, rowsToDraw } from '../src/ui/list';

// A list of 100 rows of 102 px in a view of 816 px (eight rows), as on the television.
const ROW = 102;
const VIEW = 816;
const N = 100;

describe('the virtual list keeps its own offset (ui/list.ts)', () => {
  it('does not move while the row stays in view with a row to spare', () => {
    expect(offsetToShow(3, 0, ROW, VIEW, N)).toBe(0);
  });

  it('moves down just enough to show the next row with one to spare', () => {
    // Row 7 is the last whole row in view at 0; keeping one below it needs row 8 in view.
    expect(offsetToShow(7, 0, ROW, VIEW, N)).toBe((8 + 1) * ROW - VIEW);
    expect(inView(7, offsetToShow(7, 0, ROW, VIEW, N), ROW, VIEW)).toBe(true);
    expect(inView(8, offsetToShow(7, 0, ROW, VIEW, N), ROW, VIEW)).toBe(true);
  });

  it('walking down row by row always keeps the focused row in view', () => {
    let offset = 0;
    for (let i = 0; i < N; i++) {
      offset = offsetToShow(i, offset, ROW, VIEW, N);
      expect(inView(i, offset, ROW, VIEW)).toBe(true);
      const [first, last] = rowsToDraw(offset, ROW, VIEW, N);
      expect(i).toBeGreaterThanOrEqual(first);
      expect(i).toBeLessThan(last);
    }
    expect(offset).toBe(N * ROW - VIEW);
  });

  it('walking back up keeps it in view too, and ends at the top', () => {
    let offset = N * ROW - VIEW;
    for (let i = N - 1; i >= 0; i--) {
      offset = offsetToShow(i, offset, ROW, VIEW, N);
      expect(inView(i, offset, ROW, VIEW)).toBe(true);
    }
    expect(offset).toBe(0);
  });

  it('centres a row reached from elsewhere, within the list’s ends', () => {
    expect(offsetToCentre(50, ROW, VIEW, N)).toBe(50 * ROW - VIEW / 2 + ROW / 2);
    expect(offsetToCentre(0, ROW, VIEW, N)).toBe(0);
    expect(offsetToCentre(99, ROW, VIEW, N)).toBe(N * ROW - VIEW);
  });

  it('never goes past either end, and a short list does not move at all', () => {
    expect(clampOffset(-50, ROW, VIEW, N)).toBe(0);
    expect(clampOffset(1e9, ROW, VIEW, N)).toBe(N * ROW - VIEW);
    expect(offsetToShow(2, 0, ROW, VIEW, 3)).toBe(0);
  });

  it('draws the rows in view and a few each side', () => {
    expect(rowsToDraw(0, ROW, VIEW, N)).toEqual([0, 11]);
    expect(rowsToDraw(50 * ROW, ROW, VIEW, N)).toEqual([47, 61]);
    expect(rowsToDraw(0, ROW, VIEW, 0)).toEqual([0, 0]);
  });
});
