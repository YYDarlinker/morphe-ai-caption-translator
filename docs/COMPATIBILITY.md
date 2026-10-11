# Compatibility

Last checked: 2026-10-12. Official stable patch release: **1.47.0**. Recommended YouTube: **21.20.405**, Android 9+.

The addon declares these stable YouTube targets, newest first: **21.20.405, 21.16.256, 21.13.164, 21.07.247**. This is the union of official stable targets from patch release 1.42.0 onward at or above the project's historical minimum 21.07.247. It is an explicit list, not a continuous range and not a promise that future releases work automatically.

| Official patch release | Official stable YouTube targets within this project's range |
| --- | --- |
| 1.42.0 | 21.13.164, 21.07.247 |
| 1.43.0 | 21.13.164, 21.07.247 |
| 1.44.0 | 21.16.256, 21.13.164 |
| 1.45.0 | 21.16.256, 21.13.164 |
| 1.46.0 | 21.16.256, 21.13.164 |
| 1.47.0 | 21.20.405, 21.16.256 |

Sources: official [1.42.0](https://github.com/MorpheApp/morphe-patches/blob/v1.42.0/patches-list.json), [1.43.0](https://github.com/MorpheApp/morphe-patches/blob/v1.43.0/patches-list.json), [1.44.0](https://github.com/MorpheApp/morphe-patches/blob/v1.44.0/patches-list.json), [1.45.0](https://github.com/MorpheApp/morphe-patches/blob/v1.45.0/patches-list.json), [1.46.0](https://github.com/MorpheApp/morphe-patches/blob/v1.46.0/patches-list.json), [1.47.0](https://github.com/MorpheApp/morphe-patches/blob/v1.47.0/patches-list.json) metadata. Older 20.x APK targets are below this addon's supported floor. Official experimental targets remain outside this addon's stable declarations.

## What the declaration means

The official patch compatibility table describes the official bundle, not independent verification of this addon. The complete baseline of the 1.4.x series is N37R2 with official 1.45.0 on YouTube 21.16.256. Release 1.5.0 additionally ran the real patcher on the stock YouTube 21.20.405 APK with official 1.47.0: every patch applied, all caption hooks were bound and the full DEX audit passed. That run, like the baseline, does not cover every device or OEM. Historical targets do not imply that every N37R2 scene, API provider and OEM has been tested on them. Missing or ambiguous structural bindings stop patching instead of guessing an obfuscated method or silently omitting a required hook.

Local tests, patch-composition audits, controlled Android layouts and physical phone observations are separate evidence. N37R2 controlled layout checks measured a maximum 0.5px centering error; its real YouTube/OEM centering after-check is not recorded as completed. Full semantic naturalness in fourteen languages and perfect audio synchronization are not certification claims.

Choose both an official bundle and an APK from the same row. Use Expert mode, the compatible official default selection and this addon's AI patch; optional memory is independent. Do not select another addon shipping the same runtime namespace or overlapping caption-memory behavior.
