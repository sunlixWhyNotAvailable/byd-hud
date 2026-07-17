package com.bydhud.app;

//extracts google maps foreground text so notification gaps still provide distance and road evidence.

import java.util.regex.Matcher;
import java.util.regex.Pattern;

//defines the GMapsAccessibilityParser parser boundary so raw app evidence is normalized before HUD decisions use it.
final class GMapsAccessibilityParser {
    private static final int MAX_ROAD_NAME_CHARS = 64;
    private static final int UNKNOWN_NEXT_DISTANCE_METERS = 0;
    private static final Pattern MINUTES =
            Pattern.compile("([0-9]+)\\s*min\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern IN_DISTANCE =
            Pattern.compile("\\b(in|for)\\s+[0-9]+(?:[\\.,][0-9]+)?\\s*(km|m|\\u043a\\u043c|\\u043c)(?=$|\\s|[.,;:!?])",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern DISTANCE_TOKEN =
            Pattern.compile("[0-9]+(?:[\\.,][0-9]+)?\\s*"
                            + "(?:km|meters?|metres?|m|\\u043a\\u043c|\\u043c)"
                            + "(?=$|\\s|[.,;:!?])",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern UK_ROUNDABOUT_EXIT =
            Pattern.compile("(10|[1-9])-(?:\\u0438\\u0439|\\u0439|\\u0456\\u0439)\\s+"
                            + "\\u0437['\\u2019]\\u0457\\u0437\\u0434",
                    Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final String UK_TURN_LEFT =
            "\u043f\u043e\u0432\u0435\u0440\u043d\u0456\u0442\u044c "
                    + "\u043b\u0456\u0432\u043e\u0440\u0443\u0447";
    private static final String UK_TURN_RIGHT =
            "\u043f\u043e\u0432\u0435\u0440\u043d\u0456\u0442\u044c "
                    + "\u043f\u0440\u0430\u0432\u043e\u0440\u0443\u0447";
    private static final String UK_AT_ROUNDABOUT =
            "\u043d\u0430 \u043a\u0456\u043b\u044c\u0446\u0456";
    private static final int NUMBERED_ROUNDABOUT_MIN_SOURCE = 50;
    private static final int NUMBERED_ROUNDABOUT_MAX_SOURCE = 59;

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

        Candidate structuredBest = null;
        Candidate fallbackBest = null;
        DestinationApproach destinationApproach = null;
        boolean hasStructuredInstruction = false;
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
            boolean currentInstruction = isCurrentInstructionId(idLower);
            boolean nextInstruction = isNextInstructionId(idLower);
            hasStructuredInstruction |= currentInstruction || nextInstruction;
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

            if (isCurrentStepInstructionId(idLower)) {
                destinationApproach = better(
                        destinationApproach,
                        destinationApproach(desc));
                destinationApproach = better(
                        destinationApproach,
                        destinationApproach(text));
            }

            if (currentInstruction) {
                structuredBest = better(
                        structuredBest,
                        candidate(desc, scoreBaseForId(idLower, true)));
                structuredBest = better(
                        structuredBest,
                        candidate(text, scoreBaseForId(idLower, false)));
            } else if (!nextInstruction) {
                fallbackBest = better(
                        fallbackBest,
                        candidate(desc, scoreBaseForId(idLower, true)));
                fallbackBest = better(
                        fallbackBest,
                        candidate(text, scoreBaseForId(idLower, false)));
            }
        }

        if (arrival.isArrival()) {
            String reason = "gmaps accessibility arrival=\"" + arrival.destination + "\"";
            return GMapsNotificationParser.arrivalResult(
                    packageName,
                    arrival.destination,
                    reason,
                    95);
        }

        if (destinationApproach != null && destinationApproach.matches(topCue)) {
            String reason = "gmaps accessibility destination approach=\""
                    + destinationApproach.text + "\" topCue=\"" + topCue + "\"";
            return GMapsNotificationParser.arrivalResult(
                    packageName,
                    topCue,
                    reason,
                    95,
                    destinationApproach.distanceMeters);
        }

        Candidate best = hasStructuredInstruction ? structuredBest : fallbackBest;
        if (best == null || !best.hasDistance) {
            return hasStructuredInstruction
                    ? blankCurrentResult(packageName, topCue, structuredBest,
                            remainingMeters, timeSeconds, baseline)
                    : null;
        }

        String maneuverText = stripManeuverDistance(best.text);
        String streetText = roadName(topCue, "");
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
        state.roadName = streetText;
        state.directionText = maneuverText;
        state.guidePoint = "";
        state.navigationRatio = baseline == null ? 0.0d : baseline.navigationRatio;
        int roundaboutExit = roundaboutExitNumber(maneuverText, baseline);
        state.setSourceManeuver(sourceManeuver(maneuverText, roundaboutExit));

        NavSnapshot.Maneuver maneuver = maneuver(maneuverText);
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
                roundaboutExit,
                "",
                confidence,
                reason);
        return new GMapsNotificationParser.Result(state, snapshot, reason);
    }

    private static GMapsNotificationParser.Result blankCurrentResult(
            String packageName,
            String topCue,
            Candidate current,
            int remainingMeters,
            int timeSeconds,
            HudState baseline) {
        HudState state = new HudState();
        state.distanceToIntersection = UNKNOWN_NEXT_DISTANCE_METERS;
        state.navigationStatus = 2;
        state.crossStatus = 2;
        state.carToDestination = Math.max(0, remainingMeters);
        state.timeToDestination = Math.max(0, timeSeconds);
        state.currentMaxSpeedLimit = 0;
        state.currentSpeed = 0;
        state.numOfLanes = 0;
        state.includeLaneBitmap = false;
        state.laneString = "";
        state.roadName = NavTextNormalizer.cleanText(topCue);
        state.directionText = "";
        state.guidePoint = "";
        state.navigationRatio = baseline == null ? 0.0d : baseline.navigationRatio;
        state.hideNativeWithBlankId();
        state.hideTurnBitmapWithBlankSource();

        String instruction = current == null ? "" : current.text;
        String reason = "gmaps accessibility blank current=\"" + instruction
                + "\" topCue=\"" + state.roadName + "\"";
        NavSnapshot snapshot = new NavSnapshot(
                System.currentTimeMillis(),
                NavSnapshot.SourceApp.GOOGLE_MAPS,
                packageName,
                NavSnapshot.Maneuver.UNKNOWN,
                UNKNOWN_NEXT_DISTANCE_METERS,
                state.roadName,
                0,
                "",
                90,
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

    private static DestinationApproach better(
            DestinationApproach current, DestinationApproach candidate) {
        return current == null ? candidate : current;
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
            return 0;
        }
        return description ? 60 : 0;
    }

    private static boolean isCurrentInstructionId(String idLower) {
        return idLower.endsWith(":id/navigation_instruction_panel")
                || isCurrentStepInstructionId(idLower);
    }

    private static boolean isCurrentStepInstructionId(String idLower) {
        return idLower.endsWith(":id/step_instruction_container");
    }

    private static boolean isNextInstructionId(String idLower) {
        return idLower.endsWith(":id/next_step_instruction_container")
                || idLower.endsWith(":id/next_step_instruction");
    }

    private static DestinationApproach destinationApproach(String value) {
        String clean = NavTextNormalizer.cleanText(value);
        String lower = NavTextNormalizer.lower(clean);
        if (clean.isEmpty() || isTurnLike(lower) || isStraightLike(lower)) {
            return null;
        }
        int distanceMeters = NavTextNormalizer.distanceMeters(clean, -1);
        if (distanceMeters <= 0) {
            return null;
        }
        Matcher matcher = DISTANCE_TOKEN.matcher(clean);
        if (!matcher.find()) {
            return null;
        }
        String remainder = NavTextNormalizer.cleanText(
                clean.substring(matcher.end()).replaceFirst("^[\\s\\p{P}\\p{S}]+", ""));
        return remainder.isEmpty()
                ? null
                : new DestinationApproach(clean, distanceMeters, remainder);
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
                || lower.contains("roundabout")
                || lower.contains(UK_TURN_LEFT)
                || lower.contains(UK_TURN_RIGHT)
                || lower.contains(UK_AT_ROUNDABOUT);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isStraightLike(String lower) {
        return lower.startsWith("head ")
                || lower.startsWith("continue")
                || lower.contains("straight");
    }

    private static NavSnapshot.Maneuver maneuver(String text) {
        String lower = NavTextNormalizer.lower(text);
        if (lower.contains(UK_AT_ROUNDABOUT)) {
            return NavSnapshot.Maneuver.ROUNDABOUT_RIGHT_EXIT;
        }
        if (lower.contains(UK_TURN_LEFT)) {
            return NavSnapshot.Maneuver.LEFT_90;
        }
        if (lower.contains(UK_TURN_RIGHT)) {
            return NavSnapshot.Maneuver.RIGHT_90;
        }
        return GMapsNotificationParser.maneuver(text);
    }

    private static int sourceManeuver(String text, int roundaboutExit) {
        String lower = NavTextNormalizer.lower(text);
        if (lower.contains(UK_AT_ROUNDABOUT)) {
            if (roundaboutExit > 0) {
                return NUMBERED_ROUNDABOUT_MIN_SOURCE + roundaboutExit - 1;
            }
            return 21;
        }
        if (lower.contains(UK_TURN_LEFT)) {
            return 2;
        }
        if (lower.contains(UK_TURN_RIGHT)) {
            return 3;
        }
        return GMapsNotificationParser.sourceManeuver(text);
    }

    private static int roundaboutExitNumber(String text, HudState baseline) {
        if (!NavTextNormalizer.lower(text).contains(UK_AT_ROUNDABOUT)) {
            return 0;
        }
        Matcher matcher = UK_ROUNDABOUT_EXIT.matcher(text);
        if (!matcher.find()) {
            if (baseline != null
                    && baseline.turnBitmapId >= NUMBERED_ROUNDABOUT_MIN_SOURCE
                    && baseline.turnBitmapId <= NUMBERED_ROUNDABOUT_MAX_SOURCE) {
                return baseline.turnBitmapId - NUMBERED_ROUNDABOUT_MIN_SOURCE + 1;
            }
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
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

    private static final class DestinationApproach {
        final String text;
        final int distanceMeters;
        final String remainder;

        DestinationApproach(String text, int distanceMeters, String remainder) {
            this.text = text;
            this.distanceMeters = distanceMeters;
            this.remainder = remainder;
        }

        boolean matches(String topCue) {
            String cleanTopCue = NavTextNormalizer.cleanText(topCue);
            return !cleanTopCue.isEmpty()
                    && NavTextNormalizer.lower(remainder)
                    .equals(NavTextNormalizer.lower(cleanTopCue));
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
