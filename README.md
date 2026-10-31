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

When the torrent is 100% complete, all limits the plugin set are removed. The expectation is that you already have a maximum share ratio set in BiglyBT, so the torrent has a natural end point, and you reach it as fast as possible once limits are lifted. Limits set by you or other plugins are never changed.

------------

### How to install
(as a user - no code compilation required)

1. Download the latest `Reciprocal_X.Y.Z.jar` from this repository's [Releases page](https://github.com/Alex870/biglybt-reciprocal/releases).
2. In BiglyBT select: **Tools -> Plugins -> Install from file**
3. Locate the downloaded .jar file and follow the wizard instructions.
4. Ensure the plugin is enabled: **Tools -> Options -> Plugins -> Reciprocal**
5. Make sure "Enable" is checked.
6. Adjust parameters as desired and press the `Save` button.

### Settings

| Setting | Default | Meaning |
|---|---|---|
| Enable | on | Turning it off lifts every limit the plugin set. |
| Logging level | 0 | 0 = off, 1 = limit changes, 2 = one line per peer, 3–5 = increasing detail. |
| Peer update interval | 30 s | How often limits are recalculated. |
| Allow unlimited upload rate when peer delta exceeds | 100 MB | Peers this far ahead are not limited. |
| Ignore peers when upload total below | 10 MB | Free start before limits apply. |
| Minimum upload rate for throttled peers | 0.5 KiB/s | Must be greater than 0. |
| Stop uploading entirely to peers in near-total deficit | off | If off, such peers get the minimum rate. |

------------

### Building

This is a Gradle project that compiles against BiglyBT's `BiglyBT.jar` (not redistributed here).

1. Provide the jar, either:
   - copy `BiglyBT.jar` from your BiglyBT install directory into `libs/`, or
   - build it from BiglyBT's source: `scripts/build-biglybt-jar.sh /path/to/BiglyBT-source`, or
   - point at it directly: `./gradlew build -PbiglybtJar=/path/to/BiglyBT.jar`
2. Run `./gradlew build`. The plugin jar is written to `build/libs/`.

The version number lives in `gradle.properties`. Pushing a tag such as `v0.3.0` makes CI build the jar and attach it to a GitHub Release.

See [docs/DESIGN.md](docs/DESIGN.md) for the design and roadmap.
