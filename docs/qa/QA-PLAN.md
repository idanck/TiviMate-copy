# QA plan — Shidur

What is checked before a build is trusted on the living-room TV, how, and what is deliberately left
to a real device. The automated part lives in `qa/` and runs as `.github/workflows/qa.yml`.

## 1. Levels

| Level | Where | What it proves | Gate |
|---|---|---|---|
| L0 — static | any checkout | version bump, manifest, exported components, strings parity | review |
| L1 — JVM | CI, `testDebugUnitTest` + `lintDebug` | parsers, queries, URL builders, update manifest logic | must be green |
| L2 — emulated operation | CI, `qa.yml` | the installed app, driven like a viewer, against a mock provider | no FAIL |
| L3 — real device | the TVs (`googletv-control`) and the phone | codecs, remotes, launcher, Auto, real providers | manual, per release |

## 2. The simulated environment (L2)

* **Devices:** Android TV emulator (API 34, 1080p, leanback) and phone emulator (API 34, Pixel 6).
  Both shells (`TvRoot`, `MobileRoot`) are exercised because they share `AppViewModel`.
* **Provider:** `qa/mock_provider/server.py`, reached from the emulator at `10.0.2.2:8080`. It serves
  an M3U (30 channels, 3 groups, Hebrew name, catch-up attributes, a 404 channel, a failover pair, a
  direct MPEG-TS channel, an 8-second slow start), XMLTV plain and gzipped, a 5,000-channel playlist,
  malformed and empty playlists, and a minimal Xtream Codes panel (live, VOD, series). Live HLS is a
  real sliding window over generated test patterns, so a frozen picture is detectable. Every request
  is logged to `mock/requests.jsonl`.
* **Real network** stays on, so the built-in Israeli channel and radio bootstrap and the update
  check run against the real `il-epg.idanck.com` / `updates.idanck.com`.
* **Driver:** `qa/driver/shidur_qa.py` — adb only: taps found through the accessibility tree, D-pad
  key events, the `shidur://channel/<id>` deep link, `svc wifi/data` for network loss, `am kill` for
  process death. State is verified from the app's own SQLite database (debug build, `run-as`),
  playback from screen motion between two captures and from AudioService's player list.

## 3. Scenarios and pass criteria

| ID | Area | Scenario | Passes when |
|---|---|---|---|
| QA-01 | install | Fresh install of the debug APK | `adb install` succeeds |
| QA-02 | performance | 3 cold starts + 1 warm start | worst cold start < 4 s (WARN < 8 s) |
| QA-03 | sources | First run bootstraps built-in TV channels and radio | both playlists exist with channels |
| QA-04 | navigation | Every top-level destination opens | all open, no crash |
| QA-05 | sources | Malformed, 404 and empty playlist URLs | no crash; failure is recorded, not reported as OK |
| QA-06 | sources | Add the mock M3U + XMLTV through the UI | 30 channels, ≥3 groups, ≥28 matched to the guide |
| QA-07 | sources | Add the mock Xtream account, test connection | "Connection succeeded", 12 live, 6 films + 2 series |
| QA-08 | playback | Deep link plays HLS and MPEG-TS | picture moves or audio is playing |
| QA-09 | playback | Channel up/down, digits, last channel, D-pad up | ≥3 distinct channels reached |
| QA-10 | playback | 404 stream, failover pair, slow start | 404 doesn't crash; failover ends up playing |
| QA-11 | navigation | 20 D-pad keys around the home screen | a focused node exists after every key |
| QA-12 | guide | Guide shows mock programmes; search finds "Sport 05" | both found |
| QA-13 | lifecycle | Home, return, Back ×4, process death, rotation, PiP | no sound behind the launcher without PiP; app survives |
| QA-14 | resilience | Network off 20 s during playback | picture back within 45 s with no user action |
| QA-15 | security | 7 malformed/hostile deep links | no crash |
| QA-16 | i18n | Hebrew per-app locale | Hebrew labels, no untranslated English labels |
| QA-17 | performance | 5,000-channel playlist, fast scroll | loads < 60 s, alive; jank recorded |
| QA-18 | stability | Monkey, 4,000 events, seed 4242 | no crash, no ANR |
| QA-19 | performance | Memory while playing | PSS < 350 MB (TV) / 450 MB (phone) |
| QA-20 | stability | Log audit over the whole run | no FATAL, no ANR; top error tags listed |

Statuses: **PASS**, **FAIL** (a defect or a broken criterion), **WARN** (works, outside the target, or
the evidence is ambiguous), **SKIP** (could not be exercised here), **INFO** (measurement only).

## 4. Left to a real device (L3)

Not provable on an emulator, checked by hand on the TVs before a release reaches everyone:

* Hardware decoders, 4K/HDR, frame-rate behaviour, AC3 passthrough (FR-PLB-02/03/09/15).
* The physical remote: long-OK menu, Menu key, voice key; the emulator's D-pad is only a proxy.
* Google TV launcher integration: Watch Next row, banner, the in-app self-update install (ADR-012).
* Android Auto radio (ADR-015) — needs the Desktop Head Unit or a car.
* Real provider panels and Israeli streams geo-restricted to Israel (the CI runner is abroad).
* Recording to storage over hours, scheduled-recording alarms across reboots.

## 5. Running it

* Push a change under `qa/` or run **QA (emulators)** from the Actions tab.
* Evidence: the run's artefacts, and the `qa-results` branch (`tv/`, `phone/`, `build/`), each with
  `report.md`, `report.json`, `screenshots/`, `ui/`, `logcat.txt`, `mock/requests.jsonl`.
* The mock provider runs anywhere: `python3 qa/mock_provider/server.py <media> <logs> 8080` after
  `qa/mock_provider/make_media.sh <media>` — useful for pointing a real TV at it on the home network.
