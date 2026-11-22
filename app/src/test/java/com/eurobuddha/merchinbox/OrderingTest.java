package com.eurobuddha.merchinbox;

import com.eurobuddha.comms.MerchDb;
import com.eurobuddha.comms.MerchMessage;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.Opened;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** The final check's ordering harness, kept as a regression test: every processing order of (stranger order SO,
 *  stranger coin SC, honest order HO, honest coin HC), per stranger variant and honest Shop format, with the vendor
 *  never (P0) or always (P1, up to 3x) taking the offered action. Asserted: a stranger is never delivered for less than
 *  the listing's due; an honest buyer with a secret who ordered first and paid in full is always delivered with no
 *  vendor action. */
public class OrderingTest extends DrainTest {
    static final String STRANGER = "0x" + "66".repeat(32);
    final List<String> strangerSends = new ArrayList<>();
    int run = 0;

    @Override NodeApi fakeNode() {
        NodeApi n = super.fakeNode();
        NodeApi spy = org.mockito.Mockito.mock(NodeApi.class);
        org.mockito.Mockito.doAnswer(i -> {
            String c = i.getArgument(0);
            if (c.startsWith("send") && c.contains("address:" + STRANGER)) strangerSends.add(c);
            n.cmd(c, i.getArgument(1));
            return null;
        }).when(spy).cmd(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        return spy;
    }

    MerchMessage order(String ref, String from, String payaddr, String nonce) throws Exception {
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = ref; m.from = from; m.to = ME; m.currency = "Minima"; m.shopId = "art";
        m.shipping = "digital"; m.buyerPayaddr = payaddr; m.amount = "10"; m.nonce = nonce; m.date = System.currentTimeMillis();
        m.items = new JSONArray().put(new JSONObject().put("product", "Piece").put("quantity", 1).put("unitPrice", "10").put("nftTokenId", NFT)).toString();
        return m;
    }
    JSONObject coin(String ref, String id, String amount, String commit) throws Exception {
        JSONArray st = new JSONArray().put(new JSONObject().put("port", 1).put("data", "0x" + hex(ref)));
        if (commit != null) st.put(new JSONObject().put("port", 2).put("data", commit));
        return new JSONObject().put("coinid", id).put("tokenid", "0x00").put("amount", amount).put("tokenamount", amount)
                .put("created", String.valueOf(tip - 5)).put("state", st);
    }
    void deliverOrder(MerchMessage m, String coinid) throws Exception {
        call("routeIncoming", new Class<?>[]{String.class, Opened.class, JSONObject.class}, coinid,
                new Opened(true, m.from, m.toWire()), new JSONObject().put("coinid", coinid).put("created", tip - 6));
    }
    void deliverCoin(JSONObject c) throws Exception {
        payments.add(c);
        set("chainBlock", (int) tip); db.setMeta(MainActivity.ORDERS_TIP, String.valueOf(tip));   // judged: its block + 3 is read
        call("routePayment", new Class<?>[]{String.class, Opened.class, JSONObject.class}, c.getString("coinid"), null, c);
    }

    /** variant: 1 SO secret + SC(Cs) tiny; 2 SO secret + SC(Cs) full; 3 SO no secret + SC no port2 full;
     *  4 SO secret + SC copies Ch tiny; 5 SO no secret + SC(Cs of a secret never sent) tiny */
    String[] runOne(int[] perm, int variant, boolean honestNew, boolean takeAction) throws Exception {
        run++;
        String ref = String.format("ORD-%016X", (long) run * 7919L + variant);
        db.getWritableDatabase().delete("orders", null, null);
        db.getWritableDatabase().delete("order_chat", null, null);
        db.getWritableDatabase().execSQL("DELETE FROM meta WHERE k NOT LIKE 'listing:%'");
        payments.clear(); sends.clear(); strangerSends.clear();
        String nh = honestNew ? MerchMessage.newNonce() : "", ch = honestNew ? MerchMessage.commitment(nh) : null;
        boolean sSecret = variant == 1 || variant == 2 || variant == 4;
        String ns = sSecret ? MerchMessage.newNonce() : "", cs = sSecret ? MerchMessage.commitment(ns) : null;
        String scCommit = variant == 3 ? null : variant == 4 ? ch : variant == 5 ? MerchMessage.commitment(MerchMessage.newNonce()) : cs;
        String scAmt = variant == 2 || variant == 3 ? "10" : "1";
        MerchMessage ho = order(ref, "0xHONEST", BUYER, nh), so = order(ref, "0xSTRANGER", STRANGER, ns);
        for (int e : perm) switch (e) {
            case 0: deliverOrder(so, "0xC5" + run); break;
            case 1: deliverCoin(coin(ref, "0xD5" + run, scAmt, scCommit)); break;
            case 2: deliverOrder(ho, "0xC6" + run); break;
            case 3: deliverCoin(coin(ref, "0xD6" + run, "10", ch)); break;
        }
        int actions = 0;
        if (takeAction) for (int k = 0; k < 3; k++) {
            MerchDb.Order o = db.order(ref);
            if (db.committedContender(o) < 0) break;
            if (db.switchToCommitted(ref)) { call("recheck", new Class<?>[]{MerchDb.Order.class}, db.order(ref)); actions++; }
        }
        MerchDb.Order o = db.order(ref);
        if (o != null) send(o, false);
        String rec = o == null ? "-" : o.counterparty.equals("0xHONEST") ? "H" : "S";
        return new String[]{rec, String.valueOf(!sends.isEmpty()), String.valueOf(!strangerSends.isEmpty()), String.valueOf(actions),
                o == null ? "-" : String.valueOf(db.conflict(o)), o == null ? "-" : String.valueOf(o.paid)};
    }

    static List<int[]> perms() {
        List<int[]> out = new ArrayList<>();
        int[] a = {0, 1, 2, 3};
        permute(a, 0, out);
        return out;
    }
    static void permute(int[] a, int k, List<int[]> out) {
        if (k == a.length) { out.add(a.clone()); return; }
        for (int i = k; i < a.length; i++) { int t = a[k]; a[k] = a[i]; a[i] = t; permute(a, k + 1, out); t = a[k]; a[k] = a[i]; a[i] = t; }
    }
    static final String[] NAMES = {"SO", "SC", "HO", "HC"};

    @Test public void n1() throws Exception { table(true, 1); }
    @Test public void n2() throws Exception { table(true, 2); }
    @Test public void n3() throws Exception { table(true, 3); }
    @Test public void n4() throws Exception { table(true, 4); }
    @Test public void n5() throws Exception { table(true, 5); }
    @Test public void o1() throws Exception { table(false, 1); }
    @Test public void o2() throws Exception { table(false, 2); }
    @Test public void o3() throws Exception { table(false, 3); }
    @Test public void o4() throws Exception { table(false, 4); }
    @Test public void o5() throws Exception { table(false, 5); }

    void table(boolean honestNew, int variant) throws Exception {
        List<String> bad = new ArrayList<>();
        for (int[] p : perms()) {
            int so = indexOf(p, 0), ho = indexOf(p, 2), hc = indexOf(p, 3);
            String[] p0 = runOne(p, variant, honestNew, false), p1 = runOne(p, variant, honestNew, true);
            StringBuilder name = new StringBuilder(); for (int e : p) name.append(NAMES[e]).append(' ');
            boolean strangerPaidFull = variant == 2 || variant == 3;
            if (!strangerPaidFull && ("true".equals(p0[2]) || "true".equals(p1[2]))) bad.add(name + "v" + variant + ": a stranger who paid less than the due was delivered");
            // the honest coin after its own order (a coin seen before its order is not a payment: pre-existing, by design)
            if (honestNew && ho < so && ho < hc) {
                if (!"true".equals(p0[1])) bad.add(name + "v" + variant + ": the honest buyer who ordered first and paid in full was not delivered without action " + String.join(",", p0));
                if (!"true".equals(p1[1])) bad.add(name + "v" + variant + ": ... nor with the offered action " + String.join(",", p1));
            }
        }
        org.junit.Assert.assertTrue(String.join("\n", bad), bad.isEmpty());
    }

    /** Switch with a stranger who states a tiny amount and pays it: due recomputed from the listing, nothing sent. */
    @Test public void switchNeverDeliversBelowTheListingDue() throws Exception {
        String ref = "ORD-00000000000000A1";
        MerchMessage ho = order(ref, "0xHONEST", BUYER, MerchMessage.newNonce());
        String ns = MerchMessage.newNonce();
        MerchMessage so = order(ref, "0xSTRANGER", BUYER, ns); so.amount = "0.0001";   // stranger uses BUYER address so the fake node records
        deliverOrder(ho, "0xC1"); deliverOrder(so, "0xC2");
        deliverCoin(coin(ref, "0xD1", "0.0001", MerchMessage.commitment(ns)));
        MerchDb.Order o = db.order(ref);
        org.junit.Assert.assertTrue(db.committedContender(o) >= 0);
        org.junit.Assert.assertTrue(db.switchToCommitted(ref));
        org.junit.Assert.assertTrue("PAID against the stranger's own figure before the recheck", db.order(ref).paid);
        org.junit.Assert.assertFalse("but not deliverable before the recheck", db.deliverable(db.order(ref)));
        call("maybeAutoDeliver", new Class<?>[]{String.class}, ref);
        call("recheck", new Class<?>[]{MerchDb.Order.class}, db.order(ref));
        org.junit.Assert.assertFalse(db.order(ref).paid);
        db.setMeta("autosend", "1");
        call("maybeAutoDeliver", new Class<?>[]{String.class}, ref);
        send(db.order(ref), false);
        org.junit.Assert.assertTrue(sends.toString(), sends.isEmpty());
    }

    /** L-A: with 9 stranger orders first, the honest order (secret) finds the 8 slots full, but its commitment is kept, so
     *  its coin flags the ref and is recorded. */
    @Test public void nineStrangersFirstNoLongerSilentlyDropTheHonestPayment() throws Exception {
        String ref = "ORD-00000000000000A2";
        for (int k = 0; k < 9; k++) deliverOrder(order(ref, "0xSTRANGER" + k, STRANGER, MerchMessage.newNonce()), "0xC9" + k);
        String nh = MerchMessage.newNonce();
        deliverOrder(order(ref, "0xHONEST", BUYER, nh), "0xCA");
        deliverCoin(coin(ref, "0xDA", "10", MerchMessage.commitment(nh)));
        MerchDb.Order o = db.order(ref);
        org.junit.Assert.assertEquals(8, db.contenders(ref).length());
        org.junit.Assert.assertTrue("flagged", db.conflict(o));
        org.junit.Assert.assertEquals("the contended coin is recorded", 1, db.contendedCoins(ref).length());
        org.junit.Assert.assertFalse(o.paid);
        org.junit.Assert.assertEquals("its order is not among the kept ones: no switch", -1, db.committedContender(o));
    }

    /** L-B: an old-Shop honest order arriving after a stranger's order with a secret is kept beside it, and its coin
     *  (no port 2) is recorded as unassigned and shown; no flag. */
    @Test public void oldShopHonestOrderAfterAStrangerIsKeptAndItsCoinShown() throws Exception {
        String ref = "ORD-00000000000000A3";
        deliverOrder(order(ref, "0xSTRANGER", STRANGER, MerchMessage.newNonce()), "0xCB");
        deliverOrder(order(ref, "0xHONEST", BUYER, ""), "0xCC");
        deliverCoin(coin(ref, "0xDB", "10", null));
        MerchDb.Order o = db.order(ref);
        org.junit.Assert.assertEquals(1, db.contenders(ref).length());
        org.junit.Assert.assertEquals("", db.contenders(ref).getJSONObject(0).getString("commitment"));
        org.junit.Assert.assertFalse(db.conflict(o));
        org.junit.Assert.assertEquals(1, db.unassignedCoins(ref).length());
        org.junit.Assert.assertEquals("0xDB", db.unassignedCoins(ref).getJSONObject(0).getString("coinid"));
    }

    /** L-A: 32 stranger coins committing to stranger contender S2, then the honest coin: the list is full, so no switch
     *  is offered (it would point at S2) and the screen says payments could not all be read. */
    @Test public void thirtyTwoCoinsNoLongerMisdirectTheAction() throws Exception {
        String ref = "ORD-00000000000000A4";
        String n2 = MerchMessage.newNonce(), nh = MerchMessage.newNonce();
        deliverOrder(order(ref, "0xSTRANGER1", STRANGER, MerchMessage.newNonce()), "0xCD");
        deliverOrder(order(ref, "0xSTRANGER2", STRANGER, n2), "0xCE");
        deliverOrder(order(ref, "0xHONEST", BUYER, nh), "0xCF");
        for (int k = 0; k < 32; k++) deliverCoin(coin(ref, "0xE" + k, "0.00000001", MerchMessage.commitment(n2)));
        deliverCoin(coin(ref, "0xEFF", "10", MerchMessage.commitment(nh)));
        MerchDb.Order o = db.order(ref);
        org.junit.Assert.assertEquals(-1, db.committedContender(o));
        org.junit.Assert.assertTrue(db.contendedCoinsFull(ref));
        org.junit.Assert.assertFalse(db.switchToCommitted(ref));
    }
    static int indexOf(int[] a, int v) { for (int i = 0; i < a.length; i++) if (a[i] == v) return i; return -1; }
}
