package com.eurobuddha.comms;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 2026-11-22 (r3 I2): the payment scanner's bookmark stays at the orders read, while each of its passes reads 64 blocks
 *  back; it keeps its own "read whole up to" mark and never notes as unread a block it read. */
public class CommsScannerReadWholeTest {
    static final String LEAD = "scanned_tip_4D494E49";

    static void payPass(CommsScannerTipMovesTest.Node node, Map<String, String> m, int tip, Set<Long> seen) {
        node.tip = tip;
        CommsScanner s = new CommsScanner(node.api(), null, CommsScannerBookmarkTest.store(m), CommsScannerTipMovesTest.PAY,
                (id, o, c) -> { seen.add(c.optLong("created")); return false; }, (k, n) -> {}, false);
        s.follows(LEAD);
        s.scan(tip);
    }

    /** A note names the lowest block its pass read as its last block (never fewer): any other read block is a false note. */
    static void nothingReadIsNoted(Map<String, String> m, Set<Long> seen) {
        for (long b : seen) assertFalse("block " + b + " was read but is noted unread: " + m,
                CommsScannerTipMovesTest.noted(m, b) && !m.containsValue(String.valueOf(b)));
    }

    static Map<String, String> stuckOrders() {
        Map<String, String> m = CommsScannerTipMovesTest.start(100);
        m.put(LEAD, "100");   // the orders scan held at 100 by one block the node will not serve
        return m;
    }

    /** R3-G: orders held at 100 while payments read every block to 300; the orders bookmark then jumps to the tip. */
    @Test public void whenTheOrdersBookmarkJumpsNothingReadIsNoted() {
        Map<String, String> m = stuckOrders(); Set<Long> seen = new TreeSet<>();
        CommsScannerTipMovesTest.Node node = new CommsScannerTipMovesTest.Node();
        for (int t = 101; t <= 300; t++) payPass(node, m, t, seen);
        assertEquals("100", m.get(CommsScannerTipMovesTest.BM));
        m.put(LEAD, "300");
        payPass(node, m, 301, seen);
        assertEquals("300", m.get(CommsScannerTipMovesTest.BM));
        nothingReadIsNoted(m, seen);
        CommsScannerTipMovesTest.noSilentHole(m, 101, seen);
        assertFalse("every block was read: " + m, m.keySet().stream().anyMatch(k -> k.startsWith("unread_")));
    }

    /** Orders held at 100; payments read to 150, then the Inbox was closed until 400: 151 to 336 are noted at once,
     *  and the blocks it read before are not, when the orders bookmark later jumps. */
    @Test public void aGapWhileTheOrdersAreHeldIsNotedOnceAndOnlyIt() {
        Map<String, String> m = stuckOrders(); Set<Long> seen = new TreeSet<>();
        CommsScannerTipMovesTest.Node node = new CommsScannerTipMovesTest.Node();
        for (int t = 101; t <= 150; t++) payPass(node, m, t, seen);
        payPass(node, m, 400, seen);
        assertEquals("336", m.get(CommsScannerTipMovesTest.UNREAD + "151"));
        m.put(LEAD, "400");
        payPass(node, m, 401, seen);
        assertEquals("400", m.get(CommsScannerTipMovesTest.BM));
        nothingReadIsNoted(m, seen);
        CommsScannerTipMovesTest.noSilentHole(m, 101, seen);
    }
}
