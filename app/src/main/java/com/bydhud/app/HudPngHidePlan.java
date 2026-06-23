package com.bydhud.app;

//plans PNG clear timing so stale images disappear when route evidence is lost.

final class HudPngHidePlan {
    //initializes owned dependencies here so later runtime work can avoid repeated setup.
    private HudPngHidePlan() {
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static int neutralModeForPrime(HudState state) {
        if (HudState.isNeutralTurnBitmapMode(state.turnBitmapMode)) {
            return state.turnBitmapMode;
        }
        return state.turnBitmapHiddenMode;
    }
}
