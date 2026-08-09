package com.eurobuddha.merchinbox;

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
import org.minimarex.minimaapi.MinimaAPIMessages;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** miniMall Inbox — the vendor's native order manager (Maxima-free, on the comms primitive). */
public class MainActivity extends AppCompatActivity {

    private static final String CH = "merchinbox";

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

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ArrayDeque<View> stack = new ArrayDeque<>();
    /** NFT sends in flight, keyed ref:tokenid — blocks double-taps while the node builds the txn. */
    private final java.util.HashSet<String> nftSending = new java.util.HashSet<>();
    /** NFT lines auto-attempted this process, keyed ref:tokenid — a failed auto-send is never looped;
     *  the next app open (autoDeliverSweep) gets exactly one more automatic attempt. */
    private final java.util.HashSet<String> autoTried = new java.util.HashSet<>();
    /** Orders whose payment flipped to PAID during the current scan pass (main thread only). */
    private final java.util.HashSet<String> pendingAutoDeliver = new java.util.HashSet<>();
    private boolean autoSweepDone = false;

    private LinearLayout root;
    private FrameLayout container;
    private View pairingBanner;
    private BroadcastReceiver notifyReceiver;
    private int insetTop = 0, insetBottom = 0;

    // ---- lifecycle ----

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ls = Sodium.get();
        db = new MerchDb(this);
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

        showOrders();

