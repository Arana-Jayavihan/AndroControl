package com.aranaj.androcontrol;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.util.Log;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Share-sheet target ("Send via AndroControl"). Receives shared files, copies them into
 * the app cache (we hold the share's read grant here), then hands the cache paths to
 * {@link MainActivity} which streams them to the desktop over the data channel. Reading
 * here — rather than in MainActivity — avoids URI-permission hand-off problems.
 */
public class ShareReceiverActivity extends AppCompatActivity {
    private static final String TAG = "ShareReceiver";
    public static final String ACTION_FILE_SEND = "com.aranaj.androcontrol.action.FILE_SEND";
    public static final String EXTRA_PATHS = "paths";
    public static final String EXTRA_NAMES = "names";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!MainActivity.isFileTransferReady()) {
            Toast.makeText(this, R.string.share_not_connected, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        final List<Uri> uris = extractUris(getIntent());
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.share_nothing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        Toast.makeText(this, getResources().getQuantityString(
                R.plurals.share_sending, uris.size(), uris.size()), Toast.LENGTH_SHORT).show();

        // Copy to cache off the main thread, then broadcast the paths to MainActivity.
        new Thread(() -> {
            ArrayList<String> paths = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            int skipped = 0;
            File outDir = new File(getCacheDir(), "outgoing");
            outDir.mkdirs();
            for (Uri uri : uris) {
                long size = fileSize(uri);
                if (size > DataChannel.MAX_FILE_BYTES) {
                    skipped++;
                    continue;
                }
                String name = displayName(uri);
                File dest = new File(outDir, name);
                if (copyToCache(uri, dest)) {
                    paths.add(dest.getAbsolutePath());
                    names.add(name);
                }
            }
            if (!paths.isEmpty()) {
                Intent i = new Intent(ACTION_FILE_SEND).setPackage(getPackageName());
                i.putStringArrayListExtra(EXTRA_PATHS, paths);
                i.putStringArrayListExtra(EXTRA_NAMES, names);
                sendBroadcast(i);
            }
            if (skipped > 0) {
                runOnUiThread(() -> Toast.makeText(this,
                        getString(R.string.share_too_large, skipped), Toast.LENGTH_LONG).show());
            }
        }, "ShareCopy").start();

        finish();
    }

    private List<Uri> extractUris(Intent intent) {
        List<Uri> out = new ArrayList<>();
        if (intent == null) return out;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri u = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) out.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) out.addAll(list);
        }
        return out;
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.isEmpty()) {
            name = "file";
        }
        return new File(name).getName(); // strip any path components
    }

    private long fileSize(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.SIZE);
                if (idx >= 0 && !c.isNull(idx)) return c.getLong(idx);
            }
        } catch (Exception ignored) {
        }
        return -1; // unknown — allow; DataChannel.enqueueSend is the backstop
    }

    private boolean copyToCache(Uri uri, File dest) {
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) return false;
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "copy to cache failed", e);
            return false;
        }
    }
}
