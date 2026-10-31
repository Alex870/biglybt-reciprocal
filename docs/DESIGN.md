# Reciprocal — Design & Roadmap

Status: draft · Covers plugin version 0.2.7 → 1.0

## 1. Purpose

Reciprocal exists to **protect a fixed upload budget while a torrent is still downloading.**

The user sets a share-ratio quota in BiglyBT (e.g. 1.2× the torrent size). Without this plugin, on a high-bandwidth symmetric connection that quota is routinely exhausted — often reaching 3× or more — *before the download completes*, mostly to peers who give little or nothing back. Reciprocal makes sure that, during downloading, upload goes preferentially to peers who reciprocate.

Once the download is complete there is no reliable way to judge reciprocity, and the user's interest is to discharge the share obligation as fast as possible (so files can be moved freely afterwards). Therefore **all limits are lifted at completion.**

### Non-goals

- Maximising total upload to the swarm or achieving work-conserving bandwidth usage.
- Replacing or tuning BitTorrent's choking algorithm (upload slots are assumed plentiful).
- Policing peers on seeding torrents.

### Design principles

1. Every byte sent to a non-reciprocating peer during download is budget lost. Default behaviour must fail *closed* (throttled), never *open* (unlimited).
2. Mutual generosity converges: the side that is ahead is more generous, the other side follows, and the pair levels out at the highest rate both can sustain. Preserve this property.
3. Only touch rate limits the plugin itself set.
4. The limit calculation is a pure, unit-tested function, independent of BiglyBT.

## 2. Current behaviour (0.2.7)

Every `check_secs` (default 30 s), for each torrent in `ST_DOWNLOADING`, for each peer:

| Condition | Upload limit |
|---|---|
| Peer not interesting to us | unlimited |
| Sent to peer ≤ `start_threshold` (10 MB) | unlimited |
| Balance (received − sent) ≥ `ignore_margin` (100 MB) | unlimited |
| Balance < 0 | `rate × (1 − severity)`, severity = −balance / margin; floor `min_ul_rate`; disabled when severity > 0.99 |
| 0 ≤ balance < margin | `rate × (1 + 0.5 × balance / margin)`; at least `balance_MB` KB/s |

`rate` = peer's smoothed receive rate. Torrents not downloading: every peer set unlimited.

### Known defects

| ID | Location | Defect |
|---|---|---|
| B1 | `adjustPeer` | `long / int` division truncates MB totals before the float cast; balance has up to 1 MB error and is frequently exactly 0. |
| B2 | positive branch | No `min_ul_rate` floor. Balance 0 and rate 0 ⇒ limit 0 ⇒ **unlimited**. |
| B3 | `min_ul_rate` | Accepts 0, negatives, `NaN`, `Infinity`; ≤ 0 reopens B2 in the negative branch. |
| B4 | timer loop | `bytesToHex(peer.getId())` runs unconditionally; a null ID would throw, and no exception handling exists, aborting the pass for remaining peers. |
| B5 | seeding branch | Overwrites every peer's limit to unlimited each pass, including user-set limits. |
| B6 | disable/unload | Limits set by the plugin persist after disable; no `UnloadablePlugin`. |
| B7 | logging | "Action:" lines logged regardless of debug; log area grows without bound. |
| B8 | UI | "Make adjustments to new clients immediately" is shown but does nothing. |
| G1 | `isInteresting()` gate | Peers with nothing we want get unlimited upload — the largest budget leak. |
| G2 | per-connection stats | Reconnecting resets the ledger and grants a fresh free start. |
| G3 | fixed thresholds | 10 MB free start per peer is not scaled to torrent size and is unbounded in aggregate. |
| D1 | README | Says non-senders get 0.5 KB/s; code disables upload entirely at severity > 0.99. |

### Why the experimental "adjust on new" feature failed

1. `priorPeerList` is never updated, so every peer is "new" every tick.
2. Each new peer is added to `newPeerList` twice.
3. Even when fixed, a just-connected peer is below `start_threshold`, so the adjustment yields "unlimited" — the existing default. With no memory of the peer, there is nothing to apply immediately.

The feature is redesigned in Phase 4 on top of the peer ledger from Phase 3.

---

## 3. Phase 0 — Repository hygiene

**Goal:** anyone can clone, build and release the plugin reproducibly.

Tasks

- Remove committed runtime/build artefacts: `BiglyBT.exe`, `BiglyBT.exe.vmoptions`, `aereg64.dll`, `plugins/azupdater/`, `upnp_trace1.log`, `target/`, `release/*.jar`. (Redistributing BiglyBT binaries also raises licensing questions.)
- Fix `.gitignore` to cover `target/`, `build/`, `*.log`, IDE files.
- Add a Gradle build:
  - BiglyBT core as a `compileOnly` dependency (Maven Central `com.biglybt:biglybt-core` if available for the target version; otherwise a local `libs/` path configured via `gradle.properties`).
  - Task producing `Reciprocal_<version>.jar` containing `plugin.properties` and message bundles.
  - Single version source: `gradle.properties` → filtered into `plugin.properties`.
