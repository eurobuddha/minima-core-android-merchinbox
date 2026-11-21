package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import com.eurobuddha.comms.MerchDb;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.math.BigDecimal;
import java.util.List;

/** Final review L1, L2, L5: complete collections and pieces share one stock, editions are StateNFT coins of exactly
 *  one unit, and two identical lines of one order are two lines. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class) @org.robolectric.annotation.Config(sdk = 33, manifest = org.robolectric.annotation.Config.NONE)
public class PiecesTest {
    static final String ME = "0x" + "AB".repeat(64), T = "0x" + "CD".repeat(32), BUYER = "0x" + "34".repeat(32);

    static JSONObject listing() throws Exception {
        return Listing.compact(new JSONObject().put("shopId", "art").put("vendorPublicId", ME).put("currency", "Minima")
                .put("products", new JSONArray()
                        .put(new JSONObject().put("name", "Piece 1").put("price", "5").put("nftTokenId", T).put("nftStateIdx", 1))
                        .put(new JSONObject().put("name", "All").put("price", "40").put("nftTokenId", T).put("nftBundle", 1)))
                .put("shipping", new JSONArray().put(new JSONObject().put("id", "digital").put("fee", "0"))).toString(), ME);
    }
    static DeliveryRules.Inputs inputs(String name, String idx, String bundle, String price) throws Exception {
        DeliveryRules.Inputs in = new DeliveryRules.Inputs();
        MerchDb.Order o = new MerchDb.Order(); o.ref = "ORD-PIECES01"; o.shopId = "art"; o.currency = "Minima"; o.shipping = "digital"; o.payaddr = BUYER;
        JSONObject it = new JSONObject().put("product", name).put("quantity", 1).put("unitPrice", price).put("nftTokenId", T);
        if (!"0".equals(idx)) it.put("nftStateIdx", Integer.parseInt(idx));
        if ("1".equals(bundle)) it.put("nftBundle", 1);
        o.items = new JSONArray().put(it).toString();
        in.order = o; in.listing = listing(); in.line = new String[]{name, T, "1", idx, bundle, "0", "0"};
        in.deliverable = true; in.tip = 100;
        in.tokenReply = new JSONObject().put("status", true).put("response", new JSONObject().put("tokenid", T).put("decimals", 0)
                .put("script", StateNft.script("0x" + "11".repeat(32), "embed")));
        in.holdingsReply = new JSONObject().put("status", true).put("response", new JSONArray().put(new JSONObject().put("tokenid", T).put("tokenamount", "8")));
        in.paid = new java.util.ArrayList<>();
        return in;
    }
    static boolean says(DeliveryRules.Verdict v, String part) { for (String r : v.reasons) if (r.contains(part)) return true; return false; }

    @Test public void aCompleteCollectionAndItsPiecesShareOneStock() throws Exception {
        DeliveryRules.Inputs bundle = inputs("All", "0", "1", "40");
        bundle.deliveredOfToken = BigDecimal.ONE;   // piece #1 already went
        assertTrue(says(DeliveryRules.reasons(bundle), "no longer whole"));
        DeliveryRules.Inputs piece = inputs("Piece 1", "1", "0", "5");
        piece.bundleTaken = true;
        assertTrue(says(DeliveryRules.reasons(piece), "complete collection of this token was already"));
        assertFalse(says(DeliveryRules.reasons(inputs("Piece 1", "1", "0", "5")), "complete collection"));
        MerchDb.Order both = inputs("All", "0", "1", "40").order;
        both.items = new JSONArray().put(new JSONObject().put("product", "All").put("quantity", 1).put("unitPrice", "40").put("nftTokenId", T).put("nftBundle", 1))
                .put(new JSONObject().put("product", "Piece 1").put("quantity", 1).put("unitPrice", "5").put("nftTokenId", T).put("nftStateIdx", 1)).toString();
        assertFalse(Listing.check(listing(), both).problems.isEmpty());
    }

    @Test public void editionsAreStateNftCoinsOfExactlyOneUnit() throws Exception {
        DeliveryRules.Inputs in = inputs("Piece 1", "1", "0", "5");
        in.tokenReply.getJSONObject("response").put("script", "RETURN TRUE");
        assertTrue(says(DeliveryRules.reasons(in), "not a StateNFT collection"));
        java.lang.reflect.Method one = MainActivity.class.getDeclaredMethod("oneUnit", JSONObject.class); one.setAccessible(true);
        assertTrue((Boolean) one.invoke(null, new JSONObject().put("tokenamount", "1")));
        assertFalse((Boolean) one.invoke(null, new JSONObject().put("tokenamount", "2")));
        assertFalse((Boolean) one.invoke(null, new JSONObject().put("tokenamount", "0.5")));
        assertFalse((Boolean) one.invoke(null, new JSONObject()));
    }

    @Test @SuppressWarnings("unchecked") public void twoIdenticalLinesAreTwoLines() throws Exception {
        MerchDb.Order o = new MerchDb.Order(); o.ref = "ORD-TWICE001";
        JSONObject it = new JSONObject().put("product", "Art").put("quantity", 1).put("unitPrice", "1").put("nftTokenId", T);
        o.items = new JSONArray().put(it).put(it).toString();
        MainActivity a = org.robolectric.Robolectric.buildActivity(MainActivity.class).get();
        java.lang.reflect.Method n = MainActivity.class.getDeclaredMethod("nftLines", MerchDb.Order.class); n.setAccessible(true);
        java.lang.reflect.Method k = MainActivity.class.getDeclaredMethod("lineKey", String.class, String[].class); k.setAccessible(true);
        List<String[]> lines = (List<String[]>) n.invoke(a, o);
        assertEquals(2, lines.size());
        assertEquals("ORD-TWICE001:" + T, k.invoke(null, o.ref, lines.get(0)));   // the first keeps the historical key
        assertNotEquals(k.invoke(null, o.ref, lines.get(0)), k.invoke(null, o.ref, lines.get(1)));
        assertNotEquals(MainActivity.ledgerKey(o.ref, lines.get(0)), MainActivity.ledgerKey(o.ref, lines.get(1)));
    }
}
