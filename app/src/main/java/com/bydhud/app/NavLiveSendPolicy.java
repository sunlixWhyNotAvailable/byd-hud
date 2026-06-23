package com.bydhud.app;

//decides when live output should be sent or cleared so transient parser gaps do not create stale HUD data.

final class NavLiveSendPolicy {
    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private NavLiveSendPolicy() {
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldSendLiveNavigation(NavParserResult result) {
        if (result == null || result.snapshot == null) {
            return false;
        }
        if (result.snapshot.sourceApp == NavSnapshot.SourceApp.WAZE) {
            return shouldSendWaze(result);
        }
        return result.snapshot.confidence >= 70;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean shouldSendWaze(NavParserResult result) {
        NavSnapshot snapshot = result.snapshot;
        if (snapshot.maneuver != NavSnapshot.Maneuver.UNKNOWN) {
            return snapshot.confidence >= 55;
        }
        if (snapshot.confidence < 45) {
            return false;
        }
        if (snapshot.distanceMeters > 0) {
            return true;
        }
        if (isWazeZeroDistanceWithoutRouteFields(snapshot)) {
            return false;
        }
        return hasUsefulRoad(snapshot.streetName);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasUsefulRoad(String streetName) {
        String clean = streetName == null ? "" : streetName.trim();
        return !clean.isEmpty() && !"Waze".equals(clean);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isWazeZeroDistanceWithoutRouteFields(NavSnapshot snapshot) {
        String raw = snapshot.rawReason == null ? "" : snapshot.rawReason;
        return snapshot.distanceMeters <= 0
                && raw.contains("distance=\"\"")
                && !hasNonEmptyField(raw, "eta")
                && !hasNonEmptyField(raw, "lanes");
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasNonEmptyField(String rawReason, String field) {
        String prefix = field + "=\"";
        return rawReason.contains(prefix) && !rawReason.contains(prefix + "\"");
    }
}
