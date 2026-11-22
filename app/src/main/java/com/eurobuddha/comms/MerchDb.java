package com.eurobuddha.comms;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Local store for miniMall (both Shop and Inbox use it). Orders dedup on {@code ref}; chat dedups on
 * {@code randomid}; {@code meta} holds scan bookmarks + identity. Implements {@link CommsScanner.MetaStore}.
 */
public class MerchDb extends SQLiteOpenHelper implements CommsScanner.MetaStore {

    private static final String DB = "minima_merch.db";
    private static final int VERSION = 3;   // v3: + sender on chat rows (delta review M-2)
    private static final String ORD = "orders", CHAT = "order_chat", META = "meta";

    // statuses
    public static final String PENDING = "PENDING", PAID = "PAID", CONFIRMED = "CONFIRMED",
            SHIPPED = "SHIPPED", DELIVERED = "DELIVERED", UNDERPAID = "UNDERPAID", WRONG_TOKEN = "WRONG_TOKEN",
            INQUIRY = "INQUIRY",
            /** Status kept by orders stored before the separate mismatch flag ({@link #mismatch}); not set any more. */
            TOKEN_MISMATCH = "TOKEN_MISMATCH";

    public MerchDb(Context ctx) { super(ctx, DB, null, VERSION); }

    public static class Order {
        public long id;
        public String ref, role, counterparty, counterpartyname, payaddr, shopId, shopName;
        public String items, product, amount, currency, tokenid, shipping, delivery, message;
        public String status, coinid, paidAmount, paidTokenid;
        public boolean paid, read;
        public long date;
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + ORD + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, ref TEXT UNIQUE, role TEXT, counterparty TEXT," +
                "counterpartyname TEXT, payaddr TEXT, shopid TEXT, shopname TEXT, items TEXT, product TEXT, amount TEXT, currency TEXT," +
                "tokenid TEXT, shipping TEXT, delivery TEXT, message TEXT, status TEXT, coinid TEXT," +
                "paidamount TEXT, paidtokenid TEXT, paid INTEGER DEFAULT 0, date INTEGER, read INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + CHAT + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, ref TEXT, incoming INTEGER, message TEXT," +
                "randomid TEXT UNIQUE, date INTEGER, read INTEGER DEFAULT 0, sender TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + META + " (k TEXT PRIMARY KEY, v TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        for (String col : new String[]{"shopid TEXT", "shopname TEXT"}) {
            try { db.execSQL("ALTER TABLE " + ORD + " ADD COLUMN " + col); } catch (Exception ignored) {}
        }
        try { db.execSQL("ALTER TABLE " + CHAT + " ADD COLUMN sender TEXT"); } catch (Exception ignored) {}
    }

