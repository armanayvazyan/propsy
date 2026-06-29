# Contributing to Propsy

Thanks for your interest in contributing! Propsy is an open-source IntelliJ
Platform plugin and contributions of all kinds are welcome — bug reports, fixes,
features, docs, and tests.

## Getting started

You need:

- **JDK 21** (the Kotlin and Java toolchains are pinned to 21).
- Internet access on first build — Gradle downloads the IntelliJ Platform `2026.1`.

The Gradle wrapper is bundled, so no local Gradle install is required.

```bash
./gradlew test          # run all tests
./gradlew buildPlugin   # build installable zip → build/distributions/
./gradlew runIde        # launch a sandbox IDE with the plugin loaded
./gradlew verifyPlugin  # plugin-structure verification (also run in CI)
```

Run a single test class:

```bash
./gradlew test --tests "io.github.armanayvazyan.propsy.PropertiesFileBridgeTest"
```

## Project layout

All code lives under `src/main/kotlin/io/github/armanayvazyan/propsy/`. See
[CLAUDE.md](CLAUDE.md) for an architecture overview — data flows in one
direction: settings → tool window → PSI bridge → disk.

## Conventions

- **All PSI access must be wrapped** in `ReadAction` / `WriteCommandAction`.
  Follow `PropertiesFileBridge` rather than touching PSI directly elsewhere.
- Match the style of surrounding code (naming, comment density, idiom).
- Add or update tests for any behavior change. Tests extend
  `BasePlatformTestCase` and use `myFixture.configureByText(...)`.
- Keep commits focused and write clear commit messages.

## Submitting a change

1. Fork the repo and create a branch off `main`.
2. Make your change, with tests.
3. Run `./gradlew test verifyPlugin` and make sure both pass.
4. Open a pull request against `main`. Fill in the PR template and describe
   what changed and why.

CI runs `test → verifyPlugin → buildPlugin` on every push and PR to `main`;
your PR must be green before it can be merged.

## Changelog

User-facing changes are recorded in [CHANGELOG.md](CHANGELOG.md), following the
[Keep a Changelog](https://keepachangelog.com/) format. Add your change under the
`## [Unreleased]` section in the same PR.

The changelog is wired into the build via the
[`org.jetbrains.changelog`](https://github.com/JetBrains/gradle-changelog-plugin)
Gradle plugin, so updates flow automatically:

- The current version's notes are injected into the plugin's marketplace
  "What's New" (`change-notes`) at build time — no manual copy into `plugin.xml`.
- On release, the GitHub Release body is generated from the changelog.

### Releasing (maintainers)

1. `./gradlew patchChangelog` — moves `[Unreleased]` into a new `[<version>]`
   section dated today, and re-opens an empty `[Unreleased]`.
2. Commit the bumped `version` in `build.gradle.kts` and the patched changelog.
3. Tag and push: `git tag v<version> && git push origin v<version>`.
   The `Release` workflow signs the zip, attaches it to the GitHub Release with
   notes from `getChangelog`, and publishes to the JetBrains Marketplace.

## Reporting bugs and requesting features

Use the [issue templates](.github/ISSUE_TEMPLATE/). Include your IDE version,
plugin version, and reproduction steps for bugs.

## Code of Conduct

By participating you agree to abide by our
[Code of Conduct](CODE_OF_CONDUCT.md).

## License

By contributing, you agree that your contributions will be licensed under the
[Apache License 2.0](LICENSE), the same license that covers the project.
