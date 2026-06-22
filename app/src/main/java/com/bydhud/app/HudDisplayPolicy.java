package com.bydhud.app;

final class HudDisplayPolicy {
    static final int SMALL_DISTANCE_MIN_METERS = 20;

    private HudDisplayPolicy() {
    }

    static int displayDistanceMeters(int rawDistanceMeters, boolean clampSmallDistance) {
        if (!clampSmallDistance) {
            return rawDistanceMeters;
        }
        if (rawDistanceMeters >= 0 && rawDistanceMeters < SMALL_DISTANCE_MIN_METERS) {
            return SMALL_DISTANCE_MIN_METERS;
        }
        return rawDistanceMeters;
    }

    static HudState apply(HudState rawState, boolean clampSmallDistance) {
        if (rawState == null) {
            return null;
        }
        HudState displayState = rawState.copy();
        if (displayState.navigationStatus != 1) {
            displayState.distanceToIntersection =
                    displayDistanceMeters(displayState.distanceToIntersection, clampSmallDistance);
        }
        return displayState;
    }
}
