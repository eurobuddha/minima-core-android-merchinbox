package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import android.view.View;
import android.widget.TextView;

import com.eurobuddha.comms.MerchMessage;
import com.eurobuddha.comms.Opened;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 2026-11-22 (R3): an order with no secret is warned about at the moment of sending. The vendor's Send confirmation
 *  always says it came from an older Shop and is matched by its reference only, which can be copied from a payment,
 *  then the facts as stored (the order's coin and block, each counted coin and its block, whole and one tap away), a red
 *  line when the order was mined after its earliest payment, a softer one in the same block; the order screen says the
 *  same whether automatic delivery is on or off. */
public class OldShopSendTest extends DrainTest {
    static final String REF = "ORD-00000000000000E1";
    static final String ORDER_COIN = "0x" + "C1".repeat(32), PAY_COIN = "0x" + "E1".repeat(32);

    static DeliveryRules.PaidCoin coin(long created) { return new DeliveryRules.PaidCoin("0xF" + created, "10", "0x00", created); }

    @Test public void whichLinesAreShown() {
        DeliveryRules.OldShopFacts f = DeliveryRules.oldShopFacts(90, Arrays.asList(coin(91), coin(95)));
        assertTrue("order before its payment", f.facts); assertEquals(0, f.after); assertFalse(f.sameBlock);
        f = DeliveryRules.oldShopFacts(90, Arrays.asList(coin(90)));
        assertTrue(f.facts); assertTrue("same block: the softer note", f.sameBlock); assertEquals(0, f.after);
        for (long n : new long[]{1, 3, 4}) {
            f = DeliveryRules.oldShopFacts(90 + n, Arrays.asList(coin(95), coin(90)));
            assertEquals("measured from the earliest payment", n, f.after); assertEquals(90, f.earliest); assertFalse(f.sameBlock);
        }
        assertFalse("order block unknown: the generic paragraph only", DeliveryRules.oldShopFacts(0, Arrays.asList(coin(90))).facts);
        assertFalse("coin block unknown", DeliveryRules.oldShopFacts(90, Arrays.asList(coin(0))).facts);
        assertFalse("no coin", DeliveryRules.oldShopFacts(90, new ArrayList<>()).facts);
        assertFalse(DeliveryRules.oldShopFacts(90, null).facts);
    }

    void orderAt(long orderBlock, long payBlock) throws Exception {
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = REF; m.from = "0xBUYER"; m.to = ME; m.currency = "Minima"; m.shopId = "art"; m.shipping = "digital";
        m.buyerPayaddr = BUYER; m.amount = "10"; m.date = System.currentTimeMillis();
        m.items = new JSONArray().put(new JSONObject().put("product", "Piece").put("quantity", 1).put("unitPrice", "10").put("nftTokenId", NFT)).toString();
        call("routeIncoming", new Class<?>[]{String.class, Opened.class, JSONObject.class}, ORDER_COIN, new Opened(true, m.from, m.toWire()),
                new JSONObject().put("coinid", ORDER_COIN).put("created", orderBlock));
        JSONObject c = new JSONObject().put("coinid", PAY_COIN).put("tokenid", "0x00").put("amount", "10").put("tokenamount", "10").put("created", String.valueOf(payBlock))
                .put("state", new JSONArray().put(new JSONObject().put("port", 1).put("data", "0x" + hex(REF))));
        payments.add(c);
        set("chainBlock", (int) tip);
        call("routePayment", new Class<?>[]{String.class, Opened.class, JSONObject.class}, PAY_COIN, null, c);
        assertTrue(db.order(REF).paid);
    }
    String screen() throws Exception { return String.join("\n", InboxShowsIdentifiersWholeTest.texts((View) call("buildOrderDetail", new Class<?>[]{String.class}, REF))); }

    @Test public void theOrderScreenSaysItWithAutomaticDeliveryOff() throws Exception {
        db.setMeta("autosend", "0");
        orderAt(tip - 8, tip - 10);
        String all = screen();
        assertTrue(all, all.contains("This order came from an older Shop: it is matched to its payment by its reference only"));
        assertTrue(all, all.contains(ORDER_COIN) && all.contains(PAY_COIN));
        assertTrue(all, all.contains("Order coin (block " + (tip - 8) + ")") && all.contains("Payment coin (block " + (tip - 10) + ")"));
        assertTrue(all, all.contains("This order was mined 2 block(s) AFTER its payment (the order at block " + (tip - 8) + ", the payment at block " + (tip - 10) + "): an honest Shop sends the order first."));
    }

    @Test public void anOrderMinedFirstHasNoRedLine() throws Exception {
        orderAt(tip - 10, tip - 8);
        String all = screen();
        assertTrue(all, all.contains("matched to its payment by its reference only"));
        assertFalse(all, all.contains("AFTER its payment"));
        assertFalse(all, all.contains("same block"));
    }

    /** The vendor's Send: the confirmation carries the paragraph, the facts and the older-payment note. */
    @Test public void theSendConfirmationWarns() throws Exception {
        orderAt(tip - 4, tip - 9);   // 5 blocks after: also older than the order (the olderpay note)
        View detail = (View) call("buildOrderDetail", new Class<?>[]{String.class}, REF);
        TextView send = find(detail, "Send ");
        assertNotNull(InboxShowsIdentifiersWholeTest.texts(detail).toString(), send);
        send.performClick();
        ShadowLooper.idleMainLooper();
        android.app.Dialog d = ShadowDialog.getLatestDialog();
        assertNotNull(d);
        String all = String.join("\n", InboxShowsIdentifiersWholeTest.texts(d.getWindow().getDecorView()));
        assertTrue(all, all.contains("This order came from an older Shop and is matched to its payment by its reference only. A reference can be copied from a payment."));
        assertTrue(all, all.contains(ORDER_COIN) && all.contains(PAY_COIN));
        assertTrue(all, all.contains("This order was mined 5 block(s) AFTER its payment"));
        assertTrue(all, all.contains("is older than the order (payment coin " + PAY_COIN + " at block " + (tip - 9)));
        assertTrue("nothing sent by the confirmation alone", sends.isEmpty());
    }

    @Test public void anOrderWithASecretHasNoWarningAtSend() throws Exception {
        String n = MerchMessage.newNonce();
        order("ORD-NEW", "Piece", NFT, 1, "10", n);
        pay("ORD-NEW", "10", tip - 5, "0xB2", n);
        View detail = (View) call("buildOrderDetail", new Class<?>[]{String.class}, "ORD-NEW");
        find(detail, "Send ").performClick();
        ShadowLooper.idleMainLooper();
        String all = String.join("\n", InboxShowsIdentifiersWholeTest.texts(ShadowDialog.getLatestDialog().getWindow().getDecorView()));
        assertFalse(all, all.contains("older Shop"));
    }

    static TextView find(View v, String prefix) {
        if (v instanceof TextView && ((TextView) v).getText().toString().startsWith(prefix) && v.hasOnClickListeners()) return (TextView) v;
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) {
            TextView t = find(((android.view.ViewGroup) v).getChildAt(i), prefix); if (t != null) return t;
        }
        return null;
    }
}
