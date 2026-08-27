package com.bydhud.app;

/** Fixed native traffic-light vocabulary used by the HUD check only. */
final class HudCheckTrafficLight {
    static final int CLEAR = -1;
    static final int DISTANCE_FID = 1_139_871_760;
    static final int DISTANCE_METERS = 77;
    static final int INTERSECTION_COUNT = 3;

    static final int STATE_RED = 3;
    static final int STATE_GREEN = 4;
    static final int STATE_YELLOW = 5;
    static final int DESCRIPTION_COUNTDOWN = 1;
    static final int DESCRIPTION_PASS = 2;
    static final int DESCRIPTION_WAIT = 3;
    static final int DESCRIPTION_CAUTION = 4;
    static final int DIRECTION_LEFT = 1;
    static final int DIRECTION_RIGHT = 2;
    static final int DIRECTION_CWU = 3;
    static final int DIRECTION_STRAIGHT = 4;

    private static final int[][] SELECTORS = {
            {1_139_847_186, 1_139_847_189, 1_139_847_198, 1_139_847_201,
                    1_139_855_408, 1_139_847_192, 1_139_847_195},
            {1_139_847_204, 1_139_847_207, 1_139_847_216, 1_139_847_219,
                    1_139_855_416, 1_139_847_210, 1_139_847_213},
            {1_139_847_222, 1_139_847_225, 1_139_847_234, 1_139_847_237,
                    1_139_855_424, 1_139_847_228, 1_139_847_231}
    };
    private static final int[] CLEAR_VALUES = {1, 0, 0, 0, 0, 0, 0};

    private HudCheckTrafficLight() {
    }

    static boolean validSampleIndex(int sampleIndex) {
        return sampleIndex == CLEAR || sampleIndex >= 0 && sampleIndex <= 11;
    }

    static int[] selectors(int intersection) {
        if (intersection < 0 || intersection >= INTERSECTION_COUNT) {
            throw new IllegalArgumentException("invalid traffic-light intersection");
        }
        return SELECTORS[intersection].clone();
    }

    static int[] clearValues() {
        return CLEAR_VALUES.clone();
    }

    static int[] valuesForSample(int sampleIndex) {
        switch (sampleIndex) {
            case 0: return values(STATE_GREEN, DESCRIPTION_COUNTDOWN,
                    DIRECTION_STRAIGHT, 8);
            case 1: return values(STATE_RED, DESCRIPTION_COUNTDOWN,
                    DIRECTION_LEFT, 8);
            case 2: return values(STATE_YELLOW, DESCRIPTION_COUNTDOWN,
                    DIRECTION_RIGHT, 8);
            case 3: return values(STATE_GREEN, DESCRIPTION_COUNTDOWN,
                    DIRECTION_CWU, 8);
            case 4: return values(STATE_GREEN, DESCRIPTION_COUNTDOWN,
                    DIRECTION_STRAIGHT, 3);
            case 5: return values(STATE_GREEN, DESCRIPTION_COUNTDOWN,
                    DIRECTION_STRAIGHT, 2);
            case 6: return values(STATE_GREEN, DESCRIPTION_COUNTDOWN,
                    DIRECTION_STRAIGHT, 1);
            case 7: return values(STATE_GREEN, DESCRIPTION_COUNTDOWN,
                    DIRECTION_STRAIGHT, 0);
            case 8: return values(STATE_RED, DESCRIPTION_COUNTDOWN,
                    DIRECTION_STRAIGHT, 99);
            case 9: return values(STATE_GREEN, DESCRIPTION_PASS,
                    DIRECTION_STRAIGHT, 0);
            case 10: return values(STATE_RED, DESCRIPTION_WAIT,
                    DIRECTION_STRAIGHT, 0);
            case 11: return values(STATE_YELLOW, DESCRIPTION_CAUTION,
                    DIRECTION_STRAIGHT, 0);
            default: throw new IllegalArgumentException("unknown traffic-light sample");
        }
    }

    private static int[] values(int state, int description, int direction, int countdown) {
        return new int[]{state, description, direction, 0, countdown, 0, 0};
    }
}
