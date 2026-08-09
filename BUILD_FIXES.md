# MoonLite v13.1 build fixes

This patch addresses the GitHub Actions `assembleDebug` failure from the v13 UI build.

## Fixed

- `MainActivity.kt`: avoid Kotlin receiver ambiguity around the member `Int.dp()` extension inside `ImageButton.apply {}` by using an explicit `dp(value)` helper for the new-tab button dimensions and padding.
- `SettingsActivity.kt`: `MoonliteService.tabManager` is a property, not a function. Replaced `service?.tabManager()` with `service?.tabManager` for reload/new-tab/cache operations.

## Build environment from the supplied CI log

- JDK 17
- Gradle 8.5
- Android Gradle Plugin 8.3.2
- `gradle assembleDebug --no-daemon`

The supplied failure was during `:app:compileDebugKotlin`; Gradle setup, Android resources, manifest processing, and dependency resolution completed successfully.

## Note

This environment does not contain a Gradle distribution/Android SDK, so a local `assembleDebug` cannot be executed here. GitHub Actions remains the final compile verification.
