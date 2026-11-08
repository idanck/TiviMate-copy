import { useLayoutEffect, useRef, useState } from 'preact/hooks';
import { clock, clockRange, dayLabel, nowNext, offsetMs, progress, startOfDay } from '../data/guide';
import type { Channel, Programme } from '../data/model';
import { engine, playback } from '../player/engine';
import { liveFilter, play, playerSlot, toggleFullscreen, visibleChannels } from '../state/actions';
import { settings } from '../state/settings';
import { guideProgress, now, programmesOf, refreshGuide, refreshing, sources } from '../state/store';
import { Dialog, Logo, Progress, remPx } from './common';
import { CH_COL, cellAt, cellsOf, clampX, DAY_MS, HOURS, offsetXToShow, PX_PER_MIN, ROW_H, RULER_H, strip, ticks, timeWindow, xOf, type Cell } from './grid';
import { Icon } from './icons';
import { offsetToCentre, offsetToShow, rowsToDraw } from './list';
import { filterLabel } from './LiveScreen';
import { flashInfo } from './PlayerHost';
import { watch } from './watch';

const UP = 38;
const DOWN = 40;
const LEFT = 37;
const RIGHT = 39;
const OK = 13;

/** What the guide reports, for tools/cdp.mjs (ui/debug.ts). */
export const guideDebug = { day: '', row: -1, col: -1, offsetX: 0, offsetY: 0, first: 0, last: 0, cells: 0, active: false, viewW: 0, viewH: 0 };

/** Where the remote is in the grid: a row, and a cell in it, or -1 for the channel's own cell. */
interface Pos {
  row: number;
  col: number;
}

/**
 * The guide grid (FR-UI guide), ported from the computer version (web/src/ui/GuideScreen.tsx)
 * for the remote: channels down the right, time across, running right to left as the Hebrew
 * television guide does, with a line at now; the picture in its box above with what is on.
 *
 * The grid never scrolls (ADR-057): it keeps two offsets of its own and moves its rows with
 * `translateY` and the hours with `translateX` (ui/grid.ts). The remote is not on a DOM element
 * inside the grid but on the grid itself: the arrows move a position of the grid's own, the cell
 * at it is drawn as focused, and OK opens it. So nothing is ever focused off screen, and no
 * `scroll` event is waited for.
 */
