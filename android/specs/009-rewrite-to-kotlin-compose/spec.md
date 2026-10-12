# Spec: Rewrite Coach Android to Kotlin + Jetpack Compose

## Goal

Replace the Flutter UI and background Dart isolate with pure Kotlin + Jetpack Compose. Keep the same features. Remove the dual-engine architecture and all method channel IPC.

## What stays the same

- All user-facing behavior: overlay types, focus monitoring, WebSocket sync, stats, rules
- Android permissions: Usage Stats, Overlay, Battery Optimization, Boot Receiver
- Foreground service with notification
- WebSocket server protocol (same message format)
- SQLite database schema (events + rule_counters tables)
- SharedPreferences keys (for migration compatibility)

## What changes

- Flutter UI (5 screens) becomes Compose UI
- Flutter background isolate becomes Kotlin coroutines inside the service
- Riverpod state management becomes ViewModel + StateFlow
- Freezed models become Kotlin data classes
- Method channels and event channels are deleted entirely
- `sqflite` becomes Room
- `web_socket_channel` becomes OkHttp WebSocket
- `shared_preferences` stays as Android SharedPreferences (already used natively)
- Code generation (build_runner, freezed, json_serializable) is no longer needed

## Screens to rewrite

1. **Apps view** - Select monitored apps, toggle monitoring
2. **Stats view** - Daily focus stats, blocked app counts, usage charts
3. **Settings view** - Overlay customization, challenge settings, target app config
4. **Debug view** - Connection status, force reminder, service controls
5. **Logs view** - Activity log with filtering

## Non-goals

- No new features during the rewrite
- No iOS support
- No changes to the WebSocket server protocol
- No changes to overlay behavior or challenge types
