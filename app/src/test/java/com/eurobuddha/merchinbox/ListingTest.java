package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import com.eurobuddha.comms.MerchDb;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** What an order is due comes from the vendor's own listing; every line the vendor does not list, a quantity
 *  above stock, a different price, an unknown delivery method or another currency is a problem. */
public class ListingTest {
    static final String ME = "0x" + "AB".repeat(64), NFT = "0x" + "CD".repeat(32), MXUSD = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    static String shop(String vendor, String currency, String tokenid, String price) throws Exception {
        JSONObject o = new JSONObject().put("shopId", "art").put("shopName", "Art").put("vendorPublicId", vendor).put("currency", currency)
                .put("products", new JSONArray()
                        .put(new JSONObject().put("name", "Print").put("price", price).put("maxUnits", 3).put("image", "AAAA"))
                        .put(new JSONObject().put("name", "Piece").put("price", "10").put("maxUnits", 1).put("nftTokenId", NFT)))
                .put("shipping", new JSONArray().put(new JSONObject().put("id", "digital").put("label", "Digital").put("fee", "0"))
                        .put(new JSONObject().put("id", "postal").put("label", "Postal").put("fee", "1.5")));
        if (tokenid != null) o.put("tokenid", tokenid);
        return o.toString();
    }
    static MerchDb.Order order(String currency, String shipping, JSONObject... items) {
        MerchDb.Order o = new MerchDb.Order(); o.ref = "ORD-T"; o.shopId = "art"; o.currency = currency; o.shipping = shipping; o.amount = "0.000000001";
        JSONArray a = new JSONArray(); for (JSONObject i : items) a.put(i); o.items = a.toString(); return o;
    }
    static JSONObject item(String name, Object qty, String price, String token) throws Exception {
        JSONObject i = new JSONObject().put("product", name).put("quantity", qty).put("unitPrice", price);
        if (token != null) i.put("nftTokenId", token);
        return i;
    }

    @Test public void onlyTheVendorsOwnPayableListingIsKeptWithoutPictures() throws Exception {
        JSONObject l = Listing.compact(shop(ME, "Minima", null, "2.5"), ME.toLowerCase());
        assertFalse(l.toString().contains("AAAA"));
        assertEquals("art", l.getString("shopId"));
        assertThrows(IllegalArgumentException.class, () -> Listing.compact(shop("0x" + "EF".repeat(64), "Minima", null, "2.5"), ME));
        assertThrows(IllegalArgumentException.class, () -> Listing.compact(shop(ME, "Minima", MXUSD, "2.5"), ME));
        assertThrows(IllegalArgumentException.class, () -> Listing.compact(shop(ME, "Minima", null, "1e3"), ME));
        assertThrows(IllegalArgumentException.class, () -> Listing.compact("not json", ME));
    }

    @Test public void aMatchingOrderIsDueThePricesTimesQuantitiesPlusTheFee() throws Exception {
        JSONObject l = Listing.compact(shop(ME, "Minima", null, "2.5"), ME);
        Listing.Check c = Listing.check(l, order("Minima", "postal", item("Print", 2, "2.50", null), item("Piece", 1, "10", NFT.toLowerCase())));
        assertTrue(c.problems.toString(), c.problems.isEmpty());
        assertEquals("16.5", c.due);
    }

    @Test public void theAttackOrderIsRefused() throws Exception {
        JSONObject l = Listing.compact(shop(ME, "Minima", null, "2.5"), ME);
        Listing.Check c = Listing.check(l, order("Minima", "digital", item("Print", 1000, "0.000000001", "0x00")));
        assertFalse(c.problems.isEmpty());
        assertTrue(c.problems.get(0), c.problems.get(0).contains("is not in your listing"));
    }