- Replace the machine-specific `.classpath` (Eclipse can import the Gradle project).
- GitHub Actions: build + test on push; on tag `v*`, attach the jar to a GitHub Release.
- Rename package `org.biglybt.plugin.throttle` → `io.github.alex870.reciprocal` (keep `plugin.id=reciprocal` so existing installs upgrade and settings survive).
- Add `CHANGELOG.md`; fix README typos and use KiB consistently.

Done when: `./gradlew build` produces an installable jar on a clean machine and CI is green.

## 4. Phase 1 — Correctness

**Goal:** the existing algorithm behaves as documented, with no accidental "unlimited" outcomes.

| Fix | Change |
|---|---|
| B1 | Divide by `1048576f` (or keep bytes as `long` throughout and convert only for display). |
| B2 | Apply `max(limit, minRate)` in every throttled branch; never emit 0 from a throttled branch. |
| B3 | Validate `min_ul_rate` as > 0 and finite; consider replacing with an `IntParameter` in B/s or KiB/s. |
| B4 | Compute peer ID only when logging; null-safe. Wrap each peer's processing in try/catch and log once per error type. |
| B5 | Only reset peers whose limit the plugin set (see Phase 2 ownership tracking). |
| B6 | On disable or unload, restore every limit the plugin set. Implement `UnloadablePlugin`. |
| B7 | "Action:" lines become debug-level; cap the log area (e.g. keep last N lines). |
| B8 | Hide the checkbox until Phase 4. |
| D1 | Decide: keep "disable at extreme deficit" (and document it) or always floor at `min_ul_rate`. Recommendation: make it a setting, default floor. |
| — | Merge `debug` + `debug_level` into one level setting (0 = off). |

Done when: unit tests (Phase 2) cover each fixed case; manual run shows no peer flipping to unlimited at balance 0.

## 5. Phase 2 — Restructure

**Goal:** separate decision logic from BiglyBT plumbing so it can be tested and evolved.

### Components

```
Reciprocal (Plugin, UnloadablePlugin)   lifecycle, config UI, wiring
├── Settings                            immutable snapshot of config per pass
├── ReciprocityPolicy                   pure: inputs -> Decision
├── LimitController                     applies Decisions, tracks ownership, restores on exit
├── PeerScanner                         iterates downloads/peers via public PIF API
└── PluginLog                           level-gated, bounded log
```

### Policy contract

```java
record PeerSnapshot(long bytesSent, long bytesReceived, long receiveRateBps,
                    boolean interesting, boolean lanLocal) {}

record TorrentSnapshot(long sizeBytes, int pieceSize, long freeUploadUsedBytes) {}

sealed interface Decision permits Unlimited, Limit, Disabled {}
record Limit(int bytesPerSecond) implements Decision {}   // always >= minRate > 0

Decision decide(PeerSnapshot peer, TorrentSnapshot torrent, Settings s);
```

The policy never returns a raw `0`/`-1`; translation to BiglyBT's sentinel values happens only in `LimitController`.

### Ownership tracking

`LimitController` keeps `Map<Peer, Integer /*limit before we touched it*/>`. It:
- records the original limit the first time it changes a peer,
- skips peers whose current limit differs from the last value it set (user/other plugin changed it),
- restores originals on completion, disable, or unload,
- drops entries when peers disconnect.

### API

Replace `PeerStatsImpl`/`PEPeerStats` (internal) with the public plugin interface: `Peer.getRateLimitBytes(boolean upload)` / `Peer.setRateLimitBytes(boolean upload, int bytes)` and `PeerStats` totals/rates. Verify each against the targeted BiglyBT version; keep any remaining internal access isolated in one adapter class.

### Tests

JUnit tests for `ReciprocityPolicy` covering: below start threshold, above margin, deficit gradient, extreme deficit, positive gradient, balance exactly 0 with zero rate (B2), non-interesting peer, LAN peer, free-upload cap exhausted.

Done when: policy has full branch coverage and the plugin runs with no `pifimpl` imports outside the adapter.

## 6. Phase 3 — Budget protection

**Goal:** close the remaining leaks that let the share quota be spent on non-reciprocating peers before completion.

### 3.1 Non-interesting peers (G1)

New setting `non_interesting_policy`:

| Value | Behaviour |
|---|---|
| `min_rate` *(default)* | Throttle to `min_ul_rate`. |
| `allowance` | Unlimited until `start_threshold` sent, then `min_rate`. |
| `unlimited` | Legacy 0.2.x behaviour. |

### 3.2 Peer ledger across reconnects (G2)

A per-torrent ledger that outlives connections.

```
LedgerKey   = (torrentHash, peerIp)            // optional: + client name
LedgerEntry = { bytesSent, bytesReceived, lastSeen, connections }
```

