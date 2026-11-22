package com.eurobuddha.comms;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** 2026-11-22 (P2 c, d): a failed read never moves the tip bookmark, and the payment scanner's window. */
public class CommsScannerBookmarkTest {
    static final String ADDR = "0x4D494E494D45524348";

    static CommsScanner.MetaStore store(Map<String, String> m) {
        return new CommsScanner.MetaStore() {
            public String getMeta(String k, String d) { return m.getOrDefault(k, d); }
            public void setMeta(String k, String v) { m.put(k, v); }
        };
    }

    @Test public void aFailedReadDoesNotMoveTheBookmark() throws Exception {
        NodeApi node = mock(NodeApi.class);
        doAnswer(inv -> {   // every coins page refused (a reply over the limit)
            NodeApi.Cb cb = inv.getArgument(1);
            String c = inv.getArgument(0);
            cb.onResult(c.startsWith("coins") ? new JSONObject().put("status", false) : new JSONObject().put("status", true).put("response", new JSONArray()));
            return null;
        }).when(node).cmd(anyString(), any(NodeApi.Cb.class));
        Map<String, String> m = new HashMap<>();
        m.put("backfilled_4D494E49", "true"); m.put("scanned_tip_4D494E49", "50");
        boolean[] done = {false, true};
        new CommsScanner(node, null, store(m), ADDR, (id, o, c) -> false, (ok, n) -> { done[0] = true; done[1] = ok; }).scan(60);
        assertTrue(done[0]);
        assertFalse(done[1]);
        assertEquals("a failed read keeps the bookmark", "50", m.get("scanned_tip_4D494E49"));
    }

    @Test public void theWindow() {
        assertEquals("a first pass", 64, CommsScanner.targetDepth(true, 100, 0, false));
        assertEquals("no tip", 64, CommsScanner.targetDepth(false, 0, 90, false));
        assertEquals("orders: the gap, as before", 3, CommsScanner.targetDepth(false, 100, 98, false));
        assertEquals("payments: the gap and the margin", 2 + CommsScanner.REREAD_MARGIN, CommsScanner.targetDepth(false, 100, 99, true));
        assertEquals("never more than 64", 64, CommsScanner.targetDepth(false, 1000, 1, true));
    }

    @Test public void aFollowingScannersBookmarkNeverPassesTheLeadsAndItHasNoThrottle() throws Exception {
        NodeApi node = mock(NodeApi.class);
        doAnswer(inv -> { ((NodeApi.Cb) inv.getArgument(1)).onResult(new JSONObject().put("status", true).put("response", new JSONArray())); return null; })
                .when(node).cmd(anyString(), any(NodeApi.Cb.class));
        Map<String, String> m = new HashMap<>();
        m.put("backfilled_pay_12121212", "true"); m.put("scanned_tip_pay_12121212", "90"); m.put("scanned_tip_4D494E49", "92");
        CommsScanner s = new CommsScanner(node, null, store(m), "0x" + "12".repeat(32), (id, o, c) -> false, (ok, n) -> {}, false);
        s.follows("scanned_tip_4D494E49");
        assertTrue(s.scan(95));
        assertEquals("92", m.get("scanned_tip_pay_12121212"));
        m.put("scanned_tip_4D494E49", "95");
        assertTrue("no throttle of its own", s.scan(95));
        assertEquals("95", m.get("scanned_tip_pay_12121212"));
    }
}
