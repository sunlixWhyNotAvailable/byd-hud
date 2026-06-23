package com.bydhud.app;

//serializes HUD hide commands so repeated clears do not race with fresh route output.

final class HudHideLockSequence {
    private static final int[] SOURCE_IDS = {
            9, 9, 70, 71, 50, 9
    };

    private static final boolean[] LOCKED = {
            false, true, true, true, true, false
    };

    private static final String[] LABELS = {
            "Baseline OEM S09",
            "Hide 1T S09",
            "Hide 1T S70",
            "Hide 1T S71",
            "Hide 1T S50",
            "Unlock OEM S09"
    };

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private HudHideLockSequence() {
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int size() {
        return SOURCE_IDS.length;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int sourceAt(int index) {
        return SOURCE_IDS[normalize(index)];
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static boolean lockedAt(int index) {
        return LOCKED[normalize(index)];
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static String labelAt(int index) {
        return LABELS[normalize(index)];
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static String roadLabelAt(int index) {
        int step = normalize(index);
        return String.format("HL%02d %s", step + 1, LABELS[step]);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int nextIndex(int index) {
        return (normalize(index) + 1) % SOURCE_IDS.length;
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static int normalize(int index) {
        int size = SOURCE_IDS.length;
        int normalized = index % size;
        return normalized < 0 ? normalized + size : normalized;
    }
}
