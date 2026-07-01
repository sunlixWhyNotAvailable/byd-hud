package com.bydhud.app;

//restarts the persistent runtime after Android boot so HUD output recovers without opening the app.

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

//anchors the BootReceiver android entry point so lifecycle recovery stays separate from business logic.
public final class BootReceiver extends BroadcastReceiver {
    private static final String ACTION_QUICKBOOT_POWERON =
            "android.intent.action.QUICKBOOT_POWERON";

    @Override
    //handles broadcast recovery here so the app can restart required services without user interaction.
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : intent.getAction();
        AppEventLogger.event(context, "boot_receiver action=" + action
                + " boot=" + HudPrefs.isBootEnabled(context)
                + " runtimeRunning=" + HudPrefs.isRuntimeServiceRunning(context)
                + " shutdown=" + HudPrefs.isUserShutdownActive(context));
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            if (!HudRuntimeUpgradeGuard.markPackageReplaced(context, "receiver")) {
                AppEventLogger.event(context, "boot_receiver package_replace_mark_failed action=" + action);
                return;
            }
            if (HudPrefs.isUserShutdownActive(context)) {
                HudRuntimeWatchdog.cancel(context);
                AppEventLogger.event(context, "boot_receiver shutdown_active action=" + action);
                return;
            }
            if (!HudPrefs.isBootEnabled(context)) {
                HudRuntimeWatchdog.cancel(context);
                return;
            }
            HudRuntimeSupervisor.hardResetAfterPackageReplace(context, "receiver");
            return;
        } else {
            HudRuntimeUpgradeGuard.recordVersionStart(context, "receiver:" + action);
        }
        if (!isRuntimeRecoveryAction(action)) {
            AppEventLogger.event(context, "boot_receiver ignored action=" + action);
            return;
        }
        if (HudPrefs.isUserShutdownActive(context)) {
            HudRuntimeWatchdog.cancel(context);
            AppEventLogger.event(context, "boot_receiver shutdown_active action=" + action);
            return;
        }
        if (!HudPrefs.isBootEnabled(context)) {
            HudRuntimeWatchdog.cancel(context);
            return;
        }
        HudRuntimeSupervisor.ensureStarted(context, "receiver:" + action);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isRuntimeRecoveryAction(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || ACTION_QUICKBOOT_POWERON.equals(action)
                || Intent.ACTION_USER_PRESENT.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
    }
}
