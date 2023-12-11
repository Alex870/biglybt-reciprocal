# biglybt-reciprocal
Reward the good, limit the leeches

### The problem being solved

For Torrent peers on high bandwidth connections, some greedy peers take large volumes of data without providing data back.  The higher bandwidth connection you have, the more egregious the problem.  Some users run modified torrent clients that deliberately don`t reciprocate in sending data.

### The solution

This plugin for BiglyBT (formerly Azureus, Vuze) does the following for each torrent peer connection:

- Allows unlimited upload speed up to a threshold, typically 10MB (kicks things off).
- After 10MB, looks for connections where the download minus the upload is < 100MB.
- For these < 100MB connections, adjusts upload speed to match the current downstream speed from the other peer.  Also, a rate offset from this value is applied depending on whether the sharing ratio is >1 (they've given more) or <1 (you`ve give more).  

#### The Results
- In the case the peer has taken more data than they gave, the average upload speed to them will be kept at a rate lower than they are sending to force a catch up scenario to occur until a 1:1 up/down ratio is reached.  The further from a 1:1 ratio, the more severe the upload limiting will be until the metrics  are brought into compliance.
- If the peer has sent you more data than you`ve sent to them, the upstream limit to them will be set higher than the speed sending you data.  This may nudge the ratio closer to 1:1.
- For peers that do not send data, they are throttled down to a minimal stream of 0.5KB per sec.  This leaves the option open for them to reciprocate later.  But prevents and punishes significant leeching.

When the torrent is 100% complete, all rate limits are removed.  The expectation here is you already have a max share ratio set in BiglyBT so the torrent has a natural termination point, and you can reach that goal ASAP by lifting upload speed limits.

------------

### How to install 
(as a user - no code compilation required)

1. Download the latest `reciprocal_XXXXX.jar` release file from this github repositiory from the `release` folder.
2. Select: **Tools -> Plugins -> Install from file**
3. Locate the .jar release file downloaded from this github repositiory and follow the wizard instructions.
4. Ensure plugin is enabled: **Tools -> Options -> Plugins -> Reciprocal**
5. Make sure "Enable" is checked.
6. Adjust parameters as desired and press `Save` button.

------------

Developer notes:
This is an Eclipse Java project
