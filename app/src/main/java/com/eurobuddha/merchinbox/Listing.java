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

    /** The listing inside a .shop as the vendor's Studio sealed it to the vendor ("listingSealed": the shop without
     *  pictures, sealed and signed with the shop key, security review M1): returns that plaintext, or throws with
     *  the plain reason. Only the holder of this shop key can make one; a file without it is refused. */
    static String unseal(String shopJson, com.eurobuddha.comms.CryptoProvider crypto, String myId) {
        String blob;
        try { blob = new JSONObject(shopJson).optString("listingSealed", ""); } catch (Exception e) { throw new IllegalArgumentException("That is not a .shop file."); }
        if (blob.isEmpty()) throw new IllegalArgumentException("This .shop file is not signed by your shop key. Export it again from miniMall Studio (it now signs the listing) and import that file.");
        com.eurobuddha.comms.Opened opened;
        try { opened = crypto == null ? null : crypto.open(blob); } catch (Throwable e) { opened = null; }
        if (opened == null || !opened.valid || myId == null || !myId.equalsIgnoreCase(opened.fromPublicId))
            throw new IllegalArgumentException("This .shop file's listing is not signed by your shop key. Export it again from miniMall Studio and import that file.");
        String text = new String(opened.plaintext, java.nio.charset.StandardCharsets.UTF_8);
        // only a text Studio signed AS a listing (delta review L-c): never any other text sealed with this key
        String kind;
        try { kind = new JSONObject(text).optString("kind", ""); } catch (Exception e) { kind = ""; }
        if (!KIND.equals(kind)) throw new IllegalArgumentException("This .shop file's signed text is not a miniMall listing. Export it again from miniMall Studio and import that file.");
        return text;
    }

    static final String KIND = "minimall-listing-v1";

    /** Said above the summary of a .shop that carries no signature (a shop built with the miniMall-core desktop
     *  builder, which holds only the public Vendor Card, or with Studio before 0.3.21). */
    static final String UNSIGNED_WARNING = "⚠ This file is not signed by this shop's key. Be sure it is the .shop you published: every order is checked against what it lists, and what it lists is what may be sent.";

    /** A .shop the vendor is importing: its listing, whether it is signed by this shop key, and the summary to read.
     *  A signed file must carry a valid signature by this shop key (a bad one is refused, never treated as
     *  unsigned); an unsigned one is allowed only through this explicit import, with the warning first. The vendor
     *  key in the file must be this shop key and NFTs priced at 0 are refused either way (compact). */
    static final String OLDER_WARNING = "⚠ This listing was signed BEFORE the one in use: it is an older listing. Use it only if you mean to go back to it.";

    static final class Prepared {
        final JSONObject listing; final boolean signed, older; final String summary;
        Prepared(JSONObject listing, boolean signed, boolean older, String summary) { this.listing = listing; this.signed = signed; this.older = older; this.summary = summary; }
    }

    /** {@code currentFor} gives the listing in use for a shop id: the diff is against the listing of the shop id the
     *  file's own (signed) listing names, never the unsigned outer one (delta review L-b). */
    static Prepared prepare(String shopJson, com.eurobuddha.comms.CryptoProvider crypto, String myId, java.util.function.Function<String, JSONObject> currentFor) {
        boolean signed;
        try { signed = !new JSONObject(shopJson).optString("listingSealed", "").isEmpty(); }
        catch (Exception e) { throw new IllegalArgumentException("That is not a .shop file."); }
        JSONObject listing = compact(signed ? unseal(shopJson, crypto, myId) : shopJson, myId);
        if (!signed) listing.remove("signedAt");   // only a signature dates a listing
        JSONObject current = currentFor.apply(listing.optString("shopId"));
        long at = listing.optLong("signedAt", 0), now = current == null ? 0 : current.optLong("signedAt", 0);
        boolean older = at > 0 && now > 0 && at < now;
        String s = summary(listing, current);
        if (!signed) s = UNSIGNED_WARNING + "\n\n" + s;
        if (older) s = OLDER_WARNING + "\n\n" + s;
        return new Prepared(listing, signed, older, s);
    }

    /** A name or id from a .shop as the summary shows it: no control characters, line breaks or direction overrides
     *  (so one entry cannot pose as several), and at most 60 characters (delta review L-b). */
    static String shown(String s) {
        return cap(s == null ? "" : s.replaceAll("[\\p{Cc}\\p{Zl}\\p{Zp}\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069]", " "), 60);
    }

    /** Which listing this is, for the delivery ledger (point release item 2): when it was signed, else when the
     *  vendor confirmed it; "" for a listing stored before either was kept. */
    static String id(JSONObject listing) {
        if (listing == null) return "";
        if (listing.optLong("signedAt", 0) > 0) return "s" + listing.optLong("signedAt");
        if (listing.optLong("confirmedAt", 0) > 0) return "c" + listing.optLong("confirmedAt");
        return "";
    }

    /** Every product of a compact listing (name, token id whole, price, stock) and its delivery fees, and how it
     *  differs from the current one (null = none), for the vendor to read before "Use this listing". */
    static String summary(JSONObject next, JSONObject current) {
        StringBuilder sb = new StringBuilder("Shop \"" + shown(next.optString("shopName", next.optString("shopId"))) + "\" (" + shown(next.optString("shopId")) + "), paid in " + MerchMessage.label(next.optString("currency")) + "\n");
        java.util.Map<String, String> now = lines(next), before = current == null ? new java.util.LinkedHashMap<>() : lines(current);
        for (String l : now.values()) sb.append("\n• ").append(l);
        if (current != null) {
            StringBuilder d = new StringBuilder();
            for (java.util.Map.Entry<String, String> e : now.entrySet()) {
                String old = before.get(e.getKey());
                if (old == null) d.append("\n+ ").append(e.getValue());
                else if (!old.equals(e.getValue())) d.append("\n~ ").append(old).append("\n  → ").append(e.getValue());
            }
            for (java.util.Map.Entry<String, String> e : before.entrySet()) if (!now.containsKey(e.getKey())) d.append("\n− ").append(e.getValue());
            sb.append("\n\nChanges from the listing in use:").append(d.length() == 0 ? "\nnone" : d.toString());
        }
        return sb.toString();
    }

    private static java.util.Map<String, String> lines(JSONObject l) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        JSONArray p = l.optJSONArray("products");
        for (int i = 0; p != null && i < p.length(); i++) {
            JSONObject x = p.optJSONObject(i); if (x == null) continue;
            String t = x.optString("nftTokenId"), key = x.optString("name") + "|" + t + "|" + x.optInt("nftStateIdx") + "|" + x.optInt("nftBundle");
            m.put(key, "\"" + shown(x.optString("name")) + "\"" + (t.isEmpty() ? "" : " token " + t + (x.optInt("nftStateIdx") > 0 ? " #" + x.optInt("nftStateIdx") : "") + (x.optInt("nftBundle") > 0 ? " (complete collection)" : ""))
                    + ": price " + x.optString("price") + ", stock " + (x.optInt("nftStateIdx") > 0 || x.optInt("nftBundle") > 0 ? 1 : x.optInt("maxUnits", 10)));
        }
        JSONArray s = l.optJSONArray("shipping");
        for (int i = 0; s != null && i < s.length(); i++) {
            JSONObject x = s.optJSONObject(i); if (x == null) continue;
            m.put("ship|" + x.optString("id"), "delivery \"" + shown(x.optString("id")) + "\": fee " + x.optString("fee"));
        }
        return m;
    }

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
                if (!p.optString("nftTokenId", "").isEmpty() && new BigDecimal(price).signum() <= 0)
                    throw new IllegalArgumentException("The NFT \"" + cap(p.optString("name"), 60) + "\" is priced at 0: an NFT is never listed for nothing.");
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
            if (o.optLong("signedAt", 0) > 0) out.put("signedAt", o.optLong("signedAt"));
        } catch (org.json.JSONException e) { throw new IllegalArgumentException("That is not a .shop file."); }
        return out;
    }

    /** A buyer's items JSON, or null: refused unread when over 64 KB or nested deeper than 8 (scanned as text
     *  first, so nothing huge or deep is ever parsed), and any parsing failure at all is null (review M2). */
    static JSONArray items(String raw) {
        if (raw == null || raw.isEmpty()) return new JSONArray();
        if (raw.length() > 64 * 1024) return null;
        int depth = 0; boolean inString = false, escaped = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (inString) { if (escaped) escaped = false; else if (ch == '\\') escaped = true; else if (ch == '"') inString = false; continue; }
            if (ch == '"') inString = true;
            else if (ch == '[' || ch == '{') { if (++depth > 8) return null; }
            else if (ch == ']' || ch == '}') depth--;
        }
        try { return new JSONArray(raw); } catch (Throwable e) { return null; }
    }

    private static List<String> bundledList(JSONArray items) {
        List<String> out = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && it.optInt("nftBundle", 0) > 0) out.add(it.optString("nftTokenId", "").toLowerCase(java.util.Locale.ROOT));
        }
        return out;
    }

    /** A buyer's number, or null unless it is a plain decimal (no exponent) with at most {@code whole} digits
     *  before the point and {@code decimals} after it; checked on the text first, so "1E+999999999" never
     *  becomes a huge number or a huge string (security review, M1). */
    static BigDecimal bounded(String s, int whole, int decimals) {
        if (s == null || s.length() > whole + decimals + 2) return null;
        if (!s.matches("\\d{1," + whole + "}" + (decimals > 0 ? "(\\.\\d{1," + decimals + "})?" : ""))) return null;
        BigDecimal b = new BigDecimal(s);
        return b.scale() < 0 || b.precision() - b.scale() > whole ? null : b;
    }

    /** A buyer-supplied value in a message or a stored note: whole up to n characters, else its first n and its length. */
    static String cap(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "… (" + s.length() + " characters)";
    }

    /** The one listed product a line names (same name, NFT token id, edition, bundle), or null. */
    static JSONObject product(JSONObject listing, String name, String token, int idx, int bundle) {
        JSONArray products = listing.optJSONArray("products");
        JSONObject match = null;
        for (int j = 0; products != null && j < products.length(); j++) {
            JSONObject p = products.optJSONObject(j);
            if (p != null && name.equals(p.optString("name")) && token.equalsIgnoreCase(p.optString("nftTokenId"))
                    && idx == p.optInt("nftStateIdx") && bundle == p.optInt("nftBundle")) {
                if (match != null) return null;
                match = p;
            }
        }
        return match;
    }

    /** What an order is due by the listing, and every way it departs from it (empty = it matches). */
    static Check check(JSONObject listing, MerchDb.Order o) {
        BigDecimal due = BigDecimal.ZERO;
        List<String> problems = new ArrayList<>();
        String want = MerchMessage.tokenFor(listing.optString("currency"));
        if (want == null || !want.equals(MerchMessage.tokenFor(o.currency)))
            problems.add("the order's currency (" + MerchMessage.label(o.currency) + ") is not your shop's (" + MerchMessage.label(listing.optString("currency")) + ")");
        JSONArray items;
        items = items(o.items);
        if (items == null || items.length() == 0) problems.add("the order has no items you can read");
        JSONArray products = listing.optJSONArray("products");
        java.util.Map<JSONObject, BigDecimal> perProduct = new java.util.IdentityHashMap<>();   // all lines of one product together (L6)
        java.util.Set<String> bundled = new java.util.HashSet<>(), pieced = new java.util.HashSet<>();   // a token as a whole collection and as pieces (L1)
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
            String what = "\"" + cap(name, 60) + "\"" + (token.isEmpty() ? "" : " (token " + cap(token, 80) + (idx > 0 ? " #" + idx : "") + ")");
            if (match == null) { problems.add(what + " is not in your listing"); continue; }
            if (matches > 1) { problems.add(what + " is listed more than once"); continue; }
            String q = String.valueOf(it.opt("quantity"));
            BigDecimal qty = bounded(q, 9, 0);   // a buyer's number: never trusted to be small (M1)
            if (qty == null || qty.signum() <= 0) { problems.add(what + ": the quantity " + cap(q, 40) + " is not a whole number from 1 to 999999999"); continue; }
            if (qty.compareTo(BigDecimal.valueOf(Math.max(1, match.optInt("maxUnits", 10)))) > 0 || (bundle == 1 || idx > 0) && qty.compareTo(BigDecimal.ONE) != 0) {
                problems.add(what + ": the quantity " + qty.toPlainString() + " is more than you list (" + (bundle == 1 || idx > 0 ? 1 : match.optInt("maxUnits", 10)) + ")"); continue;
            }
            BigDecimal price = new BigDecimal(match.optString("price"));
            BigDecimal stated = bounded(it.optString("unitPrice", ""), 30, 44);
            if (stated == null || stated.compareTo(price) != 0) { problems.add(what + ": the order's price " + cap(it.optString("unitPrice", "(none)"), 60) + " is not your price " + price.toPlainString()); continue; }
            due = due.add(price.multiply(qty));
            (bundle == 1 ? bundled : pieced).add(token.toLowerCase(java.util.Locale.ROOT));
            BigDecimal together = perProduct.getOrDefault(match, BigDecimal.ZERO).add(qty);
            perProduct.put(match, together);
            int stock = bundle == 1 || idx > 0 ? 1 : Math.max(1, match.optInt("maxUnits", 10));
            if (together.compareTo(BigDecimal.valueOf(stock)) > 0) problems.add(what + ": the order's lines together ask for " + together.toPlainString() + ", more than you list (" + stock + ")");
        }
        for (String t : bundled) if (pieced.contains(t) || java.util.Collections.frequency(bundledList(items), t) > 1)
            problems.add("the order asks for the complete collection of token " + cap(t, 80) + " and for more of the same token");
        BigDecimal fee = null;
        JSONArray shipping = listing.optJSONArray("shipping");
        for (int j = 0; shipping != null && j < shipping.length(); j++) {
            JSONObject s = shipping.optJSONObject(j);
            if (s != null && s.optString("id").equals(o.shipping)) fee = new BigDecimal(s.optString("fee", "0"));
        }
        if (fee == null && (shipping == null || shipping.length() == 0) && (o.shipping == null || o.shipping.isEmpty() || "digital".equals(o.shipping))) fee = BigDecimal.ZERO;
        if (fee == null) problems.add("the delivery method \"" + cap(o.shipping, 60) + "\" is not in your listing");
        else due = due.add(fee);
        Check c = new Check(due.toPlainString());
        c.problems.addAll(problems);
        return c;
    }
}
