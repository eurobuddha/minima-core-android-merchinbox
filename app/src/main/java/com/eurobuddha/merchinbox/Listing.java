package com.eurobuddha.merchinbox;

import com.eurobuddha.comms.MerchDb;
import com.eurobuddha.comms.MerchMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * The vendor's OWN listing (security review 2026-11-20): an order's items, prices, quantities and total are
 * the buyer's own JSON. The vendor imports the .shop file they published (from Studio or the builder); the
 * Inbox keeps a compact copy (no pictures) and recomputes what each order is due from it: every line must be
 * a listed product (same name, NFT token id, edition, bundle), at the listed price, within the listed stock,
 * plus the listed fee of the chosen delivery method. MerchDb.recordPayment then compares the coins with that
 * due amount, never the buyer's figure, and an order with any problem is never delivered.
 */
final class Listing {

    private Listing() {}

    static final class Check {
        final String due; final List<String> problems = new ArrayList<>();
        Check(String due) { this.due = due; }
    }

    private static boolean plain(String v) { return v != null && v.matches("\\d{1,30}(\\.\\d{0,44})?|\\.\\d{1,44}"); }

    /** A compact copy of the vendor's own .shop, or IllegalArgumentException with the plain reason. */
    static JSONObject compact(String shopJson, String myId) {
        JSONObject o;
        try { o = new JSONObject(shopJson); } catch (Exception e) { throw new IllegalArgumentException("That is not a .shop file."); }
        String shopId = o.optString("shopId", ""), vendor = o.optString("vendorPublicId", ""), currency = o.optString("currency", "Minima");
        if (shopId.isEmpty()) throw new IllegalArgumentException("This .shop file has no shop id.");
        if (myId == null || !myId.equalsIgnoreCase(vendor))
            throw new IllegalArgumentException("This .shop file belongs to another vendor:\n" + vendor + "\nYour shop key is\n" + myId);
        String why = MerchMessage.tokenProblem(currency, o.optString("tokenid", ""));
        if (why != null) throw new IllegalArgumentException(why);
        JSONObject out = new JSONObject();
        try {
            out.put("shopId", shopId).put("shopName", o.optString("shopName", "")).put("currency", currency);
            JSONArray products = new JSONArray(), in = o.optJSONArray("products");
            if (in == null || in.length() == 0) throw new IllegalArgumentException("This .shop file lists no products.");
            for (int i = 0; i < in.length(); i++) {
                JSONObject p = in.optJSONObject(i); if (p == null) continue;
                String price = p.optString("price", "");
                if (!plain(price)) throw new IllegalArgumentException("The price of \"" + p.optString("name") + "\" is not a plain number (" + price + ").");
                products.put(new JSONObject().put("name", p.optString("name", "Item")).put("price", price)
                        .put("maxUnits", p.optInt("maxUnits", 10)).put("nftTokenId", p.optString("nftTokenId", ""))
                        .put("nftStateIdx", p.optInt("nftStateIdx", 0)).put("nftBundle", p.optInt("nftBundle", 0) > 0 ? 1 : 0));
            }
            JSONArray shipping = new JSONArray(), sh = o.optJSONArray("shipping");
            if (sh != null) for (int i = 0; i < sh.length(); i++) {
                JSONObject s = sh.optJSONObject(i); if (s == null) continue;
                String fee = s.optString("fee", "0"); if (fee.isEmpty()) fee = "0";
                if (!plain(fee)) throw new IllegalArgumentException("The fee of the delivery method \"" + s.optString("label") + "\" is not a plain number (" + fee + ").");
                shipping.put(new JSONObject().put("id", s.optString("id", "")).put("fee", fee));
            }
            out.put("products", products).put("shipping", shipping);
        } catch (org.json.JSONException e) { throw new IllegalArgumentException("That is not a .shop file."); }
        return out;
    }

