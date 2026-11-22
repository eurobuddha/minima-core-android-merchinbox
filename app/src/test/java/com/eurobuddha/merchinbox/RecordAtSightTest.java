package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import com.eurobuddha.comms.CommsScanner;
import com.eurobuddha.comms.MerchDb;
import com.eurobuddha.comms.MerchMessage;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.Opened;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;

/** 2026-11-22 (R1): a payment coin is judged the first time it is seen, at any tip and whatever the orders read says
 *  (as 2.2.74): it is never left unrecorded to be careful, since the wallet may spend it or it may leave the window.
 *  Care is taken at the moment of sending: an order with no secret is never sent automatically (P1), and a second
 *  order with the reference flags it, which blocks every send. */
public class RecordAtSightTest extends DrainTest {
    static final String STRANGER = "0x" + "66".repeat(32);
    long nodeTip = 0;   // the node's own tip; a scanner page lists coins created at nodeTip - depth or later

    @Override NodeApi fakeNode() {
        NodeApi n = super.fakeNode();
        NodeApi spy = org.mockito.Mockito.mock(NodeApi.class);
        org.mockito.Mockito.doAnswer(i -> {
            String c = i.getArgument(0); NodeApi.Cb cb = i.getArgument(1);
            java.util.regex.Matcher d = java.util.regex.Pattern.compile("coins address:\\S+ order:desc depth:([0-9]+)").matcher(c);
            if (d.matches() && nodeTip > 0) {
                long depth = Long.parseLong(d.group(1));
                JSONArray a = new JSONArray();
                for (JSONObject p : payments) if (p.optLong("created") >= nodeTip - depth) a.put(p);
                cb.onResult(new JSONObject().put("status", true).put("response", a));
                return null;
            }
            if (c.contains("address:" + STRANGER) && !c.startsWith("coins") && !c.endsWith(" dryrun:true")) sends.add(c);   // a send to the copier
            n.cmd(c, cb);
            return null;
        }).when(spy).cmd(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        return spy;
    }

    JSONObject coin(String ref, String id, long created, String port2) throws Exception {
        JSONArray st = new JSONArray().put(new JSONObject().put("port", 1).put("data", "0x" + hex(ref)));
        if (port2 != null) st.put(new JSONObject().put("port", 2).put("data", port2));
        JSONObject c = new JSONObject().put("coinid", id).put("tokenid", "0x00").put("amount", "10").put("tokenamount", "10").put("created", String.valueOf(created)).put("state", st);
        payments.add(c);
        return c;
    }
    /** The chain tip and the orders read, as a pass sees them. */
    void at(long chain, long orders) throws Exception { tip = chain; set("chainBlock", (int) chain); db.setMeta(MainActivity.ORDERS_TIP, String.valueOf(orders)); }
    boolean route(JSONObject c) throws Exception {
        return (Boolean) call("routePayment", new Class<?>[]{String.class, Opened.class, JSONObject.class}, c.getString("coinid"), null, c);
    }
    void routeOrder(String ref, String from, String payaddr, String coinid, long created, String nonce) throws Exception {
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = ref; m.from = from; m.to = ME; m.currency = "Minima"; m.shopId = "art"; m.shipping = "digital";
        m.buyerPayaddr = payaddr; m.amount = "10"; m.nonce = nonce; m.date = System.currentTimeMillis();
        m.items = new JSONArray().put(new JSONObject().put("product", "Piece").put("quantity", 1).put("unitPrice", "10").put("nftTokenId", NFT)).toString();
        call("routeIncoming", new Class<?>[]{String.class, Opened.class, JSONObject.class}, coinid, new Opened(true, from, m.toWire()),
                new JSONObject().put("coinid", coinid).put("created", created));
    }
    void autoAttempt(String ref) throws Exception {
        call("maybeAutoDeliver", new Class<?>[]{String.class}, ref);
        call("autoDeliverSweep", new Class<?>[]{});
        ShadowLooper.idleMainLooper();
    }

    @Test public void aCoinIsJudgedTheFirstTimeItIsSeen() throws Exception {
        order("ORD-S1", "Piece", NFT, 1, "10");
        at(90, 85);   // its own block, the orders read behind
        route(coin("ORD-S1", "0xF1", 90, null));
        assertTrue("recorded at sight", db.order("ORD-S1").paid);
        assertEquals("ORD-S1", db.getMeta("paycoin:0xf1", ""));
    }

    /** The reviewer's sequence, no secret anywhere: payment at N, the copier's order at N + 1, the real order at N + 2.
     *  The copier's order may be paid at N + 1; it is never sent automatically; from N + 2 the flag blocks every send. */
    @Test public void aCopiersOrderIsNeverSentAndTheRealOneFlagsTheReference() throws Exception {
        String ref = "ORD-00000000000000C1";
        long n = 90;
        JSONObject c = coin(ref, "0xF3", n, null);
        db.setMeta("autosend", "1");
        for (long t = n; t <= n + 4; t++) {
            at(t, t);
            if (t == n + 1) routeOrder(ref, "0xCOPIER", STRANGER, "0xC31", n + 1, "");
            if (t == n + 2) routeOrder(ref, "0xREAL", BUYER, "0xC32", n + 2, "");
            route(c);
            ShadowLooper.idleMainLooper();
            MerchDb.Order o = db.order(ref);
            if (t == n + 1) assertTrue("recorded at sight for the order stored", o.paid);
            if (o != null) autoAttempt(ref);
            assertTrue("sent automatically at block " + t + ": " + sends, sends.isEmpty());
            if (t >= n + 2) assertTrue(db.conflict(o));
        }
        MerchDb.Order o = db.order(ref);
        assertEquals("the copier's order stays the order of record", STRANGER, o.payaddr);
        assertFalse(db.deliverable(o));
        send(o, false);
        assertTrue("the vendor's Send is refused while the reference is flagged: " + sends, sends.isEmpty());
    }

    /** The same sequence when the real order has a secret: its payment commits to it, so the copier's order (no secret)
     *  is never paid; once the real order is stored beside it, the vendor can make it the order of record. */
    @Test public void withASecretTheCopiersOrderIsNeverPaid() throws Exception {
        String ref = "ORD-00000000000000C2";
        String secret = MerchMessage.newNonce();
        long n = 90;
        JSONObject c = coin(ref, "0xF4", n, MerchMessage.commitment(secret));
        db.setMeta("autosend", "1");
        for (long t = n; t <= n + 4; t++) {
            at(t, t);
            if (t == n + 1) routeOrder(ref, "0xCOPIER", STRANGER, "0xC41", n + 1, "");
            if (t == n + 2) routeOrder(ref, "0xREAL", BUYER, "0xC42", n + 2, secret);
            route(c);
            MerchDb.Order o = db.order(ref);
            if (o != null) { assertFalse("the copier's order paid at block " + t, o.paid); autoAttempt(ref); }
            assertTrue(sends.isEmpty());
        }
        MerchDb.Order o = db.order(ref);
        assertEquals(STRANGER, o.payaddr);
        assertTrue(db.conflict(o));
        assertTrue("the real order is the one its payment commits to", db.committedContender(o) >= 0);
    }

    /** A coin seen before its order is stored is not recorded; the payment scan reads it again (its gap plus the margin)
     *  in the pass that stores an order mined two blocks later, and counts it then. */
    @Test public void aCoinSeenBeforeItsOrderIsReadAgainAndCounted() throws Exception {
        String ref = "ORD-00000000000000C3";
        long created = 90;
        coin(ref, "0xF5", created, null);
        db.setMeta("backfilled_pay_12121212", "true");
        db.setMeta("scanned_tip_pay_12121212", String.valueOf(created - 1));
        call("startPaymentScanner", new Class<?>[]{});
        Field ps = MainActivity.class.getDeclaredField("paymentScanner"); ps.setAccessible(true);
        for (long t = created; t <= created + 2; t++) {
            at(t, t);
            nodeTip = t + 1;
            if (t == created + 2) routeOrder(ref, "0xREAL", BUYER, "0xC51", created + 2, "");
            ((CommsScanner) ps.get(activity)).scan((int) t);
            ShadowLooper.idleMainLooper();
            MerchDb.Order o = db.order(ref);
            if (o != null) assertTrue("pass at " + t, o.paid);
        }
        assertTrue(db.order(ref).paid);
    }
}
