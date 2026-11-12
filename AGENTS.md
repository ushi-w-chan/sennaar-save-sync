# Repository Guidelines

## Project Structure

- Android sources live in `app/src/main/java/com/ushi/sennaar/`. `MainActivity.kt` contains the Compose UI, `SaveService.kt` handles privileged save access through Shizuku, `Snapshot.kt` defines the snapshot format, and `Cloud.kt` calls the sync API.
- The Shizuku service interface lives in `app/src/main/aidl/com/ushi/sennaar/ISaveService.aidl`. App configuration lives in `app/src/main/AndroidManifest.xml` and `app/build.gradle.kts`.
- `worker/src.mjs` implements the Cloudflare Worker API. `worker/wrangler.toml` configures its `SAVES` R2 binding.

## Build and Development

Use JDK 17 and Android SDK 36. Set `ANDROID_HOME` or open the project in Android Studio. Run these commands from the repository root.

- `./gradlew :app:assembleDebug :app:lintDebug` builds the debug APK and runs Android Lint.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` installs the APK on a connected device.
- `node --check worker/src.mjs` checks Worker syntax. See `README.md` for deployment and R2 setup.

## Coding Style

Use four-space indentation in Kotlin and two spaces in Worker JavaScript. Follow existing naming patterns, including `PascalCase` for Kotlin types and composables, `camelCase` for functions and variables, and `UPPER_SNAKE_CASE` for Kotlin constants. Keep the app and Worker compatible when changing snapshot fields or API behavior.

## Testing

The repository currently has no automated test suite or coverage target. For save changes, use an Android 11+ device with Shizuku and the Epic edition of Chants of Sennaar. Back up and restore a disposable save, then reopen the game to verify it loads. For sync changes, upload on one device and download and restore on another. Confirm that separate uploads remain available. Follow the device test steps in `README.md`.

## Commits and Pull Requests

Use short, action-oriented commit subjects, such as `Shrink Android release APK`. In the PR, summarize the behavior change, list the checks you ran, and link a related issue when available. Include screenshots for visible UI changes.

## Secrets

Keep the R2 bucket private. Set `SYNC_TOKEN` through Wrangler secrets and keep real tokens and player saves out of Git.
