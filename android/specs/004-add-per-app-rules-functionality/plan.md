# Plan: Per-App Rules

**Spec**: User request — add per-app "rules" that trigger a custom popup every N-th app open, up to X times per day, with daily reset at midnight.

## Tech Stack

- Language: Dart + Kotlin
- Framework: Flutter + Riverpod
- Storage: SharedPreferences (rules config), SQLite (daily open counts + trigger counts)
- Testing: None configured

## Structure

New and modified files:

```
lib/
├── models/
│   ├── app_rule.dart                    # NEW: Rule data model
│   └── app_settings.dart                # MODIFY: add rules overlay appearance fields
├── services/
│   ├── usage_database.dart              # MODIFY: add rules table + daily counter queries
│   └── settings_service.dart            # MODIFY: load/save rules overlay settings
├── state_management/
│   └── providers/
│       └── app_rules_provider.dart      # NEW: Riverpod provider for rules CRUD
├── views/
│   └── settings_view.dart              # MODIFY: add "Rules Overlay" appearance section
├── widgets/
│   ├── app_detail_bottom_sheet.dart     # MODIFY: add rules list + add/edit/delete UI
│   └── rule_editor_dialog.dart          # NEW: dialog for creating/editing a rule
├── background_monitor_handler.dart      # MODIFY: check rules on app open
├── constants/
│   └── storage_keys.dart                # MODIFY: add rules storage + appearance keys

android/app/src/main/kotlin/.../
├── MainActivity.kt                      # MODIFY: read overlay type to pick coach vs rules settings
```

## Approach

### 1. Rule data model (`lib/models/app_rule.dart`)

A plain Dart class (no Freezed needed for this):

```dart
class AppRule {
  final String id;           // UUID
  final String packageName;  // which app this rule belongs to
  final int everyN;          // trigger every N-th open (e.g. 3 = every 3rd open)
  final int maxTriggers;     // max times to show popup per day (X)
}
```

Rules only define trigger logic. Overlay appearance for rules is configured separately (see step 5).

Serialization: `toJson()` / `fromJson()` for SharedPreferences storage.

Rules stored in SharedPreferences as a JSON map keyed by rule ID, under a single key `appRules`. This keeps it simple and readable by both UI and background engines.

### 2. Daily counters in SQLite (`usage_database.dart`)

Add a new table `rule_counters` to track per-rule daily state:

```sql
CREATE TABLE rule_counters (
  rule_id TEXT NOT NULL,
  date TEXT NOT NULL,        -- 'YYYY-MM-DD' format
  open_count INTEGER DEFAULT 0,
  trigger_count INTEGER DEFAULT 0,
  PRIMARY KEY (rule_id, date)
);
```

New methods on `UsageDatabase`:
- `incrementOpenCount(ruleId, date)` — bump open_count, return new value
- `incrementTriggerCount(ruleId, date)` — bump trigger_count, return new value
- `getCounters(ruleId, date)` — get current open_count and trigger_count
- `cleanupOldCounters(beforeDate)` — delete rows older than N days (housekeeping)

No migration needed — just add the table in the existing `onCreate`. Users can reinstall if needed.

Daily reset happens naturally: each day uses a new date key, so old counters are just ignored. No timer or midnight scheduling needed.

Cleanup: on each app start (in `_loadPersistedState`), call `cleanupOldCounters` to delete rows older than yesterday. This keeps the table at most 2 days of data.

### 3. Rules provider (`app_rules_provider.dart`)

Riverpod `NotifierProvider` managing a `Map<String, AppRule>` (keyed by rule ID):

- `addRule(rule)` — add to map, persist
- `updateRule(rule)` — update in map, persist
- `deleteRule(ruleId)` — remove from map, persist
- `getRulesForApp(packageName)` — filter rules for a specific app

Persistence: read/write JSON to SharedPreferences under `StorageKeys.appRules`.

Also calls `BackgroundMonitorHandler.updateRules(rules)` to sync rules to the background engine immediately (same pattern as `updateMonitoredPackages`).

### 4. Background monitor logic changes (`background_monitor_handler.dart`)

Add to `_handleAppChanged(packageName)`:

