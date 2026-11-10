package com.bydhud.app;

/** Ordinary-meter contract traced from the retained SL06 naviauto and mode feedback. */
final class NaviautoLayout {
    static final int SET_MODE = 0x4C10A018;
    static final int READ_MODE = 0x40C03032;
    static final int READ_SR = 0x40C0B022;
    static final long TIMEOUT_MS = 3_000L;

    private NaviautoLayout() { }

    static String rejection(int operation, int srFlag, int observedMode) {
        if (operation != 1 && operation != 2 && operation != 4) {
            return "naviauto ordinary-meter operation unsupported: " + operation;
        }
        // Only flag 2 positively rules out the separate SR controller.
        if (srFlag != 2) {
            return srFlag == 1 ? "naviauto SR-capable configuration unsupported: active backend unverified"
                    : "naviauto layout backend unknown: srFlag=" + srFlag;
        }
        if (!validMode(observedMode)) {
            return "naviauto mode read unavailable or invalid: " + observedMode;
        }
        return "";
    }

    static boolean validMode(int mode) {
        return mode >= 1 && mode <= 4;
    }
}
