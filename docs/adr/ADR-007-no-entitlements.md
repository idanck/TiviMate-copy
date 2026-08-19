# ADR-007 — No entitlement, billing or device-licensing system

**Status:** accepted

## Decision

`FR-LIC-01` to `FR-LIC-09` are not implemented, and no feature is gated by tier. The `Tier`
column in `requirements/functional-requirements.md` is treated as informational only.

## Context

This build is for the author's personal use. There is no account to license, no store listing,
and no second user to charge. Implementing entitlement caching, device registration, purchase
restoration and a web checkout would add a large amount of code, a server dependency and a set
of failure modes, all in service of a business model that does not exist here.

`docs/08` row 10 argues that correctness features — the decoder toggle, user-agent, parental
PIN — should never be paywalled. Here that principle is simply total: everything is available.

## Consequence

- Every `Paid` requirement that was otherwise in scope is implemented as a free feature:
  unlimited playlists, custom groups, multiple EPG handling, PiP.
- `FR-LIC-10` (no advertising) holds trivially — there is no ad code of any kind.
- If this were ever distributed, the entitlement layer would be new work, but it would sit
  above the existing feature set rather than requiring changes to it.
