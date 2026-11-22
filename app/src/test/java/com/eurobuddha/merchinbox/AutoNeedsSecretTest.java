package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import com.eurobuddha.comms.MerchDb;
import com.eurobuddha.comms.MerchMessage;

import org.junit.Test;
import org.robolectric.shadows.ShadowLooper;

/** 2026-11-22 (P1): an order with no secret (an older Shop) is never sent automatically, whatever the timing of a
 *  copier's order: it waits for the vendor's own Send, which keeps every rule; the order screen says why. */
public class AutoNeedsSecretTest extends DrainTest {

    @Test public void onlyAnOrderWithASecretIsAllowedAutomatically() {
        assertFalse(DeliveryRules.autoAllowed(""));
        assertFalse(DeliveryRules.autoAllowed(null));
        assertTrue(DeliveryRules.autoAllowed(MerchMessage.newNonce()));
    }

    @Test public void aNoSecretOrderPaidInFullIsNotSentAutomaticallyButTheVendorCanSendIt() throws Exception {
        db.setMeta("autosend", "1");
        MerchDb.Order o = order("ORD-OLDSHOP", "Piece", NFT, 1, "10");
        pay("ORD-OLDSHOP", "10", tip - 5, "0xB1");
        assertTrue("paid in full and checked", db.deliverable(db.order("ORD-OLDSHOP")));
        call("maybeAutoDeliver", new Class<?>[]{String.class}, "ORD-OLDSHOP");   // the payment's own path
        ShadowLooper.idleMainLooper();
        call("autoDeliverSweep", new Class<?>[]{});                              // the per-block sweep
        ShadowLooper.idleMainLooper();
        assertTrue("never automatic: " + sends, sends.isEmpty());
        String all = String.join("\n", InboxShowsIdentifiersWholeTest.texts((android.view.View) call("buildOrderDetail", new Class<?>[]{String.class}, "ORD-OLDSHOP")));
        assertTrue(all, all.contains("is matched to its payment by its reference only") && all.contains("send by hand"));
        send(o, false);
        assertEquals("the vendor's own Send still works", 1, sends.size());
    }

    @Test public void anOrderWithASecretIsStillSentAutomatically() throws Exception {
        db.setMeta("autosend", "1");
        String n = MerchMessage.newNonce();
        order("ORD-NEWSHOP", "Piece", NFT, 1, "10", n);
        pay("ORD-NEWSHOP", "10", tip - 5, "0xB2", n);
        call("autoDeliverSweep", new Class<?>[]{});
        ShadowLooper.idleMainLooper();
        assertEquals(1, sends.size());
        String all = String.join("\n", InboxShowsIdentifiersWholeTest.texts((android.view.View) call("buildOrderDetail", new Class<?>[]{String.class}, "ORD-NEWSHOP")));
        assertFalse(all, all.contains("is matched to its payment by its reference only"));
    }
}
