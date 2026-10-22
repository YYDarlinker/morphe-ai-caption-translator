# Compatibility

Last checked: 2026-10-22 for the 1.5.6 release metadata; the YouTube target list follows official **1.48.0**. Official stable patch release: **1.48.0**; required Patcher: **2.0.0**. Recommended YouTube: **21.20.405**, Android 9+.

The addon declares these YouTube targets, newest first: **21.42.241, 21.41.525, 21.28.208, 21.23.492** (experimental, as in official 1.48.0) and **21.20.405, 21.16.256** (stable). 20.x targets are no longer declared. It is an explicit list, not a continuous range and not a promise that future releases work automatically. The 21.28.208, 21.41.525 and 21.42.241 targets require Android 10 (minSdk 29).

| Official patch release | Official stable YouTube targets within this project's range |
| --- | --- |
| 1.42.0 | 21.13.164, 21.07.247 |
| 1.43.0 | 21.13.164, 21.07.247 |
| 1.44.0 | 21.16.256, 21.13.164 |
| 1.45.0 | 21.16.256, 21.13.164 |
| 1.46.0 | 21.16.256, 21.13.164 |
| 1.47.0 | 21.20.405, 21.16.256 (experimental: 21.40.161, 21.39.522, 21.38.130, 21.28.208, 21.23.492) |
| 1.48.0 | 21.20.405, 21.16.256 (experimental: 21.42.241, 21.41.525, 21.28.208, 21.23.492) |

Sources: official [1.42.0](https://github.com/MorpheApp/morphe-patches/blob/v1.42.0/patches-list.json), [1.43.0](https://github.com/MorpheApp/morphe-patches/blob/v1.43.0/patches-list.json), [1.44.0](https://github.com/MorpheApp/morphe-patches/blob/v1.44.0/patches-list.json), [1.45.0](https://github.com/MorpheApp/morphe-patches/blob/v1.45.0/patches-list.json), [1.46.0](https://github.com/MorpheApp/morphe-patches/blob/v1.46.0/patches-list.json), [1.47.0](https://github.com/MorpheApp/morphe-patches/blob/v1.47.0/patches-list.json), [1.48.0](https://github.com/MorpheApp/morphe-patches/blob/v1.48.0/patches-list.json) metadata. Older 20.x APK targets are below this addon's supported floor. Official experimental targets remain outside this addon's stable declarations.

## What the declaration means

The official patch compatibility table describes the official bundle, not independent verification of this addon. The complete baseline of the 1.4.x series is N37R2 with official 1.45.0 on YouTube 21.16.256. Release 1.5.0 additionally ran the real patcher on the stock YouTube 21.20.405 APK with official 1.47.0: every patch applied, all caption hooks were bound and the full DEX audit passed. That run, like the baseline, does not cover every device or OEM. Historical targets do not imply that every N37R2 scene, API provider and OEM has been tested on them. Missing or ambiguous structural bindings stop patching instead of guessing an obfuscated method or silently omitting a required hook.

Local tests, patch-composition audits, controlled Android layouts and physical phone observations are separate evidence. N37R2 controlled layout checks measured a maximum 0.5px centering error; its real YouTube/OEM centering after-check is not recorded as completed. Full semantic naturalness in fourteen languages and perfect audio synchronization are not certification claims.

Choose both an official bundle and an APK from the same row. Use Expert mode, the compatible official default selection and this addon's AI patch. Caption language is owned by YouTube and the official Captions patch (including **Preferred caption language**); this addon follows that native selection and does not add a separate memory root. Do not select another addon shipping the same runtime namespace or overlapping caption behavior.

Release 1.5.6 moves the addon to **Morphe Patches 1.48.0 / Patcher 2.0.0**, drops all 20.x targets, and declares the 1.48.0 21.x list above. It also removes the addon's own Auto-translate language list: language choices, sorting and visibility are owned by YouTube and the official Captions patch. The 1.48.0 composition run passed on YouTube 21.41.525 and structural checks passed on 21.28.208 and 21.23.492; 21.42.241, 21.20.405 and 21.16.256 were not composed locally because the APKs were not present. The 21.20.405 flyout `VerifyError` seen with 1.48.0 is fixed by the quick-toggle register change and covered by the composition audit on 21.41.525.

Release 1.5.5 kept the 1.5.4 target declaration. Its release preparation revalidated the metadata and packaged MPP; it does not add a new real-patcher or device/OEM compatibility claim.

Release 1.5.3 additionally ran the real patcher with official Morphe Patches 1.48.0-dev.6 on stock YouTube 21.16.256, 21.20.405, 21.23.492, 21.28.208, 21.40.161 and 21.41.525: every patch applied and all caption hooks were bound. That development-bundle run is historical evidence, not the current stable recommendation. 21.38.130 and 21.39.522 were not available for testing. YouTube 20.51.39 and 20.21.37 are not supported.
