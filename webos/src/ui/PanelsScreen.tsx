import type { ComponentChildren } from 'preact';
import { useEffect, useLayoutEffect, useRef, useState } from 'preact/hooks';
import { nowNext, offsetMs } from '../data/guide';
import type { Channel } from '../data/model';
import { flashTime, SOURCE_TONES, type SourcedFlash } from '../data/news';
import type { PanelPlayer } from '../player/panel';
import { flashes } from '../state/news';
import { session } from '../state/panelMode';
import { audibleSlot, type Slot } from '../state/panels';
import { settings, updateSettings } from '../state/settings';
import { now, programmesOf, sources } from '../state/store';
import { Logo } from './common';
import { Icon } from './icons';
import { cells, PAIR, SLOTS, type Cell } from './panelLayout';

/**
 * Sport mode and News mode on the television (ADR-053), as the Android television shell draws
 * them (`ui/tv/SportScreen.kt`): the pictures are the whole screen, with no rail. The panel with
 * the remote is drawn larger and is the only one heard; the arrows move the remote and the sound
 * with it. OK on a panel opens its menu (full screen, change, remove; in News mode also two or
 * four screens and the running strip); on an empty panel it opens the picker, on a failed one it
 * tries again. Back closes a drawer, then a full panel, then leaves, and the channel heard goes on
 * full screen. Up and down with no panel that way step the panel's channel, as they zap in full
 * screen: webOS gives apps no channel keys.
 */

const GAP = 4;
const TICKER_HEIGHT = 48;
/** The strip's speed, and the column's, in pixels a second at the app's 1280×720. */
const TICKER_SPEED = 50;
const COLUMN_SPEED = 12;
const CAPTION_HOLD_MS = 4000;

export function PanelsScreen() {
  const news = session.mode.value === 'news';
  const ticker = news && settings.value.newsTicker;
  const slots = session.slots.value;
  const count = session.count.value;
  const focused = session.focused.value;
  const expanded = session.expanded.value;
  const picking = session.picking.value;
  const menu = session.menu.value;
  const drawerOpen = picking !== null || menu !== null;
  const heard = audibleSlot(slots, focused);
  const grid = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ width: window.innerWidth, height: window.innerHeight - (ticker ? TICKER_HEIGHT : 0) });

  useLayoutEffect(() => {
    const el = grid.current;
    if (!el) return;
    const update = () => setSize({ width: el.clientWidth, height: el.clientHeight });
    update();
    const ro = new ResizeObserver(update);
    ro.observe(el);
    return () => ro.disconnect();
  }, [ticker]);

  // The remote goes to the panel in focus whenever nothing else holds it.
  useEffect(() => {
    if (drawerOpen) return;
    const t = setTimeout(() => document.querySelector<HTMLElement>(`.panel[data-slot="${session.focused.value}"]`)?.focus(), 0);
    return () => clearTimeout(t);
  }, [drawerOpen, focused, count, expanded]);

  const layout = cells(size.width, size.height, focused, expanded, GAP, count);
  // Rendered in the players' own order, never the places', so a swap moves no <video> in the page:
  // a media element taken out of the document pauses.
  const byPlayer = slots
    .map((slot, index) => ({ slot, index }))
    .sort((a, b) => a.slot.panel.id - b.slot.panel.id);

  return (
    <div class="panels-screen" aria-label={news ? 'חדשות' : 'ספורט'}>
      {ticker && <NewsTicker />}
      <div class="panels-grid" ref={grid}>
        {byPlayer.map(({ slot, index }) => (
          <PanelView
            key={slot.panel.id}
            slot={slot}
            index={index}
            cell={index < count ? layout[index] : undefined}
            audible={index === heard}
            focusable={!drawerOpen && index < count && (!expanded || index === focused)}
          />
        ))}
      </div>
      {menu !== null && <PanelMenu slot={menu} />}
      {picking !== null && <Picker slot={picking} />}
    </div>
  );
}

