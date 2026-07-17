package com.bydhud.app;

//keeps output toggles persistent so testing can switch native, PNG, and log-only modes safely.

import android.content.Context;

//defines the HudOutputPreferences module boundary so related behavior stays readable inside one unit.
final class HudOutputPreferences {
    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private HudOutputPreferences() {
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static HudState applyToCopy(Context context, HudState rawState) {
        if (rawState == null) {
            return null;
        }
        HudState state = rawState.copy();
        apply(context, state);
        return state;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static void apply(Context context, HudState payloadState) {
        if (payloadState == null) {
            return;
        }
        if (!HudPrefs.isPngOutputEnabled(context)) {
            payloadState.hideTurnBitmapWithBlankSource();
        }
        if (!HudPrefs.isNativeOutputEnabled(context)) {
            payloadState.hideNativeWithBlankId();
        }
        if (!HudPrefs.isLaneOutputEnabled(context)) {
            payloadState.includeLaneBitmap = false;
            payloadState.numOfLanes = 0;
            payloadState.laneString = "";
        }
        if (!HudPrefs.isDistanceOutputEnabled(context)) {
            payloadState.distanceToIntersection = 0;
        }
        String street = payloadState.roadName;
        String direction = payloadState.directionText;
        payloadState.roadName = HudPrefs.isStreetOutputEnabled(context) && nonBlank(street)
                ? street
                : HudPrefs.isTextDirectionOutputEnabled(context) && nonBlank(direction)
                ? direction
                : "";
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
