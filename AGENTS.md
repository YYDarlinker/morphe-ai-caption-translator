# Project workspace rules

- The active repository for this project is `E:\Projects\DeepSeek morphe-ai-caption-translator`.
- Run project development, builds, reports and project-specific temporary work on E:. Use `build/` for build outputs, `validation/runs/` (or `.verification/`) for evidence, and `.tmp/` for scratch work. Do not recreate project workspaces or delivery copies under `C:\Users\14776\Documents\Codex`.
- If E: is unavailable, stop project writes and report the missing drive; do not silently fall back to C:.
- Shared installed tools, application/session storage and operating-system-managed files may remain on C:. Do not move or delete them as project cleanup.
- Current state is `docs/PROJECT-STATE.md`; its local mirror is `.verification/state-mirror/PROJECT-STATE.md`. Update both byte-identically when updating project state.
- The current development environment is `.devenv/`: JDK 21, Python 3.12, Android SDK 35/36 and the Robolectric jars are under `work/toolchain/`; Gradle's user home is `work/gradle-home/`. Prefer `.\.devenv\dev.ps1` (or `.devenv\dev.cmd` where script execution is restricted) for tests and builds. It sets workspace-local `TEMP`, `TMP`, `GRADLE_USER_HOME` and `ANDROID_USER_HOME` for the process only; never change global Windows environment variables.
- For ad-hoc Gradle commands, set process-local `TEMP` and `TMP` to `work/temp` (or use `.devenv\Enter-Dev.ps1` via an execution-policy-allowed wrapper) and preserve shared tool caches.
- Historical material under `docs/N*`, `docs/WORKSPACE-MIGRATION-20261005.md`, `.verification/` and `recovered/` preserves original paths and hashes as evidence. It is not a description of the active repository; do not rewrite or run archived scripts blindly.
- Preserve release artifacts and historical delivery bytes. Workspace maintenance does not authorize a publication or release; product changes require an explicit request and the current verification path.
