package io.github.alex870.reciprocal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Applies per-peer upload limits while tracking which limits this plugin owns.
 * <p>
 * Rules:
 * <ul>
 * <li>A peer is only adopted if its limit is still the default (unlimited), so limits set by
 *     the user or another plugin are never overwritten.</li>
 * <li>If an owned peer's limit no longer matches what we last set, someone else changed it:
 *     we release the peer and leave it alone for the rest of its connection.</li>
 * <li>{@link #releaseAll()} restores every owned peer to unlimited (used on completion,
 *     disable and unload).</li>
 * </ul>
 * Generic over the peer type so it can be tested without BiglyBT.
 */
final class LimitController<P> {

	/** Reads and writes a peer's upload limit. */
	interface Access<P> {
		int getUploadLimit(P peer);
		void setUploadLimit(P peer, int limit);
	}

	/** Outcome of {@link #apply}. */
	enum Result { UNCHANGED, SET, RESTORED, SKIPPED_FOREIGN }

	private final Access<P> access;
	private final Map<P, Integer> owned = new HashMap<P, Integer>(); // peer -> limit we last set
	private final Set<P> handsOff = new HashSet<P>();                 // peers someone else controls

	LimitController(Access<P> access) {
		this.access = access;
	}

	synchronized Result apply(P peer, int desired) {
		if (handsOff.contains(peer)) return Result.SKIPPED_FOREIGN;
		int current = access.getUploadLimit(peer);
		Integer lastSet = owned.get(peer);

		if (lastSet == null) {
			if (current != ReciprocityPolicy.UNLIMITED) { // someone else's limit
				handsOff.add(peer);
				return Result.SKIPPED_FOREIGN;
			}
			if (desired == ReciprocityPolicy.UNLIMITED) return Result.UNCHANGED;
			access.setUploadLimit(peer, desired);
			owned.put(peer, desired);
			return Result.SET;
		}

		if (current != lastSet) { // changed behind our back
			owned.remove(peer);
			handsOff.add(peer);
			return Result.SKIPPED_FOREIGN;
		}
		if (desired == current) return Result.UNCHANGED;

		access.setUploadLimit(peer, desired);
		if (desired == ReciprocityPolicy.UNLIMITED) {
			owned.remove(peer);
			return Result.RESTORED;
		}
		owned.put(peer, desired);
		return Result.SET;
	}

	/** Restores one peer to unlimited if we own it. Returns true if a limit was lifted. */
	synchronized boolean release(P peer) {
		Integer lastSet = owned.remove(peer);
		if (lastSet == null) return false;
		if (access.getUploadLimit(peer) != lastSet) return false; // not ours any more
		access.setUploadLimit(peer, ReciprocityPolicy.UNLIMITED);
		return true;
	}

	/** Restores every owned peer to unlimited. Returns the number of limits lifted. */
	synchronized int releaseAll() {
		int lifted = 0;
		for (P peer : new HashSet<P>(owned.keySet())) {
			try {
				if (release(peer)) lifted++;
			} catch (RuntimeException e) {
				owned.remove(peer); // peer probably disconnected; nothing to restore
			}
		}
		owned.clear();
		handsOff.clear();
		return lifted;
	}

	/** Forgets peers that are no longer connected, so state does not grow over time. */
	synchronized void retainOnly(Set<P> connected) {
		for (Iterator<P> it = owned.keySet().iterator(); it.hasNext();) {
			if (!connected.contains(it.next())) it.remove();
		}
		handsOff.retainAll(connected);
	}

	synchronized int ownedCount() {
		return owned.size();
	}
}
