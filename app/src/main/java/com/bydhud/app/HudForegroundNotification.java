package com.bydhud.app;

import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;

import java.util.LinkedHashMap;
import java.util.Map;

/** The three foreground services share one entry and the same primary action. */
final class HudForegroundNotification {
    static final String CHANNEL_ID = "byd_hud_runtime";
    private static final int ID = 4302;
    private static final Map<Service, String> owners = new LinkedHashMap<>();
    private static int surfaceDisplay = -1;
    private static String lastPublishedState;

    private HudForegroundNotification() {}

    static synchronized void start(Service service, String state, int type) {
        String previous = owners.put(service, state);
        try {
            service.startForeground(ID, build(service), type);
        } catch (RuntimeException error) {
            if (previous == null) owners.remove(service);
            else owners.put(service, previous);
            throw error;
        }
        AppEventLogger.event(service, "foreground_notification acquire owner="
                + service.getClass().getSimpleName() + " owners=" + owners.size());
    }

    static synchronized void update(Service service, String state) {
        // A late projection callback must not resurrect a stopped service's notification.
        if (!owners.containsKey(service)) return;
        if (state != null) owners.put(service, state);
        publish(service);
    }

    static synchronized void setSurfaceDisplay(int displayId) {
        if (surfaceDisplay == displayId) return;
        surfaceDisplay = displayId;
        if (!owners.isEmpty()) publish(owners.keySet().iterator().next());
    }

    static synchronized void stop(Service service) {
        if (owners.remove(service) == null) return;
        // Android retains this ID while another foreground service in our package uses it.
        service.stopForeground(Service.STOP_FOREGROUND_REMOVE);
        AppEventLogger.event(service, "foreground_notification release owner="
                + service.getClass().getSimpleName() + " owners=" + owners.size());
        if (!owners.isEmpty()) publish(service);
        else lastPublishedState = null;
    }

    static void ensureChannel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "BYD HUD", NotificationManager.IMPORTANCE_LOW));
        }
    }

    private static void publish(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(ID, build(context));
    }

    private static Notification build(Context context) {
        ensureChannel(context);
        String language = HudPrefs.uiLanguage(context);
        PendingIntent open;
        String text;
        if (surfaceDisplay >= 0) {
            Intent surface = new Intent(context, WazeSurfaceActivity.class)
                    .setAction(WazeSurfaceActivity.ACTION_OPEN)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(surfaceDisplay);
            // Display-specific identities avoid cancelling an action the OEM UI still holds.
            open = PendingIntent.getActivity(context, 4302 + surfaceDisplay, surface,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT,
                    options.toBundle());
            text = choose(language, "Waze Surface · натисніть, щоб повернутися",
                    "Waze Surface · tap to return", "Waze Surface · нажмите, чтобы вернуться");
        } else {
            Intent hud = new Intent(context, MainActivity.class);
            open = PendingIntent.getActivity(context, 0, hud,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT,
                    MainActivityDisplayGuard.tabletOptions());
            if (owners.containsValue("Runtime active")) {
                text = choose(language, "BYD HUD працює", "BYD HUD is running",
                        "BYD HUD работает");
            } else if (owners.values().stream().anyMatch(value -> value.startsWith("Projecting "))) {
                text = choose(language, "Навігатор на приборці", "Navigator on the dashboard",
                        "Навигатор на приборке");
            } else if (owners.containsValue("widget")) {
                text = choose(language, "Віджет приборки увімкнено", "Dashboard widget enabled",
                        "Виджет приборки включён");
            } else {
                text = choose(language, "Готовий до роботи", "Ready", "Готов к работе");
            }
            text += choose(language, " · натисніть, щоб відкрити BYD HUD",
                    " · tap to open BYD HUD", " · нажмите, чтобы открыть BYD HUD");
        }
        String publishedState = surfaceDisplay + ":" + text;
        if (!publishedState.equals(lastPublishedState)) {
            lastPublishedState = publishedState;
            AppEventLogger.event(context, "foreground_notification target="
                    + (surfaceDisplay >= 0 ? "surface display=" + surfaceDisplay : "hud")
                    + " owners=" + owners.size() + " text=" + text);
        }
        return new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_hud_notification)
                .setContentTitle("BYD HUD")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private static String choose(String language, String uk, String en, String ru) {
        return "uk".equals(language) ? uk : "ru".equals(language) ? ru : en;
    }
}
