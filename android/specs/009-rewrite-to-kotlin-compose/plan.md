# Plan: Rewrite Coach Android to Kotlin + Jetpack Compose

**Spec**: specs/009-rewrite-to-kotlin-compose/spec.md

## Tech Stack

- Language: Kotlin
- UI: Jetpack Compose + Material 3
- Architecture: MVVM with ViewModel + StateFlow
- Networking: OkHttp WebSocket
- Storage: Room (SQLite) with destructive migration, SharedPreferences
- DI: Manual (`AppContainer` class)
- Async: Kotlin Coroutines + Flow
- Testing: JUnit + Compose UI tests

## Structure

New code lives alongside existing Kotlin in `android/app/src/main/kotlin/com/example/coach_android/`. Flutter code (`lib/`) gets deleted at the end.

```
com/example/coach_android/
├── CoachApp.kt                    # Application class (creates AppContainer)
├── di/
│   └── AppContainer.kt            # Manual DI container
├── MainActivity.kt                # Compose activity (replaces FlutterActivity)
├── navigation/
│   └── AppNavigation.kt           # Bottom nav + screen routing
├── data/
│   ├── model/
│   │   ├── FocusData.kt           # data class (replaces Freezed FocusData)
│   │   ├── AppRule.kt             # data class (replaces Freezed AppRule)
│   │   ├── AppInfo.kt             # data class
│   │   ├── AppSettings.kt         # data class
│   │   └── LogEntry.kt            # data class
│   ├── db/
│   │   ├── UsageDatabase.kt       # Room database
│   │   ├── EventDao.kt            # Room DAO for events table
│   │   └── RuleCounterDao.kt      # Room DAO for rule_counters table
│   ├── preferences/
│   │   └── PreferencesManager.kt  # SharedPreferences wrapper
│   └── websocket/
│       └── WebSocketService.kt    # OkHttp WebSocket with reconnect
├── service/
│   ├── FocusMonitorService.kt     # Foreground service (refactored, no Flutter engine)
│   ├── AppMonitorHandler.kt       # UsageStats polling (mostly unchanged)
│   ├── MonitorLogic.kt            # Focus logic (from BackgroundMonitorHandler.dart)
│   ├── ServiceNotificationManager.kt  # (mostly unchanged)
│   ├── PopNotificationManager.kt      # (mostly unchanged)
│   └── BootReceiver.kt               # (unchanged)
├── ui/
│   ├── apps/
│   │   ├── AppsScreen.kt          # App selection UI
│   │   └── AppsViewModel.kt
│   ├── stats/
│   │   ├── StatsScreen.kt         # Usage stats dashboard
│   │   └── StatsViewModel.kt
│   ├── settings/
│   │   ├── SettingsScreen.kt      # Overlay + challenge config
│   │   └── SettingsViewModel.kt
│   ├── debug/
│   │   ├── DebugScreen.kt         # Debug tools
│   │   └── DebugViewModel.kt
│   ├── logs/
│   │   ├── LogsScreen.kt          # Activity logs
│   │   └── LogsViewModel.kt
│   ├── components/
│   │   ├── FocusStatusCard.kt     # Focus status display
│   │   ├── AppDetailSheet.kt      # Bottom sheet for app rules
│   │   └── RuleEditorDialog.kt    # Rule creation dialog
│   └── overlay/
│       └── OverlayManager.kt      # System overlay builder (extracted from MainActivity)
└── util/
    └── TimeFormatter.kt           # Duration formatting
```

## Approach

The rewrite happens in 7 phases. Each phase produces working code that can be tested independently. The app stays on Flutter until Phase 6, when we switch the entry point.

### Phase 1: Data layer

Port the data models and database.

1. **Data classes** - Convert Freezed models to Kotlin `data class` with `@Serializable` annotation. No code generation needed. Files: `FocusData.kt`, `AppRule.kt`, `AppInfo.kt`, `AppSettings.kt`, `LogEntry.kt`.

2. **Room database** - Replace `sqflite` with Room. Same schema (events + rule_counters), same queries. Use `fallbackToDestructiveMigration()` — the old sqflite database gets wiped on first launch. Room gives us compile-time query validation and Flow-based reactive queries. Files: `UsageDatabase.kt`, `EventDao.kt`, `RuleCounterDao.kt`.

3. **Preferences** - Wrap SharedPreferences in a single `PreferencesManager` class. Read from the same keys Flutter uses so the migration is seamless. File: `PreferencesManager.kt`.

### Phase 2: WebSocket service

Port the WebSocket client from Dart to Kotlin.

1. **OkHttp WebSocket** - Replace `web_socket_channel` with OkHttp's built-in WebSocket support. Same reconnection logic (exponential backoff, max 10 attempts). Same message format (`{"type": "focus"}`, `{"type": "get_focusing"}`). Expose updates as a `SharedFlow`. File: `WebSocketService.kt`.

2. **Connection lifecycle** - Tie WebSocket lifecycle to the foreground service instead of a background Dart isolate. The service starts the WebSocket; the service stops it.

### Phase 3: Monitor logic (the core)

This is the most important phase. Port `BackgroundMonitorHandler.dart` (700 lines of Dart) to `MonitorLogic.kt`.

