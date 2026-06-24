package com.aranaj.androcontrol;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.PrintWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manages heartbeat/keep-alive mechanism for the connection.
 * Sends PING every 10 seconds and expects PONG response.
 * If no PONG is received within timeout, connection is considered dead.
 */
public class HeartbeatManager {
    private static final String TAG = "HeartbeatManager";
    private static final long HEARTBEAT_INTERVAL_MS = 10000; // 10 seconds
    private static final long PONG_TIMEOUT_MS = 5000; // 5 seconds to wait for PONG

    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean running;
    private final AtomicBoolean waitingForPong;
    private final AtomicLong lastPongTime;
    // Last time we successfully sent or received anything. With a small send buffer a
    // returning flush() means TCP is still ACKing (the peer is alive), so recent
    // activity counts as liveness — a congested-but-alive link drops samples, not the
    // connection.
    private final AtomicLong lastActivityTime;
    // While a file transfer is in flight, tolerate a much longer stall before declaring
    // the link dead (a big transfer can briefly stall the shared Wi-Fi link). Cleared
    // when the transfer ends.
    private final AtomicBoolean transferActive = new AtomicBoolean(false);
    private static final long TRANSFER_GRACE_MS = 120_000;

    private PrintWriter writer;
    private HeartbeatListener listener;
    private Thread heartbeatThread;

    public interface HeartbeatListener {
        void onHeartbeatTimeout();
    }

    public HeartbeatManager() {
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.running = new AtomicBoolean(false);
        this.waitingForPong = new AtomicBoolean(false);
        this.lastPongTime = new AtomicLong(System.currentTimeMillis());
        this.lastActivityTime = new AtomicLong(System.currentTimeMillis());
    }

    /**
     * Records successful connection activity (a flushed send or a received line).
     * Counts as liveness alongside PONGs, so a slow link doesn't trip the timeout.
     */
    public void onActivity() {
        lastActivityTime.set(System.currentTimeMillis());
    }

    /** Marks whether a file transfer is in flight (extends the stall grace window). */
    public void setTransferActive(boolean active) {
        transferActive.set(active);
    }

    /**
     * Sets the output writer for sending heartbeats.
     */
    public void setWriter(PrintWriter writer) {
        this.writer = writer;
    }

    /**
     * Sets the listener for heartbeat events.
     */
    public void setListener(HeartbeatListener listener) {
        this.listener = listener;
    }

    /**
     * Starts the heartbeat mechanism.
     */
    public void start() {
        if (running.getAndSet(true)) {
            return; // Already running
        }

        lastPongTime.set(System.currentTimeMillis());

        heartbeatThread = new Thread(() -> {
            Log.d(TAG, "Heartbeat thread started");
            while (running.get()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS);

                    if (!running.get()) {
                        break;
                    }

                    sendPing();

                    // Wait for PONG
                    Thread.sleep(PONG_TIMEOUT_MS);

                    if (!running.get()) {
                        break;
                    }

                    // Check if we received a PONG
                    if (waitingForPong.get()) {
                        // No PONG yet — but recent successful traffic also proves the
                        // link is alive (just congested), so only time out when nothing
                        // at all has flowed for the window.
                        long lastAlive = Math.max(lastPongTime.get(), lastActivityTime.get());
                        long timeSinceAlive = System.currentTimeMillis() - lastAlive;
                        long window = transferActive.get()
                                ? TRANSFER_GRACE_MS
                                : HEARTBEAT_INTERVAL_MS + PONG_TIMEOUT_MS;
                        if (timeSinceAlive > window) {
                            Log.w(TAG, "Heartbeat timeout - no activity received");
                            notifyTimeout();
                        }
                    }
                } catch (InterruptedException e) {
                    Log.d(TAG, "Heartbeat thread interrupted");
                    break;
                }
            }
            Log.d(TAG, "Heartbeat thread stopped");
        }, "HeartbeatThread");

        heartbeatThread.start();
    }

    /**
     * Stops the heartbeat mechanism.
     */
    public void stop() {
        running.set(false);
        writer = null; // Clear writer to prevent any pending tasks from using it
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
            heartbeatThread = null;
        }
    }

    /**
     * Sends a PING message.
     */
    private void sendPing() {
        final PrintWriter w = writer; // Capture reference for thread safety
        if (w == null) {
            return;
        }

        executor.execute(() -> {
            try {
                // Double-check writer is still valid
                if (!running.get() || w.checkError()) {
                    return;
                }
                waitingForPong.set(true);
                synchronized (w) {
                    w.println("PING");
                    w.flush();
                }
                Log.d(TAG, "Sent PING");
            } catch (Exception e) {
                Log.e(TAG, "Failed to send PING", e);
            }
        });
    }

    /**
     * Call this when a PONG is received from the server.
     */
    public void onPongReceived() {
        waitingForPong.set(false);
        lastPongTime.set(System.currentTimeMillis());
        Log.d(TAG, "Received PONG");
    }

    private void notifyTimeout() {
        if (listener != null) {
            mainHandler.post(() -> listener.onHeartbeatTimeout());
        }
    }

    /**
     * Cleans up resources.
     */
    public void shutdown() {
        stop();
        executor.shutdown();
    }
}
