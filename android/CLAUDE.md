# CLAUDE.md

## Project Overview

Android app ("Coach") that helps users focus by monitoring app usage and showing overlays when distracted. Pure Kotlin + Jetpack Compose. Communicates with a WebSocket server for focus state management.

## Architecture

### Single-process, no IPC
Everything runs in one process. `MonitorLogic` is the central state holder exposing `StateFlow<FocusData>`. ViewModels observe it directly. No method channels, no cross-engine serialization.

### Key classes
- **MonitorLogic** (`service/MonitorLogic.kt`) — Core focus logic. Owns `StateFlow<FocusData>`, handles app change events, rule checking, WebSocket updates.
- **FocusMonitorService** (`FocusMonitorService.kt`) — Foreground service. Creates `AppContainer`, wires `MonitorLogic`, manages `OverlayManager`. START_STICKY with alarm-based restart.
- **OverlayManager** (`OverlayManager.kt`) — Builds system overlays (standard, longPress, typing challenge) via `WindowManager.addView()`. Uses native Views, not Compose.
- **AppMonitorHandler** (`AppMonitorHandler.kt`) — Polls `UsageStatsManager` for foreground app changes.
- **AppContainer** (`di/AppContainer.kt`) — Manual DI. Creates PreferencesManager, Room database, WebSocketService, MonitorLogic.

### Data layer
- **Room** — `UsageDatabase` with `EventDao` and `RuleCounterDao`. Destructive migration.
- **SharedPreferences** — `PreferencesManager` reads/writes `coach_prefs` file. Stores focus data, monitored packages, rules, settings.
- **WebSocketService** — OkHttp WebSocket with exponential backoff reconnect. Exposes `SharedFlow<Map<String, Any?>>` for focus updates.

### UI layer (Jetpack Compose + Material 3)
- **Navigation** — Bottom nav (Apps, Stats) + top bar actions (Debug, Settings). `AppNavigation.kt`.
- **Screens** — AppsScreen, StatsScreen, SettingsScreen, DebugScreen, LogsScreen. Each has a ViewModel.
- **Theme** — Dark theme with indigo primary (`0xFF818CF8`), dark surface (`0xFF0F0F23`).

## Build

```bash
# Debug build and install
just run

# Release build
just build-release

# Release build and install
just install-release
```

Requires `JAVA_HOME=/opt/android-studio/jbr` (handled by justfile).

WebSocket URL configured via `.env` file with `WEBSOCKET_URL` variable (read from manifest meta-data at runtime).

## Permissions
- Usage Stats — monitoring foreground apps
- Overlay — showing focus interruption overlays
- Foreground Service (specialUse) — background monitoring
- Internet — WebSocket
- Boot Completed — restart service after reboot

## Key Files

1. `android/app/src/main/kotlin/.../service/MonitorLogic.kt` — Core focus logic
2. `android/app/src/main/kotlin/.../FocusMonitorService.kt` — Background service
3. `android/app/src/main/kotlin/.../OverlayManager.kt` — System overlay builder
4. `android/app/src/main/kotlin/.../data/websocket/WebSocketService.kt` — WebSocket client
5. `android/app/src/main/kotlin/.../data/preferences/PreferencesManager.kt` — All SharedPreferences access
6. `android/app/src/main/kotlin/.../ui/navigation/AppNavigation.kt` — App navigation
