package com.bydhud.app;

//uses Waze accessibility geometry so parser fallback can locate route text without image matching.

import android.graphics.Rect;

import java.util.List;

//defines the WazeAccessibilityGeometry module boundary so related behavior stays readable inside one unit.
final class WazeAccessibilityGeometry {
    static final WazeAccessibilityGeometry EMPTY = new WazeAccessibilityGeometry(null, null);

    final Rect directionBounds;
    final Rect laneGuidanceBounds;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    WazeAccessibilityGeometry(Rect directionBounds, Rect laneGuidanceBounds) {
        this.directionBounds = copyValid(directionBounds);
        this.laneGuidanceBounds = copyValid(laneGuidanceBounds);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    static WazeAccessibilityGeometry fromPayload(String payload) {
        List<NavAccessibilityPayload.Node> nodes = NavAccessibilityPayload.nodes(payload);
        if (nodes.isEmpty()) {
            return EMPTY;
        }
        Rect direction = null;
        Rect lanes = null;
        for (NavAccessibilityPayload.Node node : nodes) {
            String idLower = NavTextNormalizer.lower(node.id);
            Rect bounds = parseBounds(node.bounds);
            if (bounds == null) {
                continue;
            }
            if (idLower.endsWith(":id/navbardirection")) {
                direction = bounds;
            } else if (idLower.endsWith(":id/laneguidanceview")
                    || idLower.contains("laneguidance")) {
                lanes = union(lanes, bounds);
            }
        }
        return new WazeAccessibilityGeometry(direction, lanes);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean hasAnyBounds() {
        return directionBounds != null || laneGuidanceBounds != null;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean hasDirectionBounds() {
        return directionBounds != null;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    boolean hasLaneGuidanceBounds() {
        return laneGuidanceBounds != null;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    String summary() {
        return "direction=" + rectSummary(directionBounds)
                + " lanes=" + rectSummary(laneGuidanceBounds);
    }

    //parses source data here so downstream HUD code receives normalized navigation fields.
    private static Rect parseBounds(String value) {
        String clean = NavTextNormalizer.cleanText(value);
        if (clean.isEmpty()) {
            return null;
        }
        String[] parts = clean.split(",", -1);
        if (parts.length != 4) {
            return null;
        }
        try {
            Rect rect = new Rect(
                    Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()),
                    Integer.parseInt(parts[3].trim()));
            return copyValid(rect);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static Rect union(Rect current, Rect next) {
        Rect valid = copyValid(next);
        if (valid == null) {
            return current;
        }
        if (current == null) {
            return valid;
        }
        Rect out = new Rect(current);
        out.union(valid);
        return copyValid(out);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static Rect copyValid(Rect rect) {
        if (rect == null || rect.width() < 8 || rect.height() < 8) {
            return null;
        }
        return new Rect(rect);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    private static String rectSummary(Rect rect) {
        if (rect == null) {
            return "none";
        }
        return rect.left + "," + rect.top + "," + rect.right + "," + rect.bottom;
    }
}
