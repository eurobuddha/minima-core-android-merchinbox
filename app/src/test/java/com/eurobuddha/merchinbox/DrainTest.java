package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import android.widget.FrameLayout;

import com.eurobuddha.comms.MerchDb;
import com.eurobuddha.comms.MerchMessage;
import com.eurobuddha.comms.NodeApi;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/** "The vendor cannot be drained": nothing leaves the vendor's wallet unless every DeliveryRules check passes,
 *  for manual and automatic sends alike. A fake node answers block / tokens / coins and records every send. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 33, manifest = Config.NONE)
public class DrainTest {
    static final String ME = "0x" + "AB".repeat(64), VENDOR = "0x" + "12".repeat(32), BUYER = "0x" + "34".repeat(32);
    static final String NFT = "0x" + "CD".repeat(32), ART = "0x" + "EF".repeat(32);
    MainActivity activity; MerchDb db;
    long tip = 100;
    final Map<String, Integer> decimals = new HashMap<>();
    final Map<String, String> held = new HashMap<>();
    final List<JSONObject> payments = new ArrayList<>();
    final List<String> sends = new ArrayList<>(), coinReads = new ArrayList<>();
    final java.util.Set<String> spent = new java.util.HashSet<>();
    String dryInput = "0x" + "98".repeat(32);   // the coin a send dry run says it would spend
    String sendInput = "0x" + "96".repeat(32);  // the coin a real send reply says it spent
    boolean rejectSends;   // coin ids (lower case) the vendor has since spent

    /** Runs io work at once, so a test needs only the main looper. */
    static final class Inline extends AbstractExecutorService {
        public void execute(Runnable r) { r.run(); }
        public void shutdown() {} public List<Runnable> shutdownNow() { return new ArrayList<>(); }
        public boolean isShutdown() { return false; } public boolean isTerminated() { return false; }
        public boolean awaitTermination(long t, TimeUnit u) { return true; }
    }

    /** The fake node: block / tokens / coins answers; every other command is a send, recorded. */
    NodeApi fakeNode() {
        NodeApi n = org.mockito.Mockito.mock(NodeApi.class);
        org.mockito.Mockito.doAnswer(i -> {
            String c = i.getArgument(0); NodeApi.Cb cb = i.getArgument(1);
            JSONObject r = new JSONObject().put("status", true);
            if (c.equals("block")) r.put("response", new JSONObject().put("block", String.valueOf(tip)));
            else if (c.startsWith("tokens tokenid:")) {
                String id = c.substring(15);
                r.put("response", new JSONObject().put("tokenid", id).put("decimals", decimals.getOrDefault(id, 8)));
            } else if (c.startsWith("coins relevant:true tokenid:")) {
                String id = c.substring(28);
                r.put("response", new JSONArray().put(new JSONObject().put("coinid", "0x" + "99".repeat(32)).put("tokenid", id).put("tokenamount", held.getOrDefault(id, "0"))));
            } else if (c.startsWith("coins relevant:true address:")) { coinReads.add(c); r.put("response", new JSONArray(payments)); }
            else if (c.startsWith("coins address:")) r.put("response", new JSONArray(payments));   // a scanner page
            else if (c.startsWith("coins relevant:false address:")) {   // a find window: the unspent coins of the window
                coinReads.add(c);
                JSONArray a = new JSONArray();
                for (JSONObject p : payments) if (!spent.contains(p.optString("coinid").toLowerCase(java.util.Locale.ROOT))) a.put(p);
                r.put("response", a);
            }
            else if (c.startsWith("coins relevant:true coinid:")) {   // a read by id: the coin while it is unspent, else nothing
                coinReads.add(c);
                JSONArray a = new JSONArray(); String id = c.substring(27);
                for (JSONObject p : payments) if (id.equalsIgnoreCase(p.optString("coinid")) && !spent.contains(id.toLowerCase(java.util.Locale.ROOT))) a.put(p);
                r.put("response", a);
            }
            else if (c.endsWith(" dryrun:true")) r.put("response", new JSONObject().put("body", new JSONObject().put("txn", new JSONObject()
                    .put("inputs", new JSONArray().put(new JSONObject().put("coinid", dryInput))))));   // a dry run: the coins it would spend, nothing sent
            else if (rejectSends && c.startsWith("send ")) { r.put("status", false).put("error", "refused"); }
            else { if (c.contains("address:" + BUYER)) sends.add(c); r.put("response", new JSONObject().put("txpowid", "0x" + "77".repeat(32))
                    .put("body", new JSONObject().put("txn", new JSONObject().put("inputs", new JSONArray().put(new JSONObject().put("coinid", sendInput)))))); }
            cb.onResult(r);
            return null;
        }).when(n).cmd(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        return n;
    }

    @Before public void setup() throws Exception {
        activity = Robolectric.buildActivity(MainActivity.class).get();
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat);
        activity.getWindow().setContentView(new FrameLayout(activity));
        db = new MerchDb(RuntimeEnvironment.getApplication()); set("db", db);
        set("io", new Inline()); set("vendorAddr", VENDOR); set("myId", ME);
        set("node", fakeNode());
        decimals.put(NFT, 0); decimals.put(ART, 0);
        held.put(NFT, "1"); held.put(ART, "100");
        JSONObject listing = new JSONObject().put("shopId", "art").put("shopName", "Art").put("vendorPublicId", ME).put("currency", "Minima")
                .put("products", new JSONArray()
                        .put(new JSONObject().put("name", "Piece").put("price", "10").put("maxUnits", 1).put("nftTokenId", NFT))
                        .put(new JSONObject().put("name", "Art").put("price", "1").put("maxUnits", 5).put("nftTokenId", ART)))
                .put("shipping", new JSONArray().put(new JSONObject().put("id", "digital").put("fee", "0")));
        db.setMeta("listing:art", Listing.compact(listing.toString(), ME).toString());
    }
    @After public void close() { db.close(); activity.finish(); }

    void set(String n, Object v) throws Exception { Field f = MainActivity.class.getDeclaredField(n); f.setAccessible(true); f.set(activity, v); }
    Object call(String n, Class<?>[] t, Object... a) throws Exception { Method m = MainActivity.class.getDeclaredMethod(n, t); m.setAccessible(true); return m.invoke(activity, a); }

    MerchDb.Order order(String ref, String product, String token, Object qty, String unitPrice) throws Exception {
        return order(ref, product, token, qty, unitPrice, "");
    }
    /** As above, from a current Shop: the order carries this secret (nonce). */
    MerchDb.Order order(String ref, String product, String token, Object qty, String unitPrice, String nonce) throws Exception {
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = ref; m.from = "0xBUYER"; m.to = ME; m.amount = "0.000000001"; m.currency = "Minima"; m.nonce = nonce;
        m.shopId = "art"; m.shipping = "digital"; m.buyerPayaddr = BUYER;
        JSONObject it = new JSONObject().put("product", product).put("quantity", qty).put("unitPrice", unitPrice);
        if (token != null) it.put("nftTokenId", token);
        m.items = new JSONArray().put(it).toString();
        db.upsertOrder(m, "seller", "0xC0");
        call("recheck", new Class<?>[]{MerchDb.Order.class}, db.order(ref));   // as routeIncoming does: the due from the listing
        return db.order(ref);
    }
    void pay(String ref, String amount, long created, String coinid) throws Exception {
        payments.add(new JSONObject().put("coinid", coinid).put("tokenid", "0x00").put("amount", amount).put("tokenamount", amount)
                .put("created", String.valueOf(created))
                .put("state", new JSONArray().put(new JSONObject().put("port", 1).put("data", "0x" + hex(ref)))));
        db.recordPayment(ref, coinid, amount, "0x00");
    }
    /** A current Shop's payment: port 2 carries the commitment to the order's secret. */
    void pay(String ref, String amount, long created, String coinid, String nonce) throws Exception {
        String cc = MerchMessage.commitment(nonce);
        payments.add(new JSONObject().put("coinid", coinid).put("tokenid", "0x00").put("amount", amount).put("tokenamount", amount)
                .put("created", String.valueOf(created))
                .put("state", new JSONArray().put(new JSONObject().put("port", 1).put("data", "0x" + hex(ref))).put(new JSONObject().put("port", 2).put("data", cc))));
        assertEquals(MerchDb.PAID, db.recordPayment(ref, coinid, amount, "0x00", cc, 0));
    }
    static String hex(String s) { StringBuilder b = new StringBuilder(); for (byte x : s.getBytes(StandardCharsets.UTF_8)) b.append(String.format("%02x", x)); return b.toString(); }
    @SuppressWarnings("unchecked") List<String[]> lines(MerchDb.Order o) throws Exception { return (List<String[]>) call("nftLines", new Class<?>[]{MerchDb.Order.class}, o); }
    void send(MerchDb.Order o, boolean auto) throws Exception {
        for (String[] l : lines(o)) call("sendNft", new Class<?>[]{MerchDb.Order.class, String[].class, boolean.class}, o, l, auto);
        ShadowLooper.idleMainLooper();
    }

    @Test public void aFullyCheckedOrderIsSentOnce() throws Exception {
        MerchDb.Order o = order("ORD-OK", "Piece", NFT, 1, "10");
        pay("ORD-OK", "10", tip - 5, "0xA1");
        send(o, false);
        assertEquals(1, sends.size());
        assertTrue(sends.get(0), sends.get(0).startsWith("send amount:1 address:" + BUYER + " tokenid:" + NFT));
        send(db.order("ORD-OK"), false);   // a second delivery of the same line
        assertEquals("a line is sent once", 1, sends.size());
    }

    @Test public void aDoubleConfirmSendsOnce() throws Exception {
        MerchDb.Order o = order("ORD-DBL", "Piece", NFT, 1, "10");
        pay("ORD-DBL", "10", tip - 5, "0xA2");
        String[] l = lines(o).get(0);
        call("sendNft", new Class<?>[]{MerchDb.Order.class, String[].class, boolean.class}, o, l, false);
        call("sendNft", new Class<?>[]{MerchDb.Order.class, String[].class, boolean.class}, o, l, false);
        ShadowLooper.idleMainLooper();
        call("sendNft", new Class<?>[]{MerchDb.Order.class, String[].class, boolean.class}, o, l, false);
        ShadowLooper.idleMainLooper();
        assertEquals(1, sends.size());
    }

    @Test public void theMinimaTimesAThousandAttackSendsNothing() throws Exception {
        MerchDb.Order o = order("ORD-ATK", "Piece", "0x00", 1000, "0.000000001");
        pay("ORD-ATK", "0.000000001", tip - 5, "0xA3");
        send(o, false);
        send(o, true);
        assertTrue(sends.toString(), sends.isEmpty());
    }

    @Test public void aListedNftAboveItsStockSendsNothing() throws Exception {
        held.put(NFT, "5");
        MerchDb.Order o = order("ORD-STK", "Piece", NFT, 3, "10");
        pay("ORD-STK", "30", tip - 5, "0xA4");
        send(o, false);
        assertTrue(sends.toString(), sends.isEmpty());
    }

    @Test public void twentySmallOrdersNeverAddUpToMoreThanTheStock() throws Exception {
        for (int i = 0; i < 20; i++) {
            MerchDb.Order o = order("ORD-S" + i, "Art", ART, 1, "1");
            pay("ORD-S" + i, "1", tip - 5, "0xB" + i);
            send(o, false);
        }
        assertEquals("the listing says 5", 5, sends.size());
    }

    @Test public void aPaymentWithoutEnoughConfirmationsSendsNothing() throws Exception {
        MerchDb.Order o = order("ORD-CNF", "Piece", NFT, 1, "10");
        pay("ORD-CNF", "10", tip, "0xA5");           // 1 confirmation; 3 are needed by default
        send(o, false);
        assertTrue(sends.isEmpty());
        MerchDb.Order z = order("ORD-CNF0", "Art", ART, 1, "1");
        pay("ORD-CNF0", "1", tip + 1, "0xA6");       // not yet in a block: 0 confirmations
        db.setMeta("minconf", "1");
        send(z, false);
        assertTrue(sends.isEmpty());
        send(db.order("ORD-CNF"), false);
        assertEquals("1 confirmation is enough when the vendor sets 1", 1, sends.size());
    }

    @Test public void aCoinCountedForAnotherOrderPaysNothingHere() throws Exception {
        MerchDb.Order o = order("ORD-R2", "Piece", NFT, 1, "10");
        pay("ORD-R2", "10", tip - 5, "0xA8");
        db.setMeta("paycoin:0xa8", "ORD-R1");        // the same coin was counted for order ORD-R1
        send(o, false);
        assertTrue(sends.isEmpty());
    }

    @Test public void anUncheckedOrderCannotBeSentEvenByHand() throws Exception {
        db.getWritableDatabase().delete("meta", "k=?", new String[]{"listing:art"});
        MerchDb.Order o = order("ORD-UNC", "Piece", NFT, 1, "10");
        pay("ORD-UNC", "10", tip - 5, "0xA9");
        send(o, false);
        assertTrue(sends.isEmpty());
    }

    @Test public void automaticDeliveryIsOffByDefaultAndOnOnlyWhenSet() throws Exception {
        String n = MerchMessage.newNonce();   // automatic delivery is only for an order with a secret
        MerchDb.Order o = order("ORD-AUTO", "Piece", NFT, 1, "10", n);
        pay("ORD-AUTO", "10", tip - 5, "0xAA", n);
        db.recordPayment("ORD-AUTO", "0xAB", "10", "0x00");   // the order row says paid (the list's pre-filter)
        db.setMeta("due:ORD-AUTO", "10"); db.setMeta("listingproblem:ORD-AUTO", "");
        db.setMeta("autodeliver", "1");   // the old setting, ON by default before, is never read
        call("maybeAutoDeliver", new Class<?>[]{String.class}, "ORD-AUTO");
        ShadowLooper.idleMainLooper();
        assertTrue("off by default", sends.isEmpty());
        db.setMeta("autosend", "1");
        call("maybeAutoDeliver", new Class<?>[]{String.class}, "ORD-AUTO");
        ShadowLooper.idleMainLooper();
        assertEquals(1, sends.size());
    }

    /** Point release item 1: thousands of old coins at the vendor's address change nothing: the address is never
     *  read, only the order's own coins, by id. */
    @Test public void theVendorsAddressIsNeverReadAndItsOldCoinsChangeNothing() throws Exception {
        MerchDb.Order o = order("ORD-FLOOD", "Piece", NFT, 1, "10");
        pay("ORD-FLOOD", "10", tip - 5, "0xF0");
        for (int i = 0; i < 5000; i++)
            payments.add(new JSONObject().put("coinid", "0x1" + i).put("tokenid", "0x00").put("amount", "0.000000001").put("created", "1"));
        send(o, false);
        assertEquals(1, sends.size());
        for (String c : coinReads) assertTrue(c, c.startsWith("coins relevant:true coinid:"));
        assertEquals(java.util.Collections.singletonList("coins relevant:true coinid:0xf0"), coinReads);
    }

    /** Point release item 1: a coin that reached the confirmations is kept confirmed, so the vendor may spend or
     *  consolidate it and still deliver; a coin that vanished before reaching them (a reorg) never counts. */
    @Test public void spentRevenueStillCountsButACoinGoneBeforeItsConfirmationsDoesNot() throws Exception {
        MerchDb.Order o = order("ORD-SPENT", "Piece", NFT, 1, "10");
        payments.add(new JSONObject().put("coinid", "0xB1").put("tokenid", "0x00").put("amount", "10").put("tokenamount", "10").put("created", String.valueOf(tip - 5))
                .put("state", new JSONArray().put(new JSONObject().put("port", 1).put("data", "0x" + hex("ORD-SPENT")))));
        db.recordPayment("ORD-SPENT", "0xB1", "10", "0x00", null, tip - 5);
        call("confirmSweep", new Class<?>[]{long.class}, tip);   // the per-block sweep reads it back once
        ShadowLooper.idleMainLooper();
        assertTrue(db.getMeta("paidconf:0xb1", ""), db.getMeta("paidconf:0xb1", "").contains("\"block\":" + tip));
        spent.add("0xb1");                                       // the vendor consolidates the revenue
        send(o, false);
        assertEquals(1, sends.size());
        MerchDb.Order r = order("ORD-REORG", "Piece", ART, 1, "1");
        payments.add(new JSONObject().put("coinid", "0xB2").put("tokenid", "0x00").put("amount", "1").put("tokenamount", "1").put("created", String.valueOf(tip - 1))
                .put("state", new JSONArray().put(new JSONObject().put("port", 1).put("data", "0x" + hex("ORD-REORG")))));
        db.recordPayment("ORD-REORG", "0xB2", "1", "0x00", null, tip - 1);
        spent.add("0xb2");                                       // gone at 2 of 3 confirmations
        tip += 5;
        send(r, false);
        assertEquals("never counted", 1, sends.size());
    }

    /** Point release item 2: Studio exports a product's stock as what the vendor holds; after a re-export, the
     *  deliveries under the earlier listing are already out of that holding and do not count again. */
    @Test public void aReExportedListingCountsOnlyItsOwnDeliveries() throws Exception {
        android.database.sqlite.SQLiteDatabase w = db.getWritableDatabase();
        String p = "art|" + ART.toLowerCase(java.util.Locale.ROOT) + "|0|0";
        java.math.BigDecimal one = java.math.BigDecimal.ONE, five = java.math.BigDecimal.valueOf(5), ten = java.math.BigDecimal.TEN;
        for (int k = 0; k < 5; k++) { assertNull(DeliveryLedger.reserve(w, "ORD-OLD" + k + "|0", p, ART, one, five, ten, "s1")); DeliveryLedger.mark(w, "ORD-OLD" + k + "|0", DeliveryLedger.SENT); }
        assertNotNull("the first listing's stock is used up", DeliveryLedger.reserve(w, "ORD-OLD5|0", p, ART, one, five, ten, "s1"));
        db.setMeta("ledger:ORD-LEGACY|0", new JSONObject().put("product", p).put("token", ART).put("qty", "3").put("state", "sent").toString());   // before 0.5.32
        assertNull("the re-exported listing starts from its own stock", DeliveryLedger.reserve(w, "ORD-NEW|0", p, ART, one, five, ten, "s2"));
        assertEquals(0, one.compareTo(DeliveryLedger.delivered(w, p, "s2")));
        assertEquals("in flight still counts across listings", 0, one.compareTo(DeliveryLedger.inflight(w, ART)));
        assertEquals("s5", Listing.id(new JSONObject().put("signedAt", 5)));
        assertTrue(Listing.id(new JSONObject().put("confirmedAt", 7)).startsWith("h"));   // an unsigned listing: its content (m3)
    }

    /** Point release item 3: an automatic attempt that waits for confirmations is not a failure (no notification);
     *  a real automatic failure is notified once per line, not at every attempt. */
    @Test public void waitingIsNeverAFailureAndAFailureIsNotifiedOnce() throws Exception {
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("android.permission.POST_NOTIFICATIONS");
        android.app.NotificationManager nm = (android.app.NotificationManager) RuntimeEnvironment.getApplication().getSystemService(android.content.Context.NOTIFICATION_SERVICE);
        db.setMeta("autosend", "1");
        String w = MerchMessage.newNonce();
        order("ORD-WAIT", "Piece", NFT, 1, "10", w);
        pay("ORD-WAIT", "10", tip, "0xC1", w);   // 1 of 3 confirmations
        call("maybeAutoDeliver", new Class<?>[]{String.class}, "ORD-WAIT");
        ShadowLooper.idleMainLooper();
        assertEquals("waiting: no notification", 0, org.robolectric.Shadows.shadowOf(nm).size());
        assertTrue(sends.isEmpty());
        String n = MerchMessage.newNonce();   // automatic delivery is only for an order with a secret
        MerchDb.Order f = order("ORD-FAIL", "Art", ART, 1, "1", n);
        pay("ORD-FAIL", "1", tip - 5, "0xC2", n);
        held.put(ART, "0");                    // a real failure: the vendor no longer holds the token
        call("maybeAutoDeliver", new Class<?>[]{String.class}, "ORD-FAIL");
        ShadowLooper.idleMainLooper();
        assertEquals(1, org.robolectric.Shadows.shadowOf(nm).size());
        nm.cancelAll();
        call("maybeAutoDeliver", new Class<?>[]{String.class}, "ORD-FAIL");
        ShadowLooper.idleMainLooper();
        assertEquals("notified once per line", 0, org.robolectric.Shadows.shadowOf(nm).size());
    }

    /** Point release item 9: a re-send after the vendor released an unfinished line must spend at least one coin of
     *  the first attempt (so two transfers conflict); "it WAS sent" marks the line sent. */
    @Test public void aReSendReusesTheFirstAttemptsCoinsAndItWasSentMarksTheLine() throws Exception {
        MerchDb.Order o = order("ORD-PIN", "Art", ART, 1, "1");
        pay("ORD-PIN", "1", tip - 5, "0xD1");
        String[] line = lines(o).get(0);
        String key = (String) call("lineKey", new Class<?>[]{String.class, String[].class}, "ORD-PIN", line);
        db.setMeta("attemptcoins:" + key, new JSONArray().put("0x" + "97".repeat(32)).toString());   // the first attempt's coin
        send(db.order("ORD-PIN"), false);
        assertTrue("other coins: refused, nothing sent", sends.isEmpty());
        assertEquals("the line is free again", DeliveryLedger.RELEASED, DeliveryLedger.state(db.getReadableDatabase(), MainActivity.ledgerKey("ORD-PIN", line)));
        dryInput = "0x" + "97".repeat(32);
        send(db.order("ORD-PIN"), false);
        assertEquals("the first attempt's coin: sent", 1, sends.size());
    }

    /** Point release item 12: an order an older Inbox recorded (no coin list) that the vendor already advanced is not
     *  flipped to UNDERPAID by a listing import; a merely paid one is, and says "upgraded"; and the old automatic
     *  delivery setting shows a one-time notice. */
    @Test public void olderOrdersAreNotFlippedWhenAdvancedAndTheOldAutoSettingIsAnnounced() throws Exception {
        for (String[] r : new String[][]{{"ORD-OLDD", "DELIVERED"}, {"ORD-OLDP", "PAID"}}) {
            order(r[0], "Piece", NFT, 1, "10");
            db.getWritableDatabase().execSQL("UPDATE orders SET paid=1, status=?, paidamount='0.0001', paidtokenid='0x00' WHERE ref=?", new Object[]{r[1], r[0]});
            db.setMeta("due:" + r[0], "10");
            db.reevaluate(r[0]);
        }
        assertTrue("advanced: kept", db.order("ORD-OLDD").paid);
        assertEquals(MerchDb.DELIVERED, db.order("ORD-OLDD").status);
        assertFalse(db.order("ORD-OLDP").paid);
        assertEquals("1", db.getMeta("upgraded:ORD-OLDP", ""));
        assertFalse((Boolean) call("autoDeliverWasOn", new Class<?>[]{}));
        db.setMeta("autodeliver", "1");
        assertTrue((Boolean) call("autoDeliverWasOn", new Class<?>[]{}));
        db.setMeta("autodeliverbanner", "1");
        assertFalse((Boolean) call("autoDeliverWasOn", new Class<?>[]{}));
    }

    /** Point release items 7 and 8: a scanner's message is stored on the io executor and counted for the scan's end;
     *  no dialog is shown once the screen is finishing. */
    @Test public void scannerWorkRunsOnIoAndNoDialogOutlivesTheScreen() throws Exception {
        java.util.List<String> handled = new java.util.ArrayList<>();
        com.eurobuddha.comms.CommsScanner.Router r = (com.eurobuddha.comms.CommsScanner.Router) call("onIo", new Class<?>[]{com.eurobuddha.comms.CommsScanner.Router.class},
                (com.eurobuddha.comms.CommsScanner.Router) (cid, op, coin) -> { handled.add(cid); return true; });
        assertFalse("the scanner thread never waits for the database", r.handle("0xC9", null, new JSONObject()));
        assertEquals(java.util.Collections.singletonList("0xC9"), handled);   // run by the (inline) io executor
        java.lang.reflect.Field f = MainActivity.class.getDeclaredField("routedNew"); f.setAccessible(true);
        assertEquals(1, ((java.util.concurrent.atomic.AtomicInteger) f.get(activity)).get());
        activity.finish();
        androidx.appcompat.app.AlertDialog d = ((androidx.appcompat.app.AlertDialog.Builder) call("dialog", new Class<?>[]{})).setMessage("late").show();
        assertFalse("not shown on a finishing screen", d.isShowing());
    }

    /** Runs io work only when drained; drop() loses it, as a killed app would. */
    static final class Queued extends AbstractExecutorService {
        final java.util.ArrayDeque<Runnable> q = new java.util.ArrayDeque<>();
        public void execute(Runnable r) { q.add(r); }
        void drain() { while (!q.isEmpty()) q.poll().run(); }
        void drop() { q.clear(); }
        public void shutdown() {} public List<Runnable> shutdownNow() { return new ArrayList<>(); }
        public boolean isShutdown() { return false; } public boolean isTerminated() { return false; }
        public boolean awaitTermination(long t, TimeUnit u) { return true; }
    }

    /** Point release M1: a scan whose coins were queued but never stored (the app closed or was killed) leaves its
     *  bookmark where it was, so the next scan reads them again; nothing is lost and nothing is counted twice. */
    @Test public void aScanClosedBeforeItsCoinsAreStoredIsReadAgainAndNothingIsCountedTwice() throws Exception {
        order("ORD-SCAN", "Piece", NFT, 1, "10");
        payments.add(new JSONObject().put("coinid", "0xE5").put("tokenid", "0x00").put("amount", "10").put("tokenamount", "10").put("created", String.valueOf(tip - 5))
                .put("state", new JSONArray().put(new JSONObject().put("port", 1).put("data", "0x" + hex("ORD-SCAN")))));
        Queued q = new Queued(); set("io", q);
        call("startPaymentScanner", new Class<?>[]{});
        Field ps = MainActivity.class.getDeclaredField("paymentScanner"); ps.setAccessible(true);
        ((com.eurobuddha.comms.CommsScanner) ps.get(activity)).scan((int) tip);
        assertFalse("queued, not stored yet", db.order("ORD-SCAN").paid);
        q.drop();                                                    // closed or killed before the queue ran
        assertEquals("the bookmark did not advance", "", db.getMeta("scanned_tip_pay_12121212", ""));
        set("io", new Inline());
        call("startPaymentScanner", new Class<?>[]{});
        ((com.eurobuddha.comms.CommsScanner) ps.get(activity)).scan((int) tip);
        assertTrue("read again and stored", db.order("ORD-SCAN").paid);
        assertEquals(String.valueOf(tip), db.getMeta("scanned_tip_pay_12121212", ""));
        String paid = db.order("ORD-SCAN").paidAmount;
        call("startPaymentScanner", new Class<?>[]{});
        ((com.eurobuddha.comms.CommsScanner) ps.get(activity)).scan((int) tip + 1);
        assertEquals("not counted twice", paid, db.order("ORD-SCAN").paidAmount);
        // a coin that cannot be stored keeps the bookmark too
        Queued q2 = new Queued(); set("io", q2);
        db.setMeta("scanned_tip_pay_12121212", "1");
        ScanQueue: {
            Field pq = MainActivity.class.getDeclaredField("paymentQueue"); pq.setAccessible(true);
            Object queue = pq.get(activity);
            com.eurobuddha.comms.CommsScanner.Router r = (com.eurobuddha.comms.CommsScanner.Router) queue.getClass().getDeclaredMethod("route", com.eurobuddha.comms.CommsScanner.Router.class)
                    .invoke(queue, (com.eurobuddha.comms.CommsScanner.Router) (cid, op, coin) -> { throw new IllegalStateException("disk full"); });
            r.handle("0xE6", null, new JSONObject());
            ((com.eurobuddha.comms.CommsScanner.MetaStore) queue).setMeta("scanned_tip_pay_12121212", "200");
            q2.drain();
            assertEquals("a failed coin keeps the bookmark", "1", db.getMeta("scanned_tip_pay_12121212", ""));
        }
    }

    /** Point release m1: the real send's actual coins join the line's attempt coins; a send refused before it reached
     *  the node forgets them; a re-send may use other coins when every first-attempt coin was spent for another line. */
    @Test public void theRealSendsCoinsAreKeptAndASendRefusedFirstForgetsThem() throws Exception {
        MerchDb.Order o = order("ORD-PIN2", "Art", ART, 1, "1");
        pay("ORD-PIN2", "1", tip - 5, "0xD2");
        String[] line = lines(o).get(0);
        String key = (String) call("lineKey", new Class<?>[]{String.class, String[].class}, "ORD-PIN2", line);
        rejectSends = true;
        send(db.order("ORD-PIN2"), false);
        assertEquals("refused before sending: the attempt coins are forgotten", "", db.getMeta("attemptcoins:" + key, ""));
        rejectSends = false;
        db.setMeta("attemptcoins:" + key, new JSONArray().put("0x" + "95".repeat(32)).toString());   // a first attempt's coin
        db.setMeta("spentby:0x" + "95".repeat(32), "ORD-OTHER:" + NFT);   // spent by this app for another line
        send(db.order("ORD-PIN2"), false);
        assertEquals("allowed: the first attempt's coin went elsewhere", 1, sends.size());
        assertTrue(db.getMeta("attemptcoins:" + key, ""), db.getMeta("attemptcoins:" + key, "").contains(sendInput));
        assertEquals(key, db.getMeta("spentby:" + sendInput, ""));
    }

    /** Point release m3: an unsigned listing's id is its content (confirming it again changes nothing, changing it
     *  does); ledger rows from before rows named their listing are tagged once with the listing then in use. */
    @Test public void anUnsignedListingIsItsContentAndOlderRowsAreTaggedOnce() throws Exception {
        JSONObject l = new JSONObject(db.getMeta("listing:art", ""));
        String id = Listing.id(l);
        assertTrue(id, id.matches("h[0-9a-f]{64}"));
        assertEquals(id, Listing.id(new JSONObject(l.toString()).put("confirmedAt", 123)));
        assertNotEquals(id, Listing.id(new JSONObject(l.toString()).put("shopName", "Other")));
        db.setMeta("ledger:ORD-T1|0", new JSONObject().put("product", "art|" + ART.toLowerCase(java.util.Locale.ROOT) + "|0|0").put("token", ART).put("qty", "2").put("state", "sent").toString());
        call("tagLedger", new Class<?>[]{});
        assertEquals(id, new JSONObject(db.getMeta("ledger:ORD-T1|0", "")).getString("listing"));
        db.setMeta("ledger:ORD-T2|0", new JSONObject().put("product", "art|x|0|0").put("token", ART).put("qty", "1").put("state", "sent").toString());
        call("tagLedger", new Class<?>[]{});
        assertFalse("once only", new JSONObject(db.getMeta("ledger:ORD-T2|0", "")).has("listing"));
    }
}
