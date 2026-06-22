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
import javax.net.ssl.X509KeyManager;
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
    private static final String ALIAS = "androcontrol_client";

    private ClientIdentity() {}

    /** Generates the client key + self-signed cert on first use; no-op afterwards. */
    public static synchronized void ensureKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(ANDROID_KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(ALIAS)) {
            return;
        }

        KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE);

        Calendar start = Calendar.getInstance();
        Calendar end = Calendar.getInstance();
        end.add(Calendar.YEAR, 20);

        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
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

        return new KeyManager[]{
                new X509KeyManager() {
                    @Override
                    public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                        return ALIAS;
                    }

                    @Override
                    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
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
