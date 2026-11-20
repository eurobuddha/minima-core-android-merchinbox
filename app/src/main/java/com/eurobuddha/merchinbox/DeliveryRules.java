package com.eurobuddha.merchinbox;

import com.eurobuddha.comms.CommsScanner;
import com.eurobuddha.comms.MerchDb;
import com.eurobuddha.comms.MerchMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * 4. Paid in full on chain: coins of the currency's token at the vendor's own address carrying the order's ref,
 *    each counted for this order only, each with at least the vendor's minimum confirmations and present
 *    (unspent) when read, summing to at least the due amount recomputed from the listing, which is above zero.
 * 5. Once only: a line already sent, or whose send did not finish, is never sent again here
 *    (DeliveryLedger; the vendor releases an unfinished one only after checking the history).
 * 6. The recipient is the buyer address in the order, a valid Minima address.
 */
final class DeliveryRules {

    /** The default minimum confirmations of a payment coin; the vendor may raise it, never below 1. */
    static final int DEFAULT_MIN_CONF = 3;

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
        JSONObject tokenReply, holdingsReply, paymentReply;
        long tip;                     // current chain height
        int minConf = DEFAULT_MIN_CONF;
        Map<String, String> counted = new HashMap<>();   // coin id (lower case) -> the ref it was counted for
    }

    static final class Verdict {
        final List<String> reasons = new ArrayList<>();
        String product = "", token = "";
        BigDecimal qty = BigDecimal.ZERO, stock = BigDecimal.ZERO, held, due, paid = BigDecimal.ZERO;
    }

    static Verdict reasons(Inputs in) {
        Verdict v = new Verdict();
        MerchDb.Order o = in.order;
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
        }

        // 3 + 5. stock across orders, holding, once only (a resumed Pandamonium journal is its own reservation)
        v.held = DeliveryGuard.heldAmount(tokenid, in.holdingsReply);
        if (!in.resuming) {
            if (DeliveryLedger.RESERVED.equals(in.lineState)) v.reasons.add("this line's send did not finish and may have gone: check your wallet's history first");
            if (DeliveryLedger.SENT.equals(in.lineState)) v.reasons.add("this line was already sent");
            if (v.stock.signum() > 0 && in.delivered.add(v.qty).compareTo(v.stock) > 0)
                v.reasons.add("you list " + v.stock.toPlainString() + " of this product and " + in.delivered.toPlainString() + " are already delivered or being sent");
            if (v.held == null) v.reasons.add("your holdings of token " + tokenid + " could not be read");
            else if (in.inflight.add(v.qty).compareTo(v.held) > 0)
                v.reasons.add("you hold " + v.held.toPlainString() + " of token " + tokenid + " and " + in.inflight.toPlainString() + " are already being sent");
        }

        // 4. paid in full on chain, with confirmations
        String want = MerchMessage.tokenFor(o.currency);
        JSONArray coins = in.paymentReply == null || !in.paymentReply.optBoolean("status", false) ? null : in.paymentReply.optJSONArray("response");
        if (in.tip <= 0) v.reasons.add("the chain height could not be read");
        if (coins == null) v.reasons.add("your payment coins could not be read");
        else if (want != null) for (int i = 0; i < coins.length(); i++) {
            JSONObject c = coins.optJSONObject(i);
            if (c == null || !want.equalsIgnoreCase(c.optString("tokenid", "")) || c.optBoolean("spent", false)) continue;
            String h = CommsScanner.statePort(c, 1);   // no port 1, or not hex UTF-8, is never a payment (C1)
            if (o.ref.isEmpty() || h == null || !o.ref.equals(text(h))) continue;
            if (!in.nonce.isEmpty() && !MerchMessage.commitment(in.nonce).equalsIgnoreCase(String.valueOf(CommsScanner.statePort(c, 2)))) continue;   // not this buyer's payment (H1)
            String id = c.optString("coinid", "").toLowerCase(java.util.Locale.ROOT);
            String owner = in.counted.get(id);
            if (owner != null && !owner.isEmpty() && !owner.equals(o.ref)) { v.reasons.add("coin " + id + " was counted for order " + owner); continue; }
            long created;
            try { created = Long.parseLong(c.optString("created", "")); } catch (NumberFormatException e) { created = Long.MAX_VALUE; }
            long conf = in.tip - created + 1;
            if (in.tip <= 0 || conf < in.minConf) { v.reasons.add("coin " + id + " has " + Math.max(0, conf) + " confirmation(s); " + in.minConf + " are needed"); continue; }
            try { v.paid = v.paid.add(new BigDecimal(MerchDb.coinAmount(c))); } catch (NumberFormatException ignored) {}
        }
        if (v.due == null || v.due.signum() <= 0 || v.paid.compareTo(v.due) < 0)
            v.reasons.add("paid on chain with enough confirmations: " + v.paid.toPlainString() + " of " + (v.due == null ? "an unknown amount" : v.due.toPlainString()) + " due");
        return v;
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
