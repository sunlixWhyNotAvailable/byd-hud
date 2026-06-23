package com.bydhud.app;

//tracks Waze route lifecycle so capture starts and stops with real navigation evidence.

import android.content.Context;
import android.os.SystemClock;

//defines the WazeRouteTracker module boundary so related behavior stays readable inside one unit.
final class WazeRouteTracker {
    static final long ROUTE_EVIDENCE_TTL_MS = NavRouteStateStore.ROUTE_EVIDENCE_TTL_MS;
    private static final String WAZE_PACKAGE = "com.waze";

    private static WazeRouteTracker instance;

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    static synchronized WazeRouteTracker get(Context context) {
        if (instance == null) {
            instance = new WazeRouteTracker();
        }
        return instance;
    }

    private final Object lock = new Object();
    private final NavRouteStateStore routeStore = new NavRouteStateStore();
    private String lastEvidenceNotificationKey = "";

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    WazeRouteTracker() {
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    void updateFromSnapshot(NavSnapshot snapshot, String channel, long nowElapsedMs) {
        if (snapshot == null || snapshot.sourceApp != NavSnapshot.SourceApp.WAZE) {
            return;
        }
        routeStore.updateFromSnapshot(snapshot, channel, nowElapsedMs);
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
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

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
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

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    void onRouteEnded(String reason, long nowElapsedMs) {
        synchronized (lock) {
            lastEvidenceNotificationKey = "";
        }
        routeStore.clearRoute(WAZE_PACKAGE, reason, nowElapsedMs);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    void onVisualRouteEvidence(String reason, long nowElapsedMs) {
        routeStore.updateFromVisualRouteEvidence(
                WAZE_PACKAGE,
                "waze_crop_visual",
                reason,
                nowElapsedMs);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean isRouteActive(long nowElapsedMs) {
        return routeStore.isRouteActive(WAZE_PACKAGE, nowElapsedMs);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    long evidenceAgeMs(long nowElapsedMs) {
        return routeStore.evidenceAgeMs(WAZE_PACKAGE, nowElapsedMs);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    String reason() {
        return routeStore.reason(WAZE_PACKAGE);
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    NavSnapshot latestSnapshot() {
        return routeStore.latestSnapshot(WAZE_PACKAGE);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasRawRouteEvidence(String payload) {
        return NavRouteEvidencePolicy.hasRawRouteEvidence(WAZE_PACKAGE, payload);
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String normalizePackage(String packageName) {
        return packageName == null ? "" : packageName.trim();
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean channelContainsNotification(String channel) {
        return NavTextNormalizer.lower(channel).contains("notification");
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
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

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
