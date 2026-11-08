import { radioShelves, type RadioShelf } from '../data/classify';

/**
 * The radio menu's shelves as keys (ui/RadioScreen.tsx, state/actions.ts `radioFilter`). Pure:
 * `data/classify.ts` holds the rules and stays the computer version's byte for byte.
 */
export const shelfKey = (s: RadioShelf): string =>
  s.kind === 'NATIONAL' ? 'NATIONAL' : s.kind === 'REGIONAL' ? `REGIONAL:${s.region}` : `GENRE:${s.label}`;

/** Whether a station is on the shelf with this key: its place's, or its genre's (ADR-050). */
export function onShelf(station: { group?: string; name: string }, key: string): boolean {
  return radioShelves(station.group, station.name).some((s) => shelfKey(s) === key);
}
