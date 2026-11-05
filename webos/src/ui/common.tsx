import { signal } from '@preact/signals';
import type { ComponentChildren } from 'preact';
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { imageUrl } from '../data/api';

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
    <span class={`logo${round ? ' round' : ''}`} style={{ width: size, height: size, fontSize: Math.max(11, size * 0.32) }}>
      {src ? <img src={src} alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" onError={() => setFailed(true)} /> : letters}
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

/* ---------- toggle ---------- */

export function Toggle({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: string }) {
  return <button type="button" role="switch" class="toggle" aria-checked={checked} aria-label={label} onClick={() => onChange(!checked)} />;
}

/* ---------- virtual list ---------- */

/**
 * A list that draws only the rows in view: a provider's playlist can hold tens of thousands of
 * channels, and a row per channel in the DOM would make every scroll and every zap slow.
 */
export function VirtualList<T>({
  items,
  rowHeight,
  render,
  scrollTo,
  class: className = '',
}: {
  items: T[];
  rowHeight: number;
  render: (item: T, index: number) => ComponentChildren;
  /** An index to bring into view whenever it changes. */
  scrollTo?: number;
  class?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [view, setView] = useState({ top: 0, height: 800 });

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const update = () => setView({ top: el.scrollTop, height: el.clientHeight });
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    el.addEventListener('scroll', update, { passive: true });
    return () => {
      ro.disconnect();
      el.removeEventListener('scroll', update);
    };
  }, []);

  useEffect(() => {
    const el = ref.current;
    if (!el || scrollTo === undefined || scrollTo < 0) return;
    const top = scrollTo * rowHeight;
    if (top < el.scrollTop || top + rowHeight > el.scrollTop + el.clientHeight) {
      el.scrollTop = Math.max(0, top - el.clientHeight / 2 + rowHeight / 2);
    }
  }, [scrollTo, rowHeight]);

  const first = Math.max(0, Math.floor(view.top / rowHeight) - 6);
  const last = Math.min(items.length, Math.ceil((view.top + view.height) / rowHeight) + 6);
  const rows: ComponentChildren[] = [];
  for (let i = first; i < last; i++) {
    rows.push(
      <div class="vlist-row" key={i} style={{ top: i * rowHeight, height: rowHeight }}>
        {render(items[i], i)}
      </div>,
    );
  }
  return (
    <div class={`vlist ${className}`} ref={ref}>
      <div class="vlist-inner" style={{ height: items.length * rowHeight }}>
        {rows}
      </div>
    </div>
  );
}

/* ---------- dialog ---------- */

export function Dialog({ title, onClose, children, label }: { title?: string; onClose: () => void; children: ComponentChildren; label?: string }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const first = ref.current?.querySelector<HTMLElement>('input, select, textarea, button:not(.icon-btn)');
    first?.focus();
    const onKey = (e: KeyboardEvent) => {
      // Escape on a keyboard, Back (461) on the remote.
      if (e.key === 'Escape' || e.keyCode === 461) {
        e.preventDefault();
        e.stopPropagation();
        onClose();
      }
    };
    window.addEventListener('keydown', onKey, true);
    return () => {
      window.removeEventListener('keydown', onKey, true);
      previous?.focus?.();
    };
  }, []);
  return (
    <div class="scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div class="dialog" role="dialog" aria-modal="true" aria-label={label ?? title} ref={ref}>
        {title && <h2>{title}</h2>}
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
