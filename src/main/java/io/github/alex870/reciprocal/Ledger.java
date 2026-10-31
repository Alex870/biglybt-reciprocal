package io.github.alex870.reciprocal;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Per-torrent record of how much we have exchanged with each remote address, kept across
 * reconnects so a peer cannot reset its standing by disconnecting.
 * <p>
 * Keyed by (torrent hash, IP address). Not thread safe; the {@link Engine} serialises access.
 */
final class Ledger {

	static final class Entry {
		long sent;
		long received;
		long lastSeen;
		int connections;

		long balance() {
			return received - sent;
		}

		/** Upload we have given this address beyond what it gave back. */
		long unreciprocated() {
			return Math.max(0, sent - received);
		}
	}

	private static final String FORMAT = "R1";

	private final Map<String, Map<String, Entry>> torrents = new HashMap<String, Map<String, Entry>>();

	/** Returns the entry for an address, creating it if needed. */
	Entry get(String torrent, String ip, long now) {
		Map<String, Entry> peers = torrents.get(torrent);
		if (peers == null) torrents.put(torrent, peers = new HashMap<String, Entry>());
		Entry e = peers.get(ip);
		if (e == null) {
			peers.put(ip, e = new Entry());
			e.lastSeen = now;
		}
		return e;
	}

	/** Returns the entry for an address, or null if there is no history. */
	Entry find(String torrent, String ip) {
		Map<String, Entry> peers = torrents.get(torrent);
		return peers == null ? null : peers.get(ip);
	}

	/** Sum of unreciprocated upload across all addresses for a torrent. */
	long unreciprocated(String torrent) {
		Map<String, Entry> peers = torrents.get(torrent);
		if (peers == null) return 0;
		long total = 0;
		for (Entry e : peers.values()) total += e.unreciprocated();
		return total;
	}

	int size(String torrent) {
		Map<String, Entry> peers = torrents.get(torrent);
		return peers == null ? 0 : peers.size();
	}

	int size() {
		int n = 0;
		for (Map<String, Entry> peers : torrents.values()) n += peers.size();
		return n;
	}

	void forget(String torrent) {
		torrents.remove(torrent);
	}

	/** Drops entries not seen since {@code cutoff}. Returns the number removed. */
	int expire(long cutoff) {
		int removed = 0;
		for (Iterator<Map<String, Entry>> ti = torrents.values().iterator(); ti.hasNext();) {
			Map<String, Entry> peers = ti.next();
			for (Iterator<Entry> pi = peers.values().iterator(); pi.hasNext();) {
				if (pi.next().lastSeen < cutoff) {
					pi.remove();
					removed++;
				}
			}
			if (peers.isEmpty()) ti.remove();
		}
		return removed;
	}

	// ---------------------------------------------------------------- persistence

	void save(File file) throws IOException {
		File tmp = new File(file.getPath() + ".tmp");
		Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8));
		try {
			for (Map.Entry<String, Map<String, Entry>> t : torrents.entrySet()) {
				for (Map.Entry<String, Entry> p : t.getValue().entrySet()) {
					Entry e = p.getValue();
					w.write(FORMAT + "\t" + t.getKey() + "\t" + p.getKey() + "\t" + e.sent + "\t" + e.received
							+ "\t" + e.lastSeen + "\t" + e.connections + "\n");
				}
			}
		} finally {
			w.close();
		}
		if (file.exists() && !file.delete()) throw new IOException("Cannot replace " + file);
		if (!tmp.renameTo(file)) throw new IOException("Cannot rename " + tmp + " to " + file);
	}

	/** Loads entries seen at or after {@code cutoff}, merging into this ledger. Returns the number loaded. */
	int load(File file, long cutoff) throws IOException {
		if (!file.exists()) return 0;
		int loaded = 0;
		BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8));
		try {
			String line;
			while ((line = r.readLine()) != null) {
				String[] f = line.split("\t");
				if (f.length != 7 || !FORMAT.equals(f[0])) continue;
				try {
					long lastSeen = Long.parseLong(f[5]);
					if (lastSeen < cutoff) continue;
					Entry e = get(f[1], f[2], lastSeen);
					e.sent = Math.max(e.sent, Long.parseLong(f[3]));
					e.received = Math.max(e.received, Long.parseLong(f[4]));
					e.lastSeen = Math.max(e.lastSeen, lastSeen);
					e.connections = Math.max(e.connections, Integer.parseInt(f[6]));
					loaded++;
				} catch (NumberFormatException ignored) {
					// skip corrupt line
				}
			}
		} finally {
			r.close();
		}
		return loaded;
	}
}