        node = new NodeApi(this, this::onPaired);
        notifyReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                try {
                    String event = new JSONObject(i.getStringExtra(MinimaAPIMessages.MINIMA_API_NOTIFY_DATA)).optString("event", "");
                    if ("NEWBLOCK".equals(event) || "NEWBALANCE".equals(event)) { fetchBlock(); requestScan(); }
                } catch (Exception ignored) {}
            }
        };
        ContextCompat.registerReceiver(this, notifyReceiver,
                new IntentFilter(MinimaAPIMessages.MINIMA_API_NOTIFY), ContextCompat.RECEIVER_EXPORTED);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (notifyReceiver != null) try { unregisterReceiver(notifyReceiver); } catch (Exception ignored) {}
        if (node != null) node.onDestroy();
        io.shutdownNow();
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
            if (chainBlock == 0) fetchBlock();
            fetchVendorAddr();
            if (crypto == null) setupIdentity(); else requestScan();
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

    private void fetchBlock() {
        node.cmd("block", new NodeApi.Cb() {
            @Override public void onResult(JSONObject j) {
                JSONObject r = j.optJSONObject("response");
                if (r != null) try { chainBlock = Integer.parseInt(r.optString("block", "0")); } catch (Exception ignored) {}
            }
            @Override public void onError(String m) {}
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
                ui.post(() -> { adoptIdentity(id); refreshTop(buildOrders()); requestScan(); });
            } catch (Exception e) { ui.post(() -> toast("Identity error: " + e.getMessage())); }
        });
    }

    private void adoptIdentity(CommsIdentity id) {
        identity = id;
        crypto = new LocalEcCryptoProvider(ls, id);
        myId = id.publicId();
        orderScanner = new CommsScanner(node, crypto, db, CommsTransport.MINIMERCH_ADDRESS,
                this::routeIncoming, (ok, n) -> onScanDone(n));
        if (!vendorAddr.isEmpty()) startPaymentScanner();
    }

    private void startPaymentScanner() {
        paymentScanner = new CommsScanner(node, crypto, db, vendorAddr,
                this::routePayment, (ok, n) -> {
                    onScanDone(n);
                    // After the first clean pass, auto-deliver anything paid-but-unsent (e.g. an
                    // auto-send that failed last session, or a payment that landed while closed).
                    if (ok && !autoSweepDone) { autoSweepDone = true; autoDeliverSweep(); }
                }, false);
    }

    private void askForSeed() {
        final EditText in = input("Your Minima seed phrase");
        in.setMinLines(3);
        new AlertDialog.Builder(this)
                .setTitle("Create your shop identity")
                .setMessage("Your shop key is derived from your Minima seed (recoverable, distinct from other apps). Paste your seed phrase once — it's used only to derive the key and is never stored.")
                .setView(in)
                .setPositiveButton("Create", (d, w) -> { String s = in.getText().toString().trim(); if (!s.isEmpty()) deriveIdentity(s); })
                .show();
    }

    // ---- scanning + routing ----

    private void requestScan() {
        if (crypto == null) return;
        if (orderScanner != null) orderScanner.scan(chainBlock);
        if (paymentScanner != null) paymentScanner.scan(chainBlock);
    }

    /** An opened MINIMERCH message: orders/inquiries land as orders; buyer replies append to the order chat. */
    private boolean routeIncoming(String coinid, com.eurobuddha.comms.Opened opened, JSONObject coin) {
        MerchMessage m = MerchMessage.fromWire(opened.plaintext);
        if (m == null || !opened.fromPublicId.equals(m.from)) return false;   // sig must match claimed sender
        if (!myId.equals(m.to)) return false;                                  // addressed to me
        if (MerchMessage.ORDER.equals(m.type)) {
            return db.upsertOrder(m, "seller", coinid);
        }
        if (MerchMessage.INQUIRY.equals(m.type)) {
            boolean rowNew = db.upsertOrder(m, "seller", coinid);   // ensures the inquiry row exists
            boolean chatNew = db.insertChat(m.ref, true, m.message, m.randomid, m.date > 0 ? m.date : System.currentTimeMillis());
            return rowNew || chatNew;
        }
        if (MerchMessage.BUYER_REPLY.equals(m.type)) {
            return db.insertChat(m.ref, true, m.message, m.randomid, m.date > 0 ? m.date : System.currentTimeMillis());
        }
        return false;
    }

    /** A plaintext payment coin at the vendor address: match by the ref stamped in state[1], verify, mark paid. */
    private boolean routePayment(String coinid, com.eurobuddha.comms.Opened ignored, JSONObject coin) {
        String refHex = CommsScanner.statePort(coin, 1);
        if (refHex == null) return false;
        String ref = hexToText(refHex);
        if (ref.isEmpty()) return false;
        MerchDb.Order o = db.order(ref);
        if (o == null || o.paid) return false;   // replay guard: backfill/grow-passes/restarts re-see coins
        String amount = coin.optString("amount", coin.optString("tokenamount", "0"));
        String tokenid = coin.optString("tokenid", "0x00");
        String result = db.recordPayment(ref, amount, tokenid);
        // Auto-deliver only on a clean PAID — never UNDERPAID/WRONG_TOKEN (o.paid is true even then,
        // which is why this keys off the returned status). Queued, drained at scan end.
        if (MerchDb.PAID.equals(result)) pendingAutoDeliver.add(ref);
        return result != null;
    }

    private void onScanDone(int newCount) {
        ui.post(() -> {
            if (!pendingAutoDeliver.isEmpty()) {
                List<String> refs = new ArrayList<>(pendingAutoDeliver);
                pendingAutoDeliver.clear();
                for (String r : refs) maybeAutoDeliver(r);
            }
            if (newCount <= 0) return;
            notifyNew(newCount);
            View top = stack.peek();
            Object tag = top == null ? null : top.getTag();
            if (stack.size() == 1) refreshTop(buildOrders());
            else if (tag instanceof String && ((String) tag).startsWith("order:")) refreshTop(buildOrderDetail(((String) tag).substring(6)));
        });
    }

    // ---- automatic NFT delivery ----

    private boolean autoDeliverOn() { return "1".equals(db.getMeta("autodeliver", "1")); }

    /** Auto-send every unsent NFT line of a cleanly-paid order. Idempotent: nftsent: meta skips
     *  delivered lines, autoTried caps automatic attempts at one per line per session. */
    private void maybeAutoDeliver(String ref) {
        if (!autoDeliverOn()) return;
        MerchDb.Order o = db.order(ref);
        if (o == null || !o.paid) return;
        if (!MerchDb.PAID.equals(o.status) && !MerchDb.CONFIRMED.equals(o.status)) return;
        if (o.payaddr == null || o.payaddr.isEmpty()) return;   // undeliverable — order screen explains
        for (String[] line : nftLines(o)) {
            String key = lineKey(ref, line);
            if (!db.getMeta("nftsent:" + key, "").isEmpty()) continue;
            if (!autoTried.add(key)) continue;
            sendNft(o, line, true);
        }
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
        head.addView(iconBtn("⤓", this::exportCsv));          // export orders CSV
        head.addView(iconBtn("≡", this::showIdentityCard));   // ≡ menu → identity card
        col.addView(head);

        TextView ver = new TextView(this);
        ver.setText("miniMall Inbox v" + BuildConfig.VERSION_NAME);
        ver.setTextColor(Design.DIM2); ver.setTextSize(10f);
        ver.setPadding(dp(16), dp(2), dp(16), dp(4));
        col.addView(ver);

        TextView s = new TextView(this);
        s.setPadding(dp(16), dp(4), dp(16), dp(8)); s.setTextSize(12.5f);
        if (crypto == null) {
            s.setText(paired ? "Connecting to your node…" : "Enable miniMall Inbox in Minima Core → Apps.");
            s.setTextColor(Design.DIM);
        } else {
            s.setText("✓ Ready to receive orders  ·  shop key " + shortId(myId));
            s.setTextColor(Design.IN);
        }
        col.addView(s);

        // Auto NFT delivery toggle — ON: paid NFT orders are sent to the buyer automatically.
        final boolean auto = autoDeliverOn();
        TextView ad = new TextView(this);
        ad.setText("⚡ Auto NFT delivery  ·  " + (auto ? "ON" : "OFF"));
        ad.setTextColor(auto ? Design.IN : Design.DIM);
        ad.setTextSize(12.5f); ad.setPadding(dp(16), 0, dp(16), dp(8));
        ad.setOnClickListener(v -> {
            final String next = auto ? "0" : "1";
            io.execute(() -> {
                db.setMeta("autodeliver", next);
                ui.post(() -> { toast("Auto NFT delivery " + ("1".equals(next) ? "on" : "off")); refreshTop(buildOrders()); });
            });
        });
        col.addView(ad);

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
            sub.setText(shopPrefix + o.amount + " " + dispCurrency(o) + "   ·   " + shortId(o.counterparty) + "   ·   " + timeAgo(o.date));
            sub.setTextColor(Design.DIM); sub.setTextSize(12f); sub.setPadding(0, dp(3), 0, 0);
            row.addView(sub);
            row.setOnClickListener(v -> openOrder(o.ref));
        }
        @Override public int getItemCount() { return data.size(); }
    }

    // ---- order detail ----

    private void openOrder(String ref) {
        io.execute(() -> { db.markRead(ref); ui.post(() -> push(buildOrderDetail(ref))); });
    }

    private View buildOrderDetail(String ref) {
        MerchDb.Order o = db.order(ref);
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
            paid.setText(o.paid ? "  paid " + o.paidAmount + " " + dispCurrency(o) : "  awaiting payment");
            paid.setTextColor(o.paid ? Design.IN : Design.DIM); paid.setTextSize(12f);
            st.addView(paid);
        }
        st.setPadding(0, 0, 0, dp(10)); body.addView(st);

        if (!inquiry) {
            // line items + totals
            body.addView(sectionLabel("Items"));
            body.addView(card(itemsText(o)));
            body.addView(kv("Total", o.amount + " " + dispCurrency(o)));
            body.addView(kv("Reference", o.ref));
            if (!o.shopName.isEmpty()) body.addView(kv("Shop", o.shopName));
            if (!o.coinid.isEmpty()) body.addView(copyRow("Order tx", shortId(o.coinid), o.coinid));

            // delivery
            body.addView(sectionLabel("Delivery"));
            body.addView(kv("Method", shippingLabel(o.shipping)));
            if (!o.delivery.isEmpty()) body.addView(copyRow("Address", o.delivery, o.delivery));
            if (!o.message.isEmpty()) { body.addView(sectionLabel("Buyer note")); body.addView(card(o.message)); }

            // NFT delivery — order lines that carry an NFT tokenid (from an NFT Studio .shop)
            List<String[]> nfts = nftLines(o);
            if (!nfts.isEmpty()) {
                body.addView(sectionLabel(autoDeliverOn() ? "NFT delivery · automatic" : "NFT delivery"));
                for (String[] line : nfts) body.addView(nftSendRow(o, line));
            }
        }
        body.addView(copyRow("Buyer key", shortId(o.counterparty), o.counterparty));

        // status actions (orders only — an enquiry has no lifecycle)
        if (!inquiry) {
            body.addView(sectionLabel("Update status"));
            body.addView(statusButtons(o));
        }

        // reply / conversation
        body.addView(sectionLabel(inquiry ? "Conversation" : "Message the buyer"));
        body.addView(replyRow(o));
        return col;
    }

    private View statusButtons(final MerchDb.Order o) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        String[] flow = {MerchDb.PAID, MerchDb.CONFIRMED, MerchDb.SHIPPED, MerchDb.DELIVERED};
        for (final String s : flow) {
            TextView b = button(s, s.equals(o.status));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            lp.rightMargin = dp(6); b.setLayoutParams(lp);
            b.setOnClickListener(v -> advanceStatus(o, s));
            wrap.addView(b);
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
        View top = stack.peek();
        Object tag = top == null ? null : top.getTag();
        if (tag instanceof String && tag.equals("order:" + ref)) refreshTop(buildOrderDetail(ref));
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
            JSONArray a = new JSONArray(o.items);
            for (int i = 0; i < a.length(); i++) {
                JSONObject it = a.optJSONObject(i); if (it == null) continue;
                String tokenid = it.optString("nftTokenId", "");
                if (tokenid.isEmpty()) continue;
                int q = Math.max(1, it.optInt("quantity", 1));
                int idx = Math.max(0, it.optInt("nftStateIdx", 0));
                int bundle = it.optInt("nftBundle", 0) > 0 ? 1 : 0;
                out.add(new String[]{it.optString("product", "NFT"), tokenid, String.valueOf(q), String.valueOf(idx), String.valueOf(bundle)});
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Meta/set key for one deliverable line. Plain NFTs keep the historical ref:tokenid shape (so
     *  existing nftsent: metas survive); pieces append #idx; complete-collection bundles #ALL. */
    private static String lineKey(String ref, String[] line) {
        if ("1".equals(line[4])) return ref + ":" + line[1] + "#ALL";
        return ref + ":" + line[1] + ("0".equals(line[3]) ? "" : "#" + line[3]);
    }

    private View nftSendRow(final MerchDb.Order o, final String[] line) {
        final String name = line[0], tokenid = line[1], qty = line[2];
        final boolean piece = !"0".equals(line[3]);
        final boolean bundle = "1".equals(line[4]);
        final String key = lineKey(o.ref, line);
        final String label = bundle ? "the complete collection (\"" + name + "\")"
                : piece ? "\"" + name + "\"" : qty + " × " + name;
        String sentTx = db.getMeta("nftsent:" + key, "");
        if (!sentTx.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("✓ Sent " + label + (("sent".equals(sentTx) || "posted".equals(sentTx)) ? "" : " · " + shortId(sentTx)));
            t.setTextColor(Design.IN); t.setTextSize(13f); t.setPadding(0, dp(4), 0, dp(4));
            return t;
        }
        LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL);
        boolean ready = o.paid && o.payaddr != null && !o.payaddr.isEmpty();
        boolean inFlight = nftSending.contains(key);
        TextView b = button(inFlight ? "Sending…" : "Send " + label + " to buyer", ready && !inFlight);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6); b.setLayoutParams(lp);
        b.setOnClickListener(v -> {
            if (!o.paid) { toast("Awaiting payment first."); return; }
            if (o.payaddr == null || o.payaddr.isEmpty()) { toast("This order carries no buyer pay address."); return; }
            if (nftSending.contains(key)) return;
            new AlertDialog.Builder(this)
                    .setTitle("Send NFT")
                    .setMessage("Send " + label + " (" + shortId(tokenid) + ") to " + shortId(o.payaddr) + "?\n\nThis transfers the NFT on-chain.")
                    .setPositiveButton("Send", (d, w) -> sendNft(o, line, false))
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        wrap.addView(b);
        if (!ready) {
            TextView why = new TextView(this);
            why.setText(!o.paid ? "Available once the order is paid." : "The order didn't include a buyer pay address.");
            why.setTextColor(Design.DIM2); why.setTextSize(11f); why.setPadding(0, dp(2), 0, 0);
            wrap.addView(why);
        }
        return wrap;
    }

    /** Deliver one order line. StateNFT pieces (stateIdx ≥ 1) transfer the exact coin with its state
     *  replayed; plain NFTs go via `send` (atomic, mark-on-post). auto=true is the headless
     *  payment-confirmed path: no toasts, notifications instead, refresh only if visible. */
    private void sendNft(final MerchDb.Order o, final String[] line, final boolean auto) {
        final String tokenid = line[1], qty = line[2];
        final String key = lineKey(o.ref, line);
        // The pay address is buyer-supplied chain data interpolated into a node command — validate hard.
        if (!Util.isValidAddress(o.payaddr)) { deliverFailed(o, key, "buyer pay address is malformed", auto); return; }
        if (!Util.isValidHexId(tokenid)) { deliverFailed(o, key, "token id is malformed", auto); return; }
        if ("1".equals(line[4])) { collectionDeliver(o, line, auto); return; }
        if (!"0".equals(line[3])) { stateNftDeliver(o, line, auto); return; }

        if (!nftSending.add(key)) return;
        refreshOrderIfTopmost(o.ref);
        CommsTransport.sendPayment(node, o.payaddr, qty, tokenid, o.ref, new CommsTransport.SendCb() {
            @Override public void onSent(final String txid) {
                markSentAndMaybeAdvance(o, key, txid == null || txid.isEmpty() ? "sent" : txid, auto);
            }
            @Override public void onFailed(final String e) {
                // A StateNFT collection sold by an old .shop has no edition index — `send` fails with
                // an opaque sendable:0; name the real cause for the vendor.
                if (isKnownStateCollection(tokenid)) deliverFailed(o, key, "this is a StateNFT collection but the order names no edition — the buyer's miniMall is outdated", auto);
                else deliverFailed(o, key, e, auto);
            }
        });
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
        if (auto) notifyDelivery(o.ref, false, msg);
        else toast("NFT send failed: " + msg);
        refreshOrderIfTopmost(o.ref);
    }

    private void markSentAndMaybeAdvance(final MerchDb.Order o, final String key, final String txid, final boolean auto) {
        io.execute(() -> {
            db.setMeta("nftsent:" + key, txid == null || txid.isEmpty() ? "sent" : txid);
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
                    if (c != null && !StateNft.isBuried(c) && idx.equals(StateNft.stamped(c))) { coin = c; break; }
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
                            || !StateNft.replayableState(c) || StateNft.safeAmount(c).isEmpty()) continue;
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
                if (auto) notifyDelivery(o.ref, false, msg);
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

    private View replyRow(final MerchDb.Order o) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        for (String[] c : db.chat(o.ref)) {
            boolean incoming = "1".equals(c[0]);
            TextView t = new TextView(this);
            t.setText((incoming ? "Buyer: " : "You: ") + c[1]);
            t.setTextColor(incoming ? Design.TEXT : Design.DIM); t.setTextSize(13f);
            t.setPadding(0, dp(2), 0, dp(2));
            wrap.addView(t);
        }
        LinearLayout bar = new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setPadding(0, dp(6), 0, 0);
        final EditText box = input("Reply…");
        box.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView send = button("Send", false);
        send.setOnClickListener(v -> {
            String txt = box.getText().toString().trim();
            if (txt.isEmpty()) return;
            box.setText("");
            sendReply(o, txt);
        });
        bar.addView(box); bar.addView(send);
        wrap.addView(bar);
        return wrap;
    }

    private void sendReply(final MerchDb.Order o, final String text) {
        final String rid = MailText.randomId();
        io.execute(() -> {
            db.insertChat(o.ref, false, text, rid, System.currentTimeMillis());
            MerchMessage m = new MerchMessage();
            m.type = MerchMessage.REPLY; m.ref = o.ref; m.message = text;
            m.from = myId; m.to = o.counterparty; m.randomid = rid; m.date = System.currentTimeMillis();
            CommsTransport.sendMessage(node, crypto, o.counterparty, m.toWire(), new CommsTransport.SendCb() {
                @Override public void onSent(String txid) {}
                @Override public void onFailed(String e) { ui.post(() -> toast("Reply failed: " + e)); }
            });
            ui.post(() -> refreshTop(buildOrderDetail(o.ref)));
        });
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
        new AlertDialog.Builder(this).setTitle("Vendor Card").setView(wrapScroll(col))
                .setPositiveButton("Copy", (d, w) -> copy(card))
                .setNegativeButton("Close", null).show();
    }

    /** publicId|receivingAddress — the studio bakes both into the Shop APK. */
    private String vendorCard() {
        return myId + "|" + (vendorAddr == null ? "" : vendorAddr);
    }

    // ---- helpers: formatting ----

    private String dispCurrency(MerchDb.Order o) {
        if (!o.currency.isEmpty()) return o.currency;
        return CommsTransport.USDT.equalsIgnoreCase(o.tokenid) ? "USDT" : "Minima";
    }
    private String shippingLabel(String s) {
        if ("uk".equalsIgnoreCase(s)) return "UK shipping";
        if ("intl".equalsIgnoreCase(s)) return "International";
        if ("digital".equalsIgnoreCase(s)) return "Digital";
        return s.isEmpty() ? "—" : s;
    }
    private String itemsText(MerchDb.Order o) {
        try {
            JSONArray a = new JSONArray(o.items);
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
        } catch (Exception e) { return o.product.isEmpty() ? "—" : o.product; }
    }
    private int statusColor(String s) {
        if (MerchDb.DELIVERED.equals(s) || MerchDb.SHIPPED.equals(s)) return Design.IN;
        if (MerchDb.PAID.equals(s) || MerchDb.CONFIRMED.equals(s)) return Design.ACCENT;
        if (MerchDb.UNDERPAID.equals(s) || MerchDb.WRONG_TOKEN.equals(s)) return Design.RED;
        return Design.DIM2;
    }
    private static String shortId(String s) {
        if (s == null || s.length() < 14) return s == null ? "" : s;
        return s.substring(0, 8) + "…" + s.substring(s.length() - 4);
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

    private static String hexToText(String hex) {
        try {
            String h = hex.startsWith("0x") ? hex.substring(2) : hex;
            return new String(Hex.from("0x" + h), StandardCharsets.UTF_8);
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
                    .setAutoCancel(true);
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
                    .setAutoCancel(true);
            NotificationManagerCompat.from(this).notify(2, b.build());
        } catch (Exception ignored) {}
    }
}
