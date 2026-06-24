package com.aranaj.androcontrol;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/**
 * Central store for user-configurable app settings, backed by SharedPreferences.
 *
 * Covers:
 *  - Theme mode (light / dark / follow system)
 *  - Scroll bar position beside the touchpad (off / left / right / both)
 *  - Scroll bar width
 */
public class SettingsManager {
    private static final String PREFS_NAME = "AndroControlSettings";

    // Theme
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    public static final String THEME_SYSTEM = "system";
    private static final String KEY_THEME = "theme_mode";

    // Scroll bar position
    public static final String SCROLLBAR_OFF = "off";
    public static final String SCROLLBAR_LEFT = "left";
    public static final String SCROLLBAR_RIGHT = "right";
    public static final String SCROLLBAR_BOTH = "both";
    private static final String KEY_SCROLLBAR_POS = "scrollbar_position";

    // Scroll bar width (dp)
    private static final String KEY_SCROLLBAR_WIDTH = "scrollbar_width_dp";
    public static final int SCROLLBAR_WIDTH_MIN = 16;
    public static final int SCROLLBAR_WIDTH_MAX = 64;
    public static final int SCROLLBAR_WIDTH_DEFAULT = 28;

    // Scroll direction
    private static final String KEY_SCROLL_INVERTED = "scroll_inverted";

    // Stable per-install device identifier (used so re-pairing doesn't create
    // duplicate server-side device records). App-global; never tied to a server.
    private static final String KEY_CLIENT_DEVICE_ID = "client_device_id";

    // Pointer update rate (Hz): how often pointer moves are sent. Discrete options,
    // capped at 125 Hz — higher is pointless over Wi-Fi and only risks congestion.
    private static final String KEY_MOVEMENT_RATE = "movement_rate_hz";
    public static final int[] MOVEMENT_RATE_HZ = {60, 80, 100, 125};
    public static final int MOVEMENT_RATE_DEFAULT = 60;

    // Haptic feedback intensity (0 = off .. 100 = strongest)
    private static final String KEY_HAPTIC_INTENSITY = "haptic_intensity";
    public static final int HAPTIC_INTENSITY_MIN = 0;
    public static final int HAPTIC_INTENSITY_MAX = 100;
    public static final int HAPTIC_INTENSITY_DEFAULT = 60;

    private final SharedPreferences prefs;

    public SettingsManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // ---------------- Theme ----------------

    public String getThemeMode() {
        return prefs.getString(KEY_THEME, THEME_SYSTEM);
    }

    public void setThemeMode(String mode) {
        prefs.edit().putString(KEY_THEME, mode).apply();
    }

    /** Maps the stored theme mode to an {@link AppCompatDelegate} night-mode constant. */
    public int getNightModeFlag() {
        switch (getThemeMode()) {
            case THEME_LIGHT:
                return AppCompatDelegate.MODE_NIGHT_NO;
            case THEME_DARK:
                return AppCompatDelegate.MODE_NIGHT_YES;
            case THEME_SYSTEM:
            default:
                return AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        }
    }

    /** Reads the saved theme and applies it process-wide. */
    public void applyTheme() {
        AppCompatDelegate.setDefaultNightMode(getNightModeFlag());
    }

    // ---------------- Client device identity ----------------

