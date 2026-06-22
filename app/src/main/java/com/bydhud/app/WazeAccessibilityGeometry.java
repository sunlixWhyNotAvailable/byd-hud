package com.bydhud.app;

import android.graphics.Rect;

import java.util.List;

final class WazeAccessibilityGeometry {
    static final WazeAccessibilityGeometry EMPTY = new WazeAccessibilityGeometry(null, null);

    final Rect directionBounds;
    final Rect laneGuidanceBounds;

    WazeAccessibilityGeometry(Rect directionBounds, Rect laneGuidanceBounds) {
        this.directionBounds = copyValid(directionBounds);
        this.laneGuidanceBounds = copyValid(laneGuidanceBounds);
    }

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

    boolean hasAnyBounds() {
        return directionBounds != null || laneGuidanceBounds != null;
    }

    boolean hasDirectionBounds() {
        return directionBounds != null;
    }

    boolean hasLaneGuidanceBounds() {
        return laneGuidanceBounds != null;
    }

    String summary() {
        return "direction=" + rectSummary(directionBounds)
                + " lanes=" + rectSummary(laneGuidanceBounds);
    }

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

    private static Rect copyValid(Rect rect) {
        if (rect == null || rect.width() < 8 || rect.height() < 8) {
            return null;
        }
        return new Rect(rect);
    }

    private static String rectSummary(Rect rect) {
        if (rect == null) {
            return "none";
        }
        return rect.left + "," + rect.top + "," + rect.right + "," + rect.bottom;
    }
}
