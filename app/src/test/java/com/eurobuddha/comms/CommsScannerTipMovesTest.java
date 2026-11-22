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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 2026-11-22 (r3): a node faithful to the node's `coins` (depth:D walks tip..tip-D, coinage:C keeps created <= tip-C),
 *  one coin per block, a tip that moves while a pass reads, and blocks it refuses. No block may be both unread and
 *  unreported: every block up to the bookmark was handed to the router or is inside an unread note. */
public class CommsScannerTipMovesTest {
    static final Pattern PAGE = Pattern.compile("coins address:\\S+ order:desc depth:([0-9]+)( coinage:([0-9]+))?");
    static final String PAY = "0x" + "12".repeat(32), BM = "scanned_tip_pay_12121212", UNREAD = "unread_pay_12121212:";

    static final class Node {
        long tip; int perCall, onceAfterFirst, calls, maxSpan = Integer.MAX_VALUE, blockFails;
        final Set<Long> refuse = new HashSet<>();
        NodeApi api() {
            NodeApi n = mock(NodeApi.class);
            doAnswer(inv -> {
                NodeApi.Cb cb = inv.getArgument(1); String c = inv.getArgument(0);
                if ("block".equals(c) && blockFails-- > 0) { cb.onError("timeout"); return null; }
                if ("block".equals(c)) { cb.onResult(new JSONObject().put("status", true).put("response", new JSONObject().put("block", String.valueOf(tip)))); return null; }
                Matcher m = PAGE.matcher(c);
                if (!m.matches()) { cb.onResult(new JSONObject().put("status", true).put("response", new JSONArray())); return null; }
                int d = Integer.parseInt(m.group(1)), a = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
                long lo = tip - d, hi = tip - a;
                boolean bad = d - a > maxSpan;
                for (long b = lo; b <= hi; b++) if (refuse.contains(b)) bad = true;
                tip += perCall + (calls++ == 0 ? onceAfterFirst : 0);
                if (bad) { cb.onResult(new JSONObject().put("status", false)); return null; }
                JSONArray coins = new JSONArray();
                for (long b = hi; b >= lo; b--) coins.put(new JSONObject().put("coinid", "0x" + Long.toHexString(1000 + b)).put("created", String.valueOf(b)));
                cb.onResult(new JSONObject().put("status", true).put("response", coins));
                return null;
            }).when(n).cmd(anyString(), any(NodeApi.Cb.class));
            return n;
        }
    }

    static boolean pass(Node node, Map<String, String> m, int chainBlock, Set<Long> seen) {
        boolean[] ok = {false};
        new CommsScanner(node.api(), null, CommsScannerBookmarkTest.store(m), PAY,
                (id, o, c) -> { seen.add(c.optLong("created")); return false; }, (k, n) -> ok[0] = k, false).scan(chainBlock);
        return ok[0];
    }

    static boolean noted(Map<String, String> m, long b) {
        for (Map.Entry<String, String> e : m.entrySet())
            if (e.getKey().startsWith(UNREAD) && Long.parseLong(e.getKey().substring(UNREAD.length())) <= b
                    && b <= Long.parseLong(e.getValue().split(" ")[0])) return true;
        return false;
    }

    static void noSilentHole(Map<String, String> m, long from, Set<Long> seen) {
        long bm = Long.parseLong(m.get(BM));
        for (long b = from; b <= bm; b++) assertTrue("block " + b + " neither read nor noted, bookmark " + bm + ": " + m, seen.contains(b) || noted(m, b));
    }

    static Map<String, String> start(int bookmark) {
        Map<String, String> m = new HashMap<>(); m.put("backfilled_pay_12121212", "true"); m.put(BM, String.valueOf(bookmark)); return m;
    }

    /** R3-A: the tip moves one block per page of a paged pass. */
    @Test public void aTipMovingOnEveryPageLeavesNoSilentHole() {
        Map<String, String> m = start(79); Set<Long> seen = new TreeSet<>();
        Node node = new Node(); node.tip = 100; node.perCall = 1; node.maxSpan = 4;
        pass(node, m, 100, seen);
        noSilentHole(m, 80, seen);
    }

