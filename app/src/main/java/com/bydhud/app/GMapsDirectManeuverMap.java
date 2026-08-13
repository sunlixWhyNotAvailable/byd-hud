package com.bydhud.app;

import com.bydhud.gmapswire.GmapsWireParser;

/** Versioned GMaps wire maneuver mapping for the direct HUD channel. */
final class GMapsDirectManeuverMap {
    private static final int SOURCE_BLANK = 72;
    private static final int NATIVE_BLANK = 99;

    private int lastClockwiseRoundaboutSource = SOURCE_BLANK;
    private int lastCounterClockwiseRoundaboutSource = SOURCE_BLANK;

    Result map(int wireValue, boolean usableDistance) {
        String name = GmapsWireParser.maneuverName(wireValue);
        if (name == null) {
            clearRoundaboutSequence();
            return result(wireValue, "UNKNOWN", 0, SOURCE_BLANK);
        }

        switch (name) {
            case "DEPART":
                clearRoundaboutSequence();
                // DEPART is blank on RoadInfo, but the dashboard contract uses AMap 20.
                return result(wireValue, name, 20, SOURCE_BLANK);
            case "NAME_CHANGE":
            case "STRAIGHT":
            case "FERRY_BOAT":
            case "FERRY_TRAIN":
            case "MERGE":
                clearRoundaboutSequence();
                return result(wireValue, name, 9, 9);
            case "KEEP_LEFT":
            case "TURN_SLIGHT_LEFT":
            case "ON_RAMP_SLIGHT_LEFT":
            case "OFF_RAMP_SLIGHT_LEFT":
            case "FORK_LEFT":
            case "MERGE_LEFT":
            case "ON_RAMP_KEEP_LEFT":
            case "OFF_RAMP_KEEP_LEFT":
                clearRoundaboutSequence();
                return result(wireValue, name, 4, 4);
            case "KEEP_RIGHT":
            case "TURN_SLIGHT_RIGHT":
            case "ON_RAMP_SLIGHT_RIGHT":
            case "OFF_RAMP_SLIGHT_RIGHT":
            case "FORK_RIGHT":
            case "MERGE_RIGHT":
            case "ON_RAMP_KEEP_RIGHT":
            case "OFF_RAMP_KEEP_RIGHT":
                clearRoundaboutSequence();
                return result(wireValue, name, 5, 5);
            case "TURN_NORMAL_LEFT":
            case "ON_RAMP_NORMAL_LEFT":
            case "OFF_RAMP_NORMAL_LEFT":
                clearRoundaboutSequence();
                return result(wireValue, name, 2, 2);
            case "TURN_NORMAL_RIGHT":
            case "ON_RAMP_NORMAL_RIGHT":
            case "OFF_RAMP_NORMAL_RIGHT":
                clearRoundaboutSequence();
                return result(wireValue, name, 3, 3);
            case "TURN_SHARP_LEFT":
            case "ON_RAMP_SHARP_LEFT":
            case "OFF_RAMP_SHARP_LEFT":
                clearRoundaboutSequence();
                return result(wireValue, name, 6, 6);
            case "TURN_SHARP_RIGHT":
            case "ON_RAMP_SHARP_RIGHT":
            case "OFF_RAMP_SHARP_RIGHT":
                clearRoundaboutSequence();
                return result(wireValue, name, 7, 7);
            case "U_TURN_LEFT":
            case "ON_RAMP_U_TURN_LEFT":
            case "OFF_RAMP_U_TURN_LEFT":
                clearRoundaboutSequence();
                return result(wireValue, name, 8, 8);
            case "U_TURN_RIGHT":
            case "ON_RAMP_U_TURN_RIGHT":
            case "OFF_RAMP_U_TURN_RIGHT":
                clearRoundaboutSequence();
                return result(wireValue, name, 19, 19);
            case "DESTINATION":
            case "DESTINATION_STRAIGHT":
            case "DESTINATION_LEFT":
            case "DESTINATION_RIGHT":
                clearRoundaboutSequence();
                return result(wireValue, name, 15, 10);
            case "ROUNDABOUT_ENTER_AND_EXIT_CW_SHARP_LEFT":
            case "ROUNDABOUT_ENTER_AND_EXIT_CW_NORMAL_LEFT":
                return exactRoundabout(wireValue, name, true, 25);
            case "ROUNDABOUT_ENTER_AND_EXIT_CW_STRAIGHT":
            case "ROUNDABOUT_ENTER_AND_EXIT_CW_SLIGHT_LEFT":
                return exactRoundabout(wireValue, name, true, 27);
            case "ROUNDABOUT_ENTER_AND_EXIT_CW_SHARP_RIGHT":
            case "ROUNDABOUT_ENTER_AND_EXIT_CW_NORMAL_RIGHT":
            case "ROUNDABOUT_ENTER_AND_EXIT_CW_SLIGHT_RIGHT":
                return exactRoundabout(wireValue, name, true, 26);
            case "ROUNDABOUT_ENTER_AND_EXIT_CW_U_TURN":
                return exactRoundabout(wireValue, name, true, 28);
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW_SHARP_RIGHT":
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW_NORMAL_RIGHT":
                return exactRoundabout(wireValue, name, false, 22);
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW_SLIGHT_RIGHT":
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW_STRAIGHT":
                return exactRoundabout(wireValue, name, false, 23);
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW_SHARP_LEFT":
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW_NORMAL_LEFT":
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW_SLIGHT_LEFT":
                return exactRoundabout(wireValue, name, false, 21);
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW_U_TURN":
                return exactRoundabout(wireValue, name, false, 24);
            case "ROUNDABOUT_EXIT_CW":
                return result(wireValue, name, 22, lastClockwiseRoundaboutSource, 18);
            case "ROUNDABOUT_EXIT_CCW":
                return result(wireValue, name, 25, lastCounterClockwiseRoundaboutSource, 12);
            case "ROUNDABOUT_ENTER_AND_EXIT_CW":
            case "ROUNDABOUT_ENTER_CW":
                return result(wireValue, name, 22, SOURCE_BLANK, 17);
            case "ROUNDABOUT_ENTER_AND_EXIT_CCW":
            case "ROUNDABOUT_ENTER_CCW":
                return result(wireValue, name, 25, SOURCE_BLANK, 11);
            default:
                clearRoundaboutSequence();
                return result(wireValue, name, 0, SOURCE_BLANK);
        }
    }

