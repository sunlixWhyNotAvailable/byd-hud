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
    static final String EXTRA_EVENT_TYPE = "event_type";
    static final int PROTOCOL_VERSION = 1;
    static final int V2_PROTOCOL_VERSION = 2;
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
    private static final String KEY_TERMINAL_BRIDGE_GENERATION = "terminal_bridge_generation";
    private static final long NO_TERMINAL_FENCE = Long.MIN_VALUE;
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
        final long terminalBridgeGeneration;

        Snapshot(boolean active, long eventElapsedMs, long packageUpdateMs) {
            this(active, eventElapsedMs, packageUpdateMs, 0L, 0);
        }

        Snapshot(boolean active, long eventElapsedMs, long packageUpdateMs,
                long bridgeGeneration, int bridgeCapabilities) {
            this(active, eventElapsedMs, packageUpdateMs, bridgeGeneration,
                    bridgeCapabilities, NO_TERMINAL_FENCE);
        }

        Snapshot(boolean active, long eventElapsedMs, long packageUpdateMs,
                long bridgeGeneration, int bridgeCapabilities,
                long terminalBridgeGeneration) {
            this.active = active;
            this.eventElapsedMs = eventElapsedMs;
            this.packageUpdateMs = packageUpdateMs;
            this.bridgeGeneration = bridgeGeneration;
            this.bridgeCapabilities = bridgeCapabilities;
            this.terminalBridgeGeneration = terminalBridgeGeneration;
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
            ApplicationInfo info = packageInfo.applicationInfo;
            Bundle metadata = info == null ? null : info.metaData;
            int declaredProtocol = metadata == null
                    ? 0 : metadata.getInt(CAPABILITY_META_DATA, 0);
            boolean permissionGranted = context.getPackageManager().checkPermission(
                    PERMISSION, WAZE_PACKAGE) == PackageManager.PERMISSION_GRANTED;
            boolean supported = isBridgeCapabilitySupportedForTest(
                    declaredProtocol, permissionGranted);
            if (supported) cacheBridgeSupport(versionCode, updateMs);
            return supported;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    /** Records a validated V2 identity so startup capability checks can use the
     * observed structural channel even when the installed build has no legacy
     * signature permission. */
    static void noteV2BridgeObserved(Context context) {
        if (context == null) return;
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                    WAZE_PACKAGE, 0);
            cacheBridgeSupport(packageInfo.getLongVersionCode(), packageInfo.lastUpdateTime);
        } catch (PackageManager.NameNotFoundException ignored) {
            // The identity fence already failed if Waze is not installed.
        }
    }

    /** V2 is structural; legacy V1 still relies on its signature permission. */
    static boolean isBridgeCapabilitySupportedForTest(
        int declaredProtocol, boolean permissionGranted) {
        return declaredProtocol == V2_PROTOCOL_VERSION
                || permissionGranted
                && (declaredProtocol == PROTOCOL_VERSION || declaredProtocol == 0);
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
            long terminalGeneration = !active
                    ? bridgeGenerationForTerminal(previous, 0L) : NO_TERMINAL_FENCE;
            return commitLocked(context, previous, active, eventElapsedMs,
                    active ? 0L : terminalGeneration, 0, !active, active,
                    REASON_UNAVAILABLE, "LOCAL_DIRECT", "local");
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
        return recordBridge(context, navigating, reasonCode, reasonAvailable,
                eventElapsedMs, bridgeGeneration, bridgeCapabilities, "");
    }

    static RecordResult recordBridge(Context context, boolean navigating, int reasonCode,
            boolean reasonAvailable, long eventElapsedMs, long bridgeGeneration,
            int bridgeCapabilities, String eventType) {
        synchronized (LOCK) {
            long now = SystemClock.elapsedRealtime();
            Snapshot previous = validatedSnapshotLocked(context, now);
            boolean snapshot = isStateSnapshot(eventType);
            String decision = snapshot
                    ? snapshotEventDecision(eventElapsedMs, now)
                    : eventDecision(previous.eventElapsedMs, eventElapsedMs, now);
            String reasonName = reasonAvailable ? reasonName(reasonCode) : "UNAVAILABLE";
            if (!"accept".equals(decision)) {
                return new RecordResult(false, false, previous, decision,
                        false, navigating, reasonCode, reasonName);
            }
            String generationDecision = bridgeGenerationDecision(previous, bridgeGeneration);
            if (!"accept".equals(generationDecision)) {
                return new RecordResult(false, false, previous, generationDecision,
                        false, navigating, reasonCode, reasonName);
            }
            boolean terminal = !snapshot && !navigating && reasonAvailable
                    && isTerminalReason(reasonCode);
            if (snapshot) {
                String snapshotDecision = snapshotDecision(previous, navigating, bridgeGeneration);
                if (!"accept".equals(snapshotDecision)) {
                    return new RecordResult(false, false, previous, snapshotDecision,
                            false, navigating, reasonCode, reasonName);
                }
                boolean seed = shouldSeedSnapshot(previous, bridgeGeneration);
                if (!seed) {
                    return new RecordResult(true, false, previous,
                            "snapshot_replay_noop", false, navigating, reasonCode, reasonName);
                }
                long seededGeneration = bridgeGeneration;
                long fence = !navigating && seededGeneration != 0L
                        ? seededGeneration
                        : seededGeneration != previous.terminalBridgeGeneration
                        ? NO_TERMINAL_FENCE : previous.terminalBridgeGeneration;
                return commitLocked(context, previous, navigating,
                        previous.eventElapsedMs, seededGeneration, bridgeCapabilities,
                        false, navigating, reasonCode, reasonName, eventType,
                        fence, true);
            }
            if (navigating && terminalFenceBlocks(previous, bridgeGeneration,
                    reasonAvailable, reasonCode)) {
                return new RecordResult(false, false, previous, "terminal_fence",
                        false, navigating, reasonCode, reasonName);
            }
            boolean active = resolveBridgeActive(
                    previous.active, navigating, reasonAvailable, reasonCode);
            long acceptedGeneration = active
                    ? bridgeGenerationForActive(previous, bridgeGeneration)
                    : bridgeGenerationForTerminal(previous, bridgeGeneration);
            int acceptedCapabilities = active ? bridgeCapabilities : 0;
            long resultingFence = terminalFenceAfterEvent(previous, navigating, terminal,
                    acceptedGeneration, bridgeGeneration, reasonAvailable, reasonCode);
            return commitLocked(context, previous, active, eventElapsedMs,
                    acceptedGeneration, acceptedCapabilities,
                    terminal, navigating, reasonCode, reasonName, eventType,
                    resultingFence, false);
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
            boolean terminal, boolean rawNavigating, int reasonCode, String reasonName,
            String eventType) {
        return commitLocked(context, previous, active, eventElapsedMs, bridgeGeneration,
                bridgeCapabilities, terminal, rawNavigating, reasonCode, reasonName,
                eventType, terminal ? bridgeGeneration : NO_TERMINAL_FENCE, false);
    }

    private static RecordResult commitLocked(Context context, Snapshot previous, boolean active,
            long eventElapsedMs, long bridgeGeneration, int bridgeCapabilities,
            boolean terminal, boolean rawNavigating, int reasonCode, String reasonName,
            String eventType, long terminalGeneration, boolean snapshotSeed) {
        long packageUpdateMs = installedPackageUpdateTime(context);
        Snapshot updated = new Snapshot(active, eventElapsedMs, packageUpdateMs,
                bridgeGeneration, bridgeCapabilities,
                terminalGeneration);
        SharedPreferences.Editor editor = prefs(context).edit()
                .putBoolean(KEY_ACTIVE, active)
                .putLong(KEY_EVENT_ELAPSED_MS, eventElapsedMs)
                .putLong(KEY_BRIDGE_GENERATION, bridgeGeneration)
                .putInt(KEY_BRIDGE_CAPABILITIES, bridgeCapabilities)
                .putLong(KEY_PACKAGE_UPDATE_MS, packageUpdateMs)
                .putInt(KEY_BOOT_COUNT, bootCount(context));
        if (terminalGeneration != NO_TERMINAL_FENCE) {
            editor.putLong(KEY_TERMINAL_BRIDGE_GENERATION, terminalGeneration);
        } else {
            editor.remove(KEY_TERMINAL_BRIDGE_GENERATION);
        }
        if (!active || previous.bridgeGeneration != bridgeGeneration) {
            editor.remove(KEY_SPEED_EVENT_ELAPSED_MS)
                    .remove(KEY_SPEED_BRIDGE_GENERATION);
        }
        boolean committed = editor.commit();
        if (!committed) {
            return new RecordResult(false, false, previous, "commit_failed",
                    false, rawNavigating, reasonCode, reasonName);
        }
        String state = snapshotSeed ? "snapshot_seed"
                : previous.active == active ? "watermark" : "transition";
        return new RecordResult(true, previous.active != active, updated,
                state + ":" + reasonName + ":event=" + safeEventType(eventType),
                terminal, rawNavigating, reasonCode, reasonName);
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

    static String snapshotEventDecision(long incomingElapsedMs, long nowElapsedMs) {
        if (incomingElapsedMs <= 0L) return "invalid_timestamp";
        return incomingElapsedMs > nowElapsedMs ? "future_timestamp" : "accept";
    }

    static boolean isStateSnapshot(String eventType) {
        return "state_snapshot".equals(eventType);
    }

    static boolean shouldSeedSnapshot(Snapshot previous, long incomingGeneration) {
        if (!hasBridgeIdentity(previous)) return true;
        return incomingGeneration > knownBridgeGeneration(previous);
    }

    static String snapshotDecision(Snapshot previous, boolean navigating,
            long incomingGeneration) {
        if (previous.terminalBridgeGeneration != NO_TERMINAL_FENCE
                && incomingGeneration == previous.terminalBridgeGeneration) {
            return "terminal_fence";
        }
        long knownGeneration = knownBridgeGeneration(previous);
        if (knownGeneration > 0L
                && (incomingGeneration == 0L || incomingGeneration < knownGeneration)) {
            return "generation_regression";
        }
        return "accept";
    }

    static String bridgeGenerationDecision(Snapshot previous, long incomingGeneration) {
        long knownGeneration = knownBridgeGeneration(previous);
        return knownGeneration > 0L && incomingGeneration > 0L
                && incomingGeneration < knownGeneration
                ? "generation_regression" : "accept";
    }

    static boolean terminalFenceBlocks(Snapshot previous, long incomingGeneration,
            boolean reasonAvailable, int reasonCode) {
        if (previous.terminalBridgeGeneration == NO_TERMINAL_FENCE) return false;
        boolean explicitFreshReason = reasonAvailable && isTransitionReason(reasonCode);
        boolean newGeneration = incomingGeneration != 0L
                && incomingGeneration > previous.terminalBridgeGeneration;
        return !explicitFreshReason && !newGeneration;
    }

    static long terminalFenceAfterEvent(Snapshot previous, boolean navigating,
            boolean terminal, long acceptedGeneration, long incomingGeneration,
            boolean reasonAvailable, int reasonCode) {
        if (terminal) return acceptedGeneration;
        if (previous.terminalBridgeGeneration == NO_TERMINAL_FENCE) return NO_TERMINAL_FENCE;
        boolean explicitFreshReason = reasonAvailable && isTransitionReason(reasonCode);
        boolean newGeneration = incomingGeneration != 0L
                && incomingGeneration > previous.terminalBridgeGeneration;
        return navigating && (explicitFreshReason || newGeneration)
                ? NO_TERMINAL_FENCE : previous.terminalBridgeGeneration;
    }

    private static boolean hasBridgeIdentity(Snapshot snapshot) {
        return snapshot.bridgeGeneration != 0L
                || snapshot.eventElapsedMs > 0L
                || snapshot.terminalBridgeGeneration != NO_TERMINAL_FENCE;
    }

    private static long knownBridgeGeneration(Snapshot snapshot) {
        long known = Math.max(0L, snapshot.bridgeGeneration);
        if (snapshot.terminalBridgeGeneration > known) {
            known = snapshot.terminalBridgeGeneration;
        }
        return known;
    }

    private static long bridgeGenerationForTerminal(Snapshot previous, long incomingGeneration) {
        return incomingGeneration != 0L ? incomingGeneration : previous.bridgeGeneration;
    }

    private static long bridgeGenerationForActive(Snapshot previous, long incomingGeneration) {
        return incomingGeneration != 0L ? incomingGeneration : previous.bridgeGeneration;
    }

    private static String safeEventType(String eventType) {
        if (eventType == null || eventType.isEmpty()) return "unspecified";
        return eventType.replace(':', '_').replace('\n', '_').replace('\r', '_');
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
            return new Snapshot(false, 0L, currentPackageUpdateMs, 0L, 0,
                    NO_TERMINAL_FENCE);
        }
        long terminalGeneration = preferences.getLong(
                KEY_TERMINAL_BRIDGE_GENERATION, NO_TERMINAL_FENCE);
        return new Snapshot(active, eventElapsedMs, currentPackageUpdateMs,
                bridgeGeneration, bridgeCapabilities, terminalGeneration);
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
