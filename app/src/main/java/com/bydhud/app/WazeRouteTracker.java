package com.bydhud.app;

import android.content.Context;
import android.os.SystemClock;

final class WazeRouteTracker {
    static final long ROUTE_EVIDENCE_TTL_MS = NavRouteStateStore.ROUTE_EVIDENCE_TTL_MS;
    private static final String WAZE_PACKAGE = "com.waze";

    private static WazeRouteTracker instance;

    static synchronized WazeRouteTracker get(Context context) {
        if (instance == null) {
            instance = new WazeRouteTracker();
        }
        return instance;
    }

    private final Object lock = new Object();
    private final NavRouteStateStore routeStore = new NavRouteStateStore();
    private String lastEvidenceNotificationKey = "";

    WazeRouteTracker() {
    }

    void updateFromSnapshot(NavSnapshot snapshot, String channel, long nowElapsedMs) {
        if (snapshot == null || snapshot.sourceApp != NavSnapshot.SourceApp.WAZE) {
            return;
        }
        routeStore.updateFromSnapshot(snapshot, channel, nowElapsedMs);
    }

    void updateFromRawPayload(
            String channel,
            String packageName,
            String payload,
            long nowElapsedMs) {
        if (!WAZE_PACKAGE.equals(normalizePackage(packageName))) {
            return;
        }
        if (hasRawRouteEvidence(payload)) {
            String notificationKey = channelContainsNotification(channel)
                    ? payloadField(payload, "key")
                    : "";
            routeStore.updateFromRawPayload(packageName, channel, payload, nowElapsedMs);
            synchronized (lock) {
                lastEvidenceNotificationKey = safe(notificationKey);
            }
        }
    }

    void onNotificationRemoved(String packageName, String notificationKey, long nowElapsedMs) {
        if (!WAZE_PACKAGE.equals(normalizePackage(packageName))) {
            return;
        }
        String removedKey = safe(notificationKey);
        synchronized (lock) {
            boolean keyedMatch = !lastEvidenceNotificationKey.isEmpty()
                    && lastEvidenceNotificationKey.equals(removedKey);
            if (keyedMatch) {
                lastEvidenceNotificationKey = "";
            } else if (!lastEvidenceNotificationKey.isEmpty()) {
                return;
            }
        }
        routeStore.onRouteRemoved(
                packageName,
                "notification removed key=" + removedKey + " at " + nowElapsedMs,
                nowElapsedMs);
    }

    void onRouteEnded(String reason, long nowElapsedMs) {
        synchronized (lock) {
            lastEvidenceNotificationKey = "";
        }
        routeStore.clearRoute(WAZE_PACKAGE, reason, nowElapsedMs);
    }

    void onVisualRouteEvidence(String reason, long nowElapsedMs) {
        routeStore.updateFromVisualRouteEvidence(
                WAZE_PACKAGE,
                "waze_crop_visual",
                reason,
                nowElapsedMs);
    }

    boolean isRouteActive(long nowElapsedMs) {
        return routeStore.isRouteActive(WAZE_PACKAGE, nowElapsedMs);
    }

    long evidenceAgeMs(long nowElapsedMs) {
        return routeStore.evidenceAgeMs(WAZE_PACKAGE, nowElapsedMs);
    }

    String reason() {
        return routeStore.reason(WAZE_PACKAGE);
    }

    NavSnapshot latestSnapshot() {
        return routeStore.latestSnapshot(WAZE_PACKAGE);
    }

    private static boolean hasRawRouteEvidence(String payload) {
        return NavRouteEvidencePolicy.hasRawRouteEvidence(WAZE_PACKAGE, payload);
    }

    private static String normalizePackage(String packageName) {
        return packageName == null ? "" : packageName.trim();
    }

    private static boolean channelContainsNotification(String channel) {
        return NavTextNormalizer.lower(channel).contains("notification");
    }

    private static String payloadField(String payload, String name) {
        String safePayload = payload == null ? "" : payload;
        String prefix = name + "=";
        int start = safePayload.startsWith(prefix)
                ? 0
                : safePayload.indexOf("; " + prefix);
        if (start < 0) {
            return "";
        }
        if (start > 0) {
            start += 2;
        }
        start += prefix.length();
        int end = safePayload.indexOf("; ", start);
        if (end < 0) {
            end = safePayload.length();
        }
        return safePayload.substring(start, end).trim();
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
