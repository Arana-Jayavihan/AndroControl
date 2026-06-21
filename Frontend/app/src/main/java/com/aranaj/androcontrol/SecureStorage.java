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
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Secure storage for sensitive data like authentication tokens.
 * Uses Android Keystore for AES-GCM encryption. Requires Android 6.0+ (API 23).
 */
public class SecureStorage {
    private static final String TAG = "SecureStorage";
    private static final String PREFS_NAME = "SecureTokenStorage";
    private static final String KEYSTORE_ALIAS = "AndroControlKey";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int MINIMUM_API_LEVEL = Build.VERSION_CODES.M;

    private final Context context;
    private final SharedPreferences prefs;

    public SecureStorage(Context context) {
        if (Build.VERSION.SDK_INT < MINIMUM_API_LEVEL) {
            throw new SecurityException("Secure storage requires Android 6.0+ (API 23)");
        }
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ensureKeyExists();
    }

    /**
     * Ensures the encryption key exists in the Android Keystore.
     */
    private void ensureKeyExists() {
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
     * Encrypts data using Android Keystore with AES-GCM.
     */
    private String encrypt(String plaintext) {
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
            Log.e(TAG, "Encryption failed", e);
            throw new SecurityException("Failed to encrypt data", e);
        }
    }

    /**
     * Decrypts data using Android Keystore with AES-GCM.
     */
    private String decrypt(String encrypted) {
        // Handle legacy obfuscated data by removing it (force re-authentication)
        if (encrypted.startsWith("OBF:")) {
            Log.w(TAG, "Found legacy obfuscated data, removing (insecure)");
            return null;
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
            Log.e(TAG, "Decryption failed", e);
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

    /**
     * Retrieves an authentication token as a char[] for secure memory handling.
     * The caller is responsible for clearing the array after use with clearCharArray().
     */
    public char[] getTokenAsChars(String serverId) {
        String token = getToken(serverId);
        if (token == null) {
            return null;
        }
        char[] result = token.toCharArray();
        // Clear the String reference - note: String is immutable so this is limited
        // but the char[] can be explicitly cleared after use
        return result;
    }

    // ---------------- Per-device tokens ----------------
    // The enrollment/pairing token is stored under "token_<id>" (above). After a
    // successful pairing the server issues a per-device token, stored here under
    // "devtoken_<id>", and the enrollment token is discarded.

    /** Saves a per-device token (from char[]) and clears the source array. */
    public void saveDeviceTokenFromChars(String serverId, char[] token) {
        if (token == null || token.length == 0) {
            prefs.edit().remove("devtoken_" + serverId).apply();
            return;
        }
        try {
            saveEncrypted("devtoken_" + serverId, new String(token));
        } finally {
            clearCharArray(token);
        }
    }

    /** Retrieves the per-device token as char[], or null if none. */
    public char[] getDeviceTokenAsChars(String serverId) {
        String token = getEncrypted("devtoken_" + serverId);
        if (token == null) {
            return null;
        }
        return token.toCharArray();
    }

    public boolean hasDeviceToken(String serverId) {
        return prefs.contains("devtoken_" + serverId);
    }

    public void removeDeviceToken(String serverId) {
        prefs.edit().remove("devtoken_" + serverId).apply();
    }

    /** Stores the server-assigned device ID (not secret, but kept with the token). */
    public void saveDeviceId(String serverId, String deviceId) {
        saveEncrypted("devid_" + serverId, deviceId);
    }

    public String getDeviceId(String serverId) {
        return getEncrypted("devid_" + serverId);
    }

    public void removeDeviceId(String serverId) {
        prefs.edit().remove("devid_" + serverId).apply();
    }

    /**
     * Saves a token from a char[] and then clears the source array.
     */
    public void saveTokenFromChars(String serverId, char[] token) {
        if (token == null || token.length == 0) {
            prefs.edit().remove("token_" + serverId).apply();
            return;
        }
        try {
            String tokenStr = new String(token);
            saveToken(serverId, tokenStr);
        } finally {
            clearCharArray(token);
        }
    }

    /**
     * Securely clears a char array by overwriting with zeros.
     * Call this immediately after using token data.
     */
    public static void clearCharArray(char[] array) {
        if (array != null) {
            Arrays.fill(array, '\0');
        }
    }

    /**
     * Securely clears a byte array by overwriting with zeros.
     */
    public static void clearByteArray(byte[] array) {
        if (array != null) {
            Arrays.fill(array, (byte) 0);
        }
    }

    /**
     * Encrypts and stores arbitrary data (for fingerprints, server configs, etc.)
     */
    public void saveEncrypted(String key, String value) {
        if (value == null || value.isEmpty()) {
            prefs.edit().remove(key).apply();
            return;
        }
        String encrypted = encrypt(value);
        prefs.edit().putString(key, encrypted).apply();
    }

    /**
     * Retrieves and decrypts arbitrary data.
     */
    public String getEncrypted(String key) {
        String encrypted = prefs.getString(key, null);
        if (encrypted == null) {
            return null;
        }
        return decrypt(encrypted);
    }

    /**
     * Removes encrypted data by key.
     */
    public void removeEncrypted(String key) {
        prefs.edit().remove(key).apply();
    }
}
