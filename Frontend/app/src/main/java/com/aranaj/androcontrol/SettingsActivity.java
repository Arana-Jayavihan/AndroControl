package com.aranaj.androcontrol;

import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.slider.Slider;

/**
 * Settings screen: theme mode, scroll behaviour (position / direction / width)
 * and haptic intensity. The scroll section shows a live preview that mirrors the
 * touchpad's edge scroll zones, and the haptic slider previews intensity on change.
 */
public class SettingsActivity extends AppCompatActivity {

    private SettingsManager settings;
    private Vibrator vibrator;

    private View previewLeft;
    private View previewRight;
    private TextView widthValue;
    private TextView hapticValue;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        View root = findViewById(R.id.settingsRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });

        settings = new SettingsManager(this);
        vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);

        MaterialToolbar toolbar = findViewById(R.id.settingsToolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        previewLeft = findViewById(R.id.previewScrollLeft);
        previewRight = findViewById(R.id.previewScrollRight);
        widthValue = findViewById(R.id.widthValue);
        hapticValue = findViewById(R.id.hapticValue);

        setupThemeControls();
        setupScrollbarControls();
        setupHapticControls();
        setupDeviceInfo();
        refreshPreview();
    }

    private void setupDeviceInfo() {
        TextView nameValue = findViewById(R.id.deviceNameValue);
        TextView idValue = findViewById(R.id.deviceIdValue);

        String name = android.os.Build.MODEL;
        if (name == null || name.trim().isEmpty()) {
            name = android.os.Build.MANUFACTURER;
        }
        nameValue.setText(name);
        idValue.setText(settings.getClientDeviceId());
    }

    private void setupThemeControls() {
        MaterialButtonToggleGroup group = findViewById(R.id.themeToggleGroup);

        switch (settings.getThemeMode()) {
            case SettingsManager.THEME_LIGHT:
                group.check(R.id.themeLight);
                break;
            case SettingsManager.THEME_DARK:
                group.check(R.id.themeDark);
                break;
            default:
                group.check(R.id.themeSystem);
                break;
        }

        group.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            String mode;
            if (checkedId == R.id.themeLight) {
                mode = SettingsManager.THEME_LIGHT;
            } else if (checkedId == R.id.themeDark) {
                mode = SettingsManager.THEME_DARK;
            } else {
                mode = SettingsManager.THEME_SYSTEM;
            }
            if (!mode.equals(settings.getThemeMode())) {
                settings.setThemeMode(mode);
                // Applies process-wide and recreates started activities (incl. this one).
                settings.applyTheme();
            }
        });
    }

    private void setupScrollbarControls() {
        MaterialButtonToggleGroup posGroup = findViewById(R.id.scrollbarPositionGroup);

        switch (settings.getScrollbarPosition()) {
            case SettingsManager.SCROLLBAR_OFF:
                posGroup.check(R.id.posOff);
                break;
            case SettingsManager.SCROLLBAR_LEFT:
                posGroup.check(R.id.posLeft);
                break;
            case SettingsManager.SCROLLBAR_BOTH:
                posGroup.check(R.id.posBoth);
                break;
            case SettingsManager.SCROLLBAR_RIGHT:
            default:
                posGroup.check(R.id.posRight);
                break;
        }

        posGroup.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            String pos;
            if (checkedId == R.id.posOff) {
                pos = SettingsManager.SCROLLBAR_OFF;
            } else if (checkedId == R.id.posLeft) {
                pos = SettingsManager.SCROLLBAR_LEFT;
            } else if (checkedId == R.id.posBoth) {
                pos = SettingsManager.SCROLLBAR_BOTH;
            } else {
                pos = SettingsManager.SCROLLBAR_RIGHT;
            }
            settings.setScrollbarPosition(pos);
            refreshPreview();
        });

        // Direction
        MaterialButtonToggleGroup dirGroup = findViewById(R.id.scrollDirectionGroup);
        dirGroup.check(settings.isScrollInverted() ? R.id.dirInverted : R.id.dirNatural);
        dirGroup.addOnButtonCheckedListener((g, checkedId, isChecked) -> {
            if (!isChecked) return;
            settings.setScrollInverted(checkedId == R.id.dirInverted);
        });

        // Width
        Slider slider = findViewById(R.id.widthSlider);
        slider.setValueFrom(SettingsManager.SCROLLBAR_WIDTH_MIN);
        slider.setValueTo(SettingsManager.SCROLLBAR_WIDTH_MAX);
        slider.setValue(settings.getScrollbarWidthDp());

        slider.addOnChangeListener((s, value, fromUser) -> {
            settings.setScrollbarWidthDp((int) value);
            refreshPreview();
        });
    }

    private void setupHapticControls() {
        Slider slider = findViewById(R.id.hapticSlider);
        slider.setValueFrom(SettingsManager.HAPTIC_INTENSITY_MIN);
        slider.setValueTo(SettingsManager.HAPTIC_INTENSITY_MAX);
        slider.setValue(settings.getHapticIntensity());
        updateHapticLabel();

        slider.addOnChangeListener((s, value, fromUser) -> {
            settings.setHapticIntensity((int) value);
            updateHapticLabel();
            if (fromUser) {
                previewHaptic();
            }
        });
    }

    private void updateHapticLabel() {
        int pct = settings.getHapticIntensity();
        if (pct <= 0) {
            hapticValue.setText(R.string.settings_haptic_off);
        } else {
            hapticValue.setText(getString(R.string.settings_haptic_value, pct));
        }
    }

    /** Plays a short vibration at the currently selected intensity, as a preview. */
    private void previewHaptic() {
        if (vibrator == null || !vibrator.hasVibrator()) return;
        int amplitude = settings.getHapticAmplitude();
        if (amplitude <= 0) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                int amp = vibrator.hasAmplitudeControl() ? amplitude : VibrationEffect.DEFAULT_AMPLITUDE;
                vibrator.vibrate(VibrationEffect.createOneShot(40, amp));
            } else {
                vibrator.vibrate(40);
            }
        } catch (Exception ignored) {
            // Best-effort preview; ignore failures.
        }
    }

    /** Updates the live preview zones to match the current position and width settings. */
    private void refreshPreview() {
        int widthPx = dpToPx(settings.getScrollbarWidthDp());
        widthValue.setText(getString(R.string.settings_scrollbar_width_value, settings.getScrollbarWidthDp()));

        applyPreviewZone(previewLeft, settings.isScrollbarOnLeft(), widthPx);
        applyPreviewZone(previewRight, settings.isScrollbarOnRight(), widthPx);
    }

    private void applyPreviewZone(View zone, boolean visible, int widthPx) {
        zone.setVisibility(visible ? View.VISIBLE : View.GONE);
        ViewGroup.LayoutParams lp = zone.getLayoutParams();
        lp.width = widthPx;
        zone.setLayoutParams(lp);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
