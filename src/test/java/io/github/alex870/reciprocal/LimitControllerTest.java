package io.github.alex870.reciprocal;

import static io.github.alex870.reciprocal.LimitController.Result.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LimitControllerTest {

	final Map<String, Integer> peerLimits = new HashMap<String, Integer>();
	LimitController<String> c;

	@BeforeEach
	void setUp() {
		c = new LimitController<String>(new LimitController.Access<String>() {
			public int getUploadLimit(String peer) {
				Integer v = peerLimits.get(peer);
				return v == null ? 0 : v;
			}

			public void setUploadLimit(String peer, int limit) {
				peerLimits.put(peer, limit);
			}
		});
	}

	@Test
	void setsAndUpdatesOwnLimit() {
		assertEquals(SET, c.apply("a", 1000));
		assertEquals(UNCHANGED, c.apply("a", 1000));
		assertEquals(SET, c.apply("a", 2000));
		assertEquals(2000, peerLimits.get("a"));
	}

	@Test
	void unlimitedOnUntouchedPeerDoesNothing() {
		assertEquals(UNCHANGED, c.apply("a", 0));
		assertFalse(peerLimits.containsKey("a"));
	}

	@Test
	void neverOverridesForeignLimit() {
		peerLimits.put("a", 5000); // set by the user
		assertEquals(SKIPPED_FOREIGN, c.apply("a", 1000));
		assertEquals(5000, peerLimits.get("a"));
	}

	@Test
	void releasesPeerChangedBehindOurBack() {
		c.apply("a", 1000);
		peerLimits.put("a", 7000); // user changed it
		assertEquals(SKIPPED_FOREIGN, c.apply("a", 2000));
		assertEquals(7000, peerLimits.get("a"));
		assertFalse(c.release("a"));
		assertEquals(7000, peerLimits.get("a"));
	}

	@Test
	void restoringToUnlimitedReleasesOwnership() {
		c.apply("a", 1000);
		assertEquals(RESTORED, c.apply("a", 0));
		assertEquals(0, peerLimits.get("a"));
		assertEquals(0, c.ownedCount());
	}

	@Test
	void releaseAllLiftsOnlyOwnLimits() {
		c.apply("a", 1000);
		c.apply("b", -1);
		peerLimits.put("u", 3000);
		c.apply("u", 1000);
		assertEquals(2, c.releaseAll());
		assertEquals(0, peerLimits.get("a"));
		assertEquals(0, peerLimits.get("b"));
		assertEquals(3000, peerLimits.get("u"));
	}

	@Test
	void retainOnlyForgetsDisconnectedPeers() {
		c.apply("a", 1000);
		c.apply("b", 1000);
		c.retainOnly(Collections.singleton("a"));
		assertEquals(1, c.ownedCount());
	}
}
