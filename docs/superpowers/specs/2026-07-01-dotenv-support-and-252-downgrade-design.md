# Propsy 0.1.4 — `.env` support + downgrade to platform 252

**Date:** 2026-07-01
**Status:** Approved (design)

## Goal

Ship the next Propsy release with two changes:

1. **`.env` support** — render and edit `.env*` files in the same Key/Value tool
   window as `.properties`, via the JetBrains-maintained `.env` plugin
   (`ru.adelf.idea.dotenv`).
2. **Downgrade the minimum IDE from 2026.1 (`sinceBuild 261`) to 2025.2
   (`sinceBuild 252`)** so the plugin installs on older IDEs.

**Hard constraint:** the tool window UI and UX do not change. `.env` files appear
in the same combo picker and table, and edit identically to `.properties`.

## Non-goals

- No new UI surfaces, actions, or settings controls beyond what already exists.
- No `.env`-specific semantics beyond flat `KEY=VALUE` (no `${VAR}` interpolation
  resolution, no `export ` rewriting, no multi-line/quoted-value normalization
  beyond preserving what the user typed).
- No unrelated refactors.

## Architecture

Data flow is unchanged: settings → tool window → bridge → disk. The change is that
the single `PropertiesFileBridge` becomes one of two backends behind a shared
interface.

### `PropsyBridge` interface (new)

Extract a backend-neutral interface so the table code depends on it, not on
Properties PSI:

```
interface PropsyBridge {
    fun resolve(project: Project, relPath: String): Target?      // opaque per-backend handle
    fun entries(target: Target): List<Entry>
    fun setValue(project: Project, entry: Entry, newValue: String)
    fun setKey(project: Project, entry: Entry, newKey: String)
    fun addEntry(project: Project, target: Target, key: String, value: String): Boolean  // false if dup
    fun deleteEntry(project: Project, entry: Entry)
}
```

- `Entry(key, value, handle)` — `handle` is opaque: an `IProperty` for the
  Properties backend, a line/PSI reference for the `.env` backend. The table never
  inspects it.
- `Target` — opaque resolved file handle per backend.
- Contract shared by both backends: reads in document order; `addEntry` appends
  after the last entry (never alphabetical) and rejects duplicate keys; all writes
  run in a named `WriteCommandAction` (undoable) and preserve comments, blank
  lines, and key order.

### Backends

- **`PropertiesFileBridge`** — the existing implementation, adapted to the
  interface. Behavior unchanged; existing tests must still pass.
- **`DotEnvBridge`** (new) — resolves `.env*` files; **reads** via the dotenv
  plugin PSI; **writes** via Document edits inside a `WriteCommandAction`
  (line-based edit to the target key's line / append a new line), which sidesteps
  reliance on any dotenv-PSI mutation API and still preserves layout + undo.

### Backend selection

A resolver picks the backend by file:

- Properties PSI file → `PropertiesFileBridge`.
- Filename matches `.env` or `.env.*` → `DotEnvBridge` (only registered when the
  dotenv plugin is present; see wiring).

`PropsyTableModel` and `PropsyTablePanel` are refactored to call the interface
only. No behavioral change → **UI/UX identical**.

## Optional dependency wiring

`.env` support is gated on the dotenv plugin; `.properties` works without it.

- `plugin.xml`:
  `<depends optional="true" config-file="propsy-dotenv.xml">ru.adelf.idea.dotenv</depends>`
- `propsy-dotenv.xml` (new) — registers the `DotEnvBridge` backend (and any
  dotenv-specific wiring) **only** when the dotenv plugin is loaded. Without it:
  Propsy loads, `.properties` fully works, `.env` paths simply fail to resolve.
- `build.gradle.kts` — add the dotenv plugin as a compile dependency:
  `plugin("ru.adelf.idea.dotenv:<version compatible with 252>")`.

## Scanner + discovery

`PropertiesScanner` also discovers `.env*` files.

- `.env` has no conventional extension, so `getAllFilesByExt(project, "properties", …)`
  will not find them. Add a filename-based pass (match `.env` and `.env.*`) using
  the filename index, unioned with the existing `.properties` results.
- Same filtering (in-content, non-excluded, non-library) and same
  module-based naming as today.
- The `.env` discovery pass is skipped/empty when the dotenv plugin is absent.
- Settings file chooser: allow picking `.env*` files in addition to `.properties`.

## Downgrade to platform 252 (orthogonal)

- `build.gradle.kts`: `intellijIdea("2025.2")`; `sinceBuild = "252"`.
- Verify every used API exists in 2025.2: `project.guessProjectDir()`,
  `WriteCommandAction.runWriteCommandAction(...)`, `FilenameIndex`,
  `ProjectFileIndex`, Properties PSI (`PropertiesFile`, `IProperty`,
  `file.addPropertyAfter`). All expected to exist in 252.
- Run `test`, `verifyPlugin`, `runIde` against 252.
- Update README min-IDE note (it currently lists `2024.2`; make it accurate to
  2025.2).

## Testing

- **`DotEnvBridgeTest`** — mirrors `PropertiesFileBridgeTest`: entries read in
  order; set value preserves comments/blank lines/order; set key; `addEntry`
  appends and rejects duplicates; delete. Uses `myFixture.configureByText(".env", …)`.
  Guarded so it is meaningful only when the dotenv plugin is on the test classpath
  (added via the build dependency).
- **Existing `PropertiesFileBridgeTest`** — unchanged, must pass against the
  refactored interface.
- **Selection test** — resolver routes `.env` vs `.properties` to the correct
  backend.

## Changelog / version

- `version = "0.1.4"` in `build.gradle.kts`.
- CHANGELOG `[Unreleased]`:
  - **Added** — `.env` file support (via the `.env files support` plugin).
  - **Changed** — minimum IDE lowered to 2025.2.
- `patchChangelog` on release.

## Implementation order (first step is a spike)

1. **Spike:** confirm whether the dotenv PSI exposes any value/key mutation. If
   not (expected), the Document-based write path in `DotEnvBridge` stands as
   designed. This decision affects only `DotEnvBridge` internals, not the
   interface.
2. Extract `PropsyBridge` interface; adapt `PropertiesFileBridge`; refactor
   `PropsyTableModel`/`PropsyTablePanel` to the interface (green tests, no UX
   change).
3. Add `DotEnvBridge` + `propsy-dotenv.xml` + optional `<depends>` + build
   dependency.
4. Extend `PropertiesScanner` + settings file chooser for `.env*`.
5. Downgrade to 252; verify.
6. Version bump + changelog.
