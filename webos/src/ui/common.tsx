import { signal } from '@preact/signals';
import type { ComponentChildren } from 'preact';
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { imageUrl } from '../data/api';
import { offsetToCentre, offsetToShow, rowsToDraw } from './list';

/* ---------- sizes ---------- */

/**
 * The app is laid out at 1920×1080 with no scaling, every size in rem with the root at 24 px
 * (ADR-057). A size taken from the computer version (a 1280×720 frame, 16 px to the rem) is
 * `px / 16` rem, the same share of the screen.
 */
export const rem = (px1280: number) => `${px1280 / 16}rem`;

/** Pixels per rem as laid out now: 24 on the television. */
export function remPx(): number {
  const v = parseFloat(getComputedStyle(document.documentElement).fontSize);
  return v > 0 ? v : 24;
}

/* ---------- logo ---------- */

/** A channel's or station's logo, or its first letters when there is none or it fails to load. */
export function Logo({ url, name, size = 40, round = false }: { url?: string; name: string; size?: number; round?: boolean }) {
  const [failed, setFailed] = useState(false);
  const src = failed ? undefined : imageUrl(url);
  useEffect(() => setFailed(false), [url]);
  const letters = name
    .replace(/^[\s|:\-[(]*[a-z]{2}\s*[|:\-\])]\s*/i, '')
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w[0])
    .join('');
  return (
    <span class={`logo${round ? ' round' : ''}`} style={{ width: rem(size), height: rem(size), fontSize: rem(Math.max(11, size * 0.32)) }}>
      {src ? <img src={src} alt="" decoding="async" referrerpolicy="no-referrer" onError={() => setFailed(true)} /> : letters}
    </span>
  );
}

/* ---------- progress ---------- */

export function Progress({ value, live = false }: { value: number; live?: boolean }) {
  return (
    <div class={`progress${live ? ' on-air' : ''}`} role="progressbar" aria-valuenow={Math.round(value * 100)} aria-valuemin={0} aria-valuemax={100}>
      <i style={{ width: `${Math.round(value * 1000) / 10}%` }} />
    </div>
  );
}

/* ---------- virtual list ---------- */

/** What the list in focus reports, for tools/cdp.mjs (ui/debug.ts). */
export const listDebug = { offset: 0, focus: -1, first: 0, last: 0, rowPx: 0, viewPx: 0, count: 0, strayScrolls: 0 };

const UP = 38;
const DOWN = 40;

/**
 * A list that draws only the rows in view: a provider's playlist can hold tens of thousands of
 * channels. It keeps its own offset and moves its rows with `translateY` in a box that never
 * scrolls (ui/list.ts says why); ▲ and ▼ are its own, so a row is drawn, then focused, then known
 * to be in view, in that order, with nothing waited for from the browser.
 *
 * Every row is focusable (`tabIndex` on the element `render` returns). Focus that arrives from
 * elsewhere (an arrow from the column beside it) is followed: the list takes that row as its own.
 */
