package com.eurobuddha.comms;

import static org.junit.Assert.*;

import org.junit.Test;

/** The one rule (shared comms, byte-identical in shop, studio and inbox): the currency decides the token. */
public class MerchTokenRuleTest {
    static final String MXUSD = "0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90";

    @Test public void currencyDecidesTheToken() {
        assertEquals("0x00", MerchMessage.tokenFor("Minima"));
        assertEquals("0x00", MerchMessage.tokenFor("MINIMA"));
        assertEquals(MXUSD, MerchMessage.tokenFor("USDT"));
        assertEquals(MXUSD, MerchMessage.tokenFor("mxUSDT"));
        assertEquals(MXUSD, MerchMessage.tokenFor("usdt"));
        assertNull(MerchMessage.tokenFor("USD"));
        assertNull(MerchMessage.tokenFor("MxUSD"));   // not a wire value: the .shop schema says USDT
        assertNull(MerchMessage.tokenFor(""));
        assertNull(MerchMessage.tokenFor(null));
        assertEquals("Minima", MerchMessage.tokenName("0x00"));
        assertEquals("MxUSD", MerchMessage.tokenName(MXUSD));
    }

    @Test public void peopleReadMxUsdNeverTheWireValue() {
        assertEquals("MxUSD", MerchMessage.label("USDT"));
        assertEquals("MxUSD", MerchMessage.label("mxUSDT"));
        assertEquals("Minima", MerchMessage.label("minima"));
        assertEquals("BTC", MerchMessage.label("BTC"));
        assertEquals("", MerchMessage.label(null));
    }

    @Test public void aStatedTokenMustBeTheCurrencysToken() {
        assertNull(MerchMessage.tokenProblem("Minima", null));
        assertNull(MerchMessage.tokenProblem("Minima", ""));
        assertNull(MerchMessage.tokenProblem("Minima", "0X00"));
        assertNull(MerchMessage.tokenProblem("USDT", MXUSD.toLowerCase()));
        String why = MerchMessage.tokenProblem("Minima", MXUSD);
        assertNotNull(why);
        assertTrue("the whole token id is named", why.contains("\n" + MXUSD + "\n"));
        assertNotNull(MerchMessage.tokenProblem("USDT", "0x00"));
        assertNotNull(MerchMessage.tokenProblem("USDT", MXUSD + "00"));
        assertNotNull(MerchMessage.tokenProblem("BTC", null));
        assertTrue(MerchMessage.tokenProblem("x".repeat(500), null).contains("a value of 500 characters"));
    }
}
