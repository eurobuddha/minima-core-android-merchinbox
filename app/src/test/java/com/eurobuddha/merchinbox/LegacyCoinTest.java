package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;
import com.eurobuddha.comms.MerchDb;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.robolectric.shadows.ShadowLooper;

/** The reviewer's probe of payments recorded by older Inbox versions (rev5 LegacyCoinReviewTest), kept as regression
 *  tests of the fixes: each failed on 0.5.39. */
public class LegacyCoinTest extends DrainTest {
    JSONObject coin(String ref, String id) throws Exception {
        return new JSONObject().put("coinid", id).put("tokenid", "0x00").put("amount", "10").put("tokenamount", "10").put("created", String.valueOf(tip - 5))
                .put("state", new JSONArray().put(new JSONObject().put("port", 1).put("data", "0x" + hex(ref))));
    }

    /** M2 b: a coin known only by its marker (counted before 0.5.30) keeps its amount once confirmed: it counts, even
     *  after the vendor spends it. */
    @Test public void aLegacyCoinStillCountsAfterItWasConfirmedAndSpent() throws Exception {
        MerchDb.Order o = order("ORD-LEG", "Piece", NFT, 1, "10");
        payments.add(coin("ORD-LEG", "0xC1"));
        db.recordPayment("ORD-LEG", "0xC1", "10", "0x00", null, tip - 5);
        db.getWritableDatabase().delete("meta", "k=? OR k=?", new String[]{"paidcoins:ORD-LEG", "paidconf:0xc1"});   // as an older Inbox left it
        assertEquals("ORD-LEG", db.getMeta("paycoin:0xc1", ""));
        call("confirmSweep", new Class<?>[]{long.class}, tip);
        ShadowLooper.idleMainLooper();
        assertTrue(db.getMeta("paidconf:0xc1", ""), db.getMeta("paidconf:0xc1", "").contains("\"amount\":\"10\""));
        spent.add("0xc1");
        send(o, false);
        assertEquals("the legacy coin counts", 1, sends.size());
    }

    /** M2 a: an order paid under Inbox 0.5.7 (no coin list, no marker) waits, plainly, until the vendor finds its
     *  payment; then it is delivered. */
    @Test public void aPaymentFromInbox057IsFoundByTheVendorAndThenDelivered() throws Exception {
        MerchDb.Order o = order("ORD-OLD", "Piece", NFT, 1, "10");
        payments.add(coin("ORD-OLD", "0xC2"));
        db.recordPayment("ORD-OLD", "0xC2", "10", "0x00", null, tip - 5);
        db.getWritableDatabase().delete("meta", "k=? OR k=? OR k=?", new String[]{"paidcoins:ORD-OLD", "paycoin:0xc2", "paidconf:0xc2"});   // as Inbox 0.5.7 left it
        assertTrue((Boolean) call("legacyPaid", new Class<?>[]{MerchDb.Order.class}, db.order("ORD-OLD")));
        send(o, false);
        assertTrue("not before it is found", sends.isEmpty());
        set("chainBlock", (int) tip);
        call("findPayment", new Class<?>[]{String.class}, "ORD-OLD");
        ShadowLooper.idleMainLooper();
        assertEquals("found", db.getMeta("findpayment:ORD-OLD", ""));
        for (String c : coinReads) assertFalse("never the whole address", c.startsWith("coins relevant:true address:"));
        assertTrue(coinReads.toString(), coinReads.stream().anyMatch(c -> c.matches("coins relevant:false address:" + VENDOR + " tokenid:0x00 depth:[0-9]+ coinage:[0-9]+")));
        send(db.order("ORD-OLD"), false);
        assertEquals("a 0.5.7 paid order is deliverable once found", 1, sends.size());
        call("findPayment", new Class<?>[]{String.class}, "ORD-OLD");   // a second find counts nothing twice
        ShadowLooper.idleMainLooper();
        assertEquals(1, new JSONArray(db.getMeta("paidcoins:ORD-OLD", "[]")).length());
    }

    /** M2 a: nothing found says so plainly (spent before it was confirmed here, or older than the node keeps). */
    @Test public void aPaymentThatCannotBeFoundIsSaidPlainly() throws Exception {
        order("ORD-GONE", "Piece", NFT, 1, "10");
        db.recordPayment("ORD-GONE", "0xC3", "10", "0x00", null, tip - 5);
        db.getWritableDatabase().delete("meta", "k=? OR k=? OR k=?", new String[]{"paidcoins:ORD-GONE", "paycoin:0xc3", "paidconf:0xc3"});
        set("chainBlock", (int) tip);
        call("findPayment", new Class<?>[]{String.class}, "ORD-GONE");
        ShadowLooper.idleMainLooper();
        assertEquals("none", db.getMeta("findpayment:ORD-GONE", ""));
        String all = String.join("\n", InboxShowsIdentifiersWholeTest.texts((android.view.View) call("buildOrderDetail", new Class<?>[]{String.class}, "ORD-GONE")));
        assertTrue(all, all.contains("may have been spent before it was confirmed here") && all.contains("Check your wallet history"));
    }

    /** M2 c and the back-off: a coin read by the scanner at the minimum confirmations is confirmed when recorded; a coin
     *  read back and not found is not read again at every block. */
    @Test public void aDeepScannedCoinIsConfirmedAtOnceAndAGoneCoinBacksOff() throws Exception {
        order("ORD-DEEP", "Piece", NFT, 1, "10");
        set("chainBlock", (int) tip);
        JSONObject c = coin("ORD-DEEP", "0xC4");
        call("routePayment", new Class<?>[]{String.class, com.eurobuddha.comms.Opened.class, JSONObject.class}, "0xC4", null, c);
        assertTrue(db.getMeta("paidconf:0xc4", "").contains("\"amount\":\"10\""));
        order("ORD-GONE2", "Art", ART, 1, "1");
        db.recordPayment("ORD-GONE2", "0xC5", "1", "0x00", null, 0);   // never on chain at the address
        call("confirmSweep", new Class<?>[]{long.class}, tip);
        ShadowLooper.idleMainLooper();
        int reads = coinReads.size();
        call("confirmSweep", new Class<?>[]{long.class}, tip + 1);
        ShadowLooper.idleMainLooper();
        assertEquals("not read again at the next block", reads, coinReads.size());
    }
}
