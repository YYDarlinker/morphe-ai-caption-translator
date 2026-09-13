# Architecture and delivery notes

## What changed

The old implementation had two competing cores, a model translation boundary, and a second local display-slicing pass. That made a grammatically good translation vulnerable to a later display split and made source timing depend on target-language text length.

The production path is now one core:

1. Fetch the selected YouTube source track.
2. If it is an English provider track, fetch English auto-generated captions as a **clock-only** reference. Match monotonic 4-gram anchors, use a robust median/MAD estimate, and shift the provider track only when the delay is stable. The provider text is never replaced with ASR text.
3. Prefer native JSON3 segment offsets. For formats without word offsets, create bounded lexical atoms only inside the original cue interval.
4. Create bounded transport windows locally (up to 48 atoms / 12 seconds). A window is not a subtitle and is never displayed directly.
5. Make one API request for a window. The model translates and chooses sentence boundaries together, but it can only return ranges over numbered source atoms.
6. Validate exact coverage, monotonic ranges, source-range timing, IDs, and translation plausibility locally. Invalid output is discarded rather than repaired by another model call.
7. Display the returned ranges with the source atom start/end timestamps. Translation length cannot move a timestamp.
8. Cache the anchored plan, not only a flattened translation string.

This gives the model enough context to produce natural sentences while avoiding a separate segmentation request. It also makes the timing contract mechanically checkable.

## Native YouTube takeover

When the AI path owns captions, the patch now:

- injects `中文（简体）` into the automatic-translation track list using YouTube's own track model/builder;
- routes selected automatic-translation entries from the CC/settings menu to the AI overlay;
- preserves the original track object, language identity, VSS identity, and URL fields except in the new cloned Simplified Chinese entry;
- masks YouTube's native `SubtitleWindowView.draw(Canvas)` while the AI overlay is active, preventing stale native captions from appearing underneath or racing the custom box;
- leaves the original YouTube source-track and Off actions available when AI is not the owner.

The host bindings are resolved structurally at patch time and fail closed if the target YouTube layout/model changes.

## Cost controls

- One request performs translation plus boundary selection.
- Context is one adjacent window on each side, not a full transcript.
- Realtime windows are bounded; background prefetch is limited to three windows.
- Failed windows stop after three failures per session instead of retrying indefinitely.
- Unsupported `max_tokens` endpoints fail clearly rather than silently removing the output budget.
- Translation cache entries include the anchored plan and are invalidated by cache format 3.

## Verification boundary

Build and unit regression: passed on September 13, 2026. The current regression suite contains 17 discovered tests: 16 passed and one intentionally ignored historical test that reproduces the retired target-length display splitter defect.

The combined Morphe operation was structurally executed against the original YouTube 21.07.247 APK (SHA-256 `afed0724c7cbdec08626573f5e0c405db76e11fe9bfdafbc3884690a766666db`) with the official default patch set (including `Captions`) plus `AI caption translator`. The result rebuilt successfully, and no patch step failed.

A real phone is still required to judge API-provider behavior, the CC/settings menu interaction, subtitle visual placement, and playback under seek/miniplayer transitions.
