# ADR-064 — The car's radio folders are the radio menu's shelves

**Status:** accepted 2026-11-10, built in 0.30.0. Brings ADR-050's shelves to Android Auto
(ADR-015), which ADR-050 had left on `RadioCategory`.

## Context

On 2026-11-10 the owner asked which of the past days' changes apply to Android Auto as well, and to
build those that do. The car is a media browser for the radio playlist only (ADR-015): no video,
no guide, no YouTube, and a screen whose layout is the car's, not the app's. Of the changes since
2026-11-04:

- **Turning off or deleting a playlist (0.24.0)** already reached the car: `RadioBrowseTree`
  offers no stations when the radio playlist is off or gone, and `ensureRadioPlaylist` does not
  bring back a built-in the viewer deleted.
- **The radio by region (ADR-050; the television's strip in 0.21.8)** had not. The car's folders
  were still `RadioCategory`, the feed's `group-title`s, which are genres now: Favourites, a
  "regional" folder with two stations, two genre folders, and "Other" with nearly all the rest.
  The regions never showed, the problem ADR-050 fixed in the menu.
- Everything else of those days is video, the screen's own design, YouTube, accounts or updates,
  none of which the car has.

A car shows at most four tabs at the root in most cases
(`BROWSER_ROOT_HINTS_KEY_ROOT_CHILDREN_LIMIT`, four), and may drop or hide root items past the
hints. The menu's shelves — national, north, centre, Jerusalem, south, other regions, and the
genres — are more than that.

## Decision

The root holds four folders at most, in the menu's order:

1. **Favourites**, always, as before.
2. **National** (ארצי) — the stations whose name places them nowhere, in the provider's order.
3. **Regions** (אזורים) — every station a region's shelf holds, under a heading per region in
   `RadioRegion`'s order: north, centre, Jerusalem, south, other regions. The heading is the car's
   group-title hint, and the region is also each station's subtitle.
4. **Genres** (סגנונות) — the genre shelves, under a heading per genre, alphabetically. A station
   whose name says a genre is here as well as under its place, as in the menu.

A folder with no station is not listed. A tapped station's queue is the folder of its place
(national, or the regions in order), whichever folder it was tapped in, because the car hands back
only the station's id; skip-next then walks to the station below and on into the next region.

The national folder keeps its media id, so a car that remembered that tab finds it again.

## Alternatives rejected

- **A tab per region.** Seven tabs or more with the genres; past the four a car shows.
- **A regions folder holding a folder per region.** One more tap while driving, for lists of one
  to eight stations, where a heading does the same work.
- **Keeping `RadioCategory` in the car.** Leaves the car without the regions the menu shows.

## Consequences

- The car's folders, the phone's chips and the television's strip are one split, from
  `RadioCategoryClassifier.shelves`.
- `RadioCategory` is now read by nothing in the car; `classify` stays for the phone player's station strip, which still sorts by it (national, regional, everything else).
- Like the rest of FR-AUTO, this is not exercised by the JVM tests beyond the folder arithmetic
  (`CarFoldersTest`); it needs a phone with Android Auto and a car or the Desktop Head Unit.
