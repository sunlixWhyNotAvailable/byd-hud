package com.bydhud.app;

//extracts google maps foreground text so notification gaps still provide distance and road evidence.

import java.util.regex.Matcher;
import java.util.regex.Pattern;

//defines the GMapsAccessibilityParser parser boundary so raw app evidence is normalized before HUD decisions use it.
final class GMapsAccessibilityParser {
    private static final int MAX_ROAD_NAME_CHARS = 64;
    private static final int UNKNOWN_NEXT_DISTANCE_METERS = 1;
    private static final Pattern MINUTES =
            Pattern.compile("([0-9]+)\\s*min\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IN_DISTANCE =
            Pattern.compile("\\b(in|for)\\s+[0-9]+(?:[\\.,][0-9]+)?\\s*(km|m|\\u043a\\u043c|\\u043c)(?=$|\\s|[.,;:!?])",
                    Pattern.CASE_INSENSITIVE);

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private GMapsAccessibilityParser() {
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    static GMapsNotificationParser.Result parse(String packageName, String payload, HudState baseline) {
        if (!GMapsNotificationParser.isCandidatePackage(packageName)) {
            return null;
        }
        String cleanPayload = NavTextNormalizer.cleanText(payload);
        if (cleanPayload.isEmpty()) {
            return null;
        }

        Candidate best = null;
        String topCue = "";
        int remainingMeters = baseline == null ? 0 : Math.max(0, baseline.carToDestination);
        int timeSeconds = baseline == null ? 0 : Math.max(0, baseline.timeToDestination);
        ArrivalSignal arrival = new ArrivalSignal();

        String[] segments = cleanPayload.split(";");
        for (String rawSegment : segments) {
            String segment = NavTextNormalizer.cleanText(rawSegment);
            if (segment.isEmpty()) {
                continue;
            }
            String id = fieldValue(segment, " id=");
            String text = fieldValue(segment, " text=");
            String desc = fieldValue(segment, " desc=");
            String idLower = NavTextNormalizer.lower(id);
            String descLower = NavTextNormalizer.lower(desc);
            arrival.observe(text, desc);

            if (idLower.endsWith(":id/top_cue_text") && !text.isEmpty()) {
                topCue = text;
            }
            if (idLower.endsWith(":id/navigation_time_remaining_label")) {
                timeSeconds = timeSeconds(text, timeSeconds);
            }
            if (descLower.startsWith("distance remaining is")) {
                remainingMeters = NavTextNormalizer.distanceMeters(desc, remainingMeters);
            }

            best = better(best, candidate(desc, scoreBaseForId(idLower, true)));
            best = better(best, candidate(text, scoreBaseForId(idLower, false)));
        }

        if (arrival.isArrival()) {
            String reason = "gmaps accessibility arrival=\"" + arrival.destination + "\"";
            return GMapsNotificationParser.arrivalResult(
                    packageName,
                    arrival.destination,
                    reason,
                    95);
        }

        if (best == null || !best.hasDistance) {
            return null;
        }

        String maneuverText = stripManeuverDistance(best.text);
        String roadName = roadName(topCue, maneuverText);
        HudState state = new HudState();
        state.distanceToIntersection = best.distanceMeters >= 0
                ? Math.max(0, best.distanceMeters)
                : UNKNOWN_NEXT_DISTANCE_METERS;
        state.navigationStatus = 2;
        state.crossStatus = 2;
        state.carToDestination = remainingMeters;
        state.timeToDestination = timeSeconds;
        state.currentMaxSpeedLimit = 0;
        state.currentSpeed = 0;
        state.numOfLanes = 0;
        state.includeLaneBitmap = false;
        state.laneString = "";
        state.roadName = roadName.isEmpty() ? maneuverText : roadName;
        state.guidePoint = "";
        state.navigationRatio = baseline == null ? 0.0d : baseline.navigationRatio;
        state.setSourceManeuver(GMapsNotificationParser.sourceManeuver(maneuverText));

        NavSnapshot.Maneuver maneuver = GMapsNotificationParser.maneuver(maneuverText);
        int confidence = Math.min(100, 70 + (best.hasDistance ? 20 : 0)
                + (remainingMeters > 0 ? 5 : 0) + (timeSeconds > 0 ? 5 : 0));
        String reason = "gmaps accessibility instruction=\"" + best.text
                + "\" topCue=\"" + topCue + "\"";
        NavSnapshot snapshot = new NavSnapshot(
                System.currentTimeMillis(),
                NavSnapshot.SourceApp.GOOGLE_MAPS,
                packageName,
                maneuver,
                state.distanceToIntersection,
                state.roadName,
                0,
                "",
                confidence,
                reason);
        return new GMapsNotificationParser.Result(state, snapshot, reason);
    }

    //keeps this Google Maps step isolated so notification and accessibility evidence remain comparable.
    private static Candidate better(Candidate current, Candidate candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null || candidate.score > current.score) {
            return candidate;
        }
        return current;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static Candidate candidate(String value, int baseScore) {
        String clean = NavTextNormalizer.cleanText(value);
        if (clean.isEmpty() || baseScore <= 0) {
            return null;
        }
        String lower = NavTextNormalizer.lower(clean);
        boolean turnLike = isTurnLike(lower);
        boolean straightLike = isStraightLike(lower);
        if (!turnLike && !straightLike) {
            return null;
        }
        int distanceMeters = NavTextNormalizer.distanceMeters(clean, -1);
        boolean hasDistance = distanceMeters >= 0;
        int score = baseScore + (hasDistance ? 30 : 0) + (turnLike ? 20 : 0);
        return new Candidate(clean, distanceMeters, hasDistance, score);
    }

    //keeps this Google Maps step isolated so notification and accessibility evidence remain comparable.
    private static int scoreBaseForId(String idLower, boolean description) {
        if (idLower.endsWith(":id/navigation_instruction_panel")) {
            return description ? 100 : 90;
        }
        if (idLower.endsWith(":id/step_instruction_container")) {
            return description ? 70 : 60;
        }
        if (idLower.endsWith(":id/top_cue_text")) {
            return description ? 50 : 45;
        }
        if (idLower.endsWith(":id/next_step_instruction_container")
                || idLower.endsWith(":id/next_step_instruction")) {
            return description ? 80 : 70;
        }
        return description ? 60 : 0;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isTurnLike(String lower) {
        return lower.contains("turn ")
                || lower.contains("left")
                || lower.contains("right")
                || lower.contains("u-turn")
                || lower.contains("uturn")
                || lower.contains("exit")
                || lower.contains("ramp")
                || lower.contains("roundabout");
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isStraightLike(String lower) {
        return lower.startsWith("head ")
                || lower.startsWith("continue")
                || lower.contains("straight");
    }

    //keeps this Google Maps step isolated so notification and accessibility evidence remain comparable.
    private static String fieldValue(String segment, String marker) {
        int start = segment.indexOf(marker);
        if (start < 0) {
            return "";
        }
        start += marker.length();
        int end = segment.length();
        String[] markers = {" id=", " text=", " desc="};
        for (String nextMarker : markers) {
            int next = segment.indexOf(nextMarker, start);
            if (next >= 0 && next < end) {
                end = next;
            }
        }
        return NavTextNormalizer.cleanText(segment.substring(start, end));
    }

    //keeps this Google Maps step isolated so notification and accessibility evidence remain comparable.
    private static int timeSeconds(String text, int fallback) {
        Matcher matcher = MINUTES.matcher(NavTextNormalizer.cleanText(text));
        if (!matcher.find()) {
            return fallback;
        }
        try {
            int minutes = Integer.parseInt(matcher.group(1));
            if (minutes > 1666) {
                return 99999;
            }
            return minutes * 60;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    //keeps this Google Maps step isolated so notification and accessibility evidence remain comparable.
    private static String roadName(String topCue, String maneuverText) {
        String value = NavTextNormalizer.cleanText(topCue);
        String lower = NavTextNormalizer.lower(value);
        if (lower.startsWith("toward ")) {
            value = value.substring("toward ".length()).trim();
        }
        if (value.isEmpty()) {
            value = maneuverText;
        }
        return cap(value, MAX_ROAD_NAME_CHARS);
    }

    //keeps this Google Maps step isolated so notification and accessibility evidence remain comparable.
    private static String stripManeuverDistance(String value) {
        String clean = NavTextNormalizer.cleanText(value);
        String lower = NavTextNormalizer.lower(clean);
        if (lower.startsWith("then ")) {
            clean = clean.substring("then ".length()).trim();
        }
        return cap(IN_DISTANCE.matcher(clean).replaceAll("").trim(), MAX_ROAD_NAME_CHARS);
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

    //defines the Candidate module boundary so related behavior stays readable inside one unit.
    private static final class Candidate {
        final String text;
        final int distanceMeters;
        final boolean hasDistance;
        final int score;

        Candidate(String text, int distanceMeters, boolean hasDistance, int score) {
            this.text = text;
            this.distanceMeters = distanceMeters;
            this.hasDistance = hasDistance;
            this.score = score;
        }
    }

    //defines the ArrivalSignal module boundary so related behavior stays readable inside one unit.
    private static final class ArrivalSignal {
        boolean hasArrivingAt;
        boolean hasRestart;
        boolean hasSaveParking;
        boolean hasHowWasNavigation;
        boolean hasCloseButton;
        String destination = "";

        //keeps this Google Maps step isolated so notification and accessibility evidence remain comparable.
        void observe(String text, String desc) {
            observeValue(text, false);
            observeValue(desc, true);
        }

        //keeps this Google Maps step isolated so notification and accessibility evidence remain comparable.
        void observeValue(String value, boolean description) {
            String clean = NavTextNormalizer.cleanText(value);
            if (clean.isEmpty()) {
                return;
            }
            String lower = NavTextNormalizer.lower(clean);
            if (lower.startsWith("arriving at")) {
                hasArrivingAt = true;
                String suffix = clean.substring("Arriving at".length()).trim();
                if (!suffix.isEmpty()) {
                    destination = suffix;
                }
            } else if (hasArrivingAt && destination.isEmpty()
                    && !lower.equals("restart")
                    && !lower.equals("walk")
                    && !lower.equals("close")
                    && !lower.equals("save parking")
                    && !lower.startsWith("how was the navigation")) {
                destination = clean;
            }
            if (lower.equals("restart")) {
                hasRestart = true;
            }
            if (lower.equals("save parking")) {
                hasSaveParking = true;
            }
            if (lower.startsWith("how was the navigation")) {
                hasHowWasNavigation = true;
            }
            if (description && lower.equals("close")) {
                hasCloseButton = true;
            }
        }

        //keeps this predicate explicit so safety checks can be audited without tracing callers.
        boolean isArrival() {
            return hasArrivingAt
                    || (hasHowWasNavigation && (hasRestart || hasSaveParking || hasCloseButton));
        }
    }
}
