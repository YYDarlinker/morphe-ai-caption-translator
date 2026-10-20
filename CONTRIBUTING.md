# Contributing

Report addon issues here, not in the official Morphe tracker. Search [existing issues](https://github.com/YYDarlinker/morphe-ai-caption-translator/issues) first. Include addon/official patch/YouTube versions, Android/device, source and target language, reproduction steps and a redacted diagnostic export. Never attach API keys, signing keys or pre-patched YouTube APKs.

## Local development

Use JDK21, the Android SDK and this repository's Gradle wrapper. On the maintainer's Windows workspace the pinned toolchain is `.devenv/`; run `.\.devenv\dev.ps1` for the localization check and unit tests, or pass Gradle tasks explicitly. Morphe's packages are resolved through its public GitHub package registry; if authentication is needed, use environment variables or your untracked user Gradle configuration. Do not commit credentials.

```sh
./gradlew :extensions:extension:testDebugUnitTest :patches:buildAndroid :patches:generatePatchesList
python3 tools/check_localization.py
python3 .github/scripts/test_release_contract.py
```

The MPP appears in `build/n39/gradle/patches/libs/`; its embedded MPE comes from the same build. Local composition tasks need an original APK and the matching official MPP, supplied explicitly through `composition.*` properties. These proprietary/input files are not distributed here.

Keep translation logic in `translation-core`, host integration in `application-host`, configuration and transport in `infrastructure`, display scheduling in `presentation`, settings and rendering in `ui`, and structural hooks in Kotlin `patches`. UI calls the core through the application interfaces. Preserve independent namespaces, the single public AI root, existing configuration/cache identity, publication ownership and typed DEX safety. Caption language is owned by YouTube/the official Captions patch; do not reintroduce a separate language-memory root. A fix should address a mechanism, not a video ID, phrase or arbitrary time offset. Add focused regression evidence for material behavior changes; do not rewrite old assertions to hide a failure.

Work on `dev` or a feature branch, use semantic commits (`feat:`, `fix:`, `chore:`), then merge `dev` into `main` without squashing. Publishing uses the existing semantic-release workflow; see [RELEASING.md](docs/RELEASING.md). Do not hand-edit generated release metadata.

For the maintainer's local Windows workspace, follow [AGENTS.md](AGENTS.md): project work stays on E:, while shared installed tools may remain on C:.