    /** One-time repair (security review 2026-11-20): versions before 0.5.10 set paid=1 for any recorded coin,
     *  even an UNDERPAID or WRONG_TOKEN one. Those rows are not paid. Runs once per database (meta flag). */
    @Override public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        if (db.isReadOnly()) return;
        // Rows stored with the old TOKEN_MISMATCH status keep the flag even if the status changes (review L3).
        boolean flagged;
        try (Cursor c = db.rawQuery("SELECT v FROM " + META + " WHERE k='migrated:mismatch-flag'", null)) { flagged = c.moveToFirst(); }
        if (!flagged) {
            db.beginTransaction();
            try {
                db.execSQL("INSERT OR REPLACE INTO " + META + " (k, v) SELECT 'mismatch:' || ref, '1' FROM " + ORD + " WHERE status='" + TOKEN_MISMATCH + "'");
                db.execSQL("INSERT OR REPLACE INTO " + META + " (k, v) VALUES ('migrated:mismatch-flag', '1')");
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        }
        try (Cursor c = db.rawQuery("SELECT v FROM " + META + " WHERE k='migrated:paid-only-on-paid'", null)) { if (c.moveToFirst()) return; }
        db.beginTransaction();
        try {
            db.execSQL("UPDATE " + ORD + " SET paid=0 WHERE status IN ('" + UNDERPAID + "','" + WRONG_TOKEN + "')");
            ContentValues f = new ContentValues(); f.put("k", "migrated:paid-only-on-paid"); f.put("v", "1");
            db.insertWithOnConflict(META, null, f, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    // ----- orders -----

    /** Insert an incoming order/inquiry (dedup on ref; never overwrites an existing one's status).
     *  The token an order is paid in follows from its currency ({@link MerchMessage#tokenFor}), never from the token id
     *  it states: the row holds the currency's token ("" when the currency is not payable), and an ORDER whose stated
     *  token id disagrees, or whose currency is not payable, is kept with a mismatch flag ({@link #mismatch}) that
     *  lives apart from the status, so no status change can make it deliverable. */
    public boolean upsertOrder(MerchMessage m, String role, String coinid) { return upsertOrder(m, role, coinid, 0); }

    /** As above, with the block of the coin that carried the order (0 = unknown), kept with a new row in the same
     *  transaction ("orderblock:"), so it is there before any payment is judged against it. */
    public boolean upsertOrder(MerchMessage m, String role, String coinid, long orderBlock) {
        String want = MerchMessage.tokenFor(m.currency);
        boolean mismatch = MerchMessage.ORDER.equals(m.type) && MerchMessage.tokenProblem(m.currency, m.tokenid) != null;
        ContentValues v = new ContentValues();
        v.put("ref", m.ref); v.put("role", role);
        // the "other party": for a seller that's the order's sender (buyer); for a buyer it's the recipient (vendor)
        v.put("counterparty", "seller".equals(role) ? m.from : m.to);
        v.put("counterpartyname", m.fromname); v.put("payaddr", m.buyerPayaddr);
        v.put("shopid", m.shopId); v.put("shopname", m.shopName);
        v.put("items", m.items); v.put("product", m.product); v.put("amount", m.amount);
        v.put("currency", m.currency); v.put("tokenid", want == null ? "" : want); v.put("shipping", m.shipping);
        v.put("delivery", m.delivery); v.put("message", m.message);
        v.put("status", MerchMessage.INQUIRY.equals(m.type) ? INQUIRY : PENDING); v.put("coinid", coinid);
        v.put("date", m.date > 0 ? m.date : System.currentTimeMillis()); v.put("read", 0);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long id = db.insertWithOnConflict(ORD, null, v, SQLiteDatabase.CONFLICT_IGNORE);
            if (id != -1 && mismatch) {   // only with the row it belongs to: a later message with the same ref changes nothing
                ContentValues f = new ContentValues(); f.put("k", "mismatch:" + m.ref); f.put("v", "1");
                db.insertWithOnConflict(META, null, f, SQLiteDatabase.CONFLICT_REPLACE);
            }
            if (id != -1 && orderBlock > 0) {   // when the order reached the chain: bounds the payment read (L-a) and P3
                ContentValues f = new ContentValues(); f.put("k", "orderblock:" + m.ref); f.put("v", String.valueOf(orderBlock));
                db.insertWithOnConflict(META, null, f, SQLiteDatabase.CONFLICT_REPLACE);
            }
            if (id != -1 && MerchMessage.validNonce(m.nonce)) {   // the order's secret, kept with the row it came with
                ContentValues f = new ContentValues(); f.put("k", "nonce:" + m.ref); f.put("v", m.nonce);
                db.insertWithOnConflict(META, null, f, SQLiteDatabase.CONFLICT_REPLACE);
            }
            if (id == -1 && "seller".equals(role) && MerchMessage.ORDER.equals(m.type)) {
                // A second, different ORDER with a ref already stored (a ref is public once paid; delta review H-1).
                // Stored order with a secret: a different order with a secret of its own is kept beside it (with its
                // commitment), never flagged by its arrival alone; one with no secret is ignored. Stored order with no
                // secret (an older Shop): the ref is flagged as before, and the newcomer is kept beside it.
                Order old = order(m.ref);
                if (old != null && orderBlock > 0 && eq(old.counterparty, m.from) && eq(old.items, m.items) && eq(old.payaddr, m.buyerPayaddr)
                        && eq(getMeta("nonce:" + m.ref, ""), MerchMessage.validNonce(m.nonce) ? m.nonce : "")) {
                    // The same order re-sent by its own signer (r3 I7): the earliest copy's coin and block are the order's,
                    // whichever copy was read first (pages are newest first). Never moved by another sender.
                    long had = 0; try { had = Long.parseLong(getMeta("orderblock:" + m.ref, "0")); } catch (NumberFormatException ignored) {}
                    if (had <= 0 || orderBlock < had) {
                        ContentValues f = new ContentValues(); f.put("k", "orderblock:" + m.ref); f.put("v", String.valueOf(orderBlock));
                        db.insertWithOnConflict(META, null, f, SQLiteDatabase.CONFLICT_REPLACE);
                        ContentValues c = new ContentValues(); c.put("coinid", coinid);
                        db.update(ORD, c, "ref=?", new String[]{m.ref});
                    }
                }
                else if (old != null && (!eq(old.counterparty, m.from) || !eq(old.items, m.items) || !eq(old.payaddr, m.buyerPayaddr))) {
                    String oldNonce = getMeta("nonce:" + m.ref, "");
                    boolean secret = MerchMessage.validNonce(m.nonce) && !m.nonce.equalsIgnoreCase(oldNonce);
                    // shown to the vendor (L-B); the ref is flagged only by an order not kept yet: the same coin read again
                    // after the vendor resolved the conflict does not flag it again (final review F2)
                    if (addContender(db, m.ref, contender(m, coinid, secret ? m.nonce : "")) && oldNonce.isEmpty()) {
                        ContentValues f = new ContentValues(); f.put("k", "conflict:" + m.ref); f.put("v", "1");
                        db.insertWithOnConflict(META, null, f, SQLiteDatabase.CONFLICT_REPLACE);
                    }
                }
            }
            db.setTransactionSuccessful();
            return id != -1;
        } finally { db.endTransaction(); }
    }

    /** An order whose stated token id is not its currency's token, or whose currency is not payable: its NFTs are
     *  never sent, whatever its status becomes. Rows stored before this flag carry the TOKEN_MISMATCH status. */
    public boolean mismatch(Order o) {
        return o != null && (TOKEN_MISMATCH.equals(o.status) || "1".equals(getMeta("mismatch:" + o.ref, "")));
    }

    public List<Order> orders() {
        return queryOrders("SELECT * FROM " + ORD + " ORDER BY date DESC", null);
    }

    public Order order(String ref) {
        List<Order> l = queryOrders("SELECT * FROM " + ORD + " WHERE ref=?", new String[]{ref});
        return l.isEmpty() ? null : l.get(0);
    }

    public void setStatus(String ref, String status) {
        ContentValues v = new ContentValues(); v.put("status", status);
        getWritableDatabase().update(ORD, v, "ref=?", new String[]{ref});
    }

    public void markRead(String ref) {
        ContentValues v = new ContentValues(); v.put("read", 1);
        getWritableDatabase().update(ORD, v, "ref=?", new String[]{ref});
    }

    public int unreadCount() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + ORD + " WHERE read=0", null);
        try { return c.moveToFirst() ? c.getInt(0) : 0; } finally { c.close(); }
    }

