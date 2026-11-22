package com.eurobuddha.merchinbox;

import static org.junit.Assert.*;

import com.eurobuddha.comms.MerchDb;
import com.eurobuddha.comms.Opened;

import org.json.JSONObject;
import org.junit.Test;

/** 2026-11-22 (P4): "Deliver to the order of record" states only what is known: the block of this order, of its
 *  earliest payment and of every other order, the coins whole and one tap away, and that an order mined before the
 *  payment can still be a copy made from the unmined payment, so the vendor confirms with the buyer first. */
public class CameFirstWordingTest extends Poc2Test {
    static final String ORDER_COIN = "0x" + "C1".repeat(32), PAY_COIN = "0x" + "E1".repeat(32), OTHER_COIN = "0x" + "C2".repeat(32);

    @Test public void theOrderScreenSaysOnlyWhatIsKnown() throws Exception {
        call("routeIncoming", new Class<?>[]{String.class, Opened.class, JSONObject.class}, ORDER_COIN, opened(msg("0xBUYER", BUYER, "", "Piece", "10")),
                new JSONObject().put("coinid", ORDER_COIN).put("created", tip - 8));
        payments.add(coin(PAY_COIN, null));
        db.recordPayment(REF, PAY_COIN, "10", "0x00", null, tip - 5);
        call("routeIncoming", new Class<?>[]{String.class, Opened.class, JSONObject.class}, OTHER_COIN, opened(msg("0xSTRANGER", STRANGER_ADDR, "", "Piece", "10")),
                new JSONObject().put("coinid", OTHER_COIN).put("created", tip - 2));
        assertTrue(db.conflict(db.order(REF)));
        assertTrue((Boolean) call("recordCameFirst", new Class<?>[]{MerchDb.Order.class}, db.order(REF)));
        String all = String.join("\n", InboxShowsIdentifiersWholeTest.texts((android.view.View) call("buildOrderDetail", new Class<?>[]{String.class}, REF)));
        assertFalse(all, all.contains("before its reference was public") || all.contains("made the reference public"));
        assertTrue(all, all.contains("in block " + (tip - 8) + " and the earliest payment counted for it is in block " + (tip - 5)));
        assertTrue(all, all.contains("a copy made from it can be mined before it") && all.contains("Confirm with the buyer"));
        assertTrue(all, all.contains("Earliest payment coin (block " + (tip - 5) + ")") && all.contains(PAY_COIN.toLowerCase(java.util.Locale.ROOT)));
        assertTrue(all, all.contains("Other order's coin (block " + (tip - 2) + ")") && all.contains(OTHER_COIN));
        String help = MainActivity.DELIVER_TO_RECORD_HELP;
        assertFalse(help, help.contains("made the reference public"));
        assertTrue(help, help.contains("can still be a copy made from the payment while it waited to be mined") && help.contains("confirm with the buyer"));
    }
}
