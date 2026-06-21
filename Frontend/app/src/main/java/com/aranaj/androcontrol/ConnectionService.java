package com.aranaj.androcontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * Foreground service that keeps an active remote-control session alive while the
 * app is backgrounded or the screen is off. It shows an ongoing notification
 * (with a Disconnect action) and holds a partial wake lock so the connection
 * threads keep running and the process isn't reaped.
 *
 * The socket/protocol themselves are owned by {@link MainActivity}; this service
 * exists to keep that process foreground-priority and the CPU awake.
 */
public class ConnectionService extends Service {
    private static final String TAG = "ConnectionService";
    public static final String CHANNEL_ID = "androcontrol_connection";
    public static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START = "com.aranaj.androcontrol.action.START_CONNECTION";
    public static final String ACTION_STOP = "com.aranaj.androcontrol.action.STOP_CONNECTION";
    public static final String ACTION_DISCONNECT_REQUEST = "com.aranaj.androcontrol.action.DISCONNECT_REQUEST";
    public static final String EXTRA_SERVER_NAME = "server_name";

    private PowerManager.WakeLock wakeLock;

    /** Starts the foreground service for an active connection to {@code serverName}. */
    public static void start(Context ctx, String serverName) {
        Intent i = new Intent(ctx, ConnectionService.class);
        i.setAction(ACTION_START);
        i.putExtra(EXTRA_SERVER_NAME, serverName);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    /** Stops the foreground service (connection ended). Safe to call from the background. */
    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, ConnectionService.class));
        } catch (Exception ignored) {
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            releaseWakeLock();
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        String serverName = intent != null ? intent.getStringExtra(EXTRA_SERVER_NAME) : null;
        if (serverName == null || serverName.isEmpty()) {
            serverName = getString(R.string.app_name);
        }

        createChannel();
        Notification notification = buildNotification(serverName);

        int type = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type);
        } catch (Exception e) {
            Log.e(TAG, "startForeground failed", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        acquireWakeLock();
        // Don't auto-restart: the connection lives in the Activity, so a lone
        // service restart would be useless.
        return START_NOT_STICKY;
    }

    private Notification buildNotification(String serverName) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent disconnectIntent = new Intent(ACTION_DISCONNECT_REQUEST).setPackage(getPackageName());
        PendingIntent disconnectPi = PendingIntent.getBroadcast(this, 1, disconnectIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_connected_title))
                .setContentText(getString(R.string.notif_connected_text, serverName))
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setOngoing(true)
                .setShowWhen(false)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openPi)
                .addAction(0, getString(R.string.notif_disconnect), disconnectPi)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.notif_channel_name),
                        NotificationManager.IMPORTANCE_LOW);
                ch.setDescription(getString(R.string.notif_channel_desc));
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            return;
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndroControl::ConnectionWakeLock");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
            } catch (Exception ignored) {
            }
        }
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
