package com.eurobuddha.comms;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;

/**
 * The sealed wire payload for miniMall: an order, an inquiry, a vendor reply/status, or a buyer reply.
 * Only sender-authored fields travel on-chain (sealed into coin state[99]); the local row adds status etc.
 */
public class MerchMessage {

    public static final String ORDER = "ORDER";
    public static final String INQUIRY = "INQUIRY";
    public static final String REPLY = "REPLY";              // vendor -> buyer free text
    public static final String BUYER_REPLY = "BUYER_REPLY";  // buyer -> vendor free text
    public static final String STATUS_UPDATE = "STATUS_UPDATE";

    // ---- the one rule for which token pays a shop or an order (shop, studio and inbox alike) ----
    public static final String MINIMA_TOKEN = "0x00";
    public static final String MXUSD_TOKEN = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    /** The token a currency is paid in: "Minima" pays 0x00, "USDT" or "mxUSDT" (the wire values for MxUSD) pay
     *  MxUSD, any case; anything else null (not payable). A .shop's or an order's own tokenid never decides it. */
    public static String tokenFor(String currency) {
        if ("Minima".equalsIgnoreCase(currency)) return MINIMA_TOKEN;
        if ("USDT".equalsIgnoreCase(currency) || "mxUSDT".equalsIgnoreCase(currency)) return MXUSD_TOKEN;
        return null;
    }

    /** "Minima" or "MxUSD" for a token from {@link #tokenFor}. */
    public static String tokenName(String tokenid) { return MINIMA_TOKEN.equals(tokenid) ? "Minima" : "MxUSD"; }

    /** How a currency is shown to people: "Minima", "MxUSD" for the wire values "USDT"/"mxUSDT", else the value as given.
     *  Display only; the wire value is never rewritten. */
    public static String label(String currency) {
        String t = tokenFor(currency);
        return t != null ? tokenName(t) : currency == null ? "" : currency;
    }

    /** Why a currency and a stated token id cannot be paid together, or null when they agree. A token id that is
     *  absent or empty defers to the currency; one that is present must be the currency's token (hex, any case). */
    public static String tokenProblem(String currency, String tokenid) {
        String t = tokenFor(currency);
        if (t == null) return "This shop asks to be paid in \"" + shown(currency) + "\". miniMall pays in Minima or MxUSD only. Nothing was sent.";
        if (tokenid != null && !tokenid.isEmpty() && !tokenid.equalsIgnoreCase(t))
            return "This shop says it is paid in " + tokenName(t) + " but names another token:\n" + shown(tokenid)
                    + "\n\nNothing was sent. Ask the vendor for a corrected .shop file.";
        return null;
    }

    /** A vendor-supplied value in a message: whole up to 80 characters (a token id is 66), else only its length. */
    private static String shown(String v) {
        if (v == null) return "";
        return v.length() <= 80 ? v : "a value of " + v.length() + " characters";
    }

    public String type = ORDER;
    public String ref = "";
    public String randomid = "";
    public String from = "";        // sender publicId
    public String fromname = "";
    public String to = "";          // recipient publicId
    public long date = 0;

    // ---- order / inquiry ----
    public String items = "";       // JSON array string: [{product,size,quantity,unitPrice,lineTotal}]
    public String product = "";     // human summary, e.g. "Coffee x2, Tea"
    public String amount = "";      // order total, decimal string
    public String currency = "";    // .shop schema value: "Minima" | "USDT" (the latter is shown as MxUSD)
    public String tokenid = "";     // 0x00 | MxUSD id
    public String shipping = "";    // uk | intl | digital
    public String delivery = "";    // postal address or email (private; only the vendor decrypts it)
    public String buyerPayaddr = ""; // buyer's Minima receiving address (for refunds / paying them)
    public String shopId = "";       // which shop this order/inquiry is for (one vendor may run several)
    public String shopName = "";

    // ---- status update ----
    public String status = "";

    // ---- order: a secret only the buyer and the vendor know (security review H1) ----
    public String nonce = "";        // 0x + 64 hex, inside the sealed order; the payment coin carries commitment(nonce) in port 2

    // ---- free text (REPLY / BUYER_REPLY / order note) ----
    public String message = "";

    public byte[] toWire() {
        try {
            JSONObject o = new JSONObject();
            o.put("type", type == null ? ORDER : type);
            o.put("ref", ref); o.put("randomid", randomid);
            o.put("from", from); o.put("fromname", fromname == null ? "" : fromname);
            o.put("to", to); o.put("date", date);
            o.put("message", message == null ? "" : message);
            if (ORDER.equals(type) || INQUIRY.equals(type)) {
                o.put("items", items); o.put("product", product); o.put("amount", amount);
                o.put("currency", currency); o.put("tokenid", tokenid); o.put("shipping", shipping);
                o.put("delivery", delivery); o.put("buyerPayaddr", buyerPayaddr);
                o.put("shopId", shopId); o.put("shopName", shopName);
                if (nonce != null && !nonce.isEmpty()) o.put("nonce", nonce);
            }
            if (STATUS_UPDATE.equals(type)) o.put("status", status);
            return o.toString().getBytes(StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("toWire failed", e);
        }
    }

    /** An order reference as the Shop makes it ("ORD-" or "INQ-" and hex), or any 4 to 64 letters, digits and
     *  dashes; anything else (an empty ref above all) is never accepted (security review C1). */
    public static boolean validRef(String ref) { return ref != null && ref.matches("[A-Za-z0-9-]{4,64}"); }

    /** A buyer's order secret: 0x and 64 hex. */
    public static boolean validNonce(String n) { return n != null && n.matches("0x[0-9a-fA-F]{64}"); }

    /** What a payment coin carries in state port 2 for an order with this secret: 0x + SHA-256 of its bytes, upper
     *  case. Anyone can read it on chain; only the buyer and the vendor (who decrypts the order) know the secret,
     *  so a stranger who copies a payment's ref into an order of their own cannot make that payment theirs. */
    public static String commitment(String nonce) {
        if (!validNonce(nonce)) return "";
        try {
            byte[] in = new byte[32];
            for (int i = 0; i < 32; i++) in[i] = (byte) Integer.parseInt(nonce.substring(2 + 2 * i, 4 + 2 * i), 16);
            StringBuilder sb = new StringBuilder("0x");
            for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(in)) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) { return ""; }
    }

    /** A fresh order secret (SecureRandom). */
    public static String newNonce() {
        byte[] b = new byte[32]; new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder("0x");
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    public static MerchMessage fromWire(byte[] wire) {
        try {
            JSONObject o = new JSONObject(new String(wire, StandardCharsets.UTF_8));
            MerchMessage m = new MerchMessage();
            m.type = o.optString("type", ORDER);
            m.ref = o.optString("ref", ""); m.randomid = o.optString("randomid", "");
            m.from = o.optString("from", ""); m.fromname = o.optString("fromname", "");
            m.to = o.optString("to", ""); m.date = o.optLong("date", 0);
            m.message = o.optString("message", "");
            m.items = o.optString("items", ""); m.product = o.optString("product", "");
            m.amount = o.optString("amount", ""); m.currency = o.optString("currency", "");
            m.tokenid = o.optString("tokenid", ""); m.shipping = o.optString("shipping", "");
            m.delivery = o.optString("delivery", ""); m.buyerPayaddr = o.optString("buyerPayaddr", "");
            m.shopId = o.optString("shopId", ""); m.shopName = o.optString("shopName", "");
            m.nonce = o.optString("nonce", "");
            m.status = o.optString("status", "");
            return m;
        } catch (Exception e) {
            return null;
        }
    }
}
