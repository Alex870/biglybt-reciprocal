package io.github.alex870.reciprocal;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.alex870.reciprocal.ReciprocityPolicy.Decision;
import io.github.alex870.reciprocal.ReciprocityPolicy.NonInteresting;
import io.github.alex870.reciprocal.ReciprocityPolicy.Reason;

/**
 * Core of the plugin: keeps the peer ledger, decides and applies limits, and serves status
 * for the UI. Independent of BiglyBT (the peer type and its accessors are supplied), so it
 * can be tested end to end. All public methods are synchronized; BiglyBT calls in from the
 * timer thread, peer events and the UI.
 */
final class Engine<P> {

	// Log levels. Errors and enable/disable are always logged by the plugin.
	static final int LOG_ACTIONS = 1;  // every limit change
	static final int LOG_PEERS = 2;    // one line per peer per pass
	static final int LOG_DEFICIT = 3;  // detail for peers in deficit
	static final int LOG_SURPLUS = 4;  // detail for peers in surplus / unlimited
	static final int LOG_TRACE = 5;    // everything

	/** Reads peer state and reads/writes its upload limit. */
	interface PeerAccess<P> extends LimitController.Access<P> {
		String ip(P peer);
		long sent(P peer);
		long received(P peer);
		/** The peer's current send rate to us, used before we have our own measurement. */
		long currentReceiveRate(P peer);
		boolean interesting(P peer);
		boolean lanLocal(P peer);
	}

	interface Log {
		boolean on(int level);
		void log(String message);
	}

	/** The torrent a peer belongs to. */
	static final class Torrent {
		final String hash;
		final String name;
		final long size;
		final boolean downloading;
		final boolean complete;

		Torrent(String hash, String name, long size, boolean downloading, boolean complete) {
			this.hash = hash;
			this.name = name;
			this.size = size;
			this.downloading = downloading;
			this.complete = complete;
		}
	}

	/** Snapshot of user settings. */
	static final class Config {
		int startThresholdMb = 10;
		int startThresholdPct = 1;
		int ignoreMarginMb = 100;
		int ignoreMarginPct = 0;
		int minRateBps = 512;
		boolean disableAtExtremeDeficit = false;
		NonInteresting nonInteresting = NonInteresting.MIN_RATE;
		boolean exemptLan = true;
		int freeUploadCapPct = 20;
		long ledgerTtlMs = 24L * 3600 * 1000;
		boolean adjustOnConnect = true;
		boolean dryRun = false;

		ReciprocityPolicy.Settings forTorrent(long size, boolean capExhausted) {
			double start = ReciprocityPolicy.scaledThreshold(startThresholdMb, startThresholdPct, size, 0);
			double margin = ReciprocityPolicy.scaledThreshold(ignoreMarginMb, ignoreMarginPct, size, ReciprocityPolicy.BYTES_PER_MB);
			ReciprocityPolicy.Settings s = new ReciprocityPolicy.Settings(start, margin, minRateBps,
					disableAtExtremeDeficit, nonInteresting, exemptLan);
			return capExhausted ? s.withoutFreeStart() : s;
		}

		long freeUploadCap(long size) {
			if (freeUploadCapPct <= 0 || size <= 0) return Long.MAX_VALUE;
			return (long) (size * (freeUploadCapPct / 100d));
		}
	}

	/** Per-torrent figures for the UI. */
	static final class TorrentSummary {
		long unreciprocated;
		long freeUploadCap;
		int peers;
		int throttled;
		int deficit;
		int surplus;

		boolean capExhausted() {
			return unreciprocated >= freeUploadCap;
		}
	}

	/** Per-peer figures for the UI. */
	static final class PeerStatus {
		final long balance;
		final int limit;
		final Reason reason;
		final String label;
		final boolean applied;

		PeerStatus(long balance, int limit, Reason reason, String label, boolean applied) {
			this.balance = balance;
			this.limit = limit;
			this.reason = reason;
			this.label = label;
			this.applied = applied;
		}
	}

	/** Session totals: upload sent to peers by the state they were in at the time. */
	static final class SessionStats {
		long sentToDeficit;
		long sentToSurplus;
		long sentUnlimited;
		long reconnectsRecognised;
	}