function PanelView({ slot, index, cell, audible, focusable }: { slot: Slot; index: number; cell?: Cell; audible: boolean; focusable: boolean }) {
  const host = useRef<HTMLDivElement>(null);
  const player = slot.panel as PanelPlayer;
  const state = player.state.value;
  const channel = slot.channel;
  const shown = !!cell && cell.width > 0;

  useLayoutEffect(() => {
    if (player.media && host.current && player.media.parentElement !== host.current) host.current.appendChild(player.media);
  }, [player]);

  const ok = () => {
    if (slot.flashes) session.openMenu(index);
    else if (!channel) session.openPicker(index);
    else if (state.status === 'failed') session.retry(index);
    else session.openMenu(index);
  };
  const label = slot.flashes ? 'מבזקים' : channel?.name ?? 'הוספת ערוץ';
  const style = shown ? { top: cell!.y, right: cell!.x, width: cell!.width, height: cell!.height } : undefined;

  return (
    <div
      class={`panel${shown ? '' : ' hidden'}${!channel && !slot.flashes ? ' empty' : ''}${session.expanded.value ? ' full' : ''}`}
      data-slot={index}
      role="button"
      tabIndex={focusable ? 0 : -1}
      aria-label={label}
      style={style}
      onFocus={() => session.focus(index)}
      onClick={ok}
      onKeyDown={(e) => {
        if (e.keyCode === 13) {
          e.preventDefault();
          ok();
        }
      }}
    >
      <div class={`panel-video${channel ? '' : ' off'}`} ref={host} />
      {slot.flashes ? (
        <FlashesPanel />
      ) : !channel ? (
        <div class="panel-empty">
          <Icon name="plus" size={30} />
          <span class="title">הוספת ערוץ</span>
          <span class="hint">OK לבחירת ערוץ.</span>
        </div>
      ) : (
        <>
          {state.status === 'failed' ? (
            <div class="panel-state error">
              <Icon name="alert" size={28} />
              <span class="msg">{state.message}</span>
              <span class="hint">OK לניסיון נוסף</span>
            </div>
          ) : state.status === 'loading' ? (
            <div class="panel-state"><div class="spinner" /></div>
          ) : null}
          <Caption channel={channel} audible={audible} />
        </>
      )}
    </div>
  );
}

function Caption({ channel, audible }: { channel: Channel; audible: boolean }) {
  const expanded = session.expanded.value;
  const [visible, setVisible] = useState(true);
  useEffect(() => {
    setVisible(true);
    if (!expanded) return;
    const t = setTimeout(() => setVisible(false), CAPTION_HOLD_MS);
    return () => clearTimeout(t);
  }, [expanded, channel.key]);
  if (!visible) return null;
  const air = nowNext(programmesOf(channel), now.value, offsetMs(channel, settings.value.epgOffsetMinutes));
  return (
    <div class="panel-caption">
      <Logo url={channel.logoUrl} name={channel.name} size={32} />
      <div class="text">
        <span class="name" dir="auto">{channel.name}</span>
        {air.now && <span class="now" dir="auto">{air.now.title}</span>}
      </div>
      {session.paused.value && <Icon name="pause" size={20} />}
      {audible && (
        <span class="sound" title="הסאונד מהמסך הזה">
          <Icon name="audio" size={18} label="הסאונד מהמסך הזה" />
        </span>
      )}
    </div>
  );
}

/* ---------- drawers ---------- */

/** A drawer against the end edge, over the panels that keep playing beside it. Back closes it. */
function Drawer({ title, subtitle, onClose, children }: { title: string; subtitle: string; onClose: () => void; children: ComponentChildren }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape' || e.keyCode === 461) {
        e.preventDefault();
        e.stopPropagation();
        onClose();
      }
    };
    window.addEventListener('keydown', onKey, true);
    return () => window.removeEventListener('keydown', onKey, true);
  }, [onClose]);
  return (
    <div class="scrim drawer-scrim" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div class="drawer" role="dialog" aria-modal="true" aria-label={title}>
        <h2 dir="auto">{title}</h2>
        <div class="sub">{subtitle}</div>
        <div class="drawer-list" data-scroll>{children}</div>
      </div>
    </div>
  );
}

function Row({ icon, label, onClick, selected, value, logo, first }: {
  icon?: Parameters<typeof Icon>[0]['name'];
  label: string;
  onClick: () => void;
  selected?: boolean;
  value?: string;
  logo?: Channel;
  first?: boolean;
}) {
  const ref = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    if (!first) return;
    const t = setTimeout(() => {
      ref.current?.focus();
      ref.current?.scrollIntoView?.({ block: 'nearest' });
    }, 0);
    return () => clearTimeout(t);
  }, [first]);
  return (
    <button ref={ref} class={`drawer-row${selected ? ' selected' : ''}`} onClick={onClick} aria-current={selected ? 'true' : undefined}>
      {logo ? <Logo url={logo.logoUrl} name={logo.name} size={30} /> : icon ? <Icon name={icon} size={20} /> : null}
      <span class="label" dir="auto">{label}</span>
      {value && <span class="value">{value}</span>}
    </button>
  );
}

