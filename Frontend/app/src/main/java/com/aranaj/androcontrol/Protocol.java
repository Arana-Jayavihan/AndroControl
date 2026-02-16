package com.aranaj.androcontrol;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Protocol handler for ACK/NACK message tracking and retry logic.
 * Message format: <seq_id>|<command>:<payload>
 * Response format: <seq_id>|ACK or <seq_id>|NACK:<error_code>
 */
public class Protocol {
    private static final String TAG = "Protocol";
    private static final String PROTOCOL_VERSION = "1.0";
    private static final int MAX_RETRIES = 3;
    private static final long ACK_TIMEOUT_MS = 2000;

    private final AtomicInteger sequenceCounter;
    private final ConcurrentHashMap<Integer, PendingMessage> pendingMessages;
    private final ExecutorService executor;
    private final Handler mainHandler;

    private PrintWriter writer;
    private BufferedReader reader;
    private ProtocolListener listener;
    private volatile boolean running;

    public interface ProtocolListener {
        void onConnectionLost();
        void onAuthenticationRequired();
        void onError(String message);
    }

    public Protocol() {
        this.sequenceCounter = new AtomicInteger(0);
        this.pendingMessages = new ConcurrentHashMap<>();
        this.executor = Executors.newFixedThreadPool(2);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.running = false;
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
     * Starts the response listener thread.
     */
    public void start() {
        if (running) return;
        running = true;

        executor.execute(this::responseLoop);
    }

    /**
     * Stops the protocol handler.
     */
    public void stop() {
        running = false;
        pendingMessages.clear();
    }

    /**
     * Sends the authentication message.
     */
    public boolean authenticate(String token) {
        if (writer == null) return false;

        try {
            writer.println("AUTH:" + token);
            writer.flush();

            // Read auth response
            String response = reader.readLine();
            if (response == null) {
                return false;
            }

            response = response.trim();
            Log.d(TAG, "Auth response: " + response);

            return response.equals("AUTH:OK");
        } catch (IOException e) {
            Log.e(TAG, "Authentication failed", e);
            return false;
        }
    }

    /**
     * Sends a version negotiation message.
     */
    public boolean negotiateVersion() {
        if (writer == null) return false;

        try {
            writer.println("VERSION:" + PROTOCOL_VERSION);
            writer.flush();

            String response = reader.readLine();
            if (response == null) {
                return false;
            }

            response = response.trim();
            Log.d(TAG, "Version response: " + response);

            return response.contains(":OK") || response.contains(":COMPATIBLE");
        } catch (IOException e) {
            Log.e(TAG, "Version negotiation failed", e);
            return false;
        }
    }

    /**
     * Sends a command and waits for ACK.
     * Returns true if ACK received, false otherwise.
     */
    public void sendCommand(String command, String payload) {
        sendCommand(command, payload, null);
    }

    /**
     * Sends a command with callback.
     */
    public void sendCommand(String command, String payload, CommandCallback callback) {
        if (writer == null) {
            if (callback != null) callback.onFailure(-1, "Not connected");
            return;
        }

        int seqId = sequenceCounter.incrementAndGet();
        String message = formatMessage(seqId, command, payload);

        PendingMessage pending = new PendingMessage(seqId, message, callback);
        pendingMessages.put(seqId, pending);

        executor.execute(() -> {
            sendWithRetry(pending);
        });
    }

    /**
     * Sends a command without waiting for ACK (fire-and-forget).
     * Use for high-frequency commands like mouse movement.
     */
    public void sendCommandNoAck(String command, String payload) {
        if (writer == null) return;

        executor.execute(() -> {
            try {
                // Send without sequence ID for fire-and-forget
                String message = command + ":" + payload;
                writer.println(message);
                writer.flush();
            } catch (Exception e) {
                Log.e(TAG, "Failed to send command", e);
            }
        });
    }

    /**
     * Formats a message with sequence ID.
     */
    private String formatMessage(int seqId, String command, String payload) {
        if (payload != null && !payload.isEmpty()) {
            return seqId + "|" + command + ":" + payload;
        }
        return seqId + "|" + command + ":";
    }

    /**
     * Sends a message with retry logic.
     */
    private void sendWithRetry(PendingMessage pending) {
        for (int attempt = 0; attempt < MAX_RETRIES && running; attempt++) {
            try {
                writer.println(pending.message);
                writer.flush();
                Log.d(TAG, "Sent: " + pending.message + " (attempt " + (attempt + 1) + ")");

                // Wait for ACK
                long startTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - startTime < ACK_TIMEOUT_MS && running) {
                    if (pending.acknowledged) {
                        pendingMessages.remove(pending.seqId);
                        if (pending.callback != null) {
                            mainHandler.post(() -> pending.callback.onSuccess(pending.seqId));
                        }
                        return;
                    }
                    Thread.sleep(50);
                }

                if (pending.acknowledged) {
                    pendingMessages.remove(pending.seqId);
                    return;
                }

                Log.w(TAG, "ACK timeout for seq " + pending.seqId + ", retrying...");
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.e(TAG, "Send failed", e);
            }
        }

        // Max retries reached
        pendingMessages.remove(pending.seqId);
        if (pending.callback != null) {
            mainHandler.post(() -> pending.callback.onFailure(pending.seqId, "Max retries reached"));
        }
    }

