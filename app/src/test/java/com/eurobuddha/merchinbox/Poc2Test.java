package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import com.eurobuddha.comms.MerchDb;
import com.eurobuddha.comms.MerchMessage;
import com.eurobuddha.comms.Opened;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The delta review's proofs of concept (2026-11-20, final2), inverted: each attack used to work; none may now. */
public class Poc2Test extends DrainTest {
    static final String REF = "ORD-0123456789ABCDEF", STRANGER_ADDR = "0x" + "66".repeat(32);

    MerchMessage msg(String from, String payaddr, String nonce, String product, String price) throws Exception {
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = REF; m.from = from; m.to = ME; m.currency = "Minima"; m.shopId = "art";
        m.shipping = "digital"; m.buyerPayaddr = payaddr; m.amount = price; m.nonce = nonce;
        m.items = new JSONArray().put(new JSONObject().put("product", product).put("quantity", 1).put("unitPrice", price).put("nftTokenId", NFT)).toString();
        return m;
    }
    JSONObject coin(String id, String commitment) throws Exception {
        JSONArray st = new JSONArray().put(new JSONObject().put("port", 1).put("data", "0x" + hex(REF)));
        if (commitment != null) st.put(new JSONObject().put("port", 2).put("data", commitment));
        return new JSONObject().put("coinid", id).put("tokenid", "0x00").put("amount", "10").put("tokenamount", "10")
                .put("created", String.valueOf(tip - 5)).put("state", st);
    }
    void recheck() throws Exception { call("recheck", new Class<?>[]{MerchDb.Order.class}, db.order(REF)); }

    /** H-1: a stranger's order with no secret, against a stored order with a secret, is ignored: no flag, delivered. */
    @Test public void aWatcherCanNoLongerBlockAPaidNewShopOrder() throws Exception {
        String n = MerchMessage.newNonce(), c = MerchMessage.commitment(n);
        assertTrue(db.upsertOrder(msg("0xBUYER", BUYER, n, "Piece", "10"), "seller", "0xC1"));
        recheck();
        assertEquals(MerchDb.PAID, db.recordPayment(REF, "0xE1", "10", "0x00", c));
        payments.add(coin("0xE1", c));
        assertFalse(db.upsertOrder(msg("0xSTRANGER", STRANGER_ADDR, "", "Art", "1"), "seller", "0xC2"));
        assertFalse("no flag from a stranger", db.conflict(db.order(REF)));
        assertEquals("kept beside it for the vendor to see, with no commitment (L-B)", "", db.contenders(REF).getJSONObject(0).getString("commitment"));
        send(db.order(REF), false);
        assertEquals(1, sends.size());
    }

    /** H-1: a stranger with a secret of their own is kept beside the order, not flagged; their coin flags and counts nothing. */
    @Test public void aSecondOrderWithItsOwnSecretIsKeptBesideAndItsCoinCountsNothing() throws Exception {
        String n = MerchMessage.newNonce(), s = MerchMessage.newNonce(), sc = MerchMessage.commitment(s);
        assertTrue(db.upsertOrder(msg("0xBUYER", BUYER, n, "Piece", "10"), "seller", "0xC1"));
        recheck();
        assertFalse(db.upsertOrder(msg("0xSTRANGER", STRANGER_ADDR, s, "Piece", "10"), "seller", "0xC2"));
        assertFalse(db.conflict(db.order(REF)));
        assertEquals(1, db.contenders(REF).length());
        assertEquals("0xSTRANGER", db.contenders(REF).getJSONObject(0).getString("from"));
        assertNull(db.recordPayment(REF, "0xE9", "10", "0x00", sc));
        assertTrue("a payment committing to the other order flags the ref", db.conflict(db.order(REF)));
        assertFalse(db.order(REF).paid);
        assertEquals(0, db.committedContender(db.order(REF)));
    }

