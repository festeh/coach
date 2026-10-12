# Plan: Customizable Overlay Appearance

**Spec**: specs/002-customizable-notification-appearance-settings/spec.md

## Tech Stack

- Language: Dart (Flutter) + Kotlin (native Android)
- State: SharedPreferences (read from both Dart and Kotlin sides)
- UI: Flutter settings screen (existing pattern)
- Overlay: Native Android WindowManager + XML layout (existing)

## Structure

Files to change:

```
lib/
├── constants/
│   └── storage_keys.dart              # add overlay setting keys
├── models/
│   └── app_settings.dart              # add overlay fields
├── services/
│   └── settings_service.dart          # load/save overlay fields
└── views/
    └── settings_view.dart             # add Overlay section UI

android/app/src/main/kotlin/com/example/coach_android/
└── MainActivity.kt                    # read prefs, apply to overlay
```

No new files needed.

## Approach

### 1. Add overlay settings to the data model

Add two fields to `AppSettings`:
- `overlayMessage` (String, default: empty string meaning "use default")
- `overlayColor` (String, default: `"dark"`)

Add matching keys to `StorageKeys`:
- `settingsOverlayMessage`
- `settingsOverlayColor`

Update `SettingsService.loadSettings()` and `saveSettings()` to handle the new string fields. Unlike the existing int fields, these are stored as strings directly (no conversion needed).

### 2. Add Overlay section to Settings UI

In `settings_view.dart`, add a new section between Notifications and the Reset button:

- **Section header**: "Overlay"
- **Message field**: A `TextField` showing the current custom message. Hint text shows the default message. Below it, a caption explaining `{app}` placeholder.
- **Color picker**: A row of `ChoiceChip` or colored `CircleAvatar` widgets for presets: Dark (#000000), Blue (#1565C0), Red (#B71C1C), Green (#2E7D32). Tapping one saves immediately (same pattern as sliders).
- **Preview button**: An `OutlinedButton` that calls `FocusService.showOverlay('Preview')` to trigger the native overlay with current settings. The overlay shows for a few seconds or until the user taps "Got it!".

Reset button already exists; update `_resetDefaults()` to also clear overlay settings.

### 3. Update Kotlin overlay to read preferences

In `MainActivity.showOverlay()`:

1. Read `flutter.settingsOverlayMessage` and `flutter.settingsOverlayColor` from `SharedPreferences` (using the `flutter.` prefix that Flutter's SharedPreferences uses on Android).
2. **Message**: If custom message is set, replace `{app}` with the friendly app name (resolved via `packageManager.getApplicationInfo(packageName).loadLabel(pm)`). If empty, use the default message but also resolve to friendly name instead of package name (bug fix).
3. **Color**: Parse the color key to a hex value, create a `GradientDrawable` programmatically with the matching color + 80% alpha + 16dp corners + white stroke (same as current `overlay_background.xml` but with dynamic color). Set it as the overlay container background.

### 4. Fix: resolve friendly app name in default message

Currently `showOverlay()` shows raw package names like `com.instagram.android`. Change it to resolve the friendly label using `packageManager`. This applies regardless of whether the user has set a custom message. Fall back to the package name if resolution fails.

## Color Preset Map

| Key   | Hex (80% alpha) | Display        |
|-------|-----------------|----------------|
| dark  | #CC000000       | Black (default)|
| blue  | #CC1565C0       | Blue           |
| red   | #CCB71C1C       | Red            |
| green | #CC2E7D32       | Green          |

## Risks

- **SharedPreferences prefix**: Flutter stores keys with a `flutter.` prefix on Android. The Kotlin side must read using that prefix. The existing `PopNotificationManager` already does this, so we follow the same pattern.
- **App name resolution failure**: `getApplicationInfo()` can throw `NameNotFoundException` for uninstalled apps. Fall back to the raw package name.
- **Overlay already shown**: `showOverlay()` returns early if overlay is already visible. Preview works fine since it uses the same method.
