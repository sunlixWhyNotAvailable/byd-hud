package com.bydhud.app;

//schedules periodic recovery checks so DiLink sleep and task removal do not leave services stopped.

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

//defines the HudRuntimeWatchdog module boundary so related behavior stays readable inside one unit.
final class HudRuntimeWatchdog {
    static final String ACTION_RUNTIME_WATCHDOG =
            "com.bydhud.app.action.RUNTIME_WATCHDOG";

    private static final String TAG = "BydHudRuntimeWatchdog";
    private static final int REQUEST_CODE = 4303;
    private static final long WATCHDOG_INTERVAL_MS = 5L * 60L * 1000L;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private HudRuntimeWatchdog() {
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    static void schedule(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        if (!HudPrefs.isBootEnabled(appContext)) {
            cancel(appContext);
            return;
        }
        AlarmManager alarmManager =
                (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            Log.w(TAG, "schedule failed: no AlarmManager");
            return;
        }
        long triggerAt = SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS;
        alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerAt,
                pendingIntent(appContext));
        AppEventLogger.event(appContext, "runtime_watchdog scheduled reason=" + reason
                + " intervalMs=" + WATCHDOG_INTERVAL_MS);
    }

    //stops or releases work here so stale capture and HUD output cannot keep running silently.
    static void cancel(Context context) {
        Context appContext = context.getApplicationContext();
        AlarmManager alarmManager =
                (AlarmManager) appContext.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(pendingIntent(appContext));
        }
        AppEventLogger.event(appContext, "runtime_watchdog canceled");
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    private static PendingIntent pendingIntent(Context context) {
        Intent intent = new Intent(context, HudRuntimeWatchdogReceiver.class);
        intent.setAction(ACTION_RUNTIME_WATCHDOG);
        return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
