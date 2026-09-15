# Repository Guidelines

## Project Structure & Module Organization
- `app/` is the Android module with sources in `app/src/main/java/com/example/simplecontroller`; key packages include `ui/` for gestures, `net/` for UDP, `io/` for persistence, and `model/` for data classes.
- Layouts, drawables, and themes live in `app/src/main/res`; keep strings and colors in `values/`. Exported touchpad layouts land in `touchpad_logs/`. Tests live in `app/src/{test,androidTest}` with mirrored packages.

## Build, Test & Development Commands
```bash
./gradlew assembleSideloadDebug        # compile the existing debug APK
./gradlew installSideloadDebug         # deploy the existing debug APK
./gradlew testSideloadDebugUnitTest    # run JVM tests against the debug variant
./gradlew bundlePlayRelease            # compile the unsigned Play Store AAB
./gradlew connectedSideloadDebugAndroidTest # run instrumentation tests
```
- Use Android Studio for layout inspection and `adb logcat -s NetworkClient` for UDP traces.

## Coding Style & Naming Conventions
- Follow Kotlin official style: 4-space indentation, PascalCase types, camelCase functions and fields. Keep activities thin; move behavior to helpers or view models in `ui/`.
- Prefer extension functions over utility singletons, align resource names with existing patterns (`activity_main.xml`, `dark_background`), and avoid committing generated `build/` artifacts.

## Testing Guidelines
- Co-locate unit tests with their package paths in `app/src/test`; name methods `targetCondition_expectedOutcome`.
- Cover layout persistence, swipe gestures, and UDP emission with instrumentation tests. Run `./gradlew lint test` before pushing and `connectedSideloadDebugAndroidTest` whenever UI or protocol behavior changes.

## Commit & Pull Request Guidelines
- Write single-purpose, imperative commit subjects under ~72 characters (e.g., `Fix split screen bottom inset handling`) and squash noisy work-in-progress commits.
- PRs should summarize user impact, list manual verification (`assembleSideloadDebug`, `test`, `bundlePlayRelease`, device smoke), link issues, and attach screenshots or recordings for UI tweaks. Flag migrations or risky follow-ups.

## ConsoleBridge Pico Integration
- Keep network defaults aligned with `consolebridge_pico_v0`: UDP port 9010, CBv0 `DELTA:dx,dy` payloads, and retry timing; document deviations in both repos.
- When extending gestures or protocol fields, update the Pico firmware contract (`src/main.c`, `udp_server.c`) and document sample payloads in the PR.
- Validate end-to-end with the Pico firmware: confirm STA join, AP fallback, LED blink on send, and pointer delta translation.

## Configuration & Operational Notes
- Maintain `local.properties` locally; it holds the Android SDK path. New preference keys need safe defaults in `NetworkClient` plus PR notes for QA.
- For layout issues, export the current layout via the in-app save action and attach the file from `touchpad_logs/` for reviewers.

## Stable Recovery Baseline
- Read `STABLE_BASELINE.md` before implementation work.
- GitHub commit `c6b60428296e1bc2a93fe3859bb962104f769bc9`, tagged `stable-2026-09-10`, is Tyler's confirmed stable recovery baseline for this critical app.
- Preserve the history containing that commit. Never force-push, rewrite, squash away, or otherwise remove the stable recovery point.
- The dirty local checkout may contain newer unconfirmed work and is not part of the stable baseline unless it is deliberately committed and confirmed later.
