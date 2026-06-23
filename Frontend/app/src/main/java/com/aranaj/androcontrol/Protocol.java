package com.aranaj.androcontrol;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Protocol handler.
 *
 * Wire format for commands: {@code <command>:<payload>} (fire-and-forget).
 * Identity is established by mutual TLS; see {@link #establishSession}.
 */
public class Protocol {
    private static final String TAG = "Protocol";

    private final Handler mainHandler;
    private final AtomicBoolean running;

    // Outbound commands are drained by a single sender thread so they stay ordered.
    // Consecutive mouse-moves are conflated (deltas summed) while queued, so a burst of
    // input can't grow an unbounded backlog or monopolise the writer lock — which would
    // starve the heartbeat PING and drop the connection.
    private static final String CMD_MOVE = "M";
    private static final int MAX_SEND_QUEUE = 256;
    private final ArrayDeque<String> sendQueue = new ArrayDeque<>();
    private final Object sendLock = new Object();
    private Thread senderThread;

    private PrintWriter writer;
    private BufferedReader reader;
    private ProtocolListener listener;
    private HeartbeatManager heartbeatManager;
    private Thread responseThread;

    public interface ProtocolListener {
        void onConnectionLost();
    }

    public Protocol() {
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.running = new AtomicBoolean(false);
    }

    /**
     * Resets the protocol state for a new connection.
     */
    public void reset() {
        stop();
        writer = null;
        reader = null;
        synchronized (sendLock) {
            sendQueue.clear();
        }
    }

    /**
     * Sets the I/O streams.
     */
    public void setStreams(PrintWriter writer, BufferedReader reader) {
        this.writer = writer;
        this.reader = reader;
    }

    /**
     * Sets the protocol listener.
     */
    public void setListener(ProtocolListener listener) {
        this.listener = listener;
    }

    /**
     * Sets the heartbeat manager for PONG notifications.
     */
    public void setHeartbeatManager(HeartbeatManager heartbeatManager) {
        this.heartbeatManager = heartbeatManager;
    }

    /**
     * Starts the response listener thread.
     */
    public void start() {
        if (running.getAndSet(true)) {
            return; // Already running
        }
        synchronized (sendLock) {
            sendQueue.clear();
        }
        senderThread = new Thread(this::senderLoop, "ProtocolSenderThread");
        senderThread.start();
        responseThread = new Thread(this::responseLoop, "ProtocolResponseThread");
        responseThread.start();
    }

    /**
     * Stops the protocol handler.
     */
    public void stop() {
        running.set(false);
        synchronized (sendLock) {
            sendLock.notifyAll(); // wake the sender so it can exit
        }
        if (senderThread != null && senderThread.isAlive()) {
            senderThread.interrupt();
            senderThread = null;
        }
        if (responseThread != null && responseThread.isAlive()) {
            responseThread.interrupt();
            responseThread = null;
        }
    }

    /**
     * Outcome of the identity handshake. With mTLS the device is identified by its
     * client certificate (presented during the TLS handshake); the server speaks first.
     */
    public enum AuthOutcome {
        AUTHENTICATED,    // server recognized our client certificate
        PAIRED,           // unknown cert; paired successfully with the enrollment token
        NEEDS_ENROLLMENT, // server requires pairing but we have no enrollment token
        FAILED,           // pairing failed (bad token / server error / I/O)
        LOCKED,           // too many failed attempts; the server locked out this IP
        BUSY              // another device already holds the single active session
    }

    /**
     * Establishes the session over the already mutually-authenticated TLS connection.
     * The server speaks first:
     *   AUTH:OK        -> our certificate is recognized (AUTHENTICATED)
     *   AUTH:LOCKED    -> the IP is locked out (LOCKED)
     *   PAIR:REQUIRED  -> unknown cert; if an enrollment token is available we send
     *                     PAIR:&lt;token&gt;:&lt;clientId&gt;:&lt;name&gt; and read the result,
     *                     otherwise NEEDS_ENROLLMENT.
     * The enrollment token array is cleared after use.
     */
    public AuthOutcome establishSession(char[] enrollToken, String clientId, String deviceName) {
        if (reader == null || writer == null) {
            SecureStorage.clearCharArray(enrollToken);
            return AuthOutcome.FAILED;
        }
        try {
            String first = reader.readLine();
            if (first == null) return AuthOutcome.FAILED;
            first = first.trim();
            Log.d(TAG, "Handshake: " + first);

            if (first.equals("AUTH:OK")) return AuthOutcome.AUTHENTICATED;
            if (first.equals("AUTH:LOCKED")) return AuthOutcome.LOCKED;
            if (first.equals("AUTH:BUSY")) return AuthOutcome.BUSY;
            if (!first.equals("PAIR:REQUIRED")) return AuthOutcome.FAILED;

            // Server wants pairing.
            if (enrollToken == null || enrollToken.length == 0) {
                return AuthOutcome.NEEDS_ENROLLMENT;
            }

            String safeName = sanitizeDeviceName(deviceName);
            String safeId = clientId != null ? clientId : "";
            char[] prefix = "PAIR:".toCharArray();
            char[] suffix = (":" + safeId + ":" + safeName).toCharArray();
            char[] message = new char[prefix.length + enrollToken.length + suffix.length];
            System.arraycopy(prefix, 0, message, 0, prefix.length);
            System.arraycopy(enrollToken, 0, message, prefix.length, enrollToken.length);
            System.arraycopy(suffix, 0, message, prefix.length + enrollToken.length, suffix.length);
            try {
                writer.println(new String(message));
                writer.flush();
            } finally {
                SecureStorage.clearCharArray(message);
            }

            String resp = reader.readLine();
            if (resp == null) return AuthOutcome.FAILED;
            resp = resp.trim();
            Log.d(TAG, "Pair result: " + resp);
            if (resp.equals("PAIR:OK")) return AuthOutcome.PAIRED;
            if (resp.equals("AUTH:LOCKED")) return AuthOutcome.LOCKED;
            if (resp.equals("AUTH:BUSY")) return AuthOutcome.BUSY;
            return AuthOutcome.FAILED;
        } catch (IOException e) {
            Log.e(TAG, "Handshake failed", e);
            return AuthOutcome.FAILED;
        } finally {
            SecureStorage.clearCharArray(enrollToken);
        }
    }

    /**
     * Asks the server to revoke this device (fire-and-forget). The server revokes
     * the device tied to this connection and closes it. Call on a background thread.
     */
    public void sendUnpair() {
        PrintWriter w = writer;
        if (w != null) {
            try {
                synchronized (w) {
                    w.println("UNPAIR");
                    w.flush();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send unpair", e);
            }
        }
    }

    private static String sanitizeDeviceName(String name) {
        if (name == null) return "Android device";
        String cleaned = name.replace('\n', ' ').replace('\r', ' ').trim();
        if (cleaned.isEmpty()) cleaned = "Android device";
        if (cleaned.length() > 64) cleaned = cleaned.substring(0, 64);
        return cleaned;
    }

    /**
     * Checks if connected (has valid writer).
     */
    public boolean isConnected() {
        return writer != null && running.get();
    }

    /**
     * Sends a command without waiting for an ACK (fire-and-forget).
     * Use for all input commands (mouse, keys, scroll).
     */
    public void sendCommandNoAck(String command, String payload) {
        if (!running.get() || writer == null) return;

        synchronized (sendLock) {
            // Conflate consecutive moves: if the last still-queued command is a move,
            // sum the new delta into it instead of appending another packet. Discrete
            // commands break the chain, so submission order is preserved.
            if (CMD_MOVE.equals(command)) {
                String merged = mergeMove(sendQueue.peekLast(), payload);
                if (merged != null) {
                    sendQueue.pollLast();
                    sendQueue.addLast(merged);
                    sendLock.notify();
                    return;
                }
            }
            if (sendQueue.size() < MAX_SEND_QUEUE) {
                sendQueue.addLast(command + ":" + payload);
                sendLock.notify();
            }
        }
    }

    /**
     * Drains the send queue on a single thread so commands stay ordered. Blocks on the
     * queue (not the writer lock) when idle, so the writer stays free for the heartbeat.
     */
    private void senderLoop() {
        while (running.get()) {
            String message;
            synchronized (sendLock) {
                while (running.get() && sendQueue.isEmpty()) {
                    try {
                        sendLock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (!running.get()) return;
                message = sendQueue.pollFirst();
            }
            PrintWriter w = writer;
            if (w == null) continue;
            try {
                if (w.checkError()) continue;
                synchronized (w) {
                    w.println(message);
                    w.flush();
                }
                // A returning flush proves the link is draining → liveness for heartbeat.
                if (heartbeatManager != null) {
                    heartbeatManager.onActivity();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send command", e);
            }
        }
    }

    /**
     * If {@code lastMsg} is a still-queued move ("M:dx,dy"), returns a merged move with
     * {@code newPayload}'s delta summed in; otherwise null (don't conflate).
     */
    private static String mergeMove(String lastMsg, String newPayload) {
        if (lastMsg == null || !lastMsg.startsWith(CMD_MOVE + ":")) return null;
        try {
            String[] a = lastMsg.substring(CMD_MOVE.length() + 1).split(",");
            String[] b = newPayload.split(",");
            if (a.length != 2 || b.length != 2) return null;
            int x = Integer.parseInt(a[0].trim()) + Integer.parseInt(b[0].trim());
            int y = Integer.parseInt(a[1].trim()) + Integer.parseInt(b[1].trim());
            return CMD_MOVE + ":" + x + "," + y;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Response loop that reads and processes server responses.
     */
    private void responseLoop() {
        Log.d(TAG, "Response loop started");
        try {
            while (running.get() && reader != null) {
                String line = reader.readLine();
                if (line == null) {
                    Log.w(TAG, "Connection closed by server");
                    if (listener != null) {
                        mainHandler.post(() -> listener.onConnectionLost());
                    }
                    break;
                }

                // Any received line is liveness (covers PONG and any server reply).
                if (heartbeatManager != null) {
                    heartbeatManager.onActivity();
                }

                line = line.trim();
                if (line.isEmpty()) continue;

                processResponse(line);
            }
        } catch (IOException e) {
            if (running.get()) {
                Log.e(TAG, "Response loop error", e);
                if (listener != null) {
                    mainHandler.post(() -> listener.onConnectionLost());
                }
            }
        }
        running.set(false);
        Log.d(TAG, "Response loop ended");
    }

    /**
     * Processes a server response. Commands are fire-and-forget, so only PONG and
     * server-initiated TIMEOUT need handling; ACK/NACK lines are ignored.
     */
    private void processResponse(String response) {
        if (response.equals("PONG")) {
            if (heartbeatManager != null) {
                heartbeatManager.onPongReceived();
            }
            return;
        }
        if (response.equals("TIMEOUT")) {
            Log.w(TAG, "Server reported timeout");
            if (listener != null) {
                mainHandler.post(() -> listener.onConnectionLost());
            }
        }
    }

    /**
     * Sends a graceful disconnect message.
     */
    public void disconnect() {
        PrintWriter w = writer; // Capture reference
        if (w != null) {
            try {
                synchronized (w) {
                    w.println("DISCONNECT");
                    w.flush();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to send disconnect", e);
            }
        }
        reset(); // Clear all state
    }

    /**
     * Shuts down the send executor.
     */
    public void shutdown() {
        stop();
    }
}
