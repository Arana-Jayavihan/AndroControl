package com.aranaj.androcontrol;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/**
 * Application entry point.
 *
 * Applies Material You dynamic color on Android 12+ (the theme adapts to the
 * user's wallpaper). On older devices this is a no-op and the app falls back to
 * the fixed "tech slate + cyan" brand palette defined in the theme.
 *
 * Also restores the user's saved theme mode (light / dark / follow system)
 * process-wide before any activity is created.
 */
public class AndroControlApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Restore saved light/dark/system preference before the first activity inflates.
        new SettingsManager(this).applyTheme();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
