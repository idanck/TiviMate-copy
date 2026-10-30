# ADR-050 — The radio menu is by region

**Status:** accepted. Decided by the owner on 2026-10-30: *radio stations by region in the menu,
the region taken from the station's name.* Narrows ADR-015's categories in the radio list's menu
only; Android Auto's folders and the radio screen's station strip still sort by `RadioCategory`.

## Context

The radio list (`ChannelListScreen` repointed at the bundled playlist, on the television's group
column and the phone's chips alike) built its category menu from `RadioCategoryClassifier.classify`:
national or regional by the feed's `group-title`, a genre when the name says one, and "other" for
the rest. ADR-015 was written when the feed had two `group-title`s, "רדיו ישראלי" and "רדיו אזורי".
It now files its stations by genre — of the 98 on 2026-10-30, "מוזיקה" 59, "מוסיקה" 8, "שיחות" 6,
"חדשות" 5, "אקטואליה" 5, "ים תיכוני" 3, and ten more with one or two each — and only two by place
("אזורי"). So the menu read "אזורי" (2), "מזרחי" (2) and "אחר" (93): one shelf holding nearly every
station. ("יווני" never appeared: the feed's Greek station is "רדיו כחול יוון", which the keyword
"יווני" does not match.)

The classifier already had a name-based heuristic for where a station broadcasts
(`RadioCategoryClassifier.region`, `RadioRegion`), used until now only to split the car's regional
folder.

## Decision

**1. The menu is one flat list:** national (ארצי), north (צפון), centre (מרכז), Jerusalem
(ירושלים), south (דרום), then the genres in alphabetical order, as before. A shelf with no station
on it is not listed. The shelves are `RadioShelf`; the list is `RadioCategoryClassifier.menu`, a
pure function the screen labels.

**2. Every station is on exactly one shelf of the first part, by its name:**

- the region its name names (`region`), if it names one;
- otherwise, if the feed files it as regional, **"other regions"** (אזורים נוספים, the label the
  car already uses for `RadioRegion.UNKNOWN`), listed after the south — the classifier's region set
  has this fifth member, and a station the feed calls regional is not called national for want of
  a place name;
- otherwise **national**. In this feed a name that places a station nowhere belongs to one of the
  country's own stations (Kan, Galgalatz, Galei Tzahal, 103FM, 99FM, 100FM…) or to an internet
  station heard the same everywhere, which is what the menu means by national.

**3. A genre is a second shelf, beside the first.** A station whose name says a genre ("מזרחי",
"יווני") is on that shelf as well as on its place's. Selecting a shelf lists every station on it
(`AppViewModel.loadChannels`, through `RadioCategoryClassifier.shelves`).

**4. There is no "other" shelf.** Every station is on one of the first part's, so a shelf of the
stations on none would always be empty.

**5. The keyword lists, from the feed's own names** (CLAUDE.md: a heuristic, extended where the
names need it):

- north: "יזרעאל" (in place of "עמק יזרעאל") and "גולן" / "golan" — "רדיו קול יזרעאל 106FM",
  "רדיו קול הגולן";
- centre: "רדיו תשעים", the feed's spelling of the Radio 90 the list already placed in the centre
  as "רדיו 90" — as a phrase, so a station for the nineties is not;
- the Greek genre: "יוון" added beside "יווני" (the country, final nun, is not inside the adjective).

The Greek keyword reaches the car too, which gains a "יווני" folder. The place names change nothing
there today: the car splits by place only the two stations the feed files as regional. The radio
screen's strip, whose chips are national, regional and everything else, is unchanged. The web
version's port (`web/src/data/classify.ts`) carries the same lists and the same shelves, with the
same fixtures; its radio screen keeps its own layout, and gains the Greek shelf by the keyword.

**What the feed gives, with these rules** (97 stations; the Russian one is excluded everywhere):

| Shelf | Stations |
|---|---|
| ארצי | 76 |
| צפון | 8 — חיפה, קול רגע, קול יזרעאל, קול הכנרת, קול הגליל העליון, צפון 104.5, קול הגולן, קריות |
| מרכז | 7 — רדיו תשעים, לב המדינה, 102 תל אביב, קול נתניה, קול רמת השרון, קול המרכז, סלסה תל אביב |
| ירושלים | 1 — רדיו ירושלים |
| דרום | 3 — רדיו דרום, אילת 102FM, דרום 101.5 |
| אזורים נוספים | 2 — קול השפלה, קול גלים (filed "אזורי", no place in the name) |
| יווני | 1 — כחול יוון |
| מזרחי | 2 — נושמים מזרחית, קצב מזרחית |

Names the heuristic places by their words but may not broadcast there, and names that place
nothing though the station is local, are left to the feed (CLAUDE.md, "Bundled Israeli radio"):
"רדיו סלסה תל אביב" is an internet station named after the city; "קול השפלה" could be the centre or
the south; "רדיו אשל הנשיא" (a school station in the Negev) and "קול האוניברסיטה (הבינתחומי)"
(Herzliya) say nothing of where they are and sit under national.

## Alternatives rejected

- **The places only, under one "regional" heading with the regions inside it.** The owner asked
  for one flat list.
- **Stations no name places, under their genre only.** The genres are two name keywords, so almost
  every station would have been on no shelf at all, reachable only through "All".
- **The feed's `group-title`s as the genres** ("מוזיקה", "שיחות", "חדשות"…). Not asked for, and it
  would put "מוזיקה" and "מוסיקה" side by side until the feed settles one spelling. Left open.
- **Changing the feed.** The owner's decision: the region comes from the name, with no change
  upstream.

## Consequences

- The radio list's menu, on the television and the phone, lists places first and genres after;
  the "אזורי" and "אחר" entries are gone from it. `ChannelListState.selectedRadioShelf` replaces
  `selectedRadioCategory`, and `AppViewModel.selectRadioShelf` replaces `selectRadioCategory`.
- The car's folders and the radio screen's strip still sort by `RadioCategory` (ADR-015).
- `RadioCategoryClassifierTest` pins the new keywords, each station's shelf, the menu's order and
  that an empty shelf is left out, with the feed's regional stations as fixtures; `web/test/data.test.ts`
  pins the port.
