import { batch, signal } from '@preact/signals';
import { isIsraeliNews, isIsraeliSport, newsStation } from '../data/classify';
import type { Channel } from '../data/model';
import type { Panel } from '../player/panel';
import { keptPanels, PAIR, panelCount, SLOTS } from '../ui/panelLayout';

/**
 * Sport mode's state and News mode's (ADR-053): a port of the Android app's `ui/SportSession.kt`
 * (ADR-041, ADR-042, ADR-045), kept free of the DOM so it can be tested with stand-in panels.
 *
 * Two panels, or in News mode four if the viewer chose them; which one has the remote, and
 * whether it fills the screen. There are always [SLOTS] slots, each with its own player; only the
 * first [count] are on screen, and the rest are kept empty and released. The player belongs to the
 * channel rather than to the place, so two panels that swap channels swap players too, and nothing
 * reconnects. In News mode a panel may show the headlines instead: no channel, and no connection.
 *
 * The sound follows the remote: the panel in focus is the one heard; with the headlines in focus,
 * the first picture beside them is ([audibleSlot]).
 */

export type Mode = 'sport' | 'news';

export interface Slot {
  channel: Channel | null;
  panel: Panel;
  flashes: boolean;
}

/** What News mode's remembered grid says for the panel of headlines. */
export const FLASHES_ID = '__flashes';

export interface SessionHost {
  /** Makes panel [id]; asked once for each slot, on the first opening. */
  createPanel(id: number, othersPlaying: () => boolean): Panel;
  /** Remembers the grid of [mode], by channel key, [FLASHES_ID] or null. */
  persist(mode: Mode, slots: Array<string | null>): void;
}

/** The panel to be heard: the one in focus, unless it shows the headlines; -1 for none. */
export function audibleSlot(slots: Slot[], focused: number): number {
  return slots[focused]?.flashes ? slots.findIndex((s) => s.channel !== null) : focused;
}

/**
 * What [mode] may show, in playlist order. In News mode the free-to-air copy of a station comes
 * before the provider's, which is left out (Android's `FreeCopies`, ADR-042): the provider's would
 * spend one of the account's connections on what looks like the same channel. [replacedBy] gives
 * the free copy by the key of each provider copy left out, for a grid remembered with one.
 */
export function choicesFor(mode: Mode, channels: Channel[], freeSourceIds: Set<string>): { choices: Channel[]; replacedBy: Map<string, Channel> } {
  if (mode === 'sport') return { choices: channels.filter((c) => isIsraeliSport(c.name, c.group, c.tvgId)), replacedBy: new Map() };
  const news = channels.filter((c) => isIsraeliNews(c.name, c.group, c.tvgId));
  const stations = new Map<string, Channel>();
  for (const c of news) {
    if (!freeSourceIds.has(c.sourceId)) continue;
    const st = newsStation(c.name, c.tvgId);
    if (st && !stations.has(st)) stations.set(st, c);
  }
  const replacedBy = new Map<string, Channel>();
  const choices = news.filter((c) => {
    if (freeSourceIds.has(c.sourceId)) return true;
    const st = newsStation(c.name, c.tvgId);
    const copy = st ? stations.get(st) : undefined;
    if (!copy) return true;
    replacedBy.set(c.key, copy);
    return false;
  });
  return { choices, replacedBy };
}

export class PanelSession {
  readonly slots = signal<Slot[]>([]);
  readonly mode = signal<Mode>('sport');
  readonly count = signal<2 | 4>(PAIR);
  readonly focused = signal(0);
  readonly expanded = signal(false);
  /** The panel the channel picker is choosing for; null while it is closed. */
  readonly picking = signal<number | null>(null);
  /** The panel whose menu is open; null while none is. */
  readonly menu = signal<number | null>(null);
  /** The channels on offer, in playlist order; null while they are still being read. */
  readonly choices = signal<Channel[] | null>(null);
  readonly paused = signal(false);
  /** Whether a mode is the screen and in front of the viewer: what keeps the headlines fetching. */
  readonly live = signal(false);

  active = false;
  private suspended = false;
  private epoch = 0;

  constructor(private readonly host: SessionHost) {}

  /** The current opening's number, for [offer]. */
  get opening(): number {
    return this.epoch;
  }

  private ensurePanels() {
    if (this.slots.value.length) return;
    const othersPlaying = (id: number) => () =>
      this.slots.value.some((s) => s.panel.id !== id && s.channel !== null && s.panel.state.value.status === 'playing');
    this.slots.value = Array.from({ length: SLOTS }, (_, id) => ({ channel: null, panel: this.host.createPanel(id, othersPlaying(id)), flashes: false }));
  }