export function GuideScreen() {
  watch.renders++;
  const slot = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const el = slot.current;
    playerSlot.value = el;
    return () => {
      if (playerSlot.value === el) playerSlot.value = null;
    };
  }, []);

  const k = remPx() / 16;
  const pxPerMin = PX_PER_MIN * k;
  const rowPx = ROW_H * k;
  const chColPx = CH_COL * k;
  const rulerPx = RULER_H * k;

  const today = startOfDay(now.value);
  const channels = visibleChannels.value;
  const count = channels.length;
  const current = playback.value.role === 'tv' ? playback.value.channel : null;
  const epgOffset = settings.value.epgOffsetMinutes;
  const rowCells = (i: number, day: number): Cell[] => {
    const c = channels[i];
    return c ? cellsOf(programmesOf(c), offsetMs(c, epgOffset), day, pxPerMin) : [];
  };

  const box = useRef<HTMLDivElement>(null);
  const [view, setView] = useState({ w: 1500, h: 600 });
  const [day, setDay] = useState(today);
  const [offX, setOffX] = useState(0);
  const [offY, setOffY] = useState(0);
  const [pos, setPos] = useState<Pos>(() => {
    const i = current ? channels.findIndex((c) => c.key === current.key) : -1;
    return { row: Math.max(0, i), col: -1 };
  });
  /** The time the remote is at: kept across rows, so ▲▼ stay in the same hour. */
  const anchor = useRef(now.value);
  const [details, setDetails] = useState<{ channel: Channel; programme: Programme } | null>(null);
  const [active, setActive] = useState(false);

  // The grid's own size: the time area and the rows area, measured again on any change.
  useLayoutEffect(() => {
    const el = box.current;
    if (!el) return;
    const measure = () => setView({ w: Math.max(100, el.clientWidth - chColPx), h: Math.max(rowPx, el.clientHeight - rulerPx) });
    measure();
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    const stray = () => {
      if (el.scrollTop !== 0 || el.scrollLeft !== 0) {
        el.scrollTop = 0;
        el.scrollLeft = 0;
      }
    };
    el.addEventListener('scroll', stray, { passive: true });
    return () => {
      ro.disconnect();
      el.removeEventListener('scroll', stray);
    };
  }, [chColPx, rowPx, rulerPx]);

  /** Moves the remote to `row`, `col`; the grid follows so the cell shows. */
  const go = (row: number, col: number, cells: Cell[]) => {
    setPos({ row, col });
    setOffY((o) => offsetToShow(row, o, rowPx, view.h, count));
    const cell = cells[col];
    if (cell) {
      anchor.current = Math.max(cell.start, day);
      setOffX((o) => offsetXToShow(cell.x, cell.w, o, pxPerMin, view.w));
    }
  };

  // Open on now (today), or on the morning of another day: the hours there, and the remote on
  // the programme on at that time in its row.
  useLayoutEffect(() => {
    const at = day === today ? now.value : day + 6 * 3600_000;
    anchor.current = at;
    const cells = rowCells(pos.row, day);
    const col = cellAt(cells, at);
    setOffX(clampX(xOf(at - 30 * 60_000, day, pxPerMin), pxPerMin, view.w));
    setPos((p) => ({ row: p.row, col: p.col < 0 ? -1 : col }));
    if (col >= 0 && pos.col >= 0) setOffX((o) => offsetXToShow(cells[col].x, cells[col].w, o, pxPerMin, view.w));
  }, [day, view.w]);
  useLayoutEffect(() => {
    setOffY(offsetToCentre(pos.row, rowPx, view.h, count));
  }, [view.h]);

  const onKeyDown = (e: KeyboardEvent) => {
    if (e.defaultPrevented || e.target !== e.currentTarget || count === 0) return;
    const code = e.keyCode;
    const row = Math.min(pos.row, count - 1);
    const cells = rowCells(row, day);
    const col = Math.min(pos.col, cells.length - 1);
    if (code === UP || code === DOWN) {
      const next = row + (code === DOWN ? 1 : -1);
      // Past either end the arrow is not the grid's: the screen's own navigation takes it.
      if (next < 0 || next >= count) return;
      e.preventDefault();
      const nextCells = rowCells(next, day);
      go(next, col < 0 ? -1 : cellAt(nextCells, anchor.current), nextCells);
      return;
    }
    if (code === LEFT) {
      // Later: time runs right to left.
      e.preventDefault();
      if (cells.length === 0) return;
      go(row, col < 0 ? cellAt(cells, anchor.current) : Math.min(col + 1, cells.length - 1), cells);
      return;
    }
    if (code === RIGHT) {
      // Earlier, and from the first programme to the channel's own cell.
      if (col < 0) return;
      e.preventDefault();
      go(row, col - 1, cells);
      return;
    }
    if (code === OK) {
      e.preventDefault();
      const channel = channels[row];
      if (col < 0) {
        if (current && channel.key === current.key) {
          if (playback.value.status === 'error') engine.retry();
          else {
            toggleFullscreen();
            flashInfo.value++;
          }
        } else play(channel);
      } else {
        const c = cells[col];
        setDetails({ channel, programme: { ...c.programme, start: c.start, end: c.end } });
      }
    }
  };

  const width = strip(pxPerMin);
  const [first, last] = rowsToDraw(offY, rowPx, view.h, count, 2);
  const [tFrom, tTo] = timeWindow(offX, view.w, day, pxPerMin);
  const inDay = now.value >= day && now.value < day + HOURS * 3600_000;
  const nowX = xOf(now.value, day, pxPerMin);
  const days = [-1, 0, 1, 2].map((d) => today + d * DAY_MS);
  const dayName = (d: number) => (d === today ? 'היום' : d === today - DAY_MS ? 'אתמול' : d === today + DAY_MS ? 'מחר' : dayLabel(d));
  const busy = sources.value.some((s) => refreshing.value[s.id] === 'guide');
  const status = Object.values(guideProgress.value)[0];
  const focusRow = Math.min(pos.row, count - 1);
  const focusCells = count ? rowCells(focusRow, day) : [];
  const focusCol = Math.min(pos.col, focusCells.length - 1);
  const focused = focusCells[focusCol];
  Object.assign(guideDebug, { day: new Date(day).toISOString().slice(0, 10), row: focusRow, col: focusCol, offsetX: offX, offsetY: offY, first, last, active, viewW: view.w, viewH: view.h });

  const toNow = () => {
    if (day !== today) {
      setDay(today);
      return;
    }
    const cells = rowCells(focusRow, day);
    anchor.current = now.value;
    setOffX(clampX(xOf(now.value - 30 * 60_000, day, pxPerMin), pxPerMin, view.w));
    if (focusCol >= 0) go(focusRow, cellAt(cells, now.value), cells);
  };

  let drawn = 0;
  const rows = [];
  const chs = [];
  for (let i = first; i < last; i++) {
    const c = channels[i];
    const selected = !!current && c.key === current.key;
    const isFocusRow = i === focusRow;
    chs.push(
      <div
        key={c.key}
        class={`epg-ch${selected ? ' selected' : ''}${isFocusRow && focusCol < 0 ? ' focused' : ''}`}
        style={{ top: `${i * rowPx}px`, height: `${rowPx}px` }}
        id={isFocusRow && focusCol < 0 ? 'epg-focus' : undefined}
        onClick={() => play(c)}
      >
        <Logo url={c.logoUrl} name={c.name} size={36} />
        <span class="name" dir="auto">{c.name}</span>
        {selected && <span class="onscreen-dot" role="img" aria-label="על המסך" />}
      </div>,
    );
    const cells = rowCells(i, day);
    const items = [];
    if (cells.length === 0) {
      const from = xOf(Math.max(tFrom, day), day, pxPerMin);
      const to = xOf(Math.min(tTo, day + HOURS * 3600_000), day, pxPerMin);
      items.push(
        <div key="nodata" class="epg-cell nodata" style={{ right: `${from + 2 * k}px`, width: `${Math.max(0, to - from - 4 * k)}px` }}>
          <span class="s">אין מידע מלוח השידורים</span>
        </div>,
      );
    } else {
      for (let j = 0; j < cells.length; j++) {
        const cell = cells[j];
        const isFocus = isFocusRow && j === focusCol;
        if (cell.end < tFrom || cell.start > tTo) continue;
        if (cell.w < 6 * k && !isFocus) continue;
        const t = now.value;
        const state = cell.end <= t ? ' past' : cell.start <= t ? ' airing' : '';
        drawn++;
        items.push(
          <div
            key={cell.programme.start}
            id={isFocus ? 'epg-focus' : undefined}
            class={`epg-cell${state}${isFocus ? ' focused' : ''}`}
            style={{ right: `${cell.x + 2 * k}px`, width: `${Math.max(2 * k, cell.w - 4 * k)}px` }}
            onClick={() => setDetails({ channel: c, programme: { ...cell.programme, start: cell.start, end: cell.end } })}
          >
            <span class="t" dir="auto">{cell.programme.title}</span>
            {cell.w > 90 * k && <span class="s">{clockRange(cell.start, cell.end)}</span>}
          </div>,
        );
      }
    }
    rows.push(
      <div key={c.key} class="epg-row" style={{ top: `${i * rowPx}px`, height: `${rowPx}px` }}>
        {items}
      </div>,
    );
  }
  guideDebug.cells = drawn;

  return (
    <div class="guide">
      <div class="guide-top">
        <div class="controls">
          <div class="row">
            <h1 class="screen-title">לוח שידורים</h1>
            <span class="faint">{dayLabel(day)}</span>
            <span class="faint" dir="auto">{` · ${filterLabel(liveFilter.value)}`}</span>
          </div>
          <div class="row">
            {days.map((d) => (
              <button key={d} class="chip" aria-pressed={d === day} onClick={() => setDay(d)}>
                {dayName(d)}
              </button>
            ))}
            <button class="chip" onClick={toNow}>
              <Icon name="history" size={16} /> <span>עכשיו</span>
            </button>
          </div>
          <div class="row">
            <button class="btn" disabled={busy} onClick={() => sources.value.filter((s) => s.enabled && s.role === 'tv').forEach((s) => void refreshGuide(s.id))}>
              <Icon name="refresh" size={18} /> <span>{busy ? 'מעדכן…' : 'עדכן לוח שידורים'}</span>
            </button>
            {status && <span class="faint">{status}</span>}
          </div>
          <FocusLine channel={channels[focusRow]} cell={focusCol >= 0 ? focused : undefined} />
        </div>
        <div class="preview">
          <div class="slot" ref={slot}>
            {!current && <div class="slot-empty">אין ערוץ על המסך</div>}
          </div>
          {current && <PreviewInfo channel={current} />}
        </div>
      </div>

      <div
        class={`epg${active ? ' active' : ''}`}
        ref={box}
        tabIndex={0}
        role="grid"
        aria-label="לוח שידורים"
        aria-activedescendant={active ? 'epg-focus' : undefined}
        onKeyDown={onKeyDown}
        onFocus={() => setActive(true)}
        onBlur={() => setActive(false)}
      >
        <div class="epg-chcol" style={{ width: `${chColPx}px` }}>
          <div class="epg-corner" style={{ height: `${rulerPx}px` }} />
          <div class="epg-rows" style={{ top: `${rulerPx}px` }}>
            <div class="epg-chcol-inner" style={{ height: `${count * rowPx}px`, transform: `translateY(${-offY}px)` }}>
              {chs}
            </div>
          </div>
        </div>
        <div class="epg-view" style={{ right: `${chColPx}px` }}>
          <div class="epg-ruler" style={{ width: `${width}px`, height: `${rulerPx}px`, transform: `translateX(${offX}px)` }}>
            {ticks(day, tFrom, tTo).map((t) => (
              <span key={t} class="epg-tick" style={{ right: `${xOf(t, day, pxPerMin)}px`, lineHeight: `${rulerPx}px` }}>
                {clock(t)}
              </span>
            ))}
            {inDay && <span class="epg-now-pill" style={{ right: `${nowX}px` }}>{clock(now.value)}</span>}
          </div>
          <div class="epg-rows" style={{ top: `${rulerPx}px` }}>
            <div class="epg-body" style={{ width: `${width}px`, height: `${count * rowPx}px`, transform: `translate(${offX}px, ${-offY}px)` }}>
              {rows}
              {inDay && <div class="epg-now" style={{ right: `${nowX}px`, height: `${count * rowPx}px` }} />}
            </div>
          </div>
        </div>
        {count === 0 && <div class="empty"><p>אין ערוצים בקבוצה הזו.</p></div>}
      </div>

      {details && <ProgrammeDialog {...details} onClose={() => setDetails(null)} />}
    </div>
  );
}

