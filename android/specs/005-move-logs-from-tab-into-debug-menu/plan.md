# Plan: Move Logs into Debug Menu

## Tech Stack

- Language: Dart
- Framework: Flutter + Riverpod
- Testing: Manual (no test framework configured)

## Structure

Files to change:

```
lib/
├── app.dart                    # Remove Logs tab, keep 2-tab nav
├── views/
│   ├── debug_view.dart         # Add "Logs" entry that navigates to LogsView
│   └── logs_view.dart          # Wrap in Scaffold with AppBar (standalone page)
```

## Approach

### 1. Remove Logs from bottom navigation (`app.dart`)

- Remove `LogsView` from `_screens` list (keep AppsView + StatsView)
- Remove the Logs `BottomNavigationBarItem`
- Remove the `logs_view.dart` import
- Result: 2-tab bottom nav (Apps, Stats)

### 2. Add Logs entry to Debug menu (`debug_view.dart`)

- Add a new "Logs" section (or entry in Actions) with `Icons.list` icon
- On tap, push `LogsView` as a new route
- Add `logs_view.dart` import
- Place it at the top of the debug menu since it's the most-used debug tool

### 3. Make LogsView work as a standalone page (`logs_view.dart`)

- Currently `LogsView` is a tab body (no AppBar). When pushed as a route from DebugView (which also has no AppBar but is inside a Scaffold), LogsView needs its own AppBar with a back button so the user can return.
- Wrap the existing body in a `Scaffold` with `AppBar(title: Text('Logs'))`.
- Move the service status bar, overflow menu (refresh/export/clear) into the AppBar actions for a cleaner layout.

### 4. Refactor LogsView for readability (optional improvements)

The view is 721 lines but already reasonably organized. Practical improvements:

- **Extract the service status bar into the AppBar subtitle** — saves vertical space, puts status info where your eye goes naturally.
- **Move the overflow menu (refresh/export/clear) to AppBar actions** — standard pattern, frees up space in the search row.
- No structural splitting needed — the file is cohesive (one widget, one purpose). Splitting it into multiple files would add indirection without benefit.

## Risks

- **Tab index off-by-one**: After removing the Logs tab, make sure `_selectedIndex` stays within bounds (max index becomes 1). Low risk since we're reducing from 3 to 2 tabs.
- **LogsView used elsewhere**: Only referenced in `app.dart`. After the move it'll be referenced from `debug_view.dart` instead.

## Open Questions

None — straightforward move with minor UI polish.