export function VirtualList<T>({
  items,
  rowHeight,
  render,
  scrollTo,
  class: className = '',
}: {
  items: T[];
  /** In the computer version's pixels (ui/common.tsx `rem`). */
  rowHeight: number;
  render: (item: T, index: number) => ComponentChildren;
  /** An index to bring into view, and to focus when the list has focus, whenever it changes. */
  scrollTo?: number;
  class?: string;
}) {
  const box = useRef<HTMLDivElement>(null);
  const [rowPx, setRowPx] = useState(() => (rowHeight / 16) * remPx());
  const [viewPx, setViewPx] = useState(800);
  const [offset, setOffset] = useState(0);
  const focusIndex = useRef(-1);
  /** Set by a key press: the row to focus once it is drawn. */
  const [wanted, setWanted] = useState<{ index: number } | null>(null);
  const count = items.length;

  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const measure = () => {
      setRowPx((rowHeight / 16) * remPx());
      setViewPx(el.clientHeight || 800);
    };
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    // The box must never scroll: a focus() without preventScroll, or the browser on a re-layout,
    // could. Put it back rather than trust it.
    const stray = () => {
      if (el.scrollTop !== 0 || el.scrollLeft !== 0) {
        listDebug.strayScrolls++;
        el.scrollTop = 0;
        el.scrollLeft = 0;
      }
    };
    el.addEventListener('scroll', stray, { passive: true });
    return () => {
      ro.disconnect();
      el.removeEventListener('scroll', stray);
    };
  }, [rowHeight]);

  // A shorter list (a new filter) must not leave the view past its end.
  useLayoutEffect(() => {
    setOffset((o) => offsetToShow(Math.min(Math.max(focusIndex.current, 0), Math.max(0, count - 1)), o, rowPx, viewPx, count));
    if (focusIndex.current >= count) focusIndex.current = count - 1;
  }, [count, rowPx, viewPx]);

  // The index asked for from outside (the channel on screen): into the middle, and focused if the
  // list holds focus now.
  useLayoutEffect(() => {
    if (scrollTo === undefined || scrollTo < 0 || scrollTo >= count) return;
    setOffset((o) => {
      const top = scrollTo * rowPx;
      const shown = top >= o && top + rowPx <= o + viewPx;
      return shown ? o : offsetToCentre(scrollTo, rowPx, viewPx, count);
    });
    const el = box.current;
    if (el && el.contains(document.activeElement) && focusIndex.current !== scrollTo) setWanted({ index: scrollTo });
  }, [scrollTo, rowPx, viewPx, count]);

  // Focus the row a key press asked for, now that it is drawn.
  useLayoutEffect(() => {
    if (!wanted) return;
    const row = box.current?.querySelector<HTMLElement>(`[data-index="${wanted.index}"] [tabindex]`);
    if (row) {
      focusIndex.current = wanted.index;
      listDebug.focus = wanted.index;
      row.focus({ preventScroll: true });
    }
    setWanted(null);
  }, [wanted]);

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.defaultPrevented || (e.keyCode !== UP && e.keyCode !== DOWN)) return;
    const current = focusIndex.current;
    const next = current + (e.keyCode === DOWN ? 1 : -1);
    // Past either end the arrow is not the list's: the screen's own navigation takes it.
    if (current < 0 || next < 0 || next >= count) return;
    e.preventDefault();
    setOffset((o) => offsetToShow(next, o, rowPx, viewPx, count));
    setWanted({ index: next });
  };

  const onFocusIn = (e: FocusEvent) => {
    const at = (e.target as HTMLElement).closest?.('[data-index]');
    if (!at) return;
    const index = Number(at.getAttribute('data-index'));
    focusIndex.current = index;
    listDebug.focus = index;
    // Arrived from beside the list: make sure the whole row shows.
    setOffset((o) => offsetToShow(index, o, rowPx, viewPx, count));
  };

  const [first, last] = rowsToDraw(offset, rowPx, viewPx, count);
  Object.assign(listDebug, { offset, focus: focusIndex.current, first, last, rowPx, viewPx, count });
  const rows: ComponentChildren[] = [];
  for (let i = first; i < last; i++) {
    rows.push(
      <div class="vlist-row" key={i} data-index={i} style={{ top: `${i * rowPx}px`, height: `${rowPx}px` }}>
        {render(items[i], i)}
      </div>,
    );
  }
  return (
    <div class={`vlist ${className}`} ref={box} onKeyDown={onKeyDown} onFocusIn={onFocusIn} data-offset={offset}>
      <div class="vlist-inner" style={{ height: `${count * rowPx}px`, transform: `translateY(${-offset}px)` }}>
        {rows}
      </div>
    </div>
  );
}

/* ---------- dialog ---------- */

/** The dialogs open now, innermost last: Back closes the last one (ui/App.tsx `back`). */
const openDialogs: Array<() => void> = [];

/** Closes the innermost dialog; false when none is open. */
export function closeTopDialog(): boolean {
  const close = openDialogs[openDialogs.length - 1];
  if (!close) return false;
  close();
  return true;
}

/**
 * A box over the screen that holds the remote while it is open: the arrows stay inside it
 * (ui/spatial.ts scopes focus to the last `.scrim`), Back closes it, and focus goes back to where
 * it was. Opens with its first button focused.
 */
export function Dialog({ onClose, children, label }: { onClose: () => void; children: ComponentChildren; label?: string }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const first = ref.current?.querySelector<HTMLElement>('button, [tabindex]');
    first?.focus({ preventScroll: true });
    openDialogs.push(onClose);
    return () => {
      const i = openDialogs.lastIndexOf(onClose);
      if (i >= 0) openDialogs.splice(i, 1);
      if (previous && document.contains(previous)) previous.focus({ preventScroll: true });
    };
  }, []);
  return (
    <div class="scrim">
      <div class="dialog" role="dialog" aria-modal="true" aria-label={label} ref={ref}>
        {children}
      </div>
    </div>
  );
}

/* ---------- toasts ---------- */

export const toasts = signal<Array<{ id: number; text: string }>>([]);
let toastId = 0;

export function toast(text: string) {
  const id = ++toastId;
  toasts.value = [...toasts.value, { id, text }];
  setTimeout(() => (toasts.value = toasts.value.filter((t) => t.id !== id)), 3200);
}

export function Toasts() {
  return (
    <div class="toasts" role="status" aria-live="polite">
      {toasts.value.map((t) => (
        <div class="toast" key={t.id}>
          {t.text}
        </div>
      ))}
    </div>
  );
}

/* ---------- clock ---------- */

export function useClock(intervalMs = 1000): number {
  const [t, setT] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setT(Date.now()), intervalMs);
    return () => clearInterval(id);
  }, [intervalMs]);
  return t;
}

/** Is the keyboard event aimed at a text field, where letters are typing rather than shortcuts? */
export function isTyping(e: KeyboardEvent): boolean {
  const t = e.target as HTMLElement | null;
  if (!t) return false;
  const tag = t.tagName;
  return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || t.isContentEditable;
}
