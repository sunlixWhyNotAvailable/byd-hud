package com.bydhud.app;

//receives watchdog alarms so runtime recovery works even when the activity is closed.

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

//anchors the HudRuntimeWatchdogReceiver android entry point so lifecycle recovery stays separate from business logic.
public final class HudRuntimeWatchdogReceiver extends BroadcastReceiver {
    @Override
    //handles broadcast recovery here so the app can restart required services without user interaction.
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        AppEventLogger.event(context, "runtime_watchdog_receiver action=" + action
                + " boot=" + HudPrefs.isBootEnabled(context)
                + " runtimeRunning=" + HudPrefs.isRuntimeServiceRunning(context)
                + " shutdown=" + HudPrefs.isUserShutdownActive(context));
        if (!HudRuntimeWatchdog.ACTION_RUNTIME_WATCHDOG.equals(action)) {
            AppEventLogger.event(context, "runtime_watchdog_receiver ignored action=" + action);
            return;
        }
        if (HudPrefs.isUserShutdownActive(context)) {
            HudRuntimeWatchdog.cancel(context);
            AppEventLogger.event(context, "runtime_watchdog_receiver shutdown_active");
            return;
        }
        if (!HudPrefs.isBootEnabled(context)) {
            HudRuntimeWatchdog.cancel(context);
            return;
        }
        HudRuntimeSupervisor.ensureStarted(context, "watchdog");
    }
}
