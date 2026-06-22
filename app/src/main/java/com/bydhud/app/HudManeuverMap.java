package com.bydhud.app;

final class HudManeuverMap {
    private static final int[] SOURCE_TO_NATIVE = {
            99, 99, 1, 2, 3, 5, 1, 2, 7, 11,
            99, 11, 11, 99, 99, 99, 99, 11, 11, 8,
            11, 99, 99, 11, 99, 99, 99, 11, 99
    };

    private HudManeuverMap() {
    }

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

    static int nativeToFirstSource(int nativeId) {
        for (int i = 0; i < SOURCE_TO_NATIVE.length; i++) {
            if (SOURCE_TO_NATIVE[i] == nativeId) {
                return i;
            }
        }
        return nativeId;
    }

    static boolean hasKnownSource(int sourceId) {
        return sourceId >= 0 && sourceId < SOURCE_TO_NATIVE.length;
    }
}
