import type { Channel, Programme } from './model';

/**
 * Reading the guide at a moment in time. Offsets shift the question, not the stored programmes
 * (data/repo/EpgRepository.kt): a channel's own `tvg-shift` wins over the global offset.
 */

export function offsetMs(channel: Channel, globalOffsetMinutes: number): number {
  return (channel.tvgShiftMinutes || globalOffsetMinutes) * 60_000;
}

/** Index of the programme on air at [at] (already in guide time), or of the next one; -1 past the end. */
export function indexAt(list: Programme[], at: number): number {
  let lo = 0;
  let hi = list.length - 1;
  let candidate = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (list[mid].end <= at) {
      lo = mid + 1;
    } else {
      candidate = mid;
      hi = mid - 1;
    }
  }
  return candidate;
}

export interface Airing {
  now?: Programme;
  next?: Programme;
}

/** What is on now and next, with the times moved into wall-clock time by the channel's offset. */
export function nowNext(list: Programme[] | undefined, now: number, shift: number): Airing {
  if (!list?.length) return {};
  const i = indexAt(list, now - shift);
  if (i < 0) return {};
  const move = (p: Programme | undefined) => (p && shift ? { ...p, start: p.start + shift, end: p.end + shift } : p);
  const first = list[i];
  if (first.start <= now - shift) return { now: move(first), next: move(list[i + 1]) };
  return { next: move(first) };
}

export function progress(p: Programme | undefined, now: number): number {
  if (!p || p.end <= p.start) return 0;
  return Math.min(1, Math.max(0, (now - p.start) / (p.end - p.start)));
}

const clockFormat = new Intl.DateTimeFormat('he-IL', { hour: '2-digit', minute: '2-digit', hour12: false });
const dayFormat = new Intl.DateTimeFormat('he-IL', { weekday: 'long', day: 'numeric', month: 'numeric' });

export const clock = (ms: number) => clockFormat.format(ms);
export const dayLabel = (ms: number) => dayFormat.format(ms);

/**
 * A start–end range that reads start-then-end in a Hebrew line: isolated left to right, or the
 * neutral dash takes the paragraph's direction and the two times swap (core/TimeModel.kt).
 */
export const clockRange = (start: number, end: number) => `⁦${clock(start)} – ${clock(end)}⁩`;

export function startOfDay(ms: number): number {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}
