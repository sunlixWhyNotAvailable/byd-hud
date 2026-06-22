package com.bydhud.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    private static final String ACTION_QUICKBOOT_POWERON =
            "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        AppEventLogger.event(context, "boot_receiver action=" + action
                + " boot=" + HudPrefs.isBootEnabled(context)
                + " runtimeRunning=" + HudPrefs.isRuntimeServiceRunning(context));
        if (!isRuntimeRecoveryAction(action)) {
            AppEventLogger.event(context, "boot_receiver ignored action=" + action);
            return;
        }
        if (!HudPrefs.isBootEnabled(context)) {
            HudRuntimeWatchdog.cancel(context);
            return;
        }
        HudRuntimeSupervisor.ensureStarted(context, "receiver:" + action);
    }

    private static boolean isRuntimeRecoveryAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT_POWERON.equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
    }
}
