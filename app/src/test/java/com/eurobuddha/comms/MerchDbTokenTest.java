package com.eurobuddha.comms;

import static org.junit.Assert.*;

import android.content.ContentValues;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Vendor side: the token an order is paid in follows from its currency; a stated token id that disagrees is
 *  flagged, and PAID is decided by the token actually received, never by the token the order names. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 33, manifest = Config.NONE)
public class MerchDbTokenTest {
    static final String MXUSD = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";
    MerchDb db;

    @Before public void open() { db = new MerchDb(RuntimeEnvironment.getApplication()); }
    @After public void close() { db.close(); }

    static MerchMessage order(String ref, String type, String currency, String tokenid) {
        MerchMessage m = new MerchMessage();
        m.type = type; m.ref = ref; m.from = "0xBUYER"; m.to = "0xVENDOR"; m.amount = "5"; m.currency = currency; m.tokenid = tokenid;
        return m;
    }

    @Test public void anOrderSayingMxUsdWithTheMinimaIdIsFlaggedAndNotPaidByMinima() {
        assertTrue(db.upsertOrder(order("ORD-A", MerchMessage.ORDER, "USDT", "0x00"), "seller", "0xC01"));
        MerchDb.Order o = db.order("ORD-A");
        assertEquals(MerchDb.TOKEN_MISMATCH, o.status);
        assertEquals(MXUSD, o.tokenid);
        assertEquals(MerchDb.WRONG_TOKEN, db.recordPayment("ORD-A", "0x01CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", "5", "0x00"));
        o = db.order("ORD-A");
        assertFalse("never marked paid in the stated token", o.paid);
        assertEquals(MerchDb.TOKEN_MISMATCH, o.status);
        assertEquals("0x00", o.paidTokenid);
        assertNull("a coin is counted once", db.recordPayment("ORD-A", "0x01CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", "5", "0x00"));
        assertEquals("a later honest payment still pays", MerchDb.PAID, db.recordPayment("ORD-A", "0xFEED", "5", MXUSD));
    }

    @Test public void aRowStoredBeforeTheFixIsJudgedByItsCurrency() {
        ContentValues v = new ContentValues();   // what older versions stored: the buyer's token id as sent
        v.put("ref", "ORD-OLD"); v.put("role", "seller"); v.put("amount", "5"); v.put("currency", "USDT");
        v.put("tokenid", "0x00"); v.put("status", MerchDb.PENDING); v.put("date", 1L);
        db.getWritableDatabase().insert("orders", null, v);
        assertEquals(MerchDb.WRONG_TOKEN, db.recordPayment("ORD-OLD", "0x03CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", "5", "0x00"));
        assertFalse(db.order("ORD-OLD").paid);
        assertEquals(MerchDb.WRONG_TOKEN, db.order("ORD-OLD").status);
    }

    @Test public void theCurrencysTokenInFullIsPaid() {
        db.upsertOrder(order("ORD-B", MerchMessage.ORDER, "USDT", MXUSD.toLowerCase()), "seller", "0xC02");
        assertEquals(MerchDb.PENDING, db.order("ORD-B").status);
        assertEquals(MerchDb.PAID, db.recordPayment("ORD-B", "0x04CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", "5", MXUSD));
        assertTrue(db.order("ORD-B").paid);

        db.upsertOrder(order("ORD-C", MerchMessage.ORDER, "Minima", ""), "seller", "0xC03");
        assertEquals("0x00", db.order("ORD-C").tokenid);
        assertEquals(MerchDb.UNDERPAID, db.recordPayment("ORD-C", "0x05CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", "4.99999999", "0x00"));
        assertFalse(db.order("ORD-C").paid);
    }

    @Test public void anUnpayableCurrencyIsFlaggedAndNeverPaid() {
        db.upsertOrder(order("ORD-D", MerchMessage.ORDER, "BTC", "0x00"), "seller", "0xC04");
        assertEquals(MerchDb.TOKEN_MISMATCH, db.order("ORD-D").status);
        assertEquals("", db.order("ORD-D").tokenid);
        assertEquals(MerchDb.WRONG_TOKEN, db.recordPayment("ORD-D", "0x06CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", "5", "0x00"));
        assertFalse(db.order("ORD-D").paid);
    }

    static JSONObject coin(String tokenid, String amount, String tokenamount) throws Exception {
        return new JSONObject().put("coinid", "0x" + "01".repeat(32)).put("tokenid", tokenid).put("amount", amount).put("tokenamount", tokenamount);
    }

