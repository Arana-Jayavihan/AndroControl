package com.aranaj.androcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * TLS helper with Trust-On-First-Use (TOFU) certificate pinning.
 * On first connection, the server's certificate fingerprint is saved.
 * On subsequent connections, the fingerprint is verified.
 */
public class TlsHelper {
    private static final String TAG = "TlsHelper";
    private static final String PREFS_NAME = "TlsCertificates";
    private static final String KEY_PREFIX = "cert_";

    private final Context context;
    private final String serverKey;
    private String expectedFingerprint;

    public TlsHelper(Context context, String serverIp, int serverPort) {
        this.context = context;
        this.serverKey = KEY_PREFIX + serverIp + "_" + serverPort;
        loadSavedFingerprint();
    }

    private void loadSavedFingerprint() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        expectedFingerprint = prefs.getString(serverKey, null);
        if (expectedFingerprint != null) {
            Log.d(TAG, "Loaded saved certificate fingerprint for " + serverKey);
        }
    }

    private void saveFingerprint(String fingerprint) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(serverKey, fingerprint).apply();
        expectedFingerprint = fingerprint;
        Log.d(TAG, "Saved certificate fingerprint for " + serverKey);
    }

    /**
     * Clears the saved certificate fingerprint for this server.
     * Use this when the user wants to re-trust a server with a new certificate.
     */
    public void clearSavedFingerprint() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().remove(serverKey).apply();
        expectedFingerprint = null;
        Log.d(TAG, "Cleared certificate fingerprint for " + serverKey);
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

                            if (expectedFingerprint == null) {
                                // First connection - trust on first use
                                Log.i(TAG, "First connection - trusting certificate");
                                saveFingerprint(fingerprint);
                            } else if (!expectedFingerprint.equals(fingerprint)) {
                                // Fingerprint mismatch - potential MITM attack
                                Log.e(TAG, "Certificate fingerprint mismatch!");
                                Log.e(TAG, "Expected: " + expectedFingerprint);
                                Log.e(TAG, "Got: " + fingerprint);
                                throw new CertificateException(
                                        "Certificate fingerprint mismatch. " +
                                                "The server's certificate has changed. " +
                                                "This could indicate a security issue."
                                );
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
     */
    public static void clearAllCertificates(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().apply();
        Log.d(TAG, "Cleared all saved certificates");
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
