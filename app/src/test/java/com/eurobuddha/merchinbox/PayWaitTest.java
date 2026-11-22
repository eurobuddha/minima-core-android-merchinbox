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

/** 2026-11-22 (P2): a payment coin is judged only once the chain tip and the orders read have reached its block + 3,
 *  and the payment scanner reads it again until then. A copier who lifts the reference off the payment and gets an
 *  order mined first meets the real order as a second order, flagged before anything is paid. */
public class PayWaitTest extends DrainTest {
    static final String STRANGER = "0x" + "66".repeat(32);
    long nodeTip = 0;   // the node's own tip; the scanner reads coins created at nodeTip - depth or later

    @Override NodeApi fakeNode() {
        NodeApi n = super.fakeNode();
        NodeApi spy = org.mockito.Mockito.mock(NodeApi.class);
        org.mockito.Mockito.doAnswer(i -> {
            String c = i.getArgument(0); NodeApi.Cb cb = i.getArgument(1);
            java.util.regex.Matcher d = java.util.regex.Pattern.compile("coins address:\\S+ order:desc depth:([0-9]+)").matcher(c);
            if (d.matches() && nodeTip > 0) {   // a scanner page: only the blocks it asks for
                long depth = Long.parseLong(d.group(1));
                JSONArray a = new JSONArray();
                for (JSONObject p : payments) if (p.optLong("created") >= nodeTip - depth) a.put(p);
                cb.onResult(new JSONObject().put("status", true).put("response", a));
                return null;
            }
            n.cmd(c, cb);
            return null;
        }).when(spy).cmd(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        return spy;
    }

    JSONObject coin(String ref, String id, long created) throws Exception {
        return new JSONObject().put("coinid", id).put("tokenid", "0x00").put("amount", "10").put("tokenamount", "10").put("created", String.valueOf(created))
                .put("state", new JSONArray().put(new JSONObject().put("port", 1).put("data", "0x" + hex(ref))));
    }
    /** The chain tip and the orders read, as a pass sees them. */
    void at(long chain, long orders) throws Exception { set("chainBlock", (int) chain); db.setMeta(MainActivity.ORDERS_TIP, String.valueOf(orders)); }
    boolean route(JSONObject c) throws Exception {
        return (Boolean) call("routePayment", new Class<?>[]{String.class, Opened.class, JSONObject.class}, c.getString("coinid"), null, c);
    }
    void routeOrder(String ref, String from, String payaddr, String coinid, long created) throws Exception {
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = ref; m.from = from; m.to = ME; m.currency = "Minima"; m.shopId = "art"; m.shipping = "digital";
        m.buyerPayaddr = payaddr; m.amount = "10"; m.date = System.currentTimeMillis();
        m.items = new JSONArray().put(new JSONObject().put("product", "Piece").put("quantity", 1).put("unitPrice", "10").put("nftTokenId", NFT)).toString();
        call("routeIncoming", new Class<?>[]{String.class, Opened.class, JSONObject.class}, coinid, new Opened(true, from, m.toWire()),
                new JSONObject().put("coinid", coinid).put("created", created));
    }

    @Test public void judgedAtItsBlockPlusThreeNotBefore() throws Exception {
        assertFalse(DeliveryRules.judgeable(90, 92, 92));
        assertTrue(DeliveryRules.judgeable(90, 93, 93));
        assertFalse("the orders read is behind", DeliveryRules.judgeable(90, 95, 92));
        assertTrue("a coin of unknown block, as before", DeliveryRules.judgeable(0, 0, 0));
        order("ORD-W1", "Piece", NFT, 1, "10");
        JSONObject c = coin("ORD-W1", "0xF1", 90);
        at(92, 92);
        route(c);
        assertFalse("created + 2: waits", db.order("ORD-W1").paid);
        assertEquals("not marked: judged again later", "", db.getMeta("paycoin:0xf1", ""));
        at(93, 93);
        route(c);
        assertTrue("created + 3: judged", db.order("ORD-W1").paid);
    }

    @Test public void aCoinWaitsWhileTheOrdersReadIsBehind() throws Exception {
        order("ORD-W2", "Piece", NFT, 1, "10");
        JSONObject c = coin("ORD-W2", "0xF2", 90);
        at(95, 92);
        route(c);
        assertFalse(db.order("ORD-W2").paid);
        at(95, 93);
        route(c);
        assertTrue(db.order("ORD-W2").paid);
    }

    /** The reviewer's sequence: payment at N, the copier's no-secret order at N + 1, the real no-secret order at N + 2.
     *  Each pass reads the orders first, then the payment again. Nothing is paid before the reference is flagged. */
    @Test public void theCopiersOrderIsFlaggedBeforeAnythingIsPaid() throws Exception {
        String ref = "ORD-00000000000000C1";
        long n = 90;
        JSONObject c = coin(ref, "0xF3", n);
        db.setMeta("autosend", "1");
        for (long t = n; t <= n + 4; t++) {
            at(t, t);
            if (t == n + 1) routeOrder(ref, "0xCOPIER", STRANGER, "0xC31", n + 1);
            if (t == n + 2) routeOrder(ref, "0xREAL", BUYER, "0xC32", n + 2);
            route(c);
            ShadowLooper.idleMainLooper();
            MerchDb.Order o = db.order(ref);
            if (o != null && o.paid) assertTrue("paid at block " + t + " before the second order flagged the reference", db.conflict(o));
            if (o != null) assertFalse("deliverable at block " + t, db.deliverable(o));
        }
        assertTrue(db.conflict(db.order(ref)));
        call("maybeAutoDeliver", new Class<?>[]{String.class}, ref);
        ShadowLooper.idleMainLooper();
        assertTrue(sends.isEmpty());
    }

    /** P2 d: the payment scanner reads a waiting coin again on every pass until it is judged, with the node one block
     *  past the tip the pass read. */
    @Test public void theScannerReadsAWaitingCoinAgainUntilItIsJudged() throws Exception {
        order("ORD-W3", "Piece", NFT, 1, "10");
        long created = 90;
        payments.add(coin("ORD-W3", "0xF4", created));
        db.setMeta("backfilled_pay_12121212", "true");
        db.setMeta("scanned_tip_pay_12121212", String.valueOf(created - 1));
        call("startPaymentScanner", new Class<?>[]{});
        Field ps = MainActivity.class.getDeclaredField("paymentScanner"); ps.setAccessible(true);
        for (long t = created; t <= created + 3; t++) {
            at(t, t);
            nodeTip = t + 1;
            ((CommsScanner) ps.get(activity)).scan((int) t);
            ShadowLooper.idleMainLooper();
            assertEquals("pass at " + t, t >= created + 3, db.order("ORD-W3").paid);
        }
    }
}
