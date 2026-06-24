package com.aranaj.androcontrol;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLSocket;

/**
 * Bulk file-transfer channel: a second mTLS connection to the server's data port, kept
 * separate from the input/control stream so transfers never add latency. The wire
 * protocol (end-to-end with the desktop agent) is line-framed control + a raw payload:
 *
 * <pre>
 *   SENDER -> OFFER &lt;id&gt; &lt;size&gt; &lt;name-b64&gt; &lt;isZip&gt;\n
 *   RECVR  -> ACCEPT &lt;id&gt;\n | REJECT &lt;id&gt;\n
 *   SENDER -> &lt;exactly size raw bytes&gt;
 *   RECVR  -> DONE &lt;id&gt;\n
 * </pre>
 *
 * One transfer at a time.
 */
public class DataChannel {
    private static final String TAG = "DataChannel";
    private static final long ACCEPT_TIMEOUT_MS = 90_000;
    /** Max file size for a single transfer (5 MB) — larger transfers aren't reliable
     *  over Wi-Fi yet (pending resumable transfers). */
    public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    // Pace outbound transfers to leave Wi-Fi headroom for the control connection, so a
    // big upload can't saturate the uplink and stall the heartbeat. ~4 MB/s.
    private static final long SEND_RATE_BPS = 4L * 1024 * 1024;

    public interface Listener {
        /** An incoming transfer is offered; show an Accept/Reject prompt, then call
         *  {@link #respondToOffer(int, boolean)}. */
        void onIncomingOffer(int id, String name, long size);

        void onReceived(String name);

        void onSent(String name);

        void onTransferError(String message);

        /** Per-chunk progress (also used to keep the control heartbeat alive). */
        void onProgress(int id, String name, long done, long total, boolean sending);
    }

    private final Context appContext;
    private final TlsHelper tlsHelper;
    private final Listener listener;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger seq = new AtomicInteger(0);
    private final Object writeLock = new Object();
    private final BlockingQueue<SendJob> sendQueue = new LinkedBlockingQueue<>();

    private SSLSocket socket;
    private InputStream in;
    private OutputStream out;
    private Thread readerThread;
    private Thread senderThread;

    // Incoming-offer handoff (one at a time).
    private volatile int incomingId = -1;
    private volatile Boolean incomingDecision; // null until the user responds

    // Outgoing pending transfer.
    private volatile SendJob pending;

    public DataChannel(Context context, TlsHelper tlsHelper, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.tlsHelper = tlsHelper;
        this.listener = listener;
    }

    /** Connects the bulk channel. Call off the main thread. */
    public void connect(String host, int port) {
        try {
            SSLSocket s = tlsHelper.createSocket(host, port);
            s.startHandshake();
            InputStream sin = s.getInputStream();
            OutputStream sout = s.getOutputStream();
            String ack = readLine(sin);
            if (!"DATA:OK".equals(ack)) {
                Log.w(TAG, "data handshake rejected: " + ack);
                s.close();
                return;
            }
            this.socket = s;
            this.in = sin;
            this.out = sout;
            running.set(true);
            readerThread = new Thread(this::readerLoop, "DataChannelReader");
            readerThread.start();
            senderThread = new Thread(this::senderLoop, "DataChannelSender");
            senderThread.start();
            Log.d(TAG, "bulk channel connected to " + host + ":" + port);
        } catch (Exception e) {
            Log.e(TAG, "bulk channel connect failed", e);
        }
    }

    public boolean isConnected() {
        return running.get() && socket != null && !socket.isClosed();
    }

    public void close() {
        running.set(false);
        SendJob p = pending;
        if (p != null) {
            finishSend(p, false, null); // unblock a waiting sender
        }
        if (readerThread != null) readerThread.interrupt();
        if (senderThread != null) senderThread.interrupt();
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
    }

    /** Queues a cached file to send to the desktop (deleted after the transfer). */
    public void enqueueSend(File cacheFile, String displayName) {
        if (cacheFile == null || !cacheFile.exists()) return;
        if (cacheFile.length() > MAX_FILE_BYTES) {
            listener.onTransferError(displayName + ": exceeds 5 MB limit");
            cacheFile.delete();
            return;
        }
        sendQueue.add(new SendJob(cacheFile, displayName));
    }

