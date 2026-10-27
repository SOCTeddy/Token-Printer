package com.socteddy.tokenprinter;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Classic Bluetooth (SPP) link to an ESC/POS receipt printer.
 * The connection can stay open between prints so the next token goes out right away.
 * Only ever used from one background thread.
 */
@SuppressLint("MissingPermission")
class PrinterLink {
    /** Standard Serial Port Profile id that these printers listen on. */
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /** A failure with a message worth showing as-is, plus the technical detail behind it. */
    static class PrintError extends IOException {
        private static final long serialVersionUID = 1L;
        final String detail;
        PrintError(String message, String detail) { super(message); this.detail = detail; }
    }

    /** Printer settings chosen on the page's "Printer settings" section. */
    static class Options {
        String connect = "auto";      // auto | secure | insecure | channel1
        String mode = "raster";       // raster (GS v 0) | escstar (ESC * 33)
        int bandRows = 128;           // rows sent per chunk
        int bandDelayMs = 0;          // pause after each chunk, for printers that choke on fast data
        int heat = 0;                 // 0 = printer default; otherwise ESC 7 heating time (higher = darker)
        int feedLines = 2;            // blank lines after the print
        boolean keepConnected = true;

        static Options parse(String json) {
            Options o = new Options();
            try {
                JSONObject j = new JSONObject(json == null || json.isEmpty() ? "{}" : json);
                o.connect = j.optString("connect", o.connect);
                o.mode = j.optString("mode", o.mode);
                o.bandRows = clamp(j.optInt("bandRows", o.bandRows), 8, 255);
                o.bandDelayMs = clamp(j.optInt("bandDelayMs", o.bandDelayMs), 0, 200);
                o.heat = clamp(j.optInt("heat", o.heat), 0, 255);
                o.feedLines = clamp(j.optInt("feedLines", o.feedLines), 0, 10);
                o.keepConnected = j.optBoolean("keepConnected", o.keepConnected);
            } catch (Exception ignored) { }
            return o;
        }

        private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
    }

    private BluetoothSocket socket;
    private OutputStream out;
    private String connectedTo;
    private String connectedHow;

    static String describe(Throwable e) {
        if (e == null) return null;
        String m = e.getMessage();
        return e.getClass().getSimpleName() + (m == null || m.isEmpty() ? "" : ": " + m);
    }

    /** Prints a 1-bit image: widthBytes bytes per row, most significant bit = leftmost dot, 1 = black. */
    void print(BluetoothAdapter adapter, String address, byte[] bits, int widthBytes, int height, Options opt)
            throws IOException {
        if (adapter == null) throw new PrintError("This phone doesn't have Bluetooth.", null);
        if (!adapter.isEnabled()) throw new PrintError("Bluetooth is off. Turn it on and try again.", null);

        boolean fresh = connect(adapter, address, opt.connect);
        try {
            send(bits, widthBytes, height, opt);
        } catch (IOException e) {
            close();
            if (fresh) throw new PrintError("Connected, but the printer stopped taking data. Try again, or try other settings.", describe(e));
            // The kept-open link had gone stale (printer slept or was switched off and on): reconnect once.
            connect(adapter, address, opt.connect);
            try {
                send(bits, widthBytes, height, opt);
            } catch (IOException again) {
                close();
                throw new PrintError("Connected, but the printer stopped taking data. Try again, or try other settings.", describe(again));
            }
        }
    }

