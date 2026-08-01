package com.bydhud.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;

// Persists the patched-Waze route transition watermark across BYD HUD process restarts.
final class WazeRouteLifecycleStore {
    static final String WAZE_PACKAGE = "com.waze";
    static final String ACTION = "com.bydhud.app.action.WAZE_NAVIGATION_STATE";
    static final String PERMISSION = "com.bydhud.app.permission.WAZE_NAVIGATION_STATE";
    static final String CAPABILITY_META_DATA = "com.bydhud.waze.ROUTE_LIFECYCLE_PROTOCOL";
    static final String EXTRA_PROTOCOL_VERSION = "protocol_version";
    static final String EXTRA_NAVIGATING = "navigating";
    static final String EXTRA_EVENT_ELAPSED_MS = "event_elapsed_ms";
    static final int PROTOCOL_VERSION = 1;

    private static final String PREFS_NAME = "waze_route_lifecycle";
    private static final String KEY_ACTIVE = "route_active";
    private static final String KEY_EVENT_ELAPSED_MS = "event_elapsed_ms";
    private static final String KEY_PACKAGE_UPDATE_MS = "package_update_ms";
    private static final String KEY_BOOT_COUNT = "boot_count";
    private static final Object LOCK = new Object();

    static final class Snapshot {
        final boolean active;
        final long eventElapsedMs;
        final long packageUpdateMs;

        Snapshot(boolean active, long eventElapsedMs, long packageUpdateMs) {
            this.active = active;
            this.eventElapsedMs = eventElapsedMs;
            this.packageUpdateMs = packageUpdateMs;
        }
    }

    static final class RecordResult {
        final boolean accepted;
        final boolean changed;
        final Snapshot snapshot;
        final String reason;

        RecordResult(boolean accepted, boolean changed, Snapshot snapshot, String reason) {
            this.accepted = accepted;
            this.changed = changed;
            this.snapshot = snapshot;
            this.reason = reason;
        }
    }

    private WazeRouteLifecycleStore() {
    }

    static boolean isBridgeSupported(Context context) {
        if (NavigatorPatchStore.isInstalledWazeLifecycleV2(context)) return true;
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                    WAZE_PACKAGE, PackageManager.GET_META_DATA);
            Bundle metadata = info.metaData;
            return metadata != null
                    && metadata.getInt(CAPABILITY_META_DATA, 0) == PROTOCOL_VERSION
                    && context.getPackageManager().checkPermission(
                    PERMISSION, WAZE_PACKAGE) == PackageManager.PERMISSION_GRANTED;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    static Snapshot snapshot(Context context) {
        synchronized (LOCK) {
            return validatedSnapshotLocked(context, SystemClock.elapsedRealtime());
        }
    }

    static boolean isRouteActive(Context context) {
        return snapshot(context).active;
    }

    static RecordResult record(Context context, boolean active, long eventElapsedMs) {
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            Snapshot previous = validatedSnapshotLocked(context, now);
            String decision = eventDecision(previous.eventElapsedMs, eventElapsedMs, now);
            if (!"accept".equals(decision)) {
                return new RecordResult(false, false, previous, decision);
            }

            long packageUpdateMs = installedPackageUpdateTime(context);
            Snapshot updated = new Snapshot(active, eventElapsedMs, packageUpdateMs);
            boolean committed = prefs(context).edit()
                    .putBoolean(KEY_ACTIVE, active)
                    .putLong(KEY_EVENT_ELAPSED_MS, eventElapsedMs)
                    .putLong(KEY_PACKAGE_UPDATE_MS, packageUpdateMs)
                    .putInt(KEY_BOOT_COUNT, bootCount(context))
                    .commit();
            if (!committed) {
                return new RecordResult(false, false, previous, "commit_failed");
            }
            return new RecordResult(true, previous.active != active, updated,
                    previous.active == active ? "watermark" : "transition");
        }
    }

    static RecordResult recordLocalTerminal(Context context, long detectedAtMs) {
        return record(context, false, detectedAtMs);
    }

    static void clearForBoot(Context context, String reason) {
        synchronized (LOCK) {
            prefs(context).edit().clear().commit();
            AppEventLogger.event(context, "waze_route_lifecycle cleared reason=" + reason);
        }
    }

    static String eventDecision(long storedElapsedMs, long incomingElapsedMs, long nowElapsedMs) {
        if (incomingElapsedMs <= 0L) return "invalid_timestamp";
        if (incomingElapsedMs > nowElapsedMs) return "future_timestamp";
        if (storedElapsedMs > 0L && incomingElapsedMs <= storedElapsedMs) return "stale_timestamp";
        return "accept";
    }

    private static Snapshot validatedSnapshotLocked(Context context, long nowElapsedMs) {
        SharedPreferences preferences = prefs(context);
        boolean active = preferences.getBoolean(KEY_ACTIVE, false);
        long eventElapsedMs = preferences.getLong(KEY_EVENT_ELAPSED_MS, 0L);
        long storedPackageUpdateMs = preferences.getLong(KEY_PACKAGE_UPDATE_MS, 0L);
        long currentPackageUpdateMs = installedPackageUpdateTime(context);
        int storedBootCount = preferences.getInt(KEY_BOOT_COUNT, -1);
        int currentBootCount = bootCount(context);
        boolean rebooted = shouldInvalidateForBoot(
                storedBootCount, currentBootCount, eventElapsedMs, nowElapsedMs);
        boolean packageChanged = storedPackageUpdateMs > 0L
                && storedPackageUpdateMs != currentPackageUpdateMs;
        if (rebooted || packageChanged) {
            preferences.edit().clear().commit();
            AppEventLogger.event(context, "waze_route_lifecycle invalidated reboot=" + rebooted
                    + " packageChanged=" + packageChanged);
            return new Snapshot(false, 0L, currentPackageUpdateMs);
        }
        return new Snapshot(active, eventElapsedMs, currentPackageUpdateMs);
    }

    static boolean shouldInvalidateForBoot(
            int storedBootCount,
            int currentBootCount,
            long storedElapsedMs,
            long nowElapsedMs) {
        if (storedBootCount >= 0 && currentBootCount >= 0) {
            return storedBootCount != currentBootCount;
        }
        return storedElapsedMs > 0L && nowElapsedMs < storedElapsedMs;
    }

    private static int bootCount(Context context) {
        try {
            return Settings.Global.getInt(
                    context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static long installedPackageUpdateTime(Context context) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(WAZE_PACKAGE, 0);
            return info.lastUpdateTime;
        } catch (PackageManager.NameNotFoundException ignored) {
            return 0L;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