    /**
     * Record one payment coin (security review 2026-11-20). Coins of the order CURRENCY's token
     * ({@link MerchMessage#tokenFor}; the stored token id is not trusted) for this ref add up, exactly, each
     * coin id counted once (as the desktop's recordPayment does): the order is PAID, and only then locked, once
     * their sum reaches what is due ({@link #due}) and that is above zero. A coin of another token never pays
     * and never blocks a later honest payment (a third party can stamp any ref on a tiny coin). Status moves
     * from PENDING / UNDERPAID / WRONG_TOKEN only, never back from PAID or a vendor-advanced status.
     * Returns the outcome (PAID / UNDERPAID / WRONG_TOKEN), or null when nothing was counted: no such order,
     * already PAID, or this coin counted before.
     */
    public String recordPayment(String ref, String coinid, String paidAmount, String paidTokenid) {
        return recordPayment(ref, coinid, paidAmount, paidTokenid, null);
    }

    /** As above; {@code coinCommitment} is the coin's state port 2. An order that came with a secret
     *  ({@link #nonce}) counts only coins carrying its commitment (MerchMessage.commitment): any other coin with
     *  its ref is not its payment (returns null, nothing counted). */
    public String recordPayment(String ref, String coinid, String paidAmount, String paidTokenid, String coinCommitment) {
        return recordPayment(ref, coinid, paidAmount, paidTokenid, coinCommitment, 0);
    }

    /** As above, with the block the coin was created at (0 = unknown), kept with it so a delivery counts its
     *  confirmations from it and never reads the vendor's address again. The whole record, its reads and every
     *  write, is one transaction (a scan and a vendor action never interleave in it). */
    public synchronized String recordPayment(String ref, String coinid, String paidAmount, String paidTokenid, String coinCommitment, long created) {
        SQLiteDatabase tx = getWritableDatabase();
        tx.beginTransaction();
        try {
            String r = recordPaymentIn(ref, coinid, paidAmount, paidTokenid, coinCommitment, created);
            tx.setTransactionSuccessful();
            return r;
        } finally { tx.endTransaction(); }
    }

