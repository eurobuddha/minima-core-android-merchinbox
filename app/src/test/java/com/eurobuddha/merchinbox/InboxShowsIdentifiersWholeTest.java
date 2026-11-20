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
        MerchDb.Order o = new MerchDb.Order(); o.ref = "ORD-MAN"; o.payaddr = "0x" + "12".repeat(32); o.paid = true;
        Method send = MainActivity.class.getDeclaredMethod("sendNft", MerchDb.Order.class, String[].class, boolean.class); send.setAccessible(true);
        send.invoke(activity, o, new String[]{"x", "0x00", "1000", "0", "0"}, false);
        send.invoke(activity, o, new String[]{"x", DeliveryGuard.MXUSD, "5", "0", "0"}, false);
        assertTrue(commands.toString(), commands.isEmpty());
        String decimalToken = "0x" + "EF".repeat(32);
        send.invoke(activity, o, new String[]{"x", decimalToken, "1", "0", "0"}, false);
        assertEquals(java.util.Collections.singletonList("tokens tokenid:" + decimalToken), commands);
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
        org.mockito.Mockito.verifyNoInteractions(node);
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
