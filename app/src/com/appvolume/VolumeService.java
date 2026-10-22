package com.appvolume;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reports the active playback sessions to the SystemUI hook.
 *
 * <p>The hook lives in SystemUI, which holds MODIFY_AUDIO_ROUTING and can
 * attach an effect to another application's session. This service only scans
 * the players (using the DUMP permission) and broadcasts the exact
 * package/session mapping plus the current per-package volumes.
 */
public final class VolumeService extends Service {
    public static final String ACTION_REFRESH = "com.appvolume.action.REFRESH";
    public static final String ACTION_RESET_ALL = "com.appvolume.action.RESET_ALL";

    private static final String TAG = "AppVolume";
    private static final String CHANNEL_ID = "playback";
    private static final int NOTIFICATION_ID = 1;
    private static final long SCAN_INTERVAL_MS = 1500L;

    private static volatile VolumeService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    private ExecutorService executor;
    private Prefs prefs;
    private int lastNotificationCount = -1;
    private boolean destroyed;

    private final Runnable scanRunnable = this::scanNow;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = new Prefs(this);
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "app-volume-session-reporter");
            t.setDaemon(true);
            return t;
        });
        createChannel();
        startInForeground(0);
        schedule(0);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_RESET_ALL.equals(action)) {
            prefs.clear();
            clearHook();
            stopSelf();
            return START_NOT_STICKY;
        }
        requestRefresh();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        instance = null;
        handler.removeCallbacksAndMessages(null);
        clearHook();
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    public static void ensureRunning(Context context) {
        Intent intent = new Intent(context, VolumeService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Throwable t) {
            Log.w(TAG, "cannot start service", t);
        }
    }

    public static void requestRefresh() {
        VolumeService service = instance;
        if (service == null) {
            return;
        }
        service.handler.removeCallbacks(service.scanRunnable);
        service.handler.postDelayed(service.scanRunnable, 100L);
    }

    private void scanNow() {
        if (prefs == null || destroyed) {
            return;
        }
        if (!isFeatureEnabled()) {
            clearHook();
            stopSelf();
            return;
        }
        if (!scanning.compareAndSet(false, true)) {
            schedule(SCAN_INTERVAL_MS);
            return;
        }
        executor.execute(() -> {
            List<PlayerScanner.Player> players;
            try {
                players = PlayerScanner.scan(VolumeService.this);
            } catch (Throwable t) {
                Log.w(TAG, "session scan failed", t);
                players = java.util.Collections.emptyList();
            }
            final List<PlayerScanner.Player> result = players;
            handler.post(() -> {
                scanning.set(false);
                if (destroyed) {
                    return;
                }
                publish(result);
                schedule(SCAN_INTERVAL_MS);
            });
        });
    }

    private void publish(List<PlayerScanner.Player> players) {
        Map<String, Integer> volumes = prefs.all();

        Bundle volumeBundle = new Bundle();
        for (Map.Entry<String, Integer> entry : volumes.entrySet()) {
            volumeBundle.putInt(entry.getKey(), entry.getValue());
        }

        Bundle sessionBundle = new Bundle();
        int active = 0;
        for (PlayerScanner.Player player : players) {
            if (player.sessions.isEmpty()) {
                continue;
            }
            int[] ids = new int[player.sessions.size()];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = player.sessions.get(i);
            }
            sessionBundle.putIntArray(player.pkg, ids);
            active++;
        }

        Intent state = new Intent(Protocol.ACTION_SESSIONS)
                .setPackage(Protocol.SYSTEMUI)
                .putExtra(Protocol.EXTRA_SESSIONS, sessionBundle)
                .putExtra(Protocol.EXTRA_VOLUMES, volumeBundle);
        sendBroadcast(state);

        updateNotification(active);
    }

    private void clearHook() {
        Intent intent = new Intent(Protocol.ACTION_CLEAR).setPackage(Protocol.SYSTEMUI);
        sendBroadcast(intent);
    }

    /** The feature is switched on in the system settings, like crDroid does. */
    private boolean isFeatureEnabled() {
        try {
            return android.provider.Settings.System.getInt(
                    getContentResolver(), "show_app_volume", 0) == 1;
        } catch (Throwable t) {
            return true;
        }
    }

    private void schedule(long delayMs) {
        handler.removeCallbacks(scanRunnable);
        if (!destroyed) {
            handler.postDelayed(scanRunnable, delayMs);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notif_waiting));
        channel.setShowBadge(false);
        channel.setSound(null, null);
        nm.createNotificationChannel(channel);
    }

    private void startInForeground(int count) {
        Notification notification = buildNotification(count);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void updateNotification(int count) {
        if (count == lastNotificationCount) {
            return;
        }
        lastNotificationCount = count;
        NotificationManager nm = (NotificationManager) getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(count));
        }
    }

    private Notification buildNotification(int count) {
        Intent openIntent = new Intent(this, PanelActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent open = PendingIntent.getActivity(this, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent resetIntent = new Intent(this, VolumeService.class)
                .setAction(ACTION_RESET_ALL);
        PendingIntent reset = PendingIntent.getService(this, 1, resetIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.ic_stat_volume)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(count > 0
                        ? getString(R.string.notif_active, count)
                        : getString(R.string.notif_waiting))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_stat_volume),
                        getString(R.string.notif_reset),
                        reset).build());
        return builder.build();
    }
}
