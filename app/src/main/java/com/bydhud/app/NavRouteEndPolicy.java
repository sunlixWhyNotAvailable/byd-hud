package com.bydhud.app;

//detects route completion so the HUD clears promptly when navigation ends.

final class NavRouteEndPolicy {
    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavRouteEndPolicy() {
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldStopForRemovedNotification(boolean active,
            String removedPackage, String activePackage, String removedKey, String activeNotificationKey) {
        String safeRemovedPackage = normalizePackage(removedPackage);
        String safeActivePackage = normalizePackage(activePackage);
        if (!active || safeRemovedPackage.isEmpty()
                || !safeRemovedPackage.equals(safeActivePackage)) {
            return false;
        }
        String safeActiveKey = normalizeString(activeNotificationKey);
        if (safeActiveKey.isEmpty()) {
            return false;
        }
        String safeRemovedKey = normalizeString(removedKey);
        return !safeRemovedKey.isEmpty() && safeRemovedKey.equals(safeActiveKey);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldScheduleNoRouteAccessibilityStop(boolean active,
            String packageName, String activePackage, String payload) {
        String safePackage = normalizePackage(packageName);
        String safeActivePackage = normalizePackage(activePackage);
        if (!active || safePackage.isEmpty() || !safePackage.equals(safeActivePackage)) {
            return false;
        }
        if (!NavRouteEvidencePolicy.isRelevantAccessibilityPayload(safePackage, payload)) {
            return false;
        }
        NavRouteEvidencePolicy.RawRouteState state =
                NavRouteEvidencePolicy.classifyRawPayload(safePackage, payload);
        return state == NavRouteEvidencePolicy.RawRouteState.NO_ROUTE
                || state == NavRouteEvidencePolicy.RawRouteState.PREVIEW;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldScheduleArrivalStop(boolean active,
            String packageName, String activePackage, NavParserResult result) {
        return shouldScheduleArrivalStop(active, packageName, activePackage, result,
                false, false, false, Long.MAX_VALUE);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldScheduleArrivalStop(boolean active,
            String packageName, String activePackage, NavParserResult result,
            boolean hasFreshVisualCue, boolean hasFreshRouteText,
            boolean hasFreshNotificationRoute, long candidateAgeMs) {
        String safePackage = normalizePackage(packageName);
        String safeActivePackage = normalizePackage(activePackage);
        if (!active || safePackage.isEmpty() || !safePackage.equals(safeActivePackage)
                || result == null || result.snapshot == null) {
            return false;
        }
        NavSnapshot snapshot = result.snapshot;
        if (snapshot.maneuver == NavSnapshot.Maneuver.ARRIVE) {
            if (snapshot.sourceApp == NavSnapshot.SourceApp.WAZE) {
                return shouldEndWazeRouteOnArrival(
                        snapshot.rawReason,
                        hasFreshVisualCue,
                        hasFreshRouteText,
                        hasFreshNotificationRoute,
                        candidateAgeMs);
            }
            return true;
        }
        if (snapshot.sourceApp != NavSnapshot.SourceApp.WAZE
                || snapshot.maneuver != NavSnapshot.Maneuver.UNKNOWN
                || snapshot.distanceMeters > 0) {
            return false;
        }
        return !hasNonEmptyDistanceText(snapshot.rawReason)
                && !hasEtaOrLane(snapshot.rawReason)
                && hasUsefulRoad(snapshot.streetName);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldEndWazeRouteOnArrival(String payload,
            boolean hasFreshVisualCue,
            boolean hasFreshRouteText,
            boolean hasFreshNotificationRoute,
            long candidateAgeMs) {
        if (!containsWazeArrivalText(payload)) {
            return false;
        }
        if (hasNonEmptyDistanceText(payload) || hasEtaOrLane(payload)) {
            return false;
        }
        if (hasFreshVisualCue || hasFreshRouteText || hasFreshNotificationRoute) {
            return false;
        }
        return candidateAgeMs >= 3000L;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean hasActiveRouteSnapshot(NavParserResult result) {
        if (result == null || result.snapshot == null) {
            return false;
        }
        NavSnapshot snapshot = result.snapshot;
        if (snapshot.maneuver == NavSnapshot.Maneuver.ARRIVE
                || snapshot.maneuver == NavSnapshot.Maneuver.HIDE) {
            return false;
        }
        if (snapshot.distanceMeters > 0 || hasNonEmptyDistanceText(snapshot.rawReason)) {
            return true;
        }
        return hasEtaOrLane(snapshot.rawReason);
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String normalizePackage(String packageName) {
        return packageName == null ? "" : packageName.trim();
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static String normalizeString(String value) {
        return value == null ? "" : value.trim();
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasUsefulRoad(String roadName) {
        String clean = normalizeString(roadName);
        return !clean.isEmpty() && !"Waze".equals(clean);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasNonEmptyDistanceText(String rawReason) {
        String clean = normalizeString(rawReason);
        return clean.contains("distance=\"")
                && !clean.contains("distance=\"\"");
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasEtaOrLane(String rawReason) {
        String clean = normalizeString(rawReason);
        return clean.contains("eta=\"")
                && !clean.contains("eta=\"\"")
                || clean.contains("lanes=\"")
                && !clean.contains("lanes=\"\"");
    }

    //keeps this step explicit so callers can rely on one documented behavior boundary.
    private static boolean containsWazeArrivalText(String payload) {
        String lower = NavTextNormalizer.lower(payload);
        return lower.contains("arriving at")
                || lower.contains("you have arrived")
                || lower.contains("arrival=\"");
    }
}
