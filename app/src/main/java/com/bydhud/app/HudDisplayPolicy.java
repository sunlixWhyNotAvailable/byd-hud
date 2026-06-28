package com.bydhud.app;

//chooses what the HUD should show so stale navigation data is cleared before it misleads the driver.

final class HudDisplayPolicy {
    static final int SMALL_DISTANCE_MIN_METERS = 20;

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private HudDisplayPolicy() {
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int displayDistanceMeters(int rawDistanceMeters, boolean clampSmallDistance) {
        if (!clampSmallDistance) {
            return rawDistanceMeters;
        }
        if (rawDistanceMeters >= 0 && rawDistanceMeters < SMALL_DISTANCE_MIN_METERS) {
            return SMALL_DISTANCE_MIN_METERS;
        }
        return rawDistanceMeters;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static HudState apply(HudState rawState, boolean clampSmallDistance) {
        if (rawState == null) {
            return null;
        }
        HudState displayState = rawState.copy();
        //preserves explicit zero so stale-distance clearing is not shown as a fake 20 m.
        if (displayState.navigationStatus != 1
                && displayState.distanceToIntersection != 0) {
            displayState.distanceToIntersection =
                    displayDistanceMeters(displayState.distanceToIntersection, clampSmallDistance);
        }
        return displayState;
    }
}
