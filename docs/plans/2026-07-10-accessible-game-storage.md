# Accessible Game Storage Implementation Plan

> **For Hermes:** Implement task-by-task with tests and review.

**Goal:** Build a side-by-side GameNative fork whose Steam, GOG, Epic, and Amazon game payloads can live under the app's primary external files directory and be modified through ADB, while Wine prefixes and ImageFS remain private.

**Architecture:** Add a distinct debug application ID, remove runtime assumptions about `app.gamenative`, centralize game-install root selection, expose primary app-scoped external storage in Settings, and route every storefront plus Storage Manager through the same resolver.

**Target path:** `/storage/emulated/0/Android/data/app.gamenative.mod/files/<store>/...`

## Acceptance criteria

- `app.gamenative.mod` installs alongside official `app.gamenative`.
- Phone storage is selectable without SD or USB media.
- New game payloads are ADB-readable and writable.
- Steam, GOG, Epic, and Amazon use the selected root consistently.
- SD/USB support remains available.
- ImageFS and Wine prefixes remain private.
- Unit tests cover path selection and storefront path construction.

## Task 1: Side-by-side debug build

Modify `app/build.gradle.kts` to add `.mod` and `-mod` suffixes to debug builds. Change the manifest launch action and Kotlin shortcut/intent code to use `${applicationId}` or `BuildConfig.APPLICATION_ID` instead of a fixed package name. Test the launch action. Commit as `build: add side-by-side debug application id`.

## Task 2: Package-independent runtime paths

Replace fixed `/data/data/app.gamenative` paths with values derived from `Context`, `ImageFs`, or launcher-provided environment variables. Audit `DXVKHelper.java`, `WineUtils.java`, `Container.java`, `BionicProgramLauncherComponent.java`, `CrashHandler.kt`, and `evshim.c`. Run legacy and modern tests. Commit as `refactor: make runtime paths package independent`.

## Task 3: Central storage resolver

Create `app/src/main/java/app/gamenative/utils/GameStoragePaths.kt` and `app/src/test/java/app/gamenative/utils/GameStoragePathsTest.kt`. Model internal storage, primary app-scoped external storage, removable app-specific roots, persisted selection, invalid-path fallback, and no-external fallback. Update `DownloadService.kt` to publish primary and removable candidates separately. Commit as `feat: centralize game storage path selection`.

## Task 4: Settings support

Update `SettingsGroupInterface.kt` and strings so primary app-scoped external storage appears first as `Phone storage (ADB-accessible)`, followed by mounted SD/USB roots. Enable the switch whenever the primary external files directory exists. Persist the chosen root. Commit as `feat: offer primary external game storage`.

## Task 5: All storefronts

Route `SteamService.kt`, `GOGConstants.kt`, `EpicConstants.kt`, and `AmazonConstants.kt` through `GameStoragePaths`. Ensure staging and partial-install discovery use the same root. Expected suffixes are `Steam/steamapps/common`, `GOG/games/common`, `Epic/games`, and `Amazon/games`. Add tests and commit as `feat: use accessible storage for all storefronts`.

## Task 6: Storage Manager

Update `ContainerStorageManager.kt` to recognize and move payloads to the primary accessible root. Only game install directories may move; container directories and ImageFS must remain private. Add detection and move tests. Commit as `feat: support moving games to accessible storage`.

## Task 7: Build and device verification

Install JDK 17, Android SDK 36, NDK `27.3.13750724`, CMake, and Ninja. Run:

```bash
./gradlew :app:testLegacyDebugUnitTest :app:testModernDebugUnitTest
./gradlew :app:assembleModernDebug
adb install -r app/build/outputs/apk/modern/debug/app-modern-debug.apk
```

Verify both package IDs are installed. Select phone storage, install or move a small game, then use `adb push`, `adb pull`, checksums, Wine Explorer, and a game launch to prove two-way access without moving the prefix.

## Task 8: Publish reviewable work

Run tests and pre-commit review, verify no secrets/APKs/local SDK paths are tracked, push `feature/accessible-game-storage`, and open a draft PR against the fork's `master` with device evidence.
