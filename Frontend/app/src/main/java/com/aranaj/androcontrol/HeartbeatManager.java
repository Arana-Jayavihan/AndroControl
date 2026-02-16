package com.aranaj.androcontrol;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
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

    private PrintWriter writer;
    private HeartbeatListener listener;
    private Thread heartbeatThread;

    public interface HeartbeatListener {
        void onHeartbeatTimeout();
        void onHeartbeatRestored();
    }

    public HeartbeatManager() {
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.running = new AtomicBoolean(false);
        this.waitingForPong = new AtomicBoolean(false);
        this.lastPongTime = new AtomicLong(System.currentTimeMillis());
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
                        // Still waiting - connection might be dead
                        long timeSinceLastPong = System.currentTimeMillis() - lastPongTime.get();
                        if (timeSinceLastPong > HEARTBEAT_INTERVAL_MS + PONG_TIMEOUT_MS) {
                            Log.w(TAG, "Heartbeat timeout - no PONG received");
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

    /**
     * Call this when any message is received from the server.
     * This resets the timeout since we know the connection is alive.
     */
    public void onMessageReceived() {
        lastPongTime.set(System.currentTimeMillis());
    }

    /**
     * Checks if the connection is considered alive.
     */
    public boolean isAlive() {
        long timeSinceLastPong = System.currentTimeMillis() - lastPongTime.get();
        return timeSinceLastPong < (HEARTBEAT_INTERVAL_MS + PONG_TIMEOUT_MS) * 2;
    }

    /**
     * Gets the time since last successful heartbeat.
     */
    public long getTimeSinceLastHeartbeat() {
        return System.currentTimeMillis() - lastPongTime.get();
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
