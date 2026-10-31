package io.github.alex870.reciprocal;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineTest {

	static final long MB = 1024L * 1024L;
	static final int MIN = 512;

	static final class FakePeer {
		final String ip;
		long sent, received, rate;
		boolean interesting = true, lan;
		int limit;

		FakePeer(String ip) {
			this.ip = ip;
		}
	}

	final List<String> logLines = new ArrayList<String>();
	Engine<FakePeer> engine;
	Engine.Config config;
	long now = 1_000_000;

	final Engine.Torrent downloading = new Engine.Torrent("T1", "test", 1000 * MB, true, false);
	final Engine.Torrent seeding = new Engine.Torrent("T1", "test", 1000 * MB, false, true);

	@BeforeEach
	void setUp() {
		engine = new Engine<FakePeer>(new Engine.PeerAccess<FakePeer>() {
			public String ip(FakePeer p) { return p.ip; }
			public long sent(FakePeer p) { return p.sent; }
			public long received(FakePeer p) { return p.received; }
			public long currentReceiveRate(FakePeer p) { return p.rate; }
			public boolean interesting(FakePeer p) { return p.interesting; }
			public boolean lanLocal(FakePeer p) { return p.lan; }
			public int getUploadLimit(FakePeer p) { return p.limit; }
			public void setUploadLimit(FakePeer p, int limit) { p.limit = limit; }
		}, new Engine.Log() {
			public boolean on(int level) { return true; }
			public void log(String message) { logLines.add(message); }
		});
		config = new Engine.Config();
		config.startThresholdPct = 0; // keep the 10 MB free start for simple numbers
		config.freeUploadCapPct = 0;  // no cap unless a test enables it
		config.minRateBps = MIN;
		engine.setConfig(config);
	}

	void pass(Engine.Torrent t, FakePeer... peers) {
		engine.pass(t, Arrays.asList(peers), now);
		engine.endPass(new HashSet<FakePeer>(Arrays.asList(peers)), new HashSet<String>(Arrays.asList(t.hash)), now);
		now += 30_000;
	}

	@Test
	void reconnectingLeecherKeepsDeficit() {
		FakePeer a = new FakePeer("10.0.0.1");
		engine.onPeerAdded(downloading, a, now);
		a.sent = 60 * MB;
		a.received = 10 * MB;
		pass(downloading, a);
		assertTrue(a.limit > 0, "throttled after leeching");
		engine.onPeerRemoved(downloading, a, now);

		FakePeer again = new FakePeer("10.0.0.1"); // new connection, fresh per-connection stats
		engine.onPeerAdded(downloading, again, now);
		assertEquals(MIN, again.limit, "throttled immediately on reconnect, no new free start");
		Ledger.Entry e = engine.ledgerEntry("T1", "10.0.0.1");
		assertEquals(60 * MB, e.sent);
		assertEquals(2, e.connections);
	}

	/** From the trial log: a returning address with nothing exchanged must not be throttled on connect. */
	@Test
	void returningPeerWithNoHistoryIsNotTouched() {
		FakePeer a = new FakePeer("10.0.0.1");
		engine.onPeerAdded(downloading, a, now);
		pass(downloading, a);
		engine.onPeerRemoved(downloading, a, now);

		FakePeer again = new FakePeer("10.0.0.1");
		again.interesting = false; // BiglyBT doesn't know its pieces yet at connect time
		engine.onPeerAdded(downloading, again, now);
		assertEquals(0, again.limit);
		assertEquals(0, engine.sessionStats().reconnectsRecognised);
		assertTrue(logLines.stream().noneMatch(l -> l.contains("Reconnected")));
	}

	@Test
	void returningPeerInSurplusIsNotTouched() {
		FakePeer a = new FakePeer("10.0.0.1");
		engine.onPeerAdded(downloading, a, now);
		a.sent = 20 * MB;
		a.received = 50 * MB;
		pass(downloading, a);
		engine.onPeerRemoved(downloading, a, now);
		FakePeer again = new FakePeer("10.0.0.1");
		engine.onPeerAdded(downloading, again, now);
		assertEquals(0, again.limit, "left alone until the next pass measures its rate");
		assertEquals(1, engine.sessionStats().reconnectsRecognised);
	}

	/** Not knowing a peer's pieces at connect time must not stop a returning leecher being caught. */
	@Test
	void returningLeecherCaughtEvenBeforeInterestKnown() {
		FakePeer a = new FakePeer("10.0.0.1");
		engine.onPeerAdded(downloading, a, now);
		a.sent = 60 * MB;
		pass(downloading, a);
		engine.onPeerRemoved(downloading, a, now);
		FakePeer again = new FakePeer("10.0.0.1");
		again.interesting = false;
		engine.onPeerAdded(downloading, again, now);
		assertEquals(MIN, again.limit);
		assertEquals("deficit", engine.status(again).label);
	}

	@Test
	void finalTotalsFoldedOnDisconnect() {
		FakePeer a = new FakePeer("10.0.0.1");
		engine.onPeerAdded(downloading, a, now);
		pass(downloading, a);
		a.sent = 30 * MB; // transferred after the last pass
		engine.onPeerRemoved(downloading, a, now);
		assertEquals(30 * MB, engine.ledgerEntry("T1", "10.0.0.1").sent);
	}

	@Test
	void newPeerWithNoHistoryIsNotTouchedOnConnect() {
		FakePeer a = new FakePeer("10.0.0.2");
		engine.onPeerAdded(downloading, a, now);
		assertEquals(0, a.limit);
	}

	@Test
	void adjustOnConnectCanBeTurnedOff() {
		FakePeer a = new FakePeer("10.0.0.1");
		engine.onPeerAdded(downloading, a, now);
		a.sent = 60 * MB;
		pass(downloading, a);
		engine.onPeerRemoved(downloading, a, now);
		config.adjustOnConnect = false;
		FakePeer again = new FakePeer("10.0.0.1");
		engine.onPeerAdded(downloading, again, now);
		assertEquals(0, again.limit);
		pass(downloading, again);
		assertEquals(MIN, again.limit, "caught on the next pass instead");
	}

	@Test
	void freeUploadCapEndsFreeStarts() {
		config.freeUploadCapPct = 2; // 2% of 1000 MB = 20 MB
		FakePeer a = new FakePeer("10.0.0.1"), b = new FakePeer("10.0.0.2"), c = new FakePeer("10.0.0.3");
		a.sent = 9 * MB;
		b.sent = 9 * MB;
		c.sent = 1 * MB;
		pass(downloading, a, b, c);
		assertEquals(0, c.limit, "cap not reached yet: free start");

		a.sent = 10 * MB;
		b.sent = 10 * MB;
		pass(downloading, a, b, c);
		assertEquals(MIN, c.limit, "cap reached: free start withdrawn");
		assertTrue(engine.summary("T1").capExhausted());

		FakePeer d = new FakePeer("10.0.0.4");
		engine.onPeerAdded(downloading, d, now);
		assertEquals(MIN, d.limit, "new peer throttled on connect once cap is used up");
	}

	@Test
	void reciprocatingPeerIsMatched() {
		FakePeer a = new FakePeer("10.0.0.1");
		a.sent = 20 * MB;
		a.received = 20 * MB;
		pass(downloading, a);
		// 3 MB received over the next 30 s = ~104857 B/s measured rate
		a.received += 3 * MB;
		a.sent += 3 * MB;
		pass(downloading, a);
		assertEquals(3 * MB / 30, a.limit, 2);
	}

	@Test
	void limitsLiftedWhenTorrentCompletes() {
		FakePeer a = new FakePeer("10.0.0.1");
		a.sent = 60 * MB;
		pass(downloading, a);
		assertEquals(MIN, a.limit);
		engine.onTorrentNotDownloading(seeding, Arrays.asList(a));
		assertEquals(0, a.limit);
		assertNull(engine.ledgerEntry("T1", "10.0.0.1"), "ledger forgotten on completion");
	}

	@Test
	void seedingPassLiftsLimits() {
		FakePeer a = new FakePeer("10.0.0.1");
		a.sent = 60 * MB;
		pass(downloading, a);
		pass(seeding, a);
		assertEquals(0, a.limit);
	}

	@Test
	void userLimitsAreRespected() {
		FakePeer a = new FakePeer("10.0.0.1");
		a.sent = 60 * MB;
		a.limit = 7000; // set by the user
		pass(downloading, a);
		assertEquals(7000, a.limit);
		pass(seeding, a);
		assertEquals(7000, a.limit);
	}

	/** After completion the user can set per-peer upload limits and the plugin leaves them alone. */
	@Test
	void userCanLimitPeersAfterCompletion() {
		FakePeer a = new FakePeer("10.0.0.1"), b = new FakePeer("10.0.0.2");
		a.sent = 60 * MB;
		b.sent = 60 * MB;
		pass(downloading, a, b);
		assertEquals(MIN, a.limit, "limited while downloading");

		engine.onTorrentNotDownloading(seeding, Arrays.asList(a, b));
		assertEquals(0, a.limit, "our limits lifted at completion");
		assertEquals(0, b.limit);

		a.limit = 20_000; // user sets a cap on one peer in the Peers tab
		for (int i = 0; i < 5; i++) pass(seeding, a, b);
		assertEquals(20_000, a.limit, "user's cap is never overridden while seeding");
		assertEquals(0, b.limit);

		FakePeer c = new FakePeer("10.0.0.3"); // peer connecting after completion
		engine.onPeerAdded(seeding, c, now);
		c.limit = 5_000;
		pass(seeding, a, b, c);
		engine.onPeerRemoved(seeding, c, now);
		assertEquals(5_000, c.limit);
		assertEquals(0, engine.releaseAll(), "nothing on a completed torrent is ours");
	}

	@Test
	void dryRunNeverApplies() {
		config.dryRun = true;
		engine.setConfig(config);
		FakePeer a = new FakePeer("10.0.0.1");
		a.sent = 60 * MB;
		pass(downloading, a);
		assertEquals(0, a.limit);
		assertFalse(engine.status(a).applied);
		assertTrue(logLines.stream().anyMatch(l -> l.contains("Dry run: would set")));
	}

	@Test
	void turningOnDryRunLiftsLimits() {
		FakePeer a = new FakePeer("10.0.0.1");
		a.sent = 60 * MB;
		pass(downloading, a);
		assertEquals(MIN, a.limit);
		Engine.Config dry = new Engine.Config();
		dry.dryRun = true;
		engine.setConfig(dry);
		assertEquals(0, a.limit);
	}

	@Test
	void releaseAllLiftsEverything() {
		FakePeer a = new FakePeer("10.0.0.1"), b = new FakePeer("10.0.0.2");
		a.sent = 60 * MB;
		b.sent = 60 * MB;
		pass(downloading, a, b);
		assertEquals(2, engine.releaseAll());
		assertEquals(0, a.limit);
		assertEquals(0, b.limit);
	}

	@Test
	void ledgerExpires() {
		config.ledgerTtlMs = 60_000;
		FakePeer a = new FakePeer("10.0.0.1");
		a.sent = 60 * MB;
		pass(downloading, a);
		engine.onPeerRemoved(downloading, a, now);
		now += 120_000;
		pass(downloading);
		assertNull(engine.ledgerEntry("T1", "10.0.0.1"));
	}

	@Test
	void sessionStatsByState() {
		FakePeer a = new FakePeer("10.0.0.1");
		a.sent = 60 * MB;
		pass(downloading, a);  // first fold: no decision yet -> unlimited bucket
		a.sent += 1 * MB;
		pass(downloading, a);  // sent while in deficit
		Engine.SessionStats s = engine.sessionStats();
		assertEquals(60 * MB, s.sentUnlimited);
		assertEquals(1 * MB, s.sentToDeficit);
	}

	@Test
	void ledgerPersistsAcrossRestarts(@TempDir Path dir) throws Exception {
		FakePeer a = new FakePeer("10.0.0.1");
		a.sent = 60 * MB;
		a.received = 5 * MB;
		pass(downloading, a);
		File f = dir.resolve("ledger.txt").toFile();
		engine.saveLedger(f);

		setUp(); // a fresh engine, as after a restart
		assertEquals(1, engine.loadLedger(f, now));
		FakePeer again = new FakePeer("10.0.0.1");
		engine.onPeerAdded(downloading, again, now);
		assertEquals(MIN, again.limit, "known from the saved ledger");
	}

	@Test
	void persistedLedgerSkipsExpired(@TempDir Path dir) throws Exception {
		FakePeer a = new FakePeer("10.0.0.1");
		a.sent = 60 * MB;
		pass(downloading, a);
		File f = dir.resolve("ledger.txt").toFile();
		engine.saveLedger(f);
		setUp();
		assertEquals(0, engine.loadLedger(f, now + config.ledgerTtlMs + 60_000));
	}
}
