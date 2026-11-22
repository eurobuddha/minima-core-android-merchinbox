package com.eurobuddha.merchinbox;

import com.eurobuddha.comms.CommsScanner;
import com.eurobuddha.comms.MerchDb;
import com.eurobuddha.comms.MerchMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * "The vendor cannot be drained" (owner, 2026-11-20). Nothing leaves the vendor's wallet from the Inbox
 * unless every rule below holds, worked out at the moment of sending from fresh reads, for automatic and
 * manual sends alike. There is no override. {@link #reasons} lists every rule that fails (empty = send).
 *
 * 1. A listing: the vendor's own published .shop (vendor key = this Inbox's shop key) for the order's shop.
 * 2. Only what is listed: the order matches the listing (Listing.check) and the line is one listed product,
 *    in the quantity ordered and listed (a piece or a complete collection is 1); never Minima, MxUSD or a
 *    token with decimals (DeliveryGuard).
 * 3. Stock across orders: what was already delivered of the product plus this line never exceeds the listed
 *    stock, and what is in flight plus this line never exceeds what the vendor holds (DeliveryLedger, re-checked
 *    inside the reservation's transaction).
 * 4. Paid in full on chain: the coins counted for this order when they arrived (its ref, its commitment, the
 *    currency's token), each with at least the vendor's minimum confirmations from its own block, read back by id
 *    to confirm it really was on chain then (kept confirmed afterwards, so revenue the vendor spends later still
 *    counts), summing to at least the due amount recomputed from the listing, which is above zero. The vendor's
 *    address is never read: its coin count can grow without bound.
 * 5. Once only: a line already sent, or whose send did not finish, is never sent again here
 *    (DeliveryLedger; the vendor releases an unfinished one only after checking the history).
 * 6. The recipient is the buyer address in the order, a valid Minima address.
 */
final class DeliveryRules {

    /** The default minimum confirmations of a payment coin; the vendor may raise it, never below 1. */
    static final int DEFAULT_MIN_CONF = 3;

    /** The most payment coins one check reads back by id; the others wait for the next check. */
    static final int MAX_COIN_READS = 32;

    /** A coin counted for the order when it arrived (MerchDb paidcoins, or an older paycoin marker), as checked now. */
    static final class PaidCoin {
        static final String CONFIRMED = "CONFIRMED", WAITING = "WAITING", GONE = "GONE", UNREAD = "UNREAD";
        final String coinid; String amount, tokenid; long created; String state = UNREAD;
        PaidCoin(String coinid, String amount, String tokenid, long created) { this.coinid = coinid; this.amount = amount; this.tokenid = tokenid; this.created = created; }
    }

    /** Settles one coin from its read by id ({@code coins coinid:}, null = no answer): it must still be the order's
     *  payment (its ref in port 1, the order's commitment in port 2 or none for an order without a secret, the same
     *  token and amount as when it was counted), with at least {@code minConf} confirmations from its own block. A
     *  coin no longer in the reply never reached them (once reached, the caller keeps it CONFIRMED for good). */
    static void settle(PaidCoin c, JSONObject read, String ref, String nonce, long tip, int minConf) {
        if (PaidCoin.CONFIRMED.equals(c.state)) return;
        if (c.created > 0 && (tip <= 0 || tip - c.created + 1 < minConf)) { c.state = PaidCoin.WAITING; return; }
        JSONArray a = read == null || !read.optBoolean("status", false) ? null : read.optJSONArray("response");
        if (a == null) { c.state = PaidCoin.UNREAD; return; }
        JSONObject k = null;
        for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null && c.coinid.equalsIgnoreCase(a.optJSONObject(i).optString("coinid"))) k = a.optJSONObject(i);
        if (k == null) { c.state = PaidCoin.GONE; return; }
        String h = CommsScanner.statePort(k, 1), cc = CommsScanner.statePort(k, 2), amount = MerchDb.coinAmount(k), token = k.optString("tokenid", "");
        boolean same = h != null && ref.equals(text(h)) && (nonce.isEmpty() ? cc == null : MerchMessage.commitment(nonce).equalsIgnoreCase(cc))
                && (c.tokenid == null || c.tokenid.equalsIgnoreCase(token)) && (c.amount == null || eqAmount(c.amount, amount));
        if (!same) { c.state = PaidCoin.GONE; return; }
        long created;
        try { created = Long.parseLong(k.optString("created", "")); } catch (NumberFormatException e) { created = 0; }
        if (created <= 0 || (c.created > 0 && created != c.created)) { c.state = PaidCoin.GONE; return; }
        c.created = created; c.amount = amount; c.tokenid = token;
        c.state = tip > 0 && tip - created + 1 >= minConf ? PaidCoin.CONFIRMED : PaidCoin.WAITING;
    }

    private static boolean eqAmount(String a, String b) {
        try { return new BigDecimal(a).compareTo(new BigDecimal(b)) == 0; } catch (RuntimeException e) { return false; }
    }

    private DeliveryRules() {}

    /** Everything read for one check. Replies are the node's JSON (null = no answer). */
    static final class Inputs {
        MerchDb.Order order;          // a fresh read
        boolean mismatch;
        boolean deliverable;          // MerchDb.deliverable: recorded paid in full against the listing's due
        boolean conflict;             // MerchDb.conflict: a second, different order came with this ref
        String nonce = "";            // MerchDb.nonce: the order's secret ("" = an older Shop: unverified ref)
        JSONObject listing;           // compact (Listing.compact), or null
        String[] line;                // {name, tokenid, quantity, stateIdx, bundle}
        String lineState = "";        // DeliveryLedger state of this line
        boolean resuming;             // Pandamonium: this line's delivery journal already exists (it only resumes)
        BigDecimal delivered = BigDecimal.ZERO, inflight = BigDecimal.ZERO;
        BigDecimal deliveredOfToken = BigDecimal.ZERO;   // every product of this token in this shop (L1)
        boolean bundleTaken;                             // its complete collection is reserved or sent (L1)
        JSONObject tokenReply, holdingsReply;
        List<PaidCoin> paid;          // the coins counted for this order, settled (null = they could not be read)
        long tip;                     // current chain height
        int minConf = DEFAULT_MIN_CONF;
    }

    static final class Verdict {
        final List<String> reasons = new ArrayList<>();
        String product = "", token = "", listing = "";
        MerchDb.Order order;          // the fresh order row the check used (its buyer address is the recipient)
        BigDecimal qty = BigDecimal.ZERO, stock = BigDecimal.ZERO, held, due, paid = BigDecimal.ZERO;
    }

    /** True when the only things missing are confirmations still to come (and the shortfall they leave): a line
     *  then waits, it has not failed (point release item 3: never a failure notification for it). */
    static boolean onlyWaiting(List<String> reasons) {
        boolean waiting = false;
        for (String r : reasons) {
            if (r.matches("coin .* has [0-9]+ confirmation\\(s\\); [0-9]+ are needed")) waiting = true;
            else if (!r.startsWith("not yet paid in full on chain") && !r.equals("the order is not recorded as paid in full against your listing")) return false;
        }
        return waiting;
    }

    /** Automatic delivery only for an order that came with a secret (2026-11-22): an older Shop's order proves no payment
     *  is its own (its reference is plain text on chain, so anyone can put it in an order of their own), so it waits for
     *  the vendor's own Send, where every rule here still applies. */
    static boolean autoAllowed(String nonce) { return nonce != null && !nonce.isEmpty(); }

    /** Point release item 6: an older-Shop order (no secret) flagged by a stranger's order with its copied ref may be
     *  delivered to the order of record when the evidence says it came first: its order coin's block is earlier than
     *  the block of every coin counted for it (so it existed before its ref was public), and no other order's coins
     *  were recorded, and every other order with this ref has an order coin later than the earliest payment coin. Coins
     *  and orders of unknown block give no evidence. */
    static boolean recordCameFirst(long orderBlock, List<PaidCoin> paid, boolean contendedCoins, List<Long> contenderBlocks) {
        if (orderBlock <= 0 || contendedCoins || paid == null || paid.isEmpty()) return false;
        long earliest = Long.MAX_VALUE;
        for (PaidCoin c : paid) { if (c.created <= 0 || c.created <= orderBlock) return false; earliest = Math.min(earliest, c.created); }
        // m2: every other order with this ref came after the ref was public (its own order coin is later than the
        // earliest payment); one of unknown block gives no evidence
        for (Long b : contenderBlocks) if (b == null || b <= 0 || b <= earliest) return false;
        return true;
    }

    static Verdict reasons(Inputs in) {
        Verdict v = new Verdict();
        MerchDb.Order o = in.order;
        v.order = o;
        String[] line = in.line;
        if (o == null || line == null || line.length < 5) { v.reasons.add("the order or the line could not be read"); return v; }
        String name = line[0], tokenid = line[1];
        boolean piece = !"0".equals(line[3]), bundle = "1".equals(line[4]);
        v.token = tokenid;
        try { v.qty = piece || bundle ? BigDecimal.ONE : new BigDecimal(line[2]); }
        catch (NumberFormatException e) { v.reasons.add("the quantity is not a number: " + line[2]); }

        // 6. recipient
        if (!Util.isValidAddress(o.payaddr)) v.reasons.add("the order's buyer address is not a valid Minima address: " + Listing.cap(o.payaddr, 120));
        if (in.mismatch) v.reasons.add("the order names a token that is not its currency's");
        if (!MerchMessage.validRef(o.ref)) v.reasons.add("the order's reference is not one the Shop makes: " + Listing.cap(o.ref, 70));
        if (!in.deliverable) v.reasons.add("the order is not recorded as paid in full against your listing");
        if (in.conflict) v.reasons.add("two buyers sent orders with this reference: nothing is sent for it");

        // 1 + 2. listing, and only what is listed
        if (in.listing == null) {
            v.reasons.add("no listing: nothing can be sent until the .shop you published for this shop is in the Inbox");
        } else {
            Listing.Check c = Listing.check(in.listing, o);
            v.reasons.addAll(c.problems);
            try { v.due = new BigDecimal(c.due); } catch (NumberFormatException e) { v.due = BigDecimal.ZERO; }
            v.listing = Listing.id(in.listing);
            int idx; try { idx = Integer.parseInt(line[3]); } catch (NumberFormatException e) { idx = -1; }
            JSONObject p = Listing.product(in.listing, name, tokenid, idx, bundle ? 1 : 0);
            if (p == null) v.reasons.add("\"" + name + "\" (token " + tokenid + ") is not one product in your listing");
            else {
                v.stock = piece || bundle ? BigDecimal.ONE : BigDecimal.valueOf(Math.max(1, p.optInt("maxUnits", 10)));
                v.product = o.shopId + "|" + tokenid.toLowerCase(java.util.Locale.ROOT) + "|" + idx + "|" + (bundle ? 1 : 0);
            }
        }
        String bad = DeliveryGuard.basic(tokenid);
        if (bad != null) v.reasons.add(bad);
        else {
            String t = DeliveryGuard.token(tokenid, in.tokenReply);
            if (t != null) v.reasons.add(t);
            else if ((piece || bundle) && !StateNft.isStateNftScript(script(in.tokenReply)))   // L2
                v.reasons.add("token " + tokenid + " is not a StateNFT collection, so it has no editions to send");
        }

        // 3 + 5. stock across orders, holding, once only (a resumed Pandamonium journal is its own reservation)
        v.held = DeliveryGuard.heldAmount(tokenid, in.holdingsReply);
        if (!in.resuming) {
            if (DeliveryLedger.RESERVED.equals(in.lineState)) v.reasons.add("this line's send did not finish and may have gone: check your wallet's history first");
            if (DeliveryLedger.SENT.equals(in.lineState)) v.reasons.add("this line was already sent");
            if (bundle && in.deliveredOfToken.signum() > 0) v.reasons.add("pieces of this collection were already delivered or are being sent: the complete collection is no longer whole");
            if (!bundle && in.bundleTaken) v.reasons.add("the complete collection of this token was already delivered or is being sent");
            if (v.stock.signum() > 0 && in.delivered.add(v.qty).compareTo(v.stock) > 0)
                v.reasons.add("you list " + v.stock.toPlainString() + " of this product and " + in.delivered.toPlainString() + " are already delivered or being sent");
            if (v.held == null) v.reasons.add("your holdings of token " + tokenid + " could not be read");
            else if (in.inflight.add(v.qty).compareTo(v.held) > 0)
                v.reasons.add("you hold " + v.held.toPlainString() + " of token " + tokenid + " and " + in.inflight.toPlainString() + " are already being sent");
        }

        // 4. paid in full on chain, with confirmations: the coins counted for this order when they arrived (MerchDb
        //    paidcoins), each confirmed from its own block and read back by id once; never the vendor's address
        String want = MerchMessage.tokenFor(o.currency);
        if (in.tip <= 0) v.reasons.add("the chain height could not be read");
        if (in.paid == null) v.reasons.add("your payment coins could not be read");
        else if (want != null) for (PaidCoin c : in.paid) {
            if (c.tokenid != null && !want.equalsIgnoreCase(c.tokenid)) continue;
            switch (c.state) {
                case PaidCoin.CONFIRMED:
                    try { v.paid = v.paid.add(new BigDecimal(c.amount)); }
                    catch (RuntimeException unreadable) {   // never silently 0: said, logged, not counted (M2 b)
                        v.reasons.add("coin " + c.coinid + ": its amount could not be read, so it is not counted");
                        android.util.Log.w("miniMallInbox", "coin " + c.coinid + " amount unreadable: " + c.amount, unreadable);
                    }
                    break;
                case PaidCoin.WAITING:
                    v.reasons.add("coin " + c.coinid + " has " + Math.max(0, in.tip - c.created + 1) + " confirmation(s); " + in.minConf + " are needed");
                    break;
                case PaidCoin.GONE:
                    v.reasons.add("coin " + c.coinid + " is not on chain any more and never reached " + in.minConf + " confirmation(s)");
                    break;
                default:
                    v.reasons.add("coin " + c.coinid + " could not be read");
            }
        }
        if (v.due == null || v.due.signum() <= 0 || v.paid.compareTo(v.due) < 0)
            v.reasons.add("not yet paid in full on chain: " + v.paid.toPlainString() + " of " + (v.due == null ? "an unknown amount" : v.due.toPlainString()) + " due has enough confirmations");
        return v;
    }

    private static String script(JSONObject reply) {
        Object r = reply == null ? null : reply.opt("response");
        if (r instanceof JSONArray) r = ((JSONArray) r).length() == 1 ? ((JSONArray) r).opt(0) : null;
        if (!(r instanceof JSONObject)) return "";
        JSONObject o = (JSONObject) r;
        if (o.optJSONObject("token") != null) return o.optJSONObject("token").optString("script", o.optString("script", ""));
        return o.optString("script", "");
    }

    /** The order ref a payment coin carries in state port 1 (hex of UTF-8), or "". */
    static String text(String hex) {
        if (hex == null) return "";
        String h = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if (h.length() % 2 != 0 || !h.matches("[0-9a-fA-F]*")) return "";
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b)).toString();
        } catch (java.nio.charset.CharacterCodingException e) { return ""; }
    }
}
