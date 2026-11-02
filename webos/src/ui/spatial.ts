/**
 * Moving focus with the remote's arrows (ADR-052). The computer version is driven by a mouse and
 * Tab; a television has four arrows and OK, and LG requires every control to be reachable with
 * them. Rather than wiring each screen, the arrow goes to the nearest focusable control in its
 * direction, measured on the screen as it is drawn: what a TV user expects, and it holds for any
 * screen the app gains later.
 */

export type Direction = 'up' | 'down' | 'left' | 'right';

export interface Box {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

/** How far `to` lies from `from` in `dir`, or Infinity when it is not in that direction at all. */
export function distance(from: Box, to: Box, dir: Direction): number {
  const fx = (from.left + from.right) / 2;
  const fy = (from.top + from.bottom) / 2;
  const tx = (to.left + to.right) / 2;
  const ty = (to.top + to.bottom) / 2;
  // Along the arrow: the gap between facing edges (0 when they touch or overlap a little).
  // Across it: how far the centres are out of line, which weighs more, so the arrow goes
  // straight on rather than to a closer control off to the side.
  let along: number;
  let across: number;
  let overlap: boolean;
  switch (dir) {
    case 'down':
      if (ty <= fy || to.top < from.bottom - (from.bottom - from.top) / 2) return Infinity;
      along = Math.max(0, to.top - from.bottom);
      across = Math.abs(tx - fx);
      overlap = to.left < from.right && to.right > from.left;
      break;
    case 'up':
      if (ty >= fy || to.bottom > from.top + (from.bottom - from.top) / 2) return Infinity;
      along = Math.max(0, from.top - to.bottom);
      across = Math.abs(tx - fx);
      overlap = to.left < from.right && to.right > from.left;
      break;
    case 'right':
      if (tx <= fx || to.left < from.right - (from.right - from.left) / 2) return Infinity;
      along = Math.max(0, to.left - from.right);
      across = Math.abs(ty - fy);
      overlap = to.top < from.bottom && to.bottom > from.top;
      break;
    case 'left':
      if (tx >= fx || to.right > from.left + (from.right - from.left) / 2) return Infinity;
      along = Math.max(0, from.left - to.right);
      across = Math.abs(ty - fy);
      overlap = to.top < from.bottom && to.bottom > from.top;
      break;
  }
  return along + across * (overlap ? 0.5 : 2.5);
}

/** The index of the box `dir` leads to from `from`, or -1 when there is none. */
export function pick(from: Box, candidates: Box[], dir: Direction): number {
  let best = -1;
  let bestScore = Infinity;
  candidates.forEach((c, i) => {
    const score = distance(from, c, dir);
    if (score < bestScore) {
      bestScore = score;
      best = i;
    }
  });
  return best;
}

const FOCUSABLE = 'button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), a[href], summary, [tabindex]:not([tabindex="-1"])';

function visible(el: HTMLElement): boolean {
  if (el.closest('.hidden, .player-overlay:not(.show), [aria-hidden="true"]')) return false;
  const r = el.getBoundingClientRect();
  if (r.width === 0 || r.height === 0) return false;
  if (r.bottom < 0 || r.right < 0 || r.top > window.innerHeight || r.left > window.innerWidth) {
    // Off screen but inside a scrolling list: still reachable, focusing it scrolls it in.
    return !!el.closest('.vlist, [data-scroll]');
  }
  return getComputedStyle(el).visibility !== 'hidden';
}

/** Where focus may go now: inside the top dialog when one is open, anywhere otherwise. */
export function focusables(): HTMLElement[] {
  const dialogs = document.querySelectorAll<HTMLElement>('.scrim');
  const scope: ParentNode = dialogs.length ? dialogs[dialogs.length - 1] : document;
  return Array.from(scope.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(visible);
}

/** The part of the screen a control belongs to: the rail, the screen beside it, the player. */
const SECTIONS = '.scrim, .rail, .screen, .player-host, .radio-pill';
const sectionOf = (el: HTMLElement) => el.closest(SECTIONS);

/**
 * Moves focus one step in `dir`. Up and down stay within the part of the screen focus is in, so
 * the bottom of a list does not jump across to the rail; left and right cross between parts.
 * Returns false when there is nowhere to go.
 */
export function move(dir: Direction): boolean {
  const all = focusables();
  if (all.length === 0) return false;
  const current = document.activeElement as HTMLElement | null;
  if (!current || current === document.body || !all.includes(current)) {
    const start = all.find((el) => el.getAttribute('aria-current') === 'page') ?? all[0];
    focus(start);
    return true;
  }
  const section = sectionOf(current);
  const vertical = dir === 'up' || dir === 'down';
  const others = all.filter((el) => el !== current && (!vertical || !section || sectionOf(el) === section));
  const i = pick(current.getBoundingClientRect(), others.map((el) => el.getBoundingClientRect()), dir);
  if (i < 0) return false;
  focus(others[i]);
  return true;
}

export function focus(el: HTMLElement) {
  el.focus();
  el.scrollIntoView?.({ block: 'nearest', inline: 'nearest' });
}