    /** What an order is due by the listing, and every way it departs from it (empty = it matches). */
    static Check check(JSONObject listing, MerchDb.Order o) {
        BigDecimal due = BigDecimal.ZERO;
        List<String> problems = new ArrayList<>();
        String want = MerchMessage.tokenFor(listing.optString("currency"));
        if (want == null || !want.equals(MerchMessage.tokenFor(o.currency)))
            problems.add("the order's currency (" + MerchMessage.label(o.currency) + ") is not your shop's (" + MerchMessage.label(listing.optString("currency")) + ")");
        JSONArray items;
        try { items = new JSONArray(o.items == null || o.items.isEmpty() ? "[]" : o.items); } catch (Exception e) { items = null; }
        if (items == null || items.length() == 0) problems.add("the order has no items you can read");
        JSONArray products = listing.optJSONArray("products");
        if (items != null) for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it == null) { problems.add("an item cannot be read"); continue; }
            String name = it.optString("product", ""), token = it.optString("nftTokenId", "");
            int idx = it.optInt("nftStateIdx", 0), bundle = it.optInt("nftBundle", 0) > 0 ? 1 : 0;
            JSONObject match = null; int matches = 0;
            for (int j = 0; products != null && j < products.length(); j++) {
                JSONObject p = products.optJSONObject(j);
                if (p != null && name.equals(p.optString("name")) && token.equalsIgnoreCase(p.optString("nftTokenId"))
                        && idx == p.optInt("nftStateIdx") && bundle == p.optInt("nftBundle")) { match = p; matches++; }
            }
            String what = "\"" + name + "\"" + (token.isEmpty() ? "" : " (token " + token + (idx > 0 ? " #" + idx : "") + ")");
            if (match == null) { problems.add(what + " is not in your listing"); continue; }
            if (matches > 1) { problems.add(what + " is listed more than once"); continue; }
            Object q = it.opt("quantity");
            BigDecimal qty;
            try { qty = new BigDecimal(String.valueOf(q)); } catch (Exception e) { qty = BigDecimal.ZERO; }
            if (qty.signum() <= 0 || qty.stripTrailingZeros().scale() > 0) { problems.add(what + ": the quantity " + q + " is not a whole number above zero"); continue; }
            if (qty.compareTo(BigDecimal.valueOf(Math.max(1, match.optInt("maxUnits", 10)))) > 0 || (bundle == 1 || idx > 0) && qty.compareTo(BigDecimal.ONE) != 0) {
                problems.add(what + ": the quantity " + qty.toPlainString() + " is more than you list (" + (bundle == 1 || idx > 0 ? 1 : match.optInt("maxUnits", 10)) + ")"); continue;
            }
            BigDecimal price = new BigDecimal(match.optString("price"));
            BigDecimal stated;
            try { stated = new BigDecimal(it.optString("unitPrice", "")); } catch (Exception e) { stated = null; }
            if (stated == null || stated.compareTo(price) != 0) { problems.add(what + ": the order's price " + it.optString("unitPrice", "(none)") + " is not your price " + price.toPlainString()); continue; }
            due = due.add(price.multiply(qty));
        }
        BigDecimal fee = null;
        JSONArray shipping = listing.optJSONArray("shipping");
        for (int j = 0; shipping != null && j < shipping.length(); j++) {
            JSONObject s = shipping.optJSONObject(j);
            if (s != null && s.optString("id").equals(o.shipping)) fee = new BigDecimal(s.optString("fee", "0"));
        }
        if (fee == null && (shipping == null || shipping.length() == 0) && (o.shipping == null || o.shipping.isEmpty() || "digital".equals(o.shipping))) fee = BigDecimal.ZERO;
        if (fee == null) problems.add("the delivery method \"" + o.shipping + "\" is not in your listing");
        else due = due.add(fee);
        Check c = new Check(due.toPlainString());
        c.problems.addAll(problems);
        return c;
    }
}
