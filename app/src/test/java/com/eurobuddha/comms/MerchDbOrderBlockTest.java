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
}