    private String recordPaymentIn(String ref, String coinid, String paidAmount, String paidTokenid, String coinCommitment, long created) {
        Order o = order(ref);
        if (o == null || o.paid || coinid == null || coinid.isEmpty()) return null;
        String n = nonce(o), cc = coinCommitment == null ? "" : coinCommitment;
        String counted = "paycoin:" + coinid.toLowerCase(java.util.Locale.ROOT);
        if (!getMeta(counted, "").isEmpty()) return null;
        // A coin carrying a commitment pays ONLY the order with the matching secret; a reference-only order is paid
        // only by coins without one (delta review M-1). A coin committing to an order kept beside this one flags
        // the ref and counts nothing; the vendor then sees both and may make that order the order of record.
        boolean mine = n.isEmpty() ? cc.isEmpty() : MerchMessage.commitment(n).equalsIgnoreCase(cc);
        if (!mine) {
            if (!cc.isEmpty() && (!getMeta("othercommit:" + ref + ":" + cc.toLowerCase(java.util.Locale.ROOT), "").isEmpty()
                    || "1".equals(getMeta("conflictoverflow:" + ref, ""))))
                contendedPayment(o, coinid, paidAmount, paidTokenid, cc, created);
            else if (cc.isEmpty() && !n.isEmpty()) unassignedPayment(ref, coinid, paidAmount, paidTokenid, created);   // L-B
            return null;
        }
        // A coin mined more than 3 blocks before an order with no secret is counted for it (as 2.2.74), and the order keeps
        // one note with the first such coin (2026-11-22): its reference is plain text on chain, so an order made from an
        // older payment's reference may be a copy. The vendor sees the note and confirms with the buyer before sending.
        long ob;
        try { ob = Long.parseLong(getMeta("orderblock:" + ref, "0")); } catch (NumberFormatException e) { ob = 0; }
        if (n.isEmpty() && olderThanOrder(created, ob) && getMeta("olderpay:" + ref, "").isEmpty()) {
            try { setMeta("olderpay:" + ref, new org.json.JSONObject().put("coinid", coinid).put("created", created).put("orderblock", ob).toString()); }
            catch (org.json.JSONException ignored) {}
        }
        String want = MerchMessage.tokenFor(o.currency);
        boolean tokenOk = want != null && want.equalsIgnoreCase(paidTokenid);
        boolean started = want != null && want.equalsIgnoreCase(o.paidTokenid);   // right-token coins already counted
        String result;
        ContentValues v = new ContentValues();
        if (!tokenOk) {
            result = WRONG_TOKEN;
            if (!started) { v.put("paidamount", paidAmount); v.put("paidtokenid", paidTokenid == null ? "" : paidTokenid); }
        } else {
            BigDecimal sum;
            try { sum = new BigDecimal(paidAmount); if (started) sum = sum.add(new BigDecimal(o.paidAmount)); }
            catch (Exception e) { sum = BigDecimal.ZERO; }
            boolean full;   // exact decimals; a due amount that is not above zero is never paid
            try { BigDecimal due = new BigDecimal(due(o)); full = due.signum() > 0 && sum.compareTo(due) >= 0; }
            catch (Exception e) { full = false; }
            result = full ? PAID : UNDERPAID;
            v.put("paid", full ? 1 : 0); v.put("paidamount", sum.toPlainString()); v.put("paidtokenid", paidTokenid);
        }
        boolean movable = o.status == null || o.status.isEmpty() || PENDING.equals(o.status) || WRONG_TOKEN.equals(o.status)
                || (UNDERPAID.equals(o.status) && !WRONG_TOKEN.equals(result));
        if (movable) v.put("status", result);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues m = new ContentValues(); m.put("k", counted); m.put("v", ref);
            db.insertWithOnConflict(META, null, m, SQLiteDatabase.CONFLICT_REPLACE);
            if (v.size() > 0) db.update(ORD, v, "ref=?", new String[]{ref});
            // the coins counted for the order of record, so they move with it on a switch (L-C)
            org.json.JSONArray pc = coinList("paidcoins:" + ref);
            try { pc.put(new org.json.JSONObject().put("coinid", coinid).put("amount", nz(paidAmount)).put("tokenid", nz(paidTokenid)).put("commitment", cc.toLowerCase(java.util.Locale.ROOT)).put("created", created)); }
            catch (org.json.JSONException ignored) {}
            ContentValues pm = new ContentValues(); pm.put("k", "paidcoins:" + ref); pm.put("v", pc.toString());
            db.insertWithOnConflict(META, null, pm, SQLiteDatabase.CONFLICT_REPLACE);
            // An order with a secret, paid in full by its own commitment, is the buyer's who holds that secret: a
            // flag raised by another kept order's coins no longer holds it back (those coins stay recorded).
            if (PAID.equals(result) && !n.isEmpty()) db.delete(META, "k=?", new String[]{"conflict:" + ref});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return result;
    }

    /** How many blocks before its order a payment may be mined before an order with no secret gets the older-payment note. */
    public static final int PAY_BEFORE_ORDER = 3;

    /** A coin of known block mined more than PAY_BEFORE_ORDER blocks before the order's own block (orders stored with no
     *  block, by older versions, are judged as before). */
    public static boolean olderThanOrder(long created, long orderBlock) {
        return created > 0 && orderBlock > 0 && created < orderBlock - PAY_BEFORE_ORDER;
    }

    /** The first coin older than this no-secret order that was counted for it: {coinid, created, orderblock}, or null. */
    public org.json.JSONObject olderPayment(String ref) {
        try { String v = getMeta("olderpay:" + ref, ""); return v.isEmpty() ? null : new org.json.JSONObject(v); } catch (org.json.JSONException e) { return null; }
    }

    /** What is due for an order: the total the Inbox recomputed from the vendor's own listing ("due:" meta),
     *  else the buyer's own figure (an order the Inbox could not check; the order screen says so). */
    public String due(Order o) { String d = getMeta("due:" + o.ref, ""); return d.isEmpty() ? o.amount : d; }

    /** After the due amount changed (a listing was imported or re-read), PAID follows it: an order paid against
     *  an older figure becomes UNDERPAID again (and further coins count) when what was received is now short,
     *  and an UNDERPAID order becomes PAID when it now suffices (review L4). Vendor-advanced statuses are kept. */
    public void reevaluate(String ref) {
        Order o = order(ref);
        if (o == null) return;
        String want = MerchMessage.tokenFor(o.currency);
        if (want == null || !want.equalsIgnoreCase(o.paidTokenid)) return;
        boolean full;
        try { BigDecimal d = new BigDecimal(due(o)); full = d.signum() > 0 && new BigDecimal(o.paidAmount).compareTo(d) >= 0; }
        catch (Exception e) { full = false; }
        if (full == o.paid) return;
        // An order counted by an Inbox before 0.5.30 kept no coin list (and before 0.5.11 an MxUSD payment kept the
        // Minima backing it as its amount): one the vendor already advanced is not flipped back; the others say
        // "upgraded: re-check payment" (point release item 12). A delivery still reads its coins back by id.
        boolean legacy = getMeta("paidcoins:" + ref, "").isEmpty();
        if (!full && legacy && (CONFIRMED.equals(o.status) || SHIPPED.equals(o.status) || DELIVERED.equals(o.status))) return;
        if (!full && legacy && o.paid) setMeta("upgraded:" + ref, "1");
        ContentValues v = new ContentValues();
        v.put("paid", full ? 1 : 0);
        if (full && (PENDING.equals(o.status) || UNDERPAID.equals(o.status) || WRONG_TOKEN.equals(o.status))) v.put("status", PAID);
        if (!full && PAID.equals(o.status)) v.put("status", UNDERPAID);
        getWritableDatabase().update(ORD, v, "ref=?", new String[]{ref});
    }

