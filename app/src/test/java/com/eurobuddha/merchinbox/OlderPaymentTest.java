package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import com.eurobuddha.comms.MerchDb;
import com.eurobuddha.comms.MerchMessage;
import com.eurobuddha.comms.Opened;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 2026-11-22 (R2, was P3): a coin mined more than 3 blocks before an order with no secret is counted for it (as 2.2.74:
 *  the order can be paid and the vendor's Send is there), and the order keeps one note with the first such coin, shown
 *  in red: a copy of an order can be made from a payment's reference, so the vendor confirms with the buyer. The order's
 *  block is stored with the order, in the same transaction. */
public class OlderPaymentTest extends DrainTest {
    static final String REF = "ORD-00000000000000D1";
    static final String C1 = "0x" + "A1".repeat(32), C2 = "0x" + "A2".repeat(32), C3 = "0x" + "A3".repeat(32);

    MerchMessage msg(String ref, String nonce) throws Exception {
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = ref; m.from = "0xBUYER"; m.to = ME; m.currency = "Minima"; m.shopId = "art"; m.shipping = "digital";
        m.buyerPayaddr = BUYER; m.amount = "10"; m.nonce = nonce; m.date = System.currentTimeMillis();
        m.items = new JSONArray().put(new JSONObject().put("product", "Piece").put("quantity", 1).put("unitPrice", "10").put("nftTokenId", NFT)).toString();
        return m;
    }
    void routeOrder(MerchMessage m, long created) throws Exception {
        call("routeIncoming", new Class<?>[]{String.class, Opened.class, JSONObject.class}, "0xC0D1", new Opened(true, m.from, m.toWire()),
                new JSONObject().put("coinid", "0xC0D1").put("created", created));
    }
    JSONObject coin(String ref, String id, String amount, long created) throws Exception {
        JSONObject c = new JSONObject().put("coinid", id).put("tokenid", "0x00").put("amount", amount).put("tokenamount", amount).put("created", String.valueOf(created))
                .put("state", new JSONArray().put(new JSONObject().put("port", 1).put("data", "0x" + hex(ref))));
        payments.add(c);
        return c;
    }

    @Test public void olderMeansMoreThanThreeBlocksBeforeTheOrderBothKnown() {
        assertTrue(MerchDb.olderThanOrder(86, 90));
        assertFalse(MerchDb.olderThanOrder(87, 90));
        assertFalse(MerchDb.olderThanOrder(0, 90));
        assertFalse(MerchDb.olderThanOrder(86, 0));
    }

    @Test public void anOlderCoinIsCountedAndTheOrderKeepsOneNote() throws Exception {
        routeOrder(msg(REF, ""), 90);
        assertEquals("stored with the order", "90", db.getMeta("orderblock:" + REF, ""));
        assertEquals("counted", MerchDb.UNDERPAID, db.recordPayment(REF, C1, "4", "0x00", null, 80));
        assertEquals(MerchDb.PAID, db.recordPayment(REF, C2, "6", "0x00", null, 81));
        assertTrue(db.order(REF).paid);
        assertEquals("counted for the order, not kept aside", 0, db.unassignedCoins(REF).length());
        JSONObject note = db.olderPayment(REF);
        assertEquals("one note per order: the first coin", C1, note.getString("coinid"));
        String all = String.join("\n", InboxShowsIdentifiersWholeTest.texts((android.view.View) call("buildOrderDetail", new Class<?>[]{String.class}, REF)));
        assertTrue(all, all.contains("older than the order (payment coin " + C1 + " at block 80, the order at block 90). A copy of an order can be made from a payment's reference: confirm with the buyer before sending."));
        assertFalse(all, all.contains("not counted for this order"));
    }

    /** A real buyer of an older Shop whose order coin was mined 4 blocks after its payment: paid, and the vendor's own
     *  Send delivers it (2.2.85 kept the coin aside and refused every send); never automatically. */
    @Test public void aLateMinedRealOrderIsPaidAndTheVendorCanSendIt() throws Exception {
        db.setMeta("autosend", "1");
        set("chainBlock", (int) tip);
        routeOrder(msg(REF, ""), tip - 10);
        JSONObject c = coin(REF, "0xE9", "10", tip - 14);
        assertTrue(db.order(REF) != null);
        call("routePayment", new Class<?>[]{String.class, Opened.class, JSONObject.class}, "0xE9", null, c);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        MerchDb.Order o = db.order(REF);
        assertTrue(o.paid);
        assertNotNull(db.olderPayment(REF));
        assertTrue(db.deliverable(o));
        call("autoDeliverSweep", new Class<?>[]{}); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertTrue("never automatic", sends.isEmpty());
        send(o, false);
        assertEquals("the vendor's Send delivers it", 1, sends.size());
    }

    @Test public void anOrderWithASecretOrWithNoBlockIsJudgedAsBefore() throws Exception {
        String n = MerchMessage.newNonce();
        routeOrder(msg(REF, n), 90);
        assertEquals(MerchDb.PAID, db.recordPayment(REF, C1, "10", "0x00", MerchMessage.commitment(n), 80));
        db.upsertOrder(msg("ORD-00000000000000D2", ""), "seller", "0xC0D2");   // an order stored by an older version: no block
        call("recheck", new Class<?>[]{MerchDb.Order.class}, db.order("ORD-00000000000000D2"));
        assertEquals(MerchDb.PAID, db.recordPayment("ORD-00000000000000D2", C2, "10", "0x00", null, 80));
        assertNull(db.olderPayment("ORD-00000000000000D2"));
    }
}
