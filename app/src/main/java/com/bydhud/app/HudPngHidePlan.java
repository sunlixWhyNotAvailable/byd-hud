package com.bydhud.app;

final class HudPngHidePlan {
    private HudPngHidePlan() {
    }

    static int neutralModeForPrime(HudState state) {
        if (HudState.isNeutralTurnBitmapMode(state.turnBitmapMode)) {
            return state.turnBitmapMode;
        }
        return state.turnBitmapHiddenMode;
    }
}
