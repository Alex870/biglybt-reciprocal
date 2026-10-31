# biglybt-reciprocal
Reward the good, limit the leeches

### The problem being solved

For torrent peers on high-bandwidth connections, some greedy peers take large volumes of data without giving data back. The more bandwidth you have, the worse the problem gets. Some users run modified torrent clients that deliberately don't reciprocate.

If you set a share-ratio quota in BiglyBT (for example 1.2× the torrent size), that quota can be used up — often reaching 3× or more — before your download has even finished, mostly by peers who give little back.

### The solution

This plugin for BiglyBT (formerly Azureus, Vuze) does the following for each peer connection on a torrent that is still downloading:

- Allows unlimited upload speed until a threshold has been sent to the peer, 10 MB by default (kicks things off).
- After that, looks for peers where download minus upload is below 100 MB.
- For those peers, sets the upload speed to match the rate the peer is currently sending to you, with an offset depending on who is ahead:
  - **Peer has taken more than they gave:** the upload limit is kept below their rate to force a catch-up, until a 1:1 ratio is reached. The further behind they are, the more severe the limit.
  - **Peer has given more than they took:** the upload limit is set above their rate. The other side sees the higher rate and raises its own, so the two level out at the highest rate both can support.
- Peers that send nothing are throttled to a minimum rate (0.5 KiB/s by default). This leaves them the option to reciprocate later, but prevents significant leeching. Optionally, upload can be switched off entirely for peers in near-total deficit.

It also closes the common ways leechers slip through:

- **Peers that have nothing you want** (so can't give anything back) are limited too, instead of getting unlimited upload. Credit a peer has already earned is kept.
- **Reconnecting doesn't reset anything.** The plugin remembers what it has exchanged with each address on each torrent, so a leecher can't disconnect and come back for another free start. A returning peer is limited the moment it reconnects.
- **Free starts are capped per torrent.** Once unreciprocated upload on a torrent reaches 20% of its size, no more free starts are given on it.
- Peers on your local network are never limited.

When the torrent is 100% complete, all limits the plugin set are removed. The expectation is that you already have a maximum share ratio set in BiglyBT, so the torrent has a natural end point, and you reach it as fast as possible once limits are lifted. Limits set by you or other plugins are never changed.

------------

### How to install
(as a user - no code compilation required)

#### Step 1: Download the plugin file from Releases

The plugin is a single file named like `Reciprocal_0.4.0.jar`. You get it from this repository's **Releases** page — not from the green **Code** button, and not from the file list at the top of this page.

1. Open the latest release directly: **[github.com/Alex870/biglybt-reciprocal/releases/latest](https://github.com/Alex870/biglybt-reciprocal/releases/latest)**
   (Or, on the repository's main page, look in the right-hand column for **Releases** and click the version marked **Latest**.)
2. Scroll down to the **Assets** section at the bottom of the release. If it's collapsed, click **Assets** to expand it.
3. Click **`Reciprocal_X.Y.Z.jar`** to download it.

   Ignore the two files called **Source code (zip)** and **Source code (tar.gz)**. GitHub adds those to every release automatically; they contain the program's source code, not something BiglyBT can install.

Older versions are on the [full list of releases](https://github.com/Alex870/biglybt-reciprocal/releases), each with its own **Assets** section. Each release also lists what changed in that version (see also [CHANGELOG.md](CHANGELOG.md)).

#### Step 2: Install it in BiglyBT

1. In BiglyBT select: **Tools -> Plugins -> Install from file**
2. Locate the downloaded `.jar` file and follow the wizard instructions.
3. Ensure the plugin is enabled: **Tools -> Options -> Plugins -> Reciprocal**
4. Make sure "Enable" is checked.
5. Adjust parameters as desired and press the `Save` button.

#### Upgrading

Download the newer `.jar` from the latest release and install it the same way. BiglyBT replaces the old version and your settings are kept.

### Settings

| Setting | Default | Meaning |
|---|---|---|
| **General** | | |
| Enable | on | Turning it off lifts every limit the plugin set. |
| Logging level | 0 | 0 = off, 1 = limit changes, 2 = one line per peer, 3–5 = increasing detail. |
| Peer update interval | 30 s | How often limits are recalculated. |
| Dry run | off | Work out and log limits without applying them. |
| **Per-peer limits** | | |
| Free start | 10 MB | Upload unlimited until this much has been sent to a peer (0 = no free start). |
| ... or % of torrent size | 1% | If smaller, used instead (0 = off). |
| Unlimited when peer delta exceeds | 100 MB | Peers this far ahead are not limited. |
| ... or % of torrent size | 0% | If smaller, used instead (0 = off). |
| Minimum upload rate | 0.5 KiB/s | Must be greater than 0. |
| Stop uploading entirely in near-total deficit | off | If off, such peers get the minimum rate. |
| **Upload budget** | | |
| Peers that have nothing we want | throttle, no free start | Or: free start then throttle; or never limit (0.2.x behaviour). |
| Free-upload cap | 20% | Stop free starts once unreciprocated upload on a torrent reaches this % of its size (0 = no cap). |
| Never limit LAN peers | on | |
| Share-ratio budget | 120% | Only used by the budget column. |
| **Peer memory** | | |
| Apply standing on connect | on | Limit a returning peer as soon as it connects. |
| Forget peers after | 24 h | Unseen addresses are forgotten after this long. |
| Remember across restarts | off | Saves the peer memory to the plugin's folder. |

### Seeing what it does

- **Peers tab:** add the **Reciprocal** column (the plugin's decision and limit for each peer) and **Reciprocal Delta** (received minus sent, across reconnects).
- **Torrents list:** the **Reciprocal Budget** column shows upload so far vs your budget, e.g. `0.64× of 1.20× at 72%`, and turns red when you're on track to exceed it.
- **Tools -> Plugins -> Reciprocal** view: the log, with session totals in the status line.

Columns can be added from the table's column setup if they don't appear automatically.

------------

### Building

This is a Gradle project that compiles against BiglyBT's `BiglyBT.jar` (not redistributed here).

1. Provide the jar, either:
   - copy `BiglyBT.jar` from your BiglyBT install directory into `libs/`, or
   - build it from BiglyBT's source: `scripts/build-biglybt-jar.sh /path/to/BiglyBT-source`, or
   - point at it directly: `./gradlew build -PbiglybtJar=/path/to/BiglyBT.jar`
2. Run `./gradlew build`. The plugin jar is written to `build/libs/`.

### Publishing a release (maintainer)

Releases are built by GitHub Actions; there is no need to upload jars by hand or commit them to the repository.

1. Set the new version in `gradle.properties` (`pluginVersion=...`) and add a section for it to `CHANGELOG.md`. Commit and push to `main`.
2. Create and push a tag named after the version, starting with `v`:
   ```bash
   git tag v0.4.0
   git push origin v0.4.0
   ```
3. The **Build** workflow runs on the tag, builds `Reciprocal_0.4.0.jar` and creates a GitHub Release for the tag with the jar attached. Check progress in the repository's **Actions** tab; the release appears on the Releases page when it finishes (about a minute).
4. Optionally edit the release on GitHub to paste the changelog section into its description (GitHub pre-fills it with a list of commits).

To publish a test build, use a tag with a suffix such as `v0.4.0-beta1`. It is published as a **pre-release**: downloadable from the full releases list, but not shown as **Latest**, so ordinary users aren't sent to it.

See [docs/DESIGN.md](docs/DESIGN.md) for the design and roadmap.
