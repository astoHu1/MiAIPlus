# Repository Guidelines

## Project Structure & Module Organization

MiAIPlus is an Android 13+ LSPosed API 102 module for XiaoAI. Production Java lives in `app/src/main/java/io/github/miaiplus/`; `hook/` provides hooking/reflection helpers. Resources and the manifest are under `app/src/main/`; module entry, scope, and hot-reload metadata live in `resources/META-INF/xposed/`. Do not restore legacy `assets/xposed_init`. Debug fixtures live in `app/src/debug/`, JUnit tests in `app/src/test/`, and device runners in `app/src/androidTest/`.

`scripts/` contains mock-model, device-test, and release-packaging utilities. `docs/` explains architecture and releases; `third_party/` records upstream references and licenses. Treat `work/` and `recon/` as local investigation material, excluded from distributions. Build outputs belong in `app/build/`; packaged artifacts belong in `dist/`.

## Build, Test, and Development Commands

Use JDK 17+, Android SDK platform 37 and build-tools 37.0.0. The wrapper uses Gradle 9.6.0 with AGP 9.4.1; targetSdk remains 35. Set `JAVA_HOME` and `ANDROID_HOME` for your installation. Run from the repository root:

- `./gradlew :app:assembleDebug` — build the development APK.
- `./gradlew :app:testDebugUnitTest :app:lintDebug` — run unit tests and Android Lint; lint errors fail the build.
- `./gradlew :app:assembleRelease :app:lintRelease` — build and check the unsigned release APK.
- `./gradlew :app:assembleDebugAndroidTest` — build device test runners.
- `python3 scripts/package_release.py` — package release outputs, whitelisted source files, and SHA256 checksums after building release.

## Coding Style & Naming Conventions

Follow existing Java 17 code: four-space indentation, `PascalCase` classes, `camelCase` methods and variables, and lowercase package names. Keep Python scripts four-space indented with `snake_case` functions. Use descriptive names matching responsibilities such as `NativeSession` and `ScreenService`. No dedicated formatter is configured; use Android Lint and match nearby code.

## Testing Guidelines

Unit tests use JUnit 4.13.2; name classes `*Test.java` and methods after observable behavior. Cover action parsing, coordinate conversion, endpoint validation, and affected boundary cases. No numeric coverage threshold is configured.

For device tests, start `python3 scripts/mock_model.py`, run `adb reverse tcp:18765 tcp:18765`, build/install debug and debug-AndroidTest APKs, then run `python3 scripts/device_test.py`. Use `--responses --reasoning` for protocol checks and `--hook --focus` for interaction probes. Hook tests restart XiaoAI unless `--no-restart` is supplied; use an authorized device. See `docs/api102-migration.md` for reload/popup checks. Distinguish mock results from real-model behavior.

## Commit & Pull Request Guidelines

No commit history predates the initial import, so earlier commit conventions cannot be verified. Prefer concise imperative subjects, such as `Fix screenshot coordinate conversion`. PRs should explain behavior changes, link relevant issues, report checks and tested XiaoAI versions, and include screenshots for UI changes. Update implementation-status documentation when validation changes.

## Security & Compatibility

Preserve exact XiaoAI version checks and execution safeguards. Keep default API settings empty. Never commit credentials, keystores, personal device data, or decompiled APK contents. Follow `docs/releasing.md` before distributing artifacts.

API 102 reload changes must unhook and release owned callbacks, clients, and host references; reject reload while work is active. Keep API dependencies `compileOnly`, service dependencies packaged, and third-party notices current. Virtual-display documents are plans, not implemented capability.
