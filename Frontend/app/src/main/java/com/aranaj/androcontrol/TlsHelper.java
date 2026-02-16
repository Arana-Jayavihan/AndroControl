package com.aranaj.androcontrol;

import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS helper with Trust-On-First-Use (TOFU) certificate pinning.
 * On first connection, the server's certificate fingerprint is shown to the user for confirmation.
 * On subsequent connections, the fingerprint is verified against the saved value.
 */
public class TlsHelper {
    private static final String TAG = "TlsHelper";
    private static final String KEY_PREFIX = "cert_fp_";
    private static final long CONFIRMATION_TIMEOUT_SECONDS = 60;

    private final Context context;
    private final SecureStorage secureStorage;
    private final String serverKey;
    private String expectedFingerprint;
    private TofuConfirmationCallback confirmationCallback;

    /**
     * Callback interface for TOFU certificate confirmation.
     */
    public interface TofuConfirmationCallback {
        /**
         * Called when a certificate is received for the first time.
         * The implementation should show a confirmation dialog to the user.
         * @param fingerprint The certificate fingerprint in XX:XX:XX format
         * @param onConfirm Called when user confirms (accepts the certificate)
         * @param onReject Called when user rejects the certificate
         */
        void onFirstUse(String fingerprint, Runnable onConfirm, Runnable onReject);

        /**
         * Called when a certificate doesn't match the saved fingerprint.
         * This is a serious security warning.
         * @param expectedFingerprint The previously saved fingerprint
         * @param actualFingerprint The current certificate's fingerprint
         * @param onAcceptNew Called if user accepts the new certificate (replaces old)
         * @param onReject Called if user rejects the new certificate
         */
        void onMismatch(String expectedFingerprint, String actualFingerprint,
                        Runnable onAcceptNew, Runnable onReject);
    }

    public TlsHelper(Context context, String serverIp, int serverPort) {
        this.context = context;
        this.secureStorage = new SecureStorage(context);
        this.serverKey = KEY_PREFIX + serverIp + "_" + serverPort;
        loadSavedFingerprint();
    }

    /**
     * Sets the callback for TOFU confirmation dialogs.
     * Must be set before creating connections if user confirmation is desired.
     */
    public void setConfirmationCallback(TofuConfirmationCallback callback) {
        this.confirmationCallback = callback;
    }

    private void loadSavedFingerprint() {
        expectedFingerprint = secureStorage.getEncrypted(serverKey);
        if (expectedFingerprint != null) {
            Log.d(TAG, "Loaded saved certificate fingerprint for " + serverKey);
        }
    }

    private void saveFingerprint(String fingerprint) {
        secureStorage.saveEncrypted(serverKey, fingerprint);
        expectedFingerprint = fingerprint;
        Log.d(TAG, "Saved certificate fingerprint for " + serverKey);
    }

    /**
     * Clears the saved certificate fingerprint for this server.
     * Use this when the user wants to re-trust a server with a new certificate.
     */
    public void clearSavedFingerprint() {
        secureStorage.removeEncrypted(serverKey);
        expectedFingerprint = null;
        Log.d(TAG, "Cleared certificate fingerprint for " + serverKey);
    }

