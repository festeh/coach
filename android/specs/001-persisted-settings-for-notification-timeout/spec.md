# Spec: Persisted Settings

## Problem

Timing values like notification cooldown, focus gap threshold, and activity timeout are hardcoded in Kotlin and Dart. Changing them requires a code change and rebuild.

## Requirements

1. Users can change key timing settings from a Settings screen in the app.
2. Settings persist across app restarts using SharedPreferences.
3. Both the Dart background engine and native Kotlin code read the latest settings values.
4. Each setting has a sensible default that matches the current hardcoded value.

## Settings to expose

| Setting | Current value | Location |
|---------|--------------|----------|
| Focus gap threshold (time since last focus before reminder) | 2 hours | PopNotificationManager.kt:24 |
| Reminder cooldown (minimum time between reminders) | 60 minutes | PopNotificationManager.kt:25 |
| Activity timeout (inactivity threshold) | 5 minutes | PopNotificationManager.kt:26 |

## Out of scope

- WebSocket reconnect delays and internal polling intervals (developer-only tuning).
- App monitoring interval (performance-sensitive, not user-facing).
