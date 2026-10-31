package io.github.alex870.reciprocal;

import static io.github.alex870.reciprocal.ReciprocityPolicy.DISABLED;
import static io.github.alex870.reciprocal.ReciprocityPolicy.UNLIMITED;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import io.github.alex870.reciprocal.ReciprocityPolicy.Decision;
import io.github.alex870.reciprocal.ReciprocityPolicy.Reason;
import io.github.alex870.reciprocal.ReciprocityPolicy.Settings;

class ReciprocityPolicyTest {

	static final long MB = 1024L * 1024L;
	static final int MIN = 512; // 0.5 KiB/s
	static final Settings DEFAULTS = Settings.ofMegabytes(10, 100, MIN, false);
	static final Settings DISABLE_EXTREME = Settings.ofMegabytes(10, 100, MIN, true);

	static Decision decide(long sent, long received, long rate) {
		return ReciprocityPolicy.decide(sent, received, rate, true, DEFAULTS);
	}

	@Test
	void notInterestingIsUnlimited() {
		Decision d = ReciprocityPolicy.decide(500 * MB, 0, 0, false, DEFAULTS);
		assertEquals(UNLIMITED, d.limit);
		assertEquals(Reason.NOT_INTERESTING, d.reason);
	}

	@Test
	void freeStartUpToThreshold() {
		assertEquals(Reason.FREE_START, decide(10 * MB, 0, 0).reason);
		assertEquals(UNLIMITED, decide(10 * MB, 0, 0).limit);
		assertEquals(Reason.DEFICIT, decide(10 * MB + 1, 0, 0).reason);
	}

	@Test
	void wellAheadIsUnlimited() {
		Decision d = decide(20 * MB, 120 * MB, 0);
		assertEquals(Reason.WELL_AHEAD, d.reason);
		assertEquals(UNLIMITED, d.limit);
	}

	@Test
	void deficitScalesDownWithSeverity() {
		// 50 MB behind with margin 100 MB -> severity 0.5 -> half their rate
		Decision d = decide(60 * MB, 10 * MB, 100_000);
		assertEquals(Reason.DEFICIT, d.reason);
		assertEquals(0.5, d.factor, 1e-9);
		assertEquals(50_000, d.limit);
	}

	@Test
	void deficitIsFlooredAtMinimumRate() {
		Decision d = decide(60 * MB, 10 * MB, 0);
		assertEquals(MIN, d.limit);
	}

	@Test
	void extremeDeficitFloorsByDefault() {
		Decision d = decide(200 * MB, 0, 100_000);
		assertEquals(Reason.DEFICIT, d.reason);
		assertEquals(MIN, d.limit);
	}

	@Test
	void extremeDeficitCanDisableUpload() {
		Decision d = ReciprocityPolicy.decide(200 * MB, 0, 100_000, true, DISABLE_EXTREME);
		assertEquals(Reason.EXTREME_DEFICIT, d.reason);
		assertEquals(DISABLED, d.limit);
	}

	@Test
	void surplusIsMoreGenerousThanTheirRate() {
		// 50 MB ahead -> margin factor 0.5 -> rate * 1.25
		Decision d = decide(20 * MB, 70 * MB, 100_000);
		assertEquals(Reason.SURPLUS, d.reason);
		assertEquals(125_000, d.limit);
	}

	@Test
	void surplusCreditWhenPeerIsSlow() {
		// 50 MB ahead, peer sending nothing: 1 KiB/s per MB of surplus
		Decision d = decide(20 * MB, 70 * MB, 0);
		assertEquals(50 * 1024, d.limit);
	}

	/** B2: break-even with a silent peer used to compute 0, which BiglyBT treats as unlimited. */
	@Test
	void breakEvenSilentPeerIsNeverUnlimited() {
		Decision d = decide(50 * MB, 50 * MB, 0);
		assertEquals(Reason.SURPLUS, d.reason);
		assertEquals(MIN, d.limit);
	}

	/** B1: fractional megabytes are no longer truncated. */
	@Test
	void fractionalMegabytesCount() {
		// Received 25.3 MB, sent 25.9 MB: 0.6 MB behind. Old code truncated both to 25 -> balance 0.
		Decision d = decide((long) (25.9 * MB), (long) (25.3 * MB), 100_000);
		assertEquals(Reason.DEFICIT, d.reason);
		assertTrue(d.limit < 100_000);
	}

	@Test
	void throttledResultsAreAlwaysPositive() {
		long[] amounts = {11 * MB, 50 * MB, 99 * MB, 101 * MB, 500 * MB};
		long[] rates = {0, 1, 100, 1_000_000};
		for (long sent : amounts)
			for (long received : amounts)
				for (long rate : rates) {
					Decision d = decide(sent, received, rate);
					if (d.reason == Reason.DEFICIT || d.reason == Reason.SURPLUS) {
						assertTrue(d.limit >= MIN, () -> "sent=" + sent + " received=" + received + " rate=" + rate + " -> " + d);
					}
				}
	}

	@Test
	void minRateParsing() {
		assertEquals(0.5f, ReciprocityPolicy.parseMinRateKiB("0.5"));
		assertEquals(2f, ReciprocityPolicy.parseMinRateKiB(" 2 "));
		assertNull(ReciprocityPolicy.parseMinRateKiB("0"));
		assertNull(ReciprocityPolicy.parseMinRateKiB("-1"));
		assertNull(ReciprocityPolicy.parseMinRateKiB("NaN"));
		assertNull(ReciprocityPolicy.parseMinRateKiB("Infinity"));
		assertNull(ReciprocityPolicy.parseMinRateKiB("abc"));
		assertNull(ReciprocityPolicy.parseMinRateKiB(null));
		assertEquals(512, ReciprocityPolicy.kibToBps(0.5f));
		assertEquals(1, ReciprocityPolicy.kibToBps(0.0001f));
	}

	@Test
	void settingsRejectNonPositiveMinRate() {
		assertThrows(IllegalArgumentException.class, () -> Settings.ofMegabytes(10, 100, 0, false));
	}
}
