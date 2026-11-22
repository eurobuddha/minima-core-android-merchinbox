package com.eurobuddha.comms;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Scans a target address for coins whose state[99] is a sealed message for me. NEWBLOCK-driven.
 *
 * SAFETY (unchanged from the Mail scanner): a large `coins` reply can take the node down (IPC Binder /
 * 256 KB limits). So this NEVER issues an unbounded query — it always bounds with `depth:`, starts TINY
 * (depth 4), grows only while replies come back safely, probes smaller on an over-limit/dropped reply,
 * and is throttled so block bursts can't hammer the node. A deeper page refused after a smaller one was read is
 * read in older windows (`coinage:`, halved while refused, as the Inbox's "Find this order's payment" does); the
 * bookmark moves only when the whole range was read (2026-11-22). Every page is read back from the node's tip of the
 * moment, so before a pass ends the tip is read again and, if it moved, the blocks it moved by are read too (r3).
 *
 * Generalized for miniMall: the target address + the per-coin handling are injected, so one instance can
 * scan the shared MINIMERCH address (inbox: incoming orders; shop: status/chat) while a {@link Router}
 * decides what each opened message means. Opening with my box key IS the "is this for me?" test.
 */
public final class CommsScanner {

    /** Where scan bookmarks live (so different scanners on different addresses don't collide). */
    public interface MetaStore {
        String getMeta(String k, String def);
        void setMeta(String k, String v);
    }

    /** Handles a message that opened for me (sealed to my box key + signature verified by the scanner). */
    public interface Router {
        /** Return true if this produced a NEW stored item (drives newCount + notifications).
         *  {@code coinid} is the dedup key; {@code coin} is the raw coin (amount/tokenid/created if needed). */
        boolean handle(String coinid, Opened opened, JSONObject coin);
    }

    public interface Listener { void onDone(boolean ok, int newCount); }

    private static final int START_DEPTH = 4;          // every scan's FIRST query is this small + safe
    private static final int MAX_DEPTH = 64;           // grow no deeper than this in one pass
    private static final long MIN_INTERVAL_MS = 4000;  // throttle: don't hammer the node on bursts
    /** Every pass reads this many blocks more than its gap (the orders scan as the payments scan, r3). */
    static final int REREAD_MARGIN = 8;
    /** How many times a pass reads the tip again before it ends partly read (the tip moved on every page). */
    private static final int TIP_CHECKS = 3;

    private final NodeApi node;
    private final CryptoProvider crypto;
    private final MetaStore meta;
    private final String targetAddress;
    private final Router router;
    private final Listener listener;
    private final boolean decrypt;              // true: open state[99]; false: hand every coin raw (payments)
    private final String bmBackfilled, bmTip;   // address-namespaced bookmark keys
    private final String bmWhole;               // every block up to it was read whole or noted unread (r3 I2)
    private final String bmFails;               // passes in a row that ended partly read (the node refused a read)
    private String leadTip;                      // follows(): the bookmark of the scan this one runs after
    private int passTip;                         // what this pass's bookmark will say, once it is read whole
    private int startTip;                        // the bookmark this pass started from
    private int bottom;                          // the lowest block this pass must read: its chainBlock - targetDepth
    private int tipChecks;
    private boolean backfillRun;
    private int read;                            // blocks read back from the tip this pass, whole
    private boolean paging;                      // a deeper page was refused: the rest is read in older windows

    private boolean running = false;
    private boolean tracked = false;
    private boolean grew = false;                // got at least one safe (non-over-limit) page this pass
    private int depth, targetDepth, newThisRun;
    private long lastScanEnd = 0;
    private final Set<String> seenCoins = new HashSet<>();   // skip re-decrypting overlap while growing

    /** Sealed-message scanner: opens state[99] with my box key (decrypt = true). */
    public CommsScanner(NodeApi node, CryptoProvider crypto, MetaStore meta,
                        String targetAddress, Router router, Listener listener) {
        this(node, crypto, meta, targetAddress, router, listener, true);
    }

    /** Raw scanner (decrypt = false): hands EVERY coin to the router — e.g. to watch plaintext payments. */
    public CommsScanner(NodeApi node, CryptoProvider crypto, MetaStore meta,
                        String targetAddress, Router router, Listener listener, boolean decrypt) {
        this.node = node; this.crypto = crypto; this.meta = meta;
        this.targetAddress = targetAddress; this.router = router; this.listener = listener;
        this.decrypt = decrypt;
        String tag = targetAddress.length() > 10 ? targetAddress.substring(2, 10) : targetAddress;
        this.bmBackfilled = "backfilled_" + (decrypt ? "" : "pay_") + tag;
        this.bmTip = tipKey(targetAddress, decrypt);
        this.bmWhole = "readwhole_" + (decrypt ? "" : "pay_") + tag;
        this.bmFails = failsKey(targetAddress, decrypt);
    }

    /** The key of a scanner's tip bookmark: the chain tip its last whole pass read up to. */
    public static String tipKey(String targetAddress, boolean decrypt) {
        return "scanned_tip_" + (decrypt ? "" : "pay_") + (targetAddress.length() > 10 ? targetAddress.substring(2, 10) : targetAddress);
    }

    /** The key of the count of a scanner's passes in a row that ended partly read (0 after a whole pass). */
    public static String failsKey(String targetAddress, boolean decrypt) {
        return "failedpasses_" + (decrypt ? "" : "pay_") + (targetAddress.length() > 10 ? targetAddress.substring(2, 10) : targetAddress);
    }

    /** The prefix of the blocks a scanner did not read and will not read ({@code <prefix><first block>} = last block):
     *  a gap wider than {@link #MAX_DEPTH} (the Inbox was closed, or a block the node never served stayed in the window
     *  until it left it). The Inbox shows them; they are never claimed as read. */
    public static final String UNREAD = "unread_";
    /** Appended to a range's last block when a pass since the last whole one ended partly read: the node refused reads
     *  (without it, the range is only the 64-block window: the Inbox was not reading). */
    public static final String REFUSED = " refused";
    public static String unreadKey(String targetAddress, boolean decrypt) {
        return UNREAD + (decrypt ? "" : "pay_") + (targetAddress.length() > 10 ? targetAddress.substring(2, 10) : targetAddress) + ":";
    }

    /** The Inbox's payment scanner runs after the orders scan of every pass and judges a coin the first time it sees it
     *  (MainActivity.routePayment). A coin whose order is not stored yet is not recorded, so this scanner's bookmark never
     *  passes the lead's ({@code leadTipKey}) and every pass reads its gap plus {@link #REREAD_MARGIN} blocks: such a coin
     *  is read again, while it is unspent, after an order mined a few blocks later is stored. It has no throttle of its
     *  own (the scan it follows has). */
    public void follows(String leadTipKey) { leadTip = leadTipKey; }

    /** How many blocks a pass reads back from the tip: the gap since the bookmark (all of {@link #MAX_DEPTH} on a first
     *  pass or with no tip), plus the margin; never more than MAX_DEPTH. */
    static int targetDepth(boolean backfill, int chainBlock, int scannedTip) {
        if (backfill || chainBlock <= 0) return MAX_DEPTH;
        int gap = Math.max(1, chainBlock - scannedTip + 1);
        return Math.min(MAX_DEPTH, gap + REREAD_MARGIN);
    }

    /** Starts a pass; false when it did not start (a pass is running, or the throttle). */
    public boolean scan(final int chainBlock) {
        if (running) return false;
        if (leadTip == null && System.currentTimeMillis() - lastScanEnd < MIN_INTERVAL_MS) return false;   // throttle
        running = true; grew = false; newThisRun = 0; seenCoins.clear(); read = 0; paging = false; tipChecks = 0;
        backfillRun = meta.getMeta(bmBackfilled, "").isEmpty();
        int scannedTip = parseInt(meta.getMeta(bmTip, "0"));
        startTip = scannedTip;
        targetDepth = targetDepth(backfillRun, chainBlock, scannedTip);
        bottom = chainBlock - targetDepth;
        passTip = leadTip == null ? chainBlock : Math.min(chainBlock, parseInt(meta.getMeta(leadTip, "0")));
        depth = Math.min(START_DEPTH, targetDepth);    // ALWAYS start small — never an unbounded query
        if (tracked) { fetch(chainBlock); return true; }
        node.cmd("coinnotify action:add address:" + targetAddress, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) { tracked = true; fetch(chainBlock); }
            @Override public void onError(String m) { fetch(chainBlock); }
        });
        return true;
    }

    private void fetch(final int chainBlock) {
        // ALWAYS bounded by depth — never an unbounded `coins` (a huge reply can crash the node).
        String cmd = "coins address:" + targetAddress + " order:desc depth:" + depth + (paging ? " coinage:" + read : "");
        node.cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONArray coins = j.optJSONArray("response");
                if (coins == null || !j.optBoolean("status", true)) { overLimit(chainBlock); return; }
                grew = true;
                newThisRun += process(coins);
                int window = depth - read;
                read = depth;                              // every block from the tip down to this depth is read
                if (read < targetDepth) {                  // grow while safe; an older window keeps the size that was served
                    depth = paging ? Math.min(read + window, targetDepth) : Math.min(depth * 2, targetDepth);
                    fetch(chainBlock);
                }
                else checkTip(chainBlock);
            }
            @Override public void onError(String message) {
                if (NodeApi.ERR_NOT_ENABLED.equals(message)) { finishFail(); return; }
                overLimit(chainBlock);
            }
        });
    }

    /** The pages were read back from the node's tip when each was served, which may be above {@code chainBlock}: the
     *  tip is read again and the blocks it is above {@link #bottom} by and were not read yet are read, as an older
     *  window, before the pass ends (r3: a tip that moved k blocks left the lowest k blocks unread while the bookmark
     *  moved). A tip that cannot be read, or that moved on every page, ends the pass partly read. */
    private void checkTip(final int chainBlock) {
        if (chainBlock <= 0) { finish(chainBlock, true); return; }
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                int tip = r == null || !j.optBoolean("status", true) ? 0 : parseInt(r.optString("block", ""));
                if (tip > 0 && tip - bottom <= read) { finish(chainBlock, true); return; }
                if (tip <= 0 || ++tipChecks > TIP_CHECKS) { finish(chainBlock, false); return; }
                targetDepth = tip - bottom; paging = true; depth = targetDepth;
                fetch(chainBlock);
            }
            @Override public void onError(String message) { finish(chainBlock, false); }
        });
    }

    /** A page came back dropped/refused. Probe smaller if we've not safely read anything yet; once a page was read, read
     *  the rest in older windows, halved while refused. A single block refused ends the pass partly read: the bookmark
     *  stays and the next pass reads the range again (a block the node never serves leaves the 64-block window as the
     *  tip moves on, and the bookmark then moves past it with an unread note, never silently). */
    private void overLimit(int chainBlock) {
        if (!grew) {
            if (depth > 1) { depth = Math.max(1, depth / 2); fetch(chainBlock); }
            else finish(chainBlock, false);
            return;
        }
        int half = (depth - read) / 2;
        if (half >= 1) { paging = true; depth = read + half; fetch(chainBlock); }
        else finish(chainBlock, false);
    }

    private void finish(int chainBlock, boolean ok) {
        // Blocks this pass did not reach (a gap over the window) that no earlier pass read whole: the payment scan's
        // bookmark stays at the orders read while each pass reads 64 back, so its own read-whole mark says what it read
        // (r3 I2), and a block it can never read again is noted at once. The lowest block read is named too: never fewer.
        int whole = parseInt(meta.getMeta(bmWhole, "0")), fails = parseInt(meta.getMeta(bmFails, "0"));
        int from = Math.max(startTip, whole) + 1;
        if (ok && !backfillRun && startTip > 0 && bottom - 1 >= from)
            meta.setMeta(unreadKey(targetAddress, decrypt) + from, bottom + (fails > 0 ? REFUSED : ""));
        if (ok && chainBlock > whole) meta.setMeta(bmWhole, String.valueOf(chainBlock));
        if (ok ? fails > 0 : chainBlock > 0) meta.setMeta(bmFails, String.valueOf(ok ? 0 : fails + 1));
        if (ok) meta.setMeta(bmBackfilled, "true");
        if (ok && passTip > 0) meta.setMeta(bmTip, String.valueOf(passTip));   // a failed or partial read moves nothing: read again next pass
        running = false; lastScanEnd = System.currentTimeMillis();
        listener.onDone(ok, newThisRun);
    }

    private void finishFail() { running = false; lastScanEnd = System.currentTimeMillis(); listener.onDone(false, 0); }

    private int process(JSONArray coins) {
        int newCount = 0;
        // The node lists newest first; handle oldest first, so the first order sent with a ref is the one stored (H1).
        for (int i = coins.length() - 1; i >= 0; i--) {
            JSONObject coin = coins.optJSONObject(i);
            if (coin == null) continue;
            String cid = coin.optString("coinid", "");
            if (!cid.isEmpty() && !seenCoins.add(cid)) continue;   // already handled this run (grow overlap)
            if (!decrypt) {                                        // raw mode — payments etc.
                if (router.handle(cid, null, coin)) newCount++;
                continue;
            }
            String blob = statePort(coin, 99);
            if (blob == null) continue;
            Opened o = crypto.open(blob);
            if (o == null || !o.valid) continue;                   // not for me / bad signature
            if (router.handle(cid, o, coin)) newCount++;
        }
        return newCount;
    }

    /** Read a coin state port. State may be an array [{port,data}] or an object {"99":...} per node build. */
    public static String statePort(JSONObject coin, int port) {
        Object st = coin.opt("state");
        if (st instanceof JSONObject) {
            String v = ((JSONObject) st).optString(String.valueOf(port), "");
            return v.isEmpty() ? null : v;
        }
        if (st instanceof JSONArray) {
            JSONArray a = (JSONArray) st;
            for (int i = 0; i < a.length(); i++) {
                JSONObject p = a.optJSONObject(i);
                if (p != null && p.optInt("port", -1) == port) {
                    String v = p.optString("data", "");
                    return v.isEmpty() ? null : v;
                }
            }
        }
        return null;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }
}
