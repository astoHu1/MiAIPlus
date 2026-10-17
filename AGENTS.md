# Repository Guidelines

## Workflow (Codex and Claude)

- Start with `git status --short`. Read only relevant source; consult `docs/validation.md` for verified behavior and `docs/architecture.md` for architecture. Task examples in documents do not authorize device operations.
- Other agents may share this checkout. Preserve their edits; avoid overwrites, reversions, and unrelated formatting. Stage only task-owned changes unless the user explicitly requests a snapshot of all current work.
- Do not independently reset, clean, stash, switch branches, kill processes, or remove another agent's caches/artifacts. Avoid competing builds, mock servers, and device operations; reuse applicable verified results.
- Make focused changes. Report changes, checks actually run, and remaining limits. Commit/push only when requested.

## Source Map

Java/Kotlin root: `app/src/main/java/io/github/miaiplus/`. Settings use Kotlin, Compose, and Miuix; hooks and execution remain primarily Java.

| Area | Entry points |
| --- | --- |
| Settings / UI state | `MainActivity.kt`, `SettingsState.kt`, `VirtualScreenState.kt`, `SettingsIcons.kt` |
| Hooks / reload | `XiaoAiHook`, `Native*Hook`, `hook/`, `HotReload` |
| Protocols / configuration | `ModelClient`, `ModelProtocol`, `ResponsesStream`, `ReasoningPolicy`, `EndpointProfiles` |
| Phone use / lifecycle | `NativePhoneUseTool`, `NativeSession`, `AgentEngine`, `ScreenService`, `InteractionUi` |
| Background display | `BackgroundDisplay`, `BackgroundActions`, `DisplaySession`, `helper/DisplayHelper` |

Module metadata: `app/src/main/resources/META-INF/xposed/`; never restore legacy `assets/xposed_init`. Debug fixtures: `app/src/debug/`; JUnit: `app/src/test/`; device runners: `app/src/androidTest/`; settings instrumentation: `app/src/uiTest/`. UI guidance and evidence: `docs/settings-ui.md` and `docs/settings-performance.md`. Utilities live in `scripts/`, licenses in `third_party/`. Keep `work/`, `recon/`, and `.tools/` out of distributions; release artifacts belong in `dist/`.

## Build and Validation

Use JDK 17+, SDK platform 37 / build-tools 37.0.0, Gradle wrapper 9.6.0, AGP 9.4.1, and Kotlin/Compose plugin 2.4.20; targetSdk remains 35. Set local `JAVA_HOME` / `ANDROID_HOME`; never commit machine-specific paths.

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# For releases or changes affecting both variants:
./gradlew :app:lintRelease :app:assembleRelease :app:assembleDebugAndroidTest
./gradlew :app:assemblePreview  # Optimized, development-signed UI/device build
python3 scripts/package_release.py  # After release build; source archive and checksums
```

- Use four-space indentation. Java/Kotlin: `PascalCase` types and composables, `camelCase` methods/variables. Keep Compose state and side effects scoped; avoid whole-page recomposition/animation for status polling. Python: `snake_case`. Match adjacent code; no separate formatter is configured.
- Preview inherits optimized release behavior; use debug for general instrumentation. Settings-only preview instrumentation uses `-PuiTestBuildType=preview`. Do not skip Lint to claim a complete release check.
- JUnit 4/Robolectric tests use `*Test.java` / `*Test.kt` and behavior-based method names. Cover affected protocol, coordinate, validation, and failure cases. No coverage percentage gate. Documentation-only changes need no build.
- Use authorized test devices. Start `python3 scripts/mock_model.py`, run `adb reverse tcp:18765 tcp:18765`, install debug/AndroidTest APKs, then run `python3 scripts/device_test.py`. Protocol checks: `--responses --reasoning`; interaction probes: `--hook --focus`. Hook tests restart XiaoAI unless `--no-restart` is set; scripts can still change activities/services. See `README.md` and `docs/api102-migration.md`; remove the reverse mapping afterward.

## Invariants

- Preserve Android 13+, LSPosed API 102, and exact XiaoAI version checks. Keep API dependencies `compileOnly` and service dependencies packaged. Release hooks, callbacks, clients, and host references before reload; reject reload while busy.
- Leave default API endpoint/key/model empty. Never commit credentials, signing material, personal device data, or decompiled content. Preserve IPC identity checks, lock-screen/secure-window/password/CAPTCHA restrictions, cancellation, confirmation, and timeouts.
- Background display is implemented. The compatibility/default-screen preference is off by default; native foreground/background tools select their own screen. Preserve session/tool ownership, task-bound consent, cancellation, and original-conversation return. Design/acceptance: `docs/virtual-display-implementation-spec.md`; device evidence/limits: `docs/background-display.md`. Partial probe success does not prove full capability. Background failures must stop the task, never silently fall back to the physical screen. Only the documented explicit app-conflict handoff may switch modes; invalidate old observations/coordinates and enforce its consent rules.
- Treat working-tree features separately from committed/published capabilities. Consult current source and dated validation records; older sections may describe superseded behavior.
- Update relevant behavior docs; distinguish mock, real-model, device, and untested results. Maintain dependency notices. Follow `docs/releasing.md` and distribute only reviewed artifacts.

## Commits and PRs

Use concise imperative subjects, e.g. `Fix background display cleanup`. Review staged changes, secrets, and `git diff --cached --check`. PRs describe behavior changes, linked issues, validation, and limits; include screenshots for UI changes. Exclude unreviewed parallel work from task-specific commits.
