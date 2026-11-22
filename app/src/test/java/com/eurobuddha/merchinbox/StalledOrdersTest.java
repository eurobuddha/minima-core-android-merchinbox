package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import com.eurobuddha.comms.MerchDb;
import org.junit.Test;
import org.robolectric.shadows.ShadowLooper;

/** 2026-11-22 (r3 I4): an orders scan whose every pass is refused is not silent: one red line once 5 passes in a row
 *  ended partly read, or one did and the tip is more than 64 blocks past the orders bookmark; the next whole pass
 *  clears it. */
public class StalledOrdersTest extends DrainTest {
    static final String FAILS = "failedpasses_4D494E49";   // CommsScanner.failsKey of the orders scan
    static final String REFUSED = "refusedblocks_4D494E49";   // CommsScanner.refusedKey of the orders scan
    static final String LINE = "Orders have not been read since block 101: the node refused the reads.";

    String shown() throws Exception {
        java.lang.reflect.Field c = MainActivity.class.getDeclaredField("container"); c.setAccessible(true);
        return String.join("\n", InboxShowsIdentifiersWholeTest.texts((android.view.View) c.get(activity)));
    }

    @Test public void aStalledOrdersScanSaysSoUntilAWholePass() throws Exception {
        set("container", new android.widget.FrameLayout(activity));
        set("chainBlock", 120);
        db.setMeta(FAILS, "4"); db.setMeta(REFUSED, "101 120");
        call("showOrders", new Class<?>[]{}); ShadowLooper.idleMainLooper();
        assertFalse("4 failed passes and the tip 20 past: not yet", shown().contains(LINE));
        db.setMeta(FAILS, "5");
        call("onScanDone", new Class<?>[]{int.class}, 0); ShadowLooper.idleMainLooper();
        assertTrue("5 in a row: " + shown(), shown().contains(LINE));
        db.setMeta(FAILS, "0"); db.setMeta(REFUSED, "");
        call("onScanDone", new Class<?>[]{int.class}, 0); ShadowLooper.idleMainLooper();
        assertFalse("a whole pass clears it", shown().contains(LINE));
        db.setMeta(FAILS, "1"); db.setMeta(REFUSED, "101 160"); set("chainBlock", 165);
        call("onScanDone", new Class<?>[]{int.class}, 0); ShadowLooper.idleMainLooper();
        assertTrue("one failed pass and the tip 65 past the bookmark: " + shown(), shown().contains(LINE));
    }

    /** Final review F1 c: the line names the lowest block the refused passes could not read (not the bookmark), and with
     *  no refused blocks (every page was read, the tip only moved) there is no line. */
    @Test public void theLineNamesTheFirstBlockNotReadAndNeedsRefusedBlocks() throws Exception {
        set("container", new android.widget.FrameLayout(activity));
        set("chainBlock", 120);
        db.setMeta(FAILS, "5");
        call("showOrders", new Class<?>[]{}); ShadowLooper.idleMainLooper();
        assertFalse("no refused blocks: " + shown(), shown().contains("Orders have not been read since block"));
        db.setMeta(REFUSED, "107 120");
        call("showOrders", new Class<?>[]{}); ShadowLooper.idleMainLooper();
        assertTrue(shown(), shown().contains("Orders have not been read since block 107: the node refused the reads."));
    }

    /** Final review F3: while only the stalled line changes, an open order screen is not rebuilt (its scroll is kept);
     *  the list is drawn again once it is on top. */
    @Test public void theStalledLineAloneDoesNotRebuildAnOpenOrder() throws Exception {
        set("container", new android.widget.FrameLayout(activity));
        set("chainBlock", 120);
        call("showOrders", new Class<?>[]{}); ShadowLooper.idleMainLooper();
        MerchDb.Order o = order("ORD-F3", "Piece", NFT, 1, "10");
        android.view.View screen = (android.view.View) call("buildOrderDetail", new Class<?>[]{String.class}, o.ref);
        call("push", new Class<?>[]{android.view.View.class}, screen);
        db.setMeta(FAILS, "5"); db.setMeta(REFUSED, "101 120");
        call("onScanDone", new Class<?>[]{int.class}, 0); ShadowLooper.idleMainLooper();
        java.lang.reflect.Field st = MainActivity.class.getDeclaredField("stack"); st.setAccessible(true);
        assertSame("the order screen is the same view", screen, ((java.util.ArrayDeque<?>) st.get(activity)).peek());
        call("pop", new Class<?>[]{});
        call("onScanDone", new Class<?>[]{int.class}, 0); ShadowLooper.idleMainLooper();
        assertTrue("back on the list, the line comes: " + shown(), shown().contains("Orders have not been read since block 101"));
    }
}
