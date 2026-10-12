# Plan: App Detail Bottom Sheet with Coach Toggle

**Spec**: User request — remove checkboxes from app list; tap app to open detail view with stats and "Coach Enabled" toggle.

## Tech Stack

- Language: Dart
- Framework: Flutter + Riverpod
- Storage: SQLite (existing `UsageDatabase`), SharedPreferences (existing)
- Testing: None configured

## Structure

Changes to existing files, plus one new widget:

```
lib/
├── views/
│   └── apps_view.dart              # MODIFY: remove checkbox, add onTap → bottom sheet
├── widgets/
│   └── app_detail_bottom_sheet.dart # NEW: bottom sheet with stats + toggle
```

## Approach

### 1. Create `AppDetailBottomSheet` widget

New file: `lib/widgets/app_detail_bottom_sheet.dart`

A `StatefulWidget` that receives:
- `AppInfo app` (name + packageName)
- `bool isCoachEnabled` (current selection state)
- `ValueChanged<bool> onToggle` callback

Displays:
- **Header**: App name
- **Today's usage time**: Call `FocusService.getAppUsageStats(DateTime.now())`, filter to matching package. Show formatted time (e.g., "2h 15m").
- **Times opened during focus**: Query `UsageDatabase.instance.getBlockedAppsForDay(DateTime.now())`, filter to matching package. Show count.
- **Coach Enabled**: `CheckboxListTile` with title "Coach Enabled". Calls `onToggle` on change.

Use `showModalBottomSheet` to display it.

### 2. Modify `AppsView` list items

In `apps_view.dart`:

- Remove the `IconButton` with checkbox icon from `leading`.
- Add a small indicator (e.g., colored dot or subtle icon) on the trailing side to show coach-enabled status at a glance.
- Make the entire `ListTile` tappable via `onTap` — opens `AppDetailBottomSheet`.
- In the `onTap` handler, call `showModalBottomSheet` with the detail widget.
- The bottom sheet's `onToggle` callback calls `ref.read(appSelectionProvider.notifier).toggleApp(packageName)`.

### 3. Data loading in the bottom sheet

The bottom sheet loads stats on init:
- `FocusService.getAppUsageStats(DateTime.now())` — already returns `List<AppUsageEntry>`. Filter by `packageName`.
- `UsageDatabase.instance.getBlockedAppsForDay(DateTime.now())` — already returns `List<BlockedAppEntry>`. Filter by `packageName`.

Both calls are async, so show a small loading indicator while fetching.

### 4. Visual design

List item (after change):
```
[ App Name                    (coach icon if enabled) ]
```

Bottom sheet:
```
─────────────────────────────
  App Name
─────────────────────────────
  Today's usage         2h 15m
  Opened during focus      3x
─────────────────────────────
  [✓] Coach Enabled
─────────────────────────────
```

## Risks

- **Stats load delay in bottom sheet**: The two async calls could cause a flicker. Mitigation: show a compact loading spinner, stats section is small.
- **No usage data available**: If the user hasn't granted Usage Stats permission, `getAppUsageStats` returns empty. Mitigation: show "No data" or "0m" gracefully.

## Open Questions

- None — the scope is clear and contained.
