# Plan: Persisted Settings

**Spec**: specs/001-persisted-settings-for-notification-timeout/spec.md

## Tech Stack

- Language: Dart + Kotlin
- Framework: Flutter with Riverpod
- Storage: SharedPreferences (already used throughout)
- Testing: Manual (no test framework configured)

## Structure

Changes touch these files:

```
lib/
├── constants/
│   └── storage_keys.dart          # Add settings keys
├── models/
│   └── app_settings.dart          # NEW - settings model with defaults
├── services/
│   └── settings_service.dart      # NEW - load/save settings from SharedPreferences
├── views/
│   └── settings_view.dart         # NEW - settings UI screen
├── app.dart                       # Add gear icon to AppBar that opens settings

android/app/src/main/kotlin/com/example/coach_android/
├── PopNotificationManager.kt      # Read settings from SharedPreferences instead of constants
```

## Approach

### 1. Settings model (`lib/models/app_settings.dart`)

Create a plain Dart class holding the three timing values with defaults matching current hardcoded values:

- `focusGapThresholdMinutes` (default: 120)
- `reminderCooldownMinutes` (default: 60)
- `activityTimeoutMinutes` (default: 5)

Store as minutes since these are user-facing values. Convert to seconds when writing to SharedPreferences for the Kotlin side.

### 2. Settings service (`lib/services/settings_service.dart`)

Simple singleton that wraps SharedPreferences:
- `loadSettings()` -> reads keys, returns `AppSettings`
- `saveSettings(AppSettings)` -> writes keys

Uses a shared SharedPreferences instance (same as the rest of the app). Keys stored in `StorageKeys`.

### 3. Storage keys (`lib/constants/storage_keys.dart`)

Add three new keys:
- `settingsFocusGapThreshold`
- `settingsReminderCooldown`
- `settingsActivityTimeout`

Values stored in seconds (matching what Kotlin reads).

### 4. Settings UI (`lib/views/settings_view.dart`)

A simple screen with three slider or number-input rows, one per setting. Each shows the setting name, current value, and allows adjustment. A "Reset to defaults" button at the bottom.

### 5. Navigation (`lib/app.dart`)

Replace the theme toggle icon in the AppBar with a gear icon (`Icons.settings`). Tapping it pushes `SettingsView` as a new route via `Navigator.push`. The theme toggle moves into the settings screen as a row (e.g., a switch for dark mode). The `onThemeToggle` callback gets passed through to `SettingsView`. No changes to the bottom nav tabs.

### 6. Kotlin reads from SharedPreferences (`PopNotificationManager.kt`)

Change `PopNotificationManager` to read the three timing values from the same SharedPreferences file that Flutter writes to (`FlutterSharedPreferences`). Fall back to current hardcoded defaults if keys are missing.

Flutter's `shared_preferences` plugin stores values with a `flutter.` prefix in a file called `FlutterSharedPreferences`. Kotlin reads from the same file using:
```kotlin
context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
```
Keys become `flutter.<key>` (e.g., `flutter.settingsFocusGapThreshold`).

The values are read fresh each time `checkAndShowFocusReminder` is called, so there's no cache invalidation problem.

## Implementation Order

1. Add storage keys to `StorageKeys`
2. Create `AppSettings` model
3. Create `SettingsService`
4. Create `SettingsView` UI
5. Add gear icon to AppBar in `app.dart`
6. Update `PopNotificationManager.kt` to read from SharedPreferences

## Risks

- **SharedPreferences prefix mismatch**: Flutter's `shared_preferences` plugin prefixes keys with `flutter.`. The Kotlin code must use this prefix when reading. Mitigation: verify the actual key format with a log statement during development.
- **Settings not picked up immediately**: PopNotificationManager reads settings on each reminder check, so changes take effect on the next check cycle. This is acceptable since reminders are infrequent.

## Open Questions

None.
