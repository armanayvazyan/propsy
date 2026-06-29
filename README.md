# Propsy

[![Build](https://github.com/armanayvazyan/propsy/actions/workflows/build.yml/badge.svg)](https://github.com/armanayvazyan/propsy/actions/workflows/build.yml)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

IntelliJ plugin that shows configured `.properties` files as an **editable Key/Value table** in a tool window.

- Configure file paths per-project in **Settings | Tools | Propsy** (paths are relative to the project root).
- Pick a file from the dropdown in the **Propsy** tool window (bottom).
- Edit keys/values, add/delete rows. Writes go straight to disk and **preserve comments, blank lines, and key order** (uses the bundled Properties PSI, with full undo support).

## Requirements

- JDK 21
- IntelliJ IDEA 2026.1 or newer (`sinceBuild = 261`; Community or Ultimate)
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
build/distributions/propsy-0.1.2.zip
```

## Try it without installing (sandbox IDE)

Launches a throwaway IDE instance with the plugin already loaded:

```bash
./gradlew runIde
```

## Install into your IDE

1. Build the zip (`./gradlew buildPlugin`) — or download a prebuilt one.
2. In IntelliJ: **Settings | Plugins | ⚙ (gear) | Install Plugin from Disk…**
3. Select `build/distributions/propsy-0.1.2.zip`.
4. Restart the IDE when prompted.

## Use

1. Open a project.
2. **Settings | Tools | Propsy** → click **+**, pick the `.properties` file(s) you want to track.
3. Open the **Propsy** tool window (bottom toolbar, or **View | Tool Windows | Propsy**).
4. Select a file in the dropdown. Edit cells, or use the toolbar to add/delete rows and refresh from disk.

## Project layout

```
src/main/kotlin/io/github/armanayvazyan/propsy/
  PropsySettings.kt          per-project path list (PersistentStateComponent)
  PropsyConfigurable.kt      Settings | Tools page
  PropsyToolWindowFactory.kt tool window registration
  PropsyTablePanel.kt            dropdown + table + toolbar UI
  PropsyTableModel.kt            editable table model
  PropertiesFileBridge.kt       PSI read/write (preserves formatting)
src/main/resources/META-INF/plugin.xml
src/test/kotlin/io/github/armanayvazyan/propsy/   tests
```

## Contributing

Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for build
instructions and conventions, and [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) for
community expectations. Use the [issue templates](.github/ISSUE_TEMPLATE/) to
report bugs or request features. Notable changes are tracked in
[CHANGELOG.md](CHANGELOG.md).

## Security

To report a vulnerability, see [SECURITY.md](SECURITY.md). Please do not file
security issues publicly.

## License

Licensed under the [Apache License 2.0](LICENSE).