/** One line on the programme the remote is on (ADR-051's strip), under the controls. */
function FocusLine({ channel, cell }: { channel: Channel | undefined; cell: Cell | undefined }) {
  if (!channel) return <div class="focus-line faint">&nbsp;</div>;
  if (!cell) {
    return (
      <div class="focus-line">
        <span class="name" dir="auto">{channel.name}</span>
        <span class="faint">OK לצפייה · ◀ לתוכניות</span>
      </div>
    );
  }
  const p = cell.programme;
  return (
    <div class="focus-line">
      <span class="name" dir="auto">{p.title}</span>
      <span class="faint">{clockRange(cell.start, cell.end)}</span>
      <span class="faint" dir="auto">{channel.name}</span>
      {p.subtitle && <span class="faint" dir="auto">{p.subtitle}</span>}
    </div>
  );
}

function PreviewInfo({ channel }: { channel: Channel }) {
  const air = nowNext(programmesOf(channel), now.value, offsetMs(channel, settings.value.epgOffsetMinutes));
  return (
    <div class="preview-info">
      <span class="muted" dir="auto">{channel.name}</span>
      {air.now && (
        <>
          <span class="p-title" dir="auto">{air.now.title}</span>
          <Progress value={progress(air.now, now.value)} live />
        </>
      )}
    </div>
  );
}