    /**
     * Formats a fingerprint as XX:XX:XX... for display.
     */
    public static String formatFingerprint(String rawFingerprint) {
        if (rawFingerprint == null || rawFingerprint.length() < 2) {
            return rawFingerprint;
        }
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < rawFingerprint.length(); i += 2) {
            if (i > 0) formatted.append(':');
            formatted.append(rawFingerprint.substring(i, Math.min(i + 2, rawFingerprint.length())).toUpperCase());
        }
        return formatted.toString();
    }

    /**
     * Returns whether we have a saved fingerprint for this server.
     */
    public boolean hasSavedFingerprint() {
        return expectedFingerprint != null;
    }

    /**
     * Gets the saved fingerprint for display purposes.
     */
    public String getSavedFingerprint() {
        return expectedFingerprint;
    }

    /**
     * Calculates full SHA-256 fingerprint of a certificate.
     * Uses the entire certificate DER encoding for the hash.
     */
    private String calculateFingerprint(X509Certificate cert) throws NoSuchAlgorithmException, java.security.cert.CertificateEncodingException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        // Use full certificate DER encoding, not just public key
        byte[] certDer = cert.getEncoded();
        byte[] digest = md.digest(certDer);
        // Return hex string for consistency with server
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    /**
     * Creates an SSL socket factory with TOFU trust management.
     * If a confirmation callback is set, user confirmation is required for first use.
     */
    public SSLSocketFactory createSocketFactory() throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustManagers = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                        // Not used for client sockets
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                        if (chain == null || chain.length == 0) {
                            throw new CertificateException("No certificates provided");
                        }

                        X509Certificate serverCert = chain[0];

                        try {
                            // Calculate fingerprint
                            String fingerprint = calculateFingerprint(serverCert);
                            String formattedFingerprint = formatFingerprint(fingerprint);

                            if (expectedFingerprint == null) {
                                // First connection - require user confirmation if callback is set
                                if (confirmationCallback != null) {
                                    if (!requestUserConfirmation(formattedFingerprint, false)) {
                                        throw new CertificateException("User rejected certificate");
                                    }
                                } else {
                                    Log.w(TAG, "First connection - auto-trusting certificate (no callback set)");
                                }
                                saveFingerprint(fingerprint);
                                Log.i(TAG, "Certificate accepted and saved");
                            } else if (!expectedFingerprint.equals(fingerprint)) {
                                // Fingerprint mismatch - serious security warning
                                Log.e(TAG, "Certificate fingerprint mismatch!");
                                Log.e(TAG, "Expected: " + formatFingerprint(expectedFingerprint));
                                Log.e(TAG, "Got: " + formattedFingerprint);

                                if (confirmationCallback != null) {
                                    if (requestUserConfirmationMismatch(
                                            formatFingerprint(expectedFingerprint),
                                            formattedFingerprint)) {
                                        // User accepted new certificate
                                        saveFingerprint(fingerprint);
                                        Log.w(TAG, "User accepted new certificate despite mismatch");
                                    } else {
                                        throw new CertificateException(
                                                "Certificate fingerprint mismatch. " +
                                                        "The server's certificate has changed. " +
                                                        "This could indicate a security issue."
                                        );
                                    }
                                } else {
                                    throw new CertificateException(
                                            "Certificate fingerprint mismatch. " +
                                                    "The server's certificate has changed. " +
                                                    "This could indicate a security issue."
                                    );
                                }
                            } else {
                                Log.d(TAG, "Certificate fingerprint verified");
                            }
                        } catch (NoSuchAlgorithmException | java.security.cert.CertificateEncodingException e) {
                            throw new CertificateException("Failed to calculate fingerprint", e);
                        }
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagers, null);
        return sslContext.getSocketFactory();
    }

    /**
     * Requests user confirmation for first-use certificate.
     * Blocks until user responds or timeout.
     */
    private boolean requestUserConfirmation(String fingerprint, boolean isMismatch) {
        if (confirmationCallback == null) {
            return true; // Auto-accept if no callback
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean accepted = new AtomicBoolean(false);

        confirmationCallback.onFirstUse(
                fingerprint,
                () -> {
                    accepted.set(true);
                    latch.countDown();
                },
                () -> {
                    accepted.set(false);
                    latch.countDown();
                }
        );

        try {
            if (!latch.await(CONFIRMATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Certificate confirmation timed out");
                return false;
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Certificate confirmation interrupted", e);
            return false;
        }

        return accepted.get();
    }

    /**
     * Requests user confirmation for certificate mismatch.
     */
    private boolean requestUserConfirmationMismatch(String expectedFp, String actualFp) {
        if (confirmationCallback == null) {
            return false; // Reject mismatch if no callback
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean accepted = new AtomicBoolean(false);

        confirmationCallback.onMismatch(
                expectedFp,
                actualFp,
                () -> {
                    accepted.set(true);
                    latch.countDown();
                },
                () -> {
                    accepted.set(false);
                    latch.countDown();
                }
        );

        try {
            if (!latch.await(CONFIRMATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                Log.w(TAG, "Certificate mismatch confirmation timed out");
                return false;
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Certificate mismatch confirmation interrupted", e);
            return false;
        }

        return accepted.get();
    }

    /**
     * Creates an SSL socket connected to the specified server.
     */
    public SSLSocket createSocket(String host, int port) throws IOException, NoSuchAlgorithmException, KeyManagementException {
        SSLSocketFactory factory = createSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket(host, port);

        // Enable modern TLS versions
        socket.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.3"});

        return socket;
    }

    /**
     * Creates an SSL socket from an existing socket.
     */
    public SSLSocket createSocket(Socket socket, String host, int port, boolean autoClose)
            throws IOException, NoSuchAlgorithmException, KeyManagementException {
        SSLSocketFactory factory = createSocketFactory();
        return (SSLSocket) factory.createSocket(socket, host, port, autoClose);
    }

    /**
     * Result of a certificate verification check.
     */
    public enum VerificationResult {
        TRUSTED,           // Certificate matches saved fingerprint
        FIRST_USE,         // No saved fingerprint, will trust on first use
        MISMATCH,          // Certificate doesn't match saved fingerprint
        ERROR              // Error during verification
    }

    /**
     * Checks if a connection to the server would be trusted.
     */
    public VerificationResult checkTrust() {
        if (expectedFingerprint == null) {
            return VerificationResult.FIRST_USE;
        }
        return VerificationResult.TRUSTED;
    }

    /**
     * Static method to clear all saved certificates.
     * Note: This only clears the legacy unencrypted storage.
     * Encrypted fingerprints are stored in SecureStorage and should be cleared there.
     */
    public static void clearAllCertificates(Context context) {
        // Clear legacy unencrypted storage
        context.getSharedPreferences("TlsCertificates", Context.MODE_PRIVATE)
                .edit().clear().apply();
        Log.d(TAG, "Cleared all saved certificates (legacy)");
    }

    /**
     * Exception thrown when certificate verification fails.
     */
    public static class CertificateMismatchException extends Exception {
        private final String expectedFingerprint;
        private final String actualFingerprint;

        public CertificateMismatchException(String expected, String actual) {
            super("Certificate fingerprint mismatch");
            this.expectedFingerprint = expected;
            this.actualFingerprint = actual;
        }

        public String getExpectedFingerprint() {
            return expectedFingerprint;
        }

        public String getActualFingerprint() {
            return actualFingerprint;
        }
    }
}
