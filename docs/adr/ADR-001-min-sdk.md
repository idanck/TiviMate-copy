# ADR-001 — Minimum SDK is API 23 (Android 6.0)

**Status:** accepted

## Decision

`minSdk = 23`, `targetSdk = 35`, `compileSdk = 35`.

## Context

`docs/00` section 0.4 offers API 23 for reach on older TV boxes, or API 24 if it materially
simplifies Media3, PiP or scoped-storage work. It does not.

- Media3 supports API 21, so nothing in the player stack forces a higher floor.
- Picture-in-picture needs API 24 on TV and API 26 for the modern behaviour. It is guarded by
  `PackageManager.FEATURE_PICTURE_IN_PICTURE` and an SDK check, which is required anyway
  because plenty of API 26+ devices still do not offer PiP.
- Scoped storage arrives much later; the recorder already writes through the Storage Access
  Framework, which works from API 21.
- `java.time` is used throughout the guide code and is only native from API 26. Core library
  desugaring provides it down to API 23, which is a build setting rather than a design
  constraint.

## Consequence

Older boxes stay in range. PiP is simply absent on devices below API 24 rather than the app
being uninstallable there.
