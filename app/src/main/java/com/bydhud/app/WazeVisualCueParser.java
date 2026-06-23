package com.bydhud.app;

//combines template and geometry evidence so Waze maneuvers stay robust across glyph variants.

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

//defines the WazeVisualCueParser parser boundary so raw app evidence is normalized before HUD decisions use it.
final class WazeVisualCueParser {
    private static final String WAZE_PACKAGE = "com.waze";
    private static final int ARRIVAL_SOURCE_MANEUVER = 10;
    private static final int ROUNDABOUT_RIGHT_HAND_BASE_SOURCE = 50;
    private static final int ROUNDABOUT_LEFT_HAND_BASE_SOURCE = 60;
    private static final int ROUNDABOUT_MIN_EXIT = 1;
    private static final int ROUNDABOUT_MAX_EXIT = 10;
    private static final double UTURN_MIN_BOTTOM_DELTA = 7.25d;
    private static final int DIGIT_ROWS = 7;
    private static final int DIGIT_COLS = 5;
    private static final String[][] DIGIT_TEMPLATES = {
            {"11111", "10001", "10001", "10001", "10001", "10001", "11111"},
            {"00100", "01100", "00100", "00100", "00100", "00100", "01110"},
            {"01111", "11111", "11011", "00111", "01110", "11100", "11111"},
            {"01111", "11111", "00011", "00111", "00111", "11011", "11111"},
            {"10010", "10010", "10010", "11111", "00010", "00010", "00010"},
            {"11111", "10000", "10000", "11110", "00001", "00001", "11110"},
            {"01111", "10000", "10000", "11110", "10001", "10001", "01110"},
            {"11111", "00001", "00010", "00100", "01000", "01000", "01000"},
            {"01110", "10001", "10001", "01110", "10001", "10001", "01110"},
            {"01110", "10001", "10001", "01111", "00001", "00001", "11110"}
    };
    private static final Set<String> KNOWN_LANE_GLYPHS = new HashSet<>(Arrays.asList(
            "S", "S*",
            "L", "L*",
            "R", "R*",
            "U", "U*",
            "UR", "UR*",
            "S+R", "S+R*", "S*+R", "S*+R*",
            "S+L", "S+L*", "S*+L", "S*+L*",
            "Rs", "Rs*",
            "Ls", "Ls*",
            "S+Rs", "S+Rs*", "S*+Rs", "S*+Rs*",
            "S+Ls", "S+Ls*", "S*+Ls", "S*+Ls*",
            "Ls+L", "Ls+L*", "Ls*+L", "Ls*+L*",
            "Rs+R", "Rs+R*", "Rs*+R", "Rs*+R*",
            "L+Rs", "L+Rs*", "L*+Rs",
            "Ls+R", "Ls+R*", "Ls*+R",
            "S+L+R", "S+L+R*", "S+L*+R", "S*+L+R",
            "RampL*", "RampR*"));
    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private WazeVisualCueParser() {
    }

    //defines the LaneGuidanceStatus module boundary so related behavior stays readable inside one unit.
    enum LaneGuidanceStatus {
        NONE,
        PARSED,
        UNPARSED_ROW
    }

