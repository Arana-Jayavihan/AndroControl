package com.aranaj.androcontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Secure storage for sensitive data like authentication tokens.
 * Uses Android Keystore for encryption on API 23+, falls back to obfuscation on older versions.
 */
public class SecureStorage {
    private static final String TAG = "SecureStorage";
    private static final String PREFS_NAME = "SecureTokenStorage";
    private static final String KEYSTORE_ALIAS = "AndroControlKey";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final Context context;
    private final SharedPreferences prefs;

    public SecureStorage(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ensureKeyExists();
        }
    }

    /**
     * Ensures the encryption key exists in the Android Keystore.
     */
    private void ensureKeyExists() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }

        try {
            KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
            keyStore.load(null);

            if (!keyStore.containsAlias(KEYSTORE_ALIAS)) {
                KeyGenerator keyGenerator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);

                KeyGenParameterSpec keySpec = new KeyGenParameterSpec.Builder(
                        KEYSTORE_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build();

                keyGenerator.init(keySpec);
                keyGenerator.generateKey();
                Log.d(TAG, "Generated new encryption key");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to ensure key exists", e);
        }
    }

    /**
     * Gets the secret key from Android Keystore.
     */
    private SecretKey getKey() throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        return (SecretKey) keyStore.getKey(KEYSTORE_ALIAS, null);
    }

    /**
     * Encrypts data using Android Keystore.
     */
    private String encrypt(String plaintext) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Fallback: simple obfuscation for older Android versions
            return obfuscate(plaintext);
        }

        try {
            SecretKey key = getKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);

            byte[] iv = cipher.getIV();
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            // Combine IV and ciphertext
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return Base64.encodeToString(combined, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Encryption failed, using fallback", e);
            return obfuscate(plaintext);
        }
    }

    /**
     * Decrypts data using Android Keystore.
     */
    private String decrypt(String encrypted) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return deobfuscate(encrypted);
        }

        try {
            byte[] combined = Base64.decode(encrypted, Base64.NO_WRAP);

            // Extract IV and ciphertext
            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] ciphertext = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, ciphertext, 0, ciphertext.length);

            SecretKey key = getKey();
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, key, spec);

            byte[] plaintext = cipher.doFinal(ciphertext);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Decryption failed, trying fallback", e);
            return deobfuscate(encrypted);
        }
    }

    /**
     * Simple obfuscation for older Android versions.
     * Not cryptographically secure, but provides some protection.
     */
    private String obfuscate(String plaintext) {
        byte[] bytes = plaintext.getBytes(StandardCharsets.UTF_8);
        byte[] obfuscated = new byte[bytes.length];
        byte key = (byte) (context.getPackageName().hashCode() & 0xFF);

        for (int i = 0; i < bytes.length; i++) {
            obfuscated[i] = (byte) (bytes[i] ^ key ^ i);
        }

        return "OBF:" + Base64.encodeToString(obfuscated, Base64.NO_WRAP);
    }

    /**
     * Deobfuscates data.
     */
    private String deobfuscate(String obfuscated) {
        if (!obfuscated.startsWith("OBF:")) {
            return obfuscated; // Not obfuscated, return as-is
        }

        try {
            byte[] bytes = Base64.decode(obfuscated.substring(4), Base64.NO_WRAP);
            byte[] deobfuscated = new byte[bytes.length];
            byte key = (byte) (context.getPackageName().hashCode() & 0xFF);

            for (int i = 0; i < bytes.length; i++) {
                deobfuscated[i] = (byte) (bytes[i] ^ key ^ i);
            }

            return new String(deobfuscated, StandardCharsets.UTF_8);
        } catch (Exception e) {
            Log.e(TAG, "Deobfuscation failed", e);
            return null;
        }
    }

    /**
     * Saves an authentication token for a server.
     */
    public void saveToken(String serverId, String token) {
        if (token == null || token.isEmpty()) {
            prefs.edit().remove("token_" + serverId).apply();
            return;
        }

        String encrypted = encrypt(token);
        prefs.edit().putString("token_" + serverId, encrypted).apply();
        Log.d(TAG, "Saved token for server: " + serverId);
    }

    /**
     * Retrieves an authentication token for a server.
     */
    public String getToken(String serverId) {
        String encrypted = prefs.getString("token_" + serverId, null);
        if (encrypted == null) {
            return null;
        }

        return decrypt(encrypted);
    }

    /**
     * Removes an authentication token for a server.
     */
    public void removeToken(String serverId) {
        prefs.edit().remove("token_" + serverId).apply();
        Log.d(TAG, "Removed token for server: " + serverId);
    }

    /**
     * Checks if a token exists for a server.
     */
    public boolean hasToken(String serverId) {
        return prefs.contains("token_" + serverId);
    }

    /**
     * Clears all stored tokens.
     */
    public void clearAll() {
        prefs.edit().clear().apply();
        Log.d(TAG, "Cleared all stored tokens");
    }
}
