# 06 — Licensing and Tiers

## 6.1 Benchmark model (for reference)

The reference product is freemium with a hard gate: the free tier is effectively a trial
(one playlist, basic guide, search), and nearly every power feature sits behind Premium.
A licence covers up to **5 devices** and is activated through a **separate companion
phone app** — necessary because devices such as Fire TV have no Play billing. Pricing
observed in mid-2026 was roughly $2.99/month, $9.99/year (with a 7-day trial), or a
$33.99 one-time lifetime licence; figures vary by region and over time.

Observed gating (high confidence): multiple playlists, recording, catch-up, multi-view,
picture-in-picture, custom groups, favourites management, parental PIN, custom
user-agent, multiple EPG sources, backup/restore, auto frame rate, manual channel
sorting, startup channel, audio passthrough.
Lower confidence / version-dependent: exact free EPG day-window, whether the free tier
carries ads, and whether external-player support is gated.

## 6.2 Our proposed tiering

Goal: a free tier that is genuinely usable, so the app is judged on quality rather than
on how quickly it nags; a paid tier that gates *scale and convenience*, not correctness.

| Feature | Free | Paid |
|---|---|---|
| Playlists | 2 | Unlimited |
| Full guide, search, favourites, custom groups, hide/reorder | ✓ | ✓ |
| Hardware/software decoder toggle, buffer settings, aspect ratio | ✓ | ✓ |
| Audio/subtitle track selection, audio delay, passthrough | ✓ | ✓ |
| Custom user-agent and headers | ✓ | ✓ |
| Parental controls / PIN | ✓ | ✓ |
| Catch-up / archive | ✓ | ✓ |
| Local backup / restore | ✓ | ✓ |
| Recording (instant + scheduled) | 1 concurrent | Unlimited + series rules |
| Multi-view | — | ✓ |
| Picture-in-Picture | — | ✓ |
| Auto Frame Rate | — | ✓ |
| Multiple EPG sources with priority | — | ✓ |
| Cloud sync across devices | — | ✓ |
| Profiles (beyond one) | — | ✓ |
| Ads | none | none |

Rationale: putting correctness features (decoder toggle, user-agent, parental controls)
behind a paywall is what makes competitors feel hostile. Charge for *more* — more
playlists, more screens, more devices, more automation — not for *working*.

## 6.3 Entitlement mechanics

- One account, N devices (default 5), managed from an in-app device list with the ability
  to deactivate a device remotely.
- Entitlement checked at launch and cached; **grace period of at least 14 days offline**
  so a network hiccup never blackens someone's TV.
- Degradation on expiry is graceful: paid features become read-only/disabled, but
  playlists, favourites and recordings remain intact and accessible.
- Restore purchases must work on a fresh install with no support ticket.

## 6.4 Purchase and activation flows

1. **Play-billing devices** (Play Store Android TV, Google TV, phone): purchase in-app.
   Do not force users through a companion app — the benchmark's companion-only flow is a
   documented source of confusion.
2. **Non-Play devices** (Fire TV, sideloaded): sign in with the account; entitlement
   arrives from the server. A web checkout and a phone companion path exist as
   alternatives, never as the only route.
3. Trial: 7 or 14 days, full features, no card required if the store allows it.

## 6.5 Accounts and privacy

- Account is required only for paid entitlement and cloud sync. The free tier must be
  fully usable with no account.
- Playlist credentials are user secrets: encrypted at rest on device; if synced, encrypted
  client-side with a key derived from the user's password, so the server never sees them.
- No telemetry by default; opt-in crash reporting with a clear description of what is sent.
