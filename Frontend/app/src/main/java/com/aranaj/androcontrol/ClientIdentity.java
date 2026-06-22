package com.aranaj.androcontrol;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.math.BigInteger;
import java.net.Socket;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Calendar;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.security.auth.x500.X500Principal;

/**
 * This device's client certificate for mutual TLS.
 *
 * An EC key pair is generated once in the Android Keystore (non-exportable, and
 * hardware-backed where available) with a long-lived self-signed certificate, and
 * reused across all servers. The server identifies the device by the SHA-256
 * fingerprint of this certificate; the private key never leaves the device.
 */
public final class ClientIdentity {
    private static final String TAG = "ClientIdentity";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    // v2: the original key authorized only SHA-* digests. Conscrypt signs the TLS
    // CertificateVerify as raw bytes (NONEwithECDSA), which that key rejected, so the
    // mTLS handshake died right after presenting the cert. v2 additionally authorizes
    // DIGEST_NONE. The alias bump forces a one-time regeneration; the old key never
    // completed a handshake (was never paired), so nothing is lost.
    private static final String ALIAS = "androcontrol_client_v2";
    private static final String LEGACY_ALIAS = "androcontrol_client";

    private ClientIdentity() {}

    /** Generates the client key + self-signed cert on first use; no-op afterwards. */
    public static synchronized void ensureKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        // Drop the superseded key that lacked DIGEST_NONE.
        if (ks.containsAlias(LEGACY_ALIAS)) {
            ks.deleteEntry(LEGACY_ALIAS);
            Log.i(TAG, "Removed legacy client key (lacked DIGEST_NONE)");
        }
        if (ks.containsAlias(ALIAS)) {
            return;
        }

        KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE);

        Calendar start = Calendar.getInstance();
        Calendar end = Calendar.getInstance();
        end.add(Calendar.YEAR, 20);

        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setKeySize(256)
                .setCertificateSubject(new X500Principal("CN=AndroControl Client"))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(start.getTime())
                .setCertificateNotAfter(end.getTime())
                .build();

        kpg.initialize(spec);
        kpg.generateKeyPair();
        Log.i(TAG, "Generated client certificate key pair");
    }

    /** KeyManagers that present this device's client certificate during the TLS handshake. */
    public static KeyManager[] keyManagers() throws Exception {
        ensureKey();

        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        final PrivateKey privateKey = (PrivateKey) ks.getKey(ALIAS, null);
        final X509Certificate cert = (X509Certificate) ks.getCertificate(ALIAS);
        final X509Certificate[] chain = new X509Certificate[]{cert};

        // Use X509ExtendedKeyManager and override BOTH the socket- and engine-based
        // alias selectors. Conscrypt often drives an SSLSocket through an internal
        // SSLEngine, in which case it calls chooseEngineClientAlias(); a plain
        // X509KeyManager lacks that method, so no client certificate would be sent
        // and the mTLS server would reject the handshake.
        return new KeyManager[]{
                new X509ExtendedKeyManager() {
                    @Override
                    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                        return ALIAS;
                    }

                    @Override
                    public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
                        return ALIAS;
                    }

                    @Override
                    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                        return null;
                    }

                    @Override
                    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
                        return null;
                    }

                    @Override
                    public X509Certificate[] getCertificateChain(String alias) {
                        return chain;
                    }

                    @Override
                    public String[] getClientAliases(String keyType, Principal[] issuers) {
                        return new String[]{ALIAS};
                    }

                    @Override
                    public String[] getServerAliases(String keyType, Principal[] issuers) {
                        return null;
                    }

                    @Override
                    public PrivateKey getPrivateKey(String alias) {
                        return privateKey;
                    }
                }
        };
    }
}
