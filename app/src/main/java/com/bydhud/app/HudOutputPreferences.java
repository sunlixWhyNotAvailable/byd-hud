package com.bydhud.app;

import android.content.Context;

final class HudOutputPreferences {
    private HudOutputPreferences() {
    }

    static HudState applyToCopy(Context context, HudState rawState) {
        if (rawState == null) {
            return null;
        }
        HudState state = rawState.copy();
        apply(context, state);
        return state;
    }

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
        if (!HudPrefs.isStreetOutputEnabled(context)) {
            payloadState.roadName = "";
        }
    }
}
