# ADR-029 — Television text follows the canvas, below 12sp

**Status:** accepted, provisional until checked on a real panel. Changes `docs/03` §3.1 ("no text
smaller than 12sp effective").

## Context

`docs/03` set a floor for the ten-foot shell: no text smaller than 12sp. The Studio canvas
(ADR-027) is drawn at 1080p, where a pixel is half a dp, and its two smallest steps are
*caption* 20px (10sp) and *meta* 22px (11sp). Its body is 12sp. The previous ramp's smallest
role, `labelSmall`, was 12sp, and its body 14–16sp.

Whether 10sp is legible from a sofa depends on the panel and the distance, and none of that can be
measured in the environment this app is developed in.

## Decision

The television ramp takes the canvas's sizes exactly:

| Step | sp | | Step | sp |
| --- | --- | --- | --- | --- |
| hero | 70 | | action | 15 |
| title | 54 | | nav | 14 |
| title-s | 32 | | row label | 13 |
| h1 | 17 | | body | 12 |
| | | | meta | 11 |
| | | | caption | 10 |

The 12sp floor is lifted. The font-scale setting (FR-UI-19, 0.8×–1.6×) still multiplies every
step, so anyone who finds the default small can raise it, and the floor at the largest setting is
16sp.

The phone ramp (`studio-phone.css`) has no step below 13sp and is not affected.

## Alternatives rejected

- **Keep the 12sp floor** by raising caption and meta to 12sp until a real-panel check. Safer, but
  it flattens the bottom of the ramp — caption, meta and body would all be 12sp — and the
  owner chose to see the canvas as drawn first.
- **Scale the whole ramp by 1.2.** Keeps the proportions and the floor, but every layout in the
  canvas was drawn against these sizes; a uniform 1.2 overflows rows the canvas measured.

## Consequences

- The smallest television text is 10sp at the default font scale, 8sp at the smallest.
- This is to be checked on the owner's televisions. If it reads too small, the fix is one table
  (`TelevisionRamp` in `ui/theme/Type.kt`) and an update to this record.
