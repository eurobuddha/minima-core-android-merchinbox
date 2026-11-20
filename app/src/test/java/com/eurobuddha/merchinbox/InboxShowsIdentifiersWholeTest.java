package com.eurobuddha.merchinbox;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.eurobuddha.comms.MerchDb;
import com.eurobuddha.comms.MerchMessage;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Identifiers are shown whole (owner rule): the order screen carries the buyer key and the order coin id in full. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class InboxShowsIdentifiersWholeTest {
    static final String BUYER = "0x" + "AB".repeat(64), COIN = "0x" + "CD".repeat(32), PAYADDR = "Mx" + "Q".repeat(58);
    MainActivity activity; MerchDb db;
    @Before public void setup() throws Exception {
        activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat);
        activity.getWindow().setContentView(new FrameLayout(activity));
        db = new MerchDb(RuntimeEnvironment.getApplication()); set("db", db);
    }
    @After public void close() throws Exception {
        db.close(); ((ExecutorService) get("io")).shutdownNow(); activity.finish();
    }
    Object get(String n) throws Exception { Field f = MainActivity.class.getDeclaredField(n); f.setAccessible(true); return f.get(activity); }
    void set(String n, Object v) throws Exception { Field f = MainActivity.class.getDeclaredField(n); f.setAccessible(true); f.set(activity, v); }
    static List<String> texts(View v) {
        List<String> out = new ArrayList<>();
        if (v instanceof TextView) out.add(((TextView) v).getText().toString());
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) out.addAll(texts(((ViewGroup) v).getChildAt(i)));
        return out;
    }
    @Test public void theOrderScreenShowsTheBuyerKeyAndCoinIdWhole() throws Exception {
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = "ORD-WHOLE"; m.from = BUYER; m.to = "0xME"; m.amount = "5"; m.currency = "Minima";
        m.buyerPayaddr = PAYADDR; m.items = "[]";
        assertTrue(db.upsertOrder(m, "seller", COIN));
        Method b = MainActivity.class.getDeclaredMethod("buildOrderDetail", String.class); b.setAccessible(true);
        String all = String.join("\n", texts((View) b.invoke(activity, "ORD-WHOLE")));
        assertTrue(all, all.contains(BUYER));
        assertTrue(all, all.contains(COIN));
        assertFalse(all, all.contains("…"));
    }

    @Test public void automaticDeliveryNeverRunsEvenIfTheOldSettingSaysOn() throws Exception {
        com.eurobuddha.comms.NodeApi node = org.mockito.Mockito.mock(com.eurobuddha.comms.NodeApi.class); set("node", node);
        db.setMeta("autodeliver", "1");
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = "ORD-AUTO"; m.from = BUYER; m.to = "0xME"; m.amount = "0.000000001"; m.currency = "Minima";
        m.buyerPayaddr = PAYADDR; m.items = "[{\"product\":\"x\",\"quantity\":1000,\"nftTokenId\":\"0x00\"}]";
        db.upsertOrder(m, "seller", COIN);
        assertEquals(MerchDb.PAID, db.recordPayment("ORD-AUTO", "0xC0FFEE", "0.000000001", "0x00"));
        Method a = MainActivity.class.getDeclaredMethod("maybeAutoDeliver", String.class); a.setAccessible(true);
        a.invoke(activity, "ORD-AUTO");
        org.mockito.Mockito.verifyNoInteractions(node);
    }

    @Test public void aManualSendOfMinimaOrADecimalTokenNeverReachesSend() throws Exception {
        com.eurobuddha.comms.NodeApi node = org.mockito.Mockito.mock(com.eurobuddha.comms.NodeApi.class); set("node", node);
        List<String> commands = new ArrayList<>();
        org.mockito.Mockito.doAnswer(i -> {
            String c = i.getArgument(0); commands.add(c);
            com.eurobuddha.comms.NodeApi.Cb cb = i.getArgument(1);
            if (c.startsWith("tokens tokenid:")) cb.onResult(new org.json.JSONObject().put("status", true)
                    .put("response", new org.json.JSONObject().put("tokenid", c.substring(15)).put("decimals", 8)));
            return null;
        }).when(node).cmd(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        MerchMessage paidOrder = new MerchMessage();
        paidOrder.type = MerchMessage.ORDER; paidOrder.ref = "ORD-MAN"; paidOrder.from = BUYER; paidOrder.to = "0xME"; paidOrder.amount = "5";
        paidOrder.currency = "Minima"; paidOrder.buyerPayaddr = PAYADDR; paidOrder.items = "[]";
        db.upsertOrder(paidOrder, "seller", COIN); db.recordPayment("ORD-MAN", "0xBEEF", "5", "0x00");
        MerchDb.Order o = db.order("ORD-MAN"); o.payaddr = "0x" + "12".repeat(32);
        Method send = MainActivity.class.getDeclaredMethod("sendNft", MerchDb.Order.class, String[].class, boolean.class); send.setAccessible(true);
        send.invoke(activity, o, new String[]{"x", "0x00", "1000", "0", "0"}, false);
        send.invoke(activity, o, new String[]{"x", DeliveryGuard.MXUSD, "5", "0", "0"}, false);
        assertTrue(commands.toString(), commands.stream().allMatch("block"::equals));
        String decimalToken = "0x" + "EF".repeat(32);
        send.invoke(activity, o, new String[]{"x", decimalToken, "1", "0", "0"}, false);
        assertTrue(commands.toString(), commands.stream().noneMatch(c -> c.startsWith("send") || c.startsWith("txn")));
    }

    @Test public void aMismatchedOrderNeverSendsWhateverItsStatus() throws Exception {
        com.eurobuddha.comms.NodeApi node = org.mockito.Mockito.mock(com.eurobuddha.comms.NodeApi.class); set("node", node);
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = "ORD-MIS"; m.from = BUYER; m.to = "0xME"; m.amount = "5"; m.currency = "Minima";
        m.tokenid = DeliveryGuard.MXUSD; m.buyerPayaddr = PAYADDR; m.items = "[]";
        db.upsertOrder(m, "seller", COIN);
        assertEquals(MerchDb.PAID, db.recordPayment("ORD-MIS", "0xD00D", "5", "0x00"));
        db.setStatus("ORD-MIS", MerchDb.CONFIRMED);
        MerchDb.Order o = db.order("ORD-MIS"); o.payaddr = "0x" + "12".repeat(32);
        Method send = MainActivity.class.getDeclaredMethod("sendNft", MerchDb.Order.class, String[].class, boolean.class); send.setAccessible(true);
        send.invoke(activity, o, new String[]{"x", "0x" + "AB".repeat(32), "1", "0", "0"}, false);
        org.mockito.Mockito.verify(node, org.mockito.Mockito.never()).cmd(org.mockito.ArgumentMatchers.argThat(c -> !"block".equals(c)), org.mockito.ArgumentMatchers.any());   // asked nothing past the chain height (DrainTest checks the full path)
    }

    @Test public void anOrderNotPaidInFullNeverSends() throws Exception {
        com.eurobuddha.comms.NodeApi node = org.mockito.Mockito.mock(com.eurobuddha.comms.NodeApi.class); set("node", node);
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = "ORD-SHORT"; m.from = BUYER; m.to = "0xME"; m.amount = "5"; m.currency = "Minima";
        m.buyerPayaddr = PAYADDR; m.items = "[]";
        db.upsertOrder(m, "seller", COIN);
        db.recordPayment("ORD-SHORT", "0xF1", "4", "0x00");
        MerchDb.Order o = db.order("ORD-SHORT"); o.paid = true; o.payaddr = "0x" + "12".repeat(32);   // a stale screen copy says paid
        Method send = MainActivity.class.getDeclaredMethod("sendNft", MerchDb.Order.class, String[].class, boolean.class); send.setAccessible(true);
        send.invoke(activity, o, new String[]{"x", "0x" + "AB".repeat(32), "1", "0", "0"}, false);
        org.mockito.Mockito.verify(node, org.mockito.Mockito.never()).cmd(org.mockito.ArgumentMatchers.argThat(c -> !"block".equals(c)), org.mockito.ArgumentMatchers.any());   // asked nothing past the chain height (DrainTest checks the full path)
    }

    @Test public void anOrderCheckedAgainstTheListingIsDueWhatTheVendorListsAndTheAttackNeverSends() throws Exception {
        com.eurobuddha.comms.NodeApi node = org.mockito.Mockito.mock(com.eurobuddha.comms.NodeApi.class); set("node", node);
        String me = "0x" + "AB".repeat(64);
        db.setMeta("listing:art", Listing.compact(ListingTest.shop(me, "Minima", null, "2.5"), me).toString());
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = "ORD-ATK"; m.from = BUYER; m.to = "0xME"; m.amount = "0.000000001"; m.currency = "Minima";
        m.shopId = "art"; m.shipping = "digital"; m.buyerPayaddr = PAYADDR;
        m.items = "[{\"product\":\"Print\",\"quantity\":1000,\"unitPrice\":\"0.000000001\",\"nftTokenId\":\"0x00\"}]";
        db.upsertOrder(m, "seller", COIN);
        Method recheck = MainActivity.class.getDeclaredMethod("recheck", MerchDb.Order.class); recheck.setAccessible(true);
        recheck.invoke(activity, db.order("ORD-ATK"));
        assertFalse(db.listingProblems(db.order("ORD-ATK")).isEmpty());
        assertNotEquals(MerchDb.PAID, db.recordPayment("ORD-ATK", "0xA7", "0.000000001", "0x00"));
        MerchDb.Order o = db.order("ORD-ATK"); o.paid = true; o.payaddr = "0x" + "12".repeat(32);
        Method send = MainActivity.class.getDeclaredMethod("sendNft", MerchDb.Order.class, String[].class, boolean.class); send.setAccessible(true);
        send.invoke(activity, o, new String[]{"Print", "0x00", "1000", "0", "0"}, false);
        org.mockito.Mockito.verify(node, org.mockito.Mockito.never()).cmd(org.mockito.ArgumentMatchers.argThat(c -> !"block".equals(c)), org.mockito.ArgumentMatchers.any());   // asked nothing past the chain height (DrainTest checks the full path)
        Method b = MainActivity.class.getDeclaredMethod("buildOrderDetail", String.class); b.setAccessible(true);
        String all = String.join("\n", texts((View) b.invoke(activity, "ORD-ATK")));
        assertTrue(all, all.contains("Not as you list it"));

        MerchMessage ok = new MerchMessage();
        ok.type = MerchMessage.ORDER; ok.ref = "ORD-OK"; ok.from = BUYER; ok.to = "0xME"; ok.amount = "5"; ok.currency = "Minima";
        ok.shopId = "art"; ok.shipping = "postal"; ok.buyerPayaddr = PAYADDR;
        ok.items = "[{\"product\":\"Print\",\"quantity\":2,\"unitPrice\":\"2.5\"}]";
        db.upsertOrder(ok, "seller", COIN);
        recheck.invoke(activity, db.order("ORD-OK"));
        assertEquals("6.5", db.due(db.order("ORD-OK")));
        assertEquals(MerchDb.UNDERPAID, db.recordPayment("ORD-OK", "0xA8", "5", "0x00"));   // the buyer's 5 is not the 6.5 due
        assertEquals(MerchDb.PAID, db.recordPayment("ORD-OK", "0xA9", "1.5", "0x00"));
        assertTrue(db.deliverable(db.order("ORD-OK")));
        all = String.join("\n", texts((View) b.invoke(activity, "ORD-OK")));
        assertTrue(all, all.contains("Checked against your listing: due 6.5 Minima."));
    }

    @Test public void anUncheckedOrderSaysItsTotalIsTheBuyersOwnFigure() throws Exception {
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = "ORD-UN"; m.from = BUYER; m.to = "0xME"; m.amount = "5"; m.currency = "Minima"; m.items = "[]";
        db.upsertOrder(m, "seller", COIN);
        Method b = MainActivity.class.getDeclaredMethod("buildOrderDetail", String.class); b.setAccessible(true);
        String all = String.join("\n", texts((View) b.invoke(activity, "ORD-UN")));
        assertTrue(all, all.contains("are the buyer's own figures"));
    }

    @Test(timeout = 20000) public void anOrderThatCannotBeCheckedStaysBlockedAndItsNoteIsShort() throws Exception {
        String me = "0x" + "AB".repeat(64);
        db.setMeta("listing:art", Listing.compact(ListingTest.shop(me, "Minima", null, "2.5"), me).toString());
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = "ORD-HUGE"; m.from = BUYER; m.to = "0xME"; m.amount = "1"; m.currency = "Minima";
        m.shopId = "art"; m.shipping = "digital"; m.buyerPayaddr = PAYADDR;
        StringBuilder items = new StringBuilder("[");
        for (int i = 0; i < 200; i++) items.append(i == 0 ? "" : ",").append("{\"product\":\"").append("P".repeat(5000)).append("\",\"quantity\":\"1E+999999999\",\"unitPrice\":\"1\"}");
        m.items = items.append("]").toString();
        db.upsertOrder(m, "seller", COIN);
        Method recheck = MainActivity.class.getDeclaredMethod("recheck", MerchDb.Order.class); recheck.setAccessible(true);
        recheck.invoke(activity, db.order("ORD-HUGE"));
        String note = db.listingProblems(db.order("ORD-HUGE"));
        assertFalse(note.isEmpty());
        assertTrue(note.length() + " characters", note.length() < 10000);
        assertFalse(db.deliverable(db.order("ORD-HUGE")));
    }

    @Test public void anMxUsdOrderReadsMxUsdNeverTheWireValue() throws Exception {
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = "ORD-MXUSD"; m.from = BUYER; m.to = "0xME"; m.amount = "5"; m.currency = "USDT";
        m.tokenid = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90"; m.items = "[]";
        assertTrue(db.upsertOrder(m, "seller", COIN));
        Method b = MainActivity.class.getDeclaredMethod("buildOrderDetail", String.class); b.setAccessible(true);
        String all = String.join("\n", texts((View) b.invoke(activity, "ORD-MXUSD")));
        assertTrue(all, all.contains("5 MxUSD"));
        assertFalse(all, all.contains("USDT"));
    }
}