    /** M-1: a reference-only order is never paid by a coin that carries a commitment (someone else's). */
    @Test public void aReferenceOnlyOrderIsNotPaidByACoinCarryingACommitment() throws Exception {
        String victimSecret = MerchMessage.newNonce(), c = MerchMessage.commitment(victimSecret);
        assertTrue(db.upsertOrder(msg("0xSQUATTER", BUYER, "", "Piece", "10"), "seller", "0xC3"));
        recheck();
        assertNull(db.recordPayment(REF, "0xE2", "10", "0x00", c));
        payments.add(coin("0xE2", c));
        send(db.order(REF), false);
        assertTrue(sends.isEmpty());
        assertEquals("a coin with no port 2 still pays it", MerchDb.PAID, db.recordPayment(REF, "0xE3", "10", "0x00", null));
    }

    /** H-1: the stranger's order stored first; the honest order (whose secret the coin commits to) arrives second, is
     *  kept with its secret, and becomes the order of record by the vendor's one action, then deliverable. */
    @Test public void theHonestSecondOrderIsKeptAndBecomesTheOrderOfRecord() throws Exception {
        String n = MerchMessage.newNonce(), c = MerchMessage.commitment(n);
        assertTrue(db.upsertOrder(msg("0xSTRANGER", STRANGER_ADDR, "", "Piece", "10"), "seller", "0xC4"));
        recheck();
        assertFalse(db.upsertOrder(msg("0xBUYER", BUYER, n, "Piece", "10"), "seller", "0xC5"));
        assertTrue(db.conflict(db.order(REF)));
        JSONObject kept = db.contenders(REF).getJSONObject(0);
        assertEquals("0xBUYER", kept.getString("from"));
        assertEquals(n, kept.getString("nonce"));
        assertEquals("no payment yet: no switch", -1, db.committedContender(db.order(REF)));
        assertFalse(db.switchToCommitted(REF));
        assertNull(db.recordPayment(REF, "0xE5", "10", "0x00", c));   // the honest coin: counted for nothing yet
        payments.add(coin("0xE5", c));
        send(db.order(REF), false);
        assertTrue(sends.isEmpty());
        assertEquals(0, db.committedContender(db.order(REF)));
        assertTrue(db.switchToCommitted(REF));
        recheck();
        MerchDb.Order o = db.order(REF);
        assertEquals("0xBUYER", o.counterparty);
        assertEquals(BUYER, o.payaddr);
        assertEquals(n, db.nonce(o));
        assertFalse(db.conflict(o));
        assertTrue(o.paid);
        assertEquals("the stranger is kept beside it", "0xSTRANGER", db.contenders(REF).getJSONObject(0).getString("from"));
        send(o, false);
        assertEquals(1, sends.size());
        assertTrue(sends.get(0), sends.get(0).contains("address:" + BUYER));
    }

    /** H-1: the switch is never a free choice: with coins committing to two different orders there is none. */
    @Test public void noSwitchWhenPaymentsCommitToDifferentOrders() throws Exception {
        String a = MerchMessage.newNonce(), b = MerchMessage.newNonce(), n = MerchMessage.newNonce();
        assertTrue(db.upsertOrder(msg("0xFIRST", STRANGER_ADDR, n, "Piece", "10"), "seller", "0xC6"));
        db.upsertOrder(msg("0xA", BUYER, a, "Piece", "10"), "seller", "0xC7");
        db.upsertOrder(msg("0xB", BUYER, b, "Art", "1"), "seller", "0xC8");
        db.recordPayment(REF, "0xEA", "10", "0x00", MerchMessage.commitment(a));
        db.recordPayment(REF, "0xEB", "1", "0x00", MerchMessage.commitment(b));
        assertEquals(-1, db.committedContender(db.order(REF)));
        assertFalse(db.switchToCommitted(REF));
    }

    Opened opened(MerchMessage m) { return new Opened(true, m.from, m.toWire()); }