    /** A second, different ORDER arrived with this order's ref (H1): nothing is sent for it. */
    public boolean conflict(Order o) { return o != null && "1".equals(getMeta("conflict:" + o.ref, "")); }

    /** At most this many other orders are kept beside one ref; later ones are dropped (the ref stays as it is). */
    public static final int MAX_CONTENDERS = 8, MAX_CONTENDED_COINS = 32, MAX_OTHER_COMMITS = 64;

    /** A kept order's due, recomputed from the vendor's listing (the Inbox sets this); null when it cannot be, and with
     *  no hook the order's own stated amount. */
    public interface Dues { String due(String ref, org.json.JSONObject contender); }
    public volatile Dues dues;

    private static org.json.JSONObject contender(MerchMessage m, String coinid, String nonce) {
        org.json.JSONObject c = new org.json.JSONObject();
        try {
            c.put("from", nz(m.from)).put("fromname", nz(m.fromname)).put("payaddr", nz(m.buyerPayaddr)).put("items", nz(m.items))
                    .put("product", nz(m.product)).put("amount", nz(m.amount)).put("currency", nz(m.currency)).put("tokenid", nz(m.tokenid))
                    .put("shipping", nz(m.shipping)).put("delivery", nz(m.delivery)).put("message", nz(m.message))
                    .put("shopId", nz(m.shopId)).put("shopName", nz(m.shopName)).put("date", m.date > 0 ? m.date : System.currentTimeMillis())
                    .put("coinid", nz(coinid)).put("nonce", nonce).put("commitment", nonce.isEmpty() ? "" : MerchMessage.commitment(nonce));
        } catch (org.json.JSONException ignored) {}
        return c;
    }

    /** Keeps another order with this ref; false when the same order was kept already. */
    private boolean addContender(SQLiteDatabase db, String ref, org.json.JSONObject c) {
        org.json.JSONArray a = contenders(ref);
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject x = a.optJSONObject(i);
            if (x != null && x.optString("from").equals(c.optString("from")) && x.optString("items").equals(c.optString("items"))
                    && x.optString("payaddr").equals(c.optString("payaddr")) && x.optString("nonce").equals(c.optString("nonce"))) return false;
        }
        if (a.length() < MAX_CONTENDERS) {   // past 8 kept orders only the commitment is kept (L-A)
            a.put(c);
            ContentValues f = new ContentValues(); f.put("k", "conflictorder:" + ref); f.put("v", a.toString());
            db.insertWithOnConflict(META, null, f, SQLiteDatabase.CONFLICT_REPLACE);
        }
        otherCommit(db, ref, c.optString("commitment"));
        return true;
    }

