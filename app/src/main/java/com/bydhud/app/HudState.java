package com.bydhud.app;

//keeps last HUD output state so send policy can avoid stale or duplicated cluster writes.

final class HudState {
    static final int NATIVE_BLANK_ID = 99;
    static final int TURN_BITMAP_BLANK_SOURCE_ID = 72;

    static final int TURN_BITMAP_OMIT = 0;
    static final int TURN_BITMAP_OEM = 1;
    static final int TURN_BITMAP_BLANK_TRANSPARENT_34 = 2;
    static final int TURN_BITMAP_BLANK_TRANSPARENT_120 = 3;
    static final int TURN_BITMAP_BLACK_120 = 4;
    static final int TURN_BITMAP_EMPTY_FIELD = 5;
    static final int TURN_BITMAP_BLACK_34 = 6;
    static final int TURN_BITMAP_BLACK_JPEG_34 = 7;
    static final int TURN_BITMAP_BLACK_RGB_PNG_34 = 8;
    static final int TURN_BITMAP_OEM_RAW = 9;
    static final int TURN_BITMAP_BLACK_JPEG_1 = 10;
    static final int TURN_BITMAP_BLACK_RGB_PNG_1 = 11;
    static final int TURN_BITMAP_BLANK_TRANSPARENT_1 = 12;
    static final int TURN_BITMAP_BAD_1 = 13;
    static final int TURN_BITMAP_FAKE_JPEG = 14;
    static final int TURN_BITMAP_ZERO_BYTES_34 = 15;

