package com.bydhud.app;

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

    private HudPrimeOmitSequence() {
    }

    static int size() {
        return MODES.length;
    }

    static int modeAt(int index) {
        return MODES[normalize(index)];
    }

    static boolean isPrimeStep(int index) {
        return (normalize(index) % 2) == 1;
    }

    static String labelAt(int index) {
        return LABELS[normalize(index)];
    }

    static String roadLabelAt(int index) {
        int step = normalize(index);
        return String.format("PO%02d %s", step + 1, LABELS[step]);
    }

    static int nextIndex(int index) {
        return (normalize(index) + 1) % MODES.length;
    }

    private static int normalize(int index) {
        int size = MODES.length;
        int normalized = index % size;
        return normalized < 0 ? normalized + size : normalized;
    }
}
