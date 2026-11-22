package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import org.junit.Test;
import org.robolectric.shadows.ShadowLooper;

/** 2026-11-22 (R4): blocks a scan did not read are said on the Inbox, in red, with both blocks and why (r3 I3: the
 *  64-block window, or reads the node refused); orders and payments of one range are one line; a tap deletes it. */
public class UnreadRangeTest extends DrainTest {
    String orders() throws Exception {
        android.view.View list = (android.view.View) call("buildOrders", new Class<?>[]{});
        ShadowLooper.idleMainLooper();
        return String.join("\n", InboxShowsIdentifiersWholeTest.texts(list));
    }

    @Test public void theInboxSaysWhichBlocksWereNotRead() throws Exception {
        db.setMeta(MainActivity.ORDERS_TIP.replace("scanned_tip_", "unread_") + ":101", "235");
        db.setMeta("unread_pay_12121212:90", "120 refused");
        String all = orders();
        assertTrue(all, all.contains("Orders between block 101 and block 235 were not read: the Inbox reads at most 64 blocks back. An order sent then may be missing here."));
        assertTrue(all, all.contains("Payments between block 90 and block 120 could not be read: the node refused the reads. A payment made then may not be counted."));
    }

    @Test public void ordersAndPaymentsOfOneRangeAreOneLineAndATapDeletesBoth() throws Exception {
        String orders = MainActivity.ORDERS_TIP.replace("scanned_tip_", "unread_") + ":101";
        db.setMeta(orders, "235");
        db.setMeta("unread_pay_12121212:101", "235");
        android.view.View list = (android.view.View) call("buildOrders", new Class<?>[]{});
        ShadowLooper.idleMainLooper();
        String all = String.join("\n", InboxShowsIdentifiersWholeTest.texts(list));
        assertTrue(all, all.contains("Orders and payments between block 101 and block 235 were not read: the Inbox reads at most 64 blocks back. An order or payment sent then may be missing here."));
        assertEquals(all, all.indexOf("between block 101"), all.lastIndexOf("between block 101"));
        set("container", new android.widget.FrameLayout(activity));
        call("showOrders", new Class<?>[]{});
        OldShopSendTest.find(list, "Orders and payments between block 101").performClick();
        ShadowLooper.idleMainLooper();
        try (android.database.Cursor q = db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM meta WHERE k LIKE 'unread_%'", null)) {
            q.moveToFirst();
            assertEquals("the dismissed rows are deleted, not blanked", 0, q.getInt(0));
        }
    }
}
