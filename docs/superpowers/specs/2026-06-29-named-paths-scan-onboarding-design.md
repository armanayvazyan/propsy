# Named Paths, Scan & Onboarding — Design

Date: 2026-06-29

## Problem

The Settings | Tools | Properties Table View page gives users no guidance — an
empty list with no explanation of what to add or why. Adding files is manual,
one chooser dialog at a time. The tool-window combo shows raw relative paths
(`/src/main/resources/config.properties`), which are long and hard to tell apart.

## Goals

1. Onboarding: explain the page and its actions.
2. One-click **Scan** that discovers all `.properties` files across modules and adds them.
3. Each configured file carries an editable **Name** (default = its module name).
4. Tool-window selector shows the **Name**, not the path.

Example end state in the combo:

```
cloud-e2e
cloud-e2e-gh
```

instead of:

```
/src/main/resources/config.properties
/src/gh/config.gh.properties
```

## Data Model

`PropsViewSettings` (`@State` "PropsViewSettings", storage `propsTableView.xml`)
moves from a `List<String>` of paths to a list of named entries.

```kotlin
class PathEntry {              // serializable bean: no-arg ctor, var fields
    var name: String = ""
    var path: String = ""      // relative to project base dir, '/' separated
}

class State {
    var entries: MutableList<PathEntry> = mutableListOf()
    // legacy — read only, for migration from pre-name versions
    var paths: MutableList<String> = mutableListOf()
}
```

Public accessor: `var entries: List<PathEntry>` returning a defensive copy.
The old public `paths` accessor is removed.

### Migration (auto)

In `loadState`, after assigning state: if `entries` is empty and legacy `paths`
is non-empty, convert each path to `PathEntry(name = path.substringAfterLast('/'),
path = path)`, then clear `paths`. This runs once; subsequent saves persist only
`entries`. No data loss for existing users.

## Scanner

New `object PropertiesScanner`:

```kotlin
fun scan(project: Project): List<PathEntry>
```

- Enumerate via
  `FilenameIndex.getAllFilesByExt(project, "properties", GlobalSearchScope.projectScope(project))`.
- For each `VirtualFile`, consult `ProjectFileIndex.getInstance(project)`:
  - skip if `isExcluded(vf)` (covers `out/`, `build/`, `target/`, generated roots),
  - skip if `isInLibrary(vf)`,
  - skip if not in content (`!isInContent(vf)`).
- `name = fileIndex.getModuleForFile(vf)?.name` — fallback to project base-dir name when null.
- `path = VfsUtilCore.getRelativePath(vf, base)` — skip files with no relative path (outside base).
- Duplicate names are allowed (user can rename afterward).
- Return sorted by name, then path, for stable display.

Runs inside a `ReadAction`.

## Settings UI — `PropsViewConfigurable`

Layout (top → bottom):

1. **Onboarding header** — an HTML `JBLabel`:
   > Configure which `.properties` files appear in the Properties Table tool
   > window. Click **Scan** to auto-discover every `.properties` file in your
   > modules, or **+** to add one manually. Edit the **Name** column to label
   > each file — that name is what the tool window shows.

2. **Entries table** — `JBTable` over a small `AbstractTableModel`:
   - Column 0 **Name** — editable.
   - Column 1 **Path** — read-only.

   Wrapped by `ToolbarDecorator` with actions:
   - **Add** (`+`): file chooser restricted to `.properties` inside the project;
     resolves the relative path and a default name = module name of the chosen
     file (fallback: filename). Skips if the path is already present.
   - **Scan** (extra action, refresh-style icon): runs `PropertiesScanner.scan`,
     merges entries whose `path` is not already in the table (dedupe against
     existing), then shows an info message "Added N file(s)." (or "No new
     `.properties` files found.").
   - **Remove** (`-`): removes selected row.

`isModified` / `apply` / `reset` operate over the entries list. `apply` writes
`settings.entries` and publishes `CHANGED_TOPIC`.

## Tool Window — `PropsTablePanel`

- Combo model holds `PathEntry` objects.
- `combo.renderer = SimpleListCellRenderer` rendering `entry.name`
  (fallback to `entry.path` if name blank).
- Selection resolves the file via `entry.path` through `PropertiesFileBridge.resolve`.
- Empty/keep-selection logic mirrors the current `refreshAll`, keyed on `path`.
- Status text when nothing configured is unchanged.

## Testing

- `PropsViewSettingsTest`:
  - entries round-trip through `getState`/`loadState`,
  - legacy `paths` → `entries` migration (name = filename, path preserved),
  - defensive-copy behaviour of the `entries` accessor.
- New `PropertiesScannerTest` (`BasePlatformTestCase`): create a `.properties`
  file in the fixture, assert `scan` returns an entry whose path matches.

## Out of Scope

- Re-scan/watch on filesystem changes (Scan is manual).
- Grouping or reordering entries beyond the sorted scan output.
- Per-entry enable/disable.
