# MoonLite 1.16.2 — build fixes

## Fixed
- Restored `app/src/main/res/layout/activity_main.xml`, which had accidentally contained the AndroidManifest XML.
- Restored all MainActivity view IDs used by `MainActivity.kt`:
  `drawerLayout`, `tabStrip`, `tabStripScroller`, `toolbarContainer`,
  `addressShell`, `addressBar`, `refreshButton`, `menuButton`,
  `moreButton`, and `webViewContainer`.
- Kept the compact black Chrome-style UI.
- Verified all XML resource files parse successfully.
- Scanned MainActivity `R.id.*` references against the layout resources; no unresolved view IDs remain.
- Verified `versionName=1.16.2` and `versionCode=11602`.

## Build environment limitation
This source tree does not contain `gradle/wrapper/gradle-wrapper.jar`, so a local Gradle compile cannot be executed in this environment. The wrapper stops before Gradle starts with `GradleWrapperMain` missing.

The source-level XML/Kotlin reference scan was performed after the fix.

## Bugfix 2 — startup hardening
- Do not automatically launch `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` during `MainActivity.onCreate()`; this can disrupt startup on OEM ROMs.
- Start the data-sync foreground service with the explicit `FOREGROUND_SERVICE_TYPE_DATA_SYNC` on Android 10+.
- Guard `WebView.enableSlowWholeDocumentDraw()` so an OEM WebView implementation cannot abort service initialization before the browser UI is created.


## bugfix4 — startup crash diagnostics

This build adds a process-level uncaught-exception logger and startup checkpoints.
The diagnostic file is written to the app's internal files directory as `startup_crash.log`.

Useful commands on a connected device:

```bash
adb logcat -c
adb logcat -v time | grep -E "MoonLiteStartup|AndroidRuntime|MoonliteService|MoonLite"
```

After reproducing a crash, the most important marker is the last `SERVICE:*` or `MAIN:*` checkpoint before `UNCAUGHT_EXCEPTION`.

The startup path also removes the non-essential `WebView.enableSlowWholeDocumentDraw()` call, because it can trigger early WebView initialization on some provider/OEM combinations.