    /** The tip moved 3 blocks after the first page: those blocks are read too and the pass ends, bookmark at its tip. */
    @Test public void aTipThatMovedIsReadAgainAndThePassEnds() {
        Map<String, String> m = start(79); Set<Long> seen = new TreeSet<>();
        Node node = new Node(); node.tip = 100; node.onceAfterFirst = 3;
        assertTrue(pass(node, m, 100, seen));
        assertEquals("100", m.get(BM));
        noSilentHole(m, 80, seen);
        assertFalse("nothing was skipped: " + m, m.keySet().stream().anyMatch(k -> k.startsWith("unread_")));
    }

    /** R3-B1: a pass capped at the window (the Inbox was closed), the tip moving one block per page. */
    @Test public void aCappedPassWhoseTipMovesNamesEveryBlockItDidNotRead() {
        Map<String, String> m = start(100); Set<Long> seen = new TreeSet<>();
        Node node = new Node(); node.tip = 300; node.perCall = 1;
        pass(node, m, 300, seen);
        noSilentHole(m, 101, seen);
    }

    /** A capped pass whose tip moved twice after its first page: the note ends at the lowest block read, no lower. */
    @Test public void aCappedPassNamesTheGapUpToTheLowestBlockItRead() {
        Map<String, String> m = start(100); Set<Long> seen = new TreeSet<>();
        Node node = new Node(); node.tip = 300; node.onceAfterFirst = 2;
        assertTrue(pass(node, m, 300, seen));
        assertEquals("300", m.get(BM));
        assertEquals("236", m.get(UNREAD + "101"));
        noSilentHole(m, 101, seen);
    }

    /** r3 I3: a range left unread because the node refused a read (block 150, until it left the window) says so; the
     *  count of passes that ended partly read is back to 0 after the whole pass. A range of the window alone does not. */
    @Test public void aRangeLeftByRefusedReadsSaysSo() {
        Map<String, String> m = start(100); Set<Long> seen = new TreeSet<>();
        Node node = new Node(); node.refuse.add(150L);
        for (int t = 151; t <= 215; t++) { node.tip = t; pass(node, m, t, seen); }
        assertEquals("151 refused", m.get(UNREAD + "101"));
        assertEquals("215", m.get(BM));
        assertEquals("0", m.get("failedpasses_pay_12121212"));
        noSilentHole(m, 101, seen);
    }

    /** 2026-11-22 (final review F1 b): a tip that cannot be read holds the bookmark, but every page was read: it is not
     *  a refused pass (no count toward the stall line, no refused blocks). */
    @Test public void aTipThatCannotBeReadHoldsTheBookmarkButIsNotARefusedPass() {
        Map<String, String> m = start(95); Set<Long> seen = new TreeSet<>();
        Node node = new Node(); node.tip = 100; node.blockFails = 99;
        assertFalse(pass(node, m, 100, seen));
        assertEquals("95", m.get(BM));
        assertNull("not counted: " + m, m.get("failedpasses_pay_12121212"));
        assertTrue(m.getOrDefault("refusedblocks_pay_12121212", "").isEmpty());
    }

    /** F1: a tip read that failed once is read again: the pass ends whole. */
    @Test public void aTipReadThatFailedIsReadAgain() {
        Map<String, String> m = start(95); Set<Long> seen = new TreeSet<>();
        Node node = new Node(); node.tip = 100; node.blockFails = 1;
        assertTrue(pass(node, m, 100, seen));
        assertEquals("100", m.get(BM));
    }

    /** F1: the tip moves one block on every page: the blocks it left are read with the margin below them, so the pass
     *  ends whole although the tip moved again while they were read; no block is left unread. */
    @Test public void aTipThatMovesWhileTheBlocksItLeftAreReadIsStillCovered() {
        Map<String, String> m = start(95); Set<Long> seen = new TreeSet<>();
        Node node = new Node(); node.tip = 100; node.perCall = 1;
        assertTrue(pass(node, m, 100, seen));
        assertEquals("100", m.get(BM));
        noSilentHole(m, 96, seen);
    }