    /**
     * Response loop that reads and processes server responses.
     */
    private void responseLoop() {
        Log.d(TAG, "Response loop started");
        try {
            while (running && reader != null) {
                String line = reader.readLine();
                if (line == null) {
                    Log.w(TAG, "Connection closed by server");
                    if (listener != null) {
                        mainHandler.post(() -> listener.onConnectionLost());
                    }
                    break;
                }

                line = line.trim();
                if (line.isEmpty()) continue;

                processResponse(line);
            }
        } catch (IOException e) {
            if (running) {
                Log.e(TAG, "Response loop error", e);
                if (listener != null) {
                    mainHandler.post(() -> listener.onConnectionLost());
                }
            }
        }
        Log.d(TAG, "Response loop ended");
    }

    /**
     * Processes a server response.
     */
    private void processResponse(String response) {
        Log.d(TAG, "Received: " + response);

        // Handle PONG
        if (response.equals("PONG")) {
            // Heartbeat response handled elsewhere
            return;
        }

        // Handle TIMEOUT
        if (response.equals("TIMEOUT")) {
            Log.w(TAG, "Server reported timeout");
            if (listener != null) {
                mainHandler.post(() -> listener.onConnectionLost());
            }
            return;
        }

        // Parse seq_id|response format
        int pipeIndex = response.indexOf('|');
        if (pipeIndex > 0) {
            try {
                int seqId = Integer.parseInt(response.substring(0, pipeIndex));
                String result = response.substring(pipeIndex + 1);

                PendingMessage pending = pendingMessages.get(seqId);
                if (pending != null) {
                    if (result.equals("ACK")) {
                        pending.acknowledged = true;
                    } else if (result.startsWith("NACK:")) {
                        pending.acknowledged = true;
                        String errorCode = result.substring(5);
                        Log.w(TAG, "NACK received for seq " + seqId + ": " + errorCode);
                        if (pending.callback != null) {
                            mainHandler.post(() -> pending.callback.onFailure(seqId, "NACK: " + errorCode));
                        }
                    }
                }
            } catch (NumberFormatException e) {
                Log.w(TAG, "Invalid seq_id in response: " + response);
            }
        } else {
            // Handle responses without seq_id
            if (response.startsWith("ACK")) {
                // Simple ACK for fire-and-forget
            } else if (response.startsWith("NACK:")) {
                String errorCode = response.substring(5);
                Log.w(TAG, "NACK received: " + errorCode);
            }
        }
    }

    /**
     * Sends a graceful disconnect message.
     */
    public void disconnect() {
        if (writer != null) {
            try {
                writer.println("DISCONNECT");
                writer.flush();
            } catch (Exception e) {
                Log.e(TAG, "Failed to send disconnect", e);
            }
        }
        stop();
    }

    /**
     * Shuts down the executor.
     */
    public void shutdown() {
        stop();
        executor.shutdown();
    }

    /**
     * Callback for command results.
     */
    public interface CommandCallback {
        void onSuccess(int seqId);
        void onFailure(int seqId, String error);
    }

    /**
     * Represents a pending message awaiting ACK.
     */
    private static class PendingMessage {
        final int seqId;
        final String message;
        final CommandCallback callback;
        volatile boolean acknowledged;

        PendingMessage(int seqId, String message, CommandCallback callback) {
            this.seqId = seqId;
            this.message = message;
            this.callback = callback;
            this.acknowledged = false;
        }
    }
}
