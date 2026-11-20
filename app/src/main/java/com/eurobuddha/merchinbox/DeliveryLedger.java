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

    /** Units of a product reserved or sent across all orders. */
    static BigDecimal delivered(SQLiteDatabase db, String product) { return sum(db, product, null, false); }

    /** Units of a token in flight (reserved, not yet known to be sent) across all orders. */
    static BigDecimal inflight(SQLiteDatabase db, String tokenid) { return sum(db, null, tokenid, true); }

    private static BigDecimal sum(SQLiteDatabase db, String product, String tokenid, boolean reservedOnly) {
        BigDecimal t = BigDecimal.ZERO;
        try (Cursor c = db.rawQuery("SELECT v FROM meta WHERE k LIKE 'ledger:%'", null)) {
            while (c.moveToNext()) {
                JSONObject r;
                try { r = new JSONObject(c.getString(0)); } catch (org.json.JSONException e) { continue; }
                String st = r.optString("state", RESERVED);
                if (RELEASED.equals(st) || reservedOnly && !RESERVED.equals(st)) continue;
                if (product != null && !product.equals(r.optString("product"))) continue;
                if (tokenid != null && !tokenid.equalsIgnoreCase(r.optString("token"))) continue;
                try { t = t.add(new BigDecimal(r.optString("qty", "0"))); } catch (NumberFormatException ignored) {}
            }
        }
        return t;
    }

    /** Reserve a line for sending, or the reason it may not be sent (once only, stock across orders, holding). */
    static String reserve(SQLiteDatabase db, String lineKey, String product, String tokenid, BigDecimal qty, BigDecimal stock, BigDecimal held) {
        db.beginTransaction();
        try {
            String st = state(db, lineKey);
            if (RESERVED.equals(st)) return "this line is already being sent, or its send did not finish and may have gone: check your wallet's history first";
            if (SENT.equals(st)) return "this line was already sent";
            BigDecimal done = delivered(db, product);
            if (done.add(qty).compareTo(stock) > 0)
                return "sending " + qty.toPlainString() + " would make " + done.add(qty).toPlainString() + " delivered of this product, more than the " + stock.toPlainString() + " you list";
            BigDecimal moving = inflight(db, tokenid);
            if (held == null || moving.add(qty).compareTo(held) > 0)
                return "you hold " + (held == null ? "an unknown amount" : held.toPlainString()) + " of token " + tokenid + " and " + moving.toPlainString() + " are already being sent";
            put(db, lineKey, product, tokenid, qty, RESERVED);
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

    private static void put(SQLiteDatabase db, String lineKey, String product, String tokenid, BigDecimal qty, String state) throws org.json.JSONException {
        ContentValues v = new ContentValues();
        v.put("k", P + lineKey);
        v.put("v", new JSONObject().put("product", product).put("token", tokenid).put("qty", qty.toPlainString()).put("state", state).toString());
        db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }
}