- On each pass, fold the connection's delta since the last pass into the entry.
- Policy uses ledger totals instead of connection totals.
- Free start (`start_threshold`) is granted once per ledger entry, not once per connection.
- Expire entries after a configurable idle time (default 24 h) and when the torrent completes or is removed.
- Optional persistence across client restarts (plugin config store or a small file in the plugin dir), keyed by torrent hash.
- Caveat: shared IPs (NAT, VPN exits, CGNAT) merge peers. Including the client name in the key reduces collisions; peer ID is not reliable because clients may randomise it.

### 3.3 Per-torrent free-upload cap (G3)

Bound the total unreciprocated upload per downloading torrent:

```
freeUploadUsed = Σ over ledger entries of max(0, bytesSent − bytesReceived)
cap            = free_upload_cap_pct × torrentSize        // default e.g. 20 %
```

When `freeUploadUsed ≥ cap`, new peers receive no free start (they begin in the throttled range immediately). This guarantees the free-start mechanism cannot by itself consume the 1.2× quota regardless of peer count.

### 3.4 Scaled thresholds

Allow `start_threshold` and `ignore_margin` to be expressed as either absolute MB or a percentage of torrent size, with absolute min/max clamps. Default: `start_threshold = min(10 MB, 1 % of size)`.

### 3.5 Exemptions

- LAN-local peers: never throttled (setting, default on).
- Peers the user has manually limited: untouched (via Phase 2 ownership).

### Preserved property

The positive-branch generosity (`rate × (1 + 0.5 × balance / margin)`) is unchanged so that two reciprocating clients continue to ratchet up to their highest mutual rate. Phase 1's B1 fix makes this work correctly near balance 0.

Done when: in a test with many short-lived leeching connections, upload sent during download stays within `cap + Σ reciprocated`.

## 7. Phase 4 — Event-driven peer handling

**Goal:** apply a known peer's standing the moment it connects; replaces the abandoned "adjust on new" feature.

- Register a `DownloadManagerListener` → per download a `DownloadPeerListener` → per peer manager a `PeerManagerListener2` (verify exact listener types against the target BiglyBT version).
- `peerAdded`: look up the ledger; if the peer has history (or the torrent's free-upload cap is exhausted), compute and apply a limit immediately instead of granting unlimited until the next pass.
- `peerRemoved`: fold final connection totals into the ledger; drop ownership entries.
- Download state change to complete: lift all plugin-owned limits immediately rather than waiting for the next pass.
- Keep the periodic timer only for rate-based re-evaluation; remove the 1 s polling tick and the peer-list diffing.
- Re-enable the "Adjust new peers immediately" setting (default on) once this works.

Done when: a peer that disconnects while in deficit and reconnects is throttled within one event, with no free start.

## 8. Phase 5 — Visibility

**Goal:** make the plugin's effect observable so settings can be tuned against the quota.

- **Peers table columns** (plugin `TableColumn` on the Peers view): ledger balance, plugin-set limit, state (`free start` / `deficit` / `surplus` / `unlimited` / `exempt`).
- **Per-torrent summary** in the plugin view: uploaded-while-downloading vs. share-ratio target (e.g. "0.64× of 1.2× budget used, 72 % downloaded"), free-upload cap used, peers throttled.
- **Session statistics:** bytes withheld from deficit peers, bytes given to surplus peers.
- **Dry-run mode:** compute and log decisions without applying limits.
- Bounded, level-filtered log with optional export.

Done when: a user can tell from the UI alone whether the quota is on track to last until completion.

---

## 9. Settings (target state)

| Key | Type | Default | Phase |
|---|---|---|---|
| `enable` | bool | on | — |
| `log_level` | int 0–5 | 0 | 1 |
| `check_secs` | int | 30 | — |
| `start_threshold` | MB or % | min(10 MB, 1 %) | 3 |
| `ignore_margin` | MB or % | 100 MB | 3 |
| `min_ul_rate` | KiB/s > 0 | 0.5 | 1 |
| `disable_at_extreme_deficit` | bool | off | 1 |
| `non_interesting_policy` | enum | `min_rate` | 3 |
| `free_upload_cap_pct` | int % | 20 | 3 |
| `ledger_ttl_hours` | int | 24 | 3 |
| `ledger_persist` | bool | off | 3 |
| `exempt_lan` | bool | on | 3 |
| `adjust_on_connect` | bool | on | 4 |
| `dry_run` | bool | off | 5 |

## 10. Risks & open questions

- **Public API coverage:** confirm the public plugin interface exposes per-peer upload limits and smoothed receive rate in the target BiglyBT version; otherwise keep a thin internal adapter.
- **Shared IP collisions** in the ledger (NAT/VPN). Mitigated by client-name keying and TTL; worth measuring.
- **Default for non-interesting peers:** `min_rate` changes behaviour for existing users; call it out in the changelog.
- **Minimum supported BiglyBT version** to declare in `plugin.properties`.
