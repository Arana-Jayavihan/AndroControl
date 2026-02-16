package com.aranaj.androcontrol;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Protocol handler for ACK/NACK message tracking and retry logic.
 * Message format: <seq_id>|<command>:<payload>
 * Response format: <seq_id>|ACK or <seq_id>|NACK:<error_code>
 */
public class Protocol {
    private static final String TAG = "Protocol";
    private static final String PROTOCOL_VERSION = "1.1";
    private static final int MAX_RETRIES = 3;
    private static final long ACK_TIMEOUT_MS = 2000;
    private static final int RANDOM_OFFSET_RANGE = 1000;

    private final AtomicInteger sequenceBase;
    private final SecureRandom secureRandom;
    private final ConcurrentHashMap<Integer, PendingMessage> pendingMessages;
    private final Handler mainHandler;

    private ExecutorService executor;
    private PrintWriter writer;
    private BufferedReader reader;
    private ProtocolListener listener;
    private HeartbeatManager heartbeatManager;
    private final AtomicBoolean running;
    private Thread responseThread;
    private volatile String sessionToken; // Session token from server (v1.1+)

    public interface ProtocolListener {
        void onConnectionLost();
        void onAuthenticationRequired();
        void onError(String message);
    }

    public Protocol() {
        this.secureRandom = new SecureRandom();
        // Initialize sequence base with a random starting point
        this.sequenceBase = new AtomicInteger(secureRandom.nextInt(Integer.MAX_VALUE / 2));
        this.pendingMessages = new ConcurrentHashMap<>();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.running = new AtomicBoolean(false);
        this.executor = Executors.newFixedThreadPool(2);
    }

    /**
     * Generates a cryptographically unpredictable sequence ID.
     * Combines an incrementing base with a random offset to prevent prediction.
     */
    private int generateSequenceId() {
        int base = sequenceBase.incrementAndGet();
        int randomOffset = secureRandom.nextInt(RANDOM_OFFSET_RANGE);
        return (base + randomOffset) & 0x7FFFFFFF; // Ensure positive
    }

    /**
     * Resets the protocol state for a new connection.
     */
    public void reset() {
        stop();
        // Reset sequence base to new random starting point
        sequenceBase.set(secureRandom.nextInt(Integer.MAX_VALUE / 2));
        pendingMessages.clear();
        writer = null;
        reader = null;
        sessionToken = null;

        // Recreate executor if shutdown
        if (executor.isShutdown()) {
            executor = Executors.newFixedThreadPool(2);
        }
    }

    /**
     * Gets the current session token (for session-based auth).
     */
    public String getSessionToken() {
        return sessionToken;
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

        responseThread = new Thread(this::responseLoop, "ProtocolResponseThread");
        responseThread.start();
    }

    /**
     * Stops the protocol handler.
     */
    public void stop() {
        running.set(false);
        pendingMessages.clear();

        // Interrupt the response thread if it's waiting on I/O
        if (responseThread != null && responseThread.isAlive()) {
            responseThread.interrupt();
            responseThread = null;
        }
    }

    /**
     * Sends the authentication message.
     * @deprecated Use authenticate(char[]) for better memory security
     */
    @Deprecated
    public boolean authenticate(String token) {
        if (token == null) return false;
        char[] tokenChars = token.toCharArray();
        try {
            return authenticate(tokenChars);
        } finally {
            SecureStorage.clearCharArray(tokenChars);
        }
    }

    /**
     * Sends the authentication message using char[] for secure memory handling.
     * The token array is cleared after use.
     */
    public boolean authenticate(char[] token) {
        if (writer == null || token == null) return false;

        try {
            // Build auth message
            char[] prefix = "AUTH:".toCharArray();
            char[] message = new char[prefix.length + token.length];
            System.arraycopy(prefix, 0, message, 0, prefix.length);
            System.arraycopy(token, 0, message, prefix.length, token.length);

            try {
                writer.println(new String(message));
                writer.flush();
            } finally {
                // Clear the message buffer
                SecureStorage.clearCharArray(message);
            }

            // Read auth response
            String response = reader.readLine();
            if (response == null) {
                return false;
            }

            response = response.trim();
            Log.d(TAG, "Auth response: " + (response.startsWith("AUTH:") ? response.substring(0, Math.min(response.length(), 15)) : response));

            // Handle new protocol: AUTH:OK or AUTH:OK:<session_token>
            if (response.equals("AUTH:OK") || response.startsWith("AUTH:OK:")) {
                if (response.startsWith("AUTH:OK:") && response.length() > 8) {
                    sessionToken = response.substring(8);
                    Log.d(TAG, "Session token received");
                }
                return true;
            }
            return false;
        } catch (IOException e) {
            Log.e(TAG, "Authentication failed", e);
            return false;
        } finally {
            // Clear the input token
            SecureStorage.clearCharArray(token);
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
     * Checks if connected (has valid writer).
     */
    public boolean isConnected() {
        return writer != null && running.get();
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
        if (writer == null || executor.isShutdown()) {
            if (callback != null) callback.onFailure(-1, "Not connected");
            return;
        }

        int seqId = generateSequenceId();
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
        PrintWriter w = writer; // Capture reference for thread safety
        if (w == null || executor.isShutdown()) return;

        executor.execute(() -> {
            try {
                // Double-check writer is still valid
                if (w.checkError()) return;

                // Send without sequence ID for fire-and-forget
                String message = command + ":" + payload;
                synchronized (w) {
                    w.println(message);
                    w.flush();
                }
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
        PrintWriter w = writer; // Capture reference for thread safety
        if (w == null) {
            pendingMessages.remove(pending.seqId);
            if (pending.callback != null) {
                mainHandler.post(() -> pending.callback.onFailure(pending.seqId, "Not connected"));
            }
            return;
        }

        for (int attempt = 0; attempt < MAX_RETRIES && running.get(); attempt++) {
            try {
                synchronized (w) {
                    w.println(pending.message);
                    w.flush();
                }
                Log.d(TAG, "Sent: " + pending.message + " (attempt " + (attempt + 1) + ")");

                // Wait for ACK
                long startTime = System.currentTimeMillis();
                while (System.currentTimeMillis() - startTime < ACK_TIMEOUT_MS && running.get()) {
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
            while (running.get() && reader != null) {
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
     * Processes a server response.
     */
    private void processResponse(String response) {
        Log.d(TAG, "Received: " + response);

        // Handle PONG
        if (response.equals("PONG")) {
            if (heartbeatManager != null) {
                heartbeatManager.onPongReceived();
            }
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
     * Shuts down the executor.
     */
    public void shutdown() {
        stop();
        executor.shutdownNow();
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
