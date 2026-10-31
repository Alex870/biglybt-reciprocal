# Changelog

## 0.4.0 — unreleased

Everything in 0.3.0, plus phases 2–5 of docs/DESIGN.md.

### Budget protection
- **Peers that have nothing we want are no longer given unlimited upload** (the biggest leak in 0.2.x). By default they get no free start and are limited like any other peer; credit they have already earned is kept. New setting "Peers that have nothing we want" also offers a free-start allowance or the old unlimited behaviour.
- **Peers are remembered across reconnects.** Totals are kept per torrent and IP address, so disconnecting no longer wipes a peer's record or earns it a new free start. Entries are forgotten after 24 hours unseen (configurable) or when the torrent completes; optionally saved across restarts.
- **Free-upload cap per torrent:** once unreciprocated upload reaches 20% of the torrent size (configurable, 0 = off), free starts end for that torrent.
- The free start and the "unlimited when ahead" margin can be scaled to torrent size; by default the free start is the smaller of 10 MB and 1% of the torrent.
- Peers on the local network are never limited (configurable).

### Reacting to peers
- A returning peer's standing is applied the moment it connects, instead of at the next pass (replaces the old non-working "adjust on new" checkbox).
- Limits are lifted the moment a torrent stops downloading.
- Turning the plugin on or off takes effect immediately; the timer runs at the update interval instead of waking every second.

### Visibility
- New columns: **Reciprocal** and **Reciprocal Delta** in the Peers table, **Reciprocal Budget** in the torrents tables (upload so far vs your budget, red when projected to exceed it).
- Session totals in the plugin view's status line.
- **Dry run** mode: logs what it would do without applying limits.

### Internal
- No longer uses BiglyBT internal classes; the receive rate is measured from the ledger.
- Logic split into `Engine`, `Ledger`, `ReciprocityPolicy` and `LimitController`, with 43 unit tests.

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
