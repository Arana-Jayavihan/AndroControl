package com.aranaj.androcontrol;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Invisible, momentary activity that reads or writes the system clipboard from the
 * background.
 *
 * Reading/writing the clipboard requires foreground focus on Android 10+. Launching an
 * Activity straight from a notification is an allowed foreground transition (and is
 * Android-12+ "trampoline"-compliant, since it's an Activity, not a service/broadcast).
 * This activity uses a transparent theme, does its one job on first window focus, then
 * finishes — so the user just sees a brief flash, if anything.
 */
public class ClipboardBridgeActivity extends AppCompatActivity {
    /** Set the local clipboard from {@link ClipboardBridge#takeIncoming()}. */
    public static final String ACTION_SET = "com.aranaj.androcontrol.action.CLIP_SET";
    /** Read the local clipboard and hand it to MainActivity to send to the desktop. */
    public static final String ACTION_SEND = "com.aranaj.androcontrol.action.CLIP_SEND_FROM_NOTIF";

    private boolean done = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || done) {
            return;
        }
        done = true;
        try {
            handle();
        } finally {
            finish();
            overridePendingTransition(0, 0);
        }
    }

    private void handle() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) {
            return;
        }
        String action = getIntent() != null ? getIntent().getAction() : null;

        if (ACTION_SET.equals(action)) {
            String text = ClipboardBridge.takeIncoming();
            if (text != null && !text.isEmpty()) {
                cm.setPrimaryClip(ClipData.newPlainText("AndroControl", text));
            }
        } else if (ACTION_SEND.equals(action)) {
            String text = readClipboard(cm);
            if (text != null && !text.isEmpty()) {
                ClipboardBridge.setOutgoing(text);
                sendBroadcast(new Intent(MainActivity.ACTION_CLIP_SEND).setPackage(getPackageName()));
            }
        }
    }

    private String readClipboard(ClipboardManager cm) {
        if (!cm.hasPrimaryClip()) {
            return null;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        CharSequence cs = clip.getItemAt(0).coerceToText(this);
        return cs == null ? null : cs.toString();
    }
}
