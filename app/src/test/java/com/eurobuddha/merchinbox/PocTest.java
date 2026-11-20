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
}