	/** One live connection. */
	private static final class Conn {
		final String torrent;
		final String ip;
		long lastSent;
		long lastReceived;
		long lastTime;
		long rate = -1; // measured receive rate, -1 = not yet measured
		Decision lastDecision;
		boolean applied;

		Conn(String torrent, String ip) {
			this.torrent = torrent;
			this.ip = ip;
		}
	}

	private final PeerAccess<P> access;
	private final Log log;
	private final LimitController<P> limits;
	private final Ledger ledger = new Ledger();
	private final Map<P, Conn> conns = new HashMap<P, Conn>();
	private final Map<String, TorrentSummary> summaries = new HashMap<String, TorrentSummary>();
	private final SessionStats stats = new SessionStats();
	private Config config = new Config();

	Engine(PeerAccess<P> access, Log log) {
		this.access = access;
		this.log = log;
		this.limits = new LimitController<P>(access);
	}

	synchronized void setConfig(Config config) {
		boolean was_dry = this.config.dryRun;
		this.config = config;
		if (config.dryRun && !was_dry) {
			int lifted = limits.releaseAll();
			if (lifted > 0) log.log("Dry run enabled: lifted " + lifted + " upload limit(s).");
		}
	}

	synchronized Config getConfig() {
		return config;
	}

	// ---------------------------------------------------------------- events

	/** A peer connected. Applies its known standing immediately if adjust-on-connect is on. */
	synchronized void onPeerAdded(Torrent t, P peer, long now) {
		if (t.complete || conns.containsKey(peer)) return;
		Conn c = track(t, peer, now);
		if (!t.downloading || !config.adjustOnConnect) return;
		Ledger.Entry e = ledger.find(t.hash, c.ip);
		// Only act early for a returning address that owes us, or once free starts have run out.
		// Anyone else is handled at the next pass, when BiglyBT knows which pieces it has.
		boolean returningInDeficit = e != null && e.connections > 1 && e.balance() < 0;
		boolean capExhausted = ledger.unreciprocated(t.hash) >= config.freeUploadCap(t.size);
		if (returningInDeficit || capExhausted) {
			decideAndApply(t, peer, c, config.forTorrent(t.size, capExhausted), "on connect");
		}
	}

	/** A peer disconnected: fold its final totals into the ledger and forget it. */
	synchronized void onPeerRemoved(Torrent t, P peer, long now) {
		Conn c = conns.remove(peer);
		if (c != null && !t.complete) fold(c, peer, now);
		limits.forget(peer);
	}

	/** A torrent stopped downloading (completed, stopped, ...): lift our limits on its peers now. */
	synchronized void onTorrentNotDownloading(Torrent t, Iterable<P> peers) {
		int lifted = 0;
		for (P peer : peers) {
			if (limits.release(peer)) lifted++;
			Conn c = conns.get(peer);
			if (c != null) c.lastDecision = null;
		}
		if (t.complete) ledger.forget(t.hash);
		if (lifted > 0 && log.on(LOG_ACTIONS)) log.log(t.name + ": lifted " + lifted + " upload limit(s) (not downloading).");
	}

	// ---------------------------------------------------------------- periodic pass

	/** Full pass over one torrent's peers. Call {@link #endPass} after all torrents. */
	synchronized void pass(Torrent t, List<P> peers, long now) {
		if (t.complete) ledger.forget(t.hash);
		if (!t.downloading) {
			summaries.remove(t.hash);
			for (P peer : peers) {
				if (!t.complete) {
					// Keep the ledger current (e.g. a stopped-but-connected torrent).
					Conn c = conns.get(peer);
					if (c == null) track(t, peer, now);
					else fold(c, peer, now);
				}
				if (limits.release(peer) && log.on(LOG_ACTIONS)) {
					log.log(access.ip(peer) + "\tAction: lifted upload limit (torrent not downloading).");
				}
			}
			return;
		}

		// Fold everyone first so the free-upload cap reflects this pass.
		for (P peer : peers) {
			Conn c = conns.get(peer);
			if (c == null) track(t, peer, now);
			else fold(c, peer, now);
		}

		long unreciprocated = ledger.unreciprocated(t.hash);
		long cap = config.freeUploadCap(t.size);
		boolean capExhausted = unreciprocated >= cap;
		ReciprocityPolicy.Settings s = config.forTorrent(t.size, capExhausted);

		TorrentSummary sum = new TorrentSummary();
		sum.unreciprocated = unreciprocated;
		sum.freeUploadCap = cap;
		for (P peer : peers) {
			Decision d = decideAndApply(t, peer, conns.get(peer), s, null);
			sum.peers++;
			if (d.reason.isThrottled()) sum.throttled++;
			if (d.reason == Reason.DEFICIT || d.reason == Reason.EXTREME_DEFICIT) sum.deficit++;
			if (d.reason == Reason.SURPLUS) sum.surplus++;
		}
		TorrentSummary prev = summaries.put(t.hash, sum);
		if (log.on(LOG_ACTIONS) && capExhausted && (prev == null || !prev.capExhausted()) && cap != Long.MAX_VALUE) {
			log.log(t.name + ": free-upload cap reached (" + mb(unreciprocated) + " of " + mb(cap) + "); no more free starts.");
		}
	}

