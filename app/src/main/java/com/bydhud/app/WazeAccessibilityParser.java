package com.bydhud.app;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class WazeAccessibilityParser {
    private static final int MAX_ROAD_NAME_CHARS = 64;
    private static final int ARRIVAL_SOURCE_MANEUVER = 10;
    private static final Pattern MINUTES =
            Pattern.compile("([0-9]+)\\s*min\\b", Pattern.CASE_INSENSITIVE);

    private WazeAccessibilityParser() {
    }

    static NavParserResult parse(String packageName, String payload, HudState baseline) {
        if (NavTextNormalizer.sourceApp(packageName) != NavSnapshot.SourceApp.WAZE) {
            return null;
        }
        List<NavAccessibilityPayload.Node> nodes = NavAccessibilityPayload.nodes(payload);
        if (nodes.isEmpty()) {
            return null;
        }
        if (!isUsableWazePayload(nodes)) {
            return null;
        }

        String nextDistanceText = "";
        String roadName = "";
        String destinationDistanceText = "";
        String timeText = "";
        String arrivalText = "";
        String explicitManeuverText = "";
        String laneString = "";
        String arrivalDestination = "";
        boolean arrivalDetected = false;
        boolean nextTextIsArrivalDestination = false;

        for (NavAccessibilityPayload.Node node : nodes) {
            String nodeText = NavTextNormalizer.cleanText(node.text);
            String nodeDesc = NavTextNormalizer.cleanText(node.desc);
            if (node.idEndsWith(":id/navBarDistance")) {
                nextDistanceText = node.text;
            } else if (node.idEndsWith(":id/navBarStreetLine")) {
                roadName = routeLabel(nodeText);
            } else if (node.idEndsWith(":id/lblDistanceToDestination")) {
                destinationDistanceText = node.text;
            } else if (node.idEndsWith(":id/lblTimeToDestination")) {
                timeText = node.text;
            } else if (node.idEndsWith(":id/lblArrivalTime")) {
                arrivalText = node.text;
            } else if (node.idEndsWith(":id/pillViewLabel") && isManeuverText(node.text)) {
                explicitManeuverText = node.text;
            }
            String lowerText = NavTextNormalizer.lower(nodeText);
            String lowerDesc = NavTextNormalizer.lower(nodeDesc);
            if (arrivalDestination.isEmpty()) {
                if (nextTextIsArrivalDestination && !nodeText.isEmpty()) {
                    arrivalDestination = nodeText;
                    nextTextIsArrivalDestination = false;
                } else if (lowerText.startsWith("arriving at ")
                        || lowerDesc.startsWith("arriving at ")) {
                    arrivalDetected = true;
                    arrivalDestination = arrivingDestination(
                            nodeText.isEmpty() ? nodeDesc : nodeText);
                } else if ("arriving at".equals(lowerText) || "arriving at".equals(lowerDesc)) {
                    arrivalDetected = true;
                    nextTextIsArrivalDestination = true;
                }
            }
            String idLower = NavTextNormalizer.lower(node.id);
            if (laneString.isEmpty()
                    && (idLower.contains("lane")
                    || NavTextNormalizer.lower(node.text).contains(" lane"))) {
                laneString = WazeLaneParser.parseLaneText(node.text);
            }
        }

        int nextMeters = NavTextNormalizer.distanceMeters(nextDistanceText, -1);
        int parsedDestinationMeters = NavTextNormalizer.distanceMeters(destinationDistanceText, -1);
        int destinationMeters = parsedDestinationMeters >= 0
                ? parsedDestinationMeters
                : baseline == null ? 0 : Math.max(0, baseline.carToDestination);
        int timeSeconds = timeSeconds(timeText,
                baseline == null ? 0 : Math.max(0, baseline.timeToDestination));
        String cleanRoad = cap(roadName, MAX_ROAD_NAME_CHARS);
        boolean positiveRouteDistance = nextMeters > 0 || parsedDestinationMeters > 0;
        if (arrivalDetected && !positiveRouteDistance) {
            return arrivalResult(packageName, arrivalDestination, cleanRoad,
                    "waze accessibility arrival", 90);
        }
        if (nextMeters < 0 && destinationMeters <= 0 && cleanRoad.isEmpty()) {
            return null;
        }

        HudState state = new HudState();
        state.distanceToIntersection = Math.max(0, nextMeters);
        state.navigationStatus = 2;
        state.crossStatus = 2;
        state.carToDestination = Math.max(0, destinationMeters);
        state.timeToDestination = Math.max(0, timeSeconds);
        state.currentMaxSpeedLimit = 0;
        state.currentSpeed = 0;
        int laneCount = WazeLaneParser.laneCountFromSignature(laneString);
        if (laneCount > 1) {
            state.laneString = laneString;
            state.numOfLanes = laneCount;
            state.includeLaneBitmap = true;
        } else {
            state.laneString = "";
            state.numOfLanes = 0;
            state.includeLaneBitmap = false;
        }
        state.roadName = cleanRoad;
        state.guidePoint = "";
        state.navigationRatio = baseline == null ? 0.0d : baseline.navigationRatio;

        NavSnapshot.Maneuver maneuver = maneuver(explicitManeuverText);
        if (maneuver == NavSnapshot.Maneuver.UNKNOWN) {
            state.setSourceManeuver(HudState.TURN_BITMAP_BLANK_SOURCE_ID);
        } else {
            state.setSourceManeuver(sourceManeuver(explicitManeuverText));
        }

        int confidence = 35;
        if (nextMeters >= 0) {
            confidence += 20;
        }
        if (!cleanRoad.isEmpty()) {
            confidence += 10;
        }
        if (maneuver != NavSnapshot.Maneuver.UNKNOWN) {
            confidence += 35;
        }
        confidence = Math.min(100, confidence);
        String reason = "waze accessibility distance=\"" + nextDistanceText
                + "\" road=\"" + cleanRoad
                + "\" maneuver=\"" + explicitManeuverText
                + "\" lanes=\"" + laneString
                + "\" eta=\"" + arrivalText + "\"";
        return new NavParserResult(
                state,
                new NavSnapshot(
                        System.currentTimeMillis(),
                        NavSnapshot.SourceApp.WAZE,
                        packageName,
                        maneuver,
                        nextMeters >= 0 ? Math.max(0, nextMeters) : 0,
                        cleanRoad,
                        0,
                        laneString,
                        confidence,
                        reason),
                reason);
    }

    static boolean isUsableWazePayload(String payload) {
        return isUsableWazePayload(NavAccessibilityPayload.nodes(payload));
    }

    static boolean isUsableWazePayloadForTest(String payload) {
        return isUsableWazePayload(payload);
    }

    static WazeAccessibilityGeometry geometry(String payload) {
        return WazeAccessibilityGeometry.fromPayload(payload);
    }

    static NavParserResult arrivalResult(String packageName, String destination,
            String fallbackRoad, String reason, int confidence) {
        String cleanDestination = cap(
                NavTextNormalizer.cleanText(destination).isEmpty() ? fallbackRoad : destination,
                MAX_ROAD_NAME_CHARS);
        HudState state = new HudState();
        state.distanceToIntersection = 0;
        state.navigationStatus = 2;
        state.crossStatus = 2;
        state.carToDestination = 0;
        state.timeToDestination = 0;
        state.currentMaxSpeedLimit = 0;
        state.currentSpeed = 0;
        state.numOfLanes = 0;
        state.includeLaneBitmap = false;
        state.laneString = "";
        state.roadName = cleanDestination;
        state.guidePoint = "";
        state.navigationRatio = 1.0d;
        state.setSourceManeuver(ARRIVAL_SOURCE_MANEUVER);

        String detail = reason + " arrival=\"" + cleanDestination + "\"";
        return new NavParserResult(
                state,
                new NavSnapshot(
                        System.currentTimeMillis(),
                        NavSnapshot.SourceApp.WAZE,
                        packageName,
                        NavSnapshot.Maneuver.ARRIVE,
                        0,
                        cleanDestination,
                        0,
                        "",
                        confidence,
                        detail),
                detail);
    }

    static boolean isManeuverText(String value) {
        String lower = NavTextNormalizer.lower(value);
        return lower.contains("turn ")
                || lower.contains("left")
                || lower.contains("right")
                || lower.contains("u-turn")
                || lower.contains("uturn")
                || lower.contains("exit")
                || lower.contains("ramp")
                || lower.contains("roundabout")
                || lower.startsWith("keep ");
    }

    static int sourceManeuver(String text) {
        return GMapsNotificationParser.sourceManeuver(text);
    }

    static NavSnapshot.Maneuver maneuver(String text) {
        if (!isManeuverText(text)) {
            return NavSnapshot.Maneuver.UNKNOWN;
        }
        return GMapsNotificationParser.maneuver(text);
    }

    private static boolean isUsableWazePayload(List<NavAccessibilityPayload.Node> nodes) {
        boolean hasWazeRouteNode = false;
        boolean hasForeignMeaningfulNode = false;
        for (NavAccessibilityPayload.Node node : nodes) {
            String idLower = NavTextNormalizer.lower(node.id);
            if (idLower.startsWith("com.waze:id/")) {
                if (idLower.contains("navbar")
                        || idLower.contains("lbl")
                        || idLower.contains("pillview")
                        || idLower.contains("lane")) {
                    hasWazeRouteNode = true;
                }
            } else if (isForeignResourceId(idLower)) {
                hasForeignMeaningfulNode = true;
            }
        }
        if (hasForeignMeaningfulNode) {
            return false;
        }
        return hasWazeRouteNode || !hasForeignMeaningfulNode;
    }

    private static boolean isInstructionLikeStreet(String value) {
        String lower = NavTextNormalizer.lower(value);
        if (lower.isEmpty()) {
            return false;
        }
        return lower.equals("continue")
                || lower.equals("turn")
                || lower.equals("keep")
                || lower.equals("take")
                || lower.equals("continue straight")
                || lower.startsWith("continue ")
                || lower.startsWith("turn ")
                || lower.startsWith("keep ")
                || lower.startsWith("take ")
                || lower.contains("u-turn")
                || lower.contains("roundabout")
                || lower.contains("arriving at");
    }

    private static String routeLabel(String value) {
        String clean = NavTextNormalizer.cleanText(value);
        String lower = NavTextNormalizer.lower(clean);
        if (lower.equals("continue straight") || lower.startsWith("continue straight ")) {
            return clean;
        }
        return isInstructionLikeStreet(clean) ? "" : clean;
    }

    private static boolean isForeignResourceId(String idLower) {
        if (idLower.isEmpty() || idLower.startsWith("com.waze:id/")) {
            return false;
        }
        return idLower.indexOf(":id/") > 0;
    }

    private static int timeSeconds(String text, int fallback) {
        Matcher matcher = MINUTES.matcher(NavTextNormalizer.cleanText(text));
        if (!matcher.find()) {
            return fallback;
        }
        try {
            int minutes = Integer.parseInt(matcher.group(1));
            return Math.min(99999, minutes * 60);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String cap(String value, int limit) {
        String clean = NavTextNormalizer.cleanText(value);
        if (clean.length() <= limit) {
            return clean;
        }
        return clean.substring(0, limit);
    }

    private static String arrivingDestination(String value) {
        String clean = NavTextNormalizer.cleanText(value);
        String lower = NavTextNormalizer.lower(clean);
        if (!lower.startsWith("arriving at ")) {
            return clean;
        }
        return clean.substring("Arriving at ".length()).trim();
    }
}
