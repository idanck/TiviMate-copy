# ADR-016 — The television's root is a home screen, not bare video

**Status:** accepted. The *shape* of the home screen described here — a rail beside a boxed hero
with its caption alongside — is superseded by ADR-020, which makes the picture a full-bleed banner
and collapses the rail. Everything else below still holds: that the television's root is a home
screen, what the rails are, and that video never stops. "Back from any browse surface rises to
Home" is amended by ADR-036 for two routes: the channel list and the guide, when they were opened
from fullscreen video, return to the video instead.

## Decision

The Android TV shell gains a `HOME` overlay — a navigation rail down one side, the live picture
as a hero with its now/next guide text beside it, and horizontal rails of cards below — and it
becomes the shell's root: `StartBehaviour` gains a `HOME` value and defaults to it, and Back from
any browse surface rises to Home rather than dropping to bare video.

This reverses a decision `docs/03-ui-navigation.md` §3.2 stated plainly: *"fullscreen video is the
home screen, not a menu. The app should boot into video, not into a dashboard."* That section is
rewritten alongside this record.

Two things are unchanged, and they are what make the reversal cheap:

- **The video never stops.** The home screen docks the one `PlayerSurface` the shell owns into
  its hero slot, through the same `docksPlayer` / `onDockBounds` mechanism the guide has used
  since ADR-013's picture-in-guide. Nothing re-tunes, and `OverlayScaffold` already knows not to
  draw a scrim over a docking overlay.
- **Every destination stays reachable by D-pad, OK and Back alone** (NFR-UX-01). The channel
  list's group column keeps its destinations; the rail is a second, more visible route to the
  same places.

## Why

The group column was the ten-foot navigation: a column of words inside the channel list. It
satisfied NFR-UX-01 and nothing else. Every commercial television app the owner compares this
one against opens on artwork — what is on now, what was being watched, what is worth starting —
and reaches the same destinations from a rail that is visible without opening anything first.

Booting into video made sense while the app was a player someone had already chosen a channel
in. It makes less sense for a two-playlist appliance whose whole content set is a few dozen
Israeli channels and a radio list: there, the first question is "what is on", and video-first
answers it only for the one channel that happened to be last.

The setting remains. Anyone who wants the old behaviour picks *last channel* and gets it.

## What the rails are, and are not

Six, in a fixed order, each dropped entirely when it has no cards: Featured, Continue watching,
Channels, Radio, Favourites, Starting soon. They are assembled by `HomeRails.build`, a pure
function over already-fetched lists, so the rules — an empty rail is not drawn, every rail is
capped, a card carries its own now-programme — are unit-tested rather than trusted. That matters
more than usual here: the environment this app is developed in cannot build it (see `CLAUDE.md`),
so anything that can be checked without a device is.

There is deliberately **no on-demand destination and no VOD rail**. The Xtream client exists in
the code and is unused in this deployment; a rail that opens an empty catalogue is worse than no
rail. Scope stays where ADR-013 and ADR-015 put it: the two bundled Israeli playlists.

## The featured rail ships with no content

`Settings.featuredFeedUrl` is empty by default, and empty means the rail does not exist — not
that it is empty on screen. Pointing it at a JSON document (`data/featured/FeaturedFeed.kt`)
makes the rail appear. Nothing is bundled and no source is hardcoded, because the owner has not
decided what to promote there yet; what is being decided now is only that the mechanism exists,
in the same shape as the update manifest it is modelled on (ADR-012): strict parse, `Outcome`,
the raw document cached verbatim in preferences, refreshed by the existing `RefreshWorker`.

A promoted item names a channel by reference — a tvg-id, a guide id, a name or a number — and an
item that does not resolve to a channel this device has is dropped rather than drawn. On a remote,
a tile that swallows OK is indistinguishable from a frozen screen, and resolving at build time
rather than at press time is also what makes one feed safe to serve to installs whose playlists
differ. A card that opens something other than a channel is a later addition, and would need
somewhere to open it.

One deliberate difference from that manifest: a malformed *item* is dropped and the rest of the
feed still renders, and a feed with an empty `items` array is a valid document rather than a
failure. The update manifest decides which binary is installed over the running app and refuses
anything it cannot vouch for. This one decides which posters appear on a shelf, and publishing
no items is how the rail gets turned off.

## Alternatives rejected

- **Keeping video as the root and adding Home behind a key.** Every remote key worth binding is
  bound (ADR-014), and a root nobody can find is not a root.
- **`androidx.tv:tv-material3`.** It would bring `TvLazyRow`, `Carousel` and a focus system with
  scale animations. It would also bring a second Material implementation into a Compose-Material3
  app, for a screen whose rails are a `LazyRow` of bordered tiles. The existing focus conventions
  — border and background, never `focusable()` beside `clickable()` — are followed instead.
- **A rail that expands on focus.** The Google TV pattern, and it reflows every rail on the
  screen each time focus crosses into it. Labels are always visible instead, as in the design
  this was modelled on.
- **A hero that follows the focused card.** It would put the video of one channel beside the
  text of another. The hero shows what is playing; each card carries its own caption.

## Consequences

- `TvOverlay` has a value whose absence from the `when` in `TvRoot` would compile and render
  nothing — `else -> Unit` is still there. The branch and the value were added together.
- `dockBounds` is now cleared per overlay change rather than when docking stops, because moving
  between two docking surfaces would otherwise leave the picture in the previous one's slot.
- The phone does **not** get a Home tab. Its bottom bar is already at Material's five-item limit,
  Live is already its home tab, and the hero's whole point is a docked picture the phone
  deliberately does not decode while browsing (ADR-011). `HomeRails` is shell-agnostic if that
  changes.
- Existing installs that never chose a start behaviour will open on Home after this update. That
  is the intent; the setting is one screen away for anyone it surprises.
