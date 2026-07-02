# Propsy settings page — UX refresh + `.env` plugin status

**Date:** 2026-07-02
**Status:** Approved (design)
**Branch:** feat/dotenv-support-252 (follow-up to 0.1.4 .env work)

## Goal

Improve the **Settings → Tools → Propsy** page:

1. Add a clearer description of what the plugin does.
2. Add a `.env` support status row that reflects whether the
   `ru.adelf.idea.dotenv` plugin is installed & enabled — with an **Install…**
   button when it is not.
3. Swap the **Scan** action's icon from the refresh/reload icon to a download
   icon (label and behavior unchanged).

## Non-goals

- No changes to the bottom tool window (`PropsyTablePanel`) — its Refresh
  (reload-from-disk) action stays as-is; refresh semantics fit it.
- No changes to backends, discovery, `plugin.xml`, or `build.gradle.kts`.
- No custom plugin download/install code — installation goes through the IDE's
  standard Marketplace flow.
- No live re-render of the status row while the page is open (the Configurable is
  recreated each time the page opens, which is sufficient).

## Page layout (top → bottom)

Sections stacked vertically with light spacing (a vertical box / `BorderLayout`
composition consistent with the existing page).

1. **Intro description** — a short paragraph (HTML `JBLabel`, ~480px wrap):
   Propsy renders configured `.properties` and `.env` files as an editable
   Key/Value table in the bottom tool window; edits write straight to disk,
   preserving comments, blank lines and key order, with full undo. Retains the
   existing how-to hint (use **Scan** to auto-discover, **+** to add manually,
   edit the **Name** column to label each file — that name is what the tool
   window shows).

2. **`.env` support status row** — sits between the intro and the table.
   Reflects `DotEnvPlugin.isActive()`:
   - **Active** (installed & enabled): a subtle line `✓ .env support enabled`
     — muted foreground, a small success/check icon
     (e.g. `AllIcons.General.InspectionsOK` or `AllIcons.RunConfigurations.TestPassed`,
     final icon chosen in impl).
   - **Inactive** (missing or disabled): a note
     `.env editing needs the ".env files support" plugin.` followed by an
     **Install…** button. Clicking it opens **Settings → Plugins → Marketplace**
     pre-searched for `ru.adelf.idea.dotenv`.

3. **Files table** — the existing editable Name/Path `JBTable` with its
   `ToolbarDecorator` (+ / − / Scan). Unchanged except the **Scan** action's
   icon becomes `AllIcons.Actions.Download` (label "Scan" and behavior — merge
   `PropsyFiles.discoverAll` results — unchanged).

## Components

### `DotEnvPlugin` (new, `DotEnvPlugin.kt`)

A tiny object isolating the platform plugin-state query so it is testable and the
platform call lives in one place. References no `ru.adelf.*` classes.

```kotlin
object DotEnvPlugin {
    const val ID = "ru.adelf.idea.dotenv"
    /** True when the dotenv plugin is installed AND enabled (its backend is loaded). */
    fun isActive(): Boolean =
        PluginManagerCore.getPlugin(PluginId.getId(ID))?.isEnabled == true
}
```

### `PropsyConfigurable` (modified)

`createComponent()` composes the three sections. Two new private helpers:

- `buildDotEnvStatusRow(): JComponent` — returns the `✓ enabled` label when
  `DotEnvPlugin.isActive()`, else a panel with the note label + **Install…**
  button wired to `openDotEnvInMarketplace()`.
- `openDotEnvInMarketplace()` — opens the Plugins settings on the Marketplace tab
  pre-searched for the dotenv id. Intended API:

  ```kotlin
  ShowSettingsUtil.getInstance().showSettingsDialog(project, PluginManagerConfigurable::class.java) {
      it.openMarketplaceTab(DotEnvPlugin.ID)
  }
  ```

  The exact `PluginManagerConfigurable` open/search API is verified against the
  2025.2 platform during implementation; if `openMarketplaceTab` differs, fall
  back to opening the Plugins configurable and setting the search filter to the
  plugin id (a one-line adjustment — the button's behavior "open Plugins pre-
  searched for dotenv" is the contract).

The Scan action's icon argument changes from `AllIcons.Actions.Refresh` to
`AllIcons.Actions.Download`.

## Error handling

- `openDotEnvInMarketplace()` is a UI action; any failure to open settings is
  non-fatal (log/ignore). No project-state mutation.
- `DotEnvPlugin.isActive()` returns `false` for unknown/disabled plugins; never
  throws for a missing plugin.

## Testing

- **`DotEnvPluginTest`** (`BasePlatformTestCase`): asserts `DotEnvPlugin.isActive()`
  returns `true` in the test environment (the dotenv plugin is on the test
  classpath as a build dependency). Also asserts `DotEnvPlugin.ID ==
  "ru.adelf.idea.dotenv"` to lock the id string used by both detection and the
  install action.
- The Swing layout and the Marketplace-open action are not unit-tested,
  consistent with the existing page; verified via manual `runIde`.

## Files

- Create: `src/main/kotlin/io/github/armanayvazyan/propsy/DotEnvPlugin.kt`
- Modify: `src/main/kotlin/io/github/armanayvazyan/propsy/PropsyConfigurable.kt`
- Create (test): `src/test/kotlin/io/github/armanayvazyan/propsy/DotEnvPluginTest.kt`

## Changelog

Add to CHANGELOG `[Unreleased]` under **Changed**: improved the Propsy settings
page (clearer description, `.env` plugin status with a one-click install
shortcut, download icon for Scan).