    @Test public void stockPriceDeliveryAndCurrencyAreChecked() throws Exception {
        JSONObject l = Listing.compact(shop(ME, "Minima", null, "2.5"), ME);
        assertFalse(Listing.check(l, order("Minima", "digital", item("Print", 4, "2.5", null))).problems.isEmpty());
        assertFalse(Listing.check(l, order("Minima", "digital", item("Piece", 2, "10", NFT))).problems.isEmpty());
        assertFalse(Listing.check(l, order("Minima", "digital", item("Print", 1, "0.5", null))).problems.isEmpty());
        assertFalse(Listing.check(l, order("Minima", "digital", item("Print", "1.5", "2.5", null))).problems.isEmpty());
        assertFalse(Listing.check(l, order("Minima", "drone", item("Print", 1, "2.5", null))).problems.isEmpty());
        assertFalse(Listing.check(l, order("USDT", "digital", item("Print", 1, "2.5", null))).problems.isEmpty());
        assertFalse(Listing.check(l, order("Minima", "digital")).problems.isEmpty());
    }

    @Test(timeout = 5000) public void hugeBuyerNumbersAreRefusedQuicklyWithShortMessages() throws Exception {
        JSONObject l = Listing.compact(shop(ME, "Minima", null, "2.5"), ME);
        for (Object q : new Object[]{"1E+999999999", "1E+3000000", "1e3", "-1", "0", "1.5", "1" + "0".repeat(40), "NaN"}) {
            Listing.Check c = Listing.check(l, order("Minima", "digital", item("Print", q, "2.5", null)));
            assertFalse(String.valueOf(q), c.problems.isEmpty());
            for (String p : c.problems) assertTrue(p.length() + " characters", p.length() < 400);
        }
        Listing.Check c = Listing.check(l, order("Minima", "digital", item("Print", 1, "1E+999999999", null)));
        assertFalse(c.problems.isEmpty());
        assertTrue(c.problems.get(0).length() < 400);
        c = Listing.check(l, order("Minima", "x".repeat(100000), item("P".repeat(100000), 1, "2.5", null)));
        for (String p : c.problems) assertTrue(p.length() + " characters", p.length() < 400);
    }

    @Test(timeout = 5000) public void hugeOrDeepItemsAreRefusedUnread() throws Exception {
        assertNull(Listing.items("[" + "{\"product\":\"x\"},".repeat(7000) + "{}]"));   // over 64 KB
        assertNull(Listing.items("[[[[[[[[[1]]]]]]]]]"));                              // nested 9 deep
        assertNotNull(Listing.items("[[[[[[[[1]]]]]]]]"));                              // 8 is allowed
        assertNotNull(Listing.items("[{\"product\":\"a [[[[[[[[[ b\"}]"));              // brackets inside a string do not count
        assertNull(Listing.items("[1,"));
        JSONObject l = Listing.compact(shop(ME, "Minima", null, "2.5"), ME);
        MerchDb.Order o = order("Minima", "digital"); o.items = "[" + "[".repeat(5000) + "]".repeat(5000) + "]";
        assertFalse(Listing.check(l, o).problems.isEmpty());
    }

