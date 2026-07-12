package com.bydhud.app;

//repairs runtime permissions through adb so DiLink updates do not silently break capture.

import android.content.Context;

//defines the NavRuntimePermissionRepair module boundary so related behavior stays readable inside one unit.
final class NavRuntimePermissionRepair {
    private static final Object LOCK = new Object();
    private static final long MIN_REPAIR_INTERVAL_MS = 60_000L;
    private static final long REBIND_SETTLE_MS = 1_000L;
    private static final long FORCE_WAIT_FOR_ACTIVE_REPAIR_MS = 70_000L;
    private static boolean running;
    private static long lastStartedMs;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavRuntimePermissionRepair() {
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static void checkAndRepairAsync(
            Context context,
            String reason,
            boolean allowAdb,
            LocalAdbBridge.AuthorizationPromptMode promptMode) {
        Context appContext = context.getApplicationContext();
        LocalAdbBridge.AuthorizationPromptMode safeMode = promptMode == null
                ? LocalAdbBridge.AuthorizationPromptMode.NEVER
                : promptMode;
        NavRuntimePermissionStatus status = NavRuntimePermissionStatus.check(appContext);
        boolean keyKnown = LocalAdbBridge.isCurrentKeyKnownAuthorized(appContext);
        if (status.readyForCapture()
                && LocalAdbBridge.canShortCircuitReadyForCapture(appContext, safeMode)) {
            AppEventLogger.event(appContext, "nav_permission_repair skipped reason="
                    + safe(reason) + " status=ready keyKnown=" + keyKnown);
            return;
        }
        synchronized (LOCK) {
            long now = android.os.SystemClock.elapsedRealtime();
            boolean force = safeMode == LocalAdbBridge.AuthorizationPromptMode.FORCE;
            if (running || (!force && now - lastStartedMs < MIN_REPAIR_INTERVAL_MS)) {
                AppEventLogger.event(appContext, "nav_permission_repair skipped reason="
                        + safe(reason) + " running=" + running);
                return;
            }
            running = true;
            lastStartedMs = now;
        }
        Thread worker = new Thread(() -> {
            try {
                checkAndRepairBlockingOwned(appContext, reason, allowAdb, safeMode);
            } finally {
                finishRepair();
            }
        }, "BydHudNavPermissionRepair");
        worker.start();
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static LocalAdbBridge.Result checkAndRepairBlocking(
            Context context,
            String reason,
            boolean allowAdb,
            LocalAdbBridge.AuthorizationPromptMode promptMode) {
        Context appContext = context.getApplicationContext();
        LocalAdbBridge.AuthorizationPromptMode safeMode = promptMode == null
                ? LocalAdbBridge.AuthorizationPromptMode.NEVER
                : promptMode;
        synchronized (LOCK) {
            if (running && safeMode == LocalAdbBridge.AuthorizationPromptMode.FORCE) {
                LocalAdbBridge.AuthorizationCancellation cancellation =
                        LocalAdbBridge.cancelPendingAuthorization();
                AppEventLogger.event(appContext, "nav_permission_repair force_wait"
                        + " pending_auth_cancelled=" + cancellation.socketClosed);
                long deadline = android.os.SystemClock.elapsedRealtime()
                        + FORCE_WAIT_FOR_ACTIVE_REPAIR_MS;
                while (running) {
                    long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                    if (remaining <= 0L) {
                        break;
                    }
                    try {
                        LOCK.wait(remaining);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return LocalAdbBridge.Result.partial(
                                "Interrupted while waiting for active permission repair");
                    }
                }
            }
            if (running) {
                AppEventLogger.event(appContext, "nav_permission_repair skipped reason="
                        + safe(reason) + " running=true");
                return LocalAdbBridge.Result.partial("Permission repair already running");
            }
            running = true;
            lastStartedMs = android.os.SystemClock.elapsedRealtime();
        }
        try {
            return checkAndRepairBlockingOwned(
                    appContext,
                    reason,
                    allowAdb,
                    safeMode);
        } finally {
            finishRepair();
        }
    }

    //runs one repair after the shared single-flight gate has been acquired.
    private static LocalAdbBridge.Result checkAndRepairBlockingOwned(
            Context appContext,
            String reason,
            boolean allowAdb,
            LocalAdbBridge.AuthorizationPromptMode safeMode) {
        NavRuntimePermissionStatus before = NavRuntimePermissionStatus.check(appContext);
        boolean keyKnown = LocalAdbBridge.isCurrentKeyKnownAuthorized(appContext);
        AppEventLogger.event(appContext, "nav_permission_repair start reason="
                + safe(reason) + " status=" + before.summary()
                + " keyKnown=" + keyKnown + " mode=" + safeMode);
        if (before.readyForCapture()
                && LocalAdbBridge.canShortCircuitReadyForCapture(appContext, safeMode)) {
            return LocalAdbBridge.Result.alreadyGranted(before.summary());
        }

        if (before.settingsGranted() && !before.readyForCapture()) {
            NavNotificationListenerService.requestRuntimeRebind(appContext, reason);
            sleepQuietly(REBIND_SETTLE_MS);
            NavRuntimePermissionStatus rebound = NavRuntimePermissionStatus.check(appContext);
            if (rebound.readyForCapture()
                    && LocalAdbBridge.canShortCircuitReadyForCapture(appContext, safeMode)) {
                AppEventLogger.event(appContext,
                        "nav_permission_repair rebound_ready reason=" + safe(reason));
                return LocalAdbBridge.Result.granted(rebound.summary());
            }
        }

        if (!allowAdb) {
            NavRuntimePermissionStatus afterRebind = NavRuntimePermissionStatus.check(appContext);
            return LocalAdbBridge.Result.partial(afterRebind.summary());
        }

        LocalAdbBridge.Result result =
                LocalAdbBridge.grantNavCapturePermissions(appContext, safeMode);
        AppEventLogger.event(appContext, "nav_permission_repair adb_result "
                + result.code + " " + result.message);
        return result;
    }

    private static void finishRepair() {
        synchronized (LOCK) {
            running = false;
            LOCK.notifyAll();
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static void sleepQuietly(long delayMs) {
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
