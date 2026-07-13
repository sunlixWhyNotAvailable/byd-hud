package com.bydhud.app;

//parses google maps notifications so background navigation can keep the HUD route fresh.

import java.util.regex.Matcher;
import java.util.regex.Pattern;

//defines the GMapsNotificationParser parser boundary so raw app evidence is normalized before HUD decisions use it.
final class GMapsNotificationParser {
    private static final int MAX_ROAD_NAME_CHARS = 64;
    private static final int UNKNOWN_NEXT_DISTANCE_METERS = 0;
    private static final int ARRIVAL_SOURCE_MANEUVER = 10;
    private static final int ARRIVAL_FALLBACK_MAX_REMAINING_METERS = 100;
    private static final Pattern MINUTES =
            Pattern.compile("([0-9]+)\\s*min\\b", Pattern.CASE_INSENSITIVE);

    //defines the Result module boundary so related behavior stays readable inside one unit.
    static final class Result extends NavParserResult {
        Result(HudState state, NavSnapshot snapshot, String reason) {
            super(state, snapshot, reason);
        }

        Result(HudState state, NavSnapshot snapshot, String reason,
                NavManeuverEvidence maneuverEvidence) {
            super(state, snapshot, reason, maneuverEvidence);
        }
    }

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private GMapsNotificationParser() {
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isCandidatePackage(String packageName) {
        return NavTextNormalizer.sourceApp(packageName) == NavSnapshot.SourceApp.GOOGLE_MAPS;
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    static Result parse(String packageName, String title, String text, String subText,
            String category, boolean ongoing) {
        return parse(packageName, title, text, subText, category, ongoing,
                NavManeuverEvidence.NONE, System.currentTimeMillis());
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    static Result parse(String packageName, String title, String text, String subText,
            String category, boolean ongoing, NavManeuverEvidence iconEvidence,
            long nowElapsedMs) {
        if (!isCandidatePackage(packageName)) {
            return null;
        }
        if (!ongoing || !"navigation".equals(NavTextNormalizer.lower(category))) {
            return null;
        }

        String cleanTitle = NavTextNormalizer.cleanText(title);
        String cleanText = NavTextNormalizer.cleanText(text);
        String cleanSubText = NavTextNormalizer.cleanText(subText);
        if (cleanTitle.isEmpty() && cleanText.isEmpty() && cleanSubText.isEmpty()) {
            return null;
        }
        if ("rerouting...".equals(NavTextNormalizer.lower(cleanText))) {
            return null;
        }

        int nextMeters = NavTextNormalizer.distanceMeters(cleanTitle, -1);
        int remainingMeters = NavTextNormalizer.distanceMeters(cleanSubText, -1);
        int timeSeconds = timeSeconds(cleanSubText);
        String reason = "gmaps title=\"" + cleanTitle
                + "\" text=\"" + cleanText
                + "\" subText=\"" + cleanSubText + "\"";
        if (isArrivalNotification(cleanTitle, cleanText, nextMeters, remainingMeters, timeSeconds)) {
            return arrivalResult(packageName, cleanText, reason, 85);
        }
        String roadName = roadName(cleanText, cleanTitle);
        if (nextMeters < 0 && remainingMeters < 0 && roadName.isEmpty()) {
            return null;
        }
        NavManeuverEvidence textEvidence = NavManeuverEvidence.text(
                maneuver(cleanText),
                sourceManeuver(cleanText),
                confidence(nextMeters, remainingMeters, cleanText, cleanSubText),
                Long.MAX_VALUE,
                "text:" + cleanText);
        NavManeuverEvidence selectedEvidence =
                selectManeuverEvidence(cleanText, textEvidence, iconEvidence, nowElapsedMs);
        if (nextMeters < 0 && selectedEvidence.maneuver == NavSnapshot.Maneuver.UNKNOWN) {
            return null;
        }

        HudState state = new HudState();
        state.distanceToIntersection = nextMeters >= 0
                ? Math.max(0, nextMeters)
                : UNKNOWN_NEXT_DISTANCE_METERS;
        state.navigationStatus = 2;
        state.crossStatus = 2;
        state.carToDestination = Math.max(0, remainingMeters);
        state.timeToDestination = Math.max(0, timeSeconds);
        state.currentMaxSpeedLimit = 0;
        state.currentSpeed = 0;
        state.numOfLanes = 0;
        state.includeLaneBitmap = false;
        state.laneString = "";
        state.roadName = roadName.isEmpty() ? "Google Maps" : roadName;
        state.guidePoint = "";
        state.navigationRatio = 0.0d;
        state.setSourceManeuver(sourceManeuver(cleanText));
        state.setSourceManeuver(selectedEvidence.sourceManeuver);

        NavSnapshot.Maneuver maneuver = selectedEvidence.maneuver;
        int confidence = Math.max(
                confidence(nextMeters, remainingMeters, cleanText, cleanSubText),
                selectedEvidence.confidence);
        String selectedReason = reason + " maneuverEvidence={" + selectedEvidence.summary() + "}";
        NavSnapshot snapshot = new NavSnapshot(
                System.currentTimeMillis(),
                NavSnapshot.SourceApp.GOOGLE_MAPS,
                packageName,
                maneuver,
                nextMeters >= 0 ? Math.max(0, nextMeters) : UNKNOWN_NEXT_DISTANCE_METERS,
                state.roadName,
                0,
                "",
                confidence,
                selectedReason);
        return new Result(state, snapshot, selectedReason, selectedEvidence);
    }

    //keeps this Google Maps step isolated so notification and accessibility evidence remain comparable.
    static Result arrivalResult(String packageName, String destination, String reason, int confidence) {
        return arrivalResult(packageName, destination, reason, confidence, 0);
    }

    static Result arrivalResult(String packageName, String destination, String reason, int confidence,
            int distanceMeters) {
        int safeDistanceMeters = Math.max(0, distanceMeters);
        HudState state = new HudState();
        state.distanceToIntersection = safeDistanceMeters;
        state.navigationStatus = 2;
        state.crossStatus = 2;
        state.carToDestination = safeDistanceMeters;
        state.timeToDestination = 0;
        state.currentMaxSpeedLimit = 0;
        state.currentSpeed = 0;
        state.numOfLanes = 0;
        state.includeLaneBitmap = false;
        state.laneString = "";
        state.roadName = cap(NavTextNormalizer.cleanText(destination), MAX_ROAD_NAME_CHARS);
        state.guidePoint = "";
        state.navigationRatio = safeDistanceMeters > 0 ? 0.0d : 1.0d;
        state.setSourceManeuver(ARRIVAL_SOURCE_MANEUVER);

        String detail = reason + " arrival=\"" + NavTextNormalizer.cleanText(destination) + "\"";
        NavSnapshot snapshot = new NavSnapshot(
                System.currentTimeMillis(),
                NavSnapshot.SourceApp.GOOGLE_MAPS,
                packageName,
                NavSnapshot.Maneuver.ARRIVE,
                safeDistanceMeters,
                state.roadName,
                0,
                "",
                confidence,
                detail);
        return new Result(state, snapshot, detail);
    }

    //keeps this Google Maps step isolated so notification and accessibility evidence remain comparable.
    private static int timeSeconds(String subText) {
        Matcher matcher = MINUTES.matcher(subText);
        if (!matcher.find()) {
            return -1;
        }
        try {
            int minutes = Integer.parseInt(matcher.group(1));
            if (minutes > 1666) {
                return 99999;
            }
            return minutes * 60;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    //keeps this Google Maps step isolated so notification and accessibility evidence remain comparable.
    private static String roadName(String text, String title) {
        String value = text;
        String lower = NavTextNormalizer.lower(value);
        if (lower.startsWith("toward ")) {
            value = value.substring("toward ".length()).trim();
        }
        if (value.isEmpty()) {
            value = title;
        }
        return cap(value, MAX_ROAD_NAME_CHARS);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isArrivalNotification(
            String title,
            String text,
            int nextMeters,
            int remainingMeters,
            int timeSeconds) {
        if (!NavTextNormalizer.cleanText(title).isEmpty()) {
            return false;
        }
        if (NavTextNormalizer.cleanText(text).isEmpty()) {
            return false;
        }
        if (nextMeters >= 0) {
            return false;
        }
        return timeSeconds == 0
                && remainingMeters >= 0
                && remainingMeters <= ARRIVAL_FALLBACK_MAX_REMAINING_METERS;
    }

    //keeps this Google Maps step isolated so notification and accessibility evidence remain comparable.
    static int sourceManeuver(String text) {
        String lower = NavTextNormalizer.lower(text);
        if (lower.contains("u-turn") || lower.contains("uturn")) {
            return lower.contains("right") ? 19 : 8;
        }
        if (lower.contains("exit") || lower.contains("ramp")) {
            return lower.contains("left") ? 71 : 70;
        }
        if (lower.contains("roundabout")) {
            return 21;
        }
        if (lower.contains("slight left")) {
            return 4;
        }
        if (lower.contains("slight right")) {
            return 5;
        }
        if (lower.contains("left")) {
            return 2;
        }
        if (lower.contains("right")) {
            return 3;
        }
        return 9;
    }

    //keeps this Google Maps step isolated so notification and accessibility evidence remain comparable.
    static NavSnapshot.Maneuver maneuver(String text) {
        String lower = NavTextNormalizer.lower(text);
        if (lower.contains("u-turn") || lower.contains("uturn")) {
            return lower.contains("right")
                    ? NavSnapshot.Maneuver.UTURN_RIGHT
                    : NavSnapshot.Maneuver.UTURN_LEFT;
        }
        if (lower.contains("exit") || lower.contains("ramp")) {
            return lower.contains("left")
                    ? NavSnapshot.Maneuver.RAMP_LEFT
                    : NavSnapshot.Maneuver.RAMP_RIGHT;
        }
        if (lower.contains("roundabout")) {
            return NavSnapshot.Maneuver.ROUNDABOUT_RIGHT_EXIT;
        }
        if (lower.contains("slight left")) {
            return NavSnapshot.Maneuver.LEFT_45;
        }
        if (lower.contains("slight right")) {
            return NavSnapshot.Maneuver.RIGHT_45;
        }
        if (lower.contains("left")) {
            return NavSnapshot.Maneuver.LEFT_90;
        }
        if (lower.contains("right")) {
            return NavSnapshot.Maneuver.RIGHT_90;
        }
        return NavSnapshot.Maneuver.STRAIGHT;
    }

    //guard notification icon fallback so weak image evidence cannot overwrite stronger text evidence.
    private static NavManeuverEvidence selectManeuverEvidence(
            String cleanText,
            NavManeuverEvidence textEvidence,
            NavManeuverEvidence iconEvidence,
            long nowElapsedMs) {
        if (iconEvidence == null
                || iconEvidence.source != NavManeuverEvidence.Source.LARGE_ICON
                || !iconEvidence.isFreshAt(nowElapsedMs)) {
            return textEvidence;
        }
        if (isWeakTextManeuver(cleanText, textEvidence)) {
            return iconEvidence;
        }
        if (textEvidence != null && textEvidence.maneuver == iconEvidence.maneuver) {
            return textEvidence;
        }
        return textEvidence;
    }

    //guard largeIcon fallback to cases where notification text does not describe an actual maneuver.
    private static boolean isWeakTextManeuver(String text, NavManeuverEvidence evidence) {
        if (evidence == null || evidence.maneuver != NavSnapshot.Maneuver.STRAIGHT) {
            return false;
        }
        String lower = NavTextNormalizer.lower(text);
        return !lower.contains("straight")
                && !lower.contains("continue")
                && !lower.contains("left")
                && !lower.contains("right")
                && !lower.contains("u-turn")
                && !lower.contains("uturn")
                && !lower.contains("exit")
                && !lower.contains("ramp")
                && !lower.contains("roundabout");
    }

    //keeps this Google Maps step isolated so notification and accessibility evidence remain comparable.
    private static int confidence(int nextMeters, int remainingMeters, String text, String subText) {
        int confidence = 50;
        if (nextMeters >= 0) {
            confidence += 25;
        }
        if (remainingMeters >= 0) {
            confidence += 15;
        }
        if (!text.isEmpty()) {
            confidence += 10;
        }
        if (!subText.isEmpty()) {
            confidence += 5;
        }
        return Math.min(100, confidence);
    }

    //keeps this Google Maps step isolated so notification and accessibility evidence remain comparable.
    private static String cap(String value, int limit) {
        if (value == null) {
            return "";
        }
        String clean = value.trim().replaceAll("\\s+", " ");
        if (clean.length() <= limit) {
            return clean;
        }
        return clean.substring(0, limit);
    }
}