    //defines the LaneFailureReason module boundary so related behavior stays readable inside one unit.
    enum LaneFailureReason {
        NONE,
        NO_ACTIVE_PANEL,
        ARRIVAL_PANEL,
        NO_LANE_STRIP,
        NO_DIVIDERS,
        BAD_CELL_GRID,
        NO_COMPONENTS,
        EMPTY_CELL,
        UNKNOWN_GLYPH,
        UNKNOWN_MANEUVER,
        ALL_STRAIGHT,
        SINGLE_LANE,
        FALSE_ROW_SINGLE_CARD,
        BITMAP_DECODE_UNAVAILABLE,
        BITMAP_DECODE_FAILED
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    static NavParserResult parseScreenshot(File screenshot, NavSnapshot baseline) {
        return parseScreenshot(screenshot, baseline, null);
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    static NavParserResult parseScreenshot(
            File screenshot,
            NavSnapshot baseline,
            LaneGuidanceAnalysis laneAnalysis) {
        return parseScreenshot(screenshot, baseline, laneAnalysis, WazeAccessibilityGeometry.EMPTY);
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    static NavParserResult parseScreenshot(
            File screenshot,
            NavSnapshot baseline,
            LaneGuidanceAnalysis laneAnalysis,
            WazeAccessibilityGeometry geometry) {
        return parseScreenshot(screenshot, baseline, laneAnalysis, geometry, false);
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    static NavParserResult parseScreenshot(
            File screenshot,
            NavSnapshot baseline,
            LaneGuidanceAnalysis laneAnalysis,
            WazeAccessibilityGeometry geometry,
            boolean leftHandRoundaboutTraffic) {
        if (screenshot == null || !screenshot.exists()) {
            return null;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
        if (bitmap == null) {
            return null;
        }
        try {
            Cue cue = parseBitmap(bitmap, laneAnalysis, geometry, leftHandRoundaboutTraffic);
            if (cue == null) {
                return null;
            }
            return toParserResult(cue, baseline);
        } finally {
            bitmap.recycle();
        }
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static String laneCueForTest(
            int[] widths,
            int[] counts,
            int[] whiteCounts,
            double[] topAverageX,
            double[] midAverageX,
            double[] bottomAverageX) {
        int length = widths == null ? 0 : widths.length;
        List<Component> components = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            components.add(new Component(
                    0,
                    Math.max(0, widths[i] - 1),
                    0,
                    0,
                    value(counts, i),
                    value(whiteCounts, i),
                    value(topAverageX, i),
                    value(midAverageX, i),
                    value(bottomAverageX, i)));
        }
        return laneCue(components);
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static String screenLaneCueForTest(
            int[] x1s,
            int[] widths,
            int[] counts,
            int[] whiteCounts,
            double[] topAverageX,
            double[] midAverageX,
            double[] bottomAverageX) {
        int length = widths == null ? 0 : widths.length;
        List<Component> components = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            int x1 = value(x1s, i);
            components.add(new Component(
                    x1,
                    x1 + Math.max(0, widths[i] - 1),
                    0,
                    0,
                    value(counts, i),
                    value(whiteCounts, i),
                    value(topAverageX, i),
                    value(midAverageX, i),
                    value(bottomAverageX, i)));
        }
        return screenLaneCue(components, 150);
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static String glyphTokenForTest(boolean hasStraight, boolean straightRecommended,
            boolean hasLeft, boolean leftRecommended,
            boolean hasRight, boolean rightRecommended) {
        String token;
        if (hasStraight && hasRight && !hasLeft) {
            token = compoundGlyph("S", straightRecommended, "R", rightRecommended);
        } else if (hasStraight && hasLeft && !hasRight) {
            token = compoundGlyph("S", straightRecommended, "L", leftRecommended);
        } else if (hasStraight && !hasLeft && !hasRight) {
            token = laneGlyph("S", straightRecommended);
        } else if (hasLeft && !hasStraight && !hasRight) {
            token = laneGlyph("L", leftRecommended);
        } else if (hasRight && !hasStraight && !hasLeft) {
            token = laneGlyph("R", rightRecommended);
        } else {
            return "";
        }
        return KNOWN_LANE_GLYPHS.contains(token) ? token : "";
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static String smoothGlyphTokenForTest(boolean hasStraight, boolean straightRecommended,
            boolean hasSmoothLeft, boolean smoothLeftRecommended,
            boolean hasSmoothRight, boolean smoothRightRecommended) {
        String token;
        if (hasStraight && hasSmoothRight && !hasSmoothLeft) {
            token = compoundGlyph("S", straightRecommended, "Rs", smoothRightRecommended);
        } else if (hasStraight && hasSmoothLeft && !hasSmoothRight) {
            token = compoundGlyph("S", straightRecommended, "Ls", smoothLeftRecommended);
        } else if (hasSmoothLeft && !hasStraight && !hasSmoothRight) {
            token = laneGlyph("Ls", smoothLeftRecommended);
        } else if (hasSmoothRight && !hasStraight && !hasSmoothLeft) {
            token = laneGlyph("Rs", smoothRightRecommended);
        } else {
            return "";
        }
        return KNOWN_LANE_GLYPHS.contains(token) ? token : "";
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static NavSnapshot.Maneuver maneuverFromLaneCueForTest(String laneString) {
        return maneuverFromLaneCue(laneString);
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static NavSnapshot.Maneuver maneuverFromSingleTokenForTest(String token) {
        return maneuverFromSingleToken(token);
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static String singleManeuverTokenForTest(int width, int count, int whiteCount,
            double topAverageX, double midAverageX, double bottomAverageX) {
        Component component = new Component(
                0,
                Math.max(0, width - 1),
                0,
                0,
                count,
                whiteCount,
                topAverageX,
                midAverageX,
                bottomAverageX);
        return singleManeuverToken(component);
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static int sourceFromManeuverForTest(NavSnapshot.Maneuver maneuver) {
        return sourceFromManeuver(maneuver);
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static int roundaboutSourceForExitForTest(int exitNumber, boolean leftHandTraffic) {
        return roundaboutSourceForExit(exitNumber, leftHandTraffic);
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static Set<String> knownLaneGlyphsForTest() {
        return Collections.unmodifiableSet(KNOWN_LANE_GLYPHS);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isKnownMultiLaneSignature(String laneString) {
        String clean = laneString == null ? "" : laneString.trim();
        if (WazeLaneParser.laneCountFromSignature(clean) <= 1 || !hasDirectionalLane(clean)) {
            return false;
        }
        String[] tokens = clean.split("\\|", -1);
        for (String rawToken : tokens) {
            String token = rawToken.trim();
            if (token.isEmpty() || !KNOWN_LANE_GLYPHS.contains(token)) {
                return false;
            }
        }
        return true;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean hasVisualNavigationCueCandidate(File screenshot) {
        if (screenshot == null || !screenshot.exists()) {
            return false;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
        if (bitmap == null) {
            return false;
        }
        try {
            return hasVisualNavigationCueCandidate(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean hasLaneGuidanceCandidate(File screenshot) {
        LaneGuidanceStatus status = laneGuidanceStatus(screenshot);
        return status == LaneGuidanceStatus.PARSED || status == LaneGuidanceStatus.UNPARSED_ROW;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    static LaneGuidanceStatus laneGuidanceStatus(File screenshot) {
        return analyzeLaneGuidance(screenshot).status;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    static LaneGuidanceAnalysis analyzeLaneGuidance(File screenshot) {
        return analyzeLaneGuidance(screenshot, WazeAccessibilityGeometry.EMPTY);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    static LaneGuidanceAnalysis analyzeLaneGuidance(
            File screenshot,
            WazeAccessibilityGeometry geometry) {
        if (screenshot == null || !screenshot.exists()) {
            return LaneGuidanceAnalysis.none(LaneFailureReason.NO_ACTIVE_PANEL);
        }
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
        } catch (NoClassDefFoundError e) {
            return LaneGuidanceAnalysis.none(LaneFailureReason.BITMAP_DECODE_UNAVAILABLE);
        } catch (RuntimeException e) {
            return LaneGuidanceAnalysis.none(LaneFailureReason.BITMAP_DECODE_FAILED);
        }
        if (bitmap == null) {
            return LaneGuidanceAnalysis.none(LaneFailureReason.BITMAP_DECODE_FAILED);
        }
        try {
            return analyzeLaneGuidance(bitmap, geometry);
        } finally {
            bitmap.recycle();
        }
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static String visualLayoutForTest(File screenshot) {
        if (screenshot == null || !screenshot.exists()) {
            return WazeVisualLayout.NO_ACTIVE_PANEL.name();
        }
        Bitmap bitmap;
        try {
            bitmap = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
        } catch (NoClassDefFoundError | RuntimeException e) {
            return WazeVisualLayout.UNKNOWN.name();
        }
        if (bitmap == null) {
            return WazeVisualLayout.UNKNOWN.name();
        }
        try {
            return visualLayout(bitmap).name();
        } finally {
            bitmap.recycle();
        }
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static LaneGuidanceAnalysisForTest analyzeLaneGuidanceForTest(File screenshot) {
        return LaneGuidanceAnalysisForTest.from(analyzeLaneGuidance(screenshot));
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static LaneGuidanceAnalysisForTest analyzeLaneComponentsDirectForTest(
            int[] x1s,
            int[] widths,
            int[] counts,
            int[] whiteCounts,
            double[] topAverageX,
            double[] midAverageX,
            double[] bottomAverageX) {
        return analyzeLaneComponentsDirectForTest(
                x1s,
                null,
                widths,
                null,
                counts,
                whiteCounts,
                topAverageX,
                midAverageX,
                bottomAverageX);
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static LaneGuidanceAnalysisForTest analyzeLaneComponentsDirectForTest(
            int[] x1s,
            int[] y1s,
            int[] widths,
            int[] heights,
            int[] counts,
            int[] whiteCounts,
            double[] topAverageX,
            double[] midAverageX,
            double[] bottomAverageX) {
        int length = widths == null ? 0 : widths.length;
        List<Component> components = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            int x1 = value(x1s, i);
            int y1 = y1s == null ? 0 : value(y1s, i);
            int height = heights == null ? 40 : Math.max(0, value(heights, i));
            components.add(new Component(
                    x1,
                    x1 + Math.max(0, widths[i] - 1),
                    y1,
                    y1 + Math.max(0, height - 1),
                    value(counts, i),
                    value(whiteCounts, i),
                    value(topAverageX, i),
                    value(midAverageX, i),
                    value(bottomAverageX, i)));
        }
        return LaneGuidanceAnalysisForTest.from(analyzeLaneComponentsDirect(null, components, 0, 0));
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean hasActiveInstructionPanel(File screenshot) {
        if (screenshot == null || !screenshot.exists()) {
            return false;
        }
        Bitmap bitmap = BitmapFactory.decodeFile(screenshot.getAbsolutePath());
        if (bitmap == null) {
            return false;
        }
        try {
            return hasActiveInstructionPanel(bitmap);
        } finally {
            bitmap.recycle();
        }
    }

    //exposes this helper so parser behavior can be verified without depending on Android runtime state.
    static boolean activeInstructionPanelForTest(int darkPixels, int sampledPixels) {
        return darkPixels >= minInstructionPanelDarkPixels(sampledPixels);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isArrivalCueForTest(int redCount, int whiteCount, int grayCount) {
        return isArrivalCue(redCount, whiteCount, grayCount);
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static Cue parseBitmap(
            Bitmap bitmap,
            LaneGuidanceAnalysis laneAnalysis,
            WazeAccessibilityGeometry geometry,
            boolean leftHandRoundaboutTraffic) {
        ColorCounts arrival = colorCounts(bitmap, 30, 130, 130, 220);
        if (isArrivalCue(arrival.red, arrival.white, arrival.gray)) {
            return Cue.arrival();
        }
        if (!hasActiveInstructionPanel(bitmap)) {
            return null;
        }

        LaneGuidanceAnalysis lane = laneAnalysis == null ? analyzeLaneGuidance(bitmap) : laneAnalysis;
        if (lane.status == LaneGuidanceStatus.PARSED) {
            return lane.cue;
        }
        if (lane.status == LaneGuidanceStatus.UNPARSED_ROW && lane.blocksSingleFallback) {
            return null;
        }
        return parseSingleCue(bitmap, geometry, false, leftHandRoundaboutTraffic);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasVisualNavigationCueCandidate(Bitmap bitmap) {
        ColorCounts arrival = colorCounts(bitmap, 30, 130, 130, 220);
        if (isArrivalCue(arrival.red, arrival.white, arrival.gray)) {
            return true;
        }
        if (!hasActiveInstructionPanel(bitmap)) {
            return false;
        }
        LaneGuidanceAnalysis lane = analyzeLaneGuidance(bitmap);
        if (lane.status != LaneGuidanceStatus.NONE) {
            return true;
        }
        List<Component> mainComponents = components(bitmap, 20, 95, 190, 230);
        if (mainComponents.isEmpty()) {
            return false;
        }
        Component largest = primaryCueComponent(mainComponents, scaleX(bitmap, 140));
        return largest != null && largest.count >= 100 && largest.width() >= scaleX(bitmap, 10);
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static Cue parseLaneCue(Bitmap bitmap) {
        LaneGuidanceAnalysis lane = analyzeLaneGuidance(bitmap);
        return lane.status == LaneGuidanceStatus.PARSED ? lane.cue : null;
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static Cue parseSingleCue(Bitmap bitmap, boolean uturnOnly) {
        return parseSingleCue(bitmap, WazeAccessibilityGeometry.EMPTY, uturnOnly, false);
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static Cue parseSingleCue(
            Bitmap bitmap,
            WazeAccessibilityGeometry geometry,
            boolean uturnOnly) {
        return parseSingleCue(bitmap, geometry, uturnOnly, false);
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static Cue parseSingleCue(
            Bitmap bitmap,
            WazeAccessibilityGeometry geometry,
            boolean uturnOnly,
            boolean leftHandRoundaboutTraffic) {
        Cue roundaboutCue = uturnOnly ? null : roundaboutCue(bitmap, leftHandRoundaboutTraffic);
        if (roundaboutCue != null) {
            return roundaboutCue;
        }
        Cue geometryCue = parseSingleCueFromBounds(bitmap, geometry, uturnOnly);
        if (geometryCue != null) {
            return geometryCue;
        }
        List<Component> mainComponents = components(bitmap, 20, 95, 190, 230);
        if (!mainComponents.isEmpty()) {
            Component largest = primaryCueComponent(mainComponents, scaleX(bitmap, 140));
            String direction = templateToken(bitmap, largest, true);
            if (direction.isEmpty()) {
                direction = singleManeuverToken(largest);
            }
            NavSnapshot.Maneuver maneuver = maneuverFromSingleToken(direction);
            if (isRoundaboutManeuver(maneuver)) {
                return null;
            }
            if (uturnOnly
                    && maneuver != NavSnapshot.Maneuver.UTURN_LEFT
                    && maneuver != NavSnapshot.Maneuver.UTURN_RIGHT) {
                return null;
            }
            if (maneuver != NavSnapshot.Maneuver.UNKNOWN) {
                return Cue.maneuver(maneuver, sourceFromManeuver(maneuver));
            }
        }
        return null;
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static Cue parseSingleCueFromBounds(
            Bitmap bitmap,
            WazeAccessibilityGeometry geometry,
            boolean uturnOnly) {
        if (bitmap == null || geometry == null || !geometry.hasDirectionBounds()) {
            return null;
        }
        Rect rect = clampRect(geometry.directionBounds, bitmap);
        if (rect == null || rect.width() < scaleX(bitmap, 10) || rect.height() < scaleY(bitmap, 16)) {
            return null;
        }
        String templateDirection = templateToken(bitmap, rect, true);
        if (!templateDirection.isEmpty()) {
            NavSnapshot.Maneuver maneuver = maneuverFromSingleToken(templateDirection);
            if (isRoundaboutManeuver(maneuver)) {
                return null;
            }
            if (uturnOnly
                    && maneuver != NavSnapshot.Maneuver.UTURN_LEFT
                    && maneuver != NavSnapshot.Maneuver.UTURN_RIGHT) {
                return null;
            }
            if (maneuver != NavSnapshot.Maneuver.UNKNOWN) {
                return Cue.maneuver(maneuver, sourceFromManeuver(maneuver));
            }
        }
        List<Component> components = componentsRaw(bitmap, rect.left, rect.top, rect.right, rect.bottom);
        if (components.isEmpty()) {
            return null;
        }
        Component largest = primaryCueComponent(components, Integer.MAX_VALUE);
        String direction = singleManeuverToken(largest);
        NavSnapshot.Maneuver maneuver = maneuverFromSingleToken(direction);
        if (isRoundaboutManeuver(maneuver)) {
            return null;
        }
        if (uturnOnly
                && maneuver != NavSnapshot.Maneuver.UTURN_LEFT
                && maneuver != NavSnapshot.Maneuver.UTURN_RIGHT) {
            return null;
        }
        if (maneuver == NavSnapshot.Maneuver.UNKNOWN) {
            return null;
        }
        return Cue.maneuver(maneuver, sourceFromManeuver(maneuver));
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static Component primaryCueComponent(List<Component> components, int maxX) {
        Component largest = null;
        for (Component component : components) {
            if (component.x1 > maxX) {
                continue;
            }
            if (largest == null || component.count > largest.count) {
                largest = component;
            }
        }
        return largest == null ? largest(components) : largest;
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static NavParserResult toParserResult(Cue cue, NavSnapshot baseline) {
        String packageName = baseline == null || baseline.packageName.isEmpty()
                ? WAZE_PACKAGE : baseline.packageName;
        String roadName = baseline == null ? "" : baseline.streetName;
        if (cue.maneuver == NavSnapshot.Maneuver.ARRIVE && roadName.isEmpty()) {
            roadName = "Arrive";
        }
        int distanceMeters = baseline == null ? 0 : Math.max(0, baseline.distanceMeters);

        HudState state = new HudState();
        state.distanceToIntersection =
                cue.maneuver == NavSnapshot.Maneuver.ARRIVE ? 0 : distanceMeters;
        state.navigationStatus = 2;
        state.crossStatus = 2;
        state.carToDestination = 0;
        state.timeToDestination = 0;
        state.currentMaxSpeedLimit = 0;
        state.currentSpeed = 0;
        state.roadName = roadName;
        state.guidePoint = "";
        state.navigationRatio = cue.maneuver == NavSnapshot.Maneuver.ARRIVE ? 1.0d : 0.0d;
        state.setSourceManeuver(cue.sourceManeuver);

        int laneCount = WazeLaneParser.laneCountFromSignature(cue.laneString);
        if (laneCount > 1) {
            state.laneString = cue.laneString;
            state.numOfLanes = laneCount;
            state.includeLaneBitmap = true;
        } else {
            state.laneString = "";
            state.numOfLanes = 0;
            state.includeLaneBitmap = false;
        }

        int confidence = cue.maneuver == NavSnapshot.Maneuver.ARRIVE
                ? 92 : (laneCount > 1 ? 88 : 80);
        String reason = "waze visual cue maneuver=\"" + cue.maneuver.name()
                + "\" lanes=\"" + cue.laneString
                + "\" roundaboutExit=" + cue.roundaboutExitNumber;
        return new NavParserResult(
                state,
                new NavSnapshot(
                        System.currentTimeMillis(),
                        NavSnapshot.SourceApp.WAZE,
                        packageName,
                        cue.maneuver,
                        state.distanceToIntersection,
                        roadName,
                        cue.roundaboutExitNumber,
                        laneCount > 1 ? cue.laneString : "",
                        confidence,
                        reason),
                reason);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String laneCue(List<Component> components) {
        if (components == null || components.size() < 2) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < components.size(); i++) {
            Component component = components.get(i);
            String token = glyphToken(component);
            if (token.isEmpty() || !KNOWN_LANE_GLYPHS.contains(token)) {
                return "";
            }
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append(token);
        }
        return builder.toString();
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String screenLaneCue(List<Component> components, int minX) {
        if (components == null || components.size() < 2) {
            return "";
        }
        List<Component> filtered = new ArrayList<>();
        for (Component component : components) {
            if (component.x1 >= minX) {
                filtered.add(component);
            }
        }
        String cue = laneCue(filtered);
        if (cue.isEmpty() || !hasDirectionalLane(cue)) {
            return "";
        }
        return cue;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String screenLaneCueForBitmap(
            Bitmap bitmap,
            List<Component> components,
            LaneCropProfile profile) {
        if (bitmap == null || components == null || components.size() < 2 || profile == null) {
            return "";
        }
        LaneGuidanceAnalysis lane = analyzeLaneGuidance(bitmap);
        return lane.status == LaneGuidanceStatus.PARSED ? lane.laneString : "";
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasLaneGuidanceLayoutForBitmap(
            Bitmap bitmap,
            List<Component> components,
            LaneCropProfile profile) {
        if (bitmap == null || components == null || components.size() < 2 || profile == null) {
            return false;
        }
        return !laneDividerColumns(bitmap, profile).isEmpty();
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static LaneGuidanceAnalysis analyzeLaneGuidance(Bitmap bitmap) {
        return analyzeLaneGuidance(bitmap, WazeAccessibilityGeometry.EMPTY);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static LaneGuidanceAnalysis analyzeLaneGuidance(
            Bitmap bitmap,
            WazeAccessibilityGeometry geometry) {
        WazeVisualLayout layout = visualLayout(bitmap);
        if (layout == WazeVisualLayout.ARRIVAL) {
            return LaneGuidanceAnalysis.none(LaneFailureReason.ARRIVAL_PANEL);
        }
        if (layout == WazeVisualLayout.NO_ACTIVE_PANEL) {
            return LaneGuidanceAnalysis.none(LaneFailureReason.NO_ACTIVE_PANEL);
        }
        LaneGuidanceAnalysis boundsLane = analyzeLaneGuidanceFromBounds(bitmap, geometry);
        if (isBlockingLaneAnalysis(boundsLane)) {
            return boundsLane;
        }
        if (layout == WazeVisualLayout.SINGLE_MANEUVER) {
            return LaneGuidanceAnalysis.none(LaneFailureReason.FALSE_ROW_SINGLE_CARD);
        }
        if (layout != WazeVisualLayout.LANES) {
            return LaneGuidanceAnalysis.none(LaneFailureReason.NO_LANE_STRIP);
        }
        return analyzeLaneRows(bitmap);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static LaneGuidanceAnalysis analyzeLaneGuidanceFromBounds(
            Bitmap bitmap,
            WazeAccessibilityGeometry geometry) {
        if (bitmap == null || geometry == null || !geometry.hasLaneGuidanceBounds()) {
            return LaneGuidanceAnalysis.none(LaneFailureReason.NO_LANE_STRIP);
        }
        Rect rect = clampRect(geometry.laneGuidanceBounds, bitmap);
        if (rect == null || rect.width() < scaleX(bitmap, 80) || rect.height() < scaleY(bitmap, 24)) {
            return LaneGuidanceAnalysis.none(LaneFailureReason.BAD_CELL_GRID);
        }
        if (!hasDarkPanelRaw(bitmap, rect)) {
            return LaneGuidanceAnalysis.none(LaneFailureReason.NO_LANE_STRIP);
        }
        List<Component> components = componentsRaw(bitmap, rect.left, rect.top, rect.right, rect.bottom);
        LaneGuidanceAnalysis direct = analyzeLaneComponentsDirect(bitmap, components, 0, 0);
        if (isBlockingLaneAnalysis(direct)) {
            return direct;
        }
        if (direct.componentCount >= 2) {
            return LaneGuidanceAnalysis.unparsed(
                    direct.reason,
                    0,
                    0,
                    direct.componentCount);
        }
        return direct;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static LaneGuidanceAnalysis analyzeLaneRows(Bitmap bitmap) {
        LaneGuidanceAnalysis fallback = LaneGuidanceAnalysis.none(LaneFailureReason.NO_LANE_STRIP);
        for (LaneCropProfile profile : laneCropProfiles(bitmap)) {
            if (!hasLaneStripPanel(bitmap, profile)) {
                continue;
            }
            List<Component> components = laneComponents(bitmap, profile);
            List<Component> directComponents = laneComponentsAfterMinX(bitmap, components, profile);
            List<Integer> dividers = laneDividerColumns(bitmap, profile);
            if (dividers.isEmpty()) {
                LaneGuidanceAnalysis direct = analyzeLaneComponentsDirect(bitmap, directComponents, 0, 0);
                if (isBlockingLaneAnalysis(direct)) {
                    return direct;
                }
                fallback = rememberLaneFallback(
                        fallback,
                        LaneGuidanceAnalysis.none(
                                LaneFailureReason.NO_DIVIDERS,
                                0,
                                0,
                                direct.componentCount,
                                false));
                continue;
            }
            List<LaneCell> cells = laneCellsFromDividers(bitmap, profile, dividers);
            if (cells.size() < 2 || cells.size() > 6) {
                LaneGuidanceAnalysis direct =
                        analyzeLaneComponentsDirect(bitmap, directComponents, dividers.size(), cells.size());
                if (isBlockingLaneAnalysis(direct)) {
                    return direct;
                }
                fallback = rememberLaneFallback(
                        fallback,
                        LaneGuidanceAnalysis.unparsed(
                                LaneFailureReason.BAD_CELL_GRID,
                                dividers.size(),
                                cells.size(),
                                direct.componentCount));
                continue;
            }
            if (components.isEmpty()) {
                fallback = rememberLaneFallback(
                        fallback,
                        LaneGuidanceAnalysis.none(
                                LaneFailureReason.NO_COMPONENTS,
                                dividers.size(),
                                cells.size(),
                                0,
                                false));
                continue;
            }
            LaneParseResult parsedCells = parseLaneCells(bitmap, components, cells);
            String lanes = parsedCells.laneString;
            int expectedLaneCount =
                    Math.max(cells.size(), directLaneComponents(directComponents).size());
            if (isPartialLaneParse(lanes, expectedLaneCount)) {
                LaneGuidanceAnalysis direct =
                        analyzeLaneComponentsDirect(bitmap, directComponents, dividers.size(), cells.size());
                if (direct.status == LaneGuidanceStatus.PARSED
                        && !isPartialLaneParse(direct.laneString, expectedLaneCount)) {
                    return direct;
                }
                if (isBlockingLaneAnalysis(direct)
                        && direct.status == LaneGuidanceStatus.UNPARSED_ROW) {
                    return direct;
                }
                return LaneGuidanceAnalysis.unparsed(
                        LaneFailureReason.BAD_CELL_GRID,
                        dividers.size(),
                        cells.size(),
                        components.size(),
                        parsedCells.cells);
            }
            if (lanes.isEmpty()) {
                LaneGuidanceAnalysis direct =
                        analyzeLaneComponentsDirect(bitmap, directComponents, dividers.size(), cells.size());
                if (isBlockingLaneAnalysis(direct)) {
                    return direct;
                }
                return LaneGuidanceAnalysis.unparsed(
                        parsedCells.reason,
                        dividers.size(),
                        cells.size(),
                        components.size(),
                        parsedCells.cells);
            }
            if (!hasDirectionalLane(lanes)) {
                return LaneGuidanceAnalysis.none(
                        LaneFailureReason.ALL_STRAIGHT,
                        dividers.size(),
                        cells.size(),
                        components.size(),
                        false);
            }
            if (!isKnownMultiLaneSignature(lanes)) {
                return LaneGuidanceAnalysis.unparsed(
                        LaneFailureReason.UNKNOWN_GLYPH,
                        dividers.size(),
                        cells.size(),
                        components.size(),
                        parsedCells.cells);
            }
            NavSnapshot.Maneuver maneuver = maneuverFromLaneCue(lanes);
            if (maneuver == NavSnapshot.Maneuver.UNKNOWN) {
                return LaneGuidanceAnalysis.unparsed(
                        LaneFailureReason.UNKNOWN_MANEUVER,
                        dividers.size(),
                        cells.size(),
                        components.size(),
                        parsedCells.cells);
            }
            return LaneGuidanceAnalysis.parsed(
                    Cue.lanes(maneuver, sourceFromManeuver(maneuver), lanes),
                    lanes,
                    dividers.size(),
                    cells.size(),
                    components.size(),
                    parsedCells.cells);
        }
        return fallback;
    }

    //renders this UI section here so screen structure stays traceable during preview and car testing.
    private static LaneGuidanceAnalysis rememberLaneFallback(
            LaneGuidanceAnalysis current,
            LaneGuidanceAnalysis candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        if (current.status == LaneGuidanceStatus.UNPARSED_ROW) {
            if (candidate.status != LaneGuidanceStatus.UNPARSED_ROW) {
                return current;
            }
            if (candidate.cells.size() > current.cells.size()
                    || candidate.componentCount > current.componentCount) {
                return candidate;
            }
            return current;
        }
        if (candidate.status == LaneGuidanceStatus.UNPARSED_ROW) {
            return candidate;
        }
        return candidate;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static WazeVisualLayout visualLayout(Bitmap bitmap) {
        if (bitmap == null) {
            return WazeVisualLayout.NO_ACTIVE_PANEL;
        }
        ColorCounts arrival = colorCounts(bitmap, 30, 130, 130, 220);
        if (isArrivalCue(arrival.red, arrival.white, arrival.gray)) {
            return WazeVisualLayout.ARRIVAL;
        }
        if (!hasActiveInstructionPanel(bitmap)) {
            return WazeVisualLayout.NO_ACTIVE_PANEL;
        }
        if (hasStrongLaneRowEvidence(bitmap)) {
            return WazeVisualLayout.LANES;
        }
        if (parseSingleCue(bitmap, false) != null) {
            return WazeVisualLayout.SINGLE_MANEUVER;
        }
        return WazeVisualLayout.UNKNOWN;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasStrongLaneRowEvidence(Bitmap bitmap) {
        for (LaneCropProfile profile : laneCropProfiles(bitmap)) {
            if (!hasLaneStripPanel(bitmap, profile)) {
                continue;
            }
            List<Component> components = laneComponents(bitmap, profile);
            List<Integer> dividers = laneDividerColumns(bitmap, profile);
            if (!dividers.isEmpty()) {
                List<LaneCell> cells = laneCellsFromDividers(bitmap, profile, dividers);
                if (cells.size() >= 2 && cells.size() <= 6 && !components.isEmpty()) {
                    return true;
                }
            }
            List<Component> directComponents =
                    laneComponentsAfterMinX(bitmap, components, profile);
            LaneGuidanceAnalysis direct =
                    analyzeLaneComponentsDirect(bitmap, directComponents, dividers.size(), 0);
            if (direct.componentCount >= 2
                    && (direct.status == LaneGuidanceStatus.PARSED
                    || direct.status == LaneGuidanceStatus.UNPARSED_ROW)) {
                return true;
            }
        }
        return false;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isBlockingLaneAnalysis(LaneGuidanceAnalysis direct) {
        return direct != null
                && (direct.status == LaneGuidanceStatus.PARSED
                || direct.status == LaneGuidanceStatus.UNPARSED_ROW);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static LaneGuidanceAnalysis analyzeLaneComponentsDirect(
            Bitmap bitmap,
            List<Component> components,
            int dividerCount,
            int cellCount) {
        List<Component> laneLike = directLaneComponents(components);
        int componentCount = laneLike.size();
        if (componentCount <= 0) {
            return LaneGuidanceAnalysis.none(
                    LaneFailureReason.NO_COMPONENTS,
                    dividerCount,
                    cellCount,
                    0,
                    false);
        }
        if (componentCount == 1) {
            return LaneGuidanceAnalysis.none(
                    LaneFailureReason.SINGLE_LANE,
                    dividerCount,
                    cellCount,
                    componentCount,
                    false);
        }
        if (componentCount > 6) {
            return LaneGuidanceAnalysis.none(
                    LaneFailureReason.BAD_CELL_GRID,
                    dividerCount,
                    cellCount,
                    componentCount,
                    false);
        }
        if (!hasDirectLaneRowGeometry(laneLike)) {
            return LaneGuidanceAnalysis.none(
                    LaneFailureReason.BAD_CELL_GRID,
                    dividerCount,
                    cellCount,
                    componentCount,
                    false);
        }

        LaneParseResult parsed = parseLaneComponentsDirect(bitmap, laneLike);
        String lanes = parsed.laneString;
        if (lanes.isEmpty()) {
            return LaneGuidanceAnalysis.unparsed(
                    parsed.reason,
                    dividerCount,
                    cellCount,
                    componentCount,
                    parsed.cells);
        }
        if (!hasDirectionalLane(lanes)) {
            return LaneGuidanceAnalysis.none(
                    LaneFailureReason.ALL_STRAIGHT,
                    dividerCount,
                    cellCount,
                    componentCount,
                    false);
        }
        if (!isKnownMultiLaneSignature(lanes)) {
            return LaneGuidanceAnalysis.unparsed(
                    LaneFailureReason.UNKNOWN_GLYPH,
                    dividerCount,
                    cellCount,
                    componentCount,
                    parsed.cells);
        }
        NavSnapshot.Maneuver maneuver = maneuverFromLaneCue(lanes);
        if (maneuver == NavSnapshot.Maneuver.UNKNOWN) {
            return LaneGuidanceAnalysis.unparsed(
                    LaneFailureReason.UNKNOWN_MANEUVER,
                    dividerCount,
                    cellCount,
                    componentCount,
                    parsed.cells);
        }
        return LaneGuidanceAnalysis.parsed(
                Cue.lanes(maneuver, sourceFromManeuver(maneuver), lanes),
                lanes,
                dividerCount,
                cellCount,
                componentCount,
                parsed.cells);
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static LaneParseResult parseLaneComponentsDirect(Bitmap bitmap, List<Component> components) {
        List<String> tokens = new ArrayList<>();
        List<WazeLaneCell> debugCells = new ArrayList<>();
        LaneFailureReason failure = LaneFailureReason.NONE;
        for (int i = 0; i < components.size(); i++) {
            Component component = components.get(i);
            String template = templateToken(bitmap, component, false);
            String geometry = glyphToken(component);
            String token = chooseTemplateOrGeometry(template, geometry);
            if (token.isEmpty() || !KNOWN_LANE_GLYPHS.contains(token)) {
                if (failure == LaneFailureReason.NONE) {
                    failure = LaneFailureReason.UNKNOWN_GLYPH;
                }
                debugCells.add(debugCell(i, component, template, geometry, token,
                        LaneFailureReason.UNKNOWN_GLYPH));
                continue;
            }
            debugCells.add(debugCell(i, component, template, geometry, token,
                    LaneFailureReason.NONE));
            tokens.add(token);
        }
        if (failure != LaneFailureReason.NONE) {
            return LaneParseResult.empty(failure, debugCells);
        }
        return LaneParseResult.parsed(joinLaneTokens(tokens), debugCells);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static List<Component> directLaneComponents(List<Component> components) {
        if (components == null || components.isEmpty()) {
            return Collections.emptyList();
        }
        List<Component> filtered = new ArrayList<>();
        for (Component component : components) {
            if (isDirectLaneComponent(component)) {
                filtered.add(component);
            }
        }
        Collections.sort(filtered, Comparator.comparingInt(component -> component.x1 + component.x2));
        return filtered;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isDirectLaneComponent(Component component) {
        if (component == null) {
            return false;
        }
        int width = component.width();
        int height = component.height();
        int count = component.count;
        if (width < 18 || width > 95 || height < 18 || height > 120 || count < 100) {
            return false;
        }
        return count >= Math.max(180, width * 6);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasDirectLaneRowGeometry(List<Component> components) {
        if (components == null || components.size() < 2 || components.size() > 6) {
            return false;
        }
        int minCenterY = Integer.MAX_VALUE;
        int maxCenterY = Integer.MIN_VALUE;
        int minCenterX = Integer.MAX_VALUE;
        int maxCenterX = Integer.MIN_VALUE;
        int minGap = Integer.MAX_VALUE;
        int maxGap = Integer.MIN_VALUE;
        int previousCenterX = Integer.MIN_VALUE;
        int maxWidth = 0;
        int minHeight = Integer.MAX_VALUE;
        for (Component component : components) {
            int centerX = centerX(component);
            int centerY = centerY(component);
            minCenterX = Math.min(minCenterX, centerX);
            maxCenterX = Math.max(maxCenterX, centerX);
            minCenterY = Math.min(minCenterY, centerY);
            maxCenterY = Math.max(maxCenterY, centerY);
            maxWidth = Math.max(maxWidth, component.width());
            minHeight = Math.min(minHeight, component.height());
            if (previousCenterX != Integer.MIN_VALUE) {
                int gap = centerX - previousCenterX;
                if (gap <= 0) {
                    return false;
                }
                minGap = Math.min(minGap, gap);
                maxGap = Math.max(maxGap, gap);
            }
            previousCenterX = centerX;
        }
        if (maxCenterY - minCenterY > 46) {
            return false;
        }
        if (components.size() >= 3 && maxCenterX - minCenterX < 260) {
            return false;
        }
        int absoluteMinGap = Math.max(28, (maxWidth * 2) / 3);
        int absoluteMaxGap = components.size() == 2
                ? Math.max(680, maxWidth * 8)
                : Math.max(220, maxWidth * 5);
        if (minGap < absoluteMinGap) {
            return false;
        }
        if (maxGap > absoluteMaxGap
                && !isWideDirectLaneRowGeometry(components, minHeight, maxCenterY - minCenterY,
                maxGap, maxWidth)) {
            return false;
        }
        return maxGap * 100 <= minGap * 200;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isWideDirectLaneRowGeometry(List<Component> components,
            int minHeight, int centerYSpread, int maxGap, int maxWidth) {
        if (components == null || components.size() < 3) {
            return false;
        }
        if (minHeight < 58 || centerYSpread > 36) {
            return false;
        }
        int wideMaxGap = Math.max(380, maxWidth * 12);
        return maxGap <= wideMaxGap;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int centerX(Component component) {
        return (component.x1 + component.x2) / 2;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int centerY(Component component) {
        return (component.y1 + component.y2) / 2;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static List<Integer> laneDividerColumns(Bitmap bitmap, LaneCropProfile profile) {
        if (bitmap == null || profile == null || !hasLaneStripPanel(bitmap, profile)) {
            return Collections.emptyList();
        }
        int x1 = scaleX(bitmap, profile.x1);
        int x2 = Math.min(scaleX(bitmap, profile.x2), bitmap.getWidth() - 1);
        int y1 = scaleY(bitmap, profile.y1, profile.referenceHeight);
        int y2 = Math.min(scaleY(bitmap, profile.y2, profile.referenceHeight), bitmap.getHeight());
        int height = Math.max(1, y2 - y1);
        int upperY1 = y1 + height / 10;
        int upperY2 = y1 + height / 2;
        int scanY1 = y1 + height / 2;
        int scanY2 = y1 + (height * 9) / 10;
        int minScore = Math.max(10, (scanY2 - scanY1) / 3);
        int maxUpperScore = Math.max(4, (upperY2 - upperY1) / 8);
        int maxRunWidth = Math.max(2, scaleX(bitmap, 7));
        int minGap = Math.max(20, scaleX(bitmap, 90));
        List<Integer> dividers = new ArrayList<>();
        int runStart = -1;
        int runEnd = -1;
        int runScore = 0;
        for (int x = x1 + scaleX(bitmap, 50); x <= x2 - scaleX(bitmap, 50); x++) {
            int score = 0;
            for (int y = scanY1; y < scanY2; y++) {
                if (isLaneDividerPixel(bitmap.getPixel(x, y))) {
                    score++;
                }
            }
            int upperScore = 0;
            for (int y = upperY1; y < upperY2; y++) {
                if (isLaneDividerPixel(bitmap.getPixel(x, y))) {
                    upperScore++;
                }
            }
            if (score >= minScore && upperScore <= maxUpperScore) {
                if (runStart < 0) {
                    runStart = x;
                    runScore = score;
                }
                runEnd = x;
                runScore = Math.max(runScore, score);
            } else {
                addDividerRun(dividers, runStart, runEnd, runScore, maxRunWidth, minGap);
                runStart = -1;
                runEnd = -1;
                runScore = 0;
            }
        }
        addDividerRun(dividers, runStart, runEnd, runScore, maxRunWidth, minGap);
        return dividers;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static void addDividerRun(List<Integer> dividers,
            int runStart, int runEnd, int runScore, int maxRunWidth, int minGap) {
        if (runStart < 0 || runEnd < runStart || runScore <= 0) {
            return;
        }
        int width = runEnd - runStart + 1;
        if (width > maxRunWidth) {
            return;
        }
        int center = (runStart + runEnd) / 2;
        if (!dividers.isEmpty() && center - dividers.get(dividers.size() - 1) < minGap) {
            return;
        }
        dividers.add(center);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static List<LaneCell> laneCellsFromDividers(
            Bitmap bitmap, LaneCropProfile profile, List<Integer> dividers) {
        if (bitmap == null || profile == null || dividers == null || dividers.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> selectedDividers = selectLaneDividers(bitmap, profile, dividers);
        if (selectedDividers.isEmpty()) {
            return Collections.emptyList();
        }
        List<LaneCell> cells = new ArrayList<>();
        int left = Math.max(scaleX(bitmap, profile.x1), scaleX(bitmap, profile.minLaneX));
        int right = Math.min(scaleX(bitmap, profile.x2), bitmap.getWidth() - 1);
        int top = scaleY(bitmap, profile.y1, profile.referenceHeight);
        int bottom = Math.min(scaleY(bitmap, profile.y2, profile.referenceHeight), bitmap.getHeight() - 1);
        int minWidth = laneCellMinWidth(bitmap);
        int maxWidth = laneCellMaxWidth(bitmap, minWidth);
        int cellLeft = left;
        for (Integer divider : selectedDividers) {
            int cellRight = divider - 1;
            if (!addLaneCell(cells, cellLeft, cellRight, top, bottom, minWidth, maxWidth)) {
                return Collections.emptyList();
            }
            cellLeft = divider + 1;
        }
        if (!addLaneCell(cells, cellLeft, right, top, bottom, minWidth, maxWidth)) {
            return Collections.emptyList();
        }
        return cells;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static List<Integer> selectLaneDividers(
            Bitmap bitmap, LaneCropProfile profile, List<Integer> candidates) {
        int count = Math.min(candidates.size(), 12);
        if (count <= 0) {
            return Collections.emptyList();
        }
        int left = Math.max(scaleX(bitmap, profile.x1), scaleX(bitmap, profile.minLaneX));
        int right = Math.min(scaleX(bitmap, profile.x2), bitmap.getWidth() - 1);
        int minWidth = laneCellMinWidth(bitmap);
        int maxWidth = laneCellMaxWidth(bitmap, minWidth);
        List<Integer> best = Collections.emptyList();
        int bestSpread = Integer.MAX_VALUE;
        int masks = 1 << count;
        for (int mask = 1; mask < masks; mask++) {
            List<Integer> selected = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                if ((mask & (1 << i)) != 0) {
                    selected.add(candidates.get(i));
                }
            }
            if (selected.size() > 5 || selected.size() + 1 < 2) {
                continue;
            }
            int minCell = Integer.MAX_VALUE;
            int maxCell = Integer.MIN_VALUE;
            int previous = left;
            boolean valid = true;
            for (Integer divider : selected) {
                int width = divider - previous;
                if (width < minWidth || width > maxWidth) {
                    valid = false;
                    break;
                }
                minCell = Math.min(minCell, width);
                maxCell = Math.max(maxCell, width);
                previous = divider + 1;
            }
            int lastWidth = right - previous + 1;
            if (lastWidth < minWidth || lastWidth > maxWidth) {
                valid = false;
            } else {
                minCell = Math.min(minCell, lastWidth);
                maxCell = Math.max(maxCell, lastWidth);
            }
            if (!valid || maxCell * 100 > minCell * 180) {
                continue;
            }
            int spread = maxCell - minCell;
            if (selected.size() > best.size()
                    || (selected.size() == best.size() && spread < bestSpread)) {
                best = selected;
                bestSpread = spread;
            }
        }
        return best;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int laneCellMinWidth(Bitmap bitmap) {
        return Math.max(60, scaleX(bitmap, 110));
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int laneCellMaxWidth(Bitmap bitmap, int minWidth) {
        return Math.max(minWidth + 1, scaleX(bitmap, 560));
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean addLaneCell(List<LaneCell> cells,
            int left, int right, int top, int bottom, int minWidth, int maxWidth) {
        int width = right - left + 1;
        if (width < minWidth || width > maxWidth) {
            return false;
        }
        cells.add(new LaneCell(left, right, top, bottom));
        return true;
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static LaneParseResult parseLaneCells(
            Bitmap bitmap,
            List<Component> components,
            List<LaneCell> cells) {
        List<String> tokens = new ArrayList<>();
        List<WazeLaneCell> debugCells = new ArrayList<>();
        LaneFailureReason failure = LaneFailureReason.NONE;
        for (int i = 0; i < cells.size(); i++) {
            LaneCell cell = cells.get(i);
            Component component = componentForCell(components, cell);
            if (component == null) {
                if (failure == LaneFailureReason.NONE) {
                    failure = LaneFailureReason.EMPTY_CELL;
                }
                debugCells.add(debugCell(i, cell, "", "", "", LaneFailureReason.EMPTY_CELL));
                continue;
            }
            String template = templateToken(bitmap, component, false);
            if (template.isEmpty()) {
                template = templateToken(bitmap, cell, false);
            }
            String geometry = glyphToken(component);
            String token = chooseTemplateOrGeometry(template, geometry);
            if (token.isEmpty() || !KNOWN_LANE_GLYPHS.contains(token)) {
                if (failure == LaneFailureReason.NONE) {
                    failure = LaneFailureReason.UNKNOWN_GLYPH;
                }
                debugCells.add(debugCell(i, cell, template, geometry, token,
                        LaneFailureReason.UNKNOWN_GLYPH));
                continue;
            }
            debugCells.add(debugCell(i, cell, template, geometry, token,
                    LaneFailureReason.NONE));
            tokens.add(token);
        }
        if (failure != LaneFailureReason.NONE) {
            return LaneParseResult.empty(failure, debugCells);
        }
        return LaneParseResult.parsed(joinLaneTokens(tokens), debugCells);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String chooseTemplateOrGeometry(String template, String geometry) {
        String cleanTemplate = template == null ? "" : template;
        String cleanGeometry = geometry == null ? "" : geometry;
        if (cleanTemplate.isEmpty()) {
            return cleanGeometry;
        }
        if (isStraightOnlyToken(cleanTemplate) && hasDirectionalLane(cleanGeometry)) {
            return cleanGeometry;
        }
        if (isStraightOnlyToken(cleanGeometry) && hasDirectionalLane(cleanTemplate)) {
            return cleanGeometry;
        }
        return cleanTemplate;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String glyphSource(String templateToken, String geometryToken, String finalToken) {
        String template = templateToken == null ? "" : templateToken.trim();
        String geometry = geometryToken == null ? "" : geometryToken.trim();
        String token = finalToken == null ? "" : finalToken.trim();
        if (!token.isEmpty() && token.equals(geometry) && !token.equals(template)) {
            return "geometry";
        }
        if (!token.isEmpty() && token.equals(template)) {
            return "template";
        }
        if (!template.isEmpty() && geometry.isEmpty()) {
            return "template";
        }
        if (template.isEmpty() && !geometry.isEmpty()) {
            return "geometry";
        }
        if (!template.isEmpty()) {
            return "template";
        }
        return "none";
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isStraightOnlyToken(String token) {
        String clean = token == null ? "" : token.trim();
        return "S".equals(clean) || "S*".equals(clean);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static WazeLaneCell debugCell(
            int zeroBasedIndex,
            LaneCell cell,
            String templateToken,
            String geometryToken,
            String finalToken,
            LaneFailureReason reason) {
        return new WazeLaneCell(
                zeroBasedIndex + 1,
                cell.x1,
                cell.y1,
                cell.x2,
                cell.y2,
                templateToken,
                geometryToken,
                finalToken,
                glyphSource(templateToken, geometryToken, finalToken),
                reason == null ? "" : reason.name());
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static WazeLaneCell debugCell(
            int zeroBasedIndex,
            Component component,
            String templateToken,
            String geometryToken,
            String finalToken,
            LaneFailureReason reason) {
        return new WazeLaneCell(
                zeroBasedIndex + 1,
                component.x1,
                component.y1,
                component.x2,
                component.y2,
                templateToken,
                geometryToken,
                finalToken,
                glyphSource(templateToken, geometryToken, finalToken),
                reason == null ? "" : reason.name());
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static Component componentForCell(List<Component> components, LaneCell cell) {
        Component best = null;
        int bestCount = 0;
        for (Component component : components) {
            int overlap = Math.min(component.x2, cell.x2) - Math.max(component.x1, cell.x1) + 1;
            if (overlap <= 0) {
                continue;
            }
            int center = (component.x1 + component.x2) / 2;
            if (center < cell.x1 || center > cell.x2) {
                continue;
            }
            if (component.count > bestCount) {
                best = component;
                bestCount = component.count;
            }
        }
        return best;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String joinLaneTokens(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String token : tokens) {
            if (token == null || token.trim().isEmpty()) {
                return "";
            }
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append(token.trim());
        }
        return builder.toString();
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static List<Component> laneComponentsAfterMinX(
            Bitmap bitmap,
            List<Component> components,
            LaneCropProfile profile) {
        List<Component> filtered = new ArrayList<>();
        int minX = scaleX(bitmap, profile.minLaneX);
        for (Component component : components) {
            if (component.x1 >= minX) {
                filtered.add(component);
            }
        }
        return filtered;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean looksLikeLaneGuidanceLayout(
            Bitmap bitmap,
            List<Component> components,
            LaneCropProfile profile) {
        if (components == null || components.size() < 2 || components.size() > 7) {
            return false;
        }
        int minWidth = scaleX(bitmap, 18);
        int maxWidth = scaleX(bitmap, 95);
        int minHeight = scaleY(bitmap, 18, profile.referenceHeight);
        int maxHeight = scaleY(bitmap, 120, profile.referenceHeight);
        int minCenterY = Integer.MAX_VALUE;
        int maxCenterY = Integer.MIN_VALUE;
        for (Component component : components) {
            int width = component.width();
            int height = component.height();
            if (width < minWidth || width > maxWidth || height < minHeight || height > maxHeight) {
                return false;
            }
            int centerY = component.y1 + height / 2;
            minCenterY = Math.min(minCenterY, centerY);
            maxCenterY = Math.max(maxCenterY, centerY);
        }
        return maxCenterY - minCenterY <= scaleY(bitmap, 46, profile.referenceHeight);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasDirectionalLane(String laneString) {
        String clean = laneString == null ? "" : laneString.trim();
        if (clean.isEmpty()) {
            return false;
        }
        String[] tokens = clean.split("\\|", -1);
        for (String rawToken : tokens) {
            String[] parts = rawToken.split("\\+", -1);
            for (String rawPart : parts) {
                PartCue cue = PartCue.parse(rawPart);
                if (cue == null) {
                    continue;
                }
                if ("L".equals(cue.token) || "R".equals(cue.token)
                        || "Ls".equals(cue.token) || "Rs".equals(cue.token)
                        || "RampL".equals(cue.token) || "RampR".equals(cue.token)
                        || "U".equals(cue.token) || "UR".equals(cue.token)) {
                    return true;
                }
            }
        }
        return false;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static LaneCropProfile[] laneCropProfiles(Bitmap bitmap) {
        if (bitmap != null && bitmap.getHeight() <= 800) {
            return new LaneCropProfile[] {
                    new LaneCropProfile(20, 95, 970, 195, 720, 20),
                    new LaneCropProfile(20, 216, 900, 306, 720, 150)
            };
        }
        return new LaneCropProfile[] {
                new LaneCropProfile(20, 95, 970, 195, 1080, 20),
                new LaneCropProfile(20, 245, 900, 335, 1080, 150)
        };
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static List<Component> laneComponents(Bitmap bitmap, LaneCropProfile profile) {
        if (bitmap == null || profile == null || !hasLaneStripPanel(bitmap, profile)) {
            return Collections.emptyList();
        }
        return components(
                bitmap,
                profile.x1,
                profile.y1,
                profile.x2,
                profile.y2,
                profile.referenceHeight);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasLaneStripPanel(Bitmap bitmap, LaneCropProfile profile) {
        int x1 = scaleX(bitmap, profile.x1);
        int y1 = scaleY(bitmap, profile.y1, profile.referenceHeight);
        int x2 = Math.min(scaleX(bitmap, profile.x2), bitmap.getWidth());
        int y2 = Math.min(scaleY(bitmap, profile.y2, profile.referenceHeight), bitmap.getHeight());
        int sampled = Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
        if (sampled <= 0) {
            return false;
        }
        int dark = 0;
        for (int x = x1; x < x2; x += 2) {
            for (int y = y1; y < y2; y += 2) {
                if (isDarkPanelPixel(bitmap.getPixel(x, y))) {
                    dark += 4;
                }
            }
        }
        return dark >= Math.max(4000, sampled / 5);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static NavSnapshot.Maneuver maneuverFromLaneCue(String laneString) {
        String clean = laneString == null ? "" : laneString.trim();
        if (clean.isEmpty()) {
            return NavSnapshot.Maneuver.UNKNOWN;
        }
        String[] tokens = clean.split("\\|", -1);
        NavSnapshot.Maneuver recommendedStraight = NavSnapshot.Maneuver.UNKNOWN;
        NavSnapshot.Maneuver recommendedSide = NavSnapshot.Maneuver.UNKNOWN;
        NavSnapshot.Maneuver fallback = NavSnapshot.Maneuver.UNKNOWN;
        for (String rawToken : tokens) {
            String token = rawToken.trim();
            if (token.isEmpty()) {
                continue;
            }
            String[] parts = token.split("\\+", -1);
            for (String rawPart : parts) {
                PartCue cue = PartCue.parse(rawPart);
                if (cue == null) {
                    continue;
                }
                NavSnapshot.Maneuver maneuver = maneuverFromToken(cue.token);
                if (cue.recommended) {
                    if (isSideManeuver(maneuver)) {
                        if (recommendedSide == NavSnapshot.Maneuver.UNKNOWN) {
                            recommendedSide = maneuver;
                        }
                        continue;
                    }
                    if (maneuver == NavSnapshot.Maneuver.STRAIGHT) {
                        recommendedStraight = maneuver;
                    }
                }
                if (fallback == NavSnapshot.Maneuver.UNKNOWN
                        || (fallback == NavSnapshot.Maneuver.STRAIGHT
                        && maneuver != NavSnapshot.Maneuver.STRAIGHT
                        && maneuver != NavSnapshot.Maneuver.UNKNOWN)) {
                    fallback = maneuver;
                }
            }
        }
        if (recommendedStraight != NavSnapshot.Maneuver.UNKNOWN) {
            return recommendedStraight;
        }
        if (recommendedSide != NavSnapshot.Maneuver.UNKNOWN) {
            return recommendedSide;
        }
        return fallback;
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    static boolean isPartialLaneParseForTest(String laneString, int expectedLaneCount) {
        return isPartialLaneParse(laneString, expectedLaneCount);
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static boolean isPartialLaneParse(String laneString, int expectedLaneCount) {
        if (expectedLaneCount <= 1) {
            return false;
        }
        int actualLaneCount = WazeLaneParser.laneCountFromSignature(laneString);
        return actualLaneCount > 0 && actualLaneCount < expectedLaneCount;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static NavSnapshot.Maneuver maneuverFromSingleToken(String token) {
        String clean = canonicalToken(stripRecommendation(token == null ? "" : token.trim()));
        if ("U".equals(clean) || "U*".equals(clean)) {
            return NavSnapshot.Maneuver.UTURN_LEFT;
        }
        if ("UR".equals(clean) || "UR*".equals(clean)) {
            return NavSnapshot.Maneuver.UTURN_RIGHT;
        }
        return maneuverFromToken(clean);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static NavSnapshot.Maneuver maneuverFromToken(String token) {
        String clean = canonicalToken(stripRecommendation(token == null ? "" : token.trim()));
        if ("U".equals(clean)) {
            return NavSnapshot.Maneuver.UTURN_LEFT;
        }
        if ("UR".equals(clean)) {
            return NavSnapshot.Maneuver.UTURN_RIGHT;
        }
        if ("L".equals(clean)) {
            return NavSnapshot.Maneuver.LEFT_90;
        }
        if ("R".equals(clean)) {
            return NavSnapshot.Maneuver.RIGHT_90;
        }
        if ("Ls".equals(clean)) {
            return NavSnapshot.Maneuver.LEFT_45;
        }
        if ("Rs".equals(clean)) {
            return NavSnapshot.Maneuver.RIGHT_45;
        }
        if ("RoundL".equals(clean)) {
            return NavSnapshot.Maneuver.ROUNDABOUT_LEFT_EXIT;
        }
        if ("RoundR".equals(clean)) {
            return NavSnapshot.Maneuver.ROUNDABOUT_RIGHT_EXIT;
        }
        if ("RampL".equals(clean)) {
            return NavSnapshot.Maneuver.RAMP_LEFT;
        }
        if ("RampR".equals(clean)) {
            return NavSnapshot.Maneuver.RAMP_RIGHT;
        }
        if ("S".equals(clean)) {
            return NavSnapshot.Maneuver.STRAIGHT;
        }
        if ("S+Ls".equals(clean) || "SLs".equals(clean)) {
            return NavSnapshot.Maneuver.LEFT_45;
        }
        if ("S+Rs".equals(clean) || "SRs".equals(clean)) {
            return NavSnapshot.Maneuver.RIGHT_45;
        }
        if ("S+L".equals(clean) || "SL".equals(clean)) {
            return NavSnapshot.Maneuver.LEFT_90;
        }
        if ("S+R".equals(clean) || "SR".equals(clean)) {
            return NavSnapshot.Maneuver.RIGHT_90;
        }
        return NavSnapshot.Maneuver.UNKNOWN;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int sourceFromManeuver(NavSnapshot.Maneuver maneuver) {
        if (maneuver == NavSnapshot.Maneuver.LEFT_90) {
            return 2;
        }
        if (maneuver == NavSnapshot.Maneuver.RIGHT_90) {
            return 3;
        }
        if (maneuver == NavSnapshot.Maneuver.RAMP_LEFT) {
            return 71;
        }
        if (maneuver == NavSnapshot.Maneuver.RAMP_RIGHT) {
            return 70;
        }
        if (maneuver == NavSnapshot.Maneuver.LEFT_45) {
            return 4;
        }
        if (maneuver == NavSnapshot.Maneuver.RIGHT_45) {
            return 5;
        }
        if (maneuver == NavSnapshot.Maneuver.ARRIVE) {
            return ARRIVAL_SOURCE_MANEUVER;
        }
        if (maneuver == NavSnapshot.Maneuver.UTURN_LEFT) {
            return 8;
        }
        if (maneuver == NavSnapshot.Maneuver.UTURN_RIGHT) {
            return 19;
        }
        if (maneuver == NavSnapshot.Maneuver.ROUNDABOUT_RIGHT_EXIT
                || maneuver == NavSnapshot.Maneuver.ROUNDABOUT_LEFT_EXIT) {
            return 21;
        }
        return 9;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isRoundaboutManeuver(NavSnapshot.Maneuver maneuver) {
        return maneuver == NavSnapshot.Maneuver.ROUNDABOUT_RIGHT_EXIT
                || maneuver == NavSnapshot.Maneuver.ROUNDABOUT_LEFT_EXIT;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static Cue roundaboutCue(Bitmap bitmap, boolean leftHandRoundaboutTraffic) {
        int exitNumber = roundaboutExitNumber(bitmap);
        if (exitNumber < ROUNDABOUT_MIN_EXIT || exitNumber > ROUNDABOUT_MAX_EXIT) {
            return null;
        }
        return Cue.roundabout(
                NavSnapshot.Maneuver.ROUNDABOUT_RIGHT_EXIT,
                roundaboutSourceForExit(exitNumber, leftHandRoundaboutTraffic),
                exitNumber);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int roundaboutSourceForExit(int exitNumber, boolean leftHandTraffic) {
        if (exitNumber < ROUNDABOUT_MIN_EXIT || exitNumber > ROUNDABOUT_MAX_EXIT) {
            return 0;
        }
        int base = leftHandTraffic
                ? ROUNDABOUT_LEFT_HAND_BASE_SOURCE
                : ROUNDABOUT_RIGHT_HAND_BASE_SOURCE;
        return base + exitNumber - 1;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isArrivalCue(int redCount, int whiteCount, int grayCount) {
        return redCount >= 10 && whiteCount >= 200 && whiteCount > grayCount;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isRecommended(Component component) {
        return component.count > 0 && component.whiteCount * 100 >= component.count * 35;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean straightRecommended(Component component) {
        if (component.topCount > 0) {
            return component.topWhiteCount * 100 >= component.topCount * 35;
        }
        return false;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean sideRecommended(Component component) {
        int sideCount = component.midCount + component.bottomCount;
        if (sideCount > 0) {
            int sideWhite = component.midWhiteCount + component.bottomWhiteCount;
            return sideWhite * 100 >= sideCount * 55;
        }
        return isRecommended(component);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean smoothStraightRecommended(Component component) {
        if (component != null && component.topCount > 0) {
            return component.topWhiteCount * 100 >= component.topCount * 55;
        }
        return false;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean smoothSideRecommended(Component component) {
        if (component == null) {
            return false;
        }
        int sideCount = component.midCount + component.bottomCount;
        if (sideCount > 0) {
            int sideWhite = component.midWhiteCount + component.bottomWhiteCount;
            return sideWhite * 100 >= sideCount * 70;
        }
        return false;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String direction(Component component) {
        return direction(GlyphGeometry.from(component));
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String direction(GlyphGeometry geometry) {
        Component component = geometry == null ? null : geometry.component;
        if (component == null || component.count < 100) {
            return "";
        }
        if ((geometry.width <= 42
                || (geometry.width <= 64
                && Math.abs(geometry.midDelta) < 6.0d
                && Math.abs(geometry.bottomDelta) < UTURN_MIN_BOTTOM_DELTA))
                && Math.abs(geometry.midDelta) < 8.0d
                && Math.abs(geometry.bottomDelta) < 10.0d) {
            return "S";
        }
        if (looksLikeSingleSmoothSide(geometry)) {
            return geometry.bottomDelta > 0.0d ? "Ls" : "Rs";
        }
        if (geometry.bottomDelta > 18.0d) {
            return "L";
        }
        if (geometry.bottomDelta < -18.0d) {
            return "R";
        }
        if (component.height() <= 1) {
            if (geometry.midDelta > 8.0d) {
                return "Rs";
            }
            if (geometry.midDelta < -8.0d) {
                return "Ls";
            }
        }
        if (geometry.bottomDelta > 10.0d) {
            return "Ls";
        }
        if (geometry.bottomDelta < -10.0d) {
            return "Rs";
        }
        if (geometry.midDelta > 8.0d) {
            return "Rs";
        }
        if (geometry.midDelta < -8.0d) {
            return "Ls";
        }
        return "";
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String singleManeuverToken(Component component) {
        GlyphGeometry geometry = GlyphGeometry.from(component);
        String roundabout = roundaboutToken(geometry);
        if (!roundabout.isEmpty()) {
            return roundabout;
        }
        String uturn = uturnToken(geometry);
        if (!uturn.isEmpty()) {
            return uturn;
        }
        String direction = direction(geometry);
        return direction.isEmpty() ? "" : laneGlyph(direction, isRecommended(geometry.component));
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String uturnToken(Component component) {
        return uturnToken(GlyphGeometry.from(component));
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String uturnToken(GlyphGeometry geometry) {
        if (geometry == null
                || geometry.component == null
                || geometry.component.count < 300
                || geometry.width < 50
                || (geometry.component.height() > 1
                && geometry.component.height() * 10 < geometry.width * 13)) {
            return "";
        }
        if (geometry.bottomDelta < -UTURN_MIN_BOTTOM_DELTA && Math.abs(geometry.midDelta) < 6.0d) {
            return laneGlyph("U", isRecommended(geometry.component));
        }
        if (geometry.bottomDelta > UTURN_MIN_BOTTOM_DELTA && Math.abs(geometry.midDelta) < 6.0d) {
            return laneGlyph("UR", isRecommended(geometry.component));
        }
        return "";
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String glyphToken(Component component) {
        return glyphTokenInternal(component);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String glyphTokenInternal(Component component) {
        GlyphGeometry geometry = GlyphGeometry.from(component);
        if (geometry == null) {
            return "";
        }
        String uturn = uturnToken(geometry);
        if (!uturn.isEmpty()) {
            return uturn;
        }
        if (looksLikeSmoothSharpRightCompound(geometry)) {
            return compoundGlyph("Rs", isRecommended(component), "R", false);
        }
        if (looksLikeSmoothSharpLeftCompound(geometry)) {
            return compoundGlyph("Ls", isRecommended(component), "L", false);
        }
        if (looksLikeStraightSmoothRightCompound(geometry)) {
            return compoundGlyph("S", smoothStraightRecommended(component),
                    "Rs", smoothSideRecommended(component));
        }
        if (looksLikeStraightSmoothLeftCompound(geometry)) {
            return compoundGlyph("S", smoothStraightRecommended(component),
                    "Ls", smoothSideRecommended(component));
        }
        if (looksLikeStraightRightCompound(geometry)) {
            return compoundGlyph("S", straightRecommended(component), "R", sideRecommended(component));
        }
        if (looksLikeStraightLeftCompound(geometry)) {
            return compoundGlyph("S", straightRecommended(component), "L", sideRecommended(component));
        }
        String direction = direction(geometry);
        if (direction.isEmpty()) {
            return "";
        }
        return laneGlyph(direction, isRecommended(component));
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String laneGlyph(String direction, boolean recommended) {
        return recommended ? direction + "*" : direction;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String compoundGlyph(String firstDirection, boolean firstRecommended,
            String secondDirection, boolean secondRecommended) {
        return laneGlyph(firstDirection, firstRecommended) + "+"
                + laneGlyph(secondDirection, secondRecommended);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String roundaboutToken(GlyphGeometry geometry) {
        if (geometry == null
                || geometry.component == null
                || geometry.component.count < 300
                || geometry.width < 55
                || geometry.component.height() < 95
                || geometry.component.height() * 10 < geometry.width * 13
                || Math.abs(geometry.midDelta) >= 6.0d
                || Math.abs(geometry.bottomDelta) >= UTURN_MIN_BOTTOM_DELTA) {
            return "";
        }
        return laneGlyph("RoundR", isRecommended(geometry.component));
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int roundaboutExitNumber(Bitmap bitmap) {
        if (bitmap == null) {
            return 0;
        }
        int x1 = scaleX(bitmap, 58);
        int y1 = scaleY(bitmap, 150);
        int x2 = Math.min(scaleX(bitmap, 98), bitmap.getWidth());
        int y2 = Math.min(scaleY(bitmap, 192), bitmap.getHeight());
        List<DigitBlob> blobs = digitBlobs(bitmap, x1, y1, x2, y2);
        if (blobs.isEmpty()) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (DigitBlob blob : blobs) {
            int digit = classifyDigit(bitmap, blob);
            if (digit < 0) {
                return 0;
            }
            digits.append(digit);
        }
        try {
            int value = Integer.parseInt(digits.toString());
            return value >= ROUNDABOUT_MIN_EXIT && value <= ROUNDABOUT_MAX_EXIT ? value : 0;
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static List<DigitBlob> digitBlobs(Bitmap bitmap, int x1, int y1, int x2, int y2) {
        List<DigitBlob> blobs = new ArrayList<>();
        if (bitmap == null || x2 <= x1 || y2 <= y1) {
            return blobs;
        }
        boolean[] active = new boolean[x2 - x1];
        for (int x = x1; x < x2; x++) {
            int count = 0;
            for (int y = y1; y < y2; y++) {
                if (isRoundaboutDigitPixel(bitmap.getPixel(x, y))) {
                    count++;
                }
            }
            active[x - x1] = count > 0;
        }
        int start = -1;
        int last = -1;
        for (int i = 0; i < active.length; i++) {
            if (!active[i]) {
                continue;
            }
            int x = x1 + i;
            if (start < 0 || x > last + 1) {
                addDigitBlob(bitmap, blobs, start, last, y1, y2);
                start = x;
            }
            last = x;
        }
        addDigitBlob(bitmap, blobs, start, last, y1, y2);
        Collections.sort(blobs, Comparator.comparingInt(blob -> blob.x1));
        return blobs.size() > 2 ? Collections.emptyList() : blobs;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static void addDigitBlob(
            Bitmap bitmap, List<DigitBlob> blobs, int x1, int x2, int y1, int y2) {
        if (bitmap == null || x1 < 0 || x2 < x1) {
            return;
        }
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int count = 0;
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y < y2; y++) {
                if (isRoundaboutDigitPixel(bitmap.getPixel(x, y))) {
                    count++;
                    minY = Math.min(minY, y);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        int width = x2 - x1 + 1;
        int height = maxY - minY + 1;
        if (count < 8 || maxY <= minY || width < 3 || height * 4 > (y2 - y1) * 3) {
            return;
        }
        blobs.add(new DigitBlob(x1, x2 + 1, minY, maxY + 1, count));
    }

    //classifies raw evidence here so later decisions can use stable route state labels.
    private static int classifyDigit(Bitmap bitmap, DigitBlob blob) {
        boolean[][] bits = digitBits(bitmap, blob);
        int bestDigit = -1;
        int bestScore = Integer.MAX_VALUE;
        int secondScore = Integer.MAX_VALUE;
        for (int digit = 0; digit < DIGIT_TEMPLATES.length; digit++) {
            int score = digitScore(bits, DIGIT_TEMPLATES[digit]);
            if (score < bestScore) {
                secondScore = bestScore;
                bestScore = score;
                bestDigit = digit;
            } else if (score < secondScore) {
                secondScore = score;
            }
        }
        return bestScore <= 12 && secondScore - bestScore >= 2 ? bestDigit : -1;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean[][] digitBits(Bitmap bitmap, DigitBlob blob) {
        boolean[][] bits = new boolean[DIGIT_ROWS][DIGIT_COLS];
        for (int row = 0; row < DIGIT_ROWS; row++) {
            int y1 = blob.y1 + row * blob.height() / DIGIT_ROWS;
            int y2 = blob.y1 + Math.max(row * blob.height() / DIGIT_ROWS + 1,
                    (row + 1) * blob.height() / DIGIT_ROWS);
            for (int col = 0; col < DIGIT_COLS; col++) {
                int x1 = blob.x1 + col * blob.width() / DIGIT_COLS;
                int x2 = blob.x1 + Math.max(col * blob.width() / DIGIT_COLS + 1,
                        (col + 1) * blob.width() / DIGIT_COLS);
                int white = 0;
                for (int y = y1; y < y2 && y < blob.y2; y++) {
                    for (int x = x1; x < x2 && x < blob.x2; x++) {
                        if (isRoundaboutDigitPixel(bitmap.getPixel(x, y))) {
                            white++;
                        }
                    }
                }
                bits[row][col] = white > 0;
            }
        }
        return bits;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isRoundaboutDigitPixel(int color) {
        int red = (color >> 16) & 0xff;
        int green = (color >> 8) & 0xff;
        int blue = color & 0xff;
        int max = Math.max(red, Math.max(green, blue));
        int min = Math.min(red, Math.min(green, blue));
        return max > 165 && max - min < 80;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int digitScore(boolean[][] bits, String[] template) {
        int score = 0;
        for (int row = 0; row < DIGIT_ROWS; row++) {
            for (int col = 0; col < DIGIT_COLS; col++) {
                boolean expected = template[row].charAt(col) == '1';
                if (bits[row][col] != expected) {
                    score++;
                }
            }
        }
        return score;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean looksLikeStraightRightCompound(Component component) {
        return looksLikeStraightRightCompound(GlyphGeometry.from(component));
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean looksLikeStraightRightCompound(GlyphGeometry geometry) {
        return geometry != null
                && geometry.width > 42
                && Math.abs(geometry.bottomDelta) < 14.0d
                && geometry.midDelta > 8.0d;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean looksLikeStraightLeftCompound(Component component) {
        return looksLikeStraightLeftCompound(GlyphGeometry.from(component));
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean looksLikeStraightLeftCompound(GlyphGeometry geometry) {
        return geometry != null
                && geometry.width > 42
                && Math.abs(geometry.bottomDelta) < 14.0d
                && geometry.midDelta < -8.0d;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean looksLikeStraightSmoothRightCompound(GlyphGeometry geometry) {
        return geometry != null
                && geometry.width > 42
                && geometry.bottomDelta < -5.0d
                && Math.abs(geometry.midDelta) < 5.0d
                && sideRecommended(geometry.component);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean looksLikeStraightSmoothLeftCompound(GlyphGeometry geometry) {
        return geometry != null
                && geometry.width > 42
                && geometry.bottomDelta > 5.0d
                && Math.abs(geometry.midDelta) < 5.0d
                && sideRecommended(geometry.component);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean looksLikeSmoothSharpRightCompound(GlyphGeometry geometry) {
        return geometry != null
                && geometry.width > 42
                && geometry.bottomDelta < -14.0d
                && Math.abs(geometry.midDelta) < 5.0d;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean looksLikeSmoothSharpLeftCompound(GlyphGeometry geometry) {
        return geometry != null
                && geometry.width > 42
                && geometry.bottomDelta > 14.0d
                && Math.abs(geometry.midDelta) < 5.0d;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static boolean looksLikeSingleSmoothSide(GlyphGeometry geometry) {
        return geometry != null
                && geometry.component != null
                && geometry.width <= 48
                && geometry.component.height() >= 90
                && Math.abs(geometry.bottomDelta) >= 12.0d;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static NavSnapshot.Maneuver maneuverFromLaneToken(String token) {
        String clean = token == null ? "" : token.trim();
        if (clean.isEmpty()) {
            return NavSnapshot.Maneuver.UNKNOWN;
        }
        if (clean.contains("+")) {
            String[] parts = clean.split("\\+", -1);
            NavSnapshot.Maneuver recommendedStraight = NavSnapshot.Maneuver.UNKNOWN;
            NavSnapshot.Maneuver fallback = NavSnapshot.Maneuver.UNKNOWN;
            for (String part : parts) {
                PartCue cue = PartCue.parse(part);
                if (cue == null) {
                    continue;
                }
                NavSnapshot.Maneuver maneuver = maneuverFromToken(cue.token);
                if (cue.recommended) {
                    if (isSideManeuver(maneuver)) {
                        return maneuver;
                    }
                    if (maneuver == NavSnapshot.Maneuver.STRAIGHT) {
                        recommendedStraight = maneuver;
                    }
                }
                if (fallback == NavSnapshot.Maneuver.UNKNOWN
                        || (fallback == NavSnapshot.Maneuver.STRAIGHT
                        && maneuver != NavSnapshot.Maneuver.STRAIGHT
                        && maneuver != NavSnapshot.Maneuver.UNKNOWN)) {
                    fallback = maneuver;
                }
            }
            if (recommendedStraight != NavSnapshot.Maneuver.UNKNOWN) {
                return recommendedStraight;
            }
            if (fallback != NavSnapshot.Maneuver.UNKNOWN) {
                return fallback;
            }
            return maneuverFromToken(stripRecommendation(clean));
        }
        return maneuverFromToken(stripRecommendation(clean));
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isSideManeuver(NavSnapshot.Maneuver maneuver) {
        return maneuver == NavSnapshot.Maneuver.LEFT_90
                || maneuver == NavSnapshot.Maneuver.RIGHT_90
                || maneuver == NavSnapshot.Maneuver.LEFT_45
                || maneuver == NavSnapshot.Maneuver.RIGHT_45
                || maneuver == NavSnapshot.Maneuver.RAMP_LEFT
                || maneuver == NavSnapshot.Maneuver.RAMP_RIGHT
                || maneuver == NavSnapshot.Maneuver.UTURN_LEFT
                || maneuver == NavSnapshot.Maneuver.UTURN_RIGHT;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasRecommendation(String token) {
        return token != null && (token.indexOf('*') >= 0 || token.indexOf('!') >= 0);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String stripRecommendation(String token) {
        return token == null ? "" : token.replace("*", "").replace("!", "");
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static String canonicalToken(String token) {
        String clean = token == null ? "" : token.trim().replace(" ", "");
        if ("Ls".equals(clean) || "ls".equals(clean)) {
            return "Ls";
        }
        if ("Rs".equals(clean) || "rs".equals(clean)) {
            return "Rs";
        }
        if ("RoundL".equals(clean) || "roundl".equals(clean)) {
            return "RoundL";
        }
        if ("RoundR".equals(clean) || "roundr".equals(clean)) {
            return "RoundR";
        }
        if ("RampL".equals(clean) || "rampl".equals(clean)) {
            return "RampL";
        }
        if ("RampR".equals(clean) || "rampr".equals(clean)) {
            return "RampR";
        }
        if ("S+Ls".equals(clean) || "s+ls".equals(clean) || "S+ls".equals(clean)) {
            return "S+Ls";
        }
        if ("S+Rs".equals(clean) || "s+rs".equals(clean) || "S+rs".equals(clean)) {
            return "S+Rs";
        }
        return clean.toUpperCase(Locale.ROOT);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String templateToken(Bitmap bitmap, Component component, boolean singleContext) {
        if (bitmap == null || component == null) {
            return "";
        }
        int padX = Math.max(2, component.width() / 10);
        int padY = Math.max(2, component.height() / 10);
        Rect rect = new Rect(
                component.x1 - padX,
                component.y1 - padY,
                component.x2 + 1 + padX,
                component.y2 + 1 + padY);
        return templateToken(bitmap, rect, singleContext);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String templateToken(Bitmap bitmap, LaneCell cell, boolean singleContext) {
        if (cell == null) {
            return "";
        }
        return templateToken(bitmap, new Rect(cell.x1, cell.y1, cell.x2 + 1, cell.y2 + 1), singleContext);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String templateToken(Bitmap bitmap, Rect rect, boolean singleContext) {
        TemplateSignature signature = templateSignature(bitmap, rect);
        if (signature == null || signature.bitCount < 20) {
            return "";
        }
        TemplateMatch best = null;
        TemplateMatch second = null;
        for (WazeGlyphTemplates.Template template : WazeGlyphTemplates.ALL) {
            if (!isTemplateAllowed(template.token, singleContext)) {
                continue;
            }
            int min = Math.min(signature.bitCount, template.bitCount);
            int max = Math.max(signature.bitCount, template.bitCount);
            if (max > 0 && min * 100 < max * 45) {
                continue;
            }
            TemplateMatch candidate = new TemplateMatch(template.token, templateScore(signature, template));
            if (best == null || candidate.score < best.score) {
                second = best;
                best = candidate;
            } else if (second == null || candidate.score < second.score) {
                second = candidate;
            }
        }
        if (best == null || best.score > 95) {
            return "";
        }
        if (second != null && best.score > 55 && second.score - best.score < 10) {
            return "";
        }
        return best.token;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isTemplateAllowed(String token, boolean singleContext) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        if (singleContext) {
            return token.indexOf('+') < 0 && !"RampL".equals(token) && !"RampR".equals(token);
        }
        return KNOWN_LANE_GLYPHS.contains(token);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int templateScore(TemplateSignature signature, WazeGlyphTemplates.Template template) {
        int shapeDiff = hamming(signature.bits, template.bits);
        int whiteDiff = hamming(signature.whiteBits, template.whiteBits);
        int countDelta = Math.abs(signature.bitCount - template.bitCount);
        int whiteDelta = Math.abs(signature.whiteBitCount - template.whiteBitCount);
        int whitePenalty = hasRecommendation(template.token) ? whiteDiff * 2 + whiteDelta : whiteDiff;
        return shapeDiff + countDelta / 2 + whitePenalty;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int hamming(long[] left, long[] right) {
        int length = Math.min(left.length, right.length);
        int diff = 0;
        for (int i = 0; i < length; i++) {
            diff += Long.bitCount(left[i] ^ right[i]);
        }
        for (int i = length; i < left.length; i++) {
            diff += Long.bitCount(left[i]);
        }
        for (int i = length; i < right.length; i++) {
            diff += Long.bitCount(right[i]);
        }
        return diff;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static TemplateSignature templateSignature(Bitmap bitmap, Rect rect) {
        Rect safe = clampRect(rect, bitmap);
        if (safe == null) {
            return null;
        }
        long[] bits = new long[(WazeGlyphTemplates.WIDTH * WazeGlyphTemplates.HEIGHT + 63) / 64];
        long[] whiteBits = new long[bits.length];
        int bitCount = 0;
        int whiteBitCount = 0;
        for (int y = 0; y < WazeGlyphTemplates.HEIGHT; y++) {
            for (int x = 0; x < WazeGlyphTemplates.WIDTH; x++) {
                PixelClass pixelClass = templatePixelClass(bitmap, safe, x, y);
                if (pixelClass == PixelClass.NONE) {
                    continue;
                }
                int index = y * WazeGlyphTemplates.WIDTH + x;
                bits[index / 64] |= 1L << (index % 64);
                bitCount++;
                if (pixelClass == PixelClass.WHITE) {
                    whiteBits[index / 64] |= 1L << (index % 64);
                    whiteBitCount++;
                }
            }
        }
        return new TemplateSignature(bits, whiteBits, bitCount, whiteBitCount);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static PixelClass templatePixelClass(Bitmap bitmap, Rect rect, int normalizedX, int normalizedY) {
        int x1 = rect.left + normalizedX * rect.width() / WazeGlyphTemplates.WIDTH;
        int x2 = rect.left + Math.max(
                normalizedX * rect.width() / WazeGlyphTemplates.WIDTH + 1,
                (normalizedX + 1) * rect.width() / WazeGlyphTemplates.WIDTH);
        int y1 = rect.top + normalizedY * rect.height() / WazeGlyphTemplates.HEIGHT;
        int y2 = rect.top + Math.max(
                normalizedY * rect.height() / WazeGlyphTemplates.HEIGHT + 1,
                (normalizedY + 1) * rect.height() / WazeGlyphTemplates.HEIGHT);
        boolean hasCue = false;
        for (int y = y1; y < y2 && y < rect.bottom; y++) {
            for (int x = x1; x < x2 && x < rect.right; x++) {
                int color = bitmap.getPixel(x, y);
                if (isWhiteCuePixel(color)) {
                    return PixelClass.WHITE;
                }
                if (isNeutralCuePixel(color)) {
                    hasCue = true;
                }
            }
        }
        return hasCue ? PixelClass.GRAY : PixelClass.NONE;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static List<Component> components(Bitmap bitmap,
            int normX1, int normY1, int normX2, int normY2) {
        return components(bitmap, normX1, normY1, normX2, normY2, 1080);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static List<Component> components(Bitmap bitmap,
            int normX1, int normY1, int normX2, int normY2, int referenceHeight) {
        int x1 = scaleX(bitmap, normX1);
        int y1 = scaleY(bitmap, normY1, referenceHeight);
        int x2 = Math.min(scaleX(bitmap, normX2), bitmap.getWidth());
        int y2 = Math.min(scaleY(bitmap, normY2, referenceHeight), bitmap.getHeight());
        return componentsRaw(bitmap, x1, y1, x2, y2);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static List<Component> componentsRaw(Bitmap bitmap, int rawX1, int rawY1, int rawX2, int rawY2) {
        if (bitmap == null) {
            return Collections.emptyList();
        }
        int x1 = Math.max(0, Math.min(rawX1, bitmap.getWidth()));
        int y1 = Math.max(0, Math.min(rawY1, bitmap.getHeight()));
        int x2 = Math.max(x1, Math.min(rawX2, bitmap.getWidth()));
        int y2 = Math.max(y1, Math.min(rawY2, bitmap.getHeight()));
        if (x2 <= x1 || y2 <= y1) {
            return Collections.emptyList();
        }
        int columnThreshold = Math.max(4, (y2 - y1) / 24);
        boolean[] activeColumns = new boolean[Math.max(0, x2 - x1)];
        for (int x = x1; x < x2; x++) {
            int count = 0;
            for (int y = y1; y < y2; y++) {
                if (isNeutralCuePixel(bitmap.getPixel(x, y))) {
                    count++;
                }
            }
            activeColumns[x - x1] = count > columnThreshold;
        }

        List<Component> components = new ArrayList<>();
        int start = -1;
        int last = -1;
        int maxGap = Math.max(2, scaleX(bitmap, 2));
        for (int i = 0; i < activeColumns.length; i++) {
            if (!activeColumns[i]) {
                continue;
            }
            int x = x1 + i;
            if (start < 0 || x > last + maxGap) {
                addComponent(bitmap, components, start, last, y1, y2);
                start = x;
            }
            last = x;
        }
        addComponent(bitmap, components, start, last, y1, y2);
        Collections.sort(components, Comparator.comparingInt(component -> component.x1));
        return components;
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static Rect clampRect(Rect source, Bitmap bitmap) {
        if (source == null || bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return null;
        }
        int left = Math.max(0, Math.min(source.left, bitmap.getWidth()));
        int top = Math.max(0, Math.min(source.top, bitmap.getHeight()));
        int right = Math.max(left, Math.min(source.right, bitmap.getWidth()));
        int bottom = Math.max(top, Math.min(source.bottom, bitmap.getHeight()));
        if (right <= left || bottom <= top) {
            return null;
        }
        return new Rect(left, top, right, bottom);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasDarkPanelRaw(Bitmap bitmap, Rect rect) {
        Rect safe = clampRect(rect, bitmap);
        if (safe == null) {
            return false;
        }
        int sampled = safe.width() * safe.height();
        if (sampled <= 0) {
            return false;
        }
        int dark = 0;
        for (int x = safe.left; x < safe.right; x += 2) {
            for (int y = safe.top; y < safe.bottom; y += 2) {
                if (isDarkPanelPixel(bitmap.getPixel(x, y))) {
                    dark += 4;
                }
            }
        }
        return dark >= Math.max(300, sampled / 5);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static void addComponent(Bitmap bitmap, List<Component> components,
            int x1, int x2, int y1, int y2) {
        if (x1 < 0 || x2 < x1) {
            return;
        }
        int count = 0;
        int white = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        double topSum = 0.0d;
        double midSum = 0.0d;
        double bottomSum = 0.0d;
        int topCount = 0;
        int midCount = 0;
        int bottomCount = 0;
        int topWhite = 0;
        int midWhite = 0;
        int bottomWhite = 0;
        int topBand = y1 + (y2 - y1) / 3;
        int bottomBand = y1 + ((y2 - y1) * 2) / 3;
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y < y2; y++) {
                int color = bitmap.getPixel(x, y);
                if (!isNeutralCuePixel(color)) {
                    continue;
                }
                count++;
                boolean whitePixel = isWhiteCuePixel(color);
                if (whitePixel) {
                    white++;
                }
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                if (y < topBand) {
                    topSum += x;
                    topCount++;
                    if (whitePixel) {
                        topWhite++;
                    }
                } else if (y < bottomBand) {
                    midSum += x;
                    midCount++;
                    if (whitePixel) {
                        midWhite++;
                    }
                } else {
                    bottomSum += x;
                    bottomCount++;
                    if (whitePixel) {
                        bottomWhite++;
                    }
                }
            }
        }
        int minWidth = scaleX(bitmap, 10);
        int minCount = Math.max(100, ((y2 - y1) * Math.max(1, x2 - x1)) / 8);
        if (x2 - x1 + 1 < minWidth && count < minCount) {
            return;
        }
        if (count < 100 || maxY <= minY) {
            return;
        }
        components.add(new Component(
                x1,
                x2,
                minY,
                maxY,
                count,
                white,
                average(topSum, topCount, x1, x2),
                average(midSum, midCount, x1, x2),
                average(bottomSum, bottomCount, x1, x2),
                topCount,
                topWhite,
                midCount,
                midWhite,
                bottomCount,
                bottomWhite));
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static Component largest(List<Component> components) {
        Component largest = components.get(0);
        for (Component component : components) {
            if (component.count > largest.count) {
                largest = component;
            }
        }
        return largest;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static ColorCounts colorCounts(Bitmap bitmap,
            int normX1, int normY1, int normX2, int normY2) {
        int x1 = scaleX(bitmap, normX1);
        int y1 = scaleY(bitmap, normY1);
        int x2 = Math.min(scaleX(bitmap, normX2), bitmap.getWidth());
        int y2 = Math.min(scaleY(bitmap, normY2), bitmap.getHeight());
        ColorCounts counts = new ColorCounts();
        for (int x = x1; x < x2; x += 2) {
            for (int y = y1; y < y2; y += 2) {
                int color = bitmap.getPixel(x, y);
                if (isRedCuePixel(color)) {
                    counts.red++;
                } else if (isWhiteCuePixel(color)) {
                    counts.white++;
                } else if (isNeutralCuePixel(color)) {
                    counts.gray++;
                }
            }
        }
        return counts;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isNeutralCuePixel(int color) {
        int red = (color >> 16) & 0xff;
        int green = (color >> 8) & 0xff;
        int blue = color & 0xff;
        int max = Math.max(red, Math.max(green, blue));
        int min = Math.min(red, Math.min(green, blue));
        return max > 90 && max - min < 45;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isLaneDividerPixel(int color) {
        int red = (color >> 16) & 0xff;
        int green = (color >> 8) & 0xff;
        int blue = color & 0xff;
        int max = Math.max(red, Math.max(green, blue));
        int min = Math.min(red, Math.min(green, blue));
        return max > 90 && max < 210 && max - min < 45;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isWhiteCuePixel(int color) {
        int red = (color >> 16) & 0xff;
        int green = (color >> 8) & 0xff;
        int blue = color & 0xff;
        return red > 240 && green > 240 && blue > 240;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isTemplateWhiteCuePixelForTest(int red, int green, int blue) {
        int color = ((red & 0xff) << 16) | ((green & 0xff) << 8) | (blue & 0xff);
        return isWhiteCuePixel(color);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isRedCuePixel(int color) {
        int red = (color >> 16) & 0xff;
        int green = (color >> 8) & 0xff;
        int blue = color & 0xff;
        return red > 210 && green < 120 && blue < 140;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean hasActiveInstructionPanel(Bitmap bitmap) {
        int sampled = sampledPixelCount(bitmap, 15, 95, 600, 250);
        int dark = darkPixelCount(bitmap, 15, 95, 600, 250);
        return activeInstructionPanelForTest(dark, sampled);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int minInstructionPanelDarkPixels(int sampledPixels) {
        return Math.max(5000, sampledPixels / 3);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int sampledPixelCount(Bitmap bitmap,
            int normX1, int normY1, int normX2, int normY2) {
        int x1 = scaleX(bitmap, normX1);
        int y1 = scaleY(bitmap, normY1);
        int x2 = Math.min(scaleX(bitmap, normX2), bitmap.getWidth());
        int y2 = Math.min(scaleY(bitmap, normY2), bitmap.getHeight());
        return Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int darkPixelCount(Bitmap bitmap,
            int normX1, int normY1, int normX2, int normY2) {
        int x1 = scaleX(bitmap, normX1);
        int y1 = scaleY(bitmap, normY1);
        int x2 = Math.min(scaleX(bitmap, normX2), bitmap.getWidth());
        int y2 = Math.min(scaleY(bitmap, normY2), bitmap.getHeight());
        int count = 0;
        for (int x = x1; x < x2; x++) {
            for (int y = y1; y < y2; y++) {
                if (isDarkPanelPixel(bitmap.getPixel(x, y))) {
                    count++;
                }
            }
        }
        return count;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isDarkPanelPixel(int color) {
        int red = (color >> 16) & 0xff;
        int green = (color >> 8) & 0xff;
        int blue = color & 0xff;
        return red < 35 && green < 35 && blue < 35;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int scaleX(Bitmap bitmap, int normalized) {
        return Math.max(0, normalized * bitmap.getWidth() / 1920);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int scaleY(Bitmap bitmap, int normalized) {
        return scaleY(bitmap, normalized, 1080);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int scaleY(Bitmap bitmap, int normalized, int referenceHeight) {
        int safeReference = referenceHeight <= 0 ? 1080 : referenceHeight;
        return Math.max(0, normalized * bitmap.getHeight() / safeReference);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static double average(double sum, int count, int x1, int x2) {
        return count <= 0 ? (x1 + x2) / 2.0d : sum / count;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static int value(int[] values, int index) {
        return values == null || index >= values.length ? 0 : values[index];
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static double value(double[] values, int index) {
        return values == null || index >= values.length ? 0.0d : values[index];
    }

    //defines the LaneGuidanceAnalysisForTest module boundary so related behavior stays readable inside one unit.
    static final class LaneGuidanceAnalysisForTest {
        final String statusName;
        final String reasonName;
        final String laneString;
        final int dividerCount;
        final int cellCount;
        final int componentCount;
        final boolean blocksSingleFallback;

        LaneGuidanceAnalysisForTest(
                String statusName,
                String reasonName,
                String laneString,
                int dividerCount,
                int cellCount,
                int componentCount,
                boolean blocksSingleFallback) {
            this.statusName = statusName == null ? "" : statusName;
            this.reasonName = reasonName == null ? "" : reasonName;
            this.laneString = laneString == null ? "" : laneString;
            this.dividerCount = dividerCount;
            this.cellCount = cellCount;
            this.componentCount = componentCount;
            this.blocksSingleFallback = blocksSingleFallback;
        }

        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        static LaneGuidanceAnalysisForTest from(LaneGuidanceAnalysis analysis) {
            LaneGuidanceAnalysis safe = analysis == null
                    ? LaneGuidanceAnalysis.none(LaneFailureReason.UNKNOWN_GLYPH)
                    : analysis;
            return new LaneGuidanceAnalysisForTest(
                    safe.status.name(),
                    safe.reason.name(),
                    safe.laneString,
                    safe.dividerCount,
                    safe.cellCount,
                    safe.componentCount,
                    safe.blocksSingleFallback);
        }
    }

    //defines the LaneGuidanceAnalysis module boundary so related behavior stays readable inside one unit.
    static final class LaneGuidanceAnalysis {
        final LaneGuidanceStatus status;
        final LaneFailureReason reason;
        final Cue cue;
        final String laneString;
        final int dividerCount;
        final int cellCount;
        final int componentCount;
        final boolean blocksSingleFallback;
        final List<WazeLaneCell> cells;

        LaneGuidanceAnalysis(
                LaneGuidanceStatus status,
                LaneFailureReason reason,
                Cue cue,
                String laneString,
                int dividerCount,
                int cellCount,
                int componentCount,
                boolean blocksSingleFallback) {
            this(status, reason, cue, laneString, dividerCount, cellCount, componentCount,
                    blocksSingleFallback, Collections.emptyList());
        }

        LaneGuidanceAnalysis(
                LaneGuidanceStatus status,
                LaneFailureReason reason,
                Cue cue,
                String laneString,
                int dividerCount,
                int cellCount,
                int componentCount,
                boolean blocksSingleFallback,
                List<WazeLaneCell> cells) {
            this.status = status;
            this.reason = reason == null ? LaneFailureReason.UNKNOWN_GLYPH : reason;
            this.cue = cue;
            this.laneString = laneString == null ? "" : laneString;
            this.dividerCount = Math.max(0, dividerCount);
            this.cellCount = Math.max(0, cellCount);
            this.componentCount = Math.max(0, componentCount);
            this.blocksSingleFallback = blocksSingleFallback;
            this.cells = cells == null ? Collections.emptyList() : Collections.unmodifiableList(cells);
        }

        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        static LaneGuidanceAnalysis none() {
            return none(LaneFailureReason.NONE);
        }

        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        static LaneGuidanceAnalysis none(LaneFailureReason reason) {
            return none(reason, 0, 0, 0, false);
        }

        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        static LaneGuidanceAnalysis none(
                LaneFailureReason reason,
                int dividerCount,
                int cellCount,
                int componentCount,
                boolean blocksSingleFallback) {
            return new LaneGuidanceAnalysis(
                    LaneGuidanceStatus.NONE,
                    reason,
                    null,
                    "",
                    dividerCount,
                    cellCount,
                    componentCount,
                    blocksSingleFallback);
        }

        //parses source data here so downstream HUD code receives normalized navigation fields.
        static LaneGuidanceAnalysis unparsed() {
            return unparsed(LaneFailureReason.UNKNOWN_GLYPH, 0, 0, 0);
        }

        //parses source data here so downstream HUD code receives normalized navigation fields.
        static LaneGuidanceAnalysis unparsed(
                LaneFailureReason reason,
                int dividerCount,
                int cellCount,
                int componentCount) {
            return unparsed(reason, dividerCount, cellCount, componentCount, Collections.emptyList());
        }

        //parses source data here so downstream HUD code receives normalized navigation fields.
        static LaneGuidanceAnalysis unparsed(
                LaneFailureReason reason,
                int dividerCount,
                int cellCount,
                int componentCount,
                List<WazeLaneCell> cells) {
            return new LaneGuidanceAnalysis(
                    LaneGuidanceStatus.UNPARSED_ROW,
                    reason,
                    null,
                    "",
                    dividerCount,
                    cellCount,
                    componentCount,
                    true,
                    cells);
        }

        //parses source data here so downstream HUD code receives normalized navigation fields.
        static LaneGuidanceAnalysis parsed(Cue cue, String laneString) {
            return parsed(cue, laneString, 0, 0, 0);
        }

        //parses source data here so downstream HUD code receives normalized navigation fields.
        static LaneGuidanceAnalysis parsed(
                Cue cue,
                String laneString,
                int dividerCount,
                int cellCount,
                int componentCount) {
            return parsed(cue, laneString, dividerCount, cellCount, componentCount,
                    Collections.emptyList());
        }

        //parses source data here so downstream HUD code receives normalized navigation fields.
        static LaneGuidanceAnalysis parsed(
                Cue cue,
                String laneString,
                int dividerCount,
                int cellCount,
                int componentCount,
                List<WazeLaneCell> cells) {
            return new LaneGuidanceAnalysis(
                    LaneGuidanceStatus.PARSED,
                    LaneFailureReason.NONE,
                    cue,
                    laneString,
                    dividerCount,
                    cellCount,
                    componentCount,
                    false,
                    cells);
        }
    }

    //defines the LaneParseResult parser boundary so raw app evidence is normalized before HUD decisions use it.
    private static final class LaneParseResult {
        final String laneString;
        final LaneFailureReason reason;
        final List<WazeLaneCell> cells;

        LaneParseResult(String laneString, LaneFailureReason reason) {
            this(laneString, reason, Collections.emptyList());
        }

        LaneParseResult(String laneString, LaneFailureReason reason, List<WazeLaneCell> cells) {
            this.laneString = laneString == null ? "" : laneString;
            this.reason = reason == null ? LaneFailureReason.UNKNOWN_GLYPH : reason;
            this.cells = cells == null ? Collections.emptyList() : Collections.unmodifiableList(cells);
        }

        //parses source data here so downstream HUD code receives normalized navigation fields.
        static LaneParseResult parsed(String laneString) {
            return new LaneParseResult(laneString, LaneFailureReason.NONE);
        }

        //parses source data here so downstream HUD code receives normalized navigation fields.
        static LaneParseResult parsed(String laneString, List<WazeLaneCell> cells) {
            return new LaneParseResult(laneString, LaneFailureReason.NONE, cells);
        }

        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        static LaneParseResult empty(LaneFailureReason reason) {
            return new LaneParseResult("", reason);
        }

        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        static LaneParseResult empty(LaneFailureReason reason, List<WazeLaneCell> cells) {
            return new LaneParseResult("", reason, cells);
        }
    }

    //defines the LaneCell module boundary so related behavior stays readable inside one unit.
    private static final class LaneCell {
        final int x1;
        final int x2;
        final int y1;
        final int y2;

        LaneCell(int x1, int x2, int y1, int y2) {
            this.x1 = x1;
            this.x2 = x2;
            this.y1 = y1;
            this.y2 = y2;
        }
    }

    //defines the Cue module boundary so related behavior stays readable inside one unit.
    private static final class Cue {
        final NavSnapshot.Maneuver maneuver;
        final int sourceManeuver;
        final int roundaboutExitNumber;
        final String laneString;
        final boolean missingLaneGuidance;

        Cue(NavSnapshot.Maneuver maneuver, int sourceManeuver,
                int roundaboutExitNumber, String laneString, boolean missingLaneGuidance) {
            this.maneuver = maneuver;
            this.sourceManeuver = sourceManeuver;
            this.roundaboutExitNumber = Math.max(0, roundaboutExitNumber);
            this.laneString = laneString == null ? "" : laneString;
            this.missingLaneGuidance = missingLaneGuidance;
        }

        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        static Cue lanes(NavSnapshot.Maneuver maneuver, int sourceManeuver, String laneString) {
            return new Cue(maneuver, sourceManeuver, 0, laneString, false);
        }

        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        static Cue maneuver(NavSnapshot.Maneuver maneuver, int sourceManeuver) {
            return new Cue(maneuver, sourceManeuver, 0, "", false);
        }

        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        static Cue roundabout(
                NavSnapshot.Maneuver maneuver, int sourceManeuver, int roundaboutExitNumber) {
            return new Cue(maneuver, sourceManeuver, roundaboutExitNumber, "", false);
        }

        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        static Cue arrival() {
            return new Cue(NavSnapshot.Maneuver.ARRIVE, ARRIVAL_SOURCE_MANEUVER, 0, "", false);
        }

        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        static Cue missingLaneGuidance() {
            return new Cue(NavSnapshot.Maneuver.UNKNOWN, 0, 0, "", true);
        }
    }

    //defines the TemplateSignature module boundary so related behavior stays readable inside one unit.
    private static final class TemplateSignature {
        final long[] bits;
        final long[] whiteBits;
        final int bitCount;
        final int whiteBitCount;

        TemplateSignature(long[] bits, long[] whiteBits, int bitCount, int whiteBitCount) {
            this.bits = bits;
            this.whiteBits = whiteBits;
            this.bitCount = bitCount;
            this.whiteBitCount = whiteBitCount;
        }
    }

    //defines the TemplateMatch module boundary so related behavior stays readable inside one unit.
    private static final class TemplateMatch {
        final String token;
        final int score;

        TemplateMatch(String token, int score) {
            this.token = token == null ? "" : token;
            this.score = score;
        }
    }

    //defines the PixelClass module boundary so related behavior stays readable inside one unit.
    private enum PixelClass {
        NONE,
        GRAY,
        WHITE
    }

    //defines the Component module boundary so related behavior stays readable inside one unit.
    private static final class Component {
        final int x1;
        final int x2;
        final int y1;
        final int y2;
        final int count;
        final int whiteCount;
        final double topAverageX;
        final double midAverageX;
        final double bottomAverageX;
        final int topCount;
        final int topWhiteCount;
        final int midCount;
        final int midWhiteCount;
        final int bottomCount;
        final int bottomWhiteCount;

        Component(int x1, int x2, int y1, int y2, int count, int whiteCount,
                double topAverageX, double midAverageX, double bottomAverageX) {
            this(x1, x2, y1, y2, count, whiteCount, topAverageX, midAverageX, bottomAverageX,
                    0, 0, 0, 0, 0, 0);
        }

        Component(int x1, int x2, int y1, int y2, int count, int whiteCount,
                double topAverageX, double midAverageX, double bottomAverageX,
                int topCount, int topWhiteCount,
                int midCount, int midWhiteCount,
                int bottomCount, int bottomWhiteCount) {
            this.x1 = x1;
            this.x2 = x2;
            this.y1 = y1;
            this.y2 = y2;
            this.count = count;
            this.whiteCount = whiteCount;
            this.topAverageX = topAverageX;
            this.midAverageX = midAverageX;
            this.bottomAverageX = bottomAverageX;
            this.topCount = topCount;
            this.topWhiteCount = topWhiteCount;
            this.midCount = midCount;
            this.midWhiteCount = midWhiteCount;
            this.bottomCount = bottomCount;
            this.bottomWhiteCount = bottomWhiteCount;
        }

        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        int width() {
            return x2 - x1 + 1;
        }

        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        int height() {
            return y2 - y1 + 1;
        }
    }

    //defines the DigitBlob module boundary so related behavior stays readable inside one unit.
    private static final class DigitBlob {
        final int x1;
        final int x2;
        final int y1;
        final int y2;
        final int count;

        DigitBlob(int x1, int x2, int y1, int y2, int count) {
            this.x1 = x1;
            this.x2 = x2;
            this.y1 = y1;
            this.y2 = y2;
            this.count = count;
        }

        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        int width() {
            return Math.max(1, x2 - x1);
        }

        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        int height() {
            return Math.max(1, y2 - y1);
        }
    }

    //defines the GlyphGeometry module boundary so related behavior stays readable inside one unit.
    private static final class GlyphGeometry {
        final Component component;
        final int width;
        final double midDelta;
        final double bottomDelta;

        GlyphGeometry(Component component) {
            this.component = component;
            this.width = component.width();
            this.midDelta = component.midAverageX - component.topAverageX;
            this.bottomDelta = component.bottomAverageX - component.topAverageX;
        }

        //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
        static GlyphGeometry from(Component component) {
            return component == null ? null : new GlyphGeometry(component);
        }
    }

    //defines the LaneCropProfile module boundary so related behavior stays readable inside one unit.
    private static final class LaneCropProfile {
        final int x1;
        final int y1;
        final int x2;
        final int y2;
        final int referenceHeight;
        final int minLaneX;

        LaneCropProfile(int x1, int y1, int x2, int y2, int referenceHeight, int minLaneX) {
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.referenceHeight = referenceHeight;
            this.minLaneX = minLaneX;
        }
    }

    //defines the ColorCounts module boundary so related behavior stays readable inside one unit.
    private static final class ColorCounts {
        int red;
        int white;
        int gray;
    }

    //defines the PartCue module boundary so related behavior stays readable inside one unit.
    private static final class PartCue {
        final String token;
        final boolean recommended;

        PartCue(String token, boolean recommended) {
            this.token = token;
            this.recommended = recommended;
        }

        //parses source data here so downstream HUD code receives normalized navigation fields.
        static PartCue parse(String rawToken) {
            String raw = rawToken == null ? "" : rawToken.trim();
            String token = raw.toUpperCase(Locale.ROOT);
            boolean recommended = token.startsWith("*") || token.startsWith("!")
                    || token.endsWith("*") || token.endsWith("!");
            token = canonicalToken(stripRecommendation(raw));
            if (!"S".equals(token) && !"L".equals(token) && !"R".equals(token)
                    && !"Ls".equals(token) && !"Rs".equals(token)
                    && !"RampL".equals(token) && !"RampR".equals(token)
                    && !"U".equals(token) && !"UR".equals(token)) {
                return null;
            }
            return new PartCue(token, recommended);
        }
    }
}