    /** A stand-in for the shop key's sealed box: "sealed:<from>:<hex text>". */
    static final class FakeCrypto implements com.eurobuddha.comms.CryptoProvider {
        public String identity() { return ME; }
        public String seal(String to, byte[] p) { return "sealed:" + ME + ":" + new String(p, java.nio.charset.StandardCharsets.UTF_8); }
        public com.eurobuddha.comms.Opened open(String blob) {
            if (!blob.startsWith("sealed:")) return null;
            String[] f = blob.split(":", 3);
            return new com.eurobuddha.comms.Opened(true, f[1], f[2].getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test public void onlyAListingSignedWithThisShopKeyIsUsed() throws Exception {
        String plain = shop(ME, "Minima", null, "2.5");
        assertThrows(IllegalArgumentException.class, () -> Listing.unseal(plain, new FakeCrypto(), ME));   // an old unsigned .shop
        String text = signedText(plain, 1);
        String signed = new JSONObject(plain).put("listingSealed", "sealed:" + ME + ":" + text).toString();
        assertEquals(text, Listing.unseal(signed, new FakeCrypto(), ME));
        String notAListing = new JSONObject(plain).put("listingSealed", "sealed:" + ME + ":" + plain).toString();   // sealed by this key, but no kind
        assertThrows(IllegalArgumentException.class, () -> Listing.unseal(notAListing, new FakeCrypto(), ME));
        String other = "0x" + "EF".repeat(64);
        String forged = new JSONObject(plain).put("listingSealed", "sealed:" + other + ":" + plain).toString();
        assertThrows(IllegalArgumentException.class, () -> Listing.unseal(forged, new FakeCrypto(), ME));
        String garbage = new JSONObject(plain).put("listingSealed", "0x1234").toString();
        assertThrows(IllegalArgumentException.class, () -> Listing.unseal(garbage, new FakeCrypto(), ME));
    }

    @Test public void anNftPricedAtZeroIsRefusedAndTheSummaryShowsEverythingWhole() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> Listing.compact(shop(ME, "Minima", null, "2.5").replace("\"price\":\"10\"", "\"price\":\"0\""), ME));
        JSONObject now = Listing.compact(shop(ME, "Minima", null, "2.5"), ME);
        JSONObject next = Listing.compact(shop(ME, "Minima", null, "3"), ME);
        String s = Listing.summary(next, now);
        assertTrue(s, s.contains(NFT) && s.contains("price 10, stock 1") && s.contains("fee 1.5"));
        assertTrue(s, s.contains("~ \"Print\": price 2.5, stock 3\n  → \"Print\": price 3, stock 3"));
        assertTrue(Listing.summary(now, now).contains("Changes from the listing in use:\nnone"));
    }

    @Test public void anUnsignedShopIsUsableOnlyThroughTheWarnedImportAndIsMarkedSo() throws Exception {
        String plain = shop(ME, "Minima", null, "2.5");                 // the desktop builder's file: no signature
        JSONObject now = Listing.compact(shop(ME, "Minima", null, "2"), ME);
        Listing.Prepared u = Listing.prepare(plain, new FakeCrypto(), ME, id -> now);
        assertFalse(u.signed);
        assertTrue(u.summary, u.summary.startsWith(Listing.UNSIGNED_WARNING) && u.summary.contains("not signed by this shop's key"));
        assertTrue(u.summary, u.summary.contains(NFT) && u.summary.contains("fee 1.5") && u.summary.contains("~ \"Print\": price 2, stock 3\n  → \"Print\": price 2.5, stock 3"));
        assertFalse(u.listing.toString().contains("AAAA"));             // compacted like a signed one
        // the same rules as a signed file: this shop key only, no NFT for nothing
        String other = "0x" + "EF".repeat(64);
        assertThrows(IllegalArgumentException.class, () -> Listing.prepare(shop(other, "Minima", null, "2.5"), new FakeCrypto(), ME, id -> null));
        assertThrows(IllegalArgumentException.class, () -> Listing.prepare(plain.replace("\"price\":\"10\"", "\"price\":\"0\""), new FakeCrypto(), ME, id -> null));
        // a file that claims a signature must carry a good one: a bad one is refused, never taken as unsigned
        String forged = new JSONObject(plain).put("listingSealed", "sealed:" + other + ":" + plain).toString();
        assertThrows(IllegalArgumentException.class, () -> Listing.prepare(forged, new FakeCrypto(), ME, id -> null));
        Listing.Prepared s = Listing.prepare(new JSONObject(plain).put("listingSealed", "sealed:" + ME + ":" + signedText(plain, 1)).toString(), new FakeCrypto(), ME, id -> null);
        assertTrue(s.signed);
        assertFalse(s.summary.contains(Listing.UNSIGNED_WARNING));
        assertEquals("Listing in use: signed by your shop key.", MainActivity.listingInUse("1"));
        assertEquals("Listing in use: an unsigned file you confirmed (not signed by your shop key).", MainActivity.listingInUse("0"));
        assertEquals(MainActivity.listingInUse("0"), MainActivity.listingInUse(""));   // stored before the mark existed
    }

    /** What Studio signs: the listing with its kind and when it was signed (delta review L-c). */
    static String signedText(String plain, long signedAt) throws Exception {
        return new JSONObject(plain).put("kind", "minimall-listing-v1").put("signedAt", signedAt).toString();
    }

