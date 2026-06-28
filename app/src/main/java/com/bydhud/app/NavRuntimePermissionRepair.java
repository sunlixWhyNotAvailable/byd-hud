package com.bydhud.app;

//repairs runtime permissions through adb so DiLink updates do not silently break capture.

import android.content.Context;

//defines the NavRuntimePermissionRepair module boundary so related behavior stays readable inside one unit.
final class NavRuntimePermissionRepair {
    private static final Object LOCK = new Object();
    private static final long MIN_REPAIR_INTERVAL_MS = 60_000L;
    private static final long REBIND_SETTLE_MS = 1_000L;
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
        synchronized (LOCK) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (running || now - lastStartedMs < MIN_REPAIR_INTERVAL_MS) {
                AppEventLogger.event(appContext, "nav_permission_repair skipped reason="
                        + safe(reason) + " running=" + running);
                return;
            }
            running = true;
            lastStartedMs = now;
        }
        Thread worker = new Thread(() -> {
            try {
                checkAndRepairBlocking(appContext, reason, allowAdb, promptMode);
            } finally {
                synchronized (LOCK) {
                    running = false;
                }
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
        NavRuntimePermissionStatus before = NavRuntimePermissionStatus.check(appContext);
        AppEventLogger.event(appContext, "nav_permission_repair start reason="
                + safe(reason) + " status=" + before.summary());
        LocalAdbBridge.AuthorizationPromptMode safeMode =
                promptMode == null ? LocalAdbBridge.AuthorizationPromptMode.NEVER : promptMode;
        if (before.readyForCapture()
                && LocalAdbBridge.canShortCircuitReadyForCapture(safeMode)) {
            return LocalAdbBridge.Result.alreadyGranted(before.summary());
        }

        if (before.settingsGranted()
                && LocalAdbBridge.canShortCircuitReadyForCapture(safeMode)) {
            NavNotificationListenerService.requestRuntimeRebind(appContext, reason);
            sleepQuietly(REBIND_SETTLE_MS);
            NavRuntimePermissionStatus rebound = NavRuntimePermissionStatus.check(appContext);
            if (rebound.readyForCapture()) {
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
