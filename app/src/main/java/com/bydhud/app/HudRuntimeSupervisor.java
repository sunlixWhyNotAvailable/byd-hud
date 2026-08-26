package com.bydhud.app;

//supervises runtime startup so boot, activity, and watchdog paths converge on one recovery policy.

import android.content.Context;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicBoolean;

//defines the HudRuntimeSupervisor module boundary so related behavior stays readable inside one unit.
final class HudRuntimeSupervisor {
    private static final long PACKAGE_REPLACE_RESTART_DELAY_MS = 1_500L;
    private static final AtomicBoolean PACKAGE_REPLACE_RESET_IN_FLIGHT =
            new AtomicBoolean(false);

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
        boolean hardResetPending = HudRuntimeUpgradeGuard.hasPendingHardReset(appContext);
        if (hardResetPending || !HudRuntimeState.isAlive(appContext, now)) {
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
        if (hasActiveRuntimeWork(appContext)) {
            HudRuntimeWatchdog.schedule(appContext, "supervisor:" + safeReason);
            NavRuntimePermissionRepair.checkAndRepairAsync(
                    appContext,
                    "supervisor:" + safeReason,
                    true,
                    LocalAdbBridge.AuthorizationPromptMode.NEVER);
        } else {
            HudRuntimeWatchdog.cancel(appContext);
            AppEventLogger.event(appContext,
                    "runtime_supervisor idle_no_watchdog reason=" + safeReason);
        }
    }

    //keeps watchdog and permission repair active only when HUD/log/dashboard/update work exists.
    static boolean hasActiveRuntimeWork(Context context) {
        Context appContext = context.getApplicationContext();
        return HudRuntimeUpgradeGuard.isPendingReinit(appContext)
                || !NavCapturePrefs.getCapturePackages(appContext).isEmpty()
                || !NavAppDisplayController.get(appContext).persistedDashboardPackage().isEmpty()
                || hasTbtRuntimeWork(appContext);
    }

    static boolean shouldKeepTbtRuntimeForTest(
            boolean tbtEnabled, boolean gmapsInstalled, boolean gmapsHudEnabled,
            boolean wazeRouteActive, boolean wazeHudEnabled) {
        return tbtEnabled
                && ((gmapsInstalled && !gmapsHudEnabled)
                || (wazeRouteActive && !wazeHudEnabled));
    }

    private static boolean hasTbtRuntimeWork(Context context) {
        if (!HudPrefs.isTbtWithoutHudOutputEnabled(context)) return false;
        boolean gmapsInstalled = isInstalled(context, GMapsDirectChannel.PACKAGE_NAME);
        return shouldKeepTbtRuntimeForTest(
                true,
                gmapsInstalled,
                NavCapturePrefs.isHudEnabled(context, GMapsDirectChannel.PACKAGE_NAME),
                WazeRouteLifecycleStore.isRouteActive(context),
                NavCapturePrefs.isHudEnabled(context, WazeRouteLifecycleStore.WAZE_PACKAGE));
    }

    private static boolean isInstalled(Context context, String packageName) {
        try {
            context.getPackageManager().getApplicationInfo(packageName, 0);
            return true;
        } catch (Exception ignored) {
            return false;
        }
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
        if (!PACKAGE_REPLACE_RESET_IN_FLIGHT.compareAndSet(false, true)) {
            AppEventLogger.event(appContext,
                    "runtime_supervisor package_replace_hard_reset_skipped in_flight reason="
                            + safeReason);
            return;
        }
        AppEventLogger.event(appContext, "runtime_supervisor package_replace_hard_reset_start reason="
                + safeReason);
        AtomicBoolean statePersisted = new AtomicBoolean(false);
        AtomicBoolean senderTeardownComplete = new AtomicBoolean(false);
        AtomicBoolean finishHandled = new AtomicBoolean(false);
        Runnable finishReset = () -> {
            if (!statePersisted.get() || !senderTeardownComplete.get()
                    || !finishHandled.compareAndSet(false, true)) {
                return;
            }
            if (!HudRuntimeUpgradeGuard.completePendingHardReset(
                    appContext, "hard-reset:" + safeReason)) {
                HudRuntimeUpgradeGuard.rearmPendingHardReset(
                        appContext, "completion-persist-failed:" + safeReason);
                HudRuntimeWatchdog.scheduleSoon(
                        appContext, "package-replace-complete-failed",
                        PACKAGE_REPLACE_RESTART_DELAY_MS);
                AppEventLogger.event(appContext,
                        "runtime_supervisor package_replace_hard_reset_deferred completion_persist_failed reason="
                                + safeReason);
            } else {
                HudRuntimeWatchdog.scheduleSoon(
                        appContext, "package-replace-hard-reset",
                        PACKAGE_REPLACE_RESTART_DELAY_MS);
                AppEventLogger.event(appContext,
                        "runtime_supervisor package_replace_restart_scheduled reason="
                                + safeReason + " restartDelayMs="
                                + PACKAGE_REPLACE_RESTART_DELAY_MS);
            }
            PACKAGE_REPLACE_RESET_IN_FLIGHT.set(false);
            AppEventLogger.event(appContext,
                    "runtime_supervisor package_replace_hard_reset_exit reason="
                            + safeReason + " teardownComplete=true");
        };
        try {
            NavHudLiveSender.get(appContext).stop(
                    "", "package-replace-hard-reset", true,
                    () -> {
                        senderTeardownComplete.set(true);
                        finishReset.run();
                    });
            appContext.stopService(new android.content.Intent(appContext, HudRuntimeService.class));
            HudPrefs.setRuntimeServiceRunning(appContext, false);
            statePersisted.set(HudRuntimeState.markPackageReplaceReset(appContext, safeReason));
            if (!statePersisted.get()) {
                HudRuntimeUpgradeGuard.rearmPendingHardReset(
                        appContext, "state-persist-failed:" + safeReason);
                HudRuntimeWatchdog.scheduleSoon(
                        appContext, "package-replace-state-persist-failed",
                        PACKAGE_REPLACE_RESTART_DELAY_MS);
                PACKAGE_REPLACE_RESET_IN_FLIGHT.set(false);
                AppEventLogger.event(appContext,
                        "runtime_supervisor package_replace_hard_reset_deferred state_persist_failed reason="
                                + safeReason);
                return;
            }
            finishReset.run();
        } catch (RuntimeException error) {
            PACKAGE_REPLACE_RESET_IN_FLIGHT.set(false);
            HudRuntimeUpgradeGuard.rearmPendingHardReset(
                    appContext, "hard-reset-exception:" + safeReason);
            HudRuntimeWatchdog.scheduleSoon(
                    appContext, "package-replace-hard-reset-exception",
                    PACKAGE_REPLACE_RESTART_DELAY_MS);
            AppEventLogger.event(appContext,
                    "runtime_supervisor package_replace_hard_reset_failed reason="
                            + safeReason + " error=" + error.getClass().getSimpleName());
        }
    }

    static boolean packageReplaceResetInFlightForTest() {
        return PACKAGE_REPLACE_RESET_IN_FLIGHT.get();
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