    @Test public void theSummaryShowsOneLinePerEntryAndDiffsAgainstTheSignedShop() throws Exception {
        String evil = new JSONObject(shop(ME, "Minima", null, "2.5")).toString()
                .replace("\"name\":\"Print\"", "\"name\":\"Print\\n• \\\"Gift\\\" token " + NFT + ": price 0, stock 9\\u202E\"");
        JSONObject l = Listing.compact(evil, ME);
        String s = Listing.summary(l, null);
        assertEquals(s, 3 + 2, s.split("\n• ", -1).length);           // 2 products + 2 deliveries: no injected line
        assertFalse(s.contains("\u202E"));
        assertTrue(Listing.shown("x".repeat(100)).startsWith("x".repeat(60) + "… (100 characters)"));
        // the outer shopId says "other", the signed listing says "art": the diff is against "art"
        String inner = signedText(shop(ME, "Minima", null, "3"), 5);
        String file = new JSONObject(shop(ME, "Minima", null, "3")).put("shopId", "other").put("listingSealed", "sealed:" + ME + ":" + inner).toString();
        JSONObject art = Listing.compact(shop(ME, "Minima", null, "2"), ME);
        java.util.List<String> asked = new java.util.ArrayList<>();
        Listing.Prepared p = Listing.prepare(file, new FakeCrypto(), ME, id -> { asked.add(id); return "art".equals(id) ? art : null; });
        assertEquals(java.util.Collections.singletonList("art"), asked);
        assertTrue(p.summary, p.summary.contains("price 2, stock 3\n  → \"Print\": price 3, stock 3"));
    }

    @Test public void anOlderSignedListingIsImportedOnlyUnderAPlainWarning() throws Exception {
        JSONObject inUse = Listing.compact(signedText(shop(ME, "Minima", null, "2"), 2000), ME);
        assertEquals(2000, inUse.getLong("signedAt"));
        String older = new JSONObject(shop(ME, "Minima", null, "2.5")).put("listingSealed", "sealed:" + ME + ":" + signedText(shop(ME, "Minima", null, "2.5"), 1000)).toString();
        Listing.Prepared p = Listing.prepare(older, new FakeCrypto(), ME, id -> inUse);
        assertTrue(p.older);
        assertTrue(p.summary, p.summary.startsWith(Listing.OLDER_WARNING));
        String newer = new JSONObject(shop(ME, "Minima", null, "2.5")).put("listingSealed", "sealed:" + ME + ":" + signedText(shop(ME, "Minima", null, "2.5"), 3000)).toString();
        assertFalse(Listing.prepare(newer, new FakeCrypto(), ME, id -> inUse).older);
        // an unsigned file's own "signedAt" dates nothing
        String unsigned = new JSONObject(shop(ME, "Minima", null, "2.5")).put("signedAt", 999999).toString();
        Listing.Prepared u = Listing.prepare(unsigned, new FakeCrypto(), ME, id -> inUse);
        assertFalse(u.older); assertFalse(u.listing.has("signedAt"));
    }

    @Test public void thePaymentReadIsBoundedByTheOrder() {
        String a = VENDOR_ADDR, t = "0x00";
        assertEquals("coins relevant:true address:" + a + " tokenid:0x00 depth:12", MainActivity.paymentRead(a, t, 1000, 990, 0, 0));
        assertEquals("from the date: 1 hour = 144 + 100", "coins relevant:true address:" + a + " tokenid:0x00 depth:244", MainActivity.paymentRead(a, t, 1000, 0, 0, 3_600_000));
        assertEquals("never deeper than the chain", "coins relevant:true address:" + a + " tokenid:0x00 depth:1001", MainActivity.paymentRead(a, t, 1000, 0, 0, Long.MAX_VALUE / 2));
        assertNull("no chain height: no read", MainActivity.paymentRead(a, t, 0, 990, 0, 0));
    }
    static final String VENDOR_ADDR = "0x" + "12".repeat(32);
}
