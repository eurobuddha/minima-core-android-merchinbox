package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import android.view.View;
import android.widget.TextView;

import com.eurobuddha.comms.MerchDb;
import com.eurobuddha.comms.MerchMessage;
import com.eurobuddha.comms.Opened;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

/** 2026-11-22 (r3 I5): an order with no secret and no NFT line (physical goods) shows the older-Shop facts too, and
 *  confirming or shipping it asks the vendor to accept the Send confirmation's warning first. */
public class OldShopStatusTest extends DrainTest {
    static final String REF = "ORD-00000000000000P1";
    static final String ORDER_COIN = "0x" + "C7".repeat(32), PAY_COIN = "0x" + "E7".repeat(32);

    void physicalOrder(long orderBlock, long payBlock) throws Exception {
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = REF; m.from = "0xBUYER"; m.to = ME; m.currency = "Minima"; m.shopId = "art"; m.shipping = "digital";
        m.buyerPayaddr = BUYER; m.amount = "10"; m.date = System.currentTimeMillis(); m.delivery = "1 High Street";
        m.items = new JSONArray().put(new JSONObject().put("product", "Mug").put("quantity", 1).put("unitPrice", "10")).toString();
        if (orderBlock > 0) call("routeIncoming", new Class<?>[]{String.class, Opened.class, JSONObject.class}, ORDER_COIN, new Opened(true, m.from, m.toWire()),
                new JSONObject().put("coinid", ORDER_COIN).put("created", orderBlock));
        else db.upsertOrder(m, "seller", ORDER_COIN);   // stored by an older Inbox: no block
        JSONObject c = new JSONObject().put("coinid", PAY_COIN).put("tokenid", "0x00").put("amount", "10").put("tokenamount", "10").put("created", String.valueOf(payBlock))
                .put("state", new JSONArray().put(new JSONObject().put("port", 1).put("data", "0x" + hex(REF))));
        payments.add(c);
        set("chainBlock", (int) tip);
        call("routePayment", new Class<?>[]{String.class, Opened.class, JSONObject.class}, PAY_COIN, null, c);
    }
    View detail() throws Exception { return (View) call("buildOrderDetail", new Class<?>[]{String.class}, REF); }
    static String texts(View v) { return String.join("\n", InboxShowsIdentifiersWholeTest.texts(v)); }

    @Test public void aPhysicalOrderShowsTheFacts() throws Exception {
        physicalOrder(tip - 8, tip - 10);
        String all = texts(detail());
        assertTrue(all, all.contains("Order coin (block " + (tip - 8) + ")") && all.contains(ORDER_COIN));
        assertTrue(all, all.contains("Payment coin (block " + (tip - 10) + ")") && all.contains(PAY_COIN));
        assertTrue(all, all.contains("This order was mined 2 block(s) AFTER its payment"));
    }

    @Test public void anOrderOfUnknownBlockStillListsItsCoins() throws Exception {
        physicalOrder(0, tip - 10);
        String all = texts(detail());
        assertTrue(all, all.contains("Order coin (block ?)") && all.contains(ORDER_COIN));
        assertTrue(all, all.contains("Payment coin (block " + (tip - 10) + ")") && all.contains(PAY_COIN));
        assertFalse(all, all.contains("AFTER its payment"));
    }

    @Test public void confirmingAndShippingAskFirst() throws Exception {
        physicalOrder(tip - 8, tip - 10);
        for (String s : new String[]{MerchDb.CONFIRMED, MerchDb.SHIPPED}) {
            String before = db.order(REF).status;
            TextView b = OldShopSendTest.find(detail(), s);
            assertNotNull(s, b);
            b.performClick(); ShadowLooper.idleMainLooper();
            assertEquals("not marked before the vendor accepts", before, db.order(REF).status);
            androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) ShadowDialog.getLatestDialog();
            String all = texts(d.getWindow().getDecorView());
            assertTrue(all, all.contains("This order came from an older Shop and is matched to its payment by its reference only."));
            assertTrue(all, all.contains(PAY_COIN));
            d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper();
            assertEquals(s, db.order(REF).status);
        }
    }

    /** Final review F4: DELIVERED straight from PAID asks first too; once SHIPPED (the vendor accepted it then) it does not. */
    @Test public void deliveringAsksFirstUnlessShipped() throws Exception {
        physicalOrder(tip - 8, tip - 10);
        String before = db.order(REF).status;
        OldShopSendTest.find(detail(), MerchDb.DELIVERED).performClick(); ShadowLooper.idleMainLooper();
        assertEquals("not marked before the vendor accepts", before, db.order(REF).status);
        androidx.appcompat.app.AlertDialog d = (androidx.appcompat.app.AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(texts(d.getWindow().getDecorView()).contains("This order came from an older Shop and is matched to its payment by its reference only."));
        d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); ShadowLooper.idleMainLooper();
        assertEquals(MerchDb.DELIVERED, db.order(REF).status);
        db.setStatus(REF, MerchDb.SHIPPED);
        d.dismiss();
        OldShopSendTest.find(detail(), MerchDb.DELIVERED).performClick(); ShadowLooper.idleMainLooper();
        assertEquals("from SHIPPED it is marked at once", MerchDb.DELIVERED, db.order(REF).status);
    }

    /** Final review F6: the order coin of a no-secret order is shown once (in the facts), not again as "Order tx". */
    @Test public void theOrderCoinIsShownOnce() throws Exception {
        physicalOrder(tip - 8, tip - 10);
        String all = texts(detail());
        assertEquals(all, 1, all.split(java.util.regex.Pattern.quote(ORDER_COIN), -1).length - 1);
        assertTrue(all, all.contains("Order coin (block " + (tip - 8) + ")"));
    }
}
