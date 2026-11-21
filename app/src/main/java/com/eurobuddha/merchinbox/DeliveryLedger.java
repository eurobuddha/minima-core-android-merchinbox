package com.eurobuddha.merchinbox;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;

import java.math.BigDecimal;

/**
 * What the Inbox has sent or is sending, per order line (security review 2026-11-20, "the vendor cannot be
 * drained"). One row per line key in the Inbox database's meta table: {product, token, qty, state}, state
 * reserved (being sent, or the send did not finish: it may have gone) / sent / released (the vendor checked
 * the history and it was not sent). A line is sent at most once; a product's lines together never exceed its
 * listed stock; lines in flight count against what the vendor holds. {@link #reserve} re-checks all of that
 * inside one transaction, so two sends can never both pass.
 */
final class DeliveryLedger {

    static final String P = "ledger:";
    static final String RESERVED = "reserved", SENT = "sent", RELEASED = "released";

    private DeliveryLedger() {}

    static JSONObject row(SQLiteDatabase db, String lineKey) {
        try (Cursor c = db.rawQuery("SELECT v FROM meta WHERE k=?", new String[]{P + lineKey})) {
            return c.moveToFirst() ? new JSONObject(c.getString(0)) : null;
        } catch (org.json.JSONException e) { return new JSONObject(); }   // unreadable: treated as in flight
    }

    /** "" (never), reserved, sent or released. */
    static String state(SQLiteDatabase db, String lineKey) {
        JSONObject r = row(db, lineKey);
        return r == null ? "" : r.optString("state", RESERVED);
    }

    /** Units of a product reserved or sent across all orders under one listing (point release item 2: Studio exports
     *  a product's stock as what the vendor holds then, so after a re-export the deliveries under the earlier listing
     *  are already gone from that holding and must not count again). Rows from before the listing was recorded never
     *  count here; what is in flight stays bounded by the holding. */
    static BigDecimal delivered(SQLiteDatabase db, String product, String listing) { return totals(db, listing, product, null)[0]; }

    /** Units of a token in flight (reserved, not yet known to be sent) across all orders and listings. */
    static BigDecimal inflight(SQLiteDatabase db, String tokenid) { return totals(db, null, null, tokenid)[1]; }

    /** Units of every product of one token in one shop reserved or sent under one listing, and whether one of them
     *  is the complete collection (review L1: a complete collection uses every piece's stock, and a piece uses the
     *  collection's). */
    static BigDecimal deliveredOfToken(SQLiteDatabase db, String tokenPrefix, String listing) { return totals(db, listing, tokenPrefix, null)[2]; }
    static boolean bundleTaken(SQLiteDatabase db, String tokenPrefix, String listing) { return totals(db, listing, tokenPrefix, null)[3].signum() > 0; }

    /** One pass over the ledger: {delivered of product, in flight of token, delivered of token prefix, bundles of
     *  token prefix}; {@code key} is a product (exact) for [0] and a "shop|token|" prefix for [2] and [3]. */
    static BigDecimal[] totals(SQLiteDatabase db, String listing, String key, String tokenid) {
        BigDecimal[] t = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        try (Cursor c = db.rawQuery("SELECT v FROM meta WHERE k LIKE 'ledger:%'", null)) {
            while (c.moveToNext()) {
                JSONObject r;
                try { r = new JSONObject(c.getString(0)); } catch (org.json.JSONException e) { continue; }
                String st = r.optString("state", RESERVED), product = r.optString("product");
                if (RELEASED.equals(st)) continue;
                BigDecimal q;
                try { q = new BigDecimal(r.optString("qty", "0")); } catch (NumberFormatException e) { continue; }
                if (tokenid != null && RESERVED.equals(st) && tokenid.equalsIgnoreCase(r.optString("token"))) t[1] = t[1].add(q);
                if (key == null || listing == null || !r.has("listing") || !listing.equals(r.optString("listing"))) continue;
                if (product.equals(key)) t[0] = t[0].add(q);
                if (product.startsWith(key)) { t[2] = t[2].add(q); if (product.endsWith("|1")) t[3] = t[3].add(q); }
            }
        }
        return t;
    }

    /** Reserve a line for sending, or the reason it may not be sent (once only, stock across orders, holding). */
    static String reserve(SQLiteDatabase db, String lineKey, String product, String tokenid, BigDecimal qty, BigDecimal stock, BigDecimal held, String listing) {
        db.beginTransaction();
        try {
            String st = state(db, lineKey);
            if (RESERVED.equals(st)) return "this line is already being sent, or its send did not finish and may have gone: check your wallet's history first";
            if (SENT.equals(st)) return "this line was already sent";
            BigDecimal done = delivered(db, product, listing);
            if (done.add(qty).compareTo(stock) > 0)
                return "sending " + qty.toPlainString() + " would make " + done.add(qty).toPlainString() + " delivered of this product, more than the " + stock.toPlainString() + " you list";
            BigDecimal moving = inflight(db, tokenid);
            if (held == null || moving.add(qty).compareTo(held) > 0)
                return "you hold " + (held == null ? "an unknown amount" : held.toPlainString()) + " of token " + tokenid + " and " + moving.toPlainString() + " are already being sent";
            put(db, lineKey, product, tokenid, qty, RESERVED, listing);
            db.setTransactionSuccessful();
            return null;
        } catch (org.json.JSONException e) {
            return "the delivery record could not be written";
        } finally { db.endTransaction(); }
    }

    /** sent, or released (only after the vendor checked the history and the line was not sent). */
    static void mark(SQLiteDatabase db, String lineKey, String state) {
        JSONObject r = row(db, lineKey);
        if (r == null) return;
        try {
            r.put("state", state);
            ContentValues v = new ContentValues(); v.put("k", P + lineKey); v.put("v", r.toString());
            db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        } catch (org.json.JSONException ignored) {}
    }

    private static void put(SQLiteDatabase db, String lineKey, String product, String tokenid, BigDecimal qty, String state, String listing) throws org.json.JSONException {
        ContentValues v = new ContentValues();
        v.put("k", P + lineKey);
        v.put("v", new JSONObject().put("product", product).put("token", tokenid).put("qty", qty.toPlainString()).put("state", state).put("listing", listing == null ? "" : listing).toString());
        db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
