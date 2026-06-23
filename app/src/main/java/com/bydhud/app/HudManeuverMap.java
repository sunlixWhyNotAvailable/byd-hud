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

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int nativeToFirstSource(int nativeId) {
        for (int i = 0; i < SOURCE_TO_NATIVE.length; i++) {
            if (SOURCE_TO_NATIVE[i] == nativeId) {
                return i;
            }
        }
        return nativeId;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean hasKnownSource(int sourceId) {
        return sourceId >= 0 && sourceId < SOURCE_TO_NATIVE.length;
    }
}
