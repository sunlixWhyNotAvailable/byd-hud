package com.bydhud.app;

//maps app-specific maneuvers to HUD codes so every source speaks the same cluster protocol.

final class HudManeuverMap {
    private static final int[] SOURCE_TO_NATIVE = {
            99, 99, 1, 2, 3, 5, 1, 2, 7, 11,
            99, 11, 11, 99, 99, 99, 99, 11, 11, 8,
            11, 99, 99, 11, 99, 99, 99, 11, 99
    };

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private HudManeuverMap() {
    }

    // Normalized circle families: 22 is clockwise, 25 is counter-clockwise.
    static int roundaboutToNative(int maneuver, RoundaboutExit.Direction direction) {
        if (maneuver != 22 && maneuver != 25) return HudState.NATIVE_BLANK_ID;
        boolean clockwise = maneuver == 22;
        switch (direction) {
            case LEFT: return clockwise ? 16 : 15;
            case STRAIGHT: return clockwise ? 19 : 20;
            case RIGHT: return clockwise ? 17 : 18;
            case U_TURN: return clockwise ? 21 : 22;
            default: return HudState.NATIVE_BLANK_ID;
        }
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int sourceToNative(int sourceId) {
        if (sourceId >= 29 && sourceId <= 69) {
            return HudState.NATIVE_BLANK_ID;
        }
        if (sourceId == 70) {
            return 2;
        }
        if (sourceId == 71) {
            return 1;
        }
        if (sourceId >= 72 && sourceId <= 99) {
            return HudState.NATIVE_BLANK_ID;
        }
        if (sourceId < 0 || sourceId >= SOURCE_TO_NATIVE.length) {
            return sourceId;
        }
        return SOURCE_TO_NATIVE[sourceId];
    }

}
