# Properties Table View

IntelliJ plugin that shows configured `.properties` files as an **editable Key/Value table** in a tool window.

- Configure file paths per-project in **Settings | Tools | Properties Table View** (paths are relative to the project root).
- Pick a file from the dropdown in the **Properties Table** tool window (bottom).
- Edit keys/values, add/delete rows. Writes go straight to disk and **preserve comments, blank lines, and key order** (uses the bundled Properties PSI, with full undo support).

## Requirements

- JDK 21
- IntelliJ IDEA 2024.2 or newer (Community or Ultimate)
- Internet access on first build (Gradle downloads the IntelliJ Platform)

## Build

The project uses the [IntelliJ Platform Gradle Plugin 2.x](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html). The Gradle wrapper (Gradle 9.3) is bundled — no local Gradle install needed.

```bash
# Run the tests
./gradlew test

# Build the installable plugin zip
./gradlew buildPlugin
```

The plugin zip lands in:

```
build/distributions/properties-table-view-0.1.0.zip
```

## Try it without installing (sandbox IDE)

Launches a throwaway IDE instance with the plugin already loaded:

```bash
./gradlew runIde
```

## Install into your IDE

1. Build the zip (`./gradlew buildPlugin`) — or download a prebuilt one.
2. In IntelliJ: **Settings | Plugins | ⚙ (gear) | Install Plugin from Disk…**
3. Select `build/distributions/properties-table-view-0.1.0.zip`.
4. Restart the IDE when prompted.

## Use

1. Open a project.
2. **Settings | Tools | Properties Table View** → click **+**, pick the `.properties` file(s) you want to track.
3. Open the **Properties Table** tool window (bottom toolbar, or **View | Tool Windows | Properties Table**).
4. Select a file in the dropdown. Edit cells, or use the toolbar to add/delete rows and refresh from disk.

## Project layout

```
src/main/kotlin/com/example/propstableview/
  PropsViewSettings.kt          per-project path list (PersistentStateComponent)
  PropsViewConfigurable.kt      Settings | Tools page
  PropsTableToolWindowFactory.kt tool window registration
  PropsTablePanel.kt            dropdown + table + toolbar UI
  PropsTableModel.kt            editable table model
  PropertiesFileBridge.kt       PSI read/write (preserves formatting)
src/main/resources/META-INF/plugin.xml
src/test/kotlin/com/example/propstableview/   tests
```
