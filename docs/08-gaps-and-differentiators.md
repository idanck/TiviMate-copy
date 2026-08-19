# 08 — Gaps in the Benchmark, and Our Differentiators

These are the recurring complaints about the reference product, gathered from store
reviews, forums and community guides. Each one is an explicit product opportunity, and
each maps to requirements in `requirements/functional-requirements.md`.

| # | Weakness of the benchmark | Our response | Priority |
|---|---|---|---|
| 1 | No touch-optimized phone/tablet UI; explicitly TV-only in design | A first-class touch UI with gestures, portrait/landscape, tablet two-pane | **MUST** |
| 2 | No cross-device sync — only manual backup files | Opt-in, client-side-encrypted account sync of favourites, groups, ordering, resume points, settings | **SHOULD** |
| 3 | Recording only works if the app is open and the device awake; cannot wake the device | Foreground service + exact alarms + boot re-arm + wake/WiFi locks; honest warning where the platform genuinely cannot wake | **SHOULD** |
| 4 | Series recording silently records nothing when the EPG lacks series metadata | Detect and warn at scheduling time; offer a repeat-rule fallback | **SHOULD** |
| 5 | VOD/series browsing is serviceable but unremarkable; some VOD fails to play | Proper VOD browser with artwork, resume, continue-watching; automatic decoder fallback and one-tap external-player handoff | **SHOULD** |
| 6 | Unbounded cache growth on some TVs | Hard cache budget with LRU eviction; visible cache size; one-tap clear | **MUST** |
| 7 | Per-channel EPG offsets can revert; DST shifts the guide | UTC-internal model, sticky per-channel offsets, DST regression tests | **MUST** |
| 8 | Channel/EPG ID mismatches leave channels with no guide | Multi-stage matcher + a manual mapping UI + a match-quality diagnostic | **MUST** |
| 9 | Purchase/activation via a companion app confuses users | In-app purchase wherever billing exists; companion/web only as an alternative for Fire TV and sideloads | **SHOULD** |
| 10 | Correctness features paywalled (decoder toggle, user-agent, parental PIN) | Keep correctness free; charge for scale and convenience | **MUST** |
| 11 | Support responsiveness criticized | In-app diagnostic report with redacted credentials, so a support ticket starts with facts | **COULD** |
| 12 | Buffering regressions after updates | Buffer values exposed numerically; a "revert to previous playback settings" action; staged rollouts | **SHOULD** |
| 13 | Android-only — no iOS/Roku/webOS/desktop | Out of scope for v1, but keep domain and data layers platform-agnostic (Kotlin Multiplatform-friendly) so a second platform is not a rewrite | **COULD** |
| 14 | Weak external-subtitle support | Sidecar `.srt`/`.ass` for VOD; full subtitle styling | **COULD** |
| 15 | No cast-from-phone story | Chromecast sender support from the mobile app | **COULD** |
| 16 | RTL/Hebrew layout is not a strength | Full RTL support with a correctly mirrored guide — a real advantage in this market | **SHOULD** |

## Positioning

The benchmark wins on **speed and polish of the TV experience**. Matching that is the
price of entry, not a differentiator. Our wedge is:

1. **It works on the phone too** — properly, not as a compromise.
2. **It remembers you across devices** — sync, not backup files.
3. **Recordings actually happen** — the DVR is dependable.
4. **It tells you what went wrong** — diagnostics instead of a black screen.

Anything that does not serve one of those four should be scheduled after the things that do.