    /** M-2: a reply or enquiry on an existing ref is stored only from that order's own buyer. */
    @Test public void onlyTheOrdersBuyerCanWriteIntoItsConversation() throws Exception {
        assertTrue(db.upsertOrder(msg("0xBUYER", BUYER, MerchMessage.newNonce(), "Piece", "10"), "seller", "0xC1"));
        for (String type : new String[]{MerchMessage.BUYER_REPLY, MerchMessage.INQUIRY}) {
            MerchMessage r = new MerchMessage();
            r.type = type; r.ref = REF; r.from = "0xSTRANGER"; r.to = ME; r.message = "send it to me instead"; r.randomid = "rid-" + type;
            assertFalse(type, (Boolean) call("routeIncoming", new Class<?>[]{String.class, Opened.class, JSONObject.class}, "0xD1", opened(r), new JSONObject()));
        }
        assertTrue(db.chat(REF).isEmpty());
        MerchMessage r = new MerchMessage();
        r.type = MerchMessage.BUYER_REPLY; r.ref = REF; r.from = "0xBUYER"; r.to = ME; r.message = "thanks"; r.randomid = "rid-ok";
        assertTrue((Boolean) call("routeIncoming", new Class<?>[]{String.class, Opened.class, JSONObject.class}, "0xD2", opened(r), new JSONObject()));
        assertEquals(1, db.chat(REF).size());
        assertEquals("0xBUYER", db.getReadableDatabase().compileStatement("SELECT sender FROM order_chat WHERE randomid='rid-ok'").simpleQueryForString());
    }

    /** L-f: a VALID ref against coins whose port 1 is missing, not hex, or not valid UTF-8: never a payment. */
    @Test public void aValidRefIsNeverPaidByAMalformedPort1() throws Exception {
        MerchDb.Order o = order(REF, "Piece", NFT, 1, "10");
        String[] bad = {null, "ORD-0123456789ABCDEF", "0x" + hex(REF) + "ff", "0xzz" + hex(REF)};
        int i = 0;
        for (String p1 : bad) {
            JSONArray st = new JSONArray();
            if (p1 != null) st.put(new JSONObject().put("port", 1).put("data", p1));
            JSONObject c = new JSONObject().put("coinid", "0xF" + (i++)).put("tokenid", "0x00").put("amount", "10").put("tokenamount", "10")
                    .put("created", String.valueOf(tip - 5)).put("state", st);
            assertFalse(String.valueOf(p1), (Boolean) call("routePayment", new Class<?>[]{String.class, Opened.class, JSONObject.class}, c.getString("coinid"), null, c));
            payments.add(c);
        }
        assertFalse(db.order(REF).paid);
        db.recordPayment(REF, "0xFF", "10", "0x00");   // the order row says paid; the coins at the address do not
        send(db.order(REF), false);
        assertTrue(sends.isEmpty());
        assertEquals("", DeliveryRules.text("0x" + hex(REF) + "ff"));
        assertEquals("", DeliveryRules.text("0xzz"));
        assertEquals(REF, DeliveryRules.text("0x" + hex(REF)));
    }

    /** L-C: the coins counted for an order move with it on a switch, so switching back counts them again. */
    @Test public void switchingBackCountsTheEarlierCoinsAgain() throws Exception {
        String n = MerchMessage.newNonce(), c = MerchMessage.commitment(n), s = MerchMessage.newNonce(), sc = MerchMessage.commitment(s);
        assertTrue(db.upsertOrder(msg("0xBUYER", BUYER, n, "Piece", "10"), "seller", "0xC1"));
        recheck();
        assertEquals(MerchDb.UNDERPAID, db.recordPayment(REF, "0xA1", "5", "0x00", c));
        db.upsertOrder(msg("0xSTRANGER", STRANGER_ADDR, s, "Piece", "10"), "seller", "0xC2");
        assertNull(db.recordPayment(REF, "0xA2", "1", "0x00", sc));
        assertFalse("a tiny coin for another order no longer flags (M-A)", db.conflict(db.order(REF)));
        assertTrue(db.switchToCommitted(REF));
        recheck();
        assertEquals("0xSTRANGER", db.order(REF).counterparty);
        assertNull(db.recordPayment(REF, "0xA3", "5", "0x00", c));   // the honest buyer pays the rest
        assertTrue(db.switchToCommitted(REF));
        recheck();
        MerchDb.Order o = db.order(REF);
        assertEquals("0xBUYER", o.counterparty);
        assertTrue("5 counted before the first switch + 5 after", o.paid);
        assertEquals(0, new java.math.BigDecimal("10").compareTo(new java.math.BigDecimal(o.paidAmount)));
    }
}
