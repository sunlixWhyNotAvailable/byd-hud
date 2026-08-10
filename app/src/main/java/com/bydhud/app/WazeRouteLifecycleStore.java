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
    static final String EXTRA_REASON_CODE = "reason_code";
    static final String EXTRA_BRIDGE_GENERATION = "bridge_generation";
    static final String EXTRA_BRIDGE_CAPABILITIES = "bridge_capabilities";
    static final String EXTRA_EVENT_ELAPSED_MS = "event_elapsed_ms";
    static final int PROTOCOL_VERSION = 1;
    static final int REASON_UNAVAILABLE = -1;

    private static final String PREFS_NAME = "waze_route_lifecycle";
    private static final String KEY_ACTIVE = "route_active";
    private static final String KEY_EVENT_ELAPSED_MS = "event_elapsed_ms";
    private static final String KEY_BRIDGE_GENERATION = "bridge_generation";
    private static final String KEY_BRIDGE_CAPABILITIES = "bridge_capabilities";
    private static final String KEY_SPEED_EVENT_ELAPSED_MS = "speed_event_elapsed_ms";
    private static final String KEY_SPEED_BRIDGE_GENERATION = "speed_bridge_generation";
    private static final String KEY_PACKAGE_UPDATE_MS = "package_update_ms";
    private static final String KEY_BOOT_COUNT = "boot_count";
    private static final Object LOCK = new Object();
    private static final Object BRIDGE_SUPPORT_LOCK = new Object();
    private static long cachedBridgeVersionCode = Long.MIN_VALUE;
    private static long cachedBridgeUpdateMs = Long.MIN_VALUE;
    private static boolean cachedBridgeSupported;

    static final class Snapshot {
        final boolean active;
        final long eventElapsedMs;
        final long packageUpdateMs;
        final long bridgeGeneration;
        final int bridgeCapabilities;

        Snapshot(boolean active, long eventElapsedMs, long packageUpdateMs) {
            this(active, eventElapsedMs, packageUpdateMs, 0L, 0);
        }

        Snapshot(boolean active, long eventElapsedMs, long packageUpdateMs,
                long bridgeGeneration, int bridgeCapabilities) {
            this.active = active;
            this.eventElapsedMs = eventElapsedMs;
            this.packageUpdateMs = packageUpdateMs;
            this.bridgeGeneration = bridgeGeneration;
            this.bridgeCapabilities = bridgeCapabilities;
        }
    }

    static final class SpeedRecordResult {
        final boolean accepted;
        final Snapshot snapshot;
        final String reason;

        SpeedRecordResult(boolean accepted, Snapshot snapshot, String reason) {
            this.accepted = accepted;
            this.snapshot = snapshot;
            this.reason = reason;
        }
    }

    static final class RecordResult {
        final boolean accepted;
        final boolean changed;
        final Snapshot snapshot;
        final String reason;
        final boolean terminal;
        final boolean rawNavigating;
        final int reasonCode;
        final String reasonName;

        RecordResult(boolean accepted, boolean changed, Snapshot snapshot, String reason,
                boolean terminal, boolean rawNavigating, int reasonCode, String reasonName) {
            this.accepted = accepted;
            this.changed = changed;
            this.snapshot = snapshot;
            this.reason = reason;
            this.terminal = terminal;
            this.rawNavigating = rawNavigating;
            this.reasonCode = reasonCode;
            this.reasonName = reasonName;
        }
    }

    private WazeRouteLifecycleStore() {
    }

    static boolean isBridgeSupported(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                    WAZE_PACKAGE, PackageManager.GET_META_DATA);
            long versionCode = packageInfo.getLongVersionCode();
            long updateMs = packageInfo.lastUpdateTime;
            synchronized (BRIDGE_SUPPORT_LOCK) {
                if (cachedBridgeSupported
                        && cachedBridgeVersionCode == versionCode
                        && cachedBridgeUpdateMs == updateMs) {
                    return true;
                }
            }
            if (NavigatorPatchStore.isInstalledWazeLifecycleV2(context)) {
                cacheBridgeSupport(versionCode, updateMs);
                return true;
            }
            ApplicationInfo info = packageInfo.applicationInfo;
            Bundle metadata = info == null ? null : info.metaData;
            boolean supported = metadata != null
                    && metadata.getInt(CAPABILITY_META_DATA, 0) == PROTOCOL_VERSION
                    && context.getPackageManager().checkPermission(
                    PERMISSION, WAZE_PACKAGE) == PackageManager.PERMISSION_GRANTED;
            if (supported) cacheBridgeSupport(versionCode, updateMs);
            return supported;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private static void cacheBridgeSupport(long versionCode, long updateMs) {
        synchronized (BRIDGE_SUPPORT_LOCK) {
            cachedBridgeVersionCode = versionCode;
            cachedBridgeUpdateMs = updateMs;
            cachedBridgeSupported = true;
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
                return new RecordResult(false, false, previous, decision,
                        false, active, REASON_UNAVAILABLE, "LOCAL_DIRECT");
            }
            return commitLocked(context, previous, active, eventElapsedMs,
                    0L, 0, !active, active, REASON_UNAVAILABLE, "LOCAL_DIRECT");
        }
    }

    static RecordResult recordBridge(Context context, boolean navigating, int reasonCode,
            boolean reasonAvailable, long eventElapsedMs) {
        return recordBridge(context, navigating, reasonCode, reasonAvailable,
                eventElapsedMs, 0L, 0);
    }

    static RecordResult recordBridge(Context context, boolean navigating, int reasonCode,
            boolean reasonAvailable, long eventElapsedMs, long bridgeGeneration,
            int bridgeCapabilities) {
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            Snapshot previous = validatedSnapshotLocked(context, now);
            String decision = eventDecision(previous.eventElapsedMs, eventElapsedMs, now);
            String reasonName = reasonAvailable ? reasonName(reasonCode) : "UNAVAILABLE";
            if (!"accept".equals(decision)) {
                return new RecordResult(false, false, previous, decision,
                        false, navigating, reasonCode, reasonName);
            }
            boolean terminal = !navigating && reasonAvailable && isTerminalReason(reasonCode);
            boolean active = resolveBridgeActive(
                    previous.active, navigating, reasonAvailable, reasonCode);
            long acceptedGeneration = active ? bridgeGeneration : 0L;
            int acceptedCapabilities = active ? bridgeCapabilities : 0;
            return commitLocked(context, previous, active, eventElapsedMs,
                    acceptedGeneration, acceptedCapabilities,
                    terminal, navigating, reasonCode, reasonName);
        }
    }

    static SpeedRecordResult recordSpeedEvent(Context context, long bridgeGeneration,
            int bridgeCapabilities, long eventElapsedMs) {
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            Snapshot route = validatedSnapshotLocked(context, now);
            SharedPreferences preferences = prefs(context);
            long storedGeneration = preferences.getLong(KEY_SPEED_BRIDGE_GENERATION, 0L);
            long storedElapsedMs = preferences.getLong(KEY_SPEED_EVENT_ELAPSED_MS, 0L);
            String decision = speedEventDecision(route.active, route.bridgeGeneration,
                    storedGeneration, storedElapsedMs, bridgeGeneration, eventElapsedMs, now);
            if (!"accept".equals(decision)) {
                return new SpeedRecordResult(false, route, decision);
            }
            preferences.edit()
                    .putLong(KEY_SPEED_BRIDGE_GENERATION, bridgeGeneration)
                    .putLong(KEY_SPEED_EVENT_ELAPSED_MS, eventElapsedMs)
                    .putInt(KEY_BRIDGE_CAPABILITIES, bridgeCapabilities)
                    .apply();
            Snapshot updated = new Snapshot(route.active, route.eventElapsedMs,
                    route.packageUpdateMs, route.bridgeGeneration, bridgeCapabilities);
            return new SpeedRecordResult(true, updated, "accept");
        }
    }

    private static RecordResult commitLocked(Context context, Snapshot previous, boolean active,
            long eventElapsedMs, long bridgeGeneration, int bridgeCapabilities,
            boolean terminal, boolean rawNavigating, int reasonCode, String reasonName) {
        long packageUpdateMs = installedPackageUpdateTime(context);
        Snapshot updated = new Snapshot(active, eventElapsedMs, packageUpdateMs,
                bridgeGeneration, bridgeCapabilities);
        SharedPreferences.Editor editor = prefs(context).edit()
                .putBoolean(KEY_ACTIVE, active)
                .putLong(KEY_EVENT_ELAPSED_MS, eventElapsedMs)
                .putLong(KEY_BRIDGE_GENERATION, bridgeGeneration)
                .putInt(KEY_BRIDGE_CAPABILITIES, bridgeCapabilities)
                .putLong(KEY_PACKAGE_UPDATE_MS, packageUpdateMs)
                .putInt(KEY_BOOT_COUNT, bootCount(context));
        if (!active || previous.bridgeGeneration != bridgeGeneration) {
            editor.remove(KEY_SPEED_EVENT_ELAPSED_MS)
                    .remove(KEY_SPEED_BRIDGE_GENERATION);
        }
        boolean committed = editor.commit();
        if (!committed) {
            return new RecordResult(false, false, previous, "commit_failed",
                    false, rawNavigating, reasonCode, reasonName);
        }
        String state = previous.active == active ? "watermark" : "transition";
        return new RecordResult(true, previous.active != active, updated,
                state + ":" + reasonName, terminal, rawNavigating, reasonCode, reasonName);
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

    static String speedEventDecision(boolean routeActive, long routeGeneration,
            long storedSpeedGeneration, long storedElapsedMs, long incomingGeneration,
            long incomingElapsedMs, long nowElapsedMs) {
        if (!routeActive) return "inactive_route";
        if (incomingGeneration != routeGeneration) return "generation_mismatch";
        long watermark = storedSpeedGeneration == routeGeneration ? storedElapsedMs : 0L;
        return eventDecision(watermark, incomingElapsedMs, nowElapsedMs);
    }

    static boolean isTerminalReason(int reasonCode) {
        return reasonCode == 1 || reasonCode == 5 || reasonCode == 6 || reasonCode == 7;
    }

    static boolean isTransitionReason(int reasonCode) {
        return reasonCode == 4 || reasonCode == 8 || reasonCode == 9;
    }

    static boolean resolveBridgeActive(boolean previousActive, boolean navigating,
            boolean reasonAvailable, int reasonCode) {
        return navigating || previousActive
                && !(reasonAvailable && isTerminalReason(reasonCode));
    }

    static String reasonName(int reasonCode) {
        switch (reasonCode) {
            case 0: return "UNKNOWN";
            case 1: return "REACHED_DESTINATION";
            case 2: return "SERVER_ERROR";
            case 3: return "TRANSPORT_APP";
            case 4: return "NEW_DEST";
            case 5: return "TERMINATE";
            case 6: return "USER";
            case 7: return "PARKED";
            case 8: return "NEW_ROUTE_RECEIVED";
            case 9: return "NEW_ROUTE_REQUESTED";
            default: return reasonCode == REASON_UNAVAILABLE
                    ? "UNAVAILABLE" : "UNRECOGNIZED_" + reasonCode;
        }
    }

    private static Snapshot validatedSnapshotLocked(Context context, long nowElapsedMs) {
        SharedPreferences preferences = prefs(context);
        boolean active = preferences.getBoolean(KEY_ACTIVE, false);
        long eventElapsedMs = preferences.getLong(KEY_EVENT_ELAPSED_MS, 0L);
        long bridgeGeneration = preferences.getLong(KEY_BRIDGE_GENERATION, 0L);
        int bridgeCapabilities = preferences.getInt(KEY_BRIDGE_CAPABILITIES, 0);
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
            return new Snapshot(false, 0L, currentPackageUpdateMs, 0L, 0);
        }
        return new Snapshot(active, eventElapsedMs, currentPackageUpdateMs,
                bridgeGeneration, bridgeCapabilities);
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
