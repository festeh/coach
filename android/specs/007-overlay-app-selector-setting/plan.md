# Plan: Overlay Button App Selector

## Tech Stack

- Language: Dart + Kotlin
- Framework: Flutter with Riverpod, Android native overlays
- Storage: SharedPreferences (existing pattern)
- Testing: Manual (no test framework in place)

## Structure

Files to change:

```
lib/
├── constants/
│   └── storage_keys.dart              # Add new key
├── models/
│   └── app_settings.dart              # Add new field
├── services/
│   └── settings_service.dart          # Load/save new field
└── views/
    └── settings_view.dart             # Add app picker UI

android/app/src/main/kotlin/.../
└── MainActivity.kt                    # Read setting, launch chosen app
```

## Approach

### 1. Add storage keys and model fields

Add two keys to `StorageKeys`:
- `settingsOverlayTargetApp` — for Coach Overlay
- `settingsRulesOverlayTargetApp` — for Rules Overlay

Add two fields to `AppSettings` (both `String`, default `''`):
- `overlayTargetApp`
- `rulesOverlayTargetApp`

Add both to `copyWith`. Empty string means "go home" (current behavior).

### 2. Load and save the new setting

In `SettingsService.loadSettings()`, read both new keys from SharedPreferences. In `saveSettings()`, write both. Same pattern as existing string settings.

### 3. Add app picker in Settings UI

Add a "Button opens" picker in both the "Coach Overlay" and "Rules Overlay" sections of `settings_view.dart`. Each shows a `ListTile` with:
- Label: "Button opens"
- Current value: app name or "Home screen" if empty
- On tap: show a bottom sheet or dialog with installed apps list (reuse the pattern from `AppsView` — query installed apps via the existing method channel, show scrollable list with icons)

Each picker should have a "Home screen (default)" option at the top. Coach Overlay saves to `overlayTargetApp`, Rules Overlay saves to `rulesOverlayTargetApp`.

### 4. Read the setting in Kotlin and launch the target app

In `MainActivity.kt` `showOverlay()`, read the correct preference key based on overlay type:
- Coach: `flutter.settingsOverlayTargetApp`
- Rule: `flutter.settingsRulesOverlayTargetApp`

Store the resolved target package in an instance variable (e.g., `currentTargetApp`) so it's available when the button is pressed.

In `goHomeAndHideOverlay()`, check `currentTargetApp`:
- If empty or null: launch HOME intent (current behavior)
- If set: launch the app using `packageManager.getLaunchIntentForPackage(targetPackage)`. Fall back to HOME if the intent is null (app uninstalled).

Clear `currentTargetApp` in `hideOverlay()`.

The challenge views' close/X buttons and `notifyChallengeCompleted()` also call `goHomeAndHideOverlay()`, so they'll all pick up the correct target automatically.

### 5. Load installed apps in settings

The `AppsView` already loads installed apps via `InstalledApps.getInstalledApps()`. Reuse this same approach in the settings picker dialog. Show app icon + name, save the selected package name.

## Risks

- **App uninstalled after selection**: Fall back to home intent if `getLaunchIntentForPackage` returns null. Show "Home screen" in settings if saved package is no longer installed.
- **Loading installed apps is slow**: Show a loading indicator in the picker dialog. The app list is already cached by the system.

## Open Questions

None — separate settings for Coach and Rules overlays as requested.
