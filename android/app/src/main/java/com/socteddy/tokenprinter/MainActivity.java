package com.socteddy.tokenprinter;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Token Printer: shows the live web app and gives it two things Chrome can't do —
 * printing straight to the Bluetooth printer, and voice input inside an app.
 * The page finds these on window.TokenPrinterNative; replies come back through window.__tp(kind, json).
 */
@SuppressLint("MissingPermission")
public class MainActivity extends Activity {
    static final String SITE = "https://socteddy.github.io/Token-Printer/";
    static final String OFFLINE_COPY = "file:///android_asset/index.html";   // used only if the site can't load
    static final int REQ_BT = 1, REQ_MIC = 2;
    static final long IDLE_DISCONNECT_MS = 120_000;   // let the printer go after 2 quiet minutes

    WebView web;
    SharedPreferences prefs;
    VoiceInput voice;
    final PrinterLink printer = new PrinterLink();
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final Handler main = new Handler(Looper.getMainLooper());
    final Runnable idleDisconnect = () -> worker.execute(printer::close);
    Runnable afterBtGranted, afterBtDenied, afterMicGranted, afterMicDenied;
    boolean triedOfflineCopy = false;

    interface PickResult { void done(String address, String error); }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("printer", MODE_PRIVATE);

        int felt = Color.parseColor("#16201c");
        Window w = getWindow();
        w.setStatusBarColor(felt);
        w.setNavigationBarColor(felt);

