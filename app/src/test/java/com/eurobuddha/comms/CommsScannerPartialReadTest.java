package com.eurobuddha.comms;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 2026-11-22 (R4): a pass whose deeper page is refused reads the rest of its range in older windows (coinage:), and the
 *  bookmark moves only over blocks it read; a gap wider than the window is never claimed silently. */
public class CommsScannerPartialReadTest {
    static final Pattern PAGE = Pattern.compile("coins address:\\S+ order:desc depth:([0-9]+)( coinage:([0-9]+))?");

    /** A node at {@code tip} that serves a page only when it spans at most {@code most} blocks (depth - coinage); records each page. */
    static NodeApi node(int most, List<int[]> served, int tip) {
        NodeApi node = mock(NodeApi.class);
        doAnswer(inv -> {
            NodeApi.Cb cb = inv.getArgument(1); Matcher m = PAGE.matcher((String) inv.getArgument(0));
            if ("block".equals(inv.getArgument(0))) { cb.onResult(new JSONObject().put("status", true).put("response", new JSONObject().put("block", String.valueOf(tip)))); return null; }
            if (!m.matches()) { cb.onResult(new JSONObject().put("status", true).put("response", new JSONArray())); return null; }
            int depth = Integer.parseInt(m.group(1)), coinage = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            if (depth - coinage > most) { cb.onResult(new JSONObject().put("status", false)); return null; }
            served.add(new int[]{coinage, depth});
            cb.onResult(new JSONObject().put("status", true).put("response", new JSONArray()));
            return null;
        }).when(node).cmd(anyString(), any(NodeApi.Cb.class));
        return node;
    }

    /** The reviewer's case: depth 4 of a 21-block gap is read and every deeper page is refused. */
    @Test public void aPartlyReadPassKeepsTheBookmark() {
        Map<String, String> m = new HashMap<>();
        m.put("backfilled_4D494E49", "true"); m.put("scanned_tip_4D494E49", "40");
        NodeApi reviewer = mock(NodeApi.class);
        List<String> asked = new ArrayList<>();
        doAnswer(inv -> {
            NodeApi.Cb cb = inv.getArgument(1); String c = inv.getArgument(0); asked.add(c);
            boolean refuse = c.startsWith("coins") && !c.endsWith("depth:4");
            cb.onResult(refuse ? new JSONObject().put("status", false) : new JSONObject().put("status", true).put("response", new JSONArray()));
            return null;
        }).when(reviewer).cmd(anyString(), any(NodeApi.Cb.class));
        boolean[] ok = {true};
        new CommsScanner(reviewer, null, CommsScannerBookmarkTest.store(m), CommsScannerBookmarkTest.ADDR, (id, o, c) -> false, (k, n) -> ok[0] = k).scan(60);
        assertFalse(asked.toString(), ok[0]);
        assertEquals("only 4 of the 21 blocks were read: the bookmark stays", "40", m.get("scanned_tip_4D494E49"));
        assertTrue("it tried the older part in windows: " + asked, asked.stream().anyMatch(c -> c.contains(" coinage:4")));
    }

    @Test public void theRestIsReadInOlderWindowsAndThenTheBookmarkMoves() {
        Map<String, String> m = new HashMap<>();
        m.put("backfilled_4D494E49", "true"); m.put("scanned_tip_4D494E49", "40");
        List<int[]> served = new ArrayList<>();
        boolean[] ok = {false};
        new CommsScanner(node(4, served, 60), null, CommsScannerBookmarkTest.store(m), CommsScannerBookmarkTest.ADDR, (id, o, c) -> false, (k, n) -> ok[0] = k).scan(60);
        assertTrue(ok[0]);
        assertEquals("60", m.get("scanned_tip_4D494E49"));
        int reached = 0;   // the pages cover every block from the tip down to depth 21 and the margin, with no hole
        for (int[] p : served) { assertTrue("a hole below " + reached, p[0] <= reached); reached = Math.max(reached, p[1]); }
        assertEquals(21 + CommsScanner.REREAD_MARGIN, reached);
        assertFalse("nothing was skipped", m.keySet().stream().anyMatch(k -> k.startsWith("unread_")));
    }

    /** A gap wider than the 64-block window: the bookmark moves to the tip and the blocks it did not read are noted. */
    @Test public void aGapOverTheWindowIsNotedNotClaimed() {
        Map<String, String> m = new HashMap<>();
        m.put("backfilled_4D494E49", "true"); m.put("scanned_tip_4D494E49", "100");
        new CommsScanner(node(64, new ArrayList<>(), 300), null, CommsScannerBookmarkTest.store(m), CommsScannerBookmarkTest.ADDR, (id, o, c) -> false, (k, n) -> {}).scan(300);
        assertEquals("300", m.get("scanned_tip_4D494E49"));
        assertEquals("blocks 101 to 235 were never read (the pass read 236 to 300), and 236 is named too", "236", m.get("unread_4D494E49:101"));
    }

    /** The payment scanner follows the orders read: only what its own bookmark moves past is noted. */
    @Test public void aFollowerNotesOnlyWhatItsBookmarkPasses() {
        Map<String, String> m = new HashMap<>();
        String pay = "0x" + "12".repeat(32);
        m.put("backfilled_pay_12121212", "true"); m.put("scanned_tip_pay_12121212", "100"); m.put("scanned_tip_4D494E49", "150");
        CommsScanner s = new CommsScanner(node(64, new ArrayList<>(), 300), null, CommsScannerBookmarkTest.store(m), pay, (id, o, c) -> false, (k, n) -> {}, false);
        s.follows("scanned_tip_4D494E49");
        s.scan(300);
        assertEquals("150", m.get("scanned_tip_pay_12121212"));
        assertEquals("150", m.get("unread_pay_12121212:101"));
    }
}
