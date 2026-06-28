package com.bydhud.app;

//decides whether visual evidence is usable so stale crops do not override fresh route state.

final class WazeVisualStatePolicy {
    private static final long VISUAL_STICKY_MS = NavRouteStateStore.ROUTE_EVIDENCE_TTL_MS;
    private static final int WAZE_LEFT_45_SOURCE_ID = 4;
    private static final int WAZE_RIGHT_45_SOURCE_ID = 5;
    private static final int WAZE_STRAIGHT_SOURCE_ID = 9;
    private static final int WAZE_ROUNDABOUT_SOURCE_ID = 21;
    private static final int WAZE_ROUNDABOUT_RIGHT_HAND_MIN_SOURCE_ID = 50;
    private static final int WAZE_ROUNDABOUT_LEFT_HAND_MAX_SOURCE_ID = 69;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private WazeVisualStatePolicy() {
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldPreserveWazeVisual(String packageName,
            HudState latestVisualState,
            NavParserResult incoming,
            long visualAgeMs) {
        if (!"com.waze".equals(packageName)
                || latestVisualState == null
                || incoming == null
                || incoming.snapshot == null
                || incoming.snapshot.maneuver != NavSnapshot.Maneuver.UNKNOWN
                || visualAgeMs < 0L
                || visualAgeMs > VISUAL_STICKY_MS) {
            return false;
        }
        return latestVisualState.turnBitmapId != HudState.TURN_BITMAP_BLANK_SOURCE_ID;
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    static HudState mergeRouteFieldsKeepingVisual(HudState visualState, HudState routeState) {
        return mergeRouteFieldsKeepingVisual(
                visualState, routeState, NavSnapshot.Maneuver.UNKNOWN);
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    static HudState mergeRouteFieldsKeepingVisual(
            HudState visualState, HudState routeState, NavSnapshot.Maneuver routeManeuver) {
        if (visualState == null) {
            return routeState;
        }
        if (routeState == null) {
            return visualState;
        }
        HudState merged = visualState.copy();
        if (shouldKeepRouteRoundaboutManeuver(visualState, routeState, routeManeuver)) {
            merged.turnBitmapId = routeState.turnBitmapId;
            merged.maneuverId = routeState.maneuverId;
            merged.turnBitmapMode = routeState.turnBitmapMode;
            merged.includeNativeArrow = routeState.includeNativeArrow;
            merged.turnBitmapHiddenLocked = routeState.turnBitmapHiddenLocked;
            merged.turnBitmapHiddenMode = routeState.turnBitmapHiddenMode;
        }
        merged.distanceToIntersection = routeState.distanceToIntersection;
        merged.navigationStatus = routeState.navigationStatus;
        merged.crossStatus = routeState.crossStatus;
        merged.carToDestination = routeState.carToDestination;
        merged.timeToDestination = routeState.timeToDestination;
        merged.currentMaxSpeedLimit = routeState.currentMaxSpeedLimit;
        merged.currentSpeed = routeState.currentSpeed;
        merged.roadName = routeState.roadName;
        merged.guidePoint = routeState.guidePoint;
        merged.navigationRatio = routeState.navigationRatio;
        return merged;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean shouldKeepRouteRoundaboutManeuver(
            HudState visualState, HudState routeState, NavSnapshot.Maneuver routeManeuver) {
        return isRouteRoundabout(routeState, routeManeuver)
                && isSimpleVisualManeuver(visualState);
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isRouteRoundabout(HudState routeState, NavSnapshot.Maneuver routeManeuver) {
        if (routeManeuver == NavSnapshot.Maneuver.ROUNDABOUT_RIGHT_EXIT
                || routeManeuver == NavSnapshot.Maneuver.ROUNDABOUT_LEFT_EXIT) {
            return true;
        }
        return routeManeuver == NavSnapshot.Maneuver.UNKNOWN
                && (routeState.turnBitmapId == WAZE_ROUNDABOUT_SOURCE_ID
                || isNumberedRoundaboutSource(routeState.turnBitmapId));
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isNumberedRoundaboutSource(int sourceId) {
        return sourceId >= WAZE_ROUNDABOUT_RIGHT_HAND_MIN_SOURCE_ID
                && sourceId <= WAZE_ROUNDABOUT_LEFT_HAND_MAX_SOURCE_ID;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    private static boolean isSimpleVisualManeuver(HudState visualState) {
        return visualState.turnBitmapId == WAZE_LEFT_45_SOURCE_ID
                || visualState.turnBitmapId == WAZE_RIGHT_45_SOURCE_ID
                || visualState.turnBitmapId == WAZE_STRAIGHT_SOURCE_ID;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldClearVisualWhenCropUnavailable(
            boolean activeHud, String reason, long unavailableMs) {
        return activeHud
                && shouldTreatCropUnavailableAsVisualMissing(activeHud, reason)
                && unavailableMs >= 0L;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldTreatCropUnavailableAsRouteEnd(boolean activeHud, String reason) {
        if (!activeHud) {
            return false;
        }
        String safe = NavTextNormalizer.lower(reason);
        return safe.contains("no-route") || safe.contains("route-ended");
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean shouldTreatCropUnavailableAsVisualMissing(boolean activeHud, String reason) {
        if (!activeHud) {
            return false;
        }
        String safe = NavTextNormalizer.lower(reason);
        return safe.contains("background")
                || safe.contains("no-cue")
                || safe.contains("not-visible")
                || safe.contains("display")
                || safe.contains("screencap");
    }

    //keeps this Waze step isolated so visual and accessibility evidence can be debugged independently.
    static HudState routeOnlyWithoutVisual(HudState routeState) {
        if (routeState == null) {
            return null;
        }
        HudState state = routeState.copy();
        state.turnBitmapId = HudState.TURN_BITMAP_BLANK_SOURCE_ID;
        state.maneuverId = HudState.NATIVE_BLANK_ID;
        state.numOfLanes = 0;
        state.includeLaneBitmap = false;
        state.laneString = "";
        return state;
    }

    //clears stale route text while keeping crop-derived maneuver and lane evidence visible.
    static HudState staleRouteFieldsClearedForVisual(HudState visualState) {
        if (visualState == null) {
            return null;
        }
        HudState state = visualState.copy();
        state.distanceToIntersection = 0;
        state.carToDestination = 0;
        state.timeToDestination = 0;
        state.roadName = "";
        state.guidePoint = "";
        state.navigationRatio = 0.0d;
        return state;
    }

    //clears state here so stale navigation output is removed before new evidence arrives.
    static HudState clearLanesForCurrentUnknownRow(HudState state) {
        if (state == null) {
            return null;
        }
        HudState cleared = state.copy();
        cleared.numOfLanes = 0;
        cleared.includeLaneBitmap = false;
        cleared.laneString = "";
        return cleared;
    }
}