    /** F1 d: one refused pass, then the Inbox closed for a long time: only the blocks that pass tried are said refused;
     *  the rest of the gap is the window's. */
    @Test public void aRefusedPassBeforeALongCloseLabelsOnlyWhatItTried() {
        Map<String, String> m = start(99); Set<Long> seen = new TreeSet<>();
        Node node = new Node(); node.tip = 101; node.refuse.add(100L);
        assertFalse(pass(node, m, 101, seen));
        assertEquals("1", m.get("failedpasses_pay_12121212"));
        node.refuse.clear(); node.tip = 400;
        assertTrue(pass(node, m, 400, seen));
        assertEquals("101 refused", m.get(UNREAD + "100"));
        assertEquals("336", m.get(UNREAD + "102"));
        assertEquals("0", m.get("failedpasses_pay_12121212"));
        noSilentHole(m, 100, seen);
    }

    /** Final review (phone): a held pass (every page read, tip not confirmed) cannot prove it read down to the refused
     *  blocks: it keeps their record and the count. Here the tip moves 5 blocks during its pages, so 101 is never read;
     *  refused and held passes in turn still reach the stall line (5 in a row). */
    @Test public void aHeldPassKeepsTheRefusedBlocksAndTheCount() {
        Map<String, String> m = start(100); Set<Long> seen = new TreeSet<>();
        Node node = new Node(); node.tip = 105; node.refuse.add(101L);
        assertFalse(pass(node, m, 105, seen));
        assertEquals("101 105", m.get("refusedblocks_pay_12121212"));
        assertEquals("1", m.get("failedpasses_pay_12121212"));
        node.refuse.clear(); node.tip = 106; node.calls = 0; node.onceAfterFirst = 5; node.blockFails = 99;
        assertFalse(pass(node, m, 106, seen));
        assertFalse("101 was not read", seen.contains(101L));
        assertEquals("100", m.get(BM));
        assertEquals("101 105", m.get("refusedblocks_pay_12121212"));
        assertEquals("1", m.get("failedpasses_pay_12121212"));
        for (int p = 0; p < 8; p++) {
            boolean refused = p % 2 == 0;
            node.refuse.clear(); if (refused) node.refuse.add(101L);
            node.calls = 0; node.onceAfterFirst = refused ? 0 : 5; node.blockFails = refused ? 0 : 99;
            assertFalse(pass(node, m, (int) ++node.tip, seen));
        }
        assertEquals("5", m.get("failedpasses_pay_12121212"));
        assertFalse(m.getOrDefault("refusedblocks_pay_12121212", "").isEmpty());
    }

    /** The orders scan reads its gap only (orders are stored idempotently; the tip read again covers a tip that moved). */
    @Test public void theOrdersScanReadsItsGapOnly() {
        Map<String, String> m = new HashMap<>(); m.put("backfilled_4D494E49", "true"); m.put("scanned_tip_4D494E49", "95");
        Node node = new Node(); node.tip = 100;
        Set<String> asked = new TreeSet<>();
        NodeApi api = node.api();
        NodeApi spy = mock(NodeApi.class);
        doAnswer(inv -> { asked.add(inv.getArgument(0)); api.cmd(inv.getArgument(0), inv.getArgument(1)); return null; }).when(spy).cmd(anyString(), any(NodeApi.Cb.class));
        new CommsScanner(spy, null, CommsScannerBookmarkTest.store(m), CommsScannerBookmarkTest.ADDR, (id, o, c) -> false, (k, n) -> {}).scan(100);
        assertTrue(asked.toString(), asked.stream().anyMatch(c -> c.endsWith("depth:6")));
        assertFalse(asked.toString(), asked.stream().anyMatch(c -> c.contains("depth:" + (6 + CommsScanner.REREAD_MARGIN))));
    }
}
