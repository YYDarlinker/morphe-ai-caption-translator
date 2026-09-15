# Anchored AI Captions

## Current revision: 1.2.0

Three independently selectable caption patches: the AI translator, a locale-ordered Simplified Chinese menu entry, and native-compatible caption selection memory. AI includes an in-player engine selector; settings support 14 UI languages. See [feature boundaries, upgrade instructions and validation limits](docs/MODULAR-CAPTIONS-1.2.0.md). Historical revision notes below retain their original scope.

Independent YouTube AI subtitle addon, compatible with Morphe.

This repository is a Morphe custom patch. It routes YouTube automatic-translation selections through a user-provided OpenAI-compatible API and displays the result in a dedicated in-player subtitle box.

## Caption engine

The caption engine uses an anchored joint translation plan:

- local code creates bounded source windows, never final subtitles;
- one API request translates a window and pairs each translation with its exact contiguous source phrase;
- local validation requires exact source coverage and strictly increasing ranges;
- displayed timestamps always come from the original source atoms, never translated-text length;
- English provider captions can be calibrated against English auto-generated captions as a clock-only reference;
- YouTube's native subtitle renderer is suppressed while the AI overlay owns captions;
- the optional Simplified Chinese patch adds a localized, locale-ordered `zh-Hans` entry;
- every automatic-translation language selected through the native menu uses the configured API.

Detailed design and verification limits are in `docs/ARCHITECTURE.md`.

## Import into Morphe

Use Expert mode. For AI, keep the compatible official default patch set (including `Captions` and player-flyout/settings support), then select `AI caption translator`. Optionally select `Add Simplified Chinese to auto-translate` and/or `Remember caption selection`. Those two optional patches also work without selecting AI. To retain the former all-in-one feature set, select all three. The tested original package is YouTube `21.07.247`; incompatible structural bindings fail closed.

Release source: `https://github.com/YYDarlinker/morphe-ai-caption-translator`

## Configuration

Open YouTube → Settings → Morphe → AI caption translator. Configure the OpenAI-compatible API endpoint, model, API key and translation preferences. Keys are stored in Android Keystore-backed storage and are not bundled into this repository.

## Important behavior

When enabled, the custom subtitle box displays both modes, but **only Auto-translate selections call the API**. Original/manual/English auto-generated tracks display their original text and cue times without translation API requests or calibration probing.

When `Remember caption selection` is selected, explicit caption choices are remembered across videos for this application process, regardless of whether AI is enabled. Without that patch, the addon does not impose cross-video language restoration. A full process restart resets this optional memory. The in-player AI/YouTube choice shares the existing global AI switch and does not erase language memory.

API fields are edited inline with Android EditText selection/Paste. The host breadcrumb-copy handler is bypassed only for the API configuration rows; there is no API-key dialog. Keys are never copied into diagnostics. The style section includes one live preview; tap the frame to switch 16:9 landscape and 9:16 portrait without changing shared size/opacity. Portrait is larger with at most a 64dp stage-height increase.

Test API now sends the **same anchored protocol** as playback. A rejected optional schema is negotiated once; persistent invalid requests stop at configuration level and display a visible error instead of silently failing every subtitle window. Run Test API after correcting the endpoint/model/key. Network latency and translation quality depend on the provider.

## License

GPLv3. See `LICENSE`.

## Remote source installation and release contract

Add the repository URL, not a release-page URL, in Morphe Expert mode:
`https://github.com/YYDarlinker/morphe-ai-caption-translator`

Manager resolves `patches-bundle.json` from `main` with prerelease disabled, and `dev` with prerelease enabled. Both channels must contain a valid, timezone-free `created_at`, matching version metadata, and a public MPP download URL. An uploaded release asset alone is not a usable patch source.

This project's regular release channel is for normal source installation; it does **not** certify device playback or translation quality. The original 1.0.0-dev.1 was manually published with a stale manifest and is superseded by the automated metadata repair release. Real-phone validation remains required. Do not select the old AI translator addon together with this replacement (shared runtime namespace).

The release pipeline uses Morphe's changelog generator and semantic-release, builds the Android MPP and extension, executes tests, validates the generated manifest, and checks root DEX / extension / version / repository identity before uploading. No more in-place replacement of published assets.

Optional feature ownership is now explicit. Do not select another AI translator with the AI root, another Simplified Chinese remapping/insertion patch with the language root, or another subtitle-memory patch with the memory root. Installed sources may remain; this restriction concerns selected overlapping functionality.

## Continuity revision (explicit-anchors-r3)

Source atoms now carry printed numeric IDs and last_id, so the model copies boundaries instead of counting an implicit array. Lossless JSON representations are normalized; exact coverage, timing, and translation-quality checks remain mandatory. Retry feedback contains only error codes and endpoint indices, never raw model output. Context is capped to 160 characters on each side; background batching stays within the existing 30-second planning horizon. There is no second segmentation API pass and the retry cap is unchanged.

Co-timed source text is merged into a single real interval instead of overlapping artificial 1ms spans. Continuous playback no longer replays an earlier slice on a slightly delayed clock callback. No universal negative time offset is introduced: noisy ASR calibration smaller than three times the anchor MAD is declined. These address measurable error paths; exact device synchronization and real-model semantic quality still require device comparison.

## Readability revision (readable-anchors-r4)

Translation mode first requests the same source track as JSON3 to obtain native word offsets. Signed identity bytes remain untouched; when the format is signed or unavailable, the original format is retained with explicit estimated-timing diagnostics. This is not an extra speech-recognition or translation service.

Brief translated fragments are merged locally **before display**, using the original union of source intervals. Adjacent ready windows can join a brief boundary only while neither display plan has been rendered. Long gaps, empty/non-speech spans, oversized text and overlong unions are not merged. There is no target-length timing reallocation or extra model pass.

Version/model compounds are protected before window boundaries are selected. The user-confirmed GPT 5.6 Soul/Sol ASR case is corrected only inside that model name; ordinary uses of soul remain translatable. At most eight distinct protected model names are supplied per window.

API fields use the Android floating text action bar and delegate Paste to Android. The key field intentionally uses normal text input, **visible while editing**, not a password input type that invokes OEM secure keyboards. Stored keys are never loaded into the editor; successful input is cleared on focus loss and remains encrypted at rest. No personalized keyboard learning is requested, but this is a keyboard hint, not an OS-wide privacy guarantee. Other keyboard settings are not changed.

The settings page now uses consistent insets and typography, compact model controls, and an inset rounded 16:9 preview. Automated Android-framework tests cover long-touch/Paste and preview geometry; actual OEM keyboard and visual behavior still require device validation.

## Semantic / ASR / pause revision

Local English ASR word anchors now take priority when uniquely matched and validated, with the original calibration fallback retained. Display follows confirmed player positions rather than predicting future speech. Semantic windows, bounded context and labelled original-cue recovery address fragmented and rejected translations without an extra model pass. See docs/SEMANTIC-ASR-R6.md for evidence, cost tradeoffs and verification limits.


## Segmentation and viewport r7

Subtitle geometry now prefers the visible video rendering surface and follows resized/moved players. A consistent clause-first prompt, bounded source-pause hints and local dependent-phrase joins improve presentation without a second model pass. Presentation warnings do not trigger paid retries. Speaker/shot changes without source evidence remain unsupported. See docs/SEGMENTATION-SURFACE-R7.md for research, costs and verification limits.

