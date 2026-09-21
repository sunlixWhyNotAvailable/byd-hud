package com.bydhud.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/** Foreground lifetime for a manually requested Shanghai test, independent of autostart. */
public final class ShanghaiTestService extends Service {
    private static final String CHANNEL = "byd_hud_shanghai";
    private static final int NOTIFICATION_ID = 4311;
    private static final String SESSION = "session";
    private static final String RESET = "reset";
    private String activeSessionId = "";

    static void launch(Context context, String sessionId, boolean reset) {
        context.startForegroundService(new Intent(context, ShanghaiTestService.class)
                .putExtra(SESSION, sessionId).putExtra(RESET, reset));
    }

    static void finish(Context context) {
        context.stopService(new Intent(context, ShanghaiTestService.class));
    }

    @Override public void onCreate() {
        super.onCreate();
        String language = HudPrefs.uiLanguage(this);
        String title = "uk".equals(language) ? "Тест у Шанхаї"
                : "ru".equals(language) ? "Тест в Шанхае" : "Shanghai test";
        String detail = "uk".equals(language) ? "Керування тестом у BYD HUD"
                : "ru".equals(language) ? "Управление тестом в BYD HUD" : "Manage the test in BYD HUD";
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, title, NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION_ID,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        startForeground(NOTIFICATION_ID, new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_hud_notification).setContentTitle(title)
                .setContentText(detail).setContentIntent(open).setOngoing(true).build());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            ShanghaiTestController.get(this).recoverOwned("service-restart");
            stopSelf();
        } else {
            activeSessionId = intent.getStringExtra(SESSION);
            ShanghaiTestController.get(this).onServiceReady(
                    activeSessionId, intent.getBooleanExtra(RESET, false));
        }
        // An interrupted route is recovered, never automatically replayed.
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        ShanghaiTestController.get(this).onServiceDestroyed(activeSessionId);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