1. **Direct calls replace IPC** - Instead of method channels, `MonitorLogic` calls `OverlayManager.show()` and `OverlayManager.hide()` directly. No serialization, no async channel overhead.

2. **State lives in one place** - `FocusData` is a `StateFlow<FocusData>` inside `MonitorLogic`. The service reads it. ViewModels observe it. No SharedPreferences sync between engines.

3. **Rule checking** - Same logic: count app opens per rule, trigger overlay when threshold reached, track pending challenges. Uses Room DAOs instead of raw SQL.

4. **Focus reminders** - Same logic: check `sinceLastChange` and `lastActivityTime`, delegate to `PopNotificationManager`.

### Phase 4: Service refactor

Simplify `FocusMonitorService.kt` by removing all Flutter engine code.

1. **Delete** - `initializeBackgroundEngine()`, `setupBackgroundChannels()`, `sendAppToBackgroundIsolate()`, `cleanupBackgroundEngine()`, all `FlutterEngine` imports.

2. **Replace** - `notifyAppDetected()` calls `MonitorLogic.onAppChanged()` directly (instead of sending through EventChannel to Dart and back through MethodChannel).

3. **Keep** - Service lifecycle, `START_STICKY`, `onTaskRemoved` restart, notification management, `AppMonitorHandler` polling.

### Phase 5: Compose UI

Build all 5 screens in Jetpack Compose. Each screen gets a ViewModel that observes `MonitorLogic` state via `StateFlow`.

1. **Apps screen** - List installed apps with checkboxes. Search filter. Toggle monitoring per app. Bottom sheet for app rules. Maps to `apps_view.dart` (201 lines) + `app_detail_bottom_sheet.dart`.

2. **Stats screen** - Daily stats cards (focus count, total time, blocked opens). Blocked apps list. Date picker. Maps to `stats_view.dart` (385 lines).

3. **Settings screen** - Overlay message, color, button text. Challenge type (none/longPress/typing). Target app picker. Maps to `settings_view.dart` (624 lines).

4. **Debug screen** - WebSocket status, force reminder, service controls. Maps to `debug_view.dart` (388 lines).

5. **Logs screen** - Event list with type filtering. Maps to `logs_view.dart` (617 lines).

6. **Navigation** - Bottom nav bar with 5 tabs. Maps to `app.dart` (150 lines).

### Phase 6: Overlay extraction

Extract overlay building from `MainActivity` into `OverlayManager`.

1. **OverlayManager** - Takes a `Context` and `WindowManager`. Builds standard, long-press, and typing challenge overlays. Same native Android Views (not Compose, since overlays use `WindowManager.addView` which requires traditional Views).

2. **Direct integration** - `MonitorLogic` calls `OverlayManager.show(packageName, type, challengeType, ruleId)` directly. No method channel round-trip.

### Phase 7: Switch entry point and cleanup

1. **MainActivity** - Change from `FlutterActivity` to `ComponentActivity`. Set `setContent { CoachApp() }`.

2. **Delete Flutter** - Remove `lib/` directory, `pubspec.yaml`, `.dart_tool/`, `build.gradle.kts` Flutter plugin, `flutter` block in settings.

3. **Update build** - Add Compose, Room, OkHttp, kotlinx-serialization dependencies. Remove Flutter Gradle plugin.

4. **Migration test** - Verify SharedPreferences data carries over (monitored apps, rules, settings). Verify Room can open the existing SQLite database by matching the schema.

## Risks

- **Overlay in Compose**: System overlays (`TYPE_APPLICATION_OVERLAY`) require `WindowManager.addView()` which takes traditional Android Views, not Compose. Mitigation: Keep overlay rendering as native Views (already Kotlin), only the in-app UI uses Compose. This is the standard approach.

- **Room schema mismatch**: The existing SQLite database was created by `sqflite` without Room's metadata tables. Mitigation: `fallbackToDestructiveMigration()` — old stats and logs are wiped on upgrade. Users start fresh.

- **SharedPreferences key format**: Flutter's `shared_preferences` plugin prefixes keys with `flutter.` (e.g., `flutter.settingsOverlayMessage`). Mitigation: `PreferencesManager` reads from the `FlutterSharedPreferences` file and strips/adds the prefix as needed. The native code already reads from `FlutterSharedPreferences` directly.

- **Service restart during migration**: If the user updates from Flutter version to Kotlin version, the running service might crash. Mitigation: `START_STICKY` + `onTaskRemoved` restart will recover. First launch after update will reinitialize cleanly.

- **WebSocket behavior parity**: OkHttp WebSocket has different threading than Dart's `web_socket_channel`. Mitigation: Use coroutine dispatchers to match the current behavior (messages processed sequentially on a single coroutine).

## Decisions

- **DI**: Manual `AppContainer` class created in `CoachApp.onCreate()`. ViewModels get dependencies via factory. No Hilt — keeps build simple for 5 screens.
- **Storage**: SharedPreferences (same file Flutter uses). No DataStore migration.
- **Database migration**: Destructive. `fallbackToDestructiveMigration()` wipes the old sqflite database. Users start with fresh stats.
- **Color picker**: Preset palette with hex input field. No third-party library.

## Open Questions

- None — all resolved.
