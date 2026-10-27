package com.socteddy.tokenprinter;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.IOException;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Classic Bluetooth (SPP) link to an ESC/POS receipt printer.
 * The connection is kept open between prints so the next token goes out right away.
 * Only ever used from one background thread.
 */
@SuppressLint("MissingPermission")
class PrinterLink {
    /** Standard Serial Port Profile id that these printers listen on. */
    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    /** Rows per raster command; small enough for cheap printers' buffers. */
    private static final int BAND_ROWS = 128;

    /** A failure with a message worth showing as-is. */
    static class PrintError extends IOException {
        PrintError(String message) { super(message); }
    }

    private BluetoothSocket socket;
    private OutputStream out;
    private String connectedTo;

    /** Prints a 1-bit image: widthBytes bytes per row, most significant bit = leftmost dot, 1 = black. */
    void print(BluetoothAdapter adapter, String address, byte[] bits, int widthBytes, int height) throws IOException {
        if (adapter == null) throw new PrintError("This phone doesn't have Bluetooth.");
        if (!adapter.isEnabled()) throw new PrintError("Bluetooth is off. Turn it on and try again.");

        boolean fresh = connect(adapter, address);
        try {
            send(bits, widthBytes, height);
        } catch (IOException e) {
            close();
            if (fresh) throw e;
            // The kept-open link had gone stale (printer slept or was switched off and on): reconnect once.
            connect(adapter, address);
            send(bits, widthBytes, height);
        }
    }

    /** Returns true when a new connection was made, false when the open one was reused. */
    private boolean connect(BluetoothAdapter adapter, String address) throws IOException {
        if (socket != null && socket.isConnected() && address.equals(connectedTo)) return false;
        close();
        adapter.cancelDiscovery();   // discovery slows connecting to a crawl
        BluetoothDevice device = adapter.getRemoteDevice(address);
        BluetoothSocket s = null;
        try {
            s = device.createRfcommSocketToServiceRecord(SPP);
            s.connect();
        } catch (IOException first) {
            closeQuietly(s);
            s = null;
            // Some cheap printers don't advertise SPP properly; channel 1 almost always works.
            try {
                s = (BluetoothSocket) device.getClass().getMethod("createRfcommSocket", int.class).invoke(device, 1);
                if (s == null) throw first;
                s.connect();
            } catch (Exception second) {
                closeQuietly(s);
                throw new PrintError("Couldn't connect to the printer. Make sure it's on and close by, then try again.");
            }
        }
        socket = s;
        out = s.getOutputStream();
        connectedTo = address;
        return true;
    }

    private void send(byte[] bits, int widthBytes, int height) throws IOException {
        out.write(new byte[]{0x1B, 0x40});                       // ESC @  reset
        for (int y = 0; y < height; y += BAND_ROWS) {
            int rows = Math.min(BAND_ROWS, height - y);
            out.write(new byte[]{0x1D, 0x76, 0x30, 0x00,          // GS v 0  raster image, normal size
                    (byte) widthBytes, (byte) (widthBytes >> 8),
                    (byte) rows, (byte) (rows >> 8)});
            out.write(bits, y * widthBytes, rows * widthBytes);
        }
        out.write(new byte[]{0x1B, 0x64, 0x02});                 // ESC d 2  feed past the tear bar
        out.flush();
    }

    void close() {
        closeQuietly(socket);
        socket = null;
        out = null;
        connectedTo = null;
    }

    private static void closeQuietly(BluetoothSocket s) {
        if (s == null) return;
        try { s.close(); } catch (IOException ignored) { }
    }
}