    /** Returns true when a new connection was made, false when the open one was reused. */
    private boolean connect(BluetoothAdapter adapter, String address, String how) throws IOException {
        if (socket != null && socket.isConnected() && address.equals(connectedTo) && how.equals(connectedHow)) return false;
        close();
        // Stopping a running scan speeds things up, but Android 12+ only allows it with a scan permission
        // this app doesn't ask for — so try it and move on if refused.
        try { if (adapter.isDiscovering()) adapter.cancelDiscovery(); } catch (SecurityException ignored) { }

        BluetoothDevice device = adapter.getRemoteDevice(address);
        List<String> tries = "auto".equals(how)
                ? Arrays.asList("secure", "insecure", "channel1")
                : Collections.singletonList(how);
        Throwable last = null;
        for (String t : tries) {
            BluetoothSocket s = null;
            try {
                s = open(device, t);
                if (s == null) throw new IOException("no socket");
                s.connect();
                socket = s;
                out = s.getOutputStream();
                connectedTo = address;
                connectedHow = how;
                return true;
            } catch (Throwable e) {
                closeQuietly(s);
                last = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
            }
        }
        throw new PrintError("Couldn't connect to the printer. Make sure it's on, close by, and not connected to another app.",
                describe(last));
    }

    private static BluetoothSocket open(BluetoothDevice d, String how) throws Exception {
        switch (how) {
            case "secure": return d.createRfcommSocketToServiceRecord(SPP);
            case "insecure": return d.createInsecureRfcommSocketToServiceRecord(SPP);
            default:   // channel 1: for printers that don't advertise SPP properly
                return (BluetoothSocket) d.getClass().getMethod("createRfcommSocket", int.class).invoke(d, 1);
        }
    }

    private void send(byte[] bits, int widthBytes, int height, Options opt) throws IOException {
        out.write(new byte[]{0x1B, 0x40});                                   // ESC @  reset
        if (opt.heat > 0) out.write(new byte[]{0x1B, 0x37, 7, (byte) opt.heat, 2});   // ESC 7  heating
        if ("escstar".equals(opt.mode)) sendBitImage(bits, widthBytes, height, opt);
        else sendRaster(bits, widthBytes, height, opt);
        if (opt.feedLines > 0) out.write(new byte[]{0x1B, 0x64, (byte) opt.feedLines});   // ESC d n  feed
        out.flush();
    }

    /** GS v 0: rows of packed bits, sent in chunks. Works on most cheap printers. */
    private void sendRaster(byte[] bits, int widthBytes, int height, Options opt) throws IOException {
        for (int y = 0; y < height; y += opt.bandRows) {
            int rows = Math.min(opt.bandRows, height - y);
            out.write(new byte[]{0x1D, 0x76, 0x30, 0x00,
                    (byte) widthBytes, (byte) (widthBytes >> 8),
                    (byte) rows, (byte) (rows >> 8)});
            out.write(bits, y * widthBytes, rows * widthBytes);
            pause(opt);
        }
    }

    /** ESC * 33: 24-dot-tall strips sent column by column. For Epson-style printers that ignore GS v 0. */
    private void sendBitImage(byte[] bits, int widthBytes, int height, Options opt) throws IOException {
        int dots = widthBytes * 8;
        out.write(new byte[]{0x1B, 0x33, 24});                               // line spacing = 24 dots
        byte[] strip = new byte[5 + dots * 3 + 1];
        for (int y = 0; y < height; y += 24) {
            java.util.Arrays.fill(strip, (byte) 0);
            strip[0] = 0x1B; strip[1] = 0x2A; strip[2] = 33;
            strip[3] = (byte) dots; strip[4] = (byte) (dots >> 8);
            for (int x = 0; x < dots; x++) {
                int mask = 0x80 >> (x & 7), col = x >> 3;
                for (int r = 0; r < 24; r++) {
                    int yy = y + r;
                    if (yy < height && (bits[yy * widthBytes + col] & mask) != 0)
                        strip[5 + x * 3 + (r >> 3)] |= (byte) (0x80 >> (r & 7));
                }
            }
            strip[strip.length - 1] = 0x0A;                                   // LF: advance one strip
            out.write(strip);
            pause(opt);
        }
        out.write(new byte[]{0x1B, 0x32});                                   // back to default spacing
    }

    private void pause(Options opt) throws IOException {
        if (opt.bandDelayMs <= 0) return;
        out.flush();
        try { Thread.sleep(opt.bandDelayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    void close() {
        closeQuietly(socket);
        socket = null;
        out = null;
        connectedTo = null;
        connectedHow = null;
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) { }
    }
}
