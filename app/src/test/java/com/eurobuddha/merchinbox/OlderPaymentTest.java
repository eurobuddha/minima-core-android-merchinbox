package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import com.eurobuddha.comms.MerchDb;
import com.eurobuddha.comms.MerchMessage;
import com.eurobuddha.comms.Opened;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** 2026-11-22 (P3): an order with no secret is never paid by a coin mined more than 3 blocks before the order (it may be
 *  a copy made from that payment's public reference); the coin is shown to the vendor with one note per order. The
 *  order's block is stored with the order, in the same transaction. */
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

    @Test public void olderMeansMoreThanThreeBlocksBeforeTheOrderBothKnown() {
        assertTrue(MerchDb.olderThanOrder(86, 90));
        assertFalse(MerchDb.olderThanOrder(87, 90));
        assertFalse(MerchDb.olderThanOrder(0, 90));
        assertFalse(MerchDb.olderThanOrder(86, 0));
    }

    @Test public void aNoSecretOrderIsNotPaidByACoinOlderThanItAndTheVendorSeesIt() throws Exception {
        routeOrder(msg(REF, ""), 90);
        assertEquals("stored with the order", "90", db.getMeta("orderblock:" + REF, ""));
        assertNull(db.recordPayment(REF, C1, "10", "0x00", null, 80));
        assertFalse(db.order(REF).paid);
        assertNull(db.recordPayment(REF, C2, "10", "0x00", null, 81));
        assertNull("judged again, nothing changes", db.recordPayment(REF, C1, "10", "0x00", null, 80));
        assertEquals(2, db.unassignedCoins(REF).length());
        JSONObject note = db.olderPayment(REF);
        assertEquals("one note per order: the first coin", C1, note.getString("coinid"));
        String all = String.join("\n", InboxShowsIdentifiersWholeTest.texts((android.view.View) call("buildOrderDetail", new Class<?>[]{String.class}, REF)));
        assertTrue(all, all.contains("older than the order (payment coin " + C1 + " at block 80, the order at block 90): check by hand"));
        assertEquals("a coin within 3 blocks before the order pays it", MerchDb.PAID, db.recordPayment(REF, C3, "10", "0x00", null, 87));
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
