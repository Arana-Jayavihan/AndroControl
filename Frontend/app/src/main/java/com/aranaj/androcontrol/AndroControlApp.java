package com.aranaj.androcontrol;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

/**
 * Application entry point.
 *
 * Applies Material You dynamic color on Android 12+ (the theme adapts to the
 * user's wallpaper). On older devices this is a no-op and the app falls back to
 * the fixed "tech slate + cyan" brand palette defined in the theme.
 */
public class AndroControlApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
