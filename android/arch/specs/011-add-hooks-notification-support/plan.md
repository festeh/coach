# Plan: Hook Results — Notifications & History

## Tech Stack

- Language: Kotlin
- Framework: Jetpack Compose + Room + OkHttp WebSocket
- Storage: Room DB (new `hook_results` table, existing `coach_usage.db`)
- Testing: None (no existing test infrastructure)

## Structure

New and changed files:

```
android/app/src/main/kotlin/com/example/coach_android/
├── data/
│   ├── db/
│   │   ├── HookResultEntity.kt          # NEW — Room entity + DAO
│   │   └── UsageDatabase.kt             # CHANGE — add entity + DAO, bump version
│   └── websocket/
│       └── WebSocketService.kt          # CHANGE — emit hook_result messages on new flow
├── HookNotificationManager.kt           # NEW — notification channel + display
├── FocusMonitorService.kt               # CHANGE — wire hook flow → notification + DB
├── di/
│   └── AppContainer.kt                  # CHANGE — expose hookResultDao
└── ui/
    ├── hooks/
    │   ├── HooksScreen.kt               # NEW — scrollable list of hook results
    │   └── HooksViewModel.kt            # NEW — loads from Room
    └── navigation/
        └── AppNavigation.kt             # CHANGE — add Hooks tab or route
```

## Approach

### 1. Room entity and DAO (`HookResultEntity.kt`)

Create `HookResultEntity` with fields matching the server payload:

```kotlin
@Entity(tableName = "hook_results", indices = [Index("created_at")])
data class HookResultEntity(
    @PrimaryKey val id: String,           // server-assigned ID
    @ColumnInfo(name = "hook_id") val hookId: String,
    val content: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,  // epoch seconds, set on insert
)
```

DAO operations:
- `insert(result)` — insert one result
- `getAll(): Flow<List<HookResultEntity>>` — return all ordered by `created_at DESC`, limit 1000
- `count(): Int` — count rows
- `deleteOldest(keepCount: Int)` — delete everything except the newest `keepCount` rows

Cleanup runs after each insert: if count > 1000, delete oldest.

### 2. Database migration (`UsageDatabase.kt`)

- Add `HookResultEntity::class` to `@Database(entities = [...])`
- Add `abstract fun hookResultDao(): HookResultDao`
- Bump version from 1 to 2 (destructive migration is already configured, so no manual migration needed)

### 3. New WebSocket flow (`WebSocketService.kt`)

Add a second `SharedFlow` for hook results alongside the existing `focusUpdates`:

```kotlin
private val _hookResults = MutableSharedFlow<Map<String, Any?>>(extraBufferCapacity = 16)
val hookResults: SharedFlow<Map<String, Any?>> = _hookResults.asSharedFlow()
```

In `handleMessage()`, add a branch for `type == "hook_result"`:

```kotlin
"hook_result" -> {
    scope.launch { _hookResults.emit(dataMap) }
}
```

This keeps focus handling untouched and gives downstream code a clean stream.

### 4. Hook notification manager (`HookNotificationManager.kt`)

New class following `PopNotificationManager` pattern:

- Channel: `hook_results` / "Hook Results" / IMPORTANCE_DEFAULT (sound + vibration)
- Uses `PowerManager.isInteractive()` to check if screen is on before showing
- Content: shows `hook_id` as title, truncated `content` as body
- Tap opens app (same pattern as PopNotificationManager)
- **Stacked notifications**: each result gets a unique notification ID (1003 + auto-increment counter), grouped under `GROUP_KEY_HOOKS`. Summary notification (ID 1003) shows count. Individual notifications dismissible.

**"Active" definition**: `PowerManager.isInteractive()` returns true when the screen is on. This is a reliable system API available since API 20. No broadcast receiver needed — we just check at the moment a hook result arrives.

### 5. Wire it all together (`FocusMonitorService.kt`)

In `initializeMonitorLogic()`, after existing flow collectors, add:

```kotlin
serviceScope.launch {
    container.webSocketService.hookResults.collect { data ->
        // 1. Save to Room
        val entity = HookResultEntity(
            id = data["id"] as? String ?: UUID.randomUUID().toString(),
            hookId = data["hook_id"] as? String ?: "unknown",
            content = data["content"] as? String ?: "",
            createdAt = System.currentTimeMillis() / 1000,
        )
        container.hookResultDao.insert(entity)
        container.hookResultDao.cleanup(1000)

        // 2. Notify if screen is on
        hookNotificationManager.showIfActive(entity)
    }
}
```

### 6. DI (`AppContainer.kt`)

Expose the new DAO:

```kotlin
val hookResultDao = database.hookResultDao()
```

### 7. Hooks screen (`HooksScreen.kt` + `HooksViewModel.kt`)

**ViewModel** — `AndroidViewModel` following `StatsViewModel` pattern:
- Creates its own `UsageDatabase` instance (same pattern as StatsViewModel)
- Exposes `StateFlow<List<HookResultEntity>>` from DAO's `Flow` query
- No pagination needed — Room Flow + LazyColumn handles 1000 items fine

**Screen** — follows `LogsScreen` pattern:
- `LazyColumn` with hook result cards
- Each card shows: hook ID (subtitle), content (body), timestamp (formatted)
- Content shown as full text (these are AI coaching messages, want to read them)
- No search/filter needed for v1 — just a reverse-chronological list

### 8. Navigation (`AppNavigation.kt`)

Add "Hooks" as a third bottom tab:

```kotlin
data object Hooks : Screen("hooks", "Hooks", Icons.Default.Notifications)
```

Update `bottomTabs` to include it. Three tabs is standard and keeps it accessible.

Also add the title mapping in the top bar.

## Risks

- **DB version bump with destructive migration**: Existing events and rule counters will be lost. This is the current behavior (already configured). Users of this app expect it — CLAUDE.md says "Destructive migration." If this is a problem, we could add a proper migration instead.
- **Notification spam**: If hooks fire frequently, notifications stack up. Mitigation: Android notification grouping — each result gets a unique notification ID, grouped under a summary. User can dismiss individually or all at once.
- **Large content in notifications**: AI responses can be long. Mitigation: truncate to ~200 chars in notification, full text visible in the Hooks screen.

## Open Questions

- Should the Hooks tab show an unread badge/count? (Skipping for v1 — can add later.)
- Should tapping the notification navigate directly to the Hooks screen? (For v1: just opens the app, same as focus reminder.)
