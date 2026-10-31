package io.github.alex870.reciprocal;

/**
 * Decides the upload rate limit for one peer on a torrent that is still downloading.
 * <p>
 * Pure logic with no BiglyBT dependencies, so every branch can be unit tested.
 * Results use BiglyBT's per-peer limit convention:
 * {@link #UNLIMITED} (0), {@link #DISABLED} (-1), or a positive bytes/second value.
 * A throttled result is never 0, because BiglyBT would read that as "unlimited".
 */
public final class ReciprocityPolicy {

	public static final int UNLIMITED = 0;
	public static final int DISABLED = -1;

	private static final double BYTES_PER_MB = 1024d * 1024d;

	/** Severity above which upload may be switched off entirely (if enabled in settings). */
	static final double EXTREME_DEFICIT_SEVERITY = 0.99;

	/** Settings that shape the policy. All byte values are bytes, rates are bytes/second. */
	public static final class Settings {
		final double startThresholdBytes;
		final double ignoreMarginBytes;
		final int minRateBps;
		final boolean disableAtExtremeDeficit;

		/**
		 * @param startThresholdBytes peers sent no more than this are left unlimited
		 * @param ignoreMarginBytes   peers ahead by at least this much are left unlimited; must be > 0
		 * @param minRateBps          floor for any throttled limit; must be > 0
		 * @param disableAtExtremeDeficit switch upload off for peers in near-total deficit instead of flooring
		 */
		public Settings(double startThresholdBytes, double ignoreMarginBytes, int minRateBps, boolean disableAtExtremeDeficit) {
			if (!(ignoreMarginBytes > 0)) throw new IllegalArgumentException("ignoreMarginBytes must be > 0");
			if (minRateBps <= 0) throw new IllegalArgumentException("minRateBps must be > 0");
			this.startThresholdBytes = startThresholdBytes;
			this.ignoreMarginBytes = ignoreMarginBytes;
			this.minRateBps = minRateBps;
			this.disableAtExtremeDeficit = disableAtExtremeDeficit;
		}

		public static Settings ofMegabytes(int startThresholdMb, int ignoreMarginMb, int minRateBps, boolean disableAtExtremeDeficit) {
			return new Settings(startThresholdMb * BYTES_PER_MB, ignoreMarginMb * BYTES_PER_MB, minRateBps, disableAtExtremeDeficit);
		}
	}

	/** Why a decision was made; used for logging. */
	public enum Reason { NOT_INTERESTING, FREE_START, WELL_AHEAD, DEFICIT, EXTREME_DEFICIT, SURPLUS }

	/** The outcome for one peer. */
	public static final class Decision {
		public final int limit;
		public final Reason reason;
		/** Severity (deficit) or margin factor (surplus), 0..1; 0 for other reasons. */
		public final double factor;

		Decision(int limit, Reason reason, double factor) {
			this.limit = limit;
			this.reason = reason;
			this.factor = factor;
		}

		@Override
		public String toString() {
			return reason + " limit=" + limit + " factor=" + factor;
		}
	}

	private ReciprocityPolicy() {}

	/**
	 * @param bytesSent       data bytes we have sent to the peer
	 * @param bytesReceived   data bytes we have received from the peer
	 * @param receiveRateBps  peer's current (smoothed) send rate to us
	 * @param interesting     whether the peer has pieces we want
	 */
	public static Decision decide(long bytesSent, long bytesReceived, long receiveRateBps, boolean interesting, Settings s) {
		if (!interesting) return new Decision(UNLIMITED, Reason.NOT_INTERESTING, 0);
		if (bytesSent <= s.startThresholdBytes) return new Decision(UNLIMITED, Reason.FREE_START, 0);

		double rate = Math.max(0, receiveRateBps);
		double balance = (double) bytesReceived - (double) bytesSent; // > 0: peer has given us more
		if (balance >= s.ignoreMarginBytes) return new Decision(UNLIMITED, Reason.WELL_AHEAD, 0);

		if (balance < 0) {
			// 0 (barely behind) .. 1 (behind by the whole margin or more)
			double severity = clamp01(-balance / s.ignoreMarginBytes);
			if (severity > EXTREME_DEFICIT_SEVERITY && s.disableAtExtremeDeficit) {
				return new Decision(DISABLED, Reason.EXTREME_DEFICIT, severity);
			}
			double limit = rate * (1 - severity);
			return new Decision(floor(limit, s.minRateBps), Reason.DEFICIT, severity);
		}

		// Peer is ahead but within the margin: be more generous than their rate so the
		// other side sees a higher rate and raises its own, levelling out at the highest
		// rate both clients can sustain.
		double marginFactor = clamp01(balance / s.ignoreMarginBytes); // 0 (even) .. 1 (well ahead)
		double limit = rate * (1 + 0.5 * marginFactor);
		// Credit for a peer that is ahead but currently slow or choking us:
		// 1 KiB/s of upload per MiB of surplus.
		double credit = balance / BYTES_PER_MB * 1024;
		if (limit < credit) limit = credit;
		return new Decision(floor(limit, s.minRateBps), Reason.SURPLUS, marginFactor);
	}

	private static int floor(double limit, int minRateBps) {
		if (!(limit >= minRateBps)) return minRateBps; // also catches NaN
		if (limit >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
		return (int) Math.round(limit);
	}

	private static double clamp01(double v) {
		if (v < 0) return 0;
		if (v > 1) return 1;
		return v;
	}

	/**
	 * Parses a minimum-rate setting in KiB/s. Returns null if it is not a finite number > 0.
	 */
	public static Float parseMinRateKiB(String text) {
		if (text == null) return null;
		try {
			float v = Float.parseFloat(text.trim());
			if (Float.isNaN(v) || Float.isInfinite(v) || v <= 0) return null;
			return v;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Converts a KiB/s value to a positive bytes/second limit (at least 1). */
	public static int kibToBps(float kib) {
		long bps = Math.round(kib * 1024d);
		if (bps < 1) return 1;
		if (bps > Integer.MAX_VALUE) return Integer.MAX_VALUE;
		return (int) bps;
	}
}
