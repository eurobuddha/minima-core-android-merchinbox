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
}
