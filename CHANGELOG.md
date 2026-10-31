# Changelog

## 0.3.0 — unreleased

### Fixed
- Peers at break-even who were sending nothing could be given **unlimited** upload (a computed limit of 0 means unlimited in BiglyBT). Every throttled limit is now at least the minimum rate.
- Upload/download totals were rounded down to whole MB before comparing, so the balance could be off by up to 1 MB and often read as exactly 0.
- The minimum upload rate accepted 0, negative numbers, `NaN` and `Infinity`; it must now be a number greater than 0.
- An error with one peer (for example a missing peer ID) could stop the whole pass; errors are now isolated per peer and logged once.
- Limits set by you or other plugins are no longer overwritten. Previously every peer on a completed torrent was reset to unlimited on every pass.
- Turning the plugin off, or unloading it, now lifts every limit it set.

### Changed
- **Behaviour change:** peers in near-total deficit now get the minimum rate instead of having upload switched off. The old behaviour is available with the new "Stop uploading entirely to peers in near-total deficit" setting.
- "Enable debug logging" and "Logging level" are merged into one **Logging level** setting (0 = off). Existing debug settings are migrated. Limit-change messages are only shown at level 1 or higher.
- Removed the non-functional "Make adjustments to new clients immediately" checkbox (to be redesigned; see docs/DESIGN.md, Phase 4).
- Package renamed to `io.github.alex870.reciprocal`. The plugin ID is unchanged, so installing over 0.2.x keeps your settings.

### Project
- Gradle build with unit tests; GitHub Actions builds every push and publishes tagged releases.
- Removed BiglyBT runtime files and build output that had been committed by mistake. Release jars now live on the GitHub Releases page.

## 0.2.6
- Last release built from the Eclipse project.