        web = new WebView(this);
        web.setBackgroundColor(felt);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);        // recent tokens, art picks, brightness/contrast
        s.setMediaPlaybackRequiresUserGesture(false);

        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame() && !triedOfflineCopy) {
                    triedOfflineCopy = true;
                    view.loadUrl(OFFLINE_COPY);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("socteddy.github.io".equals(u.getHost()) || "file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;   // other links open outside the app
            }
        });

        voice = new VoiceInput(this, this::sendToPage);
        web.addJavascriptInterface(new Bridge(), "TokenPrinterNative");

        if (saved != null) web.restoreState(saved);
        else loadFresh();
    }

    /** Load the site past any cached copy, so a just-pushed update shows up right away. */
    void loadFresh() {
        triedOfflineCopy = false;
        web.loadUrl(SITE + "?v=" + System.currentTimeMillis());
        loadedAt = System.currentTimeMillis();
    }

    long loadedAt, pausedAt;
    static final long STALE_AFTER_MS = 30 * 60_000;   // away this long → pick up any update on return

    @Override
    protected void onResume() {
        super.onResume();
        if (pausedAt > 0 && System.currentTimeMillis() - pausedAt > STALE_AFTER_MS) loadFresh();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    protected void onPause() {
        super.onPause();
        pausedAt = System.currentTimeMillis();
        voice.cancel();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(idleDisconnect);
        voice.cancel();
        worker.execute(printer::close);
        worker.shutdown();
        web.destroy();
        super.onDestroy();
    }

    // ---------- talking to the page ----------

    void sendToPage(String kind, JSONObject data) {
        String js = "window.__tp&&window.__tp(" + JSONObject.quote(kind) + "," + JSONObject.quote(data.toString()) + ")";
        main.post(() -> web.evaluateJavascript(js, null));
    }

    static JSONObject obj(Object... keyValues) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < keyValues.length; i += 2) o.put((String) keyValues[i], keyValues[i + 1]);
        } catch (JSONException ignored) { }
        return o;
    }

    JSONObject printerInfo() {
        return obj("name", prefs.getString("name", null), "address", prefs.getString("address", null));
    }

    /** Methods the page can call. They run on a background binder thread, so UI work hops to the main thread. */
    class Bridge {
        @JavascriptInterface
        public String info() {
            return obj("app", true, "printer", printerInfo()).toString();
        }

        @JavascriptInterface
        public void print(String id, String base64Bits, int widthBytes, int height) {
            printWith(id, base64Bits, widthBytes, height, "{}");
        }

        @JavascriptInterface
        public void disconnect() {
            main.removeCallbacks(idleDisconnect);
            worker.execute(printer::close);
        }

        /** Same as print(), plus the page's printer settings as JSON (see PrinterLink.Options). */
        @JavascriptInterface
        public void printWith(String id, String base64Bits, int widthBytes, int height, String optionsJson) {
            final PrinterLink.Options opt = PrinterLink.Options.parse(optionsJson);
            final byte[] bits;
            try {
                bits = Base64.decode(base64Bits, Base64.DEFAULT);
            } catch (IllegalArgumentException e) {
                finishPrint(id, false, "The image didn't come through. Try again.");
                return;
            }
            if (widthBytes <= 0 || height <= 0 || bits.length < widthBytes * height) {
                finishPrint(id, false, "The image didn't come through. Try again.");
                return;
            }
            main.post(() -> withBluetooth(() -> {
                String address = prefs.getString("address", null);
                if (address != null) startPrint(id, address, bits, widthBytes, height, opt);
                else pickPrinter((picked, err) -> {
                    if (picked == null) finishPrint(id, false, err);
                    else startPrint(id, picked, bits, widthBytes, height, opt);
                });
            }, () -> finishPrint(id, false,
                    "Token Printer needs the Nearby devices permission to reach the printer. Allow it in Android settings.")));
        }

        @JavascriptInterface
        public void choosePrinter() {
            main.post(() -> withBluetooth(() -> pickPrinter((picked, err) -> {
                if (picked == null && err != null && !err.equals(NO_PICK)) sendToPage("printer", obj("error", err));
            }), () -> sendToPage("printer", obj("error",
                    "Token Printer needs the Nearby devices permission to see the printer. Allow it in Android settings."))));
        }

        @JavascriptInterface
        public void startVoice() {
            main.post(() -> withMic(voice::start, () -> {
                sendToPage("voice", obj("type", "error", "code", "not-allowed"));
                sendToPage("voice", obj("type", "end"));
            }));
        }

        @JavascriptInterface
        public void stopVoice() {
            main.post(voice::stop);
        }

        @JavascriptInterface
        public void saveImage(String base64Png, String name) {
            worker.execute(() -> saveToPictures(base64Png, name));
        }
    }

    // ---------- printing ----------

    void startPrint(String id, String address, byte[] bits, int widthBytes, int height, PrinterLink.Options opt) {
        main.removeCallbacks(idleDisconnect);
        worker.execute(() -> {
            try {
                printer.print(bluetooth(), address, bits, widthBytes, height, opt);
                if (!opt.keepConnected) {
                    // Give the last bytes time to leave the phone before hanging up
                    try { Thread.sleep(1500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    printer.close();
                }
                finishPrint(id, true, null, null);
            } catch (PrinterLink.PrintError e) {
                finishPrint(id, false, e.getMessage(), e.detail);
            } catch (SecurityException e) {
                finishPrint(id, false, "Android blocked the Bluetooth connection. Allow \"Nearby devices\" for Token Printer in Android settings.",
                        PrinterLink.describe(e));
            } catch (Exception e) {
                printer.close();
                finishPrint(id, false, "Couldn't reach the printer. Make sure it's on and close by, then try again.",
                        PrinterLink.describe(e));
            }
            if (opt.keepConnected) {
                main.removeCallbacks(idleDisconnect);
                main.postDelayed(idleDisconnect, IDLE_DISCONNECT_MS);
            }
        });
    }

    void finishPrint(String id, boolean ok, String message) {
        finishPrint(id, ok, message, null);
    }

    void finishPrint(String id, boolean ok, String message, String detail) {
        sendToPage("print", obj("id", id, "ok", ok, "message", message, "detail", detail));
    }

    BluetoothAdapter bluetooth() {
        BluetoothManager m = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    static final String NO_PICK = "No printer picked.";

    /** Lists paired Bluetooth devices (likely printers first) and remembers the one tapped. */
    void pickPrinter(PickResult result) {
        BluetoothAdapter a = bluetooth();
        if (a == null) { result.done(null, "This phone doesn't have Bluetooth."); return; }
        if (!a.isEnabled()) { result.done(null, "Bluetooth is off. Turn it on and try again."); return; }
        List<BluetoothDevice> devices = new ArrayList<>(a.getBondedDevices());
        if (devices.isEmpty()) {
            result.done(null, "No paired devices yet. Pair the printer in Android's Bluetooth settings first.");
            return;
        }
        devices.sort((x, y) -> Integer.compare(printerScore(x), printerScore(y)));
        String[] labels = new String[devices.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = nameOf(devices.get(i));

        final boolean[] picked = {false};
        new AlertDialog.Builder(this)
                .setTitle("Which one is your printer?")
                .setItems(labels, (dialog, i) -> {
                    picked[0] = true;
                    BluetoothDevice d = devices.get(i);
                    prefs.edit().putString("address", d.getAddress()).putString("name", nameOf(d)).apply();
                    worker.execute(printer::close);          // drop any link to a previous printer
                    sendToPage("printer", printerInfo());
                    result.done(d.getAddress(), null);
                })
                .setOnDismissListener(dialog -> { if (!picked[0]) result.done(null, NO_PICK); })
                .show();
    }

    static String nameOf(BluetoothDevice d) {
        String n = d.getName();
        return n == null || n.isEmpty() ? d.getAddress() : n;
    }

    /** 0 = looks like a printer, 1 = anything else (headphones, cars, watches). */
    static int printerScore(BluetoothDevice d) {
        BluetoothClass c = d.getBluetoothClass();
        if (c != null && c.getMajorDeviceClass() == BluetoothClass.Device.Major.IMAGING) return 0;
        String n = nameOf(d).toLowerCase(Locale.US);
        for (String hint : new String[]{"print", "pos", "pt-", "mtp", "rpp", "58", "thermal", "receipt"})
            if (n.contains(hint)) return 0;
        return 1;
    }

    // ---------- saving the image ----------

    void saveToPictures(String base64Png, String name) {
        if (Build.VERSION.SDK_INT < 29) {
            sendToPage("saved", obj("ok", false, "message", "Saving images needs Android 10 or newer."));
            return;
        }
        try {
            byte[] png = Base64.decode(base64Png, Base64.DEFAULT);
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Token Printer");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IOException("no uri");
            try (OutputStream o = getContentResolver().openOutputStream(uri)) {
                if (o == null) throw new IOException("no stream");
                o.write(png);
            }
            sendToPage("saved", obj("ok", true, "message", "Saved to Pictures › Token Printer."));
        } catch (Exception e) {
            sendToPage("saved", obj("ok", false, "message", "Couldn't save the image."));
        }
    }

    // ---------- permissions ----------

    void withBluetooth(Runnable granted, Runnable denied) {
        if (Build.VERSION.SDK_INT < 31
                || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            granted.run();
            return;
        }
        afterBtGranted = granted;
        afterBtDenied = denied;
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
    }

    void withMic(Runnable granted, Runnable denied) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            granted.run();
            return;
        }
        afterMicGranted = granted;
        afterMicDenied = denied;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        boolean ok = results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED;
        Runnable next;
        if (code == REQ_BT) { next = ok ? afterBtGranted : afterBtDenied; afterBtGranted = afterBtDenied = null; }
        else if (code == REQ_MIC) { next = ok ? afterMicGranted : afterMicDenied; afterMicGranted = afterMicDenied = null; }
        else return;
        if (next != null) next.run();
    }
}