    /** Called when the user accepts/rejects an incoming offer. */
    public void respondToOffer(int id, boolean accept) {
        if (id == incomingId) {
            incomingDecision = accept;
        }
    }

    // ---------------- sender ----------------

    private void senderLoop() {
        while (running.get()) {
            SendJob job;
            try {
                job = sendQueue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                doSend(job);
            } catch (Exception e) {
                Log.e(TAG, "send failed", e);
                finishSend(job, false, "error");
            }
        }
    }

    private void doSend(SendJob job) {
        int id = seq.incrementAndGet();
        job.id = id;
        pending = job;
        String nameB64 = Base64.encodeToString(job.name.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        try {
            writeLine("OFFER " + id + " " + job.file.length() + " " + nameB64 + " 0");
        } catch (IOException e) {
            finishSend(job, false, "send failed");
            return;
        }
        // Wait for the reader loop to complete the transfer (ACCEPT -> stream -> DONE), a
        // REJECT, an error, or a disconnect. No short timeout: large transfers can run
        // long, and close() unblocks us if the connection drops.
        try {
            job.done.take();
        } catch (InterruptedException ignored) {
        }
    }

    private void finishSend(SendJob job, boolean ok, String err) {
        if (!job.finished.compareAndSet(false, true)) {
            return; // already finalized (reader + close/timeout can race)
        }
        job.file.delete();
        if (pending == job) {
            pending = null;
        }
        if (ok) {
            listener.onSent(job.name);
        } else if (err != null) {
            listener.onTransferError(err);
        }
        job.done.offer(Boolean.TRUE);
    }

    // ---------------- reader ----------------

    private void readerLoop() {
        try {
            while (running.get()) {
                String line = readLine(in);
                if (line == null) break;
                if (line.isEmpty()) continue;
                String[] f = line.split(" ");
                switch (f[0]) {
                    case "OFFER":
                        handleIncoming(f);
                        break;
                    case "ACCEPT":
                        if (pending != null && f.length >= 2 && String.valueOf(pending.id).equals(f[1])) {
                            streamPending();
                        }
                        break;
                    case "REJECT":
                        if (pending != null && f.length >= 2 && String.valueOf(pending.id).equals(f[1])) {
                            finishSend(pending, false, "rejected by desktop");
                        }
                        break;
                    case "DONE":
                        // handled inline in streamPending
                        break;
                    default:
                        break;
                }
            }
        } catch (Exception e) {
            if (running.get()) Log.e(TAG, "reader loop error", e);
        }
        running.set(false);
    }

    private void streamPending() {
        SendJob job = pending;
        long total = job.file.length();
        long done = 0;
        try (FileInputStream fis = new FileInputStream(job.file)) {
            synchronized (writeLock) {
                byte[] buf = new byte[64 * 1024];
                int n;
                long startNs = System.nanoTime();
                while ((n = fis.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    listener.onProgress(job.id, job.name, done, total, true);
                    pace(done, startNs);
                }
                out.flush();
            }
            String doneLine = readLine(in); // expect DONE <id>
            boolean ok = doneLine != null && doneLine.startsWith("DONE");
            finishSend(job, ok, ok ? null : "no ack");
        } catch (IOException e) {
            finishSend(job, false, "send error");
        }
    }

    private void handleIncoming(String[] f) throws IOException {
        // OFFER <id> <size> <name-b64> <isZip>
        if (f.length < 5) return;
        final int id;
        final long size;
        try {
            id = Integer.parseInt(f[1]);
            size = Long.parseLong(f[2]);
        } catch (NumberFormatException e) {
            return;
        }
        String name = sanitize(new String(Base64.decode(f[3], Base64.NO_WRAP), StandardCharsets.UTF_8));
        if (name.isEmpty() || size < 0) {
            writeLine("REJECT " + id);
            return;
        }
        if (size > MAX_FILE_BYTES) {
            writeLine("REJECT " + id);
            listener.onTransferError(name + ": exceeds 5 MB limit");
            return;
        }

        // Ask the user (notification with Accept/Reject) and wait.
        incomingId = id;
        incomingDecision = null;
        listener.onIncomingOffer(id, name, size);
        long deadline = System.currentTimeMillis() + ACCEPT_TIMEOUT_MS;
        while (incomingDecision == null && System.currentTimeMillis() < deadline && running.get()) {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                break;
            }
        }
        boolean accept = Boolean.TRUE.equals(incomingDecision);
        incomingId = -1;
        if (!accept) {
            writeLine("REJECT " + id);
            drain(size);
            return;
        }

        writeLine("ACCEPT " + id);
        if (receiveToStore(id, name, size)) {
            writeLine("DONE " + id);
            listener.onReceived(name);
        } else {
            // We already ACCEPTed, so we must still consume the bytes to stay in sync,
            // but receiveToStore drains on failure.
            listener.onTransferError("could not save " + name);
        }
    }

    private boolean receiveToStore(int id, String name, long size) {
        OutputStream os = null;
        Uri mediaUri = null;
        File legacyFile = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AndroControl");
                cv.put(MediaStore.Downloads.IS_PENDING, 1);
                mediaUri = appContext.getContentResolver()
                        .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (mediaUri == null) {
                    drain(size);
                    return false;
                }
                os = appContext.getContentResolver().openOutputStream(mediaUri);
            } else {
                File dir = new File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "AndroControl");
                dir.mkdirs();
                legacyFile = new File(dir, name);
                os = new FileOutputStream(legacyFile);
            }
            if (os == null) {
                drain(size);
                return false;
            }
            copyWithProgress(os, size, id, name);
            os.close();
            os = null;
            if (mediaUri != null) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.IS_PENDING, 0);
                appContext.getContentResolver().update(mediaUri, cv, null, null);
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "receive failed", e);
            try {
                if (os != null) os.close();
            } catch (IOException ignored) {
            }
            if (mediaUri != null) appContext.getContentResolver().delete(mediaUri, null, null);
            if (legacyFile != null) legacyFile.delete();
            return false;
        }
    }

    // ---------------- io helpers ----------------

    /** Reads one '\n'-terminated line (ASCII control) without buffering past it. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
        }
        if (c == -1 && sb.length() == 0) return null;
        return sb.toString();
    }

    private void writeLine(String s) throws IOException {
        synchronized (writeLock) {
            out.write((s + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    /** Receives exactly {@code total} bytes into {@code os}, reporting progress. */
    private void copyWithProgress(OutputStream os, long total, int id, String name) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = total, done = 0;
        while (left > 0) {
            int want = (int) Math.min(buf.length, left);
            int r = in.read(buf, 0, want);
            if (r < 0) throw new IOException("unexpected EOF");
            os.write(buf, 0, r);
            left -= r;
            done += r;
            listener.onProgress(id, name, done, total, false);
        }
        os.flush();
    }

    /** Consumes and discards n bytes to keep the stream framed after a reject/failure. */
    private void drain(long n) {
        try {
            byte[] buf = new byte[64 * 1024];
            long left = n;
            while (left > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (r < 0) break;
                left -= r;
            }
        } catch (IOException ignored) {
        }
    }

    /** Throttles the sender to SEND_RATE_BPS by sleeping when ahead of schedule. */
    private static void pace(long sentBytes, long startNs) {
        long allowedNs = sentBytes * 1_000_000_000L / SEND_RATE_BPS;
        long sleepMs = (allowedNs - (System.nanoTime() - startNs)) / 1_000_000L;
        if (sleepMs > 0) {
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static String sanitize(String name) {
        String base = new File(name).getName();
        if (base.equals(".") || base.equals("..") || base.isEmpty()) return "";
        return base;
    }

    private static final class SendJob {
        final File file;
        final String name;
        int id;
        final java.util.concurrent.ArrayBlockingQueue<Boolean> done =
                new java.util.concurrent.ArrayBlockingQueue<>(1);
        final AtomicBoolean finished = new AtomicBoolean(false);

        SendJob(File file, String name) {
            this.file = file;
            this.name = name;
        }
    }
}
