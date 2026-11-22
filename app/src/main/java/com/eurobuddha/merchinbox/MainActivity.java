package com.eurobuddha.merchinbox;

import com.eurobuddha.minimaapi.MinimaAPI;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.goterl.lazysodium.LazySodium;

import org.json.JSONArray;
import org.json.JSONObject;
import com.eurobuddha.comms.CommsIdentity;
import com.eurobuddha.comms.CommsScanner;
import com.eurobuddha.comms.CommsTransport;
import com.eurobuddha.comms.CryptoProvider;
import com.eurobuddha.comms.Hex;
import com.eurobuddha.comms.LocalEcCryptoProvider;
import com.eurobuddha.comms.MailText;
import com.eurobuddha.comms.MerchDb;
import com.eurobuddha.comms.MerchMessage;
import com.eurobuddha.comms.NodeApi;
import com.eurobuddha.comms.QrUtil;
import com.eurobuddha.comms.Sodium;
import com.eurobuddha.minimaapi.MinimaAPIMessages;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** miniMall Inbox — the vendor's native order manager (Maxima-free, on the comms primitive). */
public class MainActivity extends AppCompatActivity {

    private static final String CH = "merchinbox";

    private final ReplyDrafts replyDrafts = new ReplyDrafts();
    private LazySodium ls;
    private NodeApi node;
    private MerchDb db;
    private CryptoProvider crypto;
    private CommsIdentity identity;
    private CommsScanner orderScanner, paymentScanner;
    private String myId, vendorAddr = "", shopName = "";
    private boolean paired = false;
    private int chainBlock = 0;
    private String filterStatus = "ALL";
    private String filterShop = "ALL";
    private ActivityResultLauncher<String> csvLauncher;
    private ActivityResultLauncher<String[]> listingLauncher;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<View> stack = new ArrayDeque<>();
    /** NFT sends in flight, keyed ref:tokenid — blocks double-taps while the node builds the txn. */
    private final java.util.HashSet<String> nftSending = new java.util.HashSet<>();
    /** NFT lines auto-attempted this process, keyed ref:tokenid — a failed auto-send is never looped;
     *  the next app open (autoDeliverSweep) gets exactly one more automatic attempt. */
    private final java.util.HashSet<String> autoTried = new java.util.HashSet<>();
    /** Orders whose payment flipped to PAID during the current scan pass (main thread only). */
    private final java.util.Set<String> pendingAutoDeliver = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private boolean autoSweepDone = false;

    private LinearLayout root;
    private FrameLayout container;
    private View pairingBanner;
    private BroadcastReceiver notifyReceiver;
    private int insetTop = 0, insetBottom = 0;