    /**
     * Returns this installation's stable device id, generating and persisting one
     * on first use. It survives deleting/re-adding servers, so the server can
     * recognise a re-pairing device and avoid creating duplicate records.
     */
    public String getClientDeviceId() {
        String id = prefs.getString(KEY_CLIENT_DEVICE_ID, null);
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
            prefs.edit().putString(KEY_CLIENT_DEVICE_ID, id).apply();
        }
        return id;
    }

    // ---------------- Scroll bar position ----------------

    public String getScrollbarPosition() {
        return prefs.getString(KEY_SCROLLBAR_POS, SCROLLBAR_RIGHT);
    }

    public void setScrollbarPosition(String position) {
        prefs.edit().putString(KEY_SCROLLBAR_POS, position).apply();
    }

    public boolean isScrollbarOnLeft() {
        String p = getScrollbarPosition();
        return SCROLLBAR_LEFT.equals(p) || SCROLLBAR_BOTH.equals(p);
    }

    public boolean isScrollbarOnRight() {
        String p = getScrollbarPosition();
        return SCROLLBAR_RIGHT.equals(p) || SCROLLBAR_BOTH.equals(p);
    }

    // ---------------- Scroll bar width ----------------

    public int getScrollbarWidthDp() {
        int w = prefs.getInt(KEY_SCROLLBAR_WIDTH, SCROLLBAR_WIDTH_DEFAULT);
        return Math.max(SCROLLBAR_WIDTH_MIN, Math.min(SCROLLBAR_WIDTH_MAX, w));
    }

    public void setScrollbarWidthDp(int widthDp) {
        prefs.edit().putInt(KEY_SCROLLBAR_WIDTH, widthDp).apply();
    }

    // ---------------- Scroll direction ----------------

    /** @return true if scrolling should be inverted relative to finger drag direction. */
    public boolean isScrollInverted() {
        return prefs.getBoolean(KEY_SCROLL_INVERTED, false);
    }

    public void setScrollInverted(boolean inverted) {
        prefs.edit().putBoolean(KEY_SCROLL_INVERTED, inverted).apply();
    }

    /** Multiplier (+1 natural, -1 inverted) to apply to scroll deltas. */
    public int getScrollDirectionFactor() {
        return isScrollInverted() ? -1 : 1;
    }

    // ---------------- Haptic intensity ----------------

    /** @return haptic intensity as a percentage 0..100 (0 disables vibration). */
    public int getHapticIntensity() {
        int v = prefs.getInt(KEY_HAPTIC_INTENSITY, HAPTIC_INTENSITY_DEFAULT);
        return Math.max(HAPTIC_INTENSITY_MIN, Math.min(HAPTIC_INTENSITY_MAX, v));
    }

    public void setHapticIntensity(int intensity) {
        prefs.edit().putInt(KEY_HAPTIC_INTENSITY, intensity).apply();
    }

    /**
     * Maps the stored intensity percentage to a {@link android.os.VibrationEffect}
     * amplitude (1..255). Returns 0 when haptics are disabled.
     */
    public int getHapticAmplitude() {
        int pct = getHapticIntensity();
        if (pct <= 0) return 0;
        return Math.max(1, Math.round(pct / 100f * 255f));
    }

    // ---------------- Pointer update rate ----------------

    /** @return the selected pointer update rate in Hz (one of {@link #MOVEMENT_RATE_HZ}). */
    public int getMovementRateHz() {
        int hz = prefs.getInt(KEY_MOVEMENT_RATE, MOVEMENT_RATE_DEFAULT);
        for (int option : MOVEMENT_RATE_HZ) {
            if (option == hz) return hz;
        }
        return MOVEMENT_RATE_DEFAULT;
    }

    public void setMovementRateHz(int hz) {
        prefs.edit().putInt(KEY_MOVEMENT_RATE, hz).apply();
    }

    /**
     * @return the move-coalescing window in milliseconds (the period of the selected
     * update rate). Used by the touch handler's send gate.
     */
    public int getMovementBufferMs() {
        return Math.round(1000f / getMovementRateHz());
    }

    // ---------------- Clipboard sync ----------------

    private static final String KEY_CLIPBOARD_SYNC = "clipboard_sync";

    /** @return true if bidirectional clipboard sync with the desktop is enabled. */
    public boolean isClipboardSyncEnabled() {
        return prefs.getBoolean(KEY_CLIPBOARD_SYNC, false);
    }

    public void setClipboardSyncEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_CLIPBOARD_SYNC, enabled).apply();
    }

    // ---------------- File transfer ----------------

    private static final String KEY_FILE_TRANSFER = "file_transfer";

    /** @return true if file send/receive with the desktop is enabled. */
    public boolean isFileTransferEnabled() {
        return prefs.getBoolean(KEY_FILE_TRANSFER, false);
    }

    public void setFileTransferEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FILE_TRANSFER, enabled).apply();
    }
}
