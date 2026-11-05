import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { clock, clockRange, dayLabel, nowNext, offsetMs, progress, startOfDay } from '../data/guide';
import type { Channel, Programme } from '../data/model';
import { playback } from '../player/engine';
import { liveFilter, play, playerSlot, setFilter, visibleChannels } from '../state/actions';
import { settings } from '../state/settings';
import { guideProgress, now, programmesOf, refreshGuide, refreshing, sources } from '../state/store';
import { Dialog, Logo, Progress } from './common';
import { Icon } from './icons';
import { GroupOptions } from './LiveScreen';

const PX_PER_MIN = 4; // an hour is 240px, as the television's guide (TvMetrics: hour width 240dp)
const ROW_H = 60;
const CH_COL = 220;
const RULER_H = 34;
const HOURS = 26; // a day, and the first two hours of the next for what runs past midnight

const DAY = 24 * 3600_000;

/**
 * The guide grid (FR-UI guide): channels down the side, time across — running right to left, as
 * the Hebrew television guide does — with a line at now. Rows and programmes are drawn only where
 * they are in view, for the same reason the channel list is.
 */
export function GuideScreen() {
  const slot = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => {
    const el = slot.current;
    playerSlot.value = el;
    return () => {
      if (playerSlot.value === el) playerSlot.value = null;
    };
  }, []);

  const today = startOfDay(now.value);
  const [day, setDay] = useState(today);
  const [details, setDetails] = useState<{ channel: Channel; programme: Programme } | null>(null);
  const scroller = useRef<HTMLDivElement>(null);
  const [view, setView] = useState({ top: 0, x: 0, w: 1200, h: 800 });
  const channels = visibleChannels.value;
  const current = playback.value.role === 'tv' ? playback.value.channel : null;

  useLayoutEffect(() => {
    const el = scroller.current;
    if (!el) return;
    let frame = 0;
    const update = () => {
      cancelAnimationFrame(frame);
      frame = requestAnimationFrame(() => setView({ top: el.scrollTop, x: Math.abs(el.scrollLeft), w: el.clientWidth, h: el.clientHeight }));
    };
    update();
    el.addEventListener('scroll', update, { passive: true });
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => {
      el.removeEventListener('scroll', update);
      ro.disconnect();
      cancelAnimationFrame(frame);
    };
  }, []);

  const scrollToTime = (t: number) => {
    const el = scroller.current;
    if (!el) return;
    const x = Math.max(0, ((t - day) / 60_000) * PX_PER_MIN);
    // In a right-to-left box the start is at scrollLeft 0 and the content runs into negatives.
    el.scrollLeft = getComputedStyle(el).direction === 'rtl' ? -x : x;
  };

  // Open on now (today), or on the morning of another day; and on the channel being watched.
  useEffect(() => {
    scrollToTime(day === today ? Date.now() - 30 * 60_000 : day + 6 * 3600_000);
  }, [day]);
  useEffect(() => {
    const el = scroller.current;
    const i = current ? channels.findIndex((c) => c.key === current.key) : -1;
    if (el && i >= 0) el.scrollTop = Math.max(0, i * ROW_H - el.clientHeight / 2);
  }, []);

  const width = CH_COL + HOURS * 60 * PX_PER_MIN;
  const first = Math.max(0, Math.floor(view.top / ROW_H) - 4);
  const last = Math.min(channels.length, Math.ceil((view.top + view.h) / ROW_H) + 4);
  const tFrom = day + (Math.max(0, view.x - CH_COL) / PX_PER_MIN) * 60_000 - 3600_000;
  const tTo = day + ((view.x + view.w) / PX_PER_MIN) * 60_000 + 3600_000;
  const xOf = (t: number) => CH_COL + ((t - day) / 60_000) * PX_PER_MIN;

  const days = [-1, 0, 1, 2].map((d) => today + d * DAY);
  const dayName = (d: number) => (d === today ? 'היום' : d === today - DAY ? 'אתמול' : d === today + DAY ? 'מחר' : dayLabel(d));
  const busy = sources.value.some((s) => refreshing.value[s.id] === 'guide');
  const status = Object.values(guideProgress.value)[0];

  return (
    <div class="guide">
      <div class="guide-top">
        <div class="controls">
          <div class="row">
            <h1 class="screen-title">לוח שידורים</h1>
            <span class="faint">{dayLabel(day)}</span>
          </div>
          <div class="row">
            {days.map((d) => (
              <button key={d} class="chip" aria-pressed={d === day} onClick={() => setDay(d)}>
                {dayName(d)}
              </button>
            ))}
            <button class="chip" onClick={() => (day === today ? scrollToTime(Date.now() - 30 * 60_000) : setDay(today))}>
              <Icon name="history" size={16} /> עכשיו
            </button>
          </div>
          <div class="row">
            <select class="input" style={{ width: 'auto', minWidth: 220 }} value={liveFilter.value} onChange={(e) => setFilter((e.target as HTMLSelectElement).value)} aria-label="קבוצה">
              <GroupOptions />
            </select>
            <button class="btn small ghost" disabled={busy} onClick={() => sources.value.filter((s) => s.enabled).forEach((s) => void refreshGuide(s.id))}>
              <Icon name="refresh" size={16} /> {busy ? 'מעדכן…' : 'עדכן לוח שידורים'}
            </button>
            {status && <span class="faint">{status}</span>}
          </div>
        </div>
        <div class="preview">
          <div class="slot" ref={slot} />
          {current && <PreviewInfo channel={current} />}
        </div>
      </div>

      <div class="epg" ref={scroller}>
        <div class="epg-inner" style={{ width, height: RULER_H + channels.length * ROW_H }}>
          <div class="epg-ruler" style={{ width }}>
            <div class="epg-corner" style={{ width: CH_COL }} />
            {Array.from({ length: HOURS * 2 }, (_, i) => day + i * 30 * 60_000)
              .filter((t) => t >= tFrom - 3600_000 && t <= tTo)
              .map((t) => (
                <span key={t} class="epg-tick" style={{ insetInlineStart: xOf(t) }}>
                  {clock(t)}
                </span>
              ))}
            {now.value >= day && now.value < day + HOURS * 3600_000 && (
              <span class="epg-now-pill" style={{ insetInlineStart: xOf(now.value) }}>{clock(now.value)}</span>
            )}
          </div>
          {channels.slice(first, last).map((c, k) => (
            <GuideRow
              key={c.key}
              channel={c}
              top={RULER_H + (first + k) * ROW_H}
              width={width}
              from={tFrom}
              to={tTo}
              dayStart={day}
              xOf={xOf}
              selected={c.key === current?.key}
              onOpen={(p) => setDetails({ channel: c, programme: p })}
            />
          ))}
          {now.value >= day && now.value < day + HOURS * 3600_000 && (
            <div class="epg-now" style={{ insetInlineStart: xOf(now.value), top: RULER_H, height: channels.length * ROW_H }} />
          )}
        </div>
        {channels.length === 0 && <div class="empty"><p>אין ערוצים בקבוצה הזו.</p></div>}
      </div>

      {details && <ProgrammeDialog {...details} onClose={() => setDetails(null)} />}
    </div>
  );
}