  /** Opens [mode] on an empty grid of [panels] while the channels are read; returns the opening's number for [offer]. */
  begin(mode: Mode, panels: number): number {
    if (this.active) return this.epoch;
    this.ensurePanels();
    this.active = true;
    this.epoch++;
    this.suspended = false;
    batch(() => {
      this.mode.value = mode;
      this.expanded.value = false;
      this.picking.value = null;
      this.menu.value = null;
      this.choices.value = null;
      this.focused.value = 0;
      this.paused.value = false;
      this.count.value = panelCount(panels);
      this.slots.value = this.slots.value.map((s) => {
        s.panel.release();
        return { channel: null, panel: s.panel, flashes: false };
      });
      this.refresh();
    });
    return this.epoch;
  }

  /**
   * The channels the mode may show, and the ones it showed last time, by panel, with the headlines
   * at [flashesAt] (-1 for none). A grid with nothing to come back to opens the picker on the
   * first panel, and in News mode puts the headlines in the second, the one panel that needs no
   * connection.
   */
  offer(opening: number, choices: Channel[], restored: Array<Channel | null | undefined>, flashesAt = -1) {
    if (!this.active || opening !== this.epoch) return;
    this.choices.value = choices;
    // Something was already chosen while the list was being read; that choice stands.
    if (this.slots.value.some((s) => s.channel !== null || s.flashes)) return;
    const count = this.count.value;
    const news = this.mode.value === 'news';
    const nothingBack = !restored.slice(0, count).some(Boolean) && !(flashesAt >= 0 && flashesAt < count);
    const headlines = !news ? -1 : nothingBack ? 1 : flashesAt;
    const seen = new Set<string>();
    batch(() => {
      this.slots.value = this.slots.value.map((s, i) => {
        const flashes = i === headlines && i < count;
        const candidate = restored[i];
        const channel = candidate && !flashes && i < count && !seen.has(candidate.key) ? candidate : null;
        if (channel) {
          seen.add(channel.key);
          if (!this.suspended) s.panel.play(channel);
        }
        return { channel, panel: s.panel, flashes };
      });
      const first = this.slots.value.findIndex((s) => s.channel !== null);
      const empty = this.slots.value.slice(0, count).findIndex((s) => s.channel === null && !s.flashes);
      this.focused.value = first >= 0 ? first : empty >= 0 ? empty : 0;
      if (first < 0 && empty >= 0 && choices.length) this.picking.value = empty;
      this.applyAudible();
      this.refresh();
    });
  }

  /** News mode's two or four panels, changed while it is on screen (ADR-045). */
  setPanels(panels: number) {
    const target = panelCount(panels);
    const count = this.count.value;
    if (!this.active || target === count) return;
    batch(() => {
      if (target < count) {
        const current = this.slots.value;
        const filled = current.slice(0, count).map((s) => s.channel !== null || s.flashes);
        const kept = keptPanels(filled, this.focused.value, target);
        const order = [...kept, ...current.map((_, i) => i).filter((i) => !kept.includes(i))];
        this.slots.value = order.map((from, i) => {
          const slot = current[from];
          if (i < target) return slot;
          slot.panel.release();
          return { channel: null, panel: slot.panel, flashes: false };
        });
        this.focused.value = 0;
        this.expanded.value = false;
      }
      this.count.value = target;
      this.picking.value = null;
      this.menu.value = null;
      this.applyAudible();
      this.persist();
      this.refresh();
    });
  }

  /** Leaves the mode: every panel stops and lets go of its decoder and its connection. */
  close() {
    if (!this.active) return;
    this.active = false;
    this.suspended = false;
    batch(() => {
      this.slots.value.forEach((s) => s.panel.release());
      this.expanded.value = false;
      this.picking.value = null;
      this.menu.value = null;
      this.refresh();
    });
  }

  /** The channel being heard, which plays on full screen when the mode is left. */
  focusedChannel(): Channel | null {
    if (!this.active) return null;
    return this.slots.value[audibleSlot(this.slots.value, this.focused.value)]?.channel ?? null;
  }

  focus(slot: number) {
    if (slot < 0 || slot >= this.count.value || this.focused.value === slot) return;
    this.focused.value = slot;
    this.applyAudible();
  }

  /** Fills the screen with the panel in focus — a channel or the headlines — or puts the grid back. */
  setExpanded(value: boolean) {
    const entry = this.slots.value[this.focused.value];
    const shown = !!entry && (entry.channel !== null || entry.flashes);
    this.expanded.value = value && this.active && shown;
  }

  openPicker(slot: number) {
    if (!this.active || slot < 0 || slot >= this.count.value) return;
    batch(() => {
      this.menu.value = null;
      this.picking.value = slot;
    });
  }

  closePicker() {
    this.picking.value = null;
  }

  /** A panel's menu; a panel with nothing in it has none. */
  openMenu(slot: number) {
    const entry = this.slots.value[slot];
    if (this.active && entry && slot < this.count.value && (entry.channel !== null || entry.flashes)) this.menu.value = slot;
  }