function PanelMenu({ slot }: { slot: number }) {
  const entry = session.slots.value[slot];
  const expanded = session.expanded.value;
  const news = session.mode.value === 'news';
  const four = session.count.value > PAIR;
  const ticker = settings.value.newsTicker;
  const title = entry?.flashes ? 'מבזקים' : entry?.channel?.name ?? 'הוספת ערוץ';
  return (
    <Drawer title={title} subtitle="OK לבחירה" onClose={() => session.closeMenu()}>
      <Row
        first
        icon={expanded ? 'grid' : 'fullscreen'}
        label={!expanded ? 'מסך מלא' : four ? 'חזרה לכל המסכים' : 'חזרה לשני המסכים'}
        onClick={() => {
          session.closeMenu();
          session.focus(slot);
          session.setExpanded(!session.expanded.value);
        }}
      />
      <Row icon="refresh" label={news ? 'החלפת ערוץ או מבזקים' : 'החלפת ערוץ'} onClick={() => session.openPicker(slot)} />
      <Row icon="close" label="הסרה מהמסך" onClick={() => session.clear(slot)} />
      {news && (
        <>
          <Row
            icon="grid"
            label={four ? 'שני מסכים' : 'ארבעה מסכים'}
            onClick={() => {
              const panels = four ? PAIR : SLOTS;
              updateSettings({ newsPanels: panels });
              session.setPanels(panels);
            }}
          />
          <Row
            icon="news"
            label="פס מבזקים רץ למעלה"
            value={ticker ? 'פועל' : 'כבוי'}
            selected={ticker}
            onClick={() => updateSettings({ newsTicker: !ticker })}
          />
        </>
      )}
    </Drawer>
  );
}

function Picker({ slot }: { slot: number }) {
  const slots = session.slots.value.slice(0, session.count.value);
  const news = session.mode.value === 'news';
  const choices = session.choices.value;
  const current = slots[slot]?.channel ?? null;
  const showsFlashes = !!slots[slot]?.flashes;
  const elsewhere = new Set(slots.filter((_, i) => i !== slot).map((s) => s.channel?.key).filter(Boolean));
  const flashesElsewhere = slots.some((s, i) => i !== slot && s.flashes);
  const list = choices ?? [];
  // Which playlist a channel is from is worth a line only when there is more than one.
  const mixed = new Set(list.map((c) => c.sourceId)).size > 1;
  const names = new Map(sources.value.map((s) => [s.id, s.name]));
  const landOn = current && list.some((c) => c.key === current.key) ? current.key : showsFlashes ? 'flashes' : list[0]?.key ?? (news ? 'flashes' : '');
  return (
    <Drawer
      title="ערוץ למסך הזה"
      subtitle={news ? 'ערוצי חדשות ישראליים, או המבזקים' : 'ערוצי ספורט ישראליים'}
      onClose={() => session.closePicker()}
    >
      {(current || showsFlashes) && <Row icon="close" label="הסרה מהמסך" onClick={() => session.clear(slot)} />}
      {news && (
        <Row
          icon="news"
          label="מבזקים"
          selected={showsFlashes}
          value={flashesElsewhere ? 'כבר על המסך' : undefined}
          first={landOn === 'flashes'}
          onClick={() => session.showFlashes(slot)}
        />
      )}
      {choices === null ? (
        <div class="drawer-note"><div class="spinner" /></div>
      ) : list.length === 0 ? (
        <div class="drawer-note">{news ? 'לא נמצאו ערוצי חדשות ישראליים ברשימות שלך.' : 'לא נמצאו ערוצי ספורט ישראליים ברשימות שלך.'}</div>
      ) : (
        list.map((c) => (
          <Row
            key={c.key}
            logo={c}
            label={c.name}
            selected={c.key === current?.key}
            value={elsewhere.has(c.key) ? 'כבר על המסך' : mixed ? names.get(c.sourceId) : undefined}
            first={landOn === c.key}
            onClick={() => session.assign(slot, c)}
          />
        ))
      )}
    </Drawer>
  );
}

/* ---------- headlines ---------- */

/** Moves [el] toward [to] and round again, for ever, at [speed] pixels a second. */
function useLoop(el: HTMLElement | null, axis: 'x' | 'y', distance: number, speed: number) {
  useEffect(() => {
    if (!el || distance <= 0 || typeof el.animate !== 'function') return;
    const to = axis === 'x' ? `translateX(${distance}px)` : `translateY(${-distance}px)`;
    const anim = el.animate([{ transform: 'translate(0, 0)' }, { transform: to }], {
      duration: (distance / speed) * 1000,
      iterations: Infinity,
      easing: 'linear',
    });
    return () => anim.cancel();
  }, [el, axis, distance, speed]);
}

