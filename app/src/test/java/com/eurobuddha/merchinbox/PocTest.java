package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import com.eurobuddha.comms.MerchDb;

import org.json.JSONObject;
import org.junit.Test;
import org.robolectric.shadows.ShadowLooper;

/** The final review's proofs of concept, kept as regression tests: each attack sent the vendor's NFT; none may now. */
public class PocTest extends DrainTest {

    /** An ORDER with ref "" is paid by every vendor coin of the currency that has no state port 1. */
    @Test public void emptyRefOrderIsPaidByTheVendorsOwnCoins() throws Exception {
        MerchDb.Order o = order("", "Piece", "0x" + "CD".repeat(32), 1, "10");
        assertNotNull(o);
        // the vendor's own ordinary Minima coin at the vendor address (change, a transfer...), no state at all
        payments.add(new JSONObject().put("coinid", "0x" + "55".repeat(32)).put("tokenid", "0x00")
                .put("amount", "500").put("tokenamount", "500").put("created", String.valueOf(tip - 1000)));
        send(o, false);   // the manual path: the vendor tapped Send after "Every check passes"
        assertTrue("an empty ref is never paid by the vendor's own coins (C1)", sends.isEmpty());
    }

    /** Same, port 1 present but not hex (e.g. a number from another dapp): also matches ref "". */
    @Test public void emptyRefMatchesNonHexPort1() throws Exception {
        MerchDb.Order o = order("", "Piece", "0x" + "CD".repeat(32), 1, "10");
        payments.add(new JSONObject().put("coinid", "0x" + "56".repeat(32)).put("tokenid", "0x00")
                .put("amount", "500").put("tokenamount", "500").put("created", String.valueOf(tip - 1000))
                .put("state", new org.json.JSONArray().put(new JSONObject().put("port", 1).put("data", "123"))));
        send(o, false);
        assertTrue("a non-hex port 1 never matches (C1)", sends.isEmpty());
    }

    /** Ref squatting: the first ORDER stored for a ref wins (CONFLICT_IGNORE); the victim's later ORDER with the
     *  same ref (the ref is public in the payment coin's state port 1) is dropped and the victim's payment pays
     *  the attacker's order, delivered to the attacker's address. */
    @Test public void refSquatterIsDeliveredTheVictimsPayment() throws Exception {
        // attacker's ORDER (buyerPayaddr = BUYER, which the fake node records) stored first
        MerchDb.Order atk = order("ORD-1234ABCD", "Piece", "0x" + "CD".repeat(32), 1, "10");
        // the victim's real ORDER with the same ref and their own address arrives second
        com.eurobuddha.comms.MerchMessage v = new com.eurobuddha.comms.MerchMessage();
        v.type = "ORDER"; v.ref = "ORD-1234ABCD"; v.from = "0xVICTIM"; v.to = ME; v.currency = "Minima"; v.shopId = "art";
        v.shipping = "digital"; v.buyerPayaddr = "0x" + "56".repeat(32); v.items = atk.items; v.amount = "10";
        assertFalse("victim's order is silently ignored", db.upsertOrder(v, "seller", "0xC1"));
        pay("ORD-1234ABCD", "10", tip - 5, "0xD1");   // the victim pays 10 with the ref
        send(db.order("ORD-1234ABCD"), false);
        assertTrue("two buyers with one ref: nothing is sent until the vendor sorts it out (H1)", sends.isEmpty());
    }

    /** The full fix: an order that came with a secret is paid only by coins carrying its commitment. */
    @Test public void aPaymentWithoutTheOrdersCommitmentPaysNothing() throws Exception {
        com.eurobuddha.comms.MerchMessage m = new com.eurobuddha.comms.MerchMessage();
        String nonce = com.eurobuddha.comms.MerchMessage.newNonce();
        m.type = "ORDER"; m.ref = "ORD-NONCE001"; m.from = "0xBUYER"; m.to = ME; m.currency = "Minima"; m.shopId = "art";
        m.shipping = "digital"; m.buyerPayaddr = BUYER; m.amount = "10"; m.nonce = nonce;
        m.items = new org.json.JSONArray().put(new JSONObject().put("product", "Piece").put("quantity", 1).put("unitPrice", "10").put("nftTokenId", "0x" + "CD".repeat(32))).toString();
        assertTrue(db.upsertOrder(m, "seller", "0xC2"));
        call("recheck", new Class<?>[]{MerchDb.Order.class}, db.order("ORD-NONCE001"));
        assertNull("a coin without the commitment is not this order's payment", db.recordPayment("ORD-NONCE001", "0xE7", "10", "0x00", null));
        payments.add(new JSONObject().put("coinid", "0xE7").put("tokenid", "0x00").put("amount", "10").put("created", String.valueOf(tip - 5))
                .put("state", new org.json.JSONArray().put(new JSONObject().put("port", 1).put("data", "0x" + hex("ORD-NONCE001")))));
        send(db.order("ORD-NONCE001"), false);
        assertTrue(sends.isEmpty());
        String c = com.eurobuddha.comms.MerchMessage.commitment(nonce);
        assertEquals(MerchDb.PAID, db.recordPayment("ORD-NONCE001", "0xE8", "10", "0x00", c));
        payments.clear();
        payments.add(new JSONObject().put("coinid", "0xE8").put("tokenid", "0x00").put("amount", "10").put("created", String.valueOf(tip - 5))
                .put("state", new org.json.JSONArray().put(new JSONObject().put("port", 1).put("data", "0x" + hex("ORD-NONCE001"))).put(new JSONObject().put("port", 2).put("data", c))));
        send(db.order("ORD-NONCE001"), false);
        assertEquals(1, sends.size());
    }
}