    @Test public void aFullMxUsdTokenCoinIsPaidByItsTokenAmount() throws Exception {
        db.upsertOrder(order("ORD-F", MerchMessage.ORDER, "USDT", MXUSD), "seller", "0xC06");
        JSONObject c = coin(MXUSD, "0.000000000000000000000000000000000005", "5");   // amount = the Minima backing the token
        assertEquals("5", MerchDb.coinAmount(c));
        assertEquals(MerchDb.PAID, db.recordPayment("ORD-F", "0x07CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", MerchDb.coinAmount(c), MXUSD));
        assertTrue(db.order("ORD-F").paid);
    }

    @Test public void aShortMxUsdTokenCoinIsUnderpaid() throws Exception {
        db.upsertOrder(order("ORD-G", MerchMessage.ORDER, "USDT", MXUSD), "seller", "0xC07");
        JSONObject c = coin(MXUSD, "0.000000000000000000000000000000000005", "4.999999999999999999999999");
        assertEquals(MerchDb.UNDERPAID, db.recordPayment("ORD-G", "0x08CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", MerchDb.coinAmount(c), MXUSD));
        assertFalse(db.order("ORD-G").paid);
    }

    @Test public void minimaOfTheSameNumberDoesNotPayAnMxUsdOrder() throws Exception {
        db.upsertOrder(order("ORD-H", MerchMessage.ORDER, "USDT", MXUSD), "seller", "0xC08");
        JSONObject c = coin("0x00", "5", "5");
        assertEquals("5", MerchDb.coinAmount(c));
        assertEquals(MerchDb.WRONG_TOKEN, db.recordPayment("ORD-H", "0x09CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC", MerchDb.coinAmount(c), "0x00"));
        assertFalse(db.order("ORD-H").paid);
    }

    @Test public void anOrderTotalThatIsNotAboveZeroIsNeverPaid() throws Exception {
        for (String due : new String[]{"0", "-5", "0.000", "x"}) {
            MerchMessage m = order("ORD-Z" + due, MerchMessage.ORDER, "USDT", MXUSD); m.amount = due;
            db.upsertOrder(m, "seller", "0xC09");
            assertEquals(due, MerchDb.UNDERPAID, db.recordPayment("ORD-Z" + due, "0xZZ" + due, "5", MXUSD));
            assertFalse(db.order("ORD-Z" + due).paid);
        }
    }

    @Test public void aStrangersTinyCoinNeverBlocksTheHonestPayment() {
        db.upsertOrder(order("ORD-S", MerchMessage.ORDER, "USDT", MXUSD), "seller", "0xC10");
        assertEquals(MerchDb.WRONG_TOKEN, db.recordPayment("ORD-S", "0xA1", "0.000000001", "0x00"));
        assertEquals(MerchDb.UNDERPAID, db.recordPayment("ORD-S", "0xA2", "0.000000001", MXUSD));
        assertEquals(MerchDb.PAID, db.recordPayment("ORD-S", "0xA3", "5", MXUSD));
        assertTrue(db.order("ORD-S").paid);
        assertEquals(MerchDb.PAID, db.order("ORD-S").status);
        assertNull("PAID locks", db.recordPayment("ORD-S", "0xA4", "5", MXUSD));
    }

    @Test public void coinsOfTheRightTokenAddUpAndEachCountsOnce() {
        db.upsertOrder(order("ORD-P", MerchMessage.ORDER, "Minima", "0x00"), "seller", "0xC11");
        assertEquals(MerchDb.UNDERPAID, db.recordPayment("ORD-P", "0xB1", "2.5", "0x00"));
        assertNull(db.recordPayment("ORD-P", "0xb1", "2.5", "0x00"));   // the same coin again, any case
        assertEquals(MerchDb.UNDERPAID, db.order("ORD-P").status);
        assertEquals(MerchDb.WRONG_TOKEN, db.recordPayment("ORD-P", "0xB2", "9", MXUSD));
        assertEquals("a wrong coin does not undo a partial payment", MerchDb.UNDERPAID, db.order("ORD-P").status);
        assertEquals(MerchDb.PAID, db.recordPayment("ORD-P", "0xB3", "2.5", "0x00"));
        assertEquals("5.0", db.order("ORD-P").paidAmount);
        assertTrue(db.order("ORD-P").paid);
    }

    @Test public void anInquiryStaysAnInquiry() {
        db.upsertOrder(order("INQ-E", MerchMessage.INQUIRY, "Minima", MXUSD), "seller", "0xC05");
        assertEquals(MerchDb.INQUIRY, db.order("INQ-E").status);
        assertEquals("0x00", db.order("INQ-E").tokenid);
    }
}
