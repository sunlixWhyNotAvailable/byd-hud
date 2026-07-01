package com.bydhud.app;

//supervises runtime startup so boot, activity, and watchdog paths converge on one recovery policy.

import android.content.Context;
import android.os.SystemClock;

//defines the HudRuntimeSupervisor module boundary so related behavior stays readable inside one unit.
final class HudRuntimeSupervisor {
    private static final long PACKAGE_REPLACE_RESTART_DELAY_MS = 1_500L;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private HudRuntimeSupervisor() {
    }

    //starts or schedules work here so lifecycle recovery follows one controlled path.
    static void ensureStarted(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        String safeReason = safe(reason);
        if (HudPrefs.isUserShutdownActive(appContext)) {
            HudRuntimeWatchdog.cancel(appContext);
            HudRuntimeState.recordLifecycleHook(appContext, "supervisor-shutdown-active", safeReason);
            AppEventLogger.event(appContext,
                    "runtime_supervisor shutdown_active reason=" + safeReason);
            return;
        }
        if (!HudPrefs.isBootEnabled(appContext)) {
            HudRuntimeWatchdog.cancel(appContext);
            HudRuntimeState.recordLifecycleHook(appContext, "supervisor-disabled", safeReason);
            AppEventLogger.event(appContext,
                    "runtime_supervisor disabled reason=" + safeReason);
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (!HudRuntimeState.isAlive(appContext, now)) {
            try {
                HudRuntimeState.recordLifecycleHook(appContext, "supervisor-start", safeReason);
                HudRuntimeService.startPersistent(appContext, "supervisor:" + safeReason);
                AppEventLogger.event(appContext, "runtime_supervisor start_service reason="
                        + safeReason + " state=" + HudRuntimeState.summary(appContext, now));
            } catch (RuntimeException e) {
                HudRuntimeState.recordLifecycleHook(appContext, "supervisor-start-failed",
                        safeReason);
                AppEventLogger.event(appContext, "runtime_supervisor start_failed reason="
                        + safeReason + " error=" + e.getClass().getSimpleName()
                        + ":" + safe(e.getMessage()));
            }
        } else {
            HudRuntimeState.recordLifecycleHook(appContext, "supervisor-alive", safeReason);
            AppEventLogger.event(appContext, "runtime_supervisor alive reason="
                    + safeReason + " state=" + HudRuntimeState.summary(appContext, now));
        }
        HudRuntimeWatchdog.schedule(appContext, "supervisor:" + safeReason);
        NavRuntimePermissionRepair.checkAndRepairAsync(
                appContext,
                "supervisor:" + safeReason,
                true,
                LocalAdbBridge.AuthorizationPromptMode.AUTO_ONCE);
    }

    //hard-resets post-update runtime state so stale service/capture binders cannot survive install replace.
    static void hardResetAfterPackageReplace(Context context, String reason) {
        Context appContext = context.getApplicationContext();
        String safeReason = safe(reason);
        if (HudPrefs.isUserShutdownActive(appContext)) {
            HudRuntimeWatchdog.cancel(appContext);
            AppEventLogger.event(appContext, "runtime_supervisor package_replace_hard_reset_skipped shutdown_active reason="
                    + safeReason);
            return;
        }
        AppEventLogger.event(appContext, "runtime_supervisor package_replace_hard_reset_start reason="
                + safeReason);
        try {
            NavHudLiveSender.get(appContext).stop("", "package-replace-hard-reset", true);
            WazeCropCapture.get(appContext).stop("package-replace-hard-reset");
            WazeMediaProjectionController.resetForRuntimeReinit(
                    appContext, "package-replace-hard-reset:" + safeReason);
            appContext.stopService(new android.content.Intent(appContext, HudRuntimeService.class));
            HudPrefs.setRuntimeServiceRunning(appContext, false);
            HudRuntimeState.markStopped(appContext, "package-replace-hard-reset:" + safeReason);
            HudRuntimeWatchdog.scheduleSoon(
                    appContext, "package-replace-hard-reset", PACKAGE_REPLACE_RESTART_DELAY_MS);
            AppEventLogger.event(appContext, "runtime_supervisor package_replace_hard_reset_exit reason="
                    + safeReason + " restartDelayMs=" + PACKAGE_REPLACE_RESTART_DELAY_MS);
        } finally {
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