	/** Ends a full pass: forgets disconnected peers and expires old ledger entries. */
	synchronized void endPass(Set<P> connected, Set<String> activeTorrents, long now) {
		for (Iterator<P> it = conns.keySet().iterator(); it.hasNext();) {
			if (!connected.contains(it.next())) it.remove();
		}
		limits.retainOnly(connected);
		summaries.keySet().retainAll(activeTorrents);
		int expired = ledger.expire(now - config.ledgerTtlMs);
		if (expired > 0 && log.on(LOG_TRACE)) log.log("Ledger: expired " + expired + " entr" + (expired == 1 ? "y" : "ies") + ".");
	}

	private Conn track(Torrent t, P peer, long now) {
		Conn c = new Conn(t.hash, access.ip(peer));
		conns.put(peer, c);
		Ledger.Entry e = ledger.get(t.hash, c.ip, now);
		e.connections++;
		if (e.connections > 1 && (e.sent > 0 || e.received > 0)) { // only reconnects with history matter
			stats.reconnectsRecognised++;
			if (log.on(LOG_PEERS)) {
				log.log(c.ip + "\tReconnected (connection " + e.connections + "), ledger delta = " + mb(e.balance()) + ".");
			}
		}
		fold(c, peer, now);
		return c;
	}

	/** Adds a connection's transfer since the last fold to the ledger and measures its rate. */
	private void fold(Conn c, P peer, long now) {
		long sent = access.sent(peer);
		long received = access.received(peer);
		long dSent = Math.max(0, sent - c.lastSent);
		long dReceived = Math.max(0, received - c.lastReceived);

		Ledger.Entry e = ledger.get(c.torrent, c.ip, now);
		e.sent += dSent;
		e.received += dReceived;
		e.lastSeen = now;

		if (c.lastTime > 0 && now - c.lastTime >= 1000) {
			c.rate = dReceived * 1000 / (now - c.lastTime);
		}
		c.lastSent = sent;
		c.lastReceived = received;
		c.lastTime = now;

		if (dSent > 0 && c.lastDecision != null) {
			Reason r = c.lastDecision.reason;
			if (r == Reason.DEFICIT || r == Reason.EXTREME_DEFICIT) stats.sentToDeficit += dSent;
			else if (r == Reason.SURPLUS) stats.sentToSurplus += dSent;
			else stats.sentUnlimited += dSent;
		} else if (dSent > 0) {
			stats.sentUnlimited += dSent;
		}
	}

	private Decision decideAndApply(Torrent t, P peer, Conn c, ReciprocityPolicy.Settings s, String when) {
		Ledger.Entry e = ledger.get(t.hash, c.ip, c.lastTime);
		long rate = c.rate >= 0 ? c.rate : Math.max(0, access.currentReceiveRate(peer));
		// At connect time BiglyBT does not yet know which pieces the peer has, so "interesting" is
		// meaningless; judge the peer on its ledger record alone.
		boolean interesting = when != null || access.interesting(peer);
		Decision d = ReciprocityPolicy.decide(e.sent, e.received, rate, interesting, access.lanLocal(peer), s);
		c.lastDecision = d;
		logDecision(c, e, rate, d);

		int before = access.getUploadLimit(peer);
		String suffix = when == null ? "" : " (" + when + ")";
		if (config.dryRun) {
			c.applied = false;
			if (before != d.limit && log.on(LOG_ACTIONS)) {
				log.log(c.ip + "\tDry run: would set upload limit to " + rate(d.limit) + suffix + ".  Current limit is " + rate(before) + ".");
			}
			return d;
		}
		LimitController.Result result = limits.apply(peer, d.limit);
		c.applied = result != LimitController.Result.SKIPPED_FOREIGN;
		if (log.on(LOG_ACTIONS) && (result == LimitController.Result.SET || result == LimitController.Result.RESTORED)) {
			log.log(c.ip + "\tAction: Setting upload limit to " + rate(d.limit) + suffix + ".  Prior limit was " + rate(before) + ".");
		} else if (log.on(LOG_TRACE) && result == LimitController.Result.SKIPPED_FOREIGN) {
			log.log(c.ip + "\tSkipped: upload limit " + rate(before) + " was set outside Reciprocal.");
		}
		return d;
	}

