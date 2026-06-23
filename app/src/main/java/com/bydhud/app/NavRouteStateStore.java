package com.bydhud.app;

//tracks active route state so foreground and background sources can share continuity.

import android.content.Context;
import android.os.SystemClock;

import java.util.HashMap;
import java.util.Map;

//models NavRouteStateStore data here so transport and parser layers share a stable contract.
final class NavRouteStateStore {
    static final long ROUTE_EVIDENCE_TTL_MS = 30000L;

    private static NavRouteStateStore instance;

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    static synchronized NavRouteStateStore get(Context context) {
        if (instance == null) {
            instance = new NavRouteStateStore();
        }
        return instance;
    }

    private final Object lock = new Object();
    private final Map<String, RouteState> states = new HashMap<>();

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    NavRouteStateStore() {
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    void updateFromSnapshot(NavSnapshot snapshot, String channel, long nowElapsedMs) {
        if (snapshot == null || snapshot.packageName.isEmpty()) {
            return;
        }
        if (!hasSnapshotRouteEvidence(snapshot)) {
            return;
        }
        remember(snapshot.packageName, channel,
                "snapshot distance=" + snapshot.distanceMeters
                        + " street=\"" + snapshot.streetName + "\"",
                snapshot,
                nowElapsedMs);
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    void updateFromRawPayload(String packageName, String channel,
            String payload, long nowElapsedMs) {
        String normalized = safe(packageName);
        if (normalized.isEmpty()) {
            return;
        }
        if (!NavRouteEvidencePolicy.hasRawRouteEvidence(normalized, payload)) {
            return;
        }
        remember(normalized, channel, "raw route evidence", null, nowElapsedMs);
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    void updateFromVisualRouteEvidence(String packageName, String channel,
            String reason, long nowElapsedMs) {
        String normalized = safe(packageName);
        if (normalized.isEmpty()) {
            return;
        }
        remember(normalized, channel, safe(reason), null, nowElapsedMs);
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    void onRouteRemoved(String packageName, String reason, long nowElapsedMs) {
        String normalized = safe(packageName);
        if (normalized.isEmpty()) {
            return;
        }
        synchronized (lock) {
            RouteState current = states.get(normalized);
            if (current != null && !NavTextNormalizer.lower(current.channel).contains("notification")) {
                states.put(normalized, new RouteState(
                        current.lastEvidenceMs,
                        current.channel,
                        current.reason + "; ignored notification removal " + safe(reason),
                        current.latestSnapshot));
                return;
            }
            long safeNow = nowElapsedMs > 0L ? nowElapsedMs : SystemClock.elapsedRealtime();
            if (current != null) {
                states.put(normalized, new RouteState(
                        current.lastEvidenceMs,
                        "removed",
                        safe(reason),
                        current.latestSnapshot,
                        true));
                return;
            }
            states.put(normalized, new RouteState(
                    safeNow,
                    "removed",
                    safe(reason),
                    null,
                    true));
        }
    }

    //clears state here so stale navigation output is removed before new evidence arrives.
    void clearRoute(String packageName, String reason, long nowElapsedMs) {
        String normalized = safe(packageName);
        if (normalized.isEmpty()) {
            return;
        }
        long safeNow = nowElapsedMs > 0L ? nowElapsedMs : SystemClock.elapsedRealtime();
        synchronized (lock) {
            RouteState current = states.get(normalized);
            states.put(normalized, new RouteState(
                    current == null ? safeNow : current.lastEvidenceMs,
                    "cleared",
                    safe(reason),
                    current == null ? null : current.latestSnapshot,
                    true));
        }
    }

    //updates shared state here so freshness and lifecycle checks use the same evidence.
    void markRouteEnded(String packageName, String reason, long nowElapsedMs) {
        clearRoute(packageName, reason, nowElapsedMs);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean hasFreshWazeRouteEvidence(long nowElapsedMs) {
        return isRouteActive("com.waze", nowElapsedMs);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean isRouteActive(String packageName, long nowElapsedMs) {
        synchronized (lock) {
            RouteState state = states.get(safe(packageName));
            return state != null
                    && !state.removed
                    && state.lastEvidenceMs > 0L
                    && nowElapsedMs - state.lastEvidenceMs <= ROUTE_EVIDENCE_TTL_MS;
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    long evidenceAgeMs(String packageName, long nowElapsedMs) {
        synchronized (lock) {
            RouteState state = states.get(safe(packageName));
            if (state == null || state.lastEvidenceMs <= 0L) {
                return Long.MAX_VALUE;
            }
            return Math.max(0L, nowElapsedMs - state.lastEvidenceMs);
        }
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    String reason(String packageName) {
        synchronized (lock) {
            RouteState state = states.get(safe(packageName));
            if (state == null) {
                return "none";
            }
            return state.channel + ": " + state.reason;
        }
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    NavSnapshot latestSnapshot(String packageName) {
        synchronized (lock) {
            RouteState state = states.get(safe(packageName));
            return state == null ? null : state.latestSnapshot;
        }
    }

    //renders this UI section here so screen structure stays traceable during preview and car testing.
    private void remember(String packageName, String channel, String reason,
            NavSnapshot snapshot, long nowElapsedMs) {
        long safeNow = nowElapsedMs > 0L ? nowElapsedMs : SystemClock.elapsedRealtime();
        String key = safe(packageName);
        synchronized (lock) {
            RouteState current = states.get(key);
            NavSnapshot effectiveSnapshot =
                    snapshot != null || current == null ? snapshot : current.latestSnapshot;
            states.put(key, new RouteState(
                    safeNow,
                    safe(channel),
                    safe(reason),
                    effectiveSnapshot,
                    false));
        }
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasSnapshotRouteEvidence(NavSnapshot snapshot) {
        if (snapshot.sourceApp == NavSnapshot.SourceApp.WAZE
                && snapshot.maneuver == NavSnapshot.Maneuver.UNKNOWN
                && snapshot.distanceMeters <= 0
                && !hasNonEmptyField(snapshot.rawReason, "distance")
                && !hasNonEmptyField(snapshot.rawReason, "eta")
                && !hasNonEmptyField(snapshot.rawReason, "lanes")) {
            return false;
        }
        return snapshot.distanceMeters > 0
                || !snapshot.streetName.isEmpty()
                || snapshot.maneuver == NavSnapshot.Maneuver.ARRIVE
                || snapshot.rawReason.contains("eta=\"")
                || snapshot.rawReason.contains("lanes=\"");
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasNonEmptyField(String rawReason, String field) {
        String safe = rawReason == null ? "" : rawReason;
        String prefix = field + "=\"";
        return safe.contains(prefix) && !safe.contains(prefix + "\"");
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    //models RouteState data here so transport and parser layers share a stable contract.
    private static final class RouteState {
        final long lastEvidenceMs;
        final String channel;
        final String reason;
        final NavSnapshot latestSnapshot;
        final boolean removed;

        RouteState(long lastEvidenceMs, String channel, String reason,
                NavSnapshot latestSnapshot) {
            this(lastEvidenceMs, channel, reason, latestSnapshot, false);
        }

        RouteState(long lastEvidenceMs, String channel, String reason,
                NavSnapshot latestSnapshot, boolean removed) {
            this.lastEvidenceMs = lastEvidenceMs;
            this.channel = channel;
            this.reason = reason;
            this.latestSnapshot = latestSnapshot;
            this.removed = removed;
        }
    }
}
