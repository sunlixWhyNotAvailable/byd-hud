package com.bydhud.app;

final class NavLiveSendPolicy {
    private NavLiveSendPolicy() {
    }

    static boolean shouldSendLiveNavigation(NavParserResult result) {
        if (result == null || result.snapshot == null) {
            return false;
        }
        if (result.snapshot.sourceApp == NavSnapshot.SourceApp.WAZE) {
            return shouldSendWaze(result);
        }
        return result.snapshot.confidence >= 70;
    }

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

    private static boolean hasUsefulRoad(String streetName) {
        String clean = streetName == null ? "" : streetName.trim();
        return !clean.isEmpty() && !"Waze".equals(clean);
    }

    private static boolean isWazeZeroDistanceWithoutRouteFields(NavSnapshot snapshot) {
        String raw = snapshot.rawReason == null ? "" : snapshot.rawReason;
        return snapshot.distanceMeters <= 0
                && raw.contains("distance=\"\"")
                && !hasNonEmptyField(raw, "eta")
                && !hasNonEmptyField(raw, "lanes");
    }

    private static boolean hasNonEmptyField(String rawReason, String field) {
        String prefix = field + "=\"";
        return rawReason.contains(prefix) && !rawReason.contains(prefix + "\"");
    }
}
