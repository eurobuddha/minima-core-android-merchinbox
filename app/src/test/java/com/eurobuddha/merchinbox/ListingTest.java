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
}
