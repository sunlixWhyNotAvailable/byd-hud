package com.bydhud.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public final class HudRuntimeService extends Service {
    private static final String TAG = "BydHudRuntimeService";
    private static final String CHANNEL_ID = "byd_hud_runtime";
    private static final int NOTIFICATION_ID = 4302;

    private static final String ACTION_START_PERSISTENT =
            "com.bydhud.app.action.START_PERSISTENT_RUNTIME";
    private static final String ACTION_STOP_PERSISTENT =
            "com.bydhud.app.action.STOP_PERSISTENT_RUNTIME";
    private static final String EXTRA_REASON = "reason";
    private static final long HEARTBEAT_INTERVAL_MS = 30_000L;

    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            HudRuntimeState.markHeartbeat(HudRuntimeService.this, "periodic");
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS);
        }
    };

    static void startPersistent(Context context, String reason) {
        Intent intent = new Intent(context, HudRuntimeService.class);
        intent.setAction(ACTION_START_PERSISTENT);
        intent.putExtra(EXTRA_REASON, reason);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void stopPersistent(Context context, String reason) {
        Intent intent = new Intent(context, HudRuntimeService.class);
        intent.setAction(ACTION_STOP_PERSISTENT);
        intent.putExtra(EXTRA_REASON, reason);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        HudGraphicPayload.setContext(this);
        startForeground(NOTIFICATION_ID, buildNotification("Runtime active"));
        HudPrefs.setRuntimeServiceRunning(this, true);
        HudRuntimeState.markStarted(this, "onCreate");
        scheduleHeartbeat();
        log("runtime foreground active version=" + BuildConfig.VERSION_NAME
                + "/" + BuildConfig.VERSION_CODE
                + " logDir=" + AppEventLogger.logDir(this).getAbsolutePath());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        String reason = intent == null ? "sticky-restart" : intent.getStringExtra(EXTRA_REASON);
        log("runtime onStartCommand action=" + action
                + " reason=" + reason
                + " boot=" + HudPrefs.isBootEnabled(this));
        if (ACTION_STOP_PERSISTENT.equals(action)) {
            HudRuntimeWatchdog.cancel(this);
            HudRuntimeState.markStopped(this, "stop:" + reason);
            stopForegroundCompat();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (!HudPrefs.isBootEnabled(this)) {
            HudRuntimeWatchdog.cancel(this);
            HudRuntimeState.markStopped(this, "boot-disabled:" + reason);
            stopForegroundCompat();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        HudPrefs.setRuntimeServiceRunning(this, true);
        HudRuntimeState.markHeartbeat(this, "onStartCommand:" + reason);
        scheduleHeartbeat();
        updateNotification("Runtime active");
        HudRuntimeWatchdog.schedule(this, "service-start");
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        log("runtime task removed boot=" + HudPrefs.isBootEnabled(this));
        HudRuntimeState.recordLifecycleHook(this, "task-removed",
                "boot=" + HudPrefs.isBootEnabled(this));
        if (HudPrefs.isBootEnabled(this)) {
            HudRuntimeWatchdog.schedule(this, "task-removed");
            startPersistent(this, "task-removed");
        } else {
            HudRuntimeWatchdog.cancel(this);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        HudPrefs.setRuntimeServiceRunning(this, false);
        HudRuntimeState.markStopped(this, "destroyed");
        log("runtime destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification(String text) {
        createNotificationChannel();
        Intent launchIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_hud_notification)
                .setContentTitle("BYD HUD")
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "BYD HUD runtime",
                NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    private void scheduleHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        heartbeatHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL_MS);
    }

    private void log(String line) {
        Log.i(TAG, line);
        AppEventLogger.event(this, "runtime " + line);
    }
}