    /** Remember a kept order's commitment (its own cap, 64); past it, an overflow mark makes any commitment not of the
     *  order of record count as another order's (L-A). */
    private void otherCommit(SQLiteDatabase db, String ref, String commitment) {
        if (commitment.isEmpty()) return;
        long n;
        try (Cursor q = db.rawQuery("SELECT COUNT(*) FROM " + META + " WHERE k LIKE ?", new String[]{"othercommit:" + ref + ":%"})) { n = q.moveToFirst() ? q.getLong(0) : 0; }
        ContentValues g = new ContentValues();
        if (n < MAX_OTHER_COMMITS) { g.put("k", "othercommit:" + ref + ":" + commitment.toLowerCase(java.util.Locale.ROOT)); g.put("v", "1"); }
        else { g.put("k", "conflictoverflow:" + ref); g.put("v", "1"); }
        db.insertWithOnConflict(META, null, g, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private org.json.JSONArray coinList(String key) {
        try { return new org.json.JSONArray(getMeta(key, "[]")); } catch (Exception e) { return new org.json.JSONArray(); }
    }

    /** Coins with this ref and no commitment, at the vendor address, while the order of record has a secret (L-B):
     *  counted for nothing, shown to the vendor. */
    public org.json.JSONArray unassignedCoins(String ref) { return coinList("unassigned:" + ref); }

    private void unassignedPayment(String ref, String coinid, String amount, String tokenid, long created) {
        org.json.JSONArray a = unassignedCoins(ref);
        if (a.length() >= MAX_CONTENDED_COINS) { setMeta("unassignedfull:" + ref, "1"); return; }
        for (int i = 0; i < a.length(); i++) { org.json.JSONObject x = a.optJSONObject(i); if (x != null && coinid.equalsIgnoreCase(x.optString("coinid"))) return; }
        try { a.put(new org.json.JSONObject().put("coinid", coinid).put("amount", nz(amount)).put("tokenid", nz(tokenid)).put("created", created)); } catch (org.json.JSONException ignored) {}
        setMeta("unassigned:" + ref, a.toString());
    }

    /** More coins with this ref arrived than can be kept: no switch is offered and the screen says so (L-A). */
    public boolean contendedCoinsFull(String ref) { return "1".equals(getMeta("conflictpaidfull:" + ref, "")) || "1".equals(getMeta("unassignedfull:" + ref, "")); }

    /** The other orders that arrived with this ref, kept for the vendor to see (sender, buyer address, items whole). */
    public org.json.JSONArray contenders(String ref) {
        try { return new org.json.JSONArray(getMeta("conflictorder:" + ref, "[]")); } catch (Exception e) { return new org.json.JSONArray(); }
    }

    /** Coins at the vendor address whose commitment belongs to an order kept beside this ref (counted for nothing). */
    public org.json.JSONArray contendedCoins(String ref) {
        try { return new org.json.JSONArray(getMeta("conflictpaid:" + ref, "[]")); } catch (Exception e) { return new org.json.JSONArray(); }
    }

    /** A coin committing to another order kept with this ref (or to one past the caps): recorded, counted for nothing.
     *  The ref is flagged when the order of record has no secret (as before), when the commitment is of no kept order,
     *  or when that kept order's coins together reach its own due (from the listing where there is one): a stranger's
     *  tiny coin no longer holds back the order of record (final check M-A). */
    private void contendedPayment(Order o, String coinid, String amount, String tokenid, String commitment, long created) {
        String ref = o.ref, lc = commitment.toLowerCase(java.util.Locale.ROOT);
        org.json.JSONArray a = contendedCoins(ref);
        for (int i = 0; i < a.length(); i++) { org.json.JSONObject x = a.optJSONObject(i); if (x != null && coinid.equalsIgnoreCase(x.optString("coinid"))) return; }
        boolean full = a.length() >= MAX_CONTENDED_COINS;
        if (!full) {
            try { a.put(new org.json.JSONObject().put("coinid", coinid).put("amount", nz(amount)).put("tokenid", nz(tokenid)).put("commitment", lc).put("created", created)); }
            catch (org.json.JSONException ignored) {}
        }
        org.json.JSONObject owner = null;
        org.json.JSONArray cs = contenders(ref);
        for (int i = 0; i < cs.length(); i++) if (lc.equalsIgnoreCase(cs.optJSONObject(i).optString("commitment"))) owner = cs.optJSONObject(i);
        boolean flag = nonce(o).isEmpty() || (owner == null && !full) || (owner != null && paidInFull(ref, owner, a));
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            if (flag) { ContentValues f = new ContentValues(); f.put("k", "conflict:" + ref); f.put("v", "1"); db.insertWithOnConflict(META, null, f, SQLiteDatabase.CONFLICT_REPLACE); }
            ContentValues g = new ContentValues();
            if (full) { g.put("k", "conflictpaidfull:" + ref); g.put("v", "1"); }
            else { g.put("k", "conflictpaid:" + ref); g.put("v", a.toString()); }
            db.insertWithOnConflict(META, null, g, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** True when the coins committing to this kept order, in its currency's token, reach its due (above zero). */
    private boolean paidInFull(String ref, org.json.JSONObject owner, org.json.JSONArray coins) {
        String due;
        try { due = dues == null ? owner.optString("amount") : dues.due(ref, owner); } catch (RuntimeException e) { due = null; }
        String want = MerchMessage.tokenFor(owner.optString("currency"));
        if (due == null || want == null) return false;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < coins.length(); i++) {
            org.json.JSONObject x = coins.optJSONObject(i);
            if (x == null || !owner.optString("commitment").equalsIgnoreCase(x.optString("commitment")) || !want.equalsIgnoreCase(x.optString("tokenid"))) continue;
            try { sum = sum.add(new BigDecimal(x.optString("amount"))); } catch (NumberFormatException ignored) {}
        }
        try { BigDecimal d = new BigDecimal(due); return d.signum() > 0 && sum.compareTo(d) >= 0; } catch (NumberFormatException e) { return false; }
    }

    /** The one order kept beside this ref that every contended coin commits to, or -1 (none, or coins for several):
     *  the only order the vendor may make the order of record, and only while the order of record is unpaid. */
    public int committedContender(Order o) {
        if (o == null || o.paid || contendedCoinsFull(o.ref)) return -1;
        // never once anything was sent or reserved for this ref (a line delivered against the order of record)
        try (Cursor q = getReadableDatabase().rawQuery("SELECT 1 FROM " + META + " WHERE k LIKE ? OR k LIKE ? LIMIT 1",
                new String[]{"nftsent:" + o.ref + ":%", "ledger:" + o.ref + "|%"})) { if (q.moveToFirst()) return -1; }
        org.json.JSONArray coins = contendedCoins(o.ref), cs = contenders(o.ref);
        String commit = null;
        for (int i = 0; i < coins.length(); i++) {
            String c = coins.optJSONObject(i) == null ? "" : coins.optJSONObject(i).optString("commitment");
            if (c.isEmpty() || (commit != null && !commit.equalsIgnoreCase(c))) return -1;
            commit = c;
        }
        if (commit == null) return -1;
        for (int i = 0; i < cs.length(); i++) if (commit.equalsIgnoreCase(cs.optJSONObject(i).optString("commitment"))) return i;
        return -1;
    }

    /** Make the order the payments commit to the order of record (the vendor's action; no free choice): the stored
     *  order is kept beside the ref instead, with the coins counted for it (their markers removed, so a switch back
     *  counts them again: L-C), the flag is cleared, and the new order's coins (contended, or counted for it before)
     *  are counted for it. The check runs inside the write transaction (L-C). The caller rechecks it against the listing. */
    public boolean switchToCommitted(String ref) {
        SQLiteDatabase db = getWritableDatabase();
        org.json.JSONArray replay = new org.json.JSONArray();
        db.beginTransaction();
        try {
            Order o = order(ref);
            int i = committedContender(o);
            if (i < 0) return false;
            org.json.JSONArray cs = contenders(ref);
            org.json.JSONObject c = cs.optJSONObject(i);
            MerchMessage was = new MerchMessage();
            was.ref = ref; was.from = o.counterparty; was.fromname = o.counterpartyname; was.buyerPayaddr = o.payaddr; was.items = o.items;
            was.product = o.product; was.amount = o.amount; was.currency = o.currency; was.tokenid = o.tokenid; was.shipping = o.shipping;
            was.delivery = o.delivery; was.message = o.message; was.shopId = o.shopId; was.shopName = o.shopName; was.date = o.date;
            org.json.JSONObject wasEntry = contender(was, o.coinid, nonce(o));
            org.json.JSONArray counted = coinList("paidcoins:" + ref);
            for (int k = 0; k < counted.length(); k++) {
                org.json.JSONObject x = counted.optJSONObject(k);
                if (x != null) db.delete(META, "k=?", new String[]{"paycoin:" + x.optString("coinid").toLowerCase(java.util.Locale.ROOT)});
            }
            try { wasEntry.put("coins", counted); } catch (org.json.JSONException ignored) {}
            org.json.JSONArray theirs = c.optJSONArray("coins"), contended = contendedCoins(ref);
            for (int k = 0; theirs != null && k < theirs.length(); k++) replay.put(theirs.opt(k));
            for (int k = 0; k < contended.length(); k++) replay.put(contended.opt(k));
            org.json.JSONArray rest = new org.json.JSONArray();
            for (int k = 0; k < cs.length(); k++) if (k != i) rest.put(cs.opt(k));
            ContentValues f = new ContentValues(); f.put("k", "conflictorder:" + ref); f.put("v", rest.toString());
            db.insertWithOnConflict(META, null, f, SQLiteDatabase.CONFLICT_REPLACE);
            db.delete(META, "k=?", new String[]{"othercommit:" + ref + ":" + c.optString("commitment").toLowerCase(java.util.Locale.ROOT)});
            addContender(db, ref, wasEntry);
            ContentValues v = new ContentValues();
            String want = MerchMessage.tokenFor(c.optString("currency"));
            v.put("counterparty", c.optString("from")); v.put("counterpartyname", c.optString("fromname")); v.put("payaddr", c.optString("payaddr"));
            v.put("shopid", c.optString("shopId")); v.put("shopname", c.optString("shopName")); v.put("items", c.optString("items"));
            v.put("product", c.optString("product")); v.put("amount", c.optString("amount")); v.put("currency", c.optString("currency"));
            v.put("tokenid", want == null ? "" : want); v.put("shipping", c.optString("shipping")); v.put("delivery", c.optString("delivery"));
            v.put("message", c.optString("message")); v.put("coinid", c.optString("coinid")); v.put("date", c.optLong("date"));
            v.put("status", PENDING); v.put("paid", 0); v.put("paidamount", ""); v.put("paidtokenid", ""); v.put("read", 0);
            db.update(ORD, v, "ref=?", new String[]{ref});
            ContentValues nn = new ContentValues(); nn.put("k", "nonce:" + ref); nn.put("v", c.optString("nonce"));
            db.insertWithOnConflict(META, null, nn, SQLiteDatabase.CONFLICT_REPLACE);
            if (MerchMessage.tokenProblem(c.optString("currency"), c.optString("tokenid")) != null) {
                ContentValues mm = new ContentValues(); mm.put("k", "mismatch:" + ref); mm.put("v", "1");
                db.insertWithOnConflict(META, null, mm, SQLiteDatabase.CONFLICT_REPLACE);
            } else db.delete(META, "k=?", new String[]{"mismatch:" + ref});
            for (String k : new String[]{"conflict:", "due:", "listingproblem:", "paidcoins:"}) db.delete(META, "k=?", new String[]{k + ref});
            ContentValues z = new ContentValues(); z.put("k", "conflictpaid:" + ref); z.put("v", "[]");
            db.insertWithOnConflict(META, null, z, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        for (int k = 0; k < replay.length(); k++) {
            org.json.JSONObject x = replay.optJSONObject(k);
            if (x != null) recordPayment(ref, x.optString("coinid"), x.optString("amount"), x.optString("tokenid"), x.optString("commitment"), x.optLong("created"));
        }
        return true;
    }

    /** The secret this order came with ("" for orders from Shops before the commitment). */
    public String nonce(Order o) { return o == null ? "" : getMeta("nonce:" + o.ref, ""); }

    private static boolean eq(String a, String b) { return (a == null ? "" : a).equals(b == null ? "" : b); }

    /** True when the due amount was recomputed from the vendor's own listing. */
    public boolean checked(Order o) { return o != null && !getMeta("due:" + o.ref, "").isEmpty(); }

    /** The ways an order departs from the vendor's listing, one per line ("" = none or not checked). */
    public String listingProblems(Order o) { return o == null ? "" : getMeta("listingproblem:" + o.ref, ""); }

    /** Checked again at the moment of sending anything for an order: PAID, not mismatched, received in the
     *  currency's token, and what was received >= what is due > 0 (exact decimals). */
    public boolean deliverable(Order o) {
        if (o == null || !o.paid || mismatch(o) || conflict(o) || !checked(o) || !listingProblems(o).isEmpty()) return false;   // never the buyer's figure (review M2)
        String want = MerchMessage.tokenFor(o.currency);
        if (want == null || !want.equalsIgnoreCase(o.paidTokenid)) return false;
        try { BigDecimal d = new BigDecimal(due(o)); return d.signum() > 0 && new BigDecimal(o.paidAmount).compareTo(d) >= 0; }
        catch (Exception e) { return false; }
    }

    /** What a payment coin carries in its own token: a token coin's {@code tokenamount} (its {@code amount} is the
     *  Minima that backs the token), a Minima coin's {@code amount}. Missing = "0", which never pays. */
    public static String coinAmount(org.json.JSONObject coin) {
        String t = coin.optString("tokenid", MerchMessage.MINIMA_TOKEN);
        return coin.optString(MerchMessage.MINIMA_TOKEN.equals(t) ? "amount" : "tokenamount", "0");
    }

    private List<Order> queryOrders(String sql, String[] args) {
        List<Order> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        try {
            int iRef = c.getColumnIndex("ref"), iRole = c.getColumnIndex("role"),
                iCp = c.getColumnIndex("counterparty"), iCn = c.getColumnIndex("counterpartyname"),
                iPa = c.getColumnIndex("payaddr"), iSid = c.getColumnIndex("shopid"), iSnm = c.getColumnIndex("shopname"),
                iItems = c.getColumnIndex("items"),
                iProd = c.getColumnIndex("product"), iAmt = c.getColumnIndex("amount"),
                iCur = c.getColumnIndex("currency"), iTok = c.getColumnIndex("tokenid"),
                iShip = c.getColumnIndex("shipping"), iDel = c.getColumnIndex("delivery"),
                iMsg = c.getColumnIndex("message"), iSt = c.getColumnIndex("status"),
                iCoin = c.getColumnIndex("coinid"), iPaid = c.getColumnIndex("paid"),
                iPaidA = c.getColumnIndex("paidamount"), iPaidT = c.getColumnIndex("paidtokenid"),
                iDate = c.getColumnIndex("date"), iRead = c.getColumnIndex("read"), iId = c.getColumnIndex("id");
            while (c.moveToNext()) {
                Order o = new Order();
                o.id = c.getLong(iId); o.ref = c.getString(iRef); o.role = c.getString(iRole);
                o.counterparty = c.getString(iCp); o.counterpartyname = nz(c.getString(iCn));
                o.payaddr = nz(c.getString(iPa));
                o.shopId = iSid >= 0 ? nz(c.getString(iSid)) : ""; o.shopName = iSnm >= 0 ? nz(c.getString(iSnm)) : "";
                o.items = nz(c.getString(iItems)); o.product = nz(c.getString(iProd));
                o.amount = nz(c.getString(iAmt)); o.currency = nz(c.getString(iCur)); o.tokenid = nz(c.getString(iTok));
                o.shipping = nz(c.getString(iShip)); o.delivery = nz(c.getString(iDel)); o.message = nz(c.getString(iMsg));
                o.status = nz(c.getString(iSt)); o.coinid = nz(c.getString(iCoin));
                o.paidAmount = nz(c.getString(iPaidA)); o.paidTokenid = nz(c.getString(iPaidT));
                o.paid = c.getInt(iPaid) == 1; o.date = c.getLong(iDate); o.read = c.getInt(iRead) == 1;
                out.add(o);
            }
        } finally { c.close(); }
        return out;
    }

    // ----- chat -----

    public boolean insertChat(String ref, boolean incoming, String message, String randomid, long date) {
        return insertChat(ref, incoming, message, randomid, date, null);
    }

    /** As above, with the public id of whoever sent it (delta review M-2). */
    public boolean insertChat(String ref, boolean incoming, String message, String randomid, long date, String sender) {
        ContentValues v = new ContentValues();
        v.put("ref", ref); v.put("incoming", incoming ? 1 : 0); v.put("message", message); v.put("sender", sender);
        v.put("randomid", randomid); v.put("date", date); v.put("read", incoming ? 0 : 1);
        return getWritableDatabase().insertWithOnConflict(CHAT, null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1;
    }

    public List<String[]> chat(String ref) {
        List<String[]> out = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT incoming,message,date FROM " + CHAT + " WHERE ref=? ORDER BY date ASC", new String[]{ref});
        try { while (c.moveToNext()) out.add(new String[]{String.valueOf(c.getInt(0)), c.getString(1), String.valueOf(c.getLong(2))}); }
        finally { c.close(); }
        return out;
    }

    // ----- meta (MetaStore) -----

    @Override public void setMeta(String k, String v) {
        ContentValues cv = new ContentValues(); cv.put("k", k); cv.put("v", v);
        getWritableDatabase().insertWithOnConflict(META, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    @Override public String getMeta(String k, String def) {
        Cursor c = getReadableDatabase().rawQuery("SELECT v FROM " + META + " WHERE k=?", new String[]{k});
        try { return c.moveToFirst() ? c.getString(0) : def; } finally { c.close(); }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