function FlashLine({ item, nowUtc }: { item: SourcedFlash; nowUtc: number }) {
  const tone = SOURCE_TONES[item.colourIndex % SOURCE_TONES.length];
  return (
    <div class="flash">
      <div class="meta">
        <i class="dot" style={{ background: tone }} />
        <span class="source" dir="auto" style={{ color: tone }}>{item.source}</span>
        {item.flash.publishedUtc !== undefined && <span class="time">{flashTime(item.flash.publishedUtc, nowUtc)}</span>}
      </div>
      <div class="title" dir="auto">{item.flash.title}</div>
    </div>
  );
}

/** News mode's panel of headlines: a column that runs upward when there is more than fits. */
function FlashesPanel() {
  const list = flashes.value;
  const box = useRef<HTMLDivElement>(null);
  const [track, setTrack] = useState<HTMLDivElement | null>(null);
  const [round, setRound] = useState(0);
  const [height, setHeight] = useState(0);
  useLayoutEffect(() => {
    const first = track?.firstElementChild as HTMLElement | null;
    setRound(first?.offsetHeight ?? 0);
    setHeight(box.current?.clientHeight ?? 0);
  }, [track, list]);
  const moving = round > height && height > 0;
  useLoop(moving ? track : null, 'y', round, COLUMN_SPEED);
  const t = now.value;
  return (
    <div class="flashes-panel" ref={box}>
      <div class="flashes-head"><Icon name="news" size={18} /> מבזקים</div>
      {list.length === 0 ? (
        <div class="flashes-none">אין מבזקים עדיין. את המקורות שלהם בוחרים בהגדרות, תחת מבזקים.</div>
      ) : (
        <div class="flashes-view">
          <div class="flashes-track" ref={setTrack}>
            <div class="flashes-round">{list.map((f) => <FlashLine key={`${f.colourIndex}:${f.flash.id}`} item={f} nowUtc={t} />)}</div>
            {moving && <div class="flashes-round" aria-hidden="true">{list.map((f) => <FlashLine key={`c${f.colourIndex}:${f.flash.id}`} item={f} nowUtc={t} />)}</div>}
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * News mode's running strip (ADR-045), in a band of its own above the panels so it hides no
 * picture. The plate stays at the start edge; the headlines run toward it without stopping,
 * rightwards in Hebrew, so each comes in from its first word.
 */
function NewsTicker() {
  const list = flashes.value;
  const view = useRef<HTMLDivElement>(null);
  const [track, setTrack] = useState<HTMLDivElement | null>(null);
  const [round, setRound] = useState(0);
  const [width, setWidth] = useState(0);
  useLayoutEffect(() => {
    const first = track?.firstElementChild as HTMLElement | null;
    setRound(first?.offsetWidth ?? 0);
    setWidth(view.current?.clientWidth ?? 0);
  }, [track, list]);
  const copies = round > 0 ? Math.ceil(width / round) + 1 : 1;
  useLoop(round > 0 ? track : null, 'x', round, TICKER_SPEED);
  const t = now.value;
  return (
    <div class="ticker" style={{ height: TICKER_HEIGHT }} aria-label="מבזקים">
      <div class="ticker-plate">מבזקים</div>
      <div class="ticker-view" ref={view}>
        {list.length === 0 ? (
          <div class="ticker-none">אין מבזקים עדיין. את המקורות שלהם בוחרים בהגדרות, תחת מבזקים.</div>
        ) : (
          <div class="ticker-track" ref={setTrack}>
            {Array.from({ length: copies }, (_, copy) => (
              <div class="ticker-round" key={copy} aria-hidden={copy > 0 ? 'true' : undefined}>
                {list.map((f) => {
                  const tone = SOURCE_TONES[f.colourIndex % SOURCE_TONES.length];
                  return (
                    <span class="ticker-item" key={`${f.colourIndex}:${f.flash.id}`}>
                      <i class="dot" style={{ background: tone }} />
                      <span class="source" dir="auto" style={{ color: tone }}>{f.source}</span>
                      {f.flash.publishedUtc !== undefined && <span class="time">{flashTime(f.flash.publishedUtc, t)}</span>}
                      <span class="title" dir="auto">{f.flash.title}</span>
                    </span>
                  );
                })}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
