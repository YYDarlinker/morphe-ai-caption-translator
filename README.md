# AI Caption Translator for YouTube / Morphe

This repository is a Morphe custom patch. It routes YouTube automatic-translation selections through a user-provided OpenAI-compatible API and displays the result in a dedicated in-player subtitle box.

## v1.0.0-dev.1 delivery

This release replaces the previous multi-stage display path with an anchored joint translation plan:

- local code creates bounded source windows, never final subtitles;
- one API request translates a window and selects sentence ranges over numbered source atoms;
- local validation requires exact source coverage and strictly increasing ranges;
- displayed timestamps always come from the original source atoms, never translated-text length;
- English provider captions can be calibrated against English auto-generated captions as a clock-only reference;
- YouTube's native subtitle renderer is suppressed while the AI overlay owns captions;
- the CC/settings automatic-translation list receives a real `中文（简体）` entry;
- every automatic-translation language selected through the native menu uses the configured API.

Detailed design and verification limits are in `docs/ARCHITECTURE.md`.

## Import into Morphe

Use Expert mode, keep the official YouTube `Captions` patch selected, and additionally select `AI caption translator` from this repository. The tested original package is YouTube `21.07.247`; the patch fails closed for incompatible host structures.

Release source: `https://github.com/YYDarlinker/morphe-ai-caption-translator`

## Configuration

Open YouTube → Settings → Morphe → AI caption translator. Configure the OpenAI-compatible API endpoint, model, API key, target language, and translation preferences. Keys are stored in Android Keystore-backed storage and are not bundled into this repository.

## Important behavior

The AI overlay owns automatic-translation tracks while enabled. Selecting Off hides it. Source-track behavior remains available when YouTube is not owned by the AI path. Network/API latency depends on the selected provider.

## License

GPLv3. See `LICENSE`.

## Remote source installation and release contract

Add the repository URL, not a release-page URL, in Morphe Expert mode:
`https://github.com/YYDarlinker/morphe-ai-caption-translator`

Manager resolves `patches-bundle.json` from `main` with prerelease disabled, and `dev` with prerelease enabled. Both channels must contain a valid, timezone-free `created_at`, matching version metadata, and a public MPP download URL. An uploaded release asset alone is not a usable patch source.

This project's regular release channel is for normal source installation; it does **not** certify device playback or translation quality. The original 1.0.0-dev.1 was manually published with a stale manifest and is superseded by the automated metadata repair release. Real-phone validation remains required. Do not select the old AI translator addon together with this replacement (shared runtime namespace).

The release pipeline uses Morphe's changelog generator and semantic-release, builds the Android MPP and extension, executes tests, validates the generated manifest, and checks root DEX / extension / version / repository identity before uploading. No more in-place replacement of published assets.
