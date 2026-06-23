package com.bydhud.app;

//suppresses redundant prime frames so HUD updates stay fast on the car bus.

final class HudPrimeOmitSequence {
    private static final int[] MODES = {
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BLANK_TRANSPARENT_1,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BLACK_JPEG_1,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BLACK_RGB_PNG_1,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BLACK_JPEG_34,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BLACK_RGB_PNG_34,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BLACK_34,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BLANK_TRANSPARENT_34,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_EMPTY_FIELD,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BAD_1,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_FAKE_JPEG,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_ZERO_BYTES_34,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BLANK_TRANSPARENT_1,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BLACK_JPEG_1,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BLACK_RGB_PNG_1,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BLACK_JPEG_34,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BLACK_RGB_PNG_34,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BLACK_34,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BLANK_TRANSPARENT_34,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_EMPTY_FIELD,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BAD_1,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_FAKE_JPEG,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_ZERO_BYTES_34
    };

    private static final String[] LABELS = {
            "PNG OEM", "Prime Blank 1T",
            "PNG OEM", "Prime JPG 1B",
            "PNG OEM", "Prime RGB 1B",
            "PNG OEM", "Prime JPG 34B",
            "PNG OEM", "Prime RGB 34B",
            "PNG OEM", "Prime Black 34",
            "PNG OEM", "Prime Blank 34T",
            "PNG OEM", "Prime Empty F8",
            "PNG OEM", "Prime Bad 1B",
            "PNG OEM", "Prime Bad JPG",
            "PNG OEM", "Prime Zero 34",
            "OEM Raw", "Prime Blank 1T",
            "OEM Raw", "Prime JPG 1B",
            "OEM Raw", "Prime RGB 1B",
            "OEM Raw", "Prime JPG 34B",
            "OEM Raw", "Prime RGB 34B",
            "OEM Raw", "Prime Black 34",
            "OEM Raw", "Prime Blank 34T",
            "OEM Raw", "Prime Empty F8",
            "OEM Raw", "Prime Bad 1B",
            "OEM Raw", "Prime Bad JPG",
            "OEM Raw", "Prime Zero 34"
    };

    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private HudPrimeOmitSequence() {
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int size() {
        return MODES.length;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int modeAt(int index) {
        return MODES[normalize(index)];
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isPrimeStep(int index) {
        return (normalize(index) % 2) == 1;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static String labelAt(int index) {
        return LABELS[normalize(index)];
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static String roadLabelAt(int index) {
        int step = normalize(index);
        return String.format("PO%02d %s", step + 1, LABELS[step]);
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