    int distanceToIntersection = 80;
    int maneuverId = 11;
    int turnBitmapId = 9;
    int turnBitmapMode = TURN_BITMAP_OEM;
    int navigationStatus = 2;
    int crossStatus = 2;
    int carToDestination = 1200;
    int timeToDestination = 180;
    int currentMaxSpeedLimit = 50;
    int currentSpeed = 21;
    int numOfLanes = 3;
    int laneBitmapScale = 1;
    int laneStrokePx = 5;
    int laneIconScalePercent = 160;
    int laneCanvasScalePercent = 50;
    int laneGapPx = 10;
    String roadName = "N11 S09";
    String laneString = "L|S*|R";
    String guidePoint = "30.5240,50.4505,0";
    double navigationRatio = 0.250d;
    boolean includeNativeArrow = true;
    boolean includeLaneBitmap = false;
    boolean turnBitmapHiddenLocked = false;
    int turnBitmapHiddenMode = TURN_BITMAP_BLANK_TRANSPARENT_1;

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    HudState copy() {
        HudState state = new HudState();
        state.distanceToIntersection = distanceToIntersection;
        state.maneuverId = maneuverId;
        state.turnBitmapId = turnBitmapId;
        state.turnBitmapMode = turnBitmapMode;
        state.navigationStatus = navigationStatus;
        state.crossStatus = crossStatus;
        state.carToDestination = carToDestination;
        state.timeToDestination = timeToDestination;
        state.currentMaxSpeedLimit = currentMaxSpeedLimit;
        state.currentSpeed = currentSpeed;
        state.numOfLanes = numOfLanes;
        state.laneBitmapScale = laneBitmapScale;
        state.laneStrokePx = laneStrokePx;
        state.laneIconScalePercent = laneIconScalePercent;
        state.laneCanvasScalePercent = laneCanvasScalePercent;
        state.laneGapPx = laneGapPx;
        state.roadName = roadName;
        state.laneString = laneString;
        state.guidePoint = guidePoint;
        state.navigationRatio = navigationRatio;
        state.includeNativeArrow = includeNativeArrow;
        state.includeLaneBitmap = includeLaneBitmap;
        state.turnBitmapHiddenLocked = turnBitmapHiddenLocked;
        state.turnBitmapHiddenMode = turnBitmapHiddenMode;
        return state;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    HudState copyForClear() {
        HudState state = new HudState();
        state.distanceToIntersection = 0;
        state.maneuverId = NATIVE_BLANK_ID;
        state.turnBitmapId = TURN_BITMAP_BLANK_SOURCE_ID;
        state.turnBitmapMode = TURN_BITMAP_OEM;
        state.turnBitmapHiddenLocked = false;
        state.turnBitmapHiddenMode = turnBitmapHiddenMode;
        state.navigationStatus = 1;
        state.crossStatus = 2;
        state.carToDestination = 0;
        state.timeToDestination = 0;
        state.currentMaxSpeedLimit = 0;
        state.currentSpeed = 0;
        state.numOfLanes = 0;
        state.laneBitmapScale = laneBitmapScale;
        state.laneStrokePx = laneStrokePx;
        state.laneIconScalePercent = laneIconScalePercent;
        state.laneCanvasScalePercent = laneCanvasScalePercent;
        state.laneGapPx = laneGapPx;
        state.roadName = "";
        state.laneString = "";
        state.guidePoint = "";
        state.navigationRatio = 0.0d;
        state.includeNativeArrow = true;
        state.includeLaneBitmap = false;
        return state;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    String summary() {
        return "nativeId=" + maneuverId
                + " sourceId=" + turnBitmapId
                + " dist=" + distanceToIntersection
                + " status=" + navigationStatus
                + " lanes=" + numOfLanes
                + " field28=" + includeNativeArrow
                + " lanePng=" + includeLaneBitmap
                + " turnPng=" + turnBitmapModeName()
                + " pngLock=" + turnBitmapHiddenLocked
                + " pngHide=" + turnBitmapModeName(turnBitmapHiddenMode)
                + " laneSig=" + HudLaneModel.signature(this)
                + " laneStroke=" + laneStrokePx
                + " iconScale=" + laneIconScalePercent
                + " canvasScale=" + laneCanvasScalePercent
                + " gapPx=" + laneGapPx
                + " road=\"" + roadName + "\""
                + " lane=\"" + laneString + "\"";
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    String turnBitmapModeName() {
        return turnBitmapModeName(turnBitmapMode);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    static String turnBitmapModeName(int mode) {
        if (mode == TURN_BITMAP_OEM) {
            return "oem";
        }
        if (mode == TURN_BITMAP_OEM_RAW) {
            return "oemRaw";
        }
        if (mode == TURN_BITMAP_BLANK_TRANSPARENT_34) {
            return "blank34t";
        }
        if (mode == TURN_BITMAP_BLANK_TRANSPARENT_120) {
            return "blank120t";
        }
        if (mode == TURN_BITMAP_BLANK_TRANSPARENT_1) {
            return "blank1t";
        }
        if (mode == TURN_BITMAP_BLACK_120) {
            return "black120";
        }
        if (mode == TURN_BITMAP_BLACK_34) {
            return "black34";
        }
        if (mode == TURN_BITMAP_BLACK_JPEG_34) {
            return "black34jpg";
        }
        if (mode == TURN_BITMAP_BLACK_RGB_PNG_34) {
            return "black34rgb";
        }
        if (mode == TURN_BITMAP_BLACK_JPEG_1) {
            return "black1jpg";
        }
        if (mode == TURN_BITMAP_BLACK_RGB_PNG_1) {
            return "black1rgb";
        }
        if (mode == TURN_BITMAP_EMPTY_FIELD) {
            return "emptyF8";
        }
        if (mode == TURN_BITMAP_BAD_1) {
            return "bad1";
        }
        if (mode == TURN_BITMAP_FAKE_JPEG) {
            return "fakeJpg";
        }
        if (mode == TURN_BITMAP_ZERO_BYTES_34) {
            return "zero34";
        }
        return "omit";
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    void setSourceManeuver(int sourceId) {
        turnBitmapId = Math.max(0, Math.min(99, sourceId));
        maneuverId = Math.max(0, Math.min(99, HudManeuverMap.sourceToNative(turnBitmapId)));
        includeNativeArrow = true;
        turnBitmapMode = turnBitmapHiddenLocked
                ? turnBitmapHiddenMode
                : (turnBitmapMode == TURN_BITMAP_OEM_RAW ? TURN_BITMAP_OEM_RAW : TURN_BITMAP_OEM);
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    void setTurnBitmapMode(int mode) {
        turnBitmapMode = mode;
        if (mode == TURN_BITMAP_OEM || mode == TURN_BITMAP_OEM_RAW) {
            turnBitmapHiddenLocked = false;
        } else if (isNeutralTurnBitmapMode(mode)) {
            turnBitmapHiddenMode = mode;
        }
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    void lockTurnBitmapHidden() {
        turnBitmapHiddenLocked = true;
        if (isNeutralTurnBitmapMode(turnBitmapMode)) {
            turnBitmapHiddenMode = turnBitmapMode;
        }
        turnBitmapMode = turnBitmapHiddenMode;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    void lockTurnBitmapHiddenWithDefault() {
        turnBitmapHiddenLocked = true;
        turnBitmapHiddenMode = TURN_BITMAP_BLANK_TRANSPARENT_1;
        turnBitmapMode = turnBitmapHiddenMode;
    }

    //clears state here so stale navigation output is removed before new evidence arrives.
    void hideTurnBitmapWithBlankSource() {
        turnBitmapId = TURN_BITMAP_BLANK_SOURCE_ID;
        turnBitmapMode = TURN_BITMAP_OEM;
        turnBitmapHiddenLocked = false;
    }

    //clears state here so stale navigation output is removed before new evidence arrives.
    void hideNativeWithBlankId() {
        maneuverId = NATIVE_BLANK_ID;
        includeNativeArrow = true;
    }

    //keeps this HUD step isolated so cluster payload behavior stays predictable.
    void unlockTurnBitmapHidden() {
        turnBitmapHiddenLocked = false;
        turnBitmapMode = TURN_BITMAP_OEM;
    }

    //keeps this predicate explicit so safety checks can be audited without tracing callers.
    static boolean isNeutralTurnBitmapMode(int mode) {
        return mode == TURN_BITMAP_EMPTY_FIELD
                || mode == TURN_BITMAP_BLANK_TRANSPARENT_1
                || mode == TURN_BITMAP_BLANK_TRANSPARENT_34
                || mode == TURN_BITMAP_BLANK_TRANSPARENT_120
                || mode == TURN_BITMAP_BLACK_34
                || mode == TURN_BITMAP_BLACK_JPEG_34
                || mode == TURN_BITMAP_BLACK_RGB_PNG_34
                || mode == TURN_BITMAP_BLACK_JPEG_1
                || mode == TURN_BITMAP_BLACK_RGB_PNG_1
                || mode == TURN_BITMAP_BLACK_120
                || mode == TURN_BITMAP_BAD_1
                || mode == TURN_BITMAP_FAKE_JPEG
                || mode == TURN_BITMAP_ZERO_BYTES_34;
    }
}
