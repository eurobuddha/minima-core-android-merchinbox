package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** The Inbox never sends Minima, MxUSD, a token with decimals, or more than the vendor holds, on a buyer's say-so. */
public class DeliveryGuardTest {
    static final String NFT = "0x" + "AB".repeat(32), MXUSD = DeliveryGuard.MXUSD;

    static JSONObject token(String id, Object decimals) throws Exception {
        JSONObject t = new JSONObject().put("tokenid", id).put("name", "Art");
        if (decimals != null) t.put("decimals", decimals);
        return new JSONObject().put("status", true).put("response", t);
    }
    static JSONObject coins(String id, String... amounts) throws Exception {
        JSONArray a = new JSONArray();
        for (String x : amounts) a.put(new JSONObject().put("tokenid", id).put("tokenamount", x).put("amount", "0.000000000000000000000000000000000001"));
        return new JSONObject().put("status", true).put("response", a);
    }

    @Test public void minimaAndMxUsdAreNeverSent() throws Exception {
        assertNotNull(DeliveryGuard.basic("0x00"));
        assertNotNull(DeliveryGuard.basic(MXUSD));
        assertNotNull(DeliveryGuard.basic(MXUSD.toLowerCase()));
        assertNotNull(DeliveryGuard.token(MXUSD, token(MXUSD, 0)));   // even if a node said decimals 0
        assertNotNull(DeliveryGuard.basic("0x1234"));
        assertNull(DeliveryGuard.basic(NFT));
    }

    @Test public void onlyAWholeUnitTokenTheNodeConfirms() throws Exception {
        assertNull(DeliveryGuard.token(NFT, token(NFT, 0)));
        assertNull(DeliveryGuard.token(NFT, token(NFT.toLowerCase(), "0")));
        assertNull(DeliveryGuard.token(NFT, new JSONObject().put("status", true).put("response", new JSONObject().put("token", token(NFT, 0).getJSONObject("response")))));
        assertNotNull(DeliveryGuard.token(NFT, token(NFT, 8)));
        assertNotNull(DeliveryGuard.token(NFT, token(NFT, null)));
        assertNotNull(DeliveryGuard.token(NFT, token("0x" + "CD".repeat(32), 0)));
        assertNotNull(DeliveryGuard.token(NFT, new JSONObject().put("status", false)));
        assertNotNull(DeliveryGuard.token(NFT, null));
    }

    @Test public void neverMoreThanTheVendorHolds() throws Exception {
        assertNull(DeliveryGuard.held(NFT, "2", coins(NFT, "1", "1")));
        assertNotNull(DeliveryGuard.held(NFT, "1000", coins(NFT, "1")));
        assertNotNull(DeliveryGuard.held(NFT, "1", coins("0x" + "CD".repeat(32), "5")));
        assertNotNull(DeliveryGuard.held(NFT, "1.5", coins(NFT, "5")));
        assertNotNull(DeliveryGuard.held(NFT, "0", coins(NFT, "5")));
        assertNotNull(DeliveryGuard.held(NFT, "-1", coins(NFT, "5")));
        assertNotNull(DeliveryGuard.held(NFT, "1", new JSONObject().put("status", false)));
    }
}
