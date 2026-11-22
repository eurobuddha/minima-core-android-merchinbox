package com.eurobuddha.comms;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** 2026-11-22 (r3 I7): an identical order re-sent by its own signer keeps the EARLIEST copy's coin and block, whichever
 *  copy is read first (the node lists newest first); another sender never moves them. */
@RunWith(RobolectricTestRunner.class) @Config(sdk = 33, manifest = Config.NONE)
public class MerchDbOrderBlockTest {
    static final String REF = "ORD-00000000000000B7";
    MerchDb db;
    @Before public void open() { db = new MerchDb(RuntimeEnvironment.getApplication()); }
    @After public void close() { db.close(); }

    static MerchMessage order(String from) throws Exception {
        MerchMessage m = new MerchMessage();
        m.type = MerchMessage.ORDER; m.ref = REF; m.from = from; m.to = "0xME"; m.currency = "Minima"; m.amount = "10";
        m.shopId = "art"; m.shipping = "digital"; m.buyerPayaddr = "0x" + "34".repeat(32);
        m.items = new JSONArray().put(new JSONObject().put("product", "Mug").put("quantity", 1).put("unitPrice", "10")).toString();
        return m;
    }

    @Test public void theEarliestCopyOfTheSameOrderIsTheOrder() throws Exception {
        assertTrue(db.upsertOrder(order("0xBUYER"), "seller", "0xC105", 105));
        assertFalse(db.upsertOrder(order("0xBUYER"), "seller", "0xC101", 101));
        assertEquals("101", db.getMeta("orderblock:" + REF, ""));
        assertEquals("0xC101", db.order(REF).coinid);
        db.upsertOrder(order("0xBUYER"), "seller", "0xC110", 110);
        assertEquals("a later copy changes nothing", "101", db.getMeta("orderblock:" + REF, ""));
        assertEquals("0xC101", db.order(REF).coinid);
        db.upsertOrder(order("0xCOPIER"), "seller", "0xC099", 99);
        assertEquals("never lowered by another sender", "101", db.getMeta("orderblock:" + REF, ""));
        assertEquals("0xC101", db.order(REF).coinid);
    }

    /** Final review F2 (the reviewer's ReviewPolishConflictTest): another sender's order coin read again after the vendor
     *  resolved the conflict ("Deliver to the order of record") does not flag the ref again; a new, different order does. */
    @Test public void aKeptOrderReadAgainDoesNotFlagAResolvedConflict() throws Exception {
        assertTrue(db.upsertOrder(order("0xBUYER"), "seller", "0xC100", 100));
        MerchMessage copy = order("0xCOPIER"); copy.buyerPayaddr = "0x" + "66".repeat(32);
        db.upsertOrder(copy, "seller", "0xC103", 103);
        assertTrue(db.conflict(db.order(REF)));
        db.setMeta("conflict:" + REF, ""); db.setMeta("conflictresolved:" + REF, "orderblock");   // as MainActivity's resolve
        db.upsertOrder(copy, "seller", "0xC103", 103);   // the same coin read again (a re-read page)
        assertFalse("re-read after the resolve", db.conflict(db.order(REF)));
        MerchMessage other = order("0xOTHER"); other.buyerPayaddr = "0x" + "77".repeat(32);
        db.upsertOrder(other, "seller", "0xC104", 104);
        assertTrue("a genuinely new order with the ref flags it again", db.conflict(db.order(REF)));
    }
}
