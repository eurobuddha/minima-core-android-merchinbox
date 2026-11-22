package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import org.junit.Test;
import org.robolectric.shadows.ShadowLooper;

/** 2026-11-22 (R4): blocks a scan moved past unread are said on the Inbox, in red, with both blocks; a tap dismisses. */
public class UnreadRangeTest extends DrainTest {
    @Test public void theInboxSaysWhichBlocksWereNotRead() throws Exception {
        db.setMeta(MainActivity.ORDERS_TIP.replace("scanned_tip_", "unread_") + ":101", "235");
        db.setMeta("unread_pay_12121212:90", "120");
        android.view.View list = (android.view.View) call("buildOrders", new Class<?>[]{});
        ShadowLooper.idleMainLooper();
        String all = String.join("\n", InboxShowsIdentifiersWholeTest.texts(list));
        assertTrue(all, all.contains("Orders between block 101 and block 235 could not be read"));
        assertTrue(all, all.contains("Payments between block 90 and block 120 could not be read"));
    }
}
