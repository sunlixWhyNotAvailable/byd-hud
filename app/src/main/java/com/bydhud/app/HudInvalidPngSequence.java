package com.bydhud.app;

//guards against invalid PNG state so the cluster does not keep rendering broken maneuver art.

final class HudInvalidPngSequence {
    private static final int[] MODES = {
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BAD_1,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_FAKE_JPEG,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_ZERO_BYTES_34,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BAD_1,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_FAKE_JPEG,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_ZERO_BYTES_34
    };

    private static final String[] LABELS = {
            "PNG OEM", "Bad 1B",
            "PNG OEM", "Bad JPG",
            "PNG OEM", "Zero 34",
            "OEM Raw", "Bad 1B",
            "OEM Raw", "Bad JPG",
            "OEM Raw", "Zero 34"
    };

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private HudInvalidPngSequence() {
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int size() {
        return MODES.length;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int modeAt(int index) {
        return MODES[normalize(index)];
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static String labelAt(int index) {
        return LABELS[normalize(index)];
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static String roadLabelAt(int index) {
        int step = normalize(index);
        return String.format("IV%02d %s", step + 1, LABELS[step]);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int nextIndex(int index) {
        return (normalize(index) + 1) % MODES.length;
    }

    //normalizes values here so malformed app text cannot leak into HUD payloads.
    private static int normalize(int index) {
        int size = MODES.length;
        int normalized = index % size;
        return normalized < 0 ? normalized + size : normalized;
    }
}
