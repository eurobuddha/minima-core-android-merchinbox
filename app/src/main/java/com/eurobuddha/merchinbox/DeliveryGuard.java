package com.eurobuddha.merchinbox;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;

/**
 * What the Inbox will never send on a buyer's say-so (security review 2026-11-20). An order's lines are the
 * buyer's own JSON: a line naming {@code nftTokenId:"0x00"} × 1000 would otherwise have the vendor send 1000
 * Minima. Every delivery passes these checks first, and each fails closed:
 * never Minima, never MxUSD, only a token the node confirms has 0 decimals, never more than the vendor holds.
 */
final class DeliveryGuard {

    static final String MINIMA = "0x00";
    static final String MXUSD = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    private DeliveryGuard() {}

    /** Refusals that need no node: Minima, MxUSD or a malformed id. Null = ask the node. */
    static String basic(String tokenid) {
        if (tokenid == null || !tokenid.matches("0x[0-9a-fA-F]{64}")) return "the token id is not a token id: " + tokenid;
        if (MINIMA.equalsIgnoreCase(tokenid)) return "an order line names Minima (0x00); Minima is never sent as an NFT";
        if (MXUSD.equalsIgnoreCase(tokenid)) return "an order line names MxUSD (" + MXUSD + "); MxUSD is never sent as an NFT";
        return null;
    }

    /** Why this token may not be delivered, given the node's {@code tokens tokenid:} reply, or null. */
    static String token(String tokenid, JSONObject reply) {
        String b = basic(tokenid);
        if (b != null) return b;
        JSONObject t = record(reply);
        String id = t == null ? "" : t.optString("tokenid", "");
        if (t == null || !tokenid.equalsIgnoreCase(id)) return "the node did not confirm token " + tokenid + "; nothing was sent";
        int decimals;
        try { decimals = Integer.parseInt(String.valueOf(t.opt("decimals")).trim()); }
        catch (NumberFormatException e) { return "the node gave no decimals for token " + tokenid + "; nothing was sent"; }
        if (decimals != 0) return "token " + tokenid + " has " + decimals + " decimals; only whole-unit NFTs are sent";
        return null;
    }

    /** What the vendor holds of a token (the node's {@code coins relevant:true tokenid:} reply), or null if unreadable. */
    static BigDecimal heldAmount(String tokenid, JSONObject reply) {
        JSONArray coins = reply == null || !reply.optBoolean("status", false) ? null : reply.optJSONArray("response");
        if (coins == null) return null;
        BigDecimal have = BigDecimal.ZERO;
        for (int i = 0; i < coins.length(); i++) {
            JSONObject c = coins.optJSONObject(i);
            if (c == null || !tokenid.equalsIgnoreCase(c.optString("tokenid", "")) || c.optBoolean("spent", false)) continue;
            try { have = have.add(new BigDecimal(c.optString("tokenamount", "0"))); } catch (Exception e) { return null; }
        }
        return have;
    }

    /** The token record in a {@code tokens tokenid:} reply: an object, an {token:{…}} envelope, or a one-row list. */
    private static JSONObject record(JSONObject reply) {
        if (reply == null || !reply.optBoolean("status", false)) return null;
        Object r = reply.opt("response");
        if (r instanceof JSONArray) r = ((JSONArray) r).length() == 1 ? ((JSONArray) r).opt(0) : null;
        if (!(r instanceof JSONObject)) return null;
        JSONObject o = (JSONObject) r;
        return o.optJSONObject("token") != null ? o.optJSONObject("token") : o;
    }
}
