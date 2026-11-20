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
    private static final int VERSION = 2;   // v2: + shopid/shopname on orders
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
                "randomid TEXT UNIQUE, date INTEGER, read INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + META + " (k TEXT PRIMARY KEY, v TEXT)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int o, int n) {
        for (String col : new String[]{"shopid TEXT", "shopname TEXT"}) {
            try { db.execSQL("ALTER TABLE " + ORD + " ADD COLUMN " + col); } catch (Exception ignored) {}
        }
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
    public boolean upsertOrder(MerchMessage m, String role, String coinid) {
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
            if (id != -1 && MerchMessage.validNonce(m.nonce)) {   // the order's secret, kept with the row it came with
                ContentValues f = new ContentValues(); f.put("k", "nonce:" + m.ref); f.put("v", m.nonce);
                db.insertWithOnConflict(META, null, f, SQLiteDatabase.CONFLICT_REPLACE);
            }
            if (id == -1 && "seller".equals(role) && MerchMessage.ORDER.equals(m.type)) {
                // A second ORDER with a ref already stored (security review H1: a ref is public once paid). If it is
                // not the same order (buyer key, items, buyer address), the ref is flagged; nothing is sent for it.
                Order old = order(m.ref);
                if (old != null && (!eq(old.counterparty, m.from) || !eq(old.items, m.items) || !eq(old.payaddr, m.buyerPayaddr))) {
                    ContentValues f = new ContentValues(); f.put("k", "conflict:" + m.ref); f.put("v", "1");
                    db.insertWithOnConflict(META, null, f, SQLiteDatabase.CONFLICT_REPLACE);
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
        Order o = order(ref);
        if (o == null || o.paid || coinid == null || coinid.isEmpty()) return null;
        String n = nonce(o);
        if (!n.isEmpty() && (coinCommitment == null || !MerchMessage.commitment(n).equalsIgnoreCase(coinCommitment))) return null;
        String counted = "paycoin:" + coinid.toLowerCase(java.util.Locale.ROOT);
        if (!getMeta(counted, "").isEmpty()) return null;
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
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return result;
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
        ContentValues v = new ContentValues();
        v.put("paid", full ? 1 : 0);
        if (full && (PENDING.equals(o.status) || UNDERPAID.equals(o.status) || WRONG_TOKEN.equals(o.status))) v.put("status", PAID);
        if (!full && PAID.equals(o.status)) v.put("status", UNDERPAID);
        getWritableDatabase().update(ORD, v, "ref=?", new String[]{ref});
    }

    /** A second, different ORDER arrived with this order's ref (H1): nothing is sent for it. */
    public boolean conflict(Order o) { return o != null && "1".equals(getMeta("conflict:" + o.ref, "")); }

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
        ContentValues v = new ContentValues();
        v.put("ref", ref); v.put("incoming", incoming ? 1 : 0); v.put("message", message);
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
