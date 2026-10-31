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

	static final double BYTES_PER_MB = 1024d * 1024d;

	/** Severity above which upload may be switched off entirely (if enabled in settings). */
	static final double EXTREME_DEFICIT_SEVERITY = 0.99;

	/** What to do with peers that have nothing we want (so cannot currently give back). */
	public enum NonInteresting {
		/** No free start; throttled like any other peer with a current rate of 0. */
		MIN_RATE,
		/** Free start applies, then throttled like any other peer with a current rate of 0. */
		ALLOWANCE,
		/** Never limited (0.2.x behaviour). */
		UNLIMITED;

		static NonInteresting parse(String s) {
			for (NonInteresting v : values()) if (v.name().equalsIgnoreCase(s)) return v;
			return MIN_RATE;
		}
	}

	/** Settings that shape the policy for one torrent. Byte values are bytes, rates bytes/second. */
	public static final class Settings {
		final double startThresholdBytes;
		final double ignoreMarginBytes;
		final int minRateBps;
		final boolean disableAtExtremeDeficit;
		final NonInteresting nonInteresting;
		final boolean exemptLan;

		/**
		 * @param startThresholdBytes free start: peers sent no more than this are unlimited; 0 = no free start
		 * @param ignoreMarginBytes   peers ahead by at least this much are unlimited; must be > 0
		 * @param minRateBps          floor for any throttled limit; must be > 0
		 */
		public Settings(double startThresholdBytes, double ignoreMarginBytes, int minRateBps,
				boolean disableAtExtremeDeficit, NonInteresting nonInteresting, boolean exemptLan) {
			if (!(ignoreMarginBytes > 0)) throw new IllegalArgumentException("ignoreMarginBytes must be > 0");
			if (minRateBps <= 0) throw new IllegalArgumentException("minRateBps must be > 0");
			this.startThresholdBytes = Math.max(0, startThresholdBytes);
			this.ignoreMarginBytes = ignoreMarginBytes;
			this.minRateBps = minRateBps;
			this.disableAtExtremeDeficit = disableAtExtremeDeficit;
			this.nonInteresting = nonInteresting;
			this.exemptLan = exemptLan;
		}

		public static Settings ofMegabytes(int startThresholdMb, int ignoreMarginMb, int minRateBps, boolean disableAtExtremeDeficit) {
			return new Settings(startThresholdMb * BYTES_PER_MB, ignoreMarginMb * BYTES_PER_MB, minRateBps,
					disableAtExtremeDeficit, NonInteresting.MIN_RATE, true);
		}

		/** Same settings with the free start removed (used when a torrent's free-upload cap is used up). */
		public Settings withoutFreeStart() {
			return new Settings(0, ignoreMarginBytes, minRateBps, disableAtExtremeDeficit, nonInteresting, exemptLan);
		}

		public Settings withNonInteresting(NonInteresting n) {
			return new Settings(startThresholdBytes, ignoreMarginBytes, minRateBps, disableAtExtremeDeficit, n, exemptLan);
		}

		public Settings withExemptLan(boolean exempt) {
			return new Settings(startThresholdBytes, ignoreMarginBytes, minRateBps, disableAtExtremeDeficit, nonInteresting, exempt);
		}
	}

	/** Why a decision was made; used for logging and the UI. */
	public enum Reason {
		LAN("exempt (LAN)"),
		NOT_INTERESTING("unlimited (nothing we want)"),
		FREE_START("free start"),
		WELL_AHEAD("unlimited (well ahead)"),
		DEFICIT("deficit"),
		EXTREME_DEFICIT("disabled (extreme deficit)"),
		SURPLUS("surplus");

		public final String label;

		Reason(String label) {
			this.label = label;
		}

		public boolean isThrottled() {
			return this == DEFICIT || this == EXTREME_DEFICIT || this == SURPLUS;
		}
	}

	/** The outcome for one peer. */
	public static final class Decision {
		public final int limit;
		public final Reason reason;
		/** Severity (deficit) or margin factor (surplus), 0..1; 0 for other reasons. */
		public final double factor;
		/** Throttled because the peer has nothing we want (it cannot currently give back). */
		public final boolean nothingWanted;

		Decision(int limit, Reason reason, double factor) {
			this(limit, reason, factor, false);
		}

		Decision(int limit, Reason reason, double factor, boolean nothingWanted) {
			this.limit = limit;
			this.reason = reason;
			this.factor = factor;
			this.nothingWanted = nothingWanted;
		}

		Decision nothingWanted() {
			return reason.isThrottled() ? new Decision(limit, reason, factor, true) : this;
		}

		/** Human-readable reason, e.g. "deficit" or "limited (nothing we want)". */
		public String label() {
			if (nothingWanted && reason == Reason.SURPLUS && factor == 0) return "limited (nothing we want)";
			if (nothingWanted) return reason.label + ", nothing we want";
			if (reason == Reason.SURPLUS && factor == 0) return "even";
			return reason.label;
		}

		@Override
		public String toString() {
			return reason + " limit=" + limit + " factor=" + factor;
		}
	}

	private ReciprocityPolicy() {}

	/** Convenience overload for an interesting, non-LAN peer. */
	public static Decision decide(long bytesSent, long bytesReceived, long receiveRateBps, boolean interesting, Settings s) {
		return decide(bytesSent, bytesReceived, receiveRateBps, interesting, false, s);
	}

	/**
	 * @param bytesSent       data bytes we have sent to the peer (ledger total)
	 * @param bytesReceived   data bytes we have received from the peer (ledger total)
	 * @param receiveRateBps  peer's current send rate to us
	 * @param interesting     whether the peer has pieces we want
	 * @param lanLocal        whether the peer is on the local network
	 */
	public static Decision decide(long bytesSent, long bytesReceived, long receiveRateBps,
			boolean interesting, boolean lanLocal, Settings s) {
		Decision d = decideInner(bytesSent, bytesReceived, receiveRateBps, interesting, lanLocal, s);
		return interesting ? d : d.nothingWanted();
	}

	private static Decision decideInner(long bytesSent, long bytesReceived, long receiveRateBps,
			boolean interesting, boolean lanLocal, Settings s) {
		if (lanLocal && s.exemptLan) return new Decision(UNLIMITED, Reason.LAN, 0);

		boolean freeStartAllowed = true;
		if (!interesting) {
			switch (s.nonInteresting) {
			case UNLIMITED:
				return new Decision(UNLIMITED, Reason.NOT_INTERESTING, 0);
			case MIN_RATE:
				freeStartAllowed = false;
				break;
			case ALLOWANCE:
				break;
			}
			receiveRateBps = 0; // they cannot be sending us anything useful
		}

		double rate = Math.max(0, receiveRateBps);
		double balance = (double) bytesReceived - (double) bytesSent; // > 0: peer has given us more
		if (balance >= s.ignoreMarginBytes) return new Decision(UNLIMITED, Reason.WELL_AHEAD, 0);

		if (freeStartAllowed && s.startThresholdBytes > 0 && bytesSent <= s.startThresholdBytes) {
			return new Decision(UNLIMITED, Reason.FREE_START, 0);
		}

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
		// Credit for a peer that is ahead but currently slow, choking us, or has nothing we
		// want: 1 KiB/s of upload per MiB of surplus.
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
	 * A threshold given in MB, optionally also as a percentage of torrent size. When the
	 * percentage is > 0 the smaller of the two applies. Never below {@code floorBytes}.
	 */
	static double scaledThreshold(int mb, int pct, long torrentSize, double floorBytes) {
		double bytes = mb * BYTES_PER_MB;
		if (pct > 0 && torrentSize > 0) bytes = Math.min(bytes, torrentSize * (pct / 100d));
		return Math.max(floorBytes, bytes);
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
