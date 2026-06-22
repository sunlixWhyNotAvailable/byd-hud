package com.bydhud.app;

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

    private HudHideLockSequence() {
    }

    static int size() {
        return SOURCE_IDS.length;
    }

    static int sourceAt(int index) {
        return SOURCE_IDS[normalize(index)];
    }

    static boolean lockedAt(int index) {
        return LOCKED[normalize(index)];
    }

    static String labelAt(int index) {
        return LABELS[normalize(index)];
    }

    static String roadLabelAt(int index) {
        int step = normalize(index);
        return String.format("HL%02d %s", step + 1, LABELS[step]);
    }

    static int nextIndex(int index) {
        return (normalize(index) + 1) % SOURCE_IDS.length;
    }

    private static int normalize(int index) {
        int size = SOURCE_IDS.length;
        int normalized = index % size;
        return normalized < 0 ? normalized + size : normalized;
    }
}
