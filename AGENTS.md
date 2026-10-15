# Repository Guidelines

## Project Structure & Module Organization

MiAIPlus is an Android 13+ LSPosed module for XiaoAI. Production Java code lives in `app/src/main/java/io/github/miaiplus/`; resources, the manifest, and Xposed entry metadata are under `app/src/main/`. Debug fixtures live in `app/src/debug/`, JUnit tests in `app/src/test/`, and device instrumentation runners in `app/src/androidTest/`.

`scripts/` contains mock-model, device-test, and release-packaging utilities. `docs/` explains architecture and releases; `third_party/` records upstream references and licenses. Treat `work/` and `recon/` as local investigation material, excluded from distributions. Build outputs belong in `app/build/`; packaged artifacts belong in `dist/`.

## Build, Test, and Development Commands

Use JDK 17+, Android SDK platform 35, and build-tools 35.0.0. Run commands from the repository root:

- `./gradlew :app:assembleDebug` — build the development APK.
- `./gradlew :app:testDebugUnitTest :app:lintDebug` — run unit tests and Android Lint; lint errors fail the build.
- `./gradlew :app:assembleRelease :app:lintRelease` — build and check the unsigned release APK.
- `python3 scripts/package_release.py` — package release outputs, whitelisted source files, and SHA256 checksums after building release.

## Coding Style & Naming Conventions

Follow existing Java 17 code: four-space indentation, `PascalCase` classes, `camelCase` methods and variables, and lowercase package names. Keep Python scripts four-space indented with `snake_case` functions. Use descriptive names matching responsibilities such as `NativeSession` and `ScreenService`. No dedicated formatter is configured; use Android Lint and match nearby code.

## Testing Guidelines

Unit tests use JUnit 4.13.2; name classes `*Test.java` and methods after observable behavior. Cover action parsing, coordinate conversion, endpoint validation, and affected boundary cases. No numeric coverage threshold is configured.

For device tests, start `python3 scripts/mock_model.py`, run `adb reverse tcp:18765 tcp:18765`, build/install debug and debug-AndroidTest APKs, then run `python3 scripts/device_test.py`. See `README.md` for exact commands. Run `--hook` only on an authorized LSPosed test device; it restarts XiaoAI. Distinguish fixture results from verified real-model behavior.

## Commit & Pull Request Guidelines

No commit history predates the initial import, so earlier commit conventions cannot be verified. Prefer concise imperative subjects, such as `Fix screenshot coordinate conversion`. PRs should explain behavior changes, link relevant issues, report checks and tested XiaoAI versions, and include screenshots for UI changes. Update implementation-status documentation when validation changes.

## Security & Compatibility

Preserve exact XiaoAI version checks and execution safeguards. Keep default API settings empty. Never commit credentials, keystores, personal device data, or decompiled APK contents. Follow `docs/releasing.md` before distributing artifacts.