    // ---- lifecycle ----

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        replyDrafts.restore(b);
        ls = Sodium.get();
        db = new MerchDb(getApplicationContext());   // item 8: the store never holds this Activity
        io.execute(this::tagLedger);   // m3, once: earlier deliveries count under the listing then in use
        io.execute(() -> { autoWasOn = autoDeliverWasOn(); if (autoWasOn) ui.post(this::refreshVisibleOrderOrList); });
        shopName = loadShopName();

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Design.BG);
        pairingBanner = buildPairingBanner();
        pairingBanner.setVisibility(View.GONE);
        container = new FrameLayout(this);
        root.addView(pairingBanner, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(container, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        applyInsets();
        ensureChannel();
        requestNotifPermission();

        csvLauncher = registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"),
                uri -> { if (uri != null) writeCsv(uri); });
        listingLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                uri -> { if (uri != null) importListing(uri); });

        showOrders();
        if (b != null) {
            String orderRef = b.getString("mall.openOrder", "");
            if (!orderRef.isEmpty()) showOrderAsync(orderRef, true);
        }

        node = new NodeApi(this, this::onPaired);
        notifyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    String event = new JSONObject(MinimaAPI.notifyData(i)).optString("event", "");
                    if ("NEWBLOCK".equals(event) || "NEWBALANCE".equals(event)) fetchBlock(MainActivity.this::requestScan);   // the pass reads the new tip
                } catch (Exception ignored) {}
            }
        };
        ContextCompat.registerReceiver(this, notifyReceiver,
                MinimaAPI.notifyFilter(), ContextCompat.RECEIVER_EXPORTED);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        replyDrafts.save(state);
        View top = stack.peek(); Object tag = top == null ? null : top.getTag();
        if (tag instanceof String && ((String) tag).startsWith("order:")) state.putString("mall.openOrder", ((String) tag).substring(6));
        super.onSaveInstanceState(state);
    }

    @Override protected void onDestroy() {
        replyDrafts.close();
        super.onDestroy();
        if (notifyReceiver != null) try { unregisterReceiver(notifyReceiver); } catch (Exception ignored) {}
        if (node != null) node.onDestroy();
        ui.removeCallbacksAndMessages(null);   // item 8: nothing posted earlier runs against a dead screen
        // Drain, never drop: every scanned message and payment already queued is stored, the scan bookmarks after
        // them; the store closes last and keeps no reference to this Activity.
        try { io.execute(() -> { db.dues = null; db.close(); }); } catch (java.util.concurrent.RejectedExecutionException ignored) {}
        io.shutdown();
    }

    @Override public void onBackPressed() { if (stack.size() > 1) pop(); else super.onBackPressed(); }

    private void applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            insetTop = bars.top; insetBottom = Math.max(bars.bottom, ime.bottom);
            root.setPadding(0, insetTop, 0, insetBottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        new WindowInsetsControllerCompat(getWindow(), root).setAppearanceLightStatusBars(false);
    }

    // ---- navigation ----

    private void push(View v) { stack.push(v); showTop(); }
    private void pop() { if (stack.size() > 1) { stack.pop(); showTop(); } }
    private void showOrders() { stack.clear(); stack.push(buildOrders()); showTop(); }
    private void showTop() { container.removeAllViews(); container.addView(stack.peek()); }
    private void refreshTop(View rebuilt) { stack.pop(); stack.push(rebuilt); showTop(); }

    // ---- pairing + identity ----

    private void onPaired(boolean enabled) {
        paired = enabled;
        pairingBanner.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (enabled) {
            fetchBlock(crypto == null ? null : this::requestScan);
            fetchVendorAddr();
            if (crypto == null) setupIdentity();
        }
    }

    private void fetchVendorAddr() {
        String saved = db.getMeta("vendoraddr", "");   // lock to the first address (the one in the Vendor Card)
        if (!saved.isEmpty()) {
            vendorAddr = saved;
            if (paymentScanner == null && crypto != null) startPaymentScanner();
            return;
        }
        node.cmd("getaddress", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) {
                    String a = r.optString("miniaddress", r.optString("address", ""));
                    if (!a.isEmpty()) { vendorAddr = a; db.setMeta("vendoraddr", a);
                        if (paymentScanner == null && crypto != null) startPaymentScanner(); }
                }
            }
            @Override public void onError(String m) {}
        });
    }

    /** Reads the chain tip, then runs {@code then} (null = nothing): a scan pass judges payments against the tip it read,
     *  never one block stale. */
    private void fetchBlock(final Runnable then) {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (gone()) return;
                JSONObject r = j.optJSONObject("response");
                int was = chainBlock;
                if (r != null) try { chainBlock = Integer.parseInt(r.optString("block", "0")); } catch (Exception ignored) {}
                if (chainBlock > was) confirmSweep(chainBlock);   // once per new block
                if (then != null) then.run();
            }
            @Override public void onError(String m) { if (then != null && !gone()) then.run(); }
        });
    }

    private void setupIdentity() {
        node.cmd("vault action:seed", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                String ikm = r == null ? "" : r.optString("seed", r.optString("phrase", ""));
                if (ikm.isEmpty()) askForSeed(); else deriveIdentity(ikm);
            }
            @Override public void onError(String m) {
                if (NodeApi.ERR_NOT_ENABLED.equals(m)) { pairingBanner.setVisibility(View.VISIBLE); return; }
                askForSeed();
            }
        });
    }

    private void deriveIdentity(final String ikm) {
        io.execute(() -> {
            try {
                byte[] seed = ikm.startsWith("0x") ? Hex.from(ikm) : ikm.getBytes(StandardCharsets.UTF_8);
                CommsIdentity id = CommsIdentity.fromSeed(ls, seed);
                ui.post(() -> { adoptIdentity(id); refreshVisibleOrderOrList(); requestScan(); });
            } catch (Exception e) { ui.post(() -> toast("Identity error: " + e.getMessage())); }
        });
    }

    /** One scanner's io queue (point release M1): its coins are stored on the io executor, in order, and its bookmarks
     *  are written through the same queue after them, so a bookmark advances only once every coin of its scan was
     *  stored; never when one failed (the next scan reads them again: storing is idempotent, nothing is counted twice),
     *  and never when the queue was dropped because the app was closed or killed first. */
    final class ScanQueue implements CommsScanner.MetaStore {
        final java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
        CommsScanner.Router route(final CommsScanner.Router r) {
            return (cid, opened, coin) -> {
                try {
                    io.execute(() -> {
                        try { if (r.handle(cid, opened, coin)) routedNew.incrementAndGet(); }
                        catch (RuntimeException e) { failed.set(true); android.util.Log.w("miniMallInbox", "not stored, the next scan reads it again: " + cid, e); }
                    });
                } catch (java.util.concurrent.RejectedExecutionException closing) { failed.set(true); }
                return false;
            };
        }
        @Override public String getMeta(String k, String def) { return db.getMeta(k, def); }
        @Override public void setMeta(final String k, final String v) {
            try {
                io.execute(() -> {
                    boolean skip = failed.get();
                    if (k.startsWith("scanned_tip_")) failed.set(false);   // the scan's last bookmark: the next scan starts clean
                    if (!skip) db.setMeta(k, v);
                });
            } catch (java.util.concurrent.RejectedExecutionException closing) { /* not advanced: read again next time */ }
        }
    }
    private final ScanQueue orderQueue = new ScanQueue(), paymentQueue = new ScanQueue();

    /** Item 7: a scanner's message or payment is stored on the io executor (in order: one thread), never on the main
     *  thread; what it adds is counted for the scan's end. */
    private final java.util.concurrent.atomic.AtomicInteger routedNew = new java.util.concurrent.atomic.AtomicInteger();
    CommsScanner.Router onIo(final CommsScanner.Router r) { return orderQueue.route(r); }

    private void adoptIdentity(CommsIdentity id) {
        identity = id;
        crypto = new LocalEcCryptoProvider(ls, id);
        myId = id.publicId();
        // Orders first, then payments, in every pass (2026-11-22): the payment scan starts when the orders scan ends, and
        // its coins are stored on io after the orders and their bookmark (one io thread, in order).
        orderScanner = new CommsScanner(node, crypto, orderQueue, CommsTransport.MINIMERCH_ADDRESS,
                orderQueue.route(this::routeIncoming), (ok, n) -> {
                    io.execute(() -> onScanDone(routedNew.getAndSet(0)));
                    if (paymentScanner != null) paymentScanner.scan(chainBlock);
                });
        if (!vendorAddr.isEmpty()) startPaymentScanner();
    }

    private void startPaymentScanner() {
        paymentScanner = new CommsScanner(node, crypto, paymentQueue, vendorAddr,
                paymentQueue.route(this::routePayment), (ok, n) -> io.execute(() -> {
                    onScanDone(routedNew.getAndSet(0));   // after every routed coin (the io executor runs in order)
                    // After the first clean pass, auto-deliver anything paid-but-unsent (e.g. an
                    // auto-send that failed last session, or a payment that landed while closed).
                    if (ok) ui.post(() -> { if (!autoSweepDone) { autoSweepDone = true; autoDeliverSweep(); } });
                }), false);
        paymentScanner.follows(ORDERS_TIP);   // its bookmark never passes the orders read; every pass re-reads what waits
    }

    /** The orders scanner's bookmark: every order mined up to this block was read and stored. */
    static final String ORDERS_TIP = CommsScanner.tipKey(CommsTransport.MINIMERCH_ADDRESS, true);
    long ordersTip() { try { return Long.parseLong(db.getMeta(ORDERS_TIP, "0")); } catch (NumberFormatException e) { return 0; } }

    private void askForSeed() {
        final EditText in = input("Your Minima seed phrase");
        in.setMinLines(3);
        AlertDialog seedDialog = dialog()
                .setTitle("Create your shop identity")
                .setMessage("Your shop key is derived from your Minima seed (recoverable, distinct from other apps). Paste your seed phrase once — it's used only to derive the key and is never stored.")
                .setView(in)
                .setPositiveButton("Create", (d, w) -> { String s = in.getText().toString().trim(); if (!s.isEmpty()) deriveIdentity(s); })
                .create();
        seedDialog.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        seedDialog.show();
    }

    // ---- scanning + routing ----

    private boolean rescanPosted;

    /** One pass: orders, then payments (started when the orders scan ends). A request dropped because a pass is running
     *  or ended under 4 s ago is tried again once, so a new block is not left unread until the next event. */
    private void requestScan() {
        if (crypto == null || orderScanner == null) return;
        if (orderScanner.scan(chainBlock) || rescanPosted) return;
        rescanPosted = true;
        ui.postDelayed(() -> { rescanPosted = false; if (!gone()) requestScan(); }, 4500);
    }

    /** An opened MINIMERCH message: orders/inquiries land as orders; buyer replies append to the order chat. */
    private boolean routeIncoming(String coinid, com.eurobuddha.comms.Opened opened, JSONObject coin) {
        MerchMessage m = MerchMessage.fromWire(opened.plaintext);
        if (m == null || !opened.fromPublicId.equals(m.from)) return false;   // sig must match claimed sender
        if (!myId.equals(m.to)) return false;                                  // addressed to me
        if (!MerchMessage.validRef(m.ref)) return false;                       // an empty or odd ref is never stored (C1)
        if (MerchMessage.ORDER.equals(m.type)) {
            boolean fresh = db.upsertOrder(m, "seller", coinid, coin == null ? 0 : coin.optLong("created", 0));   // with its block, in one transaction
            if (!fresh && coin != null && coin.optLong("created", 0) > 0 && coinid != null)   // another order with a stored ref: when it came (m2)
                db.setMeta("contenderblock:" + m.ref + ":" + coinid.toLowerCase(java.util.Locale.ROOT), String.valueOf(coin.optLong("created")));
            if (fresh) recheck(db.order(m.ref));   // what is due, from the vendor's own listing
            return fresh;
        }
        // A message on an existing ref is stored only from that order's own buyer, with its sender (delta review M-2).
        if (MerchMessage.INQUIRY.equals(m.type)) {
            boolean rowNew = db.upsertOrder(m, "seller", coinid);   // ensures the inquiry row exists
            MerchDb.Order o = db.order(m.ref);
            if (o == null || !m.from.equals(o.counterparty)) return rowNew;
            boolean chatNew = db.insertChat(m.ref, true, m.message, m.randomid, m.date > 0 ? m.date : System.currentTimeMillis(), m.from);
            return rowNew || chatNew;
        }
        if (MerchMessage.BUYER_REPLY.equals(m.type)) {
            MerchDb.Order o = db.order(m.ref);
            if (o == null || !m.from.equals(o.counterparty)) return false;
            return db.insertChat(m.ref, true, m.message, m.randomid, m.date > 0 ? m.date : System.currentTimeMillis(), m.from);
        }
        return false;
    }

    /** A plaintext payment coin at the vendor address: match by the ref stamped in state[1], verify, mark paid. */
    private boolean routePayment(String coinid, com.eurobuddha.comms.Opened ignored, JSONObject coin) {
        String refHex = CommsScanner.statePort(coin, 1);
        if (refHex == null) return false;
        String ref = DeliveryRules.text(refHex);   // strict: hex of valid UTF-8 only (delta review L-f)
        if (!MerchMessage.validRef(ref)) return false;
        long created = coin.optLong("created", 0);
        if (!DeliveryRules.judgeable(created, chainBlock, ordersTip())) return false;   // not judged yet: a later pass reads it again
        MerchDb.Order o = db.order(ref);
        if (o == null || o.paid) return false;   // PAID locks; any other coin is counted once (recordPayment keeps its id)
        db.dues = this::contenderDue;   // another kept order's coins flag the ref only once they reach its listed due (M-A)
        String amount = MerchDb.coinAmount(coin);   // a token coin's tokenamount, never the Minima amount backing it
        String tokenid = coin.optString("tokenid", "0x00");
        String result = db.recordPayment(ref, coinid, amount, tokenid, CommsScanner.statePort(coin, 2), created);
        // M2 c: read by the scanner already at the minimum confirmations: confirmed now, so spending it later is fine
        if (result != null && created > 0 && chainBlock > 0 && chainBlock - created + 1 >= minConf())
            confirm(coinid, amount, tokenid, created, chainBlock);   // each coin counted once; port 2 = the order secret's commitment (H1)
        // Auto-deliver only on a clean PAID (the token received is the order currency's) — never
        // UNDERPAID/WRONG_TOKEN, and nothing is sent for a mismatched order (MerchDb.mismatch). Queued, drained at scan end.
        if (MerchDb.PAID.equals(result)) pendingAutoDeliver.add(ref);
        return result != null;
    }

    private int sweptBlock = -1;

    private void onScanDone(int newCount) {
        ui.post(() -> {
            if (!pendingAutoDeliver.isEmpty()) {
                List<String> refs = new ArrayList<>(pendingAutoDeliver);
                pendingAutoDeliver.clear();
                for (String r : refs) maybeAutoDeliver(r);
            }
            if (autoDeliverOn() && chainBlock > sweptBlock) { sweptBlock = chainBlock; autoDeliverSweep(); }   // once per block, not per scanner callback
            if (newCount <= 0) return;
            notifyNew(newCount);
            refreshVisibleOrderOrList();
        });
    }

    // ---- the vendor's own listing (Listing) ----

    /** Import the .shop file this vendor published; every order of that shop is checked against it again. */
    private void importListing(android.net.Uri uri) {
        final String me = myId;
        io.execute(() -> {
            String msg;
            try (java.io.InputStream is = getContentResolver().openInputStream(uri)) {
                if (me == null || me.isEmpty()) throw new IllegalArgumentException("Connect your node first: the Inbox needs your shop key to check the file is yours.");
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n;
                while ((n = is.read(buf)) != -1) {
                    if (bos.size() + n > 16 * 1024 * 1024) throw new IllegalArgumentException("That file is too large to be a .shop file.");
                    bos.write(buf, 0, n);
                }
                // Shown in full first, every time; a signed listing must be this shop key's, an unsigned one (desktop
                // builder, older Studio) is allowed only here, with the warning, and only on "Use this listing".
                String raw = new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                final Listing.Prepared p = Listing.prepare(raw, crypto, me, this::listingFor);
                ui.post(() -> dialog().setTitle(p.signed ? "Use this listing?" : "Use this UNSIGNED listing?").setMessage(p.summary)
                        .setPositiveButton("Use this listing", (d, w) -> useListing(p.listing, p.signed))
                        .setNegativeButton("Cancel", null).show());
                return;
            } catch (IllegalArgumentException e) { msg = e.getMessage(); }
            catch (Exception e) { msg = "That file could not be read."; }
            final String say = msg;
            ui.post(() -> { dialog().setTitle("Your listing").setMessage(say).setPositiveButton("OK", null).show(); refreshVisibleOrderOrList(); });
        });
    }

    /** The order screen's line on the listing in use; one stored before 0.5.27 carries no mark and is not called signed. */
    static String listingInUse(String signedMark) {
        return "1".equals(signedMark) ? "Listing in use: signed by your shop key."
                : "Listing in use: an unsigned file you confirmed (not signed by your shop key).";
    }

    /** The vendor read the summary and chose it: stored, and every order of that shop is checked against it again. */
    private void useListing(final JSONObject listing, final boolean signed) {
        io.execute(() -> {
            String shopId = listing.optString("shopId");
            try { listing.put("confirmedAt", System.currentTimeMillis()); } catch (org.json.JSONException ignored) {}   // its id when unsigned (item 2)
            db.setMeta("listing:" + shopId, listing.toString());
            db.setMeta("listingsigned:" + shopId, signed ? "1" : "0");   // shown on every order of this shop
            int checked = 0;
            for (MerchDb.Order o : db.orders()) if ("seller".equals(o.role) && shopId.equals(o.shopId)) { recheck(o); checked++; }
            final String say = "Your listing for \"" + listing.optString("shopName", shopId) + "\" is in. " + checked + " order(s) checked against it; new orders are checked as they arrive.";
            ui.post(() -> { dialog().setTitle("Your listing").setMessage(say).setPositiveButton("OK", null).show(); refreshVisibleOrderOrList(); });
        });
    }

    /** What another order kept with a ref is due, from the vendor's listing for its shop (null when it departs from the
     *  listing: it never counts as paid), else its own stated amount (no listing: nothing can be sent anyway). */
    private String contenderDue(String ref, JSONObject c) {
        MerchDb.Order x = new MerchDb.Order();
        x.ref = ref; x.role = "seller"; x.shopId = c.optString("shopId"); x.currency = c.optString("currency"); x.shipping = c.optString("shipping");
        x.items = c.optString("items"); x.product = c.optString("product"); x.amount = c.optString("amount");
        JSONObject listing = listingFor(x.shopId);
        if (listing == null) return x.amount;
        try { Listing.Check k = Listing.check(listing, x); return k.problems.isEmpty() ? k.due : null; } catch (Throwable e) { return null; }
    }

    /** Recompute what an order is due from the vendor's own listing for its shop, if one was imported. */
    private void recheck(MerchDb.Order o) {
        if (o == null || !"seller".equals(o.role)) return;
        String raw = db.getMeta("listing:" + o.shopId, "");
        if (raw.isEmpty()) return;
        db.setMeta("listingproblem:" + o.ref, "this order has not been checked yet");   // fail closed until the check ends (M1)
        try {
            Listing.Check c = Listing.check(new JSONObject(raw), o);
            List<String> kept = new ArrayList<>();
            for (String p : c.problems) { if (kept.size() == 20) { kept.add("… and " + (c.problems.size() - 20) + " more"); break; } kept.add(Listing.cap(p, 400)); }
            db.setMeta("due:" + o.ref, c.due);
            db.setMeta("listingproblem:" + o.ref, String.join("\n", kept));
            db.reevaluate(o.ref);   // PAID follows the recomputed due (review L4)
        } catch (Throwable e) {
            db.setMeta("listingproblem:" + o.ref, "this order could not be checked against your listing; nothing is sent for it");
        }
    }

    // ---- automatic NFT delivery ----


    /** The old "autodeliver" setting was on and the one-time notice was not dismissed yet (item 12). */
    /** autoDeliverWasOn, read on io at start (the orders screen reads this field, never the database). */
    volatile boolean autoWasOn;

    boolean autoDeliverWasOn() { return "1".equals(db.getMeta("autodeliver", "")) && db.getMeta("autodeliverbanner", "").isEmpty(); }

    /** Automatic delivery: OFF unless the vendor switches it on ("autosend"; the old "autodeliver" setting, ON by
     *  default before, is never read). Even ON, an order is sent only when every DeliveryRules check passes. */
    private boolean autoDeliverOn() { return "1".equals(db.getMeta("autosend", "0")); }

    /** Auto-send every unsent NFT line of a cleanly-paid order. Idempotent: nftsent: meta skips
     *  delivered lines, autoTried caps automatic attempts at one per line per session. */
    private void maybeAutoDeliver(final String ref) {
        io.execute(() -> {   // m4: the checks read the database on io; the sends start on the main thread
            if (!autoDeliverOn()) return;
            final MerchDb.Order o = db.order(ref);
            if (!db.deliverable(o)) return;
            if (!DeliveryRules.autoAllowed(db.nonce(o))) return;   // no secret: only the vendor's own Send (the order screen says so)
            if (!MerchDb.PAID.equals(o.status) && !MerchDb.CONFIRMED.equals(o.status)) return;
            if (o.payaddr == null || o.payaddr.isEmpty()) return;   // undeliverable — order screen explains
            final List<String[]> todo = new ArrayList<>();
            for (String[] line : nftLines(o)) {
                String key = lineKey(ref, line);
                if (!db.getMeta("nftsent:" + key, "").isEmpty()) continue;
                if (!DeliveryLedger.state(db.getReadableDatabase(), ledgerKey(ref, line)).isEmpty()) continue;   // one automatic send per line
                todo.add(line);
            }
            if (!todo.isEmpty()) ui.post(() -> {
                for (String[] line : todo) if (!autoTried.contains(lineKey(ref, line))) sendNft(o, line, true);   // every DeliveryRules check runs; a line that fails waits
            });
        });
    }

    /** Once per process: re-attempt paid-but-unsent NFT orders (failed auto-send last session,
     *  or payment that confirmed while the app was closed). */
    private void autoDeliverSweep() {
        if (!autoDeliverOn()) return;
        io.execute(() -> {
            List<String> refs = new ArrayList<>();
            for (MerchDb.Order o : db.orders())
                if (o.paid && (MerchDb.PAID.equals(o.status) || MerchDb.CONFIRMED.equals(o.status))
                        && !nftLines(o).isEmpty()) refs.add(o.ref);
            if (!refs.isEmpty()) ui.post(() -> { for (String r : refs) maybeAutoDeliver(r); });
        });
    }

    // ---- orders list ----

    private View buildOrders() {
        LinearLayout col = column();
        LinearLayout head = header(shopName.isEmpty() ? "Orders" : shopName, false);
        head.addView(iconBtn("⇪", () -> listingLauncher.launch(new String[]{"*/*"})));   // import your own .shop (Listing)
        head.addView(iconBtn("⤓", this::exportCsv));          // export orders CSV
        head.addView(iconBtn("≡", this::showIdentityCard));   // ≡ menu → identity card
        col.addView(head);

        TextView ver = new TextView(this);
        ver.setText("miniMall Inbox v" + BuildConfig.VERSION_NAME);
        ver.setTextColor(Design.DIM2); ver.setTextSize(10f);
        ver.setPadding(dp(16), dp(2), dp(16), dp(4));
        col.addView(ver);
        if (autoWasOn) {   // once: the old setting was on, automatic delivery is now off until switched on
            TextView ban = label("Automatic NFT delivery is now OFF after this update. Switch it on again with the \"Auto NFT delivery\" line below; it then sends only for orders that pass every check. (Tap to dismiss.)");
            ban.setTextColor(Design.RED); ban.setPadding(dp(16), dp(4), dp(16), dp(4));
            ban.setOnClickListener(v -> io.execute(() -> { db.setMeta("autodeliverbanner", "1"); autoWasOn = false; ui.post(() -> refreshTop(buildOrders())); }));
            col.addView(ban);
        }

        TextView s = new TextView(this);
        s.setPadding(dp(16), dp(4), dp(16), dp(8)); s.setTextSize(12.5f);
        if (crypto == null) {
            s.setText(paired ? "Connecting to your node…" : "Enable miniMall Inbox in Minima Core → Apps.");
            s.setTextColor(Design.DIM);
        } else {
            s.setText("✓ Ready to receive orders  ·  shop key " + myId);
            s.setTextColor(Design.IN);
        }
        col.addView(s);

        // Automatic NFT delivery (default OFF) and the payment confirmations every send needs.
        final boolean auto = autoDeliverOn();
        TextView ad = new TextView(this);
        ad.setText("⚡ Auto NFT delivery  ·  " + (auto ? "ON" : "OFF") + "  ·  only orders that pass every check (tap to change)");
        ad.setTextColor(auto ? Design.IN : Design.DIM);
        ad.setTextSize(12.5f); ad.setPadding(dp(16), 0, dp(16), dp(4));
        ad.setOnClickListener(v -> {
            if (auto) { io.execute(() -> { db.setMeta("autosend", "0"); ui.post(() -> refreshTop(buildOrders())); }); return; }
            dialog().setTitle("Turn on automatic NFT delivery?")
                    .setMessage("An NFT is then sent without asking you, but only when the order matches your imported listing, the line is listed and in stock, it was not sent before, and the payment is on chain in full with at least " + minConf() + " confirmation(s). Anything else waits for you with the reasons listed.")
                    .setPositiveButton("Turn on", (d, w) -> io.execute(() -> { db.setMeta("autosend", "1"); ui.post(() -> refreshTop(buildOrders())); }))
                    .setNegativeButton("Cancel", null).show();
        });
        col.addView(ad);
        TextView mc = new TextView(this);
        mc.setText("⛓ Payments need " + minConf() + " confirmation(s) before anything is sent (tap to change)");
        mc.setTextColor(Design.DIM); mc.setTextSize(12.5f); mc.setPadding(dp(16), 0, dp(16), dp(8));
        mc.setOnClickListener(v -> {
            final EditText in = new EditText(this);
            in.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); in.setText(String.valueOf(minConf()));
            dialog().setTitle("Confirmations").setMessage("How many blocks a payment coin must have before an NFT is sent (at least 1). "
                    + "Once a payment has them it stays counted, even if a deeper reorganisation of the chain later drops it.").setView(in)
                    .setPositiveButton("Save", (d, w) -> {
                        int n; try { n = Integer.parseInt(in.getText().toString().trim()); } catch (NumberFormatException e) { n = 0; }
                        if (n < 1) { toast("At least 1 confirmation."); return; }
                        final int keep = n;
                        io.execute(() -> { db.setMeta("minconf", String.valueOf(keep)); ui.post(() -> refreshTop(buildOrders())); });
                    })
                    .setNegativeButton("Cancel", null).show();
        });
        col.addView(mc);

        col.addView(filterBar());
        final HorizontalScrollView shopBar = new HorizontalScrollView(this);
        shopBar.setHorizontalScrollBarEnabled(false);
        col.addView(shopBar);   // populated below only if the vendor runs >1 shop

        RecyclerView rv = new RecyclerView(this);
        rv.setLayoutManager(new LinearLayoutManager(this));
        final OrderAdapter adapter = new OrderAdapter(new ArrayList<>());
        rv.setAdapter(adapter);
        col.addView(rv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        io.execute(() -> {
            List<MerchDb.Order> all = db.orders();
            java.util.LinkedHashSet<String> shops = new java.util.LinkedHashSet<>();
            for (MerchDb.Order o : all) if (!o.shopName.isEmpty()) shops.add(o.shopName);
            List<MerchDb.Order> shown = new ArrayList<>();
            for (MerchDb.Order o : all) {
                if (!"ALL".equals(filterStatus) && !filterStatus.equals(o.status)) continue;
                if (!"ALL".equals(filterShop) && !filterShop.equals(o.shopName)) continue;
                shown.add(o);
            }
            ui.post(() -> {
                adapter.data.clear(); adapter.data.addAll(shown); adapter.notifyDataSetChanged();
                if (shops.size() >= 2) shopBar.addView(shopFilterRow(shops));
            });
        });
        return col;
    }

    private LinearLayout shopFilterRow(java.util.Set<String> shops) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), 0, dp(12), dp(8));
        java.util.List<String> all = new java.util.ArrayList<>(); all.add("ALL"); all.addAll(shops);
        for (final String s : all) {
            boolean on = filterShop.equals(s);
            TextView chip = Design.pill(this, "ALL".equals(s) ? "All shops" : s, on ? Design.ACCENT : Design.SURFACE2, on ? Design.ON_ACCENT : Design.DIM);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6); chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> { filterShop = s; refreshTop(buildOrders()); });
            row.addView(chip);
        }
        return row;
    }

    private HorizontalScrollView filterBar() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(dp(12), dp(4), dp(12), dp(8));
        for (final String s : new String[]{"ALL", MerchDb.PENDING, MerchDb.PAID, MerchDb.CONFIRMED, MerchDb.SHIPPED, MerchDb.DELIVERED}) {
            boolean on = filterStatus.equals(s);
            TextView chip = Design.pill(this, s, on ? Design.ACCENT : Design.SURFACE2, on ? Design.ON_ACCENT : Design.DIM);
            chip.setPadding(dp(14), dp(7), dp(14), dp(7));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6); chip.setLayoutParams(lp);
            chip.setOnClickListener(v -> { filterStatus = s; refreshTop(buildOrders()); });
            row.addView(chip);
        }
        hs.addView(row);
        return hs;
    }

    private class OrderAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<MerchDb.Order> data;
        OrderAdapter(List<MerchDb.Order> d) { data = d; }
        @NonNull @Override public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup p, int t) {
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerView.ViewHolder(row) {};
        }
        @Override public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            MerchDb.Order o = data.get(pos);
            LinearLayout row = (LinearLayout) h.itemView;
            row.removeAllViews();
            LinearLayout top = new LinearLayout(MainActivity.this);
            top.setOrientation(LinearLayout.HORIZONTAL); top.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = new TextView(MainActivity.this);
            title.setText((o.read ? "" : "●  ") + (o.product.isEmpty() ? o.ref : o.product));
            title.setTextColor(Design.TEXT); title.setTextSize(15f); title.setTypeface(null, Typeface.BOLD);
            title.setMaxLines(1); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(Design.pill(MainActivity.this, o.status, statusColor(o.status), Design.ON_ACCENT));
            row.addView(top);
            TextView sub = new TextView(MainActivity.this);
            String shopPrefix = o.shopName.isEmpty() ? "" : o.shopName + "   ·   ";
            sub.setText(shopPrefix + o.amount + " " + dispCurrency(o) + "   ·   " + o.counterparty + "   ·   " + timeAgo(o.date));
            sub.setTextColor(Design.DIM); sub.setTextSize(12f); sub.setPadding(0, dp(3), 0, 0);
            row.addView(sub);
            row.setOnClickListener(v -> openOrder(o.ref));
        }
        @Override public int getItemCount() { return data.size(); }
    }

    // ---- order detail ----

    private void openOrder(String ref) {
        io.execute(() -> { db.markRead(ref); showOrderAsync(ref, true); });
    }

    /** Everything the order screen shows, read on the io executor (item 7: no database work while a screen is built). */
    static final class OrderModel {
        String ref; MerchDb.Order o;
        boolean mismatch, conflict, upgraded, listing, autoOn, contendedFull, cameFirst, legacyPaid;
        String findResult = "";
        JSONObject olderPay;
        String nonce = "", listingSigned = "", problems = "", due = "", orderBlock = "";
        boolean checked;
        List<String[]> nfts = new ArrayList<>();
        java.util.Map<String, String> sentTx = new java.util.HashMap<>(), ledger = new java.util.HashMap<>(), verdict = new java.util.HashMap<>();
        JSONArray contenders = new JSONArray(), unassigned = new JSONArray();
        int committed = -1;
        List<String[]> chat = new ArrayList<>();
    }

    OrderModel orderModel(String ref) {
        OrderModel m = new OrderModel();
        m.ref = ref; m.o = db.order(ref);
        MerchDb.Order o = m.o;
        if (o == null) return m;
        m.mismatch = db.mismatch(o); m.conflict = db.conflict(o); m.nonce = db.nonce(o);
        m.upgraded = "1".equals(db.getMeta("upgraded:" + ref, ""));
        m.listing = listingFor(o.shopId) != null; m.listingSigned = db.getMeta("listingsigned:" + o.shopId, "");
        m.problems = db.listingProblems(o); m.checked = db.checked(o); m.due = db.due(o);
        m.autoOn = autoDeliverOn();
        m.nfts = nftLines(o);
        for (String[] line : m.nfts) {
            String key = lineKey(ref, line);
            m.sentTx.put(key, db.getMeta("nftsent:" + key, ""));
            m.ledger.put(key, DeliveryLedger.state(db.getReadableDatabase(), ledgerKey(ref, line)));
            m.verdict.put(key, storedVerdict(key));
        }
        m.contenders = db.contenders(ref); m.unassigned = db.unassignedCoins(ref);
        m.committed = db.committedContender(o); m.contendedFull = db.contendedCoinsFull(ref);
        m.cameFirst = m.conflict && m.nonce.isEmpty() && o.paid && recordCameFirst(o);
        m.legacyPaid = legacyPaid(o);
        m.findResult = db.getMeta("findpayment:" + ref, "");
        m.olderPay = db.olderPayment(ref);
        m.orderBlock = db.getMeta("orderblock:" + ref, "");
        m.chat = db.chat(ref);
        return m;
    }

    /** Builds the order screen for ref from a model read on the io executor, then pushes or refreshes it. */
    private void showOrderAsync(final String ref, final boolean push) {
        io.execute(() -> {
            final OrderModel m = orderModel(ref);
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (push) push(buildOrderDetail(m));
                else {
                    View top = stack.peek(); Object tag = top == null ? null : top.getTag();
                    if (tag instanceof String && tag.equals("order:" + ref)) refreshTop(buildOrderDetail(m));
                }
            });
        });
    }

    /** The order screen read and built on the calling thread (tests; the app uses showOrderAsync). */
    private View buildOrderDetail(String ref) { return buildOrderDetail(orderModel(ref)); }

    private View buildOrderDetail(OrderModel m) {
        String ref = m.ref;
        MerchDb.Order o = m.o;
        LinearLayout col = column();
        col.setTag("order:" + ref);
        LinearLayout head = header(o == null ? "Order" : (o.product.isEmpty() ? o.ref : o.product), true);
        col.addView(head);
        ScrollView sv = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(8), dp(16), dp(24));
        sv.addView(body);
        col.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        if (o == null) { body.addView(label("Order not found.")); return col; }

        final boolean inquiry = MerchDb.INQUIRY.equals(o.status);

        // status + payment
        LinearLayout st = new LinearLayout(this); st.setGravity(Gravity.CENTER_VERTICAL);
        st.addView(Design.pill(this, inquiry ? "ENQUIRY" : o.status, statusColor(o.status), Design.ON_ACCENT));
        if (!inquiry) {
            TextView paid = new TextView(this);
            // paid = the currency's own token arrived in full; anything else received is named by its whole token id
            paid.setText(o.paid ? "  paid " + o.paidAmount + " " + dispCurrency(o)
                    : o.paidTokenid.isEmpty() ? "  awaiting payment" : "  received " + o.paidAmount + " of token " + o.paidTokenid);
            paid.setTextColor(o.paid ? Design.IN : Design.DIM); paid.setTextSize(12f);
            st.addView(paid);
        }
        st.setPadding(0, 0, 0, dp(10)); body.addView(st);
        if (m.mismatch) {
            String want = MerchMessage.tokenFor(o.currency);
            body.addView(label(want == null
                    ? "This order asks to be paid in \"" + o.currency + "\", which is neither Minima nor MxUSD. It is never marked paid, and its NFTs are never sent from this app."
                    : "This order names a token that is not its currency's. Its NFTs are never sent from this app, whatever its status, and a payment counts only in "
                            + MerchMessage.tokenName(want) + "\n" + want));
        }

        if (!inquiry) {
            // line items + totals
            body.addView(sectionLabel("Items"));
            body.addView(card(itemsText(o)));
            body.addView(kv("Total", o.amount + " " + dispCurrency(o)));
            if (m.conflict) {
                TextView cf = label("Another order arrived with this reference (a reference is public once paid), so nothing is sent for it while it is contested. "
                        + "A payment counts only for the order whose secret it commits to; the other orders with this reference are listed below.");
                cf.setTextColor(Design.RED); body.addView(cf);
            } else if (m.nonce.isEmpty()) body.addView(label("This order came from an older Shop without a payment secret: its payment is matched by its reference only."));
            if (m.legacyPaid) {   // M2 a: paid under an older Inbox that kept no coin list
                TextView lg = label("This order was paid under an older Inbox, which did not keep its payment coins, so nothing is sent for it until they are found. "
                        + ("none".equals(m.findResult) ? "They were not found at your address: the payment may have been spent before it was confirmed here, or it is older than your node keeps. Check your wallet history for it by hand."
                        : "unread".equals(m.findResult) ? "The last search could not read every block: try again." : "Until then, check the payment by hand in your wallet."));
                lg.setTextColor(Design.RED); body.addView(lg);
                TextView find = button("Find this order's payment", false);
                find.setOnClickListener(v -> { find.setEnabled(false); find.setText("Searching…"); findPayment(ref); });
                body.addView(find);
            }
            if (m.upgraded) {
                TextView up = label("Upgraded: re-check this order's payment. It was recorded by an older Inbox, and nothing is sent until its coins are read back on chain.");
                up.setTextColor(Design.RED); body.addView(up);
            }
            if (m.olderPay != null) {   // a payment older than this no-secret order: not counted, checked by hand (tap: the whole coin id)
                final String coin = m.olderPay.optString("coinid");
                TextView op = label("A payment with this order's reference is older than the order (payment coin " + coin + " at block " + m.olderPay.optString("created")
                        + ", the order at block " + m.olderPay.optString("orderblock") + "): check by hand. It is not counted for this order.");
                op.setTextColor(Design.RED); op.setOnClickListener(v -> copy(coin)); body.addView(op);
            }
            if (m.listing) body.addView(label(listingInUse(m.listingSigned)));
            String problems = m.problems;
            TextView chk = label(!problems.isEmpty()
                    ? "Not as you list it, so nothing is sent for this order:\n• " + problems.replace("\n", "\n• ")
                    : m.checked ? "Checked against your listing: due " + m.due + " " + dispCurrency(o) + "."
                    : "Unchecked: the items and the total " + o.amount + " " + dispCurrency(o) + " are the buyer's own figures, and nothing can be sent for this order until you import the .shop file you published (⇪ on the orders screen).");
            if (!problems.isEmpty() || !m.checked) chk.setTextColor(Design.RED);
            body.addView(chk);
            body.addView(kv("Reference", o.ref));
            if (!o.shopName.isEmpty()) body.addView(kv("Shop", o.shopName));
            if (!o.coinid.isEmpty()) body.addView(copyRow("Order tx", o.coinid, o.coinid));

            // delivery
            body.addView(sectionLabel("Delivery"));
            body.addView(kv("Method", shippingLabel(o.shipping)));
            if (!o.delivery.isEmpty()) body.addView(copyRow("Address", o.delivery, o.delivery));
            if (!o.message.isEmpty()) { body.addView(sectionLabel("Buyer note")); body.addView(card(o.message)); }

            // NFT delivery — order lines that carry an NFT tokenid (from an NFT Studio .shop)
            List<String[]> nfts = m.nfts;
            if (!nfts.isEmpty()) {
                boolean byHand = m.autoOn && !DeliveryRules.autoAllowed(m.nonce);
                body.addView(sectionLabel(m.autoOn && !byHand ? "NFT delivery · automatic" : "NFT delivery"));
                if (byHand) {
                    TextView bh = label("This order came from an older Shop that does not prove which payment is its own, so it is never sent automatically: check it and send by hand.");
                    bh.setTextColor(Design.RED); body.addView(bh);
                }
                for (String[] line : nfts) body.addView(nftSendRow(m, o, line));
            }
        }
        body.addView(copyRow("Buyer key", o.counterparty, o.counterparty));
        if (!inquiry) addContenders(body, m, o);

        // status actions (orders only — an enquiry has no lifecycle)
        if (!inquiry) {
            body.addView(sectionLabel("Update status"));
            body.addView(statusButtons(o));
        }

        // reply / conversation
        body.addView(sectionLabel(inquiry ? "Conversation" : "Message the buyer"));
        body.addView(replyRow(o, m.chat));
        return col;
    }

    /** The other orders that arrived with this ref (delta review H-1): sender, buyer address and items whole, which one
     *  a payment commits to, and the one action the vendor has: make THAT order the order of record. */
    private void addContenders(LinearLayout body, OrderModel m, final MerchDb.Order o) {
        JSONArray cs = m.contenders;
        JSONArray un = m.unassigned;
        if (m.cameFirst) {
            body.addView(label("This order's own coin is in block " + m.orderBlock + ", before every payment counted for it, so it existed before its reference was public."));
            TextView keep = button("Deliver to the order of record (" + o.payaddr + ")", false);
            keep.setOnClickListener(v -> dialog().setTitle("Deliver to the order of record?")
                    .setMessage("The other order with this reference arrived after this one's payment made the reference public. Nothing else changes: every other check still applies.")
                    .setPositiveButton("Deliver to the order of record", (d, w) -> io.execute(() -> {
                        if (db.conflict(o) && recordCameFirst(db.order(o.ref))) { db.setMeta("conflict:" + o.ref, ""); db.setMeta("conflictresolved:" + o.ref, "orderblock"); }
                        ui.post(() -> refreshOrderIfTopmost(o.ref));
                    }))
                    .setNegativeButton("Cancel", null).show());
            body.addView(keep);
        }
        if (cs.length() == 0 && un.length() == 0 && !m.contendedFull) return;
        int committed = m.committed;
        if (m.contendedFull) { TextView t = label("More payments with this reference arrived than the Inbox keeps, so they could not all be read: no order can be switched here."); t.setTextColor(Design.RED); body.addView(t); }
        for (int i = 0; i < un.length(); i++) {
            JSONObject u = un.optJSONObject(i); if (u == null) continue;
            body.addView(label("A payment with this reference and no payment secret, counted for no order: coin " + u.optString("coinid") + ", " + u.optString("amount") + " of token " + u.optString("tokenid") + "."));
        }
        if (cs.length() == 0) return;
        body.addView(sectionLabel("Other orders with this reference"));
        for (int i = 0; i < cs.length(); i++) {
            JSONObject c = cs.optJSONObject(i); if (c == null) continue;
            MerchDb.Order x = new MerchDb.Order(); x.items = c.optString("items"); x.product = c.optString("product");
            body.addView(card("Sender " + c.optString("from") + "\nBuyer address " + c.optString("payaddr") + "\n" + itemsText(x)
                    + "\nTotal " + c.optString("amount") + " " + MerchMessage.label(c.optString("currency"))
                    + (c.optString("commitment").isEmpty() ? "\nNo payment secret (an older Shop)." : "")
                    + (i == committed ? "\nThe payment at your address commits to THIS order." : "")));
        }
        if (committed >= 0) {
            TextView b = button("Make the order the payment commits to the order of record", false);
            b.setOnClickListener(v -> io.execute(() -> {
                db.dues = this::contenderDue;
                if (db.switchToCommitted(o.ref)) recheck(db.order(o.ref));
                ui.post(() -> refreshOrderIfTopmost(o.ref));
            }));
            body.addView(b);
        }
    }

    private View statusButtons(final MerchDb.Order o) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        LinearLayout actionRow = null;
        int actionIndex = 0;
        String[] flow = {MerchDb.PAID, MerchDb.CONFIRMED, MerchDb.SHIPPED, MerchDb.DELIVERED};
        for (final String s : flow) {
            if (actionIndex++ % 2 == 0) { actionRow = new LinearLayout(this); actionRow.setOrientation(LinearLayout.HORIZONTAL); wrap.addView(actionRow); }
            TextView b = button(s, s.equals(o.status));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(6); b.setLayoutParams(lp);
            b.setOnClickListener(v -> advanceStatus(o, s));
            actionRow.addView(b);
        }
        return wrap;
    }

    private void advanceStatus(final MerchDb.Order o, final String status) { advanceStatus(o, status, false); }

    private void advanceStatus(final MerchDb.Order o, final String status, final boolean quiet) {
        io.execute(() -> {
            db.setStatus(o.ref, status);
            // notify the buyer
            MerchMessage m = new MerchMessage();
            m.type = MerchMessage.STATUS_UPDATE; m.ref = o.ref; m.status = status;
            m.from = myId; m.to = o.counterparty; m.randomid = MailText.randomId(); m.date = System.currentTimeMillis();
            CommsTransport.sendMessage(node, crypto, o.counterparty, m.toWire(), new CommsTransport.SendCb() {
                @Override public void onSent(String txid) {}
                @Override public void onFailed(String e) {}
            });
            ui.post(() -> { if (!quiet) toast("Marked " + status); refreshOrderIfTopmost(o.ref); });
        });
    }

    /** Refresh only the screen the vendor is actually looking at — never yank navigation from
     *  a background event (auto-delivery, scans). Same pattern as onScanDone. */
    private void refreshOrderIfTopmost(String ref) {
        if (isFinishing() || isDestroyed()) return;
        View top = stack.peek();
        Object tag = top == null ? null : top.getTag();
        if (tag instanceof String && tag.equals("order:" + ref)) {
            if (!replyDrafts.defer(ref)) showOrderAsync(ref, false);
        }
        else if (stack.size() == 1) refreshTop(buildOrders());
    }

    private void refreshVisibleOrderOrList() {
        if (isFinishing() || isDestroyed()) return;
        View top = stack.peek(); Object tag = top == null ? null : top.getTag();
        if (tag instanceof String && ((String) tag).startsWith("order:")) refreshOrderIfTopmost(((String) tag).substring(6));
        else if (stack.size() == 1) refreshTop(buildOrders());
    }

    // ---- NFT delivery ----

    /** Order lines carrying an NFT tokenid, as {name, tokenid, quantity, stateIdx, bundle}.
     *  stateIdx "0" + bundle "0" = plain NFT (delivered via `send`); stateIdx ≥1 = a StateNFT piece
     *  (state-replay transfer of the exact coin); bundle "1" = the COMPLETE collection (every held
     *  piece, one transfer each). Empty on plain orders. */
    private List<String[]> nftLines(MerchDb.Order o) {
        List<String[]> out = new ArrayList<>();
        if (o == null || o.items == null || o.items.isEmpty()) return out;
        try {
            JSONArray a = Listing.items(o.items);   // refused unread when too large or too deep (M2)
            if (a == null) return out;
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i); if (it == null) continue;
                String tokenid = it.optString("nftTokenId", "");
                if (tokenid.isEmpty()) continue;
                int q = Math.max(1, it.optInt("quantity", 1));
                int idx = Math.max(0, it.optInt("nftStateIdx", 0));
                int bundle = it.optInt("nftBundle", 0) > 0 ? 1 : 0;
                String[] l = {it.optString("product", "NFT"), tokenid, String.valueOf(q), String.valueOf(idx), String.valueOf(bundle), String.valueOf(i), "0"};
                for (String[] before : out) if (baseKey("", before).equals(baseKey("", l))) { l[6] = "1"; break; }   // a repeat of an earlier line (L5)
                out.add(l);
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** A StateNFT piece's coin carries exactly one unit of its token (review L2). */
    private static boolean oneUnit(JSONObject c) {
        try { return new java.math.BigDecimal(c.optString("tokenamount", "")).compareTo(java.math.BigDecimal.ONE) == 0; }
        catch (NumberFormatException e) { return false; }
    }

    /** The delivery ledger's key for one order line: the order ref and the line's index in the order (L6). */
    static String ledgerKey(String ref, String[] line) { return ref + "|" + line[5]; }

    /** Meta/set key for one deliverable line. Plain NFTs keep the historical ref:tokenid shape (so
     *  existing nftsent: metas survive); pieces append #idx; complete-collection bundles #ALL. */
    private static String lineKey(String ref, String[] line) {
        if (line.length > 6 && "1".equals(line[6])) return baseKey(ref, line) + "@" + line[5];   // a repeated line gets its own key (L5)
        return baseKey(ref, line);
    }

    private static String baseKey(String ref, String[] line) {
        if ("1".equals(line[4])) return ref + ":" + line[1] + "#ALL";
        return ref + ":" + line[1] + ("0".equals(line[3]) ? "" : "#" + line[3]);
    }

    private View nftSendRow(OrderModel m, final MerchDb.Order o, final String[] line) {
        final String name = line[0], tokenid = line[1], qty = line[2];
        final boolean piece = !"0".equals(line[3]);
        final boolean bundle = "1".equals(line[4]);
        final String key = lineKey(o.ref, line);
        final String label = bundle ? "the complete collection (\"" + name + "\")"
                : piece ? "\"" + name + "\"" : qty + " × " + name;
        String sentTx = m.sentTx.getOrDefault(key, "");
        if (!sentTx.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("✓ Sent " + label + (("sent".equals(sentTx) || "posted".equals(sentTx)) ? "" : " · " + sentTx));
            t.setTextColor(Design.IN); t.setTextSize(13f); t.setPadding(0, dp(4), 0, dp(4));
            return t;
        }
        LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL);
        boolean inFlight = nftSending.contains(key);
        // A send that did not finish may have gone: nothing more is sent until the vendor has checked the history.
        if (DeliveryLedger.RESERVED.equals(m.ledger.get(key)) && !inFlight) {
            wrap.addView(label("This line's send did not finish and may have gone. Check your wallet's transaction history for "
                    + (bundle ? "the pieces" : piece ? "edition #" + line[3] : qty) + " of token\n" + tokenid + "\nto\n" + o.payaddr
                    + "\nNothing more is sent for it until you say it was not sent."));
            TextView rel = button("I checked: it was NOT sent", true);
            rel.setOnClickListener(v -> dialog().setTitle("It was not sent?")
                    .setMessage("Only if your wallet's history shows no send of token\n" + tokenid + "\nto\n" + o.payaddr + "\nfor this order. If it went, saying it did not lets it be sent twice.")
                    .setPositiveButton("It was not sent", (d, w) -> io.execute(() -> { DeliveryLedger.mark(db.getWritableDatabase(), ledgerKey(o.ref, line), DeliveryLedger.RELEASED); ui.post(() -> refreshOrderIfTopmost(o.ref)); }))
                    .setNegativeButton("Cancel", null).show());
            wrap.addView(rel);
            TextView went = button("I checked: it WAS sent", false);
            went.setOnClickListener(v -> dialog().setTitle("It was sent?")
                    .setMessage("Only if your wallet's history shows the send of token\n" + tokenid + "\nto\n" + o.payaddr + "\nfor this order. The line is then marked sent and never sent again.")
                    .setPositiveButton("It was sent", (d, w) -> io.execute(() -> {
                        DeliveryLedger.mark(db.getWritableDatabase(), ledgerKey(o.ref, line), DeliveryLedger.SENT);
                        db.setMeta("nftsent:" + key, "sent (checked by you)");
                        ui.post(() -> refreshOrderIfTopmost(o.ref));
                    }))
                    .setNegativeButton("Cancel", null).show());
            wrap.addView(went);
            return wrap;
        }
        TextView b = button(inFlight ? "Sending…" : "Send " + label + " to buyer", !inFlight);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6); b.setLayoutParams(lp);
        final TextView why = new TextView(this);
        why.setText("Checking…"); why.setTextColor(Design.DIM2); why.setTextSize(11f); why.setPadding(0, dp(2), 0, 0);
        b.setOnClickListener(v -> {
            if (nftSending.contains(key)) return;
            checkDelivery(o.ref, line, verdict -> {
                if (!verdict.reasons.isEmpty()) {   // refused: there is no override
                    dialog().setTitle("NFT not sent").setMessage("Not sent:\n• " + String.join("\n• ", verdict.reasons))
                            .setPositiveButton("OK", null).show();
                    return;
                }
                MerchDb.Order now = verdict.order;
                dialog()
                        .setTitle("Send NFT")
                        .setMessage("Send " + label + "?\n\nToken id\n" + tokenid + "\n\nQuantity\n"
                                + (bundle ? "every piece you hold" : piece ? "1 (edition #" + line[3] + ")" : qty)
                                + "\n\nTo\n" + now.payaddr + "\n\nPaid on chain\n" + verdict.paid.toPlainString() + " of " + verdict.due.toPlainString() + " due"
                                + "\n\nThis transfers the NFT on-chain.")
                        .setPositiveButton("Send", (d, w) -> sendNft(now, line, false))
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        });
        wrap.addView(b);
        wrap.addView(why);
        why.setText(m.verdict.get(key));   // the last check's verdict: a check runs only on Send or the automatic attempt
        return wrap;
    }

    /** The verdict of the last check of this line, as stored by checkDelivery. */
    String storedVerdict(String key) {
        String at = db.getMeta("verdictat:" + key, "");
        if (at.isEmpty()) return "Not checked yet: tap Send to check this line (nothing is sent unless every check passes).";
        String v = db.getMeta("verdict:" + key, "");
        return v.isEmpty() ? "Every check passed at block " + at + ": ready to send."
                : "At block " + at + ", nothing could be sent until:\n• " + v.replace("\n", "\n• ");
    }

    /** Deliver one order line. StateNFT pieces (stateIdx ≥ 1) transfer the exact coin with its state
     *  replayed; plain NFTs go via `send` (atomic, mark-on-post). auto=true is the headless
     *  payment-confirmed path: no toasts, notifications instead, refresh only if visible. */
    private void sendNft(final MerchDb.Order o, final String[] line, final boolean auto) {
        final String key = lineKey(o.ref, line);
        if (!nftSending.add(key)) return;   // one check-and-send per line at a time
        checkDelivery(o.ref, line, v -> {
            if (auto && DeliveryRules.onlyWaiting(v.reasons)) { nftSending.remove(key); return; }   // confirmations to come: waiting, never a failure
            if (!v.reasons.isEmpty()) { deliverFailed(o, key, "Not sent:\n• " + String.join("\n• ", v.reasons), auto); return; }
            io.execute(() -> {
                final String why = !db.getMeta("nftsent:" + key, "").isEmpty() ? "this line was already sent"
                        : DeliveryLedger.reserve(db.getWritableDatabase(), ledgerKey(o.ref, line), v.product, v.token, v.qty, v.stock, v.held, v.listing);
                final MerchDb.Order now = db.order(o.ref);   // the fresh row the checks used: its buyer address
                ui.post(() -> {
                    if (why != null) { deliverFailed(o, key, "Not sent: " + why, auto); return; }
                    if (auto) autoTried.add(key);
                    if (auto) android.util.Log.i("miniMallInbox", "automatic delivery: " + v.qty.toPlainString() + " of token " + v.token
                            + " for order " + now.ref + " to " + now.payaddr);
                    nftSending.remove(key);   // each transfer path below takes the line again
                    if ("1".equals(line[4])) { collectionDeliver(now, line, auto); return; }
                    if (!"0".equals(line[3])) { stateNftDeliver(now, line, auto); return; }
                    plainDeliver(now, line, key, auto);
                });
            });
        });
    }

    static long tipOf(JSONObject b) {
        try { return Long.parseLong(b == null || b.optJSONObject("response") == null ? "" : b.optJSONObject("response").optString("block", "")); }
        catch (NumberFormatException e) { return 0; }
    }

    /** Every rule of DeliveryRules, from fresh reads: the chain tip, the token, the vendor's holdings, the
     *  payment coins at the vendor's own address, and (on the io thread) the order, its listing and the ledger. */
    private void checkDelivery(final String ref, final String[] line, final java.util.function.Consumer<DeliveryRules.Verdict> then) {
        final String tokenid = line[1];
        final boolean askToken = DeliveryGuard.basic(tokenid) == null;
        ask("block", b -> ask(askToken ? "tokens tokenid:" + tokenid : null, t -> ask(askToken ? "coins relevant:true tokenid:" + tokenid : null, h ->
                settlePayments(ref, tipOf(b), paid -> io.execute(() -> {
            DeliveryRules.Inputs in = new DeliveryRules.Inputs();
            in.order = db.order(ref);
            in.mismatch = db.mismatch(in.order);
            in.deliverable = db.deliverable(in.order);
            in.conflict = db.conflict(in.order);
            in.nonce = db.nonce(in.order);
            in.listing = in.order == null ? null : listingFor(in.order.shopId);
            in.line = line;
            String key = lineKey(ref, line);
            android.database.sqlite.SQLiteDatabase w = db.getWritableDatabase();
            in.lineState = !db.getMeta("nftsent:" + key, "").isEmpty() ? DeliveryLedger.SENT : DeliveryLedger.state(w, ledgerKey(ref, line));
            in.resuming = !db.getMeta("nftposted:" + key, "").isEmpty();
            in.tokenReply = t; in.holdingsReply = h; in.paid = paid;
            in.tip = tipOf(b);
            in.minConf = minConf();
            DeliveryRules.Verdict v = DeliveryRules.reasons(in);
            if (!v.product.isEmpty()) {
                String tokenPrefix = v.product.substring(0, v.product.lastIndexOf('|', v.product.lastIndexOf('|') - 1) + 1);   // shop|token|
                in.delivered = DeliveryLedger.delivered(w, v.product, v.listing); in.inflight = DeliveryLedger.inflight(w, tokenid);
                in.deliveredOfToken = DeliveryLedger.deliveredOfToken(w, tokenPrefix, v.listing); in.bundleTaken = DeliveryLedger.bundleTaken(w, tokenPrefix, v.listing);
                v = DeliveryRules.reasons(in);
            }
            final DeliveryRules.Verdict out = v;
            db.setMeta("verdict:" + key, v.reasons.isEmpty() ? "" : String.join("\n", v.reasons));   // shown on the screen until the next check
            db.setMeta("verdictat:" + key, String.valueOf(in.tip));
            ui.post(() -> then.accept(out));
        })))));
    }

    /** Once per block: every paid order's coins that reached the minimum confirmations are read back by id and kept
     *  confirmed, so revenue the vendor spends or consolidates afterwards still counts for its delivery. */
    private void confirmSweep(final long tip) {
        io.execute(() -> {
            for (MerchDb.Order o : db.orders()) {
                if (!"seller".equals(o.role) || !o.paid) continue;
                boolean open = false;
                for (DeliveryRules.PaidCoin c : paidCoins(o.ref)) if (!DeliveryRules.PaidCoin.CONFIRMED.equals(c.state)) open = true;
                if (open) { final String ref = o.ref; ui.post(() -> settlePayments(ref, tip, ignored -> {})); }
            }
        });
    }

    /** Paid (or part paid) under an Inbox that kept no coin list: no paidcoins and no paycoin marker for it. */
    boolean legacyPaid(MerchDb.Order o) {
        return o != null && "seller".equals(o.role) && (o.paid || !o.paidAmount.isEmpty()) && paidCoins(o.ref).isEmpty();
    }

    static final int FIND_WINDOW = 64, FIND_MAX_READS = 400, FIND_MAX_BLOCKS = 50_000;

    /** M2 a, the vendor's "Find this order's payment": reads the vendor address window by window, from the tip back to
     *  the order's block (else its date), at most FIND_WINDOW blocks per read (halved when a reply is refused, so no
     *  reply is large) with relevant:false, depth and coinage, in the currency's token only; records each coin that is
     *  this order's payment (its ref in port 1, no commitment for an order without a secret, its commitment otherwise)
     *  with its block, and confirms it when it is deep enough. Never more than FIND_MAX_READS reads or FIND_MAX_BLOCKS. */
    void findPayment(final String ref) {
        io.execute(() -> {
            final MerchDb.Order o = db.order(ref);
            final String token = o == null ? null : MerchMessage.tokenFor(o.currency);
            if (o == null || token == null || !Util.isValidAddress(vendorAddr) || chainBlock <= 0) { db.setMeta("findpayment:" + ref, "unread"); ui.post(() -> refreshOrderIfTopmost(ref)); return; }
            final long tip = chainBlock;
            long ob;
            try { ob = Long.parseLong(db.getMeta("orderblock:" + ref, "0")); } catch (NumberFormatException e) { ob = 0; }
            if (ob <= 0) ob = tip - Math.max(0, System.currentTimeMillis() - o.date) / 25_000L - 100;   // its date, at 2 x the block time
            final long from = Math.max(1, Math.max(ob, tip - FIND_MAX_BLOCKS));
            final String nonce = db.nonce(o);
            ui.post(() -> findWindow(ref, token, nonce, tip, from, 0, FIND_WINDOW, 0, 0));
        });
    }

    private void findWindow(String ref, String token, String nonce, long tip, long from, long coinage, int window, int reads, int found) {
        if (tip - coinage < from || reads >= FIND_MAX_READS) { endFind(ref, found, tip - coinage < from); return; }
        String cmd = "coins relevant:false address:" + vendorAddr + " tokenid:" + token + " depth:" + (coinage + window) + " coinage:" + coinage;
        ask(cmd, r -> {
            JSONArray a = r == null || !r.optBoolean("status", false) ? null : r.optJSONArray("response");
            if (a == null) {   // refused or too large: a smaller window, else stop with what was read
                if (window > 1) findWindow(ref, token, nonce, tip, from, coinage, window / 2, reads + 1, found);
                else endFind(ref, found, false);
                return;
            }
            io.execute(() -> {
                int n = 0;
                for (int i = 0; i < a.length(); i++) if (foundCoin(ref, nonce, token, tip, a.optJSONObject(i))) n++;
                final int total = found + n;
                ui.post(() -> findWindow(ref, token, nonce, tip, from, coinage + window, FIND_WINDOW, reads + 1, total));
            });
        });
    }

    /** One coin of a find: kept for the order when it is its payment; its marker makes it counted once only. */
    boolean foundCoin(String ref, String nonce, String token, long tip, JSONObject c) {
        if (c == null || !token.equalsIgnoreCase(c.optString("tokenid"))) return false;
        String h = CommsScanner.statePort(c, 1), cc = CommsScanner.statePort(c, 2);
        if (h == null || !ref.equals(DeliveryRules.text(h)) || !(nonce.isEmpty() ? cc == null : MerchMessage.commitment(nonce).equalsIgnoreCase(cc))) return false;
        String id = c.optString("coinid").toLowerCase(java.util.Locale.ROOT);
        long created = c.optLong("created", 0);
        if (!id.matches("0x[0-9a-f]{1,128}") || created <= 0) return false;
        String owner = db.getMeta("paycoin:" + id, "");
        if (!owner.isEmpty() && !owner.equals(ref)) return false;
        String amount = MerchDb.coinAmount(c);
        JSONArray pc;
        try { pc = new JSONArray(db.getMeta("paidcoins:" + ref, "[]")); } catch (Exception e) { pc = new JSONArray(); }
        for (int i = 0; i < pc.length(); i++) if (pc.optJSONObject(i) != null && id.equalsIgnoreCase(pc.optJSONObject(i).optString("coinid"))) return false;
        try { pc.put(new JSONObject().put("coinid", id).put("amount", amount).put("tokenid", token).put("commitment", cc == null ? "" : cc.toLowerCase(java.util.Locale.ROOT)).put("created", created)); }
        catch (org.json.JSONException e) { return false; }
        db.setMeta("paidcoins:" + ref, pc.toString());
        db.setMeta("paycoin:" + id, ref);
        if (tip - created + 1 >= minConf()) confirm(id, amount, token, created, tip);
        return true;
    }

    private void endFind(final String ref, final int found, final boolean complete) {
        io.execute(() -> {
            db.setMeta("findpayment:" + ref, found > 0 ? "found" : complete ? "none" : "unread");
            ui.post(() -> refreshOrderIfTopmost(ref));
        });
    }

    /** A coin confirmed for this Inbox: {coinid, amount, tokenid, created, block}, or null. */
    JSONObject confirmed(String coinid) {
        String v = db.getMeta("paidconf:" + coinid.toLowerCase(java.util.Locale.ROOT), "");
        if (!v.startsWith("{")) return null;   // a bare block from an unreleased build: read the coin again
        try { JSONObject k = new JSONObject(v); return k.optString("amount").isEmpty() ? null : k; } catch (org.json.JSONException e) { return null; }
    }

    void confirm(String coinid, String amount, String tokenid, long created, long block) {
        try {
            db.setMeta("paidconf:" + coinid.toLowerCase(java.util.Locale.ROOT), new JSONObject().put("coinid", coinid).put("amount", amount)
                    .put("tokenid", tokenid).put("created", created).put("block", block).toString());
            db.setMeta("paidgone:" + coinid.toLowerCase(java.util.Locale.ROOT), "");
        } catch (org.json.JSONException ignored) {}
    }

    /** A coin read back and not found: read again only after 2, 4, 8 ... blocks (at most a day), never every block. */
    void gone(String coinid, long tip) {
        String k = "paidgone:" + coinid.toLowerCase(java.util.Locale.ROOT);
        int n;
        try { n = new JSONObject(db.getMeta(k, "{}")).optInt("n", 0); } catch (org.json.JSONException e) { n = 0; }
        n = Math.min(n + 1, 11);
        try { db.setMeta(k, new JSONObject().put("n", n).put("until", tip + Math.min(1440, 1L << n)).toString()); } catch (org.json.JSONException ignored) {}
    }

    long goneUntil(String coinid) {
        String v = db.getMeta("paidgone:" + coinid.toLowerCase(java.util.Locale.ROOT), "");
        if (v.isEmpty()) return 0;
        try { return new JSONObject(v).optLong("until", 0); } catch (org.json.JSONException e) { return 0; }
    }

    void tagLedger() { DeliveryLedger.tagUntagged(db.getWritableDatabase(), shop -> Listing.id(listingFor(shop))); }

    /** recordCameFirst for a stored order, from its orderblock and the coins counted for it. */
    boolean recordCameFirst(MerchDb.Order o) {
        long ob;
        try { ob = Long.parseLong(db.getMeta("orderblock:" + o.ref, "0")); } catch (NumberFormatException e) { ob = 0; }
        List<Long> blocks = new ArrayList<>();
        JSONArray cs = db.contenders(o.ref);
        for (int i = 0; i < cs.length(); i++) {
            String b = db.getMeta("contenderblock:" + o.ref + ":" + cs.optJSONObject(i).optString("coinid").toLowerCase(java.util.Locale.ROOT), "");
            try { blocks.add(Long.parseLong(b)); } catch (NumberFormatException e) { blocks.add(0L); }
        }
        return DeliveryRules.recordCameFirst(ob, paidCoins(o.ref), db.contendedCoins(o.ref).length() > 0, blocks);
    }

    /** The coins counted for an order: those MerchDb kept with their block, then older ones known only by their
     *  paycoin marker (read back by id for their block and amount). Coins already confirmed stay confirmed. */
    List<DeliveryRules.PaidCoin> paidCoins(String ref) {
        List<DeliveryRules.PaidCoin> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        JSONArray pc;
        try { pc = new JSONArray(db.getMeta("paidcoins:" + ref, "[]")); } catch (Exception e) { pc = new JSONArray(); }
        for (int i = 0; i < pc.length(); i++) {
            JSONObject x = pc.optJSONObject(i); if (x == null) continue;
            String id = x.optString("coinid").toLowerCase(java.util.Locale.ROOT);
            if (id.isEmpty() || !seen.add(id)) continue;
            String owner = db.getMeta("paycoin:" + id, "");
            if (!owner.isEmpty() && !owner.equals(ref)) continue;   // counted for another order: never here
            out.add(new DeliveryRules.PaidCoin(id, x.optString("amount", null), x.optString("tokenid", null), x.optLong("created", 0)));
        }
        try (android.database.Cursor q = db.getReadableDatabase().rawQuery("SELECT k FROM meta WHERE k LIKE 'paycoin:%' AND v=?", new String[]{ref})) {
            while (q.moveToNext()) {
                String id = q.getString(0).substring(8).toLowerCase(java.util.Locale.ROOT);
                if (seen.add(id)) out.add(new DeliveryRules.PaidCoin(id, null, null, 0));   // counted before 0.5.31
            }
        }
        for (DeliveryRules.PaidCoin c : out) {
            // confirmed for good, with what was read then (point release M2 b): a coin known only by its marker keeps
            // its amount, token and block once it was confirmed, even after the vendor spends it
            JSONObject k = confirmed(c.coinid);
            if (k == null) continue;
            c.state = DeliveryRules.PaidCoin.CONFIRMED;
            if (c.amount == null) c.amount = k.optString("amount", null);
            if (c.tokenid == null) c.tokenid = k.optString("tokenid", null);
            if (c.created <= 0) c.created = k.optLong("created", 0);
        }
        return out;
    }

    /** Settle every coin counted for an order against the tip (DeliveryRules.settle): at most MAX_COIN_READS reads
     *  by id, one coin each, never the address; a coin found confirmed is kept confirmed ("paidconf:" = the block). */
    private void settlePayments(final String ref, final long tip, final java.util.function.Consumer<List<DeliveryRules.PaidCoin>> then) {
        io.execute(() -> {
            final MerchDb.Order o = db.order(ref);
            if (o == null) { ui.post(() -> then.accept(null)); return; }
            final String nonce = db.nonce(o);
            final int minConf = minConf();
            final List<DeliveryRules.PaidCoin> coins = paidCoins(ref);
            ui.post(() -> settleNext(ref, nonce, tip, minConf, coins, 0, 0, then));
        });
    }

    private void settleNext(String ref, String nonce, long tip, int minConf, List<DeliveryRules.PaidCoin> coins, int i, int reads,
                            java.util.function.Consumer<List<DeliveryRules.PaidCoin>> then) {
        if (i >= coins.size()) { then.accept(coins); return; }
        DeliveryRules.PaidCoin c = coins.get(i);
        boolean waiting = c.created > 0 && (tip <= 0 || tip - c.created + 1 < minConf);
        if (!waiting && !DeliveryRules.PaidCoin.CONFIRMED.equals(c.state) && goneUntil(c.coinid) > tip) {   // read again only after a back-off
            c.state = DeliveryRules.PaidCoin.GONE;
            settleNext(ref, nonce, tip, minConf, coins, i + 1, reads, then);
            return;
        }
        if (DeliveryRules.PaidCoin.CONFIRMED.equals(c.state) || waiting || reads >= DeliveryRules.MAX_COIN_READS || !c.coinid.matches("0x[0-9a-f]{1,128}")) {
            if (waiting) c.state = DeliveryRules.PaidCoin.WAITING;
            settleNext(ref, nonce, tip, minConf, coins, i + 1, reads, then);
            return;
        }
        ask("coins relevant:true coinid:" + c.coinid, r -> {
            DeliveryRules.settle(c, r, ref, nonce, tip, minConf);
            if (DeliveryRules.PaidCoin.CONFIRMED.equals(c.state)) io.execute(() -> confirm(c.coinid, c.amount, c.tokenid, c.created, tip));
            else if (DeliveryRules.PaidCoin.GONE.equals(c.state)) io.execute(() -> gone(c.coinid, tip));
            ui.post(() -> settleNext(ref, nonce, tip, minConf, coins, i + 1, reads + 1, then));
        });
    }

    /** One node command; the callback gets the reply, or null on no answer (or when cmd is null). */
    private void ask(String cmd, java.util.function.Consumer<JSONObject> next) {
        if (cmd == null) { next.accept(null); return; }
        node.cmd(cmd, new NodeApi.Cb() {   // item 8: a reply after this screen is gone reaches nothing
            @Override public void onResult(JSONObject j) { if (!gone()) next.accept(j); }
            @Override public void onError(String m) { if (!gone()) next.accept(null); }
        });
    }

    private boolean gone() { return isFinishing() || isDestroyed(); }

    /** Item 8: every dialog is shown only while this Activity is alive (a reply or a scan can arrive after it). */
    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(this) {
            @Override public AlertDialog show() { return gone() ? create() : super.show(); }
        };
    }

    /** The vendor's own listing for a shop (imported with ⇪), or null. */
    private JSONObject listingFor(String shopId) {
        String raw = db.getMeta("listing:" + shopId, "");
        if (raw.isEmpty()) return null;
        try { return new JSONObject(raw); } catch (Exception e) { return null; }
    }

    /** Minimum confirmations a payment coin needs before anything is sent: vendor setting, default 3, never below 1. */
    private int minConf() {
        try { return Math.max(1, Integer.parseInt(db.getMeta("minconf", String.valueOf(DeliveryRules.DEFAULT_MIN_CONF)))); }
        catch (NumberFormatException e) { return DeliveryRules.DEFAULT_MIN_CONF; }
    }

    private void plainDeliver(final MerchDb.Order o, final String[] line, final String key, final boolean auto) {
        final String tokenid = line[1], qty = line[2];
        if (!nftSending.add(key)) return;
        refreshOrderIfTopmost(o.ref);
        // First a dry run, to learn the coins this send would spend (item 9): the first attempt's coins are kept, and a
        // later attempt must spend at least one of them, so if the first one went after all, the two conflict.
        final String dry;
        try { dry = "send amount:" + qty + " address:" + o.payaddr + " tokenid:" + tokenid + " state:" + new JSONObject().put("1", "0x" + com.eurobuddha.comms.Hex.to(o.ref.getBytes(StandardCharsets.UTF_8))) + " dryrun:true"; }
        catch (org.json.JSONException e) { io.execute(() -> DeliveryLedger.mark(db.getWritableDatabase(), ledgerKey(o.ref, line), DeliveryLedger.RELEASED)); deliverFailed(o, key, "the send could not be prepared", auto); return; }
        node.cmd(dry, new NodeApi.Cb() {
            @Override public void onResult(JSONObject r) {
                final java.util.Set<String> inputs = dryRunInputs(r);
                io.execute(() -> {
                    String why = attemptCoins(key, inputs);
                    ui.post(() -> {
                        if (why != null) {   // nothing reached the node: the line is free again
                            io.execute(() -> DeliveryLedger.mark(db.getWritableDatabase(), ledgerKey(o.ref, line), DeliveryLedger.RELEASED));
                            deliverFailed(o, key, why, auto);
                            return;
                        }
                        plainSend(o, line, key, auto, dry.substring(0, dry.length() - " dryrun:true".length()));
                    });
                });
            }
            @Override public void onError(String m) {
                io.execute(() -> DeliveryLedger.mark(db.getWritableDatabase(), ledgerKey(o.ref, line), DeliveryLedger.RELEASED));
                ui.post(() -> deliverFailed(o, key, "the send could not be prepared: " + m, auto));
            }
        });
    }

    /** The input coin ids (lower case) of a send dry run, or empty when it gave none. */
    static java.util.Set<String> dryRunInputs(JSONObject r) {
        java.util.Set<String> out = new java.util.HashSet<>();
        JSONObject resp = r == null || !r.optBoolean("status", false) ? null : r.optJSONObject("response");
        JSONObject body = resp == null ? null : resp.optJSONObject("body");
        JSONObject txn = body == null ? null : body.optJSONObject("txn");
        JSONArray in = txn == null ? null : txn.optJSONArray("inputs");
        for (int i = 0; in != null && i < in.length(); i++) { JSONObject c = in.optJSONObject(i); if (c != null && !c.optString("coinid").isEmpty()) out.add(c.optString("coinid").toLowerCase(java.util.Locale.ROOT)); }
        return out;
    }

    /** Keeps the first attempt's input coins of a line; a later attempt must spend at least one of them. Returns why
     *  not, or null. */
    String attemptCoins(String key, java.util.Set<String> inputs) {
        if (inputs.isEmpty()) return "the node did not say which coins this send would spend; nothing was sent";
        java.util.Set<String> first = new java.util.HashSet<>();
        try { JSONArray a = new JSONArray(db.getMeta("attemptcoins:" + key, "[]")); for (int i = 0; i < a.length(); i++) first.add(a.getString(i)); } catch (Exception ignored) {}
        if (first.isEmpty()) { db.setMeta("attemptcoins:" + key, new JSONArray(inputs).toString()); return null; }
        if (java.util.Collections.disjoint(first, inputs)) {
            // every coin of the first attempt was spent by a transfer this app recorded for another line: the first
            // attempt cannot have gone, so this one may use other coins
            boolean elsewhere = true;
            for (String c : first) { String by = db.getMeta("spentby:" + c, ""); if (by.isEmpty() || by.equals(key)) { elsewhere = false; break; } }
            if (!elsewhere) return "this send would not spend any coin of the first attempt for this line (" + String.join(", ", first)
                    + "): if the first send went, say so (it WAS sent); nothing was sent";
            db.setMeta("attemptcoins:" + key, new JSONArray(inputs).toString());
        }
        return null;
    }

    /** The real send (the same command as its dry run, which CommsTransport.sendPayment would build): its reply's
     *  actual input coins join the line's attempt coins and are marked spent by this line (point release m1). */
    private void plainSend(final MerchDb.Order o, final String[] line, final String key, final boolean auto, final String cmd) {
        final String tokenid = line[1];
        final boolean[] inCall = {true};
        node.cmd(cmd, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                if (!(j.optBoolean("status", false) || j.optBoolean("pending", false))) { failed(j.optString("error", "the node rejected the send")); return; }
                final java.util.Set<String> used = dryRunInputs(j);
                JSONObject r = j.optJSONObject("response");
                final String txid = r == null ? "" : r.optString("txpowid", "");
                io.execute(() -> sentCoins(key, used));
                markSentAndMaybeAdvance(o, key, txid.isEmpty() ? "sent" : txid, auto);
            }
            @Override public void onError(String m) { failed(m); }
            private void failed(final String e) {
                // Refused before anything reached the node: certainly not sent, the line is free again and its attempt
                // coins are forgotten. Any later failure may have been posted: the line stays reserved until the
                // vendor checks the history.
                if (inCall[0]) io.execute(() -> { DeliveryLedger.mark(db.getWritableDatabase(), ledgerKey(o.ref, line), DeliveryLedger.RELEASED); db.setMeta("attemptcoins:" + key, ""); });
                // A StateNFT collection sold by an old .shop has no edition index — `send` fails with
                // an opaque sendable:0; name the real cause for the vendor.
                if (isKnownStateCollection(tokenid)) deliverFailed(o, key, "this is a StateNFT collection but the order names no edition — the buyer's miniMall is outdated", auto);
                else deliverFailed(o, key, e, auto);
            }
        });
        inCall[0] = false;
    }

    /** The coins a sent line actually spent: added to its attempt coins, and each marked spent by this line. */
    void sentCoins(String key, java.util.Set<String> used) {
        if (used.isEmpty()) return;
        java.util.Set<String> all = new java.util.HashSet<>(used);
        try { JSONArray a = new JSONArray(db.getMeta("attemptcoins:" + key, "[]")); for (int i = 0; i < a.length(); i++) all.add(a.getString(i)); } catch (Exception ignored) {}
        db.setMeta("attemptcoins:" + key, new JSONArray(all).toString());
        for (String c : used) db.setMeta("spentby:" + c, key);
    }

    // ---- StateNFT piece delivery (state-replay transfer, confirm by coin departure) ----

    /** Tokenids whose script fingerprints as a StateNFT collection (memoized). */
    private final java.util.HashMap<String, Boolean> stateNftScripts = new java.util.HashMap<>();
    /** Serial txn queue: build/sign/post grinds PoW — never run two at once on a phone. */
    private final ArrayDeque<Runnable> txQueue = new ArrayDeque<>();
    private boolean txBusy = false;

    private boolean isKnownStateCollection(String tokenid) {
        Boolean b = stateNftScripts.get(tokenid);
        if (b == null && Util.isValidHexId(tokenid)) {
            node.cmd("tokens tokenid:" + tokenid, new NodeApi.Cb() {
                @Override public void onResult(JSONObject j) {
                    Object resp = j.opt("response");
                    JSONObject tok = resp instanceof JSONObject ? (JSONObject) resp
                            : (resp instanceof JSONArray && ((JSONArray) resp).length() > 0 ? ((JSONArray) resp).optJSONObject(0) : null);
                    if (tok != null) stateNftScripts.put(tokenid, StateNft.isStateNftScript(tok.optString("script", "")));
                }
                @Override public void onError(String m) {}
            });
        }
        return b != null && b;
    }

    private void enqueueTx(Runnable job) {
        txQueue.add(job);
        pumpTxQueue();
    }
    private void pumpTxQueue() {
        if (txBusy) return;
        Runnable job = txQueue.poll();
        if (job == null) return;
        txBusy = true;
        job.run();
    }
    private void txDone() { txBusy = false; pumpTxQueue(); }

    private void deliverFailed(MerchDb.Order o, String key, String msg, boolean auto) {
        nftSending.remove(key);
        if (auto) {   // an automatic failure is notified once per line, not at every block (as Pandamonium)
            io.execute(() -> {   // m4: the once-per-line mark is read and written on io
                if ("1".equals(db.getMeta("notified:" + key, ""))) return;
                db.setMeta("notified:" + key, "1");
                ui.post(() -> notifyDelivery(o.ref, false, msg));
            });
        }
        else dialog().setTitle("NFT not sent").setMessage(msg).setPositiveButton("OK", null).show();   // whole ids: a dialog, not a toast
        refreshOrderIfTopmost(o.ref);
    }

    private void markSentAndMaybeAdvance(final MerchDb.Order o, final String key, final String txid, final boolean auto) {
        io.execute(() -> {
            db.setMeta("nftsent:" + key, txid == null || txid.isEmpty() ? "sent" : txid);
            MerchDb.Order fresh0 = db.order(o.ref);
            if (fresh0 != null) for (String[] l : nftLines(fresh0)) if (lineKey(o.ref, l).equals(key)) DeliveryLedger.mark(db.getWritableDatabase(), ledgerKey(o.ref, l), DeliveryLedger.SENT);
            MerchDb.Order fresh = db.order(o.ref);
            boolean all = fresh != null;
            if (fresh != null) for (String[] l : nftLines(fresh)) {
                if (db.getMeta("nftsent:" + lineKey(o.ref, l), "").isEmpty()) { all = false; break; }
            }
            final boolean advance = all
                    && (MerchDb.PAID.equals(fresh.status) || MerchDb.CONFIRMED.equals(fresh.status));
            final MerchDb.Order forStatus = fresh;
            ui.post(() -> {
                nftSending.remove(key);
                if (auto) notifyDelivery(o.ref, true, txid);
                else toast("NFT sent to the buyer");
                if (advance) advanceStatus(forStatus, MerchDb.SHIPPED, auto);
                else refreshOrderIfTopmost(o.ref);
            });
        });
    }

    /** Transfer the exact coin carrying edition #idx to the buyer, replaying every state port, then
     *  confirm by watching the coin LEAVE the UTXO set — `txnpost status:true` is not proof for a
     *  manual script-token txn (the statenft-suite hard rule). Only then does the line mark sent. */
    private void stateNftDeliver(final MerchDb.Order o, final String[] line, final boolean auto) {
        final String name = line[0], tokenid = line[1], idx = line[3];
        final String key = lineKey(o.ref, line);
        if (!idx.matches("^[1-9][0-9]*$")) { deliverFailed(o, key, "bad edition index", auto); return; }
        if (!nftSending.add(key)) return;
        refreshOrderIfTopmost(o.ref);
        node.cmd("coins relevant:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject coin = null;
                JSONArray arr = j.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c != null && !StateNft.isBuried(c) && idx.equals(StateNft.stamped(c)) && oneUnit(c)) { coin = c; break; }   // exactly 1 unit (L2)
                }
                if (coin == null) {
                    // Coin gone. If WE posted the transfer earlier, its departure IS the delivery.
                    String posted = db.getMeta("nftposted:" + key, "");
                    if (!posted.isEmpty()) { markSentAndMaybeAdvance(o, key, posted, auto); return; }
                    deliverFailed(o, key, "piece #" + idx + " of " + name + " is not in this wallet — sold or moved?", auto);
                    return;
                }
                if (!Util.isValidHexId(coin.optString("coinid", "")) || !StateNft.replayableState(coin)
                        || StateNft.safeAmount(coin).isEmpty()) {
                    deliverFailed(o, key, "piece #" + idx + " carries state this app won't replay", auto);
                    return;
                }
                final JSONObject theCoin = coin;
                enqueueTx(() -> runStateTransfer(o, key, tokenid, theCoin, auto));
            }
            @Override public void onError(String m) {
                deliverFailed(o, key, NodeApi.ERR_TOO_LONG.equals(m) ? "coin list too large for this node" : m, auto);
            }
        });
    }

    private void runStateTransfer(final MerchDb.Order o, final String key, final String tokenid,
                                  final JSONObject coin, final boolean auto) {
        final String coinid = coin.optString("coinid", "");
        // Deterministic txn id per coin: the pre-clean txndelete in transferCommands recovers from
        // a previous abnormal exit instead of failing txncreate on the stale txn.
        final String hex = coinid.replaceFirst("(?i)^0x", "");
        final String txn = "md" + hex.substring(0, Math.min(10, hex.length()));
        List<String> cmds = StateNft.transferCommands(txn, tokenid, coin, o.payaddr);
        CmdChain.run(node, cmds, "txndelete id:" + txn, new CmdChain.Done() {
            @Override public void ok(JSONObject last) {
                node.cmd("txndelete id:" + txn, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j) {}
                    @Override public void onError(String m) {}
                });
                String txid = last == null ? "" : last.optString("txpowid", "");
                if (txid.isEmpty() && last != null) {
                    JSONObject r = last.optJSONObject("response");
                    if (r != null) txid = r.optString("txpowid", "");
                }
                final String postedVal = txid.isEmpty() ? "posted" : txid;
                io.execute(() -> db.setMeta("nftposted:" + key, postedVal));
                txDone();
                watchDeparture(o, key, tokenid, coinid, postedVal, auto, 0);
            }
            @Override public void fail(String message) {
                txDone();
                deliverFailed(o, key, message, auto);
            }
        });
    }

    // ---- complete-collection delivery: every held piece, one state-replay transfer each ----

    /** Deliver a whole collection: gather every sendable piece, post one transfer per coin
     *  (serially — the NFT Wallet's send-collection pattern), then confirm by watching every
     *  posted coin leave the UTXO set. Retry re-gathers, so a partial delivery resumes where it
     *  stopped: already-transferred coins are simply gone from the next gather. */
    private void collectionDeliver(final MerchDb.Order o, final String[] line, final boolean auto) {
        final String name = line[0], tokenid = line[1];
        final String key = lineKey(o.ref, line);
        if (!nftSending.add(key)) return;
        refreshOrderIfTopmost(o.ref);
        node.cmd("coins relevant:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                final List<JSONObject> coins = new ArrayList<>();
                JSONArray arr = j.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null || !Util.isValidHexId(c.optString("coinid", ""))) continue;
                    // Same refusals as a single transfer: buried, unstamped (creator bypass live),
                    // or state this app won't replay.
                    if (StateNft.isBuried(c) || StateNft.stamped(c) == null
                            || !StateNft.replayableState(c) || StateNft.safeAmount(c).isEmpty() || !oneUnit(c)) continue;   // exactly 1 unit (L2)
                    coins.add(c);
                }
                if (coins.isEmpty()) {
                    // Nothing left to send. If WE posted transfers earlier, their departure IS the
                    // completed delivery; otherwise the collection isn't here.
                    String posted = db.getMeta("nftposted:" + key, "");
                    if (!posted.isEmpty()) { markSentAndMaybeAdvance(o, key, posted, auto); return; }
                    deliverFailed(o, key, "no pieces of " + name + " are in this wallet — sold or moved?", auto);
                    return;
                }
                enqueueTx(() -> bundleTransferNext(o, key, tokenid, coins, 0, new ArrayList<>(), new String[]{null}, auto));
            }
            @Override public void onError(String m) {
                deliverFailed(o, key, NodeApi.ERR_TOO_LONG.equals(m) ? "coin list too large for this node" : m, auto);
            }
        });
    }

    /** Serial per-coin transfer loop — one failure doesn't strand the rest (first error kept). */
    private void bundleTransferNext(final MerchDb.Order o, final String key, final String tokenid,
                                    final List<JSONObject> coins, final int i,
                                    final List<String> postedIds, final String[] firstError, final boolean auto) {
        if (i >= coins.size()) {
            txDone();
            if (postedIds.isEmpty()) {
                deliverFailed(o, key, firstError[0] == null ? "no transfers could be posted" : firstError[0], auto);
                return;
            }
            io.execute(() -> db.setMeta("nftposted:" + key, "posted"));
            watchBundleDeparture(o, key, tokenid, postedIds, firstError[0], auto, 0);
            return;
        }
        JSONObject coin = coins.get(i);
        final String coinid = coin.optString("coinid", "");
        final String hex = coinid.replaceFirst("(?i)^0x", "");
        final String txn = "mc" + hex.substring(0, Math.min(10, hex.length()));
        List<String> cmds = StateNft.transferCommands(txn, tokenid, coin, o.payaddr);
        CmdChain.run(node, cmds, "txndelete id:" + txn, new CmdChain.Done() {
            @Override public void ok(JSONObject last) {
                node.cmd("txndelete id:" + txn, new NodeApi.Cb() {
                    @Override public void onResult(JSONObject j) {}
                    @Override public void onError(String m) {}
                });
                postedIds.add(coinid);
                bundleTransferNext(o, key, tokenid, coins, i + 1, postedIds, firstError, auto);
            }
            @Override public void fail(String message) {
                if (firstError[0] == null && message != null && !message.isEmpty()) firstError[0] = message;
                bundleTransferNext(o, key, tokenid, coins, i + 1, postedIds, firstError, auto);
            }
        });
    }

    /** Wait for every posted coin to leave the UTXO set, then confirm nothing sendable remains. */
    private void watchBundleDeparture(final MerchDb.Order o, final String key, final String tokenid,
                                      final List<String> postedIds, final String firstError,
                                      final boolean auto, final int attempt) {
        if (isFinishing() || isDestroyed()) return;
        if (attempt >= WATCH_TRIES) {
            nftSending.remove(key);
            if (auto) notifyDelivery(o.ref, false, o.ref + " — transfers posted but unconfirmed; open the order to retry");
            else toast("Transfers posted but not yet confirmed — retry later from the order.");
            refreshOrderIfTopmost(o.ref);
            return;
        }
        ui.postDelayed(() -> node.cmd("coins relevant:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                java.util.HashSet<String> live = new java.util.HashSet<>();
                int sendableLeft = 0;
                JSONArray arr = j.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null) continue;
                    live.add(c.optString("coinid", ""));
                    if (!StateNft.isBuried(c) && StateNft.stamped(c) != null && StateNft.replayableState(c)) sendableLeft++;
                }
                boolean anyPostedLeft = false;
                for (String id : postedIds) if (live.contains(id)) { anyPostedLeft = true; break; }
                if (anyPostedLeft) { watchBundleDeparture(o, key, tokenid, postedIds, firstError, auto, attempt + 1); return; }
                if (sendableLeft == 0) { markSentAndMaybeAdvance(o, key, "posted", auto); return; }
                // Posted coins confirmed away, but some pieces failed to post — resumable.
                nftSending.remove(key);
                String msg = o.ref + " — " + postedIds.size() + " piece(s) delivered, " + sendableLeft + " remain"
                        + (firstError != null ? " (first error: " + firstError + ")" : "") + " — retry to send the rest";
                if (auto) { if (!"1".equals(db.getMeta("notified:" + key, ""))) { db.setMeta("notified:" + key, "1"); notifyDelivery(o.ref, false, msg); } }
                else toast(msg);
                refreshOrderIfTopmost(o.ref);
            }
            @Override public void onError(String m) {
                if (NodeApi.ERR_TOO_LONG.equals(m)) {
                    nftSending.remove(key);
                    if (auto) notifyDelivery(o.ref, false, o.ref + " — posted; can't confirm on this node");
                    refreshOrderIfTopmost(o.ref);
                } else watchBundleDeparture(o, key, tokenid, postedIds, firstError, auto, attempt + 1);
            }
        }), WATCH_INTERVAL_MS);
    }

    private static final long WATCH_INTERVAL_MS = 20000;
    private static final int WATCH_TRIES = 20;   // ~6.7 minutes

    /** Poll until the input coin leaves the UTXO set (delivery confirmed) or we give up for now.
     *  On timeout the line stays unsent + nftposted stays set — a later retry (or next app open)
     *  finds the coin gone and marks it sent, or finds it still here and reposts. */
    private void watchDeparture(final MerchDb.Order o, final String key, final String tokenid,
                                final String coinid, final String postedVal, final boolean auto, final int attempt) {
        if (isFinishing() || isDestroyed()) return;
        if (attempt >= WATCH_TRIES) {
            nftSending.remove(key);
            if (auto) notifyDelivery(o.ref, false, o.ref + " — transfer posted but unconfirmed; open the order to retry");
            else toast("Transfer posted but not yet confirmed — retry later from the order.");
            refreshOrderIfTopmost(o.ref);
            return;
        }
        ui.postDelayed(() -> node.cmd("coins relevant:true tokenid:" + tokenid, new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                boolean present = false;
                JSONArray arr = j.optJSONArray("response");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c != null && coinid.equals(c.optString("coinid", ""))) { present = true; break; }
                }
                if (!present) markSentAndMaybeAdvance(o, key, postedVal, auto);
                else watchDeparture(o, key, tokenid, coinid, postedVal, auto, attempt + 1);
            }
            @Override public void onError(String m) {
                if (NodeApi.ERR_TOO_LONG.equals(m)) {
                    // can't confirm on this node — leave unsent, nftposted resolves it later
                    nftSending.remove(key);
                    if (auto) notifyDelivery(o.ref, false, o.ref + " — posted; can't confirm on this node");
                    refreshOrderIfTopmost(o.ref);
                } else watchDeparture(o, key, tokenid, coinid, postedVal, auto, attempt + 1);
            }
        }), WATCH_INTERVAL_MS);
    }

    private View replyRow(final MerchDb.Order o, List<String[]> chat) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        for (String[] c : chat) {
            boolean incoming = "1".equals(c[0]);
            TextView t = new TextView(this);
            t.setText((incoming ? "Buyer: " : "You: ") + c[1]);
            t.setTextColor(incoming ? Design.TEXT : Design.DIM); t.setTextSize(13f);
            t.setPadding(0, dp(2), 0, dp(2));
            wrap.addView(t);
        }
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setPadding(0, dp(6), 0, 0);
        final EditText box = input("Reply…");
        replyDrafts.bind(o.ref, box, () -> refreshOrderIfTopmost(o.ref));
        box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView send = button("Send", false);
        send.setOnClickListener(v -> {
            if (crypto == null || myId == null) { toast("Connect to your node first."); return; }
            ReplyDrafts.Submission ticket = replyDrafts.begin(o.ref);
            if (ticket != null) sendReply(o, ticket);
        });
        bar.addView(box); bar.addView(send);
        wrap.addView(bar);
        return wrap;
    }

    private void sendReply(final MerchDb.Order o, final ReplyDrafts.Submission ticket) {
        final String rid = MailText.randomId();
        final String text = ticket.text();
        try {
            io.execute(() -> {
                try {
                    if (!db.insertChat(o.ref, false, text, rid, System.currentTimeMillis())) throw new IllegalStateException("Reply not saved");
                } catch (RuntimeException unavailable) {
                    ui.post(() -> replySaveFailed(ticket));
                    return;
                }
                MerchMessage m = new MerchMessage();
                m.type = MerchMessage.REPLY; m.ref = o.ref; m.message = text;
                m.from = myId; m.to = o.counterparty; m.randomid = rid; m.date = System.currentTimeMillis();
                CommsTransport.sendMessage(node, crypto, o.counterparty, m.toWire(), new CommsTransport.SendCb() {
                    @Override public void onSent(String txid) {}
                    @Override public void onFailed(String e) { ui.post(() -> {
                        if (!isFinishing() && !isDestroyed()) toast("Reply failed: " + e);
                    }); }
                });
                ui.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    replyDrafts.saved(ticket);
                    refreshOrderIfTopmost(o.ref);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) { replySaveFailed(ticket); }
    }
    private void replySaveFailed(ReplyDrafts.Submission ticket) {
        replyDrafts.failed(ticket);
        if (!isFinishing() && !isDestroyed()) toast("Reply could not be saved. Your draft is still here.");
    }

    // ---- CSV export ----

    private void exportCsv() {
        if (db.orders().isEmpty()) { toast("No orders to export yet."); return; }
        csvLauncher.launch("orders-" + System.currentTimeMillis() + ".csv");
    }

    private void writeCsv(android.net.Uri uri) {
        io.execute(() -> {
            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                StringBuilder sb = new StringBuilder();
                sb.append("ref,status,product,total,currency,paid,paidAmount,shipping,delivery,buyerKey,date\n");
                for (MerchDb.Order o : db.orders()) {
                    sb.append(csv(o.ref)).append(',').append(csv(o.status)).append(',').append(csv(o.product)).append(',')
                      .append(csv(o.amount)).append(',').append(csv(dispCurrency(o))).append(',').append(o.paid ? "yes" : "no").append(',')
                      .append(csv(o.paidAmount)).append(',').append(csv(o.shipping)).append(',').append(csv(o.delivery)).append(',')
                      .append(csv(o.counterparty)).append(',').append(csv(fmtDate(o.date))).append('\n');
                }
                os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                ui.post(() -> toast("Exported " + db.orders().size() + " orders"));
            } catch (Exception e) { ui.post(() -> toast("Export failed: " + e.getMessage())); }
        });
    }

    private static String csv(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return "\"" + s.replace("\"", "\"\"") + "\"";
        return s;
    }
    private static String fmtDate(long ms) {
        if (ms <= 0) return "";
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date(ms));
    }

    // ---- identity card (for pasting into the studio) ----

    private void showIdentityCard() {
        if (myId == null) { toast("Connect to your node first."); return; }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL); col.setPadding(dp(20), dp(16), dp(20), dp(8));
        TextView t = new TextView(this);
        t.setText("Paste this Vendor Card into the miniMall studio to build your Shop APK. It's your public shop key + receiving address — safe to share.");
        t.setTextColor(Design.DIM); t.setTextSize(12f); t.setPadding(0, 0, 0, dp(12));
        col.addView(t);
        final String card = vendorCard();
        ImageView qr = new ImageView(this);
        Bitmap bmp = QrUtil.qr(card, dp(220));
        if (bmp != null) { qr.setImageBitmap(bmp); qr.setAdjustViewBounds(true); col.addView(qr); }
        TextView val = new TextView(this);
        val.setText(card); val.setTextColor(Design.DIM2); val.setTextSize(10f);
        val.setPadding(0, dp(10), 0, 0); col.addView(val);
        dialog().setTitle("Vendor Card").setView(wrapScroll(col))
                .setPositiveButton("Copy", (d, w) -> copy(card))
                .setNegativeButton("Close", null).show();
    }

    /** publicId|receivingAddress — the studio bakes both into the Shop APK. */
    private String vendorCard() {
        return myId + "|" + (vendorAddr == null ? "" : vendorAddr);
    }

    // ---- helpers: formatting ----

    private String dispCurrency(MerchDb.Order o) {
        if (!o.currency.isEmpty()) return MerchMessage.label(o.currency);   // the wire value "USDT" is MxUSD on Minima
        return CommsTransport.MXUSD.equalsIgnoreCase(o.tokenid) ? "MxUSD" : "Minima";
    }
    private String shippingLabel(String s) {
        if ("uk".equalsIgnoreCase(s)) return "UK shipping";
        if ("intl".equalsIgnoreCase(s)) return "International";
        if ("digital".equalsIgnoreCase(s)) return "Digital";
        return s.isEmpty() ? "—" : s;
    }
    private String itemsText(MerchDb.Order o) {
        try {
            JSONArray a = Listing.items(o.items);
            if (a == null) return "(the order's items could not be read)";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i); if (it == null) continue;
                sb.append("• ").append(it.optString("product", "item"));
                String size = it.optString("size", ""); if (!size.isEmpty()) sb.append(" (").append(size).append(")");
                sb.append("  x").append(it.optInt("quantity", 1));
                String lt = it.optString("lineTotal", ""); if (!lt.isEmpty()) sb.append("  = ").append(lt);
                if (i < a.length() - 1) sb.append("\n");
            }
            return sb.length() == 0 ? (o.product.isEmpty() ? "—" : o.product) : sb.toString();
        } catch (Throwable e) { return o.product.isEmpty() ? "—" : o.product; }
    }
    private int statusColor(String s) {
        if (MerchDb.DELIVERED.equals(s) || MerchDb.SHIPPED.equals(s)) return Design.IN;
        if (MerchDb.PAID.equals(s) || MerchDb.CONFIRMED.equals(s)) return Design.ACCENT;
        if (MerchDb.UNDERPAID.equals(s) || MerchDb.WRONG_TOKEN.equals(s) || MerchDb.TOKEN_MISMATCH.equals(s)) return Design.RED;
        return Design.DIM2;
    }
    private static String timeAgo(long ms) {
        if (ms <= 0) return "";
        long d = System.currentTimeMillis() - ms;
        if (d < 60_000) return "just now";
        if (d < 3_600_000) return (d / 60_000) + "m ago";
        if (d < 86_400_000) return (d / 3_600_000) + "h ago";
        return (d / 86_400_000) + "d ago";
    }
    private String loadShopName() {
        try (java.io.InputStream is = getAssets().open("shop.json")) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n; while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return new JSONObject(new String(bos.toByteArray(), StandardCharsets.UTF_8)).optString("shopName", "");
        } catch (Exception e) { return ""; }
    }

    // ---- helpers: views ----

    private int dp(int v) { return Design.dp(this, v); }
    private LinearLayout column() {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(Design.BG);
        c.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return c;
    }
    private LinearLayout header(String title, boolean back) {
        LinearLayout h = new LinearLayout(this); h.setOrientation(LinearLayout.HORIZONTAL); h.setGravity(Gravity.CENTER_VERTICAL);
        h.setBackgroundColor(Design.SURFACE); h.setPadding(dp(8), dp(10), dp(8), dp(10));
        if (back) h.addView(iconBtn("‹", this::pop));
        TextView t = new TextView(this); t.setText(title); t.setTextColor(Design.TEXT); t.setTextSize(18f);
        t.setTypeface(null, Typeface.BOLD); t.setPadding(dp(8), 0, 0, 0); t.setMaxLines(1);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        h.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return h;
    }
    private TextView iconBtn(String glyph, Runnable onClick) {
        TextView b = new TextView(this); b.setText(glyph); b.setTextColor(Design.TEXT); b.setTextSize(22f);
        b.setContentDescription("‹".equals(glyph) ? "Back" : "⤓".equals(glyph) ? "Export orders" : "Vendor card");
        b.setMinWidth(dp(48)); b.setMinHeight(dp(48));
        b.setGravity(Gravity.CENTER); b.setPadding(dp(10), dp(2), dp(10), dp(2));
        b.setOnClickListener(v -> onClick.run());
        return b;
    }
    private TextView label(String s) { TextView t = new TextView(this); t.setText(s); t.setTextColor(Design.DIM); t.setTextSize(13f); t.setPadding(0, dp(8), 0, 0); return t; }
    private TextView sectionLabel(String s) {
        TextView t = new TextView(this); t.setText(s.toUpperCase()); t.setTextColor(Design.DIM2); t.setTextSize(11f);
        t.setTypeface(null, Typeface.BOLD); t.setPadding(0, dp(16), 0, dp(6)); return t;
    }
    private TextView card(String s) {
        TextView t = new TextView(this); t.setText(s); t.setTextColor(Design.TEXT); t.setTextSize(14f);
        t.setBackground(Design.roundBg(this, Design.SURFACE, 12)); t.setPadding(dp(14), dp(12), dp(14), dp(12)); return t;
    }
    private View kv(String k, String v) {
        LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0, dp(5), 0, dp(5));
        TextView kk = new TextView(this); kk.setText(k); kk.setTextColor(Design.DIM); kk.setTextSize(13f);
        kk.setLayoutParams(new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView vv = new TextView(this); vv.setText(v); vv.setTextColor(Design.TEXT); vv.setTextSize(13f);
        vv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        r.addView(kk); r.addView(vv); return r;
    }
    private View copyRow(String k, String shown, final String full) {
        View r = kv(k, shown); r.setOnClickListener(v -> copy(full)); return r;
    }
    private TextView button(String text, boolean active) {
        TextView b = new TextView(this); b.setText(text); b.setTextSize(13f); b.setGravity(Gravity.CENTER);
        b.setTextColor(active ? Design.ON_ACCENT : Design.TEXT);
        b.setBackground(Design.roundBg(this, active ? Design.ACCENT : Design.SURFACE2, 10));
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        return b;
    }
    private EditText input(String hint) {
        EditText e = new EditText(this); e.setHint(hint); e.setHintTextColor(Design.DIM2);
        e.setTextColor(Design.TEXT); e.setTextSize(14f); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setBackground(Design.roundBg(this, Design.SURFACE2, 10)); e.setPadding(dp(14), dp(10), dp(14), dp(10));
        return e;
    }
    private ScrollView wrapScroll(View v) { ScrollView s = new ScrollView(this); s.addView(v); return s; }

    private View buildPairingBanner() {
        TextView t = new TextView(this);
        t.setText("Enable miniMall Inbox in Minima Core → Apps to receive orders.");
        t.setTextColor(Design.ON_ACCENT); t.setBackgroundColor(Design.ACCENT);
        t.setTextSize(13f); t.setPadding(dp(16), dp(10), dp(16), dp(10));
        return t;
    }

    // ---- misc ----

    private void copy(String s) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("merch", s));
        toast("Copied");
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(new NotificationChannel(CH, "Orders", NotificationManager.IMPORTANCE_DEFAULT));
        }
    }
    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }
    private void notifyNew(int n) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
            androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle("New shop activity")
                    .setContentText(n + " new order update(s)")
                    .setAutoCancel(true).setOnlyAlertOnce(true);
            NotificationManagerCompat.from(this).notify(2, b.build());
        } catch (Exception ignored) {}
    }

    /** Auto-delivery outcome. Same id as notifyNew so the happy path collapses to ONE visible
     *  notification: "New shop activity" is replaced by the delivery result seconds later. */
    private void notifyDelivery(String ref, boolean ok, String detail) {
        try {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) return;
            androidx.core.app.NotificationCompat.Builder b = new androidx.core.app.NotificationCompat.Builder(this, CH)
                    .setSmallIcon(android.R.drawable.ic_dialog_email)
                    .setContentTitle(ok ? "Payment received" : "NFT delivery failed")
                    .setContentText(ok ? ref + " paid · NFT sent to the buyer"
                                       : ref + " — open the order to retry")
                    .setAutoCancel(true).setOnlyAlertOnce(true);
            NotificationManagerCompat.from(this).notify(2, b.build());
        } catch (Exception ignored) {}
    }
}