```
1. Get all rules for this packageName
2. For each rule:
   a. Get today's date string
   b. Increment open_count for (rule.id, today) in SQLite
   c. If open_count % rule.everyN == 0:
      - Get trigger_count for (rule.id, today)
      - If trigger_count < rule.maxTriggers:
        - Increment trigger_count
        - Show rule overlay (uses rules overlay appearance settings)
        - Break (show only one rule popup per app open)
```

This runs independently of focus state. Both can fire on the same app open — if the user is focusing and a rule triggers, both overlays show (user dismisses one, then sees the other). The existing focus overlay logic stays as-is; rules are checked separately after it.

New static fields:
- `static Map<String, AppRule> _rules = {};`

New methods:
- `static Future<void> updateRules(Map<String, AppRule> rules)` — called from UI provider
- `static Future<void> _checkRules(String packageName)` — the logic above
- `static Future<void> _showRuleOverlay(String packageName)` — calls native `showOverlay` with `overlayType: "rule"` param

Load rules from SharedPreferences in `_loadPersistedState()`.

### 5. Rules overlay appearance settings

Two independent overlay configurations, each with their own settings:

| Setting | Coach keys (existing) | Rules keys (new) |
|---|---|---|
| Message | `settingsOverlayMessage` | `settingsRulesOverlayMessage` |
| Button text | `settingsOverlayButtonText` | `settingsRulesOverlayButtonText` |
| Background color | `settingsOverlayColor` | `settingsRulesOverlayColor` |
| Button color | `settingsOverlayButtonColor` | `settingsRulesOverlayButtonColor` |

**Changes needed:**

- **`StorageKeys`**: Add 4 new keys for rules overlay appearance
- **`AppSettings` model**: Add 4 new fields (`rulesOverlayMessage`, `rulesOverlayButtonText`, `rulesOverlayColor`, `rulesOverlayButtonColor`)
- **`SettingsService`**: Load/save the new fields (same pattern as existing coach settings)
- **`SettingsView`**: Add a "Rules Overlay" section below the existing "Coach Overlay" section, with the same widgets (text fields, color pickers, preview, reset)
- **`MainActivity.kt`**: Modify `showOverlay` to accept an `overlayType` param. When `"rule"`, read from `flutter.settingsRulesOverlay*` keys instead of `flutter.settingsOverlay*` keys. Default behavior (no type or `"coach"`) stays unchanged.

### 6. UI: rules in bottom sheet (`app_detail_bottom_sheet.dart`)

Add a "Rules" section below the "Coach Enabled" checkbox:

```
─────────────────────────────
  App Name
─────────────────────────────
  Today's usage         2h 15m
  Opened during focus      3x
─────────────────────────────
  [✓] Coach Enabled
─────────────────────────────
  Rules
  ┌─────────────────────────┐
  │ Every 3rd open (0/5)  ✕ │  ← tap to edit, ✕ to delete
  └─────────────────────────┘
  [+ Add Rule]
─────────────────────────────
```

Each rule tile shows:
- "Every {N}th open" description
- Current trigger count / max for today: "(2/5)"
- Delete button (X icon)
- Tap to edit

"+ Add Rule" button opens the rule editor dialog.

### 7. Rule editor dialog (`rule_editor_dialog.dart`)

A simple `AlertDialog` or `showDialog` with:
- **Every N-th open**: number input (default: 3)
- **Max triggers per day**: number input (default: 5)
- Save / Cancel buttons

Used for both creating and editing rules. Pre-fills values when editing.

## Order of Implementation

1. `AppRule` model
2. Database migration + counter methods
3. `StorageKeys` update (rules storage key + 4 rules overlay appearance keys)
4. Rules overlay appearance: `AppSettings` model, `SettingsService`, `SettingsView` section
5. Native overlay: `MainActivity.kt` — overlay type param to pick coach vs rules settings
6. Background monitor handler: load rules, check rules, show rule overlay
7. Rules provider
8. Rule editor dialog
9. Bottom sheet UI updates

## Risks

- **Race condition on counters**: Background isolate reads/writes SQLite counters. Since there's only one background isolate, no concurrency issue within that engine. The UI engine only reads counters for display, so no write conflicts.
- **Multiple rules firing on same open**: The plan shows only one rule popup per app open (first matching rule wins). This prevents popup spam.
- **SharedPreferences sync delay**: After UI saves rules, background engine needs to reload. The `updateRules` call handles this immediately, same as `updateMonitoredPackages`.

## Open Questions

None — scope is clear.