    void reset() {
        clearRoundaboutSequence();
    }

    private Result exactRoundabout(int wireValue, String name, boolean clockwise, int source) {
        if (clockwise) {
            lastClockwiseRoundaboutSource = source;
            lastCounterClockwiseRoundaboutSource = SOURCE_BLANK;
        } else {
            lastCounterClockwiseRoundaboutSource = source;
            lastClockwiseRoundaboutSource = SOURCE_BLANK;
        }
        return result(wireValue, name, clockwise ? 22 : 25, source, source);
    }

    private static Result result(int wireValue, String name, int intermediate, int source) {
        return result(wireValue, name, intermediate, source, intermediate);
    }

    private static Result result(int wireValue, String name, int intermediate,
            int source, int amapBroadcastManeuver) {
        return new Result(wireValue, name, intermediate, source, nativeFor(intermediate),
                amapBroadcastManeuver, 0);
    }

    /*
     * Intermediate legend:
     * 0 unknown/blank; 2 normal left; 3 normal right; 4 slight/keep left;
     * 5 slight/keep right; 6 sharp left; 7 sharp right; 8 left U-turn;
     * 9 straight; 15 destination; 19 right U-turn; 22 clockwise roundabout;
     * 25 counter-clockwise roundabout.
     */
    private static int nativeFor(int intermediate) {
        switch (intermediate) {
            case 2: return 1;
            case 3: return 2;
            case 4: return 3;
            case 5: return 5;
            case 6: return 1;
            case 7: return 2;
            case 8: return 7;
            case 9: return 11;
            case 19: return 8;
            case 15:
            case 22:
            case 25:
            default: return NATIVE_BLANK;
        }
    }

    private void clearRoundaboutSequence() {
        lastClockwiseRoundaboutSource = SOURCE_BLANK;
        lastCounterClockwiseRoundaboutSource = SOURCE_BLANK;
    }

    static final class Result {
        final int wireValue;
        final String maneuverName;
        final int intermediate;
        final int fallbackSource;
        final int nativeManeuver;
        final int amapBroadcastManeuver;
        final int roundaboutExitNumber;

        Result(int wireValue, String maneuverName, int intermediate,
               int fallbackSource, int nativeManeuver,
               int amapBroadcastManeuver, int roundaboutExitNumber) {
            this.wireValue = wireValue;
            this.maneuverName = maneuverName;
            this.intermediate = intermediate;
            this.fallbackSource = fallbackSource;
            this.nativeManeuver = nativeManeuver;
            this.amapBroadcastManeuver = amapBroadcastManeuver;
            this.roundaboutExitNumber = roundaboutExitNumber;
        }
    }
}