	private void logDecision(Conn c, Ledger.Entry e, long rate, Decision d) {
		if (log.on(LOG_PEERS) && d.reason != Reason.NOT_INTERESTING && d.reason != Reason.LAN) {
			log.log(c.ip + "\tdown-up delta = " + mb(e.balance()) + ", receive rate = " + bps(rate) + ", " + d.label());
		}
		if (log.on(LOG_DEFICIT) && (d.reason == Reason.DEFICIT || d.reason == Reason.EXTREME_DEFICIT)) {
			log.log(c.ip + "\tDeficit - severity = " + f2(d.factor) + ", upload limit = " + rate(d.limit));
		}
		if (log.on(LOG_SURPLUS)) {
			if (d.reason == Reason.SURPLUS) {
				log.log(c.ip + "\tSurplus - margin factor = " + f2(d.factor) + ", upload limit = " + rate(d.limit));
			}
		}
	}

	// ---------------------------------------------------------------- lifecycle

	/** Lifts every limit we set (disable / unload). */
	synchronized int releaseAll() {
		for (Conn c : conns.values()) c.lastDecision = null;
		return limits.releaseAll();
	}

	synchronized int saveLedger(File file) throws IOException {
		ledger.save(file);
		return ledger.size();
	}

	synchronized int loadLedger(File file, long now) throws IOException {
		return ledger.load(file, now - config.ledgerTtlMs);
	}

	// ---------------------------------------------------------------- status for the UI

	synchronized PeerStatus status(P peer) {
		Conn c = conns.get(peer);
		if (c == null) return null;
		Ledger.Entry e = ledger.find(c.torrent, c.ip);
		long balance = e == null ? 0 : e.balance();
		if (c.lastDecision == null) return new PeerStatus(balance, ReciprocityPolicy.UNLIMITED, null, null, false);
		return new PeerStatus(balance, c.lastDecision.limit, c.lastDecision.reason, c.lastDecision.label(), c.applied);
	}

	synchronized TorrentSummary summary(String torrentHash) {
		return summaries.get(torrentHash);
	}

	synchronized SessionStats sessionStats() {
		SessionStats s = new SessionStats();
		s.sentToDeficit = stats.sentToDeficit;
		s.sentToSurplus = stats.sentToSurplus;
		s.sentUnlimited = stats.sentUnlimited;
		s.reconnectsRecognised = stats.reconnectsRecognised;
		return s;
	}

	synchronized int ledgerSize() {
		return ledger.size();
	}

	/** Visible for tests. */
	synchronized Ledger.Entry ledgerEntry(String torrent, String ip) {
		return ledger.find(torrent, ip);
	}

	// ---------------------------------------------------------------- formatting

	static String mb(long bytes) {
		return f2(bytes / ReciprocityPolicy.BYTES_PER_MB) + " MB";
	}

	static String f2(double v) {
		return String.format("%.2f", v);
	}

	/** Formats a limit in BiglyBT's convention (0 = unlimited, -1 = disabled). */
	static String rate(long limit) {
		if (limit == ReciprocityPolicy.DISABLED) return "disabled";
		if (limit == ReciprocityPolicy.UNLIMITED) return "unlimited";
		return bps(limit);
	}

	/** Formats a plain transfer rate. */
	static String bps(long bps) {
		if (bps < 1024) return bps + " B/s";
		return f2(bps / 1024d) + " KiB/s";
	}
}
