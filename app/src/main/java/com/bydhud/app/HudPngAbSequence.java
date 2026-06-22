package com.bydhud.app;

final class HudPngAbSequence {
    private static final int[] MODES = {
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BLACK_JPEG_34,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BLACK_JPEG_1,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BLACK_RGB_PNG_34,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BLACK_RGB_PNG_1,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BLACK_34,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BLANK_TRANSPARENT_34,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BLANK_TRANSPARENT_1,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_EMPTY_FIELD,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_BAD_1,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_FAKE_JPEG,
            HudState.TURN_BITMAP_OEM, HudState.TURN_BITMAP_ZERO_BYTES_34,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BLACK_JPEG_34,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BLACK_JPEG_1,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BLACK_RGB_PNG_34,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BLACK_RGB_PNG_1,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BLACK_34,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BLANK_TRANSPARENT_34,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BLANK_TRANSPARENT_1,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_EMPTY_FIELD,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_BAD_1,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_FAKE_JPEG,
            HudState.TURN_BITMAP_OEM_RAW, HudState.TURN_BITMAP_ZERO_BYTES_34
    };

    private static final String[] LABELS = {
            "PNG OEM", "JPG 34B",
            "PNG OEM", "JPG 1B",
            "PNG OEM", "RGB 34B",
            "PNG OEM", "RGB 1B",
            "PNG OEM", "Black 34",
            "PNG OEM", "Blank 34T",
            "PNG OEM", "Blank 1T",
            "PNG OEM", "Empty F8",
            "PNG OEM", "Bad 1B",
            "PNG OEM", "Bad JPG",
            "PNG OEM", "Zero 34",
            "OEM Raw", "JPG 34B",
            "OEM Raw", "JPG 1B",
            "OEM Raw", "RGB 34B",
            "OEM Raw", "RGB 1B",
            "OEM Raw", "Black 34",
            "OEM Raw", "Blank 34T",
            "OEM Raw", "Blank 1T",
            "OEM Raw", "Empty F8",
            "OEM Raw", "Bad 1B",
            "OEM Raw", "Bad JPG",
            "OEM Raw", "Zero 34"
    };

    private HudPngAbSequence() {
    }

    static int size() {
        return MODES.length;
    }

    static int modeAt(int index) {
        return MODES[normalize(index)];
    }

    static String labelAt(int index) {
        return LABELS[normalize(index)];
    }

    static String roadLabelAt(int index) {
        int step = normalize(index);
        return String.format("AB%02d %s", step + 1, LABELS[step]);
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
