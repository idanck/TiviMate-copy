# ADR-038 — The screensaver's news sources can be picked from a short list

**Status:** accepted. Amends FR-UI-33 (ADR-037): Settings ▸ Appearance now also offers a list of
suggested sources, each added with one press. The list of *active* sources is still empty by
default. Decided by the owner on 2026-10-16.

## Context

ADR-037 left the screensaver's news sources empty by default, because this app installs itself on
other people's televisions and which newsroom scrolls past on one is its owner's to choose. That
reason stands. What it left behind is the only way in: typing a feed address — an RSS path nobody
knows by heart — with a remote.

## Decision

Settings lists **suggested sources** below the add row: each a name and whether it is breaking
news or articles, added to the active list by pressing it (`SuggestedNewsSources`). A suggestion
already in the list is not shown again; the whole block hides once the list holds its eight.

The suggestions were checked on 2026-10-16 to answer an HTTPS request from an Israeli connection,
sent with the app's own User-Agent, with a document `NewsFeed` reads:

| Name | Kind | Address |
|---|---|---|
| ynet | breaking news | `https://www.ynet.co.il/Integration/StoryRss1854.xml` |
| Walla | breaking news | `https://www.walla.co.il/rss/feed/news/breaking-news` |
| Maariv | breaking news | `https://www.maariv.co.il/Rss/RssFeedsMivzakiChadashot` |
| i24NEWS | breaking news, without times | `https://api.i24news.tv/v2/he/news` |
| N12 | articles | `https://rcs.mako.co.il/rss/news-israel.xml` |
| Israel Hayom | articles | `https://www.israelhayom.co.il/rss.xml` |
| Arutz Sheva | articles | `https://www.inn.co.il/Rss.aspx` |
| Srugim | articles | `https://www.srugim.co.il/feed` |

## Alternatives rejected

- **Switching the suggestions on by default.** Reverses ADR-037 on every installation, which is
  the thing ADR-037 exists to prevent; the owner chose against it.
- **A backup file to restore, with no code.** Restoring is per device and replaces the rest of the
  settings in the file with it.
- **Kan (כאן) through Google News** (`news.google.com/rss/search?q=site:kan.org.il/…`). It works,
  but carries Kan's articles rather than its news flashes, through Google, not strictly in time
  order. The owner declined it.

## Consequences

- **Kan is not on the list.** It publishes no feed, and its news-flash page
  (`kan.org.il/newsflash`) answers 403 to anything that is not a full browser — with the app's
  User-Agent and with a browser's headers alike. Showing Kan's flashes would need something that
  loads the page as a browser and republishes it as a feed.
- **i24NEWS arrives without times.** Its list dates items in `startedAt`, a key `NewsFeed` does not
  read; the owner chose not to add it. The row says so before the source is added.
- **The addresses can go stale.** A newsroom that moves its feed turns its suggestion into a row
  that adds a source showing nothing — which the existing "no news source at this address" message
  already reports, since the add path is the same one a typed address takes.