function ProgrammeDialog({ channel, programme, onClose }: { channel: Channel; programme: Programme; onClose: () => void }) {
  const t = now.value;
  const airing = programme.start <= t && programme.end > t;
  const episode = programme.season || programme.episode ? `עונה ${programme.season ?? '?'} פרק ${programme.episode ?? '?'}` : '';
  return (
    <Dialog onClose={onClose} label={programme.title}>
      <div class="head">
        <Logo url={channel.logoUrl} name={channel.name} size={40} />
        <span class="muted" dir="auto">{channel.name}</span>
        {airing && <span class="badge-live">משודר עכשיו</span>}
      </div>
      <h2 class="p-title" dir="auto">{programme.title}</h2>
      <div class="muted meta">
        {dayLabel(programme.start)} · {clockRange(programme.start, programme.end)}
        {programme.subtitle ? ` · ${programme.subtitle}` : ''}
        {episode ? ` · ${episode}` : ''}
      </div>
      {airing && <Progress value={progress(programme, t)} live />}
      {programme.description && <p class="desc" dir="auto">{programme.description}</p>}
      <div class="buttons">
        <button class="btn" onClick={() => { play(channel); onClose(); }}>
          <Icon name="play" size={18} /> <span>צפייה בערוץ</span>
        </button>
        <button class="btn" onClick={onClose}>סגור</button>
      </div>
    </Dialog>
  );
}
