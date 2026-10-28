# Compatibility and evidence

Declarations come from `patches/src/main/kotlin/app/yydarlinker/patches/shared/Constants.kt` and published metadata. The inspected official input is Morphe Patches 1.48.0 with Patcher 2.0.0. This is a discrete target list, not a continuous supported 21.x range. Source and device status belong in [PROJECT-STATE](PROJECT-STATE.md).

| YouTube | Declaration | APK minSdk | Evidence boundary |
| --- | --- | --- | --- |
| 21.42.241 | experimental | 29 | No current original APK/composition/device observation |
| 21.41.525 | experimental | 29 | Exact SECONDARY/code1561303419 input; finite resource recompilation and DEX audits with official 1.48.0/104 default roots + AI |
| 21.28.208 | experimental | 29 | code1561185128 supplementary input; separate finite resource/DEX proof |
| 21.23.492 | experimental | 28 | Historical structural checks, not latest candidate device acceptance |
| 21.20.405 | stable target metadata | 28 | User-reported earlier successful m7b/1.5.8 use; no independent desktop composition or installed-version readback for that phone |
| 21.16.256 | stable target metadata | 28 | No latest original APK/composition/device observation |

The two selected desktop inputs have package com.google.android.youtube and the expected signer rotation/certificates, four ABI entries (arm64-v8a, armeabi-v7a, x86, x86_64), and no pre-injected addon/official namespace. Exact input/output hashes and roots are part of each composition record. Use the actual APK's SDK/ABI/signature; filenames alone do not prove identity.

## Separate evidence layers

- Published 1.5.8: immutable release MPP and historical strict CI/finite desktop compositions. Its public SHA-256 is 5c9dfb19a3b0b88f55516bfca38bf47ee18ed3f282511b6b6401c37e8c5db686 (600263 bytes).
- Local candidate: see state for source fingerprint, package identity, same-package composition results and the latest phone limitation. Previous candidate proof applies only to its actual inputs.
- Desktop composition: verifies selected resources and final DEX under specified official roots. Unsigned APK outputs are local evidence, not installable release assets or a universal device matrix.
- JVM/Robolectric: protects mechanisms, fixture HTTP, crypto/editor/cache/lifecycle and layout contracts. Synthetic clocks/layouts do not certify OEM/player behavior, audio synchronization, semantic completeness or billing.
- Phone observation: the earlier m7b report covered Manager 2.2.0 / YouTube 21.20.405 / official 1.48.0 as reported by the user. The later d6r2 report of a repeated line/missing following translation is a real failure, not overridden by those older observations or unit passes.

The copied-whole-quote coverage flaw was reproduced in both public 1.5.8 and d6r2 with the same bad response. A local source-coverage-v2 repair exists, but actual phone acceptance and the separate intentional prompt/quality-policy differences need their own current evidence. Do not describe public 1.5.8 as universally defect-free because it worked in the user's earlier sample.

## Runtime provenance and limits

Exported official/patcher fields are unknown/runtime_unverified; build_expected fields are compile-time expectations, not measured runtime composition. core_source/core_adaptation/provenance describe this project's pinned earlier core and local changes, not an official Morphe translator. Old unqualified static markers remain literal in old logs.

Other APK variants, official root selections, overlapping caption addons, OEMs and providers require relevant checks. Do not combine another addon using the same namespace/behavior. Native CC and target language stay owned by YouTube/the official Captions patch.

Retained limits include startup credential reads, multi-store nontransactions, the source-cache checkActive→put window, hardware IO/Keystore tails, arbitrary interleavings, fourteen-language fluency, empty-cache provisioning and real billing. See [architecture contracts](ARCHITECTURE.md). These are bounded evidence limits, not claims that each unknown is an observed defect.
