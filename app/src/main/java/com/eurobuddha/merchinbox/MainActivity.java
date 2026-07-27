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
                this::routePayment, (ok, n) -> onScanDone(n), false);
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
        if (o == null || o.paid) return false;
        String amount = coin.optString("amount", coin.optString("tokenamount", "0"));
        String tokenid = coin.optString("tokenid", "0x00");
        return db.recordPayment(ref, amount, tokenid) != null;
    }

    private void onScanDone(int newCount) {
        ui.post(() -> {
            if (newCount <= 0) return;
            notifyNew(newCount);
            View top = stack.peek();
            Object tag = top == null ? null : top.getTag();
            if (stack.size() == 1) refreshTop(buildOrders());
            else if (tag instanceof String && ((String) tag).startsWith("order:")) refreshTop(buildOrderDetail(((String) tag).substring(6)));
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

    private void advanceStatus(final MerchDb.Order o, final String status) {
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
            ui.post(() -> { toast("Marked " + status); refreshTop(buildOrderDetail(o.ref)); });
        });
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
}
