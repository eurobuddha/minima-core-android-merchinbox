package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import org.junit.Test;
import org.robolectric.shadows.ShadowLooper;

/** 2026-11-22 (r3 I4): an orders scan whose every pass is refused is not silent: one red line once 5 passes in a row
 *  ended partly read, or one did and the tip is more than 64 blocks past the orders bookmark; the next whole pass
 *  clears it. */
public class StalledOrdersTest extends DrainTest {
    static final String FAILS = "failedpasses_4D494E49";   // CommsScanner.failsKey of the orders scan
    static final String LINE = "Orders have not been read since block 100: the node refused the reads.";

    String shown() throws Exception {
        java.lang.reflect.Field c = MainActivity.class.getDeclaredField("container"); c.setAccessible(true);
        return String.join("\n", InboxShowsIdentifiersWholeTest.texts((android.view.View) c.get(activity)));
    }

    @Test public void aStalledOrdersScanSaysSoUntilAWholePass() throws Exception {
        set("container", new android.widget.FrameLayout(activity));
        set("chainBlock", 120);
        db.setMeta(FAILS, "4");
        call("showOrders", new Class<?>[]{}); ShadowLooper.idleMainLooper();
        assertFalse("4 failed passes and the tip 20 past: not yet", shown().contains(LINE));
        db.setMeta(FAILS, "5");
        call("onScanDone", new Class<?>[]{int.class}, 0); ShadowLooper.idleMainLooper();
        assertTrue("5 in a row: " + shown(), shown().contains(LINE));
        db.setMeta(FAILS, "0");
        call("onScanDone", new Class<?>[]{int.class}, 0); ShadowLooper.idleMainLooper();
        assertFalse("a whole pass clears it", shown().contains(LINE));
        db.setMeta(FAILS, "1"); set("chainBlock", 165);
        call("onScanDone", new Class<?>[]{int.class}, 0); ShadowLooper.idleMainLooper();
        assertTrue("one failed pass and the tip 65 past the bookmark: " + shown(), shown().contains(LINE));
    }
}