  closeMenu() {
    this.menu.value = null;
  }

  /**
   * Puts [channel] in [slot]. A channel already on screen moves rather than opening a second
   * connection to the same stream: the two panels swap, players and all.
   */
  assign(slot: number, channel: Channel) {
    const current = this.slots.value;
    if (!this.active || slot < 0 || slot >= this.count.value) return;
    const from = current.findIndex((s) => s.channel?.key === channel.key);
    const next = current.slice();
    if (from >= 0 && from !== slot) {
      next[slot] = current[from];
      next[from] = current[slot];
    } else if (from < 0) {
      const panel = current[slot].panel;
      if (!this.suspended) panel.play(channel);
      next[slot] = { channel, panel, flashes: false };
    }
    batch(() => {
      this.slots.value = next;
      this.picking.value = null;
      this.menu.value = null;
      this.focused.value = slot;
      this.applyAudible();
      this.persist();
      this.refresh();
    });
  }

  /** News mode's headlines in [slot]; one panel of them at most, so another panel with them swaps. */
  showFlashes(slot: number) {
    const current = this.slots.value;
    if (!this.active || this.mode.value !== 'news' || slot < 0 || slot >= this.count.value) return;
    const from = current.findIndex((s) => s.flashes);
    const next = current.slice();
    if (from >= 0 && from !== slot) {
      next[slot] = current[from];
      next[from] = current[slot];
    } else if (from < 0) {
      current[slot].panel.release();
      next[slot] = { channel: null, panel: current[slot].panel, flashes: true };
    }
    batch(() => {
      this.slots.value = next;
      this.picking.value = null;
      this.menu.value = null;
      this.focused.value = slot;
      this.applyAudible();
      this.persist();
      this.refresh();
    });
  }

  /** Empties [slot]; its player stops and lets go of its connection. */
  clear(slot: number) {
    const current = this.slots.value;
    const entry = current[slot];
    if (!entry) return;
    entry.panel.release();
    const next = current.slice();
    next[slot] = { channel: null, panel: entry.panel, flashes: false };
    batch(() => {
      this.slots.value = next;
      this.picking.value = null;
      this.menu.value = null;
      if (this.focused.value === slot) this.expanded.value = false;
      this.applyAudible();
      this.persist();
      this.refresh();
    });
  }

  /**
   * The next or previous channel on offer, in the panel with the remote. Channels already in
   * another panel are stepped over, so a press never swaps two panels.
   */
  step(delta: number) {
    const choices = this.choices.value;
    if (!choices || !this.active || !choices.length || this.picking.value !== null || delta === 0) return;
    const slot = this.focused.value;
    const current = this.slots.value;
    if (current[slot]?.flashes) return;
    const elsewhere = new Set(current.filter((_, i) => i !== slot).map((s) => s.channel?.key).filter(Boolean));
    const own = current[slot]?.channel?.key;
    const at = choices.findIndex((c) => c.key === own);
    const base = at >= 0 ? at : delta > 0 ? -1 : choices.length;
    for (let k = 1; k <= choices.length; k++) {
      const n = choices.length;
      const candidate = choices[(((base + delta * k) % n) + n) % n];
      if (!elsewhere.has(candidate.key)) {
        if (candidate.key !== own) this.assign(slot, candidate);
        return;
      }
    }
  }

  /** Starts a failed panel's channel again from the top. */
  retry(slot: number) {
    const entry = this.slots.value[slot];
    if (entry?.channel && this.active && !this.suspended) entry.panel.play(entry.channel);
  }

  /** The remote's play/pause: every panel at once. */
  togglePause() {
    if (!this.active) return;
    const value = !this.paused.value;
    this.paused.value = value;
    this.slots.value.forEach((s) => s.panel.setPaused(value));
    this.refresh();
  }

  /** The app is out of sight: every panel lets go, the channels stay for [resume]. */
  suspend() {
    if (!this.active || this.suspended) return;
    this.suspended = true;
    this.slots.value.forEach((s) => s.panel.release());
    this.refresh();
  }

  resume() {
    if (!this.active || !this.suspended) return;
    this.suspended = false;
    this.paused.value = false;
    this.slots.value.forEach((s) => {
      if (s.channel) s.panel.play(s.channel);
    });
    this.applyAudible();
    this.refresh();
  }

  /** Silences every panel but the one heard, then lets that one speak. */
  private applyAudible() {
    const slots = this.slots.value;
    const target = audibleSlot(slots, this.focused.value);
    slots.forEach((s, i) => i !== target && s.panel.setAudible(false));
    slots[target]?.panel.setAudible(true);
  }

  private persist() {
    this.host.persist(this.mode.value, this.slots.value.map((s) => (s.flashes ? FLASHES_ID : s.channel?.key ?? null)));
  }

  private refresh() {
    this.live.value = this.active && !this.suspended;
  }
}