function GuideRow(props: {
  channel: Channel;
  top: number;
  width: number;
  from: number;
  to: number;
  dayStart: number;
  xOf: (t: number) => number;
  selected: boolean;
  onOpen: (p: Programme) => void;
}) {
  const { channel, top, width, from, to, xOf, selected, onOpen, dayStart } = props;
  const shift = offsetMs(channel, settings.value.epgOffsetMinutes);
  const list = programmesOf(channel) ?? [];
  const t = now.value;
  const cells = [];
  for (const p of list) {
    const start = p.start + shift;
    const end = p.end + shift;
    if (end < from || start > to || end <= dayStart) continue;
    const x = xOf(Math.max(start, dayStart));
    const w = xOf(end) - x;
    if (w < 6) continue;
    const state = end <= t ? ' past' : start <= t ? ' airing' : '';
    const open = () => onOpen({ ...p, start, end });
    cells.push(
      <div
        key={p.start}
        class={`epg-cell${state}`}
        style={{ insetInlineStart: x + 2, width: w - 4 }}
        role="button"
        tabIndex={0}
        onClick={open}
        onKeyDown={(e) => activate(e, open)}
        title={`${p.title} · ${clock(start)}`}
      >
        <span class="t" dir="auto">{p.title}</span>
        {w > 90 && <span class="s">{clockRange(start, end)}</span>}
      </div>,
    );
  }
  return (
    <div class="epg-row" style={{ top, width, height: ROW_H }}>
      <div
        class={`epg-ch${selected ? ' selected' : ''}`}
        style={{ width: CH_COL }}
        role="button"
        tabIndex={0}
        aria-current={selected ? 'true' : undefined}
        onClick={() => play(channel)}
        onKeyDown={(e) => activate(e, () => play(channel))}
        title={`צפייה ב${channel.name}`}
      >
        <Logo url={channel.logoUrl} name={channel.name} size={36} />
        <span class="name" dir="auto">{channel.name}</span>
        {selected && <span class="onscreen-dot" role="img" aria-label="על המסך" />}
      </div>
      {list.length === 0 ? (
        <div class="epg-cell nodata" style={{ insetInlineStart: xOf(Math.max(from, dayStart)) + 2, width: Math.max(0, xOf(to) - xOf(Math.max(from, dayStart)) - 4) }}>
          <span class="s">אין מידע מלוח השידורים</span>
        </div>
      ) : (
        cells
      )}
    </div>
  );
}

/** Enter or Space on a cell or a channel does what a click does: the guide works from the keyboard. */
function activate(e: KeyboardEvent, run: () => void) {
  if (e.key !== 'Enter' && e.key !== ' ') return;
  e.preventDefault();
  e.stopPropagation();
  run();
}

function PreviewInfo({ channel }: { channel: Channel }) {
  const air = nowNext(programmesOf(channel), now.value, offsetMs(channel, settings.value.epgOffsetMinutes));
  return (
    <div class="preview-info" style={{ marginTop: 8 }}>
      <span class="muted">{channel.name}</span>
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
        <span class="muted">{channel.name}</span>
        {airing && <span class="badge-live">משודר עכשיו</span>}
      </div>
      <h2 class="p-title" dir="auto">{programme.title}</h2>
      <div class="muted meta">
        {dayLabel(programme.start)} · {clockRange(programme.start, programme.end)}
        {programme.subtitle ? ` · ${programme.subtitle}` : ''}
        {episode ? ` · ${episode}` : ''}
      </div>
      {airing && <Progress value={progress(programme, t)} live />}
      {programme.description && <p style={{ margin: 0 }} dir="auto">{programme.description}</p>}
      <div class="buttons">
        <button class="btn" onClick={onClose}>סגור</button>
        <button class="btn primary" onClick={() => { play(channel); onClose(); }}>
          <Icon name="play" size={16} /> צפייה בערוץ
        </button>
      </div>
    </Dialog>
  );
}
